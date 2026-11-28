# Design 141: the kiosk ends from its root

Status: built 2026-11-27 on branch `kiosk-root` (§15), not gated; design rev 2 (branch `kiosk-root-design`; rev 1 7f84d99d2, its design QA 0H 3M 5L all taken - §14);
PLAN "Fix backlog" 1k. Launcher only, no server or contract change (Q1: log only unless the owner asks for the PWA
line). Android 14-17: AOSP `android14-release`, `android15-release`, `android16-release` and `main` read 2026-11-27 - the
functions below are the same code in all four (§7); the APIs are `Activity.stopLockTask` (API 21) and
`ActivityManager.getLockTaskModeState` (API 23), no version gate.

L paths under `launcher/app/src/main/java/com/kidslauncher/mdm/`. AOSP: LTC `services/core/java/com/android/server/wm/
LockTaskController.java`, ATMS `ActivityTaskManagerService.java`, ATS `ActivityTaskSupervisor.java`, ACC
`ActivityClientController.java` (same directory), `core/java/android/app/Activity.java`, core manifest
`core/res/AndroidManifest.xml`. Line numbers are 14's, with 16's where they differ.

The kiosk's end no longer leaves lock task on: Home, the lock-task root, stops it before the list is emptied, and
that clears every other locked task - a leftover "App is not available" included.

## 1. The finding (design 139's gate, 2026-11-27, Android 14; the same code on 16)

1. Kiosk on (Home roots lock task, the app block set). A start of a package that is neither pinned nor suspended
   becomes Android's `BlockedAppActivity` in a task of its own. Its manifest says `lockTaskMode="always"` (core manifest
   7872-7877), so its task's auth is `LOCK_TASK_AUTH_LAUNCHABLE_PRIV` (LTC 826-827) and `realStartActivityLocked` adds
   it to lock task (ATS 862-868, 16: 895-901). The kid leaves it with Home instead of OK or Back: the task stays,
   locked, in the background (`excludeFromRecents`).
2. The kiosk ends (`LockTaskChrome.writeLockTask`, the `packages == null` branch: `liftAppBlock`, `kioskEnabled(false)`,
   `setLockTaskPackages(admin, [])`). `updateLockTaskPackages` removes and clears only the tasks that **were**
   `LAUNCHABLE`/`ALLOWLISTED` (LTC 766-783, 16: 771-788) - Home's, the kid's apps', our pages'. The blocked task is
   `LAUNCHABLE_PRIV` and stays, so lock task stays LOCKED with it as the only locked task.
3. From then on every new task start is a violation (`isLockTaskModeViolationInternal`, LTC 379-400: not allowlisted
   and the locked list not empty) - our Home's restart and Chrome are refused with 101, the stock launcher shows,
   nothing opens. `HomeActivity.reconcileKioskMode` (HomeActivity.kt 660) calls `stopLockTask()` from Home's **new**
   task, which was never locked: "Invalid uid, expected -1" (LTC 518-522). Re-pinning at the pause's end hides it; a
   parent's kiosk-off stays stuck until a reboot (or until the kid finds the blocked screen and taps OK - it is not in
   Recents).

Not a regression: the kiosk's end has emptied the list this way since design 16 (e5b5f5a4a) and §6af (a735acf7f). Not
caused by design 139. Likely also left by (4i)'s charging glimpse (a `BlockedAppActivity` task under the lock at a
plug-in, `RefrontPolicy.kt` "(4i)") - unchecked, gate row 10.

## 2. Why a stop from the root fixes it, and the gap it opens (AOSP)

