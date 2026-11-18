package com.example

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.core.messaging.*
import kotlinx.coroutines.runBlocking
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
}
