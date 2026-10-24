package com.example.core.media

import java.io.File

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
}
