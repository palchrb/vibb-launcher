# Code QA: the Jelly Star's layout (2026-11-20)

Branch `jellystar-layout` (worktree checked to be on it), diff `9edca83f6...jellystar-layout`, commits 35386ef94 (Home),
7767aa81b (lock glance), 45478a0da (Telefonbok), 126579149 (emulator.md §3). Read: the diff, `HomeActivity`'s
content/night swap and the grid's XML (`activity_home.xml`: weight 1, `clipToPadding=false`, 16 dp bottom padding),
`HomeGridAdapter`'s `GridGapDecoration`, `HomeEdges.kt`, `KidInsets`, `GlancePanel`'s handle and
`handleAboveButtons`, `LockGlance.kt`, `PhoneBookList.kt`, `PhoneBookActivity`'s call site, the manifest's
`configChanges`, the touched tests and notes, and the findings file `jellystar-layout.md`. No Gradle, no emulator.

## Verdict

No H in the code. All three changes are correct as code:

- **Home.** `fitGridRows` runs from a global-layout listener, which fires after the layout pass, so its
  `invalidateItemDecorations()` is not a `requestLayout` inside a layout and RecyclerView's
  `assertNotInLayoutOrScroll` holds.
  - It settles in one pass. GridLayoutManager re-measures every child of a row to the row's height less that row's own
    (equal) decoration inset, so `child.height` doesn't depend on the gap. In the squeeze band (an overflow of at most
    8 dp per gap) the last row is always partly laid out. The truncated `toInt()` stays at or under the even gap, so
    the next pass gives the same answer.
  - The lambda can't go stale. `invalidateItemDecorations` marks the attached and cached children dirty, and
    `Adapter.bindViewHolder` marks every rebound pool view dirty.
  - The listener is added to the grid's floating observer before the first `setContentView`. The attach merges it into
    the window's observer, it stays registered once across the night ground's swaps, and `contentShown` guards it.
  - A rotation or a split window (`orientation|screenSize`, handled in place) changes the grid's height, which
    triggers the next global layout. A font-scale or density change recreates the activity.
