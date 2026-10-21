# Design QA - 53 Sonos: grouping in "Spill av på", grouping done outside the app, precision and push events

Reviewed on `main` at 8414e925 (2026-10-21). I read `docs/design/53-sonos-grouping-precision.md` against:
- `CLAUDE.md` and `music/CLAUDE.md` (the DIDL rule, "Nothing starts by itself on Sonos", the threading, the volume
  and room-scope rules, "never a guess in Room");
- PLAN's "Design 53" entry, with both speaker measurements, and the "To decide" DIDL item;
- the code the note names;
- vibb `pi/daemon.py` `_sonos_body` (1423-1475) and `pi/sonosd.py` (`didl()` 346-379, `Session.play` 478-560, the
  migration comment 81-85);
- the approved sketch (`Main`, `Grouped`, `Busy` `.dc.html`, with their aria labels);
- `scripts/sonos-ws-probe.py` and `scripts/sonos-play-test.py`;
- `docs/testing/emulator.md` §6h.

Paths: `M/` = `music/app/src/main/java/me/vibb/music/`. I only read; nothing was built.

Note on where this file lives: the earlier design QAs are in `docs/design/qa-NN-design.md` (for example
`docs/design/qa-52-design.md`). This one is in `docs/review/` because the brief asked for that path.

## What holds

- **The shape** (§1): commands stay on UPnP and the socket only carries events. Every decision that matters (ours,
  the advance, the migration follow, grouped away, the bookmark) is still taken on a UPnP snapshot. If the socket dies,
  we lose speed and keep every feature. This is the right reading of the measurements, and it matches vibb's rule that
  a decision only ever comes from a measured, ours-verified poll.
- **The DIDL fix itself** (§2.1) matches vibb field by field. I checked `PlayBodies.build` (`M/sonos/PlayBody.kt:65-81`),
  `SonosTransport.play` (`M/sonos/SonosTransport.kt:105-131`), `NrkService` and `didl()` against `_sonos_body` and
  `Session.play`:
  - `url`: title, album, http art only, no duration.
  - `nrk_program`: the show class, `sonos.com-http:*:audio/mpeg:*`, the `cdudn` desc and the same uri recipe. vibb
    passes `duration_s`, but `_sonos_body` never sets it for NRK.
  - Storytel: title, album and the shelf's duration. vibb also sends `art_url or art`. The app sends no picture, which
    is less than vibb, and that is allowed.
  - The app's own progress keeps its duration. `played()` seeds `durS` from the listing (`SonosController.kt:938-947`)
    and `accept()` carries it (`:1053-1078`), flagged `durFromListing`. §0.3 describes this correctly.
- **The facts in §0.4** match the code: the cadences and the disarm (`SonosSession.kt:435-453`), freshness (`Fields.fresh`),
  the advance (`SonosController.kt:1039-1044`), the scope watch (`PlaybackService.kt:313-321`) and the migration probe's
  gate (`SonosSession.kt:482-495`). The table of the four outside cases describes today's behaviour correctly.
- **The trust boundary** (§2.2) is sound:
  - The socket gets its own client. OkHttp keys pooled connections by `Address`, which includes the SSL socket factory
    and the hostname verifier, so a trust-all connection is never reused by another client.
  - The host gate runs before connecting: a private IP literal that is the cached IP of the uid we command, port 1443,
    path `/websocket/api`.
  - A URL from a message is never dialled.
  - The socket's input can only wake a UPnP poll, and the poll decides.
  - The inherited `readTimeout(5 s)` (`Soap.kt:117-122`) is harmless. OkHttp clears the read timeout on a socket once
    the upgrade succeeds.
- **The verbs** (§2.6) are soco's: `x-rincon:` with empty metadata on the joining room, and
  `BecomeCoordinatorOfStandaloneGroup` on the leaving room. The order "members first, the coordinator last" for a busy
  group row is right.
- **The split** into 53-a and 53-b: each round ships on its own. 53-b also works if the socket turns out to be dead on
  the house's firmware, because it falls back to the group watch.
- **The three questions are real decisions** (see "Questions for the user").

