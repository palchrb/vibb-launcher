# QA 59 prototype - code (branch `proto-59-hybrid`, `fa33001d..46cf0fa5`)

Code QA of the design 59 hybrid prototype (`docs/design/59-prototype.md`) against `59-android-keyguard-assessment.md`
§3, §4 and §9. Reviewed 2026-10-22 on `proto-59-qa` (= `46cf0fa5`). No emulator here: the AOSP reasoning is from the
sources as I know them and is marked [device] where the run has to confirm it.

**Run**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1527 tests, 0 failures.
`compileReleaseKotlin -x checkReleaseHasTsnet` (no `tsnet.aar` here): the only errors are `TsnetClient.kt`'s tsnet
references, so the release source set compiles with the hybrid. `checkReleaseHasNoDebugHook` (now with
`HybridDebugReceiver`, `hybrid59_debug`) can't run without the aar; CI runs it.

**Verdict**: fit for the user's emulator run. The release build is unchanged (§1). With the switch on, no way past
Android's PIN was found (§2). There are 0 High, 3 Medium and 5 Low findings, plus nits. Two of the Mediums are about the
Recents question (the doc's claim and step 14's design). The third is a test gap in the release guard, not a live
bug.

## 1. Release is untouched

`HybridDebug.enabled` is `= false` in `src/release`. `Hybrid59.refresh` is its only reader. `switchOn` is set only
there, and `faceOn` only from `hybridFace(switchOn, ...)`. The receiver and its prefs name exist only in `src/debug`,
the receiver is DUMP-guarded and exported only from the debug manifest, and both names are banned in main and release
(`CheckDebugHook`, `DebugHookAbsentTest`). Every path changed for everyone behaves exactly as today with the switch off:

| Path | Switch off (release) | Same as today? |
|---|---|---|
| `PinLockRuntime.configure` (:1212-1224) | `refresh` false; `configured`: face false = was, no `CameraLock` call, returns false; `KeyguardCover.configure(..., hybrid=false)`; `lockActivation(androidGate=false)` | yes |
| `keyguardMode` / `keyguardDisabledFeatures` / `writeKeyguard` | `hybridFace(false, ..)` false; `!hybrid` true; `hybrid=false` takes the old branch; HYBRID is never wanted | yes |
| `KeyguardCover.write` / `setT` / `followTimeout`, `ScreenTimeout` :98/:162 | `hybridOn` is only `mode == HYBRID`, so `timeToLockFollows == coverOn`; the new log needs `hybridOn` | yes |
| `lockActivation` (PinLockState) | `&& !androidGate` is `&& true` | yes |
| `lockUpAtScreenOff` (:642) | `coverOn \|\| false`; `note` returns without `faceOn` | yes |
| USER_PRESENT (:784) | gated by `Hybrid59.faceOn` | yes |
| re-front (:1463), `onLockPaused/Stopped`, VoIP note | `faceOn && ..` short-circuits before any binder call; `note` returns | yes |
| `CameraLock.onLockChanged` (:89) | `cameraLockEngaged(locked, false) == locked` | yes |
| `PinLockActivity` `onStage`, `render` :862-866, ring card :517 | `onStage` = `render()`. The new `visibility = VISIBLE` writes match the XML defaults, and nothing else ever changes those views (grep), so they are no-ops | yes (redundant writes only) |
| `PinLockActivity.check` (:798) | `migrateIfDue` returns on `!switchOn` | yes |
| `PinLockActivity.onResume` (:592) | `resumeAsFace` returns false on `!faceOn` | yes |
| `WindowRelock` | `ensureResetToken` only changed from private to internal; `resetCredential`/`resetTokenActive` are reachable only through `Hybrid59.migrateIfDue` (switch) and `rollback`/`describe` (debug receiver only) | yes |

## 2. With the switch on: escape and stranding

