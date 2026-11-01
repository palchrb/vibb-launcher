-- Round S4's fix round M1 (docs/notes/server/play-and-updates.md, "F-Droid source", "The repo's signature"): the
-- newest entry.json `timestamp` (ms, as the repo signed it) this server took for each F-Droid repo - f-droid.org and
-- IzzyOnDroid too. A later entry.jar whose signed entry.json is older is refused as a rollback (an old, genuinely
-- signed entry.jar replayed with its old index), and the last good index stays. `repo` is `tracked_apps.fdroid_repo`
-- as stored; written only while an F-Droid app has the repo, and a repo no catalog app uses any more loses its row
-- (`tracked_apps::remove_unused_fdroid_indexes`), as its key pin in `fdroid_repo_keys` (0111) does.
CREATE TABLE fdroid_repo_state (
    repo TEXT PRIMARY KEY NOT NULL,
    entry_timestamp INTEGER NOT NULL
);
