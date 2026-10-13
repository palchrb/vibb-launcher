# QA 32 step 2 (code) - the keyguard cover behind a debug switch; no splash or animation for an awake lock start

Reviewer: code QA, 2026-10-13. Branch `design-32-s2`, commit `54a7979d` (from `main` at `a5d30b92`). I read the
step against:
- the root and launcher `CLAUDE.md`;
- `docs/design/32-lock-flash.md`: §8 step 2, §11, §12 and §15;
- `qa-32-design.md` and `qa-32-s1-code.md` (this file sits next to them in `docs/design/`; the brief named
  `docs/review/`, where neither exists);
- `docs/testing/emulator.md` §6k and `scripts/smoke-test.sh`'s new "Double press" step.

I checked the code the step touches:
- in `lock/`: `KeyguardCover`, `KeyguardCoverPlan`, `PinLockRuntime` (`init`, `dispatch`, `lockUpAtScreenOff`, the
  screen receiver, `onVoipPhase`, `sendVoipRingScreen`, `configure`, `guardTripped`, `show`, `lockStartOptions`,
  `onLockResumed/Paused/Stopped/Destroyed/NewIntent`, `runRefrontCheck`), `PinLockActivity` (the dismissal,
  `onNewIntent`), `LockUpPlan`, `RefrontPolicy`, `BootCover`'s shutdown receiver;
- `ui/HomeActivity.dismissBootKeyguard`, `server/ScreenTimeout`, `server/AppEnforcer.apply`,
  `server/OwnPermissions`, `timerules/TimeRulesRuntime.recheck`, `res/values/styles.xml`, `res/xml/device_admin.xml`;
- `src/debug`'s `KeyguardCoverDebug` and `KeyguardCoverDebugReceiver`, `src/release`'s `KeyguardCoverDebug`,
  `app/build.gradle.kts` (`CheckDebugHook`), the debug manifest;
- the tests the step adds or changes (`KeyguardCoverPlanTest`, `LockUpPlanTest`, `DebugHookAbsentTest`).

AOSP 16 (`android16-release`) was read from the copies in the session scratchpad:
- `ActivityRecord`: `determineLaunchSourceType` `:2214-2228`, `launchedFromSystemSurface` `:2200-2204`,
  `validateStartingWindowTheme` `:2231-2271`, `addStartingWindow` `:2274-2352`, `getStartingWindowType`
  `:2392-2450`, `applyOptionsAnimation` `:5037-5075`, `allowTaskSnapshot` `:8902-8924`;
- `ActivityTaskSupervisor.updateHomeProcessIfNeeded` `:1091-1105`, `ATMS.onCleanUpApplicationRecord` `:6326-6331`;
- `Task.startActivityLocked` `:5319-5324`, `Task.moveTaskToFront` `:5724-5771`;
- `TaskFragment.resumeTopActivity` `:1587-1606`;
- `Transition.setOverrideAnimation` `:1013-1023`, `overrideAnimationOptionsToInfoIfNecessary` `:2050-2078`;
- Shell's `DefaultTransitionHandler.loadAnimation` `:838-903`;
- `KeyguardController.dismissKeyguard` `:332-347`, `updateKeyguardSleepToken` `:566-573`;
- `DevicePolicyManagerService.setMaximumTimeToLock` `:6125-6149`, `updateMaximumTimeToLockLocked` `:6152-6177`.

Line numbers are `54a7979d`'s. Launcher paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`. No
product code was changed: the mutations below were temporary and reverted, and `git status` was clean before this
file was written.

**Verdict: the kid's PIN lock is never bypassable through the cover, and the release build has no switch. But the
two "all builds" fixes for the double press don't do what they claim, and the experiment's own state doesn't
survive a process restart.** Fix both before the device run, or the run will measure something else.

The lock:
- The cover's keyguard is the insecure Swipe screen. The PIN lock stays LOCKED through every path I followed:
  a failed cover, the fallback, a VoIP call, the crash guard, a reboot, a new process.
- The kid can't get stuck behind it: the Swipe screen always swipes, and `setKeyguardDisabled(true)` dismisses it.
- Swiping it un-occluded never lands anywhere the screen-on backstop doesn't then cover with the lock, as today.

