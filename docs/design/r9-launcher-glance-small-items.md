# Launcher round 9 - the lock's glance page and touch sounds (proposal, revised after `qa-r9-design.md`)

Asked 2026-10-21/22 (PLAN "The lock's glance page, three things" and "Touch sounds and vibration on Kid Settings").
Launcher, plus the server's quick-controls bit for §4. Mockup (today's page vs the proposal at 320x569 dp):
https://claude.ai/artifact/Kf86Nv4Rub2gDsguLKy2dr - not committed. Everything here carries over when design 59 makes
the glance a `showWhenLocked` face over Android's keyguard: nothing depends on our keypad stage. The one tie left is
"Nødsamtale", which clicks the keypad's button (`PinLockActivity.kt:287`, `binding.pinEmergency.performClick()`) -
for design 59's list: under the hybrid it calls `EmergencyCall.confirm` directly.

## 1. Bug: the bottom row is cut off

**What the user saw** (320x569 dp, music tile full, one WhatsApp missed-call row): "Nødsamtale" and the flashlight
below the screen's bottom edge.

**Cause.** `targetSdk` is 36, so the lock's window is edge-to-edge: the glance root is always the full window height
and the system bars arrive only as its padding (`KidInsets.apply`, `getInsets(systemBars | displayCutout)` - visible
bars only). `GlancePanel` refits only when the root's **height** changes (`GlancePanel.kt:172-173`: `bottom - top !=
fitFor`), but `fitNow` uses `available = height - paddingTop - paddingBottom` (`:640`). A padding change after a fit -
a bar reported visible only after the fit, a gesture <-> 3-button switch - re-lays the page at the same height and
never refits; the page keeps a fit made for up to 72 dp more room, the weighted `Space` can't go below 0, and the
bottom row is pushed past the padding (`clipToPadding` cuts it). The numbers fit: in the pinned model the full tile +
one row needs 501.4 dp with the small clock and 523.8 dp with the big one; with both bars counted (24 + 48 dp: 481 dp)
the fit can't choose it, with no bars counted (553 dp) candidate 1 fits, and drawn in 481 dp it overflows by ~43 dp -
the bottom row. The likely moment is the lock-up: the lock is started while dark (design 32) and fitted around the
screen-on, when SystemUI reports the bars visible. QA Low 4's "again after gesture <-> 3-button navigation" never
worked for the same reason.

**The two suspects are not it.** `fixed` is measured from `lock_glance_unlock` + `lock_glance_bottom` with its 12 dp
margin (pinned 116 dp), and the full tile's measured height includes its time line (pinned 233.8 dp); the panel
measures the real views. Both changes used up slack (the full tile alone keeps 3.2 dp at 473 dp in the model), which
is why a stale inset now shows at once.

**Fix** (`GlancePanel`): the fit is keyed on the available height. A private `available(height)` (height minus the
root's paddings) is called by both the layout listener and `fitNow`; `fitFor` holds its value; the listener refits
when it differs (`setPadding` from the insets listener requests a layout, so the listener runs). The log line is
written when the fit **or the available height** changes, with the available height and both paddings in dp ("Glance
fit: candidate 2, 1 rows, 0 more, 1 visible, available 457 dp (pad 56/56)" - counts and dp only,
`LockGlanceGuardTest`; the example is the 473 dp case below: 8 dp of the page's own padding plus a 48 dp status bar at
the top and 3-button navigation's 48 dp at the bottom). §2 then keeps the bottom block on screen whatever a fit says. Test: `LockGlanceLayoutTest`
pins by source that the listener and `fitNow` both call `available(` and the listener no longer compares the bare
height.

## 2. The bottom block stays put; the rows scroll

**Behaviour.** "Lås opp", "Nødsamtale" and the flashlight sit at the bottom whatever the content. Clock, date, alarm,
the tiles and the rows share what is left; the rows scroll inside it when they don't fit. This changes design 25's
"the page never scrolls" for the rows only - the fit still picks the clock's, date's and tiles' sizes. A drag scrolls
the rows; a tap on a row still leads to the keypad with its target (the ScrollView's slop keeps a drag from being a
tap; the lock has no gesture detector to compete with, and `onUserInteraction` still sees every DOWN). Every lock-up
starts the rows at the top.

**Layout** (`view_lock_glance.xml`):

```
lock_glance_root (vertical, insets as padding)
  lock_glance_top   LinearLayout vertical, match_parent x 0dp weight 1, center_horizontal
    clock, date, alarm, lock_media_tile, lock_media_compact, lock_timer_tile   (as today)
    lock_glance_scroll  ScrollView match_parent x 0dp weight 1, marginTop 14dp (22 without a tile, set by the panel),
                        requiresFadingEdge="vertical", fadingEdgeLength="16dp"  (the contacts row's CONTACTS_FADE_DP)
      lock_glance_list  (lock_glance_rows + lock_glance_more, as today)
  lock_glance_unlock  (56 dp, as today)
  lock_glance_bottom  (marginTop 12dp, as today)
```

The `Space` goes (the scroll's weight takes its place, so rows still sit right under the tiles). The bottom block is
measured whole; the top block gets what is left and clips its own overflow, so a wrong fit can't push the bottom row
off. The fade shows only while there is more below - the calm cue that a row continues.

**The fit** (`glanceFit`, pure; `GlanceFit` gains `visibleRows`). `rows` is `min(n, 3)` in every candidate but 7, and
`more` = `n - rows`: rows are no longer folded into "+ N varsler til" to make room. A candidate counts `visibleRows`
rows - all of them (with the "more" line), or one (without it: the line sits in the scroll after row 3). First that
fits wins:

1. big clock, date, full tile, every row
2. small clock, date, full tile, every row
3. small clock, date, full tile, one row visible (the rest scrolls)
4. small clock, date, compact tile, every row
5. small clock, date, compact tile, one row visible
6. small clock, no date, compact tile, one row visible
7. small clock, no date, compact tile, no rows - only the "more" line counting all `n` (design 25's candidate 7, so
   the last resort is a line, never a faded sliver)

Without a music tile 4 = 2 and 5 = 3 (harmless duplicates). With a running timer the seven with the timer come first,
then the seven without it - **a change to design 42 §1.6**: the timer now also outlives the date (it kept the date in
every timer candidate). Old candidate 3 (big clock + compact tile) goes. `GlancePanel.apply`: `i < fit.rows` stays
(it hides the rows only in 7); the top-margin and `GONE` logic moves from `lock_glance_list` to `lock_glance_scroll`,
which is `GONE` only with no row at all (n = 0, `rows == 0 && more == 0` as today - candidate 7 binds none but shows
the "more" line in the scroll); `resumeNow`'s lock-up branch adds `lockGlanceScroll.scrollTo(0, 0)`. Nothing else in
the panel changes.

**Edge cases.** Three rows and the "more" line scroll. Font scale 2.0 at 320x569: the compact tile and candidate 6/7,
the bottom block whole. TalkBack: the ScrollView is a node with scroll actions and focus moving onto a row below
scrolls it into view; the root has no node, rows stay data-sensitive. The lock is portrait-only.

**Tests.** `LockGlanceTest`: the model's `tileFull` becomes §3's height; the candidate order, "less room" (one row
visible, then no date, then only the "more" line - never a row folded below three) and the timer test (the date goes
before the timer) rewritten. `LockGlanceLayoutTest`: `rowsTop` from `lock_glance_scroll`; the order test pins the
bottom block as the root's last two children, `lock_glance_top`/`lock_glance_scroll` as `0dp` + weight 1, the scroll
last in the top block with `lock_glance_list` in it, the 16 dp fade; and the fits (tileFull 174.8, tileCompact 90,
fixed 116; model available 457/481; totals = counted height + 16 dp paddings; 473 dp = 569 less a 48 dp status bar - the
Jelly Star's, with its cutout - and 3-button navigation's 48 dp, 497 dp the same with gesture navigation's 24 dp):

| case | 473 dp | 497 dp |
|---|---|---|
| full tile alone | 1 (410.8) | 1 |
| no music, 4 rows | 1, 3 rows + 1 more (466) | 1 |
| music + 1 row (the user's case) | 2 (458.4) | 1 (480.8) |
| music + 2 rows (mockup) | 3, 1 visible (458.4), scroll 70.6 dp | 3, scroll 94.6 dp |
| music + 3 rows | 3, 1 visible, scroll 70.6 dp | 3 |

## 3. The compact music player (items 3 and 4)

**Behaviour** (the iPhone 16's lock player; mockup right), one tile in `bg_music_tile`:

- a 48 dp cover (`bg_music_art`; the note glyph 24 dp without art), the title (15 sp extrabold, `kid_ink`) and the
  artist/entry (13 sp, `kid_core_dim`) on one line each, ellipsized;
- one progress row: elapsed left, the 4 dp bar (`bg_music_progress`), the time left right-aligned with a minus
  (`1:26 ━━━━── −2:38`, U+2212), replacing the time line under the bar; a live stream or unknown duration shows the
  elapsed time alone;
- previous / play-pause / next as plain glyphs in `kid_ink`, no discs: 48 dp targets (~26 dp glyphs), play 56 dp
  (~34 dp), 28 dp apart, background `?android:attr/selectableItemBackgroundBorderless` (an oval ripple, no fill). No
  output button - the lock opens nothing.

Model height 12 + 48 + 10 + 16.8 + 2 + 56 + 12 + the 18 dp margin = **174.8 dp** (today 233.8). The one-row compact
tile stays, unchanged, as the fit's last resort (open question 2): at font scale 2.0 on 320x569 the new tile (~222 dp)
doesn't fit even without rows; the compact one (~125 dp) does.

**Code.**
- `lock_media_tile` rewritten, keeping the ids that stay (`lock_media_art`, `_glyph`, `_item` - `maxLines` 1 -,
  `_entry`, `_progress` - `0dp` weight 1 -, `_previous`, `_play`, `_next`); new `lock_media_elapsed` and
  `lock_media_remaining` (12 sp bold, `kid_core_dim`, one line, **no `ellipsize`**, `importantForAccessibility="no"`,
  an initial `40dp` width, never `wrap_content`); `lock_media_time` and `bg_music_art_large` (no other user) go.
- `LockGlance.kt`: `glanceTimeLine` becomes `glanceTimes(positionMs, durationMs): GlanceTimes(elapsed, remaining:
  String?)` - remaining from whole seconds (`durationMs / 1000 - positionMs / 1000`, at least 0, `clockText`), so the
  two add up to the duration; `null` without a duration - and `glanceTimeWidest(durationMs)`: the duration's shape
  with every digit "0" ("0:00", "00:00", "0:00:00"; "0:00:00" without a duration, so a live position past 10 h clips -
  accepted). The bundled Nunito's default figures are already tabular (every digit 600/1000, U+2212 too; the font has
  no `tnum` feature, so `tnum` is a harmless no-op).
- `MusicLockPanel.render()`: each time view's width = `Layout.getDesiredWidth(widest, paint)` (`"−" + widest` for the
  remaining; kerning counts) + 1 dp, set through `layoutParams` only when the px differ (a new track, a duration that
  became known, a font-scale change via `refit` -> `show` -> `render`); the remaining view `GONE` without a duration.
  The elapsed time's shape is the larger of the duration's and the position's (`glanceTimeWidest(duration, position)`,
  checked at each tick, which then asks for one layout): a position past a stale shorter duration ("10:00" against
  "9:59") never clips (qa-r9-revision-check N6).
  `tick()` sets only the two texts and the bar - a fixed width never asks for a layout (round 6's rule). The bar's
  content description stays "1:26 av 4:04"; `glanceRefitDue` already refits when the header grows with the font.

**Edge cases.** Long titles "…" (TalkBack reads the full text). Paused: the play glyph, no tick. A book over 10 h at
font scale 2.0 on 320 dp leaves the bar ~40 dp - accepted. TalkBack: the buttons keep "Forrige", "Spill"/"Pause",
"Neste"; the times aren't read twice. Home's card is untouched.

**Tests:** `glanceTimes` (a track, the seconds adding up, position past the end, no duration, over an hour) and
`glanceTimeWidest`; the tile's 174.8; the progress row elapsed - bar - remaining, the times one line, fixed width, no
ellipsize, not important for accessibility, the bar weighted; controls 48/56/48 dp, the borderless background,
`kid_ink` tints, 208 dp at 320/360/411; no `lock_media_time`. `KidPaletteTest`: the glyphs on `kid_music_tile`.

## 4. Touch sounds and vibration on Kid Settings

**Behaviour.** A card section under "Lyd på telefonen", only when the parent ticked it (bit 16, **off by default**:
the parent turns it on in the PWA, after granting the app-op below), never on the PIN lock or the time-rule screen:

- "Lyd ved trykk" / "Touch sounds" - `Settings.System.SOUND_EFFECTS_ENABLED` (every view's click sound; our PIN keys
  and phone book already follow it);
- "Vibrasjon ved trykk" / "Vibrate on touch" - `HAPTIC_FEEDBACK_ENABLED`; no switch without a vibrator (design 18);
- a dim line: "Tastaturet har egen lyd og vibrasjon i sine innstillinger." / "The keyboard has its own sound and
  vibration in its settings." (HeliBoard plays its click with an explicit volume, ungated by the setting); nothing
  opens.

**`DTMF_TONE_WHEN_DIALING` doesn't belong**: only a dial pad's local tones, and Vibb has no dial pad; left alone.

**Writable only with the app-op** design 34 P12 already needs (`WRITE_SETTINGS`, `Settings.System.canWrite`; both
keys are public). Without it the switches show the phone's state, disabled at 50 % alpha, with "Kan ikke endres på
denne telefonen ennå." / "Can't be changed on this phone yet." Not a restriction: the override/pause change nothing;
the bit going off leaves the setting as it is.

**Launcher code.**
- `server/QuickControls.kt`: `QuickControlFeature.TOUCH = 16`; `touchState(context)` (both keys, missing = on);
  `suspend fun setTouchSounds(context, on)` / `setTouchVibration(context, on)` on `Dispatchers.IO`, called from
  `lifecycleScope` as the Bluetooth page's calls are (`loadSoundEffects()` waits for SoundPool and can take seconds):
  `putInt` in a `try` (`SecurityException`, `IllegalArgumentException` -> false), for sounds then
  `AudioManager.loadSoundEffects()`/`unloadSoundEffects()` as Android's Settings does; the re-read and render come
  after, so a refused write flips the switch back. One `Mutex` serialises the two setters, so a second tap while
  `loadSoundEffects()` still runs waits and the writes finish in tap order; the activity counts the writes in flight,
  keeps the tapped state while any runs and reads the row again after the last (qa-r9-revision-check N2).
- Pure `server/TouchFeedback.kt` (`TouchFeedbackTest`): `touchRowState(sounds: Int?, haptic: Int?, hasVibrator,
  canWrite): TouchRowState(sounds, vibration: Boolean?, writable)`.
- `KidSettingsModel.kt`: `ControlsSection.Rows.touch`, `describe()`, mask 16 alone is a card; `KidSettingsActivity`:
  `renderTouch` (re-read on resume and after a tap), `renderControls`' `anyRow` takes `touch`;
  `activity_kid_settings.xml` `kid_settings_touch_row` after the sound row - two `SwitchCompat`s in `KidSwitchRow`,
  the notes in the sound note's style, `renderInk` covers them. Strings in both files. TalkBack: the switches' role
  and state, then the note.

**Server code** (one commit with the launcher's; no JSON key changes, so `policy_json_keys_snapshot` and
`PolicyResponseCompatTest` stay): `handlers/devices.rs` `QUICK_CONTROL_TOUCH: i64 = 16`, **not** in
`DEFAULT_QUICK_CONTROLS` (`tests/device_api.rs` stays at 15), the policy form's `quick_control_touch`;
`device_pages.rs` `quick_touch`; `device_home.html` a fifth switch "Touch sounds and vibration" in the same
`data-autosave` form (saves in place, `#quick-controls` return - never a jump to the top; its "One form for the four"
comment updated), and the ⓘ line: "Touch sounds and vibration: Android's switches for taps; the keyboard has its own.
They need the "Modify system settings" permission the clock format uses - grant it first, or your child sees them but
can't change them." An older launcher ignores the bit.

**Tests.** `TouchFeedbackTest` (the state table; by source, `putInt` of these keys only in `QuickControls.setTouch*`,
both `suspend` and on IO), `KidSettingsModelTest` (bit 16, 16 alone a card); server: the quick form with
`quick_control_touch` stores 16, the Home Screen page renders it from the mask, and
`tests/device_pages.rs:944-950`'s loop gains the fifth name in the form but unchecked on a new phone.

**Residuals.** Android 13+'s own Touch feedback toggle also writes the hidden `HAPTIC_FEEDBACK_INTENSITY`, which an
app can't write; on a phone where it was turned off in Android's Settings before enrolment, our "on" shows on while
haptics stay off - accepted (setup runbook `docs/setup/android-settings.md`: leave Touch feedback on). Under design 59 the keyguard's PIN pad follows
the same switches [unverified].

## 5. Emulator check list (for the user)

1. **The bug**: 320x569 dp, 3-button navigation, music playing, one WhatsApp missed-call row; screen off/on five
   times - the bottom row always whole; `adb logcat -s GlancePanel` shows a line with the new `available … (pad …)`
   after each screen-on. Switch to gesture navigation with the lock up: a new line, the page refits.
2. **Fixed bottom**: 0, 1, 2, 3 and 5 rows, with and without music, with a running timer; a drag scrolls the rows, a
   tap opens the keypad with the right hint, a drag ending on a row opens nothing; the next lock-up starts at the top;
   the fade only with more below and the one counted row clear. Font scale 1.0/1.3/2.0 at 320 and 411 dp: "Lås opp",
   "Nødsamtale" and the flashlight always whole; at 2.0 the compact tile and, at worst, the "+ N varsler" line.
3. **Player**: a long title, paused/playing, an NRK live stream (elapsed only), a Storytel book over an hour
   ("1:02:13 … −4:10:05"), the times never cut ("1:2…") and steady at each tick; 48/56 dp targets; TalkBack through
   the tile and the rows.
4. **Touch row**: grant the app-op, tick "Touch sounds and vibration" in the PWA (the page stays put) - the section
   shows; toggles change Home's tap sounds and the long-press vibration, a tap doesn't stall;
   `adb shell appops set me.vibb.launcher WRITE_SETTINGS default` - read-only with the note; HeliBoard unaffected;
   untick - the section goes, the setting stays. Then Touch feedback off in Android's Settings, ours on: the
   residual above (expected: haptics stay off).

## 6. Not in scope

- Every conversation in the scroll (needs recycled row views; open question 3).
- A newer conversation arriving while the kid has scrolled lands above the viewport (a `scrollTo(0, 0)` when
  `bindRows` gains one would show it).
- A status field `touch_writable`: with the bit off by default the parent turns the row on after granting the app-op,
  and the ⓘ line says so (`clock_format_applied` doesn't warn - it is left out for "Follow the phone", the default).
- Key haptics on our PIN keypad; the flashlight's disc, Home's music card, the compact tile's look.

## 7. Open questions for the user (the defaults are the QA's recommendations)

1. **Music and rows compete** - default: the full player stays and the rows scroll from one visible row (the iPhone's
   behaviour, the mockup). Flip: design 25's order, the compact tile first so every row shows.
2. **The one-row compact tile** - default: kept, unchanged, as the last resort (at font scale 2.0 the full player
   doesn't fit). Flip: drop it.
3. **Rows** - default: at most three + "+ N varsler til". Flip: list every conversation (its own design).

## QA fixes (`qa-r9-design.md`)

- R9-1: §4 launcher code - the setters `suspend` on IO from `lifecycleScope`; §4 tests.
- R9-2: §4 residuals and §5 check 4 - accepted residual, runbook note.
- R9-3: §4 behaviour/server code - bit 16 off by default, `device_api.rs` stays 15; §6 justification corrected.
- R9-4: §2 layout - 16 dp fade; candidate 7 is the "more" line; scroll heights in the fits table.
- R9-5: §1 fix - log on an available-height change too; §5 check 1 reads the `available` part.
- R9-6: §2 fit - the "more" line not counted with one row, `rows` = `min(n, 3)`, `i < fit.rows` stays; the design 42
  §1.6 change listed and pinned in the timer test.
- R9-7: §1 cause - 501.4/523.8 dp, ~43 dp overflow.
- R9-8: §3 code - `Layout.getDesiredWidth` + 1 dp, no ellipsize, `tnum` a no-op, the 10 h clip accepted.
- R9-9: §1 fix - a private `available()` in `GlancePanel`, pinned by source; no pure helper.
- R9-10: §2 tests (no `height()` change, `0dp` + weight pinned), §4 tests (`device_pages.rs` loop, template comment),
  §4 launcher code (`anyRow`).
- R9-11: §3 behaviour - `selectableItemBackgroundBorderless`, no new drawable.
- R9-12: header - the published mockup linked, not committed.
- R9-13: header - the Nødsamtale tie noted for design 59.
