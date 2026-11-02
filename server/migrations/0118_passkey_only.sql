-- Design 74 step 2: "Kun passkey" (passkey only). While it is 1 the account has no password ('!passkey') and no
-- TOTP, signs in only with a passkey and always keeps at least one (D2.2, D2.3); another parent's Reset…, a soft
-- Remove and the shell's reset-2fa/reset-sign-in set it back to 0. (0117 is the CalDAV write-back's, in build at the
-- same time.) Frozen from its first push (sqlx's checksum); later notes go into docs/design/74-passkeys.md.
ALTER TABLE admin_users ADD COLUMN passkey_only INTEGER NOT NULL DEFAULT 0;
