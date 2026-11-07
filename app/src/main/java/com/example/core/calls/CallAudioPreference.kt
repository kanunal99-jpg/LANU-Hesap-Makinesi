package com.example.core.calls

import android.content.Context
import android.content.SharedPreferences

/** Non-secret local preference. Never records audio, routes, peers or calls. */
internal class CallAudioPreference(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("lanu_audio_options", Context.MODE_PRIVATE))

    fun preferSoftware(): Boolean = try { preferences.getBoolean("software_processing", false) }
        catch (_: RuntimeException) { false }

    fun savePreferSoftware(enabled: Boolean): Boolean = try {
        preferences.edit().putBoolean("software_processing", enabled).commit() && preferSoftware() == enabled
    } catch (_: RuntimeException) { false }
}
