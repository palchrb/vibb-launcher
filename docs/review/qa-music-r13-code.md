# Code QA - music round r13 (the launch splash; the room hand-over's timing and "Kobler til høyttaleren…")

- Branch `music-r13`, two commits over `main`'s `ef15e842`: `c23cd967` (the splash: `scripts/music-splash.py`, the two
  drawables, the theme's `windowSplashScreen*`, `MainActivity.holdSplash`, `logic/Splash.kt`, `SplashTest`) and
  `095eeb1a` (`sonos/HandOverTiming.kt` and its call sites, `SonosController.connecting`, `PlayerHub.State.connecting`,
  `logic/HandOver.kt` `timeRow`, `SeekBar`'s status line, nb + en strings, `HandOverTest`, design 54 "Hand-over timing",
  emulator.md §6u, PLAN, both CLAUDE.md files). The branch is behind `main` (pwa-fixes-c, server 0.52.0 and two PLAN
  commits came after its base): `git diff main..HEAD` shows those as deletions. The branch's own diff is
  `git diff main...HEAD` (26 files, music + docs only), and `git merge-tree main music-r13` merges cleanly.
- Read: root and `music/CLAUDE.md`; PLAN's two "Music app" items; design 54 "Hand-over timing (2026-10-25)"; emulator.md
  §6u; the full diff and the code around it (`SonosController` play/played/failed/dropped/pollTick/onSocket/earlyHint/
  spec, `SonosSession.cadenceMs`/`precise`, `SonosTransport.play`/`playShare`, `SonosRenderer.flush`,
  `PlaybackService.onCustomCommand`/`publish`/`queueFor`, `MusicRoot`, `MusicViewModel.snapshot`/`startOn`, `SeekBar`),
  `PlayBodies.speakerDropReason`, `scripts/sonos-play-test.py` (`upnp_watch`, `spotify_scenario`), the launcher's
  `splash_vibb_breathe.xml`, NOTICE.md.
- Ran: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` (full music suite): **BUILD SUCCESSFUL, 1210 tests, 0
  failures, 0 skipped**. `python3 -I scripts/music-splash.py --selftest`: **ok** (both committed drawables are byte for
  byte what the script writes). Then 8 one-line mutations, each reverted. The tree is clean except for this file.
- The user's rules: the DIDL is not touched (`PlayBody.kt`, `builtDidl` and `PlayBodies` aren't in the diff; the
  transport's lines only sit around the existing calls). No "Spotify" in a string (`SourceScanTest` green; the new
  strings are "Kobler til høyttaleren…" / "Connecting to the speaker…").

## Findings

**Q-1 (Medium) - the timeline isn't keyed per tap, so overlapping taps mix their lines.** `HandOverTiming.kt:34-70`
keeps one global timeline. `tap()` and a `(no tap)` `play()` call `begin()`, which resets `start`, `played` and `once`
for everyone. A kid who sees nothing happen taps again, and that's the very case this round measures. Then:
(a) the first play's actor job keeps running. A URL/NRK play's gate is `!closed && mayPlay()`, with no generation check
(`SonosController.kt:1098`), so a superseded job still runs to the end. Its transport lines (`the speaker resolved`,
`RemoveAllTracksFromQueue answered`, `Play answered`, `the play job done, a poll woken`) print as `+N ms` since the
**second** tap, with nothing to mark them as another play's. Line 9 ("the play job done") is one of the four lines the
design's recommendation compares. (b) When the second tap lands while the first is still waiting in the service (the
renderer gate, the switch lock), the first play's `play()` takes over the second tap's timeline (`played` is false
again), and the second play starts a `(no tap)` one. (c) The service's "the service runs it" line for the first tap
can print after the second tap's `tap:` line. Measurement only, nothing breaks, but the numbers this round exists to
produce can mislead. **Fix:** number the timelines and print the number on every line (`#3 +412 ms ...`). Let `play()`
return its timeline. The play job captures it, and at the job's start on the actor (serial: one job at a time) sets
`HandOverTiming.current` to it, so the transport's `step()`s print under the job's own number and time. The polls and
socket events keep going to the newest timeline. Allow a few open timelines, each closing after `WINDOW_MS`. **Test**
(`HandOverTest`): tap, play, tap, then a step "from the first job" prints under #1, timed from tap 1; the second play
continues tap 2.

