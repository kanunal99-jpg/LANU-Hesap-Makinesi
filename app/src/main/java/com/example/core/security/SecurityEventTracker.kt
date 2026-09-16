package com.example.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SecurityEventTracker(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "secure_security_events_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback or handle encryption corruption gracefully
        context.getSharedPreferences("secure_security_events_fallback", Context.MODE_PRIVATE)
    }

    fun logEvent(eventType: String, details: String) {
        try {
            val eventsJson = sharedPreferences.getString("events_list", "[]") ?: "[]"
            val jsonArray = JSONArray(eventsJson)

            val eventObject = JSONObject().apply {
                put("type", eventType)
                put("timestamp", System.currentTimeMillis())
                put("details", details)
            }

            jsonArray.put(eventObject)

            // Keep last 50 events for privacy and storage efficiency
            val trimmedArray = JSONArray()
            val startIndex = maxOf(0, jsonArray.length() - 50)
            for (i in startIndex until jsonArray.length()) {
                trimmedArray.put(jsonArray.get(i))
            }

            sharedPreferences.edit()
                .putString("events_list", trimmedArray.toString())
                .apply()
        } catch (e: Exception) {
            // Fail silently to adhere to strict privacy/security guidelines
        }
    }

    fun getLoggedEvents(): List<Map<String, Any>> {
        return try {
            val eventsJson = sharedPreferences.getString("events_list", "[]") ?: "[]"
            val jsonArray = JSONArray(eventsJson)
            val list = mutableListOf<Map<String, Any>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    mapOf(
                        "type" to obj.optString("type"),
                        "timestamp" to obj.optLong("timestamp"),
                        "details" to obj.optString("details")
                    )
                )
            }
            list.reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearEvents() {
        try {
            sharedPreferences.edit().remove("events_list").apply()
        } catch (e: Exception) {
            // Ignored
        }
    }
}
