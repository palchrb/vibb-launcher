-- Curated wallpapers (design docs/design/08-ui-polish.md in the handy workspace, QA
-- qa-08-design.md). The parent picks which wallpapers each phone may use; the kid chooses among
-- them on the phone (that choice stays on the phone).

-- kind 'color' (one colour), 'gradient' (two colours at 160°) or 'image' (an uploaded photo,
-- data/wallpapers/<image_hash>.jpg, see src/photos.rs). colors is a JSON list of "#RRGGBB".
-- builtin_key names the built-ins so the launcher can label them in the kid's language; uploads
-- have none and carry the parent's label. lock_screen: an image also goes on the lock screen -
-- off by default, the lock screen then shows a colour (QA 08 #1). Colours always go on both.
CREATE TABLE wallpapers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK (kind IN ('color', 'gradient', 'image')),
    colors TEXT NOT NULL DEFAULT '[]',
    image_hash TEXT
        CHECK (image_hash IS NULL OR (length(image_hash) = 64 AND image_hash NOT GLOB '*[^0-9a-f]*')),
    label TEXT NOT NULL,
    sort INTEGER NOT NULL DEFAULT 0,
    builtin_key TEXT UNIQUE,
    lock_screen INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    CHECK ((kind = 'image') = (image_hash IS NOT NULL)),
    CHECK (builtin_key IS NULL OR kind <> 'image')
);

-- The mockup's set. Navy (id 1) is the launcher's own default too.
INSERT INTO wallpapers (id, kind, colors, label, sort, builtin_key) VALUES
    (1, 'color', '["#14213D"]', 'Navy', 10, 'navy'),
    (2, 'color', '["#1E4D3A"]', 'Forest', 20, 'forest'),
    (3, 'color', '["#4A2545"]', 'Plum', 30, 'plum'),
    (4, 'color', '["#2B8A3E"]', 'Green', 40, 'green'),
    (5, 'gradient', '["#1C7ED6","#14213D"]', 'Sky', 50, 'sky'),
    (6, 'gradient', '["#F76707","#862E9C"]', 'Sunset', 60, 'sunset');

-- Which wallpapers a phone may use. Deleting a wallpaper or a device removes its rows.
CREATE TABLE device_wallpapers (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    wallpaper_id INTEGER NOT NULL REFERENCES wallpapers(id) ON DELETE CASCADE,
    PRIMARY KEY (device_id, wallpaper_id)
);

-- Every phone gets the built-ins; uploads start on no phone (QA 08 #2).
INSERT INTO device_wallpapers (device_id, wallpaper_id)
    SELECT d.id, w.id FROM devices d, wallpapers w WHERE w.builtin_key IS NOT NULL;
CREATE TRIGGER devices_get_builtin_wallpapers AFTER INSERT ON devices
BEGIN
    INSERT INTO device_wallpapers (device_id, wallpaper_id)
        SELECT NEW.id, id FROM wallpapers WHERE builtin_key IS NOT NULL;
END;
