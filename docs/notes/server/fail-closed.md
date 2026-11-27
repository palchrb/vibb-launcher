# Server notes: fail closed

Moved from server/CLAUDE.md on 2026-11-27 (the "Fail closed" section and the security-review bullets of "Non-obvious gotchas"); the core file's 'Where the rest is' lists every note.

## Fail closed

- **A status report is stored within limits (security review M7).** `device_api::cap_installed_apps`: real package
  names only (`is_package_name`), no duplicates, labels clipped to 100 chars, an installer that isn't a package name
  dropped, at most `MAX_INSTALLED_APPS` (1500); `lock_reason` 32 chars, `app_version` 64; every JSON blob goes
  through its own `sanitize_*` (`call_state`/`time_state` through `device_api::known_fields` since 0.55.0, security
  review L10: a key the launcher adds to its `TimeState`/`CallState` is dropped until
  `device_api::TIME_STATE_FIELDS`/`CALL_STATE_FIELDS` list it - one commit with the launcher's change, the API rule;
  `intake_limits::the_field_tables_keep_every_contract_key` checks every contract file's). An app's `logo` (design 87) is kept
  only as 64 lowercase hex (`lenient_text`: a number or object is no logo, never a refused report) and goes to
  `device_app_logos`, never into the stored snapshot (design 87b's `auto_tile` the same, kept only beside a valid `logo`); an uploaded logo is checked against its hash and this phone's
  rows, decoded under limits as exactly a 128 px PNG and written again by the server's encoder (`app_logos::reencode`),
  within a daily budget per phone and the store's and the phone's caps. `/security` pages back 200 events at a time (`?before=<id>`), so a flood can't
  hide a sign-in attempt. A phone's storage has a daily budget (security review L4, `retention::Budget`,
  `STATUS_ROWS_A_DAY`, `CRASH_HASHES`; `docs/notes/server/retention-and-crashes.md`), and its security-log lines and
  inbox events an hourly one (L1, `security::record_phone_event`, `parent_events::EVENTS_AN_HOUR`).
- **A catalog app is pinned to its package and signing key (security review M10, server part).** `src/apk.rs` reads
  an APK's manifest `package` (binary XML) and its signer (SHA-256 hex of the first certificate of the v3 signer
  Android picks for each SDK 34 to 37, or of the v2 block's first signer when there is no v3 block - one
  certificate for all four, else unreadable; a v3 block with no signer for one of them is unreadable unless a v3.1
  block is there, which keeps the original key as the pin; a second v2, v3 or v3.1 block is unreadable too,
  `apk::phone_signer` - what `apksigner --print-certs` and the
  launcher's `SelfUpdate.signers` give). `tracked_apps`
  `sync_one_app` and the manual upload refuse a release whose package differs from `package_name` or whose signer
  differs from `signer_sha256` (migration 0062), and pin what the first release has (`store_pins`); our own two rows
  (Vibb One's and Vibb music's, and any row whose pinned or incoming package is one of theirs, whatever the repo)
  take only the certificate behind `LAUNCHER_SIGNATURE_CHECKSUM`, never the first signer seen, and only their own
  packages (`key_rule`/`KeyRule::Ours`: `OwnKey`/`NoOwnKey`/`OtherApp` refusals, `servable` in `scoped_apps`, a cached
  release of another key forgotten at startup by `drop_foreign_own_releases` - nothing forgotten while the checksum
  is unset, then only new releases are refused); a first package
  that a catalog app can never be (`never_a_catalog_package`: `android`, `com.android.*`, Play core) or that a phone
  has as a system app or from another installer (`foreign_on_a_phone`) is refused (QA of the security wave,
  Medium 2); a manifest that can't be read is refused once a package is pinned, like the signer. `backfill_pins`
  (after the hash backfill at startup) pins releases cached before, with the same checks. A refused `(tag, asset id)`
  is remembered in `AppSyncs` (`SyncStatus::refused`): the hourly sync doesn't download it again, "Check now"
  (`check_now`, `sync_one_app(.., forced)`) and "Forget" do (QA Low 5). **A release never allows
  anything by itself**: `device_api::allow_installed_catalog_apps` (every status report) allows, on that phone only,
  a checked catalog app's pinned package once the phone reports it installed by the launcher, and nudges it; checking
  the app with a known package (`toggle_app`) allows it as before. "Forget Package and Key…" on the app's page (behind a confirm) clears both. `TrackedAppUpdate` carries
  `package_name` and `signer_sha256` (launcher DTO `signerSha256`, `PolicyResponseCompatTest`). The phone checks the
  APK itself before installing (launcher security wave, `CatalogApkCheck.kt`): the package and the pinned key in the
  APK's verified signers or their lineage (a v3.1 rotation the server doesn't read), the session held to the package
  (`setAppPackageName`); an app without both pins isn't downloaded at all. A refusal comes back in `app_downloads`
  (state `refused_package`/`refused_signer`/`refused_unreadable`/`refused_unpinned`, below). A phone's report
  never sets a catalog app's package (QA Low 4: the `installer` it names is its own claim): for an app whose APK
  didn't give one, the app's page lists what the launcher installed on its phones (`package_candidates`) and the
  parent confirms one (`confirm_package`). A new phone's first report
  allowlists only `device_api::SAFE_DEFAULT_APPS` it has (clock, calculator, camera, Vibb music; the user's choice,
  2026-10-11) plus its phone and SMS apps - `CALL_AND_TEXT_APPS` (Google's and AOSP's) and the `system_dialer` /
  `default_sms_package` its `call_state` names (`call_and_text_roles`), since while calls are unmanaged the launcher
  restricts outgoing calls unless the system dialer is allowed and hides an SMS app that isn't (QA Medium 3); the SMS
  apps are left out while SMS is managed (a new phone's default since 2026-11-23, `NEW_PHONE_CHANNEL_MODE`) - never
  everything installed. `calls::call_report` warns when the allowlist leaves either out. Fixtures: `testdata/apk/` (aapt2 + apksigner, throwaway keys, no code).
- **A catalog release that asks for more waits for the parent (design 90).** `apk::read_identity` reads the declared
  permissions all or nothing, with the split permissions Android adds for the app's target SDK (by resource id only, the whole document, else `None`); `catalog_holds::judge` holds a
  GitHub or F-Droid release (never the first, never Vibb One's or Vibb music's) whose list can't be read or that asks
  for a sensitive permission (`catalog_holds::SENSITIVE`) outside the set the parent accepted
  (`accepted_permissions`, NULL = none: a cached release whose file can't be read leaves it unknown, so the next
  sensitive release is held). Only the parent widens the set (Approve, an upload); a held release never reaches
  `latest_release_*`, so the phones keep what they have; Approve checks the file again (pins, package, SHA-256). Every
  new release first waits the app's hours from the later of its source's time and this server's first sight
  (`release_wait_hours`, 48; `tracked_app_seen_releases`). A string pool or resource map after the first XML node, a
  NUL in a permission's string, or an element's attributes out of Android's resource-id order makes the list unreadable (Android reads them otherwise). `docs/notes/server/play-and-updates.md`, "Update holds and the per-app wait".
- **An F-Droid repo's index is used only under its pinned key (round S4).** entry.json is read only out of the
  repo's `entry.jar` once `jar_signature::verify_entry_jar` has checked its JAR v1 signature (manifest digest, .SF
  over the manifest, the CMS SignedData by `ring`, SHA-256 only) and `fdroid::RepoKey` has matched the signer's
  certificate SHA-256: compiled in for `f-droid.org` and `apt.izzysoft.de` (`fdroid::KNOWN_REPO_KEYS`), any other
  repo pinned at its first good fetch (`fdroid_repo_keys`, 0111). A bad signature or another key fails closed
  (`SyncProblem::RepoSignature`/`RepoKey`: nothing downloaded or replaced); at the first pin the APK's signer must be
  one the index lists (`IndexSigner`). A signed entry.json older than the newest taken (`fdroid_repo_state`, 0112) is
  a rollback (`RepoRollback`), and the hourly sync never moves a release to a lower versionCode (`OlderVersion`;
  "Check Now" does). `docs/notes/server/play-and-updates.md`, "F-Droid source", "The repo's
  signature".
