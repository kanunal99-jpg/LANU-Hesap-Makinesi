package com.example

import com.example.core.calls.CallCameraStartupPolicy
import com.example.core.calls.CallCameraFallback
import org.junit.Assert.*
import org.junit.Test

class CallCameraStartupPolicyTest {
    data class Handle(val id: String)
    @Test fun camera2InitializeFailureReleasesHandleAndUsesCamera1() {
        val disposed = mutableListOf<String>()
        val devices = listOf(
            { listOf(CallCameraFallback.Device("cam2", true)) },
            { listOf(CallCameraFallback.Device("cam1", false)) }
        )
        val result = CallCameraFallback.select(devices) { index, d ->
            CallCameraStartupPolicy.tryStart(Handle(d.name),
                start = { if (index == 0) throw IllegalStateException("camera2 init") },
                releaseFailed = { disposed += it.id }
            )
        }
        assertEquals("cam1", result?.id)
        assertEquals(listOf("cam2"), disposed)
    }

    @Test fun nativeStartFailureReleasesBeforeNextCameraAttempt() {
        val calls = mutableListOf<String>()
        val candidate = CallCameraStartupPolicy.tryStart(Handle("front"),
            start = { calls += "start"; throw IllegalStateException("startCapture failed") },
            releaseFailed = { calls += "release" })
        assertNull(candidate)
        assertEquals(listOf("start", "release"), calls)
    }

    @Test fun successfulCaptureMustNotBeReleased() {
        var releases = 0
        val camera = Handle("camera2")
        assertSame(camera, CallCameraStartupPolicy.tryStart(camera,
            start = { }, releaseFailed = { releases++ }))
        assertEquals(0, releases)
    }

    @Test fun cleanupExceptionMustNotBlockFallback() {
        val first = CallCameraStartupPolicy.tryStart(Handle("bad"),
            start = { throw RuntimeException("HAL") },
            releaseFailed = { throw RuntimeException("broken cleanup") })
        assertNull(first)
        val second = CallCameraStartupPolicy.tryStart(Handle("good"),
            start = { }, releaseFailed = { fail("should not be released") })
        assertNotNull(second)
    }

    @Test fun nullHandleNeverInitializesOrReleases() {
        var invoked = false
        assertNull(CallCameraStartupPolicy.tryStart<Handle>(null,
            start = { invoked = true }, releaseFailed = { invoked = true }))
        assertFalse(invoked)
    }
}
