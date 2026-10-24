# Home round - emulator notes, 2026-10-24 (before code QA)

Branch `home-round` (778d0edd Home at the top after an unlock, fed6d9c4 the "Media on the lock screen" row, c493aa5e
Home's own ground during a transition), the debug launcher of that branch on `jelly36` (the user's profile, Android
16 Play image, gesture navigation), the test server on port 3160 (main, 0.44.0 + the 1-minute reload), kiosk off
(no apps ticked), wallpaper 5 "Evening sky" (a gradient) ticked and as the lock background, Android's lock on
(Child PIN 1357). Screenshots and frame sheets in `screens/home-round/`.

## The black flash after the Home button (item 3): FIXED

Recorded with the emulator console (`screenrecord start/stop`, 30 fps frames through ffmpeg), from Messages
(allowed app, light UI) pressing the Home key (`input keyevent 3`), the focus checked before (Messages) and after
(HomeActivity). A frame's background is read as the 10th percentile of its luminance (the icons don't move it):

| Build | Runs | Frames with a black background (p10 < 10) | Darkest background during the transition |
|---|---|---|---|
| Before (branch `r11-glance`, = rc.5's Home) | 2 | **2 and 3 frames** (67-100 ms), one fully black | p10 = 0, p30 = 0 |
| `home-round` | 3 (+1 earlier) | **0** | p10 = 29 (the gradient) |

Screen 01: the old build's frames 75-86 - Messages slides down over Home's icons on **black**, then the gradient
arrives. Screen 02: the new build's transition - the gradient is under the icons from the first frame. Back from
Messages was clean on both (as the user saw). So the cause was the system wallpaper not yet shown under Home during
the app-close transition, and Home drawing its own copy of the ground (`homeGroundDue`) covers it; no seam seen when
the copy goes.

## Home at the top after an unlock (item 1): not observable here

With kiosk off and only six tiles the grid doesn't scroll on this AVD, so the reset can't be seen; `HomeResetTest`
pins the rule, and the log shows the new arrival signal (`HomeFront: Home brought to the front: the lock left`). The
user's emulator with its full app list is the place to see it: scroll Home down, screen off/on, PIN -> the top.

## The "Media on the lock screen" row (item 2): not opened

Not reached in this session (time); the row's state reads a Secure setting that may say "Ukjent" on a phone - the
implementer flagged that; the user checks it on their emulator during a pause.

## Observations for code QA (not findings yet)

- **ANRs right after provisioning**, twice, on this build: `Input dispatching timed out (Application does not have a
  focused window)` for `SettingsActivity`/`HomeActivity` within the first minute after `set-device-owner`, while the
  main thread was in `Application.loadApps` <- `LauncherApps.Callback.onPackageChanged` (the enforcer's hide/suspend
  storm at provisioning fires a package-changed callback per package, each calling `loadApps`) and the VM's CPU was
  over 80 % (a cargo build in parallel, six ANRs system-wide incl. GMS). The same storm exists before this round;
  the control build was not tested inside the storm. Worth a look at `loadApps` debouncing regardless (RC3-4 family).
- `SettingsActivity` "Displayed +5s853ms" on this build's first start after install (a fresh process) vs +1s768ms on
  the control build in the same situation ten minutes earlier; one sample each, the emulator just booted for the
  first - inconclusive, but check that nothing in the round delays Home's/Settings' first frame.
- A `BLASTSyncEngine: Unfinished container ... HomeActivity` plus pause/stop timeouts for HomeActivity appeared during
  the storm (the main thread busy), not after it.

## Setup lessons

- `jelly36` lost its userdata twice when the emulator process died (not `emu kill`): the overlay isn't flushed. After
  such a death: re-provision, and check `dumpsys device_policy` for the owner before anything else.
- `WallpaperApplier: Not applied: not allowed or not supported` = the launcher isn't device owner (yet); it said so
  until `set-device-owner` and a process restart.
- The HOME-role dialog ("Set Vibb One as your default home app?") needs the radio picked before "Set as default".
- Android's lock's 60 s timeout darkens the screen mid-run: wake and unlock before every recording.

## Follow-up: the provisioning storm with the app-list debounce (branch `appload-debounce`, main a11c05a2)

A fresh `jelly36` (wiped), main's debug launcher, `set-device-owner` + the first Home start, logcat watched for 2 min:
**0 ANRs of ours** (7 system-wide: the stock launcher's notification listener and GMS failing to start - the
emulator's cold-boot load), Home resumed and in front. "N apps loaded" lines: 6 in the first minute, 59 after two -
on this loaded emulator the enforcer's per-package DPM calls are spaced more than 300 ms apart, so most packages
still open their own burst; each load took 90-1700 ms. The ANRs are gone, but the storm still costs ~1 load per
restricted package. Follow-up (PLAN): hold the app-list loads while `AppEnforcer.apply`'s loop runs (one load at its
end), which removes the storm's loads altogether.
