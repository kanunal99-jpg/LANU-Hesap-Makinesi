package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.features.communication.IncomingCallActions
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class IncomingCallActionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun answerAndDeclineAreVisibleAndDispatchDistinctUserActions() {
        var answers=0;var declines=0
        compose.setContent { MaterialTheme { IncomingCallActions({answers++},{declines++}) } }
        compose.onNodeWithText("Yanıtla").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,answers);assertEquals(0,declines) }
        compose.onNodeWithText("Reddet").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,answers);assertEquals(1,declines) }
    }
    @Test fun bufferedVerificationDisablesRepeatAndConflictingGestures() {
        val pending=mutableStateOf(false);var answers=0;var declines=0
        compose.setContent { MaterialTheme { IncomingCallActions({answers++;pending.value=true},{declines++},!pending.value) } }
        compose.onNodeWithTag("incoming_call_answer").performClick()
        compose.onNodeWithTag("incoming_call_answer").assertIsNotEnabled()
        compose.onNodeWithTag("incoming_call_decline").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,answers);assertEquals(0,declines) }
    }
}
