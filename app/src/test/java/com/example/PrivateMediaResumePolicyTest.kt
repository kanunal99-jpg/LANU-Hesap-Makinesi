package com.example

import com.example.core.media.PrivateMediaCrypto
import com.example.core.media.PrivateMediaResumePolicy
import com.example.core.media.PrivateMediaTransferBudget
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaResumePolicyTest {
    private fun manifest(size: Long): PrivateMediaCrypto.Manifest {
        val chunk = PrivateMediaCrypto.CHUNK_BYTES.toLong()
        return PrivateMediaCrypto.Manifest(
            version = 1,
            fileId = "a".repeat(32),
            mime = "application/pdf",
            plainBytes = size,
            chunks = ((size - 1) / chunk + 1).toInt(),
            noncePrefix = "b".repeat(16),
            cipherSha256 = "c".repeat(64)
        )
    }

    @Test fun partialCipherChunkNeverBecomesTrustedResumeOffset() {
        val m = manifest(2L * 65_536 + 9)
        val size = PrivateMediaTransferBudget.encryptedBytes(m.plainBytes)
        val chunk = 65_536L + 16L
        val s = PrivateMediaResumePolicy.plan(m, chunk + 700, 1)
        assertEquals(chunk, s.verifiedCipherOffset)
        assertEquals(size, s.expectedCipherBytes)
        assertFalse(s.restartFromZero)
        assertEquals(3, m.chunks)
        val done = PrivateMediaResumePolicy.plan(m, size, 3)
        assertEquals(size, done.verifiedCipherOffset)
    }

    @Test fun freshTransferStartsAtZeroAndNeverTrustsPartialChunk() {
        val m = manifest(12_000)
        val p = PrivateMediaResumePolicy.plan(m, 2_000, 0)
        assertTrue(p.restartFromZero)
        assertEquals(0, p.verifiedCipherOffset)
        assertEquals(12_016, p.expectedCipherBytes)
    }

    @Test fun forgedChunkProofAndOverflowAreRejected() {
        val m = manifest(2L * 65_536 + 9)
        for ((bytes, count) in listOf(
            -1L to 0,
            Long.MAX_VALUE to 0,
            0L to -1,
            0L to 4,
            500L to 1,
            65_536L to 1,
            65_552L to 2
        )) {
            try {
                PrivateMediaResumePolicy.plan(m, bytes, count)
                fail("Unsafe media resume state accepted: $bytes/$count")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun invalidManifestCannotEstablishAnOffset() {
        val m = manifest(65_536)
        try {
            PrivateMediaResumePolicy.plan(m.copy(chunks = 2), 20, 0)
            fail("Forged media chunk count")
        } catch (_: IllegalArgumentException) {}
    }
}
