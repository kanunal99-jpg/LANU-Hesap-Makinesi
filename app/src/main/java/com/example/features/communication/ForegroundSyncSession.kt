package com.example.features.communication

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Start/stop/request on the owner's UI dispatcher; callbacks emit through Flow only. */
internal class ForegroundSyncSession(
    private val scope: CoroutineScope,
    private val networkChanges: Flow<Unit>,
    private val activeChat: () -> Boolean,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val synchronize: suspend () -> ForegroundSyncPump.Attempt
) {
    private val owners = mutableSetOf<String>()
    private var job: Job? = null
    private var requests: Channel<Unit>? = null
    // A visibility transition is not permission to bypass cadence or 429.
    // Only a monotonic in-memory deadline survives across visibility epochs.
    private var nextAllowedAt: Long? = null

    fun start(owner: String) {
        if (owner.isBlank()) return
        owners.add(owner)
        if (job?.isActive == true) return
        val epoch = Channel<Unit>(Channel.CONFLATED)
        requests = epoch
        job = scope.launch {
            try {
                coroutineScope {
                    launch {
                        networkChanges.catch { error ->
                            if (error is CancellationException || error !is Exception) throw error
                            // Periodic fallback survives unavailable callback services.
                        }.collect { epoch.trySend(Unit) }
                    }
                    ForegroundSyncPump.run(epoch, activeChat) {
                        val wait = nextAllowedAt?.let { (it - clockMillis()).coerceIn(0L, 120000L) } ?: 0L
                        if (wait > 0) delay(wait)
                        nextAllowedAt = clockMillis() + ForegroundSyncPump.MIN_INTERVAL_MILLIS
                        val result = synchronize()
                        val cooldown = if (result.retryAfterMillis > 0)
                            result.retryAfterMillis.coerceIn(5000L, 120000L)
                            else ForegroundSyncPump.MIN_INTERVAL_MILLIS
                        nextAllowedAt = clockMillis() + cooldown
                        result
                    }
                }
            } finally { epoch.cancel() }
        }
    }

    fun stop(owner: String) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            job?.cancel(); job = null
            requests?.cancel(); requests = null
        }
    }

    fun request() {
        if (owners.isNotEmpty()) requests?.trySend(Unit)
    }
}
