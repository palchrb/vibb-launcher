-- Kiosk escapes (design docs/design/11-kiosk-escapes.md in the handy workspace, with
-- qa-11-design.md on top).
--
-- The launcher's update fence: while it installs its own update, every other Home app is
-- suspended and the status bar disabled. Off by default until the A3 device check passes on the
-- Jelly Star (its own launcher package); off also releases a fence that is up.
ALTER TABLE device_policy ADD COLUMN update_fence INTEGER NOT NULL DEFAULT 0;
-- The launcher's notification listener cancels other apps' nags (not allowed, not essential).
-- Off by default until the B5 device check (emergency alerts and calls stay).
ALTER TABLE device_policy ADD COLUMN notification_auto_cancel INTEGER NOT NULL DEFAULT 0;
-- What the phone reports about them, stored re-serialized and capped (kiosk_escapes.rs): the
-- fence state and the pending launcher update, and per (package, channel) the notifications
-- removed since the last report - ids and counts only, never notification text.
ALTER TABLE device_status ADD COLUMN update_fence_json TEXT;
ALTER TABLE device_status ADD COLUMN notification_cancels_json TEXT;
