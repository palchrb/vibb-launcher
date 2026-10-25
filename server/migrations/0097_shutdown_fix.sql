-- A last position at shutdown (2026-10-25, PLAN "A last position at shutdown and at low battery"):
-- the phone's `off` report (`POST /api/devices/power`) carries the newest fix it already held, stored
-- as one of its fixes (the same row when the phone had reported it, `zones::store_fix`'s identity),
-- and that row is marked here with the shutdown's instant (RFC 3339 with milliseconds, UTC). Locate
-- says "Last position before the phone went off" while that row is the newest and no report came
-- after the shutdown. NULL = not a shutdown's fix.
ALTER TABLE device_locations ADD COLUMN shutdown_at TEXT;
