-- Design 66: a phone's Bedtime is one flagged `time_rules` row (kind `bedtime`, name "Bedtime", Sun-Thu nights on
-- days 6, 0-3, Fri-Sat nights on days 4-5), the only rule that may run past midnight. At most one per phone, never in
-- everyone's rules (the index doesn't cover a NULL `device_id`). `time_rules::migrate_crossing` turns the rules that
-- crossed midnight into it, or splits them at midnight, at every start.
ALTER TABLE time_rules ADD COLUMN bedtime INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX idx_time_rules_bedtime ON time_rules(device_id) WHERE bedtime = 1;
