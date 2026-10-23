package com.example.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Android 11+ process-exit diagnostics. Never opens raw stack traces or message
 * contents, and never uploads user data. The user can see the cause category
 * from inside the update screen after an unexpected exit.
 */
object CrashDiagnostics {
    data class RecentExit(val label: String, val timestamp: Long)

    fun mostRecentAbnormalExit(context: Context): RecentExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching null
            val mostRecent = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .asSequence()
                .filter { it.timestamp > 0L && System.currentTimeMillis() - it.timestamp <= TimeUnit.DAYS.toMillis(7) }
                .filter { it.reason in setOf(
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                ) }
                .maxByOrNull { it.timestamp } ?: return@runCatching null
            val label = when (mostRecent.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Uygulama kodu hatası (Java/Kotlin)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android yerel kod hatası"
                ApplicationExitInfo.REASON_ANR -> "Uygulama yanıt vermedi (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Düşük bellek nedeniyle kapanma"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Aşırı kaynak kullanımı"
                else -> return@runCatching null
            }
            RecentExit(label, mostRecent.timestamp)
        }.getOrNull()
    }
}
