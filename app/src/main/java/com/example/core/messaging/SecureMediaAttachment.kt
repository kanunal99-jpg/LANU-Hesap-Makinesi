package com.example.core.messaging

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-only attachment encryption primitive. Never upload unencrypted bytes.
 * Media uses a domain-separated key, independent of text-message AES-GCM keys.
 * The relay/storage integration remains gated until private-bucket controls pass.
 */
object SecureMediaAttachment {
    const val MAX_CIPHER_BYTES = 2 * 1024 * 1024
    const val MAX_CLEAR_BYTES = MAX_CIPHER_BYTES - 28
    const val MANIFEST_PREFIX = "LANU_MEDIA_V1:"
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private val messageIdPattern = Regex("[a-f0-9-]{36}")
    private val shaPattern = Regex("[a-f0-9]{64}")
    private val allowedMime = setOf("image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain")

    data class Encrypted(val ciphertext: ByteArray, val sha256: String)
    data class Manifest(val name: String, val mime: String, val size: Int, val sha256: String) {
        fun serialize(): String {
            require(isValidManifest(this)) { "Güvenli medya bilgisi geçersiz." }
            return MANIFEST_PREFIX + JSONObject()
                .put("v", 1).put("name", name).put("mime", mime)
                .put("size", size).put("sha256", sha256).toString()
        }
    }

    fun parseManifest(value: String): Manifest? {
        if (!value.startsWith(MANIFEST_PREFIX)) return null
        require(value.length <= 2048) { "Medya açıklaması çok uzun." }
        val json = JSONObject(value.removePrefix(MANIFEST_PREFIX))
        require(json.getInt("v") == 1) { "Medya biçimi desteklenmiyor." }
        return Manifest(json.getString("name"), json.getString("mime"),
            json.getInt("size"), json.getString("sha256")).also {
            require(isValidManifest(it)) { "Güvenli medya açıklaması geçersiz." }
        }
    }

    fun isAllowedMime(mime: String): Boolean = mime in allowedMime

    private fun isValidManifest(info: Manifest): Boolean =
        info.name.isNotBlank() && info.name.length <= 90 &&
        info.name.none { it == '/' || it == '\\' || it.code < 32 } &&
        info.mime in allowedMime &&
        info.size in 29..MAX_CIPHER_BYTES && shaPattern.matches(info.sha256)

    private fun aesKey(self: MessagingIdentity, peerId: String, peerKey: String): SecretKeySpec {
        val factory = KeyFactory.getInstance("EC")
        val own = factory.generatePrivate(PKCS8EncodedKeySpec(UserCodes.decode(self.privateKey)))
        val other = factory.generatePublic(X509EncodedKeySpec(UserCodes.decode(peerKey)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own); doPhase(other, true); generateSecret()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(shared)
        digest.update(("LANU-media-key-v1:" +
            UserCodes.conversationId(self.id, peerId)).toByteArray(Charsets.UTF_8))
        shared.fill(0)
        return SecretKeySpec(digest.digest(), "AES")
    }

    private fun aad(messageId: String, senderId: String, recipientId: String): ByteArray {
        require(messageIdPattern.matches(messageId) && messageId.count { it == '-' } == 4) {
            "Medya mesaj kimliği geçersiz."
        }
        return "LANU-media-v1:$messageId:$senderId:$recipientId".toByteArray(Charsets.UTF_8)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    fun encrypt(self: MessagingIdentity, peerId: String, peerKey: String,
                messageId: String, clearBytes: ByteArray): Encrypted {
        require(clearBytes.size in 1..MAX_CLEAR_BYTES) {
            "Dosya çok büyük; en fazla 2 MB şifreli medya destekleniyor."
        }
        val iv = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(self, peerId, peerKey), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(aad(messageId, self.id, peerId))
        val output = iv + cipher.doFinal(clearBytes)
        check(output.size <= MAX_CIPHER_BYTES)
        return Encrypted(output, sha256(output))
    }

    fun decrypt(self: MessagingIdentity, peerId: String, peerKey: String,
                messageId: String, senderId: String, encrypted: ByteArray,
                expectedSha256: String): ByteArray {
        require(encrypted.size in 29..MAX_CIPHER_BYTES &&
            shaPattern.matches(expectedSha256) &&
            sha256(encrypted) == expectedSha256) { "Şifreli medya bütünlüğü doğrulanamadı." }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey(self, peerId, peerKey),
                GCMParameterSpec(TAG_BYTES * 8, encrypted.copyOfRange(0, NONCE_BYTES)))
            val recipientId = if (senderId == self.id) peerId else self.id
            cipher.updateAAD(aad(messageId, senderId, recipientId))
            cipher.doFinal(encrypted.copyOfRange(NONCE_BYTES, encrypted.size))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecurityException("Dosya kimlik doğrulaması başarısız.", e)
        }
    }
}
