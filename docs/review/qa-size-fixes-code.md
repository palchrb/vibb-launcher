# Code QA: branch `size-fixes` (screen-size fixes S1-S9)

Reviewed: `git diff main...size-fixes` (5f0d2f97, ad24218a) against `docs/testing/2026-11-14-screen-sizes.md`. The
user's bar: every screen works with gesture and 3-button navigation at 270 dp, 320 dp @ 240 dpi (36 px bar) and
411 dp, font 1.0 and 1.3; the look stays calm; light and night looks keep working; no standard gesture removed;
"Nødsamtale" always whole and tappable. Read-only review (no emulator, no Gradle run).

**Counts:** High 0, Medium 2, Low 8.

No finding hides or covers an emergency button. S1's new structure keeps "Nødsamtale" whole above the bar in every
state checked (see "Checked, no finding").

## Medium

### M1 - Dagen's second line drops to 75 % (9.75 sp) for any location with a long URL or token, at every screen size

- **Where:** `launcher/app/src/main/res/layout/item_day_event.xml:81-89` (the second line is now a
  `WholeWordTextView`), with `ui/WholeWordText.kt:210-226` (`fitWords`) and `day/RowText.kt:57` (the location is shown
  unchanged).
- **Failure:** take an event whose location is a meeting link, e.g.
  `https://us02web.zoom.us/j/81234567890?pwd=AbCdEfGhIjKlMnOpQrStUvWxYz.1`, or a Teams link with its
  `19%3ameeting_NjE3ZTk5...` run. ICU allows no break inside `pwd=AbCd...Yz.1` (`=`, `.` and `_` between letters are
  not break opportunities), and the run is wider than the text column. That column is about 150 dp on the narrow
  phone and about 240 dp at 411 dp. So `wholeAt` fails at every size. With `ownHyphens == NONE` (the row sets no
  hyphenation), `cutsAt` fails at every size too. The result is `Fit(floor, ellipsized = true)`: the **whole** second
  line, "Pågår nå, " included, is drawn at 13 sp × 0.75 = 9.75 sp at font 1.0, and the token is cut to
  `pwd=AbCd…`. Before the branch the line stayed at 13 sp and wrapped between letters, ending in the TextView's "…".
  This happens on the wide phone too, not only in the narrow class S3 was about. For a dim secondary line, 9.75 sp is
  below anything else on the kid's screens.
- **Fix:** keep the row's second line a plain `TextView`: S3's finding was about the title, and the second line already
  ends in an ellipsis. Or give `WholeWordTextView` a mode without the shrink (whole words, or the TextView's own
  ellipsis, never the floor) and use it there. At the least, don't shrink for a run that holds `/`, `@`, `=`, `%` or `_`,
  which is no word a child reads.

### M2 - S9's 80 dp strip leaves Dagen almost no list in landscape

- **Where:** `launcher/app/src/main/java/com/kidslauncher/mdm/ui/day/DayActivity.kt:123-131` (`renderAdd`, `ADD_CLEAR_DP`
  at :267). `AndroidManifest.xml:239-241`: Dagen rotates in place (`configChanges="orientation|screenSize|uiMode"`,
  no `screenOrientation`).
- **Failure:** take the 320 dp class phone (569 dp tall) turned to landscape, with the "+" shown (the kid calendar is
  writable):
  - the window is about 320 dp tall: less the status bar (24), the bottom inset in gesture mode (about 24) and the
    root's 4, about 268 dp;
  - less the header with Dagen's date line (about 64-70 dp at 1.0, more at 1.3) and the 8 dp gap, about 190 dp;
  - less the strip (80), about 110 dp of list. With a reminder row (about 45 dp) it is about 65 dp, less than one
    event row at font 1.3.

  Before the branch the list had the whole 190 dp, and the "+" floated over it with 96 dp of scroll room. A horizontal
  day swipe that starts in the strip also does nothing now, because the strip is outside the `ViewPager2`. In portrait
  the cost is the 80 dp that the fix-round doc already names.
- **Fix:** reserve the strip only when the pager keeps enough room without it. For example, keep the strip only while
  the pager is at least about 3 rows tall (a check in `onLayout` or a `doOnLayout`); else fall back to the overlay with
  the page's old 96 dp bottom padding. Another way: put "+" in the header's action slot in landscape.

## Low

### L1 - S6 can trade a fully shown hyphenated label for an ellipsized one

- **Where:** `ui/WholeWordText.kt:125-126` (`fitPreferWhole`) for Home's tiles (`item_kid_tile.xml`,
  `hyphenationFrequency="normal"`, `maxLines="2"`).
