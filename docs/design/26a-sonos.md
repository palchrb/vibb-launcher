# 26a - Vibb music phase 1b: Sonos (buildable spec)

Architect draft (2026-10-11), revised after the design QA the same day (§11-§12), the first step of design 26's phase
1b. Inputs: design 26 (the brief and its review), design 20 "Outputs", design 21 (§4.6, QA #12, the user's answer on
own files), `music/CLAUDE.md`, and vibb. **vibb's live line is the `pipewire` branch** (`1fcadac`, 2026-09-21; user
2026-10-11), not GitHub's default `main` (`22bdc38`, 2026-09-03, its direct ancestor). Line numbers below cite
`22bdc38`, where the Sonos code is identical except for the one `pipewire` change noted in §2.1 (`sonosd.py`
`rescan_in_background`, `tests/sonos_hold_scan.py`; `daemon.py`'s `pipewire` diff is Soloist/PipeWire work and only
adds log lines to the Sonos paths; `storytel.py` is unchanged). Sources: `pi/sonosd.py` (the sidecar),
`pi/vibb/renderer.py`, the `_sonos_*` controller in `pi/daemon.py` (lines 350-420, 1083-2126, 2599-2653, 4254-4530,
5340-5364), and `tests/sonos_{contract,renderer,groups,grouped,migrate,migrate_probe,same_tile,hush,hold_scan}.py` and
`storytel_sonos.py`. **This is a port**: every behaviour below names its vibb source, and a deviation is listed in §9
with its reason. `M/` = `music/app/src/main/java/me/vibb/music/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`.
[device check] = needs the Jelly Star and a real speaker.

**In:** Sonos rooms (and groups, by their coordinator) in "Spill av på"; the phone as UPnP controller; content kinds
`url` (RSS enclosures, NRK podcast mp3) and `nrk_program` (NRK series through the NRK Radio Sonos service); the
uid -> ip cache; stall, takeover, grouped-away, lost-session and migration handling; bookmarks with vibb's holds;
adopt after a restart; the launcher's media surfaces unchanged but for one managed-config key (§5.3). **Out:** own
files on Sonos (design 21 decision), Storytel on Sonos (phase 2, `storytel_sonos.py`), `spotify_sharelink` and the
speaker-owned queue (phase 3, `sonos_spotify_queue.py`), group management (the Pi never did it), any server change.

## 0. The shape

The Pi keeps two axes apart (`renderer.py:1-13`): `output` (local|bt: where *our* player writes audio) and `renderer`
(box|sonos: who makes sound). The phone gets the same split:

- **`Renderer`** (`M/playback/Renderer.kt`): what `PlaybackService` talks to. `LocalRenderer` wraps the existing
  ExoPlayer; `SonosRenderer` (`M/sonos/SonosRenderer.kt`) is a Media3 `SimpleBasePlayer` driven by a SOAP poller.
  Each exposes a `Player` for the session: `MediaSession.setPlayer(KidPlayer(renderer.player))` on a switch, so
  `KidPlayer`'s gating and skip routing (`PlaybackService.kt:215-238`) and everything the launcher reads from the
  platform session (`L/music/MusicSessions.kt:118-138`) stay as they are.
- **Output** stays the phone's audio-device choice (`setPreferredAudioDevice`) and is only meaningful while the
  renderer is local. The one picker ("Spill av på") lists both: the phone, connected headsets, Sonos rooms.
- **One process, no sidecar.** vibb's daemon <-> sidecar HTTP split (`sonosd.py:998-1111`, `renderer.py:52-81`) and
  its "sidecar down" state disappear; the three-way distinction that matters stays: speaker unreachable vs nothing
  playing vs playing something else (`renderer.py:9-13`).
- **The phone never serves anything** (design 20): no GENA event subscriptions, no local HTTP. Polling only.
- **Threading (QA M2):** controller state lives on the main thread, inside `SimpleBasePlayer`'s looper. Every SOAP
  sequence runs in one actor (a `Channel` consumed on `Dispatchers.IO`, holding the transport mutex), and its results
  are posted back to main. Player handlers return at once and express intent through the optimistic fields
  (`optTr`, `optPos`, `pending`), exactly as vibb's daemon does against its sidecar; a handler never waits on SOAP.
  The switch, the launcher's pause and the suspension Stop go through the same actor in order, so a bookmark always
  precedes its Stop.

## 1. Behaviour map (vibb -> Kotlin)

| vibb | Kotlin home | Notes |
|---|---|---|
| `soco.discover`, `rescan()`, `rescan_in_background()` (`sonosd.py:142-190`) | `M/sonos/Ssdp.kt` + `Topology.kt` | SSDP finds one speaker; one GetZoneGroupState gives the household (§2.1) |
| `refresh_topology()`, `players()`, `players_payload()` (164-243) | `M/sonos/Topology.kt`, `SpeakerCache.kt` | merge players, replace groups wholesale, coordinator first, invisible skipped |
| `_speaker(uid)` (246-264) | `SpeakerCache.resolve(uid)` + `Soap.uidOf(ip)` | uid mismatch -> `UnknownUid` -> the failed-play path (§4.2) |
| `_hms`, `_hms_to_s`, `MIME`, `_norm_uri`, `_mime_for` (275-313) | `M/sonos/SonosUris.kt` | pure; §2.3 encodings |
| `didl()` (316-349), `DIDL_NS` (269-272) | `M/sonos/Didl.kt` | pure; escape once here, once in the SOAP layer |
| `_nrk_serial`, `nrk_program_uri`, `nrk_desc` (354-389) | `M/sonos/NrkService.kt` | serial from `/status/accounts`, fallback `"14"`, cached per ip |
| `Session` state, `publish`, `state` (392-441) | `M/sonos/SonosSession.kt` (pure) | `seq`, `staleS` from elapsed realtime |
| `play`, `_hush`, `_seek_settled`, `adopt`, `verb` (448-653) | `M/sonos/SonosTransport.kt` | the SOAP sequences, in the actor |
| `_classify`, `poll_loop`, `_cadence`, `_stall_bookkeeping`, migration (658-984) | `SonosSession.kt` (pure) + `SonosPoller.kt` | the machine is pure and tested with canned polls; the poller is a coroutine |
| `SoCo` SOAP calls | `M/sonos/Soap.kt` | §2.2 |
| `renderer.py` `read/write` | `M/sonos/RendererChoice.kt` | `noBackupFilesDir/renderer.json` |
| daemon `_sonos_*` fields (350-420), `_sonos_position`, `_sonos_fresh` (1083-1162) | `SonosRenderer.kt` | §4 |
| `_sonos_bookmark_now` (1177-1268) | `SonosRenderer.bookmarkNow()` + `M/sonos/SonosBookmarks.kt` (pure) | the holds and the regression guard, §4.4 |
| `_sonos_body` (1270-1322) | `M/sonos/PlayBody.kt` (pure) | from a `PlayItem` + entry, §2.5 |
| `_sonos_play_entry`, `sonos_start_target` (1324-1417, 1511-1566) | `SonosRenderer.start()` / `playIndex()` | the speaker-playable filter, §4.2 |
| `sonos_step`, `_sonos_step_worker` (1568-1623) | `SonosRenderer.step()` | coalesced, latest wins |
| seek + `_sonos_seek_worker` (1945-2064) | `SonosRenderer.seek()` | coalesced, 0.7 s spacing, optimistic position |
| `_renderer_to_sonos`, `_renderer_to_box` (1625-1708) | `PlaybackService.switchRenderer()` | §4.6 |
| `_sonos_poller` adopt-on-start (4254-4310) | `PlaybackService.onCreate` -> `SonosRenderer.adopt()` behind `rendererReady` | §4.7 |
| poller tick: settle, duration carry, opt confirm, ends_near (4310-4530) | `SonosRenderer.onPoll()` | §4.3, §4.5 |
| `_sonos_on_term` (5340-5364) | `onDestroy` + the suspension receiver (§6.1-6.2) | live refresh, bookmark, ours-gate, Stop |
| status (2599-2653) | `SimpleBasePlayer.State` + `PlayerHub.State.rendererState` | §3.2-3.3 |
| `sonos_contract.py`'s closed sets; the sidecar JSON | `music/app/src/test/resources/sonos/*.xml` canned SOAP + `SonosFixturesTest` | the boundary worth pinning is now SOAP (§7) |

## 2. The `sonos/` package (Android-free except where said)

