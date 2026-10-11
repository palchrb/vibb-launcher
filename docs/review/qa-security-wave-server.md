# QA: the server security wave (2026-10-11)

Scope: the server part of `docs/review/security-review-2026-10-10.md`, as on `main` at `c103b283` (server 0.24.0, not
tagged). The commits:

| Finding | Commits |
|---|---|
| H1 | `06cd48d7` |
| H2 | `7e9fe55e` |
| M4 | `f952f67b` |
| M2 | `cd5e8216` |
| M6 | `2481de32` |
| M10 (server part, launcher DTO) | `9325eadf` |
| M5 | `5cbf69c7`, `0475158d` |
| M7 (rest) | `e4f942f6` |
| L5 | `952b4ed1` |
| L9 (server) | `ff6692bd` |
| L10 (rest) | `41849523` |
| L12 (server) | `908757f0`, `470171f2` |
| version, docs | `c103b283`, `bc40c5bd` |

The merges and the QA 22 fix commits are out of scope. The work is judged against:
- the review;
- its "Status and decisions" and "Server security wave status";
- `CLAUDE.md`, `server/CLAUDE.md` and `server/DEPLOY.md`.

Paths are under `server/` unless they say otherwise. Line numbers are at `c103b283`.

## Build

- `cargo test`: 498 passed.
- `cargo fmt --check`: clean.
- `cargo clippy --all-targets`: 13 warnings. One is new from the wave: `for_kv_map` at
  `src/handlers/device_api.rs:686` (M6; `for blocklist_id in global_enabled.keys()`).
- Launcher: not run. The change is one DTO field and a compat test. I read both: `ServerJson` is `SnakeCase`, so
  `signerSha256` is `signer_sha256`, and the test's variables are in scope.

Shell scripts:
- `bash -n`: clean, for both scripts and the four extracted heredocs.
- `shellcheck`: one warning, SC2174 at `deploy/update.sh:637`. It dates from 2026-10-04 and is harmless.

Exercised by hand in a temp dir, with real tools: `unzip` 6.0 and `minisign` 0.11, unpacked from their Debian packages.
- `scheduler.sh`: fed injected `schedule.conf` values.
- `action_restore_backup`, with:
  - a symlinked zip;
  - a symlink entry inside the zip;
  - no `kidphone.db` in the zip;
  - a zip over the size cap;
  - a symlinked `data/kidphone.db`.
- `verify_signed`, with a throwaway key: not-set, good, wrong tag, another file's signature, tampered, missing, legacy
  format, a comment with a suffix.
- `backup_sync.sh`'s copy commands, with symlinks: Medium 1.
- `watcher.sh` with a FIFO: Low 1.

Probe tests: three temporary Rust tests through the real routers, now deleted. They confirmed Medium 2, Low 3 and
Low 4.

Not run:
- nothing on a Pi, a phone or GitHub Actions;
- the release workflow was read, not run.

Counts: 0 High, 3 Medium, 8 Low.

## Medium

