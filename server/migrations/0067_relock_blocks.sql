-- Design 24b, fix of 2026-10-12: every app the phone's relock or install check has blocked now -
-- its package, signing key, why and when (window_relock::sanitize_relock_blocks). An install-time
-- block reached no report before, so the parent couldn't let it out. NULL from older launchers.
ALTER TABLE device_status ADD COLUMN relock_blocks_json TEXT;
