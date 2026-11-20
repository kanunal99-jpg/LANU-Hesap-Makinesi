package com.example.core.messaging

import android.content.Context
import androidx.work.*
import com.example.core.network.AndroidNetworkRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Durable FCM-independent inbox repair under MESSAGE consent, not DeliveryOptions. */
internal object MessageWakeRecovery {
    const val PERIODIC = "lanu-message-inbox-recovery-v1"
    const val NETWORK = "lanu-message-network-recovery-v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: Job? = null
    private var observedContext: Context? = null
    private val networkScheduleMutex = Mutex()

    @Synchronized fun reconcile(context: Context) {
        val app = context.applicationContext
        synchronized(MessageWakePreferences.requestGate) {
            if (!MessageWakePreferences.isEnabled(app)) return
            try {
                WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<MessageWakeSyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            } catch (_: Exception) { /* Live route recovery and consented foreground remain independent. */ }
            enqueueNetwork(app) // Persist a CONNECTED job even when currently offline.
            if (observer?.isActive == true && observedContext === app) return
            observer?.cancel()
            observedContext = app
            observer = scope.launch {
                AndroidNetworkRecovery.changes(app).catch { /* Durable periodic work remains. */ }.collect {
                    enqueueNetworkChange(app)
                }
            }
        }
    }

    /** A running worker may already have taken its inbox snapshot. Retain one
     * trailing pass, but coalesce route flapping behind any not-yet-started pass.
     * WorkManager is the durable owner; queries never block the UI/request gate. */
    internal suspend fun enqueueNetworkChange(context: Context): Boolean = networkScheduleMutex.withLock {
        if (!MessageWakePreferences.isEnabled(context)) return@withLock false
        try {
            val manager = WorkManager.getInstance(context.applicationContext)
            val states = withContext(Dispatchers.IO) {
                manager.getWorkInfosForUniqueWork(NETWORK).get(5, TimeUnit.SECONDS).map { it.state }
            }
            val append = states.any { it == WorkInfo.State.RUNNING } &&
                states.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
            val operation = synchronized(MessageWakePreferences.requestGate) {
                if (!MessageWakePreferences.isEnabled(context)) return@synchronized null
                manager.enqueueUniqueWork(NETWORK,
                    if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<MessageWakeSyncWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            } ?: return@withLock false
            withContext(Dispatchers.IO) { operation.result.get(5, TimeUnit.SECONDS) }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { enqueueNetwork(context) } // Persisted ordinary route + periodic fallback.
    }

    fun enqueueNetwork(context: Context): Boolean = synchronized(MessageWakePreferences.requestGate) {
        if (!MessageWakePreferences.isEnabled(context)) return@synchronized false
        try {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NETWORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MessageWakeSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            true
        } catch (_: Exception) { false }
    }

    @Synchronized fun cancel(context: Context) {
        observer?.cancel(); observer = null; observedContext = null
        try {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(NETWORK)
        } catch (_: Exception) { /* Persisted opt-out is checked inside sync and transport. */ }
    }
}
