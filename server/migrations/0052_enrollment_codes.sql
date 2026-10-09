-- Enrollment codes are stored hashed (design 22 §3.1): only the SHA-256 of the normalised code
-- (upper-cased, spaces and dashes stripped), and its kind - 'qr' (26 characters of base32, only in
-- a setup QR, 30 minutes) or 'typed' (8 characters, 15 minutes). The plaintext column stays (SQLite
-- can't drop a UNIQUE column) but is cleared and never written again, so codes that were still open
-- stop working: make a new one on the device page.
ALTER TABLE devices ADD COLUMN enrollment_code_hash TEXT;
ALTER TABLE devices ADD COLUMN enrollment_code_kind TEXT
    CHECK (enrollment_code_kind IS NULL OR enrollment_code_kind IN ('qr', 'typed'));
UPDATE devices SET enrollment_code = NULL, enrollment_code_expires_at = NULL;
CREATE UNIQUE INDEX idx_devices_enrollment_code_hash ON devices (enrollment_code_hash)
    WHERE enrollment_code_hash IS NOT NULL;
