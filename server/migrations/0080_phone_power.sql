-- The phone turned off and on (design 38): two more push switches for Notify Me About, on by
-- default like design 35's. The events themselves are parent_events rows of the kinds `phone_off`
-- and `phone_on`; a held `phone_off` push is a queued push_deliveries row with next_try_at set.
ALTER TABLE notification_settings ADD COLUMN push_phone_off INTEGER NOT NULL DEFAULT 1;
ALTER TABLE notification_settings ADD COLUMN push_phone_on INTEGER NOT NULL DEFAULT 1;
