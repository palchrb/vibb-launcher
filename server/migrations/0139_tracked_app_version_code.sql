-- The stored release's own versionCode, read from its APK's manifest (`apk::ApkIdentity.version_code`,
-- `PackageInfo.getLongVersionCode`'s value): how a phone's report is known to run the release, for
-- `app_updated` (2026-11-12: Organic Maps' tag "2026.11.11-39-android" is never its versionName
-- "2026.11.11-39-Web"). NULL until a release is stored or the cached one read at startup
-- (`tracked_apps::backfill_version_codes`), and when the APK's code can't be read.
ALTER TABLE tracked_apps ADD COLUMN latest_release_version_code INTEGER;
