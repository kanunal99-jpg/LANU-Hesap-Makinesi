package com.example

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Runs on BOTH debug and R8 release. No live account or network fixture is used. */
@RunWith(AndroidJUnit4::class)
class DeliveryDeviceAcceptanceTest {
    @Test fun realAndroidSchedulerStartsKeptWorkerAndRespectsOptOut() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("lanu_delivery", Context.MODE_PRIVATE)
        // Instrumentation is restricted to an isolated emulator/test install.
        // Never touch Room, identity, PIN or any real user data in this test.
        check(preferences.edit().clear().putBoolean("background_enabled", false).commit())
        val workerClass = Class.forName("com.example.core.delivery.BackgroundDeliveryWorker")
            .asSubclass(ListenableWorker::class.java)
        val request = OneTimeWorkRequest.Builder(workerClass).addTag("lanu-ci-delivery-probe").build()
        val manager = WorkManager.getInstance(context)
        try {
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            val result = withTimeout(30_000L) {
                manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }!!
            }
            assertEquals(WorkInfo.State.SUCCEEDED, result.state)
            assertTrue(result.outputData.keyValueMap.isEmpty())
            assertEquals(0L, preferences.getLong("last_attempt_at", 0L))
            assertEquals(0L, preferences.getLong("last_checked_at", 0L))
            assertFalse(preferences.contains("last_outcome"))
        } finally {
            manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
            preferences.edit().clear().commit()
        }
    }
}
