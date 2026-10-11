# QA 26a step 2 code review - the renderer axis, local only (2026-10-11)

Scope: branch `music-1b-sonos`, 253c2ed..daf4ec6 (`469b109` the output keys, `d802ead` the renderer axis, `daf4ec6` the
step 2 status): `music/app/src/main/java/me/vibb/music/playback/` (`Renderer.kt`, `LocalRenderer.kt`, `PlayerModel.kt`,
`PlaybackService.kt`, `PlayerHub.kt`), the player screen's output `when`s, `MainActivity`/`MusicRoot`'s OUTPUT command,
the `output_sonos` string, `GuardsTest` and `OutputKeyTest`. Read against design 26a §0, §3, §4.6-§4.7, §8 step 2 and §9,
and Media3 1.7.1 (`MediaSession.setPlayer`). Line numbers are daf4ec6's.

Verdict: behaviour preserved line for line; 251 tests, 0 failures confirmed; lint clean; nothing persists the display
position. A short fix round now is cheaper than inside step 3a.

Counts: 0 High, 2 Medium, 6 Low.

## Medium

1. **M1: `switchRenderer` is an incomplete seam** (`PlaybackService.kt:204-207`, `:158-159`). `onEvent` is wired only in
   `onCreate`; `switchRenderer` neither sets `next.onEvent` nor clears the old one, and `onRendererEvent` reads whatever
   `renderer` currently is. In 3a a phone -> Sonos switch would make the local Exo's `Paused(USER)` cancel the kid's
   sleep timer, call `saveNow()` on the Sonos renderer, drop `saveTick`, and write Exo's duration under the Sonos index -
   or Sonos events never arrive. Fix: one `attach(r)` for `onCreate` and the switch (save, detach the old, wire the new,
   assign, `setPlayer(KidPlayer(...))`), pinned in `GuardsTest`; the KDoc says the old renderer is paused BEFORE it is
   detached, so its `Paused` event is still handled for the local renderer.
2. **M2: the H3 pin is weak** (`GuardsTest.kt:58`). `positionMs\(\)[^\n]*Row\(` misses `val pos = renderer.positionMs()`
   on one line and the Row on the next, and `renderer.player.currentPosition`/`contentPosition` inside a Row. Pin the
   allowlist (four `positionMs()` readers), forbid `.currentPosition`/`.contentPosition` in the service, pin that its only
   `PositionRow(`/`NowPlayingRow(` constructions are in `markDone`/`saveBookmark`, and slice `onCreate` for "rendererReady
   is completed in onCreate" instead of counting occurrences.

## Low

1. **L1**: `publish()`'s shown-output fallback `outputs.firstOrNull { it.kind != OutputKind.PHONE }` (`:858`) would pick a
   Sonos room once rooms are outputs. Use BLUETOOTH or WIRED only.
2. **L2**: unstated invariant (`Renderer.kt:35`): the service indexes `a.items` with `renderer.currentIndex()` everywhere.
   KDoc: it indexes the queue passed to `load`/`reorder` (`Active.items`); a renderer never filters - the speaker-playable
   filter belongs where the queue is built, the renderer passed explicitly (§9), so `load` without a `PlayPlan` stands.
3. **L3**: `setVolume(Float)` (`Renderer.kt:55-56`; callers `:545`, `:561`, `:566`) would collide with the Sonos device
   volume: `setSleep` calls it on every set, cancel and user pause, `sleepTick` every 250 ms for 10 s (40 SOAP calls past
   the cap). Rename to `setFadeGain`, "local gain only; a remote renderer ignores it, its fade is `fadeOutAndPause`".
4. **L4**: `onEvent`'s contract "inside the call that caused them" (`Renderer.kt:21`) can't hold for a polled renderer.
   State the real one: main thread, synchronous, possibly from a post; set only by the service on the current renderer;
   may re-enter the renderer; 3a's `SimpleBasePlayer` must tolerate that.
