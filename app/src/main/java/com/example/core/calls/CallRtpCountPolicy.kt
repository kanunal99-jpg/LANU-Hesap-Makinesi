package com.example.core.calls

/**
 * Validates counter values from native WebRTC stats before merging RTP
 * streams. Missing optional fields preserve the prior behavior (zero).
 * Invalid, non-finite, negative, fractional, or overflowing counts mark
 * the entire direction as unavailable (-1); no fake "Connected" proof.
 * No raw RTC stats, addresses, track IDs or private data are persisted.
 */
internal object CallRtpCountPolicy {
    fun add(current: Long, raw: Any?): Long {
        if (current < 0L) return -1L
        if (raw == null) return current
        val number = raw as? Number ?: return -1L
        val value = number.toDouble()
        // Kotlin Long.MAX_VALUE.toDouble() rounds upward. Reject the boundary
        // rather than allowing saturating toLong() to invent a valid packet count.
        if (!value.isFinite() || value < 0.0 ||
            value >= Long.MAX_VALUE.toDouble()) return -1L
        val count = number.toLong()
        if (count < 0L || count.toDouble() != value ||
            current > Long.MAX_VALUE - count) return -1L
        return current + count
    }
}
