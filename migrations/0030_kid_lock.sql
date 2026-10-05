-- Handy's own PIN lock (design docs/design/10-lock-and-call-ui.md in the handy workspace, with
-- qa-10-design.md on top): the kid's PIN, hashed like the override PIN (security::hash_pin,
-- PBKDF2-SHA256 210k, 16-byte salt) and sent to the phone as `kid_lock`. NULL hash = lock off.
-- The length (4-6 digits) lets the keypad submit at the last digit.
ALTER TABLE device_policy ADD COLUMN kid_pin_hash TEXT;
ALTER TABLE device_policy ADD COLUMN kid_pin_salt TEXT;
ALTER TABLE device_policy ADD COLUMN kid_pin_length INTEGER;
-- The phone's `lock_state` (active, why not, locked, failures, backoff end) - opaque JSON, capped.
-- Never unlock times (privacy).
ALTER TABLE device_status ADD COLUMN lock_state_json TEXT;
