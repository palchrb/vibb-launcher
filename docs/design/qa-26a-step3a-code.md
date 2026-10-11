# QA 26a step 3a code review - the Sonos renderer (2026-10-11)

Scope: branch `music-1b-sonos`, 73bfdfb..7645958 (`833e8f8` the pure controller, `cd1fedf` the renderer, `5512865`
the wiring and the UI, `7645958` the status). Read against design 26a (§0, §3-§6, §9, the step 1-2 status and their
fix rounds) and palchrb/vibb `pipewire`. QA built and ran it: 298 tests, 0 failures, lint clean. Line numbers are
7645958's.

Verdict: fix round needed. Counts: 2 High, 3 Medium, 6 Low.

## High

1. **H1: a skip on a paused Sonos session starts the speaker with no `playAllowed` check.** `SonosController.kt:533-555`
   (`stepTo` -> `playIndex` whenever `session`), reached from `PlaybackService.kt:269` (KidPlayer's
   `seekToNext/Previous` -> `skipNext`/`goTo` -> `renderer.seekTo`) and `SonosRenderer.kt:302-308`
   (`COMMAND_SEEK_TO_MEDIA_ITEM` -> `stepTo`). A time rule fires, the launcher pauses and suspends the app; a
   headset/AVRCP "next", the notification or the lock view's next then plays the next episode on the speaker. Probe:
   PAUSED then `stepTo(1)` gives `[seturi, play]`. On the phone a skip while paused stays paused. Fix: while the
   effective transport isn't PLAYING (or at least while `!playAllowed`) a step only moves the index; the next play
   restarts. Controller test and a `GuardsTest` pin.
2. **H2: a failed or superseded play leaves the speaker playing while the app goes back to the phone.**
   `SonosController.kt:650-662`, `:620-648`, `PlaybackService.kt:744/1066`. (a) ep1 ours and PLAYING; the kid taps ep3
   and its SetAVTransportURI faults: `failed(START)` runs `endSession` + `lost`, then `comeHome`; no Stop, the speaker
   plays ep1 on, renderer.json says local, nothing controls the speaker (vibb keeps the renderer and warns). (b) Tap A,
   then B while A's play is in the actor: A fails, `failed(START)` wipes B's session and sets `lost`; B's job still
   runs and plays ep2 unowned. (c) Start, then next before the play returns: the index goes 2 -> 0 -> 2 because
   `played()` confirms an older play whenever `stepWant == null`; `ItemChanged(0)` removes ep1 from Nytt and
   `host.played` writes the stale key into renderer.json. Fix: a per-play generation; an older result may only update
   `playing`; a START failing while our session lives on this uid behaves like a failed step; only the first start on
   a room is Lost, and `lost()` sends an ours-gated Stop before `comeHome`. Three tests.

## Medium

1. **M1: a hand-off timeout tears down the actor before the Stop is sent.** `PlaybackService.kt:992, :1038`
   (`HANDOFF_MS` = 8 s), `SonosRenderer.kt:179-188`, `SonosActor.kt:59-62`. The hand-off queues behind what the actor
   holds (a hushed resume with `seekSettled` up to 8 s, the 3 s ramp, a poll); on a timeout `release()` cancels the
   consumer and the Stop never runs. Fix: drain on release, or a standalone ours-gated Stop on a timeout; size
   `HANDOFF_MS` from the real budgets.
2. **M2: switches and plays are not serialized.** `PlaybackService.kt:312`, `toRoom` 982-1034, `comeHome` 1043-1064. A
   re-pick during an 8 s `toRoom` hands the room off twice and starts the phone's unfiltered queue on the new room;
   PLAY_ENTRY/PLAY_ITEM read `renderer.kind` before `load()` and can start on a renderer attached meanwhile. Fix: one
   Mutex (or a "switching" state) around `output()`/`lost()` and the read-kind -> start sections; bail out when
   `renderer` changed.
3. **M3: a new sleep timer set during the 3 s ramp is silently erased.** `SonosController.kt:942-953`,
   `PlaybackService.kt:621-650`: the cancelled ramp still calls `done()` -> `sleepEnded()`. Fix: a cancelled fade
   doesn't call `done`. Test.

## Low

1. **L1**: the ramp's volume restore never runs (every write of renderer.json drops `volume`; `read()` returns
   `LOCAL_CHOICE` for local), and it is per room. Store it with the uid and keep it across the reset.
2. **L2**: a failed same-tile resume doesn't fall back to a full start (vibb: the full start is the heal).
3. **L3**: a play pressed while a play is in flight is dropped, even after an in-flight pause; queue a resume.
4. **L4**: the bookmark's age term can run up to 25 s ahead (vibb added only the sidecar's <= 5 s). Cap it at ~5 s and
   note it in §9.
