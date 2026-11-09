-- Design 77 step 4 (§7 "The rate", QA R2-M3): a per-kind push budget in `parent_events::raise`
-- (`Kind::push_budget`; `sms_held`: 3 pushes a phone's hour, 1 a sender). An event pushed within its budget keeps its
-- budget key (`sms_held`: the sender), so the next one counts the hour's pushes and the key's; an event past the
-- budget (inbox only, counted into the hour's digest) and every other kind's keep NULL.
ALTER TABLE parent_events ADD COLUMN push_budget_key TEXT;
CREATE INDEX idx_parent_events_budget ON parent_events(device_id, kind, created_at)
    WHERE push_budget_key IS NOT NULL;
