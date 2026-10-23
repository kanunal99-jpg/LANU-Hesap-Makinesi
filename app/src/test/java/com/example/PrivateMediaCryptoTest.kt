package com.example

import com.example.core.media.PrivateMediaCrypto
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom

class PrivateMediaCryptoTest {
    @get:Rule val work = TemporaryFolder()

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("An unauthenticated or malformed media object must be rejected.")
        } catch (_: IllegalArgumentException) {
        } catch (_: javax.crypto.AEADBadTagException) {
        } catch (_: IOException) {
        }
    }

    private fun makeSource(bytes: Int): ByteArray =
        ByteArray(bytes) { index -> (index * 29 + 77).toByte() }

    @Test fun roundTripAcrossChunkBoundariesWithoutLeakingCleartext() {
        for (size in listOf(1, 65535, 65536, 65537, 131072, 170000)) {
            val dir = work.newFolder("size-" + size)
            val original = makeSource(size)
            val encrypted = PrivateMediaCrypto.encryptToPrivateFile(
                ByteArrayInputStream(original), original.size.toLong(), "image/jpeg", dir)
            try {
                PrivateMediaCrypto.validate(encrypted.manifest)
                val expectedChunks = (size + PrivateMediaCrypto.CHUNK_BYTES - 1) /
                    PrivateMediaCrypto.CHUNK_BYTES
                assertEquals(expectedChunks, encrypted.manifest.chunks)
                assertEquals(size.toLong() + expectedChunks * 16L, encrypted.ciphertextFile.length())
                assertFalse(encrypted.ciphertextFile.readBytes().contentEquals(original))
                assertFalse(encrypted.manifest.toString().contains("keyMaterial"))
                val cleartext = PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                    encrypted.ciphertextFile, encrypted.manifest, encrypted.keyMaterial, dir)
                try {
                    assertArrayEquals(original, cleartext.readBytes())
                } finally {
                    assertTrue(cleartext.delete())
                }
            } finally {
                encrypted.destroyKey()
                assertTrue(encrypted.keyMaterial.all { it == 0.toByte() })
                encrypted.ciphertextFile.delete()
            }
        }
    }

    @Test fun corruptFirstAndLastTagAreRejectedWithoutLeakingVerifiedPlaintextFile() {
        val dir = work.newFolder("tampering")
        val encrypted = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(makeSource(180000)), 180000, "video/mp4", dir)
        try {
            for (offset in listOf(10, encrypted.ciphertextFile.length().toInt() - 1)) {
                val data = encrypted.ciphertextFile.readBytes()
                data[offset] = (data[offset].toInt() xor 1).toByte()
                encrypted.ciphertextFile.writeBytes(data)
                assertRejected {
                    PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                        encrypted.ciphertextFile, encrypted.manifest, encrypted.keyMaterial, dir)
                }
                assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("lanu-verified-") })
                // Reconstruct valid bytes for the next iteration, not a combined tamper.
                data[offset] = (data[offset].toInt() xor 1).toByte()
                encrypted.ciphertextFile.writeBytes(data)
            }
        } finally { encrypted.destroyKey() }
    }

    @Test fun swappedCiphertextChunksAndWrongSecretKeyAreRejected() {
        val dir = work.newFolder("ordering")
        val encrypted = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(makeSource(131072)), 131072, "application/pdf", dir)
        try {
            val wrongKey = encrypted.keyMaterial.clone()
            wrongKey[0] = (wrongKey[0].toInt() xor 8).toByte()
            assertRejected {
                PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                    encrypted.ciphertextFile, encrypted.manifest, wrongKey, dir)
            }
            val bytes = encrypted.ciphertextFile.readBytes()
            val n = PrivateMediaCrypto.CHUNK_BYTES + 16
            bytes.copyOfRange(0, n).copyInto(bytes, n)
            assertRejected {
                PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                    encrypted.ciphertextFile, encrypted.manifest, encrypted.keyMaterial, dir)
            }
            assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("lanu-verified-") })
        } finally { encrypted.destroyKey() }
    }

    @Test fun manifestMetadataIsAuthenticatedWithAad() {
        val dir = work.newFolder("aad")
        val encrypted = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(makeSource(70123)), 70123, "image/png", dir)
        try {
            val m = encrypted.manifest
            val variations = listOf(
                m.copy(mime = "image/jpeg"),
                m.copy(fileId = "0".repeat(32)),
                m.copy(noncePrefix = "0".repeat(16)),
                m.copy(cipherSha256 = "0".repeat(64))
            )
            for (altered in variations) {
                assertRejected {
                    PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                        encrypted.ciphertextFile, altered, encrypted.keyMaterial, dir)
                }
            }
            assertRejected { PrivateMediaCrypto.validate(m.copy(chunks = 2 + m.chunks)) }
            assertRejected { PrivateMediaCrypto.validate(m.copy(version = 2)) }
            assertRejected { PrivateMediaCrypto.validate(m.copy(mime = "application/x-msdownload")) }
            assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("lanu-verified-") })
        } finally { encrypted.destroyKey() }
    }

    @Test fun oversizeMissingExtraAndUnsupportedInputsLeaveNoCipherTemp() {
        val dir = work.newFolder("input")
        assertRejected {
            PrivateMediaCrypto.encryptToPrivateFile(
                ByteArrayInputStream(makeSource(100)), 99, "text/plain", dir)
        }
        assertRejected {
            PrivateMediaCrypto.encryptToPrivateFile(
                ByteArrayInputStream(makeSource(99)), 100, "text/plain", dir)
        }
        assertRejected {
            PrivateMediaCrypto.encryptToPrivateFile(
                ByteArrayInputStream(makeSource(8)),
                PrivateMediaCrypto.MAX_FILE_BYTES + 1, "video/mp4", dir)
        }
        assertRejected {
            PrivateMediaCrypto.encryptToPrivateFile(
                ByteArrayInputStream(makeSource(8)), 8, "application/x-executable", dir)
        }
        assertRejected {
            PrivateMediaCrypto.encryptToPrivateFile(
                ByteArrayInputStream(byteArrayOf()), 0, "image/jpeg", dir)
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun truncatedOrAppendedCipherFileIsRejectedBeforeDecrypting() {
        val dir = work.newFolder("size")
        val encrypted = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(makeSource(18000)), 18000, "text/plain", dir)
        try {
            val data = encrypted.ciphertextFile.readBytes()
            encrypted.ciphertextFile.writeBytes(data.copyOf(data.size - 1))
            assertRejected {
                PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                    encrypted.ciphertextFile, encrypted.manifest, encrypted.keyMaterial, dir)
            }
            encrypted.ciphertextFile.writeBytes(data + byteArrayOf(1))
            assertRejected {
                PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                    encrypted.ciphertextFile, encrypted.manifest, encrypted.keyMaterial, dir)
            }
            assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("lanu-verified-") })
        } finally { encrypted.destroyKey() }
    }

    @Test fun separateFilesHaveIndependentKeysNoncesAndIds() {
        val dir = work.newFolder("unique")
        val source = makeSource(18000)
        val first = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(source), source.size.toLong(), "video/mp4", dir)
        val second = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(source), source.size.toLong(), "video/mp4", dir)
        try {
            assertNotEquals(first.manifest.fileId, second.manifest.fileId)
            assertNotEquals(first.manifest.noncePrefix, second.manifest.noncePrefix)
            assertFalse(first.keyMaterial.contentEquals(second.keyMaterial))
            assertNotEquals(first.manifest.cipherSha256, second.manifest.cipherSha256)
        } finally {
            first.destroyKey()
            second.destroyKey()
        }
    }
}
