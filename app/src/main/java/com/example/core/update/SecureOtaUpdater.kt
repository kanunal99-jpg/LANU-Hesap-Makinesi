package com.example.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** This updater never silently installs, deletes an existing app, or bypasses Android signature checks. */
data class LanuUpdate(
    val tag: String,
    val versionCode: Long,
    val apkUrl: String,
    val checksumUrl: String,
    val certificateUrl: String
)

/** Installation is only offered for the exact already-verified local package. */
data class VerifiedUpdate(
    val release: LanuUpdate,
    val file: File,
    val sha256: String,
    val certificateSha256: String
)

sealed class UpdateStatus {
    data class Current(val versionCode: Long) : UpdateStatus()
    data class Available(val release: LanuUpdate) : UpdateStatus()
    data class Incompatible(val tag: String, val reason: String) : UpdateStatus()
}

object SecureOtaUpdater {
    private const val OWNER_REPO = "kanunal99-jpg/LANU-Hesap-Makinesi"
    private const val RELEASE_API = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=30"
    private const val APK_NAME = "app-debug.apk"
    private const val CHECKSUM_NAME = "app-debug.apk.sha256"
    private const val SIGNER_NAME = "app-debug.apk.signer.txt"
    private const val MAX_APK_BYTES = 90L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val approvedHosts = setOf(
        "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
    )
    private val tagPattern = Regex("""v1\.(?:0\.0-debug|1\.0-signed)\.(\d+)""")
    private val shaPattern = Regex("[a-f0-9]{64}")

