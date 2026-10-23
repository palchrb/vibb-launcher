# Code QA: design 59d round 1 (steps C, N1, H, B, E, K; G not built)

Reviewed: branch `d59d-round1` (c327af5a, b2b2a97d, 0a8cc3dd, 729a9e2c, c2bcbc23, eec9ee20, acc306aa, d033537b,
91054fc3) on `main` a21c4700, against `docs/design/59d-android-mode-inventory.md` §8 (8.1, 8.2, 8.4, 8.5, 8.6 "As
built"; §8.8's step K revision is on `main`, not on the branch), `qa-59d-design.md`, `qa-59d-revision-check.md` and
`docs/testing/2026-10-23-d59d-g-capture.md`. Paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless
named. No device run in this review: what needs one is said per finding.

## Build and tests

`launcher/`, JDK 17, no `app/libs/tsnet.aar` (debug stub):
`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest` - **exit 0**, BUILD
SUCCESSFUL, **1596 tests, 0 failures, 0 errors, 0 skipped**. `assembleRelease` **skipped**: no `tsnet.aar` in this
worktree (`checkReleaseHasTsnet` would fail it by design).

## The bar

- **No path past Android's PIN.** Every exit of the glance as the face still goes through `unlock()` from
  `actAsFace(LEAVE)` / `androidAnswered("succeeded")` with `faceStep`'s LEAVE (`lock/PinLockActivity.kt:339`, `:381`).
  None of the six steps adds a caller of `unlock`/`leave` or relaxes a `faceStep` input. B and H only shape a leave
  already decided (`leave()` runs after `PinLockRuntime.unlocked`).
- **B never hides the window**: `GlancePanel.showContent` (`lock/GlancePanel.kt:302`) sets only `glance.root`'s
  children; the root keeps its ground, there is no `decorView` in the panel (pinned), so `GlanceOpener`'s
  `PendingIntent` send in `go()` runs with our window visible. A refused finish shows the content again at once
  (`PinLockActivity.kt:1080`) and a leave that didn't finish within its resume at the next `resume()` (`:219`).
- **H with the kiosk on** (`lock/LockTaskRoot.kt:97`): no Home only when not the root; the glance finishes inside the
  running lock task, so only locked (kiosk-list) tasks can come forward. The one latent-task case is a stale
  BlockedAppActivity task between the glance and the call (16d's residual): it needs a Recents start while LOCKED,
  which the occluded secure keyguard refuses as the face. Same exposure as today's kiosk-off rule; no PIN involved
  (Android is open at that point). Device check 2 should look at what surfaces (R1-7).
- **C still relocks an open Android**: as the face the lock-up is LOCK whatever `kg` read (`lock/LockUpPlan.kt:55`),
  `kg` still feeds `sawLocked` (`lock/PinLockRuntime.kt:767`), and `androidSleepCheck` is untouched (RELOCK 1 s after
  the screen-off while dark). A wake inside that second is decided by the glance's resume (`resumeAsFace` -> WAIT ->
  RELOCK), unchanged.
- **E keeps Android locked**: `noticeWakeAllowed` needs our LOCKED, dark and no lock-up in the last 2 s
  (`lock/NoticeWakePlan.kt:70`); Android's own state is held by the sleep check (1 s after the screen-off) and, should
  Android read open at the wake, by the glance's resume rule (WAIT, then RELOCK - the wake's `show` resumes the glance
  with the screen on). E removes only the 15 s `lockNow`; nothing in the branch writes `setKeyguardDisabled` or a
  keyguard feature (`KeyguardCover` untouched). Note: "Android locked throughout" holds because of the sleep check and
  the resume rule, not because of `noticeWakeAllowed` itself - the §8.1 wording overstates the gate a little (R1-9).
- **Own mode byte for byte**: every new input defaults to own mode's value, and each step pins own mode's rows:
  `PinLockStateTest` (ScreenOn rows equal with `lockOnTop` for `face = false`), `LockUpPlanTest`, `LockTaskRootTest`
  (every input), `LockGlanceTest` (every state x event), `LockWakeTest`, `WallpapersTest` (fills and **keys** equal),
  `HomeFrontTest` (the one `noAnimation = ` caller passes `AndroidLock.faceOn`). Own mode never sets `ownGroundDue`.
  The mutations below confirm each pin bites.