- **`isKeyguardLocked` / `isDeviceLocked` while occluded** (AOSP): `KeyguardManager.isKeyguardLocked` -> WMS ->
  `PhoneWindowManager.isKeyguardLocked() = keyguardOn() = isKeyguardShowingAndNotOccluded() ||
  inKeyguardRestrictedKeyInputMode()`. The second term is `KeyguardViewMediator.isInputRestricted() = mShowing || ...`,
  and `mShowing` stays true while occluded. So it reads **true** over our occluding glance. `isDeviceLocked` ->
  `TrustManagerService`: `secure && wm.isKeyguardLocked() && !trusted && !biometricAuthenticated`. That is also
  true, and TRUST_AGENTS is off. The code needs **both** to be false (`androidLocked` = `km == null || isDeviceLocked ||
  isKeyguardLocked`; any exception counts as locked), plus a live credential and the screen on. This is correct
  [device: the run's `the glance resumed:` lines].
- **The ways out**: `androidAnswered` "succeeded" leaves. `onDismissSucceeded` is called only once the keyguard
  really goes away; a keyguard that isn't showing answers `onDismissError`, and an error leaves only through the
  `unlockStep == UNLOCK_NOW` re-read. USER_PRESENT and the resume both go through `unlockedUs`. The `screenOffs`
  check is kept. **No escape found.**
- **The fallbacks** fail closed: `deviceSecure` unreadable = false (our keypad), `androidLocked` unreadable = true,
  `interactive` unreadable = false. **The re-front** stops only on `faceOn && androidLocked` (but see P59-3).
- **The rollback** stores `own`, clears the PIN with the token, then applies: DISABLED, the hybrid's recorded bits
  and T out, our lock active. If there is no token or the clear fails, it is UNTOUCHED with Android's PIN as the gate.
  A lock is always in place. In the crash-guard case, HYBRID is chosen before `guardTripped`, so Android's PIN stays.
- **The migration** passes the checked string straight to `resetPasswordWithToken`. It is never stored, and no log
  line contains it. The token is read from `WindowRelockStore`, copied into a local `ByteArray` and zeroed in
  `finally`, and never stored a second time (pinned by `HybridPlanTest`).
- **Stranding**: none in the code; the documented gap (a PWA PIN change never reaches Android) needs `own`.

## 3. Recents (the user's 2026-10-22 finding)

Today the keyguard is disabled, so Android sees no keyguard and Recents behaves as on an unlocked phone. Lock task's
missing OVERVIEW evidently doesn't stop Quickstep (the user's repro with features 0x71). In the hybrid, the guard is
Android's keyguard showing under the occluding glance, not lock task:
- KEYCODE_APP_SWITCH: PhoneWindowManager drops it while `keyguardOn()`, which is true when occluded (as above).
- The nav bar's square key: SystemUI's `toggleRecentApps` runs through `executeRunnableDismissingKeyguard` while the
  keyguard is showing. That brings the bouncer first, and Recents only after the PIN.
- The swipe up: Quickstep's `TouchInteractionService` picks the `DeviceLockedInputConsumer` when
  `isKeyguardShowingOccluded()`. Its swipe leads to the bouncer, not Overview.
- `RecentsActivity` isn't `showWhenLocked`.

So the hybrid should close it **while Android's keyguard is locked** [device]. The prototype's own explanation (lock
task) is wrong (P59-1), and step 14 can't tell a fix from a non-repro (P59-2).

## 4. Mutations (each one alone, `testDebugUnitTest`)

| # | Mutation | Result |
|---|---|---|
| M1 | release `HybridDebug.enabled = true` | killed (`DebugHookAbsentTest`), but only with `--rerun`: the first run stayed UP-TO-DATE (P59-7) |
| M2 | `refresh`: `HybridDebug.enabled(..) \|\| true` | **survived** (P59-5) |
| M3 | `configured`: `hybridFace(true, ..)` instead of `switchOn` | **survived** (P59-5) |
| M4 | `unlockStep`: `androidUnlockStep(true, true, ..)` (no live credential check) | **survived** (P59-8) |
| M5 | `configured`: camera re-sync only when the face turns on, not off | **survived** (P59-8) |
| M6 | `lockActivation`: `deviceSecure && androidGate` | killed (`PinLockStateTest`) |
| M7 | `keyguardMode`: the cover with the switch on (`!hybrid` dropped) | killed (`HybridPlanTest`) |

## 5. Findings

