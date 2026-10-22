# Code QA: design 59b (Android's lock as the gate - the face's re-front, the platform's ring, the look)

Branch `design-59b` at `6c678515` (base `d88509ae`, 29 files, +726/-65), reviewed on `59b-qa`. Inputs: the design's
"As built" and deviations, `qa-59b-design.md`, `qa-59b-revision-check.md`, and 59d's target picture
(`docs/design/59d-android-mode-inventory.md`, from `9677ffcc`). Launcher only; paths under
`launcher/app/src/main/java/com/kidslauncher/mdm/` unless named.

**Verdict: no path past the PIN found, own mode unchanged, no stranded phone, no loop.** One Medium (59b step 2 shipped
ahead of 59d's step A: our own Home start under Android's keyguard is read as an unexpected loss (if Home takes the front there, a device check), and the SOS guess
then holds SystemUI's face for 15 s) and five Lows (one of them two test gaps). Full run (`assembleDebug testDebugUnitTest
-PwarningsAsErrors=true`): BUILD SUCCESSFUL, 1568 tests, 0 failures. Mutations: 17 run, 15 killed, 2 survived (B-6, both test gaps behind a second guard).

## The bar, checked

**Every exit of the glance in face mode** needs Android's verdict:
- `RefrontAction.AndroidLeave` (`RefrontPolicy.kt:176`) comes only from `androidLoss(..) == OPEN` (`:59-64`): face,
  `deviceSecure`, Android not locked **and** `presentSinceLocked` - all live reads in `AndroidLock.loss`
  (`AndroidLock.kt:367-368`), computed only with our chrome LOCKED and the glance not resumed
  (`PinLockRuntime.kt:1695`). The action runs the glance's hook, whose `presentLeave` reads `faceStep(USER_PRESENT)`
  again (LEAVE needs the same three facts) with one re-read (`PinLockActivity.kt:394-418`); with no glance it
  dispatches `Unlocked` on the reads just made - exactly the `USER_PRESENT` receiver's existing fallback (`:940-941`).
- The mark is set only by the EXPORTED receiver for SystemUI's protected broadcast, only with the screen on (S3-2), and
  cleared at every LOCKED edge, screen-off and remote lock (`PinLockRuntime.kt:639-642`). A late `USER_PRESENT`
  ignored while dark marks nothing; one that lands after a fast off/on (after the ScreenOff reset) sets the mark while
  Android is locked again, and `OPEN` still needs Android open - which then only the credential, the fingerprint or the
  lock delay can give; the last is design 59's sleep-check RELOCK, unchanged. "OPEN before the exemptions" (the
  deviation) is therefore a leave on Android's verdict during a yield, never a new door.
- `leaveInFront`/`lockLeave(.., glanceInFront)` (`LockTaskRoot.kt:79-91`) only shape a leave already decided
  (mode UNLOCKED): Home or not, the row target kept or dropped. The pad's success still needs `PAD_SUCCEEDED` with a
  live credential and no screen-off since the ask (`PinLockActivity.kt:321-331`, `faceStep`).
- The 2-minute ask stamp only suppresses re-fronts; it opens nothing. A lost callback expires (pinned).

**The kept keypad stage** (`glancePageShown`, `GlancePanel.androidPad`): our keys and dots are `INVISIBLE` (no touch, no
accessibility node) whenever `keypadIsAndroids`, which `tapAsFace` sets for every step but `OWN_KEYPAD` (= no
credential, `faceStep`'s first row); the page drawn over them adds a second cover. A tap on the page while the stage is
KEYPAD runs `step()` -> `onStage()` again (`GlancePanel.kt:273-278`), so a dead pad never leaves dead taps.
`overrideActivityTransition` needs API 34 = `minSdk`, no fallback needed.

**The re-front** cannot pass the PIN: the glance is showWhenLocked with no dismiss flag (pinned in
`PinLockManifestTest`), so a re-front only occludes. Cap 2 per screen-on, zeroed only at the screen-off and
`USER_PRESENT` (pinned by count), `NO_USER_ACTION` only from the face's rule (pinned). No loop: after the cap the rule
stops; Yield rechecks run before the cap but end in it.

**The VoIP ring** (`voipPlatformRing`, `voipGrace`): with an FSI and Android locked the platform launches it; the grace
always writes this ring's try, so the 17b send can't follow (`voipFsiDue(.., platformRing)` and `last.ringId`), the card
comes after PLATFORM/WAKE, and the timer is dropped off RINGING and ignores a stale ring id. Dark with no FSI launch
(DND) -> the wake and the card at 3 s, the ringer by `ringPlan` as own mode; FSI denied -> stripped by NMS, so
`hasFullScreen` is false and own mode's path runs. The app's slow screen after SystemUI's own launch can't trigger the
heads-up SEND: that launch puts the app's activity over the glance before it can resume. Own mode's rows pinned
(`VoipCallPlanTest`: `step`, `voipFsiDue` default).

**The wallpaper**: the opt-in wins (`Wallpapers.kt:99-104`, pinned); the key carries the face, so a face edge or a
rollback re-applies on the sync that follows (`WallpaperStore.sync` after every accepted sync); NightMark is drawn on
the store's thread at screen size; lock first, then home, as design 08. Material You stays check 7.

## Findings

**B-1 (Medium) - 59b step 2 runs before 59d step A: the kiosk's Home-root start under Android's keyguard is an
"unexpected loss", and the SOS guess turns it into 15 s of SystemUI's face.** 59d orders "A, B, C, then 59b step 2 with
D" (59d §"Order"; its row "Home to root lock task at the glance's resume ... CHANGE: START_SELF in face mode").
As built, a kiosk-on glance without lock task still takes `LockTaskEntry.START_HOME` (`PinLockActivity.kt:681`, `:691`,
`:769`; `lockTaskEntry` has no face input, `LockTaskRoot.kt:42-54`). The implementer expects that start to
un-occlude the keyguard and stop the glance ("As built", last bullet). 59d's device note says the prototype's shell start
of Home was "brought to the front" with focus left on the glance. If that note holds, the 1 s fallback roots lock task
and nothing below happens, so the severity depends on that check. HomeFront is unstamped: the source test's allowlist
says it "never starts over a LOCKED glance's loss" (`AndroidLockFaceTest.kt:137`), which is wrong for this start. Then
`noteProbableSos` (`PinLockRuntime.kt:1622`, `:1654-1661`) marks any interactive stop within 3 s of a screen-on, once
an SOS package is resolved. `exemptYield`'s "sos" comes before "ours" and the cap (`RefrontPolicy.kt:156`, `:177-178`).
Scenario: kiosk on, a self-update committed at night (lock task ends; `SelfUpdate`'s Home start can't resume under the
keyguard while dark, `SelfUpdate.kt:348`). At the morning's first wake the glance resumes and starts Home. SystemUI's
face then shows for 15 s, lock task narrowed for SOS. After that comes re-front #1; the glance resumes 15 s after the
first ask, past `HOME_ROOT_RETRY_MS`, so START_HOME runs again. That loss is re-front #2, and then the 1 s fallback
roots lock task. The kid sees SystemUI's face for ~15 s and two flickers, and this screen-on's re-fronts are spent. The
same unstamped Home starts come from `WindowRelock.kt:727`, `AppEnforcer.kt:756` and `SelfUpdate.kt:348` while LOCKED
with the screen on (one re-front each, or the SOS hold). Fix: 59d step A's first line - `lockTaskEntry(.., androidLocked)`
-> START_SELF while the face's Android is locked (the leave already re-roots through Home: `homeFirst = startedLockTask`).
Gate those HomeFront starts on `KeyguardCover.mustWait`, as 59d A says. Correct the allowlist comment. Emulator check 2
with the kiosk on and lock task stopped.

**B-2 (Low) - The SOS guess swallows the face's fast re-front in the first 3 s of every wake** (deviation "the face's
'sos' yield now runs"). Any stop within `SOS_GESTURE_WINDOW_MS` of a screen-on - DeskClock relaunched, an app that
isn't showWhenLocked coming up at the wake - is "probably Emergency SOS": 15 s of SystemUI's face and narrowed lock task
instead of the ≤2 re-fronts (59d: "The guess fires for *any* stop"). No door: SOS is showWhenLocked and SystemUI's face
is Android's gate. Fix (59d D): as the face, keep the exemption, drop `narrowForSos`. Mark a probable SOS only when the
gesture's own screen toggles are seen (>= 3 screen-on/off edges in the window, counted in the screen receiver). Run
check 2 once within 3 s of a wake.

**B-3 (Low) - The pad's Emergency dialer is "ours" only while the ask is open.** If SystemUI answers our
`requestDismissKeyguard` with `onDismissCancelled` when its Emergency button hides the bouncer, `androidAnswered`
closes the ask and steps the glance back (`PinLockActivity.kt:321-331`, `closeAsk` `:252-256`). From then on only
`ANDROID_ASK_SETTLE_MS` (1 s, `AndroidLockPlan.kt:134`) covers it. A cold `com.android.phone` dialer whose stop + 200 ms
lands later is re-fronted over, up to twice, while the kid types 112 (our own Nødsamtale on the glance still works). The
same holds for Android's dialer opened from SystemUI's face when a pending recheck (a yield's 2 s, a re-front's own)
runs after its yield ends. Fix: keep a *cancelled* answer "ours" for `ANDROID_ASK_OPEN_MS` from the ask. In face mode,
re-front only from a check a fresh pause/stop scheduled, not from a yield's recheck. Check 3 must log which callback
the pad's Emergency gives.

**B-4 (Low) - `leaveInFront` reads the lifecycle bit, not the trigger.** The pad's success is always the glance's own
ask (a row's target may be pending), but `leave()` passes `resumed` (`PinLockActivity.kt:1050-1052`). A build that
pauses the occluding glance during the bouncer or keyguard-going-away drops the row's target and starts no Home:
"Lås opp" + a row then opens nothing (no door; the app the kid was in shows). Fix: a `leaveFromAsk` flag set in
`androidAnswered`'s LEAVE -> `inFront = true`; `resumed` only for the `USER_PRESENT`/AndroidLeave paths. N6's check 3
(row + PIN lands on the row's app over Home) is the guard until then.

**B-5 (Low) - Android open without the mark takes own mode's re-front at once.** `androidLoss` gives `null` there
(`RefrontPolicy.kt:59-64`), so a check that lands between the keyguard's going-away and a late `USER_PRESENT` (a
background broadcast) re-fronts with the user-leave hint and no cap. The glance then resumes in front and the
`USER_PRESENT` that follows leaves through Home: Q59b-1's symptom. If the broadcast is later than the 1-3 s wait,
RELOCK turns the screen off under a correct unlock. Narrow: the unlock itself sends the stopped glance no event, so
only a pending recheck can land there. Fix: as the face with a credential, Android open and no mark -> one recheck after
`ANDROID_ANSWER_WAIT_MS` (a `Yield("android_wait")`), then the backstop. An open Android is RELOCKed 1 s later, never
skipped.

**B-6 (Low) - Two test gaps.** (a) Nothing pins our keys `INVISIBLE` under Android's pad (M7b survived the whole
`lock.*`/`ui.*` suite): today the page drawn over the stage still covers them, but a later change of either guard
alone goes unseen. Pin `render()`'s `ours`/`pinParentCode` lines next to `glance?.androidPad(keypadIsAndroids)` in
`AndroidLockFaceTest`. Better, give `onDigit`/`onDelete` an `if (keypadIsAndroids) return` and pin that. (b)
`noteOwnStart` is pinned only by call sites and a count of `ownStartAt = ` (M10b, `ownStartAt = ownStartAt ?: null`,
survived): pin its body as `ownStartAt = SystemClock.elapsedRealtime()`.

## Tests

The new tests are behaviour tests on the pure seams (`androidLoss`, `androidLossStep`, `androidLossOurs`,
`voipPlatformRing`/`voipRingStep`/`voipReopenDue`, `lockLeave`/`leaveInFront`, `glancePageShown`, `lockScreenFill`),
with source pins for the glue (the counter's two resets, the flag reaching `show()`, the stamping allowlist, the grace's
wiring). Own-mode rows are pinned: `refrontAction` without `android`, `step(VoipRinging())`, `voipFsiDue` without
`platformRing`, `lockLeave(.., glanceInFront = true)` equals the old rows for every input, and `lockScreenFill(w)`.

## Mutations (each reverted; batches of mutations whose target test methods are disjoint)

| # | Mutation | Target test | Result |
|---|---|---|---|
| M1 | `androidLoss`: Android open = OPEN without `USER_PRESENT` | `AndroidLockFaceTest` the loss input | killed |
| M2 | `androidLoss`: no `deviceSecure` check | the loss input | killed |
| M3 | `androidLossStep`: no cap | three losses | killed |
| M4 | `androidRefronts = 0` also at the glance's pause | three losses (reset count) | killed |
| M5 | `voipFsiDue` ignores `platformRing` (the 17b send on the platform's ring) | `VoipCallPlanTest` no lock-up or wake | killed |
| M6 | `voipRingStep` without the dark WAKE outcome | the grace always ends | killed |
| M7a | `glancePageShown` ignores `androidPad` | under Android's PIN pad | killed |
| M7b | `render()`: our keys and dots `VISIBLE` under Android's pad | all of `lock.*`, `ui.*` | **survived** (B-6) |
| M8a | `FLAG_ACTIVITY_NO_USER_ACTION` on every glance start | own mode's rows | killed |
| M8b | own mode's re-front with `noUserAction = true` | own mode's rows | killed |
| M9 | the face beats the parent's lock-photo opt-in | `WallpapersTest` night ground | killed |
| M10a | `startOwn` doesn't call `noteOwnStart` | no start of ours | killed |
| M10b | `noteOwnStart` never writes `ownStartAt` | no start of ours | **survived** (B-6) |
| M11 | `leaveInFront` always true (Home after SystemUI's unlock) | `LockTaskRootTest` leaves without Home | killed |
| M12 | `voipPlatformRing` without `androidLocked` | no lock-up or wake | killed |
| M13 | the grace not dropped when the phase leaves RINGING | the grace's timer | killed |
| M14 | Android OPEN checked after the exemptions | Android open with USER_PRESENT | killed |
| M15 | the ask "ours" with no 2 min bound | ours - the ask | killed |

## Not checked here (emulator)

Design 59b §10 checks 1-10, plus B-1's kiosk-on case without lock task, check 2 within 3 s of a wake (B-2), the
callback the pad's Emergency gives (B-3), and whether the glance is resumed at `onDismissSucceeded` (B-4).
