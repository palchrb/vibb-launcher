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
| A | S | One face-mode "after the unlock" queue. `KeyguardCover.mustWait` is also true for the face while Android is locked. Through it go the time-rule screen (already via `whenUnlocked`), `WindowRelock`'s, Play's, `SelfUpdate`'s and `AppEnforcer`'s Home starts (a `HomeFront.bring` gate), and the VoIP reopen for a non-showWhenLocked app. The queue runs on LEAVE. `ensureLockTask` START_SELF in face mode |
| B | S | No KEYPAD stage in face mode: "Lås opp" and a row ask from the glance page (the target kept, `keypadIsAndroids` goes). On LEAVE the content goes INVISIBLE (black ground) before `go()`; Home `NO_ANIMATION`. Fixes R1 |
| C | S | Screen events in face mode: `lockUpStarts` takes LOCK for the face whatever `kg` read (no HOME_THEN_LOCK). No ScreenOn backstop start (`PinLockState.kt:173`). Check with 59b step 4's capture (R2) |
| D | S | SOS in face mode: the exemption only, no `narrowForSos`. Fold into 59b step 2 |
| E | S | The notice wake ends without `lockNow` in face mode (the phone's own timeout), or keeps it. The user's choice |
| F | M | BFU: Android's pad over the night cover (§5). After the F3 merge |
| G | S | F2: hold the LOCKED-chrome feature write while an ask is open |
| H | S | Q1's answer: if "back to the last app", `lockLeave` never starts Home in face mode except as the kiosk root |

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

## 7. Questions for the user

1. **Q1**: after "Lås opp" on the glance, land on Home (as now, design 32 decision 2) or back in the app the kid was
   in (Android's normal)? The second removes the last Home start of the face. Default: Home.
2. **Q2 (step E)**: the notice wake's 15 s end with `lockNow` costs the fingerprint until the next PIN. End it by the
   phone's own screen timeout instead? Default: no `lockNow` in android mode.
3. **Q3 (phase D)**: no Android credential (removed on the phone): SystemUI's swipe face until `set_kid_pin` lands,
   or keep our keypad? Default: the command, and the PWA warns.
