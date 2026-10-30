package com.example

import com.example.core.media.PrivateMediaTransferHttp
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaTransferHttpTest {
    private val host = URI(BuildConfig.LANU_RELAY_URL).host
    private val id = "a".repeat(32)
    private val token = "b".repeat(80)
    private fun url(mode: String, fileId: String = id) =
        "https://$host/storage/v1/object/$mode/lanu-private-media-v1/v1/$fileId.cipher?token=$token"

    @Test fun grantsAreRestrictedToOriginalPrivateBucketAndImmutableCipherId() {
        assertEquals(url("upload/sign"), PrivateMediaTransferHttp.verifyGrantUrl(
            url("upload/sign"), id, upload = true))
        assertEquals(url("sign"), PrivateMediaTransferHttp.verifyGrantUrl(
            url("sign"), id, upload = false))
    }

    @Test fun encodedPathsAndNoncanonicalBearerTokensAreRejected() {
        val valid = url("sign")
        for (bad in listOf(
            valid.replace("/v1/$id.cipher", "/v1/%61${id.drop(1)}.cipher"),
            valid.replace("?token=$token", "?token=${token}%0A"),
            valid.replace("?token=$token", "?token=${token}&token=another"),
            valid.replace("?token=$token", "?token=short"),
            valid.replace("?token=$token", "?token=${"c".repeat(4097)}"),
            valid.replace("?token=$token", "?token=%62${token.drop(1)}"),
            valid.replace("https://", "https://user:pass@")
        )) {
            assertTrue("Noncanonical signed grant must fail", runCatching {
                PrivateMediaTransferHttp.verifyGrantUrl(bad, id, upload = false)
            }.isFailure)
        }
    }

    @Test fun offSiteRedirectWrongModeHostPortAndPathAreRejected() {
        val valid = url("sign")
        for (bad in listOf(
            valid.replace("https://", "http://"),
            valid.replace(host, "evil.invalid"),
            valid.replace(host, "$host.evil.invalid"),
            valid.replace(host, "$host:444"),
            valid.replace("/object/sign/", "/object/upload/sign/"),
            valid.replace("/lanu-private-media-v1/", "/public-media/"),
            valid.replace(id, "f".repeat(32)),
            valid.replace("?token=", "?debug=1&token="),
            valid.replace("?token=$token", ""),
            valid + "#exfiltrate"
        )) {
            assertTrue("Malformed signed grant must fail: $bad",
                runCatching {
                    PrivateMediaTransferHttp.verifyGrantUrl(bad, id, upload = false)
                }.isFailure)
        }
        assertTrue(runCatching {
            PrivateMediaTransferHttp.verifyGrantUrl(valid, "../escape", upload = false)
        }.isFailure)
    }
}
