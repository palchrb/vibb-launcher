-- Design 108c: app push problems on Attention and the delivery time.
-- The reason of the last VAPID refusal (R23-S fix round; 0153 is frozen): 'unsigned' (the sender's: a missing, wrong or
-- bad signature), 'time' or 'address' (a correctly signed push our own clock or origin setting refused). NULL reads as
-- 'unsigned'.
ALTER TABLE up_registrations ADD COLUMN vapid_refused_why TEXT;
-- The first 'unsigned' refusal of the current run, and when the current run of outside-gateway pushes began.
ALTER TABLE up_registrations ADD COLUMN vapid_refused_since TEXT;
ALTER TABLE up_registrations ADD COLUMN outside_since TEXT;
-- A queued push's stream generation at accept, the first pull that handed it out and the latest one (epoch ms).
ALTER TABLE up_messages ADD COLUMN stream_ms INTEGER;
ALTER TABLE up_messages ADD COLUMN fetched_ms INTEGER;
ALTER TABLE up_messages ADD COLUMN handed_ms INTEGER;
-- Delivery-time samples: accept -> first hand-out, only on the same stream; no key, package or body.
CREATE TABLE up_delivery (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    acked_ms INTEGER NOT NULL,
    ms INTEGER NOT NULL
);
CREATE INDEX up_delivery_at ON up_delivery (acked_ms);
CREATE INDEX up_delivery_device ON up_delivery (device_id, acked_ms);
