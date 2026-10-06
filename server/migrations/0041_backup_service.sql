-- Google account on the phone (docs/setup/google-account.md): the launcher keeps Android's backup
-- service (backup to the Google account) off while the phone is managed and reports whether it is
-- on, read as device owner (`DevicePolicyManager.isBackupServiceEnabled`). NULL = an older
-- launcher, not device owner, or unreadable - the device page then shows nothing.
ALTER TABLE device_status ADD COLUMN backup_service_enabled INTEGER;
