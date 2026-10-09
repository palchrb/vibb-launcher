# 16: Boot Home window - the stock launcher is Home before ours

L paths under `launcher/app/src/main/java/com/kidslauncher/mdm/`. AOSP `android16-release` (googlesource, read 2026-10-06): CE
ComputerEngine, RIH ResolveIntentHelper, RWC RootWindowContainer, ASI ActivityStartInterceptor, LTC LockTaskController, ATS
ActivityTaskSupervisor (as cited in the 11 doc), core manifest = `core/res/AndroidManifest.xml`. Investigation only, no code changed.

## 1. Evidence (AVD jellystar, Android 16 Play `BE2A.250530.026.D1`, gesture nav, `me.vibb.launcher.debug` DO, kiosk on, PIN lock)
- **State**: ROLE_HOME = ours; DO persistent preferred MAIN/HOME/DEFAULT -> our HomeActivity (`dumpsys device_policy`); the role's ordinary
  preferred HOME = ours. `query-activities` HOME: NexusLauncherActivity and ours, both priority 0. **With `--query-flags 0x80000`
  (direct-boot-aware only) NexusLauncherActivity is the only one.** `com.android.settings` is `hidden=true` (suspend refused, hide works),
  so FallbackHome is no candidate and `resolve-activity -a android.settings.SETTINGS` finds nothing. uid 10182 =
  `com.google.android.apps.nexuslauncher`, 10217 = ours. `config_recentsComponentName` = `nexuslauncher/com.android.quickstep.RecentsActivity`.
- **Two boots**: r0 is the reported one (after the emulator crash, load 17). r1 is a normal `adb reboot`, polled every ~0.5 s with screenshots.

| Event (21:05 / 21:15, seconds) | r0 | r1 |
|---|---|---|
| system START HOME -> NexusLauncher (uid 0, user still locked) | 48.65 | 44.38 |
| boot animation ends (`sf_stop_bootanim`) | 54.57 | 50.65 |
| CE unlocking / USER_UNLOCKED | 54.92 / 55.60 | 51.10 / 51.92 |
| our process starts (the always-on VPN starts it) | 55.86 | 52.54 |
| PinLockActivity resumed (`PinLockRuntime` ProcessStart) | 57.82 | 55.17 |
| Nexus starts our Home itself and finishes (`ActivityStartInterceptor: Starting home with component specified, uid=10182`) | 59.44 | 55.17 |
| quickstep preloads its RecentsActivity -> `BlockedAppActivity` in a hidden `type=recents` task owned by `android` | 58.51 | 57.11 |

- **The window** (boot animation end -> our first screen) is **3.3 s (r0) and 4.5 s (r1)**, so it happens on a normal boot too. The first
  ~1 s shows the launcher's blank direct-boot state (screenshot), then the unlocked Pixel Launcher. In r1, Nexus's handover put our Home over
  the PIN lock (`startLockTask failed: Invalid task, not in foreground`) until the re-front loop at 56.88 (1.2 s). The status bar stayed
  `mDisabled1=0x2070000` in the window (DPM `setStatusBarDisabled`, kept from LOCKED), so the shade and QS were closed. A phone that went
  down UNLOCKED boots with them open.
