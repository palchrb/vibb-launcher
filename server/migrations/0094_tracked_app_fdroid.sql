-- An F-Droid repo as a release source (PLAN "An F-Droid repo as a release source; Etar first",
-- 2026-10-24): a catalog app of source_type 'fdroid' (source_type has no CHECK, migration 0008)
-- takes its releases from an F-Droid repo - its address (https, stored without a trailing slash,
-- e.g. https://f-droid.org/repo) and the app's package id there (e.g. ws.xsoh.etar). Both NULL on
-- GitHub and manual rows. The release itself goes into the latest_release_* columns as a GitHub
-- release does (tag v<versionName>, asset id = the versionCode).
ALTER TABLE tracked_apps ADD COLUMN fdroid_repo TEXT;
ALTER TABLE tracked_apps ADD COLUMN fdroid_package TEXT;
