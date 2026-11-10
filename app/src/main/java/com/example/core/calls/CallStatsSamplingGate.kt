package com.example.core.calls

/**
 * An OEM WebRTC getStats callback may stall for several seconds or never
 * arrive. Polling again every second then queues redundant native requests,
 * increasing camera/Opus work and callback races on a weak handset.
 *
 * Allow only one current async sample; after five seconds a timed-out sample
 * can be replaced. Its late callback is rejected and cannot masquerade as
 * new transport/RTP evidence. No media samples or peer data are stored.
 */
internal class CallStatsSamplingGate {
    companion object { const val MAX_IN_FLIGHT_MS = 5_000L }
    private var active: Long? = null
    private var startedAt: Long = 0L

    @Synchronized fun begin(sequence: Long, nowMs: Long): Boolean {
        if (sequence <= 0L || nowMs < 0L) return false
        if (active != null && nowMs >= startedAt &&
            nowMs - startedAt < MAX_IN_FLIGHT_MS) return false
        active = sequence
        startedAt = nowMs
        return true
    }

    @Synchronized fun finish(sequence: Long): Boolean {
        if (active != sequence || sequence <= 0L) return false
        active = null
        return true
    }

    @Synchronized fun invalidate() { active = null }
}
