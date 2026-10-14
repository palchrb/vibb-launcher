# QA 34 step 2 (code) - quiet depth, press feedback, the badge colour

Reviewer: code QA, 2026-10-14. Branch `design-34-s2`, `59c7fbef..d17069e7` (one commit, launcher and docs).
I read it against `docs/design/34-launcher-polish.md`: P2, §2, §5, §7 (QA M2 and L5), and the new "Step 2
implementation status". I also read `qa-34-design.md` (M2, L5), the `01-home-*` mockups, `launcher/CLAUDE.md` and
the root `CLAUDE.md`.

In the code I read:
- the whole diff;
- `HomeActivity`'s gesture wiring: the grid's `OnItemTouchListener`, the contacts row's observing listener,
  `onFling` and `renderContacts`;
- `HomeGridAdapter` in full, `PhoneBookAdapter`, `KidAvatars` (`sizeContact`, `bindBadge`, the icon renders,
  `circular`);
- `item_kid_contact.xml`, `item_kid_tile.xml`, `bg_kid_circle.xml` and the `kid_badge` users.

Line numbers are `d17069e7`'s. No product code was changed. The mutations below were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge after a short fix round.** The hard rule holds: the listener never consumes. `onAction` returns
false for every action, and the listener returns exactly that. Both are pinned, and the tests caught both mutations.
Nothing new sits in the gesture path:
- the grid's `OnItemTouchListener` and the contacts row's listener only observe;
- a scroll in a parent still reaches the cell as a CANCEL;
- the click, long-press and fling outcomes are untouched.

Other checks that passed:
- Animations off is read the right way.
- The badge colour is right (4.98:1 against white), and it is the only colour that changed.
- The z-order of the badges is correct.

There are two Medium findings, both about what the press looks like rather than what it does:
- **M1:** the press doesn't let go when the finger leaves the cell. The icon stays shrunk and darkened through a
  swipe-left from the grid, or a sideways drag in the phone book, until the finger lifts. Android's own pressed state
  clears as soon as the finger leaves the cell.
- **M2:** the oval ripple's mask lies entirely under the opaque, elevated icon. Keyboard or D-pad focus is invisible
  on Home and in the phone book; it used to cover the whole cell. The docs say the focus highlight shows. It doesn't.

Counts: 0 High, 2 Medium, 3 Low, 6 Nits.

## What I ran

- `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest -PwarningsAsErrors=true` (from `launcher/`,
  under `~/build.lock`) passed: 122 test classes and 1136 tests, `PressFeedbackTest` 7/7.
- I ran 11 mutations: `PressFeedbackTest` alone for the logic and the colour, and the full unit suite for the two
  wiring mutations. 7 were caught and 4 survived. The table is at the end.

## Medium

### M1. The press doesn't follow the finger out of the cell

`PressFeedback.kt:57-80` (`onAction` looks only at DOWN, UP and CANCEL) and `:224` (the listener passes only
`actionMasked`).

Android's own pressed state ends when the finger moves past the touch slop outside the view:
`View.onTouchEvent`, MOVE, `!pointInView(x, y, touchSlop)` → `setPressed(false)`, and the tap and long-press callbacks
are removed. `PressMotion` ignores MOVE, so the press ends only at UP or CANCEL. A parent sends CANCEL only when it
intercepts, and the grid (a vertical `GridLayoutManager`) never intercepts a horizontal drag. The phone book doesn't
either, and the contacts row (`HorizontalScrollView`) never intercepts a vertical one. So:

- **Swipe-left from a grid icon to kid Settings, or any sideways drag on the grid or in the phone book:** after the
  100 ms tap timeout `pressLater` fires mid-drag. The icon shrinks and darkens, and it stays that way while the finger
  crosses the screen, until it lifts.
- **A drag that leaves the cell within 100 ms and lifts:** UP finds `pending` and plays the whole press and release
  (90 + 140 ms) on an icon that was not clicked.
- **A vertical drag that starts on a Home contact:** the same thing happens.

