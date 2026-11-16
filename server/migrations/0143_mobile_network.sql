-- The round "Mobilnett" (docs/design/94-data-roaming.md, docs/design/95-connectivity-control.md revision 3).
-- Data roaming: 'off' (off and locked, the default for every phone, existing ones included) or 'phone' (the phone's
-- choice). 'eea' is allowed now so a later round ("Tillat i EU/EØS", 95 §6 item 1) needs no table rebuild; nothing
-- offers it yet.
ALTER TABLE device_policy ADD COLUMN data_roaming TEXT NOT NULL DEFAULT 'off'
    CHECK (data_roaming IN ('off', 'eea', 'phone'));
-- "Mobildata for apper": 'on' or 'vibb' (Bare Vibb: only Vibb uses cellular, the VPN's sink).
ALTER TABLE device_policy ADD COLUMN apps_mobile TEXT NOT NULL DEFAULT 'on'
    CHECK (apps_mobile IN ('on', 'vibb'));
-- "Lås mobilnett-innstillingene": DISALLOW_CONFIG_MOBILE_NETWORKS on the phone, on by default.
ALTER TABLE device_policy ADD COLUMN mobile_settings_locked INTEGER NOT NULL DEFAULT 1;
-- The report's `data_roaming` and `mobile_data`, cleaned field by field (mobile_network::clean_*); NULL from an older
-- launcher.
ALTER TABLE device_status ADD COLUMN data_roaming_json TEXT;
ALTER TABLE device_status ADD COLUMN mobile_data_json TEXT;