- **Failure:** take a three-word label whose middle word is a little wider than the cell, laid out with hyphenation at
  the base size. With hyphens the whole label fits in its 2 lines: "Aa Bbbbbb-" / "bbb Cc". Without hyphens it needs 3
  lines: "Aa" / "Bbbbbbbbb" / "Cc". `wholeAt` only checks for cut words, and every line ends at a space, so it passes
  at the base size. The tile then shows "Aa" / "Bbbbbbbbb…" and loses "Cc", which it showed in full before. Realistic
  candidates are three-word app names at font 1.3 on the narrow phone, or at 2.0 on any width.
- **Fix:** let `wholeAt` also require `lineCount <= maxLines` whenever the hyphenated layout at that size fits within
  `maxLines`. "Whole words" should never cost text that hyphenation would have shown.

### L2 - TalkBack now reads the page heading as "Meld…" where the header title hits the floor

- **Where:** `res/layout/include_kid_header.xml:41` (the title is a `WholeWordTextView`, `accessibilityHeading="true"`),
  `ui/WholeWordText.kt` (`WordEllipsis`).
- **Failure:** the fix-round doc names one case that S4 leaves cut: Meldinger on the narrow phone at 1.3. There the
  fit ends in `WordEllipsis`, which is a `TransformationMethod`. `TextView.getTextForAccessibility()` returns the
  transformed text, so the heading node reads "Meld…". Before S4 the TextView's own `ellipsize="end"` left the
  transformed text whole, and TalkBack read "Meldinger". The header has no clickable parent with a content description
  to carry the full name, as the tiles have.
- **Fix:** in `WholeWordTextView`, override `onInitializeAccessibilityNodeInfo` to set `info.text` to the source text
  while a `WordEllipsis` is applied. Or set the header title's `contentDescription` to the full title in `KidHeader.bind`.

### L3 - Dagen's "›" doesn't show for a word-level ellipsis

- **Where:** `ui/day/DayListAdapter.kt:110-114` (`ellipsized` reads only `layout.getEllipsisCount`).
- **Failure:** in M1's case, or a long single-word title, the row's text ends in `WordEllipsis`'s "…" while the layout
  has no ellipsis (`getEllipsisCount == 0`). The "›" that means "something was cut" stays invisible, though text was
  cut.
- **Fix:** expose `val wordEllipsized: Boolean` on `WholeWordTextView` (true while a `WordEllipsis` is applied) and
  OR it into `ellipsized()`.

### L4 - S7 squeezes Liste and Sov to their bare content whenever the output's name is long, on every width

- **Where:** `music/app/src/main/java/me/vibb/music/ui/Fit.kt:106` (`shrinkerMin`), `ui/PlayerScreen.kt:303-307`.
- **Failure:** take a Sonos room or group with a long name ("Stue og kjøkken", "Barnerommet (Era 100)") at 411 dp.
  The name's min becomes `available - fixed`, so `flexWidths` fixes the name at that width. Liste and Sov get exactly
  their `maxIntrinsicWidth`: icon, 6 dp, label and `BarButton`'s 2 dp sides, with the glyph 2 dp from the pill's
  rounded edge. Before the branch they had their weighted share (about 113 dp each) and the name ellipsized. The look
  goes cramped on the roomiest phones, which S7 never needed to touch.
- **Fix:** give the others some air before the name takes the rest: `shrinkerMin(own, available, fixed + n * AIR)` with
  AIR of about 12-16 dp. Or cap the name's min at its weighted share plus the others' slack minus that air.

### L5 - The fit runs up to 12 layouts, 6 of them identical to ones that just failed, in RecyclerView binds

- **Where:** `ui/WholeWordText.kt:214-218` (`fitPreferWhole` then `fit`) and :255 (a new `BreakIterator` per
  `cutsAt`).
