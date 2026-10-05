-- handy step 7 fix round (qa-step7-code.md #2/#3).
-- The hash (fcm::token_hash) of the last token FCM rejected for this device: a phone that keeps
-- reporting it until it renews is ignored instead of stored, test-nudged and logged again.
ALTER TABLE device_push ADD COLUMN rejected_token_hash TEXT;
-- A token belongs to one device: a re-enrolled phone keeps its Firebase token, so the old row must
-- not keep nudging it. record_report clears it from other rows; this index enforces it.
UPDATE device_push SET fcm_token = NULL, fcm_ok = 0, send_pending = 0, unacked_sends = 0,
    last_send_nonce = NULL
WHERE fcm_token IS NOT NULL AND device_id NOT IN (
    SELECT MAX(device_id) FROM device_push WHERE fcm_token IS NOT NULL GROUP BY fcm_token);
CREATE UNIQUE INDEX device_push_fcm_token ON device_push(fcm_token) WHERE fcm_token IS NOT NULL;
