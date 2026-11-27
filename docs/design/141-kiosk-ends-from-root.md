# Design 141: the kiosk ends from its root

Status: design, 2026-11-27 (branch `kiosk-root-design`); PLAN "Fix backlog" 1k. Launcher only, no server or contract
change (the optional report in §3.3 is Q1). Android 14-17: AOSP `android14-release`, `android15-release`,
`android16-release` and `main` read 2026-11-27 - every function below is the same code in all four (§7); the APIs are
`Activity.stopLockTask` (API 21) and `ActivityManager.getLockTaskModeState` (API 23), no version gate.

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

## 2. Why a stop from the root fixes it (AOSP)

- `Activity.stopLockTask()` is one binder call with the activity's token (Activity 9019-9021, 16: 9626-9628; ACC
  1218-1220 -> ATMS `stopLockTaskModeInternal` 2613-2639, 16: 2687-2713). No foreground check (only the start has
  "Invalid task, not in foreground", ATMS 2590-2593, 16: 2667-2670) and no thread check. A token whose record is gone
  returns silently (`r == null -> return`).
- `stopLockTaskMode` (LTC 492-530): nothing while NONE; else the caller's uid must be the task's `mLockTaskUid` - ours,
  since Home's `startLockTask` set it (LTC 652-653) - then `clearLockedTask(callerTask)`.
- `clearLockedTask` (LTC 551-568): **from the root** it first clears every other locked task, newest first (each:
  `removeLockedTask` + `performClearTaskForReuse` + a resume of the focused task), whatever its auth - the
  `LAUNCHABLE_PRIV` blocked task included -, then removes the root **without clearing it** (the list is empty, it
  returns before the clear) and posts the end of lock task (`removeLockedTask`, LTC 574-584). From a task that isn't
  the root it removes and clears only that task (Activity's own doc: "will not terminate the LockTask mode, but only
  finish its own task").
- So: Home stops lock task from its own task while it is the root -> every other locked task is closed, Home stays as
  it is, the locked list is empty at once. The `setLockTaskPackages(admin, [])` after it finds no locked task: nothing
  removed, no Home restart, so neither of §6af's races can happen (both were the system's Home restart inside that
  call).
- The end of LOCKED itself is posted (`performStopLockTask`, LTC 587). In the gap a start is no violation (the locked
  list is empty, LTC 400) and never `BlockedAppActivity` (the block is lifted first, `isActivityAllowed` LTC 418-421).

## 3. The fix

### 3.1 The kiosk's end, in this order (`LockTaskChrome.writeLockTask`, `packages == null`)

