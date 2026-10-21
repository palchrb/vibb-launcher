# 53 - Sonos: grouping in "Spill av på", grouping done outside the app, precision and push events (architect)

Asked 2026-10-20/21 (the user). Four parts, one note: (1) grouping in "Spill av på", the approved sketch ("digget jeg
designeforslaget for gruppering og ungrouping! Vi kjører med det!"; "Barnet skal få gruppere selv", "Ingen begrensing
på hvilke høyttalere"); (2) grouping done outside the app - the kids add and drop rooms by holding Play on a speaker,
which can change the coordinator ("Endres groups utenfor vår musikk app skal vi klare å følge med på hva som skjer i
appen vår"); (3) push events from the speaker's local WebSocket, measured on the user's speakers 2026-10-21 (PLAN,
"Design 53"); (4) precision and an immediate advance - plus the DIDL fix the user decided ("Ikke endre noe i didl!!",
PLAN "To decide": the app sends exactly vibb's fields). Music app only: no server, launcher, PWA, AIDL or contract file
changes (§2.11). Out of scope: the queue view (46 §1.1-§1.3, parked), WebSocket commands, the Sonos cloud API (the
user: not now).

## 0. Facts

### 0.1 The speaker's local WebSocket (measured 2026-10-21, `scripts/sonos-ws-probe.py`, `scripts/sonos-play-test.py`)

- `wss://<ip>:1443/websocket/api`, request header `X-Sonos-Api-Key: 123e4567-e89b-12d3-a456-426655440000` (the RFC 4122
  example UUID that `sonos-websocket` sends; the speaker accepts it locally - **public, not a secret**), subprotocol
  `v1.api.smartspeaker.audio`, a self-signed certificate. Messages are JSON arrays `[header, body]`; a command carries
  `namespace`, `command`, a `cmdId`, and `householdId` or `groupId`.
- An empty command `[{}, {}]` answers with the `householdId`. `groups:1 getGroups` / `subscribe` (householdId) give
  `groups` (`groups[]`: `id`, `name`, `coordinatorId`, `playerIds`; `players[]`: `id`, `name`, `websocketUrl`).
  `playback:1 subscribe` (groupId) pushes `playbackStatus` (`playbackState` `PLAYBACK_STATE_BUFFERING|PLAYING|PAUSED|IDLE`,
  `positionMillis`). `playbackMetadata:1` pushes `metadataStatus` with only `container` = our `dc:title` (no track,
  duration or art, whichever DIDL). `groupVolume:1` pushes volumes.
- `playbackStatus` comes **on every change only** (no ticks; 8 events in ~60 s of commands), each with an exact
  `positionMillis`, 0.0-0.4 s after a command; the 1 s UPnP poll saw the same change 0.3-2.6 s after it. The natural end
  is `IDLE` the moment the audio ends (16.5 s after a seek to 15 s before the end).
- **One socket follows only the groups its speaker coordinates.** Subscribing to another group answers
  `groupCoordinatorChanged` with `groupStatus: GROUP_STATUS_MOVED`, that coordinator's `websocketUrl` and `playerId`.
  On the own group a membership change sends `groupInfo`, `groups` and a `global/groupCoordinatorChanged
  GROUP_STATUS_UPDATED`; the group id (`RINCON_...:100`, name "Sverre + 1") stays while the coordinator stays.
  `groups:1 modifyGroupMembers` added and removed a room in ~0.2 s on the user's speakers - proven, **not used** (§2.6).

### 0.2 UPnP grouping (soco, the reference vibb's sidecar uses)

- Join = on the **joining** room `AVTransport SetAVTransportURI(CurrentURI = "x-rincon:<coordinator uid>",
  CurrentURIMetaData = "")` (soco `SoCo.join`). A room that coordinated a stream of its own stops it.
- Leave = on the **leaving** room `AVTransport BecomeCoordinatorOfStandaloneGroup()` (soco `SoCo.unjoin`). When the
  leaving room is the coordinator, Sonos promotes a member and the stream moves there - exactly the migration vibb's
  stage B2 follows (`_sonos_stream_moved`), already ported.

### 0.3 The DIDL (vibb branch pipewire, `pi/daemon.py` `_sonos_body` 1423-1475, `pi/sonosd.py` `didl()`)

vibb's body is `title` (the episode's), `album` (the library entry's name), `art` (the item's `image` only when it starts
with `http`), and **`duration_s` only in the Storytel branch** (`ep.get("dur_s")`; there `art = ep.get("art_url") or
art`). NRK series go out as `nrk_program` with no duration. The app: `PlayBodies.build` (`sonos/PlayBody.kt`) passes
`item.durationMs` for **every** kind (url, nrk_program, Storytel) and `SonosController.playIndex` (~779) calls it
without `art`. The phone has **no http picture to give**: a listing's `art` and the library's `cover` are 64-hex hashes
the server serves only to the phone's bearer (music/CLAUDE.md, design 26a "Merge of main with design 27"). So vibb's art
rule yields nothing here, which is also the user's "no art" (PLAN); the duration is the one real difference.

The app's own progress does not depend on the DIDL's duration (confirmed in the code): `SonosController.played()` seeds
the snapshot's `durS` from the listing, and `accept()` (the duration carry, 1053-1078) takes the listing's for our item
whenever the speaker reports none or `0:00`, flagged `durFromListing` so it never decides "heard to the end". One
consequence of the fix, wanted: a speaker that echoed our DIDL duration as `TrackDuration` (the fake does; real ones
likely) had that echo counted as the speaker's own measurement; after the fix only a real measurement counts (vibb's
rule, `_sonos_bookmark_now`'s "only when the SPEAKER measured it").

### 0.4 What the app does today (read 2026-10-21)

- **Polling** (`SonosSession.cadenceMs`): PLAYING 15 s cruise, 5 s within `TAIL_FAST_S` (45 s) of the speaker's own
  duration, 5 s for `VERB_FAST_MS` (30 s) after a verb or an arm, PAUSED 15 s, STOPPED 60 s (5 s while a migration
  window is open), UNREACHABLE backoff to 300 s; disarm after 10 min neither PLAYING nor PAUSED. Every live poll writes
  the bookmark (`pollTick` -> `bookmarkNow(false)`). Freshness = measured within the cadence chosen after that poll +
  10 s (`Fields.fresh`).
- **The advance** (`pollTick` 1041-1044): STOPPED after a poll saw the position within `ENDS_NEAR_S` (20 s) of the end
  -> `advance()` (repeat, "Til slutten", gated). Silence between episodes is the time to the next poll: up to 5 s with
  a speaker-known duration, up to 15 s without (an extension-less URL reads `0:00`), plus the play.
- **Position**: the poll's `RelTime` (whole seconds) anchored at its `measuredAt` and extrapolated (`position()`,
  `SonosSpec.positionAt`); a seek target held up to 12 s (`optPos`), the pause freeze (`frozen`), a play's seed held
  until the speaker shows our uri or `SETTLE_MS` (8 s) after the answer (`pending`).
