-- Launcher crash reports (cleanup 2026-10-06), replacing upstream's on-phone "copy the crash
-- log" screen: the stack-trace hash, a short trace (exception class names and frames, never a
-- message), how often and when (phone clock, ms), and the build. One row per phone and hash;
-- pruned 30 days after it was last reported (retention::prune).
CREATE TABLE device_crashes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    hash TEXT NOT NULL,
    trace TEXT NOT NULL,
    count INTEGER NOT NULL,
    first_at_ms INTEGER NOT NULL,
    last_at_ms INTEGER NOT NULL,
    app_version_code INTEGER NOT NULL,
    reported_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (device_id, hash)
);
