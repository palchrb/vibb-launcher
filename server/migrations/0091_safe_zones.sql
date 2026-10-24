-- Design 64 step 1 (safe zones, docs/design/64-safe-zones.md §3.1), all of it in one file: the
-- zones, each parent's Notify Me About switch for them, and an event's parameters.

-- A phone's named circles ("Home", "School"), shared by the family. The ranges (radius, name
-- length, how many per phone) are code's (`zones::RADIUS`, `NAME_MAX`, `MAX_ZONES`), not frozen
-- here. The last seven columns are the rule's state (§3.2), kept on the zone's own row so a save
-- and an evaluation are one row: `side` NULL = not known yet (a new or moved zone - its first
-- side is set silently), `side_since` = the first fix of the pair that set it, `pending_*` = a
-- sure fix on the other side waiting for its second, and the cursor `evaluated_at` +
-- `evaluated_fix_id` = the newest fix evaluated (`device_locations.captured_at`, `.id`): only a
-- fix after it counts, so no fix is judged twice. No foreign key to the fix (retention prunes it).
CREATE TABLE device_zones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m INTEGER NOT NULL CHECK (radius_m > 0),
    notify_arrive INTEGER NOT NULL DEFAULT 1,
    notify_leave INTEGER NOT NULL DEFAULT 1,
    created_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    side TEXT CHECK (side IN ('inside', 'outside')),
    side_since TEXT,
    pending_side TEXT CHECK (pending_side IN ('inside', 'outside')),
    pending_at TEXT,
    evaluated_at TEXT,
    evaluated_fix_id INTEGER
);
CREATE INDEX idx_device_zones_device ON device_zones(device_id);

-- Notify Me About's "Zones: arrived and left" (per parent, design 62 §5; no row = on).
ALTER TABLE notification_prefs ADD COLUMN push_zones INTEGER NOT NULL DEFAULT 1;

-- An event told from a key and its parameters (design 64, the user's decision 5 of 2026-10-24):
-- a JSON object the words are made from when shown or pushed (`parent_events::told`), never stored
-- as prose, so a later translation of the PWA only adds strings. NULL for the older kinds, whose
-- words are in `title`/`summary`; a zone event stores those empty.
ALTER TABLE parent_events ADD COLUMN params TEXT;
