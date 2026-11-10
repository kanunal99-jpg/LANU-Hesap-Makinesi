package com.example

import com.example.core.calls.CallCameraCaptureProfilePolicy
import com.example.core.calls.CallCameraStartupPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CallCameraCaptureProfilePolicyTest {
    private val profiles = CallCameraCaptureProfilePolicy.profiles

    @Test fun boundedLowBandwidthAndOemFallbackHasValidOrderedCaptureFormats() {
        assertEquals(3, profiles.size)
        assertEquals(12, CallCameraCaptureProfilePolicy.MAX_ATTEMPTS_PER_HAL)
        assertTrue(CallCameraCaptureProfilePolicy.mayAttempt(0))
        assertTrue(CallCameraCaptureProfilePolicy.mayAttempt(11))
        assertFalse(CallCameraCaptureProfilePolicy.mayAttempt(12))
        assertFalse(CallCameraCaptureProfilePolicy.mayAttempt(-1))
        assertEquals(CallCameraCaptureProfilePolicy.Profile(1280, 720, 24), profiles[0])
        assertEquals(CallCameraCaptureProfilePolicy.Profile(640, 480, 24), profiles[1])
        assertEquals(CallCameraCaptureProfilePolicy.Profile(320, 240, 15), profiles[2])
        assertEquals(profiles.size, profiles.toSet().size)
        profiles.forEach { assertTrue(CallCameraCaptureProfilePolicy.valid(it)) }
        for (i in 1 until profiles.size)
            assertTrue(profiles[i].width * profiles[i].height <
                profiles[i - 1].width * profiles[i - 1].height)
        assertFalse(CallCameraCaptureProfilePolicy.valid(
            CallCameraCaptureProfilePolicy.Profile(0, 720, 24)))
        assertFalse(CallCameraCaptureProfilePolicy.valid(
            CallCameraCaptureProfilePolicy.Profile(1280, 721, 24)))
        assertFalse(CallCameraCaptureProfilePolicy.valid(
            CallCameraCaptureProfilePolicy.Profile(640, 480, 500)))
    }

    @Test fun failedHighResolutionReleasesCameraBeforeLowerResolutionSucceeds() {
        val events = mutableListOf<String>()
        var selected: CallCameraCaptureProfilePolicy.Profile? = null
        for (profile in profiles) {
            val candidate = "camera-${profile.width}"
            val result = CallCameraStartupPolicy.tryStart(candidate,
                start = {
                    events += "open:${profile.width}"
                    if (profile.width > 640) error("OEM HAL cannot do HD")
                },
                releaseFailed = { events += "release:${profile.width}" })
            if (result != null) { selected = profile; break }
        }
        assertEquals(profiles[1], selected)
        assertEquals(listOf("open:1280", "release:1280", "open:640"), events)
    }

    @Test fun cancellationDoesNotFallbackIntoAnotherCameraOrRestartVideo() {
        val events = mutableListOf<String>()
        try {
            for (profile in profiles) {
                CallCameraStartupPolicy.tryStart("camera", start = {
                    events += "open:${profile.width}"
                    throw CancellationException("user ended secure call")
                }, releaseFailed = { events += "release:${profile.width}" })
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf("open:1280", "release:1280"), events)
    }
}
