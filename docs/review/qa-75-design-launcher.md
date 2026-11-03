# QA 75 (design, launcher side): weather on the lock glance

Note dd19c4f2 (`docs/design/75-lock-weather.md`), against `main` at 6539d573: `lock/GlancePanel.kt`,
`lock/LockClockFace.kt`, `lock/LockGlance.kt`, `view_lock_glance.xml`, `ui/LockActivity.kt`, `lock/FontScaleRefresh.kt`,
`server/MdmSyncWorker.kt`, `push/SyncRunner.kt`/`SyncSchedule.kt`/`BackstopAlarm.kt`, `ui/wallpaper/WallpaperInk.kt`,
`LockGlanceTest`, `docs/notes/launcher/lock-and-glance.md`, the approved mockup v7 (`yr-glance7`), the screenshot
`docs/testing/screens/e7b/e7c-mb1-glance-front.png` and 18 of yr's darkmode SVGs fetched for the mockup. The area is the
launcher and the glance UI; the server, MET, Kartverket and privacy are the other reviewer's (`qa-75-design-server.md`).
Read-only; no build run.

**Verdict: revise first - a small revision.** The shape fits the glance: the phone renders keys, the pull rides the
existing sync and screen-on (no new wake-up), the card is information and not a tile, and the time-rule screen is
left alone. One thing must change: the mark is a ~22 dp tap target on a page whose taps are 48 dp (H1). The five
Mediums are about calm: the mark must not move between lines while the kid looks at it (M1), and a late nowcast must
not push the rows (M2). The others are the pull's limits (M3), Location Off on the phone (M4) and the spoken strings
(M5). **1 High, 5 Medium, 15 Low, 2 over-design notes.**

## Claims checked (all hold)

- `LockClockFace` is shared by `GlancePanel` (`GlancePanel.kt:131`) and the time-rule screen (`LockActivity.kt:102`).
  With `dateRow: View? = null` the time-rule screen's path stays unchanged.
- The date is `lock_glance_date`, a direct child of `lock_glance_top`: 14 sp `kid_core_dim` without a profile, the
  profile's `dateSp` (16 sp on the emulator Pixel) with one. Today the short skeleton `EEEdMMM` and the capitalising
  `FirstCapital` are profile-only (`glanceDateSkeleton`, `profileLook`).
- The rule card (`bg_glance_compact_tile`, 12 dp padding) is the card's pattern. `glanceLayout` doesn't need the
  weather card, as the note says.
- A tap on the locked glance that acts in place and opens nothing is already part of the page: the torch and the music
  controls do it. As Android's face the glance is our own showWhenLocked window, so the tap reaches us. It also counts
  as `onUserInteraction` for the notice wake.
- When the screen goes off, the sleep's pause/stop runs `GlancePanel.stop()`, and the next screen-on is a lock-up
  (`lockStageDue`), so folding the card in both places covers it. `FLAG_SECURE` makes the task snapshot black, so the
  screen-on can't show a stale frame with the card open.
- Font scale: `PinLockActivity` rescales every `TextView` in sp (`FontScaleRefresh.rescale`) and then calls
  `GlancePanel.refit()`, which is the hook the note uses.
- Pull: `performMdmSync` ends with `commitPendingSelfUpdateIfDue` (`MdmSyncWorker.kt:392`), and `MusicSync` sits just
  before it with a catch-all, which is the pattern to copy. The backstop is a 30-minute `setAndAllowWhileIdle`, so
  the "55 min or older" rule gives about one pull an hour, inside Doze's maintenance windows. Nothing new wakes the
  phone. WorkManager would add nothing here; the note is right not to use it.
- r11's scroller: the card is inside `lock_glance_top`, so it scrolls with the page. A swipe up from the card or the
  mark still asks for the unlock (they stay out of `swipeControls`, like a row). With the card open the page can
  scroll, so a swipe there scrolls first, which is r11's rule.
- APK size isn't a concern. The sample averages 4.2 KB per SVG, so 83 files make ~350 KB of text, compressed in the
  APK; the debug APK is 29 MB.

## High

