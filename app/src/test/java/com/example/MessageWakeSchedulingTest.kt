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
import com.example.core.messaging.*
import com.example.core.delivery.DeliveryCoordinator
import com.example.core.delivery.DeliveryOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class MessageWakeSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: WorkManager
    @Before fun setup() {
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
    }
    @After fun cleanup() {
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun newOutboxMessageKeepsExistingDrainAndQueuesFollowUp() = runBlocking<Unit> {
        val coordinator = DeliveryCoordinator(context)
        coordinator.setOptions(DeliveryOptions(true, false))
        try {
            coordinator.enqueuePendingOutbox()
            val first = manager.getWorkInfosForUniqueWork(DeliveryCoordinator.OUTBOX).get().single()
            coordinator.enqueuePendingOutbox()
            val work = manager.getWorkInfosForUniqueWork(DeliveryCoordinator.OUTBOX).get()
            assertEquals(2, work.size)
            assertEquals(WorkInfo.State.ENQUEUED, work.single { it.id == first.id }.state)
            assertEquals(WorkInfo.State.BLOCKED, work.single { it.id != first.id }.state)
            assertTrue(work.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
            assertTrue(work.all { it.outputData.keyValueMap.isEmpty() })
        } finally { coordinator.setOptions(DeliveryOptions(false, false)) }
        assertTrue(manager.getWorkInfosForUniqueWork(DeliveryCoordinator.OUTBOX).get().all { it.state.isFinished })
        coordinator.enqueuePendingOutbox()
        assertTrue(manager.getWorkInfosForUniqueWork(DeliveryCoordinator.OUTBOX).get().all { it.state.isFinished })
    }
    private fun consent() {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
    }
    @Test fun laterHintQueuesFollowUpWithoutCancellingExistingSync() {
        consent()
        assertTrue(MessageWakeSyncWorker.enqueue(context))
        val first = manager.getWorkInfosForUniqueWork(MessageWakeSyncWorker.UNIQUE).get().single()
        assertTrue(MessageWakeSyncWorker.enqueue(context))
        val work = manager.getWorkInfosForUniqueWork(MessageWakeSyncWorker.UNIQUE).get()
        assertEquals(2, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single { it.id == first.id }.state)
        assertEquals(WorkInfo.State.BLOCKED, work.single { it.id != first.id }.state)
        assertTrue(work.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
        MessageWakeSyncWorker.cancel(context)
        assertTrue(manager.getWorkInfosForUniqueWork(MessageWakeSyncWorker.UNIQUE).get()
            .all { it.state == WorkInfo.State.CANCELLED })
    }
    @Test fun noConsentSchedulesNothingAndPeriodicRepairStopsOnOptOut() {
        assertFalse(MessageWakeSyncWorker.enqueue(context))
        MessageWakeTokenRefreshWorker.enqueue(context)
        assertTrue(manager.getWorkInfosForUniqueWork(MessageWakeTokenRefreshWorker.WORK_NAME).get().isEmpty())
        consent()
        MessageWakeTokenRefreshWorker.maintainTokenRecovery(context)
        val first = manager.getWorkInfosForUniqueWork(MessageWakeTokenRefreshWorker.PERIODIC_WORK_NAME).get().single()
        MessageWakeTokenRefreshWorker.maintainTokenRecovery(context)
        assertEquals(first.id, manager.getWorkInfosForUniqueWork(MessageWakeTokenRefreshWorker.PERIODIC_WORK_NAME).get().single().id)
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        assertTrue(manager.getWorkInfosForUniqueWork(MessageWakeTokenRefreshWorker.PERIODIC_WORK_NAME).get().all { it.state.isFinished })
    }
}
