package com.example.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Android 11+ process-exit diagnostics. Never opens raw stack traces or message
 * contents, and never uploads user data. The user can see the cause category
 * from inside the update screen after an unexpected exit.
 */
object CrashDiagnostics {
    data class RecentExit(val label: String, val timestamp: Long)

    private const val MAX_RECENT_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Classifies platform exit metadata, never reads stack traces, exception
     * details, PID, process name or Android's potentially sensitive trace.
     * Reject future timestamps rather than showing them as new crashes.
     */
    internal fun classify(reason: Int, timestamp: Long, now: Long): RecentExit? {
        if (timestamp <= 0L || timestamp > now || now - timestamp > MAX_RECENT_AGE_MS) return null
        val label = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "Uygulama kodu hatası (Java/Kotlin)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android yerel kod hatası"
            ApplicationExitInfo.REASON_ANR -> "Uygulama yanıt vermedi (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Düşük bellek nedeniyle kapanma"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Aşırı kaynak kullanımı"
            else -> return null // normal user kill, update or unknown reason
        }
        return RecentExit(label, timestamp)
    }

    fun mostRecentAbnormalExit(context: Context): RecentExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching null
            val now = System.currentTimeMillis()
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
                .asSequence()
                .mapNotNull { classify(it.reason, it.timestamp, now) }
                .maxByOrNull { it.timestamp }
        }.getOrNull()
    }
}
