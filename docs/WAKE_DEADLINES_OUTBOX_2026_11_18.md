# 18 November 2026 — delayed-message follow-up

Baseline: main 229ab153 / signed APK 1319 / production relay v19. No message, identity, token or database migration is required by this patch. Secret calculator entry, PIN, Room, opt-in settings and APK signing identity remain unchanged.

## Incident evidence and limits

The reported window is 12:00–13:00 Türkiye (09:00–10:00 UTC), with discovery around 16:40. Aggregate-only reads on 18 November found no currently retained message or message-wake claim created in that window. Nearby retained messages were one in the 11:00 local hour and two in the 15:00 local hour; all three have delivered/read timestamps, maximum server-created-to-delivered delay 3.773 seconds. The window's Edge logs show 20 HTTP 200 and two WebSocket 101 responses, max execution 2037 ms. These aggregates are **not proof** that the reported message was never sent, that it was one of these three, or that the recipient displayed it promptly. We have no sender outbox history, installed-version evidence or per-message historical FCM outcomes. Thus the exact historical incident cause remains unproven.

## Reproduced weaknesses and repairs

1. **Outgoing WorkManager race:** KEEP ignores a new enqueue after the running job has already read the outbox. APPEND_OR_REPLACE retains the existing job and chains a follow-up; failed/cancelled chains can recover. Network, battery and Wi-Fi consent constraints remain intact; opt-out cancels the chain. Foreground and consented 15-minute periodic delivery remain independent fallbacks. This can explain a delayed send, but is not attributed to this incident without sender-device evidence.
2. **Google response-body hang:** the old timeout ended when headers arrived. OAuth JSON/FCM error bodies could hang beyond the deadline and hold the shared OAuth refresh promise. Requests now have a 10-second deadline across headers plus complete body, maximum 64 KiB streamed body, abort/cancel and timer cleanup. Stalled responses fail without deleting ciphertext or registrations.
3. **Incorrect FCM backoff:** old retries used 450/1350 ms regardless of Retry-After. New bounded three-attempt retries respect Retry-After seconds/date, exponential minimum 10 seconds plus jitter and a minimum 60 seconds for 429. Retry budget is limited by the ORIGINAL 45-second call / 120-second message wake lifetime; outgoing TTL shrinks with elapsed time. If Google's required pause exceeds the wake lifetime, stop that optional hint safely. Durable messages remain available through normal authenticated sync. This is not a new independent push provider or guaranteed background receipt.
4. **Missing transport outcomes:** fixed `LANU push call|message: accepted|expired|backpressure|unavailable|unregistered` logs now distinguish Google acceptance from failure. No message text, identity, peer, token, key, caller or call ID is logged. Google acceptance is never recipient delivery or ringing proof. Logging failure cannot fail a send.

Only explicit FCM UNREGISTERED retains existing compare-and-delete token invalidation; unrelated 404, auth, rate limit and transient failures cannot erase a registration. Existing ECDSA/E2EE, idempotency, durable send before optional push and EdgeRuntime.waitUntil isolation remain unchanged. No paid provider, public TURN, automatic opt-in or schema deletion was introduced.

## Verification gates

Tests cover stalled and oversized bodies, timer cleanup, Retry-After dates/seconds/invalid/overflow values, quota minimum, jitter, stale hints, decreasing retry TTL, unchanged hint timestamp, permanent errors, shared OAuth/recovery, durable message acceptance despite optional push failure, follow-up WorkManager ordering and opt-out cancellation on API28/34. Full server suite and Android CI must pass on the final PR head before merge. Main CI, signed OTA and release download/checksum/signer evidence are separate gates, recorded on the PR after completion.

Official references: https://firebase.google.com/docs/cloud-messaging/scale-fcm and https://firebase.google.com/docs/cloud-messaging/error-codes .

## Remaining acceptance

Two physical phones (especially Samsung A55), application closed/locked, send/receive before opening the app, green/red call controls and authenticated remote ringing must be observed on the new installed version. Force-stop and Samsung deep-sleep can block background work by OS policy. Huawei without Google services has no verified independent push provider. Neither these physical tests nor the exact noon incident cause is claimed complete by server/CI success. Do not uninstall or clear app data during acceptance.
