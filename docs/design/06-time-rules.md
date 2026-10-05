# Step 6: named time rules, daily screen time, lifts, next-boundary alarm, location policy

PLAN "Next features": "School mode and time rules" and the "Battery" items on schedule timers and location. S =
kid-phone-server, L = kids-launcher-mdm, branch `handy`. Steps 1-5 guarantees are unchanged (fail closed, kiosk, call
rules, direct boot, PIN gates, the lock screen's Emergency call).

## Model

- **Rule** = `{id, name, kind: school|bedtime|custom, calls_allowed, exempt_apps: [pkg], days: [7 × {start,end}|null]}`,
  days Monday first, minutes 0-1439 local time. `start<end` = that day; `start>end` = ends next day; `start==end` =
  24 h from `start`. A rule is active at local date-time t if day(t) or day(t)-1 has a window covering t. Compared as
  local date-times, so DST needs nothing special (boundary instants: gap -> later offset, overlap -> earlier).
  Defaults when the parent adds one: school -> calls off; bedtime/custom -> calls on (step 4 behaviour).
- **Global rules + per-device override** (upstream's pattern): `time_rules.device_id` NULL = global; the existing
  `device_policy.custom_schedule_enabled` switches a device to its own rules **and** its own budget.
- **Daily budget**: `[7 × minutes|null]` (null = unlimited), global or per device like the rules.
- **Migration** (S startup, `time_rules::migrate_legacy`, once per row via `rules_migrated`; L the same pure function
  for a policy from an older server): bedtime `bs-be` -> rule "Bedtime" (bedtime, calls on, all 7 days). The allowed
  weekday/weekend windows become locked spans: if both are set, not wrapping, and every next-day start <= this day's
  end -> one custom rule "Outside allowed hours" with day D = `[end_D, start_D+1)`; otherwise two rules
  ("... (morning)" `[0, start_D)`, "... (evening)" `[end_D, 24:00)` or the wrapping-window gap `[end_D, start_D)`).
  Tested minute-by-minute over a week against the old `KidModeEnforcer` on both sides. Legacy columns are kept and still
  sent (frozen), so a launcher without `time_rules_v1` keeps the old schedule until it updates (restrictive).

## Wire (always sent by S; key snapshot test on both sides)

`time_policy: {rules: [...], daily_budget_minutes: [7], lifts: [{id, target: "rule"|"budget", rule_id|null, minutes,
expires_at_ms}]}` and `location_policy: {mode: "off"|"on_request"|"interval", interval_minutes}`. Status report adds
`time_state: {day, used_minutes, budget_minutes|null, extra_minutes, active_rule_id, active_rule_name, calls_blocked,
lock_reason, lifts_active: [id]}` (S stores it capped in `device_status.time_state_json`) and capability
`time_rules_v1`. `lock_reason`: NONE, BEDTIME, SCHOOL, RULE (custom), SCREEN_TIME (budget used up).

## Phone decisions (pure Kotlin, `timerules/`)

- **Lock** (`decideTimeLock`): override PIN or pause -> nothing. Else the active, un-lifted rules: reason from the
  strongest kind (school > bedtime > custom, then order), calls allowed only if every active rule allows them, usable
  apps = intersection of their exempt apps. No rule but budget used up -> SCREEN_TIME, calls allowed, usable apps =
  the messaging apps chosen for contacts (SMS app while SMS is on, Element X, Signal/Molly). Usable apps still follow
  the allowlist. During a lock everything else is suspended (not hidden) as in step 4; kiosk pins ours + usable.
- **Calls during a no-calls rule**: the call path uses `CallPolicyStore.effectiveState()` = managed rules with
  `callsEnabled = false`, so only emergency calls (and the emergency callback window) pass in or out, the phone book
  shows emergency contacts only and `DISALLOW_OUTGOING_CALLS` is set. SMS stays allowed by the platform (the SMS app is
  suspended; incoming texts aren't lost). Calls unmanaged: outgoing restricted, incoming can't be screened (gap).
  Before the first unlock the DE copy `boot_call_blocks` (only the no-calls windows, `v:1`) decides; missing or
  unreadable -> blocked (restrictive); lifts/override aren't seen there.
- **Fail closed**: a policy without `time_policy` on a phone that has had one is rejected (`time_policy_seen`, like
  `call_policy`). A rule with `days.size != 7` is always active; an out-of-range window covers its whole day; an
  unknown kind is custom; a budget array of the wrong size is 0 every day. `LastEnforcedPlan` carries rules + budget
  (no lifts); an unreadable plan is step 1's "nothing allowed".
- **Screen time** counts while the screen is interactive, the keyguard is unlocked, no call is going on (our in-call
  list or audio mode in call/communication - VoIP too) and none of our activities is resumed. Event-driven: screen
  on/off, user present, our activity lifecycle, audio-mode listener; while counting, a checkpoint every 60 s and a
  one-shot timer at the remaining budget. Nothing runs while the screen is off.
- **Ledger** (`ScreenTimeLedger`, CE prefs, `commit()`): `{day, used_ms, extra_minutes, anchor_wall, anchor_elapsed,
  boot, applied_budget_lifts}`. Within one boot the trusted clock is `anchor_wall + elapsed`; wall clock earlier than
  that (rollback) is ignored; later by > 5 min (jump) is accepted but carries today's usage over (no refill). Only the
  trusted clock passing midnight, or a new boot on a later date, starts a new day; the day never goes back.
- **Lifts**: rule lifts apply while listed in the policy and `timedWindowActive` (first seen: wall + elapsed + boot;
  length `min(expires_at - first seen, minutes)`; a reboot ends it). `rule_id` null = every rule. Budget lifts add
  `minutes` to the day they are first seen, once per id.

## Timing

No more 60 s polling (CommandListenerService, Home, LockActivity). `TimeRuleAlarm` sets one exact alarm
(`setExactAndAllowWhileIdle`, `USE_EXACT_ALARM`; inexact fallback) at the next boundary: rule starts/ends over the next
8 days, local midnight when a budget is set, rule-lift expiries. Re-evaluated on the alarm, `TIME_SET`,
`TIMEZONE_CHANGED`, `BOOT_COMPLETED`, process start after unlock, screen on / user present, and every accepted policy.

## Location

`device_policy.location_mode` (default `on_request`) + `location_interval_minutes` (10-240, default 30). off: no
location in reports, a `locate` command answers "location off"; on_request: a fresh fix only for `locate`/`ring`;
interval: a fresh fix when N minutes have passed, else the cached fix. Locate page: policy form, "Update location now"
(queues `locate`, which already bypasses the 10-min throttle), last fix age/accuracy, the page waits for the new fix,
and the outdated "couple of minutes" text now says commands arrive within seconds when the phone is online.

## Server UI

`/schedules` -> "Time rules": rule list + add/edit/delete (name, kind, calls, exempt apps from the devices' reported
apps, 7 start/end pairs), budget form; same per device behind the override switch. Device page card: active rule,
screen time used/budget, lift form (rule or all rules for 15/30/60/120 min; +15/30/60 min screen time), recent lifts
with "End now" for active rule lifts, warning when the launcher lacks `time_rules_v1`. Every change nudges the
affected devices (global edits: all). Lifts are recorded in `time_lifts` and the security log.

## Implementation status (2026-10-05)

Done on `handy` (not pushed). S: `2efa112` (migration 0025, `time_rules.rs` + startup migration, `time_policy`/
`location_policy`, `time_state`, Time rules page, device card + `handlers/lifts.rs`, locate page), `c86a49e` CLAUDE.md.
L: `e06c549` (`timerules/`, alarm, tracker, call path, lock screen nb/en, location policy, tests), `e1661d7` CLAUDE.md.
Tests: server 100 (was 80), fmt clean, clippy unchanged (25); launcher 234 JVM tests (was 194), `assembleDebug` +
`assembleRelease` build. No emulator here: Android parts are build-checked only.
Choices: override/pause lift the time rules including school's call block (never beyond the call rules); a rule's
exempt apps stay usable when the budget is also used up; budget lifts can't be taken back, rule lifts can ("End now");
budget lifts are delivered for 12 h and count for the phone's day; migrated rules get English names (parent renames);
location intervals 10/15/30/60/120/240 min; a launcher on an old server converts the windows itself (empty names ->
localized kind label). Open: incoming calls during school with calls unmanaged; usage between checkpoints is lost
on a process death (<= 60 s); lift records and the ledger are CE-only (a reboot ends rule lifts).

## Device checklist (Jelly Star, release; plus 02/04/05 checklists)

1. Upgrade with an existing bedtime/weekday schedule: the server shows the migrated rules; the phone locks at the same
   times as before (spot-check 1 min before/after each edge), lock screen shows time + localized name.
2. School rule Mon-Fri now: lock screen within a second of the edge with the screen on and off (`dumpsys alarm | grep
   kidslauncher` shows one exact alarm at the next edge, no wakeups in between); B (allowed contact) calls in ->
   rejected, no ring; calling B from anywhere -> blocked; 112 test mode works from the lock screen; exempt calendar opens.
3. Reboot during school, don't unlock: B rejected (DE `boot_call_blocks`); after unlock still school.
4. Bedtime with Vibb exempt: audio keeps playing with the screen off; phone book calls B.
5. Budget 5 min: use an app -> locks at ~5 min, lock screen says used up, phone book + Element X still open; time on
   Home, lock screen, in calls (also Element Call) doesn't count; reboot keeps the count; with debugging allowed,
   `adb shell date` back a day doesn't refill (set auto-time on again after).
6. PWA "End school for 30 min" arrives within seconds (push), ends by itself at 30 min (screen off at the time ->
   locked on screen-on), "End now" re-locks at once; "+15 min" adds 15 to today only.
7. Time zone change and manual time set (adb) move the next alarm; midnight resets the budget.
8. Location off: no indicator ever, locate answers "location is off"; on request: indicator only after "Update
   location now", the page shows the new fix with age/accuracy; every 30 min: fixes ~30 min apart in the trail.
9. Override PIN during school: calls to B work again, everything re-locks when it ends (alarm at the end time).
