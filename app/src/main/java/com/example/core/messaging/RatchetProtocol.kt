package com.example.core.messaging

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/** Public, identity-signed per-conversation prekeys. No secret key or pickle in a bundle. */
internal object RatchetProtocol {
    private val hash=Regex("[a-f0-9]{64}")
    private val curve=Regex("[A-Za-z0-9+/]{43}")
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val fields=setOf("owner","peer","generation","identity","oneTime","signature")
    fun bundle(own:MessagingIdentity,peer:String,created:JSONObject):JSONObject {
        val result=JSONObject().put("owner",own.id).put("peer",peer)
            .put("generation",UUID.randomUUID().toString()).put("identity",created.getString("identity"))
            .put("oneTime",created.getString("oneTime"))
        result.put("signature",UserCodes.sign(own,transcript(result)))
        return result
    }
    fun transcript(bundle:JSONObject):String {
        require(hash.matches(bundle.getString("owner")) && hash.matches(bundle.getString("peer")) &&
            bundle.getString("owner") != bundle.getString("peer") && uuid.matches(bundle.getString("generation")))
        for(key in listOf("identity","oneTime")) {
            val value=bundle.getString(key)
            require(curve.matches(value))
            val bytes=Base64.decode(value,Base64.NO_WRAP)
            require(bytes.size==32 && bytes.any { it!=0.toByte() } &&
                Base64.encodeToString(bytes,Base64.NO_WRAP or Base64.NO_PADDING)==value)
        }
        return "LANU-OLM2-BUNDLE-v1\n"+listOf("owner","peer","generation","identity","oneTime").joinToString("\n") { bundle.getString(it) }
    }
    fun verify(bundle:JSONObject,owner:String,peer:String,publicKey:String) {
        require(bundle.keys().asSequence().toSet()==fields && bundle.toString().length<=2048 &&
            bundle.getString("owner")==owner && bundle.getString("peer")==peer && UserCodes.id(publicKey)==owner)
        val signature=bundle.getString("signature")
        require(signature.length in 80..100)
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(UserCodes.decode(publicKey)))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key);update(transcript(bundle).toByteArray(Charsets.UTF_8));verify(UserCodes.decode(signature))
        }) { "Güvenli oturum kimlik imzası doğrulanamadı." }
    }
    fun epoch(first:JSONObject,second:JSONObject):String {
        require(first.getString("owner")==second.getString("peer") && first.getString("peer")==second.getString("owner"))
        val ordered=listOf(first,second).sortedBy { it.getString("owner") }
        return RatchetTransactionStore.digest("LANU-OLM2-PAIR-v1\n"+ordered.joinToString("\n") { transcript(it) })
    }
    fun verifySignature(publicKey:String,payload:String,signature:String) {
        require(publicKey.length in 64..256 && payload.length<=32768 && signature.length in 80..100)
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(UserCodes.decode(publicKey)))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key);update(payload.toByteArray(Charsets.UTF_8));verify(UserCodes.decode(signature))
        }) { "Oturum kaydının kimlik imzası doğrulanamadı." }
    }
    fun resetTranscript(owner:String,peer:String,id:String,previous:String,bundle:JSONObject):String {
        require(hash.matches(owner) && hash.matches(peer) && owner!=peer && uuid.matches(id) && hash.matches(previous) &&
            bundle.getString("owner")==owner && bundle.getString("peer")==peer)
        return "LANU-OLM2-RESET-v1\n"+listOf(owner,peer,id,previous,transcript(bundle)).joinToString("\n")
    }
    fun verifyCurrentPair(own:MessagingIdentity,peer:String,peerKey:String,state:JSONObject):String {
        check(state.getBoolean("ready")) { "Önce iki tarafın ilk oturumu oluşturulmalı." }
        val local=state.getJSONObject("ownBundle");val remote=state.getJSONObject("peerBundle")
        verify(local,own.id,peer,own.publicKey);verify(remote,peer,own.id,peerKey)
        val epoch=epoch(local,remote)
        check(epoch==state.getString("epoch")) { "Sunucu oturum nesli doğrulanamadı." }
        return epoch
    }
    fun verifyRenewal(own:MessagingIdentity,peer:String,peerKey:String,receipt:JSONObject):Pair<JSONObject,JSONObject> {
        require(receipt.getString("state")=="active")
        val id=receipt.getString("id");val previous=receipt.getString("previous_epoch")
        require(setOf(receipt.getString("first_id"),receipt.getString("second_id"))==setOf(own.id,peer))
        val votes=receipt.getJSONObject("votes")
        require(votes.keys().asSequence().toSet()==setOf(own.id,peer))
        val local=votes.getJSONObject(own.id);val remote=votes.getJSONObject(peer)
        val lb=local.getJSONObject("bundle");val rb=remote.getJSONObject("bundle")
        verify(lb,own.id,peer,own.publicKey);verify(rb,peer,own.id,peerKey)
        verifySignature(own.publicKey,resetTranscript(own.id,peer,id,previous,lb),local.getString("proof"))
        verifySignature(peerKey,resetTranscript(peer,own.id,id,previous,rb),remote.getString("proof"))
        require(epoch(lb,rb)==receipt.getString("new_epoch"))
        return lb to rb
    }
    /** Bound JSON depth BEFORE JSONObject recursion on untrusted text. */
    fun parse(value:String,maxBytes:Int=65536):JSONObject {
        require(value.length<=maxBytes && value.toByteArray(Charsets.UTF_8).size<=maxBytes)
        var quoted=false;var escaped=false;var depth=0
        for(c in value) {
            if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
            else when(c) { '"' -> quoted=true; '{','[' -> { depth++;require(depth<=6) }; '}',']' -> { depth--;require(depth>=0) } }
        }
        require(!quoted && depth==0)
        return JSONObject(value)
    }
}
