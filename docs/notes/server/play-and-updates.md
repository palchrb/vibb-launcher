# Server notes: play-and-updates

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## FCM removed, SSE only (design 19, `docs/design/19-remove-fcm.md`, at the monorepo root; server 0.20.0)

- **Gone**: `src/fcm.rs`, `src/push.rs` (sender, dispatcher, health, acks, test nudges), `AppState.fcm`, policy `push`,
  the status `push` (an old launcher still sends it: accepted with 204, stored nowhere - no `deny_unknown_fields`;
  `fcm_push_v1` in `capabilities` is stored as reported and read by nothing), the card's FCM lines, the direct deps
  `base64`/`ring`, the dev-dep `tokio/test-util` and the fixture PEM. No vestigial `push` key: every shipped launcher
  treats a missing key like `fcm_enabled: false` (SSE). `FCM_SERVICE_ACCOUNT_FILE` is ignored (its startup warning went in 0.54.0).
- **Migration `0048_remove_fcm.sql`**: `DROP TABLE device_push` (the installation IDs were credentials to message a
  phone), `device_status.push_state_json` set to NULL (column kept, unused). Revoking the service-account key is what
  makes stored IDs (freed pages, backups) useless (DEPLOY.md, "Removing FCM"). 0.19 refuses a database with 0048:
  rollback = the `update.sh` backup.
- **Keepalive**: `SSE_KEEPALIVE_SECS` default 240 (= `MAX_SSE_KEEPALIVE_SECS`; shipped launchers read with 300 s),
  invalid values fall back to 240; `.env` `SSE_KEEPALIVE_SECS=120` reverts. A proxy in front needs a read timeout
  >= 300 s (`tailscale serve` streams unbuffered).
