# Code QA of design 96 (the glance's general copy of Android's lock-screen clock)

Branch `glance-generic`, commits 8ab79dba, 29726efc, 25995886, b9c47e86 (`git diff main...glance-generic`). Read
against `docs/design/96-glance-general-clock.md`, 59d step L (§8.8, §8.11, §8.15), `docs/notes/launcher/lock-and-glance.md`,
`docs/testing/2026-10-23-aosp-lock-look.md` and `docs/setup/android-settings.md`. No emulator and no Gradle run: every
claim below comes from reading the code and AOSP, and from arithmetic on `res/font/nunito_light.ttf`'s tables, read
with a small parser.

**Result: 0 High, 0 Medium, 6 Low.** The arithmetic holds. Both bounds are right: 271 dp for Google and 274 dp for
the others. Nunito Light's digits are tabular, 0.600 em each with a 0.219 em colon (2.619 em), and the smallest first
bearing is 0.053 em ("4" and "8"). The general profile reproduces the three measured profiles exactly. The SDK gate
does what the design says.

## The real Jelly Star (the kid's phone)

- **Which Android it runs.** The Jelly Star launched on Android 13. Unihertz never shipped Android 14 or 15 for it: the
  update went straight from 13 to 16 ("Jelly Star Upgrades to Android 16", unihertz.com, 2026-08-31, security patch
  2026-07-05, rolled out in batches; it calls 13 "the older Android 13 version"). The launcher's `minSdk` is 34
  (`launcher/app/build.gradle.kts:97`), so it cannot be installed on 13. A Jelly Star that runs Vibb One is therefore on
  **Android 16, API 36**. The repo is not consistent about this: `81-sms-reactions.md:465` says "Android 14",
  `qa-81-code.md:460` says "13/14" and `fingerprint-over-the-glance-2026-11-11.md:116` says "Android 13". Against
  them: `02-calls.md:9`, design 56 §1 and `86-qr-preflight.md` ("needs its Android 16 OTA first"). See L6.
