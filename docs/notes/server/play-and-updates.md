# Server notes: play-and-updates

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

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
- **The hash, always from the index**: `<repo>/entry.json` (every check, at most 1 MiB) names the index
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
  untouched (`ChecksumMismatch`). Once is retried at the next hourly check (a CDN mid-update); 3 in a row of one
  release (`MISMATCHES_TO_REFUSE`, `AppSyncs::mismatched`) make it a remembered refusal like a wrong key - the hourly
  check doesn't download it again, "Check Now" and a new release do, and a stored release clears it (Q-3). Then
  `check_fdroid_package`: the APK's manifest package must be `fdroid_package` (`OtherApp`), before the pins
  (`check_pins`, `check_new_package`) as for GitHub; `check_new_package` now also refuses (`Taken`) a first
  package that another catalog row has as `package_name` or `fdroid_package` (Q-6, every source). Redirects may not
  leave https and stop after 5 (`tracked_apps::https_redirects`, the rule a pure `redirect_verdict`; GitHub's downloads
  use it too; Q-1). The F-Droid clients ignore proxies from the environment (`no_proxy`, the index and the APK; Q-2)
  and say `kid-phone-server (self-hosted)`, as GitHub's downloads now do (Q-9). The cached file's name is
  `file_safe(tag)-<code>.apk`.
- **The ABI** (Q-4): the pick ignores `manifest.nativecode` and `usesSdk` - fine for an app with one APK for every
  phone (Etar), not for one the repo builds per ABI (the highest code may be an x86_64 build). Add App's ⓘ by the
  package says so; the filter waits for the first such app (PLAN).
- **Trust statement**: the index and the APK come over HTTPS from the repo, and the APK's SHA-256 must be the one the
  repo's index lists (and the index's the one entry.json lists) - so a file is never served unverified, which GitHub
  can't give. The repo's own signature (`entry.jar`, the JAR signature over entry.json by the repo's key) is **not**
  verified in this round: a JAR signature verifier in Rust is its own piece of work (PLAN, the follow-up). Until then a
  repo's TLS certificate is the trust anchor; the per-app signing-key pin (security review M10) still refuses a
  release signed with another key than the first one's.
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
  timeout, a non-2xx other than 404), `RepoError` "The repo's answer can't be used" (no entry.json - not an index-v2
  repo -, an unreadable or oversized index, no APK for the API's version, a stored repo or package that isn't valid),
  `ChecksumMismatch` "Download didn't match the repo's checksum" (the APK or the index).
- **The pages**: Add App's third radio (`#source-fdroid`, `.fdroid-only`, `style.css?v=52`), the fields' refusals as
  the GitHub ones (400, the values kept, F-Droid still chosen, `aria-invalid` + `autofocus` + the reason under the
  field); the app's page: Check Now and "Last checked" for every source but uploads, the F-Droid fields in Details,
  no Upload group and no launcher switch; the Apps list unchanged.
- No F-Droid client on the kid's phone (an app store breaks the model). No `scripts/fake-fdroid.py`: the tests fake the
  repo in-process (`fdroid::tests::fake_repo`: entry.json, the index, the API, the APKs, with request counters; on
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
  `music_spotify::migrations_73_to_94_in_order`. QA: `docs/review/qa-fdroid-source-code.md` (Q-1..Q-9 fixed).
