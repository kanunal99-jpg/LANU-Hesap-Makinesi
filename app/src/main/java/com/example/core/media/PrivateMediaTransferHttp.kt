package com.example.core.media

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Strictly bounded HTTPS transfer of CIPHERTEXT only. The authenticated relay
 * mints short-lived, private, owner-scoped signed URLs; never persist URLs or
 * upload keys in Room, logs, analytics or external storage.
 *
 * The relay can be available while media remains gated. This client must never
 * bypass that gate by asking Storage with a public/anon API key.
 */
internal object PrivateMediaTransferHttp {
    private const val BUCKET = "lanu-private-media-v1"
    private const val MAX_CIPHER_BYTES = PrivateMediaTransferBudget.MAX_RESERVED_CIPHER_BYTES
    private val fileId = Regex("[a-f0-9]{32}")
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Synchronous OkHttp execute() ignores coroutine cancellation while a
     * 40 MiB encrypted stream is blocked on a slow mobile network. The active
     * call MUST be cancelled when the activity scope exits or a caller aborts.
     * Late HTTP responses are closed even if cancellation won the race.
     * Neither the URL nor private ciphertext bytes are logged.
     */
    internal suspend fun awaitResponse(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                    } else {
                        response.close()
                    }
                }
            })
        }

    internal fun verifyGrantUrl(signedUrl: String, id: String, upload: Boolean): String {
        require(id.matches(fileId)) { "Dosya kimliği geçersiz." }
        val source = URI(BuildConfig.LANU_RELAY_URL)
        val expected = URI(signedUrl)
        val route = if (upload) "upload/sign" else "sign"
        val pathname = "/storage/v1/object/$route/$BUCKET/v1/$id.cipher"
        // An arbitrary HTTPS URL is never an authorized media grant.
        require(expected.scheme == "https" &&
            expected.host == source.host && source.host.endsWith(".supabase.co") &&
            expected.port == -1 && expected.rawUserInfo == null &&
            expected.path == pathname && expected.fragment == null &&
            expected.rawQuery?.let { q ->
                q.length in 23..5000 && q.startsWith("token=") &&
                    q.substringAfter("token=").matches(Regex("[A-Za-z0-9._~%-]+"))
            } == true) { "Güvenli dosya bağlantısı doğrulanamadı." }
        return signedUrl
    }

    suspend fun uploadCiphertext(
        signedUrl: String,
        id: String,
        source: File,
        manifest: PrivateMediaCrypto.Manifest,
        privateDirectory: File
    ) = withContext(Dispatchers.IO) {
        PrivateMediaCrypto.validate(manifest)
        require(manifest.fileId == id)
        val checked = PrivateMediaTransferBudget.verifyForReservation(
            source, manifest, privateDirectory
        )
        require(checked.cipherBytes in 17..MAX_CIPHER_BYTES)
        val address = verifyGrantUrl(signedUrl, id, upload = true)
        // Do not buffer even one full 40 MiB file in Java heap.
        val requestBody = object : RequestBody() {
            // 40 MiB uploads use a consumable ciphertext stream. A transport
            // failure must not make OkHttp automatically replay the signed PUT:
            // the relay alone verifies whether the immutable object committed.
            // A new upload grant, if necessary, is obtained explicitly.
            override fun isOneShot(): Boolean = true
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = checked.cipherBytes
            override fun writeTo(sink: okio.BufferedSink) {
                val canonical = source.canonicalFile
                require(source.absoluteFile == canonical &&
                    canonical.parentFile == privateDirectory.canonicalFile) {
                    "Özel dosya kaynağı değişti."
                }
                FileInputStream(source).use { input ->
                    require(input.channel.size() == checked.cipherBytes) {
                        "Yüklenecek dosya boyutu değişti."
                    }
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var nbytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("Şifreli medya okunamadı.")
                        nbytes += count
                        if (nbytes > checked.cipherBytes) throw IOException("Şifreli medya boyutu aşıldı.")
                        hash.update(buffer, 0, count)
                        sink.write(buffer, 0, count)
                    }
                    if (nbytes != checked.cipherBytes ||
                        !MessageDigest.isEqual(hash.digest(), hex(manifest.cipherSha256)))
                        throw IOException("Dosya yüklemesi sırasında bütünlük bozuldu.")
                }
            }
        }
        val request = Request.Builder().url(address).put(requestBody).build()
        awaitResponse(request).use { response ->
            if (response.code !in 200..299)
                throw IOException("Şifreli dosya yüklenemedi. Tekrar deneyin.")
        }
    }

    suspend fun downloadCiphertext(
        signedUrl: String,
        manifest: PrivateMediaCrypto.Manifest,
        privateDirectory: File
    ): File = withContext(Dispatchers.IO) {
        PrivateMediaCrypto.validate(manifest)
        val expectedBytes = PrivateMediaTransferBudget.encryptedBytes(manifest.plainBytes)
        val address = verifyGrantUrl(signedUrl, manifest.fileId, upload = false)
        require(privateDirectory.isDirectory && privateDirectory.absoluteFile == privateDirectory.canonicalFile)
        val destination = File.createTempFile("lanu-media-v1-", ".cipher", privateDirectory)
        try {
            val request = Request.Builder().url(address).get().build()
            awaitResponse(request).use { response ->
                require(response.code == 200 && !response.isRedirect) {
                    "Güvenli medya indirilemedi."
                }
                require(response.header("Content-Encoding").orEmpty().lowercase() in setOf("", "identity"))
                require(response.header("Content-Type")?.startsWith("application/octet-stream") == true)
                val body = response.body ?: throw IOException("Boş dosya yanıtı.")
                require(body.contentLength() == -1L || body.contentLength() == expectedBytes)
                FileOutputStream(destination).use { output ->
                    val bytes = PrivateMediaTransferBudget.digestCipherStream(
                        object : java.io.FilterInputStream(body.byteStream()) {
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                val n = super.read(b, off, len)
                                if (n > 0) output.write(b, off, n)
                                return n
                            }
                        }, expectedBytes
                    )
                    require(MessageDigest.isEqual(bytes, hex(manifest.cipherSha256))) {
                        "Şifreli dosya bütünlüğü doğrulanamadı."
                    }
                    output.fd.sync()
                }
            }
            require(destination.length() == expectedBytes) { "Şifreli dosya eksik indirildi." }
            destination
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }

    private fun hex(value: String): ByteArray {
        require(value.matches(Regex("[a-f0-9]{64}")))
        return ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
