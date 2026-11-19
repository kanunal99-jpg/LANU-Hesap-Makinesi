# Pending alert cleanup and batch publication

Conversation deletion/clear and message deletion/expiry remove pending metadata in the same Room transaction, independent of notification preference. Local startup pruning repairs legacy orphans without network or enrollment. Global SQL cleanup removes absent messages/threads, READ, zero unread count, hidden/expired messages and hidden threads before the 200-item publication batch. Valid unread rows are retained without a new arbitrary TTL.

Eligibility uses one joined identifier-only query per batch, including the final atomic unread check. Completion deletes keys in a single batch. Room schema v4 and message/identity/outbox data are unchanged. Accepted batches advance through all valid rows without OFFSET; invalid cleanup has no LIMIT. No recurring cleanup job is added.

QA reproductions from draft #390 are included. Deletion with notifications off and 401 stale rows now require safe results. The external Android-post/Room-commit ambiguity is intentionally a characterization expecting TWO posts after lost Android state, by user instruction; it is not fixed or described as exactly once. SQL regression budget is at most 60 statements for two rejected 200-row passes, versus the measured 4034 baseline. Timing is diagnostic, not a physical device/battery claim.

Validation pending final-head Android CI and server tests. Physical Samsung A55/two-phone acceptance is unavailable in this workspace.
