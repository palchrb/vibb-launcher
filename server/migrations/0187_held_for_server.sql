-- 0187_held_for_server.sql (design 140 §4.2): a release of ours (Vibb One, Vibb music) that names a newer server than
-- this one (assets/vibb/min_server) is held with reason 'server' and the version it needs, until a server that new
-- starts. 0141's CHECK allowed three reasons; SQLite can't change a CHECK, so the table is rebuilt (the pattern of 0099
-- and 0175) with every 0141 column, its key and cascade kept, and the rows copied as they are. Nothing references it.
CREATE TABLE tracked_app_held_releases_new (
    tracked_app_id INTEGER PRIMARY KEY REFERENCES tracked_apps(id) ON DELETE CASCADE,
    release_tag TEXT NOT NULL,
    asset_id INTEGER NOT NULL,
    file_path TEXT NOT NULL,
    asset_name TEXT,
    asset_size INTEGER,
    sha256 TEXT NOT NULL,
    version_code INTEGER,
    permissions TEXT,                       -- JSON array or NULL (unread)
    reason TEXT NOT NULL CHECK (reason IN ('permissions', 'unreadable', 'always_ask', 'server')),
    new_permissions TEXT NOT NULL DEFAULT '[]',
    held_at TEXT NOT NULL,
    min_server TEXT,                        -- X.Y.Z, the server the release needs (reason 'server')
    CHECK (reason <> 'server' OR min_server IS NOT NULL)
);
INSERT INTO tracked_app_held_releases_new (tracked_app_id, release_tag, asset_id, file_path, asset_name, asset_size,
    sha256, version_code, permissions, reason, new_permissions, held_at)
    SELECT tracked_app_id, release_tag, asset_id, file_path, asset_name, asset_size, sha256, version_code, permissions,
    reason, new_permissions, held_at FROM tracked_app_held_releases;
DROP TABLE tracked_app_held_releases;
ALTER TABLE tracked_app_held_releases_new RENAME TO tracked_app_held_releases;
