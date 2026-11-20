# Code QA: Home's contacts with a name pill (branch `home-name-pill`, 280f6e23a)

2026-11-20. This review covers the diff `3c584ee72...origin/home-name-pill`: `WallpaperHint.kt`, `NamePill.kt`, `KidAvatars`
(`bindHomeRing`, `bindNamePill`, `sizeContact`), `PressFeedback.contact`, `item_kid_contact.xml`, `HomeActivity`
and the tests. I read the code only. No Gradle or emulator run.

No H findings. There are three M findings and seven L findings.

## M

**M1 - The hint reads Android's wallpaper, but Home draws its own copy.** `WallpaperHint.kt:51-58`,
`HomeActivity.kt:1015`.
Home always draws Vibb's wallpaper itself (`WallpaperGround.apply(..., ownGround = true)`: `GroundKind.FULL` from
`WallpaperStore.state.current`, with the ink's scrim). Android's `FLAG_SYSTEM` wallpaper is the same picture only when
`WallpaperStore.state.systemShowsOurs` is true. `getWallpaperColors(FLAG_SYSTEM)` describes a different picture in
these cases:
- a new phone before its first policy, and an unmanaged phone (`wallpaperApplyPlan` skips the apply). Home shows navy
  and Android shows the stock wallpaper.
