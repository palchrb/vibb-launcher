-- Language (fix round 2026-10-06, user decision): the setup QR sets the phone's locale and time
-- zone (android.app.extra.PROVISIONING_LOCALE / PROVISIONING_TIME_ZONE - QR provisioning only;
-- adb provisioning doesn't set them), and a hardening switch keeps the system language from
-- being changed while managed (DISALLOW_CONFIG_LOCALE, default on, only the server lifts it).
ALTER TABLE device_policy ADD COLUMN disallow_config_locale INTEGER NOT NULL DEFAULT 1;
ALTER TABLE provisioning_settings ADD COLUMN locale TEXT NOT NULL DEFAULT 'nb_NO';
ALTER TABLE provisioning_settings ADD COLUMN time_zone TEXT NOT NULL DEFAULT 'Europe/Oslo';
