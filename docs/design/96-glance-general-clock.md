# Design 96: the glance's general copy of Android's lock-screen clock

Status: built, 2026-11-14, branch `glance-generic` (approved by the user: "Do it"; finding S10 of
`docs/testing/2026-11-14-screen-sizes.md`), with its code QA's L1-L6 fixed (`docs/review/qa-96-code.md`, §8). It
extends design 59d step L (§8.8, §8.11, §8.15 of `59d-android-mode-inventory.md`).

## 1. The problem

The glance and the time-rule screen share one clock face (`lock/LockClockFace`). Since 59d step L it copies Android's
small keyguard clock - thin, left-aligned, high up, the short date under it - so the switch between Android's
keyguard and our page is seamless. That copy came only from a **measured profile** (`glanceClockProfile`): the Pixel
emulator image, the pure AOSP image and the Jelly Star, and only at 240 dpi and 320 dp wide. Every other phone, and a
Jelly Star at another display size, got the centred 56 sp bold clock and the long date. That looks nothing like
Android's face, and on a 411 dp phone it leaves half the page empty (S10).

## 2. What AOSP SystemUI does (Android 14-16)

Read in AOSP `platform/frameworks/base`, branches `android14-release`, `android15-release` and `android16-release`
(`packages/SystemUI/...`). All the values are equal on the three branches unless the table says otherwise.

| What | AOSP source | Value |
|---|---|---|
| The small clock's size | `customization/res/values/dimens.xml`, `small_clock_text_size` | **86 dp** ("dp is intentional, to prevent any further scaling": it never follows the font scale) |
| The clock view's height | `customization/.../dimens.xml`, `small_clock_height` (read on 15 and 16) | 114 dp |
| The clock's box from the window's start | `customization/.../dimens.xml`, `clock_padding_start` (read on 15 and 16), plus `status_view_margin_horizontal` (0 dp on phones; 16: customization, 14/15: `res/values/dimens.xml`) | **28 dp** (`ClockSection.applyDefaultConstraints`: `connect(lockscreen_clock_view, START, PARENT_ID, START, clock_padding_start + status_view_margin_horizontal)`) |
| The clock's top | `res/values/dimens.xml`, `keyguard_clock_top_margin` | **18 dp** under the keyguard status bar (`KeyguardClockViewModel.getSmallClockTopMargin` -> `ClockPreviewConfig.getSmallClockTopPadding`: `keyguard_clock_top_margin + getStatusBarHeaderHeightKeyguard()`) |
| The keyguard status bar | `res/values/dimens.xml`, `status_bar_header_height_keyguard`; `SystemBarUtils.getStatusBarHeaderHeightKeyguard` | the larger of the status bar (a top cutout included) and **40 dp** (already in the code since 59d §8.15, `keyguardStatusBarPx`) |
| The date's start (below the clock) | `res-keyguard/values/dimens.xml`, `below_clock_padding_start` (`KeyguardSmartspaceViewModel.getDateWeatherStartMargin`) | 32 dp |
| The clock's font | `customization/res/layout/clock_default_small.xml`: `@*android:string/config_clockFontFamily`, weight 400 on the lock | the maker's (Google Sans Flex on a Pixel - 16's `FlexClockController` when `clockReactiveVariants` is on) |
| The gesture handle | `res/values/dimens.xml`, `navigation_home_handle_width`, `navigation_handle_radius`, `navigation_handle_bottom` | 108 dp wide, 4 dp high (radius 2 dp), its bottom 10 dp above the window's |

No phone-size qualifier overrides any of these: the `dimens.xml` of `res/values-sw360dp`, `-sw372dp`, `-sw392dp`,
`-sw400dp`, `-sw410dp`, `-h411dp`, `-h700dp`, `-h800dp`, `-h841dp` (and `res-keyguard/values-sw360dp`) hold none of them
on 16 (nor the size folders of 15's `res/`), and `customization/res/`'s `h700dp`/`h800dp` change only the *large*
clock's size. They change only from `sw600dp` (a tablet's split shade). So **the small clock's geometry is the same in dp on every phone**, whatever its
density or width, and it does not follow the font scale.

**Android 14 and 15 differ** (measured, §6): there the keyguard is the legacy `KeyguardStatusView`, placed by
`KeyguardClockPositionAlgorithm` (`mMinTopMargin` = the keyguard status bar + `keyguard_clock_top_margin`), not 16's
blueprint (`ClockSection`). On the Android 15 and the Android 14 Pixel images (Google APIs) the small clock and the
date sit **18 dp lower** than on 16 - `keyguard_clock_top_margin` once more -, at the same left edge, pixel for pixel
the same on 14 and 15. Which path SystemUI takes is a build flag (`MigrateClocksToBlueprint`, aconfig), not the SDK:
the code keys it on `SDK_INT` (below 36: legacy) because that is what Google's builds do, so a maker's build that
differs gets the clock 18 dp off; the face logs the SDK with the general profile to trace such a phone.

Not an AOSP dimension, so measured (59d §8.11, §8.15): the clock view's baseline under its own top - its font's
ascent - is 110 px at 1.5, **73.3 dp**, on both the Pixel image (Google Sans Flex) and the pure AOSP image; with the
18 dp that is the 91.3 dp under the bar both measured profiles carry. The date's baseline is 37.4 dp under the clock's
(the Pixel image; AOSP's own face shows no date, its date comes from a smartspace plugin). The first digit's ink lies
right of the box by the font's side bearing: 6 dp on the Pixel image (34 dp), 9.3 dp on the AOSP image (37.3 dp).
The date keeps its **top** in place when the font scale grows it, so its baseline moves down by its ascent's share of
the growth. Since Android 14 sp sizes scale non-linearly from 1.13 (`FontScaleConverterFactory`: 16 sp is 20.2 dp at
1.3, 28 dp at 2.0), so the shift is taken from the date's real scaled size: 0.93 of the growth (measured 0.91-0.95 at
1.3, 1.8 and 2.0, Android 14 and 16; §6).

## 3. The general profile

`generalClockProfile(manufacturer, widthDp, sdk)` (`lock/LockGlance.kt`):

- clock: 86 dp in Nunito Light (the stand-in, as the measured profiles); its first digit's ink at 28 dp plus the
  bearing - Google's 6 dp on a phone whose maker is Google, the AOSP image's 9.3 dp on every other maker's (AOSP leaves
  the font to the maker); its baseline 18 + 73.3 = 91.3 dp under the keyguard status bar, below Android 16 (API 36)
  18 dp more, 109.3 dp;
