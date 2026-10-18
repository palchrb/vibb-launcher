-- Design 35 (notifications to the parent, phase 1), every table of it in one file (design QA M1):
-- the events and the inbox (step 1), the per-kind push switches, the battery columns, and the Web
-- Push tables, which stay empty until step 2. Design 36 takes 0079.

-- What happened on the phones (design 35 §2-§3): one row per event, raised once
-- (`parent_events::raise`, ON CONFLICT DO NOTHING on the unique key). The words are made at
-- raise from typed values and kept, so a renamed phone keeps old rows' words. `target` is a path
-- on this server ("/devices/3/calls"); `detail` is shown in the inbox only, never pushed. Kept 90
-- days by `created_at` (`retention::prune`).
CREATE TABLE parent_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    dedup_key TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    target TEXT NOT NULL,
    detail TEXT,
    happened_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    read_at TEXT,
    UNIQUE (device_id, kind, dedup_key)
);
CREATE INDEX idx_parent_events_created ON parent_events(created_at DESC);
-- The bell's count.
CREATE INDEX idx_parent_events_unread ON parent_events(id) WHERE read_at IS NULL;

-- Which kinds are pushed (§2.4; every kind is stored whatever these say). The emergency call is
-- always pushed and has no column.
CREATE TABLE notification_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    push_pin_used INTEGER NOT NULL DEFAULT 1,
    push_pin_lockout INTEGER NOT NULL DEFAULT 1,
    push_foreign_access INTEGER NOT NULL DEFAULT 1,
    push_unseen_boot INTEGER NOT NULL DEFAULT 1,
    push_low_battery INTEGER NOT NULL DEFAULT 1
);
INSERT INTO notification_settings (id) VALUES (1);

-- The battery (§2.5): the latch (NULL = armed; set when a report said `low`, cleared when one said
-- neither low nor charging) and the newest report's reading (NULL without `battery`).
ALTER TABLE devices ADD COLUMN battery_low_since TEXT;
ALTER TABLE device_status ADD COLUMN battery_level INTEGER;
ALTER TABLE device_status ADD COLUMN battery_charging INTEGER;

-- Web Push (step 2): a browser's subscription, the admin's. `label` comes from the User-Agent at
-- subscribe ("iPhone · Safari"; the raw string is never stored), `vapid_fp` is the server key it
-- was made for. `last_receipt_at` outlives the deliveries' pruning. `gone` ones are pruned after
-- 30 days.
CREATE TABLE push_subscriptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE CHECK (length(endpoint) <= 1024),
    p256dh BLOB NOT NULL,
    auth BLOB NOT NULL,
    origin TEXT NOT NULL,
    label TEXT NOT NULL,
    vapid_fp TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_receipt_at TEXT,
    state TEXT NOT NULL DEFAULT 'active',
    gone_at TEXT
);

-- One push to one subscription (step 2): an event's, a summary or a test. The receipt token is
-- kept only as its SHA-256. Pruned after 30 days.
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
CREATE INDEX idx_push_deliveries_subscription ON push_deliveries(subscription_id, accepted_at);
CREATE INDEX idx_push_deliveries_event ON push_deliveries(event_id);
