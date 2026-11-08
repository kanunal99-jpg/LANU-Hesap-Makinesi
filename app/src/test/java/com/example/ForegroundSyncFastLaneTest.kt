package com.example

import com.example.features.communication.ForegroundSyncPump
import com.example.features.communication.ForegroundSyncSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncFastLaneTest {
    @Test fun liveHintsWakeAfterOneSecondButCannotBypassServerCooldownAndExit() = runTest {
        val hints = Channel<Unit>(Channel.CONFLATED)
        var calls = 0
        val session = ForegroundSyncSession(this, hints.receiveAsFlow(), { true },
            clockMillis = { testScheduler.currentTime }, minimumInterval = { 1000L }) {
            calls++
            ForegroundSyncPump.Attempt(true, if (calls == 2) 60000L else 0L)
        }
        session.start("private")
        try {
            runCurrent(); assertEquals(1, calls)
            hints.trySend(Unit); advanceTimeBy(999L); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
            repeat(100) { hints.trySend(Unit) }
            advanceTimeBy(59999L); runCurrent(); assertEquals(2, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(3, calls)
            session.stop("private"); runCurrent()
            hints.trySend(Unit); advanceTimeBy(10000L); runCurrent(); assertEquals(3, calls)
        } finally { session.stop("private"); hints.cancel() }
    }
}
