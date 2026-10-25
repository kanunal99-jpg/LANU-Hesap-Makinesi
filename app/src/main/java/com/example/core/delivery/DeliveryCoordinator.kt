package com.example.core.delivery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DeliveryCoordinator(context: Context) {
    private val appContext = context.applicationContext
    val store = AndroidDeliveryStore(appContext)
    private fun manager() = WorkManager.getInstance(appContext)

    suspend fun setOptions(options: DeliveryOptions) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            // Persist disable BEFORE cancel: an already starting worker fails closed.
            store.saveOptions(options)
            scheduleCurrent(replace = true)
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val snapshot = store.read()
            // Do not even initialize WorkManager on an ordinary opt-out launch.
            if (snapshot.available && snapshot.options.enabled) scheduleCurrent(replace = false)
        }
    }

    private fun scheduleCurrent(replace: Boolean) {
        val snapshot = store.read()
        check(snapshot.available) { "Teslimat tercihi okunamadı." }
        val work = manager()
        if (!snapshot.options.enabled) {
            work.cancelUniqueWork(PERIODIC).result.get(10, TimeUnit.SECONDS)
            work.cancelUniqueWork(MANUAL).result.get(10, TimeUnit.SECONDS)
            return
        }
        // UPDATE would let a running metered job outlive a stricter Wi-Fi choice.
        // Cancel/re-enqueue only on an explicit option change; normal launches KEEP.
        if (replace) work.cancelUniqueWork(MANUAL).result.get(10, TimeUnit.SECONDS)
        val request = periodicRequest(snapshot.options)
        work.enqueueUniquePeriodicWork(PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
            .result.get(10, TimeUnit.SECONDS)
    }

    suspend fun checkNow() = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val snapshot = store.read()
            check(snapshot.available) { "Teslimat tercihi okunamadı." }
            val request = OneTimeWorkRequestBuilder<BackgroundDeliveryWorker>()
                .setInputData(workDataOf(BackgroundDeliveryWorker.MANUAL to true))
                .setConstraints(constraints(snapshot.options))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .addTag(TAG).build()
            manager().enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
                .result.get(10, TimeUnit.SECONDS)
        }
    }

    fun workStates(): Flow<List<WorkInfo>> = flow {
        emitAll(manager().getWorkInfosByTagFlow(TAG))
    }.flowOn(Dispatchers.IO).catch { emit(emptyList()) }

    companion object {
        const val PERIODIC = "lanu-private-delivery-periodic-v1"
        const val MANUAL = "lanu-private-delivery-manual-v1"
        const val TAG = "lanu-private-delivery-v1"
        private val scheduling = Mutex()

        fun constraints(options: DeliveryOptions) = Constraints.Builder()
            .setRequiredNetworkType(if (options.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()

        fun periodicRequest(options: DeliveryOptions) = PeriodicWorkRequestBuilder<BackgroundDeliveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(options))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .addTag(TAG).build()
    }
}
