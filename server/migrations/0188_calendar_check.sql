-- 0188_calendar_check.sql (the owner's ask of 2026-11-28): how often the server checks a calendar source, the parent's
-- choice on Device › Calendar's sources. Every source was checked every 30 min (`calendar_feeds::INTERVAL`).
-- A CalDAV account's calendars: its owner's choice for the whole account, 5 / 15 / 30 / 60 min, 5 by default (an
-- unchanged calendar is one PROPFIND). A link: each phone's own row, 15 / 30 / 60 min, 30 by default; the fetch is
-- shared, so a source is checked at the shortest of the rows that name it. A CalDAV calendar's phone rows keep the
-- column's default and never read it. Only columns are added; every existing row takes the default.
ALTER TABLE caldav_accounts ADD COLUMN check_minutes INTEGER NOT NULL DEFAULT 5
    CHECK (check_minutes IN (5, 15, 30, 60));
ALTER TABLE device_calendar_feeds ADD COLUMN check_minutes INTEGER NOT NULL DEFAULT 30
    CHECK (check_minutes IN (15, 30, 60));
