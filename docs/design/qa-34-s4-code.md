# QA 34 step 4 (code) - Home's fading edges, two-line labels, the contacts row that fits

Reviewer: code QA, 2026-10-14. Branch `design-34-s4`, `35676753..7831e7fa` (one commit, launcher and docs).
I read it against `docs/design/34-launcher-polish.md`: P4, §2, §5 step 4, §7 (QA L6, M6), the step 0-3 status
sections and the new "Step 4 implementation status". I also read `qa-34-design.md` (L6), the `01-home-*` mockups,
`launcher/CLAUDE.md` (Home, the gestures) and the root `CLAUDE.md`.

In the code I read:
- the whole diff;
- `HomeActivity`'s gesture wiring: the `GestureDetector` and `onFling`, the grid's `OnItemTouchListener`, the
  contacts row's observing `OnTouchListener`, `startedInContactsRow`, `renderContacts` and `contentWidthDp`;
- `item_kid_tile.xml`, `item_kid_contact.xml`, `activity_home.xml`, `KidAvatars.dp`/`sizeContact`,
  `PressFeedback`'s cell ripple;
- for the framework behaviour: AOSP 14's `HorizontalScrollView` (`onInterceptTouchEvent`, the fading-edge
  strengths) and, from memory of the same release, `View.draw`'s fading-edge path, `GestureDetector` and
  `ViewGroup.dispatchTouchEvent`.

Line numbers are `7831e7fa`'s. No product code was changed. The mutations were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge after a short fix round, and the gesture point (M1) goes to the user.** The step does what P4 asks:
- The top stays empty, and a test pins the order of Home's children.
- The fades have the right lengths, with no fade at an edge that can't scroll.
- The padding offsets match AbsListView's, so the fade covers the 16 dp bottom padding.
- Labels are two lines with an ellipsis.
- The badge fix finds the real cause (a 312 dp row in 288 dp), and the fix is small.

`FadingRecyclerView` adds no touch handling, the HSV fade is drawing only, and nothing in P2's press or focus path
changed.

