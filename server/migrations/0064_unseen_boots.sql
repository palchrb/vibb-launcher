-- Design 24b §3 (security review H4): boots the launcher didn't see (safe mode, a start cut
-- short), from the phone's BOOT_COUNT witness - the count (it only goes up) and when it last
-- noticed one. NULL from older launchers.
ALTER TABLE device_status ADD COLUMN unseen_boots_count INTEGER;
ALTER TABLE device_status ADD COLUMN unseen_boots_last_at_ms INTEGER;
-- The fix round (QA Low 12): when "Block safe mode" was last turned on (ms; NULL = off, or on since
-- before this column), so the warning's advice follows the switch in force at the boot.
ALTER TABLE device_policy ADD COLUMN disallow_safe_boot_set_at_ms INTEGER;
