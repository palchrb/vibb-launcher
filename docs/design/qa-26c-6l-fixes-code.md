# QA (code): the fixes after the emulator check §6l A (Spotify, design 26c) - 2026-10-14

Reviewed: `spotify-6l-fixes` at `2c37338d`, i.e. `git diff 22980243 2c37338d`: the report merge `1345b58a`; `6d2dfbe2`
(the fake: F4, F10); `c1c8fb90` (PWA: F5); `a8568903` (music: F1-F3, F7); and the docs commit `2c37338d`. I read them
against `docs/testing/2026-10-14-spotify-6l-a.md`, 26c §5.6 and its new "Fixes after the emulator check §6l A",
`26c-handover.md` §5, `music/CLAUDE.md` (Spotify, calls, audio focus), `server/CLAUDE.md`'s Spotify section and
emulator.md §6l.

## Summary and verdict

**1 High, 1 Medium, 2 Low, 3 Nits. Not ready for the §6l re-run as it stands: fix H1 and M1 first.** Both are small
and both are in F2's new focus model.

- **F1, F3, F4, F5, F7, F8, F10 and the doc-only answers (F6, F9) are correct and minimal.**
  - The F1 test truly reproduces the device path. Unqualifying `play()` or `seekTo(i, 0)` turns the Player-API tests red
    (M1, M1b).
  - F3's gate and `STAYED` are pinned.
  - The contract files, the kid's strings (`res/`) and the launcher are untouched.
  - No new log line and no secret anywhere.
  - No "Spotify" reaches the kid.
- **F2 is the right decision.** The renderer requests no focus and reads Chromium's pauses and resumes from the page.
  The text scan pins "no focus request".
- **F2 lost two things the old `suppressed` state did for the kid.**
  - **(H1) A pause we send while Chromium holds the page suspended is lost.** That covers the kid's pause, the sleep
    timer's end, and a time-rule pause during an alarm or a call. Chromium's resume then plays the page's audio while
    the player shows paused.
  - **(M1) The intent across a long transient loss is gone.** After 5 minutes (a call), the idle timer destroys the
    page, so nothing comes back. The call rule doesn't step in either when Chromium paused before `CALL_HOLD` arrived.

## Runs (this worktree, `2c37338d`)

The full run was under `~/build.lock`, with `CARGO_TARGET_DIR=~/.cache/handy-target/qa-sp-fix`.

- **music:** `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug --rerun
  -PwarningsAsErrors=true`: **BUILD SUCCESSFUL**, **860 tests, 0 failed, 0 skipped**, lint clean.
- **server:**
  - `cargo fmt --check` is clean.
  - `cargo clippy --all-targets -- -D warnings` stops on **13**: the known 12 plus `chunks_exact`, which is main's 13,
    unchanged by this branch.
  - `cargo test` gives **949 passed, 0 failed**. Node is installed, so `jstest` ran inside it.
- `python3 -I scripts/fake-spotify.py --selftest`: all 7 groups OK, the consent page and its single use among them.
- **Contract files:** `git diff --stat 22980243 2c37338d -- server/testdata music/app/src/test/resources launcher
  music/app/src/main/res music/app/src/main/aidl` is empty.
- **Probes** (temporary tests appended to `SpotifyRendererTest`, then reverted): H1's and M1's evidence below.

### Mutations

Each mutation was reverted from a copy afterwards; `git status` is clean. The renderer suite is `SpotifyRendererTest`,
`GuardsTest` and `SpotifyRulesTest` (126 tests).

