-- Design 139 part A (docs/design/139-app-groups.md §5.1): app groups - a named set of a phone's apps with one daily
-- limit for all of them together, per weekday - and extra time for one group as a third lift target, `group`, on the
-- one lift path.

-- One phone's groups (D1). `apps_json`: package names, at most 50, an app in at most one of the phone's groups (D2,
-- checked on save). `days_json`: 7 entries Monday first, minutes 0-1440 or null for no limit that day (D3).
CREATE TABLE app_groups (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    apps_json  TEXT    NOT NULL DEFAULT '[]',
    days_json  TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_app_groups_device ON app_groups (device_id);

-- `time_lifts` takes target `group` with the group (`group_id`, NULL once the group is gone) and its name
-- (`group_name`, Activity's words then). SQLite can't widen a CHECK in place, and this file runs in sqlx's
-- transaction with foreign keys on, so the table and its child `time_request_answers` are rebuilt the 0099 way: each
-- old table renamed first (the child's REFERENCES follows the rename), the new ones made with today's columns, the rows
-- copied with their ids, the old child dropped before the old lifts (no ON DELETE SET NULL runs on a copied answer),
-- the indexes made again under their old names, and the counter kept at the highest id ever given.
DROP INDEX idx_time_lifts_device;
DROP INDEX time_lifts_phone_key;
DROP INDEX idx_time_request_answers_lift;
ALTER TABLE time_request_answers RENAME TO old_time_request_answers;
ALTER TABLE time_lifts RENAME TO old_time_lifts;

-- 0025's table, 0147's `phone_key`, and the two columns above.
CREATE TABLE time_lifts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    target TEXT NOT NULL CHECK (target IN ('rule', 'budget', 'group')),
    rule_id INTEGER,
    rule_name TEXT,
    minutes INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT NOT NULL,
    created_by TEXT,
    ended_early_at TEXT,
    phone_key TEXT,
    group_id INTEGER REFERENCES app_groups(id) ON DELETE SET NULL,
    group_name TEXT
);

-- 0149's table as it is.
CREATE TABLE time_request_answers (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    answer TEXT NOT NULL CHECK (answer IN ('yes', 'no', 'superseded')),
    lift_id INTEGER REFERENCES time_lifts(id) ON DELETE SET NULL,
    answered_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    by_name TEXT,
    answered_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, request_id)
);

INSERT INTO time_lifts (id, device_id, target, rule_id, rule_name, minutes, created_at, expires_at, created_by,
    ended_early_at, phone_key)
  SELECT id, device_id, target, rule_id, rule_name, minutes, created_at, expires_at, created_by, ended_early_at,
    phone_key
  FROM old_time_lifts;
INSERT INTO time_request_answers (device_id, request_id, answer, lift_id, answered_by, by_name, answered_at)
  SELECT device_id, request_id, answer, lift_id, answered_by, by_name, answered_at FROM old_time_request_answers;

-- The highest id ever given (a pruned lift's too): the renamed table kept its counter under the old name, which its
-- drop removes.
INSERT INTO sqlite_sequence (name, seq)
  SELECT 'time_lifts', 0 WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'time_lifts');
UPDATE sqlite_sequence SET seq = MAX(seq,
    COALESCE((SELECT o.seq FROM sqlite_sequence o WHERE o.name = 'old_time_lifts'), 0))
  WHERE name = 'time_lifts';

DROP TABLE old_time_request_answers;
DROP TABLE old_time_lifts;

CREATE INDEX idx_time_lifts_device ON time_lifts(device_id, created_at);
CREATE UNIQUE INDEX time_lifts_phone_key ON time_lifts (device_id, phone_key, rule_id) WHERE phone_key IS NOT NULL;
CREATE INDEX idx_time_request_answers_lift ON time_request_answers(device_id, lift_id);
