-- Design 90 (docs/design/90-catalog-update-holds.md, §17.3 M6): the served release's declared permissions, the set
-- the parent accepted, the per-app ask switch and wait, the release waiting out its wait, and the one the parent
-- declined; the held release in its own table. Existing rows get the 48 hours too - f-droid.org's included (D2).
ALTER TABLE tracked_apps ADD COLUMN latest_release_permissions TEXT;   -- JSON array or NULL (unread)
ALTER TABLE tracked_apps ADD COLUMN accepted_permissions TEXT;         -- JSON array of sensitive names; NULL = unknown
ALTER TABLE tracked_apps ADD COLUMN always_ask INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tracked_apps ADD COLUMN release_wait_hours INTEGER NOT NULL DEFAULT 48
    CHECK (release_wait_hours BETWEEN 0 AND 336);  -- read only for GitHub and F-Droid rows
ALTER TABLE tracked_apps ADD COLUMN waiting_release_tag TEXT;
ALTER TABLE tracked_apps ADD COLUMN waiting_release_asset_id INTEGER;
ALTER TABLE tracked_apps ADD COLUMN waiting_release_published_at TEXT; -- the source's time, for the page; NULL = none
ALTER TABLE tracked_apps ADD COLUMN waiting_release_since TEXT;        -- the wait's anchor, UTC RFC 3339
ALTER TABLE tracked_apps ADD COLUMN declined_release_tag TEXT;
ALTER TABLE tracked_apps ADD COLUMN declined_release_asset_id INTEGER;

CREATE TABLE tracked_app_held_releases (
    tracked_app_id INTEGER PRIMARY KEY REFERENCES tracked_apps(id) ON DELETE CASCADE,
    release_tag TEXT NOT NULL,
    asset_id INTEGER NOT NULL,
    file_path TEXT NOT NULL,
    asset_name TEXT,
    asset_size INTEGER,
    sha256 TEXT NOT NULL,
    version_code INTEGER,
    permissions TEXT,                       -- JSON array or NULL (unread)
    reason TEXT NOT NULL CHECK (reason IN ('permissions', 'unreadable', 'always_ask')),
    new_permissions TEXT NOT NULL DEFAULT '[]',
    held_at TEXT NOT NULL
);

-- QA M1: when this server first saw each listed release - the wait counts from the later of the source's time and
-- this (a self-hosted repo's `added` can be backdated). Rows of releases no longer listed go at the next sync.
CREATE TABLE tracked_app_seen_releases (
    tracked_app_id INTEGER NOT NULL REFERENCES tracked_apps(id) ON DELETE CASCADE,
    release_tag TEXT NOT NULL,
    asset_id INTEGER NOT NULL,
    first_seen TEXT NOT NULL,                -- UTC RFC 3339
    PRIMARY KEY (tracked_app_id, release_tag, asset_id)
);
