package com.example.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Opaque ciphertext transport. ONLY server-signed grants from the user's
 * existing authenticated ECDSA relay are accepted. No Storage API key, bearer
 * credential, filename, user ID or AES secret is ever transmitted here.
 *
 * This is a transport primitive, not a user-facing feature: it must be bound
 * to the verified E2EE envelope and private document UI before activation.
 */
internal class PrivateMediaStorageClient(
    private val relayEndpoint: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS).build()
) {
    companion object {
        const val TUS_CHUNK_BYTES = 6 * 1024 * 1024
        private const val MAX_RETRIES = 4
        private val HEX_ID = Regex("[a-f0-9]{32}")
        private val HEX_SHA = Regex("[a-f0-9]{64}")
        // Supabase grants use one opaque URL-safe bearer token. Never accept
        // duplicate/unknown query keys or encoded delimiters from relay input.
        private val SIGNED_GRANT_QUERY = Regex("token=[A-Za-z0-9._~-]{16,4096}")

        /**
         * A failed TUS PATCH may have committed zero, some, or all of the
         * attempted bytes. HEAD must never move behind a confirmed offset or
         * past bytes actually sent: that would silently skip ciphertext.
         */
        internal fun validRecoveryOffset(
            previousOffset: Long, attemptedBytes: Int, serverOffset: Long, expectedBytes: Long
        ): Boolean {
            if (previousOffset < 0 || attemptedBytes <= 0 ||
                attemptedBytes > TUS_CHUNK_BYTES || expectedBytes < 0 ||
                previousOffset > expectedBytes ||
                attemptedBytes.toLong() > expectedBytes - previousOffset) return false
            return serverOffset >= previousOffset &&
                serverOffset <= previousOffset + attemptedBytes.toLong()
        }

        /** Bind the signed TUS header to the one token in the authorized grant URL. */
        internal fun validSignedToken(signedUrl: String, token: String): Boolean {
            if (token.length !in 8..4096 ||
                token.any { it.isWhitespace() || Character.isISOControl(it) }) return false
            val query = runCatching { URI(signedUrl).rawQuery }.getOrNull() ?: return false
            val fields = query.split('&')
            if (fields.size != 1 || !fields[0].startsWith("token=")) return false
            val decoded = runCatching {
                java.net.URLDecoder.decode(fields[0].removePrefix("token="), "UTF-8")
            }.getOrNull() ?: return false
            return decoded == token
        }

        /** Signed grants may use either supported Supabase host; never double the storage label. */
        internal fun storageHost(host: String): String {
            require(host.matches(Regex("[a-z0-9-]+(\\.storage)?\\.supabase\\.co")))
            return if (host.endsWith(".storage.supabase.co")) host
            else host.removeSuffix(".supabase.co") + ".storage.supabase.co"
        }


        /** Prevent a forged grant URL from exfiltrating private ciphertext. */
        internal fun safeSignedUrl(endpoint: String, url: String, fileId: String, upload: Boolean): Boolean {
            if (!HEX_ID.matches(fileId)) return false
            val base = runCatching { URI(endpoint) }.getOrNull() ?: return false
            val target = runCatching { URI(url) }.getOrNull() ?: return false
            if (base.scheme != "https" || target.scheme != "https" ||
                base.userInfo != null || target.userInfo != null ||
                target.fragment != null || target.port != -1 || base.port != -1 ||
                base.host == null || target.host == null ||
                !base.host.matches(Regex("[a-z0-9-]+\\.supabase\\.co"))) return false
            if (target.host != base.host &&
                target.host != base.host.replace(".supabase.co", ".storage.supabase.co")) return false
            val path = "/storage/v1/object/" +
                (if (upload) "upload/sign/" else "sign/") +
                "lanu-private-media-v1/v1/$fileId.cipher"
            return target.rawPath == path &&
                target.rawQuery?.matches(SIGNED_GRANT_QUERY) == true
        }
    }

    private fun authorizedBase(signedUrl: String, fileId: String, upload: Boolean): URI {
        require(safeSignedUrl(relayEndpoint, signedUrl, fileId, upload)) {
            "Özel dosya sunucusu doğrulanamadı."
        }
        return URI(signedUrl)
    }

    /**
     * TUS Location is untrusted server input. Only one opaque upload identifier
     * under the exact resumable path is allowed: no query, encoded separators,
     * traversal, or nested paths. Never forward the signed token elsewhere.
     */
    internal fun locationForTus(base: URI, location: String): String {
        val candidate = runCatching { base.resolve(location) }.getOrNull()
            ?: throw IllegalArgumentException("Şifreli dosya aktarım bağlantısı güvenli değil.")
        val origin = URI(relayEndpoint)
        val expectedHosts = setOf(origin.host,
            origin.host.replace(".supabase.co", ".storage.supabase.co"))
        val prefix = "/storage/v1/upload/resumable/"
        val rawPath = candidate.rawPath.orEmpty()
        val uploadId = if (rawPath.startsWith(prefix)) rawPath.removePrefix(prefix) else ""
        require(candidate.scheme == "https" && candidate.userInfo == null &&
            candidate.rawFragment == null && candidate.rawQuery == null &&
            candidate.port == -1 && candidate.host in expectedHosts &&
            uploadId.matches(Regex("[A-Za-z0-9._~-]{1,512}")) &&
            uploadId != "." && uploadId != "..") {
            "Şifreli dosya aktarım bağlantısı güvenli değil."
        }
        return candidate.toString()
    }

    private fun validatedToken(signedUrl: String, token: String): String {
        require(validSignedToken(signedUrl, token)) {
            "Dosya gönderim izni veya bağlantısı geçersiz."
        }
        return token
    }

    private fun metadata(bucket: String, path: String): String {
        fun b64(value: String) = android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return listOf(
            "bucketName " + b64(bucket),
            "objectName " + b64(path),
            "contentType " + b64("application/octet-stream")
        ).joinToString(",")
    }

    /**
     * 6 MiB TUS PATCHes with server HEAD recovery. Never replay an uncertain
     * write without querying its committed offset first. Existing object paths
     * cannot be overwritten (no x-upsert header).
     */
    suspend fun upload(
        encrypted: File, fileId: String, expectedBytes: Long,
        signedUrl: String, signedToken: String
    ) = withContext(Dispatchers.IO) {
        val base = authorizedBase(signedUrl, fileId, upload = true)
        val token = validatedToken(signedUrl, signedToken)
        require(expectedBytes in 17..PrivateMediaTransferBudget.MAX_RESERVED_CIPHER_BYTES &&
            encrypted.isFile && encrypted.length() == expectedBytes) {
            "Şifreli dosya boyutu beklenen değere uymuyor."
        }
        val origin = storageHost(base.host)
        val createUrl = "https://$origin/storage/v1/upload/resumable"
        val path = "v1/$fileId.cipher"
        val create = Request.Builder().url(createUrl)
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", expectedBytes.toString())
            .header("Upload-Metadata", metadata("lanu-private-media-v1", path))
            .header("x-signature", token)
            .post(ByteArray(0).toRequestBody()).build()
        val location = client.newCall(create).execute().use { response ->
            if (response.code != 201) throw IOException("Güvenli dosya yüklemesi başlatılamadı.")
            response.header("Location") ?: throw IOException("Dosya yükleme adresi bulunamadı.")
        }
        val tusUrl = locationForTus(base, location)
        RandomAccessFile(encrypted, "r").use { file ->
            var offset = 0L
            var failures = 0
            while (offset < expectedBytes) {
                currentCoroutineContext().ensureActive()
                val amount = minOf(TUS_CHUNK_BYTES.toLong(), expectedBytes - offset).toInt()
                file.seek(offset)
                val part = ByteArray(amount)
                file.readFully(part)
                try {
                    val request = Request.Builder().url(tusUrl)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Offset", offset.toString())
                        .header("x-signature", token)
                        .header("Content-Type", "application/offset+octet-stream")
                        .method("PATCH", part.toRequestBody(
                            "application/offset+octet-stream".toMediaType())).build()
                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.code !in listOf(200, 204))
                                throw IOException("Dosya parçası aktarılamadı.")
                            val next = response.header("Upload-Offset")?.toLongOrNull()
                                ?: throw IOException("Yükleme ilerlemesi doğrulanamadı.")
                            require(next == offset + amount) { "Yükleme sırası geçersiz." }
                            offset = next
                            failures = 0
                        }
                    } catch (error: IOException) {
                        if (++failures > MAX_RETRIES) throw error
                        delay(300L * failures)
                        val probe = Request.Builder().url(tusUrl)
                            .header("Tus-Resumable", "1.0.0")
                            .header("x-signature", token).head().build()
                        client.newCall(probe).execute().use { response ->
                            if (response.code != 200 && response.code != 204)
                                throw IOException("Yükleme kaldığı yerden doğrulanamadı.")
                            val serverOffset = response.header("Upload-Offset")?.toLongOrNull()
                                ?: throw IOException("Yükleme konumu bilinmiyor.")
                            if (!validRecoveryOffset(offset, amount, serverOffset, expectedBytes))
                                throw IOException("Yükleme konumu doğrulanmış parçanın dışına çıktı.")
                            offset = serverOffset
                        }
                    }
                } finally { part.fill(0) }
            }
            require(offset == expectedBytes && file.length() == expectedBytes) {
                "Şifreli dosya eksik gönderildi."
            }
        }
    }

    /** Download ciphertext into app-private storage, hash before AES-GCM. */
    suspend fun downloadVerified(
        fileId: String, signedUrl: String,
        manifest: PrivateMediaCrypto.Manifest, key: ByteArray,
        privateDirectory: File
    ): File = withContext(Dispatchers.IO) {
        require(fileId == manifest.fileId && key.size == 32 &&
            HEX_SHA.matches(manifest.cipherSha256) &&
            privateDirectory.isDirectory && privateDirectory.absoluteFile == privateDirectory.canonicalFile)
        val target = authorizedBase(signedUrl, fileId, upload = false)
        val expected = PrivateMediaTransferBudget.encryptedBytes(manifest.plainBytes)
        val cipher = File.createTempFile("lanu-media-v1-", ".cipher", privateDirectory)
        try {
            val request = Request.Builder().url(target.toString())
                .header("Accept-Encoding", "identity").get().build()
            val result = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                if (response.code != 200 || !response.isSuccessful)
                    throw IOException("Dosya indirme yetkisi doğrulanamadı.")
                val declared = response.body?.contentLength() ?: -1
                if (declared > expected) throw IOException("Şifreli dosya beklenen boyutu aştı.")
                val input = response.body?.byteStream()
                    ?: throw IOException("Dosya içeriği alınamadı.")
                FileOutputStream(cipher).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var stalls = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) {
                            if (++stalls >= 3) throw IOException("Dosya aktarımı durdu.")
                            continue
                        }
                        stalls = 0
                        readTotal += n
                        if (readTotal > expected) throw IOException("Şifreli dosya sınırı aşıldı.")
                        result.update(buffer, 0, n)
                        sink.write(buffer, 0, n)
                    }
                    sink.fd.sync()
                    require(readTotal == expected) { "Şifreli dosya eksik alındı." }
                }
            }
            val expectedDigest = manifest.cipherSha256.chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray()
            require(MessageDigest.isEqual(result.digest(), expectedDigest)) {
                "Şifreli dosya doğrulanamadı."
            }
            return@withContext PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                cipher, manifest, key, privateDirectory
            )
        } finally { cipher.delete() }
    }
}
