# Code QA - server round 7, part A (design 48: Activity, Now, Locate's Requests, commands pruned)

- Commits: `096d86c0..4d843a70` on branch `r7-a`: `b6182ef8` (retention prunes `device_commands` after 30 days,
  design 48 §1.6) and `4d843a70` (the summary's Now and Activity, Locate's Requests, `Zone::at` on command rows).
- Read: root and server `CLAUDE.md`; `docs/notes/server/pwa.md` (the summary, Locate, the no-jump rule);
  `docs/design/48-activity-log.md` with its revision list (48-1…48-11) and Implementation status;
  `qa-r6-b-code.md` for the format. `docs/review/qa-r6-server-design.md` is not in the repo (main or the worktree), so
  I checked the 48-x items against the design's own revision list. I read the full diff and the code around it: the
  pop and its re-delivery window (`device_api::build_policy`), `DeviceCommand::{status, cancellable, redeliverable}`,
  `EXPIRING_COMMANDS`, `cancel_waiting`, `notifications::{open, back_of, back_query}`, `last_answers`, `check_forms` /
  `leaves_the_page`, and the schema for anything that points at `device_commands`.
- Ran (`CARGO_TARGET_DIR=…/r7-a`): `cargo test`: **1090 passed, 0 failed, 0 ignored**. `cargo fmt --check`: clean.
  `cargo clippy --all-targets`: 13 warnings, the same 13 that were already there (`models.rs`, `dns_engine.rs`,
  `storytel.rs`, `devices.rs:1305`). None is in the changed code.
- Mutations: 16 one-line mutations, each run against `summary::`, `locate_page::`, `cleanup::`, `locate::tests`,
  `command_expiry::`, `shell::every_form`, `notifications::` and `window_relock::` (93 tests). Each was reverted with
  `git checkout --`. The tree is clean (`git status` shows only this file).

## What holds

- **No save jumps to the top.** End now → `#now`. The summary's Cancel → `#activity`. Locate's Cancel → `#recent`.
  The macro's `group` parameter puts the right fragment on each form, `check_forms` runs on both pages, and
  `assert_lands` passes for both Cancels. Activity and Requests both stay on screen to show a Cancel's flash after the
  last row has gone (both cases are tested). An event's `open` form is exempt as navigation (`leaves_the_page`).
  Without a target, it lands on `/notifications?from=devices&device=<id>#inbox` (tested after a prune).
- **Retention keeps every command that can still reach the phone.** The SQL keeps a row when `delivered_at`,
  `cancelled_at` and `acknowledged_at` are all null and the command is not in `EXPIRING_COMMANDS`. That is exactly the
  set the pop can still hand out for the first time. A delivered command is offered again only within
  `REDELIVER_WINDOW_MINUTES` (15) of its first delivery, so after 30 days it is dead. An expiring command is dead after
  an hour. Nothing has a foreign key to `device_commands` (checked every migration). The readers that look at old rows
  are `last_answers`, `install_now`'s newest row and `locate_result_json`. For them, a pruned row only means "no
  history"; they never act on it. The older launcher's `relock_blocked` looks back only `WARN_MS` = 7 days, well
  inside 30. `Pruned.commands` is counted and logged through `?pruned`.
- **Merge and cap.** The two reads are each ordered `DESC, id DESC`, and the merge is a stable sort on
  `Reverse(Option<time>)`. Rows that tie keep each table's order, with commands before events. A time that doesn't
  parse goes last. Commands that can still be cancelled never count toward the cap of 10. The event read's `LIMIT 10`
  is enough because only non-cancellable rows count. The 7-day window string matches each table's stored format
  (`datetime('now')` text for commands, RFC 3339 with milliseconds for events). Clock words come from `Zone::at`,
  which works on local dates, so DST only moves the clock reading.
- **Requests matches design §1.4 bucket for bucket.** Shown at any age: Waiting, Sent{NotYet | OfferedAgain |
  CantConfirm}, Received{!overdue}, Erasing. Shown for 24 h (by when it was asked): Failed, Expired, Sent{Lost},
  Received{overdue}. Also shown: the newest Ring, Stop Ringing, Lock and Erase. Never shown: Cancelled{Parent}, or a
  Done locate. Only the old Recent's ten-row history is gone, as the design intends (Activity holds it now).
- **The answers are fully removed.** No template or test reads `relock.answers`, `data-answer` or `AnswerState` any
  more. `summary.rs:533` asserts that `data-answer` is gone. `last_answers` still feeds `relock_blocked`.
- **Security.** The event form's `device` goes through `number()` (an i64), and `from` goes through `back_of`'s
  whitelist. The redirect is `/notifications?…` built from those two values, so it can't be steered off the site. The
  server has a single household and the inbox is not scoped per phone, so `device` only picks a filter; it grants no
  access. A cross-phone Cancel is still refused by `cancel_waiting`'s `device_id = ?`.

## Findings

