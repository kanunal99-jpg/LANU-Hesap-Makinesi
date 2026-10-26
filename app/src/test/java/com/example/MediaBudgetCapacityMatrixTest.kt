package com.example

import com.example.core.media.PrivateMediaTransferBudget
import com.example.core.media.PrivateMediaCrypto
import org.junit.Assert.*
import org.junit.Test

/** 20,000 boundary calculations without loading a 40MiB blob into memory. */
class MediaBudgetCapacityMatrixTest {
    @Test fun encodedSizesAreMonotonicAndMatchChunkAuthenticationOverhead() {
        val max = PrivateMediaTransferBudget.MAX_PLAIN_BYTES
        val chunk = PrivateMediaCrypto.CHUNK_BYTES.toLong()
        var previous = 0L
        repeat(20_000) { i ->
            val bytes = 1L + (i.toLong() * 7919L) % max
            val expected = bytes + ((bytes - 1L) / chunk + 1L) * 16L
            assertEquals(expected, PrivateMediaTransferBudget.encryptedBytes(bytes))
        }
        for (bytes in listOf(1L, chunk - 1, chunk, chunk + 1, max - 1, max)) {
            val encoded = PrivateMediaTransferBudget.encryptedBytes(bytes)
            assertTrue(encoded > bytes)
            assertTrue(encoded <= PrivateMediaTransferBudget.MAX_RESERVED_CIPHER_BYTES)
            assertTrue(encoded >= previous)
            previous = encoded
        }
    }

    @Test fun invalidInputsRejectBeforeOverflowOrFileAllocation() {
        for (n in listOf(Long.MIN_VALUE, -1L, 0L,
            PrivateMediaTransferBudget.MAX_PLAIN_BYTES + 1L, Long.MAX_VALUE)) {
            try {
                PrivateMediaTransferBudget.encryptedBytes(n)
                fail("Invalid requested transfer size was accepted")
            } catch (_: IllegalArgumentException) {
                // Reject without disk, network, or allocating bytes proportional to n.
            }
        }
    }
}