Outcomes are unchanged: the click is still cancelled by the framework. But a pressed icon that follows a swipe it
didn't start is the kind of motion the user asked to avoid, and the ripple (the framework's pressed state) and the
shade now disagree.

**Fix:** pass "inside" with the action:
```kotlin
cell.setOnTouchListener { v, e -> motion.onAction(e.actionMasked, inside(v, e)) }
```
`inside` is the bounds test with `ViewConfiguration.get(ctx).scaledTouchSlop`. In `onAction`, MOVE with
`inside == false` does what CANCEL does: `cancelPending()` and release, and it sets a `left` flag. The following MOVE
and UP events are then ignored until the next DOWN, so a lift outside doesn't replay the press. Pin it in
`PressFeedbackTest`:
- DOWN, MOVE (outside), timeout, UP → no `animate 1.0` and no post left;
- DOWN, timeout, MOVE (outside) → `animate 0.0 140` once.

The listener stays a one-liner, so the source check still holds.

### M2. The focus highlight (and nearly all of the ripple) is hidden under the icon

`PressFeedback.kt:129-140`, called from `HomeGridAdapter.kt:106` and `PressFeedback.contact` (`:156`). The doc claims
are in `34-launcher-polish.md:377` ("and the focus highlight") and `launcher/CLAUDE.md:372` ("plus focus").

The mask is an oval of exactly `iconPx` at the icon's position. The cell's background draws under the icon, which is
opaque (white `bg_kid_circle` or a full-circle render) and also elevated. The focused state's highlight lies inside
the same mask, so it is entirely covered. Before this step, `selectableItemBackground` lit the whole cell, label
included. Now a keyboard or D-pad user has no focus indicator on the grid, Home's contacts or the phone book.
TalkBack and Switch Access draw their own overlays, so they are not affected. While the icon is scaled to 0.94 the
pressed ripple shows only as a ring of about 1.8 dp, which is acceptable by design. Focus has no scale, so it shows
nothing.

**Fix:** keep the press as designed and give focus its own quiet sign:
- make the cell's foreground a `StateListDrawable`;
- for `state_focused`, use an oval ring of 2 dp in `colorControlHighlight` (or `kid_ink` at about 40 %), sized
  `iconPx + 8 dp` and placed like the mask;
- otherwise use nothing.

The ring is static and shows only with a keyboard or D-pad. Then correct the two doc sentences.

## Low

### L1. The ripple is rebuilt on every bind

`HomeGridAdapter.kt:106` (`applyMetrics` → `ovalRipple`) and `:130` (`onBindViewHolder` calls `applyMetrics`).
`submit` (`:58`) calls `notifyDataSetChanged()`, so every grid refresh (a badge, `onResume`, a policy sync) rebinds
every tile. Each tile then allocates:
- a `TypedValue`;
- a `ShapeDrawable`, a `LayerDrawable` and a `RippleDrawable`;
- a theme lookup.

It also replaces the background. A ripple or focus state that is showing when a refresh lands is dropped mid-press.
That is small, but on a Jelly Star it is avoidable work in the bind path.

**Fix:** remember the last `iconPx` in the `ViewHolder`, and build the ripple in `applyMetrics` only when it changes
(the column count 3/4 or a new width). Alternatively, build it once in `init` and update only `setLayerSize` /
`setLayerInsetTop`.

### L2. Nothing resets a recycled or rebound cell

`HomeGridAdapter` has no `onViewRecycled`, and the `PressMotion` instance is only in `attach`'s closure. The press
state therefore lives on the view and relies on every gesture ending in UP or CANCEL. Today I found no path that
loses both:
- a parent intercept, `removeView` and `removeAllViews` each send a CANCEL;
- the change animation's old holder is removed through `removeViewAt`, which also sends one.

But `View.dispatchTouchEvent` skips the touch listener for a disabled view, and the `pressLater` runnable is
queued on the view itself. If either ever misses the end of a gesture, the 0.94 scale and the shade move to another
app's tile through the pool. The brief's "no reset on rebind" mutation has nothing to mutate.

