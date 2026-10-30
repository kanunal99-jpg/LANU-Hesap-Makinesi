package com.example.core.calls

/**
 * Audio-first congestion hint. Adapt the VIDEO encoder when the peer reports
 * trouble receiving either audio or video, without changing microphone state
 * or inventing latency from local packet counts.
 *
 * Missing, NaN and out-of-range remote stats are ignored independently. This
 * class does not contain call identifiers, addresses or private media samples.
 */
internal object VoiceFirstNetworkPolicy {
    data class Signal(val loss: Double?, val rttMs: Double?) {
        val available: Boolean get() = loss != null || rttMs != null
    }

    fun select(
        remoteVideoLoss: Double?, remoteVideoRttMs: Double?,
        remoteAudioLoss: Double?, remoteAudioRttMs: Double?
    ): Signal {
        fun validLoss(v: Double?): Double? = v?.takeIf { it.isFinite() && it in 0.0..1.0 }
        fun validRtt(v: Double?): Double? = v?.takeIf { it.isFinite() && it in 0.0..60_000.0 }
        val losses = listOfNotNull(validLoss(remoteVideoLoss), validLoss(remoteAudioLoss))
        val rtts = listOfNotNull(validRtt(remoteVideoRttMs), validRtt(remoteAudioRttMs))
        return Signal(losses.maxOrNull(), rtts.maxOrNull())
    }
}