## Mutations

Each applied alone to `launcher/app/src/main`, then `testDebugUnitTest --tests 'com.kidslauncher.mdm.lock.*' --tests
'com.kidslauncher.mdm.ui.*'` under the build lock, then reverted (the tree is clean). 26 mutations compiled, and all 26
were killed. E2 didn't compile (`false &&` loses the smart cast), so E3 replaces it.

| # | Step | Mutation | Result: killed by |
|---|------|----------|-------------------|
| C1 | C | `faceOnTop = face` (lockOnTop ignored) | `PinLockStateTest` "as the face the screen-on backstop skips a glance on top and still starts after a new process" |
| C2 | C | `!event.faceOnTop` dropped from the ScreenOn row | same |
| C3 | C | `screenOnUnshown` without the face's exception (the skip clears the mark) | `LockUpPlanTest` "the face's backstop skip keeps lockOnTop, the event built with the live reads" |
| C4 | C | `coverLocked \|\| face -> LOCK` becomes `true -> LOCK` (own mode takes LOCK) | `LockUpPlanTest` x3 (own-mode rows, HOME_THEN_LOCK, lockOnTop), `KeyguardCoverPlanTest` |
| C5 | C | the screen receiver builds `ScreenOn(.., lockOnTop = false)` | `LockUpPlanTest` (live reads pin) |
| N1 | N1 | `sosPauseMarkLogDue = unlogged` (no 700 ms check) | `AndroidLockFaceTest` "N1 - a pause's SOS mark is logged only when the pause's check still finds it" |
| N1b | N1 | the pause's `noteProbableSos(pausedNow)` logs at once | `AndroidLockFaceTest` "R1 - as the face a pause in the SOS window is the mark" |
| H1 | H | kiosk on: `homeFirst = startedLockTask \|\| glanceInFront` (Home over a call) | `LockTaskRootTest` "59d H - as the face with the kiosk on no Home over a live call" |
| H2 | H | the root exception dropped | `LockTaskRootTest` x2 (59d H, 59b step 0) |
| H3 | H | `!(androidFace && callLive)` becomes `!callLive` (own mode changed) | `LockTaskRootTest` x2 (kiosk-on rows, own-mode rows) |
| B1 | B | Unlock -> ASK whatever the face (ASK in own mode) | `LockGlanceTest` x2 (own mode never enters ASK, the keypad rows) |
| B2 | B | a row tap in ASK drops the target | `LockGlanceTest` "as the face Lås opp and a row lead to ASK" |
| B3 | B | content hidden at ASK (`glanceContentShown(leaving \|\| ASK)` in `GlancePanel`) | `AndroidLockFaceTest` "59d B - the page shows under the pad" |
| B3b | B | `glancePageShown` = GLANCE only (page hidden under the pad) | `AndroidLockFaceTest`, `LockGlanceTest` |
| B4 | B | `noAnimation` skips `endDarkLockUp` | `HomeFrontTest` "HomeFront without an animation adds only NO_ANIMATION" |
| B5 | B | the leave's Home without `noAnimation` | `AndroidLockFaceTest` (leave order), `HomeFrontTest` (the two callers) |
| B6 | B | Back from ASK keeps ASK | `LockGlanceTest` |
| B7 | B | `leaveRefused()` after `rootLeave` dropped | `AndroidLockFaceTest` "59d B - the face's leave hides the content first" |
| E1 | E | `!face &&` dropped (`lockNow` back as the face) | `LockWakeTest` "as the face the notice wake has no 15 s end" |
| E2 | E | `false && ..` (own mode loses it) | compile failure (smart cast) - see E3 |
| E3 | E | `face && ..` (own mode loses `lockNow`, the face gets it) | `LockWakeTest` x2 (the 15 s off, the face row) |
| K1 | K | the opt-in photo branch before the face branch | `WallpapersTest` "59d K - as Android's face its lock wallpaper is the glance's own ground" |
| K2 | K | the scrim bake (`drawColor(lockScrim)`) removed | `WallpapersTest` "59d K - the key follows the glance's ground" (source pin) |
| K3 | K | `faceChanged` without `applyAndRefresh` | same (source pin) |
| K4 | K | the key without its `lockscrim=` part | same |
| K5 | K | `AndroidLock.face`'s edge no longer calls `faceChanged` | same (source pin) |
| K6 | B/K | `groundKind` ignores `ownGround` | `HomeFrontTest` "Home after the face's leave draws its own ground until it stops" |

