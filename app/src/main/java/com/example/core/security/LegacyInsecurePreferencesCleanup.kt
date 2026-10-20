package com.example.core.security

import android.content.Context
import android.os.Build
import java.io.File

/** Delete only historical unencrypted FALLBACK settings, never encrypted identity or Room. */
object LegacyInsecurePreferencesCleanup {
    private val oldFallbackNames = listOf(
        "secure_settings_prefs_fallback",
        "secure_security_events_fallback"
    )

    fun clean(context: Context) {
        for (name in oldFallbackNames) {
            val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val backupFile = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml.bak")
            if (!legacyFile.exists() && !backupFile.exists()) continue
            // Discard insecure biometric toggle and old audit log contents.
            // Never silently re-enable biometrics when Keystore protection is unavailable.
            try {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                if (Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(name)
                else {
                    legacyFile.delete()
                    backupFile.delete()
                }
            } catch (_: Exception) {
                // If cleanup fails we still never read or write these plaintext files.
            }
        }
    }
}
