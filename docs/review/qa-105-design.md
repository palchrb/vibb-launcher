# QA of design 105 - the clock during a rule

Design QA, 2026-11-16, of `docs/design/105-clock-during-a-rule.md` (commit c66ae262e). I checked every citation against
the launcher on the branch and against `docs/notes/launcher/time-rules.md` and `lock-and-glance.md`. The line numbers
and the code facts in §1 and §2 hold. Nothing was built and no emulator was run.

Counts: **1 High, 3 Medium, 6 Low.**

## High

**H1 - The alarm window can keep itself open, so a determined kid keeps the clock all rule long.**
- *How it works.* The window has two parts that both reopen it. The trigger part is `[trigger - 2 min, trigger +
  3 min)` and takes any lock clock's next alarm clock, including one set *inside* the window. The ring part has
  "no time bound, consumed or not".
- *Scenario.* The evening before, the kid sets a Fossify alarm at 08:20 with a silent ringtone, no vibration and a
  long "max reminder duration". During Skole the alarm rings silently and the window opens. The kid goes Home, then
  Recents, then the clock, and sets the next silent alarm 4 min ahead (or snoozes with a short custom snooze). Each
  new trigger's pre-pin starts before the last ring ends, so the window never closes. The kid only has to find
  Recents, which is exactly how this kid found the bug. A gap at 08:15 is enough to start the chain.
- *Also.* §2.3's row "Stop or Snooze removes it, the window closes" is wrong. After Stop the trigger part holds the
  window open until `trigger + ALARM_RING_MS`, so every alarm gives up to 3 min of Recents-to-clock.
- *Fix.*
  - Drop the trigger pre-pin while unlocked (M1 below replaces it).
  - End a trigger's part as soon as a ring of it is seen and then removed.
  - Bound the ring part per ring, from first seen, as the yield does (`ALARM_NOTICE_MAX_MS`). Hide the card's
    "Slå av alarmen" row past the bound too, so no tap is refused.
  - Put the residual to the user as Q3, a visible cost: "a silent alarm the kid set before the rule gives up to N
    min of clock per ring".

## Medium

**M1 - The pre-pin is fragile in exactly the case that has no second chance: the bedtime morning alarm with the
screen off.**
- *Scenario.* It misses when:
  - another app's alarm clock is earlier, because `getNextAlarmClock()` is global and its creator isn't a clock;
  - our boundary is late in Doze. §3's "setAlarmClock brings the device out of idle shortly before" is not
    guaranteed: AlarmManagerService ends idle at the wake-from-idle alarm minus a *random* fuzz, which can be 0;
  - `rememberAlarm`'s sticky rule keeps an old or ringing trigger over a snooze's new one, if the window reads the
    remembered value and not a fresh one;
  - a timer set before the rule finishes, since a timer has no alarm clock.
- *What happens.* The full-screen intent is refused under lock task. The fallback send can't run in the dark
  (`alarmScreenStep` returns `SCREEN_ON`), so the alarm rings in a dark room until a power press. That is half of bug
  2026-11-02 back.
- *Fix.* Open the window also **while LOCKED, or while the screen is off**.
  - While LOCKED, `featuresWhileLocked` already removes HOME, OVERVIEW and NOTIFICATIONS. In android mode a clock
    activity that isn't the ringing screen needs the keyguard dismissed, and dismissing it is the UNLOCKED edge,
    which closes the window and clears the task. So the pin gives no reach.
  - The screen-off part covers a phone whose lock is DISABLED. Close it at screen-on only after
    `ALARM_SCREEN_SETTLE_MS`, so the listener sees a ring that woke the screen.
  - This adds a list write at lock and unlock, as the recents pin already does (`LockTaskHelpers.kt:184-187`). It
    removes the Doze reasoning, the two boundary instants (test 4) and the `getNextAlarmClock` dependence. While
    unlocked only a ring opens the window, which also closes H1's chain.
  - Keep `ruleClockWindow` pure, with `locked` and `interactive` as inputs.

**M2 - Without the notification listener the fix is off for the whole rule, and nobody is told.**
- *Scenario.* `listening()` false means the window is open. That is the case on a phone where access was never
  granted (no device-owner API grants it) or where Android didn't rebind the listener after a self-update. The clock
  then stays pinned all day, as before 105.
- *Fix.*
  - Give "not listening" a grace from process start (for example 2 min, to cover the rebind). After the grace, use
    LOCKED or screen-off (M1) plus the old fixed `alarmLikelyRinging` window, and offer the card's clock row only
    inside that window.
  - Have the PWA say it: the lock-glance note for `notification_listener_enabled: false` should name "the clock is
    open during rules".
  - Add the case to `RefrontPolicyTest`.

