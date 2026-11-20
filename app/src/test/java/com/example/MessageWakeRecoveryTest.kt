package com.example

import android.app.Application
import android.content.Context
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.*
import com.example.core.messaging.*
import com.example.core.calls.CallWakeMessagingService
import com.example.core.delivery.AndroidDeliveryStore
import com.example.core.delivery.DeliveryOptions
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class MessageWakeRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: WorkManager
    private val synced = CountDownLatch(1)
    @Before fun setup() {
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
        AndroidDeliveryStore(context).saveOptions(DeliveryOptions(false, false))
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(app: Context, name: String, params: WorkerParameters): ListenableWorker? =
                    if (name == MessageWakeSyncWorker::class.java.name) MessageWakeSyncWorker(app, params,
                        publishNewMessages = { fail("An empty inbox never alerts"); false }, targetFactory = {
                        object : MessageWakeSyncTarget {
                            override val configured = true
                            override suspend fun hasStoredIdentity() = true
                            override suspend fun synchronize() { synced.countDown() }
                            override suspend fun hasPendingPage() = false
                        }
                    }) else null
            }).build())
        manager = WorkManager.getInstance(context)
    }
    @After fun cleanup() {
        MessageWakePreferences.disableAndQueueRemoteRevoke(context)
        MessageWakeRecovery.cancel(context)
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun consent() {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
    }
    @Test fun networkRestoredRunsDurableWorkWithoutFcmOrDeliveryOption() {
        assertFalse(AndroidDeliveryStore(context).read().options.enabled)
        assertFalse(MessageWakeRecovery.enqueueNetwork(context))
        consent()
        val first = manager.getWorkInfosForUniqueWork(MessageWakeRecovery.NETWORK).get().single()
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(1L, synced.count)
        MessageWakeRecovery.reconcile(context) // Repeated startup keeps the same persisted work.
        assertEquals(first.id, manager.getWorkInfosForUniqueWork(MessageWakeRecovery.NETWORK).get().single().id)
        val periodic = manager.getWorkInfosForUniqueWork(MessageWakeRecovery.PERIODIC).get().single()
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertTrue(first.outputData.keyValueMap.isEmpty())
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        assertTrue("Network-constrained sync runs without any FCM callback", synced.await(5, TimeUnit.SECONDS))
    }
    @Test fun reconnectDuringSnapshotRetainsOneTrailingPassAndCoalescesFlapping() = runBlocking<Unit> {
        consent()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        // Replace the test worker factory before scheduling this isolated test.
        MessageWakeRecovery.cancel(context)
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(app: Context, name: String, params: WorkerParameters): ListenableWorker? =
                    if (name == MessageWakeSyncWorker::class.java.name) MessageWakeSyncWorker(app, params,
                        targetFactory = { object : MessageWakeSyncTarget {
                            override val configured = true
                            override suspend fun hasStoredIdentity() = true
                            override suspend fun synchronize() {
                                calls++
                                if (calls == 1) { entered.complete(Unit); release.await() }
                            }
                            override suspend fun hasPendingPage() = false
                        } }) else null
            }).build())
        manager = WorkManager.getInstance(context)
        assertTrue(MessageWakeRecovery.enqueueNetwork(context))
        val first = manager.getWorkInfosForUniqueWork(MessageWakeRecovery.NETWORK).get().single()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(first.id)
        try {
            withTimeout(5000) { entered.await() }
            assertTrue(MessageWakeRecovery.enqueueNetworkChange(context))
            repeat(20) { assertTrue(MessageWakeRecovery.enqueueNetworkChange(context)) }
            val work = manager.getWorkInfosForUniqueWork(MessageWakeRecovery.NETWORK).get()
            assertEquals(2, work.count { !it.state.isFinished })
            assertEquals(WorkInfo.State.RUNNING, work.single { it.id == first.id }.state)
            val trailing = work.single { it.id != first.id }
            assertEquals(WorkInfo.State.BLOCKED, trailing.state)
            assertTrue(trailing.outputData.keyValueMap.isEmpty())
            release.complete(Unit)
            driver.setAllConstraintsMet(trailing.id)
            withTimeout(5000) {
                while (manager.getWorkInfoById(trailing.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(10)
            }
            assertEquals(2, calls)
            assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
            assertFalse(MessageWakeRecovery.enqueueNetworkChange(context))
        } finally { release.complete(Unit) }
    }

    @Test fun optingOutCancelsBothRecoveryPathsAndPreventsRequeue() {
        consent()
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        for (name in listOf(MessageWakeRecovery.NETWORK, MessageWakeRecovery.PERIODIC))
            assertTrue(manager.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished })
        assertFalse(MessageWakeRecovery.enqueueNetwork(context))
        MessageWakeRecovery.reconcile(context)
        assertTrue(manager.getWorkInfosForUniqueWork(MessageWakeRecovery.PERIODIC).get().all { it.state.isFinished })
    }
    @Test fun delayedFcmCannotDisplayNotificationBeforeAuthenticatedSync() {
        consent()
        val service = Robolectric.buildService(CallWakeMessagingService::class.java).create().get()
        val now = System.currentTimeMillis()
        val remote = RemoteMessage.Builder("synthetic").setData(mapOf(
            "type" to MessageWakePolicy.TYPE, "at" to (now - 7_200_000L).toString())).build()
        service.onMessageReceived(remote)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(1L, synced.count) // constraints not satisfied: no sync or speculative alert yet
    }
}
