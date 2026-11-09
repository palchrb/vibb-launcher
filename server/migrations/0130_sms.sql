-- Design 77 step 1 (docs/design/77-sms-mms-client.md §7): SMS in Vibb One.
--
-- D2: calls and SMS each get their own state, 'off' | 'managed' | 'unmanaged'. Every existing phone keeps exactly
-- what it does today (D2's migration table): calls_managed = 0 leaves both channels alone; managed, calls_enabled
-- picks off/managed for calls and sms_enabled off/unmanaged for SMS. No phone is migrated to SMS 'managed' (that
-- needs the engine and the parent's choice). A phone created from now on starts as before 0130, both channels
-- 'unmanaged' (code QA M2): the setup QR installs the newest stable launcher, which may not have the engine yet, and
-- the parent chooses "Styrt av Vibb" once the phone reports `sms_engine_v1` (the Calls page's gate).
-- calls_managed/calls_enabled/sms_enabled stay, unused but for a rollback (the server writes their derived values,
-- the fail-closed old-launcher view), and go in a later migration.
ALTER TABLE device_policy ADD COLUMN calls_mode TEXT NOT NULL DEFAULT 'unmanaged'
    CHECK (calls_mode IN ('off', 'managed', 'unmanaged'));
ALTER TABLE device_policy ADD COLUMN sms_mode TEXT NOT NULL DEFAULT 'unmanaged'
    CHECK (sms_mode IN ('off', 'managed', 'unmanaged'));
UPDATE device_policy SET
    calls_mode = CASE
        WHEN calls_managed = 0 THEN 'unmanaged'
        WHEN calls_enabled = 0 THEN 'off'
        ELSE 'managed' END,
    sms_mode = CASE
        WHEN calls_managed = 0 THEN 'unmanaged'
        WHEN sms_enabled = 0 THEN 'off'
        ELSE 'unmanaged' END;
-- §6.8: the message text on the lock glance's row, off by default.
ALTER TABLE device_policy ADD COLUMN sms_lock_text INTEGER NOT NULL DEFAULT 0;

-- D3: each contact's text switches, independent of the call switches. A contact's texting starts where its calling
-- is; a new contact gets all three on.
ALTER TABLE device_contacts ADD COLUMN sms_in INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_contacts ADD COLUMN sms_out INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_contacts ADD COLUMN mms INTEGER NOT NULL DEFAULT 1;
UPDATE device_contacts SET sms_in = allow_inbound, sms_out = allow_outbound, mms = 1;

-- D12: the phone's own numbers, one per SIM slot (1 or 2), normalised.
CREATE TABLE device_own_numbers (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    slot INTEGER NOT NULL CHECK (slot IN (1, 2)),
    number TEXT NOT NULL CHECK (length(number) BETWEEN 3 AND 16),
    PRIMARY KEY (device_id, slot)
);

-- D17: the sender names let through. `fold` is NFC + lowercase (`sms::fold`), unique per phone; at most 50 a phone
-- (checked by the writer).
CREATE TABLE device_sms_names (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 11),
    fold TEXT NOT NULL,
    PRIMARY KEY (device_id, fold)
);

-- D16 / §7: the held and blocked senders the phone posted (`POST /api/devices/sms-held`): who and when, never what.
-- `held_id` is the phone's; `at`/`expires_at` the phone's epoch milliseconds; `received_at` the server's first sight
-- (UTC text), `dropped_at` the parent's "Slett nå" (sent back as `sms_held_drop` for 24 hours). Purged 30 days after
-- `at` (retention).
CREATE TABLE device_sms_held (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    held_id TEXT NOT NULL,
    at INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('sms', 'mms')),
    sender_class TEXT NOT NULL,
    sender TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('held', 'released', 'expired', 'dropped', 'blocked')),
    received_at TEXT NOT NULL DEFAULT (datetime('now')),
    dropped_at TEXT,
    PRIMARY KEY (device_id, held_id)
);
CREATE INDEX idx_device_sms_held_at ON device_sms_held(device_id, at);

-- §7: a post that tried to move a row's state backwards or sideways, ignored and counted.
ALTER TABLE devices ADD COLUMN sms_ignored_transitions INTEGER NOT NULL DEFAULT 0;

-- §7: the launcher's `sms_state` (known keys only, `device_api::SMS_STATE_FIELDS`); NULL from older launchers.
ALTER TABLE device_status ADD COLUMN sms_state_json TEXT;

-- §7: the parent's push switch for a held message (step 4 raises the event).
ALTER TABLE notification_prefs ADD COLUMN push_sms_held INTEGER NOT NULL DEFAULT 1;
