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
  The cost, accepted: an empty track on short pages there, the sign-in pages included (QA N2).
- **iPhone edges**: `head.html`'s viewport has `viewport-fit=cover`, without which every `env(safe-area-inset-*)` is 0;
  the insets then apply to `.container` (top, the sides as `max(16px, inset)`, bottom = 96 px + inset under the bar),
  the tab bar (sides, bottom `max(4px, inset + 6px)`), `.auth-page` (all four) and `.editor-bar` (sticky top, its side
  margins matching the container's). **dvh only after a vh fallback** (iOS 14, the floor, drops the `dvh` declaration):
  `.auth-page` is `min-height: 100vh; min-height: 100dvh` (`shell::every_dvh_follows_a_vh_fallback`).
- **`static/tab-bar.js`** (loaded deferred by `partials/tab_bar.html`; external, so a CSP needs no inline script;
  `jstest/tab-bar.test.js`): on `pageshow`, a `visibilitychange` to visible and - in an installed app
  (`display-mode: standalone`) - `resize`, at most once a frame, it hides and shows the fixed bar again so it is laid
  out against the current viewport; it never scrolls and moves nothing else. **[needs device check: resume a
  standalone install on the iPhone after > 1 min in the background, the bar visible]** - the iOS stale-viewport bug it
  targets is a hypothesis; delete the file if the check shows no effect. If the bar still misbehaves, the robust
  alternative is an app shell (content scrolls in its own box, the bar a normal flex child).
- **The Locate marker**: `L.Icon.Default.imagePath = '/static/leaflet/images/'` - Leaflet prepends it to its own file
  names, so an absolute `iconUrl` was prefixed twice (a 404, the alt text "Mark";
  `locate_page::the_map_marker_uses_leaflets_image_path`).

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
  reconnects: seconds after a clean stop, up to 5 min after a crash or power cut (no special case).
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
- **Devices list** (`devices_list.html`, `devices::list_row`): avatar + dot, name, "Online · seen 3 min ago" (or
  the lock part while a rule locks it), the screen-time bar, the badge, Add Phone. The sign-in warnings are short
  orange rows at the top (`auth::live_warning_row`/`login_warning_row`); the full sentences stay on Account. Tests:
  `display_time::tests`, `streams::tests`, `time_state::tests`, `attention::tests`, `src/tests/devices_list.rs`,
  `src/tests/attention.rs` (a row per fact).

## About This Phone and Advanced (design 31 step 3, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **Two sub-pages** (`src/handlers/device_pages.rs`, `device_about.html`, `device_advanced.html`), reached from two
  rows at the bottom of the device page ("About This Phone · Vibb One 0.23.6", "Advanced"). A rebuilt sub-page has the
  §3.0 header - a back link to the phone (`.nav-back`) and the large title - and includes `partials/tab_bar.html`
  alone, without the brand bar (gone since step 15).
- **About This Phone** (`GET /devices/{id}/about`, read-only): Connection (Status "Online since 08:12" from
  `connection_state`, Last contact, Last full report, Network from `limits::LastAccess`), Sound (`sound_mode::state`),
  Software (Vibb version, the music app's version from its versionCode, "Settings from server" = Applied or a
  `PolicyProblem` title, Battery optimisation), Diagnostics (crash reports, each a row whose ⓘ has the trace;
  "Starts without Vibb One" from `boot_witness::unseen`; the time lock). Times are local (`display_time`), the exact one
  in `title`. Rows are `device_pages::Row` (`partials/info_row.html`: a row with a sentence opens it as an ⓘ panel).
- **Advanced** (`GET /devices/{id}/advanced`): Restrictions (the 10 hardening switches as one form, split into
  groups for their footers, plus the Backup to Google row), App screens (`kiosk-block` + `PlayNote`s), Updates &
  notifications (`kiosk-escapes`, one form, + `EscapeNote`s and what the filter removed), Connection
  (`regenerate-code`, the QR link, Disconnect Phone… behind a `<details>` confirm), Remove Phone… (`delete`,
  `<details>` confirm). App screens, Updates and Connection only for an enrolled phone. Warnings sit above their
  group (`partials/note_warning.html`), states are rows (`partials/note_row.html`).
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
  before setup). Groups in the LockScreen mockup's order: Child PIN, Parent PIN (+ "Recent use"), Screen, Lock screen,
  Notifications on the lock screen.
- **Forms** (POST routes unchanged): the Child PIN and Parent PIN rows are `<details class="edit">` with the field
  and a Save button (a PIN is typed, not toggled); Remove Child PIN… is a `<details>` confirm (M6) posting
  `clear_kid_pin`; the Parent PIN's "Remove it instead" stays a checkbox in its form. "Turn off after" and
  "Background" are inline `select.pick`s, "Show message text" and each app a switch - all `data-autosave` with a
  `<noscript>` Save.
