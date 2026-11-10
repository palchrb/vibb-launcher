# Launcher notes: the PIN lock, Android's lock screen as the gate, the glance

Moved from launcher/CLAUDE.md on 2026-11-01.

## Handy's own PIN lock and call screens (handy step 10, design `docs/design/10-lock-and-call-ui.md`, at the monorepo root)

- **No Android screen lock any more** (own mode; with `kid_lock.keyguard` "android" Android's PIN is the gate instead - design 59 below): `PinLockRuntime.configure` (from `apply()`) switches the keyguard off (`setKeyguardDisabled(true)`, screen lock "None") only when managed, the policy has `kid_lock` and the phone has **no** Android credential; with one our lock stays off (`lock_state.inactive = android_credential`, no double lock) until it is removed (runbook: 04 doc step 4, `docs/testing/emulator.md` §6c; `onPasswordChanged` with `<watch-login/>` re-checks at once). Without a credential CE unlocks by itself at boot, so the BFU state is gone; `LOCK_TASK_FEATURE_KEYGUARD` stays forced and all direct-boot code stays (unmigrated phones, boot window). `PinLockActivity` is **not** direct-boot-aware.
- **Pure parts** (`lock/`): `PinLockState.kt` (`step`: DISABLED/LOCKED/UNLOCKED; process start = LOCKED and shown with the screen on; every screen-off locks; calls ending while LOCKED or with the screen off show the lock; `lockActivation`), `PinBackoff.kt` (4 free tries, then 30 s/1/2/5/15 min, since security review S-L6 doubling on to 1 h a try - `PIN_BACKOFF_CAP_MS`, the user's choice 2026-11-01, and a try whose count is not committed is refused; a wait only ever extends - new boot = full wait from now, either clock keeps it going; the failure is committed **before** PBKDF2 and undone on success; a new hash resets), `RefrontPolicy.kt` (re-front 0.5 s → 2 s → every 5 s, never giving up; exempt: our call, `TelecomManager.isInCall`, 2 min after "Emergency call", the system clock app's alarm; crash guard 3 recorded crashes / 2 min, re-armed by a sync after 10 min). Tests `PinLockStateTest`, `PinBackoffTest` (also pins `timedWindowActive`'s opposite polarity), `RefrontPolicyTest`, `PinLockTaskTest`, `PinHashTest` (vector shared with the server), `PinLockManifestTest`.
- **Always in lock task while LOCKED** (QA 10 #1): `lockTaskWhileLocked` - kiosk on: the kiosk list is never touched (removing a package clears its task) except that the recents provider leaves it while LOCKED (16d, qa-16d-code #1), features minus NOTIFICATIONS/HOME/OVERVIEW; kiosk off: our package + `pinLockHelpers` (emergency dialer, Telecom, system dialer, system clock app) with KEYGUARD|GLOBAL_ACTIONS|SYSTEM_INFO, and `PinLockActivity` starts/stops lock task itself (only when `isLockTaskPermitted`; not during a VoIP call, and not as Android's face with Android locked - design 59b R2 below). Plus `setStatusBarDisabled(true)` (backstop) and `DISALLOW_CREATE_WINDOWS` while locked. **`lock/LockTaskChrome` is now the only place that sets lock-task packages/features, the status bar and `DISALLOW_CREATE_WINDOWS`** - `apply()` hands it the plan (`applyPlan`), the runtime calls `refresh` on LOCKED changes; never set them anywhere else. `HomeActivity` never `stopLockTask`s while LOCKED. Stopping a root lock task clears the other locked tasks (AOSP `clearLockedTask`), so after an unlock with the kiosk off the time-rule screen is started again (`afterUnlock`).
- **Lock background** (design 25, 25f): `PinLockActivity` draws `WallpaperStore.state.lock` (pure `lockWallpaper`: the parent's `launcher_ui.lock_wallpaper` when allowed and usable, else Home's `current` - photos included) under a black scrim (`lockScrim*`: every ink in `LOCK_INKS` - white, the dim date, "Foreldrekode" at 75 %, the red emergency link - >= 4.5:1, at least `MIN_IMAGE_SCRIM` for photos, cap 0.8) on the keypad root and the glance root (`GlancePanel.ground`), once `lockReady`, again on every store change between onStart and onStop. The window stays opaque (no `windowShowWallpaper`); the layout roots keep `kid_ground` until the store is ready. Since design 32 the stand-in before the first frame (window background, splash, bars, task description) is black, not `kid_ground`.
- **Lock fix round 25b** (design 25, "Lock fix round 25b"): while LOCKED without a live call the cameras are off by policy (`setCameraDisabled`, `<disable-camera />`, `CameraLock`'s `cameraPolicyDisabled(locked, callActive)`, `PinLockRuntime.callForCamera`; never unlocked, 25c) so the power double-press starts nothing (a suspended camera crashed SystemUI); a pause re-fronts too (Recents only pauses), pause and stop after 200 ms; no open transition for the lock; the screen-off's non-lock work waits 400 ms (`SCREEN_OFF_WORK_DELAY_MS`); the first lock resume after a screen-off heal doesn't heal; Emergency SOS (`HelperKind.EMERGENCY_SOS`, `emergencySosPackage`) is pinned, never restricted or listed, and the re-front yields "sos" for a stop within 3 s of a screen-on (`sosGestureLikely`, never with our own screen in front) - for at most 15 s, ended by the lock in front or any other yield (`sosMarkKept`), and while it runs lock task is narrowed to our package, the helpers and the SOS app (`sosNarrowedPackages`, `LockTaskChrome.sosYield`; 25c, qa-25b-25f H1). The SOS package is cached (`PinLockRuntime.sosPackage`).
- **Lock flash (design 32, `docs/design/32-lock-flash.md`, step 1)**: `ACTION_SCREEN_OFF` comes after the display is off, and an activity without turn-screen-on can't draw while dark, so the lock is drawn only **after** the screen-on - until its first frame WM shows the task under it (its snapshot) and the lock's stand-in. Never give the lock turn-screen-on, a cover overlay or an accessibility service (§7). Step 1: every screen-off's start goes through `PinLockRuntime.lockUp(start)`, planned by pure `lockUpStarts(showLock, lockOnTop, callLive, interactive)` (`lock/LockUpPlan.kt`, `LockUpPlanTest`): HOME_THEN_LOCK while dark with no live call (ours, Telecom's, VoIP) - except as Android's face, where it is LOCK whatever `kg` read (design 59d step C, `lockUpStarts(.., face)`; the read still feeds `sawLocked`), and the ScreenOn backstop as the face skips a glance known to be on top (`ScreenOn.faceOnTop`, its skip keeps the mark in `screenOnUnshown`) - Home first with `HomeFront.bring(..., animate = false)` (`NO_ANIMATION`, still typed HOME), then the lock; LOCK with the screen on again (a late `SCREEN_OFF`) or a live call; NONE when the lock is the top task already (`lockOnTop`, best effort, pure `lockOnTopAfter`: set by the lock-up, kept by the sleep's pause/stop and the same lock-up's ScreenOn backstop, cleared by an interactive pause/stop, destroy, the unlock, every other start - inside `show` itself - and a screen-on that finds the lock not resumed and starts nothing, `screenOnUnshown`: a system-dialer or VoIP call came over the sleep-stopped lock; `show` answers `true` only for a start that didn't throw). Log `Lock-up at screen-off: NONE|LOCK|HOME_THEN_LOCK (call=, interactive=)`. A dark lock-up marks `darkLockUp` (cleared at the next interactive resume of the lock or Home, and by every other `HomeFront.bring` - only the lock-up's is unanimated; one from another thread is posted to the main thread, so it can't land between the lock-up's mark and starts): Home's resume then only shows the night ground (pure `homeDarkResume`; no lock task - the lock's task is focused by then, the lock's own `START_HOME` roots it), its `onStart` work waits for an interactive start or resume (`startWork`). Home's `singleTask` start clears the time-rule screen (`LockActivity`, in Home's task) at a dark lock-up; it comes back at the unlock (`afterUnlock`) or Home's next interactive resume. The stand-in is black: `PinLockTheme`'s window background, splash and bars, and a black `TaskDescription` on the lock and Home; Home has `setRecentsScreenshotEnabled(false)`. Unlocking lands on Home in every mode, kiosk off too, but never over a live call (`lockLeave(..., callLive)`; the glance send check counts Home below then, `GlanceOpener.open(..., homeBelow)`) - as Android's face with the kiosk on too since design 59d step H (`lockLeave(.., androidFace)`, `callLive` = `PinLockRuntime.callLiveNow`), except as the kiosk's lock-task root, which re-roots through Home (own mode's kiosk-on leave is unchanged). (§5's H2 toggle, `LockUpDebug`, was deleted in round 0b.) A double press that cancels the screen-off fade starts the lock as a visible launch (the user's mash log): its black splash and Shell's close animation of the app the kid was in remain without the cover - a residual (§12, qa-32-s2-code M1): handy is the home process, so `windowDisablePreview` is skipped for every lock start, and no public `ActivityOptions` overrides a task change; don't add either back as a "fix". The ScreenOn backstop's start onto a lock already resumed pauses it for the new intent; that pause keeps `lockOnTop` (`newIntentPauseKept`, restored in `onNewIntent`). Step 2 (§15): the system keyguard as a bare Swipe cover across the sleep - never on since its debug switch (`KeyguardCoverDebug`, its receiver and the dismissal knobs) was deleted in round 0b: `KeyguardCover.switchOn` is `false`, and the COVER mode's code below goes with design 59 phase D. `lock/KeyguardCover` is the **only** writer of `setKeyguardDisabled`, the keyguard features and `setMaximumTimeToLock` (pure `lock/KeyguardCoverPlan.kt`: `keyguardMode`, `writeKeyguard` - features, read back, T = the screen timeout, read back, then `false`; any failure is today's disabled keyguard with the lock active - the cover clears exactly its own feature bits, prefs `keyguard_cover`); `ScreenTimeout`'s writes go through `followTimeout` (T in the safe order). Under the cover: the lock-up starts the lock alone, the lock dismisses the keyguard after its first frame (state check each second while the cover is on, fallback at 2 failures, re-armed at a lock resume with the screen on after the next dark screen-off; held off in a new process until its first such resume after an apply), the lock-up starts the lock alone only while the keyguard is locked at that screen-off (`kg=`), the VoIP call screen and the time-rule screen wait for the unlock (`whenUnlocked`, re-checked when they run; our showWhenLocked call screen doesn't wait), DISABLED in a VoIP call, off at `ACTION_SHUTDOWN` and at a crash-guard trip. The device policy outlives the process: prefs `keyguard_cover` `cover_written` (committed before `setKeyguardDisabled(false)`, cleared after a `true` that worked) makes a new process write DISABLED at once (`atProcessStart`; it also dismisses the Swipe screen a reboot without `ACTION_SHUTDOWN` left up - Home can't, it sleeps behind the keyguard) and lets `disableNow` act in a new process; no rewrite before the first apply. `setMaximumTimeToLock` turns "Stay awake" off (android mode too, emulator.md §6k). Not device-tested: §11's step 1 and step 2 checks (step 2's no longer can be).
- **`PinLockRuntime`**: the screen on/off/USER_PRESENT receiver moved here from `CommandListenerService` and is registered process-wide in `Application.initRest` (screen time, time-rule re-checks and the Play window run from it too). Time-rule screens are never suppressed: `LockActivity.start` puts the PIN lock back on top (`TimeRuleShown`). PIN checks run off the main thread; the Parent code link goes through `ParentPin.check` (the parent PIN's own escalating lockout, works during the kid's wait and offline) and unlocks only the lock (no `activate()`); a kid-PIN or parent-code Ok that comes back after a screen-off is dropped (`screenOffs`, `LockEvent.Unlocked(checkedAt, screenOffs)`, design 24 QA #4). Remote `lock` → `lockNow` then `dpm.lockNow()`, result "locked (handy lock)"/"locked (Android)". Status `lock_state` (active, inactive, locked, failures, backoff end, exempt yields - never unlock times or PIN material), capability `pin_lock_v1`. State in CE prefs `pin_lock_state` (`PinLockStore`, `commit()`); **nothing PIN-related in DE storage**; `allowBackup="false"` + `data_extraction_rules.xml`/`backup_rules.xml` exclude everything.
- **Call screens** (`InCallActivity`, mocks IncomingCall/InCall): gradient, 140 dp avatar (since the 2026-10-06 user requests the Home avatar for a known contact after unlock, else the placeholder figure - see "Call screens, ongoing-call card, camera while locked"), Decline/Answer, Speaker/Mute toggles (on = white, navy icon), Hang up; nothing leads elsewhere (same over the lock). While LOCKED a ringing call's screen gets one retry after 300 ms; still hidden → `call_state.in_call_ui_failed_at`.
- **Calls and the lock (fix round qa-10-code)**: Back on the call screen does nothing while a call exists; whenever the lock resumes during **our** call it starts the call screen again (`LockResumed` → `showCall`), so a ringing call stays answerable and an active one controllable. Every in-call SCREEN_OFF locks (a proximity blank sends none), a call ending with the screen off locks, and LOCKED during our call starts the lock (the call screen comes over it, inside the lock task). A call only the system dialer shows (`systemCall`: emergency, or our dialer role not held) is never covered; the re-front loop waits for it to end. Kiosk off pins the system dialer only while our dialer role isn't held (`pinLockHelpers(ourDialerHeld)`); with calls unmanaged its "Add call" may reach the dialer's full UI during a call (10 doc). The alarm exemption needs the **system** clock app (since design 42 also Fossify Clock with its pinned signer, `clockPackages()`) as `setAlarmClock` creator, lasts 3 min and ends when the lock is back in front. The crash guard counts only crashes the uncaught-exception handler records while `PinLockActivity.instances > 0` (no recreations, no kills). The lock is started before the chrome change; kiosk-off helpers are resolved at init.
- Toasts don't show over the lock (`DISALLOW_CREATE_WINDOWS`): the lock and its dialog show their own texts. Screen time: `screenTimeCounts(..., pinLocked)` and `PinLockActivity` is a free screen.
- **Not device-tested**: the checklists in the 10 doc ("Implementation status") - lock before any frame at screen-on, Recents/Assistant/camera/power menu escapes in both kiosk modes, full-screen intents with the shade disabled, alarms, PBKDF2 time, safe mode, migration timing.

## Android's own lock screen as the gate, the glance its face (design 59 steps 3-4, `docs/design/59-android-keyguard-design.md`, at the monorepo root)

- **The switch**: the policy's `kid_lock.keyguard` - exactly `"android"` is Android's lock (pure `androidRequested`),
  anything else (missing, `"own"`, junk, an older server) is the own lock above, every path unchanged. Glue
  `lock/AndroidLock`, pure `lock/AndroidLockPlan.kt` (`AndroidLockPlanTest`), log tag `AndroidLock`. The prototype's
  debug switch (`HybridDebug`, its receiver) is gone. Since step 4 the capability `android_keyguard_v1` is claimed
  (with its handler), so the server sends the Child PIN by `set_kid_pin` (below); the keypad migration stays for a
  server that holds no PIN.
- **`set_kid_pin`** (step 4, §3.5): `dispatchPendingCommand(..., pending, freshPolicy.kidLock)` ->
  `AndroidLock.applyPinCommand(context, kidLock, pending.pin)`: pure `pinCommandStep` - the fresh policy not asking
  for Android's lock `own lock`, no PIN `refused: no_pin`, the PIN not verifying against the **fresh** hash
  `refused: mismatch` (`PinLockRuntime.verifyCommandPin` = the top-level `commandPinVerifies`: no backoff, no failure,
  exactly `pin_length` digits; Q59-1), no active token `refused: no_token` (`ensureAndroidResetToken` sets one for the
  escrow) -, else under `AndroidLock`'s monitor `keyguard_android` first with the rev Android holds now
  (`commandWriteAheadRev`), `reset()` (our change marked with the rev it sets), then the fresh rev (`commitRev`, tried
  twice): `pin set` (a failed reset `refused: failed`, and what was written ahead given back). The answers are
  `PinCommandAnswer`'s words. The PIN is a parameter only: never stored, cached (`cacheablePolicy`), logged (a scan of
  every log call, multi-line, in the five files it passes through) or answered; the dispatch returns the command
  without it. The sync's own apply that follows turns the face on and, with our lock LOCKED in the dark, locks Android
  at once (`lockAfterPinSet` in `afterKeyguard`, not a relock).
- **Reports** (step 4): `lock_state.keyguard`/`pin_rev`/`reset_token`/`keyguard_relocks`, always sent
  (`AndroidLock.report`, pure `keyguardReport`/`androidGate`: `"android"` while the face is on or we own the live
  credential, with `android_pin_rev` only for ours; else `"own"` with the applied hash's rev); `reset_token` in every
  mode; the relocks subtracted only by a report the server took (`relocksReported`). The remote `lock` answers `locked (android pin)` while `AndroidLock.gateNow`
  (`lockAnswer`). `power.on.unlocked_at_ms`: `PowerEvents.recordUnlock(this, AndroidLock.firstCeStartOfBoot)` in
  `Application.initUnlocked` right after `decide` (pure `withUnlock`, DE key `on_unlocked_at_ms`, once a boot).
- **Stored** (CE `pin_lock_state`, M-1): `keyguard_requested` and `pin_rev` committed with the hash by `saveConfig`;
  `keyguard_android` = we own Android's credential - committed **before** a reset (write-ahead) and cleared only when
  no credential is left (a verified clear, or removed on the phone); `android_pin_rev` = the rev Android's PIN was set
  from (`null` after a change on the phone, §3.7); `android_boot_count`, written at **every** CE process start
  (`decide`, L-4).
- **Face** = requested, managed, a kid PIN and an Android credential (`androidFace`). Then `KeyguardCover` writes
  ANDROID (`setKeyguardDisabled(false)`, `ANDROID_KEYGUARD_FEATURES` = widgets, secure notifications, trust agents,
  FACE, IRIS, shortcuts - fingerprint stays -, T = the screen timeout, read back incl. B2's 0x2; never
  `setKeyguardDisabled(true)`, never the cover's dismissal), `lockActivation(androidGate)` keeps our lock active as the
  face, the camera lock stands down only while the write read SECURE_CAMERA back (`KeyguardCover.androidCameraGuard`,
  re-synced on both edges, P59-4/8), the face's own re-front rule while Android's **secure** keyguard is locked
  (P59-3; design 59b below), the ring card and keypad hide "Foreldrekode".
- **Design 59b** (`docs/design/59b-android-lock-face.md`, "As built"; every rule face-only, own mode pinned unchanged):
  - *The leave without Home* (step 0): `lockLeave(.., glanceInFront)` with `leaveInFront(faceOn, resumed)` - an unlock
    on SystemUI's own face or pad (the glance stopped under the app) finishes the glance under that app, no Home, no
    row target; kiosk on with the glance the lock-task root still goes through Home. Amends design 59 §3.3's "Home
    below". `presentLeave` re-reads a non-LEAVE once.
  - *The face's loss rule* (`androidLossStep` in `RefrontPolicy.kt`, replacing the blanket stop): `RefrontInputs.android`
    (`androidLoss`: Android LOCKED under the face, or OPEN with `USER_PRESENT` -> `RefrontAction.AndroidLeave` through
    the glance's `USER_PRESENT` path; neither -> own mode's rows, the RELOCK backstop). Dark -> nothing; the exemptions
    as own mode's (a VoIP call ringing or live yields); ours (`androidLossOurs`: the pad's ask - `noteAndroidAsk` -, our
    own start < 2 s, our screens) -> nothing; at most `ANDROID_REFRONT_CAP` (2) re-fronts per screen-on
    (`androidRefronts`, zeroed only at the screen-off and `USER_PRESENT`); else a re-front with
    `FLAG_ACTIVITY_NO_USER_ACTION` (`show(noUserAction)`, only from this rule). Log `AndroidLock: re-front #n (..)`.
  - *One stamping point*: `PinLockRuntime.noteOwnStart`, via `startOwn` (the call screen, the wakes, the time-rule
    screen's `LockActivity.start`) and `VoipCalls.send`. **A new `startActivity` in `lock/` goes through `startOwn`**
    unless it is in `HomeFront`, `GlanceOpener` or `BootCover*`, or the glance's own start in `show()`
    (`AndroidLockFaceTest`).
  - *The platform's ring* (step 1): as the face with Android locked a ring with its full-screen intent is SystemUI's
    (`voipPlatformRing` -> `VoipRinging.platformRing`: no lock-up, no wake; `voipFsiDue(.., platformRing)` false); the
    timer `voipGrace` runs `voipRingStep` 3 s from the ring's first sight: the one heads-up send (glance resumed and not
    paused since, screen on), else `VoipFsiTry(sent = false)` (the card), dark -> the wake and the card. The reopen of a
    VoIP call screen needs `IN_COMMUNICATION` as the face (`voipReopenDue`). §4.4 (a re-posted ring continues) is not
    built.
  - *Look*: since design 59d step K Android's lock wallpaper as the face is the glance's own ground with its scrim baked
    in (`lockScreenFill(.., glance)`, `lockScreenScrim`; `WallpaperFill.NightMark`, drawn by `WallpaperRender`, only
    while no ground is known); no organisation name. Since 59d step B "Lås opp" (since step J the swipe up) and a row lead to the `ASK` stage
    (`glanceStageStep(.., face)`, `GlanceStageEvent.OwnKeypad` for no credential, `PinLockActivity.asking()`): the
    glance page stays drawn under Android's pad, our keypad never visible or focusable; at the leave, as the face and in
    front, the page's content is hidden (`glanceLeaveHides`, `GlancePanel.leaving`/`leaveRefused`), Home comes with
    `HomeFront.bring(.., noAnimation)` and the glance's finish is unanimated (59b's close fade is gone); while it asks
    for the pad the glance's (opaque) window has `FLAG_SHOW_WALLPAPER` (`PinLockActivity.onStage`, RC3-2, design 59d
    §8.14), so the leave is the shell's wallpaper-intra transition, not `wallpaper_open_exit` (the ground sliding down
    off the screen with a photo ground) - set well before the leave, since WM reads it when the transition collects
    the glance, and never cleared by the leave.
    `AndroidLock: screen-on` logs the keyguard at every screen-on.
  - *The code QA's fix round* (`qa-59b-code.md`; 59d steps A and D): as the face with Android locked the glance roots
    lock task itself (`lockTaskEntry(.., androidLocked)`; with the kiosk on - kiosk off it takes none since R2, below), and Home after a policy edge (`HomeFront.bringAfterUnlock`:
    the relock, install mode's end, the update, a role change) and the time-rule screen (`LockActivity.start`) wait for
    the unlock in `KeyguardCover.afterAndroidUnlock`'s queue, run when our lock leaves LOCKED (`runAfterUnlock`) - **a
    new Home or non-showWhenLocked start of ours goes through it**. The face re-fronts only on a fresh pause or stop
    (`androidFreshLoss`), a cancelled ask stays ours for 2 min, Android open with no `USER_PRESENT` waits once
    (`AndroidWait`) before own mode's backstop, the pad's success leaves as in front (`leaveFromAsk`), the "sos" yield
    narrows no lock task, and no digit is taken under Android's pad (`keypadEnabled`).
  - *The rc.2 run's fix round* (`docs/testing/2026-10-22-rc2-run.md`): as the face a pause of the glance in the SOS
    window, not ours, is the probable-SOS mark (`sosMarkAtPause`; R1 - own mode keeps its pause re-front for Recents;
    since 59d N1 that mark is set silently and `SOS_MARK_LINE` is logged by the pause's check only while the mark still
    holds; since E7's L2 a stop's mark too, and only by a check that yields to it, `sosMarkLogDue` - an alarm's screen
    coming up just after a screen-on is logged as its "alarm" yield alone),
    and a queued start sent at once drops its queued copy (`KeyguardCover.dropWaiting`; R4: one time-rule screen).
    R3 is a label only.
  - *R2, decided* (the user, 2026-11-02; branch `android-r2`): as the face with Android's secure keyguard locked
    (`AndroidLock.refrontHeld`) **and the kiosk off** (`LockTaskChrome.kioskOn` false) the glance takes **no lock task**
    (`lockTaskEntry(.., androidLocked)` -> NONE, beside the VoIP row; log `AndroidLock: lock task: none of the glance's
    own ...`). The rc.2 run's R2: a helper started over the glance (DeskClock - a ringing alarm, the run's own start)
    joined the glance's lock task, and the face's leave after SystemUI's pad stopped it from the root, which clears every
    locked task (`clearLockedTasks`, `clear-task-all`) - the kid landed on Home, not in the app. Now nothing runs, so the
    leave stops nothing (`lockLeave` reads `lockTaskRunning()`) and the glance is no lock-task root (it can finish; no
    `rootLeave`, which is kiosk-on only). What still holds the glance: Android's locked keyguard (a start that isn't
    showWhenLocked lands behind it and un-occludes it -> the face's loss rule; Recents refused, P59-1), Back held by
    the glance, the status bar's `setStatusBarDisabled` and `DISALLOW_CREATE_WINDOWS` (`lockTaskWhileLocked` still
    sets them and the kiosk-off package list while LOCKED), the power menu the keyguard's. What lock task gave and no
    longer does there: Home/Recents disabled in SystemUI (a home gesture or key now reaches the keyguard - the pad, or
    SystemUI's face and the loss rule's re-front with §8.16's gesture ask), and the refusal (101) of other apps'
    starts - a showWhenLocked screen of an allowed app (its full-screen intent: an alarm, a call) can now come over
    the glance, never past Android's PIN. Unchanged: the kiosk on (the glance roots it, 59d A), own mode, the
    fallback, the face with Android not locked (START_SELF). **A lock task already running** in that state is stopped
    at the entry check (`LockTaskEntry.STOP_OWN`, log `AndroidLock: lock task: the glance's own stopped ...`; code QA
    M1): the face starts one whenever Android reads open at an entry check - the RESUME/UNLOCK_TAP wait before a
    RELOCK: a process start with the screen on (a crash, a self-update's restart), a remote lock (`lockNow` posts the
    show before `DPM.lockNow`), a late or missed `USER_PRESENT` - or one is left from a kiosk on -> off flip; with the
    kiosk off any running lock task is the lock's own (`lockLeave`'s kiosk-off row). Never during a VoIP call (its
    PiP). Only when this lock instance started it (`glanceRoot` = `startedLockTask`, reset when nothing runs): E7b's
    M-B1 - own mode's kiosk rooted by Home, then Android's lock and the kiosk off in one change - showed that
    `stopLockTask` from a task that isn't the root takes that task out **and clears it** (AOSP `clearLockedTask` ->
    `performClearTaskForReuse`): the glance was finished, Home's locked resume showed a new one, which joined the lock
    task and stopped again - 142-266 resumes a minute with Android's keyguard un-occluded and no way to the pad. Another
    root (Home's) now stays until the leave (the kiosk-off row, then Home's unlocked `reconcileKioskMode`); unknown
    (an earlier instance or process rooted it) counts as another root - at worst R2's helper case at that leave. **A policy that turns the kiosk or the face on or off** re-runs a resumed LOCKED lock's entry
    check at once (`chromeEntryRecheckDue` on `EntryPolicy(kioskOn, face)`): `LockTaskChrome.applyPlan` tells the chrome
    listeners after its writes (`PinLockRuntime.chromePassLanded`; `AppEnforcer` sets the face just before it), so the
    kiosk turned on under the face's glance roots its lock task from the glance (59d A) whether Home is in front or not
    - E7's M2: only the runtime's own passes told the lock, it waited for the next screen cycle, and Home's
    `reconcileKioskMode` from the background got "Invalid task, not in foreground" (still logged, harmless) -; the kiosk
    turned off with Android locked stops the glance's own (STOP_OWN); the face switched off roots the own mode's.
    **The status-bar heal** (16d) flips lock-task features, which SystemUI never hears without lock task, and a lock
    task that starts later sends them itself: so without one no flip runs (`statusBarHealKind` -> NONE; E7 L3), except
    that LOCKED the screen-off heal, still dark, writes `setStatusBarDisabled` off and on (DISABLE_TOGGLE,
    `LockTaskChrome.healStatusBarDisable`; L1) - never lit, since the shade is unblocked between the two writes; a
    failed "on" write is retried and then rewritten by a normal pass. The system-Home heal (59d §8.9 (a)) and its "a home gesture got through" line need a
    lock task running (`systemHomeHealDue(.., lockTaskRunning)`, L3); §8.16's gesture ask is unchanged. Pinned in
    `LockTaskRootTest` (the R2 and STOP_OWN rows, every other input against the old rule, the policy recheck) and
    `StatusBarHealTest`.
