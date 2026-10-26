# Server notes: time-rules

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Time rules, screen time, location policy (handy step 6, design `docs/design/06-time-rules.md`, at the monorepo root)

- **Rules** (`src/time_rules.rs`, migration `0025`, table `time_rules`): name, kind (`school`/`bedtime`/`custom`), `calls_allowed`, `exempt_apps_json` (package names usable during the rule), `days_json` = 7 entries Monday first, `{start,end}` minutes 0-1439 or null; `start > end` ends the next day, `start == end` lasts 24 h (the phone's local time) - since design 66 only a phone's **Bedtime** crosses midnight (below), a rule set's windows start and end on their day (23:59 = until midnight). `device_id` NULL = global; a device with `device_policy.custom_schedule_enabled` uses its own rules **and** its own `daily_budget_json` instead of the global ones (`global_schedule.daily_budget_json`), 7 entries, minutes or null = unlimited. The phone evaluates everything (DST, overnight, lock screen); the server only stores, validates and serves.
- **Legacy conversion**: `time_rules::migrate_legacy`, called from `connect_db` right after the SQL migrations, converts each `global_schedule`/`device_policy` row with `rules_migrated = 0` once (one transaction per row): bedtime -> a bedtime rule on all 7 days; the allowed weekday/weekend windows -> one custom rule "Outside allowed hours" `[end_D, start_D+1)` when every day has a non-wrapping window and no locked span exceeds 24 h, else a "(morning)" and an "(evening)" rule. Calls stay allowed (step 4 behaviour). Unit tests compare the old launcher logic and the new semantics minute by minute over a week. The legacy columns stay and are still sent, frozen, so a launcher without `time_rules_v1` keeps the pre-migration schedule (the device page warns).
- **Wire**: `PolicyResponse.time_policy = {rules, daily_budget_minutes, lifts}` and `location_policy = {mode, interval_minutes}`, always sent (the launcher rejects a response without `time_policy` once it has had one). A stored rule/budget that doesn't validate (bad JSON, not 7 entries, minute out of range, unknown kind, bad package name) is a 500 (`PolicyError::CorruptTimePolicy`), never fewer rules. Design 66 (no contract change): every window ending at 23:59 is served with `end: 0` (`time_rules::served_days`, also the badge's `time_state::rule_days`; `{0, 23:59}` = `{0, 0}`, the whole day), and the phone's bedtime is one more `rules[]` entry, last, `{name: "", kind: "bedtime"}` with `start > end` windows (the phone names it "Leggetid"). Status reports carry `time_state` (active rule, `used_minutes`, `budget_minutes`, `extra_minutes`, `calls_blocked`, `lock_reason` NONE/BEDTIME/SCHOOL/RULE/SCREEN_TIME, `lifts_active`), stored capped (object, ≤ 4096) in `device_status.time_state_json`, and capability `time_rules_v1`.
- **Admin UI** (`handlers::schedules`; laid out as design 31's Settings › Time Rules and Device › Time since step 9, "Time Rules and Device › Time" below): global rules (add/edit/delete, `POST /schedules/rules`, `/schedules/rules/{id}`, `/schedules/rules/{id}/delete`, every form carries `scope` = "" or a device id and a rule outside that scope is a 404), global budget (`POST /schedules/global`), and per device the override switch + budget (`POST /schedules/device/{id}`, `section=switch|budget`) and its own rules; the phone's Bedtime on its own page (`handlers::bedtime`, below). Exempt apps are switches from the devices' latest reported installed apps (plus already chosen ones). The kind select pre-sets the calls switch (school: off). A refusal renders its page again (400, nothing written), 500 on DB errors; a global change nudges every device, a device change that device.
- **Lifts** (`handlers::lifts`, table `time_lifts`): Device › Time (the device page's "Time rules" card until design 31 step 9) - pause one rule or all rules for 15/30/60/120 min (`POST /devices/{id}/lifts`, `target=rule`, `rule=all|id`, the rule must be one of the device's effective rules - one that no longer is lands back with nothing written), or `+15/30/60` min screen time (`target=budget`). Rule lifts are delivered until `created + minutes`, budget lifts for 12 h (the phone adds them once, to the day it first sees them). "End now" (`POST /devices/{id}/lifts/{lift_id}/end`) stops delivering an active rule lift (`ended_early_at`; a pause already over lands back, one the phone never had is a 404); added screen time can't be taken back. Both write a `security_events` row (`time_lift`, `time_lift_ended`) and nudge. `back=time` returns to Device › Time, anything else to the device page (§5.1).
- **Location policy** (`device_policy.location_mode` off/on_request/interval, default `on_request`; `location_interval_minutes` 10/15/30/60/120/240, default 30): set on the Locate tab's Location Settings (`POST /devices/{id}/location-policy`, mode alone keeps the interval; "Every" shows only for Regularly). Update Location (`POST /devices/{id}/command/locate`, back to `#location`) queues `locate` (a fresh fix past the phone's throttle) and the page then polls `locations.json` for up to a minute for a newer fix; the page shows the last fix's age and accuracy ("10 min ago", "Within 40 m", `locate::Fix`).
- **Fix round after `qa-step6-code.md`**: the device card and `/schedules?device=` warn when a calls-off rule applies while calls are unmanaged (incoming calls still ring); rule-lift status comes from the phone's `time_state.lifts_active` (since design 31 step 9 the typed `lifts::LiftState`: "Until 10:42" / "Ended on the phone" / "Waiting for the phone", local time), the server expiry is secondary; `time_state.ledger_unreadable` shows "today's budget counts as used up"; the Locate tab shows the phone's answer to the newest `locate` (`device_commands.result`, reworded; also `GET /devices/{id}/locate-result.json` for the page's wait, with the reworded `text`) and says "off" switches location off on the phone; rule create/update/delete, budget saves and location-policy changes are in the security log (`time_rule_*`, `screen_time_saved`, `location_policy_changed`). The override PIN text says it lifts the time rules incl. school's call block, never calls beyond the call rules, never hardening (user decision on QA item 4). Tests: unauthenticated POSTs to every new admin route write nothing.

