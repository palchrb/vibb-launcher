# Code QA: launcher round 9 (glance fit, fixed bottom block, compact player, touch sounds)

Branch `r9-glance` at bbc7d7b0 (code 7ddc80a6, base ef65e97f), checked against `docs/design/r9-launcher-glance-small-items.md`,
`qa-r9-design.md` and `qa-r9-revision-check.md`. One full run of each: launcher `assembleDebug testDebugUnitTest
-PwarningsAsErrors=true` - BUILD SUCCESSFUL, 1511 tests, 0 failures; server `cargo test` - 1134 passed, `cargo fmt
--check` clean. Then 15 targeted mutations, each reverted (table below).

**Verdict: mergeable.** No High or Medium findings. Two Low findings (one a test gap the mutations found), three Nits.

## What was checked and holds

- **No refit loop.** The layout listener refits only when `available(bottom - top)` differs from `fitFor`, and `fitNow`
  stores the same `available(root.height)` (the listener runs after `mTop`/`mBottom` are set, so the two agree). `apply`'s
  visibility and margin changes request a layout, but the room stays the same, so nothing follows. A `setPadding` from
  `KidInsets` requests a layout, and `View.layout` calls the listeners on `PFLAG_LAYOUT_REQUIRED` even when the bounds
  are unchanged, so a bar arriving at screen-on does refit. A padding change while the page is `GONE` is caught by the
  same listener on the first layout after the page shows again, and `renderNow`'s refit covers it as well.
- **The log** is written only on a new fit or a new room (`next != fit || room != loggedFor`). `renderNow` refits on every
  render but logs nothing unless one of those changed. `refitPending` (the skipped frame) is still tied to a fit change
  only.
- **The lock-up's `scrollTo(0, 0)`** runs in the `lockStageDue` branch only. ScrollView clamps every scroll, so a call
  before the first layout or with no rows is harmless, and `onLayout` clamps after rows are removed.
- **The top block clips; the bottom block stays.** The root measures `lock_glance_unlock` and `lock_glance_bottom` first,
  and the weighted `lock_glance_top` gets `max(0, rest)`. Inside the top block, the views before the weighted scroll are
  measured `AT_MOST` what is left (`usedHeight = mTotalLength` while `totalWeight == 0`). So at font scale 2.0 on 320x569
  an overflow squashes or clips the last tile, and the scroll gets 0 dp. Nothing is drawn or hit-tested outside the top
  block (`clipChildren` is on by default, and touches go by bounds), so "Lås opp", "Nødsamtale" and the flashlight stay
  whole and tappable. `glanceRefitDue` compares the `UNSPECIFIED` heights on both sides, so a squashed tile never sets off
  a refit.
- **Touch.** ScrollView takes over a drag only past its slop, and only while it can scroll (`!canScrollVertically` ->
  no intercept). The row then gets a CANCEL, so a drag is never a tap. With nothing to scroll, the behaviour is the same as
  before r9. The lock has no gesture detector, and `onUserInteraction` (notice wake) still sees every DOWN.
  `shouldDelayChildPressedState` keeps a row's ripple from flashing at the start of a drag.
- **TalkBack.** The ScrollView is a plain scrollable node, and the rows keep `setAccessibilityDataSensitive`. The root
  stays `importantForAccessibility="no"`. The times are `no`, and the bar keeps "1:26 av 4:04".
- **`fixWidth`.** `Layout.getDesiredWidth` reads `view.paint`. `FontScaleRefresh.rescale` runs before `GlancePanel.refit`
  -> `apply` -> `music.show` -> `render` -> `tick`, so the widths are recomputed from the new size. On the keypad stage
  `fitNow` stops at `pageWidth()`, and the next `renderNow` sets the widths. Layout params change only when the px
  differ, so the 1 s tick does no layout while the shape holds (deviation 1 accepted: two `getDesiredWidth` calls of
  seven characters a second cost nothing). Heights never depend on these widths (`maxLines` 1), so the fit is unaffected.
- **Touch writes.** One process-wide `Mutex` around `putTouch` (+ `loadSoundEffects`). `touchWrites` changes on Main only
  (`lifecycleScope` is `Main.immediate`, and `finally` resumes there). Cancellation in `withContext`/`lock` releases
  through `withLock`'s `finally` and decrements the counter. The re-read after the last write comes from the setting that
  was written (`putInt` comes before the slow load), so a refused write flips back. Every throw the setters can see is
  caught (SecurityException/IAE in `putTouch`, `Exception` around AudioManager and in `touchState`), so the uncaught path
  to the home process's crash handler stays closed. Both keys are in `Settings.System.PUBLIC_SETTINGS`, and
  `VibrationSettings` still applies `HAPTIC_FEEDBACK_ENABLED=0`: the residual stated in §4 is right. Deviations 2 and 5
  are accepted (the log is the redacted state, with no names).
- **Server / compat.** Bit 16 is outside `DEFAULT_QUICK_CONTROLS` (15), and `section=quick` writes the five bits only.
  The row is in the same `data-autosave` form with `#quick-controls` (`device_home.html:105`). Old server + new launcher:
  no bit, no row. New server + old launcher: the bit is ignored, and mask 16 alone is `NoneEnabled` on the old build (no
  card). The policy shape is unchanged.
- **Nothing new is tied to our PIN screen.** The scroll reset hangs on `lockStageDue`/`screenOffs`, and the touch row
  lives on Kid Settings (redirected away under a time rule). The design 59 face carries over.
- **Deviation 3** (one list of seven, `flatMap` twice with a timer): accepted - less code than the old special cases.
  Fourteen `fit()`s per refit is nothing. **Deviation 4**: see R9C-3. The runbook `docs/setup/android-settings.md` is
  right and short.

## Findings