**Q-2 (Medium) - `SonosController.connecting()` has no test: the rule that ends the line can be broken freely.**
`SonosController.kt:526-533`. `HandOverTest` only covers `timeRow`, which is a one-line AND of four flags. The rule the
kid sees is in `connecting()`: `pending` from the tap, the early hint's READY ending it, after the landing only
`ours && transitioning` within `SETTLE_MS` of the answer, `playWhenReady`, a failed or dropped play ending it. That rule
has no test. Mutation M3 (the line kept for 8 s after the answer whatever the state, PLAYING included) survives the full
suite, and so does dropping the 8 s window. A smaller edge goes with it: `playAnsweredAt` is the **last** answered play's,
so after a failed step (pending cleared) while the snapshot still reads ours + TRANSITIONING within 8 s of the previous
answer, the line stays until that window ends. **Fix:** tests in `SonosControllerTest` with the existing
`SonosControllerRig`: a play queued -> true; answered, the landing poll PLAYING ours -> false; landing TRANSITIONING
ours -> true, then false once 8 s pass; the early hint applied -> false; `failed` -> false; `dropped` -> false; a pause
pressed while it settles -> false; no session -> false.

**Q-3 (Medium) - the wait the user describes is probably not one the line covers.** The design's analysis is right that a
Spotify tile on a room can't have waited. `speakerDropReason` returns `"spotify"` (`PlayBody.kt:160`), `queueFor` emits
`CANT_PLAY_ON_SONOS` at once and the renderer stays (`PlaybackService.kt:841-847`), and `playShare` has no caller outside
the transport. But the user's words name a Spotify playlist and the spike's `--spotify`, so the likeliest remaining case
is the phone's web player starting cold: the page, the SDK, the token through the launcher, the Connect device,
`PUT /me/player/play`. There, `timeRow` gives `onRoom = false` -> TIMES. The web player has no "starting" line at all
(its `RendererState`s are failures only), so for that case nothing changed for the kid. PLAN says **found**, but this is
really an open question for the user. **Fix:** ask the user which play it was (a room's podcast/NRK/Storytel, or Spotify
on the phone), and say so in PLAN and in design 54 rather than "found". If it was the web player: the same quiet line
for its BUFFERING-with-`playWhenReady` before its first PLAYING. It needs its own words, because "høyttaleren" is wrong on
the phone ("Starter…" / "Starting…" is one option, the user's call). It also needs `VibbHandover`-style lines for its
steps, measured against `fake-spotify.py` (§6l).

**Q-4 (Low) - `SplashTest` doesn't pin the cap's value.** `SplashTest.kt:11-12` tests against `SPLASH_CAP_MS` itself, so
M1 (`30_000L`) survives. The value matters. A window that never draws while a key event waits for it is an input ANR
("no focused window") after 5 s, and the shell's transition timeout is about 5 s too. 3 s stays safely below both.
**Fix:** `assertTrue("below the 5 s input ANR", SPLASH_CAP_MS in 1_000L..4_000L)`, with the reason as a comment by the
constant.

**Q-5 (Low) - nothing guards the timing lines' privacy rule.** The lines log in release too (`SonosHome.handOver` ->
`Log.i`), and the rule is "names, kinds, counts, states, positions - never an id, a uri, a title". M4 (`${item.title}` in
the "answer on main" line) survives. Every line in the diff complies today (checked one by one: action names, `PlayWhy`,
`Kind.wire`, the exception's simple name, transport and socket states, counts, queue positions, the service number).
**Fix:** a scan in `SourceScanTest`'s manner: every `HandOverTiming.(tap|play|step|first|until)(` argument in `src/main`
interpolates none of `title`, `name`, `uri`, `url`, `key`, `id`, `token`, `link`, `message`.

**Q-6 (Low) - the splash hold's Android glue is only checked on the emulator.** M5 (`onPreDraw` always false: the app
never draws, the cap is never reached) and M7 (`onContent` never called: every launch held to the 3 s cap) survive, as
expected for glue. M7's failure mode is quiet: the splash simply lasts 3 s on every launch. **Fix:** §6u step 1 must
time it, not just look for the dark frame (below). Optionally add one `VibbSplash` line when the hold lets go
(`released after N ms: content|cap`), so a cap release shows in logcat.

