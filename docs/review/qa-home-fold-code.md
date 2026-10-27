# Code QA: home-fold (Home's fold never lands between an icon and its label)

Branch `home-fold` (67ec3950, one commit, cut from main bc2d42c9) against main `8baaf932`. Reviewer's branch
`qa-launcher-small-b` (cut from `day-task`'s head) carries this file with the two other small reviews. Design: PLAN
"Fix backlog" ("Home's fold at four rows: the last tile's label disappears", the user's 2026-10-26 `jellystar2`
screenshot), rule (b): "the grid's bottom padding is set from the row height so the last partly visible row shows at
most half its icon (the cut through the icon reads as more below, as glance C's rule)"; (a) measured and dropped.

## Setup

- Worktree on 67ec3950 (detached: the branch is held by the implementer's worktree). I read `git diff
  home-fold~1..home-fold` in full, and the code around it: `FadingRecyclerView` (the fade strengths and the padding
  offsets), `HomeEdges.kt`'s constants and `tileHeightDp`, `item_kid_tile.xml` (the tile's hierarchy), `activity_home.xml`'s
  grid, `HomeGridAdapter.applyMetrics` and `GridGapDecoration`, and emulator.md §6's numbered steps on main.
- **Trial merge onto current main** (a throwaway branch from `8baaf932`, `git merge --no-commit --no-ff home-fold`,
  then aborted and deleted): **clean** - six files, `docs/testing/emulator.md` and `launcher/CLAUDE.md` auto-merged,
  no conflict; main's §6 list ends at step 11, so the branch's "12." is still the next number after the merge. The
  branch is also disjoint from `day-task`'s hunk in `HomeGridAdapter.kt` (the tile's click vs. the appended `foldGrid`).
- Launcher, full suite under the lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2
  -PwarningsAsErrors=true assembleDebug testDebugUnitTest`): **BUILD SUCCESSFUL in 1m 11s, 1879 tests, 0 failures,
  0 errors, 0 skipped** (`HomeEdgesTest` 14, three new; main is at 1878 from a later base). The only warning is kapt's
  `RELEASE_11` line.
- Mutations: six batches by an exact-replace script, one pure mutation each (they all land in the sweep test), each
  run `testDebugUnitTest --tests '*HomeEdgesTest'` (14 tests). The sources were put back with `git checkout --
  launcher/app/src` after each batch, `git status` empty before this file.
- No emulator (as briefed): `docs/testing/emulator.md` §6 step 12 is the emulator's - `jelly36`, 11 tiles, font 1.0
  and 1.3, gesture and 3-button navigation: the fourth row whole with its label above the fade, or cut through its
  icon (at most the top half), never a whole icon with its label under the fade; the same after a scroll down and up.

## What was checked and holds

- **Rule (b) is what the code does.** `gridFoldPaddingPx` walks the laid-out rows from the top; a row's label is
  legible when its first line's bottom is at or above the middle of the fade drawn there (`2 * labelLinePx <= 2 *
  heightPx - fade`); the first row that isn't legible decides: its icon's middle at or below the edge means the edge
  already cuts through the icon's top half (or the row is below it) - nothing moves; else the grid clips at that
  middle (`heightPx - iconMidPx` of bottom padding, never under the layout's 16 dp). Otherwise nothing changes. That
  is "the last partly visible row shows at most half its icon", with the one refinement the PLAN's screenshot needs:
  the row to judge is the first whose label is lost, not the last laid out (mutation F6).
- **The maths at the edges.** The last row's fade is only what is left to scroll (`bottomPx + paddingPx - heightPx`,
  clamped to 0..fade): a grid that fits has fade 0 and every label legible, so nothing clips (the sweep's third
  invariant); a last row 11 dp short of fitting gets a 5 dp fade and stays. A row that isn't the last always sees the
  full fade (more than a row is left to scroll). After a clip the edge is the icon's middle, 4 + 30 dp under the row's
  top, so the fade's middle (16 dp above the edge) is at least 18 dp under the row's top at 60 dp icons - above every
  earlier row's label line (rows don't overlap) - and the row's own label is under the edge with its icon halved:
  the test's `cut(edgePx, clipped)` re-judges every row at the new edge with the full fade (the clipped grid's
  remaining scroll is at least the icon's lower half plus the label, more than 32 dp) and the sweep asserts no row is
  "an icon without its label" afterwards. Four rows, the fourth partly visible: the user's case, 46.3 dp on the Jelly
  Star with gestures, 22.3 with three buttons. RTL: vertical only, nothing to check.
- **The glue reads what the rule models.** `tile.top` is the child's laid-out top with `GridGapDecoration`'s row gap
  above it (the model adds `GRID_ROW_GAP_DP` between rows); the icon's middle is `tile_frame.top + tile_icon.top +
  height / 2` (4 dp of badge room, the frame is `icon + 4 dp` with the icon at its bottom - `TILE_BADGE_ROOM_DP`); the
  label's first line is `label.top + totalPaddingTop + layout.getLineBottom(0)`; rows merge by `position / span` with
  the lowest label line and bottom. `heightPx` is the view's own height, which the padding never changes, so a second
  pass computes the same fold and the `if` writes nothing (no loop). Only at the top (`canScrollVertically(-1)`),
  only when the first laid-out row is row 0, `lastShown` from the adapter's count.
- **The fade follows the clip.** `FadingRecyclerView.getBottomPaddingOffset()` is 0 with `clipToPadding` (AbsListView's
  rule), so `View.draw` draws the bottom fade over the last 32 dp above the clipped edge, i.e. over the halved icon,
  and the padding offsets return to covering the 16 dp padding when the clip goes.
- **Padding and layout.** `LinearLayoutManager` adds the end padding to the extra layout space whatever
  `clipToPadding` says, so the rows are laid out into the padding (the rule sees the partly visible row either way),
  and with `clipToPadding = true` the clip is only a draw-time clip at `height - padding`. `setClipToPadding` alone
  only invalidates; the `setPadding` that follows it requests the layout when the padding changed, and when it didn't
  (a fold of exactly 16 dp) no relayout is needed - the layout is the same, only the clip.
- **The tests pin the rule more than the numbers.** The measured table is documentation with numbers (the Jelly Star
  and the six-inch at 1.0/1.3, both navigations: before, the clip in dp, after); the sweep over four screens, 3-4
  columns, three font scales, three navigation heights, three music-card heights, four line patterns and 1-30 tiles
  pins three invariants that don't restate the production inequality - nothing moves unless the fold is between an
  icon and its label, then by at least 16 dp; after the move no row is an icon without its label; a grid that fits
  never clips - plus the three hand cases (a last row with less than its half fade left, the full fade when the last
  row isn't known, an empty grid) and the glue's source pins. `cut()` restates the legibility threshold itself, so
  that threshold (the fade's middle) is a design choice the table documents, not something the sweep could refute.
- **Nothing else changes**: `FadingRecyclerView` keeps its seven overrides (the existing pin), the swipes and the item
  touch listener are untouched, `GRID_BOTTOM_PADDING_DP` equals the layout's `paddingBottom` (pinned), the fold runs
  once per layout pass over at most a screen of children (two `findViewById`s each).

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| F1 | an icon exactly halved clips to 16 dp (`>=` -> `>`) | the table, the sweep and the glue test |
| F2 | legible only strictly above the fade's middle (`<=` -> `<`) | the sweep |
| F3 | the last row gets the full fade | the sweep (its hand case: a last row with less than its half fade left) |
| F4 | the clip may be under 16 dp (the `max` dropped) | the sweep (at least 16 dp) |
| F5 | a legible grid clips at 16 dp (`return 0` -> `paddingPx`) | the table and the sweep |
| F6 | only the last laid-out row is judged | the table and the sweep |
| F7 | glue: a scrolled grid is folded too | `Home feeds the fold from the laid-out rows after every layout` |
| F8 | glue: a zero fold sets a zero padding (`bottom = fold`) | **not caught** |

Every mutation of the pure rule is caught, both boundaries included (the sweep's integer pixels hit the equalities);
of the glue, the scroll guard is pinned and the padding fallback is not (Q-3).

## Findings

### Q-1 (Low) - the fold is applied from inside a layout pass

`launcher/app/src/main/java/com/kidslauncher/mdm/ui/HomeActivity.kt:333-334` (`addOnLayoutChangeListener { ... foldGrid(binding.homeGrid) }`),
`HomeGridAdapter.kt:292-296` (`setClipToPadding`/`setPadding` in `foldGrid`).

What's wrong: `OnLayoutChangeListener` runs inside `View.layout()`, so a fold that changes the padding calls
`requestLayout()` during the parent's layout pass. `ViewRootImpl` allows it but logs `requestLayout() improperly
called by ... FadingRecyclerView during layout: running second layout pass` and runs that second pass in the same
frame. It converges (the second pass computes the same fold from the same rows and height, and the `if` writes
nothing), and it happens only when the fold changes - the first layout of a grid that needs one, and a change of tile
count or font scale - so it is one warning per such change, no flicker. Still the known anti-pattern, and the warning
will sit in every emulator log of Home.

Expected fix: defer the write out of the pass - `grid.post { foldGrid(grid) }` guarded by a pending flag (one fold
per frame), or `doOnPreDraw` on the grid (the rows are laid out, the draw hasn't started; a padding change there still
lands before the frame). The glue test's `addOnLayoutChangeListener` pin moves with it.

### Q-2 (Low) - a change while scrolled keeps the old fold until the next layout at the top

`HomeGridAdapter.kt:268` (`if (grid.canScrollVertically(-1)) return`).

What's wrong: the fold is recomputed only by a layout pass with the grid at its top. A scroll is no layout, so after a
content change while scrolled (an app installed or removed, a badge rebind - each a `requestLayout`) the grid returns
to the top with the fold of the previous content. Scenario: 11 tiles clipped at row 4's icon; the kid scrolls to the
end; an app installs (12 tiles, still four rows - fine) or two (13 tiles, five rows): row 4 is no longer the last, so
its fade is now the full one, but the clip stays where it was - which is the same icon middle, so the stale case only
shows when row 4 was legible under its partial fade before and isn't under the full one (no clip where one is due),
or the reverse after a removal (a clip that is no longer needed, 46 dp of room under the last row). The unlock's
`resetToTopIfDue` (a `scrollToPositionWithOffset`, a layout) and the next real layout heal it.

Expected fix: a scroll listener that calls `foldGrid` when the grid arrives at the top (`onScrolled` with
`!canScrollVertically(-1)` and a changed-content flag), or compute from the rows at any offset by passing the scroll
offset into the rule (then the guard can go).

### Q-3 (Low) - the padding fallback is unpinned

`HomeGridAdapter.kt:292` (`val bottom = if (fold > 0) fold else padding`), `HomeEdgesTest` (the glue test).

What's wrong: F8 survives - with `bottom = fold` a grid that needs no fold gets a bottom padding of 0: the 16 dp of
room under the last row at the scroll's end is gone, and the fade over the padding with it. The glue test pins the
scroll guard, the height argument, the clip flag and the single call, not this line.

Expected fix: one more `contains` on the exact line.

### Q-4 (Nit) - `foldGrid` hard-codes the tile's hierarchy

`HomeGridAdapter.kt:276-279`: `tile.top + (icon.parent as View).top + icon.top` assumes `tile_icon` sits in exactly one
`ViewGroup` (`tile_frame`) under the tile's root. A wrapper added to `item_kid_tile.xml` later would make the fold
measure the wrong middle without any test noticing. Pin it (the layout test can check `tile_icon`'s parent is
`tile_frame` and that is a direct child of the root), or sum the offsets up to the tile in a loop.

### Q-5 (Nit) - at the scroll's end the grid now stops at the clip, not 16 dp above the bar

With a fold of 46 dp (the Jelly Star, 1.0, gestures) the last row ends 46 dp above the navigation bar when scrolled to
the end, not 16 - the bottom padding is the clip. Rule (b) accepts it ("the grid's bottom padding is set from the row
height"); §6 step 12 could say so, so the run doesn't log it as a gap.

### Q-6 (Nit) - the threshold is a judgment the sweep can't test

A label whose first line ends in the fade's lower half is "not legible" and the row is cut to half its icon even
though most of the label was readable under a light fade. That is rule (b) read strictly and the table documents the
outcome; if the emulator run finds the Jelly Star at 1.3 (24.2 dp of clip) looks worse than the faded label did, the
threshold (`fade / 2`) is the one number to move.

## Docs

PLAN's entry ("Built 2026-10-27", 1879 tests, (a) measured and dropped with 22.4 / 46 dp), launcher/CLAUDE.md's fading-
edges bullet and §6 step 12 match the code: `clipToPadding` on, the padding to the icon's middle, at least 16 dp, the
fade following the clip, `foldGrid` from Home's layout listener. The step's "Scroll down and back up: the same" is
Q-2's check; it holds for a scroll alone.

## Verdict

**Merge.** Nothing Medium: the rule is (b) as the PLAN states it, every mutation of the pure function is caught at both
boundaries, the edge cases (a grid that fits, a last row short of fitting, a partly visible row that isn't the last,
the clipped edge re-judged) hold, and the branch merges cleanly onto current main. Q-1 (the in-layout write) is the
one worth a few lines before the merge; Q-2 and Q-3 can ride with it. The screen is §6 step 12's to prove.

Suite results, verbatim: `BUILD SUCCESSFUL in 1m 11s` - 1879 tests, 0 failures, 0 errors, 0 skipped.
