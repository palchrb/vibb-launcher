-- Security review H2: a phone could store any text as a fix's captured_at (it was shown through
-- innerHTML on the locate page). The server now takes only an RFC 3339 time, coordinates on Earth
-- and a non-negative accuracy; this removes stored rows that aren't that. A valid time holds only
-- digits, "T", "Z", ":", ".", "+" and "-".
DELETE FROM device_locations
WHERE captured_at IS NULL
   OR length(captured_at) > 64
   OR captured_at NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]*'
   OR captured_at GLOB '*[^0-9TZ:.+-]*'
   OR julianday(captured_at) IS NULL
   OR latitude IS NULL OR latitude NOT BETWEEN -90 AND 90
   OR longitude IS NULL OR longitude NOT BETWEEN -180 AND 180
   OR accuracy_meters < 0;