1. **Medium: `backup_sync.sh` still writes through symlinks the service user plants in `data/`. With the USB backup
   on, that is root code execution: M4's hole by another door.**
   - Where:
     - `deploy/install.sh:772-780` and its copy at `deploy/update.sh:596-604`:
       ```
       cp -au "$DATA_DIR/backups/." "$EXTERNAL_MOUNT/snapshots/"
       cp -au "$DATA_DIR/live_mirror.db" "$EXTERNAL_MOUNT/live/kidphone.db"
       ```
     - M4's check (`:761`) covers only `data/`, `data/backups` and the config file. It doesn't cover entries inside
       `backups/` or `live_mirror.db`.
   - Scenario: code runs as `kidphone`, the M4 threat model. The external drive is mounted; the format action makes it
     ext4, so it can hold symlinks.
     1. The attacker writes `EXTERNAL_COPY_ENABLED=true` into `data/backup_schedule.conf`. That file is theirs.
     2. They create `data/backups/x`, a symlink to a root file that exists, such as
        `/opt/kid-phone-server-updater/actions.sh`.
     3. Within 90 s, root's `cp -a` copies the symlink as a symlink to `snapshots/x` on the drive.
     4. They replace `data/backups/x` with a regular file holding their script and set its mtime in the future.
     5. The next run's `cp -au` opens the existing destination `snapshots/x` and writes through it. Root's
        `actions.sh` now holds the attacker's text.
     6. The next flag file (`restart`) runs that text as root.

     `live_mirror.db` works the same way. `[ -f ]` passes for a symlink, and `-a` copies the link, not its target.
   - Confirmed in a temp dir with GNU cp 9.1: the second `cp -au` overwrote the existing target file, both for an
     entry in `backups/` and for `live_mirror.db`. A symlinked directory is refused by cp ("cannot overwrite
     non-directory"). With `--remove-destination`, the same sequence left the target untouched.
   - The wave's status says "the watcher and backup sync ignore symlinks in data/". That holds only for the three
     paths checked.
   - Fix:
     - Copy only regular files: `find "$DATA_DIR/backups" -maxdepth 1 -type f` for the snapshots, and
       `[ -f ] && [ ! -L ]` for the mirror.
     - Copy with `cp -a --remove-destination` (`-a` keeps `--no-dereference`), so a symlink already at the
       destination is replaced, never written through. That also covers a regular file swapped for a symlink between
       `find` and `cp`.
     - Delete any symlink already on the drive: `find "$EXTERNAL_MOUNT" -type l -delete`.
     - Add a `deploy_scripts` assertion that both `cp` lines carry `--remove-destination`.

2. **Medium: whatever package a catalog release's manifest names goes onto every selected phone's allowlist before
   anything is installed. A release can unlock Settings or a browser.**
   - Where:
     - `src/handlers/tracked_apps.rs:564-594`: `store_pins` calls `allow_on_selected_phones` (`:538-561`) as soon as
       a release with a readable manifest is cached for an app with no package.
     - Every new GitHub catalog app has no package: the add form takes none.
     - `allow_on_selected_phones` skips the checks `toggle_app` has, including the 409 for a package Play installed
       (`server/CLAUDE.md`, "Play").
   - Scenario: the attacker controls a catalog app's release source. It could be a third-party developer, a
     compromised repo, or the kid ("Dad, can you add my app?").
     1. The release's binary manifest says `package="com.android.settings"`, or a browser or file manager that is on
        the phone.
     2. The hourly sync caches it and pins that package.
     3. The server adds it to the allowlist of every phone that has the app checked, and nudges them. Settings or the
        browser is now usable in kiosk.
     4. The APK itself never needs to install. For a system package it can't install anyway: its signature doesn't
        match.

     This is the review's M10 attack ("få Innstillinger tillatt"), moved from a fake phone's report to the release.
     Before M10 the package came only from a package that newly appeared on the phone, so an APK that never installs
     unlocked nothing. Here M10 adds the hole.
   - Probe (confirmed):
     1. A phone reports Firefox installed from Play. The allowlist has only the clock.
     2. A catalog app is checked for it.
     3. A release whose manifest says `org.mozilla.firefox` is uploaded.
     4. The phone's policy then allows `org.mozilla.firefox`.

     The manual upload and the GitHub sync share `store_pins`.
   - Fix:
     - Pin the package from the APK, but don't allowlist on pin. Allowlist on the phone's evidence instead: a status
       report where the pinned package appears with `installer` = the launcher. The status path already has that
       shape (`device_api.rs:1052`); let it also fire when the catalog package is set but not yet on that phone's
       allowlist.
     - Or, at the least, allowlist only on phones that don't report the package installed. Apply `toggle_app`'s rules
       too: Play core, a package Play installed.
     - Refuse to pin a package a selected phone already has from another installer, and say so on the app's page.
     - Test: a release naming a package the phone already has isn't allowed there.

3. **Medium: a new phone starts with no outgoing calls and with Messages hidden. The safe default leaves out the
   dialer and the SMS app, and calls are unmanaged by default.**
   - Where:
     - `src/handlers/device_api.rs:584-595` (`SAFE_DEFAULT_APPS`) and the bootstrap at `:980-1006`.
     - On the launcher, `launcher/app/src/main/java/com/kidslauncher/mdm/server/EnforcementPlan.kt`:
       - `:163`: `CallPolicyState.Unmanaged -> appsManaged && systemDialer != null && systemDialer !in allowed`
         restricts outgoing calls;
       - `:171`: hides every controllable app that isn't allowed, Messages included.
     - `calls_managed` defaults to 0 (migration 0022).
   - Scenario: a parent enrolls a new phone. Its first report sets the allowlist to clock, calculator, camera and
     music.
     - The launcher sets `DISALLOW_OUTGOING_CALLS`. The kid can't call home; only emergency calls work.
     - The messaging app is hidden as well as suspended, though SMS isn't switched off. The kid can't read or
       send SMS, and incoming SMS may not be delivered while the default SMS app is hidden.
     - Nothing on the device page says why: no call warning covers "dialer not allowed while calls are unmanaged".
     - DEPLOY says only "allow the rest on its page".
     - Before M10 the bootstrap allowed everything but Play core, the dialer and Messages included.
   - The launcher's own tests confirm the mechanism:
     - `LockTaskHelpersTest.kt:211`: unmanaged calls with the dialer not allowed restrict outgoing calls.
     - `EnforcementPlanTest.kt:73-75`: an allowlist without the SMS app hides it.
   - Not affected:
     - Existing phones keep their lists: the bootstrap is `WHERE allowlist_json IS NULL`, and nothing resets an
       allowlist to NULL.
     - The parent can't be locked out: the launcher's own package, with the PIN-gated Settings, is always pinned.
   - Fix (either, or both):
     - Add the common dialers and SMS apps to the safe defaults: `com.google.android.dialer`, `com.android.dialer`,
       `com.google.android.apps.messaging`, `com.android.messaging`. They aren't Settings, a browser or a file
       manager, which is what the review excludes.
     - Or report the phone's system dialer and default SMS package in the status and allow those two.
     - Either way, warn on the device page when calls are unmanaged and the system dialer isn't allowed, and when
       Messages is hidden while SMS is on.
     - Say in DEPLOY's 0.24.0 note what a new phone can't do until the parent allows it.

## Low

1. **Low: a FIFO at `data/update_requested` wedges the root watcher for good, so the PWA can't install the next
   update.**
   - Where: `deploy/install.sh:623-628` and `deploy/update.sh:464-469`. The flag is checked with `[ -L ]` only, then
     `head -c 256` reads it.
   - Scenario:
     1. Code running as `kidphone` runs `mkfifo data/update_requested`.
     2. The path unit starts `watcher.sh`, and `head` blocks on opening the FIFO. A oneshot service has no start
        timeout, so it stays "activating".
     3. "Update now", restore, reboot and restart never run again until someone logs in on the Pi.
     4. The update that would fix the hole can't reach the Pi from the PWA.
   - Confirmed: the watcher on a FIFO was still blocked after 5 s.
   - Fix: refuse anything that isn't a regular file (`[ -f "$FLAG_FILE" ] && [ ! -L "$FLAG_FILE" ]`). Read with a
     bound, `timeout 5 head -c 256 -- "$FLAG_FILE"`, since a swap after the test is still possible. Add
     `TimeoutStartSec=` to `kid-phone-server-updater.service`, generous enough for an update (for example 30 min).
     `scheduler.sh`'s `grep` on `schedule.conf` and the restore's `unzip -p` on a zip in `data/backups` have the same
     check-then-open window. A timeout bounds those too.

2. **Low: `install.sh`'s `write_data_file` creates its temp file inside `data/`, where the service user can swap it
   for a symlink before root writes and chmods it.**
   - Where: `deploy/install.sh:276-282`: `mktemp "$INSTALL_DIR/data/.install.XXXXXX"`, then `printf >"$tmp"`, then
     `chmod 644 "$tmp"`, then `mv`.
   - Scenario: the admin re-runs `install.sh`, as the in-app hint suggests. On a re-run the service keeps running
     until `:336`.
     1. Code running as `kidphone` watches `data/` with inotify.
     2. It renames the new `.install.*` away and puts a symlink there. `data/` is its own directory, with no sticky
        bit, so `protected_symlinks` doesn't apply.
     3. Root's `printf` writes "6" or "v0.24.0" into the target, creating it if needed.
     4. Root's `chmod 644` then applies to the target. With `/etc/shadow` as the target, that file becomes
        world-readable, ready for offline cracking.

     The M4 comment says files are written "by rename" so a symlink is "replaced, never written through". The rename
     is safe; the temp file isn't.
   - In the same vein: `update.sh:639-643` copies `data/kidphone.db*` with `cp -p`, which follows a planted symlink,
     into the root-only `/var/backups/...`. The restore actions don't have this problem: they stop the service first,
     and ProtectSystem confines it to `data/`.
   - Fix: make the temp file outside `data/`, in a root-owned directory on the same filesystem such as
     `/opt/kid-phone-server-updater`, then `mv -fT` it into `data/`. Or write these files as the service user with
     `runuser -u kidphone -- sh -c '...'`. In `update.sh`, copy only `[ -f ] && [ ! -L ]` files with `cp -P`.

3. **Low (H2 residual): a fix dated far in the future is stored, stays "the newest fix" for good, and is never
   pruned.**
   - Where: `src/models.rs:809-829` accepts any valid RFC 3339 time. Retention keeps the newest fix by `captured_at`
     (`src/retention.rs:59-66`), and the locate page shows it.
   - Scenario:
     1. One report with `"captured_at":"9999-12-31T23:59:59Z"` arrives. It could come from anything holding the
        phone's token, or from a phone with a broken clock.
     2. The map's "Last seen" marker sits on that point from then on.
     3. "Update location now" never sees a newer fix.
     4. Retention removes the real fixes after 7 days and keeps the fake one.
   - Probe (confirmed): a 9999 fix followed by a real one; `locations.json` ends with the 9999 one.
   - Fix: drop or clamp a `captured_at` more than a few minutes past the server's time. The launcher sends the fix
     time, which is never in the future. Optionally also one older than the retention window.

4. **Low (M10 residual): the `installer` that permits the package guess is the phone's own claim, so a fake phone
   still writes a package onto the catalog row.**
   - Where: `src/handlers/device_api.rs:599-611` (`installed_by_launcher`) and `:1052`.
   - Scenario:
     1. Something holding a device token, the review's "falsk telefon", reports one new package with
        `"installer": "me.vibb.launcher"`, for example `com.android.settings`.
     2. A catalog app selected for it has no package, for example one whose manifest couldn't be read.
     3. The catalog row becomes `com.android.settings`.
     4. The parent later checks that app for a sibling's phone, and `toggle_app` allowlists Settings there.
   - The review's "Ferdig når" asked for exactly this check, and it is implemented. It just can't tell a fake report
     from a real one.
   - Probe (confirmed): the catalog package became `com.android.settings`.
   - Fix:
     - Keep a guessed package per device, so it never writes to the global catalog row.
     - Or refuse guesses that are system or known-dangerous packages (Settings, Play core, browsers, DocumentsUI).
     - Now that the APK gives the package, the guess could also go entirely, with the page asking the parent instead.

5. **Low (M10): a refused release is downloaded again every hour, forever.**
   - Where: `src/handlers/tracked_apps.rs:790-799`. A refusal deletes the file but records nothing, so the next hourly
     pass (`:846-867`) sees the same new `(tag, asset id)` and downloads it again.
   - Scenario: a developer rotates their key, a legitimate case the page explains. If the parent doesn't open the
     app's page, the Pi downloads Element X's 326 MB APK every hour (about 7.8 GB a day) and refuses it each time.
   - Fix: remember the refused `(tag, asset_id)`, in a column or in `AppSyncs`. Skip it until the release changes or
     the parent presses "Forget" or "Check now".

6. **Low (M5): the release gate runs after the Docker image is pushed and tagged, and the key isn't in a protected
   environment. Following DEPLOY's optional step breaks the release.**
   - Where: `.github/workflows/server-release.yml`. `server-release-image` (`:161-267`) pushes and tags `X.Y.Z` and
     `latest`. Only then does `server-release-publish` (`:269`) check the secret and the pinned key (`:287`).
   - Scenario 1: 0.24.0 is tagged before the key is set up. The status notes say that must not happen, but nothing
     enforces it.
     - GHCR gets `handy-server:0.24.0` and `latest`.
     - The Pi tarball is never published.
     - Re-tagging re-runs the image job.
   - Scenario 2: the owner follows DEPLOY "Release signing" step 4 and moves `SERVER_MINISIGN_KEY` into an
     environment.
     - The publish job declares no `environment:`, so it no longer sees the secret.
     - Every release then fails with "the SERVER_MINISIGN_KEY secret is not set".
   - As a plain repository secret, the key is readable by any workflow pushed to any branch by anyone with write
     access. That is the review's first M5 attacker ("den som kan skrive til repoet"). The launcher and music already
     sign in the protected `release` environment.
   - Fix:
     - Check "secret present, key pinned, a test signature verifies with the pinned key" in the first job.
     - Sign in the publish job with `environment: release`. Its deployment rule must allow `server-v*` tags, like
       `music-v*`.
     - Correct DEPLOY step 4: the secret goes in that environment, and it isn't optional.

7. **Low (M5, until a signed release exists): the documented install command now fails for everyone.**
   - Where:
     - `server/README.md:28`, DEPLOY, and the in-app hint (`src/security.rs:260`) all fetch `install.sh` from `main`.
     - `main`'s `install.sh` has `SERVER_SIGNING_PUBKEY="not-set"` and refuses everything.
     - Once a key is pinned, it still refuses every release before 0.24.0, which has no `.minisig`. `curl -f` stops
       on the 404 before `verify_signed`, so the user sees a bare curl error, not "No release signing key is
       pinned".
   - Scenario: a new user, or a Pi that needs the documented "re-run install.sh" (the old repo's installs, a watcher
     schema bump), can't install anything until the owner pins the key and a signed 0.24.0 is published.
   - Fix:
     - Do the key setup and tag 0.24.0 soon.
     - Meanwhile, give DEPLOY and the README one line pointing at the `server-v0.23.x` tag's own `install.sh`.
     - Optionally, run `verify_signed`'s not-set check before the downloads, for the clearer message.

8. **Low (M6): a feed that never loads freezes every phone's DNS list, custom blocks included.**
   - Where: `src/handlers/device_api.rs:686-697`: 503 while any feed the phone uses isn't `loaded`. `loaded` is
     in-memory, from this start only.
   - Scenario: the parent adds a feed whose URL is mistyped, or whose format `parse_feed` doesn't read: adblock syntax
     (`||example.com^`) or a `127.0.0.1 host` hosts file. It is enabled on creation and parses to "no domains", so it
     never loads.
     - From then on every phone gets 503 for its blocklist.
     - The parent's custom block, say tiktok.com, never reaches a phone.
     - Only the DNS page's per-feed line says why. The custom-domain form doesn't.
     - Before M6 such a feed was harmless.
   - Fix (either):
     - Persist each feed's last good domains, for example `data/dns_feeds/<id>.txt`. After a restart a feed is then
       loaded from disk, which also covers the review's reboot scenario better than a 503. A feed that has never
       loaded on this server can be left out without shrinking any phone's list.
     - Or refuse to save a feed whose first download yields nothing.

     Either way, add a warning near the custom-domain form while a phone is being refused.

## Notes (not findings)

- **M2 and the Tailscale key.** The review's M2 lists the Tailscale auth key among a backup's secrets. Every backup
  still holds it; only sessions are stripped. DEPLOY's "Keep backups as safe as the admin password" list doesn't
  mention it. With design 22's one-time keys the exposure is one tailnet join. Either blank it in `snapshot` (it is
  write-only in the PWA and easy to re-enter), or name it in DEPLOY.