### 2.1 Discovery and topology (`Ssdp.kt`, `Topology.kt`, `SpeakerCache.kt`)
- **SSDP** (`rescan()`, `sonosd.py:142-161`; SoCo's `discover`): UDP M-SEARCH to 239.255.255.250:1900, `ST:
  urn:schemas-upnp-org:device:ZonePlayer:1`, `MX: 1`, **sent three times** (SoCo), replies collected for 3 s and accepted
  only with `Sonos` in the `SERVER` header; each reply's `LOCATION` gives an ip (`http://<ip>:1400/xml/
  device_description.xml`). A `WifiManager.MulticastLock` held for the scan only (`CHANGE_WIFI_MULTICAST_STATE`,
  §6.5), released in `finally`. Android wrapper `SsdpScanner` over a pure `Ssdp.parseReply(text): String?`.
- **Topology** (`refresh_topology()`, 164-222): `ZoneGroupTopology.GetZoneGroupState` against cached ips in sorted
  uid order until one answers, **under one budget for the whole refresh** (QA M7: 2 s per ip, 6 s in all), and only
  on Wi-Fi; the `ZoneGroupState` XML (**with or without** the `<ZoneGroupState><ZoneGroups>` wrapper, both forms occur)
  gives every `ZoneGroup Coordinator="<uid>"` with its `ZoneGroupMember UUID Location ZoneName Invisible`. Pure
  `Topology.parse(xml): Household` = players `{uid -> ip (from Location), name}` and groups `{coordinator, members}`.
  Rules as vibb: `Invisible="1"` members skipped (152-153, 205-206); a group whose coordinator is not a visible
  member is skipped; members coordinator-first (217).
- **Rescan = SSDP for one ip, then topology from it.** SoCo's `discover` reads the zone group state under the hood;
  the phone does it in the open: SSDP alone learns neither uids nor groups reliably.
- **Cache** (`_load_cache`/`_save_cache`, 120-139): `noBackupFilesDir/sonos.json`, `{players:{uid:{ip,port,name,
  seen_at}}, groups:[{coordinator,members}], fetched_at, topology_at}`, written tmp + rename. `port` is 1400 and only
  the debug feed may set another (§8 step 1, the emulator). Players **merge** (a scan that misses a speaker never
  deletes the row the kid aimed at, 143-146), groups **replace** (176-179). Garbage -> empty.
- **When discovery runs** (23-26): a cache miss, the picker's "Se etter høyttalere" row, or a failed play (§4.2).
  Never on a timer. Opening the picker runs one topology refresh; nobody answering serves the cache marked `stale`
  **and starts one SSDP round in the background** (`pipewire` `rescan_in_background`/`scanning`, `sonosd.py:164-190,
  1088-1097`; owner 2026-09-05: "the list fills itself"): the sheet opens at once with the old rows, shows "Søker
  etter høyttalere…", a second open while the round runs starts nothing, and the rows refresh when it ends. One
  round in flight at a time.
- **Picker rows** (`players_payload`, 230-243; ui.py:2614-2640 via `sonos_groups.py:120-136`): players sorted by
  name; a group with 2+ members is one row joining **every** member's name coordinator-first ("Kjøkken + Stua + Bad"),
  selecting it targets the coordinator; absorbed members don't repeat; a group naming an unknown uid gives no row.
  Never an ip in the UI.
- `resolve(uid)` (`_speaker`, 246-264): the cached ip; `Soap.uidOf(ip)` (the `device_description` UDN) compared on
  the **play path only**; mismatch -> `UnknownUid`; unreachable -> let the poller classify.
- **Wi-Fi as a gate, never a binding (QA M7; corrected 2026-10-12, §6.5):** SSDP and SOAP run only while a Wi-Fi
  `Network` is up (`ConnectivityManager` callback); without Wi-Fi the refresh is skipped and the sheet shows the cache
  as stale. The sockets are **not** bound to that `Network`: the launcher's always-on VPN allows no bypass, so Android
  refuses the bind (EPERM), and unbound sockets reach the LAN and the multicast group on the default route anyway.

### 2.2 SOAP (`Soap.kt`)
- `call(ip, port, service, action, args: List<Pair<String,String>>): Map<String,String>`; POST
  `http://<ip>:<port><controlPath>`, headers `SOAPACTION: "<serviceType>#<action>"`, `Content-Type: text/xml;
  charset="utf-8"`; body the standard envelope with each arg XML-escaped once (`&`, `<`, `>` only, like Python's
  `escape`; SoCo does the same, which is why a DIDL argument carries `&amp;amp;` on the wire - correct,
  `sonosd.py:319-323`). Reply parsed with `javax.xml.parsers` (DOM, namespace-aware; in the JDK and in Android, so
  JVM tests parse for real - `android.jar`'s `XmlPullParser` is a stub under `isReturnDefaultValues`) into the
  response's child elements; a `<s:Fault>` with
  `<errorCode>` -> `UpnpFault(code)`; transport errors -> `SpeakerUnreachable`. Timeouts: connect 2 s, read 5 s;
  one OkHttp client with a connection pool (vibb's keep-alive session, 101-117), a fresh pool per Wi-Fi network, its
  sockets bound to no network (§6.5), and **no address
  guard**: this is control traffic to the LAN (21b §2.7 is about audio origins; design 26 review #3).
- Services: AVTransport (`/MediaRenderer/AVTransport/Control`, `urn:schemas-upnp-org:service:AVTransport:1`):
  `SetAVTransportURI(InstanceID=0, CurrentURI, CurrentURIMetaData)`, `Play(0, Speed=1)`, `Pause(0)`, `Stop(0)`,
  `Seek(0, Unit=REL_TIME, Target=H:MM:SS)`, `GetPositionInfo(0)` -> `Track, TrackURI, RelTime, TrackDuration,
  TrackMetaData`, `GetTransportInfo(0)` -> `CurrentTransportState`, `RemoveAllTracksFromQueue(0)` (the cosmetic clear
  before a url play, 464-469, errors ignored). RenderingControl (`/MediaRenderer/RenderingControl/Control`):
  `GetVolume/SetVolume(0, Channel=Master, DesiredVolume)`, `GetMute/SetMute(0, Master, DesiredMute)`.
  ZoneGroupTopology (`/ZoneGroupTopology/Control`): `GetZoneGroupState()`. Phase 3 adds `AddURIToQueue`,
  `SetPlayMode`, `Seek(TRACK_NR)` (496-512).
- `SourceScanTest` bans `Uri.parse("http` literals: build every URL with `HttpUrl.Builder` from the ip and port.

### 2.3 DIDL, URIs, NRK (`Didl.kt`, `SonosUris.kt`, `NrkService.kt`)
- `didl(uri, title, artist?, album?, art?, durationS?, upnpClass, protocolInfo?, desc?)` exactly as `sonosd.py:316-349`:
  `<item id="vibb-1" parentID="-1" restricted="true">`, `dc:title` (default "Vibb"), `dc:creator` **and** `upnp:artist`
  when an artist is given, `upnp:album`, `upnp:albumArtURI` (http only - Sonos resolves no `.local` and can't reach the
  phone), `upnp:class`, the `<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">` for service
  items, `<res protocolInfo=… duration="H:MM:SS">uri</res>`. Default protocolInfo `http-get:*:<mime>:*`, mime by
  extension from vibb's table, fallback `audio/mpeg` (288-313).
- `normUri(u)` (293-305): percent-decode **like Python's `unquote`** (a `+` stays a `+`; not `URLDecoder`), then strip
  one of `x-rincon-mp3radio://`, `aac://`, `https://`, `http://`. Ours-detection and the migration probe compare
  **only** through it.
- `hms(sec)` / `hmsToS(s)` (275-285): `H:MM:SS`; `"0:00:00"` -> 0.0 (the duration-carry rule depends on 0 being
  falsy, daemon.py:4398-4406); **null or empty -> 0.0** (vibb's `s or "0:00:00"`), `"NOT_IMPLEMENTED"` or other
  garbage -> null.
- NRK Radio service (354-389): `sid = 277`, `svcType = 277*256+7 = 70919`; the household serial from
  `GET http://<ip>:1400/status/accounts` (`<Account Type="70919" SerialNum=…>`), cached per ip in memory, fallback
  `"14"` with a log line; uri `x-sonos-http:series%3a<quote(series)>%3a1%3a<programId>.unknown?sid=277&flags=0&
  sn=<serial>` where `quote` is Python's (a `/` stays unescaped); class `object.item.audioItem.show`, protocolInfo
  `sonos.com-http:*:audio/mpeg:*`, desc `SA_RINCON70919_X_#Svc70919-0-Token`. **Precondition:** the household's Sonos
  has the NRK Radio service linked. It does: vibb's fallback `sn=14` is "the owner's known-working household value"
  (`sonosd.py:377`). Only NRK *series* need it (HLS, `content.py:8-13, 193`); NRK podcasts and RSS are plain mp3
  urls the speaker fetches itself.

### 2.4 The session machine (`SonosSession.kt`, pure)
State as `Session.__init__` (392-421): `armed, uid, kind, uri, seq, lastOkAt, measuredAt, frozen, lastPos,
retriedAt, aux{volume, groupedAway, coordinator}, auxN, fastAt, oursAt, migrateTries, moved, lastTr, didlChecked`.
Time is an injected `elapsedMs()` (never wall clock: vibb's `monotonic`, 437-441). Transport reads come through a
`SpeakerIo` interface (`positionInfo()`, `transportInfo()`, `group()`, `volume()`), so tests feed canned polls.
- `classify(io): Fields` (658-780): one GetPositionInfo + one GetTransportInfo; `TRANSITIONING` reports the last real
  state, or PLAYING at a fresh start (665-671); `ours = normUri(trackUri) == normUri(session.uri)`; `foreignUri` when
  not ours; `x-rincon:<uid>` -> `groupedAway=true, coordinator=<uid>` on the same poll, a non-empty other uri clears,
  an empty uri decides nothing (690-695); aux (group via topology-of-this-speaker, volume) every `AUX_EVERY=12` polls
  and at session start, kept on failure (704-718); `lostSession = STOPPED && trackUri.isEmpty()` (720-721); the
  one-time DIDL accepted/rejected log (746-754); ours+live re-arms `migrateTries`, clears `moved`, stamps `oursAt`
  (771-779). Fields = the `/state` snapshot's (contract §3): `armed, uid, kind, seq, measuredAt, reachable, transport,
  relS, durS, uri, ours, foreignUri, groupedAway, coordinator, lostSession, volume, retriedAt, streamMoved?, error?`.
- **Freshness, defined once (QA H2):** a snapshot is *fresh* while `elapsedMs - measuredAt <= measuredCadence + 10 s`,
  where `measuredAt` is the last **successful** poll of this session and `measuredCadence` the cadence the poller chose
  after it (`Fields.measuredCadenceMs`); a failed poll never extends it (its backoff cadence would make every failure
  look fresh and flicker in between, qa-26a-step1-code M2). vibb's `SONOS_STALE_S=15` / `stale_s<12` (daemon.py:1083, 4465) were tuned for a daemon re-reading a
  sidecar every 5 s; in one process with a 15 s cruise they would flicker every poll. Every consumer (`playing`, the
  ours-gate, migration follow, the switch-home live refresh) uses this one predicate.
- `cadence(tr, snap, fails)` (832-858): UNREACHABLE `min(5*2^min(fails,6), 300)`; **5 s whatever the state while the
  speaker reads TRANSITIONING or within 30 s of the last verb/arm** (§9, qa-26a-step1-code M1; vibb fast-polls only
  PLAYING there); else PAUSED 15 s; STOPPED 5 s only while a migration transition pends (empty uri, no hint, tries
  left, within 90 s of `oursAt`), else 60 s; PLAYING 5 s when `dur-rel <= 45`, else cruise 15 s. The same with the screen off (user,
  2026-10-11: the position is saved as finely as ever; only the screen needn't follow, §6.3).
- `stall(fields)` (860-885): PLAYING + ours + `relS` identical for `STALL_POLLS=3` polls and no retry this session ->
  `retriedAt`, then Stop, Play, `seekSettled(rel)` when rel >= 5; one retry per session.
- `maybeProbeMigration(fields)` + `probeMigration()` (889-984): trigger only STOPPED with an **empty** TrackURI, no
  hint, tries left, within `MIGRATE_WINDOW_S=90` of `oursAt`; each try refreshes the topology and probes up to 6 other
  coordinators' own transports; match = PLAYING/PAUSED and `normUri` equality (url/nrk); hit -> `moved = {uid, name,
  uri = session.uri}` (never the snapshot's empty one), tries = 0, published on every snapshot until an ours+live
  sighting, a play or an adopt clears it. `MIGRATE_TRIES=3`, each attempt logged.
- `disarm` (814-828): a streak of neither PLAYING nor PAUSED for `DISARM_AFTER_S=600` -> `armed=false`, republished
  at once; only a play or an adopt arms again.
- `publish()` increments `seq`; `measuredAt` is the poll's elapsed time; a failed poll publishes `reachable=false,
  transport=UNREACHABLE, error` and keeps the last measured values **of this session**: a play or an adopt starts
  from nothing measured, no down streak, no failure count and no last real state (qa-26a-step1-code H1, H2, M1).

### 2.5 Transport sequences (`SonosTransport.kt`) and the play body (`PlayBody.kt`)
All in the actor (the session lock, 393-394), never on the main thread.
- `play(body)` (448-536): `uid` and `kind` set **before** the SOAP (452), `uri` only **after** success (516);
  migration state cleared; `hush(startS)`; `url`: `RemoveAllTracksFromQueue` (ignored errors), `SetAVTransportURI(uri,
  didl)`, `Play`; `nrk_program`: the service uri and DIDL (§2.3), `SetAVTransportURI`, `Play`; then arm, reset
  stall/aux, `fastAt = now`, `seekSettled(startS)` when `startS >= 5`; `finally unhush()`; wake the poller. Result
  `{sought, uri}` where `uri` is **the built one** (for `nrk_program` the `x-sonos-http` uri, not the radio.nrk.no
  target): that is what `renderer.json` stores and what adopt compares against (QA ambiguity 1).
- `hush` (538-559): when `startS >= 5`, read mute, set mute true, and restore the **old** value in `finally` (a
  deliberately muted speaker stays muted); below 5 s never touches mute; all best-effort.
- `seekSettled(startS)` (561-589): poll GetTransportInfo every 0.3 s up to 8 s until PLAYING, then up to 3 Seeks,
  retrying only on faults 701/710/711 with 0.4 s between; false = degrade ("playing from the top"), never an error.
- `adopt(uid, kind, uri)` (591-604): bookkeeping only, zero transport commands.
- `verb` (606-653): `pause`, `resume`, `stop` (disarms), `seek(s)`, `volume(v)` (clamped 0..100, written straight into
  the aux so the snapshot never lags our own set); each stamps `fastAt` and wakes the poller for an immediate re-poll
  (the cached snapshot still says the old state, 611-613). vibb's `if_uid` 409 becomes an in-process check: a verb for
  a uid that is no longer the session's is dropped.
- `PlayBody.build(entry, item, uid, startS, album = entry.name)` (`_sonos_body`, 1270-1322): art = `item.art` only
  when it starts with `http` (the phone's cover file is unreachable to a speaker), else null; an NRK series entry
  (`entry.nrkSerie`, the series slug from `entry.target` with vibb's regex `https?://radio\.nrk\.no/serie/([a-z0-9_-]+)`,
  daemon.py:1275) -> `nrk_program{uri = entry.target, series, programId = item.key}`: **the item key is the program id**
  for every NRK series listing (`server/src/music_sources.rs:87-89`, vibb `content.py:160-165`); the RSS fallback with
  `sha1(guid)` keys exists only for NRK podkast (`music_sweep.rs:1412-1449`) and keeps psapi keys where it has them
  (QA M3: no server change). Else `url{uri = item.url}`; `durationS` from the listing when the item has one (the
  speaker reports 0:00 for an extension-less url until buffered, `daemon.py:1306-1313`). Storytel's branch arrives in
  phase 2.

## 3. The renderer axis in `playback/`

### 3.1 `Renderer`
```kotlin
interface Renderer {
    val player: Player                     // what the session wraps (KidPlayer(renderer.player))
    val kind: RendererKind                 // LOCAL, SONOS
    fun load(plan: PlayPlan, items: List<MediaItem>, startIndex: Int, startMs: Long, playNow: Boolean)
    fun reorder(order: List<Int>)          // shuffle: the local one edits Exo's list, Sonos its own queue
    fun positionMs(): Long; fun durationMs(): Long?; fun currentIndex(): Int; fun itemCount(): Int
    fun play(); fun pause(); fun seekTo(index: Int, ms: Long); fun clear()
    fun saveNow(force: Boolean)            // the ONLY persistence path: local = savePosition(), Sonos = bookmarkNow()
    fun fadeOutAndPause()                  // the sleep timer's end: local 10 s fade, Sonos §5.2
    val events: Flow<RendererEvent>        // ItemChanged(index, auto), Ended, Error(code), StateChanged
}
```
`LocalRenderer(exo)` is a straight extraction of today's `exo.` reads and writes (the inventory's list:
`PlaybackService.kt:168-882`): `currentPosition/duration/currentMediaItemIndex/mediaItemCount`, `setMediaItems/
prepare/seekTo/play/pause/clearMediaItems/add+removeMediaItems`, `volume`, `setAudioAttributes/setWakeMode/
setPreferredAudioDevice`, and the `Player.Listener` callbacks (619-733) re-emitted as `RendererEvent`s. **Every
place that persisted `exo.currentPosition` (`savePosition`, 803-823, called from `saveTick`, `start`, `goTo`,
`playItem`, `toggleShuffle`, `onPlayWhenReadyChanged`, `onTaskRemoved`, `onDestroy`) calls `renderer.saveNow()`
instead; `positionMs()` is for display only** (QA H3). Behaviour unchanged; **step 2 is this refactor alone**, with
all existing tests green.

### 3.2 `SonosRenderer : SimpleBasePlayer`
- `getState()` from the last snapshot + the queue: playlist = the plan's speaker-playable items as `MediaItemData`
  (uid = `"<entryId>/<itemKey>"`, the same `MediaMetadata` the local path builds: title, artist = album = entry name,
  `artworkUri` the <= 300 px file, duration from the listing); `contentPositionMs` as a `PositionSupplier` anchored at
  `measuredAt` (so the launcher's `positionNow` extrapolation, `L/music/MusicMediaPlan.kt:57-65`, is right);
  `deviceInfo = DeviceInfo(PLAYBACK_TYPE_REMOTE, 0, 100)` and `deviceVolume` from the aux (§5.3).
- **State mapping (QA H2), the table:**

  | Situation | playbackState | playWhenReady |
  |---|---|---|
  | armed, ours, PLAYING (or `optTr` PLAYING) | READY | true |
  | armed, ours, PAUSED (or `optTr` PAUSED) | READY | false |
  | TRANSITIONING, a pending verb's settle window, the first 3 failed polls | BUFFERING | last value held |
  | grouped away / taken over / lost session (fresh) | READY, **no position, no duration** (someone else's stream) | false |
  | UNREACHABLE past 3 polls | READY, last position held | false |
  | disarmed, or the queue ended | IDLE / ENDED | false |

  Media3's `MediaSessionService` keeps the `mediaPlayback` FGS only while `playWhenReady && (READY || BUFFERING)` and
  Android 12+ refuses to re-enter the foreground from the background, so one failed poll must never read as IDLE.
  While armed, `onUpdateNotification(session, startInForegroundRequired = true)` keeps the FGS for a paused Sonos
  session until disarm (600 s), the way a paused Pi session stays armed. The implementer checks Media3 1.7.1's
  paused-FGS timeout against this.
- `availableCommands` (QA M1): `PLAY_PAUSE`, `STOP`, `SEEK_TO_NEXT/PREVIOUS(_MEDIA_ITEM)`, `SEEK_IN_CURRENT_MEDIA_ITEM`,
  `SEEK_TO_MEDIA_ITEM`, `GET_CURRENT_MEDIA_ITEM`, `GET_TIMELINE`, `GET_METADATA`, `GET_DEVICE_VOLUME`,
  `SET_DEVICE_VOLUME_WITH_FLAGS`, `ADJUST_DEVICE_VOLUME_WITH_FLAGS`. Without the three GET commands the platform
  metadata is empty and the launcher's card has no title (`MusicSessions.kt:118-138`).
- Handlers return a completed future at once and queue the SOAP in the actor: `handleSetPlayWhenReady` -> §4.5
  playpause; `handleSeek` -> §4.5 seek, or step when the index changes (`KidPlayer` routes the four skips to
  `seekToNext/Previous`); `handleStop` -> bookmark then Stop; `handleSetDeviceVolume` / `handleIncrease/
  DecreaseDeviceVolume` -> §5.3; `handleRelease` no-op. `invalidateState()` after every poll result lands on main.
- Not applicable on Sonos, so no-ops: `setAudioAttributes`, `setWakeMode`, `setPreferredAudioDevice`. The sleep fade
  is §5.2's ramp.

### 3.3 `PlayerHub` and the UI model
- `Output` widens: `data class Output(val key: String, val kind: OutputKind, val name: String?)` with keys `"phone"`,
  `"dev:<AudioDeviceInfo.id>"`, `"sonos:<uid>"`; `OutputKind` gains `SONOS`; `Commands.ARG_OUTPUT` becomes a String
  (app-internal, not in the AIDL). `Commands.OUTPUT` **to a Sonos key is a play** (it starts the speaker), so it joins
  `PLAYS` and is gated by `playAllowed`; to the phone or a headset it stays ungated (a pause-side move). `PlayerHub.State`
  gains `rendererState: RendererState?` (`null`, `GROUPED_AWAY`, `TAKEN_OVER`, `LOST_SESSION`, `UNREACHABLE`; precedence
  as `daemon.py:2627-2632`; the name is this enum's alone - the persisted renderer.json record is `RendererChoice`,
  §4.1, and a cached room is `sonos.Zone`, apart from Media3's `Player`), `rooms: List<Room>`, `roomsStale: Boolean`,
  `scanning: Boolean` (from `RoomDirectory.state`, which also publishes the rows when a background round ends); commands
  `ROOMS_REFRESH` (the sheet opening) and `ROOMS_RESCAN` (the row).
- `publish()` reads through `renderer`: `playing` on Sonos = fresh and PLAYING and reachable and ours (2605-2611);
  **position and duration are shown only while ours** (2615-2621, `sonos_grouped.py` check 2): a fresh snapshot of a
  stream that isn't ours (grouped away, taken over, lost) shows position 0 with no duration (`hidesPosition`; vibb's
  `position: None` - a member's RelTime is the coordinator's; vibb still showed the snapshot's `dur_s`, the parent's,
  which the phone hides too); a stale snapshot shows "not playing" but **keeps** the last ours position and duration
  (never zeroed, 2612-2621). (Corrected 2026-10-12: §3.2's table said "at the last ours position" for the not-ours
  rows and steps 3a-4 shipped that; vibb and this paragraph hide it.)
- `setNowPlaying.playing` over the bridge (`reportNowPlaying`, 845-868) = the same `playing` predicate: grouped away,
  taken over or stale report `false`, which is what screen time (listening = playing and in front) and the deferred
  install should see. `PlayerHub.position` and `PlayerHub.pause` lambdas point at the current renderer. `saveTick`
  keeps its triggers but calls `saveNow`; `GuardsTest`'s pinned strings (`GuardsTest.kt:26-55`) are updated with the
  refactor, keeping their intent (every play path gated, `saveTick` saves and reports).

## 4. Controller logic (`SonosRenderer`, from `daemon.py`)

### 4.1 Persisted renderer state (`RendererChoice.kt`, `renderer.py:23-49`)
`noBackupFilesDir/renderer.json`: `{renderer: "local"|"sonos", uid, name, entryId, itemKey, uri}` with `uri` the
**built** one (§2.5); anything not sonos-with-uid reads as local. Written before the first SOAP of a switch (order as
1625-1667), cleared on the way home.

### 4.2 Starting an entry on the speaker (`sonos_start_target`, 1511-1566; `_sonos_play_entry`, 1324-1417)
- The plan is the same `coverTapPlan`/`pickPlan` the local path uses (`M/logic/Queue.kt`), then the **speaker-playable
  filter** (1535-1538): keep items with an http(s) `url` whose host is not a private literal when the entry is
  `publicOnly` (QA M7: the speaker fetches by itself, so the address guard moves here) and, for a `lan` entry, whose
  host is not a tailnet literal (100.64.0.0/10, fd7a:115c:a1e0::/48: the phone and the server reach the tailnet, a
  speaker on the LAN never does - "tailnet address"; correction 2026-10-12, before it the item went out and failed), excluding `hls` items unless the entry is an NRK series (then `nrk_program`);
  own files (`fileId`), withdrawn items (`url == null`) drop out with a log line. Nothing left ->
  `Message.CANT_PLAY_ON_SONOS` and the renderer stays where it was (`nothing-remote-playable`).
- Index and position: an explicit item -> its index and its saved position; else the bookmark (`rotateToBookmark`, as
  today). `startedPlaying` (Nytt removal) as on the phone.
- `playIndex(idx, startS, deliberate)`: build the body **once** (1367-1371), `transport.play`; on success `index = idx`,
  `pending = (itemKey, now)`, `optTr = (PLAYING, now)`, seed the snapshot `{PLAYING, relS = startS, durS = item
  duration with durFromListing = true, uri = body.uri, ours = true, reachable = true, seq = -1, measuredAt = now}`;
  when `startS >= 5` and not `sought` -> `bmHold = (itemKey, startS, now)` (1395-1405); a deliberate restart (previous,
  a tap on an item) stamps `seekAt` so the regression guard doesn't keep the old bookmark for 90 s (QA L4).
- **A failed play** (a fault, unreachable, `UnknownUid`): `Message.CANT_PLAY_ON_SONOS`, the renderer reverts to local
  paused, and one topology refresh runs (with the background SSDP round when nobody answers, §2.1) so the sheet is
  right the next time it opens. vibb's docstring promises a rescan its code never does (`sonosd.py:246-264`); the
  phone does it.

### 4.3 The poll tick (`_sonos_poller`, 4310-4530)
In order, whole tick guarded (4523-4530): take the snapshot; **duration carry** - `durS` falsy -> the listing's duration
with `durFromListing`, else carry the previous value only for the same uri (4398-4406); **optimistic transport** `optTr`
confirmed or expired after 8 s, else it overrides the snapshot's transport (4408-4420); **optimistic position** `optPos`
cleared when `|rel - target| <= 6`, after 12 s, or when the uri changed (4421-4432); **pending** cleared when the
speaker's uri is ours or after 8 s; **migration follow** (§4.8) runs before the ours-gate; then the gate `fresh && ours`
(4465-4468); **bookmark** (§4.4); **ends_near**: count PLAYING/PAUSED polls with `rel > dur - 20`; url/nrk kind +
STOPPED + ends_near -> `markDone` for the finished item and the next item **at 0** (vibb 4509; the phone's
`startPos` resume applies only to a tap), or at the queue's end `transport.stop` and the local `queueEnded()` path
(`markDone`, clear partial positions, now-playing cleared; `PlaybackService.kt:758-772`) (4499-4520). Position for the
UI and the session (`_sonos_position`, 1137-1162): `optPos` wins while <= 12 s old and same uri; null unless ours and
PLAYING/PAUSED; PLAYING -> `rel + (elapsedMs - measuredAt)/1000` (**one age term**: vibb's `age + stale_s` summed two
clocks across its process split), clamped to `dur`.

### 4.4 Bookmarks (`_sonos_bookmark_now`, 1177-1268; pure in `SonosBookmarks.kt`)
`saveNow()` on Sonos **is** `bookmarkNow()`; no other path writes a position (QA H3). Needs an index, `ours`, a
**measured** `relS` (PLAYING adds the one age term), not STOPPED. Written on **every poll** (user, 2026-10-11: as
fine-grained as possible; vibb's 25 s throttle was SD hygiene), so every 15 s at cruise and every 5 s near the end or
after a verb, and forced before any Stop or switch. Rules, in order: the refused-seek **hold** `(itemKey, minPos,
at)` keeps a lower position from being written, bounded by `BM_HOLD_MAX_S = 120` of real listening (1216-1231); the
**regression guard**: `rel < BM_RESTART_FLOOR_S (90)` while the saved position is `> BM_REGRESS_MIN_S (300)` and no
user seek or deliberate restart within `BM_USER_GRACE_S (180)` -> keep the bookmark ("the speaker restarted the track",
1232-1258); then `Bookmarks.save(itemKey, rel, duration = speaker-measured only, null when durFromListing)`
(1259-1268: a listing length short by half a minute must never mark a ten-hour book finished). **Always before any Stop
or renderer change**: after a Stop the transport reads 0:00 (1178-1180). An `optPos` or an extrapolated position never
reaches Room (test, §7). Deviation (§9): vibb bookmarks only the `url` kind (1210); here `nrk_program` is bookmarked
too, with the same rules.

### 4.5 Verbs (`_sonos_command`, 2066-2126; `pause`/`unpause`/`stop`, 2128-2231)
- **playpause**: not (ours and PLAYING/PAUSED) -> `start(entry)` again (a STOPPED speaker resumes from the bookmark, not
  from 0); PLAYING -> bookmark, `pause`, optimistic PAUSED with the extrapolated position frozen; PAUSED -> `resume`;
  both set `optTr` and wake the poller. `pause()`/`play()` are ours-gated: not ours -> nothing is sent (2140, 2184).
- **Same tile** (`play()`, 792-862; `sonos_same_tile.py`): tapping the loaded entry while fresh, ours, index known and
  nothing settling (`pending`/`optTr` < 8 s): PLAYING -> no-op; PAUSED -> one `resume`. Anything else (stale, grouped
  away, an explicit item) -> a full start **to our own uid**, never the coordinator (190-199).
- **next/previous** (`sonos_step`, 1568-1623): `M/logic/Transport.kt`'s rules decide (previous restarts the current
  item past 5 s in a long-form entry, else goes back; the wrap rules), as on the phone; vibb's own previous rule never
  fired for the url kind because `pending` was never cleared (§9). `(base + delta) mod n` where base is the coalesced
  want; optimistic index at once; one worker jumps to the **latest** want with `playIndex(want, 0, deliberate = true)`;
  the worker catches everything (never leaves `stepping` true).
- **seek** (1945-2002, 2046-2064): target = clamp(abs or base + delta, 0, dur - 5); no duration -> refused (`live`);
  coalesced worker posts `seek` latest-wins with 0.7 s spacing; `optPos = (target, now, uri)`, `bmHold` cleared,
  `seekAt` stamped. The guess is never written into the snapshot or Room.
- **stop** (`onDestroy`, suspension, §6): live refresh, bookmark, ours-gate, `stop`.
- **volume** (§5.3).

### 4.6 Switching (`_renderer_to_sonos`, 1625-1667; `_renderer_to_box`, 1669-1708)
- **Phone -> room**: `LocalRenderer.saveNow(force)` (the mpv bookmark flush), pause Exo, write `renderer.json`,
  `MediaSession.setPlayer(KidPlayer(sonos.player))`, then `start(current entry)` at the saved position; **with no
  active queue** (the bar shows the stored now-playing row paused) the stored row is restored first, as `RESUME`
  does (QA ambiguity 4). An error -> §4.2's failed-play path.
- **Room -> room** (1636-1644): one live classify, bookmark, `stop` to the **old** uid, then the start above.
- **Room -> phone** (1669-1708): live classify (`/state?live=1`: one classify with a 1.5 s budget, falling back to the
  snapshot, 1021-1039), bookmark, `stop`, write local, clear the Sonos state, `setPlayer(KidPlayer(exo))`, and
  **continue on the phone from that bookmark only if the speaker was PLAYING**; a paused speaker comes home paused
  (vibb resumes either way, 1695-1708: a deviation, §9, so a phone in a pocket never starts sounding). The switch is
  the kid's own tap, so playing on is not "starting by itself" (`music/CLAUDE.md`); the phone's volume cap applies again.
- A headset arriving or leaving (`deviceCallback`, 586-605) touches the output choice only, never the renderer
  (`sonos_renderer.py` check 5).

### 4.7 Adopt after a restart, and the startup gate (`_sonos_poller` start, 4254-4310; QA H5)
`PlaybackService.onCreate` builds the session on `KidPlayer(exo)` as today, then completes a `rendererReady`
deferred: at once when `renderer.json` says local; else after one classify of the stored uid (budget 1.5 s, in the
actor). Fresh-equivalent and PLAYING/PAUSED and `normUri` equality with the stored built uri -> `adopt` (no transport
commands), rebuild the queue from the stored entry with the speaker-playable filter, index by uri equality, duration
from the listing with `durFromListing`, `setPlayer(sonos)`. Otherwise -> local, nothing starts, the now-playing bar
comes back paused as today (`NowPlayingRow`). **Every custom command and `onPlaybackResumption` awaits
`rendererReady`** (vibb's double-playback trap, `daemon.py:794-800`, `sonos_renderer.py` check 2: Home's RESUME after
a crash must not start Exo under a playing speaker). While Sonos is the renderer, `onPlaybackResumption` (a Bluetooth
play button) becomes `start()` on the speaker, gated by `playAllowed`, and returns no local items. The service stays
alive while the speaker plays because the renderer reports playing (§3.2); adopt is for the process dying anyway.

### 4.8 Grouped away, taken over, lost, moved (`sonos_grouped.py`, `sonos_migrate.py`)
- `rendererState` for the UI and `playing = false`: `GROUPED_AWAY` (x-rincon: uri or the aux topology), `TAKEN_OVER`
  (not ours, non-empty uri), `LOST_SESSION` (STOPPED, empty uri), `UNREACHABLE` (not fresh) - in that precedence
  (2627-2632). The player screen shows one line under the title (§5.2); nothing acts on it but the kid.
- **Migration follow** (`_sonos_stream_moved`, 1090-1135): a `streamMoved` hint with `uid != current`, snapshot uid ==
  renderer uid, fresh -> `adopt(hint.uid, kind, hint.uri)` **first**, then `renderer.json` with the new uid and name;
  later verbs go to the new uid. A failed adopt changes nothing and is retried next tick.

## 5. UI, strings, the launcher

### 5.1 "Spill av på" (`OutputSheet`, `M/ui/PlayerScreen.kt:197-230`)
Rows: "Telefonen" + connected headsets as today, then the Sonos rooms from `PlayerHub.State.rooms` (grouped rows as
§2.1, icon a speaker glyph from `scripts/material-symbols.sh`, subtitle "Sonos" / "Sonos · svarer ikke" when
`roomsStale`), then "Se etter høyttalere" (`ROOMS_RESCAN`; "Søker etter høyttalere…" with a spinner while
`scanning`). Opening the sheet sends `ROOMS_REFRESH`. The player's bottom bar shows the room name highlighted like a
headset. Strings in `values` **and** `values-nb` (`SourceScanTest`): the rows, the state lines, the messages; `FitTest`
for the bar with a long room name and a three-member group label.

### 5.2 The player on Sonos
Everything as today, plus: `rendererState` as one quiet line under the entry name ("Spilles i en annen gruppe" /
"Playing in another group", "Noen andre bruker høyttaleren" / "Someone else is using the speaker", "Høyttaleren
stoppet" / "The speaker stopped", "Får ikke kontakt med høyttaleren" / "Can't reach the speaker"); a stale snapshot
keeps the last position and shows the play button (a tap starts over, §4.5). **Sleep timer** (user, 2026-10-11: a
fade, as long as it is short): `fadeOutAndPause()` = three `SetVolume` steps (2/3, 1/3, 0 of the volume we last read)
1 s apart, `pause`, then the volume restored, so the next play isn't silent; the local 10 s fade would be ten SOAP
calls. An external pause seen by the poller cancels the timer, as a manual pause does today (667). The ramp is one
actor job; if the process dies mid-ramp the speaker may be left quiet, and the next `play` restores the volume to the
last value we read (stored with the renderer state).

### 5.3 Volume (user 2026-10-11: the keys control the speaker, with the parent's cap)
`DeviceInfo(PLAYBACK_TYPE_REMOTE, 0, 100)` makes the phone's volume keys reach the player as `handleIncrease/
DecreaseDeviceVolume` (one press = `SetVolume` of the last value **we set or read**, ±3; vibb's `_sonos_vol_opt`
existed because bursts computed from a stale base did nothing, daemon.py:1001-1015) and the slider as
`handleSetDeviceVolume`. Every set is clamped to the cap **in force**. The cap in force (`capPctInForce`:
`volume_cap_pct`, managed, the override and the pause; `L/music/VolumeCap.kt:89`) **is not in the music app's managed
config today** (`RestrictionKeys`, `M/bridge/BridgeConfig.kt:33-41`): the launcher publishes it as a new restriction
`volume_cap_pct` (0 = none), re-pushed at every `AppEnforcer.apply` edge, read live like `mobile_data`. That is a
launcher change and a contract change: both `bridge_contract.json` copies, `MusicPlanTest`/`BridgeTest`, one commit
(QA H6). **At a session's start** (a room picked, a start, a restart - `PlayWhy.START`; corrected 2026-10-12 after the
main session's review) the speaker's volume is read once (`GetVolume`) and, when above the cap in force, set to the cap
**before Play, inside the hush** (`SonosTransport.capVolume`): our stream never starts louder than the parent allows,
whatever the last listener left. **A group row caps every room in it** (qa-26a-corrections M2): RenderingControl is
one speaker's volume, so each member of the row (`roomMembers`, the cache's ips) gets its own `GetVolume`/`SetVolume`
in the same hush, best effort within 1.5 s each - an unknown or silent member never holds the play up. No cap ->
nothing is read. **During the session** a louder volume set from the Sonos app or the speaker's buttons is **not**
pulled down (vibb never fights external changes, 995-998): a step, an advance, a poll and the adopt at start (zero
transport commands) never touch it; only our own sets are clamped, the sleep ramp's leftover volume too. **A cap that
goes down during the session** (the override or the restrictions pause ending, a parent's change; the
`ACTION_APPLICATION_RESTRICTIONS_CHANGED` broadcast -> `PlayerHub.capChanged` -> `SonosController.capChanged`) pulls
**our own** last set (`volOpt`) down to it once - 70 from the keys under a lifted cap becomes 40 - as the phone's own
`STREAM_MUSIC` follows the cap at once (`L/music/VolumeCap.kt`); a volume we didn't set (`volOpt` null) or one at or
below the cap is left alone (qa-26a-corrections M1). `volOpt` is always what we last set on the speaker: the start's
cap, a ramp's leftover put back after it, or a key press sent while the start was in flight, whichever went out last
(L1). Best effort: a speaker that won't answer `GetVolume` still plays (logged). The keys and the slider still reach
the coordinator only (vibb's model, a group's own volume is the Sonos app's).

### 5.4 No server change for playback
NRK series items carry their program id as the item key (§2.5), so the listing contract stays. The server sees no
now-playing (design 20 decision 5).

### 5.5 Room scoping (user, 2026-10-11: every room allowed by default; the parent may restrict to specific speakers)
Step 3c, after 3b; the one change that touches the server, launcher and music app, in one commit:
- **The phone reports the rooms it knows**: `music_state` gains `sonos_rooms: [{uid, name}]` (<= 32 rows, names <= 40
  chars, uids `RINCON_[A-Z0-9]{12,20}`), from the speaker cache, refreshed with every report. Server `MusicState` +
  `sanitize_music_state`, `server/testdata/music_state.json`, the launcher's key list (`MusicPlan.kt`), `ReportTest`/
  `BridgeTest` (root CLAUDE.md: the fixture is every field).
- **The parent picks**: the device page's Music card lists the reported rooms with a switch "Alle rom" (default on)
  and, when off, one checkbox per room; stored as `device_policy.music_sonos_rooms_json` (NULL = all), migration
  numbered after the final rebase, `security_events` `music_sonos_rooms_changed`.
- **The phone obeys**: the per-phone library JSON (`GET /api/devices/music/library`, already per phone, hashed into
  `library_version`) carries `sonos_rooms: null | ["RINCON_…", …]`; `Library.sonosRooms` lenient (absent = all). The
  sheet lists only allowed rooms (**a group row only if every member is allowed** - corrected after
  qa-26a-step3c-code H1, the main session's decision: a group with a forbidden member sounds there); the pick refuses
  a room whose group isn't wholly allowed (`Message.ROOM_NOT_ALLOWED`, "Rommet er ikke lenger tillatt"); a running
  session on a room that becomes disallowed - the scope changed at a sync, a forbidden room grouped into ours, the
  stream followed to a forbidden coordinator - is stopped (bookmark first) and comes home paused. While a room plays
  under a limited scope the household's groups are read once a minute (one GetZoneGroupState, never a discovery
  round), so a regroup is noticed. `music_library.json` snapshot and the music app's copy change together
  (`ContractFilesTest`).
- An allowed room that the household has grouped with a forbidden one leaves the sheet until it is ungrouped (from
  the Sonos app): playing it alone would break the household's group, playing the group would sound in the forbidden
  room.
- **A member the phone can't name counts as forbidden** (correction 2026-10-12): a group naming a uid with no player
  row in the cache gives no group row (§2.1), and its known rooms show alone - but each row's members are the whole
  group as the topology has it, unknown uids included, so under a limited scope such a room is neither listed nor
  playable (the scope lists only uids the phone reported). With every room allowed nothing changes.
- **Every room means every room on any Wi-Fi**: with the default, at a friend's house the phone lists and can play on
  that household's Sonos speakers too (the cache merges, the sheet lists what answers). The PWA's Sonos card says so
  next to "All rooms"; a parent who doesn't want it restricts the phone to the home's rooms.
- The tap-vs-sync race (qa-26a-step3c-code L8): a pick checked against the old library just before the sync applies
  a narrower one starts on the speaker, and the sync's leave stops it moments later - a second or two of sound, then
  a bookmarked Stop and the phone, paused. Bounded by the switch lock (the leave waits for the pick, never the other
  way round); not worth a lock around the library write.
- A room never seen by the phone can't be restricted: the PWA says so ("Telefonen har ikke sett noen høyttalere ennå";
  in the PWA's English: "The phone hasn't seen any speakers yet").

## 6. Phone-specific rules

1. **Time rules and suspension (QA H4, M5).** The launcher pauses the session, then suspends the app
   (`L/music/MusicSessions.kt:179-190`, design 21 step 4). On Sonos a pause from the session stays a `Pause` (vibb).
   `MY_PACKAGE_SUSPENDED` (`SuspendedReceiver`, `M/MusicApp.kt:64-68`) today posts `PlayerHub.pause`, which is null
   without the service. It becomes: `goAsync()`; if `renderer.json` says sonos, one live classify of the stored uid
   (1.5 s budget, standalone when the service is gone, through the actor when it is up; the standalone look is
   **retried** while it got no answer - at most three looks, the later ones with everything left until 6.5 s into the
   receiver's 9 s, so a slow speaker still gets its Stop - and reads **no group and no volume**, whose time is the
   Stop's; correction 2026-10-12. The Stop and the park then get what is left until 8.5 s, at least 0.5 s, as one
   budget for both SOAPs (qa-26a-corrections L2), so the receiver finishes inside its 9 s even after a late answer; a
   Stop that fails or runs out is NO_ANSWER - the room stays recorded and the next look parks it); ours (normUri
   against the stored uri) and PLAYING/PAUSED -> bookmark, `stop`, then `SetAVTransportURI("x-rincon-queue:<uid>#0",
   "")` so the speaker's own Play button plays the (empty) queue, not the episode from 0:00 (after a Stop the speaker
   keeps its AVTransportURI, `sonosd.py:894-896`); disarm; `renderer.json` -> local. `playAllowed(suspended)` gates
   every Sonos play path as it gates the local ones (`GuardsTest`). **Limit, documented:** the household's own Sonos
   apps and grouping from them are out of reach; a kid with the parent's Sonos app is a household matter, not the
   phone's.
2. **Process death while Sonos plays** leaves the speaker playing until the next start adopts (§4.7) or the next
   suspension edge stops it (1 above, which works without the service). `onDestroy` (`onTaskRemoved` too) does the
   full stop path: live refresh, bookmark, ours-gate, `stop` (vibb `_sonos_on_term`, 5340-5364). A crash does not.
3. **The CPU and Doze (QA H1).** An FGS keeps network access in Doze but not the CPU awake; today's only wake lock is
   ExoPlayer's (`setWakeMode(C.WAKE_MODE_NETWORK)`, 156, 428-431), and a `SimpleBasePlayer` has none. So the Sonos
   renderer holds a `PARTIAL_WAKE_LOCK` **while armed and PLAYING, and inside the verb and tail fast windows**,
   released on PAUSED, disarm and the switch home (`SourceScanTest` bans only screen wake locks,
   `SourceScanTest.kt:81-87`). The partial wake lock - honoured in Doze for a foreground service's process - and the
   FGS's own network access are what keep the polls running with the screen off. A `WifiManager.WifiLock`
   (`WIFI_MODE_FULL_LOW_LATENCY`) is held beside it, harmless but doing little: low-latency mode is effective only in
   the foreground with the screen on, and `WIFI_MODE_FULL_HIGH_PERF` is a no-op since API 34 (qa-26a-step3b-code L5). The cadence is vibb's whatever the screen
   does, and a bookmark lands on every poll (§4.4); what idles with the screen off is the UI (`livePosition` polls only
   while a screen is shown, `MusicRoot.kt:166-182`). [device check] 1 h screen off: polls every 15 s in logcat, the
   bookmark within 15 s of the truth, `vibb:sonos` held in `dumpsys power`, battery per hour (whether Wi-Fi stays up
   with the screen off is what this check finds out; the Wi-Fi lock is not counted on). If Doze still defers them, the launcher grants the music app
   the battery-optimisation exemption design 19b gives itself.
4. **Network.** Only on Wi-Fi, never bound to it (§2.1, 5 below); Wi-Fi gone -> the speaker reads UNREACHABLE (backoff, disarm after
   600 s); the kid's phone away from home keeps the cache (the "cabin case", `sonosd.py:1050-1053`): the sheet lists
   home rooms marked stale and starts no scan without Wi-Fi.
5. **Permissions and the VPN:** `CHANGE_WIFI_MULTICAST_STATE` (the `MulticastLock`), `ACCESS_WIFI_STATE`, `WAKE_LOCK`
   (present). The launcher's `KidVpnService` (`L/server/KidVpnService.kt`) is the device's always-on VPN
   (`setAlwaysOnVpnPackage(admin, pkg, lockdown = false)`, `L/server/AppEnforcer.kt`), covers every app (no allowed or
   disallowed list), calls no `allowBypass()`, and routes only `192.0.2.2/32` (its fake DNS server). Two consequences
   (corrected 2026-10-12 after the main session's review; the step 1-4 code bound every socket to the Wi-Fi `Network`):
   - **No socket may be bound to the Wi-Fi `Network`.** For an app inside a VPN that doesn't allow bypass, Android
     refuses `Network.bindSocket`/`bindProcessToNetwork` to any other network - netd answers EPERM
     (`VpnService.Builder.allowBypass()`: "By default, all traffic from apps is forwarded through the VPN interface and
     it is not possible for apps to side-step the VPN"); only the VPN app itself could (`protect()`). The music app is
     not the VPN app, so a bound OkHttp socket factory failed every SOAP call and a bound SSDP socket every round
     while the filter is on. `SourceScanTest` bans the binding APIs in `src/main`.
   - **Unbound sockets reach the LAN and the multicast group.** The VPN's table holds only 192.0.2.2/32, and without
     lockdown a lookup that finds no route there falls through to the default network's table (the launcher's own
     design: "everything else flows over the real network untouched", `AppEnforcer.applyVpnRestrictions`). So SSDP to
     239.255.255.250:1900 and SOAP to 192.168.x.x leave on Wi-Fi, and the `MulticastLock` lets the replies in
     (design 26 review #3). DNS is not involved (ips only).
   Wi-Fi stays a **gate**: no Wi-Fi network up -> nothing is tried, the speaker reads unreachable and the sheet stale.
   Limit: if Wi-Fi is up but not the default network (a Wi-Fi without internet while mobile data is up, which Android
   then prefers), the LAN traffic follows the default route and the speakers read unreachable; a home Wi-Fi with
   internet is the case this is for. [device check] **VPN filter on: discovery and control still work** (the sheet
   fills by SSDP, a play, a pause, the volume keys; logcat shows no `EPERM`). Android's coming local-network
   permission (`NEARBY_WIFI_DEVICES` on a later target SDK) is one the device owner can grant; noted for later.
6. **Kiosk.** Nothing opens: no Sonos app, no links (`SourceScanTest`). The NRK service precondition is the
   household's, set once from a parent's Sonos app, not from the phone. The speaker resolves hosts outside the
   phone's DNS filter; acceptable for a parent-curated library (design 20).
7. **Calls (QA M6; user 2026-10-11: yes, Element X voice and video calls included).** vibb has no phone. Today a
   system call pauses local playback through audio focus; on Sonos nothing holds focus, and an Element X call
   (WebRTC) sets `MODE_IN_COMMUNICATION` only once connected - while it *rings* nothing in the audio system changes
   (design 17 "Facts": a `CATEGORY_CALL` notification with a ringtone), and design 17 QA #5 forbids trusting
   `MODE_IN_COMMUNICATION` alone (any app can hold it). The launcher already sees both: `VoipCallPlan`'s `RINGING` /
   `IN_CALL` from the notification listener (`L/lock/VoipCallPlan.kt:70-75`) and system calls (`isInManagedCall`,
   `MODE_IN_CALL`). So the launcher sees the calls, the same for local and Sonos playback: when a VoIP call starts
   ringing or a system call starts while the music plays it sends the player `CALL_HOLD`, and `CALL_RELEASE` when that
   call is over (the session's custom actions, names in `bridge_contract.json`); the player pauses as its own pause
   (the kid's sleep timer runs on - a plain session pause is the kid's and would end it, qa-26a-step3b-code H1),
   remembers that it did, and plays again only then (a kid who paused during the call stays paused; a pause the kid
   made before the call is untouched; not after the sleep timer ended during the hold). The music app holds no audio focus while Sonos renders and keeps `AudioManager`'s
   `OnModeChangedListener` (`MODE_IN_CALL` / `MODE_RINGTONE` only) as a backstop for a system call when the launcher's
   listener access is missing (design 21 QA #8's listener-free fallbacks). Launcher part: step 3b, `MusicMediaPlanTest`.

## 7. Tests (JVM; vibb's tests ported, one line each)

- **Fixtures** (`music/app/src/test/resources/sonos/`): canned SOAP XML for GetPositionInfo (PLAYING ours, PAUSED,
  STOPPED empty uri, STOPPED uri retained, x-rincon member, foreign uri, TRANSITIONING, `NOT_IMPLEMENTED` metadata),
  GetTransportInfo, ZoneGroupState in both wrapper forms, faults 701/710/711 and 500, a device description, SSDP
  replies (Sonos and non-Sonos). `scripts/fake-sonos.py` serves **the same files**: that is the new "two-fakes" guard
  (`sonos_contract.py:1-10`). `sonos_contract.py`'s closed sets become `SonosSession`'s enums; its canonical snapshots
  become `SonosSessionTest`'s expected `Fields`.
- `DidlTest`: the exact DIDL of vibb's PLAY_URL and PLAY_NRK (escaping once, both artist tags, duration attribute, the
  desc). `SonosUrisTest`: `normUri` on the four prefixes, percent-encoding and `+`; `hms`/`hmsToS` incl. `"0:00:00"`
  -> 0.0, null -> 0.0, `NOT_IMPLEMENTED` -> null; `quote` keeps `/`.
- `SsdpTest`: M-SEARCH text, three sends, a non-Sonos reply ignored, LOCATION -> ip and port.
- `TopologyTest` (`sonos_groups.py:77-108, 120-136`): both wrappers; ip/name healed, invisible dropped, groups
  replaced coordinator-first, nobody answering -> stale cache, picker rows ("Kjøkken + Stua" -> coordinator, a
  three-member label, absorbed members not repeated, unknown member -> no row).
- `SpeakerCacheTest` (`sonos_hold_scan.py:71-99`, `pipewire`): nobody answering -> the old rows at once, `stale`, one
  background scan started and `scanning` true, the call returns in under 2 s; a second refresh while it runs starts no
  second scan; after the round the new rows show and `scanning` is false; a later refresh that fails again keeps the
  rows and scans once more.
- `SonosSessionTest` (`sonos_grouped.py:242-260`, `sonos_migrate_probe.py:200-307`): x-rincon -> grouped away on the same
  poll, mp3radio not grouped, empty uri keeps, own uri clears; no probe for STOPPED+x-rincon / uri retained / PLAYING+empty
  / window past; url match by `normUri` against a live candidate, hint carries the session uri and rides every publish;
  a STOPPED candidate never qualifies; exactly 3 probes; ours+live re-arms; adopt clears; cadence 5 s only while a
  transition pends; stall after 3 frozen polls, one retry; disarm after 600 s; freshness from `measuredAt`; a failed
  poll keeps the last values; `uid`/`kind` set before, `uri` after a play.
- `SonosTransportTest` with a fake `SpeakerIo` recording calls (`sonos_hush.py:72-119`): start 120 -> `[mute true,
  setUri, play, seek 0:02:00, mute false]`, already muted stays muted, start < 5 never touches mute, a SetURI fault
  restores mute and surfaces; 701 retried then degrade.
- `SonosRendererTest` (`sonos_renderer.py:93-167`, `sonos_same_tile.py:113-202`, `sonos_grouped.py:288-331`,
  `sonos_migrate.py:62-115`, `storytel_sonos.py` non-Storytel parts 178-203, 230-243, 255-299, 372-381, 409-432,
  446-470): a start posts one body, never touches Exo; fresh PLAYING -> playing, position ≈ rel + age, duration; stale
  -> not playing, position kept, UNREACHABLE; foreign -> TAKEN_OVER, position hidden; grouped away -> GROUPED_AWAY not
  shadowed, position and duration hidden; switch back bookmarks before exactly one stop; a paused speaker comes home paused; same tile PLAYING
  no-op, PAUSED one resume, untrusted/stale/explicit -> start to our uid; pause freezes the extrapolated position; the
  bm hold keeps 3120 over 39 and releases after 120 s; the regression guard's table (3120->5 kept, 3120->2400 written,
  10800->9000 written, seeked 5 written, 200->5 written, a deliberate restart written); duration carry only for the
  same uri, falsy never erases; playpause STOPPED -> start, PAUSED -> resume, PLAYING -> pause, not ours -> start;
  seek hold TOL/HOLD and the guess never in the snapshot **nor in Room**; migration follow adopts first then writes the
  renderer, no-ops on same uid / other uid / stale / absent, a failed adopt changes nothing; a tick that throws never
  kills the poller; a headset event never touches the renderer; the state table of §3.2 (one failed poll stays
  BUFFERING, never IDLE); `availableCommands`; volume adjust from our own base and clamped to the cap, an external
  higher volume left alone; the start cap (before Play, inside the hush, every room of a group, once per session, no
  cap no read), a lowered cap pulling our own louder set down once, `volOpt` = what we last set; the startup gate (RESUME before `rendererReady` waits; resumption on Sonos starts the
  speaker, not Exo); suspension -> bookmark, stop, queue uri, disarm, also with no service; the bedtime looks retried
  in their budget without the aux reads, the Stop and park within what is left (`SonosLifecycleTest`).
- `PlayBodyTest`: art only when http, NRK series -> `nrk_program` with `programId = item.key` and the slug from the
  target, non-series `hls` filtered, a private-literal host of a `publicOnly` entry filtered, a tailnet-literal host of
  a `lan` entry filtered, listing duration rides along.
- Existing: `GuardsTest` pins updated (OUTPUT-to-Sonos in `PLAYS`, the receiver's path), `ReportTest` unchanged,
  `FitTest` + the long labels, `SourceScanTest` (no `Uri.parse("http`, no screen wake lock, no socket bound to a `Network`
  in any built source set, §6.5), `ContractFilesTest`
  unchanged. Launcher: `MusicPlanTest`/`BridgeTest` for the new restriction, `MusicMediaPlanTest` for a remote session
  (position with update time, speed 1).

## 8. Steps (each a commit set with its tests; branch `music-1b-sonos`)

1. **`sonos/` core**: §2 entirely (Ssdp, Soap, Topology, SpeakerCache, Didl, SonosUris, NrkService, SonosSession,
   SonosTransport, PlayBody, RendererChoice), the manifest permissions, the SOAP fixtures and the §7 tests down to
   `PlayBodyTest`. No change to `PlaybackService`. **The fake speaker** `scripts/fake-sonos.py` (GPL, credited to
   vibb's test fakes in NOTICE): serves N speakers on N ports (`--speakers Stua:1401,Kjøkken:1402`) from one scripted
   topology and the shared fixtures, answers the §2.2 actions with a scripted transport (`--script` lines like
   `t+30 STOPPED ""` for a migration, `t+10 x-rincon:RINCON_B` for grouped-away, `t+5 drop` for unreachable), logs every
   SOAP call; no SSDP (the emulator's user-mode network carries no multicast and every host fake is 10.0.2.2), so the
   cache is seeded through the debug feed (`debug-feed/sonos.json` with ip, **port**, uid, name; `src/debug` only;
   `checkReleaseHasNoDebugFeed`). `docs/testing/emulator.md` §6g.
2. **Renderer axis, local only**: `Renderer`, `LocalRenderer`, `saveNow`, `MediaSession.setPlayer`, the `Output` key
   widening, `rendererReady` (trivially complete), the `GuardsTest` pins; every existing music test green (135 today)
   plus the new ones; no behaviour change (emulator smoke: §6f).
3. **a. `SonosRenderer` core**: §3.2-3.3, §4.2-4.6, §4.8, §5.1-5.2 (sheet, state lines, sleep ramp); the threading
   model; `SonosRendererTest`'s matching rows; emulator against the fake speaker (switch both ways, same tile,
   grouped-away and taken-over scripts, migration, room -> room).
   **b. Lifecycle and the phone**: §4.7 (adopt, the startup gate, resumption), §6.1 (the receiver), §6.2, §6.3 (wake
   locks), §6.7 (calls, after the user's answer), §5.3 (volume: music app + launcher restriction + contract, one
   commit); the remaining `SonosRendererTest` rows; emulator: restart adopt, suspension stop with and without the
   service, volume keys.
   **c. Room scoping** (§5.5): server + launcher + music app in one commit, its tests on all three sides, the PWA
   card; emulator: a restricted room refused, a group with a disallowed coordinator hidden.
4. **Docs and release**: `music/CLAUDE.md` (the renderer axis, the Sonos rules, the fixtures), `launcher/CLAUDE.md`
   (the restriction), NOTICE.md (the `sonosd.py`/`daemon.py` port and the fake, as the existing vibb paragraph),
   design 21's "Sonos is 1b" note -> points here, `docs/design/26` status. Then `music-v0.2.0-rc.1`.

## 9. Deviations from vibb (each with its reason)

| vibb | Here | Why |
|---|---|---|
| Sidecar on 127.0.0.1:3681, `SidecarDown`, `/state?live=1` thread | one process, an actor, a 1.5 s-budget classify | no second process on Android |
| SoCo | own SSDP + SOAP (§2.2) | no GPL-compatible maintained UPnP library; the surface is ten actions |
| `soco.discover` | SSDP for one ip, then GetZoneGroupState | that is what discover does; done in the open |
| `stale_s<12`, `SONOS_STALE_S=15` | fresh = within the cadence chosen after the last successful poll + 10 s | tuned for a 5 s sidecar re-read; would flicker every 15 s poll here; a failure's backoff never extends it |
| `_cadence`: fast (5 s) only for PLAYING in the verb window; TRANSITIONING keeps the last real state's cadence | 5 s whatever the state while TRANSITIONING or within 30 s of a verb/arm | a resume read as PAUSED slept 15 s and a buffering start after an episode end 60 s, past the controller's 8 s optimistic hold (qa-26a-step1-code M1) |
| `down_since`, `fails` local to `poll_loop`, `_last_tr` kept across sessions | reset by every play and adopt, with the last measurement | a play after a long STOPPED was disarmed by its own first poll, and a failed first poll showed the last episode's position as ours (qa-26a-step1-code H1, H2) |
| `age + stale_s` | one age term from `measuredAt` | two clocks across the process split, summed; one process has one |
| bookmark throttle 25 s | a bookmark on every poll | user 2026-10-11: as fine-grained as possible; SD hygiene was the Pi's reason |
| bookmarks only for the `url` kind (1210) | `nrk_program` bookmarked too, same rules | an NRK series is long-form and resumes on the phone; the Pi's gap was never a decision |
| previous never restarts for the url kind (`pending` never cleared) | `Transport.kt`'s rules; `pending` clears when the speaker shows our uri or after 8 s | vibb's rule couldn't fire; the phone's rule is the one the kid knows |
| natural advance plays the next item at 0 (4509) | the same, with `markDone` on the finished item | the phone marks done on auto transitions everywhere |
| room -> phone resumes even a paused speaker (1695-1708) | resumes only a PLAYING speaker | a phone in a pocket must not start sounding (design 20) |
| no volume cap on Sonos | the parent's cap, clamped on our own `SetVolume` only | design 21 user answer: the cap applies to every output (confirmed 2026-10-11); external changes are never fought (vibb) |
| volume via a Pi card | the phone's volume keys (remote `DeviceInfo`), ±3 per press | the mockup has no volume UI |
| shuffle unsupported on Sonos (1892-1901) | supported for url kind | the phone sequences the queue itself, shuffle is only an order |
| sleep timer fades `exo.volume` over 10 s | three `SetVolume` steps over 3 s, pause, volume restored | user 2026-10-11: a short fade; ten SOAP calls for a 10 s ramp is too chatty |
| `content.PREFER_REMOTE` global, `_EXPAND_CACHE` | the renderer passed explicitly to the plan filter | the thread race at `daemon.py:1529` |
| `_hms`-formatted `start_s` in a Python float | `Long` ms internally, `H:MM:SS` at the SOAP edge | Kotlin |
| `openssl`, ALSA, btwatchd, mpv, go-librespot, `sonos_keep_playing` | n/a | Pi-only |
| uid mismatch 404 triggers nothing (docstring says rescan) | a failed play reverts to local and refreshes the topology | the code's actual behaviour, made visible and useful |
| process death keeps polling (sidecar) | the speaker plays on until the next start adopts or a suspension edge stops it | no sidecar (§6.2) |
| no phone, no calls | pause on a call, resume only if we paused (§6.7, pending the user) | the phone rings |
| the bookmark adds the sidecar's `stale_s` (a poll's measurement lag, <= 5 s) | the one age term from `measuredAt`, capped at 5 s (`BOOKMARK_AGE_MAX_MS`) | qa-26a-step3a-code L4: at a 15 s cruise the uncapped age reached 25 s at a save tick, pause or hand-off; a speaker paused from the Sonos app would get a bookmark that far ahead |

## 10. User answers and open questions

Answered 2026-10-11:
1. **Volume keys control the speaker, with the parent's cap** (§5.3): yes.
2. **Sleep timer**: a fade, as long as it is short -> the 3 s `SetVolume` ramp in §5.2.
3. **The NRK Radio service**: the user expected plain streaming links plus DIDL to be enough. True for RSS and NRK
   podcasts (mp3). NRK *series* are HLS manifests, which the Pi downloads locally with ffmpeg and plays on Sonos only
   through the NRK service (`daemon.py:1270-1290`); the household already has it linked (`sn=14` is its known-working
   value). So: no new setup, and §2.3 stays as the port of what works.
4. **Positions with the screen off**: saved as finely as ever; only the screen needn't follow -> the cadence is
   vibb's, a bookmark on every poll, the UI idles (§4.4, §6.3). If Doze defers the polls, the launcher grants the
   battery exemption rather than the bookmarks getting coarser.

Answered 2026-10-11, after the QA review:
5. **Rooms (QA M5):** every room allowed by default; the parent may restrict the phone to specific speakers -> §5.5,
   step 3c.
6. **Calls (QA M6):** supported: the speaker pauses on a call and resumes afterwards only if we paused it -> §6.7,
   step 3b.
7. **Is `nrk_program` needed at all? (user, 2026-10-11)** vibb's recipe is inherited from the user's older
   sonos-remotes project and was committed "unverified until the rig" (`0255dc3`, 2026-08-09); nothing in vibb ever
   tried handing the speaker the HLS manifest url as a plain `url` kind. If a Sonos (S2 firmware) plays and seeks an
   NRK series manifest directly, the whole service path goes (the serial lookup, the `<desc>`, the precondition).
   Against it: psapi manifest urls may carry expiring tokens the service handles itself, and HLS seek on Sonos is
   unproven either way. **Answered (user, 2026-10-11, tested on a speaker): the split is necessary** - a Sonos does
   not play an NRK series manifest handed over as a plain url. `nrk_program` stays as the port of what works.

## 11. QA review (2026-10-11, design QA of this spec)

High: **H1** the poller stops when the CPU sleeps (no wake lock on a `SimpleBasePlayer`); **H2** the state mapping
dropped the FGS on a transient (IDLE on one failed poll) and copied vibb's freshness constants, which flicker at a 15 s
cadence; **H3** the save paths would persist extrapolated positions and listing durations (`savePosition` reads the
renderer's display position); **H4** bedtime didn't stop the speaker without the service (`SuspendedReceiver` posts a
null lambda) and classified nothing before the ours-gate; **H5** double playback at startup (RESUME before adopt);
**H6** the volume cap isn't in the managed config, so §5.3 needs a launcher and contract change. Medium: **M1** the
`availableCommands` list missed the GET commands (empty platform metadata) and volume adjust; **M2** no threading
model (`SimpleBasePlayer` ignores `invalidateState` while a handler future is pending); **M3** `nrk_program_id` is
unnecessary (the item key is the program id; the RSS fallback is podkast-only) and broke the step order; **M4**
fidelity: nrk bookmarks, the index rule, vibb's dead previous rule, position ours-gating, the natural advance, room ->
phone on a paused speaker, the encodings; **M5** the speaker's own Play button replays after a Stop, and rooms aren't
scoped; **M6** calls unspecified; **M7** clients not bound to Wi-Fi, the public-address rule not mapped, `hls` not
filtered; **M8** step 3 too big, the emulator path (no multicast, one host ip, hardcoded 1400), the fixture pinned the
removed sidecar JSON. Low: L1 encodings, L2 internal contradictions (sleep fade, failed-play rescan, test count,
"Launcher: none"), L3 untested vibb checks, L4 deliberate restarts vs the regression guard, L5 English strings and the
three-member label, L6 SSDP x3 and the two ZoneGroupState wrappers, L7 gaps (onDestroy Stop, "clear audio effects",
NOTICE for the fake), L8 info. Seven ambiguities (the stored uri, reorder/THEN_SEEK/setMediaItems in `Renderer`, the
sheet's commands, phone -> room with no queue, `setNowPlaying.playing` on Sonos, sleep interplay, `uid` before success).

## 12. Decisions after QA review (2026-10-11)

Every finding is adopted and folded into §0-§9 above: the actor and main-confined state (§0, M2); the §3.2 state
table, freshness from `measuredAt` + cadence, `startInForegroundRequired` (H2); `saveNow` as the one persistence path
(§3.1, §4.4, H3); the standalone suspension receiver with the queue uri (§6.1, H4, M5); `rendererReady` and
resumption on Sonos (§4.7, H5); the `volume_cap_pct` restriction (§5.3, H6); the command list and ±3 adjust (§3.2,
§5.3, M1); no server change, `programId = item.key` (§2.5, §5.4, M3); the deviations table rows (§9, M4); Wi-Fi-bound
clients, the address guard in the filter, `hls` filtered (§2.1, §4.2, M7); steps 3a/3b, the fake with ports and the
debug-seeded cache, SOAP fixtures shared with the fake (§7, §8, M8); the encodings (§2.3, L1); the contradictions
resolved (L2); the extra vibb checks (§7, L3); `seekAt` on deliberate restarts (§4.2, L4); English strings and the
long label (§5.1-5.2, L5); SSDP x3, both wrappers (§2.1, L6); `onDestroy` stops, the audio-effects phrase dropped,
NOTICE for the fake (§6.2, §4.6, §8, L7). The two user questions are §10 items 5 and 6; step 3b waits for item 6.

## Device checks (Jelly Star, real speakers; after step 3)
- Picker: rooms and a group row; a renamed or re-addressed speaker (DHCP) heals on the next refresh; "Se etter
  høyttalere" after a cold cache; the cabin case (home rooms listed stale, nothing plays, no scan without Wi-Fi).
- Play an RSS episode and an NRK series episode at 0 and from a 12-minute bookmark (the hush: no audible second from
  0:00); the Sonos app shows title/artist/art; `didl accepted` in logcat.
- Pause/resume from the phone, from the Sonos app, and from the speaker's buttons; next/previous mash; seek drag.
- Switch phone -> room -> other room -> phone: position within 3 s each time; both rooms silent when they should be; a
  paused speaker comes home paused.
- Group the speaker from the Sonos app (grouped away), play something else on it (taken over), remove our coordinator
  from its group (migration follow), pull its power (unreachable, backoff, disarm after 10 min); the notification and
  FGS survive a Wi-Fi blip.
- Kill the music app while Sonos plays: the speaker plays on; reopen -> adopt, correct position, no double playback
  from Home's card; reboot the phone -> the bar comes back paused.
- A bedtime edge mid-episode on Sonos, with and without the app alive: the speaker stops; its Play button plays
  nothing; the lock's media view and Home card went away.
- Screen off 1 h in Doze while Sonos plays: poll timestamps in logcat every 15 s, the bookmark after a forced stop
  within 15 s of the truth, battery per hour; the sleep timer's fade and the volume restored after it.
- Volume keys: ±3 per press, the slider, the cap holds; a speaker left louder than the cap starts at the cap (no loud
  first second), every room of a group row too; the override ending while the keys had the room above the cap pulls
  it down; a louder volume set from the Sonos app during the session is left alone.
- An incoming phone call and an Element X voice/video call during Sonos playback and during local playback: paused
  at the ring, played again after only when it was playing; the kid's own pause during the call stays.
- VPN filter on (the launcher's always-on `KidVpnService`): discovery and control still work - the sheet fills by
  SSDP, a play, a pause, the volume keys; no `EPERM` in logcat (§6.5: no socket is bound to the Wi-Fi `Network`).
- Room scope (step 3c, §5.5): the device card before the phone has seen a speaker ("The phone hasn't seen any speakers
  yet") and after (the real rooms under "All rooms"); "All rooms" off and one room unticked -> after the next sync the
  sheet lacks it, a session playing there was bookmarked, stopped and came home paused ("Rommet er ikke lenger
  tillatt"); a group with any unticked member has no row; picking a restricted room is refused (the speaker
  untouched); grouping a forbidden room into the playing one from the Sonos app stops the session within a minute.

## Step 1 implementation status (2026-10-11)

Built on branch `music-1b-sonos`; compiled and tested in the sandbox with a pure-JVM harness, **not yet with the
Android build** (the sandbox can't reach dl.google.com, so no Android SDK or Google Maven): the AGP build, the module's
`testDebugUnitTest` (with android.jar on the classpath), lint, R8 and `checkReleaseHasNoDebugFeed` run in CI. No
`PlaybackService`, `PlayerHub`, UI or launcher change: nothing uses the package yet.
- **In** (`M/sonos/`, 13 files): `Ssdp` (SoCo's M-SEARCH sent three times, `SERVER` must name Sonos, `LOCATION` ->
  ip + port, a round over an `SsdpIo`) and the Android `SsdpScanner` (MulticastLock for the round only, the socket
  bound to the Wi-Fi `Network`); `Soap` (OkHttp, one pooled client, 2 s/5 s, no proxy, a `SocketFactory` for the
  Wi-Fi binding, `HttpUrl.Builder`, javax.xml DOM, `UpnpFault`/`SpeakerUnreachable`/`UnknownUid`, `SoapSpeaker` for
  the §2.2 actions); `Topology` (both wrapper forms, invisible members and satellites out, coordinator first);
  `SpeakerCache` (`sonos.json` with `port`, per-row lenient read, players merge / groups replace, the picker rows of
  `_sonos_choices`), `RoomDirectory` (the topology refresh under 2 s per ip and 6 s in all, stale + one background
  SSDP round, only on Wi-Fi) and `CachedSpeakers` (the uid check on the play path only); `SonosUris`, `Didl`,
  `NrkService` with Python's encodings; `SonosSession` (the pure machine: classify, aux every 12 polls, cadence, stall,
  migration probe, disarm, `Fields.fresh`); `SonosTransport` (play, hush, `seekSettled`, adopt, verbs, the stall
  retry, behind one Mutex, over an injected `SonosClock`); `PlayBody` with the speaker-playable filter;
  `RendererState` (`RendererChoice` since the fix round); `SonosFiles` (the two `noBackupFilesDir` paths). The manifest gains `CHANGE_WIFI_MULTICAST_STATE`
  and `ACCESS_WIFI_STATE`.
- **Fixtures and the fake**: 26 canned replies in `music/app/src/test/resources/sonos/` (§7's list, plus GetVolume,
  GetMute, an empty answer, `/status/accounts` and an NRK position); `scripts/fake-sonos.py` builds every answer from
  those files (`--selftest`, `--print-seed`, `--script` with grouped-away, takeover, migration, drop/back, stall and
  volume events); the debug feed merges `debug-feed/sonos.json` into the cache; `docs/testing/emulator.md` §6g.
- **Tests**: 98 new JVM tests in 9 classes - `SonosFixturesTest` 11 (every fixture, the client on the wire against a
  loopback socket server), `DidlTest` 6 (expected strings printed by vibb's own `didl()`), `SonosUrisTest` 7 (values
  printed by vibb's own functions), `SsdpTest` 4, `TopologyTest` 12 (`sonos_groups.py` 1-4), `SpeakerCacheTest` 8
  (`sonos_hold_scan.py` 1-4, the file, the resolver, `renderer.json`), `SonosSessionTest` 23 (`sonos_grouped.py` 1,
  1b, 1c; `sonos_migrate_probe.py` 1, 2, 2b, 2c, 4, 5; `sonos_contract.py`'s canonical snapshots as `Fields`; stall,
  disarm, cadence, freshness, the failed poll), `SonosTransportTest` 20 (`sonos_hush.py` 1-4, the 701/710/711 rule),
  `PlayBodyTest` 7. Not ported, by design: `sonos_migrate_probe.py` 3 and `sonos_hush.py` 5 (`spotify_sharelink`,
  phase 3), `sonos_groups.py` 5-6 and `sonos_grouped.py` 2-3 (the Pi screen and the controller: `SonosRendererTest`,
  step 3).
- **What ran here**: a Gradle project in the session scratchpad with Kotlin 2.1.20 (`allWarningsAsErrors`),
  kotlinx.serialization 1.8.1, OkHttp 4.12.0, coroutines 1.10.2, JUnit 4.13.2, JDK 21 targeting 17, compiling every
  `sonos/` file except `SsdpScanner.kt` and `SonosFiles.kt`, plus `model/*.kt` and `logic/Addresses.kt` they use (one
  JDK-21-only warning in the existing `Addresses.kt` suppressed there: JDK 21's `InetAddress` is sealed); all 98 tests
  green, and two mutations (the one-retry rule, the 701 retry set) turned 4 of them red. `SsdpScanner.kt`,
  `SonosFiles.kt` and the changed `DebugFeedReceiver.kt` type-checked against hand-written stubs of the android.jar
  members they call (not the real SDK). A harness-only test drove the Kotlin client against `scripts/fake-sonos.py`
  (seed, topology refresh, a hushed play with a settled seek, polls, grouped away, the migration probe, adopt, pause,
  stop): green. `python3 -I scripts/fake-sonos.py --selftest`: OK.
- **Device checks**: none new (the list above stands); the emulator's are in §6g, from step 3a on.

Choices made while building (each against the spec text, with its reason):
1. **The port comes from the topology's `Location` too**, not only from the debug feed (§2.1 says only the feed sets
   one): a real speaker's `Location` always says 1400, and the fake's topology names its own ports, so a refresh keeps
   the debug-seeded port instead of resetting it to 1400.
2. **The snapshot carries `nextPollMs` and `failedPolls`**: freshness (§2.4, `cadence + 10 s`) and §3.2's "the first 3
   failed polls" are then computed from the published snapshot alone, on main, without reaching into the actor.
3. **`volume` doesn't wake the poller** (§2.5 says every verb does): vibb's `verb("volume")` doesn't, since the set
   value goes straight into the snapshot; the other verbs wake it.
4. **`TransportState.UNKNOWN`** for anything outside the closed set (NO_MEDIA_PRESENT, ...): vibb passes the raw string
   and its daemon maps unknowns to the conservative branch; an enum needs the member. An empty state is STOPPED.
5. **`retriedAt` is elapsed time**, not vibb's wall clock: one clock in the machine; it is only a "retried" marker.
6. **The stall retry runs after the publish** (vibb: before it, under the same lock): the transport does the SOAP, the
   machine stays pure; the next poll sees the effect either way.
7. **`PlayBody.build` takes `art` as a parameter**: the listing's `art` is parsed (`ListingItem.art`) but Room doesn't
   keep it (`ItemRow` has no art column), so step 3 passes it or null (null = no `albumArtURI`, as a non-http art).
8. **The picker's group rule is vibb's code** (`_sonos_choices`: unknown members dropped, a row while two known rooms
   remain): §2.1 says "a group naming an unknown uid gives no row", which is the same for a two-member group (vibb's
   test case) but keeps a row for a three-member group with one unknown member.
9. **`fault_500.xml` is a SOAP fault with UPnP code 500** (the "not retried" case); an HTTP error without a SOAP fault
   (an HTML page, a 503) is `SpeakerUnreachable`.
10. **Tests use a plain-socket `TinyHttp` and `runBlocking`**: android.jar has no `com.sun.net.httpserver`, and
    MockWebServer and coroutines-test aren't music dependencies; no build change.
11. **The fake's uids are `RINCON_FAKE<port>01400`** and it binds 127.0.0.1 (the emulator's 10.0.2.2 is the host's
    loopback): inside §5.5's `RINCON_[A-Z0-9]{12,20}`, and nothing on the LAN.
12. Left for step 3 because they are §4/§6, not §2: the live classify with a 1.5 s budget (switch home, the
    suspension receiver, adopt at startup) and the `x-rincon-queue:` park after a suspension Stop.

### Fix round after QA's code review (qa-26a-step1-code, 2026-10-11)

CI's first Android run (38167939758) built everything (debug, release, R8, lint, `checkDebugHasDebugFeed`) but the
unit-test compile: `SonosFakes.kt:15` called `.list()` on `parentFile`, which android.jar marks nullable and the JDK
leaves a platform type - now `parentFile!!`. The review (`docs/design/qa-26a-step1-code.md`: 2 High, 2 Medium, 9 Low)
is fixed in full:
- **H1** `played()`/`adopt()` reset the down streak and the failure count (with H2/M1 in one `newSession()`): a play
  after 595 s STOPPED stays armed, its first failed poll backs off 10 s.
- **H2** they also drop the last session's measurement (`lastOkAt` and the snapshot's measured fields): a failed first
  poll after a play publishes `ours=false`, no position, `measuredAt=null`, never fresh.
- **M1** `lastTr` resets per session; the cadence is 5 s whatever the state while the speaker reads TRANSITIONING or
  inside the verb/arm window (`Fields.transitioning`). §2.4 and §9 say so.
- **M2** freshness is anchored on the cadence chosen after the last successful poll (`Fields.measuredCadenceMs`), so
  failures never extend it; §2.4 and §9 reworded.
- **L1** `Topology.location` takes IP literals only, `Soap` refuses a non-literal host and maps a bad host or port to
  `SpeakerUnreachable`, and a body without its series is a `SonosException` (no `requireNotNull`).
- **L2** `Soap(() -> OkHttpClient)` (the old constructor stays): a Wi-Fi reconnect swaps the provider's value only.
  `SoapSpeaker(..., budgetMs)` and `SpeakerResolver.speaker(uid, verify, budgetMs)`: one time budget shared by a
  speaker's calls, for the 1.5 s live classify of step 3.
- **L3** `RoomDirectory.state: StateFlow<RoomsView>`, published on every refresh and when a round starts and ends.
- **L4** the scanning flag is cleared in `invokeOnCompletion`, so a scope cancelled before the round ran can't wedge it.
- **L5** `sonos.Player` -> `Zone`, `RendererState` (renderer.json) -> `RendererChoice` (`RendererChoice.kt`); §1, §3.3,
  §4.1 and §8 use the new names, §3.3's UI enum keeps `RendererState`.
- **L6** the speaker-playable filter reads the URL with `toHttpUrlOrNull()` (an unencoded space or `|` plays, as on
  the phone); an NRK series item without a key drops out ("no program id"); numeric shorthand hosts are accepted and
  the comment says why.
- **L7** `SonosSession.snapshot` is `@Volatile` (the actor writes it; main reads whole copies).
- **L8** `music.yml` runs `python3 -I scripts/fake-sonos.py --selftest` after the build and watches the script;
  root `CLAUDE.md`'s CI paragraph and `scripts/` list name it.
- **L9** `SpeakerCacheTest` 1-2 wait on the round's own latch (5 s), not 1 s of polling.

Tests: 109 (+11: H1, H2, M1 x2 and M2 in `SonosSessionTest`; L1 and L2 x2 in `SonosFixturesTest`; L1 in
`SonosTransportTest`; L4 in `SpeakerCacheTest`; L6 in `PlayBodyTest`; L1's Location cases and L3 joined existing tests).
The JVM harness (warnings as errors) ran all 109 green plus the interop test against `scripts/fake-sonos.py`; the
Android wrappers and the debug receiver type-check against the stubs; `--selftest` OK. The Android build runs in CI.

## Step 2 implementation status (2026-10-11)

Built on branch `music-1b-sonos` in two commits (the output keys, then the renderer axis), each built and tested on
its own. **This time with the real Android build** in the sandbox (SDK 36, build-tools 35/36, Google Maven
reachable): `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` green, **251 JVM
tests, 0 failures** (the 135 + 109 of before, +2 in `GuardsTest`, +5 `OutputKeyTest`), `lintDebug` 0 errors (its
one warning on the new code, `SwitchIntDef` on the playback-state switch, is the old one, moved). Not run on an
emulator or a phone (no KVM here): §6f's smoke test is still due. No behaviour change intended; `FitTest`,
`SourceScanTest`, `ReportTest`, `BridgeTest` and `ContractFilesTest` are untouched, and so are the AIDL and
`bridge_contract.json` (the output key is app-internal).
- **What moved where** (`M/playback/`): `Renderer.kt` (the interface, `RendererKind`, `RendererEvent`, `PauseCause`);
  `LocalRenderer.kt` builds the ExoPlayer (media source factory, attributes, noisy, wake mode) and holds every read and
  write the service made on it: `currentPosition/duration/currentMediaItemIndex/mediaItemCount`, `setMediaItems` +
  `prepare` (`load`), the shuffle's remove/add (`reorder`), `seekTo` (both forms), `play/pause/prepare`,
  `clearMediaItems`, `volume`, `setAudioAttributes/setWakeMode/setPreferredAudioDevice`, `release`, and the
  `Player.Listener`, whose callbacks come back as `RendererEvent`s. `savePosition()` is `LocalRenderer.saveNow(force)`
  (its body unchanged; `force` = the old `blocking`, from `onTaskRemoved` and `onDestroy`), called from every former
  caller (`saveTick`, `start`, `goTo`, `playItem`, `toggleShuffle`, the pause, the transition, the transient focus
  loss, the error, the sleep timer's end, `onTaskRemoved`, `onDestroy`). `positionMs()` feeds `PlayerHub.position`,
  the launcher's `setNowPlaying`, previous's 3 s rule and the resume overlap - never a stored row. The service's
  `listener` is `onRendererEvent` (its bodies split into `onItemChanged`, `onIsPlayingChanged`, `onError`, unchanged).
  `Active` is a top-level `internal` class (the renderer reads the queue to save it). `audioAttributes(speech)` moved
  next to the player. `PlayerModel.kt` (Android-free): `Output`, `OutputKind` (+`SONOS`) and `RendererState`.
- **The session**: `MediaSession.Builder(this, KidPlayer(renderer.player))`; `switchRenderer(next)` sets the renderer
  and calls `session?.setPlayer(KidPlayer(next.player))`, uncalled until step 3 (kotlinc has no unused-private warning;
  the warnings-as-errors build confirms). `KidPlayer` is unchanged.
- **`rendererReady`**: a `CompletableDeferred<Unit>` completed in `onCreate` after the session; `onCustomCommand`
  keeps the `playAllowed` gate and the unknown-action refusal synchronous, then launches one coroutine that awaits it
  and dispatches (on `Main.immediate` with the deferred complete that runs in place, so SHUFFLE/SLEEP/OUTPUT stay
  synchronous as before); `onPlaybackResumption`'s coroutine awaits it before `restore()`.
- **Outputs**: `Output(key: String, kind, name)` with `"phone"` (`PHONE_KEY`), `"dev:<id>"` (`deviceKey`/`deviceId`),
  `SONOS_PREFIX = "sonos:"` reserved; `Commands.ARG_OUTPUT` is the key (`getString`, default the phone);
  `chosenOutput: String?`. `outputIcon`, `outputName` and the sheet's glyph and subtitle are exhaustive `when`s;
  SONOS shows the speaker glyph and "Sonos" (a new string, `output_sonos`, en and nb). OUTPUT stays ungated: §3.3's
  gate for a Sonos key comes with the key, in step 3.
- **`PlayerHub.State`** gains `rendererState: RendererState? = null`, `rooms: List<sonos.Room> = emptyList()`,
  `roomsStale = false`, `scanning = false`, all inert.
- **`GuardsTest` pins changed**: `MediaSession.Builder(this, KidPlayer(exo))` -> `...KidPlayer(renderer.player))`;
  the 15 s tick's `savePosition()` -> `renderer.saveNow()`, its slice now ending at `private fun onRendererEvent`
  (was `private val listener`). Unchanged and still passing: `KidPlayer`'s two gates, the command gate (its slice
  still ends at `when (customCommand.customAction)`), resumption's gate, `PLAYS`, the Home card's resume. Added: every
  `setPlayer(` wraps `KidPlayer` (exactly one, in `switchRenderer`); both the command coroutine and resumption's await
  `rendererReady`, completed exactly once; the service names no `exo.`/`ExoPlayer`/`savePosition` and stores no
  `positionMs()`, and `LocalRenderer.saveNow` reads `exo.currentPosition`. Three mutations (resumption without the
  await, a bare `setPlayer(next.player)`, the tick without `saveNow`) each turned the suite red.

Choices made while building (each against the §3.1 text, with its reason):
1. **`onEvent: ((RendererEvent) -> Unit)?`, not `events: Flow<RendererEvent>`.** ExoPlayer calls its listeners
   synchronously inside the call that caused them, and the service depends on that: `sleepPausing` is true only
   during the sleep timer's own `pause()`, `start()` sets `active` before `setMediaItems` fires the transition, an
   error handler's seek/prepare/play runs before `onEvents`' publish. A Flow collected on `Main.immediate` queues an
   emission behind a coroutine already running on main (every custom command runs in one), so the order would change.
   A synchronous callback keeps it; step 3's `SonosRenderer` emits on main the same way.
2. **`load(items, startIndex, startMs, playNow)` without a `PlayPlan`** (no such type exists; step 3 adds what the
   speaker needs). `start()` passes `playNow = false` and then plays, so the wake mode is still set between `prepare`
   and `play` as before.
3. **`reorder(keep: Int, after: List<MediaItem>)`, not `reorder(order: List<Int>)`**: shuffle on/off rebuilds the
   queue from the entry's whole play order, which can hold items the player's list doesn't (a queue restored after a
   restart keeps only its stored keys, `restoredQueue`), so an index permutation of the current list can't express it;
   and the current item must play on untouched, which is what the remove-around/add-after edit does.
4. **Added to the interface**: `prepare()` (the error path and a seek from IDLE), `seekTo(ms)` (in-item seeks, as
   `exo.seekTo(ms)` before), `setVolume(v)` (the sleep fade's steps), `setAudioAttributes/setWakeMode/
   setPreferredAudioDevice` (§3.2: no-ops on Sonos) and `release()`.
5. **No `fadeOutAndPause()` yet**: the local 10 s fade is the service's `sleepTick` (250 ms steps, cancelled by a
   manual pause or a new timer); moving it into the renderer would rewrite the timer in a no-behaviour-change step.
   Step 3a adds `fadeOutAndPause()` with §5.2's ramp and decides whether the local fade moves behind it.
6. **`rooms` reuses `sonos.Room`** (label, coordinator uid, member names: step 1's picker row from `_sonos_choices`,
   what `RoomDirectory.state` already publishes), not a new `Room(uid, name)` (a second type for the same row, and a
   clash of names) nor `sonos.Zone` (it carries ip and port, and the UI never shows an ip).
7. `Output`, `OutputKind` and `RendererState` live in `PlayerModel.kt`, not `PlayerHub.kt`: Android-free, so
   `OutputKeyTest` needs no Bundle. `RendererState`'s doc says it is not `sonos.RendererChoice`.

### Fix round after QA's code review (qa-26a-step2-code, 2026-10-11)

QA (`docs/design/qa-26a-step2-code.md`: 0 High, 2 Medium, 6 Low) confirmed the behaviour preserved, 251/0 and lint
clean; every finding is fixed:
- **M1** `switchRenderer` -> `attach(next)`, the one place the renderer changes (`onCreate` and step 3's switch): the
  old renderer is saved (`force`) and paused **before** it is detached (its events still reach the service; the pause
  runs under `selfPausing`, the renamed `sleepPausing`, so it never cancels the sleep timer), then only `next` reports
  and the session wraps `KidPlayer(next.player)`. Pinned in `GuardsTest` (a new test).
- **M2** the display-position pin is an allowlist: four `positionMs()` readers by line, no `.currentPosition` /
  `.contentPosition` in the service, its only rows in `markDone` and `saveBookmark`, `rendererReady` completed inside
  `onCreate`.
- **L1** the shown output falls back to a Bluetooth or wired output only. **L2** `currentIndex()` indexes the queue
  given to `load`/`reorder` (`Active.items`); a renderer never filters. **L3** `setVolume` -> `setFadeGain` (local gain
  only). **L4** `onEvent`'s contract: main thread, synchronous, possibly from a post; only the current renderer's; the
  handler may re-enter the renderer. **L5** `PlayerHub.State.rooms` is one `RoomsView`. **L6** `setOutput` stores the
  canonical `deviceKey(device.id)`.

For step 3a (QA's notes, not code here):
- Whether a Sonos room is an `Output` in `outputs`, a `Room` in `rooms.rooms`, or both is 3a's call; the player's bar
  needs the `Output`.
- `MediaSession.setPlayer` requires the new player on the session's application looper and the call on it: the
  `SimpleBasePlayer` is built on the main looper.
- The command coroutine runs at once only when its caller isn't itself inside a `Main.immediate` coroutine (then the
  launch is queued behind it); Media3 calls `onCustomCommand` from a handler message, so today it does.
- The `PLAYS` pin is an action set and can't say "OUTPUT to a Sonos key is a play": the gate must read `ARG_OUTPUT` in
  `onCustomCommand`'s synchronous prefix.
- `publish` and `reportNowPlaying` read `renderer.player.playWhenReady`, which §3.2's table holds through the first
  failed polls while §3.3 says a stale snapshot shows "not playing": 3a likely needs a `renderer.showsPlaying`.

Build: `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` green; 252 tests, 0 failures
(+1); lint 0 errors.

## Step 3a implementation status (2026-10-11)

Built on branch `music-1b-sonos` in three code commits, each built and tested on its own (`833e8f8` the pure
controller, `cd1fedf` the renderer, `5512865` the wiring and the UI), with the real Android build in the sandbox:
`./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` green at each - **293,
295, then 298 JVM tests, 0 failures** (252 before: +36 `SonosControllerTest`, +4 `SonosBookmarksTest`, +2
`SonosActorTest`, +1 `PlayBodyTest`, +2 `GuardsTest`, +1 `FitTest`), lint 0 errors and the same 41 warnings;
`python3 -I scripts/fake-sonos.py --selftest` OK. `SourceScanTest`, `ReportTest`, `BridgeTest` and
`ContractFilesTest` are untouched and green; no launcher, AIDL, contract or server change. **Not run on an emulator
or a phone** (no KVM here): §6g's 3a checks and §6f's smoke (the local sleep fade moved) are owed, listed below.

- **The controller** (`M/sonos/SonosController.kt`, pure; `SonosBookmarks.kt`): main-thread state over an injected
  actor (`SonosWork`), clock and bookmark store (`SonosStore`). `load`/`playTile`/`playPause` (§4.2, §4.5: the body
  built once, `pending`/`optTr`, the seeded snapshot, the bounded hold, `seekAt` on a deliberate start), the failed
  play (a start's -> `Lost`; a step's or an advance's dropped), the poll tick in §4.3's order, the one-age-term
  position, `bookmarkNow` (§4.4, the only write), the ours-gated `pause`/`stop`, the coalesced step (latest wins, at
  0) and seek (latest wins, 0.7 s, `optPos`, clamped to `dur - 5`, refused without a duration), the hand-off (§4.6:
  live classify, bookmark, Stop), migration follow (adopt first), volume (clamped 0..100) and the sleep ramp.
  `spec(now)` is §3.2's state table as data (`SonosSpec`: phase, playWhenReady, index, position anchored at
  `measuredAt`, duration); `showsPlaying`/`rendererState` are §3.3's and §4.8's predicates.
- **The renderer** (`SonosRenderer.kt`): a `SimpleBasePlayer` on the main looper whose `getState()` copies `spec`
  (playlist from the service's MediaItems, `DeviceInfo(REMOTE, 0, 100)`, `deviceVolume`, the `SONOS_COMMANDS` list
  of §3.2) with a `PositionSupplier` over the spec; handlers set intent and return `immediateVoidFuture()`, and the
  `RendererEvent`s they cause are posted (emitted outside the handler); actor and poll results flush at once.
  `SonosActor` (one Channel consumer on IO; each result posted to main before the next job runs, so a poll of the old
  track can never land after the play that replaced it), `SonosPoller` (the session's cadence, woken by verbs),
  `SonosHome` (Wi-Fi tracked by a `NetworkCallback`, every Soap socket bound to it; the cache, `RoomDirectory`, the
  speakers, NRK, the probe, renderer.json). `SonosTransport.liveRefresh` is the 1.5 s `/state?live=1`.
- **The renderer axis** (`M/playback/`): `Renderer` gains `showsPlaying`, `rendererState`, `room`, `fadeMs`,
  `fadeOutAndPause(done)`/`cancelFade()` (replacing `setFadeGain`; the phone's 10 s fade moved into `LocalRenderer`)
  and `RendererEvent.Lost`; both renderers persist through one `persistPlace`. `PlaybackService`: the OUTPUT gate,
  `output`/`toRoom`/`toPhone`/`comeHome`/`lost` through `attach()`, `queueFor` + the filter in `activeFor`,
  `ROOMS_REFRESH`/`ROOMS_RESCAN`, `publish`/`reportNowPlaying` through `showsPlaying`, `rooms` and `rendererState`
  into `PlayerHub.State`, `Message.CANT_PLAY_ON_SONOS`.
- **UI** (§5.1-5.2): the sheet's rooms (group labels, "Sonos · svarer ikke"), "Se etter høyttalere" / "Søker etter
  høyttalere…" with a spinner, `ROOMS_REFRESH` when it opens; the bar's room highlighted; the state line under the
  entry name; the message. Strings in `values` and `values-nb`.
- **Media3 1.7.1 as found**: `invalidateState()` is ignored while a handler future is pending, so every handler returns
  a completed one; `State` refuses an empty playlist outside IDLE/ENDED and checks the index, so the state falls back
  to IDLE whenever the MediaItems and the queue differ. **The paused-FGS question (§3.2)**: `MediaNotificationManager`
  already keeps the foreground for `DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000` after the player stops being
  "engaged" (playWhenReady and READY/BUFFERING) - the same 600 s as vibb's disarm - so no `onUpdateNotification`
  override was needed; a [device check] confirms a paused Sonos session keeps its notification for 10 min.

Choices made while building (each against the spec text, with its reason):
1. **The bookmark is about the item actually on the speaker** (`Playing`: index, key, built uri from the play's
   result) and needs the snapshot's uri to equal that uri, not `index`: vibb's url kind could write the old track's
   position under the optimistic new index while a step was in flight (its `pending` only guarded the sharelink kind).
2. **The seed of a play is never bookmarked** (it is the start position we asked for, not a measurement) and
   **the pause freeze is a display hold** (`frozen`), not a patch of the snapshot as vibb's: a `saveNow` between the
   pause and the next poll (the service saves on every pause) would otherwise persist an extrapolation (QA H3).
3. **The age term is added only while the snapshot is fresh and PLAYING** (so at most the cadence + 10 s): a stale
   PLAYING snapshot would otherwise add minutes of invented listening.
4. **While a play is in flight the old track's polls are ignored entirely** (the seed stands), and `pending` expires
   8 s after the play's **result**, not its submission: a hushed play with a settled seek takes up to ~10 s in the
   actor, and the old track read as "not ours" would flash "taken over".
5. **`playPause` (the player's play) and `playTile` (`Renderer.play`: the tile, RESUME, the first play after `load`)**:
   the tile falls through to a full start while a press is settling (`sonos_same_tile.py` 4); the player's play does
   nothing then (a play in flight is the answer; a second tap must not re-transfer). A pause pressed while a play is
   in flight follows it through the actor and its intent is kept.
6. **"Deliberate" is inferred**: `Renderer.load` has no such flag, so a start below the item's saved place (a pick
   with resume off, a pre-start step or seek) stamps `seekAt`; steps and seeks always do. A cover tap resuming the
   bookmark does not (the refused-seek hold covers it).
7. **Steps start at 0** (vibb `_sonos_play_entry(want, 0.0)`, §4.5), unlike the phone's `goTo`, which resumes an item's
   own place; the position the service passes is honoured only before a start. See the open questions.
8. **A failed step or advance puts the index back** on what the speaker plays (vibb leaves the optimistic index
   showing a track that isn't playing); a failed start is `Lost`: "Kan ikke spilles på Sonos", the phone paused, one
   topology refresh.
9. **Switch positions**: phone -> room starts at the item's saved place as `resumeFrom` gives it (after the phone's
   forced save), no `seekGate` (vibb's `episode_pos`; the speaker seeks from 5 s); room -> phone rebuilds the entry's
   play order rotated to the current item (as `pickPlan`), at the bookmark the hand-off just wrote, not the stored
   row (that one holds only the speaker-playable keys). Picking the room we're on is the same-tile rule (vibb
   re-transfers). Coming home also needs `playAllowed` (a suspended app never plays on).
10. **The output axis stays the phone's**: `setPreferredAudioDevice` and the headset callbacks go to `LocalRenderer`
    always, never through `renderer` (a headset never touches the renderer, `sonos_renderer.py` 5; pinned).
    Rooms stay in `PlayerHub.State.rooms`; the current room is `renderer.room` (an `Output`, which the bar needs).
11. **The sleep timer**: `fadeOutAndPause(done)` + `cancelFade()` + `fadeMs` (the service starts the fade that long
    before the end: 10 s on the phone as before, 3 s on Sonos). The ramp's base volume is noted in renderer.json
    (`volume`) and the next play puts it back if the process died mid-ramp. A pause we or the speaker made is
    `PauseCause.USER` (it ends the timer, as a manual pause does; §5.2), a takeover or unreachable is `OTHER`.
12. **The speaker glyph is drawn**, a stroked cabinet in `Icons` (`roomSpeaker`), not a Material Symbol from
    `scripts/material-symbols.sh`: the bar and the sheet use the mockup's stroked set (a filled 24 px symbol stands
    out there), and the script's music table is the category table that `SourceScanTest` pins to
    `music_icons.json`. No third-party asset, no licence note needed.
13. **3a's trivial startup**: `onCreate` resets a sonos renderer.json to the phone (vibb: no live remote session at
    start -> the renderer reverts; nothing starts), `rendererReady` stays complete at once, and
    `onPlaybackResumption` on Sonos fails for now. `onDestroy` only bookmarks (`saveNow(force)`): the speaker plays on
    after the service goes, until 3b.
14. The SimpleBasePlayer is never `release()`d (`COMMAND_RELEASE` isn't offered); `SonosRenderer.release()` stops the
    poller and the actor and drops the posted callbacks.

Owed checks: emulator §6g (now listing 3a's: the sheet, switch phone -> Stua -> Kjøkken -> phone with one Stop per
room left, same tile, grouped-away, taken-over and migration scripts, the ramp, the volume keys, an own-files
entry, a failed start; the image needs the emulated Wi-Fi) and §6f (the local sleep fade, now in `LocalRenderer`);
the device checks above stand.

For step 3b:
- §4.7: adopt and the startup gate (`rendererReady` after one classify; drop `onCreate`'s reset), resumption on Sonos
  starting the speaker (gated), the queue rebuilt with the filter and the index by uri equality.
- §6.1 the suspension receiver and §6.2 `onDestroy`/`onTaskRemoved`'s full stop: `SonosRenderer.handOff()` (live
  classify, bookmark, ours-gated Stop) is the building block; add the `x-rincon-queue:` park and the disarm.
- §6.3 the wake lock and WifiLock: `SonosRenderer(onArmedPlaying = ...)` (from `SonosHost.armedPlaying`, true while a
  session plays or a step/seek is in flight) is the seam; the service doesn't pass it yet.
- §5.3 the cap: clamp in `SonosController.setVolume` to the cap in force (`adjustVolume` already builds on our own
  last set), with the launcher's restriction and the contract.
- §6.7 calls; the remaining `SonosRendererTest` rows (startup gate, adopt, suspension with and without the service,
  wake locks, calls, the cap).

Open questions: (a) steps on Sonos start at 0 (vibb, §4.5) while the phone's next/previous resume an item's own
place - keep vibb's rule, or pass the phone's `startPos` through? (b) picking the room already playing re-transfers
in vibb; here it is the same-tile rule. (c) Is Media3's 10-minute engaged timeout enough for §3.2's FGS rule, or should
3b still override `onUpdateNotification` while armed?

### Fix round after QA's code review (qa-26a-step3a-code, 2026-10-11)

QA (`docs/design/qa-26a-step3a-code.md`: 2 High, 3 Medium, 6 Low) built 298/0 with clean lint; every finding is
fixed in one commit, none skipped. Build: `assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` green, **317 tests, 0 failures** (+19), lint 0 errors and the same 41 warnings; the fake's
selftest OK.
- **H1** a skip on a paused (or stopped, stale, not-yet-started) speaker, or while `playAllowed` says no, only moves
  where the next play starts (`startPending`: the index and the service's start position); the next play starts
  that item there. Every play the controller sends asks `mayPlay` first (`SonosRenderer` passes
  `playAllowed(suspendedNow())`): a start, a resume, a step, a natural advance (which then waits at the next item).
- **H2** a play generation (`gen`), bumped by a load, a clear, a step and every play: a result of an older generation
  only records what the speaker now holds (`playing`, `ownsSpeaker`), never the index, `pending`, the session, `Lost`
  or renderer.json. `load` keeps what the speaker holds (re-indexed in the new queue, or "another entry"). A start
  that fails while our stream plays on this uid is refused like a failed step - "Kan ikke spilles på Sonos", the index
  back, the renderer stays; only a first start is `Lost`, and `lost()` hands off (an ours-gated Stop) before coming
  home.
- **M1** `SonosActor.close()` drains the queued jobs (30 s at most) before cancelling; a hand-off past `HANDOFF_MS`
  (now 15 s, sized from the budgets queued ahead of it) calls `abandon()`: the bookmark from the last poll and an
  ours-gated Stop queued behind the busy actor.
- **M2** one `Mutex` (`switching`) around the command coroutine's dispatch, playback resumption and `lost()`;
  `toRoom`/`comeHome` also bail out when `renderer` changed under them. The switching decisions are pure
  (`storedPlan`, `homePlan` in `logic/Queue.kt`, tested).
- **M3** a cancelled ramp never calls `done` (a timer set during the ramp survives it); the phone's never did.
- **L1** renderer.json keeps a cut ramp's `volume` with its room (`volume_uid`) across a switch home and the restart
  reset (`RendererChoice.local`); a room restores only its own.
- **L2** a refused same-tile resume falls through to the full start. **L3** a play pressed after a pause while a play
  is in flight queues a resume (the last intent wins). **L4** the bookmark's age term is capped at 5 s (the §9 row
  above). **L5** the hand-off's `wasPlaying` reads the effective transport (our own pause counts).
- **L6** tests: TRANSITIONING -> BUFFERING; a disarm -> IDLE and the next play from the bookmark; the player's
  playlist preconditions as a pure `playlistPlan` (unique uids, the index, an empty playlist only IDLE/ENDED), used by
  `getState`; `FitTest` measures the room labels (142.5 and 184.7 dp) and fits the four state lines (en and nb,
  Nunito Bold 12 sp) in the header at 320/360/411 dp; H1, H2 (a/b/c), M1 (the abandon, the actor's drain), M3, L1-L5
  each have a test; `GuardsTest` pins the paused-skip branch, `mayPlay` before any send, the switch lock and the
  abandon/drain.

QA's view of the 14 choices: accepted, except #8 for a START (now H2) and #13's side effects (L1 fixed; `onDestroy`
reporting paused while the speaker plays on stays with 3b's §6.2).


## Step 3b implementation status (2026-10-11)

Built on branch `music-1b-sonos` in three code commits, each built and tested on its own with the real Android
build in the sandbox (`7f11626` the lifecycle, `b9f907c` the volume cap, `9bf0487` calls). Music app:
`assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` green at each - **334, 339, then
346 JVM tests, 0 failures** (317 before: +15 `SonosLifecycleTest`, +2 `GuardsTest`; +3 `SonosLifecycleTest`,
+1 `BridgeTest`, +1 `GuardsTest`; +6 `CallPauseTest`, +1 `GuardsTest`), lint 0 errors and the same 40 warnings
(QA's count; "41" here and in the 3a sections counted the report's `<issues>` root tag).
Launcher: `assembleDebug testDebugUnitTest` (the tsnet stub) green at the two commits that touch it - **832, then 839
tests, 0 failures** (830 before: +2 `MusicPlanTest`; +6 `MusicMediaPlanTest`, +1 `MusicSuspensionGuardTest`).
`python3 -I scripts/fake-sonos.py --selftest` OK. Two mutations (the adopt decision without the PLAYING/PAUSED
check, the locks without the verb window) turned 3 lifecycle tests red. **Not run on an emulator or a phone** (no
KVM here): the checks below are owed.

- **Adopt and the startup gate (§4.7)**: `onCreate`'s renderer.json reset is gone. `rendererReady` completes at once
  when renderer.json says the phone; else `adopt()` runs first (under the switch lock): a `SonosRenderer` for the
  stored room, `probe()` - `SonosHome.awaitWifi` (the network callback answers within a moment of the start), then
  in the room's actor `SonosTransport.adoptLive`: vibb's `adopt` bookkeeping (zero transport commands) and one
  classify within `LIVE_BUDGET_MS`, published; `ourLiveSession` (pure: measured just now, the stored room, `normUri`
  equality with the stored built uri, PLAYING or PAUSED) keeps it (the poller runs on) or disarms it. Kept: the
  stored entry's queue rebuilt through `activeFor(..., SONOS)` (the speaker-playable filter), `adoptIndex` (uri
  equality, vibb's `e["url"] == uri`; an NRK series by its program's service uri rebuilt with the stored serial),
  `attach(sonos)`, `SonosController.adopt` (the classify accepted with the duration carry: the listing's with
  `durFromListing` when the speaker reads 0). Not kept: the room released, renderer.json back to the phone, nothing
  starts, the bar paused from `NowPlayingRow`. `rendererReady` completes in a `finally`. Playback resumption on a
  room calls `resumeLast()` (the held queue, else the stored one, on the speaker: `playAllowed` first, the
  controller's `mayPlay` before any SOAP) and fails the future ("no local items"; Media3 1.7.1 then logs and calls
  `Util.handlePlayButtonAction` on the session's player - `KidPlayer`'s gate, then a play already in flight on the
  room: nothing more is sent).
- **The suspension (§6.1)**: `SuspendedReceiver` (`MY_PACKAGE_SUSPENDED` only) `goAsync()`s for 9 s. With the
  service up, `PlayerHub.suspendStop` starts the service's `suspendStop()` in its own scope (after `rendererReady`,
  under the switch lock): on a room `SonosRenderer.park()` = `handOff(park = true)` - the live classify (also of a
  stood-down session: `liveRefresh(armedOnly = false)`), the bookmark at that second, then for our stream PLAYING or
  PAUSED (a play of ours in flight counts) `SonosTransport.park`: Stop, `SetAVTransportURI("x-rincon-queue:<uid>#0",
  "")`, disarmed - then `comeHome(play = false)` (renderer.json local, the phone paused); on the phone the pause as
  before. Without the service, `SonosSuspension.standalone` (a `SonosHome`, a fresh `SonosSession`/`SonosTransport`)
  runs `suspensionStop`: `adoptLive` (kept never), `ourLiveSession`, the bookmark (`suspensionBookmark`: the measured
  second through `SonosBookmarks.decide`, no hold, no age term; written to the position, the bookmark and the stored
  queue's row when it is on that item), `park`; renderer.json back to the phone in every case. `PlayerHub.pause` is
  gone (the receiver was its only reader).
- **The service going (§6.2)**: `onDestroy` on a room skips the immediate save and runs the hand-off (live classify,
  bookmark, ours-gated Stop; `abandon()` past `HANDOFF_MS`) in a scope of its own after the service; the room, the
  household and the writer are released after it. renderer.json is left as it is (a Stop that didn't land leaves
  the next start to adopt; a stopped speaker isn't adopted). A crash still leaves the speaker playing until the next
  start adopts it or a suspension edge stops it.
- **Wake locks (§6.3)**: `SonosLocks` - `PARTIAL_WAKE_LOCK` "vibb:sonos" taken with a 10-minute timeout and renewed
  every 5 minutes while held, and a `WIFI_MODE_FULL_LOW_LATENCY` lock (little use with the screen off: the fix
  round's L5) - follow `SonosController.armedPlaying(spec,
  now)`: a driven session that is not closed AND (the player shows playWhenReady - PLAYING, or held through the first
  failed polls - or a play, step or seek in flight, or our own verb or play less than `VERB_FAST_MS` = 30 s ago). The
  tail's fast window is PLAYING's own. False on PAUSED once the verb window is over, stopped, ended, unreachable past
  the transient, disarmed; the hand-off, `abandon()`, `close()` and `release()` report false at once. `SourceScanTest`
  is untouched and green (no screen wake lock); `WAKE_LOCK` was already in the manifest.
- **The volume cap (§5.3)**: the launcher's `restrictionsFor(..., volumeCapPct)` writes `volume_cap_pct` =
  `volumeCapRestriction(capPctInForce)` (1..99, else 0); `VolumeCap.refresh` (every `AppEnforcer.apply` edge and the
  cached policy at process start) calls `MusicSync.capChanged` when the cap in force changed (and once per process),
  which writes the managed config on IO (`writeRestrictions` synchronized) and nudges. Both `bridge_contract.json`
  copies, `MusicRestrictions`/`RestrictionKeys` and both contract tests changed in the one commit. The music app reads
  `BridgeConfig.volumeCapPct` live; `SonosController.setVolume` sends `volumeTarget(v, cap)`; `adjustVolume` keeps
  3a's base (our own last set, else the last read, else 50) and +-3.
- **Calls (§6.7)**: launcher `MusicCalls` (from `MusicMedia.init`): re-read on every `VoipCalls` pass, every
  `OngoingCalls` change and every audio-mode change; a call = `musicCallActive(VoipCalls.phase,
  OngoingCalls.hasLiveCall, managedCallActive(isInManagedCall, mode))`; `callHoldStep` decides; the music session is
  looked up at each edge and watched (its `MediaController.Callback`) only during a call. `VoipCalls`, `OngoingCalls`
  and `VoipCallPlan` are only used (public listeners, `managedCallActive`), not changed. Music app: no audio focus
  while a room renders (`LocalRenderer.standDown()` on the switch), and the service's `OnModeChangedListener`
  backstop runs `callPauseStep` (the same rule) for MODE_IN_CALL / MODE_RINGTONE while a room renders.

Choices made while building (each against the spec text, with its reason):
1. **Adopt's queue order is the stored queue's when it is the stored entry's** (`NowPlayingRow.keys`, its shuffle
   flag), else the entry's play order: §4.7 says "from the stored entry"; the stored row is that entry's queue as the
   kid left it (a shuffled order included), filtered again.
2. **An adopted stream whose item left the library is not adopted** (no index): vibb adopts with a `None` index,
   which silently disables bookmarks and the advance for the session; here the phone renders and the speaker plays
   on unowned until the kid picks the room (the same tile: a full start from the bookmark).
3. **`onTaskRemoved` has no stop of its own**: Media3 1.7.1's `onTaskRemoved` already pauses every player and stops
   the service unless one plays, so a swipe while paused reaches `onDestroy`'s full stop, and a swipe while a room
   plays leaves it playing - as the phone's own playback after a swipe. §6.2 lists `onTaskRemoved` beside
   `onDestroy`; a Stop there would make a swipe stop the speaker but not the phone.
4. **The destroy-time stop runs after the service, not inside `onDestroy`**: the hand-off's results come back
   through main-thread posts, so `onDestroy` can't block on it; the writer and the household stay open until it is
   done (bounded by `HANDOFF_MS` and the actor's 30 s drain).
5. **The suspension writes renderer.json back to the phone in every case**, a speaker that didn't answer included
   (§6.1's order); with the service up the phone then holds the queue, paused.
6. **The park gate is "ours and PLAYING/PAUSED, or a play of ours in flight"**, and with the service up it also
   reads a session the poller stood down (a speaker paused for ten minutes still replays our episode from its own
   Play button).
7. **A failed bookmark write never stops the Stop** (standalone path); a refused queue uri still leaves the Stop
   done (logged).
8. **The wake lock is timed (10 min, renewed every 5 min while held)**, not held without a timeout: a missed release
   (a bug) can't keep the CPU awake for longer than that; the hook still reports only changes.
9. **An up press at or above the cap sends nothing**, a press down from above the cap (a louder speaker set from the
   Sonos app) lands at most at the cap, and the slider is clamped: §5.3's "every set is clamped" for our own sets,
   while an up press must not pull a speaker down. The sleep ramp's restore and the restart's `restoreVolume` are
   not clamped: they put back a volume the speaker already had.
10. **The cap is re-pushed only when the cap in force changed** (and once per process), not on every `apply()`:
    `writeRestrictions` writes only when the bundle differs anyway; the first push covers a cap changed while the
    launcher's process was dead.
11. **The call rule remembers an unconfirmed pause** (`pauseSent`): right after our pause the session can still read
    PLAYING for a moment, which must not count as the kid's play; the music app's backstop uses the same rule.
12. **"No audio focus while Sonos renders" is a stand-down to IDLE** (`exo.stop()` + clear) on the switch to a room
    instead of 3a's `clearMediaItems()`: Media3 1.7.1's `AudioFocusManager` abandons focus only in IDLE, so a paused
    prepared player kept the focus it had. The next `load` prepares it again.
13. **`SuspendedReceiver` checks its action**: it now does real work (lint's `UnsafeProtectedBroadcastReceiver`).
14. 3a's open question (c): no `onUpdateNotification` override - Media3's 600 s engaged timeout matches vibb's
    disarm; the FGS-timeout check below confirms it.

Owed checks:
- Emulator (§6g, now listing 3b's): adopt after a kill while the fake plays (reads only, the right item, Home's RESUME
  starts nothing twice), a stopped or taken-over fake at start (the phone, paused), `MEDIA_PLAY` on a room with no
  queue, suspension with and without the service (`stop` then `seturi x-rincon-queue:`), the swipe while paused (one
  Stop), the volume keys against the cap and the override, `gsm call` / `gsm cancel`, the locks in `dumpsys power`.
- Device (Jelly Star, real speakers; the list above stands): **Doze 1 h** screen off while a room plays - polls every
  15 s in logcat (`VibbSonos`), the bookmark after a forced stop within 15 s of the truth, `vibb:sonos` held in
  `dumpsys power`, battery per hour, and whether Wi-Fi stays up with the screen off without counting on the Wi-Fi lock (if Doze still
  defers the polls, the launcher grants the battery exemption, §6.3); the **FGS timeout** - a paused room keeps its
  notification for 10 minutes, then Media3 lets the service go and `onDestroy`'s full stop stops the paused speaker
  (box off = music off); a bedtime edge with and without the app (the speaker's own Play button plays nothing); an
  Element X ring and call and a phone call during room and phone playback; adopt after a real process kill.

Open questions: (a) when Media3 ends a service paused for 10 minutes, `onDestroy` stops the paused speaker (vibb's
TERM rule; the speaker kept the episode at the pause) - wanted, or should a paused room only be let go? (b) the 3a
questions (a) and (b) stand. (c) `launcher/CLAUDE.md` names the new restriction only; a line for `MusicCalls` (the
launcher pausing music on calls) belongs with step 4's docs.

### Fix round after QA's code review (qa-26a-step3b-code, 2026-10-11)

QA (`docs/design/qa-26a-step3b-code.md`: 1 High, 1 Medium, 8 Low; music 346/0, launcher 839/0, contracts
byte-identical) asked for a fix round; every finding is fixed, none skipped, H1 by option (a) and M1 fixed (not
documented as a limit), as the main session decided. Two code commits: `2d0bef4` (H1 + L7: launcher, music and both
contract copies in one) and `9161bbd` (M1, L1-L4, L6, L8: music only). Builds: music `assembleDebug assembleRelease
testDebugUnitTest lintDebug -PwarningsAsErrors=true` green at each - **348, then 355 tests, 0 failures**, lint 0 errors
and 40 warnings; launcher `assembleDebug testDebugUnitTest` green - **837 tests, 0 failures** (839 before: the call
cases shrink with the rule); `fake-sonos.py --selftest` OK.
- **H1** a call is the player's own pause: the launcher sends `CALL_HOLD` (a call begins while the music plays) and
  `CALL_RELEASE` (that call is over) as the session's custom actions (names in `bridge_contract.json`); the player
  grants them only to a launcher signed like itself and runs `callPauseStep` itself - the pause under `selfPausing`
  (the sleep timer runs on), the release plays again only what it paused, not after the kid's own play during the
  call, not after the timer ended during the hold (`callTimerEnded`), never while `playAllowed` says no. The launcher's
  `callHoldStep` is HOLD at the start if playing, RELEASE at the end after a HOLD.
- **M1** renderer.json goes back to the phone only on a definite answer (`roomVerdict`: OURS_LIVE / OURS_STOPPED /
  NOT_OURS / NO_ANSWER). No answer keeps the room recorded: the start, every play on the phone (at most every 15 s)
  and a suspension look again. No built uri stored yet: the speaker playing an item of the stored entry is ours.
  Ours but its item gone from the library: one Stop, then the phone. The standalone suspension follows the same rule.
- **L1** the destroy tail clears the renderer's `onEvent` and saves first. **L2** `SonosTail`: the standalone path
  (5 s) and the next adopt wait for a destroyed service's tail, and our uri on a stopped speaker is parked (the
  tail's plain Stop otherwise left the Play button replaying it). **L3** the wake lock is renewed only when an owner
  re-asserted it since the last renewal. **L4** holds are per renderer. **L5** §6.3 and this status corrected: the
  partial wake lock with the FGS's network access keeps the polls; the low-latency Wi-Fi lock does little with the
  screen off. **L6** `place()` / `writePlace()`: the one write behind `persistPlace` and the standalone bookmark,
  pinned. **L7** the call resume goes through the renderer's player's play (the session's entry point). **L8**
  `RendererGate` (the startup gate, a behavioural test), `adoptPlan` (pure) and the verdicts tested; resumption on a
  room stays a source pin plus the controller's start tests.
- QA's deviation verdicts: choices 1, 3, 4, 6, 7, 9-14 accepted; 2 and 5 are M1 (replaced: the item gone stops the
  speaker; a suspension without an answer keeps the room); 8 is L3.

## Step 3c implementation status (2026-10-11)

Built on branch `music-1b-sonos` in one code commit for the contract (`600c9f0`: server, launcher, music app and the
shared files, root CLAUDE.md's rule) and this docs commit, with the real builds in the sandbox:
- **Server**: `cargo test` **508 tests, 0 failures** (505 before: +3 - `the_room_scope_reads_fail_closed`,
  `music_state_keeps_sonos_rooms_checked`, `the_device_card_scopes_sonos_rooms`; `music_library_snapshot`,
  `the_version_ignores_itself_and_follows_every_field` and `music_state_keeps_every_field_the_phone_sends` extended),
  `cargo fmt --check` clean, `cargo clippy --all-targets` the same 13 warnings as before (none in the new code;
  local Rust 1.97).
- **Launcher**: `assembleDebug testDebugUnitTest` (the tsnet stub) **837 tests, 0 failures** (unchanged count:
  `MusicPlanTest`'s `music_state` test extended; its end-to-end test runs the new fixture key through).
- **Music app**: `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **362 tests, 0
  failures** (355 before: +4 `RoomScopeTest`, +1 `ReportTest`, +1 `ContractFilesTest`, +1 `GuardsTest`; three
  existing `GuardsTest` pins follow the new `comeHome` caller, the `SonosRenderer` call and the belt), lint 0 errors
  and the same 40 warnings. `python3 -I scripts/fake-sonos.py --selftest` OK.
- **Migration `0065_music_sonos_rooms.sql`**: numbered above both this branch (0062) and main (0064) - **renumber it
  after the final rebase** if main has moved on (root rule). (Renumbered `0071_music_sonos_rooms.sql` at the merge of
  2026-10-12: main's 0065 is `0065_command_receipts.sql`.)

What is in it:
- **The phone reports the rooms it knows**: `music_state.sonos_rooms` = `[{uid, name}]` from the speaker cache's
  players, with every report (`Sync.writeReport` -> `Report.reportedRooms`: uids `RINCON_[A-Z0-9]{12,20}` only, names
  cleaned as the server cleans them - whitespace collapsed, control characters out, cut to 40 characters, never inside
  one - by name, at most 32; never an ip). The launcher's `musicState` passes the key on; the server's
  `sanitize_music_state` keeps Sonos-shaped uids once each, names cut, at most 32 (one bad row drops that row);
  stored with the rest of `music_state` in `device_status.music_state_json`. `server/testdata/music_state.json` has
  two rooms; `ReportTest`/`BridgeTest`/`MusicPlanTest`/`music_state_keeps_every_field_the_phone_sends` read it.
- **The parent picks** (device page, Music card, `#music-sonos`): before any reported room "The phone hasn't seen any
  speakers yet ... Until then every room is allowed"; then "All rooms" (on = `music_sonos_rooms_json` NULL) and, off,
  one auto-saving tick per reported room. `POST /devices/{id}/music/sonos-rooms` (`all`, repeated `room`) stores
  NULL or the JSON list, nudges the phone, logs `music_sonos_rooms_changed` ("device 3: only Stua, Kjøkken" / "no
  room" / "all rooms") and redirects to `#music-sonos`. The column's trigger moves `music_library_revision` (migration
  0050's rule), so the cached library is rebuilt.
- **The phone obeys**: the library's `sonos_rooms` (null or the uids, after `covers`) is hashed into
  `library_version`; `music_library.json` regenerated (the snapshot phone is scoped to one room) and copied
  byte-identically. `Library.sonosRooms`: absent or null = every room, a list = those (strings, once each). In the
  player service (`sonos/RoomScope.kt`, pure): the sheet's rows go through `allowedRooms` (a group row only when its
  coordinator - the uid the row targets - is allowed); `toRoom` refuses a room outside the scope with
  `CANT_PLAY_ON_SONOS` before anything moves (no hand-off, no save, no renderer.json); a new scope from the sync
  (`Store.libraries()`, distinct) re-publishes the sheet and, once the renderer is settled and under the switch lock,
  `leaveDisallowedRoom()` hands a session on a dropped room off (the live classify, the bookmark at that second, the
  ours-gated Stop) and comes home paused - 3a/3b's `handOff` + `comeHome(play = false)`, as a failed start does.

Choices made while building (each against the spec text, with its reason):
1. **The PWA text is English** ("The phone hasn't seen any speakers yet."): the PWA has no i18n and is English
   throughout (server/CLAUDE.md); the spec's Norwegian line is its meaning.
2. **"All rooms" sends every listed room as a hidden field**: turning the switch off then stores every room the phone
   reported (nothing changes for the kid) and the parent unticks the ones to forbid; without it the switch's own
   auto-save would store `[]` and silence every speaker at once. **No room ticked is `[]`** - no speaker allowed; the
   card says "No room ticked: the music plays only on the phone and headphones".
3. **A room in the stored scope that the phone no longer reports stays listed** ("- not seen by the phone lately",
   ticked), so the parent can still take it out.
4. **The save accepts any Sonos-shaped uid** (at most 32, a non-Sonos value is 400), not only the latest report's:
   a report landing between the page and the click must not drop a room the parent just ticked. The phone only ever
   matches uids it has seen.
5. **Fail closed on both ends**: a stored scope that isn't a JSON list of strings is no room (logged); a library
   `sonos_rooms` that is neither null nor a list is no room. Absent (an older server) is every room, per §5.5.
6. **The leave also runs after an adopt** (renderer.json still naming a room the scope dropped while the app was
   dead): adopted, then handed off and home, paused - nothing starts. It shows `CANT_PLAY_ON_SONOS` ("Kan ikke
   spilles på Sonos") so the kid sees why the music came home.
7. **A belt in the controller**: the room's `mayPlay` also asks the scope (`roomScope`, the current uid - a
   migration follow included), so no play reaches a dropped room in the moment between the sync and the leave.
8. **Only `sonos_rooms` is new in the library document**; `v` stays 1 (an older app ignores the field: it then
   allows every room, which is the server's default too).
9. One route line in `server/src/main.rs` (`/devices/{id}/music/sonos-rooms`) besides the music files, the card
   template, the migration and the tests: the form needs a route.

Owed checks:
- Emulator (§6g, now listing 3c's): the card without and with reported rooms, the scope narrowed while Kjøkken plays
  (the bookmark and one Stop in the fake's log, home paused), `--group Kjøkken+Stua` with Kjøkken unticked (no group
  row).
- Device (the list above): a restricted room refused, a group with a disallowed coordinator hidden, the card with and
  without reported rooms. (Corrected by the fix round below: a group with **any** disallowed member hidden.)

Open questions: (a) a group row whose coordinator is allowed is listed even when a member is not (§5.5 says "by its
coordinator") - playing there sounds in the forbidden room too; should a group need every member allowed? (b) the
leave reuses "Kan ikke spilles på Sonos" - or a line of its own ("Høyttaleren er ikke tillatt")? (c) 3a's (a)/(b)
and 3b's (a) stand.

### Fix round after QA's code review (qa-26a-step3c-code, 2026-10-12)

QA (`docs/design/qa-26a-step3c-code.md`: 1 High, 1 Medium, 8 Low; server 508/0, launcher 837/0, music 362/0,
contracts identical) asked for a fix round; every finding is fixed, none skipped, with the main session's decisions:
(a) a group row needs every member allowed, (b) the leave gets its own line, M1 fixed rather than documented. Commits
`789506e` (server: L2-L5 and L6's server test) and `378926b` (music: H1, M1, L1, L6, L7), then this docs commit (L8 and
§5.5). Builds: server `cargo test` **508/0**, fmt clean, clippy the same 13 warnings; launcher **837/0** (no change);
music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **362/0** (the scope's tests
rewritten in place), lint 0 errors and 40 warnings; the fake's selftest OK; the contract copies identical.
- **H1** `Room.members`; the sheet, the pick, the leave and the controller's belt all check the target's whole group
  (`groupAllowed`; a uid without a row alone); the pick and the leave read the groups from the speaker cache, the belt
  from the sheet's state. An allowed room absorbed into a group with a forbidden one leaves the sheet (§5.5).
- **M1** the leave also runs from the rooms collector (the groups changed) and after a migration follow
  (`SonosRenderer.onFollowed`); while a room plays under a limited scope the groups are read every 60 s
  (`RoomDirectory.refreshQuietly`: one GetZoneGroupState, never a discovery round; nothing when every room is
  allowed). Choice: the topology read on a timer is new (discovery stays "never on a timer"); without it a regroup
  from the Sonos app is unseen until the kid opens the sheet.
- **L1** `ROOM_NOT_ALLOWED`: "Rommet er ikke lenger tillatt" / "This room is no longer allowed", for the leave and
  the refused pick. **L2** the switch's line says later speakers need a tick, and that a group needs every room
  ticked. **L3** "A speaker the phone no longer knows" + the uid small. **L4** refused saves redirect to
  `#music-sonos` with the reason by the rooms (`music_notice=sonos_unknown|sonos_too_many`). **L5** the event detail
  clipped to 300 characters. **L6** the report's name cleaning mirrors `cut_title` in its order (White_Space split,
  Cc out, 40 code points) - not "control characters first", which would differ from the server; one odd input
  asserted on both sides. **L7** the scope is read before the first rows and the startup look. **L8** the tap-vs-sync
  race documented in §5.5.

Owed checks (replacing the 3c list's group line): a group with **any** forbidden member has no row (Kjøkken leading
or Stua leading); a forbidden room grouped into the playing one from the Sonos app stops the session within a minute
("Rommet er ikke lenger tillatt", one Stop, the phone paused); a migration follow onto a forbidden coordinator the
same; the card's notices (`?music_notice=sonos_too_many`) and the "no longer knows" row.

## Step 4 implementation status (2026-10-12)

Docs and the merge, no code change. Built on branch `music-1b-sonos`:
- **Main merged in** (`e539e76`, one merge commit, no rebase): the security wave's QA, design 24b (the override relock),
  design 25's spec. No conflicts; the six files both sides touched (root `CLAUDE.md`, `launcher/CLAUDE.md`,
  `server/CLAUDE.md`, design 26, `server/src/main.rs`'s routes, `device_detail.html`) merged by themselves in separate
  places. Main's migrations end at `0063_window_relock.sql` and `0064_unseen_boots.sql`, so
  **`0065_music_sonos_rooms.sql` keeps its number** (until the next merge: it is `0071` since 2026-10-12). Main
  changed none of the shared contract files (`music_library.json`, `music_state.json`, the bridge AIDL and
  `bridge_contract.json`) and nothing in `music/`.
  After the merge: server `cargo test` **540 tests, 0 failures**, `cargo fmt --check` clean, `cargo clippy
  --all-targets` 12 warnings, none in this branch's or the merge's code (local Rust 1.97); launcher `assembleDebug
  testDebugUnitTest` (the tsnet stub) **900, 0 failures**; music `assembleDebug assembleRelease testDebugUnitTest
  lintDebug -PwarningsAsErrors=true` **362, 0 failures** (`ContractFilesTest`, `BridgeTest`, `MusicPlanTest`: the
  copies still byte-identical); `python3 -I scripts/fake-sonos.py --selftest` OK.
- **Docs**: `music/CLAUDE.md` (the `sonos/` package and the renderer axis in the layout, the SOAP fixtures shared with
  the fake, the rules: `attach`, `saveNow`, `showsPlaying`, the actor, renderer.json and the startup look, every play
  path gated, never a guess in Room, the suspension and destroy paths, the locks, the volume cap, calls, the room
  scope, discovery); `launcher/CLAUDE.md` (`MusicCalls`, `volume_cap_pct` pushed on a change of the cap in force,
  `sonos_rooms` in `music_state`, the suspension on a room); `server/CLAUDE.md` (the fail-closed scope read, the
  report's `sonos_rooms`, the library field); NOTICE.md (what was ported from `sonosd.py`, `renderer.py` and
  `daemon.py`'s controller, and what was written here); design 21's "Sonos is 1b" note points here; design 26's
  status; PLAN.md.

## Correction round after the main session's review (2026-10-12)

The main session reviewed the branch at `9ecdad8` (step 4) and asked for points 1-3, 7 and 8 (4-6 were its own).
Built on `music-1b-sonos`, one commit per point (`c489ad4`, `8c43b0c`, `b4c04c6`, `0187c5d` + `c9f8881`, `6f5aca6`,
the PWA line `122a53c`), then this section.

1. **No socket bound to the Wi-Fi `Network` (the likely blocker) - the premise holds.** Checked, not assumed:
   `L/server/KidVpnService.kt` builds its tun with `addAddress(192.0.2.1, 32)`, `addDnsServer(192.0.2.2)` and
   `addRoute(192.0.2.2, 32)` only, `setBlocking(true)`, no `allowBypass()`, no `addAllowedApplication` /
   `addDisallowedApplication` (so every app of the user is inside it); `AppEnforcer.applyVpnRestrictions` makes it the
   always-on VPN with lockdown **off** (`setAlwaysOnVpnPackage(admin, pkg, false)`) whenever the filter is on.
   Android: `VpnService.Builder.allowBypass()` documents that without it "it is not possible for apps to side-step the
   VPN" and that only with it may apps use `bindProcessToNetwork`/the underlying network; netd enforces it per uid, so
   `Network.bindSocket` (what `Network.getSocketFactory()`'s sockets call) fails with EPERM for an app inside a
   non-bypassable VPN - only the VPN app itself can (`protect()`), and the music app is not the VPN app. So with the
   filter on, every SOAP call (OkHttp on `network.socketFactory`) and every SSDP round (`wifi.bindSocket`) failed: the
   sheet would only ever show the debug-seeded or stale cache and no play could go out - consistent with nothing
   having run on a real phone yet. And unbound sockets work: the tun's table has only 192.0.2.2/32 and, without
   lockdown, a lookup that finds no route there falls through to the default network's table (the launcher's
   documented "everything else flows over the real network untouched", reproduced live there; design 26 review #3).
   Done: `Soap.client()` takes no socket factory, `SsdpScanner.scan()` binds nothing, `SonosHome` keeps Wi-Fi as a
   gate only (the callback; a fresh connection pool per Wi-Fi network) and the multicast lock stays; `SourceScanTest`
   bans `bindSocket(`, `socketFactory(`, `.socketFactory`, `bindProcessToNetwork`, `setProcessDefaultNetwork` and
   `.openConnection(` in `src/main` (no test pinned the binds before). §2.1, §2.2, §6.4, §6.5 rewritten; one limit
   written down there (Wi-Fi up but not the default network - no internet on it while mobile data is up - sends LAN
   traffic the default way, so the speakers read unreachable); the device check "VPN filter on: discovery and control
   still work" stays and now names `EPERM`.
2. **The volume cap at a session's start.** `SonosTransport.capVolume`: with a cap in force (1..99), a start
   (`PlayWhy.START`: a pick, a start, a restart) reads `GetVolume` and, when the speaker is louder, sets the cap - after
   the hush's mute, before `SetAVTransportURI`/`Play`; the controller takes it as `volOpt` (the next key press builds on
   it) and the snapshot shows it. No cap: no read. Steps, advances, polls and the adopt never touch a louder volume
   (vibb's rule within a session); the sleep ramp's leftover volume is put back no louder than the cap. A speaker that
   won't answer `GetVolume` still plays (logged). §5.3, `music/CLAUDE.md`. Tests (`SonosControllerTest`): the order,
   inside the hush, no cap / at the cap, once per session, a failing `GetVolume`, the ramp's leftover; one
   `SonosLifecycleTest` case now sets the louder volume during the session (before, it set it before the start and
   expected it left alone - exactly what the review asked to change).
3. **The bedtime stop on the standalone path.** `suspensionStop` looks again while it got no answer: at most three
   looks, the first 1.5 s, each later one with everything left of `lookMs` (`SonosSuspension.standalone`: until 6.5 s
   after the path started, at least 1.5 s, so the Stop and the park keep ~2.5 s of the receiver's 9 s), 250 ms between;
   a definite answer is never looked at again. The looks read no group and no volume (`classify(io, aux = false)`,
   `adoptLive(..., aux = false)`; the aux sub-cadence is left alone). The service-up path (`handOff(park = true)`)
   already sends its Stop from the last poll when the live read fails, so it is unchanged. Tests
   (`SonosLifecycleTest`): a slow first look retried and the speaker stopped and parked (the budgets 1500 then 4250),
   the looks' calls exactly `getposition, gettransport, stop, seturi`, no answer in the whole budget (two looks, nothing
   sent, the room stays recorded), three refused looks at most, a foreign first answer looked at once; `GuardsTest`'s
   pin follows the loop.
7. **Tests.**
   - **same_tile 3b/4/5/6/7**: one test each, each from vibb's `steady()` (a start, past the 8 s hold, a poll: fresh,
     ours, nothing settling), then one thing broken. Deleting `s.fresh(now)` in `playTile` fails "same tile 5"
     (checked by running the mutation); deleting `!settling` fails 4 (the optimistic resume hold). 3b's "url kind
     without its queue mapping" has no reachable twin here - the controller's index and the speaker's item move
     together - so it is ported as the shown item not being the speaker's (a skip while paused): the press is a full
     start of the shown item, never a resume of the old one.
   - **migrate 1**: the hint carries a uri different from the session's (vibb's signed CDN url); adopt goes out with the
     hint's uri, the bookmark follows it, and `volOpt` (set to 40 before) is reset.
   - **grouped 2**: §3.2's table said "READY, at the last ours position" for grouped away / taken over / lost, §3.3
     and §7 said the position is shown only while ours; the code did the table. vibb hides it (`status`:
     `position = ... if ours else None`, `sonos_grouped.py` check 2). Now a fresh, reachable snapshot of a stream that
     isn't ours shows position 0 with no duration (`hidesPosition`; vibb kept the snapshot's `dur_s`, which for a member
     is the coordinator's - hidden too, a small deviation in vibb's own direction); a stale one keeps the last ours
     values (UNREACHABLE, as before); ours again, the position is back. The table, §3.3 and §7 now agree.
   - **hold_scan 4**: the cached-but-silent case asserts `scanning` true (the view and the state) before the round ends.
   - **`scripts/fake-sonos.py`**: GetPositionInfo answers from the fixture of its situation
     (`position_{playing_ours,paused,transitioning,stopped_retained,stopped_empty,x_rincon}.xml`), GetTransportInfo from
     `transport_<state>.xml`, a fault from its `fault_<code>.xml` (701's with the code when none exists, e.g. 401), the
     topology in `zgs_wrapped.xml`'s shape (its wrapper, each member with the fixture member's attributes). 20 of the
     26 fixtures now answer (the other six - foreign, NOT_IMPLEMENTED, NRK, `zgs_bare`, the two SSDP texts - are
     JVM-only cases). The selftest checks every template and compares the stopped-empty, stopped and paused transport,
     x-rincon and fault replies with their fixtures; `python3 -I scripts/fake-sonos.py --selftest` OK.
8. **Lows.**
   - **Unknown group members**: `SpeakerCache.rooms` kept only named members in a row's `members`, and a group with
     fewer than two named rooms gave its named room a solo row with itself alone - so under a limited scope a room
     grouped with a speaker the phone can't name passed. Every row's `members` is now the whole group (unknown uids
     included; an unknown uid is never in the parent's list, which holds reported rooms only). `RoomScopeTest`,
     `TopologyTest` 4. §5.5.
   - **Tailnet audio**: a `lan` entry's item on 100.64.0.0/10 or fd7a:115c:a1e0::/48 drops out of the speaker-playable
     filter ("tailnet address"; a name is never resolved and passes - the speaker's own DNS fails it). `PlayBodyTest`.
     §4.2.
   - **The "every room" default away from home**: one sentence in the PWA's Sonos card
     (`server/templates/device_detail.html`, both the "hasn't seen any speakers" text and the "All rooms" switch): with
     every room allowed, at a friend's house the phone lists and can play on their speakers too. That is the only place
     parents read about room scoping (no `docs/` page covers it), so the line went there although the round's rules
     said "server untouched": a template text change only (`cargo test` 540 passed, 0 failures, local Rust 1.97).
     §5.5 says it too.

Tests: music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **378 tests, 0
failures**, lint clean (362 at `9ecdad8`); `python3 -I scripts/fake-sonos.py --selftest` OK; launcher not run (no file
its tests read changed); server `cargo test` 540, 0 failures (only the template changed). Contract files and the AIDL
untouched.

Disagreements and limits: none with the review's points. Two notes: (a) 3b of `sonos_same_tile.py` is ported by
meaning, not by state (above); (b) the start cap changes 26a's earlier "a speaker already above the cap is not pulled
down" to "not during a session", which is what the review asked - the user's 2026-10-11 answer ("the keys control the
speaker, with the parent's cap") reads either way, so the stricter one wins for a kid's phone. Device checks added or
sharpened: the VPN line (no `EPERM`), the start cap ("a speaker left louder than the cap starts at the cap").

### Fix round after QA's code review (qa-26a-corrections-code, 2026-10-12)

`docs/design/qa-26a-corrections-code.md`: 0 High, 3 Medium, 5 Low, every one fixed (its "not worth fixing" list
skipped, as the main session decided); all five points and both notes above were accepted.

- **M1, a cap lowered during a session.** The restrictions broadcast (`MusicApp.applyConfig`, on the main thread) calls
  `PlayerHub.capChanged`, which the service points at the room renderer's `SonosController.capChanged`: our own last
  set (`volOpt`) above the cap in force -> one `SetVolume(cap)` through the actor; `volOpt` null (a volume we didn't
  set) or at/below the cap, no session, or no cap -> nothing. §5.3. Tests: keys to 70 under a lifted cap, cap 40 ->
  one `volume 40`, not twice, nothing when the cap lifts; the Sonos app's 80, ours at the cap, a stopped session ->
  nothing.
- **M2, a group row's other rooms.** The start cap goes to every member of the row (`SonosController(members =
  roomMembers(...))`, `SonosTransport.play(body, cap, members)`): each member's own `GetVolume`/`SetVolume`, after the
  coordinator's, inside the same hush, within `MEMBER_CAP_BUDGET_MS` (1.5 s) each; an unknown uid or a silent member is
  logged and skipped. Steps cap no one. §5.3, the device check. Test: four speakers in one shared call log - the
  coordinator and Stua set to 40, Bad at 30 only read, a silent and an unknown member skipped, all between `mute true`
  and `seturi`; a step afterwards reads no volume.
- **M3, the committed bytecode.** `scripts/__pycache__/fake-sonos.cpython-313.pyc` untracked, `__pycache__/` in the root
  `.gitignore` (`36227df`).
- **L1, `volOpt` is what we last set.** A play's job reports the last volume it set (`PlayResult.volume`: the ramp's
  leftover when it was put back after the cap, `restored`), and a play's result takes it only when no key press of
  ours went out after the play was sent (`volSets`); a leftover that failed to go back is kept for the next play (it
  was cleared before). Tests: base 30 / cap 50 / speaker 80 -> `volume 50, seturi, play, volume 30`, `volOpt` 30, the
  next "down" 27; the slider at 20 while the start's cap is in flight -> `volOpt` 20.
- **L2, the bedtime Stop's time.** `suspensionStop(stopMs)`: the park (Stop + the queue uri) runs on one speaker budget
  of what is left until `stopMs` (`SonosSuspension`: 8.5 s after the path started), at least 0.5 s; a Stop that fails
  or runs out is NO_ANSWER (the room stays recorded, the next look parks it) instead of an exception out of the
  receiver. §6.1. Tests: the park's budget after a slow first look (6250 of 8000), the 0.5 s floor after a late
  answer, a failing Stop -> NO_ANSWER with the bookmark written first.
- **L3, stale text.** `Ssdp.kt` (the header, `DatagramSsdpIo`), `Topology.kt`'s `location` doc, `emulator.md` §6g (Wi-Fi
  is a gate, the device check names the VPN and `EPERM`); §7 lists the start cap, the lowered cap, `volOpt`, the
  bedtime looks and park budget, the tailnet filter and the bind ban; §6g gains the start-cap check (the fake's new
  `--volume 90`: `GetVolume`, `SetVolume 40` before `SetAVTransportURI`, both rooms with `--group`) and the lowered-cap
  one.
- **L4.** `tailnetLiteral` and its KDoc moved above `privateLiteral`'s, which is back on its function.
- **L5, the bind ban.** `SourceScanTest` scans every built source set (`src/main`, `src/debug`; asserted present) and
  also bans the bare `bindSocket` (a `Network::bindSocket` reference), `SocketFactory()` and `etSocketFactory`
  (`getSocketFactory()`).

Tests: music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **385 tests, 0
failures**, lint clean (378 before this round); `python3 -I -B scripts/fake-sonos.py --selftest` OK. Launcher and
server untouched in this round.

## Release

`music-v0.2.0-rc.1` is the user's tag, **on the merge commit on `main`** (`music.yml` refuses a tag whose commit isn't
on `main`; root `CLAUDE.md`): versionName `0.2.0-rc.1`, versionCode 1999, a prerelease, never "latest". Design 26's
review (#5) planned `music-v0.1.0` on `main` before 1b so phase 1's device checks test a fixed build; GitHub has no
`music-v*` tag yet (2026-10-12), so if phase 1 is to keep a release of its own, tag it on `main` **before** merging this
branch.

Deploy order:
1. **Server.** 3c's server part (migration 0071, the card, the library's `sonos_rooms`, the report's rooms, the event)
   rides in **0.24.0**: `server/Cargo.toml` says 0.24.0 on both sides and `server-v0.24.0` isn't tagged yet. If it is
   tagged before this merge, bump `server/Cargo.toml` (and `Cargo.lock`) to 0.25.0 on `main` for the next one. Not
   required before the RC: the music app reads the library's `sonos_rooms` leniently (absent = every room), so on an
   older server every room is allowed and the parent just has no card. Required before the parent can limit rooms,
   and the music catalog row (0.21.0+) before any **stable** `music-v*` (design 21 QA #11).
2. **Launcher** (a `launcher-v*` on `main` after the merge), before or with the music RC: the `volume_cap_pct`
   restriction (an older launcher sends none: no cap on a Sonos room), `CALL_HOLD`/`CALL_RELEASE` (none: a VoIP call
   doesn't pause a room; the music app's audio-mode backstop covers only system calls) and `sonos_rooms` in
   `music_state` (an older launcher drops the key: the card keeps saying the phone hasn't seen any speakers). Nothing
   breaks without it; those three are missing.
3. **Music app** `music-v0.2.0-rc.1`, installed through the catalog (the `release` environment's deployment rule must
   allow `music-v*` tags, design 21 §5). The stable `music-v0.2.0` after the emulator and device checks below.

Owed before the stable tag: the emulator checks (`docs/testing/emulator.md` §6g with 3a's, 3b's and 3c's lists, and
§6f's smoke for the local sleep fade) and the device checks (the "Device checks" section, plus 3b's Doze hour, the
FGS timeout, bedtime with and without the app, calls, adopt after a kill, and 3c's fix round's group and regroup
checks). Open questions still standing: 3a (a) steps on a room start at 0 (vibb) while the phone's resume an item's
place; 3a (b) picking the room already playing is the same-tile rule, not vibb's re-transfer; 3b (a) Media3 ending a
service paused for 10 minutes stops the paused speaker (vibb's TERM rule).
