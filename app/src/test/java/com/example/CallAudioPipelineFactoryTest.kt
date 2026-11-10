package com.example

import com.example.core.calls.CallAudioPipelineFactory
import com.example.core.calls.CallAudioProcessingPolicy
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CallAudioPipelineFactoryTest {
    private val hardware = CallAudioProcessingPolicy.choose(true, true)
    @Test fun factoryFailureReleasesHardwareBeforeOneSoftwareAttempt() {
        val events = mutableListOf<String>()
        val result = CallAudioPipelineFactory.build(hardware,
            createModule = { if (it.hardwareAec) "hardware" else "software" },
            createEngine = { events += "build:$it"; if (it == "hardware") error("OEM"); "engine" },
            releaseModule = { events += "release:$it" })
        assertEquals("software" to "engine", result)
        assertEquals(listOf("build:hardware", "release:hardware", "build:software"), events)
    }
    @Test fun failureOfBothAttemptsReleasesBothAndStops() {
        var created = 0; var released = 0
        val error = runCatching { CallAudioPipelineFactory.build(hardware,
            createModule = { ++created }, createEngine = { error("OEM") },
            releaseModule = { released++ }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(2, created); assertEquals(2, released)
    }
    @Test fun hardwareAudioLinkageFailureRecoversViaOneSoftwareApmAttempt() {
        val events = mutableListOf<String>()
        val result = CallAudioPipelineFactory.build(hardware,
            createModule = {
                if (it.hardwareAec) "oem-hardware" else "webrtc-software"
            },
            createEngine = {
                events += "open:$it"
                if (it == "oem-hardware") throw LinkageError("OEM audiofx broken")
                "working-microphone"
            },
            releaseModule = { events += "release:$it" })
        assertEquals("webrtc-software" to "working-microphone", result)
        assertEquals(listOf("open:oem-hardware", "release:oem-hardware",
            "open:webrtc-software"), events)
    }

    @Test fun fatalJvmErrorNeverTriggersAnUnsafeExtraAudioInitialization() {
        var opened = 0
        var released = 0
        val fatal = OutOfMemoryError("no memory")
        val result = runCatching {
            CallAudioPipelineFactory.build(hardware, { ++opened },
                { throw fatal }, { released++ })
        }
        assertSame(fatal, result.exceptionOrNull())
        assertEquals(1, opened)
        assertEquals(1, released)
    }

    @Test fun cancellationAndPermissionFailuresNeverRetry() {
        for (error in listOf(CancellationException("cancel"), SecurityException("denied"))) {
            var created = 0; var released = 0
            val actual = runCatching { CallAudioPipelineFactory.build(hardware,
                createModule = { ++created }, createEngine = { throw error },
                releaseModule = { released++ }) }.exceptionOrNull()
            assertSame(error, actual); assertEquals(1, created); assertEquals(1, released)
        }
    }
    @Test fun softwareOnlyCannotLoopAndSuccessIsNotReleased() {
        var released = 0; var created = 0
        val result = CallAudioPipelineFactory.build(hardware, { ++created }, { "ok" }, { released++ })
        assertEquals(1 to "ok", result); assertEquals(0, released)
        val software = CallAudioProcessingPolicy.choose(false, false)
        runCatching { CallAudioPipelineFactory.build(software, { ++created }, { error("fail") }, { released++ }) }
        assertEquals(2, created); assertEquals(1, released)
    }
}
