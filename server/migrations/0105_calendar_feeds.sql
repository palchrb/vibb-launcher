-- Design 72 step 1 ("Calendars without sync apps", docs/design/72-calendar-feeds.md at the monorepo root): a calendar
-- link pasted in Device › Calendar. The server fetches and parses the link; the phone writes the events into a local
-- calendar of its own. A link is per phone, the fetch shared: the same link on a sibling's page names the same source.

-- One fetched link. `uid` is 32 hex of SHA-256 over `vibb-feed:` + the normalized URL (scheme and host lowercased,
-- webcal(s) as https, the default port and the fragment dropped), so a link removed and added again is the same
-- calendar on the phone. The URL is sealed with the server's music key (associated data
-- `calendar-v1:<fingerprint>:<uid>`): a school's private feed link is a read password; `host` is the only part shown
-- or logged. `lan` as the music sweep judges it (a public link stays public); `lan_http` = plain http on the home LAN
-- (the page warns). `feed_json` is the document the phones get (`GET /api/devices/calendar/feeds/{uid}`), `rev` its
-- version; nothing historical is kept: each accepted fetch replaces the set. `error` is a code, worded at render.
CREATE TABLE calendar_sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uid TEXT NOT NULL UNIQUE CHECK (length(uid) = 32),
    kind TEXT NOT NULL DEFAULT 'ics' CHECK (kind IN ('ics', 'caldav')),
    url_sealed BLOB NOT NULL,
    url_nonce BLOB NOT NULL,
    url_key_fp TEXT NOT NULL,
    host TEXT NOT NULL,
    lan INTEGER NOT NULL DEFAULT 0,
    lan_http INTEGER NOT NULL DEFAULT 0,
    etag TEXT,
    last_modified TEXT,
    rev TEXT NOT NULL,
    feed_json TEXT NOT NULL,
    feed_name TEXT,
    ok_at TEXT,
    tried_at TEXT,
    failures INTEGER NOT NULL DEFAULT 0,
    next_at TEXT NOT NULL,
    error TEXT,
    error_status INTEGER,
    empty_count INTEGER NOT NULL DEFAULT 0,
    empty_since TEXT,
    churn INTEGER NOT NULL DEFAULT 0,
    events INTEGER NOT NULL DEFAULT 0,
    cut INTEGER NOT NULL DEFAULT 0,
    unknown_tz INTEGER NOT NULL DEFAULT 0,
    bad INTEGER NOT NULL DEFAULT 0,
    fetch_now_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_calendar_sources_next ON calendar_sources(next_at);

-- Which phone has which link, under the name the parent gave it (1-60 characters; the phone's calendar's name).
CREATE TABLE device_calendar_feeds (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    source_id INTEGER NOT NULL REFERENCES calendar_sources(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, source_id)
);
CREATE INDEX idx_device_calendar_feeds_source ON device_calendar_feeds(source_id);

-- A source no phone names any more goes at once (QA H2): Remove and a deleted phone (whose cascade deletes only its
-- rows here) both end here, so no sealed link or event text stays behind, fetched for nobody.
CREATE TRIGGER calendar_sources_orphaned AFTER DELETE ON device_calendar_feeds
WHEN NOT EXISTS (SELECT 1 FROM device_calendar_feeds WHERE source_id = OLD.source_id)
BEGIN
    DELETE FROM calendar_sources WHERE id = OLD.source_id;
END;