**The gesture change, for the user.** The rule in `onFling` ("a left swipe that starts in a contacts row that can
still scroll stays a scroll", `HomeActivity.kt:342-346`) is unchanged. As written, it now lets the swipe through for
three contacts on the Jelly Star, because the row no longer scrolls. Rows that still overflow still block it at rest.
That matches the implementer's description. But the rule doesn't actually run on the swipe's own start point (M1).
When a swipe starts on a contact, the gesture detector never sees its DOWN. It decides with the DOWN of an earlier
gesture, usually the last grid touch. So "a left swipe from the contacts row now opens kid Settings" is not
something this code guarantees, before or after this step. That bug was already there. This step didn't cause it,
but the step's docs now claim an outcome on top of it.

Counts: 0 High, 1 Medium, 3 Low, 5 Nits.

## What I ran

- `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` (from
  `launcher/`, under `~/build.lock`) passed: 126 test classes and 1191 tests, 0 failures. `HomeEdgesTest` passed 9/9.
- I ran 10 mutations, each against `ui.home.*` and `ui.Home*`. 6 were caught and 4 survived (m5, m6, m7, m9). The
  table is at the end.

## Medium

### M1. A swipe that starts on a contact is decided with an old DOWN

Where: `HomeActivity.kt:319-349` (`onFling`), `:375-378` (the row's listener) and `:383-389`
(`startedInContactsRow`). The step's claim is in `34-launcher-polish.md:620-623`. `launcher/CLAUDE.md:111` and the
new P4 section state the same rule as fact.

How a left swipe that starts on a Home contact travels:
1. The DOWN goes to the contact item, which is clickable (`setOnClickListener`/`setOnLongClickListener`) and takes
   it. The row (`HorizontalScrollView`) passes the DOWN to its child. Its own `OnTouchListener` runs only for events
   the row handles itself, so the listener doesn't see the DOWN. The grid's `OnItemTouchListener` and
   `Activity.onTouchEvent` don't see it either.
2. Past the touch slop, the row intercepts. AOSP 14's `HorizontalScrollView.onInterceptTouchEvent` has no "can't
   scroll" check (unlike `ScrollView`). The item gets CANCEL, so there's no accidental click. The listener sees the
   remaining MOVEs and the UP.
3. On UP, `GestureDetector` calls `onFling(mCurrentDownEvent, up, ...)`. `mCurrentDownEvent` is the last DOWN the
   detector was given: usually a grid touch (through the item touch listener), or null on Home's first gesture.
4. So:
   - `startedInContactsRow(e1)` tests where the old touch was, not this one.
   - `diffX`/`diffY` subtract coordinates local to two different views (the row's and the grid's).
   - With a grid DOWN as `e1`, the guard is false, and Settings opens or not depending on where the earlier touch
     was.
   - With a null `e1` nothing happens.
   - "Stays a scroll" for five contacts isn't reliable either.

This step didn't cause this. It dates from design 08's swipe guard, and with three contacts the items already covered
the whole row (312 dp) before this step. But the step changes the guard's input (`canScrollHorizontally(1)` is now
false for three contacts). It also documents the outcome ("now opens kid Settings like everywhere else on Home")
without the emulator pass, which is still to come. A swipe that starts in the empty space of a short row (one or two
contacts, below a one-line name) does give the detector its DOWN, so it works as described.

**Fix:**
- **In this step:** reword the claim in `34-launcher-polish.md:620-623` and the CLAUDE.md lines as "the rule's input
  changes; the outcome is checked in the emulator pass". Add these cases to the step's emulator checklist: three
  contacts and five contacts, swiping left from an avatar, (a) as Home's first gesture and (b) right after tapping a
  grid tile's label area and coming back.
- **Later, only with the user's OK** (it changes which swipes open Settings):
  - Feed the detector from one place in screen coordinates. Override `HomeActivity.dispatchTouchEvent(ev)` with an
    observe-only feed (`val c = MotionEvent.obtain(ev); c.setLocation(ev.rawX, ev.rawY); detector.onTouchEvent(c);
    c.recycle()`), then `return super.dispatchTouchEvent(ev)`. Gate it on `contentShown` as `onTouchEvent` does.
  - Drop the three per-view feeds.
  - Then `e1` is always this gesture's DOWN, and `startedInContactsRow` can compare `e1.x/y` directly.
  - Pin it with a source check like the existing ones: one feed, no per-view feeds.

## Low

### L1. The fade snaps on and off at the first and last pixel

Where: `FadingRecyclerView.kt:23-27` and `HomeEdges.kt:36-37`.

`fadeStrength` is 0 or 12/32 (top) and 0 or 1 (bottom), keyed on `canScrollVertically(±1)`. Scroll one pixel
down from the top and a full 12 dp fade appears in one frame. Arrive at the end and the whole 32 dp bottom fade
disappears in one frame: the last row's labels jump from faded to full ink. `ScrollView` and `AbsListView` ramp the
strength with the distance left (`HorizontalScrollView` does it for the contacts row too, so the two fades now behave
differently). Plain `View` and `RecyclerView` don't. For a "calm" Home, the snap at the end is the visible one,
because it happens while a fling settles.

**Fix:** ramp the strength. Make `fadeStrength(remainingPx: Int, edgePx: Float, lengthPx: Float)` return
`min(1, remainingPx / edgePx) * edgePx / lengthPx` (0 when `remainingPx <= 0`). The remaining distance is
`computeVerticalScrollOffset()` for the top and `range - extent - offset` for the bottom. Pin it in
`HomeEdgesTest`: 0 at 0, half at half the edge, full from the edge's length on.

### L2. The contacts row's width comes from `screenWidthDp`, not the row

Where: `HomeActivity.kt:676` and `:824`; `HomeEdges.kt:82-87`.

`contactItemPx` floors `(screenWidthDp - 32) * density / count`. `Configuration.screenWidthDp` is an integer derived
from the window's pixel width, and the window manager can round it up rather than down. Rounding up makes the model
up to 0.5 dp × density px wider than the real row. The floor absorbs up to 1 px. At density ≥ 2 the gap can be more,
and on such a phone three contacts in a tight row would scroll by a pixel or two. The row would then fade at its
right edge and turn the swipe guard back on. These are the very symptoms this step removes. The Jelly Star (480 px
at 1.5 = 320 dp exactly) is not affected. The test sweeps the model only, so it can't see this.

**Fix:** size from what is measured. In `renderContacts`, use `binding.homeContainer.width - paddingStart -
paddingEnd` once laid out (fall back to the model before the first layout). Or keep the model and take 1 px of slack
per row: `floor((contentPx - 1) / count)`.

### L3. Two source checks pin a string, not the behaviour

Where: `HomeEdgesTest.kt:82-86` and `:213-218`.

- "Consumes no touch" is checked by searching for three method names. `init { setOnTouchListener { _, _ -> true } }`
  in `FadingRecyclerView` consumes every grid touch and passes (m6 survived). That's exactly the hard rule's failure:
  no taps, no scroll, no swipe-up from the grid.
- `assertTrue(src.contains("maxWidth = itemPx"))` passes for `maxWidth = itemPx + 40`, which lets a name run wider
  than its item (m7 survived). The `LayoutParams(itemPx, ...)` check has the same weakness.

**Fix:**
- For the touch check, assert that `FadingRecyclerView`'s class body declares no members other than the six
  overrides. Parse `override fun (\w+)` and compare the set. That also catches a listener in an `init`.
- For the wiring, match the whole line with a regex anchored at the end (`maxWidth = itemPx\s*$`, multiline).

## Nits

- **N1. The 76 dp row has no room check.** `HomeModel.kt:107` returns 76/28 for one contact at 76-83 dp of content.
  `contactItemDp` then gives 76-83 dp, less than `contactRoomDp(76)` = 84, despite "every item keeps its room". The
  width isn't a real one, and the test's sweep starts at 200 dp, so the case is never tried. The same goes for the new
  64 dp room check at `:108`: it only bites for one contact at 64-71 dp, and removing it passes every test (m5
  survived). Add `&& count * contactRoomDp(76) <= contentWidthDp` (as for 64), or say in the KDoc that the room check
  covers only tiny widths. Either way, start the sweep at 1 dp so both checks are exercised.
- **N2. A long single word now breaks mid-word.** At font scale 1.3 and 2.0, "Innstillinger" (and similar
  single-word app names) is wider than a 3-column cell (about 86 dp of text width). With `maxLines="2"`, Android
  breaks the word between characters with no hyphen ("Innstillin / ger"); before, it ended in "…" on one line.
  Readable, but consider `android:hyphenationFrequency="normal"` on `tile_label`, which hyphenates where the locale
  has patterns. Look at it in the emulator pass at 1.3/2.0.
- **N3. `contactRoomDp`'s `max(overhang, ring)`.** It works because both are 4 dp, but the two don't add: the badge
  overhangs on the end side only, while the ring is on both sides. The name "room on both sides" over-reserves 4 dp
  at the start. That's harmless. A one-line comment would save the next reader the arithmetic.
- **N4. The doc's "frame time" is unmeasured.** The fading edge makes `View.draw` take its slow path on every grid
  frame. While scrolled, it saves one unclipped layer per faded edge per frame (on the Jelly Star, about 432×18 px at
  the top and 432×48 px at the bottom) and calls `canScrollVertically` twice per draw. That's cheap. Shadows drawn
  inside the region are faded with the content, which is right. Put a `dumpsys gfxinfo` line in the emulator pass
  next to step 2's missing frame-time note (§ step 2 status), so both are measured once.
- **N5. The test's row model is optimistic about the contacts row.** `HomeEdgesTest.kt:140` counts the contacts row
  at 76 dp avatars with names of two lines. It doesn't count the music card (`home_music_card`, 12 dp + its height),
  which sits between the contacts and the grid while music plays. At 2.0 with three buttons, a whole row plus the
  fade may not fit then. Either add the card to the model, or state in the test that it's the no-music case.

## What holds (checked, no finding)

- **No touch in `FadingRecyclerView`.** It overrides only the two strengths and the four padding offsets. The grid's
  `OnItemTouchListener`, the swipe-up check (`canScrollVertically(1)`), clicks, long-press and the fling are
  RecyclerView's as before. The swipe-up at the grid's end is unaffected: `canScrollVertically` is unchanged, and the
  bottom fade is drawing only.
- **The padding fade.** With `isPaddingOffsetRequired()` true, `View.draw`'s fade rectangle runs from the view's top
  to its bottom including the padding (`getFadeTop`/`getFadeHeight` plus the offsets), exactly as `AbsListView` does.
  The last row can't reappear unfaded inside the 16 dp padding. At the end of the list `canScrollVertically(1)` is
  false (the layout manager counts the padding), so there's no fade at rest.
- **The HSV fade.** `getLeftFadingEdgeStrength` ramps with `scrollX`. The right strength is ≤ 0 when the content
  fits, so `View.draw` draws no right fade (`> 1 px` check). A row that fits never fades; a scrolling row fades
  16 dp at the side it can move to. A 3-contact row no longer has a scroll range, so it has no overscroll stretch
  either (`OVER_SCROLL_IF_CONTENT_SCROLLS`).
- **The contacts row's touch.** The row still intercepts a horizontal drag when it can't scroll (AOSP 14), so a left
  swipe over an avatar cancels the item's click. The narrower 96 dp items can't turn a swipe into a tap. Long-press
  on an avatar is unchanged. The tap target is 96 × ~120 dp, well above 48 dp. TalkBack order is the add order, as
  before.
- **The badge.** With 96 dp items the third badge ends at 282 of 288 dp, inside the frame (88 dp in a 96 dp item),
  with 6 dp left. P2's focus ring (4 dp out) fits in the 10 dp on each side of a 76 dp avatar. Scrolling rows keep
  `(itemDp * density).toInt()`, the same truncation as `KidAvatars.dp`, so they really are unchanged.
- **Rounding.** `count * floor(W * d / count) <= W * d` for every count. The test's sweep (1-8 contacts, 200-480 dp,
  four densities) checks it and the room, except N1's tiny widths.