The release build:
- No receiver, no prefs name and no knob strings (dex scan below).
- `KeyguardCover.switchOn` is always false there, which keeps every cover path inert.
- It writes what step 1 wrote, plus one `getMaximumTimeToLock` read per apply.

What needs fixing:
- **M1:** `windowDisablePreview=true` is ignored for every lock start, because handy *is* the home process. And
  `makeCustomAnimation(0, 0)` is thrown away by `FLAG_ACTIVITY_NO_ANIMATION`. Even without that flag, it isn't
  applied to the task changes of a cross-task start. So the splash and the card shrink from the user's mash log
  stay, while the spec, `CLAUDE.md`, the theme comment and two test pins say they are gone.
- **M2:** `coverOn`, `armed` and `inputs` are memory only, but what the cover wrote to the DPM outlives the process.
  - The crash guard's "off at once" (L8) is a no-op in the process where the guard trips.
  - A new process's first re-arm writes UNTOUCHED (`setKeyguardDisabled(false)`, the camera bit cleared) from null
    inputs.
  - A shutdown before the new process's first COVER leaves the keyguard on across the reboot.

Counts: 0 High, 2 Medium, 5 Low, 4 Nits.

## What I ran (under the shared build lock, `--no-daemon --max-workers=2`)

- **On `54a7979d`, unchanged:** `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`, with the
  throwaway stub `tsnet.aar` (removed afterwards).
  - BUILD SUCCESSFUL, **1121 tests, 0 failures**: 24 in `KeyguardCoverPlanTest`, 21 in `LockUpPlanTest` and 6 in
    `DebugHookAbsentTest`.
  - `checkDebugHasDebugHook: hook present`, `checkReleaseHasNoDebugHook: no debug hook`.
- **A dex scan of both APKs** (counts of the string in `classes*.dex`):

  | String | release | debug |
  |---|---|---|
  | `KeyguardCoverDebugReceiver` | 0 | 11 |
  | `keyguard_cover_debug` | 0 | 1 |
  | `failDismiss`, `skipDismiss` | 0 | 1 each |
  | `KeyguardCoverDebug` | 2 (the release stub) | 14 |
  | `makeCustomAnimation` | 1 | 2 |

- **Mutation runs.** Each mutation was applied, then reverted, and the files were compared with a copy taken
  before.

  | Batch | Mutation | Result |
  |---|---|---|
  | A | `KeyguardCover.check`: `if (dismissFallback(failures))` -> `if (false && ...)` (`KeyguardCover.kt:305`) | **survives** |
  | A | `whenUnlocked` without its at-once path (`if (!mustWait(context)) { send(context); return }`, `:347-350`) | **survives** |
  | A | `disableNow` without `armed = false` (`:167`) | **survives** |
  | A | `lockUpAtScreenOff`: `val cover = KeyguardCover.coverOn` -> `true` (`PinLockRuntime.kt:629`) | **survives** |
  | A | `write` without `if (wasOn && !coverOn) handler.post { runWaiting(...) }` (`KeyguardCover.kt:149`) | **survives** |
  | A | the screen receiver without `KeyguardCover.onScreenOff(...)` (`PinLockRuntime.kt:737`) | **survives** |
  | B | `dismissFallback`: `>= 2` -> `>= 3` | red: `KeyguardCoverPlanTest` "the fallback at the 2nd failure" |
  | B | `newIntentPauseKept`: `0..BACKSTOP_NEW_INTENT_MS` -> `0..10_000L` | red: `LockUpPlanTest` "the backstop's new-intent pause keeps lockOnTop" |
  | B | `onLockNewIntent`: `sameLockUp = newIntentPauseHeld` -> `true` | red: `LockUpPlanTest` "the new-intent wiring" |
  | B | release `KeyguardCoverDebug.enabled`: `= false` -> `= true` | red: `DebugHookAbsentTest` "release KeyguardCoverDebug is always off" |
  | B | `keyguardMode` without `voip == VoipPhase.NONE` | red: `KeyguardCoverPlanTest` "COVER only with the switch ..." |
  | B | the cover off: `current and coverAdded.inv()` -> `current` (no bits taken out) | red: 3 tests in `KeyguardCoverPlanTest` (cover off, DISABLED, the fallbacks) |
  | B | `lockOnTopAfter(NEW_INTENT)` -> `true` | red: `LockUpPlanTest` "the backstop's new-intent pause keeps lockOnTop" |
  | B | `refrontAction` without the `DarkLockUp` row | red: `KeyguardCoverPlanTest` "refrontAction - ... DarkLockUp" |

  - Batch A ran as one build, the whole suite, and stayed green.
  - Batch B ran one mutation at a time against `com.kidslauncher.mdm.lock.*`.
  - Two of batch A's survivors change the **release** build, see L2.
