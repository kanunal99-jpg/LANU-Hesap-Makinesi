package com.example.core.notifications

/**
 * A push wake is a short-lived hint, not a durable call or message record.
 * Count Android notification lifetime from the ORIGINAL sender timestamp,
 * rather than granting a fresh full TTL to a delayed FCM delivery.
 * Only bounded positive epoch-millisecond inputs are accepted; no ID,
 * contact, token, or encrypted content is needed.
 */
internal object WakeNotificationLifetimePolicy {
    fun remainingMs(
        createdAt: Long,
        now: Long,
        pastWindowMs: Long,
        futureGraceMs: Long,
        maxVisibleMs: Long
    ): Long? {
        if (createdAt <= 0L || now <= 0L || pastWindowMs <= 0L ||
            futureGraceMs < 0L || maxVisibleMs <= 0L ||
            pastWindowMs > Long.MAX_VALUE - futureGraceMs) return null

        val remaining = if (createdAt <= now) {
            val age = now - createdAt // positive operands; cannot overflow
            if (age >= pastWindowMs) return null
            pastWindowMs - age
        } else {
            val ahead = createdAt - now
            if (ahead > futureGraceMs) return null
            pastWindowMs + ahead
        }
        // Android treats timeoutAfter(0) as an absent expiration: never
        // allow a stale alert to accidentally become indefinite.
        return remaining.coerceAtMost(maxVisibleMs).takeIf { it > 0L }
    }
}
