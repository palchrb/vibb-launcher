-- The lock's background (design 25, "Lock background", 25f): by default handy's PIN lock shows the
-- wallpaper Home shows (no row here). A row picks a separate one for the lock - only a wallpaper
-- this phone may use (the composite key), so unticking or deleting it falls back to Home's by
-- itself. The tick save deletes only the unticked rows of device_wallpapers, never all of them.
CREATE TABLE device_lock_wallpaper (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    wallpaper_id INTEGER NOT NULL,
    FOREIGN KEY (device_id, wallpaper_id)
        REFERENCES device_wallpapers(device_id, wallpaper_id) ON DELETE CASCADE
);
