-- Design 107 (blocked notification channels, docs/design/107-blocked-channels.md §4): the report's
-- `notification_channels`, cleaned (notification_channels::clean): `{all_off, blocked}`, read by About This Phone ›
-- Sound's "Notifications" row and the summary's Attention row. Kept with its status row (no retention change).
ALTER TABLE device_status ADD COLUMN notification_channels_json TEXT;
