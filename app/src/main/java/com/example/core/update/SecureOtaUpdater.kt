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
    private val tagPattern = Regex("""v1\.(?:0\.0-debug|1\.0-signed)\.(\d+)""")
    private val shaPattern = Regex("[a-f0-9]{64}")

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followSslRedirects(true)
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

    /** Two checks, both mandatory: byte SHA-256 and installed APK signing identity. */
    fun verifyArchive(context: Context, apk: File, release: LanuUpdate, expectedSha: String) {
        val info = packageWithSignatures(context, apk)
        require(info.packageName == context.packageName) { "APK farklı uygulamaya ait." }
        require(versionCode(info) == release.versionCode && versionCode(info) > installedVersion(context)) {
            "APK sürüm numarası beklenenden farklı veya güncel değil."
        }
        val certs = signatures(info)
        require(certs.size == 1 && sameCertificate(digest(certs.single()), installedCertificate(context))) {
            "APK imzası mevcut LANU kurulumu ile uyumsuz. Kurulu uygulamayı kaldırmayın."
        }
        require(expectedSha.matches(shaPattern)) { "Geçersiz APK özeti." }
        require(apk.length() in 1..MAX_APK_BYTES) { "APK boyutu güvenli aralık dışında." }
    }

    suspend fun check(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
        val current = installedVersion(context)
        val release = chooseRelease(readText(RELEASE_API, 1_500_000), current)
            ?: return@withContext UpdateStatus.Current(current)
        val pinned = parseSigner(readText(release.certificateUrl, 3_000))
        val installed = installedCertificate(context)
        if (!sameCertificate(pinned, installed)) {
            UpdateStatus.Incompatible(
                release.tag,
                "Yeni sürümün APK imzası mevcut kurulumla uyuşmuyor. Verileri korumak için güncelleme engellendi; uygulamayı kaldırmayın."
            )
        } else UpdateStatus.Available(release)
    }

    suspend fun download(context: Context, release: LanuUpdate): File = withContext(Dispatchers.IO) {
        val pinned = parseSigner(readText(release.certificateUrl, 3_000))
        require(sameCertificate(pinned, installedCertificate(context))) {
            "Sertifika mevcut uygulama ile uyuşmuyor. Güncelleme engellendi."
        }
        val expected = parseChecksum(readText(release.checksumUrl, 1_000))
        val directory = File(context.cacheDir, "ota").apply { mkdirs() }
        val partial = File(directory, "pending.apk.part")
        val final = File(directory, "lanu-update.apk")
        partial.delete()
        final.delete()
        try {
            val req = Request.Builder().url(release.apkUrl).header("User-Agent", "LANU-Android-Updater").build()
            http.newCall(req).execute().use { response ->
                require(response.isSuccessful) { "APK indirilemedi (HTTP ${response.code})." }
                require(response.request.url.isHttps) { "Güvensiz indirme yönlendirmesi reddedildi." }
                val body = response.body ?: error("APK yanıtı boş.")
                require(body.contentLength() <= MAX_APK_BYTES) { "APK boyutu sınırı aşıldı." }
                val md = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= MAX_APK_BYTES) { "APK çok büyük." }
                            md.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                require(sameCertificate(actual, expected)) { "APK SHA-256 değeri uyuşmuyor; indirme iptal edildi." }
            }
            verifyArchive(context, partial, release, expected)
            require(partial.renameTo(final)) { "Doğrulanmış APK hazırlanamıyor." }
            final
        } catch (e: Exception) {
            partial.delete()
            final.delete()
            throw e
        }
    }

    private fun readText(url: String, limit: Int): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "LANU-Android-Updater")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Güncelleme sunucusuna erişilemiyor (HTTP ${response.code})." }
            require(response.request.url.isHttps) { "Güvensiz bağlantı reddedildi." }
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
        require(apk.isFile && apk.parentFile?.name == "ota") { "Geçersiz indirme dosyası." }
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
