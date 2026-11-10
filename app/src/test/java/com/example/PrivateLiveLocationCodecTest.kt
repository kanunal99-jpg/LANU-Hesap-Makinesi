package com.example

import com.example.core.location.PrivateLiveLocationCodec as Codec
import com.example.core.location.PrivateLocationCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PrivateLiveLocationCodecTest {
    private val id = UUID.randomUUID().toString()
    private val started = 1_794_000_000_000L
    private val expires = started + Codec.FIFTEEN_MINUTES_MS
    private val point = PrivateLocationCodec.Point(
        41.015137, 28.979530, 12, started + 20_000L, false
    )

    private fun start() = Codec.Event(Codec.Kind.START, id, 0, started, expires)
    private fun updated() = Codec.Event(Codec.Kind.POINT, id, 1, started, expires, point)
    private fun stopped() = Codec.Event(Codec.Kind.STOP, id, 2, started, expires,
        stoppedAtMs = started + 30_000L)

    @Test fun encryptedChannelEventsHaveCanonicalStrictRoundTrip() {
        for (event in listOf(start(), updated(), stopped())) {
            val encoded = Codec.encode(event)
            assertTrue(Codec.isLiveLocationMessage(encoded))
            assertEquals(event, Codec.decodeOrNull(encoded))
            assertFalse(encoded.contains("https://"))
            assertTrue(encoded.length < 230)
        }
        assertEquals(Codec.Kind.POINT, Codec.decodeOrNull(Codec.encode(updated()))?.kind)
    }

    @Test fun onlyUserAllowedDurationsAndBoundedUpdateCadence() {
        assertEquals(30_000L, Codec.intervalMs(Codec.FIFTEEN_MINUTES_MS))
        assertEquals(60_000L, Codec.intervalMs(Codec.ONE_HOUR_MS))
        assertEquals(180_000L, Codec.intervalMs(Codec.EIGHT_HOURS_MS))
        assertFalse(Codec.expired(expires, expires - 1))
        assertTrue(Codec.expired(expires, expires))
        assertTrue(Codec.fresh(started, started + 120_000L))
        assertFalse(Codec.fresh(started, started + 120_001L))
        assertFalse(Codec.fresh(started + 1, started))
        assertTrue(Codec.MAX_POINTS_PER_SESSION <= 256)
        for (wrong in listOf(-1L, 0L, 5_000L, 17 * 60_000L)) {
            assertTrue(runCatching { Codec.intervalMs(wrong) }.isFailure)
            assertTrue(runCatching {
                Codec.encode(start().copy(expiresAtMs = started + wrong))
            }.isFailure)
        }
    }

    @Test fun invalidUntrustedSenderContentNeverBecomesAMapLink() {
        val original = Codec.encode(updated())
        val malformed = listOf(
            "lanu-live-v1:", original + ",forged", original + "\n",
            original.replace(id, UUID.randomUUID().toString().uppercase()),
            original.replace(",1,", ",-1,"),
            original.replace(",1,", ",257,"),
            original.replace("41.015137", "999.000000"),
            original.replace("41.015137", "NaN"),
            original.replace("28.979530", "28.9795300"),
            original.replace(",12,", ",0,"),
            original.replace(",P", ",A"),
            original.replace(expires.toString(), (started - 100L).toString()),
            " ".repeat(300)
        )
        malformed.forEach { assertNull("Rejected: " + it.take(40), Codec.decodeOrNull(it)) }
    }

    @Test fun oldSessionsAndPointsOutsideExpiryFailClosed() {
        assertTrue(runCatching {
            Codec.encode(updated().copy(point = point.copy(
                capturedAtMillis = started - 60_001L)))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(updated().copy(point = point.copy(
                capturedAtMillis = expires + 1)))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(stopped().copy(stoppedAtMs = expires + 1))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(start().copy(sequence = 1))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(updated().copy(sequence = 0))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(stopped().copy(sequence = 0))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(updated().copy(sessionId = "not-a-uuid"))
        }.isFailure)
        assertTrue(runCatching {
            Codec.encode(stopped().copy(point = point))
        }.isFailure)
    }

    @Test fun approximateLocationIsRoundedBeforeBeingPlacedInEvent() {
        val approximate = PrivateLocationCodec.fromDevice(
            41.015137, 28.979530, 8.2f, started + 30_000L, true
        )
        val event = updated().copy(point = approximate)
        val encryptedPlaintext = Codec.encode(event)
        assertFalse(encryptedPlaintext.contains("41.015137"))
        assertTrue(encryptedPlaintext.contains("41.02,28.98"))
        assertEquals(event, Codec.decodeOrNull(encryptedPlaintext))
        assertTrue(PrivateLocationCodec.mapsSearchUrl(approximate)
            .contains("41.02%2C28.98"))
    }
}
