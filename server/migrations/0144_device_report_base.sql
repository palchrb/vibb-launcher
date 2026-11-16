-- Design 95 §3.5 (step 95-T2), the heartbeat report: the last full status report a phone sent with a `hash`, without
-- the heartbeat fields (`status_heartbeat::HEARTBEAT_FIELDS` - no location is kept here), so a heartbeat can be
-- expanded into the full report it stands for. `hash` is the phone's own name for the body; `server_version` the
-- server that kept it (another version asks for a full report); `stored_at` against `devices.enrolled_at` and the
-- age limit. One row per phone, replaced by each full report, gone with the phone.
CREATE TABLE device_report_base (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    hash TEXT NOT NULL,
    body_json TEXT NOT NULL,
    server_version TEXT NOT NULL,
    stored_at TEXT NOT NULL
);
