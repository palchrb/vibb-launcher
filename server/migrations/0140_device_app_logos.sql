-- Design 87: the apps' real logos. Which logo each phone reported for each of its apps: the SHA-256 of the
-- 128 px PNG the launcher drew (`installed_apps[].logo`), the file `data/app_logos/<hash>.png` shared by every
-- phone (`app_logos.rs`). A row goes when its package leaves the phone's report; a file no row names is deleted.
-- The files are never backed up: after a restore the rows name files that aren't there, and the phones send them
-- again when the next report's answer asks for them.
CREATE TABLE device_app_logos (
    device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT    NOT NULL,
    logo_hash    TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL,
    PRIMARY KEY (device_id, package_name)
);
CREATE INDEX device_app_logos_hash ON device_app_logos(logo_hash);
