-- "Show as a media player on the lock screen" per phone and app (Device › Apps › Permissions, 2026-11-04): one row per
-- package the parent switched on. The lock's media tile shows another app's playback only for a media app - its
-- manifest's category audio or video, or a web browser - so a messenger's voice message never shows there; a row here
-- counts one more app (a podcast app without a category, AntennaPod). No row = the phone's own rule. The policy sends
-- every row as `lock_media_apps`. At most 200 rows a phone (`app_permissions::MAX_ROWS_PER_DEVICE`).
CREATE TABLE device_lock_media_apps (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, package_name)
);