- a refused or failed `setBitmap`, which `WallpaperApplier.applyIfNeeded` does not retry that day.
- a wallpaper dim amount (`setWallpaperDimAmount`, Pixel's "Dark theme dims wallpaper"). Android 13+ computes the
  colours on the dimmed bitmap. Home draws the undimmed copy.

Failure scenario: a new phone has a light stock wallpaper and Vibb's navy ground. Every contact gets the dark pill and
the #2A2630 ring on navy. The ring disappears around a dark photo, and the pill sits as a low-key dark lozenge. This is
the opposite of the approved rule. It lasts until the apply succeeds, which can be a day after a refusal.

When Vibb itself changes the wallpaper, two renders also happen. `WallpaperStore` notifies first, so the new ground
draws with the old pill colours. Android's colours callback re-renders a little later. This can show for a frame or
more.

Fix, preferred: run Android's own reading on the pixels Home draws. In `WallpaperStore.refreshNow`, on the store's
thread, call `WallpaperColors.fromBitmap(state.bitmap)` for a photo. For a solid or gradient fill, call it on a small
render of the fill. Cache the result by hash, and keep `supportsDarkText` in `State`. Then `renderWallpaper` re-renders
the call parts when the hint changes, as it does for the ink. This is still "Android bestemt farge" (the same
algorithm and the same API), and it removes the listener, the binder read, the race in L2 and the cold-start flip in L1.

Fix, minimal: use the read hint only while `WallpaperStore.state.systemShowsOurs`. Otherwise use the white pill (or
`state.ink.darkInk`), and re-render when `systemShowsOurs` flips.

**M2 - A name on two lines gets a stadium shape, and its end letters stick out past the curve.** `KidAvatars.kt:183`
(`cornerRadius = dp(999)`).
`GradientDrawable` clamps the radius to half the shorter side. A two-line pill (maxLines 2) therefore gets semicircle
ends with radius h/2. `WholeWordTextView` shrinks a line to fill the 80 dp line, and the text keeps only 8 dp padding,
so the first and last glyphs of a full-width line reach into the curved ends.

Rough numbers with Nunito's metrics, for a two-word name such as "Onkel Per" or "Bestemor Kari" on two lines:
- font 1.0: inside the pill.
- font 1.5: about 1 dp out.
- font 2.0: about 4-5 dp out at the cap tops of line 1 and the descenders of line 2.

Those letter corners sit on the wallpaper. A white name on a light wallpaper loses them, so the letters look cut.
The project supports font 2.0 explicitly (`WholeWordsTest`).

Fix: make the radius half of a one-line pill's height (`lineHeight / 2 + paddingTop`), set in `bindNamePill` from the
view's `lineHeight`. One line stays fully rounded, and two lines become a rounded rectangle. Put that rule in a pure
function with a test at 1.0 and 2.0.

**M3 - The approved spec differs from what was built, and that decision belongs to the user.** `PLAN.md:38` on
`main`, `docs/notes/launcher/ui-polish.md:11`, `HomeActivity.renderGrid` (`shownInk`).
The approved entry says "app labels follow the same hint". The build keeps `InkChoice` for the labels on purpose. The
reason is sound: InkChoice guarantees 4.5:1 on the pixels and the scrim, and the strict hint could break that. But the
reason is recorded only in the area note.

The pill's padding is also 8 dp, not the mockup's 12 dp. The reason is that "Mamma" at 2.0 would be ellipsised on the
Jelly Star.

Failure scenario: the item is marked built as approved. On a medium-light wallpaper the user then sees dark app labels
next to white name pills (the hint is false, the ink is dark). Or the user expects the labels to change with the hint,
and they don't.

Fix: put both deviations to the user before merging, in a line or two each with the reason (the memory rule "visible
choices first"). Then record the answer in the PLAN entry. If the user still wants labels to follow the hint, the M1
preferred fix gives a hint on Home's own pixels that the label code could use, with the scrim kept for 4.5:1.

## L

**L1 - Cold start: a white pill, then a dark one.** `WallpaperHint.kt:28` (`null` at process start), `:51-58`.
In a new process the first `render()` in `onResume` draws with `supportsDarkText == null`, so the pills are white. The
read is posted with `main.post`, and the traversal's sync barrier runs ahead of it. On a light wallpaper the first frame
therefore shows white pills and the next frame dark ones.

When it shows: after a self-update, a crash or an out-of-memory kill. At boot Home is behind the PIN lock. This is the
kind of blink the user does not want.

Fix: M1's preferred fix removes it. Otherwise keep the last hint in `WallpaperStore.PREFS` and seed from it.

**L2 - A slow read can overwrite a newer change.** `WallpaperHint.kt:51-58`, `:74-78`.
`start` reads in the background, while the listener is already registered. Suppose an `onColorsChanged` with the new
colours is posted before the read's `main.post` with the old colours. Then `take(old)` runs last and wins. The wrong
pill stays until the next resume, because `render()` in `onResume` runs the call parts again.

This is rare: the read thread has to be descheduled after the binder returns. Nothing tests the follower's ordering.

Fix: a sequence number. Every callback increments it, and a read result is applied only if no callback came after the
read started. Put that rule in a small pure class with a test. M1's preferred fix makes this unnecessary.

**L3 - The teal unread badge can cover the tops of the name's last letters.** `item_kid_contact.xml:104` (the badge,
`bottom|end` of the frame, whose bottom is the avatar's bottom) against `:73-86` (the pill's text box starts
10 - 3 = 7 dp above the avatar's bottom).

The positions, relative to the cell's centre: the badge covers about +18 to +42 dp across, more with a two-digit count,
and the 7 dp above the avatar's bottom edge. At font 1.0 the cap and ascender tops of a name wider than about 36 dp lie
in that band, for example the "ff" in "Kristoffer".

The note accepts that the badge "overlaps a wide pill", but it does not say whether it covers letters.

Fix: check it on jelly36 with an unread count of 1 and 12 and a long name. If letters are covered, lift the unread
badge by the overlap, or pull the pill's text below the badge.

**L4 - The focus ring is drawn across the pill.** `PressFeedback.kt:212`.
The cell's `state_focused` foreground is an oval ring 4 dp outside the avatar, and it draws above every child. At the
bottom it crosses the pill and the name. This happens only with D-pad or keyboard focus.

Fix: accept it, or draw the focus ring under the pill (on the avatar, not the cell).

**L5 - The pill's shadow may be cut at the row's bottom.** `item_kid_contact.xml:22` (`paddingBottom 4dp`).
The pill now ends 4 dp above the item's bottom. The `HorizontalScrollView` clips there, and a 4 dp elevation casts its
spot shadow downward. The result can be a hard edge under each pill, most visible under the dark pill on a light
wallpaper.

Fix: check a jelly36 screenshot. If the edge shows, use `paddingBottom` 8 dp, or make the scroll view's content not
clip at the bottom.

**L6 - PLAN.md is not touched on the branch.** The branch's parent is `aa4c470ed`, so it does not include `773e61ee1`
("in build").
Fix: the merge commit marks the entry as built, awaiting QA, with M3's answer. Per CLAUDE.md, a gate on the emulator
(Android 14 and 17, screenshots on jelly36 with a light, a dark and a busy wallpaper, and font 2.0) comes before
calling it done.

**L7 - The new tests are mostly source greps.** `ContactBadgeLayoutTest` matches strings in `HomeActivity`,
`KidAvatars` and `PressFeedback`, as before. `NamePillTest` pins the colours, the bit and the contrast. No test
pins the hint follower's behaviour (L2) or the pill's corner rule (M2). Fix: as in L2 and M2.

## Checked and fine

- **Registration.**
  - `registered` guards against double registration. `startWork`'s `started` guard and `singleTask` mean there is one
    owner.
  - `stop` runs in every `onStop`, including the dark-start path, where it does nothing.
  - The listener is anonymous and does not hold the Activity. `onChange`, which does, is cleared in `stop`.
  - After removal, `WallpaperManager.Globals` does not deliver to a listener that was removed.
- **Main thread.** `getWallpaperColors` runs only on the executor. On the main thread there is only the one-time
  register and unregister binder call, which is cheap.
- **A read that lands after `onStop`.** It updates the value without rendering, and the next `onResume` runs
  `render()`, so nothing is left stale.
- **Lock screen and colours.**
  - `FLAG_LOCK`-only changes (the glance face, photos on Home only) are filtered out by `which and FLAG_SYSTEM`.
  - When the wallpaper is shared, the service adds `FLAG_LOCK` to a system change, which still passes the filter.
  - `null` colours and live wallpapers give the white pill. The three-argument `WallpaperColors` constructor never
    sets `HINT_SUPPORTS_DARK_TEXT`.
- **Accessibility.** The item keeps its `contentDescription`. `contact_name` is neither focusable nor clickable, so
  TalkBack reads the name once, as before; the new nesting changes nothing.
- **Contrast.** The name on its pill is about 14.8:1 (white on #2A2630) and about 16.9:1 (#1B1B28 on white).
- **Shape.** `GradientDrawable` clamps the radius for drawing and for its outline, so the one-line shadow is a
  correct stadium.
- **Press and ripple.** The avatar is still 10 dp below the item's top (`CONTACT_AVATAR_TOP_DP`), so the ripple mask
  and the press geometry are unchanged. The press does not change Z, so the pill stays above the avatar while it is
  pressed.
- **Draw order.** Equal Z draws in the order the children are added (the ordering is stable), so the order is avatar,
  pill, frame. The frame has no outline, so it casts no shadow, and it is not clickable, so touches reach the item.
- **RTL.** Padding, margins and the ring inset are symmetric, and the badges use `end`.
- **Android versions.** The APIs are 27 (`getWallpaperColors`, the listener) and 31 (`getColorHints`), and none
  needs a permission. In the test, `HINT_SUPPORTS_DARK_TEXT` is a constant inlined at compile time, so it is safe
  against the `android.jar` stubs.
- **Look.** A dark or white pill with a faint outline and a soft shadow (65 % or 40 % of the theme's spot alpha) is
  calm. `KidInk`'s text shadow is cleared on the name.
