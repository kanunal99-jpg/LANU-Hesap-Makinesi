package com.example.core.media

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Small offline-queueable attachment transported as the *plaintext input* to
 * PeerMessageCrypto (ECDH + AES-GCM). Only the already-encrypted "lanu2:" frame
 * reaches the relay or Room. This is deliberately NOT a full-size media upload.
 *
 * 18 KB cap avoids changing the production relay 50K ciphertext protocol,
 * increasing storage use unexpectedly, or introducing unauthenticated links.
 */
object InlineMediaCodec {
    private const val PREFIX = "lanu-media-v1:"
    const val MAX_FILE_BYTES = 18_000
    const val MAX_ENVELOPE_CHARS = 28_000
    const val MAX_FILENAME_CHARS = 64
    private val supported = setOf("image/jpeg", "application/pdf", "text/plain")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val base64Pattern = Regex("[A-Za-z0-9+/]+={0,2}")

    data class Attachment(val name: String, val mime: String, val bytes: ByteArray) {
        val isImage: Boolean get() = mime == "image/jpeg"
    }

    fun isAttachment(text: String): Boolean = text.startsWith(PREFIX)

    fun encode(name: String, mime: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) {
            "Dosya en fazla 18 KB olabilir; büyük dosyalar için güvenli aktarım henüz etkin değil."
        }
        require(mime in supported && matchesType(mime, bytes)) { "Dosya türü veya içeriği desteklenmiyor." }
        val safeName = cleanName(name)
        val json = JSONObject()
            .put("v", 1)
            .put("name", safeName)
            .put("mime", mime)
            .put("size", bytes.size)
            .put("sha256", sha256(bytes))
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val envelope = PREFIX + json.toString()
        require(envelope.length <= MAX_ENVELOPE_CHARS &&
            envelope.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_CHARS) {
            "Dosya mesaj sınırını aşıyor."
        }
        return envelope
    }

    /** Reject malformed or tampered content, including noncanonical base64. */
    fun decode(text: String): Attachment {
        require(text == text.trim() && isAttachment(text) && text.length <= MAX_ENVELOPE_CHARS) {
            "Ek dosya kimliği geçersiz."
        }
        val json = JSONObject(text.removePrefix(PREFIX))
        val expected = setOf("v", "name", "mime", "size", "sha256", "data")
        val actual = mutableSetOf<String>()
        val names = json.keys()
        while (names.hasNext()) actual.add(names.next())
        require(actual == expected && json.optInt("v", -1) == 1) { "Ek dosya biçimi geçersiz." }
        val name = json.getString("name")
        val mime = json.getString("mime")
        val size = json.getInt("size")
        val checksum = json.getString("sha256")
        val encoded = json.getString("data")
        require(name == cleanName(name) && mime in supported) { "Ek dosya adı veya türü geçersiz." }
        require(size in 1..MAX_FILE_BYTES && checksum.matches(digestPattern) &&
            encoded.length <= ((MAX_FILE_BYTES + 2) / 3 * 4) && encoded.matches(base64Pattern)) {
            "Ek dosya boyutu veya SHA-256 alanı geçersiz."
        }
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size == size && Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded &&
            sha256(bytes) == checksum && matchesType(mime, bytes)) {
            "Ek dosya bütünlüğü doğrulanamadı."
        }
        return Attachment(name, mime, bytes)
    }

    fun cleanName(source: String): String {
        // No paths, control characters, bidirectional overrides or double-dot traversal.
        val filename = source.replace('\\', '/').substringAfterLast('/').trim()
            .filter { it.isLetterOrDigit() || it in "._- " }.take(MAX_FILENAME_CHARS)
            .replace("..", ".").trim(' ', '.')
        return filename.ifBlank { "dosya" }
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun matchesType(mime: String, data: ByteArray): Boolean = when (mime) {
        "image/jpeg" -> data.size >= 4 &&
            (data[0].toInt() and 255) == 0xff && (data[1].toInt() and 255) == 0xd8 &&
            (data[data.size - 2].toInt() and 255) == 0xff &&
            (data.last().toInt() and 255) == 0xd9
        "application/pdf" -> data.size >= 8 &&
            data.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
        "text/plain" -> {
            if (data.any { it == 0.toByte() }) false
            else try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                true
            } catch (_: CharacterCodingException) { false }
        }
        else -> false
    }
}
