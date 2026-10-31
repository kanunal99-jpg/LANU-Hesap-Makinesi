package com.example.core.messaging

import com.example.core.diagnostics.PrivateRelayHealth
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RelayException(val status: Int, message: String, val retryAfterMillis: Long = 60000L) : IOException(message)

/** Bounds decompressed bytes on Android too; the server's own cap is not a client guarantee. */
object RelayResponseReader {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun read(stream: InputStream, declaredBytes: Long = -1L, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes in 1..MAX_BYTES)
        if (declaredBytes > maxBytes) throw RelayException(503, "Sunucu yanıtı güvenli boyut sınırını aştı.")
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var stalled = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (n == 0) {
                if (++stalled >= 3) throw RelayException(503, "Sunucu yanıtı tamamlanamadı.")
                continue
            }
            stalled = 0
            if (out.size().toLong() + n > maxBytes) throw RelayException(503, "Sunucu yanıtı güvenli boyut sınırını aştı.")
            out.write(buffer, 0, n)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (_: Exception) { throw RelayException(503, "Sunucu yanıtı okunamadı.") }
    }
}
/**
 * HTTP status must be validated before reading/parsing a possibly empty, HTML
 * or oversized error response. Otherwise 401/403/429 can be misreported as
 * 503 and a caller may retry an unauthorized operation.
 *
 * Never surface the server's response body or header contents.
 */
internal object RelayHttpResponseGate {
    fun <T> readSuccessBody(code: Int, retryAfterHeader: String?, readBody: () -> T): T {
        if (code !in 200..299) {
            val message = when (code) {
                401 -> "Kimlik doğrulanamadı. Telefonun tarih ve saatini kontrol edin."
                403 -> "Bu işlem için yetki bulunamadı."
                409 -> "Karşılıklı kişi kaydı bekleniyor veya kayıt çakıştı."
                413 -> "Gönderim boyutu izin verilen sınırı aşıyor."
                429 -> "Çok fazla istek. Mesajlar korunarak yeniden denenecek."
                else -> "Bağlantı tamamlanamadı. Mesajlarınız korunuyor."
            }
            val retry = retryAfterHeader?.toLongOrNull()?.coerceIn(5L, 120L)?.times(1000L) ?: 60000L
            throw RelayException(code, message, retry)
        }
        return readBody()
    }
}

interface MessagingRelay {
    val configured: Boolean
    suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject = JSONObject()): JSONObject
}
/**
 * Completion verifies a whole encrypted object on the relay. The Storage
 * verifier has a strict 90s deadline, so the 35s ordinary messaging budget
 * cannot be reused for this one authenticated, user-initiated operation.
 * No user-triggered action can ask for an arbitrary/unbounded timeout.
 */
internal object RelayRequestDeadlinePolicy {
    fun callTimeoutSeconds(action: String): Long = if (action == "mediaComplete") 110L else 35L
    fun readTimeoutSeconds(action: String): Long = if (action == "mediaComplete") 100L else 20L
}

class HttpMessagingRelay(private val endpoint: String) : MessagingRelay {
    override val configured: Boolean get() = endpoint.startsWith("https://")
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val verifiedMediaClient = client.newBuilder()
        .readTimeout(RelayRequestDeadlinePolicy.readTimeoutSeconds("mediaComplete"), TimeUnit.SECONDS)
        .callTimeout(RelayRequestDeadlinePolicy.callTimeoutSeconds("mediaComplete"), TimeUnit.SECONDS)
        .build()
    override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (!configured) throw RelayException(0, "Mesajlaşma sunucusu henüz bağlanmadı. Mesaj gönderilmedi.")
        val payload = JSONObject().put("action", action).put("identityId", identity.id).put("ts", System.currentTimeMillis())
            .put("requestId", UUID.randomUUID().toString()).put("data", data).toString()
        val envelope = JSONObject().put("payload", payload).put("signature", UserCodes.sign(identity, payload))
        val request = Request.Builder().url(endpoint).post(envelope.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            // Only whole-object SHA256 confirmation may outlive the normal
            // message/receipt budget. This is still cancellable and bounded.
            val call = (if (action == "mediaComplete") verifiedMediaClient else client).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        // Record only the fixed network-failure category.
                        // Never retain e.message (URLs, headers or device data).
                        PrivateRelayHealth.record(PrivateRelayHealth.Outcome.NETWORK)
                        continuation.resumeWithException(e)
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                }
            })
        }
        response.use {
            // Classify HTTP errors before consuming any untrusted error body.
            // This preserves 401/403/409/429 even when a gateway returns HTML.
            if (!response.isSuccessful) PrivateRelayHealth.recordHttp(response.code)
            val raw = try {
                RelayHttpResponseGate.readSuccessBody(response.code, response.header("Retry-After")) {
                    response.body?.let { body ->
                        body.byteStream().use { RelayResponseReader.read(it, body.contentLength()) }
                    } ?: "{}"
                }
            } catch (e: Exception) {
                if (response.isSuccessful) PrivateRelayHealth.record(PrivateRelayHealth.Outcome.RESPONSE)
                throw e
            }
            val value = try { JSONObject(raw) } catch (_: Exception) {
                PrivateRelayHealth.record(PrivateRelayHealth.Outcome.RESPONSE)
                throw RelayException(503, "Sunucu yanıtı okunamadı.")
            }
            if (value.has("error")) {
                PrivateRelayHealth.recordHttp(503)
                // Retain the existing non-retry behavior for 2xx protocol error
                // envelopes until the server's error contract is reviewed.
                throw RelayException(response.code, "Bağlantı tamamlanamadı. Mesajlarınız korunuyor.")
            }
            PrivateRelayHealth.record(PrivateRelayHealth.Outcome.SUCCESS)
            value
        }
    }
}