| # | Mutation | Tests | Result |
|---|---|---|---|
| M1 | `handleSetPlayWhenReady` calls unqualified `play()` | renderer suite | **red**: 3 (both Player-API tests, the scan) |
| M1b | `handleSeek` calls unqualified `seekTo(mediaItemIndex, 0)` | renderer suite | **red**: 2 |
| M2 | The renderer requests focus again (`requestAudioFocus(AudioFocusRequest…)` in `AndroidSpotifyPorts.watchNoisy`) | renderer suite | **red**: the "requests none" scan |
| M3 | `spotifyPauseCause(false)` = `OTHER` | renderer suite | **red**: 3 |
| M3b | The call site maps `ours` by hand (`USER`/`OTHER`) | renderer suite | **red**: 2 |
| M4 | `spotifySelfResumeShown` ignores `mayPlay` | renderer suite | **red**: 2 |
| M4b | The call site passes `mayPlay = true` (a self-resume during a time-rule lock) | renderer suite | **red**: 1 |
| M5 | `goTo` doesn't send `STAYED` | renderer suite | **red**: GuardsTest 26c 5_10 |
| M6 | `seekTo(index, ms)` without the network gate | renderer suite | **red**: `F3 - offline, a step …` |
| M7 | Nudge on every outcome, changed or not | renderer suite | **red**: GuardsTest (the site) |
| M7b | `Sync.nudge` without its debounce (`enqueue(app, 0, REPLACE)`) | all 860 | **survived** (Low 1) |
| M8 | The artist hint on every context entry (`spotify_artist = …is_some()`) | `cargo test music_spotify` | **red**: `the_entry_page_has_no_offline_and_in_context_mode_no_order` |
| M9 | `STAYED` shows a message (the `return@collect` removed) | renderer suite | **red**: GuardsTest step 6 |
| M10 | `val wanted = pwr \|\| true` (a self-start without the kid's intent) | renderer suite | **red**: 3 (H1/M1 of step 5a/6) |

13 of 14 killed. The survivor, M7b, is old code (`Sync.nudge`), but F7 now leans on its debounce.

## Findings

### High 1 - A pause sent while Chromium holds the page suspended is lost, and Chromium's resume then plays unseen

- **Where:**
  - `SpotifyRenderer.kt:1065` (`pause(cause)`): it sends `sdk("pause")` and `tracker.pauseSent()` even when our track is
    already paused by Chromium.
  - `SpotifyTracker.kt:571`: `if (pausePending) return emptyList()` swallows every later playing state.
  - `SpotifyRenderer.kt:1241-1255` (`Event.Resumed`) and `logic/Spotify.kt` (`spotifySelfResumeShown`): neither knows a
    pause was asked after the fault.
- **Scenario:** Spotify plays and an alarm rings, or a call's ringtone, or navigation speaks. Chromium's media session
  takes a transient loss and pauses the element; the renderer shows `Paused(FAULT)`.
  1. During that loss, something sends a pause: the kid taps pause, or **the sleep timer ends** (`fadeOutAndPause` ->
     `pause(USER)`), or the service pauses for the time rule.
  2. The element is already paused. Its `pause()` changes nothing and fires no `pause` event (the fake's stub: `Player.
     pause = this._audio.pause()`; the real SDK has no reason to differ). So no paused state follows and
     `pausePending` stays set.
  3. At the gain, Chromium resumes what it suspended. The page's audio plays and its playing states are swallowed by
     `pausePending`.
  4. Result: the audio plays while the player, the session, design 28's card and the PWA's state say paused. No locks
     are held, no gap timer runs, and nothing pauses it until the 5-minute idle timer destroys the page.
  - This is the "audio plays when it must not" path the brief asks about. The sleep timer is the kid's bedtime tool,
    and the UI lies about it.
  - The time rule's lock itself is safe: it suspends the app, which destroys the page.
- **Evidence (probe, reverted):**
  ```kotlin
  val r = playing(); post(state(0, 5_000))
  post(state(0, 6_000, paused = true))   // Chromium suspends
  r.pause(); settle()                    // the kid's pause / the sleep timer's end
  post(state(0, 6_000)); post(state(0, 8_000))   // Chromium resumes
  // -> showsPlaying=false, no vibb.pause() after the playing states, locks=false
  ```
- **Before F2:** a transient loss set `suppressed`; a pause during it cleared the intent, and the gain played nothing.
- **Fix:**
  1. Keep the kid's word across the suspension. In `pause(cause)`, when `!pwr` and the tracker already reads our track
     paused, don't call `tracker.pauseSent()`; set `pausedAfterFault = true` instead. Clear it at our own
     `resume()`/`request()`/`load()`/`detached()`.
  2. Give `spotifySelfResumeShown` that input (`…, askedPaused: Boolean`): false means not shown and `vibb.pause()` at
     once, the same branch as the time rule's.
  3. Pin it with the probe above, turned into a test: the playing state after the pause gives `vibb.pause()`, and
     `showsPlaying` stays false.
  4. Add one emulator.md §6l A1 line under "Another app's audio": "pause while the alarm rings, then Dismiss - it stays
     paused" (and the same with the sleep timer ending during the ring).
  5. The `pausePending` swallow itself stays right for its own case: a stale playing state from before our pause took
     effect.

### Medium 1 - A transient loss longer than 5 minutes destroys the page, and a call Chromium paused first is not the call rule's

- **Where:**
  - `SpotifyRenderer.kt:1573` (`syncHolds`: `if (playing || start != null) cancelIdle() else armIdle()`) and `:1492`
    (`armIdle`, `SPOTIFY_HOST_IDLE_MS` = 5 min).
  - `PlaybackService.kt:1296` (`callStep`: `val playing = r.showsPlaying`).
- **The idle timer:** Chromium's transient pause is now an ordinary `Paused(FAULT)`, so `pwr = false`. `syncHolds` then
  drops the locks and the noisy receiver and arms the 5-minute idle timer. A call that lasts longer than 5 minutes
  destroys the page (`spotify: idle for 5 min - the web player goes`), so Chromium's resume after the call has nothing
  left to play.
  - **Probe (reverted):** the page's paused state after a playing one, `advance(6 min)`: `page.alive == false`.
- **The call rule:** if the ringtone's focus loss reaches the page before the launcher's `CALL_HOLD` reaches the
  service (the two race at ring start), `callStep` sees `showsPlaying == false`. `callPauseStep` then records no
  `pausedByCall`, and `CALL_RELEASE` plays nothing. So after a call longer than 5 minutes the kid's music doesn't come
  back. Local playback does come back (design 26a §6.7 and the user's rule of 2026-10-11: "the music pauses on a call
  and resumes afterwards").
  - Before F2, `suppressed` kept the intent with no idle timer, and the gain replayed even with the page gone
    (`ports.mayPlay() -> request(...)`).
  - emulator.md's new "A call" text ("either way one tone") hides the race. §6l's `gsm call` is short.
- **Also during the pause:** the service drops its foreground state (play-when-ready false), which it kept before. On a
  long call, the process can be reclaimed; that ends the same way.
- **Fix:**
  1. Have the renderer remember a pause that isn't ours (`heldByWebView`): set it at the `Paused(ours = false)` while
     `pwr`, and clear it at a resume (by itself or ours), a pause of ours (H1), `load`, `detached`, `suspended` and
     `release`.
  2. While it is held, the idle timer is not armed (or is armed with a long bound, such as the 2.5 h token horizon).
  3. Expose it (`val heldByWebView`), and let `callStep` count it as playing for a Spotify renderer (`playing =
     r.showsPlaying || (r as? SpotifyRenderer)?.heldByWebView == true`). `CALL_RELEASE` then plays through
     `player.play()` when Chromium hasn't already. If Chromium did, `showsPlaying` is true and `callPauseStep` does
     nothing.
  4. Tests:
     - the probe, turned into a test (the page alive after 6 minutes held, and gone 5 minutes after a pause of ours);
     - a `GuardsTest`/service case for the call order "Chromium's pause, then `CALL_HOLD`, then `CALL_RELEASE`" ->
       play.
  5. emulator.md §6l A1 "A call": one call of more than 5 minutes; logcat must show which came first (`CALL_HOLD` or
     the page's pause).

### Low 1 - F7 runs a whole music sync to rewrite one word, and the nudge's debounce is unpinned

- **Where:** `PlaybackService.kt:808-810`; `sync/Sync.kt:78-89` (`nudge`).
- **What it does:** a changed account word enqueues the full `SyncWorker` (catalog, listings, downloads plan, art)
  15 s later. All the fix needs is `Sync.writeReport`, which writes `report.json` and hands it to the launcher
  (`BridgeSync.push`), as the download job already calls it (`Downloads.kt:157,174`).
- **Load:** the words are written only on a change, so there is no storm. But on a flaky network, `ok` <->
  `unreachable` alternates with the kid's starts, and each change costs a full sync. KEEP within the 15 s settle bounds
  it, but mutation M7b (no settle, REPLACE) survived the whole suite.
- **The launcher unreachable** is handled either way: `writeReport` logs a warning and goes on, and the next launcher
  sync reads the file.
- **Fix:** `scope.launch(writer) { if (store.recordSpotifyOutcome(…)) Sync.writeReport(this@PlaybackService) }`. If the
  sync is wanted, keep it and add a `SyncTest` pinning the settle and the KEEP/APPEND policy.

### Low 2 - A play during a call no longer stops at our focus request (device check)

- **Before:** `acquireFocus()` failed in a call (`requestFocus` false), so "no audio focus - not started".
- **Now:** a play the kid makes during a call (the call rule allows it: "a play the kid makes forgets it") sends the
  Web API play. "Vibb" becomes the account's active device. Chromium's focus request is then refused by the call, and
  the element doesn't sound.
- **Open question:** what the renderer shows next is not covered by any test: a `Paused(FAULT)`, or the 10 s
  first-state check with "Trenger internett" (`SpotifyProblem.UNREACHABLE`) if the page reports no playing state of
  ours.
- **Fix:** an emulator.md §6l A1 line ("during `gsm call`, tap play: no tone, the player shows paused with no line;
  after `gsm cancel`, one tap plays"). If it shows the line, gate starts on the launcher's call state (`launcherInCall`)
  in `start()`/`resumeLast()` for Spotify.

### Nit 1 - The KDoc names tests that don't exist

`SpotifyRenderer.kt:1650` says "`SpotifyPlayerApiTest`, and the scan in `GuardsTest`". Both the Player-API tests and
the scan are in `SpotifyRendererTest` (`:1615`, `:1651`, `:1664`). Name them.

### Nit 2 - The renderer's belt refuses a step without `STAYED`

`seekTo(index, ms)`'s new gate (`SpotifyRenderer.kt:503`) calls `refuse(it)`, but only the service sends `STAYED`. If
the network changes between the service's gate and the renderer, the screens hold the target for the full 3 s
(`SWITCH_HOLD_MS`). This is rare, and it is only a display. Either accept it (say so in the comment) or have `refused`
paths emit `STAYED` through `onEvent`.

### Nit 3 - The test-only framework stand-ins are global to the module's tests (acceptable)

- `music/app/src/test/java/android/{os/Looper,util/Pair,util/SparseBooleanArray}.java` shadow the mockable
  `android.jar` for every JVM test in `music/`, not just `SpotifyRendererTest`.
- **Today this is safe:**
  - the 860 tests pass;
  - `Looper`'s statics still answer null as the mockable jar does;
  - `Pair` and `SparseBooleanArray` now behave as the platform does, which can only make other tests truer;
  - they never reach an APK.
- **If the classpath order ever changes** (an AGP or Gradle change puts the android jar first), the session tests fail
  loudly (`NoSuchMethodError: forCurrentThread`) and mask nothing.
- **No smaller alternative:** `isReturnDefaultValues` is already on and is what makes `Looper.getThread()` null.
  Robolectric isn't in the project, and Mockito would be a new verified dependency. `SimpleBasePlayer` needs a Looper
  whose thread is the caller's.
- **Suggestion:** put one line in `music/CLAUDE.md`'s test notes saying a new stand-in under `src/test/java/android/`
  needs a reason. Nothing to fix.

## What the §6l re-run should add (after H1 and M1)

- **A1 "Another app's audio":** pause while the alarm rings, then Dismiss -> it stays paused. Let the sleep timer end
  during the ring -> it stays paused.
- **A1 "A call":** one call longer than 5 minutes -> it plays on afterwards. Log which came first, `CALL_HOLD` or the
  page's pause. During the call, tap play (Low 2).
