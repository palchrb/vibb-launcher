# 105 - The clock during a rule (revision 2)

Round `clock-in-rule`, 2026-11-16. Launcher only. **Status: built** (branch `clock-in-rule`, emulator §6au). Approved by the user ("Ja gjør det"); PLAN "The clock opens from
Recents during a rule". Revision 2 answers `docs/review/qa-105-design.md` (section 9 maps each finding).

**The user's decision on Q1** ("nei dette får foreldre bestemme selv, enig i det"): during a rule the clock is usable
only when the parent ticks it on that rule. This includes Leggetid, and nothing is ticked by default. Q2 (kiosk off)
is moot, because the kid's phone runs with the kiosk on.

## 1. The bug and its cause (checked against the code)

On the Jelly Star (Android 16, android mode, kiosk on), during Skole, the kid pressed Recents on the mode screen and
picked Fossify Clock. The clock opened and stayed open. This is how the code was built to behave:
- **The clock is spared.** The schedule lock keeps the clock apps out of suspension, so an alarm set inside bedtime
  still rings (QA step 4 #2). This is `alarmAppPackages` (`server/AppEnforcer.kt:195`; `sparedAlarmApps`,
  `EnforcementPlan.kt:419`, used at `:223`).
- **The clock is pinned.** While allowed, the clock apps are also pinned in the kiosk during the lock
  (`EnforcementPlan.kt:239`, bug 2026-11-02). Without the pin, lock task refused the ringing screen's new task. The
  plan's doc (`:121-124`) accepted that "the kid can reach the clock app ... during the rule". The user withdraws that.
- **Nothing brings the mode screen back.** `LockActivity` has no loss-of-front check. The launcher can't see which app
  is in front: it has no usage access and no accessibility service, and none should be added.

## 2. The mechanism: pin the clock only while the phone is locked or dark

During a **rule**, the allowed clock apps are in the kiosk's lock-task list only while the PIN lock is LOCKED or the
screen is dark. While the phone is unlocked and lit, they are out of the list. The clock stays spared from suspension
at all times, so alarms fire and sound as today.

- **LOCKED: no reach.** While LOCKED, `featuresWhileLocked` (`LockTaskHelpers.kt:145`) already removes HOME, OVERVIEW
  and NOTIFICATIONS. In android mode, any clock activity other than the showWhenLocked ringing screen needs Android's
  keyguard dismissed first. Dismissing it is the UNLOCKED edge, and that edge takes the clock out of the list. The
  ringing screen over the glance therefore works exactly as today: the bedtime morning alarm, Doze, a timer, an alarm
  behind another app's alarm clock. None of it depends on timing or on `getNextAlarmClock`.
- **Android's keyguard counts as LOCKED** (code QA L1). With Android's PIN lock as the kid's lock, Vibb's own lock
  need not be LOCKED while Android's keyguard is; `KeyguardManager.isKeyguardLocked` puts the clocks in too, and
  USER_PRESENT (the keyguard dismissed) runs a pass that takes them out.
- **The screen dark: covers the gap.** This covers the time between screen-off and Android's keyguard locking, and a
  phone without a lock. A full-screen intent in the dark finds the clock allowed. The dark part ends
  `ALARM_SCREEN_SETTLE_MS` after the screen comes on, so a ring that woke the screen reaches the keyguard state first.
- **Unlocked and lit: no pin, even while a clock rings.** This closes the chain in QA's H1. Section 3 shows that a
  ring can be stopped without the clock in front.
