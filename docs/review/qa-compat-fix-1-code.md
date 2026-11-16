# Code QA: compat-fix-1 (Android 14-17: Settings off the lists, the media button's hold, lint baselines)

Branch `compat-fix-1` (58628fc40; 5c6a2e497, e2d6f91bc, d632ca419, 58628fc40) against its merge base with main
`9c805614a`. Background: `docs/testing/2026-11-16-android-14-17.md`, `docs/notes/launcher/hardening.md` (the new
section), `docs/testing/emulator.md` §6as, `launcher/CLAUDE.md`, `music/CLAUDE.md`.

## Setup

- Read-only, as briefed: no Gradle, no emulator. I read `git diff main...compat-fix-1` in full, plus the code around it:
  `AppFilter`, `CameraLock.suspendedForLists`, `computeEnforcementPlan` (what `suspend`/`hide` hold, in a lock and in a
  window), `AppEnforcer.apply`'s loop and its applied-listeners, `HomeActivity`'s `render`/`refreshApps`/`gate` and
  `reconcileKioskMode`, `systemPageIntent` and its six callers, every direct `Settings.ACTION_*` start in the launcher,
  `BootClock.bootCount`, `BootCoverPlan.bootCoverEnabled` and step M in `home-and-photos.md`, the music app's manifest,
  `PlaybackService.onDestroy` and its notification provider, and both lint baselines.
- The builder's scratchpad (`compat/`) has the 34/37.0/37.2 logs of the morning's check, not the `adb emu kill` hang
  itself; the hang is judged from §6as, step M and the code.

## What holds

