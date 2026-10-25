package com.example

import com.example.core.delivery.*
import com.example.core.messaging.RelayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeliveryRunTest {
    private class Store(var value: DeliverySnapshot = DeliverySnapshot()) : DeliveryStore {
        var rejectWrites = false
        override fun read() = value
        override fun saveOptions(options: DeliveryOptions) { value = value.copy(options = options) }
        override fun attempt(at: Long) { check(!rejectWrites); value = value.copy(lastAttemptAt = at) }
        override fun finish(outcome: DeliveryOutcome, at: Long) {
            check(!rejectWrites)
            value = value.copy(outcome = outcome,
                lastCheckedAt = if (outcome == DeliveryOutcome.CHECKED) at else value.lastCheckedAt)
        }
    }
    private class Target : DeliveryTarget {
        override var configured = true
        var storedIdentity = true
        var identityReads = 0
        var syncs = 0
        var action: suspend () -> Unit = {}
        override suspend fun hasStoredIdentity(): Boolean { identityReads++; return storedIdentity }
        override suspend fun synchronize() { syncs++; action() }
    }

    @Test fun disabledOrUnreadablePreferencesNeverConstructARepository() = runTest {
        for (snapshot in listOf(DeliverySnapshot(), DeliverySnapshot(available = false))) {
            val store = Store(snapshot)
            var constructed = 0
            val run = DeliveryRun(store, { constructed++; Target() })
            assertEquals(DeliveryRun.Disposition.COMPLETE, run.execute(false, 0))
            assertEquals(0, constructed)
            assertEquals(DeliveryOutcome.NEVER, store.value.outcome)
            assertEquals(0L, store.value.lastCheckedAt)
            assertEquals(0L, store.value.lastAttemptAt)
        }
    }

    @Test fun explicitCheckWorksWhileAutomaticBackgroundIsDisabled() = runTest {
        val store = Store()
        val target = Target()
        assertEquals(DeliveryRun.Disposition.COMPLETE, DeliveryRun(store, { target }, { 1234L }).execute(true, 0))
        assertEquals(1, target.syncs)
        assertFalse(store.value.options.enabled)
        assertEquals(1234L, store.value.lastCheckedAt)
        assertEquals(DeliveryOutcome.CHECKED, store.value.outcome)
    }

    @Test fun missingIdentityNeverStartsSynchronizationOrPretendsSuccess() = runTest {
        val store = Store(DeliverySnapshot(options = DeliveryOptions(enabled = true)))
        val target = Target().apply { storedIdentity = false }
        DeliveryRun(store, { target }).execute(false, 0)
        assertEquals(0, target.syncs)
        assertEquals(DeliveryOutcome.IDENTITY_REQUIRED, store.value.outcome)
        assertEquals(0L, store.value.lastCheckedAt)
    }

    @Test fun successfulCheckIsRecordedOnlyAfterTheServerOperationCompletes() = runTest {
        val store = Store(DeliverySnapshot(lastCheckedAt = 700L))
        val target = Target().apply { action = { assertEquals(700L, store.value.lastCheckedAt) } }
        DeliveryRun(store, { target }, { 900L }).execute(true, 0)
        assertEquals(900L, store.value.lastCheckedAt)
    }

    @Test fun networkFailuresKeepLastSuccessAndBoundAutomaticRetries() = runTest {
        val store = Store(DeliverySnapshot(lastCheckedAt = 777L))
        val target = Target().apply { action = { throw IOException("private peer and message must not be stored") } }
        val run = DeliveryRun(store, { target })
        assertEquals(DeliveryRun.Disposition.RETRY, run.execute(true, 0))
        assertEquals(DeliveryRun.Disposition.RETRY, run.execute(true, 1))
        assertEquals(DeliveryRun.Disposition.COMPLETE, run.execute(true, 2))
        assertEquals(777L, store.value.lastCheckedAt)
        assertEquals(DeliveryOutcome.NETWORK_WAIT, store.value.outcome)
    }

    @Test fun rateLimitRetriesButAuthenticationFailureRequiresAttention() = runTest {
        val store = Store()
        val target = Target().apply { action = { throw RelayException(429, "private error") } }
        val run = DeliveryRun(store, { target })
        assertEquals(DeliveryRun.Disposition.RETRY, run.execute(true, 0))
        assertEquals(DeliveryOutcome.SERVER_BUSY, store.value.outcome)
        target.action = { throw RelayException(401, "private error") }
        assertEquals(DeliveryRun.Disposition.COMPLETE, run.execute(true, 0))
        assertEquals(DeliveryOutcome.NEEDS_ATTENTION, store.value.outcome)
        assertEquals(0L, store.value.lastCheckedAt)
    }

    @Test fun systemCancellationAndParentTimeoutAreNeverSwallowed() = runTest {
        val store = Store()
        val target = Target().apply { action = { throw CancellationException("stop") } }
        val run = DeliveryRun(store, { target })
        try { run.execute(true, 0); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        target.action = { delay(1000L) }
        try { withTimeout(5L) { run.execute(true, 0) }; fail("Parent timeout must propagate") }
        catch (_: CancellationException) { }
        assertEquals(0L, store.value.lastCheckedAt)
        assertEquals(DeliveryOutcome.NEVER, store.value.outcome)
    }

    @Test fun ownTimeoutCancelsTheOperationAndKeepsTheQueueRetryable() = runTest {
        val store = Store()
        var cancelled = false
        val target = Target().apply { action = { try { delay(1000) } finally { cancelled = true } } }
        val run = DeliveryRun(store, { target }, timeoutMillis = 50L)
        assertEquals(DeliveryRun.Disposition.RETRY, run.execute(true, 0))
        assertTrue(cancelled)
        assertEquals(DeliveryOutcome.NETWORK_WAIT, store.value.outcome)
        assertEquals(0L, store.value.lastCheckedAt)
    }

    @Test fun persistenceFailureStopsBeforeAnyNetworkOrIdentityAccess() = runTest {
        val store = Store().apply { rejectWrites = true }
        var constructed = 0
        DeliveryRun(store, { constructed++; Target() }).execute(true, 0)
        assertEquals(0, constructed)
        assertEquals(0L, store.value.lastCheckedAt)
    }

    @Test fun secureStoreAndUnconfiguredBackendNeverPretendToBeOnline() = runTest {
        val store = Store()
        val target = Target().apply { configured = false }
        DeliveryRun(store, { target }).execute(true, 0)
        assertEquals(0, target.identityReads)
        assertEquals(0, target.syncs)
        assertEquals(DeliveryOutcome.NEEDS_ATTENTION, store.value.outcome)
        DeliveryRun(store, { throw java.security.GeneralSecurityException("private key") }).execute(true, 0)
        assertEquals(DeliveryOutcome.SECURE_STORAGE_UNAVAILABLE, store.value.outcome)
        assertEquals(0L, store.value.lastCheckedAt)
    }
}
