-- Design r13 §2.6 (the launcher's "Be om mer tid"): each parent's Notify Me About switch for a phone
-- asking for more screen time (per parent, design 62 §5; on by default for every parent, existing ones
-- included, the user 2026-10-25; no row = on, migration 0088). The events themselves are
-- `parent_events` rows of kind `extra_time_request`, stored as a key plus `params` (design 64's way).
ALTER TABLE notification_prefs ADD COLUMN push_extra_time INTEGER NOT NULL DEFAULT 1;
