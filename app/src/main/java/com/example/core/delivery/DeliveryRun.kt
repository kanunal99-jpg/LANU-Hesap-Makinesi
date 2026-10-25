package com.example.core.delivery

import com.example.core.messaging.RelayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.GeneralSecurityException

data class DeliveryOptions(val enabled: Boolean = false, val unmeteredOnly: Boolean = true)

enum class DeliveryOutcome {
    NEVER, CHECKED, NETWORK_WAIT, SERVER_BUSY, IDENTITY_REQUIRED,
    SECURE_STORAGE_UNAVAILABLE, NEEDS_ATTENTION
}

data class DeliverySnapshot(
    val options: DeliveryOptions = DeliveryOptions(),
    val available: Boolean = true,
    val lastAttemptAt: Long = 0,
    val lastCheckedAt: Long = 0,
    val outcome: DeliveryOutcome = DeliveryOutcome.NEVER
)

/** Contains only scheduling preferences, timestamps and a fixed outcome enum. */
interface DeliveryStore {
    fun read(): DeliverySnapshot
    fun saveOptions(options: DeliveryOptions)
    fun attempt(at: Long)
    fun finish(outcome: DeliveryOutcome, at: Long)
}

interface DeliveryTarget {
    val configured: Boolean
    suspend fun hasStoredIdentity(): Boolean
    /** Must not pass an active conversation: a background run never sends read receipts. */
    suspend fun synchronize()
}

/** Bounded execution, shared by the real Android worker and deterministic tests. */
class DeliveryRun(
    private val store: DeliveryStore,
    private val target: () -> DeliveryTarget,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 90_000L
) {
    enum class Disposition { COMPLETE, RETRY }

    suspend fun execute(manual: Boolean, attempt: Int): Disposition {
        try {
            val current = store.read()
            // No keystore, repository, or network access when disabled/corrupt.
            if (!current.available || (!manual && !current.options.enabled)) return Disposition.COMPLETE
            store.attempt(clock())
            val completed = withTimeoutOrNull(timeoutMillis) {
                val service = target()
                if (!service.configured) {
                    store.finish(DeliveryOutcome.NEEDS_ATTENTION, clock())
                } else if (!service.hasStoredIdentity()) {
                    store.finish(DeliveryOutcome.IDENTITY_REQUIRED, clock())
                } else {
                    service.synchronize()
                    store.finish(DeliveryOutcome.CHECKED, clock())
                }
                true
            }
            if (completed == null) return transient(DeliveryOutcome.NETWORK_WAIT, attempt)
            return Disposition.COMPLETE
        } catch (e: CancellationException) {
            // Android stopping a job must cancel OkHttp too, never record success.
            throw e
        } catch (e: RelayException) {
            return when (e.status) {
                429 -> transient(DeliveryOutcome.SERVER_BUSY, attempt)
                0, 408, in 500..599 -> transient(DeliveryOutcome.NETWORK_WAIT, attempt)
                else -> finished(DeliveryOutcome.NEEDS_ATTENTION)
            }
        } catch (_: IOException) {
            return transient(DeliveryOutcome.NETWORK_WAIT, attempt)
        } catch (_: GeneralSecurityException) {
            return finished(DeliveryOutcome.SECURE_STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            // Exception messages may contain identifiers. Store no raw exception.
            return finished(DeliveryOutcome.NEEDS_ATTENTION)
        }
    }

    private fun transient(outcome: DeliveryOutcome, attempt: Int): Disposition {
        finished(outcome)
        // Two retries, then the next periodic window or an explicit user action.
        return if (attempt in 0..1) Disposition.RETRY else Disposition.COMPLETE
    }

    private fun finished(outcome: DeliveryOutcome): Disposition {
        try { store.finish(outcome, clock()) } catch (_: Exception) { /* No plaintext fallback. */ }
        return Disposition.COMPLETE
    }
}
