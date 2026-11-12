package com.example

import android.app.NotificationManager
import android.media.AudioManager
import com.example.core.calls.CallRingVibrationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRingVibrationPolicyTest {
    @Test fun systemSilentDndAndBlockedChannelRemainAuthoritative() {
        val normal = AudioManager.RINGER_MODE_NORMAL
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        assertTrue(CallRingVibrationPolicy.allowed(normal, all, null, null))
        assertTrue(CallRingVibrationPolicy.allowed(AudioManager.RINGER_MODE_VIBRATE, all,
            NotificationManager.IMPORTANCE_HIGH, true))
        assertFalse(CallRingVibrationPolicy.allowed(AudioManager.RINGER_MODE_SILENT, all,
            NotificationManager.IMPORTANCE_HIGH, true))
        assertFalse(CallRingVibrationPolicy.allowed(normal,
            NotificationManager.INTERRUPTION_FILTER_NONE, null, null))
        assertFalse(CallRingVibrationPolicy.allowed(normal,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, null, null))
        assertFalse(CallRingVibrationPolicy.allowed(normal, all,
            NotificationManager.IMPORTANCE_NONE, true))
        assertFalse(CallRingVibrationPolicy.allowed(normal, all,
            NotificationManager.IMPORTANCE_HIGH, false))
        assertTrue(CallRingVibrationPolicy.PATTERN.any { it > 0L })
    }
}
