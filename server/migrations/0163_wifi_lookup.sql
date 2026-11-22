-- Design 125 step 2 (docs/design/125-wifi-places-on-the-phone.md §4a): the Apple Wi-Fi lookup's own tables (an
-- integration behind design 110's boundary, docs/notes/server/integrations.md - nothing added to the core's).
--
-- Each phone's switch, off until the parent turns it on (the Location page's "Wi-Fi lookup at Apple").
CREATE TABLE wifi_lookup_devices (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    is_on INTEGER NOT NULL DEFAULT 0
);
-- A5: Apple's answers, sealed. `id` = HMAC-SHA256(K, BSSID) with K in data/keys/wifi_lookup (never in a backup);
-- `sealed` = a nonce and AES-256-GCM of the position under a key derived from (K, BSSID), NULL for a BSSID Apple
-- didn't know (`negative`); the months as year * 12 + month - 1. No device, no time of a lookup, no query history.
CREATE TABLE wifi_lookup_aps (
    id BLOB PRIMARY KEY,
    sealed BLOB,
    fetched_month INTEGER NOT NULL,
    used_month INTEGER NOT NULL,
    negative INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;
CREATE INDEX wifi_lookup_aps_used ON wifi_lookup_aps (negative, used_month);
