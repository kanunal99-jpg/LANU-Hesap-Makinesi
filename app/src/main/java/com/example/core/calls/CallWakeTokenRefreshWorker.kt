package com.example.core.calls

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

/**
 * Refresh the currently issued FCM token without ever storing the token,
 * identity, conversation or signing material in WorkManager input or output.
 * The caller is already opted in; the worker never creates an E2EE identity.
 */
object CallWakeTokenRefreshPolicy {
    suspend fun reconcile(
        context: Context,
        currentToken: suspend () -> String?,
        register: suspend (String?) -> Unit
    ): Boolean {
        suspend fun finishOptOut(force: Boolean = false): Boolean {
            // A signed revoke may have completed while an earlier positive
            // registration was still in flight. Revoke that late write even
            // when the UI has already cleared the old pending flag.
            val pending = CallWakePreferences.hasPendingRemoteRevoke(context)
            if (!force && !pending) return true
            if (force && !pending) {
                // Persist the *new* compensating revoke before network IO,
                // otherwise a failed attempt could leave an unwanted remote
                // token with no durable retry obligation.
                if (!CallWakePreferences.disableAndQueueRemoteRevoke(context)) return false
            }
            register(null)
            return CallWakePreferences.acknowledgeRemoteRevoke(context)
        }

        if (!CallWakePreferences.isEnabled(context)) return finishOptOut()
        val token = currentToken()
        if (!CallWakePreferences.isEnabled(context)) return finishOptOut()
        if (token.isNullOrBlank()) return false
        register(token)
        // A user can revoke consent while an asynchronous request is in flight.
        if (!CallWakePreferences.isEnabled(context)) return finishOptOut(force = true)
        return true
    }
}

/**
 * Retries explicit opt-in token rotation. A previously scheduled worker can
 * also finish a pending signed opt-out, but a cold launch never schedules an
 * opted-out device: pending revocation is retried inside authenticated
 * CallWakeSettings. Do not imply automatic background retry on reconnect.
 */
@Keep
class CallWakeTokenRefreshWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val done = CallWakeTokenRefreshPolicy.reconcile(
                applicationContext,
                currentToken = {
                    // Blocking Task bridge is isolated to IO and bounded in time.
                    withContext(Dispatchers.IO) {
                        Tasks.await(FirebaseMessaging.getInstance().token, 12, TimeUnit.SECONDS)
                    }
                },
                register = { token ->
                    (applicationContext as LanuApplication).container.communicationRepository
                        .registerCallWakeToken(token)
                }
            )
            if (done) Result.success() else retryBounded()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No exception details, tokens, account IDs or credentials in WorkManager logs.
            retryBounded()
        }
    }

    private fun retryBounded(): Result =
        if (runAttemptCount < 5) Result.retry() else Result.failure()

    companion object {
        const val WORK_NAME = "lanu-call-wake-token-refresh-v1"

        fun enqueue(context: Context, replace: Boolean = true) {
            if (!CallWakePreferences.isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<CallWakeTokenRefreshWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build() // Empty inputData: never persist an FCM token.
            // A new FCM rotation supersedes any queued old work. The worker
            // obtains the latest current token only after it starts running.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME,
                    if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    request)
        }
    }
}
