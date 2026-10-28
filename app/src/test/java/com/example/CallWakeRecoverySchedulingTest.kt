package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.core.calls.CallWakePreferences
import com.example.core.calls.CallWakeTokenRefreshWorker
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CallWakeRecoverySchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: WorkManager

    @Before fun setup() {
        context.getSharedPreferences("lanu_call_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
    }

    @After fun cleanup() {
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.getSharedPreferences("lanu_call_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun scheduled() = manager.getWorkInfosForUniqueWork(CallWakeTokenRefreshWorker.WORK_NAME)
        .get(5, TimeUnit.SECONDS)

    @Test fun offlineOptOutSchedulesNetworkConstrainedWorkAndRelaunchKeepsIt() {
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        CallWakeTokenRefreshWorker.enqueue(context, replace = false)
        val first = scheduled().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertTrue(first.outputData.keyValueMap.isEmpty())
        CallWakeTokenRefreshWorker.enqueue(context, replace = false)
        assertEquals(first.id, scheduled().single().id)
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun activeEnrollmentSchedulesNothingButInterruptedEnrollmentRecovers() {
        assertTrue(CallWakePreferences.beginRemoteRegistration(context))
        CallWakeTokenRefreshWorker.enqueue(context)
        assertTrue(scheduled().isEmpty())
        assertTrue(CallWakePreferences.recoverInterruptedRegistration(context))
        CallWakeTokenRefreshWorker.enqueue(context, replace = false)
        assertEquals(WorkInfo.State.ENQUEUED, scheduled().single().state)
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun freshInstallOrFullyRevokedConsentNeverSchedulesRegistration() {
        CallWakeTokenRefreshWorker.enqueue(context)
        assertTrue(scheduled().isEmpty())
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertTrue(CallWakePreferences.acknowledgeRemoteRevoke(context))
        CallWakeTokenRefreshWorker.enqueue(context)
        assertTrue(scheduled().isEmpty())
    }
}
