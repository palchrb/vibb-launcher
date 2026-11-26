-- Design 138 (docs/design/138-confirmed-zone-networks.md): the Wi-Fi routers a parent confirmed for
-- a zone, one row per router address (BSSID). `ssid` the name the phone reported, `mld` the
-- router's multi-link address when the phone had one; `blocked_at` set when the phone reported the
-- router seen far outside the zone (a blocked row is never served). The rows go with the zone.
CREATE TABLE zone_networks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    zone_id INTEGER NOT NULL REFERENCES device_zones(id) ON DELETE CASCADE,
    ssid TEXT NOT NULL,
    bssid TEXT NOT NULL,
    mld TEXT,
    confirmed_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    confirmed_at TEXT NOT NULL DEFAULT (datetime('now')),
    blocked_at TEXT
);
CREATE UNIQUE INDEX idx_zone_networks_ap ON zone_networks(zone_id, bssid);
