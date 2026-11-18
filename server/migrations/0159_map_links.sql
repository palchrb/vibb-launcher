-- Design 110 (map links, docs/design/110-map-links.md §8.2): the integration's own tables (the integration-boundary
-- pilot, docs/notes/server/integrations.md - nothing added to the core's). 0157 and 0158 are reserved by designs 114
-- and 116.
--
-- Each phone's switch (off until the parent turns it on) and its last `map_links` report, cleaned
-- (integrations::map_links::clean); a pause's report keeps the last `reach` and `vibb_hosts` from outside one.
CREATE TABLE map_link_devices (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    is_on INTEGER NOT NULL DEFAULT 0,
    report_json TEXT,
    reported_at TEXT
);
-- §8.4: one row per day and lookup outcome (found, disagree, consent, http, format, echo, range, far, text,
-- unreachable, busy) - never a link, an id or a text. Kept 30 days.
CREATE TABLE map_link_lookups (
    day TEXT NOT NULL,
    outcome TEXT NOT NULL,
    n INTEGER NOT NULL,
    PRIMARY KEY (day, outcome)
);
