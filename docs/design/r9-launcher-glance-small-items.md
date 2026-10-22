# Launcher round 9 - the lock's glance page and touch sounds (proposal)

Asked 2026-10-21/22 (PLAN "The lock's glance page, three things" and "Touch sounds and vibration on Kid Settings").
Launcher, plus the server's quick-controls bit for §4. Mockup: `r9-glance-mockup.html` (scratchpad; suggested home
`docs/design/mockups/r9-glance.html`) - today's page vs the proposal at 320x569 dp. Every part keeps working when design
59 turns the glance into a `showWhenLocked` face over Android's keyguard: nothing here touches our keypad stage;
"Lås opp" only changes what it opens.

## 1. Bug: the bottom row is cut off

**What the user saw** (320x569 dp, music tile full, one WhatsApp missed-call row): "Nødsamtale" and the flashlight
below the screen's bottom edge.

**Cause.** `targetSdk` is 36, so the lock's window is edge-to-edge: the glance root is always the full window height
and the system bars arrive only as its padding (`KidInsets.apply`, `getInsets(systemBars | displayCutout)` - visible
bars only). `GlancePanel` refits only when the root's **height** changes (`addOnLayoutChangeListener`: `bottom - top !=
fitFor`), but `fitNow` uses `available = height - paddingTop - paddingBottom`. A padding change after a fit - a bar
reported visible only after the fit, a gesture <-> 3-button switch - re-lays the page at the same height and never
refits; the page keeps a fit made for up to 72 dp more room, and the weighted `Space` can't shrink below 0, so the
bottom row is pushed past the padding (`clipToPadding` cuts it). The numbers say this is what happened: with both
bars counted (24 + 48 dp: 481 dp available) the full tile + one row needs ~499 dp even with the small clock, so the fit
can't choose it - the compact tile would show; with no bars counted (553 dp) candidate 1 (~521 dp) fits, and drawn in
481 dp it overflows by ~40 dp, the bottom row. The likely moment is the lock-up: the lock is started while dark
(design 32) and fitted at the screen-on, around which SystemUI reports the bars visible. QA Low 4's "again after
gesture <-> 3-button navigation" never worked for the same reason (the height doesn't change under edge-to-edge).

**The two suspects are not it.** `fixed` is measured from `lock_glance_unlock` + `lock_glance_bottom` with its 12 dp
margin (pinned 116 dp), and the full tile's measured height includes its time line (pinned 233.8 dp); the panel
measures the real views, the test's 1.4 em model is only an upper bound on Nunito's 1.364. Both changes did use up
slack (the full tile alone keeps 3.2 dp at 473 dp in the model), which is why a stale inset now shows at once.

**Fix** (`GlancePanel`): the fit is keyed on the available height, not the root's. One pure helper
`glanceAvailablePx(height, paddingTop, paddingBottom)` (in `LockGlance.kt`); `fitFor` holds its value; the layout
listener refits when `glanceAvailablePx(bottom - top, v.paddingTop, v.paddingBottom)` differs (a `setPadding` from the
insets listener requests a layout, so the listener runs). The fit's log line gains the available height and the two
paddings in dp ("Glance fit: candidate 2, 1 rows, 0 more, 1 visible, available 457 dp (pad 56/56)") - counts and
dp only (`LockGlanceGuardTest`). §2 then makes the bottom block safe whatever any fit says.

Tests: `LockGlanceLayoutTest` pins by source that the listener and `fitNow` both go through `glanceAvailablePx` and
that the listener no longer compares the bare height; `glanceAvailablePx` itself is one assertion.

## 2. The bottom block stays put; the rows scroll

**Behaviour.** "Lås opp", "Nødsamtale" and the flashlight sit at the bottom whatever the content. Clock, date, alarm,
the tiles and the rows share what is left; the rows scroll inside it when they don't fit. This changes design 25's
"the page never scrolls" for the rows only: the clock, date and tiles are never scrolled - the fit still picks their
sizes. A drag on the rows scrolls them; a tap on a row still leads to the keypad with its target (a drag past the
touch slop is no tap, the ScrollView's own rule). At every lock-up the rows start at the top.

**Layout** (`view_lock_glance.xml`): the root keeps its paddings and gets two children above the bottom block -

```
lock_glance_root (vertical, insets as padding)
  lock_glance_top   LinearLayout vertical, match_parent x 0dp weight 1, center_horizontal
    clock, date, alarm, lock_media_tile, lock_media_compact, lock_timer_tile   (as today)
    lock_glance_scroll  ScrollView match_parent x 0dp weight 1, marginTop 14dp (22 without a tile, set by the panel),
                        requiresFadingEdge="vertical", fadingEdgeLength="24dp"
      lock_glance_list  (lock_glance_rows + lock_glance_more, as today)
  lock_glance_unlock  (56 dp, as today)
  lock_glance_bottom  (marginTop 12dp, as today)
