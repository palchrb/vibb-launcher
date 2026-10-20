# Launcher round 6 - the small items (proposal)

Decided in PLAN on 2026-10-20. Launcher only; no API change. Goes out with designs 41 and 42 as launcher 0.31.0.

## 1. An inbox event asks for a sync at once

Today only the emergency call requests a sync (`EmergencyConnects` -> `CommandListenerService.requestSync`); the
parent-PIN events (design 24: "no send of its own", decided before push existed), foreign access, an unseen boot and
low battery ride the next sync (screen-on, an SSE nudge, else the 30-minute backstop) - the user saw a parent-PIN use
reach the iPhone minutes later.

- `ParentPin`: after `store.save(...)` of a used, wrong or lockout event (`ParentPin.kt:246, 264`),
  `CommandListenerService.requestSync(context, "pin_event")`.
- `ForeignAccess.checkInBackground`: when the check finds a report that differs from the last one it stored (a new
  foreign package, or none any more), `requestSync(context, "foreign_access")`.
- `BootWitness.record`: when `isNewBoot` is true, `requestSync(context, "unseen_boot")` (the start's sync may run
  anyway; the sync mutex coalesces).
- `BatteryStatus.watch`: on `ACTION_BATTERY_LOW`, `requestSync(context, "battery_low")` (not on OKAY).
- `requestSync` returning false (the anchor service can't start) changes nothing: the backstop covers, as today. No
  new work when nothing changed. A pure `SyncTriggers` (JVM) decides "ask now?" per event so each rule is a test, not
  a scattering of calls: `pinEvent(kind) -> true`, `foreignAccess(previous, current) -> changed`, `boot(isNew)`,
  `battery(action)`.

## 2. The lock's music tile is rendered alone

The recording of 2026-10-20 (PLAN, "Play/pause flicker on the lock's music tile"): every session callback
(`MusicLockPanel.changed()` -> `GlancePanel.render()` -> `renderNow()`) rebinds the rows and runs `fitNow()`, which
measures by setting the live clock's text size big, then small, and `lockGlanceMore`'s text, then picks the tile size
again - a full page re-layout two or three times per tap, visible as the whole card redrawing ~0.8 s after a tap.

- `MusicLockPanel.changed()` renders the tile alone (`render()`), then tells the page only whether the tile's
  presence changed (`glanceTile(...)` flipped) - `GlancePanel.onMusicTile(changed: Boolean)` re-fits only then; the
  rows are rebound only on a store/rows change (`GlanceStore` callbacks), never on a session callback.
- `fitNow()` measures on measure-only copies: `measured(view, width)` for the clock uses a `TextPaint`/`StaticLayout`
  with the two sizes (or a detached `TextView` kept for measuring), and "+ N varsler" is measured the same way;
  the live views are set once, after `glanceFit` decided.
- Callbacks within one frame coalesce: a posted render (`Handler.post` with a pending flag, or
  `Choreographer.postFrameCallback`), so two or three session messages per tap draw once.
- Design 42's clock tile follows the same path (its own `render()`, the fit only when it comes or goes).

Tests: a JVM test of the coalescer (three calls in one frame = one render); `GlanceFitTest` unchanged (the maths is
untouched); `LockGlanceGuardTest` unchanged. Device check: the recording method of 2026-10-20 (`ffmpeg` scene changes
on the tile's region) shows one change per tap and no later full-card redraw.

## 3. Not in this round

- The diagnostics of the VPN incident (PLAN "Diagnostics from the same incident"): (1) and (2) are the music app's
  (its round); (3) the Web Filter's "upstream DNS can't be reached" line is a status-contract change in both
  directories (server + launcher in one commit) and goes with the next server round.
- `launcher/CLAUDE.md`'s split into area notes: a mechanical task on its own, after this round's merge, so the notes
  land where the new code's sections go.

## 4. Tests (required)

`SyncTriggersTest`, the coalescer test, and the existing suites (`./gradlew assembleDebug assembleRelease
testDebugUnitTest`). Device checks: a parent-PIN use on the emulator reaches the inbox within seconds (logcat
`CommandListenerService: ... sync (pin_event)`), a low-battery edge (`adb shell dumpsys battery set level 5`) the same;
the tile recording.
