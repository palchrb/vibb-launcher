-- Admin login hardening (design 22 §3.2):
-- - totp_last_step: the 30-second step of the last TOTP code accepted (login or setup); only a
--   later step is accepted, so a code can't be used twice.
-- - lock_count: how many times TOTP failures have locked the account since the last complete
--   login; each lock lasts 15 minutes doubled per earlier lock, at most 24 hours.
-- - session_epoch: stored in each session at login; a password change, a 2FA reset and "Sign out
--   everywhere" bump it, which ends every other session.
ALTER TABLE admin_users ADD COLUMN totp_last_step INTEGER;
ALTER TABLE admin_users ADD COLUMN lock_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE admin_users ADD COLUMN session_epoch INTEGER NOT NULL DEFAULT 0;