**M3 - The "AOSP closes what is open" claim needs the kiosk's lock task to be LOCKED, and the emulator gate never
tests the bug's own path.**
- *What AOSP does.* `LockTaskController.updateLockTaskPackages` terminates only tasks in `mLockTaskModeTasks` that
  lost authorization, and only while `mLockTaskModeState == LOCK_TASK_MODE_LOCKED`. A Recents tap goes through
  `moveTaskToFrontLocked` (via `startActivityFromRecents`), which refuses an `isLockTaskModeViolation` task. I'm sure
  of both in AOSP 9-14. Android 15, 16 and 17 need the emulator.
- *Scenario.* The gate checks `dumpsys device_policy` and `am start SHOW_ALARMS`, but never the Recents path the kid
  used or a task surviving the close.
- *Fix.* Add these steps to §6au:
  - Open the clock before Skole. At the rule's start, `dumpsys activity recents` and `dumpsys activity activities`
    show no clock task.
  - Open the clock during a ring's window. After Stop, once the window closes, the clock task is gone and the mode
    screen is `mResumedActivity`.
  - Run M1's screen-off case under `dumpsys deviceidle force-idle`.
  - Snooze once.
  - Run all of these on `compat34` and `compat372`, plus a `dumpsys` look on the Jelly (16) after the release. The
    target phone's version is in neither emulator.

## Low

**L1 - Two system clocks are pinned together.** Any ring or trigger pins all of `ruleClockPins`. On a phone with
Google Clock and an OEM DeskClock, Google's ring lets the kid into the other clock too. *Fix:* pin only the ringing
package (`AlarmNotice.packageName`) or the trigger's creator. Pin the whole set only when not listening.

**L2 - The block bit off leaves a way in that is never cleared.** With `block_activity_start` off (a parent's
Advanced choice; default on, migration 0029), an exempt app that starts `SHOW_ALARMS` without `FLAG_ACTIVITY_NEW_TASK`
puts the clock's activity into *its own* allowed task. Lock task checks only new tasks, so the start isn't refused,
and the window's close doesn't clear it. Whether this happens depends on the clock's launchMode. *Fix:* qualify §2's
"refuses every way" with "with the block bit on", and add one emulator line.

**L3 - The window can't open before the first apply.** Before the first apply in a process (`plan == null`),
`ruleClockPins` is unknown. A fallback pass reads the platform's list, so a window left open by a dead process stays
until the apply. *Fix:* as `routeWindow` does, treat a `clockWindow` without a plan as a request for an apply. Store
the last `ruleClockPins` so `fallbackKioskPackages` can take them out while the window is closed.

**L4 - `follow`'s triggers and ordering.**
- `ACTION_NEXT_ALARM_CLOCK_CHANGED` is only registered by activities today (`LockActivity.kt:198`,
  `GlancePanel.kt:306`). Add it to `TimeRuleReceiver`, which means changing `timeRuleSystemAction`'s allowlist
  (S-L7). That receiver also reschedules `TimeRuleAlarm`, which the design leaves out.
- `follow` from several threads needs its compute-and-set under `LockTaskChrome`'s monitor. Otherwise a stale "open"
  can land after a newer "close" and hold until the next sync.

**L5 - The fallback send can be spent before the pin lands.** A refused `PendingIntent.send` doesn't throw, and
`sentFromTimeRule` / `alarmScreenCheck` then spend the ring for that screen-on. *Fix:* in the listener's main-thread
callback, write the pass synchronously before any send, as `sosYield` does. The fallback's one send should ignore
`timeRuleSent` for the ring that opened the window.

**L6 - Ringing without a full-screen intent.** If `USE_FULL_SCREEN_INTENT` is denied on 14+, the clock's alarm isn't
ringing-shaped. Only the 3-min trigger part holds then, and after it a tap on the notification's content or on its
Snooze action is refused if either is an activity (Fossify's snooze picker may be one). *Fix:* add a device check
next to the existing `dumpsys notification --noredact` one, covering the actions' PendingIntent types. Name the case
in §2.3.

## Checked, no finding

- **Snarveier:** no clock key (`shortcuts.json`).
- **Widgets and app shortcuts:** the launcher hosts none.
- **The glance timer tile:** gated by `glanceUsableUnderLock`.
- **The SOS row:** pins the clocks through `pinLockHelpers`.
- **The update fence:** protects the clocks through `resolvePinLockHelpers`, not only the lock-task list.
- **The pause, the override, Foreldrekode and the budget alone:** `locked` is false, or there is no rule in the lock.
- **Own mode:** the kiosk-on LOCKED row gets the pins; kiosk off is unchanged.
- **A silent rule:** the window stays closed, consistent with `alarmRingingNow`.
- **The server:** nothing to change; `scope_apps` offers the clock.
- **Tests:** tests 1-3 are lean and enough. If M1 is taken, drop test 4 and add the `locked`/`interactive` and
  listener-grace rows to test 2.
