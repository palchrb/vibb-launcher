-- Design 24b S4 (security review H4), after device check 6 passed on the Jelly Star: safe mode is
-- blocked on every phone. Safe mode starts the phone without the launcher, so no app, call or time
-- rule applies there. Every device flipped here is named in the security log; new devices get it
-- from insert_device_with_policy. A parent can still switch it off per device.
-- When it went on is kept for the unseen-boots advice (0064's disallow_safe_boot_set_at_ms).
INSERT INTO security_events (event_type, detail)
  SELECT 'safe_boot_blocked_by_default', 'device ' || device_id FROM device_policy WHERE disallow_safe_boot = 0;
UPDATE device_policy SET disallow_safe_boot_set_at_ms = CAST(strftime('%s', 'now') AS INTEGER) * 1000
  WHERE disallow_safe_boot = 0;
UPDATE device_policy SET disallow_safe_boot = 1;
