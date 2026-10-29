# LANU — remaining ten tracked issues: implementation / acceptance boundary
Checkpoint: 2026-10-29. Main baseline `e5de3b7`, published signed APK `v1.2.0-signed.903`.
This page is a **test/acceptance ledger**, not a claim that all issues have passed.

## What can be closed from automation alone?
**None of the ten issues are declared closed solely by this document.**
The safe improvements in this PR are narrow: scientific precision admission (#5),
FCM/call-hint timestamp overflow guards (#133/#3), audio-route regression (#245),
and CI protection of safe system insets on the update page (#84).
CI must pass on this PR's final HEAD; a green PR does not mean the physical or legal
acceptance below has happened.

| Issue | Source-level / CI evidence available | Remaining independent acceptance | Risk priority |
| --- | --- | --- | --- |
| #245 OEM call audio routing | API31+ pending device ID/type/permission and listener callback policy; new revoked Bluetooth/hotplug regression | Samsung A55 + second OEM Bluetooth HFP/BLE/wired, live bidirectional audio and teardown logs without sensitive identifiers | P1 |
| #224 owner-account erasure and KVKK | Local deletion UI acknowledged; staged fail-closed server delete proposal #266 | Verified owner identity/support, public usable HTTPS request page, audited authorized server erasure, storage object cleanup, RLS/rollback tests, human legal approval | P0 |
| #177 quality 11-category matrix | Historical merged #256 and CI/signed APK #749; latest #903 publication | Current code/CI evidence **and** human/device acceptance per category; do not raise numeric scores automatically | P1 |
| #133 background call wake / FCM | Consent, timestamp, neutral notification policies; new time-bound overflow negatives | Release APK Firebase config+server secret *presence* confirmed without exposing secrets, 2 registered devices, screen locked/Doze/DND/OEM call invitation/notification tests | P0 |
| #114 double ratchet / forward secrecy | Current encrypted messages do NOT prove post-compromise secrecy | Threat model, license review, authenticated version negotiation, key rotation, downgrade/offline/multi-device matrix, external crypto assessment, backwards compatibility | P1 |
| #100 production hardening | Hilt baseline, R8/minified release, unit tests and Android 14 emulator | Opt-in privacy-safe crash diagnostics, Samsung/Huawei/Xiaomi tests, low memory, ANR, API level/memory and real upgrade acceptance | P1 |
| #84 OTA system bar overlap | Compose public update route uses safeDrawing; new static CI regression prevents removal/order mistake | Actual A55 Android 16 screenshot under gesture/3-button nav, 200% font/large display and landscape; button must not overlap status/navigation bars | P2 |
| #83 signing key disaster recovery | CI exercises only fake, generated signing keys, encrypted archive recovery and certificate mismatch rejection | Owner locally creates **encrypted off-device** backup, isolates and restores privately, proves same signing fingerprint + a real signing challenge; never store key/password in GitHub/chat | P0 |
| #5 large-number math functions | Normal exponent budget guards; new direct sqrt/cbrt/ln/sin/cos/tan/asin/acos/atan context guard and negative JUnit | Boundary/error budget at 34/100 digits; real A55 stress/ANR, accuracy/round-trip independent reference checks | P1 |
| #3 two-phone WebRTC | Real ICE/DTLS-SRTP call code, direct call answer #312 merged/signed #903; owner earlier reported two-way voice+video success | Two-device evidence for reconnect/timeout, physical camera, permissions, Bluetooth, audio quality, network switching, background/wake and safe teardown | P1 |

## Do not automate irreversible operations
- Never run live SQLCipher Room migration, account/server deletion, media storage
  sweeping, message key rotation or a private signing-key export as part of
  regression tests or release scripts.
- No extra paid TURN/FCM plans or telemetry uploads without owner approval.
- Never display or write private identity, contacts, credentials, PIN or call
  metadata on public calculator surfaces or public system notifications.
- Run final HEAD unit/Robolectric, Android14 DB runtime, Android Lint, minified
  APK R8, signer's SHA256 continuity, non-destructive emulator OTA seed/restore,
  public GitHub Release APK checksum read-back *before* labeling a new build published.
- Physical tests record pass/fail, device API/build, relevant sanitized screenshot
  and user authorization only; no secrets, caller IDs or message content.

## Scope of this PR and deferred work
All ten issue IDs remain open until their individual acceptance criteria pass.
The automated fixes do not implement legal account deletion, live SQLCipher,
Double Ratchet, new media transport, Apple/Google Play publication, or an
OEM device lab. PR #313 remains a separate two-issue review (#85/#113).
