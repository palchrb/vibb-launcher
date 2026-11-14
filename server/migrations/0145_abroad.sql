-- Design 97 (Mobilnett revision 4, docs/design/97-mobilnett-r4.md §7): "I utlandet" replaces "Dataroaming".
-- 'off' Ingen data (Android's roaming locked off), 'vibb' Bare Vibb (only Vibb uses mobile data abroad), 'home' Som
-- hjemme. D1 (the user, 2026-11-14): a new phone starts on 'vibb', the column's default. Every existing row is set
-- explicitly below (D2), so none keeps that default and no existing phone gains data abroad.
ALTER TABLE device_policy ADD COLUMN abroad TEXT NOT NULL DEFAULT 'vibb'
    CHECK (abroad IN ('off', 'vibb', 'home'));
-- D2: 'phone' becomes 'home' only where the phone's newest roaming report read Android's switch on, every other
-- existing row 'off' (what it really had abroad). `data_roaming` stays as a column and is no longer read or written.
UPDATE device_policy SET abroad = CASE WHEN data_roaming = 'phone' AND (
    SELECT json_extract(s.data_roaming_json, '$.enabled') FROM device_status s
    WHERE s.device_id = device_policy.device_id AND s.data_roaming_json IS NOT NULL ORDER BY s.id DESC LIMIT 1) = 1
    THEN 'home' ELSE 'off' END;
-- The roaming notice (§6, D6 as corrected): the last abroad place told (a country code, 'ship' or 'plain', NULL at
-- home) and the places told in the last hour with when (a JSON object), driven by mobile_network::roaming_edge.
ALTER TABLE devices ADD COLUMN roaming_place TEXT;
ALTER TABLE devices ADD COLUMN roaming_told TEXT;
-- Notify Me About's "Roaming" switch, on by default.
ALTER TABLE notification_prefs ADD COLUMN push_roaming INTEGER NOT NULL DEFAULT 1;
