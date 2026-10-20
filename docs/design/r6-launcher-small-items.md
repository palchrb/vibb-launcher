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
  pending count), not `CommandListenerService.requestSync`. A false return (the anchor service can't start) changes
  nothing: the backstop covers.
- The pure `SyncTriggers` (JVM) holds that one rule: `pin(result, lockoutAlreadyRunning) -> Boolean`.

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
  must leave the 3 dp bar intact: a `FitTest`-style assertion in `LockGlanceTest` (the bar keeps at least 120 dp).
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
