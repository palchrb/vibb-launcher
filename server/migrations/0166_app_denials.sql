-- Design 127 step 2 (docs/design/127-app-page.md §3.4): what each app may use, per phone - one row per phone, package
-- and key the parent switched off on the app's own page (Device › Apps › <app>, Tilgang). No row = allowed (the kid's
-- own dialog decides; the phone never grants). The policy sends the rows as `app_permissions`
-- (`[{package_name, camera?, location?, microphone?, contacts?}]`, each key "deny"), and the phone denies each of the
-- key's requested permissions (`setPermissionGrantState` DENIED). `network` is reserved for step 3 (Nett). A key row
-- table needs no table rebuild per new key: 0102's `camera NOT NULL CHECK (camera = 'deny')` can't be widened in
-- place. At most 200 packages a phone (`app_permissions::MAX_ROWS_PER_DEVICE`); rows are kept when an app is removed,
-- so a reinstalled app comes back with its denials.
CREATE TABLE device_app_denials (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    permission TEXT NOT NULL CHECK (permission IN ('network', 'camera', 'location', 'microphone', 'contacts')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, package_name, permission)
);

INSERT INTO device_app_denials (device_id, package_name, permission, updated_at)
SELECT device_id, package_name, 'camera', updated_at FROM device_app_permissions WHERE camera = 'deny';

DROP TABLE device_app_permissions;