1. `liftAppBlock` - unchanged (§6af part 1; now also guards §2's gap).
2. `kioskEnabled(false)` - unchanged (§6af part 2). It must come before step 3: Home, resumed by the clear of the task
   in front, reads it in `reconcileKioskMode` and must not start lock task again.
3. **New: `HomeRootStop.stop(context)`** - when lock task runs, Home's `stopLockTask()` (§3.2). Never throws.
4. `setLockTaskPackages(admin, [])` - unchanged, also the cleanup for anything started in step 3's gap and the whole
   fallback when step 3 couldn't stop it. A failed emptying puts the switch and the block back, as now (Home then
   re-roots at its next resume - the listener's `startLockTask` needs Home in front).
5. **New: the stuck check** (§3.3), 1 s later.

The pin (kiosk on) is unchanged. Every pass with the kiosk off runs this branch, so step 3 runs only while
`lockTaskModeState != NONE` - one cheap read; a phone with the kiosk off and nothing locked does nothing new.

### 3.2 `lock/HomeRootStop.kt` - Home's stop, called from the pass's own thread

- **Who is Home**: `HomeActivity` registers itself in `onCreate` and unregisters in `onDestroy` (only if it is still
  the registered one) - a `@Volatile WeakReference`. One instance: `singleTask`, typed HOME only (design 16 QA #1,
  `HomeFrontTest`); a `recreate()` keeps the same record and token.
- **The call**: `stop(context)`: no registered Home -> `NO_HOME`; `lockTaskModeState == NONE` -> `NOT_RUNNING`;
  `PinLockRuntime.chromeLocked` -> `LOCKED` (not ours to stop; cannot happen from this branch, kept as a guard); else
  `home.stopLockTask()` -> `ASKED`, a `SecurityException` (Home's task not locked: the stuck state of §1) -> `REFUSED`,
  any other exception -> `FAILED`. One log line per answer with fixed words; `REFUSED`/`NO_HOME` at most once per
  kiosk-off spell (the passes repeat).
- **Off the main thread, directly, no latch** (the suggested latch on main is rejected): `stopLockTask` is a plain
  binder call with the token (§2), safe from any thread. A latch would wait **under LockTaskChrome's monitor** (and
  `AppEnforcer.apply`'s) for the main thread, and the main thread takes that monitor itself - the LOCKED edge's pass
  (`PinLockRuntime.refreshChromeNow`) and the "sos" yield (`sosYield`). In that collision the latch would time out and
  fall back to the very path this fixes, and the lock's appearance would wait for the timeout. The direct call has no
  wait, no timeout and no stale posted stop to cancel. A pass on the main thread (a VoIP refresh) calls it inline - the
  same code.
- **Races with Home's own main thread** (all harmless; the system serialises them under `mGlobalLock`): Home's
  `kioskEnabled` listener (step 2 posts it to main) calls the same `stopLockTask()` - whichever runs second finds the
  locked list empty (`clearLockedTask` returns) or NONE (returns); Home's `onResume` after the clear reads the switch
  off and stops nothing new; a Home being destroyed at that moment gives a token without a record - a silent no-op,
  which the stuck check sees.
- **No root bookkeeping.** With the kiosk on and the phone unlocked Home is the root in every designed flow: Home
  roots it (design 16 #2), the glance roots only while LOCKED and re-roots through Home at its leave (`lockLeave`,
  `rootLeave`, design 59d A), and Home's record survives a process death in place (`stateNotNeeded`,
  `kiosk-escapes-and-updates.md` "Home stays the lock-task root"). If it isn't the root after all, Home's stop clears
  only Home's own task (§2) - which today's emptying does anyway - and step 4 runs as today.

### 3.3 The stuck check

1 s after a kiosk-off pass that found lock task running (main handler, one pending at a time): pure
`lockTaskStuck(state != NONE, kioskOn, chromeLocked, voipPinned)` - lock task on with the kiosk off, the chrome not
LOCKED and no VoIP pin is never ours (the kiosk-off lock task exists only LOCKED, `lockTaskWhileLocked`). Then one log
line "Lock task still on after the kiosk's end (Home's stop: <answer>)". Nothing on the phone can end it (§8), so the
only remedy is the parent's: restart the phone. Whether the PWA shows it is Q1.

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
| The setup hold (design 114, a pause with the hold flag) | `RestrictionsPause.startHold` -> `reapply` | unlocked | §3.1: the setup flow's task (`.setup`, a non-root) is cleared as today, Home is resumed (not restarted) and `SetupRun.takeReturnAfterPause` goes back to the flow - gate row 7 |
| Phase D / Android's lock as the face | the face never empties the list while LOCKED; with the kiosk on and Android locked the glance may root (59d A) and leaves via `rootLeave` (a root stop: it clears leftovers itself) | - | unchanged; §3.1 runs at the UNLOCKED edge |
| The boot cover (16b, 59d M) | lock task never survives a reboot; the cover runs in `:bootcover` and never registers as Home | NONE | step 3 skipped (`NOT_RUNNING`) |
| A process start's pass without a plan (`applyFallback`, stored switch off) | `refresh` | either | §3.1 if lock task runs; usually `NOT_RUNNING` |

A call live at the pause start: ATMS `stopLockTaskModeInternal` calls `TelecomManager.showInCallScreen(false)` after
every app stop (ATMS 2631-2636, 16: 2705-2710), so the in-call screen comes to the front. The glance's and Home's
listener stops already do this today; gate row 9 records it.

## 5. Home not alive or not in front

- **Behind another app** (the kid's app, our pages, Settings): works - no foreground check (§2). The app in front is
  closed if it is a locked task (as today), Home is resumed.
- **Screen off, not LOCKED** (the dark gap before Android's keyguard locks): works - the call needs no window. With
  Vibb's lock delay 0 (design 139 gate row 15) the screen off is LOCKED at once, i.e. §4's dark row.
- **Pause end -> start with another app in front**: at the end Home's listener asks `startLockTask` while not in front
  -> "Invalid task, not in foreground" (caught, design 16 QA #3) -> lock task stays NONE until Home's next resume. The
  start that follows finds NONE -> `NOT_RUNNING`, today's emptying (harmless). With Home re-rooted in between: Home's
  stop from behind the app, as above.
- **No Home in this process** (the launcher process died while another app was in front; Home's record stays in place
  and is restarted only when it resumes): `NO_HOME` -> today's emptying. The bug stays possible only when a leftover
  blocked task exists too; the stuck check logs it (gate row 8 documents the residual). Starting Home first and waiting
  for it was weighed and left out: it needs a wait under the monitor again (§3.2), and with the screen off Home doesn't
  resume behind the keyguard.

## 6. Threading

- The pass runs where it runs today (apply's IO thread, the chrome executor, rarely main) and holds the same two
  monitors; it gains one binder read and at most one binder call - no wait, no new lock, no callback into the app.
- Home's register/unregister are main-thread writes of one volatile field; the pass reads it once.
- The stuck check posts to the main looper and reads only `lockTaskModeState` and volatile fields - no monitor.
- No deadlock: nothing waits for the main thread, and the main thread's own monitor takers (§3.2) wait only for the
  pass's binder calls, as today.

## 7. Android 14-17

`stopLockTaskMode`, `clearLockedTasks`/`clearLockedTask`, `removeLockedTask` and `updateLockTaskPackages` are line for
line the same in `android14-release`, `android15-release`, `android16-release` and `main` (diffed 2026-11-27); so are
ATS's `LAUNCHABLE_PRIV` join, ATMS's `stopLockTaskModeInternal` with its in-call screen, ACC's token path and
`Activity.stopLockTask`. `BlockedAppActivity`'s manifest entry is the same on 14 and 16. Gate on 14 and 16 (the Jelly
Star runs 16); Android 17 is owed - no API 37 image runs here (`docs/testing/emulator.md` "Owed rows").

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
- **Reboot the phone when stuck** (`dpm.reboot`): would cut a call and surprise the kid; with step 3 the stuck state
  needs a process death and a leftover at once. Not offered.

## 9. What the kid and the parent see

- A pause or the kiosk's end with a leftover blocked task: apps open, as with none. Today: the stock launcher and
  nothing opens.
- Every kiosk's end: Home is no longer torn down and rebuilt (no launch splash, the grid where it was); the kid's open
  app still closes, as today.
- A pause started from Settings: Settings stays in front (Back goes to Home) - today often, now always.
- A call live at the kiosk's end: its screen comes to the front (§4).

## 10. Tests (pure, `LockTaskRootTest` and a source scan)

- `homeRootStopAnswer(registered, lockTaskState, chromeLocked, outcome)`: each answer of §3.2 (no Home, NONE, LOCKED,
  asked, refused, failed) - the runtime only feeds it.
- `lockTaskStuck(...)`: the table - kiosk off + running + unlocked + no VoIP pin -> stuck; any of kiosk on, LOCKED, a
  VoIP pin or NONE -> not.
- `kioskEndLogOnce`: `REFUSED`/`NO_HOME` logged once per kiosk-off spell, again after a kiosk-on pass.
- Source scan (with `HomeFrontTest`'s pattern): in `writeLockTask`'s unpin, `liftAppBlock` < `kioskEnabled(false)` <
  `HomeRootStop.stop` < `setLockTaskPackages(admin, emptyArray())`; `HomeActivity` registers in `onCreate` and
  unregisters in `onDestroy`; `setLockTaskPackages` appears only in `LockTaskChrome.kt`.
- `LockTaskHelpersTest`'s `unpinFeatures` rows stay.

## 11. Emulator gate (Android 14 and 16; 17 owed)

Setup: `jelly36` (and the 14 image), the branch's debug launcher as Device Owner, enrolled, kiosk on with the block
(`0x7f`), Home the root (`dumpsys activity activities`: `mLockTaskModeTasks` #0 is Home's task), the lock-task protolog
on (`wm logging enable-text WM_DEBUG_LOCKTASK`, after every boot), Chrome allowed. "A leftover" = `am start -n` of a
package neither pinned nor suspended (design 139 gate's), "App is not available" shown, then the Home key: two locked
tasks, the second `BlockedAppActivity`'s. Record per row: the protolog order, `mLockTaskModeState`,
`mLockTaskModeTasks`, Home's task id before and after, `wm_finish_activity`/`wm_on_create_called` for `HomeActivity`,
our `HomeRootStop` and stuck lines.

1. **Control on `main`**: a leftover, then a pause from Settings: stuck (LOCKED, the blocked task alone, the stock
   launcher, `Invalid uid` in our log) - proves the setup reproduces. OK on the blocked screen (or a reboot) clears it.
2. **The fix, a pause from Settings**: a leftover, the pause: the blocked task's `removeLockedTask` and Home's ("last
   task, reverting") **before** `Allowlisting 0:[]`; NONE within 1 s; Home's task id unchanged, no `HomeActivity`
   finish or create; Settings in front, Back -> Home; Chrome opens from Home. Ten times.
3. **A pause from the PWA** with Home in front and with Chrome (an allowed app) in front: as 2; Chrome's task closed
   (as today), Home resumed, not recreated.
4. **The pause's end re-pins**: "Lås igjen" and the PWA's end: LOCKED, Home #0, the list and `0x7f` back.
5. **The parent's kiosk switch off** (PWA) with a leftover: NONE, apps open; still NONE after the next sync and a
   screen cycle; the switch on again -> 4.
6. **Screen off during the unpin**: a leftover, the power key (the glance LOCKED), the PWA's pause in the dark: while
   dark the kiosk-off LOCKED row (our package + helpers listed, the leftover still locked); Android's PIN -> after the
   unlock NONE, no blocked task, Home there, apps open. The same with the parent's kiosk-off.
7. **The setup hold**: design 114's row 13 (the flow's "Sett på pause i 2 t" returns to the flow) with a leftover.
8. **The residual**: a leftover, Chrome in front, a kill of the launcher's process (`adb shell kill <pid>`, not
   `am force-stop`, which removes its tasks), the PWA's pause before Home was resumed: `NO_HOME`, the stuck line within ~1 s (documents §5's residual;
   OK on the blocked screen clears it).
9. **A live call**: `adb emu gsm call`, answered, Home in front, the PWA's pause: the in-call screen comes to the front,
   NONE, nothing else.
10. **(4i)'s leftover**: kiosk on, screen off, `adb emu power ac on` (a plug-in): after the unlock, is a
    `BlockedAppActivity` task left in `mLockTaskModeTasks`? If so, the pause -> NONE (as 2).

## 12. Size

S, launcher only: `lock/HomeRootStop.kt` (~70 lines with the pure answer), `LockTaskChrome.writeLockTask` (+~15),
`HomeActivity` register/unregister (+6), the stuck check (~30, pure rule in `LockTaskRoot.kt`), tests (~100); docs:
`docs/notes/launcher/hardening.md` "The kiosk's end", `kiosk-escapes-and-updates.md` "Home stays the lock-task root",
the gate section in `docs/testing/emulator.md` (the next free §). The gate: 10 rows on two images, ~2-3 h. Q1 yes adds
S: one status-report key (contract file + README row), the server's parse, one orange line in nb and en.

## 13. Question for the owner

**Q1 (visible): show a stuck kiosk in the PWA?** When lock task is still on 1 s after the kiosk's end (after this fix
only: a launcher process death plus a leftover blocked screen), the phone can report it and the PWA show one orange
line on the phone's page - "Kiosken henger" / "Kiosk stuck" - with "Start telefonen på nytt" / "Restart the phone"
behind the (i). (a) Yes (+S, a contract change in both dirs). (b) Log only for now (recommended until the gate's row 8
shows how often it can happen).
