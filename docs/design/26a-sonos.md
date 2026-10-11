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
- **Network binding (QA M7):** SSDP and SOAP use clients bound to the Wi-Fi `Network` (`ConnectivityManager`); without
  Wi-Fi the refresh is skipped and the sheet shows the cache as stale.

### 2.2 SOAP (`Soap.kt`)
- `call(ip, port, service, action, args: List<Pair<String,String>>): Map<String,String>`; POST
  `http://<ip>:<port><controlPath>`, headers `SOAPACTION: "<serviceType>#<action>"`, `Content-Type: text/xml;
  charset="utf-8"`; body the standard envelope with each arg XML-escaped once (`&`, `<`, `>` only, like Python's
  `escape`; SoCo does the same, which is why a DIDL argument carries `&amp;amp;` on the wire - correct,
  `sonosd.py:319-323`). Reply parsed with `javax.xml.parsers` (DOM, namespace-aware; in the JDK and in Android, so
  JVM tests parse for real - `android.jar`'s `XmlPullParser` is a stub under `isReturnDefaultValues`) into the
  response's child elements; a `<s:Fault>` with
  `<errorCode>` -> `UpnpFault(code)`; transport errors -> `SpeakerUnreachable`. Timeouts: connect 2 s, read 5 s;
  one OkHttp client with a connection pool (vibb's keep-alive session, 101-117), bound to Wi-Fi, and **no address
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
  | grouped away / taken over / lost session | READY, at the last **ours** position | false |
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
  **position and duration are shown only while ours** (2615-2621, `sonos_grouped.py` check 2); a stale snapshot shows
  "not playing" but **keeps** the last ours position and duration (never zeroed, 2612-2621).
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
  `publicOnly` (QA M7: the speaker fetches by itself, so the address guard moves here; `lan` feeds on 100.64/10 are
  unreachable from a speaker anyway), excluding `hls` items unless the entry is an NRK series (then `nrk_program`);
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
(QA H6). The speaker already above the cap is **not** pulled down (vibb never fights external changes, 995-998); only
our own sets are clamped.

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
  sheet lists only allowed rooms (a group row only if its coordinator is allowed); `start()` refuses a uid outside the
  list (`Message.CANT_PLAY_ON_SONOS`); a running session on a room that becomes disallowed is stopped at the next
  sync (bookmark first). `music_library.json` snapshot and the music app's copy change together (`ContractFilesTest`).
- A room never seen by the phone can't be restricted: the PWA says so ("Telefonen har ikke sett noen høyttalere ennå").

## 6. Phone-specific rules

1. **Time rules and suspension (QA H4, M5).** The launcher pauses the session, then suspends the app
   (`L/music/MusicSessions.kt:179-190`, design 21 step 4). On Sonos a pause from the session stays a `Pause` (vibb).
   `MY_PACKAGE_SUSPENDED` (`SuspendedReceiver`, `M/MusicApp.kt:64-68`) today posts `PlayerHub.pause`, which is null
   without the service. It becomes: `goAsync()`; if `renderer.json` says sonos, one live classify of the stored uid
   (1.5 s budget, standalone when the service is gone, through the actor when it is up); ours (normUri against the
   stored uri) and PLAYING/PAUSED -> bookmark, `stop`, then `SetAVTransportURI("x-rincon-queue:<uid>#0", "")` so the
   speaker's own Play button plays the (empty) queue, not the episode from 0:00 (after a Stop the speaker keeps its
   AVTransportURI, `sonosd.py:894-896`); disarm; `renderer.json` -> local. `playAllowed(suspended)` gates every Sonos
   play path as it gates the local ones (`GuardsTest`). **Limit, documented:** the household's own Sonos apps and
   grouping from them are out of reach; a kid with the parent's Sonos app is a household matter, not the phone's.
2. **Process death while Sonos plays** leaves the speaker playing until the next start adopts (§4.7) or the next
   suspension edge stops it (1 above, which works without the service). `onDestroy` (`onTaskRemoved` too) does the
   full stop path: live refresh, bookmark, ours-gate, `stop` (vibb `_sonos_on_term`, 5340-5364). A crash does not.
3. **The CPU and Doze (QA H1).** An FGS keeps network access in Doze but not the CPU awake; today's only wake lock is
   ExoPlayer's (`setWakeMode(C.WAKE_MODE_NETWORK)`, 156, 428-431), and a `SimpleBasePlayer` has none. So the Sonos
   renderer holds a `PARTIAL_WAKE_LOCK` and a `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) **while armed and
   PLAYING, and inside the verb and tail fast windows**, released on PAUSED, disarm and the switch home
   (`SourceScanTest` bans only screen wake locks, `SourceScanTest.kt:81-87`). The cadence is vibb's whatever the screen
   does, and a bookmark lands on every poll (§4.4); what idles with the screen off is the UI (`livePosition` polls only
   while a screen is shown, `MusicRoot.kt:166-182`). [device check] 1 h screen off: polls every 15 s in logcat, the
   bookmark within 15 s of the truth, battery per hour. If Doze still defers them, the launcher grants the music app
   the battery-optimisation exemption design 19b gives itself.
4. **Network.** Clients bound to Wi-Fi (§2.1); Wi-Fi gone -> the speaker reads UNREACHABLE (backoff, disarm after
   600 s); the kid's phone away from home keeps the cache (the "cabin case", `sonosd.py:1050-1053`): the sheet lists
   home rooms marked stale and starts no scan without Wi-Fi.
5. **Permissions:** `CHANGE_WIFI_MULTICAST_STATE` (the `MulticastLock`), `ACCESS_WIFI_STATE`, `WAKE_LOCK` (present).
   The launcher's `KidVpnService` routes only `192.0.2.2/32`, so SSDP and LAN SOAP bypass the tun (design 26 review
   #3); DNS is not involved (ips only). Still a [device check] with the VPN up. Android's coming local-network
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
   `MODE_IN_CALL`). So the rule lives in the launcher, the same for local and Sonos playback: `MusicSessions` pauses
   the music session when a VoIP call starts ringing or a system call starts, remembers that it did, and sends play
   when the call ends only then (a kid who paused during the call stays paused; a pause the kid made before the call
   is untouched). The music app holds no audio focus while Sonos renders and keeps `AudioManager`'s
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
  shadowed; switch back bookmarks before exactly one stop; a paused speaker comes home paused; same tile PLAYING
  no-op, PAUSED one resume, untrusted/stale/explicit -> start to our uid; pause freezes the extrapolated position; the
  bm hold keeps 3120 over 39 and releases after 120 s; the regression guard's table (3120->5 kept, 3120->2400 written,
  10800->9000 written, seeked 5 written, 200->5 written, a deliberate restart written); duration carry only for the
  same uri, falsy never erases; playpause STOPPED -> start, PAUSED -> resume, PLAYING -> pause, not ours -> start;
  seek hold TOL/HOLD and the guess never in the snapshot **nor in Room**; migration follow adopts first then writes the
  renderer, no-ops on same uid / other uid / stale / absent, a failed adopt changes nothing; a tick that throws never
  kills the poller; a headset event never touches the renderer; the state table of §3.2 (one failed poll stays
  BUFFERING, never IDLE); `availableCommands`; volume adjust from our own base and clamped to the cap, an external
  higher volume left alone; the startup gate (RESUME before `rendererReady` waits; resumption on Sonos starts the
  speaker, not Exo); suspension -> bookmark, stop, queue uri, disarm, also with no service.
- `PlayBodyTest`: art only when http, NRK series -> `nrk_program` with `programId = item.key` and the slug from the
  target, non-series `hls` filtered, a private-literal host of a `publicOnly` entry filtered, listing duration rides
  along.
- Existing: `GuardsTest` pins updated (OUTPUT-to-Sonos in `PLAYS`, the receiver's path), `ReportTest` unchanged,
  `FitTest` + the long labels, `SourceScanTest` (no `Uri.parse("http`, no screen wake lock), `ContractFilesTest`
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
- Volume keys: ±3 per press, the slider, the cap holds, a louder speaker set from the Sonos app is left alone.
- An incoming call during Sonos playback (per the user's answer to §10 item 6).
- VPN filter on: discovery and control still work.

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
