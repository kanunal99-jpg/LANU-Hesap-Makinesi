package com.example.core.calls

/**
 * Constrains video encoder resources while preserving an active audio track.
 * The existing remote-loss/RTT hysteresis decides when bitrate changes.
 * The returned values are upper bounds, not measured video quality claims.
 */
internal object AdaptiveVideoEncodingPolicy {
    data class Profile(
        val maxBitrateBps: Int,
        val maxFramerate: Int,
        val scaleResolutionDownBy: Double
    )

    fun forBitrate(targetBps: Int): Profile = when {
        targetBps <= 350_000 -> Profile(350_000, 15, 2.0)
        targetBps <= 650_000 -> Profile(650_000, 20, 1.5)
        else -> Profile(1_100_000, 24, 1.0)
    }
}
