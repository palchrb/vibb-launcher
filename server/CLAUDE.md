# Kid Phone Server — Project Context for Claude Code

## What this is

A lightweight, purpose-built parental-control server for the Kid Phone project. It's the sole backend for the client - there is no other MDM server or protocol involved. An earlier prototype used a different MDM stack entirely; that's been fully replaced, not extended, and none of its code, protocol, or data model carried over.

The client is the launcher in `../launcher/` of the same monorepo (`palchrb/vibb-launcher`; was the `kids-launcher-mdm` fork - a custom Kotlin Android launcher that's also the Device-Owner MDM agent, package `me.vibb.launcher` (Kotlin namespace `com.kidslauncher.mdm`), networking code in its `app/.../server/`). It speaks this server's API directly - enrollment, policy fetch, and status reporting are all this server's own device-facing routes under `/api/devices/*`. See `../launcher/CLAUDE.md` for client-side architecture, and the root `CLAUDE.md` for the layout. Older notes below say "kids-launcher-mdm" or "that repo" for the launcher.

**Both upstream repos are public** (as of 2026-07-31): `siesta5787/kid-phone-server` (GPLv3) and `siesta5787/kids-launcher-mdm` (GPLv3 for its own code; the inherited app-list screen stays MIT per `LICENSE-MIT-UPSTREAM` in that repo - it's a fork of Josia Pietsch's µLauncher, though the fork no longer describes itself that way since almost everything else - the gesture system, Minimalist Mode, the whole parental-control layer - is original).

## This fork (palchrb)

This is `server/` in the `palchrb/vibb-launcher` monorepo (was the `palchrb/kid-phone-server` fork). The repo publishes
both components: the launcher from `launcher-vX.Y.Z` tags (stable ones are GitHub's "latest" release) and this server
from `server-vX.Y.Z` tags (`.github/workflows/server-release.yml` at the root: tag must be on master/main and match
`Cargo.toml`; published with makeLatest false). So the in-app update check (`handlers::system_update`) and
`install.sh`/`update.sh` look up the newest stable `server-vX.Y.Z` in the releases *list*, never `releases/latest`,
and the tracked-app sync skips `server-v*` releases. Everything that names a GitHub repo or a launcher build is configuration,
not code: `src/config.rs` (`ForkConfig`, `AppState.config`) reads `SERVER_RELEASE_REPO`, `LAUNCHER_ADMIN_COMPONENT`,
`LAUNCHER_APK_URL` and `LAUNCHER_SIGNATURE_CHECKSUM` once at startup, with defaults pointing at the monorepo; the
pre-monorepo defaults (`palchrb/kid-phone-server`, the `kids-launcher-mdm` APK URL) are read as today's, migration
0031 repoints a catalog row watching `palchrb/kids-launcher-mdm`, and `update.sh` maps the old `KPS_REPO`. Watcher
schema 6: `actions.sh` fetches `server/deploy/update.sh`. The
checksum has no default: without it the provisioning page shows a banner and no QR (never upstream's checksum). An
invalid value is logged and treated as unset. The root-run deploy scripts never read `.env` (it's writable by the
service user): `install.sh`/`update.sh` take `KPS_REPO=owner/repo` (validated), and `install.sh` substitutes it into
`actions.sh` at install time. `update.sh` downloads with `curl -f` and unpacks/checks the release before stopping the
service, restarts it from an `ERR` trap if anything fails after the stop, copies the DB and the old binary to
`/var/backups/kid-phone-server/` before every swap, and prunes old backups only once the new version is running
(rollback steps in DEPLOY.md). Production launchers are the release build, package `me.vibb.launcher` (renamed from
`com.kidslauncher.mdm` on 2026-10-06: `LAUNCHER_ADMIN_COMPONENT`'s old default is read as the new one, migration 0037
renames the launcher row's package; the Kotlin class names keep `com.kidslauncher.mdm`)
(no `.debug` suffix), from normal GitHub releases with the stable asset name `kids-launcher-mdm.apk` - so the
launcher's catalog row has `include_prereleases` **off** (release candidates are prereleases). The server creates that
row at startup when no row is the launcher, watching `LAUNCHER_APK_URL`'s repo (`tracked_apps::ensure_launcher_row`,
the "global catalog" bullet below). Some bullets below
describe upstream's rolling `pre-release` debug builds; that's history, not how this fork ships.

## Tech stack

Deliberately mirrors `board-game-tracker` (another self-hosted Pi app by the same developer) since it already solved the boring-but-essential infrastructure for this exact hardware target:

- **Language:** Rust, **Web framework:** Axum, **Database:** SQLite via `sqlx` (WAL mode)
- **Templating:** Askama (compile-time-checked server-rendered HTML, no JS build step, no frontend framework) - templates are compiled into the binary, so editing one requires a rebuild, not just a file save. A plain `cargo run` dev server (no `cargo watch`) needs to be manually killed and restarted after any template/static change.
- **Sessions/auth:** `tower-sessions` + `tower-sessions-sqlx-store`, `argon2` for the admin password, mandatory TOTP 2FA
- **UI:** the parent PWA is **Vibb Phone** (design 31, `docs/design/31-pwa-redesign.md`; the 2FA issuer stays "Kids Device MDM"), mobile-first bottom tab bar (Devices / Apps / Locate / Music / Settings, `templates/partials/tab_bar.html`: each page sets `{% let tab = "..." %}` before including it and the server marks that tab `aria-current="page"`; an app shell (design 43): the document never scrolls, `main.container` does and the bar is a plain flex child under it - no fixed box - and `static/scroll-restore.js` keeps the place in `main`, on Back and reload too (`docs/notes/server/pwa.md`, "The app shell"); no brand bar since design 31 steps 10 and 15 - a tab's landing page opens on its large title, every other page on its back link and large title; Time Rules, Web Filter (`/dns`) and Blocked Activity are rows of the Settings hub, `handlers::settings_hub`, whose values come only from the database - never the network), iOS-style grouped-list CSS (`static/style.css`: tokens for light and dark, rows, the switch as a checkbox plus a sibling `<span class="knob">`, ⓘ, badges, Attention levels, flash, confirm; browser floor Chrome/WebView 80 and iOS Safari 14, no `:has()` or nesting), installable PWA (`static/manifest.webmanifest`, minimal install-only service worker at `templates/sw.js` - no offline page caching, deliberately, since this app handles session cookies and device bearer tokens), light/dark theming via `prefers-color-scheme` CSS variables (no manual toggle)
- **Hosting target:** Raspberry Pi Zero 2 W (or similar aarch64 board), `aarch64-unknown-linux-musl` cross-compiled via GitHub Actions on every `server-v*` tag (root `.github/workflows/server-release.yml`, uses `cross`) - `deploy/install.sh` downloads the newest `server-v*` release and sets up a systemd service

## About the developer

No prior programming experience - build features directly rather than explaining Rust concepts, but loop them in on real architecture/schema/UX decisions and explain the *why* in plain language.

## Fail closed

- **A status report is stored within limits (security review M7).** `device_api::cap_installed_apps`: real package
  names only (`is_package_name`), no duplicates, labels clipped to 100 chars, an installer that isn't a package name
  dropped, at most `MAX_INSTALLED_APPS` (1500); `lock_reason` 32 chars, `app_version` 64; every JSON blob already
  goes through its own `sanitize_*`. An entry's `battery_exempt` (launcher round 7 §2: `Option<bool>`, only for the
  catalog apps the launcher protects from force-stop, absent otherwise) is kept as sent; the shared contract is
  `testdata/installed_apps_battery.json` (`intake_limits::the_battery_state_of_a_protected_app_is_kept` posts it), and
  the phone's Apps page shows it as the row's "Background: …" line (`devices::background_line`). `/security` pages back 200 events at a time (`?before=<id>`), so a flood can't
  hide a sign-in attempt.
- **A catalog app is pinned to its package and signing key (security review M10, server part).** `src/apk.rs` reads
  an APK's manifest `package` (binary XML) and its signer (SHA-256 hex of the first certificate in the v3, else v2,
  signing block - what `apksigner --print-certs` and the launcher's `SelfUpdate.signers` give). `tracked_apps`
  `sync_one_app` and the manual upload refuse a release whose package differs from `package_name` or whose signer
  differs from `signer_sha256` (migration 0062), and pin what the first release has (`store_pins`); a first package
  that a catalog app can never be (`never_a_catalog_package`: `android`, `com.android.*`, Play core) or that a phone
  has as a system app or from another installer (`foreign_on_a_phone`) is refused (QA of the security wave,
  Medium 2); a manifest that can't be read is refused once a package is pinned, like the signer. `backfill_pins`
  (after the hash backfill at startup) pins releases cached before, with the same checks. A refused `(tag, asset id)`
  is remembered in `AppSyncs` (`SyncStatus::refused`): the hourly sync doesn't download it again, "Check now"
  (`sync_app(.., forced)`) and "Forget" do (QA Low 5). **A release never allows
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
  restricts outgoing calls unless the system dialer is allowed and hides an SMS app that isn't (QA Medium 3) - never
  everything installed. `calls::call_report` warns when the allowlist leaves either out. Fixtures: `testdata/apk/` (aapt2 + apksigner, throwaway keys, no code).
- **`GET /api/devices/policy` never answers with a default policy** (`handlers::device_api::build_policy`): a missing `device_policy` row, an unparseable `allowlist_json`, or any DB error is an empty 500 (logged). The launcher treats non-2xx as "no fresh policy" and keeps enforcing its cache. A `NULL` allowlist is still served as `null` (a new device before its first heartbeat); `"[]"` is served as `[]` and means "nothing allowed" on the launcher. Upstream returned `DevicePolicy::default()` (kiosk off, no allowlist) on these errors, which unlocked the phone.
- **The launcher reports what it did with the policy** (`StatusReportRequest.policy_state`, migration `0021`): `"ok"`, `"cache_corrupt"`, `"rejected_suspect"`, `"fresh_decode_failed"` or `"server_error"` (this server answered 5xx) - anything but `"ok"` (or `NULL` from an older launcher) is an Attention alarm ("Phone can't apply its settings", About This Phone › Software says which). `restrictions_paused` reports the launcher's PIN-gated, time-limited "pause all restrictions" switch, an Attention alarm too ("Restrictions paused", to the summary's Quick actions).
- **Allowlist writes never guess**: `devices::add_to_allowlist`/`remove_from_allowlist` read-modify-write in one transaction (`edit_allowlist`) and return an error on a DB error, a missing policy row or a stored allowlist that isn't valid JSON - `toggle_app` then answers 500 and writes nothing. The first-heartbeat bootstrap in `device_api::status` is one `UPDATE ... WHERE allowlist_json IS NULL`, so a read error can't make it overwrite a parent's list.
- **`create_device` inserts the device and its policy row in one transaction** and returns 500 if either fails, so a device without a policy row can't be created through the UI.

## Tests

`cargo test` runs in-process tests in `src/tests/` (and in CI): `build_admin_router` (split out of `main()` for this; `TestApp::device_router()` for the phone listener) is driven directly with `tower::ServiceExt::oneshot`, against a fresh migrated SQLite file in a temp dir per test - no network, no background tasks. `TestApp` (`src/tests/mod.rs`) has helpers to create and enroll a device and send JSON requests with or without a bearer token; handlers extracting `ConnectInfo` get a fixed loopback address. A test that documents a known bug is marked `#[ignore = "..."]` until the fix lands - run `cargo test -- --ignored` to see them fail. Admin handlers can be called directly with `TestApp::state` (skipping the session/2FA middleware), or through the real middleware: `TestApp::admin_cookie()` seeds an onboarded admin with a fixed TOTP secret, logs in via `/login` + `/auth/verify-2fa` (with a TOTP step not used yet - a code works once) and returns the session cookie for `request_form` (urlencoded POSTs) and `get_page`. Requests come from `LOOPBACK_PEER`; `send_via(router, peer, request)` sends from any peer to the admin router or `device_router()` (the phone listener), `with_env(&[...])` builds the app with other listener settings. `policy_json_keys_snapshot` is the contract with the launcher's `PolicyResponse` DTO - update both sides together.

## Non-obvious gotchas hit so far

- **`security::REQUIRED_WATCHER_SCHEMA` must be bumped in the exact same commit as any `deploy/install.sh` change to `WATCHER_SCHEMA_VERSION`, and it's easy to forget since they're two separate files with no compile-time link between them.** The DNS/Filters commit bumped `install.sh`'s `WATCHER_SCHEMA_VERSION` from `1` to `2` (adding the `dns_filter_enable`/`disable` root actions), but `REQUIRED_WATCHER_SCHEMA` was left at `1` - so `security::watcher_needs_update()` never actually flagged a Pi whose watcher predates that commit as needing a re-run of `install.sh`, even though its watcher script genuinely doesn't recognize `dns_filter_enable` as a valid action (falls through to `install.sh`'s "Unknown or empty update-request action" case, and the iptables redirect silently never gets applied - confirmed live: "Enable DNS filtering" showed 0 queries handled and ads still loaded on a device that otherwise had this Pi correctly configured as its Tailscale exit node). Fixed by bumping `REQUIRED_WATCHER_SCHEMA` to `2` to match (now `3`, bumped again in the same commit as the DNS-over-TLS iptables/cert-renewal actions, precisely to not repeat this; `5` in this fork, where `actions.sh` gets the release repo substituted in). If a future privileged root action is ever silently not taking effect, check this pairing first - specifically whether the Updates page's "watcher needs update" banner should have fired but didn't.
- **axum's built-in `Form` extractor cannot deserialize repeated same-named form fields** (e.g. multiple checked checkboxes named `allowed_packages`) into a `Vec<String>` - errors with `invalid type: string "...", expected a sequence`. Fixed in `handlers::devices::update_policy` by taking raw `axum::body::Bytes` and parsing manually with `form_urlencoded` instead of `Form<T>`. Reuse this pattern for any future repeated-checkbox-group field.
- **Browsers cache `/static/*` assets aggressively with no revalidation** - editing `style.css` and reloading can silently keep serving the old cached copy even after a hard navigate. `partials/head.html` links it with a `?v=N` cache-busting query param (bump `N` when the stylesheet changes) - the same pattern `board-game-tracker` already uses. A lost `}` in `style.css` doesn't fail anything by itself - the browser nests everything after it and silently drops ~160 lines (the PWA browser pass of 2026-10-14, High 1) - so `tests::shell::style_css_has_the_shape_browsers_read` tokenizes the file (comments, strings, braces) and fails on a rule opened inside a declaration block, an unbalanced brace, or an unclosed comment or string.
- **The dev server is a plain `cargo run`, not `cargo watch`** - it will not pick up template or static-file changes on its own. Kill the running `cargo`/`kid_phone_server` processes and restart after any such edit, or you'll be testing against stale HTML/CSS while genuinely-fresh files sit on disk (this has caused real confusion mid-session more than once).
- **(Since handy step 10 phones have no Android screen lock - the launcher's own PIN lock replaces it, see above - but the keyguard bit below stays forced.)** **`LOCK_TASK_FEATURE_KEYGUARD` is disabled by default in Android's lock-task mode, and that suppression is enforced at the `DevicePolicyManager`/`system_server` level - it keeps applying even before a device is decrypted (Before First Unlock), when the client app's own process can't run at all.** A real device got stuck at boot after GrapheneOS's auto-reboot-after-inactivity feature re-locked storage while this bit was off for that device: no keyguard to enter a PIN into (suppressed by policy) *and* no launcher available either (this app was the exclusive enforced Home app, and its components aren't resolvable pre-unlock) - a total deadlock, recoverable only via hardware-level recovery mode. Fixed by forcing `LOCK_FEATURE_KEYGUARD` on unconditionally in `update_policy`, same as the other always-on features - it's no longer admin-configurable. The tradeoff this accepts: every kiosk-mode device now also requires a PIN to resume from sleep, not just after a reboot, since Android doesn't expose those as separate bits.
- **`rustls` 0.23+ needs an explicit process-wide default `CryptoProvider` installed before *any* TLS connection works, and there's no error at the call site that says so.** Without `rustls::crypto::ring::default_provider().install_default()` early in `main()`, TLS just silently fails - first discovered via the (now-deleted, see Phase E above) DNS filter's DoT upstream forwarding, which failed every query with `NetError(NoConnections)`, no panic, no obvious log line pointing at the real cause, only found by turning on `hickory_resolver=debug` tracing and noticing the connection attempt simply never got anywhere. **Still just as real after Phase E removed that code** - `reqwest`'s own `rustls` feature (used for blocklist downloads and every GitHub Releases API call in `tracked_apps.rs`) depends on this exact same process-wide install; that's the only reason `main.rs` still calls it and `rustls` is still a direct dependency at all (see Cargo.toml's comment on it). Install exactly one provider once, at startup, before anything that might open a TLS connection runs - and don't ever remove this call while `reqwest`'s `rustls` feature is still in use, even if the original DoT code that motivated adding it is long gone.
- **A missing root-certificate-store feature is a second, separate way to get the exact same silent TLS failure** as the gotcha above, even with the crypto provider correctly installed - the TLS client needs either a `webpki-roots`-style bundled root store or a way to reach the OS's own trust store (`rustls-platform-verifier`/`native-roots`), or certificate validation has no trust anchors and every handshake fails post-connect with no distinguishing error. If outbound HTTPS (blocklist downloads, GitHub API calls) ever breaks again, check both of these before assuming it's a network/firewall issue - `Test-NetConnection`/`nc` succeeding against the remote port proves nothing here, since the TCP+TLS handshake itself can still complete while certificate validation silently fails afterward.
- **`tailscale status --json | grep '"ID"'` matches every device in the tailnet, not just the Pi itself** - the JSON always includes both `Self` and every `Peer`, each with their own `"ID"` field, regardless of the `--self` flag (which only affects the plain-text table output, not JSON structure). To get just the Pi's own stable node ID (the value `AppEnforcer.applyVpnRestrictions` on the client pushes to Tailscale as `ExitNodeID`, and what goes in `device_detail.html`'s "Exit node ID" field), use `tailscale status --json | jq -r '.Self.ID'`. **Repeated this exact mistake once already** with `action_tls_cert_renew`'s hostname lookup (`grep -o '"DNSName":"[^"]*"' | head -1 | ...` instead of `jq -r '.Self.DNSName'`) - worse, this one failed completely silently on the real Pi: under `set -euo pipefail`, a bare `var=$(pipeline)` assignment where the pipeline's last stage (`grep`) matches nothing makes the whole script exit immediately, before the script's own `[ -z "$hostname" ]` check (and its helpful error message) ever runs. `jq` also isn't installed by this script by default - `iptables`/`jq` are both explicit `apt-get install` prerequisites now; don't assume a utility is present just because a past interactive session happened to use it over SSH.
- **QR-code zero-touch provisioning (`handlers::provisioning`) failed live on a genuine GMS-certified device (Motorola Moto G Play 2024, Android 14) during the Device-Owner-provisioning step specifically** - not app installation (the identical APK installs fine as a plain sideload on that same device afterward, and the download URL/signature checksum were independently confirmed correct). Not yet confirmed via logs (no adb access reachable before `ManagedProvisioning` resets back to OOBE on failure), but the leading theory is that Google restricts the zero-touch/QR Device-Owner flow specifically to Play-Store-sourced DPCs on GMS-certified hardware - separate from whatever (if anything) gates a plain sideload install. If true, a release-signed (non-debug) build wouldn't fix this specific failure, since it isn't a signing/trust issue. This is in addition to the already-documented GrapheneOS limitation above (no `ManagedProvisioning` trigger at all) - so as of this writing, QR provisioning has no confirmed-working real-device target; manual `adb shell dpm set-device-owner ...` remains the reliable path everywhere.

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
  install.sh, update.sh and both copies of actions.sh, with the pinned `SERVER_SIGNING_PUBKEY`) needs `FILE.minisig`
  to verify with minisign and its trusted comment to be `NAME TAG`; `download_release` checks the tarball before
  unpacking it, and `action_app_update` takes `update.sh` + `.minisig` from the release assets (never
  raw.githubusercontent) and checks it before running it. `server-release.yml`'s `server-release-sign` job (the
  `release` environment, before the image job) signs both with that environment's `SERVER_MINISIGN_KEY` and checks
  them against the key pinned in `deploy/update.sh` - no secret or no pinned key, nothing is pushed or published. `"not-set"` (the state until the owner pins a key) refuses everything. `tests::deploy_scripts`
  keeps the six key lines and the four `verify_signed` equal.
