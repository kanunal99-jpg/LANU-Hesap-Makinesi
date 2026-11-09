package com.example.core.messaging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Shared monotonic, per-identity admission. Never changes the server's 120/min cap. */
internal class RelayRequestBudget(private val clock: () -> Long) {
    private data class Entry(val at: Long, val control: Boolean, val critical: Boolean)
    private class Window { val entries = ArrayDeque<Entry>(); var blockedUntil = 0L }
    private val windows = LinkedHashMap<String, Window>()
    private var lastNow = 0L
    private fun now(): Long = maxOf(lastNow, clock().coerceAtLeast(0L)).also { lastNow = it }
    private fun plus(value: Long, delta: Long) = if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    @Synchronized fun admit(owner: String, action: String): Long {
        require(owner.matches(Regex("[a-f0-9]{64}")))
        val at = now()
        windows.values.forEach { window ->
            while (window.entries.firstOrNull()?.let { at >= it.at && at - it.at >= WINDOW_MS } == true) window.entries.removeFirst()
        }
        windows.entries.removeAll { it.key != owner && it.value.entries.isEmpty() && it.value.blockedUntil <= at }
        if (owner !in windows && windows.size >= 4) return WINDOW_MS
        val window = windows.getOrPut(owner) { Window() }
        if (window.blockedUntil > at) return window.blockedUntil - at
        val control = action in CONTROL_ACTIONS
        val critical = action in CRITICAL_ACTIONS
        val ordinary = window.entries.filter { !it.control }
        val noncritical = window.entries.filter { !it.critical }
        val totalWait = if (window.entries.size >= TOTAL_LIMIT) maxOf(1L, plus(window.entries.first().at, WINDOW_MS) - at) else 0L
        val ordinaryWait = if (!control && ordinary.size >= ORDINARY_LIMIT) maxOf(1L, plus(ordinary.first().at, WINDOW_MS) - at) else 0L
        val pollWait = if (!critical && noncritical.size >= NONCRITICAL_LIMIT) maxOf(1L, plus(noncritical.first().at, WINDOW_MS) - at) else 0L
        val wait = maxOf(totalWait, ordinaryWait, pollWait)
        if (wait > 0) return wait
        window.entries.addLast(Entry(at, control, critical))
        return 0L
    }
    suspend fun awaitPermit(owner: String, action: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = admit(owner, action)
            if (wait == 0L) return
            delay(wait.coerceIn(1L, WINDOW_MS * 2))
        }
    }
    @Synchronized fun throttle(owner: String, retryAfterMillis: Long) {
        val at = now()
        val window = windows[owner] ?: return
        window.blockedUntil = maxOf(window.blockedUntil, plus(at, retryAfterMillis.coerceIn(5000L, 120000L)))
    }
    companion object {
        const val WINDOW_MS = 60000L
        const val TOTAL_LIMIT = 112
        const val ORDINARY_LIMIT = 84
        const val NONCRITICAL_LIMIT = 104
        private val CRITICAL_ACTIONS = setOf("callSend", "callAck")
        private val CONTROL_ACTIONS = setOf("callSend", "callAck", "callSync")
    }
}

internal object WakeReconnectPolicy {
    const val READY_TIMEOUT_MS = 10000L
    const val STABLE_CONNECTION_MS = 20000L
    fun nextFailures(previous: Int, ready: Boolean, durationMs: Long): Int =
        if (ready && durationMs >= STABLE_CONNECTION_MS) 0 else (previous.coerceIn(0, 6) + 1).coerceAtMost(6)
    fun delayMillis(failures: Int, jitter: Int): Long {
        val base = (2000L * (1L shl failures.coerceIn(0, 5))).coerceAtMost(60000L)
        return (base + jitter.coerceIn(0, 1500)).coerceAtMost(60000L)
    }
}
