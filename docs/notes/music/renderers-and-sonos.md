# Music notes: the renderer axis and Sonos rooms

Moved from music/CLAUDE.md on 2026-11-01.

## Rules that are easy to break (continued): the renderer axis and Sonos

- **The renderer axis** (26a §0, §3-§4): `attach(next)` is the one place the renderer changes (the old one saved
  with `force` and paused under `selfPausing` before it is detached; the session always wraps
  `KidPlayer(renderer.player)`). `Renderer.saveNow` is the only persistence path: the phone's `savePosition`, on Sonos
  `bookmarkNow` (a measured position of the item the speaker plays, through vibb's hold and regression guard; never an
  optimistic, extrapolated or seeded value, never a listing duration). `positionMs()` is for display only (an
  allowlist of readers in `GuardsTest`). `showsPlaying` (fresh, reachable, ours, PLAYING) is what `publish` and
  `setNowPlaying.playing` report, not `playWhenReady`, which §3.2's table holds through the first failed polls so the
  FGS survives a transient.
- **Sonos threading** (26a §0): controller state is main-confined (`SonosController`, inside the `SimpleBasePlayer`'s
  looper); every SOAP sequence runs in the room's one `SonosActor` (a Channel consumed on IO), its results posted back
  to main before the next job runs. Handlers return a completed future at once and express intent through the
  optimistic fields; nothing waits on SOAP on main. A play generation drops a superseded result.
- **renderer.json** (`RendererChoice`, `noBackupFilesDir`): the room, the entry, the item and the **built** uri -
  for a Storytel book its marker `storytel:book:<cid>`, never the signed URL: a uri is written only by
  `RendererChoice.played`/`followed`/`adopted`, each through `storedUri` (design 26b §5.3, `GuardsTest`) -
  written before a switch's first SOAP; it goes back to the phone only on a definite answer (`roomVerdict`). At start
  `RendererGate` holds every custom command and playback resumption until one look at the stored room has decided
  (`adoptLive`: vibb's adopt, **zero transport commands**, kept only for our own live PLAYING/PAUSED stream - matched
  through `sameStream`, a marker by the live URL's `consumableId` - the session going on with the live TrackURI; else
  the phone, paused). On a room, resumption starts the speaker (gated) and returns no local items.

