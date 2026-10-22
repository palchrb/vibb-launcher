# Design QA: launcher round 9 - the glance page and touch sounds (`docs/design/r9-launcher-glance-small-items.md`)

Checked against `main` (e3bbb593): `lock/GlancePanel.kt`, `lock/LockGlance.kt`, `music/MusicLockPanel.kt`,
`ui/KidInsets.kt`, `lock/PinLockActivity.kt`, `res/layout/view_lock_glance.xml`, `LockGlanceTest.kt` (it holds
`LockGlanceLayoutTest`), `KidSettingsActivity.kt`/`KidSettingsModel.kt`, `server/QuickControls.kt`, the server's
`handlers/devices.rs`, `device_pages.rs`, `templates/device_home.html`, `tests/device_api.rs`/`device_pages.rs`, the
bundled Nunito files (cmap/GSUB/GPOS/hmtx read directly), PLAN.md ~728-774, design 59 §4.2 and §8.

**Verdict: sound, and inside the user's scope.** The root-cause analysis is right. `GlancePanel.kt:172-173` refits only
when `bottom - top` changes. `fitNow` (`:640`) subtracts the paddings. `KidInsets.apply` (`KidInsets.kt:24-27`) puts the
visible bars in as padding, and `setPadding` triggers a layout at the same bounds. So a change in the insets alone never
refits. The fix covers every path. A change in the insets (screen-on, a switch in navigation mode, the bouncer later
under design 59) runs the listener with a new available height. A font-scale change goes `refit()` -> `fitNow`. The
clock and timer tiles go `tileChanged` -> `fitNow`. A change in the rows goes `renderNow` -> `fitNow`. The lock is
portrait-only (`AndroidManifest.xml:163`). Insets that arrive while the page is GONE are read by the next `renderNow`.
With §2, a wrong fit can no longer push the bottom block off the screen. `lock_glance_top` is `0dp`/weight 1 and comes
before two non-weighted children, so LinearLayout gives it only what is left (at least 0), and it clips its own children.
Only "Lås opp" plus the bottom row taller than the window could overflow. That is beyond what font scale 2.0 does.

Every reference I checked holds: `fitFor`/`fitNow`/the listener, `resumeNow`'s lock-up branch, the 116/233.8/90 pins,
the 1.4 model, the 3.2 dp of slack, `bg_music_art_large` having no other user, mask bits 1/2/4/8 on both sides, the
15 in `tests/device_api.rs:529-533`, the `data-autosave` form with its `#quick-controls` return
(`device_home.html:85`), and `Settings.System.canWrite` as the app-op check (`ClockFormatSetting.kt:124`). I worked
the §2 table out again from the pinned model: 410.8 / 466 / 458.4 (2) and 480.8 (1) / 458.4 (3) / 3. All correct.
The lock has no swipes or gesture detectors of its own (`lock/` has no `GestureDetector` and no touch listener), so
the ScrollView competes only with taps. Its own slop rule keeps a drag from being a row tap, and `onUserInteraction`
(the notice wake) still sees every DOWN.

Counts: 0 High, 1 Medium, 5 Low, 7 Nit.

## Findings

**R9-1 (Medium) - `loadSoundEffects()` blocks its caller, and the note's setter would run on the main thread.**
AudioService's `loadSoundEffects()` waits for SoundPool to load the samples before it returns. That is a synchronous
binder call that can take seconds. Android's own `TouchSoundPreferenceController` posts it to a background thread for
this reason. Every Kid Settings setter today runs on main (`KidSettingsActivity.kt:103-139`), so an implementer
following §4 would block the tap. *Fix in the note*: `setTouchSounds`/`setTouchVibration` are `suspend` and run on
`Dispatchers.IO` from `lifecycleScope`, as the Bluetooth page's calls do. The re-read after the tap comes after them.
TalkBack's disabled state isn't affected. (Less code, if preferred: drop `load`/`unloadSoundEffects`. AudioService
loads the effects at boot, and `SOUND_EFFECTS_ENABLED` alone gates `playSoundEffect`. But matching Android's
Settings is defensible, so keep them, off main.)

**R9-2 (Low) - "Vibrate on touch" on can show on while Android keeps haptics off.** Since Android 13, Android's own
Touch feedback toggle writes `HAPTIC_FEEDBACK_ENABLED` and the hidden `HAPTIC_FEEDBACK_INTENSITY` together. A phone
whose Touch feedback was turned off in Android Settings before enrolment has intensity 0. Our `putInt(ENABLED, 1)`
doesn't bring haptics back. The intensity key isn't one an app may write: a non-public System key throws
`IllegalArgumentException` for targetSdk >= 23. §4's carry-over covers only the off direction. *Fix*: add "Touch
feedback off in Android's Settings before enrolment, then ours on" to the device check and to the carry-over as an
accepted residual (setup runbook: leave Touch feedback on). No code.

**R9-3 (Low) - The default mask makes the row read-only on most new phones, and the parent can't tell.** §4 adds 16
to `DEFAULT_QUICK_CONTROLS`. But the `WRITE_SETTINGS` app-op is granted only with adb or by the parent during a pause
(design 34 P12), so a new phone shows "Kan ikke endres på denne telefonen ennå". §6 drops a writability status
because "`clock_format_applied` warns already". It doesn't: that field is left out (`null`) for "Follow the phone",
the default (launcher CLAUDE.md, "One clock format"). *Fix (recommended, less code)*: keep 16 out of
`DEFAULT_QUICK_CONTROLS`. The parent ticks it after granting the app-op, the ⓘ line says so, and `device_api.rs`
stays at 15. Otherwise ask the user. Either way, correct §6's justification.

