-- User certificates (Advanced › Restrictions): the launcher sets DISALLOW_CONFIG_CREDENTIALS while the phone is
-- managed - no certificate installed or removed from Android's Settings, and Android's own trusted ones can't be
-- switched off. On by default; the parent turns it off for a Wi-Fi network or an app that needs a certificate of its
-- own. Like the other hardening switches, a pause or the Parent PIN doesn't lift it.
ALTER TABLE device_policy ADD COLUMN disallow_config_credentials INTEGER NOT NULL DEFAULT 1;
