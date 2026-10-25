package com.example.core.delivery

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.LanuApplication
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException

/** Public name/constructor are persisted by WorkManager and must survive R8/OTA. */
@Keep
class BackgroundDeliveryWorker @JvmOverloads constructor(
    context: Context,
    parameters: WorkerParameters,
    private val targetFactory: () -> DeliveryTarget = {
        val repository = (context.applicationContext as LanuApplication).container.communicationRepository
        object : DeliveryTarget {
            override val configured get() = repository.relayConfigured
            override suspend fun hasStoredIdentity() = repository.hasStoredIdentity()
            override suspend fun synchronize() {
                repository.synchronize(activeConversationId = null, requireExistingIdentity = true)
            }
        }
    }
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val store = AndroidDeliveryStore(applicationContext)
            val snapshot = store.read()
            // Recheck the live preference, even if an older queued request had
            // looser constraints when the owner changed the Wi-Fi preference.
            if (snapshot.available && snapshot.options.unmeteredOnly &&
                (inputData.getBoolean(MANUAL, false) || snapshot.options.enabled)) {
                val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (network.isActiveNetworkMetered) {
                    store.finish(DeliveryOutcome.NETWORK_WAIT, System.currentTimeMillis())
                    return Result.success() // next periodic window; no metered request
                }
            }
            val run = DeliveryRun(store, targetFactory)
            when (run.execute(inputData.getBoolean(MANUAL, false), runAttemptCount)) {
                DeliveryRun.Disposition.COMPLETE -> Result.success()
                DeliveryRun.Disposition.RETRY -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken scheduler/preferences/keystore must not crash the app or
            // leak exception messages into WorkManager's persisted output Data.
            Result.failure()
        }
    }

    companion object { const val MANUAL = "explicit_user_check" }
}
