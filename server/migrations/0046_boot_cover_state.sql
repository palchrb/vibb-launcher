-- The boot cover's state as the phone reports it (design 16b, qa-16b-code #5): whether it takes
-- the switch, a tripped crash guard, and when it was last armed, shown and handed over. Stored
-- re-serialized through the known fields (kiosk_escapes::sanitize_boot_cover).
ALTER TABLE device_status ADD COLUMN boot_cover_json TEXT;
