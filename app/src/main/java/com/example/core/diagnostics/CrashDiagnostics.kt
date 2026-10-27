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

    /**
     * Bounded and privacy-safe summary for a repeatedly crashing OEM device.
     * Consumes only Android's reason code and event timestamp, never description,
     * app trace streams, package labels or exception messages.
     *
     * The OS history is a sampled/recent window, not a lifetime crash count.
     */
    fun summarizeReasons(entries: List<Pair<Int, Long>>, now: Long): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        val seen = HashSet<Pair<Int, Long>>()
        entries.take(40).forEach { (reason, timestamp) ->
            if (!withinRetention(timestamp, now)) return@forEach
            val label = safeReasonLabel(reason) ?: return@forEach
            if (!seen.add(reason to timestamp)) return@forEach
            result[label] = (result[label] ?: 0).coerceAtMost(39) + 1
        }
        return result
    }

    /** Bounded OS-only indicator; this is NOT a root-cause diagnosis. */
    enum class RepeatedExitRisk(val label: String) {
        WITHIN_HOUR("Son bir saat içinde en az üç çökme veya ANR kaydı var."),
        WITHIN_DAY("Son 24 saatte en az üç çökme veya ANR kaydı var.")
    }

    /**
     * Ignores memory pressure and user-requested exits when looking for a
     * repeatable app crash/ANR burst. Accepts only numeric system reason codes
     * and times; reads no OS description, ANR traces, accounts or private data.
     */
    fun assessRepeatedCrashRisk(
        entries: List<Pair<Int, Long>>, now: Long
    ): RepeatedExitRisk? {
        val crashing = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR
        )
        // Android may surface multiple diagnostic reason records at one process
        // exit millisecond. Count each exit time only once, conservatively,
        // rather than declaring a three-crash burst from one termination.
        val recent = entries.take(40).distinctBy { it.second }.filter { (reason, timestamp) ->
            reason in crashing && withinRetention(timestamp, now)
        }
        val hourly = recent.count { now - it.second <= TimeUnit.HOURS.toMillis(1) }
        val daily = recent.count { now - it.second <= TimeUnit.DAYS.toMillis(1) }
        return when {
            hourly >= 3 -> RepeatedExitRisk.WITHIN_HOUR
            daily >= 3 -> RepeatedExitRisk.WITHIN_DAY
            else -> null
        }
    }

    fun recentCrashRisk(
        context: Context, now: Long = System.currentTimeMillis()
    ): RepeatedExitRisk? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching null
            assessRepeatedCrashRisk(
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 40)
                    .map { it.reason to it.timestamp }, now
            )
        }.getOrNull()
    }

    fun recentAbnormalCounts(context: Context, now: Long = System.currentTimeMillis()): Map<String, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching emptyMap()
            val events = manager.getHistoricalProcessExitReasons(context.packageName, 0, 40)
                .map { it.reason to it.timestamp }
            summarizeReasons(events, now)
        }.getOrDefault(emptyMap())
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
