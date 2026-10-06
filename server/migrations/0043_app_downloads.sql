-- Catalog downloads on the phone: Wi-Fi only, resumable (design docs/design/13-app-downloads.md at
-- the monorepo root, with its QA review and decisions).
--
-- The per-device switch "App updates only on Wi-Fi", on by default (also for existing phones): sent
-- as `PolicyResponse.app_updates_wifi_only`. The launcher then downloads catalog apps only on an
-- unmetered network; its own update may use any non-roaming network after 3 days. Roaming always
-- waits, switch on or off.
ALTER TABLE device_policy ADD COLUMN app_updates_wifi_only INTEGER NOT NULL DEFAULT 1;
-- SHA-256 (lower-case hex) of the cached release's file, computed while it is downloaded or
-- uploaded and sent in `GET /api/devices/apps` (QA #2): the phone checks the whole file against it
-- before installing. NULL on a row cached before this - filled in at startup
-- (`tracked_apps::backfill_release_hashes`).
ALTER TABLE tracked_apps ADD COLUMN latest_release_sha256 TEXT;
-- What the phone reports about its downloads (`app_downloads`: state, bytes, total, since when,
-- the network), stored re-serialized and capped (src/app_downloads.rs).
ALTER TABLE device_status ADD COLUMN app_downloads_json TEXT;