- **Source pins checked by reading.** `KeyguardCoverPlanTest`'s and `LockUpPlanTest`'s pins each match the line they
  guard. The three DPM setters appear only in `KeyguardCover.kt`, and `requestDismissKeyguard` only in the lock and
  Home. Exact-string pins break at a reformat, but a behaviour change doesn't slip past them.

## Medium

### M1. The double-press fixes are no-ops: the lock's splash is shown regardless of `windowDisablePreview`, and `makeCustomAnimation(0, 0)` never reaches Shell

**Where:**
- `res/values/styles.xml:69`: `PinLockTheme`'s `windowDisablePreview=true`, and its comment `:55-63`;
- `lock/PinLockRuntime.kt:1108-1113` and `:1125-1133`: `lockStartOptions`;
- `LockUpPlanTest` "every lock start has no starting window and no animation for any change";
- the spec's §15 "The double press", the launcher `CLAUDE.md` bullet, and emulator.md §6k's last block.

**The splash.**
- `validateStartingWindowTheme` honours `windowDisablePreview` only when `!launchedFromSystemSurface()`
  (`ActivityRecord:2263`).
- `launchedFromSystemSurface()` is true for `LAUNCH_SOURCE_TYPE_HOME`. That is any start whose caller process
  `isHomeProcess()` (`:2220-2221`).
- The home process is the process of the realized home activity (`updateHomeProcessIfNeeded`). `HomeActivity` and
  `PinLockActivity` share handy's one process.
- So every `show()` from handy is a "system surface" start, and the flag is skipped.
- The double press's start is a task switch to a lock that exists. `allowTaskSnapshot()` is true (the same
  component, no extras). Without a usable snapshot, `getStartingWindowType` returns SPLASH_SCREEN (`:2424-2431`).
  That is the `VRI[debug]` splash from the mash log, and it stays.
- The base theme's own comment already says this (`styles.xml:29-34`): Android ignores the flag for a start from
  "the launcher" - and handy is the launcher.
- The one case where the flag does act is a start from a handy process that isn't the home process yet. That is a
  new process (an update, a crash; `ATMS:6329` clears `mHomeProcess` at death) before Home has been realized in
  it, which is `ProcessStart`'s "the lock at once with the screen on" (QA 10 #4). There the black splash used to
  cover the old frame at once. Now the old frame (an app, Home) stays, and stays touchable, through the lock's
  cold start.
- That isn't a bypass: the kid was in that app. But it is the opposite of design 32's direction, in exactly the
  one case the change reaches.

**The animation.**
- `show` keeps `FLAG_ACTIVITY_NO_ANIMATION` (`:1110`). For a new lock, `Task.startActivityLocked` puts it in
  `mNoAnimActivities` (`Task:5319-5321`). For the lock's task brought to front, `moveTaskToFront(noAnimation = true)`
  adds it and calls `ActivityOptions.abort(options)` (`Task:5733-5735`, `:5767-5771`).
- `TaskFragment.resumeTopActivity` then takes `anim = false` and calls `next.abortAndClearOptionsAnimation()`
  instead of `applyOptionsAnimation()` (`:1587-1606`). The `ANIM_CUSTOM` options never reach
  `Transition.setOverrideAnimation`.
- Even without the flag, it wouldn't help. `makeCustomAnimation` has `overrideTaskTransition = false`, and WM
  copies the options only onto activity-level changes or tasks with `getOverrideTaskTransition()`
  (`Transition:2058-2077`). Shell applies `ANIM_CUSTOM` only `!isTask || getOverrideTaskTransition()`
  (`DefaultTransitionHandler:868-869`).
