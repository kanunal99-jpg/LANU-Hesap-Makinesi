package com.example.core.location

import java.util.UUID

/**
 * Authenticated live-location events are encrypted by the existing LANU
 * PeerMessageCrypto conversation channel, never sent as plaintext map URLs.
 * A new session is unique to one validated recipient conversation.
 *
 * Events are NOT an external tracking link. Stops revoke future capture only:
 * the recipient can retain coordinates already delivered in E2EE history.
 */
object PrivateLiveLocationCodec {
    private const val PREFIX = "lanu-live-v1:"
    private const val MIN_TIME = 946_684_800_000L
    private const val MAX_TIME = 4_102_444_800_000L
    const val FIFTEEN_MINUTES_MS = 15 * 60_000L
    const val ONE_HOUR_MS = 60 * 60_000L
    const val EIGHT_HOURS_MS = 8 * 60 * 60_000L
    const val MAX_POINTS_PER_SESSION = 256
    const val FRESH_UPDATE_MAX_AGE_MS = 2 * 60_000L
    val durations = setOf(FIFTEEN_MINUTES_MS, ONE_HOUR_MS, EIGHT_HOURS_MS)

    enum class Kind { START, POINT, STOP }
    data class Event(
        val kind: Kind,
        val sessionId: String,
        val sequence: Int,
        val startedAtMs: Long,
        val expiresAtMs: Long,
        val point: PrivateLocationCodec.Point? = null,
        val stoppedAtMs: Long? = null
    )

    fun isLiveLocationMessage(text: String): Boolean = text.startsWith(PREFIX)

    fun intervalMs(durationMs: Long): Long = when (durationMs) {
        FIFTEEN_MINUTES_MS -> 30_000L
        ONE_HOUR_MS -> 60_000L
        EIGHT_HOURS_MS -> 180_000L
        else -> throw IllegalArgumentException("Paylaşım süresi geçersiz")
    }

    fun expired(expiresAtMs: Long, nowMs: Long): Boolean = nowMs >= expiresAtMs

    fun fresh(pointAtMs: Long, nowMs: Long): Boolean =
        pointAtMs <= nowMs && nowMs - pointAtMs <= FRESH_UPDATE_MAX_AGE_MS

    private fun validateBase(event: Event) {
        require(runCatching { UUID.fromString(event.sessionId).toString() }.getOrNull() ==
            event.sessionId) { "Oturum kimliği geçersiz" }
        require(event.startedAtMs in MIN_TIME..MAX_TIME)
        require(event.expiresAtMs in MIN_TIME..MAX_TIME)
        require(event.expiresAtMs - event.startedAtMs in durations)
        require(event.sequence in 0..MAX_POINTS_PER_SESSION)
    }

    fun encode(event: Event): String {
        validateBase(event)
        val head = listOf(event.sessionId, event.sequence.toString(),
            event.startedAtMs.toString(), event.expiresAtMs.toString())
        val body = when (event.kind) {
            Kind.START -> {
                require(event.sequence == 0 && event.point == null &&
                    event.stoppedAtMs == null)
                listOf("S") + head
            }
            Kind.POINT -> {
                require(event.sequence > 0 && event.stoppedAtMs == null)
                val point = requireNotNull(event.point)
                require(point.capturedAtMillis in
                    (event.startedAtMs - 60_000L)..event.expiresAtMs)
                val canonical = PrivateLocationCodec.encode(point)
                val roundTrip = requireNotNull(PrivateLocationCodec.decodeOrNull(canonical))
                require(roundTrip == point)
                val parts = canonical.substringAfter("lanu-location-v1:").split(',')
                listOf("P") + head + parts
            }
            Kind.STOP -> {
                require(event.sequence > 0 && event.point == null)
                val stopped = requireNotNull(event.stoppedAtMs)
                require(stopped in event.startedAtMs..event.expiresAtMs)
                listOf("E") + head + stopped.toString()
            }
        }
        return PREFIX + body.joinToString(",")
    }

    /** Strict, canonical, bounded parser for untrusted decrypted peer messages. */
    fun decodeOrNull(text: String): Event? {
        if (!isLiveLocationMessage(text) || text.length > 230 || text != text.trim())
            return null
        val parts = text.removePrefix(PREFIX).split(',')
        if (parts.size !in setOf(5, 6, 10) || parts.any { it.isEmpty() }) return null
        val kind = when (parts[0]) {
            "S" -> Kind.START
            "P" -> Kind.POINT
            "E" -> Kind.STOP
            else -> return null
        }
        if ((kind == Kind.START && parts.size != 5) ||
            (kind == Kind.POINT && parts.size != 10) ||
            (kind == Kind.STOP && parts.size != 6)) return null
        val sequence = parts[2].toIntOrNull() ?: return null
        val started = parts[3].toLongOrNull() ?: return null
        val expires = parts[4].toLongOrNull() ?: return null
        val point = if (kind == Kind.POINT) {
            PrivateLocationCodec.decodeOrNull(
                "lanu-location-v1:" + parts.subList(5, 10).joinToString(",")
            ) ?: return null
        } else null
        val stopped = if (kind == Kind.STOP)
            parts[5].toLongOrNull() ?: return null else null
        val event = Event(kind, parts[1], sequence, started, expires, point, stopped)
        return if (runCatching { encode(event) }.getOrNull() == text) event else null
    }
}
