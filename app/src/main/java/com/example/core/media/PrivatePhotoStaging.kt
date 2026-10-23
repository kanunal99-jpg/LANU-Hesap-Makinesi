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

/**
 * Encrypts a higher-quality Photo Picker selection inside the app cache.
 * Pixels are re-encoded rather than copying EXIF/GPS metadata.
 * Does not upload or send files; returned key must later be destroyed.
 */
object PrivatePhotoStaging {
    private const val MAX_SOURCE = 12 * 1024 * 1024

    suspend fun stage(context: Context, uri: Uri): PrivateMediaCrypto.EncryptedFile =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content") { "Güvenli galeri seçicisi gerekli." }
            require(context.contentResolver.getType(uri)?.lowercase() in
                setOf("image/jpeg", "image/png")) { "Fotoğraf türü geçersiz." }
            val original = context.contentResolver.openInputStream(uri)?.use {
                InlineMediaPreparer.readBoundedInput(it, MAX_SOURCE)
            } ?: throw IllegalArgumentException("Fotoğraf açılamadı.")
            try {
                stageSelectedBytes(original, context.cacheDir)
            } finally {
                original.fill(0)
            }
        }

    /**
     * Input is a bounded image snapshot. This helper enables offline tests of
     * EXIF-scrubbing and encrypted output, without a real document provider.
     */
    internal fun stageSelectedBytes(original: ByteArray, privateDirectory: File):
        PrivateMediaCrypto.EncryptedFile {
        require(original.size in 1..MAX_SOURCE) { "Görsel kaynağı çok büyük." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) {
            "Görsel güvenli piksel sınırını aşıyor."
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400 ||
            bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > 6_000_000) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: throw IllegalArgumentException("Fotoğraf okunamadı.")
        try {
            val buffer = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, buffer)) {
                "Fotoğraf JPEG'e dönüştürülemedi."
            }
            val bytes = buffer.toByteArray()
            try {
                require(bytes.isNotEmpty() && bytes.size.toLong() <=
                    PrivateMediaCrypto.MAX_FILE_BYTES) { "Dosya sınırı aşıldı." }
                PrivateMediaSourcePreflight.inspect(
                    ByteArrayInputStream(bytes), "image/jpeg", sanitizedImage = true
                ).use {
                    PrivateMediaCrypto.encryptToPrivateFile(
                        it, bytes.size.toLong(), "image/jpeg", privateDirectory
                    )
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
