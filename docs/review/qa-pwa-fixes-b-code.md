# Code QA: pwa-fixes-b (shorter push texts, app update notices, pull down to refresh)

Branch `pwa-fixes-b` (72e7b4fa; 5cbe5500, 3da522c7, 6c411915, 72e7b4fa) against `bd43c97b`. The reviewer's branch is
`qa-pwa-fixes-b`, which is `pwa-fixes-b` plus this file. Server only. Asks: PLAN's three queue lines (the user,
2026-10-25: "Generelt kunne vi også forenklet web push meldingene? Mindre tekst"; "sende notifications når apper lagt
til via pwa blir oppdatert? ... kanskje man kan få valget"; "update gestures for devices taben? Om man drar ned så
refresher den ... Kunne også vært når man er inne på en device"). Notes: `docs/notes/server/notifications.md`, "Push
wording (2026-10-25)" and "App update notices"; `docs/notes/server/pwa.md`, `handyReconnect` and "Pull down to refresh".

## Verdict

**Ready to merge after Q-1 and Q-2. Q-3 can wait.** I found no High findings. There is one Medium (Q-1): a catalog
app whose source goes back to an older release raises "update available" for that older release. The rest are test
gaps and notes. Migration 0099 kept every row, id, counter, index, foreign key and cascade, both in the branch's test
and in a separate check with 300 events, 400 deliveries and two parents' reads. The push words match the note's table
row for row, and the inbox words are unchanged. Pull to refresh works in Chromium and WebKit on both pages and is
refused when it should be.

## Suites

- `cargo test` (own target dir, under the build lock): **test result: ok. 1320 passed; 0 failed; 0 ignored; 0
  measured; 0 filtered out; finished in 460.16s**.
- `cargo fmt --check`: clean. `cargo clippy --all-targets`: 13 warnings, the known set (dead model fields, the
  `dns_engine` doc lists, `chunks_exact`, `sort_by_key`). None are new.
- `node --test jstest/*.test.js`: **163 tests, 163 pass, 0 fail**.

## Findings

### Q-1 (Medium): an older release stored over a newer one raises "update available"

`server/src/handlers/tracked_apps.rs:1403-1420`, `update_available_event`. The only check is that a previous tag
exists. The function never checks that the new release is newer. A source can go back in at least three ways:

- "Include prereleases" turned off after an RC was stored.
- A release deleted on GitHub.
- An F-Droid repo that drops its newest build.

In each case `sync_release` stores the older release, and the inbox then says "Vibb One 0.35.0 is available · 0.36.0-rc.7 →
0.35.0". If "App updates" is on, it also pushes "Vibb One: update available / version 0.35.0". This breaks the brief's
rule for `app_updated` ("a downgrade or a rollback must not fire") for the catalog event too. The key does stop a
repeat: when the newer release comes back, its key already exists.

Fix: raise only for a release that is ahead.
- F-Droid: `asset.id` is the versionCode, so require it to be greater than `app.latest_release_asset_id`.
- GitHub: compare `version_of(old)` and `version_of(new)` with `handlers::system_update::is_newer`. Keep the
  rolling-tag case (same version, new asset id) as it is.
- Add a test: store `v1.0.57`, then sync `v1.0.56`, and expect no notice.

### Q-2 (Low): two `app_updated` guards have no test

`server/src/tests/app_updates.rs:141`. Mutations M9 and M18 survived:

- **M9**: change `is <= was` to `is == was` (`tracked_apps.rs:1494`), which lets a downgrade fire. No test fails.
- **M18**: treat a missing previous music code as 0 (`tracked_apps.rs:1491`). A phone's first report with the music
  app then fires "Musikk updated". No test fails.

The code is right today, but nothing pins it.

Fix: add two cases to `a_report_with_the_new_release_raises_app_updated_once`:
- A phone on `0.37.0-rc.1` (370001) that goes back to the stored release `0.36.0` (360009) raises nothing.
- A phone whose previous report had no `music_state` and whose next one has 60009 raises nothing for the music app.