**R9-4 (Low) - The 24 dp fade cuts into the one row counted as visible, and candidate 7 can be just a faded strip.**
In the mockup's case (music + 2 rows at 473 dp), candidate 3 leaves the scroll 457 - 386.4 = 70.6 dp. The fade has
full strength, because the content (120 dp) is far past the viewport. It covers 46.6-70.6 dp, so the last ~10 dp of
the one row the fit counted are dimmed. Candidate 7 ("no row counted") at font scale 2.0 can leave less than 24 dp,
which shows as a sliver that is faded out completely. *Fix*: `fadingEdgeLength` 16 dp (the contacts row's
`CONTACTS_FADE_DP`). State in §2 how candidate 7 looks (a strip, or `GONE` below one row's height plus the more line
- pick one). Pin the scroll's height at 473 dp (70.6) in the fits table.

**R9-5 (Low) - The refit log line doesn't prove the fix, and check 1 expects it to.** `GlancePanel.kt:666` logs only
when `next != fit`. If a refit on new paddings keeps the same candidate, it logs nothing. §5 check 1 expects "a refit
with the bars' padding after the screen-on". *Fix*: log when the available height changes as well as when the fit
changes (one condition), or reword check 1 to read the `available … (pad …)` part of the line that comes with the
candidate change.

**R9-6 (Low) - §2's spec leaves two points open.** (a) In a candidate that counts one row (3, 5, 6), does the "more"
line count? It sits in the scroll after row 3, so it shouldn't. Say so. Say also that `GlanceFit.rows` is now always
`min(n, 3)`, so `apply`'s `i < fit.rows` (`GlancePanel.kt:692`) can stay or go. (b) "The timer now also outlives the
date" changes design 42 §1.6, which kept the date in every timer candidate. List it as a change and pin it in the
rewritten timer test.

**R9-7 (Nit) - Numbers in §1.** In the pinned model the full tile with one row needs 501.4 dp with the small clock
and 523.8 dp with the big one (116 + 56/78.4 + 25.6 + 233.8 + 14 + 56), not ~499/~521. The overflow is ~43 dp. The
conclusion doesn't change.

**R9-8 (Nit) - The fixed time widths.** The bundled Nunito has no `tnum` feature (GSUB: aalt, calt, case, ccmp,
dnom, frac, liga, locl, numr, onum, ordn, salt, sinf, ss01, ss02, subs, sups). Its default figures are already
tabular (every digit is 600/1000), and U+2212 is in the font at 600. So the claim holds because of the font, and
`tnum` is a harmless no-op. GPOS does have `kern`. So take the width from `Layout.getDesiredWidth(widest, paint)`,
add 1 dp, and give the two times no `ellipsize`: a pixel short must never show "1:2…". Without a duration,
"0:00:00" clips a live position past 10 h. That is accepted, but say so.

**R9-9 (Nit) - `glanceAvailablePx` as a pure helper with its own assertion is machinery for a subtraction.** A
private `available()` in `GlancePanel` that the listener and `fitNow` both call, pinned by the source test, is less
code. Drop "`glanceAvailablePx` itself is one assertion".

**R9-10 (Nit) - Test details.** `LockGlanceLayoutTest.height()` reads `0dp` as an explicit 0
(`LockGlanceTest.kt:440`), and nothing needs the height of the two new weighted views (the parts are read by
id). So drop "`height()` learns `ScrollView`" and pin the `0dp` + weight 1 of `lock_glance_top`/`lock_glance_scroll`
in the order test instead. Server: the "new phone has all on" loop in `tests/device_pages.rs:944-950` and the
template's "One form for the four" comment need the fifth name. `KidSettingsActivity.renderControls`'s `anyRow`
(`:214`) needs `touch` as well as the model.

**R9-11 (Nit) - `bg_glance_glyph`.** `?android:attr/selectableItemBackgroundBorderless` is already an oval ripple
with no fill. It replaces the new drawable.

**R9-12 (Nit) - The mockup lives only in a scratchpad.** Commit it as `docs/design/mockups/r9-glance.html` with the
note, or drop the reference.

**R9-13 (Nit) - Design 59, for its list rather than this round.** The one tie between the glance and our PIN screen
that r9 keeps is Nødsamtale. It clicks the keypad's button (`PinLockActivity.kt:287`,
`binding.pinEmergency.performClick()`). Under the hybrid it calls `EmergencyCall.confirm` directly. A row tap and
"Lås opp" go to the bouncer, as the note says. Everything else in r9 carries over: the scroll, the fixed block, the
inset refit (which also covers the bouncer appearing and going away), the tile, and touch sounds (never on the lock).

## The open questions (recommendations, not decisions)

1. **The order when music and rows compete: as proposed.** It is the iPhone's behaviour, which the user named: the
   full player stays and the notifications scroll. Every row stays reachable by scrolling. With R9-4's 16 dp fade the
   visible row is clear.
2. **The one-row compact tile: keep it, unchanged.** At font scale 2.0 on 320x569 dp with both bars, the clock, date
   and fixed block take ~273 dp of the 457. The new tile is ~222 dp there (a 78 dp header against the 48 dp cover,
   and 34 dp times). That leaves 184 dp, so it doesn't fit even with no rows, and the top block would clip its
   controls. The compact tile (~125 dp) fits.
3. **"At most three + '+ N varsler til'": keep it this round.** Listing every conversation needs recycled row views
   instead of `rowViews`. That is a separate design, so it goes under "not in scope".

## Not in scope (proposals)

- When a newer conversation arrives while the kid has scrolled the rows (screen on, no lock-up), it lands above the
  viewport. A `scrollTo(0, 0)` when `bindRows` gains a conversation would show it.
- A status field `touch_writable` (see R9-3), only if the user wants the bit on by default.
