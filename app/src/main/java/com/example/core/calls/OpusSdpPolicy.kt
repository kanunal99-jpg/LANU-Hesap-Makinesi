package com.example.core.calls

/**
 * Opus receive-side preferences for the currently negotiated audio section.
 * RFC 7587/8854: in-band FEC is receiver capability, not a promise that
 * every lost packet will be recovered. Preserve all codec IDs and unknown fmtp.
 * DTX is opt-in for future physical quality tests, not forced on existing calls.
 */
internal object OpusSdpPolicy {
    fun preferReceiveFec(sdp: String, enableDtx: Boolean = false): String {
        if (!sdp.contains("m=audio ")) return sdp
        val separator = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val trailing = sdp.endsWith(separator)
        val lines = sdp.removeSuffix(if (trailing) separator else "").split(separator).toMutableList()
        var sectionStart = lines.indexOfFirst { it.startsWith("m=audio ") }
        if (sectionStart < 0) return sdp
        var sectionEnd = (sectionStart + 1 until lines.size).firstOrNull { lines[it].startsWith("m=") } ?: lines.size
        val payloads = lines[sectionStart].split(Regex("\\s+")).drop(3).toSet()
        val opus = (sectionStart + 1 until sectionEnd).firstOrNull { index ->
            val matched = Regex("^a=rtpmap:(\\d+) opus/48000/2\\s*$", RegexOption.IGNORE_CASE)
                .matchEntire(lines[index])
            matched != null && matched.groupValues[1] in payloads
        } ?: return sdp
        val payload = Regex("^a=rtpmap:(\\d+)", RegexOption.IGNORE_CASE)
            .find(lines[opus])?.groupValues?.get(1) ?: return sdp
        val fmtpPrefix = "a=fmtp:$payload"
        val existing = (sectionStart + 1 until sectionEnd).firstOrNull {
            lines[it].startsWith("$fmtpPrefix ")
        }
        if (existing != null) {
            val parameters = lines[existing].removePrefix("$fmtpPrefix ").split(';')
                .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (parameters.none { it.substringBefore('=').trim().equals("useinbandfec", ignoreCase = true) })
                parameters.add("useinbandfec=1")
            if (enableDtx && parameters.none {
                    it.substringBefore('=').trim().equals("usedtx", ignoreCase = true)
                }) parameters.add("usedtx=1")
            lines[existing] = "$fmtpPrefix " + parameters.joinToString(";")
        } else {
            lines.add(opus + 1, "$fmtpPrefix useinbandfec=1" + if (enableDtx) ";usedtx=1" else "")
        }
        return lines.joinToString(separator) + if (trailing) separator else ""
    }
}
