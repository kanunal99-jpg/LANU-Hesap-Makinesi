package com.example.core.calls

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.log10
import kotlin.math.sqrt

enum class DspProcessingMode {
    AUTO_HYBRID,
    SOFTWARE_DSP,
    HARDWARE_PREF
}

data class DspTelemetry(
    val mode: DspProcessingMode = DspProcessingMode.AUTO_HYBRID,
    val isHardwareSupported: Boolean = false,
    val isHardwareActive: Boolean = false,
    val isSoftwareActive: Boolean = false,
    val noiseFloorDbfs: Float = 0f,
    val suppressionRatioDb: Int = 0,
    val latencyMs: Float = 0f,
    val voiceClarityScore: Int = 0,
    val activeAlgorithm: String = "Etkin değil — canlı mikrofon oturumu yok"
)

/**
 * Call audio preference and diagnostics adapter.
 * No real audio transport has been connected in this demo. Hardware effect
 * availability is NOT evidence that any live audio is being processed; actual
 * activity requires an AudioRecord session with RECORD_AUDIO permission.
 * The optional processPcmBuffer helper below is only a basic amplitude gate,
 * not spectral DSP, RNNoise, WebRTC APM, headset ANC or a guaranteed fallback.
 */
class AudioDspProcessor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _telemetry = MutableStateFlow(DspTelemetry())
    val telemetry: StateFlow<DspTelemetry> = _telemetry.asStateFlow()

    private var isHardwareAvailable: Boolean = false
    private var isAecAvailable: Boolean = false
    private var isAgcAvailable: Boolean = false

    private val callAudio = CallAudioPreprocessor()
    private var currentNoiseFloorRms: Float = 0.02f

    init {
        detectHardwareCapabilities()
        updateTelemetryState()
    }

    private fun detectHardwareCapabilities() {
        isHardwareAvailable = try {
            NoiseSuppressor.isAvailable()
        } catch (e: Throwable) {
            false
        }
        isAecAvailable = try {
            AcousticEchoCanceler.isAvailable()
        } catch (e: Throwable) {
            false
        }
        isAgcAvailable = try {
            AutomaticGainControl.isAvailable()
        } catch (e: Throwable) {
            false
        }
    }

    fun setDspMode(mode: DspProcessingMode) {
        _telemetry.update { it.copy(mode = mode) }
        updateTelemetryState()
    }

    /**
     * Preference only. No live call exists on this screen; suppressions,
     * latency and clarity MUST NOT be invented from a selected slider level.
     */
    fun updateAncParameters(isAncEnabled: Boolean, level: String) {
        // No effect is started solely by selecting an ENC preference.
        updateTelemetryState()
    }

    /**
     * Only a real, permission-approved AudioRecord/ WebRTC capture owner may
     * provide its audioSessionId. This preparatory adapter is not connected
     * to the existing demo. Avoid double AEC/NS when WebRTC software APM is on.
     */
    fun bindToRealAudioSession(audioSessionId: Int, microphonePermissionGranted: Boolean) {
        if (_telemetry.value.mode == DspProcessingMode.SOFTWARE_DSP) {
            callAudio.close()
        } else {
            callAudio.attach(audioSessionId, microphonePermissionGranted)
        }
        updateTelemetryState()
    }

    fun releaseAudioSession() {
        callAudio.close()
        updateTelemetryState()
    }

    private fun updateTelemetryState() {
        _telemetry.update { current ->
            val session = callAudio.status
            val active = session.hasAnyActiveEffect
            current.copy(
                isHardwareSupported = isHardwareAvailable,
                isHardwareActive = session.noiseSuppressionActive,
                isSoftwareActive = false, // no RNNoise/WebRTC processing is wired in this demo
                suppressionRatioDb = 0, // no measured figures
                latencyMs = 0f,
                voiceClarityScore = 0,
                activeAlgorithm = if (active) {
                    "Android NS=${session.noiseSuppressionActive}, " +
                        "AEC=${session.echoCancellationActive}, " +
                        "AGC=${session.automaticGainActive}"
                } else "Etkin değil — canlı mikrofon oturumu yok"
            )
        }
    }

    /**
     * Basic testable PCM amplitude gate; NOT wired into live WebRTC capture.
     * It is not spectral noise suppression and cannot replace WebRTC APM.
     */
    fun processPcmBuffer(
        buffer: ShortArray,
        isAncEnabled: Boolean,
        suppressionStrength: Float
    ): ShortArray {
        if (!isAncEnabled || buffer.isEmpty()) return buffer

        val output = ShortArray(buffer.size)
        var sumSquares = 0.0

        for (sample in buffer) {
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / buffer.size).toFloat() / 32768f
        // Smooth noise floor tracking
        currentNoiseFloorRms = 0.95f * currentNoiseFloorRms + 0.05f * rms
        val dbfs = (20 * log10(currentNoiseFloorRms.coerceAtLeast(0.0001f))).coerceIn(-80f, 0f)

        // Simple amplitude attenuation; this is not a spectral algorithm.
        val gateThreshold = (currentNoiseFloorRms * 1.5f).coerceAtLeast(0.005f)
        val attenuationFactor = (1f - (suppressionStrength * 0.75f)).coerceIn(0.15f, 1f)

        for (i in buffer.indices) {
            val sampleVal = buffer[i].toFloat() / 32768f
            val isNoise = kotlin.math.abs(sampleVal) < gateThreshold
            val filteredSample = if (isNoise) {
                sampleVal * attenuationFactor
            } else {
                // Speech frame preservation
                sampleVal
            }
            output[i] = (filteredSample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        _telemetry.update { it.copy(noiseFloorDbfs = dbfs) }
        return output
    }
}
