package com.example.core.calls

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.work.*
import com.example.LanuApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Only the public alert timestamp enters the job; no call/peer IDs, keys or SDP. */
@Keep
class CallWakeVerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val at = inputData.getLong(AT, 0L)
        if (!CallWakePreferences.isEnabled(applicationContext) ||
            !CallWakeTapPolicy.freshWakeIntent(CallWakeTapPolicy.ACTION, at, System.currentTimeMillis()))
            return Result.success()
        return try {
            withTimeout(12_000L) {
                withContext(Dispatchers.Main.immediate) {
                    (applicationContext as LanuApplication).callModel.verifyIncomingWake(at)
                }
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            // The displayed notification remains actionable; a stale delayed
            // retry must never revive a call after its original deadline.
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.success() // notification tap independently verifies the INVITE
        }
    }

    companion object {
        internal const val AT = "public_alert_at"
        internal const val UNIQUE = "lanu-call-wake-verify-v1"
        fun enqueue(context: Context, at: Long): Boolean {
            if (!CallWakePreferences.isEnabled(context) ||
                !CallWakeTapPolicy.freshWakeIntent(CallWakeTapPolicy.ACTION, at, System.currentTimeMillis())) return false
            return try {
                val builder = OneTimeWorkRequestBuilder<CallWakeVerifyWorker>()
                    .setInputData(workDataOf(AT to at))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(
                    OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
                true
            } catch (_: RuntimeException) { false }
              catch (_: LinkageError) { false }
        }
    }
}
