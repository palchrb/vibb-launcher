-- Weather on the lock glance (design 75 §3.1). Device › Weather's switch (off by default, served as
-- `launcher_ui.lock_weather`) and the zone that stands in when the phone has no fresh fix (D5's source 2: one of
-- the phone's own zones; none by default). A deleted zone leaves none (the foreign key).
--
-- NOTE for any later migration that rebuilds `device_zones` (copy, drop, rename): migrations run with foreign keys
-- on, so dropping the old table sets every `weather_zone_id` to NULL without a word. Such a rebuild must save the
-- column first and restore it after (server QA R2; `docs/notes/server/zones.md`).
ALTER TABLE device_policy ADD COLUMN lock_weather INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN weather_zone_id INTEGER REFERENCES device_zones(id) ON DELETE SET NULL;

-- One forecast per grid cell (D6-D7), shared by every phone in it: no device id. Times are UTC RFC 3339.
CREATE TABLE weather_cells (
    id INTEGER PRIMARY KEY,
    lat_i INTEGER NOT NULL,            -- round(lat / 0.02)
    lon_i INTEGER NOT NULL,            -- round(lon / 0.04), wrapped into [-180, 180)
    hours_json TEXT,                   -- the compact hours (weather::Hour), NULL before the first 200
    last_modified TEXT,                -- MET's Last-Modified, verbatim
    checked_at TEXT,                   -- the last 200 or 304
    next_fetch_at TEXT,                -- not before this (Expires, the 30-min floor, the jitter, a backoff)
    failures INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,                   -- a code: network, timeout, status_429, status_403, status_5xx, status_other, unreadable
    asked_at TEXT NOT NULL,            -- the last phone ask (retention: 48 h)
    UNIQUE (lat_i, lon_i)
);

-- The place name per cell (D13), from Kartverket: NULL = looked up, no name. Kept while a phone asks for the cell
-- within the longest location retention.
CREATE TABLE weather_places (
    lat_i INTEGER NOT NULL,
    lon_i INTEGER NOT NULL,
    name TEXT,
    asked_at TEXT NOT NULL,
    PRIMARY KEY (lat_i, lon_i)
);