- date: 16 sp (it follows the font scale, as before), its box at AOSP's `below_clock_padding_start`, **32 dp for every
  maker** (it does not follow the clock font's bearing; the code QA's L2), its baseline 37.4 dp under the clock's at
  font 1.0, and 0.93 x (the date's scaled size - 16) dp lower at another font scale (`profileDateBaselineBelowBarDp`,
  `DATE_GROWTH_SHIFT`, the size from `TypedValue.applyDimension(COMPLEX_UNIT_SP, ..)`; for the measured profiles too - at
  1.0 nothing moves);
- handle: AOSP's 108 x 4 dp, 10 dp off the bottom, white at 92 % (measured on both images).

Everything is in dp, so the density and the width only enter through the bound below and the keyguard status bar
(which `LockClockFace` already reads from the window at every placing). The font scale does not move the clock.

**`glanceClockProfile` returns the measured profile where one matches, else the general one.** The measured ones were
all taken on Android 16, so they apply from API 36 only; below it the general one (with the legacy place) is used.
**The real Jelly Star is on Android 16:** Unihertz took it from 13 straight to 16 ("Jelly Star Upgrades to Android 16",
unihertz.com, 2026-08-31), and Vibb One's `minSdk` is 34, so a Jelly Star that runs Vibb One keeps `JELLY_STAR_CLOCK`
(at 240 dpi and 320 dp, its model string matching) - still to be confirmed on the phone, as since 59d step L. Where its
model string or density doesn't match, it gets "general AOSP": the same clock, the date at 32 dp instead of 35.3.

It is `null` only when the sanity bound fails, and then the face keeps the old centred clock:

- the widest clock must end inside the face's 16 dp side padding: Nunito Light's digits are tabular, so every "HH:mm"
  is 2.619 em wide, and the box starts at most 0.053 em (a "4") left of the ink - at 86 dp, `ink start + 220.7 dp <=
  width - 16`. That is **271 dp** for a Google phone and **274 dp** for the others. The narrow 270 dp class keeps the
  centred clock, which fits there at both fonts (the screen-size run);