## Time Rules and Device › Time (design 31 step 9, `docs/design/31-pwa-redesign.md` §3.5, §8)

- **Settings › Time Rules** (`GET /schedules`, `schedules.html`, links only): everyone's rules as rows ("Skole" over
  "Mon-Fri 08:15-14:30 · calls off", `schedules::days_summary`; a rule that doesn't read says "Can't be read" and still
  opens), Add Rule, the Daily Limit row ("Mon-Fri 90 min · Sat-Sun 2 h", `budget_summary`), a row per phone
  ("Everyone's rules" / "Own rules") to its Time page. `/schedules?device=X` (an old bookmark) redirects to
  `/devices/X/time` (L5). **Daily Limit** (`GET /schedules/screen-time`, `schedule_budget.html`): the 7 fields
  (`partials/budget_rows.html`, also Device › Time's) as one form.
- **The rule editor** (`GET /schedules/rules/{id}` chained with its POST, `GET /schedules/rules/new` and
  `?device=X` for a phone's own; `schedule_rule.html`): one form, Save at the top (the RuleEditor mockup), Name, Type
  as an inline select, "Calls with contacts", the 7 day rows, Exceptions as a `<details>` inside the form (H1: the
  handler writes the exempt apps with every save); Delete Rule… a `<details>` confirm outside it. A stored rule that
  doesn't read opens with what does and a warning (saving it again fixes it). Its tab is Settings, also for a phone's
  own rule (its back link goes to the phone's Time page).
- **Device › Time** (`GET /devices/{id}/time`, `schedules::view_device_time`, `device_time.html`; the device page's row
  "Time · Everyone's rules / Own rules", "Needs attention" (orange) while the page shows the record or Vibb-too-old
  warning, "Couldn't read" on a failed read): warnings (Vibb too old for time rules, the screen-time record unreadable,
  incoming calls not blocked), **Now** (the Time lock as §3.10's badge - `time_state::lock_badge`, so the end shows
  only when provably right, which needs the phone's `time_state.zone` - sent since design 34 P13 -, today's screen time
  with the extra minutes, the report's age with the exact local time in `title`), **Extra Time Today** (+15 min / +30
  min / +1 h tiles; above them "Asked for more time at 17:21" while the kid's request is open - design r13,
  `notifications.md`, "Requests for more time"), **Pause a Rule** (rule and length pickers, only with rules to pause), **Recent** (the 10 newest
  lifts, `lifts::recent`, statuses local - `lifts::LiftState` -, End now), **Bedtime** (design 66, below), **Rules** (the own-rules switch, its own
  form, and while off a row to everyone's rules), **Own Rules** (rows to the editor, Add Rule), **Own Daily Limit**. A
  phone not set up has the page. Every read is a 500 on a DB error, never an empty list.
- **Where forms return** (§5.1, `tests::time_pages::every_form_on_the_time_pages_returns_to_its_group`, built from the
  rendered forms; the Bedtime page's in `the_bedtime_page_adds_saves_refuses_and_removes_in_place`): the bedtime's
  Add/Save → `/devices/{id}/bedtime?saved=1#bedtime` (the form, Save at its top), Remove →
  `/devices/{id}/time?notice=bedtime_removed#bedtime`; a rule's save → `/schedules/rules/{id}?saved=1#rule` (the form: Save is at the top), a new rule →
  its page `?saved=added#rule`, a delete → the list it was in (`/schedules?notice=deleted#rules`,
  `/devices/{id}/time?notice=deleted#own-rules`); a save or delete of a rule deleted in another tab lands on that list
  (`notice=gone_unsaved|gone`), nothing written. Daily Limit → `/schedules/screen-time?saved=1#screen-time`. On Device
  › Time each form carries its group's fragment: the lifts (`back=time`) → `?done=extra|pause|gone#extra|pause`, End
  now → `?done=ended|over#lifts` (`lifts::done_flash`); the switch → `#rules`, the limits → `?saved=budget#screen-time`.
- **`section` on `/schedules/device/{id}`** (§5.6, H1): `switch` writes only `custom_schedule_enabled`, `budget` only
  `daily_budget_json` (`COALESCE` of a NULL bind keeps the other), none both (an older tab), another value 400. Pinned
  by `tests::time_pages::a_switch_only_post_keeps_the_limits`.
- **Refusals in place**: a rule (no name, a day with one time or a time that isn't `HH:MM`, more than 50 apps) or a
  limit (outside 0-1440) renders its page again (400) as typed (escaped, cut short), the reason under the field, which
  is `autofocus`ed and `aria-invalid`; the editor also shows it in its sticky top bar beside Save, where a save's flash
  shows too (qa-31-s9-code M2, L1); an unreadable stored rule's warning names the parts that will be saved empty;
  a refused pause (the rule no longer applies) always lands on Device › Time's Pause a Rule, which then shows even
  with no rule left; an unknown kind or another phone's rule stays a plain 400 / 404 (no page sends one). Times must be
  `HH:MM` (what `<input type="time">` sends).
- **Attention**: the screen-time record and Vibb too old for time rules → `/devices/{id}/time` (the warnings at its
  top); incoming calls not blocked → Calls & Contacts. `TimeReport` now also reads `extra_minutes` and
  `ledger_unreadable`; `time_state::rule_days` gives the badge its rules (the Devices list's too).
- Tests: `src/tests/time_pages.rs` (the four pages, the redirect table, the section test, refusals, local times and
  the zone-less badge, failed reads, the device page's row), repointed `time_rules.rs` (redirects, lift statuses in
  local time, the escaped rule name on every page, the warnings on Device › Time and as Attention rows),
  `devices_list.rs`, `shell.rs`; `lifts::tests`, `schedules::tests`, `attention::tests`.

## Bedtime (design 66, `docs/design/66-bedtime.md`)

- **Model**: a phone's bedtime is its one `time_rules` row with `bedtime = 1` (migration 0101, unique per phone by
  `idx_time_rules_bedtime`; never on everyone's rules): kind `bedtime`, name "Bedtime" (the lift's `rule_name`, Pause
  a Rule's picker), `calls_allowed`, `exempt_apps_json`, and the days from two rows (`time_rules::Nights`): Sun-Thu
  nights on days 6, 0-3, Fri-Sat nights - the nights before Saturday and Sunday - on days 4 and 5; an empty row is
  `null` on its days. It is outside the rule sets: `rule_set_rows(db, None | Some(d))` is a set alone (`bedtime = 0`;
  Settings › Time Rules' list, Device › Time's Own Rules), `effective_rule_rows` the set in force plus the bedtime,
  last (the policy, lifts, the badge, the summary, Attention, Pause a Rule) - the Own rules switch never adds or
  drops it.
- **Day rules stay inside one day**: the editor refuses an end at or before the start ("{Day}: the end must be after
  the start. For a night, use Bedtime."), except 23:59-23:59 ("23:59 to midnight"); `parse_days` (the policy read)
  still takes any window. The Type select offers School and Other (Bedtime only on a stored day rule of that kind).
  The editor never handles the bedtime row: `show_rule` answers it with a 303 to `/devices/{id}/bedtime` (a rule
  converted in place keeps its id), `rule_in_scope` with a 404 (save, delete).
- **Conversion at startup** (`time_rules::migrate_crossing`, from `connect_db` right after `migrate_legacy`; at every
  start, no marker, idempotent - a restored older backup is converted at its first start): an end of 00:00 becomes
  23:59 (served the same); per phone (one transaction each) a phone on Own rules gets its first bedtime-shaped rule
  (kind bedtime before custom, every window 12:00+ to before 12:00, Sun-Thu and Fri-Sat each equal) as its bedtime in
  place; everyone's first bedtime-shaped rule (one transaction) is copied into every phone that follows everyone's
  rules, their lifts on it repointed, the global row deleted - unless no phone follows them or one has a bedtime;
  everything else that still crosses is split exactly at midnight (the row keeps its id and runs each crossing day to
  23:59, "<name> (morning)" takes `{0, end}` the next day). A phone with a bedtime never gets a second. A row whose
  days or apps don't read is skipped with a warning, never a failed start. A 24 h window at 23:59 is read as "23:59 to
  midnight", the one inexact case. Logged `time_rule_to_bedtime` / `time_rule_split` (no actor). Pinned by
  `time_rules::tests::crossing_rules_convert_exactly` (every minute of a week, before and after, and a second run) and
  `a_stopped_conversion_ends_in_the_same_rows`, and `tests::time_rules` on a database.
- **The Bedtime page** (`GET /devices/{id}/bedtime`, `handlers::bedtime`, `device_bedtime.html`, tab Devices): one
  form like the rule editor (Save in the sticky bar with its flash and a refusal's reason), the two rows (`n0_*`
  Sun-Thu, `n1_*` Fri-Sat; each both times or neither, the evening 12:00-23:59, the morning 00:00-11:59, not both
  empty), Calls with contacts, Exceptions (`partials/exceptions.html`, shared with the rule editor), Remove Bedtime… as
  a `<details>` confirm outside the form. A phone without one opens at 21:00-07:00 both rows, calls on, **Add**,
  nothing stored. Add and Save are one upsert (`ON CONFLICT(device_id) WHERE bedtime = 1`), so two saves of a new
  bedtime make one row. A refusal renders the page again (400) as typed, the reason under its row (`autofocus`,
  `aria-invalid`) and in the bar. A stored bedtime that doesn't read opens with the rows empty and a warning; a save
  rewrites all seven days. Unknown phone 404, DB error 500; a save or remove nudges the phone and is logged
  (`bedtime_saved`, `bedtime_removed`).
- **Where it shows**: Device › Time's Bedtime group between Recent and Rules (`#bedtime`: "Bedtime" over "Sun-Thu
  21:00-07:00 · Fri-Sat 22:30-09:00" / "Every night 21:00-07:00" / "Not set" / "Can't be read" in `text-alarm`; the
  remove's flash), and each phone's sub-line on Settings › Time Rules ("Bedtime …", "No bedtime", "Bedtime can't be
  read"). The phone: `ruleLabel(kind, "")` gives "Leggetid" and the moon; no launcher change.
