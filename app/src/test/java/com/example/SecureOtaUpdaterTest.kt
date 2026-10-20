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

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Untrusted update metadata must fail closed")
        } catch (_: IllegalArgumentException) {
            // Required security rejection.
        }
    }
}
