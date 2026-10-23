package com.example

import com.example.core.media.PrivateMediaCacheRecovery
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateMediaCacheRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun removesOnlyAbandonedMediaTempAndPreservesAllOtherFiles() {
        val dir = temporary.newFolder("private-cache")
        val plaintext = java.io.File(dir, "lanu-verified-AbCd912.private").apply {
            writeText("user data must not survive an app restart")
        }
        val cipher = java.io.File(dir, "lanu-media-v1-Wxyz012.cipher").apply {
            writeText("encrypted abandoned upload")
        }
        val keepFiles = listOf(
            "lanu-messages.db", "ota-app-debug.apk", "lanu-signing.tar.gz.gpg",
            "lanu-verified-AbCd912.private.bak", "lanu-media-v1-Wxyz012.cipher.sha256",
            "lanu-verified-malicious.tmp"
        ).map { name ->
            java.io.File(dir, name).apply { writeText("leave alone") }
        }
        val subdirectory = java.io.File(dir, "lanu-verified-is-a-directory.private").apply {
            mkdirs()
        }
        val result = PrivateMediaCacheRecovery.purge(dir)
        assertEquals(2, result.removed)
        assertTrue(result.unableToRemove >= 1)
        assertFalse(plaintext.exists())
        assertFalse(cipher.exists())
        for (file in keepFiles) assertEquals("leave alone", file.readText())
        assertTrue(subdirectory.isDirectory)
        assertEquals(0, PrivateMediaCacheRecovery.purge(dir).removed)
    }

    @Test fun absentOrNonDirectoryNeverThrowsOrDeletesUserFiles() {
        val dir = temporary.newFolder("cache-root")
        val userFile = java.io.File(dir, "lanu-verified-storage.private").apply {
            writeText("do not delete outside selected cache directory")
        }
        val nonexistent = java.io.File(dir, "not-a-folder")
        assertEquals(0, PrivateMediaCacheRecovery.purge(nonexistent).removed)
        assertEquals("do not delete outside selected cache directory", userFile.readText())
    }
}
