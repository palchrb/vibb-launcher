-- Push never lost silently (2026-11-04): the PWA sends this browser's subscription again by itself when the server
-- has lost it (static/register-sw.js), so a subscription a parent ended on purpose - Remove under Getting
-- Notifications, Turn Off on This Device - is noted here by its endpoint's hash (push::endpoint_hash, 16 hex; never
-- the endpoint) and the app's own re-send never brings it back. A tap on Turn On does, and deletes the row. Kept as
-- long as the security log (retention). 0127 is another branch's.
CREATE TABLE push_removed (
    endpoint_hash TEXT PRIMARY KEY,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    removed_at TEXT NOT NULL DEFAULT (datetime('now'))
);
