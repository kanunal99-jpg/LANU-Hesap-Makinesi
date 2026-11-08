package com.example

import com.example.core.media.PrivateMediaKeyEnvelope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PrivateMediaPayloadDepthTest {
    @Test fun deeplyNestedAuthenticatedTextCannotReachRecursiveMediaParser() {
        val text="{\"x\":".repeat(200)+"0"+"}".repeat(200)
        assertTrue(text.length<2048)
        assertFalse(PrivateMediaKeyEnvelope.isAuthenticatedMediaPayload(text))
        assertTrue(runCatching {PrivateMediaKeyEnvelope.openAuthenticatedPayload(text)}.isFailure)
    }
}
