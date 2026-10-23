# Android's lock on a pure AOSP image at the Jelly Star's profile, 2026-10-23

Main at `f0c6934a`, branch `aosp-lock-look`. The question (design 59d §8.4, "Android's pad size" and the AOSP-image
paragraph; §8.8 step L): the user's emulator and `handy36` run Google's images (Pixel SystemUI), but the Jelly Star
runs a near-stock AOSP build, so its PIN pad, clock and gesture handle come from AOSP SystemUI. What does AOSP show at
the Jelly Star's 320x569 dp, and how does our glance compare? Step L's Jelly Star clock profile gets its numbers from
this run. Screenshots are in `screens/aosp-lock-look/` (480 px wide).

## Setup

- Image `system-images;android-36;default;x86_64` r2 (pure AOSP: Launcher3 + AOSP SystemUI, no Google apps),
  installed for this run. Fingerprint `Android/sdk_phone64_x86_64/emu64x:16/BE2A.250530.026.D1`. `ro.product.model`
  = **"Android SDK built for x86_64"**, `ro.product.manufacturer` = **"unknown"**.
- AVD `aosp36`, made without `-d`, then `config.ini`: `hw.lcd.width=480`, `hw.lcd.height=854`, `hw.lcd.density=240`,
  `skin.name=480x854`, `skin.path=_no_skin`, `hw.ramSize=2048`, `hw.keyboard=yes`. Also `hw.mainKeys=no` (the
  default `yes` means hardware keys and no navigation bar at all) and a 4 GB data partition. `wm size` 480x854,
  `wm density` 240 = **320x569 dp**, the Jelly Star. Headless (`-wipe-data -no-window -no-audio -gpu
  swiftshader_indirect -memory 2048`). It started only after the other agent's `six35` had stopped. I killed it at the end.
- **Navigation**: the image starts in 3-button mode (`navigation_mode` 0). I switched it to gesture navigation
  (`cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`) to see the handle. The
  Jelly Star's mode is not known.
- Debug launcher 0.23.6-debug of this commit (no `tsnet.aar` here, so the stub and no native libraries: it installs
  on x86_64). Local server 0.40.1 from this commit, fresh data dir, debug admin component. PWA over HTTP with the
  scratch `pwa_setup.py` recipe: Parent PIN, Child PIN `1357`, calls managed. Provisioned per `emulator.md` §4,
  enrolled in Vibb One's Settings, Lock row to Android's lock (`PIN command` -> escrow token -> `secure=true face=true`,
  `keyguard: mode=ANDROID features=0x397`). Language en-US, time zone UTC.
- **Restart, a lesson for the next run**: my first boot set an Android PIN with `locksettings set-pin` *before*
  provisioning (to shoot AOSP's own pad), then cleared it. LockSettingsService then logged "Permanently disabling
  support for escrow tokens on user 0", and every reset token afterwards failed with `SecurityException`
  (`PIN command: refused: no_token`, `migration: no active reset token yet`). Escrow tokens are disabled for good once
  an unmanaged user unlocks with a credential. Wiping and provisioning again fixed it. **Never set a PIN on the AVD
  before `dpm set-device-owner`.** Those first frames (AOSP's own wallpaper, unmanaged) are still useful and are named
  "unmanaged" below.
- Glance rows: AOSP Messaging (an old target SDK, with only the `miscellaneous` channel at importance -1000) posted
  **no notification** for console SMS, even after its first run. So the rows are three **missed calls** (Mamma,
  Pappa, Mormor) from `gsm call`/`gsm cancel`.
- Ground: no wallpaper set, so the glance is the Vibb night. Android's lock wallpaper is the night mark (step K:
  NightMark where no ground is known), and that is what SystemUI's face shows below.
- Screenshots come from the emulator console (it captures FLAG_SECURE), and recordings from `emu screenrecord` at
  24 fps. On the 480x854 display at 240 dpi, 1 dp = 1.5 px (1.75 px at `wm density 280`). mm are for the Jelly Star's
  3.0" 480x854 panel (≈ 326 ppi, 0.078 mm a pixel).

## The PIN pad (bouncer)

Measured by `pad.py` (connected regions that differ from the background, ≥ 40 px, round): key diameter, column pitch,
and the 12 keys' block. The Pixel row is a `handy36` frame at `wm density 240` from the hybrid-prototype run
(`plain-proto/shots/pinpad-from-row.png`), measured the same way. The user's own measurement was ~39 dp.

| Pad | Key | Key mm | Pitch | Block (12 keys) | Share of screen |
|---|---|---|---|---|---|
| Pixel SystemUI, font 1.0 | 61 px = 40.7 dp | 4.7 mm | 78.5 px = 6.1 mm | 218x297 px | 45 % wide, 35 % high |
| **AOSP, font 1.0** | **67 px = 44.7 dp** | **5.2 mm** | 85.5 px = 6.7 mm | 238x323 px | 50 % wide, 38 % high |
| AOSP, font 1.3 | 61 px = 40.7 dp | 4.7 mm | 79.5 px = 6.2 mm | 220x299 px | 46 %, 35 % |
| AOSP, font 1.0, `wm density 280` | 46 px = 26.3 dp | 3.6 mm | 67 px = 5.2 mm | 180x246 px | 38 %, 29 % - **the digits are cut off** at the bottom of each key |
| Vibb One's own keypad (own mode), 1.0 | 108 px = 72 dp | 8.4 mm | 144 px = 11.2 mm | 396x459 px | 82 %, 54 % |

- AOSP's pad at 1.0 is about **10 % bigger** than the Pixel image's (67 against 61 px). It is the same bouncer code,
  but on the Pixel image the prompt sits 26 px lower ("Enter PIN" at y 153 against 127), and the pad shrinks to fit
  the height that is left.
- **Bigger text or display size makes the keys smaller, not bigger.** The bouncer fits its keys into the height left
  over by the text. At 1.3 the AOSP keys are the Pixel's size at 1.0. At display size one notch up (`wm density 280`),
  the screen is 274x488 dp and the keys are 46 px with their digits clipped (`pad-density-280.png`). Both are no help
  for a small pad on a 569 dp tall screen.
- Same order on both images: the prompt, the PIN field (370 px wide), the 4x3 keys (Delete and Enter in the bottom
  corners), "Emergency" as a pink pill. The image: `pad-compare.png` (Pixel 1.0 | AOSP 1.0 | AOSP 1.3 | AOSP 280),
  `pad-1.0.png`, `pad-1.3.png`, `pad-density-280.png`.

## AOSP's lock face

AOSP's face uses its default clock. On this image the face shows **no date line** in any form: on the Pixel the date
comes from Google's smartspace, and AOSP has none. A secure-notification policy keeps notifications off the face
(our `ANDROID_KEYGUARD_FEATURES` = `0x397`), so on the managed phone the face never has notifications. That decides
which clock it shows:

- **Large, double-line clock** (the default; `lockscreen_use_double_line_clock` unset). Hours over minutes, centred
  (ink x 130-357 for "12", centre 243). Each line's digits are **162 px = 108 dp** tall (line 1 y 300-461, line 2
  y 476-637), stems 19 px. Lavender on the BFU face, white in the wake frame. This is what the Jelly Star will show if
  its build keeps AOSP's default: `face-bfu-double-line.png`, `wake-frames.png`.
