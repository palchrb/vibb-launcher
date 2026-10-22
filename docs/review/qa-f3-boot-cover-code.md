# QA F3 - code (`fix-f3-boot-cover`, `ccb62880..07dcfc64`)

Code QA of design 59's F3 fix: android mode implies the boot cover (16b), armed also at `LOCKED_BOOT_COMPLETED`, and the
cover waits for our Home (design 59 "F3", `launcher/CLAUDE.md` 16b bullet, run report §9/F3). Reviewed 2026-10-22 on
`f3-qa` (= `07dcfc64`). Launcher only, no emulator here. AOSP claims were checked against the android14/15/16-release
sources. Where only the device can confirm a claim, it is marked [device].

**Run**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1548 tests, 0 failures.

**Verdict**: the bar holds.
- **No path past the PIN.** The cover is never showWhenLocked and never dismisses (pinned, M1/M1b). It starts nothing
  before the unlock (`coverWaitStep` returns STAY while locked, M8), and HomeActivity is still not direct-boot-aware.
- **Own mode is unchanged.** With the switch off there is no marker, no locked-boot start and the cover is disabled.
  With the switch on, `waitForLauncher` is false and 16b/16e run as before.
- **Never stranded.** Every exit ends at the 30 s fallback or Home.
- Two gaps remain, both off the bar:
  - The locked-boot start can push a BFU call screen behind the keyguard (F3C-1).
  - A call at the take-over still ends on the stock launcher (F3C-2), which is the opposite of what the emulator
    check §4 expects.
- Findings: 0 High, 1 Medium, 4 Low, 2 Nits. 14 mutations: 9 killed, 5 survived (F3C-5).

## What holds

| Claim | Verdict |
|---|---|
| A device owner may start its own activity from the background, BFU | holds. `isDeviceOwner(callingUid)` -> `BAL_ALLOW_ALLOWLISTED_COMPONENT` (A14 `BackgroundActivityStartController` :340-346, A15 :859-862 as cited, A16 :1090-1092). The DO uid comes from `Owners.load()` -> `notifyChangeLocked` -> `setDeviceOwnerUid` (Owners.java :105-168), run from the DPMS constructor (:2339) before any user starts, so BFU too. ASM lets `NEW_TASK` + `ALLOWLISTED_COMPONENT` go to the front (A15 :1040-1046) |
| The start is HOME-typed and resolves to the cover alone, BFU | holds. No component, only `CATEGORY_HOME` (= `isHomeIntent`), and `setPackage`. Before the unlock only direct-boot-aware components match, and HomeActivity isn't one. For the system's own boot HOME (shutdown arm), the PPA walk skips HomeActivity's PPA (its activity is not in the query) and takes the cover's BOOT_COVER PPA |
| Never over the keyguard | holds, pinned (manifest attributes and a code scan; M1, M1b killed). Behind the keyguard the cover is created and stopped; `waitStep(visible=false)` while locked = STAY |
| `coverWaitStep` exits | take-over + stop -> FINISH. Take-over + 5 s in front -> FINISH. No take-over 30 s after the first resumed tick past the unlock -> disable, `startHome` (HomeActivity, which starts our process), then FINISH at its stop or the grace. A stop while still enabled never ends it (M5). No loop: FINISH returns before re-posting. The crash guard is unchanged (2 crashes -> disabled; HOME then resolves to HomeActivity unlocked, or to the stock launcher BFU) |
| Home resumes before the cover's first tick | fine. Home's start comes right after the main process's disable, so the cover's stop sees `disabled` -> FINISH. A cover stopped while disabled with no tick is finished by AMS (`cleanupDisabledPackageComponentsLocked`, AMS :15732) |
| Main-thread ordering | `BootCallReceiver.onReceive`, `ACTION_USER_UNLOCKED`/`ensureUnlockedInit` and the posted `BootCover.init` + `BootHome` pass all run on the main looper, so the locked-boot enable can't land between the init's disable and Home's start (except F3C-6) |
| `BootCallReceiver` | the call policy loads first (pinned, M8b). `atLockedBoot` reads DE only (marker, record, `isUserUnlocked`, pinned) and catches `Exception`, so screening BFU is untouched |
| No security effect of the marker | holds. It only picks whether our own non-showWhenLocked cover is armed and waits. It is a DE file that only our uid writes. A stale or missing marker changes what shows after the PIN, never whether the PIN is needed |
| Crash reboot, `adb reboot` | both deliver `LOCKED_BOOT_COMPLETED`, so both get the start. After a graceful arm the start is a re-delivery (singleTask, no `onNewIntent` needed) |

## Findings