- **What `JELLY_STAR_CLOCK` is based on.** It was never measured on the real phone.
  - 59d step L (87b92992, 2026-10-23) first built it from the Pixel emulator's numbers.
  - The same day it was rebased on the pure AOSP Android 16 emulator image: `AOSP_EMULATOR_CLOCK.copy(name =
    "Jelly Star")` (`LockGlance.kt:464`), AVD `aosp36` at 480x854 and 240 dpi with the single-line clock
    (`2026-10-23-aosp-lock-look.md`).
  - Its comment still says "confirm on the phone". The phone's frame, `ro.product.model` and its density are all still
    owed.
  - `jelly36` (the user's twin `jellystar2`) is a Google Play image whose maker is Google. It takes
    `EMULATOR_PIXEL_CLOCK`, never `JELLY_STAR_CLOCK`.
- **What changes on the real phone with this branch.** On API 36 the gate (`sdk >= KEYGUARD_BLUEPRINT_SDK`) lets the
  measured profile through. So:
  - If the model string matches and the phone is at 240 dpi and 320 dp, the phone gets `JELLY_STAR_CLOCK`, as on
    main.
  - If the unverified model string does not match, or the density is not 240, main showed the centred 56 sp clock.
    The branch now shows "general AOSP", whose numbers are identical to `JELLY_STAR_CLOCK` (to 0.1 dp, and tested).
    That is a visible change on the phone, toward the intended copy.
  - The Android 14/15 "+18 dp" never applies to the Jelly Star: no supported Jelly Star runs below API 36.
  - The only other change is the date's baseline at a font scale other than 1.0: 4.2 dp lower at 1.3. That shift is
    modelled on the Pixel date. The pure AOSP face shows no date at all, so on the Jelly Star it matters only if
    Unihertz's build draws one.
- **Verdict.** The branch neither fixes nor breaks the real Jelly Star: the clock sits on the same spot as on main.
  Whether that spot is right on the real phone is exactly as unverified as before. It still waits for a frame of
  Unihertz's own keyguard (single-line clock on, per `docs/setup/android-settings.md`). Unihertz describes the 16
  update as "a core system restructure" and says nothing about SystemUI, so its build may move or restyle the clock.

## Findings

### L1 - Low - The date's font-scale shift assumes linear scaling; Android 14+ scales sp non-linearly

`LockGlance.kt:402-407` (`DATE_ASCENT_EM`, `profileDateBaselineBelowBarDp`), test `LockGlanceTest.kt:329`.

- **What the code does.** It moves the date by `(fontScale - 1) x 0.875 x 16` dp. That is "0.875 em of a 16 sp date
  growing linearly".
- **Why that is wrong from Android 14 on.** Since Android 14, sp sizes scale non-linearly from a font scale of 1.13
  (`FontScaleConverterFactory`, fromSp {8, 10, 12, 14, 18, 20, 24, 30, 100}). Its 1.3 table maps 16 sp to 20.2 dp, not
  20.8 dp. So the date really grows by 4.2 dp at 1.3, and the measured shift is also 4.2 dp. The data fits "the
  baseline moves by the whole size growth" just as well as the code's "0.875 of a linear growth". The two models agree
  at 1.15, 1.3 and 1.5 by coincidence of the tables (2.1, 4.2 and 7.0 dp), and part where the curves flatten:

  | Font scale | Code's model | Whole-growth model |
  |---|---|---|
  | 1.8 | 11.2 dp | 10.0 dp |
  | 2.0 | 14.0 dp | 12.0 dp |
  | 0.85 (linear, below the curves) | -2.1 dp | -2.4 dp |

- **Scenario.** A phone at the largest font, 2.0 (offered on 14+). Our date sits about 2 dp (7 px at 3.5) below
  Android's.
- **The test.** Its "0.85" row pins the formula, not a measurement.
- **Fix.** Derive the shift from the date's real scaled size:
  `TypedValue.applyDimension(COMPLEX_UNIT_SP, p.dateSp, metrics) / density - p.dateSp`, times a factor fitted at 1.3,
  which comes out at about 1.0. Then measure one frame at 2.0, or at least 1.8, before choosing. Drop the made-up 0.85
  row, or replace it with a measured one.

### L2 - Low - The general profile puts every non-Google maker's date 3.3 dp right of AOSP's date margin

`LockGlance.kt:531` (`DATE_LEFT_OF_CLOCK_INK_DP`), `LockGlance.kt:579`.

- **What the code does.** `dateStartDp = inkStart - 2`, which is 35.3 dp for every maker but Google.
- **What AOSP does.** Design 96's own table gives AOSP's date start as `below_clock_padding_start`, 32 dp
  (`KeyguardSmartspaceViewModel.getDateWeatherStartMargin`). That value does not depend on the clock font's bearing.
- **Where 35.3 came from.** It is the AOSP image's extrapolation: that face had no date, so the "2 dp left of the ink"
  rule was carried over from the Pixel.
- **Scenario.** A Motorola, Nokia or Fairphone on Android 16 that shows AOSP's date under the small clock. Our date
  starts 3.3 dp (about 9 px at 2.75) right of Android's. The clock itself is right.
- **Fix.** For the general profile, use AOSP's 32 dp date start (`AOSP_BELOW_CLOCK_PADDING_START_DP`) for every maker.
  Keep the measured profiles as they are until a phone with a date is measured. Or say in design 96 §3 and §5 that
  the non-Google date start is a guess.

### L3 - Low - Android 14's +18 dp is extrapolated from one Android 15 image, and the SDK is a proxy for a SystemUI flag

`LockGlance.kt:550-555`, `LockGlance.kt:572`; design 96 §2 ("Android 14 and 15 differ").

- **What was measured.** The legacy offset was measured once, on the Android 15 Google APIs image. Android 14 was
  never measured, but `minSdk` 34 makes 14 a supported floor.
- **What the code assumes.** Whether SystemUI places the clock by the blueprint (`ClockSection`) or by the legacy
  `KeyguardClockPositionAlgorithm` is a SystemUI build choice: an aconfig flag, not `SDK_INT`. A maker's 15 build with
  the blueprint, or a 16 build that kept the legacy path, gets the wrong 18 dp (27-47 px).
- **Scenario.** An Android 14 Motorola (in scope at `minSdk` 34) whose legacy keyguard does not add the second
  `keyguard_clock_top_margin`. The glance's clock sits 18 dp below Android's.
- **Fix.** Run one emulator check on an API 34 image (as on `gg61`) before release. Name the flag in design 96 §2.
  Optionally log the SDK with the general profile's line, so a mismatch can be traced from a phone's log.

### L4 - Low - The tablet bound reads the current width; AOSP's `sw600dp` is the smallest width

`LockGlance.kt:546` and `LockGlance.kt:591`, fed by `LockClockFace.kt:63` (`configuration.screenWidthDp`).

- **The mismatch.** AOSP's qualifier is `sw600dp`, the smallest width; the code uses the width as it is now.
  `PinLockActivity` is portrait-only, so the glance is safe. The time-rule screen (`ui/LockActivity`) rotates when the
  launcher's "rotate screen" preference is on (`UIObject`), and it is recreated on rotation.
- **Scenario.** With rotation on:
  - On a 411x914 dp phone held landscape, the time-rule screen is built at 914 dp, gets `null` and shows the centred
    clock. In portrait it shows the left-aligned copy, so the two screens no longer share one face.
  - On a 320x569 dp Jelly Star in landscape (569 dp), it gets the general profile, a 86 dp clock on a 320 dp-high
    screen. Android's keyguard on a phone does not rotate at all.
- **Fix.** Test `smallestScreenWidthDp < 600` for the tablet bound, and keep `screenWidthDp` for the fit. On a
  landscape phone, decide either way (the centred clock is probably the calmer choice), but on purpose.

### L5 - Low - RTL and non-Latin digits now reach the profile path on every phone

`LockClockFace.kt:307-311` and `LockClockFace.kt:333` (`clockStartPx`, `contentStart`), `LockGlance.kt:611`
(`startMarginPx`), `LockGlance.kt:591` (the bound).

- **Why it is reachable.** The launcher's language may be "follow the phone" (`resolveLauncherLocale("")`), and the
  manifest has `supportsRtl="true"`. A phone in Arabic, Hebrew, Persian or Urdu therefore lays the face out RTL.
- **What goes wrong in RTL.**
  - `marginStart` is then the right margin, but `contentStart` is computed from the left (`at[0]`).
  - The bearing that is subtracted is the first character's *left* bearing (`getTextBounds(text, 0, 1)`). Under RTL
    the edge that counts is the right edge of the last digit.
  - Android mirrors its clock to the right edge at 28 dp. Ours lands near the right edge, off by up to the digits'
    bearing difference (0.07 em, about 6 dp).
- **What goes wrong with the digits.** A locale whose clock uses Arabic-Indic or Persian digits falls back from Nunito
  Light, which has no U+0660 or U+06F0. The bound's 2.619 em is then not the real width.
- **History.** This was already possible on main for a Jelly Star at 320 dp. Design 96 makes it every phone.
- **Fix.** Under `layoutDirection == RTL`, measure from the right edge (`root.width - ...`) with the last glyph's right
  bearing, or keep the centred clock for RTL. Compute the bound from `clock.paint.measureText("00:00")` in the actual
  locale rather than a constant. Or state that the copy is LTR-only in design 96 §7.

### L6 - Low - Docs: the real phone's Android version is stated three ways, and design 96 never says which applies

Design 96 §3 ("The measured ones were all taken on Android 16, so they apply from API 36 only") and §7; `81-sms-reactions.md:465`;
`qa-81-code.md:460`; `fingerprint-over-the-glance-2026-11-11.md:116`.

- **The risk.** A reader of design 96 can take the API 36 gate to mean the Jelly Star's own profile is now skipped on
  the kid's phone ("Android 13/14" per those notes). That is not the case: see the section above.
- **Fix.**
  - Add a line to design 96 §3 or §7: "The Jelly Star went from 13 straight to 16 (Unihertz, 2026-08-31); with
    `minSdk` 34 it runs Vibb One only on 16, so it keeps `JELLY_STAR_CLOCK`."
  - Correct the three notes, or point them at design 56 §1.
  - Separately: design 96 §2's claim that no phone-size qualifier overrides these dimensions is about AOSP's own
    `res/`. A maker's device overlay (RRO, e.g. `status_bar_header_height_keyguard` or `keyguard_clock_top_margin` in
    a device tree) can still change them, and no emulator carries one. Mention it next to "Matches" in §5.