- **`GET /api/devices/policy` never answers with a default policy** (`handlers::device_api::build_policy`): a missing `device_policy` row, an unparseable `allowlist_json`, or any DB error is an empty 500 (logged). The launcher treats non-2xx as "no fresh policy" and keeps enforcing its cache. A `NULL` allowlist is still served as `null` (a new device before its first heartbeat); `"[]"` is served as `[]` and means "nothing allowed" on the launcher. Upstream returned `DevicePolicy::default()` (kiosk off, no allowlist) on these errors, which unlocked the phone.
- **The launcher reports what it did with the policy** (`StatusReportRequest.policy_state`, migration `0021`): `"ok"`, `"cache_corrupt"`, `"rejected_suspect"`, `"fresh_decode_failed"` or `"server_error"` (this server answered 5xx) - anything but `"ok"` (`NULL` aside) is an Attention alarm ("Phone can't apply its settings", About This Phone › Software says which). `restrictions_paused` reports the launcher's PIN-gated, time-limited "pause all restrictions" switch, an Attention alarm too ("Restrictions paused", to the summary's Quick actions); every page reads it through `DeviceStatus::paused_at` (2026-11-04): a report older than the longest pause (`RESTRICTIONS_PAUSE_MAX_MS`, 2 h, the launcher's `RESTRICTIONS_PAUSE_DURATION_MS` by `contracts/core/restrictions_pause.json`) isn't paused any more, and Quick actions says how old the report is once it is over 35 min (`report_age`) - a pause re-entered while the phone is offline shows only with its next report.
- **Allowlist writes never guess**: `devices::add_to_allowlist`/`remove_from_allowlist` read-modify-write in one transaction (`edit_allowlist`) and return an error on a DB error, a missing policy row or a stored allowlist that isn't valid JSON - `toggle_app` then answers 500 and writes nothing. The first-heartbeat bootstrap in `device_api::status` is one `UPDATE ... WHERE allowlist_json IS NULL`, so a read error can't make it overwrite a parent's list.
- **`create_device` inserts the device and its policy row in one transaction** and returns 500 if either fails, so a device without a policy row can't be created through the UI.

## Phone data, root scripts and backups

- **Nothing a phone sends reaches the PWA as HTML or script (security review H2).** A status report's `location` is
  stored only when `models::checked_location` accepts it (RFC 3339 `captured_at`, stored as UTC
  `YYYY-MM-DDTHH:MM:SS.mmmZ`; latitude +-90, longitude +-180, accuracy finite and >= 0; else dropped and logged, the
  rest of the report kept); migration `0054_location_cleanup.sql` removed rows the old intake let in. The Locate
  pages' script (`static/locate.js`, design 44) builds its popups with `textContent` and reads the newest fix's time
  from an Askama-escaped `data-` attribute; a value reaches a script that way, never as `{{ value }}` inside a
  `<script>`. A fix more than 5
  minutes past the server's time is dropped too, and `retention::prune` deletes stored ones first (QA of the security
  wave, Low 3: it stayed "the newest fix" for good).
