# Six QA findings — implementation and acceptance

Baseline: main 790e9cc8, release1322, relay v20. Existing calculator/PIN, identity, Room, E2EE, ACK, call controls, opt-ins and stable signing identity are preserved. No message/history reset or paid provider is introduced. Exact attribution of the historical noon incident and physical two-phone acceptance remain separate.

## 1. Critical: poisoned first event page

Add `lanu_message_event_page`: keyset traversal over the signed owner's events, same media gate and maximum200. The old RPC is unchanged. New Android clients opt into eventPaging1, persist only the last event UUID in Room settings, move beyond a fully rejected page and wrap after traversal. A foreign/missing cursor cannot skip another owner's events. Cursor movement never marks delivery/read or deletes ciphertext. Background jobs process up to three pages in90seconds, then queue a follow-up if necessary. Old relays omit capability and clear the cursor safely.

## 2. Critical: nondurable message wake

An AFTER INSERT trigger atomically creates an opt-in message push job with the encrypted message. No existing messages are backfilled. Jobs contain message reference and scheduling state, never device tokens or content. A short lease, four-attempt cap, original120-second lifetime, live mutual/contact/consent checks, recipient throttling and stale-completion rejection govern dispatch. Message FCM transport uses one bounded attempt and returns Retry-After to the durable worker; call transport keeps its existing behavior. An idempotent send may request a drain again without reinserting ciphertext.

A pg_cron10-second tick invokes pg_net only while a due job exists. Each invocation uses a random256-bit,30-second, single-use ticket; only its SHA256 remains in a service-role-only table. No permanent project key is stored in the scheduler or sent to a client. Anonymous/authenticated roles cannot read jobs, mint/consume tickets or use the net schema. A process crash or lost completion is recovered by lease expiry. At-least-once notification dispatch is intentional; UUID message idempotency and existing generic-notification behavior remain in place. Google acceptance is not recipient delivery.

Migrations are additive. Install paging and outbox first, deploy the matching relay, then activate the scheduler migration. The existing safe-retention cron remains unchanged. pg_net uses the existing project; no external paid service/account is created. Message jobs stop at expiry without erasing messages. Staged account-erasure remains OFF; its existing schema-drift guard must continue to reject unreviewed dependencies including the new job FK.

Rollback: disable `lanu-message-push-outbox`, restore the previous seven-file relay bundle, and revert APK code via a higher signed version if needed. Keep the new tables/columns and all user data. Do not perform a destructive down migration.

## 3. Medium: handled sync failures

Database-lock/network/timeout failures get bounded retries. Security/storage/response failures use fixed local diagnostic categories and explicit WorkManager output. Handled failures do not cancel unrelated jobs in the APPEND chain. No exception text, peer, token or message is persisted in job output. True cancellation propagates.

## 4. Medium: OTA alternate route

Only official release asset URLs may fall back to anonymous GitHub asset API. Validate exact tag, non-draft release, unique asset name, uploaded state, numeric asset ID, expected repository URL and size. Existing HTTPS/redirect/host, SHA256, installed signer, package/version and Android installer checks remain mandatory. Security errors, cancellation and quota429 never trigger fallback. If a primary stream partially wrote bytes, alternate download restarts from zero to prevent duplicate appended bytes.

## 5. Medium: total OTA budget

Metadata has30seconds total; APK network/fallback has480seconds total; an individual HTTP call has240seconds. Existing cancellation owns the response stream/socket and releases the download mutex. Byte limits and streaming progress checks remain. Deadline expiry cannot launch an installer or delete app data.

## 6. Low: FCM priority diagnostics

Existing optional, RAM-only metrics now count call/message wakes, explicit HIGH-to-NORMAL downgrade and device-clock-estimated age over10seconds. No timestamps, tokens, IDs, text or uploads. Disable clears counters. Cold process starts keep the existing opt-out default; these are session diagnostics, not an authoritative server-to-device latency measurement.

## Validation

New real Postgres/PGlite tests cover200+event pagination, ownership, no false receipts, atomic queue, lost leases, stale completion, deferred retry, opt-out, single-use ticket/expiry, scheduler no-op and role grants. Dispatcher/FCM tests cover duplicate send drains, permanent/transient/unregistered failures and Retry-After without sleeping. Android tests cover Room cursor persistence/recreation, actual worker retry/output behavior, OTA fallback validation/cancellation/quota/deadline and metrics consent/reset.

Required gates: final-head PR CI, main CI, API34 runtime, R8/Lint, signed OTA preservation from828 and previous-stable, public APK/SHA/signer read-back, backend migration/function read-back and scheduler smoke. Physical A55/second-phone message/call acceptance cannot be replaced by emulators. Completion evidence is recorded on the PR; the existence of this document is not a claim that pending gates passed.
