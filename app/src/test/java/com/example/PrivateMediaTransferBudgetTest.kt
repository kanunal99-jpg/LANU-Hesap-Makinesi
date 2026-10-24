package com.example

import com.example.core.media.PrivateMediaTransferBudget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateMediaTransferBudgetTest {
    @get:Rule val work = TemporaryFolder()

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("A source larger than the production upload quota must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun gcmOverheadIsIncludedAtEveryChunkBoundary() {
        assertEquals(17L, PrivateMediaTransferBudget.encryptedBytes(1))
        assertEquals(65_552L, PrivateMediaTransferBudget.encryptedBytes(65_536))
        assertEquals(65_569L, PrivateMediaTransferBudget.encryptedBytes(65_537))
        assertEquals(131_104L, PrivateMediaTransferBudget.encryptedBytes(131_072))
        assertEquals(131_121L, PrivateMediaTransferBudget.encryptedBytes(131_073))
    }

    @Test fun exactProductionSqlAndStorageCapsAreRespected() {
        assertEquals(40L * 1024 * 1024, PrivateMediaTransferBudget.MAX_PLAIN_BYTES)
        assertEquals(41_953_280L,
            PrivateMediaTransferBudget.encryptedBytes(PrivateMediaTransferBudget.MAX_PLAIN_BYTES))
        assertTrue(PrivateMediaTransferBudget.MAX_RESERVED_CIPHER_BYTES <=
            PrivateMediaTransferBudget.BUCKET_CIPHER_BYTES)
        rejected { PrivateMediaTransferBudget.encryptedBytes(0) }
        rejected { PrivateMediaTransferBudget.encryptedBytes(-1) }
        rejected { PrivateMediaTransferBudget.encryptedBytes(
            PrivateMediaTransferBudget.MAX_PLAIN_BYTES + 1) }
        rejected { PrivateMediaTransferBudget.encryptedBytes(Long.MAX_VALUE) }
    }

    @Test fun stagedCipherBytesMustMatchReservationExactly() {
        val ciphertext = work.newFile("encrypted.bin")
        ciphertext.writeBytes(ByteArray(17))
        PrivateMediaTransferBudget.verifyCiphertext(ciphertext, 1)
        rejected { PrivateMediaTransferBudget.verifyCiphertext(ciphertext, 2) }
        ciphertext.appendBytes(byteArrayOf(0))
        rejected { PrivateMediaTransferBudget.verifyCiphertext(ciphertext, 1) }
        ciphertext.delete()
        rejected { PrivateMediaTransferBudget.verifyCiphertext(ciphertext, 1) }
    }
}
