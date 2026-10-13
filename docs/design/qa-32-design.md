# QA 32 design review - the lock flash at screen-on: Home under the lock, the keyguard cover (2026-10-13)

Scope: `docs/design/32-lock-flash.md` at `d9738e15` (branch `lock-flash-investigation`), read against the root and
launcher `CLAUDE.md`, design 25's fix rounds 25b/25c/25f (`docs/design/25-lock-glance.md:993-1137`) and
`qa-25b-25f-code.md`, and checked against the code it changes: `lock/PinLockRuntime.kt` (`init`, `dispatch`,
`screenReceiver`, `screenOffWork`, `configure`, `show`, `onLockResumed/Paused/Stopped`), `lock/PinLockState.kt`,
`lock/PinLockActivity.kt` (`onCreate`, `onResume`, `ensureLockTask`, `leave`/`go`), `lock/LockTaskRoot.kt`,
`lock/HomeFront.kt`, `lock/BootCover.kt`, `ui/HomeActivity.kt`, `ui/LockActivity.kt`, `timerules/TimeRulesRuntime.kt`,
`server/AppEnforcer.kt` / `OwnPermissions.kt` (keyguard features), `server/LockTaskHelpers.kt`,
`server/ScreenTimeout.kt`, `res/values/styles.xml`, the manifests, `app/build.gradle.kts` (`CheckDebugHook`) and the
tests `LockUpTest`, `HomeFrontTest`, `LockTaskRootTest`, `BootCoverManifestTest`, `DebugHookAbsentTest`. Launcher
paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`; line numbers are `d9738e15`'s.

AOSP was read from `android16-release` on android.googlesource.com on 2026-10-13: `frameworks/base/services/core/.../`
`wm/` (`KeyguardController`, `ActivityRecord`, `TaskFragment`, `WindowState`, `WindowManagerService`,
`DisplayPolicy`), `policy/PhoneWindowManager`, `policy/keyguard/KeyguardServiceDelegate`, `power/Notifier`,
`locksettings/LockSettingsService`, `devicepolicy/DevicePolicyManagerService` (DPMS), `wm/LockTaskController`, and
SystemUI's `keyguard/KeyguardViewMediator` (KVM), `keyguard/KeyguardService` and
`statusbar/NotificationLockscreenUserManagerImpl` (NLUM), plus `core/.../app/KeyguardManager`. QA wrote no product
code.

**Verdict: step 1 is buildable after a fix round; step 2 is not buildable as specified.** The investigation holds up.
Every framework claim the root cause rests on checks out against the 16 source:
- `ACTION_SCREEN_OFF` is sent from the late interactive change (Notifier `:676-732`).
- On a sleeping display, only `canTurnScreenOn()` activities are visible (`ActivityRecord.shouldBeVisibleUnchecked`
  `:5893-5899`, `canTurnScreenOn` `:9095-9101`), and `TaskFragment.sleepIfPossible` `:1089-1121` pauses everything else.
- `activityStopped` destroys the surfaces (`:6426`).
- `requestDrawIfNeeded` skips windows without a surface and activities with a starting window (`:1958-1993`).
- A FLAG_SECURE or screenshot-disabled activity gets an app-theme snapshot (`:5615`).
- Occlusion is decided by the top focusable task's top activity, `canShowWhenLocked()`, independent of sleep
  (`KeyguardController.updateVisibility` `:711-791`, `getRootTaskForControllingOccluding` `:800-803`,
  `TaskFragment.isFocusableAndVisible` `:1804` has no sleep term).
- While the keyguard is locked, only showWhenLocked activities pass `checkKeyguardVisibility` (`:405-419`).
- `dismissKeyguard` needs only `visibleIgnoringKeyguard` (`:332-347`).
- `setKeyguardDisabled(false)` only writes the setting, and `true` also dismisses (DPMS `:15444-15453`).
- A device with no credential still unlocks CE at boot (`LockSettingsService.unlockUserKeyIfUnsecured` `:2250`).
- `LOCK_TASK_FEATURE_KEYGUARD` is forced (`LockTaskHelpers.kt:101`).
- `setSecureSetting` can't reach any keyguard setting (DPMS `:728-735`).

The open question in §7.4 can be closed for AOSP SystemUI: `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` hides lock-screen
notifications whether or not the keyguard is secure (NLUM `updateDpcSettings` `:592-600`,
`updateLockscreenNotificationSetting` `:570-586`). The Jelly Star's SystemUI must still be checked.

Where it breaks:
- **H1.** Step 2 does not meet the strict requirement in the user's own case. After a screen *timeout*, SystemUI
  shows even a Swipe keyguard only after `lock_screen_lock_after_timeout` (5 s by default). A wake inside those 5 s
  has no keyguard, so it flashes exactly as today.
- **H2.** As written, a cover that can't be set switches handy's PIN lock **off**.
- **Step 1 (M1-M3).** Home's own resume at the dark lock-up re-starts the lock and runs main-thread work ahead of
  the lock's creation, which undoes 25b-B's fix (1) and defeats item 2. Home's start animates, which undoes 25b-B's
  no-transition fix. And "pure black" covers only one of the stand-in's colour sources.
- **Step 2 (M4-M7).** Boot shows SystemUI's lockscreen. VoIP calls get SystemUI's lockscreen and a failing dismissal.
  The cover's keyguard features are never restored. The failure counter can't see the failures that matter.

Counts: 2 High, 7 Medium, 9 Low.

## High

1. **H1: the keyguard does not come on for 5 s after a screen *timeout*, so step 2 leaves the user's own case
   unprotected.**
   - **Evidence.**
     - KVM `onStartedGoingToSleep` `:1812-1846`: `lockImmediately = getPowerButtonInstantlyLocks || !isSecure` is
       true for Swipe, but it only applies to `OFF_BECAUSE_OF_USER`. For `OFF_BECAUSE_OF_TIMEOUT && timeout > 0` the
       branch is `doKeyguardLaterLocked(timeout)`, an alarm, and `mLockLater = true`.
     - `getLockTimeout` `:1973-2005`: with no `getMaximumTimeToLock` (handy sets none - `ScreenTimeout.kt:60`), the
       timeout is `LOCK_SCREEN_LOCK_AFTER_TIMEOUT`, default `KEYGUARD_LOCK_AFTER_DELAY_DEFAULT = 5000` (`:320`).
     - A device owner can't write that Secure setting (DPMS `:728-735`).
   - **Failure.**
     - The run in §1 is a timeout: "the lock was in front and dimmed by the screen timeout".
     - With the cover on, a kid who touches the power key within 5 s of the timeout gets no keyguard at all. No
       visibility rule applies, and the task under the lock flashes exactly as in §1.
     - Most screen-offs on a kid's phone are timeouts, and a wake right after the dim is the typical case.
     - §7.4's "the next sleep shows it again by itself" and §11's quick-press check (power off and on) cover only the
       power key, so the step 2 checks would pass while the user's case fails.
   - **Fix (spec, before step 2 is built).** Make the lock delay 0 under the cover:
     - **Preferred: `setMaximumTimeToLock(admin, T)`**, with T equal to the effective `SCREEN_OFF_TIMEOUT`.
       - `getLockTimeout` then returns `min(T - displayTimeout, lockAfter) = 0`, and the timeout path falls through
         to `setPendingLock(true)`.
       - T equal to the setting doesn't shorten the screen timeout (PowerManager takes the minimum). It is kept in
         step by `ScreenTimeout` in every mode, including `Release` and its observer.
       - It needs `<force-lock/>`, which is already declared.
       - It is cleared when the cover is off.
     - **Alternative: `dpm.lockNow()` from the `SCREEN_OFF` receiver while `!isInteractive`.**
       - Its `goToSleep` (DPMS `:6402`) turns a screen that was *just* woken back off, which is a race the kid would
         see.
       - It also sets `STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW` (`:6390`).
       - So it is second choice.
   - **Device checks (§11).**
     - "Screen timeout, then power within 1-4 s, 20 times: no failing frame".
     - Right after a timeout screen-off, `dumpsys window` must show the keyguard showing and occluded.
     - Note the Jelly Star's own lock-after-timeout behaviour.

2. **H2: "`KEYGUARD_NOT_DISABLED` becomes 'the cover couldn't be set'" turns the PIN lock off.**
   - **Evidence.** §8 step 2 item 2. `lockActivation` (`lock/PinLockState.kt:259-276`) returns
     `false to KEYGUARD_NOT_DISABLED` when that input is false, and `configure` (`PinLockRuntime.kt:917-956`) stores it
     as `active = false`.
   - **Failure.**
     - COVER is two calls, and the second one can throw: `setKeyguardDisabledFeatures` is refused without the
       `disable-keyguard-features` policy granted, which `AppEnforcer.kt:823-825` and `AdminPolicies` record as a real
       state after an update.
     - Then the lock is inactive until the next good apply. That is a security regression bought by an experiment
       whose only purpose is cosmetic.
     - The other order is worse: `setKeyguardDisabled(false)` succeeds and the features fail. That leaves a Swipe
       keyguard with its notifications, shortcuts and camera enabled behind the lock.
   - **Fix.**
     - `keyguardMode`'s COVER is attempted in this order:
       1. Set the features.
       2. Read them back. `getKeyguardDisabledFeatures(admin)` must equal `KEYGUARD_DISABLE_FEATURES_ALL`.
       3. Only then call `setKeyguardDisabled(false)`.
     - Any failure falls back to `setKeyguardDisabled(true)`, today's path. `lockActivation` then sees today's
       `keyguardDisabled = true`.
     - The lock is never inactive because of the cover. `KEYGUARD_NOT_DISABLED` keeps its meaning (a credential or
       an exception on the `true` call).
     - Report the failed cover separately: `keyguard_cover = "failed"` or logcat.
     - Pure test: a features failure gives DISABLED and an active lock.

## Medium

1. **M1: Home's resume at the dark lock-up re-starts the lock (and the time-rule screen), so item 2 almost never
   applies, and its main-thread work runs before the new lock is created (undoes 25b-B fix 1).**
   - **Evidence.**
     - `HomeActivity.onResume` while LOCKED (`ui/HomeActivity.kt:418-435`) runs, in order:
       - `reconcileKioskMode()`, which is binder calls and maybe `startLockTask`;
       - `UpdateFence.onFront`;
       - `redirectToLockScreenIfLocked()`, which during a time rule starts `LockActivity`, and that sends
         `TimeRuleShown`, another lock start (`LockActivity.kt:195-200`);
       - `PinLockRuntime.show(this, ask = LockAsk.BOOT)`, another lock start.
     - Before that, `onStart` registers six listeners and a content observer and renders the call and music cards
       (`:376-402`).
     - A Home brought to the front while dark is resumed and then paused "sleep", exactly like the lock in §1's log
       (`wm_resume_activity` ... `wm_pause_activity sleep`).
     - Its transactions are queued before the lock's, because Home is started first.
     - The `ScreenOn` backstop (`PinLockRuntime.kt:682`, `showLock` when `!lockResumed`) races the lock's resume at
       the wake. `SCREEN_ON` is sent at the start of the wake, before `screenTurningOn` releases the sleep token.
   - **Failure.**
     1. Item 2 clears `lockOnTop` on "any other start of the lock". So Home's own resume clears it during the same
        lock-up, and the next screen-off is HOME_THEN_LOCK again.
        - That is *more* starts while dark than today (Home, the lock, the lock again, and with a rule
          `LockActivity` and the lock), not fewer.
        - §5's H2 experiment ("step 1 item 2 alone") can't be run as described.
     2. 25b-B fix (1) moved all screen-off work 400 ms later, so that nothing keeps the main thread before the new
        lock's `onCreate`. The lock is a new instance at every lock-up after an unlock, because `go()` calls
        `finishAndRemoveTask`. Home's `onRestart/onStart/onResume` now run first, and they include binder calls.
     3. During a time rule, `LockActivity` is started over the lock while dark at every screen-off. With its default
        affinity it lands in Home's task, on top of `HomeActivity`. So Home's task snapshot at the wake is the
        time-rule screen, not Home's black stand-in.
   - **Fix.**
     - A lock-up while dark marks itself: a runtime flag set by HOME_THEN_LOCK and cleared at the next interactive
       resume.
     - `HomeActivity.onResume` with that mark and `!interactive` only shows the night ground and runs
       `reconcileKioskMode`. It does not run `redirectToLockScreenIfLocked`, `show` or the boot-mark calls.
       - Kiosk on, Home must still root lock task. Otherwise the lock's `ensureLockTask` starts Home again at the
         wake, *visibly* (START_HOME, `PinLockActivity.kt:395-399`).
     - Keep `onStart`'s work off the dark path where possible.
     - `lockOnTop` is not cleared by lock starts of the same lock-up.
     - Either define the `ScreenOn` backstop's start as not clearing it when the lock resumes without an interactive
       pause before it, or say plainly that item 2 is best effort and give H2 its own debug toggle.
     - Pin Home's dark-resume branch in a test.

2. **M2: the lock-up's Home start animates, and a transition queued while dark plays at the next screen-on (25b-B).**
   - **Evidence.**
     - `HomeFront.intent` (`lock/HomeFront.kt:22-26`) adds only `FLAG_ACTIVITY_NEW_TASK`.
     - `launcherHomeTheme` inherits `windowAnimationStyle = @style/WindowFadeTransition` from `launcherBaseTheme`
       (`res/values/styles.xml:37`).
     - 25b-B's root cause (`25-lock-glance.md:1003`): "started at the screen-off ... its open transition ran at the
       screen-on from Home's last frame". The fix was `NO_ANIMATION`, `overrideActivityTransition` and
       `windowAnimationStyle @null`, all on the lock only.
   - **Failure.** Step 1 adds an animated Home task-to-front while dark, right before the lock's unanimated one. If the
     Shell transitions are collected or played at the wake, as in 25b-B, Home (night ground or its snapshot) shows or
     fades during the wake. That is precisely the "not Home either" the user forbade.
   - **Fix.**
     - The lock-up's Home start gets `FLAG_ACTIVITY_NO_ANIMATION`: `HomeFront.bring(context, why, animate = false)`,
       still typed HOME. `HomeFrontTest` allows the flag, which it already does by not banning it, and a new pin
       requires it on the lock-up path.
     - Device check: no `TRANSIT_OPEN`/`TO_FRONT` with an animation for Home's task in the Shell transition trace at
       the wake.

3. **M3: "the lock's stand-in is pure black" changes one of four colour sources.**
   - **Evidence.**
     - The spec changes `PinLockTheme`'s `windowBackground` (§8 1.4) and pins only that (§9).
     - On API 31+ an activity's starting window is a *splash screen*, coloured by `windowSplashScreenBackground`.
       `PinLockTheme` inherits `kid_ground` from `launcherBaseTheme` (`styles.xml:35`).
     - A lock started at a lock-up is a new instance every time after an unlock (`finishAndRemoveTask`,
       `PinLockActivity.kt:669`).
     - The app-theme snapshot is drawn from the TaskDescription's background *and* its status and navigation bar
       colours. `PinLockTheme` sets both bars to `kid_ground` (`styles.xml:59-60`).
     - The default TaskDescription background is the theme's `android:colorBackground`, `#252827` (`colors.xml:3`),
       not `windowBackground`.
   - **Failure.** The pins pass while decision 3 is unmet: the splash and the bars stay `kid_ground`. Home's black
     TaskDescription likewise governs only while `HomeActivity` is its task's top (see M1.3).
   - **Fix.**
     - Black for `windowSplashScreenBackground`, `windowBackground`, `statusBarColor` and `navigationBarColor` in
       `PinLockTheme`.
     - An explicit `TaskDescription.Builder().setBackgroundColor(BLACK).setStatusBarColor(BLACK).setNavigationBarColor(BLACK)`
       in the lock's `onCreate`, before `setContentView`.
     - Home: the same builder.
     - Pin all four theme items and both `setTaskDescription` calls.
     - The painted ground (25f) is unaffected.

