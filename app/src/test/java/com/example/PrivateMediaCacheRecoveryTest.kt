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

    @Test fun processDeathCleansDedicatedLargeMediaStagingButPreservesUnrelatedAndNestedFiles() {
        val cache = temporary.newFolder("cache")
        val large = java.io.File(cache, "lanu-private-large-media").apply { mkdir() }
        val expired = listOf("lanu-staged-Ab12.private", "lanu-verified-Cd34.private", "lanu-media-v1-Ef56.cipher")
            .map { java.io.File(large, it).apply { writeText("abandoned") } }
        val unrelated = java.io.File(large, "backup.private").apply { writeText("keep") }
        val rootStage = java.io.File(cache, "lanu-staged-Ab12.private").apply { writeText("keep") }
        val nested = java.io.File(large, "nested").apply { mkdir() }
        val nestedFile = java.io.File(nested, "lanu-staged-Ab12.private").apply { writeText("keep") }
        assertEquals(3, PrivateMediaCacheRecovery.purge(cache).removed)
        assertTrue(expired.none { it.exists() })
        for (file in listOf(unrelated, rootStage, nestedFile)) assertEquals("keep", file.readText())
    }

    @Test fun dedicatedDirectorySymlinkCannotDeleteOutsideCache() {
        val cache = temporary.newFolder("cache-link")
        val outside = temporary.newFolder("outside")
        val protected = java.io.File(outside, "lanu-staged-Ab12.private").apply { writeText("keep") }
        java.nio.file.Files.createSymbolicLink(java.io.File(cache, "lanu-private-large-media").toPath(), outside.toPath())
        val result = PrivateMediaCacheRecovery.purge(cache)
        assertEquals(0, result.removed); assertEquals(1, result.unableToRemove)
        assertEquals("keep", protected.readText())
    }

    @Test fun trustedOperatingSystemCacheAliasStillCleansItsRealChild() {
        val real = temporary.newFolder("real-cache")
        val alias = java.io.File(temporary.root, "system-cache-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        val large = java.io.File(real, "lanu-private-large-media").apply { mkdir() }
        val abandoned = java.io.File(large, "lanu-staged-Ab12.private").apply { writeText("clear") }
        assertEquals(1, PrivateMediaCacheRecovery.purge(alias).removed)
        assertFalse(abandoned.exists())
    }
}