- **"Pixel Launcher is not available"** (r0 21:06:47): the PIN unlock ran `PinLockActivity.leave()` -> `stopOwnLockTask()`. The lock had
  started lock task first at boot and was the root, so AOSP cleared every locked task (Home `finish-imm:clear-task-all`). The recents task
  surfaced, and BlockedAppActivity (`lockTaskMode="always"`, core manifest) started lock task on itself (ATS 891-896). After r1 the same
  latent task (#18) is in the stack. The difference is that Home is the root there.

## 2. Cause
1. Before CE unlock, PackageManager matches only direct-boot-aware components (CE:2734-2745). The persistent preferred HOME is skipped while
   its component doesn't resolve (`ai == null -> continue`, CE:3520-3573; not removed). With one candidate, RIH returns it without any
   preference check (RIH:159). With FallbackHome present, Nexus would still win on priority 0 vs -1000 (RIH:173). RWC:1346-1405 starts it.
   HomeActivity must stay non-DBA (BFU lockout rule), so on a phone with a DBA stock launcher that launcher is Home at every boot.
2. Nothing resolves Home again at unlock. AOSP relies on FallbackHome finishing. A DBA launcher stays until it hands over by itself
   (Pixel does it ~3.5 s after USER_UNLOCKED; undocumented) or until something of ours covers it. We never start Home at boot, only the PIN lock.
3. Quickstep (TouchInteractionService, DBA, in the Nexus package) preloads the fallback Recents at unlock. In lock task that start becomes
   BlockedAppActivity (ASI:220, 364-378) in a recents task above Home. By the same path, kiosk Recents (OVERVIEW feature) on Pixel is a
   BlockedAppActivity [inferred].
- **What the kid can reach in the window**: not Settings (already hidden, so option "hide Settings" is moot; FallbackHome would need it
  visible and still loses). Reachable: the Pixel Launcher drawer (non-hidden apps, suspended ones greyed out), long-press -> Wallpaper &
  style (`com.google.android.apps.wallpaper`, unmanaged), launcher settings, widgets, Recents (restored tasks; only non-suspended apps
  start). Shade/QS only after an UNLOCKED shutdown. There is no lock task (lock task never survives a reboot).

## 3. Options
| Option | Closes | Cost / risk |
|---|---|---|
| A. **Home first at boot**: at the first process start of a boot (BOOT_COUNT differs from the stored value), early in `initRest` (prefs and cached policy readable, before `loadApps`), call `SelfUpdate.bringHomeToFront(app, "boot")` (Home, then the PIN lock if LOCKED; never over a call; managed or kiosk only) | the unlocked part (~2-3 s); Home becomes the lock-task root, so a PIN unlock no longer clears it | none new; the BFU second remains; Nexus's own handover may still put Home over the lock (re-front loop, 0.5 s) |
| B. **Lock leaves through Home**: in `leave()`, when `stopOwnLockTask()` stopped a lock task while the kiosk is on, start Home (`onResume` re-enters lock task) | the r0 symptom whenever the lock was the root (also outside boot) | trivial |
| C. **Boot fence**: keep the direct-boot-aware HOME candidates other than ours suspended while managed. `fencePlan` over a `MATCH_DIRECT_BOOT_AWARE` HOME query, own write-ahead record, server switch, default off | BFU and unlocked window, the recents preload (suspension is checked before lock task, ASI:215-219; with Settings hidden the admin-support intent doesn't resolve, so no task), crash and `adb install` windows | quickstep in a suspended package at all times: the open A3 questions (gesture Home/Recents, both nav modes); Recents dead with the kiosk off; blank wallpaper until our Home. `orphanFenceTargets` must exclude the boot-fence set (else it unsuspends it at every start and `apply()`); release on unmanaged or switch off. The update fence is unaffected (its ALREADY_SUSPENDED skip keeps these packages out of its record) |
| C'. As C, armed only at `ACTION_SHUTDOWN`, released once Home or the lock is in front | clean reboots | no crash reboots (r0!); whether a suspension written during shutdown persists [device check] |
| D. Hide other HOMEs, or disable only their HOME component | - | hiding = PACKAGE_REMOVED for quickstep (11 doc option d, rejected); no DO API disables another package's component (`setComponentEnabledSetting` needs a privileged permission) |
| E. Lock task before unlock | - | needs DBA lock-task code, which is forbidden (BFU lockouts); after unlock this is A |
| F. Own DBA "boot cover" HOME (a FallbackHome clone) | BFU look | needs a second persistent-preferred HOME that must lose to HomeActivity after unlock (otherwise it is Home). Two DBA priority-0 candidates without a preference give the chooser. Needs `DirectBootComponents`. A DBA HOME in our package is the incident class: rejected |
| H. Put the recents package on the lock-task list | latent BlockedApp, kiosk Recents | the whole stock launcher package (NexusLauncherActivity, its settings) becomes startable in kiosk: rejected. Instead, decide whether kiosk OVERVIEW makes sense with a third-party Home |

## 4. Recommendation
**A + B now** (own code only; pure `bringHomeAtBoot(firstStartThisBoot, managed, kioskOn, liveCall, telecomInCall)` next to
`bringHomeAfterUpdate`, pure `homeAfterLockLeave(stoppedOwnLockTask, kioskOn)`, both unit-tested). Expected window: boot-animation end to
~0.5 s after USER_UNLOCKED, the first ~1 s the launcher's blank direct-boot state. **C later**, own `boot_fence` server switch (default
off), only after A3 (11 doc) and check 8 pass on the Jelly Star; not needed where no other DBA HOME exists (check 1).

## 5. Device checks (emulator first, then Jelly Star)
1. `cmd package query-activities --brief --query-flags 0x80000 -a android.intent.action.MAIN -c android.intent.category.HOME`: is the
   stock launcher (Unihertz Launcher3, own package?) DBA? If not, no stock-launcher window exists (pre-unlock: nothing, or FallbackHome).
2. `cmd overlay lookup android android:string/config_recentsComponentName`: is quickstep in the launcher package?
3. Same query without flags: any enabled HOME with priority > 0 beats our persistent preferred even after unlock (RIH:173 returns first).
4. `dumpsys package com.android.settings | grep hidden=` and `cmd package resolve-activity -a android.settings.SETTINGS` (expect hidden, none).
5. Reboot twice: power menu while LOCKED, and `adb reboot` while UNLOCKED. Poll `dumpsys activity activities | grep -m1 ResumedActivity`,
   `dumpsys statusbar | grep -m1 mDisabled1` and `getprop sys.user.0.ce_available` every 0.3 s. Run `logcat -b all -d` for
   `START u0 .*HOME|uc_finish_user_unlock|Start proc .*vibb|BlockedAppActivity|Starting home with component`. Record who is Home, the
   window, and whether the shade is open.
6. After boot: `dumpsys activity activities | grep -E "Task\{|Hist"`. Is there a `type=recents` task with BlockedAppActivity?
7. Does the stock launcher hand over to ours by itself after unlock, as Pixel's does? Without A, a launcher that doesn't stays Home until
   the PIN lock is unlocked.
8. For C: A3, plus a reboot with the launcher suspended. Check what shows pre-unlock, whether system_server logs a `No home screen found`
   loop, and how gesture Home/Recents/Back behave in both nav modes.
9. A/B on the emulator: the r0 sequence (boot, PIN unlock) ends on our Home, and `mLockTaskModeTasks` #0 is the Home task after boot.

## QA review (2026-10-06)
Checked against `lock/`, `ui/HomeActivity`, `server/SelfUpdate.kt`, `LockTaskHelpers.kt` and AOSP `android16-release` (AR ActivityRecord, LTC LockTaskController, ATS, ASI, CE, RIH, PAH PreferredActivityHelper) + Launcher3 quickstep; includes the user's option D (DBA boot cover = §3 F) and the live "Pixel Launcher is not available" report.
1. **High - Home must start typed HOME.** `bringHomeToFront` sends `Intent(app, HomeActivity)`: an explicit component is HOME-typed only for system/recents/resolver callers (AR:2845-2877) and ASI only normalises MAIN+HOME intents (ASI:512-541). At boot no Home task exists, so A makes a STANDARD Home task and Nexus's handover or the Home key later start a second, HOME-typed instance (singleTask doesn't match across types). Change: every "bring Home" path (A, B, update, role change) starts `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME).setPackage(ours)` + NEW_TASK (no component).
2. **High - with the kiosk on the lock must never be the lock-task root; B is keyed wrong.** A plus ProcessStart start Home and the lock back to back, so the lock often resumes first and roots lock task (r0 again). After a process restart `startedLockTask` is false: B never fires, and a root lock can't even finish (`activityBlockedFromFinish`, LTC:254: kid stuck on an unlocked lock); removing it clears every locked task (ATS:1819). Change: kiosk on -> `ensureLockTask` starts Home (as in 1) instead of `startLockTask`; Home's `onResume` enters lock task and re-shows the lock (it already does when LOCKED); the lock's own `startLockTask` only as a fallback after ~1 s with no lock task. At boot A starts only Home; ProcessStart's show stays as a 1 s fallback (never without a lock).
3. **High - `HomeActivity.reconcileKioskMode` calls `startLockTask()` unguarded.** ATMS throws `IllegalArgumentException("Invalid task, not in foreground")` (r1 hit it in the lock). With A's back-to-back starts Home can crash at boot, and the crash handler counts that for the PIN-lock crash guard (`PinLockActivity.instances > 0`). Wrap it, retry on the next resume.
4. **High - the dialog (user: never shown).** BlockedAppActivity is `android`, `lockTaskMode="always"` (core manifest 9240-9245): whenever it really starts it joins lock task and moves itself to the front (ATS:894-900, LTC `setLockTaskMode(andResume)`). (a) Stale: quickstep preloads its Recents at TIS init (`ActivityPreloadUtil`); with the kiosk on this becomes a hidden, unstarted BlockedAppActivity recents task that surfaces when the task above it goes (the lock finishing at unlock = the live report). We can't remove another app's task (no DO API; `removeTask` needs REMOVE_TASKS). (b) Live: `isActivityAllowed` ignores OVERVIEW (LTC:418-429), so with the block bit every Recents and, with gestural nav while HOME or OVERVIEW is on (`canStartAnyGesture`), the swipe-up's fallback-Recents start is this dialog. The server's default kiosk features include both (devices.rs:207-212): not "[inferred]", it is every Pixel today. A, B and D remove neither.
5. **Fix for 4:** (a) the lock always leaves through Home with the kiosk on: start Home (as in 1) *before* `finishAndRemoveTask` (stop lock task first only when the lock is the root, see 2) - Home then sits above any latent task and is never finished. (b) Pure `kioskFeatures(...)`: while the block bit is on and `config_recentsComponentName`'s package isn't lock-task permitted, drop OVERVIEW; device-check whether swipe-up with HOME on still starts the fallback Recents (Pixel + Jelly Star, both nav modes). If it does, choose per device: HOME off in kiosk (Back only), pin only the recents package (H) or C. Add "no BlockedAppActivity after unlock / Recents / swipe-up" to `smoke-test.sh`.
6. **Medium - gate and placement.** `bringHomeAfterUpdate` needs `appsManaged || kioskOn`: a phone with only calls managed + PIN lock (kiosk off) keeps the stock launcher under the lock (check 7). Add "PIN lock active". "Early in initRest" runs before `PinLockRuntime.init`, so `showIfLocked` runs before ProcessStart (mode still DISABLED, no-op). Harmless only by luck: run A after `PinLockRuntime.init`, or use 2's Home-shows-lock path. Store the boot count after Home was started.
7. **D - resolution.** In BFU HomeActivity's PPA is skipped (`ai == null -> continue`, CE:3549-3555). A DBA cover beats Nexus only with manifest priority 0 (non-privileged apps can't go higher; a lower one, like FallbackHome's, loses at RIH:173 before any preference is read) **and** its own PPA. Without the PPA, two priority-0 DBA candidates plus a preferred HomeActivity outside the query give ResolverActivity (chooser) in BFU. Its PPA needs a distinct filter (e.g. an extra category): the DPM policy engine keys PPAs by IntentFilter (DPMS:11603), and PAH:410 appends without replacing.
8. **D - after unlock.** Both PPAs resolve, and their order isn't stable: PMS writes PPAs from an ArraySet and `IntentFilter.writeToXml` drops priority (IF:2506), so each boot either may win. If the cover wins, its explicit start of HomeActivity isn't normalised (ASI:527-534) and Home is STANDARD. Workable only so: once unlocked and shown >= 1 s, the cover disables its own component (DONT_KILL_APP). AMS finishes it, the system re-resolves HOME to HomeActivity (HOME-typed), and Home's own launch splash bridges the gap. It is re-enabled at ACTION_SHUTDOWN. Crash reboots (r0) then fall back to today's window, so A stays needed.
9. **D - other effects.** ROLE_HOME is unchanged (same package). `fencePlan`/`orphanFenceTargets` never touch ours. PlayInvariantsTest: add the cover's PPA and never clear or replace HomeActivity's. Lock task: same package, so the cover's task is permitted; it never calls `startLockTask`, and a start while LOCKED just joins and leaves as a non-root. **Must** be in `DirectBootComponents`: otherwise `KidAppComponentFactory` runs `ensureUnlockedInit` in BFU, CE prefs throw, `initUnlocked` catches it and `unlockedInitDone` stays true (no enforcement in that process).
10. **D - crash safety and the BFU rule.** A cover that crash-loops while pinned by a PPA has no fallback HOME (Settings hidden, adb off). That is the reason behind the BFU rule (our code in the boot path, no way out), even though its wording (HomeActivity, lock-task code) doesn't cover D. Required: own process `:bootcover` (Application.onCreate does nothing there, so a crash can't take the DBA call path), plain `android.app.Activity`, no CE, no native code, and a DE crash counter that disables the component on the 2nd crash of a boot.
11. **D - look.** `splash_vibb_breathe.xml`, `kid_ground` and Nunito are APK resources, readable in BFU. Use a framework NoActionBar theme with `windowBackground` #0C0C14 (not `launcherBaseTheme`/AppCompat) and start the AVD in onResume. "Until Home drew" comes from z-order: the cover stays under Home until it disables itself.
12. **Recommendation: A+B with 1-6 now** (they also close the dialog). D only later, as D+A+B behind its own server switch (default off), after device checks: cover PPA vs Pixel and Jelly Star in BFU, self-disable finishing the cover, ACTION_SHUTDOWN enable persisting, crash guard. Never D alone or D+B: crash reboots, the unlock race and the dialog stay.

