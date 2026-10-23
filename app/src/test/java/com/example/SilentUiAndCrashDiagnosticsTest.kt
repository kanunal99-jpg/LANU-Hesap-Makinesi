package com.example

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression checks for default-silent feedback and backward-compatible diagnostics. */
@RunWith(RobolectricTestRunner::class)
class SilentUiAndCrashDiagnosticsTest {
    @Test
    @Config(sdk = [34])
    fun composeHostIsSilentEvenIfSystemClickSoundsAndHapticsWereInitiallyEnabled() {
        val view = View(ApplicationProvider.getApplicationContext<Context>())
        view.isSoundEffectsEnabled = true
        view.isHapticFeedbackEnabled = true
        MainActivity.configureSilentUi(view)
        assertFalse(view.isSoundEffectsEnabled)
        assertFalse(view.isHapticFeedbackEnabled)
    }

    @Test
    @Config(sdk = [28])
    fun crashDiagnosticsDoesNotInvokeUnavailableAndroidExitApisOnOlderPhones() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(CrashDiagnostics.mostRecentAbnormalExit(context))
    }
}
