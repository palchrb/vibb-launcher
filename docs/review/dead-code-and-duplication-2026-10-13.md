# Dead code, duplication and maintenance cost (2026-10-13)

Reviewed: the worktree `spotify-wt` at `d23038a` (= `origin/main` `571f86f` plus the finished Spotify phase,
design 26c). Read-only: nothing in the tree was changed. Every number below comes from a command or a
script; the scripts (`measure.py`, `dups.py`, `ktdead2.py`, `pins.py`, `routes.py`, `cols.py`, `cost.py`) are in
the session scratchpad, not in the repo. Builds and lint ran in scratchpad copies (`git archive HEAD`), never in
the worktree: `cargo build`/`cargo clippy --all-targets` (Rust 1.97 stable, offline), `./gradlew lintDebug` with
`UnusedResources` and `UnusedIds` forced on by a scratch `lint.xml`, and the "safe to delete" set applied to a copy and
re-tested (§7).

Churn is "commits since 2026-09-01 that touch the file", counted on a full-history clone
(`git log --full-history` over the file's monorepo path and its pre-import path) plus `571f86f..d23038a`. The
worktree itself is a shallow clone (history starts 2026-10-06), so its own `git log` undercounts.

"Pins" are test assertions that match exact production source text: a string literal of 12+ characters passed to
`contains`/`indexOf`/`substringAfter`/`substringBefore`/... in a test file that appears verbatim in at most three
production files (`pins.py`). They break on a rename or a reformat even when behaviour is unchanged.

## Verdict

The repo is big because it does a lot and documents and tests heavily, not because it carries much dead code.
The compiler proves the server's Rust is clean to within 27 lines: it is a binary crate, so `dead_code` covers `pub`
items too, and the only warnings are 9 unread struct fields. Lint and grep find about 200 dead lines in the launcher
and about 10 in the music app. Unintended exact duplication is small. The largest clone, about 380 lines of root
scripts in `install.sh`/`update.sh`, is deliberate and pinned by a test.

The cost is concentrated in a few places instead:
- Two Kotlin files that tests pin by their exact source text: `PlaybackService.kt` (333 pins) and
  `PinLockRuntime.kt` (189 pins).
- Four server files of about 2,000-4,400 lines that every feature touches.
- About 1,000 lines of per-feature handler and test boilerplate that a few helpers would collapse.
- The docs: 52k lines in `docs/`, and 69k words in the four CLAUDE.md files.

Recommendation: delete the safe set now (about 240 lines, one small commit per part). Merge the test helpers.
Leave the large modules alone until a feature next needs to open them, and decide the ten items in §9.

## 1. Summary

| Part | Tracked lines¹ | Production source | Tests | Test : code | Comment share (prod) | Fns > 150 lines (prod) | Dead, safe now | Dead, needs a decision | Unintended duplicates (removable) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `server/` | 113,358 | 47,269 Rust² + 6,279 templates/CSS/JS + 1,529 SQL + 1,711 deploy sh | 47,221 Rust (36,506 `src/tests/` + 10,715 inline `mod tests`) + 2 jstest files | 1.00 | 11% | 18 | ~50 | ~450 (+160 test) | ~250 prod + ~300 test + ~300 templates; +~380 intended (deploy) |
| `launcher/` | 73,979 | 41,861 Kotlin + 5,083 res XML | 18,792 Kotlin | 0.45 | 24% | 3 | ~190 | ~270 (+100 test) | ~40 prod; ~80 test (21 copies of one helper) |
| `music/` | 46,322 | 21,130 Kotlin + 431 res XML | 16,051 Kotlin | 0.76 | 24% | 0 | ~10 | ~30 | ~75 prod |
| `scripts/` | 4,097 | 2,761 Python (fakes) + 1,336 sh | each fake's `--selftest` (in CI) | - | - | - | 0 | 0 | ~150 (shared fake scaffolding) |
| `docs/` + CLAUDE.md/PLAN.md | 51,808 + 2,852 | - | - | - | - | - | - | 4 review/QA docs (~960 lines) | - |
| Intended copies (contracts, licences, wrappers) | 2,191 | | | | | | | | intended, guarded by tests |

¹ `git ls-files | xargs wc -l`: raw lines including blank lines and comments, binaries (fonts, images) excluded. The
repo total is 292,253. ² 57,984 lines in `server/src/*.rs` outside `src/tests/`, less 10,715 lines of inline
`#[cfg(test)] mod` blocks (brace-matched, `measure.py rtest`).

Largest production files (lines). Server: `handlers/music.rs` 4,517, `music_sweep.rs` 4,177,
`spotify.rs` 4,592 (1,928 of them inline tests), `handlers/tracked_apps.rs` 3,052, `music.rs` 2,851,
`handlers/devices.rs` 2,414, `handlers/device_api.rs` 1,925, `storytel.rs` 1,834, `main.rs` 1,303,
`window_relock.rs` 1,351. Launcher: `lock/PinLockRuntime.kt` 1,518, `server/AppEnforcer.kt` 1,302,
`server/MdmSyncWorker.kt` 996, `server/AppDownloads.kt` 912, `ui/HomeActivity.kt` 886, `lock/PinLockActivity.kt` 829.
Music: `spotify/SpotifyRenderer.kt` 1,991, `playback/PlaybackService.kt` 1,923, `sonos/SonosController.kt` 1,394,
`spotify/SpotifyTracker.kt` 740. Biggest tests: `server/src/tests/music.rs` 5,020, `music_spotify.rs` 4,327,
`music_sweep.rs` 3,161, `music_storytel.rs` 2,254. In the apps: `SpotifyRendererTest.kt` 1,620,
`SonosControllerTest.kt` 1,418 and `GuardsTest.kt` 1,140 (music), `PolicyResponseCompatTest.kt` 862 (launcher).

Longest production functions (`measure.py long`, brace-matched):
- Server, 18 functions over 150 lines: `main.rs:672 build_admin_router` 570 (the route table),
  `handlers/devices.rs:1275 render_device` 411, `music_sweep.rs:3691 finish` 372, `handlers/device_api.rs:978 status` 361,
  `handlers/music_import.rs:287 confirm` 322, `main.rs:168 main` 286, `handlers/music.rs:3679 device_card` 278,
  `handlers/calls.rs:248 call_report` 268, `device_api.rs:224 build_policy` 261, `music_sweep.rs:3422 art_pass` 260, ...
- Launcher: `AppEnforcer.kt:274 apply` 242, `MdmSyncWorker.kt:71 performMdmSync` 240 and
  `SettingsFragmentLauncher.kt:89 onCreatePreferences` 156.
- Music: none. The longest is `MusicRoot` at 138 lines.

## 2. Server

### S1. Nine unread struct fields and one struct never built (safe, S)

- Evidence: `cargo build` gives 9 warnings, all `dead_code`, all in `src/models.rs`. `cargo clippy --all-targets`
  gives 12: the same 9 plus 2 `doc_lazy_continuation` (`dns_engine.rs:54-55`) and 1 `sort_by_key`
  (`handlers/devices.rs:1457`).
  - `:270` `GlobalSchedule.{id, updated_at}`
  - `:284` `DeviceStatus.{id, device_id, kiosk_engaged, app_version_code}`
  - `:396` `TrackedApp.created_at`
  - `:437` `DnsFilterSettings.{id, updated_at}`
  - `:447` `ProvisioningSettings.{id, updated_at}`
  - `:465` `DnsBlocklist.created_at`
  - `:475` `DnsCustomDomain.{device_id, created_at}`
  - `:483` `struct DeviceBlocklistOverride` (never built; its table is read with tuple queries at
    `device_api.rs:618,859` and `dns_filter.rs:121`)
  - `:525` `DeviceCommand.device_id`
- Risk: none. `sqlx::FromRow` reads only the fields the struct declares, so `SELECT *` keeps working. The test target
  warns about the same fields, so no test reads them either.
- Recommendation: **remove now**. This is 27 lines, verified in §7. Fix the 3 clippy style warnings in the same
  commit.

### S2. A route no client calls: `POST /dns/blocklists/{id}/delete` (safe, S; see D4)

- Evidence: `main.rs:1105-1108` → `handlers/dns_filter.rs:297-310 delete_blocklist`. `routes.py` grepped all 175
  routes against the templates, static JS, server redirects, the launcher, the music app, scripts and CI. This is
  the only route with no caller. `dns_filter.html` has forms for `new`, `toggle` and `override`, never `delete`, and
  `git log -G` over the whole history finds no template that ever posted to it. No test calls it.
- Risk: none for reachability. It is the only way to delete a blocklist row, though, and only by a hand-crafted POST.
- Recommendation: delete the route and handler (14 + 4 lines), or add the missing button (D4).

### S3. `tokio-util` is a direct dependency nothing uses (safe, S)

- Evidence: `Cargo.toml:74-76` says "streams a cached APK from disk (ReaderStream)". There is no `tokio_util::` and no
  `ReaderStream` anywhere in `src/`. Downloads go through tower-http's `ServeFile` (`device_api.rs:1784`,
  `music_api.rs:201`). I grepped the other 39 crates: each has at least one use, and the dev-deps are used by
  tests only, as expected. The copy builds and tests pass without the line (§7).
