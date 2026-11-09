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