**Q-7 (Low) - NOTICE.md doesn't name the music app's splash.** `splash_vibb_mark.xml` says "Vibb mark (c) palchrb,
NOTICE.md", but NOTICE.md's Vibb-mark paragraph lists only the launcher's `ic_launcher_foreground.xml` and
`splash_vibb_breathe.xml` and the PWA's start-up images. **Fix:** add "and the music app's launch splash
(`music/app/src/main/res/drawable/splash_vibb_mark.xml`, `splash_wordmark.xml`, drawn by `scripts/music-splash.py`)". The
wordmark is Nunito's outlines as filled paths. That's artwork made with the font, which the OFL permits and doesn't
cover (OFL FAQ: documents and artwork made with the font aren't Font Software). The existing OFL paragraph and
`assets/licenses/OFL.txt` are enough, and one clause in NOTICE saying so would settle it.

**Q-8 (Low) - `music-splash.py --selftest` isn't in CI.** The drawable copies the launcher's `splash_vibb_breathe.xml`
rings (regenerated by `launcher/scripts/vibb-icon.py`). If that file or the font changes, the music app's copy goes stale
silently. `music.yml` already runs the other scripts' selftests. **Fix:** run it in `music.yml`'s build job, add
`scripts/music-splash.py` and `launcher/app/src/main/res/drawable/splash_vibb_breathe.xml` to its path filter, and the
line to root CLAUDE.md's CI paragraph. The script is plain Python 3 with nothing to install, and deterministic (no
randomness, fixed order, `--selftest` byte-compares), so it fits there.

**Q-9 (Low) - a swipe and a play before the queue loaded don't start a timeline.** `PlaybackService.kt:635`: `roomPlay`
is `Commands.PLAYS - ROOM_JOIN`, so `SKIP_TO` (a carousel swipe) and `RESTORE` (next/previous/seek before a play after a
restart) start only at the controller's `(no tap)` `play()`. The service's wait (the renderer gate, the switch lock),
line 1 of the step list, goes unmeasured for them, though the UI's line covers them (`MOVES`). **Fix:** `roomPlay` over
`Commands.PLAYS + Commands.MOVES - ROOM_JOIN`.

**Q-10 (Low) - the carousel may visibly move right after the splash.** The hold lets go at the first frame with the
snapshot, but `vm.startOn` needs the now-playing row too (`MusicRoot.kt:77-82`). When `nowRow` arrives a frame later, the
first library frame shows tile 0 and then jumps to the last played tile, the kind of first-second jump the hold is meant
to hide. This isn't a regression (it jumped before, behind the dark gap). **Fix:** check it on the emulator (below). If
it shows, hold also until the row's flow has emitted once: a loaded flag beside the `stateIn`'s `null`, because "no row"
is a real value too.

**Q-11 (Nit) - eager strings, release logging.** The callers build each step's text before `step()` knows whether a
timeline is open: `pollTick`'s settle line, and every socket event's `until`. That's one short string per poll or event,
which is negligible. A `step { ... }` lambda overload would remove it. Logging in release is acceptable:
`SonosHome.log` already logs at `Log.i` in release (with titles, `sonos: playing [..] ${item.title}`, from before this
round), and a timeline closes 30 s after its start. `HandOverTiming.tap` runs before the unknown-command check, which is
harmless because `PLAYS` ⊂ `ALL`.

**Q-12 (Nit) - the line ends by time only at the next flush.** `connecting` is computed in `SonosRenderer.flush` and
`PlaybackService.publish`, which run on events and polls. When the 8 s window or freshness runs out, the line goes at
the next poll: up to 5 s late while TRANSITIONING. That's bounded and harmless, and no change is needed. A sentence in
design 54's "The player's line" would keep a reader from looking for a timer.

## The splash hold - verdict: sound

