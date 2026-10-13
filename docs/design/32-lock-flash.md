# 32 - The previous app flashes before the PIN lock at screen-on (investigation and fix design)

Security bug, reported by the user on 2026-10-13. Android 16 Google Play emulator, gesture navigation, debug launcher
as device owner. At screen-on, a Play Store page shows for about 80 ms before handy's PIN lock covers it.
The user's words: "this MUST be fixed properly".

Code reading only; no product code was changed. AOSP references are to `android16-release` on
android.googlesource.com, read 2026-10-13: `frameworks/base/services/core/java/com/android/server/...`, written below
as `wm/`, `policy/`, `power/`, plus `core/java/android/view/ViewRootImpl.java` (VRI) and the Shell's
`libs/WindowManager/Shell/.../transition/Transitions.java`. Line numbers are from that branch on that date.
Launcher paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`.

## 0. In short

- **What the user saw.** At each screen-on, these frames: black; 2 frames of a Play Store **task snapshot** (its
  status bar says 9:55, the real time was 11:17); 2 near-black frames with the live status bar; then the lock.
- **Root cause.** The lock is an ordinary activity. AOSP keeps every activity without turn-screen-on invisible
  while the display sleeps, and the screen-on gate waits only for the system keyguard and for windows that are
  already visible. So at every wake the lock **has to be drawn again after the display is lit**. Until its first
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
      then be visible, and the keyguard's draw gates the screen-on.
    - The lock dismisses it after its first frame, with a failure counter and the `setKeyguardDisabled(true)`
      fallback.
    - Home dismisses it at boot.
  - **Step 3:** step 2 becomes the release behaviour once its device checks pass on the emulator and the Jelly Star.

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
   - `PhoneWindowManager.screenTurningOn` (`:6315-6345`) holds the display off only for the system keyguard
     (`mKeyguardDelegate.onScreenTurningOn(mKeyguardDrawnCallback)`, `:6330`, with a timeout) and for
     `waitForAllWindowsDrawn` (`:6253`, 1000 ms).
   - Without a keyguard, the drawn-complete comes at once (`:6333-6336`).
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
   - The keyguard's window is not an activity, so it stays drawn across the sleep. The screen-on waits for it.
   - Activities that aren't `showWhenLocked` stay invisible behind it (`KeyguardController.checkKeyguardVisibility`).
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
  1. the keyguard: `onScreenTurningOn(mKeyguardDrawnCallback)`, or straight on when there is no keyguard
     (`:6333-6336`);
  2. `waitForAllWindowsDrawn` (`WindowManagerService.java:7975-8015`, timeout 1000 ms).
- `waitForAllWindowsDrawn` asks `WindowState.requestDrawIfNeeded` (`:1958-1996`). That needs `isVisible()`, which
  needs a surface (`wouldBeVisibleIfPolicyIgnored`, `:1716-1722`), and for an activity window it needs a
  visible-requested activity that has no starting window.
- The lock lost its surface at the sleep stop (§7.1), so it is never on that list.
- Only two kinds of window are ever waited for at a screen-on: a window that stayed visible with a surface through
  the sleep (a non-activity window) and the keyguard. That leads to the two candidates below.

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
3. The keyguard's draw gates the screen-on (`PhoneWindowManager.java:6330`).

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
  - So `KEYGUARD_DISABLE_FEATURES_ALL`, no AOD, and checking whether its notifications show on an insecure
    keyguard (`KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` is documented for secure keyguards;
    `lock_screen_show_notifications` isn't DO-writable) all matter. This window is the reason the lock is started first at `SCREEN_OFF` (no
    work before the start).
- **Boot.** A swipe keyguard shows at boot before our process.
  - The boot mark (16e) on Home is hidden behind it (Home isn't `showWhenLocked`).
  - Either Home dismisses it at its locked resume (`requestDismissKeyguard` is allowed for an activity that is
    `visibleIgnoringKeyguard`, `KeyguardController.dismissKeyguard`), or the boot shows the lock without the mark.
    Decided: Home dismisses it (§10, decision 4).
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
| 1 | **Keyguard bridge** (7.4) + a black lock background + no redundant start | **yes** by AOSP's keyguard visibility and screen-on rules, except for a screen-on within the occlude window, which then shows SystemUI's bare lockscreen | medium: reverses design 10's "no Android lock screen" for the sleep span. Boot, dismissal and yield checks |
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

None of them touched the keyguard, the sleep visibility, or what lies under the lock.

## 8. Steps

There are two stages (decision 1, §10). Step 1 is the defence in depth and is built now. Step 2 builds the keyguard
cover as a device experiment behind a debug-only switch. Step 3 promotes the cover once the device checks have
passed. Each step is one sitting, green on its own, with its tests.

### Step 1 - Home under the lock, the black stand-in, no needless start (release behaviour)

1. **Home under the lock at a lock-up.**
   - When the lock comes up at `SCREEN_OFF` (`showLock` from `ScreenOff`), `PinLockRuntime` starts Home
     (`HomeFront.bring`, typed HOME) and then the lock (`show`, `REORDER_TO_FRONT`). Both run on the main thread,
     in the receiver, in that order. Home's task then lies between the lock and every app.
   - **Skipped during a live call**: ours (`OngoingCalls.hasLiveCall`), Telecom's, or a VoIP phase other than
     NONE. Then only the lock is started, as today. It is also skipped during shutdown, where `HomeFront` already
     refuses.
   - The re-fronts with the screen on stay lock-only. A Home start in front of the kid would itself be a visible
     frame.
   - Pure: `lockUpStarts(showLock, lockOnTop, callLive): LockUpStart { NONE, LOCK, HOME_THEN_LOCK }` in
     `lock/LockUpPlan.kt`.
2. **No start for a lock already on top.**
   - The runtime flag `lockOnTop` (main thread) is set by a `HOME_THEN_LOCK` start.
   - Anything that may have put something over the lock clears it:
     - any other start of the lock (re-front, `ScreenOn`, `TimeRuleShown`, `CallsEnded`, VoIP, process start);
     - any lock pause or stop with the screen on;
     - the lock's destroy;
     - the UNLOCKED edge.
   - A pause or stop while not interactive is the sleep's own and keeps the flag. `isInteractive` flips at the
     early sleep change, before WM pauses for the sleep token.
   - With the flag set, a screen-off starts nothing. That removes the `result=3` re-start and its sleep-time
     resume/draw/stop (§4.4, H2).
   - Unknown state = `HOME_THEN_LOCK`, which is harmless while dark.
3. **Home's snapshot is plain and black.**
   - `HomeActivity.onCreate`: `setRecentsScreenshotEnabled(false)` (as `SettingsActivity`), so WM uses Home's
     app-theme snapshot (`ActivityRecord.shouldUseAppThemeSnapshot`). Otherwise Home's stored snapshot could be
     its *unlocked* grid.
   - `setTaskDescription(TaskDescription.Builder().setBackgroundColor(BLACK))`, so that app-theme snapshot is
     black.
   - Home's cold-start splash (16e's breathing mark on `vibb_night`) is unchanged. It is a launch splash, not the
     task snapshot.
4. **The lock's stand-in is pure black** (decision 3).
   - `PinLockTheme` `windowBackground` becomes `@android:color/black`, and the lock's task description background
     becomes black.
   - The lock still paints its wallpaper and scrim itself (25f). Only the frame before its first draw changes
     (was `kid_ground` #0C0C14).
5. **Unlocking lands on Home in every mode** (decision 2).
   - Kiosk on: nothing to build; the lock already leaves through Home.
   - Kiosk off: the lock stops its own lock task and finishes, and Home - right under it - is in front.
   - As a backstop for a lock-up where Home was skipped (a call), `lockLeave(kioskOn = false, ...)` gets
     `homeFirst = true`, so Home is started before the finish, as with the kiosk on.
   - A glance row's target still opens after Home (`go()`).
6. **Comments corrected.**
   - `PinLockRuntime.kt:650` and `PinLockState.kt:163` no longer claim the lock is drawn before the screen-on.
   - Design 10 §3 gets a pointer to this doc.
   - `launcher/CLAUDE.md` gets a "Lock flash (design 32)" bullet.
7. **Log:** `Lock-up at screen-off: NONE|LOCK|HOME_THEN_LOCK (call=<bool>)`, once per screen-off.

### Step 2 - The keyguard cover as a device experiment (debug builds only)

The release behaviour stays exactly as after step 1. Everything new is reachable only through the debug hook,
in the same pattern as 16d's `LockTaskOverrideReceiver` and design 28's `HeadsetDebugReceiver`.

1. **The switch.**
   - `src/debug`'s `lock/KeyguardCoverDebugReceiver`: exported, guarded by `android.permission.DUMP`.
   - Usage:
     ```
     adb shell am broadcast -n $P/com.kidslauncher.mdm.lock.KeyguardCoverDebugReceiver --ez on true|false
     ```
   - It stores the switch in CE prefs `keyguard_cover_debug`, re-runs `PinLockRuntime.configure` (through an
     apply), and logs `KeyguardCover: on|off -> setKeyguardDisabled(<bool>) = <result>, features <hex>, keyguard
     locked <bool>`. It reports the same in the result data.
   - `src/main` reads the switch only through `KeyguardCoverDebug.enabled(context)`. `src/release`'s version
     returns `false`.
   - The receiver and its names go into `CheckDebugHook`'s list and `DebugHookAbsentTest`'s ban. Never name it in
     `src/main`, comments included.
2. **The cover** (only while the switch is on, the lock is active, managed, a kid PIN, no credential):
   - Pure `keyguardMode(managed, hasKidPin, deviceSecure, coverWanted): COVER | DISABLED | UNTOUCHED`.
   - COVER means `setKeyguardDisabled(false)` (Swipe) and `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_FEATURES_ALL)`.
   - `lockActivation` accepts the cover as "the keyguard is handled". `KEYGUARD_NOT_DISABLED` becomes "the cover
     couldn't be set".
   - Switch off means as today: `setKeyguardDisabled(true)`, and the features as `AppEnforcer` computes them.
3. **The dismissal after the lock's first frame.**
   - `PinLockActivity`: once per resume with the screen on, after the first frame since the resume
     (`OnDrawListener` -> post), `KeyguardManager.requestDismissKeyguard(this, callback)`. Only while the cover is
     on and `isKeyguardLocked`.
   - Pure: `dismissDue(coverOn, resumed, interactive, keyguardLocked, drawnSinceResume, triedThisResume)`.
4. **The failure counter and report.**
   - `onDismissError` / `onDismissCancelled`, or the keyguard still locked 1 s after `onDismissSucceeded`, counts
     one failure.
   - The counter is kept in memory and reported in `lock_state` as `keyguard_dismiss_failures`, with
     `keyguard_cover` (on/off). This is a server and PWA change (status only, both sides in one commit) - or, for
     the experiment, logcat only. The implementer decides; logcat is the minimum.
   - Every failure logs `KeyguardCover: dismissal failed (<why>), n=<count>`.
5. **The fallback.**
   - At the 2nd consecutive failure: `setKeyguardDisabled(true)`, which dismisses the showing keyguard.
   - The cover is set again at the next screen-off's deferred work (`screenOffWork`, dark, 400 ms) and the counter
     resets on a success.
   - Pure: `dismissFallback(failures) = failures >= 2`.
6. **The boot dismissal** (decision 4).
   - At its locked resume in the boot's first process start (`bootMarkDue`), `HomeActivity` dismisses the keyguard
     with `requestDismissKeyguard`, so the breathing mark (16e) shows. Home isn't `showWhenLocked`, and dismissal
     is allowed for a `visibleIgnoringKeyguard` activity.
   - The lock then comes up as today. Failures count the same.
7. **Tests:** the pure functions, plus the pins in §9. The debug receiver is tested only by the hook guards.
8. **Usage doc:** `docs/testing/emulator.md` gets §6k "Keyguard cover (design 32 step 2)": the broadcast, the
   `dumpsys` checks, the screenrecord procedure.

### Step 3 - After the device checks: the cover becomes the release behaviour

- Only after §11's step 2 checks pass on the emulator **and** the Jelly Star.
- `KeyguardCoverDebug` is removed. The cover becomes the default wherever `keyguardMode` says COVER.
- Decide whether it gets a server switch: a `keyguard_cover` policy field, default on, as an off switch for an
  OEM that misbehaves. If yes, policy JSON on both sides in one commit, `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`.
- Design 10's "no Android lock screen" rule is updated in `docs/design/10-lock-and-call-ui.md` and
  `launcher/CLAUDE.md`. The screen lock is Swipe, a bare cover across the sleep, dismissed by handy's lock after
  its first frame; `setKeyguardDisabled(true)` is only the fallback.
- The keyguard state becomes part of the status report (if step 2 kept it to logcat).

## 9. Tests

**Step 1, pure** (`LockUpPlanTest`):
- `lockUpStarts`:
  - `showLock=false` -> NONE;
  - a live call (ours, Telecom, VoIP) -> LOCK;
  - `lockOnTop` -> NONE;
  - otherwise HOME_THEN_LOCK;
  - unknown -> HOME_THEN_LOCK.
- `lockOnTopAfter(event, interactive)`:
  - set only by HOME_THEN_LOCK;
  - cleared by every other lock start, an interactive pause or stop, destroy, UNLOCKED;
  - kept by a non-interactive pause or stop.
- `lockLeave(kioskOn = false, ...)` now has `homeFirst = true` (`LockTaskRootTest`).
- `PinLockStateTest`'s `ScreenOff` cases are unchanged.

**Step 1, source pins:**
- The `SCREEN_OFF` branch goes through `lockUpStarts`, and `HomeFront.bring` comes before `show`.
- No other screen-off path starts Home.
- `HomeFrontTest` still bans explicit Home intents.
- `HomeActivity.onCreate` calls `setRecentsScreenshotEnabled(false)` and sets a black task-description background.
- `PinLockTheme` `windowBackground` is `@android:color/black`.
- `PinLockActivity` keeps `FLAG_SECURE` before `setContentView`, keeps `showWhenLocked`, and has no
  `turnScreenOn` / `setTurnScreenOn` (`PinLockManifestTest`).
- No `TYPE_APPLICATION_OVERLAY`, `SYSTEM_ALERT_WINDOW` or `AccessibilityService` in the launcher.
- No "drawn before the next screen-on" in `lock/`.

**Step 2, pure:**
- `keyguardMode`: COVER only with the switch on, managed, a PIN and no credential; switch off = today's.
- `lockActivation` with the cover.
- `dismissDue`: not before the first frame, not twice per resume, not with the screen off, not without the cover.
- `dismissFallback`.

**Step 2, source pins:**
- `KeyguardCoverDebugReceiver` is only in `src/debug`, in `CheckDebugHook` and `DebugHookAbsentTest`.
- `src/main` reaches the switch only through `KeyguardCoverDebug`.
- `requestDismissKeyguard` is called only from `PinLockActivity` and `HomeActivity`'s boot path.
- `setKeyguardDisabled` is called only from `PinLockRuntime.configure` and the fallback.
- `InCallActivity` and `VoipWakeActivity` stay `showWhenLocked`.

## 10. Decisions (user, 2026-10-13, final)

1. **Both stages.** Step 1 (Home under the lock, the black stand-in, no needless start) is built now. The Swipe
   keyguard cover follows as a device experiment behind a debug switch (step 2) and is made permanent (step 3)
   only if it holds on the emulator and the Jelly Star.
2. **Unlocking lands on Home in every mode**, kiosk off included. So Home under the lock is wanted as defence in
   depth.
3. **The stand-in frame is pure black** - the orchestrator's call, since it matches "lock or black". `kid_ground`
   stays the lock's painted ground, not its stand-in.
4. **At boot, Home dismisses the keyguard**, so the breathing mark (16e) still shows - the orchestrator's call.

Open for step 3: whether the cover gets a server switch.

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
- Black frames, with or without the live status bar, pass. In step 1, a plain frame of our own (Home's black
  stand-in) passes too.
- The emulator's own recorder captures the secure lock; `screenrecord` on a phone shows it black. When in doubt,
  use the §5 Perfetto layer trace: at the first lit frame, no layer of another package, Home's content or
  SystemUI's keyguard view may be visible.
- The modes, each 10 power-off/3 s/power-on cycles plus the screen timeout on the lock (the user's case) and
  mashing (5 presses = SOS still reachable):
  - kiosk on, from an allowed app;
  - install mode, with Play in front;
  - the pause, with Play in front (from the drawer);
  - the override;
  - a time rule;
  - our call;
  - a VoIP call;
  - an alarm.

**Step 1** (emulator, then the Jelly Star release build):
- [ ] The rule above in every mode. Step 1 does not promise the strict requirement (§7.5 rank 3). Record whether
  any non-black frame is left, which one, and attach the §5 trace if so - that decides how urgent step 2 is.
- [ ] Logcat:
  - `Lock-up at screen-off: HOME_THEN_LOCK` after an unlock or a yield;
  - `NONE` at a screen-off with the lock already on top;
  - no `ActivityTaskManager: START ... PinLockActivity ... result code=3`.
- [ ] `dumpsys activity activities` after a lock-up: lock task, then Home's task, then the app.
- [ ] During a call (ours, VoIP), the power key: no Home over the call screen. After the hang-up, the lock.
- [ ] Unlock lands on Home with the kiosk on, the kiosk off (pause, override) and install mode. A glance row's
  target still opens.
- [ ] Recents after the unlock: no `BlockedAppActivity` (16d); `smoke-test.sh` green.
- [ ] Home in Recents/Overview shows a plain black card, not its grid.
- [ ] VoIP ring from dark, alarm from dark, SOS: unchanged.

**Step 2** (debug build, emulator and the Jelly Star, the switch on):
- [ ] The broadcast logs `on -> setKeyguardDisabled(false) = true`. Settings > Security shows Swipe.
  `dumpsys lock_settings` shows no credential.
- [ ] After a lock-up while dark: `adb shell dumpsys window | grep -i -E "keyguard|occlud"` shows
  `showing=true occluded=true`. `dumpsys activity activities` shows the Play/Home tasks `visibleRequested=false`.
- [ ] **The rule above in every mode: no failing frame at all.** That is the strict requirement - only black, then
  the lock.
- [ ] **The quick press:** power off and on within ~100 ms, 20 times - SystemUI's lockscreen is never seen. If it
  is, the cover fails this check.
- [ ] After the lock's first frame the keyguard is gone (`mKeyguardShowing=false`, log `dismissed`). Then, as
  today:
  - Recents (16d);
  - a VoIP call screen over the lock;
  - the time-rule screen after an unlock;
  - the emergency dialer;
  - the alarm;
  - SOS;
  - the camera double-press (nothing opens).
- [ ] Dismissal failure: make it fail (e.g. a debug extra that skips the call, or `adb shell wm dismiss-keyguard`
  races). The counter logs, the 2nd failure runs the fallback (`setKeyguardDisabled(true)`), and the next
  screen-off sets the cover again.
- [ ] Boot (`svc power reboot`): the boot animation, then Home's breathing mark for 3 s (Home dismissed the
  keyguard), then the lock. No SystemUI lockscreen frame. `smoke-test.sh REBOOT=1` green.
- [ ] The switch off: `setKeyguardDisabled(true)`, Settings shows None, and everything is as step 1.
- [ ] The Jelly Star: note the Android version. On Android 13 (legacy transitions) repeat the quick-press check
  carefully.

**Step 3** (release build, after the step 2 checks pass on both devices):
- [ ] The full step 2 list again on the release build, without the switch.
- [ ] An update from a step 1 build: the cover comes on at the first apply, without a reboot.
- [ ] If a server switch is added: off restores step 1's behaviour at the next sync, on brings the cover back.
- [ ] A phone with an Android PIN set (`lock_state.inactive = android_credential`): untouched, no cover, no double
  lock.
