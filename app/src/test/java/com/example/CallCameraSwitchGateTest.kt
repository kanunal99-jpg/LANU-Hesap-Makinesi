package com.example

import com.example.core.calls.CallCameraSwitchGate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A pure-state unit regression; real Camera1/Camera2 needs physical OEM acceptance. */
class CallCameraSwitchGateTest {
    @Test fun concurrentDoubleTapCannotInvokeCameraSwitchTwice() {
        val gate = CallCameraSwitchGate()
        assertNull(gate.begin(canSwitch = false))
        val first = assertNotNullAndReturn(gate.begin(canSwitch = true))
        assertNull("Second tap while native switch is pending must do nothing", gate.begin(true))
        assertTrue(gate.finish(first))
        assertNotNull(gate.begin(true))
    }

    @Test fun staleOemCompletionCannotFinishASubsequentSwitch() {
        val gate = CallCameraSwitchGate()
        val first = assertNotNullAndReturn(gate.begin(true))
        assertTrue(gate.finish(first)) // Normal native callback or timeout
        val next = assertNotNullAndReturn(gate.begin(true))
        assertFalse(gate.finish(first))
        assertNull(gate.begin(true)) // New switch must remain pending
        assertTrue(gate.finish(next))
        assertNotNull(gate.begin(true))
    }

    @Test fun callClosureRejectsPreviousAndFutureCameraCallbacks() {
        val gate = CallCameraSwitchGate()
        val old = assertNotNullAndReturn(gate.begin(true))
        gate.invalidate()
        assertFalse(gate.finish(old))
        assertNull(gate.begin(false))
        val fresh = assertNotNullAndReturn(gate.begin(true))
        assertFalse(gate.finish(old))
        assertNull(gate.begin(true))
        assertTrue(gate.finish(fresh))
    }

    @Test fun asynchronousOemCallbackAndUiRaceStillAllowOneOwnedSwitch() {
        val gate = CallCameraSwitchGate()
        val first = assertNotNullAndReturn(gate.begin(true))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread(start = true) {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            assertTrue(gate.finish(first))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertNull(gate.begin(true))
        release.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertNotNull(gate.begin(true))
    }

    private fun assertNotNullAndReturn(ticket: Long?): Long {
        assertNotNull(ticket)
        return ticket!!
    }
}
