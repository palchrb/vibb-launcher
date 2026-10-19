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

- **Migration `0036_kiosk_escapes.sql`**: `device_policy.update_fence` and `notification_auto_cancel` (both **default 0**: the fence stays off until the A3 device check passes on the Jelly Star, the notification filter until B5), `device_status.update_fence_json`, `notification_cancels_json`.
- **Migration `0045_boot_cover.sql`** (design 16b): `device_policy.boot_cover`, **default 0** - the launcher's boot cover, a third checkbox on the same card ("Test it on this phone first"); `PolicyResponse.boot_cover` always sent, missing = off on the launcher. The form returns to `/devices/{id}/advanced#updates` (Advanced › Updates & notifications since design 31 step 3). **Migration `0046_boot_cover_state.sql`**: `device_status.boot_cover_json` - the phone's `boot_cover` report (`kiosk_escapes::BootCoverState`, known fields only, hand-over "cover"/"launcher"), capability `boot_cover_v1`; the card warns about an older launcher, a tripped crash guard (switch off and on to retry) or an arm not shown at the next start.
- **Wire**: `PolicyResponse.update_fence` and `notification_auto_cancel`, always sent (`policy_json_keys_snapshot`); a launcher on an older server treats both as off. Advanced › Updates & notifications (the device page's "Launcher updates and notifications" card until design 31 step 3; `POST /devices/{id}/kiosk-escapes`, one auto-saving form: a missing checkbox is off, 404 unknown device, nudges) with the privacy text behind its footer's ⓘ (the filter uses the launcher's "App badges" notification listener, reads only app, channel and type, reports only names and counts).
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
