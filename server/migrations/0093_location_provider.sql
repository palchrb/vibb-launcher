-- Design 64 step 2 (the coordinator's decision, 2026-10-24): each stored fix keeps the phone's
-- word for where it came from - `provider` (`gps`, `network`, `fused`; a short lowercase word,
-- checked in code, `models::location_provider`) and `mock` (1 = `Location.isMock`, which only a
-- debug launcher ever sends; 0 = not mock; NULL from older launchers and older rows). Shown only in
-- the Locate page's newest-fix row's title. Not part of the fix's identity (the dedupe stays the
-- phone, `captured_at` and the coordinates).
ALTER TABLE device_locations ADD COLUMN provider TEXT;
ALTER TABLE device_locations ADD COLUMN mock INTEGER;

-- qa-design-64-step2-code Q-3: when the phone last re-sent this fix as still current - its motion
-- sensor said it hadn't moved since (`zone_watch.still`). "Here now" counts from the later of
-- `captured_at` and this; the fix's own time is never rewritten. NULL = never re-sent so.
ALTER TABLE device_locations ADD COLUMN resent_at TEXT;

-- Q-9: the phone's running count of ignored mock fixes as last logged, and when it last rose. The
-- security line comes once per rise (an UPDATE that only a higher count passes, so two parallel
-- reports log it once); a lower count (the launcher's data cleared, a new enrollment) is taken
-- silently. NULL = nothing logged yet.
ALTER TABLE devices ADD COLUMN mock_logged INTEGER;
ALTER TABLE devices ADD COLUMN mock_logged_at TEXT;