- **The DIDL is vibb's, field for field, and is never extended** (the user, 2026-10-20: "Ikke endre noe i didl!!" ...
  "å sende med mer enn vi gjør i vibb pi gjør at det blir rejected"): a speaker refuses a `SetAVTransportURI` whose
  metadata carries more than vibb's. `Didl` is vibb's `didl()`; what goes into it is vibb's `_sonos_body` (daemon.py,
  branch pipewire): title, album, the picture when it is http, a `duration` on the `res` only for Storytel - nothing
  added. Done in design 53-0 (§2.1): `PlayBodies.build` gives a `duration` only to a Storytel book and no picture (the
  phone holds only hashes, so vibb's http-art rule yields nothing); one pure `builtDidl(body, uri)` builds the DIDL for
  `SonosTransport.play` and `PlayBodyTest`, which compares it byte for byte with `src/test/resources/sonos/didl_*.xml`
  (written by `scripts/sonos-play-test.py --golden DIR` from vibb's per-kind arguments - regenerate, never hand-edit).
  The listing's duration of every kind is `PlayBody.cadenceHintS`: the session's tail cadence only, never the DIDL.
- **Nothing starts by itself on Sonos either**: adopt sends nothing, a stale or unreachable speaker shows the play
  button, room -> phone plays on only when the speaker was PLAYING. **Every play path is gated** (`playAllowed`, then
  the controller's `mayPlay` before any SOAP): a start, a resume, a step, a natural advance, OUTPUT to a `sonos:` key
  (it starts the speaker, so it is a play), a "+" in "Spill av på" (`ROOM_JOIN`, design 53 §2.7: another room starts
  sounding) and resumption; a skip on a paused, stopped or not-yet-started room only
  moves where the next play starts.
- **The Sonos event socket** (design 53 §1-§2.5, round 53-a; ours, not vibb's): one WebSocket per room session to
  `wss://<cached ip>:1443/websocket/api` (`X-Sonos-Api-Key` = the RFC 4122 example UUID, public; subprotocol
  `v1.api.smartspeaker.audio`), **events only** - UPnP still sends every command and is the only ours-verifier and the
  only source of a bookmark; the socket never writes Room and never starts anything. Open while `socketWanted`: the
  session armed, Wi-Fi up, not closed, the uid not parked; any change of the Wi-Fi network or of the uid we command
  (a follow) closes it and opens it again; none while the phone renders (only a `SonosRenderer` owns one). Healthy =
  open, our coordinator's `playback:1` subscribed and acknowledged, its `getPlaybackStatus` read, and live: a verb of
  ours against a confirmed opposite state (a pause on PLAYING, a resume on PAUSED, a play or a step against the state
  the socket and the poll agree on - never a seek while paused or a no-op, N3) owes a `playbackStatus` within 3 s -
  one that came after the verb went out, also before its SOAP answer reached main, pays it (qa-53a-code 2) -, and
  two ours polls against the socket's state 3 s after its last event also close it. Reconnects 1, 2, 5, 15, 60 s, then
  300 s, reset only after a healthy socket passed a liveness check; an HTTP answer or five refused connects (TCP or a
  refused certificate) at one ip park the uid 6 h at that ip (process memory) - another ip in the cache voids the park
  and restarts the streak, so a stale ip never parks a good speaker; the park is the socket's own (re-checked every
  300 s), not `socketWanted`'s (qa-53c-code 1). An event (on main, `SonosController.onSocket`): BUFFERING is TRANSITIONING (the phase only - never
  the transport, `playWhenReady`, a `Paused`/`Resumed` or a USER pause); our own verb wins until `SETTLE_MS` (a matching
  state confirms it); outside it a PLAYING or PAUSED newer than the last poll is the effective transport and a
  measurement - only while the snapshot is fresh and ours, no play settles (an answered play's event is parked for the
  landing poll, one before the answer dropped) and no IDLE or BUFFERING we didn't cause came since the last ours poll.
  Every event wakes a confirm poll, at most once a second (an IDLE or a `MOVED` at once), none while the last poll
  failed; **a change we didn't cause that the last poll doesn't show** (a PLAYING, PAUSED or IDLE, or a new `itemId`:
  the TrackURI must change) is polled for every second until UPnP shows it, at most 4 s after the event (design 53-d,
  `confirmFor`/`confirmed`), and meanwhile a confirm poll still reading the old state never takes the screen or the bar
  back (`awaited`). **An IDLE near the end advances at once** through the poll's existing gated STOPPED-near-the-end rule
  (`advance()`: `mayPlay`, "Til slutten", repeat); a BUFFERING or PLAYING we didn't cause between the IDLE and that poll
  sets `skipAdvance` - someone else's stream is never replaced. **The bar** (`sonosAnchor`): every measurement of the
  same item in the same motion within a second re-bases smoothly, a second or more (or a seek, a step, a new item, a play)
  jumps; a poll is `RelTime + 0.5 s` at GetPositionInfo's return - within the second it never moves the value (the
  event's exact position stands under a healthy socket) but always moves the anchor's instant on, or the bar would stop
  at the extrapolation cap (70 s since 53-d; events come on a change only; qa-53a-code 1) - display only. **The extra polls** (confirm polls; the 1 s display poll while `PlayerHub.playerShown`
  and the snapshot is ours, fresh and PLAYING, only without a healthy socket) change nothing else: freshness keeps the
  normal cadence, the stall counts polls 5 s apart (an uncounted one leaves the count alone), the probe tries 1.5 s
  apart, the aux at most every 60 s, a poll's bookmark at most every 5 s unless the transport or the stream changed;
  the tail cadence takes the play's listed duration (`cadenceHintS`) when the speaker reads `0:00`. **The slow check
  (design 53-d)**: while the socket is healthy (and the speaker answered, and no position is frozen) PLAYING's cruise and
  PAUSED poll every 60 s (`POLL_SLOW_MS`) instead of 15 s, the cruise cut short where the 5 s tail begins; the tail, the
  verb window, TRANSITIONING, STOPPED, the migration window and the backoff are unchanged, and without a healthy socket
  everything is the 5/15 s cadence and the 1 s display poll as before. The bar's extrapolation cap is 70 s
  (`POLL_SLOW_MS` + the freshness slack); the socket going unhealthy wakes a poll at once; a poll that disagrees with a
  healthy socket looks again 3 s later. Because a measurement can be a minute old: a step, a new pick and the
  player's stop read the old item's place live first when the last measurement is older than 15 s
  (`bookmarkLiveFirst`, in the actor ahead of the play or Stop, never ending a play's settle; a new pick writes through
  the store pinned at the service's last `saveNow` - the old queue's place and bookmark, never the new queue's row), and
  with no volume set of ours as the base the keys and the sleep fade read GetVolume first when the last read is over
  60 s old (`readVolume`; a parent's own turn-down on the speaker is never stepped or ramped back up). ~162 UPnP calls for an hour of a podcast instead of ~542 (design 53 "53-d
  status"). **Our DIDL's id as a hint** (53-d): `playbackMetadata:1` is subscribed for the container's object id only
  (`SocketEvent.Metadata`, never a title); `vibb-1` (`OUR_OBJECT_ID`, the DIDL's `item id`) since our play went out plus
  a parked PLAYING/PAUSED of a new `itemId` re-base the bar and show READY while the play settles - display only:
  `pending`, ours, the bookmark, the advance and every gate stay UPnP's; an absent or other id is no evidence. Tests:
  `SonosEventsTest` (the `ws/` fixtures: the user's capture of 2026-10-21, design 53 §5 check 1, replayed whole -
  only the answer about our own playback subscription counts, never another group's MOVED reply), `SonosSocketTest`,
  `SonosAnchorTest`, `SonosControllerTest`'s and `SonosSessionTest`'s `d53`. Owed: design 53 §5 checks 1-5, 9-11.
- **Never a guess in Room**: `optPos`, the pause freeze, the seed of a play and the age term (capped at 5 s, only
  fresh + PLAYING) never reach a stored position; a bookmark always precedes a Stop or a renderer change (after a
  Stop the speaker reads 0:00).
- **The phone's lifecycle on a room** (26a §6): `MY_PACKAGE_SUSPENDED` (`SuspendedReceiver`, `goAsync`) stops our
  stream with the service up (`suspendStop`) or without it (`SonosSuspension.standalone`): live classify, bookmark,
  Stop, `x-rincon-queue:<uid>#0` (the speaker's own Play button plays nothing; a share link's session then also clears
  the queue - design 54 §2.9, never a podcast's or NRK's, whose household queue stays), disarm. `onDestroy` runs the hand-off
  (bookmark, ours-gated Stop) in a tail after the service; the next adopt and the standalone path wait for it
  (`SonosTail`). A crash leaves the speaker playing until the next start adopts it or a suspension edge stops it.
  `SonosLocks` holds a partial wake lock (timed, renewed) and a Wi-Fi lock while a room is armed and playing or a
  verb is settling; never a screen wake lock (`SourceScanTest`).
- **Volume on a room** (26a §5.3): the keys and slider reach the speaker (remote `DeviceInfo`, ±3 from our own last
  set); every set of ours is clamped to `volume_cap_pct`, read live from the managed config; a session's start (a
  pick, a start, a restart) brings every room of the row louder than the cap down to it once, before Play, inside the
  hush (`SonosTransport.capVolume`); during the session a louder volume set from the Sonos app is never pulled down,
  but a cap lowered at a restrictions broadcast pulls our own louder set (`volOpt`) down once (`capChanged`). No
  audio focus while a room renders (`LocalRenderer.standDown()`). **A group** (design 53 §2.12, the user's Q1; ours,
  not vibb's): the group's value is the mean of its rooms (the system's slider, the player, the sheet's "Begge"/"Alle");
  the keys move every room ±3 from its own last set, the system's slider and "Begge" add the same delta to every room
  (from the slide's or the drag's base, so sliding back restores the balance), each clamped to 0..100 and the cap per
  room (`logic/GroupVolume.kt`: a raise leaves a room at or above the cap where it is, and `setRooms` never sends a
  value above the cap) - never Sonos' `SetGroupVolume`; a member's own slider sets that room only; the known rooms
  move when one can't be read. Our coordinator's set goes through our actor, every other member's through
  `RoomVolumes` (latest-wins, 1.5 s, off the actor). What the app knows of a room's volume is our own last set
  (`volOpts`, per room), else the last read (the sheet opening, the members changing, a group key press while one is
  unknown) - a member's read that differs replaces our set (the Sonos app changed it; never pulled down), but only a
  read started after that room's last own set (`volGen`). `capChanged` runs over every room of our group our own set
  took above the new cap. The sleep timer's ramp fades every known room of the group with the coordinator (off the
  actor for the others, never above the cap) and puts each one's own volume back after the Pause (the user,
  2026-10-21). A lone room is unchanged.
- **Bluetooth headphones** (design 52): `BLUETOOTH_CONNECT` is the app's only Bluetooth permission (the launcher's
  `MusicSync` grants it while a policy exists; without it the sheet is the old one). A headset tap only connects (or
  makes a connected one active) - the renderer never moves at the tap; on a Sonos room the headset gets `OUTPUT` once it
  is routed inside the 10 s window, a failure leaves the room playing. Never a scan, pairing, `enable()`, a Settings
  intent or the system output switcher; never a blocking wait for the proxy; names and addresses stay in memory - no
  log interpolates them, and `Output`/`PlayerHub.State`, the report, `music_state`, now-playing and the bridge carry
  none (`SourceScanTest`'s `d52` tests).
- **Calls** (26a §6.7): the launcher sends `CALL_HOLD` / `CALL_RELEASE` (session custom actions, granted only to a
  launcher signed like this app, names in `bridge_contract.json`) - since the emulator round of 2026-10-14 `CALL_HOLD`
  means "a call began" (every call's begin while the music app has a session, playing or not; 26c N3); `logic/CallPause.kt`
  decides: a call that begins while nothing plays holds nothing, our own pause (the
  sleep timer runs on), played again only what we paused, never after the kid's own play, a timer that ended or
  while `playAllowed` says no. The audio-mode backstop (MODE_IN_CALL / MODE_RINGTONE, never IN_COMMUNICATION) covers
  a system call on a room and on the web player (`callOn`) without the launcher's listener access or with an older one.
- **Room scope** (26a §5.5): the library's `sonos_rooms` (absent or null = every room; anything else not a list =
  none) limits the sheet, the pick (`ROOM_NOT_ALLOWED`), the controller's belt and a running session (bookmarked,
  stopped, home paused). **A group needs every member allowed** (`groupAllowed`; a `Room`'s members are the whole
  group, uids the cache can't name included, and those are never allowed), so an allowed room grouped with a forbidden
  or unknown one leaves the sheet; while a room plays the groups are re-read every 60 s under a limited scope, and
  whatever the scope while the room's event socket isn't healthy (the group watch, design 53 §2.8; one
  GetZoneGroupState, never discovery). **The exception (design 53 §2.8, the user's Q4: "det barna gjør manuelt bør
  respekteres")**: a room grouped into our running session from outside the app (a kid's hold-Play) is accepted for
  this renderer's life (`SonosController.accepted`: the `joiners` seen while a session of ours plays - a room grouped
  in while nothing plays is judged by the scope at the next start, qa-53b-code 1 -, plus an adopt's baseline, the
  first fresh topology after it; a follow keeps the group's rooms known and accepted as they were) -
  the session's own checks use `sessionAllowed` (the leave, the controller's belt, our row in the sheet with that
  room's "−"), and a follow onto an accepted room plays on there. Everything the app starts or adds itself still needs
  `groupAllowed` (a pick of another row, a "+": only rows the scope allows get one), a parent's new scope still makes
  the session leave over a room the app put there, and the cap still applies to such a joiner. The report's
  `sonos_rooms` (uids `RINCON_…`, names cut as the server cuts them, at most 32, never an ip) feeds the parent's card:
  only the home's players (security review P-L2, `SpeakerCache.reportable`) - each topology tags its players with one
  household key; with a scope, the scoped rooms plus the players of their household seen in the last 30 days (a
  friend's speakers never), without one every player seen in 30 days. In a release build the Sonos log names no uid
  (a 16-bit tag), room, title, URL path or exception text (P-L1, `releaseLogLine`, `logName`, `logFailure`).
- **Grouping in "Spill av på"** (design 53 §2.6-§2.9, round 53-b; the user's approved sketch, ours - vibb groups in the
  Sonos app only): the verbs are soco's over UPnP, never the speaker's WebSocket - join = `SetAVTransportURI
  x-rincon:<our coordinator>` with empty metadata on the joining room (a busy room's own stream stops: the kid asked),
  leave = `BecomeCoordinatorOfStandaloneGroup` on the leaving room. Verbs on other rooms run in `GroupVerbs` (IO, one
  Mutex, 2 s a call, the uid verified), never in our actor or under the switch lock; only a "−" on our own coordinator
  runs in our actor (`SonosTransport.leave`) and starts the follow (the poll burst now, +1.5 s, +3.5 s and the first
  remaining member as the probe's first candidate). "+" (`ROOM_JOIN`) is a play: in `Commands.PLAYS`, then
  `joinOffered` (a live session of ours - fresh, ours, PLAYING or PAUSED -, our uid a row's uid and in its members, not
  stale, the row not ours, every room of it in the scope) and `playAllowed`, read again with the captured coordinator
  and generation before each room (members first, the row's coordinator last; each capped first). "−"
  (`ROOM_LEAVE`) only on a member of a group of two or more (`leaveOffered`): never on a lone room (Q2). Pending
  (the Spinner, until the topology shows it or 8 s) and no contact ("Fikk ikke kontakt med X - er den på?", 30 s) are
  published state; the commands answer at once. No hint lines (Q3). "Spiller noe annet" is each other row's
  `GetTransportInfo`, once per opening and per `groups` event while the sheet is open (`PlayerHub.sheetOpen`), never on a
  timer. **Grouping done outside the app** is followed: a `groups` event refreshes the topology (debounced 300 ms) and
  the service's rooms.state collector hands our group's members to the renderer before `publish()` and the scope's
  look - a joiner (`joiners`: a member not in `knownMembers` - what a start capped, an adopt's baseline taken with
  nothing sent, the rooms a follow brought along, our own joins in flight) seen while a session of ours plays is
  accepted and capped once when louder (never a volume set later from the Sonos app); the label follows ("Stua +
  Kjøkken"); a `MOVED`, or former members now held by another coordinator, is the follow's candidate; and for 8 s
  after a group change a STOPPED of ours near the end probes first (the old coordinator may keep its TrackURI) - the
  advance only when the probe's last try finds no coordinator carrying our stream (`Fields.probeMissed`) or the
  window closed.
- **Discovery never on a timer**: a cache miss, the sheet's "Se etter høyttalere" row or a failed play; opening the
  sheet is one topology refresh. Every URL is built with `HttpUrl.Builder` from an IP literal (no `Uri.parse("http`);
  SOAP is LAN control traffic and has no address guard, but the speaker-playable filter keeps a `publicOnly` entry's
  private-literal audio, a `lan` entry's tailnet-literal audio (100.64/10, fd7a:115c:a1e0::/48: no speaker reaches
  the tailnet), own files, withdrawn items and non-series HLS off the speaker.
- **The instant switch on a room** (`logic/Switch.kt`, `ui/UiModel.kt`): a tap's view is built for the renderer that
  plays (`onRoom`: the speaker-playable queue, a moved start at 0, a step on a playing room from 0) and counted with
  `playerNumber`; the hold lasts while the service still has the tap's command waiting (`PlayerHub.State.waiting`,
  `Commands.MOVES`, at most `SWITCH_WAIT_MAX_MS`) - a room switch or the look at a room delays a play by seconds.
  Design 27's carousel swipe (`SKIP_TO`, `THEN_SKIP_TO`) is in `MOVES` and goes through the `Renderer` (`goTo` ->
  `renderer.seekTo(index, ms)`: on a room `stepTo`, from 0 while playing - `swipeSwitch(..., onRoom)` shows that),
  never ExoPlayer directly; its pages on a room are the speaker-playable queue, numbered by `playerNumberOf` (the
  play order; a Storytel series' reading order - it and `playerNumber` both read one `countedKeys`, design 26b). A
  late episode picture swaps the session player's item in place (`Renderer.replaceItem`); the speaker's DIDL carries
  no art - a listing's `art` is a hash the server serves only to the phone's bearer, so there is no http URL a speaker
  could fetch (design 26a, "Merge of main with design 27").