K2, K3 and K5 are killed by source pins, not by behaviour: the bake and the re-apply run in Android glue
(`WallpaperManager`) that a JVM test can't run. That is acceptable here, and the device checks below cover them.

## Findings

**R1-1 (Should, K: the lock-photo opt-in no longer governs Android's lock wallpaper; decide it with the user and write it
down)** - `ui/wallpaper/Wallpapers.kt:113`.
- As the face, Android's lock wallpaper is now `lockWallpaper`'s choice, photo or not. That is the parent's lock
  wallpaper, or Home's photo with no "also on the lock screen" tick. Design 08 (QA 08 #1) and `launcher/CLAUDE.md:143`
  say the opposite: "Photos go on the home screen only".
- The glance already shows that photo to anyone holding an unlocked-boot phone (design 25), so the exposure after the
  first unlock is unchanged.
- What is new: the photo is on **SystemUI's BFU face after every reboot**. Our process can't draw there before the
  PIN, and the boot cover is not over the keyguard. The system also keeps the photo in its own storage, which is
  readable before the first unlock.
- The user asked for "VÅR bakgrunn" (§8.8), so this is probably wanted. Ask once, concretely: "after a restart,
  anyone who picks up the phone sees the family photo on Android's lock screen". Then either:
  - (a) keep it as built, and amend design 08's rule, `launcher/CLAUDE.md:143` and the 59b *Look* bullet; or
  - (b) as the face, use a photo only with the opt-in, and otherwise the glance's scrimmed solid/gradient or the
    night mark. Then SystemUI's frames at a wake differ from the page whenever the ground is a photo without the opt-in.

**R1-2 (Should, docs: `launcher/CLAUDE.md` lines that are now wrong)**
The As built names only the 59b bullets. The full list is:
- `:235-238` (59b *Look*): "the lock wallpaper as the face is `WallpaperFill.NightMark` ... the parent's lock-photo
  opt-in wins". Now: the glance's ground with its scrim baked in (`lockScreenFill(.., glance)`, `lockScreenScrim`), and
  the mark only while no ground is known. "Under Android's PIN pad the glance page stays drawn over the keypad stage
  (`glancePageShown`, `GlancePanel.androidPad`)" should become the ASK stage (`glanceStageStep(.., face)`,
  `GlanceStageEvent.OwnKeypad`, `asking()`). "the face leaves with a close fade" should become: the content hidden at
  the leave (`glanceLeaveHides`, `GlancePanel.leaving`/`leaveRefused`), Home with `noAnimation`, and an unanimated
  finish.
- `:143` (System wallpaper): "never on resume - only after a sync or a pick" now also covers the face's edges
  (`WallpaperStore.faceChanged`). "Photos go on the home screen only" needs R1-1's outcome. The key also carries
  `lockscrim=` as the face.
- `:145` (Drawing): Home also draws its own ground after the face's leave until it stops (`HomeFront.takeOwnGroundDue`,
  `groundKind(.., ownGround)`).
- `:168` (design 32 step 1): "HOME_THEN_LOCK while dark with no live call" needs the face's exception: LOCK whatever
  `kg` read (`lockUpStarts(.., face)`). The ScreenOn backstop as the face is gated on `faceOnTop`, and its skip keeps
  the mark (`screenOnUnshown`).
- `:687` (the notice wake, glance section): "`lockNow()` turns the screen off ... (HOME_THEN_LOCK ...)" is own mode
  only. As the face there is no 15 s end (it points to the 59 bullet). The lock-up after the wake is LOCK as the face.
