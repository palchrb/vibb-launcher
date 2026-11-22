-- The setup QR's Wi-Fi network kept on the server (the user, 2026-11-22: "skal vi tillate lagring av wifi passord på
-- serveren vår?"): one network for the family. `wifi_ssid` its name (NULL = none saved), `wifi_password_sealed` its
-- password sealed with the music key (`MusicKey::seal_text`, `v1:<fingerprint>:<nonce>:<ciphertext>` under
-- `wifi-v1:<fingerprint>:provisioning`; NULL = an open network). Opened only to build a QR, never shown or logged; a
-- backup holds it sealed, and no backup holds the key.
ALTER TABLE provisioning_settings ADD COLUMN wifi_ssid TEXT;
ALTER TABLE provisioning_settings ADD COLUMN wifi_password_sealed TEXT;
