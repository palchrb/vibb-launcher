-- Design 131: what confirmed a Wi-Fi fix (`vibb_wifi`, `vibb_apple`) - `gnss` (a satellite fix agreed) or `link` (the
-- phone was on a saved network linked to a zone the fix is surely inside). NULL for the Wi-Fi alone and every other
-- provider. A Wi-Fi fix without one never starts an arrival and never keeps "Here now" fresh.
ALTER TABLE device_locations ADD COLUMN fix_check TEXT;

-- The phone's running count of attempts in which Wi-Fi and the satellites disagreed (`zone_watch.wifi_disagreed`): the
-- count the security log last took in, and when its last line was written (at most one a day). Cleared at a new
-- enrolment.
ALTER TABLE devices ADD COLUMN wifi_disagreed_logged INTEGER;
ALTER TABLE devices ADD COLUMN wifi_disagreed_at TEXT;
