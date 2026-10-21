# 53 - Sonos: grouping in "Spill av på", grouping done outside the app, precision and push events (architect; revised after qa-53-design.md)

Asked 2026-10-20/21 (the user). Four parts, one note: (1) grouping in "Spill av på", the approved sketch ("digget jeg
designeforslaget for gruppering og ungrouping! Vi kjører med det!"; "Barnet skal få gruppere selv", "Ingen begrensing
på hvilke høyttalere"); (2) grouping done outside the app - the kids add and drop rooms by holding Play on a speaker,
which can change the coordinator ("Endres groups utenfor vår musikk app skal vi klare å følge med på hva som skjer i
appen vår"); (3) push events from the speaker's local WebSocket, measured on the user's speakers 2026-10-21 (PLAN,
"Design 53"); (4) precision and an immediate advance - plus the DIDL fix the user decided ("Ikke endre noe i didl!!",
PLAN "To decide": the app sends exactly vibb's fields). Music app only: no server, launcher, PWA, AIDL or contract file
changes (§2.11). Out of scope: the queue view (46 §1.1-§1.3, parked), WebSocket commands, the Sonos cloud API (the
user: not now). The design QA is `qa-53-design.md`; what it changed is listed in "Revision after QA" at the end.

## 0. Facts

### 0.1 The speaker's local WebSocket (measured 2026-10-21, `scripts/sonos-ws-probe.py`, `scripts/sonos-play-test.py`)

- `wss://<ip>:1443/websocket/api`, request header `X-Sonos-Api-Key: 123e4567-e89b-12d3-a456-426655440000` (the RFC 4122
  example UUID that `sonos-websocket` sends; the speaker accepts it locally - **public, not a secret**), subprotocol
  `v1.api.smartspeaker.audio`, a certificate the browser calls self-signed - in fact (measured later the same day) a
  leaf under the Sonos Device Authentication Root CA, its SAN naming the player (`urn:sonos:udn:RINCON_<...>`; "53-c
  status"). Messages are JSON arrays `[header, body]`; a command carries
  `namespace`, `command`, a `cmdId`, and `householdId` or `groupId`.
- An empty command `[{}, {}]` answers with the `householdId`. `groups:1 getGroups` / `subscribe` (householdId) give
  `groups` (`groups[]`: `id`, `name`, `coordinatorId`, `playerIds`; `players[]`: `id`, `name`, `websocketUrl`).
  `playback:1 subscribe` (groupId) pushes `playbackStatus` (`playbackState` `PLAYBACK_STATE_BUFFERING|PLAYING|PAUSED|IDLE`,
  `positionMillis`); `playback:1 getPlaybackStatus` asks for the current one (the probe sends it after subscribing).
  `playbackMetadata:1` pushes `metadataStatus` with only `container` = our `dc:title` (no track, duration or art,
  whichever DIDL). `groupVolume:1` pushes volumes.
- `playbackStatus` comes **on every change only** (no ticks; 8 events in ~60 s of commands), each with an exact
  `positionMillis`, 0.0-0.4 s after a command; the 1 s UPnP poll saw the same change 0.3-2.6 s after it. The natural end
  is `IDLE` the moment the audio ends (16.5 s after a seek to 15 s before the end).
- **One socket follows only the groups its speaker coordinates.** Subscribing to another group answers
  `groupCoordinatorChanged` with `groupStatus: GROUP_STATUS_MOVED`, that coordinator's `websocketUrl` and `playerId`.
  On the own group a membership change sends `groupInfo`, `groups` and a `global/groupCoordinatorChanged
  GROUP_STATUS_UPDATED`; the group id (`RINCON_...:100`, name "Sverre + 1") stays while the coordinator stays.
  `groups:1 modifyGroupMembers` added and removed a room in ~0.2 s on the user's speakers - proven, **not used** (§2.6).
- **Not measured yet** (§5 check 1 records them; each rule below that rests on one says what changes if it is false):
  (a) `positionMillis` at the natural end's IDLE (maybe 0); (b) whether a seek and a resume go through BUFFERING;
  (c) what a takeover from the Sonos app on our room looks like (IDLE then BUFFERING, or PLAYING -> BUFFERING ->
  PLAYING); (d) whether our socket gets a `MOVED` when our own coordinator leaves; (e) what our coordinator's transport
  and TrackURI read after it leaves (vibb's field note `sonosd.py:81-85` and the probe's gate: STOPPED, empty); (f)
  whether a subscribe pushes the current status by itself; (g) an S1 speaker or a firmware without port 1443.

### 0.2 UPnP grouping (soco, the reference vibb's sidecar uses)

- Join = on the **joining** room `AVTransport SetAVTransportURI(CurrentURI = "x-rincon:<coordinator uid>",
  CurrentURIMetaData = "")` (soco `SoCo.join`). A room that coordinated a stream of its own stops it.
- Leave = on the **leaving** room `AVTransport BecomeCoordinatorOfStandaloneGroup()` (soco `SoCo.unjoin`). When the
  leaving room is the coordinator, Sonos promotes a member and the stream moves there - exactly the migration vibb's
  stage B2 follows (`_sonos_stream_moved`), already ported.

### 0.3 The DIDL (vibb branch pipewire, `pi/daemon.py` `_sonos_body` 1423-1475, `pi/sonosd.py` `didl()` 346-379, `Session.play` 478-560)

vibb's body is `title` (the episode's), `album` (the library entry's name), `art` (the item's `image` only when it starts
with `http`), and **`duration_s` only in the Storytel branch** (`ep.get("dur_s")`; there `art = ep.get("art_url") or
art`). NRK series go out as `nrk_program` with no duration. The app: `PlayBodies.build` (`sonos/PlayBody.kt`) passes
`item.durationMs` for **every** kind (url, nrk_program, Storytel) and `SonosController.playIndex` (~779) calls it
without `art`. The phone has **no http picture to give**: a listing's `art` and the library's `cover` are 64-hex hashes
the server serves only to the phone's bearer (music/CLAUDE.md, design 26a "Merge of main with design 27"). So vibb's art
rule yields nothing here, which is also the user's "no art" (PLAN); the duration is the one real difference. (For
Storytel vibb may send `art_url`; the app sends no picture - less than vibb, never more.)

The app's own progress does not depend on the DIDL's duration (confirmed in the code): `SonosController.played()` seeds
the snapshot's `durS` from the listing, and `accept()` (the duration carry, 1053-1078) takes the listing's for our item
whenever the speaker reports none or `0:00`, flagged `durFromListing` so it never decides "heard to the end". Two
consequences of the fix: (1) wanted - a speaker that echoed our DIDL duration as `TrackDuration` (the fake does; real
ones likely) had that echo counted as the speaker's own measurement; after the fix only a real measurement counts
(vibb's rule, `_sonos_bookmark_now`'s "only when the SPEAKER measured it"); (2) handled in §2.4 - the session's tail
cadence (`cadenceMs`, `SonosSession.kt:449`) reads the speaker's own duration, so a podcast whose speaker reads `0:00`
throughout would lose the tail's 5 s polls.

### 0.4 What the app does today (read 2026-10-21)

- **Polling** (`SonosSession.cadenceMs`): PLAYING 15 s cruise, 5 s within `TAIL_FAST_S` (45 s) of the speaker's own
  duration, 5 s for `VERB_FAST_MS` (30 s) after a verb or an arm, PAUSED 15 s, STOPPED 60 s (5 s while a migration
  window is open), UNREACHABLE backoff to 300 s; disarm after 10 min neither PLAYING nor PAUSED. Every live poll writes
  the bookmark (`pollTick` -> `bookmarkNow(false)`). Freshness = measured within the cadence chosen after that poll +
  10 s (`Fields.fresh`). `measuredAt` is stamped at the end of `classify`, after GetPositionInfo, GetTransportInfo and,
  on an aux poll, GetZoneGroupState and GetVolume (`SonosSession.kt:399`).
- **The advance** (`pollTick` 1041-1044): STOPPED after a poll saw the position within `ENDS_NEAR_S` (20 s) of the end
  -> `advance()` (repeat, "Til slutten", gated). Silence between episodes is the time to the next poll: up to 5 s with
  a speaker-known duration, up to 15 s without (an extension-less URL reads `0:00`), plus the play.
- **Position**: the poll's `RelTime` (whole seconds) anchored at its `measuredAt` and extrapolated (`position()`,
  `SonosSpec.positionAt`, capped at `MAX_EXTRAPOLATE_MS` 60 s); a seek target held up to 12 s (`optPos`), the pause
  freeze (`frozen`), a play's seed held until the speaker shows our uri or `SETTLE_MS` (8 s) after the answer
  (`pending`). TRANSITIONING never changes the shown state: `classify` keeps the last real one (G1-d,
  `SonosSession.kt:339-345`) and `spec()` shows it as BUFFERING with the held `playWhenReady` (`SonosController.kt:374`).
- **Groups**: the sheet's rows are the cache's groups (`SpeakerCache.rooms`), refreshed when the sheet opens
  (`ROOMS_REFRESH` -> `RoomDirectory.refresh`), by a scan, and - only under a limited room scope while a room plays -
  every 60 s (`PlaybackService` `SCOPE_WATCH_MS`, pinned by `GuardsTest.kt:389-391`). The controller's label `name`
  changes only at a migration follow. No grouping verbs exist. Custom session commands answer `RESULT_SUCCESS` at once
  and run later under `switching` (`PlaybackService.kt:546-570`).

| Outside case | Today |
|---|---|
| A room joins our group | Not seen until the sheet opens (60 s under a limited scope); the player's label stays "Stua"; the joiner keeps its own volume, above the cap or not. |
| A member leaves | The same. |
| Our coordinator leaves (the stream moves) | Our uid reads STOPPED with an empty TrackURI at the next poll (5-15 s), then `maybeProbeMigration` (3 tries, at the 5 s cadence) and `follow()`: adopt, `renderer.json`, the scope's look. 5-25 s in all. |
| Our room joins another group | `x-rincon:` on the next poll (up to 15 s) -> `GROUPED_AWAY` "Spilles i en annen gruppe", the position hidden, nothing acts. |

## 1. Shape

One WebSocket per Sonos session, to the speaker the controller commands, carrying **events only**; UPnP does every
command and stays the only ours-verifier and the only source of a bookmark. An event (a) re-bases the shown position and
confirms our own verb's state at once, (b) wakes the poller for a **confirm poll** - so everything that decides (ours,
the advance, the migration follow, grouped away) still decides on a UPnP snapshot, only sooner. If the socket is gone the
app is exactly today's plus the 1 s display poll of §2.4: **we lose speed, never a feature.**

## 2. Decisions

### 2.1 The DIDL fix (round 53-0, ahead of 53-a)

- `PlayBodies.build`: `durationS` = the listing's duration **only** for `Source.STORYTEL`; `null` for `url` and
  `nrk_program`. `art` keeps vibb's rule (http only); the call site in `SonosController.playIndex` passes `art = null`
  explicitly with a comment: the phone holds hashes only (§0.3). `album = entry.name`, the title, no artist - unchanged.
  `didl()` (`Didl.kt`) is **not touched**. The comments in `PlayBody.kt` ("The listing's duration rides along") and design
  26a §2.5 are corrected.
- `PlayBody` gains `cadenceHintS` (the listing's duration for every kind) - **never** passed to `didl()`, read only by
  `SonosSession.played(uri, hintS)` for the tail cadence (§2.4). Its `toString` prints it like `durationS`.
- **One DIDL builder for production and the test**: the per-kind construction moves out of `SonosTransport.play`
  (105-131) into a pure `builtDidl(body: PlayBody, uri: String): String` (url: `didl(uri, title, artist, album, art,
  durationS)`; nrk_program: the same with `NrkService.UPNP_CLASS`, `PROTOCOL`, `DESC`). `play()` calls it with the
  built uri; the test calls it with the static `NrkService.programUri(series, programId, serial)` and a fixed serial, and
  for Storytel with `body.copy(uri = <a fixed extension-less fake URL>)`.
- **Generated goldens**: `scripts/sonos-play-test.py --golden DIR` writes `didl_url.xml`, `didl_nrk.xml`,
  `didl_storytel.xml` with vibb's per-kind arguments from `Session.play` (url: `didl(uri, title, album=, art=)`;
  nrk_program: the show class, the protocol, the desc, no duration; Storytel: `duration_s`) for fixed inputs (no http
  art, a fixed serial, the fixed fake Storytel URL `https://cdn.example.invalid/book?token=FAKE-SIG-1`). They live in
  `src/test/resources/sonos/`; `PlayBodyTest` compares `builtDidl(PlayBodies.build(...), uri)` with each byte for byte.
  `SonosTransportTest:144` (`body.copy(durationS = 1453.0, ...)`) and `PlayBodyTest:26,151-154` change with the fix.
- `scripts/fake-sonos.py` keeps reading `res@duration` (a Storytel book) and `--duration` otherwise; a podcast on the
  fake now ends only with `--duration N`, so docs/testing/emulator.md §6h (`:741`, the track ending at its DIDL duration;
  `:713`, `--duration`'s default - a podcast reads `0:00:00` and never ends; `:858` stays: the Storytel book keeps its
  duration) says so and starts the fake
  with `--duration` for the advance steps.

### 2.2 The socket (`sonos/SonosEvents.kt` pure, `sonos/SonosSocket.kt` OkHttp)

- **Dependency**: OkHttp 4.12.0's own WebSocket (`OkHttpClient.newWebSocket`), already on the classpath
  (`libs.squareup.okhttp`); JSON with kotlinx.serialization's `JsonElement` (already there). **No new dependency**, so
  no new `verification-metadata.xml` entries (root CLAUDE.md, "Gradle build chain").
