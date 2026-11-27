-- The owner's report of 2026-11-27 (launcher rc.6): Locate's "Hasn't moved since" told the newest fix's time, not when
-- the phone went still. `still_since` = when the phone says it went still (its last motion before the stillness it
-- re-sends this fix under, the report's `zone_watch.still_since_ms`), stamped with `resent_at` by a still re-send; NULL
-- = not told (an older launcher, a re-send without it). RFC 3339 UTC as `resent_at`. Only a column is added.
ALTER TABLE device_locations ADD COLUMN still_since TEXT;
