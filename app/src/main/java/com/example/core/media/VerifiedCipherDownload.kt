package com.example.core.media

import com.example.core.network.CancellableHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** Bounded Range recovery for an immutable, hash-bound encrypted object. */
internal object VerifiedCipherDownload {
    suspend fun download(
        url: String, expectedBytes: Long, expectedSha256: String,
        directory: File, client: OkHttpClient
    ): File = withContext(Dispatchers.IO) {
        require(expectedBytes in 17L..PrivateMediaTransferBudget.MAX_RESERVED_CIPHER_BYTES)
        require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
        require(directory.isDirectory && directory.absoluteFile == directory.canonicalFile)
        val destination = File.createTempFile("lanu-media-v1-", ".cipher", directory)
        try {
            // A retry cannot extend the five-minute user operation indefinitely.
            withTimeout(300_000L) {
                var complete = false
                for (attempt in 0..2) {
                    currentCoroutineContext().ensureActive()
                    val offset = destination.length()
                    require(offset in 0L..expectedBytes)
                    // A read may fail after all bytes arrived. Validate them
                    // locally rather than issuing an invalid Range at EOF.
                    if (offset == expectedBytes) { complete = true; break }
                    val request = Request.Builder().url(url)
                        .header("Accept-Encoding", "identity")
                        .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                        .get().build()
                    val networkFailure = AtomicBoolean(false)
                    try {
                        val transferContext = currentCoroutineContext()
                        CancellableHttp.withResponse(request, client, onNetworkFailure = { networkFailure.set(true) }) { response ->
                            require(response.code == 200 || (offset > 0 && response.code == 206)) {
                                "Güvenli medya indirilemedi."
                            }
                            require(response.header("Content-Encoding").orEmpty().lowercase() in setOf("", "identity"))
                            require(response.header("Content-Type")?.substringBefore(';')?.trim()
                                ?.equals("application/octet-stream", ignoreCase = true) == true)
                            val append = response.code == 206
                            if (append) require(response.header("Content-Range") ==
                                "bytes $offset-${expectedBytes - 1}/$expectedBytes") {
                                "Dosya devam konumu doğrulanamadı."
                            } else require(response.header("Content-Range") == null)
                            val start = if (append) offset else 0L
                            val body = requireNotNull(response.body)
                            require(body.contentLength() == -1L || body.contentLength() == expectedBytes - start)
                            FileOutputStream(destination, append).use { output ->
                                val input = body.byteStream()
                                val buffer = ByteArray(64 * 1024)
                                var total = start
                                var stalls = 0
                                while (true) {
                                    transferContext.ensureActive()
                                    val n = try { input.read(buffer) }
                                    catch (e: IOException) { networkFailure.set(true); throw e }
                                    if (n < 0) break
                                    if (n == 0) {
                                        require(++stalls < 3) { "Dosya aktarımı durdu." }
                                        continue
                                    }
                                    stalls = 0
                                    require(n.toLong() <= expectedBytes - total) { "Şifreli dosya sınırı aşıldı." }
                                    output.write(buffer, 0, n)
                                    total += n
                                }
                                if (total != expectedBytes) {
                                    networkFailure.set(true)
                                    throw IOException("Şifreli dosya eksik alındı.")
                                }
                                output.fd.sync()
                            }
                        }
                        complete = true
                        break
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        // Trust/protocol/interruption errors are never retried.
                        if (!networkFailure.get() || attempt == 2 || e is SSLException || e is ProtocolException ||
                            (e is InterruptedIOException && e !is java.net.SocketTimeoutException)) throw e
                        delay(250L * (attempt + 1))
                    }
                }
                check(complete)
                val actual = FileInputStream(destination).use {
                    PrivateMediaTransferBudget.digestCipherStream(it, expectedBytes)
                }
                val expected = expectedSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                require(MessageDigest.isEqual(actual, expected)) { "Şifreli dosya bütünlüğü doğrulanamadı." }
            }
            destination
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }
}
