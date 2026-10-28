package com.example

import com.example.core.calls.CallCameraFallback
import com.example.core.calls.CallCameraStartupPolicy
import org.junit.Assert.*
import org.junit.Test

class CallCameraFallbackTest {
    private val front = CallCameraFallback.Device("front", true)
    private val back = CallCameraFallback.Device("back", false)
    @Test fun firstRealCameraIsPreferredAndOnlyOpenedOnce() {
        val calls = mutableListOf<String>()
        val result = CallCameraFallback.select(
            listOf({ listOf(back, front) }, { listOf(front) })
        ) { idx, d ->
            calls += "$idx:${d.name}"
            if (d.name == "front") "camera2" else null
        }
        assertEquals("camera2", result)
        assertEquals(listOf("0:front"), calls)
    }
    @Test fun camera2EnumeratorCrashUsesActualCamera1Fallback() {
        val result = CallCameraFallback.select(
            listOf({ throw IllegalStateException("OEM camera2 HAL") }, { listOf(back) })
        ) { idx, _ -> if (idx == 1) "camera1" else null }
        assertEquals("camera1", result)
    }
    @Test fun camera2CreateCrashTriesOtherHardwareBeforeCamera1() {
        val calls = mutableListOf<String>()
        val result = CallCameraFallback.select(
            listOf({ listOf(back, front) }, { listOf(front) })
        ) { idx, d ->
            calls += "$idx:${d.name}"
            if (idx == 0 && d.name == "front") throw IllegalStateException("camera2 broken")
            if (idx == 0) null else "camera1"
        }
        assertEquals("camera1", result)
        assertEquals(listOf("0:front", "0:back", "1:front"), calls)
    }
    @Test fun runawayOemCameraListDoesNotExhaustRetriesOrStarveCamera1() {
        val attempted = mutableListOf<String>()
        val huge = (0 until 500).map {
            CallCameraFallback.Device("broken-$it", it % 2 == 0)
        }
        val result = CallCameraFallback.select(
            listOf({ huge }, { listOf(back) })
        ) { index, device ->
            attempted += "$index:${device.name}"
            if (index == 1) "camera1-success" else null
        }
        assertEquals("camera1-success", result)
        assertEquals(33, attempted.size) // 16 front + 16 rear + 1 Camera1
    }

    @Test fun malformedExtraOemProvidersAreNeverEnumerated() {
        var excessEnumerated = 0
        val providers: List<() -> List<CallCameraFallback.Device>> =
            (0 until 20).map { { excessEnumerated++; emptyList() } }
        val result = CallCameraFallback.select(providers) { _, _ -> "unexpected" }
        assertNull(result)
        assertEquals(4, excessEnumerated)
    }

    @Test fun noWorkingCameraFailsClosedWithNoFabricatedCapturer() {
        val result = CallCameraFallback.select(
            listOf({ emptyList() }, { listOf(front) })
        ) { _, _ -> null as String? }
        assertNull(result)
    }
    @Test fun noSourcesOrAllEnumerationCrashesFailsClosed() {
        assertNull(CallCameraFallback.select(emptyList<() -> List<CallCameraFallback.Device>>()) { _, _ -> "fake" })
        assertNull(CallCameraFallback.select(listOf({ throw RuntimeException("HAL") })) { _, _ -> "fake" })
    }

    @Test fun cancellationDuringEnumerationStopsAllFallbackProviders() {
        var secondEnumerated = false
        try {
            CallCameraFallback.select(
                listOf(
                    { throw java.util.concurrent.CancellationException("private area closed") },
                    { secondEnumerated = true; listOf(front) }
                )
            ) { _, _ -> "camera" }
            fail("Cancellation must propagate")
        } catch (_: java.util.concurrent.CancellationException) {}
        assertFalse("No alternate camera may start after cancellation", secondEnumerated)
    }

    @Test fun cancellationDuringOpenNeverAttemptsAnotherHal() {
        val opened = mutableListOf<String>()
        try {
            CallCameraFallback.select(
                listOf({ listOf(front, back) }, { listOf(back) })
            ) { index, d ->
                opened += "$index:${d.name}"
                throw java.util.concurrent.CancellationException("call ended")
            }
            fail("Cancellation must propagate")
        } catch (_: java.util.concurrent.CancellationException) {}
        assertEquals(listOf("0:front"), opened)
    }


    @Test fun realCallClosureDuringCameraStartupReleasesCaptureAndNeverOpensCamera1() {
        val released = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var closed = false
        try {
            CallCameraFallback.select(
                listOf(
                    { listOf(front) },
                    { listOf(back) }
                )
            ) { index, device ->
                if (closed) throw java.util.concurrent.CancellationException("call ended")
                opened += "$index:${device.name}"
                CallCameraStartupPolicy.tryStart(device,
                    start = {
                        // Simulate user leaving the private area after Camera2
                        // opened but before startCapture reports success.
                        closed = true
                        if (closed) throw java.util.concurrent.CancellationException("private area closed")
                    },
                    releaseFailed = { released += it.name }
                )
            }
            fail("Real cancellation must abort alternate HAL startup")
        } catch (_: java.util.concurrent.CancellationException) {}
        assertEquals(listOf("0:front"), opened)
        assertEquals(listOf("front"), released)
    }
}
