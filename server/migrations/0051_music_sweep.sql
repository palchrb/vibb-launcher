-- Vibb music: the server sweeps the sources (design docs/design/21b-music-server-sweep.md §1, with its
-- QA review, decisions and the user's answer to open question 1). The server lists every NRK and RSS
-- entry (`music_listings` + `music_items`), and each phone gets an entry's list from
-- `GET /api/devices/music/entries/{id}/items`; the library names it by `music_listings.version`.
--
-- New tables, an index and triggers only: safe on an existing database (0049 and 0050 are never
-- edited, nothing existing changes meaning). Every stamp is UTC text ("YYYY-MM-DD HH:MM:SS") written
-- from the sweeper's `now`, never datetime('now'), so the tests' fake clock drives them.

-- The family-wide sweep cadence in hours (the PWA's "Check for new episodes" setting; 6 = the Pi).
CREATE TABLE music_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sweep_hours INTEGER NOT NULL DEFAULT 6 CHECK (sweep_hours IN (1, 3, 6, 12, 24))
);
INSERT INTO music_settings (id) VALUES (1);

-- One row per "Check now", for its hourly and daily limits (rows older than a day are deleted on
-- insert; the per-entry limit reads music_listings.requested_at).
CREATE TABLE music_check_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at TEXT NOT NULL
);

-- One row per NRK/RSS entry, upserted when its first check starts (own files have none).
--   title         the source's own name (psapi series title, the RSS channel title)
--   lan           NULL until the first check resolved the target; 1 = it resolved only to private
--                 addresses (a feed on the home LAN or the tailnet), 0 = public. Never changed after.
--   fallback      the list came from NRK's RSS fallback (podkast.nrk.no)
--   capped        the source has more episodes than the list keeps (100 for NRK)
--   cut           the served list was trimmed to 1 MB (MAX_LISTING_BYTES)
--   keep_end      which 100 an NRK list keeps: 'newest' (podcasts, newest-first series) or 'first'
--                 (oldest-first series and serie/<slug>/<programId>, anchored at the start; the user's
--                 answer to open question 1). A change of play order that moves it refills the list.
--   item_count    items in the served list; bytes: its size (the per-phone listing budget)
--   version       16 hex of SHA-256 over the served list without its version; NULL = never listed
--   etag, last_modified, body_sha256, parser: RSS conditional GET; validators are sent only for a
--                 list the current parser built
--   listed_at     the last successful listing; checked_at: the start of the last check;
--   requested_at  the last Check now; full_recheck_at: the last whole-entry re-resolve
--   failures      +1 when a check starts, 0 after a successful listing (a crash counts as a failure)
CREATE TABLE music_listings (
    entry_id INTEGER PRIMARY KEY REFERENCES music_entries(id) ON DELETE CASCADE,
    title TEXT,
    lan INTEGER,
    fallback INTEGER NOT NULL DEFAULT 0,
    capped INTEGER NOT NULL DEFAULT 0,
    cut INTEGER NOT NULL DEFAULT 0,
    keep_end TEXT CHECK (keep_end IS NULL OR keep_end IN ('newest', 'first')),
    item_count INTEGER NOT NULL DEFAULT 0,
    bytes INTEGER NOT NULL DEFAULT 0,
    cover_url TEXT,
    cover_hash TEXT,
    cover_at TEXT,
    root_at TEXT,
    etag TEXT,
    last_modified TEXT,
    body_sha256 TEXT,
    parser INTEGER,
    version TEXT,
    listed_at TEXT,
    checked_at TEXT,
    requested_at TEXT,
    full_recheck_at TEXT,
    error TEXT,
    error_at TEXT,
    failing_since TEXT,
    failures INTEGER NOT NULL DEFAULT 0,
    requests INTEGER NOT NULL DEFAULT 0
);

-- An entry's episodes, oldest first by `seq`. `state`: 'ok' (it has a URL), 'pending' (a stub
-- whose manifest isn't resolved yet; never sent to phones) or 'gone' (no URL now: not playable,
-- rights ended, left the feed; sent with url null). `available_until` NULL = always.
CREATE TABLE music_items (
    entry_id INTEGER NOT NULL REFERENCES music_entries(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    seq INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ok', 'pending', 'gone')),
    title TEXT,
    url TEXT,
    hls INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER,
    art_url TEXT,
    published_at TEXT,
    available_until TEXT,
    first_seen_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    reported_at TEXT,
    rechecked_at TEXT,
    recheck INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (entry_id, key)
);
CREATE INDEX music_items_seq ON music_items(entry_id, seq);

-- The library carries each entry's listing version (`items`), the listing's cover as a fallback and,
-- for an `auto` NRK series, the order its kept end implies: those move the library revision (0050's
-- rule for a new table that feeds the library). Item rows reach the library only through the
-- version, which the sweep writes in the same transaction, so `music_items` needs no triggers.
CREATE TRIGGER music_listings_insert_revision AFTER INSERT ON music_listings
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_listings_update_revision AFTER UPDATE ON music_listings
WHEN OLD.version IS NOT NEW.version OR OLD.cover_hash IS NOT NEW.cover_hash
    OR OLD.keep_end IS NOT NEW.keep_end
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_listings_delete_revision AFTER DELETE ON music_listings
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;
