-- A time rule can silence the phone while it runs (the user, 2026-11-02, PLAN "an alarm ringing during school
-- mode"): the rule editor's and the Bedtime page's "Silent" switch, served as `time_policy.rules[].silent`. The phone
-- turns on its own Do Not Disturb rule while a silent rule is in force: nothing sounds, calls the rule allows still
-- ring; a bedtime lets alarms ring. On for every school rule and every bedtime stored before it (the user: "alt være
-- mutet ... når det er skolemodus by definition", and yes to existing school rules becoming silent) - the kind is the
-- school preset, `kind = 'bedtime'` a phone's Bedtime (`bedtime = 1`) or a day rule kept from before design 66 (a split
-- night's "(morning)" part keeps the kind, so it stays with its evening). Off for every other rule. 0120 is taken on
-- another branch (CalDAV write-back).
ALTER TABLE time_rules ADD COLUMN silent INTEGER NOT NULL DEFAULT 0;
UPDATE time_rules SET silent = 1 WHERE kind IN ('school', 'bedtime') OR bedtime = 1;
