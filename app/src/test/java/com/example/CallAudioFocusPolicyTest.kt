package com.example

import android.media.AudioManager
import com.example.core.calls.CallAudioFocusPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class CallAudioFocusPolicyTest {
    @Test fun transientAndPermanentAudioFocusMustHoldRatherThanTerminateRtpCall() {
        assertEquals(CallAudioFocusPolicy.Action.MUTE_AND_HOLD,
            CallAudioFocusPolicy.classify(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(CallAudioFocusPolicy.Action.MUTE_AND_HOLD,
            CallAudioFocusPolicy.classify(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(CallAudioFocusPolicy.Action.RESTORE_IF_USER_UNMUTED,
            CallAudioFocusPolicy.classify(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(CallAudioFocusPolicy.Action.MUTE_AND_HOLD,
            CallAudioFocusPolicy.classify(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(CallAudioFocusPolicy.Action.IGNORE,
            CallAudioFocusPolicy.classify(0))
        // A 45-second SIM/VoIP call must no longer time out the E2EE session.
    }

    @Test fun userMuteIsNeverBypassedWhenFocusReturns() {
        assertTrue(CallAudioFocusPolicy.effectiveMute(false, true))
        assertTrue(CallAudioFocusPolicy.effectiveMute(true, false))
        assertTrue(CallAudioFocusPolicy.effectiveMute(true, true))
        assertFalse(CallAudioFocusPolicy.effectiveMute(false, false))
        // User unmutes while system focus is still absent: microphone stays off.
        assertTrue(CallAudioFocusPolicy.effectiveMute(false, true))
        // A user-mute set during interruption survives focus recovery.
        assertTrue(CallAudioFocusPolicy.effectiveMute(true, false))
    }
}
