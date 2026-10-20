# Code QA - launcher round 7, part B (r7 §2 force-stop protection, r7 §3 the notice wake)

- Commits: `9a7017a0..65964c75` on branch `l7-b`:
  - `8ea5b8f0` (§2): catalog apps protected from force-stop with `setUserControlDisabledPackages`, lifted by the
    override and the pause; `installed_apps[].battery_exempt` with the shared fixture; the PWA's "Background:" line.
  - `65964c75` (§3): the per-phone switch "Wake the screen for a notification" (migration 0084,
    `launcher_ui.lock_wake_on_notification`, the `#lock-wake` cell), and the launcher's notice wake.
- Read:
  - root, launcher and server `CLAUDE.md` (Hardening and the override/pause rule, the PIN lock, Lock flash §7, the
    glance page, VoIP over the lock, Windows that lock again, Fail closed, migrations, the status report);
  - `docs/notes/server/pwa.md`;
  - `r7-launcher-small-items.md` §2 and §3, with both Implementation status sections and the 3 deviations;
  - `qa-r7-launcher-design.md` r7-6..r7-15, `32-lock-flash.md` §7, `qa-32-design.md` (`lockNow`),
    `10-lock-and-call-ui.md` §7, `19b-doze-exemption.md`;
  - the full diff.
- AOSP checked for `setUserControlDisabledPackages`' side effects: `ProtectedPackages`, `SuspendPackageHelper`,
  `PackageManagerService`, `DeletePackageHelper` and `InstallPackageHelper`, on `android14-release` (the launcher's
  minSdk 34). `SuspendPackageHelper` was also checked on `android15-release`, `android16-release` and `main`.
- Ran:
  - launcher `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1449 tests, 0 failures, 0 errors, 0
    skipped**;
  - server: full `cargo test` **1099 passed, 0 failed**; `cargo fmt --check` clean; `cargo clippy --all-targets` shows
    the 13 warnings that were already there and no new ones;
  - mutations: 16 one-line mutations in the launcher (against the brief's targeted tests) and 5 in the server
    (against the targeted modules), each reverted. `git status` is clean.

## Findings

**1 (High) - a protected package can't be suspended, hidden or uninstalled by us either: §2 breaks the time rules,
the camera lock, the update fence and uncheck/uninstall for every catalog app it protects.**
`setUserControlDisabledPackages` doesn't only grey out Force stop and Clear storage:
- DPMS hands the list to `ProtectedPackages.setOwnerProtectedPackages`;
- `isPackageStateProtected(user, pkg)` is then true for those packages;
- the platform checks that in more places than Settings.

What the platform refuses (AOSP `android14-release`):
- **Suspension.** `SuspendPackageHelper.canSuspendPackageForUser` refuses with `"Cannot suspend package …:
  protected package"`. It has **no exception for the device owner**: `isCallerOwner` only skips the block-uninstall
  check. The same check is in `android15-release`, `android16-release` and `main`. Unsuspending isn't checked.
- **Hiding.** `PackageManagerService.setApplicationHiddenSettingAsUser`: `"Not hiding protected package"`, returns
  `false`.
- **Deletion.** `DeletePackageHelper.deletePackageVersionedInternal` (`isPackageDataProtected`): `"Attempted to
  delete protected package"`, `DELETE_FAILED_INTERNAL_ERROR`.
- Installs and updates are not affected (`InstallPackageHelper` has no such check).

What this does on the phone:
- **Time rules, the daily budget, a not-allowed app.** A catalog app that is protected while its rule starts (for
  example a game, or Element X, at bedtime) is refused by `setPackagesSuspended` in `apply()`'s loop
  (`AppEnforcer.kt:418`). It stays usable. That is fail-open on a core restriction, and only a "Platform refused to
  suspend" log line shows it. One exception: an app suspended *before* it entered the list stays suspended (the
  platform keeps the existing suspension).
- **The camera lock** (`CameraLock.kt:140`, every screen-off) can't suspend a protected catalog app that is a camera
  target.