- **Outside the list, lock task refuses every way into the clock.** That covers a Recents thumbnail
  (`startActivityFromRecents`), a notification's content tap and `SHOW_ALARMS` from any app. This holds with the
  block bit on, the default since migration 0029. With the block bit off (a parent's Advanced choice), an exempt app
  can start a clock activity inside its own allowed task, as it already can for any non-suspended package (design 16).
  That is the known cost of turning the block bit off, not new here.
- **Leaving the list closes the task.** When a package leaves the list, AOSP's
  `LockTaskController.updateLockTaskPackages` terminates its locked tasks, but only while the lock-task state is
  LOCKED. The kiosk is that state, `mLockTaskModeState=LOCKED` (emulator.md §6as). We already rely on this
  (`lock-and-glance.md`, "removing a package clears its task"; the SOS narrowing, `LockTaskHelpers.kt:224`). So a
  clock task open at the unlock, or at a rule's start, is gone at the next pass. Fossify's `AlarmActivity.onDestroy`
  doesn't stop the ring, which lives in `AlarmService`, so the ring goes on and section 3 stops it.

### 2.1 Pure parts

- **`computeEnforcementPlan` takes a new input `ruleLocked`.** AppEnforcer passes `lock.rules.isNotEmpty()` next to
  `lockUsableApps` (`AppEnforcer.kt:466`).
- **While `locked && ruleLocked`, the clocks move out of the kiosk list.** The list drops `allowed ∩ alarmApps` except
  `lockUsableApps`, so a ticked clock stays pinned and gets its row. The dropped set goes into a new plan field,
  `ruleClockPins`. Suspension is unchanged.
- **The used-up budget alone is unchanged.** It has no rule and no exempt list to tick, so the clock stays pinned.
- **The pause, the override and Foreldrekode are unchanged.** `locked` is false, or the rule is lifted.
- **The list is built in `lockTaskWhileLocked` (`LockTaskHelpers.kt:205`), which takes `ruleClockPins` and `dark`.**
  - The kiosk-on LOCKED row adds the pins always.
  - The not-LOCKED rows add them only while `dark`.
  - The SOS-narrowed row and kiosk off are unchanged.
- **`clockDark(interactive, screenOnAtMs, nowMs)` decides `dark`:** not interactive, or lit for less than
  `ALARM_SCREEN_SETTLE_MS`.

### 2.2 Glue

- **Screen changes.** `LockTaskChrome` keeps the screen state, written by `PinLockRuntime`'s existing screen receiver
  (`PinLockRuntime.kt:564`). At `SCREEN_OFF` it runs one pass at once. At `SCREEN_ON` it posts one pass for after the
  settle.
- **LOCKED changes.** These already run `refresh`.
- **Every pass reads the current state under the monitor.** `locked` and `dark` are read inside `applyNow`, so a late
  pass can't land a stale open (QA L4). There is no PackageManager work, as with `sosYield`
  (`LockTaskChrome.kt:83`).
- **Before the first apply** (`plan == null`, QA L3), the last `ruleClockPins` are stored with the kiosk prefs.
  `fallbackKioskPackages` (`:251`) takes them out of the platform's list on an unlocked, lit pass, and adds them on a
  LOCKED or dark one.
- **No new triggers.** Gone are the boundary instants, `ACTION_NEXT_ALARM_CLOCK_CHANGED`, the listener-driven `follow`
  and the fallback send.

## 3. A ring while unlocked during a rule: stopped without the clock

**Fossify Clock (FossifyOrg/Clock main, read 2026-11-16).** `AlarmNotificationHelper.buildActiveAlarmNotification`
builds the ringing notification:
- Its content intent and full-screen intent are `AlarmActivity`, an activity.
- **Dismiss** is `getStopAlarmPendingIntent`: `PendingIntent.getBroadcast` to `StopAlarmReceiver`, which calls
  `stopAlarm`. It is also the notification's `deleteIntent`.
- **Snooze** is `getSnoozePendingIntent`: `PendingIntent.getService` to `SnoozeService` while `useSameSnooze`, which
  defaults to true (`BaseConfig`). Only with that setting off is it `getActivity` to `SnoozeReminderActivity`, the
  picker.

So both of the kid's stops are non-activity sends. Lock task doesn't gate them.

**The mode screen's alarm row** ("Slå av alarmen", `LockActivity.kt:375/456`) fires those actions while the clock
isn't pinned, instead of opening the ringing screen:
- The row shows each **non-activity** action of the ringing notification as a button, with the notification's own
  title (Fossify: "Slumre", "Avvis"). This is pure `alarmRowActions(actions)` over the existing `ClockAction`
  (`ClockTile.kt:141`).
- A button is sent the way `ClockActions.stop` sends: a non-activity `PendingIntent` only, checked again before the
  send, with background activity starts denied. Today that function takes broadcasts only; it gains services
  (`PendingIntent.isService` / `isForegroundService`, API 31, so no gate is needed).
- The row needs the reader to keep the ringing notification's actions next to its full-screen intent.

**Two sends stop while the clock isn't pinned.** `LockActivity`'s `alarmScreenSend` (`:78`) and `openRingingAlarm`
both do nothing then, so no refused send spends a ring (QA L5 moot). With the clock ticked on the rule, both work as
today.

**A clock with no non-activity action.** This is unverified for the Jelly's system clock; see the device check. AOSP
DeskClock's actions are `getService` sends. For such a clock the row isn't shown. The ring then stops at the clock's
own silence-after, or on the heads-up's buttons, or when the kid presses power twice: LOCKED pins the clock and the
glance sends the ringing screen, as it does today (`alarmScreenStep`).

**No question for the user.** The kid's clock (Fossify) stops from the card, so no clock is pinned while unlocked and
no Q3 is needed.

**Without the notification listener** (QA M2), nothing about the pin depends on it any more. The clock is never open
while unlocked. Two things follow:
- `lockRowPackages` (`TimeLockModel.kt:202`) stops adding the clocks while they aren't pinned. It keeps adding them
  when the clock is ticked or there is no rule.
- The card can't show the stop row. A ring stops in the shade, at the clock's silence-after, or with two power presses.
  A PWA line for `notification_listener_enabled: false` ("the mode screen can't stop an alarm") would be a server
  template change: a follow-up, not this round.

**Without `USE_FULL_SCREEN_INTENT`** (QA L6), the alarm isn't ringing-shaped, so the row isn't shown. The shade's
non-activity buttons still work, and the content tap is refused.

## 4. The cases

| Case | What happens |
|---|---|
| Recents -> clock, unlocked, rule | Refused; there is no clock task to show. |
| Alarm while the kid is in an exempt app | A heads-up (screen on). Its Avvis or Slumre buttons work. The content tap is refused. The mode screen's row works too. |
| Bedtime morning alarm, screen off, Doze | LOCKED pins the clock, so the ringing screen comes over the glance as today. |
| Kid unlocks while it rings | The clock task is cleared. The ring goes on. The row's Avvis or Slumre stops it. |
| Snooze | A service send. The next ring is handled the same way. |
| Fossify timer ends | No full-screen intent. Its Dismiss broadcast is the glance's Stopp (`ClockActions`). The mode screen is unchanged. |
| A silent rule silences alarms | No ring and no row, as today. |
| The clock ticked on the rule | Pinned all rule long, with its row. Alarm sends work as today. |
| Pause, override, budget alone | As today. |
| Own mode | LOCKED is the PIN lock: the same. |

## 5. Compatibility (Android 14-17)

- **Lock-task clearing and the Recents refusal.** Both are in AOSP `LockTaskController` and checked by the gate on 34
  and 37.2 (QA M3). The Jelly (16) gets a `dumpsys` look after the release.
- **Firing another app's `PendingIntent`.** Non-activity sends need no background-activity-start allowance. The
  `MODE_BACKGROUND_ACTIVITY_START_DENIED` option is API 34. The send is unchanged on 14-17.
- **A full-screen intent with the phone in use** is a heads-up on 14-17. With the kiosk's NOTIFICATIONS feature off
  lock task suppresses the heads-up, and SystemUI then launches nothing either (API 34: `InterruptionStateProvider`
  "No FullScreenIntent: NO_FSI_NO_HUN_OR_KEYGUARD"; code QA L4): no `BlockedAppActivity`, the mode screen stays in
  front with its row. Either way no full-screen launch is attempted while unlocked and lit, so the unpinned clock
  loses nothing there.
- **No API above 34. No feature bit changes.**

## 6. Recents

The kiosk keeps the server's features while unlocked, OVERVIEW included (16d, the user's rule). This design removes
no gesture and doesn't need to.

