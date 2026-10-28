package com.example

import com.example.ui.accessibility.AccessibleActionCatalog
import com.example.ui.accessibility.AccessibleActionCatalog.Action
import org.junit.Assert.*
import org.junit.Test

/** Localized action contract; phone-level TalkBack verification remains required. */
class AccessibleActionCatalogTest {
    @Test fun everyImportantActionHasUniqueReadableTurkishSpeechLabel() {
        val descriptions = Action.values().map { AccessibleActionCatalog.description(it) }
        assertEquals(Action.values().size, descriptions.toSet().size)
        assertTrue(descriptions.all { it.length >= 12 && it.any(Char::isLetter) })
        assertFalse(descriptions.any { it.contains("TODO") || it.contains("content_desc") })
        assertEquals("Diğer hesaplama araçları",
            AccessibleActionCatalog.description(Action.CALCULATOR_TOOLS))
    }

    @Test fun everySensitiveControlHasSafeContextFreeSpeechLabel() {
        val critical = setOf(Action.END_CALL, Action.MUTE_MICROPHONE,
            Action.SWITCH_CAMERA, Action.ANSWER_CALL, Action.DECLINE_CALL,
            Action.PRIVATE_DIAGNOSTICS, Action.BACK_TO_CALCULATOR)
        for (action in critical) {
            val label = AccessibleActionCatalog.description(action)
            assertTrue(label.length >= 15)
            assertFalse(label.contains("PIN"))
            assertFalse(label.contains("LANU-"))
            assertFalse(label.contains("http"))
        }
    }

    @Test fun documentedInteractiveMinimumIsAtLeast48Dp() {
        assertTrue(AccessibleActionCatalog.MIN_TOUCH_TARGET_DP >= 48)
    }
}
