package com.example.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Android's process-exit categories, for the PIN-gated diagnostics screen only.
 * Never accesses Android trace streams, user data, exception messages, peer IDs
 * or raw ANR/Java stack information. No analytics or network upload.
 */
object CrashDiagnostics {
    data class RecentExit(val label: String, val timestamp: Long)
    private val retentionMs = TimeUnit.DAYS.toMillis(7)

    /** Reject future timestamps, clock skew and stale system exit entries. */
    fun withinRetention(timestamp: Long, now: Long): Boolean =
        timestamp > 0L && now >= timestamp && now - timestamp <= retentionMs

    /** Fixed allowlist: OS descriptions and process-specific strings are unsafe. */
    fun safeReasonLabel(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Uygulama kodu hatası (Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android yerel kod hatası"
        ApplicationExitInfo.REASON_ANR -> "Uygulama yanıt vermedi (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Düşük bellek nedeniyle kapanma"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Aşırı kaynak kullanımı"
        else -> null
    }

    fun mostRecentAbnormalExit(context: Context, now: Long = System.currentTimeMillis()): RecentExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching null
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .asSequence()
                .filter { withinRetention(it.timestamp, now) }
                .mapNotNull { info ->
                    safeReasonLabel(info.reason)?.let { label ->
                        RecentExit(label, info.timestamp)
                    }
                }
                .maxByOrNull { it.timestamp }
        }.getOrNull()
    }
}
