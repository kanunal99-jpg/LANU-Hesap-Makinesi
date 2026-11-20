# Message recovery: retained network demand and fair unresolved outbox

The report concerns messages sent by the peer since 15:00 that are absent on the recipient. Production read-only aggregates showed all33 server-stored messages for20November with delivery receipts (max11.64s), not proof that phone-local queued messages were sent or that a particular phone showed them. Never mark the incident resolved from aggregate counts; physical sender/recipient reconciliation remains pending.

Two source-confirmed hardening gaps are fixed without replacing transport or changing consent/schema/keys:

* A validated network-route callback previously used KEEP even while a recovery worker had already taken its server snapshot. Retain one trailing pass using WorkManager APPEND_OR_REPLACE only when a worker is RUNNING and no ENQUEUED/BLOCKED pass exists. Serialize scheduling off the main thread, await insertion before another decision, and recheck consent under the existing opt-out gate. Repeated route flapping coalesces behind the future pass. Startup retains KEEP. No IDs/content enter work Data. Query/queue errors fall back to ordinary CONNECTED work plus existing periodic repair. Existing FCM and foreground HTTP paths are unchanged.
* Five queue-head messages lacking cached conversation-peer binding previously remained immediately eligible forever, starving the sixth valid conversation in bounded batches. Keep original ciphertext, UUID and status, persist a60-second retry deadline, and allow following eligible rows through on subsequent passes. Do not guess peers, reroute ciphertext, delete messages or invent delivery receipts.

Research: Android recommends persistent WorkManager work rather than process-only callbacks. KEEP ignores pending demand; APPEND_OR_REPLACE preserves trailing demand without cancelling a commit. Blindly appending every route change could grow an unbounded chain, hence the bounded state-aware coalescing. Periodic work is a fallback subject to Doze/battery policies, not an instant delivery promise.

Primary references:
https://developer.android.com/reference/androidx/work/ExistingWorkPolicy
https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work
https://developer.android.com/reference/androidx/work/PeriodicWorkRequest

Tests: block the first real worker after snapshot, request21 network recoveries, assert exactly one trailing work item, release and verify two syncs; opt-out prevents further scheduling. Run onAPI28/34. Room regression: five unresolved rows precede valid queued ciphertext; subsequent repository instance sends valid row while retaining all five unresolved ciphertexts/statuses. Full Android/server, API34 runtime, stable signer, signed OTA and public read-back are distinct gates. No backend deployment is needed for these client-only changes. Physical two-device15:00 incident remains unconfirmed.

Delivery-chain follow-up: an unseen incoming message in an explicitly deleted thread was discarded locally but still ACKed, setting server delivered_at despite no Room message. Preserve the thread tombstone (no automatic privacy override), quarantine bounded metadata with HIDDEN_CONVERSATION, withhold delivery/read ACK and revision completion, and keep paging unrelated messages. On explicit createConversation, replay the same held event and ACK only after authenticating and committing it. Explicitly deleted per-message history stays hidden. The prior regression expectation allowing ACK without persistence is corrected because it represented event disposal rather than recipient delivery. This does not retroactively recreate events that an older client already ACKed.
