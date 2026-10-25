-- Security review M1 (2026-10-21): a push subscription keeps the account's session_epoch from the
-- session that turned it on. A password change, a 2FA reset, Sign Out Everywhere or a parent's
-- Reset moves the account's epoch; push::send_due then deletes every subscription with an older
-- one, so a browser that was signed out that way gets no more notifications. Existing rows take
-- their account's epoch of today.
ALTER TABLE push_subscriptions ADD COLUMN session_epoch INTEGER NOT NULL DEFAULT 0;
UPDATE push_subscriptions SET session_epoch = COALESCE(
    (SELECT a.session_epoch FROM admin_users a WHERE a.id = push_subscriptions.admin_id), 0);