- **Lifetime** - a pure rule, `socketWanted(armed, wifiNetwork, closed, uid)` (since 53-c's fix round the park is the
  socket's own: it holds for an ip, "53-c status"): open while the session
  is **armed** (a play or an adopt armed it; the same rule as polling, battery), Wi-Fi is up and the uid isn't parked;
  closed at the disarm (`Tick.Disarmed`), `stop()`, the park, the hand-off and `release()`. **Any change of the Wi-Fi
  network** (not only `onLost`: Android may deliver B's `onAvailable` before A's `onLost`, `SonosHome.kt:35-43`) closes
  the socket and opens it again on the new one. There is no socket while the phone renders - only a `SonosRenderer`
  owns one - and none from the standalone suspension path. The renderer reads `Fields.armed` from each poll result on
  main and re-evaluates the rule.
- **Target**: the cached ip of `controller.uid` (`SpeakerCache.resolve`), built with `HttpUrl.Builder` (`https`, the ip,
  port 1443, `/websocket/api`; OkHttp maps it to `wss`), as every URL here. **A URL from a message is never connected
  to**: a `MOVED`'s `websocketUrl` and the `groups`' `websocketUrl`s are read for the player id only. When the
  controller's uid changes (a migration follow), the socket is closed and opened to the new uid's cached ip.
- **Handshake** (the pure reducer `SonosEvents.Plan`, mirroring `sonos-ws-probe.py`): the empty command -> the
  `householdId` -> `groups:1 getGroups` -> our player = the `players[]` entry whose `id` equals `controller.uid` (fallback:
  whose `websocketUrl` host equals the ip we dialled) -> its group -> `playback:1 subscribe` (that `groupId`), then
  `playback:1 getPlaybackStatus` (its reply is handled as an event), and `groups:1 subscribe` (householdId). Not
  subscribed: `playbackMetadata:1` (only our title; reconsidered if §0.1 (c) shows a takeover without an IDLE -
  **subscribed since 53-d** for the container's object id, our DIDL's `vibb-1`: "53-d status") and
  `groupVolume:1` (no reader: a group's volume is computed from each room's own, §2.12 - Sonos' group volume can't
  respect a cap per room). A `groups` event re-subscribes
  `playback:1` only when the group holding our player has another id **and its `coordinatorId` is our player** (while
  grouped away it would only earn a `MOVED` back). A `success: false` answer to our playback subscribe, or a `MOVED` for
  it (our uid no longer coordinates), leaves the socket open for `groups` but **not healthy**.
- **Health**: healthy = open, the playback subscription of the group our uid coordinates acknowledged, the
  `getPlaybackStatus` reply received, and **live**:
  - *The liveness rule*: a verb of ours that must change the speaker's state - a pause on a confirmed PLAYING, a resume
    on a confirmed PAUSED, a play of a new item or a step (each against the socket's and the poll's agreeing state) -
    must be followed by a `playbackStatus` within 3 s (measured: 0.0-0.4 s), or the socket is dead - closed and counted
    as a failure. A seek while paused, a pause while already paused or any other verb the speaker may answer without a
    change doesn't count (no event is owed). A firmware that acknowledges
    but never pushes is caught at the kid's next tap, not "when the state happens to change twice".
  - *Disagreement*: two polls in a row that see ours-PLAYING vs a socket state of PAUSED/IDLE (or the reverse), each 3 s
    or more after the socket's last event, also mark it dead - it covers changes made outside the app.
  - OkHttp's `pingInterval(30 s)` (the probe's heartbeat, which ran for minutes on the user's speakers) closes a socket
    whose pong doesn't come.
  - Health is a `@Volatile` flag the session reads through an injected `() -> Boolean` (§2.4, threading).
- **Failure and backoff**: a TCP or TLS failure, a close, a dead ping, a liveness miss -> reconnect after 1, 2, 5, 15,
  60 s, then every 300 s while armed; the sequence resets only after a socket stayed healthy **and** passed a liveness
  check, so a silent socket is never rewarded. An HTTP answer instead of the upgrade (401/403/404 - the key or the
  endpoint closed by a firmware update) parks that uid for 6 h; so do **five refused connects in a row** (an S1 speaker
  or a firmware without port 1443 refuses TCP and never answers HTTP). Process memory only, nothing persisted. Every
  failure leaves the app on today's polling plus §2.4.
- **Trust** (the self-signed certificate): the socket's own client - `home`'s SOAP client `.newBuilder()`, unbound to
  any `Network` (26a §6.5), `connectTimeout` 2 s (the inherited 5 s read timeout is cleared by OkHttp after the
  upgrade) - gets an `X509TrustManager` that accepts the speaker's chain **and a host gate before any connect**: the
  host is an IP literal (`isIpLiteral`) in a private range (`isPrivateAddress`: RFC 1918, link-local, IPv6
  ULA/link-local), it equals the cached ip of the uid we command, the port is 1443, the path `/websocket/api`; the
  `HostnameVerifier` accepts exactly that ip. OkHttp keys pooled connections by `Address` (the socket factory and the
  verifier included), so no other client ever reuses this connection. No global trust-all: the SOAP client, the media
  clients, the public-only client and the network security config (system CAs only) are untouched; music/CLAUDE.md's
  "trust is the system's CAs only" gets this one exception spelled out. Why this is enough: the socket only reads, and
  what it says is checked - a spoofed event can at worst re-base the bar until the next poll or wake a poll (rate
  limited, §2.3); the IDLE advance and every ours decision are UPnP's, and UPnP to the same address is already
  cleartext. Certificate pinning would add nothing against an attacker who can already answer port 1400. The lint ids
  for a custom trust manager are suppressed on that one class with this reason. **Superseded by round 53-c** (the
  same day, once the speaker's chain was read): the trust manager verifies the chain against the pinned Sonos root
  alone and the leaf's `urn:sonos:udn:` against the uid we dial - cheap, and it keeps a spoofer on the LAN from
  feeding the bar at all ("53-c status").
- **Threading**: OkHttp's reader thread parses (messages over 64 KB dropped), stamps `SystemClock.elapsedRealtime()` at
  receipt, and posts a compact `SocketEvent` to the main thread (the renderer's `Handler`), where the controller lives.
  Commands are sent from the listener (OkHttp's `send` is thread-safe). The socket never goes through the actor: SOAP
  ordering is the actor's business, events are not commands. Anything an event asks of the session (the probe's
  candidate) goes through the actor (`work.submit { transport.hint(uid) }`): `SonosSession` stays touched only under
  `SonosTransport`'s mutex (`SonosSession.kt:10`).
- `SocketEvent` = `Playback(groupId, state, positionMs, at)`, `Groups(ourGroupId, ourCoordinatorId, members, groups)`,
  `Moved(playerId)`, `Health(healthy)`. Logging through `SonosHome.log` with fixed words, uids and states - never a
  body (there are no secrets in one, but titles are the kid's listening).

### 2.3 What an event does (`SonosController.onSocket`, main thread)

- **Confirm polls, rate limited**: an event wakes the poller (`SonosPoller.wake()`, conflated) at most once per 1 000
  ms (`EVENT_WAKE_MIN_MS`), an IDLE or a `Moved` at once; **no event wakes it while the last poll failed** (the
  UNREACHABLE backoff stands). A poll of a speaker on the LAN answers in ~0.1-0.3 s.
- **State mapping** (`Playback` of our subscribed group, while `session` and not `closed`): only PLAYING, PAUSED and IDLE
  are states (`TransportState.PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`). **BUFFERING is TRANSITIONING**: it never changes
  the effective transport or `playWhenReady` (G1-d, as `classify` and `spec()` treat TRANSITIONING) - at most it stops
  the anchor's motion and shows the phase BUFFERING with the held `playWhenReady`. So a seek, "15 s tilbake", a resume
  or a rebuffer never emits `Paused`/`Resumed`, never flips `IsPlaying`, and never reads as a USER pause that cancels
  the sleep timer (`PlaybackService.kt:1390`).
- **Our own verb wins**: `opt(now)` keeps its precedence. A socket state equal to `optTr.state` confirms and clears it
  (the play button settles in 0.0-0.4 s); a different one is ignored until `SETTLE_MS` passes or a poll decides, as
  polls are today (`SonosController.kt:1026`). Outside a verb's window a newer socket state is the effective transport
  under the same gate as the anchor (below): a pause from the Sonos app or the speaker's button shows at once.
- **Position** (§2.4's re-anchor): PLAYING (moving) and PAUSED (not moving) are measurements; BUFFERING only stops the
  motion at the extrapolated value. A measurement counts when the snapshot is fresh and ours, `pending` is null, and no
  IDLE and no **BUFFERING we didn't cause** (none of our verbs within `SETTLE_MS`) came since the last ours poll - a
  track boundary or a takeover needs a poll's ours first (§0.1 (c): whichever way a takeover looks, its BUFFERING blocks
  the anchor). An event that lands while `pending` is set and our play has answered (`pending.uri != null`) is
  **parked**; the confirm poll that clears `pending` as landed and ours applies it. One that lands before the play
  answered (the old track's IDLE) is dropped.
- **The seek hold**: `optPos` is cleared only by a PLAYING or PAUSED event within 1 000 ms of the target (the socket's
  position is exact; 6 s was sized for whole-second polls) - a BUFFERING carrying the pre-seek position never clears
  it. Polls keep `SEEK_TOL_S`.
- **IDLE near the end of our item -> advance at once**: the confirm poll goes out at once, and `pollTick`'s existing
  rule (`STOPPED && endsNear > 0 && playing != null`) decides - `endsNear` already holds at the end (the tail's 5 s polls,
  now also for a speaker-silent duration via the hint, §2.4). No extra flag (QA 53-13). A confirm poll that still reads
  PLAYING or TRANSITIONING asks one more after 1 s; then the normal cadence. **The takeover race**: when a BUFFERING or
  PLAYING we didn't cause arrives after the IDLE and before a poll has decided, the controller sets `skipAdvance = gen`
  and that one STOPPED decides nothing (someone else started a stream on our room at the episode's end; their new
  stream is never replaced); the next ours poll clears it. So an IDLE from anything but the natural end decides
  nothing: our own SetAVTransportURI (`pending`), the stall retry's Stop (it runs inside a poll job, so the confirm poll
  after it sees PLAYING), our room joining another group (the poll reads `x-rincon:` - not ours), a stop mid-item (not
  near the end), a takeover. `advance()` is unchanged: `mayPlay()` (an IDLE while playing isn't allowed sends nothing
  at all), "Til slutten" (`holdAtEnd`, checked first), repeat (`advanceIndex`), the queue's end; `playIndex` bumps the
  generation, so a second wake after the advance's play went out can't advance again. If §0.1 (a) shows IDLE carries
  the real end position, nothing changes; if 0, nothing changes either (the decision is the poll's).
- **The socket never writes Room.** The bookmark stays `bookmarkNow` on a UPnP snapshot (measured, ours-verified,
  `SonosBookmarks`' hold and regression guard): an event can't name its stream.
- `Groups` and `Moved` (53-a): the confirm poll only. 53-b adds §2.8.
- `Health` -> the session's cadence (§2.4) and the renderer's log line.

### 2.4 Precision

- **Re-anchor with hysteresis** (a pure rule in `logic/`, `sonosAnchor(old, measurement, now)`; replaces "the newest
  wins" and a fixed floor): a new measurement (socket or poll) of the **same item** (same `playing.itemKey` and uri, same
  generation, no seek, step or play since) in the **same motion** (moving or not) re-bases the anchor at the old
  extrapolation's value at the measurement's time when the two are within 1 000 ms - the bar stays monotone and smooth,
  never frozen and never stepping. A difference of 1 s or more, a change of motion, a seek (also "15 s tilbake"), a
  step, a new item and a play move the anchor to the measurement at once. Every measurement re-bases, so
  `MAX_EXTRAPOLATE_MS` (60 s) is never reached while measurements come. **Under a healthy socket a poll only re-bases
  when it differs by 1 s or more** (the event is exact; a poll is not). Display only (`positionMs()` stays on
  `GuardsTest`'s allowlist; no reader of it persists).
- **The poll is stamped where it measured**: `classify` takes `positionAt = elapsedMs()` right after GetPositionInfo
  returns, and a poll's display anchor is `RelTime + 0.5 s` at that stamp (`RelTime` truncates; half a second is its
  expected error). Display only - never in a `Fields` value the bookmark reads ("never a guess in Room"). `measuredAt`
  (freshness, the bookmark's age term) moves to the same stamp, the earlier and truer time.
- **The 1 s display poll only without a healthy socket**: while the player screen shows (`PlayerHub.playerShown`, a
  `MutableStateFlow<Boolean>` set by `PlayerScreen` while composed and the activity is resumed; same process) and the
  last snapshot is ours, fresh and PLAYING, the session polls every 1 000 ms (`POLL_PRECISE_MS`) - only when the
  socket is **not** healthy. With a healthy socket every change is pushed with an exact position. The flag flipping
  on wakes the poller. `playerShown` and the socket's health reach the session as injected `() -> Boolean` readers (a
  `StateFlow`'s value and a `@Volatile`), never as calls from main into the session.
- **The tail cadence's hint**: `cadenceMs`' tail rule uses the speaker's duration, else `cadenceHintS` (§2.1) - cadence
  only, never published in `Fields`, never the bookmark's. A podcast whose speaker reads `0:00` keeps today's 5 s polls
  near its end (today it probably got them from the echoed DIDL duration). An adopt has no hint (rare; 15 s).
- **What the extra polls must not change** (the confirm polls and the 1 s polls are polls like any other):
  - Freshness: `measuredCadenceMs` is the normal rule's cadence, never 1 s - else the screen going off (the 1 s polls
    end) would let the snapshot read stale for seconds, `showsPlaying` false and the wake lock go.
  - The stall retry (Stop, Play, seek): a poll counts only when it is `POLL_MS` (5 s) or more after the last counted
    one, and an **uncounted poll leaves both `frozen` and `lastPos` alone** (`SonosSession.kt:467`): the rule stays "the
    same RelTime at polls 5 s or more apart".
  - The bookmark: a non-forced write from `pollTick` at most every 5 s (`BOOKMARK_MIN_MS`) - **except** when the
    transport changed since the last write (a pause from the Sonos app): the pause point is the bookmark that matters
    most.
  - The migration probe: a try only 1 500 ms or more after the last (`MIGRATE_SPACING_MS`); `MIGRATE_TRIES` stays 3.
  - The aux reads (GetVolume, the group's coordinator): every `AUX_EVERY` polls but not more often than every 60 s.

### 2.5 The cadence with a healthy socket: unchanged

**Superseded by round 53-d** (the user asked to lean more on the socket once it proved itself; "53-d status" below:
PLAYING's cruise and PAUSED check once a minute while the socket is healthy, the rest as here). Decided at first: the 15 s cruise, the tail's 5 s, the verb window and the rest stay as they are, socket or not. The poll is the
only ours-verifier (an event names no stream), the bookmark's clock (a crash loses at most one cadence) and the
fallback that must already be right when a socket dies silently. The saving would be two SOAP calls on the LAN every
15 s, while what costs battery during a session - the partial wake lock and the Wi-Fi lock (`SonosLocks`) - is held
while playing either way.

### 2.6 The grouping verbs: UPnP, as soco

Join (`x-rincon:`) and leave (`BecomeCoordinatorOfStandaloneGroup`) go over UPnP (§0.2) through two `SpeakerControl`
methods (`joinGroup(coordinatorUid)` = the `SetAVTransportURI` with empty metadata, `becomeStandalone()`), each against
the cached speaker of that room with `verify = true` (the uid at the ip, as a play) and a **2 s budget per call**. Why
not the WebSocket's `modifyGroupMembers`, which works on the user's speakers: the local WebSocket is not an official
third-party API (the key is the RFC 4122 example UUID a speaker happens to accept) and may close with a firmware update;
every other command already goes over UPnP with its timeouts and gates; and if the socket dies we lose only speed,
never a feature - grouping included.

**Where they run** (QA 53-7): verbs on **other** rooms - every join (with its cap), and a "−" on a member that isn't our
coordinator - run in a job of their own on IO (`GroupVerbs`, one `Mutex`, sequential per tap), not in our room's
actor, so polls, Pause, the volume keys and a hand-off never wait behind a switched-off room. Only a **"−" on our
coordinator** runs in our actor (`SonosTransport.leave(uid)`), because it must order against our plays and starts the
follow. The service's `switching` lock is **not** held across a join: the job checks the gates when it starts (§2.7) and
the actor's verbs to our uid need no order against it. **The job captures** the coordinator uid and the controller's
generation at its start and **re-checks both, with the gates, before each member**: a follow, a switch, a new play or a
release in the meantime ends it with the rest of the row unsent (no room ever joins a coordinator we left).
`ROOM_LEAVE` is refused for a uid that isn't a member of our group as the topology has it now.

### 2.7 "Spill av på" with grouping (53-b; the sketch's three artboards, `Main`, `Grouped`, `Busy`)

The sheet's look stays the app's (`SheetRow`, `Palette`, the 34 dp glyph disc, `OUTPUT_SHEET_MAX_SHARE`); the sketch
gives the layout, the words and the behaviour. A pure `groupSheet(...)` in `ui/OutputRows.kt` decides every row from
our uid, the renderer's kind, the controller's session state, `showsPlaying` and `RoomsView`; `OutputSheet` only draws
it.

- **Grouping is offered** (+ and −) only when all hold: a room renders, our uid is a row's uid, that row's members
  contain our uid, the controller has a **live** session (fresh, ours, PLAYING or PAUSED), and the rooms aren't stale.
  Otherwise the rooms are today's rows - a group is one row, no + and no −:
  - the phone or a headset renders;
  - a session that is stopped, ended, disarmed or not started (a + would stop another room's stream and join it to a
    silent coordinator). **A paused session counts as live** (revision check N4, accepted): "+" on it joins the room,
    which stays silent until the kid resumes, and a busy room's own stream stops at once - the kid asked for that room;
  - **grouped away** (§2.8 case 4): our room sits in another coordinator's row ("Barnerommet + Stua", uid Barnerommet);
    nothing is selected, that row's subtitle is the player's line "Spilles i en annen gruppe", and a tap on it is
    today's `OUTPUT`;
  - the rooms are stale ("Sonos · svarer ikke").
- **When it is offered**:
  - **Our row is selected.** A room alone: its name, "Spiller nå" while `showsPlaying`, else "Sonos". A group: one
    selected block - the group glyph (two speakers, `Icons.roomGroup`), the label "Stua + Kjøkken", "Spiller nå i
    begge" (two rooms) / "Spiller nå i alle 3" (three or more) while playing, else "Sonos"; under it one line per
    member, coordinator first (indented 38 dp, a 1 dp divider, the name at 15/700), each with a round 48 dp "−"
    (`Icons.remove`, content description "Ta Stua ut av gruppen"). Member names are not buttons.
  - **Every other row** - a room or another group's row - gets a round 48 dp "+" (`Icons.add`, peach outline,
    "Spill også i Kjøkken" / "Spill også i Stua + Kjøkken"); its subtitle is "Trykk for å bytte hit", or "Spiller noe
    annet" when its coordinator plays (below). **Tapping the row itself still switches there** (`OUTPUT`, today's
    hand-off and play); only the button groups.
- **+** = `Commands.ROOM_JOIN` with the row's uid: each member of that row joins our coordinator, **members first, the
  coordinator last** (a coordinator joined first would hand its stream to a member, which would then sound for a
  moment), each capped first (§2.9). A room playing something else may be added: its own stream stops and it joins (the
  user's decision; no confirm). It is a play path: in `Commands.PLAYS` (refused while suspended) and, in the job, behind
  `playAllowed`, the scope (every member allowed) and the offered-rule above, read again when the job starts. The
  service refuses `ROOM_JOIN` for any row whose members include our uid.
- **−** = `Commands.ROOM_LEAVE` with the member's uid: `BecomeCoordinatorOfStandaloneGroup` on it; it stops, the rest
  play on. On **our coordinator** Sonos moves the stream to a remaining member and the app follows it (§2.8 case 3;
  the controller knows it is coming: the poll burst and the first remaining member as the probe's candidate, and the
  advance guard of §2.8). A group block always has two or more members; once one is left it is that room's ordinary
  selected row with no "−" - so a "−" never leaves nothing playing, and stopping stays the play button's (decided by
  the user, §7 Q2).
- **"Spiller noe annet"**: when the sheet opens (`ROOMS_REFRESH`), after the topology refresh, `RoomDirectory` asks
  each other row's coordinator `GetTransportInfo` - in parallel on IO, 1 s each, 2 s in all, at most 8 - and
  publishes the PLAYING ones as `RoomsView.busy` (a set of uids). Once per opening and per `Groups` event while the
  sheet is open: the service knows that from `PlayerHub.sheetOpen` (a `MutableStateFlow<Boolean>`, set by the view
  model's `outputSheetOpened`/`outputSheetClosed`, like `playerShown`). Never on a timer, never while the sheet is
  closed.
- **Pending and failure are published state** (as design 52's `HeadsetLine`): the service keeps `groupPending` and
  `groupFailed` (uid -> expiry) in `RoomsView`/`PlayerHub.State`; the custom commands keep answering `RESULT_SUCCESS` at
  once, as every custom command does. A tapped + or − shows the `Spinner` in its place until the topology shows the
  change or 8 s; a verb that got no answer puts "Fikk ikke kontakt med Kjøkken - er den på?" on that row until the next
  tap on it or 30 s. After every verb the job runs one `refreshQuietly()` (the `Groups` event does it too when the
  socket is up).
- **The scope**: the PWA's room scope still limits the sheet (`allowedRooms`: a row only when every member is allowed),
  so a + exists only on allowed rows and **the app's own join never adds a room outside the scope**. The one exception
  is our own row: a room the kids grouped in by hand stays a member of it (§2.8, the user's answer to Q4), so our row
  is kept by `sessionAllowed` and that room shows in the block with its "−" like any member.
- **No hint lines** (decided by the user, §7 Q3): the sketch's bottom paragraphs on boards 1-4 ("+ betyr «spill også
  her» ...", "− tar et rom ut ...", "Et rom som spiller noe annet ...", "«Begge» flytter alle rommene ...") are
  captions; the sheet shows none of them. The buttons and sliders carry their content descriptions for TalkBack.
- **The order stays design 52's** (the user, 2026-10-21: the phone, wired - minijack/USB-C - and Bluetooth outputs stay
  above the Sonos rooms): `outputSheetRows` keeps the phone, the routed outputs (Bluetooth, wired), the paired headsets
  not routed now, then the rooms - our group block with its "Begge"/"Alle" line, its member lines, their "−" and
  sliders among them, wherever its row sorts - then "Se etter høyttalere". The group block and its sliders are one
  `SheetItem.Group` in the rooms' part, never above an output; `OutputRowsTest` pins it (§3).
- `Room.names` becomes the known members' `(uid, name)` pairs, coordinator first (its readers take the names); no
  second field.
- Strings (`values` + `values-nb`): `room_switch_here` "Tap to switch here" / "Trykk for å bytte hit",
  `room_playing_now` "Playing now" / "Spiller nå", `group_playing_both` "Playing in both" / "Spiller nå i begge",
  `group_playing_all` "Playing in all %1$d" / "Spiller nå i alle %1$d", `room_busy` "Playing something else" /
  "Spiller noe annet", `room_join` "Also play in %1$s" / "Spill også i %1$s", `room_leave` "Take %1$s out of the group" /
  "Ta %1$s ut av gruppen", `room_no_contact` "Couldn't reach %1$s - is it switched on?" / "Fikk ikke kontakt med %1$s -
  er den på?" (design 52's `headset_no_contact_row` wording). Glyphs `Icons.add`, `Icons.remove`, `Icons.roomGroup` are
  stroked paths in `ui/Theme.kt` like `roomSpeaker` (our own drawing of the sketch's SVGs, GPL).
- `FitTest`: the group block with two and three members and the + row at 320/360/411 dp; the bar's room label with a
  four-room group (cut with an ellipsis, never wrapping).

### 2.8 Grouping done outside the app (53-b)

| Case | 53 |
|---|---|
| A room joins our group | `Groups` -> one `refreshQuietly()` (debounced 300 ms: `groupInfo`, `groups` and `groupCoordinatorChanged` come together) -> `rooms.state` -> the sheet (open or not), the label, the scope check, the joiner capped once (§2.9). |
| A member leaves | The same. |
| Our coordinator leaves | `Moved(playerId)`, or - if no `MOVED` reaches us (§0.1 (d)) - a `Groups` event in which our uid no longer holds our former members: the candidate is the coordinator of the group that now holds them. Then the **poll burst** (now, +1.5 s, +3.5 s: `poller.wake()` and `work.after`) and the candidate handed to the probe through the actor (`transport.hint(uid)`, asked first); the existing ours-gate (`sameStream`) decides; `follow()` adopts, writes `renderer.json`, `onFollowed` runs the scope look; the socket moves to the new uid. ~1-2 s in all. |
| Our room joins another group | `Groups` / `Moved` -> the confirm poll reads `x-rincon:` -> `GROUPED_AWAY` at once; nothing is sent, nothing follows (the stream there isn't ours); the socket stays on our room (not healthy: our uid coordinates no subscribed group) and re-subscribes when a `groups` event shows our room coordinating again. |

- **The advance guard after a group change of ours** (QA 53-9 (e)): for `SETTLE_MS` (8 s) after a `Groups` event that
  changed our group, or our own "−" on our coordinator, a STOPPED of ours near the end first runs the migration probe
  (with the candidate) before `advance()`; only when no coordinator carries our stream does the advance run. Today's
  probe gate is "STOPPED with an empty TrackURI"; inside this window it also takes "STOPPED with our TrackURI kept". So
  if the old coordinator keeps its TrackURI after leaving, Stua never starts the next episode while Kjøkken plays the
  current one. The window reaches the session the way the candidate does: through the actor
  (`transport.groupChanged(at)`, beside `transport.hint(uid)`), never a call from main into `SonosSession`.
- **The label follows**: `SonosController.relabel(label)` from each `rooms.state` change (`home.label(controller.uid)`:
  the row's joined label, else the cached name), so the bar and the player read "Stua + Kjøkken" as it happens.
  `renderer.json`'s name is left as written (a pick, a follow): the label is recomputed from the cache at every start.
- **Without the socket** the same paths run from the **group watch**: `PlaybackService`'s 60 s look
  (`SCOPE_WATCH_MS`, one GetZoneGroupState, never discovery) now runs while a room plays **whatever the scope** when the
  socket isn't healthy, and under a limited scope always (as today, a belt for the parent's rule). `GuardsTest`'s
  pinned line (`GuardsTest.kt:389-391`) changes with it. The −'s own follow doesn't need it: the burst and the
  candidate come from the tap.
- **A room grouped in by hand is respected, whatever the scope** (the user's answer to Q4, 2026-10-21: "det barna gjør
  manuelt bør respekteres"). Today a forbidden room joining our group from outside reaches four places, each through
  `groupAllowed`/`mustLeaveRoom` (`sonos/RoomScope.kt`), which require every member of our group to be allowed:
  1. `leaveIfNotAllowed` -> `leaveDisallowedRoom` (`PlaybackService.kt:349`, `:1902-1910`; run from the `rooms.state`
     collector `:300`, the library collector `:309` and `onFollowed` `:1817`): the session leaves - bookmark, Stop of
     the whole group, the phone paused, "Rommet er ikke lenger tillatt";
  2. the controller's belt `mayPlay` (`roomAllowed = groupAllowed(roomScope, rooms, it)`, `PlaybackService.kt:1815`,
     `SonosRenderer.kt:134`): every later play - the next episode's advance, a skip, a resume - would be refused;
  3. the sheet's filter `allowedRooms` (`PlaybackService.kt:1763`): our own row would vanish from the sheet;
  4. `toRoom`'s check (`PlaybackService.kt:1976`), which runs before its same-room shortcut: a tap on our own row would
     read `ROOM_NOT_ALLOWED`.

  **The change**: the service keeps `acceptedRooms` - the rooms that joined our group **from outside the app** during
  this renderer's life - and the session's own checks use `sessionAllowed(allowed, rooms, uid, accepted)` =
  `allowed == null || roomMembers(rooms, uid).all { roomAllowed(allowed, it) || it in accepted }` (in `RoomScope.kt`;
  `mustLeaveRoom` gains the `accepted` argument):
  - **Who is accepted**: exactly §2.9's joiners - a uid in our group's members that is not in `knownMembers` (the
    controller's pure `joiners(known, members, ourUid)` decides both the cap and the acceptance), plus, at an adopt or
    a follow, the baseline's members other than our coordinator (the app put none of them there). Unknown uids (no
    cache row) count like any other joiner. A room leaves `acceptedRooms` when it leaves our group, and the set is
    dropped with the renderer (a switch home, to another room, a release). Memory only.
  - **Order**: the service's `rooms.state` collector hands the new members to the renderer (which updates
    `knownMembers`, `acceptedRooms` and the caps, on main) **before** `publish()` and the leave check, so an outside
    joiner is accepted before anything can judge it.
  - Places 1-3 use `sessionAllowed` for the session's own uid: no leave, no refused advance, our row stays in the
    sheet with the accepted room in its block (its "−" like any member's). Place 4 checks `uid == current renderer's
    uid` first (the same-room shortcut), then `groupAllowed` as today for any other room.
  - **What the scope still does**: it limits every room the app itself starts or adds - a pick of another row
    (`toRoom`, `groupAllowed`, today's), the rows that get a "+", the service's belt on `ROOM_JOIN`. A parent's **new**
    scope (the library collector) still makes the session leave when it forbids our coordinator or a member the app put
    there (not accepted); an accepted room never does.
  - **If our coordinator leaves and the stream moves to an accepted room** (§2.8 case 3, a kid's hold-Play), the follow
    lands on it and the session plays on there: it is accepted, and what the kids did by hand is respected. A follow to
    a room that is neither allowed nor accepted leaves, as today.
  - With the socket (53-a) and 53-b's `Groups` handling all of this happens about a second after the hold-Play; without
    the socket, at the group watch (60 s).

### 2.9 The volume cap on joining rooms

"The volume cap set on each joining room": a room that joins our group - by our + or from outside - and is louder than
the parent's cap (`volume_cap_pct`, read live) is set to the cap **once**. For our + it happens in the join job,
**before** its `x-rincon:` (it never sounds above the cap). From outside, through our actor (`SonosTransport.capVolume`
on that room, `MEMBER_CAP_BUDGET_MS`) as soon as the topology shows it (it may sound for a moment first -
unavoidable).

**Who is a joiner** (QA 53-4): the controller keeps `knownMembers` per session:
- at a start: the members `playIndex` capped (`members(target)` at that moment);
- at an adopt or a follow: the first topology's members after it, with **nothing sent** (adopt stays zero commands, and
  volumes the household set are left alone);
- a later change adds to it.
Only a uid that appears in our group's members and is not in `knownMembers` is a joiner: capped once if louder, then
added; our own join adds its rooms when it caps them. A start whose `members` list was empty or stale (`rooms.state`
not loaded yet) leaves the rooms it didn't cap out of `knownMembers`, so they are capped when they first show. A volume
set later from the Sonos app is never pulled down (26a §5.3, vibb never fights an external change).

**The cap applies to a room the scope doesn't allow, too** (decided here for the user to see; it follows from the
answer to Q4): a joiner grouped in by hand is accepted *and* capped once if louder - the scope says where the app may
play, the cap how loud; respecting the kids' grouping is not a reason to let a room join above the parent's limit.

### 2.10 What stays

Ours-detection (`sameStream`), `SETTLE_MS` and the seed's hold, `optTr`'s precedence, `optPos`/`frozen`, the stall retry
and the hijack states, `bookmarkNow` and `SonosBookmarks`, "Til slutten" (`holdAtEnd`, the 3 s ramp), repeat
(`advanceIndex`), every gate (`playAllowed`, `mayPlay`, the scope's belt - `sessionAllowed` for the running session, `groupAllowed` for
everything the app starts or adds), "nothing starts by itself" (an
event never starts anything: it re-bases, confirms, or asks for a poll; a natural advance is today's gated `advance()`),
the disarm, the locks, the threading (controller on main, SOAP in the actor or the group job, the session under the
transport's mutex), the hand-off and the park, `adoptLive`'s zero commands.

### 2.11 Contracts, secrets, licences

No shared contract changes: no server, launcher or AIDL change; `ROOM_JOIN`/`ROOM_LEAVE` are app-internal session
commands (`Commands.ALL`), not in `bridge_contract.json`; the report and `music_state` are unchanged. The API key is the
RFC 4122 example UUID, public (`sonos-websocket` ships it) - a constant `SONOS_WS_API_KEY` in `SonosSocket.kt` with that
comment; it looks fake because it is the example. The golden DIDLs' Storytel URL is fake (`FAKE-SIG-1`,
`.invalid`). All new code GPL-3.0; the protocol steps follow our own probe scripts (the sonos-websocket library is read
for the handshake, not copied).

### 2.12 Volume in a group (53-b; the user's answer to Q1)

The user (2026-10-21): "Volum i en gruppe burde ideelt sett kunne styres både sammen og individuelt", and after board 4
of the sketch (`group-page/spill-av-pa-grupper.html`, "Volum i gruppe"): "jeg synes volumbiten så bra ut jeg".

- **The sheet's group block** (board 4): under the group's title row a "Begge" slider (two rooms; "Alle" for three or
  more) - the label at 13/700 in `Palette.sub`, 44 dp wide, the track peach, indented 38 dp like the member lines -
  moves every room together. Each member line keeps its name and "−" (48 dp) and gets, under them, its own quieter
  slider: a small speaker glyph (`Icons.volume`, stroked like the others) and a track at 75 % peach, the line at 90 %
  opacity; it moves that room only. Content descriptions "Volum i begge rommene" / "Volum i alle rommene" and "Volum i
  Stua". A **lone room is unchanged**: no slider in the sheet, the keys and the system's slider as today.
- **The group's value** is the mean of the members' volumes (the sketch: Stua 55, Kjøkken 35, "Begge" 45; Sonos'
  own group volume is shown the same way). It is what the "Begge" slider, the platform session's device volume
  (`setDeviceVolume`, the system's volume panel) and the player show while a group plays.
- **A group move = the same delta for every room, clamped per room**: dragging "Begge" from the mean m to m' adds
  m' - m to each room's volume **as it was when the drag began** (a base snapshot, so dragging back restores the
  balance within one drag), each result clamped to 0..100 and to the parents' cap (`volumeTarget(v, cap())`, the cap
  read live as today). **The phone's volume keys and the player's / system's slider move the whole group the same way**
  (a key press = ±3 to each room from its own last set; a system-slider value = the new mean); while the
  household's volumes are unknown a key press reads them first (below). A member's own slider sets that room only,
  clamped the same way. A raise never takes any room above the cap; a room already at the cap stays there while the
  others rise; a room at 0 stays at 0 on a lower.
- **Why not Sonos' `GroupRenderingControl`** (`SetGroupVolume` / `SetRelativeGroupVolume` on the coordinator, after a
  `SnapshotGroupVolume`): it keeps the members' proportions and scales them on the speaker - there is no per-room
  limit, so raising a group with one room at the cap would take that room above it. The parents' cap is per room
  (§2.9, 26a §5.3: "every set of ours is clamped"), so every set stays our own `RenderingControl SetVolume` per room,
  and the group value is ours (the mean). Equal deltas rather than proportions: the keys are already ±3 steps, a
  proportional raise would take a room at 0 nowhere, and a delta is what "the same for both" looks like on two
  sliders.
- **Where the sets run**: the coordinator's own as today (our actor, `transport.volume`, `session.ownVolume`); every
  other member's in a per-room, latest-wins job on IO (`RoomVolumes`: one conflated value per uid, 1.5 s budget, a
  drag sends at most one set per room at a time), never in our actor - a switched-off member never delays a Pause. The
  controller's `volOpt` becomes per room (`volOpt: Map<String, Int>`, our own last set per uid; `volSets` per uid too,
  so a play's result never overwrites a newer set of that room, L1).
- **What the app knows of each room's volume**: our own last set, else the last read. The members' `GetVolume`s are
  read (in parallel, 1 s each, off our actor) when the sheet opens - beside the busy look -, when the group's members
  change, and before the first group key press of a session whose members' volumes are unknown; the coordinator's
  stays the session's aux read. A volume changed in the Sonos app shows at the next of these reads (no socket for
  volume: §2.2).
- **The cap**: unchanged per room - a start caps every member (26a §5.3), a joiner is capped once (§2.9, the accepted
  ones of Q4 too), a cap lowered at a restrictions broadcast pulls each room whose **own last set by us** is above it
  down once (`capChanged` over the map), and a louder volume set elsewhere is never pulled down.
- **The UI pieces**: a `VolumeSlider` composable in `ui/Widgets.kt` (Compose foundation only, no Material: a track, a
  fill and an 18 dp thumb, a horizontal drag and tap, `semantics { progressBarRangeInfo; setProgress }` for TalkBack,
  the touch height 48 dp); the view model sends `Commands.ROOM_VOLUME` (`ARG_OUTPUT` = a member's uid, or the group's
  coordinator with `ARG_GROUP = true`; `ARG_VOLUME`) at most every 150 ms while dragging and once at the release. The
  values come back as published state (`PlayerHub.State.roomVolumes`: uid -> volume, and the group's mean). Not a play
  (not in `PLAYS`), but nothing is set while the session isn't ours. `FitTest`: the block with the "Begge" line and two
  and three member lines with their sliders at 320 dp, and the sheet's scroll when it is taller than
  `OUTPUT_SHEET_MAX_SHARE`. Strings (`values` + `values-nb`): `group_volume_both` "Both" / "Begge",
  `group_volume_all` "All" / "Alle", `group_volume_cd_both` "Volume in both rooms" / "Volum i begge rommene",
  `group_volume_cd_all` "Volume in all rooms" / "Volum i alle rommene", `room_volume_cd` "Volume in %1$s" /
  "Volum i %1$s".
- **Pure rules** in `logic/` (`GroupVolume.kt`): `groupMean(volumes)`, `groupMove(base, newMean, cap)` (the per-room
  targets, clamped), `groupStep(current, delta, cap)` (a key press), tested alone.

## 3. Tests (lean; JVM)

- 53-0: `PlayBodyTest` through the shared `builtDidl` against the generated goldens (url, nrk, Storytel), duration only
  for Storytel, a hash never an `albumArtURI`, `cadenceHintS` never in the DIDL; `SonosTransportTest`'s body updated.
- `SonosEventsTest` (the reducer, from fixtures `src/test/resources/sonos/ws/*.json`): the handshake's commands in
  order (getPlaybackStatus after the subscribe); our player by `id`, else by the `websocketUrl` host; a changed own group
  id re-subscribes only when its coordinator is our player; `MOVED` is an event, never a connect; a failed subscribe is
  not healthy; both `PLAYBACK_STATE_PLAYING` and `PLAYING`; junk, an unknown type and an over-size message dropped.
- `SonosSocketTest`: the host gate (a public ip, a name, another port or path, an ip not cached for our uid: refused
  before any connect); the backoff and its reset only after a healthy, live socket; an HTTP refusal and five refused
  connects park the uid 6 h; the liveness rule (a verb with no event within 3 s -> dead, and the session's health reader
  false); the pure `socketWanted` (opens on arm; closes at disarm, stop, park, hand-off and release; a Wi-Fi network
  change closes and reopens; a follow re-targets; parked -> none).
- `SonosControllerTest` `d53`: BUFFERING during a seek or a resume keeps `playWhenReady`, emits no `Paused`/`Resumed`,
  and never clears `optPos`; a PAUSED event while `optTr` is PLAYING inside `SETTLE_MS` changes nothing; a matching one
  clears `optTr`; parked while pending, applied by the landing poll, dropped before the play answered; no measurement
  after an IDLE or an uncaused BUFFERING until an ours poll; IDLE near the end -> the confirm poll -> STOPPED ours ->
  `advance()` (and with `holdAtEnd`, repeat one, repeat all); IDLE near the end with `mayPlay()` false sends nothing;
  IDLE, the STOPPED poll and a second wake -> exactly one `SetAVTransportURI`; a takeover's BUFFERING after the IDLE ->
  no advance; IDLE mid-item, IDLE while pending, IDLE then `x-rincon:` -> nothing; no `store.save` from an event; the
  event wake at most once a second and none while the last poll failed.
- `SonosAnchorTest` (`logic/`): a sawtooth of whole-second polls 1.2 s apart is monotone with no step larger than one
  tick and no stall over 100 ms; a 1.5 s correction jumps at once; a pause holds; a seek back, a step and a new item
  jump; the re-base survives more than 60 s of re-anchors; under a healthy socket a poll within 1 s is ignored.
- `SonosSessionTest` `d53`: 1 s only while shown + not healthy + ours PLAYING fresh; freshness keeps the normal
  cadence; an uncounted poll leaves `frozen` and `lastPos` alone (the stall needs 5 s-spaced frozen polls); probe tries
  1.5 s apart, the hinted candidate first; the tail cadence from the hint when the speaker says `0:00`; `measuredAt` at
  GetPositionInfo's return; a bookmark write on a transport change is not throttled (controller).
- 53-b: `SpeakerCacheTest` (`names` as pairs); `OutputRowsTest` (the pure `groupSheet`: no + or − while the phone
  renders, grouped away, on a stopped or not-started session, or stale; + on every other allowed row; the block with −
  per member; busy, pending and failed from published state); `SonosTransportTest` `d53` (leave on our coordinator =
  `BecomeCoordinatorOfStandaloneGroup` in the actor) and `GroupVerbsTest` (join = cap then `x-rincon:` with empty
  metadata on the joining room; a group row members first, the coordinator last; a closed gate or our uid among the
  row's members sends nothing; 2 s per call); `SonosControllerTest` `d53b` (an adopt or a follow baseline sends no
  `SetVolume`; a third room appearing later is capped once and only when louder; a start whose `members` was empty caps
  the second room when it first shows; the label follows; the advance guard runs the probe before advancing within
  8 s of a group change, and a kept TrackURI on the old coordinator never advances while the stream plays elsewhere);
  `RoomScopeTest` `d53` (the user's Q4: `sessionAllowed` passes our group with an accepted forbidden member and
  `mustLeaveRoom` is false for it; a member the app put there and a parent's new scope that forbids it still leave;
  our coordinator forbidden still leaves; a follow onto an accepted room stays; an accepted room that left the group
  is no longer accepted; `groupAllowed` for another row is unchanged - the existing `a session leaves a room ...` cases
  keep passing with an empty `accepted`); `SonosControllerTest` `d53b` (`joiners`: an outside joiner outside the scope
  is reported once, accepted and capped when louder; an adopt's baseline members are accepted with nothing sent);
  `OutputRowsTest` (our row kept with the accepted room and its "−"; no "+" on an out-of-scope row);
  `GroupVolumeTest` (Q1: the mean; a group move adds the same delta to each room from the drag's base and clamps each
  to 0..100 and the cap; a room at the cap stays while the others rise; dragging back restores the balance; a key
  press ±3 per room; a lone room has no group rule); `SonosControllerTest` `d53b` (the keys and `setDeviceVolume` on a
  group set every member, the coordinator through the actor and the others through `RoomVolumes`; a member's own set
  touches only it; `capChanged` pulls down each room whose own last set is above the new cap and leaves a louder
  volume set elsewhere alone; a play's result never overwrites a newer set of that room); `OutputRowsTest` (the
  "Begge"/"Alle" line and the member sliders only in a group block, none on a lone room; the order with grouping -
  `the order - phone, routed outputs, paired-not-routed, rooms, Se etter hoyttalere` gets a twin with a
  `SheetItem.Group` (our block with sliders) and a "+" row: phone, Bluetooth, wired, the paired headsets, then the
  group block and the other rooms, then the rescan row - the existing test stays as it is; no hint-line item exists);
  `FitTest` (§2.7, §2.12); `GuardsTest` (the group watch line; the `rooms.state` collector hands the members to the renderer
  before `publish()` and `leaveIfNotAllowed()`; `toRoom`'s same-room shortcut before its scope check); `SourceScanTest` `d53` (the trust manager,
  `checkServerTrusted` and a `HostnameVerifier` only in `SonosSocket.kt`; the socket client used for nothing else; no
  `wss://` literal; the strings in both languages).
- `scripts/fake-sonos.py`: `x-rincon:` joins (a coordinator's own stream stops), `BecomeCoordinatorOfStandaloneGroup`
  leaves (a coordinator's stream moves to its next member, as `move`), with a canned reply `become_standalone.xml` in
  `src/test/resources/sonos/` (`SonosFixturesTest` parses it); `--selftest` covers both. **No WebSocket in the fake**
  (the app dials 1443 on the fake's ip and is refused - and, after five, parks it): the emulator round
  (`docs/testing/emulator.md` §6h, the fake started with `--duration`) checks the fallback - the 1 s display poll, the
  STOPPED advance - and the verbs. The socket path is checked on the user's speakers (§5).
- The `ws/` fixtures (replaced by the capture on 2026-10-21, §5 check 1): until the user's capture they are written from §0.1's shapes and marked provisional
  in their README line; the capture replaces them in one commit (uids and the household id replaced by fake ones,
  `RINCON_000000000000014xx`, `Sonos_FAKE...`).

## 4. Implementation rounds

Sequential (all touch the Sonos package), each with its code QA and fix round.

- **53-0 - the DIDL fix** (branch `sonos-53-0`; small, and today a violation of the user's rule): §2.1 - `PlayBodies.build`,
  `cadenceHintS` and its read in the session's tail cadence, `builtDidl`, `sonos-play-test.py --golden`, the goldens,
  the test updates, emulator.md §6h's `--duration`. Delivers vibb's DIDL field for field.
- **53-a - events, precision, the advance** (branch `sonos-53a`): §2.2-§2.5 - `SonosEvents` and `SonosSocket`
  (`socketWanted`, liveness, the parks), the renderer's socket lifetime, `onSocket` (the state mapping, `optTr`'s
  precedence, the re-anchor, the confirm poll and its rate limit, the IDLE path and the takeover skip; `Groups` and
  `Moved` only wake), `sonosAnchor`, the poll stamp, `PlayerHub.playerShown` and the 1 s display poll, the poll guards
  (freshness, the stall, the bookmark throttle, the probe spacing, aux), `sonos-ws-probe.py --save FILE` (the raw
  `[header, body]` lines as JSONL, ids replaced) for the fixture capture. Delivers: the next episode within ~1 s of the
  end, a smooth exact bar, a pause from the Sonos app shown at once, and outside grouping cases 3-4 a few seconds faster
  (the confirm poll).
- **53-b - grouping** (branch `sonos-53b`): §2.6-§2.9 - the verbs and `GroupVerbs`, `ROOM_JOIN`/`ROOM_LEAVE`, the sheet,
  `PlayerHub.sheetOpen` and the busy look, published pending/failed, the label, `knownMembers` and the joiner cap, the
  poll burst, the candidate (from `MOVED` or `groups`) and the advance guard, the group watch for every scope,
  fake-sonos' verbs, volume in a group (§2.12: `GroupVolume`, `RoomVolumes`, `ROOM_VOLUME`, `VolumeSlider`, the per-room
  `volOpt`). Delivers the approved sketch with board 4 and the four outside cases "as it happens".

Docs with each round: music/CLAUDE.md (53-0: the DIDL rule's "Checked 2026-10-21" sentence becomes "done"; 53-a: the
socket's rule and its trust exception to "the system's CAs only", the extra polls' guards; 53-b: grouping, the cap and
its baseline, and the "Room scope" rule's exception for rooms the kids grouped in by hand, Q4), docs/testing/emulator.md §6h (53-0 `--duration`; 53-b the verbs), design 26a §2.5/§4.3/§5.1/§5.5
pointers to this note, `GuardsTest`'s pinned lines where they move, PLAN (the "To decide" DIDL item and the "Design 53"
entry; backlog items for anything found).

## 5. Owed device checks (the user's speakers, the Jelly Star, a debug build; logcat `VibbSonos`)

1. **Capture**: `sonos-ws-probe.py <ip> --all --save ws.json --do group=<room> ungroup=<room> --volume 0` once, and a
   natural end with `sonos-play-test.py`; the fixtures come from them. The capture also records §0.1's (a)-(f): the
   position at the natural end's IDLE; BUFFERING on a seek and on a resume; a takeover from the Sonos app on our room
   (and whether it passes through IDLE - if not, `playbackMetadata:1` is reconsidered as an early hint); whether the
   subscribe pushes the current status; what our socket gets when our coordinator leaves by hold-Play (a `MOVED`, or
   only `groups`).
   **Done 2026-10-21** (the user: `sonos-ws-probe.py --all --save` on one speaker while `sonos-play-test.py --volume 0`
   ran play, pause, resume, a seek, next, previous with the muted seek, the natural end -> the advance, the park; 58
   messages, now `ws/capture_2026-10-21.json`, scrubbed with `--rescrub`, replayed whole by `SonosEventsTest`). What it
   showed against 53-a: (1) the empty command is answered by `type: globalError`, `success: false`
   (`ERROR_INVALID_PARAMETER`) whose header still carries `householdId` - the handshake takes the household from it
   (it did); (2) a playback subscribe is acked as `response: subscribe`, `success: true`, `type: none` with an empty
   body, the status following as a separate `playbackStatus` without `response` - (f) yes, the subscribe pushes the
   current status itself; (3) a MOVED is the subscribe's own reply (`response: subscribe`, `success: false`, `type:
   groupCoordinatorChanged`, body `GROUP_STATUS_MOVED`, `websocketUrl`, `playerId`) - **the parser was wrong here**: a
   refused answer for any group ended our own subscription and every such reply was a `Moved` event; now only the
   answer about our own playback subscription counts (another group's or another namespace's is ignored); (4)
   `getPlaybackStatus` replies `response: getPlaybackStatus`, `type: playbackStatus`; (5) the natural end is
   `PLAYBACK_STATE_IDLE` with no `itemId`, then BUFFERING/PLAYING of the new item; event headers carry the group's
   `name`. Of §0.1: (a) the end's IDLE carries `positionMillis` 0 (the poll decides, as designed); (b) a seek and a
   resume go through BUFFERING (a next went PLAYING at once); (f) yes. Still open: (c) a takeover from the Sonos app,
   (d) what our socket gets when our coordinator leaves by hold-Play, (g) S1 - the grouping checks 6-7 record them.
   The probe's `--save` didn't scrub `serialNumber`/`deviceId` (the speakers' MACs), `locationId`, `queueId`, the item
   ids and picture URLs, and wrote addresses as 192.168.1.x: fixed (10.0.0.x, which the socket gate's tests accept).
2. **Rapid skips**: five nexts within 2 s on a playing room, with the socket and with it down: one play per settled
   want, the title and bar never flap back to the old episode (the Pi observation, "pi misforstår etter optimistisk
   start ... flapper den tilbake").
3. **Natural end**: the next episode starts within ~1 s of the end (`IDLE` -> confirm poll -> advance); "Til slutten"
   holds at the end; repeat one and all; a podcast with an extension-less URL (the speaker's `0:00` duration) - report
   the tail cadence (5 s from the hint) and the advance.
4. **Precision**: the bar against the Sonos app's over 10 min, with the socket and with it blocked: no freezes and no
   jumps of a second; pause and resume show no backward jump; a seek lands at once and the bar never jumps back to the
   old place; with the screen off and on again nothing reads stale (the locks stay).
5. **The socket gone**: power-cycle the coordinator while playing (the reconnect and backoff in logcat, playback
   followed after it comes back); a Wi-Fi switch between two networks closes the socket and opens it on the new one;
   the emulator's refused socket (§3): the park after five, 1 s polls only while the player shows, the STOPPED advance.
   If the house has an S1 speaker, it parks after the refused connects. Report whether a firmware update on the house
   changed the endpoint (the 6 h park in logcat).
6. **Hold Play on a speaker, the four cases**: (a) Kjøkken joins our Stua - the open sheet and the player's label read
   "Stua + Kjøkken" within ~1 s, Kjøkken brought down to the cap if louder; (b) Kjøkken leaves - back to "Stua"; (c) Stua
   (our coordinator) leaves "Stua + Kjøkken" - Kjøkken plays on and the app follows within ~2 s ("stream moved" in
   logcat), the label "Kjøkken", the socket on Kjøkken, `renderer.json` updated; (d) Stua joins Barnerommet playing
   something else - "Spilles i en annen gruppe" within ~1 s (in the player and on Barnerommet's row), no + or −,
   no advance, nothing sent; Stua out again - the player's line for a stopped room.
7. **+ and −**: + on a quiet room (it joins, plays with us); + on a busy room ("Spiller noe annet": its stream stops, it
   joins); + on a busy group row (both join, members first: no blip of the other stream); − on a member (it stops, the
   rest play on); **− on our coordinator** - the rest play on, the app follows, and **report the old coordinator's
   transport and TrackURI right after** (§0.1 (e)); if Sonos instead keeps the stream on the leaving room, the fallback
   is `DelegateGroupCoordinationTo(NewCoordinator, RejoinGroup=0)` before the leave, a change in this note; a
   switched-off room's + -> "Fikk ikke kontakt med ... - er den på?", and meanwhile Pause and the volume keys on our room
   answer at once (the join is off our actor).
8. **The cap on join**: a room at 80 with the cap 40 joins at 40 before any sound; a room at 20 stays at 20; a joined
   room turned up in the Sonos app afterwards is not pulled down; after an app restart that adopts a playing group,
   no member's volume changes.
8b. **A room outside the scope grouped in by hand** (Q4): with the PWA limited to Stua, a kid holds Play on Kjøkken
   while Stua plays - the music plays on in both (no "Rommet er ikke lenger tillatt", nothing stops), Kjøkken shows in
   the sheet's block with its "−" and no other out-of-scope row gets a "+", Kjøkken at 80 is brought down to the cap,
   the next episode advances on the group; the kid takes Stua out by hold-Play - the music follows to Kjøkken and plays
   on; switching home and picking Stua + Kjøkken's row again is refused as before (the app never starts there itself).
8c. **Volume in a group** (Q1): Stua 55 and Kjøkken 35 show "Begge" at 45; dragging "Begge" up 10 gives 65 and 45;
   with the cap 60 the same drag gives 60 and 45 (Stua never above it), and dragging back gives 55 and 35 again; the
   phone's volume keys and the system's volume panel move both rooms by the same steps; Kjøkken's own slider moves
   Kjøkken only; a volume changed in the Sonos app shows at the next sheet opening; a lone room has no slider in the
   sheet and its keys work as before; a switched-off member doesn't delay Pause.
9. **The DIDL**: a podcast, an NRK series item and a Storytel book each play, `didl accepted` in logcat, the Sonos
   app shows title and album; `sonos-play-test.py --didl vibb` and the app's DIDL are the same string.
10. **The certificate and the key** on every speaker model in the house: the socket opens; a debug build logs the
    certificate's subject and issuer once (for a possible later check that the subject names the uid). Since 53-c:
    the line is `sonos socket: certificate CN "<mac>", issuer CN "<issuer>", valid to <year> - verified (the pinned
    root, the udn)`, once per uid and verdict - a `refused (chain)` there (a model with an intermediate it doesn't
    send, or another root) or `refused (no udn)` means that model polls only; report the issuer CN and the year.
    `refused (dates)` is the phone's clock or the certificate's dates (never parked). **`refused (udn mismatch)`**:
    at an ip the cache has just moved (a DHCP shuffle - another speaker answered; the next topology heals it and the
    socket dials the new ip) it is expected; **on the speaker we dial, every time, it means the SAN's udn is spelt
    otherwise than the UPnP uid** (the measured spelling: SAN `URI:urn:sonos:udn:RINCON_<12 hex>01400`, the uid
    `RINCON_<12 hex>01400` from the device description's `uuid:RINCON_...` - equal after the prefix): run
    `sonos-ws-probe.py <ip> --root sonos-ca.pem` on the Pi, which prints both spellings' shape (prefix, length, last
    five characters) and never the values, and report that.
11. **Battery**: no socket while the phone renders; the socket closes at the disarm (10 min paused) and at a switch
    home (logcat).

## 6. Deviations from vibb

- vibb polls only; 53 adds the speaker's push events (§1) - display, our own verbs' confirmation and timing only; every
  decision stays on a UPnP poll, as in vibb.
- vibb has no grouping verbs (the household groups in the Sonos app); 53's + and − are the user's design, the verbs
  soco's (§2.6).
- vibb advances on STOPPED near the end at its poll; 53 advances on the confirm poll an `IDLE` asks for (§2.3), and
  holds the advance for the probe right after a group change of ours (§2.8).
- vibb caps nothing on grouping; the cap on joining rooms is the parent's rule extended (§2.9).
- vibb's volume is the coordinator room's own; 53 moves the whole group by equal steps, each room capped, and gives
  each room its own slider in the sheet (the user's answer to Q1, §2.12).
- vibb has no room scope; the app's scope (26a §5.5) no longer undoes grouping done by hand during a session (the
  user's answer to Q4, §2.8).
- The poll's display anchor is `RelTime + 0.5 s` (§2.4); vibb shows the truncated second. Display only.

## 7. Questions for the user - all four decided 2026-10-21

- **Q1. Volume on a group - decided 2026-10-21: both together and per room.** The user: "Volum i en gruppe burde
  ideelt sett kunne styres både sammen og individuelt", and after board 4 of the sketch: "jeg synes volumbiten så bra
  ut jeg". A "Begge" slider in the group block moves every room, each member its own slider, the keys and the player's
  slider move the group like "Begge", the parents' cap per room (equal deltas clamped per room, not Sonos'
  `SetGroupVolume`); a lone room unchanged. The spec is §2.12.
- **Q2. "Tas det siste ut, stopper den." - decided 2026-10-21: no "−" on a room that plays alone** (the default
  stands). The user: "gir mening for meg at vi ikke viser et minustegn på enkelthøyttalere som spiller alene". A group
  block always has two or more rooms; the last room is an ordinary selected row and stopping stays the play button's
  (§2.7).
- **Q3. The sketch's bottom lines - decided 2026-10-21: no hint lines.** The user: "Ja vi tar bort hjelpetekstene".
  Every bottom paragraph of boards 1-4 is a caption; the sheet shows none (§2.7; the buttons and sliders carry
  "Spill også i X", "Ta X ut av gruppen", "Volum i ..." for TalkBack).
- **Q4. A forbidden room grouped in from outside** (QA 53-14) - **decided 2026-10-21, not the default: the kids' own
  grouping is respected.** The user: "om barna legger til en høyttaler selv, så mener jeg appen bør bare akseptere at
  den er lagt til der og da og fortsette å spille. det barna gjør manuelt bør respekteres". A room added from outside
  stays in the group and the music plays on, whatever the PWA's room scope says; the session doesn't leave and nothing
  is removed; the sheet shows the room as a member with its "−"; the app's own "+" still offers in-scope rooms only;
  the volume cap still applies to such a joiner (a decision in this note, §2.9). The spec is §2.8's last bullet.

## Revision after QA (2026-10-21, qa-53-design.md)

Each finding was checked against the code before it was taken; all were taken, none disputed.

- **53-1 (High)** - §2.3 now maps states: only PLAYING, PAUSED, IDLE are states; BUFFERING is TRANSITIONING (never
  `playWhenReady`, no `Paused`/`Resumed`, no USER pause cancelling the sleep timer); `opt(now)` keeps precedence over the
  socket (equal confirms, different is ignored until `SETTLE_MS` or a poll); `optPos` is cleared only by a PLAYING or
  PAUSED event within 1 s of the target. Tests in §3.
- **53-2 (High)** - §2.4's "newest wins" and fixed floor are replaced by the hysteresis re-anchor (`sonosAnchor`,
  re-base within 1 s, jump at 1 s or more, every measurement re-bases so the 60 s cap is never hit); the poll is stamped
  right after GetPositionInfo and anchors at `RelTime + 0.5 s` (display only); under a healthy socket a poll re-bases
  only at 1 s or more. `SonosFloorTest` became `SonosAnchorTest` with the sawtooth case.
- **53-3 (Medium)** - §2.7's "grouping is offered" rule: our uid is a row's uid, its members hold our uid, a live
  session, not stale; grouped away shows today's rows with the player's line on the row holding our room; the service
  refuses a join whose row holds our uid. `OutputRowsTest` covers grouped away and a stopped session.
- **53-4 (Medium)** - §2.9's `knownMembers` baseline: a start's capped members, an adopt's or a follow's first topology
  with nothing sent, a later change adds; only a uid outside it is a joiner. Tests in §3 (adopt sends no `SetVolume`, a
  later third room capped once, an empty-`members` start).
- **53-5 (Medium)** - §2.1: one pure `builtDidl(body, uri)` used by `SonosTransport.play` and the test (NRK through the
  static `programUri` with a fixed serial, Storytel with a fixed minted URL); `sonos-play-test.py --golden DIR` writes
  the three files with vibb's per-kind arguments from `Session.play`; the two tests named by QA change with the fix.
- **53-6 (Medium)** - §2.2: `getPlaybackStatus` after the subscribe (its reply an event), health needs it, and the
  liveness rule (each verb of ours answered by a `playbackStatus` within 3 s, else dead); the backoff resets only after
  a healthy, live socket. Test in `SonosSocketTest`.
- **53-7 (Medium)** - §2.6/§2.7: pending and failed are published state in `RoomsView`/`PlayerHub.State` (the command
  keeps answering `RESULT_SUCCESS`); verbs on other rooms run in their own IO job (`GroupVerbs`, 2 s per call), only "−"
  on our coordinator in our actor; the switch lock is not held across a join, the job checks the gates when it starts.
- **53-8 (Low)** - §2.3: an event wakes at most once per 1 s (IDLE and `Moved` at once), none while the last poll
  failed. Test in §3.
- **53-9 (Low)** - §0.1 lists (a)-(g) as unmeasured, §5 check 1 records them, check 7 reports the TrackURI. What changes
  if false: (a) nothing (the poll decides); (b) nothing (BUFFERING is TRANSITIONING, 53-1); (c) an uncaused BUFFERING
  blocks the anchor either way, and `playbackMetadata:1` is reconsidered if no IDLE; (d) the candidate comes from a
  `groups` event (§2.8 table); (e) the advance guard of §2.8 - the probe also takes a kept TrackURI for 8 s after a
  group change and runs before any advance; (f) covered by `getPlaybackStatus`; (g) five refused connects park the uid.
- **53-10 (Low)** - §2.2: any change of the Wi-Fi network closes and reopens; the re-subscribe only when the group's
  `coordinatorId` is our player; lifetime is the pure `socketWanted`, tested.
- **53-11 (Low)** - §2.2/§2.4: the probe's candidate goes through the actor (`transport.hint`); `playerShown` and health
  reach the session as injected readers.
- **53-12 (Low)** - §2.4: an uncounted poll leaves `frozen` and `lastPos` alone; a bookmark on a transport change is not
  throttled; the tail cadence gets `cadenceHintS` (cadence only, §2.1) so a `0:00` podcast keeps its 5 s tail polls;
  §5 check 3 reports the cadence.
- **53-13 (Low)** - §2.3: `idleEnd` dropped (the confirm poll makes it fast, `endsNear` decides); the takeover race is
  closed by `skipAdvance` (an uncaused BUFFERING/PLAYING after the IDLE skips that one STOPPED). Tests: no SOAP with
  `mayPlay()` false, exactly one play for IDLE + poll + second wake.
- **53-14 (Low)** - Q4 added with the default "as today"; §2.8 states the consequence. (Superseded by the user's
  answer: see "The user's answers" below.)
- **53-15 (Low)** - the DIDL fix is its own round, 53-0 (§4); §4 lists `GuardsTest`'s watch line, emulator.md §6h
  (`--duration`, `:741` and `:713`; `:858` stays - revision check N1) and music/CLAUDE.md's trust exception; `PlayerHub.sheetOpen` for the busy refreshes;
  `Room.names` becomes pairs instead of a second field; `room_no_contact`'s English is design 52's "is it switched on?".

## The user's answers (2026-10-21)

- **Q2** (a "−" on a room that plays alone): the default stands - "gir mening for meg at vi ikke viser et minustegn på
  enkelthøyttalere som spiller alene". Nothing in the spec changes (§2.7 already shows no "−" on a lone room).
- **Q4** (a room outside the scope grouped in by hand): **not the default** - "om barna legger til en høyttaler selv, så
  mener jeg appen bør bare akseptere at den er lagt til der og da og fortsette å spille. det barna gjør manuelt bør
  respekteres". The spec: §2.8's last bullet (today's four places that act on it, `acceptedRooms` and
  `sessionAllowed`, the order in the `rooms.state` collector, what the scope still limits, a follow onto an accepted
  room), §2.7's scope bullet (our row kept, the accepted room with its "−", "+" in scope only), §2.9 (the cap still
  applies - decided here, for the user to see), §3's `RoomScopeTest`/`SonosControllerTest`/`OutputRowsTest`/
  `GuardsTest` cases, §5 check 8b. 53-b carries it; music/CLAUDE.md's "Room scope" rule gets the exception with it.
- **Q1** (group volume): "Volum i en gruppe burde ideelt sett kunne styres både sammen og individuelt", and after
  board 4 of the sketch "jeg synes volumbiten så bra ut jeg" - §2.12 (the "Begge" slider and one per room, the keys and
  the player's slider as "Begge", equal deltas clamped to each room's cap, why not `GroupRenderingControl`), §3's
  `GroupVolumeTest`/`SonosControllerTest`/`OutputRowsTest`/`FitTest` cases, §5 check 8c; in 53-b.
- **Q3** (the hint line): "Ja vi tar bort hjelpetekstene" - no hint lines; all of the sketch's bottom paragraphs
  (boards 1-4) are captions (§2.7).
- **The sheet's order** (the user, with Q3): the phone, wired (minijack/USB-C) and Bluetooth outputs stay above the
  Sonos rooms - design 52's `outputSheetRows` order, kept with the group block and its sliders in the rooms' part
  (§2.7), pinned in `OutputRowsTest`.

## Revision check (2026-10-21, qa-53-revision-check.md)

Ready for implementation; its four Lows are folded in:

- **N1** - the emulator.md lines that change are `:741` (the track ends at its DIDL duration) and `:713` (`--duration`'s
  default: a podcast reads `0:00:00`); `:858` stays, the Storytel book keeps its duration (§2.1, the 53-15 line).
- **N2** - the "+" job captures the coordinator uid and the controller's generation at its start and re-checks both with
  the gates before each member; `ROOM_LEAVE` refuses a uid not in our group; the advance guard's window reaches the
  session through the actor (`transport.groupChanged`), like `hint` (§2.6, §2.8). Tests: `GroupVerbsTest` - a follow
  between two members sends nothing more; `ROOM_LEAVE` of a non-member sends nothing.
- **N3** - the liveness rule counts only verbs against a confirmed opposite state (a pause on PLAYING, a resume on
  PAUSED, a new item or a step); a seek while paused or a no-op verb owes no event (§2.2). Test: `SonosSocketTest` - a
  seek while paused with no event keeps the socket healthy.
- **N4** - decided as accepted: "+" on a paused session joins the room; it is silent until resumed, and a busy room's
  own stream stops at once - the kid asked for it (§2.7).

## 53-0 / 53-a status (2026-10-21, branch `d53-a`)

Built as §2.1-§2.5 specify, in commits `a0fcb939` (53-0), `0ca41764` + `d9024cca` (53-a), then the code QA
(`docs/review/qa-53a-code.md`) and its fix round. 53-b (§2.6-§2.9) is next.

**53-0 - the DIDL.** `PlayBodies.build`: a `duration` only for a Storytel book, `art = null` from the controller (the
phone holds hashes only); `PlayBody.cadenceHintS` (every kind's listed duration, the tail cadence only, never the
DIDL); one pure `builtDidl(body, uri)` for `SonosTransport.play` and `PlayBodyTest`; `scripts/sonos-play-test.py
--golden DIR` writes `didl_url.xml`, `didl_nrk.xml`, `didl_storytel.xml` (no aiohttp, no speaker) - the QA checked them
equal to vibb's own `sonosd.py` output byte for byte. `didl()` untouched.

**53-a - what was built.** `sonos/SonosEvents.kt` (pure: the messages, the handshake reducer `Plan`, `SocketEvent`),
`sonos/SonosSocket.kt` (OkHttp's own WebSocket, `socketWanted`, the host gate, the limited trust, the backoff, the
parks, `Liveness`), `logic/SonosAnchor.kt` (`sonosAnchor`), `SonosController.onSocket` and its poll-side rules
(`pollTick`: the IDLE's confirm polls, `skipAdvance`, the disagreement rule, the bookmark throttle), `SonosSession`
(the poll stamp, the 1 s display poll, the stall, probe and aux guards), the renderer's socket lifetime and
`PlayerHub.playerShown`, `scripts/sonos-ws-probe.py --save`, the provisional `ws/` fixtures. Not here (53-b's): the
group watch for every scope (`GuardsTest`'s watch line stays), the probe's hinted candidate (`transport.hint`) and the
advance guard after a group change.

**Where it differs from §2 (accepted by the code QA):**
- `socketWanted` reads the session's own `armed` (volatile) at every flush, not `Fields.armed` per poll - a disarm by
  `stop()` closes the socket at once.
- The first handshake subscribes `playback:1` only for a group our player coordinates (the re-subscribe rule applied
  from the start): a room grouped away at connect is open for `groups` but not healthy.
- One parked event (the last), applied only when the landing poll saw the play land.
- An IDLE is never the effective transport (only PLAYING and PAUSED are): the confirm poll decides STOPPED - no
  Paused/Resumed flicker at an episode's end.
- A wake inside the second trails once at its end (still at most one a second) instead of being dropped.
- The socket's BUFFERING stops the anchor only outside a verb of ours (under a resume or a seek the verb's intent and
  the seek hold show).
- A play's seed has its own anchor key, so the first real measurement after a play is taken at once.
- The poll's bookmark throttle also lets a changed stream (a new item, a follow's uri) through.
- `SourceScanTest`'s "no Network binding" scan allows `tls.socketFactory` in `SonosSocket.kt` (the TLS factory over
  the client's own unbound sockets).
- `--save` replaces names too (rooms, groups, titles - the kids' names), not only ids.

**The code QA's fixes (`qa-53a-code.md`):**
1. (High) The bar no longer freezes 60 s after the last event: a poll within the second keeps the old extrapolation's
   value but moves the anchor's instant on, socket or not (events come on a change only). `SonosAnchorTest` and a
   `SonosControllerTest` case (a healthy socket, polls 15 s apart for 90 s).
2. (High) `Liveness` keeps the connection's last event: an event that came after the verb went out but before its SOAP
   answer reached main pays the debt (and resets the backoff). `SonosSocketTest` case with the event before `owed`.
3. (Low) A PLAYING that confirms our resume first gives the anchor the motion the bar showed (from the tap), so it
   re-bases within the second - no step back. A rebuffer we didn't cause stands still until the confirm poll (accepted
   as designed: a takeover must not move the bar; §5 check 4 reports it).
4. (Low) An IDLE's confirm poll that still reads PLAYING or TRANSITIONING asks again every second up to 3 s after the
   IDLE (UPnP lags 0.3-2.6 s), then the normal cadence; §5 check 3 reports the gap measured.
5. (Low) Tests for the older-measurement guard, the disagreement rule's 3 s guard, the seek hold's tolerance, an IDLE
   never effective and blocking measurements until an ours poll.
6. (Low) The host gate takes this note's ranges only (`speakerLiteral`: RFC 1918, link-local, ULA/link-local IPv6),
   never loopback, `0.0.0.0/8` or the tailnet.
7. (Low) The playback subscribe's answer is told by its `response` first; one carrying the status (§0.1 (f)) acks and
   is an event.
8. (Low) This section; §5 check 10's log: a debug build logs the leaf certificate's subject and issuer once per process
   (`SpeakerTrust`, through `SonosHome`'s log).
9. (Nit) `--save` writes the fixtures' JSON array format and scrubs IPv6 literals too.

**Owed** (§5): the capture first (it replaces the provisional `ws/` fixtures), then checks 2-5 and 9-11 on the user's
speakers; the emulator round §6h (the fallback: the refused socket parked after five, the 1 s polls, the STOPPED
advance with `--duration`).

## 53-b status (2026-10-21, branch `d53-b`)

Built as §2.6-§2.9 and §2.12 specify, on main `a2d66476`; awaiting its code QA.

**What was built.**
- §2.6: `SpeakerControl.joinGroup` (`SetAVTransportURI` to `rinconUri(coordinator)` with empty metadata) and
  `becomeStandalone` (`BecomeCoordinatorOfStandaloneGroup`); `sonos/GroupVerbs.kt` - `GroupVerbs` (one `Mutex`, IO,
  2 s a call, the uid verified; `join`: `joinOrder` = members first, the row's coordinator last, each capped first,
  `still` asked before each room; `leave`) in `SonosHome.verbs`; `SonosTransport.leave` (our coordinator's "−", in the
  actor), `capMembers` (a joiner's cap through the actor), `hint`, `groupChanged`.
- §2.7: `Commands.ROOM_JOIN` (in `PLAYS`), `ROOM_LEAVE`, `ROOM_VOLUME` (`Commands.GROUPING`, run beside the switch
  lock); the service's belts are pure in `RoomScope.kt` (`groupingOffered`, `joinOffered`, `leaveOffered`); the job
  captures the coordinator and `generationNow` and asks them with `playAllowed` and the live session before each room
  (N2). The sheet's rows are the pure `groupSheet` (`ui/OutputRows.kt`: `SheetItem.Speaker` with its `RoomLine` and
  "+" `GroupMark`, `SheetItem.Group` with its `GroupMember`s) drawn by `OutputSheet` (`SheetRow` gained a trailing
  slot; `GroupButton`, `GroupPending`, `GroupBlock`), the order design 52's (`outputSheetItems`). `RoomsView.busy` from
  `RoomDirectory.lookBusy` (parallel `GetTransportInfo`, 1 s each, 2 s in all, at most 8 rows) at `ROOMS_REFRESH` and at
  a `groups` event while `PlayerHub.sheetOpen`. Pending/failed in `PlayerHub.State` (`groupPending`, `groupFailed`,
  `roomLive`), expired by a posted republish. `Room.names` is `(uid, name)` pairs. Strings in both languages, the
  glyphs `Icons.roomGroup`/`add`/`remove`/`volume`.
- §2.8: `SonosController.topology(members)` from the renderer, which the service's rooms.state collector calls before
  `publish()` and the scope's look (`GuardsTest`); `joiners`, `knownMembers`, the baseline (a start's members; an
  adopt's or a follow's first topology, nothing sent), `accepted` (Q4), `relabel`; `groups()` and `MOVED` ->
  `followSoon` (the candidate through the actor, `groupChanged`, the burst now/+1.5/+3.5 s), `host.groupsChanged()` ->
  the renderer's debounced `refreshQuietly` (300 ms). The session probes a kept TrackURI for `GROUP_WINDOW_MS` after a
  change and asks the hinted candidate first; the controller's advance guard waits for `Fields.probeMissed` (or the
  window's end). Q4's four places: `sessionAllowed` in `leaveDisallowedRoom` (`mustLeaveRoom(..., accepted)`), the
  renderer's belt (`roomAllowed(uid, accepted)`), the sheet (`allowedRooms(view, scope, ours, accepted)`), `toRoom`'s
  same-room shortcut first. The group watch: `showsPlaying && (roomScope != null || !socketHealthy)`.
- §2.9: a joiner louder than the cap is capped once through the actor; our own join caps before its `x-rincon:` and
  marks the room known first (`joining`).
- §2.12: `logic/GroupVolume.kt` (`groupMean`, `roomMove`, `groupMove`, `groupStep`); `MemberVolumes`/`RoomVolumes`
  (a conflated channel per room on IO, 1.5 s a set; reads in parallel, 1 s each); the controller's `volOpts` per room,
  `setDeviceVolume`, the group paths of `adjustVolume`, `setRoomVolume`, `setGroupVolume(mean, drag)`, `readVolumes`,
  `capChanged` over the map; `VolumeSlider` (`ui/Widgets.kt`); the view model's 150 ms throttle (`VOLUME_SEND_MS`).
- `scripts/fake-sonos.py`: `x-rincon:` joins (the room's own stream stops; a coordinator's members keep it on the next
  of them), `BecomeCoordinatorOfStandaloneGroup` (a member stops; a coordinator's stream moves on, as `move`) answering
  from `become_standalone.xml`; `--selftest` 4b. emulator.md §6h has the round.

**Where it differs from §2 (the code's reality, or a gap the note left):**
- The member lines are indented 46 dp (the row's glyph disc and its gap), not the sketch's 38: in the app's row
  geometry that is what puts the names under the title, as the sketch's 38 does in its own.
- The "Begge" drag's base is keyed by a drag id the view model sends (`ARG_DRAG`, app-internal): a drag is a run of
  sends ended by the release, so a finger held still never starts a new base.
- A member's read volume that differs from our own last set replaces it (`absorb`): the note's "our own last set,
  else the last read" would hide a change made in the Sonos app, which §2.12 says shows at the next read. Our
  coordinator keeps vibb's rule (its own set is the base).
- An accepted room that leaves our group stays accepted for 90 s (`ACCEPTED_GRACE_MS`, the probe's window): the
  topology can show it gone before the follow lands on it (the kid took our coordinator out by hold-Play), and the
  scope's look after the follow must still see it accepted. It is no member meanwhile, so nothing else reads it.
- A room that leaves is dropped from `knownMembers`: joining again from outside is a new join, capped again.
- A joiner seen while no session of ours plays (stopped) is accepted but neither capped nor known - the next start
  caps it with the rest of the row.
- `GroupVerbs.join` treats a joining room that doesn't answer its `GetVolume` as no contact (its join isn't sent; a
  UPnP fault on the volume alone is logged and the join goes on), and refuses a row holding our coordinator (a belt
  under the service's `joinOffered`). With no cap nothing is read.
- A "+" whose row partly failed shows the no-contact line on the row; a "−" on our coordinator is settled when it no
  longer shares a group with the others (our uid alone, or the follow).
- `liveSession` doesn't wait for a play to settle (the seed is ours and PLAYING): the "+" would blink off at every
  next episode otherwise.
- The label is the row's from the published rows, else our room's own name from the rows' (uid, name) pairs - never
  `home.label`, which reads the cache file on main when the rows lack it.
- A `groups` event whose former members are held by another coordinator always names it as the candidate: from our
  socket a member leaving looks like our coordinator leaving them; the probe's gate (our uid STOPPED, empty or kept
  in the window) decides, at the cost of a three-poll burst.
- The "Begge" line and a member's slider show only once that volume is known (the sheet's opening reads them).
- The busy look runs only while a room renders (grouping can be offered only then).

**Found, then decided by the user** (2026-10-21, "enig i at den bør slå av alle grouped høyttalere"): the sleep
timer's fade on a group faded only the coordinator room - built in the fix round below.

### 53-b fix round (qa-53b-code.md: 0 High, 4 Medium, 6 Low, 6 Nit - all taken)

1. (M) A joiner is accepted (and capped, and known) only while a session of ours plays: a room grouped in while
   nothing plays is judged by the scope at the next start, whose members cap it (the coordinator: Q4 is about a
   running session going on). The deviation "a joiner seen while stopped is accepted" is withdrawn.
2. (M) The adopt's baseline is the first **fresh** topology after it: the adopt asks for one (`host.groupsChanged`),
   and the renderer's debounced refresh hands the rows to the controller whether or not the `StateFlow` emitted; the
   adopt no longer takes the cache as an earlier process left it. A follow takes no baseline at all (with 8).
3. (M) `setRooms` never sends a target equal to the known volume or above the cap (a raise leaves such a room
   where it is), and a member's read replaces our own set only when it started after that room's last own set
   (`volGen`, bumped by every own set and the joiner's cap).
4. (M) One member whose volume can't be read no longer disables the group's keys, the system's slider or "Begge": the
   known rooms move, a room that didn't answer isn't read again before every press (`unread`, until the next read),
   and "Begge" needs two known volumes (`groupVolume`).
5. (L) `VolumeSlider` lets go of its released hold after a second by itself (`LaunchedEffect`).
6. (L) The system's slider on a group moves from its slide's base (taken at its first event after 1 s without one),
   like "Begge" - sliding back restores the balance, as music/CLAUDE.md says.
7. (L) Inside the group-change window the advance runs only when the probe's **last** try misses
   (`Fields.probeMissed` = no tries left), or the window closed.
8. (L) A follow keeps `knownMembers` and `accepted` as they were - also the rooms the topology already showed gone
   with our stream (`knownLeft`, the grace) - so rooms our own "+" put there stay judged by the scope; only a room
   neither known nor accepted is a joiner after it.
9. (L) The busy lines are cleared at the sheet's opening before the new look, and by a look off Wi-Fi.
10. (L) Tests for the six surviving mutations (M7, M13, M14, M15, M20, M21) - each now caught, with the findings' own
    cases (a mutation run of nine after the fixes: all caught).
11. (N) A refused "−" on our coordinator (no session) clears its Spinner and logs; never the no-contact line.
12. (N) `ROOMS_REFRESH` reads our uid on main before the busy look's IO.
13. (N) Our own "+" rooms stay known while the job runs (`joiningNow`, `joinDone`): a topology between `joining` and
    the join never makes one a joiner from outside.
14. (N) The view model drops the drag id when the sheet closes.
15. (N) The sketch's "HØYTTALERE" section label is not in the app (the note and the string list leave it out) - a
    question for the user at the device check.
16. (N) `fake-sonos.py` refuses a coordinator joining one of its own members (fault 714; selftest 4b).

**The group's sleep fade** (the user's decision): `fadeOutAndPause` steps every other member whose volume is known
by the same 2/3, 1/3, 0 of its own volume through `MemberVolumes` (latest-wins, off our actor - a switched-off member
never delays the Pause; each step never above the cap), our coordinator's Pause stops the group, and each room gets
its own pre-fade volume back after it (also when the ramp is cancelled), as the coordinator's restore does today; the
rooms' read generation is bumped at both ends, so a read during the ramp never reads as their volume. A member whose
volume the app doesn't know is left alone (nothing to put back). Not carried across a crash: `renderer.json`'s ramp
volume is still the coordinator's only (a process that dies mid-ramp leaves a member quiet until it is turned up).

**The capture** (§5 check 1, done 2026-10-21, in this round): the `ws/` fixtures are the user's capture, scrubbed
(`ws/capture_2026-10-21.json` replayed whole; `handshake.json` its own messages in the app's order; the rest
hand-written in its shapes), and `SonosEvents.Plan` reads only the answer about our own playback subscription - the
capture's other groups answering MOVED had ended ours (§5 check 1 lists the five findings).

**Owed** (§5): checks 6, 7, 8, 8b and 8c on the user's speakers (with the socket: the four outside cases within ~1-2 s;
check 7's report of the old coordinator's transport and TrackURI after a "−" on it - if Sonos keeps the stream on the
leaving room, `DelegateGroupCoordinationTo` first, a change in this note); the emulator round §6h's grouping step.

## 53-c status (2026-10-21, branch `d53-c`)

A small hardening round, from the user's `openssl s_client` to a speaker at 1443 (PLAN, design 53's "The certificate,
checked the same day"): the leaf (`CN=<mac>`, `OU=Sonos Devices`, `O=Sonos, Inc`) chains straight to the **Sonos
Device Authentication Root CA** (2015-10-05..2055-09-25, SHA-256 `42:DC:D4:A7:...:D5:A0:8F`), `Verify return code: 0`,
and its SAN holds `urn:sonos:udn:RINCON_<uid>` (with `sonos-<mac>.local`, `sonos-<mac>.smartspeaker.audio` and the
household, user, auid and serial URNs - identifiers that are never logged). §2.2's trust-any-on-the-gated-ip is
replaced; the gate itself is unchanged.

**What was built** (`sonos/SonosSocket.kt`, still the only trust code - `SourceScanTest`'s `d53`):
- `SONOS_ROOT_PEM`: the root, a public certificate only - the copy in github.com/nickblt/sonos-websocket
  `src/certs/sonos-ca.pem` (that repo has no licence; no code taken), kept because its fingerprint matched the user's
  speaker's chain. `SonosTrustTest` checks its SHA-256 (`SONOS_ROOT_SHA256`), subject and self-signature.
- `rootOnlyTrust(root)`: the platform's `TrustManagerFactory` over a `KeyStore` holding that root alone (Android's
  documented "unknown CA" pattern: under the network security config it is a config of that store only) - never the
  system CAs, never trust-all.
- `speakerCertRefusal(chain, authType, uid, roots)`: the chain through it (`chain`), then the leaf's SAN URIs
  `urn:sonos:udn:` - none (`no udn`), or none equal to the uid whose cached ip was dialled, case aside (`udn
  mismatch`). `SpeakerTrust` (the socket client's trust manager, per (ip, uid): `clientFor(ip, uid)`,
  `SonosHome.socketClient(ip, uid)`) runs it in the handshake - before any message - and throws a
  `SpeakerCertificateException` carrying only the reason class; `checkClientTrusted` refuses; `getAcceptedIssuers` is
  the root. The host name verifier still accepts exactly the cached ip, the gate (`socketGate`) still runs before any
  dial.
- A refused certificate is a socket failure counted as a refused connect (the listener finds the exception in the
  failure's causes): the backoff, five in a row (at one ip: the fix round, 1) park the uid for 6 h - the same certificate would come back - and
  polling stays. Other TLS failures stay `OTHER`. Logged: `sonos socket: certificate refused (<reason>)` (every build)
  and `ended (REFUSED: certificate refused)` - never the platform's message, which may quote the certificate.
- §5 check 10's debug line now names the leaf's subject CN and issuer CN only (never the SAN), with the verdict, once
  per uid and process: `sonos socket: certificate CN "<mac>", issuer CN "<issuer>" - verified (the pinned root, the
  udn)` or `- refused (<reason>)`.

**Intermediates**: the user's chain was root -> leaf. Another model may sign through an intermediate; one the speaker
sends in its chain is path-built under the pinned root (the platform's validation; `SonosTrustTest` has the case, in
order and with the root sent along). One it doesn't send is never fetched (no AIA): that model is refused (`chain`)
and polls only - check 10 reports the issuer CN, and the intermediate would then be added beside the root, not instead
of the check.

**Tests**: `SonosTrustTest` over throwaway test certificates (`src/test/resources/sonos/certs/`, a "Vibb Test Root",
an other root, an intermediate and eight leaves (eleven after the fix round) made by its `make-certs.sh`, the keys deleted; README there): the good
chain and the intermediate chain pass; another udn, no SAN, a DNS-only SAN, another root, a self-signed leaf, an
expired leaf, a missing intermediate and an empty chain are each refused with their class; the real client's anchors
are the Sonos root alone (the test root's good chain is refused by it); the logs carry no SAN value. `SonosSocketTest`:
five refused certificates park, the platform's message isn't logged, another TLS failure doesn't park, the client is
asked for our uid. `SourceScanTest`'s `d53`: the trust tokens in `SonosSocket.kt` only (main and debug), no trust-all,
one `setCertificateEntry` of the pinned root, the factory initialised with that store, one `SSLContext` over
`SpeakerTrust`, every chain through `speakerCertRefusal`.

### 53-c fix round (qa-53c-code.md: 0 High, 1 Medium, 5 Low, 5 Nit - all taken)

1. (M) **A park belongs to the ip it was earned at.** `SocketParks` keeps `(until, ip)`; `parkedUntil(uid, ip, now)`
   is void (and dropped) once the cache names another ip, and a resolved ip other than the last dial's starts the
   refused streak over (`SocketBackoff.ipChanged`). So a stale ip after a DHCP shuffle - another of the house's
   speakers answering (`udn mismatch`), or a host without port 1443 (53-a's TCP refusal) - never parks a good speaker:
   four refusals at the old ip and one at the new one park nothing. The park is now the socket's own business:
   `socketWanted(armed, wifiNetwork, closed, uid)` lost its `parkedUntil` (only the socket re-reads the ip), and a
   parked target dials nothing but looks at the cache again every `PARK_RECHECK_MS` (300 s, the backoff's slowest) and
   at the park's end - a moved ip is dialled within one re-check. The park is logged once.
2. (L) **`dates`**: a chain refused while one of its certificates is outside its dates (or the platform's causes say
   expired/not yet valid) is its own class, counted `OTHER` - a phone booting with its clock far off backs off and
   never parks every speaker. `SpeakerCertificateException` carries its `reason`; the `ended` line says it
   (`ended (REFUSED: certificate refused, udn mismatch)`). The debug line adds the leaf's last year (`valid to
   <year>`), so the speakers' leaf lifetime gets recorded.
3. (L) The udn's spelling: `certificateUdns` documents the measured shape, `SonosTrustTest` ties it to
   `Soap.parseUdn` (the device description's `uuid:RINCON_...` -> the uid -> the SAN's value after the prefix), and
   §5 check 10 says how a mismatch reads and what to report. The user's measured pair (SAN
   `urn:sonos:udn:RINCON_<mac>01400`, uid `RINCON_<mac>01400`) is equal after the prefix.
4. (L) **Several udns**: the leaf is accepted when one of its `urn:sonos:udn:`s equals our uid (the CA vouches for
   every name it lists); fixture `leaf_two_udns` (another first, ours second) - kills M3.
5. (L) **A leaf as an issuer** (a LAN attacker owning a Sonos minting a leaf that names our udn with its own leaf's
   key): fixture `leaf_under_leaf`, refused `chain` with and without the root sent along.
6. (L) `SpeakerTrust` stays a plain `X509TrustManager` calling the 2-argument `checkServerTrusted`: Conscrypt calls it
   so and the network security config's trust manager then gets no host - no per-host policy and no Certificate
   Transparency, which a private-CA leaf without SCTs would fail on targetSdk 36. `SourceScanTest`'s `d53` pins it.
7. (N) The debug line is once per uid **and verdict**: a first `refused (udn mismatch)` at a stale ip never hides the
   later `verified`.
8. (N) `commonName` splits the RFC 2253 name at the unescaped `,`/`+` only (an escaped `\,CN=` inside another value is
   no CN), drops control characters and keeps at most 64 characters.
9. (N) The pinned root's trust manager is built once (`sonosTrust()`, `by lazy`); each client still gets its own
   `SSLContext` (so no pooled connection or TLS session crosses uids). `speakerSocketClient`'s last parameter is the
   anchors (`roots`), a test passing its own root's.
10. (N) `scripts/sonos-ws-probe.py --root PEM [--uid UID]`: the Pi-side twin of check 10 - the chain against that
    root alone (no `X509_STRICT`, as the platform's PKIX), then the SAN's udn against the device description's UDN;
    prints the issuer CN, the leaf's last year and the verdict, a mismatch as both spellings' shape - never a value.
    Checked against a local `openssl s_server` with the test certificates (accepted, mismatch, another root).
11. (N) M8 and M10: `SonosTrustTest` drives the host name verifier (exactly the dialled ip; another ip, the uid, the
    speaker's DNS name refused); `SonosHome.socketClient` names its arguments (`ip = ip, uid = uid`), pinned by `d53`.

Mutations after the round (each against `SonosTrustTest`, `SonosSocketTest`, `SourceScanTest`, reverted): M3 (every
udn must equal), M8 (a verifier accepting any name), M10 (ip/uid swapped in `SonosHome`), the ip reset removed, the
park's ip ignored, `dates` counted `REFUSED`, the debug line once per uid only - all caught.

**Owed** (§5 check 10, a debug build on the Jelly Star): the phone's own TLS stack (Conscrypt under the network
security config) accepting the speaker's chain against the pinned root - logcat `VibbSonos` shows `certificate CN
"...", issuer CN "Sonos Device Authentication Root CA", valid to <year> - verified (the pinned root, the udn)` and
then `open to RINCON_...` and `healthy`; on every speaker model in the house (or `sonos-ws-probe.py <ip> --root` from
the Pi for each). A `refused (udn mismatch)` on the speaker we dial is check 10's spelling case.

## 53-d status (2026-10-21, branch `d53-d`) - the polling round

The user's approval (PLAN, "Sonos polling round (53-d)": "skal vi bare starte den"), after the capture showed the
socket keeping up: lean on it more, keep UPnP the verdict. Music app only; no contract file changes. §2.5 is superseded
by (3).

**(1) Confirm polls until UPnP agrees** (`SonosController.confirmFor`/`confirmed`, generalising 53-a's IDLE recheck,
qa-53a-code 4). A `playbackStatus` we didn't cause - not a play of ours settling, not our held verb's own state, not a
BUFFERING within `SETTLE_MS` of a verb of ours (`causedByUs`) - that the last poll doesn't show opens a confirm: a state
(PLAYING, PAUSED, IDLE -> STOPPED) or a **new item** (another `itemId` than the socket's last: UPnP's TrackURI must
change). The event's own wake goes out as before (at most once a second, an IDLE at once, none while the last poll
failed); a poll that doesn't show it asks again `CONFIRM_EVERY_MS` (1 s) later, through the same rate limit, while the
next one stays within `CONFIRM_UNTIL_MS` (4 s) of the event - so at most the event's poll and three rechecks. It ends on
a poll that shows it, a poll with another TrackURI than before (a takeover, grouped away, a moved stream), a failed poll,
a newer play, the socket's health going, or the window; and a newer state from the socket (ours or not) supersedes a
state still being confirmed. A BUFFERING of the same item is a phase and opens none (its state follows). **While a PLAYING or PAUSED confirm is open, a confirm poll still reading the old state doesn't take
the screen back** (`awaited`: the socket's state stays the effective transport; `accept` doesn't re-anchor the bar from
that poll) - found while building: in 53-a the immediate confirm poll, landing inside UPnP's 0.3-2.6 s lag, flipped a
pause from the Sonos app back to playing until the next poll. When the window ends without UPnP showing it, the poll's
word is the speaker's again (and the disagreement rule takes over). The IDLE near the end advances exactly as before
(the confirm's STOPPED is `pollTick`'s rule's input); `idleGen` clears when an IDLE's confirm ends unconfirmed, as the
recheck did. Events we caused keep their one wake (the landing poll of our own play comes sooner) and the verb window.

**(2) `objectId: "vibb-1"` as an early "ours" hint** (`OUR_OBJECT_ID` in `SonosEvents.kt`; the DIDL's `<item
id="vibb-1">`, `didl()` untouched - `SonosEventsTest` ties the two). The handshake now also subscribes
`playbackMetadata:1` for our coordinated group (after `getPlaybackStatus`; re-subscribed with playback); its answers
say nothing of health; a `metadataStatus` of our group becomes `SocketEvent.Metadata(groupId, objectId, at)` - the
container's object id only, never the title or a picture. `SocketEvent.Playback` gains `itemId`. The hint
(`earlyHint`): while our answered play settles (`pending` with its uri), our id in a metadata event since the play went
out **and** a parked PLAYING or PAUSED of a **new** item (an `itemId` other than the speaker's when the play went out)
re-base the bar on that event's exact position and show READY instead of BUFFERING. That is all it does: `pending`
stays until a poll sees our TrackURI or `SETTLE_MS` (a lagging poll of the old stream never reads TAKEN_OVER), nothing
is bookmarked, and the advance, the follow, the park and every gate read UPnP as before. An absent or other id (or an
unchanged item) is no evidence: the seed shows until the landing poll, as in 53-a. The capture's first play (messages
30-33: IDLE, BUFFERING item-2, metadata `vibb-1`, PLAYING 1186 ms) is replayed through `SonosEvents.Plan` into the
controller (`SonosControllerTest` `d53d`); the capture shows the id on every play of ours (play, next, previous, the
advance) and none at the subscribe's push and the park.

**(3) The slow UPnP check while the socket is healthy** (`SonosSession.relaxed`, `POLL_SLOW_MS` = **60 s**). While
`socketHealthy()` (53-a's definition: open, our coordinator's playback subscribed, acknowledged and read, live - a
liveness miss or the disagreement rule closes it), the speaker answered the last poll and the stall counter is 0,
PLAYING's 15 s cruise and PAUSED's 15 s become 60 s. Unchanged: the 5 s tail (45 s before the end), the verb/arm window
(5 s for 30 s), TRANSITIONING (5 s), STOPPED (60 s, 5 s in the migration window), the UNREACHABLE backoff, the disarm.
Without a healthy socket the 5/15 s cadence and the 1 s player-shown poll are exactly today's (`SonosSessionTest`).
Why 60 and not 120, and what had to move with it:
- **The tail and the advance**: the advance needs an ours poll within `ENDS_NEAR_S` (20 s) of the end; the slow check
  is cut short where the tail begins (`dur - 45 s - rel`, at least 5 s), so the tail's 5 s polls (and the last bookmark
  ≤ 5 s before the end) stay; the IDLE's confirm then advances within ~1 s.
- **The bar**: `SonosSpec.MAX_EXTRAPOLATE_MS` was 60 s - with polls a minute apart and no event the bar would stop just
  before each poll; it is now `POLL_SLOW_MS + FRESH_SLACK_MS` (70 s). Freshness is the chosen cadence + 10 s (70 s), and
  a stale snapshot already stops the bar.
- **The bookmark clock**: a poll writes it, the socket never does; the service's 15 s save tick re-writes the last
  measurement (+ at most 5 s). A crash that also loses the speaker loses at most ~1 minute (was 15 s); the usual crash
  (the speaker plays on) is healed by the adopt's live read; a pause, a switch, a suspension and the hand-off read live
  as before. 120 s would double that, and the extrapolation cap would have to grow past vibb's minute twice over.
- **The stall**: a frozen position takes the session back to the 15 s cruise until it moves, so the retry comes ≤ 90 s
  after the freeze (was ≤ 45 s; at a flat 60 s it would be ~3 minutes).
- **A socket that stops pushing but stays open**: the first poll that disagrees with it asks for a second look 3 s
  later (`agreement`), so the disagreement rule closes it in seconds, not two slow polls; the socket going unhealthy
  (closed, dead ping, liveness miss, disagreement) wakes a poll at once, whose cadence is today's.
- **The aux reads** stay every 12th poll (≥ 60 s): under the slow check that is ~12 min. Accepted: the volume shown is
  our own last set (vibb's rule) and grouped away is read from the TrackURI on every poll and from `groups` events.
- **The migration probe**: unchanged (STOPPED's window polls at 5 s); with the socket a `MOVED`/`groups` event starts the
  burst as in 53-b.
- The 60 s locks renewal (`SonosLocks.RENEW_MS` 5 min) is re-asserted at every refresh, still ≥ 5 per renewal.
- A one-line log when the slow check starts or ends (`the socket is healthy - the regular UPnP check once a minute`),
  for the device check.

**UPnP calls, an hour of one podcast playing from the start, the screen off** (`SonosSessionTest` `d53d`, simulated;
each poll GetPositionInfo + GetTransportInfo, every 12th also GetZoneGroupState + GetVolume): **before** (and still
without a healthy socket) 250 polls - 6 in the arm's window, 235 at 15 s, 9 in the tail - and 21 aux reads = **542
SOAP calls**; **after**, under a healthy socket, 74 polls - 6, 59 a minute apart, 9 in the tail - and 7 aux reads =
**162** (-70 %). Confirm polls come on top only for changes made outside the app (at most 4 per event) and the natural
end (1-3). With the player screen open and no socket the 1 s display poll is ~7 200 calls an hour, as in 53-a.

**For design 54**: `IDLE_RECHECK_MS`/`IDLE_RECHECK_UNTIL_MS` are now `CONFIRM_EVERY_MS` (1 s) / `CONFIRM_UNTIL_MS`
(4 s); `SocketEvent.Playback.itemId` and `SocketEvent.Metadata` (the container's object id) exist - 54's queue parts
extend them; the new-item confirm is 54-4's precursor for a direct-URI session.

**Tests**: `SonosEventsTest` (the metadata subscribe in the handshake and the re-subscribe, `Metadata` from our group
only, the capture's `itemId`s and object ids, the DIDL's id), `SonosSessionTest` `d53d` (the cadence under and without
a healthy socket, the tail cut, the stall's return to 15 s, freshness, the hour's counts), `SonosControllerTest` `d53d`
(the Sonos-app pause confirmed every second and not taken back by a lagging poll; the confirm's end at 4 s; our own
verb's event opens none; a new item polled until the TrackURI changes; a failed poll ends it; health lost wakes a
poll; the disagreement's second look; the capture's play with the hint, `pending` and the bookmark untouched; an
absent, other or unchanged-item id is no evidence); 53-a's IDLE-recheck test kept (now the confirm).

**Owed on the speakers** (a debug build, logcat `VibbSonos`): (a) the slow-check line after `healthy`, and an hour of a
podcast advancing to the next episode within ~1 s of the end (§5 check 3) with the tail polls; (b) a pause and a resume
from the Sonos app and the speaker's button: shown at once, no flap back while UPnP lags, the bookmark at the pause
point; (c) a takeover from the Sonos app (§0.1 (c), still open): what the socket sends (a new `itemId`?), the confirm
until the TrackURI changes, "Spilles et annet sted" without a flap, nothing advanced; (d) the hint: `our DIDL's id on
the socket` on a play, a next and the advance, the bar exact before UPnP confirms - also whether an NRK
(`x-sonos-http:`) and a Storytel play carry `vibb-1` (the capture had direct-URI plays only; without it nothing
changes); (e) the socket killed (the speaker's power, a Wi-Fi switch): today's cadence at once (the line `the regular
UPnP check at 5/15 s`); (f) a stall, if one can be provoked: the retry within ~90 s.
