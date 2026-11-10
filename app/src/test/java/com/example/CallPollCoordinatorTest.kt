package com.example
import com.example.core.calls.CallPollCoordinator
import com.example.core.messaging.RelayException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class CallPollCoordinatorTest {
    @Test fun concurrentWakeHintsCoalesceAndRetainOneBoundedFollowUp() = runTest {
        var calls=0; var active=0; var peak=0
        val first=CompletableDeferred<Unit>()
        val poller=CallPollCoordinator({ testScheduler.currentTime }) {
            active++; peak=maxOf(peak,active); calls++
            try { if(calls==1) first.await() } finally { active-- }
        }
        val owner=launch { poller.run() }; runCurrent()
        val joins=List(30) { launch { poller.run(requestFollowUp=true) } }; runCurrent()
        assertEquals(1,calls); first.complete(Unit); runCurrent()
        assertEquals(1,calls); advanceTimeBy(1000); runCurrent()
        owner.join(); joins.joinAll(); assertEquals(2,calls); assertEquals(1,peak)
    }
    @Test fun cancelledJoinerDoesNotCancelOwnerAndCancelledOwnerDoesNotPoisonJoiner() = runTest {
        var calls=0; val hold=CompletableDeferred<Unit>()
        val poller=CallPollCoordinator({ testScheduler.currentTime }) { calls++; if(calls==1) hold.await() }
        val owner=launch { poller.run() }; runCurrent()
        val abandoned=launch { poller.run() }; runCurrent(); abandoned.cancelAndJoin()
        assertTrue(owner.isActive)
        val survivor=launch { poller.run() }; runCurrent()
        owner.cancelAndJoin(); runCurrent(); assertTrue(survivor.isActive)
        advanceTimeBy(1000); runCurrent(); survivor.join(); assertEquals(2,calls)
    }
    @Test fun everyTriggerRespectsShared429DeadlineAndWaitingCanBeCancelled() = runTest {
        var calls=0
        val poller=CallPollCoordinator({ testScheduler.currentTime }) {
            calls++; if(calls==1) throw RelayException(429,"bounded",10_000L)
        }
        try { poller.run() ; fail() } catch(e: RelayException) { assertEquals(429,e.status) }
        val waiting=launch { poller.run(true) }; runCurrent()
        advanceTimeBy(9999); runCurrent(); assertEquals(1,calls)
        waiting.cancelAndJoin()
        val next=launch { poller.run(true) }; runCurrent(); assertEquals(1,calls)
        advanceTimeBy(1); runCurrent(); next.join(); assertEquals(2,calls)
    }
    @Test fun repeated503BacksOffWithoutHttpStormAndResetsAfterSuccess() = runTest {
        var calls = 0
        val poller = CallPollCoordinator({ testScheduler.currentTime }) {
            calls++
            if (calls <= 3) throw RelayException(503, "temporary network failure")
        }
        repeat(3) { attempt ->
            val outcome = async { runCatching { poller.run() }.exceptionOrNull() }
            runCurrent()
            if (attempt == 0) assertEquals(1, calls)
            val requiredAt = when (attempt) { 0 -> 0L; 1 -> 1_000L; else -> 3_000L }
            if (testScheduler.currentTime < requiredAt) {
                advanceTimeBy(requiredAt - testScheduler.currentTime - 1)
                runCurrent()
                assertEquals(attempt, calls - 1)
                advanceTimeBy(1)
                runCurrent()
            }
            assertTrue(outcome.await() is RelayException)
            assertEquals(attempt + 1, calls)
        }
        val recovered = async { poller.run() }
        runCurrent()
        advanceTimeBy(3_999); runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(1); runCurrent()
        recovered.await()
        assertEquals(4, calls)
        // A successful poll clears stale transport cooldown.
        advanceTimeBy(1_000)
        poller.run()
        assertEquals(5, calls)
    }

    @Test fun validatedHandoverLiftsOnlyTransientBackoffAfterMinimumCadence() = runTest {
        var calls = 0
        val poller = CallPollCoordinator({ testScheduler.currentTime }) {
            calls++
            if (calls <= 2) throw RelayException(503, "old network")
        }
        try { poller.run(); fail("expected network failure") } catch (_: RelayException) {}
        advanceTimeBy(1_000)
        try { poller.run(); fail("expected second network failure") } catch (_: RelayException) {}
        assertEquals(2, calls)
        val recovered = async { poller.run() }
        runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(2, calls)
        poller.validatedNetworkHint()
        runCurrent()
        advanceTimeBy(499); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1); runCurrent()
        recovered.await()
        assertEquals(3, calls) // At 2s rather than 3s after validated change.
    }

    @Test fun validatedNetworkDoesNotBypassServerQuotaOrIdentityCooldown() = runTest {
        for ((status, deadline) in listOf(429 to 10_000L, 401 to 30_000L)) {
            var calls = 0
            val poller = CallPollCoordinator({ testScheduler.currentTime }) {
                calls++
                if (calls == 1) throw RelayException(status, "restricted", 10_000L)
            }
            try { poller.run(); fail("expected $status") } catch (_: RelayException) {}
            val waiting = async { poller.run() }
            runCurrent()
            advanceTimeBy(1_000); runCurrent()
            poller.validatedNetworkHint()
            runCurrent()
            advanceTimeBy(deadline - 1_001); runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(1); runCurrent()
            waiting.await()
            assertEquals(2, calls)
        }
    }

    @Test fun staleFailureFromPreHandoverNetworkCannotPoisonNewRoute() = runTest {
        var calls = 0
        val held = CompletableDeferred<Unit>()
        val poller = CallPollCoordinator({ testScheduler.currentTime }) {
            calls++
            if (calls == 1) {
                held.await()
                throw RelayException(503, "old network failed after new route arrived")
            }
        }
        val stale = async { runCatching { poller.run() }.exceptionOrNull() }
        runCurrent()
        poller.validatedNetworkHint()
        held.complete(Unit)
        runCurrent()
        assertTrue(stale.await() is RelayException)
        val fresh = async { poller.run() }
        runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1); runCurrent()
        fresh.await()
        assertEquals(2, calls)
    }

    @Test fun failureIsSharedWithoutDuplicatePollAndNextFlightStillWorks() = runTest {
        var calls=0; val hold=CompletableDeferred<Unit>()
        val poller=CallPollCoordinator({ testScheduler.currentTime }) {
            calls++; if(calls==1) { hold.await(); throw RelayException(503,"bounded") }
        }
        val a=async { runCatching { poller.run() }.exceptionOrNull() }
        runCurrent(); val b=async { runCatching { poller.run(true) }.exceptionOrNull() }; runCurrent()
        hold.complete(Unit); runCurrent()
        assertTrue(a.await() is RelayException); assertTrue(b.await() is RelayException); assertEquals(1,calls)
        advanceTimeBy(1000); poller.run(); assertEquals(2,calls)
    }
}
