-- App update notices (2026-10-25, the user: "sende notifications når apper lagt til via pwa blir oppdatert? Trenger
-- ikke nødvendigvis være push varsel ... kanskje man kan få valget"), all of it in one file: two event kinds told
-- from `params` (design 64's way, `parent_events::told`) - `app_update_available` when the sweep or Check Now stores a
-- new release of a catalog app (one event per release, no phone) and `app_updated` when a phone's report shows that
-- release installed (one per phone and version) -, and each parent's Notify Me About switch "App updates".

-- Off by default for every parent, existing ones included (the user: "kanskje man kan få valget"); a parent with no
-- row reads it off too (`parent_events::switch_default`, the only switch that isn't on with no row).
ALTER TABLE notification_prefs ADD COLUMN push_app_updates INTEGER NOT NULL DEFAULT 0;

-- An `app_update_available` is the catalog's, not a phone's: `parent_events.device_id` becomes nullable. SQLite can't
-- loosen a NOT NULL in place, and this file runs in sqlx's transaction with foreign keys on (no PRAGMA foreign_keys =
-- OFF, which a transaction ignores), so the table and its two children are rebuilt together: each old table is
-- renamed first (a child's REFERENCES follows the rename), the new ones are made with today's columns, the rows are
-- copied with their ids, then the old children are dropped before the old events, so no ON DELETE action runs on a
-- row that was copied. Every index is made again under its old name; `sqlite_sequence` keeps the highest id ever
-- given, so an event or a delivery id is never reused (a push's tag `e<id>` and a receipt's `d` name them).
DROP INDEX idx_parent_events_created;
DROP INDEX idx_push_deliveries_subscription;
DROP INDEX idx_push_deliveries_event;
DROP INDEX idx_parent_event_reads_event;
ALTER TABLE parent_event_reads RENAME TO old_parent_event_reads;
ALTER TABLE push_deliveries RENAME TO old_push_deliveries;
ALTER TABLE parent_events RENAME TO old_parent_events;

-- 0078's table, 0088 without `read_at`, 0091's `params`, 0098's push words; `device_id` NULL = no phone (a catalog
-- event). The unique key keeps a phone's events one row each; a catalog event's is the partial index below (NULLs
-- are distinct in a UNIQUE constraint).
CREATE TABLE parent_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER REFERENCES devices(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    dedup_key TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    target TEXT NOT NULL,
    detail TEXT,
    happened_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    params TEXT,
    push_title TEXT,
    push_body TEXT,
    UNIQUE (device_id, kind, dedup_key)
);

-- 0078's table as it is.
CREATE TABLE push_deliveries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel TEXT NOT NULL DEFAULT 'webpush',
    subscription_id INTEGER NOT NULL REFERENCES push_subscriptions(id) ON DELETE CASCADE,
    event_id INTEGER REFERENCES parent_events(id) ON DELETE SET NULL,
    kind TEXT NOT NULL,
    token_hash BLOB NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_try_at TEXT,
    http_status INTEGER,
    reason TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    accepted_at TEXT,
    receipt_at TEXT,
    clicked_at TEXT
);

-- 0088's table as it is.
CREATE TABLE parent_event_reads (
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    event_id INTEGER NOT NULL REFERENCES parent_events(id) ON DELETE CASCADE,
    PRIMARY KEY (admin_id, event_id)
) WITHOUT ROWID;

INSERT INTO parent_events (id, device_id, kind, dedup_key, title, summary, target, detail, happened_at, created_at,
    params, push_title, push_body)
  SELECT id, device_id, kind, dedup_key, title, summary, target, detail, happened_at, created_at, params, push_title,
    push_body
  FROM old_parent_events;
INSERT INTO push_deliveries (id, channel, subscription_id, event_id, kind, token_hash, status, attempts, next_try_at,
    http_status, reason, created_at, accepted_at, receipt_at, clicked_at)
  SELECT id, channel, subscription_id, event_id, kind, token_hash, status, attempts, next_try_at, http_status, reason,
    created_at, accepted_at, receipt_at, clicked_at
  FROM old_push_deliveries;
INSERT INTO parent_event_reads (admin_id, event_id)
  SELECT admin_id, event_id FROM old_parent_event_reads;

-- The highest id ever given (a pruned row's too), not only the highest copied: the renamed tables kept their
-- counters under the old names, which their drop removes.
INSERT INTO sqlite_sequence (name, seq)
  SELECT 'parent_events', 0 WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'parent_events');
UPDATE sqlite_sequence SET seq = MAX(seq,
    COALESCE((SELECT o.seq FROM sqlite_sequence o WHERE o.name = 'old_parent_events'), 0))
  WHERE name = 'parent_events';
INSERT INTO sqlite_sequence (name, seq)
  SELECT 'push_deliveries', 0 WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'push_deliveries');
UPDATE sqlite_sequence SET seq = MAX(seq,
    COALESCE((SELECT o.seq FROM sqlite_sequence o WHERE o.name = 'old_push_deliveries'), 0))
  WHERE name = 'push_deliveries';

DROP TABLE old_parent_event_reads;
DROP TABLE old_push_deliveries;
DROP TABLE old_parent_events;

CREATE INDEX idx_parent_events_created ON parent_events(created_at DESC);
CREATE UNIQUE INDEX idx_parent_events_catalog ON parent_events(kind, dedup_key) WHERE device_id IS NULL;
CREATE INDEX idx_push_deliveries_subscription ON push_deliveries(subscription_id, accepted_at);
CREATE INDEX idx_push_deliveries_event ON push_deliveries(event_id);
CREATE INDEX idx_parent_event_reads_event ON parent_event_reads(event_id);
