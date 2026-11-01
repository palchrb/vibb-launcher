-- The lock reminders' third form (the user, 2026-10-27; PLAN.md "Lock-screen reminders: a third option"): the quiet
-- row plus one ping, the phone's notification sound once as the row comes up. `calendar_lock_reminders`' CHECK (0103)
-- allows only 'off' and 'row', and SQLite can't change a CHECK without rebuilding device_policy, so the ping is its own
-- switch over the row: 1 with 'row' = `lock_reminders: "ping"` in the policy (`calendar::switches`), only to a launcher
-- that claims `calendar_ping_v1` - an older one reads anything but "row" as off, so it is sent "row". 1 with 'off'
-- means nothing (the PWA's save writes both columns at once).
-- Follows 0112 (0111 and 0112 are the F-Droid trust round's, S4).
ALTER TABLE device_policy ADD COLUMN calendar_lock_ping INTEGER NOT NULL DEFAULT 0
    CHECK (calendar_lock_ping IN (0, 1));