## Findings

**53-1 (High) - socket states feed `effectiveTransport` and the seek hold without the poll's TRANSITIONING rule. This
brings back the G1-d flap and adds spurious Paused/Resumed events.** §2.3 says the effective transport "becomes the
socket's state when newer than the poll", that BUFFERING anchors "not moving", and that `optPos` is cleared "when a
socket position lands within `SEEK_TOL_S`". It does not say how BUFFERING maps to a transport state, or whether `optTr`
still wins over the socket.

- The poll machine deliberately never lets TRANSITIONING change the shown state: classify keeps "the last REAL state (a
  pause icon on every fresh start, G1-d)" (`SonosSession.kt:339-345`), and `spec()` shows a transitioning snapshot as
  BUFFERING with the held `playWhenReady` (`SonosController.kt:374`).
- A socket BUFFERING after a seek, a resume or a rebuffer is "newer than the poll". Treated as anything but PLAYING,
  it makes `spec()` take its `else` branch (`:377-378`): READY with `playWhenReady = false`.
- The renderer then emits `Paused(pauseCause)` and later `Resumed` (`SonosRenderer.kt:382-385`). The service acts on
  both:
  - `Resumed` runs `applyOverlap()` and `startedPlaying()`.
  - `Paused` runs `saveNow()`.
  - `IsPlaying` flips, which affects the locks and `callStep`.
  - If the BUFFERING maps to a PAUSED-like state while `snap.ours` is true, `pauseCause` reads USER (`:342-343`), and
    `PauseCause.USER` cancels the sleep timer (`PlaybackService.kt:1390`).
- This would happen on every seek, every "15 s tilbake" and every rebuffer.
- The seek hold has the same problem. A BUFFERING right after a short scrub can carry the pre-seek position, which is
  within 6 s of the target, so it clears `optPos` and the bar jumps back to the old place before the PLAYING event
  arrives.

Fix (§2.3):
- A socket BUFFERING never changes the effective transport or `playWhenReady`, exactly like TRANSITIONING. It only
  stops the anchor's motion, and at most sets the phase to BUFFERING with the held `playWhenReady`.
- Only PLAYING, PAUSED and IDLE are states.
- `opt(now)` keeps precedence over the socket. A socket state equal to `optTr.state` confirms and clears it; a
  different one is ignored until `SETTLE_MS` or a poll decides, as polls are today (`:1026`).
- `optPos` is cleared only by a PLAYING or PAUSED event within 1 s of the target. The socket's position is exact; the
  6 s tolerance was sized for whole-second polls.
- Tests (`SonosControllerTest` `d53`):
  - BUFFERING during a seek or a resume leaves `playWhenReady` true and emits no Paused/Resumed.
  - BUFFERING with the old position does not clear `optPos`.
  - A PAUSED event while `optTr` is PLAYING inside `SETTLE_MS` changes nothing.

**53-2 (High) - the precision rule ("the newest of the WS anchor and the poll" plus a fixed floor) makes the bar
stutter. Under the 1 s poll it stutters on every poll, and the exact anchor loses to a late one.**

- A poll's anchor is behind the truth:
  - `RelTime` is whole seconds.
  - `measuredAt` is stamped at the end of `classify` (`SonosSession.kt:399`), after GetPositionInfo (`:337`), after
    GetTransportInfo, and on an aux poll also after GetZoneGroupState and GetVolume (`:368`, `:376`).
  - So the anchor is `frac + δ` late, with `frac` in [0, 1) s and `δ` about 0.1-1 s.
- At a 1 s cadence (about 1.2 s with the SOAP time) the `frac` of consecutive polls grows by about 0.2 s each poll and
  then wraps. With §2.4's fixed floor ("positionAt = max(extrapolated, floorMs) ... the floor goes once the
  extrapolation passes it"), the bar freezes about 0.2 s on each of four polls, then jumps about 0.8 s forward. The
  floor only prevents backward steps, and it causes the freezes. A seconds counter then ticks irregularly. §5 check 4
  ("the bar against the Sonos app over 10 min") would fail.