4. **M4 (step 2): at boot the Swipe keyguard shows from the end of the boot animation, over the 16b boot cover and
   Home, and Home's dismissal can't be relied on.**
   - **Evidence.**
     - With the lockscreen enabled, SystemUI shows the keyguard at boot (`doKeyguardLocked` `:2433-2535`).
     - Neither `BootCoverActivity` (`AndroidManifest.xml:169-183`, own process, framework theme) nor `HomeActivity`
       is showWhenLocked. Both stay invisible under it (`checkKeyguardVisibility` `:410-413`).
     - An un-occluded keyguard holds a sleep token (`updateKeyguardSleepToken` `:566-573`), so Home gets at most a
       resume-then-sleep-pause.
     - A `requestDismissKeyguard` that arrives before SystemUI has `mShowing` is answered `onDismissError` (KVM
       `handleDismiss` `:2570-2573`) and is never retried.
     - Home covered ends the boot wait (`endBootMark`): the 16e mark never shows, and the lock comes up over SystemUI's
       lockscreen.
   - **Failure.**
     - §11's boot check ("No SystemUI lockscreen frame") will fail. SystemUI's lockscreen is up from the end of the
       boot animation until our first activity runs.
     - The boot-time dismissal counts a spurious failure toward the fallback.
     - 16b's and 16e's boot design is silently replaced.
   - **Fix.**
     - Keep the keyguard off across a clean reboot: `setKeyguardDisabled(true)` in `BootCover`'s `ACTION_SHUTDOWN`
       receiver (`lock/BootCover.kt:89-96`).
     - Re-arm the cover only once the boot's lock is up and the screen is on (`setKeyguardDisabled(false)` only
       writes the setting, so it takes effect at the next sleep).
     - A crash or power-loss reboot keeps the cover; accept one Swipe screen there and say so.
     - Home's boot dismissal stays as that backstop. It is not counted as a failure while `!isKeyguardLocked`.