- **Root takes only signed release files (security review M5).** `verify_signed FILE NAME TAG` (the same text in
  install.sh and update.sh, with the pinned `SERVER_SIGNING_PUBKEY`) needs `FILE.minisig` to verify with minisign and
  its trusted comment to be `NAME TAG`; `download_release` checks the tarball before unpacking it.
  `server-release.yml`'s `server-release-sign` job (the `release-server` environment, after the untagged image legs
  and the keyless index job, before the merge tags anything; no docker, no checkout, no repo script) signs both
  tarballs, `update.sh` (an old install's watcher takes `update.sh` from the release assets and checks it - the way
  in to the release that removes the watcher), `update-image.sh` and the image statement `handy-server-image.txt` (one
  line `ghcr.io/<owner>/handy-server@sha256:<index digest> server-vX.Y.Z`, trusted comment `handy-server-image.txt
  server-vX.Y.Z`) with that environment's `SERVER_MINISIGN_KEY` and checks them against the key pinned in
  `deploy/update-image.sh` - no secret or no pinned key, nothing is tagged or published. The merge job checks the
  statement with `update-image.sh --verify-statement` and the index's digest before tagging; `update-image.sh` checks
  it before `gh attestation verify` (DEPLOY.md, "Docker"). `"not-set"` (the
  state until the owner pins a key) refuses everything. `tests::deploy_scripts` keeps the three key lines and the two
  `verify_signed` equal, and install.sh's and update.sh's `case "$(uname -m)"` (`aarch64`/`arm64`,
  `x86_64`/`amd64`, anything else refused) equal and matching the release's two tarballs
  (`the_scripts_pick_the_tarball_for_the_machine`).
