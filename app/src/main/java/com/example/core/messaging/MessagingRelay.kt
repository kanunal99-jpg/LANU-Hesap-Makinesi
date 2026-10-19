package com.example.core.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class RelayException(val status: Int, message: String) : IOException(message)
interface MessagingRelay {
    val configured: Boolean
    suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject = JSONObject()): JSONObject
}
class HttpMessagingRelay(private val endpoint: String) : MessagingRelay {
    override val configured: Boolean get() = endpoint.startsWith("https://")
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (!configured) throw RelayException(0, "Mesajlaşma sunucusu henüz bağlanmadı. Mesaj gönderilmedi.")
        val payload = JSONObject().put("action", action).put("identityId", identity.id).put("ts", System.currentTimeMillis())
            .put("requestId", UUID.randomUUID().toString()).put("data", data).toString()
        val envelope = JSONObject().put("payload", payload).put("signature", UserCodes.sign(identity, payload))
        val request = Request.Builder().url(endpoint).post(envelope.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val value = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful || value.has("error")) throw RelayException(response.code, value.optString("error", "Bağlantı tamamlanamadı. Mesaj gönderilmedi."))
            value
        }
    }
}