- With a healthy socket, every 15 s cruise poll is "newer" than the last event. It replaces the event's exact
  `positionMillis` with the late, truncated poll, and the bar freezes for up to a second each time.
- `SonosSpec.positionAt` caps extrapolation at `MAX_EXTRAPOLATE_MS` (`SonosController.kt:129-136`). Any rule that keeps
  an older anchor has to re-base it, or the bar stops 60 s after it.

Fix (§2.4, replacing "the newest wins" and the fixed floor):
- **Re-anchor with hysteresis.** A new measurement (socket or poll) of the same item, in the same moving or not-moving
  state and with no seek, step or play since, re-bases the anchor at the old extrapolation's value at the new
  measurement's time when the two are within 1 000 ms. Only a difference of 1 s or more, a state change, a seek, a
  step, a new item or a play moves the anchor to the new value. The bar is then monotone and smooth, never frozen.
  The 60 s cap is respected because every measurement re-bases.
- **Stamp the poll where it was measured.** Take `measuredAt` right after GetPositionInfo returns, not at the end of
  classify. A poll anchor can also count as `RelTime + 0.5 s`. That is display only and never reaches Room, so
  "never a guess in Room" still holds. Freshness can keep using the same stamp.
- **Prefer the socket.** Under a healthy socket, a poll only re-bases when it differs by 1 s or more.
- Rename `SonosFloorTest` to the hysteresis rule's test with:
  - a sawtooth input (1.2 s polls of whole seconds): monotone, no step larger than one tick, no stall over 100 ms;
  - a 1.5 s correction jumps at once;
  - a pause holds;
  - the re-base survives more than 60 s of re-anchors.

**53-3 (Medium) - the sheet is undefined when a room renders and our uid coordinates no row (case 4, grouped away),
and "+" can then target our own room.**

- §2.7 covers "no room renders" and "a room renders and our uid coordinates one of the rows". It does not cover case 4.
  There `SpeakerCache.rooms` builds the row "Barnerommet + Stua" with `uid = Barnerommet` (`SpeakerCache.kt:128-133`).
  No row has our key, so nothing is selected, and "every other row" would get a "+".
- "+" on that row means `x-rincon:<Stua>` for each member (`ROOM_JOIN`), including Stua itself and Barnerommet's
  coordinator. That silences Barnerommet's stream and sends Stua a join to itself.
- The note also lets "+" show when our session isn't live (stopped, ended, disarmed). There, "+" stops another room's
  stream and joins it to a silent coordinator.

Fix:
- `groupSheet(...)` gives no "+" and no "−" anywhere unless all of these hold:
  - our uid is a row's uid;
  - that row's members contain our uid;
  - the controller has a session that is live (fresh, ours, PLAYING or PAUSED).
- Grouped away, the row holding our room shows the player's line ("Spilles i en annen gruppe") and a tap on it is
  today's `OUTPUT`.
- The service's belt refuses `ROOM_JOIN` for any row whose members include our uid.
- `OutputRowsTest`: no "+" while grouped away, and none on a stopped session.

**53-4 (Medium) - the cap on joining rooms needs a baseline, or the first `rooms.state` caps every member and adopt
stops being zero-command.** §2.9 caps "a room that joins our group" once (`cappedMembers`, cleared by a new session),
"as soon as the topology shows it". The note never says which members count as joiners.

- At adopt (`SonosController.adopt`, `:506-528`), at a follow, and at a start that ran with an empty `members` list
  (`members(target)` comes from a `rooms.state` that may be empty or stale; `PlaybackService.kt:1815`,
  `SonosRenderer.kt:138`), the first topology lists members nobody capped.
- If those count as joiners, the app sends `SetVolume` to rooms of a group it only looked at. That breaks "adoptLive's
  zero commands" (§2.10). It also pulls down volumes the household set (26a §5.3, vibb never fights an external change).

Fix:
- The controller keeps a `knownMembers` set:
  - at a start it is the members capped at that start;
  - at an adopt or a follow it is the first topology's members, with nothing sent;
  - a later change adds to it.