- **Where the forms return** (§5.1, pinned by `device_pages::lock_forms_return_to_the_page`): `kid-lock` →
  `/lock?notice=…#child-pin`, `parent-pin` → `/lock?notice=…#parent-pin` (the flash in the group); `screen-timeout`
  → `#screen`, `lock-wallpaper`, `lock-screen`, `lock-screen/apps` → their rows (`#lock-wallpaper`,
  `#lock-screen-text`, `#lock-screen-app-<package>`; scroll-restore keeps the exact place). Attention: no/easy Parent PIN → `/lock#parent-pin`, Child PIN set but lock off →
  `/lock#child-pin`; the summary's foreign-app step and Today's Parent PIN rows point there too.
- **Typed (M5):** `kid_lock::LockNote` (+ `Inactive`) replaces the card's `lines`/`warnings` and `inactive_warning`;
  `lock_glance::Note` the notes' sentences; `parent_pin::event_row` the PWA's event lines (`event_line`, `until_text`'s
  same-day form) and `parent_pin::notices` takes the zone. A sub-page warning (`device_pages::Note`) may carry a `fix`
  (an adb command) shown behind an ⓘ after its sentence (`partials/note_warning.html`), never in the visible text.
- **Fix round (qa-31-s4-code):** "On the phone · Locked · 10:59" carries the report's local time, so an old report
  never reads as now; `device_pages::load_policy` gives a 500 on a DB error (About, Advanced and this page), never the
  defaults' "Child PIN · Off"; `device_pages::has_capability` is the pages' one capability check; the device page asks
  `parent_pin::card` for the notices only (`with_events = false`), not the 10-event list.
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
  form) and the four Quick controls (`/policy` with `section=quick`). "Notification access off" sits above the first
  group (`device_pages::notification_access_note`, the adb command behind its ⓘ; the Attention row's fact and target
  `/home#layout`). "Clock format not applied" (design 34 P12, `device_pages::clock_format_note`) sits there too, only
  while 24 h or 12 h is chosen and the last report says `clock_format_applied` false. A failed wallpaper read is a
  500, never an empty grid (its Save would untick everything).
