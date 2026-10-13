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
- **Recommended fix (8.1): the system keyguard as a bridge across the sleep.**
  1. A bare Swipe keyguard: no credential, `KEYGUARD_DISABLE_FEATURES_ALL`.
  2. Our `showWhenLocked` lock occludes it while dark. By AOSP's rules nothing that isn't `showWhenLocked` can then
     be visible, and the keyguard's draw gates the screen-on.
  3. The lock dismisses the keyguard after its first frame, so the rest is today's behaviour.
  4. The lock's stand-in becomes pure black.
  5. The needless re-start at screen-off goes.
- **Defence in depth (8.2, optional):** Home under the lock, with a plain black snapshot.

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
     reading. Section 5 names the trace that settles it. The fix in §8.1 doesn't depend on it.
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
| H2 | Our needless re-start while dark (`START ... result=3`) causes it: `wm_task_moved` and the sleep-time resume/stop leave a pending transition or starting window that makes the WAKE a task switch from the task below | possible, contributes | the flash goes away when that start is skipped (a debug toggle, or §8.1 step 5 alone), with the Play Store still directly under the lock | the flash stays with the start skipped |
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
- For H2: build with the screen-off start skipped when the lock is top (8.1 step 5 alone) and repeat.
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
    `lock_screen_show_notifications` isn't DO-writable) all matter. This window is the reason for 8.1 step 4 (no
    work before the start).