- The lock has its own task (`taskAffinity=.pinlock`), so the closing app is a Task change. It gets Shell's default
  task-close animation: the card.
- The public `ActivityOptions` API has no way to set `overrideTaskTransition`.
- The call can't crash (any context, any thread; no permission check for `ANIM_CUSTOM`), but it does nothing.

**The effect.**
- Nothing in release gets worse, apart from the new-process case above. But nothing the user reported is fixed,
  while §15, `CLAUDE.md`, the theme comment and `LockUpPlanTest` say it is.
- The smoke step (L3) will FAIL on a device where the press really cancels the fade.
- The §6k check "no card shrink and no slide" will fail.

**Fix:**
- Put `PinLockTheme` back to `windowDisablePreview=false`, which is step 1's decision 3: the splash is
  `windowSplashScreenBackground` black, so it already satisfies "the lock or black".
- Remove `lockStartOptions`. Or keep it with a comment that it is inert, but then don't pin it as a fix.
- Correct §15, the `CLAUDE.md` bullet, the theme comment and the test name.
- Record the double press as a residual in §12: a black splash, plus Shell's default task-close animation of the
  app the kid was already in.
- Change the smoke step to expect the black splash (or to check its colour), not its absence.
- If the card must go, it needs a different mechanism, which is a design question. The app has no public
  task-transition override.

### M2. The cover's DPM state outlives the process, but `coverOn`, `armed` and `inputs` don't

**Where:**
- `lock/KeyguardCover.kt:36-68`: the memory-only state;
- `:115-125` `rewrite` and `:128-136` `write` (`inputs ?: Inputs(managed = false, ...)`);
- `:158-175` `disableNow` (`if (!coverOn) return`) and `:178-184` `guardTripped`/`onShutdown`;
- `:242-256` `onLockResumed`.

`setKeyguardDisabled(false)`, the ALL features and T are device-policy state. They persist through an update, a
crash, an `am crash`, a low-memory kill and a reboot without `ACTION_SHUTDOWN`. A new process starts with
`coverOn = false`, `armed = false` and `inputs = null`. The first `configure` comes from the process-start sync's
`AppEnforcer.apply`, after a policy fetch (`MdmSyncWorker.kt:163`), so seconds later, and longer offline with
timeouts. Until then:

1. **The crash guard's L8 is dead.**
   - The guard trips in a `PinLockActivity.onCreate` after crashes recorded by the uncaught-exception handler.
     Each of those killed the process.
   - So the trip always runs in a new process, where `coverOn` is false, and `disableNow` returns at once
     (`:159`). `inputs?.copy(...)` is a no-op on null.
   - The old process's Swipe keyguard stays, with the handy lock off, until the guard's `requestSync` applies.
     §6k's check "`disabled now (crash guard)` at once" will fail.
