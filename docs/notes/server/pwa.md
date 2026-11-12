# Server notes: pwa

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Fix round 2026-10-06 (emulator run, `docs/testing/2026-10-06-emulator-run.md`)

- **No POST may make the page jump to the top - always return to the edited card.** Every form
  posts and redirects back; `static/scroll-restore.js` (loaded, not deferred, by
  `templates/partials/head.html`, which every page template includes) remembers the scroll
  position when any form leaves the page (submit event, and a wrapped
  `HTMLFormElement.prototype.submit` for the auto-saving controls - marked `data-autosave` and
  saved by `static/autosave.js` since design 31, which on a `<select>` changed from the keyboard
  waits for Enter or blur) per path in sessionStorage for 20 s, with where on the screen the form and its ancestors
  with an id were, and restores it on the page that comes back. **Since the PWA browser pass's fix round
  (2026-10-14, `docs/testing/2026-10-14-pwa-browser-pass.md`, design 31 §5.1.4) every form that stays on its page
  posts to and redirects to its own group's `#fragment`** (its row in a long list): without JS the answer lands on
  the form; with JS the script keeps the place over it - no fragment: the exact position; a fragment inside the
  form's elements (its group, a new row in it, a deleted file's neighbour): that element back where it was on the
  screen - unless it left one of its recorded ancestors (an app switched to the other group on Device › Apps): then
  the exact position, the row simply gone from where it was (following it scrolled the page to the other group, the
  jump the user saw on 2026-10-19); a fragment elsewhere (another group): the browser's anchor. The record travels in
  sessionStorage and in `window.name` (prefixed `handy-scroll:`), and the place is put back at DOMContentLoaded, at
  load and on the two frames after load (an installed iPhone app that loses sessionStorage, or a late scroll to the
  fragment, must not leave the browser at the anchor). At `load` the script gives the history entry back to the browser (`scrollRestoration` "auto"),
  so Back to that page later finds the browser's place, not the top (qa-browser-fixes-code M1). A fragment must name
  an element the answer still has (a group that is always rendered; a row that goes with the save lands on its
  group - `#notifications`, the always-rendered `#orphans`): the redirect tables follow each redirect with
  `TestApp::assert_lands`. A form in a closed `<details>` returns with it open (`?open=`). A new
  page template must include the head partial (`pages_restore_scroll_after_auto_save` checks
  every template, subfolders too, and runs `node --test jstest/*.test.js`, the script's behaviour tests, when node is
  installed - CI always runs them); a new form needs its group's fragment in its action and its redirect
  (`tests::shell::every_form_on_every_page_lands_on_its_own_group` checks every page's forms with
  `settings_pages::check_forms`), except the forms that leave the page, which carry none
  (`settings_pages::leaves_the_page`: sign-in and out, the add flows' first steps, New Setup Code, the deletes back to
  a list, Spotify's login; matched without the fragment, so one that has a fragment fails). Every class a template
  uses has a rule in `style.css` (`tests::shell::every_class_a_template_uses_is_in_style_css`, with an allowlist of
  dynamic prefixes and hooks, each with its reason). External file, so a future CSP needs no inline-script exception.
- **Screen timeout** (migration `0032`): `device_policy.screen_timeout_seconds` (one of
  `models::SCREEN_TIMEOUTS` = 15/30/60/120/300/600, default 60; anything else is sent as 60),
  Lock Screen & PINs › Screen › "Turn off after" (design 31 step 4; `POST /devices/{id}/screen-timeout`, 400 for
  other values, back to `/devices/{id}/lock`), `PolicyResponse.screen_timeout_seconds` always sent (`policy_json_keys_snapshot`). The
  launcher applies it as device owner (`setSystemSetting(SCREEN_OFF_TIMEOUT)`) and reports the
  value it reads back as `screen_timeout_seconds` (`device_status`, kept only if 1 s..1 day),
  shown ("On the phone now") while it differs from the chosen one. Handy's PIN lock locks at screen-off, so this is the auto-lock delay.
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

## Fix round 1 (2026-10-18, QA `docs/review/qa-r1-server-code.md`)

- **The tab bar's width**: `html { overflow-y: scroll }` (style.css), so a desktop window with classic scrollbars has
  the same viewport width - and the fixed bar the same tabs at the same x - on short and long pages. Not
  `scrollbar-gutter: stable`: measured in Chromium 130 it keeps the room for the page's flow but not for fixed boxes
  (the bar still spanned 500 px on a short page, 485 on a long one). Overlay scrollbars (phones, macOS) take no room.
  The cost, accepted: an empty track on short pages there, the sign-in pages included (QA N2). *Since design 43 the
  always-there scrollbar is `main`'s, on a fine pointer only, and the bar is outside the scroller.*
- **iPhone edges**: `head.html`'s viewport has `viewport-fit=cover`, without which every `env(safe-area-inset-*)` is 0;
  the insets then apply to `.container` (top, the sides as `max(16px, inset)`, bottom = 96 px + inset under the bar),
  the tab bar (sides, bottom `max(4px, inset + 6px)`), `.auth-page` (all four) and `.editor-bar` (sticky top, its side
  margins matching the container's). **dvh only after a vh fallback** (iOS 14, the floor, drops the `dvh` declaration):
  `.auth-page` is `min-height: 100vh; min-height: 100dvh` (`shell::every_dvh_follows_a_vh_fallback`). *Design 43
  changed the bottom (16 px, the bar no longer covers the page) and the editor bar's top (below). Design 84 moved
  every one of `.container`'s insets to the band and the sheet inside it, and the editor bar is gone ("The night band on every page").*
- **`static/tab-bar.js`** (a relayout of the fixed bar after an iOS resume) didn't hold on the iPhone and is deleted
  with its test: design 43's app shell replaced it (the next section).
- **The Locate marker**: `L.Icon.Default.imagePath = '/static/leaflet/images/'` - Leaflet prepends it to its own file
  names, so an absolute `iconUrl` was prefixed twice (a 404, the alt text "Mark";
  `locate_page::the_map_marker_uses_leaflets_image_path`).

## The app shell (design 43, `docs/design/43-pwa-app-shell.md`)

- **The document doesn't scroll on the tab pages** (style.css): `html { height: 100% }`, `body { height: 100vh; height:
  100dvh; overflow: hidden; display: flex; flex-direction: column }` - exactly the visible height, never `min-height`
  (a taller body could still be panned by a fragment, `scrollIntoView` or the iOS keyboard). `main.container` is the
  scroller (`flex: 1 1 auto; min-height: 0; overflow-y: auto`, `scroll` on a fine pointer for a steady desktop
  column; `overscroll-behavior-y: contain`), full width, the 720 px column (480 px `.container-narrow`) from its side
  padding `max(16px, inset, calc((100% - 720px) / 2 + 16px))` - the same 688 px of content as before. The tab bar was a
  plain flex child (`flex: none; order: 1`); since 2026-10-22 it floats over `main`'s bottom, absolute in the body
  (below, "The vibb identity"); no `position: fixed` anywhere (`shell::the_app_shell_scrolls_main_not_the_document`).
  The bottom padding under the bar is `--tab-bar-room` + the home indicator's inset (`.tab-bar ~ .container`), 16 px +
  the inset on a page without it (`music_spotify_done`). The editor bar's `top: -8px` and `main`'s `padding-top: calc(8px + inset)` go together (the shell test pins both).
  *Until design 84: `main` has no padding since; the pair that goes together now is `.editor-flash`'s `top: -15px` and
  `.container-fixed`'s 15 px.*
- **The auth pages opt out**: `<body class="auth-page">` (login, 2FA, change password) keeps the document scroll
  (`height: auto; overflow: visible`), so a card taller than the screen with the keyboard up stays reachable.
- **Double-tap zoom off, pinch kept**: `touch-action: manipulation` on `main` and `.tab-bar` - a scroller resets an
  inherited `touch-action`, so it has to be on `main`, not `body`; never on `*` (Leaflet's own rules win on the
  map), never `user-scalable=no`.
- **`.editor-bar`** sticks inside `main`: a sticky offset counts from inside the scroller's padding (8 px + the status
  bar's inset), so `top: -8px` puts it right under the status bar as before; its side margins still reach the 720 px
  column's edge (measured in Chromium at 390 and 1280 px). *Gone with design 84: Save is on the editor's band, a child
  of `body` above `main`; what sticks inside `main` is the save's flash alone (`.editor-flash`).*
- **The place on the page is `static/scroll-restore.js`'s** (a browser puts an inner scroller back only from the
  back-forward cache): `scrollerOf(win)` picks `main` when its computed `overflow-y` scrolls, else the window, at each
  use (the script runs from the head, before `main` exists). The form record's fragment maths uses the scroller's
  position (`rect.top + scroller.top()` against the saved screen tops). **The place record**: on `pagehide` and when
  the page is hidden, `{y, t}` under path + search in one sessionStorage map `handy-place` (the newest 50); a
  `back_forward` or `reload` load (`PerformanceNavigationTiming`, else `performance.navigation.type` 2/1 on iOS 14)
  puts it back - over a `#fragment` too, since after a save the address carries the group's one; a `navigate` load
  restores nothing (a link opens at its fragment or the top) - but the band's back link to the page it came from
  (since design 84's fix round, "The night band on every page"). The form record wins over the place record. On the
  shell's pages a window iOS left offset (the keyboard's pan) is put back at 0 on `focusout`, `visualViewport`
  `resize` and `pageshow`.
- `auto-reload.js`, `install-now.js` (their reload record) and `music-cards.js` (its scroll anchoring) read and scroll
  `main` the same way. Tests: `jstest/scroll-restore.test.js` (every restore case against the window and against a
  `main` scrolled to 300 that must leave the window alone; the place record, the fallback, the cap, the guard),
  `auto-reload.test.js`, `install-now.test.js`, `music-cards.test.js`, `shell::the_app_shell_scrolls_main_not_the_document`.

## The PWA's times, connection state and Attention (design 31 step 2, `docs/design/31-pwa-redesign.md` §3.8, §3.10)

- **Local times** (`src/display_time.rs`, `chrono-tz` with the IANA data compiled in): `Zone::load` reads
  `provisioning_settings.time_zone` (Phone Setup); an empty or unknown zone (`valid_time_zone` checks only the
  characters, so "Europe/Olso" is stored) falls back to UTC and every clock time then says "UTC". `relative`
  ("just now", "3 min ago", "2 h ago", "yesterday 21:40", "12 Oct") uses the server's clock only, never the
  phone's; `exact` is for `title=`. `parse_db_utc` reads `datetime('now')` text and RFC 3339. The Settings hub's
  "today" and backup time use it too. `parent_pin::when_text` is gone (step 10 moved its last callers).
- **Connection** (`streams::connection_state`, pure; `devices::connection_of` feeds it): last contact = the newest
  of `limits::last_access`, `devices.last_seen_at` and an open stream's `since`. Online = a stream open and contact
  <= `ONLINE_WINDOW_MINUTES` (60); Away = no stream and <= `AWAY_WINDOW_MINUTES` (45); else Offline; plus
  Disconnected (enrolled, no token), Not seen yet, Not set up. The two windows come from the launcher's backstop
  (`SyncSchedule.kt` 30/15 min, inexact alarms up to 1.75 x): a launcher change to the backstop must revisit them.
  Right after a restart a phone shows Away, or Offline if its last report is over 45 min old, until its stream
  reconnects: seconds after a clean stop, up to 5 min after a crash or power cut (no special case). The pages' dots
  and lines read it through `phone_status::dot` since 2026-11-01 ("Off or out of touch says so" below): Away under
  `NO_CONTACT_AFTER_MINUTES` (10) is green, after it orange "No contact"; About This Phone's Connection row keeps
  the raw state's word and colour.
- **Lock badge and screen time** (`src/time_state.rs`, from the last `time_state`): **the end ("until 14:30",
  "until 07:00 tomorrow") only when provably right** (the parent's rule): the phone reports its own zone
  (`time_state.zone`, the launcher's `ZoneId.systemDefault().id` since design 34 P13; an older launcher sends none and
  gets no end) and it is exactly Phone Setup's IANA name (another zone, an offset id, garbage or another case: no end;
  `TimeReport::parse` keeps only a non-empty string of at most 64 bytes, nothing else reads it), the report is
  fresh, from today and inside one window of the active rule, the end is exactly one instant in the zone
  (`Zone::single_instant`: not in a spring-forward gap, where the phone unlocks at the jump, nor in a fall-back
  hour, which comes twice - qa-34-s13-code L2), and at that window's end no effective rule locks
  (the same rule's next day, an all-day rule, a legacy pair, an adjacent rule) and the screen time isn't used up.
  A report from another day than the server's local today (in the fallback zone: more than a day from the UTC date)
  shows no end and no bar ("at last report (yesterday 21:40)"); Offline always and Away after 15 min add "at last
  report", never with an end. "· calls off" only with calls managed and screened (`attention::Facts::calls_enforced`).
  In the fallback zone the bar names the phone's day ("42 of 90 min on 13 Oct"). End to end with the launcher's
  own body (`devices_list::a_launcher_report_with_its_zone_shows_the_rules_end` posts `testdata/time_status_report.json`,
  the contract the launcher's `TimeStateZoneTest` encodes exactly; the launcher keeps a byte-identical copy, compared
  on both sides): Phone Setup's zone shows "until 07:00" on the Devices list, the summary and Device › Time; another
  zone, none, `null`, a non-string or garbage show the rule's name alone (the 64-byte cap is pinned by
  `time_state::tests::the_zone_is_kept_only_as_a_short_non_empty_string`). The emulator's zone (the launcher forces automatic time zone, so the
  network's wins): `adb shell getprop persist.sys.timezone` into Phone Setup (`docs/testing/emulator.md`).
- **Attention** (`src/attention.rs`): `facts(state, device, connection)` (or `facts_of` from cards already built,
  as the device page does) -> `items` = rows with a level (alarm/action), a typed title (<= 8 words;
  `calls::CallProblem` types the call report's warnings), a target (the sub-page that fixes it, or since step 10 a group of the summary: Restrictions
  paused → `#quick`, the foreign app → `#foreign-access`, blocked apps → `#blocked-apps`) and `at_last_report`. A phone not set up has none; an Offline or Disconnected phone's
  report-based rows are marked "At last report" and still count. The summary shows them in its Attention group; the Devices list shows their count as the badge - the same `items`, so they agree.
- **Devices list** (`devices_list.html`, `devices::list_row`): avatar + dot, name, one status line (since
  2026-11-01, "Tidier status" below; was "Online · seen 3 min ago" or the lock part, the screen time and the battery
  each on a line), the screen-time bar, the badge, Add Phone. The sign-in warnings are short
  orange rows at the top (`auth::live_warning_row`/`login_warning_row`); the full sentences stay on Account. Tests:
  `display_time::tests`, `streams::tests`, `time_state::tests`, `attention::tests`, `src/tests/devices_list.rs`,
  `src/tests/attention.rs` (a row per fact).

## About This Phone and Advanced (design 31 step 3, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **Two sub-pages** (`src/handlers/device_pages.rs`, `device_about.html`, `device_advanced.html`), reached from two
  rows at the bottom of the device page ("About This Phone · Vibb One 0.23.6", "Advanced"). A rebuilt sub-page has the
  §3.0 header - a back link to the phone (`.nav-back`) and the large title - and includes `partials/tab_bar.html`
  alone, without the brand bar (gone since step 15). *Since design 84 that header is the band (`band::phone`): the
  chevron, the title and the phone's small picture.*
- **About This Phone** (`GET /devices/{id}/about`, read-only): Connection (Status "Online since 08:12" from
  `connection_state`, Last contact, Last full report, Network from `limits::LastAccess`), Sound (`sound_mode::state`),
  Software (Vibb version, the music app's version from its versionCode, "Settings from server" = Applied or a
  `PolicyProblem` title, Battery optimisation, "Modify system settings" - Allowed, or Not allowed with the on-phone path and adb in its detail, from `write_settings_granted`), Diagnostics (crash reports, each a row whose ⓘ has the trace;
  "Starts without Vibb One" from `boot_witness::unseen`; the time lock). Times are local (`display_time`), the exact one
  in `title`. Rows are `device_pages::Row` (`partials/info_row.html`: a row with a sentence opens it as an ⓘ panel).
- **Advanced** (`GET /devices/{id}/advanced`): Restrictions (the 11 hardening switches as one form, split into
  groups for their footers, plus the Backup to Google row), App screens (`kiosk-block` + `PlayNote`s), Updates &
  notifications (`kiosk-escapes`, one form, + `EscapeNote`s and what the filter removed), Connection
  (`regenerate-code`, the QR link, Disconnect Phone… behind a `<details>` confirm), Remove Phone… (`delete`,
  `<details>` confirm). App screens, Updates and Connection only for an enrolled phone. Warnings sit above their
  group (`partials/note_warning.html`), states are rows (`partials/note_row.html`). **Spotify** (2026-11-01, PLAN
  "Spotify diagnostic log for 15 minutes"; only on an enrolled phone bound to a Spotify account, words through the
  catalogs, `advanced.spotify_diag.*`): "Turn On Diagnostic Log for 15 Minutes" posts `/devices/{id}/spotify-diag`
  (`music_spotify::start_diag`: `device_policy.music_spotify_diag_until` = now + `music::SPOTIFY_DIAG_SECS`, migration
  0108, the phone nudged, back to `#spotify-diag`); while it runs the group shows "Diagnostic log · On until hh:mm"
  instead, the button again once it ended. The policy serves it as `music.spotify_diag_until` only while it is ahead
  (`music::spotify_diag_until`, else `null`); the launcher hands it to the music app as the managed config's
  `spotify_diag_until`, which writes its `VibbSpotify` lines until then (`adb logcat -s VibbSpotify`). Not in the
  security log (it changes nothing on the phone but what its own log says). Tests:
  `tests::music_spotify::the_diagnostic_log_runs_fifteen_minutes`.
- **Where the forms return** (§5.1): the three auto-saves → their groups, `/devices/{id}/advanced#restrictions`,
  `#app-screens`, `#updates` (the browser pass's fix round: without JS on the switch, with JS `scroll-restore.js`
  keeps the exact position; pinned by the redirect table); `revoke` → `?notice=…#connection` (the flash in the group),
  `delete` → `/devices`. `enroll_code.html`'s Back goes to `/advanced#connection` for an enrolled phone, `#access`
  (the not-enrolled card) otherwise.
- **Attention targets** moved: Do Not Disturb → `/about#sound`, policy problem → `/about#software`, unseen restarts →
  `/about#diagnostics`, Backup to Google → `/advanced#restrictions`, access removed → `/advanced#connection`, restart
  once → `/advanced#app-screens`. Four orange rows added in the fix round (qa-31-s3-code M4/L4, decided yes on the
  user's behalf, the user can overrule): Play install mode and Play openable (not pausable, "Block other apps'
  screens" off) → `#app-screens`; "Vibb One isn't the Home app" and "Boot cover wasn't shown" → `#updates`. Their facts
  come from Advanced's own builders (`device_pages::play_notes`, `device_pages::escapes_card`).
- **Times:** past ones with `Zone::when`, end times with `Zone::until` ("14:05", "00:05 tomorrow", "Mon 14:30",
  "20 Oct 14:30"; qa-31-s3-code M2): the live code, the typed-code pause, install mode. About's Last contact comes
  from the signals (`streams::last_contact` over `devices::connection_inputs`), so a Disconnected phone keeps it.
- **Typed (M5), the sentence builders deleted with their unit tests:** `streams::instant_changes_line`,
  `limits::last_access_line`, `sound_mode::status_lines`, `boot_witness::warning`, `devices::battery_line`,
  `devices::policy_problem_text`, `models::admin_policy_notice` (now `admin_policy_effects`), the escapes card's
  lines/warnings and `release_text`.
- Tests: `src/tests/device_pages.rs` (About's rows, a phone not set up, 404s, the redirect table built from the
  rendered forms, the `<details>` confirms, the live code without the code), and the repointed `hardening.rs`,
  `step11.rs`, `step9.rs`, `play.rs`, `sound_mode.rs`, `cleanup.rs` (crash pin), `enrollment.rs`, `edge.rs`,
  `device_api.rs`, `command_receipts.rs`, `window_relock.rs`, `devices_list.rs`, `shell.rs`.

## Lock Screen & PINs (design 31 step 4, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **The page** (`GET /devices/{id}/lock`, `device_pages::view_lock`, `device_lock.html`), reached from a row at the
  bottom of the device page ("Lock Screen & PINs · PIN on/off"); also for a phone not set up yet (PINs can be set
  before setup). Groups in the LockScreen mockup's order: Child PIN, Parent PIN, Screen, Lock screen, Weather (design 75, since
  2026-11-03), Notifications on the lock screen (Parent PIN's "Recent use" went on 2026-11-03, the user's ask).
- **Forms** (POST routes unchanged): the Child PIN and Parent PIN rows are `<details class="edit">` with the field
  and a Save button (a PIN is typed, not toggled); Remove Child PIN… is a `<details>` confirm (M6) posting
  `clear_kid_pin`; the Parent PIN's "Remove it instead" stays a checkbox in its form. "Turn off after" and
  "Background" are inline `select.pick`s, each app a `select.pick` too since 2026-10-21 (below) - all
  `data-autosave` with a `<noscript>` Save.
- **One select per app on the lock** (2026-10-21, the user's pick of the mockup's variant B): "Notifications on the
  lock screen" is one row per app (`form.cell#lock-screen-app-<package>`), the app's name (and its "Not allowed" /
  "Not on the phone" note) as the label of a native `<select name="show">` (the phone's own picker): **Off** (not on
  the lock), **Name only** (on, text off), **Full text** (on, text on). It replaces the tick switch and design 51's
  "Show message text" switch under it, whose sub-line "Sender's name only" was wrong for anything but a chat: the
  launcher's `GlanceRows.glanceLevel` shows a MessagingStyle notification's sender, any other app's name and count,
  and the text for any style with the text on. The footer says so: "Name only: the sender's name for chats,
  otherwise the app's name and how many. Full text: the message too, which anyone holding the phone can read. Missed
  calls always show." One handler, `lock_glance::save_app` (`POST /devices/{id}/lock-screen/apps`, `package_name` +
  `show` = `off`/`name`/`text`; `/lock-screen/apps/text` is gone): one transaction - no row, a row with
  `show_text = 0`, or with `show_text = 1` - back to the app's row (Off on a row listed only because it was on: to
  `#notifications`, qa-browser-fixes-code L1), the phone nudged, a security-log line per change only
  (`lock_screen_app_changed` on/off, `lock_screen_text_changed` for the text; a save that changes nothing logs
  nothing). 400 for a bad package or a `show` that isn't one of the three; a body without `show` (a Lock page loaded
  before the select posts `on`) and a post to the removed `/lock-screen/apps/text` answer 400 "This page is out of
  date - reload it." (`lock_glance::stale_page`, nothing written; the route can go a release later). The write is one
  `BEGIN IMMEDIATE` transaction (two saves racing wait for each other). 404 unknown phone, 500 on a
  DB error - nothing written. **The stored data and the policy are unchanged** (`device_lock_glance_apps` row or
  none, `show_text`; `policy_json_keys_snapshot` untouched): no launcher or contract change. Tests:
  `lock_glance::each_move_of_the_select_is_stored_as_the_lock_shows_it` (every transition, the policy, the log),
  `refused_saves_write_nothing` (the values), `the_page_lists_the_allowed_apps_with_their_ticks` (the select, its
  label, the footer), `device_pages::lock_forms_return_to_the_page`, `lock_auto_saves_have_a_save_without_js`.
  **Since design 82 the same select is Meldinger's too** (the launcher's built-in SMS app, key
  `me.vibb.builtin.messages`): the group's first row while SMS is managed, saved by the same handler to its own
  anchor, and Calls & Contacts' old switch is one link row to it (`docs/notes/server/hardening-and-pins.md`,
  "Meldinger's row"). The row is labelled with the parent's name for Meldinger when one is set (W1).
- **Wake the screen for a notification** (launcher round 7 §3, `docs/design/r7-launcher-small-items.md`): its own
  cell `#lock-wake` in the Lock screen group under Background - a switch (sub-line "For the apps on the lock below and
  missed calls", `data-autosave`, `<noscript>` Save) posting `POST /devices/{id}/lock-wake`
  (`lock_glance::save_wake`: `device_policy.lock_wake_on_notification`, migration 0084, off by default; 404 for an
  unknown phone), back to `#lock-wake` (`check_forms`), the phone nudged, a `lock_wake_changed` security-log line when the value changed
  (`lock_glance::wake_log_line`); sent as `launcher_ui.lock_wake_on_notification`. The group's footer says that what
  the lock shows is seen by anyone near the phone (its ⓘ went in the ⓘ cut, 2026-10-24). Tests: `lock_glance::the_wake_switch_saves_logs_nudges_and_reaches_the_policy`,
  the redirect table in `device_pages`, `policy_json_keys_snapshot`.
- **Buzz for a notification** (design 82 W3, `docs/design/82-meldinger-as-an-app.md` §9 and §14): the cell `#lock-buzz`
  right under the wake's - a switch (sub-line "One short buzz, no sound, for the apps on the lock below and missed
  calls", `data-autosave`, `<noscript>` Save) posting `POST /devices/{id}/lock-buzz` (`lock_glance::save_buzz`:
  `device_policy.lock_buzz_on_notification`, migration 0136, off by default; 404 for an unknown phone), back to
  `#lock-buzz`, the phone nudged, a `lock_buzz_changed` security-log line when the value changed
  (`lock_glance::buzz_log_line`); sent as `launcher_ui.lock_buzz_on_notification`, always. One switch for everything
  that brings a row to the lock - Meldinger has none of its own, and no app has a buzz choice. A phone that has
  reported and whose last report lacks `messages_app_v1` (the buzz ships in that launcher release) shows the orange
  line "This phone does not buzz yet - update Vibb One" under the switch (`lock_buzz_needs_update`); never before
  the first report. Tests:
  `lock_glance::the_buzz_switch_saves_logs_nudges_and_reaches_the_policy`, the redirect table in `device_pages`,
  `policy_json_keys_snapshot`.