- Only a uid that appears in our group's members and was not in `knownMembers` is a joiner: capped once if louder,
  then added.
- Tests:
  - an adopt with a two-room group sends no `SetVolume`;
  - a third room appearing later is capped once, and only when louder;
  - a start whose `members` was empty caps the second room when it first shows (it was never capped).

**53-5 (Medium) - the golden DIDL plan can't produce two of its three files, and it would test a path production
doesn't use for NRK.** §2.1 builds `didl_{url,nrk,storytel}.xml` with `sonos-play-test.py`'s
`built_for(ep, "vibb")`, and `PlayBodyTest` builds "the same three through `PlayBodies.build` + `didl()`".

- `built_for` (`scripts/sonos-play-test.py:99-105`) only builds a plain-URL podcast. It has no NRK service item and no
  Storytel branch with its duration.
- The NRK DIDL is not `PlayBodies.build` + `didl()`. It is built inside `SonosTransport.play` with the service uri
  from `NrkService.programUri(ip, port, ...)`, `UPNP_CLASS`, `PROTOCOL` and `DESC` (`SonosTransport.kt:119-131`). A
  test through `didl()`'s defaults would compare the wrong class, protocol and desc.
- A Storytel body has `uri = ""` until the mint (`PlayBody.kt:72`).

Fix:
- Move the DIDL construction out of `play()` into a pure `builtDidl(body, uri): String`. `SonosTransport.play` calls
  it, and the test calls the same function. Use the static `NrkService.programUri(series, programId, serial)` with a
  fixed serial, and a fixed minted URL via `body.copy(uri = ...)` for Storytel.
- Give the script a `--golden DIR` mode that writes the three files with vibb's per-kind arguments, taken from
  `Session.play`: `url` with `didl(uri, title, album=, art=)`; `nrk_program` with the show class, the protocol, the
  desc and no duration; Storytel with `duration_s`. Use fixed inputs: no http art, an extension-less Storytel URL, a
  fixed serial. Then the files are generated, not hand-copied.
- `SonosTransportTest:144` (`body.copy(durationS = 1453.0, ...)`) and `PlayBodyTest:26,151-154` change with the fix.

**53-6 (Medium) - "healthy" can't catch a socket that is subscribed but silent, and "healthy" turns off two fallbacks.**

- Health is: subscription acknowledged, plus no "disagreement", meaning two polls 3 s or more after the last event
  that see a different state (§2.2).
- A firmware that acknowledges the subscription but never pushes therefore stays "healthy" until the state happens to
  change twice. Polls are 15 s apart, and a long PLAYING stretch never disagrees.
- While "healthy":
  - the 1 s display poll is off (§2.4);
  - the default-scope group watch is off (§2.8);
  - the 60 s backoff reset rewards the silent socket.
- So the precision the user asked for is lost silently, which is worse than the declared fallback.
- The handshake also never asks for the current state. The probe sends `playback:1 getPlaybackStatus` after
  subscribing (`scripts/sonos-ws-probe.py:221`); the note doesn't. Until the first change, the socket's state is
  unknown.

Fix:
- Send `getPlaybackStatus` after `playback:1 subscribe` and treat the reply as an event.
- Healthy additionally requires that reply.
- **A liveness rule:** every play, pause, resume or seek of ours must be followed by a `playbackStatus` within 3 s (the
  measurement says 0.0-0.4 s), or the socket is marked dead (closed, counted as a failure).
- `SonosSocketTest` or `SonosControllerTest`: a verb with no event within 3 s means not healthy, and the 1 s display
  poll and the group watch resume.

**53-7 (Medium) - `ROOM_JOIN`/`ROOM_LEAVE`: the result path doesn't fit the dispatcher, and the joins block our
actor.**

- §2.7 has the service "answer the custom command with `RESULT_SUCCESS` or an error". `onCustomCommand` always
  returns `RESULT_SUCCESS` at once and runs the work later under `switching` (`PlaybackService.kt:546-570`). Design
  52's failure line is published state (`HeadsetLine.NO_CONTACT` from `connect.failed`), not a session result.
