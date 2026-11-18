package com.example

import com.example.core.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OtaAssetFallbackTest {
    private val tag = "v1.2.0-signed.1322"
    private val name = "app-debug.apk"
    private val url = "https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/download/$tag/$name"
    private fun metadata(id: Any = 123L, draft: Boolean = false) = JSONObject().put("tag_name", tag).put("draft", draft)
        .put("assets", JSONArray().put(JSONObject().put("id", id).put("name", name).put("state", "uploaded")
            .put("size", 62000000).put("browser_download_url", url))).toString()
    @Test fun failedOfficialDownloadUsesOnlyConstructedAnonymousApi() = runTest {
        val result = OtaAssetFallback.fetch(url, primary = { throw IOException("synthetic") },
            metadata = { assertTrue(it.endsWith("/releases/tags/$tag")); metadata() },
            fallback = { assertEquals("https://api.github.com/repos/kanunal99-jpg/LANU-Hesap-Makinesi/releases/assets/123", it); "verified downstream" })
        assertEquals("verified downstream", result)
    }
    @Test fun invalidAssetIdentityAndDraftCannotRedirectDownloads() {
        for (raw in listOf(metadata("123x"), metadata(123L, true), metadata().replace(url, "https://example.org/file"))) {
            assertTrue(runCatching { OtaAssetFallback.apiAsset(raw, tag, name) }.isFailure)
        }
    }
    @Test fun cancellationSignatureFailureAndQuotaNeverTriggerAlternateTraffic() = runTest {
        for (error in listOf(CancellationException("cancel"), SecurityException("signature"), OtaHttpException(429))) {
            var alternate = false
            assertTrue(runCatching { OtaAssetFallback.fetch<String>(url, { throw error },
                { alternate = true; metadata() }, { alternate = true; "bad" }) }.isFailure)
            assertFalse(alternate)
        }
    }
    @Test fun totalBudgetCancelsTricklingOrNeverEndingOperation() = runTest {
        var closed = false
        val error = runCatching { OtaRequestBudget.run(false) { try { awaitCancellation() } finally { closed = true } } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertTrue(closed)
        assertEquals(OtaRequestBudget.METADATA_MS, testScheduler.currentTime)
    }
}
