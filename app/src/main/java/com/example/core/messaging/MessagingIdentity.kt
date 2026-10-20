package com.example.core.messaging

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MessagingIdentity(val id: String, val code: String, val nonce: String, val publicKey: String, val privateKey: String)
interface MessagingIdentityStore {
    fun read(): MessagingIdentity?
    fun save(identity: MessagingIdentity)
}

/** Never replace an unreadable identity or fall back to plaintext storage. */
class EncryptedMessagingIdentityStore(
    context: Context,
    // Test-only seam; production uses the protected Keystore-backed store.
    private val preferencesForTesting: SharedPreferences? = null
) : MessagingIdentityStore {
    private val prefs by lazy {
        preferencesForTesting ?: run {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "lanu_messaging_identity", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
    }
    override fun read(): MessagingIdentity? {
        val raw = prefs.getString("identity", null) ?: return null
        val obj = JSONObject(raw)
        return MessagingIdentity(obj.getString("id"), obj.getString("code"), obj.getString("nonce"),
            obj.getString("publicKey"), obj.getString("privateKey")).also {
            require(UserCodes.isConsistentIdentity(it)) {
                "Şifreli kimlikte açık/özel anahtar uyuşmazlığı. Kayıt değiştirilmedi."
            }
        }
    }
    override fun save(identity: MessagingIdentity) {
        require(UserCodes.isConsistentIdentity(identity)) {
            "Uyumsuz kimlik anahtarı kaydedilmedi; mevcut kimlik korundu."
        }
        val value = JSONObject().put("id", identity.id).put("code", identity.code).put("nonce", identity.nonce)
            .put("publicKey", identity.publicKey).put("privateKey", identity.privateKey)
        val encoded = value.toString()
        check(prefs.edit().putString("identity", encoded).commit()) { "Kimlik güvenle kaydedilemedi" }
        // A true commit is not sufficient if a broken encrypted-store adapter
        // cannot read the saved identity back. Never report recovery as complete.
        check(prefs.getString("identity", null) == encoded) {
            "Kimlik şifreli depodan doğrulanamadı; yedek dosyanızı koruyun."
        }
    }
}

object UserCodes {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    fun id(publicKey: String): String = hash(publicKey).joinToString("") { "%02x".format(it) }
    fun normalize(value: String): String {
        val compact = value.trim().uppercase(Locale.ROOT).replace("-", "").replace(" ", "").removePrefix("LANU")
        return if (compact.length == 20 && compact.all { it in ALPHABET }) "LANU-" + compact.chunked(4).joinToString("-") else ""
    }
    fun code(publicKey: String, nonce: String): String {
        var buffer = 0
        var bits = 0
        val chars = StringBuilder()
        for (byte in hash("$publicKey:$nonce")) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5 && chars.length < 20) { bits -= 5; chars.append(ALPHABET[(buffer ushr bits) and 31]) }
            if (chars.length == 20) break
        }
        return "LANU-" + chars.toString().chunked(4).joinToString("-")
    }
    fun matches(value: String, publicKey: String, nonce: String) = normalize(value) == code(publicKey, nonce)
    /**
     * An encrypted JSON record can still contain a mismatched key pair after a bad
     * import or storage failure. A plausible code and public-key digest alone do
     * not prove that the private key can sign as the advertised account.
     */
    fun isConsistentIdentity(identity: MessagingIdentity): Boolean = runCatching {
        if (identity.id != id(identity.publicKey) ||
            !matches(identity.code, identity.publicKey, identity.nonce)) {
            false
        } else {
            val challenge = "LANU-identity-keypair-integrity-v1"
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(decode(identity.publicKey)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(challenge.toByteArray(Charsets.UTF_8))
            verifier.verify(decode(sign(identity, challenge)))
        }
    }.getOrDefault(false)

    fun newNonce(): String = ByteArray(16).also(SecureRandom()::nextBytes).let(::encode)
    fun create(): MessagingIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = encode(pair.public.encoded)
        val nonce = newNonce()
        return MessagingIdentity(id(pub), code(pub, nonce), nonce, pub, encode(pair.private.encoded))
    }
    fun sign(identity: MessagingIdentity, payload: String): String {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(identity.privateKey)))
        return Signature.getInstance("SHA256withECDSA").run { initSign(key); update(payload.toByteArray(Charsets.UTF_8)); encode(sign()) }
    }
    fun conversationId(first: String, second: String): String = id(listOf(first, second).sorted().joinToString(":"))
}

object PeerMessageCrypto {
    // Match the relay's ciphertext length limit. Check BEFORE Base64 decode so
    // a malformed remote message cannot allocate an unbounded byte array.
    private const val PREFIX = "lanu2:"
    private const val MAX_CIPHERTEXT_CHARS = 50_000
    private const val MAX_UTF8_PLAINTEXT_BYTES = 37_467
    private const val MIN_FRAME_BYTES = 12 + 16
    private val encodedChars = Regex("[A-Za-z0-9_-]+")

    private fun key(identity: MessagingIdentity, peerId: String, publicKey: String): SecretKeySpec {
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(UserCodes.decode(identity.privateKey)))
        val peer = factory.generatePublic(X509EncodedKeySpec(UserCodes.decode(publicKey)))
        val shared = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(peer, true); generateSecret() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(shared)
        digest.update(("LANU-v1:" + UserCodes.conversationId(identity.id, peerId)).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest.digest(), "AES")
    }
    private fun aad(messageId: String, senderId: String, recipientId: String) = "$messageId:$senderId:$recipientId".toByteArray(Charsets.UTF_8)
    fun encrypt(identity: MessagingIdentity, peerId: String, peerKey: String, messageId: String, text: String): String {
        require(text.length <= MAX_UTF8_PLAINTEXT_BYTES) { "Mesaj boyutu sunucu sınırını aşıyor." }
        val plain = text.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_UTF8_PLAINTEXT_BYTES) { "Mesaj boyutu sunucu sınırını aşıyor." }
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(identity, peerId, peerKey), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(messageId, identity.id, peerId))
        val envelope = PREFIX + UserCodes.encode(iv + cipher.doFinal(plain))
        check(envelope.length <= MAX_CIPHERTEXT_CHARS) { "Şifreli mesaj boyutu sunucu sınırını aşıyor." }
        return envelope
    }
    fun decrypt(identity: MessagingIdentity, peerId: String, peerKey: String, messageId: String, senderId: String, content: String): String {
        require(content.length in (PREFIX.length + 38)..MAX_CIPHERTEXT_CHARS &&
            content.startsWith(PREFIX)) { "Şifreli mesaj çerçevesi geçersiz." }
        val encoded = content.removePrefix(PREFIX)
        require(encodedChars.matches(encoded)) { "Şifreli mesaj kodlaması geçersiz." }
        val bytes = UserCodes.decode(encoded)
        require(bytes.size >= MIN_FRAME_BYTES) { "Şifreli mesaj eksik." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(identity, peerId, peerKey), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(messageId, senderId, if (senderId == identity.id) peerId else identity.id))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
