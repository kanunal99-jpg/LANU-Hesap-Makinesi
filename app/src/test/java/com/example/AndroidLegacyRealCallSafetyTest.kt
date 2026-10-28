package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallCameraFallback
import com.example.core.calls.CallAudioRoutePolicy
import com.example.core.security.MediaPickerReturnPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android 6/API23 regression, not a physical OEM camera acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class Android23RealCallSafetyTest {
    @Test fun cameraHalFailureFallsBackWithoutInventingAWorkingVideoTrack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertTrue(Manifest.permission.CAMERA in permissions)
        val result = CallCameraFallback.select(listOf(
            { throw IllegalStateException("Synthetic Camera2 failure") },
            { listOf(CallCameraFallback.Device("camera1", false)) }
        )) { idx, _ -> if (idx == 1) "camera1" else null }
        assertEquals("camera1", result)
        assertNull(CallCameraFallback.select(listOf({ emptyList() })) { _, _ -> "fake" })
    }

    @Test fun voiceRouteAndLockedPickerCannotBypassPrivateAreaOnApi23() {
        val routes = CallAudioRoutePolicy.priorities(
            setOf(CallAudioRoutePolicy.Route.EARPIECE, CallAudioRoutePolicy.Route.SPEAKER),
            isVideo = false
        )
        assertEquals(CallAudioRoutePolicy.Route.EARPIECE, routes.first())
        assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(
            requirePinOnReturn = false, pickerStartedElapsedMs = 100L,
            nowElapsedMs = 200L, isUnlocked = true, screenWasLocked = true
        ))
    }
}

/** Android 10/API29 regression, no Android 12+ audio API calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Android29RealCallSafetyTest {
    @Test fun videoCallPrefersRealSpeakerAndCancelsCameraFallback() {
        val routes = CallAudioRoutePolicy.priorities(
            setOf(CallAudioRoutePolicy.Route.EARPIECE, CallAudioRoutePolicy.Route.SPEAKER),
            isVideo = true
        )
        assertEquals(CallAudioRoutePolicy.Route.SPEAKER, routes.first())
        var attemptedCamera1 = false
        try {
            CallCameraFallback.select(listOf(
                { throw java.util.concurrent.CancellationException("Call ended") },
                { attemptedCamera1 = true; emptyList() }
            )) { _, _ -> "fake" }
            fail("Cancellation must abort fallback")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertFalse(attemptedCamera1)
    }
}
