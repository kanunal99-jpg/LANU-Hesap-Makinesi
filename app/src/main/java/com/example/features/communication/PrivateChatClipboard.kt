package com.example.features.communication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copy is an EXPLICIT user action. On API 24+ mark private chat text as
 * sensitive before the system clipboard receives it; Android 13+ can then
 * redact the system keyboard/clipboard preview.
 *
 * This flag is only a display hint, NOT encryption or clipboard isolation.
 * API 23 has no ClipDescription.setExtras; preserve manual copy behavior.
 * No background listeners, network access, history logging or silent copies.
 */
internal object PrivateChatClipboard {
    const val SENSITIVE_FLAG = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val clip = ClipData.newPlainText("", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(SENSITIVE_FLAG, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        return true
    }
}