- **Web Filter** (`GET /devices/{id}/filter`, `device_pages::view_filter`, `device_filter.html`; row "Web Filter ·
  On/Off"): the Web filter switch (`/policy` with `section=filter`); Blocklists, one inline select each - Default (the
  list as everyone has it) / On / Off (`/dns/devices/{id}/blocklists/{list}/override`, `setting=default|on|off`; an
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
  icon, else the catalog's, else the first letter), the name, the state as a sub-line ("Installing 40%", "Not
  installed · No release yet", a refusal red) and a switch, its own form; an allowed app with a package has its
  `app_display_form` in a `<details>` under the row ("Name and icon: …"). The footer's ⓘ holds the keyboard and
  accessibility note. **Downloads**: "Download apps on Wi-Fi only" and the phone's network at its last report. No
  search field. While an app installs the page reloads every 3 s (`static/auto-reload.js`, pinned by
  `jstest/auto-reload.test.js`; Calls & Contacts uses it too): never once a save has started (a `submit`, a `change` on a `data-autosave`
  control, `pagehide` - a reload would cancel the POST), never while a Name and icon form is open, never on a refused
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
  `partials/tab_bar.html` alone), top to bottom: the **header** (the large avatar and dot, the name, "Online · seen 3
  min ago" with the exact time in `title`, "· changes may be delayed" while Away, "· changes wait until it's back"
  while Offline, "Disconnected · access removed" with "Connect again ›" to Advanced › Connection, "Not seen yet",
  "Not set up"; the lock badge only while a rule locks - `devices::badge_and_screen`, the Devices list's rules, so no
  end unless the phone reports Phone Setup's zone -; today's bar); **Attention** (`attention::items`, the list's count); the
  **foreign-app warning** with its steps (`#foreign-access`); **Quick Actions** (`#quick`: a red "Unlocked on the phone" first while the last report has the restrictions
  paused - what ends it, also for an older launcher -; +15 min, +30 min, +1 h,
  Pause a rule… as a `<details>` sheet with only the rules that apply, Lock again while a pause or Play install mode
  runs, Unlock for 2 h… behind a `<details>` confirm while no pause is on - both only for a `window_relock_v1`
  launcher -, Locate as a link to `/devices/locate?device={id}`; an open sheet spans the grid); **Today** (`#today`:
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
- **Typed (M5)**: `window_relock::{Blocks, BlockItem, BlockWhy, ForeignView, Findings, Answer, AnswerState, flash}`
  replace `blocks_note`, `more_blocks_line`, `foreign_note`, `relock_notes`, `answer_line` and `flash_text` (deleted
  with their unit tests); apps are named by the phone's label (`window_relock::app_names`), the package behind ⓘ;
  times in the Phone Setup zone, a phone time with its age. `parent_pin::when_text` and `ago` are gone;
  `parent_pin::notices` returns typed rows. The security log keeps its UTC lines.
- Tests: `src/tests/summary.rs` (the header states, the badge and bar, the quick actions, the redirect table, Today in
  local time, the not-set-up variant, the 500s), `src/tests/window_relock.rs`, repointed `command_expiry.rs`,
  `device_api.rs`, `device_pages.rs`, `devices_list.rs`, `enrollment.rs`, `hardening.rs`, `parent_pin.rs`,
  `time_pages.rs`, `time_rules.rs`; `window_relock::tests`, `parent_pin::tests`, `lifts::tests`, `attention::tests`.

## The Apps tab (design 31 step 11, `docs/design/31-pwa-redesign.md` §3.3)

- **`/apps`** (`tracked_apps::list_apps`, `apps_list.html`): **On phones** - a row per phone with "N allowed"
  (`allowed_count`: what the phone's Apps page switches on, the device page's Apps row's number too - reported apps on its allowlist, not Play's core, plus catalog
  apps chosen for it and not reported installed; the launcher not counted; "Not set up" before enrolment) -, then the
  **Catalog**: the icon tile (the catalog default's icon on its colour, else a letter), the name phones show with the
  admin name in the sub-line, "N phones" (`device_tracked_apps`; the launcher "all phones"), the version from the tag
  (`version_of`) or a badge instead (Off / Update failed - the in-memory last sync / No release yet), the launcher
  first; the footer and Add App. Phone rows link through `phone_apps_href` to the phone's Apps page
  (`/devices/{id}/apps`, at `#app-<package>` from an app page; step 6).
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
- **Add App** (`tracked_app_add.html`): name, source (two radios before the rows, so CSS - no script - hides the
  GitHub fields for an upload), repo; filter, tag prefix, package and pre-releases under More Options
  (`TrackedAppAddTemplate::more_open`).

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
  title, then with several phones and none chosen (or an unknown id) the phones as rows ("Online · seen 3 min ago" ›,
  as on Devices); one phone is shown at once. A chosen phone: the **phone switcher** (plain links to `?device=`, the
  state as a dot and in each `aria-label`, "Anna, online"; the chosen one `aria-current="true"` - the tab bar has the
  page's one `"page"`), the **map** (Leaflet, OSM tiles with design 33's origin-only Referer, unchanged) with the
  trail, the **last fix** as age and accuracy in the Phone Setup zone ("10 min ago", "Within 40 m", the exact time in
  `title`; the marker's popup says the same, built with `textContent`), the phone's **answer** to the newest Update
  Location (reworded, with when it came), the waiting line and its poll, **Update Location**; the two **warnings** as
  orange rows (location off, erasing not ready); **Ring, Stop Ringing, Lock** as three tiles, **Erase Phone…** as a
  `<details>` confirm with the type-the-name field; the footer with Lock's note behind ⓘ; **Recent** (the 10 newest:
  "Lock" · "10:58 · Done", the line under it, **Cancel** while it waits); **Location Settings** (When to locate +
  Every as one form - `location-policy` needs the mode on every post -, Keep history for, both auto-saving).
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

## The rest of Settings (design 31 step 14, `docs/design/31-pwa-redesign.md` §3.6)

- **Sub-pages of Settings** (the sub-page header: "‹ Settings" and the large title, `partials/tab_bar.html` alone):
  Web Filter (`/dns`), Blocked Activity (`/dns/log`), Wallpapers, Phone Setup (`/settings/provisioning`), Account &
  Password (`/account`), Security Log (`/security`), Backups, Software Updates (`/updates`). No POST route changed.
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
  `/account?changed=password#password`, `/backups?done=backup|deleted|gone|schedule|upload|restore|format#<group>`
  (`backups::Done`), `/updates?done=update|restart|os_check|os_upgrade|tailscale|reboot|app_schedule|os_schedule#<group>`
  (`updates::Done`, `updates::request_action` writes the root helper's request file); the GET shows the line in that
  group (`settings::Flash` + `partials/group_flash.html`). Only refusals render in the POST's answer, posting to their
  group (`#password`, `#two-factor`, `#backups`, `#upload`, `#schedule`, `#drive`, `#app`, `#os`, `#tailscale`,
  `#app-schedule`, `#os-schedule`): a password or typed confirm never goes into a URL; a refused Restore… or Format
  renders with that confirm open and the reason under the typed field. `check_forms` asserts each fragment is the
  form's own group. A refused Web Filter add keeps what was typed (`&typed=`, `&name=`, `&url=`).
- **Testable without the working directory or the network**: `AppState::data_dir` (`data/`, a temp dir in tests)
  holds the backups, the schedules and the root helper's request file; `ForkConfig::github_api` is a closed local port
  in tests, so Software Updates renders at once.
- **Confirms** (§5.6): Sign Out Everywhere…, Reset Two-Factor Login…, a photo's Delete Photo…, a backup's Restore… and
  Delete…, Format…, Update Now…, Restart Server…, Install Updates…, Restart Pi…. Unban, a domain's Remove, Check for
  Updates and Update Tailscale stay one tap.
- **Robust**: every page read is a 500 on a DB error, never an empty page (Web Filter, Blocked Activity, Phone Setup,
  Security Log); every Web Filter write too (it used to redirect as if saved); a password change and a 2FA reset move
  the hash or TOTP and the session epoch in one UPDATE (the first-sign-in change too), so another session is signed
  out exactly when the change is
  stored; Sign Out Everywhere answers 500 and keeps this session when the epoch can't move.
- **Texts (M5)**: the security log's event ids are labels (`admin::event_label`; every id the server writes has one,
  pinned by `admin::tests::every_written_event_has_a_label`, which reads the writers in `src/` - a new event needs its
  label there; an unknown id reads as its words), the row
  says who and from where, the time local (`Zone::when`, the exact one in `title`), the stored detail and the id behind
  the row's ⓘ; a ban's end with `Zone::until`; the live sign-in warning's lock end local (`auth::live_warning(admin,
  zone, now)`); a backup's time local. The schedule summaries are gone (the forms show the values); the update, backup
  and restore results are short `Flash`es; the installer command and the Docker commands sit behind ⓘ.
- Tests: `src/tests/settings_pages.rs` (the redirect table for Web Filter, Wallpapers, Phone Setup and the Security
  Log; Web Filter's refusals, rows and unreadable page; Blocked Activity's values; Wallpapers' confirm and refused
  upload; Phone Setup's refusal and flash; Account's field errors, confirms and local lock time; the Security Log's
  labels, escaping and local times; Backups' refusals in their groups), `tests::docker` (Software Updates on both
  kinds, its confirms and where each result shows), `admin::tests`, repointed `admin_auth.rs`, `intake_limits.rs`,
  `provisioning.rs`, `wallpapers.rs`, `dns_blocklist.rs`, `deploy_scripts.rs`.

## Add Phone, sign-in, the tab pages (design 31 step 15, `docs/design/31-pwa-redesign.md` §3.7)

- **Add Phone in 3 steps** on the pages that were there: **Name** (`/devices/new`, `device_add.html`; "Next" makes
  the phone and redirects to Connect; an empty or > 40-character name (`devices::MAX_DEVICE_NAME_CHARS`) renders the
  form again, 400, what was typed kept, the reason under the field; the name is trimmed), **Connect**
  (`/devices/{id}/provision`, `provision_qr.html`: two choices, "QR code · New or reset phone" (the Wi-Fi fields and
  Make QR Code, the GrapheneOS/adb detail behind the footer's ⓘ) and "Code · Phone already set up" (New Setup Code,
  the adb detail behind ⓘ)), then **the QR code** (rendered in the POST's answer inside `#qr`, the form posts there, so
  it lands on the QR with or without JS; its end local, `Zone::until`) or **the code** (`regenerate-code` →
  `enroll_code.html`, "Setup Code", shown once, `no-store`, its end and the typed-code pause local). Both end on
  **Done** → the phone's page for a phone not set up; for a connected phone both go back to Advanced › Connection and
  warn that a new code cuts it off. "Server setup needed" (no `LAUNCHER_SIGNATURE_CHECKSUM` - no QR form then -, no
  server address) is one red row with the env var / Phone Setup behind its ⓘ.
- **Refusals before any code**: a Wi-Fi name over 32 bytes, a password under 8 or over 63 characters (a 64-hex-digit raw key passes), or a password without a
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
  the title). `partials/app_header.html` is gone (the summary, step 10, has its back link); `shell::every_template_with_the_bar_sets_its_tab`
  asserts no template names it. For a phone not set up, Back from Connect and the code page goes to the summary's
  Connect This Phone group (`/devices/{id}#access`).
- Tests: `tests::provisioning` (the add flow's table from the rendered forms, a connected phone, the name and Wi-Fi
  refusals, "Server setup needed", 404s), `admin_auth::the_sign_in_pages_use_the_new_components`,
  `shell::the_tab_pages_open_on_their_large_title`, repointed `enrollment.rs`, `devices_list.rs`.
