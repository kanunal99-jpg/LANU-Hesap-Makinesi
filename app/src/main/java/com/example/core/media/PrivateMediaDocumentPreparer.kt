package com.example.core.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Android SAF -> bounded app-private staging -> E2EE ciphertext.
 * The original provider URI and filename never reach the relay or Room.
 * EXIF-bearing original photos/videos are intentionally excluded from this
 * document picker until their metadata sanitization/consent UI is complete.
 */
internal object PrivateMediaDocumentPreparer {
    private val permitted = setOf(
        "application/pdf", "text/plain",
        PrivateOfficeDocumentPreflight.DOCX_MIME,
        PrivateOfficeDocumentPreflight.XLSX_MIME,
        PrivateOfficeDocumentPreflight.PPTX_MIME
    )
    fun privateDirectory(context: Context): File {
        val directory = File(context.cacheDir, "lanu-private-large-media")
        check((directory.isDirectory || directory.mkdirs()) &&
            directory.absoluteFile == directory.canonicalFile &&
            directory.canonicalFile.parentFile == context.cacheDir.canonicalFile) {
            "Uygulamanın özel medya alanı açılamadı."
        }
        return directory
    }

    suspend fun encryptDocument(context: Context, uri: Uri): PrivateMediaCrypto.EncryptedFile =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri)?.lowercase()
                ?: throw IllegalArgumentException("Dosya türü okunamadı.")
            require(mime in permitted) { "Yalnız PDF, TXT, DOCX, XLSX ve PPTX paylaşılabilir." }
            val dir = privateDirectory(context)
            val stage = File.createTempFile("lanu-staged-", ".private", dir)
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(stage).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var length = 0L
                        var stalled = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) {
                                if (++stalled >= 3) throw IOException("Dosya sağlayıcısı yanıt vermedi.")
                                continue
                            }
                            stalled = 0
                            length += count
                            require(length <= PrivateMediaTransferBudget.MAX_PLAIN_BYTES) {
                                "Dosya 40 MB sınırını aşıyor."
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        length
                    }
                } ?: throw IOException("Dosya okunamadı.")
                require(bytes > 0 && stage.length() == bytes) { "Dosya eksik okundu." }
                if (mime == "application/pdf") {
                    PrivateMediaSourcePreflight.inspect(
                        FileInputStream(stage), mime
                    ).use { /* Validate PDF bytes and release provider stream. */ }
                }
                if (PrivateOfficeDocumentPreflight.isOfficeMime(mime)) {
                    PrivateMediaCrypto.encryptOfficeDocumentToPrivateFile(stage, mime, dir)
                } else {
                    FileInputStream(stage).use {
                        PrivateMediaCrypto.encryptToPrivateFile(it, bytes, mime, dir)
                    }
                }
            } finally {
                // Never retain a plaintext document in app cache after encryption.
                stage.delete()
            }
        }
}
