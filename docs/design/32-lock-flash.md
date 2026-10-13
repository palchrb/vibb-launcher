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
- **Recommended fix (8.1).**
  1. Every lock-up that happens while the screen is dark puts **our own Home** (the night ground) directly under
     the lock.
  2. Home's task snapshot becomes a plain app-theme one (`setRecentsScreenshotEnabled(false)`).
  3. The redundant re-start of a lock that is already on top at screen-off goes.
  4. Whatever the platform reveals under the lock at a wake is then ours and plain. Nothing of another app can be
     in reach.
  5. A second stage, only if even the plain frame must go, is a swipe keyguard that spans the sleep (8.2).

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
| H2 | Our needless re-start while dark (`START ... result=3`) causes it: `wm_task_moved` and the sleep-time resume/stop leave a pending transition or starting window that makes the WAKE a task switch from the task below | possible, contributes | the flash goes away when that start is skipped (a debug toggle, or the §8.1 step 3 alone), with the Play Store still directly under the lock | the flash stays with the start skipped |
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
- For H2: build with the screen-off start skipped when the lock is top (8.1 step 3 alone) and repeat.
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

## 7. Fix options

| Option | Guarantees no app content? | Cost / risk | Verdict |
| --- | --- | --- | --- |
| A. Start the lock earlier at `SCREEN_OFF` (synchronous, high-priority receiver, no work before it) | no - the broadcast comes after the display is off (§3.1), and the activity stays invisible while dark anyway (§3.2) | none | **rejected**: it can't help |
| B. `PowerManager` / `DisplayManager.DisplayListener` callbacks | no - the display state goes `OFF` only after the dim/off; an app has no power-key hook; still an activity | small | **rejected** |
| C. `WakeLock` / `setKeepScreenOn` "draw, then let it sleep" | no - the power key ignores wake locks; it could only cover the timeout | medium | **rejected** |
| D. Turn-screen-on on the lock | the lock would be visible while dark, but **every lock-up would wake the phone** (`canTurnScreenOn` -> wake) | breaks pocket / battery, design 17 | **rejected** |
| E. `setShowWhenLocked` alone | no - meaningless without a keyguard (the lock already has it) | none | part of G |
| F. `FLAG_SECURE` on other apps | impossible | - | **rejected** |
| G. Keep the system keyguard (swipe, no credential) across the sleep; the lock is `showWhenLocked` over it; the resumed lock dismisses it with `requestDismissKeyguard` at each wake | **yes, by AOSP**: non-`showWhenLocked` activities can't be visible while it shows, and the screen-on waits for it | reverses design 10's "no Android lock screen". Many interactions to re-check: VoIP/call screens that aren't `showWhenLocked`, the SOS/emergency/alarm yields, the camera double-press over a keyguard, SystemUI's lockscreen (notifications) showing whenever the lock isn't on top, and what `isKeyguardLocked` means to our code. No BFU risk: no credential means CE unlocks at boot | **second stage** (8.2), if a plain frame must go too |
| H. A `TYPE_APPLICATION_OVERLAY` cover window added at `SCREEN_OFF` (a non-activity window stays visible across the sleep; its first relayout draws even while dark, VRI `:4411`), removed after the lock's first frame | yes while it is shown | needs `SYSTEM_ALERT_WINDOW` - an app-op a DO **can't** grant with `setPermissionGrantState` (adb / manual grant at provisioning). Conflicts with our own `DISALLOW_CREATE_WINDOWS` while locked, which hides every overlay including ours. Hidden by any app window with `setHideOverlayWindows` (a normal permission - the Play Store's purchase sheets may use it). Must never stay over a call screen (untrusted-touch occlusion) | **rejected**: too many holes, it weakens the lock's overlay block |
| I. **Our own Home always directly under the lock**, with Home's snapshot forced to the plain app theme, and no redundant start while dark | **yes, for anything the wake can reveal**. The tasks the platform can show before the lock's frame are the lock's own and the one directly under it - both ours and plain. An app's task is below Home, and Home is opaque and fullscreen | small: two starts while dark, at the first lock-up after an unlock or a yield only. Kiosk off now unlocks to Home instead of the previous app (kiosk on already does) | **recommended** (8.1) |

## 8. Recommendation

### 8.1 Home under the lock (do this)

1. **Lock-up while dark = Home, then the lock.**
   - At `ScreenOff` with `showLock`, `PinLockRuntime` starts Home (`HomeFront.bring`, typed HOME) and then the lock
     (`show`, `REORDER_TO_FRONT`), in that order, both on the main thread in the receiver.
   - Home's task then sits between the lock and every other app.
   - Not while any call is live (ours, Telecom's, VoIP - no Home over a call, as `BootHome` already does), and not
     during shutdown (`HomeFront` already refuses).
   - Pure: `lockUpStarts(showLock, lockOverHome, callLive): LockUpStart { NONE, LOCK, HOME_THEN_LOCK }`, in
     `lock/LockUpPlan.kt`.
2. **No start when nothing changed.**
   - A runtime flag `lockOverHome` (main thread) is set only by a `HOME_THEN_LOCK` start.
   - It is cleared by any other start of the lock (`show` from the re-front, `ScreenOn`, `TimeRuleShown`,
     `CallsEnded`, VoIP, process start), by any lock pause or stop **with the screen on** (something came over it),
     by the lock's destroy, and by the UNLOCKED edge.
   - A pause or stop while not interactive is the sleep's own and keeps it: `PowerManager.isInteractive` has
     already flipped at the early sleep change, before WM pauses for the sleep token.
   - With `lockOverHome` set and the lock the top task, a screen-off starts nothing. That removes the `result=3`
     re-start, its sleep-time resume/draw/stop and the out-of-order buffers (H2).
   - Fail closed: an unknown state does `HOME_THEN_LOCK`, which is harmless while dark.
3. **Home's snapshot is plain.**
   - `HomeActivity.onCreate` calls `setRecentsScreenshotEnabled(false)`, as Settings already does
     (`ui/settings/SettingsActivity.kt:57`). WM then uses the app-theme snapshot for Home's task
     (`ActivityRecord.shouldUseAppThemeSnapshot`, `:5615`).
   - Otherwise Home's stored snapshot could be its *unlocked* grid from whenever Home was last left, and that would
     be the new leak.
   - For one colour at the wake, Home's task description background goes to the night ground
     (`setTaskDescription(TaskDescription.Builder().setBackgroundColor(vibb_night))`; also the starting window's
     colour). The lock's `kid_ground` stays. Or align both to one ground - a design call (§10).
4. **Leaving.**
   - Kiosk on: unchanged (`lockLeave` goes through Home anyway).
   - Kiosk off (pause, override): the lock finishes and the kid lands on Home, not on the app they left. Two cases:
     - Home's task joined the lock's own lock task: then `stopLockTask` clears it (AOSP `clearLockedTask`), and the
       app underneath comes back as today.
     - Otherwise Home is shown.
   - Either is safe: the kid has unlocked. A device check records which one happens, and the user decides whether
     Home is acceptable (§10). A glance row's target still opens after the unlock (`go()`).
5. **Re-fronts with the screen on stay as they are** (lock only). A Home start in front of the user would itself be
   a visible frame. They clear `lockOverHome`, so the next screen-off restores the order while dark.
6. **Comments corrected.** `PinLockRuntime.kt:650` and `PinLockState.kt:163` stop claiming that the lock is drawn
   before the screen-on. Design 10 §3 gets a pointer here.

**Why this is a guarantee and not a smaller window.**
- At a wake, the platform can only show the top task's stand-in (the lock: `FLAG_SECURE`, so app-theme, `kid_ground`)
  and what it reveals under it (Home: app-theme, night ground).
- Any other app's task lies under Home. Home is opaque and fullscreen, so it can't be made visible while Home is
  above it, and its snapshot isn't the one revealed.
- What remains is at most a few plain dark frames of our own grounds.

**Limits.**
- The order is restored only at the next screen-off. Between a re-front and that screen-off the lock is in front
  and the screen is on, so nothing can flash in that span.
- A phone-call or VoIP lock-up (Home skipped) leaves the call's app underneath. The call's own screen is in front
  then anyway, and the next screen-off without a call puts Home back.
- If the trace (§5) shows the platform revealing more than one task down, Home's opacity still occludes it - check
  it on the device.

### 8.2 Second stage, only if a plain frame must go too: the keyguard bridge (option G)

- Re-enable the insecure keyguard (`setKeyguardDisabled(false)` while the lock is active, still no credential).
- Keep the lock `showWhenLocked`. In `PinLockActivity.onResume` with the screen on, call
  `KeyguardManager.requestDismissKeyguard`, so the rest of the time is exactly today's.
- AOSP then keeps everything that isn't `showWhenLocked` invisible across the sleep and holds the display until the
  keyguard has drawn.
- That is its own design step, with device checks for every yield. Not part of this fix.

## 9. Tests

**Pure** (`LockUpPlanTest`):
- `lockUpStarts`: `showLock=false` -> NONE; a live call -> LOCK; `lockOverHome` -> NONE; otherwise
  HOME_THEN_LOCK; unknown -> HOME_THEN_LOCK.
- `lockOverHomeAfter(event, interactive)`: set only by HOME_THEN_LOCK; cleared by every other start, an interactive
  pause/stop, destroy, UNLOCKED; kept by a non-interactive pause/stop.
- `PinLockStateTest`: the `ScreenOff` cases unchanged.

**Source pins** (extending the existing guard tests):
- The `SCREEN_OFF` branch of `PinLockRuntime.screenReceiver` calls `HomeFront.bring` before `show` and goes through
  `lockUpStarts`. No other place calls `HomeFront.bring` on a screen-off path. `HomeFrontTest` still bans explicit
  Home intents.
- `HomeActivity` calls `setRecentsScreenshotEnabled(false)` in `onCreate`.
- `PinLockActivity` keeps `FLAG_SECURE` before `setContentView`, and keeps no `setTurnScreenOn` / `turnScreenOn`
  (`PinLockManifestTest`).
- No `TYPE_APPLICATION_OVERLAY` and no `SYSTEM_ALERT_WINDOW` in the launcher.
- The two corrected comments: no "drawn before the next screen-on" left in `lock/`.

## 10. Questions for the user (veto if wrong)

1. With the kiosk off (the pause, the override), the unlock may land on Home instead of the app that was open. This
   is already the case with the kiosk on. Acceptable?
2. Should the remaining plain frames (the lock's `kid_ground`, Home's night ground) be one colour? Or is the
   keyguard bridge (8.2) wanted, so that not even those show?

## 11. Device checks

**Emulator** (Android 16, Google Play image, gesture navigation, debug build as DO), in each mode: kiosk on from an
allowed app, install mode with Play in front, the pause with Play in front (from the drawer), the override, a time
rule, our call:

- [ ] Power off, wait 3 s, power on; 10 times per mode, and also mashing (5 quick presses = SOS stays reachable).
- [ ] Screen timeout on the lock, then power on: the user's exact case.
- [ ] Recording:
  ```
  adb shell screenrecord --bugreport --bit-rate 8000000 /sdcard/flash.mp4   # --bugreport stamps time + frame no.
  adb pull /sdcard/flash.mp4
  ffmpeg -i flash.mp4 -vsync 0 frames/%05d.png
  ```
  Step through every frame from the first lit one after each power-on to the lock's first frame.
  - Pass: no frame shows anything but black, Home's night ground, the lock's `kid_ground`, or the lock.
  - Fail: any app content, any status bar with an older time than the live one (a snapshot), or Home's grid.
  - On the emulator the secure lock may record (it did in the user's run); on the Jelly Star it records black.
    Plain frames are judged by colour either way.
- [ ] The §5 trace once, before and after the fix: at the first lit frame after the wake, no layer of another
  package is visible.
- [ ] Logcat: no `ActivityTaskManager: START ... PinLockActivity ... result code=3` at a screen-off while the lock was
  on top; one `HOME_THEN_LOCK` per lock-up after an unlock or a yield (new log line `Lock-up at screen-off: <plan>`).
- [ ] Kiosk off: what the unlock lands on (Home, or the previous app via `clearLockedTask`).
- [ ] Kiosk on: Recents after the unlock (16d: no `BlockedAppActivity`), and `smoke-test.sh`.
- [ ] Calls: the power key during our call, then hang up - the lock comes back, and no Home over the call screen.
- [ ] VoIP ring from dark, alarm from dark, SOS: unchanged.

**Jelly Star** (release build):
- [ ] The same matrix, with `screenrecord --bugreport` (the lock records black - judge by colour) and a phone camera
  at 240 fps pointed at the screen, which also sees the panel's own start.
- [ ] Note the Android version (legacy or shell transitions), and run the §5 trace if anything but plain frames shows.
