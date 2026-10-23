# 59d - Android mode: what our own lock still does, and what it should stop doing

Status: audit, 2026-10-22, read-only (no code changed). Base: `main` at `d88509ae` (design 59 steps 1-4, 59b written
but not built), and `fix-f3-boot-cover` at `2483caf8` for the boot cover. Launcher paths are under
`launcher/app/src/main/java/com/kidslauncher/mdm/` and `lock/` unless named.

The user's direction (2026-10-22): keep our own lock face (the hybrid), get rid of SystemUI's clock screen, make it
feel good, and take out the inefficiencies we built to steer things ourselves. **The principle for android mode:
Android gates, we only draw the face and curate rows; we never fight the keyguard.** A start of ours that isn't
`showWhenLocked` while Android is locked un-occludes the keyguard (SystemUI's face). A start onto our own occluding
glance only causes a pause and a resume. Neither is ever needed for security in this mode.

## 1. The user's recordings, traced to code

| Recording | Cause in our code | Fix |
|---|---|---|
| R1. After the PIN on Android's pad, the glance flashes our keypad's chrome | "Lås opp" moves the glance to our KEYPAD stage (`PinLockActivity.kt:262-266`). As the face, only the keys, the dots and "Foreldrekode" are hidden (`:940-943`); the prompt, the back arrow and the Nødsamtale pill stay. From the bouncer's dismissal until `go()` (`:1046-1051`) has started Home (animated, `HomeFront.kt:35-39`) and finished the task, that stage is on screen. | Step B |
| R2. One frame of Android's face at screen-on | 59b §6 lists the platform candidates. Ours: (a) the ScreenOn backstop starts the glance at **every** wake. `lockShowing` is `lockResumed` (`PinLockRuntime.kt:837`), which is always false at a screen-on, so `PinLockState.kt:173-177` says show, and `show()` starts the glance onto itself (REORDER_TO_FRONT, `:1402-1411`: a new-intent pause and resume of the occluding activity). (b) A not-locked read at the screen-off still gives HOME_THEN_LOCK (`PinLockRuntime.kt:685-690`, 3 of 107 lock-ups in the RC). (c) A time-rule edge at screen-on starts `LockActivity`, which isn't showWhenLocked (`TimeRulesRuntime.kt:236-237`). | Steps C, A; measured by 59b step 4 |
| R3. Three starts at a VoIP ring | SystemUI launches the FSI. We then start the glance and `VoipWakeActivity` (`PinLockState.kt:208-212`, `PinLockRuntime.kt:613`, `:1413-1418`). Then the 17b send moves the app's activity again (`:1041-1051`). | 59b step 1 |
| R4. Our Home over the app the kid unlocked into | `USER_PRESENT` -> `presentLeave` -> `leave()` -> `lockLeave` gives `homeFirst` (`LockTaskRoot.kt:74-79`, `PinLockActivity.kt:1031`, `:1048`). | 59b step 0 |

## 2. Inventory: our own lock's behaviours still running in android mode

"Keyguard?" = whether Android's secure keyguard now provides the behaviour. Verdicts: **KEEP**; **REMOVE** (in android
mode, because the keyguard does it and ours only adds flicker or races); **CHANGE** (a reduced form).

### 2.1 Front, re-front and screen events

| Behaviour (where) | What it did for the own lock | Keyguard? | Verdict |
|---|---|---|---|
| Re-front loop: 0.5 s, 2 s, then every 5 s, never giving up (`RefrontPolicy.kt:64-97`; runtime `:1465`, `:1482`, `:1545-1600`) | Covered anything that came over the lock | Yes for safety. SystemUI's face shows instead, and today the loop stops at `:80` | **CHANGE**: 59b step 2's `androidLossStep` (at most 2 tries per screen-on, `NO_USER_ACTION`). The never-give-up escalation is own mode only |
| Exempt screens: our call, Telecom, emergency flow, alarm, VoIP incl. IN_CALL (`RefrontPolicy.kt:85-94`) | The loop didn't fight them | n/a | **KEEP** as 59b's exemptions (Q59b-2: no re-front during a call) |
| SOS guess: a stop within 3 s of a screen-on (`SOS_GESTURE_WINDOW_MS`), the 700 ms pause grace, the 15 s yield (`RefrontPolicy.kt:186-241`, `PinLockRuntime.kt:1523-1531`) | Let the SOS countdown stand | SOS is showWhenLocked. The guess fires for *any* stop (RC step 3's "probably Emergency SOS" after DeskClock) | **CHANGE**: only as `androidLossStep`'s `sosFlow` exemption (59b re-fronts, so SOS needs it). Step D |
| SOS lock-task narrowing (`narrowForSos` `PinLockRuntime.kt:1534-1543`, `LockTaskChrome.kt:82-96`) | qa-25b-25f H1: only SOS could stay up during the yield | Yes: anything still over the glance is showWhenLocked or SystemUI's face, both safe | **REMOVE** (step D) |
| Lock-up HOME_THEN_LOCK (`LockUpPlan.kt:40-47`; `PinLockRuntime.kt:703-712`) | Home's black snapshot under the lock, so no app flashes | Yes: nothing that isn't showWhenLocked is drawn under a locked keyguard | **REMOVE**: always LOCK in face mode, even after a not-locked read. The sleep check relocks then (step C) |
| Lock-up LOCK / NONE (`lockOnTop`, `LockUpPlan.kt:91-97`) | Start the lock while dark, or nothing when it is on top | No: the glance must be the top activity before the wake, or SystemUI's face shows | **KEEP** |
| ScreenOn backstop start (`PinLockState.kt:173-177`; `newIntentPauseKept`, `LockUpPlan.kt:113-114`) | A lock-up that lost the race to the wake | Our occluding glance is already there, so the start only pauses it | **REMOVE** in face mode (step C). 59b's re-front covers a real loss |
| `showLockLater` / boot fallback (`PinLockRuntime.kt:621-628`) | Home first at boot, the lock 1 s later | The face's first CE start is UNLOCKED (`PinLockState.kt:149-153`) | **KEEP** (inert in face mode) |
| `TimeRuleShown` -> show the lock (`PinLockState.kt:225`, `LockActivity.kt:287-291`) | The PIN first, the rule screen behind it | No: `LockActivity` isn't showWhenLocked, so its start un-occludes, then we re-front | **CHANGE**: no start while Android is locked. `afterUnlock` starts it (`PinLockRuntime.kt:1742-1751`; step A) |
| Process start LOCKED with the screen on (`PinLockState.kt:149-160`) | Fail closed after a crash | The keyguard is the gate, and the resume's rule relocks | **KEEP** |

### 2.2 Leaving, Home, lock task

| Behaviour (where) | What it did | Keyguard? | Verdict |
|---|---|---|---|
| `HomeFront` after the lock leaves (`LockTaskRoot.kt:74-79`, `PinLockActivity.kt:1048`) | Home above any latent task (BlockedAppActivity, stock Recents) | Yes after an Android unlock: nothing latent is exposed | **CHANGE**: 59b step 0 (no Home when the glance isn't in front). For "Lås opp", Home stays (design 32 decision 2) unless the user picks Q1. Unanimated, with the glance hidden first (step B) |
| Home to root lock task at the glance's resume (`PinLockActivity.kt:756-759`, `lockTaskEntry` `LockTaskRoot.kt:42-55`) | Kiosk on: Home is the root, never the lock | A Home start while Android is locked is behind the keyguard | **CHANGE**: START_SELF in face mode; Home re-roots at the unlock (step A). [device: the prototype's shell start of Home was refused with "brought to the front", focus stayed on the glance] |
| Other Home starts: the relock (`server/WindowRelock.kt:727`), install mode's end (`play/PlayRuntime.kt:233`), the update (`server/SelfUpdate.kt:348`), a role change (`server/AppEnforcer.kt:756`) | Home in front after a policy edge | They can land while Android is locked under the glance | **CHANGE**: wait for the unlock (step A) |
| Lock task while LOCKED, features minus HOME/OVERVIEW/NOTIFICATIONS (`LockTaskChrome`) | The kiosk over the lock | No: its status-bar flags still hold the shade and QS over SystemUI's face (RC step 11; assessment §4.7) | **KEEP** |
| Status-bar heal (16d: `PinLockRuntime.kt:752-764`, `:907`, `:1457`; `StatusBarHealPlan.kt`) | SystemUI losing lock task's flags | No | **KEEP** |
| `setStatusBarDisabled` (`LockTaskChrome.kt:332-346`) | Backstop | Only recorded inside lock task, and no effect on the lock screen (assessment §4.8) | **KEEP** (costs nothing; phase D candidate) |
| `DISALLOW_CREATE_WINDOWS` while LOCKED (`LockTaskChrome.kt:348-357`) | No overlay or toast over the lock | Partly: an overlay can still draw over an *occluding* activity | **KEEP** (no flicker; the budget sets it too) |
| `GlanceOpener` (`PinLockActivity.kt:1049`) | A row's app opens after the PIN | No | **KEEP** (with 59b step 0's `homeBelow` false) |
| F2: a window edge's chrome pass cancels an open pad (RC F2) | n/a | n/a | **CHANGE** (optional step G): hold the lock-task write while an ask is open, at most `ANDROID_ASK_TIMEOUT_MS` |

### 2.3 The keyguard, the pad, the PIN