## Checked, no finding

- **The measured profiles.** `glanceClockProfile` takes a measured profile only from API 36, else the general one;
  `null` only from the bound. `jelly36` (`sdk_gphone*` by Google at 240 dpi and 320 dp) still gets `EMULATOR_PIXEL_CLOCK`.
  The general profile's numbers equal the measured literals: 34 / 91.3 / 32 / 128.7 for Google and 37.3 / 91.3 / 35.3
  / 128.7 for the others. `AOSP_GLANCE_HANDLE` equals both measured handles. The test compares them against
  independently typed literals, so it is not tautological.
- **The bounds.** Google: 28 + 6 + (2.619 - 0.053) x 86 = 254.68 <= w - 16, so w >= 271. Others: 257.98, so w >= 274. The
  clock's format is `HH:mm` or `h:mm` with no AM/PM, so nothing wider exists. The narrow 270 dp class keeps the centred
  clock, as in the screen-size run.
- **API edges.** Android 13 and below cannot install (`minSdk` 34). The `sdk < 36` path covers them anyway. API 37+
  takes the blueprint place. Design 96 §5 already records the Pixel's `clockReactiveSmartspaceLayout` (the date
  beside the clock).
- **Configuration changes.** `PinLockActivity` handles `density|screenSize|fontScale` itself, and the profile is read
  once per `LockClockFace`. It ends with `finishAndRemoveTask()` at every unlock, though, and a display-size change
  happens while unlocked, so the next lock builds a fresh face. The font scale is read at each `place`, and `refit()`
  places again. `LockActivity` is recreated on everything but `uiMode`.
