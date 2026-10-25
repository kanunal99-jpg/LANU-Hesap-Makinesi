package com.example.core.calls

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.Closeable

/**
 * Platform call-audio preprocessing for a REAL microphone AudioRecord session.
 *
 * This does not record or transmit sound, start calls, implement headset ANC,
 * or claim any measured suppression. The WebRTC AudioDeviceModule/AudioRecord
 * owner must pass its actual audio session ID AFTER the user granted RECORD_AUDIO.
 * Never attach the Android effects while software WebRTC APM is simultaneously
 * applying the same filters; the media owner must select exactly one pipeline.
 */
class CallAudioPreprocessor(
    private val effects: EffectFactory = AndroidEffectFactory()
) : Closeable {

    enum class Kind { ECHO_CANCELLATION, NOISE_SUPPRESSION, AUTO_GAIN }

    interface Effect : Closeable {
        fun activate(): Boolean
    }

    interface EffectFactory {
        fun create(kind: Kind, audioSessionId: Int): Effect?
    }

    data class Status(
        val audioSessionId: Int? = null,
        val echoCancellationActive: Boolean = false,
        val noiseSuppressionActive: Boolean = false,
        val automaticGainActive: Boolean = false
    ) {
        val hasAnyActiveEffect: Boolean
            get() = echoCancellationActive || noiseSuppressionActive || automaticGainActive
    }

    private val attached = mutableListOf<Effect>()
    var status: Status = Status()
        private set

    /**
     * Calls with invalid session IDs or without confirmed microphone permission
     * fail closed. A supported effect is only reported active after successful
     * activation. Missing/failed device effects do not block other effects.
     */
    @Synchronized
    fun attach(audioSessionId: Int, microphonePermissionGranted: Boolean): Status {
        releaseAttached()
        if (audioSessionId <= 0 || !microphonePermissionGranted) return status

        fun attachOne(kind: Kind): Boolean {
            val effect = try {
                effects.create(kind, audioSessionId)
            } catch (_: RuntimeException) {
                null
            } ?: return false
            val activated = try {
                effect.activate()
            } catch (_: RuntimeException) {
                false
            }
            if (activated) {
                attached.add(effect)
            } else {
                try { effect.close() } catch (_: RuntimeException) { /* best effort */ }
            }
            return activated
        }

        // AEC first: suppress speaker echo before noise control and gain.
        val echo = attachOne(Kind.ECHO_CANCELLATION)
        val noise = attachOne(Kind.NOISE_SUPPRESSION)
        val gain = attachOne(Kind.AUTO_GAIN)
        status = Status(audioSessionId, echo, noise, gain)
        return status
    }

    @Synchronized
    override fun close() {
        releaseAttached()
    }

    private fun releaseAttached() {
        for (effect in attached.asReversed()) {
            try { effect.close() } catch (_: RuntimeException) { /* best effort */ }
        }
        attached.clear()
        status = Status()
    }
}

/** Device-specific NS/AEC/AGC support and control are checked, not inferred. */
class AndroidEffectFactory : CallAudioPreprocessor.EffectFactory {
    override fun create(
        kind: CallAudioPreprocessor.Kind,
        audioSessionId: Int
    ): CallAudioPreprocessor.Effect? = when (kind) {
        CallAudioPreprocessor.Kind.ECHO_CANCELLATION -> {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.let(::AndroidAudioEffect)
            } else null
        }
        CallAudioPreprocessor.Kind.NOISE_SUPPRESSION -> {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.let(::AndroidAudioEffect)
            } else null
        }
        CallAudioPreprocessor.Kind.AUTO_GAIN -> {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(audioSessionId)?.let(::AndroidAudioEffect)
            } else null
        }
    }
}

private class AndroidAudioEffect(private val delegate: AudioEffect) : CallAudioPreprocessor.Effect {
    override fun activate(): Boolean {
        if (!delegate.hasControl()) return false
        // Some VOICE_COMMUNICATION sources have processing enabled by default.
        if (!delegate.enabled && delegate.setEnabled(true) != AudioEffect.SUCCESS) return false
        return delegate.enabled && delegate.hasControl()
    }

    override fun close() {
        delegate.release()
    }
}
