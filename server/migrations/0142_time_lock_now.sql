-- Design 93 (docs/design/93-lock-now-quick-actions.md, §3): a rule turned on now, for the rest of a window today
-- ("Slå på nå"). Running = not ended early and `ends_at` ahead; at most one running per phone (the insert guards it).
-- The mode is the rule's, read live when the policy is built; a deleted rule ends it (`rule_id` NULL, not served).
CREATE TABLE time_lock_now (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    rule_id INTEGER REFERENCES time_rules(id) ON DELETE SET NULL,
    rule_name TEXT NOT NULL,        -- the stored name at the start, for Activity once the rule is gone
    zone TEXT NOT NULL,             -- the phone's IANA zone (time_state.zone) at the start
    local_date TEXT NOT NULL,       -- YYYY-MM-DD in that zone
    start_minute INTEGER NOT NULL CHECK (start_minute BETWEEN 0 AND 1438),
    end_minute INTEGER NOT NULL CHECK (end_minute BETWEEN 1 AND 1439),   -- 1439 = until midnight
    ends_at TEXT NOT NULL,          -- UTC, datetime() form: the end in the zone
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    created_by TEXT,
    changed_at TEXT,
    changed_by TEXT,
    ended_early_at TEXT,
    ended_by TEXT,
    CHECK (end_minute > start_minute)
);
CREATE INDEX idx_time_lock_now_device ON time_lock_now(device_id, ends_at);
-- A rule switched off for the phone ends its on-lock at once, as a deleted rule does (design 93 §3): the copy is no
-- longer served, so the page must not show it running, nor revive it when the rule is switched on again. `ended_by`
-- stays NULL, which Activity reads as "switched off" (an Avslutt nå always names its parent).
CREATE TRIGGER time_lock_now_rule_off AFTER INSERT ON device_rule_off
BEGIN
    UPDATE time_lock_now SET ended_early_at = datetime('now')
     WHERE device_id = NEW.device_id AND rule_id = NEW.rule_id
       AND ended_early_at IS NULL AND ends_at > datetime('now');
END;
