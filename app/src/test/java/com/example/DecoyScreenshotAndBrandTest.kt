package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecoyScreenshotAndBrandTest {
    @Test fun `public calculator and utility screens allow screenshots`() {
        assertFalse(MainActivity.requiresScreenshotProtection(MainActivity.Screen.Calculator))
        assertFalse(MainActivity.requiresScreenshotProtection(MainActivity.Screen.UnitConverter()))
        assertFalse(MainActivity.requiresScreenshotProtection(MainActivity.Screen.Finance))
    }

    @Test fun `PIN entry and all private messaging screens block screenshots`() {
        assertTrue(MainActivity.requiresScreenshotProtection(MainActivity.Screen.Security()))
        assertTrue(MainActivity.requiresScreenshotProtection(MainActivity.Screen.Communication))
        assertTrue(MainActivity.requiresScreenshotProtection(MainActivity.Screen.ChatDetail("private-conversation")))
    }

    @Test fun `LANU premium launcher resources are available`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("LANU", context.getString(R.string.app_name))
        assertEquals("LANU Hesap Makinesi", context.getString(R.string.app_full_name))
        assertNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))
        assertNotNull(context.getDrawable(R.drawable.ic_lanu_monochrome))
    }
}