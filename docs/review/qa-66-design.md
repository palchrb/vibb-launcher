# QA 66 (design): Bedtime as its own setting

Reviewed: `docs/design/66-bedtime.md` at main 61bb0182, against the code it cites. Read-only, no builds.

**Verdict.** The shape holds up. The phone reads `{s, 0}` as "until midnight" and `{0, 0}` as 24 h, with the end
exclusive. Kind `bedtime` with a blank name gives `lock_kind_bedtime`. Restored backups go through the migrations and
then the conversion. `main.rs:1573` is real, and so is `migrations_73_to_100_in_order`. Partial unique indexes are
already used. The policy contract really is unchanged. Before step 1 is built, the plan needs four things: the
conversion must be crash-safe, `effective_rule_rows` must be split into the rule set and the set plus the bedtime,
the day-rule editor must stop accepting the bedtime row, and a start at 23:59 must not convert again on every start.
**0 High, 4 Medium, 5 Low, 5 Nits.**

## Medium

**M1. The conversion is not safe against a crash midway, and as written it can stop the server from starting.**
Claim (§3.5): "one transaction per phone and one for everyone's rules ... it runs at every start and finds nothing
once done". Evidence: a failed startup conversion is fatal. `connect_db` returns the error (`main.rs:1573-1575` for
`migrate_legacy`), and `main.rs:262-280` then calls `exit(1)` unless a restore is being undone. Suppose everyone's
rule G is copied into phone A in A's transaction and the server stops before B's copy or G's delete. On the next
start A still follows everyone's rules, G is still bedtime-shaped, and step 3 copies G into A again. A's second
`bedtime = 1` row breaks `idx_time_rules_bedtime`, the start fails, and every later start fails the same way.
Fix: convert each everyone's rule in one transaction (all copies, the lift repoints, the delete). Also say that step 3
skips a phone that already has a `bedtime = 1` row, and splits the crossing rule instead.

**M2. The plan for `effective_rule_rows` and the lists is wrong for the code as it is.** Claim (§3.1): it returns
"the rule set in force **plus** the phone's bedtime row, last", and "the Own Rules list leave[s] out `bedtime = 1`
rows". Evidence: the Own branch is `SELECT * FROM time_rules WHERE device_id = ?` (`time_rules.rs:418`), so it
already returns the bedtime row. Appending the row again sends it twice: in the policy (`device_api.rs:566`), the
badge, and Pause a Rule (`schedules.rs:1450`). The Own Rules list is built from this same function,
`effective_rule_rows(&state.db, id, true)` (`schedules.rs:1329` → `rule_rows`, line 1460). Everyone's list calls it
with device 0 (`schedules.rs:266`). Fix: name two readers, `rule_set_rows(db, Option<device>)` with
`AND bedtime = 0` for both lists, and `effective_rule_rows` = that set + the bedtime row. List the callers that
must get the bedtime: `device_api.rs:566`, `lifts.rs:97`, `devices.rs:268`, `devices.rs:1522`, `attention.rs:488`
and `schedules.rs:1319`.

**M3. The day-rule editor still opens, saves and deletes the bedtime row.** Claim (§2): the bedtime is edited only on
the Bedtime page. Evidence: `show_rule` loads any id (`schedules.rs:696-708`). `rule_in_scope` checks only
`device_id == scope` (`schedules.rs:1011-1027`), and `update_rule` and `delete_rule` rely on it (lines 1041, 1087).
A rule converted in place keeps its id (§3.5 step 3), so the parent's old `/schedules/rules/{id}` link (history,
bookmark) opens the Bedtime in the day editor. If the parent saves it there with day windows, it stays
`bedtime = 1` with windows the Bedtime page can't read (it reads days 0 and 4). Fix: `show_rule` redirects a
`bedtime = 1` row to `/devices/{id}/bedtime`, and `rule_in_scope` treats it as another scope's row (404). Add
this to step 1's `schedules.rs` list, with one test row.

**M4. A window starting at 23:59 is not exact, and it converts again on every start.** Evidence: in step 4, a
crossing `{23:59, e}` or a 24 h `{23:59, 23:59}` on day D is stored as `{23:59, 23:59}`. Stored, that reads as a
24 h window (`time_rules.rs:7-8`), and the editor refuses to save it unchanged (end > start, §3.3).
`served_days` sends it as `{23:59, 0}` (correct). If "still crosses" is tested on the stored window
(`start >= end`), the next start splits it again and adds another "(morning)" row every start. Also, the morning
part of a 24 h window at 23:59 is `{0, 23:59}`, which is served as `{0, 0}`: it locks one minute too long on D+1.
Fix: define "crosses" as stored `end != 23:59 && start >= end`. That matches `served_days` and makes a second run
find nothing. Either refuse a day rule that starts at 23:59, or let `parse_rule_form` take `{23:59, 23:59}` as
"23:59 to midnight". Name the 24 h-at-23:59 case as one minute longer, or exclude it. Add `{23:59, 07:00}` to
`crossing_rules_convert_exactly` and to the second-run check.

## Low

