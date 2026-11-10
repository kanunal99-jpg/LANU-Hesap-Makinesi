package com.example.core.location

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * A small, canonical, opt-in location snapshot transported ONLY as the
 * plaintext argument to the existing PeerMessageCrypto E2EE message queue.
 *
 * No external map URL, identity, background session or raw provider identifier
 * is ever persisted by this codec. Older LANU clients may show the marker as
 * text; they cannot start location capture from a received message.
 */
object PrivateLocationCodec {
    private const val PREFIX = "lanu-location-v1:"
    private const val MIN_TIMESTAMP_MS = 946_684_800_000L // 2000-01-01
    private const val MAX_TIMESTAMP_MS = 4_102_444_800_000L // 2100-01-01
    private const val MAX_LENGTH = 120

    data class Point(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Int,
        val capturedAtMillis: Long,
        val approximate: Boolean
    )

    fun isLocationMessage(text: String): Boolean = text.startsWith(PREFIX)

    fun encode(point: Point): String {
        require(point.latitude.isFinite() && point.latitude in -90.0..90.0)
        require(point.longitude.isFinite() && point.longitude in -180.0..180.0)
        require(point.accuracyMeters in 1..200_000)
        require(point.capturedAtMillis in MIN_TIMESTAMP_MS..MAX_TIMESTAMP_MS)
        val digits = if (point.approximate) 2 else 6
        val lat = String.format(Locale.US, "%.${digits}f", point.latitude)
        val lon = String.format(Locale.US, "%.${digits}f", point.longitude)
        // Rounding at poles or 180 meridian must not leave coordinate bounds.
        val accuracy = if (point.approximate) max(1_500, point.accuracyMeters)
            else point.accuracyMeters
        return PREFIX + listOf(lat, lon, accuracy, point.capturedAtMillis,
            if (point.approximate) "A" else "P").joinToString(",")
    }

    /** Strict grammar and canonical round-trip; reject malformed/untrusted data. */
    fun decodeOrNull(text: String): Point? {
        if (!isLocationMessage(text) || text.length > MAX_LENGTH || text != text.trim()) return null
        val parts = text.removePrefix(PREFIX).split(',')
        if (parts.size != 5 || parts.any { it.isEmpty() }) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        val accuracy = parts[2].toIntOrNull() ?: return null
        val timestamp = parts[3].toLongOrNull() ?: return null
        val approximate = when (parts[4]) {
            "A" -> true
            "P" -> false
            else -> return null
        }
        val point = Point(latitude, longitude, accuracy, timestamp, approximate)
        return if (runCatching { encode(point) }.getOrNull() == text) point else null
    }

    fun fromDevice(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Float,
        capturedAtMillis: Long,
        approximate: Boolean
    ): Point {
        require(horizontalAccuracyMeters.isFinite() &&
            horizontalAccuracyMeters > 0f && horizontalAccuracyMeters <= 200_000f)
        val accuracy = max(1, ceil(horizontalAccuracyMeters.toDouble()).toInt())
        val candidate = Point(latitude, longitude, accuracy, capturedAtMillis, approximate)
        // Normalize to exactly the precision being shared, not the raw GPS fix.
        return decodeOrNull(encode(candidate))
            ?: throw IllegalArgumentException("Konum doğrulanamadı")
    }

    /** Official Maps URLs: explicit tap only, no API key and no map load on chat open. */
    fun mapsSearchUrl(point: Point): String {
        val safe = decodeOrNull(encode(point)) ?: error("Geçersiz konum")
        val coord = coordinateQuery(safe)
        return "https://www.google.com/maps/search/?api=1&query=$coord"
    }

    fun mapsDirectionsUrl(point: Point): String {
        val safe = decodeOrNull(encode(point)) ?: error("Geçersiz konum")
        return "https://www.google.com/maps/dir/?api=1&destination=${coordinateQuery(safe)}"
    }

    private fun coordinateQuery(point: Point): String {
        val digits = if (point.approximate) 2 else 6
        return String.format(Locale.US, "%.${digits}f%%2C%.${digits}f",
            point.latitude, point.longitude)
    }
}
