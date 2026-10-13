# 32 - The previous app flashes before the PIN lock at screen-on (investigation and fix design)

Security bug, reported by the user on 2026-10-13. Android 16 Google Play emulator, gesture navigation, debug launcher
as device owner. At screen-on, a Play Store page shows for about 80 ms before handy's PIN lock covers it.
The user's words: "this MUST be fixed properly".

**Status:** step 1 is built (§14), not device-tested. Step 2 is built behind its debug switch (§15), not device-tested; it also fixes the double press and the backstop re-start (all builds). Step 3 is not built.

The investigation was code reading only; it changed no product code. AOSP references are to `android16-release` on
android.googlesource.com, read 2026-10-13: `frameworks/base/services/core/java/com/android/server/...`, written below
as `wm/`, `policy/`, `power/`, plus `core/java/android/view/ViewRootImpl.java` (VRI) and the Shell's
`libs/WindowManager/Shell/.../transition/Transitions.java`. Line numbers are from that branch on that date.
Launcher paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`.

## 0. In short

- **What the user saw.** At each screen-on, these frames: black; 2 frames of a Play Store **task snapshot** (its
  status bar says 9:55, the real time was 11:17); 2 near-black frames with the live status bar; then the lock.
- **Root cause.** The lock is an ordinary activity. AOSP keeps every activity without turn-screen-on invisible
  while the display sleeps, and the screen-on gate waits only for windows that are already visible with a surface
  (an un-occluded keyguard host window among them). So at every wake the lock **has to be drawn again after the display is lit**. Until its first
  frame and the wake transition land, WM/Shell shows what lies under the lock: the task right under the lock's task,
  as that task's stored snapshot, and then the lock's own plain app-theme snapshot.
  - With an app's task directly under the lock, that is the app's content. Here it was the Play Store, but any
    app can be under the lock in any mode.
  - With Home under the lock it was a plain colour: that is design 25b's leftover flash.
- **The premise that broke.** Design 10 §3 and the comment at `lock/PinLockRuntime.kt:650` /
  `lock/PinLockState.kt:163` say: "started at once while the screen is off, so it is drawn before the next
  screen-on". That has not been true since the shell-transition / sleep-token model (Android 10 for visibility,
  14+ for transitions). The broadcast itself comes **after** the display is off.
- **The requirement (user, final).** The first visible frame after the display turns on is the lock, or black.
  Not another app, and not Home either (§7).
- **What can't meet it** (§7.1-7.3):
  - No activity can stay drawn across the sleep without waking the phone. The only exemption is turn-screen-on.
  - The screen-on gate never waits for the lock: its surface is destroyed at the sleep stop.
  - A cover window would work, but `SYSTEM_ALERT_WINDOW` and accessibility can't be obtained by a device owner.
- **The fix, in two stages (decisions in §10, steps in §8).**
  - **Step 1, now, in release:** the defence in depth.
    - Home goes under the lock at each lock-up while dark, but not during a call.
    - Home's snapshot is plain and black.
    - The lock's stand-in frame is pure black.
    - A lock already on top isn't re-started at screen-off.
    - Unlocking lands on Home.
  - **Step 2, a device experiment behind a debug-only switch:** the system keyguard as a bridge across the sleep.
    - A bare Swipe keyguard: no credential, `KEYGUARD_DISABLE_FEATURES_ALL`.
    - Our `showWhenLocked` lock occludes it while dark. By AOSP's rules nothing that isn't `showWhenLocked` can
      then be visible, so the gap before the lock's first frame is empty (black). The guarantee rests on these
      visibility rules, not on a screen-on gate (L3).
    - The lock delay is 0: `setMaximumTimeToLock` follows the screen timeout (H1).
    - The cover is tried safely: features first, read back, else today's disabled keyguard - never the lock off
      (H2).
    - The lock dismisses it after its first frame, counted by state, with the `setKeyguardDisabled(true)` fallback.
    - Off across a clean reboot; Home dismisses it after a non-clean one.
    - Disabled during a VoIP call.
  - **Step 3:** step 2 becomes the release behaviour once its device checks pass on the emulator and the Jelly Star.
- **Against "lock or black"** (§12): step 2 holds in every ordinary wake. Its residuals are listed honestly: a wake
  that beats our lock to the top (SystemUI's bare lockscreen), a non-clean reboot, the first wake after a VoIP call
  that ended in the dark, and the dismissal fallback.
- **Design QA** (`qa-32-design.md`): all 18 findings are taken; §13 gives the change for each.

## 1. Evidence from the user's run (2026-10-13, 11:17-11:19)

**Screenrecord** (480x854, 24 fps, two screen-ons at ~3.4 s and ~10.0 s, frames extracted by the coordinator):

| Frames | Content | Reading |
| --- | --- | --- |
| n | black | display off |
| 2 (~80 ms) | Play Store "spotify" search page, **status bar 9:55** | a stale **task snapshot** of the Play Store task, not the live app |
| ~2 | black, **live** status bar 11:17 | the lock's **app-theme snapshot / starting window** (`kid_ground` #0C0C14; the lock is `FLAG_SECURE`, so its snapshot is never a screenshot - `wm/ActivityRecord.java:5615` `shouldUseAppThemeSnapshot`) |
| then | the lock: clock, music tile, Lås opp, Nødsamtale | the lock's first real frame |

Between the two flashes the lock was **in front and dimmed by the screen timeout**. So the lock was the top task
when the display went off, and still was at the wake.

**Logcat**, filtered. The lock is task #30 (affinity `me.vibb.launcher.debug.pinlock`). The Play Store task (last
visible at 9:55) is directly under #30.

Screen-off at 11:19:07 (the lock was in front):
```
wm_pause_activity PinLockActivity userLeaving=false,sleep           <- WM pauses it for the sleep token
wm_add_to_stopping completePauseLocked ; VRI visibilityChanged true->false
wm_new_intent [..,30,PinLockActivity] ; wm_task_moved [30,30,0,1,6]  <- our SCREEN_OFF receiver re-starts the lock
wm_set_resumed_activity ; wm_add_to_stopping makeInvisible ; wm_resume_activity ; wm_pause_activity sleep
ActivityTaskManager: START {flg=0x10030000 cmp=PinLockActivity} ... (BAL_ALLOW_ALLOWLISTED_COMPONENT) result code=3
VRI visibilityChanged false->true ; wm_on_resume_called ; paused ; add_to_stopping
viewroot_draw_event Start draw after previous draw not visible ; reportDrawFinished   <- a sync draw while dark
wm_stop_activity ; SurfaceFlinger: Out of order buffers detected for PinLockActivity#1232 (10 -> 2, 10 -> 3)
wm_on_stop_called ; Not drawing due to not visible View.INVISIBLE
WindowManagerShell: onActivityRestartAttempt topActivity=PinLockActivity wasVisible=false
```
Screen-on at 11:18:06 (the previous cycle):
```
06.577 wm_set_resumed_activity PinLockActivity resumeTopActivity ; wm_resume_activity   <- sleep token released
06.585 wm_on_restart_called ; 06.597 on_start ; 06.607 on_resume
06.616 viewroot_draw_event Start draw after previous draw not visible
06.629 reportDrawFinished                                                             <- the lock's first frame
06.635 WindowManager: ... WCT Task #30 pinlock m=TO_FRONT                             <- the WAKE transition ready
06.647 input focus entering PinLockActivity
```
- `flg=0x10030000` is `NEW_TASK | REORDER_TO_FRONT | NO_ANIMATION` (`PinLockRuntime.show`, `:1006`).
- `result code=3` is `START_DELIVERED_TO_TOP`: the lock was already the top task, and the start was needless.
- From the release of the sleep token (06.577) to the WAKE transition being ready (06.635) is about 60 ms. The
  display was already lit in that window. That matches the 2 + 2 frames.

## 2. How the lock comes up today (code)

| Step | Where | What |
| --- | --- | --- |
| Receiver | `PinLockRuntime.init` `:425-434` | `SCREEN_ON`, `SCREEN_OFF`, `USER_PRESENT`, a context-registered receiver for the whole process (no priority; `SCREEN_OFF` is `FLAG_RECEIVER_REGISTERED_ONLY`) |
| Screen-off | `screenReceiver` `:648-663` | `screenOffs++`, `dispatch(ScreenOff)`, the ringer, screen time. The rest is 400 ms later and only while still dark (`screenOffWork` `:701-719`: fence, self-update, policy re-check, status-bar heal) |
| State | `PinLockState.step` `:157-165` | `ScreenOff` -> LOCKED, `showLock` unless the system dialer's call, a VoIP call, or a VoIP ring that already has the lock |
| Start | `dispatch` `:531-533`, `show` `:998-1017` | `startActivity(PinLockActivity, NEW_TASK, REORDER_TO_FRONT, NO_ANIMATION)` **every** screen-off, even when the lock is already the top task. Then, on the LOCKED edge, the chrome on the main thread |
| Activity | manifest `PinLockActivity`, `PinLockTheme` | own task (`.pinlock`), `singleTask`, `showWhenLocked` (no effect: no keyguard), opaque `kid_ground`, `windowAnimationStyle @null`, `FLAG_SECURE`, no open transition (25b) |
| Screen-on | `screenReceiver` `:664-692` | the Play window ends, the stream check, the time-rule re-check, `dispatch(ScreenOn)`: show the lock if it isn't resumed (a backstop) |
| Lock task | `PinLockActivity.ensureLockTask` `:377-402`, `lock/LockTaskRoot.kt` | kiosk on: Home roots lock task, and the lock joins it. Kiosk off: the lock calls `startLockTask` itself (`START_SELF`) |
| Leaving | `lockLeave` `LockTaskRoot.kt:71-76` | kiosk on: through Home (`homeFirst`). Kiosk off: finish - the task underneath (the app) comes back |
| Chrome | `LockTaskChrome` | lock-task packages and features, the status bar, `DISALLOW_CREATE_WINDOWS`. No task moves, apart from Android clearing tasks of unpinned packages |
| Status-bar heal | `StatusBarHealPlan`, `healStatusBar` | flips `SYSTEM_INFO` in the lock-task features for 250 ms / 1 s. **Features only, no packages: it reorders no task.** At screen-off it runs 400 ms later, while dark. |

Nothing in our code moves the Play Store task, finishes the lock, or starts anything at screen-on while the lock is
resumed (the log confirms: `06.577` resumes the lock straight from the sleep token).
- `HomeFront.bring` is called only when the lock leaves, for lock task (kiosk on, `START_HOME`), at boot, and for
  updates.
- `PlayRuntime.suspendStoreAtScreenOn` (`play/PlayRuntime.kt:132`) acts only in the night update window. Suspending
  a package moves no task: `ActivityTaskManagerService.onPackagesSuspendedChanged` only updates recents.
- The status-bar heal, the camera lock and the time-rule re-check don't reorder tasks either.
- The flash therefore comes from the platform's wake path itself, not from one of our starts.

## 3. What AOSP does at screen-off and screen-on

1. **`ACTION_SCREEN_OFF` comes after the display is off.**
   - `power/Notifier.java`: going to sleep sends the broadcast only from the *late* interactive change
     (`handleLateGlobalInteractiveChange`, `:676-732`), after the display power state has reached off.
   - The comment at `:699-702`: "This is a good time to make transitions that we don't want the user to see ...
     There's no guarantee for this however because the user could turn the device on again at any time. Some
     things may need to be protected by other mechanisms that defer screen on."
   - No receiver priority or synchronous handling can get ahead of the display going dark. Android gives an app
     no earlier hook for the power key.
2. **While the display sleeps, every activity without turn-screen-on is invisible.**
   - `policy/PhoneWindowManager.screenTurnedOff` -> `DisplayPolicy.screenTurnedOff` (`wm/DisplayPolicy.java:887-891`)
     acquires the display-off sleep token. `screenTurningOn` (`:858-861`) releases it.
   - In between, `ActivityRecord.shouldBeVisibleUnchecked` returns `canTurnScreenOn()` on a sleeping display
     (`wm/ActivityRecord.java:5893-5899`, `canTurnScreenOn` `:9095-9101`).
   - The lock has no turn-screen-on, and must not have it: that would wake the phone at every lock-up (design 17,
     qa-16-17-code #2). So the lock started at screen-off is resumed, paused and stopped, and made invisible again.
     That is exactly the screen-off log.
   - Its views don't draw: VRI `performDraw` skips while the display is `STATE_OFF` (`:5460`). The exception is a
     sync draw the WM asks for: that is the `reportDrawFinished` in the log. The buffer it draws then is not shown,
     because the activity is made invisible again.
3. **At screen-on, the lock is made visible only after the sleep token goes.**
   - `RootWindowContainer.applySleepTokens` (`wm/RootWindowContainer.java:2541-2617`) starts a `TRANSIT_WAKE`
     transition, resumes the top activity and makes it visible (`06.577` in the log).
   - The lock then has to restart, resume and draw (`06.585`-`06.629`). Only then is the transition ready and
     played (`06.635`).
4. **The screen-on gate does not wait for the lock.**
   - `PhoneWindowManager.screenTurningOn` (`:6315-6345`) holds the display off for SystemUI's
     `onScreenTurningOn(mKeyguardDrawnCallback)` (`:6330`, with a timeout) and then for `waitForAllWindowsDrawn`
     (`:6253`, 1000 ms).
   - Corrected after QA (L3):
     - `hasKeyguard()` means SystemUI's keyguard service is bound (`KeyguardServiceDelegate:160-176`), which is
       true with the lockscreen disabled too.
     - SystemUI's callback (`KeyguardService:585-619`) goes through `ScreenOnCoordinator` (fold work), not after a
       keyguard draw.
     - The keyguard is waited for only through `waitForAllWindowsDrawn`, as the keyguard host window, and only
       while it is visible (`requestDrawIfNeeded` `:1985-1989`).
   - `waitForAllWindowsDrawn` asks only windows that are already visible (`WindowState.requestDrawIfNeeded`,
     `wm/WindowState.java:1958-1996`: `isVisible()` needs a surface; the window's activity must be
     visible-requested; an activity with a starting window isn't waited for).
   - A lock that was invisible all through the sleep isn't on that list. So the display turns on before the lock
     has drawn, and stays on during the ~60 ms above.
5. **What fills that gap is the content under the lock.**
   - In the user's run, that was the snapshot of the task directly under the lock's task (Play, from 9:55), and then
     the lock's own app-theme snapshot.
   - Task snapshots are shown as a starting window or transition content when a task is (re)shown before it has
     drawn (`ActivityRecord.getStartingWindowType` `:2392-2430`, `ActivityStarter` `:2415-2417`,
     `ActivityTaskSupervisor.findTaskToMoveToFront` `:1626`, `TaskFragment.resumeTopActivity` `:1741-1746`).
   - Exactly which code path puts the *lower* task's snapshot up in the WAKE window is the one point not settled by
     reading. Section 5 names the trace that settles it. The keyguard cover (step 2) does not depend on it.
6. **The system keyguard is the only thing that gets a real guarantee.**
   - Activities that aren't `showWhenLocked` stay invisible while it is locked
     (`KeyguardController.checkKeyguardVisibility`). That holds whether it is shown or occluded.
   - Un-occluded, its host window is not an activity, stays drawn across the sleep, and is waited for at the
     screen-on (L3).
   - Handy switched the keyguard off in design 10 (`setKeyguardDisabled(true)`, `PinLockRuntime.configure`
     `:926-933`), so none of this applies to handy's lock.
7. **Background activity starts are not the problem.** Home and device owner are both allowed
   (`wm/BackgroundActivityStartController.java:1068-1092`). The log shows `BAL_ALLOW_ALLOWLISTED_COMPONENT`.

## 4. Root cause

1. **Primary.**
   - The design relies on the lock being drawn while the screen is off. Android doesn't allow that for an activity
     (§3.2), and doesn't wait for it at the wake (§3.4).
   - So every screen-on shows the platform's stand-in for the lock's task until the lock's first frame lands
     (~60 ms on the emulator, plus the panel's own start).
   - The stand-in reveals **the task directly under the lock**, by its stored snapshot.
2. **What decides what leaks.**
   - Nothing in handy controls which task is under the lock. It is whatever the kid was in when the screen went off.
   - When the lock leaves with the kiosk off, the app underneath simply returns.
   - In install mode, the pause, the override, and the kiosk with any allowed app, that task is **another app**.
     Its snapshot is that app's last visible frame - here the Play Store from 9:55, possibly far older than the
     session.
3. **Why 25b only saw a plain colour.** Those runs locked up from Home. Home was under the lock, so the stand-in
   was Home's snapshot or starting window and then the lock's `kid_ground`. 25b's fixes (no transition, the 400 ms
   deferral) shortened the gap but couldn't close it.
4. **A contributing factor (likely, not proven).** At every screen-off we re-start a lock that is already the top
   task (`result code=3`).
   - While dark, that adds a resume, a sync draw, a stop, an Out-of-order-buffers warning, and
     `onActivityRestartAttempt ... wasVisible=false`.
   - It may also be what marks the wake as a task switch onto #30. It is needless either way.

## 5. Hypotheses still open, and what tells them apart

| # | Hypothesis | Rank | Confirms it | Refutes it |
| --- | --- | --- | --- | --- |
| H1 | At the WAKE, until #30 has drawn, Shell/WM shows the task under #30 by its snapshot (starting-window snapshot or transition content) | most likely | the SurfaceFlinger layer trace at the flash frame has a visible layer named after the Play task's snapshot / `Snapshot Starting com.android.vending/...`, or a Shell transition leash with the snapshot; the WM ProtoLog shows the snapshot starting window being added to the Play task, or the Play task in the WAKE `TransitionInfo` | no Play layer in the layer trace at the flash time |
| H2 | Our needless re-start while dark (`START ... result=3`) causes it: `wm_task_moved` and the sleep-time resume/stop leave a pending transition or starting window that makes the WAKE a task switch from the task below | possible, contributes | the flash goes away when that start is skipped (step 1 item 2 alone), with the Play Store still directly under the lock | the flash stays with the start skipped |
| H3 | A stale starting window on the Play task, left from earlier (Play's process died after 9:55), shown when anything re-evaluates visibility | less likely | `dumpsys activity activities` before the screen-off lists `mStartingWindow` / a `Snapshot Starting` window on the Play task; the layer trace has it as a hidden layer that becomes visible | no starting window on the Play task before the flash |
| H4 | Process death or an exception finishes the lock at the screen-off | ruled out by this log | - | `06.577` resumes the same #30 straight from the sleep token; no START at the wake |

**What to capture** (emulator, the same steps as the user's run):
```
# WM + Shell ProtoLog as text in logcat
adb shell wm logging enable-text WM_DEBUG_STARTING_WINDOW WM_DEBUG_WINDOW_TRANSITIONS WM_DEBUG_STATES \
    WM_DEBUG_SCREEN_ON WM_DEBUG_SLEEP_TOKEN WM_DEBUG_TASKS WM_DEBUG_APP_TRANSITIONS
