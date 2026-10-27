package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakePreferences
import com.example.core.calls.CallWakeTokenRefreshPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CallWakeTokenRefreshPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun clean() {
        CallWakePreferences.setEnabled(context, false)
        CallWakePreferences.acknowledgeRemoteRevoke(context)
    }

    @Test fun processDeathAfterRemoteRegistrationCannotLoseDurableRevoke() = runBlocking {
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))

        // Simulate a successful signed server write followed by process death
        // before the SharedPreferences opt-in commit: no token is persisted.
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))

        var tokenFetches = 0
        val signedRequests = mutableListOf<String?>()
        assertTrue(CallWakeTokenRefreshPolicy.reconcile(context,
            { tokenFetches++; "never-fetch-during-crash-recovery" },
            { signedRequests.add(it) }))
        assertEquals(0, tokenFetches)
        assertEquals(listOf<String?>(null), signedRequests)
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun registrationFinishesOnlyViaAtomicLocalCommit() {
        assertFalse("Missing pre-network marker must not enable FCM",
            CallWakePreferences.completeRemoteRegistration(context))
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.completeRemoteRegistration(context))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse("An enabled preference must not begin another registration",
            CallWakePreferences.beginRemoteRegistration(context))
    }

    @Test fun noTokenReturnedCanAbortOnlyUnsentRegistrationWithoutServerRevoke() {
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        assertTrue(CallWakePreferences.abandonUnsentRegistration(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        // If opt-out happened instead, clearing an unsent marker does not
        // erase the already durable remote-revoke obligation.
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
}
