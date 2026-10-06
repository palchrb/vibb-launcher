-- Which file a tracked app's cached release is (handlers/tracked_apps.rs): the release asset's name
-- and its size in bytes, shown on the app's status card. Set when a GitHub release syncs (or an APK
-- is uploaded); NULL on rows synced before this - the card shows nothing until the next sync.
ALTER TABLE tracked_apps ADD COLUMN latest_release_asset_name TEXT;
ALTER TABLE tracked_apps ADD COLUMN latest_release_asset_size INTEGER;
