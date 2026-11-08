package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RatchetRepositoryRuntimeTest {
    private class Identity(private val own:MessagingIdentity):MessagingIdentityStore {
        override fun read()=own
        override fun save(identity:MessagingIdentity) {check(identity==own)}
    }
    private class Relay(val users:List<MessagingIdentity>):MessagingRelay {
        override val configured=true
        val messages=linkedMapOf<String,JSONObject>()
        val events=linkedMapOf<String,Pair<String,JSONObject>>()
        val bundles=mutableMapOf<String,JSONObject>()
        val sends=mutableListOf<String>()
        var loseFirst=true
        var loseRenewal=false
        var renewal:JSONObject?=null
        private fun state(own:MessagingIdentity):JSONObject {
            val other=peer(own)
            val a=bundles[own.id];val b=bundles[other.id]
            return JSONObject().put("ready",a!=null && b!=null).put("epoch",if(a!=null && b!=null) RatchetProtocol.epoch(a,b) else JSONObject.NULL)
                .put("ownBundle",a ?: JSONObject.NULL).put("peerBundle",b ?: JSONObject.NULL)
                .put("pending",renewal?.takeIf {it.getString("state")=="pending"} ?: JSONObject.NULL)
                .put("completed",renewal?.takeIf {it.getString("state")=="active"} ?: JSONObject.NULL)
                .put("hasProtectedHistory",messages.isNotEmpty()).put("retiredIncoming",0).put("retiredOutgoing",0)
        }
        private fun peer(own:MessagingIdentity)=users.single {it.id!=own.id}
        private fun publicPeer(user:MessagingIdentity)=JSONObject().put("id",user.id).put("code",user.code)
            .put("publicKey",user.publicKey).put("nonce",user.nonce).put("mutual",true)
        private fun emit(message:JSONObject) {
            for(user in users) events[UUID.randomUUID().toString()]=user.id to JSONObject(message.toString())
        }
        override suspend fun request(identity:MessagingIdentity,action:String,data:JSONObject):JSONObject=when(action) {
            "register" -> JSONObject().put("code",identity.code).put("nonce",identity.nonce)
            "sync" -> {
                val list=JSONArray()
                for((id,pair) in events) {
                    if(pair.first!=identity.id) continue
                    if(pair.second.getString("recipientId")==identity.id && pair.second.optInt("protocolVersion")==3 &&
                        pair.second.getString("ratchetEpoch")!=state(identity).getString("epoch")) continue
                    if(loseFirst && pair.second.getString("senderId")==identity.id) continue
                    list.put(JSONObject().put("id",id).put("message",pair.second))
                }
                JSONObject().put("contacts",JSONArray().put(publicPeer(peer(identity)))).put("events",list)
            }
            "publishRatchetBundle" -> {
                val other=peer(identity)
                RatchetProtocol.verify(data.getJSONObject("bundle"),identity.id,other.id,identity.publicKey)
                bundles[identity.id]=data.getJSONObject("bundle")
                JSONObject().put("accepted",true).put("ready",bundles.containsKey(other.id))
                    .put("peerBundle",bundles[other.id] ?: JSONObject.NULL)
            }
            "ratchetState" -> state(identity)
            "renewRatchet" -> {
                val other=peer(identity);val id=data.getString("resetId");val previous=data.getString("previousEpoch")
                var row=renewal
                if(row==null) {
                    val sorted=users.map {it.id}.sorted()
                    row=JSONObject().put("id",id).put("previous_epoch",previous).put("state","pending")
                        .put("first_id",sorted[0]).put("second_id",sorted[1]).put("votes",JSONObject())
                    renewal=row
                }
                check(row.getString("id")==id && row.getString("previous_epoch")==previous)
                val votes=row.getJSONObject("votes")
                val bundle=data.getJSONObject("bundle")
                RatchetProtocol.verify(bundle,identity.id,other.id,identity.publicKey)
                RatchetProtocol.verifySignature(identity.publicKey,RatchetProtocol.resetTranscript(identity.id,other.id,id,previous,bundle),data.getString("proof"))
                votes.put(identity.id,JSONObject().put("bundle",bundle).put("proof",data.getString("proof")))
                if(votes.length()==2) {
                    for(user in users) bundles[user.id]=votes.getJSONObject(user.id).getJSONObject("bundle")
                    row.put("state","active").put("new_epoch",RatchetProtocol.epoch(bundles[identity.id]!!,bundles[other.id]!!))
                }
                if(loseRenewal && votes.length()==2) {loseRenewal=false;throw IOException("synthetic lost renewal response")}
                JSONObject().put("accepted",true).put("ready",votes.length()==2).put("receipt",row).put("state",state(identity))
            }
            "send" -> {
                check(data.getInt("protocolVersion")==3)
                val id=data.getString("messageId");val wire=data.getString("content")
                sends.add(wire)
                if(messages.containsKey(id)) check(messages[id]!!.getString("content")==wire)
                else {
                    val row=JSONObject().put("id",id).put("senderId",identity.id).put("recipientId",peer(identity).id)
                        .put("content",wire).put("createdAt",1L).put("revision",1L).put("status","SENT")
                        .put("deleted",false).put("reaction","").put("protocolVersion",3).put("ratchetEpoch",data.getString("ratchetEpoch"))
                    messages[id]=row;emit(row)
                }
                if(loseFirst) {loseFirst=false;throw IOException("synthetic lost acknowledgement")}
                JSONObject().put("status","SENT")
            }
            "ack" -> {
                val ids=data.getJSONArray("eventIds")
                for(n in 0 until ids.length()) {
                    val found=events[ids.getString(n)] ?: continue
                    if(found.first!=identity.id) continue
                    events.remove(ids.getString(n))
                    val message=messages[found.second.getString("id")] ?: continue
                    if(message.getString("recipientId")==identity.id && message.getString("status")=="SENT") {
                        message.put("status","DELIVERED").put("revision",2L);emit(message)
                    }
                }
                JSONObject().put("acknowledged",true)
            }
            else -> error("Unexpected test action")
        }
        fun inject(owner:String,message:JSONObject) {events[UUID.randomUUID().toString()]=owner to message}
    }
    private class Endpoint(val context:Context,val own:MessagingIdentity,val relay:Relay,val clock:()->Long):AutoCloseable {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val repository=CommunicationRepository(db.contactDao(),db.conversationDao(),db.messageDao(),db.settingsDao(),
            db.userProfileDao(),db.callLogDao(),Identity(own),relay,db,clock,ratchet=RatchetCoordinator(context,db))
        fun privateStateKey(peer:String):String=runBlocking {
            RatchetRootRegistry.parse(own,peer,db.settingsDao().getSettingValue(RatchetRootRegistry.pointerKey(peer))!!).stateKey
        }
        override fun close() {
            val namespaces=mutableListOf<String>()
            db.openHelper.writableDatabase.query("SELECT `key` FROM app_settings WHERE `key` GLOB 'lanu_dr_private_state_*'").use {
                while(it.moveToNext()) it.getString(0).removePrefix("lanu_dr_private_state_").takeIf {ns->ns.matches(Regex("[a-f0-9]{64}"))}?.let(namespaces::add)
            }
            db.close()
            val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            for(ns in namespaces) {
                store.deleteEntry("lanu_ratchet_wrap_v1_$ns")
                for(id in listOf(ns,RatchetTransactionStore.digest("LANU-KEY-WRAP|$ns"))) for(suffix in listOf("",".bak",".new"))
                    java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/$id.journal$suffix").delete()
            }
            for(peer in relay.users.filter {it.id!=own.id}) for(suffix in listOf("",".bak",".new"))
                java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/${RatchetRootRegistry.recordId(own.id,peer.id)}.journal$suffix").delete()
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("lanu_ratchet_wrap_v1_${own.id}")}
            val wrapped=MessageDigest.getInstance("SHA-256").digest("LANU-KEY-WRAP|${own.id}".toByteArray(Charsets.US_ASCII))
                .joinToString("") {"%02x".format(it)}
            for(id in listOf(own.id,wrapped)) for(suffix in listOf("",".bak",".new"))
                java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/$id.journal$suffix").delete()
        }
    }
    @Test fun realRepositoryPreservesLostAckCiphertextAndHistoryAndRejectsValidLegacyDowngrade()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val first=UserCodes.create();val second=UserCodes.create()
        val relay=Relay(listOf(first,second));var now=System.currentTimeMillis()
        Endpoint(context,first,relay){now}.use { a -> Endpoint(context,second,relay){now}.use { b ->
            val conversation=UserCodes.conversationId(first.id,second.id)
            for((local,remote) in listOf(a to second,b to first)) {
                local.repository.ensureIdentity()
                local.db.conversationDao().insertConversation(ConversationEntity(conversation,remote.code,"fixture",lastMessageTime=0))
                local.repository.synchronize(conversation)
            }
            assertFalse(a.repository.enableRatchet(conversation))
            assertTrue(b.repository.enableRatchet(conversation))
            assertTrue(a.repository.enableRatchet(conversation))
            a.repository.saveDraft(conversation,"repository ratchet fixture")
            val id=a.repository.sendMessage(conversation,"repository ratchet fixture")
            assertNull(a.db.settingsDao().getSettingValue("lanu_draft_$conversation"))
            a.repository.saveDraft(conversation,"a newer unsent draft")
            val before=a.db.settingsDao().getSettingValue(a.privateStateKey(second.id))
            a.repository.synchronize();now+=20000
            // A lost response is recovered from the sender's authenticated echo;
            // it must not ratchet again or create another message ID.
            a.repository.synchronize();b.repository.synchronize(conversation);a.repository.synchronize()
            assertEquals("a newer unsent draft",a.repository.loadDraft(conversation))
            assertEquals(1,relay.messages.size)
            assertTrue(relay.sends.isNotEmpty());assertTrue(relay.sends.all {it==relay.sends.first()})
            assertEquals(before,a.db.settingsDao().getSettingValue(a.privateStateKey(second.id)))
            assertEquals("repository ratchet fixture",b.repository.getMessages(conversation).first().single().content)
            assertTrue(b.db.messageDao().getMessage(id)!!.content.startsWith("lanu3-local:"))
            val rejected=UUID.randomUUID().toString()
            val legacy=PeerMessageCrypto.encrypt(second,first.id,first.publicKey,rejected,"forbidden downgrade")
            relay.inject(first.id,JSONObject().put("id",rejected).put("senderId",second.id).put("recipientId",first.id)
                .put("content",legacy).put("createdAt",2L).put("revision",1L).put("status","SENT").put("deleted",false))
            val forged=PeerMessageCrypto.encrypt(second,first.id,first.publicKey,id,"attempt to overwrite ratchet archive")
            relay.inject(first.id,JSONObject().put("id",id).put("senderId",second.id).put("recipientId",first.id)
                .put("content",forged).put("createdAt",3L).put("revision",99L).put("status","SENT").put("deleted",true))
            now+=20000
            assertTrue(runCatching {a.repository.synchronize(conversation)}.isFailure)
            assertNotNull(a.db.messageDao().getMessage(id))
            assertEquals("repository ratchet fixture",a.repository.getMessages(conversation).first().single().content)
            assertNull(a.db.messageDao().getMessage(rejected))
            assertEquals(before,a.db.settingsDao().getSettingValue(a.privateStateKey(second.id)))
        } }
    }
    @Test fun twoOwnerRenewalRecoversLostResponseAndPreservesOldHistoryAndOutbox()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val first=UserCodes.create();val second=UserCodes.create()
        val relay=Relay(listOf(first,second)).apply {loseFirst=false};var now=System.currentTimeMillis()
        Endpoint(context,first,relay){now}.use {a -> Endpoint(context,second,relay){now}.use {b ->
            val conversation=UserCodes.conversationId(first.id,second.id)
            for((local,remote) in listOf(a to second,b to first)) {
                local.repository.ensureIdentity()
                local.db.conversationDao().insertConversation(ConversationEntity(conversation,remote.code,"fixture"))
                local.repository.synchronize(conversation)
            }
            a.repository.enableRatchet(conversation);b.repository.enableRatchet(conversation);a.repository.enableRatchet(conversation)
            val old=a.repository.sendMessage(conversation,"old undelivered fixture")
            val oldWire=a.db.settingsDao().getSettingValue("lanu_dr_wire_$old")
            val oldStateKey=a.privateStateKey(second.id)
            val oldState=a.db.settingsDao().getSettingValue(oldStateKey)
            val oldEpoch=a.db.settingsDao().getSettingValue("lanu_dr_pin_${second.id}")
            assertFalse(a.repository.confirmRatchetRecovery(a.repository.ratchetRecoveryOffer(conversation)))
            assertEquals(oldEpoch,a.db.settingsDao().getSettingValue("lanu_dr_pin_${second.id}"))
            assertTrue(runCatching {a.repository.sendMessage(conversation,"blocked while renewing")}.isFailure)
            relay.loseRenewal=true
            assertTrue(runCatching {b.repository.confirmRatchetRecovery(b.repository.ratchetRecoveryOffer(conversation))}.isFailure)
            now+=31_000
            a.repository.synchronize(conversation);b.repository.synchronize(conversation)
            assertNotEquals(oldEpoch,a.db.settingsDao().getSettingValue("lanu_dr_pin_${second.id}"))
            assertEquals(a.db.settingsDao().getSettingValue("lanu_dr_pin_${second.id}"),b.db.settingsDao().getSettingValue("lanu_dr_pin_${first.id}"))
            assertNotEquals(oldStateKey,a.privateStateKey(second.id))
            assertEquals(oldState,a.db.settingsDao().getSettingValue(oldStateKey))
            assertEquals(oldWire,a.db.settingsDao().getSettingValue("lanu_dr_wire_$old"))
            assertEquals("WAITING",a.db.messageDao().getMessage(old)!!.status)
            assertEquals("old undelivered fixture",RatchetSessionService.history(first,old,a.db.messageDao().getMessage(old)!!.content))
            assertEquals(1,a.repository.resendRetiredRatchet(conversation))
            assertEquals(0,a.repository.resendRetiredRatchet(conversation))
            now+=31_000;a.repository.synchronize(conversation);b.repository.synchronize(conversation)
            val received=b.repository.getMessages(conversation).first()
            assertTrue(received.any {it.content=="old undelivered fixture"})
            assertNull(b.db.messageDao().getMessage(old))
        }}
    }

}