2. **A new process's re-arm writes UNTOUCHED.**
   - With the switch on (loaded at `init`), the first lock resume with the screen on sets `armed` and calls
     `rewrite`.
   - Before the first apply, `write` builds `Inputs(managed = false, hasKidPin = false, ...)`. `keyguardMode`
     gives UNTOUCHED, and `off` calls `setKeyguardDisabled(false)`, clears B2's `SECURE_CAMERA` (`managed = false`)
     and sets T to 0. `coverOn` stays false.
   - From then until the first apply:
     - the keyguard is the Swipe screen and nobody dismisses it, because `dismissDueNow` and `check` need
       `coverOn`;
     - `whenUnlocked` doesn't wait;
     - after a PIN unlock the kid gets SystemUI's Swipe screen;
     - VoIP's DISABLED isn't written.
   - `onVoipPhase` takes the same path.
   - This is every boot (the lock's first resume beats the first sync), every update and every crash restart. It
     contradicts M4's "a new process writes DISABLED until then" (§15, §6k).
3. **A shutdown before the new process's first COVER keeps the keyguard on across the reboot.** The steps:
   - an update or a crash leaves the old process's COVER in the DPM, or 2's UNTOUCHED;
   - then a clean reboot: `onShutdown` sees `coverOn == false` and does nothing;
   - the boot comes up with the Swipe screen.
4. **Smaller, same root.** `disableNow` checks `coverOn` outside the monitor:
   - A `configure` that is writing the first COVER when `ACTION_SHUTDOWN` arrives finishes after the check and
     leaves `false` in force.
   - `armed = false` at shutdown is undone by any later lock resume with the screen on. A following apply then
     writes COVER again.

None of these unlocks anything: the keyguard is insecure, and the PIN lock stays LOCKED. But each one is the cover
on when the design says off, or off-but-still-there with nothing dismissing it. The device run would record those
as the experiment's behaviour.

**Fix:**
- Persist "the DPM may hold the cover". For example, `cover_written` in prefs `keyguard_cover`, committed before
  `setKeyguardDisabled(false)` and cleared after a `true` that worked.
- Use it in three places:
  - in `PinLockRuntime.init`'s IO pass: if set, write DISABLED at once (`setKeyguardDisabled(true)`, which also
    dismisses a keyguard a non-clean reboot left up), the cover's bits out and T 0. That is M4's "held off" made
    real, and it covers 1 and 3;
  - in `disableNow`: act on the persisted flag, not on `coverOn`;
  - in `rewrite`: return while `inputs == null`. The first `configure` writes with `armed` already set.
- For 4: a `shuttingDown` flag set under the monitor that forces `forceOff` in `write`. Check `coverOn` inside the
  monitor.
- Pure tests: the process-start write, and that `rewrite` before `configure` writes nothing. Pins for the call
  sites.

## Low

### L1. Home's boot dismissal can never run: Home isn't resumed under an un-occluded keyguard