- The note also gives `groupSheet` `pending` and `failed` flags. Two mechanisms are named for one thing.
- §2.6 runs every join in our room's actor, through `SonosTransport`'s one mutex: `verify = true` plus a 4 s budget per
  member, plus the cap's 1.5 s. A "+" on a three-room busy row with one room switched off holds the actor for up to
  about 16 s. Polls, Pause, the volume keys and a hand-off all wait behind it. The hand-off's `HANDOFF_MS` is 25 s,
  and a Storytel mint can already take 10 s of it.

Fix:
- Publish `pending` and `failed` (uid to expiry) in `RoomsView`/`PlayerHub.State` from the service, as design 52 does.
  The custom command keeps returning `RESULT_SUCCESS`.
- Run "+" (verbs on other rooms only) in a job of its own on IO, sequential per tap, with a 2 s budget per call. It
  orders nothing against our transport.
- Keep only "−" on **our coordinator** in our actor, because it must order against our plays and it starts the follow.
- State whether the switch lock is held across a join. Recommended: not held; the job checks the gates when it
  starts.

**53-8 (Low) - nothing bounds the rate of confirm polls.** "Every event wakes the poller". The conflated channel
(`SonosActor.kt:97,116`) bounds the queue to one poll, not the rate.

- A burst of events, a chatty household on `groups:1`, or a spoofed peer on the LAN gives back-to-back polls with no
  pause.
- An event during the UNREACHABLE backoff (`SonosSession.kt:437`) also defeats the backoff.

Fix: an event-driven wake at most once per 1 s (an IDLE or MOVED one at once), and none while the last poll failed.

**53-9 (Low) - assumptions the design rests on that neither measurement covered. Add them to §5 check 1 (the
capture) and say what changes if one is false.**

- (a) **`positionMillis` at the natural end's IDLE.** It may be 0. The `max(event, extrapolation)` rule covers that, but
  the capture should show it.
- (b) **Whether a seek or a resume goes through BUFFERING** (53-1).
- (c) **A takeover from the Sonos app on our room.** §2.3 assumes "a hijack shows as IDLE then BUFFERING". If it goes
  PLAYING to BUFFERING to PLAYING with no IDLE, their position is painted under our title until the confirm poll. A
  BUFFERING we didn't cause should also block the anchor until an ours poll. `metadataStatus.container` (our
  `dc:title`, §0.1) is a cheap early hint of a takeover. It is decided no today, but it is worth subscribing if (c)
  shows no IDLE.
- (d) **Whether a `groupCoordinatorChanged MOVED` reaches our socket when our own coordinator leaves.** Only "MOVED
  for subscribing to another group" was measured. If it doesn't arrive, §2.8 case 3 relies on `groups`, and the note
  must say how the candidate is derived from a `groups` event: the coordinator of the group that now holds our former
  members.
- (e) **What our coordinator's transport reads after "−" or a hold-Play leave.** vibb's field note says STOPPED with
  an empty TrackURI (`sonosd.py:81-85`), and the probe's gate needs exactly that (`SonosSession.kt:483`). If the
  TrackURI is kept, there is no probe. Worse, near the end `endsNear` plus STOPPED plus ours would advance on Stua
  while Kjøkken plays the current episode. §5 check 7 should report the TrackURI, not only "the app follows".
- (f) **Whether the subscribe pushes the current status** (53-6).
- (g) **An S1 speaker or a firmware without port 1443.** TCP is refused rather than an HTTP answer, so the 6 h park
  never applies and the reconnect runs every 300 s for the whole session. Park a uid after five refused connects too.

**53-10 (Low) - the socket's lifetime and its reducer need their own rules and tests.**

- §2.2 closes on Wi-Fi *loss*. `SonosHome`'s callback only clears on `onLost` of the same network
  (`SonosHome.kt:35-43`). When Android delivers B's `onAvailable` before A's `onLost`, there is never a "loss" and the
  socket on the old LAN stays open until its ping fails. Close on any change of the Wi-Fi network.
