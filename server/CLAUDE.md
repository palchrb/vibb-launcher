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
launcher's catalog row has `include_prereleases` **off** (release candidates are prereleases). Some bullets below
describe upstream's rolling `pre-release` debug builds; that's history, not how this fork ships.

## Tech stack

Deliberately mirrors `board-game-tracker` (another self-hosted Pi app by the same developer) since it already solved the boring-but-essential infrastructure for this exact hardware target:

- **Language:** Rust, **Web framework:** Axum, **Database:** SQLite via `sqlx` (WAL mode)
- **Templating:** Askama (compile-time-checked server-rendered HTML, no JS build step, no frontend framework) - templates are compiled into the binary, so editing one requires a rebuild, not just a file save. A plain `cargo run` dev server (no `cargo watch`) needs to be manually killed and restarted after any template/static change.
- **Sessions/auth:** `tower-sessions` + `tower-sessions-sqlx-store`, `argon2` for the admin password, mandatory TOTP 2FA
- **UI:** the parent PWA is **Vibb Phone** (design 31, `docs/design/31-pwa-redesign.md`; the 2FA issuer stays "Kids Device MDM"), mobile-first bottom tab bar (Devices / Apps / Locate / Music / Settings, `templates/partials/app_header.html`: each page sets `{% let tab = "..." %}` before including it and the server marks that tab `aria-current="page"`, no script; Time Rules, Web Filter (`/dns`) and Blocked Activity are rows of the Settings hub, `handlers::settings_hub`, whose values come only from the database - never the network), iOS-style grouped-list CSS (`static/style.css`: tokens for light and dark, rows, the switch as a checkbox plus a sibling `<span class="knob">`, ⓘ, badges, Attention levels, flash, confirm; browser floor Chrome/WebView 80 and iOS Safari 14, no `:has()` or nesting), installable PWA (`static/manifest.webmanifest`, minimal install-only service worker at `templates/sw.js` - no offline page caching, deliberately, since this app handles session cookies and device bearer tokens), light/dark theming via `prefers-color-scheme` CSS variables (no manual toggle)
- **Hosting target:** Raspberry Pi Zero 2 W (or similar aarch64 board), `aarch64-unknown-linux-musl` cross-compiled via GitHub Actions on every `server-v*` tag (root `.github/workflows/server-release.yml`, uses `cross`) - `deploy/install.sh` downloads the newest `server-v*` release and sets up a systemd service

## About the developer

No prior programming experience - build features directly rather than explaining Rust concepts, but loop them in on real architecture/schema/UX decisions and explain the *why* in plain language.

## Architecture

