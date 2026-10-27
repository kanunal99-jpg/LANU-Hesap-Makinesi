package com.example.core.messaging

import android.content.Context
import androidx.annotation.Keep
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.LanuApplication
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object MessageWakeTokenRefreshPolicy {
    suspend fun reconcile(
        context: Context,
        currentToken: suspend () -> String?,
        register: suspend (String?) -> Unit
    ): Boolean {
        suspend fun revoke(force: Boolean = false): Boolean {
            val pending = MessageWakePreferences.hasPendingRemoteRevoke(context)
            if (!pending && !force) return true
            if (!pending && force && !MessageWakePreferences.disableAndQueueRemoteRevoke(context)) return false
            register(null)
            return MessageWakePreferences.acknowledgeRemoteRevoke(context)
        }
        if (!MessageWakePreferences.isEnabled(context)) return revoke()
        val token = currentToken()
        if (!MessageWakePreferences.isEnabled(context)) return revoke()
        if (token.isNullOrBlank()) return false
        register(token)
        // An opt-out racing an in-flight request must revoke the late write.
        if (!MessageWakePreferences.isEnabled(context)) return revoke(force = true)
        return true
    }
}

@Keep
class MessageWakeTokenRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val confirmed = MessageWakeTokenRefreshPolicy.reconcile(
            applicationContext,
            currentToken = {
                withContext(Dispatchers.IO) {
                    Tasks.await(FirebaseMessaging.getInstance().token, 12, TimeUnit.SECONDS)
                }
            },
            register = { token ->
                (applicationContext as LanuApplication).container.communicationRepository
                    .registerMessageWakeToken(token)
            }
        )
        if (confirmed) Result.success() else retryBounded()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // No PII, private message, token, ID or error text in work logs.
        retryBounded()
    }

    private fun retryBounded(): Result =
        if (runAttemptCount < 5) Result.retry() else Result.failure()

    companion object {
        const val WORK_NAME = "lanu-message-wake-token-refresh-v1"
        fun enqueue(context: Context, replace: Boolean = true) {
            if (!MessageWakePreferences.isEnabled(context) &&
                !MessageWakePreferences.hasPendingRemoteRevoke(context)) return
            val request = OneTimeWorkRequestBuilder<MessageWakeTokenRefreshWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
