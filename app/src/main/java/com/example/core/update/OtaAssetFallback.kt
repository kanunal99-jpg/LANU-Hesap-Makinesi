package com.example.core.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException

internal class OtaHttpException(val status: Int) : IOException("OTA HTTP $status")

/** Anonymous public API fallback, always retaining the existing APK verification. */
internal object OtaAssetFallback {
    private const val REPO = "kanunal99-jpg/LANU-Hesap-Makinesi"
    private val asset = Regex("https://github\\.com/$REPO/releases/download/(v1\\.(?:0\\.0-debug|1\\.0-signed|2\\.0-signed)\\.[0-9]+)/(app-debug\\.apk(?:\\.sha256|\\.signer\\.txt)?)")
    fun isApk(url: String) = asset.matchEntire(url)?.groupValues?.get(2) == "app-debug.apk"
    fun apiAsset(raw: String, tag: String, name: String): String {
        val release = JSONObject(raw)
        require(!release.optBoolean("draft", true) && release.optString("tag_name") == tag) { "OTA sürüm bilgisi uyuşmuyor." }
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter { it.optString("name") == name }
        require(matches.size == 1) { "OTA dosyası eksik veya belirsiz." }
        val selected = matches.single()
        val idText = selected.get("id").toString()
        val id = idText.toLongOrNull()
        val size = selected.get("size").toString().toLongOrNull() ?: -1L
        require(id != null && id > 0 && selected.optString("state") == "uploaded" &&
            size in 1..(if (name == "app-debug.apk") 90L * 1024 * 1024 else 4096L) &&
            selected.optString("browser_download_url") == "https://github.com/$REPO/releases/download/$tag/$name") { "OTA dosya kimliği geçersiz." }
        return "https://api.github.com/repos/$REPO/releases/assets/$id"
    }
    suspend fun <T> fetch(url: String, primary: suspend () -> T,
        metadata: suspend (String) -> String, fallback: suspend (String) -> T): T {
        try { return primary() }
        catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (e is OtaHttpException && e.status !in setOf(403, 404, 408) && e.status !in 500..599) throw e
            val match = asset.matchEntire(url) ?: throw e
            val (tag, name) = match.destructured
            val raw = metadata("https://api.github.com/repos/$REPO/releases/tags/$tag")
            return fallback(apiAsset(raw, tag, name))
        }
    }
}

internal object OtaRequestBudget {
    const val METADATA_MS = 30_000L
    const val APK_MS = 480_000L
    suspend fun <T> run(apk: Boolean, block: suspend () -> T): T =
        withTimeout(if (apk) APK_MS else METADATA_MS) { block() }
}