**Where:** `ui/HomeActivity.kt:479` (called only from Home's locked resume) and `:510-529`.

**What happens.**
- While the keyguard shows and isn't occluded, `KeyguardController.updateKeyguardSleepToken` holds the keyguard
  sleep token (`:566-573`). The activities behind it sleep.
- So Home is never resumed while the cover's Swipe screen is up un-occluded, and `dismissBootKeyguard` never sees
  `isKeyguardLocked`.
- Once the keyguard is occluded, it is the lock on top, not Home.
- After an `adb reboot` with the cover left on, the Swipe screen therefore stays until one of three things:
  - the kid swipes it;
  - the boot mark's backstop starts the lock (showWhenLocked, so it occludes the screen), and the lock dismisses it
    once a COVER apply has set `coverOn`;
  - the first apply writes DISABLED, if it comes before the first lock resume (see M2.2).
- §6k's "until Home dismisses it" and the `CLAUDE.md` bullet's "Home dismisses it at boot" don't hold.

**Fix:** M2's process-start DISABLED write makes this path unnecessary; remove it then. Otherwise move the boot
dismissal to the lock's resume (showWhenLocked, so `visibleIgnoringKeyguard`). Correct §6k and `CLAUDE.md`.

### L2. Two release-relevant guards and four cover guards are unpinned (batch A)

- **`whenUnlocked`'s at-once path** (`KeyguardCover.kt:347-350`).
  - Without it, the release build (where `check` never runs) would never send the call screen at a lock resume
    during our call, the VoIP call screen, or the time-rule screen at a screen-on.
  - The whole suite stays green. The tests pin the call sites and `mustWait`'s body, not `whenUnlocked`.
- **`val cover = KeyguardCover.coverOn`** (`PinLockRuntime.kt:629`). `true` there drops Home's stand-in from every
  dark lock-up in release, which undoes step 1, and stays green. The pin checks only that `cover` is passed.
- **The experiment's own guards:** `check`'s fallback call (`:305`), `disableNow`'s `armed = false`, `write`'s
  `runWaiting` when the cover goes off, and the screen receiver's `onScreenOff`. Without the last one, a fallback
  never re-arms in that process.

**Fix:** pins.
- `whenUnlocked` starts with `if (!mustWait(context)) {` followed by `send(context)` and `return`, and `mustWait`
  starts with `coverOn &&`.
- `lockUpAtScreenOff` has `val cover = KeyguardCover.coverOn`.
- `check` calls `fallback(context)` under `dismissFallback(failures)`.
- `disableNow` sets `armed = false`.
- `write` posts `runWaiting` on `wasOn && !coverOn`.
- The receiver calls `KeyguardCover.onScreenOff(interactive(app))` after the `ScreenOff` dispatch.

### L3. The smoke test's double-press step can pass without a double press

**Where:** `scripts/smoke-test.sh:906-950`.

**What happens.**
- `input keyevent KEYCODE_SLEEP KEYCODE_WAKEUP` doesn't guarantee a cancelled screen-off fade. On the emulator the
  display can go fully off first.
- Then the lock-up is a dark one: `okToDisplay` is false, so there is no starting window. The step PASSes whatever
  the theme says.
- The step prints the `Lock-up at screen-off` line but doesn't require `interactive=true`.
- Per M1, on a device where the press does cancel the fade, it will FAIL today.

**Fix:**
- SKIP the step (or retry it a few times) unless the lock-up line says `interactive=true`.
- After M1, assert the expected black splash rather than its absence, or check only that the lock is in front
  within N ms.

### L4. Under the cover the lock-up drops Home on the DPM state, not on the keyguard being locked at this sleep

**Where:** `lock/LockUpPlan.kt:43` (`coverOn -> LockUpStart.LOCK`) and `lock/PinLockRuntime.kt:629`.

`coverOn` means the DPM writes held. It doesn't mean SystemUI locked the keyguard at this sleep. Cases where it
doesn't:
- an app holds `KeyguardManager.KeyguardLock.disableKeyguard()`. `DISABLE_KEYGUARD` is a normal permission, and an
  insecure keyguard honours it;
- T is above the setting for a moment. KVM's lock delay after a timeout is `min(T - displayTimeout, lockAfter)`;
- SystemUI is restarting.

In each case, LOCK without Home is pre-step-1 behaviour: the app's snapshot is the stand-in at a quick wake. That
is the very frame the design removes, now in the debug experiment.

**Fix:**
- Plan LOCK only for `coverOn && keyguardLocked` read at the screen-off. A false "not locked" just brings back
  step 1's Home, which is the safe direction.
- Log `kg=` next to `cover=`.
- A §11 check: a test app holding a `KeyguardLock`, a timeout sleep, a quick wake.

### L5. A queued screen runs later without its condition being checked again

**Where:** `lock/KeyguardCover.kt:346-373`, and the three senders (`PinLockRuntime.kt:604-611`,
`TimeRulesRuntime.kt:227`).

**What happens.**
- A send queued by `whenUnlocked` runs at the next "unlocked" that `check` sees, at the dismissal callback, or when
  the cover goes off.
- If the lock paused before its dismissal, that can be long after the reason for the send is gone:
  - the call has ended (`showCall` then starts `InCallActivity` for no call);
  - the rule has changed again (`LockActivity.start` comes over the lock, then `TimeRuleShown` re-fronts it);
  - the mode is no longer LOCKED.
- Also, `InCallActivity` is `showWhenLocked`, so it can't un-occlude the keyguard. Holding it only protects the
  lock's own dismissal, at the cost of up to ~2 s without the call screen during a live call when a dismissal
  fails.

**Fix:**
- Re-check each queued send's condition when it runs (`ourCall()`, the rule's reason, `mode == LOCKED`), or drop
  the queue at the lock's pause.
- Consider sending `InCallActivity` at once and waiting only for the VoIP app's and the time-rule screen.

## Nits

1. **`setMaximumTimeToLock` turns "Stay awake" off.** DPMS writes `STAY_ON_WHILE_PLUGGED_IN = 0` whenever a finite
   max time to lock is set (`updateMaximumTimeToLockLocked` `:6170-6174`), and nothing puts it back when T returns
   to 0. emulator.md §6f's `adb shell svc power stayon true` is silently undone by the cover. Say so in §6k.
2. **Release lost two warnings.** `off()` swallows a throwing `setKeyguardDisabled` and a failing features write
   without a log. Before, `PinLockRuntime` logged `setKeyguardDisabled(...) failed` and `AppEnforcer` logged
   `Failed to set the keyguard camera feature`. `KeyguardCover`'s log line prints only with the switch or the cover
   on. Log the exceptions in `off()` unconditionally.