5. **M5 (step 2): VoIP calls end up under SystemUI's lockscreen.**
   - **Evidence.**
     - A screen-off in `VoipPhase.IN_CALL` starts no lock (`PinLockState.kt:161`), and `ScreenOn` shows none while
       the phase isn't NONE (`:170`).
     - So the top at the sleep is the VoIP app's call activity, typically not showWhenLocked. The keyguard is shown
       un-occluded (`updateVisibility` `:738-749`), and at the wake SystemUI's lockscreen covers the call. No resumed
       lock exists to dismiss it.
     - The other path: the lock resumes in a call, `LockResumed` gives `showVoipCall`, and `VoipCalls.reopenCall`
       sends the call's content intent from `onResume`. That is *before* the first frame and the dismissal.
       - The VoIP activity becomes the top while the keyguard is locked, so the keyguard un-occludes and SystemUI's
         lockscreen shows.
       - The lock's own dismissal then fails, because it is no longer `visibleIgnoringKeyguard`
         (`dismissKeyguard` `:334`).
   - **Failure.** A failing frame in the "VoIP call" mode §11 lists, an extra swipe in the middle of a call, and a
     dismissal failure counted per wake. Two of those trip the fallback, which turns the cover off for the next sleep.
   - **Fix.**
     - While the VoIP phase isn't NONE, `keyguardMode` is DISABLED: switch it on the phase edge, while awake.
     - `showVoipCall` and `showCall` from the lock's resume wait until the keyguard is unlocked (dismissal success or
       `!isKeyguardLocked`).
     - Add a pin and a §11 check: a VoIP call, then power off/on, gives the call screen, no SystemUI lockscreen.