- Risk: none. It stays in the tree through tower-http, so the binary doesn't change.
- Recommendation: **remove now** (3 lines with the stale comment).

### S4. `#[allow(dead_code)]` on `music_sweep::Listing` is broader than needed (safe, S)

- Evidence: this is the only `allow(dead_code)` in the crate, and there are no `cfg_attr` allows. `music_sweep.rs:774`
  says "`checked_at` and `requests` are for the log and the tests". `checked_at` is now read
  (`music_sweep.rs:1420`). With the attribute removed, the non-test build warns only about `requests` (`:802`), and
  the test build doesn't warn at all.
- Recommendation: replace it with `#[cfg_attr(not(test), allow(dead_code))]` on the `requests` field alone.

### S5. Retired features are gone from the code, except four leftovers

Grep over `server/src`, the templates, static files, the launcher, the music app and CI. FCM, `UnifiedPush`,
`MollySocket`, `LauncherUpdater`, `wifi_mode`/`bluetooth_mode`, the old Dashboard and the journal and browser-history
routes are gone: their names appear only in migrations, tests of the removal and one comment
(`tracked_apps.rs:1886`). What remains:

| Leftover | Where | Status |
|---|---|---|
| `launcher_releases` table | created by `migrations/0002_launcher_releases.sql`, never dropped | No code reads it (`cols.py`: `version_name` and `uploaded_at` are named nowhere in `src/`). server/CLAUDE.md:61 calls it "fully retired". D2. |
| `device_status.push_state_json` | `0026`, nulled by `0048` | "column kept, unused" (server/CLAUDE.md:160); only `tests/play.rs` names it. D2. |
| `device_status.kiosk_engaged`, `.app_version_code` | written at `device_api.rs:1110-1127` | Write-only: S1's unread fields are the only readers. The launcher still sends them (contract). D2. |
| One-shot cleanups | `main.rs:418-419` + `retention.rs:143-162` (`remove_journal_media`); `main.rs:323-327` (the `FCM_SERVICE_ACCOUNT_FILE` warning) | Needed only on a server first upgraded past 0.20. D3. |

