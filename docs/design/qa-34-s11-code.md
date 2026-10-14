# QA 34 step 11 (code) - the music app's empty states, the measured cover fit, soft shadows

Reviewer: code QA, 2026-10-14. Branch `design-34-s11`, `500b0a08..ab6041b8` (one commit, music app and docs).
I read it against `docs/design/34-launcher-polish.md`: P10, §2, §5 step 11, §7 (QA L1, L7), and the new "Step 11
implementation status". I also read `qa-34-design.md` (L1, L7), the `07-music-empty-*` and `08-music-library-*`
mockups, `music/CLAUDE.md` and the root `CLAUDE.md`.

In the code I read:
- the whole diff;
- `LibraryScreen` in full: the header, `CategoryTiles`/`CategoryTile`, `NowPlayingBar`, and the split `Carousel` /
  `CarouselPager` / `CarouselTitle`;
- `Widgets.kt` (`Line`, `Glyph`, `EmptyState`), `Theme.kt` (`text`, `Nunito`), `NewsScreen` and `SearchScreen`;
- `MusicRoot` (`safeDrawing` insets, so the IME is included) and the manifest (portrait only, `adjustResize`, no
  `configChanges`).

Line numbers are `ab6041b8`'s. No product code was changed. The mutations below were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge. A short fix round for L1 is worth it but doesn't block.** The hard rules hold:
- Visual only. No behaviour changed. The empty library keeps search and Nytt.
- The carousel's swipe, tap-to-turn, tap-to-play and snap are untouched. The only new gesture is a vertical scroll
  of the slot, and only below the 120 dp floor (2.0 with three-button navigation and the bar, under 20 dp).
- No "Spotify" anywhere. The new strings are calm, with no exclamation marks.

The structural fix for QA L1 is right. The header, the tiles and the bar are unweighted and keep their own height. The
carousel is the single `weight(1f)` slot, and the slot clips or scrolls inside its own bounds. So the tiles can't
reach the bar at any font scale, with or without the bar, in either navigation mode. The app is portrait-locked, so
landscape doesn't apply.

Other checks that passed:
- **The measurement.** "Ag" measured with `maxLines = 1` gives the same height as any one-line `Line` of the same
  style. It's the same `TextStyle`, the same Nunito and the same `Density`, so Android 14's non-linear font scaling is
  included. Neither line can wrap: both are `maxLines = 1`, with an ellipsis.
- **No recomposition storm.** `measuredTitleLines` is remembered on the density and font scale. The
  `BoxWithConstraints` only re-subcomposes when the constraints change. The per-frame work (scale, alpha, pull,
  `shadowElevation`) all stays in the `graphicsLayer` block.
- **No per-frame allocation.** `Dp` is a value class, and `shape` is held across frames.
- **The shadow's cost.** The old `.shadow()` already sat under a layer whose scale changed every frame, so its
  tessellation was already redone each frame. Animating the elevation adds next to nothing.
- **A runtime page-size change.** When the bar appears or the font scale changes, `PageSize.Fixed(cover)` changes but
  the `PagerState` keeps `currentPage` and its offset. A font-scale change recreates the activity anyway.
- **The empty states.** TalkBack sees one merged node for the headline and body. The disc has no semantics:
  `Glyph` passes `contentDescription = null`. Nothing animates or is clickable.
- **The bar's clip.** `NowPlayingBar`'s `shadow(2.dp, shape)` clips by default, so the old `clip` was safely replaced.

The implementer's decisions are right:
- **Search and Nytt stay in the empty header.** Removing them would change behaviour.
- **`coverDecodePx` stays at 224 dp.** The library cover is at most 180 dp, and the library and the player share one
  decoded bitmap per hash.
- **The progress line's room is always kept.** This stops a 10 dp resize on every turn.
- **Search's empty state starts from the top.** It sits above the IME, and `safeDrawing` includes the IME.

The one real finding is a rounding overflow at hdpi (**L1**). That density is the Jelly Star's, the target phone.

Counts: 0 High, 0 Medium, 2 Low, 6 Nits.

## What I ran

- From `music/`, under the lock: `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug
  --rerun -PwarningsAsErrors=true`. BUILD SUCCESSFUL: **853 tests, 0 failures, 0 skipped**, lint clean and no
  warnings.
- Ten targeted mutations, each one run against the relevant test classes and then reverted (table below).

## Low

**L1 - On the Jelly Star (density 1.5) the slot's content is 2 px taller than the slot, so the dots lose their bottom
2 px.** This happens whenever the cover is between the floor and 180, for example at font scale 1.3 with the bar
(about 174 dp).
- `libraryCover` (`Fit.kt:129`) works in float dp. Compose rounds every fixed part to whole pixels separately:
  - the title column's `spacedBy(3.dp)` (`LibraryScreen.kt:292`) is 4.5 px, rounded to 5, twice;
  - `Spacer(7.dp)`, or the progress line's `3.dp` + `4.dp`, is 10.5 px, rounded to 11 (`:302`);
  - the pager box's `(cover + 4).dp` (`:231`) rounds half up.
- The 13 dp `CAROUSEL_TITLE_FIXED_DP` is 19.5 px in the model but 21 px on screen. With the cover's rounding the
  column comes out at slot + 2 px.
- In this range `libraryScrolls` is false. The disabled `verticalScroll` (`:198`) still clips, so the 12 px dots are
  cut flat at the bottom. At density 2 or 3 every part is a whole number of pixels, which is why the dp model and
  `FitTest` don't see it.