6. **M6 (step 2): switching the cover off never restores the keyguard features. Two writers fight over them.**
   - **Evidence.**
     - `AppEnforcer.applyKeyguardFeatures` (`server/AppEnforcer.kt:817-827`) writes
       `keyguardDisabledFeatures(current, managed)`, which is `current or SECURE_CAMERA` and "never clears other bits"
       (`server/OwnPermissions.kt:68-75`).
     - The spec's switch-off says "the features as AppEnforcer computes them" (§8 2.2).
   - **Failure.**
     - After one COVER the features stay `KEYGUARD_DISABLE_FEATURES_ALL` for good, unmanaged included (only bit 2 is
       cleared).
     - Harmless while the keyguard is disabled. But if the parent ever sets an Android credential (lock inactive
       `android_credential`), the real keyguard has no notifications, no fingerprint or face, no trust agents and no
       shortcuts, and nobody knows why.
     - Meanwhile `configure` and `applyKeyguardFeatures` both write the same policy from the same apply, in an
       order the spec doesn't fix.
   - **Fix.**
     - One writer: `keyguardDisabledFeatures(current, managed, cover)` returns ALL under the cover and otherwise
       `current` minus the cover's bits plus SECURE_CAMERA.
     - Remember what the cover added in prefs (a device-owner-only write), so the bits a parent or another tool set
       survive.
     - Pure tests for on, off and unmanaged.

