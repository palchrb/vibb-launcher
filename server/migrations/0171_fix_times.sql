-- A fix's time is the server's receipt minus the fix's age on the phone (`age_ms`, from elapsed realtime), whatever the
-- phone's own clock says. `phone_offset_ms`: the time the phone named minus the time stored - NULL when the fix came
-- without an age (its own time stored, as before).
-- (0170 is taken by another branch's migration; this one follows it.)
ALTER TABLE device_locations ADD COLUMN phone_offset_ms INTEGER;

-- Fixes a phone sent that the server couldn't use (a place, a time or an accuracy that isn't one): how many, and when
-- the last came. The Locate page says "Position refused" while that is newer than the newest stored fix.
ALTER TABLE devices ADD COLUMN fixes_dropped INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN fix_dropped_at TEXT;
