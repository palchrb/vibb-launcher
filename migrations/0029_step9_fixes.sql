-- Fixes from emulator testing (design docs/design/09-sms-and-fixes.md part B in the handy
-- workspace, with qa-09-design.md on top). Part A (SMS allowlist) is postponed.
--
-- When calls_managed last changed: a status report older than this may still show the phone's
-- previous role state, so the calls page waits for a newer one - for at most 5 minutes (B1,
-- QA 09 #5).
ALTER TABLE device_policy ADD COLUMN roles_changed_at TEXT;
-- LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK in kiosk (B4). Sent as its own policy key, never
-- as a lock_task_features bit, so an older launcher without the pinned system helpers never gets
-- it. The per-device off switch is the remote kill switch (QA 09 #4).
ALTER TABLE device_policy ADD COLUMN block_activity_start INTEGER NOT NULL DEFAULT 1;
-- Whether the Play Store could be suspended on this phone (B4), from the status report. NULL from
-- older launchers.
ALTER TABLE device_status ADD COLUMN play_store_suspendable INTEGER;
