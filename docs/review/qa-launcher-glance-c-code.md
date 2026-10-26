# Code QA: launcher-glance-c (RC3-8, six-inch F3/F4, the music player 12 dp lower)

Scope: `git diff 8d8ba426..85ffdbf3` (3 commits `cd6bf8af`, `99486a66`, `85ffdbf3`; 11 files, +212/-55) against
`docs/plan/2026-10-25-queue-rounds.md` "Round L1" (Q1-Q4), `docs/testing/2026-10-23-rc3-run.md` "RC3-8",
`docs/testing/2026-10-23-six-inch-android15.md` F3/F4, r11, r13 and `launcher/CLAUDE.md`. Reviewed on branch
`qa-launcher-glance-c` (at `85ffdbf3`). The implementer's numbers were recomputed from the XML with the test's own
model (1.4 em a line, `LockGlanceLayoutTest`) and, for the Jelly Star, with the profile clock the phone actually runs.

**Verdict: merge after a small fix round.** The code is small and right: the two XML trims, the integer resource and
the 4 dp name sides do what they say, nothing is mode-specific, no strings moved, the suite is green with
`-PwarningsAsErrors`. The RC3-8 claim is right **in substance** (since r11 the compact tile from two rows means music +
3 rows never again hides two whole rows) but **wrong in its numbers**: 392 dp / "nothing below the fold" is the
no-profile clock, and the Jelly Star (and `jelly36`) always run the profile clock, 22.4 dp taller - 414.4 dp, so
13.4 dp of the third row sits under the fade with 3-button navigation (M-1). The fade as "the hint" is at its weakest
in exactly the case RC3-8 is about, a whole row below the fold (M-2). Two Mediums, six Lows, four Nits; 12
mutations, 10 caught, the 2 that got through are L-6 and an expected one (m7).

## Claims checked

| Claim | Checked | Result |
|---|---|---|
| r11 made the compact tile the rule from 2 rows | `LockGlance.kt:525` `GLANCE_COMPACT_FROM_ROWS = 2`, `glanceLayout` :544-552 | true, every bar, both modes |
| music + 3 rows = 392 dp in a 401 / 425 dp scroller | model: 78.4 + 25.6 + 90 + 14 + 3 x 56 + 2 x 8 = 392 | true **only without a clock profile**; the Jelly Star's is 414.4 (M-1) |
| the 16 dp edge fade is the hint, >= gap + bottom padding | `view_lock_glance.xml:37-38`, ScrollView's `getBottomFadingEdgeStrength` (span / length, 0 when nothing is below) | true, at equality (16 = 8 + 8): the weakest case fades only a card's padding (M-2) |
| the "more" line can't be the hint | it is `lock_glance_list`'s last child and counts rows beyond `GLANCE_MAX_ROWS` 8 | true |
| F3: names in 88 dp of the 96 dp cells, nothing moves at 1.0 | `item_kid_contact.xml:84-85`, `HomeActivity.kt:914-918` (`maxWidth = itemPx`), `WholeWordTextView.lineWidth` subtracts `compoundPadding*` | true for the test's names; the phone book line is mis-modelled (L-4) |
| F4: two lines from 720 dp; 533.2 dp needs 701.2 dp | 101.92 + 31.48 + 97.96 + 14 + 3 x 90.62 + 2 x 8 = 533.2; + 48 + 48 + 16 + 56 = 701.2 | true; the qualifier's meaning differs on Android 14 (N-1) |
| F4: the Jelly Star's pins unchanged | the tables call `partsAt(1f)` with `textLines = 1` | true, but they never read `values/` (L-1) |
| the tile 174.8 -> 162.8 dp | 18 + 12 + 48 + 6 + 16.8 + 2 + 56 + 4 = 162.8 | true; XML = model |
| the pin tables 266.8 / 336.8 / 348.8 / 418.8 / 439 | 104 + 162.8; + 14 + 56; 104 + 82 + 162.8; + 70; + 20.2; scrolls 17.8 / 0, 38 / 14 against 401 / 425 | all true |
| the controls keep >= 48 dp targets, 11-15 dp under the glyphs | 48 / 56 / 48 dp ImageButtons, 11 dp padding; play: 11 dp in its target + 4 dp tile; prev/next: 11 + 4 (centred in the 56 dp row) + 4 | true; the press ripple now meets the tile's edge (L-2) |
| the r13 rule card untouched | no change to `lock/RuleCard.kt` or `lock_rule_card`; card 18 + 24 + 40 = 82 | true |
| both lock modes, no strings | `GlancePanel.profile` is chosen by model/density/width, not the mode; no `strings.xml` in the diff | true |

