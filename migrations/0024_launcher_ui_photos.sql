-- Launcher UI, contact photos, launcher language and the airplane-mode switch (design
-- docs/design/05-ui-photos-i18n.md in the handy workspace).

-- SHA-256 (lowercase hex) of the contact's stored photo, data/contact_photos/<hash>.jpg
-- (src/photos.rs). One photo per address-book contact, shared like the name.
ALTER TABLE contacts ADD COLUMN photo_hash TEXT
    CHECK (photo_hash IS NULL OR (length(photo_hash) = 64 AND photo_hash NOT GLOB '*[^0-9a-f]*'));

-- Sent as PolicyResponse.launcher_ui: the launcher's language ("system" follows the phone) and
-- how many columns the home-screen app grid has.
ALTER TABLE device_policy ADD COLUMN launcher_language TEXT NOT NULL DEFAULT 'system'
    CHECK (launcher_language IN ('system', 'nb', 'en'));
ALTER TABLE device_policy ADD COLUMN home_columns INTEGER NOT NULL DEFAULT 3
    CHECK (home_columns IN (3, 4));

-- Hardening switch: DISALLOW_AIRPLANE_MODE. Off by default - airplane mode stays allowed.
ALTER TABLE device_policy ADD COLUMN disallow_airplane_mode INTEGER NOT NULL DEFAULT 0;

-- Whether the launcher's notification listener (app badges) has access. NULL from older
-- launchers.
ALTER TABLE device_status ADD COLUMN notification_listener_enabled INTEGER;
