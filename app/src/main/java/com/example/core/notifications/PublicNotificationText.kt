package com.example.core.notifications

/**
 * Public Android notification surfaces must be indistinguishable by wording:
 * no message/call/identity/PIN terminology in titles, channel labels or
 * descriptions. The user's separate consent and lockscreen visibility still
 * apply; this does not make Android notification history invisible.
 */
object PublicNotificationText {
    const val TITLE = "LANU Hesap Makinesi"
    const val CHANNEL_NAME = "LANU bildirimleri"
    const val CHANNEL_DESCRIPTION = "Uygulama bildirimleri"
}
