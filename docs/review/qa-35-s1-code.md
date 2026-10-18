# QA 35 step 1 code review - events, the inbox, the `battery` wire field (2026-10-18)

Scope: `git diff f338ae88 50574052` (branch `notif-s1`, one commit, 37 files) against `docs/design/35-parent-notifications.md`
(§2 incl. §2.5, §3, §5.1, §8, §9 step 1 and its "Step 1 implementation status"), `docs/design/qa-35-design.md`, the root
and `server/CLAUDE.md`. QA wrote no product code; every mutation was reverted and the tree is clean.

**Verdict: mergeable after a short fix round.** No High or Medium finding. Each kind is raised once from its real input.
The battery latch follows §2.5. The words follow §2.2 and carry no phone-supplied text in a title. Migration 0078 matches
§3 in full (all four tables, the battery columns, the indexes, the switches' row) and is in the order test. Every save
on the new page lands on its group with and without JS (measured in the browser below). Every POST is behind full auth
and CSRF. The Kotlin field is never sent while null. Most findings are test gaps (the latch guard, the retention bound)
and one dedup key that isn't fully parallel-safe.

## Runs

| run | result |
|---|---|
| `cargo test --locked` (server) | 984 passed, 0 failed, 0 ignored |
| `cargo fmt --check` | clean |
| `cargo clippy --all-targets --locked` | the 13 known warnings, nothing new |
| launcher `testDebugUnitTest -PwarningsAsErrors=true` | BUILD SUCCESSFUL, 1293 tests, 0 failures |
| `node --test jstest/` | 34 passed |
| `time_status_report.json` (both copies) | byte-identical, untouched by the diff |
| `policy_json_keys_snapshot` | untouched |

## Findings

### Low

**L1 - The foreign-access dedup key isn't parallel-safe: two concurrent reports can raise it twice.**
- **Where:** `server/src/handlers/device_api.rs:1071` reads `previous_foreign`; `:1099` reads the previous row's `id`
  (`previous_status_id`). They are two separate queries, with several awaits between them and no transaction.
- **What breaks:** take two reports A and B from one phone that both carry a new set S.
  - B reads `previous_foreign` from row P (empty).
  - A inserts its row.
  - B then reads `previous` = A's row, so B's key is `A:<hash>` while A's is `P:<hash>`.
  - Result: two `foreign_access` events, plus a second `phone_foreign_access` security line, which already happened
    before this change.
- **The other kinds are safe:** their keys don't depend on which row was read (the call's instant, `count:last_at_ms`,
  `event_id`, the latch instant).
- **Likelihood:** low. The launcher serialises its syncs (`syncMutex`).
- **Fix:** read `foreign_access_json` (and `unseen_boots_count`) in the same `previous` SELECT, so the key and the
  comparison come from one row. That also saves two queries per report.

**L2 - Nothing tests the latch's `AND battery_low_since IS NULL` guard.**
- **Where:** `server/src/parent_events.rs` `battery_report`.
- **Mutation M3:** removing the guard passes every test. Sequentially, `Phone::load` already reads the latch, so the
  edge is `None` before the UPDATE runs. The guard only matters for two parallel low reports, and §2.5 / §1.2 rely on
  exactly that case.
