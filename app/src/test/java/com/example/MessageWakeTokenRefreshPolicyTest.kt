package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.messaging.MessageWakePreferences
import com.example.core.messaging.MessageWakeTokenRefreshPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The private-message FCM token remains in memory only. Remote signed writes
 * must be ordered even if a WorkManager refresh races the private settings UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MessageWakeTokenRefreshPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        MessageWakePreferences.disableAndQueueRemoteRevoke(context)
        MessageWakePreferences.acknowledgeRemoteRevoke(context)
    }

    @Test fun noConsentNeverFetchesFirebaseTokenOrTouchesRelay() = runBlocking {
        var fetched = 0
        var writes = 0
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { fetched++; "not-allowed" }, { writes++ }))
        assertEquals(0, fetched)
        assertEquals(0, writes)
    }

    @Test fun enrollmentNeedsAnActiveDurableMarkerBeforeAnySignedWrite() = runBlocking {
        val calls = mutableListOf<String?>()
        try {
            MessageWakeTokenRefreshPolicy.completeRegistration(context, "too-early") { calls.add(it) }
            throw AssertionError("Enrollment without explicit consent must fail")
        } catch (_: IllegalStateException) {
            assertTrue(calls.isEmpty())
        }
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { error("Worker must not fetch during enrollment") },
            { error("Worker must not revoke a live enrollment") }))
        MessageWakeTokenRefreshPolicy.completeRegistration(context, "new-token") { calls.add(it) }
        assertEquals(listOf<String?>("new-token"), calls)
        assertTrue(MessageWakePreferences.isEnabled(context))
        assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun cancelledEnrollmentRejectsLateTokenWithoutRelayWrite() = runBlocking {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        var wrote = false
        try {
            MessageWakeTokenRefreshPolicy.completeRegistration(context, "late-token") { wrote = true }
            throw AssertionError("Cancelled enrollment must fail closed")
        } catch (_: IllegalStateException) {
            assertFalse(wrote)
            assertFalse(MessageWakePreferences.isEnabled(context))
            assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
        }
    }

    @Test fun concurrentUiAndWorkerRevocationsSendOnlyOneSignedRevoke() = runBlocking {
        withTimeout(5_000) {
            assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<String?>()
            val uiRevoke = async(start = CoroutineStart.UNDISPATCHED) {
                MessageWakeTokenRefreshPolicy.completeRevocation(context) { token ->
                    writes.add(token)
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val workerRevoke = async(start = CoroutineStart.UNDISPATCHED) {
                MessageWakeTokenRefreshPolicy.reconcile(context,
                    { error("No token must be fetched during revoke") },
                    { writes.add(it) })
            }
            assertFalse(workerRevoke.isCompleted)
            release.complete(Unit)
            assertTrue(uiRevoke.await())
            assertTrue(workerRevoke.await())
            assertEquals(listOf<String?>(null), writes)
            assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
        }
    }

    @Test fun newEnrollmentWaitsForOldWorkerAndOwnsFinalRemoteWrite() = runBlocking {
        withTimeout(5_000) {
            assertTrue(MessageWakePreferences.beginEnrollment(context))
            assertTrue(MessageWakePreferences.finishEnrollment(context))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<String?>()
            val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) {
                MessageWakeTokenRefreshPolicy.reconcile(context, { "old-token" }) { token ->
                    writes.add(token)
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
            assertTrue(MessageWakePreferences.acknowledgeRemoteRevoke(context))
            assertTrue(MessageWakePreferences.beginEnrollment(context))
            val newEnrollment = async(start = CoroutineStart.UNDISPATCHED) {
                MessageWakeTokenRefreshPolicy.completeRegistration(context, "new-token") {
                    writes.add(it)
                }
            }
            assertFalse(newEnrollment.isCompleted)
            release.complete(Unit)
            assertTrue(oldRefresh.await())
            newEnrollment.await()
            assertEquals(listOf<String?>("old-token", "new-token"), writes)
            assertTrue(MessageWakePreferences.isEnabled(context))
        }
    }

    @Test fun networkFailurePreservesDurableRemoteRevokeForRetry() = runBlocking {
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        try {
            MessageWakeTokenRefreshPolicy.completeRevocation(context) {
                throw IllegalStateException("simulated offline")
            }
            throw AssertionError("Signed revoke failure must propagate")
        } catch (_: IllegalStateException) {
            assertFalse(MessageWakePreferences.isEnabled(context))
            assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
        }
        val writes = mutableListOf<String?>()
        assertTrue(MessageWakeTokenRefreshPolicy.completeRevocation(context) { writes.add(it) })
        assertEquals(listOf<String?>(null), writes)
        assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun revokedConsentDuringSignedEnrollmentCannotRemainRegistered() = runBlocking {
        withTimeout(5_000) {
            assertTrue(MessageWakePreferences.beginEnrollment(context))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<String?>()
            val enrollment = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    MessageWakeTokenRefreshPolicy.completeRegistration(context, "inflight-token") { token ->
                        writes.add(token)
                        entered.complete(Unit)
                        release.await()
                    }
                    false
                } catch (_: IllegalStateException) {
                    true
                }
            }
            entered.await()
            // A consent revocation can arrive from a second settings surface
            // while the signed relay write is suspended on the network.
            assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
            release.complete(Unit)
            assertTrue(enrollment.await())
            assertFalse(MessageWakePreferences.isEnabled(context))
            assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
            // The next worker pass must not fetch a new token; it must remove
            // the already-acknowledged late registration instead.
            assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
                { error("Opted-out worker must not fetch an FCM token") },
                { writes.add(it) }))
            assertEquals(listOf<String?>("inflight-token", null), writes)
            assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
        }
    }

}
