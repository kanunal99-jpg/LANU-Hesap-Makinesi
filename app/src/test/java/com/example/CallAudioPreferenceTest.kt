package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallAudioPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class CallAudioPreferenceTest {
    @Test fun automaticDefaultSoftwareOptInAndExplicitRollbackSurviveRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("audio-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = CallAudioPreference(prefs)
        assertFalse(store.preferSoftware())
        assertTrue(store.savePreferSoftware(true))
        assertTrue(CallAudioPreference(prefs).preferSoftware())
        assertTrue(store.savePreferSoftware(false))
        assertFalse(CallAudioPreference(prefs).preferSoftware())
        assertEquals(setOf("software_processing"), prefs.all.keys)
    }

    @Test fun malformedPreferenceKeepsAutomaticModeWithoutCrashingOrClearingOtherData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("bad-audio-test", Context.MODE_PRIVATE)
        prefs.edit().putString("software_processing", "invalid").putString("preserved", "yes").commit()
        assertFalse(CallAudioPreference(prefs).preferSoftware())
        assertEquals("yes", prefs.getString("preserved", null))
        assertTrue(CallAudioPreference(prefs).savePreferSoftware(true))
        assertEquals("yes", prefs.getString("preserved", null))
    }
    @Test fun unavailablePreferenceStoreDoesNotPreventAutomaticCallAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failing = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw SecurityException("preference storage unavailable")
            }
        }
        val store = CallAudioPreference(failing)
        assertFalse(store.preferSoftware())
        assertFalse(store.savePreferSoftware(true))
    }

}
