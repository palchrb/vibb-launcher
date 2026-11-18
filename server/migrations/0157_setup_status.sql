-- Design 114 (the phone setup status, docs/design/114-setup-status.md §4.2): the report's `setup` - the readings no
-- other field carries (device owner, Android's own mobile-data switch, Vibb's location permission, Data Saver, the
-- full-screen intent, the fingerprint), cleaned by setup_status::clean - and the setup hold's end the server keeps,
-- min(hold_until_ms, reported_at + hold_left_ms), which models::DeviceStatus::paused_at reads without parsing JSON.
-- NULL without the hold (each report is a new row).
ALTER TABLE device_status ADD COLUMN setup_json TEXT;
ALTER TABLE device_status ADD COLUMN setup_hold_until_ms INTEGER;

-- The parent steps ticked (§4.4, §5.1): in the PWA or from the phone's setup flow (`by_phone`). The phone never unticks.
-- device_policy.setup_card_hidden (0146, design 98's "Ikke nå") loses its reader and stays as a dead column.
CREATE TABLE device_setup_ticks (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    step TEXT NOT NULL,
    ticked_at TEXT NOT NULL,
    by_phone INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, step)
);
