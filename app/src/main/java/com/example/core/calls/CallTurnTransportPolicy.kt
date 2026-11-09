package com.example.core.calls

/** Same authorized host/port only. Never downgrade TURNS, invent a server or duplicate credentials. */
@androidx.annotation.Keep
object CallTurnTransportPolicy {
    fun urls(value: CallTurnConfig.Value): List<String> {
        CallTurnConfig.validate(value)
        if (!value.tryOtherTransport || value.url.startsWith("turns:")) return listOf(value.url)
        val alternate = if (value.url.endsWith("transport=udp")) value.url.removeSuffix("udp") + "tcp"
            else value.url.removeSuffix("tcp") + "udp"
        return listOf(value.url, alternate)
    }
}
