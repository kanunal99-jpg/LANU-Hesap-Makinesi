package com.example

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakeNotification
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import com.example.core.diagnostics.CrashDiagnostics
import com.example.core.update.SecureOtaUpdater
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Isolated Android 10/API 29 regression. Does not claim Samsung/Huawei hardware
 * acceptance, FCM delivery, or data-preserving OTA on a physical telephone.
 *
 * A single SDK sandbox avoids the ZIP filesystem collision seen when the
 * same test attempted to switch platform SDKs inside one Robolectric class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidQPrivacyCompatibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun resetOptIn() {
        CallWakePreferences.setEnabled(context, false)
        CallWakePreferences.acknowledgeRemoteRevoke(context)
        CallWakePolicy.privateAreaVisible = false
    }

    @Test fun AndroidTenManifestKeepsOtaAndCallWakePrivateWithoutBroadStorage() {
        assertEquals(29, Build.VERSION.SDK_INT)
        val pkg = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_SERVICES)
        val names = pkg.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.INTERNET in names)
        assertTrue(Manifest.permission.RECORD_AUDIO in names)
        assertTrue(Manifest.permission.CAMERA in names)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in names)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in names)
        assertFalse("android.permission.READ_MEDIA_IMAGES" in names)
        assertFalse("android.permission.READ_MEDIA_VIDEO" in names)

        val ota = pkg.providers?.singleOrNull {
            it.authority == "${context.packageName}.ota.provider"
        }
        assertNotNull("OTA provider must exist for Android 10", ota)
        assertFalse("OTA files cannot be globally exported", ota!!.exported)
        assertTrue("Installer receives temporary URI grant only", ota.grantUriPermissions)

        val wake = pkg.services?.singleOrNull {
            it.name.endsWith(".CallWakeMessagingService")
        }
        assertNotNull("Call-only FCM service must be declared", wake)
        assertFalse("Private notification service must not be exported", wake!!.exported)

        val app = context.packageManager.getApplicationInfo(context.packageName,
            PackageManager.GET_META_DATA)
        assertEquals(0, app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertNotNull(app.metaData)
        assertFalse("No FCM token before private-area opt-in",
            app.metaData.getBoolean("firebase_messaging_auto_init_enabled", true))
        assertFalse("No Firebase analytics implicit collection",
            app.metaData.getBoolean("firebase_analytics_collection_enabled", true))
        assertTrue(SecureOtaUpdater.versionCode(pkg) >= 0L)
    }

    @Test fun AndroidTenDoesNotWakePrivateCallWithoutOptInOrReadUnsupportedExitApi() {
        assertFalse(CallWakePolicy.requiresNotificationPermission(29))
        assertFalse(CallWakePreferences.isEnabled(context))
        val now = 1_710_000_000_000L
        assertFalse(CallWakeNotification.show(context, mapOf(
            "type" to CallWakePolicy.TYPE, "at" to now.toString()), now))
        assertNull(CrashDiagnostics.mostRecentAbnormalExit(context))
        assertEquals(CrashDiagnostics.SystemExitSnapshot(),
            CrashDiagnostics.recentSnapshot(context, now))
    }
}