adb shell dumpsys activity service SystemUIService WMShell protolog enable-text \
    WM_SHELL_TRANSITIONS WM_SHELL_STARTING_WINDOW
adb logcat -b all -v threadtime > flash.log &
# the decisive one: a SurfaceFlinger layers + transactions and Shell transitions trace (open in ui.perfetto.dev / Winscope)
adb shell perfetto -o /data/misc/perfetto-traces/flash.pftrace -t 20s \
    -c - --txt <<'EOF'
buffers { size_kb: 65536 }
data_sources { config { name: "android.surfaceflinger.layers" surfaceflinger_layers_config { mode: MODE_ACTIVE trace_flags: TRACE_FLAG_INPUT } } }
data_sources { config { name: "android.surfaceflinger.transactions" } }
data_sources { config { name: "com.android.wm.shell.transition" } }
data_sources { config { name: "android.protolog" } }
EOF
# while it records: power off, wait 3 s, power on (twice)
adb pull /data/misc/perfetto-traces/flash.pftrace
adb shell dumpsys activity activities > tasks.txt   # task order, starting windows, mStartingWindow per task
```
- In the layer trace, step to the first lit frame after the wake. The visible layer stack names who is on screen:
  a `Snapshot`/`Splash Screen`/`Starting` layer, the Play `ActivityRecord`, or the `PinLockActivity` layer. The
  ProtoLog lines with the same timestamp name the code path (H1 or H3).
- For H2: build with the screen-off start skipped when the lock is top (step 1 item 2 alone) and repeat.
- Logcat lines worth grepping: `wm_task_moved`, `wm_set_resumed_activity`, `wm_add_to_stopping`,
  `ActivityTaskManager: START`, `addStartingWindow` / `Adding starting window`, `TaskSnapshot`,
  `StartingSurface`, `onTransitionReady`, `Finished screen turning on`, `Remove SleepToken`, `PinLock`.

## 6. Every mode

The mechanism is the same everywhere. What differs is which task lies under the lock.

| Mode | Under the lock at a lock-up | Leaks today? |
| --- | --- | --- |
| Kiosk on, from Home | Home (lock-task root) | plain colour only (25b's residual) |
| Kiosk on, from an allowed app | that app's task (in lock task) | **yes**, that app's snapshot - not Play-specific |
| Install mode (Play pinned in the kiosk list) | the Play Store task | **yes** |
| Restrictions pause (kiosk off) / offline override | any app, Play included (in the drawer since design 28) | **yes** |
| Time-rule lock | the time-rule screen (`LockActivity`, ours) or a usable app. At screen-on `TimeRulesRuntime.recheck` may start `LockActivity`, then `TimeRuleShown` re-fronts the lock (`PinLockRuntime.kt:681`, `TimeRulesRuntime.kt:224-226`) | ours, or the usable app's snapshot |
| Our call (power button in a call) | the call screen / the lock; afterwards the app the call interrupted | the same as the mode |
| System dialer's call, VoIP in a call | the lock isn't started (exempt) | n/a |
| VoIP ring | the lock is woken by `VoipWakeActivity` (turn-screen-on) | the lock is visible while dark, so no |
| Re-front after a yield (SOS, alarm, Recents) | the app that came over the lock | the next screen-on leaks it |

- **Emulator vs. real phone.**
  - On the emulator the panel is lit at once and rendering is slow (SwiftShader / host GPU), so the whole ~60 ms
    gap is visible.
  - A real panel spends 30-100 ms powering up, often with brightness ramping from 0, which can hide part of the gap.
  - The Jelly Star must be checked on whatever Android it runs. Android 13 still has legacy app transitions, where
    the reveal path is different but the sleep visibility and the screen-on gate (§3.2, §3.4) are the same.

## 7. The requirement and what can meet it

**The user's requirement (final, 2026-10-13).** At screen-on nothing may flash before the lock: no other app's
snapshot, and **not Home either**. The first visible frame after the display turns on is the lock itself, or black
at most.

That changes the question. It is no longer "what lies under the lock" but "what can be on screen in the gap before
the lock's first frame". Below is each mechanism that could put the lock - or a cover - on screen before the display
turns on, checked against AOSP 16.

### 7.1 Can the lock window stay visible across the sleep? No (without waking the phone)

- **Why the lock is paused and stopped "sleep".**
  - `DisplayPolicy.screenTurnedOff` takes the display-off sleep token (`wm/DisplayPolicy.java:887-891`).
  - `applySleepTokens` then runs `goToSleepIfPossible` for every root task (`wm/RootWindowContainer.java:2585-2589`).
  - `TaskFragment.sleepIfPossible` (`wm/TaskFragment.java:1089-1120`) pauses the resumed activity. The only
    exception is an activity that `canTurnScreenOn()`; then it logs "Waiting for screen on due to ...".
  - Visibility follows `ActivityRecord.shouldBeVisibleUnchecked`. On a sleeping display, unless the keyguard is
    going away, it returns `canTurnScreenOn()` (`:5893-5899`).
- **The only exemption is turn-screen-on.**
  - `canTurnScreenOn()` (`:9095-9101`) needs the turn-screen-on flag, `mCurrentLaunchCanTurnScreenOn` and keyguard
    visibility.
  - Such an activity *wakes the phone*: `KeyguardController` `:771-776` (`wakeUp(... "handleTurnScreenOn")`),
    and the window's relayout.
  - Once it has turned the screen on, `mCurrentLaunchCanTurnScreenOn` is cleared, so it isn't exempt at the next
    sleep either.
  - `setShowWhenLocked` doesn't exempt anything. Neither does `FLAG_KEEP_SCREEN_ON`: it only holds a wake lock,
    which the power key ignores.
  - **Conclusion: an activity can't be drawn and shown while dark without lighting the screen at every lock-up.**
    That is rejected (pocket, battery, design 17 qa-16-17-code #2).
- **Even if it were drawn while dark, its surface would be gone.** At the sleep stop, `activityStopped`
  (`:6386-6440`) calls `destroySurfaces()` and `removeStartingWindow()`. The wake starts from nothing again.

### 7.2 Can the lock be one of the windows the screen-on waits for? No

- `PhoneWindowManager.screenTurningOn` (`:6315-6345`) blocks the display for two things:
  1. SystemUI's `onScreenTurningOn` callback, which is fold work, not a keyguard draw (L3);
  2. `waitForAllWindowsDrawn` (`WindowManagerService.java:7975-8015`, timeout 1000 ms).
- `waitForAllWindowsDrawn` asks `WindowState.requestDrawIfNeeded` (`:1958-1996`). That needs `isVisible()`, which
  needs a surface (`wouldBeVisibleIfPolicyIgnored`, `:1716-1722`), and for an activity window it needs a
  visible-requested activity that has no starting window.
- The lock lost its surface at the sleep stop (§7.1), so it is never on that list.
- Only windows that stayed visible with a surface through the sleep are ever waited for at a screen-on. Those are
  non-activity windows, the un-occluded keyguard host among them. That leads to the two candidates below.

### 7.3 An always-present cover window: strong, but not available to us

- A non-activity window isn't touched by the sleep token. It stays visible with its last buffer. Its first relayout
  draws even while dark (VRI `:4411` `reportNextDraw("first_relayout")`, which bypasses the `STATE_OFF` skip at
  `:5460`).
- So a full-screen cover added at `SCREEN_OFF` would be on screen at the wake, already drawn. That is a real
  first-frame guarantee. But:
- **`TYPE_APPLICATION_OVERLAY` needs `SYSTEM_ALERT_WINDOW`.** That is an app-op permission
  (`core/res/AndroidManifest.xml:4450-4453`, `signature|setup|appop|installer|pre23|development`).
  - A device owner can't grant it: `setPermissionGrantState` accepts only runtime (dangerous) permissions
    (`DevicePolicyManagerService.java:16918`, `isRuntimePermission` `:17078-17083`).
  - It would have to be granted by hand or by adb at provisioning, on every phone, and a reset of app ops loses it.
  - It is also defeated by:
    - our own `DISALLOW_CREATE_WINDOWS`, set while LOCKED, which hides every overlay including ours. Dropping it
      would let any other app with the grant draw over the lock.
    - any app window with `setHideOverlayWindows(true)`, which needs only a normal permission.
    - the untrusted-touch rules over calls.
  - The overlay sits under the status and navigation bars.
- **`TYPE_ACCESSIBILITY_OVERLAY` needs an enabled accessibility service.**
  - A device owner can't enable one: `setSecureSetting` allows only `DEFAULT_INPUT_METHOD`, `SKIP_FIRST_USE_HINTS`,
    `INSTALL_NON_MARKET_APPS` and `LOCATION_MODE` (`DevicePolicyManagerService.java:728-735`, `:15294-15298`).
  - Android 13+ restricts it for sideloaded apps.
  - It would give the launcher the strongest privilege on the phone (reading and acting on every app). That's
    against the security model.
- **Verdict: rejected.** The guarantee is real, but neither window type can be obtained by a device owner, and the
  overlay weakens the lock's own overlay block.

### 7.4 The real system keyguard as the bridge: the guarantee, by AOSP's own rules

**The setup.**
- The keyguard is back on as **Swipe, with no credential**: `setKeyguardDisabled(false)`. With no credential CE
  storage still unlocks by itself at boot (`unlockUserKeyIfUnsecured`), so the BFU lockout class of design 10 §2
  doesn't come back.
- `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_FEATURES_ALL)` makes it a bare cover. `<disable-keyguard-features/>`
  is already declared (`res/xml/device_admin.xml`).
- `LOCK_TASK_FEATURE_KEYGUARD` is already forced on, so the keyguard works in lock task.
- `PinLockActivity` is already `showWhenLocked`, and so are `InCallActivity` and `VoipWakeActivity`.

**What AOSP then guarantees.**
1. While the keyguard is locked, only `showWhenLocked` / dismiss-keyguard activities can be visible
   (`KeyguardController.checkKeyguardVisibility` `:405-419`).
   - The Play Store, Home, the time-rule screen and every other app are not visible-requested at the wake.
   - Their snapshots and starting windows are children of their activities or tasks, so they can't be on screen
     either. That holds whichever WM/Shell path showed the Play snapshot (§5).
2. Occlusion is decided by the top activity's `canShowWhenLocked()`, not by whether it is visible
   (`KeyguardController.updateVisibility` `:711-760`). So our lock, started at `SCREEN_OFF`, makes the keyguard
   **occluded during the sleep**. SystemUI's lockscreen then isn't shown at the wake either.
3. Corrected after QA (L3): with the keyguard *occluded* nothing gates the screen-on. The guarantee rests on 1
   and 2 alone, and they hold, so the gap is genuinely empty: no window of another package is visible-requested,
   and the lock's is not drawn yet.

**What is left in the gap.** Only `showWhenLocked` windows of the top task: the lock's own app-theme snapshot or
starting window (the lock is `FLAG_SECURE`, so never a screenshot - `ActivityRecord.shouldUseAppThemeSnapshot`
`:5615`), or nothing at all. With the lock's window background and task-description colour **pure black**, that
gap is black. So the first visible frame is black or the lock - the requirement.

**Keeping today's behaviour while the screen is on.**
- The keyguard is only needed from the sleep to the lock's first frame. The resumed lock dismisses it once it has
  drawn after a wake: the first `OnDrawListener` frame, then `KeyguardManager.requestDismissKeyguard(this, cb)`.
- The keyguard is insecure, so dismissal needs no UI, and it is occluded, so nothing shows.
- From then on everything is as today: the yields (SOS, the emergency dialer, an alarm, VoIP call screens that
  aren't `showWhenLocked`), Recents, the drawer and the apps.
- The next sleep shows it again by itself, and occluded at once if the lock is the top task.

**The remaining risks** (each a device check, §11):
- **The lock reaching the top after the keyguard was shown.**
  - At the power key the top is the app (not `showWhenLocked`), so SystemUI shows the lockscreen *un*-occluded
    during the sleep. When the lock comes up at `SCREEN_OFF`, an occlude transition follows, while dark.
  - A screen-on that beats it - a press within ~100 ms, or a slow main thread - would show **SystemUI's swipe
    lockscreen**, not an app. That is still not "lock or black".
  - So `KEYGUARD_DISABLE_FEATURES_ALL` and no AOD matter.
  - Closed by QA (L3): in AOSP SystemUI, `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` hides lock-screen notifications
    whether or not the keyguard is secure (NLUM `updateDpcSettings` `:592-600`,
    `updateLockscreenNotificationSetting` `:570-586`). The Jelly Star's SystemUI is a device check.
  - This window is the reason the lock is started first at `SCREEN_OFF`, with no work before the start.
- **Boot.** A swipe keyguard shows at boot before our process.
  - The boot mark (16e) on Home is hidden behind it (Home isn't `showWhenLocked`).
  - Either Home dismisses it at its locked resume (`requestDismissKeyguard` is allowed for an activity that is
    `visibleIgnoringKeyguard`, `KeyguardController.dismissKeyguard`), or the boot shows the lock without the mark.
    Decided after QA (M4): the keyguard is switched off at `ACTION_SHUTDOWN`, so a clean reboot has none. The cover
    is re-armed once the boot's lock is up with the screen on. Home's boot dismissal stays as the backstop for a
    crash or power-loss reboot.
- **Unlock.**
  - Nothing to do: the keyguard has been dismissed since the first frame.
  - The `isKeyguardLocked()` readers in our code (none today besides `ScreenTimeTracker`'s, which design 10 says
    is always false) must stay correct.
  - `lockActivation` must stop requiring `setKeyguardDisabled(true)` (`KEYGUARD_NOT_DISABLED`).
- **Dismissal failing** (callback `onDismissError`/`onDismissCancelled`).
  - The keyguard stays locked behind the lock. The lock still works, but non-`showWhenLocked` yields (a VoIP app's
    call screen, the time-rule screen) would be hidden.
  - So retry at the next resume, report it in `lock_state`, and fall back to `setKeyguardDisabled(true)` (which
    dismisses it) after two failures.
- **The camera double-press over a keyguard.** The cameras are off by policy while LOCKED (25b) and the gesture
  checks that first, so nothing changes.
- **The system's own Swipe screen** where the lock isn't on top while locked: after a crash with the guard tripped,
  or a time with no lock. It is one swipe, never a security boundary, and our lock still re-fronts. But it is a
  visible change: a kid could see a stock lockscreen in those cases.

### 7.5 Ranking

| Rank | Option | Guarantees "first frame = lock or black"? | Risk / cost |
| --- | --- | --- | --- |
| 1 | **Keyguard bridge** (7.4) + a black lock background + no redundant start | **yes** by AOSP's keyguard visibility rules, with the lock delay at 0 (H1). Exceptions are listed per mode in §12 (the occlude window shows SystemUI's bare lockscreen) | medium: reverses design 10's "no Android lock screen" for the sleep span. Boot, dismissal and yield checks |
| 2 | Cover overlay (7.3) | yes while present | high / not available: `SYSTEM_ALERT_WINDOW` isn't DO-grantable; conflicts with `DISALLOW_CREATE_WINDOWS` and hide-overlay; accessibility is worse |
| 3 | Home under the lock + black snapshots (step 1) | no - only if the reveal path shows the task directly under the lock (not proven, §5). Home's frame is a visible frame unless it is pure black | low. Kept as defence in depth |
| 4 | Earlier start, display callbacks, wake locks, `showWhenLocked` alone | no (§7.1, §7.2) | - |
| 5 | Turn-screen-on on the lock | the lock would be visible while dark, but every lock-up wakes the phone | rejected |
| - | `FLAG_SECURE` on other apps | impossible | - |

**What 25b/25c already did** (not repeated here):
- no open transition (`NO_ANIMATION`, `overrideActivityTransition`, `windowAnimationStyle @null`);
- the screen-off work deferred 400 ms;
- the first lock resume after a screen-off heal skips its own heal;
- the starting window kept `kid_ground` (25f).

## 8. Steps

There are two stages (decision 1, §10), revised after the design QA (`qa-32-design.md`; §13 lists each finding and
its change).
- Step 1 is the defence in depth and is built now.
- Step 2 builds the keyguard cover as a device experiment behind a debug-only switch.
- Step 3 promotes it once the device checks have passed.

Each step is one sitting, green on its own, with its tests.

### Step 1 - Home under the lock, the black stand-in, no needless start (release behaviour)

1. **One lock-up function.** Every lock-up goes through `PinLockRuntime.lockUp(start: LockUpStart)` (L6).
   - The `SCREEN_OFF` branch computes
     `lockUpStarts(showLock, lockOnTop, callLive, interactive): LockUpStart { NONE, LOCK, HOME_THEN_LOCK }`
     (pure, `lock/LockUpPlan.kt`).
   - HOME_THEN_LOCK only while dark and with no live call: ours (`OngoingCalls.hasLiveCall`), Telecom's, or a VoIP
     phase other than NONE.
   - **LOCK when `interactive`** (L1): a `SCREEN_OFF` delivered after the next wake starts no visible Home.
   - LOCK during a call.
   - NONE when `lockOnTop`.
   - `lockUp` starts Home (`HomeFront.bring(context, why, animate = false)`), then the lock (`show`), on the main
     thread, in that order.
   - `dispatch`'s `show` for a `ScreenOff` event is routed through `lockUp`, so the order is visible in one function
     and pinned there.
   - The re-fronts with the screen on stay lock-only.
2. **Home's start doesn't animate** (M2).
   - `HomeFront.bring(..., animate = false)` adds `FLAG_ACTIVITY_NO_ANIMATION`; the intent is still typed HOME
     (`HomeFrontTest` allows the flag).
   - The lock-up path is pinned to pass `animate = false`.
   - Every other `bring` is unchanged.
3. **Home's resume while dark does almost nothing** (M1).
   - `lockUp(HOME_THEN_LOCK)` sets the runtime mark `darkLockUp`, cleared at the next interactive resume of the
     lock or Home.
   - `HomeActivity.onResume` with `darkLockUp` and `!interactive` shows the night ground and nothing else.
     Corrected after code QA (qa-32-s1-code L1): it does **not** run `reconcileKioskMode()`. `lockUp` starts the
     lock in the same main-thread pass, so by Home's resume the lock's task is the focused one and
     `startLockTask()` from Home can only throw ("Invalid task, not in foreground"). With the kiosk on and no lock
     task running, the lock's own `ensureLockTask` asks Home to root it (`START_HOME`), as before design 32.
   - It runs no `UpdateFence.onFront`, no `redirectToLockScreenIfLocked` (so no `LockActivity` over the lock while
     dark, M1.3), no `PinLockRuntime.show` and no boot-mark calls.
   - `onStart`'s card renders and listener registrations are skipped while `darkLockUp && !interactive`. They run
     at the next interactive start or resume.
   - Pure: `homeDarkResume(darkLockUp, interactive, locked): Boolean`. The branch is pinned in a test.
   - The lock's own main-thread work while dark is unchanged (25b-B's 400 ms deferral stands). Home's dark resume
     adds only the night ground before the lock's `onCreate`.
4. **`lockOnTop`, best effort, with its own debug toggle** (M1, §5 H2).
   - Set by `lockUp(HOME_THEN_LOCK)` and by `lockUp(LOCK)`.
   - Not cleared by lock starts that belong to the same lock-up: those that come before the next interactive resume
     of the lock, including the `ScreenOn` backstop when the lock resumes without an interactive pause before it.
   - Cleared by:
     - any interactive pause or stop of the lock;
     - the lock's destroy;
     - the UNLOCKED edge;
     - any lock start from a re-front, `TimeRuleShown`, `CallsEnded`, VoIP or process start.
   - The flag is cleared inside `show` itself unless `show` is called from `lockUp`, so no call site can forget it
     (L6).
   - It is best effort: its job is to skip the `result=3` re-start, not to guarantee anything.
   - For §5's H2 experiment, `src/debug`'s existing `LockTaskOverrideReceiver` pattern gets an extra
     `--ez skipRestart true` - in a debug-only `LockUpDebug`, named in `CheckDebugHook` and `DebugHookAbsentTest`,
     the release twin passes through.
5. **The black stand-in, every colour source** (M3, decision 3).
   - In `PinLockTheme`, the four items are `@android:color/black`: `windowBackground`,
     `windowSplashScreenBackground`, `statusBarColor` and `navigationBarColor`.
   - The lock's `onCreate`, before `setContentView`, calls
     `setTaskDescription(TaskDescription.Builder().setBackgroundColor(BLACK).setStatusBarColor(BLACK).setNavigationBarColor(BLACK).build())`.
   - The lock still paints its wallpaper and scrim itself (25f). The system bars over the drawn lock are black
     instead of `kid_ground`; that is a visible change, accepted with decision 3.
   - Home: `setRecentsScreenshotEnabled(false)` and the same `TaskDescription` builder in `onCreate`. Home's splash
     (16e's breathing mark on `vibb_night`) and its bars are unchanged - it is a launch splash, not the stand-in.
6. **Unlocking lands on Home** (decision 2), but never over a call (L2).
   - `lockLeave(kioskOn, startedLockTask, lockTaskRunning, callLive)`: `homeFirst = kioskOn || !callLive`. Kiosk off,
     this means unless a call (ours, Telecom's, VoIP) is live - Home must not cover a call. Element hangs up when
     its PiP goes (`LockTaskRoot.kt:34-41`).
   - Update `LockTaskRoot.kt`'s header ("the kid returns to whatever was open"), the `lockLeave` KDoc (`:62-69`)
     and rename the `LockTaskRootTest` case at `:54`.
7. **Comments corrected.**
   - `PinLockRuntime.kt:650` and `PinLockState.kt:163` stop claiming the lock is drawn before the screen-on.
   - Design 10 §3 gets a pointer here.
   - `launcher/CLAUDE.md` gets a "Lock flash (design 32)" bullet.
8. **Log:** `Lock-up at screen-off: NONE|LOCK|HOME_THEN_LOCK (call=<bool>, interactive=<bool>)`.

### Step 2 - The keyguard cover as a device experiment (debug builds only)

The release behaviour stays exactly as after step 1. Everything new is reachable only while the debug switch is on.

1. **The switch** (L5).
   - `src/debug`'s `lock/KeyguardCoverDebugReceiver`: exported, guarded by `android.permission.DUMP`.
   - Usage: `adb shell am broadcast -n $P/com.kidslauncher.mdm.lock.KeyguardCoverDebugReceiver --ez on true|false`.
     Optional extras: `--ez skipDismiss true` and `--ez failDismiss true`, for the failure checks.
   - It writes CE prefs `keyguard_cover_debug`, requests an apply, and logs and returns
     `KeyguardCover: on|off -> ...`.
   - `src/main` reads only `KeyguardCoverDebug.enabled`. That is a value `configure` caches (`@Volatile`), never
     CE prefs on the main thread.
   - `src/release`'s `KeyguardCoverDebug` has exactly one `fun enabled(...) = false`, pinned like `LockTaskDebug`'s
     pass-through (`DebugHookAbsentTest.kt:61-69`). Its callers are limited to `configure` and the cover cache.
   - `CheckDebugHook` and `DebugHookAbsentTest` ban the exact strings `KeyguardCoverDebugReceiver` and
     `keyguard_cover_debug`. Never the bare `KeyguardCoverDebug`: the release class carries that name.
2. **One writer for the keyguard** (H2, M6).
   - `PinLockRuntime.configure` alone writes `setKeyguardDisabled`, `setKeyguardDisabledFeatures` and
     `setMaximumTimeToLock` (item 3).
   - `AppEnforcer.applyKeyguardFeatures` is folded into it, called from the same apply, in one fixed order.
   - The pure decision is
     `keyguardMode(managed, hasKidPin, deviceSecure, coverWanted, voipPhase, guardTripped): COVER | DISABLED | UNTOUCHED`:
     - COVER only when the switch is on, the phone is managed, has a kid PIN and no credential, the VoIP phase is
       NONE (M5) and the guard hasn't tripped (L8);
     - DISABLED wherever today's code disables;
     - UNTOUCHED when not managed.
   - **COVER is tried in this order** (H2):
     1. Write the features: `keyguardDisabledFeatures(current, managed, cover = true, coverAdded)` =
        `KEYGUARD_DISABLE_FEATURES_ALL`.
     2. Read them back: `getKeyguardDisabledFeatures(admin)` must equal ALL.
     3. Write `setMaximumTimeToLock(T)` (item 3) and read it back.
     4. Only then `setKeyguardDisabled(false)`.
   - **Any failure** falls back to `setKeyguardDisabled(true)` (today's path), the features with the cover's bits
     taken out, and max-time-to-lock 0. `lockActivation` then sees today's `keyguardDisabled = true`.
   - **The PIN lock is never switched off because of the cover.** `KEYGUARD_NOT_DISABLED` keeps its meaning (a
     credential, or an exception on the `true` call). The failed cover is reported separately (item 7).
   - **The features: the cover clears exactly what it set** (M6). Pure
     `keyguardDisabledFeatures(current, managed, cover, coverAdded): Pair<Int, Int>` returns the value and the new
     `coverAdded`.
     - Cover on: ALL, `coverAdded = ALL and not current` - the bits that weren't set before.
     - Cover off: `current and not coverAdded`, plus SECURE_CAMERA when managed (today's rule), and
       `coverAdded = 0`.
     - Unmanaged: `current and not coverAdded`, minus SECURE_CAMERA (today's rule).
     - `coverAdded` is kept in CE prefs `keyguard_cover` (commit) before the write. A parent's or another tool's
       bits survive.
3. **The lock delay is 0 under the cover** (H1).
   - Without this, SystemUI shows even a Swipe keyguard only `lock_screen_lock_after_timeout` (default 5 s) after
     a screen *timeout* (KVM `onStartedGoingToSleep` `:1812-1846`, `getLockTimeout` `:1973-2005`). A DO can't write
     that setting.
   - So under the cover `setMaximumTimeToLock(admin, T)` is set, with T = the **effective** `SCREEN_OFF_TIMEOUT`.
     `getLockTimeout` then gives `min(T - displayTimeout, lockAfter) = 0`, and the timeout path locks at once.
   - `<force-lock/>` is already declared (`res/xml/device_admin.xml`).
   - **T follows the setting in every mode** (`screenTimeoutAction`, `server/ScreenTimeout.kt`):
     - Enforce (the parent's "turn off after", `screen_timeout_seconds`, clamped 15 s-10 min): T = the enforced
       value. It is written by `ScreenTimeout.apply` right after `SCREEN_OFF_TIMEOUT`, in the same pass.
     - Release (the pause, the override, no value): T = the phone's own value. `DISALLOW_CONFIG_SCREEN_TIMEOUT` is
       lifted then, so the setting can change.
       - `ScreenTimeout`'s `ContentObserver` is registered whenever the cover is on, not only while enforcing.
       - On every change it writes T anew instead of putting the target back.
     - The order matters. A T *above* the setting gives a delay > 0 (fails H1). A T *below* it shortens the screen
       timeout (PowerManager takes the minimum, and Settings greys out longer values).
       - So when the setting grows, T is written after it; when it shrinks, T is written first.
       - A failed write falls back as in item 2.
   - Pure: `maxTimeToLock(coverOn, effectiveTimeoutMs): Long` (0 = none) and
     `timeoutWriteOrder(oldMs, newMs): SETTING_FIRST | T_FIRST`.
   - Off, unmanaged or no cover: T = 0 (cleared).
   - `ScreenTimeout` stays the only writer of `SCREEN_OFF_TIMEOUT`. `configure` asks `ScreenTimeout` for T; one
     writer per value.
4. **Boot without the keyguard** (M4).
   - `BootCover`'s `ACTION_SHUTDOWN` receiver (`lock/BootCover.kt`, already runtime-registered and synchronous)
     calls `setKeyguardDisabled(true)` while the cover is on, so a clean reboot shows the boot animation, the 16b
     cover or Home's mark, and the lock - as today.
   - The cover is re-armed by the first interactive lock resume of the new process. `setKeyguardDisabled(false)`
     only writes the setting, so it takes effect at the next sleep.
   - A crash, power loss or `adb reboot` sends no `ACTION_SHUTDOWN`, so the cover stays on. Then SystemUI's Swipe
     lockscreen shows from the end of the boot animation until our first activity. That is accepted and listed in
     §12.
   - Home's boot dismissal (decision 4) is the backstop for that case:
     - at Home's locked resume in the boot's first process start, `requestDismissKeyguard` while `isKeyguardLocked`;
     - retried by state, as item 6;
     - not counted as a failure while SystemUI hasn't shown the keyguard yet (`!isKeyguardLocked`).
5. **Calls and other apps' screens wait for the unlock** (M5).
   - **VoIP:** `keyguardMode` is DISABLED while the VoIP phase isn't NONE.
     - It is written only while interactive: at the phase edge if the screen is on, else at the first interactive
       lock resume after the dismissal.
     - A ring from dark is therefore still covered: `VoipWakeActivity` and the lock are `showWhenLocked`.
     - The call itself then runs with today's disabled keyguard.
     - COVER comes back at the NONE edge, again only while interactive.
   - **Every screen of another app sent from the lock waits until the keyguard is unlocked**: dismissal success or
     `!isKeyguardLocked`.
     - This covers `showCall`, `showVoipCall` (`VoipCalls.reopenCall`), the 17b FSI send (`sendVoipRingScreen`),
       and the time-rule screen's start at a screen-on (`TimeRulesRuntime.recheck` -> `LockActivity.start`).
     - Without the wait, a non-`showWhenLocked` activity on top un-occludes the keyguard (SystemUI's lockscreen
       shows) and makes the lock's own dismissal fail (`dismissKeyguard` `:334`).
     - Pure: `sendAfterUnlock(coverOn, keyguardLocked): Boolean`. The queued sends run from the dismissal's
       success or the state re-check.
   - **The system dialer's call ending while dark.**
     - That isn't our event, and today's re-front stops while dark. So the app under the dialer becomes the top
       with the keyguard un-occluded.
     - A re-front check while dark, LOCKED, `!lockOnTop` and nothing exempt now does `lockUp(HOME_THEN_LOCK)` once
       (pure: `refrontAction`'s `!interactive` row becomes `DarkLockUp` when `!lockOnTop`, else `Stop`).
     - The `system_call` yield already re-checks every 2 s.
6. **The dismissal, counted by state** (M7).
   - **When:** `PinLockActivity`, once the screen is on, the lock resumed and the first frame since the resume
     drawn (`OnDrawListener` -> post), calls `requestDismissKeyguard(this, cb)` while the cover is on and
     `isKeyguardLocked`.
   - **The state check:** 1 s after each request, `isKeyguardLocked` is read, callback or not. Still locked counts
     one failure. The callbacks are only logged, because KVM `dismiss` `:2576-2583` answers nothing while the
     keyguard is going away, and callbacks die with the activity.
   - **Re-checks:** at every interactive lock resume, and once a second while the lock is resumed, the screen on
     and the keyguard locked - until it unlocks or the fallback runs. This catches a SystemUI restart that shows it
     again and a late keyguard alarm.
   - **The fallback:** at the 2nd consecutive failure, `setKeyguardDisabled(true)`, which dismisses. A success
     resets the count.
   - **Re-arming** while awake: at the next interactive lock resume after the fallback, with no wake pending, the
     cover is written again (`setKeyguardDisabled(false)`), so it is in place before the next sleep. Never in
     `screenOffWork`, which is too late for that sleep (M7.4).
   - Pure: `dismissDue(coverOn, resumed, interactive, keyguardLocked, drawnSinceResume, triedThisResume)`,
     `dismissFailed(requestedAtMs, nowMs, keyguardLocked)`, `dismissFallback(failures) = failures >= 2`,
     `coverRearmDue(fallbackActive, interactive, lockResumed, wakePending)`.
7. **Reporting.**
   - Logcat at minimum:
     - `KeyguardCover: mode=<COVER|DISABLED|UNTOUCHED> features=<hex> maxTimeToLock=<ms> disabled=<bool>`;
     - `KeyguardCover: dismissal failed (<state|callback>), n=<count>`;
     - `KeyguardCover: fallback`;
     - `KeyguardCover: re-armed`.
   - Status `lock_state.keyguard_cover` (`on|off|failed|fallback`) and `keyguard_dismiss_failures` are the
     implementer's choice. If added: server and launcher in one commit, `PolicyResponseCompatTest` is unaffected
     (status, not policy).
8. **The crash guard** (L8): `guardTripped` sets `keyguardMode` DISABLED at once (one `setKeyguardDisabled(true)`
   call, on a background thread), so a tripped guard leaves no Swipe keyguard without a handy lock.
9. **`USER_PRESENT` and sounds** (L4).
   - Under the cover `USER_PRESENT` arrives after the dismissal (KVM `onKeyguardExitFinished` `:3517`), so
     `screenReceiver`'s non-`SCREEN_OFF` branch runs a second time over a drawn lock.
   - Its `TimeRulesRuntime.recheck` start now goes through item 5's wait, and `ScreenOn` finds the lock resumed.
   - §11 checks for no `LockActivity` start and no re-front after the dismissal, and for the lock click and the
     unlock sound (KVM `:1845`, `:3504`; `LOCKSCREEN_SOUNDS_ENABLED` isn't DO-writable). If they play, decide with
     the user before step 3.
10. **Usage doc:** `docs/testing/emulator.md` §6k "Keyguard cover (design 32 step 2)".
    - The broadcast and its extras, the `dumpsys` checks, the frame-stepping rule.
    - The warning (L5): a 16d lock-task override that clears `LOCK_TASK_FEATURE_KEYGUARD` makes
      `LockTaskController` disable the keyguard in lock task (`setKeyguardState:926-960`), which silently turns the
      cover off. Don't combine the two hooks.

### Step 3 - After the device checks: the cover becomes the release behaviour

- Only after §11's step 2 checks pass on the emulator **and** the Jelly Star.
- `KeyguardCoverDebug` and its receiver are removed. `keyguardMode`'s COVER no longer needs the switch.
- Decide whether it gets a server switch: a `keyguard_cover` policy field, default on, as an off switch for an
  OEM that misbehaves. If yes, policy JSON on both sides in one commit, `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`.
- Design 10's "no Android lock screen" rule is updated in `docs/design/10-lock-and-call-ui.md` and
  `launcher/CLAUDE.md`:
  - the screen lock is Swipe, a bare cover across the sleep;
  - the lock delay is 0 by max-time-to-lock;
  - it is dismissed by handy's lock after its first frame;
  - `setKeyguardDisabled(true)` is the fallback and the state across a clean reboot.
- The keyguard state becomes part of the status report, if step 2 kept it to logcat.
- The lock sounds decision (item 9) is made.

## 9. Tests

**Step 1, pure** (`LockUpPlanTest`):
- `lockUpStarts`:
  - `showLock=false` -> NONE;
  - `interactive` -> LOCK (L1);
  - a live call (ours, Telecom, VoIP) -> LOCK;
  - `lockOnTop` -> NONE;
  - otherwise HOME_THEN_LOCK;
  - unknown -> HOME_THEN_LOCK.
- `lockOnTopAfter(event, interactive, sameLockUp)`:
  - set by `lockUp`;
  - kept by a same-lock-up start (the `ScreenOn` backstop without an interactive pause) and by a
    non-interactive pause or stop;
  - cleared by an interactive pause or stop, destroy, UNLOCKED, and a re-front / `TimeRuleShown` / `CallsEnded` /
    VoIP / process-start start.
- `homeDarkResume`: only with `darkLockUp`, `!interactive` and LOCKED.
- `lockLeave(kioskOn = false, callLive = false)` -> `homeFirst = true`; `callLive = true` -> false; kiosk on
  unchanged (`LockTaskRootTest`).
- `PinLockStateTest`'s `ScreenOff` cases are unchanged.

**Step 1, source pins** (L6):
- `lockUp` is the only place that calls `HomeFront.bring` on a lock-up, and it calls it before `show`, with
  `animate = false`.
- `HomeFront`: `animate = false` adds `FLAG_ACTIVITY_NO_ANIMATION`; the intent stays typed HOME (`HomeFrontTest`).
- `show` clears `lockOnTop` unless called from `lockUp`.
- `HomeActivity.onResume` has the `homeDarkResume` branch first, and that branch calls only the night ground
  (qa-32-s1-code L1). `onStart` skips its renders on the same condition.
- `PinLockTheme`'s four items are `@android:color/black`.
- `PinLockActivity.onCreate` and `HomeActivity.onCreate` each call `setTaskDescription` with black background, status
  and navigation bar colours. Home calls `setRecentsScreenshotEnabled(false)`.
- `PinLockActivity` keeps `FLAG_SECURE` before `setContentView` and `showWhenLocked`, and has no `turnScreenOn` /
  `setTurnScreenOn` (`PinLockManifestTest`).
- No `TYPE_APPLICATION_OVERLAY`, `SYSTEM_ALERT_WINDOW` or `AccessibilityService` in the launcher.
- No "drawn before the next screen-on" in `lock/`.
- `LockUpDebug` is only in `src/debug`, banned by name in `DebugHookAbsentTest`.

**Step 2, pure:**
- `keyguardMode`:
  - COVER only with the switch on, managed, a PIN, no credential, VoIP NONE and the guard not tripped;
  - otherwise DISABLED or UNTOUCHED as today.
- The COVER attempt (a fake DPM): a features write that throws or reads back wrong gives DISABLED **with the lock
  active** (H2). So do a max-time-to-lock failure and a `setKeyguardDisabled(false)` failure. On success the order
  of the calls is features, read-back, T, read-back, then `false`.
- `keyguardDisabledFeatures` (M6):
  - on: ALL, `coverAdded` = the missing bits;
  - off: exactly `coverAdded` removed, a parent's bits kept, SECURE_CAMERA by the managed rule;
  - unmanaged: as today, minus `coverAdded`.
- `maxTimeToLock` and `timeoutWriteOrder` (H1):
  - T equals the effective timeout in Enforce and Release;
  - growing writes the setting first, shrinking writes T first;
  - cover off gives 0.
- `dismissDue`, `dismissFailed` (state-based, with and without a callback), `dismissFallback`, `coverRearmDue`
  (M7).
- `sendAfterUnlock` (M5).
- `refrontAction`'s `DarkLockUp` row: dark, LOCKED, not on top, nothing exempt (M5).

**Step 2, source pins:**
- `KeyguardCoverDebugReceiver` and `keyguard_cover_debug` are only in `src/debug`, banned by those exact strings.
- The release `KeyguardCoverDebug` body is exactly `= false`, and `src/main` reads only its cached value (L5).
- `setKeyguardDisabled`, `setKeyguardDisabledFeatures` and `setMaximumTimeToLock` are called only from the one
  keyguard writer. The exceptions are the `ACTION_SHUTDOWN` call (M4) and `guardTripped` (L8), which both route
  through that writer's `disableNow()`.
- `requestDismissKeyguard` is called only from `PinLockActivity` and `HomeActivity`'s boot path.
- `showCall`, `showVoipCall`, the FSI send and the screen-on time-rule start go through `sendAfterUnlock`.
- `InCallActivity` and `VoipWakeActivity` stay `showWhenLocked`.

## 10. Decisions (user, 2026-10-13, final)

1. **Both stages.** Step 1 (Home under the lock, the black stand-in, no needless start) is built now. The Swipe
   keyguard cover follows as a device experiment behind a debug switch (step 2) and is made permanent (step 3)
   only if it holds on the emulator and the Jelly Star.
2. **Unlocking lands on Home in every mode**, kiosk off included. So Home under the lock is wanted as defence in
   depth. Refined after QA (L2): never over a live call.
3. **The stand-in frame is pure black** - the orchestrator's call, since it matches "lock or black". `kid_ground`
   stays the lock's painted ground. Every colour source of the stand-in is black (M3), the system bars over the
   drawn lock included.
4. **At boot, Home dismisses the keyguard**, so the breathing mark (16e) still shows - the orchestrator's call.
   Refined after QA (M4): a clean reboot has no keyguard at all (off at `ACTION_SHUTDOWN`). Home's dismissal is the
   backstop for a crash or power-loss reboot.
5. **The QA findings** (`qa-32-design.md`, 2 High, 7 Medium, 9 Low) are all accepted - the orchestrator's call.
   - H1: `setMaximumTimeToLock` equal to the screen timeout, following the setting.
   - H2: features first, read back, fall back to today's disabled keyguard, never the lock off.
   - M4: QA's boot fix.
   - M6: the cover clears exactly its own bits, with one writer.

Open for step 3: whether the cover gets a server switch, and whether the lock sounds (L4) need anything.

## 11. Device checks

**The frame-stepping rule** (every check below that records):
```
adb shell screenrecord --bugreport --bit-rate 8000000 /sdcard/flash.mp4   # stamps time + frame number
adb pull /sdcard/flash.mp4 && ffmpeg -i flash.mp4 -vsync 0 frames/%05d.png
```
- Step through every frame from the first lit one after each power-on to the lock's first frame.
- **A run fails if any frame shows:**
  - app content;
  - an older status-bar time than the live one (a snapshot);
  - Home's grid;
  - SystemUI's lockscreen.
- Black frames, with or without the live status bar, pass. In step 1, a plain frame of our own (a black stand-in)
  passes too.
- The emulator's own recorder captures the secure lock; `screenrecord` on a phone shows it black. When in doubt, use
  the §5 Perfetto layer trace: at the first lit frame, no layer of another package, Home's content or SystemUI's
  keyguard view may be visible.
- On the Jelly Star, add a phone camera at 240 fps on the panel.

**The modes** (each with 10 cycles of power off, wait 3 s, power on):
- kiosk on, from an allowed app;
- install mode, with Play in front;
- the pause, with Play in front (from the drawer);
- the override;
- a time rule;
- our call;
- a Telecom/system-dialer call (emergency test number, or our dialer role taken away);
- a VoIP call (ring from dark, and in a call);
- an alarm.

**The wake patterns** (each mode):
- (a) the power key;
- (b) **the screen timeout, then power within 1-4 s, 20 times** (H1) - the user's own case;
- (c) the screen timeout on the lock itself, then power;
- (d) quick presses: off and on within ~100 ms, 20 times;
- (e) mashing: 5 presses = SOS still reachable.

**Step 1** (emulator, then the Jelly Star release build):
- [ ] The rule in every mode and pattern. Step 1 does not promise the strict requirement (§12). Record whether any
  non-black frame is left, which one, and attach the §5 trace if so - that decides how urgent step 2 is.
- [ ] Logcat:
  - `Lock-up at screen-off: HOME_THEN_LOCK` after an unlock or a yield;
  - `NONE` at a screen-off with the lock already on top (best effort, M1);
  - `LOCK (interactive=true)` when `SCREEN_OFF` arrives after a quick wake (L1);
  - no `ActivityTaskManager: START ... PinLockActivity ... result code=3` in the common case.
- [ ] While dark after a lock-up: Home's `onResume` logs the dark branch, and no `LockActivity` start or second lock
  start follows (M1).
- [ ] The Shell transition trace at the wake has no animated `TRANSIT_OPEN`/`TO_FRONT` for Home's task (M2).
- [ ] `dumpsys activity activities` after a lock-up: the lock's task, then Home's task with `HomeActivity` as its top
  (not `LockActivity`, also during a time rule, L7), then the app.
- [ ] During a call (ours, Telecom, VoIP), the power key: no Home over the call screen. After the hang-up, the lock.
  An unlock during a live call doesn't bring Home over it (L2).
- [ ] Unlock lands on Home with the kiosk on, the kiosk off (pause, override) and install mode. A glance row's
  target still opens.
- [ ] Recents after the unlock: no `BlockedAppActivity` (16d); `smoke-test.sh` green.
- [ ] The lock's stand-in, splash and bars are black (frame-step a cold lock-up after an unlock, M3).
- [ ] VoIP ring from dark, alarm from dark, SOS: unchanged.
- [ ] (qa-32-s1-code M1) Our dialer role not held, a system-dialer call from dark, left in the dialer's own UI after
  the hang-up; then power off and on: the log says `HOME_THEN_LOCK`, not `NONE`.
- [ ] (qa-32-s1-code L1) Kiosk on, lock task stopped (`adb shell am task lock stop`, or the 16d override), a lock-up
  while dark: at the wake at most Home's black stand-in, then the lock (the lock's `START_HOME` roots lock task).
- [ ] Expected visible changes, not regressions (qa-32-s1-code L5):
  - Home's black app-theme snapshot is also its starting window whenever Home comes to the front interactively:
    every unlock, kiosk on and off - a black frame where Home's old screenshot was.
  - On Android 13 (the Jelly Star) the theme's black `statusBarColor`/`navigationBarColor` paint black strips above
    and below the drawn lock (decision 3). With `targetSdk 36` on Android 15+ the window's bar colours are ignored
    (edge-to-edge), so the emulator doesn't show them.
  - During a time rule the time-rule screen is gone after a dark lock-up and comes back at the unlock (§14).
- [ ] H2 experiment: with `LockUpDebug`'s `skipRestart` on, compare the flash with and without the `result=3`
  start.

**Step 2** (debug build, emulator and the Jelly Star, the switch on):
- [ ] The broadcast logs `mode=COVER features=<ALL> maxTimeToLock=<T> disabled=false`. Settings > Security shows
  Swipe. `dumpsys lock_settings` shows no credential. `dumpsys device_policy` shows `maximumTimeToLock` equal to
  `settings get system screen_off_timeout`.
- [ ] Change the parent's "turn off after" in the PWA (30 s -> 2 min -> 15 s), then start the pause (Release) and
  change the phone's own timeout in Settings. After each change, T equals the setting, and wake pattern (b) passes
  (H1).
- [ ] Right after a timeout screen-off: `adb shell dumpsys window | grep -i -E "keyguard|occlud"` shows
  `showing=true occluded=true` within 1 s (not 5 s later). `dumpsys activity activities` shows every task but the
  lock's `visibleRequested=false`.
- [ ] **The rule, every mode, every pattern: no failing frame**, except the residual cases §12 lists, and those
  are each counted and reported.
- [ ] After the lock's first frame the keyguard is gone (`mKeyguardShowing=false`, log `dismissed`). Then, as
  today:
  - Recents (16d);
  - a VoIP call screen over the lock (sent only after the unlock, M5);
  - the time-rule screen after an unlock;
  - the emergency dialer;
  - the alarm;
  - SOS;
  - the camera double-press (nothing opens).
- [ ] A VoIP call in progress, then power off/on: the call screen, no SystemUI lockscreen (the cover is DISABLED in
  the call). The call ends while dark: the cover comes back at the next interactive lock resume.
- [ ] A system-dialer call that ends while dark, then wait 3 s and power on: black, then the lock (the dark
  lock-up, M5).
- [ ] Our call: power off/on during it - black, the lock, then the call screen (after the dismissal).
- [ ] No "probably Emergency SOS" line and no lock pause or stop after a dismissal, over 20 wakes with the kiosk on
  (L9).
- [ ] After the dismissal: no `LockActivity` start and no lock re-front from the late `USER_PRESENT` (L4). Note
  whether the lock click and the unlock sound play.
- [ ] Dismissal failure with `--ez failDismiss true`:
  - the state check counts it (also with no callback);
  - the 2nd failure runs the fallback (`setKeyguardDisabled(true)`);
  - the next interactive lock resume re-arms the cover, and the next sleep is covered (M7).
- [ ] A cover that can't be set (`dpm` refusal, e.g. after an update without the policy granted): the lock stays
  active, `mode=DISABLED`, and the failure is reported (H2).
- [ ] Switch off: Settings shows None, the keyguard features are back to what they were before the cover (a parent's
  bits kept), and `maximumTimeToLock` is 0 (M6).
- [ ] Boot via the power menu (`ACTION_SHUTDOWN`): the boot animation, then the 16b cover or Home's mark for 3 s,
  then the lock, with **no SystemUI lockscreen frame**. The cover is re-armed after the first interactive lock
  resume (M4). `smoke-test.sh REBOOT=1` green.
- [ ] Boot via `adb reboot` (no shutdown broadcast): SystemUI's Swipe screen shows until Home dismisses it - the
  accepted residual. Home's dismissal succeeds or is retried by state; no false failure count.
- [ ] A crash-guard trip (debug crash loop): the keyguard goes DISABLED at once (L8).
- [ ] The Jelly Star: note the Android version and its SystemUI's lock-after-timeout and notification behaviour.
  On Android 13 (legacy transitions) repeat patterns (b) and (d) carefully.

**Step 3** (release build, after the step 2 checks pass on both devices):
- [ ] The full step 2 list again on the release build, without the switch.
- [ ] An update from a step 1 build: the cover comes on at the first apply, without a reboot.
- [ ] If a server switch is added: off restores step 1's behaviour at the next sync (features and T restored), on
  brings the cover back.
- [ ] A phone with an Android PIN set (`lock_state.inactive = android_credential`): no cover, no double lock, the
  keyguard features as before the cover.

## 12. Step 2 against "lock or black", mode by mode

**How the guarantee works.** After the H1/M4/M5 fixes, the guarantee holds whenever the keyguard is locked and our
lock is the top task before the wake:
- nothing that isn't `showWhenLocked` is visible-requested;
- the lock's stand-in is black;
- an occluded keyguard draws nothing.

It fails only where one of those two conditions doesn't hold yet at the wake.

| Mode / pattern | Holds? | Why, and the residual |
| --- | --- | --- |
| Power key, any app in front (kiosk on, install mode, pause, override) | **yes**, except a quick wake | SystemUI locks at once for an insecure keyguard (`lockImmediately`). **Residual:** a wake before our lock is the top task - `SCREEN_OFF` delivered late (L1) or a slow main thread - shows SystemUI's bare lockscreen (un-occluded) until the lock comes up. Never app content |
| Screen timeout, any app in front | **yes** with H1 | the lock delay is 0 by max-time-to-lock. Same quick-wake residual as above |
| Screen timeout with the lock in front (the user's case) | **yes** | the lock is the top and `showWhenLocked` before the sleep, so the keyguard is occluded from its first moment. No race |
| Quick presses / mashing | **yes**, except the quick-wake residual | as the power key. SOS is `showWhenLocked` and exempt by design |
| Boot, clean (power menu, OTA) | n/a by decision 4 | no keyguard at all (M4). The boot animation, then our own boot cover or Home's mark, then the lock - our own screens, no other app is running |
| Boot after a crash / power loss / `adb reboot` | **no** | **Residual:** SystemUI's Swipe lockscreen from the end of the boot animation until Home dismisses it (M4). No app content: nothing else runs yet |
| Our call | **yes** | `InCallActivity` and the lock are `showWhenLocked`. The call screen is sent only after the dismissal (M5) |
| Telecom / system-dialer call in progress | exempt by design | the lock never covers it. Its in-call UI is `showWhenLocked` and is the intended front |
| Telecom call ending while dark | **yes** after ≤ 2 s | the dark lock-up (M5). **Residual:** a wake within the 2 s re-check shows SystemUI's lockscreen (un-occluded) before the lock |
| VoIP ring from dark | **yes** | the cover stays during RINGING; the lock and `VoipWakeActivity` are `showWhenLocked`; the FSI is sent after the dismissal |
| VoIP call in progress | exempt by design | the cover is DISABLED in the call (M5); the call screen is the intended front |
| VoIP call ending while dark | **no** (step 1 only) | the cover is re-armed only while awake. **Residual:** that one wake has today's mechanism. Step 1's Home stand-in (black) is what lies under the lock if the reveal path is the task below (not proven, §5) |
| Time-rule lock | **yes** | `LockActivity` isn't `showWhenLocked`, so it is hidden. Its screen-on start waits for the unlock (M5), and Home's dark resume doesn't start it (M1) |
| Alarm, SOS, emergency dialer | exempt by design | their screens are `showWhenLocked` and intended |
| Cover fallback active (2 failures) | **no** until re-armed | today's mechanism plus step 1 for the sleeps until the next interactive lock resume (M7). Reported |
| Crash guard tripped | n/a | no handy lock; keyguard DISABLED (L8) |

**In short:**
- Step 2 meets "lock or black" in every ordinary wake.
- Its honest residuals:
  - a wake that beats our lock to the top (SystemUI's bare lockscreen, never an app);
  - a non-clean reboot (SystemUI's Swipe screen);
  - the first wake after a VoIP call that ended in the dark;
  - the sleeps while the dismissal fallback is active.
- Only the VoIP-end and fallback cases can still show app content, and only if step 1's Home stand-in doesn't
  catch it.

**Step 1's own residuals** (qa-32-s1-code M1, L5; step 1 promises no strict "lock or black"):
- **A quick press.** When the screen is back on before Home's resume transaction runs, Home takes its usual locked
  resume over a lock that is already started: the update fence, possibly `LockActivity` (then `TimeRuleShown`
  re-fronts the lock) and `show(BOOT)`, which clears `lockOnTop`. All ours, a few milliseconds.
- **A cover in the instant of the screen-off.** An activity that comes over a *resumed* lock in the milliseconds
  after `isInteractive` flipped (Recents, the emergency dialer, an app's full-screen intent) has its pause taken for
  the sleep's own, so `lockOnTop` stays true and the next screen-off may start nothing. The wake's `ScreenOn`
  backstop still starts the lock (the lock isn't resumed), so it is bounded by the backstop; best effort, §8 4.

## 13. Design QA (`qa-32-design.md`, 2026-10-13) - the change for each finding

| # | Finding | Change in this doc |
| --- | --- | --- |
| H1 | No keyguard for 5 s after a screen timeout | Step 2 item 3: `setMaximumTimeToLock` = the effective screen timeout, following `ScreenTimeout` in Enforce and Release, written in a safe order, `<force-lock/>` declared. §11 wake pattern (b), T-tracking check. §12 |
| H2 | A cover that can't be set switches the lock off | Step 2 item 2: features first, read back, T, then `setKeyguardDisabled(false)`. Any failure means today's disabled keyguard and an active lock. `KEYGUARD_NOT_DISABLED` unchanged; the failed cover reported separately. Pure test |
| M1 | Home's resume re-starts the lock / `LockActivity` and runs main-thread work first | Step 1 items 3-4: `darkLockUp` mark, Home's dark-resume branch (night ground + `reconcileKioskMode` only, `onStart` skipped), `lockOnTop` not cleared within a lock-up, best effort with its own debug toggle. Pinned |
| M2 | Home's start animates | Step 1 item 2: `HomeFront.bring(..., animate = false)` adds `NO_ANIMATION` on the lock-up path. Pin and §11 transition-trace check |
| M3 | "Black" covered one of four colour sources | Step 1 item 5: four theme items plus an explicit black `TaskDescription` (background, status and navigation bar) for the lock and Home. Pins |
| M4 | Boot shows SystemUI's lockscreen | Step 2 item 4: keyguard off at `ACTION_SHUTDOWN`, re-armed at the first interactive lock resume. Home's dismissal is the backstop, not a false failure. The non-clean reboot is a listed residual |
| M5 | VoIP calls under SystemUI's lockscreen | Step 2 item 5: DISABLED while VoIP isn't NONE (written only while awake). Every other app's screen sent from the lock (and the screen-on time-rule start) waits for the unlock. Added from the re-check: the dark lock-up after a system call |
| M6 | Features never restored, two writers | Step 2 item 2: one writer; `keyguardDisabledFeatures(current, managed, cover, coverAdded)` clears exactly the cover's bits, kept in CE prefs. Pure tests |
| M7 | The counter can't see the failures that matter; late re-arm | Step 2 item 6: state-based check 1 s after each request, re-checks each resume and every second while locked, fallback at 2, re-arm while awake (never in `screenOffWork`) |
| L1 | `SCREEN_OFF` after the next wake | Step 1 item 1: `lockUpStarts(..., interactive)` -> LOCK. Test row, §11 log check |
| L2 | `homeFirst` over a live call | Step 1 item 6: `homeFirst = kioskOn || !callLive`. KDoc, header and test renamed |
| L3 | Screen-on gate misstated | §0, §3.4, §3.6, §7.2, §7.4 corrected: the occluded cover has no gate; the guarantee rests on visibility. The notification question closed (NLUM), the Jelly Star left as a device check |
| L4 | `USER_PRESENT` moves; lock sounds | Step 2 item 9 and §11 checks; the sounds decision before step 3 |
| L5 | Debug-hook details | Step 2 item 1: exact banned strings, the release body pinned, the cached read, the §6k warning about 16d's `KEYGUARD` override |
| L6 | Tests that wouldn't catch a regression | Step 1 item 1's `lockUp(start)`, `show` clearing the flag itself, and the pins listed in §9 |
| L7 | Home is never a Recents card | §11 step 1: replaced by `dumpsys activity activities` (Home's task top) and the layer trace |
| L8 | Crash guard with the cover on | Step 2 item 8: `guardTripped` sets DISABLED at once. §11 check |
| L9 | The SOS heuristic and the dismissal | §11 step 2 check: no SOS mark and no lock pause or stop after a dismissal |

## 14. Step 1 implementation status (2026-10-13, branch `design-32-s1`)

Branch `design-32-s1` on `8b8d418d`, with `main` merged in (no conflicts). Not pushed. Nothing was run on a device or
the emulator. Step 2 and step 3 are not built: the keyguard stays disabled exactly as before.

- **Pure plan** `lock/LockUpPlan.kt`: `LockUpStart`, `lockUpStarts(showLock, lockOnTop, callLive, interactive)` (rows
  in the order of §8 1.1: no lock -> NONE, `interactive` -> LOCK, a live call -> LOCK, `lockOnTop` -> NONE, else
  HOME_THEN_LOCK), `LockTopEvent` and `lockOnTopAfter(current, event, interactive, sameLockUp)`, and
  `homeDarkResume(darkLockUp, interactive, locked)`.
- **Runtime** (`PinLockRuntime`):
  - `dispatch` routes a `ScreenOff` event's starts through `lockUpAtScreenOff` -> `lockUp(start)`; every other event's
    `showLock` goes to `show`, the `ScreenOn` backstop with `sameLockUp = true`. `callLive` is the `ScreenOff`
    event's own reads (`ourCall || systemCall || voip != NONE` = ours, Telecom's or VoIP), so no binder call is
    added before the start except `isInteractive`.
  - `lockUp(HOME_THEN_LOCK)` sets `darkLockUp`, then `HomeFront.bring(..., animate = false)`, then `show(...,
    fromLockUp = true)`; a started lock sets `lockOnTop`. The log line is §8 item 8's, after the starts, only while
    LOCKED.
  - `show` clears `lockOnTop` itself unless `fromLockUp`; with `sameLockUp` it keeps it (an interactive pause before
    has cleared it already). The lock's pause and stop apply `lockOnTopAfter` with `isInteractive` (no context yet
    counts as interactive); `onLockDestroyed` (from `PinLockActivity.onDestroy`) and the UNLOCKED/DISABLED edge
    clear it.
  - `darkLockUp` is cleared by an interactive resume of the lock (`onLockResumed`) or Home
    (`onHomeResumed(interactive)`), and - an addition - by **every other Home start** (`HomeFront.bring` with
    `animate = true` calls `endDarkLockUp()`): the lock's own `ensureLockTask` START_HOME, an update or a relock
    while dark then still gets Home's usual locked resume, which shows the lock again. Without it, such a start
    within the same dark spell would have left Home over the lock until the wake's backstop. Only the lock-up
    passes `animate = false` (pinned).
  - `callLiveNow(context)` for the leave.
- **Home** (`HomeActivity`): `onResume`'s first branch (after `resumedNow`) is `darkUnderLock()` -> `showNight()`,
  return (no `reconcileKioskMode()` since the fix round, L1). `onStart` returns on the same condition; its work moved to `startWork()`
  (idempotent per start through `started`), which `onResume` also calls, so a dark start's renders and listeners come
  at the next interactive start or resume. `onCreate`: `setRecentsScreenshotEnabled(false)` and the black
  `TaskDescription`. The live bars and the 16e launch splash are unchanged.
- **The black stand-in**: `PinLockTheme`'s `windowBackground`, `windowSplashScreenBackground`, `statusBarColor` and
  `navigationBarColor` are `@android:color/black`; `PinLockActivity.onCreate` sets the black `TaskDescription` after
  `FLAG_SECURE`, before `setContentView`. The lock's layout roots keep `kid_ground` and its painted ground (25f).
- **The leave**: `lockLeave(kioskOn, startedLockTask, lockTaskRunning, callLive)`, kiosk off `homeFirst = !callLive`
  (`PinLockActivity.leave` passes `PinLockRuntime.callLiveNow`; `stopOwnLockTask` passes `false`, the stop doesn't
  depend on it). `LockTaskRoot.kt`'s header and KDoc, the lock's KDoc and `LockTaskRootTest`'s kiosk-off case
  (renamed) follow. An addition the spec didn't name: the glance opener's send check (design 25, code QA Low 2)
  treated "kiosk on" as "Home is below the lock"; it now gets the leave's `homeFirst`
  (`GlanceOpener.open(activity, pending, homeBelow)`), so with the kiosk off a refused send is still caught and an
  opened row marked. `glanceSendCheck` itself is unchanged.
- **Debug toggle (H2)**: `src/debug`'s `lock/LockUpDebugReceiver` (exported, DUMP) and `LockUpDebug`
  (`skipRestart`, memory only, default `true` = the release plan). `adb shell am broadcast -n
  $P/com.kidslauncher.mdm.lock.LockUpDebugReceiver --ez skipRestart false` makes a lock-up re-start a lock on top as
  before design 32 (LOCK instead of NONE), `true` skips it again, no extra only reports. `src/release`'s
  `LockUpDebug.adjust(start, showLock) = start`. Interpretation of §9's "`LockUpDebug` ... banned by name": the
  release twin carries the object's name (as `LockTaskDebug`, and L5 for step 2), so the banned string is
  `LockUpDebugReceiver` - in `CheckDebugHook`'s names and `DebugHookAbsentTest`.
- **Comments**: `PinLockRuntime`'s screen-off comment, `PinLockState`'s `ScreenOff` comment and
  `SCREEN_OFF_WORK_DELAY_MS`'s KDoc (`RefrontPolicy.kt`, "drawn before a quick screen-on") no longer claim the lock
  draws before the screen-on. Design 10 §3 points here; launcher `CLAUDE.md` has the "Lock flash (design 32)"
  bullet, and its 25f ("the starting window stays `kid_ground`") and design 16 ("Kiosk off is unchanged") lines
  are updated.
- **Tests**: new `LockUpPlanTest` (§9's pure rows and the source pins: the routing, `lockUp`'s order and
  `animate = false`, the only Home start in the runtime, `show`'s clear, the pause/stop/destroy/unlock wiring, Home's
  dark branch and `onStart`, the four theme items and both task descriptions, no overlay/accessibility, no "drawn
  before" comment). `HomeFrontTest` (NO_ANIMATION, still typed HOME, `endDarkLockUp` before the start, the lock-up is
  the only unanimated caller), `PinLockManifestTest` (`showWhenLocked`, no turn-screen-on, `FLAG_SECURE` before
  `setContentView`), `DebugHookAbsentTest` (the receiver, the release pass-through), `LockTaskRootTest`,
  `LockGlanceGuardTest`, and the dispatch-order pins in `BootMarkPlanTest` and `HomeGateTest`. `PinLockStateTest`'s
  `ScreenOff` cases are unchanged. `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` (the
  release build with the throwaway tsnet stub): green, 1090 unit tests (15 in `LockUpPlanTest`), 0 failures;
  `checkReleaseHasNoDebugHook` and the debug positive control pass.
- **The time-rule screen at a dark lock-up** (qa-32-s1-code L5): `HomeActivity` is `singleTask` and `LockActivity`
  (default affinity) lives in Home's task above it, so the lock-up's Home start clears it (singleTask clear-top).
  That is why §11's "Home's task top is `HomeActivity`, also during a time rule" holds, and it keeps the time-rule
  screen's real screenshot out of the stand-in. It comes back at the unlock (`afterUnlock` -> `LockActivity.start`)
  or at Home's next interactive resume (`redirectToLockScreenIfLocked`, e.g. after a call that skipped
  `afterUnlock`'s start).
- **Not device-tested**: §11's step 1 list.

### Code QA fix round (`qa-32-s1-code.md`, 0 High, 1 Medium, 5 Low, all fixed)

- **M1:** `LockTopEvent.SCREEN_ON_UNSHOWN` (-> false) and pure `screenOnUnshown(event, showLock)`: a `ScreenOn` that
  finds the lock not resumed and starts nothing clears `lockOnTop` in `dispatch`. Every way another window comes
  over a sleep-stopped lock has a test row: a system-dialer call (cleared here), VoIP in call or ringing (cleared
  here; the ring's own start too), our call / an alarm / another turn-screen-on activity (the backstop starts the
  lock; the call screen then pauses it interactively), the time-rule screen (`TimeRuleShown`'s start), another Home
  start (Home's locked resume's start). The racy cover at the screen-off is listed in §12 as a residual.
- **L1:** Home's dark branch runs only `showNight()`; comment, §8 3, §9 and this section corrected. Chosen over an
  unanimated `START_HOME` while dark: the dropped call was a no-op or a caught throw at run time, so removing it
  changes nothing on the phone, while a second kind of dark Home start would tie design 16's lock-task rooting to
  the dark mark for a rare case (kiosk on, nothing rooting lock task) that behaves as before design 32. §11 has the
  check.
- **L2:** an animated `HomeFront.bring` from another thread posts itself to the main thread (end the mark, start),
  so it is ordered against `lockUp`'s mark-and-starts; posted, it answers `true` (only the boot's caller reads the
  answer, and it runs on the main thread). Pinned in `HomeFrontTest`.
- **L3:** pins for the leave's `callLiveNow(this)`, `callLiveNow`'s three terms, the glance's `homeBelow` through
  `open` -> `run` -> `watchSend`'s `confirmable`, `interactiveOrUnknown`'s `?: true` and Home's
  `interactive()` (`!= false`). QA's batch-A mutations each go red (the `Fixes` table in `qa-32-s1-code.md`).
- **L4:** `show` returns `started`, true only when the lock's `startActivity` didn't throw; `lockUp` sets
  `lockOnTop` only then. Pinned.
- **L5:** this section's time-rule note, §11's expected visible changes and §12's step 1 residuals; the launcher
  `CLAUDE.md` bullet.
- `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` (release with the throwaway tsnet stub,
  removed afterwards): green, 1093 unit tests (18 in `LockUpPlanTest`), 0 failures; `checkReleaseHasNoDebugHook`
  passes. Not device-tested.

## 15. Step 2 implementation status (2026-10-13, branch `design-32-s2`)

Branch `design-32-s2` from `main` at `a5d30b92` (step 1 merged). Not pushed, and not run on a device or the emulator.
The release build writes the keyguard exactly as after step 1: the release `KeyguardCoverDebug.enabled` is `false`,
so the cover is never COVER. Two step 1 defects from the user's device evidence are also fixed here (the double press
and the backstop's re-start, below), in every build.

- **The switch** (L5): `src/debug`'s `lock/KeyguardCoverDebugReceiver` (exported, DUMP) and `KeyguardCoverDebug`
  (CE prefs `keyguard_cover_debug`). `--ez on true|false` stores the switch and runs a full apply. `--ez failDismiss
  true` / `--ez skipDismiss true` set the dismissal knob, which is memory only and lives in main
  (`KeyguardCover.dismissTest`, which only the receiver sets). It reports
  `on|off -> mode=... features=... maxTimeToLock=... disabled=... armed=... dismissTest=...`. `src/release`'s
  `KeyguardCoverDebug` has one `fun enabled(context: Context): Boolean = false`.
  - `KeyguardCover` is its only caller: from its apply (`configure`), and once off the main thread at the process
    start (`loadSwitch`, from `PinLockRuntime.init`'s IO pass) for Home's boot dismissal.
  - `CheckDebugHook` and `DebugHookAbsentTest` ban `KeyguardCoverDebugReceiver` and `keyguard_cover_debug`.
  - Usage: `docs/testing/emulator.md` §6k.
- **Pure** (`lock/KeyguardCoverPlan.kt`, `KeyguardCoverPlanTest`):
  - `keyguardMode`, `keyguardDisabledFeatures(current, managed, cover, coverAdded)`, `maxTimeToLock`,
    `timeoutWriteOrder`;
  - `writeKeyguard(policy, wanted, managed, coverAdded, timeoutMs, storeCoverAdded)`: the whole write order and
    its fallback, against the `KeyguardPolicy` interface (a fake DPM in the test);
  - `dismissDue`, `dismissFailed`, `dismissFallback`, `coverRearmDue`, `sendAfterUnlock`, `homeBootDismissDue`;
  - `RefrontAction.DarkLockUp`.
  - The B2 camera rule stays `server/OwnPermissions.kt`'s `keyguardDisabledFeatures(current, managed)`, applied after
    the cover's bits are taken out.
- **One writer** (`lock/KeyguardCover`, H2, M6). `setKeyguardDisabled`, `setKeyguardDisabledFeatures` and
  `setMaximumTimeToLock` appear only there (pinned). It is reached from four places:
  - `PinLockRuntime.configure`, every apply. `AppEnforcer.applyKeyguardFeatures` is gone, and the crash guard's
    re-arm now comes before the keyguard write;
  - its own `rewrite`, on a single writer thread: the VoIP edge, the re-arm, the fallback;
  - `followTimeout` / `timeoutChanged`, from `ScreenTimeout`;
  - `disableNow`: `ACTION_SHUTDOWN` from `BootCover`'s receiver (synchronously), and the crash guard. Both act only
    while the cover is on.
  - COVER writes the features (`coverAdded` committed first to prefs `keyguard_cover`), reads them back (ALL), then
    T, read back, then `setKeyguardDisabled(false)`. Any failure means DISABLED: `true` first, then the cover's bits
    out and T 0, with `lockMayRun` (the `keyguardDisabled` input of `lockActivation`) true. `KEYGUARD_NOT_DISABLED`
    keeps its meaning.
  - DISABLED and UNTOUCHED write what step 1 wrote: `setKeyguardDisabled(want)`, and the features by B2's rule. Plus
    one `getMaximumTimeToLock` read, which clears a T left from a cover.
  - The log line is `mode=... features=... maxTimeToLock=... disabled=...` (tag `KeyguardCover`), only while the
    switch or the cover is on.
- **T** (H1). `ScreenTimeout` stays the only writer of `SCREEN_OFF_TIMEOUT`. Its one write goes through
  `KeyguardCover.followTimeout`:
  - shrinking: T first;
  - growing: the setting first;
  - then T is synced to the value in force (also after a failed write);
  - a T that doesn't read back falls back.
  - Under the cover the observer runs in Release too, and T follows the phone's own value (`timeoutChanged`).
    `apply` re-syncs T at its end.
  - Seen in the reading, not by the spec: with T set, Settings greys out longer timeouts. In Release (the pause, the
    override) the kid can shorten the timeout but not lengthen it while the cover is on (§6k).
- **Held off, the re-arm** (M4, M7). The cover is written only while `armed`. `armed` is set by the first lock resume
  with the screen on in this process, and again after a fallback. So a boot (the keyguard off since
  `ACTION_SHUTDOWN`), an update or a crash restart writes DISABLED until then. `DISABLED`'s `true` also dismisses a
  keyguard that a non-clean reboot left up.
  - Interpretation of `coverRearmDue`'s `wakePending`: the fallback's own wake. After a fallback, `wakePending`
    stays until the next dark screen-off, so the re-arm comes at the first lock resume with the screen on after it.
    A dismissal that keeps failing falls back at most once per wake. This is the brief's "until the next dark
    screen-off".
- **The dismissal** (M7, `PinLockActivity`):
  - at each resume (switch on), a one-shot `OnDrawListener`; on the first frame, posted:
    `requestDismissKeyguard(this, cb)` when `dismissDue`;
  - the state check (`KeyguardCover.check`) 1 s after each request, and every second while the lock is resumed:
    - unlocked: `dismissed`, the count resets, the waiting screens go;
    - still locked 1 s after the request: `dismissal failed (state), n=`;
    - at 2: `fallback` (DISABLED, `armed = false`);
    - locked with no request open (a SystemUI restart, a late keyguard): ask again.
  - The callbacks are only logged. A success also lets the waiting screens go at once.
  - Home's boot path (`dismissBootKeyguard`, pure `homeBootDismissDue`): at Home's locked resume, with the switch
    on, before the arm, no credential and the keyguard locked. It retries every second while Home stays resumed and
    is never counted.
- **Waits for the unlock** (M5, `KeyguardCover.whenUnlocked`, one per key, newest kept):
  - `showCall`, `showVoipCall` (`VoipCalls.reopenCall`), and `TimeRulesRuntime.recheck`'s `LockActivity.start`
    (every trigger, the screen-on's included);
  - the 17b ring screen: `sendVoipRingScreen` returns without spending the try while `mustWait`, and the lock looks
    again;
  - without the cover, everything runs at once, as before.
- **VoIP** (M5): DISABLED while the phase isn't NONE. The phase is latched only with the screen on (the phase edge, or
  the lock's resume with the screen on), so a ring from dark is still covered.
- **The dark lock-up after a system-dialer call** (M5):
  - `refrontAction`: while dark, under the cover and with the lock not on top, the exempt screens are still yielded
    to, so `system_call` keeps looking every 2 s while dark;
  - with none left: `DarkLockUp`, which runs `lockUp(LOCK)` once.
  - Without the cover, the dark row is `Stop`, as before.
- **The lock-up under the cover starts the lock alone** (coordinator's device evidence, 2026-10-13):
  `lockUpStarts(..., coverOn)` gives LOCK where step 1 gives HOME_THEN_LOCK, and the dark lock-up is LOCK too. The
  log line has `cover=`.
  - While the cover's keyguard is locked, nothing that isn't `showWhenLocked` can be visible, so Home's black
    stand-in adds nothing.
  - Home's start (a binder start and its own transition, ahead of the lock's) only widens the window in which a
    quick wake shows SystemUI's bare lockscreen un-occluded, and adds a Home transition while dark.
  - With the cover not in force (switch off, held off, fallback), step 1's Home stays.
- **The double press** (the user's mash log, all builds). Two presses ~60 ms apart cancelled the screen-off fade, so
  `SCREEN_OFF` came with the screen on (`LOCK (interactive=true)`: the reading of `isInteractive` was right, a wake
  had begun). The lock was then started as a visible launch:
  - its splash (`VRI[debug]` in SystemUI) was the "loading screen";
  - Shell's default open transition shrank the closing app into a card.
  - Fixes:
    - `PinLockTheme` now has `windowDisablePreview=true`. The wake's stand-in is the task's app-theme snapshot,
      which that flag doesn't touch (AOSP `addStartingWindow` creates snapshots before the theme check), and it
      stays black;
    - every lock start passes `ActivityOptions.makeCustomAnimation(context, 0, 0)`. `NO_ANIMATION` marks only the
      lock's own change, and a custom animation without resources loads none for any change.
  - The old frame stays until the lock's first frame; the screen never went dark. Pinned in `LockUpPlanTest`, with
    a smoke step (`smoke-test.sh`, "Double press": `input keyevent KEYCODE_SLEEP KEYCODE_WAKEUP`, no
    `VRI[<package tail>]` from SystemUI's pid). The animation stays a manual frame-step check, since Shell's log
    names no stable string to grep.
  - Not verified on Android 16: whether Shell honours the empty custom animation for the closing change (§6k
    check).
  - Under the cover, per AOSP reading (to verify): SystemUI locks an insecure keyguard at once at the power-key
    sleep, so in the double press the app is hidden. At most SystemUI's lockscreen shows until the lock occludes it
    (the §12 quick-wake residual).
- **The ScreenOn backstop's re-start** (the mash log: `START ... result code=3`, `wm_new_intent` 15-35 ms after the
  wake, then HOME_THEN_LOCK at the next screen-off with the lock on top):
  - The backstop is `ScreenOn`'s `show` while the lock isn't resumed yet. When the lock resumes first, Android pauses
    it to deliver the new intent. That interactive pause cleared `lockOnTop`.
  - Now a pause within `BACKSTOP_NEW_INTENT_MS` (1 s) of a backstop start onto the lock on top is held
    (`newIntentPauseKept`), and the lock's `onNewIntent` puts the mark back (`LockTopEvent.NEW_INTENT`).
  - A stop, a resume without the new intent, a destroy or any other start drops it.
  - The backstop itself stays: it is the security backstop for a stale `lockOnTop`.
- **Not taken from the coordinator's notes:**
  - "The keyguard cover should make WM wait at the screen-on" (~3 black frames). Per L3 an *occluded* keyguard gates
    nothing; the guarantee is visibility, so the gap stays black, which the rule allows.
  - The one-frame "app glimpse" with an old status-bar time is a stale display buffer of the emulator's composer
    (coordinator, 2026-10-13). No WM or keyguard change can touch it. §6k says to record on the device and to
    re-check with another `-gpu` mode and on a real phone; step 3 waits for that.
- **Reporting**: logcat only (§8 step 2 item 7 leaves the status field optional). No server change.
- **Docs**: `docs/testing/emulator.md` §6k (the adb usage, the frame-stepping rule, §11's step 2 checks with the
  quick-press pattern made explicit, and the double-press checks). Launcher `CLAUDE.md`'s "Lock flash" bullet.
