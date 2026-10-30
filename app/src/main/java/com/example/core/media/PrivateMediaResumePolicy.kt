package com.example.core.media

/**
 * A future large-media transfer may resume only at authenticated AES-GCM
 * chunk boundaries, never at an arbitrary ciphertext byte offset.
 *
 * This is an OFFLINE planning primitive, not a Storage grant, HTTP Range
 * request, upload, download, or authorization to open a remote object.
 * Existing inline encrypted attachments are unaffected.
 */
internal object PrivateMediaResumePolicy {
    private const val AUTH_TAG_BYTES = 16L
    private const val CIPHER_CHUNK_BYTES = PrivateMediaCrypto.CHUNK_BYTES.toLong() + AUTH_TAG_BYTES

    data class Plan(
        val completedChunks: Int,
        val verifiedCipherOffset: Long,
        val expectedCipherBytes: Long,
        val restartFromZero: Boolean
    )

    /**
     * The downloader may persist only whole, previously authenticated chunks.
     * For a partial chunk, truncate to the prior boundary and re-fetch it.
     * The server must additionally authenticate the recipient and object,
     * supply a verified immutable object identity, and enforce expiry.
     *
     * This method never trusts size alone as evidence of GCM/manifest
     * authentication: authenticatedChunks comes from the verified transfer
     * owner, NOT a user/server-supplied count.
     */
    fun plan(
        manifest: PrivateMediaCrypto.Manifest,
        stagedCipherBytes: Long,
        authenticatedChunks: Int
    ): Plan {
        PrivateMediaCrypto.validate(manifest)
        val expected = PrivateMediaTransferBudget.encryptedBytes(manifest.plainBytes)
        require(stagedCipherBytes in 0L..expected) { "Şifreli indirme boyutu geçersiz." }
        require(authenticatedChunks in 0..manifest.chunks) {
            "Doğrulanmış medya parça sayısı geçersiz."
        }
        // Last chunk may be shorter, so only the final complete object may
        // use the final nonuniform offset.
        val offset = if (authenticatedChunks == manifest.chunks) expected
            else authenticatedChunks.toLong() * CIPHER_CHUNK_BYTES
        require(offset <= stagedCipherBytes) {
            "Dosya doğrulanan parça sayısıyla uyuşmuyor."
        }
        return Plan(
            completedChunks = authenticatedChunks,
            verifiedCipherOffset = offset,
            expectedCipherBytes = expected,
            restartFromZero = authenticatedChunks == 0
        )
    }
}
