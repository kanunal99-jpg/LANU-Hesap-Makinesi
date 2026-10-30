package com.example.core.media

import com.example.core.messaging.MessagingIdentity
import com.example.core.messaging.PeerMessageCrypto
import com.example.core.messaging.UserCodes
import org.json.JSONObject

/**
 * A future private-storage attachment's manifest and one-time AES file key are
 * delivered ONLY through an authenticated PeerMessageCrypto AES-GCM frame.
 *
 * No network request, DB write, bucket creation, or UI feature is performed.
 * The existing 18 KB inline attachment remains the only enabled media route.
 *
 * Caller MUST resolve peer identity from already verified contact trust; never
 * accept a user-supplied arbitrary public key or an unauthenticated object URL.
 */
object PrivateMediaKeyEnvelope {
    private const val INNER_TYPE = "lanu-media-key-v1"
    private const val VERSION = 1
    private const val SECRET_SIZE = 32
    private const val MAX_JSON_CHARS = 2048
    private val mandatoryFields = setOf(
        "type", "version", "fileId", "mime", "plainBytes",
        "chunks", "noncePrefix", "cipherSha256", "key"
    )
    private val base64url43 = Regex("[A-Za-z0-9_-]{43}")

    /** Sensitive key returned after authenticating and decrypting the peer frame. */
    class OpenedMediaKey(
        val manifest: PrivateMediaCrypto.Manifest,
        val keyMaterial: ByteArray
    ) {
        fun destroyKey() = keyMaterial.fill(0)
    }

    /**
     * Encrypt once, addressed to a specific verified peer and message UUID.
     * The secret is never present in a server-visible envelope or URL.
     */
    fun encryptForPeer(
        sender: MessagingIdentity,
        verifiedPeerId: String,
        verifiedPeerPublicKey: String,
        messageId: String,
        manifest: PrivateMediaCrypto.Manifest,
        fileKey: ByteArray
    ): String {
        PrivateMediaCrypto.validate(manifest)
        require(fileKey.size == SECRET_SIZE) { "Medya anahtarı boyutu hatalı." }
        require(messageId.isNotBlank()) { "Medya mesaj kimliği boş olamaz." }
        val inner = JSONObject()
            .put("type", INNER_TYPE)
            .put("version", VERSION)
            .put("fileId", manifest.fileId)
            .put("mime", manifest.mime)
            .put("plainBytes", manifest.plainBytes)
            .put("chunks", manifest.chunks)
            .put("noncePrefix", manifest.noncePrefix)
            .put("cipherSha256", manifest.cipherSha256)
            .put("key", UserCodes.encode(fileKey)).toString()
        check(inner.length <= MAX_JSON_CHARS)
        return PeerMessageCrypto.encrypt(
            sender, verifiedPeerId, verifiedPeerPublicKey, messageId, inner
        )
    }

    /**
     * Fails closed for a wrong sender, recipient, message ID, malformed version,
     * invalid manifest, noncanonical key, or modified encrypted frame.
     * Do NOT write the plaintext result to logs/Room/storage.
     */
    fun decryptFromPeer(
        recipient: MessagingIdentity,
        verifiedSenderId: String,
        verifiedSenderPublicKey: String,
        messageId: String,
        senderIdInFrame: String,
        encryptedFrame: String
    ): OpenedMediaKey {
        require(verifiedSenderId == senderIdInFrame) { "Gönderen kimliği doğrulanmadı." }
        val plain = PeerMessageCrypto.decrypt(
            recipient, verifiedSenderId, verifiedSenderPublicKey,
            messageId, senderIdInFrame, encryptedFrame
        )
        require(plain.length in 1..MAX_JSON_CHARS) { "Medya anahtar zarfı çok büyük." }
        val json = JSONObject(plain)
        val keys = mutableSetOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys += it.next()
        // org.json getters silently coerce numeric strings and decimal
        // values (e.g. 1.5 -> 1). An authenticated but malformed media manifest
        // must never be normalized into another valid metadata identity.
        // JSONObject parses small integral JSON numbers as Int and larger
        // integral JSON numbers as Long; only the byte count permits both.
        require(keys == mandatoryFields &&
            json.opt("type") is String && json.optString("type") == INNER_TYPE &&
            json.opt("version") is Int && json.optInt("version") == VERSION &&
            json.opt("fileId") is String &&
            json.opt("mime") is String &&
            (json.opt("plainBytes") is Int || json.opt("plainBytes") is Long) &&
            json.opt("chunks") is Int &&
            json.opt("noncePrefix") is String &&
            json.opt("cipherSha256") is String &&
            json.opt("key") is String) {
            "Medya anahtar zarfı protokolü geçersiz."
        }
        val m = PrivateMediaCrypto.Manifest(
            version = VERSION,
            fileId = json.getString("fileId"),
            mime = json.getString("mime"),
            plainBytes = json.getLong("plainBytes"),
            chunks = json.getInt("chunks"),
            noncePrefix = json.getString("noncePrefix"),
            cipherSha256 = json.getString("cipherSha256")
        )
        PrivateMediaCrypto.validate(m)
        val encoded = json.getString("key")
        require(encoded.matches(base64url43)) { "Medya anahtarı kodu geçersiz." }
        val secret = UserCodes.decode(encoded)
        if (secret.size != SECRET_SIZE || UserCodes.encode(secret) != encoded) {
            secret.fill(0)
            throw IllegalArgumentException("Medya anahtarı doğrulanamadı.")
        }
        return OpenedMediaKey(m, secret)
    }
}
