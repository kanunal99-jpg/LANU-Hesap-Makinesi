package com.example.core.messaging

import org.json.JSONObject

/** Bounded local metadata only. Ciphertext remains unacknowledged on the relay. */
internal object MessageQuarantine {
    const val KEY = "lanu_quarantine_v1"
    const val MAX_ENTRIES = 128
    enum class Reason { MISSING_SENDER_KEY, INVALID_CIPHERTEXT, HIDDEN_CONVERSATION }
    private fun parse(raw: String?): JSONObject = try { JSONObject(raw ?: "{}") } catch (_: org.json.JSONException) { JSONObject() }
    fun record(raw: String?, id: String, eventId: String, revision: Long, reason: Reason): String {
        val items = parse(raw)
        if ((items.optJSONObject(id)?.optLong("revision") ?: -1L) <= revision) {
            items.remove(id)
            items.put(id, JSONObject().put("eventId", eventId).put("revision", revision).put("reason", reason.name))
        }
        while (items.length() > MAX_ENTRIES) items.remove(items.keys().next())
        return items.toString()
    }
    fun clearThrough(raw: String?, id: String, revision: Long): String? {
        val items = parse(raw)
        if ((items.optJSONObject(id)?.optLong("revision") ?: Long.MAX_VALUE) <= revision) items.remove(id)
        return items.toString().takeIf { items.length() > 0 }
    }
}
