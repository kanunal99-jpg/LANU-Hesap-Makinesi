package com.example.ui.accessibility

/**
 * Stable, human-readable action names for TalkBack and switch access.
 * A description cannot be a test tag or an icon glyph.
 */
object AccessibleActionCatalog {
    const val MIN_TOUCH_TARGET_DP = 48
    enum class Action {
        CALCULATOR_TOOLS, HISTORY, UPDATES, END_CALL,
        MUTE_MICROPHONE, SWITCH_CAMERA, ANSWER_CALL, DECLINE_CALL,
        PRIVATE_DIAGNOSTICS, BACK_TO_CALCULATOR,
        OPEN_PRIVATE_CHAT, START_PRIVATE_CONTACT_CHAT
    }
    fun description(action: Action): String = when (action) {
        Action.CALCULATOR_TOOLS -> "Diğer hesaplama araçları"
        Action.HISTORY -> "Hesap geçmişi"
        Action.UPDATES -> "Güncellemeleri kontrol et"
        Action.END_CALL -> "Görüşmeyi sonlandır"
        Action.MUTE_MICROPHONE -> "Mikrofonu sessize al veya aç"
        Action.SWITCH_CAMERA -> "Ön ve arka kamera arasında geçiş yap"
        Action.ANSWER_CALL -> "Gelen görüşmeyi yanıtla"
        Action.DECLINE_CALL -> "Gelen görüşmeyi reddet"
        Action.PRIVATE_DIAGNOSTICS -> "Özel tanılama ekranını göster"
        Action.BACK_TO_CALCULATOR -> "Hesap makinesi ekranına geri dön"
        Action.OPEN_PRIVATE_CHAT -> "Seçili sohbeti aç"
        Action.START_PRIVATE_CONTACT_CHAT -> "Seçili kişiyle sohbet başlat"
    }
}