- **Where the forms return** (§5.1, pinned by `device_pages::lock_forms_return_to_the_page`): `kid-lock` →
  `/lock?notice=…#child-pin`, `kid-pin/send` → `/lock?notice=…#child-pin`, `kid-lock/keyguard` → its row
  `#lock-keyguard` (design 59; a warning coming in above the group then moves nothing), Show Child PIN → the page
  itself, posted to its own row `/lock#show-pin` (the fragment never reaches the server), `parent-pin` → `/lock?notice=…#parent-pin` (the flash in the group); `screen-timeout`
  → `#screen`, `lock-wallpaper`, `lock-wake`, `lock-buzz`, `lock-screen`, `lock-screen/apps` → their rows (`#lock-wallpaper`, `#lock-wake`, `#lock-buzz`,
  `#lock-screen-text`, `#lock-screen-app-<package>`; scroll-restore keeps the exact place). Attention: no/easy Parent PIN → `/lock#parent-pin`, Child PIN set but lock off
  and (design 59) "Phone's PIN isn't the Child PIN" → `/lock#child-pin`; the summary's foreign-app step and Today's Parent PIN rows point there too.
- **Android's lock in the Child PIN group** (design 59 step 2; `docs/notes/server/hardening-and-pins.md`, "Android's
  lock screen"): under the Child PIN row, the **Lock** select ("Vibb One's lock" / "Android's lock", sub-line "Test
  phase: Android's lock will replace Vibb One's", disabled without a Child PIN; `POST /devices/{id}/kid-lock/keyguard`
  back to its row `#lock-keyguard`), **Show Child PIN…** (a `<details>` with the password; it posts to
  `/devices/{id}/lock#show-pin`, `action=reveal_kid_pin`, and is answered with the page itself - no redirect, the PIN
  in no address; without JS the answer lands on the row, with JS `scroll-restore.js` keeps the place; the GET page and
  the answer are `no-store`, `admin_auth::no_store_path`; the answer shows the PIN in a plain cell, not a
  `<details>`, so closing and opening can't restart its minute: `.pin-digits`/`.pin-inline` go `visibility: hidden`
  after 60 s by a CSS animation alone, and `static/reveal.js` - loaded only on a reveal's answer - empties them on
  `pagehide` and when the page is hidden, and turns the answer's history entry into a GET of the same address with
  `history.replaceState`, so a reload, Back or Forward loads the page without the PIN and never posts the password
  again; without JS the browser asks before posting it again, like any form's answer - the PIN can't go through a
  redirect, so post/redirect/get is out; qa-59-step2-code S2P-5/6; "Hide now" is a link to `?hidden=pin#show-pin`), the state rows and warnings, **Send the Child PIN again** (`POST
  /devices/{id}/kid-pin/send`, back to `?notice=pin_sent|pin_not_sent|pin_not_kept#child-pin`). The footer and its ⓘ
  follow the parent's choice (Android's lock: nothing can locate, ring or erase it and alarms may not ring before the
  first unlock after a restart, the Parent PIN doesn't open it - Show Child PIN does; Vibb One's lock: the back-off, the
  Parent PIN as "Parent code", it doesn't encrypt the data, some bank apps won't work). The Parent PIN's ⓘ: up to two
  hours on a time lock, never Calls or Restrictions, a new PIN ends a lockout once the phone checks in. The summary's row reads "PIN on · Android's lock". "Send again" and the reveal's "the
  phone still has" read only a report since the phone's enrolment (`device_pages::since_enrolment`, as the queue rule;
  S2P-4).