- **Labels.** `tile_label` is `maxLines=2` plus `ellipsize=end`, and contact names were already two lines.
  GridLayoutManager stretches every tile in a row to the tallest, and the tile is a top-gravity vertical
  `LinearLayout`, so the icons stay aligned. P2's ripple is masked to an oval at the icon's position, not the cell,
  so a taller cell doesn't stretch it. The row model (1.0/1.3/2.0, 3 and 4 columns, 240/288/360 dp) grows with the
  scale, and the bottom fade (32 dp) is always shorter than a one-line tile.
- **P1's tones and P2's press feedback and focus ring.** Not touched: `HomeGridAdapter` and `PressFeedback` changed
  only in visibility (`internal`).

## Mutations

Each mutation was applied and run against `ui.home.*` and `ui.Home*`, then reverted.

| # | Mutation | Result |
|---|---|---|
| m1 | top fade always on (`fadeStrength(true, FADE_TOP_DP)`) | caught (source check) |
| m2 | `isPaddingOffsetRequired()` = false (the bottom padding fade off) | caught (source check) |
| m3 | `tile_label` `maxLines="1"` | caught |
| m4 | `contactItemPx` with `ceil` instead of `floor` | caught (the sweep) |
| m5 | the 64 dp room check in `contactRow` dropped | **survived** (N1: only bites below 72 dp) |
| m6 | `FadingRecyclerView` consumes every touch (`init { setOnTouchListener { _, _ -> true } }`) | **survived** (L3) |
| m7 | `maxWidth = itemPx + 40` | **survived** (L3: substring match) |
| m8 | `fadeStrength` ignores `canScroll` | caught |
| m9 | the contacts row always `Gravity.START` (not centred when it fits) | **survived** (existing code, not P4; harmless for three contacts that fill the row, it moves one or two contacts to the left) |
| m10 | `getBottomPaddingOffset()` = 0 | caught (source check) |
