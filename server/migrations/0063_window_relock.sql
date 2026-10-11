-- Design 24b (docs/design/24b-override-relock.md): the phone's last relock of a parent-PIN window
-- (an override or a pause that ended) - what it reset and what it couldn't undo, stored through
-- the known fields only (window_relock::sanitize_relock). NULL from older launchers.
ALTER TABLE device_status ADD COLUMN relock_json TEXT;
