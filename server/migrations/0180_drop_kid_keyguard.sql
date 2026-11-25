-- Design 132 (phase D): Android's PIN lock is the only lock. kid_keyguard (0085) chose between it and
-- Vibb One's own lock; nothing reads it now. No index, trigger, view, CHECK or foreign key names it
-- (only 0085 does), so SQLite's DROP COLUMN applies. Legacy Child PINs without a sealed copy
-- (kid_pin_sealed IS NULL AND kid_pin_hash IS NOT NULL) are left as they are: the page asks to save them again.
ALTER TABLE device_policy DROP COLUMN kid_keyguard;
