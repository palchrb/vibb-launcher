# 16d: Recents and swipe-up in kiosk - "Pixel Launcher is not available"

Investigation only, no code changed (2026-10-07). Setup: AVD jellystar, Android 16 Play `BE2A.250530.026.D1`,
`me.vibb.launcher.debug` DO, kiosk on (unlocked flags 119 = no OVERVIEW; LOCKED 113), PIN lock. Caveat: someone
else used the emulator at the same time (Element VoIP calls 12:28-12:30, a non-shell reboot at 12:23). Only my own
steps count below.

## 1. Mechanism
- With a third-party Home, quickstep (TouchInteractionService in `com.google.android.apps.nexuslauncher`) runs every
  swipe-up (fast = Home, slow = Recents) and `KEYCODE_APP_SWITCH` as a recents transition to its fallback
  `com.android.quickstep.RecentsActivity`. If that activity is running, it is reused: no start, no dialog. If not, it
  is started, and with the block bit ASI turns the start into `BlockedAppActivity` (the dialog, once with an input
  ANR). Dropping OVERVIEW doesn't help: LTC's `isActivityAllowed` ignores it, and the key toggles anyway.
- **Preload, gesture nav only**: every TIS init (`TISBinder#onInitialize`) runs `START ... RecentsActivity from uid
  10182 (realCallingUid=1000)` + `RecentsAnimation: Cannot start`. In lock task this leaves a hidden `type=recents`
  BlockedAppActivity task; before lock task, a real RecentsActivity. Not tied to boot: `am force-stop` of
  nexuslauncher in kiosk made the BlockedApp task again within 0.1 s, so any quickstep death (LMK, update) re-arms it.

| Boot (gesture) | USER_UNLOCKED | TIS init / preload | our typed Home start | Result |
|---|---|---|---|---|
| 12:17 (`adb reboot`, prior run) | 36.2 | - / 43.3 | 38.7 | BlockedApp t8 (lock task first) |
| r1 12:21 (`adb reboot`) | 45.9 | 51.07 / 51.21 | 47.8 | BlockedApp t8 |
| r2 12:23 (other actor) | 01.9 | 04.62 / 04.78 | 05.05 | real RecentsActivity t10 |
| r4 12:48 (`adb reboot`) | 50.97 | 53.32 / 53.43 | 53.81 | real RecentsActivity t28 |

## 2. Results
1. **Stale task**: made by the TIS-init preload, 2.4-5.2 s after the unlock; a race with Home's lock task (table).
   Boot cover not testable: shell `pm enable` of `BootCoverActivity` gives "Cannot disable a protected package", and
   force-stop of ours is ignored ("protected package"); the PPA exists only with the switch on. [inferred] The cover
   doesn't help: the preload follows TIS init, not Pixel being Home. Without Pixel in BFU its process cold-starts
   later, so lock task is likelier to win and the dialog likelier.
2. **Every gesture, no reboot needed**: with the stale task (S1) the fast swipe resumed t20, and the slow swipe and
   the key started new standard tasks (t21, t23, t25) - all the dialog (screenshot). With no recents task at all
   (3-button boot, then switched to gestural) the first swipe gave the dialog (t28). With a real RecentsActivity (r2)
   swipes were clean, but the key opened the **real Overview** with OVERVIEW off (Element card, Screenshot, Select),
   so the smoke test's PASS hides an escape. "Passed without reboot" = the race was won earlier.
3. **3-button**: the Recents button is hidden (`mDisabled1` has DISABLE_RECENT). Home goes via
   `OverviewCommandHelper type=HOME` to our typed HOME start, no RecentsActivity. TIS init (force-stop) and an
   `adb reboot` in 3-button made **no preload, no recents task**. After that reboot and the PIN unlock: Home, Back,
   the Recents area and long-press Home, twice, also from Clock - all clean. Only `KEYCODE_APP_SWITCH` (keyboard or
   a11y, not on the phone) still gives the dialog, so smoke-test.sh's Recents check FAILs in 3-button (skip or adapt).
   - Setting it: no DPM path - the mode is a framework overlay (CHANGE_OVERLAY_PACKAGES, privileged), and
     `navigation_mode` is only a mirror, not in `setSecureSetting`'s allowlist [AOSP]. So it is a provisioning step:
     `adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton` (works from
     shell), or Settings before it is hidden. It survives a reboot (verified). The kid can't change it: Settings is
     hidden, and the gesture tutorial (`GestureSandboxActivity`) is blocked in kiosk.