- **Failure:** this applies when `ownHyphens == HYPHENATION_FREQUENCY_NONE` (Dagen's two texts, the header, every
  contact name). There `wholeAt(s)` is exactly `!cutsAt(s)`. So when no size is whole, `fit()` builds the same 6
  `StaticLayout`s and 6 `BreakIterator`s again, only to reach the floor. That makes 12 layouts per view per new text.
  Dagen now has two such views per row (M1's URL rows always take this path), across 3 live pages
  (`offscreenPageLimit = 1`). The typical case, whole at the base size, is still 1 layout, as before.
- **Fix:** in `fitWords`, when `ownHyphens == NONE` and no size is whole, go straight to `Fit(sizes.last(), true)`.
  Compute `breakOpportunities` once per fit and pass it into `cutsAt`.

### L6 - No test keeps "Nødsamtale" outside the time-rule screen's scroll

- **Where:** `launcher/app/src/test/java/com/kidslauncher/mdm/ui/TimeLockModelTest.kt:243-262` (S1's structure isn't
  pinned). `activity_lock.xml:21-107`.
- **Failure:** the safety property S1 restores is that `lock_emergency_button` is a direct child of the root, under a
  `ScrollView` of `layout_height="0dp"` / `layout_weight="1"`. No test asserts it. The id-order test passes as well
  with the button back inside `lock_top` (the old, broken layout), so a later edit can bring S1 back silently.
- **Fix:** in `TimeLockModelTest`, assert that the button's parent is the root, that the root's only other child is the
  `ScrollView` with `0dp` and weight 1, and that the button has no `visibility`.

### L7 - TimeLockModelTest's "the root never clips it" check now tests nothing

- **Where:** `TimeLockModelTest.kt:305-306`; `activity_lock.xml:17` and `:21`.
- **Failure:** the assertion `screen.first().attr("clipToPadding") == "false"` exists so that the profile clock's box
  may reach into the status bar's padding. The clock now lives inside a `ScrollView`, which clips its content to its
  own bounds, and the root clips its children (`clipChildren` defaults to true). So the clock's box is clipped at the
  status bar's edge whatever the root's attribute says. Nothing visible is lost today: `LockClockFace.placeProfile`'s
  ink floor keeps the digits below `lock_top`'s 8 dp padding. But the test passes for a property the screen no longer
  has.
- **Fix:** rewrite the check to what holds now: the ink floor keeps the digits inside the `ScrollView`, and `lock_top`
  keeps its 8 dp top padding. Or drop the assertion and its comment.

### L8 - S4 only helps single-word titles

- **Where:** `ui/WholeWordText.kt:131` (`wholeCheckedLines`), `include_kid_header.xml:41`.
- **Failure:** in a one-line title whose first line ends at a break opportunity, the end of the last shown line is
  never a cut. Examples are a space ("Ny melding", a contact's name in the thread header) or a hyphen
  ("Bluetooth-enheter", "Wi-Fi-nettverk"). So the title stays at full size, and the TextView's END ellipsis cuts the
  next word between letters ("Ny mel…", "Bluetooth-enh…"). This is no regression, and it is within the "an ellipsis or a
  hyphen" rule. It is worth knowing, though, that S4's "a word is never cut" holds only for a one-word title.
- **Fix (if wanted):** for `maxLines == 1`, also treat "the shown line is followed by more text" as a cut while a
  smaller size would fit the whole title, so the title shrinks to whole text before it ellipsizes.

## Checked, no finding

- **S1 (time-rule screen):** "Nødsamtale" is measured before the weighted `ScrollView`, so it keeps its wrap height at
  font 2.0 and on any short window. It wraps with no `maxLines`. The root's `KidInsets` covers the 3-button bar, the
  gesture inset, the cutout and a side bar in landscape (the screen rotates). There is no IME. A long rows list scrolls
  above the button, and the bottom fade is in the `ScrollView` only. Clock placement (`contentTop`) still ignores
  `scrollY`, as it did with the old `ScrollView` root. `KidHeaderLayoutTest`'s top-padding walk still sums to 8 dp.
- **S5 (glance handle):** the margin is set on a sibling of `glance.root` in the lock's frame, and each sibling gets
  the insets on its own, so nothing is lost to `CONSUMED`. `tappableElement().bottom` is 0 with gestures, so the
  handle stays on the window's edge and its swipe works as before. A navigation-mode change dispatches insets again
  and the margin follows. The returned extra is added to the XML's captured padding, so it never accumulates. The
  glance is portrait-only. The bottom block with "Nødsamtale" stays above the handle's strip. No gesture is removed.
- **S2 (`Fit.kt` / `Modifier.bleed`):** the row choice covers every width between the profiles. 232-288 dp of room
  gives the narrow row with a bleed of at most 12 dp, and less room gives the scaled row, which stays within
  room + 24. The bleed is symmetric, so the non-mirroring `place` is right in RTL too. The constraints are always
  bounded (`BoxWithConstraints` with `fillMaxWidth`).
- **S8 (the keypad):** the 4 dp of padding became 4 dp of target, so `PinKeypadLayout`'s key budget is unchanged. In
  both modes "Foreldrekode" sits right on the bar's or the gesture inset's top edge. A tap in a gesture zone still
  reaches the app.
- **WholeWordTextView:** both loops are bounded (STEPS = 5) and floor at 75 %. `FitState.due` still keys on text,
  width and base, so a recycled view refits on new text, and `ownHyphens` is the XML's value, captured once. The
  hyphenation set inside `onMeasure` follows the pattern `setTextSize` already uses, with no layout loop. Default and
  `-1` `maxLines` leave the checked lines unchanged. `WordEllipsis` keeps surrogate pairs whole.