3. **`checkKeyguard` keeps polling every second.** That is `isKeyguardLocked`, `isInteractive` and `renderVoip`,
   while the lock is resumed and the switch is on, even when the cover is off (fallback, VoIP). Stop the loop while
   `!coverOn`.
4. **Two stale values.**
   - `backstopOntoTopAt` is cleared only by `onLockNewIntent` and the next `show`, not by a stop, resume or
     destroy. That is harmless (the 1 s window, and any `onNewIntent` means the lock was just started on top), but
     clearing it with `newIntentPauseHeld` is tidier.
   - `describe()` after `disableNow` shows the last `write`, not the `true` it just wrote.

## The implementer's interpretations and additions

I judged §15's choices in order.

1. **One writer, reached from `configure`, `rewrite`, `followTimeout`/`timeoutChanged` and `disableNow`:** right.
   - The setters are pinned to `KeyguardCover.kt`.
   - `AppEnforcer.applyKeyguardFeatures` is gone. The features are now written before `BootCover.applyPolicy` and
     `LockTaskChrome.applyPlan` in the same apply, which changes nothing they read.
   - The crash guard's re-arm before the write is right: the write needs `guardTripped`.
2. **COVER's order (features, read back, T, read back, then `false`; any failure DISABLED with `lockMayRun`):**
   right, and pinned with a fake DPM. `coverAdded` is committed before the features write (M6), and a failed commit
   aborts the cover before anything is written.
3. **DISABLED/UNTOUCHED write what step 1 wrote, plus one `getMaximumTimeToLock`:** right. Release writes T 0 only if
   it reads non-zero, and nothing else in handy sets it.
4. **T (H1):** right.
   - Growing writes the setting first and shrinking writes T first, so the effective lock delay
     `max(min(T - setting, lockAfter), 0)` is 0 at every point.
   - Our own setting write fires the observer, which sees `currentMs == target`.
   - The "can't lengthen in Release" side effect is a real one-way ratchet while the cover is on, and §6k states it.
5. **`armed` and the "until the next dark screen-off" re-arm:** a sensible reading of M7. It is undermined in a new
   process by M2.2.
6. **The dismissal by state, 1 s after the request, fallback at 2:** right.
   - The callbacks are only logged. `KeyguardController.dismissKeyguard` fails the callback at once when the
     activity isn't `visibleIgnoringKeyguard`, so counting by state is the robust choice.
   - Failures count across wakes ("in a row"). With a stale `requestedAtMs` from a wake the lock paused through,
     the first check of the next resume can count one more failure. That is acceptable: the fallback is the safe
     direction.
7. **`whenUnlocked`'s set:** fine for the VoIP app's call screen and the time-rule screen (not `showWhenLocked`).
   It is debatable for `InCallActivity` (L5).
8. **VoIP latched only with the screen on:** right. A ring from dark keeps the cover until the lock resumes lit, and
   the ring screen waits without spending its try.
9. **`DarkLockUp`:** right.
   - Reachable only with the cover on, dark, LOCKED, the lock not resumed, `lockOnTop` false and no yield.
   - It runs `lockUp(LOCK)` once, which sets `lockOnTop`, so it doesn't loop.
   - The dark `system_call` yield keeps a 2 s check alive during a dark system-dialer call. That costs a little
     power, and only with the cover.
10. **The lock alone under the cover:** right in principle; L4 for when `coverOn` isn't the keyguard.
11. **The ScreenOn backstop's held pause:** right, see "Verified OK".
12. **The double press:** the root cause in §15 is right (`SCREEN_OFF` with `interactive=true`, a visible launch).
    The two fixes are M1.

## Verified OK