```

The `Space` goes (the scroll's weight takes its place, so rows still sit right under the tiles). The non-weighted
bottom block is always measured whole; the top block gets the rest and clips its own overflow, so even a wrong fit
can never push the bottom row off. The fading edge only shows while there is more to scroll - the calm cue that a row
continues below (it works over a photo wallpaper: View's alpha fade).

**The fit** (`glanceFit`, pure; `GlanceFit` gains `visibleRows`). `rows` is now always `min(n, 3)` (every row is in the
list) and `more` = `n - rows`; the rows are no longer folded into "+ N varsler til" to make room - the "more" line
only counts past three, as today with room. What a candidate counts is `visibleRows` rows: all of them, one, or none.
The candidates, first that fits wins:

1. big clock, date, full tile, every row
2. small clock, date, full tile, every row
3. small clock, date, full tile, one row visible (the rest scrolls)
4. small clock, date, compact tile, every row
5. small clock, date, compact tile, one row visible
6. small clock, no date, compact tile, one row visible
7. small clock, no date, compact tile, no row counted (the list gets what is left; the last resort)

Without a music tile the tile steps are the same as their neighbours (4 = 2, 5 = 3: duplicates, harmless, first wins).
With a running timer (design 42) the seven with the timer come first, then the seven without it - the timer still
outlives the music tile's size and the rows (it now also outlives the date, one line of code less). Old candidate 3
(big clock with the compact tile) goes. Candidate 3 keeps §3's new tile with the rows scrolling - the mockup's state;
open question 1 is whether that order is right.

`GlancePanel.apply`: every shown row `VISIBLE` (no more hiding by `fit.rows`), the scroll `GONE` with no rows, its top
margin as today's list margin. `resumeNow`'s lock-up branch adds `lockGlanceScroll.scrollTo(0, 0)`. Nothing else in
the panel changes (`rowViews`, `bindRows`, the coalescer, `glanceRefitDue`).

**Edge cases.** Many rows: three plus the "more" line, scrollable. Font scale 2.0 on 320x569: the fit falls to 6/7,
the rows' area may be under one row - still scrollable, the bottom block whole. No music: the full-width rows as
today. TalkBack: the ScrollView is a node with scroll actions; moving the focus onto a row below the edge scrolls it
into view (framework); the root still has no node, every row view stays data-sensitive. Landscape: the lock is
portrait-only. Design 59: the page is the keyguard face; the scroll and the fixed block carry over unchanged.

**Tests.** `LockGlanceTest`: its model's `tileFull` becomes §3's height; "the fit's candidates in order", "less room"
(now: the rows scroll, then the date, then nothing counted - never a row folded below three) and the timer test
rewritten to the list above. `LockGlanceLayoutTest`: `height()` learns `ScrollView` (its child's height), `rowsTop`
from `lock_glance_scroll`; the order test (bottom block the root's last two children, the scroll the top block's last,
`lock_glance_list` inside it); the scroll's fading edge and weight; and "the expected fits at 473 and 497 dp" against
the new tile (tileFull 174.8, tileCompact 90, fixed 116; model available 457/481):

| case | 473 dp | 497 dp |
|---|---|---|
| full tile alone | 1 (410.8) | 1 |
| no music, 4 rows | 1, 3 rows + 1 more (466) | 1 |
| music + 1 row (the user's case) | 2 (458.4) | 1 (480.8) |
| music + 2 rows (mockup) | 3, 1 visible (458.4) | 3, 1 visible |
| music + 3 rows | 3, 1 visible | 3, 1 visible |

(Totals: the counted height + the 16 dp paddings.) The implementer re-derives them from the XML as round 6 did and
writes them into the test.

## 3. The compact music player (items 3 and 4)

**Behaviour** (the iPhone 16's lock player; mockup right): one tile, in `bg_music_tile` as today -

- a 48 dp cover (`bg_music_art`, 12-14 dp corners; the note glyph 24 dp when there's no art), the title (15 sp
  extrabold, `kid_ink`) and the artist/entry (13 sp, `kid_core_dim`) on one line each, ellipsized - no wrap;
- one progress row: elapsed left, the bar (4 dp, the existing `bg_music_progress`), the time left right-aligned with a
  minus (`1:26 ━━━━── −2:38`; U+2212). This replaces the time line under the bar and round 6's "1:26 · 4:04"; a live
  stream or unknown duration shows the elapsed time alone and no minus side;
- previous / play-pause / next as plain glyphs in `kid_ink` on one row, no discs: 48 dp targets with ~26 dp glyphs,
  play 56 dp with a ~34 dp glyph, 28 dp apart, a press ripple masked to the target's circle (new `bg_glance_glyph`:
  a `ripple` with an oval mask and no fill). No output/AirPlay button - the lock opens nothing.

Model height: 12 + 48 + 10 + 16.8 + 2 + 56 + 12 = 156.8 + the 18 dp margin = **174.8 dp** (today 233.8: a quarter
less). The one-row compact tile (52 dp art, play only) stays as the fit's fallback (open question 2), unchanged.

**Code.**
- `view_lock_glance.xml` `lock_media_tile` rewritten with the same ids for what stays (`lock_media_art`, `_glyph`,
  `_item` - `maxLines` 1 -, `_entry`, `_progress` - `0dp` weight 1 in the row -, `_previous`, `_play`, `_next`); new
  `lock_media_elapsed` and `lock_media_remaining` (12 sp bold, `kid_core_dim`, `tnum`, one line,
  `importantForAccessibility="no"`, an initial `40dp` width, never `wrap_content`); `lock_media_time` goes.
  `bg_music_art_large` goes (no other user); `bg_music_control`/`bg_music_play` stay (the compact tile, the torch).
- `LockGlance.kt`: `glanceTimeLine` is replaced by `glanceTimes(positionMs, durationMs): GlanceTimes(elapsed: String,
  remaining: String?)` - remaining from whole seconds (`durationMs / 1000 - positionMs / 1000`, at least 0, through
  `clockText`), so elapsed + remaining always add up to the duration; `null` without a duration - and
  `glanceTimeWidest(durationMs): String`, the duration's shape with every digit "0" ("0:00", "00:00", "0:00:00";
  "0:00:00" without a duration). With tabular figures every time of that shape is as wide.
- `MusicLockPanel.render()`: each time view's width = `ceil(paint.measureText(widest))` (`"−" + widest` for the
  remaining), set through `layoutParams` only when the px differ - a new track, a duration that became known, a font
  scale change (`refit` -> `show` -> `render`); the remaining view `GONE` without a duration. `tick()` only sets the
  two texts and the bar: with a fixed width a `setText` never asks for a layout (round 6's rule, kept). The bar's
  content description stays "1:26 av 4:04" (`music_time_of`).
- `glanceRefitDue` already refits when the tile's measured height changes (a font scale that grows the header).

**Edge cases.** Long titles: one line, "…"; TalkBack reads the full text. Paused: the play glyph, no tick. A Storytel
book over 10 h at font scale 2.0 on 320 dp: two ~110 dp times leave the bar ~40 dp - still drawn, nothing ellipsized
(accepted; no special rule). No music: no tile. TalkBack: the three buttons keep their descriptions ("Forrige",
"Spill"/"Pause", "Neste"), the bar its time; the two time texts are not read twice. Home's card is untouched.

**Tests** (`LockGlanceTest`/`LockGlanceLayoutTest`): `glanceTimes` (a normal track, the seconds adding up, position
past the end, no duration, over an hour) and `glanceTimeWidest`; the tile's height 174.8; the progress row is one
horizontal row elapsed - bar - remaining, the two times one line of `tnum` with a fixed width and not important for
accessibility, the bar weighted; the controls 48/56/48 dp with no disc background and `kid_ink` tints, and their width
at 320/360/411 (208 dp); no `lock_media_time` left. `KidPaletteTest`: the glyphs' ink on `kid_music_tile`.

## 4. Touch sounds and vibration on Kid Settings

**Behaviour.** A new card section under "Lyd på telefonen", only when the parent ticked it (a quick-controls bit like
design 18's), never on the PIN lock or the time-rule screen (Kid Settings already redirects under a lock):

- "Lyd ved trykk" / "Touch sounds" - a switch on `Settings.System.SOUND_EFFECTS_ENABLED` (Android's "Touch sounds":
  every view's click sound, Home's tiles, our PIN keys and phone book included - they already follow it);
- "Vibrasjon ved trykk" / "Vibrate on touch" - a switch on `HAPTIC_FEEDBACK_ENABLED`; no row without a vibrator
  (`hasVibrator`, as design 18);
- one dim line: "Tastaturet har egen lyd og vibrasjon i sine innstillinger." / "The keyboard has its own sound and
  vibration in its settings." - HeliBoard plays its key click with an explicit volume, which Android doesn't gate on
  this setting; nothing is opened.

**`DTMF_TONE_WHEN_DIALING` doesn't belong**: it is only a dial pad's local tones, and Vibb has no dial pad (the phone
book calls a contact; the call screen has no keypad); the only one the kid reaches is the system emergency dialer.
Left alone.

**Writable only with the app-op** design 34 P12 already needs (`WRITE_SETTINGS`, `Settings.System.canWrite`; both
keys are public, so an app may write them with it). Without it the switches show the phone's state, disabled at 50 %
alpha, with "Kan ikke endres på denne telefonen ennå." / "Can't be changed on this phone yet." Not a restriction: the
bit only shows the row, the override/pause change nothing, the parent's bit going off leaves the setting as it is.

**Launcher code.**
- `server/QuickControls.kt`: `QuickControlFeature.TOUCH = 16`; `touchState(context)` (reads both keys, missing =
  on - Android's defaults), `setTouchSounds(context, on)` / `setTouchVibration(context, on)`: `putInt` in a
  `try` (`SecurityException`, `IllegalArgumentException` -> false), and for sounds `AudioManager.loadSoundEffects()` /
  `unloadSoundEffects()` as Android's own Settings does; the page re-reads after the tap, so a refused write flips
  the switch back.
- Pure `server/TouchFeedback.kt` (`TouchFeedbackTest`): `touchRowState(sounds: Int?, haptic: Int?, hasVibrator,
  canWrite): TouchRowState(sounds: Boolean, vibration: Boolean?, writable: Boolean)`.
- `KidSettingsModel.kt`: `ControlsSection.Rows.touch`, `describe()`, mask 16 alone is a card; `KidSettingsActivity`
  `renderTouch` (re-read on resume and after a tap; no observer - the kid can't change them elsewhere while the page
  shows), `activity_kid_settings.xml` `kid_settings_touch_row` after the sound row: two `SwitchCompat`s in
  `KidSwitchRow`, the two notes in the sound note's style, `renderInk` covers them. Strings in both files.
- TalkBack: the switches' own role and state; disabled reads "deaktivert", then the note.

**Server code** (one commit with the launcher's, the standing rule; no JSON key changes, so `policy_json_keys_snapshot`
and `PolicyResponseCompatTest` stay as they are): `handlers/devices.rs` `QUICK_CONTROL_TOUCH: i64 = 16`, in
`DEFAULT_QUICK_CONTROLS` (a new phone gets every row, as design 18 did; existing phones keep their mask, no
migration), the policy form's `quick_control_touch`; `device_pages.rs` `quick_touch`; `device_home.html` a fifth
switch row "Touch sounds and vibration" in the same `data-autosave` form (it saves in place; its `#quick-controls`
return keeps the place - never a jump to the top), and the ⓘ panel's line: "Touch sounds and vibration: Android's
switches for taps. The keyboard has its own. They need the same "Modify system settings" permission as the clock
format; without it your child sees them but can't change them." An older launcher ignores the bit.

