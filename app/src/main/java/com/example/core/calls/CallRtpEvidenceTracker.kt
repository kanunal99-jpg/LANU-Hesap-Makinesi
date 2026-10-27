package com.example.core.calls

/**
 * Proof of fresh two-way audio RTP within the current connected ICE epoch.
 * Raw SDP, IP, contact, media payload and packet contents are never recorded.
 *
 * A cumulative positive RTP total is NOT ongoing media evidence: a dead/stalled
 * stream can retain old counters indefinitely while ICE says CONNECTED.
 * This tracker expires that evidence; it does not hang up on deliberate
 * silence/Opus DTX. Only the authenticated foreground call owns recovery.
 */
class CallRtpEvidenceTracker {
    companion object { const val MAX_AUDIO_EVIDENCE_AGE_MS = 15_000L }
    private var transportConnected = false
    private var epoch = 0L
    private var receiveBaseline: Long? = null
    private var sendBaseline: Long? = null
    private var lastReceived: Long? = null
    private var lastSent: Long? = null
    private var lastRxProgressAt: Long? = null
    private var lastTxProgressAt: Long? = null
    private var lastAcceptedSnapshotSequence = 0L
    private var lastSnapshotAtMs: Long? = null

    @Synchronized fun updateTransport(connected: Boolean): CallMediaEvidence {
        if (transportConnected != connected) {
            transportConnected = connected
            epoch++
            resetCounters()
        }
        // A transport state alone never certifies working bidirectional audio.
        return CallMediaEvidence(transportConnected)
    }

    @Synchronized fun currentEpoch(): Long = epoch

    @Synchronized fun observe(
        snapshotEpoch: Long, received: Long, sent: Long,
        nowMs: Long = System.nanoTime() / 1_000_000L,
        snapshotSequence: Long? = null
    ): CallMediaEvidence? {
        if (snapshotEpoch != epoch) return null
        // Reject clock-regressing stats, even if their sequence is newer.
        // A delayed/native callback cannot resurrect expired RTP progress.
        if (nowMs < 0L || (lastSnapshotAtMs != null && nowMs < lastSnapshotAtMs!!)) return null
        // Async WebRTC getStats calls may finish out of order inside the SAME
        // ICE epoch. A stale callback must never roll RTP baselines backward.
        if (snapshotSequence != null) {
            if (snapshotSequence <= lastAcceptedSnapshotSequence) return null
            lastAcceptedSnapshotSequence = snapshotSequence
        }
        lastSnapshotAtMs = nowMs
        if (!transportConnected) return CallMediaEvidence()
        if (received < 0L || sent < 0L) return CallMediaEvidence(true)

        if (receiveBaseline == null || sendBaseline == null ||
            (lastReceived != null && received < lastReceived!!) ||
            (lastSent != null && sent < lastSent!!)) {
            receiveBaseline = received
            sendBaseline = sent
            lastReceived = received
            lastSent = sent
            lastRxProgressAt = null
            lastTxProgressAt = null
            return CallMediaEvidence(true)
        }

        if (received > lastReceived!!) lastRxProgressAt = nowMs
        if (sent > lastSent!!) lastTxProgressAt = nowMs
        lastReceived = received
        lastSent = sent

        fun fresh(at: Long?): Boolean =
            at != null && nowMs >= at && nowMs - at <= MAX_AUDIO_EVIDENCE_AGE_MS

        return CallMediaEvidence(
            transportConnected = true,
            receivedAudio = if (fresh(lastRxProgressAt)) (received - receiveBaseline!!).coerceAtLeast(0L) else 0L,
            sentAudio = if (fresh(lastTxProgressAt)) (sent - sendBaseline!!).coerceAtLeast(0L) else 0L
        )
    }

    private fun resetCounters() {
        receiveBaseline = null
        sendBaseline = null
        lastReceived = null
        lastSent = null
        lastRxProgressAt = null
        lastTxProgressAt = null
        lastAcceptedSnapshotSequence = 0L
        lastSnapshotAtMs = null
    }
}
