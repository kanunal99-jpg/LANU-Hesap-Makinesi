package com.example

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakePreferences
import com.example.core.calls.CallWakeTokenRefreshPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CallWakeTokenRefreshPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun clean() {
        CallWakePreferences.setEnabled(context, false)
        CallWakePreferences.acknowledgeRemoteRevoke(context)
    }

    @Test fun killedBetweenSignedServerWriteAndLocalConsentCannotLeakRegistration() = runBlocking {
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))

        // Server registration succeeded, but Android killed the process
        // before final local consent. A boolean survives; no token is stored.
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        // A real cold start distinguishes abandoned registration from live UI.
        assertTrue(CallWakePreferences.recoverInterruptedRegistration(context))
        assertFalse(CallWakePreferences.isRegistrationInProgress(context))
        var fetched = 0
        val calls = mutableListOf<String?>()
        assertTrue(CallWakeTokenRefreshPolicy.reconcile(context,
            { fetched++; "never-read-fcm-during-opt-out" },
            { calls.add(it) }))
        assertEquals(0, fetched)
        assertEquals(listOf<String?>(null), calls)
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun callConsentCommitsOnlyWhenRegistrationMarkerIsPresent() {
        assertFalse(CallWakePreferences.completeRemoteRegistration(context))
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.completeRemoteRegistration(context))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.beginRemoteRegistration(context))
    }

    @Test fun unsentCallTokenFailureMayAbortWithoutErasingAnExplicitRevoke() {
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertTrue(CallWakePreferences.abandonUnsentRegistration(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))

        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertTrue(CallWakePreferences.abandonUnsentRegistration(context))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.completeRemoteRegistration(context))
    }

    @Test fun noOptInNeverFetchesTokenOrRegistersDevice() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, false))
        var requested = 0
        var registered = 0
        val done = CallWakeTokenRefreshPolicy.reconcile(context, {
            requested++
            "unused"
        }, { registered++ })
        assertTrue(done)
        assertEquals(0, requested)
        assertEquals(0, registered)
    }

    @Test fun optedInDeviceUsesLatestFirebaseTokenWithoutPersistingIt() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        val calls = mutableListOf<String?>()
        val done = CallWakeTokenRefreshPolicy.reconcile(context,
            { "fresh-fcm-token-from-firebase" }, { token -> calls.add(token) })
        assertTrue(done)
        assertEquals(listOf<String?>("fresh-fcm-token-from-firebase"), calls)
        assertTrue(CallWakePreferences.isEnabled(context))
    }

    @Test fun nullFirebaseTokenDoesNotInventRegistration() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        var registered = false
        val done = CallWakeTokenRefreshPolicy.reconcile(context,
            { null }, { registered = true })
        assertFalse(done)
        assertFalse(registered)
    }

    @Test fun pendingRevocationRunsSignedRemoteOptOutWithoutFetchingToken() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        var requested = 0
        val calls = mutableListOf<String?>()
        assertTrue(CallWakeTokenRefreshPolicy.reconcile(context,
            { requested++; "never fetched" }, { calls.add(it) }))
        assertEquals(0, requested)
        assertEquals(listOf<String?>(null), calls)
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun revocationWhileTokenIsLoadingNeverRegisters() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        val calls = mutableListOf<String?>()
        assertTrue(CallWakeTokenRefreshPolicy.reconcile(context,
            {
                CallWakePreferences.disableAndQueueRemoteRevoke(context)
                "racing-token"
            }, { calls.add(it) }))
        assertEquals(listOf<String?>(null), calls)
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun lateRegistrationIsCompensatedEvenWhenSeparateRevokeAlreadyClearedFlag() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        val calls = mutableListOf<String?>()
        assertTrue(CallWakeTokenRefreshPolicy.reconcile(context,
            { "old-token" },
            { token ->
                calls.add(token)
                if (token != null) {
                    assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
                    // Imagine the settings screen already made and confirmed
                    // its signed revoke while this positive request was in flight.
                    assertTrue(CallWakePreferences.acknowledgeRemoteRevoke(context))
                }
            }))
        assertEquals(listOf<String?>("old-token", null), calls)
        assertFalse(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun failedCompensationAfterUiClearedRevokeIsPersistedForRetry() = runBlocking {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        var attempts = 0
        try {
            CallWakeTokenRefreshPolicy.reconcile(context,
                { "old-device-token" },
                { token ->
                    attempts++
                    if (token != null) {
                        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
                        assertTrue(CallWakePreferences.acknowledgeRemoteRevoke(context))
                    } else throw IllegalStateException("server unreachable")
                })
            throw AssertionError("Expected network failure")
        } catch (_: IllegalStateException) {
            assertEquals(2, attempts)
            assertFalse(CallWakePreferences.isEnabled(context))
            assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        }
    }

    @Test fun failedSignedRevokeKeepsPendingFlag() = runBlocking {
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        try {
            CallWakeTokenRefreshPolicy.reconcile(context,
                { "never-requested" }, { throw IllegalStateException("network") })
            throw AssertionError("Expected network failure")
        } catch (_: IllegalStateException) {
            assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
            assertFalse(CallWakePreferences.isEnabled(context))
        }
    }

    @Test fun liveEnrollmentIsNeverRevokedByAnOldWorker() = runBlocking {
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertTrue(CallWakeTokenRefreshPolicy.reconcile(context,
            { error("Active enrollment must not fetch a token") },
            { error("Active enrollment must not be revoked") }))
        assertTrue(CallWakePreferences.isRegistrationInProgress(context))
        val calls = mutableListOf<String?>()
        CallWakeTokenRefreshPolicy.completeRegistration(context, "new-token") { calls.add(it) }
        assertEquals(listOf("new-token"), calls)
        assertTrue(CallWakePreferences.isEnabled(context))
    }

    @Test fun blankFirebaseTokenNeverReachesSignedRelayOrCompletesConsent() = runBlocking {
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        var serverWrites = 0
        for (invalid in listOf("", " ", "\t\n")) {
            try {
                CallWakeTokenRefreshPolicy.completeRegistration(context, invalid) {
                    serverWrites++
                }
                throw AssertionError("Blank FCM token must be rejected")
            } catch (_: IllegalStateException) {
                assertFalse(CallWakePreferences.isEnabled(context))
                assertTrue(CallWakePreferences.isRegistrationInProgress(context))
            }
        }
        assertEquals(0, serverWrites)
        // If Android kills the process after rejection, the durable marker
        // forces a signed remote revoke rather than silent enrollment.
        assertTrue(CallWakePreferences.recoverInterruptedRegistration(context))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun cancelledEnrollmentRejectsLateTokenBeforeAnyServerWrite() = runBlocking {
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        var registered = false
        try {
            CallWakeTokenRefreshPolicy.completeRegistration(context, "late-token") {
                registered = true
            }
            throw AssertionError("Expected rejected enrollment")
        } catch (_: IllegalStateException) {
            assertFalse(CallWakePreferences.isEnabled(context))
            assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        }
        assertFalse(registered)
    }

    @Test fun simultaneousManualAndWorkerRevokesSendOnlyOneRequest() = runBlocking {
        withTimeout(5_000) {
            assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<String?>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                CallWakeTokenRefreshPolicy.reconcile(context, { error("No token") }) {
                    calls.add(it)
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                CallWakeTokenRefreshPolicy.reconcile(context, { error("No token") }) { calls.add(it) }
            }
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertTrue(first.await())
            assertTrue(second.await())
            assertEquals(listOf<String?>(null), calls)
        }
    }

    @Test fun newEnrollmentWaitsForOldRefreshAndOwnsTheFinalRemoteWrite() = runBlocking {
        withTimeout(5_000) {
            assertTrue(CallWakePreferences.setEnabled(context, true))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<String?>()
            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                CallWakeTokenRefreshPolicy.reconcile(context, { "old-token" }) {
                    calls.add(it)
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
            assertTrue(CallWakePreferences.acknowledgeRemoteRevoke(context))
            assertTrue(CallWakePreferences.beginRemoteRegistration(context))
            val enrollment = async(start = CoroutineStart.UNDISPATCHED) {
                CallWakeTokenRefreshPolicy.completeRegistration(context, "new-token") { calls.add(it) }
            }
            assertFalse(enrollment.isCompleted)
            release.complete(Unit)
            assertTrue(refresh.await())
            enrollment.await()
            assertEquals(listOf("old-token", "new-token"), calls)
            assertTrue(CallWakePreferences.isEnabled(context))
        }
    }

    @Test fun cancelledRevokePreservesDurableRetryAndReleasesRegistrationLock() = runBlocking {
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        try {
            CallWakeTokenRefreshPolicy.reconcile(context, { error("No token") }) {
                throw CancellationException("Simulated worker stop")
            }
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        }
        withTimeout(5_000) {
            var requests = 0
            assertTrue(CallWakeTokenRefreshPolicy.reconcile(context, { error("No token") }) {
                assertEquals(null, it)
                requests++
            })
            assertEquals(1, requests)
            assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        }
    }

    @Test fun coldStartDoesNotRevokeCompletedConsentOrInventCleanupForNewInstall() {
        assertTrue(CallWakePreferences.recoverInterruptedRegistration(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertTrue(CallWakePreferences.completeRemoteRegistration(context))
        assertTrue(CallWakePreferences.recoverInterruptedRegistration(context))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
    }
}