The "frozen" pre-step-6 schedule columns are not dead: `build_policy` still sends them (`device_api.rs:244-279`) to
launchers without `time_rules_v1`, and `time_rules::migrate_legacy` (`time_rules.rs:192-405`, about 215 lines plus
about 160 lines of tests) converts them at startup. Whether this can go is D1.

### S6. Nothing else is unreferenced

- Every one of the 30 templates is used through `#[template(path)]` or `{% include %}`.
- Every static file is referenced. The 26 icons under `static/app-icons` and `static/music-icons` are referenced by
  key, from `app_icons.rs`/`music_icons.rs` and from `{{ icon }}.svg` in the templates.
- All 43 `testdata/` files are read by a test, and so are all 71 test resources in the two apps (the 27
  `sdk_states/*.json` through a directory walk in `SpotifyTrackerTest.kt:31`).
- `cols.py` found no other column that is never named in `src/`.
- `deploy/generate-icons.ps1` (92 lines) has no references, but it is the source of `static/icons/*.png`. Keep it.

### S7. Duplication

- **Root scripts in `install.sh`/`update.sh`** (`dups.py`, 8-line windows):
  - `install.sh:374-624` = `update.sh:217-467` (171 normalised lines)
  - `:110-230` = `:74-194` (95)
  - `:679-755` = `:513-589` (53)
  - `:628-670` = `:469-511` (31)
  - `:767-808` = `:591-632` (28)

  About 380 lines are copied, deliberately: `update.sh` rewrites the root scripts with `install.sh`'s text, and
  `tests/deploy_scripts.rs:30 update_sh_writes_the_same_root_scripts_as_install_sh` keeps the heredocs equal. A merge
  would ship the root scripts as files in the signed tarball and have both scripts install them. That saves the
  copies but changes the update path of every existing Pi. M, **leave** until the deploy scripts next change.
- **Update-one-policy-column handlers.** 17 handlers have the shape "UPDATE ... WHERE device_id; 0 rows → 404;
  nudge; redirect; error → 500" (`grep 'rows_affected() == 0'`: 7 in `handlers/devices.rs`, 4 in `music.rs`, 2 in
  `locate.rs` and 1 each in `lifts.rs`, `lock_glance.rs`, `schedules.rs` and `tracked_apps.rs`). Examples are
  `devices.rs:718-740` ≈ `:1002-1022` ≈ `:1043-1063`. `locate.rs:398-470` has four command handlers (ring, stop_ring,
  lock, locate) that differ only in the command name and the redirect. A `set_policy_column(...)` helper and a
  `queue_and_log(...)` helper would remove about 250 lines. Behaviour is covered by the HTTP tests. S-M, **merge
  when the file is next touched**.
- **Test helpers.** `async fn policy(app, token)` is copied into 13 files under `src/tests/` and `post_status`/`report`
  into 15 (`grep -n 'async fn policy(\|async fn post_status(\|async fn report('`). `step9/10/11.rs:9-32` are
  identical. Moving them to `TestApp` methods in `tests/mod.rs` saves about 300 lines. S, **merge now**: test-only and
  zero risk.
