package com.example

import com.example.core.calls.CallCameraFallback
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
}
