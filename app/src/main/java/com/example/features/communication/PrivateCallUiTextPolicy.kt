package com.example.features.communication

/**
 * Only constant, reviewed copy may reach the call UI. Native WebRTC failures
 * can include SDP, addresses or device details; never render raw diagnostics,
 * including in a consented incoming-call dialog over the locked calculator.
 *
 * This policy does not authenticate calls or unlock the private area.
 */
internal object PrivateCallUiTextPolicy {
    private const val CLOSED = "Arama kapandı."
    private const val FAILED = "Görüşme tamamlanamadı."
    private const val UNKNOWN_NOTICE = "Arama bilgisi kullanılamıyor."

    private val safeTerminalMessages = setOf(
        "Güvenli alandan çıkıldığı için görüşme kapatıldı.",
        "Arama yanıtlanmadı.",
        "Bağlantı kurulamadı; farklı bir ağ deneyin.",
        "Arama süresi doldu.",
        "Arama reddedildi.",
        "Görüşme sonlandırıldı.",
        "Karşı taraf aramayı reddetti.",
        "Karşı taraf başka bir aramada.",
        "Karşı taraf görüşmeyi sonlandırdı.",
        "İzin verilmedi.",
        "Ağ bağlantısı kesildi.",
        "Bu cihazda görüşme motoru açılamadı.",
        "İki telefonda da güncel LANU güvenli alanı açık olmalı ve kişiler karşılıklı kayıtlı olmalıdır.",
        "Arama isteği sınırına ulaşıldı. Biraz bekleyip yeniden deneyin.",
        "Arama kurulamadı. İzinleri ve bağlantıyı kontrol edip yeniden deneyin."
    )
    private val safeNotices = safeTerminalMessages + setOf(
        "Android görüşmeyi arka planda sürdüremedi. Mikrofon iznini ve pil kısıtlamalarını kontrol edin.",
        "Görüşme için mikrofon, görüntülü arama için kamera izni gerekir.",
        "Mikrofon/kamera izni verilmedi."
    )

    fun terminal(phase: NativeCallPhase, privateAreaVisible: Boolean, raw: String): String {
        val fallback = if (phase == NativeCallPhase.FAILED) FAILED else CLOSED
        if (!privateAreaVisible) return fallback
        return raw.takeIf { it in safeTerminalMessages } ?: fallback
    }

    /** No idle diagnostic may be read by TalkBack over the locked calculator. */
    fun notice(privateAreaVisible: Boolean, raw: String?): String? {
        if (!privateAreaVisible || raw == null) return null
        return raw.takeIf { it in safeNotices } ?: UNKNOWN_NOTICE
    }
}