- N1 (`sosPauseMarkLogDue`, `SOS_MARK_LINE`) is in no bullet. Add one clause to the rc.2 fix-round bullet (`:247`).

**R1-3 (Nit, B: the relock's queued Home runs animated 77 ms after the leave's unanimated one)** -
`lock/PinLockRuntime.kt:709` (`runAfterUnlock`, posted on the LOCKED->UNLOCKED edge) -> `lock/HomeFront.kt:61`
(`bring(it, why)`).
- When a policy edge queued Home during the ask (the G capture's every run), the pad's success brings Home twice:
  first `noAnimation` from `go()`, then animated from the queue.
- The second start finds Home's task on top already (delivered to top, no task change), so it normally has no
  transition, and the capture saw none. A slide on a slow device, or a second transition merged into the first under
  Shell, can't be ruled out without a device.
- Fix (one line): `bringAfterUnlock`'s send passes `noAnimation = AndroidLock.faceOn`. Or `go()` drops the queued
  `Home (...)` entries when `plan.homeFirst` (`KeyguardCover.dropWaiting`).
- Add to §8.4 item 3: a window's end during the ask, then the pad's success, recorded.

**R1-4 (Nit, B: `ownGroundDue` can outlive its leave)** - `lock/HomeFront.kt:85`, `ui/HomeActivity.kt:456-461`.
- The flag is set before `startActivity`, so a start that throws leaves it set.
- A Home resume that takes the night path (`showNight(); return`) returns before `takeOwnGroundDue()`, so the flag
  survives to a later, unrelated resume. Home then draws its own full ground until it stops. That costs overdraw only
  and looks the same; no own-mode path sets it.
- Fix: set the flag after `startActivity` returns. Take it at the top of `onResume`, before the night return.

**R1-5 (Nit, K: crop, scale and brightness of the baked lock wallpaper need a device)** -
`ui/wallpaper/WallpaperApplier.kt:176-199`.
- The lock bitmap is `WallpaperRender.draw` at `screen(context)` (the app's `displayMetrics`). The glance draws the
  same `draw` on its root's bounds, so the centre-crop matches if the two rectangles are the same.
- Unverifiable here:
  - whether `displayMetrics` is the full display on the Jelly Star (if not, the system scales the bitmap up);
  - whether SystemUI dims the lock wallpaper (the dark-theme wallpaper dim, the keyguard's scrim);
  - banding from drawing the scrim into an RGB_565 bitmap. The glance composites the same scrim on an 8888 surface.
- Add to §8.4: a 24 fps frame of SystemUI's sharp wallpaper at a wake next to the glance's first frame, with a photo
  ground and with a gradient. No shift, zoom or brightness step.

**R1-6 (Nit, K: a lock photo that fails to decode fails the whole apply)** - `ui/wallpaper/WallpaperApplier.kt:191-193`.
- `is WallpaperFill.Image -> homeBitmap(..) ?: run { homeBitmap.recycle(); return ApplyOutcome(0, false) }`: the home
  half isn't set either, and the key is "already tried today".
- No Image reached this branch before K. `lockAfterDecode` makes it rare (the store decoded the same file).
- Fix: on a failed decode, fall back to the night mark for the lock half and still set Home.

**R1-7 (Nit, H: what the kiosk-on leave over a call lands on - device check)** - `lock/LockTaskRoot.kt:97`.
- With no Home, the next locked task comes forward. That is the call app when the glance covered it, but:
  - `callLive` also counts the audio mode alone (a voice-note recorder, a game's voice chat), so it can be any
    allowed app;
  - a stale BlockedAppActivity task (16d's residual) would surface. It needs a Recents start while LOCKED, which the
    occluded secure keyguard refuses as the face.
- No PIN is involved. Add to §8.4 item 2: after the leave, `dumpsys activity activities` shows the call app's task on
  top and no BlockedAppActivity.

**R1-8 (Nit, C: a stale `lockOnTop` is unbounded within a lock as the face)** - `lock/LockUpPlan.kt:131-133`,
`lock/PinLockState.kt:66`.
- The face's skip keeps the mark, and the next screen-off is NONE. If something covered the sleep-stopped glance
  without a callback, every wake shows SystemUI's face until Android's next unlock (USER_PRESENT -> UNLOCKED clears
  the mark).
- The system dialer and VoIP clear it, so this is not one of the known cases. Android holds, so it is cosmetic.
- Optional cheap net: 500 ms after a face skip, `!lockResumed && LOCKED && interactive && no call/VoIP` ->
  `show(sameLockUp = true)`. That covers the stale case without bringing back the wake's new-intent pair.

**R1-9 (Nit, docs: §8.1 Q2 / As built E wording)**
- "Android's keyguard is locked for the whole wake (... `noticeWakeAllowed` needs LOCKED)": that is our LOCKED.
- Android's state at the wake comes from the 1 s sleep check, and from the glance's resume rule (WAIT -> RELOCK) for
  a wake inside that second.
- Reword so nobody later relies on the gate for Android's state.

**R1-10 (Nit, N1: "a stop logs at once as before" holds only without a pause mark)** - `lock/PinLockRuntime.kt:1706-1715`.
- After a face pause marked the SOS, the stop's `noteProbableSos` returns early (`sosFlowUntilElapsed > 0`). The line
  then comes from the stop's own check `PAUSE_REFRONT_MS` later (`:1756`), not at once.
- Log only; behaviour unchanged. Say so in §8.6 N1.

**R1-11 (Nit, docs: "the ground and the mark")**
- The glance's ground (`GroundDrawable(state.lock..)`) has no mark. Only Android's lock wallpaper is ever the mark, and
  since K only while no ground is known.
- The wording "the root keeps the ground and the mark" / "only the ground and the mark" is in §8.6 B,
  `GlancePanel.kt:285`, `LockGlance.kt` (`glanceContentShown` KDoc), the `renderNow` comment, and the test names in
  `AndroidLockFaceTest`/`LockGlanceTest`.
- §8.2 B's target frame "the night ground with the mark" is K's ground now. Say "the ground".

## Wiring checks (no finding)

- **B's `face` read live** (the deviation from §8.5): sound.
  - A glance instance lives across screen-offs and applies, and a fixed `false` would have kept our keypad after the
    face came on.
  - The two reads (`glanceStageStep(.., face())`, then `onStage`'s `AndroidLock.faceOn`, `PinLockActivity.kt:268-275`)
    can differ when an apply on its background thread lands between them. ASK with the face gone gives `ownKeypad()`,
    which is handled. KEYPAD with the face just on gives our keypad as the face, 59b's OWN_KEYPAD state. Android's
    keyguard is still the gate, and a wrong-mode keypad can only add a PIN, never remove Android's.
  - A face that goes off during an open ask leaves ASK until the next stage event; "Lås opp" then gives KEYPAD, and the
    screen-off gives GLANCE. Not stuck.
- **Home's own ground**: set only by the face's leave, cleared at Home's stop.
  - A wallpaper change while it is set re-renders FULL with the new fill (`renderWallpaper` reads the store), so it
    stays consistent.
  - Own mode never sets it, and Home uses no wallpaper offsets, so no parallax is lost.
  - Cost: a full-screen bitmap draw on Home's window while it stays resumed (overdraw only). R1-4 is the one leak.
- **K's key and re-apply**:
  - `faceChanged` is one worker task: `refreshNow` + `applyAndRefresh`, and the key compare skips when nothing changed.
    It is idempotent, and nothing it does calls `AndroidLock.face`, so there is no loop.
  - The bitmap is re-rendered only for a new key (a new ground, opt-in, scrim alpha, mode or size). The scrim alpha is
    derived per photo hash (`imageStatsOf`, cached), so it is stable.
  - `systemShows` in `refreshNow` and the apply build the same `GlanceGround` (`lock`, `blackScrimArgb(alpha)`).
  - Own-mode keys are unchanged (pinned; the upgrade re-applies only as the face, once, at the next sync).
  - `imagesInKey` ignores `lockscrim=` and still finds a second photo hash on the lock half, so a revoked lock photo
    still triggers the replacement.
  - Before the first unlock: `refreshNow` returns early, and `glanceGround` is null until `lockReady`. That gives the
    night mark, and `AndroidLock.face` doesn't run before the first unlock anyway.
- **N1**: log only. `noteProbableSos(logNow = false)` still sets `sosFlowUntilElapsed`, and every yield and narrow
  decision reads the same state as before.
- **H**: `PinLockActivity.leave` passes `androidFace = AndroidLock.faceOn` (pinned). The root case keeps Home and stops
  lock task first, as 59b step 0.
- **C**: `lockOnTop` is set only by a start that didn't throw (`lockUp`, `PinLockRuntime.kt:790`), so a thrown start
  still gets the backstop. A new process starts with it false.

## Docs

- §8.6 "As built" matches the diff, commit by commit (the K revision included). The two `launcher/CLAUDE.md` lines it
  claims (`:168` design 32's sentence, `:286-288` the notice wake line) are in. R1-2 lists the rest.
- Design 59's open question 3 is closed (§3.9 and the question itself). PLAN's 59d entry records round 1, K and G's
  non-build, and says "Next: the code QA of round 1".
- §8.4's device checks need, for what the code now assumes:
  - item 3: R1-3's double Home start;
  - item 2: R1-7's top task;
  - K's own checks, none listed yet:
    - (a) after the face edge, Android's lock wallpaper is the glance's ground (a wake's sharp frames read as our
      page; R1-5);
    - (b) a parent change of the lock wallpaper, or of Home's while no lock wallpaper is set, re-applies at the sync;
    - (c) a rollback (the face off) puts design 08's rule back;
    - (d) after a reboot, SystemUI's BFU face shows the ground (R1-1's decision);
  - B: Home after the leave has no black frame, and Home is transparent again after it stops (`dumpsys` shows no own
    background, or an app switch and back).

## G's non-build

The reasoning is sound.
- Four runs: kiosk off and on, the window's end 0-2.5 s after the tap. The whole chrome pass ran under the open pad
  each time: kiosk-off -> kiosk packages, features, `DISALLOW_CREATE_WINDOWS`, the relock. The focus stayed on
  `NotificationShade`, and no `cancelled` came after any ask.
- Each run logs `Home (the window locked again) waits for Android's unlock` right before `Relocked`.
- So on this build no chrome-pass DPM write closes the pad, and step A's queue removed the one start that would
  un-occlude the keyguard. A Home start over an occluded keyguard is the plausible RC F2 cause.

**Is RC F2 still possible? Not through a chrome write, by the capture's evidence.**
- The remaining starts of ours that could un-occlude the keyguard during an ask are the direct `HomeFront.bring`
  callers:
  - the dark lock-up, which runs no ask;
  - the leave, which comes after the verdict;
  - `BootHome`, which runs at boot only;
  - `PinLockActivity.kt:788` (rooting the lock task through Home). As the face with Android locked, the glance roots
    it itself (59d A), so it doesn't start Home.
- Every other start goes through `afterAndroidUnlock`.
- Not covered by the capture: the status-bar heal flip (SYSTEM_INFO only). It runs at a screen-off and at the
  glance's resume (at most once a minute), and an ask doesn't resume the glance.
- The RC build itself was not re-run, so "the relock's Home" stays the likely cause, not a proven one. §8.4 item 5
  re-runs RC F2 on rc.3.

## Verdict

**Merge after fixes.** The bar holds: no new path past Android's PIN. B never hides the window. H keeps the kiosk root's
Home. C keeps the sleep check and the gated backstop. E leaves Android locked. Own mode is pinned unchanged in every
step, and all 26 compiled mutations are killed.

The fixes before merge are docs only:
- R1-2: the `launcher/CLAUDE.md` lines;
- R1-9, R1-10, R1-11: §8.6 and the comment wording;
- R1-1: the user's yes or no on the photo on SystemUI's BFU face, and the matching design 08 / CLAUDE.md text.

The code Nits (R1-3 one line, R1-4, R1-6, optionally R1-8) can go with step J's round before rc.3. Add §8.4's added
device checks to the rc.3 run.
