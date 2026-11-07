package com.example

import com.example.features.communication.ForegroundSyncPump
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncPumpTest {
    @Test fun triggersDuringInflightSyncAreConflatedRetainedAndNeverRunConcurrently() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        val gate = CompletableDeferred<Unit>()
        var calls = 0; var active = 0; var maxActive = 0
        val job = launch {
            ForegroundSyncPump.run(requests, { false }) {
                calls++; active++; maxActive = maxOf(maxActive, active)
                try { if (calls == 1) gate.await() }
                finally { active-- }
                ForegroundSyncPump.Attempt(true)
            }
        }
        try {
            runCurrent(); assertEquals(1, calls)
            repeat(1000) { requests.trySend(Unit) }
            runCurrent(); assertEquals(1, calls)
            gate.complete(Unit); runCurrent()
            advanceTimeBy(2499); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
            assertEquals(1, maxActive)
            advanceTimeBy(4999); runCurrent(); assertEquals(2, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(3, calls)
        } finally { job.cancelAndJoin(); requests.cancel() }
    }

    @Test fun validatedRecoveryCanWakeSixtySecondBackoffWithoutExceedingRequestCadence() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var calls = 0
        val job = launch {
            ForegroundSyncPump.run(requests, { false }) {
                calls++
                ForegroundSyncPump.Attempt(false)
            }
        }
        try {
            runCurrent()
            advanceTimeBy(70000); runCurrent() // attempts at 0, 10, 30, 70s
            assertEquals(4, calls)
            advanceTimeBy(3000); requests.trySend(Unit); runCurrent()
            assertEquals(5, calls) // no wait until the previous 130s deadline
            repeat(500) { requests.trySend(Unit) }
            advanceTimeBy(2499); runCurrent(); assertEquals(5, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(6, calls)
        } finally { job.cancelAndJoin(); requests.cancel() }
    }

    @Test fun refreshAndNetworkStormCannotBypassServerRetryAfter() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var calls = 0
        val job = launch {
            ForegroundSyncPump.run(requests, { true }) {
                calls++
                ForegroundSyncPump.Attempt(false, retryAfterMillis = 60000L)
            }
        }
        try {
            runCurrent()
            repeat(1000) { requests.trySend(Unit) }
            advanceTimeBy(59999); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        } finally { job.cancelAndJoin(); requests.cancel() }
    }

    @Test fun sustainedWakeStormStaysWithinTwentyFourForegroundSyncsPerMinute() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var calls = 0
        val job = launch {
            ForegroundSyncPump.run(requests, { true }) { calls++; ForegroundSyncPump.Attempt(true) }
        }
        try {
            runCurrent()
            repeat(599) { requests.trySend(Unit); advanceTimeBy(100); runCurrent() }
            assertEquals(24, calls)
        } finally { job.cancelAndJoin(); requests.cancel() }
    }

    @Test fun cancellationInterruptsInflightNetworkAndNoLateRequestRevivesClosedPump() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var calls = 0; var closed = false
        val job = launch {
            ForegroundSyncPump.run(requests, { false }) {
                calls++
                try { awaitCancellation() } finally { closed = true }
            }
        }
        runCurrent(); job.cancelAndJoin()
        assertTrue(closed)
        requests.trySend(Unit)
        advanceTimeBy(120000); runCurrent()
        assertEquals(1, calls)
        requests.cancel()
    }
}
