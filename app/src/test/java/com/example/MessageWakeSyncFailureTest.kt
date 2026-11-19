package com.example

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.core.messaging.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MessageWakeSyncFailureTest {
    @Test fun lockedDatabaseRetriesButSecurityFailureHasExplicitNonSecretOutcome() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(MessageWakePreferences.beginEnrollment(context)); assertTrue(MessageWakePreferences.finishEnrollment(context))
        var error: Exception = SQLiteDatabaseLockedException("synthetic private text")
        val target = object : MessageWakeSyncTarget {
            override val configured = true
            override suspend fun hasStoredIdentity() = true
            override suspend fun synchronize() { throw error }
            override suspend fun hasPendingPage() = false
        }
        fun worker() = TestListenableWorkerBuilder<MessageWakeSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, name: String, params: WorkerParameters): ListenableWorker? =
                if (name == MessageWakeSyncWorker::class.java.name) MessageWakeSyncWorker(appContext, params) { target } else null
        }).build()
        try {
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            error = SecurityException("private text must not appear in output")
            assertEquals(ListenableWorker.Result.success(workDataOf("sync_outcome" to "needs_attention", "sync_reason" to "SECURE_STORAGE")), worker().doWork())
        } finally { context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    }
    @Test fun consentRevokedInsideFirstPagePreventsSecondPageAndContinuation() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(MessageWakePreferences.beginEnrollment(context)); assertTrue(MessageWakePreferences.finishEnrollment(context))
        var pages = 0
        var pendingChecks = 0
        val target = object : MessageWakeSyncTarget {
            override val configured = true
            override suspend fun hasStoredIdentity() = true
            override suspend fun synchronize() {
                pages++
                assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
            }
            override suspend fun hasPendingPage(): Boolean { pendingChecks++; return true }
        }
        val worker = TestListenableWorkerBuilder<MessageWakeSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, name: String, params: WorkerParameters): ListenableWorker =
                MessageWakeSyncWorker(appContext, params) { target }
        }).build()
        try {
            assertEquals(ListenableWorker.Result.success(workDataOf("sync_outcome" to "disabled")), worker.doWork())
            assertEquals(1, pages)
            assertEquals(0, pendingChecks)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun cancellationInsidePagePropagatesBeforeAnotherPageOrContinuation() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(MessageWakePreferences.beginEnrollment(context)); assertTrue(MessageWakePreferences.finishEnrollment(context))
        var pages = 0
        val target = object : MessageWakeSyncTarget {
            override val configured = true
            override suspend fun hasStoredIdentity() = true
            override suspend fun synchronize() { pages++; currentCoroutineContext().cancel() }
            override suspend fun hasPendingPage(): Boolean = error("Cancelled page must not schedule more work")
        }
        val worker = TestListenableWorkerBuilder<MessageWakeSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, name: String, params: WorkerParameters): ListenableWorker =
                MessageWakeSyncWorker(appContext, params) { target }
        }).build()
        try {
            val task = async { worker.doWork() }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            assertEquals(1, pages)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun staleHintAndEmptySyncNeverAlertButCommittedNewMessageDoes() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(MessageWakePreferences.beginEnrollment(context)); assertTrue(MessageWakePreferences.finishEnrollment(context))
        var count = 0; var alerts = 0; var syncs = 0
        val target = object : MessageWakeSyncTarget {
            override val configured = true
            override val newIncomingCount get() = count
            override suspend fun publishPendingAlerts(post: (String) -> Boolean): Boolean {
                if (count == 0 || !post("stable-opaque-batch")) return false
                count = 0
                return true
            }
            override suspend fun hasStoredIdentity() = true
            override suspend fun synchronize() { syncs++ }
            override suspend fun hasPendingPage() = false
        }
        fun worker() = TestListenableWorkerBuilder<MessageWakeSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, name: String, params: WorkerParameters): ListenableWorker =
                MessageWakeSyncWorker(appContext, params, publishNewMessages = { alerts++; true }, targetFactory = { target })
        }).build()
        try {
            worker().doWork(); assertEquals(0, alerts)
            count = 1; worker().doWork(); assertEquals(1, alerts)
            count = 0; worker().doWork(); assertEquals(1, alerts)
            assertEquals(3, syncs)
        } finally { MessageWakePreferences.disableAndQueueRemoteRevoke(context); prefs.edit().clear().commit() }
    }

}
