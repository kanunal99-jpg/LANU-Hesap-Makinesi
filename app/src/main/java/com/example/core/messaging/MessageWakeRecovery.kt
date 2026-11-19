package com.example.core.messaging

import android.content.Context
import androidx.work.*
import com.example.core.network.AndroidNetworkRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import java.util.concurrent.TimeUnit

/** Durable FCM-independent inbox repair under MESSAGE consent, not DeliveryOptions. */
internal object MessageWakeRecovery {
    const val PERIODIC = "lanu-message-inbox-recovery-v1"
    const val NETWORK = "lanu-message-network-recovery-v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: Job? = null
    private var observedContext: Context? = null

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
                    enqueueNetwork(app)
                }
            }
        }
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
