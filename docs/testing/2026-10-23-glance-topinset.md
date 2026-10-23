# 2026-10-23 - the glance's clock under the keyguard status bar; the edge swipe on the user's AVD

Branch `glance-topinset` from main `1ef36d09`. There are two problems from the user's rc.3 recording on their own AVD,
`jellystar2`: the Google Play Android 16 image (Pixel SystemUI) at 480x854 and `wm density 240`, made without a device
profile (no cutout, a 24 dp status bar), running the release launcher 0.36.0-rc.3 in android mode with the kiosk on
and a photo ground. Design: 59d §8.15.

## Setup

- AVD **`jelly36`**, made to match the user's: `system-images;android-36;google_apis_playstore;x86_64` without a
  device profile. Its `config.ini` is 480x854 at 240 dpi, `_no_skin`, 2 GB, keyboard, `hw.mainKeys = no`, and
  swiftshader. The recipe is in `emulator.md` §3. `wm size` 480x854, density 240 = 320x569 dp. `ro.product.model`
  `sdk_gphone64_x86_64` by Google, so the emulator Pixel profile applies. `dumpsys window`:
  `statusBars frame=[0,0][480,36]`, cutout `Rect(0, 0 - 0, 0)`. Gesture navigation, with `mandatorySystemGestures`
  bottom at 48 px.
- The debug launcher of this branch, and this branch's server on port 3150 (fresh data). The PWA was scripted over
  HTTP: Parent PIN, Child PIN `1357`, calls managed with one contact, Messages and Chrome allowed (kiosk on: Home's
  task and the glance's are both in lock task), the Lock row set to Android's lock (`face=true`), and a photo
  ("Skumring", ticked for the lock screen) as the only wallpaper. `lockscreen_use_double_line_clock 0`. English, UTC.
  Provisioned per `emulator.md` §4.
- Captures (24 fps console recordings, the dumps, logcat with epoch times) are in `~/handy-captures/topinset/`. Frames
  for the docs are in `screens/glance-topinset/`.
- **A provisioning trap, found on the way.** The first provision enrolled from Vibb One's Settings without ever opening
  Home. Home's first interactive start sets `internal.started_before`, so at the reboot `initRest` ran its first-launch
  `resetPreferences`. The cached policy was gone: kiosk off, `apply: requested=false kidPin=false`, navy wallpaper.
  I wiped and provisioned again, opening Home first. A real phone opens Home right after provisioning, so this is a
  test-setup trap and not the user's case. `emulator.md` §3 now says to open Home first.

## Problem 1: the clock twin sat about 76 px too low

**The user's frames** (their recording; Android's face alone in frames 112-113, our glance from 115; ink = luma > 215):

| | Clock ink rows | Last ink row | First ink x | Date capitals |
|---|---|---|---|---|
| Android (Pixel SystemUI) | 104-197 | 197 | 50-51 ("1" of 18:35/36) | rows 235-252 |
| Ours, rc.3 | 181-273 | 273 | 51 | - |

That is 76 px (50.7 dp) too low, as the user said: Android's glyphs start about 70 dp from the top, ours about 121 dp.
`screens/glance-topinset/before-user.png` shows the two, with lines at 197 and 273.

**The rule, from the numbers.** The brief's model was "the status-bar inset plus the `handy36` offset": 36 + 137 =
173 px. That is 24 px higher than the user's Android. SystemUI hangs the small clock under its **keyguard status bar**,
`SystemBarUtils.getStatusBarHeaderHeightKeyguard`. That bar is the larger of the status bar (which covers a cutout) and
`status_bar_header_height_keyguard`, which is 40 dp = 60 px at 240 dpi. With that rule every measured case is
"bar + 137 px" for the clock's baseline and "bar + 193 px" for the date's:

| Status bar | Keyguard bar | Android's clock baseline | Android's date baseline | Source |
|---|---|---|---|---|
| 36 px | 60 | 197 (= 60 + 137) | 253 (= 60 + 193) | the user's `jellystar2`; `jelly36`; `aosp36` (AOSP SystemUI, `2026-10-23-aosp-lock-look.md`) |
| 72 px | 72 | 209 (= 72 + 137) | 265 | `jelly36` + `cutout.emulation.tall` |
| 136 px | 136 | 273 (= 136 + 137) | 329 | `handy36` (pixel_7 cutout); `jelly36` + `cutout.emulation.hole` |

So the Pixel and the AOSP images share the vertical offsets: 137 px = 91.3 dp, and 193 px = 128.7 dp. The AOSP
profile's old 131.3 dp from the top was the same place, seen on a 36 px status bar.