**Fix:** keep the motion in a view tag (`R.id.press_motion`) and add `PressFeedback.reset(cell)`, which calls
`cancelPending()` and `jump(0f)`. Call it from `HomeGridAdapter.onViewRecycled` and `PhoneBookAdapter.onViewRecycled`.
Add a `PressMotion.reset()` test: after DOWN and the timeout, `reset()` gives `jump 0.0` and leaves nothing posted.

### L3. The wiring and the z-order aren't pinned

Four mutations survived the full unit suite (see the table):
- the view host ignoring `areAnimatorsEnabled()`;
- the view host dropping the tap timeout;
- `KidBadge` losing its `elevation`/`outlineProvider`, which would draw the badge under the icon: exactly the
  regression the step's own docs warn about;
- `HomeGridAdapter` no longer calling `attach`.

The pure `PressMotion` is well tested. The thin parts around it, which decide whether any of it runs, are not.

**Fix:** add one cheap resource and source test next to the badge test:
- `styles.xml`'s `KidBadge` has `android:elevation` equal to `PressMotion.ELEVATION_DP` and `outlineProvider` `none`;
- `item_kid_tile.xml` has no `selectableItemBackground`, and `tile_frame` has `clipChildren="false"`;
- `HomeGridAdapter.kt` contains `PressFeedback.attach(` with `scrolling = true`, and `HomeActivity.kt` and
  `PhoneBookAdapter.kt` each contain `PressFeedback.contact(`.

Put the two host lines in a named function on the companion, as a pure
`tapDelay(scrolling, tapTimeout) = if (scrolling) tapTimeout else 0`, and test that. `animatorsEnabled` can stay
untested: it is one direct call.

## Nits

- **N1** `animate` builds a no-op `ValueAnimator` when it is already where it is going (`PressFeedback.kt:196-213`).
  Every grid scroll sends the cell a CANCEL, which animates 0 → 0, and so does an UP or CANCEL without a press.
  Return early when `animator == null && fraction == to`.
- **N2** A new DOWN during a quick tap's 90 ms press cancels it (`then` is skipped). The icon then holds partly
  pressed for up to the 100 ms tap timeout before it continues. This is barely visible. Releasing to 0 on DOWN when
  `fraction > 0` and nothing is pending would avoid it.
- **N3** The new `setOnTouchListener` (`PressFeedback.kt:224`) is the shape lint's `ClickableViewAccessibility` flags.
  CI runs lint with `abortOnError = false`, so it is only a warning, and I did not run lint. The click does still go
  through `onTouchEvent` → `performClick`. A `@SuppressLint("ClickableViewAccessibility")` on `attach`, with that
  reason, keeps the report clean.
- **N4** The source check's regex (`PressFeedbackTest.kt:75`, `setOnTouchListener\s*\{[^}]*}`) ends at the first `}`.
  A listener body that ever gets a brace fails the test, which is the safe direction. A one-line comment at
  `PressFeedback.kt:224` ("pinned by PressFeedbackTest; keep it one expression") saves the next person a puzzle.
