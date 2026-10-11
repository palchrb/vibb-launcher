-- Design 19b phase 1: whether the launcher is on Android's power-save allowlist ("battery:
-- Unrestricted"), read as `PowerManager.isIgnoringBatteryOptimizations` and sent in every status
-- report. NULL from older launchers and when the phone couldn't read it; the "Play and kiosk" card
-- shows a plain line for 0 and 1.
ALTER TABLE device_status ADD COLUMN battery_optimization_exempt INTEGER;
