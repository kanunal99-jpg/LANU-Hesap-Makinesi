package com.example.core.media

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android Photo Picker / system document picker input only. Does not request
 * broad storage access or retain URI permissions. JPEG recompression strips
 * location EXIF metadata from selected pictures before encryption.
 */
object InlineMediaPreparer {
    private const val MAX_SOURCE_IMAGE_BYTES = 12 * 1024 * 1024

    suspend fun imageFromPicker(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        // File size from document providers can be absent or forged. Always
        // enforce an actual streaming read ceiling before JPEG/PNG decoding.
        val source = context.contentResolver.openInputStream(uri)?.use {
            readBoundedInput(it, MAX_SOURCE_IMAGE_BYTES)
        } ?: throw IllegalArgumentException("Fotoğraf açılamadı.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth in 1..12_000 && bounds.outHeight in 1..12_000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) {
            "Fotoğraf boyutları güvenli sınırı aşıyor."
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 900) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: throw IllegalArgumentException("Fotoğraf çözümlenemedi.")
        try {
            InlineMediaCodec.encode("foto.jpg", "image/jpeg", compactJpeg(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    /** External camera's small preview. No camera/storage permission is required. */
    suspend fun cameraPreview(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        InlineMediaCodec.encode("kamera.jpg", "image/jpeg", compactJpeg(bitmap))
    }

    suspend fun documentFromPicker(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = when (context.contentResolver.getType(uri)?.lowercase()) {
            "application/pdf" -> "application/pdf"
            "text/plain" -> "text/plain"
            else -> throw IllegalArgumentException("Şu anda yalnız PDF ve TXT belgeleri destekleniyor.")
        }
        val name = displayName(context, uri) ?: if (mime == "application/pdf") "belge.pdf" else "belge.txt"
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            readBoundedInput(it, InlineMediaCodec.MAX_FILE_BYTES)
        } ?: throw IllegalArgumentException("Belge açılamadı.")
        InlineMediaCodec.encode(name, mime, bytes)
    }

    internal fun readBoundedInput(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes in 1..MAX_SOURCE_IMAGE_BYTES)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.size().toLong() + count <= maxBytes.toLong()) {
                "Dosya izin verilen boyutu aşıyor."
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) {
                        cursor.getString(column)?.take(256)
                    } else null
                }
        }.getOrNull()

    internal fun compactJpeg(original: Bitmap): ByteArray {
        require(original.width > 0 && original.height > 0) { "Fotoğraf geçersiz." }
        for (limit in listOf(640, 512, 448, 384, 320, 256, 192)) {
            val ratio = minOf(1.0, limit.toDouble() / max(original.width, original.height))
            val width = (original.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (original.height * ratio).roundToInt().coerceAtLeast(1)
            val resized = if (width == original.width && height == original.height) original
                else Bitmap.createScaledBitmap(original, width, height, true)
            try {
                for (quality in listOf(80, 65, 50, 35, 20)) {
                    val out = ByteArrayOutputStream()
                    check(resized.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                        "Fotoğraf sıkıştırılamadı."
                    }
                    if (out.size() in 1..InlineMediaCodec.MAX_FILE_BYTES) return out.toByteArray()
                }
            } finally {
                if (resized !== original) resized.recycle()
            }
        }
        throw IllegalArgumentException("Fotoğraf 18 KB sınırına indirgenemedi; daha küçük bir görsel seçin.")
    }
}
