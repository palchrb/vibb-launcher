# Design QA: 59d round plan (steps B, C, E, F, G, H and N1)

Reviewed: `docs/design/59d-android-mode-inventory.md` §2, §3, §5, §7 and §8 at `466f7b8c`, against the code on `main` at the
same commit. Inputs: 59b (design, "As built", `qa-59b-code.md`), design 59 "Step 3 as built" and "F3", `qa-f3-round2-code.md`,
the rc.2 run and re-check, PLAN.md. Paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/lock/` unless named.
Read-only on code.

**Bar**: no step opens a path past Android's PIN. Every exit still needs `faceStep`'s LEAVE (the pad's success or
`USER_PRESENT` since the LOCKED edge). B's hiding and H's missing Home only shape a leave that has already been
decided. F's cover shows only the mark, and `requestDismissKeyguard` is an ask. Two Blockers remain: as written, C
regresses the first screen-on after any dark process start, and E describes a screen-off path that doesn't exist.

## Findings

**Q59d-1 (Blocker, step C): with no ScreenOn backstop in face mode, nothing starts the glance after a dark process
start or a failed lock-up start.**
- `PinLockState.kt:162`: a `ProcessStart` while dark shows nothing. Examples are the night's self-update (qa-59b-code
  B-1's own scenario), a crash, or an LMK kill.
- `PinLockRuntime.kt:1550-1567` (`show()`): a lock-up start that threw returns `false`, so `lockOnTop` isn't set.
- In both cases the `ScreenOn` row (`PinLockState.kt:177-181`) is the only start at the next wake.
- §2.1 says "59b's re-front covers a real loss". It doesn't: `androidLossStep` runs only from a check that a fresh
  pause or stop of an existing glance scheduled (`markAndroidLoss`, `PinLockRuntime.kt:1639`, `:1662`). A glance that
  isn't on top at the wake gets neither.
- So as written, SystemUI's face shows for that whole screen-on: the R2 look the round is meant to remove.
- **Proposed wording (§3 C, §8 item 2)**: "As the face, the ScreenOn backstop starts the glance only when it isn't known
  to be on top: `showLock = LOCKED && !lockShowing && !lockOnTop && !systemCall && voip == NONE` (a new `face` and
  `lockOnTop` input on `LockEvent.ScreenOn`). A screen-on that skips the start because `lockOnTop` is set doesn't clear
  the mark (`screenOnUnshown` takes the face's skip as shown). A new process (`lockOnTop` false) and a lock-up whose
  start threw still get the start. Own mode unchanged."
- Tests (`PinLockStateTest`, `LockUpPlanTest`):
  - face + `lockOnTop` -> no start;
  - face + new process -> start;
  - own mode's rows unchanged.

**Q59d-2 (Blocker, step E): there is no "end of the wake minus `lockNow`". `lockNow` is the screen-off.**
- `noticeWakeOffCheck` (`PinLockRuntime.kt:1388-1410`) turns the screen off only with `dpm.lockNow()`.
  `VoipWakeActivity` finishes itself after 500 ms (`VoipWakeActivity.kt:29`, `:44`).
- `goToSleep` needs `DEVICE_POWER`. §8's "use the same path the wake's end uses today minus `lockNow`" therefore has
  nothing to implement.
- The lock-after question in §8 is moot:
  - Android's keyguard is locked for the whole notice wake. The wake only lights the occluding glance.
  - `noticeWakeAllowed` needs LOCKED and 2 s since the lock-up (`NoticeWakePlan.kt:23`), and the sleep check has
    relocked an open Android 1 s after the screen-off.
  - So there is no unlocked window, whatever `lock_screen_lock_after_timeout` is. For the record, T = the screen
    timeout makes the delay 0 for a timeout sleep (§6b).
- **Proposed wording (§8 Q2)**: "As the face, the notice wake has no 15 s end. `noticeWakeOffDue(.., face = true)` is
  false, so `noticeWakeOff` does nothing. The glance stays lit until the phone's own screen timeout: the parent's
  `screen_timeout_seconds`, 15 s-10 min, default 60 s (`server/ScreenTimeout.kt:16-18`). The keyguard is occluded, so
  SystemUI's 10 s lock-screen timeout doesn't apply. Then the screen-off locks as always. Android stays locked
  throughout, and the fingerprint keeps working. Own mode keeps `lockNow`."
- Name the cost the user accepted (design 59 open question 3): up to the full timeout of lit rows in a pocket. The
  proximity read happens only at the start.
- Tests: `LockWakeTest` with a face row.
- Docs: drop `launcher/CLAUDE.md:288`'s "The notice wake keeps its `lockNow` after 15 s", and close 59 open
  question 3.

**Q59d-3 (Should, step G): the hold's bound is the wrong constant, and the write that cancels the pad isn't known.**
- `ANDROID_ASK_TIMEOUT_MS` (2 s, `AndroidLockPlan.kt:130`) is the dead-ask recovery while our window has the focus. It
  isn't the pad's lifetime: a kid typing a PIN takes longer, so a 2 s hold still cancels the pad.
- RC F2 (`2026-10-22-d59-rc-run.md:429-437`) shows two applies at `end-windows`, and the cancel came after them. A
  pause's end changes the kiosk's packages (`setLockTaskPackages`, `LockTaskChrome.kt:285-305`) as well as the
  features, so "the LOCKED features write" is a guess.
- The ask state is main-thread private (`PinLockRuntime.kt:204`), but `applyPlan` runs on apply's background thread
  (`LockTaskChrome.kt:97-121`).
- **Proposed wording (§8 item 3)**: "While an ask is open, every lock-task DPM write of a chrome pass waits: packages,
  features, status bar, `DISALLOW_CREATE_WINDOWS` and a heal's flip. 'Open' means `androidAskAt` is set: from
  `noteAndroidAsk(open = true)` to its close by any answer, the screen-off or `ANDROID_ASK_OPEN_MS`. The `Plan` is
  stored at once, so the UNLOCKED edge's pass writes the newest one. The ask's close runs one `refresh`. The ask stamp
  is read through a `@Volatile` accessor."
- Add a device step first: capture which write precedes the `cancelled` (`wm_*`, `LockTaskChrome` lines around
  `end-windows`).
- Tests: a pure `chromeHeld(askOpen, ..)` and a pinned release on the ask's close.

**Q59d-4 (Should, step H): kiosk off already does Q1, so H changes only the kiosk-on row, and the call source is
misnamed.**
- `lockLeave` kiosk off is `homeFirst = glanceInFront && !callLive` (`LockTaskRoot.kt:87`; design 32 QA L2). Kiosk on
  is `glanceInFront || startedLockTask` (`:85`), with no call input.
- The kiosk root must keep Home. Stopping a root lock task runs `clearLockedTasks`, which finishes every locked task
  (R2's `DeskClock,clear-task-all`), the call app's included, and only Home re-roots the kiosk.
- `callLiveNow` (`PinLockRuntime.kt:2007-2008`, main thread, read in `leave()` at `PinLockActivity.kt:1064`) is
  already the reliable source on both sides:
  - `OngoingCalls.hasLiveCall` (ours);
  - Telecom's `isInManagedCall`, else the audio mode;
  - `VoipCalls.phase != NONE` (ringing or IN_CALL, its 15 s grace included).
- §8's "IN_COMMUNICATION or ringing (59b's `voipGrace`/`VoipCalls` state)" is a narrower set. `voipGrace` is the 3 s
  ring timer, not a call state.
- **Proposed wording (§8 item 4)**: "`lockLeave(.., androidFace)`: kiosk on, `homeFirst = startedLockTask ||
  (glanceInFront && !(androidFace && callLive))`. Kiosk off is unchanged (it already has the rule). `callLive` is
  `PinLockRuntime.callLiveNow`. Own mode is unchanged."
- Safe because:
  - with the kiosk on, only locked tasks can come to the front when the glance finishes (the call's or Home);
  - with the kiosk off, it is today's rule.
- Tests (`LockTaskRootTest`):
  - face, kiosk on, not root, call -> `(false, false)`;
  - root + call -> `(true, true)`;
  - own mode rows equal for every input.
- Docs: amend `launcher/CLAUDE.md`'s design 32 sentence ("Unlocking lands on Home in every mode ... never over a live
  call").

**Q59d-5 (Should, step B): the motivation is stale, and "content INVISIBLE" needs a named view.**
- The rc.2 run's check 9 PASSED (`2026-10-22-rc2-run.md:138-141`): the pad, then the glance page as it was for
  ~0.8 s, then Home sliding in. No keypad chrome showed: 59b's `glancePageShown` fixed R1.
- What B can still change: the 0.8 s page and Home's slide. Is a blank ground for 0.8 s better than the page? That is
  a look decision; the doc presents it as the flicker fix.
- Which view hides is a correctness question, not only a look:
  - the decor view `INVISIBLE` hides the window (ViewRootImpl relays the decor's visibility);
  - `GlanceOpener`'s `PendingIntent` send needs our window visible (`PinLockActivity.kt:1069-1078`);
  - so hiding the decor would break a row's target.
- **Proposed wording (§8 item 1)**: "Target frames after the pad's success: the night ground with the mark, then Home
  with no slide (a recording against check 9's). On LEAVE, as the face and in front, the glance page's children go
  `INVISIBLE`. `glance.root` stays visible and keeps the ground (`GlancePanel.ground`); never the decor view or the
  window. If the finish is still refused after `rootLeave`, the content shows again."

**Q59d-6 (Should, step B): the KEYPAD stage is the ask's trigger and the target's holder, so removing it needs a
replacement.**
- "Lås opp" and a row step the panel to KEYPAD. `onStage` then calls `tapAsFace` (`GlancePanel.kt:273-278`,
  `PinLockActivity.kt:267-270`).
- The row's target lives in `GlanceStageState(KEYPAD, target)` (`LockGlance.kt:93-99`).
- Every cancel, error, recovery and STAY drops it with `glance?.back()` (`PinLockActivity.kt:307`, `:336`, `:341`,
  `:366`, `:385`, `:388`).
- `OWN_KEYPAD` (the face with no credential at the tap, `:280-283`) still needs our keypad, and so does own mode.
- **Proposed wording (§3 B)**:
  - "A third stage `ASK`: `glanceStageStep(.., face)` gives `ASK(target)` for Unlock/Row as the face. The page is
    shown, and the keypad is never visible or focusable.
  - Back, a cancel, an error, the recovery, STAY and LockUp give GLANCE (the target dropped).
  - A tap in ASK runs `tapAsFace` again (`askAgain`); a row tap in ASK replaces the target.
  - `OWN_KEYPAD` gives KEYPAD (our keys). Own mode never enters ASK.
  - `keypadIsAndroids`, `GlancePanel.androidPad` and `glancePageShown`'s pad input go."
- Tests to rewrite: `AndroidLockFaceTest` (the B-6 pins at `:260-279`) and `LockGlanceTest` (the stage machine with
  the face).

**Q59d-7 (Should, step B): `animate = false` is not just "no animation".**
- `HomeFront.bring(.., animate = false)` skips `endDarkLockUp()` and the main-thread hop (`HomeFront.kt:59-69`).
- `HomeFrontTest.kt:103-107` pins exactly one `animate = false`, the lock-up's.
- **Proposed wording**: "The leave's Home uses a new `noAnimation` parameter (`FLAG_ACTIVITY_NO_ANIMATION`). It still
  ends the dark mark and still hops to the main thread. `HomeFrontTest` pins the two callers."
- Also say whether the face's close fade (`PinLockActivity.kt:1085`) stays. With hidden content it does nothing.

**Q59d-8 (Should, step F): gate the runtime flag and the ask on android mode and a live unlock read, and name the pins
that flip.**
- §5 says "while the user is locked". The cover also runs in own mode (16b's `boot_cover` switch), and
  `waitForLauncher` comes from the DE marker (`BootCoverActivity.kt:97`).
- An own-mode phone with an Android credential (lock inactive = `android_credential`) would otherwise get an
  occluding, asking cover.
- **Proposed wording (§5 1-2)**: "Only with `waitForLauncher` (the android marker) and `isUserUnlocked == false`,
  read again before every ask and at every resume. The `ACTION_USER_UNLOCKED` receiver and `onResume` call
  `setShowWhenLocked(false)` once it is true."
- Pins that flip:
  - `BootCoverManifestTest.kt:89-94` bans `setShowWhenLocked` and `requestDismissKeyguard` in the cover. Keep the
    manifest attributes banned; allow the two calls only inside the gated functions (pin their bodies).
  - `KeyguardCoverPlanTest.kt:400-409` (`requestDismissKeyguard only in the lock`) gains `BootCoverActivity.kt` with
    one call.
  - `launcher/CLAUDE.md`'s 16b/F3 bullet and design 59's F3 "Never over the keyguard" (`59-android-keyguard-design.md:843-846`)
    are amended in the same commit.

**Q59d-9 (Should, step F): the ask rule misses a live call, an error, and what counts towards the cap.**
- The Home key during a BFU call brings the HOME cover over our `InCallActivity`. Its resume would then auto-ask: the
  pad over a live call, with no way back to the call screen but SystemUI's call chip.
- The cover reads `telecomInCall()` only once unlocked (`BootCoverActivity.kt:195`). `isInManagedCall` works BFU
  (same uid, Telecom is system).
- An `onDismissError` at the first resume (the keyguard not ready right after `LOCKED_BOOT_COMPLETED`) isn't covered.
- **Proposed wording (`bootPadStep`)**:
  - no automatic ask while `telecomInCall()`; a tap still asks;
  - an error retries once after 1 s, counted;
  - a cancel with the screen off (power with the pad up, §5 rule 4) isn't counted towards the 3;
  - the count is per `:bootcover` process, in memory (a new process may ask 3 more; the cover's crash guard bounds a
    loop).

**Q59d-10 (Should, step F): the device gates are incomplete.**
- Add three gates:
  1. Whether a runtime-flagged HOME activity occludes the BFU keyguard at all. If it doesn't, the cover is never
     resumed while locked: no ask, today's behaviour. Say that this is the fallback.
  2. The shade and QS over the occluded BFU keyguard, with no lock task. Check airplane mode, Wi-Fi, data, Bluetooth
     and location tiles against SystemUI's own BFU face: the occluded shade must not be wider.
  3. Whether the cover as the BFU face changes N2 (the colour overlays missing after an android-mode boot).

**Q59d-11 (Should, N1 / step C): N1's pause/resume pair is very likely the ScreenOn backstop's new-intent delivery.**
- The backstop's `REORDER_TO_FRONT` start onto the resumed glance gives `START_DELIVERED_TO_TOP`, so a pause,
  `onNewIntent` and a resume (`LockUpPlan.kt:102-112`).
- Design 32 logged `wm_new_intent PinLock` 15-35 ms after each wake. N1 sees the pair 5-35 ms after `screen-on`.
- `sosMarkAtPause` (`PinLockRuntime.kt:1632-1635`) then logs it.
- Q59d-1's gated backstop removes the pair at its cause.
- **Proposed order**: C, then N1. N1 stays as the cheap log guard: log the mark only when the 700 ms check still finds
  it. Confirm with `wm_new_intent` in 59b step 4's capture.

**Q59d-12 (Nit, step C): keep the `kg` read.**
- `kg` also feeds `AndroidLock.sawLocked` (S3-3, `PinLockRuntime.kt:758-760`). Only the plan ignores it as the face.
- Name the accepted cost: with Android open at the screen-off and a wake within the 1 s sleep check, the app's
  snapshot shows for a frame (design 32's flash).
- `LockUpPlanTest`: face, not locked -> LOCK; own mode rows unchanged.

**Q59d-13 (Nit, scope): R2's decision isn't placed.**
- The decision is "as the face, kiosk off, no own lock task" (PLAN "next 59b round").
- It touches `lockTaskEntry`, `lockLeave` and `LockTaskChrome`, which B, G and H also edit.
- §8's "Out of the round" should list it, or the round should take it.

**Q59d-14 (Nit, size and order): split F.**
- F is its own process, files and tests (`BootCover*`, `BootCoverManifestTest`, `BootCoverPlanTest`), and BFU device
  checks make up most of its cost.
- **Proposed**:
  - round 1 (one implementer, one QA): C, N1, H, B (H before B: both edit `leave()`/`go()`), E, then G after its
    device capture;
  - round 2: F in its own worktree and QA. It can run in parallel, since the only overlap is
    `KeyguardCoverPlanTest` and `launcher/CLAUDE.md`.

## Verdict

**Implement after these revisions.** The direction is sound, and none of the steps re-opens a way past the keyguard:
B and H act only after Android's verdict, E leaves Android locked throughout, and F's cover shows only the mark and
only asks.

Two revisions are needed before work starts:
- **Q59d-1**: C must keep the ScreenOn backstop's start for a glance that isn't known to be on top.
- **Q59d-2**: E's "screen-off without `lockNow`" must become "no 15 s end; the phone's own timeout".

The Shoulds are specification gaps that an implementer would otherwise guess at:
- G's bound and write set;
- H's kiosk-on row and call source;
- B's stage replacement, hidden view and Home flag;
- F's gate, call rule and pins.

Split F into its own round.
