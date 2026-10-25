package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.core.delivery.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackgroundDeliveryAndroidTest {
    private lateinit var context: Context
    private lateinit var store: AndroidDeliveryStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AndroidDeliveryStore.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = AndroidDeliveryStore(context)
    }
    @After fun cleanup() { context.getSharedPreferences(AndroidDeliveryStore.NAME, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun preferencesSurviveRecreationWithoutAnySecretFields() {
        assertEquals(DeliveryOptions(), store.read().options)
        store.saveOptions(DeliveryOptions(true, false))
        store.attempt(123L)
        store.finish(DeliveryOutcome.CHECKED, 456L)
        val restored = AndroidDeliveryStore(context).read()
        assertEquals(DeliveryOptions(true, false), restored.options)
        assertEquals(456L, restored.lastCheckedAt)
        store.finish(DeliveryOutcome.NETWORK_WAIT, 789L)
        assertEquals(456L, store.read().lastCheckedAt)
        assertEquals(setOf("background_enabled", "unmetered_only", "last_attempt_at", "last_checked_at", "last_outcome"),
            context.getSharedPreferences(AndroidDeliveryStore.NAME, Context.MODE_PRIVATE).all.keys)
    }

    @Test fun malformedEnableFlagFailsClosed() {
        context.getSharedPreferences(AndroidDeliveryStore.NAME, Context.MODE_PRIVATE)
            .edit().putString("background_enabled", "true").commit()
        assertFalse(store.read().available)
        assertFalse(store.read().options.enabled)
    }

    @Test fun networkConstraintsRespectOptInMobileData() {
        assertEquals(NetworkType.UNMETERED, DeliveryCoordinator.constraints(DeliveryOptions()).requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, DeliveryCoordinator.constraints(DeliveryOptions(unmeteredOnly = false)).requiredNetworkType)
    }

    private fun worker(manual: Boolean, target: () -> DeliveryTarget): BackgroundDeliveryWorker =
        TestListenableWorkerBuilder<BackgroundDeliveryWorker>(context)
            .setInputData(workDataOf(BackgroundDeliveryWorker.MANUAL to manual))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == BackgroundDeliveryWorker::class.java.name)
                        BackgroundDeliveryWorker(appContext, workerParameters, target) else null
            }).build()

    @Test fun realWorkerAdapterDoesNothingWhenOwnerOptedOut() = runTest {
        val result = worker(false) { error("No target may be created") }.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0L, store.read().lastCheckedAt)
    }

    @Test fun realWorkerRecordsExplicitCheckWithoutLeakingDataIntoWorkOutput() = runTest {
        store.saveOptions(DeliveryOptions(false, false))
        var syncs = 0
        val result = worker(true) { object : DeliveryTarget {
            override val configured = true
            override suspend fun hasStoredIdentity() = true
            override suspend fun synchronize() { syncs++ }
        } }.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, syncs)
        assertEquals(DeliveryOutcome.CHECKED, store.read().outcome)
        assertFalse(store.read().options.enabled)
    }

    @Test fun workManagerReflectionConstructorRemainsAvailable() {
        assertNotNull(BackgroundDeliveryWorker::class.java.getConstructor(Context::class.java, WorkerParameters::class.java))
    }
}
