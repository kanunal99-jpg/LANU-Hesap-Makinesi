package com.example

import com.example.core.calls.CallSpeechProcessingPolicy
import org.junit.Assert.*
import org.junit.Test

/** Validate WebRTC-only speech preferences without manufacturing dB gains. */
class CallSpeechProcessingPolicyTest {
    @Test fun voiceAudioUsesMonoWithNoVolumeLoggerAndSingleApm() {
        assertFalse(CallSpeechProcessingPolicy.stereoMicrophone)
        assertFalse(CallSpeechProcessingPolicy.stereoSpeaker)
        assertFalse(CallSpeechProcessingPolicy.volumeLogger)
        val settings = CallSpeechProcessingPolicy.audioSourceConstraints()
        val keys = settings.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("true", settings.single { it.first == "googEchoCancellation" }.second)
        assertEquals("true", settings.single { it.first == "googNoiseSuppression" }.second)
        assertEquals("true", settings.single { it.first == "googAutoGainControl" }.second)
        assertEquals("true", settings.single { it.first == "googHighpassFilter" }.second)
    }

    @Test fun optionalApmPreferencesNeverCarrySamplesRemoteEndpointsOrIdentity() {
        val settings = CallSpeechProcessingPolicy.audioSourceConstraints()
        assertTrue(settings.all { (key, value) ->
            key.startsWith("goog") && value in setOf("true", "false")
        })
    }
}
