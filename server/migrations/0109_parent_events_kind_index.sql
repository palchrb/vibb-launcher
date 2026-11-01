-- The status lines' "Turned off" (2026-11-01, PLAN "Tidier status on Devices and the phone's summary"):
-- `parent_events::offs_now` finds each phone's newest `phone_off` and `phone_on` with per-row subqueries, now for every
-- phone on the Devices list (the landing page) - this index answers them without scanning the events.
CREATE INDEX idx_parent_events_device_kind ON parent_events(device_id, kind, happened_at);
