-- One clock format on the whole phone (design 34 P12). The parent's choice on the Home Screen page:
-- 'system' (Follow the phone, the default: the launcher writes nothing), '24' or '12' - the launcher
-- writes Android's Settings.System.TIME_12_24, so the status bar and every Vibb clock change together.
-- Sent as launcher_ui.clock_format.
ALTER TABLE device_policy ADD COLUMN clock_format TEXT NOT NULL DEFAULT 'system'
    CHECK (clock_format IN ('system', '24', '12'));
-- The launcher's answer: 1 = the phone's format is the chosen one, 0 = it should be but can't be
-- (no "Modify system settings" access, or the write was refused), NULL = "Follow the phone", or an
-- older launcher. 0 shows a warning on the Home Screen page.
ALTER TABLE device_status ADD COLUMN clock_format_applied INTEGER;