- **The handle.** It now comes from the profile (10 dp, 92 %) on every profile phone, instead of
  `DEFAULT_GLANCE_HANDLE` (8 dp, 80 %). The 3-button path (fix S5) is unchanged.
- **Vertical room.** The 86 dp clock on a short screen in the 274-300 dp class (a Jelly Star at "Larger" display size,
  about 274x488 dp) leaves less room below. The glance's rows sit in `lockGlanceScroll`, and `fitNext` drops the
  "Neste" line when the third row would leave sight, so nothing is cut. Worth a shot in the next emulator round.
- **The double-line clock.** Android's default face on AOSP and Pixel is the large double-line clock. The copy matches
  only with "Double-line clock" off, which is already a per-phone setup step (`docs/setup/android-settings.md`) and
  applies to every phone the general profile reaches.

Sources for the Jelly Star: unihertz.com, "Jelly Star Upgrades to Android 16" (2026-08-31); unihertz.com, "Unihertz
Announces Planned Android 16 Upgrades for Titan 2 and Jelly Star"; devicespecifications.com news 007c1904. AOSP
`android14-release` `core/java/android/content/res/FontScaleConverterFactory.java` (the tables in L1),
`packages/SystemUI/res-keyguard/layout/keyguard_clock_switch.xml`.