- **Single-line clock** (`lockscreen_use_double_line_clock 0`, or a face with notifications - unmanaged, with a
  notification posted: `face-with-notification-unmanaged.png`; uiautomator calls it `lockscreen_clock_view`, the small
  clock's id). The box is at x 42 (28 dp), left-aligned. The digits' ink runs from **y 104 to the last row 196** (93
  rows, the same height as the Pixel's 180-272), with the **baseline at 197 px**. The first digit's ink is at
  **x 56** for a "1". "12:17" runs x 56-417, wide and widely tracked ("1" 56-88, "2" 129-185, ":" 227-242, "1" 287-319,
  "7" 359-417). The "1"'s stem is 11 px (the Pixel's is 7-8). No date line. `face-bfu-single-line.png`.
- The double-line setting is **not Pixel-only**: AOSP has it, its default here is double-line, and `0` gives the
  single-line clock in the same place as the small clock. AOSP Settings has a "Double-line clock" switch on its Lock
  screen page. I didn't open it on this image: our kiosk hides Settings.
- **Gesture handle**: pixel for pixel the Pixel image's - 162x6 px (108x4 dp), rows 833-838 (its bottom 15 px =
  10 dp above the edge, centred in the 24 dp inset), x 159-320, luma 236 (white ≈ 92 %). It shows on Launcher3 and on
  AOSP's unmanaged face, but **not on the BFU face and not in the wake frame**.
- **Lock icon**: a small padlock (about 8 px) centred at y ≈ 688, in a 108 px `device_entry_icon_view`. **No "This
  device belongs to your organization"** on the BFU face or in the wake frame (the Pixel's face had it). The BFU
  status bar shows the time at the left and no carrier name.
- **At a wake after the first unlock** (`wake-frames.png`; three recordings with the default setting, two with
  `0`): **one** full frame of SystemUI's face - the large double-line clock in white over our ground, no handle, no
  lock icon visible - then one cross-fade frame, then the glance. With `0` there was only the cross-fade frame, the
  single-line clock showing through. The Pixel showed 2 + 3 frames (59d §8.8). After Back from the pad, the pad fades
  straight into the glance (no face). After a call ends over the lock: dark, then the glance fades in (no face).
- BFU after `svc power reboot`: SystemUI's face (the large clock over the night mark), then at the next wake step F's
  pad, then Back to our boot cover ("Tap to unlock") - so in BFU the face is seen only before the first touch.

## Our glance on AOSP

- **Fallback** (before the change): `Glance clock: no profile (model Android SDK built for x86_64, maker unknown,
  density 1.5, width 320 dp) - the centred clock`. That means 56 sp bold centred, the long date, three rows
  (`glance-fallback.png`).
