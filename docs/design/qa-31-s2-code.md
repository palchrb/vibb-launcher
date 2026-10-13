# QA 31 step 2 (code) - display helpers, connection state, Attention, the Devices list

Reviewer: code QA, 2026-10-13. Branch `design-31-s2`, `60872f24..e5316ec2` (one commit, 21 files, server only).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.2b, §3.0, §3.2, §3.8, §3.10, §5.4 and §5.7;
- §8 step 2 and the "Step 2 implementation status" with its 7 choices;
- `qa-31-design.md` (L2, M4, M5) and `qa-31-s1-code.md`;
- the Main, HeaderStates and DeviceSummary mockups.

I also read the code the step touches or leans on:
- `display_time.rs`, `time_state.rs`, `attention.rs` and the `streams.rs`, `kid_lock.rs`, `calls.rs`, `devices.rs` and
  `settings_hub.rs` diffs;
- `devices_list.html`, the `device_detail.html` diff and the CSS diff;
- `tests/devices_list.rs`;
- for the inputs, `window_relock::card`, `boot_witness::warning`, `kid_lock::lock_card`, `time_rules` (the day
  windows and `legacy_to_rules`), and `device_api.rs` (where `reported_at`/`last_seen_at` are written);
- in the launcher, `timerules/TimeRules.kt` (`decideTimeLock`, `callsBlocked`), `TimeRulesRuntime.report`/`recheck`,
  `push/SyncSchedule.kt` and `push/BackstopAlarm.kt`.

Line numbers are `e5316ec2`'s. No product code was changed: the mutations and the page-dump test below were
temporary and are reverted, and `git status` was clean before this file was written.

**Verdict: merge after a fix round.** The connection state is right and honest, and the time helpers are sound:
- DST both ways, the zone fallback and "yesterday" across midnight all behave;
- the Attention list is complete against §3.2, and the list's count is the device page's rows by construction;
- the templates escape everything;
- the dependency is clean.

The weak spot is the lock badge's end time, the one thing the user asked never to get wrong:
- **M1:** the end is the named rule's window end. Nothing checks that the report lies inside that window, or that the
  lock really ends there. An always-on rule, two rules that meet, or a report a few seconds after the end produce
  "until 00:00 tomorrow" or "until 14:30 tomorrow" for a phone that stays locked, or one that unlocked long ago. A
  test pins one of these as correct.
- **M2:** with the Phone Setup zone empty or unknown, the end is the phone's local wall clock labelled "UTC".
- **M3:** "· calls off" repeats the phone's rule flag even when nothing on the phone blocks calls. The device page
  says "Incoming calls aren't blocked" for the same phone.

Counts: 0 High, 3 Medium, 5 Low, plus nits.

## What I ran

