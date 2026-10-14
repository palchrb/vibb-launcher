# QA (code): the music app's fixes after the emulator round (2026-10-14)

Reviewed: `emu-round-music-fixes` at `897f32ed`, i.e. `git diff a9ff636f 897f32ed`. That is `ee6f5655` (N1-N5),
`3cf21276` (N6, N11) and the docs commit `897f32ed`. I read them against `docs/testing/2026-10-14-emulator-round.md`
(N1-N6, N11), 26c's new "Fixes after the emulator round (2026-10-14)", `26c-handover.md` §5, design 34 step 11's new
status, `music/CLAUDE.md`, `launcher/CLAUDE.md`'s "Calls" and `MusicCalls.kt`/`MusicMediaPlan.kt` (read only).

## Summary and verdict

**0 High, 2 Medium, 3 Low, 2 Nits. Good for the emulator re-run once M1 is fixed. M2 is a design recommendation for
N3; the re-run can go ahead without it.**

- **N1, N2, N4, N5, N6 and N11 are correct, small and pinned.** 15 of 16 mutations turn tests red. The one that
  survives is the 5 s window made 10 minutes long (below).
- **N4 never reaches a release build.** `DebugProxy` sits in `main`, as the inline proxy did before it. It is reached
  only through `WebDecision.Proxy`, and that needs a debug host in `WebPolicy`. A release has none, because
  `SpotifyEndpoints.debugBase` is assigned only from `src/debug`. So nothing new is exposed.
- **No "Spotify" word anywhere.** The new log line says "web player", and `SourceScanTest` is green. No contract file
  changed and the launcher is untouched.
- **N3 has two halves.**
  - **(a) The audio mode now drives the web player too** (`callOn`, `spotifyInCall`). This is right, but it turns a
    long-standing loose end into a bug (M1). `heldByPage` never ends by itself. So a page that another app paused for
    good counts as "playing" at the next system call, and Vibb starts playing after that call.
  - **(b) The 5 s session window works for the launcher's look, but it lies to every other reader of the session**
    (M2). The cleaner fix is in the launcher: send `CALL_HOLD` at every call begin. That also closes the VoIP residual
    the branch leaves open.

## Runs (this worktree, `897f32ed`)

- **music** (under `~/build.lock`): `./gradlew --no-daemon --max-workers=2 assembleDebug assembleRelease
  testDebugUnitTest lintDebug --rerun -PwarningsAsErrors=true` gives **BUILD SUCCESSFUL** with **886 tests, 0 failed,
  0 skipped**. Lint passes; its report holds only the usual version-bump warnings.
- `python3 -I scripts/fake-spotify.py --selftest` is all OK, including the new check that no `pause` is posted before
  `ended`.
- No emulator was used, as asked.

### Mutations

Each mutation was applied to one source file, run against the named test classes, and then restored from memory.
`git status` is clean.

| # | Mutation | Tests | Result |
|---|---|---|---|
| 1 | N1: the paused state at the duration read as a pause (`atDuration` branch off) | tracker, renderer | **red**: 6 (both N1 tracker tests, the replay, N1/N2 renderer) |
| 2 | N1: `advance()` never emits `Resumed` | tracker, renderer | **red**: 2 |
| 3 | N2: the boundary error blamed on the current item | tracker, renderer | **red**: 3 |
| 4 | N3: `SPOTIFY_HELD_SHOWN_MS` = 300 ms | renderer, Guards | **red**: 1 (`held >= 1_000`) |
| 5 | N3: `SPOTIFY_HELD_SHOWN_MS` = 10 minutes | renderer, Guards | **survived** |
| 6 | N3: the window unbounded (`wanted \|\| held`) | renderer, Guards | **red**: 3 |
| 7 | N3: the mode doesn't count on the web player (`callOn` SONOS only) | rules, Guards, renderer | **red**: 1 |
| 8 | N4: the proxy keeps `callTimeout(45 s)` | DebugProxyTest | **red**: 1 |
| 9 | N4: `Content-Length` not passed | DebugProxyTest | **red**: 2 |
| 10 | N5: `loadedPlayStale` ignores the mode (list->context tap goes on with the list) | rules, Guards, renderer | **red**: 2 |
| 11 | N6: floor back to 120 dp | FitTest | **red**: 3 |
| 12 | N6: category tiles a fixed fifth again | FitTest | **red**: 1 |
| 13 | N11: "Fjern alle" also when empty | EmptyStateTest | **red**: 1 |
| 14 | N6: the kind chip down to the floor | FitTest | **red**: 1 |
| 15 | N2: `lastPaused = false` dropped after the boundary move | tracker, renderer | **red**: 2 |
| 16 | N3: the session ignores the hold (`pwr && !ended`) | renderer | **red**: 2 |

