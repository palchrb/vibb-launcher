-- Design 82 D2 (docs/design/82-meldinger-as-an-app.md): Meldinger - the launcher's built-in SMS app - has its lock
-- level where every app has it, a row in device_lock_glance_apps under the reserved key me.vibb.builtin.messages
-- (no row = Off, 0 = Name only, 1 = Full text). Every existing phone gets what it shows today or less: Name only, or
-- Full text where "Show Message Text on the Lock Screen" was on (the server sends the text only while SMS is
-- managed). device_policy.sms_lock_text is unused after this (the column stays; nothing reads or writes it). A new
-- phone starts with Meldinger at Name only beside Element X.
INSERT OR IGNORE INTO device_lock_glance_apps (device_id, package_name, show_text)
SELECT d.id, 'me.vibb.builtin.messages',
       COALESCE((SELECT p.sms_lock_text FROM device_policy p WHERE p.device_id = d.id), 0)
FROM devices d;
DROP TRIGGER devices_get_lock_glance_default;
CREATE TRIGGER devices_get_lock_glance_default AFTER INSERT ON devices
BEGIN
    INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (NEW.id, 'io.element.android.x');
    INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (NEW.id, 'me.vibb.builtin.messages');
END;