- a **smallest** width of 600 dp or more (AOSP's `sw600dp` tablet resources; the code QA's L4) keeps it too;
- and the face asks for no copy at all (`glanceClockCopyAllowed`, the code QA's L4/L5) in **landscape** (Android's
  keyguard on a phone doesn't rotate; the time-rule screen can, with the launcher's rotate preference), **right to
  left** (Android mirrors its clock to the right edge; ours is placed from the left - mirroring it would need the last
  digit's right bearing, not built), and with **digits Nunito Light doesn't draw** (the locale's zero isn't "0":
  Arabic-Indic, Persian - another font takes them and the bound's width no longer holds). The copy is LTR with Latin
  digits.

No new controls, no server change. The face logs once which it took: "no measured profile (model, maker, density,
width, sdk) - the general one", or "no profile (..., copy true/false) - the centred clock".

## 4. It reproduces the measured profiles

| Profile | Ink start | Clock baseline under the bar | Size | Date start | Date baseline | Handle |
|---|---|---|---|---|---|---|
| `EMULATOR_PIXEL_CLOCK` (measured) | 34 | 91.3 | 86 | 32 | 128.7 | 108 x 4, 10, 0.92 |
| general, maker Google | 34 | 91.3 | 86 | 32 | 128.7 | the same |
| `AOSP_EMULATOR_CLOCK` / `JELLY_STAR_CLOCK` (measured) | 37.3 | 91.3 | 86 | 35.3 | 128.7 | 108 x 4, 10, 0.92 |
| general, any other maker | 37.3 | 91.3 | 86 | **32** | 128.7 | the same |

Equal to 0.1 dp (`LockGlanceTest`, "the general clock profile"), but for one cell: the general date start is AOSP's
32 dp for every maker, where `AOSP_EMULATOR_CLOCK` / `JELLY_STAR_CLOCK` keep 35.3 (carried over from the Pixel's "2 dp
left of the ink" on a face that showed no date; kept until a phone with a date is measured). That is no coincidence:
the measured profiles' numbers *are* AOSP's dimensions plus the two font measurements, so the general profile is the
measured ones with the gate on model, density and width taken away. The measured profiles stay and take precedence: `jelly36` and the Jelly Star at
240 dpi place the clock exactly as before (only the date moves, at a font scale other than 1.0, §3).

## 5. Which phones it matches

- **Matches** (the clock where Android's is, to a few pixels; only the glyphs differ - Nunito Light against the
  maker's font): phones with AOSP's keyguard - Pixel (Google Sans Flex: Google's bearing), the Android emulators,
  Unihertz, Nokia (HMD), Motorola, Fairphone, Sony, and other near-stock builds. A maker that keeps AOSP's layout but
  changes `config_clockFontFamily` moves the ink a few dp (the bearing) and changes the digits' height (an ascent of
  its own moves the baseline as much as a few dp). **"No qualifier overrides these" is about AOSP's own `res/`**: a
  maker's device overlay (an RRO in its device tree, e.g. `status_bar_header_height_keyguard` or
  `keyguard_clock_top_margin`) can still change any of them, and no emulator carries one - only a frame of the phone
  tells.
- **Only close** (our page shows Android's AOSP small clock; their own lock screen looks different, so the switch is
  not seamless there): Samsung One UI (its own centred clock and lock-screen editor), Xiaomi HyperOS / MIUI, OPPO /
  OnePlus / realme ColorOS and OxygenOS, vivo OriginOS / Funtouch, Honor MagicOS, Huawei (no Google services, out of
  scope anyway), Nothing OS (its own fonts and clock faces). A measured profile per such phone can join the table
  when one is in the house.
- **Pixel on Android 16 with the flag `clockReactiveSmartspaceLayout`** would put the date and weather *beside* the
  small clock when the screen is wide for the font (`KeyguardSmartspaceViewModel.dateWeatherBelowSmallClock`: below
  only up to 346 dp at font 1.0, 376 dp at 1.5, ...). The Android 16 Pixel image here (BE2A.250530.026.D1) keeps the
  date below at 411 dp at both fonts, so the flag is off there. Not modelled; a later Pixel build with it on would show
  its date beside the clock while ours stays below.

## 6. The emulator check (2026-11-14)

Android's own keyguard on an unmanaged AVD (a PIN from `locksettings`, the single-line clock with
`lockscreen_use_double_line_clock 0`, the wallpaper dimmed to black with `cmd wallpaper set-dim-amount 1`), then the
AVD wiped, the debug launcher of this branch provisioned (device owner, enrolled to a local 0.79.0 server, android mode)
and our glance shot the same way. 24-hour clock, Europe/Oslo. Each at font 1.0 and 1.3, gesture and 3-button navigation.
Positions are ink rows and columns in the console screenshots (px): the clock's first digit (a "1" on both sides),
and the date's first letter's last ink row (Android's "S" overshoots its baseline by about a pixel, our "L" does not).

| AVD | Display | Image | Profile taken | Clock: Android's / ours | Date: Android's / ours |
|---|---|---|---|---|---|
| `jelly36` | 480x854 @ 240 (320 dp), a 36 px status bar | Android 16, Google Play | `emulator Pixel` (measured) | rows 105-196, x 49 / rows 105-196, x 51 - **unchanged**: the clock's band is rows 105-197 from x 51 as in main's shot of this morning (`2026-11-14-screen-sizes.md`) | 253 / 252 at 1.0 (main's: 252); 259 / 258 at 1.3 (main's baseline was fixed: it would have stayed at 252) |
| `gg67`, throwaway | 1440x3120 @ 560 (411 dp), 84 px status bar, keyguard bar 140 px | Android 16, Google Play | `general Pixel` | rows 243-457, x 115 / rows 246-459, x 119 (the baseline within 2 px, the edge 4 px = 1.1 dp right) | 591 / 589 at 1.0; 605 / 604 at 1.3 |
| `gg61`, throwaway | 1080x2400 @ 420 (411 dp), 63 px status bar, keyguard bar 105 px | Android 15, Google APIs | `general Pixel (API 35)` | rows 230-390, x 87 / rows 231-391, x 89 | 490 / 489 at 1.0; 500 / 500 at 1.3 |

| `gg34`, throwaway (the code QA's L3) | 1080x2400 @ 420 (411 dp), 63 px status bar, keyguard bar 105 px | Android 14, Google APIs | `general Pixel (API 34)` | rows 230-390, x 87 / rows 231-391, x 89 - the same as Android 15's | 490 / 489 at 1.0; 500 / 500 at 1.3; 519 / 521 at 2.0 |

The large fonts (the code QA's L1; the date's last ink row, Android's / ours): `jelly36` 253 / 252 at 1.0, 259 / 258 at
1.3, 267 / 266 at 1.8, 270 / 269 at 2.0 (the build with 0.93; the clock on rows 105-196 at every font); the Android 14
image 519 at 2.0 (ours 521 there was the build with the whole growth, 1.0 - the first try; 0.93 puts it at 519.4 px).

- The navigation mode changes nothing on either side: SystemUI's clock and ours sit on the same rows with gestures and
  with 3-button navigation, as the model says (the keyguard bar is the top's only input).
- The time-rule screen (it shares `LockClockFace`) put its clock on the same rows as the glance on all three.
- Android's own clock on `gg61` sat 47 px (18 dp) below where the Android 16 numbers put it: that is how the legacy
  rule was found, before our glance was shot there; the run above is the build with it.
- Not matched, as on the measured profiles: the glyphs (Nunito Light against Google Sans), the date's weight (ours is
  heavier) and its language (the launcher's nb against the AVD's en-US), and the status bar's content (Android shows
  the carrier on its face).

Side by side (Android's left, ours right; the top of the screen, 300 px a phone):
`screens/glance-general/jelly-480x854-240dpi-android16.png`, `jelly-480x854-240dpi-android16-large-fonts.png` (1.8,
2.0), `tall-67in-1440x3120-560dpi-android16.png`, `six-61in-1080x2400-420dpi-android15.png`,
`six-61in-1080x2400-420dpi-android14.png` (1.0, 1.3, 2.0; its 2.0 row is the first try's, 2 px low).

## 7. Left open

- A real phone: no phone outside the emulators has been compared. The Jelly Star's own frame (Android 16, the
  single-line clock on) is still owed (59d step L); Unihertz calls its 16 update "a core system restructure" and says
  nothing about SystemUI, so its build may move or restyle the clock.
- Right to left and non-Latin digits keep the centred clock (§3); a mirrored copy is not built.
- Makers that restyle the keyguard (§5): only close; a measured profile per phone when one is in the house.
- The narrow 270 dp class keeps the centred clock (the bound). A smaller copy of Android's clock there would need
  Android's own behaviour at that width measured first.
- The status report: the face logs the model of a phone on the general profile once ("no measured profile ...");
  putting it into the status report would need a server change, left out of this round.

## 8. The code QA's findings (`docs/review/qa-96-code.md`: 0 High, 0 Medium, 6 Low), fixed

- **L1**: the date's shift comes from its real scaled size (`TypedValue.applyDimension`, non-linear since Android 14)
  times 0.93, fitted on frames at 1.3, 1.8 and 2.0 (`jelly36`, Android 16) and 1.3 and 2.0 (Android 14); the made-up
  0.85 row is gone, the test's rows are the measured ones.
- **L2**: the general date start is AOSP's `below_clock_padding_start`, 32 dp, for every maker (§3, §4).
- **L3**: the 18 dp checked on an Android 14 image (`gg34`, §6): the same as 15. The flag behind it is named (§2), and the
  general profile's log line carries the SDK.
- **L4**: the tablet bound is the smallest width; landscape keeps the centred clock (§3).
- **L5**: right to left and the Arabic-Indic and Persian digits keep the centred clock (§3, §7).
- **L6**: the Jelly Star's Android 16 (§3), the makers' overlays (§5); `81-sms-reactions.md`, `qa-81-code.md` and
  `fingerprint-over-the-glance-2026-11-11.md` corrected to it.
