-- Per-app permissions (design 68, docs/design/68-app-permissions.md at the monorepo root): one row per phone and
-- package the parent switched away from the default on that phone - today only "camera off" (`camera` = 'deny').
-- No row = the default (the kid's own dialog decides). The policy sends every row as `app_permissions`; the phone
-- denies the camera (`setPermissionGrantState` DENIED) of each such app that asks for it. At most 200 rows a phone
-- (`app_permissions::MAX_ROWS_PER_DEVICE`).
CREATE TABLE device_app_permissions (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    camera TEXT NOT NULL CHECK (camera = 'deny'),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, package_name)
);
