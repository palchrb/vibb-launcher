# QA 59 step 3 - code (`design-59-step3`, `e8c9013b..2b2b40e3`)

Code QA of the launcher's real hybrid lock (design 59 §3, "Step 3 as built"), checked against `qa-59-design.md`,
`qa-59-revision-check.md` (M-1, L-1, L-3, L-4), the prototype QA (`fb6df128:docs/review/qa-59-prototype-code.md`) and
the run report `docs/testing/2026-10-22-hybrid-prototype-run.md`. Reviewed 2026-10-22 on `59s3-qa` (= `2b2b40e3`). No
emulator here: AOSP behaviour is from the sources and marked [device] where §8 has to confirm it.

**Run**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1533 tests, 0 failures.
P59-7 checked by hand: a comment appended to `src/release/.../LockUpDebug.kt` re-ran `testDebugUnitTest` (not
UP-TO-DATE); reverted.

**Verdict**: fit for the RC's emulator run after S3-1. **No path past the PIN was found**: every way to `unlock()` or out
of the glance in face mode needs the PIN pad's success, a `USER_PRESENT` since the last LOCKED edge (with a live
credential and Android open), our own keypad and hash, or the parent code. None of these reaches the phone without a
code (table below). Android's keyguard stays the gate under every leave, and nothing that isn't `showWhenLocked` shows
over it. Findings: 0 High, 1 Medium, 3 Low, 2 Nits. 14 mutations: 9 killed, 5 survived (S3-4).

## The ways out of the glance in face mode

