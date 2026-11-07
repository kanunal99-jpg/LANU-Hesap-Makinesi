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
 * API24+ schedules one best-effort 60s expiry, clearing only its random owner
 * marker; other clipboard content is preserved. OS background restrictions or
 * process death can prevent expiry. No listener, history or content logging.
 */
internal object PrivateChatClipboard {
    const val SENSITIVE_FLAG = "android.content.extra.IS_SENSITIVE"

    private const val OWNER_MARKER = "com.example.clipboard.temporary_owner"
    const val LIFETIME_MS = 60_000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var expiry: Runnable? = null

    @Synchronized
    fun copy(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = ClipData.newPlainText("", text)
            val token = java.util.UUID.randomUUID().toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(SENSITIVE_FLAG, true)
                    putString(OWNER_MARKER, token)
                }
            }
            clipboard.setPrimaryClip(clip)
            expiry?.let { handler.removeCallbacks(it) }
            expiry = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Retain only a random ownership token, never the copied text.
                val task = Runnable { expire(clipboard, token) }
                expiry = task
                handler.postDelayed(task, LIFETIME_MS)
            }
            true
        } catch (_: RuntimeException) { false }
    }

    @Synchronized
    private fun expire(clipboard: ClipboardManager, token: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                clipboard.primaryClipDescription?.extras?.getString(OWNER_MARKER) == token) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: RuntimeException) { /* OS may deny background access; never crash or log content. */ }
    }
}
