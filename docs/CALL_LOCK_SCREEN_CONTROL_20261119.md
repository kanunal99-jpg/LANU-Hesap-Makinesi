# Call-only lock screen visibility shortcut

Add “Kilit ekranında göster” to the unlocked private call settings. It opens the exact existing incoming-call category (v5) using the public ACTION_CHANNEL_NOTIFICATION_SETTINGS API. If OEM settings reject this route, use app notification settings then application details. No new channel, global visibility write, notification opt-in, network or media operation occurs in this helper. The action stays available even while calls are off/Android blocks enrollment.

Read actual app permission/channel state on resume: OFF, blocked, SECRET, PRIVATE, PUBLIC, system-controlled/missing, unavailable. PUBLIC is described only as permitting generic call visibility; Samsung’s global hide-content/lockscreen policy and full-screen special access remain separate. Never display a fake “enabled” switch or claim that pressing the shortcut changed Android. Existing ringtone/full-screen controls and message SECRET notifications remain intact. No new channel version; no user mute or DND changes.

Test exact category ID, mute preservation, opt-out without channel creation/enrollment, secret/private/public/missing/blocked reports, OEM failures and API23 app-details fallback, plus real Compose action taps without fake success. Run full Android/server CI and signed OTA/public APK read-back. Samsung A55 physical global-hide-content behavior remains a separate manual acceptance.

A separate “İçeriği gizle ayarını kontrol et” action opens only this app’s notification page, allowing Samsung’s per-app content-visibility check when the category submenu has no Show option. It never opens/writes global all-app lock-screen settings.