**Tests.** `TouchFeedbackTest` (the state table; by source, `Settings.System.putInt` of these two keys only in
`QuickControls.setTouch*`), `KidSettingsModelTest` (bit 16, 16 alone a card), server: the quick form with
`quick_control_touch` stores 16 (beside the existing quick-section test), a new phone's mask is 31
(`tests/device_api.rs`, was 15), the Home Screen page renders the row checked from the mask.

**Carry-over and unverified.** Android 13+'s "Touch feedback" writes the hidden `HAPTIC_FEEDBACK_INTENSITY` too;
`VibrationSettings` treats `HAPTIC_FEEDBACK_ENABLED = 0` as off [device check: on the emulator and the Jelly Star our
off silences long-press haptics, our on brings them back]. Under design 59 the keyguard's PIN pad follows the same two
system switches [unverified].

## 5. Emulator check list (for the user)

1. **The bug**: 320x569 dp, 3-button navigation, music playing, one WhatsApp missed-call row; screen off/on five
   times - the bottom row always whole; `adb logcat -s GlancePanel` shows a refit with the bars' padding after the
   screen-on. Switch to gesture navigation with the lock up: the page refits.
2. **Fixed bottom**: 0, 1, 2, 3 and 5 rows, with and without music, with a running timer; a drag scrolls the rows, a
   tap opens the keypad with the right hint, a drag that ends on a row opens nothing; the next lock-up starts at the
   top; the fade shows only with more below. Font scale 1.0/1.3/2.0 at 320 and 411 dp: "Lås opp", "Nødsamtale" and the
   flashlight always whole.