- **Lock glance.** `glanceBottomRoom` gives the same result as before for 3-button (margin = tappable, extra = the
  handle's view) and for every gesture inset at least as tall as the handle's view.
  - The handle's height is fixed at construction, before any insets arrive.
  - `KidInsets` calls `extraBottom` on every dispatch, so a bar toggling or insets arriving late re-decide it.
  - Nothing in the lock hides the system bars, so `getInsets(navigationBars())` is never 0 because a bar is hidden.
  - TalkBack's unlock node (the handle view, `handleAction`) is untouched.
- **Telefonbok.** On API 34+, `TypedValue.applyDimension(COMPLEX_UNIT_SP)` goes through `DisplayMetrics`'
  `fontScaleConverter`, so `nameDp` is the non-linear size on Android 14-17. The 1.3 table gives 16 sp → 20.2 dp, and
  6 em = 121 dp ≤ 128 (inline). The 1.5 table gives 23 dp, and 138 > 128 (stacked).
  - RTL doesn't change the rule (it depends on width only).
  - Font 2.0 at 288 dp stacks.

One M: the Home change can't fix the phone it was approved for. The branch's own emulator numbers show the rows
overflow by more than the gaps can give, so the user's photo is unchanged on the Jelly Star. That needs to go back to
the user before the queue item is called done. The rest is L.

## M1 - Home: the squeeze can never fire for the Jelly Star's reported case; the approved item (1) stays unfixed

`HomeEdges.kt:132` `rowGapToFit`, `HomeActivity.kt:936` `fitGridRows`; pinned as "unchanged" by
`HomeEdgesTest.kt:242-243`; written down in `docs/notes/launcher/ui-polish.md` ("Its limit ...").

**Scenario.** On `jellykeys36` (the Jelly Star's window), with one contact and ten tiles, the branch measured:

| Font | Rows (px) | Grid content (px) | Room left for 3 gaps |
|---|---|---|---|
| 1.15 | 571 | 589 | 18 px (4 dp per gap) |
| 1.3 (the user's) | 591 | 585 | -6 px: the rows overflow with **no** gaps at all |

The squeeze's floor is 6 dp (9 px) per gap, so at 1.15 nothing changes. At 1.3 no gap could help. The gear's
"Innstillinger" still lies under the edge and the fade on the phone in the photo. The findings file's estimate (about
16 dp over) was wrong by a factor of about 3.

The code ships a mechanism that only acts on a screen 17 dp taller (`wm size 480x880`, the test's case) or on other
phones. PLAN's item (1), "shrink row gaps 14->6 dp when it only just overflows", would read as fixed when the user's bug
is still there. That conflicts with "I never give the impression that something is being built when it is not".

**Fix.** This is a visible choice for the user, not a code fix. Tell them the numbers and offer the levers:

- (a) Let a grid that doesn't scroll use its 16 dp bottom padding too. The padding exists for the fade and the scroll
  end, and with no scroll there is no fade. That is +24 px of content: at 1.15 the gaps become 14 px (9.3 dp), and at
  1.3 6 px (4 dp). Both fit. The cost is that the last label ends at the panel's edge on a phone with keys.
- (b) A lower floor than 6 dp. This only helps 1.15 (4 dp gaps), not 1.3.
- (c) Shrink a wrapping label to one line at large fonts (WholeWords' 75 % step), so row 3 doesn't grow.
- (d) Bring back the fold rule, which the user rejected on 2026-11-01.

Until the user picks one, PLAN's entry for (1) should say "mechanism built; does not fix the Jelly Star at 1.15/1.3,
choice to the user". It should not say done.

## L1 - Home: one frame is drawn with the old gap before the squeeze

`HomeActivity.kt:353, 962`. `ViewTreeObserver`'s global-layout dispatch runs after `performLayout` but before
`performDraw` in the same traversal. The `requestLayout` it schedules lands in the next frame, so the frame in between
draws the old gaps: 14 dp, a scroll and the fade, then the squeezed rows one frame later.

This happens whenever the outcome changes:
- the first show;
- a tile added or removed;
- the music card or the contact strip changing the grid's height.

The jump is at most 8 dp per row, once.

Fix: do the fit in an `OnPreDrawListener` that returns `false` when the gap changed, which cancels that frame. The
alternative is to override `onLayout` in `FadingRecyclerView` and lay out again before the frame is drawn. That also
keeps it off every other layout of the window (L2).

## L2 - Home: the listener runs on every layout of Home's window

`HomeActivity.kt:353`. The listener sits on the window's tree observer, so it runs on every layout anywhere in Home:
the music card, badges, the clock line. Each run allocates an `IntArray`, a `List<Float>` and the `map`. That is cheap,
but it is per layout for the activity's lifetime.

Fix: an `OnLayoutChangeListener` on the grid plus the adapter's data observer, or the `onLayout` override in L1.

## L3 - Home: tests that pin nothing

- `HomeEdgesTest.kt:218-228`. "Laying out again with the result keeps it" calls `rowGapToFit` twice with identical
  inputs, so it can't fail. The real reason it settles, that heights don't depend on the gap, can't be checked by a pure
  test. Drop the assertion, or reword the comment to say what the loop actually checks (truncation keeps the rows
  within the content).
- `HomeEdgesTest.kt:239` (`571 + 3 * 14 <= 615`) and `LockGlanceTest.kt:1054` (`bottom + 24f + 48f / 2 == 56`) are
  constant arithmetic. Harmless as documentation, but they are not checks.

## L4 - Telefonbok: "linear is an upper bound" holds for the 16 sp name, not for the 13 sp line

`WholeWordsTest.kt:175` (comment) and `:214`. The Android 14+ tables at 1.15, 1.3 and 1.5 scale 14 sp above linear
(16.4, 18.8 and 22 dp against 16.1, 18.2 and 21). So 13 sp renders at about 15.1, 17.2 and 20.0 dp, while the test
uses `13f * scale` (14.95, 16.9 and 19.5). For 16 sp linear really is above (20.8 against 20.2 at 1.3).

The 13 sp line now runs at 1.3 in the 128 dp inline column (before this branch it had 214 dp). The parts are far
below 128 dp, so nothing fails today, but the test's margin claim is false by about 2 %.

The `lines <= 2` assertion on `:222` is a tautology (lines is set to 1 or 2 just above). That is from before this
branch.

Fix: use the size the phone renders in the 13 sp test, for example `13f * scale * 1.03f` as an upper bound or the
tables' values, and fix the comment.

## L5 - Telefonbok: font 1.3 inline puts names in 128 dp instead of 214, and wide phones now stay inline at 2.0

`PhoneBookList.kt:69`, `PhoneBookListTest.kt:197`.

- **At 1.3 on the Jelly Star.** A name now has 128 dp at about 20 dp. `WholeWordTextView` shrinks a single long
  word (a "Kristoffersen"-length surname, or "Nødsamtale" near the edge) toward 75 %, where the stacked form had room.
  The user chose this trade, but `WholeWordsTest`'s name list has no word longer than "Emergency call", so the shrink
  case at 1.3 isn't seen.
- **On a 360 dp phone.** The column is 200 dp and stays inline up to a 33 dp name, so it is inline at font 2.0 too.
  Until now, every phone stacked from 1.3. No `WholeWordsTest` row covers a 200 dp column at 2.0, neither the name nor
  the 13 sp line at about 25 dp.

Fix: add a long single-word name to `names`. Run the row tests at a 200 dp column too, or cap inline at the 288 dp
answer (stack from 1.5 everywhere) if the user wants one rule.

## L6 - Siste and Kontakter now part at 1.3

`RecentCalls.kt:190` `recentTimeBelow(fontScale >= 1.3f)`, on the raw scale. At 1.3 a contact row keeps its buttons
inline, while a Siste row moves its time under the word. That is deliberate per the design 106 edit, and the two tabs
don't share a layout, so it is only a note. If the user wants one rule, compute it from the 13 sp size the way
`rowLayout` now does.

## L7 - Lock: gesture phones whose inset is lower than the handle's view now gain room

`LockGlance.kt:268`: `maxOf(0, handleViewPx - navBarPx)`. Before, every gesture phone added 0.

- **A phone whose gesture inset is below 24 dp.** Some OEMs report about 16-20 dp, or 0 with Samsung's "gesture hint"
  off. On such a phone the glance's bottom row now moves up by the difference: 0-24 dp.
- **A side navigation bar in landscape.** The bottom inset is 0, so the page keeps 24 dp too. The lock is
  portrait-only in practice.

Both are arguably right (the handle gets its room), and the KDoc says so. But the claim "gestures unchanged" in the
note and the emulator §3 text holds only for insets of at least 24 dp. The pixel rounding of `handleViewPx` against the
inset can add 1 px at odd densities, which is harmless.

No fix is needed. Reword "unchanged with gestures" to "unchanged where the gesture inset is at least the handle's
view".

## Checked, no finding

- **Requesting a layout during a layout.** None (above).
- **The phone book on rotation or in a split window.** `rowLayout` is computed in the contacts render from
  `screenWidthDp`, unchanged by this branch. `nameDp` doesn't change on rotation. A font change recreates the activity
  (`fontScale` is not in `configChanges`).
- **3-button.** The handle's margin and the extra room are identical to before (`LockGlanceTest` row "3 buttons").
  Home's squeeze is generic. On any phone, rows that overflow by ≤ 8 dp per gap now fit instead of scrolling, which
  is the intended rule. With five rows that is up to 32 dp.
- **Android 14-17.** Only `WindowInsets.Type.*` (API 30) and `TypedValue.applyDimension` are used. No new gate is
  needed. On 14+ the non-linear converter is set for every scale from about 1.03 up, and below that it is linear.
- **`emulator.md` §3.** The `sed` matches `jelly36`'s `hw.mainKeys = no` line as written (`config.ini:86`). The
  `jellykeys36` copy exists with `yes`.
- **TalkBack.** The handle's node, its bounds at the window's edge and `handleAction` are unchanged. On the keys phone
  the page's content no longer overlaps the handle's view.
