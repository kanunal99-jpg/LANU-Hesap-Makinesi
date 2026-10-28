package com.example

import com.example.core.calls.CallAudioRouteLabelPolicy
import com.example.core.calls.CallAudioRoutePolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CallAudioRouteLabelPolicyTest {
    private fun label(
        running: Boolean, status: String,
        selected: Route? = null, pending: Route? = null
    ) = CallAudioRouteLabelPolicy.label(running, status, selected, pending)

    @Test fun bluetoothScoPendingIsNeverPresentedAsConnected() {
        val feedback = label(true, "BLUETOOTH_PENDING", pending = Route.BLUETOOTH_HEADSET)
        assertEquals("Bluetooth ses bağlantısı kuruluyor…", feedback)
        assertFalse(feedback.contains("Ses çıkışı: Bluetooth"))
    }

    @Test fun android12PendingRoutesNeverClaimBluetoothOrSpeakerConnected() {
        val bluetooth = label(true, "ROUTE_PENDING", pending = Route.BLUETOOTH_HEADSET)
        assertEquals("Ses çıkışı bağlanıyor: Bluetooth kulaklık", bluetooth)
        assertFalse(bluetooth.contains("Ses çıkışı: Bluetooth"))
        assertEquals("Ses çıkışı bağlanıyor: Hoparlör",
            label(true, "ROUTE_PENDING", pending = Route.SPEAKER))
        assertEquals("Ses çıkışı: Doğrulanamadı",
            label(true, "ROUTE_PENDING"))
    }

    @Test fun verifiedHardwareRouteCanBeShownAsActive() {
        assertEquals("Ses çıkışı: Bluetooth kulaklık", label(true, "ROUTED", Route.BLUETOOTH_HEADSET))
        assertEquals("Ses çıkışı: Hoparlör", label(true, "ROUTED", Route.SPEAKER))
        assertEquals("Ses çıkışı: Ahize", label(true, "ROUTED", Route.EARPIECE))
        assertEquals("Ses çıkışı: Kablolu / USB kulaklık", label(true, "ROUTED", Route.WIRED_HEADSET))
    }

    @Test fun legacyApiSpeakerRequestIsNotFalselyVerified() {
        assertEquals("Ses çıkışı isteği: Hoparlör", label(true, "PLATFORM_ROUTE_REQUESTED", Route.SPEAKER))
    }

    @Test fun unsupportedOrMissingRoutesReportSystemOrUnverifiedState() {
        assertEquals("Ses çıkışı: Sistem tarafından yönetiliyor", label(true, "SYSTEM_DEFAULT"))
        assertEquals("Ses çıkışı: Doğrulanamadı", label(true, "ROUTED"))
        assertEquals("Ses çıkışı: Doğrulanamadı", label(true, "BLUETOOTH_PENDING"))
    }

    @Test fun deniedPermissionAndUnavailableAudioModeHaveDistinctMessages() {
        assertEquals("Ses yönlendirme izni verilmedi", label(false, "PERMISSION_DENIED"))
        assertEquals("Görüşme ses modu kullanılamıyor", label(false, "AUDIO_MODE_UNAVAILABLE"))
        assertEquals("Görüşme sesi kapalı", label(false, "NO_ACTIVE_CALL"))
    }

    @Test fun staleSelectionAfterCloseNeverClaimsLiveRoute() {
        assertEquals("Görüşme sesi kapalı", label(false, "ROUTED", Route.BLUETOOTH_HEADSET))
    }
}