## Decisions after QA review

- **Build A+B now with QA #1-#6** (#12): every "bring Home" path starts a typed HOME intent for our package; with the
  kiosk on, Home (not the lock) roots lock task; `reconcileKioskMode` guards `startLockTask`; the lock always leaves
  through Home; OVERVIEW is dropped from the kiosk features while the block bit is on and the recents package isn't
  lock-task permitted (pure `kioskFeatures`); the gate includes "PIN lock active" and runs after `PinLockRuntime.init`.
  `smoke-test.sh` checks "no BlockedAppActivity after unlock / Recents / swipe-up". User requirement: the kid never
  sees "App is not available".
- **D (boot cover with the breathing Vibb logo) is wanted by the user** and comes next as its own step 16b: D+A+B
  behind a server switch (default off), with QA #7-#11 (own PPA with a distinct filter, priority 0, self-disable after
  unlock + re-enable at shutdown, own process `:bootcover`, DE crash counter, framework theme, AVD from APK
  resources), switched on after the device checks in #12.

## Implementation status (2026-10-06)

Built: A+B with QA #1-#6, and since 2026-10-07 D as step 16b (below).
- **#1 typed HOME**: `lock/HomeFront` starts `MAIN` + `HOME` restricted to our package (no component, no other
  category) for every "bring Home" path: boot (A), the lock leaving (B) and asking Home to root lock task, the
  update (`SelfUpdate.bringHomeToFront`), a dialer-role change (`AppEnforcer`) and the end of Play's install
  mode. `HomeFrontTest`: no explicit `HomeActivity` start anywhere, one HOME activity in the manifest.
