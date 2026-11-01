-- Tidsregler (2026-11-01, docs/notes/server/time-rules.md "Time Rules and Device › Time"): Device › Time's one
-- "Own rules" switch becomes a switch per rule. A row here means the rule does not apply to that phone. The rules in
-- force for a phone (`time_rules::effective_rule_rows`) are everyone's rules and its own rules, each unless it has a
-- row here, then its bedtime (never switched off). A rule added later has no row, so it is on for every phone.
-- `device_policy.custom_schedule_enabled` keeps only the daily limit's switch ("Own limit": its own
-- `daily_budget_json` instead of everyone's).
CREATE TABLE device_rule_off (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    rule_id INTEGER NOT NULL REFERENCES time_rules(id) ON DELETE CASCADE,
    PRIMARY KEY (device_id, rule_id)
);
CREATE INDEX idx_device_rule_off_rule ON device_rule_off(rule_id);

-- Every phone keeps the rules it had: one on Own rules has every one of everyone's rules off ...
INSERT INTO device_rule_off (device_id, rule_id)
SELECT p.device_id, r.id FROM device_policy p
JOIN time_rules r ON r.device_id IS NULL AND r.bedtime = 0
WHERE p.custom_schedule_enabled = 1;

-- ... and one on everyone's rules has its own rules (kept from a time on Own rules, or converted from its pre-step-6
-- schedule) off, as they were not in force. They show switched off under its own rules.
INSERT OR IGNORE INTO device_rule_off (device_id, rule_id)
SELECT r.device_id, r.id FROM time_rules r
LEFT JOIN device_policy p ON p.device_id = r.device_id
WHERE r.device_id IS NOT NULL AND r.bedtime = 0 AND COALESCE(p.custom_schedule_enabled, 0) = 0;

-- A request for more time opens the phone's Quick actions now: Device › Time no longer has Extra Time Today.
UPDATE parent_events SET target = '/devices/' || device_id || '#quick'
WHERE kind = 'extra_time_request' AND device_id IS NOT NULL;
