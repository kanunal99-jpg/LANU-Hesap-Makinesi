package com.example.core.calls

import android.content.Context
import android.content.SharedPreferences

/** Non-secret local preference. Never records audio, routes, peers or calls. */
internal class CallAudioPreference(private val preferences: SharedPreferences?) {
    constructor(context: Context) : this(try {
        context.getSharedPreferences("lanu_audio_options", Context.MODE_PRIVATE)
    } catch (_: RuntimeException) { null })

    fun preferSoftware(): Boolean = try { preferences?.getBoolean("software_processing", false) ?: false }
        catch (_: RuntimeException) { false }

    fun savePreferSoftware(enabled: Boolean): Boolean = try {
        preferences?.edit()?.putBoolean("software_processing", enabled)?.commit() == true && preferSoftware() == enabled
    } catch (_: RuntimeException) { false }
}