4. **Stock launcher hidden or suspended**: `pm hide` is refused for shell (MANAGE_USERS). `pm suspend` (suspender
   shell): swipe-up and the key show `SuspendedAppActivity`, "App isn't available ... managed by" - still a dialog;
   gesture mode stays. A DO suspension can't be made from shell; proxy: the DO-suspended camera2 (while LOCKED)
   started from shell gave `result code=0` and no activity (silent: the admin-support intent doesn't resolve with
   Settings hidden). `pm disable-user` (closest to a DO hide): quickstep gone, `navigation_mode` stays 2 - no handle,
   no Home/Recents gesture (a swipe from Clock did nothing), Back works, the key does nothing, **no fallback to
   3-button**. Restored (`pm enable` + `pm default-state`). Option C's boot risk (11 doc A3) is still untested.
5. **Pinning the recents package (H)**: not testable - only the DO sets the lock-task list, no server access. Today
   explicit `am start` of NexusLauncherActivity, launcher3 `SettingsActivity` and `GestureSandboxActivity` each gives
   BlockedAppActivity. With H all of them start (LTC allows by package) and `kioskFeatures` keeps OVERVIEW, so the
   Overview (r2 screenshot) returns via the key, swipe-and-hold and the 3-button Recents button. [inferred] Its
   Screenshot/Select actions open other unpinned packages (Markup, Search): the dialog comes back elsewhere.

## 3. Options and recommendation
| Option | Dialog | Escape / cost |
|---|---|---|
| **3-button nav** (provisioning) + OVERVIEW dropped (as now) | never from the screen; only a keyboard Recents key | none found; a provisioning step, reported, not enforced |
| Gesture + HOME and OVERVIEW off in kiosk (Back only) | not from swipes (over the lock, flags 113: fast/slow/hold swipes did nothing) | no Home gesture; stale task stays latent; key = dialog |
| Gesture + H (pin recents package) | gone for swipes | Overview, NexusLauncherActivity/settings startable; Overview actions hit other blocked packages |
| Gesture + suspend (C) or hide/disable | silent (DO) or another dialog (shell) | Home gesture dead, no 3-button fallback; BFU risk |
| Drop the block bit | silent (violation) | loses design 11's in-task block: rejected |

**Recommendation: 3-button navigation on every kiosk phone, as a provisioning step.** Keep `kioskFeatures` as is.
The launcher reports `navigation_mode` and `config_recentsComponentName` in its status, and the device page warns
"gesture navigation: Home/Recents show 'App is not available' in kiosk". It is the only option tested with no dialog
from screen input and no new reach. A phone that must stay on gestures gets row 2 (HOME off in kiosk), never H.