- **Fix:** a test that loads two `Phone`s while the phone is armed, then calls `battery_report` with both (or with one
  after setting `battery_low_since` behind the first `Phone`'s back). Assert one event and that the first instant is
  kept.

**L3 - The retention test doesn't pin the 90 days; shortening it passes.**
- **Where:** `server/src/tests/notifications.rs` `events_go_with_their_phone_and_after_90_days`.
- **Mutation M9:** `EVENT_DAYS = 30` passes. The test ages one row to -91 days and leaves the rest at now.
- **Fix:** also age one row to -89 days and assert it is kept. Optionally do the same for push deliveries at -29 days.

**L4 - Back from an opened event shows the old unread state.**
- **Where:** `/notifications`, and the tab pages' bell.
- **Browser pass:** open a row, then press Back. Chromium's back-forward cache shows the inbox with its blue dot still
  on (3 dots; a reload shows 2). The same happens to the bell count on a tab page reached by Back.
- **Fix:** small and in-scheme. Either a `pageshow` handler in an existing deferred script that reloads when
  `event.persisted`, or `Cache-Control: no-store` on `/notifications` and the five tab pages (which takes them out of the
  bfcache).
- Not urgent: the parent sees the row become read at the next load.

### Nit

- **N1 - The battery line has no age.** `devices.rs` `time_glance` and `device_detail.html:30`. "Battery 13 %, charging"
  comes from the last report however old it is. The row already says "Offline · seen 3 days ago" beside it, so this is
  acceptable. A step-2/3 option: leave the line out when the report is older than the Away window.
- **N2 - A gone event drops the inbox's view.** `notifications.rs` `open`: an event that is gone, or has a non-local
  target, redirects to `/notifications#inbox` without `from`/`device`, so the back link becomes Devices. Carry `from`
  and `device` in the row form as the other forms do (or ignore it - the case is rare).
- **N3 - `Phone::at` decides by `when.contains(':')`.** `parent_events.rs` `Phone::at` guesses whether `Zone::when`
  already returned a clock time from its text. It works for every format today, but a `Zone` change would silently
  double or drop the time. The words table would catch it, so this is acceptable. A typed answer from `Zone` would be
  cleaner.
- **N4 - An old call can be announced after a report without `call_state`.** `new_emergency_call`: when the previous
  report had no `call_state`, or it was dropped by its cap, a months-old call in the next report counts as new, because
  the 24 h bound is applied only with no previous report. This is rare. Applying the bound also to `Some(None)` would
  close it.
- **N5 - No CHECK on the subscription state.** `push_subscriptions.state` has no `CHECK (state IN ('active','gone'))`.
  Add it only if 0078 hasn't been pushed anywhere yet: a migration is frozen from its first push.
- **N6 - CSRF is not tested per route (mutation M7).** Exempting `/notifications` from `csrf_guard` passes the suite,
  because the guard is one global layer tested in `admin_auth.rs`. This is by design, not a gap of this change.
  Confirmed by hand: a cross-site POST to `read-all`, `settings` and `{id}/open` with a valid session gets a 403.

## The spec checklist

- **Each kind exactly once:**
  - `raise` is `INSERT ... ON CONFLICT (device_id, kind, dedup_key) DO NOTHING RETURNING id` on `UNIQUE (device_id, kind,
    dedup_key)`. A repeat or a parallel raise with the same key is one row, which the SQL guarantees.
  - The repeat guards in front of it (newer call, count rose, set changed, `rows_affected() == 1`, the latch) mean the
    key is the last line of defence. Mutation M12, which raises PIN events for repeats too, is absorbed by it.
  - The keys follow §2.1. The one weakness is L1.
- **The battery:**
  - `battery_edge` is exactly §2.5's rule.
  - The Raise path is a conditional UPDATE and the raise in one transaction, raising only on `rows_affected() == 1`.
  - Re-arm happens only on `!low && !charging`.
  - `lenient_battery` drops a missing, null or non-object `battery`, a non-integer or out-of-range level, or missing
    booleans, and stores the report anyway. The test posts level 150 and level "x".
  - A report without `battery` touches neither the latch nor the columns.
- **Words:**
  - The titles are §2.2's, cut to 60 characters with "…" on the name only.
  - The summaries use typed values only.
  - The foreign apps' names (the phone's labels) go only into `detail`, which is inbox-only and escaped by Askama. The
    test renders "Ola <b>" escaped.
  - No event text reaches a URL or a log line. `raise` logs only a bad target's path. Grepping the server log after
    the browser seeding finds no title.
- **Switches:** stored always. `save_settings` writes every column named by `Kind::switch()`, with a missing one off. An
  event of a kind switched off is still stored (tested).
