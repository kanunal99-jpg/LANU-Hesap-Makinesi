package com.example

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.features.communication.PrivateChatClipboard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PrivateChatClipboardTest {
    @Test
    @Config(sdk = [34])
    fun android13PlusCopiesWithSensitiveFlagAndNoPreviewLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(PrivateChatClipboard.copy(context, "özel mesaj"))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals("özel mesaj", clip.getItemAt(0).text.toString())
        assertEquals("", clip.description.label.toString())
        assertTrue(clip.description.extras.getBoolean(PrivateChatClipboard.SENSITIVE_FLAG))
    }

    @Test
    @Config(sdk = [24])
    fun android7SetsCompatibleSensitivityMarker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(PrivateChatClipboard.copy(context, "secret"))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertTrue(requireNotNull(clipboard.primaryClip).description.extras
            .getBoolean(PrivateChatClipboard.SENSITIVE_FLAG))
    }

    @Test
    @Config(sdk = [23])
    fun android6CopiesWithoutCallingUnavailableClipExtrasApi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(PrivateChatClipboard.copy(context, "legacy"))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("legacy", requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString())
    }

    @Test
    @Config(sdk = [34])
    fun emptyTextIsNotCopied() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(PrivateChatClipboard.copy(context, ""))
    }
}