**P59-1 (Medium): "lock task keeps Recents out" is false, and the run's `guarded=true` line measures the wrong
thing.**
- Evidence: `HybridPlan.kt:76-81` (`recentsGuarded`; its KDoc says "Lock task holds Recents and the shade off over
  the glance"); `Hybrid59.kt:188-205` (`noteRecentsGuard`); `HybridPlanTest` "Recents is guarded by lock task"; the
  doc's §1 "Lock task" row and step 14 ("a `guarded=false` warning means lock task wasn't holding OVERVIEW off").
- The user's repro (features 0x71, lock task running) would log `guarded=true` while Recents opens.
- Failure: the run reads `guarded=true` as "lock task guards Recents". Phase C then keeps relying on it where
  Android's keyguard isn't locked under a LOCKED glance (P59-6).
- Fix: log it as `lockTask flags: ...`, with no verdict. Add `isKeyguardLocked`/`isDeviceLocked` to the same line,
  since they are the real guard (§3). Correct the KDoc, the test name and the doc's §1 row.

**P59-2 (Medium): step 14 has no baseline, and it misreads what the hybrid legitimately does.**
- Evidence: `59-prototype.md` step 14 runs only with the switch on.
- Failure: if the AVD doesn't reproduce the Jelly Star's leak, step 14 "passes" and proves nothing. The AVD has its
  own Quickstep, and a scripted 1500 ms `input swipe` may not trigger the hold.
- Fix:
  - Run 14 (a)-(d) on today's lock first: before step 1 (switch off), and again at 19(b). A hybrid pass counts only
    if the leak reproduces there.
  - Add `adb shell dumpsys activity activities | grep -A4 KeyguardController` (showing/occluded) to each "Send".
  - Say that (d)'s square tap is expected to raise the bouncer and, after the PIN, Recents. That is not a failure.

**P59-3 (Low): the re-front hold doesn't need a credential, unlike the other two gates.**
- Evidence: `Hybrid59.kt:131`, `HybridPlan.kt:70`. `isKeyguardLocked` is also true for a Swipe keyguard.
- Failure: the credential goes before the next apply (a rollback whose apply fails, 24b's `removeCredential`, the
  parent in Settings). `faceOn` stays true, HYBRID left `setKeyguardDisabled(false)`, and a Swipe keyguard shows. The
  loop is then held while only a swipe guards. This is brief, because `onPasswordChanged` applies.
- Fix: `faceOn && hybridRefrontHeld(true, deviceSecure(context) && androidLocked(context))`, as `unlockStep` and
  `unlockedUs` already do.

**P59-4 (Low): the camera lock goes off on `faceOn`, not on the secure-camera bit being in force.**
- Evidence: `HybridPlan.kt:73`, `CameraLock.kt:89`. `hybridWrite`'s read-back (`KeyguardCoverPlan.kt:210`) checks
  only `HYBRID_KEYGUARD_FEATURES`, not B2's 0x2.
- Failure: if the features write fails (`failed=features`), HYBRID and `faceOn` stay. The camera lock is then off
  and nothing refuses the gesture, which removes 59 §4.9's premise.
- Fix: check 0x2 in the read-back while managed, and engage the camera lock unless `KeyguardCover.hybridOn` and the
  written features hold 0x2.

**P59-5 (Medium): the release guard pins the call sites, not the values (M2 and M3 survived).**
- Evidence: `DebugHookAbsentTest` "release HybridDebug is always off" checks that `refresh` contains
  `HybridDebug.enabled(`, that `switchOn =` appears once, and that `faceOn =` is `face` or `hybridFace(switchOn, ..`.
  `val face = hybridFace(switchOn, ..)` in `configured` (`Hybrid59.kt:77`) isn't pinned.
- Failure:
  - M2: a release build migrates the next kid PIN into Android's credential.
  - M3: a release phone that has a credential keeps our lock active over it (`androidGate`), the camera lock off and
    the re-front held.
  - All tests pass in both cases. Nothing in today's code does this; the guard just wouldn't catch the edit.
- Fix: pin `refresh`'s whole body and `configured`'s `val face = hybridFace(switchOn, managed, hasKidPin,
  deviceSecure)`. Better still, a JVM test of `Hybrid59.configured`/`refresh` with a stub `HybridDebug` false.

**P59-6 (Low): the resume's "Android is unlocked, leave" can race the keyguard's own lock at a quick wake.**
- Evidence: `PinLockActivity.kt:331-345`. The keyguard locks at `onFinishedGoingToSleep`, or at the
  `doKeyguardLaterLocked` alarm (0 ms with T), so a wake inside that gap resumes the glance before `mShowing`.
- Failure: the glance leaves (UNLOCKED, lock task off), then Android locks over Home. That is no escape, but the face
  is lost until the next screen-off. If T failed, it becomes Android's 5 s grace without a PIN (step 3 checks that).
- Fix (phase C, or now if step 3 shows it): leave at the resume only if the keyguard was seen locked since the last
  screen-off. USER_PRESENT stays as it is.

**P59-7 (Low): a local `testDebugUnitTest` stays UP-TO-DATE after a `src/release` edit (M1).**
- The text pins read `src/release` and `src/debug` from disk, so Gradle doesn't see them as inputs.
- CI builds fresh, so this misleads only a local run. The cover's pins behave the same way.
- Fix: `inputs.dir` for both source sets on the test task.

**P59-8 (Low): two gate details aren't pinned (M4 and M5 survived).**
- M4 drops `unlockStep`'s live `deviceSecure`, so a credential that went before the apply lets "Lås opp" leave with
  no PIN at all.
- M5 skips the camera re-sync when the face turns off (`own` while LOCKED), so the camera lock stays off until the
  next lock change.
- Fix: pin `unlockStep`'s body (`androidUnlockStep(true, deviceSecure(context), androidLocked(context))`) and
  `if (face != was) CameraLock.onLockChanged(context)`.

**Nits.**
- `KeyguardCover.kt:207` says "the keyguard stays disabled" for a HYBRID `coverFailed`, which is false there.
- `own` clears any credential with the token, even a parent's from a window (debug-only; note it in the doc's §2).
- A window opened before the migration removes the migrated PIN at its relock (24b step 1). Our keypad comes back
  until the next PIN; this is harmless.
