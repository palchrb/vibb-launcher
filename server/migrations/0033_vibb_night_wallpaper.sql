-- The Vibb night palette (fix round 2026-10-06): a new built-in colour wallpaper, #0C0C14, first
-- in the list, so it is the default on phones added from now on (the launcher shows the first
-- allowed wallpaper until the kid picks one). No fixed id - an upload may already have id 7.
-- Existing phones don't get it here: their list, and the wallpaper they show, stay as they are
-- (the parent can tick it on the device page); new phones get it from the
-- devices_get_builtin_wallpapers trigger like every built-in.
INSERT INTO wallpapers (kind, colors, label, sort, builtin_key)
    VALUES ('color', '["#0C0C14"]', 'Vibb night', 5, 'vibb_night');
