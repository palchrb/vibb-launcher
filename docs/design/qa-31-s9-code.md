# QA 31 step 9 (code) - Settings › Time Rules and Device › Time

Reviewer: code QA, 2026-10-14. Branch `design-31-s9`, `e8a21506..ea3cc8db` (one commit, 24 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.7, §3.0, §3.2 (Device › Time), §3.5 (Time Rules), §3.8, §3.10 (the badge rules), §4, §5 (§5.1 incl. §5.1.4,
  §5.2, §5.5, §5.6), §8 step 9 and §9;
- step 8's status and fix-round table, and "Step 9 implementation status" with its 9 choices;
- `docs/design/06-time-rules.md` (overnight, 24 h, lifts, budgets);
- `qa-31-s7-code.md` and `qa-31-s8-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/schedules.rs` in full, with the old file at `e8a21506` side by side (`time_input_to_minutes`,
  `parse_rule_form`, `parse_budget_form`, the storage of days, apps and budgets, every write);
- `handlers/lifts.rs` in full (`create_lift`, `end_lift`, `back_to`, `done_flash`, `LiftState`, `recent`);
- `time_state.rs` (`TimeReport::parse`, `rule_days`, `provable_end`, `lock_badge`, `text_beside`), `attention.rs`
  (`facts`, `target`), `handlers/devices.rs` (the Time row, `time_glance`, the deleted card);
- `schedules.html`, `schedule_budget.html`, `schedule_rule.html`, `device_time.html`, `partials/budget_rows.html`,
  `device_detail.html`, the CSS and head diffs; `static/scroll-restore.js` and `autosave.js`;
- every new and changed test (`tests/time_pages.rs` in full, the `time_rules.rs` diff, `devices_list.rs`, `shell.rs`,
  the unit tests) and the docs diff.

Line numbers are `ea3cc8db`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a small fix round.** The step does what §8 step 9 asks:
- **routes and scope.** The four GETs are in the admin router with the POSTs (auth, CSRF, session); the phone always
  comes from the path. A rule is written or deleted only through its own scope: `rule_in_scope` compares the row's
  `device_id` with the form's `scope`, and the `UPDATE`/`DELETE` repeat `AND device_id IS ?`, so a global rule can't be
  edited through a phone's scope and the other way round (404; pinned by `rule_edit_and_delete_are_scoped`). The
  editor's GET takes the scope from the row, never from the URL. A lift's End now is `WHERE id = ? AND device_id = ?`
  and its "already over" lookup is scoped the same way. `back` is compared with the one value `time`; anything else is
  the device page, so there is no open redirect. `/schedules?device=X` takes only an `i64`;
- **the `section` split (H1).** `switch` binds only `custom_schedule_enabled`, `budget` only `daily_budget_json`, and the
  `COALESCE(?, col)` of a NULL bind keeps the other column; no section writes both (an older tab, as before); another
  value is a 400 before anything is read. An unknown phone is a 404 on every branch. Pinned by
  `a_switch_only_post_keeps_the_limits` (mutation 1);
- **no semantic change.** Days are still stored as `serde_json::to_string(&Vec<Option<DayWindow>>)`, apps and budgets
  as before; `start > end` (overnight) and `start == end` (24 h) are stored unchanged and only the summary's wording
  moved ("Every day", no "(next day)"). The policy builder is untouched; `time_policy` and `policy_json_keys_snapshot`
  pass unchanged. Lifts keep their durations, the 12 h budget delivery and the server expiry. `rule_days` is the old
  inline code moved (a rule whose days don't read still refuses any end). The badge still shows no end without the
  phone's `time_state.zone` (mutation 10 is caught on both the Devices list and Device › Time);
- **validation.** The only stricter rule is HH:MM with two digits each (choice 6): `<input type="time">` sends exactly
  that in every browser on the floor (no `step`, so never seconds), and the old page used the same input, so no real
  form is refused. The old parser took "8:00"; nothing but a hand-typed request sends it. Apps are still checked with
  `valid_package_name`, at most 50; limits 0-1440;
- **escaping.** Rule names, app labels from the phone, lift names and the delete sentence are askama-escaped on all
  four pages (pinned, mutation 8). The only inline script is the kind → calls link that §5.2 allows; it carries no data;
- **"Save never jumps to the top".** Every form on the six pages is in the redirect table, built from the rendered
  forms; each fragment is checked to be the element that holds its form and to equal the action's; the one auto-save
  (the Own rules switch) has a `<noscript>` Save (mutations 6, 7). Daily Limit and Device › Time carry their group's
  fragment in the action, so a refused limit renders at `#screen-time` with or without JS. The editor's Save is at the
  top and returns without a fragment (below);
- **DB errors.** Every read on the four pages is a 500 ("Couldn't read the time rules"), never an empty list; the
  device page's Time row says "Couldn't read" and logs;
- **texts.** Lift states and created times are local with the exact time in `title` (mutation 9), the report's age
  under Now, the lock as §3.10's badge; no package, UTC stamp or code outside an ⓘ.

On the choices the review was asked to look at:
- **(1) Save only at the top.** Right for the save itself: without JS the answer opens at the top, where Save is; with
  JS scroll-restore returns to where Save was pressed. Enter in any field (the name, a time, a switch inside the
  exceptions' `<details>`) submits through the form's first submit button, which is Save - nothing destructive is
  reachable that way (Delete is its own form outside), and `<summary>` only toggles. Two rough edges remain, both
  about what the parent sees after it (M2 for a refused app list, L1 for the rest).
- **(3) Own rules and limit visible while the switch is off.** Right, and clearly marked ("Used once Own rules is on.").
  Pause a Rule lists only the rules that apply, so nothing pauses a rule the phone doesn't run.
- **(4) The gone/over landings.** `gone_unsaved`, `gone` and `over` land on the right list or group with nothing
  written, and a lift id the phone never had stays a 404. One landing breaks: a pause of the *last* rule lands on a
  group that no longer exists (M1).
- **(5) Strict HH:MM.** Refuses nothing a browser's time input sends (above); the refusal's wording is off (L2).
- **(6) An unreadable stored rule opens in the editor.** Right: the old page had no way to delete it. The warning
  promises that saving fixes it, which is true for the phones, but the save silently writes whatever didn't read as
  empty (L3).

What is left:
- **M1:** pausing a rule that was the phone's last applicable one lands on `#pause`, which isn't rendered: the page
  opens at the top and the refusal is shown nowhere.
- **M2:** a refused app list (more than 50) shows its reason under the full list, at the bottom of a long page, and
  nothing scrolls to it - with or without JS.
- **L1:** the editor's only Save is not sticky, and after Enter in a lower field the "Saved." flash is off-screen.
- **L2:** a time that isn't HH:MM is refused as "give both times, or leave both empty".
- **L3:** saving an unreadable rule silently empties the parts that didn't read.
- **L4:** two guards are unpinned (mutations survived): End now's phone scope, and the Time row's "Couldn't read".

Counts: 0 High, 2 Medium, 4 Low, 5 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-9`), under `~/build.lock`.
- **`cargo test`, unchanged:** 829 passed, 0 failed.
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` dead fields, `dns_engine.rs`,
  `storytel.rs`, the `sort_by` in `devices.rs:1172`). None is in the step's code.
- **`node --test server/jstest/*.test.js`:** 23 passed.
- **The mutations.** Each was run with targeted filters (`time_pages`, `time_rules`, `schedules::`, `lifts::`,
  `devices_list`, `device_pages` as fitting), then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | `section=switch` also writes the limits (`(true, true)`) | `time_pages::a_switch_only_post_keeps_the_limits` |
  | 2 | `rule_in_scope` drops the scope guard (`Ok(Some(rule))`) | `time_rules::rule_edit_and_delete_are_scoped` |
  | 3 | End now's `UPDATE` ignores the phone (`(device_id = ? OR 1)`) | **nothing** (L4) |
  | 4 | HH:MM without the two-digit hour check | `schedules::tests::rule_form_validation` |
  | 5 | an overnight window stored as `min..max` (21:00-07:00 → 07:00-21:00) | `time_rules::device_override_uses_its_own_rules_and_budget` |
  | 6 | Extra Time's action without `#extra` | `time_pages::every_form_on_the_time_pages_returns_to_its_group` |
  | 7 | the Own rules switch's `<noscript>` Save removed | `every_form_on_the_time_pages_returns_to_its_group`, `device_time_holds_the_moved_card` |
  | 8 | a rule name `|safe` on Time Rules | `time_rules::a_rule_name_is_never_script` |
  | 9 | a running pause's "Until" in UTC (`end.format("%H:%M")`) | `time_rules::lift_status_comes_from_the_phone` |
  | 10 | the badge's end without the phone's zone (`if false && …` in `provable_end`) | `time_pages::device_time_shows_the_phones_state_in_local_time`, `devices_list::a_locking_rule_and_todays_screen_time_show_on_the_row` |
  | 11 | `done=gone`'s flash in `#extra` instead of `#pause` | `time_pages::device_time_holds_the_moved_card` |
  | 12 | the Time row's "Couldn't read" reads "Everyone's rules" | **nothing** (L4) |

  Two of twelve survived (L4).

## Medium

### M1. A pause of the phone's last applicable rule lands on a group that isn't there
`src/handlers/lifts.rs:92`, `templates/device_time.html:53-78`.

A pause of a rule that no longer applies (deleted in another tab, or the Own rules switch flipped) redirects to
`/devices/{id}/time?done=gone#pause`, and `done_flash("gone")` puts the red "That rule no longer applies to this phone.
Nothing was paused." into the Pause a Rule group. But that whole section, flash included, is rendered only
`{% if !pause_rules.is_empty() %}`. When the rule was the last one that applies (the phone's only own rule deleted, or
the switch turned on with no own rules yet - choice 3 makes the second likely), the section is gone: the fragment finds
nothing, the page opens at the top, and the refusal shows nowhere. The parent believes the pause went through. That is
the user's rule broken on the one landing that matters most (a refusal). `invalid_lifts_are_rejected` posts exactly
this case (a phone with no applicable rules) and checks only the `Location`; `device_time_holds_the_moved_card` checks
the `gone` flash with rules still present.

**Fix:** show the pause flash even without rules to pause: keep `<section id="pause">` when `done` is a pause code (or
always, with "No rules apply to this phone right now." as its row and no form). Pin it: in `invalid_lifts_are_rejected`,
follow the redirect and assert `section(&html, "pause")` holds `data-flash="pause"`.

### M2. A refused app list shows its reason at the bottom of a long page, and nothing scrolls to it
`templates/schedule_rule.html:78-93`, `src/handlers/schedules.rs:803-848` (`rule_entered`), `static/scroll-restore.js`.

Ticking more than 50 apps (a phone that reports many apps makes that easy) renders the editor again (400) with the
exceptions open and "Pick at most 50 apps. Nothing was saved." - but the flash sits *after* the `<details>`, below every
app row (51+ rows, some 2000 px on a phone), and no field is `autofocus`ed for this error (the name and day errors
are). So:
- without JS, the answer opens at the top (where Save is) and the reason is far below the fold;
- with JS, for a saved rule, scroll-restore puts the page back where Save was pressed - the top - at
  `DOMContentLoaded` and again at `load`, which also overrides any autofocus scroll;
- for a new rule the answer's path (`/schedules/rules`) differs from the page's (`/schedules/rules/new`), so nothing is
  restored and it opens at the top.

The parent sees the page come back unchanged at the top, with the Save button, and may think it saved. The status
text says "the exceptions open for theirs", which is true but doesn't put the reason in view. Mutation-wise
`a_refused_rule_renders_in_place_as_typed` only checks the `open` and the text.

**Fix:** put the apps flash directly under the `<summary>` row (above the list) - or beside the Save at the top, as
the refusal is about the whole form - and mark the summary `autofocus` (any focusable element takes it) with
`aria-describedby` on the error. Since Save is at the top, the cheapest robust answer is a short refusal line under
the title bar for every refusal ("Not saved: see Exceptions"), which is in view in all three cases. Pin the line's
position in the test.

## Low

### L1. The editor's only Save isn't sticky, and Enter in a lower field hides the "Saved." flash
`static/style.css:1869,2097` (`.title-bar`, `.title-bar-split`), `templates/schedule_rule.html:16-19,42`.

Choice 1 matches the RuleEditor mockup, but the mockup's header is a fixed navigation bar; here `.title-bar` scrolls
away. On a 390 px phone a parent who ticks apps at the bottom of an open exceptions list has to scroll the whole page
back up to save. And a save by Enter in, say, Sunday's time field returns (with JS) to Sunday's row, while the
"Saved." flash is in the first group at the top - out of view, so there is no visible confirmation.

**Fix:** `position: sticky; top: 0` with the page background on `.title-bar-split` (the floor, Chrome 80 / iOS 14,
has it). The flash can then sit in the sticky bar too (next to Save), which also answers M2's "in view in all three
cases".

### L2. A time that isn't HH:MM is refused as "give both times, or leave both empty"
`src/handlers/schedules.rs:766-773`.

Choice 6 says "8:00" is refused under its day, and it is, but with the same sentence as a day with one time:
"Friday: give both times, or leave both empty." For "8:00" + "09:00" (both given) that reads as wrong. No browser time
input sends it, so only a hand-typed or scripted request sees it - hence Low.

**Fix:** a second message when both fields are non-empty: "Friday: use times like 08:15."

### L3. Saving an unreadable rule silently empties the parts that didn't read
`src/handlers/schedules.rs:669-685` (`show_rule`), `templates/schedule_rule.html:23`.

A stored rule whose `days_json` doesn't parse opens with all seven days empty (`unwrap_or_else(|_| vec![None; 7])`),
one whose apps don't parse with no apps checked. The warning says "Save it again, or delete it." A parent who just
presses Save gets a rule that is never on ("Never") or exempts nothing, and is told "Saved." The phones stop getting
a 500, which is the point, but the rule they get is not the one the parent remembers. The warning also disappears on a
refused save of that rule (`refuse_rule` passes `corrupt = false`).

**Fix:** name what didn't read in the warning ("Its days couldn't be read: set them again before saving."), from
which `parse_*` failed; pass `corrupt` through `refuse_rule` (read the row once more, or carry a hidden flag).

### L4. Two guards are unpinned
`src/handlers/lifts.rs:157-161` (`end_lift`), `src/handlers/devices.rs:1381` (the Time row).

- **Mutation 3:** End now's `UPDATE … WHERE id = ? AND device_id = ?` with the phone check removed passed every test.
  The guard is correct (and older than this step), but the step rewrote `end_lift` and gave it a new landing; nothing
  checks that `/devices/{a}/lifts/{b's lift}/end` leaves the lift running and is a 404.
- **Mutation 12:** the Time row's "Couldn't read" (a failed rules read) replaced by "Everyone's rules" passed. It is
  the summary's only sign that the rules can't be read now that the card's error line is gone.

**Fix:** in `invalid_lifts_are_rejected` (or the End now test), create a running pause on the other phone, post End
now through this phone's URL, assert 404 and `ended_early_at IS NULL`. In `the_device_page_has_a_time_row`, rename
`time_rules` away and assert the row says `Couldn&#39;t read` (as `device_time_fails_loudly_and_works_before_setup`
does for the page).

## Nits

- **N1.** Device › Time computes `calls_enforced` with its own copy of `attention::facts`' expression
  (`schedules.rs:1302-1309` vs `attention.rs:536-540`), and the ledger flag is read twice in two ways
  (`TimeReport::ledger_unreadable` here, `t["ledger_unreadable"]` in `attention::facts`). Two copies of "when is a
  rule's calls-off real" can drift; one helper (or building `Facts` here) is less code.
- **N2.** "Saved. The phones get it within seconds when online." after saving a phone's own rule while Own rules is
  off: that phone doesn't use it yet. "Saved. Used once Own rules is on." in that case, as the Time page says.
- **N3.** `time_input_to_minutes` takes a sign: `"+8:00"` and `"-0:00"` pass the two-character check and
  `i64::from_str` accepts them (as 08:00 and 00:00). Harmless; `h.bytes().all(|b| b.is_ascii_digit())` closes it.
- **N4.** A pause of a gone rule from an older tab (no `back`) now lands on the device page with no word at all; it was
  a 400 with "That rule doesn't apply to this phone". Step 10's summary will show the flash; until then it is silent.
- **N5.** The device page's Time row turns "Needs attention" for the record and Vibb-too-old warnings, but not for the
  page's other red warnings - an unreadable own rule or own limit ("Stored limits unreadable"), which give the phone a
  500 on every policy poll. The phone's `policy_state` raises its own Attention row once it reports, so the signal
  isn't lost, but the row could say it too.