- **M10, for the launcher wave.**
  - The server's `signer_sha256` is read, not verified: the first certificate of the v3 block, else the v2 block. It
    is a claim the phone has to check against `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)`.
  - Android 13+ verifies a v3.1 block (`0x1b93ad61`, rotation targeting SDK 33+), which the server ignores. The
    server pins the v3 block's older key, while the phone's `apkContentsSigners` shows the new one. Compare against
    the signing lineage (`signingCertificateHistory` / `hasSigningCertificate`), not only the current signer.
  - With more than one signer, the server pins only the first.
  - The package check passes when the manifest can't be read (`check_pins`, `:509`), unlike the signer check
    (`(Some, None)` is refused). Exploiting that needs the pinned key, so it is minor. Failing closed there too would
    be consistent.
- **Migration numbering.**
  - The wave left 0058-0060 unused (0061, 0062). `relock-24b` takes 0063/0064, so nothing collides.
  - The stale `relock-24b-s4` branch has its own `0062_window_relock.sql`. `sqlx::migrate!` would reject that at
    build time.
  - If 0058-0060 are ever filled, sqlx applies them out of order on a 0.24 database, and `pre_migration_backup`
    (`MAX(version) < latest`) would take no backup. Better to skip them.
- **0061 signs everyone out once** on the first start of 0.24.0, including a parent who just pressed "Update now".
  DEPLOY's 0.24.0 notes could say so next to the restore line.

