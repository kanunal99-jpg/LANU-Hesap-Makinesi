package com.example.core.calls

/** Session-local RTCP freshness only; never stores addresses, media or peer identity. */
internal class RemoteCallStatsGate {
    private var epoch: Long? = null
    private var sequence = 0L
    private val timestamps = mutableMapOf<String, Double>()

    fun begin(currentEpoch: Long, snapshotEpoch: Long, snapshotSequence: Long,
              requestedAtMs: Long, nowMs: Long): Boolean {
        if (currentEpoch != snapshotEpoch || requestedAtMs < 0L || nowMs < requestedAtMs ||
            nowMs - requestedAtMs > 5000L || snapshotSequence <= 0L) return false
        if (epoch != currentEpoch) {
            epoch = currentEpoch; sequence = 0L; timestamps.clear()
        }
        if (snapshotSequence <= sequence) return false
        sequence = snapshotSequence
        return true
    }

    // A remote-inbound-rtp timestamp is the RTCP report receipt time, not the
    // polling time. Reading one report five times is not five good samples.
    fun fresh(kind: String, timestampUs: Double): Boolean {
        if (kind !in setOf("audio", "video") || !timestampUs.isFinite() || timestampUs <= 0) return false
        val previous = timestamps[kind]
        if (previous != null && timestampUs <= previous) return false
        timestamps[kind] = timestampUs
        return true
    }
}
