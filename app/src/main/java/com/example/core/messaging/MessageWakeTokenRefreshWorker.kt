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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object MessageWakeTokenRefreshPolicy {
    // UI enrollment/revoke and background token rotation must agree on the
    // order of signed writes. In particular, an old worker revoke cannot
    // arrive AFTER a new UI enrollment has been acknowledged by the relay.
    private val registrationMutex = Mutex()

    /** Only the already-consented private UI can commit an explicit enrollment. */
    suspend fun completeRegistration(
        context: Context,
        token: String,
        register: suspend (String?) -> Unit
    ) = registrationMutex.withLock {
        check(token.isNotBlank() && MessageWakePreferences.isEnrollmentInProgress(context)) {
            "Message wake enrollment was cancelled or has no token"
        }
        register(token)
        check(MessageWakePreferences.finishEnrollment(context)) {
            "Message wake consent changed while registering"
        }
    }

    /** UI and worker share the same lock and cannot issue duplicate revokes. */
    suspend fun completeRevocation(
        context: Context,
        register: suspend (String?) -> Unit
    ): Boolean = registrationMutex.withLock {
        // An explicit active enrollment is not a pending opt-out.
        if (MessageWakePreferences.isEnrollmentInProgress(context)) return@withLock false
        if (!MessageWakePreferences.hasPendingRemoteRevoke(context)) return@withLock true
        register(null)
        MessageWakePreferences.acknowledgeRemoteRevoke(context)
    }

    suspend fun reconcile(
        context: Context,
        currentToken: suspend () -> String?,
        register: suspend (String?) -> Unit
    ): Boolean = registrationMutex.withLock {
        reconcileLocked(context, currentToken, register)
    }

    private suspend fun reconcileLocked(
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
        // A newly opened private UI owns its in-flight signed registration.
        // Do not race a worker-side revoke against that explicit enrollment.
        // After process death, Application converts this marker to revoke-only.
        if (MessageWakePreferences.isEnrollmentInProgress(context)) return true
        if (!MessageWakePreferences.isEnabled(context)) return revoke()
        val token = currentToken()
        if (MessageWakePreferences.isEnrollmentInProgress(context)) return true
        if (!MessageWakePreferences.isEnabled(context)) return revoke()
        if (token.isNullOrBlank()) return false
        register(token)
        // A NEW enrollment may have begun while an old token refresh was in
        // flight. Its own signed registration owns the final state. Never
        // wipe it with a stale worker's compensating revocation.
        if (MessageWakePreferences.isEnrollmentInProgress(context)) return true
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
