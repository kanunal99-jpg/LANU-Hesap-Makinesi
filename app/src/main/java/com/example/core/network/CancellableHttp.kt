package com.example.core.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Owns the socket through header wait AND the complete streaming response. */
internal object CancellableHttp {
    suspend fun <T> withResponse(
        request: Request,
        client: OkHttpClient,
        onNetworkFailure: () -> Unit = {},
        process: suspend (Response) -> T
    ): T = coroutineScope {
        val call = client.newCall(request)
        val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { call.cancel() }
        }
        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            onNetworkFailure()
                            continuation.resumeWithException(error)
                        }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                        } else response.close()
                    }
                })
            }
            response.use { process(it) }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally { cancelWatcher.cancel() }
    }
}
