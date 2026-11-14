-- Design 98 §4.4 (docs/design/98-airplane-mode-assistant.md §6.3): the parent code's pause on the phone. A lift the
-- phone posted (POST /api/devices/rule-pause) carries its key, the source marker; the PWA's own lifts keep NULL.
-- `created_by` stays NULL on the phone's rows (no username can collide with it). One row per (phone, key, rule): a
-- retried post inserts nothing and never revives a row the parent ended.
ALTER TABLE time_lifts ADD COLUMN phone_key TEXT;
CREATE UNIQUE INDEX time_lifts_phone_key ON time_lifts (device_id, phone_key, rule_id) WHERE phone_key IS NOT NULL;