**R9C-1 (Low) - A "one row visible" fit doesn't keep the row clear of the 16 dp fade.**
Evidence: `LockGlance.kt:278` counts a one-visible list as `rowsTop + row`, and the scroll's bottom fade is 16 dp
(`view_lock_glance.xml:421`). `LockGlanceTest.kt:642` checks clearance only at the two pinned rooms (70.6/94.6 dp).
Scenario: candidate 3 wins for any room from 442.4 to 506.4 dp (music + 2-3 rows, model). The row's content clears the
fade only when the scroll is at least 56 + 16 - 8 = 64 dp, i.e. room >= 450.4. So the near-boundary cases - 473 dp at a
slightly larger font, a phone with a few dp less than the Jelly Star - put the only visible row's subtitle and time under
the fade (§5 check 2: "the one counted row clear"). Candidates 5 and 6 have the same gap.
Fix: in `fit()`, add `heights.fade` (the scroll's `verticalFadingEdgeLength` less the row's bottom padding, measured in
`fitNow`) to the list when `visible < rows`. Assert clearance in the "less room" test at the boundary, not only at 473/497.

**R9C-2 (Low) - The touch switch's in-place save is not pinned by any test.**
Evidence: mutation S2 (dropping `data-autosave` from `quick_control_touch`, `device_home.html:105`) survives `sound_mode`,
`home_screen_holds_the_moved_forms` and `device_api`. The loop at `tests/device_pages.rs:944-953` checks the form action
and the checked state only. The same is true for the four older quick controls.
Scenario: a later template edit loses the attribute. The switch then silently stops saving (no `<noscript>` with JS on),
or a rewrite to a plain submit breaks the "never jump to the top" rule unnoticed.
Fix: in that loop add `assert!(super::autosaves(&html, name), "{name}")`, using the helper `tests/mod.rs:597` already has.

**R9C-3 (Nit) - With a morning alarm, the user's own case at 473 dp gets the compact player (deviation 4).**
Evidence: `LockGlanceTest.kt:658`. The full tile with the small clock and the alarm line is 462.6 > 457 dp. With one row,
"one visible" is every row, so candidate 4 (compact) is next.
This is correct for the candidate list, but open question 1's default ("the full player stays") does not hold for a
Jelly Star with 3-button navigation, music, one row and an alarm set - a common school-morning state. Tell the user. If
they want the full player there, a candidate that drops the alarm line (keeping the date) before the compact tile costs
one list entry.

**R9C-4 (Nit) - A tap queued behind a running sound write is lost when Kid Settings is destroyed.**
Evidence: `KidSettingsActivity.kt:336-337`, `QuickControls.kt:319/335`. The second tap's coroutine waits on the Mutex in
`lifecycleScope`, and Back cancels it there.
Scenario: the kid taps sound on, then off, then Back, all within the seconds `loadSoundEffects()` takes. The setting stays
on, and the next visit shows it truthfully (no stale row). Accept. If ever wanted, run the write in a process scope
(`SelfUpdate`-style) and keep only the re-read in `lifecycleScope`.

**R9C-5 (Nit) - Most of the new glue's guards are text pins.**
Seven of the twelve launcher mutations (L1, L2, L4, L5, L8, L10, L12) are caught only by `source.contains("...exact
line...")` in `LockGlanceLayoutTest`/`TouchFeedbackTest`. That matches the repo's style and catches the obvious
regressions, but a harmless rename fails them, and a same-text rewrite elsewhere passes. No change asked for. If
`available()` or `glanceTimeWidest`'s use grows, prefer a pure helper with a table test.

## Mutation table

| # | Mutation (reverted after) | Targeted test | Result |
|---|---|---|---|
| L1 | listener keyed on the bare height (`bottom - top != fitFor`) | LockGlanceLayoutTest (fit keys on available) | caught |
| L2 | `available()` drops the paddings (bars) | LockGlanceLayoutTest (same) | caught |
| L3 | fit accepts a candidate 48 dp over the room | LockGlanceTest (order, less room, timer) | caught |
| L4 | scroll `GONE` for candidate 7 (`fit.rows == 0`) | LockGlanceLayoutTest (order) | caught |
| L5 | widths set on every tick (`if (true)`) | LockGlanceLayoutTest (progress row) | caught |
| L6 | `setTouchVibration` without the Mutex | TouchFeedbackTest | caught |
| L7 | "more" line counted in one-visible candidates | LockGlanceTest (candidates in order) | caught |
| L8 | no `scrollTo(0, 0)` at the lock-up | LockGlanceLayoutTest (fit keys on available) | caught |
| L9 | `renderTouch` re-reads while writes run | TouchFeedbackTest | caught |
| L10 | fit log on every render | LockGlanceLayoutTest (fit keys on available) | caught |
| L11 | timer candidates last | LockGlanceTest (running timer) | caught |
| L12 | elapsed width ignores the position's shape (N6) | LockGlanceLayoutTest (progress row) | caught |
| S1 | bit 16 in `DEFAULT_QUICK_CONTROLS` | sound_mode, device_api | caught |
| S2 | touch row without `data-autosave` | sound_mode, device_pages, device_api | **survived** (R9C-2) |
| S3 | Home Screen renders the touch switch from the sound bit | device_pages, sound_mode | caught |

14 of 15 caught. (L1, L2, L4, L5, L8, L10 and L12 survived a first run filtered to `LockGlanceTest` alone - the layout
tests are the `LockGlanceLayoutTest` class in the same file - and were caught when rerun with `LockGlance*`.)

## Not checked here

Nothing ran on a device: the note's §5 checks 1-4 (the screen-on refit with 3-button navigation, font scale 2.0 at 320
dp, TalkBack through the scroll, the touch row with and without the app-op) remain the user's emulator pass.
