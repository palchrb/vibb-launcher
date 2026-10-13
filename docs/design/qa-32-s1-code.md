# QA 32 step 1 (code) - Home under the lock at a dark lock-up, the black stand-in, no needless re-start

Reviewer: code QA, 2026-10-13. Branch `design-32-s1`, `83f720d0..df5426fc`. `83f720d0` merges `main` into the
design branch; `df5426fc` is the step. I read the step against:
- the root and launcher `CLAUDE.md`;
- `docs/design/32-lock-flash.md`: §8 step 1, §9, §10, §11 step 1, §13 and §14;
- `qa-32-design.md`;
- design 25's fix rounds 25b/25c/25f (`25-lock-glance.md`) and `qa-25b-25f-code.md`.

I checked the code the step touches:
- in `lock/`: `PinLockRuntime` (`dispatch`, `lockUpAtScreenOff`, `lockUp`, `show`, the screen receiver,
  `screenOffWork`, `onLockResumed/Paused/Stopped/Destroyed`, `runRefrontCheck`, `callLiveNow`), `PinLockState`,
  `LockUpPlan`, `PinLockActivity` (`onCreate`, `onResume`, `ensureLockTask`, `leave`/`go`), `HomeFront`,
  `LockTaskRoot`, `GlanceOpener`, `BootMarkPlan` (`lockAsk`, `bootMarkStep`);
- `ui/HomeActivity` (`onCreate`, `onStart`/`startWork`, `onResume`, `onStop`, `reconcileKioskMode`, the listeners)
  and `ui/LockActivity`;
- `timerules/TimeRulesRuntime.recheck`, every other `HomeFront.bring` caller (`PlayRuntime`, `AppEnforcer`,
  `WindowRelock`, `SelfUpdate`, `BootHome`), `res/values/styles.xml`, the manifests, `app/build.gradle.kts`
  (`CheckDebugHook`), `src/debug` and `src/release`'s `LockUpDebug*`;
- the tests the step adds or changes.

AOSP 16 (`android16-release`) was read from the copies in the session scratchpad:
- `ActivityTaskManagerService.startLockTaskMode` `:2661-2669`;
- `TaskFragment.resumeTopActivity` `:1463-1472` and `startPausing` `:1871`;
- `ActivityStarter` `clearLastPausedActivity` `:2073/:2458/:3032` and the singleTask clear-top `:2511-2519`.

Line numbers are `df5426fc`'s. Launcher paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`. No
product code was changed: the mutations below were temporary and reverted, and `git status` was clean before this
file was written.

**Verdict: sound for the kid's lock, with a small fix round before the device checks.** Nothing in the step lets
the kid reach Home's content or an app while LOCKED:
- Every Home start at a lock-up is followed, in the same main-thread pass, by the lock's start.
- Home's dark branch is entered only when a lock-up set the mark, and every other Home start ends that mark.
- The `ScreenOn` backstop still starts the lock at every wake where it isn't resumed.
- No 25b/25c fix is undone.

What needs fixing:
- **M1:** the question the brief asks - "can a stale `lockOnTop = true` make a screen-off start nothing while the
  lock is NOT on top?" - has a yes. Something can cover the lock while it is already sleep-stopped, and the
  `ScreenOn` backstop is then suppressed (a system-dialer call). The next screen-off then starts neither Home nor
  the lock, and the app in front is live at the next wake until the backstop's lock draws. That is the very
  frame leak design 32 fixes, through a narrow door. The fix is one line plus a test row.
- **L3:** QA L2's wiring at the call site (no Home over a live call at the unlock) can be undone without a red test.

Counts: 0 High, 1 Medium, 5 Low.

## What I ran (under the shared build lock, `--no-daemon --max-workers=2`)

