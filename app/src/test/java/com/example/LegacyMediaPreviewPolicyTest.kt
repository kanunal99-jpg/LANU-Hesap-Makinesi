package com.example

import com.example.features.communication.LegacyMediaPreviewPolicy
import org.junit.Assert.*
import org.junit.Test

class LegacyMediaPreviewPolicyTest {
    @Test fun permitsOnlyExplicitWellFormedHttpsLegacyHosts() {
        assertTrue(LegacyMediaPreviewPolicy.canRequestPreview("https://cdn.example.com/photos/123.jpg"))
        assertTrue(LegacyMediaPreviewPolicy.canRequestPreview("https://images.example.org/path?q=1"))
    }

    @Test fun refusesAutoUnsafeOrMalformedMediaTargets() {
        val bad = listOf(
            null, "", "  ", "http://cdn.example.com/image.jpg",
            "file:///data/data/private", "content://some.provider/picture",
            "javascript:alert(1)", "https:///picture", "https://user:secret@cdn.example.com/a",
            "https://localhost/a", "https://foo.local/a",
            "https://127.0.0.1/a", "https://192.168.1.1/a",
            "https://10.1.2.3/a", "https://172.16.1.1/a", "https://169.254.3.1/a",
            "https://0.0.0.0/a", "https://[::1]/a",
            "https://example.com/a\\nb", "https://example.com/has space",
            "https://example.com/" + "x".repeat(2050)
        )
        bad.forEach { assertFalse("Rejected unsafe legacy URL: ${it?.take(32)}",
            LegacyMediaPreviewPolicy.canRequestPreview(it)) }
    }

    @Test fun ordinaryPrivateChatMessageWithoutMediaNeverTriggersNetwork() {
        assertFalse(LegacyMediaPreviewPolicy.canRequestPreview(null))
    }
}
