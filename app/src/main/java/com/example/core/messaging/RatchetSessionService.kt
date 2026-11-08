package com.example.core.messaging

import com.example.core.database.*
import org.json.JSONObject

/**
 * Cryptographic session lifecycle, independent of relay admission/UI opt-in.
 * Callers MUST establish the signed two-peer protocol barrier before network use.
 */
internal class RatchetSessionService(
    private val database:AppDatabase,
    private val store:RatchetTransactionStore,
    private val secrets:RatchetSecrets
) {
    private fun root(raw:String?,own:MessagingIdentity):JSONObject {
        if(raw==null) return JSONObject().put("version",1).put("owner",own.id).put("peers",JSONObject())
        return RatchetProtocol.parse(raw,2*1024*1024).also {
            check(it.getInt("version")==1 && it.getString("owner")==own.id && it.getJSONObject("peers").length()<=64)
        }
    }
    suspend fun prepare(own:MessagingIdentity,peer:String):JSONObject=store.transact { raw ->
        val state=root(raw,own); val peers=state.getJSONObject("peers")
        var entry=peers.optJSONObject(peer)
        if(entry==null) {
            require(peers.length()<64)
            val created=secrets.withPickleKey { OlmRatchetNative.process(JSONObject().put("op","create"),it) }
            val bundle=RatchetProtocol.bundle(own,peer,created)
            entry=JSONObject().put("local",bundle).put("account",created.getString("account")).put("sessions",JSONObject())
            peers.put(peer,entry)
        }
        val publicBundle=entry.getJSONObject("local")
        RatchetTransactionStore.Change(state.toString(),settings=mapOf("lanu_dr_public_bundle_$peer" to publicBundle.toString())) to JSONObject(publicBundle.toString())
    }
    suspend fun pin(own:MessagingIdentity,peer:String,peerKey:String,bundle:JSONObject) {
        RatchetProtocol.verify(bundle,peer,own.id,peerKey)
        store.transact { raw ->
            val state=root(raw,own);val entry=state.getJSONObject("peers").getJSONObject(peer)
            val previous=entry.optJSONObject("remote")
            check(previous==null || RatchetProtocol.transcript(previous)==RatchetProtocol.transcript(bundle)) {
                "Karşı tarafın güvenli oturumu değişmiş; açık yenileme gerekiyor."
            }
            entry.put("remote",JSONObject(bundle.toString()))
            val epoch=RatchetProtocol.epoch(entry.getJSONObject("local"),bundle)
            RatchetTransactionStore.Change(state.toString(),settings=mapOf("lanu_dr_pin_$peer" to epoch)) to Unit
        }
    }
    private fun entry(state:JSONObject,peer:String)=state.getJSONObject("peers").getJSONObject(peer).also { check(it.has("remote")) }
    private fun epoch(entry:JSONObject)=RatchetProtocol.epoch(entry.getJSONObject("local"),entry.getJSONObject("remote"))
    private fun archive(own:MessagingIdentity,id:String,text:String)="lanu3-local:"+
        PeerMessageCrypto.encrypt(own,own.id,own.publicKey,"dr-history:$id",text)
    suspend fun queue(own:MessagingIdentity,peer:String,peerKey:String,id:String,conversation:String,text:String,at:Long):String {
        require(UserCodes.id(peerKey)==peer && UserCodes.conversationId(own.id,peer)==conversation)
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size<=20000)
        return store.transact { raw ->
            val state=root(raw,own);val entry=entry(state,peer);val sessions=entry.getJSONObject("sessions")
            val inner=JSONObject().put("version",3).put("epoch",epoch(entry)).put("id",id)
                .put("from",own.id).put("to",peer).put("text",text).toString()
            val request=if(sessions.length()==0) JSONObject().put("op","start").put("account",entry.getString("account"))
                .put("peerIdentity",entry.getJSONObject("remote").getString("identity"))
                .put("peerOneTime",entry.getJSONObject("remote").getString("oneTime"))
            else {
                val sid=sessions.keys().asSequence().sorted().first()
                JSONObject().put("op","send").put("sessionId",sid).put("session",sessions.getString(sid))
            }
            val encrypted=secrets.withPickleKey { OlmRatchetNative.process(request.put("plaintext",inner),it) }
            val sid=encrypted.getString("sessionId")
            sessions.put(sid,encrypted.getString("session"));check(sessions.length()<=2)
            val frame="LANU-DR3:"+JSONObject().put("version",3).put("epoch",epoch(entry))
                .put("sessionId",sid).put("message",encrypted.getJSONObject("message")).toString()
            val wire=PeerMessageCrypto.encrypt(own,peer,peerKey,id,frame)
            val row=MessageEntity(id,conversation,own.id,"Ben",archive(own,id,text),timestamp=at,status="PENDING")
            RatchetTransactionStore.Change(state.toString(),row,settings=mapOf("lanu_dr_wire_$id" to wire,
                "lanu_dr_seen_$id" to RatchetTransactionStore.digest(wire)),
                preview=RatchetTransactionStore.Preview("Şifreli mesaj",at)) to wire
        }
    }
    suspend fun receive(own:MessagingIdentity,peer:String,peerKey:String,id:String,wire:String,at:Long,
        revision:Long,status:String,unread:Boolean):String {
        require(UserCodes.id(peerKey)==peer && revision>0)
        val outer=PeerMessageCrypto.decrypt(own,peer,peerKey,id,peer,wire)
        require(outer.startsWith("LANU-DR3:"))
        val frame=RatchetProtocol.parse(outer.substring(9),50000)
        require(frame.keys().asSequence().toSet()==setOf("version","epoch","sessionId","message") && frame.getInt("version")==3)
        return store.transact { raw ->
            check(database.messageDao().getMessage(id)==null) { "Mesaj zaten yerel arşivde." }
            val state=root(raw,own);val entry=entry(state,peer)
            check(frame.getString("epoch")==epoch(entry))
            val sessions=entry.getJSONObject("sessions");val sid=frame.getString("sessionId")
            val existing=sessions.optString(sid).takeIf { it.isNotBlank() }
            val request=if(existing!=null) JSONObject().put("op","receive").put("session",existing).put("sessionId",sid)
            else {
                check(sessions.length()<2)
                JSONObject().put("op","accept").put("account",entry.getString("account"))
                    .put("peerIdentity",entry.getJSONObject("remote").getString("identity"))
            }
            val opened=secrets.withPickleKey { OlmRatchetNative.process(request.put("message",frame.getJSONObject("message")),it) }
            check(opened.getString("sessionId")==sid)
            val inner=RatchetProtocol.parse(opened.getString("plaintext"),32768)
            check(inner.keys().asSequence().toSet()==setOf("version","epoch","id","from","to","text") &&
                inner.getInt("version")==3 && inner.getString("epoch")==epoch(entry) &&
                inner.getString("id")==id && inner.getString("from")==peer && inner.getString("to")==own.id)
            val text=inner.getString("text");require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size<=20000)
            sessions.put(sid,opened.getString("session"))
            if(opened.has("account")) entry.put("account",opened.getString("account"))
            val conversation=UserCodes.conversationId(own.id,peer)
            val row=MessageEntity(id,conversation,peer,"LANU",archive(own,id,text),timestamp=at,status=status)
            RatchetTransactionStore.Change(state.toString(),row,settings=mapOf("lanu_dr_seen_$id" to RatchetTransactionStore.digest(wire),
                "lanu_message_revision_$id" to revision.toString()),
                preview=RatchetTransactionStore.Preview("Şifreli mesaj",at,unread)) to text
        }
    }
    companion object {
        fun history(own:MessagingIdentity,id:String,content:String):String {
            require(content.startsWith("lanu3-local:"))
            return PeerMessageCrypto.decrypt(own,own.id,own.publicKey,"dr-history:$id",own.id,content.substring(12))
        }
    }
}