- **On `df5426fc`, unchanged:**
  - `testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, **1090 tests, 0 failures** (15 in
    `LockUpPlanTest`).
  - `assembleDebug assembleRelease -PwarningsAsErrors=true` with the stub `tsnet.aar` (removed afterwards): BUILD
    SUCCESSFUL. `checkDebugHasDebugHook: hook present`, `checkReleaseHasNoDebugHook: no debug hook`.
  - A dex scan of both APKs:
    - release has `LockUpDebug` (the pass-through) and no `LockUpDebugReceiver` or `skipRestart`;
    - debug has all three.
- **Mutation runs.** Whole suite each time, without `-PwarningsAsErrors` (two mutations leave a parameter unused):

  | Batch | Mutation | Result |
  |---|---|---|
  | A | `leave`: `PinLockRuntime.callLiveNow(this)` -> `false` (`PinLockActivity.kt:662`) | **survives** |
  | A | `callLiveNow` without `\|\| VoipCalls.phase != VoipPhase.NONE` (`PinLockRuntime.kt:1398-1399`) | **survives** |
  | A | `interactiveOrUnknown`: `?: true` -> `?: false` (`:1394`) | **survives** |
  | A | `GlanceOpener`: `\|\| homeBelow` -> `\|\| LockTaskChrome.kioskOn` (`GlanceOpener.kt:146`) | **survives** |
  | A | `HomeActivity.interactive()`: unknown counts as dark (`!= false` -> `== true`) | **survives** |
  | B | `lockUpStarts`: `lockOnTop -> NONE` above `callLive -> LOCK` | red: `LockUpPlanTest` "a live call" |
  | B | the `ScreenOn` start loses `sameLockUp` (`&& false`) | red: `LockUpPlanTest`, `BootMarkPlanTest`, `HomeGateTest` pins |
  | B | Home's dark branch also calls `PinLockRuntime.show(this, ask = LockAsk.BOOT)` | red: `LockUpPlanTest` "Home's resume while dark" |

  Batch A ran as one build and stayed green. Batch B ran as one build: 5 tests red, each the one expected.
- **Source pins checked by reading.** The pins in `LockUpPlanTest`, `HomeFrontTest`, `PinLockManifestTest` and
  `DebugHookAbsentTest` each match the line they guard. Several are exact-string pins: a reformat breaks them, but
  a behaviour change doesn't slip past them.

## Medium

### M1. A cover that arrives while the lock is already sleep-stopped leaves `lockOnTop` true when the `ScreenOn` backstop is suppressed; the next screen-off then starts nothing

**Where:**
- `lock/PinLockRuntime.kt:554-558`: the `ScreenOn` start keeps the flag (`sameLockUp`), and nothing touches the
  flag when there is no start.
- `:1118-1135`: only the lock's own pause and stop clear the flag.
- `lock/LockUpPlan.kt:34-40`: `lockOnTop -> NONE`.
- `lock/PinLockState.kt:167-171`: the `ScreenOn` backstop is skipped for `systemCall` and VoIP.

**What happens.**
- `lockOnTop` is set by a lock-up. After that, only callbacks of the lock itself (an interactive pause or stop, a
  destroy), the UNLOCKED edge or another `show` clear it.
- A lock that is already sleep-stopped gets **no** callback when something else comes over it while dark. The step
  relies on the `ScreenOn` backstop to repair that: at the wake the lock isn't resumed, so `show(sameLockUp)`
  starts it, and it is on top again.
- That repair is skipped exactly when `ScreenOn`'s step doesn't show: `systemCall` or a VoIP phase other than NONE.

VoIP is safe. Every VoIP edge starts the lock through `show`, which clears the flag (`VoipRinging`, `VoipEnded`).
The system dialer's call has no such edge.

**Failure, kiosk off (pause or override), our dialer role not held:**
1. A lock-up while dark: HOME_THEN_LOCK, `lockOnTop = true`.
2. An incoming call: the system InCallUI comes over the sleep-stopped lock and turns the screen on. The lock gets no
   callback. `SCREEN_ON` gives `ScreenOn(systemCall = true)`, which shows nothing, so the flag stays true.
3. During the call the kid taps through to the dialer's own UI ("Add call", the call log). The dialer is one of
   `pinLockHelpers`, so lock task allows it. The call ends and the dialer's main activity stays in front. The lock
   is not on top, and no re-front runs: the lock's last stop was while dark, so `refrontAction` said Stop.
4. Power off: `ScreenOff` with no call, `showLock = true`, `lockOnTop = true` -> **NONE**. Neither Home nor the lock
   is started.
5. Power on: the dialer is the top activity and resumes **live**: the call log, names, numbers. That lasts until
   the `SCREEN_ON` receiver's backstop has started the lock and the lock's first frame lands. Before design 32 the
   lock was started at step 4.

A second, racy trigger: an activity comes over a *resumed* lock in the few milliseconds after `isInteractive` has
flipped at a screen-off (Recents, the emergency dialer, an app's FSI). Its pause is taken for the sleep's own
(`interactiveOrUnknown()` is false), so the flag survives.

The lock still comes at the wake, so the kid gains no access. But it is an app frame at screen-on, the bug this
design exists to fix, and the path is reachable: the role prompt in `HomeActivity.promptForCallRoleIfNeeded`
exists because the dialer role can be missing.

**Fix:**
- A `ScreenOn` event that finds the lock not resumed and starts nothing means nobody knows what is on top. Clear the
  flag there, in `dispatch`'s `ScreenOn` branch: `if (!event.lockShowing && !result.showLock) lockOnTop = false`.
  Or make it pure: a `LockTopEvent.SCREEN_ON_UNSHOWN` row in `lockOnTopAfter` (-> false).
- A pure test row, and a pin that `dispatch` applies it.
- §11 check: with the role not held, take a system-dialer call from dark and leave it in the dialer's UI. Then power
  off and on: the log says `HOME_THEN_LOCK`, not `NONE`.
- The racy trigger is best effort by the spec's own words (§8 4). Name it in §14 as a residual; the backstop still
  bounds it.

## Low

### L1. Home's dark-branch `reconcileKioskMode()` can never root lock task, so the case it was added for still brings Home over the lock, animated, while dark

**Where:**
- `ui/HomeActivity.kt:441-449`: the dark branch and its comment ("kiosk on, lock task rooted here").
- `:560-584`: `reconcileKioskMode`.
- `lock/PinLockActivity.kt:407-411`: `START_HOME`.

**What happens.**
- `lockUp` starts Home and then the lock in one main-thread pass (`PinLockRuntime.kt:622-628`). Home's resume
  transaction runs only after that pass. By then the lock's task is the focused root task.
- So `startLockTask()` from Home throws `IllegalArgumentException("Invalid task, not in foreground")` (ATMS
  `startLockTaskMode` `:2667-2669`). The throw is caught (`HomeActivity.kt:572-583`).
- With lock task already running, or with the kiosk off, the call is a no-op. So the dark branch's reconcile is
  either a binder read or a caught throw.
- The case it was meant for is the kiosk on with lock task *not* running. There, the lock's own resume (dark, or at
  the wake) still takes `START_HOME`. That start is an animated `HomeFront.bring` and ends the dark mark. Home comes
  over the lock, an open transition is queued while dark (25b-B's pattern), Home runs its full locked resume, and
  the lock is started a second time.

This was the behaviour before design 32 too, so it is not a regression. But §8 item 3's reason and the code comment
don't hold. The trigger is rare: the kiosk is on, yet nothing has rooted lock task. Examples are just after the
kiosk is switched on, or after a `rootLeave`.

**Fix:**
- Correct the comment and §8 3/§14: Home can't root lock task from the dark branch; the lock's `START_HOME` does.
- Optionally give that `START_HOME` no animation while dark. That needs "ends the dark mark" separated from
  "animate" in `HomeFront.bring`, for example `bring(context, why, animate = interactive, underLock = false)` with
  `endDarkLockUp()` unless `underLock`. A plain `animate = false` there would skip `endDarkLockUp` and leave Home
  over the lock with the dark branch, so the two must not be the same parameter.
- A §11 check: kiosk on, `adb shell am task lock stop` (or the 16d override), a lock-up while dark. Expect at most
  Home's black stand-in at the wake.

### L2. An animated `HomeFront.bring` from a background thread can interleave with `lockUp` and land Home over the lock with the dark mark set

**Where:**
- `lock/HomeFront.kt:40-46`: `endDarkLockUp()`, then `startActivity`, on the caller's thread.
- Callers off the main thread: `PlayRuntime.onInstallModeEnded` (`:225-235`, from an apply), `AppEnforcer.kt:739`
  (a role change, from an apply) and `WindowRelock.kt:655` (its worker).

**Failure.**
1. The background thread runs `endDarkLockUp()`.
2. On the main thread, a screen-off's `lockUp` sets `darkLockUp = true` and starts Home and then the lock.
3. The background thread's `startActivity(Home)` then lands, and Home is over the lock.
4. Home's resume sees `darkLockUp && !interactive && LOCKED` and takes the dark branch. No lock start comes after
   it.

Home's night ground then sits over the lock until the wake. There the backstop and Home's interactive resume both
start the lock. That is ours, not an app, and nothing is touchable (16c), but it is "not Home either" broken. The
window is microseconds wide at a screen-off.

**Fix:** order the two against `lockUp`. Either run an animated bring's `endDarkLockUp()` + `startActivity` on the
main thread (`onMain`; device owner + HOME may start from any thread, so posting is harmless), or take one monitor
around `lockUp`'s mark-and-starts and `bring`'s end-and-start.

### L3. QA L2's call-site wiring and the glance addition are unpinned

**Where:**
- `lock/PinLockActivity.kt:662`: `PinLockRuntime.callLiveNow(this)`.
- `lock/PinLockRuntime.kt:1398-1399`: `callLiveNow`'s three terms.
- `lock/GlanceOpener.kt:146` and `PinLockActivity.kt:680`: `homeBelow = plan.homeFirst`.

**What happens.** Batch A above shows the gaps:
- Replacing `callLiveNow(this)` with `false` survives the whole suite. Then, with the kiosk off, an unlock during a
  live VoIP call starts Home over it, and Element hangs up when its PiP goes (`LockTaskRoot.kt:34-41`).
- Dropping the VoIP term also survives. VoIP is the very case L2 was raised for.
- `LockTaskRootTest` pins only the pure `lockLeave`.
- `LockGlanceGuardTest` pins the `homeBelow = plan.homeFirst` argument, but `watchSend` may ignore it.
- The two fail-safe defaults (`interactiveOrUnknown ?: true`, Home's `interactive()` unknown = on) are unpinned too.

**Fix:** source pins.
- `leave` passes `PinLockRuntime.callLiveNow(this)` as `lockLeave`'s fourth argument.
- `callLiveNow` is `ourCall() || telecomInCall(...) || VoipCalls.phase != VoipPhase.NONE`.
- `watchSend`'s `confirmable` uses `homeBelow`.
- `interactiveOrUnknown` defaults to `true`.

### L4. `show` reports a failed start as started, so `lockUp` sets `lockOnTop` for a lock that never came

**Where:** `lock/PinLockRuntime.kt:1081-1084` (the catch, then `return true`) and `:628`.

**What happens.** If `startActivity(PinLockActivity)` throws:
- `lockUp` still sets `lockOnTop = true`;
- with HOME_THEN_LOCK, Home (with the dark mark) is the top.

The `ScreenOn` backstop repairs both at the wake: the lock isn't resumed, so it is started. Before design 32 a
failed start was retried at the next screen-off. Now that start is skipped as NONE.

**Fix:** `lockUp` sets the flag only for a start that didn't throw. One way is an internal
`showResult(): Started|Held|Failed` that `show` wraps, keeping `show`'s Boolean for its other callers.

### L5. Docs: what the step does that §14 doesn't say

1. **The time-rule screen is finished at every dark lock-up.**
   - `HomeActivity` is `singleTask` (`AndroidManifest.xml:115`). `LockActivity` has the default affinity, so it
     lives in Home's task above `HomeActivity`.
   - The lock-up's Home start therefore clears it (ActivityStarter `:2511-2519`, clear-top for singleTask).
   - That is *why* §11's "Home's task top is `HomeActivity`, also during a time rule" holds, and it is good for the
     stand-in.
   - The screen returns at the unlock (`afterUnlock` -> `LockActivity.start`) or at Home's next interactive resume
     (`redirectToLockScreenIfLocked`). I found no path where a rule is left without its screen: an unlock during
     our call skips `afterUnlock`'s start, and Home's resume after the call starts it.
   - Say this in §14 and in the launcher `CLAUDE.md` bullet.
2. **`HomeActivity.kt:441-444`'s comment.** See L1.
3. **Two visible changes the device run should expect, not report as regressions:**
   - Home's black app-theme snapshot is also Home's starting window whenever Home comes to front interactively:
     every unlock, with the kiosk on and now off. That is a black frame where Home's old screenshot was.
   - On Android 13 (the Jelly Star), the theme's black `statusBarColor`/`navigationBarColor` paint black strips
     above and below the drawn lock (decision 3).
   - With `targetSdk 36` on Android 15+ the window's bar colours are ignored (edge-to-edge), so the emulator won't
     show the strips.
   - Add both to §11 step 1.
4. **A quick press.** When the screen is back on before Home's resume transaction runs, Home takes its normal locked
   resume over a lock that is already started: the update fence, possibly `LockActivity` (then `TimeRuleShown`
   re-fronts the lock), and `show(BOOT)`, which clears `lockOnTop`. All of that is ours and lasts a few
   milliseconds. Name it as a known residual next to §12's.

## The implementer's interpretations and additions

I didn't have the implementer's numbered list. I judged the interpretations and additions §14 names, in its order,
and "Home recreated at the unlock" as the brief asked.

1. **`callLive` from the `ScreenOff` event's own reads** (`ourCall || systemCall || voip != NONE`): right.
   - These are the reads `step` itself uses, and no binder call is added before the starts except `isInteractive`.
   - `systemCall` is `!ourCall && telecomInCall`, so the sum is "ours or Telecom's or VoIP", as §8 1 asks.
2. **Every `ScreenOn` start counts as "the same lock-up"** (`sameLockUp = event is LockEvent.ScreenOn`): right for
   the start itself. A `ScreenOn` start keeps true only when the flag was already true, and the started lock is on
   top. The hole is the `ScreenOn` that starts nothing (M1).
3. **No context counts as interactive** (clears the flag): right, the fail-safe direction. Unpinned (L3).
4. **`endDarkLockUp()` at every animated `HomeFront.bring`:** right and necessary.
   - Without it, the lock's dark-resume `START_HOME` (kiosk on, no lock task, L1) would land Home over the lock in
     the dark branch, and nothing would start the lock until the wake.
   - It is pinned (`HomeFrontTest`: before the start, and `lockUp` is the only unanimated caller).
   - Its thread ordering is L2.
5. **`onStart`'s work moved to `startWork()`, idempotent through `started`, also called from `onResume`:** right. I
   checked both orders:
   - a dark `onStart` and an interactive `onResume`: the work runs in `onResume`;
   - an interactive `onStart` and a dark `onResume`: the work ran in `onStart`, and `onStop` undoes it.

   `onStop`'s removals of never-added listeners are no-ops, including `unregisterContentObserver` of an unregistered
   observer. The shared-preferences listener (rule boundaries, kiosk switch) stays registered from an earlier start
   until `onDestroy`, as before. A Home *created* by a dark lock-up misses it until its first interactive start;
   `TimeRulesRuntime.recheck` starts the rule screen itself, so nothing is lost.
6. **`GlanceOpener.open(..., homeBelow)`:** right. With the kiosk off the lock now leaves through Home, so a refused
   send is caught as with the kiosk on.
   - Unpinned (L3).
   - A `HomeFront.bring` refused at shutdown still counts as `homeBelow`. That is negligible.
7. **Home recreated at the unlock:** fine.
   - A Home that doesn't exist yet when the unlock brings it (with the kiosk off, the first unlock in a process) is
     created with the mode UNLOCKED: `gate()` shows the content, and `onStart` runs `startWork`. Its 16e launch
     splash then shows at that unlock, a visible change that comes with decision 2.
   - A recreate while dark (a configuration change) runs `onCreate` (night ground, black task description) and a
     dark `onStart` that skips, then stops again.
   - The modeListener's `if (started) renderCallCard()` at the UNLOCKED edge does nothing while Home is stopped.
     `startWork` renders the card at the next start.
8. **`LockUpDebugReceiver` as the banned string** (not `LockUpDebug`): right.
   - The release twin carries `LockUpDebug`, like `LockTaskDebug`.
   - Its body is pinned: one `fun`, `= start`, no `var`, one caller.
   - `CheckDebugHook` and `DebugHookAbsentTest` name the receiver, and the dex scan above agrees.
   - The receiver is exported and guarded by DUMP. It is memory only, with default `true` (the release plan), and it
     can only turn NONE into LOCK, never add a Home start.

## Verified OK

- **Home never ends up over the lock by design.** `lockUp` starts Home and then the lock, both synchronously on the
  main thread (`:622-628`). WM processes them in that order.
  - The lock's ask is `AT_ONCE` (`lockAsk(ScreenOff)`), and `bootMarkStep` never holds `AT_ONCE` (`:152`).
  - The only exceptions are L2's race and L4's failed start.
- **Home's dark branch never leaves the phone without the lock.** It is taken only when `darkLockUp` is set, the
  screen is off and the mode is LOCKED.
  - `darkLockUp` is set only in `lockUp(HOME_THEN_LOCK)`, right before that lock-up's lock start. It is ended by an
    interactive resume of the lock or Home and by every other Home start.
  - Every Home start in the code goes through `HomeFront` (grep; `HomeFrontTest`).
  - A lock that finishes while LOCKED and dark (the crash guard) switches the mode to DISABLED first.
  - A killed process starts with both flags false. WM's records survive, and the `ScreenOn` backstop starts the lock
    at the wake.
  - A late `SCREEN_OFF` (screen on again) plans LOCK, not Home, and doesn't set the mark.
- **Home does resume while dark.** `ActivityStarter` clears the target's `mLastPausedActivity` on a start, so the
  "going to sleep and all paused" early return (`TaskFragment:1465`) doesn't hold it. The dark branch is real code,
  not dead code.
- **Calls:**
  - No Home start at a lock-up with a live call: ours, Telecom's, or VoIP RINGING/IN_CALL. IN_CALL and RINGING while
    LOCKED start nothing at all, as before.
  - Kiosk off, the unlock brings no Home over a live call (`homeFirst = !callLive`).
  - Kiosk on, the leave still goes through Home during a call. That is unchanged design 16, which L2 left alone on
    purpose.
  - `InCallActivity` and `VoipWakeActivity` keep `showWhenLocked` (and `turnScreenOn`), and the incoming-call and
    VoIP ring paths are untouched.
  - The `WindowRelock` and `afterUnlock` call guards are unchanged.
- **`lockOnTop`, every way:**
  - Set: `lockUp`, LOCK or HOME_THEN_LOCK, when `show` returns true.
  - Kept: a non-interactive pause or stop; a `ScreenOn` start.
  - Cleared:
    - every other `show`: re-front, `TimeRuleShown`, `CallsEnded`, `VoipRinging`/`VoipEnded`, `RemoteLock`,
      `ProcessStart`, Home's locked resume, `endBootMark`, `showIfLocked`;
    - an interactive pause or stop;
    - `onDestroy`, including an old instance's late destroy, which only clears;
    - the edge to UNLOCKED or DISABLED;
    - a new process.

  The only stale-true paths are M1 and L4.
- **Lock task and kiosk:**
  - The launch order is Home, the lock, and then (LOCKED edge, mark over) the chrome. Before, it was the lock and
    then the chrome. Home is our package, so it is permitted in both lock-task configurations.
  - Kiosk off, Home's dark reconcile never stops the lock's own lock task (`contentAllowed()` is false while LOCKED).
  - `ensureLockTask` and the 1 s fallback are unchanged.
  - The 16b boot cover: `HomeFront.bring` refuses during shutdown, and the lock is still started.
  - 16e: a screen-off ends the boot wait (`AT_ONCE`) before Home's dark resume.
  - 16d: the debug override is untouched.
- **No 25b/25c fix undone:**
  - the lock's `NO_ANIMATION`, `overrideActivityTransition` and `windowAnimationStyle @null`;
  - the 400 ms `screenOffWork` deferral, still posted after the lock-up;
  - the screen-off heal skip (`screenOffHealed`), which works the same for the NONE path because the lock resumes
    only at the wake;
  - the 200 ms pause and stop re-front;
  - `noteProbableSos` (interactive only), the SOS narrowing;
  - the camera policy (`CameraLock` on the edge, `callForCamera`).

  Home's dark resume and pause add only `showNight`, a lock-task read and Home's `onPause` before the lock's
  `onCreate`, as §8 3 allows.
- **The black stand-ins:**
  - `PinLockTheme`'s four items are black.
  - Both `setTaskDescription` calls use opaque black. `copyFromPreserveHiddenFields` copies non-zero colours, so
    they stick.
  - The lock's call comes after `FLAG_SECURE` and before `setContentView`.
  - Home has `setRecentsScreenshotEnabled(false)`.
  - Nothing in the launcher writes a window bar colour at run time, which would rewrite a task description's bar
    colour through `updateStatusBarColor`.
  - The lock's layout roots keep `kid_ground` and the 25f ground.
  - `PinLockTheme` is used by the lock only.
- **The debug receiver:** DUMP-guarded and exported, only in `src/debug`. It is named in `CheckDebugHook` (both
  variants green) and in `DebugHookAbsentTest`. The release stub is a pass-through.
- **Comments:** no "drawn before the next screen-on" is left in `lock/` (pinned). Design 10 §3 and the launcher
  `CLAUDE.md` point to design 32.
