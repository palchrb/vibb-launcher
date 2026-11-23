-- Design 87b: the tile each phone draws for an app's Automatic look ("Vibb style": the monochrome layer in white on the
-- app's tone, else the app's own logo), reported beside the logo as `installed_apps[].auto_tile` and stored as the
-- logos are: the SHA-256 of the 128 px PNG, the file `data/app_logos/<hash>.png` shared with the logos. NULL until a
-- phone reports one; for an app without a monochrome layer it equals `logo_hash`. A file no logo or tile names is
-- deleted (`app_logos.rs`).
ALTER TABLE device_app_logos ADD COLUMN tile_hash TEXT;
CREATE INDEX device_app_logos_tile ON device_app_logos(tile_hash);
