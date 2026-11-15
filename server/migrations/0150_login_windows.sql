-- Design 102 (Wi-Fi sign-in pages and in-app logins without free Chrome, docs/design/102-captive-portals-and-logins.md
-- §6.4): Part A's switch (D1, on by default), the parent's "one more sign-in now" counter (D14), the parent code for
-- every sign-in and reopen (revision 5, on by default; Device › Advanced's #wifi-signin), and Part B's role (D10, off
-- by default) with its presets as a set. Design 100 holds 0149.
ALTER TABLE device_policy ADD COLUMN captive_portal_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN captive_portal_grant INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN captive_portal_require_code INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN login_browser_enabled INTEGER NOT NULL DEFAULT 0;
-- The ticked presets as a JSON array of names ('feide', 'google', 'microsoft', 'apple'); expanded at policy build
-- (logins::PRESETS), so a corrected preset reaches every phone with the next server release.
ALTER TABLE device_policy ADD COLUMN login_browser_presets TEXT NOT NULL DEFAULT '[]';
-- The parent's own sites only. Not `login_browser_sites` (§6.4): `cleanup::` checks that no table named like
-- "browser" is left from the removed browser history.
CREATE TABLE login_role_sites (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    entry TEXT NOT NULL,          -- logins::valid_entry's form
    note TEXT,                    -- the parent's own words, <= 60 chars
    added_ms INTEGER NOT NULL,
    PRIMARY KEY (device_id, entry)
);
-- The report's `captive_portal` and `login_browser`, cleaned (logins::clean_portal, logins::clean_browser).
ALTER TABLE device_status ADD COLUMN captive_portal_json TEXT;
ALTER TABLE device_status ADD COLUMN login_browser_json TEXT;
-- Notify Me About's "Wi-Fi sign-in" switch (wifi_signin_wide, wifi_signin_budget), on by default.
ALTER TABLE notification_prefs ADD COLUMN push_wifi_signin INTEGER NOT NULL DEFAULT 1;