`MainActivity.holdSplash` is what `androidx.core.splashscreen`'s `Impl31.setKeepOnScreenCondition` does: an
`OnPreDrawListener` on `android.R.id.content` that returns false while the condition holds and removes itself when it
lets go. On API 31+ the system's starting window (the splash) stays until the app's window reports its first drawn frame.
There is no system-side splash timer. `windowSplashScreenAnimationDuration` only matters for an animated vector, and
`splash_vibb_mark` is static. So the splash doesn't end on its own, and holding the draw keeps it.

The user's 0.9 s dark gap was the app's own first frame. `MusicRoot` drew the empty `Box` (`Palette.bg`) while `snap ==
null` (the `stateIn`'s initial value before the Room flow's first emission). That frame ended the splash. With the hold,
the sequence is:

1. The initial composition at attach, in the first traversal: `snap == null`, no `SideEffect`.
2. `onPreDraw` returns false. ViewRootImpl cancels the draw and schedules the next traversal, so the condition is read
   every vsync.
3. The snapshot arrives and the recomposition runs in that frame's Choreographer callback. Its `SideEffect` sets
   `contentShown`, the same frame's traversal passes `onPreDraw`, and the first drawn frame has the library.

Other points checked:

- Nothing blocks the main thread, so there's no ANR risk under the 3 s cap (Q-4).
- A recreate (font, theme): the ViewModel and its `stateIn` survive, the initial composition at attach already has the
  snapshot, and the `SideEffect` runs before the first `onPreDraw`, so there is no hold.
- A warm start (activity finished, process alive) holds like a cold one, briefly.
- A restore after process death holds as a cold start.
- `onStart`'s `LiveBridge.acquire`, the `MediaController` connection, `open()`'s `RESUME` and
  `onTopResumedActivityChanged` don't wait for a drawn frame, so the launcher-bridge start path is unaffected.
- The splash style: since API 33 the icon shows for launches from the HOME app, SystemUI or the system. Any start from
  Vibb One's uid counts as HOME (its tiles, the Home card's `EXTRA_RESUME`). A shell `am start` gets the solid-colour
  splash, as §6u already warns.
- The icon geometry fits the platform's rules: the outer ring is about 85 dp in radius with its stroke, so about 170 dp
  across, inside the 192 dp circle of a 288 dp icon without a background. The wordmark's ink (143 x 22 dp) sits in the
  200 x 80 dp branding box, and the script asserts the margins.

## Design 54's analysis - verdict: both claims hold

- **"54-a2 was never built - `speakerDropReason` returns \"spotify\"": true.** The code: `PlayBody.kt:160`
  `if (entry.source == Source.SPOTIFY) return "spotify"`, and `queueFor` turns it into `CANT_PLAY_ON_SONOS` with the
  renderer kept. `SonosTransport.playShare` has no caller in `src/main` outside `SonosTransport.kt`, only its tests. The
  history: `git log --all --oneline --grep=54-a2` finds only this branch's `095eeb1a` and the 54-a1 fix round's mention
  (`6592849b`). There's no 54-a2 branch, and PLAN (lines 1309, 2235) still lists 54-a2 as next.
- **The 5 s poll claim: true.** `SonosSession.cadenceMs` returns `POLL_MS` (5 000) whenever `snap.transitioning`, or
  within `VERB_FAST_MS` of a verb. The 1 s `precise` poll needs reachable, ours, `transport == PLAYING`, fresh, the player
  shown and no healthy socket. TRANSITIONING keeps the last real state, which is not PLAYING at a cold start, so a cold
  play settles at 5 s. The spike's `upnp_watch` polls on a 1.0 s period from before the transfer
  (`sonos-play-test.py:381-395`). The socket's wake is at most once per `EVENT_WAKE_MIN_MS` (1 s), and the socket opens
  only once the session is armed (`socketWanted(session.armed, ...)`). So recommendation (a), 1 s polls while our play
  settles or transitions, is the right first cut, once the lines confirm it.

## Mutations

