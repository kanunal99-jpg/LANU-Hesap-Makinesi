package com.example

import com.example.features.communication.PrivateCallControlDescriptionPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

/** Device-level TalkBack/large-font acceptance is a separate manual gate. */
class PrivateCallControlDescriptionPolicyTest {
    @Test fun microphoneActionAlwaysDescribesTheNextState() {
        assertEquals("Mikrofonu sustur", Policy.microphoneToggle(false))
        assertEquals("Mikrofonu aç", Policy.microphoneToggle(true))
        assertNotEquals(Policy.microphoneToggle(false), Policy.microphoneToggle(true))
    }

    @Test fun cameraActionNeverAnnouncesWrongState() {
        assertEquals("Kamerayı aç", Policy.cameraToggle(false))
        assertEquals("Kamerayı kapat", Policy.cameraToggle(true))
        assertNotEquals(Policy.cameraToggle(false), Policy.cameraToggle(true))
    }

    @Test fun privateCallSpeechLabelsNeverContainSecretsOrIds() {
        val labels = listOf(Policy.microphoneToggle(true), Policy.microphoneToggle(false),
            Policy.cameraToggle(true), Policy.cameraToggle(false), Policy.CAMERA_SWITCH)
        for (label in labels) {
            assertTrue(label.length >= 10)
            assertFalse(label.contains("PIN"))
            assertFalse(label.contains("2011"))
            assertFalse(label.contains("https://"))
        }
    }
}
