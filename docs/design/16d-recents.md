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
