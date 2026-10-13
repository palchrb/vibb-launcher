# QA 26c step 6 code review - Spotify context mode in the music app, the state recorder, the host list

Scope: branch `music-3-spotify`, `665ea7b..74c4942` (one commit, 35 files: `logic/{Spotify,LibraryView}.kt`,
`data/{Snapshot,Store}.kt`, `ui/{UiModel,ListScreens,PlayerScreen,LibraryScreen}.kt`, `spotify/{SpotifyTracker,
SpotifyRenderer,SpotifyWebHost}.kt`, the new `spotify/SpotifyRecording.kt`, `playback/{PlaybackService,RendererRules}.kt`,
`src/debug/.../{DebugSpotify,DebugFeedReceiver,DebugStorytel}.kt`, the strings, the tests, four `sdk_states/context_*.json`,
`scripts/fake-spotify.py`, `docs/testing/emulator.md` §6l, the design's status). Read against root and `music/CLAUDE.md`,
design 26c (§3.3, §4.1-§4.3, §5.4, §5.9, §5.10, §6.2, §9.2-§9.4, §11 step 6, §12, the design QA fix round and every
status, fix-round and merge section, esp. "Step 6 implementation status" with its 14 deviations and its spec findings),
`qa-26c-step5a-code.md` (H1: nothing starts by itself; M3: the start's stale states), `qa-26c-step5b-code.md` (M1:
`detached()`; "the state-recording gap", decided there), `qa-26c-merge-code.md` (M1: `queueKind`), `qa-26c-step4-code.md`
(H1: ownership), and the code each names (`SdkState.kt`, `player.js`'s `compact`, `SpotifyApi.start`, `persistPlace`,
`Store.writeAtomic`, the service's skip paths and `restoreThen`). Line numbers are `74c4942`'s.

Runs, here (the worktree, JDK 21, `music/local.properties`; no emulator, no VM, no account):
- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true --rerun-tasks`:
  BUILD SUCCESSFUL (4 min 36 s), **828 of 828** JVM tests, 0 failures; lint 0 errors / 42 warnings (the merge's 42, none
  in a line this step wrote); `checkReleaseHasNoDebugFeed`: "no debug feed in [app-release-unsigned.apk]".
- `python3 -I scripts/fake-{spotify,storytel,sonos,audio}.py --selftest`: all four OK.
- `git diff --stat 665ea7b..74c4942 -- server launcher music/gradle music/app/build.gradle.kts music/build.gradle.kts
  music/app/src/main/aidl music/app/src/main/AndroidManifest.xml .github` and every shared contract file
  (`spotify_hosts.json`, `music_library.json`, `music_listing.json`, `bridge_contract.json`, `play_request.json`): empty.
  The three `spotify_hosts.json` copies are byte-identical (sha256 `6b5547…793a`) and still say PROVISIONAL, as do
  `server/src/spotify.rs`, the launcher's and the music app's `SpotifyHosts.kt`: the host list is unchanged and marked
  provisional - right, the user hasn't run the capture.
- The documents the implementer's claims rest on, fetched today: the Web Playback SDK reference
  (`developer.spotify.com/documentation/web-playback-sdk/reference`) - `WebPlaybackState.context`: `uri: 'spotify:album:xxx',
  // The URI of the context (can be null)`, `metadata: {} // (can be null)`; `previous_tracks`/`next_tracks`: "Number can
  vary"; `previousTrack`: "Switch to the previous track in local playback", `nextTrack`: "Skip to the next track in local
  playback" - nothing about a paused player. The Web API's Start/Resume Playback: `offset` "Only available when
  context_uri corresponds to an album or playlist object", by `position` or `uri`; nothing on an offset the context no
  longer holds (the implementer's "not documented" is right).
- Mutations (each reverted, `git status` clean): two of the implementer's three re-done - context ownership without the
  context test (`SpotifyTracker.kt:341` `inContext` = `!isAdd(s)`) -> red in exactly the three tests the status names
  (the `sdk_states` replay, the synthetic "not ours" case, the renderer's "another context"); the step guard removed
  (`SpotifyRenderer.kt:644-647`) -> red ("nothing starts by itself"). Two of mine: deviation 13 reverted (`moveOnTo`
  without `pwr`) -> red (the recorder test and `GuardsTest`); the snapshot's item without the live title
  (`Snapshot.kt`, `contextItem(e, null)`) -> red (`SpotifyRulesTest`, `UiModelTest`). One more: the end rule's "another
  of its tracks, paused" clause dropped (`SpotifyTracker.kt:371` with `uri == liveUri`) -> **green**, all 828 (L4).
- Counter-examples (scratch tests on the suite's harnesses, not committed; each quoted below): **red** - our list's
  leftover add right after a context track ran out (M1), a `null` context at the track change after a run out (M1), the
  next track Spotify named posted paused at 0 (M1), a `null` context on our own live track mid-track (M2), a playlist
  context whose stored track left it (M3), a paused step Spotify plays (L1). **Green** - the kid's pause at 0:00 of a new
  track (`pausePending` guards the end), another device's pause at 0:00 (no run out: `Paused spotify`), a restart's stale
  playing state before our play (`IDLE`: `NotOurs`, paused), and every sequence the implementer added.

**Verdict: not ready; a short fix round first.** Counts: **0 High, 3 Medium, 5 Low.** The step is carefully built and
most of it is right: the synthetic item is the snapshot's with its stable key, it shows in both views and a room's queue
drops it, `countedKeys`/`playerNumber` stay one-of-one and the player hides the number, the hint fits (`FitTest`, at
the implementer's measured widths), `spotify_context.json` is pure, atomic and the store's alone, the service never keeps a duration or a done
mark for the item, every step path of the service reaches the web player's `step` behind the network gate (the
notification, the lock screen, a headset's `seekToNext`, `THEN_NEXT` after a restart), the title swap can't race the
service's art swap, `HostEvent.State` keeps the JSON out of its text, the recorder's hook is null in main and set only by
`src/debug`, and in every sequence I built nothing plays without a play of the kid's - the one press that plays where the
rest of the app would only move is deviation 7's paused step (L1). The three Mediums
are the context's edges where the real SDK and Web API are least known, each with a cheap guard: the end rule
declares `Ended` - and `clear_state` deletes the kid's place and the live track - on states that are no evidence of an
end (our own leftover add, a `null` context, the next track posted paused); a `null` context, which the SDK reference
documents, pauses our own track (the status's spec finding reads the reference the wrong way round); and a playlist
context whose stored track has left the playlist can, if Spotify refuses the offset, never start again - every tap
"trenger internett", two play calls and a transfer from the shared quota.

## Findings

### M1 (Medium) - `Ended` (and `clear_state`) on states that aren't the context's end: our leftover add, a `null` context, the next track posted paused

- **Where:** `M/spotify/SpotifyTracker.kt:360-367` (`onContextOurs`: any state that isn't `inContext` - another
  context, **none**, or our list-mode add - is `NotOurs`, plus `contextEnd()` when `ranOut`); `:371` (any track of the
  context paused under 1 s right after a run out is the end); `M/playback/PlaybackService.kt:1413-1430` (`queueEnded`:
  the bookmark, the context row's partial position and `spotify_context.json`'s row deleted); deviations 9 and 10.
- **Why it matters:** `Ended` is the one event that deletes a place (`clear_state`; root `music/CLAUDE.md`: "Positions
  and bookmarks are never deleted except by vibb's `clear_state` at a queue's natural end"). For a playlist context the
  next tap then starts Spotify's start - the kid's place is gone - and the player closes. Three states reach it without
  any evidence of an end:
  1. **Our leftover add** (list mode's queue add past the window, §5.4 "Spotify's queue survives a new play"; `adds` is
     cleared only by a new device). Spotify plays its queue before the context goes on, so after the context's current
     track the add plays - in the fake too (`_userQueue` first, `_context` unchanged). `inContext` refuses it (right),
     and `ranOut` turns it into Autoplay: `NotOurs` + `Ended`. The same add reached by the kid's next mid-track pauses the
     context (`NotOurs`, `LOST`) - the kid pressed next and it stopped. The add is ours to skip, never Autoplay.
  2. **A `null` context** at the track change: the reference says `context.uri` "can be null" (M2); around a track
     change is exactly when the implementer says only part B can tell.
  3. **The next track Spotify named, posted paused at 0** at the change (a loading state; [device check], but the clause
     "or Spotify back at another of its tracks, paused, then" treats every such state as the end). The real end Spotify
     goes back to (the context's first track) is not the last state's `next` - at a context's end `next_tracks` is
     empty - so the two are told apart by the state we already have.
- **Counter-examples** (red; `cs(n, pos, ctx = pl, paused, next = [n + 1], prev)` an `SdkState` of `uri(n)`, 20 s):
  ```kotlin
  // 1. actual [NotOurs playing, Ended]
  val t = SpotifyTracker().apply { load((0..2).map { track(it) }, 0) }
  t.played(0, 0); t.onState(state(0, 0), 0)                       // QueueAdd 1: uri(1) is in Spotify's queue
  t.loadContext(pl, null); t.playedContext(null)
  t.onState(cs(7, 0), 100); t.onState(cs(7, 15_000), 15_100)
  assertEquals(listOf("NotOurs playing"), t.onState(cs(1, 0), 20_200).map(::word))
  // 2. actual [NotOurs playing, Ended]
  val u = SpotifyTracker().apply { loadContext(pl, null) }; u.playedContext(null)
  u.onState(cs(1, 0), 0); u.onState(cs(1, 15_000), 15_000)
  assertFalse("Ended" in u.onState(cs(2, 0, ctx = null, prev = listOf(1)), 20_100).map(::word))
  // 3. actual [Ended]
  val v = SpotifyTracker().apply { loadContext(pl, null) }; v.playedContext(null)
  v.onState(cs(1, 0), 0); v.onState(cs(1, 15_000), 15_000)              // next = [2]
  assertFalse("Ended" in v.onState(cs(2, 0, paused = true, prev = listOf(1)), 20_100).map(::word))
  ```
- **Fix:** `Ended` in a context only on evidence: our live track paused at 0 after it ran out (as now); another track of
  the context paused at 0 then **that the last state of ours didn't name as next** (Spotify back at the top); a track of
  another, **non-null** context that isn't our add (Autoplay). Our leftover add is never the end: `forget` it, and while
  the kid wants it playing (`pwr`, not suppressed) skip it with `vibb.next()` - the context goes on, a continuation of
  the kid's play like list mode's `NewWindow` (qa-26c-step5a-code H1's rule) - else paused. A `null` context follows M2.
  The tracker keeps the last state's `next` uris. The three cases as `SpotifyTrackerTest` cases (and the add one as an
  `sdk_states/context_leftover_add.json`, which the fake reproduces: a 60-track list past its window, a switch, then a
  context); deviation 9 reworded.

### M2 (Medium) - a `null` context, which the SDK reference documents, pauses our own track; deviation 10 and the spec finding misread the reference

- **Where:** `SpotifyTracker.kt:341` (`inContext`: `s.contextUri == context`), `:345` (a start passes such a state over
  until the first-state timer fails it as "trenger internett"), `:360-367`; `M/assets/spotify/player.js:75` (`context:
  s.context ? text(s.context.uri) : null` - "no object", "uri null" and "blank" all arrive as null); the status's
  "Found in the spec": "the documented `WebPlaybackState.context` says so" (`26c-spotify.md:3614-3615`); `emulator.md:1314`.
- **Evidence:** the reference's own object, fetched today: `context: { uri: 'spotify:album:xxx', // The URI of the
  context (can be null)`. So the rule's premise - every state of our context names it - is not what the reference says;
  it says the opposite may happen. The implementer's reason for refusing a `null` context (deviation 10: "a uri list is
  another app's play") holds for a *different* track only.
- **Why it matters:** if the real SDK posts `context.uri: null` on a state of the track we are playing (around a track
  change, after a seek, a transfer back), the renderer pauses our own playback mid-track (`NotOurs` -> `vibb.pause()`,
  `LOST`): context mode stops by itself, and during a start the first state never counts and the start ends as
  "trenger internett". Part B would find it, but only after step 7's release work is planned around it.
- **Counter-example** (red: actual `[NotOurs playing]`):
  ```kotlin
  val t = SpotifyTracker().apply { loadContext(pl, null) }; t.playedContext(null)
  t.onState(cs(1, 0), 0)
  assertEquals(emptyList<String>(), t.onState(cs(1, 5_000, ctx = null), 5_000).map(::word))
  ```
- **Fix:** in context mode a state with no context is ours when its track is the live track, or the track the last state
  of ours named next (our advance - `TrackChanged`); any other track with no context is a uri list: not ours, paused,
  never `Ended` (M1). In a start, a no-context state of the sent offset near the sent position counts like one of ours.
  `SpotifyTrackerTest` cases for both; the spec finding and deviation 10 corrected ("the reference says `context.uri`
  can be null"); §6l part B's line asks the recorder for the states with `"context":null` and what they name.

### M3 (Medium) - a playlist context whose stored track has left the playlist may never start again; a 404 reads as "device unknown"

- **Where:** `SpotifyRenderer.kt:885-887` (the offset is always the stored live track), `SpotifyApi.kt:306` (any 404 is
  `DeviceUnknown`: a transfer, the play again, then `Unreachable("device")`), `:212-224`; the stored row
  (`spotify_context.json`) is replaced only by a `TrackChanged`, i.e. never if no play succeeds. §5.9 (`:1075`): "if it
  can't [open the playlist], the call's 403/404 is `SPOTIFY_ASK_ADULT`"; `emulator.md:1322` says the same; deviation 6
  ("a context the account can't play at all ends as 'trenger internett'"); the spec finding "what the Web API answers
  when a playlist context's offset track has left the playlist is not documented [device check]; the fake plays from
  the top".
- **Why it matters:** a context-mode playlist is by definition one no logged-in account owns - someone else's,
  public or shared - and such playlists change under the kid (an edited friend's list, a weekly-refreshed public one).
  The stored live track leaving it is the normal case, not an edge. If Spotify answers the offset with a 4xx instead of
  playing from the top (undocumented; the fake can only show the friendly answer), every tap: two play calls and a
  transfer from the developer account's shared quota, "Spotify - trenger internett", and the stored track stays - the
  tile is dead until the parent removes the entry. The same 404 path makes §5.9's and §6l's "a 403/404 is 'spør en
  voksen'" untrue for a playlist the kid's account can't open: the 404 is read as an unknown device and ends as
  "trenger internett".
- **Counter-example** (red; `SpotifyRendererTest`'s harness, the play answered 404 whenever it carries an offset):
  ```kotlin
  ports.contexts["e7"] = SpotifyContextTrack(uri(3), "Fake track 3 — Fake Artist")
  val r = contextRenderer()
  api.answer = { req -> if (req.url.encodedPath == "/v1/me/player/play" && req.body!!.contains("offset"))
      SpotifyResponse(404, null, ByteArray(0)) else SpotifyResponse(204, null, ByteArray(0)) }
  r.load(contextItems(), 0, 42_000, playNow = true); advance(0); advance(5_000)
  r.play(); advance(0); advance(5_000)
  assertTrue(api.plays().any { !it.body!!.contains("offset") })
  // actual: 4 plays, every one with the dead offset; SPOTIFY_NEEDS_INTERNET; the stored track unchanged
  ```
- **Fix:** a context start that sent an offset and ends on a non-account answer (`Unreachable("device")` after the
  transfer, `http_400`, `http_404`) tries the context once more **without** the offset inside the same budget, and the
  stored row goes (`storeContextTrack(key, null)`) so no later tap repeats it; a context play without an offset that
  still gets a 404 after the transfer is the context's refusal - the line §5.9 names ("spør en voksen"), not "trenger
  internett". The fake gains an option that answers a context's unknown offset with a 404 (`--offset-404`), its
  `--selftest` and one §6l A2 line (remove the stored track's id from the fixture via the option, a tap goes on from the
  top). The counter-example as a `SpotifyRendererTest` case. §5.9 and deviation 6 say what is built; the device check
  stays (what Spotify really answers).

### L1 (Low) - deviation 7: a step while paused that Spotify plays starts the sound; everywhere else "paused, it only moves"

- **Where:** `SpotifyRenderer.kt:642-650` (`step` sends `vibb.next()`/`previous()` whatever `pwr`), `:1216-1226`
  (`Resumed` while `!pwr`: shown playing with `playAllowed` and focus); list mode's own rule in the same file,
  `seekTo(index, ms)`: "paused, it only moves"; the Sonos rule in `music/CLAUDE.md` ("a skip on a paused, stopped or
  not-yet-started room only moves where the next play starts"); ExoPlayer's `seekToNext` keeps `playWhenReady`.
- **Why:** the kid's next from the lock screen, the notification or a headset while a context is paused may start
  playback (if the SDK's `nextTrack` plays a paused player - the implementer's device check), where the same press on a
  list entry, a podcast or a room doesn't. Not "by itself" - the kid pressed a button - so not 5a H1; but it is the one
  player in the app where next means play. (The `playAllowed`-false case is right and tested.) Scratch test red:
  `play`, `pause`, `step(true)`, `post(cstate(2, 0))` -> `showsPlaying` true.
- **Fix:** a step sent while `!pwr` marks it; a `Resumed` (or a playing `TrackChanged`) that follows it is paused at once
  (`vibb.pause()` + `pauseSent()`, no focus) - the title still moves. Accept the blip the SDK may make before the pause
  (one state's latency); the device check says whether there is one. A `SpotifyRendererTest` case; deviation 7 reworded.

### L2 (Low) - deviation 8: an artist's restored place and the instant view show a place the play won't go to

- **Where:** `SpotifyRenderer.kt:422` (an artist's place 0 - right, §12: no resume); `UiModel.kt:130` `playSwitch` and
  `nowView` (the stored position), pinned for an artist by `UiModelTest.kt:121` (`assertEquals(40_000L,
  sw.view.storedPosMs)` on `spotify:artist:...`); the restored bar's title is the stored live track's (deviation 1).
- **Why:** after a restart an artist's bar shows "Song B — Artist" at 0:40 (the snapshot's row) and the cover tap's
  instant view shows 0:40; the renderer shows 0:00 and Spotify plays its own start, usually another track. The renderer
  is right per §12; the screens are not. (The title of the last track heard is fine as "where you were".)
- **Fix:** one pure rule, `contextResumes(entry)` (a playlist context: true; an artist: false), read by the renderer and
  by the UI's views (`playSwitch`, `nowView`'s stored position: 0 for an artist); `UiModelTest`'s artist case asserts 0
  (and a playlist context its place). Deviation 8 then needs no "although".

### L3 (Low) - the recorder: an unbounded file, a thread per switch-on, and a compact state that can't answer two of part B's questions

- **Where:** `src/debug/.../DebugSpotify.kt:66-84` (`StateFile`: `appendText` forever; `Executors.newSingleThreadExecutor()`
  per file, never shut down when the switch goes off or on again); `player.js:60-79` (`compact`: what is recorded).
- **Why:** a `config.json` left with the key on a debug build (part B's own phone) appends every state - a line per
  5 s while playing, up to 8 KB each - until the disk fills; each off/on leaves a non-daemon thread. Debug only, so Low.
  The recording is the page's compact state (the right choice - it replays straight into `SpotifyTrackerTest`), but
  that form cannot tell "no `context` object" from "`context.uri` null" (M2) and drops `disallows`/`restrictions`,
  which is what the community reads at a context's end - so part B's "what the real SDK posts at the end" may come back
  undecided.
- **Fix:** stop appending past a cap (e.g. 32 MiB per file, one `Log.i` "recording stopped - the file is full"); shut
  the old executor down when the recorder is replaced (or one daemon executor for the object); §6l part B says the
  recording is the compact state and which questions it answers. (If part B can't decide the end from it, a debug-only
  `"disallows"` key in `compact` is a later, separate change: `SdkState.KEYS` and `PlayerJsTest` pin the shape.)

### L4 (Low) - small things

- `Store.spotifyContexts()` (`Store.kt:150`) is read from the main thread at the renderer's `load`
  (`SpotifyRenderer.kt:1910`, `contextTrack`): the file on first use, and the store's monitor, which the writer holds
  through `writeAtomic` at a track change. Usually already loaded by the snapshot flow; read it once off main (the
  service's `start` already has the snapshot, which carries it) or keep the renderer's own copy.
- `spotify_context.json` rows are never pruned (an entry back in list mode, or gone from the library); harmless and
  tiny, but a list -> context flip months later goes on from that old track - fine, say so in the status, or drop rows
  for keys no context entry has at the service's `forget()`/library change.
- Deviation 13 is pinned behaviourally only inside the recorder's test ("the debug recorder gets every posted state
  ..."); give it its own named `SpotifyRendererTest` case (a `playback_error` on a paused list track: the index moves,
  no `PUT`).
- The end rule's second clause ("Spotify back at another of its tracks, paused", `SpotifyTracker.kt:371`) has no test:
  restricting the end to `uri == liveUri` leaves all 828 green. Pin it with M1's rewrite (a context's last track, then
  its first track paused at 0, not named next: `Ended`).
### L5 (Low) - the spec body and §6l lag the step

- §5.9: `{"track": "<id>"}` (deviation 2), "title the entry's name" (deviation 1), `replaceItem(0, ...)` (deviation 4),
  "neste: N spor" (deviation 5), ownership "a state whose `context` uri isn't the entry's target is foreign" (deviation
  10, and M2), the end (deviation 9, M1), the 403/404 line (M3); §9.4 "called by the page bridge's `state()`"
  (deviation 11); §11 step 6's file list (the ports, `RendererRules.kt`, `SpotifyRecording.kt`).
- §6.2 and the three provisional comments say the capture lands "at the latest in step 6"; it didn't (rightly - it's
  the user's). §6.2 and the Release section should say "before any stable tag" (the JSON comment is a contract file:
  leave it to the capture's commit, which replaces it anyway).
- §6l: `emulator.md:1314` ("a state without it would read as not ours") and `:1322` ("a 403/404 is 'spør en voksen'")
  follow M2 and M3. The rest of §6l's new lines were walked against the fake's routes and log strings and the app's
  log words and match: `play: context <uri> from Spotify's start at 0 ms on …`, `from <uri> (track N of M) at <ms> ms`
  (entry 6 as a context is `FAKEtrack` 1..5, the A1 403 playlist the fixture's three listed tracks), "Stille natt — Ola
  Fjell" (the fixture artist's album), the stub's previous after 3 s, the end (the stub's last track paused at 0: our
  `Ended`), `--autoplay`'s own context, the recorder's lines and its file path, `spotify: state recording to
  sdk_states/<UTC>.jsonl`.

## The implementer's deviations (14, all judged)

1. **The item's title is the live track's** - accepted: one source for every screen and the session; the tile's hint
   stays the attribution line. (For an artist see L2.)
2. **`"uri"`, not `"track"`** - accepted: a playlist context holds episodes, and `offset.uri` needs the type.
3. **The renderer writes the file through ports, the service drops it at `queueEnded`** - accepted; both on the
   service's one writer, in order (`storeContextTrack` and `saveNow` from the same main-thread turn, `queueEnded`'s drop
   after the `Ended` event, which never carries a `TrackChanged`).
4. **The title swap is the renderer's** - accepted, and there is no race with the service's art swap: both run on main,
   `replaceItem` re-titles whatever item the service passes from the renderer's current `liveTitle`, and the mediaId
   guard holds (only the title changes). The session, `reportNowPlaying` (its key now carries the title: a track change
   is a new report) and the notification read the renderer's item; the screens read the snapshot, which follows the file.
5. **No "neste: N spor"** - accepted: the reference says the number "can vary", the parser keeps at most three.
6. **No unplayable item in a context** - accepted (an `unplayable` on `context` would mark the whole entry); its
   "trenger internett" for a context the account can't play is M3's second half.
7. **No step without our context on the player** - accepted (only Spotify knows next). **A paused step Spotify plays is
   shown playing** - L1.
8. **An artist's place shows 0** - the renderer is right (§12); the screens contradict it - L2.
9. **The end and Autoplay** - the same-track end and a foreign context right after a run out are accepted; "another of
   its tracks paused" and the foreign test without evidence - M1.
10. **"Ours" refuses no context and our leftover add** - the add: accepted as not ours (M1 says what to do with it); no
    context: M2.
11. **The recorder's plumbing** - accepted: the bridge has no ports; the JSON travels in `HostEvent.State.json`, left out
    of `toString`; the renderer records on main and the writer appends on its own thread (the re-encode parse runs on
    the caller's, main - harmless at 8 KB per 5 s). See L3.
12. **A track change on a refused network pauses with the line** - accepted, and it is the merge's streaming rule:
    `networkLine()` is `spotifyNetworkLine(netCaps(app), store.streamMobileData)` (`SpotifyRenderer.kt:1883`), the same
    formula the service's `onItemChanged` asks for list mode's advance, asked again at each track Spotify moves to (as
    design 30's `StreamGate` asks at every open); the place is saved before the pause, and the next play goes on from
    the new track. A paused step is gated by the service first (`stepOnWebPlayer` -> `refuseSpotify()`).
13. **The move-on after an unplayable plays only with the intent** - accepted and correct (5a H1's rule; the service's
    `prepare()`/`play()` after it return at `moveOn != null`, `moveOn` lives only during the dispatch). List mode's
    unplayable tests move on while playing (`pwr` true), so none regressed; reverting it is red (the recorder test,
    `GuardsTest`).
14. **The fake** - accepted: only playlists and artists are contexts here; Autoplay's own context and Spotify's 3 s
    previous rule are what the stub needs to drive §6l. Its context play from the offset's index and "the top" for an
    unknown offset are the friendly answer only (M3 asks for the other).

## The spec findings (7)

All right except one: "§5.9 rests the whole ownership rule on the state's `context` being the entry's target in
**every** state ... the documented `WebPlaybackState.context` says so" - the reference says `uri` "(can be null)" (M2).
The offset that left the playlist is rightly called undocumented; it needs a fallback, not only a device check (M3).

## Checked and fine

- **The snapshot**: `buildSnapshot` gives a context entry `listOf(contextItem(e, spotifyContexts[e.key]))` before the
  list-mode branch, so a playlist's last good list in Room never shows; `visible()` takes both modes; `onPhone` empty;
  the room's queue drops it (`speakerDropReason` `spotify`); `Snapshot.entries` keeps the tile; `storedPlan` finds the
  key (`SpotifyRulesTest`). No sync prunes a position row by listed keys (`MusicDb.kt:291,303` are `clear_state`'s two
  deletes), so the `context` row lives until the end.
- **The file**: `SpotifyContexts` decodes leniently (junk rows dropped, keys `[A-Za-z0-9_-]{1,64}` - the library's keys
  are 12 hex - uris track/episode only, titles cut as the server's), writes only on a change, `writeAtomic`
  (tmp + rename); `spotifyContextFlow()` is in `Store.snapshots()` and `snapshot()`; `GuardsTest` pins
  `spotify_context.json` to `Store.kt` alone. Nothing in it is a secret.
- **The service**: the `Ready` handler keeps no duration for the item (`keepsDuration`), `saveNow` passes `null` (so
  `persistPlace`'s `place()` never marks it done and the stored queue's duration is null), `queueEnded` marks nothing done
  and drops the row; `reportNowPlaying` sends the live title and re-sends on a change. Every step path of a context goes
  through `skipNext`/`skipPrevious` -> `stepOnWebPlayer` (the session's `seekToNext`/`seekToPrevious` and their
  `MediaItem` variants, `THEN_NEXT`/`THEN_PREVIOUS` after a restart's `restoreThen`); a swipe can't happen (one page, no
  carousel), and a pick of the one row is a fresh start. A restored context (tracker `IDLE`) sends nothing on a step.
- **The UI**: `countedKeys`/`playerNumber` are 1 of 1, `showsNumber` false (`UiModelTest`, run); no `skipSwitch`
  (no instant view where only Spotify knows the target); the hint `Picks` whether started or not, no progress line;
  the one "Liste" row "Spilles nå" without a trailing dot (`removeSuffix(" · ")` also tidies any list item without a
  duration); the stored account line still wins on the tile. Widths (the implementer's measurement from the font files, not re-measured here): Nunito Bold 12 sp 197.6 dp (nb) -> 214 dp at the
  tile's 13 sp in 288 dp; ExtraBold 12 sp 202.9 dp in the list's header - `FitTest` asserts both at 320/360/411.
- **The start**: `PlayBody.Context` carries `position_ms` only with an offset (an artist's body is `{"context_uri"}`
  alone, as the Web API allows); shuffle and repeat go off first as in list mode; `playedContext` widens nothing it
  shouldn't (the stale set is the last state's track and our adds; the near-the-offset exception, an artist's near 0);
  a held foreign state in a start is passed over, a foreign playing state is paused.
- **Nothing starts by itself / detached** (5a H1, 5b M1), walked: a restore loads paused with no page and no network
  (tested); `step()` returns while `detached` or `released`, and a detached player's tracker is `LOST`, so a
  `TrackChanged` can't arrive (no file write, no save: `queue()` is null there anyway); `NewWindow`/`ShuffleOrRepeat`
  still go through `wanted`; `Resumed` while detached is paused; `contextTrackChanged`'s network pause runs only with
  `pwr`. The only sequence that plays without an explicit play is L1's paused step.
- **The recorder, no state JSON in any log**: `SpotifyRecording.recorder` is declared null in main, assigned only in
  `DebugSpotify.kt` (`SourceScanTest` scans every main and debug source for an assignment), which a release doesn't
  contain (`checkReleaseHasNoDebugFeed` ran: "no debug feed in [app-release-unsigned.apk]"), switched only by
  `config.json`'s `record_spotify_states` (off when absent). The JSON's only path is `HostEvent.State.json` ->
  `ports.recordState` (one call, pinned); `State.toString` leaves it out (a data class would print it; the class is no longer one), the bridge's
  `state()` logs nothing, every `log(`/`Log.` line in `spotify/` and the service was grepped - interpolations are fixed
  words, kinds, counts, hosts and exception class names; no exception message carries a state. The recorder's own
  `Log.i` names the file only. Events are names and numbers (`error <kind>`, never the SDK's text).
- **The fake and its selftest**: offset play (index from the offset, `linked_from` honoured), the top for an unknown
  offset, the artist's album from Spotify's start, the 400 for an album context, Autoplay in
  `spotify:station:FAKEautoplay000000001` after a context only, the stub's previous rule; the four `sdk_states/context_*`
  files have exactly `SdkState.KEYS` (the selftest checks) and the documented fields as `compact` reduces them.
- **The four new sequences** are real sequences, not tautologies: each is replayed by the same harness with expected
  events per step and a checked end state (mode, index, measurement), and mutation 1 turns the replay red.

## Fix-round checklist

- [ ] M1: `Ended` in a context only on evidence (the live track paused at 0 after its run out; another track of the
      context paused then that the last state didn't name next; a non-null other context that isn't our add); our
      leftover add forgotten and skipped with `vibb.next()` while `pwr`, else paused; the three counter-examples as
      tests (+ `sdk_states/context_leftover_add.json`); deviation 9 reworded.
- [ ] M2: a no-context state of the live track, or of the track our last state named next, is ours; any other is a uri
      list (paused, never `Ended`); a start counts a no-context state of the offset near its place; tests; deviation 10
      and the spec finding corrected; §6l part B asks for the `"context":null` states.
- [ ] M3: a context start with an offset that ends on a device/400/404 answer retries once without the offset and
      drops the stored row; a 404 after the transfer on a context is the context's line, not "trenger internett"; the
      fake's `--offset-404`, its selftest and an A2 line; the counter-example as a test; §5.9, deviation 6, §6l `:1322`.
- [ ] L1: a step sent while paused stays paused if Spotify plays it (`vibb.pause()` + `pauseSent()`); a test.
- [ ] L2: `contextResumes(entry)`; the UI's views at 0 for an artist; `UiModelTest`'s artist case.
- [ ] L3: the recorder's cap, the old executor shut down, §6l part B on what the compact state can answer.
- [ ] L4: `contextTrack` off main (or the renderer's own copy); stale rows said or dropped; deviation 13's own test; the
      end rule's second clause pinned.
- [ ] L5: §5.9, §9.4, §11 step 6, §6.2/Release ("before any stable tag"), §6l `:1314`.
- [ ] The music suite with `--rerun-tasks`; the four selftests under `python3 -I`.
- [ ] Still owed after the fix round (unchanged): the DNS capture (the three `spotify_hosts.json` copies and the
      constants, one commit, before any stable tag); §6l A1/A2's context and recorder lines on the user's emulator;
      part B's recorded real sequences (artist, a playlist the parent doesn't own, a context's end with Autoplay on and
      off, the `context` field around track changes, a paused `nextTrack`, an offset no longer in the playlist) turned
      into `sdk_states/` sequences; QA checklist items 17 (with a context) and 18.

No question for the user: each fix keeps a rule the design already states (`clear_state` only at a real end, the
reference's own object, §5.9's "spør en voksen", "paused, it only moves", §12's artist without resume).
