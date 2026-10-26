package com.example.ui.accessibility

/**
 * Stable, human-readable action names for TalkBack and switch access.
 * A description cannot be a test tag or an icon glyph.
 */
object AccessibleActionCatalog {
    const val MIN_TOUCH_TARGET_DP = 48
    enum class Action { CALCULATOR_TOOLS, HISTORY, UPDATES, END_CALL }
    fun description(action: Action): String = when (action) {
        Action.CALCULATOR_TOOLS -> "Diğer hesaplama araçları"
        Action.HISTORY -> "Hesap geçmişi"
        Action.UPDATES -> "Güncellemeleri kontrol et"
        Action.END_CALL -> "Görüşmeyi sonlandır"
    }
}
