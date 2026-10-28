package com.example

import com.example.features.communication.CallWakeLocalOptOut
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallWakeLocalOptOutTest {
    @Test fun durableLocalRevokePrecedesAllAndroidOperationsAndNeverBlocksMain() = runBlocking {
        val caller = Thread.currentThread()
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val backgroundThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val committed = CallWakeLocalOptOut.persistAndPrepareRevoke(
            persistDisabledWithPendingRevoke = {
                backgroundThreads.add(Thread.currentThread())
                actions.add("persist-disabled-pending-revoke")
                true
            },
            cancelPostedCall = {
                backgroundThreads.add(Thread.currentThread())
                actions.add("cancel-call-only")
            },
            scheduleRemoteRevoke = {
                backgroundThreads.add(Thread.currentThread())
                actions.add("enqueue-signed-revoke")
            },
            resetFirebaseAutoInit = {
                backgroundThreads.add(Thread.currentThread())
                actions.add("reset-firebase")
            }
        )
        assertTrue(committed)
        assertEquals(listOf("persist-disabled-pending-revoke", "cancel-call-only",
            "enqueue-signed-revoke", "reset-firebase"), actions.toList())
        assertTrue(backgroundThreads.all { it != caller })
    }

    @Test fun failedLocalPersistenceNeverCancelsExistingConsentOrFirebase() = runBlocking {
        for (throwOnCommit in listOf(false, true)) {
            val effects = mutableListOf<String>()
            val committed = CallWakeLocalOptOut.persistAndPrepareRevoke(
                persistDisabledWithPendingRevoke = {
                    effects += "persist"
                    if (throwOnCommit) throw SecurityException("Synthetic unavailable preferences")
                    false
                },
                cancelPostedCall = { effects += "cancel" },
                scheduleRemoteRevoke = { effects += "enqueue" },
                resetFirebaseAutoInit = { effects += "firebase" }
            )
            assertFalse(committed)
            assertEquals(listOf("persist"), effects)
        }
    }

    @Test fun brokenNotificationServiceCannotPreventSignedRetryOrIndependentFirebaseReset() = runBlocking {
        val actions = mutableListOf<String>()
        val committed = CallWakeLocalOptOut.persistAndPrepareRevoke(
            persistDisabledWithPendingRevoke = { actions += "persist"; true },
            cancelPostedCall = {
                actions += "cancel"
                throw SecurityException("Synthetic Android notification binder error")
            },
            scheduleRemoteRevoke = {
                actions += "enqueue"
                throw IllegalStateException("Synthetic WorkManager unavailable")
            },
            resetFirebaseAutoInit = { actions += "firebase" }
        )
        assertTrue(committed)
        assertEquals(listOf("persist", "cancel", "enqueue", "firebase"), actions)
    }

    @Test fun cancellationOfScreenScopeCannotAbandonAlreadyInitiatedLocalCleanup() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CallWakeLocalOptOut.persistAndPrepareRevoke(
                persistDisabledWithPendingRevoke = {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    actions += "persist"
                    true
                },
                cancelPostedCall = { actions += "cancel" },
                scheduleRemoteRevoke = { actions += "enqueue" },
                resetFirebaseAutoInit = { actions += "firebase" }
            )
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel() // User leaves private settings while OEM Binder is busy
        } finally {
            release.countDown()
        }
        job.join()
        assertEquals(listOf("persist", "cancel", "enqueue", "firebase"), actions.toList())
    }
}
