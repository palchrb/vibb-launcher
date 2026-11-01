-- Round S4, F-Droid trust (docs/notes/server/play-and-updates.md, "F-Droid source", "The repo's signature"): the
-- signing key of each F-Droid repo other than f-droid.org and IzzyOnDroid (whose keys are compiled in,
-- `fdroid::KNOWN_REPO_KEYS`), pinned at this server's first good fetch of its `entry.jar` (trust on first use).
-- `repo` is `tracked_apps.fdroid_repo` as stored; `signer_sha256` the SHA-256 (lower-case hex) of the certificate
-- that signed entry.jar - F-Droid's repo fingerprint. A later entry.jar signed by another key is refused. A repo no
-- catalog app uses any more loses its row (`tracked_apps::remove_unused_fdroid_indexes`).
CREATE TABLE fdroid_repo_keys (
    repo TEXT PRIMARY KEY NOT NULL,
    signer_sha256 TEXT NOT NULL,
    pinned_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);
