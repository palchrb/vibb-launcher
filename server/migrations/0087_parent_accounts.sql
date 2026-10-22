-- Design 62 step 1 (docs/design/62-parent-accounts.md §1): several equal parent accounts in one family.
-- display_name: "Mamma", "Pappa"; NULL reads as the username.
-- invited_by: the account that added this one (Add Parent); NULL for the first account.
-- invite_expires_at: a one-time password's onboarding ends by then (written in SQL, datetime('now', ...));
--   NULL once the account is ready, and for the first account and a reset-2fa from the shell.
-- removed_at: a soft remove - the row and its username stay, its password and 2FA are gone.
-- device_commands.requested_by: the admin_users.id that queued a command; no foreign key, so the history
--   outlives a removal.
ALTER TABLE admin_users ADD COLUMN display_name TEXT;
ALTER TABLE admin_users ADD COLUMN invited_by INTEGER REFERENCES admin_users(id);
ALTER TABLE admin_users ADD COLUMN invite_expires_at TEXT;
ALTER TABLE admin_users ADD COLUMN removed_at TEXT;
ALTER TABLE device_commands ADD COLUMN requested_by INTEGER;