## 7. Server and PWA

Nothing in this round. Clock apps have launcher icons, so they are in `installed_apps`. `scope_apps`
(`server/src/handlers/schedules.rs:723-763`) offers them as a rule's and Leggetid's Exceptions when allowlisted. The
server's tests already seed `org.fossify.clock` as an exempt app (`lock_now.rs:520`). The PWA listener line is a
follow-up (section 3).

## 8. Tests

Unit tests (pure):
1. **`EnforcementPlanTest`:**
   - rule plus kiosk, with an allowed clock not usable: not in the list, not suspended, in `ruleClockPins`;
   - a usable clock: pinned, not in the pins;
   - budget only, unlocked, override: pinned as allowed;
   - a clock that isn't allowed: hidden.
2. **`LockTaskHelpersTest`:**
   - the pins in the kiosk-on LOCKED row always, and in the not-LOCKED row only while `dark`;
   - SOS-narrowed and kiosk-off rows unchanged;
   - `fallbackKioskPackages` with the stored pins.
3. **`clockDark` rows:** off, lit inside and past the settle.
4. **`alarmRowActions`:**
   - Fossify's shape gives two buttons;
   - its snooze as an activity gives one;
   - only activities gives no row;
   - plus `lockRowPackages` with pinned, unpinned and ticked clocks.

