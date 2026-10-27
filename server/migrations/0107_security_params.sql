-- Design 70 (round S-i18n, docs/design/70-pwa-i18n.md §4, slice 2): a security-log row's words as a catalog
-- key and its parameters, worded in the reader's language when the log is read - {"key": "security.detail.<name>",
-- "p": {..}}, or a list of those for a line of several sentences. `detail` keeps the facts (a route, a tag, an
-- address, a phone line's "device {id}", which the hourly budget counts by) and the prose of rows from before.
-- 0106 is the calendar colours' (branch `calendar-colours`, merged beside this one).
ALTER TABLE security_events ADD COLUMN params TEXT;
