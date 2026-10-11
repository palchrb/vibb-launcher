-- Design 24b §3 (security review H4): boots the launcher didn't see (safe mode, a start cut
-- short), from the phone's BOOT_COUNT witness - the count (it only goes up) and when it last
-- noticed one. NULL from older launchers.
ALTER TABLE device_status ADD COLUMN unseen_boots_count INTEGER;
ALTER TABLE device_status ADD COLUMN unseen_boots_last_at_ms INTEGER;
