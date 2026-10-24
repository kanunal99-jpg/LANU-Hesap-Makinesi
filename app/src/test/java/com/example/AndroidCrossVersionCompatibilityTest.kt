package com.example

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.core.diagnostics.CrashDiagnostics
import com.example.core.update.SecureOtaUpdater
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Platform compatibility regression, NOT manufacturer hardware certification.
 *
 * Verify the unchanged core app manifest/OTA/diagnostics assumptions against
 * old Android (API 23), pre-scoped-storage (29), and recent Android (35).
 * No Google Play Services dependency or real device is required by this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 29, 35])
class AndroidCrossVersionCompatibilityTest {
    @Test fun appManifestDoesNotRequireBroadMediaCameraOrMicPermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS
        )
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.CAMERA in permissions)
        assertFalse(Manifest.permission.RECORD_AUDIO in permissions)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions)
        assertFalse("android.permission.READ_MEDIA_IMAGES" in permissions)
        assertFalse("android.permission.READ_MEDIA_VIDEO" in permissions)

        // Do not create an exported file-serving endpoint for any OEM.
        val provider = info.providers?.singleOrNull {
            it.authority == "${context.packageName}.ota.provider"
        }
        assertNotNull("APK installation FileProvider must exist across API versions", provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(
            0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }

    @Test fun installedOtaVersionAndCrashFallbackAvoidUnsupportedPlatformApi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Version code must be readable", SecureOtaUpdater.installedVersion(context) >= 0L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertNull("Old Android lacks ApplicationExitInfo API",
                CrashDiagnostics.mostRecentAbnormalExit(context))
        }
    }
}
