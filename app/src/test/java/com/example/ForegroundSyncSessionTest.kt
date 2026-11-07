package com.example

import com.example.features.communication.ForegroundSyncPump
import com.example.features.communication.ForegroundSyncSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncSessionTest {
    @Test fun sharedOwnersLastExitAndStaleCallbacksHaveOneVisibilityEpoch() = runTest {
        val callbacks = mutableListOf<SendChannel<Unit>>()
        var collectors = 0; var calls = 0
        val hints = callbackFlow<Unit> {
            collectors++; callbacks += channel
            awaitClose { collectors-- }
        }
        val session = ForegroundSyncSession(this, hints, { false }, clockMillis = { testScheduler.currentTime }) {
            calls++; ForegroundSyncPump.Attempt(true)
        }
        try {
            session.request(); runCurrent(); assertEquals(0, calls)
            session.start("home"); session.start("home"); session.start("chat")
            runCurrent(); assertEquals(1, calls); assertEquals(1, collectors)
            session.stop("home"); advanceTimeBy(5000); runCurrent()
            assertEquals(2, calls); assertEquals(1, collectors)
            session.stop("chat"); runCurrent()
            assertEquals(0, collectors)
            assertTrue(callbacks[0].trySend(Unit).isFailure)
            session.request(); advanceTimeBy(60000); runCurrent(); assertEquals(2, calls)
            session.start("home"); runCurrent(); assertEquals(3, calls)
            assertEquals(1, collectors)
            callbacks[0].trySend(Unit)
            advanceTimeBy(2500); runCurrent(); assertEquals(3, calls)
            callbacks[1].trySend(Unit); runCurrent(); assertEquals(4, calls)
        } finally { session.stop("home"); session.stop("chat"); runCurrent() }
    }

    @Test fun callbackFailureKeepsPeriodicFallbackAndManualWakeWorking() = runTest {
        var calls = 0
        val session = ForegroundSyncSession(this, flow { throw SecurityException("unavailable") }, { false }, clockMillis = { testScheduler.currentTime }) {
            calls++; ForegroundSyncPump.Attempt(true)
        }
        try {
            session.start("home"); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(5000); runCurrent(); assertEquals(2, calls)
            session.request(); advanceTimeBy(2500); runCurrent(); assertEquals(3, calls)
        } finally { session.stop("home"); runCurrent() }
    }

    @Test fun lastOwnerCancelsInflightRequestAndReopenCannotReviveOldWork() = runTest {
        var calls = 0; var cancelled = 0
        val session = ForegroundSyncSession(this, flow { awaitCancellation() }, { true }, clockMillis = { testScheduler.currentTime }) {
            calls++
            try { awaitCancellation() } finally { cancelled++ }
        }
        session.start("chat"); runCurrent(); assertEquals(1, calls)
        session.stop("chat"); runCurrent(); assertEquals(1, cancelled)
        session.request(); advanceTimeBy(60000); runCurrent(); assertEquals(1, calls)
        session.start("chat"); runCurrent(); assertEquals(2, calls)
        session.stop("chat"); runCurrent(); assertEquals(2, cancelled)
    }

    @Test fun blankOwnerNeverStartsNetworkWork() = runTest {
        var calls = 0
        val session = ForegroundSyncSession(this, flow { awaitCancellation() }, { false }, clockMillis = { testScheduler.currentTime }) {
            calls++; ForegroundSyncPump.Attempt(true)
        }
        session.start(" "); session.request(); runCurrent(); assertEquals(0, calls)
    }
    @Test fun rapidCloseAndReopenPreservesMinimumCadenceAndServerCooldown() = runTest {
        var calls = 0
        val session = ForegroundSyncSession(this, flow { awaitCancellation() }, { true },
            clockMillis = { testScheduler.currentTime }) {
            calls++; ForegroundSyncPump.Attempt(false, 60000L)
        }
        try {
            session.start("chat"); runCurrent(); assertEquals(1, calls)
            session.stop("chat"); runCurrent()
            session.start("chat"); session.request(); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(59999); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        } finally { session.stop("chat"); runCurrent() }
    }

    @Test fun repeatedVisibilityChangesCannotResetMinimumRequestInterval() = runTest {
        var calls = 0
        val session = ForegroundSyncSession(this, flow { awaitCancellation() }, { false },
            clockMillis = { testScheduler.currentTime }) {
            calls++; ForegroundSyncPump.Attempt(true)
        }
        try {
            repeat(100) { session.start("home"); runCurrent(); session.stop("home"); runCurrent() }
            assertEquals(1, calls)
            session.start("home"); runCurrent()
            advanceTimeBy(2499); runCurrent(); assertEquals(1, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        } finally { session.stop("home"); runCurrent() }
    }

}