- **The update fence** (`UpdateFence.kt:430`) can't hold a protected app while it updates.
- **Order inside `apply()`.** The suspend/hide loop (`AppEnforcer.kt:384-440`) runs *before* the new block
  (`:518`). So an app that leaves the set (unchecked, or newly in `plan.hide`) is refused hiding in that pass. It is
  hidden only at the *next* apply. Until then it stays visible and usable.
- **Uninstall.** `packagesToUninstall` (`MdmSyncWorker.kt:156`) runs before the sync's `apply()` (`:170`). An uninstall
  of a still-protected app is refused.

The pure tests can't see any of this. §2.3's device checks only try force-stop, so they would pass.

Fix (needs the architect, then the user):
- **(a)** Keep the protection, but never on a package any restriction targets now:
  - the set minus `plan.suspend`, minus `CameraLock`'s targets and minus the fence-held packages;
  - the list set **before** the suspend/hide loop, shrinking first;
  - every other suspend or delete site (`CameraLock` lock step, `UpdateFence` hold, `uninstallSilently`) removes
    its packages from the list first (one small helper, source-pinned like the existing check).
  - The camera lock at every screen-off makes this churn, which argues for (b).
- **(b)** Drop §2.1.1's protection and ship only §2.1.2 (the battery line), until (a) is designed and device-checked.

Either way, add to §2.3: a protected catalog app during a bedtime rule (`dumpsys package <pkg> | grep -i suspend`),
and uncheck + uninstall.

**2 (Medium) - the 15 s `lockNow()` can turn the screen off on someone using the lock.** `noticeWakeTouched` comes
only from `PinLockActivity.onUserInteraction`. That callback runs for touches and key events on the activity's own
window, and for nothing else. Two cases miss it:
- **TalkBack.** Touch exploration sends hover events (`dispatchGenericMotionEvent`, no `onUserInteraction`). A
  double-tap clicks through `performAccessibilityAction`. A TalkBack user typing the PIN after a notice wake gets the
  screen turned off at 15 s. TalkBack is a system service and always passes `permittedAccessibility`.
- **The lock's own dialogs** (Foreldrekode, the Nødsamtale confirm): a `Dialog` window is its own `Window.Callback`
  and never calls the activity's `onUserInteraction`. Dialogs survive a screen-off (`openDialogs` is only cleared on
  dismiss). So a parent typing the parent code into a dialog that was already open when the notice woke the screen is
  cut off at 15 s.

The other cases are covered:
- an ordinary touch, a hardware key or a volume key: `onUserInteraction`;
- our call screen, VoIP, the emergency flow and an alarm: `busy`, `lockResumed`;
- an Android credential: the lock isn't LOCKED;
- the process dying: the `Handler` timer dies with it;
- `lockNow()` throwing: caught.

