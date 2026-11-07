package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.features.security.FullBackupPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class FullBackupPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun verifyIsExplicitAndNeverInvokesRestore() {
        var verified: String? = null; var restores = 0
        compose.setContent { MaterialTheme {
            FullBackupPanel(false, true, null, {}, {}, { restores++ }, {}, { verified = it })
        } }
        compose.onNodeWithText("Yedeği değiştirmeden doğrula").performClick()
        compose.onNodeWithText("Doğrulamayı başlat").assertIsNotEnabled()
        compose.onNodeWithText("Yedek parolası").performTextInput("Recovery-probe-password")
        compose.onNodeWithText("Doğrulamayı başlat").performClick()
        compose.runOnIdle { assertEquals("Recovery-probe-password", verified); assertEquals(0, restores) }
    }
    @Test fun noArchiveHasNoVerificationAction() {
        compose.setContent { MaterialTheme { FullBackupPanel(false, false, null, {}, {}, {}, {}) } }
        compose.onNodeWithText("Yedeği değiştirmeden doğrula").assertDoesNotExist()
    }
    @Test fun busyArchiveCannotStartAnotherVerification() {
        compose.setContent { MaterialTheme { FullBackupPanel(true, true, null, {}, {}, {}, {}) } }
        compose.onNodeWithText("Yedeği değiştirmeden doğrula").assertIsNotEnabled()
    }
}