- `Activity.stopLockTask()` is one binder call with the activity's token (Activity 9019-9021, 16: 9626-9628; ACC
  1218-1220 -> ATMS `stopLockTaskModeInternal` 2613-2639, 16: 2687-2713). **On Android 14 and 15 it is `oneway`**
  (`IActivityClientController.aidl`: `oneway void stopLockTaskModeByToken`, and the start, in `android14-release` and
  `android15-release`; plain `void` in `android16-release` and `main`, read 2026-11-27 - emulator.md §6cg F1): there
  `stopLockTask()` returns before system_server has run the stop, and no exception comes back - a `SecurityException`
  ("Invalid uid", §1) is lost, so `REFUSED` is never seen on 14/15. §3.5 waits for the stop to land. No foreground check (only the start has
  "Invalid task, not in foreground", ATMS 2590-2593, 16: 2667-2670) and no thread check. A token whose record is gone
  returns silently (`r == null -> return`); a record without a task throws `IllegalArgumentException` ("can't stop
  LockTask for null task", LTC 507-509).
- `stopLockTaskMode` (LTC 492-530): nothing while NONE; else the caller's uid must be the task's `mLockTaskUid` - ours,
  since Home's `startLockTask` set it (LTC 652-653) - then `clearLockedTask(callerTask)`. `mLockTaskUid` is never reset
  when a task leaves lock task (`removeLockedTask` doesn't touch it): a Home task that was once locked passes the check
  later too (the stale uid, §3.2).
- `clearLockedTask` (LTC 551-568): **from the root** it first clears every other locked task, newest first (each:
  `removeLockedTask` + `performClearTaskForReuse` + a resume of the focused task), whatever its auth - the
  `LAUNCHABLE_PRIV` blocked task included -, then removes the root **without clearing it** (the list is empty, it
  returns before the clear) and posts the end of lock task (`removeLockedTask`, LTC 574-584). From a task that isn't
  the root, or isn't locked at all, it clears that task (Activity's own doc: "will not terminate the LockTask mode, but
  only finish its own task").
- So: Home stops lock task from its own task while it is the root -> every other locked task is closed, Home stays as
  it is, the locked list is empty at once (on 16+ when the call returns; on 14/15 when the oneway stop has run, §3.5). The `setLockTaskPackages(admin, [])` that follows finds no locked task:
  nothing removed, no Home restart, so neither of §6af's races can happen (both were the system's Home restart inside
  that call).
- **The gap.** The state stays LOCKED until the posted `performStopLockTask` (LTC 587) runs. In that window:
  - a **resume** (Home after the clear of the task in front) is no start and joins nothing;
  - a start is no violation (the locked list is empty, LTC 400) and never `BlockedAppActivity` (the block is lifted
    first, `isActivityAllowed` LTC 418-421);
  - but a **launch** of an allow-listed activity joins lock task (ATS 862-868, 16: 895-901: `ALLOWLISTED` and LOCKED)
    and, the list being empty, becomes the **new root** and posts `performStartLockTask` (LTC 697-703). If step 4 lands
    before the posted stop it removes and clears that task (`updateLockTaskPackages`: was allow-listed, isn't); if it
    lands between the posted stop and the posted start it skips it (`mLockTaskModeState != LOCKED`, LTC 772), and lock
    task comes back LOCKED with that task as the root and an empty list - the same stuck picture with another root.
  - Who launches in that window: ATMS's own `showInCallScreen` after the stop (ATMS 2631-2636, 16: 2705-2710; Telecom
    then brings the in-call UI forward - ours with the dialer role, the system dialer without); Home's `onResume`,
    resumed by step 3's clear, can launch `LockActivity` (a rule's reason), `SetupFlowActivity`
    (`SetupRun.takeReturnAfterPause`, the setup hold), `ConnectActivity` and the role prompt (the permission
    controller, a pinned helper). Step 4 follows step 3 at once on the same thread (on 14/15 after §3.5's wait, once the
    state reads NONE: then on the "skips it" side), so a launch must come within that window; Home's need a client
    transaction and a main-thread hop first. Narrow, but real - §3.3's retry and gate rows 7 and 9 cover it.

## 3. The fix

### 3.1 The kiosk's end, in this order (`LockTaskChrome.writeLockTask`, `packages == null`)

1. `liftAppBlock` - unchanged (§6af part 1; now also guards §2's gap).
2. `kioskEnabled(false)` - unchanged (§6af part 2). It must come before step 3: Home, resumed by the clear of the task
   in front, reads it in `reconcileKioskMode` and must not start lock task again.
3. **New: `HomeRootStop.stop(context)`** (§3.2) - only while `lockTaskModeState == LOCK_TASK_MODE_LOCKED`.
3b. **New (§3.5, emulator.md §6cg F1): `HomeRootStop.settle(context)`** - right after a stop of Home's, wait (bounded)
   until lock task isn't LOCKED: on 14/15 the stop is oneway.
4. `setLockTaskPackages(admin, [])` - unchanged; it is also the cleanup for anything started in the gap before the
   posted stop, and the whole fallback when step 3 couldn't stop it. Skipped once when step 3 answers `NO_HOME` and
   Home is being brought (§3.4).
5. **New: the stuck check** (§3.3), 1 s later.

A failed step 4 puts the switch and the block back, as now. After a successful step 3 lock task is then NONE with the
switch on, until Home's next resume starts it again (the listener's `startLockTask` needs Home in front) - acceptable
in a pause's start: the kiosk was on its way out, and the next pass tries the emptying again.

The pin (kiosk on) is unchanged. Every pass with the kiosk off runs this branch, so step 3 costs one state read; a phone
with the kiosk off and nothing locked does nothing new.

### 3.2 `lock/HomeRootStop.kt` - Home's stop, called from the pass's own thread

