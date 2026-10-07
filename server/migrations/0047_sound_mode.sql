-- Sound mode (design 18): the phone's ringer mode ("normal"/"vibrate"/"silent") and Do Not
-- Disturb (its interruption filter "all"/"priority"/"none"/"alarms") as of each status report,
-- shown on the device page so the parent sees why the kid didn't hear a call. NULL = an older
-- launcher or unreadable on the phone; anything else the phone sends is stored as NULL
-- (sound_mode::sanitize_*). The kid's sound row itself is bit 8 of
-- device_policy.quick_controls_mask (no column): new devices get 15, existing ones keep theirs.
ALTER TABLE device_status ADD COLUMN ringer_mode TEXT;
ALTER TABLE device_status ADD COLUMN interruption_filter TEXT;