**L1. An unreadable row must be skipped, not fail the start.** §3.5 says nothing about a row whose `days_json` or
`exempt_apps_json` doesn't read. `parse_days` errors on it (`time_rules.rs:124-138`), and an error in the startup
conversion is fatal (M1). Fix: skip such rows with a `tracing::warn!`. Today they already make the policy a 500,
and saving them in the editor fixes them. Only `sqlx::Error` should be returned.

**L2. "The forms-land check picks up the new page by itself" (§5 tests) is false.** Evidence: the check uses a fixed
list of pages (`tests/shell.rs:689-721`), and `leaves_the_page` matches the actions one by one
(`tests/settings_pages.rs:73-100`). Without changes, the Bedtime page isn't checked. Fix: add
`/devices/{id}/bedtime` (once with a bedtime stored, so the Remove form shows) to the list. Add
`["devices", n, "bedtime", "remove"]` to `leaves_the_page`, the way the zone delete is added (line 98).

**L3. The test plan leaves out existing tests that this change breaks.** `tests/time_rules.rs:103-116` posts an Own
rule from 21:00 to 07:00, which the new end > start check refuses. Lines 151-153 assert that day 6 is
`{1260, 420}`. The legacy-conversion test runs `connect_db` twice (`tests/time_rules.rs:828-845`) and asserts
`rows.len() == 3`. With `migrate_crossing` the global "Bedtime" (no phone follows everyone's) and "Outside allowed
hours" are split, and the phone's own rule becomes `bedtime = 1`. Fix: name these in step 1. Keep the legacy test
as the end-to-end check of `migrate_legacy` → `migrate_crossing` (it already restarts twice, so it is the second-run
check for free).

**L4. Two saves of a new bedtime at once give a 500.** "Add" when no row exists, done as a SELECT and then an
INSERT, hits the unique index on the second save. Fix: make the save a single upsert,
`INSERT ... ON CONFLICT(device_id) WHERE bedtime = 1 DO UPDATE` (SQLite takes a partial index as the conflict
target).

**L5. A bedtime row whose stored data doesn't read, or doesn't have the expected shape.** The page reads days 0 and
4. A restored or hand-edited row with days that don't read, or with days 6/0-3 not equal, would show times that
aren't stored. Fix: like `rule_rows` (`schedules.rs:152-176`), the Bedtime row and the `schedules.html` sub-line
say "Can't be read" (`text-alarm`). The page then opens with the rows empty plus a warning, and a save rewrites
all seven days.

## Nits

- **N1.** The `lock_name` change is not needed. `TimeReport::parse` already trims an empty `active_rule_name`
  and drops it (`time_state.rs:73-77`, pinned at line 465), and `lock_name` then falls back to "Bedtime"
  (lines 112-130). "Today it would show an empty name" (§3.4) is wrong. Drop it from step 1 and from the badge test.
- **N2.** `served_days` can ignore the flag: no bedtime ends at 23:59 (the morning is before 12:00), so "map every
  end of 23:59 to 0" is less code and the same result. Also say in §3.2 that such a rule's badge now reads
  "00:00 tomorrow" (`time_state.rs:287`), not "23:59".
- **N3.** The four new security events need labels in `admin.rs`'s table (next to `time_rule_*`, lines 115-117).
  Without them they show as `words_of`'s "Time rule to bedtime".
- **N4.** The lift repoint in §3.5 step 3 says "still delivered", but its SQL repoints every lift. Either is fine,
  because `rule_name` is stored on the lift. Make the text and the SQL agree. The phone reads `ruleId` from the
  policy's lifts each time (`ScreenTime.kt:145-148`), so the repoint takes effect on the next sync.
- **N5.** Say how the bedtime interacts with D1 and `time_rules_v1`: a launcher older than step 6 gets only the frozen
  legacy columns (`device_api.rs:252-285`), so the new Bedtime never reaches it. Device › Time already warns about
  such a launcher (`schedules.rs:1401`). D1 removes that path later and doesn't touch the new flag. Optionally keep
  `bedtime = 1` to device rows only: the partial index doesn't catch a NULL `device_id`.

## Checked and true

- The phone: `Span` is end exclusive (`TimeRules.kt:70-72`). `spanOn` reads `{s, 0}` as running to the next
  midnight and `{0, 0}` as 24 h (lines 85-89). `activeSpan` checks today's and yesterday's spans (95-98).
  `bootCallBlocksOf` is built from the policy, so it follows `served_days` without changes (286-287). The days are
  Monday first, so 6, 0-3 = Sun-Thu and 4, 5 = Fri-Sat.
- `ruleLabel` (`TimeLockModel.kt:37-38`) and `RuleCardTest.kt:82` behave as the note says. The phone shows a rule's
  name only at `RuleCard.kt:66` and `LockActivity.kt:142`; time requests use `lock_reason` (`parent_events.rs:1265`).
- The server reads windows only in `to_policy`, `rule_days`/`covering_ends`, `days_summary`, `day_inputs`: step 1 has all.
- Restore swaps the file before `try_connect_db` (`main.rs:253`). The migrations, `migrate_legacy` and then the new
  conversion run on the swapped-in database. `pre_migration_backup` runs because 0101 is pending
  (`startup.rs:248+`).
- The routes follow the admin router's patterns (`/devices/{id}/time`, `/devices/{id}/zones/{zone_id}/delete`).
  The 303s match pwa.md's rules. `#bedtime` on Device › Time is always rendered ("Not set").