**H1. The mark is a ~22 dp tap target (§5.5 "Touch", §5.3).** The glance's taps are 48 dp: the "more" line,
Nødsamtale (design 34 P7), and the "Neste" line, which is untappable only because "20 dp is under the 48 dp rule"
(`view_lock_glance.xml`). The mark is one text line next to a 22 dp symbol, and the chevron is 14 dp. A kid's tap that
lands on the date or the clock's descent does nothing, and the feature reads as broken. A `minHeight` on the row would
move the alarm line and change the profile's date row height. **Fix:** widen the mark's hit area to at least 48 dp tall
(about 12 dp above and below the row, the mark's full width plus a few dp each side) without changing the layout. One
way is a `TouchDelegate` on `lock_glance_top`, the one view that contains that area. The other is the panel's own
routing: `PinLockActivity` already sends every touch to `GlancePanel.swipe` first. Do the same for the own-line slot.
Pin the hit rect in `LockGlanceLayoutTest`.

## Medium

**M1. The mark moves between lines during the day (§5.3, D14-D15, "Ticks").** The ladder runs again whenever the model
changes, and the minute tick picks a new entry at every hour, at 18:00, and when rain retires. On the Jelly Star
(≈269 dp of room) a day with words is LINE_WORDS (own line) and a day without is BESIDE. So the mark jumps between
lines and every row below moves ~20 dp, sometimes while the kid looks. D14 flips the date the same way: long ⇄ short
whenever the line hides after 3 h and comes back. **Fix:**
- Choose the place only at a lock-up, a refit, a page-width change and the first bind.
- Between those, if a new model no longer fits the place, drop its words (BESIDE/LINE without words); never move the
  mark.
- Make the short date follow the policy switch, not whether the line is showing.

§5.3's budgets also contradict its own ladder:
- At font 1.3 on 360 dp the ladder gives LINE_WORDS (core ≈75 + words ≈163 ≤ 328), not "beside without words".
- At 2.0 on 360 dp it gives BESIDE (date ≈184 + 8 + core ≈80 ≤ 328), not LINE.

§7.3 tests `weatherPlace` at these budgets, so recompute them before the brief.

**M2. A late nowcast pushes the rows (§5.4 "The card's nowcast", §5.5).** `/now` goes through `MdmApi` (connect 10 s
plus read 10 s). On the server, MET can take up to 15 s (`Kind::Weather`), plus the wait from server QA M2. The line
can therefore appear seconds after the card opens and move the periods and every row down a line, under a finger about
to tap a row (and a row's tap leads to the unlock). **Fix:**
- Give the call a ~3 s timeout.
- Insert the line only when the answer arrives within ~1 s of the open; otherwise keep it (2 min) for the next open.
- Drop a reply that lands after a fold, a lock-up or `stop()` (a generation counter).
- Hold the insert while `rowsHeld()`.
- Say that nothing shows while waiting (no spinner).

**M3. The pull's limits (§5.4 "When").** Inside the sync the pull must:
- catch everything except `CancellationException` ("never fails a sync", as `MusicSync` does);
- have its own call timeout (≤ 15 s), so a hung server can't spend the sync's 10-minute budget before the self-update
  commit;
- have one in-flight guard, so a sync pull and a screen-on pull never send two GETs.

The phone's 10 s read timeout is also shorter than the server's worst first answer for a new cell (MET 15 s plus
Kartverket inline, server QA M2). If the disconnect cancels the server's handler, that cell may never fill. The server
must answer within ~8 s (fetch spawned, `unavailable` meanwhile); settle this together with server M2.

**M4. Location Off leaves the place on the lock (§5.4 "What it keeps").** The kept answer, place name included, stays
until the next pull answers `no_position` (up to 55 min), or up to 3 h when the phone is offline. The parent switched
Location off and the lock still says "Nydalen". **Fix:** an accepted policy whose location mode isn't `interval`
clears the store at once, as the switch turning off does. Add it to the pure keep rule (L9).

**M5. The spoken strings (§5.6, §5.5 "TalkBack").**
- `weather_mark_description` / `weather_period_description` say "%2$d grader" / "degrees", so ±1° reads "1 grader" /
  "1 degrees". Use `<plurals>` with `getQuantityString(abs(t))`, and the same for "laveste 2, høyeste 7 grader".
- An unknown symbol, or no words, needs its own string variant. The project's no-gluing rule rules out "Været: , 6
  grader. ".
- The note's example says "klokka 14" but reuses the shown "kl. 14". Decide between spoken variants and TTS's own
  expansion, and pin the choice.

## Low

1. **The centred layout (§5.3, §5.5).** Without a profile the row is centred, so `lineDp` ("from the date's start") is
   undefined, and the slot can't be "start-aligned with the date". Define it as: no profile → the content width, with
   the row and the slot centred; profile → the content width less the date's start.
2. **`LockClockFace` changes.**
   - `extra` is `List<TextView>`, but the slot is a ViewGroup.
   - `profileLook`'s gravity loop must take the row instead of the date.
   - The no-profile short date needs `FirstCapital` too. Today's long date is lower-case in nb ("mandag 3. november"),
     not D14's "Mandag 3. november".
3. **No word may be cut (§5.5).** A `maxLines=1` `TextView` with no ellipsize that ends up 1 px short breaks at a word
   and shows "· Regn fra" with no hour. Measure with `Layout.getDesiredWidth` (as `MusicLockPanel.fixWidth` does) plus
   ~2 dp of slack, and make the view `setHorizontallyScrolling(true)` so it never wraps.
4. **Font scale.**
   - The 22 dp symbol and the 14 dp chevron are in dp; at 2.0 the date is ~28 sp. Size the symbol from the text
     (≈1.4 × textSize, at least 22 dp) at bind and at refit.
   - Make the words' "1 sp smaller" a `RelativeSizeSpan`: `FontScaleRefresh` rescales only the view's own size.
5. **Moving one view between two parents (§5.3)** means reparenting it and keeping margins in step. Two mark instances
   (the row and the slot), one of them visible, are simpler, and `LockClockFace` places only the visible one.
6. **The gap under the card.** `placeList` puts the rows 22 dp under an open card when there is no music, timer or
   rule card. Count the open card in that margin rule; it still doesn't count toward the compact player.
7. **The card against the mockup.**
   - The past periods at 60 % alpha aren't in the approved mockup, and `kid_core_dim` at 60 % on `kid_music_tile` is
     ≈4:1, under 4.5. Drop them.
   - The mockup marks the current column with `rgba(255,255,255,.06)`. Name that tone as a colour token
     (`KidPaletteTest`).
   - The mockup's mm is rain blue `#7CC4F2` and keeps its line height. With the note's "gone" the card's height
     changes from day to day; use INVISIBLE when any column has mm.
8. **The touchables test.** `LockGlanceTest` ("the page's touchables ...") parses `view_lock_glance.xml` without
   following `<include>`, so the mark and the card escape it. Follow includes, and add the two beside the timer tile
   and the "more" line as touchables that keep no touch. That replaces the separate `WeatherGuardTest` clause.
9. **Pure parts.** The step split works, and `WeatherModel` is pure. Also make these pure, as `glanceStageStep` is:
   - the store's keep rule: `ok` replaces; `off`, `no_position`, the switch off and Location not `interval` clear;
     everything else keeps the answer and stamps `tried_at`;
   - the open/fold step: tap, lock-up, stage off, stop, line hidden, no card, VoIP;
   - M2's nowcast window.
10. **`weatherWordsRes` returns a resource name**, which needs `getIdentifier` (no compile-time check, lint
    DiscouragedApi). Return the key/form pair and map it to `R.string` in a `when` (or a generated table); the contract
    test then checks every pair.
11. **`weatherHour` is keyed on `Locale`**, but every non-nb locale reads `values/`. Key it on the strings' catalog.
    Also show the minutes when the hour doesn't start on :00: §7.1's vectors include Asia/Kolkata.
12. **Re-pick and the chevron.** The minute tick runs only while the panel is started; also re-pick at `resume`, so a
    line stale for 3 h hides before the first frame. When there is no card, hide the chevron too.
13. **The "Neste" line.** The slot's own line pushes row 3 down ~20 dp. In today's worst case on the Jelly Star 22.4 dp
    of row 3 show, and that becomes ≈2.4. The "Neste" line then yields in more cases (`glanceNextFits`). Say that the
    weather line is part of the face and "Neste" yields first, and add the slot to `LockGlanceLayoutTest`'s 473/497 dp
    cases.
14. **The card's place.** The note puts the card after the alarm and "Neste" lines, not right under the date as the
    user put it. That is right, because both lines belong to the face. Say so in §0, so the emulator check doesn't
    flag it.
15. **The converter (§4.1), from the sample.**
    - Every `clipPath` (16 of 18 files) is the full `M0 0h100v100H0z`: drop it rather than emit `<clip-path>`, whose
      edges VectorDrawable doesn't anti-alias.
    - 15 stops carry `stop-opacity`, which goes into the colour's alpha.
    - `mix-blend-mode` comes in `style=""`, not as an attribute.
    - A radial `gradientTransform` with non-uniform scale, skew or rotation can't be expressed and must fail. The
      sample has only uniform `translate`/`scale`/`matrix(a 0 0 a ..)`.
    - Optional: every multiply layer in the sample repeats an earlier solid path's `d` exactly, so it can be baked in
      (stop × fill at its opacity) instead of dropped. That keeps yr's cloud shading (Risk 5) at no cost.
    - The symbols' contrast: the scrim is computed for the text inks (`LOCK_INKS`) only. The darkmode set is light
      (#999-#fff clouds, #00B8F1 drops) and reads on that ground. Add one emulator shot on a bright photo and on
      Sky/Sunset to §7.3.

    For CI: `launcher.yml`'s path regex (line 79) needs `server/testdata/weather(_symbols)?\.json` and
    `scripts/yr-symbols\.py`, plus a `python3 -I scripts/yr-symbols.py --selftest` step. `server-ci.yml` needs the
    launcher's two copies. `WeatherContractTest` needs the contract's `now` and the expected line written in the file
    (§7.1 has no `answer_now_ms` / `answer_line`).

## Over-design

- "Oppdatert kl. HH:MM" only appears when the phone has been offline or MET is failing for 1-3 h. It is cheap, but if
  the brief must shrink, cut it first: the 3 h hide is the user's rule.
- With M1's fixed place, the ladder is fine as four steps. Nothing else is more than the user asked for.

## Launcher-look

The design is calm: no frame, no colour field, a dim chevron, no fold animation, and `requestRectangleOnScreen` with no
scroll animation. What breaks the calm is motion the kid didn't cause: M1's line hop, M2's late line and the long/short
date flip. With those fixed, the page changes only when the kid taps or the screen comes on.

## Revision check (689bb9cb)

Read against the revised note (§5, D14-D15, §7.3, §11). **Every High and Medium is closed. Nothing blocks the brief;
three new Lows.**

- **H1, closed.** One `TouchDelegate` on `lock_glance_top` gives the visible mark a hit area 48 dp tall and 8 dp wider
  at each side, set again whenever the mark moves (§5.5). It works as written: the clock, the date row, the alarm line
  and "Neste" aren't clickable, so a touch in the widened area falls through to `lock_glance_top`'s `onTouchEvent`,
  and the open card, a clickable child, still takes its own touches first. `LockGlanceLayoutTest` pins the hit
  rectangle in both places.
- **M1, closed.** The place is chosen only at a lock-up, a refit, a width change or the first bind, from the widest
  words of the four entries, never less than the reference "Regn fra kl. 14". In between, a model that doesn't fit
  drops its words; the mark never moves. The short date follows the switch (D14). The budget table is recomputed and
  consistent with the ladder. **The user's Jelly Star answer holds:** at 269 dp the reference alone gives LINE_WORDS at
  font 1.0 and 1.3 (beside 312 > 269) and LINE at 2.0, every day, with or without words.
- **M2-M5, closed.**
  - M2: 3 s timeout, a 1 s insert window, a generation counter and `rowsHeld` (pure `weatherNowShown`).
  - M3: a catch-all, a 15 s call timeout, one in-flight guard, and the server answers within ~6.5 s.
  - M4: any change of the location mode clears the kept answer and makes a pull due (pure `weatherKeep`).
  - M5: whole sentences, plurals on `abs(temp)`.
- **The two mark views, fine.** `lock_glance_weather` and `lock_glance_weather_line` are bound from one model, at most
  one is visible, nothing is reparented, and the slot is in `extra` (now `List<View>`) at the date's start or centred.
  `TalkBack` sees only the visible one; the hit area follows it.
- **The Lows** are all taken (§11). L8's touchables test now follows `<include>`. "Oppdatert kl." stays, which is the
  user's call.

New, Low:

1. **The hit area's two limits can conflict (§5.5).** "At least 48 dp tall" and "never taller than the gap to the
   clock's ink above" contradict each other when the profile date sits in the clock's descent. Say what wins: cap the
   top at the gap and take the rest below.
2. **The line can appear after the screen comes on.** If the answer was more than 3 h old at the screen-on (the
   morning, after Doze), the line is hidden at the lock-up and appears ~1 s later when the screen-on pull lands. On the
   Jelly Star that adds the own line and moves the rows once. Decide one of two: accept it, or hold the slot's height
   while the switch is on and a pull is in flight. The place itself is already known from the reference.
3. **`weather_spoken_mm` in en.** `getQuantityString` takes an int, and CLDR reads "1.0" as `other`. Use `other`
   whenever the shown value has a decimal, or always say "millimetres" in en.