- **N5** `kid_badge` also colours `bg_kid_badge_sheet.xml` (the contact sheet's badge, on white), so that one changes
  too. That is right (one badge colour, 4.98:1 against white), but neither doc mentions it.
- **N6** An adaptive icon without a monochrome layer is drawn from its own background layer
  (`KidAvatars.kt:187-193`). If that layer is translucent, the oval shadow shows through as a grey disc. This is rare;
  look for it in the emulator pass.

## Mutations

| # | Mutation | Tests run | Result |
|---|---|---|---|
| 1 | Listener returns `onAction(...) \|\| action == DOWN` | PressFeedbackTest | caught (source check) |
| 2 | `onAction` returns `action == ACTION_DOWN` | PressFeedbackTest | caught (no action is ever consumed) |
| 3 | No release on CANCEL | PressFeedbackTest | caught (3 tests) |
| 4 | CANCEL doesn't cancel the pending press | PressFeedbackTest | caught (a scroll's CANCEL never presses) |
| 5 | `moveTo` animates with animators off | PressFeedbackTest | caught |
| 6 | View host: `animatorsEnabled = true` | PressFeedbackTest | **survived** (L3) |
| 7 | Pure tap delay ignored | PressFeedbackTest | caught (2 tests) |
| 8 | View host: `tapDelayMs = 0` | PressFeedbackTest | **survived** (L3) |
| 9 | `kid_badge` back to `#D9480F` | PressFeedbackTest | caught |
| 10 | `KidBadge` without `elevation`/`outlineProvider` | full unit suite | **survived** (L3) |
| 11 | `HomeGridAdapter` without `PressFeedback.attach` | full unit suite | **survived** (L3) |
| - | No reset on rebind | - | not applicable: there is no reset (L2) |

## Checked and fine

- **Never consumes:**
  - `onAction` has a single `return false`, and the listener returns it.
  - The grid's `OnItemTouchListener` and the contacts row's listener keep observing.
  - `onFling` fires on UP, after the cell has its UP.
  - Long-press runs on the framework's own timer.
  - TalkBack and Switch Access click through accessibility actions, with no touch events.
  - A second finger reaches another cell as a split ACTION_DOWN. A POINTER_DOWN inside the cell is ignored.
- **CANCEL without DOWN, UP without DOWN:** both release from wherever the icon is (normally at rest). This is
  harmless (N1).
- **Recycling mid-release:** the animator keeps running on the detached view and ends at 1.0 scale and 0 alpha.
- **Configuration change:** the activity is recreated, so there are new views and new state.
- **Tap timeout:** RecyclerView and `HorizontalScrollView` both delay their children's pressed state (the
  `ViewGroup` default and `HorizontalScrollView`'s `shouldDelayChildPressedState`). The 100 ms wait matches the
  framework's.
- **Animations off:** `ValueAnimator.areAnimatorsEnabled()` (API 26; minSdk is 34) is false when the animator duration
  scale is 0. That is what "Remove animations" sets, and battery saver too. Animators turned off mid-press end at
  once. With animations off the state jumps, and a quick tap ends at rest without a scale.
- **Elevation and z-order:**
  - The icon or avatar and the badges share Z 2 dp, so they draw in child order: the badge comes after the icon in
    `item_kid_tile.xml`, and the call badge and missed badge after the avatar in `item_kid_contact.xml`.
  - Neither badge casts a shadow (`outlineProvider none`/`null`).
  - The shadow falls inside the cell (into the label margin), and `tile_frame` and `contact_frame` don't clip it.
  - Nothing is allocated per frame. The press makes one `ValueAnimator` per DOWN/UP/CANCEL.
- **Mask placement:**
  - Grid: the frame is `iconPx + 4 dp` and the icon sits `bottom` in it → 4 dp.
  - Home contacts: `paddingTop` 4 dp + frame `avatar + 6` → 10 dp.
  - Phone book: 4 dp + (70 − 64) → 10 dp.
  - The mask is centred horizontally, as the frames are, and it stays centred under `GridGapDecoration`'s offsets
    (they sit outside the view's bounds).
  - It is anchored to the top, so font scale and 3 or 4 columns don't move it: the label is below.
- **The shade:** a `View` foreground covers the view's full bounds by default (`foregroundInsidePadding`), so the
  glyph tiles' padding doesn't shrink it. Alpha 0 at rest, so no darkening is left behind.
- **Colours:** `#B4532C` against white is 4.98:1 (it was 4.30:1). No other colour resource changed.
- **Licensing:** the new code is in `ui/home/` (GPL), not in `ui/list/` or `apps/`.
- **Choosing plain JUnit over Robolectric:** this is a reasonable deviation from P2's "Robolectric test" and is
  documented. The source check is a fair stand-in for building a `MotionEvent` and is robust enough (N4).