### Q-3 (Low): the summary push's body has no test

`server/src/push.rs:398`, `server/src/tests/web_push.rs:772-776`. M10 put back the old "More than 3 at once - open
Notifications to see them" and no test failed. The burst test checks only the summary's title and tag. The same gap
existed before this branch, but the new short body is part of the user's wording table.

Fix: in `switches_the_emergency_call_and_a_burst`, assert that the summary's `notification.body` equals
`push::SUMMARY_BODY`, and that `SUMMARY_BODY == "More in the inbox"`. The `What::Test` body is already pinned.

### Q-4 (Low, merge order): 0097 belongs to `last-position`

`server/migrations/0098_push_words.sql`, `0099_app_update_notices.sql`. The numbering is fine if `last-position`
(0097, in QA) merges first. If this branch merges first, `last-position` **must** renumber its file to 0100. Two
reasons:

- sqlx applies a missing lower version after higher ones without complaint.
- The backup taken before migrating compares only the highest version (`migrations_73_to_99_in_order`'s message). A
  late 0097 would therefore run with no backup in front of it.

The docs already say "whichever merges second renumbers its own". The coordinator should apply that rule.

### Q-5 (Info): 0098 has no backfill, and needs none

0098 only adds `push_title`/`push_body`, so existing rows are NULL. `parent_events::pushed` falls back to the inbox
words for them. The only rows that are still pushed are deliveries queued or retrying when the server is upgraded,
and those last at most 12 h (TTL). Such a row is pushed once with the long words, which is a sensible push.
Recomputing short words for old rows would need the phone, zone and battery context of that moment. A row whose
`push_title` is set and whose `push_body` is NULL cannot happen, because `push_body` always holds at least the time.

### Q-6 (Info): a re-uploaded asset is a second notice

A new asset id under the same non-rolling tag (a maintainer re-uploads the APK) is a new key. It raises a second
"Signal 7.2.0 is available / 14:02" without a "from". This is the documented rolling-tag behaviour applied to any tag.
It is acceptable because it is a new build.

### Q-7 (Info): a removed app's notices still point to its page

Removing a catalog app leaves its `app_update_available` and `app_updated` rows until retention, with target
`/apps/tracked/{id}`. A tap opens a page for an app that no longer exists. This is rare and harmless. It could be
handled together with Q-1, or left as it is.

### Q-8 (Info): touch cancelability on real devices is owed by hand

In Chromium with CDP touches, every move after the 10 px slop was `cancelable` and was prevented (`cP`). In WebKit,
synthetic events were prevented after the slop. On a real Android Chrome or iPhone, the first moves (up to 10 px) are
not prevented, so the browser may already have started its overscroll. Later `preventDefault` calls may then be
ignored. The code guards with `e.cancelable`, so there is no console noise, but the stretch or rubber band may show
under the arrow. The note already lists the iPhone and Android PWA as owed.

### Q-9 (Info): one surviving mutation changes nothing

M17 removed the `scrollTop` check at `touchstart` (`pull-refresh.js:117`) and survived. The `touchmove` check before a
pull begins (`pull-refresh.js:137`) still refuses a page that isn't at the top, so behaviour is the same. The start's
check is a cheap belt and can stay.

### Q-10 (Info): commit trailers

All four commits on the branch carry `Co-Authored-By: Claude Opus 5.5`. The coordinator rewrites them before the
merge. This file's commit uses the reviewer's trailer.

## What was checked and holds

### Push wording

- Every stored-words kind sets `push_title`/`push_body` through `Phone::pushed`: the emergency call, both PIN
  kinds, foreign access, unseen boot, low battery, all four `phone_off` cases and both `phone_on` cases.
- The parameter kinds (zones, extra time, the two app kinds) are worded short in `told_as(Form::Push, ..)`.
- The inbox and Activity call `told`, which is `Form::Inbox`, so the long words are unchanged.
  `the_push_words_are_short` asserts this for every row (`assert_ne!(inbox, t)`).
- `raise` clips the stored push words to `TITLE_MAX` (60) and `SUMMARY_MAX` (100). The parameter titles are clipped
  in `told_as`. A long name keeps the fact ("…: emergency call").
- No coordinates leave in a push. The PIN's place, the rule, "probably flat", "no shutdown seen" and the last report
  are gone from push bodies, as the table says. Phone, zone and app names stay, as before.
- The tests that pin texts (`power`, `web_push`, `zones`, `time_request`) were updated to the new words, not
  loosened. The one relaxed assertion (`web_push`'s body is now `ends_with(" · 1 app")`, because it carries the
  clock) is pinned exactly in `the_push_words_are_short`. The test push's title and body are pinned.

### App update notices

- `app_update_available` is raised only after `sync_release`'s UPDATE stored a release. `sync_release` returns early
  for the same tag and asset id, so a repeat sweep or Check Now never reaches the raise. A refused or failed
  download doesn't either.
- The sweep and Check Now share `sync_one_app` → `sync_release`.
- The first release raises nothing: `latest_release_tag` is NULL.
- A manual upload is left out, and the note says so ("never a manual upload").
- The key is `<app id>:<tag>@<asset id>`, and the partial unique index plus `ON CONFLICT DO NOTHING` (no target)
  hold it.
- `app_updated` is checked on every status report that has a previous report:
  - The code must be higher than before, and the release must be the stored one (`release_installed`: the
    launcher's code through `launcher_tag_version_code`, which reads old and new tag rules; the music app's through
    `version_of_code`).
  - The key is `<app id>:<code>`.
  - A missing previous code skips.
  - "Only Vibb One and the music app" is documented in the note, PLAN and code.
- The switch:
  - `Kind::switch_default` drives `push::queue`'s `COALESCE(p.col, 0)` and Notify Me About's no-row defaults.
  - 0099's `DEFAULT 0` covers existing rows, which my migration check confirmed (`push_app_updates = 0` for the
    existing row, `push_pin_used` kept at 0).
  - A new parent's row, if one is created, gets the column default of 0. Saving the form writes every switch.