- **Boot.** A swipe keyguard shows at boot before our process.
  - The boot mark (16e) on Home is hidden behind it (Home isn't `showWhenLocked`).
  - Either Home dismisses it at its locked resume (`requestDismissKeyguard` is allowed for an activity that is
    `visibleIgnoringKeyguard`, `KeyguardController.dismissKeyguard`), or the boot shows the lock without the mark.
    A design call (§10).
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
| 3 | Home under the lock + black snapshots (the first version of this doc, now 8.2) | no - only if the reveal path shows the task directly under the lock (not proven, §5). Home's frame is a visible frame unless it is pure black | low. Kept as defence in depth |
| 4 | Earlier start, display callbacks, wake locks, `showWhenLocked` alone | no (§7.1, §7.2) | - |
| 5 | Turn-screen-on on the lock | the lock would be visible while dark, but every lock-up wakes the phone | rejected |
| - | `FLAG_SECURE` on other apps | impossible | - |

**What 25b/25c already did** (not repeated here):
- no open transition (`NO_ANIMATION`, `overrideActivityTransition`, `windowAnimationStyle @null`);
- the screen-off work deferred 400 ms;
- the first lock resume after a screen-off heal skips its own heal;
- the starting window kept `kid_ground` (25f).

None of them touched the keyguard, the sleep visibility, or what lies under the lock.

## 8. Recommendation

### 8.1 The keyguard bridge (do this)

1. **The keyguard is a bare Swipe cover while the lock is active.**
   - `PinLockRuntime.configure` asks for `setKeyguardDisabled(false)` instead of `true` when the lock would be
     active and the phone has no credential. That is pure: `wantKeyguardDisabled` becomes `keyguardMode(...)`, one
     of `COVER | DISABLED`.
   - `lockActivation` no longer needs the disable to succeed; `KEYGUARD_NOT_DISABLED` becomes "the keyguard
     couldn't be enabled as a cover", reported.
   - `AppEnforcer`'s keyguard-features pass writes `KEYGUARD_DISABLE_FEATURES_ALL` while the cover is on.
   - Unmanaged, or no kid PIN: as today.
2. **Dismissal after the lock's first frame.**
   - `PinLockActivity` dismisses the keyguard with `requestDismissKeyguard` once per resume with the screen on,
     after the first draw.
   - Pure `dismissDue(resumed, interactive, keyguardLocked, drawnSinceResume, failures)`.
   - Failures are counted and reported. The fallback is `setKeyguardDisabled(true)` (it dismisses) until the next
     screen-off, when the cover is enabled again.
   - Home dismisses it at its locked resume at boot, if the user wants the boot mark (§10).
3. **The gap is black.**
   - `PinLockTheme`'s `windowBackground` and the lock's `TaskDescription` background become `#000000`. The lock
     still paints its wallpaper and scrim itself (25f); only the stand-in changes from `kid_ground`.
   - A design decision against 25f's "the starting window stays `kid_ground`": `kid_ground` is #0C0C14, close to
     black but not black. Veto in §10.
4. **Nothing in front of the lock's start at `SCREEN_OFF`.**
   - The receiver already starts the lock first (`:657`). The LOCKED edge's chrome follows on the same thread (16c).
     It may move after the start's binder call but must not move before it.
   - The occlude transition (7.4) needs the lock on top as early as possible.
5. **No redundant start.** `lockUpStarts`: a lock that is the top task and hasn't left the front since the last
   screen-off starts nothing - no more `result=3` (§4.4). Pure, with a runtime flag cleared by any interactive pause
   or stop.
6. **Comments corrected.** `PinLockRuntime.kt:650` and `PinLockState.kt:163` stop claiming the lock is drawn before
   the screen-on. Design 10 §3 gets a pointer here.

### 8.2 Defence in depth: Home under the lock (cheap, optional)

- At a lock-up while dark, start Home (typed, `HomeFront.bring`) before the lock, never during a call. Home's task
  then lies between the lock and the apps.
- Give Home `setRecentsScreenshotEnabled(false)` (its snapshot then follows the app theme), with a black
  task-description background.
- It changes nothing when 8.1 works (Home is hidden by the keyguard too). It limits the damage if the keyguard is
  ever off (dismissal failed, the crash guard, an Android credential set): then what can be revealed is a black
  Home stand-in, not an app.
- The price: with the kiosk off the unlock may land on Home. Only with the user's yes (§10).

## 9. Tests

**Pure:**
- `keyguardMode(managed, hasKidPin, deviceSecure)`: COVER only managed + PIN + no credential; DISABLED otherwise.
  This replaces `wantKeyguardDisabled`'s tests.
- `lockActivation` with the cover: active without `setKeyguardDisabled(true)`; `KEYGUARD_NOT_DISABLED` when the
  cover couldn't be set.
- `dismissDue`:
  - only resumed, interactive, keyguard locked, drawn since the resume;
  - not twice per resume;
  - after 2 failures it switches to `FALLBACK_DISABLE`.
- `lockUpStarts` / `lockOverTop`: no start while the lock has kept the top since the last screen-off; a start after
  any interactive pause or stop, and at process start.
- (8.2, if taken) `HOME_THEN_LOCK` never during a call.

**Source pins:**
- `PinLockActivity`, `InCallActivity` and `VoipWakeActivity` stay `showWhenLocked` (`PinLockManifestTest`).
- The lock has no `turnScreenOn` and no `setTurnScreenOn`.
- `FLAG_SECURE` is set before `setContentView`.
- `PinLockTheme`'s `windowBackground` is `@android:color/black`.
- `requestDismissKeyguard` is called only from `PinLockActivity` (and Home's boot path, if chosen).
- `setKeyguardDisabled` is called only from `PinLockRuntime.configure` and the dismissal fallback.
- `KEYGUARD_DISABLE_FEATURES_ALL` is written while the cover is on.
- No `TYPE_APPLICATION_OVERLAY` / `SYSTEM_ALERT_WINDOW` / `AccessibilityService` in the launcher.
- No "drawn before the next screen-on" in `lock/`.

## 10. Questions for the user (veto if wrong)

1. The keyguard comes back as a bare Swipe cover across the sleep, dismissed by the lock after its first frame.
   The phone's Settings then say "Swipe" instead of "None". A stock lockscreen may be seen in two cases:
   - when the screen is turned on within the occlude window;
   - when handy's lock is off (the crash guard).
   Is that acceptable?
2. The lock's stand-in frame (the frame before the lock draws) goes from `kid_ground` (#0C0C14) to pure black.
3. Boot: should Home dismiss the keyguard to keep the 3 s breathing mark (16e)? Or does the boot show the lock at
   once?
4. 8.2 (Home under the lock): wanted as defence in depth, given that with the kiosk off the unlock may then land on
   Home?

## 11. Device checks

**Emulator** (Android 16, Google Play image, gesture navigation, debug build as DO). The modes:
- kiosk on, from an allowed app;
- install mode, with Play in front;
- the pause, with Play in front (from the drawer);
- the override;
- a time rule;
- our call;
- a VoIP call;
- an alarm;
- SOS.

- [ ] `adb shell dumpsys window | grep -i -E "keyguard|occlud"` after a lock-up: `showing=true occluded=true` while
  dark; after the first frame `showing=false`. `dumpsys activity activities`: the Play task is
  `visibleRequested=false` while the keyguard shows.
- [ ] Power off, wait 3 s, power on, 10 times per mode. Also the screen timeout on the lock (the user's case), and
  mashing (5 presses = SOS still reachable).
- [ ] **The quick press:** power off and on within ~100 ms - is SystemUI's lockscreen ever seen?
- [ ] Recording:
  ```
  adb shell screenrecord --bugreport --bit-rate 8000000 /sdcard/flash.mp4   # frame numbers + time stamped
  adb pull /sdcard/flash.mp4 && ffmpeg -i flash.mp4 -vsync 0 frames/%05d.png
  ```
  Step from the first lit frame after each power-on to the lock.
  - Pass: only black frames (the live status bar may show), then the lock.
  - Fail: any app, Home, SystemUI's lockscreen, a stale status-bar time (a snapshot), or `kid_ground` instead of
    black.
  - The emulator's own recording captures the secure lock; `screenrecord` on a phone shows it black. Judge black
    stand-in frames by the frame before the lock's content, with the §5 layer trace when in doubt.
- [ ] The §5 Perfetto layer trace, before and after: at the first lit frame no layer of another package, Home or
  SystemUI's keyguard view is visible.
- [ ] After the first frame the keyguard is gone: `dumpsys window` `mKeyguardShowing=false`. Then: Recents (16d), a
  VoIP call screen over the lock, the time-rule screen after an unlock, the emergency dialer, the alarm, SOS. All
  as today.
- [ ] Dismissal failure path: force it (debug hook) - the lock still works, `lock_state` reports it, and the
  fallback disables the keyguard.
- [ ] Boot: what shows between the boot animation and the lock (with and without Home's dismissal), and
  `smoke-test.sh REBOOT=1`.
- [ ] Settings shows Swipe; no "None" toggling at each screen-off (`dumpsys lock_settings`).

**Jelly Star** (release build):
- [ ] The same matrix. Also a phone camera at 240 fps on the panel, because `screenrecord` shows the secure lock
  black.
- [ ] Note the Android version. On Android 13 (no shell transitions) the keyguard rules in 7.4 are the same, but
  check the occlude-while-dark timing again.