- "A `groups` event whose group of our player has another id subscribes `playback:1`" should apply only when that
  group's `coordinatorId` is our player. While grouped away, every `groups` event otherwise resubscribes and gets a
  MOVED back.
- Lifetime is a battery rule but has no test. Make it a pure rule, `socketWanted(armed, wifi, closed, parked, uid)`,
  and test it: opens on arm, closes at disarm, stop, park, hand-off and release, re-targets at a follow, never while
  the phone renders.

**53-11 (Low) - inputs from the main thread reach a session that is "not thread-safe".** `SonosSession` is touched
only under `SonosTransport`'s mutex on IO (`SonosSession.kt:10`). §2.4 reads `PlayerHub.playerShown`
and socket health in its cadence, and §2.8 calls `SonosSession.hintCandidate(playerId)` from the controller on main.

Fix:
- The hint goes through the actor (`work.submit { transport.hint(uid) }`).
- `playerShown` (a `StateFlow`) and health (`@Volatile`) are injected into the session as `() -> Boolean`.
- Say this in §2.2/§2.4.

**53-12 (Low) - details of the poll guards.**

- **Stall spacing:** an uncounted poll must leave both `frozen` and `lastPos` alone (`SonosSession.kt:467`), so the
  rule stays "the same RelTime at polls 5 s or more apart".
- **The 5 s bookmark throttle:** a write from `pollTick` when the transport changed since the last write (a pause from
  the Sonos app) is not throttled. The pause point is the bookmark that matters most.
- **§0.3's "wanted" consequence:** after the fix, a podcast whose speaker reads `0:00` throughout no longer gets the
  tail's 5 s cadence, because `cadenceMs`' tail uses the speaker's own duration (`SonosSession.kt:449`). Without the
  socket that means up to 15 s of silence at its end, instead of 5 s. Today the speaker probably echoed the DIDL
  duration. Either feed the listing's duration to the cadence as a hint (cadence only, never the bookmark), or accept
  it and say so. §5 check 3 already names the extension-less podcast; have it report the cadence too.

**53-13 (Low) - the IDLE advance: `idleEnd` adds little, and the confirm poll widens one old race.**

- In practice `endsNear > 0` already holds at the end. The tail's 5 s polls, or the 15 s cruise inside the 20 s window,
  always see the last 20 s. What makes the advance fast is the confirm poll, not `idleEnd`. Keep `idleEnd` only for
  the rare backoff case, or drop it.
- The race: someone starts another stream on our room from the Sonos app at the episode's end. The confirm poll lands
  0.1-0.3 s after IDLE, so it can catch the transient STOPPED with our TrackURI still retained, and the advance then
  replaces their new stream. Today's 5 s poll rarely lands there.
- Fix: when a BUFFERING or PLAYING event we didn't cause arrives after the IDLE and before the confirm poll is decided,
  clear `idleEnd` and skip that one advance.
- Add tests:
  - IDLE near the end while `mayPlay()` is false: no SOAP at all. This is "nothing starts by itself", and only
    `holdAtEnd` is listed today.
  - IDLE, then the STOPPED poll, then a second wake before the play job: exactly one `SetAVTransportURI`.

**53-14 (Low) - a scope consequence the user should decide (a fourth question).** Under a limited room scope, an
outside join of a forbidden room makes the whole session leave: bookmark, Stop for the whole group, home paused
(`leaveIfNotAllowed` -> `leaveDisallowedRoom`, `PlaybackService.kt:349`, `:1902-1910`). With 53-b this happens about a second after a kid holds Play on
the forbidden speaker, not up to 60 s later. One sibling can then stop the other's music on purpose. The alternative
is `BecomeCoordinatorOfStandaloneGroup` on the forbidden joiner and play on. Ask the user. The default can stay as
today.

**53-15 (Low) - split and docs.**

- **Land the DIDL fix as its own round ahead of 53-a.** It is a few lines and a violation of the user's rule today, and
  53-a is large.
