# QA F3 fix round 2 - code (`fix-f3-boot-cover`, `4ada6859..2483caf8`)

Code QA of the second F3 fix round (the F3 emulator run's B1-B6, `docs/testing/2026-10-22-f3-run.md`), against design
59 "F3: the first unlock after a reboot" and `launcher/CLAUDE.md`'s 16b bullet. Reviewed 2026-10-22 on `f3r2-qa`
(= `2483caf8`). Launcher only, no emulator. AOSP claims were checked against android15-release
(`ComputerEngine`, `PreferredActivityHelper`, `PolicyEnforcerCallbacks`). [device] = only the phone can tell.

**Run**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1557 tests, 0 failures.

**Verdict**: the bar holds - no stranded phone, no path past the PIN, no call screen pushed behind the keyguard, own
mode unchanged. One Medium: B2's change (take over at once, even under a call) brings back the stock launcher for a
call's length on non-graceful boots, and AOSP doesn't support its rationale. Findings: 0 High, 1 Medium, 4 Low,
2 Nits. 12 mutations: 9 killed, 3 survived (F3R-7).

## What holds

| Claim | Verdict |
|---|---|
| B1: decided at `LOCKED_BOOT_COMPLETED` in a manifest receiver | holds. `BootCallReceiver` is a DBA manifest receiver (manifest :345-352). The run proved its BFU delivery (calls screened, b2's start), and a DO may start the cover from the background BFU (qa-f3-boot-cover-code). The decision reads DE + binder only (pinned) |
| The retry alarm: permission, BFU, frozen process | holds. `USE_EXACT_ALARM` is in the manifest (:92, granted at install, targetSdk 36), so `setExactAndAllowWhileIdle` doesn't throw; the `SecurityException` fallback is dead code but harmless. Alarms run BFU. The PendingIntent is explicit to our non-exported receiver, so only we can fire it. An alarm broadcast or a manifest receiver's is runnable at once, never `CACHED_INFINITE_DEFER` (`BroadcastProcessQueue.updateRunnableAt` :1237-1245, checked before the freezable branch); if the process was killed, the alarm restarts it BFU (call path only, `tookOver` false, which is correct). One pending alarm at a time (same request code, `FLAG_UPDATE_CURRENT`). [device] the 5 s cadence in a frozen process (run step 4) |
| What stops the retry | the call's end (START), the unlock (`unlocked` -> NONE + cancel), our take-over (`init` cancels). No cap: see F3R-6 |
| B2: every unlocked start disables the cover | holds. `init` -> `takeOver` at the top level (pinned). Cost is one binder read (`getComponentEnabledSetting`) plus `AlarmManager.cancel` on the main thread, and a write only when the cover was enabled. Idempotent |
| The two enable sites | holds. Only `atLockedBoot` (BFU START) and `coverAtShutdown` enable it. `applyPolicy` returns `null` (no change) when wanted, `BootCoverGuard` only disables, and the cover's process never enables (pinned) |
| The cover's `onDestroy` disable | holds. It runs only unlocked and not on a configuration change. BFU it never disables (the PIN's reveal is intact). Own mode: the cover disabled itself at the hand-over anyway, so this changes nothing there |
| `clearPackagePersistentPreferredActivities` clears all of a package's PPAs | holds (`PreferredActivityHelper` :419-434 -> `Settings.clearPackagePersistentPreferredActivities(packageName)`). The design is right not to use it |
| A disabled cover's PPA is inert | holds. `ComputerEngine.findPersistentPreferredActivity` (:3515-3575) returns a PPA only if its component is in `query`, and a disabled component isn't in it |
| `armedAsHome` BFU | `resolveActivity` runs as us, with `QUERY_ALL_PACKAGES`. BFU only DBA HOMEs match: the cover (PPA) when enabled, otherwise the stock launcher/FallbackHome, or `ResolverActivity` for a tie. START uses `setPackage`, so it can only start the cover. It can't loop: one START per decision, the retry is cancelled, and a re-delivery to the cover's singleTask is a no-op |
| B3: exceptions | caught as before: the tail has its own `try`/`catch (Exception)` + `CrashReports.record` (Application.kt :407-440). An `Error` still reaches the process handler, as before |
| B3: ordering | the tail is posted after `PinLockRuntime.init`'s boot pass, so the pass (take-over, `BootHome`, `ProcessStart`) runs first. Nothing in the pass reads the tail's state: `checkStream` is a no-op without the service, the glance listens to `GlanceStore`/`MissedCallNotifier` changes, and lock task needs only the head's prefs. One reader isn't covered: F3R-3 |
| B4/B5 | `START_HOME` is reachable only while the cover is resumed (a stop while disabled is FINISH first), and `HAND_OVER` needs `visible`. "Something in front" means the cover isn't resumed: ticks run only between `onResume` and `onPause`. `telecomInCall` works in `:bootcover` (the uid holds `READ_PHONE_STATE`). A self-managed VoIP call isn't seen, but its screen in front stops the cover anyway |
| No path past the PIN, never over the keyguard | unchanged: no `showWhenLocked`, no dismissal, and `coverWaitStep` is STAY while locked (pinned) |

## Findings

**F3R-1 (Medium): with the cover in front during a call, the round-2 take-over shows the stock launcher for the
whole call on non-graceful boots.**
- Evidence:
  - `BootCover.init` now takes over at every CE start, call or not (BootCover.kt :104-117; round 1 kept the cover
    enabled under a call).
  - AMS finishes the disabled cover about 1 s later. The cover's own grace then FINISHes it even in a call
    (BootCoverPlan.kt :271, pinned "the grace still ends a taken-over cover in a call", BootCoverPlanTest :147).
  - Every Home start of its own is held by the call: `START_HOME` (:272) and `coverDestroyStartsHome` (:297).
- Scenario: `adb reboot`, a crash or a hard reset (or B6's lost arm) -> the locked-boot START puts the cover over the
  stock launcher -> a call is answered BFU and the kid presses Home to reach the pad (the run's s5d/h1 path) -> PIN
  -> our process disables the cover -> AMS finishes it while it is in front -> `onDestroy` starts nothing (call) ->
  the stock launcher, kiosk off, for the rest of the call plus `CALL_END_HOME_RETRY_MS`. The design's residual
  ("up to those 2 s") covers only the case where the call screen is in front of the cover.
- The rationale is weak too. `ComputerEngine.findPersistentPreferredActivity` (:3540-3570) returns the first PPA whose
  component is in the query. Two enabled HOME PPAs of ours resolve to one of them, never to the chooser. The run's
  three-entry chooser means no PPA matched at all, so "the cover enabled while unlocked" doesn't explain B2.
- Fix: a resumed cover proves that no call screen is above it, so its own Home start can't bury one. Drop `!callLive`
  from `START_HOME` and from `coverDestroyStartsHome` (both are in-front only; keep `HAND_OVER`'s `visible`). Make
  the grace in a call a `START_HOME`, not a bare FINISH. Then the take-over at once costs nothing. Fix the residual's
  wording in the design.

**F3R-2 (Low): B2 is not closed, and the locked-boot check reads the resolution, not the started HOME.**
- Evidence: `armedAsHome` (BootCover.kt :181-190) asks PM what HOME resolves to now. The run's chooser came from
  `startHomeOnAllDisplays`, while `cmd package resolve-activity` gave the cover in BFU around those boots, so the
  state that made the chooser may be gone by `LOCKED_BOOT_COMPLETED`.
- Scenario: a B2 boot. The chooser is the resumed HOME, `armedAsHome` reads the cover -> NONE, and the PIN reveals
  the chooser (r1: +0.3 to +11.8 s, "Pixel Launcher" one tap away). F3R-1's change doesn't touch this path (BFU).
- Fix: in android mode also START when armed (a typed start to the cover's singleTask brings it over whatever the
  boot started). The cost is F3R-4's dialer residual on graceful boots too. Or decide on the device first: on a
  chooser boot, capture `dumpsys package preferred-activities`, `dumpsys device_policy` and the `Locked boot: HOME
  resolves to` line. The chooser also stays under the cover in the home root task: check that it never comes back
  [device].

**F3R-3 (Low): the flashlight tile and the lock's torch button are missing when an activity start launched the
process.**
- Evidence: `Torch.init` sets `state` without `changed()` (Torch.kt :73). `seen` notifies only on a change (:123),
  and the torch callback's first answer is "off", the same state. The tail is now posted (Application.kt :407).
- Scenario: the process is started by HomeActivity's launch: a crash or LMK restart plus Home, the cover's
  `START_HOME`/`HAND_OVER`, N4's stock launcher. The launch transaction is queued before `onCreate` posts the boot
  pass and the tail, so Home renders with `Torch.state == null` and no listener call follows. The tile stays missing
  until something else re-renders the grid. The design's "the torch tile comes with its callback" is wrong.
- Fix: call `changed()` at the end of `Torch.init` (one line), and correct the design's "Readers" bullet.

**F3R-4 (Low): "`LOCKED_BOOT_COMPLETED` comes 1-5 s after the boot" isn't a bound.**
- Evidence: BootCoverPlan.kt :220-223 (the START doc), design "RETRY". In the same run the broadcast came 15 s after our Home start
  (o2, run step 7). Under load or on a slow phone, START can land while the kid is in the keyguard's emergency
  dialer.
- Fix: word it as "usually 1-5 s on the emulator". Log `isInteractive` with `Boot cover started at the locked boot`
  (logged only, no decision on it), so the Jelly Star run shows how often the start comes with the screen on.

**F3R-5 (Low): B3 doesn't bound the head.**
- Evidence: a service create the system queues during the head waits for the head's whole message, and the ANR
  clock (20 s) runs from its send. g1's `Unlocked setup took 27228 ms` was head + tail. Nothing yet shows how much of
  that is the head (`decide`, `LauncherPreferences.init`, the update fence, `SelfUpdate`, `AppDownloads`,
  `CameraLock`, `PinLockRuntime.init`).
- Fix: log the tail's own duration next to `Unlocked setup took`. Run step 7 then shows whether the head alone
  nears 20 s on a first boot after install. The release build is the real measure.

**F3R-6 (Nit): the retry has no cap, and two small leftovers.**
- `BootHome.callLive` counts an unknown Telecom answer as a call. An unreadable Telecom BFU then means an exact
  wake-up every 5 s until the PIN, and no cover. This is bounded by the unlock, and the outcome is the old stock
  launcher, so it's acceptable. A cap (say 10 min) plus a log line would make it visible.
- `cancelRetry` creates a PendingIntent at every CE start. Use `FLAG_NO_CREATE`.
- The `SecurityException` fallback to an inexact alarm is dead code under `USE_EXACT_ALARM`. Use the
  `canScheduleExactAlarms()` pattern of `TimeRuleAlarm`/`Torch`.

**F3R-7 (Nit): the glue is pinned by source text, so some mutations survive** (M7, M11, M12). The
`armedAsHome` result, the `telecomInCall` body and the `finishedInFront` mark aren't tested. Fix: source pins
on `home != null && home.packageName == app.packageName && home.name == BootCoverActivity::class.java.name` as
the whole expression, on `isInManagedCall == true`, and on `onPause`'s `if (isFinishing && !ownFinish)
finishedInFront = true`, in `BootCoverManifestTest`'s style.

## Mutations

Each mutation was run with `testDebugUnitTest --tests '*BootCover*' --tests '*BootHome*'` and then reverted (tree clean
after).

| # | Mutation | Result |
|---|---|---|
| M1 | `coverAtLockedBoot` loses `callLive -> RETRY` (the locked boot starts over a call) | killed: `BootCoverPlanTest` "f3-run B1 ..." |
| M2 | `BootCover.init` loses its `takeOver(app)` (no unlocked-start disable) | killed: `BootCoverManifestTest` "the android-mode glue is wired" |
| M3 | `RETRY -> Unit` (no alarm) | killed: same |
| M4 | `BootCallReceiver`'s retry branch returns without `atLockedBoot` | killed: same |
| M5 | `PinLockRuntime.init` moved after the posted tail (tail queued before the boot pass) | killed: "initRest posts its tail after the lock's init" |
| M6 | the cover's `onDestroy` disable dropped | killed: "exactly one HOME PPA of ours resolves once unlocked" |
| M7 | `armedAsHome` always false (`... && false`: the HOME-resolve branch always starts) | **survived** |
| M8 | `HAND_OVER` loses `visible &&` | killed: `BootCoverPlanTest` "f3-run B4 and B5 ..." |
| M9 | `coverDestroyStartsHome` loses `!callLive` | killed: "f3-run B2 - destroyed by the system ..." |
| M10 | `COVER_HOME_NUDGE_MS` back to 500 | killed: "f3-run B4 and B5 ..." |
| M11 | the cover's `telecomInCall` reads `isInManagedCall == null` (never a call) | **survived** |
| M12 | `onPause`'s `finishedInFront` mark dropped (no Home start on a system finish) | **survived** |

There is no retry cap to mutate (F3R-6). The survivors are F3R-7. M7 would add a needless start after every
graceful boot, with F3R-4's dialer cost. M11 is B5's call read. M12 is B4's process-death path.

## Residuals (asked)

- **The emergency dialer in the first seconds BFU** (non-graceful boots only; after a graceful boot `armedAsHome` is
  NONE): acceptable. Only the dialpad before dialing is at risk: a placed emergency call is live to `isInCall`, so it
  gets RETRY. The kid taps Emergency again. Nothing public can detect it: `TelecomManager.isInEmergencyCall` is a
  `@SystemApi` behind `READ_PRIVILEGED_PHONE_STATE`, `KeyguardManager` has no "occluded" query, and
  `isKeyguardLocked` is always true BFU. But the window is not "1-5 s": see F3R-4.
- **The stock launcher ≤2 s after a call (adb/crash boots)**: acceptable as written. But it covers only the call
  screen in front of the cover. With the cover in front during the call, it is the whole call: F3R-1.