**F3C-1 (Medium): the locked-boot start brings the home task over a call or the emergency dialer before the PIN.**
- Evidence: `BootCover.atLockedBoot` has no call check. `BootHome` has one for exactly this case: `liveCall`,
  `telecomInCall`, "never over a call".
- Scenario:
  1. A call rings or is live, or the kid has opened the keyguard's emergency dialer.
  2. Then our `LOCKED_BOOT_COMPLETED` arrives. It is an ordered boot broadcast and can be seconds late on a slow boot.
  3. The typed HOME start passes BAL/ASM as a new task to the front (above), so the home task moves over
     `InCallActivity` or the dialer.
  4. The cover isn't showWhenLocked, so the keyguard is no longer occluded. The call screen goes behind it. The call
     itself goes on, and the notification remains. [device]
- No PIN bypass and no stranding, but a call-path regression in a safety window.
- Fix: in `atLockedBoot`, skip the start while `OngoingCalls.hasLiveCall || VoipCalls.liveCall ||
  SelfUpdate.telecomInCall(app)`. These are readable BFU: our in-call service is direct-boot-aware in this process, and
  `READ_PHONE_STATE` is self-granted. Skipping costs only the old F3 look on that boot. The emergency dialer before
  anything is dialed stays a narrow residual (no public occlusion API). Add the case to the emulator checks
  (`gsm call` before `LOCKED_BOOT_COMPLETED` lands, then watch `topResumedActivity`).

**F3C-2 (Low): a take-over without Home's start ends on the stock launcher.**
- `BootCover.init` disables the cover. Then `BootHome.startIfDue` returns NONE for a live call (or the process dies
  between the two). Nothing is over the cover.
- AMS's `PACKAGE_CHANGED` cleanup finishes it after 1 s (10 s within 60 s of the system server's start: PMS :942-943,
  :2555, :4049-4051), or the 5 s grace does. The home task below is the stock launcher the boot started.
- Scenario (§4 of the design's emulator checks):
  1. An allowed call right after the PIN, before our init.
  2. Init disables the cover, and `BootHome` says "in a call".
  3. When the call ends, the stock launcher resumes. Lock task is off until the kid presses Home.
- The 5 s grace is mostly moot, because AMS gets there first.
- This is design 16's old gap (own mode too), but F3's check §4 expects "after the call our Home, no stock launcher".
- Fix (main process): when `BootHome` declines for a call, keep "boot Home pending". At the call's end (the existing
  `callsListener`), run `BootHome.startIfDue` again. The boot count is stored only after a start, so it is still due.
  Optionally, the cover's grace exit could start the typed HOME before finishing, since nothing is over it then.
  Otherwise rewrite check §4.

**F3C-3 (Low): a tripped crash guard is permanent and invisible in android mode.**
- The guard clears only when `wanted` goes false, and `switchOn || androidFace` keeps it true. The server's notes
  return early unless the switch is on (`kiosk_escapes.rs` :610).
- So a phone whose cover crashed twice in one boot shows the stock launcher after every reboot. The parent sees no
  note, and the only way out is leaving android mode, which means the rollback and a new migration.
- Fix:
  - Clear the trip on a new launcher versionCode (store it in `CoverRecord`) or on an off->on edge of the switch,
    whatever the mode.
  - On the server, gate the notes on the reported `boot_cover.wanted` instead of the switch. That is a server-only
    change.

**F3C-4 (Low): the marker follows the face, not a BFU credential.**
- `bootCoverAndroidMarker(wanted, androidFace)` with `faceOn` is false in two cases where Android's PIN still holds the
  boot BFU:
  - The rollback's NO_TOKEN escrow (owned, not requested, the PIN stays).
  - An apply without the parent's choice (corrupt-cache fallback, `policy == null`): `androidRequested(null)` is false.
    So the face, the marker and `wanted` go false, the guard is cleared and the cover disabled.
- In both, the next reboot shows the stock launcher after the PIN until a normal apply. There is no security effect
  either way.
- Acceptable as a residual. The cheap fix: derive the marker from "managed, device owner, `isDeviceSecure` and we own
  the credential" (`PinLockStore.keyguardAndroid`), or leave the marker untouched on an apply without `parentChoice`.
  The fix also covers the escrow.

**F3C-5 (Low): five wiring mutations survive.** The pure functions are well pinned, but the glue isn't:
- M4b: `AppEnforcer` passes `androidFace = true`. Own mode with the switch off would then get the marker and the
  locked-boot start: "own mode unchanged" is unguarded.
- M6: `setAndroidMode` is dropped, so no marker is ever written and F3 is silently off.
- M7: the PPA's `BOOT_COVER_CATEGORY` is dropped. The cover's PPA would then replace HomeActivity's (16b QA #7) - an
  old 16b gap.