**Built** (`LockGlance.kt`, `GlancePanel.kt`):
- `ClockGeometry.clockBaselineBelowBarDp` / `dateBaselineBelowBarDp` replace `clockBaselineDp` / `dateBaselineDp`.
  The values are 91.3 / 128.7 dp for `EMULATOR_PIXEL_CLOCK` and `AOSP_EMULATOR_CLOCK` (so `JELLY_STAR_CLOCK` too).
- The pure `keyguardStatusBarPx(statusBarPx, cutoutTopPx, density)` is the max of the status bar, the cutout and
  `KEYGUARD_STATUS_BAR_MIN_DP` (40 dp). `profileBaselinePx(bar, belowDp, density)` gives the baseline.
- `GlancePanel.placeProfile` reads the window's insets at every placing: `rootWindowInsets`, the status bar by
  `getInsetsIgnoringVisibility(statusBars())`, and the cutout by `getInsets(displayCutout())`. The root's inset
  listener consumes the insets for the views only. The log line is
  `Glance clock: keyguard status bar N px, the clock's baseline at N px, the date's at N px`, once per bar.
- The left edge, the sizes, the font, the date's start and the handle are unchanged.
- Tests (`LockGlanceTest`):
  - The 36 / 72 / 136 / cutout / no-insets rows of `keyguardStatusBarPx`.
  - Each profile on a 36 px and a 136 px status bar lands at bar + offset (197 / 273, and the date at 253 / 329).
  - Both profiles share the offsets.
  - The glue reads the window's insets.
  - The fits at 473/497 dp: on a 48 dp status bar the clock sits 42.7 dp higher than the old fixed 182 dp. Music + 1
    row now keeps the full tile at 473 dp too, and music + 2-3 rows take the full tile with one row visible. JL-3's
    order is unchanged.
- `LockGlanceGuardTest` allows the new log line's three px numbers.

**Measured on `jelly36`**, this branch, Android's face and our glance in the same 24 fps recordings (a 12-hour clock
"7:12", the same digits on both faces):

| Status bar | Ours logged | Android: clock rows / date "F" rows | Ours: clock rows / date "F" rows |
|---|---|---|---|
| 36 px | bar 60, baseline 197, date 253 | 104-196 / 236-252 | 105-196 / 236-252 |
| 72 px (`tall`) | bar 72, 209, 265 | 117-208 / 248-264 | 118-208 / 248-264 |
| 136 px (`hole`) | bar 136, 273, 329 | 180-273 / 312-328 | 181-273 / 312-328 |

The baseline (the last ink row) is equal at every bar. The top differs by 1 px (Nunito's digits are 0.712 em, Google
Sans Flex's a row taller). The date's capitals sit on the same rows.

**The left edge** is unchanged, as briefed. Our first digit's ink is at x 51-52, the profile's 34 dp. Android's "7"
starts at x 45-46, while its "1" is at 49 and its "5" at 53 (`handy36`). So for a 7 the twin is 6 px off. Google Sans
Flex's side bearings differ by digit, and the profile keeps one ink start (step L's "within 2 px" holds for 1 and 5
only). This is an open item: a per-digit ink start for Android's font would need the other digits measured (0, 2-4, 6,
8, 9).

Images: `screens/glance-topinset/after-jelly36.png` (36 px), `after-jelly36-72px.png` and `after-jelly36-136px.png`.
Each has Android's face on the left and ours on the right, with a line on the baseline.

## Problem 2: the bottom-edge swipe on the user's AVD

**What the user's frames show** (`v4/sheet2.png`, rows 2-3):
1. The glance moves up with the finger.
2. The glance shrinks into a card over SystemUI's face. The face has the clock, "T-Mobile" in the keyguard status bar
   and "Denne enheten tilhører organisasjonen din" at the bottom.
3. The glance comes back. There are dark frames in between (f096-f111).

The card is Quickstep's swipe-to-home animation over the lock, which is **`DeviceLockedInputConsumer`**. Quickstep saw
`home_disabled` missing from SystemUI's flags and started Home - the §8.9 (a) desync - on every swipe. The heal is in
rc.3 (`faf74a5d` is an ancestor of `launcher-v0.36.0-rc.3`). So on their AVD either:
- the heal didn't fire,
- it fired and didn't change SystemUI's copy, or
- lock task wasn't held.

**On `jelly36` it did not reproduce in any variant.** The recipe was `edge.sh`, the capture's: screen off and on (the
glance up), Quickstep's `TouchInteractionService` dump and lock task before, a 24 fps recording, the swipe, the focus
every 0.4 s, and the same dumps after. Logcat (main, system, events) ran throughout.

