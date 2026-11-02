-- Kalender usable during a time rule (the user, 2026-11-02; PLAN "Kalender usable during a time rule"): a rule's or a
-- phone's bedtime's "Vibb-kalender" row under Exceptions, served as `time_policy.rules[].calendar_allowed`. Off for
-- every rule stored before it, as the phone kept Kalender closed under every rule until now. 0117 and 0118 are taken
-- on other branches (CalDAV, passkeys step 2).
ALTER TABLE time_rules ADD COLUMN calendar_allowed INTEGER NOT NULL DEFAULT 0;