- NULL `device_id` in every query that reads `parent_events`:
  - Inbox (LEFT JOIN, `?device=` filter excludes it, `unread_after`'s `SUM` ignores NULL).
  - Activity (`device_id = ?`).
  - Mark all read (with a phone: excluded, matching the filtered list; without: included).
  - `unread_count`/the bell and the push badge (included).
  - `push::queue` (no device join), `send_due` (LEFT JOIN on the event), `delivery_lines` (by event id).
  - Retention: the zone prune is by kind, and the 90-day prune covers catalog rows.
  - `devices.rs`' off-since query and `device_api`'s per-phone queries (`device_id = ?`).
  - Device delete: cascades phone rows only.

### Migration 0099

- The branch's `migration_0099_keeps_the_events_their_deliveries_and_reads` fills rows at 0098, applies 0099 in a
  transaction with foreign keys on, and checks the result.
- I also ran my own check (`scratchpad/qab/mig0099.py`, Python sqlite 3.46, foreign keys on, one transaction). It
  filled 300 events and pruned the top 5, filled 400 deliveries (some with a NULL event) and pruned the top 5, and
  filled the reads of two parents. Results:
  - All rows of the three tables are identical before and after, every column, ids kept.
  - The column lists are identical except `device_id`'s NOT NULL.
  - The index set is identical plus `idx_parent_events_catalog`.
  - Foreign keys are identical (`devices` CASCADE, `push_subscriptions` CASCADE, `parent_events` SET NULL /
    CASCADE, `admin_users` CASCADE). `WITHOUT ROWID` and the autoindexes are kept.
  - No `old_*` table or `sqlite_sequence` row is left. `foreign_key_check` is empty and `integrity_check` says ok.
  - The next ids are 301 and 401, so pruned ids are never reused.
  - Duplicate catalog keys are refused, the same key under another kind is accepted, and a phone's duplicate key is
    refused.
  - Every cascade still works: event → reads and deliveries SET NULL, device → events, parent → reads,
    subscription → deliveries.
- No trigger or view references the three tables.

### Pull to refresh

- The script loads only on the two templates (shell test, M6 caught), and no Leaflet page loads it.
- It goes through `handyReconnect.reload()` → `go(start("load", ..))`, with the place record and `replaceState` GET.
- `mayReload` is asked at the start and again at the release (M11 caught). Its rules cover a save, an open
  `<details>`, and typed or picked values (M7 caught).
- `passive: false` only on `touchmove`, and nothing is prevented before 10 px (M15 caught).
- The arrow is `aria-hidden`, `pointer-events: none`, and has no focusable element (`focusable="false"` on the
  svg). It has no live region, and the reconnect bar does the announcing.
- Reduced motion is honoured. The arrow is absolute in the body (design 43).
- No PWA save can jump: the page is at its top when a pull starts.

### Docs and PLAN

The note tables match the code. CLAUDE.md's migration paragraph and the `parent_events`/`notification_prefs`
bullets are current. PLAN's queue lines say "built, awaiting code QA", and its entries list what is owed by hand.

## `routes_need_a_session` (seen failing once by the implementer)

There are two tests with this name, `music_storytel` and `music_spotify`. Neither starts a background task or shares
global state (TestApp spawns nothing, and the storytel fake counts only its own requests). Results of repeated runs:

- Alone: **0 of 40 runs failed** (both tests).
- With the whole `music_spotify` and `music_storytel` modules running in parallel (84 tests per run): **0 of 8 runs
  failed**.
- The full suite, once: passed.

The likely cause is `music_spotify::routes_need_a_session`, which reads `templates/partials/head.html` **from disk at
run time** and compares its `style.css?v=` line with the page compiled into the binary (askama). This branch bumped
`style.css?v=52` to `53`. A test binary built before that edit, or an edit while a run was going, fails this test
once and passes after a rebuild. That points to a stale build, not a race in the code.

## Browser pass

- Setup: the branch's debug server on 127.0.0.1:3170 with a fresh data dir (`scratchpad/qab/srv`), the admin, 2FA
  and two phones created over HTTP (`scratchpad/qab/setup.py`). Playwright (`scratchpad/qab/pull_pages.py`), phone
  viewport 390×844 with touch.
  - Chromium: real touch events over CDP (`Input.dispatchTouchEvent`).
  - WebKit: synthetic touch events on the document (WebKit has no CDP).
- Results, identical in **Chromium and WebKit**, on `/devices` and `/devices/1`:

  | Check | Result |
  |---|---|
  | Pull of 100 px | The arrow showed `is-pulling is-armed`, `translateY(40px)`, opacity 1. The release reloaded the page |
  | Pull of 40 px | `is-pulling` at 15 px, opacity 0.75. No reload |
  | `main` scrolled to 60 (`/devices/1`) | Nothing shown, no reload |
  | `/devices` | Not scrollable with two phones, so the scrolled case could not apply there |
  | An open `<details>` | Nothing shown, no reload |
  | Typed text in a field | Nothing shown, no reload |
  | Second pull after a reload | Reloads again. `main.scrollTop` is 0 and no arrow is left over |
  | `/devices/locate` | `pull-refresh.js` not loaded |
  | Console | No errors or warnings, no 4xx/5xx |
  | Server log | Clean |

- Screenshots are in `scratchpad/qab/pull-*.png`. The armed arrow sits half over the dark title bar, centred, calm.
- The server was stopped afterwards.

## Mutations

Each break was applied by exact replace (`scratchpad/qab/mutate.py`), the named tests were run, and the file was put
back byte for byte. For migration breaks, `src/main.rs` was touched so `sqlx::migrate!` re-embedded the files.
`git status` was empty afterwards.

| # | Break | Result |
|---|---|---|
| M1 | "App updates" on by default (`switch_default` always true) | caught (`app_updates_are_pushed_only_with_the_switch_on`) |
| M2 | 0099 `push_app_updates DEFAULT 1` | caught (`migration_0099_…`) |
| M3 | an event per sweep (key carries the time) | caught (`a_release_is_one_event_whatever_the_sweeps`, `an_app_update_is_told_…`) |
| M4 | the first release raises | caught (`a_new_release_is_one_notice_never_one_per_sweep`) |
| M5 | a parameter kind pushed in its inbox words | caught (`zones`) |
| M5b | the sender ignores the stored push words | caught (`power`) |
| M6 | `pull-refresh.js` on the Locate page | caught (`pull_down_to_refresh_is_on_devices_and_the_summary_only`) |
| M7 | `handyReconnect.mayReload` ignores typed text | caught (`reconnect.test.js`) |
| M8 | 0099 drops the reads | caught (`migration_0099_…`) |
| M9 | `app_updated` on a downgrade | **NOT CAUGHT** (Q-2) |
| M10 | the summary push's long body back | **NOT CAUGHT** (Q-3) |
| M11 | no `mayReload` at the release | caught (`pull-refresh.test.js`) |
| M12 | the inbox's inner JOIN (catalog rows vanish) | caught (`app_updates`) |
| M13 | 0099 without the catalog's partial unique index | caught (`a_release_is_one_event_…`) |
| M14 | 0099 without the `sqlite_sequence` carry | caught (`migration_0099_…`: next id 9, not 10) |
| M15 | `preventDefault` from the first move | caught (`pull-refresh.test.js`) |
| M16 | Notify Me About all on for a parent with no row | caught (`app_updates_are_pushed_…`) |
| M17 | no `scrollTop` check at `touchstart` | not caught - equivalent (Q-9) |
| M18 | `app_updated` on a first music report (missing code = 0) | **NOT CAUGHT** (Q-2) |

**Score: 15 of 19 caught, or 15 of 18 counting only mutations that change behaviour.** All three survivors are the
test gaps in Q-2 and Q-3.

## Fix round (2026-10-25, the implementer)

- **Q-1: fixed in 446b85c4.** `tracked_apps::release_ahead` gates `update_available_event`. F-Droid compares
  versionCodes (the asset id): only a higher one counts. GitHub compares the versions when both read as
  X.Y.Z[-rc.N] (`install_now::version_order`, now `pub(crate)`; an RC sorts before its release). Two other rules:
  - The same version under a newer asset id is a rolling tag's new build, as before.
  - Names that aren't versions fall back to the asset id, since GitHub hands those out in upload order.

  `system_update::is_newer` isn't used. It reads only plain X.Y.Z, so the launcher's RC-to-release step (0.36.0-rc.7 →
  0.36.0) would never raise a notice. Two new tests:
  - `an_older_release_stored_over_a_newer_one_is_no_notice`: a real F-Droid sync stores 1.0.57 over a cached 1.0.58
    and raises nothing.
  - `only_a_release_ahead_is_an_update` covers prereleases turned off after an RC, a deleted newest release, a
    re-upload of an old version under a newer asset, a rolling tag, non-version names and F-Droid by versionCode.
- **Q-2: fixed in 446b85c4.** `a_report_with_the_new_release_raises_app_updated_once` adds a phone on 0.37.0-rc.1
  (370001) going back to the stored 0.36.0 (360009), and a report naming the music app after one that didn't. M9
  (`is == was`) and M18 (a missing music code read as 0) were reapplied, and each now fails this test.
- **Q-3: fixed in 446b85c4.** `switches_the_emergency_call_and_a_burst` asserts that `push::SUMMARY_BODY` is "More in
  the inbox" and that the summary push's body equals it, so M10 now fails it.
- **Q-4**: as instructed, 0098/0099 stay and `last-position` (0097) merges first.
- **Q-5 to Q-9**: information only, nothing changed.
- **Q-10 (trailers)**: not rewritten here. See the implementer's report for why.
- Suites after the round:
  - `cargo test`: 1322 passed, 0 failed.
  - `cargo fmt --check`: clean.
  - `cargo clippy --all-targets`: the known 13 warnings, none new.
  - `node --test jstest/*.test.js`: 163/163.
