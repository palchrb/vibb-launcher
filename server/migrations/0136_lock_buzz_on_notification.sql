-- Design 82 W3 (docs/design/82-meldinger-as-an-app.md §9): the per-phone switch "Buzz for a notification" on Lock
-- Screen & PINs, in the cell under "Wake the screen for a notification", off by default. Sent as
-- launcher_ui.lock_buzz_on_notification; the launcher vibrates once, without a sound, on its lock for a new row on
-- the glance page (Meldinger's included) or a missed call.
ALTER TABLE device_policy ADD COLUMN lock_buzz_on_notification INTEGER NOT NULL DEFAULT 0;
