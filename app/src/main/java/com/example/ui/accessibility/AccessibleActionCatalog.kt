package com.example.ui.accessibility

/**
 * Stable, human-readable action names for TalkBack and switch access.
 * A description cannot be a test tag or an icon glyph.
 */
object AccessibleActionCatalog {
    const val MIN_TOUCH_TARGET_DP = 48
    enum class Action { CALCULATOR_TOOLS, HISTORY, UPDATES, END_CALL, MUTE_MIC, UNMUTE_MIC, CAMERA_ON, CAMERA_OFF, SWITCH_CAMERA }
    fun description(action: Action): String = when (action) {
        Action.CALCULATOR_TOOLS -> "Diğer hesaplama araçları"
        Action.HISTORY -> "Hesap geçmişi"
        Action.UPDATES -> "Güncellemeleri kontrol et"
        Action.END_CALL -> "Görüşmeyi sonlandır"
        Action.MUTE_MIC -> "Mikrofonu sustur"
        Action.UNMUTE_MIC -> "Mikrofonu yeniden aç"
        Action.CAMERA_ON -> "Kamerayı etkinleştir"
        Action.CAMERA_OFF -> "Kamerayı devre dışı bırak"
        Action.SWITCH_CAMERA -> "Ön ve arka kamera arasında geçiş yap"
    }
}
