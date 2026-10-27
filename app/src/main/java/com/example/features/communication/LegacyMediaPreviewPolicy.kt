package com.example.features.communication

import java.net.URI

/**
 * Legacy attachments can carry old unencrypted external preview URLs. Opening
 * a private chat must NEVER automatically contact that unrelated host.
 *
 * This gate permits a MANUAL, explicitly confirmed HTTPS preview only. It is
 * not an SSRF, redirect, DNS rebinding or remote-tracking defense; users must
 * be warned that the host may see their IP address. Newly sent LANU private
 * attachments use the authenticated encrypted inline-media flow instead.
 */
internal object LegacyMediaPreviewPolicy {
    fun canRequestPreview(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank() || rawUrl.length > 2048 ||
            rawUrl.any { it.isWhitespace() || Character.isISOControl(it) }) return false
        val url = try { URI(rawUrl) } catch (_: java.net.URISyntaxException) { return false }
        if (!url.scheme.equals("https", ignoreCase = true) ||
            url.host.isNullOrBlank() || url.rawUserInfo != null) return false
        val host = url.host?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host == "0.0.0.0" ||
            host == "::1" || host == "[::1]" || host == "127.0.0.1") return false
        // Reject common numeric loopback/private IPv4 targets even with HTTPS.
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)) return false
        }
        return true
    }
}
