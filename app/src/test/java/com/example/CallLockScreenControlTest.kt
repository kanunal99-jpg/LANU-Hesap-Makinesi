package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.core.calls.CallLockScreenSettings
import com.example.features.communication.CallLockScreenControl
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class CallLockScreenControlTest {
    @get:Rule val compose=createComposeRule()
    @Test fun showOnLockScreenOpensSettingsWithoutFakingEnabledState() {
        var opened=0
        compose.setContent { MaterialTheme { CallLockScreenControl(CallLockScreenSettings.State.HIDDEN, {}){opened++} } }
        compose.onNodeWithText("Kilit ekranında göster").assertIsDisplayed().performClick()
        compose.onNodeWithText(CallLockScreenSettings.State.HIDDEN.description).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1,opened) }
    }
    @Test fun optOutStillHasExplicitRepairAction() {
        var opened=0
        compose.setContent { MaterialTheme { CallLockScreenControl(CallLockScreenSettings.State.CALLS_OFF, {}){opened++} } }
        compose.onNodeWithTag("call_lock_screen_settings").assertIsEnabled().performClick()
        compose.onNodeWithText(CallLockScreenSettings.State.CALLS_OFF.description).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1,opened) }
    }
    @Test fun samsungContentShortcutIsDistinctFromTheCallCategoryShortcut() {
        var appPages=0;var callPages=0
        compose.setContent { MaterialTheme { CallLockScreenControl(CallLockScreenSettings.State.REDACTED,{appPages++}){callPages++} } }
        compose.onNodeWithText("İçeriği gizle ayarını kontrol et").performClick()
        compose.runOnIdle { assertEquals(1,appPages);assertEquals(0,callPages) }
        compose.onNodeWithText("Kilit ekranında göster").performClick()
        compose.runOnIdle { assertEquals(1,appPages);assertEquals(1,callPages) }
    }

    @Test fun enrollmentOrRevocationInProgressCannotLaunchAnotherSettingsFlow() {
        compose.setContent { MaterialTheme { CallLockScreenControl(CallLockScreenSettings.State.CALLS_OFF,{},enabled=false){} } }
        compose.onNodeWithTag("call_lock_screen_settings").assertIsNotEnabled()
        compose.onNodeWithTag("call_lock_content_settings").assertIsNotEnabled()
    }

}
