-- Design 135 (docs/design/135-family-zones.md §3): zones are the family's; a phone links to the ones
-- it uses. The link is the old device_zones row - its id, state and cursor unchanged - so the
-- policy's zone ids, the phone's fences and Wi-Fi zone links and the events' params all stay
-- valid. The weather moves to the family zone. Alerts become each parent's, per link; every
-- crossing is an event. Needs 0181 first (never deploy 0182 without it).
CREATE TABLE family_zones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,                 -- 1..40, unique in the family ignoring case: code's, on a change
    latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m INTEGER NOT NULL CHECK (radius_m > 0),
    created_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- The fold: one family zone per group of identical typed-in zones - the same name (ASCII case and
-- surrounding spaces aside), the same centre (stored at 6 decimals) and the same radius - made from
-- the group's oldest row. Same-name zones elsewhere stay two zones with their names.
INSERT INTO family_zones (name, latitude, longitude, radius_m, created_by, created_at, updated_at)
SELECT trim(d.name), d.latitude, d.longitude, d.radius_m, d.created_by, d.created_at, d.updated_at
FROM device_zones d
WHERE d.id = (SELECT MIN(e.id) FROM device_zones e
              WHERE lower(trim(e.name)) = lower(trim(d.name))
                AND e.latitude = d.latitude AND e.longitude = d.longitude AND e.radius_m = d.radius_m)
ORDER BY d.id;

ALTER TABLE device_zones ADD COLUMN zone_id INTEGER REFERENCES family_zones(id) ON DELETE CASCADE;
UPDATE device_zones SET zone_id = (
    SELECT f.id FROM family_zones f
    WHERE lower(f.name) = lower(trim(device_zones.name))
      AND f.latitude = device_zones.latitude AND f.longitude = device_zones.longitude
      AND f.radius_m = device_zones.radius_m);
CREATE UNIQUE INDEX idx_device_zones_link ON device_zones(device_id, zone_id);
CREATE INDEX idx_device_zones_zone ON device_zones(zone_id);

-- Design 138's confirmed routers (0180) become the family zone's: every link of a folded zone gets
-- the routers its sibling links hold, so each phone using the zone is served the same ones. One row
-- per router address and link (a link's own row stays as it is); a copied row takes the name, the
-- multi-link address and the confirmer of the oldest unblocked confirmation of that address (the
-- oldest blocked one when every copy is blocked), and is blocked when any copy is. At most 16 rows a
-- link: its own rows first, then unblocked before blocked, the oldest confirmation first.
WITH copies AS (
    SELECT d.zone_id AS family, n.id, n.ssid, n.bssid, n.mld, n.confirmed_by, n.confirmed_at,
           MAX(n.blocked_at) OVER (PARTITION BY d.zone_id, n.bssid) AS blocked_any,
           ROW_NUMBER() OVER (PARTITION BY d.zone_id, n.bssid
                              ORDER BY n.blocked_at IS NOT NULL, n.confirmed_at, n.id) AS nth
    FROM zone_networks n JOIN device_zones d ON d.id = n.zone_id
), routers AS (
    SELECT * FROM copies WHERE nth = 1
), wanted AS (
    SELECT l.id AS link, r.ssid, r.bssid, r.mld, r.confirmed_by, r.confirmed_at, r.blocked_any,
           ROW_NUMBER() OVER (PARTITION BY l.id
                              ORDER BY r.blocked_any IS NOT NULL, r.confirmed_at, r.id) AS k
    FROM device_zones l JOIN routers r ON r.family = l.zone_id
    WHERE NOT EXISTS (SELECT 1 FROM zone_networks e WHERE e.zone_id = l.id AND e.bssid = r.bssid)
)
INSERT OR IGNORE INTO zone_networks (zone_id, ssid, bssid, mld, confirmed_by, confirmed_at, blocked_at)
SELECT w.link, w.ssid, w.bssid, w.mld, w.confirmed_by, w.confirmed_at, w.blocked_any FROM wanted w
WHERE w.k + (SELECT COUNT(*) FROM zone_networks o WHERE o.zone_id = w.link) <= 16;
-- A router blocked on one link of a family zone is blocked on all of them.
UPDATE zone_networks SET blocked_at = (
    SELECT MAX(o.blocked_at) FROM zone_networks o JOIN device_zones od ON od.id = o.zone_id
    WHERE o.bssid = zone_networks.bssid
      AND od.zone_id = (SELECT d.zone_id FROM device_zones d WHERE d.id = zone_networks.zone_id))
WHERE blocked_at IS NULL AND EXISTS (
    SELECT 1 FROM zone_networks o JOIN device_zones od ON od.id = o.zone_id
    WHERE o.bssid = zone_networks.bssid AND o.blocked_at IS NOT NULL
      AND od.zone_id = (SELECT d.zone_id FROM device_zones d WHERE d.id = zone_networks.zone_id));

-- The weather's zone is a place: the family zone. weather_zone_id stays, NULL and unread (a column
-- with a reference isn't dropped).
ALTER TABLE device_policy ADD COLUMN weather_family_zone_id INTEGER
    REFERENCES family_zones(id) ON DELETE SET NULL;
UPDATE device_policy SET weather_family_zone_id =
    (SELECT zone_id FROM device_zones WHERE id = device_policy.weather_zone_id)
WHERE weather_zone_id IS NOT NULL;
UPDATE device_policy SET weather_zone_id = NULL;

-- Each parent's alerts per link (no row = both on). The old per-zone switches become every
-- parent's own - removed ones too, so a parent restored by reset-2fa finds them as they were.
CREATE TABLE zone_alerts (
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    device_zone_id INTEGER NOT NULL REFERENCES device_zones(id) ON DELETE CASCADE,
    arrive INTEGER NOT NULL DEFAULT 1,
    leave INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (admin_id, device_zone_id)
);
INSERT INTO zone_alerts (admin_id, device_zone_id, arrive, leave)
SELECT a.id, d.id, d.notify_arrive, d.notify_leave
FROM device_zones d CROSS JOIN admin_users a
WHERE d.notify_arrive = 0 OR d.notify_leave = 0;
CREATE INDEX idx_zone_alerts_link ON zone_alerts(device_zone_id);

ALTER TABLE device_zones DROP COLUMN name;
ALTER TABLE device_zones DROP COLUMN latitude;
ALTER TABLE device_zones DROP COLUMN longitude;
ALTER TABLE device_zones DROP COLUMN radius_m;
ALTER TABLE device_zones DROP COLUMN notify_arrive;
ALTER TABLE device_zones DROP COLUMN notify_leave;