- **The `*_gate!()` macros** (`music.rs:967-989`) are always used as a pair at 4 call sites (`music.rs:1002-1004,
  1359-1361, 1371-1373, 1407-1409`; `music_sweep.rs:1087-1089`), and each site binds the phone twice. One
  `library_gates!()` would save about 8 lines and one bind per site. S, **leave**; worth it when a third gated
  source comes.
- **The per-source sweep checks** (`music_sweep.rs`: `nrk_check:1866`, `rss_check:2348`, `storytel_check:2534`,
  `spotify_check:2704`) already share `Job`/`Plan`/`CheckError` and one dispatch (`:1620-1645`). Each source talks to
  a different API, and the clone detector finds nothing between them. **Leave.**
- **System pages.** `system_maintenance.rs`, `system_update.rs` and `backups.rs` each parse `KEY=value` schedule files
  and write flag files (`backups.rs:62-74` ≈ `system_update.rs:136-148` ≈ `system_maintenance.rs:127-139`;
  `system_maintenance.rs:257-277` ≈ `system_update.rs:224-244`). That is about 60 lines. S, leave.
- **Templates.** None of the 30 page templates uses `{% extends %}`, so each repeats the same 9-line skeleton (about
  270 lines). The inline info-icon SVG appears 21 times and the chevron SVG 21 times
  (`grep -c 'class="info-icon"'`). The cards before and after design 31 are not duplicated: About and Advanced both
  have a "Connection" section with different content, and the device page shrinks one step at a time. A
  `base.html` and an `icon` partial would save about 300 lines. S, **do it in the next design-31 step**, which
  rewrites those pages anyway.

## 3. Launcher

### L1. Unused resources (safe, S)

- Evidence: `lintDebug` with `UnusedResources` forced on gives 48 hits. Grep over `src/{main,test,debug,release}`
  confirms 19 of them as truly unused, and a 20th becomes unused with L2:
  - `res/anim/{bottom_up,left_right,right_left,top_down}.xml` (46 lines)
  - `res/layout/dialog_choose_color.xml` (82)
  - 12 strings in both `values/` and `values-nb/` (24 lines): `calls_incoming`, `calls_on_hold`, `calls_ended`,
    the six `dialog_select_color_*`/`dialog_choose_color_title`, `dialog_cancel`, `dialog_ok`, and
    `toast_activity_not_found_browser` (orphaned once L2 goes)
  - `color/kid_icon_fallback` (`colors_kid.xml:29`), `dimen/app_icon_side` (`dimens.xml:4`) and
    `style/AlertDialogDanger` (`styles.xml:188-190`)
- The other 29 hits are false positives (after the deletions, lint reports exactly those 29): the `settings_*_key` strings are read by the kapt-generated
  `LauncherPreferences.java` (`build/generated/source/kapt/.../LauncherPreferences.java:135ff`), which lint doesn't
  scan. I also checked all 16 `mdm.*` preferences for use. The 152 `UnusedIds` are view-binding false positives.
- Recommendation: **remove now** (about 160 lines; `TranslationsTest` stays green because both locales lose the same
  keys, §7).

### L2. Two functions with no callers (safe, S)

