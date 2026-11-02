-- Design 72b (docs/design/72b-caldav-write-back.md at the monorepo root): the kid's own events, made on the phone in
-- Kalender's "Min kalender", written by the server to the one CalDAV calendar the parent marks as the kid's, with the
-- account's app password (which never goes to the phone); the parent's changes there come back to the phone as
-- acknowledged hand-backs. Numbered 0120 (0117 on its branch): past calendar-in-rules' 0119, which may ship first -
-- a lower number arriving after a higher one gets no pre-migration backup.

-- The mark: one calendar per phone, a CalDAV one (`calendar_sources.kind = 'caldav'`, checked in code), on the phone's
-- row of that calendar as 0110's comment reserved. Unticking the calendar (the row's delete) ends the mark and, by
-- the cascades below, everything the server keeps of the kid's events.
ALTER TABLE device_calendar_feeds ADD COLUMN kid_writes INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX idx_device_calendar_feeds_kid ON device_calendar_feeds(device_id) WHERE kid_writes = 1;

-- The phone's last set (D1.3, D4.1): the kid's coming events as the phone sent them - titles, places, descriptions,
-- times; replaced at every POST, gone with the mark. `rev` the set's (the phone's), `accepted` the last set the server
-- took (it stays the old one while a mass delete is held), `from_at` the phone's clock, `cut`/`cut_from`/`cut_uids`
-- (JSON; NULL = the list was too long, nothing is deleted) and `ended` (JSON uids, no text) as sent. `rejected` the
-- code of the last set refused with a 400 (`bad_set`, `tag_pinned:<device id>`), `error` the phone's stop or the last
-- 413 (`write_refused`, `full`, `no_etag`, `too_big`). `held_since` the kid's mass delete held 24 h by time (D2.6),
-- `back_held_since` the calendar's.
CREATE TABLE device_kid_events (
    device_id INTEGER PRIMARY KEY,
    source_id INTEGER NOT NULL,
    rev TEXT,
    accepted TEXT,
    events_json TEXT,
    from_at TEXT,
    cut INTEGER NOT NULL DEFAULT 0,
    cut_from TEXT,
    cut_uids TEXT,
    ended TEXT NOT NULL DEFAULT '[]',
    rejected TEXT,
    rejected_at TEXT,
    received_at TEXT,
    written_at TEXT,
    posted_at TEXT,
    error TEXT,
    error_at TEXT,
    held_since TEXT,
    held_kind TEXT CHECK (held_kind IS NULL OR held_kind = 'kid'),
    held_deletes INTEGER NOT NULL DEFAULT 0,
    back_held_since TEXT,
    FOREIGN KEY (device_id, source_id) REFERENCES device_calendar_feeds(device_id, source_id) ON DELETE CASCADE
);

-- One kid event the server wrote or read for one phone (D4.1): its resource (`href`, `etag` verbatim), the hashes of
-- D2.7 - `sent_hash` the phone's version last taken, `remote_hash` the calendar's last written or read (the base),
-- `attempted_hash` the version of a write in flight (a lost answer is recognised by it) - the times as last known,
-- and a waiting hand-back (`back_id`, `back_json`; `back_after`: the calendar's mass delete held until then).
-- `state`: `pending` (a write to make), `deleting` (a delete to make), `ok`, `kept` (missing from a cut or
-- clock-frozen set: neither written nor deleted, not pending), `refused` (the calendar refused this
-- version), `released` (a tombstone: the parent made it a series or invited people - never written again until absent
-- from the phone's sets 60 days), `past` (ended - never deleted, never written), `gone` (deleted on the calendar,
-- counted as pending until the fetch after it). `fetch_wait` the last write's time: pending until a fetch that started
-- later stored the set (R-L2). A `past` or `released` row keeps its hashes and no text.
CREATE TABLE kid_caldav_events (
    device_id INTEGER NOT NULL,
    uid TEXT NOT NULL,
    source_id INTEGER NOT NULL,
    href TEXT,
    etag TEXT,
    sent_hash TEXT,
    remote_hash TEXT,
    attempted_hash TEXT,
    sequence INTEGER NOT NULL DEFAULT 0,
    created_at_remote TEXT,
    start_at TEXT,
    end_at TEXT,
    state TEXT NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'deleting', 'ok', 'kept', 'refused', 'released', 'past', 'gone')),
    refused_code TEXT,
    failures INTEGER NOT NULL DEFAULT 0,
    next_at TEXT,
    fetch_wait TEXT,
    back_id TEXT,
    back_json TEXT,
    back_after TEXT,
    released_absent_since TEXT,
    -- "Legg dem tilbake på telefonen": the job reads it from the calendar and hands it back; never deleted meanwhile.
    restore_wanted INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, uid),
    FOREIGN KEY (device_id, source_id) REFERENCES device_calendar_feeds(device_id, source_id) ON DELETE CASCADE
);
CREATE INDEX idx_kid_caldav_events_source ON kid_caldav_events(source_id);

-- The phone tags (the 8 hex of a kid UID) and the device that first posted each (D4.9): a tag another device holds is
-- refused. No text; the device's delete frees its tags.
CREATE TABLE kid_caldav_tags (
    tag TEXT PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE
);

-- One write job per account at a time (QA L4), its pace (60 writes a minute) and its backoff after a 429/503 (QA L1).
ALTER TABLE caldav_accounts ADD COLUMN write_claimed_at TEXT;
ALTER TABLE caldav_accounts ADD COLUMN write_claim TEXT;
ALTER TABLE caldav_accounts ADD COLUMN write_minute_at TEXT;
ALTER TABLE caldav_accounts ADD COLUMN write_minute_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE caldav_accounts ADD COLUMN write_backoff_until TEXT;
ALTER TABLE caldav_accounts ADD COLUMN write_failures INTEGER NOT NULL DEFAULT 0;
