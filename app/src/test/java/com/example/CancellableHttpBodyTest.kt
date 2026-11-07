package com.example

import com.example.core.network.CancellableHttp
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CancellableHttpBodyTest {
    @Test fun cancellationClosesSocketAfterHeadersWhileBodyReadIsBlocked() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val release = CountDownLatch(1)
        val processing = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val worker = thread(isDaemon = true) {
            try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray()); flush()
                }
                release.await(10, TimeUnit.SECONDS)
            } } catch (_: java.io.IOException) { }
        }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.incrementAndGet() }
        }).readTimeout(30, TimeUnit.SECONDS).build()
        val job = launch(Dispatchers.IO) {
            CancellableHttp.withResponse(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(), client) {
                processing.countDown()
                it.body!!.byteStream().read()
            }
        }
        try {
            assertTrue(processing.await(5, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(cancelled.get() > 0)
        } finally {
            job.cancel(); release.countDown(); server.close(); worker.join(1000)
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
