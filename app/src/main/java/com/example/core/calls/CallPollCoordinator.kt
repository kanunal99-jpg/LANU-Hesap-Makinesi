package com.example.core.calls

import com.example.core.messaging.RelayException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared signed-call polling. No identifiers, persistence, detached jobs or extra retry loop. */
internal class CallPollCoordinator(private val clock: () -> Long, private val poll: suspend () -> Unit) {
    private sealed class Outcome {
        object Complete : Outcome()
        object OwnerCancelled : Outcome()
        class Failed(val error: Exception) : Outcome()
    }
    private class Flight { val done = CompletableDeferred<Outcome>(); var followUp = false }
    private val mutex = Mutex()
    private var flight: Flight? = null
    private var lastNow = 0L
    private var nextStart = 0L
    private var blockedUntil = 0L
    private var transientBackoffUntil = 0L
    private var permanentBackoffUntil = 0L
    private var transientFailures = 0
    // A failed request on an OLD network must not delay a newly validated path.
    private var networkEpoch = 0L
    private val permitChanges = Channel<Unit>(Channel.CONFLATED)

    /** Trigger ONLY for a system-confirmed validated network route change.
     * Neither 429 Retry-After nor authentication cooldown can be bypassed. */
    suspend fun validatedNetworkHint() {
        mutex.withLock {
            networkEpoch++
            transientFailures = 0
            transientBackoffUntil = 0L
        }
        permitChanges.trySend(Unit)
    }

    private fun now() = maxOf(lastNow, clock().coerceAtLeast(0L)).also { lastNow = it }
    private fun plus(at: Long, delta: Long) = if (at > Long.MAX_VALUE - delta) Long.MAX_VALUE else at + delta

    suspend fun run(requestFollowUp: Boolean = false) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (ticket, owner) = mutex.withLock {
                val current = flight
                if (current != null) {
                    current.followUp = current.followUp || requestFollowUp
                    current to false
                } else Flight().also { flight = it } to true
            }
            if (!owner) {
                when (val result = ticket.done.await()) {
                    Outcome.Complete -> return
                    Outcome.OwnerCancelled -> continue // Joiner keeps its own cancellation/lifecycle.
                    is Outcome.Failed -> throw result.error
                }
            }
            var result: Outcome = Outcome.OwnerCancelled
            var attemptNetworkEpoch = -1L
            try {
                var pass = 0
                while (true) {
                    awaitPermit()
                    currentCoroutineContext().ensureActive()
                    attemptNetworkEpoch = mutex.withLock { networkEpoch }
                    poll()
                    val again = mutex.withLock {
                        // Only a truly successful authenticated poll resets
                        // network error budgets, not a websocket/wake hint.
                        transientFailures = 0
                        transientBackoffUntil = 0L
                        permanentBackoffUntil = 0L
                        if (pass == 0 && ticket.followUp) {
                            ticket.followUp = false
                            true
                        } else {
                            // Close the flight atomically with the final decision. A new
                            // hint cannot join a completed snapshot and be silently lost.
                            result = Outcome.Complete
                            if (flight === ticket) flight = null
                            ticket.done.complete(result)
                            false
                        }
                    }
                    if (!again) return
                    pass++ // At most one coalesced follow-up; independent timer remains.
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutex.withLock {
                    when {
                        error is RelayException && error.status == 429 -> {
                            // Server-declared quota wins over every reconnect
                            // hint; do not spin additional in-flight polls.
                            blockedUntil = maxOf(blockedUntil,
                                plus(now(), error.retryAfterMillis.coerceIn(5_000L, 120_000L)))
                            transientFailures = 0
                            transientBackoffUntil = 0L
                        }
                        CallPollBackoffPolicy.isPermanentCallControlError(error) -> {
                            permanentBackoffUntil = maxOf(permanentBackoffUntil,
                                plus(now(), CallPollBackoffPolicy.PERMANENT_ERROR_COOLDOWN_MS))
                            transientFailures = 0
                            transientBackoffUntil = 0L
                        }
                        attemptNetworkEpoch == networkEpoch -> {
                            val pause = CallPollBackoffPolicy.transientDelayMs(
                                error, transientFailures + 1)
                            if (pause != null) {
                                transientFailures = (transientFailures + 1)
                                    .coerceAtMost(CallPollBackoffPolicy.MAX_TRANSIENT_FAILURES)
                                transientBackoffUntil = maxOf(transientBackoffUntil,
                                    plus(now(), pause))
                            }
                        }
                    }
                }
                result = Outcome.Failed(error)
                throw error
            } finally {
                withContext(NonCancellable) { mutex.withLock {
                    if (flight === ticket) flight = null
                    ticket.done.complete(result)
                } }
            }
        }
    }
    private suspend fun awaitPermit() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = mutex.withLock {
                val at = now()
                val until = maxOf(nextStart, blockedUntil,
                    transientBackoffUntil, permanentBackoffUntil)
                if (until > at) until - at else { nextStart = plus(at, 1_000L); 0L }
            }
            if (wait == 0L) return
            // A validated network handover can lift TRANSIENT cooldown early
            // without ever bypassing a server Retry-After or authentication
            // hold. Channel wakeups are conflated and cancel with the owner.
            withTimeoutOrNull(wait.coerceIn(1L, 120_000L)) {
                permitChanges.receive()
            }
        }
    }
}