7. **M7 (step 2): the failure counter can't see the failures that matter, and the fallback's re-arm is too late.**
   - **Evidence.**
     1. KVM `dismiss` `:2576-2583` returns **without any callback** while the keyguard is going away.
     2. `KeyguardManager.requestDismissKeyguard`'s callbacks are dropped when the activity is destroyed
        (`KeyguardManager.java:913-928`).
     3. The keyguard can come up while the lock stays resumed: a SystemUI restart (25b's camera crash) shows it again
        from `onSystemReady`, and the delayed-keyguard alarm of H1 fires 5 s after a timeout. No new resume means no
        dismissal.
     4. `setKeyguardDisabled(false)` only writes the setting (DPMS `:15448`), and SystemUI decides whether to lock in
        `onStartedGoingToSleep` (`:1840`). So re-arming in `screenOffWork`, 400 ms after `SCREEN_OFF`, comes too late
        for that sleep.
   - **Failure.**
     - The counter counts only callbacks, so (1)-(3) never increment it, and the fallback never runs in the failure
       modes the experiment exists to find.
     - (4) leaves the first wake after a fallback unprotected.
     - The phone is never stuck: an insecure keyguard is one swipe, and handy's lock stays in front. But VoIP and
       time-rule screens stay hidden behind a locked keyguard.
   - **Fix.**
     - Count by state, not callbacks: after a request, if `isKeyguardLocked` is still true 1 s later (callback or
       not), that is one failure.
     - Re-check at every interactive lock resume and once a second while the lock is resumed, the screen is on and
       the keyguard is locked, until it unlocks or the fallback runs.
     - Re-arm the cover while awake: at the next interactive lock resume after the fallback, with no wake pending.
     - Pure tests for each.

## Low

1. **L1: the `SCREEN_OFF` receiver can run with the screen already back on.**
   - `SCREEN_OFF` comes from the late change (Notifier `:676-732`) and waits in the broadcast queue, so a quick press
     can deliver it after the next wake.
   - HOME_THEN_LOCK then starts Home in front of the kid, a visible Home frame. Today that case shows only the lock.
   - Fix: `lockUpStarts(..., interactive)` is LOCK when `interactive`, as §8 1.1 already wants for every re-front with
     the screen on. Add the test row.

2. **L2: kiosk off, `homeFirst = true` leaves through Home in cases the spec doesn't mean.**
   - `go()` (`PinLockActivity.kt:664-670`) also runs for `onResume`'s leave (switch-off, crash guard) and for an
     unlock during a live VoIP or our call. Home then covers the call, and Element hangs up when its PiP goes
     (`LockTaskRoot.kt:34-41`, QA #10).
   - Fix: `homeFirst` unless a call is live, the same rule as the lock-up.
   - Update `LockTaskRoot.kt`'s header ("the kid returns to whatever was open") and the `lockLeave` KDoc
     (`:62-69`), and rename the `LockTaskRootTest` case at `:54`.

3. **L3: §3.4 and §7.4 #3 misstate the screen-on gate.**
   - `hasKeyguard()` (`PhoneWindowManager:6326`) means SystemUI's keyguard service is bound
     (`KeyguardServiceDelegate:160-176`, `:268-270`). It is true with the lockscreen disabled too, so `:6333-6336`
     is not "no keyguard".
   - SystemUI's `onScreenTurningOn` (`KeyguardService:585-619`) calls back through `ScreenOnCoordinator` (fold and
     unfold work), not after a keyguard draw.
   - The only keyguard wait is `waitForAllWindowsDrawn` on the keyguard host window *when it is visible*
     (`WindowState.requestDrawIfNeeded:1985-1989`), that is, un-occluded.
   - With an occluded cover nothing gates the screen-on. The guarantee rests on the visibility rules alone, which
     hold, so the gap is genuinely empty (black).
   - Fix: correct the text. Also close §7.4's notification question with NLUM `:570-600` (AOSP), leaving the Jelly
     Star as a device check.

4. **L4: the cover moves `USER_PRESENT` and adds the lock sounds.**
   - With the lockscreen disabled, `USER_PRESENT` goes out at the wake's start
     (`KVM.maybeSendUserPresentBroadcast:2115-2121`). Under the cover it goes out after the dismissal
     (`onKeyguardExitFinished:3517`), that is, after the lock's first frame. `screenReceiver`'s non-`SCREEN_OFF`
     branch (`PinLockRuntime.kt:665-692`) then runs `TimeRulesRuntime.recheck`, `WindowRelock` and `ScreenOn` a
     second time, over a drawn lock.
   - KVM also plays the lock click at each power-key sleep (`:1845`) and the unlock sound at every dismissal
     (`:3504`). `LOCKSCREEN_SOUNDS_ENABLED` is not on setSystemSetting's allowlist (DPMS `:757-760`).
   - Fix: §11 step 2 checks:
     - no `LockActivity` start or lock re-front after the dismissal;
     - whether the sounds play on the emulator and the Jelly Star.
     If they play, decide with the user (an unlock chime while the lock shows is misleading).

5. **L5: debug-hook details.**
   - Name the exact strings for `CheckDebugHook` and `DebugHookAbsentTest`: `KeyguardCoverDebugReceiver` and
     `keyguard_cover_debug`. Never the bare `KeyguardCoverDebug`: the release class carries that name, and
     `checkReleaseHasNoDebugHook` (`build.gradle.kts:308-313`) would fail every release.
   - Pin the release `KeyguardCoverDebug` body exactly, as `release LockTaskDebug passes the setting through` does
     (`DebugHookAbsentTest.kt:61-69`): one `fun`, `= false`, callers limited to `configure` and the cover cache.
   - `src/main` reads a value cached by `configure`, never CE prefs on the main thread at the lock's resume.
   - §6k must warn that a 16d override clearing `LOCK_TASK_FEATURE_KEYGUARD` makes `LockTaskController` disable the
     keyguard in lock task (`setKeyguardState:926-960`), which turns the cover silently off.

6. **L6: tests that wouldn't catch a regression.**
   - "`HomeFront.bring` comes before `show`" in the `SCREEN_OFF` branch can't see the order: `show` runs inside
     `dispatch`. Route the lock-up through one function, `lockUp(start)`, and pin that.
   - Missing pins:
     - M1's dark-resume branch;
     - M2's `NO_ANIMATION`;
     - M3's four theme items and two TaskDescriptions;
     - `lockOnTopAfter`'s wiring at every call site of `show` (or the flag cleared inside `show` itself);
     - M5's "no `showVoipCall`/`showCall` while the keyguard is locked".

7. **L7: §11 step 1's "Home in Recents/Overview shows a plain black card" can't happen.**
   - `HomeActivity` is `excludeFromRecents` and HOME-typed, so it is never a Recents card.
   - Home's task snapshot is its *top* activity's. During a time rule that is `LockActivity` (M1.3), whose snapshot is
     a real screenshot. §6 already calls it "ours".
   - Fix: replace the check with `dumpsys activity activities` (Home's task top) and the §5 layer trace.

8. **L8: a crash-guard trip with the cover on leaves a Swipe keyguard and no handy lock until the next sync.**
   - `guardTripped` (`PinLockRuntime.kt:960-967`) doesn't re-run `configure`.
   - §7.4 accepts the visible change.
   - Fix: `guardTripped` sets the keyguard DISABLED (one call, background).

9. **L9: the 25b-H SOS heuristic must not see the dismissal.**
   - A lock *stop* within 3 s of a screen-on marks a probable SOS and narrows lock task, closing allowed apps' tasks
     (`noteProbableSos`, 25c H1).
   - By source, the occluded dismissal only hides the keyguard view ("Hiding keyguard while occluded",
     KVM `handleHide:3245-3253`) and pauses nothing.
   - Fix: add a §11 step 2 check: no "probably Emergency SOS" line and no lock pause or stop after a dismissal,
     20 wakes, kiosk on.

**Also verified, no finding:**
- `requestDismissKeyguard` with no credential dismisses without UI (`KeyguardManager:851-873`).
- The occluded hide path doesn't animate the lock.
- Every activity that must show over the keyguard is showWhenLocked: `PinLockActivity`, `InCallActivity`,
  `VoipWakeActivity`.
- BAL for Home and the device owner holds.
- `isInteractive` flips at the early change, before WM's sleep pause, so step 1's "a pause while not interactive is
  the sleep's own" rule is sound.
- None of 25b/25c's fixes is reverted, except where M1 and M2 say so: the camera policy, the 200 ms pause re-front,
  the SOS narrowing, the screen-off heal skip and the opaque, non-`windowShowWallpaper` lock window all stay.