## Missing tests

- `backup_sync.sh` with symlinks in `data/backups/` and as `live_mirror.db` (Medium 1). Today only the `chmod` lines
  are asserted.
- A catalog release that names a package the phone already has, Play or system (Medium 2).
- A new phone's plan: calls and SMS with the safe default (Medium 3). A launcher-side `EnforcementPlanTest` with the
  server's default list would do.
- `check_pins` for a pinned app whose new release has no v2/v3 signature, the `(Some, None)` refusal. The fixtures
  have no v1-only APK.
- The shell itself: `setting` in `scheduler.sh`, `action_restore_backup` and `verify_signed` are only checked as text.
  A test that runs them under `bash` with stubbed `systemctl`/`chown` would have caught Medium 1's class. CI can
  install `unzip` and `minisign`.

## Verified OK

- **H1:**
  - `valid_clock_time` is exactly `HH:MM`, 00:00-23:59, and rejects Arabic-Indic digits.
  - All three forms answer 400 and write nothing. `frequency` is checked against a list.
  - `scheduler.sh` never sources `data/`. Exercised with `$(...)`, `;`, backticks, CRLF and duplicate keys: an
    injected value runs nothing and falls back to the default; the first key wins.
  - `update.sh` rewrites all four root scripts by temp file and rename, so a running script keeps its inode. The
    heredocs are equal (test). No root heredoc sources anything but `actions.sh`.