3. **Player**: a long title (one line), paused/playing, an NRK live stream (elapsed only), a Storytel book over an
   hour ("1:02:13 … −4:10:05"), the times steady at each tick (no jitter, no jump of the bar); previous/play/next as
   48/56 dp targets; TalkBack on the tile and down through the rows (they scroll into view).
4. **Touch row**: tick "Touch sounds and vibration" in the PWA (the page stays where it was) - the section shows;
   toggles change Home's tap sounds and the long-press vibration; `adb shell appops set me.vibb.launcher
   WRITE_SETTINGS default` - read-only with the note; HeliBoard's key sound unaffected; untick - the section goes,
   the phone keeps the setting.

## 6. Not in scope

- Every row in the scroll (more than three) - the "+ N varsler til" line stays (open question 3).
- Key haptics on our own PIN keypad (`performHapticFeedback(KEYBOARD_TAP)`) - a new behaviour, not a switch.
- A status field for whether the touch settings are writable (the clock format's `clock_format_applied` warns already).
- The flashlight's disc, Home's music card and the compact tile's look.

## 7. Open questions for the user

1. **The order when music and rows compete**: the proposal keeps the new player and lets the rows scroll from one
   visible row (the mockup) before it shrinks to the one-row compact tile. Or the other way round (design 25's order:
   compact first, so every row shows)? Recommended: as proposed.
2. **Keep the one-row compact tile** as the last resort (font scale 2.0, small screens - without it the rows can get no
   room at all)? Recommended: keep it, unchanged.
3. **More than three rows** now that they scroll: keep "at most three + '+ N varsler til'" (recommended for this
   round), or list every conversation?