Emulator, a new `docs/testing/emulator.md` §6au on `compat34` and `compat372`. Use `dumpsys` and `uiautomator` only,
since 37.2 dies on screencap. Fossify installed and allowed, kiosk on, android mode.
1. **The clock is cleared at the rule's start.** Open the clock, then "Slå på nå" Skole. `dumpsys activity recents` and
   `activities` show no clock task, and `LockActivity` is `mResumedActivity`.
2. **The bug's path.** `input keyevent KEYCODE_APP_SWITCH`, then tap the clock's thumbnail if one is listed (bounds
   from `uiautomator dump`). The tap is refused, and there is still no clock task. `am start -a
   android.intent.action.SHOW_ALARMS` is refused.
3. **The ring while LOCKED.** Set an alarm 3 min ahead before the rule, press power, then
   `dumpsys deviceidle force-idle`. `dumpsys device_policy` lists the clock. At the ring, `AlarmActivity` is over the
   glance. Snooze once.
4. **Unlock while the snoozed alarm rings.** Unlock with the PIN while it rings. The clock task is gone, the ring goes
   on, and the row shows Slumre and Avvis. Avvis stops it (`dumpsys notification` has no ringing alarm).
5. **The clock ticked on Skole.** Its row is shown and it opens.

Device check after the release (Jelly, 16): `dumpsys notification --noredact` on the system clock's ringing alarm
gives each action's `PendingIntent` type (section 3, QA L6).

## 9. QA findings

| Finding | How it is handled |
|---|---|
| H1 | Fixed: no pin while unlocked and lit, rings included; stops via non-activity actions (section 3). |
| M1 | Fixed: pinned while LOCKED or dark; no pre-pin. |
| M2 | Fixed: the pin no longer depends on the listener; the PWA line is a follow-up (a server change). |
| M3 | Fixed: §6au steps 1-4 on 34 and 37.2; a Jelly look. |
| L1 | Moot: every clock is pinned only while LOCKED or dark, where there is no reach. |
| L2 | Explained: §2's claim qualified for the block bit off. |
| L3 | Fixed: stored pins in the fallback. |
| L4 | Fixed or moot: no alarm-changed trigger; state read under the monitor. |
| L5 | Moot: no sends while unpinned. |
| L6 | Explained, with a device check. |
