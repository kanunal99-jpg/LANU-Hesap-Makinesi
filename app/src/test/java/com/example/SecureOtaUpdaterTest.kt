package com.example

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.update.SecureOtaUpdater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureOtaUpdaterTest {
    private val root = "https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/download/"

    private fun release(tag: String, names: List<String> = listOf(
        "app-debug.apk", "app-debug.apk.sha256", "app-debug.apk.signer.txt"
    ), draft: Boolean = false): JSONObject {
        val assets = JSONArray()
        names.forEach { name ->
            assets.put(JSONObject().put("name", name).put("browser_download_url", "$root$tag/$name"))
        }
        return JSONObject().put("tag_name", tag).put("draft", draft).put("assets", assets)
    }

    @Test fun `checks newest greater code and ignores draft and obsolete releases`() {
        val data = JSONArray()
            .put(release("v1.0.0-debug.148"))
            .put(release("v1.1.0-signed.159", draft = true))
            .put(release("v1.1.0-signed.153"))
            .put(release("v1.0.0-debug.149"))
        val candidate = SecureOtaUpdater.chooseRelease(data.toString(), 149)
        assertEquals("v1.1.0-signed.153", candidate?.tag)
        assertEquals(153L, candidate?.versionCode)
        assertNull(SecureOtaUpdater.chooseRelease(data.toString(), 160))
    }

    @Test fun `rejects redirects to other repositories and missing verifier sidecar`() {
        val wrongAssets = JSONArray().put(JSONObject()
            .put("name", "app-debug.apk")
            .put("browser_download_url", "https://attacker.example/bad.apk"))
            .put(JSONObject().put("name", "app-debug.apk.sha256")
                .put("browser_download_url", root + "v1.0.0-debug.201/app-debug.apk.sha256"))
            .put(JSONObject().put("name", "app-debug.apk.signer.txt")
                .put("browser_download_url", root + "v1.0.0-debug.201/app-debug.apk.signer.txt"))
        val spoof = JSONObject().put("tag_name", "v1.0.0-debug.201").put("assets", wrongAssets)
        assertNull(SecureOtaUpdater.chooseRelease(JSONArray().put(spoof).toString(), 100))
        assertNull(SecureOtaUpdater.chooseRelease(
            JSONArray().put(release("v1.0.0-debug.201", listOf("app-debug.apk", "app-debug.apk.sha256"))).toString(), 100))
        assertNull(SecureOtaUpdater.chooseRelease(
            JSONArray().put(release("v2.0.0-debug.201")).toString(), 100))
    }

    @Test fun `signer sidecar and checksum must match strict pinned format`() {
        val expected = "ab".repeat(32)
        val signer = "apk=app-debug.apk\nsigning_mode=stable\ncertificate_sha256=$expected\nupgrade_verified_on_device=false\n"
        assertEquals(expected, SecureOtaUpdater.parseSigner(signer))
        assertEquals(expected, SecureOtaUpdater.parseChecksum("$expected  app-debug.apk\n"))
        assertTrue(SecureOtaUpdater.sameCertificate(expected, expected))
        assertFalse(SecureOtaUpdater.sameCertificate(expected, "cd".repeat(32)))
        assertFalse(SecureOtaUpdater.sameCertificate("", expected))
        assertFails { SecureOtaUpdater.parseSigner("signing_mode=stable\ncertificate_sha256=$expected") }
        assertFails { SecureOtaUpdater.parseSigner("apk=app-debug.apk\nsigning_mode=unsafe\ncertificate_sha256=$expected") }
        assertFails { SecureOtaUpdater.parseChecksum("$expected ../app-debug.apk") }
    }

    @Test fun `manifest grants only required installer permission and scoped provider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS)
        assertTrue(pkg.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val otaProvider = pkg.providers?.singleOrNull { it.authority == "${context.packageName}.ota.provider" }
        assertNotNull(otaProvider)
        assertFalse(otaProvider!!.exported)
        assertTrue(otaProvider.grantUriPermissions)
    }

    @Test fun `newer unverifiable release cannot be misreported as up to date`() {
        val releases = JSONArray()
            .put(release("v1.0.0-debug.200", listOf("app-debug.apk")))
            .put(release("v1.0.0-debug.199"))
            .put(release("v1.0.0-debug.198", draft = true))
        assertEquals("v1.0.0-debug.200" to 200L,
            SecureOtaUpdater.newestPublishedVersion(releases.toString(), 150))
        assertEquals(199L, SecureOtaUpdater.chooseRelease(releases.toString(), 150)?.versionCode)
        assertNull(SecureOtaUpdater.newestPublishedVersion(releases.toString(), 201))
    }

    @Test fun `untrusted release URLs cannot be sent to OTA downloader`() {
        val good = SecureOtaUpdater.chooseRelease(
            JSONArray().put(release("v1.0.0-debug.250")).toString(), 149
        )!!
        SecureOtaUpdater.validateRelease(good)
        assertFails {
            SecureOtaUpdater.validateRelease(good.copy(
                apkUrl = "https://github.com/attacker/evil/releases/download/${good.tag}/app-debug.apk"
            ))
        }
        assertFails {
            SecureOtaUpdater.validateRelease(good.copy(tag = "v1.0.0-debug.999"))
        }
        assertFails {
            SecureOtaUpdater.validateRelease(good.copy(
                certificateUrl = "https://attacker.example/app-debug.apk.signer.txt"
            ))
        }
    }

    @Test fun `network redirects must remain within HTTPS GitHub endpoints`() {
        assertTrue(SecureOtaUpdater.isApprovedEndpoint("https", "github.com"))
        assertTrue(SecureOtaUpdater.isApprovedEndpoint("https", "api.github.com"))
        assertTrue(SecureOtaUpdater.isApprovedEndpoint("https", "release-assets.githubusercontent.com"))
        assertFalse(SecureOtaUpdater.isApprovedEndpoint("http", "github.com"))
        assertFalse(SecureOtaUpdater.isApprovedEndpoint("https", "github.com.attacker.invalid"))
        assertFalse(SecureOtaUpdater.isApprovedEndpoint("https", "evil.example"))
        assertFalse(SecureOtaUpdater.isApprovedEndpoint("file", "github.com"))
    }

    @Test fun `tampered cached APK fails before package inspection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val good = SecureOtaUpdater.chooseRelease(
            JSONArray().put(release("v1.0.0-debug.250")).toString(), 149
        )!!
        val tempDir = java.io.File(context.cacheDir, "ota").apply { mkdirs() }
        val file = java.io.File(tempDir, "tampered-test.apk")
        try {
            file.writeText("an APK replaced after download")
            val oldDigest = "ab".repeat(32)
            assertFails {
                SecureOtaUpdater.verifyArchive(context, file, good, oldDigest)
            }
        } finally {
            file.delete()
        }
    }


    @Test fun `resuming requires exact content range alignment and bound`() {
        assertEquals(1024L, SecureOtaUpdater.acceptedResumeOffset(
            1024L, 206, "bytes 1024-2047/4096"
        ))
        assertEquals(0L, SecureOtaUpdater.acceptedResumeOffset(1024L, 200, null))
        assertEquals(0L, SecureOtaUpdater.acceptedResumeOffset(0L, 200, null))
        assertFails {
            SecureOtaUpdater.acceptedResumeOffset(1024L, 206, "bytes 1023-2047/4096")
        }
        assertFails {
            SecureOtaUpdater.acceptedResumeOffset(1024L, 206, "bytes 1025-2047/4096")
        }
        assertFails {
            SecureOtaUpdater.acceptedResumeOffset(0L, 206, "bytes 0-2047/4096")
        }
        assertFails {
            SecureOtaUpdater.acceptedResumeOffset(1024L, 206, "bytes 1024-4096/4096")
        }
        assertFails {
            SecureOtaUpdater.acceptedResumeOffset(1024L, 206, "bytes 1024-2047/*")
        }
        assertFails {
            SecureOtaUpdater.acceptedResumeOffset(-1L, 200, null)
        }
    }

    @Test fun `partial download cache identity is checksum-scoped and rejects untrusted inputs`() {
        val hashA = "ab".repeat(32)
        val hashB = "cd".repeat(32)
        val key = SecureOtaUpdater.otaCacheName(153L, hashA)
        assertEquals(key, SecureOtaUpdater.otaCacheName(153L, hashA))
        assertTrue(key.startsWith("lanu-153-"))
        assertNotEquals(key, SecureOtaUpdater.otaCacheName(153L, hashB))
        assertNotEquals(key, SecureOtaUpdater.otaCacheName(154L, hashA))
        assertFalse(key.contains(".."))
        assertFails { SecureOtaUpdater.otaCacheName(0L, hashA) }
        assertFails { SecureOtaUpdater.otaCacheName(153L, "../app.apk") }
        assertFails { SecureOtaUpdater.otaCacheName(153L, "") }
    }

    @Test fun `content range body length and encoding must agree before writing`() {
        val accept = SecureOtaUpdater::validateDownloadResponse
        assertEquals(512L, accept(512L, 206, "bytes 512-1023/2048", 512L, "identity"))
        assertEquals(0L, accept(512L, 200, null, 2048L, null))
        assertEquals(0L, accept(0L, 200, null, -1L, null))
        assertFails { accept(512L, 206, "bytes 512-1023/2048", 511L, null) }
        assertFails { accept(512L, 206, "bytes 512-1023/2048", 513L, null) }
        assertFails { accept(512L, 206, "bytes 512-1023/2048", 512L, "gzip") }
        assertFails { accept(0L, 200, "bytes 0-511/2048", 512L, null) }
        assertFails { accept(0L, 200, null, 100L * 1024 * 1024, null) }
        assertFails { accept(0L, 206, "bytes 0-511/2048", 512L, null) }
        assertFails { accept(512L, 204, null, 0L, null) }
    }

    @Test fun `cache has full digest identity and purges expired owned files only`() {
        val cache = java.io.File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "ota-prune-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val a = SecureOtaUpdater.otaCacheName(160L, "ab".repeat(32))
            val b = SecureOtaUpdater.otaCacheName(161L, "cd".repeat(32))
            val c = SecureOtaUpdater.otaCacheName(162L, "ef".repeat(32))
            assertTrue(a.endsWith("ab".repeat(32)))
            val stale = java.io.File(cache, "$a.part.apk").apply {
                writeBytes(ByteArray(32))
                setLastModified(1_000L)
            }
            val recent = java.io.File(cache, "$b.apk").apply {
                writeBytes(ByteArray(64))
                setLastModified(900_000L)
            }
            val protected = java.io.File(cache, "$c.apk").apply {
                writeBytes(ByteArray(12))
                setLastModified(1_000L)
            }
            val other = java.io.File(cache, "secret-not-ota.db").apply { writeText("retain") }
            SecureOtaUpdater.pruneOtaCache(cache, setOf(protected.name), now = 1_000_000L +
                java.util.concurrent.TimeUnit.DAYS.toMillis(3))
            assertFalse(stale.exists())
            assertFalse(recent.exists())
            assertTrue(protected.exists())
            assertTrue(other.exists())
        } finally { cache.deleteRecursively() }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Untrusted update metadata must fail closed")
        } catch (_: IllegalArgumentException) {
            // Required security rejection.
        }
    }
}
