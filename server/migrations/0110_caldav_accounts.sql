-- Design 72 step 2 ("Calendars without sync apps", docs/design/72-calendar-feeds.md §6 at the monorepo root): CalDAV,
-- read-only. The parent signs in to a CalDAV account (iCloud, Fastmail, Nextcloud, Radicale - basic auth with an app
-- password) on Device › Calendar; each calendar ticked on a phone's page is a `calendar_sources` row of kind `caldav`,
-- fetched by the server and served to the phone exactly as a calendar link (`calendar.feeds`).

-- One account, the family's: shown on every phone's Calendar page, each phone ticking its own calendars. One row per
-- server address and username (a Test with the same pair again replaces the password). `server_url` is the address
-- as typed and normalized (no secret: `https://caldav.icloud.com`); the app password is sealed with the music key
-- (associated data `caldav-v1:<fingerprint>:<id>`) and never leaves the server - not to the phone, a log, a page or
-- the settings log; an empty `password_sealed` = a failed first Test, nothing kept. `hosts` (a JSON array) are the
-- only hosts the password may go to, as the last passing Test found them: another host later is `host_changed`
-- before any request. `calendars_json` is what that Test listed (`[{id, href, name}]`, `id` = 16 hex of the href,
-- what the tick form posts). `state`: `ok`; `login_refused` (a 401/403 - no request until Test passes or a new
-- password is saved, design QA M5: hourly retries can lock an Apple ID); `host_changed`; `untested` (a first Test
-- that didn't pass, hidden from the page). `test_error` is the last Test's code, worded at render. `lan` as the
-- music sweep judges an address (a public account stays public). `unused_since`: when its last calendar left the last
-- phone (NULL while one has it, or before any was ticked); `caldav::sweep` deletes an account no phone has a calendar
-- of a day after both that and its last Test - one switch flipped by mistake never loses the login.
-- Step 3 (the kid's own events written to one CalDAV calendar, design 72 §7, the user's shape of 2026-11-01) would
-- add its flag on the phone's row of that calendar (`device_calendar_feeds`), nothing here.
CREATE TABLE caldav_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_url TEXT NOT NULL,
    username TEXT NOT NULL,
    password_sealed BLOB NOT NULL,
    password_nonce BLOB NOT NULL,
    password_key_fp TEXT NOT NULL,
    hosts TEXT NOT NULL DEFAULT '[]',
    principal TEXT,
    home_set TEXT,
    calendars_json TEXT NOT NULL DEFAULT '[]',
    lan INTEGER NOT NULL DEFAULT 0,
    lan_http INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL DEFAULT 'untested'
        CHECK (state IN ('untested', 'ok', 'login_refused', 'host_changed')),
    test_error TEXT,
    test_status INTEGER,
    tested_at TEXT,
    ok_at TEXT,
    unused_since TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (server_url, username)
);

-- A CalDAV calendar's source names its account; the account's removal takes its calendars off every phone. For a
-- `caldav` source `url_sealed` is the calendar collection's address (sealed as a link is, `calendar-v1:`) and `etag`
-- its validator (`<getctag>|<sync-token>`), `last_modified` the last REPORT's time: unchanged and reported within a day,
-- nothing more is asked.
ALTER TABLE calendar_sources ADD COLUMN account_id INTEGER REFERENCES caldav_accounts(id) ON DELETE CASCADE;
CREATE INDEX idx_calendar_sources_account ON calendar_sources(account_id);

-- An account's last calendar gone: stamped, and `caldav::sweep` deletes the account (its password with it) a day later
-- unless a calendar is ticked again (design 72 §6: "the same way" as a link no phone names).
CREATE TRIGGER caldav_accounts_unused AFTER DELETE ON calendar_sources
WHEN OLD.account_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM calendar_sources WHERE account_id = OLD.account_id)
BEGIN
    UPDATE caldav_accounts SET unused_since = datetime('now') WHERE id = OLD.account_id;
END;

CREATE TRIGGER caldav_accounts_used AFTER INSERT ON calendar_sources
WHEN NEW.account_id IS NOT NULL
BEGIN
    UPDATE caldav_accounts SET unused_since = NULL WHERE id = NEW.account_id;
END;
