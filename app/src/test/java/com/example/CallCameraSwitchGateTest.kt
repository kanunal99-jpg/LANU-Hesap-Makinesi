package com.example

import com.example.core.calls.CallCameraSwitchGate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        assertNull("A closed call must never reopen its camera switch gate", gate.begin(true))
        gate.invalidate() // Idempotent, even if an OEM callback is still pending
        assertFalse(gate.finish(old))
        assertNull(gate.begin(true))
    }

    @Test fun asynchronousOemCallbackAndUiRaceStillAllowOneOwnedSwitch() {
        val gate = CallCameraSwitchGate()
        val first = assertNotNullAndReturn(gate.begin(true))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = thread(start = true) {
            try {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertTrue(gate.finish(first))
            } catch (failure: Throwable) {
                // A failed assertion on a worker thread must fail JUnit too.
                workerFailure.set(failure)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertNull(gate.begin(true))
        release.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertNull("Background OEM callback assertion failed", workerFailure.get())
        assertNotNull(gate.begin(true))
    }

    private fun assertNotNullAndReturn(ticket: Long?): Long {
        assertNotNull(ticket)
        return ticket!!
    }
}
