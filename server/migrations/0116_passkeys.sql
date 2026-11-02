-- Design 74 step 1: passkeys for parents (a sign-in that is both factors, and the re-auth forms' other proof).
-- Frozen from its first push (sqlx's checksum); later notes go into docs/design/74-passkeys.md.
CREATE TABLE admin_passkeys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    credential_id BLOB NOT NULL UNIQUE,
    public_key BLOB NOT NULL,        -- the COSE key as registered
    alg INTEGER NOT NULL,            -- -7, -8 or -257
    rp_id TEXT NOT NULL,
    name TEXT NOT NULL,              -- security::display_name's rule, 1-32 characters
    sign_count INTEGER NOT NULL DEFAULT 0,
    backup_eligible INTEGER NOT NULL,
    backed_up INTEGER NOT NULL,
    transports TEXT,                 -- getTransports() as JSON, for allowCredentials
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_used_at TEXT,
    last_used_from TEXT
);
CREATE INDEX admin_passkeys_admin ON admin_passkeys(admin_id);
ALTER TABLE admin_users ADD COLUMN webauthn_user_id BLOB;
CREATE UNIQUE INDEX admin_users_webauthn_user_id ON admin_users(webauthn_user_id);
