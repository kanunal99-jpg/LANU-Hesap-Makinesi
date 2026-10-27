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

    @Test fun callButtonsExposeStateSpecificReadableLabels() {
        assertNotEquals(
            AccessibleActionCatalog.description(Action.MUTE_MIC),
            AccessibleActionCatalog.description(Action.UNMUTE_MIC))
        assertNotEquals(
            AccessibleActionCatalog.description(Action.CAMERA_ON),
            AccessibleActionCatalog.description(Action.CAMERA_OFF))
        assertTrue(AccessibleActionCatalog.description(Action.SWITCH_CAMERA).length >= 20)
    }

    @Test fun documentedInteractiveMinimumIsAtLeast48Dp() {
        assertTrue(AccessibleActionCatalog.MIN_TOUCH_TARGET_DP >= 48)
    }
}
