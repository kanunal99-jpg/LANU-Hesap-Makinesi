package com.example

import com.example.core.location.PrivateLocationCodec as Codec
import org.junit.Assert.*
import org.junit.Test

/** Canonical encrypted-message payload and map privacy regression checks. */
class PrivateLocationCodecTest {
    private val precise = Codec.Point(
        latitude = 41.015137,
        longitude = 28.979530,
        accuracyMeters = 11,
        capturedAtMillis = 1_794_000_000_000L,
        approximate = false
    )

    @Test fun precisePointIsCanonicalAndDecodesExactly() {
        val raw = Codec.encode(precise)
        assertTrue(Codec.isLocationMessage(raw))
        assertEquals(precise, Codec.decodeOrNull(raw))
        assertTrue(raw.startsWith("lanu-location-v1:41.015137,28.979530,11,"))
        assertFalse(raw.contains("https://"))
    }

    @Test fun approximatePointDropsGpsPrecisionBeforeEncryption() {
        val approximation = Codec.fromDevice(
            41.015137, 28.979530, 12.4f, 1_794_000_000_000L, true
        )
        assertEquals(41.02, approximation.latitude, 0.000001)
        assertEquals(28.98, approximation.longitude, 0.000001)
        assertTrue(approximation.accuracyMeters >= 1_500)
        val envelope = Codec.encode(approximation)
        assertFalse(envelope.contains("41.015137"))
        assertEquals(approximation, Codec.decodeOrNull(envelope))
        assertTrue(Codec.mapsSearchUrl(approximation).contains("41.02%2C28.98"))
    }

    @Test fun invalidCoordinatesAndAccuracyAreRejected() {
        val invalid = listOf(
            precise.copy(latitude = 90.1), precise.copy(longitude = -180.1),
            precise.copy(latitude = Double.NaN),
            precise.copy(longitude = Double.POSITIVE_INFINITY),
            precise.copy(accuracyMeters = 0), precise.copy(accuracyMeters = 200_001),
            precise.copy(capturedAtMillis = -5)
        )
        invalid.forEach { assertTrue(runCatching { Codec.encode(it) }.isFailure) }
        assertTrue(runCatching {
            Codec.fromDevice(41.0, 28.0, Float.NaN, 1_794_000_000_000L, false)
        }.isFailure)
    }

    @Test fun untrustedMessagesCannotInjectMapsUrlsOrSmuggleCoordinates() {
        val valid = Codec.encode(precise)
        val malformed = listOf(
            "", "some text", "lanu-location-v1:",
            valid + ",injected", valid + "\n", valid + "https://evil.example",
            valid.replace("41.015137", "NaN"),
            valid.replace("11,", "-1,"),
            valid.replace("11,", "0,"),
            valid.replace("11,", "200001,"),
            valid.replace("P", "A"),
            valid.replace("41.015137", "41.0151370"),
            valid.replace("28.979530", "28,979530"),
            valid.replace("41.015137", "99999"),
            " ".repeat(130)
        )
        malformed.forEach { assertNull("Rejected: ${it.take(50)}", Codec.decodeOrNull(it)) }
    }

    @Test fun linksCreatedOnlyFromValidatedCoordinates() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=41.015137%2C28.979530",
            Codec.mapsSearchUrl(precise)
        )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=41.015137%2C28.979530",
            Codec.mapsDirectionsUrl(precise)
        )
    }
}
