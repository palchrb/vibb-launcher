-- "Modify system settings" without adb (PLAN fix backlog, 2026-10-24): whether Vibb One holds
-- Android's WRITE_SETTINGS app-op (`Settings.System.canWrite`), which the touch sounds row and the
-- clock format need and a device owner can't grant itself. Sent in every status report as
-- write_settings_granted; NULL from older launchers and when the phone couldn't read it. About This
-- Phone shows a plain line for 1 and, for 0, the on-phone path (Settings › Pause all restrictions ›
-- Modify system settings).
ALTER TABLE device_status ADD COLUMN write_settings_granted INTEGER;