- **Root scripts never read `data/` as code (security review H1).** `data/` belongs to the service user. The only
  root scripts left are install.sh and update.sh, run by the owner: they read nothing in `data/` (the installed
  version is root's, `/var/lib/kid-phone-server/installed_version`; security QA L2), copy only plain database files out of it
  (`update.sh`'s backup, `cp -P` past a symlink check), and `chmod` in it only as the service user (`runuser`). The
  backup form still accepts only `HH:MM` times (`security::valid_clock_time`, 400 and nothing written otherwise).
  A restore is the server's own (`startup::apply_restore_request` at the start, from `data/restore_request`: the
  PWA's in the container, the owner's over SSH on a systemd install - DEPLOY.md, "Backups"): only the zip's
  `kidphone.db`, at most 1 GiB, passing the backup check before the swap (`backups::check_extracted` on a scratch copy,
  the bullet below; refused: the live database and its `-wal`/`-shm` untouched, its `backup_restore_refused` line with
  the code, `startup::record_restore_refused`), the replaced database kept in `data/backups/prerestore-<time>/`
  (3 kept), undone when the restored one won't open.
- **No admin session leaves the live database (security review M2).** Every copy goes through
  `backups::snapshot(db, scratch, target)`: `VACUUM INTO` a scratch file in `data/` (never `data/backups/`, where only
  finished copies are listed), `DELETE FROM tower_sessions` and a blanked
  `provisioning_settings.tailscale_auth_key` (`strip_secrets_in`) with `secure_delete` and the journal in memory,
  then a rename - backup zips and `pre-migrate-*.db`. A restore forgets every session at the next
  start (`apply_restore_request`, logged `backup_restored`); migration 0061 bumped every `session_epoch` once, for the
  ids in older backups. The process sets umask 077 first thing in `main` (rustix), the unit has `UMask=0077`
  (update.sh adds it as a drop-in), install.sh/update.sh `chmod` `data/` 700 and its files 600 as the service user
  (`runuser`, so a symlink reaches nothing of root's). The
  2FA secret stays unsealed (design 22 §3.2.8). A new copy of the database anywhere goes through `snapshot`.
- **Every backup is checked (design 116 §4).** After each backup (a task of its own) and at startup for the newest
  without a row, `backups::check_file` opens the zip's `kidphone.db` as a restore would (1 GiB cap, a scratch
  `data/backup-check-*.db` deleted after and swept at startup, read-only and immutable): `integrity_check`, every
  `_sqlx_migrations` row known to this binary with its checksum and none newer, `devices` read, no trigger or view no
  migration makes (read-only, `backup_schema::migration_trigger_and_view_names`), and then the schema: a restore takes
  only a database whose schema is exactly what the migrations make (`backups::schema_matches`: the scratch copy
  migrated as the start would, with the session store's table, compared by structure with a fresh in-memory
  database's, made once per process - `src/backup_schema.rs`: per table, case-folded, its columns, indexes (automatic
  ones by their columns), foreign keys and `CHECK` clauses, and every trigger and view by name and normalised SQL;
  exact normalised text only for a partial index's `WHERE` and a `CHECK`. Code `schema` - "Not a Vibb backup" - only
  when the comparison ran and differed; a copy that can't be opened read-write or migrated is `migrate` - "Can't be
  updated to this server's version". An upload that fails as `schema` is refused and deleted at once, logged
  `backup_upload_refused`; every restore the check refuses at the start logs `backup_restore_refused` with its code and
  fails the file's row, which the Backups page shows at that backup). `kid_phone_server check-backup <file>` runs the
  check on a copy under the system's temporary folder and prints `ok` or `refused: <code>` (DEPLOY.md, "Backups").
  Recorded as
  `integration_health('backup', 'file:<name>')` (a failed backup: `('backup', '')`); the Backups page's quiet
  "checked", its orange line and the Devices list's family row read `backups::backup_state`. In Docker `data/backups`
  is `./backups` on the host (`deploy/compose.yaml`): `snapshot` copies across filesystems on `EXDEV` (a `.partial`,
  swept at startup) and the start checks the folder is writable.