## Correctness per item

### Q1 RC3-8 - the rule fixes it; the pin models the wrong phone

`glanceLayout(tile = true, rowCount = 3)` is COMPACT for every bar and mode, so the rc.3 picture (the full tile, one
row, two rows wholly invisible) can't come back. What the branch's pin and PLAN say about what is *in sight* is the
no-profile page. `GlancePanel` (:134-143) gives the Jelly Star `JELLY_STAR_CLOCK` and `jelly36` (an `sdk_gphone` image
at 240 dpi, 320 dp) `EMULATOR_PIXEL_CLOCK`, in own mode and android mode alike, and the test's own `profileParts`
(:1091) puts that clock block at 113.7 + 12.7 = 126.4 dp against 104. My numbers (the test's model, 48 dp status bar):

| Jelly Star, font 1.0 | content | 3-button (401) | gestures (425) |
|---|---|---|---|
| music + 3 rows, no profile (the pin) | 392 | fits | fits |
| music + 3 rows, **profile** | 414.4 | row 3: 13.4 dp under, 42.6 in sight | fits (10.6 spare) |
| + the alarm line | 434.6 | row 3: 33.6 under, 22.4 in sight | row 3: 9.6 under |

With `jelly36`'s own bars (a 24 dp status bar; the keyguard bar `max(36 px, 40 dp)` = 40 dp places the profile clock):
the rows end at 462.4 dp on screen against a fold at 457 (3-button) / 481 (gestures): 5.4 dp under with 3-button,
fits with gestures; with an alarm 25.6 / 1.6 dp under. So at font 1.0 every row is at least partly in sight - RC3-8's
complaint is answered - but "nothing below the fold" holds only with gesture navigation and no alarm.

At font 1.3 (the profile; one-line rows 66.96 dp, compact tile 97.96): music + 3 rows leaves 11 dp of row 3 in sight
(3-button) or 35 dp (gestures). Whole rows hide (every case is a scroll, accepted by r11):

| Jelly Star (profile, 48 dp bar) | the fold | what is hidden |
|---|---|---|
| 1.0, 3-button, no music, 5 rows | 401 | row 4 ends 396.4, **row 5 (404.4) wholly hidden** - the fold in the gap |
| 1.0, 3-button, rule card + full music + 1 row + alarm (r13 frame D + alarm) | 401 | **the only row (405.4) wholly hidden**; the fade lies on the tile's bottom and the gap |
| 1.0, either bar, rule card + compact music + 3 rows | 401 / 425 | row 3 (440.4) hidden, row 2 partly |
| 1.3, 3-button, rule card + music + 3 rows | 401 | row 1 ends 400.0, **rows 2 and 3 wholly hidden** |
| 1.3, 3-button, music + 3 rows + alarm | 401 | row 3 hidden, row 2 60 dp in sight |

In each, the "more" line is below with the rows (or absent, < 9 rows), so the only hint is the 16 dp fade - M-2.

### Q2 six-inch F3 - right

The label's width is `maxWidth = itemPx` (`HomeActivity.kt:917`) inside an item exactly `itemPx` wide (the
`LinearLayout.LayoutParams(itemPx, WRAP_CONTENT)` HomeEdgesTest pins), and `TextView.maxWidth` includes the padding, so
the text keeps 4 dp inside its own cell: neighbours' text is >= 8 dp apart in the fitting row and the scrolling row
alike. `WholeWordTextView.lineWidth` (WholeWordText.kt:181-186) takes `compoundPaddingLeft/Right` off, so the
no-cut-in-a-word fit sees the 88 dp line. At font 1.0 (15 sp, the test's em model) the widest single words are
"Nødsamtale" and "Anne-Marie" at 82.5 dp: they fit 88 as they fit 96, so Home at 1.0 is unchanged for those names. At
1.3, "Kristoffer" (88.5 dp in the model) now shrinks a step where it fit 96 - the intended effect (N-4). The phone book
inflates the same layout, so it gets the padding too (L-4).

### Q3 six-inch F4 - right, the model and the XML agree

- **Heights.** A one-line row is 16 + 21 + 18.2 = 55.2 -> `minHeight` 56; two lines 16 + 21 + 36.4 = 73.4 dp. The
  model's 1.4 em a line is generous for the second line (Nunito's ascent + descent is about 1.364 em; font padding only
  applies to the first and last line's outer edges), so the real two-line row is a little shorter than the model,
  never taller. No `lineSpacing*` today (the model wouldn't see one - L-6), default `includeFontPadding`; `ellipsize="end"` with `maxLines` 2 ellipsizes at the
  end of line 2 (StaticLayout's maxLines + END). `cleanGlanceText` collapses whitespace, so no text forces a break.
- **The title** stays `maxLines="1"` (pinned by the order test, and m8 below).
- **The qualifier.** `-h720dp` matches `Configuration.screenHeightDp`. With `targetSdk 36` on Android 15+ that is the
  window's full height including the system bars, which is what the derivation (720 - 48 - 48 - 16 - 56 = 552 >= 533.2)
  assumes. On Android 14 (`minSdk 34`) it is the height less the status and navigation bars, so two lines need about
  816 dp of screen - stricter, so the fit still holds (N-1). In split screen / a smaller window the height drops and the
  row goes back to one line - the safe side. The lock is portrait-only, and the activity handles
  `screenSize|density|fontScale` itself, so a crossing at run time only reaches rows inflated afterwards. Skipped as
  theoretical: no one changes the display size while the lock is up.
- **The icon column.** The row is `gravity="center_vertical"`, so with two lines the 36 dp icon sits mid-row (18.7 dp
  from the top, 10 dp in a one-line row) while the time stays top-aligned (`layout_gravity="top"`) - N-2.
- The Jelly Star is 569 dp: `values/`'s 1, every pin unchanged (verified: the tables pass at 1 and the six-inch test
  asserts both values).

### Q4 the player 12 dp lower - right

`lock_media_tile` is now 12/12/12/4 padding (pinned) and the progress row's top margin 6; the model gives 162.8 and the
tables follow it exactly. The targets are unchanged (48 / 56 / 48 dp, `padding="11dp"`); the play glyph's bottom is
11 dp above its target's edge and 15 dp above the tile's, prev/next 19 dp. `selectableItemBackgroundBorderless` is
projected onto the tile's background, whose rounded outline clips it - see L-2. The rule card, the compact tile and the
timer tile are untouched; r13's frame D now scrolls 17.8 / 0 dp instead of 29.8 / 5.8 (better, N-3).

## Findings

### Medium

**M-1. The RC3-8 pin, PLAN and the evening run's first check use the no-profile page; the Jelly Star always runs the
profile clock.** `LockGlanceTest.kt:1038-1042` asserts 392 dp and "every row in sight" with `parts` (the 56 sp centred
clock). `PLAN.md:1000-1004` repeats it ("392 dp in the Jelly Star's 401/425 dp scroller, nothing below the fold"), and
`docs/testing/2026-10-25-evening-run.md` (the RC3-8 row) expects "no fade at the bottom" on `jelly36`. But
`glanceClockProfile` gives both the Jelly Star and `jelly36` a profile in either lock mode, 22.4 dp taller: 414.4 dp,
13.4 dp of row 3 under the fade with 3-button navigation (`jelly36`'s own bars: 5.4 dp), and with an alarm line
33.6 / 9.6 dp. Failure scenario: E1 runs the RC3-8 check on `jelly36` with 3-button navigation (E1 does 3-button for
the Home items) or with an alarm set, sees the bottom fade over row 3, and the check is marked failed - or worse, a
later change makes the profile page hide row 3 wholly and the pin, which models the wrong clock, stays green. Fix: run
the RC3-8 assertions on `profileParts` (the Jelly Star's page) as well: gestures - all three in sight; 3-button - row 3
at least 40 dp in sight under the fade (42.6 dp now); with the alarm line - at least 20. Correct the PLAN paragraph's
numbers and the check's expectation ("gestures, no alarm: no fade; 3-button: row 3's bottom under the fade").

**M-2. The fade is the only hint, and when a whole row is below the fold it fades nothing but a card's padding.**
`view_lock_glance.xml:37` `fadingEdgeLength="16dp"` = the row gap 8 + a row's `paddingBottom` 8, and the pin
(`LockGlanceTest.kt:1049`) accepts equality. When the fold lands in a gap, the 16 dp fade covers the 8 dp of ground
(fading ground into ground: invisible) and the row above's 8 dp of bottom padding (#1B1B28 over #0C0C14, 50 -> 0 %
alpha) - never its text. Failure scenario, the Jelly Star at font 1.0 with 3-button navigation and five conversations,
no music (the table above): row 4 ends at 396.4 dp, row 5 starts at 404.4, the fold is 401 - the kid sees four complete
rows and a faint softening of the fourth card's lower edge, i.e. RC3-8's "the kid can't tell that a row exists" for row
5. The same at 1.3 with the rule card + music + 3 rows (rows 2 and 3 wholly hidden, the fade on row 1's padding), and
r13's frame D with an alarm at 1.0 (the only row wholly hidden). RC3-8's own words ask for "a fade or 'more' hint
whenever a row is out of sight": the fade is there, but too short to read as one in that case. Fix (smallest): a
bottom fade long enough to reach into the row above's text - 32 dp, Home's `FADE_BOTTOM_DP` - and the pin made strict
(`> gap + paddingBottom + half the subtitle line`); ScrollView ramps the fade by the distance left (span / length), so a
few dp of overflow still fade only a few dp. That changes the pin to `CONTACTS_FADE_DP` (`LockGlanceTest.kt:1248-1249`)
and the top fade's length too (one attribute sets both) - or the user's call that a faint edge is enough. Either way,
the E1 check must look at a fold-in-the-gap case (below), not only music + 3 rows.

### Low

**L-1. The Jelly Star's pins don't read `values/integers.xml`.** `height(e, font, textLines = 1)` and
`partsAt(font, textLines = 1)` (`LockGlanceTest.kt:723`, :753) take the line count as a parameter, so every table is a
one-line row whatever `values/` says. Mutation m2 (`values/` -> 2) was caught only by the literal
`assertEquals(1, integer("values"))`; the tables, which are what the Jelly Star's fit rests on, stayed green. Failure
scenario: someone raises the default to 2 and "fixes" the literal with it - every Jelly Star table still passes while
the phone's rows grow 17.4 dp each. Fix: the default `textLines` is `integer("values")` (the six-inch test uses
`integer("values-h720dp")`), so the tables move with the resource.

**L-2. The play button's press ripple is now cut flat at the tile's bottom edge.** `selectableItemBackgroundBorderless`
on a 56 dp button draws a circle of half its diagonal, 39.6 dp, reaching 11.6 dp below the button; it is projected
onto the tile's `bg_music_tile`, whose rounded outline clips it. With 12 dp below the button it had 0.4 dp to spare;
with 4 dp, 7.6 dp of the circle is cut along the tile's edge (prev/next: 9.9 dp past a 48 dp button, 8 dp to the edge,
~2 dp cut). Failure scenario: the kid presses pause and the ripple's bottom is a hard straight line at the tile's
edge - the kind of glitch the "calm, never flashy" look avoids. Fix if the emulator shows it: one ripple drawable with
`android:radius="28dp"` (inside the 56 dp target and the 4 dp margin) for the three controls, or accept it with the
user. Add the press to the E1 check.

**L-3. PLAN's queue doesn't carry the round's state, and the round's fourth line isn't part of it.** `PLAN.md:509-513`
still lists Round L1's RC3-8, F3/F4 and "compact music tile a little lower" with no "built on `launcher-glance-c`,
awaiting QA" (the user's rule: every queue item with its state), and the fourth bullet under L1, "Fingerprint with the
screen on, Jelly Star check" (Q5, the help line), is not built - it waits on J1 (`2026-10-25-queue-rounds.md:29-30`).
Failure scenario: the merge commit removes Round L1's block from the queue and the fingerprint help line leaves with
it. Fix: mark the three as built (awaiting QA) now, and move the fingerprint item under "Waits on the user / the Jelly
Star" with its J1 dependency.

**L-4. `WholeWordsTest` models the phone book's line 8 dp wider than the Jelly Star's.**
`WholeWordsTest.kt:161-164` uses `nameLine(104f)` = 96 dp, but the phone book (`PhoneBookActivity`: 16 dp sides, a
3-column `GridLayoutManager`) gives each cell (320 - 32) / 3 = 96 dp, so `maxWidth="104dp"` never binds and the name's
line is 96 - 8 = 88 dp on the Jelly Star. Before the branch the model was 104 against a real 96; the branch keeps the
same 8 dp error. Failure scenario: a name that fits 96 but not 88 at 14 sp x 1.3 shrinks on the phone while the test,
documented as "the phone book: 14 sp names in 104 dp", claims room it doesn't have. Fix: the phone book's line is
`nameLine(min(104f, 288f / 3))`, named as the Jelly Star's cell.

**L-5. The owed emulator checks miss the cases that test these changes** (`docs/testing/2026-10-25-evening-run.md`,
"Owed after launcher-glance-c"). Add:
1. `jelly36`, font 1.0, music + 3 rows with **3-button navigation** and again **with an alarm set**: row 3's bottom
   under the fade, most of it in sight (M-1's numbers), in **own mode and android mode** (the profile clock in both).
2. `jelly36`, font 1.3, **3-button**, the **rule card + music + 3 rows** (a time rule locking): rows 2 and 3 wholly
   below; screenshot the bottom edge to judge whether the fade reads as a hint (M-2). And font 1.0, 3-button, no music,
   **5 rows** (the fold in the gap).
3. `jelly36`, the full tile: **press and hold play/pause, previous, next** - the ripple at the tile's bottom edge (L-2);
   the play glyph's 15 dp to the edge.
4. `six35`, font 1.3: a **two-line row next to a one-line row** (the icon's place, N-2), a **long nb message ellipsized
   at the end of line 2**, and 3-button navigation with music + 3 two-line rows (533.2 dp in a ~770 dp scroller).
5. `jelly36`, font 1.0 / 1.3 / 2.0: **Home's contacts row and the phone book** (the 4 dp sides apply to both): 1.0 as
   before, "Kristoffer" a step smaller at 1.3, "Mamma"/"Mormor" whole at 2.0 - the row in the list is `six35` only.

**L-6. The height model ignores line spacing, so a taller two-line text goes unseen.** `height()`
(`LockGlanceTest.kt:723-741`) counts a TextView as `textSize x 1.4 x font x lines` and reads nothing else. Mutation m11
(`android:lineSpacingExtra="6dp"` on `glance_row_subtitle`) passed every test, though it makes the six-inch's two-line
row 6 dp taller (three rows 18 dp: 551.2 dp in the 552 dp scroller at 720 dp - one more dp and the threshold argument
breaks) and the "XML = model" claim false. F4 is the first place a second line exists, so it is the first place
`lineSpacingExtra`/`lineSpacingMultiplier`/`lineHeight` would bite. Fix: the model fails on a TextView carrying any of
those (or on `includeFontPadding="false"` it doesn't model), one line in `height()`'s `else` branch, so whoever adds one
has to model it.

### Nit

**N-1. Say what `h720dp` measures.** `values-h720dp/integers.xml:3-7` and PLAN's F4 paragraph say "a 720 dp tall
screen". The qualifier reads `screenHeightDp`: the whole window on Android 15+ with `targetSdk` 36 (what the 720 - 48 -
48 derivation assumes), the height less both bars on Android 14 (`minSdk` 34), where two lines need about 816 dp. Safe
either way; one clause keeps someone from "correcting" the derivation later.

**N-2. Two-line rows centre the icon, the time stays at the top.** `view_lock_glance_row.xml:18` (`center_vertical`)
with `glance_row_time`'s `layout_gravity="top"`: in a two-line row the avatar sits 8.7 dp lower than in a one-line row,
below the title's line; in a mixed list the avatars don't line up with the names. Fine if intended (iOS centres its
icon); otherwise the icon's FrameLayout gets `layout_gravity="top"` and a 2 dp margin, as the time has. Look at it in
check 4 above.

**N-3. r13's table now has old numbers.** `docs/design/r13-launcher-rule-card-and-time-left.md:60, 193-194` ("430.8 dp
and scroll about 30 dp (3-button) or 6 dp", frame D "scrolls 29.8 / 5.8 (accepted, D4)") and r11 §1.2's table
(`r11-launcher-glance-scroll.md:125-126`, 278.8 / 348.8) are the trade the user accepted; one dated line under each
("since 2026-10-26 the full tile is 162.8: frame D 418.8, scrolls 17.8 / 0") keeps them honest.

**N-4. "Nothing moves at font 1.0" holds for the test's names.** PLAN's F3 paragraph: a single word between 88 and 96
dp at 15 sp (about 11-12 letters in the em model) now shrinks a step at 1.0, and at 1.3 "Kristoffer" (88.5 dp) does.
That is the intended trade; say "names up to ~88 dp" rather than "as before".

## Mutations

Each applied alone, the three classes run (`LockGlanceLayoutTest`, `HomeEdgesTest`, `WholeWordsTest`), the file
restored with `git checkout`.

| # | Mutation | Caught by | Verdict |
|---|---|---|---|
| m1 | the scroller's `fadingEdgeLength` 16 -> 4 dp | RC3-8 test (fade >= gap + padding), the order test (`"16dp"` = `CONTACTS_FADE_DP`) | caught |
| m2 | `values/` `glance_row_text_lines` 1 -> 2 | only F4's literal `assertEquals(1, integer("values"))`; **no Jelly Star table** | caught, weakly (L-1) |
| m3 | `values-h720dp/` 2 -> 1 | F4 test (`integer("values-h720dp")`) | caught |
| m4 | the tile's `paddingBottom` 4 -> 12 dp | the parts test (162.8, the 12/12/12/4 list), the r11/r13 tables | caught |
| m5 | `contact_name` sides 4 -> 0 dp | `HomeEdgesTest` (8 dp apart) | caught |
| m6a | `GLANCE_COMPACT_FROM_ROWS` 2 -> 3 | the r11 table (music + 2 rows = 328); the RC3-8 test passes (3 rows still compact) | caught |
| m6b | `GLANCE_COMPACT_FROM_ROWS` 2 -> 4 | RC3-8 test (COMPACT, 392), F4 test (533.2), the r11 table | caught |
| m7 | `WholeWordsTest.nameLine` without the padding | nothing | escaped - expected: a wider modelled line can only fit more, so no assertion can fail; HomeEdgesTest pins the padding itself |
| m8 | `glance_row_title` `maxLines` 1 -> 2 | the order test (title "1"), and the model in four more tests | caught |
| m9 | `glance_row_subtitle` back to `maxLines="1"` | the order test (the integer ref), F4 test (73.4) | caught |
| m10 | the progress row's top margin 6 -> 10 dp | the parts test, the r11/r13 tables | caught |
| m11 | `lineSpacingExtra="6dp"` on `glance_row_subtitle` | nothing | **escaped** (L-6) |

## Suite

`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true testDebugUnitTest` at `85ffdbf3`:
**1810 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL (1 m 47 s). The only warnings are kapt's
pre-existing "source version RELEASE_11" lines.

## Emulator checks to add

L-5's five items, into `docs/testing/2026-10-25-evening-run.md` "Owed after launcher-glance-c", with the RC3-8 row's
expectation corrected per M-1.

## Fix round (2026-10-26, the implementer)

On `launcher-glance-c`, on top of `85ffdbf3` and this review's cherry-pick. Every finding fixed, none skipped.

| Finding | What was done |
|---|---|
| M-1 | The RC3-8 test runs on `profileParts` too: 414.4 dp, fits with gestures, row 3 42.6 dp in sight with 3-button (13.4 under the fade); with the alarm line 434.6 dp, 22.4 / 46.4 dp in sight. PLAN's RC3-8 paragraph and the owed check's expectation now say so (gestures, no alarm: no fade; 3-button and the alarm: row 3's bottom under the fade, never a row wholly hidden), in own mode and android mode. |
| M-2 | The scroller's fade is 32 dp, Home's `FADE_BOTTOM_DP` (one attribute, so the top fade too). Pinned strictly: `fadingEdgeLength > row gap + paddingBottom + half the subtitle line` (25.1 dp) and equal to `FADE_BOTTOM_DP`; the order test's `"16dp"` / `CONTACTS_FADE_DP` pin moved to `"32dp"`. The fold-in-the-gap cases are in the owed checks (L-5 item 2). |
| L-1 | The model's default line count is `values/integers.xml`'s (`defaultTextLines` via `textLinesIn`), for `height()` and `partsAt()`; the F4 test reads `values-h720dp` the same way. m2 now moves every Jelly Star table. |
| L-2 | `res/drawable/ripple_lock_media_control.xml`: a `<ripple>` in `?android:attr/colorControlHighlight`, radius 28 dp, no mask or content (unbounded, projected as before) on previous/play/next. Pinned: the drawable's shape and radius, and that the radius reaches no further than the tile's bottom edge for each button (play 28 + 4, prev/next 24 + 4 + 4). The press is owed check 3. |
| L-3 | The queue list is the coordinator's (not touched here). Q5 (the fingerprint help line, waiting on J1) stays open and is not part of this branch. |
| L-4 | `WholeWordsTest`'s phone-book line is `nameLine(min(104, 288 / 3))` = 88 dp, pinned as such. |
| L-5 | The five checks are in `docs/testing/2026-10-25-evening-run.md` "Owed after launcher-glance-c" (3-button and alarm in both modes, the rule card + music + 3 rows at 1.3 and 5 rows at 1.0, the ripple, the six-inch two-line/one-line pair with a long nb message and 3-button, Home's row and the phone book at 1.0/1.3/2.0). |
| L-6 | `height()` refuses a text with `lineSpacingExtra`, `lineSpacingMultiplier`, `lineHeight`, `firstBaselineToTopHeight`, `lastBaselineToBottomHeight` or `includeFontPadding="false"` (`modelled()`): whoever adds one models it first. m11 now fails six layout tests. |
| N-1 | `values-h720dp/integers.xml`, PLAN's F4 paragraph and `launcher/CLAUDE.md` say what the qualifier reads: `screenHeightDp`, the whole window on Android 15+ (targetSdk 36), less both bars on 14 (~816 dp of screen there, stricter). |
| N-2 | The row's icon frame is `layout_gravity="top"` with a 2 dp top margin: exactly where centring put it in a one-line row (56 - 16 - 36 = 4, halved), beside the title in a two-line one. Pinned in the F4 test. |
| N-3 | A dated "As built 2026-10-26" line under r11 §1.2's table and r13 §1.3's table, and one clause on D4's 430.8 dp; the tables themselves untouched. |
| N-4 | PLAN's F3 paragraph says a name up to ~88 dp fits at 1.0 as before, a single word between 88 and 96 dp takes a step smaller ("Kristoffer" at 1.3) - the intended trade. |
| m7 | Expected escape, nothing to do (a wider modelled line can only fit more; `HomeEdgesTest` pins the padding). |