- **Who is Home**: `HomeActivity` registers itself in `onCreate` and unregisters in `onDestroy` - not when
  `isChangingConfigurations` (a relaunch keeps the record and the token; the new instance registers again in its
  `onCreate`, so the gap between the two is never a `NO_HOME`), and only if it is still the registered one. A
  `@Volatile WeakReference`. One instance: `singleTask`, typed HOME only (design 16 QA #1, `HomeFrontTest`).
- **The call**: `stop(context)`, pure answer `homeRootStopAnswer(...)` fed by the runtime: no registered Home ->
  `NO_HOME`; `lockTaskModeState != LOCKED` -> `NOT_LOCKED` (NONE, and also PINNED: screen pinning is the kid's or
  Android's, never the kiosk's, and under PINNED of another task Home's stale uid (§2) would only clear Home's own
  task); `PinLockRuntime.chromeLocked` -> `LOCKED_CHROME` (not ours to stop; can't happen from this branch, a guard);
  else `home.stopLockTask()` -> `ASKED`, a `SecurityException` (Home's task never locked - the stuck state of §1) ->
  `REFUSED`, any other exception (the record without a task's `IllegalArgumentException`, a dead binder) -> `FAILED`.
  The call is wrapped in `catch (e: Exception)`. One log line per answer with fixed words; `REFUSED`/`NO_HOME` at most
  once per kiosk-off spell (the passes repeat).
- **The `chromeLocked` read is a check-then-act** (QA L2): the screen can go LOCKED between the read and the stop. Not
  new - the whole unpin branch already acts on the `locked` its pass read, and the LOCKED edge's own pass (which waits
  for the chrome monitor this pass holds) pins the lock's list right after; at worst the glance starts lock task itself
  (`lockTaskEntry`).
- **Off the main thread, directly, no latch.** `stopLockTask` is a plain binder call with the token (§2), safe from any
  thread. A latch would wait **under LockTaskChrome's monitor** (and `AppEnforcer.apply`'s) for the main thread, and
  the main thread takes that monitor itself - the LOCKED edge's pass (`PinLockRuntime.refreshChromeNow`) and the "sos"
  yield (`sosYield`). In that collision the latch would time out into the very path this fixes, and the lock's
  appearance would wait for the timeout. The direct call has no wait, no timeout and no stale posted stop to cancel. A
  pass on the main thread (a VoIP refresh) calls it inline - the same code.
- **Races with Home's own main thread** (harmless; the system serialises them under `mGlobalLock`): Home's
  `kioskEnabled` listener (step 2 posts it to main) calls the same `stopLockTask()` - whichever runs second finds the
  locked list empty (`clearLockedTask` returns) or NONE (returns); note that ATMS calls `showInCallScreen` after every
  app stop, also one that found NONE (QA L4), so the listener's stop brings a live call's screen forward too, as it does
  today. Home's `onResume` after the clear reads the switch off and stops nothing new.
- **No root bookkeeping.** With the kiosk on and the phone unlocked Home is the root in every designed flow: Home
  roots it (design 16 #2), the glance roots only while LOCKED and re-roots through Home at its leave (`lockLeave`,
  `rootLeave`, design 59d A), and Home's record survives a process death in place (`stateNotNeeded`,
  `kiosk-escapes-and-updates.md` "Home stays the lock-task root"). If it isn't the root after all, Home's stop clears
  Home's own task (§2) - like today's emptying only while Home's task is still locked; from a task that has left lock
  task (`mLockTaskUid` is never reset) it clears Home's task even when today's emptying wouldn't, so Home's stop runs
  once per kiosk-off spell (§15, code QA M1) - and step 4 runs as today.
- **During a live call: step 3 still runs** (decided, QA M1). Skipping it keeps today's path exactly when a stuck kiosk
  hurts most: today the emptying clears our in-call screen's locked task and, with a leftover holding lock task, its
  relaunch is refused - the kid loses the call screen until a reboot. Home's listener already makes the same stop during
  calls today (§6af "Home ... stops lock task itself when it gets there first"), so the in-call relaunch's gap race
  (§2) is not new. With our dialer role held (the normal setup) the in-call UI is ours and §3.3's retry ends a root it
  became; with the system dialer as the in-call UI that root is the residual (a live call, a pause start and the race at
  once) - the stuck line logs it.

### 3.3 The stuck check and one retry

1 s after a kiosk-off pass that found lock task LOCKED (main handler, one pending at a time): pure
`lockTaskStuck(lockTaskLocked, kioskOn, chromeLocked, voipPinned)` - LOCKED with the kiosk off, the chrome not LOCKED and
no VoIP pin is never ours (the kiosk-off lock task exists only while LOCKED, `lockTaskWhileLocked`). PINNED is not
stuck (§3.2). Then:
- **One retry** (QA M1): when an activity of ours is resumed - tracked by `HomeRootStop`'s own
  `ActivityLifecycleCallbacks` (a weak reference set in `onActivityResumed`, cleared in `onActivityPaused`; Home, our
  in-call screen, the setup flow, `LockActivity`) - it calls `stopLockTask()` once. A resumed activity of ours while
  LOCKED with an empty list is in a locked task (else it couldn't be in front), and after §2's race it is the new root:
  its stop ends lock task (a task that joined at launch has `mLockTaskUid` 0 and our `effectiveUid`, LTC 518-519). The
  check runs again 1 s later.
- Still stuck: one log line "Lock task still on after the kiosk's end (Home's stop: <answer>, retry: <answer>)". Nothing
  on the phone can end it then (§8); the remedy is the parent's: restart the phone. **Q1: log only (the default) unless
  the owner asks for the PWA line.**

### 3.4 No Home in this process: bring it, don't wait (QA L1)

When step 3 answers `NO_HOME` with lock task LOCKED and the screen interactive (the launcher process died while
another app was in front; Home's record stays the root and is restarted only when it resumes):
- the switch is already off (step 2); **step 4 is skipped this pass** and `HomeFront.bring(context, "kiosk end")`
  starts typed HOME - our package is still listed, so Home is restarted in its root task;
- Home's `onResume` -> `reconcileKioskMode` reads the switch off with lock task on and stops it **from the root** (the
  §2 path), then asks for a chrome pass (`PinLockRuntime`'s chrome executor), which finds NONE and empties the list;
- a fallback timer (~3 s, elapsed) runs a pass regardless; that pass empties the list whatever step 3 answers (one
  bring per kiosk-off spell, pure `unpinStep(...)`, §10).
- Limits: `reconcileKioskMode` stops only when `contentAllowed()` (not while the lock is undecided with a kid PIN, 16c)
  - then the timer's pass runs today's emptying. With the screen off no Home is brought (it wouldn't resume behind the
  keyguard) - today's emptying, as before. The kid sees Home come up over the app he was in - what today's emptying
  does too.
- This shrinks §5's residual to a process death plus a leftover plus a dark screen or an undecided lock.

### 3.5 The wait for a oneway stop (Android 14 and 15; emulator.md §6cg F1, added after the gate)

On 14 and 15 `stopLockTask()` is oneway (§2): it returned `ASKED` and the emptying went out in the same ms, and in 8 of
the gate's 37 kiosk ends the emptying reached the lock first - it removed and cleared Home's task, and the stop, run
11 ms later, found no record. With a leftover locked that is §1's stuck picture again (5 of 5); without one Home is
recreated (§6af's path). When the stop won, it won by 0-19 ms. So between step 3 and step 4:
- `HomeRootStop.settle(context)` reads `lockTaskModeState` every 10 ms (`KIOSK_END_STOP_POLL_MS`) while it reads LOCKED
  and Home's stop went through less than **300 ms** ago (`KIOSK_END_STOP_SETTLE_MS`), then step 4 runs. NONE means the
  stop has landed: AOSP posts `performStopLockTask`, which sets NONE first, only once the locked list is empty. Pure rule
  `stopSettle(sinceAskedMs, waited, state)`.
- "Home's stop went through" is `KioskEndSpell.askedAtMs`: the pass's own `ASKED`, and Home's own stop at a resume
  (`homeStopped()`) - §3.4's pass after a brought Home's stop answers `ASKED_BEFORE` and would race the same way. An older
  stop (a stuck spell's later passes) waits for nothing and reads nothing.
- **The bound** (14/15): over 15 times the gate's worst (19 ms, and the lost stop's 11 ms behind) for a busy system_server at
  the kiosk's end; short enough that a main-thread taker of the chrome monitor waits at most 0.3 s once per kiosk-off
  spell (no ANR near), and it ends well before §3.3's 1 s check.
- **No deadlock**: the wait waits on system_server alone (its stop under `mGlobalLock`, the posted end on its own
  handler), never on this process's main thread, so holding the chrome monitor through it can't block on main;
  `SystemClock.sleep` ignores interrupts (no early exit into the race).
- **Out of time** (still LOCKED at 300 ms - on 14/15 also Home's lost `REFUSED`, or a gap launch's new root, §2):
  today's emptying, and one line per spell, "The kiosk's end: lock task still LOCKED ... ms after Home's stop - the list
  is emptied regardless"; §3.3's check follows as before. A wait that ended logs "lock task ended ... ms after Home's
  stop, then the list".
- **Only on 14 and 15** (`stopIsOneway(Build.VERSION.SDK_INT)`, below API 36; code QA L1): on 16+ the stop has run when
  the call returns, and a wait for the posted end would only move the emptying past it - to where a launch that joined
  in §2's gap stays the new root - so 16+ answers `NOT_ASKED` and empties at once, as before.
- **The retry (§3.3)** needs no wait: nothing follows it but the check 1 s later, which reads the true state; on 14/15
  its `REFUSED` reads `ASKED` (logged as such), the check after it tells. **`HomeActivity.reconcileKioskMode`**: its own
  stop is oneway too; it records the stop's time (`homeStopped()`), so the pass it asks for waits as above, and Home's
  next resume reads the state anew.

## 4. Every way the kiosk ends

All reach the one writer (`setLockTaskPackages` is called nowhere else) through `EnforcementPlan.kioskPackages ==
null` (`appsManaged && kioskDesired` false):

| Path | How | Lock state at the pass | What happens now |
|---|---|---|---|
| Pause start (Settings behind the Parent PIN, the PWA's `start_pause`) | `RestrictionsPause.start` -> `reapply` (IO) | unlocked | §3.1: Home's stop; from Settings, Settings stays in front (it is in Home's task, the root's task is never cleared) - §6af saw this "often", now always |
| Pause start in the dark (the PWA) | the same | LOCKED: the kiosk-off LOCKED row pins our package + helpers, no empty list | the kid's apps leave the list (cleared, as today); Home's root and the leftover stay until the unlock: the glance's own stop at the unlock (`PinLockActivity.kt` 777, a non-root - only its own task, as today), then the UNLOCKED edge's pass (chrome executor) runs §3.1 |
| Offline override (`OfflineOverride.isActive`; nothing on the phone starts it any more) | `overrideActive` | either | as the two rows above |
| The parent's kiosk switch off (policy `kiosk_desired`) | sync -> `AppEnforcer.apply` | either | as the two rows above, and it stays off - the case that stayed stuck until a reboot |
| Unmanaged (`allowlist` null), a first apply with no policy | `apply` | unlocked | §3.1 |
| The setup hold (design 114, a pause with the hold flag) | `RestrictionsPause.startHold` -> `reapply` | unlocked | §3.1: the setup flow's task (`.setup`, a non-root) is cleared as today, Home is resumed (not restarted) and `SetupRun.takeReturnAfterPause` launches the flow - a launch that can fall in §2's gap (§3.3's retry; gate row 7) |
| Phase D / Android's lock as the face | the face never empties the list while LOCKED; with the kiosk on and Android locked the glance may root (59d A) and leaves via `rootLeave` (a root stop: it clears leftovers itself) | - | unchanged; §3.1 runs at the UNLOCKED edge |
| The boot cover (16b, 59d M) | lock task never survives a reboot; the cover runs in `:bootcover` and never registers as Home | NONE | step 3 skipped (`NOT_LOCKED`) |
| A process start's pass without a plan (`applyFallback`, stored switch off) | `refresh` | either | §3.1 if LOCKED, with §3.4 when no Home exists yet; usually `NOT_LOCKED` |

A call live at the kiosk's end: ATMS calls `TelecomManager.showInCallScreen(false)` after every app stop (ATMS
2631-2636, 16: 2705-2710), so the in-call screen comes to the front (§3.2's decision; gate row 9).

## 5. Home not alive or not in front

- **Behind another app** (the kid's app, our pages, Settings): works - no foreground check (§2). The app in front is
  closed if it is a locked task (as today), Home is resumed.
- **Screen off, not LOCKED** (the dark gap before Android's keyguard locks): works - the call needs no window. With
  Vibb's lock delay 0 (design 139 gate row 15) the screen off is LOCKED at once, i.e. §4's dark row.
- **Pause end -> start with another app in front**: at the end Home's listener asks `startLockTask` while not in front
  -> "Invalid task, not in foreground" (caught, design 16 QA #3) -> lock task stays NONE until Home's next resume. The
  start that follows finds NONE -> `NOT_LOCKED`, today's emptying (harmless). With Home re-rooted in between: Home's
  stop from behind the app, as above.
- **A quick end and start** (code QA L2): a pause's end right after its start - Home's listener's `startLockTask` while
  Home isn't in front, or the end's pin landing before Home is resumed - can leave the kiosk "on" (the list pinned, the
  switch on) with lock task NONE until Home's next resume starts it. Not new and not stuck: the kid's next Home press
  roots it; gate row 11.
- **Home relaunching** (a font-size or display change): no `NO_HOME` - the registration stays across
  `isChangingConfigurations` (§3.2), and the old object's token is the record's.
- **No Home in this process**: §3.4 brings it when the screen is on; else today's emptying, logged by the stuck check.

## 6. Threading

- The pass runs where it runs today (apply's IO thread, the chrome executor, rarely main) and holds the same two
  monitors; it gains one binder read and at most one binder call (or one `startActivity` for §3.4) and, right after a
  stop of Home's, §3.5's bounded wait (at most 300 ms of 10 ms reads, on system_server only) - no new lock, no callback
  into the app.
- Home's register/unregister and the resumed-activity reference are main-thread writes of volatile fields; the pass
  reads them once.
- The stuck check and its retry post to the main looper and read only `lockTaskModeState` and volatile fields - no
  monitor. §3.4's follow-up pass and timer go through the chrome executor like any other pass.
- No deadlock: nothing waits for the main thread, and the main thread's own monitor takers (§3.2) wait only for the
  pass's binder calls and §3.5's bounded wait.

## 7. Android 14-17

`stopLockTaskMode`, `clearLockedTasks`/`clearLockedTask`, `removeLockedTask` and `updateLockTaskPackages` are line for
line the same in `android14-release`, `android15-release`, `android16-release` and `main` (diffed 2026-11-27); so are
ATS's join, ATMS's `stopLockTaskModeInternal` with its in-call screen, ACC's token path and `Activity.stopLockTask`.
`performStopLockTask` differs by one line (14 also calls `mWindowManager.onLockTaskStateChanged`, 16 doesn't) - nothing
this design reads. **Except the call itself** (missed here, found by the gate, emulator.md §6cg F1): ACC's
`stopLockTaskModeByToken` is `oneway` in `android14-release` and `android15-release`, plain `void` in
`android16-release` and `main` - on 14/15 `stopLockTask()` returns before the stop has run and throws nothing; §3.5's wait
covers it there (16+ needs no wait). `BlockedAppActivity`'s manifest entry is the same on 14 and 16. Gate on 14 and 16 (the Jelly Star
runs 16); Android 17 is owed - no API 37 image runs here (`docs/testing/emulator.md` "Owed rows").

## 8. Alternatives

- **Finish the blocked task ourselves**: not possible. `ActivityManager.getAppTasks()` lists only our own tasks,
  `getRunningTasks` likewise; removing another app's task needs `REMOVE_TASKS` (signature); no Device Owner API touches
  a `LAUNCHABLE_PRIV` task (`setLockTaskPackages` skips it, §1.2); `BlockedAppActivity` runs in the system's `:ui`
  process, which we can't kill; we can't tap its OK.
- **Re-root at every Home resume in the kiosk** (stop + start, which clears leftovers): closes the kid's apps at every
  Home press. Rejected.
- **Drop the app block**: the block is what stops an allowed app from opening other packages inside its own task
  (handy step 9). Rejected.
- **The latch on main** (the investigation's suggestion): §3.2.
- **Skip step 3 during a live call**: §3.2 (kept).
- **Reboot the phone when stuck** (`dpm.reboot`): would cut a call and surprise the kid. Not offered.

## 9. What the kid and the parent see

- A pause or the kiosk's end with a leftover blocked task: apps open, as with none. Today: the stock launcher and
  nothing opens.
- Every kiosk's end: Home is no longer torn down and rebuilt (no launch splash, the grid where it was); the kid's open
  app still closes, as today. With no Home in the process (§3.4), Home comes up - as today.
- A pause started from Settings: Settings stays in front (Back goes to Home) - today often, now always.
- A call live at the kiosk's end: its screen comes to the front (§4).

## 10. Tests (pure, `LockTaskRootTest`, and source scans in `HomeFrontTest`'s pattern)

- `homeRootStopAnswer(registered, lockTaskState, chromeLocked, outcome)`: each answer of §3.2 - no Home; NONE and
  **PINNED** both `NOT_LOCKED` (never a stop); LOCKED chrome; asked; refused; failed.
- `lockTaskStuck(...)`: kiosk off + LOCKED + unlocked + no VoIP pin -> stuck; kiosk on, LOCKED chrome, a VoIP pin,
  NONE or **PINNED** -> not.
- `unpinStep(answer, interactive, broughtThisSpell, timerDue)`: `NO_HOME` + interactive + not brought -> bring Home,
  skip the emptying; brought + the timer due, or not interactive -> empty; every other answer -> empty.
- `kioskEndLogOnce`: `REFUSED`/`NO_HOME` logged once per kiosk-off spell, again after a kiosk-on pass.
- Source scans: in `writeLockTask`'s unpin, `liftAppBlock` < `kioskEnabled(false)` < `HomeRootStop.stop` <
  `setLockTaskPackages(admin, emptyArray())`; `HomeRootStop.stop` is called nowhere else; `setLockTaskPackages` appears
  only in `LockTaskChrome.kt`; `HomeActivity` registers in `onCreate` and its `onDestroy` unregisters only behind
  `!isChangingConfigurations`.
- `LockTaskHelpersTest`'s `unpinFeatures` rows stay.
- §3.5: `stopSettle` (no stop or an older one: no wait, no read; LOCKED within 300 ms waits; NONE/PINNED ended; out of
  time after a wait; the wait run against a stop landing at 0, 19, 300 ms and never), `KioskEndSpell.askedAtMs`, the
  timeout's line once per spell, `stopIsOneway` (34, 35 yes; 36, 37 no); the scan: the version gate first, `settle` between the EMPTY step and the emptying, both stops record the time,
  `settle` has no main-thread hop, latch or `Thread.sleep`.

## 11. Emulator gate (Android 14 and 16; 17 owed)

Setup: `jelly36` (and the 14 image), the branch's debug launcher as Device Owner, enrolled, kiosk on with the block
(`0x7f`), Home the root (`dumpsys activity activities`: `mLockTaskModeTasks` #0 is Home's task), the lock-task protolog
on (`wm logging enable-text WM_DEBUG_LOCKTASK`, after every boot), Chrome allowed. "A leftover" = `am start -n` of a
package neither pinned nor suspended (design 139 gate's), "App is not available" shown, then the Home key: two locked
tasks, the second `BlockedAppActivity`'s. Record per row: the protolog order, `mLockTaskModeState` and
`mLockTaskModeTasks` right after and **~2 s after**, Home's task id before and after, `wm_finish_activity` /
`wm_on_create_called` for `HomeActivity`, our `HomeRootStop` and stuck lines. Rows 2, 3, 6 and 9 on both 14 and 16;
the rest on 16 unless noted.

1. **Control on `main`** (14): a leftover, then a pause from Settings: stuck (LOCKED, the blocked task alone, the stock
   launcher, `Invalid uid` in our log) - proves the setup reproduces. OK on the blocked screen (or a reboot) clears it.
2. **The fix, a pause from Settings** (14, 16): a leftover, the pause: the blocked task's `removeLockedTask` and Home's
   ("last task, reverting") **before** `Allowlisting 0:[]`; NONE within 1 s and at 2 s; Home's task id unchanged, no
   `HomeActivity` finish or create; Settings in front, Back -> Home; Chrome opens from Home. Ten times.
3. **A pause from the PWA** (14, 16) with Home in front and with Chrome (an allowed app) in front: as 2; Chrome's task
   closed (as today), Home resumed, not recreated.
4. **The pause's end re-pins**: "Lås igjen" and the PWA's end: LOCKED, Home #0, the list and `0x7f` back.
5. **The parent's kiosk switch off** (PWA) with a leftover: NONE, apps open; still NONE after the next sync and a
   screen cycle; the switch on again -> 4.
6. **Screen off during the unpin** (14, 16): a leftover, the power key (the glance LOCKED), the PWA's pause in the
   dark: while dark the kiosk-off LOCKED row (our package + helpers listed, the leftover still locked); Android's PIN ->
   after the unlock NONE, no blocked task, Home there, apps open. The same with the parent's kiosk-off.
7. **The setup hold** (14, 16): design 114's row 13 (the flow's "Sett på pause i 2 t" returns to the flow) with a
   leftover; at least 5 times, `mLockTaskModeTasks`/`mLockTaskModeState` ~2 s after each: NONE, or a retry line that
   ended it; and each time back in the flow (its step shown, not Home) with Home not recreated (no `wm_on_create_called`
   for `HomeActivity`, its task id unchanged). A LOCKED state with the flow's task as the root and no retry, or the
   flow's return lost, is a finding.
8. **The residual, now §3.4**: a leftover, Chrome in front, a kill of the launcher's process (`adb shell kill <pid>`, not
   `am force-stop`, which removes its tasks), the PWA's pause before Home was resumed: `NO_HOME`, Home brought, Home's
   root stop, the follow-up pass empties the list; NONE within ~3 s. Again with the screen off: today's emptying, the
   stuck line within ~1 s (the residual; OK on the blocked screen clears it).
9. **A live call** (14, 16): `adb emu gsm call`, answered, Home in front, the PWA's pause, at least 5 times: the in-call
   screen comes to the front; `mLockTaskModeTasks`/`mLockTaskModeState` ~2 s after: NONE, or a retry line that ended it
   (the in-call screen as the gap's root, §2). Once more with the system dialer as the in-call UI if the image allows
   our role to be dropped: record what happens.
10. **(4i)'s leftover**: kiosk on, screen off, `adb emu power ac on` (a plug-in): after the unlock, is a
    `BlockedAppActivity` task left in `mLockTaskModeTasks`? If so, the pause -> NONE (as 2).
11. **A quick end and start**: a pause, "Lås igjen", a pause again within ~1 s (Settings, then the PWA): no crash, no
    stuck state at 2 s, one log line per pass's answer.
12. **A font-size change just before the pause**: Settings' font size (Android's, through `adb shell settings put
    system font_scale 1.3`) - Home relaunches - then a leftover and a pause at once: `ASKED`, never `NO_HOME`; as 2.

## 12. Size

S, launcher only: `lock/HomeRootStop.kt` (~110 lines with the pure answers, the resumed-activity tracker and §3.4),
`LockTaskChrome.writeLockTask` (+~20), `HomeActivity` register/unregister and its stop's chrome request (+10), the stuck
check and retry (~40, the pure rules in `LockTaskRoot.kt`), tests (~140); docs:
`docs/notes/launcher/hardening.md` "The kiosk's end", `kiosk-escapes-and-updates.md` "Home stays the lock-task root",
the gate section in `docs/testing/emulator.md` (the next free §). The gate: 12 rows, four of them on both images and
two repeated 5 times, ~3-4 h. Q1's line, if the owner asks for it, adds S: one status-report key (contract file + README
row), the server's parse, one orange line in nb and en.

## 13. Question for the owner

**Q1 (visible): show a stuck kiosk in the PWA?** Default: **log only** (§3.3), unless the owner asks for the line.
When lock task is still on after the retry (after this fix only: §2's gap race with a foreign in-call UI, or §3.4's
limits - a launcher process death plus a leftover with the screen off or the lock undecided; §3.4 shrinks this
residual), the phone could report it and the PWA show one orange line on the phone's page - "Kiosken henger" / "Kiosk
stuck" - with "Start telefonen på nytt" / "Restart the phone" behind the (i). It is a contract change in both dirs (+S).

## 14. Design QA (2026-11-27, 0H 3M 5L) and rev 2

- **M1** the gap: §2 corrected (a launch in the window before the posted stop joins and becomes the root; who
  launches); §3.3 adds one retry from our resumed activity; §3.2 decides step 3 runs during a live call, with why; gate
  rows 7 and 9 record the state ~2 s after, 5+ times.
- **M2** step 3 and `lockTaskStuck` check LOCKED, not "not NONE": §3.1, §3.2, §3.3; the stale `mLockTaskUid` noted in §2.
- **M3** Home's relaunch: no unregister while `isChangingConfigurations` (§3.2, §5), a source scan (§10), gate row 12.
- **L1** `NO_HOME` closed without a wait where it can be: §3.4 (bring Home, skip the emptying once, a follow-up pass and
  a ~3 s timer; the `contentAllowed()` and screen-off limits), gate row 8.
- **L2** the `chromeLocked` check-then-act noted (§3.2; not new).
- **L3** a failed step 4 after a successful step 3: NONE with the switch on until Home's next resume, acceptable (§3.1).
- **L4** wording: `showInCallScreen` also after a stop that found NONE; the record without a task's
  `IllegalArgumentException` and the broad catch (§2, §3.2); §7's one-line `performStopLockTask` difference.
- **L5** tests and rows: the "only in the unpin" scan, PINNED rows, the `isChangingConfigurations` scan (§10); gate rows
  11 (end and start within ~1 s) and 12 (font size); rows 2, 3, 6 and 9 on both images (§11).

## 15. The build (2026-11-27, branch `kiosk-root`)

`lock/HomeRootStop.kt` (the stop, the resumed-activity tracker, the stuck check, §3.4), the pure rules in
`lock/LockTaskRoot.kt` (`LockTaskRootTest`), `LockTaskChrome.writeLockTask`'s unpin, `HomeActivity`'s register/unregister
and its stop's `PinLockRuntime.requestChromePass`; the source scans in `HomeFrontTest`. Where the build fills in what the
design left open:
- `homeRootStopAnswer` reads NOT_LOCKED first, then `LOCKED_CHROME`, then `NO_HOME`: §4's boot cover and process-start
  rows (no Home, nothing locked) answer NOT_LOCKED, never a bring.
- `unpinStep` has a third answer, `WAIT_FOR_HOME`: a pass while the brought Home is awaited (brought, the 3 s not over)
  leaves the list too - emptying it then would clear Home's root record before its stop.
- A pass that brings Home or waits for it schedules no stuck check (it would log stuck before Home resumes); the 3 s
  pass does.
- The retry's tracker skips `PinLockActivity` (the glance's lock task is its own, `lockTaskEntry`/`lockLeave`); with no
  activity of ours resumed the retry answers `NO_HOME`.
- `NOT_LOCKED` is never logged (every kiosk-off pass answers it); the stuck line is once per kiosk-off spell, as
  `REFUSED`/`NO_HOME`.

Code QA (2026-11-27, 0H 1M 4L), all taken:
- **M1** a second unpin pass and Home's second stop could clear Home's task (from a task no longer the root,
  `clearLockedTask` clears it; `mLockTaskUid` is never reset) and the gap's new root with it (the setup flow's return,
  the in-call screen): `HomeActivity.reconcileKioskMode` asks for a chrome pass only while a brought Home is awaited
  (`HomeRootStop.homeStopped()`, `KioskEndSpell.awaitingHome`); Home's stop runs once per kiosk-off spell until it answers
  ASKED - Home's own stop at a resume counts too - and later passes answer `ASKED_BEFORE` (stuck check due, the list
  emptied); LOCKED with the kiosk off is then the retry's job. §3.2's "clears only Home's own task" corrected; gate row 7
  also records the return to the flow and Home not recreated.
- **L1** the retry runs at most once per kiosk-off spell (`KioskEndSpell.takeRetry`), so its line is once per spell too.
- **L2** §5: a quick end and start can leave the kiosk on with lock task NONE until Home's next resume (row 11).
- **L3** the stuck line names the answer of the pass that scheduled the check, not the last pass's.
- **L4** the order scan also checks that a step other than EMPTY returns before `setLockTaskPackages` and that
  `HomeRootStop.kioskOn()` is in the pin branch.

Night gate on 16 (`docs/testing/emulator.md` §6ch F1): row 9's call screen did not come back - `KidInCallService` had no
`onBringToForeground`; since branch `incall-front` it starts `InCallActivity` while a call is live (re-run owed on 14 and 16).

The night gate on 14 (2026-11-27, emulator.md §6cg F1), branch `night-fixes`: on 14 and 15 `stopLockTask()` is oneway, so
the emptying could beat Home's stop (8 of 37 kiosk ends; 5 stuck). §3.5 adds the bounded wait between Home's stop and
the emptying (`HomeRootStop.settle`, pure `stopSettle`, 10 ms reads up to 300 ms after the stop, the pass's `ASKED` and
Home's own stop alike; only on 14 and 15, `stopIsOneway` - code QA L1: on 16 a wait would only widen §2's gap); §2 and
§7 corrected. Rows 2, 3 and 12 re-run on 14 on 2026-11-28: 11/16/11 of 11/16/11 pass (`docs/testing/emulator.md` §6ci); row 9 passes on 14 and 16 (§6ci, §6cj).
