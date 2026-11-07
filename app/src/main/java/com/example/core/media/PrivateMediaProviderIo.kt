package com.example.core.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** A provider descriptor and its cancellation signal stay owned through the last byte. */
internal object PrivateMediaProviderIo {
    private const val DEADLINE_MILLIS = 120_000L

    private suspend fun <T> descriptor(
        context: Context, uri: Uri, mode: String, block: suspend (AssetFileDescriptor) -> T
    ): T = withTimeout(DEADLINE_MILLIS) {
        require(uri.scheme == "content") { "Sistem dosya seçicisi gerekiyor." }
        withContext(Dispatchers.IO) {
            coroutineScope {
                val signal = CancellationSignal()
                val opened = AtomicReference<AssetFileDescriptor?>()
                fun close() { try { opened.getAndSet(null)?.close() } catch (_: Exception) { } }
                val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { try { signal.cancel() } catch (_: RuntimeException) { } finally { close() } }
                }
                try {
                    val fd = context.contentResolver.openAssetFileDescriptor(uri, mode, signal)
                        ?: error("Dosya sağlayıcısı açılamadı.")
                    opened.set(fd)
                    currentCoroutineContext().ensureActive()
                    block(fd)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally { watcher.cancel(); close() }
            }
        }
    }

    suspend fun copyIntoPrivateFile(context: Context, uri: Uri, target: File, limit: Long): Long =
        descriptor(context, uri, "r") { fd ->
            require(fd.declaredLength in -1L..limit) { "Dosya güvenli boyut sınırını aşıyor." }
            val owner = currentCoroutineContext()
            fd.createInputStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    copyBounded(input, output, limit) { owner.ensureActive() }.also { output.fd.sync() }
                }
            }
        }

    suspend fun readPhoto(context: Context, uri: Uri, limit: Int): ByteArray =
        descriptor(context, uri, "r") { fd ->
            require(fd.declaredLength in -1L..limit.toLong()) { "Fotoğraf çok büyük." }
            val owner = currentCoroutineContext()
            fd.createInputStream().use { input ->
                java.io.ByteArrayOutputStream().use { output ->
                    copyBounded(input, output, limit.toLong()) { owner.ensureActive() }
                    output.toByteArray()
                }
            }
        }

    suspend fun exportVerifiedFile(context: Context, uri: Uri, verified: File) =
        descriptor(context, uri, "w") { fd ->
            val expected = verified.length()
            require(verified.isFile && expected in 1..PrivateMediaTransferBudget.MAX_PLAIN_BYTES)
            val owner = currentCoroutineContext()
            fd.createOutputStream().use { output ->
                verified.inputStream().use { input ->
                    check(copyBounded(input, output, expected) { owner.ensureActive() } == expected)
                    output.flush()
                }
            }
        }

    internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long, checkActive: () -> Unit): Long {
        require(limit > 0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var stalled = 0
        try {
            while (true) {
                checkActive()
                val count = input.read(buffer)
                checkActive()
                if (count < 0) break
                if (count == 0) {
                    check(++stalled < 3) { "Dosya sağlayıcısında ilerleme yok." }
                    continue
                }
                stalled = 0
                require(count.toLong() <= limit - total) { "Dosya güvenli boyut sınırını aşıyor." }
                output.write(buffer, 0, count)
                total += count
            }
            checkActive()
            return total
        } finally { buffer.fill(0) }
    }
}
