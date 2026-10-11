-- Security review M10: a catalog app's signing certificate, pinned like its package name.
-- signer_sha256: SHA-256 (lower-case hex) of the first signer's certificate in the APK Signing
-- Block (v3, else v2), taken from the app's first release the server caches (or, for a release
-- cached before this, by the startup backfill). A later release with another package or another
-- key is refused; "Forget" on the app's page clears both, and the next release sets them again.
-- Sent to the phones with the app list (`TrackedAppUpdate.signer_sha256`), for the launcher to
-- check the APK before it installs it.
ALTER TABLE tracked_apps ADD COLUMN signer_sha256 TEXT;
