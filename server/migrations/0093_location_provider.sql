-- Design 64 step 2 (the coordinator's decision, 2026-10-24): each stored fix keeps the phone's
-- word for where it came from - `provider` (`gps`, `network`, `fused`; a short lowercase word,
-- checked in code, `models::location_provider`) and `mock` (1 = `Location.isMock`, which only a
-- debug launcher ever sends; 0 = not mock; NULL from older launchers and older rows). Shown only in
-- the Locate page's newest-fix row's title. Not part of the fix's identity (the dedupe stays the
-- phone, `captured_at` and the coordinates).
ALTER TABLE device_locations ADD COLUMN provider TEXT;
ALTER TABLE device_locations ADD COLUMN mock INTEGER;
