package com.example.core.calls

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Minimal authenticated signaling transport for WebRTC offer/answer/ICE messages. */
class LanuCallSignalingClient(
    context: Context,
    private val baseUrl: String,
    private val publishableKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    data class Signal(val id: String, val senderId: String, val type: String, val payload: JSONObject, val createdAt: String)

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "lanu_supabase_session", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun currentUserId(): String = prefs.getString("user_id", null) ?: error("LANU oturumu bulunamadı")

    suspend fun send(conversationId: String, senderId: String, type: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        val token = prefs.getString("access_token", null) ?: error("LANU oturumu bulunamadı")
        val authenticatedSenderId = currentUserId()
        val body = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("conversation_id", conversationId)
            .put("sender_id", authenticatedSenderId)
            .put("signal_type", type)
            .put("payload", payload)
            .toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_call_signals")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Call signaling HTTP ${response.code}: ${response.body?.string().orEmpty()}")
        }
    }

    suspend fun poll(conversationId: String, after: String?): List<Signal> = withContext(Dispatchers.IO) {
        val token = prefs.getString("access_token", null) ?: return@withContext emptyList()
        val params = buildString {
            append("conversation_id=eq.").append(URLEncoder.encode(conversationId, "UTF-8"))
            if (!after.isNullOrBlank()) append("&created_at=gt.").append(URLEncoder.encode(after, "UTF-8"))
            append("&order=created_at.asc&limit=50")
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_call_signals?$params")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get().build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Call signaling HTTP ${response.code}: $text")
            val array = org.json.JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val row = array.getJSONObject(i)
                    add(Signal(
                        id = row.getString("id"),
                        senderId = row.getString("sender_id"),
                        type = row.getString("signal_type"),
                        payload = row.optJSONObject("payload") ?: JSONObject(),
                        createdAt = row.getString("created_at")
                    ))
                }
            }
        }
    }
}
