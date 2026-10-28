package com.example.core.calls

import com.example.core.calls.CallAudioRoutePolicy.Route

/**
 * Honest, locale-specific spoken/written status for real Android call audio.
 * A selected API23-30 route is only requested, not a proven active device.
 * Bluetooth SCO must never be announced as connected until its callback.
 *
 * Presentation only: never opens a microphone, triggers a permission prompt,
 * changes Android volume, or promises headset hardware ANC/noise reduction.
 */
object CallAudioRouteLabelPolicy {
    fun label(
        running: Boolean,
        status: String,
        selected: Route?,
        pending: Route?
    ): String = when {
        !running -> when (status) {
            "PERMISSION_DENIED" -> "Ses yönlendirme izni verilmedi"
            "AUDIO_MODE_UNAVAILABLE" -> "Görüşme ses modu kullanılamıyor"
            else -> "Görüşme sesi kapalı"
        }
        status == "BLUETOOTH_PENDING" && pending == Route.BLUETOOTH_HEADSET ->
            "Bluetooth ses bağlantısı kuruluyor…"
        status == "ROUTE_PENDING" && pending != null ->
            "Ses çıkışı bağlanıyor: ${routeName(pending)}"
        status == "ROUTED" && selected != null ->
            "Ses çıkışı: ${routeName(selected)}"
        status == "PLATFORM_ROUTE_REQUESTED" && selected != null ->
            "Ses çıkışı isteği: ${routeName(selected)}"
        status == "SYSTEM_DEFAULT" -> "Ses çıkışı: Sistem tarafından yönetiliyor"
        else -> "Ses çıkışı: Doğrulanamadı"
    }

    fun routeName(route: Route): String = when (route) {
        Route.WIRED_HEADSET -> "Kablolu / USB kulaklık"
        Route.BLUETOOTH_HEADSET -> "Bluetooth kulaklık"
        Route.EARPIECE -> "Ahize"
        Route.SPEAKER -> "Hoparlör"
    }
}