| # | Mutation | Result |
|---|----------|--------|
| M1 | `SPLASH_CAP_MS` 3 000 -> 30 000 | NOT CAUGHT (Q-4) |
| M2 | `timeRow` without `onRoom` (the line on the phone path) | caught (`HandOverTest`, the timeRow test) |
| M3 | `connecting()` without the BUFFERING phase and `transitioning` checks (the line kept after PLAYING, 8 s from the answer) | NOT CAUGHT (Q-2) |
| M4 | `${item.title}` in the "answer on main" timing line | NOT CAUGHT (Q-5) |
| M5 | `onPreDraw` always false (the hold never released) | NOT CAUGHT (glue, emulator; Q-6) |
| M6 | `keepSplash` ignores `contentShown` (true after the first frame) | caught (`SplashTest`) |
| M7 | the `SideEffect` never calls `onContent` (every launch held to the cap) | NOT CAUGHT (glue, emulator; Q-6) |
| M8 | `HandOverTiming.play` never continues the tap's timeline | caught (`HandOverTest`, the timing test) |

3 of 8 caught. M1, M3 and M4 are JVM-testable (Q-4, Q-2, Q-5). M5 and M7 are Android glue for the emulator. M1, M3, M4,
M5 and M7 were applied together and the full suite stayed green (1210/0). M2, M6 and M8 were applied together, and each
failed exactly its own test (3 of 3 failed).

## Suite

`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug testDebugUnitTest` in
`music/`: BUILD SUCCESSFUL, 1210 tests, 0 failures, 0 skipped. `scripts/music-splash.py --selftest`: ok.

## Verdict

**Mergeable after the fix round.** Nothing is wrong for the kid. The splash hold is the platform's mechanism, correctly
wired, and the line's logic leaves the hand-over itself unchanged: the diff only adds `HandOverTiming` calls,
`playAnsweredAt` and `connecting()`. The `Health`/`earlyHint` restructures keep their behaviour. The fixes are about the
round's own purpose:

- Q-1: the timing must survive a double tap.
- Q-2: the line's end must be tested.
- Q-3: the user should be asked which wait they saw before PLAN calls it found.

Q-4 to Q-10 are cheap and belong in the same round.

## Emulator checks to add (§6u)

1. **Splash, timed** (step 1): count the frames from the tap to the first content frame. With a normal library the
   splash must end well **before** 3 s (a release at about 3 s every time means the content signal never fired, M7). No
   frame of plain night between the splash's fade and the tiles. The system's exit fade only, and the icon at rest for the
   whole hold (no fade or shrink before the exit).
2. **The start tile:** with a stored now-playing row, the library's first frame already shows the last played tile, with
   no jump right after the splash (Q-10).
3. **Recreate and warm start:** change the font scale with the app open (no blank flash, no 3 s freeze). Press back,
   then open it from the tile again (the splash, then content). Open it from the media notification (SystemUI source:
   the icon splash). The Home card's resume (`EXTRA_RESUME`): the splash, then the player, and the room or phone starts
   playing as before.
4. **A double tap on a room** (`fake-sonos.py --transition-s 4`): tap a tile, and tap another before the first plays.
   After Q-1, the lines carry two numbers and each play's `Play answered` and `the play job done` sit under its own. The
   line shows until the **second** play reaches PLAYING.
5. **TalkBack** on the player while connecting: swiping past the seek bar reads "Kobler til høyttaleren…" in the times'
   place. It isn't announced by itself, which matches the room's other lines (none is a live region).
6. **Spotify on the phone, cold** (`fake-spotify.py`, §6l): note what the player shows between the tap and the sound.
   This is the input for Q-3's question to the user.

## Fix round (2026-10-25, branch `music-r13`)

All findings taken, with the coordinator's decisions. Code in `ac28d4d0`.

- **Q-1** - Fixed in `ac28d4d0`. `HandOverTiming` numbers its timelines (`#n +N ms`). `tap()` returns its timeline,
  the service marks the one whose command it runs (`running`/`finished`, under the switch lock), and `play()`
  continues only that tap, else starts a `(no tap)` one, and returns it. The play job `enter`s its timeline on the
  actor, so the transport's steps print under it until `done`. The answer, failure and drop lines on main carry it
  explicitly, and a superseded job's answer says so. The polls and socket events go to the newest timeline.
  `HandOverTest`: tap, play, a second tap, the first job's steps under #1 timed from tap 1, the second play continuing
  tap 2.
