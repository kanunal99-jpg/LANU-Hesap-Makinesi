package com.example

import com.example.core.media.PrivateMediaTransferBudget
import com.example.core.media.PrivateMediaCrypto
import java.io.ByteArrayInputStream
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

    @Test fun reservationPreflightRejectsTamperedBytesEvenWithCorrectLength() {
        val dir = work.newFolder("trusted-cache")
        val original = ByteArray(70_009) { ((it * 17) and 255).toByte() }
        val encrypted = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(original), original.size.toLong(), "application/pdf", dir
        )
        try {
            val verified = PrivateMediaTransferBudget.verifyForReservation(
                encrypted.ciphertextFile, encrypted.manifest, dir
            )
            assertEquals(encrypted.manifest.fileId, verified.fileId)
            assertEquals(encrypted.manifest.cipherSha256, verified.cipherSha256)
            assertEquals(70_009L + 2 * 16, verified.cipherBytes)
            val data = encrypted.ciphertextFile.readBytes()
            data[3] = (data[3].toInt() xor 1).toByte()
            encrypted.ciphertextFile.writeBytes(data)
            rejected {
                PrivateMediaTransferBudget.verifyForReservation(
                    encrypted.ciphertextFile, encrypted.manifest, dir
                )
            }
            data[3] = (data[3].toInt() xor 1).toByte()
            encrypted.ciphertextFile.writeBytes(data)
            rejected {
                PrivateMediaTransferBudget.verifyForReservation(
                    encrypted.ciphertextFile, encrypted.manifest.copy(cipherSha256 = "0".repeat(64)), dir
                )
            }
            rejected {
                PrivateMediaTransferBudget.verifyForReservation(
                    encrypted.ciphertextFile, encrypted.manifest.copy(plainBytes = 41L * 1024 * 1024), dir
                )
            }
            // An identical file outside the requested private directory must
            // never be mistaken for an authorized upload candidate.
            val elsewhere = work.newFolder("outside")
            val wrongLocation = encrypted.ciphertextFile.copyTo(
                java.io.File(elsewhere, encrypted.ciphertextFile.name)
            )
            rejected {
                PrivateMediaTransferBudget.verifyForReservation(
                    wrongLocation, encrypted.manifest, dir
                )
            }
            // A source with an unexpected name cannot be used to bypass staging.
            val wrongName = encrypted.ciphertextFile.copyTo(java.io.File(dir, "stolen-cipher.bin"))
            rejected {
                PrivateMediaTransferBudget.verifyForReservation(
                    wrongName, encrypted.manifest, dir
                )
            }
        } finally {
            encrypted.destroyKey()
            assertTrue(encrypted.keyMaterial.all { it == 0.toByte() })
            encrypted.ciphertextFile.delete()
        }
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
