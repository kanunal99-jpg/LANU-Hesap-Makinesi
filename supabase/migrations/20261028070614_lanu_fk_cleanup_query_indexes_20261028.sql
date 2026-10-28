-- Applied to live LANU Supabase as migration 20261028070614 (28 Oct 2026).
-- Additive performance hardening: account/contact cleanup should find both
-- sides of identity FKs by indexed owner/recipient lookups.
-- Does not change messages, FCM tokens, media, identity, RLS, grants or keys.
create index if not exists lanu_media_recipient_fk_lookup
  on public.lanu_media_objects(recipient_id);
create index if not exists lanu_message_wake_claim_sender_fk_lookup
  on public.lanu_message_wake_claims(sender_id);
