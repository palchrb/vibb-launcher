-- Design 24 §3.4: what the phones report about the parent PIN - every correct use (`unlocked`,
-- with the end of the window it opened), wrong tries 1-4 (`wrong`, `failures`) and lockouts
-- (`lockout`, `level`, `until_ms`). `event_id` is the phone's 128-bit random id, so a re-sent
-- batch stores nothing twice. `clock_off = 1`: the phone's clock was implausible, and
-- `occurred_at_ms` is when the server received it. No PIN material. Kept 90 days
-- (retention::prune, by received_at).
CREATE TABLE device_pin_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    event_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    entry TEXT NOT NULL,
    occurred_at_ms INTEGER NOT NULL,
    until_ms INTEGER,
    failures INTEGER,
    level INTEGER,
    clock_off INTEGER NOT NULL DEFAULT 0,
    received_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (device_id, event_id)
);
CREATE INDEX device_pin_events_recent ON device_pin_events (device_id, occurred_at_ms DESC);
