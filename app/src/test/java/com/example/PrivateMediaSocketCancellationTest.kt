package com.example

import com.example.core.media.PrivateMediaTransferHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrivateMediaSocketCancellationTest {
    private fun request() = Request.Builder().url("https://media-test.invalid/never-connect").build()

    @Test fun cancelledCoroutineStopsRequestBeforeAnyResponseHeaders() = runBlocking {
        val entered = CountDownLatch(1)
        val socketStopped = CountDownLatch(1)
        val localOnly = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                entered.countDown()
                try {
                    while (!chain.call().isCanceled()) Thread.sleep(10)
                } finally {
                    socketStopped.countDown()
                }
                throw IOException("simulated cancelled network")
            }).build()
        val worker = launch(Dispatchers.IO) {
            PrivateMediaTransferHttp.withCancellableResponse<Unit>(
                request(), { error("No response expected") }, localOnly
            )
        }
        try {
            assertTrue("Local interceptor must run", entered.await(5, TimeUnit.SECONDS))
            worker.cancelAndJoin()
            assertTrue("Socket must be cancelled", socketStopped.await(5, TimeUnit.SECONDS))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun cancelledCoroutineClosesCallEvenAfterResponseHeaders() = runBlocking {
        val reading = CountDownLatch(1)
        val streamStopped = CountDownLatch(1)
        val localOnly = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val call = chain.call()
                val bodyStream = object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        try {
                            while (!call.isCanceled()) Thread.sleep(10)
                        } finally {
                            streamStopped.countDown()
                        }
                        return -1
                    }
                }
                val source: BufferedSource = bodyStream.source().buffer()
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = -1L
                    override fun source(): BufferedSource = source
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build()
            }).build()
        val worker = launch(Dispatchers.IO) {
            PrivateMediaTransferHttp.withCancellableResponse<Unit>(
                request(), { response -> response.body!!.byteStream().read(); Unit }, localOnly
            )
        }
        try {
            assertTrue("Local stream must reach blocking read", reading.await(5, TimeUnit.SECONDS))
            worker.cancelAndJoin()
            assertTrue("Cancellation must stop stream after headers",
                streamStopped.await(5, TimeUnit.SECONDS))
        } finally { worker.cancelAndJoin() }
    }
}
