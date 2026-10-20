package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Security events may contain sensitive operational details. If encrypted storage fails,
 * fall back to *volatile memory only*; never write plaintext audit logs to disk.
 */
class SecurityEventTracker(
    private val context: Context,
    private val securePreferencesForTesting: ((Context) -> SharedPreferences)? = null
) {
    private val securePreferences: SharedPreferences? by lazy {
        try {
            securePreferencesForTesting?.invoke(context) ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, "secure_security_events_prefs", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        } catch (_: Exception) {
            null
        }
    }
    private val volatileEvents = ArrayDeque<JSONObject>()
    private val MAX_EVENTS = 50

    @Synchronized
    fun logEvent(eventType: String, details: String) {
        val event = JSONObject().apply {
            put("type", eventType.take(80))
            put("timestamp", System.currentTimeMillis())
            put("details", details.take(500))
        }
        val prefs = securePreferences
        if (prefs != null) {
            try {
                val current = JSONArray(prefs.getString("events_list", "[]") ?: "[]")
                current.put(event)
                val trimmed = JSONArray()
                for (i in maxOf(0, current.length() - MAX_EVENTS) until current.length()) {
                    trimmed.put(current.get(i))
                }
                check(prefs.edit().putString("events_list", trimmed.toString()).commit())
                return
            } catch (_: Exception) {
                // Do not retry writes against potentially corrupted encrypted preferences.
            }
        }
        volatileEvents.addLast(event)
        while (volatileEvents.size > MAX_EVENTS) volatileEvents.removeFirst()
    }

    @Synchronized
    fun getLoggedEvents(): List<Map<String, Any>> {
        val prefs = securePreferences
        val stored = if (prefs == null) null else try {
            JSONArray(prefs.getString("events_list", "[]") ?: "[]")
        } catch (_: Exception) { null }
        val events = stored ?: JSONArray().also { array ->
            volatileEvents.forEach(array::put)
        }
        val list = mutableListOf<Map<String, Any>>()
        for (i in 0 until events.length()) {
            val event = runCatching { events.getJSONObject(i) }.getOrNull() ?: continue
            list.add(mapOf(
                "type" to event.optString("type"),
                "timestamp" to event.optLong("timestamp"),
                "details" to event.optString("details")
            ))
        }
        return list.reversed()
    }

    @Synchronized
    fun clearEvents() {
        volatileEvents.clear()
        try { securePreferences?.edit()?.remove("events_list")?.commit() }
        catch (_: Exception) { }
    }
}