- **Root scripts never read `data/` as code (security review H1).** `data/` belongs to the service user, so
  `scheduler.sh` reads `schedule.conf` key by key with `grep` and checks each value against a pattern (a bad one
  falls back to its default and is logged; a symlinked file is ignored), and every form that writes a schedule file
  accepts only `HH:MM` times (`security::valid_clock_time`, 400 and nothing written otherwise). `update.sh` rewrites
  every root script (`actions.sh`, `watcher.sh`, `scheduler.sh`, `backup_sync.sh`) on every update with install.sh's
  text (`tests::deploy_scripts` keeps the heredocs equal and checks that no root heredoc sources anything but
  `actions.sh`). The root restore (M4) reads only the zip's `kidphone.db` (`unzip -p`, at most 1 GiB, SQLite header
  checked), keeps the replaced database in root-owned `/var/backups/kid-phone-server-prerestore/<time>/` (3 kept),
  and puts the new file in place with `mktemp` + `mv -T` (a symlink at the target is replaced, never written
  through); `watcher.sh` ignores a symlinked flag, and install.sh writes `data/watcher_*` by rename.
  `backup_sync.sh` copies only regular files at the top of `data/backups/` and a regular `live_mirror.db`, with
  `cp -a --remove-destination`, after deleting everything on the drive that isn't a regular file or a directory
  (QA of the security wave, Medium 1: `cp -au` wrote through a link it had copied to the drive the run before).
  `tests::deploy_scripts::run_root_script` runs a root heredoc under bash in a sandbox, with stubs.
