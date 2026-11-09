package com.example.core.messaging

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opaque wake hints only. Never carry SDP, messages, credentials or identities in URLs. */
class RealtimeWake(private val endpoint: String, private val admit: suspend (MessagingIdentity) -> Unit = {}) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    fun events(identity: MessagingIdentity): Flow<String> = callbackFlow {
        require(endpoint.startsWith("https://"))
        admit(identity)
        val ready = CompletableDeferred<Unit>()
        val payload = JSONObject().put("action", "realtimeOpen").put("identityId", identity.id)
            .put("ts", System.currentTimeMillis()).put("requestId", UUID.randomUUID().toString())
            .put("data", JSONObject()).toString()
        val envelope = JSONObject().put("payload", payload).put("signature", UserCodes.sign(identity, payload)).toString()
        val socket = client.newWebSocket(Request.Builder().url(endpoint)
            .header("X-Lanu-Authorization", envelope).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 128) { webSocket.cancel(); close(); return }
                val value = runCatching { JSONObject(text) }.getOrNull() ?: return
                when {
                    value.opt("ready") == true -> { ready.complete(Unit); trySend("ready") }
                    value.opt("changed") == "message" -> trySend("message")
                    value.opt("changed") == "call" -> trySend("call")
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel(); close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null) com.example.core.diagnostics.PrivateRelayHealth.recordHttp(response.code)
                else com.example.core.diagnostics.PrivateRelayHealth.record(com.example.core.diagnostics.PrivateRelayHealth.Outcome.NETWORK)
                response?.close(); close()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        val readyTimeout = launch {
            delay(WakeReconnectPolicy.READY_TIMEOUT_MS)
            if (!ready.isCompleted) { socket.cancel(); close() }
        }
        awaitClose { readyTimeout.cancel(); socket.cancel() }
    }
}