**1 (Low) - when Now disappears after End now, nothing checks that the page still lands on `#now` (mutation n
survives).** `src/handlers/devices.rs:794`: `show_now` keeps the group on screen for its flash
(`|| self.flash("now").is_some()`), but deleting that clause leaves every test green. The case it protects is real.
Suppose a pause started yesterday is still running. When it is ended today, it is neither running nor made today, so
`lifts::today` drops it. With no other lift or PIN notice, `#now` is gone, and End now's save lands at the top of
the page: the standing rule. `time_rules.rs:420` checks the redirect but not where it lands.
Fix: in `summary.rs`, add a test with a pause created yesterday (`created_at = datetime('now','-1 day')`) and
running. End it through the rendered form, then `app.assert_lands(&res, &cookie)` and assert
`data-flash="now"` on the page.

**2 (Low) - the retention test doesn't pin the `delivered_at` clause (mutation k survives).** `src/retention.rs:163`.
The `cleanup.rs:360` fixture's only delivered row also carries `acknowledged_at` (`done`). Removing
`OR delivered_at IS NOT NULL` therefore stays green. If that happened, a delivered command the phone never confirmed
(an old lock or ring, `Sent{Lost}`) would be kept forever. That is not a safety problem, but it is unbounded growth
that the comment says can't happen.
Fix: add `("lock", "-31 days", delivered only)` to the fixture as pruned, and bump the count to 4.

**3 (Low) - "the newest of its kind" also counts one the parent cancelled, which can hide the real newest
answer.** `src/handlers/locate.rs:484`: `seen.insert` runs before `in_requests` refuses `Cancelled{Parent}`. Take
this sequence: Lock (Done yesterday), then Lock again and Cancel it. Requests then shows no Lock at all, although
"the last Lock reached the phone" is what a parent looks for on Locate. The design says "the newest
ring/stop_ring/lock/wipe whatever its status; never Cancelled{Parent}", which can be read either way, and the
implementer recorded this reading. I'm raising it because the other reading (skip the parent's cancels, then take the
newest) seems closer to what the user meant.
Fix (if the user agrees): before `seen.insert`, skip `Cancelled{Parent}` rows when picking the newest (compute the
status once, and test it first), and add one row to `requests_show_what_is_in_flight_or_just_went_wrong`. Otherwise,
note the choice in pwa.md's Requests note.

**4 (Low) - the notes still describe the summary's Today and Recent.** `docs/notes/server/hardening-and-pins.md`:
- line 25: "a count in the summary's Today";
- line 26: "The summary's Today shows at most two notices";
- line 32: "The newest of each shows in the summary's Today for a week … (`window_relock::answer`, typed)". That
  function is deleted.

`docs/notes/server/architecture.md:29` still describes "the summary's Today row for a 24b command", Recent's Cancel,
and `device_commands` with no retention. `pwa.md:364`'s "Typed (M5)" list still names `Answer, AnswerState`. pwa.md
adds "supersedes" notes, but these other files were not touched.
Fix: change Today to Now for the PIN notices and Activity for the commands. Replace line 32's answer sentence with
"each is a row in the summary's Activity (design 48)". Add design 48's 30-day pruning to architecture.md's command
paragraph, and drop `Answer, AnswerState` from pwa.md:364.

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `capped <= MAX_ROWS` → `<` (9 rows) | caught - `summary::activity_keeps_a_week_ten_rows_and_what_can_be_cancelled` |
| b | cancellable rows count toward the cap (`false \|\| {…}`) | caught - same |
| c | sort oldest first (`*at` for `Reverse(*at)`) | caught - `summary::activity_mixes_…_with_the_clock`, `command_expiry::the_device_page_says_a_pause_expired_or_was_cancelled` |
| d | an old cancellable command is dropped (window only) | caught - `activity_keeps_a_week_…` |
| e | event rows use `zone.when` (no clock past yesterday) | caught - `activity_mixes_…_with_the_clock` |
| f | `WINDOW_DAYS` 7 → 9 | caught - `activity_keeps_a_week_…` |
| g | `in_requests` without `_ if newest => true` | caught - `locate::tests::requests_take_…`, `locate_page::requests_show_…`, 2 in `command_expiry` |
| h | a locate can count as the newest of its kind | caught - `locate_page::requests_show_what_is_in_flight_or_just_went_wrong` |
| i | `REQUESTS_HOURS` 24 → 72 | caught - same |
| j | retention deletes every 31-day row (`… OR 1`) | caught - `cleanup::pruning_keeps_only_what_retention_allows` |
| k | retention without `OR delivered_at IS NOT NULL` | **NOT caught** (finding 2) |
| l | the summary's Cancel lands on `#recent` | caught - `activity_keeps_a_week_…` (form target and `assert_lands`) |
| m | Activity hidden when only the flash is left | caught - `activity_keeps_a_week_…` |
| n | `show_now` without its flash | **NOT caught** (finding 1) |
| o | the event form without hidden `device` | caught - `summary::an_event_opens_where_it_happened_or_this_phones_inbox` |
| p | Requests hidden when only the flash is left | caught - `locate_page::requests_show_…` |

## Verdict

**Approved with fixes.** There is no High or Medium finding. Retention can never prune a command that may still reach
the phone, and every new form lands on its own group. Findings 1 and 2 are a test each and should go in before the
merge. Finding 4 is a docs pass. Finding 3 is a question for the user (either a one-line change or a note).
