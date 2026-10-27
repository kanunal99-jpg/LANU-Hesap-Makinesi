package com.example

import com.example.core.calls.CallMediaControlTransaction
import org.junit.Assert.*
import org.junit.Test

class CallMediaControlTransactionTest {
    @Test fun successfulMuteUpdatesBothUnderlyingStateOwners() {
        var deviceMuted = false
        var trackMuted = false
        CallMediaControlTransaction.apply(
            previous = false, requested = true,
            setDevice = { deviceMuted = it },
            setTrack = { trackMuted = it; true }
        )
        assertTrue(deviceMuted)
        assertTrue(trackMuted)
    }

    @Test fun successfulCameraDisableUpdatesBothStateOwners() {
        var captureOn = true
        var trackOn = true
        CallMediaControlTransaction.apply(
            previous = true, requested = false,
            setDevice = { captureOn = it },
            setTrack = { trackOn = it; true }
        )
        assertFalse(captureOn)
        assertFalse(trackOn)
    }

    @Test fun trackFailureRollsBackCameraAndDoesNotReportSuccess() {
        var captureOn = true
        var trackOn = true
        var calls = 0
        try {
            CallMediaControlTransaction.apply(
                previous = true, requested = false,
                setDevice = { captureOn = it },
                setTrack = {
                    calls++
                    if (!it) false else { trackOn = it; true }
                }
            )
            fail("Expected failed track state transition")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kanalı"))
        }
        assertTrue(captureOn)
        assertTrue(trackOn)
        assertEquals(2, calls)
    }

    @Test fun nativeDeviceThrowAfterPartialMutationStillRollsBackBoth() {
        var deviceMuted = false
        var trackMuted = false
        val sentinel = IllegalArgumentException("vendor HAL failure")
        try {
            CallMediaControlTransaction.apply(
                previous = false, requested = true,
                setDevice = {
                    deviceMuted = it
                    if (it) throw sentinel
                },
                setTrack = { trackMuted = it; true }
            )
            fail("Expected native failure")
        } catch (e: IllegalArgumentException) {
            assertSame(sentinel, e)
        }
        assertFalse(deviceMuted)
        assertFalse(trackMuted)
    }

    @Test fun rollbackExceptionsNeverHideOriginalDeviceOrTrackError() {
        val sentinel = IllegalStateException("original failure")
        var calls = 0
        try {
            CallMediaControlTransaction.apply(
                previous = true, requested = false,
                setDevice = { if (it) throw UnsupportedOperationException("rollback broke") },
                setTrack = {
                    calls++
                    if (!it) throw sentinel
                    throw UnsupportedOperationException("rollback track broke")
                }
            )
            fail("Expected original failure")
        } catch (e: IllegalStateException) {
            assertSame(sentinel, e)
        }
        assertEquals(2, calls)
    }

    @Test fun noOpNeverTouchesMediaDeviceOrTrack() {
        CallMediaControlTransaction.apply(
            previous = false, requested = false,
            setDevice = { throw AssertionError("Unnecessary native mutation") },
            setTrack = { throw AssertionError("Unnecessary track mutation") }
        )
    }
}