- **H2:**
  - `checked_location` rejects NaN/inf, out-of-range coordinates and a negative accuracy, and normalises the time to
    UTC millis.
  - `js_string` is JSON with `<`, `>`, `&`, U+2028 and U+2029 escaped, the only `|safe` in a script.
  - The popup is a `textContent` node.
  - Migration 0054 only deletes, and keeps the launcher's `Instant.toString()` forms: nanoseconds, `Z` and offsets
    pass both the GLOB and `julianday`.
- **M4, the restore, exercised with the real `unzip`:**
  - These are refused before the app is stopped: a symlinked zip, a symlink entry named `kidphone.db` (fails the
    SQLite header), a missing entry, a body over the cap (the `head -c` plus `pipefail` path).
  - A symlinked `data/kidphone.db` is replaced, and its target is untouched.
  - The pre-restore copy skips symlinks.
  - `restore_applied` is written by rename.
  - During the window the service is stopped, and ProtectSystem=strict means the service can only ever write inside
    `data/`. So `data/` itself and `/opt/kid-phone-server` can't be swapped, and the temp files in `data/` aren't
    raced.
- **M2:**
  - `snapshot` removes `tower_sessions` with `secure_delete`. The test checks the raw bytes for the session id, and
    VACUUM INTO leaves no old free pages.
  - The scratch file is in `data/`, not `backups/`.
  - The backup zip, live mirror and pre-migrate copy all use it.
  - Both restore paths call `forget_sessions`. 0061 bumps every epoch once (sqlx runs it once).
  - umask 077 is set in the process, in the unit, and in the update's drop-in. The `chmod` runs as the service user
    with `find -P`.
  - Root reads `schedule.conf` and the flag regardless of the 0600 mode.
