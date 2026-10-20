# Launcher round 6 - the small items (proposal)

Decided in PLAN on 2026-10-20. Launcher only; no API change. Goes out with designs 41 and 42 as launcher 0.31.0.

## 1. The parent PIN asks for a sync at once

Foreign access and battery already do: `ForeignAccess.checkInBackground` calls `SyncRunner.request(app,
"foreign_access")` on a changed report, `BatteryStatus.watch` on `ACTION_BATTERY_LOW` and `ACTION_BATTERY_OKAY` (both
stay as they are), and every process start - so every unseen boot - already runs a sync. Only the parent-PIN events
(design 24: "no send of its own", decided before push existed) ride the next sync (screen-on, an SSE nudge, else the
30-minute backstop) - the user saw a parent-PIN use reach the iPhone minutes later. (PLAN's "today only the emergency
call" was wrong; the coordinator fixes PLAN.)

- The request goes in `ParentPin.check`, the glue that calls the pure `checkParentPin`, after the result is final:
  on `Ok`, on `Wrong`, and on a `LockedOut` that starts a lockout now. Never during a lockout already running (no
  event is written, so a kid hammering OK causes no syncs). Not inside `checkParentPin` (`ParentPin.kt:246`): the
  save there is design 24's "count first", a provisional `wrong` event committed before PBKDF2, and a sync then would
  upload it on every correct entry.
- The call is `SyncRunner.request(context, "pin_event")` like every other trigger (it takes the wake lock and the
  pending count), not `CommandListenerService.requestSync`; it returns nothing - a failed service start is handled
  inside `request`, and the backstop covers.
- The pure `SyncTriggers` (JVM) holds that one rule: `pin(result, lockoutAlreadyRunning) -> Boolean`: true for
  `Ok`, `Wrong` and a `LockedOut` that starts now; false for a `LockedOut` while one was already running or from an
  unreadable state, and for `TooShort`, `NotConfigured`, `Unavailable` and `ForeignAccessOn` (nothing happened on the
  server's side). `ParentPinResult.LockedOut` alone can't tell the three cases apart, so `ParentPin.check` (it is
  `@Synchronized`) reads `ParentPin.status(context).lockoutMs > 0` before `checkParentPin` and hands that in as
  `lockoutAlreadyRunning`; an unreadable state returns before any request.

## 2. The lock's music tile is rendered alone

The recording of 2026-10-20 (PLAN, "Play/pause flicker on the lock's music tile"): every session callback
(`MusicLockPanel.changed()` -> `GlancePanel.render()` -> `renderNow()`) rebinds the rows and runs `fitNow()`, which
measures by setting the live clock's text size big, then small, and `lockGlanceMore`'s text, then picks the tile size
again - a full page re-layout two or three times per tap, visible as the whole card redrawing ~0.8 s after a tap.

- `MusicLockPanel.changed()` renders the tile alone (`render()`). The page refits only when the tile's presence
  flipped or the shown tile's measured height changed (a two-line title, the entry line coming or going): render the
  tile, measure it at the page width, refit when the height differs from the last fit's. The rows are rebound only
  on a store/rows change (`GlanceStore` callbacks), never on a session callback.
- At a refit the live views are measured as today (no measure-only copies, no `StaticLayout`): with the rule above
  refits are rare, and the live views match the clock's font, weight, padding and font scale by construction.
- Callbacks within one frame coalesce: a posted render (`Handler.post` with a pending flag, or
  `Choreographer.postFrameCallback`), so two or three session messages per tap draw once.
- Design 42's clock tile follows the same path (its own `render()`, the same refit rule).
- **The tile shows the time** (the user, 2026-10-20: "kunne lagt inn hvor man er (timestamp) og hvor lang episoden
  er i progress baren på locked screen? vi viser jo den på de andre kortene"): a small line under the progress bar on
  both the full and the compact tile, "4:48 · 25:36" (`clockText(position)` and the duration; a live stream or an
  unknown duration: the position alone), in the row-sub tone, ticking with the bar once a second while playing (the
  existing ticker). The line has a fixed width with tabular figures (`fontFeatureSettings "tnum"`), so the tick never
  relayouts the page. `glanceFit`'s `tileFull`/`tileCompact` terms grow by the line, so `LockGlanceTest`'s pinned
  numbers (`tileFull` 211, `tileCompact` 90, the model at :66-69, the expected fits at 473/497 dp) change and are
  re-derived and re-pinned in the same commit. On the compact tile at 320 dp with the largest font scale the line
  must leave the 3 dp bar intact: an assertion in `LockGlanceTest`'s layout test (the bar keeps at least 120 dp).
  The full tile grows by the line and its margin; the compact tile's height is its 52 dp art row, so `tileCompact`
  grows only by what the text column (now four lines) exceeds 52 dp - both re-derived from the XML, never "+ one line"
  for both; `fixed` 106 stays.
  Today the text exists only as the bar's `contentDescription`; it stays there too.

Tests: a JVM test of the coalescer (three calls in one frame = one render); the refit rule (a height change refits, an
unchanged height does not); the re-pinned `LockGlanceTest` numbers; `LockGlanceGuardTest` unchanged. Device check: the
recording method of 2026-10-20 (`ffmpeg` scene changes on the tile's region) shows one change per tap and no later
full-card redraw; the same recording method on the music app's player screen (PLAN's flicker entry asks for it before
that screen is touched) - a device-check step only, no code this round.

## 3. Not in this round

- The diagnostics of the VPN incident (PLAN "Diagnostics from the same incident"): (1) and (2) are the music app's
  (its round); (3) the Web Filter's "upstream DNS can't be reached" line is a status-contract change in both
  directories (server + launcher in one commit) and goes with the next server round.
- `launcher/CLAUDE.md`'s split into area notes: a mechanical task on its own, after this round's merge, so the notes
  land where the new code's sections go.

## 4. Tests (required)

`SyncTriggersTest`, the coalescer test, the refit-rule test, and the existing suites (`./gradlew assembleDebug
assembleRelease testDebugUnitTest`). Device checks: a parent-PIN use on the emulator reaches the inbox within seconds
(logcat `CommandListenerService: ... sync (pin_event)`), a wrong PIN and a lockout starting do too, a correct entry
uploads no `wrong` event, a kid's OK during a running lockout causes no sync; the tile recording, and the player-screen
recording.

## Revision (2026-10-20, after qa-r6-launcher-design.md)

- r6-1: §1 shrinks to the parent PIN; foreign access, battery (LOW and OKAY) and the boot sync already exist.
- r6-2: the request is in `ParentPin.check` after the final result; `SyncTriggers.pin(result, lockoutAlreadyRunning)` (§1).
- r6-3: `SyncRunner.request`, not `CommandListenerService.requestSync` (§1).
- r6-4: refit also when the tile's measured height changed; render, measure, compare (§2).
- r6-5: the time line under the bar on both tiles, fixed width with tabular figures, pinned numbers re-pinned, the 320 dp bar assertion (§2).
- r6-6: no measure-only copies; live views measured at a refit as today (§2).
- r6-7: the player-screen recording as a device-check step, no code (§2).

## Implementation status (2026-10-20)

### §2 - the music tile rendered alone, the time line (implementer B, branch `r6-b`)

- **Render alone**: `MusicLockPanel`'s session and listening-window callbacks only call the panel's
  `GlanceCoalescer` (pure, `lock/LockGlance.kt`): one `Choreographer.postFrameCallback` per frame, so the two or three
  session messages of a tap draw once. The frame's `GlancePanel.tileChanged` looks the session up and renders the tile
  alone - no row rebind, no clock measure. It refits (`fitNow`, the live views as before, no measure-only copies) only
  when the pure `glanceRefitDue(tileWasUp, tileUp, fittedPx, measuredPx)` says so: the tile came or went, or the shown
  tile, measured with its margins at the page width, differs from the height the last fit was made with
  (`fitTilePx`, kept by `fitNow`). The rows are rebound only by `GlanceStore`/`MissedCallNotifier` callbacks and the
  start/resume/stage paths, as before.
- **The time line**: `lock_media_time` / `lock_media_compact_time` under each bar (the compact one under its bar, never
  beside it), "4:48 · 25:36" (pure `glanceTimeLine`; a live stream or an unknown length the position alone), 12 sp
  Nunito 700 in `kid_core_dim`, `fontFeatureSettings="tnum"`, one line, ellipsized, `importantForAccessibility="no"`
  (the bar's content description still says "4:48 av 25:36"). The once-a-second tick (`MusicLockPanel.tick`) sets only
  the bars, their descriptions and the two lines - the play icon, art and texts only on a callback.
- **Deviation - the line's width**: `match_parent`, not a width measured in code for "88:88 · 88:88". A TextView whose
  width isn't `wrap_content` swaps a same-height text with an invalidate and no `requestLayout`
  (`TextView.checkForRelayout`), which is what the measured width was for; `match_parent` needs no measuring, follows a
  font-scale change by itself, and can't outgrow the compact column at 320 dp and font scale 2.0 (a measured
  "88:88 · 88:88" at 12 sp x 2.0 is wider than its 144 dp). `LockGlanceLayoutTest` pins the attributes.
- **Re-pinned numbers** (`LockGlanceTest`, the 1.4 x line model from the XML): `tileFull` 211 -> 233.8 (the line,
  16.8, and its 6 dp margin), `tileCompact` 90 -> 107 (the text column is 69 dp, 17 more than the 52 dp art row),
  `fixed` 106 unchanged. Expected fits at 473 dp (both bars): full tile alone 1 (467.8); no music, 4 rows 1 (464);
  music + 1 row 3 (411); **music + 2 rows 4** (452.6 - the small clock; was 3, mockup A's big clock now needs 475);
  **music + 3 rows 5 with one row** and "+ 2" (412.6; was two rows). At 497 dp (gesture navigation): **music + 1 row 3**,
  the compact tile (411; the full one needs 515.4, was 2), music + 2 rows 3 (475). The line costs the Jelly Star's
  3-button layout one row with music and three notifications.
- Tests: `LockGlanceTest` (the time line, the refit rule, the coalescer - three requests in a frame, one render - the
  re-pinned model and candidates), `LockGlanceLayoutTest` (the tiles' heights, the expected fits, the line under each
  bar), `LockGlanceGuardTest` unchanged for §2.
- **Device checks owed**: the tile recording of 2026-10-20 again (`ffmpeg` scene changes on the tile's region): one
  change per play/pause tap, no full-card redraw ~0.8 s later; a track change from a one-line to a two-line title
  refits once (the "Lås opp"/"Nødsamtale" row stays on screen); the line ticking with no page movement. The same
  recording on the music app's player screen (no code this round).