- *Fix:* the smallest is a 2 dp slack in `LIBRARY_SLOT_FIXED_DP` (`Fit.kt:123`; 43, with a comment on why), plus a
  `FitTest` case at density 1.5 that rounds each part as Compose does. Alternatively, compute the cover in whole
  pixels: `floor(slotPx - linesPx - Σ roundToPx(part))` converted back to dp.

**L2 - The source checks are pinned to formatting, not to meaning.**
- `EmptyStateTest.kt:44` matches the library's `EmptyState(` call with its exact newlines and 16-space indentation.
  `EmptyStateTest` and `FitTest`'s two new source tests also match about 25 whole expressions character for character
  (for example `".verticalScroll(rememberScrollState(), enabled = libraryScrolls(slot, lines)).heightIn(min =
  maxHeight)"`).
- A reformat, a renamed local or an argument moved to a new line fails them without any change in behaviour. A
  regression written slightly differently passes: see mutation m9.
- *Fix:* collapse whitespace before matching (`text.replace(Regex("\\s+"), " ")`). Keep the few checks that carry
  meaning: the single weighted slot, `libraryScrolls` as the scroll switch, the reserved spacer, the merge, the
  missing description, and no fixed `180.dp`. Drop the rest, such as the `measure("Ag", text(21.sp, 800,
  Palette.white)...)` literal, the bar's modifier chain and the exact lerp text. Less test code is quality here.

## Nits

- **N1 - The centred cover's shadow is clipped at the top of the slot.** `verticalScroll` clips on its axis even
  when it is disabled (`LibraryScreen.kt:198`). Between the floor and 180 the pager box starts at the slot's top
  edge, with only the 2 dp `CAROUSEL_BOX_EXTRA_DP` above the cover. So the top of the 12 dp ambient shadow ends in a
  straight edge. On the night ground it is barely visible. *Fix:* apply `verticalScroll` only when
  `libraryScrolls(...)` is true (the rest of the chain unchanged), or accept it.
- **N2 - At font scale 1.3 the cover jumps between 180 and about 174 dp when the bar appears or goes.** This uses the
  model's numbers with three-button navigation. The change is instant, and the pager's page size changes with it. It
  works as designed and the step is small, but it is visible. Name it in the emulator pass (§5 step 11), or accept
  it.
- **N3 - The room goes around the carousel, not above the bar.** Mockup `08-music-library-after` puts the tiles just
  under the dots, with the spare room above the bar. The build centres the carousel in its slot, and the tiles sit
  8 dp above the bar, which is v12's layout. Either is fine and calm. The status lists other differences from
  mockup 08 but not this one. Add a line to it, or ask the user.
- **N4 - `EmptyState` crashes with an unbounded height.** It uses `heightIn(min = maxHeight)` (`Widgets.kt:309`),
  which throws if a future caller gives it infinite height, for example inside a scrolling parent. All three callers
  pass `weight(1f)`, so nothing breaks today. *Fix:* say in the KDoc that it needs a bounded height, or guard it
  with `if (constraints.hasBoundedHeight)`.
- **N5 - Parts of `FitTest`'s Jelly Star test are tautologies.** The assertions at `FitTest.kt:286` and `:288`
  (`cover in 120..180`; `content <= slot || libraryScrolls`) hold for any input, because they restate
  `coerceIn` and the definition of `libraryRoom`. The meaningful checks are the specific numbers in `:293`-`:298`.
  *Fix:* drop the loop body's two asserts and keep the loop, if anything, to print the table. The real guarantee
  (tiles never under the bar) is structural and is pinned by the source check on the single weighted slot.
- **N6 - Untested by design: mutation survivors m8 and m9.** m9 swaps search's top start with Nytt's centring, and no
  test notices. A short `centred = false` check, ideally whitespace-insensitive (L2), would catch it. m8 drops the
  font-scale key of `measuredTitleLines` and is harmless, because the activity has no `configChanges` and is
  recreated on a font-scale change. No test is needed for m8.

## Mutations

| # | Mutation | Tests run | Result |
|---|---|---|---|
| m1 | floor `LIBRARY_COVER_MIN_DP` 120 -> 100 | `FitTest` | killed (`:245`) |
| m2 | `libraryScrolls`: `<` -> `<=` | `FitTest` | killed (`:244`) |
| m2b | the slot's scroll `enabled = false` | `FitTest` | killed (`:329`, source check) |
| m3 | the reserved progress `Spacer(7.dp)` -> `0.dp` | `FitTest` | killed (`:338`, source check) |
| m4 | a weighted `Spacer` before the tiles (the tiles' slot) | `FitTest` | killed (`:323`) |
| m5 | en `nothing_found` = "Nothing found on Spotify" | `SourceScanTest` | killed (`:464`) |
| m6 | `EmptyState` without `.semantics(mergeDescendants = true)` | `EmptyStateTest` | killed (`:63`) |
| m7 | the shadow's lerp reversed (`1f - f`: neighbours 12 dp) | `FitTest` | killed (`:346`, source check) |
| m8 | `measuredTitleLines` remembered on `measurer` only | `me.vibb.music.ui.*` | **survived** (harmless, N6) |
| m9 | `EmptyState` centring swapped (search centred, Nytt/library top) | `me.vibb.music.ui.*` | **survived** (N6) |

8 of 10 killed. Every kill of a wiring mutation (m2b, m3, m6, m7) comes from a character-exact source check (L2).

## Counts

0 High, 0 Medium, 2 Low (L1-L2), 6 Nits (N1-N6).