5. **L5**: `rooms` + `roomsStale` + `scanning` duplicate `sonos.RoomsView(rooms, stale, scanning)` field for field: one
   `rooms: RoomsView`. (The "Sonos / Sonos" placeholder subtitle is fine until §5.1.) Note in the status that 3a decides
   whether a room is an `Output` in `outputs`, a `Room` in `rooms`, or both (the bar needs the `Output`).
6. **L6**: `Output.deviceId` (`PlayerModel.kt:29`) accepts non-canonical keys (`"dev:+7"`, `"dev:07"`) that set
   `chosenOutput` but never match `deviceKey(id)` again. Store the canonical key in `setOutput`.

## For the status section (no code)

- Media3: `setPlayer` requires the new player on the same application looper and the call on it, so 3a's
  `SimpleBasePlayer` must be built on the main looper.
- The command coroutine "runs at once" only when the caller isn't itself inside a `Main.immediate` coroutine (then the
  launch is queued behind it).
- Step 3 must-dos: the `PLAYS` pin can't express "OUTPUT to a Sonos key is a play" as an action set - the gate must read
  `ARG_OUTPUT` in the synchronous prefix; `publish`/`reportNowPlaying` read `renderer.player.playWhenReady`, which won't
  match §3.3's "stale shows not playing" while §3.2's table holds `playWhenReady` through the first failed polls - 3a
  likely needs a `renderer.showsPlaying`.

## Fix round (the implementer, 2026-10-11)

- **M1**: `switchRenderer` is now `attach(next)`, called by `onCreate` (`attach(LocalRenderer(...))`, before the session
  exists) and by step 3's switch. With an old renderer it runs `old.saveNow(force = true)`, then `old.pause()` under
  `selfPausing` (the renamed `sleepPausing`: a pause the service makes itself never cancels the sleep timer; its
  `Paused`/`IsPlaying(false)` are still handled while the old one is `renderer`, so the position is saved and the 15 s
  tick dropped), then `old.onEvent = null`; then `next.onEvent = { onRendererEvent(it) }`, `renderer = next`,
  `session?.setPlayer(KidPlayer(next.player))`. New `GuardsTest` test: the order (save, pause before detach, wire before
  assign, `setPlayer(KidPlayer(`), `.onEvent = ` only twice and both in `attach`, `renderer = ` only once, `onCreate`
  attaches the `LocalRenderer`. Mutation: detaching before the pause turns it red.
- **M2**: `GuardsTest` pins exactly four `positionMs()` in the service, each by its line (`PlayerHub.position`,
  `previousAction`, the overlap seek, `setNowPlaying`); no `.currentPosition`/`.contentPosition`; one `PositionRow(`
  (inside `markDone`), one `NowPlayingRow(` and one `BookmarkRow(` (inside `saveBookmark`, at 0); `rendererReady` is
  completed inside the `onCreate` slice and nowhere else. Mutation: `val shown = renderer.positionMs()` on its own line
  feeding the `NowPlayingRow` turns it red.
- **L1**: the fallback is `it.kind == OutputKind.BLUETOOTH || it.kind == OutputKind.WIRED`.
- **L2**: `Renderer.currentIndex()`'s KDoc states the invariant as given.
- **L3**: `setVolume` -> `setFadeGain(gain)`, with the KDoc as given; the three callers renamed.
- **L4**: `onEvent`'s KDoc states the contract as given (main thread, synchronous, possibly from a post of a poll result;
  only the current renderer's, set only by the service; the handler may seek, pause, play, prepare or save from inside
  it; a `SimpleBasePlayer` emits outside its own handler futures).
- **L5**: `PlayerHub.State.rooms: RoomsView = RoomsView(emptyList(), stale = false, scanning = false)`; the status note
  is in design 26a.
- **L6**: `setOutput` stores `null`, `PHONE_KEY`, or `Output.deviceKey(device.id)`; `OutputKeyTest` shows `"dev:07"` and
  `"dev:+7"` reading back as `"dev:7"`.

Build: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` green in the
sandbox (Android SDK 36); 252 tests, 0 failures (+1, the M1 test); lint 0 errors, the same 41 warnings.
