-- Design 116 (failures made visible, consolidation C2, docs/design/116-failures-visible.md). Design 114 holds 0157.
--
-- Part B (§2.1): each outside call's last success and last failure, per integration key and scope, written by
-- integration_health::ok / ::failed at the call site. `key` is the integration's name (github, fdroid, met, caldav,
-- caldav_write, calendar_link, music_feed, storytel, spotify, blocklist, backup, ...); `scope` is '' (family-wide) or
-- 'app:<id>', 'account:<id>', 'source:<id>', 'device:<id>', 'entry:<id>', 'feed:<id>', 'file:<name>'. Times are UTC
-- 'YYYY-MM-DD HH:MM:SS'. `error` is the call site's own code (logged, never shown). `failing_since` is the first
-- failure after the last success, NULL while it works; `failures` counts them. A row with neither time in 30 days
-- goes (retention::prune).
CREATE TABLE integration_health (
    key TEXT NOT NULL,
    scope TEXT NOT NULL,
    ok_at TEXT,
    error_at TEXT,
    error TEXT,
    class TEXT CHECK (class IN ('transient', 'parent', 'stop')),
    failing_since TEXT,
    failures INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (key, scope)
);

-- Part C (§3.4): the report's `vpn_filter` - {state: up|down|off, down_ms?, period?} - cleaned by
-- mobile_network::clean_vpn_filter. NULL from a launcher that doesn't send it.
ALTER TABLE device_status ADD COLUMN vpn_filter_json TEXT;

-- Notify Me About's "Web filter down" switch (the event filter_down, urgent like foreign_access), on by default.
ALTER TABLE notification_prefs ADD COLUMN push_filter_down INTEGER NOT NULL DEFAULT 1;
