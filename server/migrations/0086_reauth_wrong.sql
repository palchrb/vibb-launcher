-- Design 59 step 2's fix round (qa-59-step2-code S2P-1): the wrong passwords in a row at the forms that
-- ask a signed-in parent for the password again (Show Child PIN, the password changes, the 2FA reset),
-- counted per account in one UPDATE, so parallel requests and an older session's copy can't reset it.
ALTER TABLE admin_users ADD COLUMN reauth_wrong INTEGER NOT NULL DEFAULT 0;
