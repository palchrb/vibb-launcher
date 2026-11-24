-- 0175_enroll_key_attempts.sql (design 129b): a new phone's enrolment requires a proven hardware key. A refused key
-- (or no key) waits for a parent: one row per (phone, attempt) of the live setup code; the parent's per-phone exception.
CREATE TABLE enroll_key_attempts (
    id           INTEGER PRIMARY KEY,
    device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    code_hash    TEXT    NOT NULL,          -- the live code it came with (enrollment::code_hash)
    attempt_hash BLOB    NOT NULL,          -- SHA-256 of the phone's attempt secret; the number derives from it
    spki_sha256  BLOB,                      -- the refused key's, when a chain came (the log's only)
    reason       TEXT    NOT NULL,          -- one word of D2
    address      TEXT    NOT NULL,          -- net::Client::describe of the attempt's first try
    attempts     INTEGER NOT NULL DEFAULT 1,
    first_at     TEXT    NOT NULL,
    last_at      TEXT    NOT NULL,
    allowed_at   TEXT,
    allowed_by   INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    used_at      TEXT,                      -- D9: enrolled exempt with this attempt
    repeats      INTEGER NOT NULL DEFAULT 0,
    keep_until   TEXT,                      -- D9: the code's expiry + the grace, for a lost answer
    UNIQUE (device_id, attempt_hash)
);
CREATE INDEX idx_enroll_key_attempts_code ON enroll_key_attempts (code_hash);
ALTER TABLE devices ADD COLUMN enrollment_code_tag BLOB;                         -- D4, M5: made with each code
ALTER TABLE devices ADD COLUMN enroll_wrong_numbers INTEGER NOT NULL DEFAULT 0;  -- D7, per code
ALTER TABLE devices ADD COLUMN enroll_old_app_at TEXT;                           -- D13: a body without `attempt`
ALTER TABLE devices ADD COLUMN key_exempt_at TEXT;
ALTER TABLE devices ADD COLUMN key_exempt_reason TEXT;
ALTER TABLE devices ADD COLUMN key_exempt_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL;

-- Design 129b §7 (E2, `emulator_keys`): an emulator's key is attested at the Software level. 0174's CHECK allowed only
-- 'tee' and 'strongbox'; SQLite can't change a CHECK, so the table is rebuilt with 'software' allowed (nothing
-- references it).
CREATE TABLE device_keys_new (
    key_id TEXT PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    spki BLOB NOT NULL,
    spki_sha256 TEXT NOT NULL,
    public_key BLOB NOT NULL,
    security_level TEXT NOT NULL CHECK (security_level IN ('tee', 'strongbox', 'software')),
    attestation_version INTEGER NOT NULL,
    os_patch_level INTEGER,
    boot_state TEXT,
    boot_key TEXT,
    device_locked INTEGER,
    serials TEXT NOT NULL,
    root TEXT NOT NULL,
    address TEXT,
    unusual INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL CHECK (state IN ('observed', 'bound', 'held', 'pending', 'retired')),
    via TEXT NOT NULL CHECK (via IN ('enroll', 'register')),
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    revoked_at TEXT,
    last_signed_at TEXT
);
INSERT INTO device_keys_new SELECT key_id, device_id, spki, spki_sha256, public_key, security_level,
    attestation_version, os_patch_level, boot_state, boot_key, device_locked, serials, root, address, unusual, state, via,
    created_at, revoked_at, last_signed_at FROM device_keys;
DROP TABLE device_keys;
ALTER TABLE device_keys_new RENAME TO device_keys;
CREATE INDEX device_keys_device ON device_keys (device_id);
CREATE UNIQUE INDEX device_keys_one_live ON device_keys (device_id) WHERE state IN ('observed', 'bound', 'held');