| Behaviour (where) | What it did | Keyguard? | Verdict |
|---|---|---|---|
| `KeyguardCover`'s COVER mode, its dismissal and checks (`KeyguardCoverPlan.kt:58`; `PinLockActivity.kt:148-240`) | Design 32 step 2's Swipe cover (debug switch only) | Inert in release (`KeyguardCoverDebug.enabled = false`) | **REMOVE** (phase D). Its `whenUnlocked` queue (`KeyguardCover.kt:425-443`) is reused by step A |
| `setKeyguardDisabled` and the one keyguard writer (`KeyguardCover.kt:525`; ANDROID `KeyguardCoverPlan.kt:56`, `:195-234`) | Own mode: keyguard off | ANDROID writes `false`, the features and T, and reads them back | **KEEP** the ANDROID write |
| Our KEYPAD stage under Android's pad (`PinLockActivity.kt:259-266`, `:940-943`) | Our keypad | Android's pad | **REMOVE** in face mode: ask from the glance page (step B; R1) |
| `PinBackoff` (`PinLockRuntime.kt:1682-1708`) vs Android's lockout | 4 free tries, then 30 s to 15 min | Android's Gatekeeper: 5 tries, then 30 s (RC step 5), enforced in the TEE | **KEEP** until phase D: used only by the keypad migration and `OWN_KEYPAD` (no credential) |
| Parent code on the lock (`PinLockActivity.kt:943`, ring card `:593`) | Foreldrekode | The parent types the kid's PIN on Android's pad (answer 2b) | Already **REMOVED** in face mode |
| Sleep check and the resume's wait/RELOCK (`PinLockRuntime.kt:856-864`; `AndroidLockPlan.kt:92-101`) | n/a (design 59's own) | It is the gate's own check (0 false relocks in 107 lock-ups) | **KEEP** |
| `WindowRelock` / 24b relock | Removes a window's credential, then the signer, proxy and CA checks | Step 1 is skipped while requested or owned. The rest isn't about the lock | **KEEP**, except its Home and time-rule starts (step A) |
| Crash guard (`RefrontPolicy.kt:157-184`, `PinLockActivity.kt:439-446`) | Own: the lock off after 3 crashes | Face: `lockNow` (`AndroidLock.kt:398-402`), SystemUI's face with the PIN | **KEEP** |
| Readers of `isKeyguardLocked`/`isDeviceLocked`: `AndroidLock.kt:351-353` (`faceStep`, `refrontHeld`), `KeyguardCover.kt:465-466` (the lock-up's `kg`, `mustWait`), `timerules/ScreenTimeTracker.kt:70`, the log line `AndroidLock.kt:444` | n/a | They decide only stay, ask, wait or relock, and never open | **KEEP**. `refrontHeld` is replaced by 59b's `androidLossStep`; `mustWait` extended (step A) |

### 2.4 Calls, camera, wakes, boot

| Behaviour (where) | What it did | Keyguard? | Verdict |
|---|---|---|---|
| VoIP ring: our glance and `VoipWakeActivity` (`PinLockState.kt:208-212`) | The ring's wake and our card | Yes when the ring has an FSI: SystemUI launches it and wakes the screen | **REMOVE** for an FSI ring; keep it for the card (59b step 1) |
| 17b ring-screen send (`PinLockRuntime.kt:1041-1051`, `VoipCalls.kt:344`) and its try detection (`VoipCallPlan.kt:198-293`, `PinLockRuntime.kt:1053-1076`) | The app's own ring screen over our lock | Yes: the platform's FSI | **REMOVE** except 59b's one heads-up send after 3 s. [device: whether a heads-up ever happens under lock task's alert flags. If never, drop that too] |
| Ring card (`PinLockActivity.kt:565-595`) | The fallback ring UI | No for `FSI_DENIED` or DND (no FSI, and no lock-screen notification) | **CHANGE**: card only (59b step 1) |
| `VoipRinger` | Our ringtone (lock task mutes notification sound) | No | **KEEP**. [device: no double ring with the app's own screen] |
| IN_CALL yield, no lock task during a call (`lockTaskEntry` `voipCall`) | No fight with the call screen or PiP | n/a | **KEEP** |
| Reopen of the call screen at the glance's resume (`PinLockRuntime.kt:657-663`, `VoipCalls.kt:387`) | Back to the call from the lock | A non-showWhenLocked call screen lands behind the keyguard | **CHANGE**: only with `IN_COMMUNICATION` (59b step 1). Through step A's queue when the app isn't showWhenLocked |
| `VoipEnded`/`CallsEnded` -> show the glance (`PinLockState.kt:192-197`, `:216-223`) | The lock after a call | No: else SystemUI's face shows | **KEEP** |
| Camera lock 25b (`CameraLock.kt:84-96`, `cameraLockEngaged` `AndroidLockPlan.kt:158`) | Suspend cameras and disable them by policy | Yes: `KEYGUARD_DISABLE_SECURE_CAMERA` refuses the gesture (RC step 11) | Already **REMOVED** while the 0x2 read-back holds. KEEP the fallback |
| Runbook: the camera double-press off before enrolling (`docs/testing/emulator.md:111-113`) | No camera frame over the lock | Yes (RC step 11) | **REMOVE** the requirement in android mode (stock gestures stay - the user's rule) |
| Notice wake (`PinLockRuntime.kt:1199-1225`) | Wakes on the glance | No | **KEEP** |
| The wake's 15 s `lockNow` (`PinLockRuntime.kt:1242-1265`) | Screen off again | It costs the fingerprint until the next PIN (`STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW`, RC step 6) | **CHANGE** (step E, the user's choice; 59 open question 3) |
| Boot cover 16b/F3 and `BootHome` | The stock launcher is never shown after the boot's PIN | No | **KEEP**, and extend it (§5) |
| 16c Home night ground (`HomeGate.kt:19`), 16e boot mark (`BootMarkPlan.kt`, `PinLockRuntime.kt:353-450`) | Home never shows content under the lock; 3 s mark at boot | Yes: Home is hidden by the keyguard, and the first CE start is UNLOCKED | Inert. **REMOVE** in phase D |
| Black stand-in (`PinLockActivity.kt:449-457`, the theme's black window) | What WM shows before the lock's first frame | No | **KEEP**: the prototype's recording went app, fade, black, glance |
| `scrollTo(0, 0)` at a lock-up's first resume (`GlancePanel.kt:227`) | Rows from the top | n/a | **KEEP** (before the frame) |

## 3. The target picture of android mode

| Moment | What the kid sees | What our code does |
|---|---|---|
| Screen-off | Dark | One start of the glance only if it isn't the top activity (LOCK). The sleep check 1 s later relocks an open Android. The heal at 400 ms. No Home |
| Screen-on | The glance (black for a frame or two at most) | Nothing is started. The resume reads STAY. If SystemUI's face is up instead: at most 2 re-fronts (59b) |
| "Lås opp" | Android's pad over the glance page | `requestDismissKeyguard` from the glance page; no keypad stage |
| PIN pad success | Home, or the row's app (Q1: or the last app) | The glance hides its content, starts Home unanimated (or not at all), opens the target, finishes |
| A row tap | The pad; then that app | As "Lås opp", with the target kept |
| VoIP ring | The app's own ring screen (the platform's FSI); our card only when there is no FSI | No glance start, no wake, no send. Our ringer. The yield during the call. The glance after it ends |
| A tap towards a locked app from a call | SystemUI's face over the night ground, then the pad, then that app | Nothing: no re-front during a call, no Home after the unlock (59b steps 0, 2) |
| Reboot | Android's pad over our night cover with the mark (§5), then the mark, then Home | The cover asks for the pad; BootHome after the take-over. The glance at the next screen-off |
| Notice wake | The glance lights for 15 s | `show` + `VoipWakeActivity`; the end without `lockNow` (step E) |
| A parent window | Nothing on the lock changes. The parent types the kid's PIN on Android's pad | Window edges change the chrome (not while the pad is up, step G). The relock's Home and time-rule screen wait for the unlock (step A) |

**What 59b already covers**: R3 and R4 (steps 0, 1); SystemUI's face during use (step 2); the face's look (step 3); R2's
measurement (step 4).

**Missing, proposed steps:**

| Step | Size | What |
|---|---|---|
| A | S | **Done** (59b's code-QA fix round, qa-59b-code B-1; the VoIP reopen keeps the cover's `whenUnlocked` and 59b's `IN_COMMUNICATION` gate - no showWhenLocked read exists to queue it per app). One face-mode "after the unlock" queue. `KeyguardCover.mustWait` is also true for the face while Android is locked. Through it go the time-rule screen (already via `whenUnlocked`), `WindowRelock`'s, Play's, `SelfUpdate`'s and `AppEnforcer`'s Home starts (a `HomeFront.bring` gate), and the VoIP reopen for a non-showWhenLocked app. The queue runs on LEAVE. `ensureLockTask` START_SELF in face mode |
| B | S | No KEYPAD stage in face mode: "Lås opp" and a row ask from the glance page (the target kept, `keypadIsAndroids` goes). On LEAVE the content goes INVISIBLE (black ground) before `go()`; Home `NO_ANIMATION`. Fixes R1 |
| C | S | Screen events in face mode: `lockUpStarts` takes LOCK for the face whatever `kg` read (no HOME_THEN_LOCK). No ScreenOn backstop start (`PinLockState.kt:173`). Check with 59b step 4's capture (R2) |
| D | S | **Done** (59b's code-QA fix round, qa-59b-code B-2). SOS in face mode: the exemption only, no `narrowForSos`. Fold into 59b step 2 |
| E | S | The notice wake ends without `lockNow` in face mode (the phone's own timeout), or keeps it. The user's choice |
| F | M | BFU: Android's pad over the night cover (§5). After the F3 merge |
| G | S | F2: hold the LOCKED-chrome feature write while an ask is open |
| H | S | Q1's answer: if "back to the last app", `lockLeave` never starts Home in face mode except as the kiosk root |
| I | - | Not built: SystemUI's face during the power-press fade-out (§6b). Mitigated by 59b step 3's night lock wallpaper |

**Order**: 59b step 0, then 59b step 1, then A, B, C, then 59b step 2 with D, then 59b steps 3 and 4. F follows the
F3 merge. E and H wait for the user's answers. G is any time.

## 4. Phase D: own-mode code that goes once the switch is gone

Counted from `main` `d88509ae` with `wc -l`; "~" marks the part of a file that goes.

| Piece | Lines now | Goes |
|---|---|---|
| Keyguard cover: `KeyguardCover.kt` 527 + `KeyguardCoverPlan.kt` 358 (the ANDROID write and T stay, ~170); debug/release hooks 88; the activity's dismissal `:148-240` 93 | 1,066 | ~896 |
| Dark lock-up and backstop: `LockUpPlan.kt` 128 (~100); `LockUpDebug` hooks 57; runtime bookkeeping ~60 | 245 | ~217 |
| 16c/16e: `BootMarkPlan.kt` 252, runtime `:353-450` 98, `ui/home/NightGround.kt` 90, `activity_home_night.xml` 21, `homeShowsContent` ~20 | 481 | ~481 |
| Camera lock: `CameraLock.kt` 249 + `CameraLockPlan.kt` 132 | 381 | 381 |
| Our keypad on the lock: `PinBackoff.kt` 102, `PinKeypadLayout.kt` 91, `PinKeyHighlight.kt` 48, the activity's keypad `:777-944` 168 and parent-code dialog `:946-1000` 55, `PinLockStore`'s backoff ~80 | 544 | ~544 |
| VoIP 17b send and try detection: `VoipCallPlan.kt:198-293` 96, runtime `:1004-1076` 73 | 169 | ~169 |
| Own re-front escalation and SOS narrowing: `RefrontPolicy.kt` ~40, runtime ~30, `LockTaskChrome` ~15 | 85 | ~85 |
| **Main** | | **~2,770** |
| Tests: `KeyguardCoverPlanTest` 478, `LockUpPlanTest` 386, `LockUpTest` 221, `BootMarkPlanTest` 433, `CameraLockTest` 94, `PinBackoffTest` 110; parts of `HomeGateTest` (~150), `PinLockLayoutTest` (~250), `SosYieldTest` (~60), `VoipCallPlanTest` (~150) | | **~2,330** |

So about **5,100 lines** in all. That is less than the assessment's -4,300 in main alone (59 A §6): 59b keeps the ring
card, the ringer, the wake and a capped re-front, about 1,100 lines that A counted as gone. Phase D first needs one
decision: what replaces `OWN_KEYPAD`, our keypad when Android has no credential (a PIN removed on the phone). The
command `set_kid_pin` again, with SystemUI's swipe face meanwhile, is the obvious candidate.

## 5. Reboot: straight to Android's pad over our night cover (the user's decision, step F)

**The aim**: after a reboot the kid never sees SystemUI's clock face. Android's pad comes over the night ground with
the vibb mark.

**How** (on `fix-f3-boot-cover` `2483caf8`: the cover is DBA, in its own `:bootcover` process, and armed at every
locked boot by `BootCallReceiver` at `LOCKED_BOOT_COMPLETED`; round 2's B1):
1. While the user is locked, `BootCoverActivity` calls `setShowWhenLocked(true)` in `onCreate`. Runtime only: the
   manifest stays without the flag, and `BootCoverManifestTest` pins that plus the absence of `turnScreenOn` and
   `dismissKeyguard`. So the HOME-typed start at the locked boot occludes the BFU keyguard and the cover is the face.
2. Once resumed, interactive and with no ask open, it calls `requestDismissKeyguard`, at most once per resume after a
   stop. The bouncer is BFU's "Skriv inn PIN-koden" / "PIN is required after device restarts" pad.
3. Cancelled (Back): the cover stays with a line "Trykk for å låse opp" under the mark. A tap asks again. No new
   automatic ask until a stop, so a kid who keeps cancelling never gets a loop. A cap of 3 automatic asks per boot is
   the backstop; after it, only taps ask.
4. Power with the pad up: the bouncer goes at the sleep and the cover stays the top activity. The next screen-on
   resumes it, and that resume asks once (rule 2).
5. Success: CE unlocks, and the cover runs today's F3 path: `setShowWhenLocked(false)` at once, then it waits for the
   main process's take-over and Home (`coverWaitStep`). Round 2's B2 disables it in `onDestroy` once unlocked, so it
   never outlives the unlock. Never showWhenLocked once `isUserUnlocked`.

**Calls and emergency**: our `InCallActivity` (DBA, showWhenLocked) and the emergency dialer from the pad's Emergency
button are newer showWhenLocked activities, so they come over the cover as over the glance. B1/B5 already keep the
cover's own start and Home starts out of a live call. A residual stays: the 5 s call retry's HOME start could land
over an emergency dialer that is open but hasn't dialled yet. [device: Telecom's and the system dialer's call UI win
over an occluding activity BFU; the dialer stays in front through a cover start]

**The restart line** (`setDeviceOwnerLockScreenInfo`): SystemUI shows it in the face's indication area, not on the
bouncer. With the cover up it is not seen, but the bouncer shows Android's own "PIN is required after device
restarts". Keep writing it: it is the fallback face when the cover can't start (no marker, a tripped guard, a call).
[device: confirm on the Jelly Star's bouncer]

**Risks**:
- `requestDismissKeyguard` needs the activity visible ignoring the keyguard (`KeyguardController.java:321-336`). A
  BFU strong-auth keyguard is still dismissed only through the bouncer. [device: that the BFU bouncer comes for a DBA
  activity; no AOSP rule against it found]
- The device owner's background-start exemption is the one F3 already uses (`BackgroundActivityStartController`).
- The cover is HOME at priority 0. Occluding, it shows only itself: nothing behind it is visible, and no row exists.
- A crash loop is covered by the cover's own guard (2 crashes per boot disable it). SystemUI's face is then the
  fallback.

**Size: M.** The code is small (~80 lines: the runtime flag, the ask rule as a pure `bootPadStep` with tests, the tap
line, the strings), but the BFU device checks (the pad, calls, the emergency dialer, power, cancel) are most of it.

## 6. Optional variant: the PIN pad directly at wake (`android_pin_only`)

The glance (or a minimal black showWhenLocked activity) calls `requestDismissKeyguard` automatically at every
screen-on, so the kid sees Android's pad at once and no glance content.
- **Cost**: ~40 lines on top of `askAndroid` and the pad callbacks (an auto-ask once per screen-on, keyed on
  `screenOffs`; Cancel leaves a black page with one "Lås opp" tap, no re-ask loop). Plus a per-phone switch in the
  policy: both directories, the policy snapshot and the compat test, and a PWA row.
- **Loses**: the time, the rows, music, the timer, the torch and "Lås opp og åpne" without a PIN. The notice wake makes
  no sense then (a lit pad in the pocket).
- **Does not fix**: SystemUI's face after a reboot (§5 does), or after an app's non-showWhenLocked launch from a call
  (the chat arrow). Nor the flicker causes of §1, which are outside the wake path. It adds the bouncer's own animation
  at every wake.
- **Biometrics**: better, if anything. SystemUI listens for a side or rear sensor while the bouncer is up, but not
  while our glance occludes (A §4.9). The fingerprint would work with the screen on. Face and iris stay off.
- **When it makes sense**: a parent who wants the PIN always, and an older kid who doesn't use the glance.
- **Recommendation**: don't build it now. The user's preference is the hybrid with the glance. If a parent asks, it is
  an S on the phone plus a contract field, after steps A-C.

## 6b. Step I: SystemUI's face in the fade-out after a power press inside an app

**What the user sees**: power pressed inside an app sometimes shows a bit of SystemUI's clock face while the screen
fades out.

**Why**:
1. With "Power button instantly locks" on (Android's default), the keyguard is shown when sleep *starts*: at the press,
   before the screen-off fade.
2. The app is the top activity, so the keyguard is un-occluded and SystemUI's face is drawn during the fade, about
   0.3-0.4 s.
3. `ACTION_SCREEN_OFF` comes only after the display is off, so our glance's start (`PinLockRuntime.kt:799`, the lock-up)
   is always later.
4. No app hears the power key or the start of sleep. There is no public going-to-sleep callback, and
   `DisplayListener` changes state at the fade's end too. So nothing of ours can come first.

**The proposal "our glance first, Android's lock second"**:
- The keyguard would not lock at the press.
- The glance is started at `SCREEN_OFF`, as today.
- We call `lockNow()` once the glance is there, so the keyguard locks behind an already occluding face.
- The system's own timeout is the safety net when our process is dead.

**Can we set the two settings?** No.
- `power_button_instantly_locks` is not a `Settings` key. It lives in LockSettings' storage
  (`lockscreen.power_button_instantly_locks`, written by `LockPatternUtils.setPowerButtonInstantlyLocks`, which needs
  `ACCESS_KEYGUARD_SECURE_STORAGE`). No DPM API writes it.
- `Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT` is not on `setSecureSetting`'s allowlist. That list is the default
  input method, `SKIP_FIRST_USE_HINTS`, `INSTALL_NON_MARKET_APPS`, plus a device owner's `LOCATION_MODE`.
- `setMaximumTimeToLock` only caps the delay. Design 59 sets it to the screen timeout, which makes the delay 0. A delay
  would also mean raising that cap above the screen timeout.
- So the parent would set both once in Settings › Security › Screen lock (the gear) during a window, and nothing
  could check or keep them. Settings' shortest "Lock after" is 5 s.

**Judgement**: the proposal is a regression.
- **The fingerprint**: our `lockNow` sets `STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW`. The next unlock then needs the
  PIN (RC step 6: refused after Locate › Lock). Doing that at **every** screen-off ends fingerprint unlock for good. Step
  E removes the one `lockNow` of that kind we have now.
- **The open window**:
  - From the press to our `lockNow`: the fade (~0.35 s), then the broadcast and the glance's creation while dark,
    which is not resumed under sleep. So about 0.5-1 s, and the full "Lock after" (5 s or more) whenever our process
    is slow or dead.
  - A wake in that window finds Android open. `faceStep` then waits and RELOCKs (`lockNow` again), so there is still no
    path past the PIN.
  - But a dead process leaves the platform's 5 s no-PIN grace, which design 59 closed deliberately with T = the screen
    timeout.
- **What it doesn't need**: the screen-on is already right, because the glance occludes before the wake (the
  prototype's recording: app, fade, black, glance). Only the fade-out is affected.

**Alternatives looked at**:
- `KeyguardManager` has no public "lock now" without strong auth. `KeyguardLock.reenableKeyguard` is deprecated and
  doesn't apply to a secure keyguard.
- The screen-off animation can't be turned off by a device owner: `config_animateScreenLights`, and the animator scale
  is not on `setGlobalSetting`'s allowlist.
- No app can sit over the app the kid is using.

**Recommendation**: don't build it, and accept the fade-out frame.
- 59b step 3 makes that frame the night ground with the vibb mark (plus SystemUI's clock), fading to black, rather than
  the default lock wallpaper.
- 59b step 4's capture (`screen_toggled`, `wm_set_keyguard_shown`) measures how often and how long it shows on the
  Jelly Star: the emulator has no AOD/doze fade and may differ.
- Revisit only if Android ever offers a delayed lock without strong auth.

## 7. Questions for the user

1. **Q1**: after "Lås opp" on the glance, land on Home (as now, design 32 decision 2) or back in the app the kid was
   in (Android's normal)? The second removes the last Home start of the face. Default: Home.
2. **Q2 (step E)**: the notice wake's 15 s end with `lockNow` costs the fingerprint until the next PIN. End it by the
   phone's own screen timeout instead? Default: no `lockNow` in android mode.
3. **Q3 (phase D)**: no Android credential (removed on the phone): SystemUI's swipe face until `set_kid_pin` lands,
   or keep our keypad? Default: the command, and the PWA warns.

## 8. Round plan (2026-10-23): steps B, C, E, G, H and N1 as round 1, F as round 2 - revised from its design QA

Status: revised 2026-10-23 from `docs/review/qa-59d-design.md` (Q59d-1..14; every finding taken, the choices below)
and `docs/review/qa-59d-revision-check.md` (§8.5). **Where §2, §3 or §5 say otherwise, §8 rules** - the rows the
revision check names: §2.1's backstop row (REMOVE -> gated KEEP), §2.4's and §3's 15 s notice-wake rows (no end at
all), §3 step H's "last app" (not picked), §2.2's F2 row and §3 step G (`ANDROID_ASK_OPEN_MS`, every DPM write), §3
step B ("Fixes R1" - 59b did; the ASK stage; `noAnimation`), §5 rules 1-3 (the marker gate, the per-process cap).
This section fixes the open choices, the exact rules, and the order of work.
Base: `main` after the rc.2 re-check (`73227afc`): F3 is merged (the cover is DBA, `:bootcover`, armed at every locked
boot), 59b and its two fix rounds are in, steps A and D are done. Paths under
`launcher/app/src/main/java/com/kidslauncher/mdm/lock/` unless named. The bar (the QA's): no step opens a path past
Android's PIN; every exit still needs `faceStep`'s LEAVE.

### 8.1 The user's answers

- **Q1 (step H)**: after "Lås opp" (and after a row tap's pad) the phone lands on **Home**, as design 32 decision 2 -
  **except while a call is live**: then no Home start, the glance only hides and finishes, and the call's UI (the
  app's own, or ours) stays in front - **except as the kiosk root**, where Home must come (stopping a root lock task
  clears every locked task; only Home re-roots it; §8.2 item 3). A row's target still opens when a row was tapped. "Live" is
  `PinLockRuntime.callLiveNow` (ours `OngoingCalls.hasLiveCall`, Telecom's `isInManagedCall` else the audio mode,
  `VoipCalls.phase != NONE` ringing or IN_CALL with its grace) - not `voipGrace`, which is the 3 s ring timer
  (Q59d-4). 59b step 0's rule (no Home when the glance wasn't in front at the unlock) is unchanged.
- **Q2 (step E)**: as the face, the notice wake has **no 15 s end** (Q59d-2: `lockNow` is the wake's only screen-off,
  `goToSleep` isn't ours). The glance stays lit until the phone's own screen timeout - the parent's
  `screen_timeout_seconds`, 15 s to 10 min, default 60 s (`server/ScreenTimeout.kt`). Android's keyguard is locked
  for the whole wake (the glance only occludes it), so there is no unlocked window and the fingerprint keeps working.
  What holds Android's state is not `noticeWakeAllowed` (it needs **our** LOCKED, dark and no lock-up in the last 2 s)
  but the sleep check 1 s after the screen-off (RELOCK of an open Android) and, for a wake inside that second, the
  glance's resume rule (WAIT, then RELOCK) - code QA R1-9; nothing relies on the gate for Android's state. The cost the user accepted: up to the full timeout of lit rows in a pocket (the
  proximity read happens only at the start). Own mode keeps `lockNow`.
- **Q3**: PIN required. The user, 2026-10-23: no own keypad in android mode. The server refuses android mode without
  a Child PIN and the PWA can't clear the PIN while the lock is Android's; a phone that finds its Android credential
  gone re-sets it from the reset token at the next sync; in between, SystemUI's swipe face over the night ground and
  a PWA warning. Phase D builds it (not this round); until then `OWN_KEYPAD` stays as today.

### 8.2 Round 1 (one implementer, one worktree, one code QA): C, N1, H, B, E, G - in this order

1. **Step C** - `lockUpStarts` takes LOCK for the face whatever `kg` read (no HOME_THEN_LOCK in face mode); the `kg`
   read stays (it feeds `sawLocked`, Q59d-12). Accepted cost: Android open at the screen-off and a wake within the
   1 s sleep check shows the app's snapshot for a frame. **The ScreenOn backstop stays as the face, gated (Q59d-1)**:
   `showLock = LOCKED && !lockShowing && !lockOnTop && !systemCall && voip == NONE` (new `face` and `lockOnTop`
   inputs on `LockEvent.ScreenOn`); a screen-on that skips the start because `lockOnTop` is set doesn't clear the
   mark (`screenOnUnshown` takes the face's skip as shown); a new process (`lockOnTop` false) and a lock-up whose
   start threw still get the start. Own mode unchanged. Tests: `PinLockStateTest` (face + lockOnTop -> no start;
   face + new process -> start; own rows unchanged), `LockUpPlanTest` (face, not locked -> LOCK).
2. **N1** - after C (Q59d-11: the pause/resume pair at the wake is most likely the backstop's `START_DELIVERED_TO_TOP`
   new-intent delivery, which C's gate removes). N1 stays as the cheap log guard: `sosMarkAtPause` logs only when the
   700 ms check still finds the mark. Confirm with `wm_new_intent` in the capture (§8.4).
3. **Step H** - `lockLeave(.., androidFace)`: kiosk on, `homeFirst = startedLockTask || (glanceInFront &&
   !(androidFace && callLive))`; the kiosk root keeps Home (stopping a root lock task clears every locked task, the
   call app's included; only Home re-roots). Kiosk off is unchanged (`glanceInFront && !callLive` already). Own mode
   unchanged. Tests (`LockTaskRootTest`): face, kiosk on, not root, call -> `(false, false)`; root + call -> `(true,
   true)`; own-mode rows equal for every input. Docs: `launcher/CLAUDE.md`'s design 32 sentence ("Unlocking lands on
   Home in every mode ... never over a live call").
4. **Step B** - target frames after the pad's success (Q59d-5): the glance's ground (no mark - code QA R1-11; K made
   Android's lock wallpaper the same ground), then Home with no slide; a recording against rc.2's check 9 (the page for ~0.8 s, then Home's slide) is the check. Rules:
   - a third stage **`ASK`** (Q59d-6): `glanceStageStep(.., face)` gives `ASK(target)` for Unlock/Row as the face;
     the page stays shown, the keypad is never visible or focusable; Back, a cancel, an error, the recovery, STAY and
     LockUp give GLANCE (the target dropped); a tap in ASK runs `tapAsFace` again; a row tap in ASK replaces the
     target; `OWN_KEYPAD` gives KEYPAD (our keys); own mode never enters ASK; `keypadIsAndroids`,
     `GlancePanel.androidPad` and `glancePageShown`'s pad input go;
   - on LEAVE, as the face and in front, the glance page's **children** go `INVISIBLE`; `glance.root` stays visible
     and keeps the ground (`GlancePanel.ground`); never the decor view or the window (`GlanceOpener`'s
     `PendingIntent` send needs our window visible); if the finish is still refused after `rootLeave`, the content
     shows again;
   - the leave's Home uses a new `noAnimation` parameter of `HomeFront.bring` (`FLAG_ACTIVITY_NO_ANIMATION`) that
     still ends the dark mark and still hops to the main thread (Q59d-7); `HomeFrontTest` pins the two callers;
   - the face's close fade (`PinLockActivity` leave) goes with hidden content (it would fade nothing).
   Tests: `AndroidLockFaceTest` (the B-6 pins rewritten for ASK), `LockGlanceTest` (the stage machine with the face),
   `HomeFrontTest`.
5. **Step E** - Q2 as above: `noticeWakeOffDue(.., face = true)` is false, `noticeWakeOff` does nothing as the face.
   Tests: `LockWakeTest` with a face row. Docs: drop `launcher/CLAUDE.md`'s "The notice wake keeps its `lockNow`
   after 15 s"; close design 59's open question 3.
6. **Step G** - **after its device capture** (§8.4 first item): while an ask is open, **every** lock-task DPM write
   of a chrome pass waits - packages, features, status bar, `DISALLOW_CREATE_WINDOWS` and a heal's flip (Q59d-3: RC
   F2 saw two applies at the pause's end, packages as well as features, and the cancelling write isn't known).
   "Open" = `androidAskAt` set, from `noteAndroidAsk(open = true)` to its close by any answer, the screen-off or a
   new `ANDROID_ASK_OPEN_MS` = 2 min (not `ANDROID_ASK_TIMEOUT_MS`, the 2 s dead-ask recovery). The `Plan` is stored at
   once so the UNLOCKED edge's pass writes the newest one; the ask's close runs one `refresh`; the ask stamp is read
   through a `@Volatile` accessor (apply runs on a background thread). Tests: a pure `chromeHeld(askOpen, ..)` and a
   pinned release on the ask's close.

**Out of round 1**: step I (not built), Q3's build (phase D), R2's decision ("as the face, kiosk off, no own lock
task" - its own small round after this one; it touches `lockTaskEntry`/`lockLeave`/`LockTaskChrome` too, but the
user's test runs with the kiosk on, and it needs its own shade/QS check), 59b §4.4's ring rule after a notice dump,
N2 (Jelly Star palette), r10 (falls with phase D).

### 8.3 Round 2 (its own worktree and code QA, in parallel with round 1): step F, §5 revised

§5 as written, plus (Q59d-8, -9):
- **Gate**: the runtime `setShowWhenLocked(true)` and every ask only with `waitForLauncher` (the android marker) **and**
  `isUserUnlocked == false`, read again before every ask and at every resume. The `ACTION_USER_UNLOCKED` receiver and
  `onResume` call `setShowWhenLocked(false)` once it is true. An own-mode phone (16b's `boot_cover` switch, an
  Android credential of its own) never gets an occluding or asking cover.
- **`bootPadStep`** (pure, tested): at most one automatic ask per resume after a stop; **no automatic ask while
  `telecomInCall()`** (BFU-safe: `isInManagedCall`, same uid) - a tap still asks; an `onDismissError` retries once
  after 1 s, counted; a cancel with the screen off (power with the pad up) isn't counted; the cap is 3 automatic asks
  per `:bootcover` process, in memory (the cover's crash guard bounds a loop); after the cap only taps ask.
- **Pins that flip, in the same commit**: `BootCoverManifestTest` keeps the manifest attributes banned and allows
  `setShowWhenLocked`/`requestDismissKeyguard` only inside the gated functions (pin their bodies);
  `KeyguardCoverPlanTest`'s "requestDismissKeyguard only in the lock" gains `BootCoverActivity.kt` with one call;
  `launcher/CLAUDE.md`'s 16b/F3 bullet and design 59's F3 "Never over the keyguard" are amended.
- The overlap with round 1 is `KeyguardCoverPlanTest` and `launcher/CLAUDE.md` only; the second to merge rebases.
- **No emulator in round 2's build**: F's device gates run in the combined run (§8.4) after both rounds merge.

### 8.4 Device checks (`handy36`)

**Mid-round (round 1's implementer, after C, N1, H, B, E are built and before G)**: one short run of its own build:
G's capture - with Android's pad open from "Lås opp", end a parent window from the PWA; record which lock-task write
precedes the pad's `cancelled` (`wm_*`, `LockTaskChrome` lines around `end-windows`). If the cancelling write is not a
chrome-pass DPM write (a WM focus change, say), G is **not built**: the finding goes into §8.5 and PLAN, and the round
ends with E.

**The run after both rounds merge** (one agent, the release candidate's build):
1. C: 59b step 4's capture (R2) at the wake, with `wm_new_intent` for N1; and Q59d-1's own scenario - force-stop the
   launcher while dark, then wake: the glance starts, not SystemUI's face.
2. H: "Lås opp" during a VoIP call (Element X or WhatsApp on the AVD, or the fake), **kiosk on**: no Home, the call UI
   stays; a row tap: the target opens over the call; **kiosk off**: unchanged (today's rule). After the leave
   (code QA R1-7): `dumpsys activity activities` shows the call app's task on top and no BlockedAppActivity; and the
   audio-mode-only case (`callLive` from the audio mode alone - a voice-note recorder or a game's voice chat in
   `IN_COMMUNICATION`): which allowed app's task lands on top, and nothing outside the kiosk list.
3. B: a recording of the pad's success against rc.2's check 9: the glance's ground (no page content, no mark), then
   Home without a slide and **never on black** (Home draws its own ground after the face's leave, `ownGround`); Home
   transparent over Android's wallpaper again after it stops (an app and back; `dumpsys`/a frame shows no own
   background); a row's target still opens (the `PendingIntent` send); the ASK stage's cancel (Back), STAY (a
   screen-off with the pad up) and `OWN_KEYPAD` (the Android credential removed by hand) paths. Code QA R1-3: a
   window's end during the ask (its Home queued for the unlock), then the pad's success, recorded - the queued Home
   is unanimated too (`bringAfterUnlock(.., noAnimation = faceOn)`), so no second slide.
3b. K (Android's lock wallpaper as the face = the glance's ground with its scrim): (a) after the face's edge, a 24 fps
   frame of SystemUI's sharp wallpaper at a wake next to the glance's first frame, with a photo ground and with a
   gradient - no shift, zoom, brightness step or banding (code QA R1-5: the bitmap is `screen(context)`'s size from
   `displayMetrics`, the scrim drawn into an RGB_565 bitmap, and SystemUI may dim the lock wallpaper); (b) a parent
   change of the lock wallpaper, or of Home's while no lock wallpaper is set, re-applies at the sync; (c) a rollback
   (the face off) puts design 08's rule back; (d) after a reboot, SystemUI's BFU face shows the ground (the photo
   included, the user's decision R1-1).
4. E: a notice wake stays lit to the screen timeout, Android locked throughout (`dumpsys` keyguard state), the
   fingerprint (emulator `finger touch`) still works after it.
5. G: RC F2 re-run - the pad stays up through a window's end, and the window's chrome lands at the ask's close.
6. F (Q59d-10; if gate (a) fails, the merged code and the flipped pins **stay, inert** - the gate's false branch is
   today's behaviour - and design 59's F3 section records it): (a) whether a runtime-flagged HOME activity occludes the BFU keyguard at all - if not, the cover is
   never resumed while locked, no ask, today's behaviour, and that is the documented fallback; (b) the shade and QS
   over the occluded BFU keyguard with no lock task: airplane, Wi-Fi, data, Bluetooth, location tiles against
   SystemUI's own BFU face - the occluded shade must not be wider; (c) whether the cover as the BFU face changes N2;
   plus §5's list: a reboot with and without a call, the emergency dialer, power with the pad up, cancel x3.
   Added by step F's code QA (`qa-59d-f-code.md` F59d-7): Back twice on the face after a cancel - no SystemUI face, no
   `wm_destroy_activity` of the cover, the line stays (`Boot cover: Back on the BFU face, held`); the Home key during a
   call before the PIN - the cover over the call screen, no ask while the call lives, and once it ends with the cover
   in front the line, and an ask at the next tap or screen-on; the first resume's ask answered `error` (the keyguard
   not ready) - a `retry` ask 1 s later, not a second error; an ask line while `dumpsys` shows the keyguard **not**
   occluded is gate (a) failing too (SystemUI's bouncer over SystemUI's own face), not a pass; with (b), the Home and
   Overview gestures and the power menu over the occluded BFU keyguard (Overview stays unavailable, as on SystemUI's
   own BFU face); and whether `onDismissCancelled` comes at all for the pre-unlock pad's Back (`Boot cover: Android's
   PIN pad cancelled`) - without it only the stop's line leaves a hint.

The release after the rounds is `launcher-v0.36.0-rc.3` on the same server (no API change expected; if anything
needs a status field, both directories in one commit as always).

### 8.5 Answers to the revision check's guess list (`qa-59d-revision-check.md`)

- **G**: `ANDROID_ASK_OPEN_MS` = 2 min because a kid's slowest PIN entry plus Android's first lockout step (30 s after
  5 wrong tries) fits in it, and a longer hold only delays a window's chrome. A held `setLockTaskPackages` doesn't
  block anything the kid can reach: during an ask the kid is at the pad, and the window's packages land at the ask's
  close (one `refresh`). If the capture shows the cancel isn't a chrome-pass DPM write: §8.4, G is not built.
- **B**: "the content shows again" = at once when `rootLeave` reports the finish refused (synchronous), and from the
  glance's next `onResume` when its content is still hidden (the leave didn't finish within that resume). `face`
  reaches `glanceStageStep` as a constructor argument of the panel's state (`AndroidLock.faceOn` at `show`, fixed
  for that glance instance). STAY drops a target that wasn't sent (GLANCE); a target already sent through
  `GlanceOpener` is sent, STAY changes nothing about it.
- **C**: for a new process `lockOnTop` is false (nothing of ours is on top) and `face` is `AndroidLock.faceOn` from the
  cached policy - both read where `LockEvent.ScreenOn` is built, not stored.
- **N1**: the 700 ms check is `RefrontPolicy`'s pause grace (`SOS_GESTURE_WINDOW_MS`'s companion, the 700 ms pause
  grace in §2.1's SOS row); `sosMarkAtPause` reads the mark set at the pause and logs only if the grace check still
  finds it.
- **F**: an automatic ask resumes at the first resume after the call ended (no special case: the rule is per resume
  with `telecomInCall()` false). `telecomInCall()` BFU is a device check (§8.4 item 6: a reboot during a call) - the
  code asserts nothing. The 1 s retry after `onDismissError` is part of the same ask: at most one retry, and the pair
  counts 1 of the 3.
- **Docs**: round 1 edits `launcher/CLAUDE.md`'s design 32 sentence and the notice-wake line; round 2 edits its
  16b/F3 bullet; different bullets, the second to merge rebases.
- **G (after its capture, 2026-10-23)**: not built. The capture (`docs/testing/2026-10-23-d59d-g-capture.md`) found no
  chrome-pass DPM write cancelling the pad: four runs, kiosk off and on, the window's end 0-2.5 s after the tap -
  packages, features and the relock all ran under the open pad, which stayed. RC F2's cancel was most likely the
  relock's own Home start over the keyguard, which step A queues for Android's unlock now.

### 8.6 Step F as built (round 2, branch `d59d-round2-f`, 2026-10-23; no emulator run in this round)

**Files**: `lock/BootCoverPlan.kt` (pure: `bootPadGate`, `PadEvent`, `PadAction`, `BootPad`, `PadStep`, `bootPadStep`,
`BOOT_PAD_AUTO_ASKS` = 3, `BOOT_PAD_RETRY_MS` = 1 s), `lock/BootCoverActivity.kt` (the wiring: `padGate`,
`applyLockedFace`, `padStep`, `askPad`, `padAnswer`, the tap line), `boot_cover_tap_to_unlock` (nb "Trykk for å låse
opp", en "Tap to unlock"), tests `BootCoverPlanTest` (6 new, one per rule), `BootCoverManifestTest` (the keyguard pin
flipped), `KeyguardCoverPlanTest` (the caller list). Docs: design 59's F3 "Over the keyguard only as the BFU face"
(with gate (a)'s fallback) and its run check 3, `launcher/CLAUDE.md`'s 16b/F3 bullet.

**The rules as coded** (`bootPadStep(pad, event, gate, interactive, inCall)`):
- No gate (`bootPadGate`: marker and not unlocked) -> nothing, the ask and its retry closed. The Activity's gate is
  `waitForLauncher` (create's marker read) and a fresh `BootCoverGuard.androidMode` and `isUserUnlocked != false`
  (unreadable = unlocked, inert); read in every `padStep` (every resume, stop, tap, answer, retry) and again inside
  `askPad` right before `requestDismissKeyguard`. `applyLockedFace` sets `setShowWhenLocked` to the gate's value at
  create, every resume, the `ACTION_USER_UNLOCKED` receiver, every pad step and F3's 250 ms wait poll once unlocked.
- RESUMED (posted past `onResume`): asks when armed (a new cover, and every stop), no ask open, the screen on, no
  `telecomInCall()` and fewer than 3 counted. A resume held back (call, screen off) keeps the arm, so the first resume
  after a call asks.
- TAPPED: always asks (in a call, after the cap), uncounted. A tap reaching the cover means no pad is over it, so an
  ask still marked open is given up; its late answer is dropped by the ask id (`padAnswer`).
- CANCELLED: the line shows; an automatic ask cancelled with the screen off gives its count back.
- ERROR: one retry after 1 s, the same ask (no new count); the retry needs the screen on and, for an automatic ask, no
  call; a second error or a retry that can't go shows the line. A retry dropped by `onPause` is posted again at the next
  resume; a stop drops it.
- STOPPED: arms the next resume; an ask still open is closed (uncounted when the screen is off - the power press's
  stop may come before the cancel callback, which is then no current ask's answer).
- SUCCEEDED: the ask closes and the line goes; CE's unlock turns the gate false.
- The count lives in a companion var (`autoAsksThisProcess`), so a new cover instance in the same `:bootcover`
  process carries it.

**Where it goes past §5/§8.3** (choices the spec left open): the tap asks even with an ask marked open (above); a stop
closes an open ask, so a lost cancel callback can't block rule 4's next screen-on ask; the line also shows after a
failed retry, and never before any ask (a first resume in a call shows the mark only - a tap still asks); the resume's
ask is a plain post after `onResume`, not "after the first frame" (an early `onDismissError` is what the 1 s retry is
for).

**What the device run must check** (§8.4 item 6, on the release candidate after both rounds merge; log tag
`BootCover`):
- (a) After `adb reboot` and `svc power reboot`: `Boot cover showWhenLocked true`, then `Boot cover asks for Android's
  PIN pad (resumed, automatic 1 of 3)`; `dumpsys activity activities` shows the cover resumed on top with the keyguard
  occluded, and the bouncer is BFU's ("PIN is required after device restarts"). No resume and no ask line while locked,
  or an ask line with the keyguard not occluded, = gate (a) failed: the fallback in design 59's F3 section holds,
  nothing to revert.
- (a2) The pad keeps the cover resumed (focus only), as the rc.2 re-check measured for the glance: no `onStop` between
  the ask and its answer. If the BFU bouncer stops the cover, each stop re-arms and every Back is followed by a new
  automatic ask until the cap (3) - a finding for this rule, bounded, not a loop.
- (b) The shade and QS pulled over the occluded BFU keyguard (no lock task): airplane, Wi-Fi, data, Bluetooth,
  location tiles against SystemUI's own BFU face - not wider.
- (c) N2: the colour overlays after an android-mode boot with the cover as the face.
- §5's list: a reboot during a call (no ask while `isInManagedCall`, the read works BFU; the ask at the first resume
  after the call), the pad's Emergency button (the dialer over the cover, back to the cover), power with the pad up
  (the cancel not counted, the next screen-on asks), Back three times (the line, no loop; after 3 automatic asks only a
  tap asks), then the PIN: `Boot cover showWhenLocked false` and F3's `Boot cover leaves (taken over, Home over it)`.

**Fix round** (`docs/review/qa-59d-f-code.md`, F59d-1..7, all taken):
- F59d-1: Back is held while the flag is on - `holdBack` registers an `OnBackInvokedCallback` (the app has
  `enableOnBackInvokedCallback`) when `applyLockedFace` turns the flag on and unregisters it when it goes off, so F3's
  post-unlock Back is unchanged. Without it Back finished the cover (HOME, not LAUNCHER) and SystemUI's face showed.
- F59d-2: `bootPadStep` shows the line on every RESUMED that doesn't ask while no ask is open (a call, the cap, the
  screen off, no stop since the last ask) and on a STOPPED that closes an open ask. The face is bare only while its
  first ask is coming or an ask is out.
- F59d-3: SUCCEEDED clears the line, and `applyLockedFace` hides it (`faceViews`) whenever the gate is false.
- F59d-4: tests for a tap over an open ask, the line's gate and the resume's re-post of a due retry.
- F59d-5: the three KDocs and the 16b/F3 bullet say what the code does.
- F59d-6: the root is a tap target and a TalkBack node (labelled "Trykk for å låse opp") only while gated; the line
  has 24 dp side margins and its top is the pure `tapLineTopPx` - 156 dp under the centre, but at least 16 dp above
  the bottom system-bar inset (two lines at font scale 2.0 above a 48 dp navigation bar).
- F59d-7: §8.4 item 6's device list.

### 8.7 Step J (the user, 2026-10-23): swipe up to unlock, the "Lås opp" button goes

The user, testing rc.2: "skal vi ta vekk vår egen unlock knapp og heller også la vår låseskjerm bli swipet oppover
så den er identisk med android sin egen låseskjerm i funksjonalitet?" Yes: the glance behaves as Android's face - a
vertical swipe up anywhere on the page (not a row tap, not the music tile's controls; a fling of >= 48 dp upwards, or a
drag past a third of the page) asks for Android's pad the way "Lås opp" does today (the ASK stage, step B); the
"Lås opp" button goes; a small grey hint line at the bottom, "Sveip opp for å låse opp" / "Swipe up to unlock", in
Android's style, replaces it. "Nødsamtale" stays. Own mode: the same swipe opens our keypad (the button goes there
too; one behaviour, two gates). A bottom-edge swipe stays the system's (gesture navigation; lock task and the locked
keyguard refuse it) - the glance's gesture starts above the edge inset. Pure plan: `glanceSwipeStep(dy, vy, started
on a tappable)`; tests. Launcher, S. **After round 1 merges** (it edits `GlancePanel`/`LockGlance` too), before rc.3.

Also noted from the same test: Android's "Return to call" on the pad does nothing for a WhatsApp call (self-managed
Telecom calls have no in-call screen for `showInCallScreen` to bring up - the pad closes onto SystemUI's face). Not
ours; our own `InCallActivity` answers it for telephony. Check Element X on the emulator once (§8.4 item 2).

### 8.8 Step K revised, and step L (the user, 2026-10-23)

The user's call after the rc.2 recording: Android's lock wallpaper in android mode is **our glance's own ground**
("Jeg ville heller at VÅR bakgrunn er der på android sin klokkebakgrunn, ikke en svart en"), not the night mark.
Measured on the recording (24 fps), each of three wakes: 2 frames of SystemUI's blurred wallpaper with the lock dot,
then 3 frames of the sharp wallpaper with only the status bar, then our glance - no clock of Android's in any of
them; Android's clock shows only in the longer cases (after a call, Back from the pad). So step K (round 1) =
`lockScreenFill(.., androidFace)` gives the glance's own ground (the parent's lock wallpaper when allowed, else
Home's) with the glance's scrim baked into the bitmap, so the sharp frames read as our page; NightMark only where no
wallpaper exists, and for the boot cover's own look; re-applied whenever the glance's ground changes. **The photo
(code QA R1-1, the user's decision 2026-10-23):** as the face Android's lock wallpaper is the glance's ground
including a photo, whether or not "also on the lock screen" is ticked - the glance already shows that photo to anyone
holding the phone; the only new audience is the same person on SystemUI's face before the first unlock after a
reboot. Design 08's rule (the opt-in) now governs own mode's Android wallpaper only. Step L (the user's second recording, 2026-10-23: Android's small
single-line clock is up for exactly one frame at a wake, plus one cross-fade frame - thin, large, left-aligned high
up, "Fre. 23. okt." under it, over the sharp photo; ours is bold, centred, smaller, with the long date and the alarm
line): **build it with step J, before rc.3**. Our glance's clock takes Android's small clock's geometry - the same
left edge, baseline, size and a thin weight - and the date line its place and short format; the alarm line moves
under the date. Not pixel-identical (Pixel's Google Sans Flex isn't ours; the Jelly Star's AOSP SystemUI has another
font and placement), so the geometry is a small per-device profile (`emulator Pixel`, `Jelly Star`, measured from a
frame of each), not fixed numbers. Android's own clock font can't be changed by an app (Pixel's clock styles in
"Wallpaper & style" are the parent's by hand, during a pause - try the closest one on the emulator). Launcher, S-M.

### 8.9 The bottom-edge swipe on the glance (the user's third recording, 2026-10-23)

Three swipes up on the glance (kiosk on, android mode, music playing): each time the glance fades away upwards,
SystemUI's face shows (the sharp photo, the small clock, the media card), a black frame, then the glance is back -
about half a second each. The system's Home gesture from the bottom edge un-occludes the keyguard, and 59b's
re-front brings us back. Step J covers a swipe that starts above the edge inset (ours -> the pad). For the edge swipe:
with the kiosk on, lock task (features without HOME/OVERVIEW) should refuse the Home gesture (`canStartAnyGesture`,
`recents-over-lock-2026-10-22.md` §1); that it goes through is a finding. **Device check (with J's run)**: capture
which input consumer took the swipe (`dumpsys activity service com.google.android.apps.nexuslauncher/com.android.quickstep.TouchInteractionService`,
the `wm_*` events, the lock-task features at that moment). Then either (a) the flags were out of sync -> the 16d heal
is the place, or (b) Quickstep's `DeviceLockedInputConsumer` always un-occludes while locked -> the re-front after a
loss that a user gesture caused (no notification, no call, no screen event in the last second) asks for the pad at
once, so the edge swipe becomes "swipe, a flash, the pad". Decide from the capture.

### 8.10 Round 1 as built (branch `d59d-round1`, 2026-10-23)

Paths under `launcher/app/src/main/java/com/kidslauncher/mdm/` and `lock/` unless named; every rule is face-only and
own mode's rows are pinned unchanged.
- **C** (`c327af5a`): `lockUpStarts(.., face)` - LOCK as the face whatever `kg` read (the read stays for `sawLocked`);
  `LockEvent.ScreenOn(.., face, lockOnTop)` built in the screen receiver - the backstop skips a glance known to be on
  top (`faceOnTop`), and that skip keeps the mark (`screenOnUnshown`), a call's skip clears it as before
  (`PinLockState.kt`, `LockUpPlan.kt`, `PinLockRuntime.kt`; `PinLockStateTest`, `LockUpPlanTest`, one pin in
  `KeyguardCoverPlanTest`'s lock-up test, not the round-2 one).
- **N1** (`b2b2a97d`): the face's pause marks a probable SOS silently; the pause's check logs `SOS_MARK_LINE` only when
  the mark still holds (`sosPauseMarkLogDue`, `RefrontPolicy.kt`); a stop with no pause mark logs at once as before
  (`AndroidLockFaceTest`). After a face pause marked the SOS, the stop's `noteProbableSos` returns early (the mark is
  set), and the line comes from the stop's own check `PAUSE_REFRONT_MS` later, not at once - log only, the yield is
  unchanged (code QA R1-10).
- **H** (`0a8cc3dd`): `lockLeave(.., androidFace)` - kiosk on, `homeFirst = startedLockTask || (glanceInFront &&
  !(androidFace && callLive))`, `callLive` = `PinLockRuntime.callLiveNow` (`LockTaskRoot.kt`, `PinLockActivity.leave`;
  `LockTaskRootTest`); `launcher/CLAUDE.md`'s design 32 sentence.
- **B** (`729a9e2c`, follow-up `eec9ee20`): the `ASK` stage (`LockGlance.kt`: `glanceStageStep(.., face)`,
  `GlanceStageEvent.OwnKeypad`; `GlancePanel(.., face = { AndroidLock.faceOn })`; `PinLockActivity.onStage`/`tapAsFace`/
  `asking()`); `keypadIsAndroids` and `GlancePanel.androidPad` gone. **Deviation from §8.5**: `face` is read at each
  stage step, not fixed at the panel's construction - the lock activity lives across screen-offs, and an apply that
  turned the face on under a fixed `false` would have kept our keypad for hours. What is drawn (the user's refinements
  from the rc.2 recording, via the coordinator): the page stays drawn under Android's pad (ASK shows); at the leave, as
  the face and in front (`glanceLeaveHides`), the glance root's children go `INVISIBLE` (`glanceContentShown(leaving)`,
  `GlancePanel.leaving`/`leaveRefused`, shown again at the next resume); Home starts with `HomeFront.bring(..,
  noAnimation)` (ends the dark mark, hops to main; `HomeFrontTest` pins the two callers); the glance's own close is
  `overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)` (59b's fade gone). Home on black (3 frames in the rc.2
  recording: Android's wallpaper window not yet shown under Home's transparent window while the keyguard goes away):
  a `noAnimation` start marks `HomeFront.ownGroundDue`, Home's next resume draws its own ground until it stops
  (`ui/wallpaper/WallpaperGround.kt` `groundKind(.., ownGround)`, `ui/HomeActivity.kt`). Frames: before (the user's
  rc.2 recording, 24 fps) ~8 frames of the full page, 3 of its slide, 3 of Home on black; after (this branch on
  `handy36`, 24 fps, the default night wallpaper) 3 frames of the keyguard's dark going-away, 21 frames (~0.9 s, mostly
  Home's own resume on the emulator) of the plain ground, then Home filling in over 3 frames on its own ground - no
  page content, no slide, no black frame (the 21 frames are the glance's ground - the lock wallpaper under its
  scrim, no mark: code QA R1-11). The user re-records on rc.3.
- **E** (`c2bcbc23`): `noticeWakeOffDue(.., face)` false as the face (`NoticeWakePlan.kt`, `LockWakeTest`);
  `launcher/CLAUDE.md`'s notice-wake line; design 59's open question 3 closed. Android stays locked through the wake
  because of the sleep check (1 s after the screen-off) and the glance's resume rule (WAIT, then RELOCK), not because
  of `noticeWakeAllowed`, which gates on our LOCKED (code QA R1-9; §8.1 Q2 reworded).
- **K** (added by the user during the round; `acc306aa`, revised `d033537b`): as the face, Android's lock wallpaper is
  the glance's own ground - `lockScreenFill(home, androidFace, glance)` with `GlanceGround` (`lockWallpaper`'s choice and
  the glance's scrim, `lockScreenScrim`, baked into the lock bitmap in `WallpaperApplier.setBoth`); the night mark only
  while no ground is known. The apply key carries the lock fill and a `lockscrim=` part (own-mode keys unchanged), so a
  new ground, opt-in, scrim or mode re-applies; `AndroidLock.face`'s edges call `WallpaperStore.faceChanged`
  (`ui/wallpaper/Wallpapers.kt`, `WallpaperApplyPlan.kt`, `WallpaperApplier.kt`, `WallpaperStore.kt`;
  `WallpapersTest`). The first version (`acc306aa`, the night mark before the opt-in) was replaced by the user's answer.
- **G**: not built - §8.5 above and `docs/testing/2026-10-23-d59d-g-capture.md`.
- Not touched (round 2's): `BootCover*.kt`, `BootCoverManifestTest`, `KeyguardCoverPlanTest`'s "requestDismissKeyguard
  only in the lock". The `launcher/CLAUDE.md` lines left stale here went in the fix round (below).
- **Fix round** (`docs/review/qa-59d-round1-code.md`, R1-1..11; main merged in first, round 1's as-built moved from
  §8.6 to §8.10):
  - R1-1 (the user's decision): as the face Android's lock wallpaper is the glance's ground, photo included, ticked or
    not - design 08's rule, `launcher/CLAUDE.md`'s System wallpaper bullet, `Wallpapers.kt`'s KDoc and §8.8 say the
    opt-in governs own mode's Android wallpaper only.
  - R1-2: `launcher/CLAUDE.md`'s System wallpaper, Drawing, design 32 step 1, 59b *Look*, rc.2 fix round (N1) and
    notice-wake (glance section) bullets brought up to date.
  - R1-3: `HomeFront.bringAfterUnlock` passes `noAnimation = AndroidLock.faceOn` (the relock's queued Home after the
    pad's success no longer slides); device check in §8.4 item 3.
  - R1-4: the own-ground mark is an elapsed stamp set only after the start returned, taken once, and it expires
    (`ownGroundDue`, `OWN_GROUND_WINDOW_MS` 3 s, `HomeFront.kt`; `HomeFrontTest`) - a resume that took the night path
    leaves no mark for a later, unrelated resume.
  - R1-5, R1-7: device checks in §8.4 (items 3b and 2).
  - R1-6: a lock photo that can't be decoded falls back to the night mark for the lock half and is logged; Home's
    half is still set (`WallpaperApplier.setBoth`; `WallpapersTest` pins it).
  - R1-8 not built: a delayed re-check after the face's skip would have to repeat the re-front's exemptions (a woken
    alarm, the SOS gesture, the emergency flow, our call screen) - not a few lines, and a wrong start over an alarm or
    SOS is worse than the cosmetic stale mark it covers (Android holds either way).
  - R1-9..11: the wording above, in §8.1/§8.2, and in the code (`GlancePanel`, `LockGlance` KDoc, two test names).

### 8.11 Steps J and L, and §8.9's edge swipe, as built (branch `d59d-jl`, 2026-10-23)

Paths under `launcher/app/src/main/java/com/kidslauncher/mdm/lock/` unless named.
- **J** (the swipe up): `view_lock_glance.xml` loses `lock_glance_unlock` (and `bg_lock_unlock`); in its place the hint
  line `lock_glance_hint` ("Sveip opp for å låse opp" / "Swipe up to unlock", 14 sp `kid_core_dim`, 8 dp above; the
  bottom row 4 dp under it - the fit's `fixed` is 79.6 dp in the model, 116 with the button). Pure
  `glanceSwipeStep(dy, vy, startedOnTappable, dx, page, lifted)` (`LockGlance.kt`): a drag up past a third of the page
  (also before the lift), or at the lift a fling of >= 48 dp at >= 500 dp/s (`GLANCE_SWIPE_FLING_DP/_DPS`), always
  mostly vertical (`dy > |dx|`); `glanceSwipeBlocked(onControl, scrollTakes, downY, height, edgeInset)`: never from a
  control that keeps the touch (the music tile's buttons, the timer's Stopp, the flashlight, Nødsamtale), never a drag
  in the rows' scroll while it can scroll, never from the bottom edge's system-gesture inset (the larger of
  `systemGestures` and `mandatorySystemGestures` bottom) - the edge swipe stays the system's. A row and the timer tile
  are not controls here: a swipe from a row is the page's (as Home's swipe-up from a contact). Glue: `GlancePanel.swipe`
  (every touch of the lock's window first, from `PinLockActivity.dispatchTouchEvent`; not under the VoIP ring card,
  only on the GLANCE stage, never while leaving); a recognised swipe sends the views a cancel, keeps the rest of the
  touch and posts `step(GlanceStageEvent.Unlock)` - the same event the button sent, so the face's ASK and own mode's
  keypad are unchanged ("one behaviour, two gates"). The DOWN always reaches the views (`onUserInteraction`). No page
  animation follows the finger (calm; the pad is the feedback). **Accessibility**: the hint line is the unlock's
  TalkBack node - focusable for screen readers only, its click action labelled "Lås opp" (`lock_media_unlock`) runs the
  same `step(Unlock)` (`GlancePanel.hintAction`); a sighted tap on it does nothing (Android's hint isn't a button
  either). Tests: `LockGlanceTest` (the step's drag, fling, direction and block rows), `LockMediaGuardTest` (the
  touchables without the button, the hint's action, the swipe's only step is Unlock, the activity's feed),
  `LockGlanceGuardTest` (no row swipe/dismiss; the panel's only verdict is UNLOCK), `LockGlanceLayoutTest` (the fits at
  473/497 dp with the new bottom block). `scripts/smoke-test.sh` swipes up from the hint line.
- **L** (the clock as Android's small clock): pure `glanceClockProfile(model, manufacturer, density)` -> `ClockGeometry?`
  (`LockGlance.kt`) - `EMULATOR_PIXEL_CLOCK` (Google's `sdk_gphone*`), `JELLY_STAR_CLOCK` (Unihertz "Jelly Star"; a TODO
  with the emulator's numbers until a frame of its AOSP face is measured), only at 240 dpi; otherwise `null` and today's
  centred clock. Measured on `handy36` at `wm density 240` from SystemUI's small clock (`lockscreen_use_double_line_clock
  0`; the plain-lock run's frames and this run's): the digits' ink 92 px high from y 180 to a baseline at 273 px (the
  last ink row 272), the first digit's ink at x 53 for a "5", x 49 for a "1"; the date's capitals 17 px high on a
  baseline at 329 px, its ink at x 50. In Nunito SemiBold (digits 0.712 em high; the digits' side bearings run from
  0.041 em, "4", to 0.110 em, "1" - 5 to 14 px at this size): the clock 86 dp with its first digit's ink at 34 dp (51
  px, within 2 px of Android's "1" and "5"; the box moves with the digit's bearing, `GlancePanel.clockStartPx`, again
  at every text change) and its baseline at 182 dp; the date 16 sp, its box at 32 dp, its baseline at 219 dp. The first
  try put the box at a fixed 29 dp ("5" right, "1" 9 px off) - Nunito's bearings vary more than Google Sans Flex's. The user's frame's numbers (56 px, 40
  px, 180 px, 14 sp at 210 px) were the starting values; the emulator's own frames replaced them. The twin
  (`docs/testing/screens/59d-jl/clock-twin.png`, Android's left, ours right, each 240 px wide, both "10:18") matches
  the ink's left edge, baseline and height within 2 px; Nunito's "10:18" is wider (319 px of ink against 270). The clock in dp (Android's small clock doesn't follow the font scale), Nunito
  SemiBold (the lightest bundled; Android's is thinner - Google Sans Flex isn't ours), the date Android's short format
  capitalised on screen, the alarm line under the date at its start. `GlancePanel.placeProfile` at every fit sets the
  margins in window coordinates (`baselineMarginPx`, `startMarginPx`): the clock's box may reach above the page's content
  while its digits' ink stays inside (on the emulator the 136 px status bar ends below the box's top - the first frame
  put the ink 5 px low until this floor), the date may climb into the clock's descent but never above its baseline. The
  fit measures the placed views: one clock size (`clockBig == clockSmall`), so the rows and the tile give way first;
  `LockGlanceLayoutTest` models the profile's fits (music + 1 row takes the compact tile at 473/497 dp; no music and 4
  rows keeps one visible at 473). Consequence to know: Android's small clock is ~78 dp taller in the page than our big
  clock, so the full music tile shows with no row or alone more often - as on Android's face. Tests: `LockGlanceTest`
  (the profile table, the date, the margins), `LockGlanceLayoutTest` (the profile's fits, the font, the wiring),
  `LockGlanceGuardTest` (the profile's constant name in the one log line).
- **§8.9: decision (a)** (`docs/testing/2026-10-23-edge-swipe-capture.md`). With lock task's flags in sync Quickstep
  refuses the bottom-edge swipe over the locked glance - `TYPE_RESET_GESTURE` (`canStartSystemGesture=false`,
  `home_disabled`), the glance keeps the focus - kiosk on (three runs, one after an unlock and re-lock) and kiosk off
  (the face roots lock task while Android is locked). `DeviceLockedInputConsumer` comes only with the Home disable
  missing (HOME left in the LOCKED features through the debug override, standing in for 16d's desync of SystemUI's
  TaskbarDelegate): it **starts Home**, the keyguard un-occludes, Home's locked resume puts the lock back - the user's
  frames exactly. So (b)'s premise ("always un-occludes while locked") is false and (b) isn't built; the user's swipes
  went through on desynced flags. **Built**: the 16d heal gets a trigger - a Home start that isn't ours
  (`HomeFront.EXTRA_BROUGHT`) while our lock is LOCKED with the screen on is the desync's proof
  (`HomeActivity.onNewIntent` -> `PinLockRuntime.homeIntent` -> pure `systemHomeHealDue(locked, interactive,
  broughtByUs, lastHealElapsedMs, now)` -> `HealTrigger.SYSTEM_HOME`, at most every `SYSTEM_HOME_HEAL_MIN_GAP_MS` 5 s;
  it also stamps the lock-resume heal's minute so the resume that follows doesn't blink again), so the next swipe is
  refused. The first swipe's flash stays: an app can't read SystemUI's flags before it. Mode-agnostic (own mode's lock
  task has the same flags). On `handy36` the trigger fired at the simulated desync's Home start (`The system's Home
  while LOCKED (a home gesture got through): lock task's flags healed`, then `Status bar heal (the system's Home while
  locked): lock-task features 117 -> 116`). Not reproduced: a natural desync (killing Quickstep left the flags in
  sync; 16d saw it after some boots) - so whether the flip cures the user's case is 16d's evidence, not this run's.
  Tests: `StatusBarHealTest` (the rule's rows, the runtime's and Home's wiring, every trigger called).
- **Device checks** (`handy36`, `wm density 240`, kiosk on, android mode unless named; the capture's setup): a content
  swipe (`input swipe 240 600 240 300 150`) asks for Android's pad (`Glance swipe up: unlock asked`, the bouncer has the
  focus); Back from the pad returns to the page (`cancelled`, the glance's focus); a missed call's row tapped, the Child
  PIN into the pad -> the contact's sheet (`Glance: opened the missed calls`); own mode (the Lock row back to Vibb One's)
  -> the same swipe opens our keypad; the bottom-edge swipe as in the capture. One row tap under a loaded host (a second
  emulator ran beside) hit the 2 s dead-ask recovery while the pad was still coming up, so the leave came by
  `USER_PRESENT` and the row's target was dropped - round 1's behaviour (`ANDROID_ASK_TIMEOUT_MS`), not J's; the retry
  with the PIN typed within 1.2 s opened it.

### 8.13 Step J addendum (the user, 2026-10-23): the handle instead of the hint line

The user, looking at the clock twin: our glance should show Android's gesture handle (the bright pill at the bottom of
SystemUI's face) in the same place, as the thing you grab and swipe up, and the hint text goes. Rules: (1) we draw the
pill ourselves - lock task hides SystemUI's navigation bar while we are LOCKED, so there is never two; same size,
place and colour as SystemUI's handle on that device (profile numbers; the fallback = Android's defaults: 108 dp x 4 dp,
rounded, 8 dp above the bottom inset, white at 80 %); (2) a swipe that starts on the pill (in the bottom gesture inset)
counts as the page's swipe: when lock task refuses the system's Home gesture the touches reach our window, and when
the system takes the gesture (a desync, §8.9) we get `ACTION_CANCEL` and do nothing - so `glanceSwipeBlocked`'s
bottom-edge rule goes; (3) the hint line "Sveip opp for å låse opp" goes; TalkBack's "Lås opp" click action moves to
the pill (a focusable node with a content description); (4) own mode: the same pill, the same swipe, our keypad.
Built in J's fix round.
