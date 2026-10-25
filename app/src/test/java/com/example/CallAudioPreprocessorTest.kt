package com.example

import com.example.core.calls.CallAudioPreprocessor
import com.example.core.calls.CallAudioPreprocessor.Kind
import org.junit.Assert.*
import org.junit.Test

class CallAudioPreprocessorTest {
    private class FakeEffects : CallAudioPreprocessor.EffectFactory {
        val created = mutableListOf<Pair<Kind, Int>>()
        val freed = mutableListOf<Kind>()
        val activateFailures = mutableSetOf<Kind>()
        val unavailable = mutableSetOf<Kind>()
        var throwDuringCreate: Kind? = null

        override fun create(kind: Kind, audioSessionId: Int): CallAudioPreprocessor.Effect? {
            if (kind == throwDuringCreate) throw IllegalStateException("unsupported audio HAL")
            if (kind in unavailable) return null
            created.add(kind to audioSessionId)
            return object : CallAudioPreprocessor.Effect {
                override fun activate() = kind !in activateFailures
                override fun close() { freed.add(kind) }
            }
        }
    }

    @Test fun refusesToBindWithoutRealAudioSessionOrPermission() {
        val effects = FakeEffects()
        val session = CallAudioPreprocessor(effects)
        assertFalse(session.attach(0, true).hasAnyActiveEffect)
        assertFalse(session.attach(32, false).hasAnyActiveEffect)
        assertFalse(session.attach(-7, true).hasAnyActiveEffect)
        assertTrue(effects.created.isEmpty())
        assertNull(session.status.audioSessionId)
    }

    @Test fun enablesSupportedAudioEffectsOnlyAfterSuccessfulActivation() {
        val factory = FakeEffects()
        val session = CallAudioPreprocessor(factory)
        val status = session.attach(87, true)
        assertEquals(87, status.audioSessionId)
        assertTrue(status.echoCancellationActive)
        assertTrue(status.noiseSuppressionActive)
        assertTrue(status.automaticGainActive)
        assertEquals(
            listOf(Kind.ECHO_CANCELLATION, Kind.NOISE_SUPPRESSION, Kind.AUTO_GAIN),
            factory.created.map { it.first }
        )
        assertTrue(factory.created.all { it.second == 87 })
        session.close()
        assertEquals(3, factory.freed.size)
        assertFalse(session.status.hasAnyActiveEffect)
        assertNull(session.status.audioSessionId)
        session.close() // idempotent
        assertEquals(3, factory.freed.size)
    }

    @Test fun unsupportedOrFailedEffectsDoNotClaimRealSuppression() {
        val factory = FakeEffects().apply {
            unavailable.add(Kind.NOISE_SUPPRESSION)
            activateFailures.add(Kind.AUTO_GAIN)
        }
        val session = CallAudioPreprocessor(factory)
        val result = session.attach(13, true)
        assertTrue(result.echoCancellationActive)
        assertFalse(result.noiseSuppressionActive)
        assertFalse(result.automaticGainActive)
        assertEquals(listOf(Kind.AUTO_GAIN), factory.freed)
        session.close()
        assertEquals(listOf(Kind.AUTO_GAIN, Kind.ECHO_CANCELLATION), factory.freed)
    }

    @Test fun brokenVendorAudioHalIsTreatedAsUnavailableAndOtherEffectsSurvive() {
        val factory = FakeEffects().apply { throwDuringCreate = Kind.NOISE_SUPPRESSION }
        val session = CallAudioPreprocessor(factory)
        val result = session.attach(23, true)
        assertTrue(result.echoCancellationActive)
        assertFalse(result.noiseSuppressionActive)
        assertTrue(result.automaticGainActive)
    }

    @Test fun newCallReleasesOldEffectsAndFailedPermissionDropsOldSession() {
        val factory = FakeEffects()
        val session = CallAudioPreprocessor(factory)
        session.attach(11, true)
        session.attach(12, true)
        assertEquals(3, factory.freed.size)
        assertEquals(12, session.status.audioSessionId)
        val blocked = session.attach(12, false)
        assertNull(blocked.audioSessionId)
        assertFalse(blocked.hasAnyActiveEffect)
        assertEquals(6, factory.freed.size)
    }
}
