package com.example.core.media

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Local fail-closed upload budget for the already deployed 24.10.2026
 * lanu_media_objects.cipher_bytes constraint (17..41953280) and private
 * Storage bucket (44,040,192 bytes). Does not create network grants/uploads.
 *
 * The storage transfer format writes a 16-byte GCM tag per 64 KiB plaintext
 * chunk. Transport limits apply to the full encrypted object, not just source
 * length. The 64 MiB crypto engine limit remains a *local* capability.
 */
object PrivateMediaTransferBudget {
    const val MAX_PLAIN_BYTES: Long = 40L * 1024 * 1024
    const val MAX_RESERVED_CIPHER_BYTES: Long = 41_953_280L
    const val BUCKET_CIPHER_BYTES: Long = 44_040_192L
    private const val GCM_TAG_BYTES = 16L

    fun encryptedBytes(plainBytes: Long): Long {
        require(plainBytes in 1L..MAX_PLAIN_BYTES) {
            "Bu dosya sunucunun 40 MiB gönderim sınırını aşıyor."
        }
        val chunks = 1L + (plainBytes - 1) / PrivateMediaCrypto.CHUNK_BYTES
        val encoded = plainBytes + chunks * GCM_TAG_BYTES
        require(encoded in 17L..MAX_RESERVED_CIPHER_BYTES &&
            encoded <= BUCKET_CIPHER_BYTES) {
            "Şifreli dosya depolama kotasını aşıyor."
        }
        return encoded
    }

    fun verifyCiphertext(encryptedFile: File, plainBytes: Long) {
        require(encryptedFile.isFile && encryptedFile.length() == encryptedBytes(plainBytes)) {
            "Şifreli dosyanın uzunluğu sunucu rezervasyonuyla eşleşmiyor."
        }
    }

    /** Only public, server-safe reservation fields; NEVER includes the file key. */
    data class VerifiedReservation(val fileId: String, val cipherBytes: Long, val cipherSha256: String)

    /**
     * Fail-closed, bounded SHA-256 preflight for a local ciphertext before any
     * *future* storage reservation/upload. A manifest is trusted ONLY after its
     * existing authenticated E2EE key envelope has been verified by the caller.
     *
     * No network request, grant or object upload is performed. A future upload
     * must stream the very same bytes and have the relay verify the final hash.
     */
    /** Stop a broken storage stream from spinning forever on zero-byte reads. */
    internal fun boundedZeroReads(previous: Int, readBytes: Int): Int {
        require(readBytes >= 0)
        if (readBytes > 0) return 0
        require(previous in 0..1) { "Şifreli medya dosyası okunamıyor." }
        return previous + 1
    }

    fun verifyForReservation(
        encryptedFile: File,
        manifest: PrivateMediaCrypto.Manifest,
        privateDirectory: File
    ): VerifiedReservation {
        PrivateMediaCrypto.validate(manifest)
        val expectedBytes = encryptedBytes(manifest.plainBytes)
        require(privateDirectory.isDirectory && encryptedFile.isFile &&
            encryptedFile.name.matches(Regex("lanu-media-v1-[A-Za-z0-9_-]+\\.cipher")) &&
            encryptedFile.canonicalFile.parentFile == privateDirectory.canonicalFile) {
            "Şifreli medya yalnız uygulamanın özel dosya alanından yüklenebilir."
        }
        verifyCiphertext(encryptedFile, manifest.plainBytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
        var consumed = 0L
        var zeroReads = 0
        FileInputStream(encryptedFile).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                zeroReads = boundedZeroReads(zeroReads, length)
                if (length == 0) continue
                consumed += length
                require(consumed <= expectedBytes) { "Şifreli dosya boyutu aşıldı." }
                sha256.update(buffer, 0, length)
            }
        }
        require(consumed == expectedBytes && encryptedFile.length() == expectedBytes) {
            "Şifreli dosya rezervasyon boyutuyla eşleşmiyor."
        }
        val digest = sha256.digest()
        val expectedSha = manifest.cipherSha256.chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(digest, expectedSha)) {
            "Şifreli dosya SHA-256 özetiyle eşleşmiyor."
        }
        return VerifiedReservation(manifest.fileId, expectedBytes, manifest.cipherSha256)
    }
}
