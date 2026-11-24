-- Design 129 S1: the hardware-bound phone key, observed.
--
-- device_keys: a phone's attested Keystore key under a random key id (16 bytes, Base64url). The SPKI and the EC point
-- verify its signatures; the rest is what the attestation said (security level, KeyMint version, patch level, the
-- RootOfTrust, every certificate's serial, the root) and how it came (the registering address, `unusual` when the
-- phone's bearer never came from there, `via` enrol or register). S1 stores `observed` only; `bound`, `held`,
-- `pending` and `retired` are S2's and rotation's. `revoked_at`: the chain is on Google's status list now (the key
-- keeps working, §6.3). `last_signed_at`: a signature by it last verified (written at most every 10 minutes).
CREATE TABLE device_keys (
    key_id TEXT PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    spki BLOB NOT NULL,
    spki_sha256 TEXT NOT NULL,
    public_key BLOB NOT NULL,
    security_level TEXT NOT NULL CHECK (security_level IN ('tee', 'strongbox')),
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
CREATE INDEX device_keys_device ON device_keys (device_id);

-- One live registration challenge per phone (32 random bytes, 10 minutes, used once).
CREATE TABLE device_key_challenges (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    challenge BLOB NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

-- The addresses (`net::Client::key`: an IPv4 address, an IPv6 /56) each phone's bearer requests came from, kept 30
-- days: a registration from none of them is `unusual`.
CREATE TABLE device_addresses (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    address TEXT NOT NULL,
    first_seen TEXT NOT NULL,
    last_seen TEXT NOT NULL,
    PRIMARY KEY (device_id, address)
);

-- The signature clock's last value (ms), written every 10 s and at shutdown: the floor after a restart.
CREATE TABLE sig_clock (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sig_ms INTEGER NOT NULL
);

-- The enrolment code a phone used with a key, kept until the code's own expiry, so a lost answer can be asked for
-- again with the same key (§5.1); the last refused registration's reason (About This Phone); a key conflict (an
-- `exists` answer or the phone's report), cleared only by a new setup code.
ALTER TABLE devices ADD COLUMN enroll_used_code_hash TEXT;
ALTER TABLE devices ADD COLUMN enroll_used_expires_at TEXT;
ALTER TABLE devices ADD COLUMN enroll_used_spki_sha256 TEXT;
ALTER TABLE devices ADD COLUMN key_refused_reason TEXT;
ALTER TABLE devices ADD COLUMN key_refused_at TEXT;
ALTER TABLE devices ADD COLUMN key_conflict_at TEXT;
