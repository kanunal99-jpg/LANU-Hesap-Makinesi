package com.example.core.calls

import com.example.core.messaging.RelayException
import kotlinx.coroutines.*
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
            try {
                var pass = 0
                while (true) {
                    awaitPermit()
                    currentCoroutineContext().ensureActive()
                    poll()
                    val again = mutex.withLock {
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
                if (error is RelayException && error.status == 429) mutex.withLock {
                    blockedUntil = maxOf(blockedUntil, plus(now(), error.retryAfterMillis.coerceIn(5_000L, 120_000L)))
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
                val until = maxOf(nextStart, blockedUntil)
                if (until > at) until - at else { nextStart = plus(at, 1_000L); 0L }
            }
            if (wait == 0L) return
            delay(wait.coerceIn(1L, 120_000L))
        }
    }
}