- **Inbox:**
  - Rows come newest first by id, 100 to a page plus "Show older" and "Newest".
  - Unread is bold with a dot and "Unread:" for screen readers.
  - The row form marks the event read and 303s to its target. A non-local target or a gone event goes back to `#inbox`
    (tested).
  - Mark All honours `device`.
  - The picker is a GET with `data-autosave` and a `<noscript>` Show.
  - Every target is checked: calls, `lock#parent-pin`, `#foreign-access`, `about#diagnostics`, the summary.
- **Bell:** on all five tab pages, server-rendered, "99+", `data-unread`, before "+" (Devices, Apps) and alone on
  Locate, Music (no entries, so no Edit) and Settings. In the browser at 390 px: bell x=293, "+" x=341, no overlap, the
  title at y=56 on every page.
- **Settings hub row:** "Notifications · 20 unread", in its own group at the top.
- **Retention:** `retention::prune`, at startup and hourly: events after 90 days by `created_at`, deliveries after 30
  days, `gone` subscriptions after 30 days by `gone_at`. Deleting a phone cascades its events (tested).
- **Migration 0078:** compared column by column with §3. Complete, and in `migrations_73_to_78_in_order`.
- **No jump on save:** forms post to `#inbox` and `#events`, and the redirects carry `from` and `device`. `check_forms`
  and `assert_lands` cover them; the browser numbers are below.
