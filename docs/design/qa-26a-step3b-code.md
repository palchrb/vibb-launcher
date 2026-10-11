# QA 26a step 3b code review - lifecycle and the phone (2026-10-11)

Scope: branch `music-1b-sonos`, 9e1da9e..7650890 (`7f11626` adopt, the suspension's stop, the full stop, wake locks;
`b9f907c` the volume cap; `9bf0487` calls; `7650890` the status). Read against design 26a (§4.7, §5.3, §6.1-§6.3,
§6.7, §9, the 3b status). QA built both apps: music 346/0, launcher 839/0 (baseline 830 confirmed), contracts
byte-identical, launcher scope clean (only `L/music/*`). Line numbers are 7650890's.

Verdict: fix round needed. Counts: 1 High, 1 Medium, 8 Low. Decisions from the main session: H1 -> option (a); M1 ->
fix it, not a documented limit.

## High

1. **H1: a call from the launcher cancels the kid's sleep timer, and the music resumes without it (local and
   Sonos).** `MusicCalls.kt:66-67` paused through `transportControls.pause()`; Media3 makes it `player.pause()` -> on
   the phone `PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST` -> `PauseCause.USER` -> `PlaybackService.kt:787` ends the
   timer; on a room `handleSetPlayWhenReady(false)` -> `controller.pause()` -> USER -> the same. A 30-minute timer, an
   unanswered Element X ring: the timer is gone, the ring ends, PLAY, the music plays on to the queue's end. Before 3b
   a system call paused through transient focus and kept the timer: a regression for local playback too. And a timer
   that ends during the hold is followed by the launcher's PLAY. Fix (a): CALL_HOLD / CALL_RELEASE custom session
   actions granted to the launcher; the service pauses under `selfPausing`, remembers, and resumes only if it paused
   and the timer didn't end; the launcher's step shrinks to HOLD/RELEASE. Tests both sides + a GuardsTest pin.

## Medium

