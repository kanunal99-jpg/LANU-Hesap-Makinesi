package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Local, encrypted high-quality photo staging; does not upload any bytes. */
object PrivatePhotoStaging {
    private const val MAX_SOURCE = 12 * 1024 * 1024

    suspend fun stage(context: Context, uri: Uri): PrivateMediaCrypto.EncryptedFile =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content") { "Galeri seçicisi gerekiyor." }
            require(context.contentResolver.getType(uri)?.lowercase() in
                setOf("image/jpeg", "image/png")) { "Fotoğraf türü desteklenmiyor." }
            val source = context.contentResolver.openInputStream(uri)?.use {
                InlineMediaPreparer.readBoundedInput(it, MAX_SOURCE)
            } ?: throw IllegalArgumentException("Fotoğraf açılamadı.")
            try { stageSelectedBytes(source, context.cacheDir) }
            finally { source.fill(0) }
        }

    /** Re-encode pixels as JPEG rather than copying original metadata. */
    internal fun stageSelectedBytes(raw: ByteArray, privateDirectory: File): PrivateMediaCrypto.EncryptedFile {
        require(raw.size in 4..MAX_SOURCE) { "Görsel dosyası çok büyük veya geçersiz." }
        val jpeg = raw[0] == 0xff.toByte() && raw[1] == 0xd8.toByte() &&
            raw[2] == 0xff.toByte()
        val png = raw.size >= 8 && raw[0] == 0x89.toByte() &&
            raw[1] == 0x50.toByte() && raw[2] == 0x4e.toByte() &&
            raw[3] == 0x47.toByte() && raw[4] == 0x0d.toByte() &&
            raw[5] == 0x0a.toByte() && raw[6] == 0x1a.toByte() &&
            raw[7] == 0x0a.toByte()
        require(jpeg || png) { "Fotoğrafın JPEG/PNG başlığı geçersiz." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) {
            "Görsel çözünürlüğü sınır dışı."
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400 ||
            bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > 6_000_000) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: throw IllegalArgumentException("Fotoğraf açılamadı.")
        return try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
            val jpg = output.toByteArray()
            try {
                require(jpg.isNotEmpty() && jpg.size.toLong() <= PrivateMediaCrypto.MAX_FILE_BYTES)
                PrivateMediaSourcePreflight.inspect(
                    ByteArrayInputStream(jpg), "image/jpeg", sanitizedImage = true
                ).use { checked ->
                    PrivateMediaCrypto.encryptToPrivateFile(
                        checked, jpg.size.toLong(), "image/jpeg", privateDirectory
                    )
                }
            } finally { jpg.fill(0) }
        } finally { bitmap.recycle() }
    }
}
