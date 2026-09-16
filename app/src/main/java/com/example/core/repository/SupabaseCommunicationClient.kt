package com.example.core.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real LANU communication transport for the Willy-Kilo-Takip Supabase project. */
class SupabaseCommunicationClient(
    private val context: Context,
    private val baseUrl: String,
    private val publishableKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    data class Session(val accessToken: String, val userId: String)
    data class RemoteMessage(
        val id: String,
        val conversationId: String,
        val senderId: String,
        val ciphertext: String,
        val createdAt: String
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var cachedSession: Session? = null
    @Volatile private var realtimeSocket: WebSocket? = null
    private val sessionPrefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "lanu_supabase_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun ensureAnonymousSession(): Session = withContext(Dispatchers.IO) {
        cachedSession?.let { return@withContext it }
        val storedToken = sessionPrefs.getString("access_token", null)
        val storedUserId = sessionPrefs.getString("user_id", null)
        if (!storedToken.isNullOrBlank() && !storedUserId.isNullOrBlank()) {
            return@withContext Session(storedToken, storedUserId).also { cachedSession = it }
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/v1/signup")
            .header("apikey", publishableKey)
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        execute(request).let { body ->
            val session = Session(
                accessToken = body.optString("access_token").ifBlank { error("Supabase Auth access token alınamadı") },
                userId = body.optJSONObject("user")?.optString("id")?.ifBlank { null }
                    ?: error("Supabase Auth kullanıcı kimliği alınamadı")
            )
            sessionPrefs.edit().putString("access_token", session.accessToken).putString("user_id", session.userId).apply()
            cachedSession = session
            session
        }
    }

    fun clearSession() {
        closeRealtime()
        cachedSession = null
        sessionPrefs.edit().clear().apply()
    }

    suspend fun upsertProfile(session: Session, phoneNumber: String, displayName: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("id", session.userId).put("phone_hash", sha256(phoneNumber)).put("display_name", displayName).toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_profiles")
            .headers(sessionHeaders(session))
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(payload.toRequestBody(jsonMediaType)).build()
        execute(request)
    }

    suspend fun createConversation(session: Session, partnerPhone: String): String = withContext(Dispatchers.IO) {
        val partnerId = lookupProfileId(session, partnerPhone) ?: error("LANU kullanıcısı bulunamadı")
        val payload = JSONObject().put("participant_a", session.userId).put("participant_b", partnerId).toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_conversations")
            .headers(sessionHeaders(session))
            .header("Prefer", "return=representation")
            .post(payload.toRequestBody(jsonMediaType)).build()
        executeArray(request).optJSONObject(0)?.optString("id")?.ifBlank { null } ?: error("Konuşma oluşturulamadı")
    }

    suspend fun sendMessage(session: Session, conversationId: String, messageId: String, ciphertext: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("id", messageId).put("conversation_id", conversationId).put("sender_id", session.userId).put("ciphertext", ciphertext).toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/lanu_messages")
            .headers(sessionHeaders(session))
            .header("Prefer", "return=minimal")
            .post(payload.toRequestBody(jsonMediaType)).build()
        execute(request)
        messageId
    }

    suspend fun listMessages(session: Session, conversationId: String): List<RemoteMessage> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/lanu_messages?conversation_id=eq.${java.net.URLEncoder.encode(conversationId, "UTF-8")}&order=created_at.asc"
        val request = Request.Builder().url(url).headers(sessionHeaders(session)).get().build()
        val rows = executeArray(request)
        buildList { for (i in 0 until rows.length()) add(remoteMessageFromRow(rows.getJSONObject(i))) }
    }

    /** Opens a real Supabase Realtime Postgres Changes socket for lanu_messages. */
    suspend fun subscribeToMessages(session: Session, onMessage: (RemoteMessage) -> Unit, onConnectionState: (Boolean) -> Unit = {}) = withContext(Dispatchers.IO) {
        realtimeSocket?.cancel()
        val wsUrl = baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=${java.net.URLEncoder.encode(publishableKey, "UTF-8")}&vsn=1.0.0"
        val join = JSONObject()
            .put("topic", "realtime:public:lanu_messages")
            .put("event", "phx_join")
            .put("payload", JSONObject()
                .put("config", JSONObject()
                    .put("broadcast", JSONObject().put("ack", false))
                    .put("presence", JSONObject().put("key", ""))
                    .put("postgres_changes", JSONArray().put(JSONObject().put("event", "INSERT").put("schema", "public").put("table", "lanu_messages"))))
                .put("access_token", session.accessToken))
            .put("ref", UUID.randomUUID().toString())
        realtimeSocket = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { onConnectionState(true); webSocket.send(join.toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    if (root.optString("event") != "postgres_changes") return
                    val record = root.optJSONObject("payload")?.optJSONObject("data")?.optJSONObject("record") ?: return
                    onMessage(remoteMessageFromRow(record))
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { onConnectionState(false) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onConnectionState(false) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { onConnectionState(false) }
        })
    }

    fun closeRealtime() {
        realtimeSocket?.close(1000, "LANU iletişim kapatıldı")
        realtimeSocket = null
    }

    private suspend fun lookupProfileId(session: Session, phoneNumber: String): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("p_phone_hash", sha256(phoneNumber)).toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/rpc/lanu_find_profile_by_phone_hash")
            .headers(sessionHeaders(session))
            .post(payload.toRequestBody(jsonMediaType)).build()
        executeArray(request).optJSONObject(0)?.optString("id")?.ifBlank { null }
    }

    private fun sessionHeaders(session: Session) = Headers.Builder()
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

    private fun remoteMessageFromRow(row: JSONObject): RemoteMessage = RemoteMessage(
        id = row.getString("id"),
        conversationId = row.getString("conversation_id"),
        senderId = row.getString("sender_id"),
        ciphertext = row.getString("ciphertext"),
        createdAt = row.getString("created_at")
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