**Survivor (#5):** nothing bounds the window from above. Only "at least 1 s" is pinned. Most of M2's side effects
grow with the window. If the window stays, pin an upper bound, for example `held <= 5_000`. If M2's fix is taken, the
window goes and the survivor goes with it.

## Findings

### Medium 1 - A page another app paused for good counts as playing at the next system call, and Vibb plays after it

- **Where.** `PlaybackService.kt:1322`, `val playing = r.showsPlaying || (r as? SpotifyRenderer)?.heldByPage == true`.
  It became reachable through `callOn(..., SPOTIFY)` (`RendererRules.kt:95`) and the mode listener
  (`PlaybackService.kt:1291`).
- **The cause.** `heldByPage` is set at every pause the page makes that isn't ours (`SpotifyRenderer.kt:1309`). It is
  cleared only by our own play, pause, load, detach or suspension. Chromium resumes after a *transient* loss (a ring,
  an alarm), but not after a *permanent* one: another app starting playback, such as YouTube, a game or another player.
  That hold therefore lasts until the kid comes back, and the idle close is suppressed for it too (pre-existing, M1 of
  qa-26c-6l-fixes).
- **The scenario.**
  1. The kid listens to a Spotify entry, then opens a video app, which pauses the page for good.
  2. Minutes later a phone call rings. `MODE_RINGTONE` reaches `callStep`. `callOn` is now true for SPOTIFY, and
     `playing` is true through `heldByPage`. The result is `PAUSE` and `pausedByCall`.
  3. The call ends. The result is `PLAY` and `r.player.play()`, so the web player starts and takes the focus from the
     video.
- **Why it matters.** This breaks the user's call rule ("resumes afterwards only if we paused it") and "nothing starts
  by itself". Before this branch it could not happen: the mode didn't count on the web player, and the launcher's
  `CALL_HOLD` needed a playing session. With the window, a launcher `CALL_HOLD` inside the 5 s after another app's
  takeover does the same, though that is rare.
- **Fix.** In `callStep`, count a held page as playing only while the hold is fresh, with the same bound as the session:
  `val playing = r.showsPlaying || (r as? SpotifyRenderer)?.heldRecently() == true`. Here `heldRecently()` =
  `heldByPage && now - heldAt in 0 until SPOTIFY_HELD_SHOWN_MS`, and the pure rule goes into `logic/Spotify.kt`.
  - The ring's pause comes 60-220 ms before the launcher's look, and the mode changes at the ring itself. Both stay
    well inside the bound, so M1 of qa-26c-6l-fixes (pause first, then `CALL_HOLD`, then a long call) still works:
    `pausedByCall` carries the call.
  - Test: hold the page, advance 10 s, run `callStep(r, inCall = true)` and then `false`. Assert that nothing is sent
    to the page or the API and that `showsPlaying` stays false. Update the GuardsTest pin of that line.

### Medium 2 - N3 (b): the session says "playing" for 5 s while nothing plays; the launcher should send the call's edges instead

- **Where.** `logic/Spotify.kt:231-239` (`SPOTIFY_HELD_SHOWN_MS`, `spotifySessionPlays`) and
  `SpotifyRenderer.kt:288-299, 788`.
- **What the lie reaches.** For 5 s after every pause the page makes on its own (a ring, but also an alarm, a
  notification's transient loss, or another app taking over), the session's `playWhenReady` is true in state READY.
  Media3 reports that as `STATE_PLAYING` with speed 1 to everything that reads the platform session:
  - **The system media controls and the lock screen.** They show the pause button, and the scrubber runs on for up to
    5 s by the platform's extrapolation, then jumps back.
  - **The media button and AVRCP.** Media3 picks play or pause by `shouldShowPlayButton`, so a headset or lock-screen
    press inside the window *pauses* (an `askedPaused` no-op) instead of playing. The kid has to press twice to get the
    music back after a notification blip. A car or headset display shows "playing".
  - **The launcher's Home card and the glance page's music tile.** Both read `sessionPlay` PLAYING (design 28), so
    they show playing with a pause button.
  - **The FGS.** Media3 keeps the service in the foreground through the window. That is harmless, because it was
    already foreground while playing.
  - **What stays honest.** The music app's own screen, the locks and `setNowPlaying` follow `showsPlaying`.
- **Why it's still bounded.** No reader misleads the launcher into a call action on its own. `MusicCalls.evaluate`
  acts only on a call edge (VoIP, its dialer or Telecom/mode), never on a session change. So an alarm's 5 s window
  sends nothing unless a call begins inside it. The child sees "playing" on the lock screen or Home for at most 5 s
  while nothing plays.
- **The design is incomplete.** It still leaves the residual the doc names: a VoIP call that began while paused, with
  a play tapped during it, is Chromium's to resume. The launcher sends nothing then.
- **Recommendation: move the decision to where the knowledge is.** The launcher knows the call, and the music app
  knows what it holds. `callPauseStep` already ignores a call that begins while nothing plays (`CallPause(inCall =
  true)`, no action) and plays again only what it paused.
  - **Launcher:**
    - `callHoldStep` (`launcher/.../music/MusicMediaPlan.kt:157`) sends `HOLD` at every call begin while a music
      session exists, and `RELEASE` at that call's end after a `HOLD`.
    - `MusicCalls.evaluate` no longer reads `playing`. It keeps the log line, which can still say whether the session
      was playing.
    - Update `MusicMediaPlanTest` and the "Calls" bullet in `launcher/CLAUDE.md`.
  - **Music app:**
    - Remove `spotifySessionPlays`, so the session follows `pwr && !ended` again.
    - Keep the windowed hold inside `callStep` only (M1's `heldRecently`) and the mode backstop (a launcher without
      listener access).
    - Every renderer then hears VoIP calls too (`spotify.inCall`), which closes the residual: a tap during any call
      starts nothing.
    - Update `music/CLAUDE.md`'s "Calls" and Spotify bullets and 26a §6.7's wording: "`CALL_HOLD` = a call began".
  - **Contract:** `bridge_contract.json` is unchanged in both apps (same names, same custom actions). Only the
    *meaning* changes, from "a call began while playing" to "a call began". Per the root `CLAUDE.md` ("A change to the
    API goes into both directories in the same commit"), commit the launcher's `MusicCalls`/`callHoldStep` change and
    the music app's removal together, with both CLAUDE.md files. No AIDL change, and no root `CLAUDE.md` change beyond
    that.
  - **Skew:**
    - A new launcher with an old music app is safe: the old `callPauseStep` ignores a `HOLD` while paused, and the old
      web player only refuses starts during the call.
    - An old launcher with a new music app loses only the VoIP-ring case on Spotify, which is what this branch had
      before (b). A system call is still covered by (a).
- **If the user prefers to keep this music-only for now:**
  - keep (b);
  - pin an upper bound on the window (mutation #5);
  - list the lock-screen, Home-card and headset effects above as a stated deviation in 26c;
  - and give the window the same `heldRecently` as M1, so the two stay one rule.

### Low 1 - N2: the item that played out isn't marked done when the error is blamed on the next one

- **Where.** `SpotifyTracker.kt:346-353` returns only `unplayable(next)`. The renderer's `unplayable`
  (`SpotifyRenderer.kt:1440`) then moves with `moveIndex(k, auto = false)`.
- **What happens.** The service's `onItemChanged(k, auto = false)` skips `markDone(previous)`. So the track or episode
  that just played to its end keeps its last partial position, up to 5 s short. For a Spotify show in list mode, that
  episode shows as started, not done, and resumes near its end.
- **Fix.** At the boundary, emit the advance first:
  `return listOf(Event.ItemChanged(next, auto = true)) + unplayable(next)`. The renderer's `ItemChanged` moves the
  index with `auto = true`, and `unplayable(k == index)` no longer moves. Add `ItemChanged(1, true)` to the N2 renderer
  test's assertions.

### Low 2 - N1: a played-out pause that Spotify never follows ends as "Trenger internett" after 30 s

- **Where.** `SpotifyTracker.kt:613` (`playedOut()`).
- **What happens.** While Spotify knows our next item, or in a context, the paused state at the duration emits
  nothing and isn't measured. If no state follows, the renderer's state gap (`armGap`, 30 s from the last *playing*
  state) fires `fail(UNREACHABLE)`. The kid sees "Trenger internett", and the `unreachable` account word goes into the
  parent's report.
  - Cases where no state follows: the real SDK pausing at the end of a context's last track, a queue add Spotify
    dropped, or a focus loss landing in the last second.
  - The fake's stub never posts this shape any more, so no test covers it. Part B's recorder will show whether the
    real SDK does.
- **Fix.** When `playedOut()` defers, ask the page again: the `Look` the null rule uses, 2-3 s later. If the state is
  still our track paused at its duration, it is `end()` (or `contextEnd()`), never the network. Add a renderer test:
  a paused-at-duration state, then silence, ends with `Ended` or `NewWindow` and not `NEEDS_INTERNET`.

### Low 3 - N6: why the slot "didn't scroll (swipe tried)" is not explained, so the re-check is the only evidence

- **What the code does.** The scroll exists only when `libraryFit` says it does (`LibraryScreen.kt:211`), and
  `FitTest` pins that as text.
- **What the emulator showed.** The hint was cut at the slot's edge. `verticalScroll` clips, so the scroll was
  probably applied, with a range of about 65 px under the old floor. Yet the swipe moved nothing.
- **What the branch does about it.** It removes the need to scroll with gestures. With three-button navigation the
  slot still scrolls at the floor, and the swipe failure was never diagnosed.
  - Plausible causes: the swipe started on the `HorizontalPager` or the cover's `button`, or the range was too small
    to see.
  - No test can tell, because there are no Compose UI tests.
- **Fix.** Make the re-check specific: at 2.0 with three buttons, drag vertically on the title *and* on the cover, and
  check the dots come into view. Write the result into 34 step 11's status. If it doesn't scroll, the floor should give
  way once more (no scroll at all), rather than relying on a scroll.

### Nit 1 - N5 fixes the cover tap only

The now-playing bar's play, the notification and a headset's play still resume the old list after a flip, including
its queue adds. The doc says this is deliberate ("a library change alone doesn't end a live play"). Say in 26c that
those surfaces resume the old list until the next cover tap.

### Nit 2 - Small leftovers

- **`heldByPage`'s setter** (`SpotifyRenderer.kt:293`) arms an uncancelled `ports.after(5 s) { changed() }` at every
  hold. It is harmless (`changed()` returns once released) and goes away with M2.
- **`CategoryTiles`** measures every label with the `TextMeasurer` at each composition of the row
  (`LibraryScreen.kt:375`). A `remember(name, fontScale)` around the width would do.

## Counts

0 High, 2 Medium, 3 Low, 2 Nits. 16 mutations, 15 red, 1 survivor (#5).
