package com.example

import com.example.core.messaging.RelayException
import com.example.core.messaging.RelayHttpResponseGate
import com.example.core.messaging.RelayResponseReader
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class RelayHttpResponseGateTest {
    @Test fun nonJsonAndOversizedHttpErrorsKeepOriginalStatusWithoutReadingBody() {
        for (status in listOf(302, 400, 401, 403, 409, 413, 429, 500, 503)) {
            var bodyTouched = false
            try {
                RelayHttpResponseGate.readSuccessBody(status, "17") {
                    bodyTouched = true
                    error("Untrusted HTML/oversized body must not be read")
                }
                fail("HTTP error must reject before body for status $status")
            } catch (e: RelayException) {
                assertEquals(status, e.status)
                assertEquals(17_000L, e.retryAfterMillis)
                assertFalse(e.message.orEmpty().contains("Untrusted HTML"))
            }
            assertFalse("Body consumed for status $status", bodyTouched)
        }
    }

    @Test fun authenticationAndRateLimitRemainDistinctForSafeRetryDecisions() {
        val auth = listOf(401, 403, 409)
        for (code in auth) {
            try {
                RelayHttpResponseGate.readSuccessBody(code, null) { "{}" }
                fail("HTTP error must throw")
            } catch (e: RelayException) {
                assertEquals(code, e.status)
            }
        }
        try {
            RelayHttpResponseGate.readSuccessBody(429, "999999999999999999999") { "{}" }
            fail("Rate limit must throw")
        } catch (e: RelayException) {
            assertEquals(429, e.status)
            assertEquals(60_000L, e.retryAfterMillis)
        }
    }

    @Test fun retryAfterSecondsIsBoundedAndMalformedValuesUseSafeDefault() {
        for ((header, expected) in listOf("0" to 5_000L, "9999" to 120_000L, "25" to 25_000L, "nope" to 60_000L)) {
            try {
                RelayHttpResponseGate.readSuccessBody(503, header) { "unreachable" }
                fail("503 must throw")
            } catch (e: RelayException) {
                assertEquals(expected, e.retryAfterMillis)
            }
        }
    }

    @Test fun successfulResponseStillStreamsThroughExistingPayloadSizeGuard() {
        var invoked = 0
        val body = RelayHttpResponseGate.readSuccessBody(200, null) {
            invoked++
            RelayResponseReader.read(ByteArrayInputStream("{}".toByteArray()), maxBytes = 4)
        }
        assertEquals("{}", body)
        assertEquals(1, invoked)
        try {
            RelayHttpResponseGate.readSuccessBody(200, null) {
                RelayResponseReader.read(ByteArrayInputStream("12345".toByteArray()), maxBytes = 4)
            }
            fail("Successful but oversized body must remain rejected")
        } catch (e: RelayException) {
            assertEquals(503, e.status)
        }
    }
}