| Route | Needs | Verdict |
|---|---|---|
| `androidAnswered("succeeded")` -> `unlock(offs)` (`PinLockActivity.kt:318-329`) | `onDismissSucceeded` (WMS answers it only after `KeyguardViewMediator` dismissed a showing keyguard; a non-showing one answers `onDismissError`), no screen-off since that ask, `PAD_SUCCEEDED` with `isDeviceSecure` now, mode LOCKED | holds. An older ask's success is still Android's verdict |
| `actAsFace(LEAVE)` from `tapAsFace`, `recoverAsk`, `faceWait`, `presentLeave`, `resumeAsFace` | `faceStep` LEAVE = secure && !androidLocked && `presentSinceLocked` (live reads, `AndroidLock.step`) | holds. `faceWait` also checks the screen-offs; `presentLeave` re-reads the live mark, which a screen-off resets |
| `userPresentReceiver` with no activity -> `dispatch(Unlocked)` (`PinLockRuntime.kt:852-863`) | the same `faceStep(USER_PRESENT)` LEAVE | holds |
| `check()` keypad Ok, parent code Ok -> `unlock(checkedAt)` | the Child PIN or the parent code. The keypad is INVISIBLE (no touches) under Android's pad; the parent code is GONE while `keypadIsAndroids` | holds. Not past a code |
| `modeListener` -> `leave()` on Configured(false) | the lock goes inactive (no PIN on a fresh policy = the parent's choice; the crash guard after `lockNow`; a Fallback, see S3-1) | Android stays locked under it |
| `ProcessStart(androidBoot)` UNLOCKED (`PinLockRuntime.kt:247-258`) | face (stored request, active, `isDeviceSecure`) and the first CE start of this BOOT_COUNT. CE needs the credential (Resume-on-Reboot unlocks CE, but the keyguard still asks [device]) | holds. Home isn't `showWhenLocked` |

`USER_PRESENT`: `<protected-broadcast android:name="android.intent.action.USER_PRESENT" />` is in frameworks/base
`core/res/AndroidManifest.xml` (checked, next to SCREEN_ON/OFF), so only a system or privileged sender can send it. The
filter is that one action (`PinLockRuntime.kt:485-490`), and the receiver checks the action again.
`ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)` meets Android 14's register-time flag rule. Own mode's
NOT_EXPORTED filter keeps the action and still never hears it (unchanged).

Write order (`AndroidLock.kt:102-127, 168-187`):
- Migration: owned flag commit, then the reset, then the rev. A death between the steps heals: owned with no credential
  is cleared by the next apply, and owned with no rev reports the rev as unknown.
- The rollback clears the credential before `KeyguardCover.configure` writes DISABLED. A death before the owned flag
  is cleared lands in `NOTHING` and then `owned && !secure`.
- Without a token, or when the clear fails, the result is UNTOUCHED with Android's PIN as the gate, retried at every
  apply.
- `onPasswordChanged` shares the monitor, so it can't run between the migration's reset and its rev.

The PIN and the token:
- The PIN is a local `String` passed straight to `resetPasswordWithToken`; no log line names it (pinned).
- The token is read only through `WindowRelockStore.token()` into a `ByteArray` that is zeroed in `finally`.
- The rollback gate is `carriesParentChoice` (`Apply` with a policy). Every caller passes the decision, and the debug
  receiver's bare policy carries no choice.

**Own mode, every changed path**:
- `AndroidLock.configure`: one pref read plus one `isDeviceSecure` binder call per apply; nothing is written.
- `face()`/`afterKeyguard`: no camera call and no log.
- `keyguardMode`/`keyguardDisabledFeatures`/`lockActivation`: the defaults reproduce today's results.
- `saveConfig`: also writes `pin_rev`/`keyguard_requested`. `KidLockConfig` equality now includes `pinRev` (one extra
  commit when the rev moves); the backoff fingerprint is unchanged.
- `decide`: one `swapAndroidBoot` commit per boot; no binder call (short-circuited).
- `atBootStart`: reads the lock-screen info and writes only if it isn't null.
- `dispatch`: `lockedAgain()` clears a boolean.
- The screen receiver: `androidSleepCheck` is posted only with the face.
- `userPresentReceiver` is registered in every mode but returns on `!faceOn`.
- `lockUpAtScreenOff`: `coverOn || faceOn` gives the same result.
- Re-front, CameraLock, the notes and `crashGuardTripped`/`onShutdown` all return on `!faceOn`.
- `PinLockActivity`: `onStage` adds `closeAsk`, and the visibility writes match the XML defaults (no other writer).
  `migrateIfDue` returns on `!requested`.
- `WindowRelock`: `setNewToken` has the same order and now zeroes the token. `credentialAction` reads
  `keyguard_requested` (false), and an unreadable read skips the removal (fail-safe; see the Nit).

All of these are judged unchanged in effect.

## Findings

**S3-1 (Medium): a Fallback apply strips Android mode's keyguard features and stores `keyguard_requested=false`.**
- Evidence: the last-enforced plan has no `kid_lock`, so in `configure` `hasKidPin` is false and `requested` is false.
  - `keyguardMode` (`KeyguardCoverPlan.kt:54-55`) then gives UNTOUCHED. `off()` (`:254-271`) takes out the recorded
    `coverAdded` (= `ANDROID_KEYGUARD_FEATURES`) and sets T to 0.
  - `saveConfig(..., requested)` (`PinLockRuntime.kt:1296`) commits `keyguard_requested=false`. The 24b skip reads that
    value (`WindowRelock.kt:624`).
- Failure: a phone in android mode gets a corrupt or missing cache (`choosePolicy` returns Fallback).
  - The glance goes (NO_PIN, as in own mode). Android's PIN is then the only gate, but SystemUI's face now shows
    notification content (SECURE_NOTIFICATIONS out), with FACE/IRIS/trust agents allowed again and Android's 5 s lock
    delay back.
  - "No app content while locked" breaks without any parent choice.
  - Worse: a parent window opened before the migration (`secureAtStart=false`) now relocks with step 1 REMOVE, which
    clears the Child PIN. The phone is then left with no lock at all.
  - The doc's "Step 3 as built" claims "Android's PIN stays the gate with SystemUI's face". That is true, but it missed
    the features and the stored flag.
- Fix: while `keyguard_android` is owned, the credential is live and the apply doesn't carry the parent's choice, keep
  the ANDROID write (features plus T; the face may stay off). Commit `keyguard_requested` only from an apply with
  `parentChoice`, and keep the stored value otherwise. Test: `keyguardMode`/`configure` with a Fallback while owned.
  Add a mutation that commits `requested` from a Fallback.

**S3-2 (Low): a `USER_PRESENT` that is delivered after a later screen-off marks the next wake.**
- Evidence: `KeyguardViewMediator.USER_PRESENT_INTENT` carries no `FLAG_RECEIVER_FOREGROUND` (background queue), while
  SCREEN_OFF is foreground, so the order between them isn't guaranteed. `userPresentReceiver` (`PinLockRuntime.kt:852-857`)
  sets the mark whatever the screen state, after `dispatch(ScreenOff)` already cleared it.
- Failure: the kid unlocks (fingerprint or pad) and presses power at once. The late broadcast sets the mark while the
  screen is dark. A wake within 1 s cancels the sleep check (`:801`). If Android hasn't locked yet (the lock-delay case
  that T is there to remove), RESUME gives LEAVE with no credential from this wake. Two things must go wrong, seconds
  after a real unlock.
- Fix: `if (!interactive(app)) return` before `AndroidLock.userPresent()`. Android's keyguard is done only with the
  screen on.

**S3-3 (Low): an unlock by fingerprint from dark now depends on `USER_PRESENT` within 1 s, or it costs the fingerprint.**
- Evidence: the run's step 17(b) left through the resume's not-locked read, which is now WAIT then RELOCK
  (`faceStep`, `PinLockActivity.kt:386-389`). `USER_PRESENT` timing was never measured (B1), and it goes through the
  background queue to every receiver at each unlock.
