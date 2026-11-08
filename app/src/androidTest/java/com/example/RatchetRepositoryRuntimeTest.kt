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
        private fun peer(own:MessagingIdentity)=users.single {it.id!=own.id}
        private fun public(user:MessagingIdentity)=JSONObject().put("id",user.id).put("code",user.code)
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
                    if(loseFirst && pair.second.getString("senderId")==identity.id) continue
                    list.put(JSONObject().put("id",id).put("message",pair.second))
                }
                JSONObject().put("contacts",JSONArray().put(public(peer(identity)))).put("events",list)
            }
            "publishRatchetBundle" -> {
                val other=peer(identity)
                RatchetProtocol.verify(data.getJSONObject("bundle"),identity.id,other.id,identity.publicKey)
                bundles[identity.id]=data.getJSONObject("bundle")
                JSONObject().put("accepted",true).put("ready",bundles.containsKey(other.id))
                    .put("peerBundle",bundles[other.id] ?: JSONObject.NULL)
            }
            "send" -> {
                check(data.getInt("protocolVersion")==3)
                val id=data.getString("messageId");val wire=data.getString("content")
                sends.add(wire)
                if(messages.containsKey(id)) check(messages[id]!!.getString("content")==wire)
                else {
                    val row=JSONObject().put("id",id).put("senderId",identity.id).put("recipientId",peer(identity).id)
                        .put("content",wire).put("createdAt",1L).put("revision",1L).put("status","SENT")
                        .put("deleted",false).put("reaction","").put("protocolVersion",3)
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
        override fun close() {
            db.close()
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
            val id=a.repository.sendMessage(conversation,"repository ratchet fixture")
            val before=a.db.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)
            a.repository.synchronize();now+=20000
            // A lost response is recovered from the sender's authenticated echo;
            // it must not ratchet again or create another message ID.
            a.repository.synchronize();b.repository.synchronize(conversation);a.repository.synchronize()
            assertEquals(1,relay.messages.size)
            assertTrue(relay.sends.isNotEmpty());assertTrue(relay.sends.all {it==relay.sends.first()})
            assertEquals(before,a.db.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY))
            assertEquals("repository ratchet fixture",b.repository.getMessages(conversation).first().single().content)
            assertTrue(b.db.messageDao().getMessage(id)!!.content.startsWith("lanu3-local:"))
            val rejected=UUID.randomUUID().toString()
            val legacy=PeerMessageCrypto.encrypt(second,first.id,first.publicKey,rejected,"forbidden downgrade")
            relay.inject(first.id,JSONObject().put("id",rejected).put("senderId",second.id).put("recipientId",first.id)
                .put("content",legacy).put("createdAt",2L).put("revision",1L).put("status","SENT").put("deleted",false))
            now+=20000
            assertTrue(runCatching {a.repository.synchronize(conversation)}.isFailure)
            assertNull(a.db.messageDao().getMessage(rejected))
            assertEquals(before,a.db.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY))
        } }
    }
}
