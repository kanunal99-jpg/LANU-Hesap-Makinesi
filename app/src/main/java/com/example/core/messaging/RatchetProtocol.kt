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
