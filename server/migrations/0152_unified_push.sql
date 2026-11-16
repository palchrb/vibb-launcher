-- Design 108 (Vibb as the UnifiedPush distributor, docs/design/108-unified-push.md §3.5): the phones' public address
-- for app push and the one it replaced (accepted 30 days, M4), the phone's own switch (on by default, Q3/A1: nothing
-- changes on a phone until an address is saved), the registrations (the endpoint secret only as its SHA-256), the
-- queue, the tombstones of deleted secrets (M5) and the parent's stopped apps.
CREATE TABLE unified_push_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    public_origin TEXT,
    previous_origin TEXT,
    origin_changed_at TEXT,
    -- "Last seen from outside" (§3.1): the newest discovery GET, written at most once a minute.
    last_discovery_at TEXT
);
INSERT INTO unified_push_settings (id) VALUES (1);
ALTER TABLE devices ADD COLUMN unified_push INTEGER NOT NULL DEFAULT 1;
CREATE TABLE up_registrations (
    id INTEGER PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    reg TEXT NOT NULL,                       -- the phone's key, 32 lowercase hex
    secret_hash BLOB NOT NULL UNIQUE,        -- SHA-256 of the endpoint secret; the secret is never stored
    package TEXT NOT NULL,
    vapid TEXT,                              -- the app's 87-char VAPID key, or NULL
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_push_at TEXT,
    last_delivered_at TEXT,
    outside_gateway_at TEXT,                 -- a Matrix body that came to /up directly (R2's warning)
    pushes INTEGER NOT NULL DEFAULT 0,
    dropped INTEGER NOT NULL DEFAULT 0,
    UNIQUE (device_id, reg)
);
CREATE INDEX up_registrations_package ON up_registrations (device_id, package);
CREATE TABLE up_messages (
    id INTEGER PRIMARY KEY,
    key TEXT NOT NULL UNIQUE,                -- 16 random bytes, hex: Location and the MESSAGE id (L10)
    registration_id INTEGER NOT NULL REFERENCES up_registrations(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL,
    body BLOB NOT NULL,                      -- 1..4096 bytes (a Matrix body minimised, Q8); never logged
    urgency TEXT NOT NULL,
    topic TEXT,
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL
);
CREATE INDEX up_messages_device ON up_messages (device_id, id);
CREATE INDEX up_messages_registration ON up_messages (registration_id, id);
CREATE INDEX up_messages_expiry ON up_messages (expires_at_ms);
CREATE TABLE up_gone (                       -- M5: tombstones, pruned after 30 days
    secret_hash BLOB PRIMARY KEY,
    gone_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE up_stopped (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package TEXT NOT NULL,
    stopped_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, package)
);
-- Every deletion of a registration leaves its tombstone, whatever deletes it (the device API, Stop, the switch, the
-- address cleared, the allowlist prune, retention, and a phone deleted through ON DELETE CASCADE): the secret is
-- answered 410 / rejected for 30 days, and never counted toward a client's ban (M5).
CREATE TRIGGER up_registrations_tombstone BEFORE DELETE ON up_registrations
BEGIN
    INSERT OR REPLACE INTO up_gone (secret_hash, gone_at) VALUES (OLD.secret_hash, datetime('now'));
END;
