-- Launcher round 7 §3 (docs/design/r7-launcher-small-items.md): the per-phone switch "Wake the
-- screen for a notification" on Lock Screen & PINs, off by default. Sent as
-- launcher_ui.lock_wake_on_notification; the launcher wakes the screen on its lock for a new row on
-- the glance page or a missed call.
ALTER TABLE device_policy ADD COLUMN lock_wake_on_notification INTEGER NOT NULL DEFAULT 0;
