package com.example.core.messaging

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * HTTPS-only binary transport for user-selected, pre-encrypted attachments.
 * Signed URLs are accepted exclusively from the configured LANU project
 * and a single expected object path. No service role credential is shipped.
 */
class SecureMediaTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .build()
) {
    private val origin = URI(BuildConfig.LANU_RELAY_URL)
    private val bucket = "lanu-private-media"

    private fun verifiedUrl(url: String, mode: String, senderId: String, messageId: String): String {
        require(senderId.matches(Regex("[a-f0-9]{64}")) &&
            messageId.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) {
            "Geçersiz medya adres kimliği."
        }
        val uri = URI(url)
        val prefix = if (mode == "upload") "upload/sign" else "sign"
        val expected = "/storage/v1/object/$prefix/$bucket/$senderId/$messageId.bin"
        require(uri.scheme == "https" && uri.host == origin.host && uri.port == -1 &&
            uri.userInfo == null && uri.fragment == null &&
            uri.rawPath == expected &&
            uri.rawQuery?.matches(Regex("token=[A-Za-z0-9_.-]{20,3000}")) == true) {
            "Resmî olmayan medya aktarım adresi reddedildi."
        }
        return uri.toASCIIString()
    }

    suspend fun upload(signedUrl: String, senderId: String, messageId: String, ciphertext: ByteArray) =
        withContext(Dispatchers.IO) {
            require(ciphertext.size in 29..SecureMediaAttachment.MAX_CIPHER_BYTES) { "Dosya çok büyük." }
            val url = verifiedUrl(signedUrl, "upload", senderId, messageId)
            val request = Request.Builder().url(url)
                .header("Cache-Control", "no-store")
                .put(ciphertext.toRequestBody("application/octet-stream".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Güvenli medya yüklemesi başarısız oldu (HTTP ${response.code})." }
            }
        }

    suspend fun download(signedUrl: String, senderId: String, messageId: String, expectedBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            require(expectedBytes in 29..SecureMediaAttachment.MAX_CIPHER_BYTES) { "İndirilecek dosya boyutu geçersiz." }
            val url = verifiedUrl(signedUrl, "download", senderId, messageId)
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store").get().build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Güvenli medya indirilemedi (HTTP ${response.code})." }
                require(response.header("Content-Encoding").let { it == null || it == "identity" }) {
                    "Beklenmeyen dosya sıkıştırması."
                }
                val body = response.body ?: error("Medya yanıtı boş.")
                require(body.contentLength() in -1L..SecureMediaAttachment.MAX_CIPHER_BYTES.toLong()) {
                    "Medya yanıtı boyut sınırını aştı."
                }
                val output = ByteArrayOutputStream(expectedBytes)
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= expectedBytes) { "Medya indirimi ilan edilen boyutu aştı." }
                        output.write(buffer, 0, n)
                    }
                }
                require(output.size() == expectedBytes) { "Medya aktarımı eksik tamamlandı." }
                output.toByteArray()
            }
        }
}