- **Groups**: the sheet's rows are the cache's groups (`SpeakerCache.rooms`), refreshed when the sheet opens
  (`ROOMS_REFRESH` -> `RoomDirectory.refresh`), by a scan, and - only under a limited room scope while a room plays -
  every 60 s (`PlaybackService` `SCOPE_WATCH_MS`). The controller's label `name` changes only at a migration follow. No
  grouping verbs exist.

| Outside case | Today |
|---|---|
| A room joins our group | Not seen until the sheet opens (60 s under a limited scope); the player's label stays "Stua"; the joiner keeps its own volume, above the cap or not. |
| A member leaves | The same. |
| Our coordinator leaves (the stream moves) | Our uid reads STOPPED with an empty TrackURI at the next poll (5-15 s), then `maybeProbeMigration` (3 tries, at the 5 s cadence) and `follow()`: adopt, `renderer.json`, the scope's look. 5-25 s in all. |
| Our room joins another group | `x-rincon:` on the next poll (up to 15 s) -> `GROUPED_AWAY` "Spilles i en annen gruppe", the position hidden, nothing acts. |

## 1. Shape

One WebSocket per Sonos session, to the speaker the controller commands, carrying **events only**; UPnP does every
command and stays the only ours-verifier and the only source of a bookmark. An event (a) anchors the shown position and
state at once, (b) wakes the poller for a **confirm poll** - so everything that decides (ours, the advance, the
migration follow, grouped away) still decides on a UPnP snapshot, only sooner. If the socket is gone the app is exactly
today's plus the 1 s display poll of §2.4: **we lose speed, never a feature.**

## 2. Decisions

### 2.1 The DIDL fix (first commit of 53-a)

