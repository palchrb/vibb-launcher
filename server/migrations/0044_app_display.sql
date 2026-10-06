-- How an app shows on the kid's launcher (design docs/design/14-app-display.md at the monorepo root,
-- with its QA review and decisions): the parent's name, icon (a key of testdata/app_icons.json) and
-- tile colour ('auto' = the app's own colour).
--
-- The catalog default, for every phone that has the app (by the row's package name). NULL label and
-- icon = no default. The catalog's own admin name is not used for this.
ALTER TABLE tracked_apps ADD COLUMN display_label TEXT;
ALTER TABLE tracked_apps ADD COLUMN display_icon TEXT;
ALTER TABLE tracked_apps ADD COLUMN display_color TEXT NOT NULL DEFAULT 'auto';

-- One phone's own choice, by package name - also for preinstalled and Play apps that aren't in the
-- catalog. A row replaces the catalog default for that phone as a whole; a row with neither a label
-- nor an icon means "the app's own" (kept only while the catalog has a default to override).
CREATE TABLE device_app_display (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    label TEXT,
    icon_key TEXT,
    color_key TEXT NOT NULL DEFAULT 'auto',
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, package_name)
);
