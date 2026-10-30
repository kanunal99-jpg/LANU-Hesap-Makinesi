package com.example.core.media

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
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

    /**
     * Bounded streaming reader, testable with hostile/short-reading providers.
     * FileInputStream normally never returns zero for a positive read, but
     * fail closed if an adapter does so indefinitely rather than spin the CPU.
     */
    fun digestCipherStream(input: InputStream, expectedBytes: Long): ByteArray {
        require(expectedBytes in 17L..MAX_RESERVED_CIPHER_BYTES) { "Şifreli dosya sınırı geçersiz." }
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var emptyReads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                require(++emptyReads < 3) { "Şifreli dosya okumada ilerleme yok." }
                continue
            }
            emptyReads = 0
            total += count.toLong()
            require(total <= expectedBytes) { "Şifreli dosya boyutu aşıldı." }
            md.update(buffer, 0, count)
        }
        require(total == expectedBytes) { "Şifreli dosya eksik okundu." }
        return md.digest()
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
    fun verifyForReservation(
        encryptedFile: File,
        manifest: PrivateMediaCrypto.Manifest,
        privateDirectory: File
    ): VerifiedReservation {
        PrivateMediaCrypto.validate(manifest)
        val expectedBytes = encryptedBytes(manifest.plainBytes)
        // Fail closed for direct or parent symlinks even if they resolve to a
        // valid-looking ciphertext in the same directory. App ownership still
        // has to be established by the Android caller before using this API.
        val trusted = privateDirectory.canonicalFile
        val candidate = encryptedFile.canonicalFile
        require(privateDirectory.isDirectory &&
            privateDirectory.absoluteFile == trusted &&
            encryptedFile.absoluteFile == candidate &&
            encryptedFile.name.matches(Regex("lanu-media-v1-[A-Za-z0-9_-]+\\.cipher")) &&
            candidate.parentFile == trusted &&
            encryptedFile.isFile && encryptedFile.length() == expectedBytes) {
            "Şifreli medya yalnız uygulamanın özel dosya alanından yüklenebilir."
        }
        // Check the opened descriptor rather than trusting an earlier stat.
        // The actual network upload must use a pinned source descriptor too;
        // this offline preflight alone is NOT a live upload authorization.
        FileInputStream(encryptedFile).use { input ->
            require(input.channel.size() == expectedBytes) {
                "Şifreli dosyanın boyutu rezervasyonla eşleşmiyor."
            }
            val digest = digestCipherStream(input, expectedBytes)
            require(input.channel.size() == expectedBytes) {
                "Şifreli dosya doğrulama sırasında değişti."
            }
            val expectedSha = manifest.cipherSha256.chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray()
            require(MessageDigest.isEqual(digest, expectedSha)) {
                "Şifreli dosya SHA-256 özetiyle eşleşmiyor."
            }
        }
        return VerifiedReservation(manifest.fileId, expectedBytes, manifest.cipherSha256)
    }
}
