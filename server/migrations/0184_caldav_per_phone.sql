-- Design 136 (docs/design/136-caldav-per-phone.md at the monorepo root), a revision of design 72 step 2 / 72b: a
-- CalDAV account belongs to the phone whose page added it (`owner_device_id`), and is shown only on that phone's
-- Calendar page unless the parent chose "Delt i familien" (`shared`): then every phone's page shows it, to tick its
-- calendars and read them. Only the owner's phone may mark one of its calendars for the kid's events (`kid_writes`),
-- so a phone never writes with another child's login. A NULL owner is an account shared before 136 that no phone
-- wrote to, or one whose phone was deleted (an unshared one is then hidden on every page and swept as unused,
-- `caldav::sweep`); a passing Test with a password typed on a phone's page makes it that phone's. The password stays
-- sealed under `caldav-v1:<fingerprint>:<id>`: the owner is not in it. The design note names this file 0182; designs
-- 138, 132, 135 and 137 took 0180-0183 first, so it is 0184 and follows 0180, 0181, 0182 and 0183.
ALTER TABLE caldav_accounts ADD COLUMN owner_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL;
ALTER TABLE caldav_accounts ADD COLUMN shared INTEGER NOT NULL DEFAULT 0 CHECK (shared IN (0, 1));
CREATE INDEX idx_caldav_accounts_owner ON caldav_accounts(owner_device_id);

-- The accounts of before 136. Calendars ticked on exactly one phone: that phone's, not shared. On two or more
-- phones, or on none: shared, owned by the phone that writes the kid's events to one of its calendars - the lowest
-- device id if several do - else by none.
UPDATE caldav_accounts SET
    owner_device_id = CASE
        WHEN (SELECT COUNT(DISTINCT d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = caldav_accounts.id) = 1
        THEN (SELECT MIN(d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = caldav_accounts.id)
        ELSE (SELECT MIN(d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id
              WHERE s.account_id = caldav_accounts.id AND d.kid_writes = 1)
    END,
    shared = CASE
        WHEN (SELECT COUNT(DISTINCT d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = caldav_accounts.id) = 1
        THEN 0 ELSE 1
    END;

-- A mark on an account the phone doesn't own ends as `calendar_kid_events::unmark` ends one: the two event tables'
-- rows of that phone go (what was written stays in the calendar, the kid's events stay on the phone, the tags stay),
-- then the flag. Only a second phone writing with the same login loses its mark.
DELETE FROM kid_caldav_events WHERE device_id IN (
    SELECT d.device_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    JOIN caldav_accounts a ON a.id = s.account_id
    WHERE d.kid_writes = 1 AND a.owner_device_id IS NOT d.device_id);
DELETE FROM device_kid_events WHERE device_id IN (
    SELECT d.device_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    JOIN caldav_accounts a ON a.id = s.account_id
    WHERE d.kid_writes = 1 AND a.owner_device_id IS NOT d.device_id);
UPDATE device_calendar_feeds SET kid_writes = 0
WHERE kid_writes = 1 AND NOT EXISTS (
    SELECT 1 FROM calendar_sources s JOIN caldav_accounts a ON a.id = s.account_id
    WHERE s.id = device_calendar_feeds.source_id AND a.owner_device_id = device_calendar_feeds.device_id);

-- The rules, held by the database as well as the code (the code refuses first, with words): a phone ticks a CalDAV
-- calendar only of its own account or a shared one; it marks one only of its own account; an account is made
-- unshared only with an owner and with no other phone's calendar left; and an account gets a new owner only while no
-- other phone writes to it. A deleted phone (`SET NULL` on the owner) writes a NULL owner and touches nothing else,
-- so none of these fires for it.
CREATE TRIGGER caldav_tick_own_or_shared BEFORE INSERT ON device_calendar_feeds
WHEN EXISTS (SELECT 1 FROM calendar_sources s JOIN caldav_accounts a ON a.id = s.account_id
             WHERE s.id = NEW.source_id
               AND ((a.shared = 0 AND a.owner_device_id IS NOT NEW.device_id)
                 OR (NEW.kid_writes = 1 AND a.owner_device_id IS NOT NEW.device_id)))
BEGIN
    SELECT RAISE(ABORT, 'design 136: a CalDAV calendar of another phone''s account');
END;

CREATE TRIGGER caldav_kid_writes_own BEFORE UPDATE OF kid_writes ON device_calendar_feeds
WHEN NEW.kid_writes = 1 AND NOT EXISTS (
    SELECT 1 FROM calendar_sources s JOIN caldav_accounts a ON a.id = s.account_id
    WHERE s.id = NEW.source_id AND a.owner_device_id = NEW.device_id)
BEGIN
    SELECT RAISE(ABORT, 'design 136: the kid''s events go only to the phone''s own CalDAV account');
END;

CREATE TRIGGER caldav_unshare_clean BEFORE UPDATE OF shared ON caldav_accounts
WHEN NEW.shared = 0 AND (NEW.owner_device_id IS NULL OR EXISTS (
    SELECT 1 FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    WHERE s.account_id = NEW.id AND d.device_id IS NOT NEW.owner_device_id))
BEGIN
    SELECT RAISE(ABORT, 'design 136: unshared with another phone''s calendar left, or no owner');
END;

CREATE TRIGGER caldav_owner_keeps_marks BEFORE UPDATE OF owner_device_id ON caldav_accounts
WHEN NEW.owner_device_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    WHERE s.account_id = NEW.id AND d.kid_writes = 1 AND d.device_id IS NOT NEW.owner_device_id)
BEGIN
    SELECT RAISE(ABORT, 'design 136: a new owner while another phone writes to the account');
END;
