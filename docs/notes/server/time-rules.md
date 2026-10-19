# Server notes: time-rules

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Time rules, screen time, location policy (handy step 6, design `docs/design/06-time-rules.md`, at the monorepo root)

- **Rules** (`src/time_rules.rs`, migration `0025`, table `time_rules`): name, kind (`school`/`bedtime`/`custom`), `calls_allowed`, `exempt_apps_json` (package names usable during the rule), `days_json` = 7 entries Monday first, `{start,end}` minutes 0-1439 or null; `start > end` ends the next day, `start == end` lasts 24 h (the phone's local time). `device_id` NULL = global; a device with `device_policy.custom_schedule_enabled` uses its own rules **and** its own `daily_budget_json` instead of the global ones (`global_schedule.daily_budget_json`), 7 entries, minutes or null = unlimited. The phone evaluates everything (DST, overnight, lock screen); the server only stores, validates and serves.
- **Legacy conversion**: `time_rules::migrate_legacy`, called from `connect_db` right after the SQL migrations, converts each `global_schedule`/`device_policy` row with `rules_migrated = 0` once (one transaction per row): bedtime -> a bedtime rule on all 7 days; the allowed weekday/weekend windows -> one custom rule "Outside allowed hours" `[end_D, start_D+1)` when every day has a non-wrapping window and no locked span exceeds 24 h, else a "(morning)" and an "(evening)" rule. Calls stay allowed (step 4 behaviour). Unit tests compare the old launcher logic and the new semantics minute by minute over a week. The legacy columns stay and are still sent, frozen, so a launcher without `time_rules_v1` keeps the pre-migration schedule (the device page warns).
- **Wire**: `PolicyResponse.time_policy = {rules, daily_budget_minutes, lifts}` and `location_policy = {mode, interval_minutes}`, always sent (the launcher rejects a response without `time_policy` once it has had one). A stored rule/budget that doesn't validate (bad JSON, not 7 entries, minute out of range, unknown kind, bad package name) is a 500 (`PolicyError::CorruptTimePolicy`), never fewer rules. Status reports carry `time_state` (active rule, `used_minutes`, `budget_minutes`, `extra_minutes`, `calls_blocked`, `lock_reason` NONE/BEDTIME/SCHOOL/RULE/SCREEN_TIME, `lifts_active`), stored capped (object, ≤ 4096) in `device_status.time_state_json`, and capability `time_rules_v1`.
- **Admin UI** (`handlers::schedules`; laid out as design 31's Settings › Time Rules and Device › Time since step 9, "Time Rules and Device › Time" below): global rules (add/edit/delete, `POST /schedules/rules`, `/schedules/rules/{id}`, `/schedules/rules/{id}/delete`, every form carries `scope` = "" or a device id and a rule outside that scope is a 404), global budget (`POST /schedules/global`), and per device the override switch + budget (`POST /schedules/device/{id}`, `section=switch|budget`) and its own rules. Exempt apps are switches from the devices' latest reported installed apps (plus already chosen ones). The kind select pre-sets the calls switch (school: off). A refusal renders its page again (400, nothing written), 500 on DB errors; a global change nudges every device, a device change that device.
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
  min / +1 h tiles), **Pause a Rule** (rule and length pickers, only with rules to pause), **Recent** (the 10 newest
  lifts, `lifts::recent`, statuses local - `lifts::LiftState` -, End now), **Rules** (the own-rules switch, its own
  form, and while off a row to everyone's rules), **Own Rules** (rows to the editor, Add Rule), **Own Daily Limit**. A
  phone not set up has the page. Every read is a 500 on a DB error, never an empty list.
- **Where forms return** (§5.1, `tests::time_pages::every_form_on_the_time_pages_returns_to_its_group`, built from the
  rendered forms): a rule's save → `/schedules/rules/{id}?saved=1#rule` (the form: Save is at the top), a new rule →
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
