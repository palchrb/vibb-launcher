-- Design 59 step 1 (docs/design/59-android-keyguard-design.md §2.1): Android's own lock screen,
-- per phone, and the Child PIN kept reversibly for it.
-- kid_keyguard: 'own' (Vibb One's lock, today's) or 'android' (Android's PIN = the Child PIN);
--   sent as kid_lock.keyguard. Clearing the Child PIN sets it back to 'own'.
-- kid_pin_sealed: the current Child PIN sealed with data/keys/kid-pin.key (kid_pin.rs), NULL for
--   a PIN saved before this release or while the key was unreadable.
-- kid_pin_rev: bumped at every Child PIN save or clear, sent as kid_lock.pin_rev; the phone
--   reports the rev its gate enforces (lock_state.pin_rev), never PIN material.
-- kid_pin_prev_sealed / kid_pin_prev_rev: the PIN and rev the phone last confirmed, kept across
--   any number of saves until it reports the current rev (prev_rev set with prev_sealed NULL = an
--   earlier PIN this server can't show).
ALTER TABLE device_policy ADD COLUMN kid_keyguard TEXT NOT NULL DEFAULT 'own';
ALTER TABLE device_policy ADD COLUMN kid_pin_sealed TEXT;
ALTER TABLE device_policy ADD COLUMN kid_pin_prev_sealed TEXT;
ALTER TABLE device_policy ADD COLUMN kid_pin_prev_rev INTEGER;
ALTER TABLE device_policy ADD COLUMN kid_pin_rev INTEGER NOT NULL DEFAULT 0;
