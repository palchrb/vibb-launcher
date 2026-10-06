-- Retention and privacy (cleanup round 2026-10-06; src/retention.rs).
-- The blocked-domain log is off by default, a per-phone opt-in; while on, entries are kept 7
-- days. Everything collected before the opt-in existed is deleted.
ALTER TABLE device_policy ADD COLUMN dns_log_enabled INTEGER NOT NULL DEFAULT 0;
DELETE FROM device_dns_events;
-- How long the phone's location history is kept (days, one of retention::LOCATION_RETENTION_DAYS;
-- was a fixed 30). The newest fix per phone is always kept, for Find My Device.
ALTER TABLE device_policy ADD COLUMN location_retention_days INTEGER NOT NULL DEFAULT 7;
-- The status history (screen time, call state, app lists) is pruned after 30 days by
-- retention::prune; this index makes "newest report per phone" cheap.
CREATE INDEX IF NOT EXISTS idx_device_status_device_id_id ON device_status (device_id, id);
