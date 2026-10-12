-- Design 25 (docs/design/25-lock-glance.md §3.1): the lock's glance page. The apps whose message
-- notifications the PIN lock's first page shows (missed calls always show), one row per tick like
-- device_tracked_apps, and the "Show the message text" switch (off = the sender only). Every phone
-- starts with Element X ticked: the existing ones here, new ones by the trigger (as
-- devices_get_builtin_wallpapers does), so every path that creates a device gets the default.
CREATE TABLE device_lock_glance_apps (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    PRIMARY KEY (device_id, package_name)
);
ALTER TABLE device_policy ADD COLUMN lock_glance_text INTEGER NOT NULL DEFAULT 0;
INSERT INTO device_lock_glance_apps (device_id, package_name) SELECT id, 'io.element.android.x' FROM devices;
CREATE TRIGGER devices_get_lock_glance_default AFTER INSERT ON devices
BEGIN
    INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (NEW.id, 'io.element.android.x');
END;
