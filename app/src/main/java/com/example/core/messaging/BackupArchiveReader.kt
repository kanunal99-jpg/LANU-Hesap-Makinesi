package com.example.core.messaging

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** Cancellation owns the provider descriptor until all bounded archive bytes are read. */
internal object BackupArchiveReader {
    suspend fun read(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        coroutineScope {
            val signal = CancellationSignal()
            val current = AtomicReference<AssetFileDescriptor?>()
            fun close() { try { current.getAndSet(null)?.close() } catch (_: Exception) { } }
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally {
                    try { signal.cancel() } catch (_: RuntimeException) { }
                    finally { close() }
                }
            }
            try {
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: error("Yedek okunamadı.")
                current.set(descriptor)
                currentCoroutineContext().ensureActive()
                require(descriptor.declaredLength in -1L..(FullDataBackup.MAX_BYTES + 56L)) {
                    "Yedek dosyası güvenli boyut sınırını aşıyor."
                }
                val owner = currentCoroutineContext()
                descriptor.createInputStream().use { FullDataBackup.readFile(it) { owner.ensureActive() } }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally { watcher.cancel(); close() }
        }
    }
}