- M9: `atLockedBoot` loses `setPackage`. A HOME start BFU could then pick the stock launcher or the resolver.
- M10: the cover never reads the marker. Android mode would get 16e's 3 s hand-over, right into the stock launcher.
- Fix: source pins in `BootCoverManifestTest`, in its existing style: `faceOn` passed from `AppEnforcer.apply`, the
  `setAndroidMode(app, bootCoverAndroidMarker(wanted, androidFace))` call, `addCategory(BOOT_COVER_CATEGORY)`,
  `.setPackage(app.packageName)` in `atLockedBoot`, and `waitForLauncher = BootCoverGuard.androidMode(this)` in
  `onCreate`.

**F3C-6 (Nit): a late locked-boot start after our take-over.**
- `KidAppComponentFactory` can run `ensureUnlockedInit` while `isUserUnlocked()` is still false (the CLAUDE.md
  direct-boot line). If `LOCKED_BOOT_COMPLETED` is handled after that pass, `coverAtLockedBoot` still says start:
  - The cover is enabled and started over our Home.
  - Two HOME PPAs are active while unlocked (16b QA #8).
  - The mark shows 30 s until its own hand-over.
- The window is tiny, and nothing strands.
- Fix: a `@Volatile tookOver` that `BootCover.init` sets and `atLockedBoot` checks (same thread). Separately, a broadcast
  that arrives after `RUNNING_UNLOCKED` is skipped, and that boot looks like the old F3. Note it as a residual in the
  doc.

**F3C-7 (Nit): `HAND_OVER` never starts Home if the disable doesn't read back.** It retries the disable, with a log
line, every 250 ms and never calls `startHome`. That never happens for one's own component with `DONT_KILL_APP`, but
"never stranded" shouldn't depend on it. Fix: call `startHome()` once after N failed tries anyway. The PPA may still
pick HomeActivity.

## Timing (`Unlocked setup took N ms`), recommendations only

- The log covers only `LauncherPreferences.init` + `initRest`.
- `decide` and `PowerEvents.recordUnlock` come before it.
- The posted `BootCover.init`/`BootHome` pass comes after it, together with everything queued meanwhile.
- So the log is not the gap. For the release timing, log `Home brought to the front` against the unlock: `BootHome`
  could append elapsed time since `processStartElapsed`. The cover's leave line already gives the kid-visible number.
- Cheap and safe to move behind the boot Home pass: everything in `initRest` after `PinLockRuntime.init` (`MusicMedia`,
  `Torch`, `GlanceStore.loadAtStart`, `MissedCallNotifier.refreshAtStart`, `BatteryStatus.watch`, `PowerEvents.init`,
  `FirebaseCleanup`). Today these run on the main thread before the posted pass, so wrapping them in a
  `handler.post` queued after it would do.
- `createNotificationChannels` and the time rules' recheck could move as well, if the time-rule screen doesn't need them
  before Home.
- The 12.6 s is mostly ART verification on the debug build. Measure the release build first; a baseline profile is
  the bigger lever if it is still large.

## Mutations

Targeted tests: `BootCover*`, `DirectBoot*` (`testDebugUnitTest -PwarningsAsErrors=true`). Each mutation was reverted,
and the tree is clean.

| # | Mutation | Result |
|---|---|---|
| M1 | `android:showWhenLocked="true"` on the cover | killed (ManifestTest F3) |
| M1b | `setShowWhenLocked(true)` in `onCreate` | killed (ManifestTest F3) |
| M2 | no 30 s `HAND_OVER` in `coverWaitStep` | killed (PlanTest F3) |
| M2b | `HAND_OVER` without `startHome()` | killed (ManifestTest F3) |
| M3 | no 5 s grace (FINISH only when stopped) | killed (PlanTest F3) |
| M4 | marker = `wanted` (own mode with the switch arms it) | killed (PlanTest marker) |
| M4b | `AppEnforcer` passes `androidFace = true` (arm in own mode) | **survived** (F3C-5) |
| M5 | leave before take-over (`!visible -> FINISH`) | killed (PlanTest F3) |
| M6 | skip `setAndroidMode` (no marker) | **survived** (F3C-5) |
| M7 | PPA without `BOOT_COVER_CATEGORY` | **survived** (F3C-5, 16b gap) |
| M8 | `coverAtLockedBoot` ignores `unlocked` (start after the unlock) | killed (PlanTest locked boot) |
| M8b | `atLockedBoot` before the call policy | killed (ManifestTest F3) |
| M9 | `atLockedBoot` without `setPackage` | **survived** (F3C-5) |
| M10 | the cover never reads the marker (`waitForLauncher = false`) | **survived** (F3C-5) |