1. **M1: a transient failure orphans a speaker still playing our stream.** `PlaybackService.kt:1084-1089` (adopt),
   `SonosSuspension.kt:36`, adopt's queue rebuild (status choice 2): renderer.json went to the phone on every
   non-adoptable outcome - no Wi-Fi within 1 s, a slow classify, an empty stored uri (a crash after SetURI+Play but
   before `host.played`'s write), "ours but the item left the library" - and the standalone suspension wrote the phone
   even when the speaker never answered (choice 5). Then nothing ever stops that stream (§6.2), and Home's RESUME
   starts Exo under it (§4.7's trap). vibb keeps `renderer = sonos` on a transport failure. Fix: the phone only on a
   definite answer; no answer keeps the room recorded and adopt / the suspension retry; "ours but no index" -> an
   ours-gated Stop or the marker; the same in the standalone path. Tests per outcome.

## Low

1. **L1**: the destroy tail kept driving the dead service (`:228-268`): `onEvent` never cleared, so polls during the
   tail published and reported "playing" over the forced-paused report (install deferral up to 6 h). Clear it and
   `saveNow(force = true)` first.
2. **L2**: the tail and a new process run in parallel (`:236`): a suspension during the tail takes the standalone path
   (a second transport and renderer.json writer); the tail's plain Stop first makes the standalone read STOPPED and
   skip the park; a new service can adopt a speaker the tail is about to stop. Fix: a process-wide tail Job the
   receiver and adopt join, bounded.
3. **L3**: the wake lock's renewal re-acquired every 5 min while `held`, so a missed `hold(false)` kept the CPU awake
   for the process's life (choice 8 didn't hold). Renew only when re-asserted since the last renewal.
4. **L4**: `release()` called `onArmedPlaying(false)` on the service-wide holder unconditionally - a release after the
   new room armed would drop its locks. Drop it, or a holder per owner.
5. **L5**: `WIFI_MODE_FULL_LOW_LATENCY` is effective only in the foreground with the screen on (HIGH_PERF is a no-op on
   API 34): the Wi-Fi lock does nothing with the screen off; the partial wake lock and the FGS's network access keep
   the polls. Keep the lock, correct §6.3 and the status, reword the device check.
6. **L6**: the standalone bookmark was a third persistence path (`SonosSuspension.kt:47-60`: direct upserts, no
   shown duration, no Resumable, outside the `persistPlace(` pin). Extract one shared write and pin it.
7. **L7**: the backstop resumed through `playTile` (a settling resume falls to a full SetURI) while the launcher's
   play went through `playPause`. Resume through the session's entry point; with H1 both use the service's step.
8. **L8**: the startup gate, resumption on a room and `adoptedQueue` were pinned by source text only; add behavioural
   tests where the pure parts allow.

QA's deviation verdicts: accept 1, 3, 4, 6, 7, 9, 10, 11, 12, 13, 14; 2 and 5 are M1; 8 is L3. Also: lint reports 40
warnings, not 41 (harmless; correct the doc).

## Fix round (the implementer, 2026-10-11)

Every finding fixed, none skipped. `2d0bef4` (H1, L7; launcher + music + both contract copies) and `9161bbd` (M1,
L1-L4, L6, L8; music). Music 346 -> 348 -> 355 tests, 0 failures, lint 0 errors / 40 warnings; launcher 839 -> 837,
0 failures; `fake-sonos.py --selftest` OK.
- **H1**: `Commands.CALL_HOLD` / `CALL_RELEASE` (`me.vibb.music.CALL_HOLD` / `CALL_RELEASE`, `bridge_contract.json`
  `call_commands`, both contract tests), granted in `onConnect` only to `LAUNCHER_PACKAGES` signed like the app
  (`Bridge.sameSigner`), outside `PLAYS`. The service's `callStep()` takes the launcher's hold for every renderer and
  the audio mode only on a room; the pause is `renderer.pause()` under `selfPausing`; `sleepEnded()` marks
  `callTimerEnded`; the release plays only when we paused, the timer didn't end, it is still paused and
  `playAllowed`. Launcher: `callHoldStep` -> HOLD/RELEASE, `MusicCalls` sends `sendCustomAction` (never a plain
  pause or play, pinned). Tests: `CallPauseTest` +1 (the timer ended during the hold), `GuardsTest` +1 (a call never
  cancels the timer, a release never plays after it ended; the grant), `BridgeTest`/`MusicPlanTest` the contract,
  `MusicMediaPlanTest` rewritten (4).
- **M1**: `roomVerdict` (OURS_LIVE / OURS_STOPPED / NOT_OURS / NO_ANSWER); `lookAtRoom` writes the phone only on a
  definite answer, keeps the room on NO_ANSWER, sends `stopUnadopted()` for ours without an index; the look runs at
  start, before a play on the phone (`lookAgainBeforePlaying`, at most every 15 s) and from `suspendStop` with the
  phone rendering (the standalone path); `adoptLive(keep: (Fields) -> String?)` goes on with the speaker's uri when
  none was stored and the speaker plays an item of the stored entry (`ofEntry`); `SonosSuspension` keeps the room on
  no Wi-Fi / no answer and bookmarks the matched item. Tests: the verdict table, no stored uri (suspension and adopt).
- **L1**: `sonos.onEvent = null` and `sonos.saveNow(force = true)` before the tail (pinned).
- **L2**: `SonosTail` (process-wide Job): `lookAtRoom` joins it (16 s at most), `SonosSuspension.standalone` (5 s);
  OURS_STOPPED is parked by the suspension (test), so the tail's plain Stop no longer leaves the Play button replaying.
- **L3**: `SonosLocks` renews only when `seen` (an owner re-asserted since the last renewal), else releases;
  `SonosController.lockFor` reports true on every refresh, false once (test).
- **L4**: `SonosLocks.hold(owner, want)`; each renderer passes its own owner; `release()` no longer calls the hook.
- **L5**: §6.3, the status and the device check corrected (above).
- **L6**: `place()` + `writePlace()` in `LocalRenderer.kt`; `persistPlace` and `SonosSuspension.bookmark` both call
  `writePlace`; `GuardsTest` pins the two callers and no other file upserts a position or bookmark.
- **L7**: the call resume is `r.player.play()` (pinned).
- **L8**: `RendererGate` + `startupLook` (`RendererGateTest`: a RESUME before the look waits for it), `adoptPlan`
  (pure, `SonosLifecycleTest`: the stored order and shuffle, the filter, the index, the item gone), the verdicts.
  Resumption on a room stays a source pin (it is `resumeLast()` on the room's renderer, whose start is tested).