- **No admin session leaves the live database (security review M2).** Every copy goes through
  `backups::snapshot(db, scratch, target)`: `VACUUM INTO` a scratch file in `data/` (never `data/backups/`, which
  `backup_sync.sh` copies to the drive), `DELETE FROM tower_sessions` and a blanked
  `provisioning_settings.tailscale_auth_key` (`strip_secrets_in`) with `secure_delete` and the journal in memory,
  then a rename - backup zips, the live mirror and `pre-migrate-*.db`. A restore forgets every session at the next
  start (the container's `apply_restore_request`, or `data/restore_applied` that `actions.sh` leaves,
  `startup::take_restore_applied`, logged `backup_restored`); migration 0061 bumped every `session_epoch` once, for the
  ids in older backups. The process sets umask 077 first thing in `main` (rustix), the unit has `UMask=0077`
  (update.sh adds it as a drop-in), install.sh/update.sh `chmod` `data/` 700 and its files 600 as the service user
  (`runuser`, so a symlink reaches nothing of root's), and `backup_sync.sh` keeps the drive's copies root-only. The
  2FA secret stays unsealed (design 22 §3.2.8). A new copy of the database anywhere goes through `snapshot`.

## Data model (SQLite, see `migrations/`)

**Migration numbers 0058-0060 are skipped on `main`** (the wave took 0061/0062 while a branch held them). Never fill
them: sqlx would apply them out of order on a 0.24 database, and `startup::pre_migration_backup` (`MAX(version) <
latest`) would take no backup first. A new migration takes the next number after the highest one on `main`.
**0070 is design 27's** (`0070_music_episode_art.sql`, episode art) and `0071_music_sonos_rooms.sql` (design 26a) sits
above it (qa-26a-merge-code M1): both deploy in order, 0070 before 0071 - the natural order since design 27 is on
`main`. The same hazard stays for a server that ran 0071 without 0070 (a build of the Sonos branch from before it
merged design 27): sqlx would apply 0070 out of order and `pre_migration_backup`, comparing only `MAX(version)`,
would take no `pre-migrate-*.db`. Never deploy such a build; or the backup check compares the set of applied
versions with the compiled ones (a change for the main session, not made yet).
`0072_music_storytel.sql` (design 26b, written as 0066 on `music-2-storytel`, renumbered at the merge of
`music-1b-sonos`) sits above both: 0070 or 0071 reaching a server that has run 0072 is the same hazard. Since the
Storytel branch merged design 27 (through `music-1b-sonos`, 2026-10-12) every build of it carries 0070-0072 and applies
them in order; never deploy a build of it from before that merge (0071 and 0072 without 0070).
`0074_music_spotify.sql` (design 26c, written as 0073 on `music-3-spotify`, renumbered at its merge of main on
2026-10-13, where design 30 holds `0073_music_data_and_storage.sql`) sits above 0073: the same hazard if 0073 reaches a
server that has run 0074; design 30's server ships before or with Spotify's. A dev database that ran the Spotify
branch before that merge holds its old 73 (another checksum) and stops at `VersionMismatch(73)`: a fresh `data/`.
**A migration file is frozen from its first commit on a pushed branch, comments included**: sqlx stores a checksum of
the whole file and refuses to start (`VersionMismatch`) when an applied migration's text changed. Later notes go into
this file or the design doc; a dev database that ran an earlier text needs a fresh `data/` (qa-26a-merge27-code L2).
`0075_clock_format.sql` (design 34 P12) follows 0074, `0076_softer_wallpapers.sql` (design 34 P8) follows 0075,
`0077_vibb_one.sql` (design 34 P11, the launcher's catalog row named "Vibb One") follows 0076
(`migrations_73_to_84_in_order`); `0078_parent_events.sql` (design 35, all of it in one file) follows 0077 and
`0079_custom_dns.sql` (design 36) follows 0078, so 0078 must reach a server before or with 0079, never after (the
out-of-order hazard above; both merged into `main` on 2026-10-18, so every build from there carries both).
`0080_phone_power.sql` (design 38, the two power switches) follows 0079, and
`0081_music_art_more.sql` (2026-10-19, episode pictures ASAP: `music_listings.art_more`) follows 0080
(`migrations_73_to_84_in_order`), and `0082_art_backfill_and_last_opened.sql` (server round 5: `art_more` set for
the listings with untried episode pictures, `push_subscriptions.last_opened_at`) follows 0081, and
`0083_lock_glance_text_per_app.sql` (design 51: `device_lock_glance_apps.show_text`, backfilled from
`device_policy.lock_glance_text`, which nothing reads after it) follows 0082, and
`0084_lock_wake_on_notification.sql` (launcher round 7 §3: `device_policy.lock_wake_on_notification`, default 0)
follows 0083 (`migrations_73_to_84_in_order`). The next free number is 0085.

- `admin_users` - parent login accounts, TOTP secret (+ `totp_last_step`), forced-password-change flag, TOTP failure counter and lock (`lock_count`, `locked_until`), `session_epoch`
- `devices` - one row per kid's phone: name, enrollment code hash + kind (+ expiry, cleared on use; `enrollment_code` itself is always NULL since 0052), hashed bearer token, enrolled/last-seen timestamps
- `device_policy` - one row per device: `lock_wake_on_notification` (launcher round 7 §3, 0084: Lock Screen & PINs' "Wake the screen for a notification", sent as `launcher_ui.lock_wake_on_notification`), allowlist JSON, `custom_schedule_enabled` (own time rules + `daily_budget_json` instead of the global ones), `location_mode`/`location_interval_minutes`, the frozen pre-step-6 schedule columns, `kiosk_desired` (always `1` now), `lock_task_features` (bitmask), `override_pin_hash`/`override_pin_salt`, `kid_pin_hash`/`kid_pin_salt`/`kid_pin_length` (step 10)
- `global_schedule` - singleton (`id = 1`): the global `daily_budget_json` and the frozen pre-step-6 schedule; `time_rules` (device_id NULL = global) and `time_lifts` - see "Time rules" above
- `device_status` - append-only heartbeat log (pruned after 30 days, the newest report per phone kept): lock reason, kiosk-engaged, installed-app snapshot, app version, timestamp, `offline_override_used`, `policy_state`, `restrictions_paused`, `capabilities_json`, `call_state_json`, `time_state_json`, `lock_state_json`, `update_fence_json`, `notification_cancels_json`, `backup_service_enabled`, `relock_json`, `unseen_boots_count`/`_last_at_ms`, `foreign_access_json`, `relock_blocks_json` (design 24b)
- `security_events` / `banned_ips` - admin login audit trail and lockout tracking
- `tracked_apps` - one row per app in the global catalog (including the launcher itself, `is_launcher`), GitHub-sourced or manually-uploaded - see the "Apps tab"/"global catalog" bullets above
- `device_tracked_apps` - per-device opt-in join table (device_id, tracked_app_id) scoping which catalog apps actually get pushed to which device - see the "global catalog" bullet above
- `device_pending_uninstalls` - packages queued for silent on-device uninstall, self-cleared once a status report confirms the package is gone - see the "unchecking Apps to install" bullet above
- `dns_filter_settings` (singleton), `dns_blocklists`, `dns_custom_domains` - the DNS/Filters feature's config; global, not per-device, see the DNS/Filters gotcha above
- `contacts` (global address book, unique normalised `phone_number`, `photo_hash`), `device_contacts` (device_id, contact_id, `allow_inbound`/`allow_outbound`/`show_on_home`, `message_app`/`message_address`, `sort_order`), `call_settings` (singleton, `default_country_code`) - see "Calls & SMS" above; `device_policy.calls_managed`/`calls_enabled`/`sms_enabled`/`default_message_app` are the per-device switches; the hardening switches are `device_policy` columns too (see "Hardening")
- `wallpapers` (built-ins + uploads, `image_hash`, `lock_screen`), `device_wallpapers` (which a phone may use) - see "Wallpapers" above
- `device_lock_glance_apps` (the apps whose messages the PIN lock's first page shows, each with its `show_text` since 0083) - see "The lock's glance page"; `device_policy.lock_glance_text` is unused since 0083's backfill
- ~~`device_push`~~ - FCM tokens and health (migration `0026`), dropped by `0048` (design 19)
- `device_crashes` - launcher crash reports per phone and stack-trace hash, see "Crash reports"
- `parent_events` (kept 90 days; design 38's `phone_off`/`phone_on` too), `notification_settings` (singleton; design 38's `push_phone_off`/`push_phone_on` since 0080), `push_subscriptions` (`last_opened_at` since 0082), `push_deliveries` (empty until design 35 step 2), `devices.battery_low_since`, `device_status.battery_level`/`battery_charging` - see "Notifications to the parent"
- `device_pin_events` - the phones' parent-PIN events (kept 90 days), see "Parent PIN"; `device_policy.override_pin_easy` (the easy-PIN warning), `device_status.battery_optimization_exempt` (design 19b)
- `music_categories`, `music_entries`, `music_files`, `device_music_entries`, `music_storytel` - the Vibb music library, see "Vibb music library"; `music_spotify_app`, `music_spotify_accounts` - Spotify's app and logins, see "Vibb music: Spotify on the server"
- `music_settings`, `music_check_requests`, `music_listings`, `music_items` - the music sweep, see "Vibb music: the server sweep" (and "Vibb music: episode art" for their `art_*`/`cover_*print` columns)
- `device_locations` - append-only location history per device (kept `device_policy.location_retention_days`, default 7), `device_commands` - the Find My Device remote-command queue (and design 24b's `end_windows`/`allow_blocked`/`start_pause`) (`requested_at`/`delivered_at` (the last delivery)/`deliveries`/`received_at`/`acknowledged_at`/`result`/`succeeded`/`cancelled_at`/`cancel_reason`/`payload` - `allow_blocked`'s list, `install_update`'s release tag), see the Find My Device architecture bullet above

## Device-facing API (plain JSON, no envelope)

Every route below is declared in `src/device_routes.rs` (`device_routes()`, design 22 S0), never in `main.rs`
(`tests::listeners::main_rs_has_no_device_route_literal`): both listeners build from it.

- `POST /api/devices/enroll` - `{enrollment_code}` → `{device_id, device_token}`
- `GET /api/devices/policy` (bearer) → allowlist, `time_policy` (effective rules + budget, global or the device's own, and active lifts) and `location_policy` (always), the frozen legacy schedule fields, `kiosk_desired`, `lock_task_features`, `override_pin_hash`/`override_pin_salt`, `pending_command`, `call_policy` (always, explicit `managed`; contacts carry `photo`), `hardening` (always, every switch explicit), `launcher_ui` (always; `lock_wake_on_notification` always in it since launcher round 7 §3, pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`), `block_activity_start` (always; never inside `lock_task_features`), `kid_lock` (always, null = no lock), `screen_timeout_seconds`, `update_fence`, `notification_auto_cancel` (always), `music` (always the key; `null` when unreadable)
- `POST /api/devices/command-result` (bearer) - `{command_id, success, message, requested_at?}` → 204 (implies the receipt; a wipe only when it was refused)
- `POST /api/devices/command-receipt` (bearer) - `{command_id, requested_at?}` → 204: the phone got the command and is about to run it (security review L12; the first receipt counts). `pending_command` is `{id, command, requested_at}`: an id alone isn't unique across a restored backup or another server, so the phone keys "ran it already" on both and sends `requested_at` back; an answer that names another `requested_at` counts for nothing (QA of the launcher wave, Medium 1)
- `POST /api/devices/status` (bearer) - `{lock_reason, kiosk_engaged, installed_apps, app_version, app_version_code, offline_override_used, location, policy_state, restrictions_paused, capabilities, call_state, notification_listener_enabled, time_state, install_mode, play_window_active, play_store_suspendable, lock_state, screen_timeout_seconds, update_fence, notification_cancels, backup_service_enabled, battery_optimization_exempt, relock, unseen_boots, battery}` (installed apps may carry `installer`) → 204
- `GET /api/devices/contact-photos/{hash}` (bearer) - a contact photo, only for this device's contacts
- `GET /api/devices/wallpapers/{hash}` (bearer) - a wallpaper image, only when ticked for this device
- `POST /api/devices/crashes` (bearer) - launcher crash reports (hash + short trace), see "Crash reports" -> 204
- `POST /api/devices/dns-events` (bearer) - blocked domains, stored only while the phone's log is on (default off) -> 204
- `POST /api/devices/pin-events` (bearer) - `{events: [{id, kind, entry, at_ms, until_ms, failures, level}]}`, the phone's parent-PIN uses, wrong tries and lockouts (design 24, see "Parent PIN") -> 204; 400 only for a body that isn't JSON or has no `events` array, 413 above 100 events
- `POST /api/devices/power` (bearer) - `{off?: {at_ms, boot_count, battery?, reboot?, reason?}, on?: {at_ms, boot_count, battery?}}`, the phone's shutdown and start (design 38, see "The phone turned off and on") -> 204; 400 for neither part or not one, 413 above 2 KiB
- `GET /api/devices/apps` / `GET /api/devices/apps/{id}/download` (bearer) - update check/download, both scoped to apps selected for this specific device (plus the launcher's own self-update, always included) - see the "global catalog" bullet above; the download is resumable (`Range`/`If-Match`, `X-Release-Tag`; "App downloads" below)
- `GET /api/devices/music/library` (ETag/304, gzip), `/music/entries/{id}/items` (ETag/304, gzip; design 21b), `/music/covers/{hash}`, `/music/files/{id}` (Range, `X-Content-SHA256`), `/music/storytel` (bearer) - the phone's music library, scoped to its ticks (design 21)

## Where the rest is

Area notes, moved verbatim from this file on 2026-10-19; read only the ones your task touches.

- `docs/notes/server/architecture.md` - Architecture
- `docs/notes/server/calls-and-contacts.md` - Calls & SMS; Emulator fixes; Calls & Contacts
- `docs/notes/server/hardening-and-pins.md` - Hardening; Handy's own PIN lock; Parent PIN; Windows that lock again, unseen boots; The lock's glance page
- `docs/notes/server/time-rules.md` - Time rules, screen time, location policy; Time Rules and Device › Time
- `docs/notes/server/launcher-ui.md` - Launcher UI, contact photos, airplane mode; Wallpapers; App names and icons; The launcher is "Vibb One"
- `docs/notes/server/play-and-updates.md` - FCM removed, SSE only; Play; Kiosk escapes; Catalog downloads; App downloads: Wi-Fi only, resumable; Install Now (design 37)
- `docs/notes/server/dns.md` - A custom DNS server for the Web Filter
- `docs/notes/server/music.md` - Vibb music library; Vibb music: the server sweep; Vibb music: Storytel on the server; Vibb music: Spotify on the server; Vibb music: episode art
- `docs/notes/server/notifications.md` - Phone off and on (design 38); Notifications to the parent, step 1: events and the inbox; Notifications to the parent, step 2: Web Push
- `docs/notes/server/connectivity.md` - Connectivity and exposure
- `docs/notes/server/pwa.md` - Fix round 2026-10-06; Fix round 1; The PWA's times, connection state and Attention; About This Phone and Advanced; Lock Screen & PINs; Home Screen and Web Filter; Device › Apps; Device › Music; The phone's summary; The Apps tab; The Music tab; The Locate tab; The rest of Settings; Add Phone, sign-in, the tab pages
- `docs/notes/server/retention-and-crashes.md` - Retention; Crash reports; Removed monitoring
- `docs/notes/server/status.md` - Architecture change in progress; Current status
