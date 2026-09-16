package com.example.core.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Real LANU communication transport for the Willy-Kilo-Takip Supabase project. */
class SupabaseCommunicationClient(
    private val baseUrl: String,
    private val publishableKey: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    data class Session(val accessToken: String, val userId: String)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun ensureAnonymousSession(): Session = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/v1/signup")
            .header("apikey", publishableKey)
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        execute(request).let { body ->
            Session(
                accessToken = body.optString("access_token").ifBlank {
                    error("Supabase Auth access token alınamadı")
                },
                userId = body.optJSONObject("user")?.optString("id")?.ifBlank { null }
                    ?: error("Supabase Auth kullanıcı kimliği alınamadı")
            )
        }
    }

    suspend fun upsertProfile(session: Session, phoneNumber: String, displayName: String) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("id", session.userId)
                .put("phone_hash", sha256(phoneNumber))
                .put("display_name", displayName)
                .toString()
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_profiles")
                .headers(sessionHeaders(session))
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            execute(request)
        }

    suspend fun createConversation(session: Session, partnerPhone: String): String =
        withContext(Dispatchers.IO) {
            val partnerId = lookupProfileId(session, partnerPhone)
                ?: error("LANU kullanıcısı bulunamadı")
            val payload = JSONObject()
                .put("participant_a", session.userId)
                .put("participant_b", partnerId)
                .toString()
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_conversations")
                .headers(sessionHeaders(session))
                .header("Prefer", "return=representation")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            executeArray(request).optJSONObject(0)?.optString("id")?.ifBlank { null }
                ?: error("Konuşma oluşturulamadı")
        }

    suspend fun sendMessage(session: Session, conversationId: String, ciphertext: String): String =
        withContext(Dispatchers.IO) {
            val messageId = UUID.randomUUID().toString()
            val payload = JSONObject()
                .put("id", messageId)
                .put("conversation_id", conversationId)
                .put("sender_id", session.userId)
                .put("ciphertext", ciphertext)
                .toString()
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_messages")
                .headers(sessionHeaders(session))
                .header("Prefer", "return=minimal")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            execute(request)
            messageId
        }

    suspend fun listMessages(session: Session, conversationId: String): List<RemoteMessage> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/rest/v1/lanu_messages" +
                "?conversation_id=eq.${java.net.URLEncoder.encode(conversationId, "UTF-8")}" +
                "&order=created_at.asc"
            val request = Request.Builder()
                .url(url)
                .headers(sessionHeaders(session))
                .get()
                .build()
            val rows = executeArray(request)
            buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    add(
                        RemoteMessage(
                            id = row.getString("id"),
                            conversationId = row.getString("conversation_id"),
                            senderId = row.getString("sender_id"),
                            ciphertext = row.getString("ciphertext"),
                            createdAt = row.getString("created_at")
                        )
                    )
                }
            }
        }

    data class RemoteMessage(
        val id: String,
        val conversationId: String,
        val senderId: String,
        val ciphertext: String,
        val createdAt: String
    )

    private suspend fun lookupProfileId(session: Session, phoneNumber: String): String? =
        withContext(Dispatchers.IO) {
            val hash = sha256(phoneNumber)
            val encoded = java.net.URLEncoder.encode(hash, "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_profiles?phone_hash=eq.$encoded&select=id")
                .headers(sessionHeaders(session))
                .get()
                .build()
            executeArray(request).optJSONObject(0)?.optString("id")?.ifBlank { null }
        }

    private fun sessionHeaders(session: Session) = okhttp3.Headers.Builder()
        .add("apikey", publishableKey)
        .add("Authorization", "Bearer ${session.accessToken}")
        .add("Content-Type", "application/json")
        .build()

    private fun execute(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Supabase HTTP ${response.code}: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun executeArray(request: Request): JSONArray {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Supabase HTTP ${response.code}: $text")
            return JSONArray(text)
        }
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