- **M5, `verify_signed` with real minisign:**
  - `not-set` refuses.
  - A good signature passes.
  - A wrong tag, `update.sh`'s signature presented for the tarball, a tampered file, a missing `.minisig`, and a
    comment with extra text all refuse.
  - The tarball is checked before `tar`, and `update.sh` before `bash`. `update.sh` comes from release assets, not
    `raw` at a movable tag.
  - The six key lines and the four `verify_signed` bodies are equal (test).
  - The workflow signs with `-t "$file $TAG"` and verifies against the pinned key before publishing.
  - The first hop from 0.23 is the old unsigned path. That is inevitable and documented.
  - Before the key exists, nothing gets stuck with `not-set`: `install.sh` fails before installing anything, and no
    0.24.0 tarball can be published (but see Low 6 for the image).
- **M6:**
  - Each feed keeps its previous domains on error, a non-2xx, more than 32 MB (Content-Length and streamed), or "no
    domains".
  - A DB error keeps the compiled list, and the endpoint answers 500.
  - The launcher (`DnsFilterEngine.refreshIfNeeded`) keeps its list on any non-2xx and doesn't store the version, so
    it asks again at the next sync.
  - The startup compile is still awaited in `main` (`:344`), so switching from `interval` to `sleep` doesn't delay
    the first list.
