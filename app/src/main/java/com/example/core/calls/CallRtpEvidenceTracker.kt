package com.example.core.calls

/**
 * Checks real audio RTP progress inside the CURRENT connected transport epoch.
 *
 * WebRTC stats counters are cumulative, including packets from an older ICE
 * transport. Reusing those old counters after an ICE reconnect would falsely
 * mark an empty / silent transport as "Connected" even with no fresh media.
 * Stats callbacks can arrive asynchronously after a disconnect; epoch tokens
 * discard such stale snapshots without logging sensitive details.
 */
class CallRtpEvidenceTracker {
    private var transportConnected = false
    private var epoch = 0L
    private var receiveBaseline: Long? = null
    private var sendBaseline: Long? = null
    private var lastReceived: Long? = null
    private var lastSent: Long? = null

    @Synchronized fun updateTransport(connected: Boolean): CallMediaEvidence {
        if (transportConnected != connected) {
            transportConnected = connected
            epoch++
            resetCounters()
        }
        // Do not expose pre-connection cumulative stats as fresh evidence.
        return CallMediaEvidence(transportConnected)
    }

    @Synchronized fun currentEpoch(): Long = epoch

    @Synchronized fun observe(snapshotEpoch: Long, received: Long, sent: Long): CallMediaEvidence? {
        if (snapshotEpoch != epoch) return null // old async callback after an ICE change
        if (!transportConnected) return CallMediaEvidence()
        if (received < 0 || sent < 0) return CallMediaEvidence(true)

        // First stats snapshot after each real transport transition is a baseline.
        // Reset conservatively when a codec/track restart resets any RTP counter.
        if (receiveBaseline == null || sendBaseline == null ||
            (lastReceived != null && received < lastReceived!!) ||
            (lastSent != null && sent < lastSent!!)) {
            receiveBaseline = received
            sendBaseline = sent
            lastReceived = received
            lastSent = sent
            return CallMediaEvidence(true)
        }

        lastReceived = received
        lastSent = sent
        return CallMediaEvidence(
            transportConnected = true,
            receivedAudio = (received - receiveBaseline!!).coerceAtLeast(0),
            sentAudio = (sent - sendBaseline!!).coerceAtLeast(0)
        )
    }

    private fun resetCounters() {
        receiveBaseline = null
        sendBaseline = null
        lastReceived = null
        lastSent = null
    }
}