- **The glance leaves only on Android's verdict** (`faceStep`, §3.3): the PIN pad's `onDismissSucceeded`, or a
  `USER_PRESENT` since our lock last went LOCKED (the mark is reset at every LOCKED edge, screen-off and remote lock).
  A not-locked read alone waits `ANDROID_ANSWER_WAIT_MS` (1 s; `ANDROID_UNLOCK_WAIT_MS` 3 s once Android read
  locked since this screen-off - a fingerprint from dark, S3-3) for the broadcast, then RELOCKs (`dpm.lockNow()`,
  counted in `AndroidLock.relocks` for the report's `keyguard_relocks`); 1 s after each screen-off while LOCKED and dark the sleep
  check RELOCKs an open Android. No credential at any trigger: our keypad and hash. **`USER_PRESENT`** has its own
  receiver registered **EXPORTED** for that action alone (SystemUI sends it from its own uid, which a NOT_EXPORTED
  receiver never hears - the run's bug 1; it is a `<protected-broadcast>` in frameworks/base's core manifest);
  as the face only and with the screen on (S3-2) - own mode ignores it, as before.
- **The swipe up/a row** ("Lås opp" before 59d step J) -> `requestDismissKeyguard` (Android's PIN pad); a swipe or a row's tap on an ask older than
  `ANDROID_ASK_RETRY_MS` (1 s) asks again, and an ask with no answer after `ANDROID_ASK_TIMEOUT_MS` (2 s) while our
  window has the focus (nothing of SystemUI over us) - or when the focus comes back later - is recovered by reading
  the state again (the run's bug 2: never 30 s of dead taps). Any ask's success leaves (Android's verdict), never
  after a screen-off since that ask.
- **Process start**: the boot's first CE start with the face is UNLOCKED (`processStartUnlocked`, `ProcessStart.androidBoot`
  - the PIN was typed to unlock CE); every later start LOCKED, and the resume's rule decides. The crash guard in
  android mode calls `lockNow()` (`AndroidLock.crashGuardTripped`).
- **Migration** (§3.4): the next correct Child PIN on our keypad while requested with no credential becomes Android's
  PIN through `WindowRelock.resetCredential` (the one `resetPasswordWithToken` caller besides 24b's relock), with the
  rev of the hash it was checked against (`PinResult.Ok(pinRev)`, Q59-5). The token: `ensureAndroidResetToken` at every
  apply while requested or owned (`androidTokenStep`: none -> set, inactive without a credential -> set, inactive with
  one -> wait for the escrow; L-3).
- **Rollback** (§3.8, L-1): owned, not requested, and the apply's policy carries the parent's choice -
  `AppEnforcer.apply(context, decision: PolicyToApply)` passes `carriesParentChoice` (a fresh or `Ok` cached policy;
  never a `Fallback` or `Apply(null)`) to `PinLockRuntime.configure`; every caller passes the decision, not its policy.
  The credential is cleared with the token before the keyguard is written (then DISABLED); no active token: Android's
  PIN stays the gate (UNTOUCHED) until the escrow activates. An apply **without** the parent's choice never touches
  Android mode: while we own the live credential the keyguard stays ANDROID (`androidKeyguardHeld`) and
  `keyguard_requested` is stored only off a parent-choice apply (`requestToStore`) - qa-59-step3-code S3-1. 24b's
  relock step 1 is skipped while requested or owned (`credentialAction(..., androidRequested)`; unreadable = skipped).
- **`onPasswordChanged`**: every own reset first marks the change in CE (`OwnChange`: a count, the boot, 60 s, the rev
  it sets - qa-59-step4-code S4-1), so a new process after a death and two resets close together still know it as
  ours; ours keeps the rev (or takes the rev being set, when its commit never came), any other change makes
  `android_pin_rev` unknown. **The restart line** ("Skriv koden for å starte telefonen", `android_lock_restart_line`): written at
  `ACTION_SHUTDOWN` as the face (BootCover's one receiver, its own try, between the cover and PowerEvents), cleared at
  the boot's first CE start and when the face goes. As the face the notice wake has no 15 s end and no `lockNow`
  (design 59d step E, the user's Q2: `noticeWakeOffDue(.., face)`): the glance stays lit, Android locked under it, until
  the phone's own screen timeout, so the fingerprint keeps working; own mode keeps its `lockNow` after 15 s.
- Not device-tested: design 59 §8.

## The lock's glance page (design 25, `docs/design/25-lock-glance.md`, at the monorepo root, mockup `LockGlance.dc.html`)

- **The page** (pure `lock/LockGlance.kt`, `LockGlanceTest`; glue `lock/GlancePanel.kt`, `view_lock_glance.xml`, `view_lock_glance_row.xml`; replaces step 4's media view and `LockMedia.kt`): the PIN lock always comes up (`lockStageDue`: first show, every screen-off) on the GLANCE stage - clock (56 sp; on a phone with a clock profile Android's small clock's geometry, design 59d step L below), date, the music tile (`glanceTile`: whenever there is a session, playing or paused, also at a lock-up; goes with the session; since design 28 the session is the windowed one - a paused one only inside the listening window, as Home's card; since 2026-11-04 any allowed, usable app's session, `lockTilePick` - the playing one first, Vibb music first among equals - music-bridge.md, "PIN lock media view"; full with 0 or 1 rows, compact from 2 - r11 §2), up to 8 rows plus "+ N varsler til" (r11; 3 before), "Nødsamtale", Android's gesture handle drawn at the bottom (`pin_emergency.performClick()`). A swipe up (design 59d step J, below; the "Lås opp" button before it) or a row -> the KEYPAD stage (no clock or date, the back arrow always, the prompt two lines at most between its columns, no music (25b-G); keys shrink to the 32 dp floor rather than clip, `PinLockLayoutTest`); the arrow back and every lock-up drop a row's target; coming back with the screen on keeps stage and target. **The page scrolls whole** (launcher round 11, `docs/design/r11-launcher-glance-scroll.md` §1-2; r9's `glanceFit` and its 7 candidates are gone): everything above the bottom block - clock, date, alarm line, the music and timer tiles, the rows and the "more" line - is one `ScrollView`, `lock_glance_scroll` (0dp, weight 1, a 32 dp fade at an edge with more - Home's bottom fade since 2026-10-26, so a row wholly below the fold still fades the card above it into its text, qa-launcher-glance-c-code M-2; it holds `lock_glance_top`, wrap_content, whose last child `lock_glance_list` is gone only with no row; the page is back at its top whenever the glance is the face again - every lock-up, a step back from our keypad or Android's pad (`glanceBackToTop`), a resume that is no lock-up, the VoIP ring card going (`GlancePanel.backToTop`, the user 2026-10-24); the "more" line is a 48 dp target whose tap leads to the keypad as a row without a target (`tapMore`, `GlanceStageEvent.Row(null)`: the plain prompt, Home after the code)); the bottom block (the bottom row) and the handle stay anchored. The side padding is the top block's and the bottom block's margins, not the root's, and nothing between clips, so the scroller is the screen's width (a row swiped sideways leaves at its edge). Nothing is measured or shrunk to make room, not even at font scale 2.0: the pure `glanceLayout(tile, rowCount, timer)` - the big clock, the date and the alarm line always (since the calmer glance only the evening's wake-up alarm, on the date line where it fits - its bullet below), the timer tile while one runs, min(n, `GLANCE_MAX_ROWS` = 8) rows and the "more" line, the music tile full with 0 or 1 bound rows and compact from `GLANCE_COMPACT_FROM_ROWS` = 2 (no hysteresis, whatever the bar or the clock profile). **The layout is keyed on the available height** (`available()`: the root less its paddings, which carry the visible bars - edge-to-edge, so a bar reported at the screen-on or a navigation-mode switch changes only the padding and moves the profile's clock; the listener and `layoutNow` both use it, r9 §1); the log line "Glance layout: tile COMPACT, 2 rows, 0 more, available 457 dp (pad 56/56)" comes with every new layout or available height (counts and dp only). `LockGlanceLayoutTest` pins the parts from the XML and what scrolls at 473/497 dp (a 48 dp status bar with 3-button or gesture navigation: a 401/425 dp scroller over the 56 dp bottom block; music + 3 rows fits compact, 392 dp - with the Jelly Star's profile clock 414.4 dp: fits with gestures, row 3's bottom 13.4 dp under the fade with 3-button -, music + 4 scrolls 55/31 dp), the order, and the widths at 320/360/411 dp. A row's title is one line, its text one line and two from a 720 dp tall screen (`@integer/glance_row_text_lines`, `values-h720dp/` - `screenHeightDp`, the whole window on Android 15+, less the bars on 14 -, six-inch F4: where the compact tile and three two-line rows fit at font 1.3); the row's icon sits at the top, 2 dp in, beside the title (`LockGlanceLayoutTest`'s height model reads `values/`'s count and refuses line spacing it doesn't model). Round controls have fixed equal sizes. **`FLAG_SECURE`** on the lock's window before `setContentView` (screencap shows it black - QA screenshots via the emulator console, `docs/testing/emulator.md` §6c). TalkBack: the root has no node, the keypad is hidden while covered, one node per row with a click label ("Lås opp og åpne"), every row view `setAccessibilityDataSensitive(YES)` (UiAutomation, an accessibility tool, still reads them: the smoke test swipes up from the handle, `lock_glance_handle`, before the PIN and checks the missed call's row). **The swipe up** (design 59d step J, the user's rc.2 test: the glance as Android's face in function): the "Lås opp" button is gone; a vertical swipe up anywhere on the page asks as it did - the ASK stage as the face, our keypad in own mode (`GlanceStageEvent.Unlock`, one behaviour, two gates) - and since 59d §8.13 (the user, looking at the clock twin) **Android's gesture handle** in its place, drawn by us where SystemUI draws its own (lock task hides SystemUI's while we are LOCKED, so never two): a view of the lock's frame on the window's bottom edge (`GlancePanel.handle`, id `lock_glance_handle`; over the page, under the VoIP ring card; shown with the page's content only), the pure `glanceHandle(profile)` - the profile's measured pill (emulator: 108 x 4 dp, 10 dp off the bottom, white 92 %), else `DEFAULT_GLANCE_HANDLE` 108 x 4 dp, rounded, white 80 %, 8 dp off the bottom (inside the inset: "8 dp above the inset" read literally would sit on Nødsamtale's row) -, the view `glanceHandleViewHeightDp` tall with the pill centred, never clickable; the hint line is gone and the bottom row sits 8 dp under the top block (`fixed` 56 dp in the model). Pure `glanceSwipeStep(dy, vy, startedOnTappable, dx, page, lifted)`: a drag past a third of the page (before the lift too) or, at the lift, a fling of >= 48 dp at >= 500 dp/s, always mostly vertical; `glanceSwipeBlocked(onControl, scrollTakes)`: never from a control that keeps the touch (the music tile's buttons, the timer's Stopp, the flashlight, Nødsamtale) or a drag in the scroller while it can scroll (at its bottom end a swipe up stays the scroller's overscroll, r11 §1.4); since §8.13 a swipe from the handle / the bottom inset is the page's - lock task refuses the system's Home gesture (`TYPE_RESET_GESTURE`), so the touches reach us, and when the system takes it (a desync) its pilfer arrives as `ACTION_CANCEL`, which asks nothing. The swipe works under Android's pad too (the code QA's JL-1: the DOWN is gated on `glancePageShown(stage)`, so a swipe in ASK asks again after `ANDROID_ASK_RETRY_MS`, as the button's second tap did). `PinLockActivity.dispatchTouchEvent` feeds every touch to `GlancePanel.swipe` first, with `covered` while the VoIP ring card is up (then no swipe starts and a dragged row springs back; `renderVoip` also calls `covered(..)`, which hides the page from accessibility under the card); a recognised swipe sends the views a cancel (no row tap, no scroll) and posts `step(Unlock)`; a DOWN always reaches the views. It only asks: every exit is still `faceStep`'s LEAVE or our keypad. TalkBack (whose touch exploration takes the swipe) reaches the unlock through the handle: its node is described "Lås opp" and its click action, labelled "Lås opp" (`lock_media_unlock`), asks as the swipe does; a sighted tap on it does nothing (`GlancePanel.handleAction`). **A row swiped away** (r11 §3, the user's ask - it reverses design 25 §3's "rows can't be swiped away"; as on Android's own lock screen): `swipeNow` decides each gesture's axis by its first movement past the touch slop (`glanceTouchAxis`, a tie vertical); sideways on a bound row on the GLANCE stage takes the gesture (`SwipeMode.DISMISS`, the views' cancel), the row follows and fades (`glanceDismissAlpha`), and at the lift `glanceDismissStep` dismisses it past 40 % of its width or on a 500 dp/s fling after 16 dp (both directions; it slides off, collapses with its gap, 150 ms each - at once with "Remove animations"), else it springs back; a second finger, `ACTION_CANCEL` or the VoIP card springs it back; a sideways start on a row it can't take is no unlock, anywhere else the unlock's own rule stands (qa-r11-glance-code Q-8). No row is rebound while a finger is down (`rowsHeld`, Q-1): a held render runs at the gesture's end. `commitDismiss` runs once on the row bound when the kid saw it (the rows aren't rebound while one is dragged or dismissed): a message row `GlanceReader.dismiss` - the glance's one cancel, `cancelNotifications` of `GlanceStore.keysOf` (that conversation's kept keys up to the row's newest post; their delete intents fire as from the shade), then `GlanceStore.hide` (the opened mark); no listener -> it springs back -; a contact's missed-call row `MissedCallNotifier.dismissContact` (a committed dismissed mark, CE prefs `missed_call_dismissed`, 14 days; the notice counts after `noticeMarks(seen, dismissed)`, the phone book's badge by the seen marks alone - "Pappa 1" stays; a recompute that never posts anew, counting against the rules, and alerting only for a genuinely new call it finds - Q-3); the plain row `MissedCallNotifier.dismiss`; a missed-call row stays out until its own pass answered (a `done` callback, Q-5). What a dismissal changes: that row's notification(s), a contact's dismissed mark, and - for the plain row or the last contact - the call log's unread missed calls marked read with Telecom's count reset, as the shade's swipe of ours does (Q-6). Nothing else is dismissable (tiles, alarm line, "more" line). "Fjern" is refused under the VoIP ring card (Q-2). A lock-up and `stop()` spring a drag back and commit a released dismissal at once. TalkBack: every row's custom action "Fjern" (`glance_dismiss_action`, id `glance_action_dismiss`) commits at once; no `announceForAccessibility`. Tests: `LockGlanceTest` (the step, the block rule, the handle, the layout, the axis, the dismiss), `LockMediaGuardTest` (the touchables, the handle's action and view, the swipe's controls = the layout's touchables less the page and the timer tile, the DOWN's guard, the views' cancel, the swipe's only step), `LockGlanceGuardTest` (the one cancel, the commit's three calls), `MissedCallNoticeTest` (`noticeMarks`). App icons are Home's (`KidAvatars.renderAppIcon`, drawn by one background job, a placeholder until then); missed calls show the contact's Home avatar with a red phone badge.
- **Data** (pure `badges/GlanceRows.kt`, `GlanceRowsTest`): `badges/GlanceReader` is the only reader of names and texts, only of `GlanceStore.policy.apps` (a package not ticked is never read past its name), on the listener's callbacks (posted with its `RankingMap`, removed, ranking update, connect sweep, disconnect - `BadgeListenerService` forwards them and itself still reads no extras): `glanceCandidate` (not ours, no summaries, ongoing, non-clearable, media, importance <= MIN, call/alarm/... categories), `glanceLevel` (not ticked -> not read; notification or channel `VISIBILITY_SECRET` -> app + count; that app's text switch (`GlancePolicy.showsText(pkg)`, design 51) -> TEXT; sender only for MessagingStyle, any other style app + count - QA Low 8), of MessagingStyle bundles only `sender_person`'s name or `sender` (never the parser), the group flag and conversation title, `readTexts` only at TEXT; the ranking's suspended flag on every post, sweep and ranking update (no ranking = suspended, QA Medium 1); `cleanGlanceText` (control/bidi out, invisible marks dropped, Unicode whitespace collapsed, 60/200 chars). **Contact photos and the name line** (2026-10-25, branch `launcher-glance-b`, design 25 "2026-10-25"): `GlanceNotice.contactId` (our contact's id, memory only, from SENDER up) - Element X via `ElementDmReader.glanceContact` -> pure `elementGlanceContact` (a DM by design 15's learned room, a group by its newest `sender_person` key), WhatsApp (`GLANCE_TEL_PACKAGES`) via `glanceTelContact` (the newest sender person's URI only when `tel:`, normalised as the phone book's, matched in memory, never kept, logged or sent - the reader's one `?.uri`, pinned by `LockGlanceGuardTest`); the row shows the contact's Home avatar with the app's icon as the corner mark (`glanceRowContact`; a missed call keeps the red phone); `glanceShape` + `glanceNameSuffix`: a named group in "Name only" "Fotballgjengen · Pappa", several messages in "Full text" "Mamma · 3 nye" / "Fotballgjengen · 5 nye" (qa-launcher-glance-b-code Q-6), the suffix its own view after the name (`glance_row_suffix`; only the name is cut, Q-7); the corner by pure `glanceRowBadge` (a missed call's red phone wins); TalkBack by pure `glanceSpokenParts` ("Gruppe Fotballgjengen, siste fra Pappa"). A contact added later shows from that conversation's next post (the id is resolved when the notification is read; a removal is immediate). `GlanceStore` (main thread, **memory only**): the facts, content intents (kept, never read), opened marks, the policy - nothing in prefs, DE/CE, the status report, a DTO, a log line beyond package names, counts and exception classes (`LockGlanceGuardTest`). `glanceRows`: one per conversation (shortcut id else key), newest first, joined by the missed-call notice's contacts; a conversation opened through the glance - or dismissed on it (r11 §3) - stays hidden until a newer post. Every type has a redacted `toString()`.
- **Policy**: `PolicyResponse.lockGlance` (raw JSON) -> pure `lockGlancePolicy`: an object field by field - `apps` entries are objects `{package, show_text}` (design 51: the text per app; `show_text` only for JSON `true`, missing = false) or strings (an older server's shape: that package, sender only - its top-level `show_text` is ignored), anything else or an invalid package dropped; `GlancePolicy.apps` is `Map<String, Boolean>` (package -> text). Missing/`null` = the server's default (Element X, sender only) when fresh, unknown (missed calls only) from the cache (a pre-25 build never kept the key, QA Low 11); anything else unknown. A launcher from before design 51 drops the objects: missed calls only until it updates (accepted, design 51 §1.3). `GlanceStore.refreshPolicy` from every accepted sync (posted to the main thread) and `loadAtStart` from the `Ok` cache (never over a fresh one); a change re-sweeps. Capability `lock_glance_v1`.
- **Missed calls**: `MissedCallNotice.contacts` (`MissedContact(number, name, count, lastAtMs)`, redacted `toString`, QA Low 9); `MissedCallNotifier.current` + listeners (main thread, guarded): set on a post, cleared on every cancel, swipe and when our listener sees id 1006 leave the shade; `relearn` (a recompute that never posts anew and never clears, counting against the rules without the time rule's block - `noticeState`; from `initRest`, every time-lock change in `TimeRulesRuntime.recheck`, and when the listener sees ours posted while `current` is unknown, a snooze that ended) knows a notification still on screen. Tapping any missed-call row opens the phone book or the contact's sheet, whose resume clears the whole notification (every missed-call row goes - design 12).
- **The open** (`lock/GlanceOpener.kt`, pure `glanceOpenStep`): a row's tap takes its target (`GlanceTarget`, memory only) and its content intent; under a time rule only when the rule leaves it usable (the app in the time lock's usable apps, missed calls when calls are managed and allowed - QA Q1, Kalender's reminder row when every rule allows Kalender - 2026-11-02; that row's tap also reads today afresh, `GlanceCalendar.refresh`, 2026-11-09 - `docs/notes/launcher/dagen-and-calendar.md`), else the plain keypad. The hint "Skriv koden for å åpne <app>" (label cut to 20 characters) / "... se tapte anrop". `pendingOpen` is set only in `PinLockActivity.unlock` (the right kid PIN or parent code; `lockWasRoot` read before the lock task stops) and opened only in `go()` - after Home (kiosk on), before the finish, only while `UNLOCKED`: kiosk root -> nothing (also through a hand-over); a time rule -> handed to the time-rule screen (`LockActivity.onResume` -> `openHandedOver`, within 10 s, re-checked with the pure `glanceUsableUnderLock`; Back returns there); kiosk on with no lock task running -> nothing; missed calls -> `missedCallsIntent`; not installed/suspended/hidden -> nothing; the conversation's newest activity content intent created by the app itself (QA Low 10) -> `send` with `VisibleSender.options()` (the one helper for VoipCalls, MusicSessions and the glance); else the launch intent; none -> nothing. A send that returns normally may still have been refused (`send` throws only for a negative result; `OnFinished` reports 0 for an activity): 1.5 s later (`glanceSendCheck`) Home or the time-rule screen back in front means refused -> the launch intent from it; else `markOpened`, but only when Home or the time-rule screen was below (kiosk on, or the hand-over). A launch is marked at once. Never a notification action, remote input, cancel, snooze or mark-as-read (the one cancel is a row swiped away on the page, r11 §3). **Every glance entry point catches `Throwable`** (reader, store and notifier listeners, the icon job, the panel, the opener; a panel that can't be built leaves the plain keypad): a glance fault must never trip the lock's crash guard (QA Medium 2).
- **The Meldinger rows** (design 77 step 5, §6.8; branch `sms-glance`; the engine's side: `docs/notes/launcher/calls.md`,
  "The lock glance's Meldinger row, step 5"): our own SMS conversations with unread messages, one row each
  (`badges.GlanceSmsRow`), read from the engine - never from our notification, which `GlanceReader` skips
  (`glanceCandidate`: never ours). `SmsHome.refresh` builds them on the engine thread (`sms/SmsGlance.kt`'s pure
  `smsGlanceRow`; `SmsHome.Summary.glance`, memory only); the panel listens to `SmsHome` between start and stop (a
  refresh that leaves the rows as they were renders nothing) and `shownRows` merges them by time (`glanceWithSms`):
  Dagen's reminder row first, then every other row - missed calls, apps' conversations, ours - newest first, each
  counting toward `GLANCE_MAX_ROWS`, the "more" line and the compact tile's rule like any row. The look
  (`lock/GlanceSmsRowView.kt`): the name as the message screens say it, "Ny melding" / "3 nye meldinger", the newest
  unread message's time; the contact's Home avatar with a teal message badge in the missed call's corner
  (`bg_glance_sms_badge`, Home's Meldinger teal), else the chat glyph on that teal - since design 82 D9 both are
  Meldinger's look (`GlanceSmsRow.look`: the parent's glyph, and the badge's fill recoloured with its ring kept;
  `docs/notes/launcher/home-and-photos.md`, "Meldinger's entry"); at Full text (`lock_glance.sms`,
  design 82 - `smsLockLevel`; no row at all at Off; `docs/notes/launcher/calls.md`, "The level") the
  text in the subtitle's place and the count after the name. The tap: `GlanceTarget.Thread` -> `GlanceOpen.THREAD`
  (`MessagesScreens.startThread`, the notification's way in), the prompt "Skriv koden for å åpne Meldinger" (the parent's name for it when set, design 82 W1 - the
  click label too), under a
  time rule only where the rule allows calls (`glanceUsableUnderLock`), handed to the time-rule screen as the others. A
  swipe or "Fjern": `SmsGlance.dismiss` (hidden in memory until a newer message, our notification of it cancelled
  only while the swiped row is still the newest - `smsDismissCancels`, code QA L2 - nothing marked read). TalkBack: one node, "3 nye meldinger fra Mamma, [text,] 14:05", click label "Lås opp og åpne
  Meldinger" (`glance_sms_action`). Tests: `SmsGlanceTest`, `LockGlanceTest`, `LockGlanceGuardTest`. Emulator-checked
  2026-11-09: `docs/testing/emulator.md` §6v, step 5.
- **The music tile alone, the player, the flashlight** (round 6, `docs/design/r6-launcher-small-items.md` §2,
  `41-flashlight.md` §1.3; the player r9 §3): a session or listening-window callback never re-renders the page -
  `GlanceCoalescer` folds a frame's callbacks into one `tileChanged`, which renders the tile alone by `glanceLayout`'s
  rule (since r11 nothing refits: a tile coming or going is plain layout, only the list's margin under it follows); the
  rows are rebound only on store/notifier changes. The full tile is the iPhone 16's lock player
  (162.8 dp in the model since 2026-10-26 - 4 dp under the controls, 6 above the progress row; 174.8 after r9, 233.8 before): a 48 dp cover, item and entry one line each, one progress row - elapsed, the 4 dp
  bar (0dp, weight 1), the time left with U+2212 ("1:26 ━━── −2:38", the pure `glanceTimes`, whole seconds so they add
  up; a live stream the elapsed time alone, the time left `GONE`) - and previous/play/next as plain `kid_ink` glyphs
  (48/56/48 dp, 28 dp apart, a 28 dp borderless ripple, `ripple_lock_media_control`, that stays inside the tile's 4 dp bottom padding - Android's borderless one, half the diagonal, was cut flat at the edge); the compact tile (one row) is unchanged and,
  since r11 §2, the tile with two rows or more. The times are one line, no ellipsize, `importantForAccessibility="no"` (the bar's description
  says "1:26 av 4:04"), in a fixed width: `MusicLockPanel.fixWidth` sets `Layout.getDesiredWidth` of the shape's
  widest text (`glanceTimeWidest`: every digit "0", the position's shape once it passes a stale duration, "0:00:00"
  without one) at the view's paint + 1 dp, only when the px differ - so the 1 s tick (`MusicLockPanel.tick`, bars and
  times only) never asks for a layout while the shape holds. Nunito's figures are tabular already (`tnum` a no-op). "Nødsamtale" sits centred in the bottom row `lock_glance_bottom` with the round flashlight button at its end
  (the row with its margin is 56 dp since 59d §8.13; 79.6 with the hint line, 116 with "Lås opp"; anchored below the
  scroller since r11, its 16 dp side margins the root's old padding); the button follows the `TorchPort` the lock passes (`glanceTorchButton`:
  hidden without a flash unit or after a `CAMERA_DISABLED` refusal while LOCKED), toggles the torch and opens nothing.
  The row sits 8 dp under the top block since 59d §8.13 (12 dp under "Lås opp" from 2026-10-21, 4 dp under the hint
  line in step J; the page's bottom padding stays 8 dp), and `LockGlanceLayoutTest` pins what scrolls at 473/497 dp
  over it; the button stays the music controls' dark disc (`bg_music_control`, `kid_core` glyph, about 11.6:1) while off and
  peach only while lit (`KidPaletteTest`).
- **The time rule's card** (design r13 step 1, `docs/design/r13-launcher-rule-card-and-time-left.md` §1, at the
  monorepo root): while a time rule or the used-up budget locks, one more card in the compact tile's style
  (`lock_rule_card`, `bg_glance_compact_tile`, 12 dp padding, 18 dp margin, 82 dp in `LockGlanceLayoutTest`'s model)
  right under the alarm line, before the music tile: the kind's glyph in the time-rule screen's 40 dp disc
  (`RuleCardKind.glyph`, the `lock_glyph_*` drawables), line 1 the rule's own name else "Skole"/"Leggetid"/
  "Skjermfri tid" (or "Skjermtiden er brukt opp"), line 2 "Til 14:30" only with a **certain end** (gone otherwise).
  Pure `lock/RuleCard.kt` (`RuleCardTest`): `ruleCard(reason, lock, end)` - null unless the enforced `lockReason` and
  the snapshot's decision both lock with the same reason -, `lockEnd(policy, lock, liftsAtEnd, budget, ledgerDay,
  zone, at)` - `TimeLock.until` only when it is one instant in the zone, not when a rule with usable apps ends today
  after the budget's time left runs out (qa-r13-rule-card-code Q-6) and `decideTimeLock` at it is unlocked with the rule
  lifts still in force then (`ruleLiftsAt`: a lift expiring before the end doesn't count) and the end day's budget
  (today's use only when the end is the ledger's day, else a fresh day's `budgetMinutesFor`); never for SCREEN_TIME -,
  `lockEndOf(snap)`, `ruleCardOf(reason, snap)`. The label is `ui/TimeLockModel.kt`'s `ruleLabel(kind, name)` ->
  `RuleLabel.Named`/`Kind`, resolved by `LockActivity.labelText` for both screens; the time-rule screen's "Til" line
  uses `lockEndOf` too (D2). **One snapshot per re-check**: `TimeRulesRuntime.recheck` computes it, hands it to
  `reevaluateLockReasonFromCache(context, snap)` and then to the lock listeners (`addLockListener`/
  `removeLockListener`, main thread) **before** `MissedCallNotifier.relearn`, so the panel lays the page out once. The
  panel listens between `start` and `stop`, keeps the model (`ruleCard`) and re-renders only when it changed;
  `resumeNow` computes its own snapshot only while `lockReason != NONE`, inside its own `try/catch` (a fault is no card,
  logged by class). `renderNow` binds only from the kept model. Layout: `glanceLayout(tile, rowCount, timer, rule)` -
  the card never counts toward the compact player (D4: rows only, like the timer tile); `placeList` puts the list
  14 dp under the card. Information only: never clickable, `screenReaderFocusable`, one TalkBack text ("Skole, til
  14:30", `glance_rule_card_description`), not data-sensitive, not dismissable, out of `swipeControls`. Not
  emulator-checked yet: design r13 §1.7.
- **The clock as Android's small clock** (design 59d step L, §8.8/§8.11): on a phone with a clock profile - pure
  `glanceClockProfile(model, manufacturer, density, widthDp)` -> `ClockGeometry?` (`LockGlance.kt`): `EMULATOR_PIXEL_CLOCK`
  for the emulator's `sdk_gphone*` by Google, `JELLY_STAR_CLOCK` (the pure AOSP emulator image's numbers, `AOSP_EMULATOR_CLOCK` - measured at the
  Jelly Star's profile, `docs/testing/2026-10-23-aosp-lock-look.md`; to confirm on the phone; its model matched ignoring
  spaces, underscores and case - unverified) for Unihertz's Jelly Star, `AOSP_EMULATOR_CLOCK` for the AOSP image
  ("Android SDK built for ..." by "unknown"), both only at 240 dpi **and** 320 dp wide (`GLANCE_CLOCK_PROFILE_DENSITY`, `GLANCE_CLOCK_PROFILE_WIDTH_DP`: the
  dp width is what makes the numbers fit, the density where they were measured; the code QA's JL-6); anything else
  `null` = today's centred clock, untouched, logged once (`Glance clock: no profile (model .., maker .., density ..,
  width .. dp)`); no PWA switch - the glance's clock, the date and the alarm line go
  left-aligned where Android's small single-line clock is: the clock's first digit's ink at `clockInkStartDp` (the box
  moves with the digit's side bearing, again at each text change) with its baseline `clockBaselineBelowBarDp` under
  SystemUI's keyguard status bar - never from the screen's top (the glance-topinset round, `docs/testing/2026-10-23-glance-topinset.md`:
  pure `keyguardStatusBarPx(statusBarPx, cutoutTopPx, density)` = the larger of the window's status-bar inset
  (`getInsetsIgnoringVisibility(statusBars())`), its cutout's and `KEYGUARD_STATUS_BAR_MIN_DP` 40 dp, SystemUI's
  `getStatusBarHeaderHeightKeyguard`; `profileBaselinePx`; a 36 px status bar gives 60 px, `handy36`'s pixel_7 cutout
  136) -, `clockDp` in dp (no font scale, as Android's), Nunito Light (300, `nunito_light`, bundled
  for this clock alone - JL-2: SemiBold's stems were twice Android's); the date Android's short one (`glanceDateSkeleton`: "EEEdMMM", capitalised on screen
  by a `TransformationMethod`, `glanceDateCapitalised`: "Fre. 23. okt.", "Fri, Oct 23") at `dateSp` with its baseline
  `dateBaselineBelowBarDp` under the bar; the alarm line under it at the date's start. The emulator Pixel's numbers
  (measured on `handy36` at `wm density 240`, `docs/testing/screens/59d-jl/clock-twin.png`, and on `jelly36`, the user's
  AVD's twin): ink at 34 dp, baseline 91.3 dp (137 px) under the bar - 273 px on `handy36`, 197 px under a 24 dp status
  bar -, 86 dp; the date's box at 32 dp, baseline 128.7 dp (193 px) under the bar, 16 sp; the AOSP image's vertical
  offsets are the same - the ink's left edge, baseline and digit height within 2 px of SystemUI's, the stems 8 px
  against Android's 7-8 (Nunito's digits are wider than Google Sans Flex's). `GlancePanel.placeProfile` (every fit) sets the
  margins in the window's coordinates (`baselineMarginPx`, `startMarginPx`): the clock's box may reach above the page's
  content while its digits' ink stays inside (`handy36`'s status bar is 136 px), the date may climb into the clock's
  descent, never above its baseline; the bar and both baselines are logged once per bar (`Glance clock: keyguard status
  bar N px, the clock's baseline at N px, the date's at N px`). One clock size, placed at every layout
  (JL-3: the clock keeps Android's place); since r11 nothing gives way - the page scrolls, and the tile follows the row
  count alone (music + 1 row full, + 2 compact; the user's case, the profile clock with the alarm line and music + 2
  rows, is 370.6 dp in the 401 dp scroller; `LockGlanceLayoutTest` models both clocks).
- **The clock on the lock** (design 42, `docs/design/42-lock-clock.md`): the catalog's clock app is Fossify Clock, known
  by package and pinned signer (`lock/ClockApp`, `verified` refreshed off the main thread at start and on its package
  changes). `PinLockRuntime.clockPackages()` (the system clock - a system `ACTION_SHOW_ALARMS` handler - + Fossify while
  verified) opens the alarm exemption (`rememberAlarm` takes the set) and, with the kiosk off, `pinLockHelpers(clockApp)`
  lets its alarm screen up (with the kiosk on, the plan pins the allowed alarm apps during a rule too - bug 2026-11-02,
  hardening.md). **The alarm's yield** (bug 2026-11-02, a2): `alarmNow` = `alarmRingingNow` - a clock app's ringing
  notification (`badges/AlarmNoticeReader`, pure `AlarmNotices`/`AlarmPost`/`ringingAlarmShape` in `RefrontPolicy.kt`:
  category alarm with a full-screen intent - a snoozed/upcoming/missed one has none; not "ongoing", code QA H1: Fossify
  rings from `startForeground` without `setOngoing`; key, package, `when` and those facts only) holds it while posted,
  at most `ALARM_NOTICE_MAX_MS` (15 min, code QA L3: the yield steps aside for any front task while it lasts) from
  first seen (a re-send never extends it - E7 code QA 2: the PIN-free yield stays bounded; a ring past it is reached
  through the PIN, below); else, as before, `ALARM_RING_MS` (3 min) after the alarm's time (no listener access, another clock's
  shape, a denied `USE_FULL_SCREEN_INTENT` - API 34+ strips the intent then). The lock back in front after an "alarm"
  yield spends the ringing keys (`afterResume`, as `alarmAfterResume`); a snooze's re-post or a removal clears a key,
  and a post with a new `when` is a new ring (code QA L1: Fossify rings every alarm under one key), so it yields again.
  AOSP's and Google's ended timer has the same shape and yields too; Fossify's ended timer (`CATEGORY_EVENT`, no
  full-screen intent) doesn't - it is the glance tile's Stopp. **The ringing screen brought back** (gap closed
  2026-11-02: the kid presses power while it rings, the screen-off's lock-up covers the ringing screen):
  `PinLockActivity.onResume`, and every screen-on while it is resumed (`PinLockRuntime.addScreenOnListener` - no
  polling in the dark, code QA L6), posts `alarmScreenSend` after `ALARM_SCREEN_SETTLE_MS` (1.5 s; `onPause` drops it -
  the platform's own launch of the full-screen intent, an unlock). `PinLockRuntime.sendAlarmScreen` - pure
  `alarmScreenStep` (LOCKED, screen on, no phone call or emergency flow; held for `LATER` while the keypad has digits,
  code QA N4, or the keyguard cover's keyguard must go first, `KeyguardCover.mustWait` as 17b, N3) and
  `AlarmNotices.screenKey` (a clock app's with its screen kept - any such ring, not only the newest of all -, inside
  the yield's 15 min for the PIN lock (`inYield`: its send opens the yield), under `ALARM_SCREEN_MAX_SENDS` = 5
  re-sends per ring **per screen-on** - E7's M1: `AlarmNoticeReader.screenOn` at every screen-on starts the count
  afresh, so a power press always brings it back and only a lock and a clock screen bouncing within one screen-on
  stop; the newest; spent or not) after `AlarmNoticeReader.screenKey` asked the listener for exactly the kept keys (`reconciled`:
  Stop's removal makes it nothing; no listener answer, nothing) - sends that notification's full-screen intent (the
  reader keeps only that PendingIntent, only of the lock's clock apps, never extras, text or actions; sent only when it
  is an activity intent a clock app created, code QA N1/N2) from the visible lock with `VisibleSender.options()`, as one
  of our own starts (`noteOwnStart`). A sent one opens the yield again (`resent`), so the screen that comes up is
  yielded to and Stop/Snooze brings the lock back; if the lock is still in front `ALARM_SCREEN_CHECK_MS` (1.5 s) later,
  nothing came up and that ring's yield closes again (`alarmScreenCheck` -> `spent`, code QA L2, as 17b's
  `VOIP_FSI_CHECK_MS`). The lock's own dialogs hold it (no retry until the next resume or screen-on). Both modes (own
  and Android's face). **After an unlock** (E7's M1: the time-rule screen didn't bring it back, the shade shut by the
  kiosk, Recents empty - it rang until the clock gave up): `LockActivity`'s resume posts the same send after
  `ALARM_SCREEN_SETTLE_MS` (`sendAlarmScreen(.., timeRuleScreen = true)`: in charge while not LOCKED, the same count,
  no time bound, no yield and so no check; a pause drops it; a ring the time-rule screen - any instance, Home starts a
  new one - already sent this screen-on, or the kid opened from its row, isn't sent from there again
  (`AlarmNotices.timeRuleSent`, cleared at the screen-on and with the ring; code QA 3, E7b's L1: the kid back from the
  ringing screen by Stop, Back or Home lands on the card, and a clock slow to drop its notification never gets its stale
  screen again), and while an allowed clock's alarm rings unsilenced (`PinLockRuntime.alarmToStop`:
  `AlarmNoticeReader.openable`, not `silencesAlarms`) the card's first row is "Slå av alarmen" (`LockRow.Alarm`, the
  alarm glyph), whose tap sends the ringing screen uncapped (`openRingingAlarm`, `screenKey(.., capped = false)` after
  the listener's lookup - a tap can't loop). The row never opens by itself after an unlock (`unlockOpenRow`, code QA
  4: the settled send does it, once, with its waits). The reader tells its listeners when the kept ringing screens
  change or the listener connects or goes, and `ZenRuleRuntime` when `silencesAlarms` flips, so the row comes and goes
  live. **Without the notification listener** (no access - no device-owner API grants it - or not yet rebound after an
  update or a restart; code QA 1) nothing sees a ring: the card offers the lock's clock apps as app rows
  (`lockRowPackages`, through the openable-apps filter: only a launchable clock that isn't suspended, i.e. one the rule
  spares as an allowed alarm app) - the clock app itself is the way to its alarm. The yield stays the PIN lock's alone. **Device check**: `dumpsys notification --noredact` while Fossify rings and while the Jelly
  Star's own clock rings (category, flags, `fullscreenIntent=`) - the shape must match both; and (revision R1) `when=`
  stays the same across two dumps of one ring on the Google/OEM clock (an update with a new `when` would read as a new
  ring: fresh bound, re-send count reset). Revision R2: a resume with a re-send outstanding and no "alarm" yield in
  between (the screen came up and closed at once) spends that ring (`alarmSendSpentAtResume`). Revision R3: a ring
  already up at the process start was read before the clock set was known - once `systemClocks` is resolved, and when
  Fossify becomes verified, `AlarmNoticeReader.clocksKnown` asks the listener again for the clock apps' ringing keys
  without a kept intent (`screensMissing`). A silent time rule's Do Not Disturb that silences alarms (server 0121,
  `ZenRuleRuntime.silencesAlarms`) makes none of this run: `alarmRingingNow` is false and `alarmScreenStep` NONE
  while it holds, read live (`docs/notes/launcher/time-rules.md`, "A rule's Silent"). The next alarm under the date (`lock_glance_alarm`, pure `glanceAlarm`, weekday past 24 h;
  always shown while there is one, r11). `badges/ClockReader` reads only Fossify's three channels (`clockKind`: running timer,
  stopwatch, ended timer) - title and text into `ClockTile.parse`, and an ended timer's single broadcast action
  (`clockStopAction`, never an activity); `lock/ClockLockPanel` shows `lock_timer_tile` and moves the time locally
  (down, or up for the stopwatch); an ended timer shows "Tiden er ute" and "Stopp", sent only by `lock/ClockActions`
  with background starts denied. The tile's tap is `GlanceTarget.ClockApp` through the keypad and the opener's launch.
  the timer tile always full while one runs (r9's `glanceFit(..., clock)` kept it before the music tile's size; r11
  scrolls instead); each tile's
  callbacks render that tile alone (`musicDirty`/`clockDirty`). Fix 2026-10-21 (an empty "Timer" card that hid the
  stopwatch): the reader keeps no group summary (Android 16 autogroups an app's second ungrouped notification under a
  system summary on a child's channel with no title or text, `config_autoGroupAtCount` 2) and no `ClockTile.blank`
  tile, `clockShown` never shows a blank one; on each of Fossify's posts and removals and at the glance page's resume
  (`ClockReader.reconcile`) the kept keys are checked against the listener's `getActiveNotifications(keys)` (only those
  keys; `ClockReader.attach` at connect, pure `clockStale`), so a missed removal can't keep a tile up; the stopwatch's
  small text goes when it repeats the label (`clockSmallText`).
- **The notice wake** (launcher round 7 §3, `docs/design/r7-launcher-small-items.md`, the parent's per-phone switch `launcher_ui.lock_wake_on_notification`, missing = off - `LauncherUi.lockWakeOnNotification`, `PolicyResponseCompatTest`): `PinLockRuntime.noticeWakeOn` (memory) from the `Ok` cached policy at process start (`noticeWakeFromCache`, never over a sync's) and every accepted sync (`noticeWakeSwitch` in `MdmSyncWorker`). Triggers, both carrying nothing but "one came" (`PinLockRuntime.noticeCame()`, main thread): `GlanceReader.posted` -> `GlanceStore.putPosted` when the post alerts (pure `badges/GlanceWake.kt` `glanceAlerts`: importance >= DEFAULT, `matchesInterruptionFilter`, not `FLAG_ONLY_ALERT_ONCE` on a key already kept; no ranking = no) and `glanceWakes` (not suspended, the visible rows gain a conversation - a newer message in a shown row is no wake); never a sweep, ranking update or policy re-read; and `MissedCallNotifier`'s alerting post (`step.alert`, after a post that worked, `main.post`) - never the boot re-send, `relearn` or an update after a call. Since design 77 step 5 also our SMS notification's post (`SmsNotifier.notify` -> `wakeIfAlerts`, after the post, decided on the main thread) when pure `smsPostAlerts` says it alerts - round 7's conditions for an app's notice, for ours: an accepted message's own post (never a held or blocked message, the reply's "not sent" update, a group move's quiet re-post, or a message read in the thread in front); not silent (a time rule without calls, a picture told again); no Do Not Disturb at all (`currentInterruptionFilter == INTERRUPTION_FILTER_ALL`, fail closed - a silent rule that allows calls lets no message through, code QA H1); notifications on and the channel at DEFAULT or above; and the lock's rows gain a row (`smsGainsRow`: the conversation's only unread message, or the first after its row was swiped away - `SmsGlance.comeBack` spends the mark; a newer message in a row already there is an update, code QA M1). The gate is pure `lock/NoticeWakePlan.kt` `noticeWakeAllowed` (switch, LOCKED, dark, not `NoticeBusy` - our/Telecom/VoIP call, emergency flow, ringing alarm, the boot mark, the first 2 min of the process -, no lock-up in the last 2 s - `lockUpAtElapsed`, stamped in `lockUp` -, `WAKE_GAP_MS` 30 s, `WAKES_PER_HOUR` 20, in memory), then one proximity read (`lock/NoticeProximity`, `TYPE_PROXIMITY`, 300 ms, a 1 s partial wake lock; near = no wake, no sensor/no answer = wake) and the gate again, then `show(app, wake = WakeKind.NOTICE)`: `show`'s `wake` is a `WakeKind?` (`VOIP`/`NOTICE`) - the lock's start, then `VoipWakeActivity.intent(context, kind)` on top of it (never alone); a NOTICE wake activity never calls `voipWakePaused` (`wakeKindOf`, 17b's try detection stays the ring's). `NOTICE_WAKE_ON_MS` 15 s later, untouched (`PinLockActivity.onUserInteraction` -> `noticeWakeTouched`), the screen still on, LOCKED, the lock resumed, no call, none of the lock's dialogs open (`PinLockRuntime.lockDialogsOpen`, kept by `PinLockActivity`: their touches never reach `onUserInteraction`) and TalkBack's touch exploration off: `DevicePolicyManager.lockNow()` turns the screen off (pure `noticeWakeOffDue`, qa-l7-b-code 2) - that screen-off is an ordinary lock-up (HOME_THEN_LOCK: `show` cleared `lockOnTop`). That 15 s end is own mode's only: as Android's face there is none (design 59d step E, `noticeWakeOffDue(.., face)`; the 59 bullet above) - the phone's own screen timeout ends the wake, and its lock-up is LOCK (step C). The timer is armed only when `show` returned true; a screen-off, or a screen-on more than 2 s after the wake (`noticeWakeEndsAtScreenOn`: the kid's own power press), ends it (qa-l7-b-code 3). The turn-screen-on activities stay exactly `InCallActivity` and `VoipWakeActivity` (`PinLockManifestTest`). Tests: `LockWakeTest`, `GlanceWakeTest`, `PinLockManifestTest`, `LockGlanceGuardTest` (the reader's new identifiers). Device checks: the design's §3.3.
- **The notice buzz** (design 82 W3, `docs/design/82-meldinger-as-an-app.md` §9 and §14; the parent's per-phone switch `launcher_ui.lock_buzz_on_notification`, missing = off - `LauncherUi.lockBuzzOnNotification`, `PolicyResponseCompatTest`): `PinLockRuntime.noticeBuzzOn` (memory), set where the wake's switch is (`noticeWakeSwitch`, `noticeWakeFromCache`). The same triggers as the notice wake - `noticeCame()` reads `NoticeBusy` once and calls `noticeBuzz` first, before the wake's gate and its proximity read, so the wake switch, the lit screen and a pocket change nothing for it; `noticeBuzz` answers from memory first (the switch, LOCKED, the gap - `noticeBuzzGapOver`) and reads the ringer and the filter only then. The gate is pure `lock/NoticeBuzzPlan.kt` `noticeBuzzAllowed`: the switch, LOCKED, not `NoticeBusy` (the wake's: a call, a ring, the emergency flow, a ringing alarm, the boot mark, the first 2 min of the process), the ringer Normal or Vibrate, the interruption filter "all" (no Do Not Disturb of any kind), no silent time rule (`ZenRuleRuntime.ruleSilent()`: asked for or in force, also where our rule couldn't be switched on) and `BUZZ_GAP_MS` 10 s since the last buzz (memory); a ringer mode or a filter that can't be read is no buzz. Then one `VibrationEffect.createOneShot(NOTICE_BUZZ_MS, DEFAULT_AMPLITUDE)` (40 ms, the SMS reaction buzz's length) on the default vibrator with `USAGE_NOTIFICATION` attributes, so Android's own vibration settings apply; no sound, no notification, nothing of the notice in the log ("Notice buzz"); it never throws. An app or Meldinger set to Off never reaches `noticeCame()` - it gains no row. Tests: `LockWakeTest` (the plan and the order by source). Not seen on a phone yet (2026-11-10): the buzz itself, and that nothing else vibrates for the same notice.
- **Not device-tested**: design 25 §9 and its "Implementation status" checklist (Jelly Star: Element X's shape and its room opening from the lock in both kiosk modes, suspension during bedtime, the emulator console capturing a secure window, the API 34/35 send, TalkBack).
- **Weather on the glance** (design 75 step 2, `docs/design/75-lock-weather.md` §5): pure `weather/WeatherModel.kt`
  (`WeatherModelTest`): `weatherLine` (the answer's entry whose hour holds the clock - D2; none when the switch is off,
  the status isn't `ok`, MET last answered more than 3 h ago, or no entry holds now; unknown symbols, word pairs and
  card dates left out one by one), `weatherPullDue` (55 min, the 5-min try gap, the R1 nudge gap; never off),
  `weatherPolicyStep`/`weatherKeep` (off clears; on or a location-mode change clears and pulls; `unavailable` and a
  failure keep), `weatherFold`, `weatherNowShown` (1 s, the card's generation, no finger on the rows), the numbers
  (U+2212, never "−0°"), `weatherCard`, `weatherPlace`/`weatherWordsFit` (§5.3's budgets). The words:
  `weather/WeatherWords.kt` (a `when` table; `WeatherContractTest` checks every pair of the contract's `forms` in both
  catalogs). The hour of a `from`: `ClockFormat.weatherHour` (nb "14", en "14:00", 12 h "2 PM"). The store:
  `weather/WeatherStore.kt` (memory + CE prefs `weather`; a `generation` bumped by every clear, so a pull begun before it keeps nothing; the policy's switch and location mode from every accepted
  sync and the `Ok` cache at start). The pulls: `weather/WeatherPull.kt` - at the end of every sync before the
  self-update commit, at screen-on, at once on a policy step; one in flight, a 15 s call timeout, everything caught;
  only `createMdmApi` (`WeatherGuardTest`). **R1** (a zone's save or delete only nudges): `SyncRunner` marks a sync
  for reason `sse` and that sync pulls when the last try is 3 min old (`WEATHER_NUDGE_GAP_MS`). The glue:
  `lock/WeatherLockPanel.kt` under `GlancePanel` - the two mark instances (`lock_glance_weather` in the date row
  `lock_glance_date_row`, `lock_glance_weather_line` under it; `view_lock_weather_mark.xml`), the card
  (`lock_weather_card`, `view_lock_weather_card.xml`, before the rule card), the place (its rule since the calmer glance - its bullet below) decided at a lock-up, a refit,
  a width change and the first bind (hidden until known), the card folded by every lock-up, the stage leaving the page,
  `stop`, the VoIP card, the line or the card going; the nowcast asked at an open (3 s), inserted within 1 s, asked at most every 2 min from an ask's start whatever it brought and never while one runs (`weatherNowAsk`; a failure keeps the last good answer; the ask is cancelled at a fold or `stop`); the
  mark's 48 dp hit area a `TouchDelegate` on `lock_glance_top` (pure `weatherHitRect`: the top capped at the clock's
  baseline, the rest below - the design review's L1); the own line's room held through a screen-on pull when a line
  had gone stale (L2). `LockClockFace` gained `dateRow` (placed and baselined as the date) and `shortDate(on)` (the
  no-profile date short while the switch is on, D14). `GlancePanel`: `placeList` counts the open card, `fitNext`
  measures without it. Capability `lock_weather_v1`. Licences: `legal_info_weather` + `assets/licenses/CC-BY-4.0.txt`.
  No place name since 2026-11-04 (the user: "Vi dropper navnet"; design 75 §13): the card's foot is the low and high
  alone ("8° / 13°", the dark text beside it as before), TalkBack names no place, the DTO has no `place` (an older
  server's is an unknown key, ignored; `WeatherContractTest`), and E8's L3 re-pull of a place-less answer after 2 min
  is gone. The evening card (2026-11-04, design 75 §14): from 18:00 to midnight an entry's `tonight` makes the card
  "I kveld" | "I morgen" (`WeatherCard.tonight`; the layout's two groups `weather_group_0`/`_1` with their heads and
  the `weather_card_split` line, weighed 1 | 2 so a plain card's three columns stay equal; the foot
  `weather_low_high_tomorrow`).
  Not emulator-checked yet: `docs/testing/emulator.md` §6x.
- **The calmer glance** (2026-11-04, branch `lock-glance-calm`, PLAN "A calmer lock glance and \"I morgen\"", the
  user's mockups boards A-D: fewer lines, closer to Android's own lock screen; the expanded weather pushes the lines
  down; the alarm only in the evening):
  - **(A) The date line**: "Ons. 4. nov. · [symbol] 12° ⌄" - the date, the quiet " · " (`lock_glance_weather_sep`,
    `@string/glance_separator`, the date's font, colour and size, shown with the mark) and the weather mark beside it
    (`lock_glance_weather`, still the card's tap target; its 48 dp `TouchDelegate` unchanged). The weather off or not
    there yet: the date alone.
  - **(B) The place** (D15 rewritten; pure `weatherPlace`/`weatherWordsFit`, `WeatherModelTest`; `BESIDE_WORDS` is
    gone): its own line (`lock_glance_weather_line`, "[symbol] 9° · Regn fra kl. 14 ⌄") only when the weather has words
    to say now - the line's `WeatherWords` key, or with no line yet (the screen-on pull's held slot) the answer's entry
    for now - and they fit there; else beside the date when the date, the " · " and the core fit the line; else its own
    line without words (a narrow phone, a large font: never a cut date). Still decided only at a lock-up, a refit, a
    width change and the first bind; words that come while it is beside the date wait for the next lock-up.
  - **(C) The card** (`lock_weather_card`) sits right under the weather's line - after the date row and the own line,
    before the alarm's own line and "Neste" - so opening it pushes those and the rest down. A line after the open card
    keeps 8 dp from it (`GlancePanel.spaceUnderCard`, `CARD_LINE_GAP_DP` 6 + the line's 2); `cardHeight` counts both
    margins, so `fitNext` still measures without the card.
  - **(D) The alarm**: pure `glanceWakeAlarm` (`lock/ClockTile.kt`, `ClockTileTest`) - only from 18:00 local time
    (`day.GLANCE_EVENING_HOUR`), only an alarm that rings before noon the next day (`WAKE_ALARM_BEFORE_HOUR`), shown
    through midnight until it rings; between noon and 18:00 none, an alarm at noon or later never (formally: before
    noon of the day after the latest 18:00 at or before now, in the phone's zone). On the date line as
    " · [alarm glyph] 07:00" (`lock_glance_date_alarm`; `app_glyph_alarm` as an `ImageSpan` at 1.1 x the text, the
    date's ink) when the line holds it after the date and the weather (pure `glanceAlarmBeside`: the row's other shown
    parts measured unbounded plus the alarm's desired width, against the line's room - the weather's place is decided
    first and never counts the alarm); else the alarm's own line `lock_glance_alarm` ("[glyph] 07:00"). TalkBack
    "Vekkerklokke 07:00" (`lock_alarm`; en "Alarm 07:00"). `LockClockFace.placeAlarm` runs at every bind, every layout
    (a change skips that frame) and every weather change; `bindAlarm` runs again at each minute of the clock (the 18:00
    and noon edges with the screen on). The time-rule screen (one face, round L6) has the same rule, its alarm on its
    own line with the glyph.
  - **"I morgen"**: pure `glanceNext(today, policy, now, zone)` -> `GlanceNext(row, tomorrow)` (`day/Reminders.kt`,
    `RemindersTest`): today's next timed event as before; once today's last timed event has started (an ongoing one
    has) and it is evening, tomorrow's first timed event - "I morgen 09:00: Gym" (`glance_tomorrow`; en "Tomorrow 09:00: …"; the
    time first since 2026-11-09, as "Neste 17:30: …", so a long title is cut, never the time);
    all-day never, nothing when tomorrow has none, "Neste" again after midnight. The same switch `glance_next`.
    `GlanceCalendar` reads tomorrow (`CalendarStore.dayBlocking`, the rules applied) only while the switch is on
    (`TodayCalendar.tomorrow`); `nextReminderBoundary` adds the evening's start and the next midnight while it is on.
  - **The fit**: `glanceNextFits` and the third-row check are unchanged and read the laid-out page; with the alarm and
    the weather on the date line the worst model page (the profile clock, music + 3 rows, 3-button navigation) keeps
    the "Neste" line, 22.4 dp of row 3 in sight (`LockGlanceTest`).
  - **Not device-checked**: the date line's widths on the Jelly Star at font 1.0/1.3/2.0 (when the weather or the alarm
    falls to its own line), the glyph's centring on the baseline, the 18:00 and noon edges with the screen on, the
    card's push and its 8 dp, TalkBack on the date line.