- **M10, the parser:**
  - Every offset goes through `get`/`checked_add`; nothing indexes unchecked.
  - `pair_len` < 4 or 0 ends the walk with `None`, so it can't loop.
  - The signing block is capped at 16 MB and the manifest at 4 MB.
  - Zip64 gives `None`.
  - UTF-8 and UTF-16 pools are both read; the first element must be `<manifest>`; only a namespace-less `package`
    counts.
  - The existing fuzz-style test (truncations and byte flips) never panics.
  - Startup pins only from a cached file whose package matches the catalog.
  - Phones that already have an allowlist are unaffected by the new default, and the parent is never locked out.
- **M7:** apps are capped and deduplicated, with real package names only. `lock_reason` (the launcher's enum names,
  at most 11 chars) and `app_version` are clipped. `/security` pages by id.
- **L5:** askama escapes `data-name`, and `confirm` gets plain text.
- **L12:**
  - The launcher reports a result for ring, stop_ring, lock and locate, so "never confirmed" only shows when one is
    really missing. A wipe can't report, and the page says so.
  - Times are compared in UTC.
- **L9:** Rust 1.99.0 exists (it is the local toolchain) and is pinned in both workflows; so is `cross@0.2.5`.
- **L10:** no `| sudo bash` remains, and the hint uses `main`. The root `.gitignore` covers keystores, `*.p12`,
  `*.pk8` and `*.key`.
- **Contracts:**
  - The launcher DTO `signerSha256` maps to `signer_sha256`, and the compat test decodes both shapes.
  - `phone_vectors.json` and the music snapshots are untouched.
  - `policy_json_keys_snapshot` doesn't cover `/api/devices/apps`, and needs no change.