- **Two completely separate auth systems**: admin sessions (`tower-sessions`, cookie-based, for the parent-facing web UI) and device bearer tokens (`Authorization: Bearer <token>`, for the phone's API calls). A device is never a session; an admin never touches the device API.
- **Enrollment is a one-shot code, not a device-number+URL pair.** The admin generates a short human-typeable code from a device's page (or a long one inside the setup QR); the phone POSTs it once to `/api/devices/enroll` and gets back a bearer token in return. Only the code's hash is stored, and it is cleared the moment it's used - it can never be replayed (design 22, see "Connectivity and exposure").
- **`kiosk_desired` is server-authoritative** - the admin UI sets it, the device applies it automatically on its next sync, no on-device confirmation or local override switch. Fully built and confirmed working live on the physical test phone, using the full `lock_task_features` bitmask - status bar info, home, recents, keyguard, notifications, and the power button menu are all always forced on whenever kiosk mode is (see the gotcha below for why keyguard specifically can't be optional; notifications/power-button were originally separate per-device checkboxes, removed once it was clear there was never a real reason to want either off while kiosk mode is on). **`kiosk_desired` is no longer admin-configurable at all (2026-08-11)** - every device is kiosk mode now, full stop, matching how this project is actually used in practice (there was never a real reason to enroll a device and leave it unrestricted). `create_device` and `update_policy` both hardcode it to `1`/`true` unconditionally; the "Restrict this phone..." checkbox and its explanatory copy are gone from `device_detail.html` entirely, not just defaulted on.
- **Friendlier allowlist**: the device reports its installed apps (`{package_name, label}` pairs) in every status heartbeat; the admin UI renders checkboxes from that real, current list rather than asking a parent to hand-type Android package names. See `device_status.installed_apps_json` and `handlers::devices::view_device`.
- **Schedules became named time rules in handy step 6** - see "Time rules, screen time, location policy" below. History: the original fixed weekday/weekend allowed windows + bedtime (minutes since midnight, `weekday_start_minutes` etc.) lived per device, then (2026-08-11) moved to `/schedules` as a global default (`global_schedule`) plus a per-device opt-in override (`device_policy.custom_schedule_enabled`). Those columns are now frozen: converted into rules once and only still sent for launchers without `time_rules_v1`. The override switch itself is still `custom_schedule_enabled`.
- **The old landing-page Dashboard is gone (2026-08-11)** - it duplicated `/devices` (a device list) with very little unique content beyond an offline-override-used banner and a kiosk-engaged badge, and the parent found it not worth keeping now that Schedules (now Time rules) occupies that nav slot instead. `/` now just redirects to `/devices`. Deliberately not folded into `/devices` before deleting - if the override-used banner ends up missed in practice, it's still visible per-device on `device_detail.html`, just not aggregated across all devices at a glance.
- **WiFi/Bluetooth restriction *levels* (open/restricted/disabled) were removed entirely (2026-08-11)** - `device_policy.wifi_mode`/`bluetooth_mode` are gone (migration `0017_schedules_page.sql`, `DROP COLUMN`), confirmed in practice not worth the UI complexity. The client's `AppEnforcer.kt` still unconditionally *clears* the four `UserManager` restrictions this used to set, every `apply()` cycle, rather than just deleting that code outright - a device that already had "restricted"/"disabled" saved from before this change needs those actively lifted, not abandoned in place. WiFi/Bluetooth on/off toggles on the kid-facing Quick Controls swipe-left screen are a completely separate feature and unaffected.
- **Offline override PIN**: a per-device PIN (PBKDF2-HMAC-SHA256, `security::hash_pin`/`verify_pin`), cached hashed on the device so it verifies with zero network. Entering it correctly on the phone lifts every restriction for a bounded window and self-heals the moment the device can reach the server again. The client's Settings screen is also gated behind this same PIN, plus a separate manually-toggled "pause all restrictions" kill-switch that does *not* auto-clear (unlike the PIN override) - an emergency escape hatch if a policy or client update ever ships something broken.
- **Apps tab is a unified list+detail structure** (`/apps` → `/apps/tracked/{id}`) covering every app the server pushes to devices, including the launcher's own self-update - it's just another `tracked_apps` row (GitHub-sourced, `include_prereleases` on, since its CI only ever publishes to a rolling `pre-release` tag). The old launcher-specific `launcher_releases` table/routes/handlers (`handlers::releases`) are fully retired, not just deprecated - there is exactly one app-update system now, not two.
- **`/apps` is the global catalog, not an install list** (added 2026-08-08, migration `0013_device_tracked_apps.sql`) - adding an app there doesn't push it to any device by itself. `device_tracked_apps` (device_id, tracked_app_id) is the per-device opt-in join table; a device's own page (`/devices/{id}`, "Apps to install" card) is where an admin actually selects which of the catalog's apps get pushed to *that* device - each checkbox is its own auto-submitting mini-form (`POST /devices/{id}/apps/{app_id}/toggle`, `handlers::devices::toggle_tracked_app`), not folded into the big device-policy form; live testing showed an admin checking a box and not separately scrolling down to a Save button, since every other on/off switch in this app already auto-saves. `handlers::device_api::tracked_app_updates` is the enforcement point - it only returns rows the requesting device is enabled=1 AND (has a `device_tracked_apps` row OR `is_launcher`) for, not "every enabled tracked app" like it used to. `tracked_apps.is_launcher` marks the one row that's the launcher/MDM agent itself - it's never given a `device_tracked_apps` row and is unconditionally included for every device instead, since there's no real-world case for a kid's phone not running the app that enforces every other restriction on it. Enforced server-side too, not just hidden in the UI: `handlers::tracked_apps::delete_tracked_app` refuses to delete an `is_launcher` row, and `device_detail.html` renders its checkbox `checked disabled` so it can never reach `toggle_tracked_app` at all. **Not auto-seeded from a package-name guess anymore** - the migration's one-time `WHERE package_name = 'com.kidslauncher.mdm'` missed the real row on a live install (every build shipped so far is the debug variant, `com.kidslauncher.mdm.debug`), so there's now a direct admin toggle instead (`POST /apps/tracked/{id}/is-launcher`, `tracked_app_detail.html`) - don't rely on a package-name heuristic for this again.
- **`tracked_apps.package_name` is optional now** (same day) - typing a real Android package name was never actually required for a silent install to work (`PackageInstaller` determines the real package from the APK's own signed manifest at commit time; the string passed to `Session.openWrite()` is just an arbitrary session label). It's kept only for the admin's own reference in the UI, and as the join key `toggle_tracked_app` uses to auto-add an app to the device's kiosk allowlist (see below) - skipped when blank, since there's nothing to allowlist by. The client-side pieces that used to key off it (install-state tracking, cache filenames, self-update detection) were switched to `TrackedAppUpdate.id`/`is_launcher` instead - see kids-launcher-mdm's CLAUDE.md.
- **Selecting an app for a device also allowlists it, automatically** (`toggle_tracked_app`'s `add_to_allowlist`) - checking "install" without also checking the app in "Allowed apps" left it pushed-but-invisible under kiosk mode until an admin came back later and checked a second box, reported live as real friction. Only fires when the tracked app has a package name on file. This surfaced a real correctness gap in `update_policy`: its allowlist reconstruction only ever considered checkboxes actually rendered on the page (i.e. apps the device has already reported installed - see `view_device`'s `apps`), so a package auto-added ahead of the device installing and reporting back could get silently dropped by any unrelated policy save (schedule, WiFi mode, whatever) in the meantime. **First fix attempt was wrong**: unconditionally preserving any currently-allowed package that wasn't a rendered checkbox this time also preserved a package that *had* been installed and allowed, then got uninstalled - its checkbox just stops rendering once uninstalled, so it could never be unchecked again either, and would silently regain kiosk access if ever reinstalled without re-approval. Narrowed to only preserve a package name that belongs to a tracked app still selected for this device (`device_tracked_apps`) and not yet reported installed - genuinely pending, not stale. Everything else keeps the original self-cleaning behavior: an allowed package that's no longer installed quietly falls out of the allowlist on the device's next unrelated policy save.
- **Unchecking "Apps to install" now also silently uninstalls the app and removes it from the allowlist**, at the admin's explicit request - `toggle_tracked_app`'s uncheck branch is now symmetric with the check branch (`remove_from_allowlist` mirrors `add_to_allowlist`), and if the device's last status report shows the package currently installed, queues an uninstall via a new `device_pending_uninstalls` table (migration `0014_device_pending_uninstalls.sql`), surfaced to the device as `PolicyResponse.packages_to_uninstall`. Checking the app again deletes that row (the form's package and the catalog app's own), so untick-then-tick before the phone syncs no longer uninstalls it (found on the emulator, 2026-10-12). Deliberately its own table, not a `device_commands` row - that queue has "last action wins" single-slot replace semantics (`handlers::locate::queue_command`) that would let an unrelated ring/lock queued afterward silently clobber a pending uninstall before the device ever saw it. Self-cleaning: `handlers::device_api::status` deletes an entry once a status report no longer lists that package as installed - no separate "uninstall result" round trip needed. See kids-launcher-mdm's CLAUDE.md for the client-side silent-uninstall call.
- **A real live bug, found via `adb logcat`, not guessed**: `performMdmSync` has three independent trigger points (SSE push-nudge, periodic timer, the Settings "Sync now" button) with no coordination between them. Two overlapping calls processing the same pending tracked-app update - almost always the launcher's own self-update, since it's unconditionally in every device's batch whenever a new build's published - raced on the shared per-app cache file: one call's cleanup (delete on install failure) could delete a file a second, still-in-flight call had just written, or corrupt it mid-write. Produced exactly `INSTALL_PARSE_FAILED_NO_CERTIFICATES` and `FileNotFoundException` in the wild, matching a user report of "installing two apps at once breaks the sync, even hitting sync again doesn't help." Fixed client-side (`MdmSyncWorker`'s `syncMutex`) - see kids-launcher-mdm's CLAUDE.md.
- **`tracked_apps.rs`/`tracked_app_detail.html` support editing a catalog app after creation** (name, package name, and for GitHub-sourced apps, the repo/asset filter) - there was previously no way to fix a typo short of delete-and-recreate, which loses cached release history. `github_repo` also normalizes whatever's pasted in (`normalize_github_repo`) - a full URL with or without protocol/`www.`, pointing at the repo root or its `/releases` page, with or without a trailing slash or `.git` - down to plain `owner/repo`, applied on both create and edit. A strict "must already be `owner/repo`" parser rejected anything copied straight out of a browser address bar.
- **"Apps to install" flags a catalog app that has no synced release yet** (`TrackedAppCheckbox.has_release`, `view_device`) - checking one used to silently do nothing forever if its `github_repo` was wrong, its first sync hadn't run yet, or (for a manual-upload app) nobody had uploaded a build yet, since `device_api::tracked_app_updates` only ever advertises rows with `latest_release_tag IS NOT NULL`. Still checkable (an admin might legitimately want to pre-select it for whenever a release does land), just labeled so the wait isn't unexplained.
- **The launcher's own self-update is always processed last in a device's per-cycle app-update batch** (client-side, `checkForTrackedAppUpdates` in kids-launcher-mdm) - installing an update over the running app can get the process killed the moment that install commits, and everything queued after it in the same loop (any other app, the trailing DNS-event report) would never run that cycle if the self-update went first. Reported live as "installing two apps at once breaks the sync" - almost guaranteed to include the launcher, since it's unconditionally in every device's update batch whenever a new build's been published. See kids-launcher-mdm's CLAUDE.md.
- **`tracked_apps.source_type`** is `"github"` (poll a repo's Releases hourly, or on-demand via "Check now") or `"manual"` (admin uploads an APK + types a release label directly, via `/apps/tracked/{id}/upload` - no polling, no repo). Both feed the exact same `latest_release_tag`/`latest_release_file_path` columns and the exact same device-facing API (`handlers::device_api::tracked_app_updates`), so nothing downstream needs to know or care which source produced a given cached release.
- **A GitHub-tracked app's release identity is compared as `(tag_name, asset_id)`, not tag alone** - `fetch_latest_release` hits the Releases *list* endpoint (`/repos/{repo}/releases`), not `/releases/latest` (which only ever returns non-prerelease, non-draft releases - would never find anything for a repo, like this project's own kids-launcher-mdm, that only publishes to a rolling prerelease tag; same problem Obtainium solves the same way). A rolling tag's `tag_name` never changes between pushes, but GitHub gives the replaced asset a new numeric `id` every time, so `latest_release_asset_id` is what actually detects a new build for those repos. The composite `"{tag}@{asset_id}"` string is what's sent to devices as `release_tag` (see `device_api::tracked_app_updates`) - the client's own "is this different from what I last installed" check is plain string equality, so it needs the asset id folded in to work correctly for rolling tags.
- **`/devices/provision`** (`handlers::provisioning`; this fork takes the component/URL/checksum from `ForkConfig`, see "This fork" above) generates a QR code for Android's standard 6-tap zero-touch Device Owner provisioning flow, as an adb-free alternative to the existing enrollment-code system - not per-device, not persisted, just a form that renders a QR code from a mostly-constant payload (admin component + signature checksum barely ever change; the download URL is `kids-launcher-mdm`'s rolling `pre-release` GitHub Release tag, so the QR stays valid across every new build). Does **not** work on GrapheneOS - its setup wizard has no `ManagedProvisioning` trigger built in at all yet (confirmed by searching its source directly; tracked at github.com/GrapheneOS/platform_packages_apps_SetupWizard2/pull/40, open/unmerged) - works on stock Android and most custom ROMs today. The signature checksum (`SIGNATURE_CHECKSUM` constant) is the signing certificate's SHA-256 digest, base64url-encoded with no padding - **not** the same as the whole-APK checksum (different source data, different Android extra key); recompute via `apksigner verify --print-certs` (gives the digest as hex) if the signing key is ever rotated. After provisioning, the device still goes through the normal per-device enrollment-code flow in Settings, same as an adb-provisioned device - this only replaces the "get the app installed and made device owner" step.
- **DNS/Filters (`/dns`, `src/dns_engine.rs`, `handlers::dns_filter`) is a Pi-hole-style DNS content filter built directly into this binary**, on `hickory-server`/`hickory-resolver` (MIT/Apache-2.0) - not on any AGPL codebase (crab-hole was researched as a design reference only, never depended on or copied from; see the module doc comment on `dns_engine.rs` for the full reasoning). Global/singleton, not per-device. Blocking is `hickory_server::store::blocklist::BlocklistZoneHandler` (a ready-made part of the library itself, not custom code) chained ahead of `store::forwarder::ForwardZoneHandler` in a `Catalog` for the root zone; `Catalog` itself implements hickory's `RequestHandler` and does the "check blocklist, else forward upstream" chaining natively (`LookupControlFlow::Break` on a hit) - our own `Handler` just wraps it to track query/blocked-query stats. Settings/blocklist/custom-domain changes call `dns_engine::rebuild`, which reconstructs the whole `Catalog` from the DB and hot-swaps it into a shared `Arc<RwLock<..>>` - no process restart, no DNS blip, ever.
- **Two independent ways a device's queries actually reach the DNS filter engine, added in that order and kept for defense-in-depth, not because either alone turned out to be sufficient**: (1) **plain port 53** - requires the device to be using this Pi as its Tailscale exit node *and* the `iptables` PREROUTING DNAT rule (port 53 on `tailscale0` → the engine's own unprivileged port 5300) to be in place, requested through the same flag-file/root-owned-watcher mechanism `system_maintenance.rs` uses for reboot/Tailscale-update/etc (`action_dns_filter_enable`/`disable` in `deploy/install.sh`). Confirmed live to have a lot of independent failure modes: exit-node routing not actually engaging, Android's system Private DNS or a browser's own DNS-over-HTTPS bypassing port 53 entirely, and Tailscale's own tailnet-wide "Override DNS servers" (MagicDNS) intercepting queries before they ever reach this Pi at all. (2) **DNS-over-TLS on port 8853** (redirected from 853 by the same iptables mechanism, `action_dns_filter_enable`) - the client force-locks Android's system Private DNS to this Pi's own tailnet hostname via `DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost` (see the client's own CLAUDE.md, `AppEnforcer.applyPrivateDnsLock`), which only requires the device to be a normal tailnet peer, not exit-node routing - the more reliable path in practice. `PolicyResponse.force_private_dns_to_pi` mirrors the same global `dns_filter_settings.enabled` flag that drives the iptables actions. The DoT listener (`hickory-server`'s `register_tls_listener_with_tls_config`, feature `tls-ring`) loads its cert/key from `data/tls/{cert,key}.pem` at startup only - if they don't exist yet (before `tailscale cert` has ever run), it simply doesn't start, and the plain port-53 path keeps working regardless. Certificate provisioning is via Tailscale's own built-in ACME support (`tailscale cert <magicdns-hostname>` - needs "HTTPS Certificates" turned on for the tailnet in the Tailscale admin console first, a one-time manual step this project can't automate) - `action_tls_cert_renew` in `deploy/install.sh` runs it, both as part of `action_dns_filter_enable` (best-effort, non-fatal - `request_privileged_action` only writes one flag file at a time, so this can't be requested as a second separate action without racing the first) and on its own daily systemd timer (`kid-phone-server-tls-cert.timer`) so the cert stays renewed even after the feature's initially been turned on. **This bumped the watcher schema to 3** - see the `REQUIRED_WATCHER_SCHEMA` gotcha below; do not repeat the mistake of forgetting to bump the app-side constant to match.
- **Find My Device (`/devices/locate`, `handlers::locate`) is locate/ring/lock/wipe/stop_ring for a lost or missing device**, referencing [fmd-foss/fmd-android](https://gitlab.com/fmd-foss/fmd-android) (GPLv3) for the feature set only - not its actual protocol, which doesn't fit this project (SMS/IM delivery, its own crypto/server stack). Built on the existing sync loop instead: `device_commands` is a small queue table (`ring`/`stop_ring`/`lock`/`wipe`/`locate`; `locate` is the locate page's "Update location now"), and `PolicyResponse.pending_command` surfaces the oldest undelivered row for a device - `handlers::device_api::build_policy` pops it (one `UPDATE ... RETURNING`) only after every other read for the response has succeeded, so a 500 never consumes a command and two concurrent polls never get the same one. A launcher with capability `command_receipt_v1` confirms each command before running it (`POST /api/devices/command-receipt` -> `received_at`; security review L12, migration 0065), and the pop offers a command it didn't confirm again after 2 minutes, at most 3 deliveries (`deliveries`) and only within 15 minutes of its first delivery (`first_delivered_at`, `REDELIVER_WINDOW_MINUTES` - nothing stale replays, QA of the launcher wave High 1; 0065 marks every row delivered before it as used up), nudging the phone over its stream 2 minutes after each delivery while it stays unconfirmed (`spawn_redelivery_nudge`; the phone runs an id once); once a command goes out, every older unconfirmed one of that phone is never offered again (QA Low 1: a lost pause must not run after a later "lock again"); an enrollment into the row cancels its undelivered commands (`cancel_reason` 'enrolled', kept as history) and uses up the unconfirmed ones (`end_pending_commands`, QA Low 2 - a wipe for a lost phone never reaches its replacement), and the `device_enrolled` event says how many; **expiry and cancel** (the user's decision, 2026-10-12, migration 0068): `start_pause`, `ring`, `stop_ring` and `locate` (`models::EXPIRING_COMMANDS`) are never handed out - first or again - once their `requested_at` is an hour old (`COMMAND_EXPIRY_MINUTES`; the page says "Expired ... never sent", the Status card's 24b answer "expired"), while `wipe`, `lock`, `end_windows` and `allow_blocked` wait however long it takes; the expiry is read against the clock, so an enrollment cancels every undelivered row, expired ones too (the page still says "Expired" for one that was), and a server start cancels every waiting expiring command (`device_api::end_expiring_at_start`, `cancel_reason` 'restart': the Pi has no RTC and may start with its clock behind - QA of cmd-expiry, Low 4; a clock that jumps backwards while the server runs is not guarded); a command the phone hasn't fetched has a "Cancel" next to its request time on the Locate page (`POST /devices/{id}/commands/{command_id}/cancel`, `locate::cancel_command`: one `UPDATE ... WHERE delivered_at IS NULL AND cancelled_at IS NULL` that races the pop cleanly - `?cancel=done|too_late` -, a `device_command_cancelled` security event), and the pop skips `cancelled_at`; for older launchers delivery stays at-most-once: a command in a response the launcher then rejects is lost, so every command must be safe to queue again (`handlers::locate::queue_command` replaces only a waiting command of the same kind - ring/stop_ring, locate - as cancelled `replaced`, logged `device_command_replaced`; a wipe or lock is never replaced, the parent cancels it with Cancel - QA of cmd-expiry, Medium 1). `device_locations` is a plain history table (not just a latest-point column set), attached to every regular `StatusReportRequest.location` from the device - not just after an explicit `locate` command - so the map's trail stays fresh on its own; pruned by `retention::prune` (per-phone `location_retention_days`, default 7, the newest fix always kept - see "Retention"). The admin map is vendored Leaflet (`static/leaflet/`, BSD-2-Clause, downloaded once from unpkg and committed - no CDN dependency, no build step) against public OpenStreetMap tiles, fed by a plain `/devices/{id}/locations.json` endpoint. The "Play and kiosk" card warns "Restart the phone once: ..." while the launcher reports device-admin policies Android hasn't granted yet (`admin_policies_pending` -> `device_status.admin_policies_pending_json`, known tags only, `models::admin_policy_notice`; a policy an update added may count only after a restart). The Locate page warns "Remote wipe works after the phone restarts once" while the phone's last report has `remote_wipe_ready` false (Android 14 after the launcher update that declared the wipe policy; `device_status.remote_wipe_ready`, migration 0065, QA of the launcher wave Low 4). Wipe is gated by typing the device's exact name to confirm, same pattern as backup restore/delete in `backups.html` - a wipe that works never reports a result (the device is gone), but an up-to-date launcher confirms receipt right before it wipes, and reports a refused wipe (`succeeded` = 0, "Failed: ..."). Ring also has a server-side "Stop ringing" button (queues `stop_ring`) since the on-device UI is otherwise the only way to silence it. "Delivered" means fetched, not done (security review L12): `DeviceCommand::status_line(now, receipts)` (every time in it, and the Requested column, labelled "UTC" - QA of cmd-expiry Low 5) shows a result as "Done"/"Failed", a receipt as "Received ..., not finished yet" (after 10 minutes "never said it finished"; a wipe "the phone is wiping now"), and a fetched, unconfirmed command - for a phone that confirms - as "not confirmed yet", then "offered again", then after the last delivery "never confirmed - ... send it again"; for an older launcher "never confirmed" after 10 minutes, and its wipe as one that can't confirm. Queuing a new command of the same kind (`queue_command`) also stops an unconfirmed one from being offered again (its row stays).
- **Command delivery is push, not just poll**: `AppState.command_notify` is a `tokio::sync::broadcast::Sender<i64>` (device_id) that both `handlers::locate::queue_command` (ring/lock/wipe/stop_ring) and `handlers::devices::update_policy` (every regular policy save - allowlist, schedule, WiFi/BT mode, quick controls, PIN, etc.) fire on. `GET /api/devices/commands/stream` (bearer, `handlers::device_api::commands_stream`) is a Server-Sent Events endpoint (`axum::response::sse`, core axum, no extra feature flag) that filters the broadcast stream to one device and holds the connection open, backed by `KeepAlive` (every `SSE_KEEPALIVE_SECS`, default 240 since design 19 - 120 in step 7, 15 before; its default payload is a bare SSE comment line, not a `data:` event, so it never fires the client's `onEvent` callback). Since design 19 it is the only way this server nudges a phone (FCM removed), and `AppState.command_streams` (`src/streams.rs`, in memory) counts each device's open streams for the device page's "Instant changes" line. The SSE payload itself carries no data - it's purely a "wake up and poll" nudge; the client always re-fetches `/api/devices/policy` on any event and reuses the exact same dispatch path as a normal 2-minute cycle, so there's no duplicated command-handling logic between push and poll. This cut ring/lock/stop_ring delivery from up to 2 minutes down to near-instant, at the cost of the client needing to run a permanent foreground service to hold the SSE connection open (see the client's own CLAUDE.md).

## Fail closed

- **A status report is stored within limits (security review M7).** `device_api::cap_installed_apps`: real package
  names only (`is_package_name`), no duplicates, labels clipped to 100 chars, an installer that isn't a package name
  dropped, at most `MAX_INSTALLED_APPS` (1500); `lock_reason` 32 chars, `app_version` 64; every JSON blob already
  goes through its own `sanitize_*`. `/security` pages back 200 events at a time (`?before=<id>`), so a flood can't
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
  the app with a known package (`toggle_app`) allows it as before. "Forget the package and key" on the app's page clears both. `TrackedAppUpdate` carries
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
- **The launcher reports what it did with the policy** (`StatusReportRequest.policy_state`, migration `0021`): `"ok"`, `"cache_corrupt"`, `"rejected_suspect"`, `"fresh_decode_failed"` or `"server_error"` (this server answered 5xx) - anything but `"ok"` (or `NULL` from an older launcher) shows a warning on the device page. `restrictions_paused` reports the launcher's PIN-gated, time-limited "pause all restrictions" switch, also shown as a warning.
- **Allowlist writes never guess**: `devices::add_to_allowlist`/`remove_from_allowlist` read-modify-write in one transaction (`edit_allowlist`) and return an error on a DB error, a missing policy row or a stored allowlist that isn't valid JSON - `toggle_app` then answers 500 and writes nothing. The first-heartbeat bootstrap in `device_api::status` is one `UPDATE ... WHERE allowlist_json IS NULL`, so a read error can't make it overwrite a parent's list.
- **`create_device` inserts the device and its policy row in one transaction** and returns 500 if either fails, so a device without a policy row can't be created through the UI.

## Calls & SMS (handy step 2, design `docs/design/02-calls.md`, at the monorepo root)

- **`/devices/{id}/calls`** (`handlers::calls`, linked from a "Calls & SMS" card on the device page): per-device switches `calls_managed`, `calls_enabled`, `sms_enabled` and `default_message_app` (one auto-submitting form, so a missing checkbox means off), the device's contacts with In/Out/Home flags and a Message button choice (`device_contacts.message_app`: none/sms/element/signal, NULL = device default; `message_address` = Matrix ID for Element), an add form, and the global default country code (`POST /settings/calls`, `call_settings` singleton). Every write runs in a transaction, answers 500 on a DB error, nudges the device (`command_notify`) and redirects back. Flag/remove routes are scoped `WHERE device_id = ? AND contact_id = ?` and 404 otherwise; remove deletes an address-book entry no device has any more (so does deleting a device).
- **`contacts` is a global address book** keyed by the normalised number (`src/phone.rs`): adding a number another device already has reuses the row and renames it for every device (the page says so). Changing the default country code does not renormalise stored numbers.
- **Number normalisation is a shared contract with the launcher**: `src/phone.rs` and the launcher's `calls/PhoneNumbers.kt` run the same `testdata/phone_vectors.json` (the launcher keeps a byte-identical copy; each test suite compares the two when the sibling repo is checked out next to it). ASCII digits only; `*`/`#`/`,`/`;` rejected (MMI/USSD never allowlisted); 3-6 digits = short number, matched exactly; no suffix matching. Change both sides and both copies together.
- **`PolicyResponse.call_policy` is always sent, with an explicit `managed`** (QA blocker 3, `device_api::build_call_policy`): unmanaged devices get `{"managed": false, ...defaults, "contacts": []}`. The launcher rejects a whole response without the key once its calls were managed, so a rolled-back server can't silently unmanage calls - never make this key optional. Contacts are ordered by `sort_order, name`; `message_app` is resolved against the device default server-side.
- **Status reports carry `capabilities` (`["call_policy_v1"]`) and `call_state`** (the launcher's `CallState` object: role holders, restrictions, fail-closed state, last error, last emergency call, callback window), stored capped as JSON in `device_status.capabilities_json`/`call_state_json`. `calls::call_warnings` turns them into warnings on the calls page and the device page: launcher can't enforce calls (or stopped reporting it - an older launcher), dialer/call-redirection role missing, whether incoming calls are still filtered without the dialer role (`screening_role_held`, security review L7: the call-screening role's holder screens them; neither = "not filtered - anyone can call"), the call-screening role missing while the launcher is the phone app - a plain hint, not an error (`CallReport.hints`, a subtitle line: the launcher hangs up calls that aren't allowed, but another app could screen first - the phone can't read who holds the role -, so grant it with adb; absent from older launchers = nothing; no "accept the prompt": Android refuses exclusive-role prompts under the launcher's DISALLOW_CONFIG_DEFAULT_APPS), fail-closed, a reported error, an unreadable call log (`call_log_readable: false`), the Messages app allowlisted while SMS is off, and for 7 days an emergency call / an open callback window.
- Emergency numbers are never configured here: the launcher always allows them. The override PIN and "pause" don't open calls beyond the call rules (decision after QA review); since step 6 they do lift a time rule's call block (school), back to the normal call rules.

## Hardening (handy step 4, design `docs/design/04-hardening.md`, at the monorepo root)

- **`device_policy` has nine switches** (migrations `0023`/`0024`, `models::Hardening`, flattened into `DevicePolicy`): `disallow_factory_reset`, `disallow_add_user`, `disallow_modify_accounts`, `disallow_config_vpn`, `disallow_usb_file_transfer`, `disallow_debugging_features`, `disallow_safe_boot`, `lock_location`, `disallow_airplane_mode`. All default on except `disallow_safe_boot` and `disallow_airplane_mode` (`Hardening::default()` mirrors the column defaults). `PolicyResponse.hardening` always carries all of them explicitly; the launcher sets the matching Android user restrictions while the phone is managed and its override/pause never lift them.
- **Device page "Phone hardening" card**: one auto-submitting form to `POST /devices/{id}/hardening` (`devices::update_hardening`; a missing checkbox is off, 404 for an unknown device, 500 and no nudge on a DB error). The card explains that blocking USB debugging (the default) removes adb as the repair path, that safe mode repairs nothing, and the real recovery paths. A managed device (allowlist or managed calls) without an override PIN gets a warning (`managed_without_pin`). Deploy order: this server first (migration 0023 defaults existing rows to "debugging blocked"), set the switches and a PIN, then the launcher. Switch debugging off before enrolling a phone that still needs adb work (e.g. granting the call-redirection and call-screening roles).
- **A corrupt stored allowlist is a warning on the device page** (`allowlist_corrupt`), not silently "nothing allowed" (QA step 1 #11).
- **Backup to Google** (Google account on the phone, runbook `docs/setup/google-account.md` at the monorepo root; migration `0041_backup_service.sql`): status `backup_service_enabled` -> `device_status.backup_service_enabled` (missing from an older launcher, or `null` = NULL). The "Phone hardening" card shows the latest report: `false` one quiet line "Backup to Google: off", `true` a warning, NULL nothing. No switch: the launcher always keeps Android's backup service off while managed (override and pause included). Tests: `src/tests/hardening.rs` `backup_service_*`.

## Launcher UI, contact photos, airplane mode (handy step 5, design `docs/design/05-ui-photos-i18n.md`, at the monorepo root)

- **Contact photos** (`src/photos.rs`, migration `0024`): upload per contact on the calls page (`POST /devices/{id}/contacts/{cid}/photo`, multipart field `photo`, route body limit ~10 MB; remove: `.../photo/remove`), scoped to the device (404 otherwise) but stored on the global `contacts` row (`photo_hash`), so every device with the contact gets it and is nudged. Never stored as sent: decoded with the pure-Rust `image` crate (already in the tree via `totp-rs`'s QR feature; we enable `jpeg`/`webp`; format sniffed from the bytes, limits 8000 px / 256 MiB), EXIF orientation applied, centre-cropped square, shrunk to ≤ 512 px, re-encoded JPEG q85 - which drops all metadata (EXIF/GPS). Decoding is capped at 40 MP / 8000 px a side / 128 MiB, only the ≤ 512 px crop is copied and rotated, and `photos::process_limited` runs one upload at a time on a blocking thread. Store + `photo_hash` commit and `prune`/`recover_missing` share one file lock (`photos::lock_files`), so a prune can't delete a just-stored photo (QA step 5 #2/#3). Files: `AppState.photo_dir` (`data/contact_photos/<sha256>.jpg`, a temp dir in tests). `photos::prune` deletes unreferenced files after replace/remove/contact remove/device delete. Backups include the photos (`contact_photos/<hash>.jpg` in the zip); the root restore script still swaps only the DB, and at startup `photos::recover_missing` extracts missing referenced photos from `data/backups/*.zip` (hash checked) and sets `photo_hash = NULL` for any it can't find. Admin thumbnails: `/contact-photos/{hash}` (session).
- **Device API**: `call_policy.contacts[].photo` (hash or null); `GET /api/devices/contact-photos/{hash}` (bearer) serves it only when a contact on *this* device has that hash (invalid hash, other device's contact, missing file: 404).
- **`launcher_ui`** (always sent): `{language: "system"|"nb"|"en", home_columns: 3|4, wallpapers: [...]}` (wallpapers since step 8) from `device_policy.launcher_language`/`home_columns`, set on the device page's "Launcher" card (`POST /devices/{id}/launcher`, 400 on unknown values). The launcher applies the language with the per-app locale API. Server text stays English (PWA i18n is a later step).
- **Hardening**: ninth switch `disallow_airplane_mode`, **default off** (the family travels).
- **Status** `notification_listener_enabled` (`device_status` column): `false` shows a warning on the Launcher card with the adb command (no device-owner API grants notification-listener access).
- The device page's latest status breaks `reported_at` ties by `id` (two reports in one second).

## Time rules, screen time, location policy (handy step 6, design `docs/design/06-time-rules.md`, at the monorepo root)

- **Rules** (`src/time_rules.rs`, migration `0025`, table `time_rules`): name, kind (`school`/`bedtime`/`custom`), `calls_allowed`, `exempt_apps_json` (package names usable during the rule), `days_json` = 7 entries Monday first, `{start,end}` minutes 0-1439 or null; `start > end` ends the next day, `start == end` lasts 24 h (the phone's local time). `device_id` NULL = global; a device with `device_policy.custom_schedule_enabled` uses its own rules **and** its own `daily_budget_json` instead of the global ones (`global_schedule.daily_budget_json`), 7 entries, minutes or null = unlimited. The phone evaluates everything (DST, overnight, lock screen); the server only stores, validates and serves.
- **Legacy conversion**: `time_rules::migrate_legacy`, called from `connect_db` right after the SQL migrations, converts each `global_schedule`/`device_policy` row with `rules_migrated = 0` once (one transaction per row): bedtime -> a bedtime rule on all 7 days; the allowed weekday/weekend windows -> one custom rule "Outside allowed hours" `[end_D, start_D+1)` when every day has a non-wrapping window and no locked span exceeds 24 h, else a "(morning)" and an "(evening)" rule. Calls stay allowed (step 4 behaviour). Unit tests compare the old launcher logic and the new semantics minute by minute over a week. The legacy columns stay and are still sent, frozen, so a launcher without `time_rules_v1` keeps the pre-migration schedule (the device page warns).
- **Wire**: `PolicyResponse.time_policy = {rules, daily_budget_minutes, lifts}` and `location_policy = {mode, interval_minutes}`, always sent (the launcher rejects a response without `time_policy` once it has had one). A stored rule/budget that doesn't validate (bad JSON, not 7 entries, minute out of range, unknown kind, bad package name) is a 500 (`PolicyError::CorruptTimePolicy`), never fewer rules. Status reports carry `time_state` (active rule, `used_minutes`, `budget_minutes`, `extra_minutes`, `calls_blocked`, `lock_reason` NONE/BEDTIME/SCHOOL/RULE/SCREEN_TIME, `lifts_active`), stored capped (object, ≤ 4096) in `device_status.time_state_json`, and capability `time_rules_v1`.
- **Admin UI**: `/schedules` ("Time rules", `handlers::schedules`): global rules (add/edit/delete, `POST /schedules/rules`, `/schedules/rules/{id}`, `/schedules/rules/{id}/delete`, every form carries `scope` = "" or a device id and a rule outside that scope is a 404), global budget (`POST /schedules/global`), and per device the override switch + budget (`POST /schedules/device/{id}`) and its own rules. Exempt apps are checkboxes from the devices' latest reported installed apps (plus already chosen ones). The kind select pre-sets the calls checkbox (school: off). 400 on invalid input (nothing written), 500 on DB errors; a global change nudges every device, a device change that device.
- **Lifts** (`handlers::lifts`, table `time_lifts`): device page "Time rules" card - lift one rule or all rules for 15/30/60/120 min (`POST /devices/{id}/lifts`, `target=rule`, `rule=all|id`, the rule must be one of the device's effective rules), or `+15/30/60` min screen time (`target=budget`). Rule lifts are delivered until `created + minutes`, budget lifts for 12 h (the phone adds them once, to the day it first sees them). "End now" (`POST /devices/{id}/lifts/{lift_id}/end`) stops delivering an active rule lift (`ended_early_at`); added screen time can't be taken back. Both write a `security_events` row (`time_lift`, `time_lift_ended`) and nudge. The card also shows the phone's last `time_state` and the recent lifts.
- **Location policy** (`device_policy.location_mode` off/on_request/interval, default `on_request`; `location_interval_minutes` 10/15/30/60/120/240, default 30): set on the locate page (`POST /devices/{id}/location-policy`, mode alone keeps the interval). "Update location now" (`POST /devices/{id}/command/locate`) queues `locate` (a fresh fix past the phone's throttle) and the page then polls `locations.json` for up to a minute for a newer fix; the page shows the last fix's age and accuracy.
- **Fix round after `qa-step6-code.md`**: the device card and `/schedules?device=` warn when a calls-off rule applies while calls are unmanaged (incoming calls still ring); rule-lift status comes from the phone's `time_state.lifts_active` ("Active on the phone" / "Ended on the phone" / "Waiting for the phone", `devices::rule_lift_status`), the server expiry is secondary; `time_state.ledger_unreadable` shows "today's budget counts as used up"; the locate page shows the phone's answer to the newest `locate` (`device_commands.result`, also `GET /devices/{id}/locate-result.json` for the page's wait) and says "off" switches location off on the phone; rule create/update/delete, budget saves and location-policy changes are in the security log (`time_rule_*`, `screen_time_saved`, `location_policy_changed`). The override PIN text says it lifts the time rules incl. school's call block, never calls beyond the call rules, never hardening (user decision on QA item 4). Tests: unauthenticated POSTs to every new admin route write nothing.

## FCM removed, SSE only (design 19, `docs/design/19-remove-fcm.md`, at the monorepo root; server 0.20.0)

- **Gone**: `src/fcm.rs`, `src/push.rs` (sender, dispatcher, health, acks, test nudges), `AppState.fcm`, policy `push`,
  the status `push` (an old launcher still sends it: accepted with 204, stored nowhere - no `deny_unknown_fields`;
  `fcm_push_v1` in `capabilities` is stored as reported and read by nothing), the card's FCM lines, the direct deps
  `base64`/`ring`, the dev-dep `tokio/test-util` and the fixture PEM. No vestigial `push` key: every shipped launcher
  treats a missing key like `fcm_enabled: false` (SSE). `FCM_SERVICE_ACCOUNT_FILE` still set = one startup warning.
- **Migration `0048_remove_fcm.sql`**: `DROP TABLE device_push` (the installation IDs were credentials to message a
  phone), `device_status.push_state_json` set to NULL (column kept, unused). Revoking the service-account key is what
  makes stored IDs (freed pages, backups) useless (DEPLOY.md, "Removing FCM"). 0.19 refuses a database with 0048:
  rollback = the `update.sh` backup.
- **Keepalive**: `SSE_KEEPALIVE_SECS` default 240 (= `MAX_SSE_KEEPALIVE_SECS`; shipped launchers read with 300 s),
  invalid values fall back to 240; `.env` `SSE_KEEPALIVE_SECS=120` reverts. A proxy in front needs a read timeout
  >= 300 s (`tailscale serve` streams unbuffered).
- **"Instant changes" line** (Q1, `src/streams.rs`): `commands_stream` takes a `StreamGuard` into the response stream;
  `CommandStreams` keeps per device the open count and when it last changed (opened, or the last one closed), in
  memory only - no DB, no contract change. The "Play and kiosk" card says "connected since ... as far as this server
  can tell" / "not connected since ..." / "not connected since this server started" (a silently dead peer counts
  until this server's TCP gives up).
- Tests: `src/tests/play.rs` (old launcher's `push` dropped, migration 0048, the stream count on the device page, plus
  step 7's Play/DNS/keepalive tests), `streams::tests`; `policy_json_keys_snapshot` has no `push`. `TestApp::new`
  installs the rustls provider like `main` (the FCM tests used to, for the DNS upstream test).

## Play (handy step 7, design `docs/design/07-battery-fcm-play.md`, at the monorepo root; its FCM half removed in 19)

- **Play** (`src/play.rs`): `PLAY_CORE` (vending, gms, gsf) is the launcher's business - never listed in the device page's Apps card, `toggle_app` answers 400 for it, and the first-heartbeat allowlist bootstrap leaves it out. One source per package: selecting a catalog app whose package the phone reports installed by `com.android.vending` is a 409 (nothing written). Security log: `play_install_mode` (a new `install_mode.until_ms`), `app_installed` (a package new since the previous report, with its installer; not on the first report). The device page's "Play and kiosk" card ("Push and Play" until design 19) shows the "Instant changes" line, install mode, the nightly update window and the kiosk app block switch.
- **Battery (design 19b phase 1, migration `0055_battery_exemption.sql`)**: status `battery_optimization_exempt` (`Option<bool>`, `#[serde(default)]`) is stored in `device_status.battery_optimization_exempt` (NULL from an older launcher or when unreadable); `play_card` shows a plain line after "Instant changes" for true/false (`devices::battery_line`), nothing for NULL. No policy key, no capability.
- **DNS**: `dns_blocklist` never delivers the exact FCM hosts (for other apps' FCM - Element X - since design 19) (mtalk*, alt*-mtalk, fcm/fcmtoken/fcmregistrations/firebaseinstallations/android.googleapis.com, android.apis.google.com, android.clients.google.com) as blocked (`play::is_fcm_protected`, QA 07 #10). Parents (`google.com`, `googleapis.com`) **are** delivered as set - dropping them opened all of `*.google.com` (fix round #1); the launcher exempts the exact FCM hosts before its suffix walk. Every DNS write (upstream, blocklist create/toggle/delete, custom domain, device override) nudges the affected devices; the hourly blocklist refresh and new catalog releases don't - phones get those with the 30-min backstop.
- **The DNS blocklist fails closed, per feed (security review M6).** `compile_blocklist`: a feed whose download fails
  (an error, a non-2xx, over `MAX_FEED_BYTES` = 32 MB decompressed, or nothing that `parse_feed` takes for a domain
  - a captive portal's page) keeps its previous domains (`CompiledList.last_error`): in memory, else its last good
  copy in `data/dns_feeds/<id>.txt` (`save_feed` after each changed download, `source_hash`; a deleted feed's copy
  goes), so a restart without internet keeps them. One that never loaded on this server stays `loaded: false`, empty,
  and is left out - no phone got it from here (QA of the security wave, Low 8: a 503 for it froze every phone's list,
  custom blocks included). A DB error keeps the whole compiled list, and `device_api::dns_blocklist` answers 500 -
  never a shorter list; the launcher keeps its own list on any non-2xx and asks again at the next sync. `parse_feed`
  takes `0.0.0.0`/`127.0.0.1` hosts lines, bare domains and adblock's `||domain^`. The DNS page shows each feed's
  state; the refresh retries a failing feed after 5 minutes instead of an hour.
- **Fix round (qa-step7-code.md)** (the FCM parts gone with design 19): migration `0027`: `device_push.rejected_token_hash` - a token FCM rejected is ignored when the phone re-reports it until it renews (no re-store, test nudge or security event per report); a unique partial index on `fcm_token` + `record_report` clears a newly stored token from every other row (re-enrolled phone); health ticks and `Lagged` fan-out only use rows of devices with a bearer token. `SSE_KEEPALIVE_SECS` is capped at 240 (`MAX_SSE_KEEPALIVE_SECS`, 60 s under the launcher's 300 s read timeout); other values fall back to the default with a log line (240 since design 19, 120 before).

## Wallpapers (handy step 8, design `docs/design/08-ui-polish.md`, at the monorepo root)

- **Data** (migration `0028`): `wallpapers` (`kind` color/gradient/image, `colors` JSON hex list, `image_hash`, `label`, `sort`, `builtin_key` for the six built-ins navy/forest/plum/green/sky/sunset - navy is id 1 -, `lock_screen` opt-in per photo) and `device_wallpapers` (cascades on device and wallpaper delete). Every device gets the built-ins (existing rows in the migration, new ones by the `devices_get_builtin_wallpapers` trigger); **uploads start on no device** until ticked on the device page's "Wallpapers" card (`POST /devices/{id}/wallpapers`, repeated `wallpaper=<id>`, an unknown id is a 400 and nothing changes).
- **Uploads** (`/wallpapers` page under Settings, `handlers::wallpapers`): `photos::process(bytes, Shape::Portrait)` - same sniffing and limits as contact photos, the centred crop is computed on the **upright** size (EXIF 5-8 swap width/height) so it commutes with the orientation applied afterwards; cut to 9:20, shrunk to ≤ 1080×2400, never enlarged, JPEG q85 (q75/q65 if over 3 MB, else 400). Contact photos use `Shape::Square` (unchanged result).
- **Storage**: `photos::Store` - `CONTACT_PHOTOS` (`data/contact_photos`) and `WALLPAPERS` (`data/wallpapers`, `AppState.wallpaper_dir`) each with its own lock, prune (only its own directory - never share a dir: prune deletes every file the table doesn't reference), backup zip prefix (`wallpapers/<hash>.jpg`) and `recover_missing` at startup (a wallpaper whose image is in no backup is deleted). Deleting an upload (`POST /wallpapers/{id}/delete`; built-ins: 400) cascades, prunes and nudges the devices that had it.
- **The lock's background** (design 25, "Lock background", migration `0069`): `device_lock_wallpaper` (one row per
  device at most, composite key onto `device_wallpapers`, so unticking or deleting it falls back by itself) -> the policy's
  `launcher_ui.lock_wallpaper` (id or null = the lock shows Home's wallpaper, always sent). `POST /devices/{id}/lock-wallpaper`
  (`wallpaper` = "" or a ticked id, else 400; security event `lock_wallpaper_changed`; back to `/devices/{id}`). The tick save
  deletes only the unticked `device_wallpapers` rows (a delete-all would clear the lock choice by the cascade). Tests:
  `the_lock_wallpaper_is_homes_unless_a_ticked_one_is_chosen`.
- **Fix round (qa-08-code.md #8)**: a failing wallpaper query is logged and the policy goes out with `wallpapers: []` (never a 500 for time rules/calls); `Store::add_to_zip` skips a file that vanished between `read_dir` and the read (a prune during a backup).
- **Wire**: `launcher_ui.wallpapers` - always a list, every key always present (`id, kind, colors, image, label, builtin_key, lock_screen`), in `sort` order; a row that doesn't validate is left out with a warning. `GET /api/devices/wallpapers/{hash}` (bearer) only for a wallpaper ticked for *this* device (else 404, like contact photos). Tests: `src/tests/wallpapers.rs`.

## Emulator fixes (handy step 9 part B, design `docs/design/09-sms-and-fixes.md`, at the monorepo root)

Part A (SMS allowlist) is **postponed (user, 2026-10-05)**: `sms_enabled` stays the on/off switch.
- **Migration `0029_step9_fixes.sql`**: `device_policy.roles_changed_at` (stamped by `calls::save_settings` only when `calls_managed` changes), `device_policy.block_activity_start` (default 1), `device_status.play_store_suspendable`.
- **Pending rule** (B1, QA 09 #5, pure `calls::role_report`): a latest report older than `roles_changed_at` shows "Waiting for the phone to confirm (last report HH:MM UTC)" instead of the role/call-log/fail-closed warnings **only while the change is < 5 minutes old** (the calls page reloads every 5 s for 2 minutes); later the real warnings plus "hasn't confirmed the change made at HH:MM"; a report newer than the change is always taken as is (a disagreeing one is a warning, never "waiting"). `call_report` returns warnings + `auto_refresh`; `call_warnings` stays for the device page.
- **Kiosk app block** (B4): `PolicyResponse.block_activity_start` (always sent) - the launcher ORs `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` in itself together with the system helpers it pins; `lock_task_features` is always sent with bit 64 masked out, so a launcher without the helpers never gets it. Switch on the device page's "Play and kiosk" card ("Push and Play" until design 19; `POST /devices/{id}/kiosk-block`, missing checkbox = off, 404 unknown device, nudges). The card warns when the phone reports `play_store_suspendable: false` (Play is then blocked only in kiosk, or not at all with the block off).
- Fix round (qa-09-code.md): the calls page never auto-refreshes an error render (it would lose the typed contact); `valid_matrix_id` follows the Matrix grammar (localpart `a-z0-9._=-/+`, server host/IPv4/`[IPv6]` + optional port) like the launcher's `isMatrixId`, which percent-encodes the id into Element X's URIs. A launcher on a server without this key treats the kiosk app block as off.
- Tests: `src/tests/step9.rs`, `calls::tests::role_report_pending_expires`; `policy_json_keys_snapshot` has `block_activity_start`.

## Handy's own PIN lock (handy step 10, design `docs/design/10-lock-and-call-ui.md`, at the monorepo root)

- **Migration `0030_kid_lock.sql`**: `device_policy.kid_pin_hash/kid_pin_salt/kid_pin_length` (NULL hash = no lock), `device_status.lock_state_json`.
- **Kid PIN** (`src/kid_lock.rs`, pure + unit tests): 4-6 ASCII digits, hashed with `security::hash_pin` like the parent (override) PIN. Set/remove on the device page's "Screen lock" card (`POST /devices/{id}/kid-lock`, `new_kid_pin` / `clear_kid_pin`; 404 unknown device, 500 nothing written on a DB error; security events `kid_pin_changed`/`kid_pin_cleared`; nudges). Refused (nothing written, `?notice=<code>` flash on the device page, `kid_lock::flash_text`) when not 4-6 digits, **without an override PIN** (it is the lock screen's "Parent code") or when it verifies against the override hash (`security::verify_pin`). The other way round in `update_policy`: a new override PIN that verifies against the kid hash is refused, and the override PIN can't be removed while a kid PIN is set. PBKDF2 for these checks runs in `spawn_blocking`; the kid-PIN `UPDATE` only applies `WHERE override_pin_hash IS NOT NULL` (0 rows = needs_override) and `update_policy`'s SQL keeps the override while a kid PIN is set, so parallel saves can't leave a kid PIN without its parent code. The **first** kid PIN also sets `disallow_safe_boot` (safe mode skips the launcher's lock); later changes leave the switch as the parent set it.
- **Wire**: `PolicyResponse.kid_lock = {pin_hash, pin_salt, pin_length} | null`, always sent (`policy_json_keys_snapshot`); a half-written row (length outside 4-6, a NULL column) is sent as null - a privacy lock fails open. The kid hash appears only there (never in a status, log or page). Status `lock_state` is stored **re-serialized through `kid_lock::LockState`** (`sanitize_lock_state`: only `active, inactive, locked, failures, backoff_until_ms, exempt_yields`, `inactive` capped at 64 chars), so no unlock times or PIN material can reach the DB. Capability `pin_lock_v1`.
- **Device page**: the card shows privacy text (no Android code any more: forensic reading possible, bank/ID apps, no fingerprint, safe mode, don't reuse the PIN, ~100 tries a day), the phone's state, and warnings: launcher without `pin_lock_v1`, safe boot allowed, and every `inactive` but `no_pin` (`android_credential` with the removal runbook, `crash_guard`, `bad_hash`, `keyguard_not_disabled`, `unmanaged`). `call_state.in_call_ui_failed_at` (the in-call screen couldn't come up over the lock) is a call warning for 7 days. The locate page explains what "Lock" does with and without a kid PIN (result "locked (handy lock)" / "locked (Android)").
- Tests: `src/tests/step10.rs`, `kid_lock::tests`, `security::tests::pin_hash_shared_vector` (same vector as the launcher's `PinHashTest`).

## Fix round 2026-10-06 (emulator run, `docs/testing/2026-10-06-emulator-run.md`)

- **No POST may make the page jump to the top - always return to the edited card.** Every form
  posts and redirects back; `static/scroll-restore.js` (loaded, not deferred, by
  `templates/partials/head.html`, which every page template includes) remembers the scroll
  position when any form leaves the page (submit event, and a wrapped
  `HTMLFormElement.prototype.submit` for the auto-saving controls - marked `data-autosave` and
  saved by `static/autosave.js` since design 31, which on a `<select>` changed from the keyboard
  waits for Enter or blur) per path in sessionStorage for 20 s, and restores it on the page that comes back; a
  redirect with a `#fragment` (e.g. `#screen-lock` with a notice) keeps its anchor instead. A new
  page template must include the head partial (`pages_restore_scroll_after_auto_save` checks
  every template, subfolders too, and runs `node --test jstest/*.test.js`, the script's behaviour tests, when node is
  installed - CI always runs them); a new form needs nothing else as long as it redirects back to
  its own page. External file, so a future CSP needs no inline-script exception.
- **Screen timeout** (migration `0032`): `device_policy.screen_timeout_seconds` (one of
  `models::SCREEN_TIMEOUTS` = 15/30/60/120/300/600, default 60; anything else is sent as 60),
  "Screen timeout" card on the device page (`POST /devices/{id}/screen-timeout`, 400 for other
  values), `PolicyResponse.screen_timeout_seconds` always sent (`policy_json_keys_snapshot`). The
  launcher applies it as device owner (`setSystemSetting(SCREEN_OFF_TIMEOUT)`) and reports the
  value it reads back as `screen_timeout_seconds` (`device_status`, kept only if 1 s..1 day),
  shown on the card. Handy's PIN lock locks at screen-off, so this is the auto-lock delay.
- **Vibb night wallpaper** (migration `0033`): built-in `vibb_night` (`#0C0C14`, sort 5, so first
  and the default on phones added from now on; no fixed id). Existing phones don't get it
  automatically - their list and shown wallpaper stay as they were.
- **Provisioning language** (migration `0034`): `provisioning_settings.locale` ("xx_YY", default
  `nb_NO`) and `.time_zone` (IANA, default `Europe/Oslo`) go into the setup QR as
  `android.app.extra.PROVISIONING_LOCALE`/`PROVISIONING_TIME_ZONE` (AOSP `DevicePolicyManager`:
  device-owner provisioning, "can also be used for QR code provisioning"); empty or malformed
  ones are left out (`provisioning::valid_locale`/`valid_time_zone`), the settings form refuses
  malformed values (400, nothing written). Hardening switch `disallow_config_locale`
  (`device_policy`, default 1) -> `DISALLOW_CONFIG_LOCALE` on the phone.
- **FCM by installation ID** (migration `0035`, qa-fixround-2026-10-06 #2; removed with FCM in design 19, the
  table is dropped by 0048): the launcher registers
  by Firebase installation ID (firebase-messaging 25.1+) and reports `push.fcm_token_kind`
  ("fid"/"token", stored as `device_push.fcm_token_kind`). `fcm::TargetKind::of` (report when it
  agrees with the value, else the shape: an FID is 22 chars of base64url, a token has ':') picks
  `message.fid` or `message.token` - `token` is deprecated and takes FIDs only during Firebase's
  migration period. `FcmSender::send` takes a `fcm::Target {id, kind}`; the fake records kinds.

## Kiosk escapes (handy step 11, design `docs/design/11-kiosk-escapes.md`, at the monorepo root)

- **Migration `0036_kiosk_escapes.sql`**: `device_policy.update_fence` and `notification_auto_cancel` (both **default 0**: the fence stays off until the A3 device check passes on the Jelly Star, the notification filter until B5), `device_status.update_fence_json`, `notification_cancels_json`.
- **Migration `0045_boot_cover.sql`** (design 16b): `device_policy.boot_cover`, **default 0** - the launcher's boot cover, a third checkbox on the same card ("Test it on this phone first"); `PolicyResponse.boot_cover` always sent, missing = off on the launcher. The card's form returns to `#kiosk-escapes`. **Migration `0046_boot_cover_state.sql`**: `device_status.boot_cover_json` - the phone's `boot_cover` report (`kiosk_escapes::BootCoverState`, known fields only, hand-over "cover"/"launcher"), capability `boot_cover_v1`; the card warns about an older launcher, a tripped crash guard (switch off and on to retry) or an arm not shown at the next start.
- **Wire**: `PolicyResponse.update_fence` and `notification_auto_cancel`, always sent (`policy_json_keys_snapshot`); a launcher on an older server treats both as off. Device page card "Launcher updates and notifications" (`POST /devices/{id}/kiosk-escapes`, one auto-saving form: a missing checkbox is off, 404 unknown device, nudges) with the privacy text (the filter uses the launcher's "App badges" notification listener, reads only app, channel and type, reports only names and counts).
- **Status** (`src/kiosk_escapes.rs`, pure + unit tests): `update_fence` (`enabled, state, unsuspendable, last_release, home_role_held, pending_tag, pending_since_ms, waiting_for, last_fenced_at_ms, last_released_at_ms`) and `notification_cancels` (`active, entries[{package_name, channel, cancelled, snoozed}], dropped`) are stored **re-serialized through the known fields** like `lock_state`: lists <= 20 entries (extra counted in `dropped`), ids cut to 64 chars, counts never negative - never notification text. The card (`escapes_card`) explains a fence that is up, packages Android refused to pause, a partial fence (no ROLE_HOME), the last release (a failed install is a warning), a downloaded update waiting for the night window (and why), missing notification access, and what the filter removed. Capability `kiosk_escapes_v1` (a reporting launcher without it gets "update the launcher").
- **Fix round (qa-11-code.md #5, #8)**: `update_fence` also keeps `last_fenced_at_ms`/`last_released_at_ms` (the phone's last-fence summary, since the live record never reaches a report); a fence reported more than 15 min ago reads "started at ... and the phone hasn't reported since", never "right now"; `last_release` "orphan" (a lost record swept on the phone) and "commit_failed" are warnings like "install_failed". Phone-supplied times are clamped to 1970..2100 and counts to 0..1e6 when sanitizing, and the card uses saturating arithmetic (tested with `i64::MIN`/`MAX`).
- Tests: `src/tests/step11.rs`, `kiosk_escapes::tests`.

## Tests

`cargo test` runs in-process tests in `src/tests/` (and in CI): `build_admin_router` (split out of `main()` for this; `TestApp::device_router()` for the phone listener) is driven directly with `tower::ServiceExt::oneshot`, against a fresh migrated SQLite file in a temp dir per test - no network, no background tasks. `TestApp` (`src/tests/mod.rs`) has helpers to create and enroll a device and send JSON requests with or without a bearer token; handlers extracting `ConnectInfo` get a fixed loopback address. A test that documents a known bug is marked `#[ignore = "..."]` until the fix lands - run `cargo test -- --ignored` to see them fail. Admin handlers can be called directly with `TestApp::state` (skipping the session/2FA middleware), or through the real middleware: `TestApp::admin_cookie()` seeds an onboarded admin with a fixed TOTP secret, logs in via `/login` + `/auth/verify-2fa` (with a TOTP step not used yet - a code works once) and returns the session cookie for `request_form` (urlencoded POSTs) and `get_page`. Requests come from `LOOPBACK_PEER`; `send_via(router, peer, request)` sends from any peer to the admin router or `device_router()` (the phone listener), `with_env(&[...])` builds the app with other listener settings. `policy_json_keys_snapshot` is the contract with the launcher's `PolicyResponse` DTO - update both sides together.

## Non-obvious gotchas hit so far

- **`security::REQUIRED_WATCHER_SCHEMA` must be bumped in the exact same commit as any `deploy/install.sh` change to `WATCHER_SCHEMA_VERSION`, and it's easy to forget since they're two separate files with no compile-time link between them.** The DNS/Filters commit bumped `install.sh`'s `WATCHER_SCHEMA_VERSION` from `1` to `2` (adding the `dns_filter_enable`/`disable` root actions), but `REQUIRED_WATCHER_SCHEMA` was left at `1` - so `security::watcher_needs_update()` never actually flagged a Pi whose watcher predates that commit as needing a re-run of `install.sh`, even though its watcher script genuinely doesn't recognize `dns_filter_enable` as a valid action (falls through to `install.sh`'s "Unknown or empty update-request action" case, and the iptables redirect silently never gets applied - confirmed live: "Enable DNS filtering" showed 0 queries handled and ads still loaded on a device that otherwise had this Pi correctly configured as its Tailscale exit node). Fixed by bumping `REQUIRED_WATCHER_SCHEMA` to `2` to match (now `3`, bumped again in the same commit as the DNS-over-TLS iptables/cert-renewal actions, precisely to not repeat this; `5` in this fork, where `actions.sh` gets the release repo substituted in). If a future privileged root action is ever silently not taking effect, check this pairing first - specifically whether the Updates page's "watcher needs update" banner should have fired but didn't.
- **axum's built-in `Form` extractor cannot deserialize repeated same-named form fields** (e.g. multiple checked checkboxes named `allowed_packages`) into a `Vec<String>` - errors with `invalid type: string "...", expected a sequence`. Fixed in `handlers::devices::update_policy` by taking raw `axum::body::Bytes` and parsing manually with `form_urlencoded` instead of `Form<T>`. Reuse this pattern for any future repeated-checkbox-group field.
- **Browsers cache `/static/*` assets aggressively with no revalidation** - editing `style.css` and reloading can silently keep serving the old cached copy even after a hard navigate. `partials/head.html` links it with a `?v=N` cache-busting query param (bump `N` when the stylesheet changes) - the same pattern `board-game-tracker` already uses.
- **The dev server is a plain `cargo run`, not `cargo watch`** - it will not pick up template or static-file changes on its own. Kill the running `cargo`/`kid_phone_server` processes and restart after any such edit, or you'll be testing against stale HTML/CSS while genuinely-fresh files sit on disk (this has caused real confusion mid-session more than once).
- **(Since handy step 10 phones have no Android screen lock - the launcher's own PIN lock replaces it, see above - but the keyguard bit below stays forced.)** **`LOCK_TASK_FEATURE_KEYGUARD` is disabled by default in Android's lock-task mode, and that suppression is enforced at the `DevicePolicyManager`/`system_server` level - it keeps applying even before a device is decrypted (Before First Unlock), when the client app's own process can't run at all.** A real device got stuck at boot after GrapheneOS's auto-reboot-after-inactivity feature re-locked storage while this bit was off for that device: no keyguard to enter a PIN into (suppressed by policy) *and* no launcher available either (this app was the exclusive enforced Home app, and its components aren't resolvable pre-unlock) - a total deadlock, recoverable only via hardware-level recovery mode. Fixed by forcing `LOCK_FEATURE_KEYGUARD` on unconditionally in `update_policy`, same as the other always-on features - it's no longer admin-configurable. The tradeoff this accepts: every kiosk-mode device now also requires a PIN to resume from sleep, not just after a reboot, since Android doesn't expose those as separate bits.
- **`rustls` 0.23+ needs an explicit process-wide default `CryptoProvider` installed before *any* TLS connection works, and there's no error at the call site that says so.** Without `rustls::crypto::ring::default_provider().install_default()` early in `main()`, TLS just silently fails - first discovered via the (now-deleted, see Phase E above) DNS filter's DoT upstream forwarding, which failed every query with `NetError(NoConnections)`, no panic, no obvious log line pointing at the real cause, only found by turning on `hickory_resolver=debug` tracing and noticing the connection attempt simply never got anywhere. **Still just as real after Phase E removed that code** - `reqwest`'s own `rustls` feature (used for blocklist downloads and every GitHub Releases API call in `tracked_apps.rs`) depends on this exact same process-wide install; that's the only reason `main.rs` still calls it and `rustls` is still a direct dependency at all (see Cargo.toml's comment on it). Install exactly one provider once, at startup, before anything that might open a TLS connection runs - and don't ever remove this call while `reqwest`'s `rustls` feature is still in use, even if the original DoT code that motivated adding it is long gone.
- **A missing root-certificate-store feature is a second, separate way to get the exact same silent TLS failure** as the gotcha above, even with the crypto provider correctly installed - the TLS client needs either a `webpki-roots`-style bundled root store or a way to reach the OS's own trust store (`rustls-platform-verifier`/`native-roots`), or certificate validation has no trust anchors and every handshake fails post-connect with no distinguishing error. If outbound HTTPS (blocklist downloads, GitHub API calls) ever breaks again, check both of these before assuming it's a network/firewall issue - `Test-NetConnection`/`nc` succeeding against the remote port proves nothing here, since the TCP+TLS handshake itself can still complete while certificate validation silently fails afterward.
- **`tailscale status --json | grep '"ID"'` matches every device in the tailnet, not just the Pi itself** - the JSON always includes both `Self` and every `Peer`, each with their own `"ID"` field, regardless of the `--self` flag (which only affects the plain-text table output, not JSON structure). To get just the Pi's own stable node ID (the value `AppEnforcer.applyVpnRestrictions` on the client pushes to Tailscale as `ExitNodeID`, and what goes in `device_detail.html`'s "Exit node ID" field), use `tailscale status --json | jq -r '.Self.ID'`. **Repeated this exact mistake once already** with `action_tls_cert_renew`'s hostname lookup (`grep -o '"DNSName":"[^"]*"' | head -1 | ...` instead of `jq -r '.Self.DNSName'`) - worse, this one failed completely silently on the real Pi: under `set -euo pipefail`, a bare `var=$(pipeline)` assignment where the pipeline's last stage (`grep`) matches nothing makes the whole script exit immediately, before the script's own `[ -z "$hostname" ]` check (and its helpful error message) ever runs. `jq` also isn't installed by this script by default - `iptables`/`jq` are both explicit `apt-get install` prerequisites now; don't assume a utility is present just because a past interactive session happened to use it over SSH.
- **QR-code zero-touch provisioning (`handlers::provisioning`) failed live on a genuine GMS-certified device (Motorola Moto G Play 2024, Android 14) during the Device-Owner-provisioning step specifically** - not app installation (the identical APK installs fine as a plain sideload on that same device afterward, and the download URL/signature checksum were independently confirmed correct). Not yet confirmed via logs (no adb access reachable before `ManagedProvisioning` resets back to OOBE on failure), but the leading theory is that Google restricts the zero-touch/QR Device-Owner flow specifically to Play-Store-sourced DPCs on GMS-certified hardware - separate from whatever (if anything) gates a plain sideload install. If true, a release-signed (non-debug) build wouldn't fix this specific failure, since it isn't a signing/trust issue. This is in addition to the already-documented GrapheneOS limitation above (no `ManagedProvisioning` trigger at all) - so as of this writing, QR provisioning has no confirmed-working real-device target; manual `adb shell dpm set-device-owner ...` remains the reliable path everywhere.

- **Nothing a phone sends reaches the PWA as HTML or script (security review H2).** A status report's `location` is
  stored only when `models::checked_location` accepts it (RFC 3339 `captured_at`, stored as UTC
  `YYYY-MM-DDTHH:MM:SS.mmmZ`; latitude +-90, longitude +-180, accuracy finite and >= 0; else dropped and logged, the
  rest of the report kept); migration `0054_location_cleanup.sql` removed rows the old intake let in. The locate
  page builds its popup with `textContent` and gets the newest fix's time as `locate::js_string` (JSON with `<>&`
  escaped). Values in a `<script>` go through `js_string`, never `{{ value }}` inside quotes. A fix more than 5
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
**A migration file is frozen from its first commit on a pushed branch, comments included**: sqlx stores a checksum of
the whole file and refuses to start (`VersionMismatch`) when an applied migration's text changed. Later notes go into
this file or the design doc; a dev database that ran an earlier text needs a fresh `data/` (qa-26a-merge27-code L2).

- `admin_users` - parent login accounts, TOTP secret (+ `totp_last_step`), forced-password-change flag, TOTP failure counter and lock (`lock_count`, `locked_until`), `session_epoch`
- `devices` - one row per kid's phone: name, enrollment code hash + kind (+ expiry, cleared on use; `enrollment_code` itself is always NULL since 0052), hashed bearer token, enrolled/last-seen timestamps
- `device_policy` - one row per device: allowlist JSON, `custom_schedule_enabled` (own time rules + `daily_budget_json` instead of the global ones), `location_mode`/`location_interval_minutes`, the frozen pre-step-6 schedule columns, `kiosk_desired` (always `1` now), `lock_task_features` (bitmask), `override_pin_hash`/`override_pin_salt`, `kid_pin_hash`/`kid_pin_salt`/`kid_pin_length` (step 10)
- `global_schedule` - singleton (`id = 1`): the global `daily_budget_json` and the frozen pre-step-6 schedule; `time_rules` (device_id NULL = global) and `time_lifts` - see "Time rules" above
- `device_status` - append-only heartbeat log (pruned after 30 days, the newest report per phone kept): lock reason, kiosk-engaged, installed-app snapshot, app version, timestamp, `offline_override_used`, `policy_state`, `restrictions_paused`, `capabilities_json`, `call_state_json`, `time_state_json`, `lock_state_json`, `update_fence_json`, `notification_cancels_json`, `backup_service_enabled`, `relock_json`, `unseen_boots_count`/`_last_at_ms`, `foreign_access_json`, `relock_blocks_json` (design 24b)
- `security_events` / `banned_ips` - admin login audit trail and lockout tracking
- `tracked_apps` - one row per app in the global catalog (including the launcher itself, `is_launcher`), GitHub-sourced or manually-uploaded - see the "Apps tab"/"global catalog" bullets above
- `device_tracked_apps` - per-device opt-in join table (device_id, tracked_app_id) scoping which catalog apps actually get pushed to which device - see the "global catalog" bullet above
- `device_pending_uninstalls` - packages queued for silent on-device uninstall, self-cleared once a status report confirms the package is gone - see the "unchecking Apps to install" bullet above
- `dns_filter_settings` (singleton), `dns_blocklists`, `dns_custom_domains` - the DNS/Filters feature's config; global, not per-device, see the DNS/Filters gotcha above
- `contacts` (global address book, unique normalised `phone_number`, `photo_hash`), `device_contacts` (device_id, contact_id, `allow_inbound`/`allow_outbound`/`show_on_home`, `message_app`/`message_address`, `sort_order`), `call_settings` (singleton, `default_country_code`) - see "Calls & SMS" above; `device_policy.calls_managed`/`calls_enabled`/`sms_enabled`/`default_message_app` are the per-device switches; the hardening switches are `device_policy` columns too (see "Hardening")
- `wallpapers` (built-ins + uploads, `image_hash`, `lock_screen`), `device_wallpapers` (which a phone may use) - see "Wallpapers" above
- `device_lock_glance_apps` (the apps whose messages the PIN lock's first page shows) and `device_policy.lock_glance_text` - see "The lock's glance page"
- ~~`device_push`~~ - FCM tokens and health (migration `0026`), dropped by `0048` (design 19)
- `device_crashes` - launcher crash reports per phone and stack-trace hash, see "Crash reports"
- `device_pin_events` - the phones' parent-PIN events (kept 90 days), see "Parent PIN"; `device_policy.override_pin_easy` (the easy-PIN warning), `device_status.battery_optimization_exempt` (design 19b)
- `music_categories`, `music_entries`, `music_files`, `device_music_entries`, `music_storytel` - the Vibb music library, see "Vibb music library"
- `music_settings`, `music_check_requests`, `music_listings`, `music_items` - the music sweep, see "Vibb music: the server sweep" (and "Vibb music: episode art" for their `art_*`/`cover_*print` columns)
- `device_locations` - append-only location history per device (kept `device_policy.location_retention_days`, default 7), `device_commands` - the Find My Device remote-command queue (and design 24b's `end_windows`/`allow_blocked`/`start_pause`) (`requested_at`/`delivered_at` (the last delivery)/`deliveries`/`received_at`/`acknowledged_at`/`result`/`succeeded`/`cancelled_at`/`cancel_reason`/`payload` - `allow_blocked`'s list), see the Find My Device architecture bullet above

## Device-facing API (plain JSON, no envelope)

Every route below is declared in `src/device_routes.rs` (`device_routes()`, design 22 S0), never in `main.rs`
(`tests::listeners::main_rs_has_no_device_route_literal`): both listeners build from it.

- `POST /api/devices/enroll` - `{enrollment_code}` → `{device_id, device_token}`
- `GET /api/devices/policy` (bearer) → allowlist, `time_policy` (effective rules + budget, global or the device's own, and active lifts) and `location_policy` (always), the frozen legacy schedule fields, `kiosk_desired`, `lock_task_features`, `override_pin_hash`/`override_pin_salt`, `pending_command`, `call_policy` (always, explicit `managed`; contacts carry `photo`), `hardening` (always, every switch explicit), `launcher_ui` (always), `block_activity_start` (always; never inside `lock_task_features`), `kid_lock` (always, null = no lock), `screen_timeout_seconds`, `update_fence`, `notification_auto_cancel` (always), `music` (always the key; `null` when unreadable)
- `POST /api/devices/command-result` (bearer) - `{command_id, success, message, requested_at?}` → 204 (implies the receipt; a wipe only when it was refused)
- `POST /api/devices/command-receipt` (bearer) - `{command_id, requested_at?}` → 204: the phone got the command and is about to run it (security review L12; the first receipt counts). `pending_command` is `{id, command, requested_at}`: an id alone isn't unique across a restored backup or another server, so the phone keys "ran it already" on both and sends `requested_at` back; an answer that names another `requested_at` counts for nothing (QA of the launcher wave, Medium 1)
- `POST /api/devices/status` (bearer) - `{lock_reason, kiosk_engaged, installed_apps, app_version, app_version_code, offline_override_used, location, policy_state, restrictions_paused, capabilities, call_state, notification_listener_enabled, time_state, install_mode, play_window_active, play_store_suspendable, lock_state, screen_timeout_seconds, update_fence, notification_cancels, backup_service_enabled, battery_optimization_exempt, relock, unseen_boots}` (installed apps may carry `installer`) → 204
- `GET /api/devices/contact-photos/{hash}` (bearer) - a contact photo, only for this device's contacts
- `GET /api/devices/wallpapers/{hash}` (bearer) - a wallpaper image, only when ticked for this device
- `POST /api/devices/crashes` (bearer) - launcher crash reports (hash + short trace), see "Crash reports" -> 204
- `POST /api/devices/dns-events` (bearer) - blocked domains, stored only while the phone's log is on (default off) -> 204
- `POST /api/devices/pin-events` (bearer) - `{events: [{id, kind, entry, at_ms, until_ms, failures, level}]}`, the phone's parent-PIN uses, wrong tries and lockouts (design 24, see "Parent PIN") -> 204; 400 only for a body that isn't JSON or has no `events` array, 413 above 100 events
- `GET /api/devices/apps` / `GET /api/devices/apps/{id}/download` (bearer) - update check/download, both scoped to apps selected for this specific device (plus the launcher's own self-update, always included) - see the "global catalog" bullet above; the download is resumable (`Range`/`If-Match`, `X-Release-Tag`; "App downloads" below)
- `GET /api/devices/music/library` (ETag/304, gzip), `/music/entries/{id}/items` (ETag/304, gzip; design 21b), `/music/covers/{hash}`, `/music/files/{id}` (Range, `X-Content-SHA256`), `/music/storytel` (bearer) - the phone's music library, scoped to its ticks (design 21)

## Architecture change in progress (2026-08-07): on-device DNS filtering + embedded tsnet

**The DoT-to-Pi Private DNS approach (this file's entries below) is being replaced** - it hit a hard Android limitation confirmed live (see `kids-launcher-mdm`'s CLAUDE.md for the full diagnosis: Android's private-DNS strict-mode bootstrap resolution structurally cannot resolve a Tailscale MagicDNS hostname). New direction: DNS filtering moves fully on-device (the launcher's own `VpnService`, replacing this server's live `dns_engine.rs` DNS server), and the standalone Tailscale app on the client gets replaced by an embedded `tsnet` connection - this Pi's own role shrinks to distributing each device's resolved blocklist and ingesting what got blocked, not running live DNS resolution.

**Phase A (server) shipped**: migration `0011_client_side_dns_filtering.sql` adds per-device blocklist feed overrides (`device_blocklist_overrides`) and device-scoped custom domains (`dns_custom_domains.device_id`, nullable = global), plus a blocked-event log (`device_dns_events`, admin page at `/dns/log`). New device-facing endpoints: `GET /api/devices/dns-blocklist` (this device's fully-resolved, category-tagged list) and `POST /api/devices/dns-events` (ingests what got blocked). `PolicyResponse.dns_filter_version` is an opaque per-device token (global compiled-list hash + this device's override/custom-domain state) so the client only re-fetches the (potentially 100k+ domain) full list when it's actually changed, not every 2-minute poll. `dns_engine.rs` gained `CompiledBlocklist`/`compile_blocklist` alongside (not replacing yet) the existing live hickory-server plumbing - deliberately downloads *every* defined blocklist feed regardless of its global `enabled` flag (unlike the legacy `rebuild()`), so a device-level override can turn a globally-off feed on for just that device.

**Phase E (server) shipped 2026-08-08 - this server no longer runs a live DNS server at all.** `dns_engine.rs`'s hickory-server plumbing (`Handler`/`RequestHandler`, `Catalog`/`BlocklistZoneHandler`/`ForwardZoneHandler`, the UDP/TCP/DoT socket binding in `run()`, `SingleCertResolver`/TLS cert loading) is gone - the file now holds only `CompiledBlocklist`/`compile_blocklist`, i.e. exactly what Phase A already added. `install.sh` had the matching root-owned surface removed to match: the `dns_filter_enable`/`disable` iptables DNAT rules (ports 53/853 → the now-deleted listeners), `action_tls_cert_renew` and its daily systemd timer, and the `route_localnet` sysctl block those DNAT rules needed - re-running the installer on an already-provisioned Pi also best-effort cleans up any of those left over from before. `WATCHER_SCHEMA_VERSION`/`security::REQUIRED_WATCHER_SCHEMA` both bumped to `4` together (removing privileged actions counts as a watcher-schema change same as adding them - see the gotcha below). The `hickory-proto`/`hickory-resolver`/`hickory-server`/`rustls-pemfile`/`async-trait` crates are no longer dependencies at all; `rustls` itself stays (reqwest's own `rustls` feature still needs the process-wide `CryptoProvider` `main.rs` installs at startup, unrelated to the deleted DoT listener - see the `rustls` gotcha above, still very much alive, now for a different reason).

`dns_filter_settings.enabled` (the old network-wide "Enable DNS filtering" toggle, no longer meaningful with no live listener to enable) is dropped from the schema along with `device_policy`'s now-fully-dead `require_tailscale`/`tailscale_exit_node_id` columns (migration `0012_vpn_filter_toggle.sql` - see the client's CLAUDE.md for the Phase D work that made those client-side fields dead first). In their place: `device_policy.vpn_filter_enabled` (default on), a genuine per-device admin toggle for the on-device filter's VPN piece (`KidVpnService` on the client) - added at the parent's explicit request after confirming live on the Moto that there was no way to turn filtering off for a specific kid short of the client's own offline-override kill-switch. Surfaced in `device_detail.html`'s former "VPN (Tailscale)" card, now "Ad/content filtering (VPN)"; threaded through `PolicyResponse.vpn_filter_enabled` to `AppEnforcer.applyVpnRestrictions` on the client, which actually starts/stops `KidVpnService` and sets/clears the always-on-VPN designation accordingly (see that repo's CLAUDE.md).

**Confirmed live on both test phones (Pixel 4a 5G, Moto G Play) as of 2026-08-08**: ad-blocking, policy sync, the new `vpn_filter_enabled` toggle (both this admin checkbox and the client's local pause-restrictions switch), and the `/dns/log` blocked-domain log all verified end to end with the live DNS server fully retired - see the client's own CLAUDE.md for the full verification pass, including a real ANR bug the toggle work surfaced and fixed on the client side. Not yet cleaned up: the `com.tailscale.ipn` tracked-app row still silently auto-updates the now-unused standalone Tailscale app on enrolled devices - harmless, minor, low priority.

## Retention (cleanup 2026-10-06, `src/retention.rs`, migration `0039_retention.sql`)

- **Blocked-domain log**: `device_policy.dns_log_enabled`, **default off** (the migration deleted every existing
  entry), sent as `PolicyResponse.dns_log_enabled` (always; the launcher records and reports only while it is on).
  `POST /api/devices/dns-events` stores nothing for a phone with the log off (204, an older launcher still sends).
  Switch on the Blocked activity page (`POST /dns/log/{device_id}`, auto-saving checkbox, missing = off, 404 unknown
  device, nudges, security event `dns_log_changed`); off deletes the phone's log at once. Entries are kept
  `DNS_LOG_RETENTION_DAYS` = 7 (by `received_at`).
- **Location history**: `device_policy.location_retention_days` (1/7/14/30, default 7; was a fixed 30), locate page
  `POST /devices/{id}/location-retention` (400 other values, 404, security event `location_retention_changed`,
  prunes at once). By `received_at` (server time); the newest fix per phone is always kept (Find My Device's "last
  seen").
- **Status history** (`device_status`: screen time in `time_state_json`, call state, app lists): 30 days, the newest
  report per phone kept, and the newest one with `call_policy_v1` (the calls warning "stopped reporting that it
  enforces calls" rests on it, qa-cleanup #7). There is no server-side blocked-call log; the phone deletes blocked calls from its call log
  after 30 days (launcher `calls/BlockedCallLog`).
- `retention::run_pruning` (spawned from `main` only) runs `prune` at startup and hourly - it replaced the old
  `run_dns_event_pruning` (60 days) and `run_location_pruning` (30 days). Connections use `secure_delete`.
- Tests: `src/tests/cleanup.rs`.

## Crash reports (cleanup 2026-10-06, `src/crashes.rs`, migration `0040_device_crashes.sql`)

- `POST /api/devices/crashes` (bearer): `{crashes: [{hash, trace, count, first_at_ms, last_at_ms, app_version_code}]}`, at
  most 10 (else 400), 204 once stored (the launcher clears its copy only then), 500 on a DB error. `crashes::sanitize`
  keeps a report only with a 16-lowercase-hex hash and drops every trace line that isn't an exception class, `    at
  class.method(File:line)`, `Caused by: class` or `    ... N more` (so no free text - the launcher never sends
  messages), cuts the trace to 4000 chars and clamps counts/times. `device_crashes` is one row per phone and hash
  (counts add up), shown on the device page's "Launcher crashes" card (newest 5, trace in a `<details>`), pruned 30
  days after the phone last reported it (`retention::prune`). It replaced upstream's on-phone crash screen.

## Removed monitoring (2026-10-06)

The conversation journal (`device_journal_entries`, media in `data/journal_media`, `/api/devices/journal*`, the
`/devices/{id}/journal` viewer) and browser history (`device_browser_history_entries`, `/api/devices/browser-history`,
`/devices/{id}/browser-history`) are gone: migration `0038_remove_monitoring.sql` drops the tables, startup deletes the
media directory (`retention::remove_journal_media`), and connections run with `secure_delete` on so deleted rows are
overwritten. So is the MollySocket installer (`deploy/install_mollysocket.sh`) and the launcher's UnifiedPush
distributor it relied on (DEPLOY.md has the removal steps for an existing MollySocket). Principle: collect only what's
needed to manage the phone, delete on a schedule, never store notification or message content.

## Catalog downloads (2026-10-06, `handlers/tracked_apps.rs`, migration `0042_tracked_app_asset.sql`, QA `docs/design/qa-catalog.md`)

- **Asset filter**: blank = first `.apk`; a pattern starting with `^` is a regular expression on the asset name
  (`regex` crate, `AssetFilter`, compiled size <= 1 MiB), e.g. `^\d+\.apk$` for Element X's universal APK (named after
  its versionCode); any other pattern is a substring, as before. Whatever the filter, only an `.apk` (any case) is
  picked - an `.aab` passes the ZIP check but can't install. The add and edit forms refuse an invalid or too complex
  regex and any filter over 256 chars (400, nothing written, the entered values kept, the error by the field and that
  field `autofocus`ed). Both forms post to their page's own path (`/apps/tracked/new`, `/apps/tracked/{id}` - there
  is no `/edit` any more), so the 400 page keeps the scroll position.
- **Status card** shows the cached file, "202609040.apk (326.1 MB)" (`tracked_apps.latest_release_asset_name`/`_size`,
  set by a sync or an upload; rows synced earlier show nothing until the next sync).
- **One sync per app** (`AppSyncs`, in memory, `AppState.app_syncs`): `sync_app` takes the app's guard or answers
  "already running"; the row is read under it, so a slower sync can't roll a newer cached release back. "Check now"
  starts the sync in its own task, waits up to 10 s, then redirects back to the app's page, which says a check is
  running or shows how the last one (hourly or manual) failed. The hourly loop skips an app that is syncing.
- **Sync download** is streamed to `<file>.<random>.part` in the app's dir (`AppState.tracked_apps_dir`,
  `data/tracked_apps/<id>/`, a temp dir in tests) and renamed when complete (ZIP header and GitHub's size checked as
  it streams); the temp file goes on any failure, also when the future is dropped (`TempDownload`'s `Drop`); `.part`
  files a crash left are deleted at startup, before the scheduler (`remove_partial_downloads`). Limits: an asset
  GitHub lists over 1 GB is refused before downloading (also a larger Content-Length, or once the stream passes 1 GB),
  60 s without data fails, 30 min overall. The row's UPDATE comes first, then the old file is removed
  (`replace_cached_file`; an UPDATE that matched no row - the app deleted meanwhile - removes the new file). Deleting an
  app removes its whole directory. A manual upload follows the same order and redirects back to the page.
- **Device API** `GET /api/devices/apps/{id}/download`: since design 13 scoped and resumable - see "App downloads"
  below.
- New devices start with Kid Settings (Quick Controls) Wi-Fi, Bluetooth, brightness and (design 18) sound on
  (`devices::DEFAULT_QUICK_CONTROLS`, mask 15, set in `insert_device_with_policy`); existing devices keep theirs.
- **Sound mode** (design 18, `src/sound_mode.rs`, migration `0047_sound_mode.sql`): `QUICK_CONTROL_SOUND` = 8, a box on the
  Quick Controls card (it only shows the kid's Sound / Silent (vibrate) row; the volume keys still work). Status
  `ringer_mode` / `interruption_filter` (`#[serde(default)] Option<String>`, only known values stored, else NULL) ->
  `device_status`, shown in the Status card "at the last sync" (DND only while on). Policy shape unchanged. Tests:
  `sound_mode::tests`, `src/tests/sound_mode.rs`.
- **A catalog app installed on a phone is allowed and the phone nudged** (`device_api::allow_installed_catalog_apps`,
  every status report; it replaced the package-name backfill, which guessed the package from the one new package in a
  report): the nudge (`command_notify`, the SSE stream) matters, the phone otherwise kept the app hidden and suspended
  until its next backstop sync (found on the emulator).
- Tests: `tracked_apps::tests`, `src/tests/tracked_apps.rs`, `a_new_device_starts_with_all_kid_settings_on`.

## App downloads: Wi-Fi only, resumable (design 13, `docs/design/13-app-downloads.md`, migration `0043_app_downloads.sql`)

- **Switch**: `device_policy.app_updates_wifi_only` (default 1, existing phones too) -> `PolicyResponse.app_updates_wifi_only`,
  always sent (`policy_json_keys_snapshot`). Apps card on the device page, `POST /devices/{id}/app-updates` (auto-save,
  back to the page - scroll-restore keeps the place -, nudges the phone). The phone: catalog apps only on an unmetered
  network, its own update after 3 days on any; roaming always waits.
- **Scope**: one query for the list and the download (`device_api::scoped_apps`: enabled, a release, launcher OR
  `device_tracked_apps`) - a deselected or another phone's app is a 404 like an unknown id.
- **Download** is tower-http's `ServeFile` (`try_call` with the original request): `Content-Length`, a strong `ETag`
  (mtime + size), one `Range` -> 206 + `Content-Range`, `If-Match` -> 412, past the end -> 416 `bytes */size`; plus
  `X-Release-Tag` = the device's `tag@asset_id` from the same row as the path (`release_tag_header`: anything outside
  visible ASCII, and `%`, percent-encoded - the launcher encodes its tag the same way and compares).
- **Hash**: `tracked_apps.latest_release_sha256`, computed while a sync streams the file (`stream_to_file` returns
  `Downloaded {size, sha256}`) or from an upload; sent as `TrackedAppUpdate.sha256` (`null` until known). Files cached
  before 0043 are hashed at startup (`backfill_release_hashes`, own task, stored only while the row still names that file).
- **Status**: `app_downloads` (`{wifi_only, network, entries: <= 20 {tracked_app_id, release_tag, state, bytes, total,
  since_ms, any_network_at_ms}}`, a full snapshot) -> `device_status.app_downloads_json` (`src/app_downloads.rs`: known
  fields only, capped). The app rows show it ("Waiting for Wi-Fi since 6 Oct · 120 of 326 MB", "Installed · update
  waiting for Wi-Fi since ...", the launcher's "Installed · 0.32.0 waits for Wi-Fi, any network from 9 Oct") plus a line
  about the phone's network; a fresh progress row (< 10 min) still wins for the live percentage - except over a
  release the phone refused (`refused_*`, security review M10: "Refused - signed with another key than this app's",
  "Installed · update refused - ...", styled as failed), which it reports until the server lists another release.
  Status pills wrap when long (`.app-status`, `style.css?v=7`).
- Tests: `app_downloads::tests`, `src/tests/tracked_apps.rs` (`downloads_resume_with_range_and_if_match`,
  `only_this_devices_apps_download`, `the_list_carries_each_files_hash`, `the_app_update_switch_saves_and_reaches_the_phone`,
  `app_downloads_are_stored_and_shown`, `a_refused_release_says_why_on_the_device_page`).

## App names and icons (design 14, `docs/design/14-app-display.md`, migration `0044_app_display.sql`)

- **Two levels**: a catalog default (`tracked_apps.display_label/display_icon/display_color`, by the row's package
  name; the catalog's admin name is never used) and a per-phone row (`device_app_display`, PK device + package,
  cascades with the device, <= 200 per phone) that replaces the default **as a whole** - also for Play and
  preinstalled apps outside the catalog. A phone row with neither label nor icon = "the app's own" there; it is kept
  only while a default exists. Saving exactly the default, or "own" without one, keeps no row.
- **Wire**: `launcher_ui.app_display` - always a list (`policy_json_keys_snapshot`), `{package_name, label|null,
  icon|null, color}` resolved per phone (`app_display::policy_app_display`): unknown icons left out, unknown colours
  `auto`, entries with nothing left dropped; a failing query sends `[]` and logs (cosmetic, like wallpapers).
- **Forms** (`partials/app_display_form.html`, `AppDisplayForm`): every allowed app with a valid package (not the
  launcher) on the device page's Apps card (`POST /devices/{id}/apps/display`, `action` save/own/catalog, back to
  `#app-<package>`, nudges) and the catalog page (`POST /apps/tracked/{id}/display`, back to `#display`, nudges every
  phone). Validation (`app_display::validate`): label trimmed, 1-20 chars (`chars().count()`), no control chars;
  icon empty or known; colour `auto` or known; package per Android's grammar, never Play core. Refused: 400 with the
  device/catalog page, that form open, the values kept, the error by the field and that field `autofocus`ed (the
  POST path differs, so scroll-restore can't help - the focus keeps the place). `static/app-display.js` previews
  live (the tile in the phone's resolved colour; `auto` previews grey).
- **Icons and colours**: `testdata/app_icons.json` (16 Material Symbols, 6 colours with seed **and** the launcher's
  `tileColor` result), byte-identical to the launcher's test copy; `scripts/material-symbols.sh` (root) fetches the
  SVGs at a pinned commit with SHA-256s and writes `static/app-icons/<key>.svg` + `LICENSE.txt` (Apache-2.0), the
  launcher's vectors and both tables (`src/app_icons.rs`, generated, rustfmt-clean - don't edit). Tests check the
  table against the JSON, a glyph per icon with its "Converted from" line, and white >= 3:1 on every tile.
- Tests: `app_display::tests`, `src/tests/app_display.rs`.

## Vibb music library (design 21 step 1, `docs/design/21-vibb-music-phase1.md`, migration `0049_music.sql`, 0.21.0)

- **Data**: `music_categories` (name <= 20, `icon`/`color` keys of `testdata/music_icons.json` - 12 Material Symbols, 8
  colours, white >= 3:1, `src/music_icons.rs` + `static/music-icons/` generated by `scripts/material-symbols.sh`;
  seeded Musikk/Eventyr/Lydbøker/Podkast, `default_kind` picks a new entry's category by source), `music_entries`
  (nrk/rss/own, storytel since design 26b; `target` unique, NULL for own; `key` = vibb's `state_key`, `own-<id>`;
  `cache` -1/0/1-100, own = -1; <= 200), `music_files` (own uploads, <= 300 per entry, `missing`),
  `device_music_entries` (ticks),
  `device_policy.music_volume_cap_pct/music_storytel` (and since design 30, migration `0073`,
  `music_stream_mobile_data`/`music_download_mobile_data`, which replaced `music_mobile_data` and both took its value,
  and `music_storage_limit_gb` - 1/2/5/10, NULL = no limit, default 2 for every phone), `music_storytel` singleton,
  `device_status.music_state_json`, `tracked_apps.release_tag_prefix`; `device_policy.music_sonos_rooms_json`
  (migration `0071`, design 26a §5.5: NULL = every Sonos room, else a JSON array of speaker uids; its trigger moves
  `music_library_revision`; read fail-closed by `music::sonos_rooms_allowed` - a value that isn't a list of strings is
  no room, logged). `music_state.sonos_rooms` (the speakers the phone knows) is kept by `sanitize_music_state` as
  Sonos-shaped uids (`is_sonos_uid`, `RINCON_[A-Z0-9]{12,20}`) once each, names cut like `cut_title` (40), at most 32;
  a bad row drops only itself.
- **PWA** (`handlers::music`, `templates/music*.html`): `/music` (add by link - one GET, `AppState.music_fetch`,
  canned in tests; own-files entries; categories; Storytel; the music app's catalog row), `/music/entries/{id}`
  (settings, cover, uploads), device page card `#music` (app switch = `toggle_app` with `anchor=music`, auto-saving
  ticks, the `#music-settings` form - since design 30 two switches "Streaming may use mobile data" / "Downloads may use
  mobile data" (a hidden `v=2` marks today's page; a post without it is a page from before the update, and its one
  `mobile_data` checkbox sets both - `handlers::music::mobile_switches`), the "Music storage limit" select (1/2/5/10
  GB/No limit, `storage_limit` = a choice or `none`, else 400; missing = the stored one kept), the note "This phone's
  launcher or music app is older ..." while the switches differ and the phone's app report (`cache_bytes`) has no
  `extra_bytes` (`music::older_phone_note`), the storage line and its over-the-limit warning (`music::storage_line`;
  from the report's `audio_bytes`/`extra_bytes`, which a music app from design 30 step 3a on always sends - without
  them, an older app, today's "Stored on the phone"; `MusicState` keeps and clamps both like `cache_bytes`),
  event `music_phone_settings` naming both switches and the limit -, volume cap Off/90/80/70/60 - NULL = off,
  Storytel switch, the Sonos rooms at `#music-sonos`
  - "All rooms" or one tick per room the phone reported, `POST /devices/{id}/music/sonos-rooms`, event
  `music_sonos_rooms_changed` (detail clipped to 300), refusals back at `#music-sonos` as `music_notice=sonos_*`, "The
  phone hasn't seen any speakers yet" before a report - the phone's `music_state`). Every form
  redirects to its anchor; a 400 re-renders with the values and the error by the autofocused field. Changes nudge the
  phones they reach and log `music_*` / `storytel_login_*` events. PWA text is English like the rest.
- **Wire**: `PolicyResponse.music` = `{library_version, mobile_data, stream_mobile_data, download_mobile_data,
  volume_cap_pct, storage_limit_gb, storytel_generation}` (always the key; `null` when the library query fails, QA #3;
  `policy_json_keys_snapshot`). Design 30: `mobile_data` stays for an older launcher (it refuses a music object
  without it) and is `stream && download` (`music::mobile_data_wire`: AND never spends data the parent turned off);
  `storage_limit_gb` is the stored choice, `null` = no limit, a stored non-choice is sent as 2
  (`music::storage_limit`). The library (`music::library_for`):
  16 hex of SHA-256 over the JSON **without** its `version` (QA #4; `sonos_rooms` - null or the allowed uids, after
  `covers`, ignored by an older music app, which then allows every room - is hashed like every other field), pinned
  by `music_library_snapshot` in `testdata/music_library.json` (the music app keeps a copy). Ticks and uploads that
  would push a phone's library past 3 MB are refused / taken back. Own files not on disk are listed `missing` (startup
  check, and on a 404 while serving), never dropped.
- **Uploads**: streamed to `.part` (hashed, <= 1 GB, the free space re-checked every 64 MB - stopped below 1 GB free,
  `rustix` statvfs), read by content with `lofty` in `spawn_blocking` (mp3/m4a/m4b/ogg/opus/flac/wav, else deleted and
  listed), embedded art and parent covers in `photos::MUSIC_COVERS` (backed up, `recover_missing`); the audio is never
  backed up. The same content again restores a `missing` row in place (id and path kept) or is refused as a double;
  the 300-file and 200-entry limits are the INSERT's own `WHERE`, and a tick's size check and write share one
  `BEGIN IMMEDIATE`. Own keys are `own-<12 random hex>` (ids repeat after a restore). Files no row names (an older
  backup restored) show on `/music` with a delete button, never removed by themselves.
- **Library cache** (migration `0050`): triggers on `music_entries`, `music_categories`, `music_files` and
  `device_music_entries` move `music_library_revision`; `music::cached_library` (`AppState.music_libraries`) keeps each
  phone's built library under it, so the policy poll, the library route and the device card build once per change. A
  new table that feeds the library needs the same triggers (or `music::bump_library_revision`). Covers are checked
  with one scoped query; the import builds each phone's library once plus once to confirm.
- **Add check**: through the sweep's client (`music_net`, below): psapi's catalog root (public only) or the feed
  (LAN allowed), read up to the kind's cap and marked `truncated`; a feed is judged by what the first 20 MB hold
  (`music_sources::parse_feed`, the sweep's parser), so long feeds are accepted.
- **Storytel** (`music_secret`): AES-256-GCM, random nonce, AAD `storytel-v1:<key fingerprint>`; the key from
  `MUSIC_SECRET_KEY` or the 0600 key file `data/keys/music-secret.key` (0700 directory, written through a temp file and
  a rename; `data/` is all the unit may write, and no backup copies `data/keys/`). Write-only in the PWA; `generation` +1 on save and
  clear; policy generation 0 unless the phone's switch is on and a login is stored; the device route is `no-store`,
  404 (switch off / none), 503 (no key, other key). Never in a page, log, status or event.
- **Catalog**: `newest_matching_release` honours `release_tag_prefix` (`release_allowed`: never `server-v*`; with a
  prefix only its tags; without one never `music-v*`), so the launcher row can't take `vibb-music.apk`. Editable on the
  app's page and the add form.
- **Import from Vibb** (design 21a, `src/music_import.rs` pure, `handlers::music_import`): `POST /music/import`
  (multipart `library`, <= 1 MB, never stored) previews a Pi's `library.json` at `#import`; `POST
  /music/import/confirm` re-reads the preview's hidden document with the same parser and re-checks in one transaction
  (new categories, entries in file order after `NEXT_SORT`, ticks via `music::tick_fits`), then a session flash at
  `/music#import` and one `music_library_imported` event. Targets only through `parse_link` (a `storytel:` one through
  `storytel::parse_target`, a Storytel entry since design 26b, below): no outgoing request. Skips: Spotify, folders,
  `parse_link` refusals, `spotify_user` sections, no name, already in the library (target or key), repeats, past 200.
- Tests: `src/tests/music.rs`, `music::tests`, `music_secret::tests`, `music_import::tests`, `tracked_apps::tests`
  (`launcher_and_music_rows_keep_to_their_own_releases`).

## Vibb music: the server sweep (design 21b = step 1c, `docs/design/21b-music-server-sweep.md`, migration `0051_music_sweep.sql`, 0.22.0)

- **What**: the server lists every NRK/RSS entry itself (vibb's logic; Storytel too since design 26b, below); phones
  get each list from `GET /api/devices/music/entries/{id}/items` and only download audio from the source.
  `music_sweep::run` is spawned in `main` (never in `build_admin_router`), its first pass 60 s after start, then
  whenever something is due or `wake()` (`AppState.music_sweep`: after an add, the import's commit, a tick, Check now,
  a flagged item, the setting).
- **Data**: `music_settings.sweep_hours` (1/3/6/12/24, default 6), `music_check_requests` (Check now's hourly/daily
  limits), `music_listings` (one per NRK/RSS entry: source title, `lan`, `fallback`, `capped`, `cut`, `keep_end`
  newest/first, cover, RSS validators + `parser`, `version`, stamps, `failures`), `music_items` (`seq` oldest first,
  `state` ok/pending/gone, keys checked). Stamps are UTC text from the caller's `now` (tests drive a fake clock).
  Triggers on both tables move `music_library_revision` (the library names `items` = the listing version, falls back
  to the listing's cover, and plays an `auto` NRK series that keeps its newest 100 `newest_first`). Each library
  entry also says `lan` (true only for an RSS feed whose first check found it on the LAN or tailnet, written in the
  same update as its first version): the music app refuses private addresses for every other entry's audio
  (qa-21b-step1c-code #14).
- **Fetching** (`music_net`): every source request (add check, sweep, covers) through `AppState.music_fetch` =
  `Gated(HttpSource)` - one request in flight server-wide, starts 200 ms apart; caps/timeouts per `Kind` (page 2 MB /
  30 s, manifest 256 KB / 15 s, feed 20 MB / 120 s, image 10 MB / 60 s; 10 s connect, 30 s without data), gzip, no
  proxy, <= 5 redirects http(s) only. `Reach::Public` (NRK, and a feed whose target resolved publicly at its first
  check) uses a resolver that drops private answers at connect time and refuses private IP literals and redirects to
  them; `Reach::Any` only for a LAN/tailnet feed the parent entered. Tests use `tests::music::CannedFetch` (canned
  answers, DNS, ETags, an in-flight counter) behind the real gate; no test touches the network.
- **Parsing** (`music_sources`, pure): psapi pages/roots/manifests/metadata (keys `[A-Za-z0-9_-]{1,64}`, years >=
  2100 = always) and RSS with `quick-xml` (decoded by BOM/declaration with `encoding_rs`; a DOCTYPE with an internal
  subset is refused; direction from adjacent `pubDate` pairs; keys `sha1(guid)[:12]`, else of the enclosure; NRK's
  fallback uses the guid). Recorded answers in `testdata/music_sources/`.
- **Checks** (`check_entry`): start stamp (`failures` + 1), work, one closing transaction that drops the result if
  the entry is gone. NRK podkast newest 100 (`sort=desc` to the first known key, RSS fallback when psapi lists
  nothing); serie by play order (`keep_end`, refill on change; `auto` = newest 100 past 100 episodes, else all from
  the start); `serie/<slug>/<programId>` along the metadata's `next`. A routine NRK check reads a 5-episode page
  first (50-episode pages only if all five are new; Check now and fills read 50) and sends psapi's validators back if
  it ever gives any. The RSS fallback only for a first fill or a list already in fallback (a psapi list whose page
  comes back empty fails the check), and its merge marks nothing `gone`. Failing manifests leave items `pending`
  (<= 10 retries a check, `gone` after 14 days); 3 network errors in a row among them fail the check (an outage backs
  each entry off on its own - there is no breaker); a root/page/feed failure fails a check and keeps the last good
  list; a listed entry whose every item is withdrawn is listed with them `gone`. RSS: conditional GET (validators only
  for the current `parser`), body-hash skip, merge (missing -> `gone`, back -> `ok`), 1000 kept; tracking prefixes
  stripped from `url`. Budget 250 requests / 10 min commits progress. The parser is bounded: the newest `keep` items
  and their dedupe sets only, 8 KB of text per element, nesting past 64 = `not_feed`.
- **Cadence** (`schedule`/`due`): RSS every `I`, NRK `max(I, min(2I, 12 h))`, unticked daily; priority Check now >
  never listed > rework (flags, untried pending, refill) > interval; backoff 15 min x 4^(n-1) capped at the interval,
  daily after 7 days.
- **Rechecks**: `music_state.item_errors` 401/403/404/410 at the current version from a phone with the entry flag the
  item (`flag_reports`, once per item a day; stamped by `Sweep::now`, the tests' fake clock); a flag makes an entry
  due only while it isn't failing; it is cleared (and `rechecked_at` stamped) once its item was asked - also when the
  check then fails - and kept when the day's budget ran out; two items reported within 24 h re-resolve the whole entry
  (once a day); 300 manifests a day server-wide (expiry re-resolves count); a re-resolve answered 404/410 is `gone`;
  a flag that lands during a check survives its rewrite.
- **PWA**: `#sweep` setting card, the library cards (`partials/music_card.html`: cover, source title, LAN, episodes,
  capped/cut notes, a two-line `.music-status`, Check now within its limits, the offline select, per-phone lines from
  `music_state.downloads`/`item_errors`), the entry page's `#status`, `GET /music/cards?ids=` (<= 200) +
  `static/music-cards.js` (swaps busy status blocks in place, `scrollBy` keeps the view). Times say UTC.
- **Contract**: `music_listing_snapshot` pins `testdata/music_listing.json`; `music_library_snapshot` now shows
  `items`. Listings aren't counted against a phone (no listing budget; a library of long feeds is a few MB). The
  policy shape is unchanged. Revision triggers are on `music_listings` only (version, cover, kept end).
- Tests: `src/tests/music_sweep.rs` (incl. a simulated day per setting through `run_pass` itself, with the fake
  clock, against 21b's request counts, and one with a flagged failing feed and a failing NRK page),
  `music_sweep`/`music_sources`/`music_net::tests` (loopback server for the address rules).

## Vibb music: Storytel on the server (design 26b steps 2a, 2b and 4a, `docs/design/26b-storytel.md`, migration `0072_music_storytel.sql`)

- **What**: `src/storytel.rs` (vibb `storytel.py` ported, itself after storytel-player 1.3.0) logs in with the family's
  sealed login and reads the **bookshelf only** (never bookmarks or audio; the phones log in and mint audio URLs
  themselves). `normalize_shelf` groups it into series and standalone books in first-seen *document* order (a
  `MapAccess` visitor: `serde_json::Map` sorts keys here, and `preserve_order` must stay off - it would reorder every
  map the server serialises), `model.id` as the book's key (`id_ok`: `[A-Za-z0-9_-]{1,64}`, a number in decimal, a
  missing or bad id skipped, a duplicate kept once), Python's falsy rules (`""`/`0`/null series id = a standalone
  book). `AppState.storytel` (`storytel::Account`, a tokio `Mutex`): the JWT (1 h, one re-login on a 401) and the last
  shelf (5 min for a routine read), in memory only and per login generation; its clock is `tokio::time::Instant` plus
  an offset the tests move (`Account::advance`; a paused runtime would auto-advance while sqlx waits). Every request
  goes through `AppState.music_fetch` (`Kind::Login` 64 KB/20 s, `Kind::Shelf` 8 MB/30 s, `Reach::Public`); the
  bookshelf is a POST with `{"items":[]}` under a *form* content type (a JSON one is a 400).
- **Refused-login cooldown**: a login answered with anything but 200 (a 5xx too) stamps
  `music_storytel.login_refused_at` + `login_refused_generation`; while `0 <= now - at < 1 h` (`0 <=`, not vibb's
  `0 <`: whole-second stamps; the phone's boundary too; a stamp in the future is none) no login is tried for that
  generation - no request at all. A new save (generation + 1) clears it. A login without a JWT or one the server
  can't reach is no refusal. `login_ok_at`/`login_error` (a code, never text: `login_refused`, `network`, `http_503`,
  `storytel_key`, ...) and `login_error_at` feed the card's status line. Stamps use `Sweep::now()` (the fake clock).
- **PWA**: saving the login (`POST /music/storytel`) probes it first (one login + one bookshelf, `PROBE_BUDGET` 45 s in
  all, the gate's wait included - a feed fetch can hold the gate for 120 s): not 200 = a 400 and nothing stored (the
  old login kept, `storytel_login_refused`; worded as a wrong password only for 401/403/no JWT); accepted or
  unreachable = sealed, the probe's JWT and shelf kept (`keep` uses `try_lock`: while a read holds the session it keeps
  nothing), a one-time notice (session flash). `GET /music/storytel` is the shelf picker (a fresh bookshelf within
  `READ_BUDGET` 45 s, kids' books unless `?all=1`, in-library groups disabled, text only). `POST /music/storytel/add`
  reads a repeated `target` as `Form<Vec<(String, String)>>` (`FormMap` keeps one; more than 200 fields is a 400) and
  adds only groups on the shelf - one target that isn't refuses the whole form (vibb 4c: a raw target is privileged);
  its shelf is `Account::shelf(300 s)`, which falls back to the last good shelf of the generation (vibb's "stale beats
  dead" - the picker's only fallback). Entries: source `storytel`, order `auto` (oldest first for Storytel), cache -1,
  resume on, Lydbøker by default. A Storytel entry's offline choices are all / the first 5, 10, 20 (on the entry page
  and the card's select); a 0 is saved and sent as -1 (vibb 87-89).
- **The listing** (step 2b, `music_sweep::storytel_check`; vibb `sync` minus the downloads): **the row's cooldown is
  read first** (`storytel::credentials(..).cooldown_until(now)`: inside it the check fails `login_refused` with no
  request), then `Account::shelf_counted(300 s)` - `0` for Check now, so a book added in Storytel's app a minute ago
  shows - which **never falls back to the kept shelf**: a refused login, an outage or a bad answer fails the check with
  its code (`login_refused`, `network`, `http_<n>`, `bad_shelf`, ...) and the last good list stays, so the card never
  says "Checked" over a refusal. One read serves a pass (the 5-minute cache) and its 0-4 requests count against the
  check's budget. The group whose target is the entry's, else `not_on_shelf` (the list kept). The list is the group
  now: one `ok` item per book in reading order, key = the consumable id, `url` NULL (the phone mints; 0072's note),
  `hls` 0, the shelf's `duration_ms` (0 = NULL), `art_url` the book's cover; a book that left the shelf leaves the
  list (no `gone`); locked and geo books are listed (the phone finds out at the mint). The listing's title and cover
  candidate are the group's, **`keep_end = 'first'`** (the anchor `finish()` and `device_listing` read). `schedule`'s
  SQL, `check_entry` (`music::entry_link` -> `Link::Storytel`), `check_now`, `/music/cards` and `save_offline` take
  Storytel; `flag_reports` skips it (a mint failure is the phone's). **A save makes due at once** every Storytel entry
  whose last error (`storytel_off`, `login_refused`, `storytel_key`, `no_jwt`) is older than `music_storytel.saved_at`,
  and wakes the sweep. Texts: `entry_error_text` (the phone's codes: `no_audio`, `not_on_shelf`, ...) and
  `sweep_error_text(source, code)` ("Storytel couldn't be reached", "the Storytel shelf is too big").
- **The gate** (§2.7): `storytel_gate!()` (an exported macro, `concat!`ed into the SQL) in `LIBRARY_ENTRIES` (its
  `IN` takes `'storytel'`), `device_listing`, `cover_in_library` (both entry branches) and `art_in_library`: a phone
  gets Storytel entries, listings and covers only while its `music_storytel` switch is on and a login is stored
  (else 404, and the library drops them - the phone then prunes the books, vibb's logout). 0072's triggers move
  `music_library_revision` on the switch and on the login's presence. The library card knows the gate (`SweepView`:
  "Ella: not sent - Storytel is off on this phone", `phone_count` counts only phones that get the entry; without a
  login a failed check says "The phones don't get it until a login is saved."); a card says "N books" (no "newest", no
  "· N with episode art"); per-phone lines from `music_state.downloads` (`waiting: login`) and `item_errors`
  (`no_audio` "Storytel gave no audio for this book"). The device card: the phone's `music_state.storytel`
  (`none`/`ok`/`refused`, refused as a warning), "Storytel is off on this phone: its N Storytel entries aren't sent",
  and the downloads switch's text "... a Storytel book is often several hundred MB" (design 30).
- **The import** (`music_import.rs`, §2.6): a `storytel:` target `parse_target` accepts imports as a Storytel entry
  (name, order, resume; cache 0 -> -1); one it refuses is `Skip::BadLink`. Nothing is fetched: the sweep lists it
  (`not_on_shelf` if the family's shelf lacks it, `storytel_off` without a login).
- **Book covers** (step 4a): the art pass fetches each book's cover as the item's `art` hash - see "episode art"
  below (no `same`, no twin merge, oldest first, the carry-over, `restamp_versions`).
- **Migration 0072** adds the cooldown and outcome columns and the two revision triggers; frozen like every applied
  migration, and it deploys after 0070 and 0071 ("Data model": the order and the backup hazard).
- **Secrets**: the password, `pwd=` (AES with a public key: as good as the password), the e-mail and the JWT never
  reach a log, an error text, an event or a page. `music_net::network_error` drops the URL from every transport error
  (`without_url`: reqwest prints the URL, and the login's carries the e-mail and `pwd=`); `StoredLogin`, `Jwt`,
  `Credentials` and `SourceRequest` have no `Debug` or `Display` (a compile-time probe,
  `storytel::tests::secret_types_have_no_debug`), `SourceResponse`'s `Debug` leaves the body (a login's JWT) out, and
  the source clients send no `Referer` on a redirect (`.referer(false)`: reqwest would copy the login URL into it).
  **reqwest never logs below `info` here, whatever `RUST_LOG` says** (`main::log_filter` drops every
  `reqwest`/`reqwest::*` directive, then adds `reqwest=info`): a future reqwest could log a URL at `debug` (0.12 logged
  each redirect's; the 0.13 in the lock doesn't), and the login's carries `pwd=`. It is defence in depth for reqwest's
  own events only. Test fakes never look real (`FAKE-JWT-1`, `*.example.invalid`, never `eyJ...`).
- Tests: `storytel::tests` (AES vectors, targets, normalising, the request's shape, 401, cooldown, cache, probe
  bound, the picker's read bound, no secret in errors, no Referer), `src/tests/music_storytel.rs` (save/probe, card,
  picker, add, clear, entry page, the log floor by real events; the sweep's listing, Check now's fresh shelf, the
  cooldown-first rule, the never-stale sweep read, one shelf per pass, the gate on library/listing/covers, the cards,
  the wake after a save; the book covers), `music::tests` and `music_import::tests` (the link, cache 0 -> -1, the
  import); fixtures in `testdata/storytel/`; `music_library.json` holds a Storytel entry (shared with the music app and
  the launcher). `tests::music::CannedFetch` records each request (`requests`) and has `answer_once`.

## Vibb music: episode art (design 27, `docs/design/27-music-episode-art.md`, migration `0070_music_episode_art.sql`)

- **What**: the sweep fetches each NRK/RSS item's own picture (`art_url`: NRK's smallest variant of at least 512 px
  of `squareImage` else `image` - `null`/`[]` count as missing, as vibb's `or` -, `music_sources::EPISODE_ART_WIDTH`; an RSS item's `itunes:image`) as a 512 px JPEG
  in `photos::MUSIC_ART` (`AppState.music_art_dir`, `data/music_art`; its own lock; **not backed up**:
  `recover_missing` forgets lost ones, `music_sweep::restamp_versions` then stores the listings' versions again
  (code QA L6), and the sweep fetches them again). The listing's item `art` is the 64-hex hash
  of that picture, or null (none, the series picture, not fetched yet, failed) - `testdata/music_listing.json`, the
  music app's copy and the launcher's `MusicPlanTest` version pin change together. Served by the cover route:
  `cover_in_library` first, else `music::art_in_library` (a listed, non-pending item of a listed NRK/RSS/Storytel entry
  the phone has ticked, a Storytel one behind `storytel_gate!()`) from `music_art_dir`.
- **Storytel books** (design 26b step 4a): a book's `art_url` is the shelf's cover; `art_candidate(item, link)` takes
  an `ok` book with no `url` (an NRK/RSS item needs its `url`), `auto` reads a Storytel entry from book 1, and
  `storytel_check` keeps a known book's whole row (every `art_*` field), so a check never drops a hash or a failure's
  try count. A book is judged against nothing (qa-26b-step4a-code M1: the "series cover" is only the first-seen book's,
  so `same` would show another book's face): no reference prints, no step 1, no twin merge, a `same` row is made
  untried - every fetched book cover is `own` (the budgets are NRK/RSS's: a 40-book series fills over two checks). No
  16:9 reference (NRK only). `restamp_versions` covers Storytel listings too.
- **The pass** (`music_sweep::art_pass`, after the listing, manifests and cover; not on recheck-only checks): the
  first `ART_WINDOW` (200) `ok` items in play order (`effective_order`'s rule), this check's new items first (with
  the backlog off, also items first seen since it went off, code QA L2); due = untried (`art_from` NULL or another
  URL), `failed` with `art_fails < 3` a day after `art_at`, or a host error a day after `art_at`. A judged item whose
  URL changed only in its query (signed, cache-busting) takes the new URL with no request (`same_resource`); one
  moved to another path is due at most weekly (`ART_MOVED_RETRY_DAYS`, code QA M1). Step 1: the URL is the
  cover's or NRK's 16:9 picture's -> `same`, no request. Step 2: another item (this entry's, else any entry's, one
  with a file first) has a print for that URL -> judged against this entry's reference prints, no request; a `same`
  elsewhere has no file, so an `own` here needs the request (QA L1 c). Step 3: one GET (`Kind::Image`,
  `public_media` unless a LAN feed), `process_limited(Square)`, `photos::fingerprint_limited` (9x8 dHash + `tone_image`, the
  quarters' mean RGB); within `SAME_PICTURE_BITS` (5) of a reference -> `same` (nothing stored), else `own`; two own
  pictures of an entry are one hash only as `twins` (code QA L4: within `TWIN_BITS` 2, the same tone, and not
  `low_detail` - a flat field or small text on one background). Known limit: the series line is dHash only, so a flat
  episode picture can count as a flat series cover (the cover shows), and a recoloured series picture is `same`. A
  failure never fails the check: a 4xx, a refused address or an undecodable picture uses one of 3 tries; a network
  error, timeout or 5xx uses none, and `ART_NETWORK_FAILURES` (3) in a row end the pass (code QA M2). Budgets:
  `ART_PER_CHECK` 20 (`Sweep::set_art_per_check` in the tests; 0 in tests that count listing requests),
  `ART_PER_DAY` 400 counted in `music_art_requests` (one row per image request, the 16:9 reference included; rows
  older than a day go; code QA M1); a request the budget stops leaves its item untried (QA L1 b). The log line adds `, art N (o own, s same)`.
- **Reference prints** (QA M2, `music_listings`): `cover_print` (of a newly fetched cover, else read once from the
  stored file), `cover_alt_url`/`cover_alt_print` (NRK's 16:9 `image` when the cover is its `squareImage`,
  `Root::wide_image`, one request when its URL changes, at most daily; the root is read while `cover_alt_at` is NULL
  or the refresh is pending, code QA L1) and `old_prints` (JSON, up to 3 earlier
  covers). `art_refs` is the set the items were judged against; a change re-judges every item from its stored print
  with no request (`own` that matches -> `same`, its file pruned; `same` that no longer matches -> untried).
- **Backlog** (`art_backlog`, `art_same_run`, `art_backlog_at`): 5 fetched pictures in a row that are the series
  picture with no `own` in the entry -> only new items are tried; an `own` turns it back on, and so does a month
  (QA L1 e). **Refresh** (`art_refresh`, set by 0070 for NRK entries, QA M3): the next check walks the episode pages
  from the kept end once more and updates `art_url` of known keys (untried), nothing added or dropped; cleared when
  the walk finished within the budget.
- **Stored** with the items in `finish` (the `MUSIC_ART` lock from writing the files to the commit; a hash copied
  from another item that is no longer on disk is left untried), pruned after a `finish` that changed an art reference
  and after an entry is deleted. The card's count line adds "· N with episode art" (not on a Storytel card: "N books" only).
- Tests: `src/tests/music_art.rs`, the Storytel cases at the end of `src/tests/music_storytel.rs`, `photos::tests` (the print, the tone), `music_sources::tests` (the 512 pick,
  `wide_image`, `null`/`[]`). `CannedFetch::hold`/`release` (a gate every request waits at) and
  `Sweep::pause_before_commit` (cfg(test)) let a test act inside a check without a clock (code QA L3).

## Parent PIN (design 24, `docs/design/24-parent-pin-hardening.md`, migrations `0056_parent_pin_easy.sql`, `0057_device_pin_events.sql`)

- **Its own card and save**: `#parent-pin` on the device page, right after "Screen lock"; `POST /devices/{id}/parent-pin` (`handlers::parent_pin::update_parent_pin`, full auth) takes `new_pin` or `clear_pin`. `update_policy` (the Quick Controls/VPN form) no longer touches the PIN columns. Refused with a notice and nothing written: not 6-10 ASCII digits (`parent_pin_invalid` - silently ignored before), the kid's PIN (`override_is_kid_pin`), removing it while a kid PIN is set (`override_needed_by_lock`, the SQL `CASE` keeps it even against a parallel kid-PIN save). Security events `override_pin_changed`/`_cleared`; nudges the phone, which then takes the new PIN - and a fresh lockout state with it. Every outcome redirects to `?notice=<code>#parent-pin`.
- **Easy PINs are warned about, never refused** (user's answer; no date check): `parent_pin::easy` - all one digit, every step +1 or -1 mod 10 (890123, 1234567890), or `COMMON_PINS`. Saved with `override_pin_easy = 1` and `parent_pin_saved_easy`; the card and the Status card show the warning. PINs stored before the check get it once from a startup task (`parent_pin::run_easy_sweep` with `easy_candidates()`, the 187 PINs `easy` flags: PBKDF2 per candidate, one device at a time, `UPDATE ... WHERE override_pin_hash = ?` so a newer save wins). `override_pin_easy` is not in the policy JSON.
- **Notices inside their cards**: the `?notice=` codes of the parent PIN render at the top of `#parent-pin` (`parent_pin::flash_text`), the kid lock's at the top of `#screen-lock` (`kid_lock::flash_text`, `(text, warning)`); nothing at the top of the page. "Parent PIN" replaces "unlock code (offline override PIN)" on the device page.
- **The phone's events** (`POST /api/devices/pin-events`, `handlers::parent_pin::pin_events`): read leniently field by field (`parent_pin::clean_event`) - a bad id (not 32 hex) or an unknown `kind`/`entry` is skipped and logged; a time outside 2020..now + 1 day is kept with the received time and `clock_off = 1`; an implausible `until_ms`/`failures`/`level` becomes null. `INSERT OR IGNORE` on `(device_id, event_id)`; only a newly stored `unlocked`/`lockout` also writes `parent_pin_used`/`parent_pin_lockout` to the security log, in the same transaction, its detail built only from the checked enums and numbers and saying when it happened on the phone (`parent_pin::log_line`: `device 3, 2026-10-09 14:02 UTC: ...`, the window's end dated) - the row's own time is its arrival. Wrong tries stay on the device page. **No `command_notify`**: a sync would end the override. Retention: 90 days by `received_at` (`retention::prune`).
- **Device page**: the card lists the last 10 events ("2026-10-09 14:02 UTC (3 h ago): ..."), or says the launcher doesn't report yet (no `parent_pin_events_v1`). The Status card shows at most two notices from the last 24 h and since the PIN was last saved here (`device_policy.override_pin_set_at_ms`, so a new PIN ends them; `parent_pin::notices`): the latest use (with "Typed where your child could see?" for the time lock and the PIN lock), then a lockout (error) or else the count of wrong tries. The old `offline_override_used` banner shows only without the capability.
- Tests: `parent_pin::tests` (every easy rule, the full 6-digit loop against the candidates, `clean_event`, every line and notice), `src/tests/parent_pin.rs`.

## Windows that lock again, unseen boots (design 24b, `docs/design/24b-override-relock.md`, migrations `0063_window_relock.sql`, `0064_unseen_boots.sql`, `0067_relock_blocks.sql`)

- **The phone's parent-PIN windows** (an override, a pause) lock down again by themselves; the launcher's `relock` report (status, capability `window_relock_v1`) says what it reset and what it couldn't undo. Stored re-serialized through the known fields only (`window_relock::sanitize_relock`: known trigger/windows/step names, valid package names, at most 8 per list, counts 0..1000) in `device_status.relock_json`. The Status card warns for 7 days after a relock that found something (`window_relock::relock_notes`): what it removed (Wi-Fi proxies, certificates, an Android screen lock), "Blocked: ..." (apps installed or re-signed from outside Play while it was open, each with its signing key from `blocked_signers`; one with another key than the catalog's pin - `tracked_apps.signer_sha256` - "stays blocked: uninstall it") with "Allow these apps" for the others until the phone answered one pressed after the relock, "Still on: ..." (accessibility services, notification access, device admins, a proxy on a network, a screen lock it couldn't remove), system apps turned off, the launcher's notifications off, failed steps. A new `at_ms` with findings writes `phone_relock_findings` (`window_relock::log_line`, the phone's time in the detail).
- **Commands** (`handlers::window_relock`, full auth, plain forms): `POST /devices/{id}/end-windows` ("Lock the phone again now": shown in the Status card while the latest report has a pause or an install mode still running and the capability; the pause line then names it), `POST /devices/{id}/allow-blocked` and `POST /devices/{id}/start-pause` ("Pause restrictions for 2 hours", the phone's own pause without the PIN on it; shown while no pause is reported). The newest of each shows for a week with the phone's answer or "no answer from the phone yet" (`window_relock::answer_line`). Their own insert into `device_commands` - `locate::queue_command`'s "replace the undelivered one" skips them (`window_relock::OWN_INSERT_COMMANDS`) and they delete nothing (QA #8); the phone syncs once more after each. Security event `device_command_queued`; redirect to `/devices/{id}?notice=...` without a fragment, the notice in the Status card.
- **Do Not Disturb** at the last sync is a warning (the sound line in error style).
- **Current blocks** (fix of 2026-10-12): status `relock_blocks` `[{package_name, signer, reason, found, at_ms}]` - every app the phone keeps hidden whatever its tick, from a relock or the install check outside windows (`sanitize_relock_blocks`: valid names, a valid key or none, known reason `outside_play|other_key|catalog_key|unknown` and place `relock|install`, at most 16; `[]` = none, NULL = an older launcher) -> `device_status.relock_blocks_json` (0067), with `relock_blocks_total` (how many in all, stored only with the list - 0068; the card says "And N more - the phone lists at most 16 ...", `more_blocks_line`, QA of cmd-expiry Low 2). The Status card lists each with its key, why and when (`blocks_note`) and whether it is ticked under Apps ("Ticked under Apps: it shows again after ..." / "Not ticked under Apps: ... until you tick it too", linking to its row `#app-<package>`; nothing for an imposter or unmanaged apps - QA of cmd-expiry Low 3), with "Allow these apps" while one can be let out; one with another key than the catalog's pin stays ("uninstall it"). The button posts the listed apps with the keys shown (hidden `blocks` = `package:key,...`, `allow_form_value`/`parse_allow_form`; nothing valid -> 400) into `device_commands.payload` (0068), and the pop sends them as `pending_command.allow` `[{package_name, signer}]` (left out for other commands): the phone lets out only those, each while its key is still the one shown - never a block that came after the parent looked (QA of cmd-expiry, Low 1); a row from before 0068 has none and lets out every block, as before. `device_command_queued` names the apps. When the phone reports them, the last relock's "Blocked:" line gives way to this list. `phone_app_blocked` in the security log for each newly reported block.
- **Foreign access** (the fix round, QA High 1 (b)): status `foreign_access` `{admins, accessibility}` (apps outside the allowlist with a device admin or an accessibility service on; valid package names, at most 8 each, `sanitize_foreign_access`) -> `device_status.foreign_access_json` (in 0063). While the latest report has one, the phone refuses the parent PIN and the Status card shows a red warning with the steps (`foreign_note`: pause from here, deactivate and uninstall on the phone, lock again, change the parent PIN); `phone_foreign_access` in the security log when it appears, changes or goes.
- **Unseen boots** (H4, `boot_witness`): status `unseen_boots` `{count, last_at_ms}` (read leniently) -> `device_status.unseen_boots_count`/`_last_at_ms`; `boot_unseen` in the security log when the count rose; the Status card warns for 30 days ("safe mode, or a start cut short"), advising "Block safe mode" or, with it on since before the boot was noticed (`device_policy.disallow_safe_boot_set_at_ms`, in 0064: set when the hardening card or the first kid PIN turns it on, cleared when off), "probably a start cut short" - otherwise both explanations. `disallow_safe_boot` by default (design 24b S4) waits for device check 6 on the Jelly Star.
- Tests: `window_relock::tests`, `boot_witness::tests`, `src/tests/window_relock.rs`, `src/tests/hardening.rs` (`unseen_boots_*`).

## The lock's glance page (design 25, `docs/design/25-lock-glance.md`, migration `0066_lock_glance.sql`)

- **What it is**: the phone's PIN lock always comes up on a first page (clock, date, the music tile, up to three
  notification rows, "Lås opp", "Nødsamtale"): missed calls always, and messages from the apps the parent ticks here,
  sender only unless the text switch is on. A row's tap only reaches the keypad; nothing is answered, marked read or
  dismissed from the lock. The phone reads the notifications in memory only - **no row, name, text or count ever
  comes to this server** (only the capability `lock_glance_v1`).
- **Data** (`src/lock_glance.rs`): `device_lock_glance_apps` (device_id, package_name; one row per tick, cascades with
  the device - a table, like `device_tracked_apps`, so there is no corrupt state) and `device_policy.lock_glance_text`
  (default 0). The migration ticks Element X for every existing device; new ones get it from the
  `devices_get_lock_glance_default` trigger (as `devices_get_builtin_wallpapers`), so every path that creates a
  device has the default.
- **Wire**: `PolicyResponse.lock_glance = {apps, show_text}`, always sent; `apps` sorted, only valid package names
  (`app_display::valid_package_name`, the launcher's grammar). Cosmetic like the wallpapers: a failing query sends
  `apps: []` (missed calls only on the lock) and logs, never a 500. An older server's missing key reads as Element X,
  sender only on the phone; an older launcher ignores it - deploy order is free.
- **PWA** (`handlers::lock_glance`, full auth, CSRF as every form): the Apps card's `<h3 id="lock-screen">On the lock
  screen</h3>` (after the allow list, before "Names and icons"): the explanation, Low 8's warning (names show to anyone
  holding the phone even with the switch off, missed-call names included; with it on a ticked SMS app's login codes
  show too), the notes (`lock_glance::notes`: no kid PIN, `notification_listener_enabled` false, a reporting launcher
  without `lock_glance_v1`), the switch (`POST /devices/{id}/lock-screen`, checkbox `show_text`, form
  `#lock-screen-text`) and one auto-saving tick per app (`POST /devices/{id}/lock-screen/apps`, `package_name` +
  checkbox `on`, form `#lock-screen-app-<package>`). Listed: with an allowlist the allowed apps (the "Names and
  icons" set), without one every app the phone reports (QA Low 7: a calls-only phone with a kid PIN has the lock
  too), never Play core or the launcher; every ticked package outside that list is added - "not allowed - ticked"
  when the allowlist lacks it, else "not on the phone - ticked" (`lock_glance::unlisted_note`) - so any tick can be
  removed; an empty list says why (`empty_text`: no report yet, nothing allowed, nothing reported). 404 unknown device, 400 a package
  that isn't a package name or is Play core, 500 on a DB error - nothing written; security events
  `lock_screen_text_changed` / `lock_screen_app_changed` (`lock_glance::log_line`: "device 3: io.element.android.x on
  the lock screen: on"); nudges; redirect to `/devices/{id}` without a fragment (scroll-restore keeps the place). The
  Launcher card's listener warning and the Screen lock card's text name the lock's first page.
- Tests: `lock_glance::tests`, `src/tests/lock_glance.rs` (the migration on an old database, the trigger, the
  cascade, the policy incl. a dropped table, both routes, refusals, the page and its notes), `policy_json_keys_snapshot`
  (`lock_glance` non-null, a fresh phone's `{"apps": ["io.element.android.x"], "show_text": false}`; the launcher's
  `PolicyResponseCompatTest` decodes the same literal).

## Connectivity and exposure (design 22, `docs/design/22-connectivity-and-docker.md`, server 0.23.0)

- **Two listeners** (S1, `main::serve`, one `try_join!`): `BIND_ADDR` (default `127.0.0.1:3100`) =
  `build_admin_router` (admin, onboarding, public routes, `/static`, the device API unless `ADMIN_DEVICE_API=off`,
  `/healthz`, empty-404 fallback), and `DEVICE_BIND_ADDR` (unset = none) = `build_device_router` (`device_routes()`
  + `/healthz` + empty-404 fallback; no session layer, no `/static`). `ADMIN_DEVICE_API=off` without `DEVICE_BIND_ADDR` refuses to
  start (QA 22 Low 2). `/login` and `/auth/verify-2fa` take 4 KiB bodies.
  Equal addresses refuse to start; one exposure line is logged (`NetConfig::exposure_line`). SIGTERM/Ctrl-C: graceful
  stop, `CommandStreams::close_all` ends every SSE stream (each stream merges its close signal, a `watch`), 8 s grace.
- **`src/net.rs`** (`AppState.net`, `NetConfig::from_vars`): `BIND_ADDR`, `DEVICE_BIND_ADDR`, `ADMIN_DEVICE_API`
  (on), `ADMIN_PUBLIC` (off), `TRUSTED_PROXIES` (`127.0.0.1,::1`; IPs or CIDRs, a bad entry is dropped, never
  widened), `ADMIN_TAILSCALE_USERS`. `NetConfig::client`: `X-Forwarded-For` only from a trusted peer, walked from the
  right skipping trusted entries (all trusted: the left-most); an entry that isn't an address stops the walk and
  makes the client unknown (`Client::ip` is `None`, keyed `unknown via <peer>` by `Client::key`) - never the peer,
  which is the proxy (QA 22 H1); IPv4-mapped peers are canonical.
  `limit_key`: IPv4 address or IPv6 /64. Login uses it (the old `security::client_ip` took the left-most XFF entry
  from anyone - §0 #1).
- **Admin gate** (`src/gate.rs`, outermost layer of the admin router): passes loopback and tailnet
  (100.64.0.0/10, fd7a:115c:a1e0::/48) clients and a trusted peer without XFF; an unknown client and any forward
  from an untrusted peer are refused (QA 22 H1); `Tailscale-Funnel-Request` is refused;
  `ADMIN_PUBLIC=on` passes everyone. `ADMIN_TAILSCALE_USERS`: admin pages also need a listed `Tailscale-User-Login`,
  believed only from a tailnet client; `/api/devices/*` skips that check (tagged phones have no login). `/healthz`
  (`SELECT 1`, "ok") skips every guard. Refused: empty 403 + `admin_refused` through `AppState.audit`
  (`src/audit.rs`: one row per kind and client key per minute, counts flushed into the detail every 30 s by
  `audit::run_flusher`; at most `ROWS_PER_MINUTE` = 60 such rows per kind and minute across all keys, the rest
  counted into one summary row - QA 22 M3).
- **Device edge** (S2, on both listeners, in `device_routes()`): `limits::device_edge` (a request without a valid token -
  looked up in memory - takes one of 64 slots, 503 past it; a valid token takes none, QA 22 M2; 30 s timeout except the SSE stream and file downloads; `Cache-Control` `no-store` unless the route set
  one, `private, no-cache` for an `ETag` route, `private` added to anything else; `nosniff`; CSP `default-src 'none';
  sandbox`), a 512 KiB body limit (enroll reads its own body: at most 4 KiB, within 5 s, inside its cap of 8). `security::require_device_token`: the token is looked up in
  `AppState.tokens` (`TokenIndex`, `token_hash -> id`, rebuilt at start and by `refresh` after enroll and delete -
  never a DB query per request); `AuthedDevice` now carries only `DeviceRef { id }`. A bad or missing token counts
  against the client's failure bucket (`limits`, key `net::limit_key`: 30 per 10 min, then 429 for 10 min) and a
  throttled `device_auth_failed`; a valid token never touches it: its own bucket (600, 2/s), then an in-flight slot
  (`route_class`: APK/music file downloads 4, others 8, `policy`/`status`/`command-result`/`command-receipt` and the SSE stream always
  admitted - the stream is bounded by its cap of 2, QA 22 Low 3),
  held until the response body ends (`limits::hold_until_done`). Maps capped at 10k keys. Last access per phone
  (when, tailnet/loopback/outside, the last 5 outside IPs) in memory, on the device page's Status card.
  `CommandStreams`: 2 live streams per phone (a third closes the oldest), `close_device` on delete and re-enroll.
  Warnings: a wide bind with only loopback trusted (startup), an untrusted peer sending XFF (logged once,
  `Limits::exposure_warning` for the Connection page).
- **Enrollment** (S3, `src/enrollment.rs`, migration `0052_enrollment_codes.sql`): one live code per phone, stored
  only as SHA-256 of the normalised code (upper-case, no spaces/dashes) in `devices.enrollment_code_hash` with
  `enrollment_code_kind` - `qr` (26 chars base32, 30 min, only in the setup QR) or `typed` (8 chars of
  `TYPED_ALPHABET`, 15 min, shown as ABCD-EFGH); the plaintext column is cleared and never written (open codes died
  with the migration). A new device has no code; "New code" (`POST /devices/{id}/regenerate-code`) renders
  `enroll_code.html` once (`no-store`), the provision page makes a QR code. `enroll`: not code-shaped -> 401;
  typed-shaped -> 429 while paused or the client's failure bucket is full, 401 uncounted with no typed code live, a
  miss counts toward the bucket and `Enrollment::typed_failed` (20 an hour server-wide while a typed code is live
  pause typed codes until the hour ends: `enroll_typed_paused`, the device page offers the QR); QR-shaped always
  checked. At most 8 enrollments at once (`Enrollment::gate`, taken before the body is read; the body then has 5 s). Use = one `UPDATE ... WHERE
  enrollment_code_hash = ? AND not expired RETURNING`; then the token index, streams, `device_enrolled` (with IP).
  Failures: throttled `enroll_failed`. Device page "Access" card (`#access`): last access, the live code's kind and
  expiry, New code (re-enroll: disconnects the enrolled phone), Provision, Revoke access (`POST
  /devices/{id}/revoke`, confirm box; `token_hash = NULL`, index, streams closed, `access_revoked`).
- **Admin auth** (S4, `src/admin_auth.rs`, migration `0053_admin_auth.sql`; no step-up, user decision U2): argon2
  (verify and change) runs in `spawn_blocking` behind `AppState.passwords` (`PasswordGate`: 1 running, 4 queued, else
  429), an unknown username verifies a dummy hash. Wrong passwords never lock the account (only the per-IP ban, now
  keyed by `net::limit_key`; unban is `POST /security/unban` with an `ip` field since a key can be a /64). TOTP:
  `check_totp` accepts a step within +-1 that is later than `admin_users.totp_last_step` (stored in the same UPDATE;
  setup too); `take_totp_attempt` (one conditional `UPDATE ... RETURNING`, taken *before* the code is checked, so parallel
  requests can't slip past it - QA 22 H2; locked = no code checked): 5 attempts lock 15 min, each further lock doubles
  (`lock_count`) up to 24 h, a lock running out resets nothing, only a complete login does (`reset_after_login`); a correct password on a locked
  account says so (`login_locked` event). The pending state (`pending_admin_id` + `pending_at`) lasts 5 min. After a
  complete login with earlier failures the session carries a banner (`login_warning`, shown on Devices and Account
  via `partials/login_warning.html`, `POST /account/dismiss-warning`, back to the page it was on); a signed-in session
  also sees wrong codes entered since its sign-in and the lock (`auth::live_warning`, from the `AdminUser` row - QA 22
  Low 9), and a password change lifts the counter and the lock. Sessions: `cycle_id()` after each step; the
  session stores `session_epoch`, and a password change, a 2FA reset (asks for the current password - user
  decision on QA 22 M5; other sessions) and `POST
  /account/sign-out-everywhere` (all) bump it (`load_active_admin` compares); cookie `__Host-handy` (Secure, Path=/,
  HttpOnly, Strict) or `handy` with `INSECURE_COOKIES` (`security::session_layer`); `SESSION_INACTIVITY_DAYS`
  (`NetConfig`, default 30). `kid_phone_server reset-2fa <user>` (`main::run_command`) clears TOTP, lock and counter,
  bumps the epoch and replaces the password with a new one in `data/initial-admin-password` (QA 22 M4). `admin_headers`: HSTS, nosniff, Referrer-Policy, X-Frame-Options, COOP, Permissions-Policy,
  CSP phase 1 (`ADMIN_CSP`, inline JS still allowed until S7); HTML `private, no-cache`, `no_store_path` (locations
  JSON, backups, provision, the code page, provisioning settings, account, auth) `no-store`. `csrf_guard` (unsafe
  methods, not `/api/devices/`): `Sec-Fetch-Site` same-origin/none, else `Origin` host[:port] = `Host` (or
  `X-Forwarded-Host` from a trusted peer), neither header passes; refused = 403 + throttled `csrf_refused`.
  Provisioning: `GET /devices/{id}/provision` is the form only, `POST` makes the QR (Wi-Fi password never in a URL);
  the Tailscale key is write-only (`settings::key_hint`, empty keeps, `clear_tailscale_auth_key` removes).
  `security_events` pruned after 180 days (`retention::prune`). The TOTP secret stays in the DB (QA b, DEPLOY.md).
- **Docker and startup** (D1, `src/startup.rs`, `Dockerfile`, `deploy/image-smoke.sh`): `DEPLOY_KIND` (`NetConfig`,
  systemd default, the image sets docker). Startup for every kind: `check_data_writable` ("data/ is not writable by
  uid N", exit 1); `apply_restore_request` (the container's restore: `data/restore_request` written by
  `backups::restore_in_container`, which exits 3 a second later for the restart policy; startup deletes the marker
  first, checks the name/zip/`kidphone.db` like `actions.sh` (at most `RESTORE_MAX_BYTES`, 1 GiB), copies the DB +
  wal/shm to `backups/prerestore-<ts>/` (`PRERESTORE_KEEP` = 3, the new one always kept), swaps, and `undo_restore` puts it back if `try_connect_db` fails;
  `backup_restored` event); `try_connect_db` = `connect_db` without the panic, taking
  `<db dir>/backups/pre-migrate-<from>-<to>.db` (`VACUUM INTO`, 3 kept, a failure stops the start) when migrations
  are pending on an existing DB (`MIGRATOR` static); `bootstrap_admin` takes `ADMIN_PASSWORD`, else
  `ADMIN_PASSWORD_FILE`, else generates `data/initial-admin-password` (0600; the log names the file; deleted by the
  forced password change). Logging defaults to `info` without `RUST_LOG` (tracing-subscriber's own default was
  errors only, so the startup lines never reached the journal). Subcommands (`main::run_command`): `healthcheck`
  (`/healthz` on `BIND_ADDR`, 0.0.0.0 -> 127.0.0.1, :: -> ::1, 3 s), `selftest-tls` (api.github.com through
  reqwest/rustls, any status passes), `reset-2fa <user>`. In docker: the root-helper routes (`/update/*`,
  `/system/*`, `/backups/format-drive`) aren't registered, the live mirror and app-update scheduler aren't
  spawned, the Updates page (`updates::page`, pure) says "Set `HANDY_VERSION=X` in `.env`, then `docker compose pull
  && docker compose up -d`" with no OS/Tailscale/helper cards, the Backups page has no external drive or mirror.
  Image: distroless static nonroot (uid 65532) pinned by digest, a busybox stage seeds `data/` and `data/keys/`
  (0700) owned by 65532, `BIND_ADDR=127.0.0.1:3100`, `HEALTHCHECK` = `healthcheck`. CI `server-image` (only when the
  Dockerfile, `.dockerignore`, the smoke script or the workflow change) and the release's `server-release-image`
  (push by digest, smoke amd64 + arm64 under QEMU, then tag `X.Y.Z`, and `latest` only when X.Y.Z is the highest
  `server-v*` tag, provenance attestation, release published last; `workflow_dispatch` = dry run, on a tag ref too:
  `RELEASE` needs a push event) run `deploy/image-smoke.sh`.
- Tests: `src/tests/docker.rs` (the pre-migration backup once and 3 kept, from a real older DB; the restore marker
  with a good zip, bad names, a zip without a DB, and a DB that won't open put back; the healthcheck; the Docker
  Updates page; the host routes 404 and the drive features hidden in docker), `startup::tests`.
- Tests: `src/tests/admin_auth.rs` (the lock and its doubling, the banner, no password lock, a code works once,
  pending expiry, the epoch on password change/2FA reset/sign out everywhere, `reset-2fa`, 20 parallel logins while
  an SSE keepalive keeps its 1 s beat, CSRF, headers and cache rules, the cookie, the write-only key, pruning, IPv6
  bans by /64), `admin_auth::tests`. `TestApp::next_totp_code` picks a step not used yet; `admin_cookie` uses it.
  `src/tests/enrollment.rs` (QR once, no plaintext stored, a race of 8 gives one token, expiry, typed
  failures without a live typed code count for nothing, the pause with QR codes still enrolling after 200 junk QR
  codes, the shared failure bucket, New code once and replacing, revoke, the setup QR's kind), `enrollment::tests`.
  `src/tests/edge.rs` (a valid token behind a full failure bucket, IPv6 /64, throttled events; policy and
  command-result past full slots; a stream holds its slot; a third stream closes the first, delete ends the rest and
  the token; re-enroll retires the old token; 413s and headers on both listeners; last access on the page),
  `limits::tests`. `src/tests/listeners.rs` (no `"/api/devices` literal in `main.rs`; the device router 404s every admin path,
  sets no cookie and answers every route it reads from `device_routes.rs`; `ADMIN_DEVICE_API=off`; the gate matrix
  and its throttled event; `/healthz` past every guard; shutdown ends streams on both listeners), `net::tests`,
  `gate::tests`, `streams::tests`.

## Current status (2026-08-08, `v0.13.0`)

Feature-complete relative to the original build plan and confirmed working end-to-end on both physical test phones (Pixel 4a 5G GrapheneOS, Moto G Play), not just built-and-reviewed: enrollment (now defaulting a new device to kiosk mode on, see below), allowlist, kiosk mode + full lock-task feature set, schedule (with working clear), WiFi/Bluetooth restrictions, offline override PIN, Settings PIN-gate, pause-all-restrictions kill-switch, mandatory 2FA admin login, scheduled backups + external-drive support, self-update, on-device DNS/content filtering (the old live-DNS-server/DoT-to-Pi approach fully retired - see the Phase A-E writeup above), Find My Device (locate/ring/lock/wipe/stop_ring + SSE instant push), and the Apps catalog with per-device install scoping.

**This session's major work, in order**:
- **Per-device app scoping** (`v0.10.0`-`v0.11.2`): `/apps` became a global catalog, not an install list - a new `device_tracked_apps` join table is what actually pushes an app to a specific device, via its own auto-saving toggle on the device page. The launcher's own row is permanently included for every device (`tracked_apps.is_launcher`, now an explicit admin toggle rather than a package-name guess, after the original migration heuristic missed the real debug-suffixed package). `tracked_apps.package_name` became optional (never actually needed for a silent install to work) with editing support added for the whole catalog-entry form, plus forgiving GitHub-URL normalization. Selecting an app for a device now auto-adds it to that device's kiosk allowlist too, and unchecking it now removes it from the allowlist **and** silently uninstalls it (`device_pending_uninstalls`, self-cleaned once a status report confirms it's gone) - both added at the parent's explicit request after live testing surfaced the friction/gap.
- **A real concurrency bug, found via live `adb logcat`** (`v0.11.2`-equivalent client fix): the client's `performMdmSync` had three independent trigger points with no coordination, so overlapping syncs could race on the same shared per-app cache file and corrupt or delete a still-in-flight download - not a hypothetical, confirmed from real `INSERT_PARSE_FAILED_NO_CERTIFICATES`/`FileNotFoundException` log lines. Fixed client-side with a mutex; see kids-launcher-mdm's CLAUDE.md.
- **`v0.13.0`**: kiosk mode now defaults on for a newly enrolled device (previously every device started wide open), and the notifications/power-button lock-task checkboxes were removed entirely - always on now, same as the other non-configurable lock-task features.

**Not started** (explicitly deferred, not scope-creep):
- ~~Real server-to-device push~~ - superseded: SSE push shipped; handy step 7 added optional FCM nudges, removed again in design 19 (see "FCM removed, SSE only" above). The launcher's UnifiedPush distributor was removed on 2026-10-06.
- ~~Notification listener / message monitoring~~ - dropped (2026-10-06 cleanup: never store notification or message content). (DNS-level browsing restriction, the other half of that original plan, is now built - see DNS/Filters above.)
- Tamper-resistant/launcher-watchdog persistence (auto-restart on crash) - not built on either the old or new client.
