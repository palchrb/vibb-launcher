-- Design 83 §4: the MMS addresses a parent was asked about, per phone and SIM operator. A row is made when the phone
-- reports (`sms_state.mmsc_unlisted`) a contact's picture whose address is not on its SIM's list: `mccmnc` the SIM's
-- operator (5-6 digits), `host` the entry (`host` or `host:port`, `mmsc_hosts::valid_entry` - never the URL).
-- `state`: `open` until a parent answers - `allowed` (the entry joins that MCC/MNC's row in `call_policy.mmsc_hosts`
-- until "Fjern") or `declined` (never asked again). While a row is open `picture_from` (the contact's normalised
-- number) and `picture_at` (the notification's arrival, epoch ms) follow the newest report; `reported_at` (epoch ms)
-- is when a report last named it, and the purge's clock alone: an open row goes 30 days after it (retention).
-- `decided_by` is an `admin_users.id` (no foreign key, as `device_commands.requested_by`: the row outlives the
-- account). `id` is never used again (AUTOINCREMENT): it keys the row's event and its forms.
CREATE TABLE device_mmsc_hosts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    mccmnc TEXT NOT NULL,
    host TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'allowed', 'declined')),
    picture_from TEXT NOT NULL,
    picture_at INTEGER NOT NULL,
    reported_at INTEGER NOT NULL,
    decided_at TEXT,
    decided_by INTEGER,
    UNIQUE (device_id, mccmnc, host)
);
