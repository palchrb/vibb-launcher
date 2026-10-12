-- Security review L12: the phone confirms each remote command before it runs it.
-- received_at: when the phone said it got the command (POST /api/devices/command-receipt; a result
--   implies it too). A phone that confirms commands (capability command_receipt_v1) gets a delivered
--   command it didn't confirm again, after 2 minutes, at most `deliveries` = 3 times in all, and only
--   within 15 minutes of its first delivery (`first_delivered_at`).
-- deliveries: how often policy() served the command; delivered_at is the last time.
-- succeeded: the result's success flag (NULL for older rows), so a refused wipe reads "Failed".
ALTER TABLE device_commands ADD COLUMN received_at TEXT;
ALTER TABLE device_commands ADD COLUMN deliveries INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_commands ADD COLUMN first_delivered_at TEXT;
ALTER TABLE device_commands ADD COLUMN succeeded INTEGER;
-- Every command delivered before this migration was delivered at most once (an older launcher never
-- confirmed one - every wipe it got, for one): never offered again (QA of the launcher wave, High 1).
UPDATE device_commands SET deliveries = 3, first_delivered_at = delivered_at
 WHERE delivered_at IS NOT NULL;
UPDATE device_commands SET received_at = acknowledged_at WHERE acknowledged_at IS NOT NULL;
-- QA of the launcher wave, Low 4: whether the launcher can wipe the phone remotely now. On Android
-- 14 the device owner's new `<wipe-data />` policy only counts after the phone restarts once. NULL
-- from older launchers and when the phone couldn't tell.
ALTER TABLE device_status ADD COLUMN remote_wipe_ready INTEGER;
-- The device admin's declared policies Android hasn't granted yet (JSON list of tags, e.g.
-- ["disable-keyguard-features"]): a policy a launcher update added may only count after the phone
-- restarts once. NULL from older launchers.
ALTER TABLE device_status ADD COLUMN admin_policies_pending_json TEXT;