Fix:
- two more inputs to `noticeWakeOffDue`: `dialogOpen` (from `PinLockActivity`'s `openDialogs`) and
  `touchExploration` (`AccessibilityManager.isTouchExplorationEnabled`, or don't arm at all then);
- two rows in `LockWakeTest`.

**The deviation itself (a timer + `DevicePolicyManager.lockNow()`) is acceptable once 2 is fixed:**
- It is the only public way a device owner can turn the screen off.
- `setMaximumTimeToLock` is global: it would shorten the kid's own timeout everywhere.
- A wake lock can't shorten the screen's time on.
- With the lock LOCKED there is no Android credential and the keyguard is disabled. So `lockNow`'s
  `STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW` (qa-32-design) changes nothing. The resulting screen-off is an ordinary
  lock-up (finding 4).

**3 (Low) - the 15 s timer is armed even when the wake didn't light the screen.**
`noticeCame` arms the timer and counts the wake before `show(app, wake = WakeKind.NOTICE)`, and ignores `show`'s
result. If the start was refused, or `turnScreenOn` wasn't honoured, the screen stays dark and the timer runs on.
Then if the kid presses power within the 15 s, the screen goes off under them once the timer's 15 s are up.
Fix: arm only when `show` returned `true`, and end the timer at a `ScreenOn` that comes more than about 1 s after
the wake (the kid's own power press).

**4 (Low) - §3.4's device check expects the wrong lock-up.** It says "leaves our lock on top: the screen-off's
lock-up is NONE". But `show()` clears `lockOnTop` (`START` without `sameLockUp`, `LockUpPlan.kt:93`), and nothing
sets it back while the screen is on. So the `lockNow()` screen-off, like any screen-off after a notice wake, runs
`HOME_THEN_LOCK` (or `LOCK` under the cover). That is the normal lock-up §3.1.3 accepts. Fix the text so the tester
expects Home brought under the lock while dark, and the lock-up stamp's 2 s gate.

**5 (Low) - `glanceWakes`' `!suspended` is unguarded (mutation j survives).** `glanceRows` already drops suspended
notices, so the test's suspended case fails for the row reason alone. Fix: one assertion with a new row in `after`
and `suspended = true` -> `false`.

**6 (Nit)** A save of the wake switch logs `lock_wake_changed` even when the value didn't change (the other lock
switches do the same). `NoticeProximity`'s timeout runnable isn't removed after an early answer; that is harmless
(`done`).

## The checks, with the evidence

1. **The standing rule and fail-closed (§2): holds as coded; finding 1 is a platform effect.**
   - `userControlDisabledPackages` is empty when `!managed || overrideActive`. `overrideActive` is
     `OfflineOverride.isActive() || RestrictionsPause.isActive()` (`AppEnforcer.kt:292`). Mutation a is caught.
   - The protection comes back when a window ends: `WindowRelock`'s relock runs `AppEnforcer.apply`
     (`WindowRelock.kt:567`); the pause's `reapply` and the override's `activate` run it at the start.
   - The block is in its own try/catch after `applyHardening`, the VPN, sideloading and `PinLockRuntime.configure`.
     `NotificationRuleRuntime.refresh` follows in its own try. Mutation c is caught.
   - The set excludes our own packages (mutation b is caught), `plan.hide` and a Play twin (installer test).
   - `installerOf` on a package with no install source returns `null`, so the package is not protected.
   - `getUserControlDisabledPackages` is compared first, so the list is set only when it changed.
   - `protectedCatalogApps` keeps the set during a window, for the report. The report runs after `apply` in the sync
     (`:170` < `:213`).
2. **Design 32 §7 (§3): holds.**
   - `PinLockManifestTest` pins the turn-screen-on set to exactly `InCallActivity` and `VoipWakeActivity`.
   - The notice start is the one `show(app, wake = WakeKind.NOTICE)`. The wake window starts only inside `show`,
     after the lock's start.
   - `NoticeBusy` covers our call, Telecom, VoIP, the emergency flow, an alarm, the boot mark and the first 2 minutes.
   - The 2 s gate is stamped in `lockUp` (mutations f and g are caught).
   - Only a VOIP wake calls `voipWakePaused` (`wakeKindOf`; mutation l is caught).
   - The deviation: see finding 2.
3. **Privacy: holds.**
   - `noticeCame()` takes no argument. `GlanceAlertFacts` holds the importance, `matchesInterruptionFilter`, the
     `FLAG_ONLY_ALERT_ONCE` bit and `has(key)`: no text.
   - `LockGlanceGuardTest`'s additions are identifiers only.
   - The proximity read needs no permission. Its partial wake lock (`WAKE_LOCK` is declared) has a 800 ms timeout and
     is released in `finish`, and the listener is unregistered there.
4. **The triggers: hold.**
   - The five conditions:
     - suspended per the ranking (redundant, finding 5);
     - importance >= 3;
     - DND (mutation h is caught);
     - only-alert-once on a known key (mutation p is caught);
     - the rows gain a conversation.
   - Only `posted` triggers. The gap and the hourly cap are in memory (mutations d and e are caught).
   - The missed call: `step.alert` after a post that worked, through `main` (mutation m is caught). The boot re-send
     is `alert = false` through `KEY_ALERTED_UP_TO`. Our own notice never goes through `GlanceReader`
     (`glanceCandidate` refuses our package).
5. **Contracts: hold.**
   - `policy_json_keys_snapshot` pins the key and `false`. `PolicyResponseCompatTest` covers missing = off and the
     cache round trip (mutation k and server b are caught).
   - `installed_apps_battery.json` is byte-identical, compared on both sides, and in the root `CLAUDE.md` and both CI
     path lists.
   - Migration 0084 is a new file in `migrations_73_to_84_in_order`. `cap_installed_apps` keeps the field through
     `..app` (server a is caught).
   - "Drops garbage": a non-bool `battery_exempt` fails the entry's deserialization, as `installer`'s wrong type
     already did. This is the existing shape, not new.
6. **The PWA: holds.**
   - `#lock-wake` is its own cell with `data-autosave` and a `<noscript>` Save. The form returns to `#lock-wake`
     (`lock_forms_return_to_the_page`; server e is caught). The phone is nudged.
   - The log line has its label (server c is caught). An unknown phone is a 404 with nothing written.
   - The Apps page's `data-background` line is static server HTML (server d is caught); no form changed, so nothing
     jumps.
7. **Earlier design-QA findings:**
   - r7-6..r7-9 (§2), r7-10..r7-15 (§3): implemented as revised.
   - r7-13's wake lock became deviation 1 (finding 2).

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `userControlDisabledPackages` ignores `overrideActive` | caught - `UserControlPlanTest` lift |
| b | `protectedCatalogPackages` without `pkg !in own` | caught - `UserControlPlanTest` set |
| c | an extra `setUserControlDisabledPackages` outside the try, before it | caught - `UserControlPlanTest` source check |
| d | `NoticeBusy.any` without `alarm` | caught - `LockWakeTest` busy |
| e | the 30 s gap off (`>= 0`) | caught - `LockWakeTest` gap/cap |
| e2 | the hourly cap `<=` | caught - `LockWakeTest` gap/cap |
| f | the 2 s lock-up gate off | caught - `LockWakeTest` |
| g | the `lockUpAtElapsed` stamp removed from `lockUp` | caught - `LockWakeTest` glue |
| h | `glanceAlerts` without `passesFilter` (DND) | caught - `GlanceWakeTest` |
| j | `glanceWakes` without `!suspended` | **NOT caught** (finding 5) |
| i | `proximityNear` with `<=` | caught - `LockWakeTest` proximity |
| k | `LauncherUi.lockWakeOnNotification = true` | caught - `PolicyResponseCompatTest` |
| m | `MissedCallNotifier` wakes without `step.alert` | caught - `LockWakeTest` missed call |
| l | a NOTICE wake also calls `voipWakePaused` | caught - `PinLockManifestTest` |
| n | `noticeWakeOffDue` ignores `touched` | caught - `LockWakeTest` 15 s |
| p | only-alert-once ignores `known` | caught - `GlanceWakeTest` |
| server a | `cap_installed_apps` sets `battery_exempt: None` | caught - `intake_limits::the_battery_state_of_a_protected_app_is_kept` |
| server b | the policy sends `lock_wake_on_notification: true` | caught - `policy_json_keys_snapshot`, `lock_glance` |
| server c | the log kind renamed | caught - `lock_glance::the_wake_switch_…` |
| server d | `background_line`'s two texts swapped | caught - `intake_limits` |
| server e | `save_wake` returns to `#lock-screen` | caught - `device_pages::lock_forms_return_to_the_page`, `lock_glance` |

## Verdict

**Changes required.**
- §2.1.1 as built: finding 1 is High. The protection silently disables suspension, hiding and uninstall for exactly
  the apps it protects. That turns time rules and the camera lock fail-open for catalog apps. It needs an
  architect's decision ((a) narrowed and ordered, or (b) dropped, ship only the battery line) before the merge.
- §2.1.2 (the battery line) and the server side are fine.
- §3 is approved with fixes: finding 2 before the merge (two inputs and two test rows); 3-5 are small and can go with
  it or to the backlog. Deviation 1 (`lockNow`) is acceptable with finding 2 fixed.