- `Functions.kt:122-130 openInBrowser` and `ui/Helper.kt` (the whole 22-line file, `View.blink`, "Taken from
  stackoverflow"). `ktdead2.py`, which strips comments and counts declarations against references across the app,
  finds no reference to either, in `main`, `test`, `debug` or `release`.
- Recommendation: **remove now** (31 lines and two imports).

### L3. Probably dead, needs a decision

- **The minimalist set** (MIT code): `ui/list/apps/ContextMenuActions.kt:114` hides the "add to home screen" item
  ("Home's grid shows every app (design 05)"). So `toggleMinimalistApp` (`:40-58`) and the menu handler
  (`:125-137`) can no longer run, and `AppFilter.kt:27` reads a set that nothing writes any more. Together with
  `menu_app.xml:7` and the `minimalist` preference group that is about 45 lines. Deleting code from `ui/list/` and
  `apps/` keeps the MIT terms. D5.
- **FCM leftovers cleanup**: `push/FirebaseCleanup.kt` (77) + `push/FirebaseLeftovers.kt` (76) + its test (99), run
  once at `Application.kt:376`. They are only needed until every phone has started a design-19 launcher once.
  `push/FcmHosts.kt` is still live (other apps' FCM). D3.
- **The legacy schedule reader**: `KidModeEnforcer.kt:33-39` falls back to `legacyTimePolicy`
  (`timerules/TimeRules.kt:230-266`) when a server sends no `time_policy`. Every server since step 6 always sends it
  (server/CLAUDE.md:379). D1.
- **Production helpers only tests call**: `ktdead2.py` finds 15 in the launcher and 15 in the music app (the two
  `doWork` hits are its false positives). Examples are `Hardening.isSet` (`server/Hardening.kt:51`, 14 test uses),
  `PinKeypadLayout.fits/minHeightPx`, `TimeRules.localAt` and `MusicPlan.restrictionValueStorable`. Most are spec
  helpers, but a test that asserts on a function the app doesn't call can pass while the app is wrong. D9.

### L4. Duplication

- **The source reader in tests.** 21 test files define the same comment-stripping source reader,
  `Regex("/\\*.*?\\*/", DOT_MATCHES_ALL)` plus `Regex("//[^\\n]*")`: 19 in the launcher, and `SourceScanTest`
  and `GuardsTest` in the music app. One `SourceText.code(path)` per app is S, **merge now**.
- **Production code.** Exact clones are 42 lines, for example `calls/ContactPhotos.kt:169-183` ≈
  `ui/wallpaper/WallpaperStore.kt:373-387` (the same size-capped, hash-checked download). **Leave.**
- **The launcher and the music app.** `MusicPlan.kt:54-74 MusicRestrictions` mirrors the music app's
  `bridge/BridgeConfig.kt:34-52 RestrictionKeys`, and `SpotifyHostsTest` exists in both apps (44 lines). This is
  **intended**. The apps are separate Gradle builds, and `bridge_contract.json`/`spotify_hosts.json` (byte-identical,
  checked by `MusicPlanTest`/`BridgeTest`) pin the names. A shared module would need a third build. Leave.

## 4. Music app

### M1. Dead (safe, S)

- `logic/Spotify.kt:191 const val SPOTIFY_STATE_TICK_MS = 5_000L` has no reference (the doc line above it also
  covers `SPOTIFY_STATE_GAP_MS`, which stays).
- `sonos/SpeakerCache.kt:249-256 RoomDirectory.scanNow()` has no caller in `main`, `debug` or the tests;
  `rescanInBackground` is the live path. Fix the KDoc mention at `:149`.
- `lintDebug` with `UnusedResources`/`UnusedIds` forced on finds **no** unused resource (43 issues, none of them
  "unused"). `ktdead2.py` finds nothing else.

### M2. Probably dead, needs a decision

- **`SPOTIFY_PLAYABLE`** (`logic/Spotify.kt:34`) is a staging flag that is now always `true`. It is threaded as a
  default parameter through `data/Snapshot.kt:95,113` and `logic/NewItems.kt:29,37-39,110,112`, and only tests pass
  `false` (6 uses). That is about 10 lines of plumbing plus a dead branch. D7.
- **Wi-Fi locks**: `SonosLocks.kt:16,30` and `SpotifyLocks.kt:16,29` both say in their own comments that the
  `WIFI_MODE_FULL_LOW_LATENCY` lock is effective only with the screen on and the app in front, so on minSdk 34 it
  "does little". It is kept pending the Jelly Star screen-off run (design 26c §14 item 6). D6.
- **Every `Source` branch is reachable.** The server serves all five sources
  (`music.rs LIBRARY_ENTRIES`, `source IN ('nrk','rss','own','storytel','spotify')`). Audio focus and noisy handling
  exist only in `SpotifyRenderer.kt:1437-1500,1866-1930`: `LocalRenderer` leaves them to ExoPlayer, and
  `SonosRenderer` plays remotely. So the three renderers do **not** duplicate that code.

### M3. Duplication

- **`SonosLocks` and `SpotifyLocks`** (98 + 82 lines) share their structure: a PARTIAL wake lock and a Wi-Fi lock,
  `setReferenceCounted(false)`, a main-thread `Handler` renewal and a release on timeout. Their policies differ:
  per-owner holds renewed every 5 minutes for Sonos, one holder renewed by the 15 s save tick for Spotify. A shared
  `PlaybackLocks(tag, timeout)` would save about 60 lines, but it would put Sonos and Spotify's QA-specific
  behaviour behind one class. S, **leave**.
- **The command sets**: `sonos/SonosCommands.kt:12-25` and `SpotifyRenderer.kt:1799-1811` share 11 of their
  `Player.COMMAND_*`. S, leave.
- **The text pins.** `GuardsTest` and `SourceScanTest` don't duplicate each other: of the music tests' literals of
  15+ characters, only one is pinned in both (`"log = ::spotifyLog,"`). There are 4 overlaps between `GuardsTest` and
  `EpisodeCarouselTest`, all on `PlaybackService.onCustomCommand`. The cost is in their number, not their overlap (§6).

## 5. Scripts and docs

- **Scripts.** Every `scripts/*` is referenced: the four fakes' `--selftest` run in `music.yml:106-121`;
  `dev-rebuild.sh`, `push-all.sh`, `smoke-test.sh` and `material-symbols.sh` are named in docs or CLAUDE.md. The
  root CLAUDE.md "Layout" list leaves out `fake-spotify.py` and `fake-audio.py`, which is doc drift.
- **Scaffolding shared by the fakes.** `log`, `fixture`, `wav_header`, `duration_ms`, the Range/206 server, `serve`
  and argparse add up to about 150 normalised lines across `fake-audio.py`, `fake-storytel.py`, `fake-spotify.py`
  and `fake-sonos.py` (`dups.py`, 4-line windows). `wav_header` is copied verbatim in audio and storytel and
  rewritten with `struct` in spotify. The fakes run under `python3 -I`, which keeps the script directory off
  `sys.path`, so a shared module needs a `sys.path` insert. **Leave**; reconsider at a fifth fake.
- **Docs that no other file references** (`git grep` on the basename):
  - `docs/design/qa-monorepo.md` (14 lines)
  - `docs/review/launcher-architecture.md` (272 lines; a review of the pre-monorepo fork at `946ff1f`, 8.7k lines of
    Kotlin then)

  **Superseded by later docs:**
  - `docs/review/server-architecture.md` (350; fork at v0.18.6, cited once, from `02-calls.md:267`)
  - `docs/review/qa-33-code.md` (321; its own banner says "Superseded ... findings are about code that is gone")
  - `qa-security.md` is superseded in part by `security-review-2026-10-10.md:255-267`, but is still cited by PLAN.md
    and 9 other files: keep it.

  `03-tailscale.md` and `07-battery-fcm-play.md` are marked "superseded in part" and are still cited. Decision: D8.
- **Doc volume.** `docs/` has 154 design files (46,954 lines), 93 of them QA reviews (17,611 lines). The four
  CLAUDE.md files hold 69,477 words (launcher 33,389, server 28,146) and are read into every agent session.
  `server/CLAUDE.md` alone took 113 commits in the 8 days the shallow worktree covers, more than any source file. D8.

## 6. Maintenance cost: the ranked list

Score = production lines × churn × (1 + pins/20) / 1000 (`cost.py`). The weighting is a judgement: one text pin is
worth 5% more cost, because each pin has to be re-pinned by hand when the code around it moves.

| # | File | Prod lines | Churn | Pins | Score | Longest function |
|---|---|---:|---:|---:|---:|---|
| 1 | `music/.../playback/PlaybackService.kt` | 1,923 | 53 | 333 | 1,799 | 98 functions; `KidPlayer` `:431-561` |
| 2 | `launcher/.../lock/PinLockRuntime.kt` | 1,518 | 56 | 189 | 889 | `init` 117 |
| 3 | `server/src/handlers/music.rs` | 4,427 | 33 | 30 | 368 | `device_card` 278 |
| 4 | `server/src/handlers/devices.rs` | 2,396 | 72 | 6 | 226 | `render_device` 411 |
| 5 | `server/src/handlers/device_api.rs` | 1,925 | 77 | 3 | 172 | `status` 361, `build_policy` 261 |
| 6 | `server/src/models.rs` | 1,208 | 80 | 13 | 160 | (structs) |
| 7 | `launcher/.../server/AppEnforcer.kt` | 1,302 | 69 | 13 | 149 | `apply` 242 |
| 8 | `launcher/.../ui/HomeActivity.kt` | 886 | 53 | 40 | 140 | `bindContent` 93 |
| 9 | `server/src/main.rs` | 1,303 | 83 | 4 | 131 | `build_admin_router` 570 |
| 10 | `launcher/.../server/MdmSyncWorker.kt` | 996 | 65 | 16 | 118 | `performMdmSync` 240 |

Where the pins sit: 538 of the 1,527 pins found are in `GuardsTest.kt`. `PinLockRuntime.kt` is read by 11 launcher
test files (`BootMarkPlanTest` 72 pins, `KeyguardCoverPlanTest` 63, `LockUpPlanTest` 63, `LockUpTest` 54, `HomeGateTest`
51, ...). The server's pins in `handlers/music.rs` are mostly user-facing strings the HTTP tests read from rendered
pages. Those survive a move.

1. **`PlaybackService.kt`: the most expensive file in the repo.** It holds 9 concerns in one class: KidPlayer
   gating, starting a queue, restore, episode art, transport, shuffle/sleep/outputs, events, calls and the renderer
   switch (sections at `:562, 966, 990, 1048, 1153, 1244, 1584`). `GuardsTest` pins its code by
   `substringAfter("private inner class KidPlayer")` plus exact lines such as
   `"override fun play() {\n            if (playAllowed(suspendedNow())) super.play()"` (`GuardsTest.kt:27-45`).
   - *Refactor buys:* moving the call rule (`:1244-1583`, about 340 lines) and the renderer switch (`:1584-`) into
     their own classes would cut the file by about 40% and stop every playback feature from touching it.
   - *Refactor costs:* re-pinning most of the 333 pins, which tie each guard (no play while suspended, the call
     rule) to its exact place. Moving code without moving its pin makes `GuardsTest` fail, not pass silently, so
     safety holds, but each pin needs a hand edit. M-L.
   - *Worth it now?* **No.** Do it when design 26c §14's device run next forces changes here. First convert the
     pins guarding a *rule* (the 40 `@Test`s that say "nothing plays while suspended" and similar) into behaviour
     tests on a fake `Player`, where Media3 allows it. That is D9.
2. **`PinLockRuntime.kt`.** A 1,518-line `object` with 78 functions in 8 sections: start-up, boot mark, events,
   configuration, showing, unlocking, remote lock and helpers.
   - *Refactor buys:* the boot mark (`:320-541`, design 16e) and the events (`:542-1002`) are the two halves most
     features touch.
   - *Refactor costs:* the 189 pins across 11 test files. This is the lock, so a wrong move is a security regression.
     L.
   - *Worth it now?* **No.** Leave it and keep new lock features out of it (new files beside it, as
     `KeyguardCover.kt` and `LockTaskChrome.kt` already are).
3. **`server/src/handlers/music.rs`.** 4,427 production lines and 35 `pub` handlers for five pages: the music page
   (`music_page` 203), the entry page (`render_entry` 140, `save_entry` 125), uploads (`upload_files` 157,
   `keep_upload` 136), Storytel (`add_storytel` 223) and the device card (`device_card` 278,
   `spotify_binding_card` 127).
   - *Refactor buys:* a move-only split into `handlers/music/{page,entry,uploads,storytel,device_card}.rs`. Smaller
     diffs, fewer merge conflicts between music branches (three ran in parallel, 26a/b/c).
   - *Refactor costs:* small. The pins are rendered strings and survive; `main.rs`'s route paths change once. S-M.
   - *Worth it now?* **Yes, if no music branch is open.** It is the one large refactor here that costs little.
4. **`server/src/handlers/devices.rs`** (72 commits) and **`device_api.rs`** (77 commits) are the device page and the
   phone contract. `render_device` (411 lines) builds the whole old device page, which design 31 is taking apart
   step by step (About, Advanced and Apps are done), so this file shrinks without a dedicated refactor. In
   `device_api.rs`, `status` (361 lines) reads 21 report fields and runs 8 queries in one function. Splitting it into
   `store_<section>(&mut tx, &report)` helpers would make each new status field a 10-line change instead of an edit
   in the middle of the function. The contract is pinned by `policy_json_keys_snapshot`/`PolicyResponseCompatTest`
   and the status tests, so the refactor is safe. M. **Worth it the next time a status field is added**, not before.
   The S7 handler helper takes about 150 lines out of `devices.rs` for an S.
5. **`main.rs` route table** (`build_admin_router`, 570 lines, 83 commits). Every feature adds routes here, so it is
   the most conflict-prone file in parallel branches. `device_routes.rs` already shows the pattern: per-area
   `fn music_routes() -> Router<AppState>` and so on. S, **worth doing together with item 3**.
   `tests/listeners.rs:17` reads `main.rs` as text, so check that test's pins first.

## 7. Verification of the safe set

Applied to scratchpad copies (`git archive d23038a`), never to the worktree:

- **Server**: S1 (27 lines in `models.rs`), S2 (route + handler) and S3 (the `tokio-util` line).
  - `cargo build`: 1 warning left, `Listing.requests`, which S4 handles.
  - `cargo test --no-run`: compiles.
  - `cargo test`: **827 passed, 0 failed**.
- **Launcher**: L1 + L2 (with M1 below: 14 files, 202 lines removed).
  - `assembleDebug testDebugUnitTest lintDebug`: builds; **1,146 tests, 0 failures**; lint's remaining `UnusedResources` are exactly the 29 kapt false positives.
- **Music**: M1.
  - `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: builds with no warnings; **844 tests, 0 failures**.

## 8. Safe to delete now

Each of these has zero references, proven by the compiler, lint or grep over every source set, and was re-tested in
§7.

- [ ] `server/src/models.rs`: the 9 unread fields (`:270, 284, 396, 437, 447, 465, 475, 525`) and
      `DeviceBlocklistOverride` (`:479-488`), 27 lines.
- [ ] `server/Cargo.toml:74-76`: `tokio-util` and its stale comment (Cargo.lock updates itself).
- [ ] `server/src/music_sweep.rs:774`: narrow the `allow(dead_code)` to `#[cfg_attr(not(test), allow(dead_code))]`
      on `requests`.
- [ ] Clippy's 3 style warnings (`dns_engine.rs:54-55`, `handlers/devices.rs:1457`).
- [ ] `server/src/main.rs:1105-1108` + `handlers/dns_filter.rs:297-310`: `POST /dns/blocklists/{id}/delete`
      (unless D4 adds the button).
- [ ] `launcher/.../Functions.kt:122-130` `openInBrowser` and its two imports; `launcher/.../ui/Helper.kt` (whole
      file).
- [ ] `launcher/app/src/main/res/anim/{bottom_up,left_right,right_left,top_down}.xml` and
      `res/layout/dialog_choose_color.xml`.
- [ ] Launcher strings, in `values/` and `values-nb/`: `calls_incoming`, `calls_on_hold`, `calls_ended`,
      `dialog_select_color_{red,alpha,blue,green,color_hex}`, `dialog_choose_color_title`, `dialog_cancel`,
      `dialog_ok`, `toast_activity_not_found_browser`; `color/kid_icon_fallback`, `dimen/app_icon_side`,
      `style/AlertDialogDanger`.
- [ ] `music/.../logic/Spotify.kt:191` `SPOTIFY_STATE_TICK_MS`; `music/.../sonos/SpeakerCache.kt:249-256`
      `scanNow` (and the KDoc mention at `:149`).
- [ ] Root CLAUDE.md "Layout": add `fake-spotify.py` and `fake-audio.py` (doc drift, not code).

Merge now (test-only, no behaviour change):
- [ ] `server/src/tests/`: one `TestApp::policy`/`TestApp::post_status` in place of 28 local copies (about 300
      lines).
- [ ] One comment-stripping source reader per app in place of 21 copies.

## 9. Decisions for the user

- **D1. The pre-step-6 schedule.** Is any launcher older than handy step 6 (no `time_rules_v1`), or any server older
  than step 6, still in use? If not:
  - Server: drop the legacy fields from `PolicyResponse` (`models.rs:762-767`) and `build_policy`
    (`device_api.rs:244-279`), and `migrate_legacy` and the legacy types (`time_rules.rs:192-405`).
  - Launcher: drop `legacyTimePolicy` (`TimeRules.kt:230-266`) and the fallback (`KidModeEnforcer.kt:33-39`).

  That is about 300 production lines and 160 test lines. It is a contract change, so both directories go in one
  commit with `policy_json_keys_snapshot`/`PolicyResponseCompatTest` updated. Keep the columns and stop reading
  them, unless a migration also drops them (the migration hazards are in server/CLAUDE.md "Data model").
- **D2. Vestigial schema.** Drop `launcher_releases` (`0002`; nothing reads it) and `device_status.push_state_json`?
  Stop storing the write-only `kiosk_engaged`/`app_version_code` (the launcher keeps sending them; the server can
  ignore them)? Each needs a new migration and a restore-compatibility check. Low value, low risk.
- **D3. One-shot cleanups.** Are all phones on a design-19 launcher, and all servers past 0.20? If yes, delete:
  - `FirebaseCleanup.kt`/`FirebaseLeftovers.kt` and their test (about 250 lines)
  - `retention::remove_journal_media` and its call
  - the `FCM_SERVICE_ACCOUNT_FILE` warning
- **D4. Blocklist delete.** Add a Delete button to `dns_filter.html`, or delete the unreachable route (S2)?
- **D5. Minimalist leftovers in the MIT code.** Delete `toggleMinimalistApp`, the hidden menu item, the `minimalist`
  preference group and `AppFilter`'s `pinned` path (about 45 lines)? Check first whether an upgraded phone may still
  hold a non-empty set that changes the drawer's order.
- **D6. Wi-Fi locks** in `SonosLocks`/`SpotifyLocks`: drop them after the Jelly Star screen-off run (26c §14 item 6)
  if the stream doesn't stall. Their own comments say they do little on API 34+.
- **D7. `SPOTIFY_PLAYABLE`.** The phase is finished: inline `true` and drop the parameter and the tests' `false`
  cases?
- **D8. Docs.** Do the following, or leave history where it is:
  - Archive (move to `docs/archive/`, or delete) `launcher-architecture.md`, `server-architecture.md`,
    `qa-33-code.md` and `qa-monorepo.md`.
  - Do a pruning pass on `launcher/CLAUDE.md` (33k words) and `server/CLAUDE.md` (28k): move incident history into
    the design docs and keep rules and current state. They cost tokens in every session, and `server/CLAUDE.md` is
    the most-edited file in the repo.
- **D9. Text pins.** Accept them as the price of guarding Android-only code that JVM tests can't run, or set a rule:
  - A new guard is a behaviour test where the code can be reached (extract the decision as a pure function, as
    `*Plan.kt` already does).
  - A source pin is used only for "this call is there / that call is absent".

  This decides how expensive items 1 and 2 of §6 stay. Related: production helpers that only tests call (L3)
  should either be called by the production path or move to test sources.
- **D10. Refactor timing.** Split `handlers/music.rs` and the `main.rs` route table into per-area modules now,
  while no music branch is open, or leave them? Both are move-only and cheap (§6 items 3 and 5). Everything else
  in §6 waits until a feature opens the file.
