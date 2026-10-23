package com.example.core.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** ContentResolver always reads user-selected content URIs, never full-library permissions. */
object SecureMediaPicker {
    data class ClearFile(val name: String, val mime: String, val bytes: ByteArray)
    private const val MAX_PHOTO_DIMENSION = 1600

    fun read(context: Context, uri: Uri, photo: Boolean): ClearFile {
        require(uri.scheme == "content") { "Yalnız Android dosya seçici destekleniyor." }
        val name = safeName(displayName(context, uri))
        return if (photo) photo(context, uri) else document(context, uri, name)
    }

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "dosya"
    }

    private fun safeName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it != '/' && it != '\\' }
            .trim().take(85)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "dosya" else cleaned
    }

    private fun photo(context: Context, uri: Uri): ClearFile {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) {
            "Fotoğraf boyutu geçersiz veya dosya çözümlenemiyor."
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_PHOTO_DIMENSION ||
            bounds.outHeight / sample > MAX_PHOTO_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Seçilen fotoğraf okunamadı.")
        return try {
            val out = ByteArrayOutputStream()
            var quality = 85
            do {
                out.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Fotoğraf işlenemedi." }
                quality -= 10
            } while (out.size() > SecureMediaAttachment.MAX_CLEAR_BYTES && quality >= 45)
            require(out.size() in 1..SecureMediaAttachment.MAX_CLEAR_BYTES) {
                "Fotoğraf gönderim sınırına sığmadı. Daha küçük fotoğraf seçin."
            }
            // Re-encoding strips EXIF GPS and camera metadata.
            ClearFile("LANU-fotograf.jpg", "image/jpeg", out.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private fun document(context: Context, uri: Uri, name: String): ClearFile {
        val reportedMime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        require(reportedMime == "application/pdf" || reportedMime == "text/plain") {
            "Şimdilik yalnız PDF ve düz metin belgeleri destekleniyor."
        }
        val output = ByteArrayOutputStream()
        val source = context.contentResolver.openInputStream(uri) ?: error("Dosya açılamadı.")
        source.use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= SecureMediaAttachment.MAX_CLEAR_BYTES) {
                    "Belge şimdilik 2 MB sınırını aşamaz."
                }
                output.write(chunk, 0, count)
            }
        }
        val data = output.toByteArray()
        require(data.isNotEmpty()) { "Boş dosya gönderilemez." }
        if (reportedMime == "application/pdf") {
            require(data.size >= 5 && String(data.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-") {
                "Dosya PDF biçimiyle uyuşmuyor."
            }
        } else {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
        }
        return ClearFile(name, reportedMime, data)
    }
}