- **The stream count** (Q1, `src/streams.rs`): `commands_stream` takes a `StreamGuard` into the response stream;
  `CommandStreams` keeps per device the open count and when it last changed (opened, or the last one closed), in
  memory only - no DB, no contract change. It feeds `connection_state` (design 31 §3.10): About This Phone's Status
  row says "Online since 08:12" while a stream is open and the phone was in contact within the hour (a silently dead
  peer counts until this server's TCP gives up, so Online also needs the contact). The "Instant changes" line is gone
  (design 31 step 3).
- Tests: `src/tests/play.rs` (old launcher's `push` dropped, migration 0048, the stream count on About, plus
  step 7's Play/DNS/keepalive tests), `streams::tests`; `policy_json_keys_snapshot` has no `push`. `TestApp::new`
  installs the rustls provider like `main` (the FCM tests used to, for the DNS upstream test).

## Play (handy step 7, design `docs/design/07-battery-fcm-play.md`, at the monorepo root; its FCM half removed in 19)

- **Play** (`src/play.rs`): `PLAY_CORE` (vending, gms, gsf) is the launcher's business - never listed on the phone's Apps page, `toggle_app` answers 400 for it, and the first-heartbeat allowlist bootstrap leaves it out. One source per package: turning on a catalog app whose package the phone reports installed by `com.android.vending` writes nothing and returns to its row with the reason (`/devices/{id}/apps?notice=from_play&app=<row>#<row>`; a 409 before design 31 step 6). Security log: `play_install_mode` (a new `install_mode.until_ms`), `app_installed` (a package new since the previous report, with its installer; not on the first report). The device page's "Play and kiosk" card ("Push and Play" until design 19) shows the "Instant changes" line, install mode, the nightly update window and the kiosk app block switch.
- **Battery (design 19b phase 1, migration `0055_battery_exemption.sql`)**: status `battery_optimization_exempt` (`Option<bool>`, `#[serde(default)]`) is stored in `device_status.battery_optimization_exempt` (NULL from an older launcher or when unreadable); About This Phone shows a "Battery optimisation" row for true ("Off") and false ("On", the fix behind its ⓘ), nothing for NULL. No policy key, no capability.
- **DNS**: `dns_blocklist` never delivers the exact FCM hosts (for other apps' FCM - Element X - since design 19) (mtalk*, alt*-mtalk, fcm/fcmtoken/fcmregistrations/firebaseinstallations/android.googleapis.com, android.apis.google.com, android.clients.google.com) as blocked (`play::is_fcm_protected`, QA 07 #10). Parents (`google.com`, `googleapis.com`) **are** delivered as set - dropping them opened all of `*.google.com` (fix round #1); the launcher exempts the exact FCM hosts before its suffix walk. Spotify's hosts get the same split **per phone**, only while it is bound to a Spotify account (design 26c, "Vibb music: Spotify on the server"). Every DNS write (upstream, blocklist create/toggle/delete, custom domain, device override) nudges the affected devices; the hourly blocklist refresh doesn't - phones get it with the 30-min backstop. A new catalog release does ("Catalog downloads", "New releases nudge the phones").
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

## Kiosk escapes (handy step 11, design `docs/design/11-kiosk-escapes.md`, at the monorepo root)

- **Migration `0036_kiosk_escapes.sql`**: `device_policy.update_fence` and `notification_auto_cancel` (both **default 0**: the fence stays off until the A3 device check passes on the Jelly Star, the notification filter until B5), `device_status.update_fence_json`, `notification_cancels_json`. **Since 2026-10-21 a new phone starts with the notification filter on** (the user: "Varselfilter kan gjerne være på som standard"): `devices::insert_device_with_policy` inserts `notification_auto_cancel = 1`, the pattern of `quick_controls_mask` (the column default stays 0 - SQLite can't change it in place, and no migration was needed), so every phone created before keeps its stored value (`a_new_device_starts_with_all_kid_settings_on`).
- **Migration `0045_boot_cover.sql`** (design 16b): `device_policy.boot_cover`, **default 0** - the launcher's boot cover, a third checkbox on the same card ("Test it on this phone first"); `PolicyResponse.boot_cover` always sent, missing = off on the launcher. The form returns to `/devices/{id}/advanced#updates` (Advanced › Updates & notifications since design 31 step 3). **Migration `0046_boot_cover_state.sql`**: `device_status.boot_cover_json` - the phone's `boot_cover` report (`kiosk_escapes::BootCoverState`, known fields only, hand-over "cover"/"launcher"), capability `boot_cover_v1`; the card warns about an older launcher, a tripped crash guard (switch off and on to retry) or an arm not shown at the next start.
- **Wire**: `PolicyResponse.update_fence` and `notification_auto_cancel`, always sent (`policy_json_keys_snapshot`); a launcher on an older server treats both as off. Advanced › Updates & notifications (the device page's "Launcher updates and notifications" card until design 31 step 3; `POST /devices/{id}/kiosk-escapes`, one auto-saving form: a missing checkbox is off, 404 unknown device, nudges) with the privacy text behind its footer's ⓘ (since the ⓘ cut, pwa.md: the filter keeps allowed apps, calls, alarms and emergency alerts and never reads a notification's text; the listener itself reads only app, channel and type and reports only names and counts).
- **Status** (`src/kiosk_escapes.rs`, pure + unit tests): `update_fence` (`enabled, state, unsuspendable, last_release, home_role_held, pending_tag, pending_since_ms, waiting_for, last_fenced_at_ms, last_released_at_ms`) and `notification_cancels` (`active, entries[{package_name, channel, cancelled, snoozed}], dropped`) are stored **re-serialized through the known fields** like `lock_state`: lists <= 20 entries (extra counted in `dropped`), ids cut to 64 chars, counts never negative - never notification text. `escapes_card` and `boot_cover_notes` return typed `EscapeNote`s (design 31 M5; `EscapeNote::note` words them as a warning above the group or a row in it) for a fence that is up, packages Android refused to pause, a partial fence (no ROLE_HOME), the last release (a failed install is a warning), a downloaded update waiting for the night window (and why), missing notification access, and what the filter removed. Capability `kiosk_escapes_v1` (a reporting launcher without it gets "update the launcher").
- **Fix round (qa-11-code.md #5, #8)**: `update_fence` also keeps `last_fenced_at_ms`/`last_released_at_ms` (the phone's last-fence summary, since the live record never reaches a report); a fence reported more than 15 min ago reads "started at ... and the phone hasn't reported since", never "right now"; `last_release` "orphan" (a lost record swept on the phone) and "commit_failed" are warnings like "install_failed". Phone-supplied times are clamped to 1970..2100 and counts to 0..1e6 when sanitizing, and the card uses saturating arithmetic (tested with `i64::MIN`/`MAX`).
- Tests: `src/tests/step11.rs`, `kiosk_escapes::tests`.

## Catalog downloads (2026-10-06, `handlers/tracked_apps.rs`, migration `0042_tracked_app_asset.sql`, QA `docs/design/qa-catalog.md`)

- **Asset filter**: blank = first `.apk`; a pattern starting with `^` is a regular expression on the asset name
  (`regex` crate, `AssetFilter`, compiled size <= 1 MiB), e.g. `^\d+\.apk$` for Element X's universal APK (named after
  its versionCode); any other pattern is a substring, as before. Whatever the filter, only an `.apk` (any case) is
  picked - an `.aab` passes the ZIP check but can't install. The add and edit forms refuse an invalid or too complex
  regex and any filter over 256 chars (400, nothing written, the entered values kept, the error by the field and that
  field `autofocus`ed). Both forms post to their page's own path (`/apps/tracked/new`, `/apps/tracked/{id}` - there
  is no `/edit` any more), so the 400 page keeps the scroll position.
- **The app page** (Source & Signing since design 31 step 11) shows the cached file, "202609040.apk (326.1 MB)" (`tracked_apps.latest_release_asset_name`/`_size`,
  set by a sync or an upload; rows synced earlier show nothing until the next sync).
- **One sync per app** (`AppSyncs`, in memory, `AppState.app_syncs`): `sync_app` takes the app's guard or answers
  "already running"; the row is read under it, so a slower sync can't roll a newer cached release back. "Check now"
  starts the sync in its own task, waits up to 10 s, then redirects back to the app's page, which says a check is
  running or shows how the last one (hourly or manual) failed (a short title from `SyncProblem`, the raw error behind ⓘ). The hourly loop skips an app that is syncing.
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
- **Touch sounds and vibration** (launcher round 9 §4, `docs/design/r9-launcher-glance-small-items.md`):
  `QUICK_CONTROL_TOUCH` = 16, the fifth switch of Home Screen's Quick controls (`quick_control_touch`, the same
  `data-autosave` form, back to `#quick-controls`), **not** in `DEFAULT_QUICK_CONTROLS`: the kid's two switches
  write Android's settings only with the "Modify system settings" app-op the clock format uses, so the parent turns
  it on after granting it (the group's ⓘ says so). Policy shape unchanged (one integer). Tests:
  `sound_mode::the_touch_bit_saves_reaches_the_phone_and_starts_off`, `device_pages::home_screen_holds_the_moved_forms`.
- **Any Bluetooth Device** (security review S-L5, launcher round L4b, migration `0124_bluetooth_any_device.sql`):
  `device_policy.bluetooth_any_device`, default 0, a switch right under Bluetooth in the same Quick controls form
  (`bluetooth_any_device`, `section=quick` only - the filter's form keeps it -, unticked = off, back to
  `#quick-controls`), sent as the policy's top-level `bluetooth_any_device` (always; `policy_json_keys_snapshot`,
  the launcher's `PolicyResponseCompatTest`). Off: Kid Settings' Bluetooth page lists and pairs only headphones and
  speakers. Test: `bluetooth_pairing`.
- **Sound mode** (design 18, `src/sound_mode.rs`, migration `0047_sound_mode.sql`): `QUICK_CONTROL_SOUND` = 8, a switch in the
  Quick controls group of Home Screen (it only shows the kid's Sound / Silent (vibrate) row; the volume keys still work). Status
  `ringer_mode` / `interruption_filter` (`#[serde(default)] Option<String>`, only known values stored, else NULL) ->
  `device_status`, shown on About This Phone › Sound (Do Not Disturb, while on, is an Attention alarm). Policy shape unchanged. Tests:
  `sound_mode::tests`, `src/tests/sound_mode.rs`.
- **A catalog app installed on a phone is allowed and the phone nudged** (`device_api::allow_installed_catalog_apps`,
  every status report; it replaced the package-name backfill, which guessed the package from the one new package in a
  report): the nudge (`command_notify`, the SSE stream) matters, the phone otherwise kept the app hidden and suspended
  until its next backstop sync (found on the emulator).
- **New releases nudge the phones**: once a sync stores a new `(tag, asset id)` or an upload is stored (after the
  pins), `device_api::nudge_phones_with_app` sends `command_notify` to every enrolled phone whose list carries the app -
  `scoped_apps`' own query per phone (ticked, or every phone for the launcher; a disabled app none) - so the phone fetches
  `/api/devices/apps` in that sync, not at its 30-min backstop; its Wi-Fi and night-window gates are unchanged. A sync
  that finds what's cached, a refused release and a failed sync nudge nothing (`a_new_release_nudges_the_phones_that_get_the_app`,
  `catalog_pins` for the upload).
- Tests: `tracked_apps::tests`, `src/tests/tracked_apps.rs`, `a_new_device_starts_with_all_kid_settings_on`.

## App downloads: Wi-Fi only, resumable (design 13, `docs/design/13-app-downloads.md`, migration `0043_app_downloads.sql`)

- **Switch**: `device_policy.app_updates_wifi_only` (default 1, existing phones too) -> `PolicyResponse.app_updates_wifi_only`,
  always sent (`policy_json_keys_snapshot`). "Download apps on Wi-Fi only" on the phone's Apps page, `POST /devices/{id}/app-updates`
  (auto-save, back to `/devices/{id}/apps#downloads` - its group at the bottom of a long page -, nudges the phone). The phone: catalog apps only on an unmetered
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
  fields only, capped). The rows of the phone's Apps page show it, times in Phone Setup's zone (design 31 step 6:
  "Waiting for Wi-Fi · since 10:58 · 120 of 326 MB", "Installed · update waiting for Wi-Fi · since ...", the
  launcher's "Installed · update 0.32.0 waiting for Wi-Fi · since ... · any network from Mon 14:30") plus a
  "Network · Mobile data · 10:58" row (at the last report); a fresh progress row (< 10 min) still wins for the live
  percentage - except over a release the phone refused (`refused_*`, security review M10: "Refused: signed with
  another key", "Installed · update refused: ...", styled as failed), which it reports until the server lists
  another release.
- Tests: `app_downloads::tests`, `src/tests/tracked_apps.rs` (`downloads_resume_with_range_and_if_match`,
  `only_this_devices_apps_download`, `the_list_carries_each_files_hash`, `the_app_update_switch_saves_and_reaches_the_phone`,
  `app_downloads_are_stored_and_shown`, `a_refused_release_says_why_on_the_device_page`).

## Install Now (design 37, `docs/design/37-install-now.md`, no migration)

- **What**: the launcher's page under Apps (`/apps/tracked/{id}` with `is_launcher`) has an **Install Now** button per
  set-up phone that is behind. One tap queues a one-time `device_commands` row `install_update`
  (`install_now::COMMAND`) through `handlers::install_now::queue_install` - **one transaction** for the refusal check
  and the insert: its first statement cancels a waiting Install Now as `replaced` (a write, so the policy's pop can't
  run between the check and the insert), a refusal rolls it back; the receipt, the redelivery and the stream's nudge as
  every command -, `payload` = the row's
  release as the phone sees it (`device_api::device_release_tag`, `tag@asset_id`). The pop sends it as
  `pending_command.release_tag` **for `install_update` only** (`allow_blocked`'s JSON payload stays `allow`); both are
  `skip_serializing_if`, so the policy's keys are unchanged (`policy_json_keys_snapshot` untouched; the launcher's
  `PolicyResponseCompatTest` decodes it with and without). Not an expiring command (not in `EXPIRING_COMMANDS`): a
  server start leaves it waiting, an enrollment cancels it like any undelivered row. `models::command_label` "Install
  Now"; `received_overdue_minutes` makes a Received `install_update` "No answer" only after 25 h
  (`INSTALL_UPDATE_RECEIVED_MINUTES`), every other command after 10 minutes.
- **The row** (pure `src/install_now.rs`, typed): `launcher_tag_version_code` (the launcher's `launcherTagVersionCode`,
  both reading the shared `testdata/launcher_tag_codes.json`: since 2026-10-23 a tag from `launcher-v0.36.0-rc.4` on is
  `X*10_000_000 + Y*10_000 + Z*10 + N`, N the RC number 1..8 or 9 for the release - every build its own code,
  `scripts/release-version.sh` -, an older tag the old rule's `X*1_000_000 + Y*1_000 + Z`, an RC one less, as the
  phones on those builds report it; a report below 360004 - an old-rule build - gets every tag read by the old rule,
  as its own parser does, `tag_code_as_read_by`, so rc.1/rc.2 phones are TooOld for rc.4 to rc.8, qa-versioncode-rc-code
  Q-1), `release(tag, report)` -> `Standing` NotReported / NoRelease (no release, or the row is off)
  / UpToDate / Behind (`build_ahead`, the launcher's `launcherBuildAhead`: the tag's code above the report's
  `app_version_code`, or - legacy of the old rule, for builds tagged before `launcher-v0.36.0-rc.4` - the same code and
  the tag's name - `release_name`, "0.36.0-rc.2" - another, newer one than
  the report's `app_version`: every RC of one version shared a code, so rc.2 is behind-worthy over rc.1, never rc.1
  over rc.2; two names that can't be ordered count as ahead; or a `pending_tag`;
  a manual upload's label is behind unless the phone runs a build of that name). Ahead by the name alone (the same
  code) is Behind only when the report claims `same_code_update_v1` (`install_now::SAME_CODE_CAPABILITY`, the
  launcher's `SAME_CODE_UPDATE_CAPABILITY`), else TooOld: a launcher from before the RC-to-RC fix decides by the code
  and would answer "Already on X" (qa-rc-to-rc-code R2R-1) / TooOld (behind without capability `install_now_v1`); `offer`
  adds the newest `install_update`: Asked (Waiting, with Cancel; Sent - not lost - without) and Working (Received, no
  answer, under 25 h) - and Working "Installed - waiting for the phone's report" while the newest answer is
  `installed: X` and the latest report is older than it (`awaiting_report`, at most an hour; qa-37-code M1: in one sync
  the launcher answers before it reports, so the row would read "behind" with Install Now; the poll goes on, a POST
  answers `in_progress`). `working_words` reads the phone's report only when it came after the receipt (both server
  times; else "The phone got it (14:02)"): `update_fence.waiting_for` `install_now_download` + the launcher's
  `app_downloads` entry ("Downloading 40 of 80 MB", "Waiting for a network", "Waiting: not enough space", "Waiting: the
  phone is roaming"), `install_now_call` "Waits for the call to end", `install_now_emergency` "Waits: an emergency call
  was recent", `install_now_ready` "Installing". An answer, a Cancel, "No answer" and a lost delivery give the button
  back; the newest answer shows for 24 h as a `details.info` under the row ("Install Now · 14:31 · Done", the reworded
  line inside - `locate::command_row`, now `pub(crate)`). `refusal` is the POST's rule.
- **Routes** (`handlers::install_now`, full auth, plain forms, 404 unless the app is the launcher):
  `POST /apps/tracked/{id}/install-now/{device_id}` queues only when the row offers it (`device_command_queued`
  "install_update <tag>, device N"), else answers `up_to_date`, `too_old`, `no_release`, `not_reported`, `in_progress`
  (fetched or confirmed - a waiting one is replaced), `not_set_up`, `unknown_phone`; `POST
  .../install-now/{device_id}/cancel/{command_id}` - `locate::cancel_waiting` (the Locate tab's `cancel_command` moved
  into it, `only` = the kind) -> `cancelled` / `too_late` (`device_command_cancelled`); `GET
  /apps/tracked/{id}/install-now.json` `{phones: [{id, state, sub}]}` (state `waiting|sent|working|idle`). Every answer
  is `?install=<word>&phone={id}#phone-{id}` for a set-up phone (its row), else `?install=<word>#phones` - the group is
  always rendered with that id, also with no phones; a word for a phone without a row shows as a `row-note` at the
  group's top (`install_now::flash`). Every read is a 500 on a DB error, never a made-up row; a failed queue is a 500
  "nothing was queued". `render_detail_full` now 500s on a failed read of the app or its phones (it showed an empty list).
- **The page** (`tracked_app_detail.html`, launcher only): the rows are `<div class="row" id="phone-{id}">` - avatar,
  the name as a link (`.phone-link`, the old `phone_apps_href`) with its `row-sub` (`data-install-sub`), the running
  version as `row-value`, the form with Install Now (`button-small`) or Cancel (`button-secondary`), then the flash and
  the answer's ⓘ. Other apps' rows are unchanged. The group footer: "Install Now installs the newest release found by
  Check Now right away, also on mobile data. It waits only for a call." with an ⓘ (cut 2026-10-24, pwa.md): about a minute of the
  phone's own home screen while Vibb One restarts, **calls not filtered** then (QA R-M1), Update fence hides that
  home screen. CSS `?v=34`: `.phone-link`, `.row-form`.
- **The poll** (`static/install-now.js`, loaded only while a row is waiting/sent/working, `data-install-poll` on the
  group; `jstest/install-now.test.js`): every 5 s while the page is visible (a hidden page stops, `visibilitychange`
  starts it again), at most 360 polls (30 min - a reload starts again); each row's sub-line swapped with `textContent`;
  a changed state word reloads the page as `auto-reload.js` does (scroll-restore's record, `scrollRestoration` "auto"),
  never once a save has started and never while a `<details>` is open (the next poll does it); `?install=` dropped from
  the address once shown. Without JS a reload shows the same.
- **Words** (`locate::reword`): `installed: X` "Installed X", `already running: X` "Already on X", `not offered` "No
  update for this phone", `refused: wrong_signer|not_ours|downgrade|unparseable|release` "Refused: signed with another
  key / not Vibb One / older than the phone's version / not a readable app / this release can't be installed",
  `download failed` "The download kept failing", `download corrupt` "The download didn't match the release", `no
  space` "Not enough space on the phone", `install failed: N` "Android refused the install", `superseded` "Ended: a
  newer Install Now took over", `call never ended` "Not installed: a call stayed on". `kiosk_escapes::wait_text` (Advanced's
  "Update waiting ... Last check: ...") gets `install_now_call`/`_emergency`/`_ready`; `install_now_download` comes with
  `pending_tag: null`, so it needs none. All four words fit `MAX_WORD`. Contract files unchanged.
- Tests: `install_now::tests` (the tag codes, the release and offer tables incl. a disabled row, a manual upload, the
  25 h edge and 11 minutes, `refusal`, the working words and a report older than the receipt, the answer's day),
  `src/tests/install_now.rs` (the queue with its tag, event and nudge, the page's rows, the pop's `release_tag` - and
  none for a ring -, the replace, `in_progress`, every refusal landing on a rendered element (`assert_lands`, the
  flash), Cancel before and after the fetch and only for its kind, another app's 404s, the anonymous POST, a server
  start, the working words through a real report, the poll JSON, Recent's label and "No answer" at 25 h, the answer's
  ⓘ, the 500s), `locate::tests` (reword, label), `kiosk_escapes::tests::install_now_waits_have_words`,
  `jstest/install-now.test.js`.

## F-Droid source (2026-10-24, PLAN "An F-Droid repo as a release source; Etar first", migration `0094_tracked_app_fdroid.sql`)

- **What**: a catalog app of `source_type` `fdroid` takes its releases from an F-Droid repo: `fdroid_repo` (https only,
  stored without a trailing slash, default `https://f-droid.org/repo`; `fdroid::normalize_repo`) and `fdroid_package`
  (an Android package name; `fdroid::parse_package`). A host that is a private, loopback, link-local or tailnet IP
  literal is refused (`fdroid::private_literal`, `music_net::is_private`; qa-fdroid-source-code Q-2) by the form and
  at sync time; names stay allowed - a self-hosted repo on the LAN or the tailnet with a real certificate is a stated
  use. For Etar (`ws.xsoh.etar`), whose GitHub releases carry no APK.
  The release is stored as a GitHub one (`sync_fdroid` -> `sync_release`): tag `v<versionName>` (`fdroid::tag_of`;
  `v<versionCode>` for a blank, over 64 chars or control-character name), `latest_release_asset_id` = the
  versionCode, the `latest_release_*` columns, pins from the first APK; the page and the Apps list show the version as
  a GitHub row's ("1.0.57"). The phones' catalog is unchanged - they only download what the server serves
  (`tests::fdroid::the_phone_gets_an_fdroid_app_as_any_other`).
- **The newest version, by the repo** (`src/fdroid.rs`): **f-droid.org** (`API_HOSTS`) asks its per-package API,
  `https://f-droid.org/api/v1/packages/<id>` (`suggestedVersionCode`, else the highest listed `versionCode`; a 404 is
  `FdroidUnknown`); a version that is cached already (code and tag) ends the check there - no entry.json, no index.
  **Any other repo** (IzzyOnDroid `https://apt.izzysoft.de/fdroid/repo`, a self-hosted one) takes the index alone: the
  highest `versionCode` without a `releaseChannels` entry (betas are never picked; no "include betas" switch yet).
- **The hash, always from the index**: entry.json (read out of the signed `<repo>/entry.jar` since round S4, "The
  repo's signature" below; every check, at most 1 MiB) names the index
  (`index-v2.json`, a plain file name), its SHA-256 and size; `fdroid::index_file` downloads `<repo>/index-v2.json`
  only when no cached copy has that SHA-256 - streamed to a `.part` file, refused over the size entry.json lists or
  `MAX_INDEX_BYTES` (100 MB; f-droid.org's was 67 MB), its size and SHA-256 checked against entry.json before the
  rename (`ChecksumMismatch` / `RepoError` otherwise - nothing kept, the good copy stays), 10 min overall, 60 s
  without data. Cached as `data/tracked_apps/fdroid/<first 16 hex of SHA-256(repo url)>-<index sha256>.json` (named by
  the hash, so two syncs at once can't pair one index with another's hash). `fdroid::repo_package` reads the package
  and only then removes the repo's older copies, and a copy another sync removed between the resolve and the read is
  resolved once more (Q-8). `remove_unused_fdroid_indexes` removes the indexes of repos no app uses any more - at
  startup, after an F-Droid app is removed or moved to another repo (a database error removes nothing);
  `remove_partial_downloads` cleans the `.part` files at startup. An index that didn't match entry.json 3 times in a
  row (per repo and entry.json SHA-256, in memory) isn't downloaded by the hourly check again until entry.json
  changes; "Check Now" still tries (Q-3). entry.json's daily diffs are never
  applied: a full fetch when it changes is fine at one repo a day. Only `packages.<id>` is parsed
  (`fdroid::package_versions`, a `DeserializeSeed` that skips every other package), off the async threads. A version
  whose key isn't a SHA-256, whose `file.sha256` differs from its key, or whose `file.name` isn't a plain `.apk` name is
  left out. The version picked: the API's code (the file named `<id>_<code>.apk` first), else the highest stable.
- **The APK**: `<repo>/<file.name>` (`<id>_<versionCode>.apk`), streamed by `download_asset` with the index's size
  and SHA-256: `stream_to_file` checks the hash **before** the rename, so a mismatch keeps nothing and the row is
  untouched (`ChecksumMismatch`). A GitHub asset's `digest` (`sha256:<hex>`, in the releases API since 2025) is read
  into the same `ReleaseAsset.sha256` and checked the same way since 0.54.0 (Q27); an asset without one (null,
  another algorithm, malformed) keeps the size and ZIP-header checks only. Once is retried at the next hourly check (a CDN mid-update); 3 in a row of one
  release (`MISMATCHES_TO_REFUSE`, `AppSyncs::mismatched`) make it a remembered refusal like a wrong key - the hourly
  check doesn't download it again, "Check Now" and a new release do, and a stored release clears it (Q-3). Then
  `check_fdroid_package`: the APK's manifest package must be `fdroid_package` (`OtherApp`), before the pins
  (`check_pins`, `check_new_package`) as for GitHub; `check_new_package` now also refuses (`Taken`) a first
  package that another catalog row has as `package_name` or `fdroid_package` (Q-6, every source). Redirects may not
  leave https and stop after 5 (`tracked_apps::https_redirects`, the rule a pure `redirect_verdict`; GitHub's downloads
  use it too; Q-1). The F-Droid clients ignore proxies from the environment (`no_proxy`, the index and the APK; Q-2)
  and say `net::USER_AGENT` (`vibb/1.0` since 0.57.0, security review P5), as GitHub's downloads do (Q-9). The cached file's name is
  `file_safe(tag)-<code>.apk`.
- **The ABI** (Q-4): the pick ignores `manifest.nativecode` and `usesSdk` - fine for an app with one APK for every
  phone (Etar), not for one the repo builds per ABI (the highest code may be an x86_64 build). Add App's ⓘ by the
  package says so; the filter waits for the first such app (PLAN).
- **The repo's signature** (round S4, Q31 + Q32, 2026-11-01, migration `0111_fdroid_repo_keys.sql`): entry.json is
  read only out of `<repo>/entry.jar` (at most 1 MiB, every check; `entry.json` itself is never fetched), and only
  after `jar_signature::verify_entry_jar` has checked the repo key's JAR (v1) signature over it:
  1. `META-INF/MANIFEST.MF`'s one `Name: entry.json` section has `SHA-256-Digest` = base64 SHA-256 of the entry.json
     read (continuation lines joined, header names case-insensitive);
  2. the one `META-INF/<NAME>.SF` has `SHA-256-Digest-Manifest` = base64 SHA-256 of the whole manifest (required -
     no per-section fallback);
  3. `META-INF/<NAME>.RSA` (or `.EC`) is a CMS SignedData (RFC 5652) with detached content, exactly one certificate
     and one signer named by that certificate's issuer and serial, digest SHA-256, and a signature by the
     certificate's key - RSA PKCS#1 v1.5 2048-8192 bits or ECDSA P-256 - over the .SF bytes or, when the signer has
     signed attributes, over them re-tagged as a SET (their `messageDigest` the .SF's SHA-256, `contentType` data);
  4. the repo's fingerprint = SHA-256 of that certificate's DER, compared with the repo's pin (`fdroid::RepoKey`).
  SHA-1 digests, DSA, a second signer, signature file or certificate, a high tag or indefinite DER length are
  refused: fdroidserver's entry.jar (`Created-By: 1.0 (Android)`, f-droid.org's and IzzyOnDroid's) has none. The
  certificate is only the key's container (its dates and self-signature aren't checked, as F-Droid's client and
  Android don't for a pinned key). **No new crate**: `zip` (read), `sha2`, `base64` and `ring`
  (`UnparsedPublicKey`, `RSA_PKCS1_2048_8192_SHA256`, `ECDSA_P256_SHA256_ASN1`) were the server's already, and the
  DER reader is ~60 lines over SignedData's fixed shape - `cms`/`x509-cert` (RustCrypto, Apache-2.0/MIT) would have
  added crates for the same few fields. **The pins** (`fdroid::KNOWN_REPO_KEYS`, by host, any path): `f-droid.org`
  `43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab` (CN=Ciaran Gultnieks, RSA 2048, 2010-2037) -
  the SHA-256 of the `certificate` F-Droid's own client ships for `https://f-droid.org/repo`
  (`gitlab.com/fdroid/fdroidclient`, `app/src/main/assets/default_repos.json`, master on 2026-11-01) and the
  `?fingerprint=` on https://f-droid.org/repo/; `apt.izzysoft.de`
  `3bf0d6abfeae2f401707b6d966be743bf0eee49c2561b9ba39073711f628937a` (OU=F-Droid, CN=nebo, RSA 4096, 2016-2043) - the
  fingerprint IzzyOnDroid publishes on https://apt.izzysoft.de/fdroid/. Each matched the certificate in that repo's
  live entry.jar the same day; those two jars are the fixtures `server/testdata/fdroid/` (public, signed by the
  repos; the mutation tests use throwaway P-256 keys made at run time, `jar_signature::tests::TestKey`). **Any other
  repo** (a parent's self-hosted one) is trust on first use: the first good signature's key is pinned in
  `fdroid_repo_keys` (`tracked_apps::pin_repo_key`; two first syncs at once - the first insert wins, the other is held
  to it), and every later entry.jar must be signed by it. Fail-closed instead would make a custom repo unusable
  without a fingerprint field the parent would have to copy by hand; TOFU gives the same guarantee from the second
  fetch on, and the per-app APK key pin (M10) still holds from the first release. A repo no catalog app uses any
  more loses its pin with its cached index (`remove_unused_fdroid_indexes`): removing the repo's apps and adding one
  again is how a real key change is taken (a known host's key is code, never stored). **Fail closed**: an unsigned,
  stripped or tampered entry.jar is `RepoSignature` "The repo's signature doesn't check out", another key than the pin
  `RepoKey` "The repo's signing key changed" (both keys named in the detail) - before entry.json is read, so no index
  is downloaded, the cached index and release stay and the phones keep what they have; the next hourly check tries
  again (a 1 MiB jar is cheap, no remembered refusal). A 404 for entry.jar stays `RepoError` ("not an F-Droid repo
  (index v2)"). The two titles and Q32's are the first `SyncProblem` titles in the parent's language
  (`SyncProblem::title_in`, `apps.sync.*`; the others move with S-i18n 3b); the detail behind ⓘ stays English.
  **The signer at the first pin** (Q32, qa-fdroid-source-code N-14): index-v2's `manifest.signer.sha256`
  (`IndexVersion::signers`) is carried to the download (`ReleaseAsset::index_signers`); while the app has no signer
  pinned, an APK whose v2/v3 signer isn't one of them is refused (`IndexSigner` "Key differs from the repo's
  listing", remembered like a wrong key) before anything is pinned. An index that lists none, or an APK without a
  v2/v3 signer, isn't compared (the phone refuses an app without both pins anyway); an index that lists signers of
  which none is a 64-hex SHA-256 is refused as `IndexSigner` too (fix round L3). No defence against a tampered
  index (it could change both - that's what the signature above is for), but a mixed-up mirror or wrong file is
  caught before its key becomes the app's. f-droid.org's API shortcut is unchanged: a version that is cached already
  ends the check before entry.jar.
  **Rollback and freeze** (fix round M1, migration `0112_fdroid_repo_state.sql`): a signature alone lets an old,
  genuinely signed entry.jar be replayed with its old index. So every repo - f-droid.org and IzzyOnDroid too - keeps
  the newest entry.json `timestamp` this server took (`fdroid_repo_state`, raised never lowered,
  `tracked_apps::note_repo_timestamp`), and a signed entry.json below it is refused before its index is used
  (`RepoRollback` "The repo sent an older index" / "Repoet sendte en eldre indeks"; the same timestamp again is the
  same entry and is taken): the last good index and the cached release stay. Second guard, for the unsigned
  f-droid.org API's word and a repo that drops its newest build: the hourly sync never moves the cached release to a
  lower versionCode (`OlderVersion` "The repo offers an older version" / "Repoet tilbyr en eldre versjon", nothing
  downloaded); "Check Now" (and Forget, `forced`) takes the older one. A freeze (the same old entry.jar served for
  ever) shows only as no new version - entry.json's `timestamp` isn't held to the clock, since a quiet repo is
  legitimate. Fix round L1: a trailing dot (`https://f-droid.org./repo`) is the same host - `normalize_repo` stores it
  without the dot and `RepoKey::known` ignores it. L2: the pin and the timestamp are written only while an F-Droid
  app still has the repo (`INSERT ... WHERE EXISTS`), so a sync still running can't put back what
  `remove_unused_fdroid_indexes` just removed (the pin's write ends that sync as `Removed`).
  **Trust statement** since then: entry.json is the repo key's, the index's SHA-256 entry.json's, the APK's the
  index's - the repo's TLS certificate is no longer the only anchor; the per-app signing-key pin (security review M10) still
  refuses a release signed with another key than the first one's. Tests: `jar_signature::tests` (the real jars pass
  with their pins, a test key's with and without signed attributes; a changed entry.json byte refused at each layer -
  manifest, .SF, signature or attributes' digest; a block signed by another key; a stripped, unsigned or SHA-1-only
  jar), `fdroid::tests::a_repo_signed_by_another_key_is_refused`, `fdroid::tests::an_older_signed_entry_is_refused`,
  `tracked_apps::tests::an_fdroid_repo_key_is_pinned_and_the_index_signer_checked` (with L2, L3 and the rollback
  through a sync), `an_older_release_stored_over_a_newer_one_is_no_notice` (the versionCode guard). The ABI filter
  (Q33) is not in this round: it still waits for its first app (above). **Live check on the VM**: an F-Droid app from
  f-droid.org and one from IzzyOnDroid sync; the log has no `RepoSignature`/`RepoKey`/`RepoRollback`;
  `fdroid_repo_keys` stays empty for those two (known hosts) while `fdroid_repo_state` gets a row for each; a
  self-hosted repo gets its key row at the first sync.
- **One source per app**: F-Droid signs with its own key, so an app installed from GitHub or Play can't switch to
  F-Droid (or back) without a reinstall. Add App says so under the F-Droid fields, the app's page under its Source
  row ("F-Droid · ws.xsoh.etar", linked to `https://f-droid.org/packages/<id>/`; another repo shows its host, unlinked,
  and words the line for a repo that may carry the developer's key). The Details form posts its own `source_type`
  (hidden); another one is refused (400, nothing written, the reason at the form's top, `data-error="source_type"`):
  with a cached release "its source can't change, since the phones would have to reinstall it", without one "remove
  the app and add it again". `source_type` is still not editable in the UI (as for GitHub/manual). Once the app is
  pinned, Details keeps its package (another one is another app) and, with a pinned signer, its repo (another repo may
  sign with another key): refused by the field with the reason (`pinned_fdroid_change`, Q-5); before the first
  release both stay editable.
- **The launcher's and the music app's rows stay GitHub**: Add App and Details refuse `fdroid_package` = Vibb One's
  (`installed_by_launcher`) or Vibb music's (`music::MUSIC_PACKAGES`), a system package (`never_a_catalog_package`)
  and a package another catalog app already is (`package_name` or `fdroid_package`); `set_is_launcher` never flags an
  `fdroid` row (no switch on its page); the launcher row's source can't change. An upload to an F-Droid app is
  refused (400) - only the repo's checked APKs.
- **Sync problems** (`SyncProblem`, the page's title, the raw detail behind ⓘ): `FdroidUnknown` "F-Droid doesn't know
  this package" (f-droid.org's API's 404), `NotInRepo` "The repo doesn't have this package" (not in a repo's index -
  IzzyOnDroid's, a self-hosted one; Q-9), `RepoUnreachable` "The repo didn't answer" (network,
  timeout, a non-2xx other than 404), `RepoError` "The repo's answer can't be used" (no entry.jar - not an index-v2
  repo -, an unreadable or oversized index, no APK for the API's version, a stored repo or package that isn't valid),
  `ChecksumMismatch` "Download didn't match the repo's checksum" (the APK or the index); since round S4
  `RepoSignature`, `RepoKey` and `IndexSigner` ("The repo's signature" above).
- **The pages**: Add App's third radio (`#source-fdroid`, `.fdroid-only`, `style.css?v=52`), the fields' refusals as
  the GitHub ones (400, the values kept, F-Droid still chosen, `aria-invalid` + `autofocus` + the reason under the
  field); the app's page: Check Now and "Last checked" for every source but uploads, the F-Droid fields in Details,
  no Upload group and no launcher switch; the Apps list unchanged.
- No F-Droid client on the kid's phone (an app store breaks the model). No `scripts/fake-fdroid.py`: the tests fake the
  repo in-process (`fdroid::tests::fake_repo`: entry.json in an entry.jar signed by the run's throwaway
  `FAKE_REPO_KEY` - or `jar` as set -, the index, the API, the APKs, with request counters; on
  `127.0.0.1` it has the API, on `localhost` the sync takes the index path).
- Tests: `fdroid::tests` (the address rules, the two paths, the API's pick, entry.json, the one-package parse, the
  cache keyed on the entry's SHA-256 - no re-download when unchanged, a re-download when changed, a tampered or
  wrong-sized index refused, 3 index mismatches remembered, the read-then-remove order, the unused repos' copies;
  `a_small_document_over_1_mib_is_refused`, `a_chain_of_six_redirects_is_refused`, the private literals, the
  `<id>_<code>.apk` preference), `tracked_apps::tests` (`an_fdroid_app_syncs_by_the_api_and_the_index`,
  `another_repo_syncs_by_the_index_alone`, `an_apk_that_doesnt_match_the_index_is_refused`,
  `a_repeated_checksum_mismatch_is_remembered`, `an_fdroid_version_name_is_file_safe_in_the_cached_path`,
  `a_first_release_cant_take_another_rows_package`, `a_redirect_never_leaves_https_and_stops_after_five`,
  `fdroid_sync_problems_have_plain_words`, `a_tag_is_safe_in_a_file_name`, `sync_problems_have_short_titles`),
  `tests::fdroid` (Add App and its refusals, the page, one source per app, a pinned app's package and repo, the
  launcher switch and the upload, the ABI line, the cached index going with its repo's last app, the phones'
  catalog), `tests::tracked_apps::every_form_on_the_app_pages_returns_to_its_page` (an F-Droid row too),
  `music_spotify::migrations_73_to_95_in_order`. QA: `docs/review/qa-fdroid-source-code.md` (Q-1..Q-9 fixed).

## Update holds and the per-app wait (design 90, `docs/design/90-catalog-update-holds.md` - §17 its revision 2, migration `0141_catalog_update_holds.sql`)

The user (2026-11-12): auto-update stays for a release that keeps the package and the key and asks for nothing new
sensitive; one that asks for more waits for the parent; every GitHub and F-Droid release first waits the app's own
hours. Server only - the phones see only `latest_release_*` (`device_api::scoped_apps`, the download, `X-Release-Tag`,
Install Now), so a held or waiting release never reaches them and no contract changes.

- **What an APK asks for** (`apk.rs`): `ApkIdentity.permissions` - every `<uses-permission>`,
  `<uses-permission-sdk-23>`, `<uses-permission-sdk-m>` anywhere (an element counts by its name whatever its namespace;
  one whose `android:maxSdkVersion` is below `catalog_holds::PHONE_MIN_SDK`, 34 = the launcher's `minSdk`, a test reads
  `launcher/app/build.gradle.kts`, left out) and any element's `android:permission` that is one of
  `apk::BIND_PERMISSIONS` (notification listener, accessibility, device admin, VPN, keyboard, autofill, credential
  provider) as `bind:<name>`; sorted, deduplicated. **All or nothing**: the walk now goes to the document's end (the
  package, codes and `min_sdk` keep their lenient reading); the list is `Some` only when every chunk parsed, the walk
  ended at the declared size, and every counted attribute was found **by resource id** (`android:name` `0x01010003`,
  `android:permission` `0x01010006`, `maxSdkVersion` `0x01010271` - never by the name fallback, a parser differential),
  once, a string (typed and raw agreeing when both are there), a numeric `maxSdkVersion`, at most 500 names of at most
  200 bytes. Anything else is `None` - never "no permissions". Fixtures `testdata/apk/perms-key3-v1.apk` (INTERNET,
  POST_NOTIFICATIONS) and `perms-key3-v2.apk` (READ_CONTACTS, a `maxSdkVersion="32"` WRITE_EXTERNAL_STORAGE, an sdk-23
  ACCESS_FINE_LOCATION, another app's permission, a service with BIND_ACCESSIBILITY_SERVICE, a receiver with
  BIND_DEVICE_ADMIN): aapt2 + apksigner (v2+v3) with a third throwaway key, made 2026-11-12 and discarded.
- **What is sensitive** (`catalog_holds::SENSITIVE`, one row per exact name with its group; `GROUPS` the order and the
  words, `apps.holds.group.*` as a list item, `apps.holds.group_in.*` in a sentence): the design's §3 table - SMS,
  contacts, call log, phone, location (and background), photo locations, camera, microphone, body sensors (and Android
  16's `android.permission.health.*` by prefix), calendar, nearby devices, media, all files, overlay, installing apps,
  usage data, the five `bind:` marks plus autofill and credential provider (keyboard), and `INTERNET` (D3: only an app
  that had none). Not: `QUERY_ALL_PACKAGES` (D4), notifications, the other special accesses, anything outside
  `android.permission.`. An unknown permission is not sensitive.
- **The accepted set** (`tracked_apps.accepted_permissions`, sensitive names as JSON; NULL = unknown, counted as none):
  grows only by the parent - the app's **first release** seeds it (never held: it reaches no phone until ticked; an
  unreadable list leaves `[]`), **Approve** and a **manual upload** add what they ask for. `latest_release_permissions`
  is the served release's whole list (every store, Approve, an upload). `backfill_release_permissions` at startup
  (after `backfill_version_codes`) reads the cached file of a row from before 0141 and seeds the set; an unreadable file
  leaves both NULL, so the next sensitive release is held (fail closed). "Forget Package and Key…" clears the set to
  NULL (§17.3 M4: a new key is a new trust) with the held, waiting and declined releases (`forget_holds`); a changed
  repo or package under Details clears those three.
- **The verdict** (`catalog_holds::judge`, pure): ours (`ours`: `is_launcher`, or a package in
  `music::MUSIC_PACKAGES` - never by tag or repo) and a manual row are never judged (`holds_apply`); an unreadable list
  holds (`unreadable`); sensitive names outside the set hold (`permissions`, the new names kept); "Ask me before every
  update" (`always_ask`, D9) holds the rest (`always_ask`); else served. The pins are checked before (a release with
  another key is still refused, never offered for approval).
- **The wait** (`tracked_apps.release_wait_hours`, 0-336, 48 for every GitHub and F-Droid row - f-droid.org's too, a
  change from before (D2) - read only through `catalog_holds::wait_hours`, `None` for manual rows and ours). It counts
  from the source's time (`catalog_holds::anchor`): GitHub's later of the release's `published_at` and the asset's
  `updated_at` (a swapped APK waits from the swap); an F-Droid index version's `added` (ms, `fdroid::IndexVersion.added`,
  read leniently: a non-number is no time). A release without a time waits from this server's first sight
  (`waiting_release_since`, kept while the same `(tag, asset id)` is seen again); an older one without a time is never
  ripe. Ready = anchor + the app's hours, computed at every sync and page (§17.3 M1: a changed wait re-times it).
- **The pick** (`catalog_holds::pick`, pure; `sync_listed`): the source's matching releases newest first
  (`fetch_releases`/`matching_releases` for GitHub; `fdroid_releases` for F-Droid: the API's or the index's newest, then
  every older stable version, the stored release under its stored tag). The newest **ripe** one is synced - unless it
  isn't ahead of the served release while that is still listed (§17.3 H2: no step back after an Update Now; a served
  release gone from the list keeps today's rule, F-Droid's `OlderVersion` guard included), is the held one or behind it,
  or is at or behind the declined one (`ahead`, `release_ahead`'s rule against any release). The newest unripe one ahead
  of all three is noted as waiting (`waiting_release_*`, `note_waiting`) - listed, never downloaded, Check Now
  included; none clears the columns (a pulled release simply goes).
- **The hold** (`sync_release`): after the download and every check, a `Hold` verdict writes
  `tracked_app_held_releases` (one per app, `INSERT ... ON CONFLICT DO UPDATE`, only while the app exists) instead of
  `latest_release_*`; the previous held file goes, nothing is nudged, the release isn't remembered as refused, and the
  same `(tag, asset id)` is "nothing to do" (never downloaded again). A newer release served supersedes a held one
  behind it (row and file go); a newer one held replaces it. Its file lives next to the served one
  (`<tag>-<asset>.apk`); `remove_orphan_files` at startup removes any `.apk` in an app's directory neither names (by
  file name; the served file is never touched) and a held row whose file is gone.
- **The answers** (each under the app's sync guard - a running check answers `busy` - and only for the exact
  `(tag, asset_id)` the form posts, else `stale`; every one back to `?held=<word>#update`): **Approve**
  (`approve_held`, §17.3 M3) reads the held file again - pins, `check_new_package`, its SHA-256 - and moves it into
  `latest_release_*` with the set widened and the held row deleted in one transaction, then the old file, the pins, the
  nudge; a failed check drops the held release and shows as the sync's failure; Security Log
  `catalog_release_approved`, no inbox row. **Keep** (`decline_held`) drops it and stores `declined_release_*`
  (`catalog_release_declined`); **Ask Again** (`ask_again`) clears that and starts a check as Check Now does. **Update
  Now** (`update_now`, D6) lists the source again and syncs that exact release with the wait skipped - still judged, so
  one asking for more is held (`waits`); not listed any more: `gone`. A long download goes on after the answer.
- **The page** (`tracked_app_detail.html`): the `#update` group after Check Now (held: "2.0 waits for you", what it
  asks for, Approve and Keep, an ⓘ with the new groups, the ones it had and every name; waiting: "reaches the phones
  Thu 14:02", published or found and the app's hours, Update Now; declined: "Kept 1.0 · 2.0 declined", Ask Again);
  Source & Signing › Release's "Asks for" row (the served release's groups, every name behind its ⓘ); Updates' "Ask me
  before every update" switch and the wait's own number form (`release-wait`: no autosave, a refusal at its field with
  what was typed; Security Log `catalog_release_wait_changed`). Add App asks for the wait too (not for uploads,
  `.synced-only`). The Apps tab's badge "Waits for you" ranks after "Off".
- **The inbox**: `notifications.md`, "App update notices" - no new kind and no new switch (D1).
- Tests: `apk::tests` (the fixtures' lists; by name only, a reference, a duplicate, typed and raw disagreeing, a
  damaged chunk, a short document - `None`), `catalog_holds::tests` (the words, the verdict table, the order and the
  push's short form, the anchors, the pick's ripe/waiting split, the H2, held and declined cases, first sight, the
  field, `PHONE_MIN_SDK`), `handlers::tracked_apps::tests` (`a_release_waits_the_apps_hours_then_is_served`,
  `update_now_sends_the_exact_release_and_the_sync_never_steps_back`,
  `a_release_asking_for_more_is_held_until_superseded`), `tests::catalog_holds` (Approve, its refusal, Keep and Ask
  Again, the wait and the switch, the upload, the backfill, migration 0141), the redirect table in
  `tests::tracked_apps::every_form_on_the_app_pages_returns_to_its_page`.
