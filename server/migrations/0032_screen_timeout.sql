-- Auto-lock (emulator run 2026-10-06, docs/testing/2026-10-06-emulator-run.md): the parent sets the
-- phone's screen timeout per device; the launcher applies it as device owner
-- (DevicePolicyManager.setSystemSetting(SCREEN_OFF_TIMEOUT)) and keeps it there. Handy's PIN lock
-- locks on every screen-off, so this is also the lock delay. Sent as
-- `PolicyResponse.screen_timeout_seconds`; one of 15/30/60/120/300/600, default 1 minute.
ALTER TABLE device_policy ADD COLUMN screen_timeout_seconds INTEGER NOT NULL DEFAULT 60;
-- What the phone reports it applied (read back from Settings.System), seconds; NULL = older
-- launcher, or nothing applied.
ALTER TABLE device_status ADD COLUMN screen_timeout_seconds INTEGER;
