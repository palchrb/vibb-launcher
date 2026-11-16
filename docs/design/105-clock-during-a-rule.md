# 105 - The clock during a rule

Round `clock-in-rule`, 2026-11-16. Launcher only. Approved by the user ("Ja gjør det"); PLAN "The clock opens from
Recents during a rule".

## 1. The bug and its cause (checked against the code)

On the Jelly Star (Android 16, android mode, kiosk on), during Skole, the kid pressed Recents on the mode screen and
picked Fossify Clock. The clock opened and stayed open.

This is how the code was built to behave:
- The schedule lock spares the clock apps from suspension, so an alarm set inside bedtime still rings (QA step 4 #2).
  The spared set is `alarmAppPackages` (`server/AppEnforcer.kt:195`): every system `SHOW_ALARMS` handler, plus Fossify
  Clock with its pinned signer (`sparedAlarmApps`, `EnforcementPlan.kt:419`). See also `EnforcementPlan.kt:223`.
- While allowed, the clock apps are also pinned in the kiosk during the lock (`EnforcementPlan.kt:238-239`, bug
  2026-11-02). Without the pin, lock task refused the ringing screen's new task, so the alarm rang with no Stop.
  The plan's doc says it outright (`EnforcementPlan.kt:121-124`): "The kid can reach the clock app ... during the rule
  through it ... - accepted". The user now withdraws that acceptance.
- Every other app is suspended, so its Recents thumbnail can't be started.

Nothing re-fronts the time-rule screen: `LockActivity` (`ui/LockActivity.kt`) has no loss-of-front check. The re-front
in `lock/RefrontPolicy.kt` belongs to the PIN lock while it is LOCKED. The launcher also has no way to read which app is
in front: it holds no usage access and no accessibility service, and none should be added.

## 2. The mechanism: pin the clock only while it may ring

During a **rule**, the allowed clock apps leave the kiosk's lock-task list. They come back only for an **alarm
window**, and leave again when the window closes. The clock stays spared from suspension at all times, so alarms
still fire and sound exactly as today.

Why this mechanism:
- **The platform enforces it.** Outside the window, Android refuses every way into the clock under lock task: a
  Recents thumbnail, a notification tap, the status bar's next-alarm, `SHOW_ALARMS` from any app.
- **AOSP closes what is open.** When a package leaves the list, AOSP clears its locked task
  (`LockTaskController.updateLockTaskPackages`, "terminate locked tasks that have recently lost allowlist
  authorization"). We already rely on this in `lock-and-glance.md` ("removing a package clears its task") and in the
  SOS narrowing (`LockTaskHelpers.kt:224`). So a clock left open after its ring, or opened during the window, is
  closed when the window ends. The mode screen (or the glance) is in front again with no re-front code.
- **It needs no front-app reading** and adds no gesture rule.
- **There is a precedent.** `LockTaskChrome` already takes runtime pin inputs outside the plan: `sosYield`
  (`lock/LockTaskChrome.kt:83`) and `routeWindow` (`:104`). The clock window is a third one of the same shape.

Rejected: **re-fronting the mode screen** when the clock comes to front (without front-app access we can't tell the
clock from an allowed exempt app reached from Recents or the shade); **suspending the clock** (a suspended app "will
not ring the device" and its notifications are hidden - QA step 4 #2's reason for sparing it); **taking Recents away**
(the user's say, section 4; it also leaves the shade and the next-alarm taps open).

### 2.1 Who is pinned when (pure, `computeEnforcementPlan`)

- `locked` (`EnforcementPlan.kt:198`) **and the lock has a rule** (a new input `ruleLocked`, AppEnforcer passing
  `lock.rules.isNotEmpty()` next to `lockUsableApps` at `AppEnforcer.kt:466`; `TimeLock`, `TimeRules.kt:201`): the
  kiosk list drops `allowed ∩ alarmApps`, unless a package is also in `lockUsableApps` (ticked on every active rule -
  then it stays pinned and gets its row, as any exempt app). These packages go into a new plan field
  `ruleClockPins = (allowed ∩ alarmApps) - lockUsableApps`.
- **The used-up budget alone** (SCREEN_TIME, no rule) is unchanged: the clock stays pinned. The user asked about
  rules, and the budget lock has no exempt list a parent could tick. Without this, a kid out of screen time couldn't
  set tomorrow's alarm.
- **Not locked, the pause, the override** (`locked` false) **and the phone's Foreldrekode pause** (the rule lifted,
  so no rule in the lock): unchanged. The clock is pinned while it is allowed.
- **Suspension is unchanged** (`EnforcementPlan.kt:223`).
- **A clock that isn't allowed** is hidden, as today.

### 2.2 The window (pure, a new `ruleClockWindow` in `lock/RefrontPolicy.kt`)

The window is open when **any** of these holds:
- **The notification listener isn't connected** (`AlarmNoticeReader.listening()`). Today's behaviour stays: without
  the listener no ring is seen, and the card offers the clock as its row (`lockRowPackages`, `TimeLockModel.kt:202`).
  The PWA already gets `notification_listener_enabled` in the report.
- **A clock rings and alarms aren't silenced.** "Rings" means a lock clock package has a ringing-shaped notification
  posted (`AlarmNotices.ringing`), with no time bound and whether consumed or not. Alarms are silenced when
  `ZenRuleRuntime.silencesAlarms()` (`ZenRuleRuntime.kt:153`) is true. This covers the alarm row's condition
  (`alarmToStop`, `PinLockRuntime.kt:1389`), so the row's tap (`openRingingAlarm`) and the screen re-sends always find
  the clock pinned.
- **A lock clock's next alarm clock is near and alarms aren't silenced.** The trigger time and creator are as
  `rememberAlarm` reads them. "Near" means `now` in `[trigger - ALARM_PIN_LEAD_MS, trigger + ALARM_RING_MS)`, with
  `ALARM_PIN_LEAD_MS` = 2 min and `ALARM_RING_MS` from `RefrontPolicy.kt:222`. This is the pre-pin: the ringing
  screen's first full-screen launch finds the clock already allowed. That launch is the bedtime morning alarm with the
  screen off, which has no second chance until a power press.

Glue: a small `RuleClockWindow.follow(context)` computes the window and calls `LockTaskChrome.clockWindow(open)` only
on a change. That is one pass under the monitor with no PackageManager work, as `sosYield` does. `lockTaskWhileLocked`
(`LockTaskHelpers.kt:205`) takes the plan's `ruleClockPins` as part of the kiosk list in the not-LOCKED rows and the
kiosk-on LOCKED row while the window is open. The SOS-narrowed row and kiosk off are unchanged.

`follow` runs from `AlarmNoticeReader`'s listener (a ring starts or stops, the listener connects or disconnects),
`ZenRuleRuntime`'s listener, `ACTION_NEXT_ALARM_CLOCK_CHANGED`, `TimeRulesRuntime.recheck` (`:287`) and every
`applyPlan`.

`nextBoundary` (`TimeRulesRuntime.kt:241`) adds `trigger - lead` and `trigger + ALARM_RING_MS` while a rule locks.
These go through the same `setExactAndAllowWhileIdle` boundary alarm. Our boundary may arrive late in Doze, or the next
alarm clock may be another app's, which hides a clock's alarm behind it. Either way the clock's own launch can be
refused. **The fallback:** when a ring opens the window, `PinLockRuntime` asks the resumed screen in charge to run its
settle-send once. That screen is the PIN lock or glance while LOCKED, or `LockActivity`'s `alarmScreenSend`
(`LockActivity.kt:78`). The send itself is the existing `sendAlarmScreen` (`PinLockRuntime.kt:1364`): capped,
never over a call, and SCREEN_ON in the dark.

### 2.3 The cases

| Case | What happens |
|---|---|
| Alarm fires while the kid is in an allowed exempt app | Pre-pinned. Android shows the alarm as a heads-up (screen on). Its tap opens the ringing screen. The exempt app is never interrupted by us. Without the pre-pin, the tap works once the ring's pass has landed (well under a second). |
| The snooze/dismiss screen | Pinned while the notification rings. Stop or Snooze removes it, the window closes, AOSP clears the clock's task, and the mode screen or glance is in front. A snooze's new alarm clock opens the window again 2 min before it. |
| A timer finishing | AOSP's and Google's timer has the ringing shape, so the ring opens the window, followed by the fallback send. Fossify's timer has no full-screen intent (`CATEGORY_EVENT`). It is stopped from its notification action or the glance's Stopp (`ClockActions`, a broadcast) and needs no pin. Its content tap is refused: that would be opening the clock. |
| The alarm screen stays after the ring stops | Once the notification is gone and `trigger + ALARM_RING_MS` has passed, the window closes and AOSP clears the task. |
| A silent rule silences alarms (a silent rule that isn't a bedtime) | The window stays closed, as the lock already treats such an alarm (`alarmRingingNow`'s `alarmsSilenced`). Android suppresses the full-screen intent itself. If the rule ends mid-ring, the listener's `follow` opens the window. |
| Bedtime vs. school | The same rule for both. Bedtime's default "Silent with alarms" doesn't silence alarms, so the window works. See Q1 for the cost at bedtime. |
| The parent's pause or override | `locked` is false, so the clock is pinned while it is allowed, as today. |
| Android mode vs. own mode | Both use the kiosk list. In own mode the PIN lock's "alarm" yield still lets the ringing screen through (pinned by the ring). Own mode with the kiosk off pins `pinLockHelpers`' clock while LOCKED, unchanged. |
| The clock ticked on the rule | It is in `lockUsableApps`: pinned all through the rule, with a row on the card. |
| The rule begins with the clock open | AOSP clears the clock's task at the first pass, as with every other app at a rule's start. |

**Residual: kiosk off.** No lock task means the clock is spared and reachable during a rule, as today. Q2 asks the
user about it.

## 3. Compatibility (Android 14-17)

- **Lock-task task clearing on list removal.** In AOSP `LockTaskController` since Android 9 and unchanged through 16.
  The SOS narrowing relies on it. To check on API 37.2 (the emulator check).
- **A full-screen intent of an unpinned package under lock task is refused.** Seen on 16 (bug 2026-11-02). The
  pre-pin removes the dependency on timing. With the screen on and the phone in use, Android 14-17 all show a
  full-screen intent as a heads-up. Alarm apps keep `USE_FULL_SCREEN_INTENT` on 14+.
- **The boundary alarm** is the existing `USE_EXACT_ALARM` path. `setAlarmClock` brings the device out of idle shortly
  before the clock's alarm on every version, so our earlier boundary lands as well as a rule edge does.
- **No API above 34. No feature bits change.** `featuresWhileLocked` (`LockTaskHelpers.kt:145`) is untouched.

## 4. Recents and the lock-task features

Today the kiosk takes the server's features unchanged while unlocked, OVERVIEW included. Design 16's drop of OVERVIEW
was reverted in 16d by the user's rule, and the recents provider is pinned as a helper instead (`EnforcementPlan.kt`
doc, "The lock-task features are the server's"). While LOCKED, `featuresWhileLocked` removes NOTIFICATIONS, HOME and
OVERVIEW.

Turning OVERVIEW off during a rule would close only the Recents path. The shade's clock notifications and the status
bar's alarm would stay open. It would also remove a stock gesture. **This design removes no gesture and doesn't
need to.**

## 5. Questions for the user (before the build)

- **Q1 (visible cost, bedtime):** In bed, under Leggetid, the kid can no longer open the clock to set or change
  tomorrow's alarm unless the parent ticks the clock on Leggetid. Should the clock be ticked on Leggetid by default
  (new phones and existing Leggetid rules)? Or is it the parent's tick alone? Recommendation: the parent's tick, with
  a line in the release notes.
- **Q2 (kiosk off):** With the kiosk off, the clock stays reachable during a rule. Is that acceptable as the parent's
  choice? Closing it would need front-app access, which we don't want.

## 6. Server and PWA

Nothing is needed. Clock apps have launcher icons, so they are `controllablePackages` and in the report's
`installed_apps`. `scope_apps` (`server/src/handlers/schedules.rs:723-763`) offers every reported package that is
allowlisted in a rule's and Leggetid's Exceptions (`exceptions`, `:651`). The server already seeds `org.fossify.clock`
as an exempt app in its tests (`server/src/lock_now.rs:520`). No policy key or contract file changes.

## 7. Tests

Unit tests (pure):
1. `EnforcementPlanTest`: rule plus kiosk with an allowed, not usable clock - not pinned, not suspended, in
   `ruleClockPins`; a usable clock - pinned, not in pins; budget only, unlocked, override - pinned as allowed; a clock
   not allowed - hidden, not in pins.
2. `RefrontPolicyTest` for `ruleClockWindow`: not listening, ringing, inside `[trigger - lead, trigger +
   ALARM_RING_MS)` - open; ringing but silenced, before or after that range, no trigger - closed.
3. `LockTaskHelpersTest`: pins added to the not-LOCKED and kiosk-on LOCKED rows only while open; SOS-narrowed and
   kiosk-off rows unchanged.
4. `TimeRulesTest` (the boundary's pure part): the two clock instants are taken only while a rule locks.

Emulator check: a new `docs/testing/emulator.md` §6au on `compat34` and `compat372`. Read state with `dumpsys` only,
since 37.2 dies on screencap.
- Kiosk on, android mode, Skole in force without the clock: `dumpsys device_policy` has no clock in the lock-task
  packages. Recents shows no clock. `am start -a android.intent.action.SHOW_ALARMS` is refused.
- An alarm set 4 min ahead before the rule: at trigger - 2 min the clock is in the list. With the screen off, the
  ringing screen comes up by itself. After Stop, the clock is gone from the list and the mode screen is the resumed
  activity.
- The clock ticked on Skole: its row is shown and it opens.

## 8. Docs to change with the code

`EnforcementPlan.kt:117-124`'s doc and `alarmAppPackages`' doc; a bullet "The clock during a rule" in
`docs/notes/launcher/time-rules.md`; the kiosk-list line in `lock-and-glance.md`.