All cargo runs used my own target dir (`server-target-qa31s2`), under the shared build lock.
- **`cargo test`, unchanged:** 703 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets`:** 13 warnings, the same as before. They are in `models.rs` (9), `dns_engine.rs` (2),
  `storytel.rs` (1) and `devices.rs:1494`, the old `sort_by` that moved down from `:1321`. None is in the step's
  new code.
- **`node --test jstest/*.test.js`:** 16 passed.
- **The merge with `main` (`a5d30b92`):** `git merge-tree` is clean. `main` changed nothing under `server/` since
  `60872f24`.
- **The launcher constants on `main`:** `BACKSTOP_INTERVAL_MS` 30 min, `BACKSTOP_SSE_DOWN_INTERVAL_MS` 15 min,
  `MIN_BACKSTOP_MS` 9 min, and `setAndAllowWhileIdle`. These are what the two window constants' comments derive from.
- **A temporary probe test** (`lock_badge` called directly; the output is quoted in M1-M2 and the nits).
- **The browser pass.** A temporary test dumped `/devices` (six phones in every state, a 48-character name, `<b>` in a
  name) and a device page. Headless Chromium rendered them at **390 × 844, light and dark, with JS on and off**.
  - No horizontal scroll on any page.
  - The tab bar marks Devices.
  - Add Phone clears the tab bar when scrolled to the end.
  - The long name wraps.
  - `Mia <b>x</b>` shows as text.
  - The rows read sensibly without JS (the list has no script).
  - The focus ring shows inside the row (step 1's M1 fix holds).
- **Contrast.**
  - White on the avatars is 4.62 (green) to 6.04 (purple).
  - "Online" `#1E7A35` is 5.4:1 on white, and "Away" `#B35A00` is 4.8:1. Dark: 9.25 and 9.54.
  - The count is white on `#D70015`, 5.38:1.
  - The dots are 2.0-2.2:1 on white. That is acceptable, because each state is also the word.
- **Mutations.** Each was a temporary edit, run against the step's test modules
  (`display_time streams time_state attention kid_lock devices_list settings_hub shell calls step10 lock_glance`),
  then reverted:

  Independent mutants were batched: one compile per batch, and each killer is a different test. A batch with no
  failure means each of its mutants survives.

  | Mutation | Result |
  |---|---|
  | `ONLINE_WINDOW_MINUTES` 60 → 61 | killed (`streams::the_state_follows_the_windows`) |
  | `AWAY_WINDOW_MINUTES` 45 → 46 | killed (same) |
  | a closed stream (`open == 0`) still counts | killed (same) |
  | `FUTURE_SLACK_SECS` → 0 | killed (`a_time_slightly_ahead_is_just_now`) |
  | Offline not stale | killed (`offline_always_and_away_after_15_minutes_…`) |
  | `Zone::load` always the UTC fallback | killed (`devices_list::each_phone_shows_its_connection`) |
  | the fallback without its " UTC" label | killed (`the_zone_is_phone_setups_or_utc_labelled`, `the_end_is_shown_in_the_fallback_zone_labelled`) |
  | any report `day` counts as today (`fresh_day`) | killed (`a_locking_rule_and_todays_screen_time_show_on_the_row`) |
  | the list counts alarms only | killed (`the_badge_counts_the_device_pages_attention_rows`) |
  | `lock_off` ignores "reported" | killed (`kid_lock::the_lock_is_off_only_with_a_child_pin_and_a_reason`) |
  | Attention: `foreign_access` inverted | killed, but only because a phone **without** a report then gets a row (Mia's badge 0) |
  | Attention: `restart_once` inverted | killed, the same way |
  | `AWAY_STALE_MINUTES` 15 → 19 | **survives** (L2) |
  | `window_end` without `reported.date() != day` | **survives** (L2) |
  | the day before's 24 h window not counted (`>=` → `>`) | **survives** |
  | Attention: `policy_problem` inverted (`!= "ok"` → `== "ok"`) | **survives** (L1) |
  | Attention: `unseen_restarts` inverted | **survives** (L1) |
  | Attention: `incoming_calls_not_blocked` inverted | **survives** (L1) |
  | `call_report` drops `CallProblem::NoRedirection` | **survives** (L1) |

## Checked and fine

- **Connection state (§3.10), honest.**
  - Online needs an open stream **and** a contact within 60 min. A vanished phone whose ghost stream is still
    counted turns Offline 60 min after its last real contact, at the latest.
  - A stream's `since` moves only when one opens, or when the last one closes. A closed stream (`open == 0`) is never
    a contact, so a ghost dropping can't refresh "seen".
  - Disconnected is decided before any time is looked at.
  - After a graceful restart, `close_all` makes phones reconnect within seconds, and the stream open is itself a bearer
    request.
  - The two windows' comments derive them from the launcher's constants, which are unchanged on `main`.
- **Time math (§3.10, M4).**
  - `relative` uses only server times: `last_access`, `last_seen_at` and `reported_at` are all the server's `now`.
  - Both DST directions, the ambiguous 02:30, "yesterday" across a DST night and Oslo's local midnight (22:00 UTC) are
    tested and right.
  - `parse_db_utc` reads both stored shapes.
  - `today()` is the zone's date, not the server's `Local`.
  - The distroless image needs no `zoneinfo`.
- **The badge's day logic.**
  - `fresh_day` stops a report from yesterday from giving an end, so an overnight end can't read "tomorrow" after
    midnight.
  - The two-window equality refuses ambiguous Tuesdays.
  - An end in the past turns into "at last report".
  - Away goes stale after 15 min, and Offline always.
  - `screen_time` shows only for today's report.
- **Attention (§3.2, L2).**
  - All 11 alarms and 6 action kinds are there. Each fact matches the device page's own test for the same card:
    - DND: `priority`/`alarms`/`none`;
    - Backup: `Some(true)`;
    - `managed_without_pin`;
    - the easy PIN: `override_pin_easy` with a PIN set;
    - the policy state;
    - boots: the same `warning()`; `blocked_since` only changes its advice;
    - the relock card's `foreign` and `blocks.items`;
    - `admin_policy_notice`;
    - the listener;
    - `ledger_unreadable`;
    - the capability check.
  - `kid_lock::lock_off` mirrors `lock_card`'s lock-off warnings: an old launcher, and every `inactive` except
    `no_pin`.
  - Every `warnings.push` in `call_report` has its `problems.push` (16 pairs).
  - The titles are at most 8 words, with no jargon.
  - Each target anchor exists on the page (`#status`, `#time`, `#launcher`, `#hardening` are new).
- **The count equals the rows.** Both sides call `attention::collect`. The template renders every item, and
  `the_badge_counts_the_device_pages_attention_rows` compares them per phone.
- **Security.**
  - Askama escapes every value: the name, the initial, the phone-supplied `active_rule_name` in the lock part, the
    sign-in warnings and the titles. There is no `|safe`.
  - The only new inline code is `style="width: N%"` with an integer, which `style-src 'unsafe-inline'` allows. No new
    script.
- **Performance.** About 8-10 indexed reads per phone (`idx_device_status_device_id_reported_at`), sequential, as §3.8
  accepts for 1-5 phones. See the nits for the repeated ones.
- **Licence and supply chain.**
  - `chrono-tz` 0.10.4 and `siphasher` 1.0.4 are MIT OR Apache-2.0, and `phf`/`phf_shared` 0.12.1 are MIT. The IANA
    data is public domain. All are GPL-3.0-compatible.
  - `default-features = false, features = ["std"]` pulls no `chrono-tz-build` and no build script.
  - `Cargo.lock` has the four checksums, and CI and the release build use `--locked`.
- **Accessibility.**
  - The avatar and dot are `aria-hidden`, and the state is the word.
  - The badge reads ", 3 to check".
  - The meter is `aria-hidden` next to its text.
  - Rows are 68-176 px tall.
  - The SVGs are `focusable="false"`.
- **Docs.** The `server/CLAUDE.md` section and the status section match the code, apart from L4. No doc or script
  scraped the old "Last seen" wording.

## Medium

### M1. The lock badge's "until" is the named rule's window end, unchecked: "until 14:30 tomorrow" and "until 00:00 tomorrow" on phones that unlocked or stay locked

- **Where.**
  - `time_state.rs:242-278` (`window_end`) takes the end of the reported day's window, or the day before's
    overnight one. If that end isn't after the report's time, it adds a day (`:273-277`).
  - It never checks that the report's time lies **inside** the window.
  - It never checks what happens **at** the end: the same rule's next window starting then, or another effective rule
    already active.
- **Failures** (quoted from the probe test, Oslo zone):
  1. **A report just after the end.** Skole 08:00-14:30 reports `SCHOOL` and reaches the server at 14:30:05. Network
     latency or a phone clock a few seconds behind does this. The phone computes `time_state` live
     (`TimeRulesRuntime.report`), and nothing on the phone reports at a window's end.
     - The badge says **"Skole until 14:30 tomorrow"**.
     - If the phone then goes offline, at 22:00 it still says **"Skole until 14:30 tomorrow · at last report (7 h
       ago)"**. The `ended` guard can't catch it, because the invented end lies in the future.
  2. **A phone in another zone than Phone Setup's.** A phone set up with a typed code keeps its own zone, while Phone
     Setup's default is `Europe/Oslo`.
     - A London phone at 14:00 local is 15:00 in Oslo. The badge says **"Skole until 14:30 tomorrow"**.
  3. **Windows that meet.** An all-day rule (`start == end`, "24 h from start") every day is locked around the clock,
     but it reads **"until 00:00 tomorrow"**. `tests/devices_list.rs:155-189` pins exactly this as correct.
     - `time_rules::legacy_to_rules` creates "Outside allowed hours (evening)" X-00:00 and "(morning)" 00:00-Y. At
       21:00 the badge reads **"… (evening) until 00:00 tomorrow"**, and the phone stays locked until morning.
     - Two of the parent's own rules that meet or overlap behave the same: Skole 08:00-14:30 and a custom 14:00-16:00
       give "Skole until 14:30" at 14:15, and the lock lasts until 16:00. The launcher ranks school first
       (`decideTimeLock`).
- **Why it matters.** The brief and §3.10 say the end shows "only when it can't be wrong". A parent reads "until 14:30"
  as "it unlocks at 14:30".
- **Fix** (server only, no API change):
  - In `window_end`, keep a candidate only if the report's local time of day is inside its window:
    - `start <= t < end` for a same-day window;
    - `t >= start` (today's window) or `t < end` (the day before's) for a wrapping one;
    - a 24 h window counts as wrapping.
  - Drop the `+ 1 day` fallback for a non-wrapping window.
  - Then refuse the end when, at the end instant, any effective rule has a window that starts at or covers it. That
    includes the same rule's next day. `time_glance` already loads the effective rules.
  - Add the three probe cases as unit tests, and change the devices-list test to expect "Skole" with no end.
  - The launcher already knows the real end (`TimeLock.until` = `activeSpan(...).end`). A later `active_until`
    field, already named in §3.10, would retire the guess.

### M2. With the Phone Setup zone empty or unknown, the end is the phone's wall clock labelled "UTC"

- **Where.** `display_time.rs:101-103` (`clock_of`) appends " UTC" to a **phone-local** wall-clock time: rule windows
  are "minutes since the phone's local midnight". `time_state.rs:199-221` also places the report and `now` in UTC,
  and compares them with those local windows. `time_state.rs:566-579` pins "Skole until 14:30 UTC".
- **Failures** (probe):
  - Phones in Oslo, zone left empty: Leggetid 21:00-07:00 at 21:30 Oslo shows **"Leggetid until 07:00 UTC
    tomorrow"**. It ends at 06:00 UTC.
  - Phones in New York, zone left empty: a 20:00-07:00 bedtime at 20:30 local is already "tomorrow" in UTC.
    - Every evening from 20:00 to midnight the badge says **"Bedtime · at last report (just now)"** for a healthy
      Online phone, and the screen-time bar disappears (`fresh_day` compares the phone's `day` with UTC's).
  - An empty zone is a documented Phone Setup choice ("the phone keeps its own"). An unknown one is a typo
    `valid_time_zone` lets through.
- **Fix.**
  - In the fallback zone, show no end at all. The phone's zone is unknown, so no clock time can be right.
  - Compare `day` leniently: accept the UTC date ± 1 for `fresh_day`/`screen_time`, or drop the day test and only
    keep "at last report" by age.
  - Replace the pinned test with one that expects the name alone.

### M3. "· calls off" is shown while nothing blocks calls

- **Where.** `time_state.rs:235` copies `time_state.calls_blocked`. On the phone that is the rule's flag
  (`TimeLock.callsBlocked = locked && !callsAllowed`, `TimeRules.kt:150`). It is set whether or not the launcher
  screens calls.
- **Failure.**
  - The browser pass's Anna: calls not managed, a no-calls school rule.
  - The list says **"Online · Skole until 00:00 tomorrow · calls off"**.
  - Her page's first rows say **"Incoming calls aren't blocked"** (`attention.rs:290`).
  - The same happens with the call report's `NotEnforced`, `AnyoneCanCall` or `FailClosed` (where only emergency
    calls work, so "off" is right, but by accident).
  - A parent who reads the list, not the page, believes school hours are call-free.
- **Fix.** Pass `policy.calls_managed` (and "no `NotEnforced`/`AnyoneCanCall` problem") into `lock_badge`, and add
  "calls off" only then. Add a list test with an unmanaged phone.

## Low

### L1. Most of `attention::facts` can be inverted without a red test

- **Where.** `attention.rs:236-349`.
  - `items()` is tested with a hand-built `Facts`, and the integration test covers DND, Backup, no PIN, the old
    launcher and access removed.
  - The other mappings from the database to a fact are untested; see the surviving mutants above.
  - The count test can't catch them: both sides of the equality call the same function.
- **Failure.** A later step that moves a card and rewrites a source can silently drop a row or raise a false alarm on
  every phone.
- **Fix.** One integration test per fact, with a report that sets it and a key that appears, plus one clean report
  with no rows:
  - `policy_state: "cache_corrupt"`;
  - `unseen_boots_count`;
  - `foreign_access`;
  - `admin_policies_pending`;
  - `relock_blocks`;
  - a no-calls rule with calls unmanaged;
  - `lock_state.inactive`;
  - `ledger_unreadable`;
  - `restrictions_paused`;
  - `notification_listener_enabled: false`.

  Also add a unit test in `calls.rs` that `report.warnings.len() == report.problems.len()` for a report that sets
  every flag, so a new warning can't skip its typed twin.

### L2. Three of the badge's guards are pinned loosely or not at all

- **The stale boundary.** `AWAY_STALE_MINUTES` is tested at 10 and 20 min, so 15 → 19 survives.
  - Fix: test 15 (not stale) and 16 (stale).
- **The report's date check.** `reported.date() != day` in `window_end` (`:247`) is never exercised, because no test
  has a report from just before midnight with the next `day`.
  - Fix: with M1's in-window check this guard becomes part of that check. Otherwise add the case.
- **The day before's 24 h window.** `w.start >= w.end` (`:259`) can lose its `=` unnoticed. M1's tests for an all-day
  rule cover it.

### L3. The sign-in warnings are 30-40-word sentences with UTC times, in a row meant for one line

- **Where.** `devices_list.html:13-27` puts `live_warning` and `login_warning` whole into an orange row, plus a "Change
  the password" sub-line:
  - "2 wrong two-factor codes were entered since your last sign-in, and new sign-ins are locked until 2026-10-13 10:00
    UTC. Whoever entered them knows your password - change it now (that also lifts the lock)."
- **Failure.**
  - §3.2 asks for an orange row like "Change the generated password ›".
  - §3.0 allows one line, and the user asked for much less text.
  - The time is UTC, not local.
  - Someone who knows the password is arguably red, not orange.
- **Fix.** A typed short title ("Someone knows your password", "2 wrong sign-in codes"), the sub-line as the action,
  and the full sentence on `/account`. The lock time goes through `display_time`.

### L4. The restart note says "Away until the stream reconnects"; idle phones show Offline, and after a crash for minutes

- **Where.**
  - `server/CLAUDE.md` (the new section): "After a restart a phone shows Away until its stream reconnects".
  - The `connection_state` doc: "for the seconds until its stream reconnects".
- **Failure.**
  - A healthy idle phone's last report can be 30-52 min old. With the memory empty it shows **Offline** (> 45 min,
    no stream), not Away. The test `right_after_a_restart_…` even asserts that.
  - After a crash or a Pi power cut, no FIN reaches the phones. They reconnect only after 300 s of silence, so every
    phone is Away or Offline for up to 5 min, not 5-15 s.
- **Fix.** Docs only:
  - "Right after a restart a phone shows Away, or Offline if its last report is over 45 min old, until its stream
    reconnects: seconds after a clean stop, up to 5 min after a crash".
  - The same in §3.10's note.

### L5. The Attention rows are counted from a report of any age, and a new phone starts with a red badge

- **Where.** `attention.rs:303-348` reads the last report whatever its age, and for any connection state.
- **Failure.**
  - A phone Offline for two weeks, or Disconnected, keeps "Do Not Disturb is on", "Notification access off" and
    similar facts from its last report as live alarms in the list's count. The parent can't fix them, and the
    header's "at last report" doesn't reach them.
  - A phone just added, never set up, shows a red **1** ("No Parent PIN set") before it has been enrolled. In the
    browser pass Mia, Nora and Ella all carried it.
  - §3.2 doesn't settle either case. They are worth a decision before step 10 builds the header around them.
- **Fix.** Pick one:
  - (a) For Offline/Disconnected phones, keep only the server-side facts in the count: access, PINs, rules. Show the
    report-based rows titled "… at last report".
  - (b) Keep them, and say so in §3.2.

  For a phone not set up, (a) or a grey "Set up this phone" row instead of the red alarm.

## Nits

- **Future times.** `display_time.rs:117`: a time more than 5 min ahead shows its date, so "seen 13 Oct" for today
  (probe: 2 h ahead → "13 Oct"). Only a server clock stepped back can produce it. "just now" or the clock time reads
  better.
- **The key test.** `attention.rs:424-426`: `keys.dedup()` removes only adjacent duplicates, and several call rows
  share `data-attention="calls"`. The test's comment ("each kind has its own key") holds only because `everything()`
  has one call problem. Use a `HashSet`, or accept and document that call rows share a key.
- **Repeated reads on the device page.** It now builds `window_relock::card`, `call_report` and
  `effective_rule_rows` twice: once for the cards, once in `attention::collect`. On the list, `catalog_pins` (every
  tracked app's pin, a global read) runs once per phone, and `SELECT *` pulls `installed_apps_json` for each row.
  These are harmless at 1-5 phones. Passing the already-built cards into `facts` would halve the page's reads.
- **The initial.** `devices.rs` uses the first code point uppercased: "ß…" gives "SS", and a flag or ZWJ emoji is cut
  in half. Use the first grapheme, and take only one char of `to_uppercase`.
- **Two zones for the backups.** The hub's backup time is now in the Phone Setup zone, but the Backups page next to it
  still uses the server's `Local` (UTC in the Docker image), with no label. "Today 03:00" on the hub and "01:00" on
  the page can describe the same file. Move the page to `display_time` in step 14, or label it.
- **`title=` on a span.** The exact time sits in a `title` on a non-focusable `<span>` inside the row link, so touch
  and keyboard users never see it. That is fine for the list. The header and About need the ⓘ that §3.2 promises.

## The implementer's interpretations

1. **Online with a lock shows the lock instead of "seen …"; "at last report" leaves out a repeated time: accepted.**
   It matches the Main mockup. The long Away line ("Away · seen just now · Skole until … · calls off · at last report
   (3 h ago)", four lines at 390 px) is the worst case. It shortens once M1 and M3 drop the false parts.
2. **The bar only with a limit, "42 min today" without one, nothing at 0: accepted.** That is §3.2's "hidden when
   there is no limit and nothing is reported".
3. **Calls rows orange, one per warning, "Waiting" left out: accepted.** §3.2 lists calls problems under needs action.
   An emergency call in the last 7 days is arguably red; worth one line in §3.2.
4. **`parent_pin::when_text` folded in later: accepted.** Its callers move in steps 3, 4 and 10, and the module doc
   says so.
5. **No music or escapes rows: accepted.** §3.2's list is the contract. §3.8's source list should drop the two, so the
   docs agree.
6. **Darker avatar hues; grey for a phone not set up; no "+" while the brand bar stays: accepted.** The contrast is
   checked above.
7. **The browser pass: done here, headless** (see above). Still owed on a real phone:
   - the installed PWA;
   - iOS Safari 14's line wrapping of the status line;
   - forced colours, where the dots disappear and the words remain.

## User checks

1. After the fix round, on a phone with a school rule, look at the Devices list at 14:29 and at 14:31. Expect "until
   14:30", then "Skole · at last report", and never "tomorrow".
2. Make an all-day rule (start = end) and check the list shows the rule name with no end.
3. Leave Phone Setup's zone empty for a minute. Expect "seen" times labelled UTC, and no "until" on the badge.
4. With calls **not** managed and a no-calls rule active, the list must not say "calls off".
5. Restart the server (`systemctl restart`). Phones go Away or Offline and return to Online within seconds. After a
   power cut, within about 5 minutes.