- **The lists follow the plan, and nothing new opens Settings.** The branch only removes things: `AppFilter` (Home, the
  drawer and its search - the search filters the already filtered list -, Kid Settings' hidden apps) and
  `dialogTarget` gain one more exclusion. On 34-36 the visible effect is nil: everything in `plan.suspend`/`plan.hide`
  that Android does suspend or hide was already off (`isMdmSuspended`, a hidden package doesn't enumerate). The
  camera the PIN lock holds is suspended outside the plan, so `suspendedForLists` still keeps it on Home. The kiosk is
  on whenever apps are managed (`kioskDesired` is no longer the parent's, `server/handlers/devices.rs`), the server's
  `block_activity_start` defaults to 1, so a Settings start under the kiosk is BlockedAppActivity on 17 (§6as step 4).
- **The plan's freshness while the process lives.** `lastPlanSuspend`/`lastPlanHide` are written before the
  platform calls in every `apply` that gets that far. The pause and its end, a policy change and Flymodus's put-back
  all go through `apply`, and `HomeActivity.planListener` refilters when the sets change (data-class equality, so one
  pass per real change). A locked Home skips `refreshApps`, but `onResume` -> `render()` refilters at the unlock.
- **The refusal memory is narrow and bounded.** Only a *restricting* call that failed *and* whose package holds
  `SYSTEM_EXEMPT_FROM_SUSPENSION` is recorded (`refused` returns false otherwise, and the caller logs as before); a
  release is always made (`restrictCallDue`); an unreadable op or boot count (-1) never skips. The set can only hold
  exempt packages (a handful of system-uid ones) and is ignored at the next boot (`BOOT_COUNT` moves on every boot,
  so `Build.FINGERPRINT` is a harmless second key). The Play Store's verifier refusal still reaches
  `recordStoreSuspendable`.
- **The media button's hold.** The manifest declares `foregroundServiceType="mediaPlayback"` and
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; `startForeground(.., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` runs before
  Media3's `onStartCommand`, so a `startForegroundService` from the receiver is always answered in time, whatever
  Media3 does later (an early `stopForeground` by Media3 no longer matters once `startForeground` was called). A plain
  start from the background throws `ForegroundServiceStartNotAllowedException`, which is caught and leaves Media3's
  path as before. Media3's notification and the placeholder share `DEFAULT_NOTIFICATION_ID`/`DEFAULT_CHANNEL_ID`
  (the provider is the default one wrapped by `SecretMediaNotification`), so Media3's post replaces the placeholder;
  the placeholder is `VISIBILITY_SECRET`. `onDestroy`'s `removeCallbacksAndMessages(null)` drops a pending check. A
  room or Spotify is judged through the session's player, which follows the renderer (`setPlayer(KidPlayer(..))`).
- **The lint baselines hide nothing serious.** Launcher: `UseAppTint` 29, `RestrictedApi` 20 (all
  `KidAppComponentFactory`'s `CoreComponentFactory` use), `MissingPermission` 20, `PermissionImpliesUnsupported
  ChromeOsHardware` 7, `ResourceType` 1 (`obtainStyledAttributes` with an `intArrayOf`, AirplaneRoute.kt:355). I
  spot-checked eight `MissingPermission` sites (AppEnforcer:1161, QuickControls:474/484, MusicCalls:98,
  PinLockRuntime:2277, MobileNetworkRuntime:101, ...): each is inside a `try`/`runCatching`. Music: 16
  `UnsafeOptInUsageError` (Media3's `@UnstableApi`). No `NewApi`; CI's `./gradlew build` runs `lint`, so
  `abortOnError` bites there.

## High

### H1 (pre-existing, not from this branch): a boot without `ACTION_SHUTDOWN` can hang for good on Android 14-16 in own mode

§6as's note says `compat34` hung after `adb emu kill` with Settings hidden, the boot cover off (own mode) and no
`ACTION_SHUTDOWN`: user 0 stayed BOOTING on a black screen. I think this can happen on a real phone, and the code
doesn't prevent it:

- Before the user is unlocked, only direct-boot-aware HOMEs resolve. That holds with no Android PIN too: the user
  only leaves RUNNING_LOCKED through `finishBooting`, and `finishBooting` waits for the first HOME activity to go
  idle. On AOSP the one direct-boot-aware HOME is Settings' FallbackHome. `HomeActivity` isn't direct-boot aware
  (gotchas-and-history.md), and neither is Launcher3, as step M found on Android 15's image. While apps are managed the
  plan hides `com.android.settings`, on 14 and on 16 (16 refuses the suspension, not the hide).
- In own mode the cover is enabled only at `ACTION_SHUTDOWN`, and only while it is wanted
  (`bootCoverEnabled(SHUTDOWN, ..)`, `bootCoverWanted(switchOn || androidFace, ..)`). The `boot_cover` switch is off
  by default, so for most phones nothing is armed. Step M's fix (the cover always enabled) applies to android mode only.
- `BootCoverPlan.kt`'s own-mode note says "with no Android PIN the boot isn't BFU" and falls back to A+B. The compat34
  run contradicts that.
- On a real phone the trigger is any reboot that skips `ACTION_SHUTDOWN`: a forced restart (holding power or
  power+volume), a kernel panic or watchdog reset, a battery that cuts out, or `adb reboot`. The hidden flag survives
  the reboot, so every later boot hangs the same way. Without adb, getting out probably means a factory reset, which
  also loses the device owner.
- On 17 this can't happen, because Settings is never hidden there. The branch's note is right about that.

**What would settle it** (on the Jelly Star, ideally also on `jelly36`/`six35`; keep adb on for the rescue):
`cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME --user 0`,
then check each HOME package for `DIRECT_BOOT_AWARE` with `dumpsys package <pkg>`. Then, on a managed phone in own
mode with the cover off and Settings `hidden=true`, run `adb reboot` and watch `getprop sys.boot_completed` and
`dumpsys activity users`. If the boot hangs, `adb shell pm unhide com.android.settings` should let it finish, which
proves the cause.

**Fix** (if it reproduces, its own round): apply step M's rule in own mode too. Keep a direct-boot-aware HOME of ours
enabled at all times while the plan hides the package that hosts the only direct-boot-aware HOME. That is the cover,
inert when the switch is off: M-1's `coverInert` already is a bare window that hands over to Home. The pure rule would
be `bootCoverEnabled(.., ownModeNeedsDbaHome = true)` -> true at every event. Correct the "isn't BFU" note at the same
time.

## Medium

### M1: Before the first `apply` in a process, the plan's stand-in is the refusal record, not the plan

`plannedOff` uses this boot's refusal record until `lastPlanSuspend`/`lastPlanHide` are set, and those live only in
memory. That gives two wrong windows:

- **17, after every reboot:** the record belongs to the last boot, so it is ignored. Android Settings is visible and
  unsuspended, so it is back on Home and in the drawer as the dead tile this branch removes, until the first `apply`
  (the anchor's start sync, which is later still when the server can't be reached). Lock task still blocks it, so it
  is a dead tile and not an escape.
- **Any version, a process death during the pause:** the record still holds Settings (on 17 both sets, on 16
  `suspend`), so the pause's drawer and the system-page rows ("pause first") leave out the Settings the parent paused
  for, until the first `apply`.

**Fix:** at each `apply`, store the plan's off-set (`plan.suspend + plan.hide`, a string set in the
`platform_refusals` prefs or its own file, with the boot count). Make it the stand-in in `plannedOff`, and keep the
refusal record for `restrictCallDue` only. Add a pure row to `PlatformRefusalsTest` for each window: after a reboot the
stored plan says off, and in a pause the stored plan says on.

## Low

- **L1: The refusal memory doesn't look at the app-op again before skipping.** If the op is revoked during a boot
  (`appops set`, a role or exemption change), the restricting call is still skipped until the reboot, although Android
  would now accept it. Fix: in `apply`, skip only while `PlatformRefusals.exemptFromSuspension(context, pkg)` still
  holds. That is one binder call per recorded package per pass.
- **L2: The music hold doesn't re-enter the foreground on a second foreground start.** In `holdForeground`, the
  `hold != null` branch only extends the wait. If Media3 has already called `stopForeground` (`maybeStopForegroundService`
  with no items), a second `startForegroundService` during the hold is never answered. That start would come from a
  notification's play `PendingIntent`, or from the receiver when no session is active. It is the 30 s crash again. It
  is narrow, because a live session gets the hardware buttons. Fix: in that branch, also call `startForeground(..)`
  when `foregroundServiceType == 0`.
- **L3: The hold can leave its placeholder behind, and it swallows errors.** `RELEASE_STOP`'s
  `stopForeground(STOP_FOREGROUND_REMOVE)` removes only a notification that is still the foreground one. If Media3
  detached the placeholder first, the placeholder outlives the service. Fix: when `holdShown() == PLACEHOLDER`, also
  `NotificationManager.cancel(MEDIA_NOTIFICATION_ID)`. Separately, the `catch (e: Exception)` around `startForeground`
  hides a `SecurityException` or `InvalidForegroundServiceTypeException`. Log it (`Log.w`, class name) so a broken
  manifest isn't silent.
- **L4: The PIN-gated Settings' top gear (`SettingsActivity.settingsSystem`, `ACTION_SETTINGS`) doesn't go through the
  plan.** On 14-16 Settings doesn't resolve and the gear shows the "unavailable" toast. On 17 it resolves, and under
  the kiosk it becomes Android's "App is not available" (with the default block bit). Without the bit, a start into
  our own locked task isn't a lock-task violation, so it is unclear what happens. This is the parent's screen, not
  the kid's, but it is the one Settings entry left that doesn't say "pause first". Fix: check
  `PlatformRefusals.plannedOff(context, <resolved package>)` first and show the toast (or the rows' "pause first").
- **L5: An open drawer may miss a plan change for a refused package.** Example: at a pause's end on 17, Settings
  sends no package callback, so a drawer left open still lists it until a callback from some other package or until
  it is reopened. Lock task blocks the tap. Fix (optional): have `AppsRecyclerAdapter` listen to
  `AppEnforcer.addAppliedListener` like Home does.
- **L6: The launcher's lint baseline hasn't been through CI's build.** §6as ran the launcher's `lintDebug` with the
  tsnet stub, and the note says the launcher's `lintVitalRelease` needs `libs/tsnet.aar` (CI only). Before merging,
  check that the first CI run of `./gradlew build -PrequireTsnet=true -PwarningsAsErrors=true` passes. If CI finds a
  different set, regenerate the baseline there.
- **L7: Tests that would pin more.** `PlatformRefusalsTest` pins the pure rules and the call sites, but not
  `HomeActivity`'s listener pair (`addAppliedListener(planListener)` / `removeAppliedListener(planListener)`), and not
  that `refused` records only an exempt package (a source check that `exemptFromSuspension` comes first in
  `refused`). `ForegroundHoldTest` has no row or source check for the second start (L2). The rest is pinned where it
  matters: the hold's verdicts, each `Unresumable` as a `ResumptionRefused`, both `holdOn` returns, and no NewApi in
  either baseline.

## Verdict on the boot hang

It is plausible on a real Android 14, 15 or 16 phone. The conditions are own mode, the boot cover off (the default),
apps managed, and any reboot without `ACTION_SHUTDOWN`. When it happens it is permanent and leaves no way back on the
phone, so it is High. It comes from before this branch: the branch doesn't change it on 14-16 and removes it on 17.
The `adb reboot` test under H1 settles it.

## Counts

High 1 (pre-existing), Medium 1, Low 7.
