-- Security review S-L5 (launcher round L4b): Kid Settings' Bluetooth page offers pairing only for headphones and
-- speakers (audio-class / A2DP-sink devices) unless the parent allows any device for this phone - Device › Home ›
-- Quick Controls' "Any Bluetooth Device" switch, sent as the policy's `bluetooth_any_device`. Off by default.
ALTER TABLE device_policy ADD COLUMN bluetooth_any_device INTEGER NOT NULL DEFAULT 0;