- **The PIN lock is never bypassable through the cover:**
  - COVER is chosen only where step 1 already disabled the keyguard: managed, a kid PIN, no credential.
    `lockActivation` keeps the lock active then; a bad hash stays active too.
  - Every failure (features, `cover_added`, timeout, T, `false`) falls back to `setKeyguardDisabled(true)` with
    `lockMayRun` from that call, as before.
  - The cover never writes `setKeyguardDisabled(false)` before both of its read-backs.
  - The keyguard it shows is insecure.
    - Swiped un-occluded, it leads to whatever is on top. That is never more than without the cover: the ScreenOn
      backstop starts the lock at `SCREEN_ON`, a system-dialer call that ends while dark gets `DarkLockUp`, and
      after the PIN unlock the kid is unlocked anyway.
    - The emergency dialer, the alarm, `InCallActivity`, `VoipWakeActivity` and the lock are `showWhenLocked`
      (pinned).
    - The glance opens only after the unlock, and the music lock panel opens nothing.
  - `LOCK_TASK_FEATURE_KEYGUARD` is always on in both lock-task configurations (`LockTaskHelpers.kt:101,121,133`),
    so `LockTaskController.setKeyguardState` never disables the cover behind our back. §6k warns about §6e.
- **Nobody is stuck:** an insecure keyguard always swipes. The fallback's `true` dismisses it. The crash guard's
  intent is the swipe at worst (M2.1).
- **The release build has no switch:**
  - `src/release`'s `KeyguardCoverDebug` is one `fun enabled(...) = false` (pinned).
  - The receiver and the prefs name are absent from the release dex and banned in `src/main` and `src/release`
    (`DebugHookAbsentTest`, `CheckDebugHook`).
  - With `switchOn` always false:
    - `write`'s mode is never COVER (`coverWanted = switchOn && armed`);
    - `onLockResumed` and `onVoipPhase` never `rewrite`;
    - `coverOn` stays false, so `disableNow`, `setT`, `followTimeout`'s T and `ScreenTimeout`'s observer branch are
      inert;
    - the lock's first-frame watch, the state check and Home's boot dismissal return at once;
    - `whenUnlocked` sends at once and `mustWait` is false;
    - `lockUpStarts` and `refrontAction` get `coverOn = false`, which is step 1's table (pinned by
      `KeyguardCoverPlanTest`'s "the cover off: as before" rows).
  - Release reaches `setKeyguardDisabled(false)` only through UNTOUCHED, exactly where step 1 wrote `false`.
- **The ScreenOn backstop's held pause can't leave `lockOnTop` true with the lock not on top:**
  - The hold is set only on a pause within 1 s of a same-lock-up start onto a lock that was marked on top. It is
    turned back into `lockOnTop` only by `onNewIntent`.
  - `onNewIntent` clears `backstopOntoTopAt`.
  - `show` resets the hold before every start. `lockUp`'s start sets `lockOnTop` itself.
  - A client gets its transactions in order. If something covers the lock right after the backstop's start, the
    lock gets [pause, new intent, resume] and then [pause]. The second pause finds `backstopOntoTopAt == null`
    and clears the mark.
  - When the backstop's start finds the lock not resumed, the new intent comes without a pause, and
    `NEW_INTENT -> current || false` keeps what was there.
  - Any `onNewIntent` at all means a start just brought the lock to the front.
- **The cover's feature bits (M6):** `coverAdded` is exactly what the cover added, and the off path takes out only
  those, then applies B2's rule. A parent's or another tool's bits survive (pinned).
- **`makeCustomAnimation(context, 0, 0)` is safe** from the application context and from any thread. No permission
  is checked for `ANIM_CUSTOM`, and a `START_DELIVERED_TO_TOP` just aborts the options. It is only inert (M1).
- **Threads:**
  - The writer is one daemon executor.
  - `configure`, `rewrite`, `disableNow`, `followTimeout` and `timeoutChanged` take the object's monitor, and
    nothing under it takes `ScreenTimeout`'s.
  - `failures`, `requestedAtMs` and `waiting` are touched only on the main thread: `write` posts `runWaiting`, and
    `whenUnlocked` posts itself.
  - The one main-thread wait is `onShutdown`'s, behind at most one apply's DPM calls, inside the shutdown
    broadcast's budget.
  - The first-frame `OnDrawListener` is removed on the first draw, the pause, or a dead observer, so it doesn't
    leak.
- **Docs:**
  - §15 and §6k match the code apart from M1 (the double press), M2 (the new process, the crash guard) and L1
    (Home's boot dismissal).
  - The launcher `CLAUDE.md` bullet repeats M1's and L1's claims.