- Failure: a slow broadcast means `lockNow`, which sets STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW. Each fingerprint
  unlock then ends dark, and the next one needs the PIN. The kid is never stranded (the pad's success still leaves), but
  the experience is bad.
- Fix: make it a §8 check (logcat: the delay from the resume to `USER_PRESENT`). If the delay comes near 1 s, either
  raise `ANDROID_ANSWER_WAIT_MS`, or also accept "Android read locked by a SLEEP_CHECK/RESUME since **this** screen-off,
  now open with the screen on". That signal is sound here: P59-6's race needs a wake before Android locked, and with no
  trust agents and no face, a locked secure keyguard opens only on a credential or the fingerprint.

**S3-4 (Low): tests - 5 of 14 mutations survive on the leave rule's glue, the mark reset and L-4.**
- M3 (no reset at a screen-off): the pin is only `contains("AndroidLock.lockedAgain()")`.
- M4 (`presentLeave` leaves without `faceStep`): `presentLeave` isn't pinned.
- M5 (`faceWait` reads RESUME, which never relocks and waits forever).
- M10 (`swapAndroidBoot` never commits): every crash restart is "first", which breaks L-4.
- M14 (the resume acts while dark).
- Fix: pin `dispatch`'s condition `(lockedEdge || event is LockEvent.ScreenOff || event is LockEvent.RemoteLock)`, the
  bodies of `presentLeave` and `faceWait` (the step and the trigger), `resumeAsFace`'s `!interactiveNow()`, and
  `swapAndroidBoot`'s commit. Better: move the boot-count swap into a pure `bootSwap(stored, boot)` and test it.
- P59-5 and P59-7: `androidRequested` is pinned by value (`"own"`, null, junk, case, whitespace, no `kid_lock`), and
  `configure`'s derivation by text. That kills the prototype's M2/M3 analogues (M7/M13 here). P59-7 holds (above).

**Nits**
- N1: `migrateIfDue` passes `managed = true` literally (`AndroidLock.kt:172`). This is safe because our keypad runs
  only while the lock is active, which needs managed, but it should be said in the KDoc or the field should be read.
- N2: `credentialAction`'s unreadable `keyguard_requested` reads as true (`WindowRelock.kt:623-627`), which skips 24b's
  removal in own mode too. That is the safe side; it deserves a line in the relock notes.

Held, as specified:
- The 1 s/2 s timers check the screen-offs or read live state. `askCheck` needs the window focus, and the bouncer takes
  it.
- The "ours" flag is set before the reset, cleared on a failure, and expires after 60 s.
- The camera lock stands down only when ANDROID's write reads back `ANDROID_KEYGUARD_FEATURES | 0x2`, and it re-syncs on
  the face's edges and the guard's. FACE/IRIS are in the features, fingerprint is not.
- The restart line is written at shutdown as the face and cleared at the first CE start and when the face goes.
- `decide`'s `isDeviceSecure` runs on the main thread only with the face stored: one LockSettings binder read, the same
  order of cost as the prefs read beside it.
- `crashGuardTripped` does `lockNow` while the keyguard stays ANDROID (`keyguardMode` puts ANDROID ahead of the guard).

## Mutations (each alone, `testDebugUnitTest --tests lock.* server.*Relock* server.PolicyGate*`, reverted)

| # | Mutation | Result |
|---|---|---|
| M1 | `faceStep`: not locked, no mark -> LEAVE instead of WAIT | killed (the face's rule) |
| M2 | `faceStep`: PAD_SUCCEEDED before the `!deviceSecure` check | killed (the face's rule) |
| M3 | `dispatch`: no mark reset at a screen-off while LOCKED | **survived** (S3-4) |
| M4 | `presentLeave`: `actAsFace(LEAVE)` without `faceStep` | **survived** (S3-4) |
| M5 | `faceWait`: RESUME instead of AFTER_WAIT (never RELOCK) | **survived** (S3-4) |
| M6 | migration: no write-ahead owned flag | killed (rollback test) |
| M7 | `rollbackDue` without `parentChoice` | killed |
| M8 | `carriesParentChoice` true for a Fallback | killed |
| M9 | `processStartUnlocked` ignores the stored boot count | killed (process start) |
| M10 | `swapAndroidBoot` never commits | **survived** (S3-4) |
| M11 | the exported receiver's filter also takes SCREEN_ON | killed (USER_PRESENT pin) |
| M12 | the receiver leaves without `faceStep(USER_PRESENT)` | killed (USER_PRESENT pin) |
| M13 | `AndroidLock.configure`: `rollbackDue(.., true)` | killed (rollback test) |
| M14 | `resumeAsFace` acts while dark | **survived** (S3-4) |