- **The profile change** (the one code change, `LockGlance.kt`): `AOSP_EMULATOR_CLOCK`, keyed on maker "unknown" and a
  model starting "Android SDK built for" (only at 240 dpi and 320 dp, as the others). `JELLY_STAR_CLOCK` is now
  `AOSP_EMULATOR_CLOCK.copy(name = "Jelly Star")`, in place of the Pixel emulator's numbers, with the comment "AOSP
  emulator, confirm on the phone". The numbers, from the single-line clock:

  | Field | Value | Pixel emulator, for comparison |
  |---|---|---|
  | `clockInkStartDp` | 37.3 (56 px, AOSP's "1") | 34 (51 px) |
  | `clockBaselineDp` | 131.3 (197 px) | 182 (273 px) |
  | `clockDp` | 86 (93 ink rows, the same as the Pixel's) | 86 |
  | `dateStartDp` | 35.3 (2 dp left of the clock's ink) | 32 |
  | `dateBaselineDp` | 168.7 (253 px = the clock's baseline + 56 px, the Pixel's spacing; AOSP shows no date) | 219 |
  | `dateSp` | 16 | 16 |
  | `handle` | 108 x 4 dp, bottom 10 dp, white 92 % | the same |

- **The twin** (`clock-twin-aosp.png`: AOSP's single-line face "12:17" left, our glance "12:21" right, each 240 px
  wide; red lines at the ink's top row 104 and the baseline 197, grey line at x 56). Ours: the "1"'s ink at **x 56**
  (AOSP 56), digit ink **rows 105-196** (AOSP 104-196), so the left edge, the top and the baseline are within 1 px.
  Our date's ink is at x 55, y 236-255. The handle sits on AOSP's pixel for pixel (rows 833-838, x 159-320).
  **Not matched**: the width (ours "12:21" x 56-364, AOSP "12:17" x 56-417 - AOSP's font is wider and widely
  tracked), and the weight (Nunito Light's stem 8 px, AOSP's 11 px - here Nunito Regular would be closer than Light,
  and the profile has no weight field). With the rows: `glance-profile.png` (two missed-call rows fit under the
  clock).
- **Twinned against the single-line clock, not the default.** The face the Jelly Star most likely shows - the
  double-line clock, centred, 108 dp digits - can't be expressed by `ClockGeometry` (one line, left edge + baseline).
  Ways forward, for the user to choose:
  - (a) Turn "Double-line clock" off on the phone during setup (a pause; AOSP Settings › Lock screen). Then AOSP's
    face shows the single-line clock in exactly this profile's place.
  - (b) Keep the default and accept that the one wake frame shows a stacked clock.
  - (c) Build a double-line variant of the profile (a new layout, more than numbers).
- At `wm density 280` no profile applies (the density key), so the glance falls back to the centred clock. It fits
  three rows (`glance-density-280.png`). The rows look about the same pixel size as at 240: whether the lock
  re-measured them after the density change or kept its old configuration, I didn't check.

## Recents key, bottom-edge swipe, "Return to call"

- **Recents key** (`KEYCODE_APP_SWITCH`) over the glance: nothing. Shell logs `RecentsTransitionHandler.startAnimation:
  no controller found`, the glance stays resumed and focused, and there is no BlockedAppActivity.
- **Bottom-edge swipe** (`input swipe 240 850 240 350 200`, from the handle): Launcher3's TouchInteractionService
  takes `TYPE_RESET_GESTURE` (`canStartSystemGesture=false`, flags
  `overview_disabled|home_disabled|keygrd_occluded`). The touches reach us (`Glance swipe up: unlock asked`), and
  Android's pad comes up. This is the same as on the Pixel image (59d §8.11): lock task refuses the system's Home
  gesture on AOSP's Launcher3 too.
- **"Return to call"**: during an answered `gsm call` our call screen covers the glance, so I brought the pad up with
  `wm dismiss-keyguard`. The pad's "Emergency" pill reads **"Return to call"** (`pad-in-call.png`). Tapping it brings
  back our `InCallActivity`. When the call ends, the glance is back.

## What only the real phone can settle

- **Which clock the Jelly Star's face shows.** Unihertz's SystemUI may restyle or move it (vendor clock faces, font,
  default of the double-line setting), and its build may add a date line. This needs a frame of its face (the wake,
  or after a reboot before the PIN), with `getprop ro.product.model` / `ro.product.manufacturer` for the profile key.
  Those are still unverified: the match ignores spaces, underscores and case.
- **The pad's size there.** It depends on the phone's own font scale and display size defaults, and on the height its
  bouncer leaves the keys. On a 3.0" panel the AOSP keys are about 5.2 mm at 1.0 and smaller at every larger text or
  display size. If the user finds them too small, Android has no setting that makes them bigger. Our own keypad (8.4
  mm) is the only big pad.
- **Whether the face's one wake frame shows at all on real hardware**, and the navigation mode (the handle, or three
  buttons).
- "Return to call" when the call came while the pad was already up.