- **Q-2** - Fixed in `ac28d4d0`. Three `SonosControllerTest` cases through `ControllerRig`, reading the line as the
  renderer's flush does (`shown`, then `connecting`):
  - covered: queued, answered, landed PLAYING, landed TRANSITIONING inside and outside the 8 s window, the early
    hint, a failed start, a failed step, a dropped play, a pause while settling, and no session;
  - the edge: `failed()` and `dropped()` clear `playAnsweredAt`, so an earlier answer never keeps the line after a
    failed step.
- **Q-3** - Fixed in `ac28d4d0` (the coordinator's decision). The phone's web player shows "Kobler til…" /
  "Connecting…" in the time row from the tap until its first playing state:
  - `SpotifyRenderer.starting`: the start's calls or the tracker's STARTING, `pwr`, no line;
  - published through `PlayerHub.State.connecting`;
  - `timeRow` gains `CONNECTING` (phone) beside `CONNECTING_SPEAKER` (room);
  - the existing failure line wins; the no-"Spotify" rule stands;
  - `HandOverTest` covers the mapping, PLAN's line is as decided, and design 54 is updated.
- **Q-4** - Fixed in `ac28d4d0`: `SPLASH_CAP_MS in 1_000L..4_000L` in `SplashTest`, the reason by the constant.
- **Q-5** - Fixed in `ac28d4d0`: `SourceScanTest` `r13 - the hand-over timing lines never format a title, url, id or
  token`. Every `HandOverTiming.(tap|play|step|done|first|until)(` call's interpolations are checked against `title`,
  `name`, `uri`, `url`, `key`, `id`, `token`, `link`, `message` and the camel-case suffixes `…Title`, `…Uri`, `…Id` and
  so on. At least 20 calls are found.
- **Q-6** - An emulator check, as decided: emulator.md §6u step 6 times the splash (a release at about 3 s every time
  is M7). No log line added.
- **Q-7** - Fixed in `ac28d4d0`: NOTICE.md's Vibb-mark paragraph names the music app's splash drawables and says the
  wordmark is artwork made with Nunito (OFL permits it; not Font Software).
- **Q-8** - Fixed in `ac28d4d0`:
  - `music.yml` runs `python3 -I ../scripts/music-splash.py --selftest` after the other selftests;
  - its changes filter has `scripts/music-splash.py` and the launcher's `splash_vibb_breathe.xml`;
  - the root CLAUDE.md CI paragraph says so.
- **Q-9** - Fixed in `ac28d4d0`: `roomPlay` is `toRoom || (PLAYS + MOVES - ROOM_JOIN) on a room`, so `SKIP_TO` and
  `RESTORE` start a timeline too.
- **Q-10** - Fixed in `ac28d4d0`. The carousel could jump: `startOn` needs the row, and `nowRow` may come a frame
  after the snapshot.
  - `MusicRoot` composes nothing over the night ground until `contentReady` holds: the snapshot, the row read
    (`MusicViewModel.nowRowKnown`, since "no row" is an answer too) and, with a stored row, `startOn` done
    (`startSettled`), or the 3 s cap;
  - once shown it is never hidden again;
  - the Library then composes with its pager already on the last played tile, so there is no scroll at all;
  - `SplashTest` covers `contentReady`; emulator.md §6u step 7 checks it on the emulator.
- **Q-11** - No change (the QA's own verdict: negligible, and release logging is acceptable).
- **Q-12** - Design 54's "The player's line" now says that the line ends at the next flush or poll, up to 5 s late
  while TRANSITIONING, and that it has no timer of its own.
- **The QA's six emulator checks** are emulator.md §6u steps 6-11.

**Mutations re-run after the round**, each reverted:

| Mutation | Result |
|----------|--------|
| M1 (`SPLASH_CAP_MS` 30 000) | caught by `SplashTest` |
| M3 (`connecting()` without the phase and `transitioning` checks) | caught by `r13 the connecting line - from the play until the room reports PLAYING` and the early-hint case |
| M4 (`${item.title}` in a timing line) | caught by the new `SourceScanTest` scan |
| The 8 s window dropped | caught by the TRANSITIONING case |
| `failed()` without the `playAnsweredAt` clear | caught by the failed-step case |

**Suite**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` passes, 1217 tests, 0 failures.
`scripts/music-splash.py --selftest`: ok.