| Variant | Runs | Consumer | Our line | Result |
|---|---|---|---|---|
| Fresh provision, `input swipe 240 850 240 400 150`, 5 s apart | e1-e3 | `TYPE_RESET_GESTURE` | `Glance swipe up: unlock asked` | Android's pad (the bouncer gets the focus) |
| Slow, mouse-like: `input swipe 240 850 240 300 600` and from y 853 | slow1-2 | `TYPE_RESET_GESTURE` | unlock asked | the pad |
| `input motionevent` DOWN at y 852, MOVEs 25 px apart, UP at 300 | mev1 | `TYPE_RESET_GESTURE` | unlock asked | the pad |
| After `adb reboot`: the boot cover's pad, the Child PIN, the boot hand-over to Home, then the glance | b1-b3 | `TYPE_RESET_GESTURE` | unlock asked | the pad |
| After `am crash com.android.systemui` (SystemUI restarted under the glance) | s1-s2 | `TYPE_RESET_GESTURE` | unlock asked | the pad |
| No screen-off: swipe (pad), Back, swipe again | pb1 | `TYPE_RESET_GESTURE` | unlock asked, twice | the pad |
| After an unlock through the pad, Home, and a re-lock | u1-u3 | `TYPE_RESET_GESTURE` | unlock asked | the pad |

The dumps were the same in every run:

```
mLockTaskModeState=LOCKED   (tasks: Home's type=home, and the glance's .pinlock)
mDisabled1=0x7260000 mDisabled2=0x15
canStartSystemGesture=false
systemUiFlagsDesc=overview_disabled|home_disabled|keygrd_occluded|allow_gesture|awake    (before)
setInputConsumer: TYPE_RESET_GESTURE. reason(s):
  -> cannot start system gesture and recents animation was not running, trying to use default input consumer;
     ResetGestureInputConsumer available, using ResetGestureInputConsumer
onMotionEvent(240, 850): ACTION_DOWN ...
```

There was no `The system's Home while LOCKED` line in any run: the system never started Home.

Not tried: music playing. The music app isn't on this AVD, and design 59d §8.9's capture already found that it plays
no part in Quickstep's choice.

**The reboot path** (b1-b3) found something else. The boot unlock's first process took 10.7 s in its unlocked setup on
the main thread (the debug build, with another agent's rustc running on the host). It hit an **ANR** ("executing
service ... BadgeListenerService, waited 20048ms", `bg anr`, killed). A second process then started LOCKED and put the
glance up, with lock task held. This is RC3-4's main-thread time (PLAN's backlog) and not the swipe. It does make the
"after the boot hand-over" path differ from run to run.

### What the user's `swipe-dump.txt` / `swipe-log.txt` should show, and what each case means

1. **Lock task.** Check `mLockTaskModeState` and `mLockTaskModeTasks` in `dumpsys activity activities`.
   - `NONE` while the glance is up means lock task isn't held. Our heal does nothing then: `healStatusBar` writes the
     features only while something is pinned. Look for the glance's `lock task: mode=0 ...` line in `AndroidLock`'s
     resume logging. The fix would be in `lockTaskEntry`'s face path (who roots lock task after the boot hand-over).
2. **The status bar's copy.** Check `dumpsys statusbar` `mDisabled1` / `mDisabled2`.
   - Without the Home bit (`0x200000` in `mDisabled1`; 0x7260000 is the LOCKED set), the framework never told
     SystemUI. A heal re-sends the whole set, so check case 4 next.
3. **Quickstep's copy.** Check `systemUiFlagsDesc` in the `TouchInteractionService` dump, and the consumer's reasons.
   - `TYPE_DEVICE_LOCKED` with "can start system gesture ... keyguard is showing occluded" and no `home_disabled`,
     while `dumpsys statusbar` has the Home bit, is the 16d desync. SystemUI's taskbar or navbar copy is stale.
4. **Our heal.** Look for `PinLock: The system's Home while LOCKED (a home gesture got through): lock task's flags
   healed` and `LockTaskChrome: Status bar heal (the system's Home while locked): lock-task features 113 -> 112
   written, back in 250 ms`.
   - Present at the first swipe, with the second swipe still `TYPE_DEVICE_LOCKED`: the flip doesn't reach the stale
     copy. The next fix toggles something SystemUI must re-derive the Home disable from. A SYSTEM_INFO flip only
     changes the status-bar icons' bits.
   - Absent: Home's `onNewIntent` / `onCreate` never ran as a non-ours HOME start. Check `wm_new_intent` for
     `HomeActivity` and the Quickstep line `ActivityStartInterceptor: Starting home`. The trigger needs another hook.
   - Every swipe going through within 5 s of the last heal would also show here (`SYSTEM_HOME_HEAL_MIN_GAP_MS`). Their
     swipes were further apart.
5. **The face's state.** Check `AndroidLock: screen-on` / `the glance resumed` with `keyguardLocked`, `deviceLocked`
   and `interactive`.
   - If `PinLock` isn't LOCKED (`mode`), `systemHomeHealDue` is false. Look for the lock's mode lines
     (`... -> LOCKED on ...`).

No code change for problem 2 in this round: none of the variants reproduced it.
