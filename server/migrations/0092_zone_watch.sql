-- Design 64 step 2: the phone's zone watch, from every status report of a launcher that watches
-- zones itself (`zone_watch`: the fences it holds, whether it has a significant-motion sensor,
-- Location on, the background-location grant, how many mock fixes it ignored since it was set up).
-- Known keys only (`zones::sanitize_zone_watch`), at most 512 bytes; NULL from older launchers.
-- `fences > 0` in the phone's latest report since its enrollment is what the served Regularly
-- interval's floor (30 min while the phone has zones) waits for.
ALTER TABLE device_status ADD COLUMN zone_watch_json TEXT;
