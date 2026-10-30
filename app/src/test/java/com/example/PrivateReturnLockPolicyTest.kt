package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for Android foreground/task restoration with optional entry PIN. */
class PrivateReturnLockPolicyTest {
    @Test fun lockedPrivateScreensNeverReappearAfterForegrounding() {
        // Applies equally when the user has opted out of the additional PIN.
        assertTrue(MainActivity.mustConcealLockedPrivateScreen(
            true, MainActivity.Screen.Communication))
        assertTrue(MainActivity.mustConcealLockedPrivateScreen(
            true, MainActivity.Screen.ChatDetail("opaque-thread")))
    }

    @Test fun publicScreensStayPublicWhileLocked() {
        assertFalse(MainActivity.mustConcealLockedPrivateScreen(
            true, MainActivity.Screen.Calculator))
        assertFalse(MainActivity.mustConcealLockedPrivateScreen(
            true, MainActivity.Screen.Updates))
        assertFalse(MainActivity.mustConcealLockedPrivateScreen(
            true, MainActivity.Screen.Security()))
        assertFalse(MainActivity.mustConcealLockedPrivateScreen(true, null))
    }

    @Test fun authenticatedUnlockedPrivateNavigationIsNotInterrupted() {
        assertFalse(MainActivity.mustConcealLockedPrivateScreen(
            false, MainActivity.Screen.Communication))
        assertFalse(MainActivity.mustConcealLockedPrivateScreen(
            false, MainActivity.Screen.ChatDetail("opaque-thread")))
    }
}
