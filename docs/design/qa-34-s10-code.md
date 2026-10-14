# QA 34 step 10 (code) - Kid Settings' sound choice as one segmented control (P9)

Reviewer: code QA, 2026-10-14. Branch `design-34-s10`, `ef8a0eac..b3685120` (one commit, launcher and docs only). I read
it against `docs/design/34-launcher-polish.md` (P9, §5 step 10, §7 L7, "Step 10 implementation status"),
`qa-34-design.md` (L7), mockup `06-kid-settings-after` (`34-mockups/src/06-kid-settings.html`), the root `CLAUDE.md`
and `launcher/CLAUDE.md` (Kid Settings, design 18's sound mode, `WallpaperInk`).

In the code I read:
- the whole diff;
- the old sound row in `KidSettingsActivity` side by side with the new one;
- `KidInk`, `WallpaperInk`, `WallpaperGround`, `bg_kid_card`, and the `KidManageLink` style;
- every kid screen that draws on the wallpaper (`WallpaperGround.apply`: Home, the phone book, Kid Settings), to find
  translucent surfaces.

Line numbers are `b3685120`'s. No product code was changed. The mutations were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge after a short fix round for L1-L2 (L3 if wanted). M1 is older than this step and belongs in its own
small step.** The control is behaviourally design 18's:
- **Taps.** The click listener is unchanged (`KidSettingsActivity.kt:130-136`). It calls `QuickControls.setSound(this,
  choice)` and then re-renders what the phone really has. `soundTapMode` is untouched.
- **What shows as chosen.** `renderSound` (`:257-282`) now reads the checked half and the enabled flag through
  `soundSegments(state)`. That function is exactly `state.selected` and `state.enabled`. Nothing is checked when the
  phone is fully silent or the mode is unknown.
- **Do Not Disturb.** Both halves are disabled together at 50 % alpha, and the note shows as before.
- **The radio semantics (L7).** The halves are still two `RadioButton`s (`button="@null"`) in a horizontal
  `RadioGroup`, so the framework supplies the radio role, the checked state and the two-item group. No delegate or
  class name is set. The group's content description is the name, and the visible label is
  `importantForAccessibility="no"`, so the name is read once. The halves stay focusable, so keyboard and switch access
  work as before (see N2 on how focus looks).
- **Layout.** `0dp` width with weight 1 and no margin gives two equal halves. `match_parent` inside the wrap-content
  `RadioGroup` triggers `LinearLayout`'s uniform-height pass, so both halves take the taller one's height. Each half
  has `minHeight` 48 dp. Nothing sets `maxLines`, `ellipsize` or `singleLine`, so labels wrap and the control grows
  without clipping. At 320 dp each half is 127 dp wide with 111 dp for the text. "Lydløs (vibrerer)" takes two lines
  at 1.0 and grows the control; at 411 dp it fits on one line. N1 covers how it breaks at 2.0.
- **Contrast in the normal case.** The chosen half is 9.87:1 (`kid_ground` on `kid_accent`), on any wallpaper. On
  every built-in, the other half reaches at least 6.25:1 (Fjord) through card, track and scrim. I recomputed both.

Counts: 0 High, 1 Medium, 3 Low, 3 Nits.

## What I ran

- From `launcher/`: `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` passed: 1247 tests, 0 failures
  (`SoundSegmentsTest` 9).
- An independent contrast script (Python, the same sRGB/linear formulas, 8-bit compositing as Android draws) for the
  numbers in M1 and L1.
- Four targeted mutations, each run against `SoundSegmentsTest`, `SoundModeTest`, `KidSettingsModelTest` and
  `KidPaletteTest`:

| # | Mutation | Result |
|---|---|---|
| 1 | The mapping: `soundButtons()` swapped (`kidSettingsSoundOn to SILENT_VIBRATE`, `...Vibrate to SOUND`) | killed (`the activity maps each half ...`) |
| 2 | `android:maxLines="1"` on `kid_settings_sound_vibrate` | killed (`two equal halves ...`) |
| 3 | The radio role overridden: `button.accessibilityDelegate = ButtonRole` as the second line of `styleSegment` | **survived** (L2) |
| 4 | The chosen colours keyed to the wrong state: `state_checked` -> `state_selected` in `styleSegment` (the chosen half never gets its fill or night text) | **survived** (L2) |

## Findings

### M1 (Medium, older than this step; a separate small step) - white ink on Kid Settings' card falls under 4.5:1 on mid-tone grounds

**Is it real? Yes.** I recomputed the implementer's numbers independently. `WallpaperInk.scrimFor`
(`WallpaperInk.kt:62`) picks the lightest scrim that gives the ink 4.5:1 on the bare ground. Kid Settings then puts
its labels on `bg_kid_card` (white 8 %), and the segment's track adds another white 8 %. Both layers lighten the ground
under white ink:

| Ground | Ink, scrim | Bare | On the card | On card + track |
|---|---|---|---|---|
| `#1C7ED6` (a parent's colour) | white, 0.05 | 4.56 | **3.99** | **3.52** |
| `#7048E8` | white, 0 | 5.55 | 4.81 | **4.21** |
| `#C92A2A` | white, 0 | 5.46 | 4.90 | **4.38** |
| photo whose light side has luminance 0.20 | white, 0.15 (photo minimum) | 5.41 | 4.54 | **3.90** |
| the bare-ground edge (luminance ~0.18-0.20) | white | 4.48-4.54 | **3.84-3.90** | **3.36-3.41** |
| built-ins (Fjord, the worst) | white, 0 | 9.59 | 7.66 | 6.25 |
| light grounds (dark ink) | dark | - | gets better | gets better |

The 4.48 in the last-but-one row shows a second, smaller flaw. `scrimArgb` truncates the alpha to 8 bits
(`WallpaperInk.kt:37`), but `scrimFor` judges the continuous alpha. So even the bare ground can end up a hair under
4.5:1. `lockScrim` already judges the drawn 8-bit alpha.

**Which surfaces are affected.**
- **Only Kid Settings.** `bg_kid_card` is the only translucent surface laid over the wallpaper on a kid screen. On
  that card the affected text is:
  - every label on the card: the Wi-Fi and Bluetooth switches, "Lyd på telefonen", the brightness label and the sound
    note;
  - the unchosen segment, which is lower again on the track.
- **Not affected:**
  - Home's music card (`#2A2233`) and call card are opaque.
  - The phone book has no card.
  - The lock screens use their own scrim and opaque cards.
- **Same card, worse:** the card's "Manage networks/devices" links are `kid_accent` whatever the ink (`KidManageLink`,
  `styles.xml:281`). They measure about 2.0:1 on the card over `#1C7ED6`, 1.97:1 over white and 1.77:1 over
  `#FFF3B0`.
- **What it takes to see it.** It shows only with a parent's mid-tone colour or photo. No built-in reaches it.

**Smallest robust fix (one place, the shared rule).** In `scrimFor`, judge **white** ink against the deepest kid surface
instead of the bare ground. That surface is the ground under the scrim with two white-8 % layers on top (the card plus
the track). Judge it at the drawn 8-bit alpha, as `lockScrim` does. Dark ink needs no change, because white layers only
help it.
- **Effect.** The one shared scrim gets slightly stronger, and only on mid-tone grounds. `#1C7ED6` goes from 0.05 to
  0.24. The edge grey goes from 0 to 0.21.
- **No change for:** built-ins (all stay at 0) and light grounds.
- **The 0.45 cap is never reached.** At 0.45, white ink still reaches 4.5:1 on card + track up to a ground luminance of
  0.41. That is well above the white/dark crossover (about 0.19), where dark ink takes over.
- **Why not a per-screen scrim.** A second scrim under the card only would split the ink rule in two.
- **Tests.**
  - Add `#1C7ED6`, the edge grey and a 0.20 photo to `WallpaperInkTest`, checked on bare, card and card + track.
  - `SoundSegmentsTest`'s contrast case can then take every ground, not only the built-ins.
- **The manage links.** Draw them in the ink (bold, as now). If peach is to stay, keep it only when the ink is white
  and peach reaches 4.5:1 on the card.

**Why a separate step.**
- The change is in the shared ink rule (`WallpaperInk`, `WallpaperInkTest`). It moves the scrim on all three kid
  screens (the ink and the status-bar icons stay the same). It is not part of P9's surface.
- The card has had the problem since step 8. This step's track only adds about 0.5:1 more on the one unchosen half.
- Do it before design 34's emulator pass, so the Kid Settings screenshots are taken once.

### L1 (Low) - on a light ground the chosen half is told apart only by a 2:1 fill

`styleSegment` (`KidSettingsActivity.kt:311-334`) drops the old 1 dp ink ring from the unchosen half. On a light ground
(dark ink), the two halves then differ by:
- the peach fill against the white track: 1.97:1 on `#FFFFFF`, 1.79:1 on `#FFF3B0`;
- the text colour: `kid_ground` `#0C0C14` against `INK_DARK` `#1B1B1F`, which is 1.13:1.

WCAG 1.4.11 asks 3:1 for the visual information that shows a state. No built-in is light, but a parent's colour or photo
can be, and the old design had a ring for exactly this case.

**Fix.** When `ink.darkInk`, give the checked `GradientDrawable` a 1 dp stroke in `ink.ink` (`#1B1B1F` against white is
17:1). A pure `segmentStroke(ink)` makes it testable. Add a light-ground row to `SoundSegmentsTest`: the chosen half's
outline must be at least 3:1 against the track.

### L2 (Low) - the chosen look and the "no role override" rule are not pinned (mutations 3 and 4 survive)

- **The chosen colours.**
  - **What the test checks.** It looks for the two strings `intArrayOf(getColor(R.color.kid_ground), ink.ink)` and
    `setColor(getColor(R.color.kid_accent))` (`SoundSegmentsTest.kt:247-248`).
  - **What survives.** It does not check that they are keyed to `state_checked`. Mutation 4 (`state_selected`) leaves
    the chosen half looking exactly like the other one, and every test passes. Putting the default `addState` first
    would survive the same way.
  - **Fix.** Assert `intArrayOf(android.R.attr.state_checked)` and the order of the `addState` calls. Better: extract
    a pure `segmentColours(ink)` (checked fill, checked text, unchecked text) that the activity applies and the test
    reads.
- **The role.**
  - **What the test checks.** The TalkBack scan (`SoundSegmentsTest.kt:214-220`) only looks at lines that contain
    "Sound" or "segment", plus three spelled-out `binding.kidSettingsSound*.setAccessibilityDelegate` calls.
  - **What survives.** Inside `styleSegment` the parameter is `button`, so `button.accessibilityDelegate = ButtonRole`
    (mutation 3) passes and TalkBack would say "button", not "radio button".
  - **Fix.** Scan `styleSegment`'s and `renderSound`'s bodies, or the whole activity, for `accessibilityDelegate`,
    `AccessibilityDelegate`, `ButtonRole` and `accessibilityClassName`.

### L3 (Low) - a test-only model in production code

`SoundSegments.kt:47-91` (`SegmentLayout`, `segmentLayout`, `linesFor`, about 45 lines) is used only by
`SoundSegmentsTest`. So are six of the eight constants at `:24-33`, which only mirror values written in the XML. Only
`SEGMENT_RADIUS_DP` is read by the activity. `PinLockLayoutTest` keeps its equivalent model inside the test.

**Fix.** Move the model and the mirror constants into `SoundSegmentsTest` (the test already parses the XML for the real
values). Keep `SEGMENT_RADIUS_DP`, or make it a `dimen`. Production loses about 55 lines and nothing else changes.

### N1 (Nit) - at font scale 2.0 on 320 dp, "(vibrerer)" / "(vibrate)" breaks between letters

At 2.0, a 111 dp text width holds about 6-7 Nunito 800 characters. "(vibrerer)" has 10 and "(vibrate)" has 9, so the
TextView splits the word with no hyphen: Android 13+ defaults `hyphenationFrequency` to none. Home's `tile_label` set
`hyphenationFrequency="normal"` for this reason (P4).

**Fix.** Add `android:hyphenationFrequency="normal"` to both halves, and add it to the layout test's attribute checks.

### N2 (Nit) - keyboard focus is nearly invisible on the chosen half

The halves have no `state_focused` item. Focus therefore shows only as `RippleDrawable`'s background wash: the ink at
12 %, which is barely visible on the peach half. That is still better than before this step, when the old
`StateListDrawable` showed nothing.

**Fix.** Add a `state_focused` 2 dp ring in the ink, or in `kid_accent` on the unchosen half, as P2 did for Home's cells.

### N3 (Nit) - `soundSegments` restates `soundRowState`

`soundSegments` (`SoundSegments.kt:45`) only maps `selected == it` and copies `enabled` to both halves. `renderSound`
could read `state.selected` and `state.enabled` directly, as it did before. `SoundModeTest` already pins the mapping and
design 18's taps. Keep `soundSegments` only if L3 leaves the file with a reason to exist. Otherwise `SOUND_SEGMENTS` and
`SEGMENT_RADIUS_DP` can live next to the activity.

## Left for the emulator pass

These items are already listed in the implementation status:
- TalkBack on the halves: "Lyd, valgt" and "Lydløs (vibrerer), ikke valgt", the group's name read once, and both halves
  read-only under DND.
- Touch-exploring the hidden visible label gives nothing. That is acceptable because the group carries the name.
- 320, 360 and 411 dp at font scale 1.0, 1.3 and 2.0, in nb and en (N1).
- The press.
- After M1: a `#1C7ED6` ground and a mid-tone photo.