    /** Release metadata and content are limited to GitHub and its known download CDN. */
    fun isApprovedEndpoint(scheme: String, host: String): Boolean =
        scheme == "https" && host in approvedHosts

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        // Inspect every redirect before making a request to the next host.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun installedVersion(context: Context): Long = versionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Releases must come from the official repository, with three exact asset names. */
    fun chooseRelease(raw: String, currentVersion: Long): LanuUpdate? {
        val releases = JSONArray(raw)
        val options = mutableListOf<LanuUpdate>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            val n = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (n <= currentVersion) continue
            val assets = release.optJSONArray("assets") ?: continue
            fun url(name: String): String? {
                for (j in 0 until assets.length()) {
                    val item = assets.optJSONObject(j) ?: continue
                    if (item.optString("name") != name) continue
                    val candidate = item.optString("browser_download_url")
                    // Never download untrusted URLs supplied in the release API response.
                    if (candidate == "https://github.com/$OWNER_REPO/releases/download/$tag/$name") return candidate
                }
                return null
            }
            val apk = url(APK_NAME) ?: continue
            val checksum = url(CHECKSUM_NAME) ?: continue
            val cert = url(SIGNER_NAME) ?: continue
            options += LanuUpdate(tag, n, apk, checksum, cert)
        }
        return options.maxByOrNull { it.versionCode }
    }

    /** Fail closed if an advertised higher-version release has no signed verification metadata. */
    fun newestPublishedVersion(raw: String, currentVersion: Long): Pair<String, Long>? {
        val releases = JSONArray(raw)
        var latest: Pair<String, Long>? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            val number = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (number > currentVersion && (latest == null || number > latest.second)) latest = tag to number
        }
        return latest
    }

    /** Never allow an arbitrary URL to be injected through a forged release object. */
    fun validateRelease(release: LanuUpdate) {
        require(tagPattern.matchEntire(release.tag)?.groupValues?.get(1)?.toLongOrNull() == release.versionCode) {
            "Sürüm kimliği geçersiz."
        }
        require(release.apkUrl == "https://github.com/$OWNER_REPO/releases/download/${release.tag}/$APK_NAME") {
            "Resmî olmayan APK adresi reddedildi."
        }
        require(release.checksumUrl == "https://github.com/$OWNER_REPO/releases/download/${release.tag}/$CHECKSUM_NAME") {
            "Resmî olmayan SHA-256 adresi reddedildi."
        }
        require(release.certificateUrl == "https://github.com/$OWNER_REPO/releases/download/${release.tag}/$SIGNER_NAME") {
            "Resmî olmayan imza adresi reddedildi."
        }
    }

    fun parseSigner(text: String): String {
        val fields = text.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }.toMap()
        require(fields["apk"] == APK_NAME && fields["signing_mode"] in setOf("stable", "ephemeral")) {
            "Sürüm imza bilgisi eksik."
        }
        return fields["certificate_sha256"]?.lowercase(Locale.ROOT)?.takeIf(shaPattern::matches)
            ?: throw IllegalArgumentException("Sertifika parmak izi geçersiz.")
    }

    fun parseChecksum(text: String): String {
        val line = text.trim()
        require(line.matches(Regex("[a-fA-F0-9]{64}\\s+\\*?app-debug\\.apk"))) {
            "APK SHA-256 doğrulama kaydı geçersiz."
        }
        return line.take(64).lowercase(Locale.ROOT)
    }

    fun sameCertificate(actual: String, expected: String) =
        shaPattern.matches(actual) && actual.equals(expected, ignoreCase = true)

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: throw SecurityException("APK imza bilgisi yok.")
            val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            signatures.orEmpty().map { it.toByteArray() }
        } else info.signatures.orEmpty().map { it.toByteArray() }
    }

    @Suppress("DEPRECATION")
    private fun packageWithSignatures(context: Context, file: File?): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (file == null) context.packageManager.getPackageInfo(context.packageName, flags)
        else context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw SecurityException("İndirilen APK okunamadı.")
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun installedCertificate(context: Context): String {
        val certs = signatures(packageWithSignatures(context, null))
        require(certs.size == 1) { "Çoklu APK imzası desteklenmiyor." }
        return digest(certs.single())
    }

    /** Each execution independently checks file content AND Android's APK signing identity. */
    fun verifyArchive(context: Context, apk: File, release: LanuUpdate, expectedSha: String) {
        validateRelease(release)
        require(expectedSha.matches(shaPattern)) { "Geçersiz APK özeti." }
        require(apk.isFile && apk.length() in 1..MAX_APK_BYTES) { "APK boyutu güvenli aralık dışında." }
        val actualHash = MessageDigest.getInstance("SHA-256").let { md ->
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        require(sameCertificate(actualHash, expectedSha)) {
            "İndirilen APK son doğrulamadan sonra değişmiş. Kurulum engellendi."
        }
        val info = packageWithSignatures(context, apk)
        require(info.packageName == context.packageName) { "APK farklı uygulamaya ait." }
        require(versionCode(info) == release.versionCode && versionCode(info) > installedVersion(context)) {
            "APK sürüm numarası beklenenden farklı veya güncel değil."
        }
        val certs = signatures(info)
        require(certs.size == 1 && sameCertificate(digest(certs.single()), installedCertificate(context))) {
            "APK imzası mevcut LANU kurulumu ile uyumsuz. Kurulu uygulamayı kaldırmayın."
        }
    }

    suspend fun check(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
        val current = installedVersion(context)
        val raw = readText(RELEASE_API, 1_500_000)
        val newest = newestPublishedVersion(raw, current) ?: return@withContext UpdateStatus.Current(current)
        val release = chooseRelease(raw, current)
        if (release == null || newest.second > release.versionCode) {
            return@withContext UpdateStatus.Incompatible(
                newest.first,
                "Yeni sürüm yayımlanmış ancak güvenli OTA için gerekli APK, SHA-256 veya imza doğrulama dosyası eksik. Kurulum engellendi."
            )
        }
        validateRelease(release)
        val pinned = parseSigner(readText(release.certificateUrl, 3_000))
        val installed = installedCertificate(context)
        if (!sameCertificate(pinned, installed)) {
            UpdateStatus.Incompatible(
                release.tag,
                "Yeni sürümün APK imzası mevcut kurulumla uyuşmuyor. Verileri korumak için güncelleme engellendi; uygulamayı kaldırmayın."
            )
        } else UpdateStatus.Available(release)
    }

    /** Progress callback is delivered on Main; cancellation deletes incomplete download bytes. */
    suspend fun download(
        context: Context,
        release: LanuUpdate,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> }
    ): VerifiedUpdate = withContext(Dispatchers.IO) {
        validateRelease(release)
        val pinned = parseSigner(readText(release.certificateUrl, 3_000))
        require(sameCertificate(pinned, installedCertificate(context))) {
            "Sertifika mevcut uygulama ile uyuşmuyor. Güncelleme engellendi."
        }
        val expected = parseChecksum(readText(release.checksumUrl, 1_000))
        val directory = File(context.cacheDir, "ota").apply { require(isDirectory || mkdirs()) }
        // Remove only old files; do not touch another live download.
        val expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        val partial = File(directory, "lanu-${release.versionCode}-$token.part.apk")
        val final = File(directory, "lanu-${release.versionCode}-$token.apk")
        try {
            trustedGet(release.apkUrl, "application/octet-stream").use { response ->
                val body = response.body ?: error("APK yanıtı boş.")
                val declared = body.contentLength()
                require(declared <= MAX_APK_BYTES) { "APK boyutu sınırı aşıldı." }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastReported = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= MAX_APK_BYTES) { "APK çok büyük." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (received - lastReported >= 512 * 1024) {
                                lastReported = received
                                withContext(Dispatchers.Main) {
                                    onProgress(received, declared.takeIf { it > 0 })
                                }
                            }
                        }
                    }
                }
                require(received > 0L) { "APK indirmesi boş." }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(sameCertificate(actual, expected)) { "APK SHA-256 değeri uyuşmuyor; indirme iptal edildi." }
                withContext(Dispatchers.Main) { onProgress(received, received) }
            }
            verifyArchive(context, partial, release, expected)
            require(partial.renameTo(final)) { "Doğrulanmış APK hazırlanamıyor." }
            VerifiedUpdate(release, final, expected, pinned)
        } catch (e: Exception) {
            partial.delete()
            final.delete()
            throw e
        }
    }

    /** Recheck at the moment the user requests installation, not only at download time. */
    suspend fun verifyBeforeInstall(context: Context, ready: VerifiedUpdate) = withContext(Dispatchers.IO) {
        val otaDir = File(context.cacheDir, "ota").canonicalFile
        val file = ready.file.canonicalFile
        require(file.parentFile == otaDir && file.name.endsWith(".apk")) { "İndirilen dosya konumu geçersiz." }
        require(sameCertificate(ready.certificateSha256, installedCertificate(context))) {
            "Kurulu LANU sertifikası değişmiş; güncelleme engellendi."
        }
        verifyArchive(context, file, ready.release, ready.sha256)
    }

    /**
     * Pin all redirect destinations before making follow-on HTTP requests.
     * GitHub assets legitimately redirect to its public release CDN.
     */
    private fun trustedGet(url: String, accept: String): Response {
        var next = Request.Builder().url(url).build().url
        repeat(MAX_REDIRECTS + 1) { hop ->
            require(isApprovedEndpoint(next.scheme, next.host)) {
                "Güncelleme indirmesi onaylanmamış bir adrese yönlendirildi."
            }
            val request = Request.Builder().url(next)
                .header("User-Agent", "LANU-Android-Updater")
                .header("Accept", accept)
                .header("Cache-Control", "no-cache")
                .build()
            val response = http.newCall(request).execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                val resolved = location?.let { next.resolve(it) }
                response.close()
                require(hop < MAX_REDIRECTS && resolved != null) { "Güncelleme yönlendirme sınırına ulaştı." }
                next = resolved
            } else {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IllegalStateException("GitHub güncelleme hizmeti HTTP $code döndürdü.")
                }
                return response
            }
        }
        error("Güncelleme yönlendirmesi doğrulanamadı.")
    }

    private fun readText(url: String, limit: Int): String {
        trustedGet(url, "application/vnd.github+json").use { response ->
            val body = response.body ?: error("Sunucu yanıtı boş.")
            require(body.contentLength() <= limit) { "Beklenmeyen büyük yanıt." }
            return body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= limit) { "Sunucu yanıtı çok büyük." }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    fun launchInstaller(context: Context, apk: File): Boolean {
        require(apk.isFile && apk.canonicalFile.parentFile == File(context.cacheDir, "ota").canonicalFile) {
            "Geçersiz indirme dosyası."
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false // User explicitly returns and presses install again.
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true // Android's system installer must still ask user confirmation.
    }
}
