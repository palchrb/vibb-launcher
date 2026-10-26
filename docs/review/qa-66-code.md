# QA 66 (code): Bedtime as its own setting

Reviewed: branch `bedtime` at 0b5c9c24 (50381989 + 0b5c9c24 on main 2aed5880), 20 files, server only. I read the
diff line by line, ran the full suite, applied 15 mutations (each one reverted), ran the real binary on a seeded
pre-66 database, and did a headless browser pass. I changed no code.

**Verdict: fix first (one Medium, small).** The conversion is exact and crash-safe, and the serving is right:
`migrate_crossing` did what §3.5 says on the test databases and on a real start (a global rule copied to its
follower, the lift repointed, an own rule converted in place, a school rule split, an unreadable row skipped with a
warning, no change and no new log line at the second start). The one thing to fix before merging is the rule lists,
which now print the served windows, so a whole-day rule reads "Sat 00:00-00:00" (M1). The rest are Lows and Nits
that can wait. **0 High, 1 Medium, 2 Low, 3 Nits.**

## Medium

**M1. The rule lists show the served end (`00:00`), not the stored one (`23:59`): a whole-day rule reads
"Sat 00:00-00:00".** `rule_rows` (`server/src/handlers/schedules.rs:149-165`) builds the summary from
`row.to_policy()`, and since this branch its `days` are `served_days` (`time_rules.rs:112-124`). Settings › Time Rules
and Device › Time's Own Rules therefore print `{0, 23:59}` as "00:00-00:00" and `{20:00, 23:59}` as "20:00-00:00".
The editor shows the same rule as 00:00-23:59, and the new footer says "00:00-23:59 is the whole day". The old
"Sat 00:00 for 24 h" is gone, so the list now shows the most common whole-day shape as an empty window
(screenshot `chromium-light-js-11-own-rules-whole-day.png`; the startup's split rules read "Mon 22:00-00:00" too).
No test covers it: `days_summary`'s unit test feeds stored days, and no list test has a 23:59 rule. Fix: in
`rule_rows`, summarise `parse_days(&row.days_json)` (stored), and keep `to_policy()` only as the readability check.
Then add one list assertion for a 00:00-23:59 rule.

## Low

**L1. A split rule loses its "Til" during the evening part, both on the phone and in the PWA badge.** The startup
splits a non-bedtime crossing rule (for example school 22:00-02:00) into `{22:00, 23:59}` served as `{22:00, 0}`, plus a
"(morning)" `{0, 02:00}`. From 22:00 to midnight the active rule's end is 00:00, where the morning part still locks.
The server's `provable_end` (`time_state.rs:332-338`) and the phone's `lockEnd` (`RuleCard.kt:100-101`) both then
return no end. Before the upgrade, the card and badge said "Til 02:00". The lock itself is exact, and only the
end text is lost. The note lists what the split costs (§3.5: a running pause on the morning part), but not this.
Fix: add it to §3.5 and `time-rules.md` as an accepted cost. It only affects the rare split shapes, because a
bedtime-shaped rule becomes Bedtime. Optionally, `provable_end` could chain to the next covering end when the end is
00:00 and the same-named "(morning)" part covers it (server only; the phone would still show nothing).

**L2. A day rule "until midnight" typed as 20:00-00:00 is refused with "For a night, use Bedtime."**
(`schedules.rs:905-913`). That is the natural way to type "until midnight", and it is not a night. The conversion
already stores such a window as `{20:00, 23:59}` (`normalised`, `time_rules.rs:500-510`). Fix: accept an end of 00:00
with a start > 00:00 as 23:59 (store 23:59), or add "Use 23:59 for midnight." to the refusal.

## Nits

- **N1.** "Sun-Thu nights" breaks at its hyphen into three lines at 390 px ("Sun- / Thu / nights",
  `device_bedtime.html:31`, screenshot 2). Fix: a non-breaking hyphen (U+2011) in `ROWS` (`bedtime.rs:37`), or
  `white-space: nowrap` on that label.
- **N2.** A stored bedtime with all seven days null reads "No nights" (`bedtime.rs:58`) instead of "Can't be read",
  and its page opens with empty rows and no warning. No save can produce it (`parse_bedtime_form` refuses both rows
  empty). Fix: treat `(None, None)` as unreadable in `stored_nights`.
- **N3.** `a_stopped_conversion_ends_in_the_same_rows` (`time_rules.rs:1380`) stops after a phone whose own set is
  not in force, which is the easy stop point. The interesting one is after `convert_global_bedtime` with a second
  bedtime-shaped global rule still waiting: the next run picks that rule as "first", but every follower now has a
  bedtime, so it is split, the same as in one straight run. I checked this by reading the code, and no test covers
  it. Fix: add that house to the test.

## The design QA's findings, as built

- M1 crash safety: one transaction per phone and per global rule (`convert_global_bedtime` holds all copies, lift
  repoints, the delete and the log line); a follower with a bedtime splits the rule. Applied.
- M2 two readers: `rule_set_rows` (`AND bedtime = 0`, both lists) and `effective_rule_rows` = set + `bedtime_row`
  last; every caller classified (policy, lifts, badge, summary, Attention, Device › Time's picker). Applied.
- M3 editor guards: `show_rule` 303 to `/devices/{id}/bedtime`, `rule_in_scope` 404, UPDATE/DELETE `AND bedtime = 0`.
  Applied.
- M4 23:59: `crosses` = `start >= end && end != 23:59`; the form takes 23:59-23:59; `{23:59, 07:00}` is in the
  minute-by-minute test. Applied.
- L1 unreadable rows are skipped with `tracing::warn!` (seen on the real start); only `sqlx::Error` is returned;
  `expect`s are on serialising plain types only. Applied.
- L2 `shell.rs` page list (with and without a stored bedtime) and `leaves_the_page`. Applied.
- L3 the existing tests were rewritten (the Own-rule post, the legacy test that ends in 5 rows with the phone's
  Bedtime). Applied.
- L4 single upsert `ON CONFLICT(device_id) WHERE bedtime = 1`; two saves at the same time make one row (test). Applied.
- L5 "Can't be read" in both places, also when the apps don't parse (`stored_nights` goes through `to_policy`).
  Applied.
- N1-N5: `lock_name` untouched; `served_days` ignores the flag; the four event words are in `admin.rs:118-121`; every
  lift is repointed (an ended one too, tested); `bedtime = 1` is set only on device rows. Applied.

## Verified (critical paths)

- Conversion (`time_rules.rs:491-776`): the predicate, step order (`normalise_all` → phones → global first
  bedtime-shaped → splits), and the set in force (`COALESCE(custom_schedule_enabled, 0)`, as `build_time_policy` picks
  it, `device_api.rs:560-570`) all match the note. A phone on Own rules with its own bedtime-shaped rule plus a
  global one gets exactly one bedtime, because it is not a follower. A set no phone follows is split. `NOT EXISTS`
  is on both writes. `parse_days` requires 7 entries, so `split_days`' `% len` and `Nights::from_days` can't panic.
  Restore swaps the file before `try_connect_db` (`main.rs:249-262`), and nothing drops `time_rules`, so the partial
  index stays (a pre-0101 backup gets it from 0101, and a later one has it).
- Serving: `served_days` is used in `to_policy` and `rule_days`, the badge's only window readers. The phone's
  `bootCallBlocks` follows the policy. Real policy: the bedtime is last, `name: ""`, `{1260, 420}` untouched, and
  `{1200, 23:59}` is served as `{1200, 0}`.
- Pages: 303 targets, the 400 refusal as typed with `aria-invalid`/`autofocus`, 404 for an unknown phone, nudges,
  log words, both POSTs in the no-session test, CSRF by the listener's `csrf_guard`. The PWA never shows the empty
  name: the badge falls back to `lock_reason`, and the picker and lifts use the stored "Bedtime".
- Docs: `time-rules.md` matches the code (except M1's list text). `server/CLAUDE.md` says the next free number is
  0102. `migrations_73_to_101_in_order` is there. `style.css` is unchanged, so the version is not bumped.

## Tests, fmt, clippy

- Full local run: **1360 passed, 1 failed**. The failure is the known DST test
  (`tests::music::the_phone_reports_music_state_with_known_fields_only`, fixed by S1).
- `cargo fmt --check`: clean. `cargo clippy --all-targets`: no new warnings (the 13 are in files the branch doesn't
  touch).

## Mutations (each one reverted, time tests run)

14 of 15 were killed:

| Mutation | Result |
|---|---|
| Crossing without `end != 23:59` | killed by `crossing_rules_convert_exactly` |
| `served_days` keeps 23:59 | killed (3 tests) |
| `rule_set_rows` includes the bedtime | killed (never-a-day-rule, served-once) |
| `effective_rule_rows` without the bedtime | killed (5 tests) |
| Follower check ignored | killed |
| Lifts not repointed | killed |
| Global row not deleted | killed (3 tests) |
| Rule form takes end == start | killed |
| Bedtime evening from 11:00 | killed |
| `show_rule` renders the bedtime | killed |
| `rule_in_scope` takes the bedtime | killed |
| `convert_phone` ignores `has_bedtime` | killed |
| Morning on the same day | killed |
| Fri-Sat on days 5-6 | killed (5 tests) |
| Follower copy without `NOT EXISTS` | **survived**: equivalent, because the `any(has)` guard earlier in the same transaction already splits whenever a follower has a bedtime |

## Browser pass (Chromium 390 px; light and dark with JS, light and dark without JS; WebKit not installed)

Screenshots are in `scratchpad/bedtimeqa/shots/` (`chromium-{light,dark}-{js,nojs}-N-*.png`). In all four
runs:

- 1: Device › Time shows "Bedtime / Every night 21:00-07:00", converted from everyone's "Natt", with the
  repointed pause in Recent.
- 2: the page.
- 3: the refusal (Fri-Sat until 13:00). The flash is in the bar and under its row, the form is as typed, Vibb Music
  is still on, and the page doesn't move (`main` at 0).
- 4: Save lands on `?saved=1#bedtime` with the flash.
- 5-6: Remove lands on `/devices/1/time?notice=bedtime_removed#bedtime`, scrolled to the group (`main` at 909,
  group at the top), showing "Not set" and "Bedtime removed.".
- 7: Add with the defaults 21:00-07:00 ×2.
- 8: a day rule refused with "Wednesday: the end must be after the start…" under Wed, the new footer, and the
  Type select with School and Other.
- 9: Settings › Time Rules sub-lines ("Bedtime Every night 21:00-07:00", "Bedtime Sun-Thu 22:00-06:00 · Fri-Sat
  23:00-09:00").
- 10: Emil's converted own rule as his Bedtime, with Own Rules empty.

Also 11 (M1, "Sat 00:00-00:00") and 12. There was no horizontal scroll, and the only console errors were the
refusals' own 400s. No save landed at the top.
