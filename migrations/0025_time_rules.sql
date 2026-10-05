-- handy step 6 (docs/design/06-time-rules.md in the handy workspace): named time rules, a daily
-- screen-time budget, parent lifts and a per-device location policy.

-- Named rules. device_id NULL = a global rule; a device with custom_schedule_enabled uses its own
-- rows (device_id = its id) instead of the global ones. days_json is 7 entries, Monday first, each
-- {"start": m, "end": m} (minutes 0-1439, local time on the phone) or null (not active that day);
-- start > end ends the next day, start == end lasts 24 h. exempt_apps_json: package names that
-- stay usable while the rule is active.
CREATE TABLE time_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER REFERENCES devices(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('school', 'bedtime', 'custom')),
    calls_allowed INTEGER NOT NULL,
    exempt_apps_json TEXT NOT NULL DEFAULT '[]',
    days_json TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_time_rules_device ON time_rules(device_id);

-- Daily screen-time budget, 7 entries Monday first, minutes or null (unlimited). Global default on
-- the global_schedule singleton; a device with custom_schedule_enabled uses its own.
ALTER TABLE global_schedule ADD COLUMN daily_budget_json TEXT NOT NULL
    DEFAULT '[null,null,null,null,null,null,null]';
ALTER TABLE device_policy ADD COLUMN daily_budget_json TEXT NOT NULL
    DEFAULT '[null,null,null,null,null,null,null]';

-- The old weekday/weekend/bedtime columns are converted into rules once, at startup
-- (src/time_rules.rs, migrate_legacy). Rows that exist now still need it; rows created later
-- (new devices) have nothing to convert.
ALTER TABLE global_schedule ADD COLUMN rules_migrated INTEGER NOT NULL DEFAULT 1;
UPDATE global_schedule SET rules_migrated = 0;
ALTER TABLE device_policy ADD COLUMN rules_migrated INTEGER NOT NULL DEFAULT 1;
UPDATE device_policy SET rules_migrated = 0;

-- A parent lifting a rule ("end school mode for 30 min") or adding screen time ("+30 min").
-- rule_id NULL with target 'rule' = every rule. expires_at (UTC, datetime()) ends delivery to the
-- phone; ended_early_at is set by "End now".
CREATE TABLE time_lifts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    target TEXT NOT NULL CHECK (target IN ('rule', 'budget')),
    rule_id INTEGER,
    rule_name TEXT,
    minutes INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT NOT NULL,
    created_by TEXT,
    ended_early_at TEXT
);
CREATE INDEX idx_time_lifts_device ON time_lifts(device_id, created_at);

-- Location policy: off / on_request (a fix only for locate/ring) / interval (every N minutes).
ALTER TABLE device_policy ADD COLUMN location_mode TEXT NOT NULL DEFAULT 'on_request'
    CHECK (location_mode IN ('off', 'on_request', 'interval'));
ALTER TABLE device_policy ADD COLUMN location_interval_minutes INTEGER NOT NULL DEFAULT 30;

-- The launcher's time_state (active rule, screen time used/budget), opaque JSON.
ALTER TABLE device_status ADD COLUMN time_state_json TEXT;