- **#2 Home roots lock task with the kiosk on**: `PinLockActivity.ensureLockTask` follows the pure `lockTaskEntry`
  (`lock/LockTaskRoot.kt`): no lock task + kiosk on -> start Home (at most every 3 s; Home's resume calls
  `startLockTask` and shows the lock again), the lock's own `startLockTask` only 1 s later as the fallback; kiosk off
  unchanged (the lock roots its own). At boot only Home is started; `ProcessStart(homeFirst)` gives
  `LockStep.showLockLater`, i.e. the re-front check 1 s later (never without a lock). `showIfLocked` after an
  update is the same 1 s backstop.
- **#3**: `HomeActivity.reconcileKioskMode` catches the `startLockTask`/`stopLockTask` exceptions; the next resume
  (or the lock's fallback) tries again.
- **#5(a) the lock leaves through Home** (pure `lockLeave`): kiosk on, Home is started before
  `finishAndRemoveTask`; lock task is stopped first only when this lock started it (the root). A refused finish
  (`isFinishing` still false: a root lock from before a process restart) retries as `rootLeave` (stop, Home,
  finish). Kiosk off unchanged (no Home: the kid returns to his app).
- **#5(b)**: pure `kioskFeatures` (`server/LockTaskHelpers.kt`) drops OVERVIEW from the kiosk's features while the
  app-block bit is on and the package of `config_recentsComponentName` (`AppEnforcer.recentsPackage`, `null` =
  dropped too) isn't pinned; HOME stays. No server change (the server still sends OVERVIEW; the launcher drops it).
  **Superseded by 16d (2026-10-07, `16d-recents.md` "Implementation status"):** `kioskFeatures` is reverted (the
  user's gesture rule) and the recents provider is pinned with the kiosk-block helpers instead.
- **#6**: `bringHomeAfterUpdate(..., pinLockActive)`; `bringHomeAtBoot(bootCount, stored, ...)` = first start of a
  boot (BOOT_COUNT, CE prefs `boot_home`, stored only after Home was started; unknown count = never) and the same
  gate. It runs in `PinLockRuntime.init`'s posted ProcessStart (after the lock's state is loaded, before the
  dispatch), `lock/BootHome.kt`. The update gate reads the stored lock state (`activeOrStored`).
- **Smoke test**: "no BlockedAppActivity after unlock" (3 s), Recents (`KEYCODE_APP_SWITCH`) and, with gesture
  navigation, a slow and a fast swipe-up - each polls a captured `dumpsys activity activities` for
  `BlockedAppActivity` in front (`docs/testing/emulator.md` §5b); Recents/swipe-up only in the kiosk's lock task
  (qa-16-17-code #10).
- Tests: `LockTaskRootTest`, `HomeFrontTest`, `PinLockStateTest` (homeFirst), `SelfUpdatePlanTest` (boot gate, PIN
  lock only), `LockTaskHelpersTest` (`kioskFeatures`, the plan).

Open device checks (emulator first, then the Jelly Star):
- [ ] Check 9: boot (power menu while LOCKED and `adb reboot` while UNLOCKED): our Home is in front ~0.5 s after
  USER_UNLOCKED, `mLockTaskModeTasks` #0 is the Home task (kiosk on), the lock on top; no second Home task
  (`dumpsys activity activities | grep -E "Task\{|Hist"`: one HOME-typed task of ours).
- [ ] The r0 sequence (boot, PIN unlock) ends on our Home, never "App is not available" (smoke test after a
  reboot); a crash restart while LOCKED (`am crash`), then unlock: same.
- [ ] Kiosk on with the fallback path: no visible Home flash longer than one frame before the lock; the lock never
  stuck after unlock.
- [ ] Recents and swipe-up in kiosk with the block on (both nav modes, Pixel and Jelly Star): with OVERVIEW
  dropped, does the swipe-up with HOME on still start quickstep's fallback Recents (BlockedAppActivity)? If so,
  decide per device: HOME off in kiosk (Back only), pin only the recents package (H) or C. Answered on the emulator
  by 16d (yes; H chosen - variant A); the Jelly Star checks are in the 16d doc.
- [ ] Checks 1-3 and 7 on the Jelly Star (is its stock launcher direct-boot-aware; quickstep's package; a HOME with
  priority > 0; does it hand over by itself).
- [ ] Calls-managed + PIN lock only (kiosk off, no allowlist): the stock launcher is no longer Home under the lock
  after boot or an update.

### 16b: the boot cover (D+A+B, QA #7-#11, 2026-10-07)

Behind the server switch `boot_cover` (migration 0045, **default off**, always sent; device page card "Launcher
updates and notifications": "Boot cover ... Test it on this phone first"). A+B stay as they are - a crash reboot sends no
`ACTION_SHUTDOWN`, so the cover stays off and that boot is A+B only.
- **Cover** `lock/BootCoverActivity`: direct-boot-aware (in `DirectBootComponents`), own process `:bootcover` (where
  `Application.onCreate` returns at once - no call path, preferences, enforcement or crash handler of ours), a plain
  `android.app.Activity`, framework theme `BootCoverTheme` (`Theme.Material.NoActionBar`, `windowBackground`
  `kid_ground` #0C0C14), `splash_vibb_breathe.xml` started in `onResume` and repeated (0.7 s pause). No CE storage, no
  native code, no lock task, no services, no AppCompat (`BootCoverManifestTest` scans the source). Own taskAffinity
  `.bootcover`, `singleTask`, out of Recents, `exported="true"` like every HOME.
- **Resolution** (QA #7): manifest HOME filter at priority 0; its own PPA (`MAIN` + `HOME` + `DEFAULT` +
  `com.kidslauncher.mdm.category.BOOT_COVER` - a distinct filter, so it sits next to HomeActivity's, which is never cleared
  or replaced; `PlayInvariantsTest`) added by every apply while wanted (`lock/BootCover.applyPolicy`). In BFU only the
  cover's PPA resolves, so it beats a direct-boot-aware stock launcher without a chooser [device check].
- **Switch = the component's enabled state** (`android:enabled="false"`; a single PPA can't be removed without
  clearing them all): pure `bootCoverEnabled` - wanted (switch on, managed, device owner) and `ACTION_SHUTDOWN` (a
  runtime receiver in the main process, which the anchor keeps alive; the broadcast goes to registered receivers only)
  -> enabled; an apply never enables it, and disables it when not wanted; the hand-over and the crash guard disable it.
- **Hand-over** (QA #8): the cover disables itself (DONT_KILL_APP) once unlocked (`USER_UNLOCKED` receiver or the
  check at resume) and shown >= 1 s (`coverHandOverDelayMs`), then finishes as soon as the disable reads back (like
  FallbackHome - AMS alone removes it only with the PACKAGE_CHANGED broadcast, deferred up to 10 s after boot,
  qa-16b-code #1); the system resolves HOME again - to HomeActivity, HOME-typed. Our main process does the same at its first start after the unlock
  (`BootCover.init`, in `PinLockRuntime.init` before Home is brought to the front), so A's typed HOME start resolves
  to HomeActivity only (`HomeFrontTest`).
- **Crash guard** (QA #10, qa-16b-code #2): `lock/BootCoverGuard.install` runs first in the cover process's
  `Application.onCreate` (its only work there), so every crash in that process counts, in one device-protected
  AtomicFile (`CoverRecord`: boot, crashes, sticky `tripped`, shown/handed-over times; unreadable = tripped). The
  2nd crash in a boot disables the component and trips the guard for good: the shutdown no longer arms it until the
  switch goes off (which deletes the record) and on again. A native crash or an ANR isn't counted.
- **Status** (qa-16b-code #5): capability `boot_cover_v1`, `boot_cover` {wanted, tripped, last armed/shown/hand-over
  and by whom}; the card warns about an older launcher, a tripped guard, or an arm not shown at the next start.
  After `ACTION_SHUTDOWN` the arm is written synchronously and `HomeFront` starts no Home (qa-16b-code #3).
- Tests: `BootCoverPlanTest`, `BootCoverManifestTest`, `HomeFrontTest`, `PlayInvariantsTest`,
  `PolicyResponseCompatTest`; server `policy_json_keys_snapshot`, `step11`.

Open device checks for 16b (emulator first, then the Jelly Star; switch on, then shut down cleanly - from the power
menu or `adb shell svc power reboot`: `adb reboot` goes through init's `sys.powerctl`, not ShutdownThread, so it sends
no `ACTION_SHUTDOWN` and the cover isn't armed; `ACTION_SHUTDOWN` is protected, `am broadcast` can't fake it):
- [ ] BFU resolution: after a clean restart the cover (not Pixel's launcher, not the Jelly Star's) is Home from the
  end of the boot animation, with no chooser (`dumpsys activity activities`, `cmd package resolve-activity` for HOME).
- [ ] The self-disable at the unlock hands over to HomeActivity HOME-typed (one HOME task of ours), kiosk on: Home
  roots lock task, the PIN lock on top; no flash of the stock launcher. Time it with a phone whose A starts no Home
  (calls-only managed): the mark goes ~1 s after the unlock, not ~11 s.
- [ ] The device page shows the last start (shown, handed over by whom); an older launcher shows "update the
  launcher".
- [ ] The re-enable written at `ACTION_SHUTDOWN` persists over the restart (`dumpsys package` component state) - a
  crash/forced reboot leaves it off (A+B).
- [ ] The crash guard: a cover that crashes twice in a boot disables itself, the next HOME is another one, the next
  shutdown doesn't arm it, the card warns; switch off and on re-arms it.
- [ ] The look: night background, the breathing mark centred, status/navigation bars night, no white frame.

## 16c - Home never shows content while locked (emulator run 2026-10-07)

Live (`adb reboot`, boot cover off, loaded emulator):
- 05:35:44.8: the system starts Nexus as HOME in BFU.
- 05:35:53.6: USER_UNLOCKED.
- 05:35:58.4: the system starts our HomeActivity.
- 05:36:10.9: our `PinLock DISABLED -> LOCKED on ProcessStart(homeFirst=true)`, 12 s of process start.
- 05:36:15.2: our typed HOME start.
- 05:36:16.2: PinLockActivity start; "Displayed ... PinLockActivity +21s".

**Home showed contacts and apps unlocked for about 20 s.** The A+B order (Home first, then the lock) widens this
window.

Decision:
1. HomeActivity renders **no content** (no contacts, apps, call card or settings tile, and no touch targets) unless
   `PinLockRuntime` says UNLOCKED or the PIN lock is inactive (no kid PIN, Android credential, unmanaged).
   - This holds from `onCreate`, before the runtime has decided: an unknown state counts as locked whenever the
     stored lock state or the cached policy says a kid PIN is set.
   - Instead, Home shows the night ground with the breathing Vibb mark (`splash_vibb_breathe.xml`), centred. This is
     also the user's wished-for splash on every boot, cover or not.
   - The content is inflated and bound only on the UNLOCKED edge.
   - The kiosk/lock-task logic is unchanged.
2. Find and remove the delay between `ProcessStart` LOCKED and the lock in front (4-5 s here): show the lock in the
   same pass that brings Home forward, and keep anything slow off that path.
3. Tests:
   - a pure `homeShowsContent(lockMode, pinActive, known)`;
   - Home's touchables are gone while locked;
   - `smoke-test.sh` after reboot: no Home content node (uiautomator) before the PIN unlock.

### 16c implementation status (2026-10-07)

- **Gate**: pure `homeShowsContent(lockMode, pinActive, known)` (`lock/HomeGate.kt`). `PinLockRuntime.decide` sets the
  process's mode first thing in `Application.initUnlocked` (before `LauncherPreferences`, before any activity);
  undecided, `pinSetStored` asks the stored lock state, the stored PIN and the cached policy's `kid_lock` (unreadable =
  set).
- **Home**: `onCreate` builds nothing of the content. Locked, it is `ui/home/NightGround` (`activity_home_night.xml`:
  `kid_ground`, `splash_vibb_breathe` centred, breathing while resumed, light bar icons). The content is inflated on
  the first UNLOCKED edge (`showContent`, from the mode listener or a resume), taken off the window again on the
  LOCKED edge (`showNight`, which also closes Home's contact sheet); every render checks `contentShown`, the swipes
  run only with content. Lock task unchanged (`reconcileKioskMode` also never stops it while undecided with a PIN).
- **What was slow (point 2, from the code - the run has no trace)**: (1) the system had already resumed our Home
  (its launch is queued ahead of `init`'s posted ProcessStart), so the boot's typed HOME start gave no new resume and
  the lock waited for the 1 s fallback; (2) after the LOCKED dispatch the main thread ran `LockTaskChrome.refresh`
  twice, each waiting on the monitor for an `apply()` pass (initRest's time-rule re-check and the anchor's first sync
  both start one) and each ~8 DPM calls that persist the policy file, plus PackageManager queries for the kiosk-off
  helpers without a plan; (3) Home's resume rendered everything (a TelephonyManager call per contact, RoleManager,
  locale, wallpaper) and decoded the cached policy, BootHome decoded it again and committed synchronously, and each
  time-rule re-check decoded it 3-5 times.
- **Fixes**: early `decide`; `bootHomeAction` = MARK_DONE when Home is already up, so ProcessStart shows the lock at
  once; the runtime's chrome once per transition (on its own thread, `lock-chrome` - the LOCKED edge since QA #1
  on the main thread, below), and the lock enters lock task when a later pass lands (kiosk off, `chromeListener`); `applyFallback` reuses the prefetched helpers; Home's locked resume is
  lock task, fence, the time-rule redirect from the stored reason, the lock - no render, no decode; BootHome reads the
  policy only when neither the kiosk nor the lock decides, boot count with `apply()`; `cachedPolicy()` keeps one
  decode per cached string. The rest of `initRest` still runs before any activity (the platform waits for
  `Application.onCreate`); the night ground covers it.
- Tests: `HomeGateTest` (gate, boot action, the night layout has no touch target, Home's content only from the
  UNLOCKED edge, the locked resume, `decide` before the rest); `smoke-test.sh` `REBOOT=1` greps captured UI dumps
  for Home's content until the lock is in front.
- **QA fixes (qa-16c-code.md, 2026-10-07)**: #1 the LOCKED edge's chrome pass runs on the main thread again, right
  after the lock's start (`refreshChromeNow`), so the lock resumes only once it is in place. The pass writes the
  status bar and `DISALLOW_CREATE_WINDOWS` before lock task (`chromeWriteOrder`) and does no PackageManager work:
  `applyPlan` resolves the helpers before the monitor, and the main-thread pass takes cached ones only. A missing
  set pins our package alone, and a chrome-thread pass adds the helpers. Unlock and DISABLED passes stay on the
  chrome thread. The cost on the main thread is one pass of DPM calls plus at most one `apply()` pass's DPM calls
  (the monitor), and it falls between the lock's start and its resume. **Remaining window**: the lock's starting
  window is up during that pass. Before 16c the shade was open during that pass too. #2 Home also closes its
  long-press menu and rename dialog on the LOCKED edge. #3 an unreadable lock state starts LOCKED with the lock shown
  (`lockActiveAtStart`); `init` and the lock's own reads survive the store. #4 `REBOOT=1` samples only after
  `boot_id` changed and FAILs without a successful dump.

Open device checks (emulator, then the Jelly Star):
- [ ] `REBOOT=1` (`adb reboot`) and a power-menu restart: no content in any dump, the night ground until the lock;
  logcat from `Process start: LOCKED` to `Displayed ... PinLockActivity` well under 1 s on an idle emulator.
- [ ] Kiosk off: the lock is in lock task when it resumes at a LOCKED process start and at a screen-off.
- [ ] Pull the shade and swipe to Overview right as the lock comes up at a LOCKED process start (`adb reboot`, and
  `am crash` while LOCKED), kiosk on and off: neither opens over the lock (qa-16c-code #1).
- [ ] Unlock: the content comes with the lock leaving; screen-off over Home: the night ground before the lock.
- [ ] A time rule at boot (the time-rule screen under the PIN lock) and a call over the lock: unchanged.

## 16e - the breathing logo for 3 s at boot (user, 2026-10-07)

On a boot, the locked Home's breathing Vibb mark (16c) shows only for a blink before the PIN lock, and the user wants
about 3 s. Decision:
1. **Only at the first process start after a boot** (boot count changed): the PIN lock's first showing waits until
   the mark has been on screen for 3 s from Home's first frame. On a crash restart without a reboot, nothing waits.
   Screen-off locks, remote locks and every later lock show at once, as today.
2. **Nothing else waits:**
   - the LOCKED chrome (status bar blocked, lock-task features, the 16d heal) applies at process start, before or
     with the mark;
   - Home shows no content and takes no touches (16c);
   - our call screen, a Telecom or emergency call, a ringing system alarm and a VoIP ring (17/17b) skip the wait at
     once;
   - an unreadable state shows the lock immediately (16c #3).
3. **The boot cover (16b):** its minimum before handing over becomes 3 s from its first frame. The later Home mark
   counts toward the same 3 s, measured from the first of the two frames, so the user doesn't get 3 s twice.
4. **Tests:** the pure wait decision (boot vs restart, exemptions, the timing source). A smoke-test REBOOT=1 check that
   the lock appears within about 5 s and that nothing but the mark is visible before it.

### 16e implementation status (2026-10-07)

Code with unit tests. Commit `1ee2298e`, the qa-16e-code fix round (`qa-16e-code.md`, all five fixed), the
emulator fix below (`511bc2cb`) and its QA round (`qa-16e-fix2.md`, all fixed).

**Emulator fix (2026-10-09, "sometimes the right length, sometimes not")**: one boot's logcat - our typed HOME start
29.186, `Boot mark: the lock waits 3000 ms` 29.591, the system's own HOME start (no `pkg=`) 31.202, `Boot mark over:
Home paused` 31.383, `Displayed ...HomeActivity +2s216ms` 31.428, the lock 31.50: the mark was up ~0.1 s. Two causes,
both fixed: (1) the 3 s ran from the first lock request, before Home had drawn - they now count from the mark's first
drawn frame (the night ground's `OnDrawListener` -> `PinLockRuntime.onHomeMarkDrawn`; new state
`BootMarkState.Waiting`: held for that frame at most 3 s from the wait's start, then `Holding` until 3 s after it,
`bootMarkDrawn`); (2) the system's HOME start re-delivered the intent, which pauses and resumes Home at once - a pause
now ends the wait only if Home isn't resumed again within `HOME_PAUSE_GRACE_MS` (300 ms, `homePauseEndsWait`); a stop
or lost focus still ends it at once. Every end logs `Boot mark over: <why> - the mark on screen N ms`
(`bootMarkOnScreenMs`). Not re-run on the emulator yet.

**qa-16e-fix2 (2026-10-09)**: waiting up to 3 s for Home's first frame held the lock back while neither the mark nor
the lock was on screen (Home's theme sets `windowDisablePreview`, so the old front - the stock launcher or
FallbackHome - stayed up), and a Home never focused couldn't report covers that don't pause it. Now the mark counts as
shown only from Home's first window focus after its locked resume with the night ground drawn (pure `homeMarkShown`;
`onHomeMarkShown` from the focus gain, the ground's first frame and the locked resume; on API 34 focus goes only to a
drawn, shown window), the 3 s start there (`bootMarkShown`), and `Waiting` lasts at most the pre-16e `LOCK_FALLBACK_MS`
(1 s) from the wait's start - no focus by then means the lock, so a slow emulator safely shows no mark. The lock
comes at most 1 s + 3 s after the wait began (an end-to-end test over every path). "Up" is the pure `homeMarkUp`
(resumed locked, not stopped or unfocused since, not paused past the grace). The smoke test sorts on the end's reason
(`bootMarkOverWhy`): it FAILs only a Home pause, stop or focus loss below 2500 ms that no UI dump explains (and a
`the mark's 3 s are up` below 2500 ms, which is a bug) and SKIPs the safe ends (never shown, no wait, exemptions,
`AT_ONCE` asks, the lock already up). **Blind spot, documented**: a pause that takes no focus (a translucent activity,
or Recents over a Home not yet focused) goes unseen for up to `HOME_PAUSE_GRACE_MS` (300 ms); once Home has focus a
focusable window over it takes the focus and a non-focusable top activity leaves none - both a focus loss, at once -
and before the first focus the 1 s `Waiting` deadline bounds it. If the emulator still logs `Home paused` after the
system's HOME start, log the pause-to-resume gap before raising the grace.
- **Pure decision** (`lock/BootMarkPlan.kt`, `BootMarkPlanTest`): `bootMarkDue(bootCount, stored, lockActive,
  readable, sinceBoot)` - the first process start of a boot: `Settings.Global.BOOT_COUNT` known and not the one stored
  by the last start (CE `pin_lock_state` key `boot_mark_boot_count`, swapped in `PinLockRuntime.init` with `commit()`),
  the process started within `BOOT_MARK_WINDOW_MS` (5 min) of the boot, the lock active and its state readable
  (unreadable: no wait, qa-16c-code #3). A crash restart in the same boot, the first start of a new build after its
  update and a restart after a lost write never wait. `bootMarkStep(state, ask, markUp, now, coverFrame, homeShown,
  exempt)`: `Due` -> `Waiting(deadline)` -> `Holding(until)` -> `Over`; only the first showing waits, the 3 s count
  from the mark's start (Home's first focus with the night ground drawn, or the cover's first frame, the earlier), an
  end once fixed never moves later, and a first ask after the window doesn't wait (a first screen-on hours later).
- **Only while Home's mark is up** (qa-16e-code #1, pure `homeMarkUp`): set by Home's locked resume (`onHomeMarkUp`,
  right before its `show`), cleared by Home's `onStop` and `onWindowFocusChanged(false)` at once, by `onPause` only
  after `HOME_PAUSE_GRACE_MS` without a resume (not for a recreation). A boot ask without it shows the lock at once, as before
  16e: Home's start failed or threw, Home never resumed (the stock launcher or FallbackHome in front), or Home was
  covered. When Home is started first (`showLockLater`), `bootMarkClock` only begins the wait at the process start; the
  1 s fallback then holds only with Home's mark up - never later than before 16e when Home doesn't come.
- **Who waits** (`LockAsk`): `BOOT` asks - the process start (`showLock`), Home's locked resume, a screen-on backstop
  and the re-front loop. Every other ask (`AT_ONCE`: screen-off, remote lock, calls ending, VoIP ring/end, the
  time-rule screen, the update's `showIfLocked`) shows at once and ends the wait; so does a VoIP wake.
- **Nothing else waits**: the wait lives only in `PinLockRuntime.show`, which starts nothing while held. The mode is
  LOCKED at the process start as before; while the mark may hold (`bootMark != Over`) the LOCKED edge's chrome pass
  (`refreshChromeNow`, status bar and `DISALLOW_CREATE_WINDOWS` first) runs **before** `show`, so it never waits on the
  hold's reads (qa-16e-code #3); otherwise the order is 16c's (the lock's start, then the chrome). The hold reads
  `markUp` and the clock first and the exemptions (Telecom, Audio, AlarmManager) last, only when it would hold; the
  cover's frame is read once on an IO thread at `init` (not read yet = 3 s from the ask). The camera lock and the 16d
  heals are unchanged; Home stays the night ground (16c); the kiosk's Home still roots lock task at its resume.
- **Exemptions, at once** (`bootMarkExempt`): our call, a Telecom call (emergency included), the emergency flow, a
  ringing system alarm, a VoIP ring or call; a call starting (`callsListener`), the re-front loop yielding to any of
  them, the lock coming up for any reason and anything over Home's mark (pause, stop, lost focus: an app, Recents, the
  assistant, the power menu, a translucent activity - qa-16e-code #2) end the wait.
- **Never without a lock after 1 s + 3 s**: entering `Waiting` posts a backstop for its deadline (`LOCK_FALLBACK_MS`
  after the wait began - Home's mark not shown by then means the lock, `Home's mark never shown`), `Holding` one for
  `until`; it, like the other early ends, runs `bootMarkEnd` - our call: the lock now (the call screen comes over it,
  as at a process start in a call); otherwise the re-front check (yields to the system dialer, emergency, alarm or
  VoIP; screen off: the screen-on shows it; else the lock). So the old front is never held over for more than the
  pre-16e 1 s, and the lock comes 3 s after the mark's start. Logcat: `Boot mark: the lock waits for Home's mark to
  show, at most N ms`, `Boot mark: the lock waits N ms`, `Boot mark over: <why> - the mark on screen N ms`.
- **Boot cover**: `COVER_MIN_SHOWN_MS = BOOT_MARK_MS` (3 s from its first frame). The cover's record keeps its first
  frame of the boot (`shown_boot`, `shown_elapsed` - elapsed realtime; older records read without them, older readers
  ignore them; a recreated cover keeps the boot's first frame), and `until` is 3 s from the earlier of that frame and
  the ask - so a cover that had its 3 s means no wait, not 3 s twice.
- **Smoke test** (`REBOOT=1`, `docs/testing/emulator.md` §5b): on the same captured UI dumps, after the first one with
  the night ground no dump may hold another package's window (`boot-not-mark-<n>.xml`), and the lock must be in front
  within about 5 s of the mark (a lower bound; FAIL only on proof, SKIP when no dump caught the mark -
  qa-16e-code #5); the mark's time on screen from the captured `logcat -d -s PinLock` (`Boot mark over: <why> - the
  mark on screen N ms`): FAIL only below 2500 ms for an unexplained Home pause, stop or focus loss (or the 3 s ending
  short), SKIP for the safe ends (qa-16e-fix2 #4).

Open device checks (emulator, then the Jelly Star):
- [ ] `REBOOT=1` (`adb reboot`) several times, kiosk on and off: the night ground ~3 s, then the lock; the three 16e
  checks PASS or SKIP a safe end (`Boot mark over: the mark's 3 s are up - the mark on screen ~3000 ms`, also when the
  system's own HOME start comes during the wait; on a slow emulator `Home's mark never shown` after 1 s); the 16d
  shade probe still PASSes.
- [ ] `am crash` while LOCKED after the boot: no `Boot mark` line, the lock at once. The update to a new build (debug:
  `adb install -r` then the self-update path): no wait.
- [ ] Screen-off during the 3 s (power button): the lock at once. A call to the emulator (`gsm call`) during the 3 s:
  our call screen at once over the lock. An alarm set for the boot minute: the alarm, then the lock.
- [ ] Kiosk off, during the 3 s: swipe up to Recents, hold for the assistant, open the power menu - each
  `Boot mark over: Home paused`/`Home lost focus`, the lock at once.
- [ ] With the boot cover on (power-menu restart): the cover ~3 s from its first frame; Home's mark only for what is
  left of them (none when the cover handed over after its 3 s).
