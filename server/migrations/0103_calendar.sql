-- Design 69 ("Dagen", docs/design/69-dagen.md at the monorepo root), all of it in one file: the phone's calendars as
-- the parent set them, the title rules, the phone-wide switches and the phone's last calendar report. No event, title or
-- description is ever stored: the phone reads its calendar provider and applies the rules itself.

-- One row per phone and calendar the parent saved (the calendar key: 16 hex of SHA-256 over the provider's account
-- type, account name and NAME, computed on the phone). No row = shown, reminders off, not the kid's. At most one
-- calendar a phone is the one the kid may add events to (`idx_device_calendars_kid`); none = the phone's own local
-- "Min kalender".
CREATE TABLE device_calendars (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    calendar_key TEXT NOT NULL CHECK (length(calendar_key) = 16),
    shown INTEGER NOT NULL DEFAULT 1,
    remind TEXT NOT NULL DEFAULT 'off' CHECK (remind IN ('off', 'own', 'before')),
    remind_minutes INTEGER CHECK (remind_minutes IN (5, 10, 15, 30, 60)),
    writable_for_kid INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, calendar_key)
);
CREATE UNIQUE INDEX idx_device_calendars_kid ON device_calendars(device_id) WHERE writable_for_kid = 1;

-- The title rules in order (the first that applies wins). `calendar_key` NULL = any calendar. A description rule only
-- hides; replace and prefix are the title's. The caps (30 a phone, the texts' lengths) are code's (`calendar.rs`).
CREATE TABLE device_calendar_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    calendar_key TEXT CHECK (calendar_key IS NULL OR length(calendar_key) = 16),
    field TEXT NOT NULL CHECK (field IN ('title', 'description')),
    match_kind TEXT NOT NULL CHECK (match_kind IN ('starts', 'contains')),
    pattern TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('replace', 'prefix', 'hide')),
    replacement TEXT NOT NULL DEFAULT '',
    CHECK (field = 'title' OR action = 'hide')
);
CREATE INDEX idx_device_calendar_rules ON device_calendar_rules(device_id, sort_order);

-- The phone-wide switches: Dagen on Home (off hides the tile, the glance line and the reminders), the kid may add
-- events, the lock glance's "Neste" line, and the lock's reminders (`off` or the calm `row`) - the master switch over
-- every calendar's reminder.
ALTER TABLE device_policy ADD COLUMN calendar_on_home INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN calendar_kid_adds INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN calendar_glance_next INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN calendar_lock_reminders TEXT NOT NULL DEFAULT 'off'
    CHECK (calendar_lock_reminders IN ('off', 'row'));

-- The phone's last `calendar` report, as `calendar::sanitize_report` kept it: whether it can read its calendars and
-- which it has (key, account type, display name, the feed's host, access level), never an event.
ALTER TABLE devices ADD COLUMN calendar_report TEXT;