- **Security:**
  - The four routes are inside `require_full_auth` (a test checks `open` without a session goes to `/login`).
  - The global `csrf_guard` covers them (403 cross-site, checked by hand).
  - The page has no `<script>` (the admin CSP's phase 1 is unchanged).
  - Redirect URLs are built only from the `back_of` whitelist and an `i64`.
- **Kotlin:** `battery: BatteryReport? = null` under `ServerJson`, whose `encodeDefaults` is off, so it is left out while
  null. Mutation M13 (`@EncodeDefault`) fails `TimeStateZoneTest`.

## The implementer's deviations and open points

- **No `urgent()/ttl()/urgency()` and no reserved variants yet:** agreed. Nothing reads them in step 1. Step 2 must add
  them with the sender and extend the words table into §2.2's full table (the urgent column).
- **No transaction around the intake's raises:** agreed, with L1. For the emergency call, the start without Vibb One and
  low battery, the key alone makes repeats and parallel reports one row. For foreign access it does so only once both
  reads come from one row (L1's fix). A transaction around the whole intake isn't needed. `raise` taking
  `&mut SqliteConnection` is the right seam for step 2's delivery rows.
- **Wording additions** (other days, a lockout already over, the apps' kinds, the places of use, the cut name): agreed.
  They are all typed and pinned by `other_days_long_names_and_variants`.
- **The foreign apps named by the same report's app list:** agreed (inbox only).
- **The phone picker only with 2+ phones; Mark All only with something unread; the hub's "N unread" only above 0, in its
  own group:** agreed. A one-phone picker would be noise.
- **The forms carry `from` and `device`:** agreed. It keeps the back link and the filter across every save; N2 is the
  one path that doesn't.
- **`{% let tab = "" %}` on the inbox:** agreed. §5.1 says no tab is current, and `shell::every_template_with_the_bar_sets_its_tab`
  allows `""` for this template only. The browser shows no `aria-current="page"`.
- **The switches change nothing visible until step 2:** agreed. The footer already says "Off: still listed here, no
  notification".
- **Open for step 2** (the delivery line, This Device, Getting Notifications, the warnings, the push writers, `Kind`'s
  push properties, `push.wake()` after the raise): correctly left out. `log_foreign_access` already takes `&AppState`,
  so `wake()` is a one-line addition.

## Browser pass (Chromium via Playwright, 390 x 844 at 2x)

The throwaway server ran the debug build with `ADMIN_PASSWORD`, `BIND_ADDR=127.0.0.1:3197` and `INSECURE_COOKIES`. The
setup was real:
- the first sign-in, the forced password change and 2FA (TOTP computed from the shown key);
- two phones added and enrolled through New Setup Code and `/api/devices/enroll`;
- events raised only through the device API: status reports with `battery` low, an emergency call, unseen boots and a
  foreign app, plus `pin-events`, and 14 battery re-arm/low cycles to make the inbox long.

Screenshots are in
`/tmp/claude-1000/-workspace-handy/430a895f-506c-4fe9-bfff-b4b9a36a016b/scratchpad/qa-notif-s1/shots/`.

| check | result | screenshots |
|---|---|---|
| Bell and count on the 5 tab pages, light and dark | Bell 44x44 before "+" (Devices, Apps; x 293 vs 341), alone at x 330 on Locate, Music and Settings. Red "20" pill on the bell's corner, aria-label "Notifications, 20 unread". No horizontal scroll (scrollWidth 390). | `{light,dark}-{devices,apps,locate,music,settings}-top.png` |
| Battery line | Devices rows "Battery 13 %, charging" / "Battery 15 %"; the summary header the same | `light-devices-top.png`, `dark-summary-header.png` |
| Settings hub row | "Notifications · 20 unread" (later "2 unread"), red bell tile, first group | `light-settings-hub.png` |
| Inbox | 20 rows, newest first, 20 dots, back link to the `from` tab, no current tab, rows 66 px with avatar, title, summary and relative time | `{light,dark}-inbox-top.png`, `-inbox-full.png`, `-inbox-bottom.png`, `-inbox-anna.png` |
| Switch save, JS on | scrollY 1399 → 1399, the switch at 590 px on screen before and after; URL `...#events` | `light-switch-after-save.png` |
| Picker, JS on (autosave GET) | Position unchanged; URL `?from=devices&device=2#inbox`, 17 rows | - |
| Mark All, JS on | scrollY 988 → 988; dots 0, the button gone | `light-after-mark-all.png` |
| Open a row | Went to `/devices/1` (the low-battery target); Back shows the stale dot (L4) | - |
| JS off | The `<noscript>` Save is visible and lands on `#events` (at the page's maximum scroll; `#inbox` lands at top 0). The `<noscript>` Show resets the filter. | `light-nojs-after-save.png`, `light-nojs-inbox.png` |
| Console | No errors on any page, light or dark | - |

## Mutations

Each mutation was applied in turn and the targeted suites were run (`parent_events notifications cleanup admin_auth
settings_pages shell`; the Kotlin one with `TimeStateZoneTest`). All were reverted.

| # | mutation | result |
|---|---|---|
| M1 | dedup key made unique per raise (nanos appended) | caught: `the_words_and_targets` |
| M2 | `ON CONFLICT ... DO NOTHING` dropped from `raise` | caught: `a_target_off_this_server_is_refused` |
| M3 | latch UPDATE without `AND battery_low_since IS NULL` | **not caught** (L2) |
| M4 | re-arm on `!low` alone | caught: `the_battery_edge`, `the_battery_latches_once` |
| M5 | a report without `battery` raises low battery | caught: 6 tests incl. `the_battery_latches_once` |
| M6 | the foreign-access title names the phone's apps | caught: `the_words_and_targets`, `each_kind_is_raised_once_from_its_real_input` |
| M7 | `csrf_guard` skips `/notifications` | **not caught** (N6, a global guard by design) |
| M8 | Mark All redirects to `#events` | caught: `the_inbox_lists_opens_and_marks_read` |
| M9 | event retention 90 → 30 days | **not caught** (L3) |
| M10 | `open` follows any target | caught: `the_inbox_lists_opens_and_marks_read` |
| M11 | a first report announces a call of any age | caught: `an_emergency_call_is_new_only_once`, `an_old_emergency_call_is_not_announced` |
| M12 | PIN events raised for repeats too (outside the `rows_affected() == 1` branch) | equivalent: the dedup key absorbs it (same `event_id`) |
| M13 | Kotlin `@EncodeDefault` on `battery` (sent as `null`) | caught: `TimeStateZoneTest` (2 tests) |

## Fix round, in short

1. L1: one `previous` SELECT holding `foreign_access_json` and the id.
2. L2: a test that latches twice from two loaded `Phone`s.
3. L3: a -89-day row kept.
4. L4: optional, reload on a persisted `pageshow`, or `no-store` on the bell pages.
5. The nits as wanted.
