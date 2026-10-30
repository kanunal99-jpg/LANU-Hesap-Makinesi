package com.example.core.calls

/**
 * Conservative, audio-first bitrate control for the existing WebRTC sender.
 * Uses only validated remote video loss/RTT; missing stats leave the current
 * encoder unchanged. Never disables video, microphone or a user-selected route.
 * All counters are session-local and contain no private identifiers.
 */
internal class AdaptiveVideoBitratePolicy {
    private enum class Level(val bps: Int) {
        LOW(350_000), MEDIUM(650_000), HIGH(1_100_000)
    }
    private var level = Level.HIGH
    private var lastAttemptAt: Long? = null
    private var poorSamples = 0
    private var goodSamples = 0

    fun recommend(lossFraction: Double?, rttMs: Double?, nowMs: Long): Int? {
        if (nowMs < 0L || (lossFraction == null && rttMs == null)) return null
        if (lossFraction != null &&
            (!lossFraction.isFinite() || lossFraction !in 0.0..1.0)) return null
        if (rttMs != null && (!rttMs.isFinite() || rttMs !in 0.0..60_000.0)) return null
        val last = lastAttemptAt
        if (last != null && (nowMs < last || nowMs - last < 10_000L)) return null

        val poor = (lossFraction != null && lossFraction >= 0.08) ||
            (rttMs != null && rttMs >= 400.0)
        val good = lossFraction != null && rttMs != null &&
            lossFraction <= 0.02 && rttMs <= 180.0
        if (poor) {
            poorSamples++
            goodSamples = 0
        } else if (good) {
            goodSamples++
            poorSamples = 0
        } else {
            poorSamples = 0
            goodSamples = 0
        }
        return when {
            poorSamples >= 2 && level == Level.HIGH -> Level.MEDIUM.bps
            poorSamples >= 2 && level == Level.MEDIUM -> Level.LOW.bps
            goodSamples >= 5 && level == Level.LOW -> Level.MEDIUM.bps
            goodSamples >= 5 && level == Level.MEDIUM -> Level.HIGH.bps
            else -> null
        }
    }

    fun attemptFinished(bitrateBps: Int, nowMs: Long, applied: Boolean) {
        lastAttemptAt = nowMs
        poorSamples = 0
        goodSamples = 0
        if (applied) {
            level = Level.entries.firstOrNull { it.bps == bitrateBps } ?: level
        }
    }
}