Jelly Star (not checked): run `cmd overlay lookup android android:string/config_recentsComponentName`, `settings get
secure navigation_mode`, `cmd overlay list | grep navbar`. If quickstep lives in its stock launcher (the 11 doc
guesses `com.android.launcher3`), it's the same issue whenever Home is ours: set 3-button before `dpm
set-device-owner` or the QR flow, then rerun `smoke-test.sh REBOOT=1` with the Recents-key check adjusted.

## User decision (2026-10-07)

- **Keep gesture navigation; every stock gesture must keep working in the kiosk.** That means back, home (swipe up),
  recents (swipe up and hold), quick switch (swipe along the bottom) and the notification shade.
- **Never remove a stock gesture without asking the user concretely.** Design 16's dropping of OVERVIEW was such a
  removal, so it is to be reverted once the dialog is solved another way.
- Next: test pinning the recents package (stock launcher) in the kiosk with OVERVIEW restored, using the debug hook
  from 9cdb8c2f, and evaluate the escape surface. The assistant corner gesture is asked about separately, because the
  assistant can open apps and the web.

## Experiment 2 - recents package pinned

Emulator only, no code changed (2026-10-07, 14:48-15:31). Same AVD, gesture nav, kiosk on, PIN lock; the build with the
9cdb8c2f hook. Recents provider (`config_recentsComponentName`): `com.google.android.apps.nexuslauncher`.
**A** = `--ei add_features 8 --es extra_lock_task_packages com.google.android.apps.nexuslauncher` (unlocked 127; LOCKED
stays 113). **B** = the package only (unlocked 119). Use 8, not §6e's 12: 12 also adds HOME to LOCKED. Quick switch and
the assistant corner were dropped from scope by the user mid-run; not tested.

1. **Back, home swipe, shade: no dialog in any pinned boot.** Tested in A over 3 `adb reboot`s (B1-B3), the install
   boot and an `am force-stop` of nexuslauncher, and in B over 3 reboots (F1-F3). Fast and slow swipe, hold, the key,
   and swipe/back/hold from Clock: never BlockedAppActivity in a captured dump. The preload now leaves a real
   RecentsActivity inside lock task, never the `type=recents A=1000:android` task. Baseline (override reset, R1-R3): R3
   had the stale task and the first home swipe gave the dialog, as in 16d. Hold in B: nothing (Home stays, an app snaps
   back). The shade opened unlocked in every pinned boot. In the install boot it didn't open at all, even via `cmd
   statusbar expand-notifications` with the override reset; fine after the next reboot (cause unknown).
2. **New: SystemUI misses the LOCKED disable at boot.** In 3 of 6 pinned boots (B3, F1, F3; 0 of 3 baseline; all after
   an UNLOCKED shutdown), `dumpsys statusbar` had `mDisabled1=0x7260000 mDisabled2=0x15` but SystemUI's `TaskbarDelegate
   mDisabledFlags=0`. Over the PIN lock, the hold then opened the real Overview and the shade opened **with Quick
   Settings** (Internet, Bluetooth, Flashlight, Modes). With the override reset in that boot the shade still opened and
   the hold gave BlockedAppActivity, so the pin isn't required for it, though it may make it likelier [inferred]. It
   lasts until the next disable change: the unlock, or `cmd statusbar send-disable-flag clock` then `none` (tested).
   This is design 16's open check "pull the shade ... right as the lock comes up", failing.
3. **Overview (A)** shows only standard lock-task tasks (Clock, Element X, Camera), never our Home task or the phone
   book (which lives in it). Swipe-away works. Clear all removed Element and Clock, killed Element's process, kept Home
   and LOCKED lock task, and showed our Home. Over the lock (via the key) it shows the lock card. Swiping that card away
   brought the lock straight back as a new task. Empty space starts the typed HOME (ours). Actions:
   - The task icon menu has App info, Pause app, Screenshot, Select and Close (no split, freeform or pin at 480x854).
   - App info: `APPLICATION_DETAILS_SETTINGS` -91, nothing (Settings hidden).
   - **Pause app**: BlockedAppActivity "Digital Wellbeing is not available right now"; the app is not paused.
   - Screenshot: its Edit opens **Markup**, which gives BlockedAppActivity (same as today's power+volume screenshot).
   - Select: text offers Copy, Share and Search (WEB_SEARCH -91, nothing). An image offers Copy, Share, Save and a
     direct share to an Element contact; More opens the share sheet, whose Edit opens Markup (BlockedAppActivity).
   - Nothing opened Settings, the wallpaper picker or the stock home.
4. **Escape surface (A and B alike; lock task allows by package).** `am start -n` opens NexusLauncherActivity,
   launcher3 `SettingsActivity` ("Home settings"), `GestureSandboxActivity` and `SecondaryDisplayLauncher` (all
   exported). In the stock home, the drawer lists only non-hidden apps (Camera, Clock, Element X, Kids Launcher, Phone,
   Play Store); Play Store gives the dialog. Its search offers Google, YouTube, Maps, Play Store, Settings and Contacts:
   Google and Settings did nothing, Play Store gave the dialog. I found no kid-reachable path to any of these: HOME
   always resolved to ours (persistent preferred). [from code, untested] `fencePlan` never suspends a lock-task package,
   so with `update_fence` on, a pinned nexuslauncher is the unfenced fallback HOME during our self-update.
5. **Recents key** (not on the phone): A opens Overview, also over the lock. B opened an empty Overview from Home once
   (F2), because the key ignores OVERVIEW off.

**Conclusion.** Pinning the provider removes the home-swipe dialog: 0 dialogs in 6 pinned boots and a force-stop,
against 1 in 3 baseline boots. Choose:
- **B (pin, OVERVIEW off)** while Overview is undecided: the same fix, no Overview UI, and all the required gestures.
- **A** if the user wants to close apps from Overview. Closing works and never removes Home or ends lock task. It adds
  three dialog paths inside Overview (Pause app; Markup via Screenshot or image Edit). [untested] Hiding
  `com.google.android.markup` and `com.google.android.apps.wellbeing` would make those a silent -91.

Guards needed in either case:
1. Heal the boot desync (needed even without the pin): after the boot's first lock/Home resume, and again a few seconds
   later, force a disable change (for example `setStatusBarDisabled` off then on while LOCKED). Smoke-test it: compare
   `TaskbarDelegate mDisabledFlags` with `dumpsys statusbar` after 3 reboots from UNLOCKED, and pull the shade over the
   lock.
2. The update fence still suspends the pinned recents package, or unpins it before the commit.
3. Pin the resolved provider package, never a fixed name; keep the persistent preferred HOME.

**Jelly Star:** run `cmd overlay lookup android android:string/config_recentsComponentName` to find the provider
(likely `com.android.launcher3` or a vendor launcher). Then check that package's exported activities and its Overview
actions (split screen, pin, freeform, wallpaper), the desync check above, and the stale task with and without the pin.

## Decisions after experiment 2 (2026-10-07)

1. **Security fix first (independent of the rest):** after a boot, SystemUI can ignore the PIN lock's status-bar block
   (the shade and Quick Settings opened over the lock in 3 of 6 boots, once with no override at all). Re-apply the block
   at every point where it can have been lost:
   - after boot (BOOT_COMPLETED / USER_UNLOCKED and the first screen-on), by toggling it through `LockTaskChrome`;
   - whenever SystemUI restarts, if detectable;
   - verify it by reading `StatusBarManager`/`dumpsys statusbar` disable flags where possible.

   Add a smoke-test check after `REBOOT=1`: the shade can't be expanded over the lock.
2. **Pin the system's recents provider in the kiosk** (variant A of experiment 2):
   - resolve the package from `config_recentsComponentName`, a system package only;
   - add it to the kiosk lock-task packages whenever the kiosk is on and the block bit is set, and to the PIN-lock
     helpers when that is needed for the same race;
   - restore OVERVIEW: revert design 16's `kioskFeatures` drop, per the user's gesture rule;
   - the update fence keeps suspending it during our self-update commit, like any other HOME candidate (experiment
     2's guard 2): on a Pixel it is the stock launcher, the fallback Home the fence exists to stop. The update runs at
     night with the screen off, so a minute without Recents doesn't matter. (Corrected the same day: the first text
     said "never suspends or holds it", a mistake.)
   - enforcement never hides or suspends it (the allowlist, SMS off, a new or updated package, the time-rule lock, the
     camera lock): it is in the never-restrict set. Home and the drawer still leave it out even if it has a launcher
     icon, like the Play Store outside install mode. The fence is the only thing that suspends it.

   Residual risk, accepted by default unless the user vetoes: an allowed app could explicitly start the stock home,
   settings or search of that package. No kid-reachable path was found.
3. **Jelly Star checks:** its recents provider package and its nav mode; the dialog over 5 reboots; the shade over the
   lock after reboots.

## Implementation status (2026-10-07)

Code with unit tests, **not device-tested** (no emulator in this pass). Commits `0cb8b0a7` (decision 1), `0104eff0`
(decision 2) and the follow-up after it (the fence suspends the provider again; never-restrict); `launcher/CLAUDE.md`
design 16 section.

**Decision 1 - status-bar heal (security).** `setStatusBarDisabled` off/on can't do it: in lock task DPMS only records
it (LockTaskController owns the status bar; AOSP `notifyLockTaskModeChanged`), and a same-value call is a no-op. So
`LockTaskChrome.healStatusBar` writes the current pass's lock-task features with **SYSTEM_INFO flipped** (pure
`statusBarHealFeatures`, `lock/StatusBarHealPlan.kt`), waits 250 ms outside its monitor (LockTaskController reads the
features when its posted update runs), then `endStatusBarHeal` runs a normal pass. The flip changes only
DISABLE_CLOCK/DISABLE2_SYSTEM_ICONS - never the shade, QS, Home, Recents, power menu, KEYGUARD or the block bit - so
the net flags change twice and SystemUI is sent the whole set (as `cmd statusbar send-disable-flag clock`/`none` did
in §2 of experiment 2). Only while something is pinned; `LockTaskChrome` stays the only writer (tested over all
source sets).
- Triggers (`PinLockRuntime`, on the `lock-chrome` thread): 1, 3, 6, 10, 20 and 40 s after every process start (our
  init is the boot's user unlock; also our restart after an update), `BOOT_COMPLETED`, the process's first screen-on,
  and every screen-off. A SystemUI or quickstep restart can't be seen by an app, so the screen-off heal (invisible)
  stands in for it. Each heal logs `Status bar heal (<trigger>): lock-task features A -> B for 250 ms`; while the
  screen is on the clock and status icons blink once.
- Verification: impossible in-app (SystemUI's copy is only in its dumpsys; `StatusBarManager.getDisableInfo` is a
  system API). `smoke-test.sh REBOOT=1` now probes for 45 s from the lock's first appearance
  (`expand-notifications`, `expand-settings`, a swipe from the top) and compares `TaskbarDelegate mDisabledFlags`
  with `dumpsys statusbar` `mDisabled1` (`docs/testing/emulator.md` §5b).
- Residual: a desync that happens between two heals (from about 1 s to 40 s after the start) is open until the next
  heal - the 45 s probe shows whether that window is real.

**Decision 2 - recents provider pinned, OVERVIEW back.** `HelperKind.RECENTS`: the package of
`config_recentsComponentName` (`Resources.getSystem()`, null-safe) goes through `lockTaskHelpers` like every kiosk-block
helper - system app only, never Settings/the camera/Play - so the plan pins it whenever the kiosk is on with the block
bit (also in a time-rule lock and while LOCKED; LOCKED features stay 113). Design 16's `kioskFeatures` is removed:
the server's features are set as sent (OVERVIEW back). An unresolvable or non-system provider is not pinned and
OVERVIEW still stays (the dialog returns there rather than a gesture going). Each apply with the bit logs
`Recents provider (config_recentsComponentName): <pkg>, system <bool>, pinned|not pinned`.
- PIN-lock helpers (kiosk off): **not added.** The kiosk-off lock has no block bit (a violating start is just
  aborted - no BlockedAppActivity, so no stale task), except during a VoIP call over the lock (design 17), where
  pinning would let the call app start the stock home over the lock.
- Update fence (guard 2): `fencePlan(..., recentsPackage)` suspends it like any HOME candidate although it is pinned
  (a lock-task package and kiosk-block helper, so in the fence's protected set) and although enforcement never
  restricts it (with a launcher icon it is "controllable" without being enforcement's); ours, never, persistent,
  FallbackHome and already suspended still skip it. The release unsuspends it itself (`runRelease(...,
  recentsPackage)`), and the orphan sweep takes it too. The first implementation (`0104eff0`) excluded it, following
  the decision's first text - reverted.
- Never restricted by enforcement: `AppEnforcer.systemRecentsPackage` (system app only) is in
  `computeEnforcementPlan`'s never-restrict set (allowlist, SMS off, time-rule lock - so `apply()` unsuspends and
  unhides it whenever no fence holds it), `shouldSuspendNewPackage` (an install or update of it) and
  `cameraLockTargets` exclude it. The notification rule never suspends or hides anything; its notifications are still
  cancelled like any non-allowed app's (keeping them would let a tap open the pinned stock launcher). Home and the
  drawer leave it out (`AppFilter.kidListable`, from `recentsPackage()`), even with a launcher icon and whatever the
  allowlist says. So a stock launcher with a launcher icon (possibly the Jelly Star's) no longer loses quickstep to
  `apply()`.

**Emulator checks (Pixel AVD, gesture nav, kiosk + PIN lock):**
- [ ] `smoke-test.sh REBOOT=1` three times, each from UNLOCKED (unlock, then reboot): the new shade check PASSes and
  `TaskbarDelegate` equals `dumpsys statusbar`; logcat shows the `Status bar heal` lines 1-40 s after the start.
- [ ] Recents key and slow/fast swipe and hold over the lock and unlocked: never BlockedAppActivity; Overview opens
  unlocked (OVERVIEW back) and not from the swipes over the lock (113).
- [ ] The `Recents provider` log line names `com.google.android.apps.nexuslauncher`, system true, pinned; and
  `dumpsys device_policy` lists it in the lock-task packages with the block bit.

**Jelly Star checks (decision 3):**
- [ ] Recents provider: `cmd overlay lookup android android:string/config_recentsComponentName` and the launcher's
  `Recents provider` log line agree; it is a system app and pinned. Nav mode: `settings get secure navigation_mode`,
  `cmd overlay list | grep navbar`.
- [ ] Whether that package has a launcher icon (`cmd package query-activities -a android.intent.action.MAIN -c
  android.intent.category.LAUNCHER | grep <pkg>`): if so, it is never hidden or suspended (`pm list packages -s -u`
  / `dumpsys package <pkg> | grep -E 'hidden|suspended'` after an apply and a time-rule lock) and still not on Home
  or in the drawer.
- [ ] Its exported activities (`dumpsys package <pkg>`, `am start -n` each) and Overview's actions (split screen,
  pin, freeform, wallpaper, Pause app, Screenshot/Select): what opens, what gives the dialog, whether anything reaches
  Settings or the stock home with a kid's input.
- [ ] The dialog over 5 reboots: `smoke-test.sh REBOOT=1` five times (Recents and swipe-up checks), plus a home swipe
  right after each unlock.
- [ ] The shade over the lock after those reboots: the smoke test's 16d checks, and a manual pull of the shade (and
  QS) right as the lock comes up.
- [ ] With `update_fence` on (11 doc A3): the provider is suspended during the commit (the fence log line's
  `suspended`), unsuspended at the release, and the gestures work again right after it.