- **Typed (M5):** `kid_lock::LockNote` (+ `Inactive`) replaces the card's `lines`/`warnings` and `inactive_warning`;
  `lock_glance::Note` the notes' sentences; `parent_pin::event_row` the PWA's event lines (`event_line`, `until_text`'s
  same-day form) and `parent_pin::notices` takes the zone (deleted 2026-10-20 with the summary's Now). A sub-page warning (`device_pages::Note`) may carry a `fix`
  (an adb command) shown behind an ⓘ after its sentence (`partials/note_warning.html`), never in the visible text.
- **Fix round (qa-31-s4-code):** "On the phone · Locked · 10:59" carries the report's local time, so an old report
  never reads as now; `device_pages::load_policy` gives a 500 on a DB error (About, Advanced and this page), never the
  defaults' "Child PIN · Off"; `device_pages::has_capability` is the pages' one capability check; the device page asks
  `parent_pin::card` for the notices only (`with_events = false`), not the 10-event list (since 2026-10-20 the
  summary doesn't ask it at all, and `with_events` is gone).
- **Shared builders:** `devices::app_rows` (the Apps list's rows, parsed allowlist, installed apps, downloads) is the
  device page's and Lock Screen & PINs' (`devices::lock_screen_card` takes it).
- Tests: `src/tests/device_pages.rs` (the moved forms, the row's value, the groups' order, the confirm, the redirect
  table, `<noscript>` Saves, a phone not set up, 404), repointed `parent_pin.rs`, `step10.rs`, `lock_glance.rs`,
  `window_relock.rs`, `device_api.rs` (screen timeout), `wallpapers.rs` (lock background), `devices_list.rs`,
  `shell.rs`; unit tests in `kid_lock`, `parent_pin` (rows and notices in Oslo time), `lock_glance`, `attention`.

## Home Screen and Web Filter (design 31 step 5, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **Home Screen** (`GET /devices/{id}/home`, `device_pages::view_home`, `device_home.html`; row "Home Screen · 3 per
  row"): Language, Apps per row and Clock (`/launcher`, one form, three inline selects; design 34 P12 added Clock -
  a post without it keeps the stored format), the Wallpapers grid (`/wallpapers`, one
  form) and the five Quick controls (`/policy` with `section=quick`; the fifth, "Touch sounds and vibration", launcher round
  9 §4). "Notification access off" sits above the first
  group (`device_pages::notification_access_note`, the adb command behind its ⓘ; the Attention row's fact and target
  `/home#layout`). "Clock format not applied" (design 34 P12, `device_pages::clock_format_note`) sits there too, only
  while 24 h or 12 h is chosen and the last report says `clock_format_applied` false. A failed wallpaper read is a
  500, never an empty grid (its Save would untick everything).
- **Web Filter** (`GET /devices/{id}/filter`, `device_pages::view_filter`, `device_filter.html`; row "Web Filter ·
  On/Off"): the Web filter switch (`/policy` with `section=filter`); Blocklists, one inline select each - "As for
  everyone (On|Off)" (the list as everyone has it; "Default (On)" until 2026-11-01) / On / Off, the footer "As for
  everyone follows the switch in Settings › Web Filter." (`device.filter.*`) (`/dns/devices/{id}/blocklists/{list}/override`, `setting=default|on|off`; an
  older form's `clear` / `enabled` still works; 400 other values, 404 unknown phone or list), with the list's download
  problem as a short title (`dns_filter::FeedState::problem`; the global page keeps its sentence); Blocked / Allowed
  on this phone (`/dns/domains/new` with `device_id`, `/dns/domains/{id}/delete`; a name that isn't a domain goes back
  to its group with `?notice=not_a_domain_block|allow#blocked|allowed` from this page, a 400 from `/dns`; a domain
  already in that list is kept once; a `device_id` that isn't a number is a 400 - never "every phone" -, an unknown
  phone 404; a Remove whose row is already gone returns to the page its form's `device_id` names, which never picks
  the row); Blocked Activity: the "Keep a log" switch (`/dns/log/{id}`) and, while on, the 200 newest arrivals sorted
  by the phone's time in local time (a garbled phone time reads "Time unknown" and sorts last). Every read the page
  needs fails as a 500 (the code QA fix round, `qa-31-s5-code.md`).
- **`section` on `/devices/{id}/policy`** (§5.6, H1): `quick` writes only `quick_controls_mask` and returns to
  `/home`, `filter` only `vpn_filter_enabled` and returns to `/filter`; no section (an older tab) writes both and returns
  to the device page; another value 400. `kiosk_desired = 1` and the lock-task features in every case; 404 unknown phone,
  500 on a DB error (it used to log and redirect).
- **`back`** (§5.1) on the domain and list routes (`dns_filter::back_to`): `back=filter` returns to the phone's Web
  Filter page - the phone from the path or the deleted row, never from the form -, anything else to the form's group on
  `/dns` (step 14). Every form on the two phone pages returns to its group (`#layout`, `#wallpapers`,
  `#quick-controls`; `#filter`, `#lists`, `#blocked`, `#allowed`, `#log`; scroll-restore keeps the place), pinned by
  `tests::device_pages::home_and_filter_forms_return_to_their_page`.
- **Old URLs** (L5): `/dns?device=X` → `/devices/X/filter`, `/dns/log?device=X` → `/devices/X/filter#log`. `/dns`
  and `/dns/log` are in "The rest of Settings" below.
- Tests: `src/tests/device_pages.rs` (both pages, the rows, the section test, the redirect table, the old URLs, bad
  input, unreadable parts, a phone not set up), repointed `launcher_ui.rs`, `wallpapers.rs`, `sound_mode.rs`,
  `cleanup.rs` (the log), `dns_blocklist.rs` (the phone's list problem), `lock_glance.rs`, `devices_list.rs`, `shell.rs`.

## Device › Apps (design 31 step 6, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **The page** (`GET /devices/{id}/apps`, `device_pages::view_apps`/`render_apps`, `device_apps.html` with
  `partials/device_app_row.html`; the device page's row "Apps · 14 allowed", the count the Apps tab shows, "List
  unreadable" for a corrupt allowlist): **Allowed** (Vibb first, "Always on", no switch; then every app switched on)
  and **Not allowed**, one row per app from `devices::app_rows` - a tile (the icon the phone shows: its own Name and
  icon, else the catalog's, else the first letter), the name the kid sees (this phone's Name and icon name, else the
  catalog's, else the phone's label or the catalog app's name - `UnifiedAppRow::label`, the sort key; 2026-11-03), the
  state as a sub-line ("Installing 40%", "Not installed · No release yet", a refusal red; the app's own name first,
  "Element X · Installed", when the shown one differs - `own_label`, as the Apps tab's Katalog does) and a switch, its
  own form; an allowed app with a package has its `app_display_form` in a `<details>` under the row ("Name and icon:
  …"; its name field's placeholder stays the app's own; "On Home"'s explanation behind an ⓘ left of its switch,
  `.on-home`). Directly under Vibb One's row sits **Meldinger's** (design 82 D10: "Messages", "Part of Vibb One", no
  switch - only its Name and icon, saved to `#app-me.vibb.builtin.messages` like every row's;
  `docs/notes/server/launcher-ui.md`, "Meldinger, a part of Vibb One"). The footer's ⓘ holds the keyboard and
  accessibility note. **Downloads**: "Download apps on Wi-Fi only" and the phone's network at its last report. No
  search field. While an app installs the page reloads every 3 s (`static/auto-reload.js`, pinned by
  `jstest/auto-reload.test.js`; Calls & Contacts and the phone's summary after Lock again or Unlock use it too): never once a save has started (a `submit`, a `change` on a `data-autosave`
  control, `pagehide` - a reload would cancel the POST), never while a Name and icon form is open or a text field has
  the focus or any field differs from what the page came with (design 80's code QA V1, v8: the tick waits and reloads
  once the edit is saved or undone), never on a refused
  save's page (the answer to a POST); a refusal's `?notice=` is dropped from the address once shown. A phone
  not set up has the page too. Every read is a 500 on a DB error (`app_rows`, the names, the last report), never a
  shorter list.
- **Where the forms return** (§5.1, `tests::device_pages::apps_forms_return_to_their_rows`, built from the rendered
  forms): a switch (`/apps/toggle`, `back=apps`) to its row, `/devices/{id}/apps#app-<package>` (`#catalog-<id>` for a
  catalog app without a package; `devices::app_anchor`) - the row moves between the groups and the fragment finds it,
  with or without JS; `back=music` (or a page's old `anchor=music`) to Device › Music (step 7, below); a Name
  and icon save to the same row (the action carries the fragment, for a refusal rendered in the answer);
  `/app-updates` to `#downloads`. The blocked apps' "tick it" and the Apps tab's phone rows link to the rows.
- **`toggle_app`** takes a package name (the status intake's rule) or exactly what the catalog row it names stores (an
  older catalog's free-text package stays switchable). Refused at its row (`?notice=<code>&app=<row>`,
  `devices::toggle_refusal`): a Play copy of a catalog app (`from_play`), a free-text package that isn't its catalog
  row's (`not_an_app`). A 400: nothing at all, another free text, Play's core, Vibb by its catalog id or its package;
  404 an unknown phone. A catalog app deleted since the page loaded returns to the group the tap came from
  (`#not-allowed` / `#allowed`) with nothing written; every write's DB error is a 500. The device page's Apps row says
  "Couldn't read" when the list can't be read ("List unreadable" is the corrupt allowlist).
- Tests: `src/tests/device_pages.rs` (the page, the redirect table, refusals and failed reads, a phone not set up),
  repointed `app_display.rs`, `tracked_apps.rs` (downloads, the Wi-Fi switch, the tab's links and count),
  `catalog_pins.rs`, `play.rs`, `hardening.rs`, `window_relock.rs`, `device_api.rs`, `music.rs` (`back=music`);
  `app_downloads::tests` (the labels in Oslo time).
- **Opened from the Apps tab** (2026-10-24, the user: "på apps, om jeg går inn på feks Emma sin tlf og så trykker
  tilbake - så kommer jeg til device taben for Emma? Føltes unaturlig"): the Apps tab's phone rows link with
  `?from=apps`, a catalog app's phone rows and `phone-link`s with `?from=app:<tracked app id>` (before the
  `#app-<package>` fragment; `tracked_apps::phone_apps_href`). `device_pages::AppsFrom::parse` takes only those two
  shapes (the id a positive number in digits); with one the page lights the Apps tab and `.nav-back` goes to `/apps`
  ("Apps") or `/apps/tracked/<id>` (the app's name) at the phone's row, `#phone-<id>` (both pages give their phone
  rows that id) - at `#phones`, the app page's always-rendered group, when that page doesn't list the phone (unticked
  since; the launcher's page lists every set-up phone; qa-pwa-polish-a Q-10); a catalog app deleted since is the list;
  anything else is the phone's own page, as before. Every
  form on the page carries a hidden `from` (the switches, Name and icon - `AppDisplayForm::from` -, the Wi-Fi switch)
  and its redirect keeps it before the fragment (`device_pages::apps_url`: `?notice=…&app=…&from=…#row`), the
  refused Name and icon's answer too; `auto-reload.js` (`?v=4`) drops only a shown `notice=`/`app=`, never `from=`.
  The browser's Back is untouched. The tab is still the template's choice (§3.1): `device_apps.html` alone sets it
  from a field, `{% let tab = nav_tab.name() %}`, a `device_pages::Tab` that can only be a tab's name
  (`shell::every_template_with_the_bar_sets_its_tab` pins that line for this file and the literal rule for every
  other). Tests: `device_pages::apps_from_the_apps_tab_go_back_there` (both origins, bad values, a deleted app, every
  form's redirect, a refusal through `toggle_refusal` keeping `?notice=…&app=…&from=…#row`, the deleted catalog row,
  the refused Name and icon, Back to `#phones`), `device_pages::tests::apps_from_takes_only_its_two_shapes`,
  `tracked_apps::tests::a_phone_link_says_where_it_came_from`, `jstest/auto-reload.test.js`.

## Device › Apps: Permissions (design 68, `docs/design/68-app-permissions.md` §4.1, migration `0102_app_permissions.sql`)

- **What the parent sees**: an allowed app the phone reports something about (`installed_apps[].access`, launcher
  `app_permissions_v1`; third-party apps only, the phone reports none for a system app) gets a second sub-line under
  its row with only what needs a look - "Camera off" (a stored row and the report reads `admin_denied`; "Camera off ·
  waiting for the phone" while it still reads `requested`, "Camera off · back on while restrictions are paused" while
  the last report says paused - the pause lifts it), then each declared access "<name>: not approved / unknown" (an
  approval stays out of the line and is shown in the details; the code QA's L2) -, and a `<details>` "Permissions"
  next to "Name and icon"
  (`partials/app_permissions_form.html`, `app_permissions::PermsView`): the **camera switch only when the report has
  `access.camera`** (third-party apps that ask for the camera; a preinstalled camera or Google app has none), with its
  help ("Off: <app> can't use the camera - a video call or a QR scan stops working. Turning it off closes <app> if it was
  allowed the camera."), and one line per declared access with the way to approve it while it isn't ("On the phone:
  Settings (parent code) › Pause all restrictions › App access › <app>: <access>"; Alarms & reminders add ", then open
  <app> once while restrictions are paused" - Etar's first start outside a pause crashes, E4's M2). A stored row for an app that no
  longer asks is inert (no switch, no word). While the phone has reported its apps without `app_permissions_v1`, one
  `row-note` "Update Vibb One to manage apps' permissions" sits above the Allowed list.
- **Show as a media player on the lock screen** (2026-11-04, the user: "En bryter per app i pwa, tastefully
  implementert"; migration `0127_lock_media_apps.sql`, `device_lock_media_apps`): the phone's lock tile shows another
  app's playback only for a media app (its manifest's category audio or video, or a browser - so a messenger's voice
  message never shows); this switch counts one more app on this phone (a podcast app without a category, AntennaPod in
  E9's M2). In the same Permissions details, after the camera switch, for every allowed app the last report has
  installed and not preinstalled (`app_permissions::lock_media_offered`, `UnifiedAppRow.third_party`; a package in the
  grammar, never Vibb, Vibb music or Play's core) - so an app the report says nothing else about gets the details with
  this switch alone. Default off; its help says plainly what it shows and where ("While it plays, the app's title and
  picture show on the lock screen - before the code is typed. Music, video and browser apps do this already; turn on
  for other apps that play audio, such as podcast apps." - QA L1: a messenger switched on shows the sender and the
  avatar there): the report has no category, so the switch is never hidden for an app that already counts, and
  turning it on there changes nothing. It needs no look, so it adds nothing to the row's line. Its own auto-saving
  form, `POST /devices/{id}/apps/lock-media` (`devices::save_lock_media`, the checkbox `lock_media=on`, missing = off,
  another value a 400 `error.not_lock_media`), back to the details open as the camera's
  (`?perms=<package>#perms-<package>`); the camera's and its handler share `devices::perms_target` (the grammar, the
  phone, Play's core and Vibb refused at the row as `perms`; Vibb music too for this switch). **On** only for an app
  the page offers it for (`devices::lock_media_offered_apps`: allowed by the stored allowlist and installed third-party
  in the last report; else refused as `perms`), and it first prunes the phone's rows of apps no longer offered (one
  uninstalled or switched off since), so stale rows never fill the cap of `MAX_ROWS_PER_DEVICE` 200 a phone
  (`?notice=lock_media_full`); **off** is accepted for any package in the grammar, so a row can always be cleared. A
  stale row until then is inert (the launcher checks the allowlist itself). The policy's `lock_media_apps` (a sorted
  list of package names, always sent; a failing read sends `[]` with an error log - losing it only hides a tile, QA
  L4). Tests: `device_pages::the_lock_media_switch_returns_to_its_open_details`, `app_permissions::tests`,
  `device_api::policy_json_keys_snapshot`, `device_api::a_lost_lock_media_list_sends_none`.
- **Every word is in one table**, `app_permissions::words` (the refusals too, through `devices::toggle_refusal`'s
  `perms`/`perms_full`), marked `// i18n: moves to i18n.rs (design 69)`; the templates print fields only.
- **The form** (`POST /devices/{id}/apps/permissions`, `devices::save_app_permissions`): `package_name`, the checkbox
  `camera=default` (checked = the camera allowed; the field missing = off, an auto-saving checkbox; an explicit
  `deny` works too), the hidden `from`. It writes `device_app_permissions` (no row = the default; at most
  `MAX_ROWS_PER_DEVICE` 200 a phone, else `?notice=perms_full` at the row), nudges the phone and returns to its own
  details **open**: `apps_url("perms=<package>", from, "perms-<package>")`, and `view_apps` renders that package's
  `<details>` with `open` (`?perms=`) - a save never lands at the top and never closes the details. Refused at the
  row (`?notice=perms&app=app-<package>`, `settings_log::refused`): Play's core and Vibb; a 400 for another `camera`
  value or a package outside the launcher's grammar (`app_display::valid_package_name`; no row has one); 404 an
  unknown phone; a DB error 500. The report's `access` rides on the row (`UnifiedAppRow.access`, one parse).
- **The policy**: `app_permissions` (`[{package_name, camera: "deny"}]`, always a list) from the same table; a failing
  read is a 500 for the whole policy (`device_api::db_error_is_500_not_default`), never `[]`. The report's `access` is
  kept with known keys and values only (`AppAccess::of`, a `lenient_*`-style deserializer).
- Tests: `device_pages::the_permissions_form_returns_to_its_open_details` (with the nudge and a signed-out POST),
  `device_pages::the_permissions_cap_refuses_the_201st_app`, `app_permissions::tests`,
  `device_api::policy_json_keys_snapshot`, `app_updates::the_installed_apps_contract_raises_app_updated`.

## Device › Music (design 31 step 7, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **The page** (`GET /devices/{id}/music`, chained with the settings form's POST; `device_pages::view_music`,
  `device_music.html`, its parts from `handlers::music::device_card`; the device page's row "Music · 6 in library",
  "None" with nothing on, "Needs attention" (orange) while the page shows a warning, "Couldn't read" on a failed
  read): warnings at the top (Vibb One can't deliver music yet, the music app not reachable), **Music app** (the catalog
  switch, "Not in the catalog ›" to `/music/settings#app` without a catalog row), **Library** (one switch per entry,
  `#music-entry-<id>`, the phone's error for the entry under its name while it is on), the **settings form**
  `#music-settings` (one form, `v=2`, design 30: Mobile data, Storage limit and Volume cap pickers, Storytel - groups
  only for their footers), **Sonos speakers** and **On the phone** (only once the phone reported on music: the music
  app's version with its package and code behind ⓘ - `device_pages::music_app_row`, About's too -, the library's
  state with its size behind ⓘ, downloads waiting, the storage rows, the Storytel login; the report's age under the
  group, local). A phone not set up has the page. Every read is a 500 on a DB error ("Couldn't read this phone"),
  never an empty library or default settings.
- **Attention** (qa-31-s7-code M1): `handlers::music::music_warnings` (typed `MusicWarnings`) feeds both the page's
  warnings and six orange rows - "Vibb One can't deliver music yet" and "Music app not reachable" (→ the page),
  "Storytel refused the phone's login" (→ `#storytel-title`), "Music over the storage limit" (→ `#storage-title`),
  "Update Vibb One and the music app" / "Update Vibb One" / "Update the music app" (`OlderApps::title`, → `#music-settings`), "N music entries fail on the phone" (only entries on for the
  phone, one row, → `#library`); `attention::Sources::music_ticked` carries the entries on.
- **Where the forms return** (§5.1, `tests::device_pages::music_forms_return_to_their_groups`, built from the
  rendered forms, each fragment checked to hold its form): the app switch to `#music-app`, refused to `?music_notice=from_play|not_an_app#music-app` (the reason in that group), a catalog row
  deleted since the page loaded to `#music-app`; an entry's switch to `#music-entry-<id>` (refused
  `?music_notice=too_big&music_entry=<id>#music-entry-<id>`; an entry deleted since to `#library`); the settings form
  to `#music-settings`, Sonos to `#music-sonos` (refused `?music_notice=sonos_*#music-sonos`). The policy JSON and
  the music contract files are unchanged.
- Tests: `src/tests/device_pages.rs` (the row, the groups, the redirect table, refusals and failed reads, a phone not
  set up, the Music tab's and an entry page's links), repointed `music.rs` (the settings form, the older-phone
  warning, the storage rows, the ticks and the app switch, Sonos, the report's rows) and `music_storytel.rs` (the
  Storytel switch's note and warning, no secret on the phone's page).

## The phone's summary (design 31 step 10, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **`/devices/{id}`** (`devices::view_device` → `render_device`, `device_detail.html`; a sub-page header "‹ Devices",
  `partials/tab_bar.html` alone), top to bottom: the **header** (the large avatar and dot, the name, and since
  2026-11-01 one or two status lines - "Tidier status" below; "Disconnected · access removed" keeps "Connect again ›"
  to Advanced › Connection; the lock only while a rule locks - `devices::badge_and_screen`, the Devices list's rules,
  so no end unless the phone reports Phone Setup's zone -; today's bar while a line says the screen time); **Attention** (`attention::items`, the list's count); the
  **foreign-app warning** with its steps (`#foreign-access`); **Quick Actions** (`#quick`: while the last report has the restrictions paused, one plain line first,
  `.quick-note` - "The pause ends by itself, or tap Lock again to end it now.", an older launcher's own words; no red
  since 2026-10-26: the Attention row "Restrictions paused ›" that lands here is the one red alert -; +15 min, +30 min, +1 h,
  Pause a rule… as a `<details>` sheet with only the rules that apply, Lock again while a pause or Play install mode
  runs, Unlock for 2 h… behind a `<details>` confirm while no pause is on - both only for a `window_relock_v1`
  launcher -, Locate as a link to `/devices/locate?device={id}`; an open sheet spans the grid; after Lock again or
  Unlock the summary loads `auto-reload.js` (`?v=4`, every 3 s, the place kept, never once a save started or while a
  `<details>` is open) while the newer of the two is unanswered or answered but not in the newest report yet -
  `restrictions_paused` after Unlock, no pause or Play install mode after Lock again -, never after a refusal or a
  cancel and never 2 minutes after the press; while it waits the "Sent. …" line stays on every reload, from the
  command itself (`window_relock::waited_for`/`waiting_flash`; `auto-reload.js` drops the `?notice=`,
  qa-pwa-fixes-a-code Q-2): `window_relock::waiting_for_phone` (`RelockCard::wait_for_phone`;
  `window_relock::tests::the_summary_waits_for_the_phone_after_lock_again_or_unlock`,
  `tests::window_relock::the_summary_reloads_until_the_phone_shows_lock_again_or_unlock`; `pwa-fixes-a`); **Today** (`#today`:
  `lifts::today` - the lifts made since the local midnight and every pause still running - with End now, today's
  24b answers and any still waiting, the Parent PIN notices; only when there is something or its flash); **Blocked Apps** (`#blocked-apps`, Allow these apps…);
  **Locked Again** (`#relock`, the last relock's findings); the **Settings** rows (Calls & Contacts, Apps, Music,
  Time, Lock Screen & PINs, Home Screen, Web Filter) and About This Phone / Advanced. A phone not set up has the
  header, "Set a Parent PIN before setup ›" while managed without one, **Connect This Phone** (`#access`: the live code, the typed-code pause, New Setup Code - a POST -, Set Up
  with QR Code ›) and every row live (each sub-page answers before setup; what is saved reaches the first policy).
- **Where the forms return** (§5.1, `tests::summary::every_form_on_the_summary_returns_to_its_group`, built from the
  rendered forms, each action carrying its group's fragment): the lifts (`back=summary`) →
  `/devices/{id}?done=extra|pause|gone#quick` (`lifts::summary_group`; a refused pause says why there), End now →
  `?done=ended|over#today`; `end-windows`/`start-pause` → `?notice=…#quick`, `allow-blocked` →
  `?notice=allow_blocked_sent#blocked-apps` (the group shows for its flash even when the list is gone). A lift without
  `back` (an older tab) lands on the summary too. The commands' payloads, `allow_form_value` and the once-per-relock
  rule are unchanged.
- **Reads**: the phone, its settings, its last report, its lifts, the catalog's pins and the 24b answers are a 500 on
  a DB error (without the pins an imposter would be offered, without the answers an answered Allow; the Devices list's
  badge reads them leniently); a row whose read fails
  says "Couldn't read" (logged), as before.
- **Typed (M5)**: `window_relock::{Blocks, BlockItem, BlockWhy, ForeignView, Findings, flash}`
  replace `blocks_note`, `more_blocks_line`, `foreign_note`, `relock_notes`, `answer_line` and `flash_text` (deleted
  with their unit tests); apps are named by the phone's label (`window_relock::app_names`), the package behind ⓘ;
  times in the Phone Setup zone, a phone time with its age. `parent_pin::when_text` and `ago` are gone;
  `parent_pin::notices` returns typed rows. The security log keeps its UTC lines.
- Tests: `src/tests/summary.rs` (the header states, the badge and bar, the quick actions, the redirect table, Today in
  local time, the not-set-up variant, the 500s), `src/tests/window_relock.rs`, repointed `command_expiry.rs`,
  `device_api.rs`, `device_pages.rs`, `devices_list.rs`, `enrollment.rs`, `hardening.rs`, `parent_pin.rs`,
  `time_pages.rs`, `time_rules.rs`; `window_relock::tests`, `parent_pin::tests`, `lifts::tests`, `attention::tests`.

- **Now and Activity (design 48, `docs/design/48-activity-log.md`; supersedes Today and the round-5 Recent above)**:
  **Now** (`#now`, `show_now`) keeps the state only - today's lifts and the running pauses with End now
  (`lifts::summary_group("lifts")` = `now`, so End now lands on `?done=ended|over#now`) and the Parent PIN notices;
  the 24b answers (`RelockCard.answers`, `window_relock::answer`) are gone - a command's row is its one line
  (`last_answers` stays for the blocks). **Activity** (`#activity`, after Locked Again, before the Settings rows;
  `handlers::activity::rows`): every command of the phone (`partials/recent.html`'s `row` macro, Cancel →
  `?cancel=done|too_late#activity`) and its `parent_events` (`notifications::inbox_row`, no avatar; the `open` form
  carries hidden `from=devices`, `device=<id>`, so a pruned event or a non-local target lands on
  `/notifications?from=devices&device=<id>#inbox`), merged newest first by when asked / happened, the last 7 days, at
  most 10 rows, plus every command still cancellable at any age outside both; each row's time is `Zone::at` ("13:41",
  "yesterday 14:50", "15 Oct 14:02"), the exact one in `title`; a last row "Everything for this phone ›" to that
  inbox. Shown when it has a row or the Cancel's line. A failed read is a 500. Tests: `summary::activity_*`,
  `an_event_opens_where_it_happened_or_this_phones_inbox`, `now_*`.
- **Now went (the user, 2026-10-20, after 0.31.0: "kan vi ikke bare ta vekk hele den now biten?")**: no `#now`, no
  `show_now`, no Parent PIN notices on the summary (the uses and lockouts are Activity's events; Lock Screen & PINs
  keeps its Recent use list; `parent_pin::notices`/`Notice` and the card's `notices` are deleted). Only the lifts End
  now can still end (`can_end`: the running rule pauses, filtered in the summary's view) are rows at the end of
  **Quick Actions** - an ended pause or added screen time is history, Activity's since 2026-11-02 (below) - in a `.group` after the `act-grid` (`l.what`, the
  sub-line `l.when · l.by`, `l.status` as the value, End now posting to `/devices/{id}/lifts/{id}/end#quick` with
  `back=summary`); `lifts::summary_group` is always `quick`, so End now lands on `?done=ended|over#quick` with the
  flash in that same group, which shows while it has a lift row or its flash. **One clock per Activity row**: an
  event's summary that carries its own clock (design 38's "14:09 · no shutdown seen …", the Parent PIN's "Vibb One's
  Settings · 09:20") loses it when it is the row's (`activity::without_own_clock`: a leading "HH:MM · " or trailing
  " · HH:MM" equal to the clock at the end of `Zone::at`'s words, or all of them), so the row reads "no shutdown seen …
  · 14:09". The inbox keeps the summary whole: its time is `Zone::relative` ("3 min ago"), never the same words.
- **Lifts in Activity (the user, 2026-11-02, after Device › Time Rules lost Recent on 2026-11-01)**: every
  `time_lifts` row but a rule pause the server still sends (`NOT (target = 'rule' AND ended_early_at IS NULL AND
  expires_at > now)` - exactly Quick Actions' `can_end` rows' complement, so each lift shows once on the summary) is an
  `ActivityRow::Lift` (`activity::lift_row`), mixed in by when it was granted (`created_at`), inside the same week and
  ten-row cap (its own read is `LIMIT 10` too). Laid out as a command's row without Cancel, read-only: the label
  "Skole paused for 30 min" / "All rules paused for 1 h" / "+15 min extra screen time" (nb "Skole satt på pause i 30
  min", "Alle regler satt på pause i 1 t", "+15 min ekstra skjermtid"), the value "yesterday 14:00 · Mamma · Ended
  14:30" - the grant's `Zone::at` (exact in `title`), who granted it only in a family with several accounts
  (`parents::Names::granter`, as a command's requester), then the word: a pause "Ended {at}" (its expiry) or "Ended
  early {at}" (End now), grey (nb "Sluttet", "Avsluttet tidlig"); extra screen time "Added" (nb "Lagt til"), green, at
  once - it has nothing to end (its 12 h delivery window isn't shown). `device.summary.lift.*` in both catalogs. The
  phone's own pause ("Unlock for 2 h", a command) and the Parent PIN's pause (an event) were Activity rows already.
  Test: `summary::activity_shows_ended_pauses_and_extra_time_by_when_granted`.
  Tests: `summary::quick_actions_*`, `end_now_lands_on_quick_actions_when_nothing_else_is_left`,
  `an_event_row_shows_its_clock_once`, `activity::tests`.

## The Apps tab (design 31 step 11, `docs/design/31-pwa-redesign.md` §3.3)

- **`/apps`** (`tracked_apps::list_apps`, `apps_list.html`): **On phones** - a row per phone with "N allowed"
  (`allowed_count`: what the phone's Apps page switches on, the device page's Apps row's number too - reported apps on its allowlist, not Play's core, plus catalog
  apps chosen for it and not reported installed; the launcher not counted; "Not set up" before enrolment) -, then the
  **Catalog**: the icon tile (the catalog default's icon on its colour, else a letter), the name phones show with the
  admin name in the sub-line, "N phones" (`device_tracked_apps`; the launcher "all phones"), the version from the tag
  (`version_of`) or a badge instead (Off / Update failed - the in-memory last sync / No release yet), the launcher
  first; the footer and Add App. Phone rows link through `phone_apps_href` to the phone's Apps page
  (`/devices/{id}/apps?from=apps`, `?from=app:<id>#app-<package>` from an app page; step 6, and "Opened from the Apps
  tab" there: Back returns here).
- **`/apps/tracked/{id}`** (`render_detail_full`): the sub-page header; Version, Last checked (local, relative,
  `display_time`), Check Now with the running state or the failed check's title + ⓘ; Show on phones as (the
  `app_display_form` partial); On phones (the phones with it ticked, every set-up phone for the launcher); Source &
  Signing (a `<details>`, open when a refused save/upload is in it): the release, file, package (+ candidates), key
  (short, whole in ⓘ), Forget Package and Key… (a `<details>` confirm), the Details form, the three switches (one
  form each, `<noscript>` Save), Upload for manual apps; Remove App…. Since the browser pass's fix round every form
  returns to its group (`#check`, `#display`, `#details`, `#updates`, `#release`, `#upload`), and one in a closed
  `<details>` with it open (`?open=display`, `?open=source`: the "Show on phones as" save no longer folds up under
  the parent, 6f) (`tests::tracked_apps::every_form_on_the_app_pages_returns_to_its_page` posts each rendered form).
- **Sync failures are typed** (`SyncProblem` + `Failure { problem, detail }`), set where the error happens
  (`fetch_latest_release`, `sync_release`, `check_pins`, `check_new_package`); the page shows `title()` (at most 6
  words) and keeps the raw reqwest/sqlx/file error behind ⓘ and in the log; a temp-file or rename error is `Server`,
  an asset over 1 GB `TooBig` (`stream_to_file`/`download_asset` type their own errors), a 404 `RepoNotFound`
  (`github_problem`), no match `NoRelease` (`release_from`). Upload and package-confirm results use the same title +
  ⓘ (green when done) in their own group (`Note::Upload`/`Package`): both forms post to an action with the group's
  fragment (`.../upload#upload`, `.../confirm-package#release`) and a success redirects to
  `?notice=uploaded#upload` / `?notice=package_confirmed#release`, so the result is in view where the parent was
  (qa-31-s11-code M1).
- **Add App** (`tracked_app_add.html`): name, source (three radios before the rows - GitHub, F-Droid, Upload - so
  CSS, no script, shows only the chosen source's fields), repo; filter, tag prefix, package and pre-releases under More
  Options (`TrackedAppAddTemplate::more_open`); F-Droid's package and repo in `.fdroid-only` with the one-source line
  (`play-and-updates.md`, "F-Droid source").

## The Music tab (design 31 step 12, `docs/design/31-pwa-redesign.md` §3.4)

- **`/music`** (`handlers::music::show`, `music.html`): the Library (one row per entry: cover or category tile,
  name, "NRK series · 98 episodes · 2 phones", the live status on one line, then Check now and the Offline select;
  `?edit=1` shows ↑ ↓ instead, the move redirecting to `/music?edit=1#entry-<id>`), Add (Podcast or NRK link and
  Upload your own files as `<details>` forms that open on a refusal, From Storytel › "Signed in"/"No login", Import
  from Vibb ›), On phones ("Anna · 6 of 9": ticks of the whole library, linking through `phone_music_href` to the
  phone's Music page, `/devices/{id}/music` - from an entry's page to its row, `#music-entry-<id>`), Categories › and
  Settings ›. Its CSS is in `style.css` (since `?v=14`, the
  "Music tab" section; the code QA fix round moved it out of a per-page partial).
- **Sub-pages** (sub-page header, `partials/tab_bar.html` alone): `/music/settings` (Check for new episodes as an
  inline picker, the Storytel login with its notice and Remove Login…, the music app's catalog row, Files no entry
  uses with Delete Files…), `/music/categories` (the `<details>` editors, Delete Category… for an unused one, Add a
  category), `/music/import` (GET the form; `POST /music/import` renders this page with the preview). GETs chained
  with their POSTs on one route (L5).
- **Design 47 (server round 5)**: From Spotify › opens Add from Spotify (`/music/spotify`: the add form first, a
  "Spotify setup" row last, an attention row on top while the setup isn't done), and the setup - Your Spotify App,
  Children's Accounts with a "Plays on" picker each - is Music › Settings' Spotify group after Storytel, whose "On
  phones" row now opens to one Storytel switch per phone (`docs/notes/server/music.md`, Spotify).
- **Where forms return** (§5.1, `tests::music::every_form_on_the_music_pages_returns_to_its_page` posts every rendered
  form): sweep and orphans → `/music/settings#sweep` / `#orphans`, the Storytel login and its removal →
  `/music/settings#storytel`, the catalog row → `#app`, categories → `/music/categories?open=<id>#category-<id>` (the editor stays open; delete:
  `#categories`), the import → `/music/import#import`; an entry's save → `/music/entries/<id>#settings`, Check now from
  there `#status`, cover `#cover`, files `#files`/`#file-<id>`; the row's Check now and Offline → `/music#entry-<id>`;
  delete → `/music#entries`. Each action carries its redirect's fragment (§5.1.4). Every delete is a `<details>` confirm, no `confirm()` left
  (`the_music_deletes_are_behind_a_confirm`).
- **The entry page** (`music_entry.html`): Cover (with the kind and link) → Episodes/Books (`#status`) → Settings (one
  form, the resume switch in it) → Files (each file's row opens its Delete File… confirm) → On phones (each phone's
  report on the entry as its sub-line, `SweepView::phone_lines` → `(device, line)`; tests read it through
  `tests::music::phone_line`) → Delete Entry…. The shelf (`music_storytel.html`) keeps its shape: a one-line intro, the
  rest behind ⓘ; its links go to `/music/settings#storytel`.
- **Local times** (M4): `SweepView`, `storytel_status` and `StorytelError::message` (the shelf's cooldown) take the
  Phone Setup zone (`Zone::when` for what happened, `Zone::until` for "Next try", "Check again after" and the
  cooldown's end; dates by the local day too). Every form posts to its group's fragment, every auto-saving form has a
  `<noscript>` Save (`tests::music::check_forms`).

## The Locate tab (design 31 step 13, `docs/design/31-pwa-redesign.md` §3.6)

- **`/devices/locate`** (`handlers::locate::show_locate`, `device_locate.html`; the route is unchanged): the large
  title, then with several phones and none chosen (or an unknown id) the phones as rows (*since design 85 step 2 the
  Alle view, the section Locate's "Alle" view below*; "Online · last seen 13:05" ›, "seen 3 min ago" until 2026-11-01;
  as on Devices); one phone is shown at once. A chosen phone: (*since design 85 revision 3 the Alle view's chips over
  the map, no switcher - "Locate's one-phone view" below*) the **phone switcher** (plain links to `?device=`, the
  state as a dot and in each `aria-label`, "Anna, online" (since 2026-11-01 the Devices dot and word, "Off or out
  of touch says so"); the chosen one `aria-current="true"` - the tab bar has the
  page's one `"page"`), the **map** (Leaflet, OSM tiles with design 33's origin-only Referer, unchanged) with the
  trail, the **last fix** (kept past the window while location is on, with "Last seen 9 days ago"; "No position yet." without one, "No position kept while location is off." under Off, which erases them - security review P1, M1 of round S3) as age and accuracy in the Phone Setup zone ("10 min ago", "Within 40 m", the exact time in
  `title`; the marker's popup says the same, built with `textContent`), the newest Update Location's **answer row**
  (only while it says something - "The answer row" below), the waiting line and its poll, **Update Location**; the two **warnings** as
  orange rows (location off, erasing not ready); **Ring, Stop Ringing, Lock** as three tiles, **Erase Phone…** as a
  `<details>` confirm with the type-the-name field; the footer with Lock's note behind ⓘ; **Recent** (the 10 newest:
  "Lock" · "10:58 · Done", the line under it, **Cancel** while it waits); **Location Settings** (When to locate +
  Every as one form - `location-policy` needs the mode on every post -, Keep history for, both auto-saving).
- **What OpenStreetMap sees** (security review P8, re-checked 2026-10-26, accepted - design 33): the tiles are
  `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, no query string, so the request carries the area looked at (z/x/y),
  the parent's IP and browser User-Agent, and `Referer: <the PWA's origin>/` (the tailnet host name; never a path or
  a `?device=`). `no-referrer` isn't an option: OSM answers a browser tile request without a Referer with "403 Access
  blocked", and its tile policy forbids stripping it and discourages a proxy (design 33's two options). No
  `crossorigin`: the tiles are plain images, sent without CORS, and no OSM cookie reaches `tile.`. A self-hosted tile
  source stays out of scope.
- **Where the forms return** (§5.1, `tests::locate_page::every_form_on_the_locate_page_returns_to_its_group`, built
  from the rendered forms): Update Location → `?device={id}&requested=1#location` (the map and the wait in view; the
  poll's reload goes to `#location` too), Ring / Stop Ringing / Lock / Erase → `#actions` (Recent right under it),
  Cancel → `&cancel=done|too_late#recent` (its line in Recent), the two pickers → `#location-settings` (the last
  group of a long page: a `<noscript>` Save or a save after a refused erase must not land at the top). Erase
  Phone…'s form posts to `.../command/wipe#erase`: a wrong name renders the page there with the confirm open.
- **Robust**: the actions queue only for an existing phone (`queue_command` checks it inside its transaction - the
  replace, the used-up mark and the insert are one transaction; an unknown phone is a 404, a DB error a 500 "nothing
  was queued"); every read on the page, `locations.json` and `locate-result.json` is a 500 on a DB error, never an
  empty Recent or a made-up setting.
- **Typed (M5)**: `DeviceCommand::status` (`models::CommandStatus`, `CancelledBy`, `Unconfirmed`) replaces
  `status_line`; `models::command_label` names every command ("Stop Ringing", "Lock again", "Unlock for 2 h", never the
  id); `locate::status_words` words a status in local time ("Expires 12:30 if the phone isn't back."); `locate::reword`
  turns the launcher's answers into words (an unknown one is shown as sent); `locate::Fix` is the fix's age and
  accuracy (`describe_fix` is gone).
- Tests: `src/tests/locate_page.rs` (the redirect table, the erase refusal, the switcher and the list, local times
  and the answer, Recent's labels and Cancel, the warnings and pickers, unknown phones and failed reads),
  `handlers::locate::tests`, repointed `locations.rs`, `command_receipts.rs`, `command_expiry.rs`, `time_rules.rs`.

- **The answer row** (2026-10-25, branch `pwa-fixes-d`; the user: a stale "Found within 5 m · Answer to Update
  Location · 14:29" under a point from 20:41, "Er det viktig informasjon å vise der?" - no): `locate::answer_state`
  (pure) reads the newest `locate` command that wasn't cancelled (a cancelled one never hides the failure before it,
  as in Requests; qa-pwa-fixes-d Q-9) and its `CommandStatus` with the newest fix's own time (`captured_at`):
  **asking** while it waits, is sent (not yet confirmed, offered again, can't confirm) or is received in time -
  "Asking the phone…", left out right after the press (`?requested=1`: the wait's own `#fix_wait` line
  says it); **found** - a done answer that isn't one of the launcher's no-fix words (`LOCATE_FAILURES`: location off,
  no fix, too old) - for `ANSWER_SHOWN_MINUTES` (10) after `acknowledged_at`, and only while the newest fix is the
  one it found (taken after the ask, not after the answer: an older fix - an upload that lagged, a mock kept out -
  would contradict it, a newer one says it itself; Q-8), "Found within 5 m" with its time ("14:29", exact in
  `title`; an older launcher's plain "ok" reads "Found"); **failed** - those words or a failed
  result ("Location is off on the phone", "The phone couldn't find its location"), never fetched within its hour
  ("No position - the phone is offline", the ask's time), received but never answered or never confirmed ("No
  position - the phone didn't answer") - until a fix taken after the answer (or the ask).
  Otherwise no row: the newest point and the time row say it. No "Answer to" line under it any more;
  `data-row="locate-answer"` with `data-state` (`asking`/`found`/`failed`) for tests. Tests:
  `handlers::locate::tests::the_answer_row_comes_and_goes`, `locate_page::the_answer_row_shows_only_while_it_says_something`.
- **Requests (design 48 §1.4; supersedes Recent below)**: `locate::requests` (section `#recent`, titled by the
  partial's `show(recent, "Requests")`) lists `LOCATE_KINDS` by `CommandStatus` (`in_requests`): in flight at any age
  (`Waiting`, `Sent{NotYet|OfferedAgain|CantConfirm}`, `Received{overdue: false}`, `Erasing`), gone wrong within 24 h
  (`Failed`, `Expired`, `Sent{Lost}`, `Received{overdue: true}`), and the newest Ring / Stop Ringing / Lock / Erase
  whatever its status; never `Cancelled{Parent}` - skipped before the newest of each kind is picked, so a cancelled request never hides the last real one -, never a Done locate. Shown when it has a row or the Cancel's line;
  Cancel still lands on `#recent`. Every command row's time is `Zone::at` (the clock on every row). Tests:
  `locate_page::requests_*`.

## Locate: history, span, each page its Recent (design 44, `docs/design/44-locate-history.md`)

- **Each Recent its own commands**: `locate::recent(state, device, kinds, back, cancel, zone, now)` reads the 10
  newest of `kinds`; Locate shows `LOCATE_KINDS` (locate, ring, stop_ring, lock, wipe), the phone's summary
  (`device_detail.html`, a `#recent` right under `#quick`, shown once it has a row or a cancel line; the row Today's answer already shows is left out unless it can still be cancelled) shows
  `OVERRIDE_KINDS` (end_windows, start_pause, allow_blocked). Install Now's row stays on the launcher's app page
  (`install_now::install_row`). One macro, `partials/recent.html` (`recent_group::show`), renders both; its Cancel
  posts a hidden `back` (`summary` → `/devices/{id}?cancel=…#recent`, anything else → `/devices/locate?device={id}&
  cancel=…#recent`; `cancel_command` takes the form).
- **The span** (`locate::SPANS`: 12 hours (default), 24 hours, 7 days, All kept = 0): a segmented row of four
  `<a role="radio" aria-checked>` above the map on both pages, links with `?span=` (Locate's with `#location`) as the
  no-JS fallback; the server renders the count and the list for `?span=` (any other value is 12). `static/locate.js`
  remembers it per browser (`localStorage` `handy-locate-span`; the address's span wins and is remembered): on Locate it
  redraws in place (`preventDefault`, nothing to save, nothing to jump), on the Locations page it opens the page (`location.replace`: Back goes to Locate, not through the spans)
  with the span (the server words and pages the rows), and a remembered span other than the rendered one reopens it.
- **The "Locations" row** under the map's group: the count in the span ("14 in the last 12 hours", "3 kept", "None
  yet", "0 in the last 12 hours · newest 3 days ago" - `locate::locations_value`, worded the same by `locate.js`
  `countText`), linking to `/devices/{id}/locations?span=`.
- **`/devices/{id}/locations`** (`locate::show_locations`, `device_locations.html`, tab Locate, back to Locate): the
  pick, the map, then every fix of the span newest first, 200 a page, "Show older" with `before=<captured_at>,<id>`
  (URL-encoded; `captured_at DESC, id DESC`, fixes arrive out of order) and "Newest" on an older page; each row the
  time as `Zone::at` (exact in `title`) and "± 25 m" / "accuracy unknown". A row's tap (JS: `role=button`,
  `tabindex`, Enter/Space) scrolls `main` so the map box sits at its top padding, under the status bar (an offset in the scroll: `scroll-margin-top` needs Safari 14.1), then
  pans to the fix with a popup of its words. **No-store** (`admin_auth::no_store_path` `["devices", _,
  "locations"]`): it holds every kept fix. Nothing posts; 404 for an unknown phone, 500 on a failed read.
- **`locations.json?since=<hours>`**: the span's points oldest first (`captured_at ASC, id ASC`); when none is that
  recent, the newest fix flagged `"newest": true` (the marker never disappears, the trail doesn't join it, it isn't
  counted). No, 0 or a bad `since` is everything kept. The span's start is formatted like the stored `captured_at`
  (`YYYY-MM-DDTHH:MM:SS.mmmZ`), so the text compares as the time.
- **`static/locate.js`** (ES5, `?v=`) replaced the inline script: the phone, the newest fix's `captured_at` and popup
  words, its age and the span come in Askama-escaped `data-` attributes on the map box (`js_string` is gone); popups
  are text nodes. `L.map(…, { preferCanvas: true })` (All kept can be thousands of points), the trail plus a 3 px
  `circleMarker` per fix, the marker on the newest. After Update Location (`data-waiting`) it polls
  `locations.json?since=1` and `locate-result.json` every 5 s for a minute, as before.
- Tests: `locate_page::each_recent_lists_its_own_commands_and_cancels_back_to_it`,
  `locations_json_takes_a_span_and_keeps_the_newest`, `the_locations_row_counts_the_span`,
  `the_locations_page_pages_every_fix_once`, `admin_auth::tests::no_store_paths`, repointed `locations.rs`,
  `command_receipts.rs`, `install_now.rs`, `time_rules.rs`; `jstest/locate.test.js` (a fake Leaflet).

## The rest of Settings (design 31 step 14, `docs/design/31-pwa-redesign.md` §3.6)

- **Sub-pages of Settings** (the sub-page header: "‹ Settings" and the large title, `partials/tab_bar.html` alone;
  *since design 84 the band, with "Back to Settings" only spoken*):
  Web Filter (`/dns`), Blocked Activity (`/dns/log`), Wallpapers, Phone Setup (`/settings/provisioning`), Account &
  Parents (`/account`; design 62 added its Name and Parents groups - `connectivity.md`, "Parent accounts"), Security Log (`/security`), Backups, Software Updates (`/updates`). No POST route changed.
- **Software Updates shows versions only** (the user, 2026-11-04: the server never acts on its host): this server's
  version (with "(Docker image)" in the container), the newest stable `server-v*` release from the in-memory cache
  (`system_update::ServerRelease`, read at start and in the background when an hour old - "Checking…", "Couldn't
  check", "Version X is out" or "You have the latest version", nb "Du har nyeste versjon" - E9 L5), and How to Update behind its ⓘ: in Docker the image pull (Watchtower by
  itself, or `docker compose pull && docker compose up -d`, and the version for a pinned `HANDY_VERSION`), on a systemd
  install the project's `update.sh` command (`settings.updates.update_command`, always `palchrb/vibb-launcher`; a
  `SERVER_RELEASE_REPO` that differs is named in a line under it, never put in the command - `.env` is the service
  user's, security QA L1). A backup's systemd restore command is shown only for a plain
  `backup-[0-9A-Za-z._-]+.zip` name, single-quoted (`backups::shell_plain_backup_name`, `shell_quote`; any other name
  says to rename the file first, security QA M1). Its footer says the server never changes anything on its machine. No form, no
  host state read (no apt, `tailscale version` or reboot flag), no flash; an old `?done=` is ignored. Diagnostics ›
  Page timing stays. The update/restart/OS/Tailscale/reboot buttons, both schedules, the busy banner and "Run the
  installer again" (`security::REQUIRED_WATCHER_SCHEMA`) are gone, with their routes.
- **Settings saves in the Security Log (Q29, 0.54.0, `src/settings_log.rs`)**: `settings_log::log_saves`, inside
  `require_full_auth` on the admin routes, writes `settings_saved` ("Settings saved") for a POST answered
  303 to a page of this app (decision 10: the success path; a 4xx, a re-rendered form, a redirect to `/login` or off
  the app are not saves; `/music` is). Detail: `<page> · <route> (<param> <value>, ...); <route> ... ×N` - the
  Location's path, the matched route and its path ids (cleaned to `[A-Za-z0-9/._-]`), never form values; one row per
  parent and page from its first save for `MERGE_MINUTES` (10), up to 1000 characters. `NOT_SAVES`: commands,
  pauses and lifts, checks, read marks and the dismissed sign-in warning, the test push, backup create, upload and
  restore. Two 303s are no save (code QA M1, L2): **a refusal back to
  its page** (`?notice=parent_pin_invalid`, the Child PIN's, `revoke_unconfirmed`, `toggle_app`'s, the DNS
  `bad_list`/`not_a_domain_*`, `music_notice=`, a gone contact, rule or parent, the push refusals, a refused avatar)
  carries `settings_log::Refused` in its extensions (`settings_log::refused(...)`) - **a new refusal by redirect must
  be marked so** - and **a save whose handler wrote its own security event** (any kind but `settings_saved`, by the
  same parent, during the request: `music_sweep_saved`, `override_pin_changed`, ...) gets no second row; a success
  with a notice (`notice=uploaded`) is a save. Tests: `tests::settings_log`, `settings_log::tests`.
- **Notifications (design 45, server round 5)**: the hub's Notifications row leads to Notification Settings
  (`/notifications/settings`: This Device, Getting Notifications, Notify Me About), while the bell on the tab pages leads
  to the inbox (`/notifications`), which reads the rows it shows (`docs/notes/server/notifications.md`, step 2).
- **Where forms return** (§5.1; every action carries its group's fragment, so a save near the bottom never lands at the
  top, with or without JS; `tests::settings_pages::check_forms`): Web Filter → `/dns#lists|blocked|allowed|server`
  (`dns_filter::back_to` takes the group; a refused domain `?notice=not_a_domain_block|allow#blocked|allowed`, a list
  without a name or an http(s) address `?notice=bad_list#lists` with Add a List… open; a second tap on Remove goes to
  the group the form's hidden `list_type` names); a photo's switch → `/wallpapers#wallpaper-<id>`, an upload to the new
  photo's row, a delete (a `<details>` confirm, idempotent) → `#all`, a refused upload renders at `#add` with the reason
  under the field; Phone Setup → `?saved=1#saved` (a refusal renders the form again, 400, with what was typed - never the
  key - and the reason under the field, a pasted key not kept and said so, the remove tick kept); Unban →
  `/security?unbanned=<address>#banned` (the line only for an address no longer blocked). **Every success redirects**
  (post/redirect/get, qa-31-s14-code M1: a reload never restores, updates, restarts or backs up again):
  `/account?changed=password#password`, `/backups?done=backup|deleted|gone|schedule|upload|restore#<group>`
  (`backups::Done`; `restore` only in the container); the GET shows the line in that
  group (`settings::Flash` + `partials/group_flash.html`). Only refusals render in the POST's answer, posting to their
  group (`#password`, `#two-factor`, `#backups`, `#upload`, `#schedule`): a password or typed confirm never goes into
  a URL; a refused Restore… renders with that confirm open and the reason under the typed field. On a systemd install
  a backup's Restore… is an ⓘ with the SSH command for that file (`settings.backups.restore_command`; the server
  never restarts itself), and a POST to restore answers 400 "restores over SSH". `check_forms` asserts each fragment is the
  form's own group. A refused Web Filter add keeps what was typed (`&typed=`, `&name=`, `&url=`).
- **Testable without the working directory or the network**: `AppState::data_dir` (`data/`, a temp dir in tests)
  holds the backups, their schedule and a restore's marker; `ForkConfig::github_api` is a closed local port
  in tests, so Software Updates renders at once.
- **Confirms** (§5.6): Sign Out Everywhere…, Reset Two-Factor Login…, a waiting parent's Withdraw…, a photo's Delete Photo…, a backup's Restore… and
  Delete… (a backup's Restore… only in the container). Unban and a domain's Remove stay one tap.
- **Robust**: every page read is a 500 on a DB error, never an empty page (Web Filter, Blocked Activity, Phone Setup,
  Security Log); every Web Filter write too (it used to redirect as if saved); a password change and a 2FA reset move
  the hash or TOTP and the session epoch in one UPDATE (the first-sign-in change too), so another session is signed
  out exactly when the change is
  stored; Sign Out Everywhere answers 500 and keeps this session when the epoch can't move.
- **Sign Out takes this browser's notifications** (the user, 2026-10-25, `pwa-fixes-a`'s fix round): Settings' Sign
  Out form (`form[data-sign-out]`) has a hidden `endpoint` that `static/register-sw.js` (`?v=4`, `signOut`) fills from
  `serviceWorker.ready` → `pushManager.getSubscription()`; on submit the browser unsubscribes first and the form goes
  when that is done or after 300 ms (`SIGN_OUT_WAIT`), whichever is first. `auth::logout` deletes that one row, only
  when it is the signed-in parent's, then ends the session as before; no JS, no subscription or an empty field signs
  out exactly as before (`tests::web_push::sign_out_removes_this_browsers_subscription_only`,
  `jstest/register-sw.test.js`).
- **Three wrong passwords end the session** (design 59 step 2, qa-59-design Q59-11): every form that asks a signed-in
  parent for the password again - Account's password change, the first-sign-in change (`/auth/change-password`),
  Reset Two-Factor Login, Lock Screen & PINs' Show Child PIN, and Account's Add Parent and Withdraw (design 62) -
  counts through one helper (`admin_auth::reauth`,
  or `record_reauth` after `PasswordGate::change`): the account's `admin_users.reauth_wrong` (migration 0086), one
  `UPDATE` per answer, reset by a right password and by a complete sign-in, shared by the four - never the session,
  whose copy each request loads and writes back (qa-59-step2-code S2P-1: parallel wrong passwords counted from the same
  number, and a request held open revived a flushed session). The third in a row sets it to 0 and moves the account's
  `session_epoch` in the same statement - every session of the account ends, also one a request still in flight
  writes back -, flushes this one, logs `reauth_signed_out` and answers with `/login` (sign in again, with 2FA); a
  database error there signs out too. The second wrong one says "One more wrong one signs you out". Tests:
  `android_lock_page::the_password_forms_share_one_count`, `show_child_pin_needs_the_password`,
  `parallel_wrong_passwords_all_count`, `a_held_request_cant_bring_the_session_back`.
- **Texts (M5)**: the security log's event ids are labels (`admin::event_label`; every id the server writes has one,
  pinned by `admin::tests::every_written_event_has_a_label`, which reads the writers in `src/` - a new event needs its
  label there; an unknown id reads as its words), the row
  says who and from where, the time local (`Zone::when`, the exact one in `title`), the stored detail and the id behind
  the row's ⓘ; a ban's end with `Zone::until`; the live sign-in warning's lock end local (`auth::live_warning(admin,
  zone, now)`); a backup's time local. The schedule summaries are gone (the forms show the values); the backup
  and restore results are short `Flash`es; the update commands and the systemd restore command sit behind ⓘ.
- Tests: `src/tests/settings_pages.rs` (the redirect table for Web Filter, Wallpapers, Phone Setup and the Security
  Log; Web Filter's refusals, rows and unreadable page; Blocked Activity's values; Wallpapers' confirm and refused
  upload; Phone Setup's refusal and flash; Account's field errors, confirms and local lock time; the Security Log's
  labels, escaping and local times; Backups' refusals in their groups and both kinds' Restore…), `tests::docker`
  (Software Updates on both kinds; no route asks the host for anything; the old host files go at the start), `admin::tests`, repointed `admin_auth.rs`, `intake_limits.rs`,
  `provisioning.rs`, `wallpapers.rs`, `dns_blocklist.rs`, `deploy_scripts.rs`.

## Add Phone, sign-in, the tab pages (design 31 step 15, `docs/design/31-pwa-redesign.md` §3.7)

- **Add Phone in 3 steps** on the pages that were there: **Name** (`/devices/new`, `device_add.html`; "Next" makes
  the phone and redirects to Connect; an empty or > 40-character name (`devices::MAX_DEVICE_NAME_CHARS`) renders the
  form again, 400, what was typed kept, the reason under the field; the name is trimmed), **Connect**
  (`/devices/{id}/provision`, `provision_qr.html`: two choices, "QR code · New or reset phone" (the Wi-Fi fields and
  Make QR Code, the GrapheneOS/adb detail behind the footer's ⓘ) and "Code · Phone already set up" (New Setup Code,
  its minutes behind ⓘ)), then **the QR code** (rendered in the POST's answer inside `#qr`, the form posts there, so
  it lands on the QR with or without JS; its end local, `Zone::until`) or **the code** (`regenerate-code` →
  `enroll_code.html`, "Setup Code", shown once, `no-store`, its end and the typed-code pause local). Both end on
  **Done** → the phone's page for a phone not set up; for a connected phone both go back to Advanced › Connection and
  warn that a new code cuts it off. "Server setup needed" (no `LAUNCHER_SIGNATURE_CHECKSUM` - no QR form then -, no
  server address) is one red row with the env var / Phone Setup behind its ⓘ.
- **Refusals before any code**: a Wi-Fi name over 32 bytes, a password under 8 or over 63 characters, a 64-hex-digit raw key (since 2026-11-12, `devices.qr.password_raw_key`: AOSP quotes the password, so the phone would take the key as a passphrase), or a password without a
  name renders Connect again (400) with the reason under the field, never the password; nothing was made (the QR
  code's capacity can't be overflowed by a form). A payload too long for a QR code (a huge server address or key)
  is a 500 with a reason, checked before the code is made (the live code survives), never a panic; Connect's and the
  code page's DB reads are 500s, not 404s.
- **Sign-in pages** (`login.html`, `verify_2fa.html`, `setup_2fa.html`, `change_password.html`): one column
  (`.auth-card`): the name, a large title, a grouped form, a wide button; a refusal is one red line (`role="alert"`)
  above the form; the 2FA key shows once behind "Can't scan it?". Templates and titles only: `handlers::auth`'s
  logic (lockout, pending expiry, session cycling, epoch, CSRF) is unchanged.
- **The tab pages** (Devices, Apps, Locate, Music, Settings) include `partials/tab_bar.html` alone and open on their
  large title under a 44px bar (`.title-bar`: "+" on Devices and Apps, Edit on Music; Settings and Locate start with
  the title; *since design 84 on the band - the mark, the name, "+" and the bell - `band::tab`*). `partials/app_header.html` is gone (the summary, step 10, has its back link); `shell::every_template_with_the_bar_sets_its_tab`
  asserts no template names it. For a phone not set up, Back from Connect and the code page goes to the summary's
  Connect This Phone group (`/devices/{id}#access`).
- Tests: `tests::provisioning` (the add flow's table from the rendered forms, a connected phone, the name and Wi-Fi
  refusals, "Server setup needed", 404s), `admin_auth::the_sign_in_pages_use_the_new_components`,
  `shell::the_tab_pages_open_on_their_large_title`, repointed `enrollment.rs`, `devices_list.rs`.

## Connecting: a tap is never "nothing" after being away (2026-10-21, `static/reconnect.js`)

- **Why**: after the installed PWA was in the background a while, a tapped tab sometimes did nothing until the app
  was restarted - the network to the server (often over Tailscale) not up yet, and an installed PWA shows no loading
  indicator of its own (the user confirmed: after waiting it reconnected; "En subtil reconnecting hadde vært bra!").
  No service-worker fetch handler was added (the network is never faked).
- **The script** (ES5, deferred, from `partials/head.html` on every page, `?v=6` since the sign-in check below): a same-origin link tap (the tab bar,
  any link; not `download`, a `target` other than `_self`, a modified or handled click, a `#fragment` on this page, or
  `data-no-wait`) marks the link (`.is-pending`, `aria-busy`) and shows a thin bar at the top (`.net-progress`) at once.
  Not left after 3 s: a small calm chip "Connecting…" (`.net-chip`, `role=status`; no alarm colour, no modal, the page
  usable under it), a ping (`GET /ping`, 4 s timeout) then and every 5 s, on `online` and when the page is visible
  again; once the server answers (any status below 500 - a signed-out session gets the login's redirect, which counts)
  the same navigation starts again by itself (`location.assign`) - at once after a failed ping (the network came
  back), else once, 12 s into a navigation that still hasn't arrived, and then it is left to come (qa-srv-sweep-resume-code
  L2: a slow page - `/updates` waits for GitHub - is never restarted for good). "Try again" shows in the chip only
  after 20 s. A save started during a wait (a `submit`, a `data-autosave` change) ends it, so no restart cancels the
  save's POST (L3). A `pagehide` stops the wait's timers; coming back re-arms them (the 5 s pings, "Try again" when
  due; L5). The chip's words are set when it shows (empty while hidden), so a screen reader announces them (L9). Visible again after more than **1 minute** hidden (10 minutes until 2026-10-24; the user: a minute is not too short - under it you were only in another app and back) (or back from the back-forward cache after that long): a ping
  (the chip only if it takes over 3 s); answered, a reload in place (after more than **10 minutes** hidden, `HOME_AFTER`: Devices, `/`, instead - see below) through the same machinery, keeping the place -
  scroll-restore.js's form record in sessionStorage and `window.name` (`path`, `y` of `<main>` in the shell, and over
  a `#fragment` its element's screen top in `tops`), as auto-reload.js leaves it; never the top. No reload once a
  save started (a `submit`, a `data-autosave` change), while a `<details>` is open, when a field differs from what
  the page came with (typed text, a ticked box, a picked option, a chosen file; L4), or on a **POST's own answer**
  (qa-srv-sweep-resume-code M1) - the page then stays as it is.
  **Away over 10 minutes (2026-11-03; the user: "10 minutter er bra")**: the same return wait ends on `/` (the
  manifest's start_url, Devices) instead of the reload in place - `start("return", …, home)`, then `go()` of a
  `"load"` wait for `/`, so the chip, the pings and the restart are the same, and the same rules apply (a save on its
  way, an open `<details>`, a typed field, a POST's answer: nothing happens, as for the reload). Already on `/`: only
  the reload in place. Between 1 and 10 minutes: the reload in place, unchanged. The lock screen is left alone as
  before; a **due app lock** after more than 10 minutes goes to `/lock?next=/` (a ping answered 423 too), so after the
  passkey the parent lands on Devices; after less its `next` is the page they were on. **app-lock.js picks that `next`**
  (`static/app-lock.js?v=4`, QA M1): it is not deferred, so its own `visibilitychange`/`pageshow` handler runs before
  reconnect.js's and locks first (`going`), after which a later `lock(next)` is a no-op; so it keeps its own hidden-since
  (set by the veil) and calls `lock("/")` when that was over `HOME_AFTER`. The ten minutes are its one constant,
  `HOME_AFTER`, which reconnect.js reads as `window.handyLock.homeAfter` for its own return to `/` (without it nothing goes
  home; the head always loads app-lock.js). A hidden page whose timer fires and locks while hidden (a browser that runs
  hidden timers; a phone holds them) is already on `/lock?next=<its page>` by the return. Back from the back-forward cache
  counts the same (`pageshow` with `persisted`). `reconnect.js?v=10`; tests in `jstest/reconnect.test.js`. `admin_auth::mark_post_answer` (a layer on the admin
  router) puts `data-post-answer` on the `<body>` of every HTML answer to a POST outside the device API, whatever its
  status (the setup code, a refused form shown again, the sign-in steps' errors, a music import's preview - about 45
  handlers render on a POST; one rule instead of a flag in each); the script never reloads such a page. As a belt,
  every reload first makes the entry a GET of the address (`history.replaceState(history.state, "", url)`, Chromium
  checked by the QA: the reload is then a GET), so a reload can never resend a form; that URL drops a shown
  `notice=` (it would show again).
  `pageshow` from the back-forward cache clears everything (a first `pageshow` doesn't, so a tap during the load
  keeps its bar).
- **The CSS** (`style.css?v=39`): the bar and the chip are `position: absolute`, never fixed (design 43's rule, pinned
  by `the_app_shell_scrolls_main_not_the_document`); the bar's sweep is still under reduced motion.
- **`GET /ping`** (`main::ping`, behind `require_full_auth`): an empty 204, `Cache-Control: no-store`.
- The backup download link carries `download` (it is an attachment: the script must not wait for a page that won't
  come). A new link to a file needs `download` or `data-no-wait`.
- Tests: `jstest/reconnect.test.js` (which clicks count, the timeline with a fake clock and fetch, the restart rule,
  5xx = no answer, Try again at 20 s, `pageshow`, the return reload and its place record, the save/details rules, ES5),
  `shell::every_page_carries_the_connecting_script_and_its_ping` (also: the phone listener has no `/ping`),
  `shell::a_posts_own_page_is_marked_so_it_is_never_reloaded`; the QA's findings each have a jstest (M1, L2-L5, L9). A headless Chromium check against a never-answering
  page showed the bar at once, the chip at 3 s, "Try again" at 20 s and a restart about every 15 s while the server
  answered. Owed: the iPhone (and Android) PWA after being away.
- **`window.handyReconnect`** (2026-10-25, for pull to refresh below): `mayReload()` - no save on its way, no open
  `<details>`, nothing typed or picked (`edited`), the return reload's rules - and `reload()`, which is the reload in
  place a page script asks for: `go(start("load", null, null))`, the bar at once, the chip, the ping and the restart
  as for a slow navigation, the place record and the GET entry; `false` (nothing done) when `mayReload` says no. A
  POST's answer may be reloaded when asked (go()'s `replaceState` makes it a GET first). `reconnect.js?v=4`.
- **The sign-in page with a live session** (2026-10-25, branch `pwa-fixes-d`; the user: after Tailscale failed on
  the phone and came back, the reopened PWA asked for a sign-in, and killing and restarting it showed the app signed
  in - the session was valid; cause unknown). **Server**: `security::log_sign_in_redirects` (a layer on the admin
  router, outside the session layer) writes one line for every answer that redirects to `/login` - the reason
  (`security::SignInWhy`, put on the redirect as a response extension by `sign_in_redirect`: no session cookie, the
  cookie isn't a session id, no session row (never made, signed out, or expired and deleted - the store deletes
  expired rows hourly), the session expired, no account in the session (a sign-in not finished), the account removed
  or its one-time password run out, the epoch moved on, the store or the account unreadable, a re-auth's third wrong
  password (`admin_auth::signed_out_response`), Sign Out; a sign-in step's own redirect has none: "a sign-in step"),
  the method, the path (no query), whether a session cookie came and the browser's `Sec-Fetch-Site`/`Sec-Fetch-Mode`
  (the cookie is SameSite=Strict: a navigation the browser counts as cross-site doesn't send it). A warning with a
  session cookie, info without (and for Sign Out); never a cookie's value. "A session cookie" is this server's name
  alone (`AppState::session_cookie`, `security::session_cookie_name`: `__Host-handy`, or `handy` with
  `INSECURE_COOKIES`; qa-pwa-fixes-d N-1). The admin gate (`gate::admin_gate`) never redirects - a refused client gets
  an empty 403 -, so a refusal of a request with a session cookie is a warning line of its own there (the reason, the
  client, the path), at most one a minute per client: only when `EventThrottle::record` opened that client's minute
  of `admin_refused` (it returns whether it did; Q-4 - a refused client makes up the header). `load_active_admin` returns why (`Result<AdminUser,
  SignInWhy>`); `why_signed_out` tells the cookie cases apart (the session's id before it was read, then
  `tower_sessions` asked whether the row is there but expired). **`GET /api/session`** (`security::session_check`,
  a public route that answers itself; the admin gate applies, the phone listener has no such route): an empty 204
  while the session is a signed-in, onboarded admin's (what `require_full_auth` lets through), else 401, both
  `no-store`; it writes nothing (the session isn't flushed or saved: no `Set-Cookie` but the session layer's own
  removal of a dead cookie, the row's `expiry_date` unchanged - Q-3) and logs each answer with its reason. **PWA**
  (`reconnect.js?v=6`): every navigation of the script (a tap's, a restart's, the reload in place) remembers its
  address (`sessionStorage` `handy-wanted`, `{href, t}`); on `/login` (not a POST's answer) the script asks
  `/api/session` at once (4 s timeout; again when the page is shown after being hidden and when it comes back from
  the back-forward cache, one ask at a time - qa-pwa-fixes-d Q-7) and, 204 and no sign-in already submitted,
  `location.replace`s the page `loginReturn` picks - the page wanted in the last 2 minutes (the freshest of
  sessionStorage's record and the place record of a reload in place, which keeps the query in `href`; scroll-restore.js
  clears window.name before this deferred script runs, so it leaves the record it took as `window.handyPlaceRecord`,
  `scroll-restore.js?v=6`; Q-6), the referrer within the app, else `/` (a signed-in session never needs the sign-in
  page). **Never off the origin** (Q-1, High: `/login?next=/.//evil` went to `//evil`): nothing in the sign-in page's
  own address is read (the server never sets a `next`); `appPath` refuses a raw value starting with `//` or a
  backslash, resolves against the page's origin and compares `origin`, and refuses a resolved path starting with `//`
  or `/\` (`/.//evil`, `/%2e//evil`, `/a/..//evil`); never `/login`, `/logout`, `/auth/…` or `/api/…`. Within 30 s
  of going back (`handy-login-bounce`, in sessionStorage and in window.name as `handy-login-bounce:<ms>` - a prefix
  scroll-restore.js leaves alone, for an installed app that lost sessionStorage; Q-5) the page stays, so it can
  never loop. A ping redirected to the login (a
  fetch's `redirected` to `/login`) asks `/api/session` before it counts: its 204 or 401 is an answer (the navigation
  starts again), no answer is none. The sign-in page may show for the moment the check takes. Tests:
  `admin_auth::a_redirect_to_the_sign_in_page_says_why`, `the_session_cookie_is_found_by_its_name_alone`,
  `the_session_check_answers_204_or_401_and_changes_nothing`, `the_sign_in_log_lines_hold_no_cookie_value_and_no_query`
  (the capture subscriber: no cookie value, no query, the gate once a minute and never without the cookie; Q-2);
  `jstest/reconnect.test.js` (`loginReturn`'s table with the off-origin rows, `appPath`, the way back from the place
  record (with its query) and from a tap's, the freshest record, the loop guard in window.name, a resumed page, the
  401 / no answer / refused password / submit / loop cases, the redirected ping), `jstest/scroll-restore.test.js`
  (`handyPlaceRecord`, the bounce mark left alone). Owed: the next time it happens, the time and whether the address bar showed
  `/login…`, and the server log's line.

- **The app lock** (design 74 step 3, `reconnect.js?v=9`, `static/app-lock.js?v=1`): before the return reload, a
  restart, `handyReconnect.reload()` (pull to refresh) and auto-reload.js's/install-now.js's reloads, the scripts ask
  `window.handyLock.due()` and go to `/lock?next=<this page>` instead; a ping, an Install Now poll or the sign-in page's
  `/api/session` answered 423 (the server's lock) goes there too. On `/lock` itself reconnect.js does nothing on a
  return (no ping, no reload: the ping's 423 would replace the page under the automatic passkey prompt, qa-lock-auto
  M2), and its `toLock` from a `/lock` address keeps that address's own `next` (never `/lock?next=/lock?…`, which
  `lock::local_next` refuses). `docs/notes/server/connectivity.md`, "The app lock".

## Tab-switch timing (Q28 step 1, 0.54.0, `static/nav-timing.js`)

- **Off until switched on in a browser**: any page with `?timing=1` (remembered in localStorage `handy-timing`;
  `?timing=0` clears it), or **Settings › Software Updates › Diagnostics › Page timing** (`/updates#timing`, a plain link to
  `?timing=1` / `?timing=0` that the script shows as On/Off - the installed iPhone app has no address bar and its
  own storage; code QA M2). Then every load prints one line to the console (`[timing] <path> ...`) and shows it at the
  top of the page (`.timing-line`, no taps): `tap` (the tapped link's time, sessionStorage `handy-timing-tap`, to this
  page's load event) and `wait` (tap to the navigation's start), `dns`, `connect` (0 = a kept connection), `ttfb`,
  `download`, `dom`, `load`, the navigation type and `assets N/M` (network-fetched of all; many = the cache was gone).
  Navigation Timing level 2, level 1 on iOS 14; a back-forward cache restore says so in the same line. Sends nothing anywhere. Prefetch
  (step 2) waits for the user's iPhone numbers. Tests: `jstest/nav-timing.test.js`; the head loads it
  (`shell::every_page_carries_the_connecting_script_and_its_ping`).

## Pull down to refresh (2026-10-25, `static/pull-refresh.js`)

The user (2026-10-25): "update gestures for devices taben? Om man drar ned så refresher den ... Kunne også vært når
man er inne på en device". Branch `pwa-fixes-b`.

- **Where**: the Devices list (`devices_list.html`) and a phone's summary (`device_detail.html`) only, each loading
  `pull-refresh.js?v=1` (deferred, after `reconnect.js`); never a page with a Leaflet map (Locate, the history, a zone),
  never another tab yet ("other tabs later if it feels right"). `shell::pull_down_to_refresh_is_on_devices_and_the_summary_only`
  pins the two templates.
- **The gesture** (our own touch tracking, document listeners): a one-finger `touchstart` (passive) with main - the
  app shell's scroller - at `scrollTop` 0 and `mayReload` true starts tracking; a `touchmove` (`passive: false`) up
  first, or more sideways than down past 10 px, ends it as the browser's scroll or swipe; past 10 px down it is a
  pull, and from then every move is `preventDefault`ed and moves the arrow (half the finger past the first 10 px, at
  most 40 px, fading in over its first 20). Past 70 px of finger travel (`THRESHOLD`) the arrow turns over
  (`is-armed`); a release there (asked `mayReload` again) shows the spinner (`is-refreshing`) and calls
  `handyReconnect.reload()` - its `false` puts it away -, without reconnect.js a plain `location.reload()`. A release
  short of 70 px, a second finger, `touchcancel` or a back-forward-cache `pageshow` put it away. A tap, a scroll and a
  sideways swipe are never touched. `.container`'s `overscroll-behavior-y: contain` (on every page already) keeps the
  browser's own pull and chaining out; iOS' rubber band at main's top stays.
- **Refused** (nothing shown, nothing reloaded): a save on its way (a `submit`, a `data-autosave` change), an open
  `<details>`, a field with typed text or a changed choice - `handyReconnect.mayReload`; the script's own belt without
  reconnect.js: the save and `<details>` rules. No PWA save can jump to the top: the pull only reloads, with the place
  record (the page is at its top anyway).
- **The look** (`style.css?v=53`, "Pull down to refresh"): a 36 px round of the card's `--surface` with the peach
  `--tint` arrow and the Connecting chip's soft shadow, `position: absolute` in the body at the safe-area top + 6 px
  (design 43: never fixed), `pointer-events: none`, `aria-hidden`; the spinner a 2 px ring; under reduced motion no
  spin and no transitions. Both themes from the tokens.
- Tests: `jstest/pull-refresh.test.js` (touch timelines with a fake document: the pull and its arrow states, short of
  70 px and back above it, not at the top, up first, sideways, two fingers, a cancel, refused at the start and at the
  release, a save with and without reconnect.js, a refused reload, the bfcache, the listeners' passive options, the
  shift, ES5), `jstest/reconnect.test.js` (`handyReconnect`), the shell test above. Owed: the iPhone PWA and Android
  Chrome by hand (iOS' rubber band under the pull, the arrow under the Dynamic Island).

## The vibb identity, direction D (2026-10-22, `docs/testing/2026-10-22-pwa-identity-pass.md`)

- **The approved mockup** (artboard "D · B + flytende fanelinje", https://claude.ai/artifact/PKc1u9Qo3wdxKUk1Gkh6iX):
  the iOS grey ground and white cards stay (cards 16 px radius), the accent is vibb's dark peach `#A64B25` (hover
  `#7E3517`; links, + and the bell, filled buttons in both themes - white on it 5.8:1; *the filled buttons are `--tint-fill` since 2026-10-25, below*), headings in Nunito, the tab bar
  floats. Tokens only, in `style.css`'s `:root` (`--tint`, `--strong`, `--bar`, `--bar-shadow`, `--tab-on`,
  `--tab-off`, `--tab-pill`, `--font-display`, `--brand-word` (`--brand-tile` until the mark became the app icon, below), `--avatar-bg`, `--avatar-text`).
- **Dark mode is vibb's night**: ground `#0C0C14`, cards `#1B1B28`, text cream `#F6E7E0` / `#A89C96`, accent peach
  `#F0A884`, the bar a night card with a `#2A2A3A` pill and the peach label. The status colours kept their values
  (all 4.5:1 or more on the new grounds; the pass checks every text node).
- **The tab bar** (`partials/tab_bar.html` unchanged then, CSS only; the bottom fade's `<div>` since, below): a rounded card (24 px; off-white with a hairline since the night band's round, below) 12 px in from the sides (the notch's inset in landscape), 14 px + the
  home indicator's inset above the bottom, at most 560 px wide on a desktop; the chosen tab a `#FBE4DC` pill
  (`--tab-pill`), also the tapped tab while `reconnect.js` waits (`.tab.is-pending`). Since 2026-10-22 it floats
  over `main` (next bullet).
- **Devices' brand row**: the Pust mark (the app icon itself since 2026-10-22, below; first an inline SVG on a night tile) and the "vibb" wordmark at the left
  of the page's `.title-bar` (`.vibb-brand`, the home page's alone - `shell::the_tab_pages_open_on_their_large_title`),
  + and the bell at the right; the large title is Nunito 800, 30 px on every tab page. *Design 84: the mark is on
  every tab's band with the page's name beside it (24 px), and the wordmark is gone.*
- **Avatars**: every set-up phone's initial is `#0C0C14` on peach `#F0A884` (Nunito 900, 46 px), a phone not set up
  stays grey (`devices::avatar_class_of(enrolled)`: `""` or `avatar-grey`); `AVATAR_COLOURS` now colour only the
  Apps tab's letter tiles. *Design 85 step 1: each phone has its own colour, peach the first (below, "Every phone's
  own colour").*
- **Nunito is served from here** (no font service; `static/fonts/nunito-latin-wght.woff2`, preloaded by `head.html`,
  `font-display: swap`): google/fonts `ofl/nunito/Nunito[wght].ttf` at commit `8b0a1d0f`, cut with fonttools to
  `wght=700:900` (`fonttools varLib.instancer --update-name-table`, so it names itself Nunito Bold, not ExtraLight) and Latin + Latin Extended-A (`pyftsubset --flavor=woff2`, about 31 KB);
  SIL OFL 1.1 in `static/fonts/OFL.txt` (no Reserved Font Name), a line in `NOTICE.md`. Body text stays the system
  font.
- **Icons**: `icons/icon-192.png`, `icon-512.png` and `icon-180.png` (apple-touch-icon) are the vibb app's own
  (palchrb/vibb, `pi/web/`, branch `pipewire`); `icon-maskable-512.png` is its 512 scaled to 80 % on night, so the
  outer ring (195 px) is inside the 40 % safe zone (205 px). `theme-color` and the manifest's `theme_color` are
  night `#0C0C14` on every page in both schemes since the status-bar night (below; `background_color`, the splash,
  stayed `#F2F2F7` until the start-up splash, 2026-10-24, made it night too). The shell test checks every icon's size, that the font is local and that the head
  preloads exactly the `@font-face` file. `deploy/generate-icons.ps1` (the old blue phone icons) is deleted: the icons
  come from the vibb repo.
- **The fix round** (`docs/review/qa-pwa-identity-d-code.md`): a checked plain checkbox in dark has a night tick on
  the peach (9.9:1); the Locate map's trail is `--map-trail` (`#A64B25` in both schemes, read by `locate.js`, `?v=2`;
  the OSM tiles are light in both), no longer the old blue; contact initials on Calls & Contacts stay peach, like the
  phones'.
- **The see-through bar and the 44 px title bar** (2026-10-22, `docs/testing/2026-10-22-pwa-identity-pass.md`,
  "Polish 2"): the tab bar is `position: absolute` in `body` (`position: relative`, exactly the screen, never
  scrolling - the same place the flex child had, nothing against the viewport, so design 43's stale-viewport drift
  can't come back); `main` runs to the screen's bottom edge, the content scrolls behind the bar and shows in the gaps
  (12 px sides, 14 px + inset below), and `.tab-bar ~ .container` has `padding-bottom` and `scroll-padding-bottom`
  of `--tab-bar-room` (the bar's `--tab-bar-h` = `55px + 0.9rem`, its 14 px, 16 px clear - 24 px, the fade's start, since the bottom fade below) + the inset, so the last
  row and a focused field stop above it. Where `backdrop-filter` exists the bar is `--bar-glass` (white / night card
  at 90 %) with `saturate(180%) blur(20px)`, else the solid `--bar`; the gaps are never blurred. `z-index: 10`; the
  Locate map stays under it (`.map-box` is its own stacking context). Devices' `.vibb-brand` is exactly 44 px high
  with no vertical margin (it was 54 px, pushing +, the bell and the title 10 px lower than on the other tabs), so
  every tab page has the bar at y 8-52 and its `<h1>` at 56 (the shell test pins both rules). *Gone with design 84:
  the `<h1>` is on the band.*
- **Devices' night band** (artboard E, 2026-10-22, `docs/testing/2026-10-22-pwa-identity-pass.md`, "Night band";
  *since design 84 every page's, as `.band` - "The night band on every page" - with this `border-image` outset upward only;
  since its fix round no outset at all: in `main` iOS drew nothing of it, and `main`'s own ground shows in the bounce*):
  the home page alone ("it should feel more like a front page") has its title bar on vibb's night `#0C0C14`, from the
  screen's very top - main's 8 px and the status bar's inset - to 6 px under the 44 px bar (flat right under it until
  2026-11-10; the user on the iOS lab: the band's edge was too close under the mark, with much air down to the title),
  across main's whole width; every other tab keeps the grey top. `.title-bar-night` paints it with `border-image`'s outset alone (`fill 0 / 0
  / 100vh 100vw 6px`): ink overflow, so it takes no room and adds no scrollable overflow (clipped at main's edges), and
  the bar (y 8-52 plus the inset), + and the bell, and the `<h1>` (56) stay where Apps has them; the shell test pins
  the rule to exactly that one declaration. The band is main's content like the bar, so it scrolls away with it (design
  43 keeps the title bar in the scroller); the 100vh above the top is what an iOS pull-down bounce shows. On it, in both
  schemes (tokens, the same in dark): the mark (first on a `--brand-tile` `#1B1B28` tile; since 2026-10-22 the app icon
  on the band itself, below), the wordmark `--brand-word` white (19.5:1), + and the bell `--night-action` peach (9.9:1),
  hover `#F6C4AA` (12.4:1), the focus ring peach. In dark the band is the ground's own night, so it simply joins it. On
  a desktop the band runs the full window width (an app's top bar, its content in the 720 px column), not the column
  (a block that read as a card without corners). *`theme-color` was night on Devices alone (`night_top`) and the iPhone
  app's `status-bar-style` `default` until the status-bar night (below), which made both the same on every page.*
- **The tab bar as its own layer, the content behind it** (2026-10-22, the user's iPhone: a white bar over a white
  group ran into it; "like Nextcloud"): the bar is never the card's white nor the ground's grey, and what scrolls under
  it reads as behind - dimmed and blurred through the bar, sharp and undimmed in the gaps around it (the gaps show that
  it scrolls behind; no fade on main's bottom edge, which would dim the gaps too - *until the user asked for that fade,
  the bottom fade below*). Where `backdrop-filter` exists
  `--bar-glass` is a warm grey `rgba(238, 236, 234, 0.72)` (dark the night card `rgba(27, 27, 40, 0.72)`) with
  `saturate(180%) blur(20px)`; without it the solid `--bar` `#F4F2F0` (dark `#1B1B28`). A hairline inside the edge (an
  inset shadow, so `--tab-bar-h` holds; `rgba(12, 12, 20, 0.08)`, dark `rgba(246, 231, 224, 0.08)`) and a soft shadow
  `0 4px 18px rgba(12, 12, 20, 0.14)` (dark `rgba(0, 0, 0, 0.5)`). The lower opacity let text behind the bar pull the
  inactive labels under AA (3.98:1 measured), so `--tab-off` moved to `#5A5A5F` (dark `#B4A8A2`): 5.16:1 and 4.77:1 at
  worst against the rendered backdrop.
- **Night behind the status bar on every page** (2026-10-22, the user's iPhone: the status bar stayed grey above
  Devices' band - iOS paints it itself in `default` mode and ignored `theme-color`; `docs/testing/2026-10-22-pwa-identity-pass.md`,
  "Night behind the status bar"): `head.html`'s `apple-mobile-web-app-status-bar-style` is `black-translucent`, so the
  installed iPhone app's page runs under the status bar (with `viewport-fit=cover`) and iOS draws its text white, and
  `body::before` paints exactly `env(safe-area-inset-top)` vibb's night `#0C0C14` on every page, in both schemes:
  `position: absolute` in the body (the shell's body is the screen and never scrolls, so the strip stays and `main`
  scrolls under it - no fixed box, design 43), `z-index: 20` (over the editor bar's 5, under the Connecting bar and
  chip, which start below it), `pointer-events: none`. No inset (a desktop, Android's Chrome, Safari's browser tab) -
  0 px, nothing changes. Nothing moved: `main`'s padding still takes the inset, so the title bar (8-52 + inset), the
  `<h1>` (56 + inset) and the editor bar's sticky top (right under the strip) are where they were. Devices' band runs
  on through the strip without a seam (both exactly the night); scrolled, it goes under the strip. The auth pages
  (`.auth-page`, their own padding takes the inset) scroll the document, so there the strip is the document's top and
  scrolls with it (only with the keyboard up or a tall card). `theme-color` is simplified to one night `#0C0C14` on
  every page (Android's status bar and Safari's top bar night too, the same decision), the manifest's `theme_color`
  with it; Devices' `night_top` is gone. The shell test pins the strip's rule, `black-translucent` and the one
  `theme-color` on every tab page. The real iPhone check (white text on night on every tab, the bounce) is the user's.
- **An opaque black status bar since iOS 27** (2026-11-09, the user on iOS 27.2, with a screenshot: "pwa en rar
  overgangsfarge på toppen" - a grey-to-dark gradient over the status bar above the night header). *Superseded on
  2026-11-10 by "The status bar by iOS version" (below): iOS 27 painted no black bar, and the 100vh shell was one bar too
  tall.* iOS 27 draws its own
  blur over a `black-translucent` home-screen app's status bar (developer reports; the fix they use is an opaque bar).
  `head.html`'s `apple-mobile-web-app-status-bar-style` is now `black`: iOS paints an opaque black bar with white text
  and the page starts below it, so the top inset is 0 there. Black `#000` beside the night `#0C0C14` is the nearest
  opaque bar iOS offers (`default` is the grey one of 2026-10-22). Kept, harmless: `viewport-fit=cover` (the home
  indicator and the landscape insets), `body::before` (0 px with no inset; it still paints night for an app added
  with the old bar), every `env(safe-area-inset-top)` padding (0 on iOS now, 0 on Android and desktop as before), and
  the standalone 100vh shell (the screen below the bar now). The start-up PNGs are unchanged (flat night with the mark
  on the screen's centre; nothing in them assumed a translucent bar; `--selftest` passes); `/lock`'s mark is centred
  on the page, which now starts below the bar, so it may sit half the bar's height lower than the splash's at the
  handover. `style.css?v=85` (comments only). The shell test pins `black` and that no page carries `black-translucent`.
  **iOS stores the style when the app is added to the Home Screen**: an app added before may keep the translucent bar
  until it is relaunched (swiped away and opened) or, if that is not enough, removed and added again. Not checked on
  an iPhone here: the user checks the top on every tab in both schemes, the pull-down bounce, the tab bar's place at
  the bottom, and the splash-to-lock handover.
- **The status bar by iOS version** (2026-11-10, the user on iOS 27.2 with server 0.72.0, the app removed and added
  again, with a screenshot: with 2026-11-09's `black` iOS 27 painted no black bar - the status area was the page's light
  ground with dark text, a soft fade lay over the top of the night header, and the tab bar was cut off at the bottom
  by about the status bar's height; on iOS 26 with `black-translucent` everything had been good). What was wrong with
  2026-11-09's fix: both its assumptions - an opaque black bar, and the 100vh shell being the screen below it. Three
  causes, read from WebKit's source and Bugzilla (not measured on a device by us):
  1. The top fade is UIKit's scroll pocket on the web view. WebKit hides it (`WKWebView _updateHiddenScrollPocketEdges`)
     and gives the status area a colour only from a "fixed container edge": `LocalFrameView::fixedContainerEdges`
     hit-tests the middle of the top edge 4 px inside the viewport (ignoring `pointer-events`), walks up from the hit
     node to the first renderer that is `position: fixed` or `sticky`, has a layer and is at least 90 % of the viewport's
     width, and takes its declared `background-color` when its border box is over 10 px both ways and the colour is not
     nearly transparent (`visibility: hidden` and opacity under 0.1 are skipped). An absolute box or a pseudo-element
     of a box that isn't fixed never counts - so `body::before` did nothing, and the status area took the page's ground.
  2. Without `black-translucent` the page starts below the status bar while `100vh` is still the whole screen (WebKit
     316008, open): `body { height: 100vh }` in standalone was one status bar too tall, so the tab bar fell off the
     bottom.
  3. Apple calls `black-translucent` deprecated and says to remove the key - the status bar takes the page's colour
     (WebKit 317153). Also cited by the research: WebKit 301994, and 301108 (iOS 26 reports the layout viewport and
     `100dvh` short by the top inset under the translucent bar - why the shell was `100vh`, 2026-10-23).

  The fix: (A) `<div class="top-strip" aria-hidden="true">` is the first child of every page's `<body>` (all 54
  templates; no shared partial follows `<body>`), replacing `body::before`: `position: fixed`, top 0, the whole width,
  `height: var(--top-strip)`, vibb's night, `pointer-events: none`, `z-index: 4000` (above the photo crop's veil, 3000,
  the highest; no page has a `<dialog>`, and nothing else is fixed). The PWA's one fixed box, the deliberate exception
  to design 43's "nothing against the viewport" (only a fixed or sticky box counts for WebKit; pinned to the top edge,
  a stale viewport height can't move it). `--top-strip` is `env(safe-area-inset-top, 0px)`, and in the installed app
  on iOS alone (`@media (display-mode: standalone)` with `@supports (-webkit-touch-callout: none)`) never under 12 px,
  so the strip exists and its colour counts when iOS reports a top inset of 0 under `default`; Android's installed app,
  desktops and Safari's tab keep the inset (0 - no strip, as before). Everything placed "below the status bar" counts
  from `--top-strip`: the auth pages' padding, the photo crop's padding, the Connecting bar and chip, the
  pull-to-refresh arrow, the timing line and the lock page's centring; main's `padding-top` (*since design 84 the
  band's, `--band-top`, with 6 px for the 8*) is
  `max(calc(8px + env(safe-area-inset-top, 0px)), var(--top-strip))` (below, "On the device"). The veil (`html.locked`/`html.veiled`)
  hides every child of the body but the strip (a hidden box is skipped by WebKit). The auth pages scroll the document:
  the fixed strip stays at the top and the card scrolls under it. (B) The installed app's shell is
  `height: min(100vh, calc(100% + env(safe-area-inset-top, 0px)))` (`html` is `height: 100%`, the layout viewport):
  under the translucent bar 100% is short by the inset (301108), so this is 100vh, the screen - the state the user saw
  good on iOS 26; under `default` the inset is 0 and it is the layout viewport, right below the bar. The inset, never
  `--top-strip` (the 12 px are paint, not page). The auth pages' standalone `min-height` is the same expression; the
  lock page's mark is centred on half of it, with `100dvh` for the layout viewport since a margin's % counts from the
  width (`50vh` first, for iOS before 15.4). (C) `head.html`'s `apple-mobile-web-app-status-bar-style` is `default`,
  Apple's direction for iOS 27 and later; Safari 26 and older get `black-translucent`, the one combination seen good
  there. iOS reads the style from the page Safari had when the app was added, and Safari's User-Agent tells its version
  only by `Version/NN` (the OS token is frozen at `18_6` since iOS 26), so `status_bar::status_bar_style` decides from
  the User-Agent - `iPhone`, `iPad` or `Macintosh` with `Version/NN`, NN <= 26, and no product token ending in `iOS/`
  (`CriOS/`, `FxiOS/`, `EdgiOS/`, `OPiOS/`: another iOS browser, some with a `Version/` of their own, is never read as
  Safari) -> `black-translucent`; anything else (none, no `Version/` as the installed app's own requests, 27 and
  later, an unreadable number) -> `default` - and the middleware `status_bar::old_safari_bar` (on the admin router, beside `mark_post_answer`) swaps
  exactly that one meta line in a `text/html` answer to an old Safari, read whole, `Content-Length` dropped; the common
  path is never rewritten, nothing that isn't HTML (the event stream, downloads, static files) is touched. Every HTML
  answer carries `Vary: User-Agent`: the pages are `private, no-cache` (or `no-store`) and carry no validator, so a
  browser may keep one for Back; the service worker caches no page. `style.css?v=90`. The shell test pins the strip as
  every page's first element, its rule, the iOS 12 px, the standalone height (the auth pages' `min-height` and the lock
  page's margin too), no `body::before`, the veil's exemption, `default` on every tab page, and through the router
  `black-translucent` for Safari 26 (a POST's answer too, with `data-post-answer`), `default` for Safari 27 and no
  User-Agent, the stylesheet byte for byte and without `Vary: User-Agent`, and the phones' event stream answering its
  first chunk unbuffered; `status_bar::tests` runs the User-Agent table and
  `a_rewritten_page_never_keeps_its_old_length`.
  **On the device** (the user's iPhone, iOS 27.2, the installed app with `default` and the 12 px strip, 2026-11-10):
  screen 393 x 852; `innerHeight` = `clientHeight` = the visual viewport = 793; `100vh` and `100lvh` 852, `100dvh` and
  `100svh` 793; `safe-area-inset-top` 0, bottom 34; the body 0..793, the strip 0..12, the tab bar 676..745. The status
  area is night with a white clock, no fade, the tab bar in place. So under `default` on iOS 27 `100dvh` is the layout
  viewport and the page starts below the bar. From it: main's top padding is
  `max(calc(8px + env(safe-area-inset-top, 0px)), var(--top-strip))` - 12 px there, 4 pt more than iOS 26's look
  (`8px + --top-strip` gave 20, 12 more, and the user picked the tighter one on the lab), and exactly as before the
  strip on iOS 26's installed app and in a browser tab. The sticky editor bar keeps `top: -8px` and its own 8 px
  padding: stuck, its row sits where every page's title bar is in all three (on iOS 27 the strip covers the bar's
  padding alone). `.page-title`'s top margin is 12 px (was 4; the user: more air between the band and the title, the
  same height on every page), and the title as main's first child 56 (44 + 12). *Design 84 replaced the bar, the
  sticky editor bar and the title under it with the band; the strip and `--top-strip` are as written here.* Chromium at 393 px (measured): the
  title's top at 64 in a tab, 123 with a 59 px inset, 68 on iOS 27's numbers, the same on every page shape.
  **Not known yet**: iOS 26 with `default` (why it keeps `black-translucent`); whether iOS reads the bar style only at
  Add to Home Screen; the 12 px band in iPhone landscape (the strip is 12 px there too, on iOS 26 as well since the
  top inset is 0 in landscape); the grey ground during a momentum bounce above the night band; an app added on iOS 26
  and then updated to iOS 27 (it may keep `black-translucent`). **The knobs if the device says otherwise**: the
  strip's colour is `.top-strip`'s `background` (one line in `style.css`); the bar style rule is `status_bar_style`'s
  `major <= 26` (one line in `src/status_bar.rs`); the strip's minimum is the 12 px in the iOS-standalone `:root`.
- **The brand mark is the app icon** (2026-10-22, the user: the lifted tile read as a lighter square on the band, and
  the hand-drawn rings were "not the right icon"): Devices' `.vibb-mark` is `<img src="/static/icons/icon-192.png"
  width="34" height="34" alt="" decoding="async">` - the PWA's own icon, its night ground baked in, straight on the night
  band with no tile (`.vibb-mark` is only its box: `display: block`, 34 px); `--brand-tile` and the inline SVG are gone.
  *Since design 84 it is `.band-mark`, 36 px, on every tab's band.*
  The icon's ground is `#0C0C14` with faint dither (at most 12 off per channel), so it blends into the band; its outer
  ring runs nearly to the icon's edge, so the rings are about 33 px across (the SVG's were 23). The wordmark stays 10 px
  to its right. The shell test pins the `<img>` on Devices alone, the rule, and that neither the token nor the old
  SVG's `viewBox` comes back.
- **The bottom fade** (2026-10-22, the user's iPhone on Settings: "Sign Out" sat sharp in the gap under the floating
  bar and the bar's blur barely read; "like Nextcloud"): `partials/tab_bar.html` starts with `<div class="tab-fade"
  aria-hidden="true">`, before the `<nav>` (so `.tab-bar ~ .container` still holds, and a page without the bar has no
  fade). `.tab-fade` is absolute in the body, the whole width, from 24 px (`--tab-fade-above`) above the bar's top to
  the screen's bottom edge (`--tab-bar-h` + 14 px + 24 px + the home indicator's inset), `linear-gradient(--bg-clear,
  --bg)` - the ground at 0 % (`rgba(242, 242, 247, 0)`, dark `rgba(12, 12, 20, 0)`; never `transparent`, which older
  browsers blend through black) to the ground at the edge; `z-index: 9`, between `main` and the bar (10), so the bar's
  glass blurs the faded content; `pointer-events: none`, so taps, the wheel, main's scrollbar and the map reach main
  through it. `--tab-bar-room` is the bar + 14 px + `--tab-fade-above`, so main's last row (and a focused field,
  `scroll-padding-bottom`) stops above the fade's start. Above that start the side gaps stay sharp. The shell test pins
  the rule, the room and that the fade comes first in the partial.

## Start-up splash (2026-10-24)

- **The user's pick** ("en forvokst utgave av ikonet ... uten tekst; bare en stor logo og natt bakgrunnen akkurat som
  ikon logoen vår"; the reference Nextcloud iOS's launch screen, and - the user's addendum - the launcher's own Home
  splash): one flat night ground `#0C0C14` and the Vibb mark centred, nothing else. The mark is the launcher's
  `splash_vibb_breathe.xml` at rest (the four rings `#F0A884` at stroke alpha 0.55 scaled 0.94, the cream core
  `#FBE4DC` scaled 0.9; Vibb mark © palchrb, `NOTICE.md`), drawn from its vector geometry, so every PNG is crisp at
  its own size - no upscaled bitmap. The night is the launcher's `kid_ground` too (the same `#0C0C14`).
- **Its size is the launcher's share**: at rest the mark is 169 dp across (its outer edge 0.94 x 90 dp from the centre,
  `vibb-icon.py`'s 90 dp reach) in the 288 dp splash area, which a phone shows at that size (`activity_home_night.xml`,
  `wrap_content`; the SplashScreen API's 288 dp icon) - 43 % of a 393 dp wide phone. So the PWA's mark is 43 % of the
  shorter screen side (`mark_share` 0.43): 275 px on the iPhone SE (1st, 640 px wide), 568 px on the 16/17 Pro Max
  (1320 px). Being drawn from the vector, no size is capped.
- **iOS** (`partials/head.html`): one `<link rel="apple-touch-startup-image">` per iPhone portrait size, `media` =
  `(device-width) and (device-height) and (-webkit-device-pixel-ratio) and (orientation: portrait)` in CSS px - iOS
  shows an image only when the query matches the phone exactly, else a plain white launch. 13 sizes cover every iPhone
  from the SE (1st) to the 17 Pro Max (`server/testdata/pwa_startup_screens.json` names each); landscape is left out
  (a phone PWA opens upright; a landscape launch shows iOS's own blank). The PNGs are `static/startup/startup-<w>x<h>.png`
  (64-colour palette PNGs, about 240 KB for all 13), linked with `?v=` (the JSON's `version`) and served `public,
  max-age=31536000, immutable` (`admin_auth::admin_headers`, `/static/startup/` and a 2xx only - the icons have no long
  caching; the test pins that style.css, the manifest, an icon and a 404 under `startup/` don't get it).
  The real iPhone check (add to the home screen, cold start) is the user's.
- **The lock screen takes over from it** (2026-11-03, the user: "en fin overgang"): `/lock`'s mark
  (`partials/vibb_breath.html`) sits at the splash's size and the screen's centre (style.css's `--vibb-mark-size`,
  `min(51.30vmin, 240px)` - written by this script from `mark_share` between the `Vibb mark's size` markers; the
  middle of the body, which is the screen in the installed app: 100vh, black-translucent, `viewport-fit=cover` - since
  2026-11-09 the screen below the opaque black bar, see "An opaque black status bar since iOS 27"), and
  its first frame is the mark at rest: the breath's delays are positive (outer ring 0 s, then 0.28/0.56/0.84 s, the
  core 0.84 s - the pust SVG's stagger) with the 0 % keyframe held during them. The mark is in the flow (the space
  above it puts its centre on the screen's), so the title and the foot always come after it; below 540 px of height
  (a Safari tab on a small iPhone, a phone on its side - no splash there) it sits smaller at the top instead. The title, then the hint and Sign Out
  fade in (500 ms); under `prefers-reduced-motion` the mark stays at rest and the words show at once. A launch within
  the server's minute of slack paints the start page first, which app-lock.js hides at once (`html.locked`): that
  page is night too, with `static/vibb-rest.svg` (the mark at rest, from `vibb-icon.py`) at the same place;
  app-lock.js fetches it on every lock-on page, so it is there before the first lock.
  `shell::the_lock_screens_mark_is_the_splashs` checks the size and the rings in CI; the script's `--selftest` too.
- **Android**: the manifest's `background_color` is night `#0C0C14` (was `#F2F2F7`): Chrome draws the icon on it and
  always adds the app's name under it - nothing to do about the name. `theme_color` and the one `<meta
  name="theme-color">` stay the same night.
- **Regenerate** (a new iPhone size, a new mark): edit `server/testdata/pwa_startup_screens.json` (a new row; bump
  `version` when the PNGs change), run `python3 -I scripts/pwa-startup-images.py` (Python 3 + Pillow; it writes the
  PNGs, removes stale ones and rewrites head.html's links between the two `Start-up images` markers), check with
  `--selftest` (it draws every screen again in memory and compares it with the committed PNG pixel for pixel, and
  with the launcher in the tree compares the copied rings, core, scales, alpha, stroke and colours with its drawable),
  commit the PNGs, the JSON and head.html. A new mark means new ring data in the script (copied from the
  launcher's regenerated `splash_vibb_breathe.xml`; the script never reads the launcher's files). Test:
  `shell::every_startup_image_has_its_screens_size` (the links against the JSON in order, each PNG's exact pixels, the
  folder holds only those, the caching, under 300 KB, the one night in the manifest and the meta).

## The ⓘ cut (2026-10-24, PLAN "PWA ⓘ panels are too wordy")

- **A ⓘ only where the footer can't carry the one fact a parent needs** (a consequence, a number, "needs the phone
  online"), one or two short sentences in the page's plain words - no internals, no design numbers. A panel that
  repeated its row or footer is gone with its whole `<details class="info">` (no empty ⓘ); a fact worth keeping moved
  into the footer in a few words ("Add photos in Settings › Wallpapers", "a friend's too", "(up to 10 MB)"). Panels
  that hold data (a crash trace, a raw error, a key, a command, the phone's answer, a book list, Spotify's setup steps)
  stay as they were. A new ⓘ follows the same rule. Of the 83 `info-panel`s in `templates/`, 9 static ones went and
  the static rest were cut; the tests assert the new words. The code QA's fix round (`docs/review/qa-pwa-polish-a-code.md`
  Q-1..Q-5) put back what a parent acts on, in the fewest words: the alarms after a restart on Android's lock, the
  Parent PIN's two hours and lockout remedy, what Vibb One's lock doesn't protect, the Storytel password's truth until
  design 58 step 3 (a phone with an older music app keeps a copy) and the lost-phone remedy, and a clause each where a
  parent would otherwise be surprised (SMS off hides Messages, a phone with its own rules, the 24 h rule, the Install
  Now minute and Update fence, safe mode's restrictions, the filter's kept notifications, Locate needs location, Off
  turns location off, the Tailscale key by hand, what a backup leaves out, the lock's colour without "Also on the lock
  screen").

## Locate: zones (design 64 step 1, `docs/design/64-safe-zones.md` §3.5-§3.6; the rest in `zones.md`)

- **Safari (2026-10-24):** the zone page's map was grey on the iPhone: `fitBounds(circle.getBounds())` before any
  `setView` throws in WebKit (the circle isn't projected until the map has a view). The fit uses the pure
  `zoneBounds(lat, lng, r)` now (`zones.js?v=2`, `jstest/zones.test.js`). A Leaflet page gets a WebKit pass (Playwright's
  `webkit`, see the 2026-10-24 scratch run) before release.

- **`#zones` on Locate** (`locate::zones_view`, right after `#location`): a row per zone ("Home" over "150 m · arrive
  and leave" / "arrive" / "leave" / "no notifications", "Here now" while the phone is in it now - or "Last seen at Home
  14:02" while the newest fix is stale, `zones.md` "What the pages say of the phone" -, a chevron to its page;
  each row `id="zone-N"`), then **Add Zone** (`/devices/{id}/zones/new`), at 10 zones a note "10 zones is the most"
  instead. The footer: without zones "A zone tells you when the phone arrives or leaves, like Home or School."; with
  them by mode - Regularly "Arrivals and departures show after two locations on the same side. That is within about
  two intervals (here 1 hour)." (twice the interval), Only when asked "The zones are judged on the locations Update
  Location brings.", Off "Location is off for this phone, so the zones see nothing.". With zones: Only when asked adds
  the orange row "Zones need Regularly" / "With Only when asked the phone reports a location only when you tap Update
  Location." and a **Locate Regularly** button (`POST /devices/{id}/location-policy#zones`, `mode=interval`,
  `back=zones` - the interval kept; `update_location_policy` whitelists `back=zones` -> `#zones`, anything else
  `#location-settings` as before); Off adds "Zones can't see where the phone is." to the existing location-off row;
  "Keep location on" off (and the mode not Off) adds a row "Your child can turn Location off on the phone; zones then
  see nothing." to `/devices/{id}/advanced#restrictions`. **Step 2** (`zones.md`, "Safe zones, step 2"): with zones on a
  phone whose latest `zone_watch` holds fences, Regularly's footer is "The phone watches the zones itself and sends a
  location as soon as it notices a crossing. Arrivals and departures show after two locations on the same side." (no
  "two intervals"), followed by the watch's line (`locate::watch_line`, `data-zone-watch`: "Watching 2 zones on the
  phone." [+ "The newest zone is set up at its next sync." while it holds fewer fences than zones] [+ "It has no motion
  sensor, so it takes a location every 30 min."], or "Location is off on the phone, so it can't watch the zones." / "The
  phone can't use its location in the background yet, so it can't watch the zones." / "The phone hasn't set up its
  zones yet; they are judged at each location it reports."); Location Settings' "Every" row gets the sub-line "30 min
  while the phone has zones" (`data-interval-note`) while the served interval is above the parent's choice - the select
  still shows the choice (decision 3: never written); and the location group a row "3 made-up locations ignored, the
  last 14:02" (when the count last rose, `devices.mock_logged_at`, qa-design-64-step2-code Q-9) /
  "An app on the phone pretended it was somewhere else. Vibb One kept those out of the map and the zones."
  (`data-row="mock-ignored"`) while the phone's running count is above 0. The newest fix row's title (the exact time)
  gains the fix's provider and, for a mock fix, "test location" (migration 0093, `locate::fix_title`) - the title only.
  "Here now" counts from the later of the newest fix's time and its last still re-send (`resent_at`, Q-3).
- **The fix's badge**: the newest fix's sub-line "Within 40 m · At Home" ("At Home · School" for two), and the marker's
  popup "10 min ago · within 40 m · at Home" (`data-shown-text`, text as before), while **that fix** is surely inside
  the zone (`locate::fix_badge`, `zones::side`; code QA Q-1).
- **The map**: the box gets `data-zones` while the phone has any; `static/locate.js` (`?v=4` since the code QA fix round, `?v=5` since S-i18n slice 3c; both Locate pages) then
  fetches `GET /devices/{id}/zones.json` once (`no_store_path`: a child's home is never in a cached answer) and draws
  each as `L.circle` in `--map-zone` (#2F6F62, the same on OSM's light tiles in both schemes, beside `--map-trail`),
  fill 0.08, its name a tooltip built as a text node; a zone that isn't numbers isn't drawn. `zones.json` is asked
  once the first locations settled (code QA Q-7), and a span without points shows the zones (one zone: its centre at
  zoom 15; several: their centres fitted) instead of the world; the span pick doesn't touch the circles.
  `zones.json` is `[{id, name, latitude, longitude, radius_m}]`, a 404 for an unknown phone.
- **The zone's page** (`handlers::zones`, `device_zone.html`, tab Locate, `no_store_path` `["devices", _, "zones",
  ..]`): one form `#zone-form` with the time rule's sticky editor bar (`.nav-back` "Locate" to
  `/devices/locate?device={id}#zones`, **Add** / **Save** at the right, a refusal's "Not saved. …" in the bar), the
  large title (the zone's stored name, "New Zone"; *since design 84 all of it on the editor's band, `band::editor`,
  with the refusal in `.editor-flash` under it*), the map box (`data-zone-map`: centre, radius, the slider's range,
  the phone's newest fix), then Name, Latitude, Longitude (text, `inputmode="decimal"`, 6 decimals; a decimal comma
  reads as a point), Radius (`type=number`, 100-2000 step 10, " m"), the slider (`type=range`, no name, `hidden`
  until `zones.js` shows it), "Use Phone's Location" (`type=button`, only with a fix, `hidden` until the script), and
  Notifications: Notify When They Arrive / Notify When They Leave (switches, default on; the footer says each parent
  turns the pushes off under Notify Me About). A new zone starts at the phone's newest fix with 150 m; no fix: empty
  coordinates and "No location yet - type the coordinates or tap the map." Below, for a zone, "Delete Zone…" as a
  `<details class="confirm">`: "Delete Home? Its past arrivals stay in Notifications as long as the location history is
  kept." Without JS the form is the whole feature. Coordinates are rounded to 6 decimals when saved (about 0.1 m),
  so a form saved unchanged never "moves" the zone.
- **Where it returns** ("a save never jumps to the top"): Add -> `/devices/locate?device={id}&zone=added&zone_id=N#zone-N`
  and Save -> `…&zone=saved…#zone-N` (the row, with "Added." / "Saved." as a flash right under it), Delete ->
  `…&zone=deleted#zones` ("Zone deleted." at the group's top), Locate Regularly -> `#zones`. A refusal re-renders the
  page (400) - the form's action ends in `#zone-form`, so the answer opens there - with the field marked
  (`aria-invalid`, `autofocus`, the reason under it: "Give the zone a name.", "At most 40 characters.", "No line breaks
  or invisible characters.", "This phone has a zone with that name.", "A latitude between -90 and 90.", "A
  longitude between -180 and 180.", "Between 100 and 2000 m."), the 11th zone in the bar ("10 zones is the most for one
  phone. Delete one first."), nothing written. An unknown phone, an unknown zone or another phone's zone is a 404; a
  database error a 500 "Nothing was saved".
- **`static/zones.js`** (ES5, `?v=1`, every value from `data-` attributes, nothing as HTML): `L.map(…, {
  doubleClickZoom: false })`; a tap (`click`, which a drag never fires) places the centre; the centre's marker drags
  (`dragend` writes the fields); the slider's `input` writes the number field and redraws, the number field's `input`
  redraws what can be drawn (typing "1" on the way to "1500" draws 100 m, the field left as typed) and its `change`
  clamps it into the range; a typed coordinate redraws on `change`; "Use Phone's Location" moves the centre. Every
  change writes both coordinates (6 decimals, a longitude wrapped into -180..180), so Save posts what the map shows.
  Long-press isn't used (a tap is enough; iOS's long-press opens its own menu). `--map-zone` for the circle.
- **The phone's summary**: "Home since 15:42" on the header's second line, before the battery (`data-zone-now`,
  "Tidier status" below), only while the phone is in the zone now (no other side pending, the newest fix fresh).
- **Notify Me About** gains "Zones: arrived and left" from `Kind::switch()`, once (`Kind::switches()`); no template
  change.
- CSS under "Design 64" at the end of `style.css` (`?v=50`): `.zone-slider`, the two `[hidden]` rules; the token
  `--map-zone` in `:root`.
- Tests: `tests::zones` (the pages, the redirects with `assert_lands`, the refusals, the group by mode, the badge, the
  summary line, the switch once, "Here now" against a pending or stale fix), the two "every form lands" tests with a
  zone (code QA Q-3; a zone's delete leaves the page, `settings_pages::leaves_the_page`), `admin_auth::tests::no_store_paths`, `tests::locations` (`locate.js?v=5`),
  `jstest/zones.test.js`, `jstest/locate.test.js`.

## A phone's own picture (server round C, 2026-10-25, branch `pwa-fixes-c`, migration `0100_device_avatar.sql`)

- **The user** (2026-10-25: "Når kommer muligheten til å sette avatar?"): a phone can have its own picture instead of
  the letter tile. `devices.avatar_hash`, `photos::DEVICE_AVATARS` (its own store, lock and directory
  `data/device_avatars/`, `AppState.avatar_dir`), `handlers::device_avatar`.
- **Setting it**: on the phone's summary the large avatar is the picker - a hidden `<input type="file" name="picture"
  accept="image/*" data-autosave>` (focusable; its ring drawn round the avatar) behind a `<label>` around the avatar,
  "Add picture" under it (another label for the same input) or "Remove picture" (a form) once there is one. **No
  `capture` attribute**: with it Android and iOS open the camera straight away; without it a phone offers the photo
  library and the camera (the brief's intent). Chosen, the picture saves at once (`autosave.js` submits through the
  hooked `form.submit()`, so `scroll-restore.js` keeps the place); without JS a `<noscript>` "Save picture". Every form
  posts to `/devices/{id}/avatar` or `/avatar/remove` and comes back to `/devices/{id}#avatar` (`.avatar-edit`
  `id="avatar"` in the header): never the top.
- **The pipeline is the contact photos'** (`photos::process`, `Shape::Square`): decoded from the bytes (JPEG, PNG or
  WebP), turned upright from EXIF, cut to the centre square, at most 512 px (the brief said "e.g. 256": the shared
  pipeline's size was kept rather than a second one), re-encoded as JPEG - no EXIF, GPS or anything but pixels -
  and named by its SHA-256. 10 MB at most (the route's body limit is 10 MB + 64 KiB). A refused file changes nothing
  and comes back as `?avatar=<code>#avatar` with `photos::PhotoError`'s text under the picture (`role="alert"`, the
  input `aria-describedby` it): `empty`, `too_large`, `type`, `pixels`, `unreadable`, `failed` (a write error); any
  other code shows nothing (never echoed). A new picture or Remove prunes the old file; a deleted phone's goes too.
- **Where it shows**: over the letter on every phone avatar - the Devices list, the summary's header, Locate's phone
  list, Time Rules, the Apps tab and an app's page, the Music tab and an entry's page, the inbox's rows
  (`<img class="avatar-img">` absolutely inside `.avatar`, `object-fit: cover`, the circle; the status dot comes after
  it in the markup, so it stays on top, and the letter stays under it for a picture that doesn't load). Not on
  Locate's map (Leaflet's own marker). Never sent to a phone (the policy has no field for it).
- **Served without a session** at `GET /phone-avatars/{hash}` (`Content-Type: image/jpeg`, `Cache-Control: private,
  max-age=31536000, immutable`; content-addressed, so a new picture is a new URL), because it is also a push's icon
  (`notifications.md`, "The push icon"): the browser fetches a notification's icon for the service worker outside any
  page, where the SameSite=Strict session cookie isn't reliably sent, and an icon behind the session would come back
  as the login page. The path is the picture's own SHA-256 (256 bits: unguessable without the picture itself), only a
  hash some phone names right now is served (else 404, also for a file still on disk), there is no listing, and the
  admin gate (loopback/tailnet, `gate::admin_gate`) still applies - the same footing as `/sw.js` and the receipt. With
  `ADMIN_PUBLIC=on` (exposure b) the gate lets everyone through and **the hash is the only guard** (code QA Q-6):
  acceptable, since the URL travels only in the signed-in pages, the browser's cache and the end-to-end-encrypted push
  payload. Never on the phones' listener. The pages use the same URL, so the browser caches one copy.
- **Backups**: `backups::build_backup_zip` adds `device_avatars/<hash>.jpg`; after a restore `photos::recover_missing`
  takes a missing file back from the newest zip that has it, and forgets a hash no zip has (the letter tile again).
- Tests: `tests::avatars` (upload -> served square JPEG without EXIF and shown with the dot on every phone row, not in
  the policy; replace and Remove prune and go back to the letter, a deleted phone's file goes; a non-picture, an empty
  file and a broken PNG refused under the avatar with nothing changed; the backup and the restore; the push's icon;
  the admin gate; since the code QA: `the_picture_route_serves_only_a_named_hash` - a file on disk no phone names, an
  uppercase, 63- or 65-character hash, `.jpg`, an encoded `../` and a row naming `../secret` all 404 -,
  `the_upload_takes_a_photo_over_two_megabytes` - the route's 10 MB limit, not axum's 2 MB - and the inbox and an
  app's page in the rows' loop), `listeners::the_device_router_serves_nothing_but_the_device_api` (`/phone-avatars/`
  and `/contact-photos/` 404 on the phones' port), `summary::the_quick_actions_follow_the_phone` and `every_form_on_the_summary_returns_to_its_group`
  (the header's picker is the summary's one autosave and its first form), `jstest/sw.test.js` (the icon).

## Field focus and the filled button (the user's picks A and 3, 2026-10-25, branch `pwa-fixes-c`)

- **The mockup** (https://claude.ai/artifact/CcNPzaNhQsCJitG3RJsmxb, the user: "A og 3 høres bra ut"). `style.css?v=54` (`?v=55` since the code QA's fix round).
- **(A) A focused text field or select** (`input` text/tel/password/time/email/url/number/search, `select`,
  `textarea`; not the borderless ones that are a row's value - `.row-input`, `select.pick`): its own border takes
  `--tint` at 1.5 px (the 1 px border plus `box-shadow: inset 0 0 0 0.5px`, so nothing moves; the day's borderless
  `.row-time`/`.row-num` get the whole 1.5 px as an inset shadow) and its fill `--field-focus` - white; in dark the
  field keeps its night fill (`#0C0C14`) and the peach border says it is focused. A tap or a click shows **no outer
  ring**; the keyboard keeps the 2 px `outline`. **CSS alone can't tell those apart for a text field**: a browser
  counts every focus of a text field as `:focus-visible`, a tap too (the spec's heuristic: it takes typing), so the
  existing `:focus` / `:focus:not(:focus-visible)` pair - kept as it was, Safari before 15.4 still shows the ring from
  `:focus` - can't drop the ring on a tap. `static/focus-mode.js` (`?v=3`, un-deferred in `head.html`, ES5) marks
  `<html>` `pointer-focus` from a press (mousedown, touchstart, pointerdown) until the keyboard moves, and
  `.pointer-focus input:focus` (and select, textarea) drops the outline. What clears the mark (code QA Q-2): Tab and
  Shift+Tab always; the navigation keys (arrows, Home, End, Page Up/Down, Space, Enter) when the focus is not in a
  field one types into - so arrow keys after a tapped radio, checkbox or select bring the ring back. Not every key:
  typing and caret moves in a tapped text field keep the calm border (an on-screen keyboard sends keys too, so "any
  key" would put the ring back the moment a parent types on a phone). The borderless fields that are a row's value
  (`.row-input`, `select.pick`) have no border to tint: any focus gives them a quiet tinted underline (`box-shadow:
  inset 0 -1.5px 0 var(--tint)`), a tap's only sign, the keyboard's ring on top (before, a tapped one showed only the
  caret). Without JS the ring shows as before; a page's `autofocus` on load (a refused save's field) has no press yet,
  so it shows the ring. A page opened at a `#fragment` is marked `fragment-focus` too (design 84's fix round, "The
  night band on every page"). Tests: `jstest/focus-mode.test.js` (each press, pointer -> keyboard -> pointer, typing
  kept, arrows after a radio or select, the fragment's mark), `shell::the_accent_rules_hold` (the tokens, the three filled rules and their hover
  with night text, no `--strong`, the no-ring rule, the field's border and the borderless underline, the head's
  script).
- **(3) Filled buttons** (the plain `button`, `.button-small`, the summary's `.act-strong` "Lock again"): `--tint-fill`
  `#E8895E` with night text `--on-tint-fill` `#0C0C14` (7.6:1), hover `--tint-fill-hover` `#E07A4C` (6.6:1); dark:
  `--tint-fill` `#F0A884` (today's dark `--tint`) with the same night text (9.9:1, hover `#F6C4AA` 12.4:1). `--strong`
  / `--strong-hover` (#A64B25 with white) are gone. Links, tabs, icons, the secondary buttons and text keep `--tint`
  (#A64B25, dark #F0A884); destructive buttons (`--destructive`) are unchanged. The light fill is 2.6:1 against the
  white card: the button is told by its night label (WCAG 1.4.11 asks nothing more of a labelled button).
- **The browser pass** (Chromium and WebKit, 390 px, light and dark, `pwa-fixes-c` on port 3170 with a fresh data dir):
  Add Phone with the field tapped (no ring, tinted border, white fill; dark: peach border, night fill) and reached
  with Tab (the ring), the Next button's colours, the Devices list, a phone's page before and after uploading a
  generated PNG (`/devices/1#avatar`, main's scroll 0, the picture with the dot) and after Remove, a refused file's
  reason, Notification Settings; no console errors, no failed requests. **WebKit passes use `is_mobile=True`**: in
  Playwright's Linux desktop WebKit (no `is_mobile`) Notification Settings and Settings go to `/login` about 0.3 s
  after loading and the next screenshot hangs. The code QA traced it (`docs/review/qa-pwa-fixes-c-code.md`): that
  build exposes `PushManager` (its mobile emulation doesn't), and `PushManager.getSubscription()` on a page with a
  registered worker makes the browser itself navigate - the session stays (the next page loads signed in), and the
  two pages are exactly the ones that call it (`notifications.js`, `register-sw.js`'s Sign Out lookup). A Playwright
  artefact, independent of this branch and of main's code; stubbing `getSubscription` in an init script keeps the
  pages too. Desktop Safari has a real push implementation, so it says nothing about it.

## A Cancel in every confirm (2026-11-01, PLAN "PWA small things left from the old list" (3), branch `small-c`)

- Every `<details>` whose class has `confirm` (33, in the pages and partials - the summary's "Unlock 2 h…" is `act-sheet confirm`) ends its form with `partials/confirm_cancel.html`:
  `<button type="button" class="button-cancel" data-confirm-cancel hidden>` "Cancel" / "Avbryt" (`common.cancel`), a
  quiet text button under the red one. `static/confirm.js` (deferred, from the head partial; one listener on the
  document) shows them and, on a click, closes the confirm it sits in and focuses its summary
  (`preventScroll`): the page stays where it is, nothing is posted. Without JS the button stays hidden
  (`.button-cancel[hidden]`) - the summary closes the details then, as before. A new confirm gets the include before
  its form's `</form>` (`shell::every_confirm_has_a_cancel`, `jstest/confirm.test.js`).


## Tidier status on Devices and the summary (the user's pick C, 2026-11-01, branch `status-tidy`)

- **Why**: the row said "Online · seen for 7 min siden", "4 min today", "Battery 100 % · for 7 min siden" and the
  header added "At Test since 24. okt. 22:19" - the ages repeated what the dot says. The user picked mockup C, the
  minimal (artifact "Vibb phone status"), with "Slått av" told apart from "Frakoblet".
- **The rule** (`src/phone_status.rs`, pure; `phone_status::tests`): the name, then on the Devices row ONE line, the
  first that holds: (1) not set up / "Disconnected · access removed" / not seen yet; (2) **turned off** - design 38's
  `phone_off` later than every `phone_on` and status report and past its push hold (`parent_events::offs_now` +
  `off_since`, the rule the summary had): "Turned off 21:14 · battery was 3 %" (nb "Slått av 21:14 · batteriet var
  3 %"); (3) **offline** (`streams::connection_state`): "Offline · last seen 13:05" (nb "Frakoblet · sist sett
  13:05"), the exact time in `title`; (4) **a rule locks**: the lock's words (`LockBadge::words`: "Leggetid until
  07:00 · calls off", nb "Leggetid til 07:00 · anrop av", "at last report (…)" as before) · the battery; (5) **low
  battery** - `devices.battery_low_since` set (the phone's own `low`, design 35 §2.5) and the last report not
  charging: "Low battery, 9 %" · today's screen time; (6) else today's screen time · the battery ("4 min today ·
  100 %", "62 %, charging"), or "Online" when the phone said neither (was the connection's word, "Online"/"Away"). No "seen N min
  ago" and no battery age while the phone is online or away: the last contact is said only as "last seen" once it
  is offline. A phone turned off or offline shows that line alone - its lock, "calls off", screen time and battery
  are not shown (the last report may be stale; Device › Time still has them). Away (no stream) reads as online
  under 10 min and leads with "No contact" after it (below, since 2026-11-01). The summary's Away/Offline tails "changes may be delayed" /
  "changes wait until it's back" were dropped on purpose with the ages (the user's pick, the minimal variant C).
- **The header** (`phone_status::header`): line 1 the same lead, the screen time named ("4 min screen time today", nb
  "4 min skjermtid i dag"); line 2 the zone the phone is in now ("Test since 22:19", `devices::zone_now`) · the
  battery - the screen time instead when the lead is the battery; a phone turned off, offline or not reachable has
  line 1 alone. The lock is orange text now (`tone-orange`), not the lock badge's pill.
- **The dot** says what the line leads with (`phone_status::dot`, since 2026-11-01, below): orange for a low battery
  would read as "No contact", so the low battery is said in the line, in orange.
- **Times**: `Zone::since` - the clock alone today (never "24. okt." for today), "yesterday 21:40", the weekday within
  the week ("Sat 22:19"), else "24 Oct 22:19". Screen time with `display_time::duration` ("1 h 12 min of 1 h 30 min
  today"). Device › Time keeps `ScreenTime::text` and the badge's English `text_beside` until its slice.
- **Words**: `devices.status.*` in both catalogs, the reader's language (the pages around them are still English
  until S-i18n slice 3a); `events.phone_off.off_since*` went (the line is `devices.status.off`/`off_battery`). Wording 2026-11-01: "last seen" / nb "sist sett" (was "last heard" / "sist hørt"); keys unchanged.
- **Markup**: `partials/status_line.html`'s `parts` macro - the parts with " · " between them, each `<span>` with its
  tone class, its `data-` hook (`lock`, `battery`, `screen-time`, `power-off`, `seen`, `zone-now`) and `title`; the
  battery part has a small CSS battery (`.bat`, filled to the level, `aria-hidden`) and a visually-hidden "Battery",
  left out when the words already say it (`Part::says_label`: "Low battery, 9 %" isn't read "Battery Low battery").
  The row's line is `span.device-line[data-line]`, the header's `p.summary-state[data-line]`; the bar
  (`devices::meter_of`) shows only while a line says the screen time. `tests::status_lines` reads the lines as a
  reader sees them. Reads: the Devices list reads the power events once for every phone (`offs_now(db, None)`;
  migration 0109's `idx_parent_events_device_kind` answers its per-phone subqueries), the summary its own
  (`offs_now(db, Some(id))`); a failure is a 500 on both (logged) - read as "none is off" it would show a turned-off
  phone as reachable; everything else the pages already read.
- Tests: `phone_status::tests`, `display_time::tests::since_says_the_clock_today_and_the_weekday_within_the_week`,
  `src/tests/devices_list.rs`, `summary.rs`, `power.rs` (the row and the header "Turned off"), `notifications.rs`
  (the low battery, no doubled spoken name), `zones.rs`, `enrollment.rs`,
  `devices_list::the_list_fails_loudly_when_the_power_events_dont_read`, `music_spotify::migrations_73_to_109_in_order`.

## Off or out of touch says so: dot and line agree (2026-11-01, branch `status-off`)

- **Why**: Emma was turned off at 20:35; the push came, but the Devices row showed an orange dot and no "Turned
  off" (the user: "Kanskje når den ikke er grønn burde det stå slått av kl xx eller mistet kontakten for x min
  siden"). The dot was the raw connection (orange = no stream, heard within 45 min - also for a phone turned off),
  and `offs_now` compared the off's `happened_at` (the phone's clock) with the reports (the server's), so a phone
  clock behind or the report sent as it shut down hid the off. The user accepted the mockup ("Vibb status av og
  frakoblet").
- **The rule** (`phone_status::dot`, `no_contact`, `NO_CONTACT_AFTER_MINUTES` = 10): turned off - grey at once,
  "Turned off 20:35 · battery was 100 %"; online (stream open, `ONLINE_WINDOW_MINUTES`) or Away under 10 min -
  green, the line as before ("Online" when the phone said nothing; a sleeping phone drops its stream for a moment,
  not worth a word); Away 10-45 min (`AWAY_WINDOW_MINUTES`) - orange, the line leads with **"No contact · last seen
  20 min ago"** (nb "Ingen kontakt · sist sett for 20 min siden"; `data-no-contact` in `state-away`, then `data-seen`
  with the exact time in `title`) and then the rest of the row's line as before (the lock · the battery, the low
  battery · the screen time, the screen time · the battery); Offline (over 45 min) - grey, "Offline · last seen
  13:05" (nb "Frakoblet · sist sett 13:05"; en "last seen" too, for one wording with the orange line); not set up,
  access removed, not seen yet - grey. The header without contact: line 1 "No contact · last seen …", line 2 the
  row's rest with the screen time named, no zone (where it was then isn't where it is now).
- **Where**: the Devices row (`DeviceListRow.dot`, also its line's `data-status`), the summary's header
  (`DeviceDetailTemplate.dot`), and until design 85 revision 3 Locate's switcher and list (`PhoneLink.dot`/`word`,
  gone with the switcher; the word in the reader's language - "Online", "No contact", "Offline", "Turned off 20:35" -
  and in the `aria-label`, "Anna, no contact"). `ConnectionState::word` is
  the tests' alone now.
- **The off by the server's clock**: `parent_events::offs_now` weighs the reports against the off's arrival
  (`created_at`, `datetime('now')` text like `reported_at`, both through `datetime()`) + 60 s, not `happened_at`;
  the off against the starts stays `happened_at` (both the phone's), and the line's time is `happened_at`. A late
  off (told at the next start) comes with its start, which ends it. Locate's "Last position before the phone went
  off" (`locate::before_off`) weighs the report against the same arrival while it is within
  `ARRIVAL_NEAR_OFF_MINUTES` (5) of the shutdown's instant - else (a late off, told at the next boot, or the event
  gone) the shutdown's instant: a report just after that boot is the phone on again (light QA L1). Locate's list line
  says "last seen 13:05" / "access removed" from the catalogs (`devices.status.last_heard`,
  `device.about.access_removed`; was the English "seen 3 min ago", L2).
- **The off's last position**: "Last position at 20:26 – see the map in Locate" (nb "Siste posisjon kl. 20:26 – se
  kartet i Finn": "Finn" was read as a verb), "Last position yesterday 20:26 – …" on another day
  (`events.phone_off.detail.position_on`), "Last position – …" without a time.
- Tests: `phone_status::tests::the_dot_says_what_the_line_leads_with` (each state's dot, 9/10/44 min, the mockup's
  Anna, off over "No contact", the parts), `devices_list::each_phone_shows_its_connection` (Ella away just now green
  "Online", Frida 20 min orange "No contact", nb), `summary.rs` (away just now green), `power::a_phone_clock_behind_and_a_report_at_shutdown_still_say_off`
  (3 min behind, a report 30 s after the arrival: "Turned off" and the grey dot on both pages),
  `locate::tests::the_last_position_before_the_phone_went_off` (the clock behind),
  `parent_events::tests::the_offs_last_position_points_to_the_map`.

## Words: the i18n layer (design 69, 2026-10-26; round S-i18n, design 70, `docs/design/70-pwa-i18n.md`)

- `src/i18n.rs`: `Lang { En, Nb }`; `t(lang, key)`, `t_params(lang, key, &[("name", value)])` (`{name}` in the text,
  filled in one pass: a value is never scanned again), `t_count(lang, key, n, params)` (`<key>.one` or `<key>.other`
  by `Lang::plural_form`, `{count}` filled with `n`; zero is its own key the caller picks) and `i18n::error(lang,
  status, key)` (a plain-text answer, e.g. the CSRF refusal `error.csrf`) over `src/i18n/en.json` and
  `src/i18n/nb.json`, flat objects compiled in. A missing key reads as the key itself ("account.title" on the page)
  with a `warn!` - no English fallback.
- **The catalog's rules**: a key names the thing, not its English words (area, page, thing: `account.password.new`);
  no markup in a value (a sentence with a link is split at the element: `….before`, `….link`, `….after`), Askama
  escapes every `{{ }}` and no word gets `|safe`; a param is for user data (a name, a phone, a time) - a word of ours
  is its own key, so the two catalogs can order a sentence freely; a count is a `.one`/`.other` pair, both with
  `{count}`. `i18n::tests` checks that both catalogs have every key with the same parameters, no empty value, no `<`
  or `>`, the plural pairs, and that every key the templates and handlers name exists (the call forms' first argument,
  and every quoted literal shaped like a key in one of the catalog's namespaces - its keys' first segments).
- **The language of a request** (`Lang::of_request`, the extractor), first match wins: `?lang=en|nb` - that request
  only, never stored, any other value ignored (QA, screenshots, the sign-in page); the account's `admin_users.lang`
  (migration 0104), read from the `CurrentAdmin` that `require_session`/`require_full_auth` put in the extensions -
  no database read; `PWA_DEFAULT_LANG` (`en` or `nb`, `ForkConfig::pwa_default_lang`, read once at start; unset or invalid =
  skipped, 2026-11-01); `Accept-Language` (its q-values; `nb`, `nn`, `no` are Norwegian); English. The sign-in pages
  (no account) use the same order. Before a choice,
  `require_full_auth` stores the first signed-in request's language (the env default when set, else `Accept-Language`; never `?lang=`) with one
  `UPDATE ... WHERE id = ? AND lang IS NULL`, so today's sessions get it at their next page. One language per
  account, not per device. `provisioning_settings.locale` (0034, `nb_NO`) is the phones' locale, not a parent's,
  and never this fallback.
- **The Account row**: Settings › Account › Language (`id="language"`), one select, "Norsk" and "English" each in
  its own language (`lang` on the option), the account's stored language selected; it saves on change
  (`static/autosave.js`, a `<noscript>` Save) to `POST /account/language` (`lang=en|nb`, else a 400 worded by
  `i18n::error`; behind full auth and `csrf_guard`, guarded by the session's epoch like the Name), a 303 to
  `/account#language`: the page comes back in the new language at the same place, and `settings_log` logs it as a
  save.
- **A page**: its template struct has `lang: Lang` and `crate::i18n::words!(TheTemplate)` (the `Words` trait:
  `lang()`, and `t`, `tp`, `tc` as default methods), so the page and the partials it includes (`head`, `tab_bar`,
  `bell`) say `{{ self.t("…") }}`, `{{ self.tp("…", [("name", value.as_str())]) }}` (no `&` before the array: Askama
  references an argument) and `{{ self.tc("…", n, []) }}`; the module that first calls one imports
  `crate::i18n::Words`. A moved page sets `<html lang="{{ lang.code() }}">` (the `<html>` tag is the page's own, before
  `head.html`); a page still in English keeps `lang="en"` until its slice-3 PR moves its words and flips it
  (qa-s-i18n-1-code L1), so a screen reader never reads English text with a Norwegian voice. A handler takes `lang: Lang` as an extractor and hands it to the helper that builds the page. Since
  slice 1 every template struct has it (`/sw.js` has no words, §4: its template is the one without); Device ›
  Calendar, its rule editor, its preview and the summary's Calendar row (`calendar.md`) and Settings › Account (with
  its Parents group, `parents::rows`) were worded first; since slice 3e every page is. Since slice 3a the
  "Wrong codes before this sign-in" banner is facts in the session (`admin_auth::LoginWarning`: the count and the
  addresses), worded by the page that shows it (`account.warning.login*`, the Devices list's row `devices.warning.*`);
  a session from before keeps an English sentence that no longer reads, so its banner is gone.
- **Slice 3a (Devices)**: the Devices list, a phone's summary and its sub-pages but Locate's and Music's, Add Phone,
  Setup Code and Connect, and the shared partials (`tab_bar`, `bell`, `info_row`, `note_*`, `recent`, `exceptions`,
  `budget_rows`, `device_app_row`, `app_permissions_form`, ...) are worded, with their handlers' titles, notices,
  sub-page rows and error bodies (`i18n::error`; `device_pages::load_device`/`load_policy` answer a status and a
  key) and `app_permissions::words`. `i18n::tests::moved_templates_have_no_literal_words` scans the moved templates
  (`MOVED`); the every-page scripts read `partials/js_words.html` (`#js-words`, `data-w-*`: `reconnect.js`'s chip,
  `nav-timing.js`'s On/Off), `reveal.js` its button's own `data-w-copied`/`-select`, and `jstest/words.test.js` keeps
  the moved scripts free of words. Left for slice 4 (done, below): the summary rows and Attention, the badge and screen-time words,
  the helper modules' words (`kid_lock`, `kiosk_escapes`, `window_relock`, `lifts`, `activity`, `app_downloads`,
  `sound_mode`, `boot_witness`, `models`, `streams`, `config`, `photos`, `phone`); for 3c Locate's command rows
  (`locate::CommandRow`), for 3e Time Rules' day and limit words (`schedules`) shared with Device › Time.
- **Slice 3b (Apps)**: the Apps tab, Add App, an app's page and the "Name and icon" form
  (`partials/app_display_form.html`, on Device › Apps too) are worded (`apps.*`), with the handlers' titles, notes,
  field refusals (the file name filter as `tracked_apps::FilterRefusal`, the tag prefix, the F-Droid source and its
  pins, a source change), the sync titles (`SyncProblem::title(lang)`; the log keeps English through `Display`) and
  error bodies (`i18n::error`). `app_display` is worded by the form: `AppDisplayForm::new(lang, …)` words the summary
  (`DisplayValues::describe(lang)`), the icons' names (`apps.display.icon.<key>`, `every_icon_is_worded`: a new icon
  needs its key) and the colours (`color_label` gives a key), and a refusal (`FieldError.key`); `preview_text` is a key the template words. Install Now's rows, flashes and `install-now.json`'s sub-lines come
  from `install_now` in the reader's language (`Offer::sub(lang)`, `value(lang)`, `flash(lang, word)`, the working
  words in the `Zone`'s); `app-display.js` reads the preview tile's own `data-w-own`/`data-w-vibb`. Left for slice 4 (done, below):
  a sync's or upload's raw detail behind ⓘ (`Failure::detail`), `app_downloads::progress`
  (`fdroid::normalize_repo`/`parse_package` take `lang` since the merge with round S4); for 3c Install Now's answer row (`locate::CommandRow`).
- **Slice 3c (Locate)**: the Locate tab, a phone's Locations page and a zone's page are worded (`locate.*`), with
  the handlers' titles, rows, notices, the zones' rows and footers (`locate::zones_view`, `watch_line`), the field
  refusals (`ZoneErrors` holds keys the page words) and error bodies (`i18n::error`). Every command row
  (`locate::CommandRow`: Locate's Requests, the summary's Activity, Install Now's answer) is worded by
  `command_row` in the `Zone`'s language: `models::command_label` gives a key (`locate.command.*`), `status_words`
  the status word and its line, `reword(lang, raw)` the phone's answer (`locate.answer.*`), `cancel_flash(lang, …)`
  the line after a Cancel. `locate-result.json` takes `lang: Lang` and words its `text` so; `static/locate.js`
  (`?v=5`) asks it with the page's `?lang=` (the map box's `data-lang`, as Install Now's poll), and reads its other
  words from the page: the Locations count's `data-w-none`/`-kept`/`-in-12`/`-in-24`/`-in-168`/`-newest`, the map
  box's `data-w-newest` (the marker's popup for a newer fix) and the wait line's `data-w-answered`/`-no-answer`.
  The span pick's words are `locate.span.<hours>` (`locate::SPANS` holds the keys). Zone events and their pushes were
  keys already (design 64, slice 2). Left for slice 4 (done, below): `kid_lock::PinAnswer::words` (a `set_kid_pin` answer in
  Activity stays English with `kid_lock`'s other words).
- **Slice 3d (Music)**: the Music tab, an entry's page, Settings (with `partials/spotify_setup.html`), Categories,
  Import from Vibb, the Storytel shelf, Add from Spotify, the Spotify login's step back and Device › Music are
  worded (`music.*`, `device.music.*`, `error.music_*`), with the handlers' titles, rows, notices, field refusals,
  upload problems, the import's preview and result, and error bodies (`handlers::music::server_error(lang, …)`,
  `i18n::error`). The cards' status lines (`SweepView`: Checking…, Waiting…, Checked …, Last check failed …, the
  phones' lines, the Spotify card's notes) come in the `Zone`'s language; `music::kind_key` gives a kind's key,
  `ORDERS` holds keys, the offline choices are worded by `cache_label`/`storytel_caches`, the category icons and
  colours by `icon_word`/`color_word`, `spotify::LoginOutcome::text(lang, …)` the login's notice and
  `OlderApps::title_key` Device › Music's update warning. `music::parse_link` (the Add-link form) and
  `music_import::parse` (the Import form) refuse with a key (`music.link.*`, `music.import.not_json`/`no_sections`)
  the handler words; the import's skip reasons word `parse_link`'s keys since slice 4.
  `static/music-cards.js` (`?v=4`) asks `/music/cards` in the page's `<html lang>` (`cardsUrl`). Left for slice 4 (done, below):
  the helpers' sentences inside those lines - `music::sweep_error_text`, `CheckError::message`, `entry_error_text`,
  `StorytelError::message`, the Spotify link and add refusals (`spotify::*::message`, `add_refusal`),
  `music::check_link`'s refusals, `music_import`'s skip reasons (`Skip::reason`), `photos::PhotoError::message`,
  `music::storage`'s sizes - and the summary's Music row and Attention (`OlderApps::title` stays English there).
- **Slice 3e, part A (Settings' core pages)**: the Settings hub, Notifications (the inbox) and Notification
  Settings, Time Rules, a rule's editor and Daily Limit, Web Filter, Blocked Activity, Wallpapers, Software Updates,
  Backups and Phone Setup are worded (`settings.*`, the shared `settings.schedule.*` and `settings.hint_*`), with the
  handlers' titles, rows, flashes, field refusals and error bodies (`i18n::error`). The hub's values (`t_count`,
  `settings_hub::blocked_today`, `backup_label` through the `Zone`), the inbox's delivery lines
  (`notifications::delivery_line(lang, …)`) and Getting Notifications' rows and badges (`device_row`, so
  `subscriptions.json` is worded in the request's language) come in the reader's language; the Notify Me About
  switches are worded by column (`switch_key`, `every_switch_is_worded`). Time Rules' day and limit words
  (`days_summary`, `budget_summary`; nb lower-case days, the first capitalised) also word Device › Time; the
  rule, bedtime and limit refusals take `lang` (`parse_rule_form`, `parse_exempt_apps_form`,
  `parse_budget_form`). The Web Filter's address refusals and backup reasons are keys the page words
  (`refusal_text`), its phone lines are in the `Zone`'s language, `FeedState::problem(lang)` also words Device ›
  Web Filter's list rows; `config::check_server_url` answers a key (`Refused`) or the host (`TailnetHttp`), worded by
  Phone Setup and the setup QR (`provisioning::plain_http_warning(lang, …)`). `backups::WEEKDAYS` holds keys.
  `static/notifications.js` (`?v=4`) reads This Device's state words from its form's `data-w-*`. Left for slice 4 (done, below):
  `backups::human_size`, `photos::PhotoError::message` (an upload's
  refusal), a push failure's text (`push::Subscription::failure`) and the built-in wallpapers' and seeded lists'
  names (data).
- **Slice 3e, part B (accounts and sign-in; the last templates)**: the sign-in pages (`login`,
  `verify_2fa`, `change_password`, `setup_2fa`; `auth.*`), Settings › Security Log (`security_log.*`, `error.read_security_log`,
  `error.unban`), Account's last examples (the server commands `account.totp.command_*`, the same in both catalogs),
  "Turn the Password On Again", the passkey partials and Device › Calendar's add, link, CalDAV and rule pages (their
  examples: `calendar.link.url_example`, `calendar.caldav.server_example`, `calendar.rule.pattern_example`/
  `text_example`). The sign-in pages have no account: their language is `?lang=`, `PWA_DEFAULT_LANG`, then
  `Accept-Language` (`tests::admin_auth::the_sign_in_pages_read_in_norwegian` walks login, the first password, the
  two-factor setup and the code step in nb). The handlers word their refusals (`auth.login.wrong`, `auth.banned`,
  `auth.invite_expired`, `auth.pending_expired`, `auth.code_wrong`, `auth.change_password.*`; the two-factor lock's
  wait `auth.locked.text` with `auth.locked.hours`/`minutes` counted) and their 500s (`i18n::error`); a ban whose
  end can't be read says "For a while" (`security_log.banned.a_while`). A field's error-or-hint is two `{% if %}`s
  (one inside the `<input>` for `aria-describedby`, one after it), so the scan sees whole tags. `app-lock.js` and
  `calendar-preview.js` join `words.test.js` (no words: the locked tab's title is the name). Since 3e `MOVED` is
  every template. Left for slice 4 (done, below): the words Rust builds in the helper modules (design 70 §6), the security log's
  row for an id no catalog names (`admin::words_of`, the id as words), and the `SignInWhy` reasons (log only).
- **Events, the security log and the times (design 70 §4, slice 2)**: `display_time::Zone` carries the reader's
  `Lang` (`Zone::load(db, lang)`, `in_lang`), and every time word comes from `time.*` - "nå nettopp", "for 3 min
  siden", "i går 21:40", "i morgen 00:05", "man. 14:30", "27. okt." (en as before, 24 h in both); `Zone::date_of`
  for a date alone, `display_time::duration` for "1 t 30 min". The parent's events are stored as key + params and
  worded when read (`parent_events::told`/`pushed`/`detail`, `events.<kind>.*`; `notifications.md`, "Push
  wording"). The security log's rows are words too: `security::record_security_event` takes a `Detail` -
  `Detail::words(key, params)` (sentences stored in `security_events.params`, migration 0107, as `{"key", "p"}`
  or a list of them, with `t` instants told in the reader's zone, `w` words of ours and `d` durations) and
  `Detail::facts(text)` (identifiers only in `detail`: a route, a tag, an address, an entry's or a phone's
  `device {id}`, which the phone lines' hourly budget counts by) - the labels are `security.event.<id>`
  (`admin::event_label`; an id this server never wrote reads as its own words), and a row from before slice 2 shows
  its prose as stored, as does a `banned_ips.reason` from before. The audit throttle's "(N times in a minute)" and
  summary rows, the phone lines (`parent_pin::log_line`, `window_relock::log_line`, …), `time_rules`' conversions
  and `settings_log` (facts: the page and its routes) go the same way. The page shows a row's words, then " · "
  and its facts (`Detail::shown`): the words say "phone 3" in the reader's language, the facts and the greps say
  `device 3`. A `security.detail.*` key is frozen once a release wrote it - old rows name it for 180 days and a key
  the catalog lost reads as itself - so a rename keeps the old key (or maps it when reading) until the rows are gone.
- **The words Rust builds (design 70 §6, slice 4)**: what the slice-3 PRs left in the helper modules is worded in the
  reader's language too. A helper takes `lang` (or reads it from the `Zone` it already gets) and either words
  its text (`t`, `t_params`, `t_count`) or returns a key the template words (`{{ self.t(key) }}`): the summary's
  rows and values (`device.summary.value.*`), Attention (`attention::items(lang, …)`, `device.attention.*`, counted
  rows by `t_count`), the lifts and their flashes (`device.summary.flash.*`, `lift_state.*`;
  `lifts::minutes_text` went: `display_time::duration` is the one length of time), Locked Again and the blocked apps
  (`window_relock`: `BlockWhy::key`, `findings` in the `Zone`'s language, the flashes as keys), About This Phone's
  network, sound and starts rows (`limits::Via::label(lang)`, `sound_mode`, `boot_witness`), Advanced's update fence,
  filter and boot cover notes (`kiosk_escapes::EscapeNote::note`, `device.advanced.escape.*`) and the restart
  effects (`models::admin_policy_effects` gives keys), Lock Screen & PINs (`kid_lock::LockNote::note(lang, …)`,
  `PinAnswer::words` a key, both PINs' flashes `flash_text(lang, …)`, the screen timeouts `SCREEN_TIMEOUTS` as keys,
  the lock-screen notifications' notes `lock_glance`), Device › Apps' downloads (`app_downloads`), Device › Time's
  screen time (`ScreenTime::text(lang)`; the badge's English `text`/`text_beside` went for `LockBadge::words`), the
  music errors and sizes (`CheckError::message(lang)`, `entry_error_text`/`sweep_error_text(lang, …)`,
  `StorytelError::message`, `SpotifyError::message(lang)`, `spotify::add_refusal(lang, …)`, `LinkError::key`,
  `music_import::Skip::reason(lang)`, `music::storage(lang, …)`), a push failure (`push::failure_text(lang, …)`),
  a phone number's refusal (`PhoneError::key`), the built-in wallpapers' and seeded blocklists' names
  (`WallpaperRow::shown_label`, `models::blocklist_name`: a built-in can't be renamed, so its name is ours; lists
  sorted by the shown name), the fallback name an entry is stored with when its source gives none (Spotify, a feed,
  Storytel: `music.default_name.*`, in the adding parent's language - the kid sees it; design 70 §7), a bare id
  fact alone in the security log ("phone 3" / "telefon 3") and the error bodies (`i18n::error`). Sizes and other decimals: `i18n::number(lang, value, decimals)` ("1.5" / "1,5";
  a backup's size). Two scans keep it so: `i18n::tests::no_status_answers_with_literal_words`
  refuses a `StatusCode::X, "words"` pair outside tests (the phone's API in `device_api.rs` and `security.rs`'s log
  reasons are no parent's), and `tests::i18n_pages::nb_pages_have_no_english` signs a parent in on Norwegian over a
  seeded family (`tests::seeded_family`: three phones - a rich report, the other problems, one not set up - one
  event of each kind, a security line of each label, a tracked app, a music entry, a zone, a rule), GETs every page
  and the JSON routes and fails on a whole word that is in some English value and in no Norwegian one, or on a `{param}` left
  unfilled. Both sides read only language (`i18n::language_words`): addresses, paths, packages, codes, env vars, a
  «quote», the names Vibb Phone/One/Music and an `adb …` command are left out, so "phone", "settings", "add" and
  "password" are words it sees. Since the fix round it also follows redirects, reads every one-shot flash
  (`?done=`, `?notice=`) on its page, a refused form, `/login` and the onboarding steps (`/auth/change-password`,
  `setup-2fa`, `verify-2fa`). Still English by design: the raw technical details behind an ⓘ (a sync's or
  upload's `Failure::detail`, a feed's or a list's download error, `music_net::FetchError`'s reqwest text), an id no
  catalog names (`admin::words_of`, the id as words), a subscription's stored label fallback ("Device · Browser"),
  and the logs. A rule's name the server made (`time_rules::SERVER_NAMES`: "Bedtime", "Outside allowed hours" and its
  "(morning)"/"(evening)" forms) is worded in the PWA (`time_rules::shown_name`: `device.time.rule.default.<n>`, a
  "<name> (morning)" through `device.time.rule.morning`) until the parent renames it - a save of the shown name keeps the
  server's - and goes to the phone blank, which names it by its kind (design 70 §7, slice 5; `time-rules.md`, "Wire").
  **No new
  user-facing word is hardcoded** (the user, 2026-10-26).

## The night band on every page (design 84, 2026-11-11, `docs/design/84-pwa-night-band.md`)

Every page in the app shell opens on one night band and holds everything else in a sheet with a rounded top (the
user's pick F2b from a clickable lab). The older sections above describe the 44 px title bar, the large title under
it, the sticky editor bar and the back link's visible word: all four are gone, and each of those places has a
pointer here.

- **One partial writes it**: `templates/partials/band.html`, imported after the tab bar's include
  (`{% import "partials/band.html" as band %}`) and called once per page with the page's actions as the call's body.
  It writes the page's only `<h1 class="page-title">`; no template writes an `<h1>` or a `.nav-back` itself
  (`tests::shell::every_shell_page_opens_on_one_band`). Five kinds:

  | Macro | Left of the name | Right of it | Pages |
  |---|---|---|---|
  | `band::tab(title, tab)` | the round Vibb mark (`.band-mark`, 36 px, the app icon's artwork) | the call's body ("+" on Devices and Apps), then the bell - last by construction | the 5 tab pages |
  | `band::sub(title, href, parent)` | the back chevron | the call's body | 29 pages under a tab |
  | `band::phone(title, href, parent, who)` | the back chevron | the phone's small picture, then the call's body | a phone's 10 pages |
  | `band::editor(title, href, parent)` | the back chevron | the call's body: Save | the 4 editors |
  | `band::bare(title)` | the mark | nothing | `music_spotify_done` (no session) |

- **The mark is drawn inline** (2026-11-12, the user: pull-to-refresh on Devices made the logo blink): `band::mark()`,
  an `<svg class="band-mark">` of `partials/vibb_breath.html`'s four rings and core at rest (full colour) in
  `icon-192.png`'s frame (`viewBox="-28 -28 56 56"`, measured against the PNG), not the icon as an `<img>` - that was
  painted only once the PNG had decoded, a blank frame on every load. Its paths keep every other point, to 0.1 and
  relative: 2.3 KB on every page in place of 7.9, and against `icon-192.png` at 36 px no worse than the full paths
  (PIL, mean difference 4.2/255 against 4.3). The PNGs stay for the manifest, the favicon and push;
  `the_tab_pages_open_on_their_large_title` pins the `<svg>`, no `<img>` on the band, the four rings' starts, the
  colours and that the mark stays under 3 KB.

  49 templates; `login`, `lock`, `change_password`, `setup_2fa` and `verify_2fa` are outside the shell and have none
  (the test names the five, so a new template takes a band or fails it).
- **The back link** is the chevron alone, 46x44 px; where it goes is only spoken: `<span class="visually-hidden">`
  with `Words::back_to(parent)`, `nav.back_to` ("Back to {name}" / "Tilbake til {name}"). `href` is an expression: a
  literal, a field, or askama's `~` with a space on both sides (`"/devices/" ~ device.id`). `enroll_code` and
  `provision_qr`, whose target depends on the phone's state, take `back_href`/`back_label` from their handlers. A
  test that read the link's visible word reads the hidden span now.
- **The name** is one line, never two: a title over 16 letters marks the band `band-long`, over 24 `band-xlong`
  (counted in the partial on the translated string, so a page can differ between the languages); the type steps
  down where the row is short of room and the rest is cut with an ellipsis (the user: a long phone name may be cut).
  A 40-letter phone name is cut on its own summary, at 390 px too.
- **A phone's ten pages** (`device_about`, `device_advanced`, `device_apps`, `device_calendar`, `device_calls`,
  `device_filter`, `device_home`, `device_lock`, `device_music`, `device_time`) say whose page it is with the phone's
  picture, since the name left the back link (the user's choice, "Enig i c!"): `devices::BandAvatar { initial,
  avatar, photo }` from the functions the list and the summary use (`BandAvatar::of(&Device)`, from the
  `Device` each handler reads once), drawn as `<span class="band-who" aria-hidden="true">` around the list's own `.avatar`
  at 28 px, the photo with an empty `alt` - not spoken (the back link says the name), not a link. It stands between
  the `<h1>` and the actions box and is always shown: the title gives way. Measured at 320 px: "Låseskjerm og
  PIN-koder" reads "Låseskjerm og PIN-ko…", every other title in both languages is whole. The summary and the pages
  under the ten (a rule, a zone, a calendar's sub pages) have no picture.
- **The band's box** (`style.css`, "The night band"): `--band-top` is the status bar's room,
  `max(calc(6px + env(safe-area-inset-top)), var(--top-strip))`, and `--band-h` 54 px more. `.band` is night in both
  schemes with the page's column in its side paddings (16 px, the notch's insets, 720 px), a row of 44 px at least
  (`min-height`, so a larger text size grows it), and 32 px under the row of which the last 22 lie under the next
  box (`margin-bottom: -22px`) - what shows in that box's rounded corners is the band's foot. In a browser
  tab the band is 82 px high and the sheet starts at 60. *(Until the fix round below a `border-image` outset painted
  100vh of night above it for the bounce, as Devices' bar did in the document; inside `main` iOS drew none of it.)*
- **The sheet**: `<div class="sheet">` right after the band, to the end of `main`, on `--bg` with
  `border-radius: 22px 22px 0 0` and a 1 px top border in `--band-edge` (transparent in light, `#34344A` in dark,
  where band and ground are the same night). It has the paddings `main` had: `.container` itself has none since
  this design, and the room for the tab bar is `.tab-bar ~ .container > .sheet`'s bottom padding. Top padding 15 px
  beside the border: the first row is 16 px under the sheet's edge. A page's first header takes `margin-top: 10px`
  in place of 24 (nine `.sheet > ... :first-child` selectors, plain lists: no `:is()` under the floor), 26 px under
  the edge; the summary's head 4 px. `device_calls` opens on its Held section, which is empty without held
  messages, so its first visible header keeps its 24 px.
- **The four editors** (`schedule_rule`, `device_bedtime`, `device_zone`, `device_calendar_rule`): the band is a
  child of `<body>` right before `main` (`.band-fixed`, `flex: none`) and does not scroll; `main` is
  `.container.container-fixed` and is itself the sheet (the same edge and radius, its own paddings), scrolling
  inside its rounded top. Save is a `<button type="submit" class="title-action" form="<the form's id>">` on the
  band, outside the form (`tests::shell::every_editors_save_names_its_form` names each pair: `rule`, `bedtime`,
  `zone-form`, `rule-form`). The save's flash - "Saved …" or "Not saved. …" - is `.editor-flash`, the form's first
  box, rendered only with a flash: `position: sticky; top: -15px; margin-top: -15px; padding: 15px 0 8px` against
  `.container-fixed`'s 15 px, so it sits right under the band and stays there while the page scrolls (change one
  number, change the other; the shell test pins both). **`.container-fixed > form` is `display: flow-root`**:
  without it the flash's negative margin collapses through the form and moves the form's top edge, which is what
  `scroll-restore.js` puts back on the screen - a save came back 15 px off. A save never jumps: `main.scrollTop`
  measured before and after a save on the rule, bedtime and zone editors is equal. A calendar title rule's
  form posts to another path than its page, so there the browser lands on the form's top with the flash in view,
  as before this design.
- **The push banner** (`register-sw.js`, `?v=6`): first in the sheet when the sheet is `main`'s child, else first in
  `main` before the form (an editor). `.push-banner + form > .editor-flash { margin-top: -8px }` starts a flash at
  the banner's lower edge. `jstest/register-sw.test.js` has both places and the kept scroll.
- **Locate's map into view** (`locate.js`, `?v=6`): `main` has no top padding now, so a Locations row puts the
  map's top 8 px under `.top-strip`'s height by an offset in `main.scrollTop` (`scroll-margin-top` needs Safari
  14.1). `.pull-refresh` comes down at `calc(var(--band-h) + 6px)`, over the sheet.
- **The fix round** (2026-11-11, the user's iPhone on server 0.74.0; branch `band-fix-1`, design 84 revision 4):
  - *The bounce*: pulling a page down at its top showed a light field between the status bar and the band. iOS draws
    nothing of the scrolled content beyond its ends in a scroller's bounce (the band's outset above it), only the
    scroller's own ground - `main`'s was clear, so `body`'s `--bg` showed. `.container:not(.container-fixed)` is now a
    column (`display: flex`) with `background: linear-gradient(var(--night) 50%, var(--bg) 50%)` - a scroller's
    background stays with its box, so a pull at the top bares night, one at the bottom the sheet's ground - and
    `.container > .sheet` grows (`flex: 1 0 auto`), so a short page's sheet still covers `main`'s lower half and the
    ground shows nowhere else. The pull-to-refresh pages (Devices, a phone's summary) are the same `main`: before a
    pull takes over the bounce shows night, and the arrow comes down over the sheet as before. An editor's `main` is
    the sheet itself and bounces inside its rounded top on `--bg`, as before. `main` always has 1 px to scroll
    (`.container:not(.container-fixed)::after`, code QA M1): on a page that fits the screen (Add Phone, the setup code,
    Spotify done, a short sub-page on a tall iPhone) iOS would otherwise bounce the document, whose canvas is `body`'s
    `--bg`. Chosen over `overscroll-behavior` on the document (iOS 16 and later only) and a night canvas (which would
    show at the bottom too): with something to scroll every bounce is `main`'s, on every iOS since 14. Seen only in
    Chromium with the content moved by hand (neither Chromium nor WebKit here has a rubber band): the iPhone has the
    last word.
  - *The back link's place*: the band's back link is a plain link, often to the row it came from (`/apps#phone-3`,
    `/apps/tracked/2#phone-3`, `/music#spotify`, `/music#entry-4`, `/account#passkey-only`,
    `/devices/3/calendar#sources`, `/devices/locate?device=3#zones`, Advanced's `#connection`, the summary's
    `#access`), and with the band scrolling away the fragment put that row at the screen's top. `scroll-restore.js`
    (`?v=7`) now keeps where a page came from - `handy-from` in sessionStorage, its path -> the referrer's path +
    search (same origin; the newest 50), set by a `navigate` load from another path, kept across the page's own saves
    (the same path), a return by its child's back link, Back/Forward and reload - and a tap on `a.nav-back` whose
    target is exactly that page leaves a record of the form record's shape with `back: true` and the parent's place
    record (`{path, href, y, t, back}`, both carriers, 20 s); `restoreTarget` puts a back record's `y` back over any
    `#fragment`. So the parent opens where it was left (Apps at its top: at its top), on every back link with or
    without a fragment, after saves on the child page and through a child's child too. A parent not left in this
    session, a back link to another page than the one it came from (the target depends on where the page was opened,
    `apps_nav`), a page opened directly or no JS: the plain link, its fragment or the top, as before. Not
    `history.back()`: after a save on the child the previous entry is the child itself. Code QA M2/L2: a save's
    answer on another path (a form record of another path came with the load) takes the form page's entry - an Add's
    `/schedules/rules/new` -> `/schedules/rules/5` keeps Time Rules - unless that entry names the answer itself (a
    child's Delete back to its parent keeps the parent's own); a link load with no referrer of this origin (a
    notification's `client.navigate`, a typed address) forgets the page's entry; and pages are compared - and their
    places keyed - without a save's one-shot query words `notice`, `app` and `saved`, which reconnect.js's reload and
    auto-reload.js take off the address again.
  - *The ring on the row*: WebKit and Chromium focus a fragment's element when it can take focus (a row that is a
    link) and count that focus as `:focus-visible` (measured: the row is `document.activeElement` and matches
    `:focus-visible` in both), so the row wore the keyboard's 2 px tint ring, clipped by its group's rounded corners.
    No `:target` rule or script did it; the tab bar's links carry no fragment, so they never showed it.
    `focus-mode.js` (`?v=3`) marks `<html>` `fragment-focus` when the page opens at a `#fragment`, until a key leaves
    pointer mode (Tab, or the navigation keys outside a text field), and `.fragment-focus :target:focus { outline:
    none }`. The focus stays on the row, so Tab goes on from it, and from the first such key every ring shows.
  - *The cap* (the user's mockup): once the band has scrolled away the sheet keeps its rounded top right under the
    status bar, and rows scroll under that edge. `.container:not(.container-fixed)::before`: sticky at `--top-strip`,
    22 px high and taking no room (`margin-bottom: -22px`), the sheet's `border-radius: 22px 22px 0 0` and 1 px top
    border, and `box-shadow: 0 -50vh 0 50vh var(--night)`: night all round the box - over the strip and into its two
    corners - so nothing of a row shows outside the curve (in a browser tab, without an inset, the corners alone at
    the top). A pseudo-element: WebKit never takes it for the status bar's colour (`.top-strip`). It takes no tap
    (`pointer-events: none`), and its corners' night reaches 1 px into the 16 px column, so it covers no tap target.
    Its z-index is 7 and a scrolling band lies over it (`.container > .band`: `position: relative; z-index: 8`; only
    its children take taps, `pointer-events`, so its foot never catches a tap on the sheet's first box, code QA L1),
    with its 22 px foot drawn night only outside the sheet's two corners (two `radial-gradient`s under a
    `linear-gradient` of the band's night): the sheet shows between them as before, and the foot covers the cap's
    curve until the sheet's own edge has passed it, so the two hand over with no seam (seen with the sheet's top 10
    px and 8 px under the strip, light and dark). The cap's edge (dark's hairline) is drawn only once `main` has
    scrolled (`@supports (animation-timeline: scroll())`, `animation-range: 0px 1px`, `@keyframes cap-edge`): during
    a pull-down bounce iOS may hold a sticky box where it is while the band moves down, and the line would hang alone
    in the night; without scroll timelines (Safari before 26) there is no edge, and in dark the cap is night on
    night. Not on the four editors (their band stays put, `main` is the sheet) nor the five pages outside the shell.
    A save still keeps its place: `main.scrollTop` before and after a bedtime Save at 320 px and an Advanced switch's
    auto-save is equal in Chromium and WebKit.
  - Tests: `jstest/scroll-restore.test.js` (where a page came from and what keeps it; the back record over the
    fragment, with a query; every case that carries nothing), `jstest/focus-mode.test.js` (the fragment's mark),
    `shell::the_app_shell_scrolls_main_not_the_document` (`main`'s ground and column, the sheet's growth, the band's
    foot and z-index, the cap and its edge, the no-ring rule, no `border-image` left).
- **Cache**: `style.css?v=95`, `scroll-restore.js?v=8`, `focus-mode.js?v=3`.
- **Tests**: the two shell tests above; `the_app_shell_scrolls_main_not_the_document` pins the band's, the
  sheet's, `.container-fixed`'s and the flash's numbers and the form's `flow-root`;
  `the_tab_pages_open_on_their_large_title` reads each tab's band (mark, name, the bell last);
  `device_pages::about_this_phone_shows_the_last_reports_facts` and `avatars.rs` the phone's picture;
  `time_pages.rs` reads Save on the band and the flash box. Measured in Chromium at 320 and 390 px, light and dark,
  scripts on and off: design 84 §9. The iPhone walk is design 84 §8.

## Every phone's own colour (design 85 step 1, 2026-11-12, branch `design-85`, migration `0138_device_avatar_tone.sql`)

- **The user** (2026-11-11): "forskjellige farger generelt som avatar frem til man setter et bilde ville jo vært god
  skikk"; the seven colours and their order decided 2026-11-12 (design 85 §10, O2).
- **The palette**: `devices::AVATAR_TONES` - `tone-peach`, `tone-sky`, `tone-butter`, `tone-lilac`, `tone-sage`,
  `tone-teal`, `tone-rose`, in the order they are handed out. In `style.css` each class sets only `--avatar-bg`
  (`#F0A884`, `#9CC3EC`, `#EBD27F`, `#D7AEDB`, `#B5D098`, `#7FCBC8`, `#EFA3AF`), so `.avatar` keeps its one rule; the
  night letter is 9.7:1 or more on each, the same in both schemes.
- **Stored, not derived**: `devices.avatar_tone`, an index into the list. A new phone's own `INSERT`
  (`devices::insert_device_sql`, with `next_tone`'s scalar subquery) takes the first tone no phone has, past seven
  the one the fewest have (the lowest on a tie; a stored tone counts by the class it shows, so an out-of-range 9 holds
  butter) - one statement, no read before the write. A removed phone's tone is
  free for the next new one; nobody else's moves. The migration numbered the phones there by id: the oldest kept peach.
  `TestApp::create_device` inserts through the same SQL.
- **Where it shows**: `devices::avatar_class(device)` gives the tone's class for a set-up phone
  (`avatar_class_of(enrolled, tone)`, the tone taken `rem_euclid` the list's length, so a stored value out of range
  still maps to one), `avatar-grey` for one not set up - every page that used it changes with no template edit:
  Devices, the summary, the band's picture, Apps' "On phones" and an app's page, Schedules, Music, Locate, and the
  inbox (`notifications::ROW_COLUMNS` reads `d.avatar_tone`; a catalog event stays grey). Not changed: a contact's
  letter on Calls & Contacts (peach), the Apps tab's app tiles, Web Push (a picture or the app's icon). A picture
  covers the colour as before.
- **Tests**: `tests::avatars` - the tone handed out, freed and shared past seven, the migration, the class on every
  surface, a tone out of range, and `AVATAR_TONES`/`next_tone`/`style.css` agreeing with each colour's contrast
  against the letter computed.

## Locate's "Alle" view (design 85 step 2, 2026-11-12, branch `design-85-step2`)

- **The user** chose mockup B (2026-11-11); O1-O3 decided 2026-11-12: fresh is "Here now"'s rule (twice the served
  interval under Regularly, an hour otherwise), and Update All skips Off, not set up and Disconnected.
- **`/devices/locate`** with two phones or more and none chosen (or an unknown id) is the Alle view in place of the
  old list: `<section id="all">` holds the map box (`.map-box.map-all`, `clamp(260px, 45vh, 420px)` - 60vh/520 until 2026-11-12, when the age row and Update Location
  fell under the tab bar on an iPhone 15 -,
  `data-locate-all`), the chips over its top (`nav.map-chips`: "All" `aria-current`, then each phone a link to its
  view with its 26 px avatar - hollow, `.avatar-hollow`, without a position - and an `aria-label` carrying what its
  marker shows, "Emma, 2 min ago, within 12 m" / "Lily, location is off"; one light look in both schemes, one line
  scrolling inside itself), the legend (`.seen-dot.seen-fresh` `#1E7A35`, `.seen-old` `#C9C4BF`), "No position"
  (`data-group="no-position"`: each phone without one and why - not set up, location is off, no position yet),
  **Update All** and its wait, and the footer. One phone is still shown at once; none keeps the empty state. The
  chips, the rows and Update All work without JS.
- **`locate::Seen`** is the only place either view says fresh or old: the newest fix, the later of its own time and
  its still re-send (`resent_at`), `zones::fresh` under the phone's mode and served interval
  (`zones::freshness_inputs`). The one-phone view's age line gets the legend's dot and its hidden word, the re-send's
  age and "Hasn't moved since 11:40" (`data-fix-still`); its switcher gets "All" first with two phones or more.
- **`GET /devices/locate/phones.json`** (`locate::phones_json`, no-store: `admin_auth::no_store_path`'s arm
  `["devices", "locate", "phones.json"]`): a row per phone in the page's order - `id`, `name`, `initial`, `tone`,
  `photo`, `href`, `label`, `show` ("Show Emma") and with a position `lat`, `lng`, `accuracy_m`, `fresh`, `at`,
  `tag` ("now", "2 min", "3 h", "2 d") and `lines` (the bubble's: "Last seen …", "Within 60 m · At School", "Hasn't
  moved since …", "Last position before the phone went off"), else `none` (`not_set_up`, `off`, `no_fix`). With
  `?at=` a phone asked since then also has `answered`: its newest `locate` since `at` left Asking
  (`locate::answer_state`), never judged by fix times.
- **`POST /devices/locate/all`** (`locate::locate_all`, full auth, `settings_log::NOT_SAVES`): `queue_command`
  "locate" for each phone `skip_reason` lets through, each logged `device_command_queued`, then
  `/devices/locate?requested=all&at=<the press's second>#all`. The wait shows while `at` reads and is under 2 minutes
  old: "Asking 3 phones …" (`role="status"`), each phone not asked named with why ("Jack: not set up"), or "No phone
  can be asked" without a wait.
- **`static/locate-all.js`** (ES5, `?v=1`): markers as `L.divIcon`s of DOM nodes (avatar, ring, tag; old ones grey),
  accuracy circles (solid `--map-trail`, old dashed grey), joined into a group under 40 px (`groups`, regrouped on
  `zoomend`), a bubble per marker with a part per phone; `fitPlan` (one at 15, all at most 16, the far-old exception
  below zoom 11, fit-all without it); zoom and fit-all at the bottom right. A refresh every 60 s while visible and on
  becoming visible; after Update All every 5 s for a minute, then `history.replaceState` to `/devices/locate#all` and
  one fit unless the parent moved the map. Markers are kept by phone id and updated in place, so a refresh or a
  regroup never closes the open bubble or drops the focus. Every word comes from the server; text only.
- **Full screen** (§4.8, the user's decision of 2026-11-12): `static/map-full.js` (`?v=1`) on both map views - ⤢
  (`data-full-open`, bottom left) puts `.map-full` on the box (`data-map-full`: absolute in the body under
  `--top-strip`, as the tab bar is - never fixed -, z-index 3000 over the band and the tab bar) and `map-fullscreen` on
  the body (main stops scrolling); ✕ (`data-full-close`, 44 px, top right) and Escape close (Escape closes an open
  bubble first); Tab goes round inside the box; a window `resize` after each change makes Leaflet `invalidateSize` with the
  centre kept. The Alle view's legend shows as a strip over the map (`.map-legend-over`, `aria-hidden`).
- **Tests**: `locate_page::the_phone_switcher_and_the_alle_view`, `phones_json_tells_each_phone_fresh_or_old_by_its_own_setting`,
  `update_all_asks_every_phone_that_can_answer`, `the_wait_after_update_all_counts_the_answers`,
  `the_age_line_says_fresh_or_old`, `the_switcher_says_no_contact_in_the_readers_language`,
  `both_map_views_offer_full_screen`;
  `admin_auth::tests::no_store_paths`; `jstest/locate-all.test.js`, `jstest/map-full.test.js`.

## Locate's one-phone view: the Alle view's look (design 85 revision 3, 2026-11-12, branch `locate-one-look`)

- **The user** (on 0.75.0 with one phone, 2026-11-12): "ja selvsagt skal det se likt ut!" - one look with one phone
  or several.
- **No switcher.** With two phones or more the chosen phone's map has the Alle view's chips over its top
  (`nav.map-chips`: "All" to `/devices/locate`, then each phone with its avatar and label, the chosen one
  `aria-current="true"`, dark); with one phone no chips at all. `render_locate_page` reads every phone's Alle row
  (`all_phones`, no wait) for the chips and the marker; the online dot and `PhoneLink` are gone.
- **The marker** is the Alle view's: the box (`.map-box.map-one`, the Alle height) carries the phone's row as
  `data-marker-tone`/`-initial`/`-photo`/`-tag`/`-fresh`/`-label` (`locate::Marker`, from `AllPhone::marker`),
  `data-chips` while the chips are there; `static/locate.js` (`?v=8`) draws the avatar and tag, the accuracy circle
  (`accuracy_meters` of the newest point) and the popup's words in the white bubble on a tap (no longer opened by
  itself), the zoom at the bottom right, the fit 64 px clear of the chips. A fix newer than the one rendered is drawn
  fresh without a tag. The Locations page (no `data-marker-*`) keeps Leaflet's pin.
- **`static/map-marker.js`** (`HandyMapMarker`, `?v=1`, loaded before `locate-all.js` `?v=4` and `locate.js`): the
  avatar, the marker's node and tag (none for a row without one), its `divIcon`, the circle's style - shared by both
  views.
- **The legend** is under the map and the full screen's strip on the one-phone view too.
- **Tests**: `locate_page::the_chips_and_the_alle_view`, `the_chips_speak_the_readers_language`,
  `both_map_views_offer_full_screen`; `jstest/locate.test.js` (the avatar marker, its tag and circle, a newer fix).
