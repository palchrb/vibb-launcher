-- Screen capture switch (the user, 2026-10-23; docs/design/04-hardening.md "Screen capture"):
-- the launcher (device owner) calls DevicePolicyManager.setScreenCaptureDisabled for the whole
-- phone - no screenshots, screen recording or casting, Recents cards blank, adb screencap black.
-- Off by default, unlike the other hardening switches; a pause or the Parent PIN doesn't lift it.
ALTER TABLE device_policy ADD COLUMN disallow_screen_capture INTEGER NOT NULL DEFAULT 0;