- **The docs each round must touch, which §4 doesn't list:**
  - `GuardsTest` pins the scope watch line (`GuardsTest.kt:389-391`), which §2.8 changes.
  - `docs/testing/emulator.md` §6h says the fake's track "ends by itself at its DIDL duration" (`:741`), and line 858
    says the DIDL has a `res duration` for a non-Storytel item. After the fix, a podcast on the fake ends only with
    `--duration N`. §3's emulator round must start the fake with it, or the STOPPED advance never happens there.
  - `music/CLAUDE.md`'s "trust is the system's CAs only" needs the socket's exception spelled out.
- **The service doesn't know when the sheet closes.** `ROOMS_REFRESH` marks the open; the close only shuts the A2DP
  proxy. "Busy" refreshes "per `Groups` event while it is open" therefore need a `sheetOpen` flag, set like
  `playerShown`.
- **Nits:**
  - `Room.named` duplicates `Room.names`. Make `names` pairs instead of adding a field.
  - `room_no_contact`'s English, "is it on?", differs from `headset_no_contact_row`'s "is it switched on?"
    (`values/strings.xml:82`). Use one wording.

## Tests - add to §3

- `SonosControllerTest` `d53`:
  - BUFFERING never changes `playWhenReady` or emits Paused/Resumed, and never clears `optPos` (53-1);
  - `optTr` beats a disagreeing event inside `SETTLE_MS` (53-1);
  - exactly one play for IDLE, the STOPPED poll and a second wake (53-13);
  - IDLE near the end with `mayPlay()` false sends nothing (53-13);
  - a takeover's BUFFERING after the IDLE clears `idleEnd` (53-13).
- The hysteresis rule replaces `SonosFloorTest` (53-2).
- `SonosSessionTest` `d53`: an uncounted poll leaves `frozen` and `lastPos` alone; the event wake is rate-limited and
  none happens in the backoff (53-8, 53-12).
- A pure `socketWanted` test (53-10). Liveness: a verb with no event means not healthy (53-6).
- 53-b:
  - `OutputRowsTest`: grouped away, and a stopped session, show no "+" or "−" (53-3);
  - `SonosControllerTest` `d53b`: an adopt or follow baseline sends no `SetVolume`, and only a new joiner is capped
    (53-4);
  - pending and failed as published state (53-7).
- `PlayBodyTest` through the shared `builtDidl` against generated goldens (53-5).

## Device checks - add to §5

- Check 1, the capture, also records:
  - the position at IDLE;
  - BUFFERING on a seek and on a resume;
  - a takeover from the Sonos app on our room;
  - the subscribe's initial status;
  - what our socket gets when our coordinator leaves by hold-Play.
- Check 7: the TrackURI and transport of the old coordinator right after "−" (53-9 e).
- Check 4: the bar has no freezes or jumps of a second, with the socket and with it blocked (53-2).
- Check 5: a Wi-Fi switch between two networks closes the socket (53-10). If the house has an S1 speaker, it parks
  after the refused connects (53-9 g).

## Questions for the user

- **Q1 (group volume), Q2 (a "−" on a lone room), Q3 (the hint line)** are real user decisions, and the defaults are
  sensible:
  - Q1: vibb's own-volume rule is unchanged, and a joiner is capped anyway.
  - Q2: the `Main` artboard shows no "−" on the lone Stua. The caption "Tas det siste ut, stopper den" can only be
    true with one, so this needs the user.
  - Q3: the sketch's paragraphs sit outside the sheet's frame.
- **Add Q4** (53-14): a forbidden room grouped in from outside under a limited scope. Should the whole session leave,
  as today, or should the forbidden room be taken out while the music plays on? Default: as today.

## Verdict

**Approve with changes.** Events only, UPnP deciding, and verbs as soco does them is the right design, and the DIDL fix
is correct. Before implementation:
- Settle how socket states map, BUFFERING above all, and `optTr`'s precedence (53-1).
- Replace the anchor and floor rule with hysteresis and a correct poll timestamp (53-2).
- Define the grouped-away sheet (53-3) and the joiner baseline (53-4).
- Make the golden DIDLs real (53-5).
- Add the liveness rule (53-6).
- Move "+" off our actor and publish its state (53-7).
- Take the Lows into the note as they apply.