5. **L5**: the hand-off's `wasPlaying` reads the raw transport; a pause pressed just before switching with the live
   refresh failed makes the phone start. Use the effective transport.
6. **L6**: test gaps: TRANSITIONING -> BUFFERING; disarm -> IDLE and a play after disarm from the bookmark; nothing
   builds the player's State on the JVM (unique uids, the IDLE fallback); FitTest's loop never uses `label` and the
   header's state line isn't fitted; tests for H1, H2, M1-M3 (factor the switching decisions into pure functions where
   needed).

## Confirmed

The 14 choices are accepted except #8 for a START (= H2a) and #13's side effects (L1; `onDestroy` reporting paused
while the speaker plays is 3b). Fine: the gates, the filter, the failed-start path, no guess reaches Room, the poll
tick's order, the state table, the commands, the handlers, the launcher's position semantics (speed = isPlaying ? 1 :
0, so `positionNow` freezes while paused, stale or taken over), the threading, the Wi-Fi tracking, the strings,
`SourceScanTest`.

## Fix round (the implementer, 2026-10-11)

Every finding is fixed; none skipped. Tests 298 -> 317; `assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` green, lint 0 errors (the same 41 warnings), `fake-sonos.py --selftest` OK.
- **H1**: `stepTo` while the effective transport isn't PLAYING, before a start, or while `mayPlay()` is false only sets
  the index and the start position (`startPending`); the next play (`playPause`/`playTile` -> `start()`) plays that
  item there. `SonosController(mayPlay = ...)`, which `SonosRenderer` sets to `playAllowed(suspendedNow())`, is asked
  by `playIndex` before anything is sent and by `resume`; a natural advance while nothing may play waits at the next
  item. Tests "H1 - a skip on a paused speaker only moves where the next play starts", "H1 - while nothing may play,
  neither a skip nor a start reaches the speaker"; `GuardsTest` "fix round - ..." pins the branch and the order.
- **H2**: `gen`, bumped by `load`, `clear`, `stepTo` and every `playIndex`; a result of an older generation only sets
  `playing` (re-indexed in the current queue, or -1 for another entry) and `ownsSpeaker`. `played()` confirms the
  index unconditionally (it is the current generation). `load` keeps `playing` (re-indexed). `failed(START)` while
  `ownsSpeaker` and the snapshot is ours: `takeRefused()` (the renderer emits "Kan ikke spilles på Sonos"), the index
  back on the speaker's item, the session kept; otherwise `Lost`, and `lost()` (under the switch lock) hands off -
  an ours-gated Stop - before `comeHome`. `handOff` also runs when `ownsSpeaker` without a session. Tests H2a, H2b,
  H2c; the step-mash test now expects the superseded ep2 never to reach `host.played`.
- **M1**: `SonosActor.close()` closes the channel and cancels its scope only after the consumer finished (30 s at
  most); `SonosRenderer.release()` no longer cancels the scope itself. `HANDOFF_MS` = 15 s (the 1.5 s classify and a
  Stop behind a hushed play's settled seek, the ramp or a poll); past it the service calls `abandon()`: the bookmark
  from the last poll and an ours-gated Stop queued behind the busy actor. Tests "M1 - an abandoned hand-off ..." and
  `SonosActorTest` "close drains ...".
- **M2**: `switching: Mutex` around the command coroutine's `when`, playback resumption's restore and `lost()`
  (uncontended it doesn't suspend, so the commands stay synchronous); `toRoom` returns when `renderer` changed after
  its hand-off, load or renderer.json write; `comeHome` lets the room go without touching the queue when another
  switch moved on. `storedPlan`/`homePlan` (`logic/Queue.kt`) are the switch's queue decisions, tested in `QueueTest`.
- **M3**: the ramp's result calls `done` only when it wasn't cancelled; `LocalRenderer.cancelFade` never did. The
  cancelled-ramp test asserts it.
- **L1**: `RendererChoice.volumeUid`; `RendererChoice.local(previous)` keeps a cut ramp's volume and room (the restart
  reset, `comeHome`), `read()` keeps them for a local file, `toRoom` carries them, `SonosRenderer` restores only its
  own uid's. Test "L1 - ...".
- **L2**: `resume(fallback = true)` from the tile: a refusal runs `restart()`. **L3**: `playPause` with a play in flight
  and our pause after it queues a resume. **L4**: `BOOKMARK_AGE_MAX_MS` = 5 s, a §9 row. **L5**: `wasPlaying` uses
  `effectiveTransport`. One test each.
- **L6**: the TRANSITIONING and disarm tests; `playlistPlan` (pure, used by `getState`) with its test; `FitTest`
  measures the labels (Nunito ExtraBold 14 sp: 142.5 and 184.7 dp) and asserts a readable start (>= 40 dp) and the cut,
  and a new test fits the four state lines (Nunito Bold 12 sp, longest 191.8 dp) in the 200+ dp of the header.
