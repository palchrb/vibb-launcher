-- Design 87 follow-up (the user, 2026-11-17): the catalog keeps an app's logo after the last phone that had it drops
-- it. The catalog row's last known logo (a `device_app_logos` hash, `app_logos.rs`): set when a phone reports a new
-- or changed logo for the package (or any logo while it is NULL), cleared with the package name, gone with the row.
-- A file is pruned only when no phone row and no catalog row names it. Like the phones' rows, a restore can bring it
-- back naming a missing file: the catalog shows the letter until a phone that has the app sends it again.
ALTER TABLE tracked_apps ADD COLUMN logo_hash TEXT;
-- Today's logos: per package the hash most phones name, a tie to the most recently updated (`catalog_logos`).
UPDATE tracked_apps SET logo_hash = (
    SELECT d.logo_hash FROM device_app_logos d WHERE d.package_name = tracked_apps.package_name
    GROUP BY d.logo_hash ORDER BY COUNT(*) DESC, MAX(d.updated_at) DESC, d.logo_hash LIMIT 1
) WHERE package_name != '';