- `PlayBodies.build`: `durationS` = the listing's duration **only** for `Source.STORYTEL`; `null` for `url` and
  `nrk_program`. `art` keeps vibb's rule (http only); the call site in `SonosController.playIndex` passes the item's
  picture explicitly as `art = null` with a comment: the phone holds hashes only (§0.3). `album = entry.name`, the
  title, no artist - unchanged. `Didl.kt` (`didl()`) is **not touched**. The comments in `PlayBody.kt` ("The listing's
  duration rides along") and design 26a §2.5 are corrected.
- Golden DIDLs: `src/test/resources/sonos/didl_{url,nrk,storytel}.xml`, built by `scripts/sonos-play-test.py`'s vibb
  mode (`built_for(ep, "vibb")`) for fixed inputs; `PlayBodyTest` builds the same three through `PlayBodies.build` +
  `didl()` and compares byte for byte. `scripts/fake-sonos.py` keeps reading `res@duration` when present (a Storytel
  book) and its `--duration` otherwise; its `--selftest` still passes.

### 2.2 The socket (`sonos/SonosEvents.kt` pure, `sonos/SonosSocket.kt` OkHttp)

- **Dependency**: OkHttp 4.12.0's own WebSocket (`OkHttpClient.newWebSocket`), already on the classpath
  (`libs.squareup.okhttp`); JSON with kotlinx.serialization's `JsonElement` (already there). **No new dependency**, so
  no new `verification-metadata.xml` entries (root CLAUDE.md, "Gradle build chain").
- **Lifetime**: open while the session is **armed** (a play or an adopt armed it; the same rule as polling, battery)
  and Wi-Fi is up (`SonosHome`'s gate); closed at the disarm (`Tick.Disarmed`, 10 min not live), `stop()`, the park,
  the hand-off, `release()` and on Wi-Fi loss. Never while the phone renders, never for a room we don't command, never
  from the standalone suspension path. The renderer (`SonosRenderer`) owns it and reads `Fields.armed` from each poll
  result on main.
- **Target**: the cached ip of `controller.uid` (`SpeakerCache.resolve`), built with `HttpUrl.Builder` (`https`, the ip,
  port 1443, `/websocket/api`; OkHttp maps it to `wss`), as every URL here. **A URL from a message is never connected
  to**: a `MOVED`'s `websocketUrl` and the `groups`' `websocketUrl`s are read for the player id only. When the
  controller's uid changes (a migration follow), the socket is closed and opened to the new uid's cached ip.
- **Handshake** (the pure reducer `SonosEvents.Plan`, mirroring `sonos-ws-probe.py`): the empty command -> the
  `householdId` -> `groups:1 getGroups` -> our player = the `players[]` entry whose `id` equals `controller.uid` (fallback:
  whose `websocketUrl` host equals the ip we dialled) -> its group -> `playback:1 subscribe` (that `groupId`) and
  `groups:1 subscribe` (householdId). Not subscribed: `playbackMetadata:1` (only our title - nothing to learn) and
  `groupVolume:1` (no reader: the slider is the coordinator's own volume, §7 Q1). A `groups` event whose group of our
  player has another id subscribes `playback:1` to the new id (the probe's rule). A `success: false` answer to our
  playback subscribe, or a `MOVED` for it (our uid no longer coordinates), leaves the socket open for `groups` but
  **not healthy**.
- **Health**: healthy = open, the playback subscription of the group our uid coordinates acknowledged, and no
  disagreement: two polls in a row that see ours-PLAYING vs a socket state of PAUSED/IDLE (or the reverse), each 3 s or
  more after the socket's last event, mark it dead (closed, counted as a failure) - the firmware may stop pushing
  without closing. OkHttp's `pingInterval(30 s)` (the probe's heartbeat, which ran for minutes on the user's speakers)
  closes a socket whose pong doesn't come.
- **Failure and backoff**: a TCP or TLS failure, a close, a dead ping -> reconnect after 1, 2, 5, 15, 60 s, then every
  300 s while armed; reset once a socket stayed healthy 60 s. An HTTP answer instead of the upgrade (401/403/404 - the
  key or the endpoint closed by a firmware update) -> no new attempt to that uid for 6 h (process memory). Nothing is
  persisted. Every failure leaves the app on today's polling plus §2.4.
- **Trust** (the self-signed certificate): the socket's own client - `home`'s SOAP client `.newBuilder()`, unbound to
  any `Network` (26a §6.5), `connectTimeout` 2 s - gets an `X509TrustManager` that accepts the speaker's chain **and a
  host gate before any connect**: the host is an IP literal (`isIpLiteral`) in a private range (`isPrivateAddress`:
  RFC 1918, link-local, IPv6 ULA/link-local), it equals the cached ip of the uid we command, the port is 1443, the path
  `/websocket/api`; the `HostnameVerifier` accepts exactly that ip. No global trust-all: the SOAP client, the media
  clients, the public-only client and the network security config (system CAs only) are untouched. Why this is enough:
  the socket only reads, and what it says is checked - a spoofed event can at worst move the bar until the confirm poll
  (§2.3) or wake a poll; the IDLE advance and every ours decision are UPnP's, and UPnP to the same address is already
  cleartext. Certificate pinning would add nothing against an attacker who can already answer port 1400. The lint ids
  for a custom trust manager are suppressed on that one class with this reason.
- **Threading**: OkHttp's reader thread parses (messages over 64 KB dropped), stamps `SystemClock.elapsedRealtime()` at
  receipt, and posts a compact `SocketEvent` to the main thread (the renderer's `Handler`), where the controller lives.
  Commands are sent from the listener (OkHttp's `send` is thread-safe). The socket never goes through the actor: SOAP
  ordering is the actor's business, events are not commands.
- `SocketEvent` = `Playback(groupId, state, positionMs, at)`, `Groups(ourGroupId, members)`,
  `Moved(playerId)`, `Health(healthy)`. Logging through `SonosHome.log` with fixed words, uids and states - never a
  body (there are no secrets in one, but titles are the kid's listening).

### 2.3 What an event does (`SonosController.onSocket`, main thread)

- **Every event wakes the poller** (`SonosPoller.wake()`; its channel is conflated, so a burst costs at most one poll
  after the one running): the **confirm poll**. A poll of the speaker on the LAN answers in ~0.1-0.3 s.
- `Playback` of our subscribed group, while `session` and not `closed`:
  - **Anchor**: PLAYING (moving), PAUSED and BUFFERING (not moving) set `wsAnchor = (positionMs, at, state, gen)` - used
    by `position()` when it is newer than the snapshot's `measuredAt`, the snapshot is fresh and ours, `pending` is null
    and no IDLE came since the last ours poll (a track boundary needs a poll's ours first; a hijack shows as IDLE then
    BUFFERING). Precedence in `position()` is unchanged otherwise: `startPending`, the seek hold `optPos`, the pause
    freeze `frozen`, then the newest of the WS anchor and the poll. An event that lands while `pending` is set and our
    play has answered (`pending.uri != null`) is **parked**; the confirm poll that clears `pending` as landed and ours
    applies it. One that lands before the play answered (the old track's IDLE) is dropped.
  - **State**: the effective transport becomes the socket's state when newer than the poll (under the same gate); a
    socket state equal to `optTr`'s confirms and clears it (the play button settles in 0.0-0.4 s). A pause from the
    Sonos app or the speaker's button shows at once. `optPos` is cleared when a socket position lands within
    `SEEK_TOL_S` of the target.
  - **IDLE near the end of our item -> advance at once**: when `playing` is our current item, nothing is pending,
    stepping or seeking, and max(the event's position, the anchor's extrapolation at `at`) is within `ENDS_NEAR_S` of the
    controller's duration (the carry: the listing's when the speaker has none), the controller sets `idleEnd = gen` and
    the confirm poll goes out; `pollTick`'s advance condition becomes `STOPPED && (endsNear > 0 || idleEnd == gen) &&
    playing != null`. A confirm poll that still reads PLAYING or TRANSITIONING asks one more after 1 s; then the normal
    cadence. `idleEnd` is cleared by any load, play, step, seek, stop or after 10 s. So an IDLE from anything but the
    natural end decides nothing: our own SetAVTransportURI (pending), the stall retry's Stop (it runs inside a poll
    job, so the confirm poll after it sees PLAYING), our room joining another group (the poll reads `x-rincon:` - not
    ours), a stop mid-item (not near the end). `advance()` itself is unchanged: `mayPlay()`, "Til slutten"
    (`holdAtEnd`, checked first), repeat (`advanceIndex`), the queue's end.
  - **The socket never writes Room.** The bookmark stays `bookmarkNow` on a UPnP snapshot (measured, ours-verified,
    `SonosBookmarks`' hold and regression guard): an event can't name its stream.
- `Groups` and `Moved` (53-a): the confirm poll only. 53-b adds §2.8.
- `Health` -> the session's cadence (§2.4) and the renderer's log line.

### 2.4 Precision

- **No backward jump under 1 s** (`SonosSpec.floorMs`, a pure rule in `logic/` - `sonosFloor(shownMs, newAnchorMs,
  sameItem)` - tested alone): when a new anchor (socket or poll) would put the shown position of the **same item**
  (same `playing.itemKey` and uri, same generation, no seek, step or play since) behind what is shown by less than
  1 000 ms, the shown value is kept as a floor: `positionAt = max(extrapolated, floorMs)` while moving, the floor
  itself while not; the floor goes once the extrapolation passes it. A correction of 1 s or more, a seek (also "15 s
  tilbake"), a step, a new item and a play move at once. Display only (`positionMs()` stays on `GuardsTest`'s
  allowlist; no reader of it persists).
- **The 1 s display poll only without a healthy socket**: while the player screen shows (`PlayerHub.playerShown`, a
  `MutableStateFlow<Boolean>` set by `PlayerScreen` while composed and the activity is resumed; same process) and the
  last snapshot is ours, fresh and PLAYING, the session polls every 1 000 ms (`POLL_PRECISE_MS`) - only when the
  socket is **not** healthy. With a healthy socket every change is pushed with an exact position, so a 1 s poll would
  add load and no precision. The flag flipping on wakes the poller.
- **What the extra polls must not change** (the confirm polls and the 1 s polls are polls like any other):
  - Freshness: `measuredCadenceMs` is the normal rule's cadence, never 1 s - else the screen going off (the 1 s polls
    end) would let the snapshot read stale for seconds, `showsPlaying` false and the wake lock go.
  - The stall retry (Stop, Play, seek): a frozen poll counts only when it is `POLL_MS` (5 s) or more after the last
    counted one - at 1 s polls a 3 s rebuffer would otherwise fire it.
  - The bookmark: a non-forced write from `pollTick` at most every 5 s (`BOOKMARK_MIN_MS`).
  - The migration probe: a try only 1 500 ms or more after the last (`MIGRATE_SPACING_MS`); `MIGRATE_TRIES` stays 3.
  - The aux reads (GetVolume, the group's coordinator): every `AUX_EVERY` polls but not more often than every 60 s.

### 2.5 The cadence with a healthy socket: unchanged

Decided: the 15 s cruise, the tail's 5 s, the verb window and the rest stay as they are, socket or not. The poll is the
only ours-verifier (an event names no stream), the bookmark's clock (a crash loses at most one cadence) and the
fallback that must already be right when a socket dies silently; relaxing it would make a dead socket cost exactness
for up to a cadence before the disagreement rule notices. The saving would be two SOAP calls on the LAN every 15 s,
while what costs battery during a session - the partial wake lock and the Wi-Fi lock (`SonosLocks`) - is held while
playing either way.

### 2.6 The grouping verbs: UPnP, as soco

Join (`x-rincon:`) and leave (`BecomeCoordinatorOfStandaloneGroup`) go over UPnP (§0.2), in the room's actor, through
`SonosTransport.join(roomUid, coordinatorUid, cap)` / `leave(roomUid)` and two `SpeakerControl` methods
(`joinGroup(coordinatorUid)` = the `SetAVTransportURI`, `becomeStandalone()`), each against the cached speaker of
that room with `verify = true` (the uid at the ip, as a play) and a 4 s budget. Why not the WebSocket's
`modifyGroupMembers`, which works on the user's speakers: the local WebSocket is not an official third-party API (the
key is the RFC 4122 example UUID a speaker happens to accept) and may close with a firmware update; every other command
already goes over UPnP with its timeouts, the actor's order and the gates; and if the socket dies we lose only speed,
never a feature - grouping included.

### 2.7 "Spill av på" with grouping (53-b; the sketch's three artboards, `Main`, `Grouped`, `Busy`)

The sheet's look stays the app's (`SheetRow`, `Palette`, the 34 dp glyph disc, `OUTPUT_SHEET_MAX_SHARE`); the sketch
gives the layout, the words and the behaviour:

- **No room renders** (the phone or a headset plays): the rooms as today - a group is one row, no + and no −.
- **A room renders** (`current.kind == SONOS`) and our uid coordinates one of the rows:
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
  coordinator last** (a coordinator joined first would hand its stream to a member, which would then sound for a moment),
  each one capped first (§2.9). A room playing something else may be added: its own stream stops and it joins (the
  user's decision; no confirm). It is a play path: in `Commands.PLAYS` (refused while suspended) and behind the
  controller's `mayPlay()`.
- **−** = `Commands.ROOM_LEAVE` with the member's uid: `BecomeCoordinatorOfStandaloneGroup` on it; it stops, the rest
  play on. On **our coordinator** Sonos moves the stream to a remaining member and the app follows it (§2.8 case 3;
  the controller knows it is coming: the poll burst and the first remaining member as the probe's candidate). A group
  block always has two or more members; once one is left it is that room's ordinary selected row with no "−" - so a
  "−" never leaves nothing playing, and stopping stays the play button's (§7 Q2).
- **"Spiller noe annet"**: when the sheet opens (`ROOMS_REFRESH`), after the topology refresh, `RoomDirectory` asks
  each other row's coordinator `GetTransportInfo` - in parallel on IO, 1 s each, 2 s in all, at most 8 - and
  publishes the PLAYING ones as `RoomsView.busy` (a set of uids). Once per opening and per `Groups` event while it is
  open; never on a timer, never while the sheet is closed.
- **Pending and failure**: a tapped + or − shows the `Spinner` in its place until the topology shows the change or 8 s;
  the service answers the custom command with `SessionResult.RESULT_SUCCESS` or an error, and an error puts "Fikk ikke
  kontakt med Kjøkken - er den på?" on that row (design 52's failure line, as `headset_no_contact_row`) until the next
  tap or the sheet closes. After every verb the service runs one `refreshQuietly()` (the `Groups` event does it too when
  the socket is up).
- **The scope**: the PWA's room scope still limits the sheet (`allowedRooms`: a row only when every member is allowed),
  so a + exists only on allowed rows and a join never makes our group forbidden; the service's belt checks the row's
  members again. While the rooms are stale ("Sonos · svarer ikke") there is no + and no −.
- The sketch's bottom paragraphs ("+ betyr «spill også her» ...", "− tar et rom ut ...", "Et rom som spiller noe annet
  ...") are read as the artboards' captions, not sheet text (§7 Q3).
- `Room` gains `named: List<Pair<String, String>>` (the known members' uid and name, coordinator first, the order of
  `names`), filled by `SpeakerCache.rooms`. `outputSheetRows` gets `SheetItem.Group(room, members)` and the per-row
  flags (`join`, `busy`, `pending`, `failed`) from a pure `groupSheet(...)` in `ui/OutputRows.kt` (our uid, whether a
  room renders, `showsPlaying`, `RoomsView`); `OutputSheet` only draws it.
- Strings (`values` + `values-nb`): `room_switch_here` "Tap to switch here" / "Trykk for å bytte hit",
  `room_playing_now` "Playing now" / "Spiller nå", `group_playing_both` "Playing in both" / "Spiller nå i begge",
  `group_playing_all` "Playing in all %1$d" / "Spiller nå i alle %1$d", `room_busy` "Playing something else" /
  "Spiller noe annet", `room_join` "Also play in %1$s" / "Spill også i %1$s", `room_leave` "Take %1$s out of the group" /
  "Ta %1$s ut av gruppen", `room_no_contact` "Couldn't reach %1$s - is it on?" / "Fikk ikke kontakt med %1$s - er den
  på?". Glyphs `Icons.add`, `Icons.remove`, `Icons.roomGroup` are stroked paths in `ui/Theme.kt` like `roomSpeaker`
  (our own drawing of the sketch's SVGs, GPL).
- `FitTest`: the group block with two and three members and the + row at 320/360/411 dp; the bar's room label with a
  four-room group (cut with an ellipsis, never wrapping).

### 2.8 Grouping done outside the app (53-b)

| Case | 53 |
|---|---|
| A room joins our group | `Groups` -> one `refreshQuietly()` (debounced 300 ms: `groupInfo`, `groups` and `groupCoordinatorChanged` come together) -> `rooms.state` -> the sheet (open or not), the label, the scope check, the joiner capped once (§2.9). |
| A member leaves | The same. |
| Our coordinator leaves | `Moved(playerId)` / `Groups` -> the **poll burst** (now, +1.5 s, +3.5 s: `poller.wake()` and `work.after`) and `SonosSession.hintCandidate(playerId)` - the probe asks that coordinator first; the existing ours-gate (`sameStream`) decides; `follow()` adopts, writes `renderer.json`, `onFollowed` runs the scope look; the socket moves to the new uid. ~1-2 s in all. |
| Our room joins another group | `Groups` / `Moved` -> the confirm poll reads `x-rincon:` -> `GROUPED_AWAY` at once; nothing is sent, nothing follows (the stream there isn't ours); the socket stays on our room (not healthy: our uid coordinates no subscribed group) and resubscribes when a `groups` event shows our room coordinating again. |

- **The label follows**: `SonosController.relabel(label)` from each `rooms.state` change (`home.label(controller.uid)`:
  the row's joined label, else the cached name), so the bar and the player read "Stua + Kjøkken" as it happens.
  `renderer.json`'s name is left as written (a pick, a follow): the label is recomputed from the cache at every start.
- **Without the socket** the same paths run from the **group watch**: `PlaybackService`'s 60 s look
  (`SCOPE_WATCH_MS`, one GetZoneGroupState, never discovery) now runs while a room plays **whatever the scope** when the
  socket isn't healthy, and under a limited scope always (as today, a belt for the parent's rule). The −'s own follow
  doesn't need it: the burst and the candidate come from the tap.

### 2.9 The volume cap on joining rooms

"The volume cap set on each joining room": a room that joins our group - by our + or from outside - and is louder than
the parent's cap (`volume_cap_pct`, read live) is set to the cap **once**, through the actor (`SonosTransport.capVolume`
on that room, `MEMBER_CAP_BUDGET_MS`). For our + it happens **before** its `x-rincon:` (it never sounds above the cap);
for an outside join as soon as the topology shows it (it may sound for a moment first - unavoidable). The controller
keeps the members it has capped in this session (`cappedMembers`, cleared by a new session) and never touches a room
twice; a volume set later from the Sonos app is never pulled down (26a §5.3, vibb never fights an external change).

### 2.10 What stays

Ours-detection (`sameStream`), `SETTLE_MS` and the seed's hold, `optPos`/`frozen`, the stall retry and the hijack
states, `bookmarkNow` and `SonosBookmarks`, "Til slutten" (`holdAtEnd`, the 3 s ramp), repeat (`advanceIndex`), every
gate (`playAllowed`, `mayPlay`, the scope's `groupAllowed` belt), "nothing starts by itself" (an event never starts
anything: it anchors, shows, or asks for a poll; a natural advance is today's gated `advance()`), the disarm, the locks,
the threading (controller on main, SOAP in the actor), the hand-off and the park, `adoptLive`'s zero commands.

### 2.11 Contracts, secrets, licences

No shared contract changes: no server, launcher or AIDL change; `ROOM_JOIN`/`ROOM_LEAVE` are app-internal session
commands (`Commands.ALL`), not in `bridge_contract.json`; the report and `music_state` are unchanged. The API key is the
RFC 4122 example UUID, public (`sonos-websocket` ships it) - a constant `SONOS_WS_API_KEY` in `SonosSocket.kt` with that
comment; it looks fake because it is the example. All new code GPL-3.0; the protocol steps follow our own probe scripts
(the sonos-websocket library is read for the handshake, not copied).

## 3. Tests (lean; JVM)

- `PlayBodyTest`: duration only for Storytel; `nrk_program` and `url` none; a hash never becomes `albumArtURI`; the three
  golden DIDLs byte for byte (§2.1).
- `SonosEventsTest` (the reducer, from fixtures `src/test/resources/sonos/ws/*.json`): the handshake's commands in
  order; our player by `id`, else by the `websocketUrl` host; a changed own group id resubscribes; `MOVED` is an event,
  never a connect; a failed subscribe is not healthy; both `PLAYBACK_STATE_PLAYING` and `PLAYING`; junk, an unknown type
  and an over-size message dropped.
- `SonosSocketTest`: the host gate (a public ip, a name, another port or path, an ip not cached for our uid: refused
  before any connect); the backoff sequence and its reset; an HTTP refusal parks the uid 6 h.
- `SonosControllerTest` `d53`: a socket anchor makes the position exact; parked while pending, applied by the landing
  poll, dropped before the play answered; an anchor after IDLE waits for an ours poll; the socket state confirms
  `optTr`; IDLE near the end -> the confirm poll -> STOPPED ours -> `advance()` (and with `holdAtEnd`, repeat one, repeat
  all); IDLE mid-item, IDLE while pending, IDLE then `x-rincon:` -> nothing; no `store.save` from an event.
- `SonosFloorTest` (`logic/`): under 1 s back holds, 1 s or more jumps, a seek back / step / new item jumps, the floor
  ends when passed, paused holds.
- `SonosSessionTest` `d53`: 1 s only while shown + not healthy + ours PLAYING fresh; freshness keeps the normal
  cadence; the stall needs 5 s-spaced frozen polls; probe tries 1.5 s apart, the hinted candidate first.
- 53-b: `SpeakerCacheTest` (`named`); `OutputRowsTest` (the pure `groupSheet`: no + while the phone renders, + on every
  other allowed row, the block with − per member, busy and pending and failed lines, none while stale);
  `SonosTransportTest` `d53` (join = cap then `x-rincon:` with empty metadata on the joining room, a group row members
  first and the coordinator last, leave = `BecomeCoordinatorOfStandaloneGroup` on that room, a closed gate sends
  nothing); `SonosControllerTest` `d53b` (an outside joiner capped once and only when louder, the label follows);
  `FitTest` (§2.7); `SourceScanTest` `d53` (the trust manager, `checkServerTrusted` and a `HostnameVerifier` only in
  `SonosSocket.kt`; the socket client used for nothing else; no `wss://` literal; the strings in both languages).
- `scripts/fake-sonos.py`: `x-rincon:` joins (a coordinator's own stream stops), `BecomeCoordinatorOfStandaloneGroup`
  leaves (a coordinator's stream moves to its next member, as `move`), with a canned reply
  `become_standalone.xml` in `src/test/resources/sonos/` (`SonosFixturesTest` parses it); `--selftest` covers both.
  **No WebSocket in the fake** (the app dials 1443 on the fake's ip and is refused): the emulator round
  (`docs/testing/emulator.md` §6h) checks the fallback - the 1 s display poll, the STOPPED advance - and the verbs. The
  socket path is checked on the user's speakers (§5).
- The `ws/` fixtures: until the user's capture (§5, item 1) they are written from §0.1's shapes and marked provisional
  in their README line; the capture replaces them in one commit (uids and the household id replaced by fake ones,
  `RINCON_000000000000014xx`, `Sonos_FAKE...`).

## 4. Implementation rounds

Sequential (both touch `SonosController`, `SonosSession`, `SonosRenderer`), each with its code QA and fix round, on
branches `sonos-53a` / `sonos-53b`.

- **53-a - events, precision, the advance, the DIDL.** §2.1-§2.5 and §2.10: the DIDL fix (its own commit), `SonosEvents`
  and `SonosSocket`, the renderer's socket lifetime, `onSocket` (anchor, state, confirm poll, the IDLE advance; `Groups`
  and `Moved` only wake), the floor, `PlayerHub.playerShown` and the 1 s display poll, the poll guards (freshness, the
  stall, the bookmark throttle, the probe spacing, aux), `sonos-ws-probe.py --save FILE` (the raw `[header, body]` lines
  as JSONL, ids replaced) for the fixture capture. Delivers: the next episode within ~1 s of the end, an exact bar, a
  pause from the Sonos app shown at once, and outside grouping cases 3-4 already a few seconds faster (the confirm
  poll).
- **53-b - grouping.** §2.6-§2.9: the verbs, `ROOM_JOIN`/`ROOM_LEAVE`, the sheet, the busy look, the label, the joiner
  cap, the poll burst and the probe's candidate, the group watch for every scope, fake-sonos' verbs. Delivers the
  approved sketch and the four outside cases "as it happens".

Docs with each round: music/CLAUDE.md (the socket's rule and trust, the extra polls' guards, grouping and the cap; the
DIDL rule's "Checked 2026-10-21" sentence becomes "done"), design 26a §2.5/§4.3/§5.1/§5.5 pointers to this note,
PLAN (the "To decide" DIDL item and the "Design 53" entry; backlog items for anything found).

## 5. Owed device checks (the user's speakers, the Jelly Star, a debug build; logcat `VibbSonos`)

1. **Capture**: `sonos-ws-probe.py <ip> --all --save ws.jsonl --do group=<room> ungroup=<room> --volume 0` once, and a
   natural end with `sonos-play-test.py`; the fixtures come from them.
2. **Rapid skips**: five nexts within 2 s on a playing room, with the socket and with it down: one play per settled
   want, the title and bar never flap back to the old episode (the Pi observation, "pi misforstår etter optimistisk
   start ... flapper den tilbake").
3. **Natural end**: the next episode starts within ~1 s of the end (`IDLE` -> confirm poll -> advance); "Til slutten"
   holds at the end; repeat one and all; a podcast with an extension-less URL (the speaker's `0:00` duration).
4. **Precision**: the bar against the Sonos app's over 10 min; pause and resume show no backward jump; a seek lands
   at once; with the screen off and on again nothing reads stale (the locks stay).
5. **The socket gone**: power-cycle the coordinator while playing (the reconnect and backoff in logcat, playback
   followed after it comes back); the emulator's refused socket (§3): 1 s polls only while the player shows, the
   STOPPED advance. Report whether a firmware update on the house changed the endpoint (the 6 h park in logcat).
6. **Hold Play on a speaker, the four cases**: (a) Kjøkken joins our Stua - the open sheet and the player's label read
   "Stua + Kjøkken" within ~1 s, Kjøkken brought down to the cap if louder; (b) Kjøkken leaves - back to "Stua"; (c) Stua
   (our coordinator) leaves "Stua + Kjøkken" - Kjøkken plays on and the app follows within ~2 s ("stream moved" in
   logcat), the label "Kjøkken", the socket on Kjøkken, `renderer.json` updated; (d) Stua joins Barnerommet playing
   something else - "Spilles i en annen gruppe" within ~1 s, no advance, nothing sent; Stua out again - the player's
   line for a stopped room.
7. **+ and −**: + on a quiet room (it joins, plays with us); + on a busy room ("Spiller noe annet": its stream stops, it
   joins); + on a busy group row (both join, members first: no blip of the other stream); − on a member (it stops, the
   rest play on); **− on our coordinator** (the rest play on, the app follows - if Sonos instead keeps the stream on the
   leaving room, report it: the fallback is `DelegateGroupCoordinationTo(NewCoordinator, RejoinGroup=0)` before the
   leave, a change in this note); a switched-off room's + -> "Fikk ikke kontakt med ... - er den på?".
8. **The cap on join**: a room at 80 with the cap 40 joins at 40 before any sound; a room at 20 stays at 20; a joined
   room turned up in the Sonos app afterwards is not pulled down.
9. **The DIDL**: a podcast, an NRK series item and a Storytel book each play, `didl accepted` in logcat, the Sonos
   app shows title and album; `sonos-play-test.py --didl vibb` and the app's DIDL are the same string.
10. **The certificate and the key** on every speaker model in the house: the socket opens; a debug build logs the
    certificate's subject and issuer once (for a possible later check that the subject names the uid).
11. **Battery**: no socket while the phone renders; the socket closes at the disarm (10 min paused) and at a switch
    home (logcat).

## 6. Deviations from vibb

- vibb polls only; 53 adds the speaker's push events (§1) - display, state and timing only; every decision stays on a
  UPnP poll, as in vibb.
- vibb has no grouping verbs (the household groups in the Sonos app); 53's + and − are the user's design, the verbs
  soco's (§2.6).
- vibb advances on STOPPED near the end at its poll; 53 advances on the confirm poll an `IDLE` asks for (§2.3).
- vibb caps nothing on grouping (it caps nothing); the cap on joining rooms is the parent's rule extended (§2.9).

## 7. Open questions (the user)

- **Q1. Volume on a group.** The keys and the slider set the coordinator room's own volume (vibb's, 26a §5.3), so in
  "Stua + Kjøkken" only Stua changes. Move them to the whole group (each member ±3 from its own volume, each capped;
  or Sonos' `GroupRenderingControl`)? Default in 53: unchanged.
- **Q2. "Tas det siste ut, stopper den."** A group block always has two or more rooms, so the last room is an ordinary
  selected row without "−" and stopping stays the play button's (§2.7). Or should a lone room also get a "−" that stops
  it (bookmark, Stop, the phone paused)? Default: no "−" on a lone room.
- **Q3. The sketch's bottom lines.** Read as captions, not shown in the app (the buttons carry "Spill også i X" /
  "Ta X ut av gruppen" for TalkBack). Should the sheet show one quiet hint line instead? Default: no line.
