# 26a - Vibb music phase 1b: Sonos (buildable spec)

Architect draft (2026-10-11), the first step of design 26's phase 1b. Inputs: design 26 (the brief and its review),
design 20 "Outputs", design 21 (§4.6, QA #12, the user's answer on own files), `music/CLAUDE.md`, and vibb. **vibb's
live line is the `pipewire` branch** (`1fcadac`, 2026-09-21; user 2026-10-11), not GitHub's default `main` (`22bdc38`,
2026-09-03, its direct ancestor). Line numbers below cite `22bdc38`, where the Sonos code is identical except for the
one `pipewire` change noted in §2.1 (`sonosd.py` `rescan_in_background`, `tests/sonos_hold_scan.py`; `daemon.py`'s
`pipewire` diff is Soloist/PipeWire work and only adds log lines to the Sonos paths; `storytel.py` is unchanged).
Sources: `pi/sonosd.py` (the sidecar), `pi/vibb/renderer.py`, the `_sonos_*` controller in `pi/daemon.py` (lines
350-420, 1083-2126, 2599-2653, 4254-4530, 5340-5364), and `tests/sonos_{contract,renderer,groups,grouped,migrate,
migrate_probe,same_tile,hush,hold_scan}.py` and `storytel_sonos.py`. **This is a port**: every behaviour below names its vibb source, and a
deviation is listed in §9 with its reason. `M/` = `music/app/src/main/java/me/vibb/music/`, `L/` =
`launcher/app/src/main/java/com/kidslauncher/mdm/`. [device check] = needs the Jelly Star and a real speaker.

**In:** Sonos rooms (and groups, by their coordinator) in "Spill av på"; the phone as UPnP controller; content kinds
`url` (RSS enclosures, NRK podcast mp3) and `nrk_program` (NRK series through the NRK Radio Sonos service); the
uid -> ip cache; stall, takeover, grouped-away, lost-session and migration handling; bookmarks with vibb's holds;
adopt after a restart; the launcher's media surfaces unchanged. **Out:** own files on Sonos (design 21 decision),
Storytel on Sonos (phase 2, `storytel_sonos.py`), `spotify_sharelink` and the speaker-owned queue (phase 3,
`sonos_spotify_queue.py`), group management (the Pi never did it), server changes beyond §5.

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

## 1. Behaviour map (vibb -> Kotlin)

| vibb | Kotlin home | Notes |
|---|---|---|
| `soco.discover`, `rescan()` (`sonosd.py:142-161`) | `M/sonos/Ssdp.kt` + `Topology.kt` | SSDP finds one speaker; one GetZoneGroupState gives the household (§2.1) |
| `refresh_topology()`, `players()`, `players_payload()` (164-243) | `M/sonos/Topology.kt`, `SpeakerCache.kt` | merge players, replace groups wholesale, coordinator first, invisible skipped |
| `_speaker(uid)` (246-264) | `SpeakerCache.resolve(uid)` + `Soap.uidOf(ip)` | uid mismatch -> `UnknownUid` (a failed play, then rescan) |
| `_hms`, `_hms_to_s`, `MIME`, `_norm_uri`, `_mime_for` (275-313) | `M/sonos/SonosUris.kt` | pure; `"0:00:00"` -> 0.0, garbage -> null |
| `didl()` (316-349), `DIDL_NS` (269-272) | `M/sonos/Didl.kt` | pure; escape once here, once in the SOAP layer |
| `_nrk_serial`, `nrk_program_uri`, `nrk_desc` (354-389) | `M/sonos/NrkService.kt` | serial from `/status/accounts`, fallback `"14"`, cached per ip |
| `Session` state, `publish`, `state` (392-441) | `M/sonos/SonosSession.kt` (pure) | `seq`, `stale_s` from elapsed realtime |
| `play`, `_hush`, `_seek_settled`, `adopt`, `verb` (448-653) | `M/sonos/SonosTransport.kt` | the SOAP sequences, under one mutex |
| `_classify`, `poll_loop`, `_cadence`, `_stall_bookkeeping`, migration (658-984) | `SonosSession.kt` (pure) + `SonosPoller.kt` | the machine is pure and tested with canned polls; the poller is a coroutine |
| `SoCo` SOAP calls | `M/sonos/Soap.kt` | §2.2 |
| `renderer.py` `read/write` | `M/sonos/RendererState.kt` | `noBackupFilesDir/renderer.json` |
| daemon `_sonos_*` fields (350-420), `_sonos_position`, `_sonos_fresh` (1083-1162) | `SonosRenderer.kt` | §4 |
| `_sonos_bookmark_now` (1177-1268) | `SonosRenderer.bookmarkNow()` + `M/sonos/SonosBookmarks.kt` (pure) | the holds and the regression guard, §4.4 |
| `_sonos_body` (1270-1322) | `M/sonos/PlayBody.kt` (pure) | from a `PlayItem` + entry, §2.5 |
| `_sonos_play_entry`, `sonos_start_target` (1324-1417, 1511-1566) | `SonosRenderer.start()` / `playIndex()` | the speaker-playable filter, §4.2 |
| `sonos_step`, `_sonos_step_worker` (1568-1623) | `SonosRenderer.step()` | coalesced, latest wins |
| seek + `_sonos_seek_worker` (1945-2064) | `SonosRenderer.seek()` | coalesced, 0.7 s spacing, optimistic position |
| `_renderer_to_sonos`, `_renderer_to_box` (1625-1708) | `PlaybackService.switchRenderer()` | §4.6 |
| `_sonos_poller` adopt-on-start (4254-4310) | `PlaybackService.onCreate` -> `SonosRenderer.adopt()` | §4.7 |
| poller tick: settle, duration carry, opt confirm, ends_near (4310-4530) | `SonosRenderer.onPoll()` | §4.3, §4.5 |
| `_sonos_on_term` (5340-5364) | `onDestroy` + suspension (§6) | bookmark, ours-gate, Stop |
| status (2599-2653) | `SimpleBasePlayer.State` + `PlayerHub.State.rendererState` | §3.3 |
| `sonos_contract.py` KINDS/TRANSPORTS/shapes | `music/app/src/test/resources/sonos_contract.json` + `SonosContractTest` | one fixture for every test (§7) |

## 2. The `sonos/` package (Android-free except where said)

### 2.1 Discovery and topology (`Ssdp.kt`, `Topology.kt`, `SpeakerCache.kt`)
- **SSDP** (`rescan()`, `sonosd.py:142-161`): UDP M-SEARCH to 239.255.255.250:1900, `ST: urn:schemas-upnp-org:device:
  ZonePlayer:1`, `MX: 1`, collect replies for 3 s; each reply's `LOCATION` header gives an ip (`http://<ip>:1400/xml/
  device_description.xml`). Holding a `WifiManager.MulticastLock` for the scan (`CHANGE_WIFI_MULTICAST_STATE`, §6.5),
  released in `finally`. Android-side wrapper `SsdpScanner` over a pure `Ssdp.parseReply(text): String?`.
- **Topology** (`refresh_topology()`, 164-222): `ZoneGroupTopology.GetZoneGroupState` against cached ips in sorted
  uid order until one answers (§2.2); the `ZoneGroupState` XML gives every `ZoneGroup Coordinator="<uid>"` with its
  `ZoneGroupMember UUID Location ZoneName Invisible`. Pure `Topology.parse(xml): Household` = players `{uid -> ip
  (from Location), name}` and groups `{coordinator, members}`. Rules as vibb: `Invisible="1"` members skipped
  (152-153, 205-206); a group whose coordinator is not a visible member is skipped; members coordinator-first (217).
- **Rescan = SSDP for one ip, then topology from it.** SoCo's `discover` reads the zone group state under the hood;
  the phone does it in the open: SSDP alone learns neither uids nor groups reliably.
- **Cache** (`_load_cache`/`_save_cache`, 120-139): `noBackupFilesDir/sonos.json`, `{players:{uid:{ip,name,seen_at}},
  groups:[{coordinator,members}], fetched_at, topology_at}`, written tmp + rename. Players **merge** (a scan that misses
  a speaker never deletes the row the kid aimed at, 143-146), groups **replace** (176-179). Garbage -> empty.
- **When discovery runs** (23-26): a cache miss, the picker's "Se etter høyttalere" row, or a failed play. Never on a
  timer. Opening the picker runs one topology refresh (~200 ms on a LAN); nobody answering serves the cache marked
  `stale` and the sheet says so (1047-1057, §5), **and starts one SSDP round in the background** (`pipewire`
  `rescan_in_background`/`scanning`, `sonosd.py:164-190, 1088-1097`; owner 2026-09-05: "the list fills itself"): the
  sheet opens at once with the old rows, shows "Søker etter høyttalere…", a second open while the round runs starts
  nothing, and the rows refresh when it ends. One round in flight at a time.
- **Picker rows** (`players_payload`, 230-243; ui.py:2614-2640 via `sonos_groups.py:120-136`): players sorted by
  name; a group with 2+ members is one row named `"<coordinator> + <member>"`, selecting it targets the coordinator;
  absorbed members don't repeat; a group naming an unknown uid gives no row. Never an ip in the UI.
- `resolve(uid)` (`_speaker`, 246-264): the cached ip; `Soap.uidOf(ip)` (DeviceProperties or the `device_description`
  UDN) compared on the **play path only**; mismatch -> `UnknownUid`; unreachable -> let the poller classify.

### 2.2 SOAP (`Soap.kt`)
- `call(ip, service, action, args: List<Pair<String,String>>): Map<String,String>`; POST
  `http://<ip>:1400<controlPath>`, headers `SOAPACTION: "<serviceType>#<action>"`, `Content-Type: text/xml;
  charset="utf-8"`; body the standard envelope with each arg XML-escaped once (SoCo does the same, which is why a
  DIDL argument carries `&amp;amp;` on the wire - correct, `sonosd.py:319-323`). Reply parsed with `XmlPullParser`
  into the response's child elements; a `<s:Fault>` with `<errorCode>` -> `UpnpFault(code)`; transport errors ->
  `SpeakerUnreachable`. Timeouts: connect 2 s, read 5 s; one OkHttp client with a connection pool (vibb's keep-alive
  session, 101-117) and **no address guard**: this is control traffic to the LAN (21b §2.7 is about audio origins;
  design 26 review #3).
- Services: AVTransport (`/MediaRenderer/AVTransport/Control`, `urn:schemas-upnp-org:service:AVTransport:1`):
  `SetAVTransportURI(InstanceID=0, CurrentURI, CurrentURIMetaData)`, `Play(0, Speed=1)`, `Pause(0)`, `Stop(0)`,
  `Seek(0, Unit=REL_TIME, Target=H:MM:SS)`, `GetPositionInfo(0)` -> `Track, TrackURI, RelTime, TrackDuration,
  TrackMetaData`, `GetTransportInfo(0)` -> `CurrentTransportState`, `RemoveAllTracksFromQueue(0)` (the cosmetic clear
  before a url play, 464-469, errors ignored). RenderingControl (`/MediaRenderer/RenderingControl/Control`):
  `GetVolume/SetVolume(0, Channel=Master, DesiredVolume)`, `GetMute/SetMute(0, Master, DesiredMute)`.
  ZoneGroupTopology (`/ZoneGroupTopology/Control`): `GetZoneGroupState()`. Phase 3 adds `AddURIToQueue`,
  `SetPlayMode`, `Seek(TRACK_NR)` (496-512).
- `SourceScanTest` bans `Uri.parse("http` literals: build every URL with `HttpUrl.Builder` from the ip.

### 2.3 DIDL, URIs, NRK (`Didl.kt`, `SonosUris.kt`, `NrkService.kt`)
- `didl(uri, title, artist?, album?, art?, durationS?, upnpClass, protocolInfo?, desc?)` exactly as `sonosd.py:316-349`:
  `<item id="vibb-1" parentID="-1" restricted="true">`, `dc:title` (default "Vibb"), `dc:creator` **and** `upnp:artist`
  when an artist is given, `upnp:album`, `upnp:albumArtURI` (http only - Sonos resolves no `.local` and can't reach the
  phone), `upnp:class`, the `<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">` for service
  items, `<res protocolInfo=… duration="H:MM:SS">uri</res>`. Default protocolInfo `http-get:*:<mime>:*`, mime by
  extension from vibb's table, fallback `audio/mpeg` (288-313).
- `normUri(u)` (293-305): percent-decode, then strip one of `x-rincon-mp3radio://`, `aac://`, `https://`, `http://`.
  Ours-detection and the migration probe compare **only** through it.
- `hms(sec)` / `hmsToS(s)` (275-285): `H:MM:SS`; `"0:00:00"` -> 0.0 (the duration-carry rule depends on 0 being
  falsy, daemon.py:4398-4406), garbage -> null.
- NRK Radio service (354-389): `sid = 277`, `svcType = 277*256+7 = 70919`; the household serial from
  `GET http://<ip>:1400/status/accounts` (`<Account Type="70919" SerialNum=…>`), cached per ip in memory, fallback
  `"14"` with a log line; uri `x-sonos-http:series%3a<urlencode(series)>%3a1%3a<programId>.unknown?sid=277&flags=0&
  sn=<serial>`; class `object.item.audioItem.show`, protocolInfo `sonos.com-http:*:audio/mpeg:*`, desc
  `SA_RINCON70919_X_#Svc70919-0-Token`. **Precondition:** the household's Sonos has the NRK Radio service linked. It
  does: vibb's fallback `sn=14` is "the owner's known-working household value" (`sonosd.py:377`). Only NRK *series*
  need it (HLS, `content.py:8-13, 193`); NRK podcasts and RSS are plain mp3 urls the speaker fetches itself.

### 2.4 The session machine (`SonosSession.kt`, pure)
State as `Session.__init__` (392-421): `armed, uid, kind, uri, seq, lastOkAt, frozen, lastPos, retriedAt, aux{volume,
groupedAway, coordinator}, auxN, fastAt, oursAt, migrateTries, moved, lastTr, didlChecked`. Time is an injected
`elapsedMs()` (never wall clock: vibb's `monotonic`, 437-441). Transport reads come through a `SpeakerIo` interface
(`positionInfo()`, `transportInfo()`, `group()`, `volume()`), so tests feed canned polls.
- `classify(io): Fields` (658-780): one GetPositionInfo + one GetTransportInfo; `TRANSITIONING` reports the last real
  state, or PLAYING at a fresh start (665-671); `ours = normUri(trackUri) == normUri(session.uri)`; `foreignUri` when
  not ours; `x-rincon:<uid>` -> `groupedAway=true, coordinator=<uid>` on the same poll, a non-empty other uri clears,
  an empty uri decides nothing (690-695); aux (group via topology-of-this-speaker, volume) every `AUX_EVERY=12` polls
  and at session start, kept on failure (704-718); `lostSession = STOPPED && trackUri.isEmpty()` (720-721); the
  one-time DIDL accepted/rejected log (746-754); ours+live re-arms `migrateTries`, clears `moved`, stamps `oursAt`
  (771-779). Fields = the `/state` snapshot's (contract §3): `armed, uid, kind, seq, staleS, reachable, transport,
  relS, durS, uri, ours, foreignUri, groupedAway, coordinator, lostSession, volume, retriedAt, streamMoved?, error?`.
- `cadence(tr, snap, fails)` (832-858): PAUSED 15 s; UNREACHABLE `min(5*2^min(fails,6), 300)`; STOPPED 5 s only while
  a migration transition pends (empty uri, no hint, tries left, within 90 s of `oursAt`), else 60 s; PLAYING 5 s when
  `dur-rel <= 45` or within 30 s of the last verb/arm, else cruise 15 s. The same with the screen off (user,
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
- `publish()` increments `seq`; `staleS = (elapsedMs - lastOkAt)/1000`, null before the first success.

### 2.5 Transport sequences (`SonosTransport.kt`) and the play body (`PlayBody.kt`)
All under one `Mutex` (the session lock, 393-394), never on the main thread.
- `play(body)` (448-536): `uid, kind, uri` set, migration state cleared; `hush(startS)`; `url`: `RemoveAllTracksFromQueue`
  (ignored errors), `SetAVTransportURI(uri, didl)`, `Play`; `nrk_program`: the service uri and DIDL (§2.3), `SetAVTransportURI`,
  `Play`; then arm, reset stall/aux, `fastAt = now`, `seekSettled(startS)` when `startS >= 5`; `finally unhush()`;
  wake the poller. Result `{sought}`.
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
  (`entry.nrkSerie != null`) with `item.nrkProgramId` -> `nrk_program{uri = entry.target, series, programId}`; else
  `url{uri = item.url}`; `durationS` from the listing when the item has one (the speaker reports 0:00 for an
  extension-less url until buffered, `daemon.py:1306-1313`). Storytel's branch arrives in phase 2.

## 3. The renderer axis in `playback/`

### 3.1 `Renderer`
```kotlin
interface Renderer {
    val player: Player                     // what the session wraps (KidPlayer(renderer.player))
    val kind: RendererKind                 // LOCAL, SONOS
    fun load(plan: PlayPlan, items: List<MediaItem>, startIndex: Int, startMs: Long, playNow: Boolean)
    fun positionMs(): Long; fun durationMs(): Long?; fun currentIndex(): Int; fun itemCount(): Int
    fun play(); fun pause(); fun seekTo(index: Int, ms: Long); fun clear()
    fun setVolumeFraction(f: Float)        // sleep fade; Sonos: no-op (§9)
    val events: Flow<RendererEvent>        // ItemChanged(index, auto), Ended, Error(code), StateChanged
}
```
`LocalRenderer(exo)` is a straight extraction of today's `exo.` reads and writes (the inventory's list:
`PlaybackService.kt:168-882`): `currentPosition/duration/currentMediaItemIndex/mediaItemCount`, `setMediaItems/
prepare/seekTo/play/pause/clearMediaItems`, `volume`, `setAudioAttributes/setWakeMode/setPreferredAudioDevice`, and the
`Player.Listener` callbacks (619-733) re-emitted as `RendererEvent`s. Behaviour unchanged; **step 2 is this refactor
alone**, with all existing tests green.

### 3.2 `SonosRenderer : SimpleBasePlayer`
- `getState()` from the last snapshot + the queue: playlist = the plan's playable items as `MediaItemData` (uid =
  `"<entryId>/<itemKey>"`, the same `MediaMetadata` the local path builds: title, artist = album = entry name,
  `artworkUri` the <= 300 px file, duration from the listing); `currentMediaItemIndex`, `playbackState` (READY when
  armed and reachable and PLAYING/PAUSED, BUFFERING for a pending verb, ENDED after the queue's end, IDLE otherwise),
  `playWhenReady` from the (optimistic) transport, `contentPositionMs` as a `PositionSupplier` anchored at the poll's
  elapsed time (so the launcher's `positionNow` extrapolation, `L/music/MusicMediaPlan.kt:57-65`, is right), `deviceInfo
  = DeviceInfo(PLAYBACK_TYPE_REMOTE, 0, 100)` and `deviceVolume` from the aux (§5.3), `availableCommands` = play/pause,
  seek, next/previous, set device volume.
- Handlers: `handleSetPlayWhenReady` -> §4.5 playpause; `handleSeek` -> §4.5 seek or step (via `KidPlayer`'s skip
  routing, which calls `seekToNext/Previous` on the delegate: implement as `COMMAND_SEEK_TO_NEXT/PREVIOUS_MEDIA_ITEM`
  handlers); `handleStop` -> bookmark then Stop; `handleSetDeviceVolume` -> `verb volume`; `handleRelease` no-op.
- Not applicable on Sonos, so no-ops: `setAudioAttributes`, `setWakeMode`, `setPreferredAudioDevice`, the sleep fade.

### 3.3 `PlayerHub` and the UI model
- `Output` widens: `data class Output(val key: String, val kind: OutputKind, val name: String?)` with keys `"phone"`,
  `"dev:<AudioDeviceInfo.id>"`, `"sonos:<uid>"`; `OutputKind` gains `SONOS`; `Commands.ARG_OUTPUT` becomes a String
  (app-internal, not in the AIDL). `PlayerHub.State` gains `rendererState: RendererState?` (`null`, `GROUPED_AWAY`,
  `TAKEN_OVER`, `LOST_SESSION`, `UNREACHABLE`; ordered as `daemon.py:2627-2632`) and `sonosStale: Boolean`.
- `publish()` reads through `renderer` (`live`, `index`, `playing`, `durationMs`); `playing` on Sonos = fresh snapshot
  and PLAYING and reachable and ours (2605-2611); a stale snapshot shows "not playing" but **keeps** position and
  duration (never zeroed, 2612-2621).
- `PlayerHub.position` and `PlayerHub.pause` lambdas point at the current renderer. `reportNowPlaying`
  (`setNowPlaying` over the bridge, 845-868) and `saveTick` keep their triggers; `GuardsTest`'s pinned strings
  (`GuardsTest.kt:26-55`) are updated with the refactor, keeping their intent (every play path gated, `saveTick` saves
  and reports).

## 4. Controller logic (`SonosRenderer`, from `daemon.py`)

### 4.1 Persisted renderer state (`RendererState.kt`, `renderer.py:23-49`)
`noBackupFilesDir/renderer.json`: `{renderer: "local"|"sonos", uid, name, entryId, itemKey, uri}`; anything not
sonos-with-uid reads as local. Written before the first SOAP of a switch (order as 1625-1667), cleared on the way home.

### 4.2 Starting an entry on the speaker (`sonos_start_target`, 1511-1566; `_sonos_play_entry`, 1324-1417)
- The plan is the same `coverTapPlan`/`pickPlan` the local path uses (`M/logic/Queue.kt`), then the **speaker-playable
  filter** (1535-1538): keep items with an http(s) `url`, or NRK-series items with an `nrkProgramId`; own files
  (`fileId`), withdrawn items (`url == null`) and NRK series items without a program id drop out with a log line. Nothing
  left -> `Message.CANT_PLAY_ON_SONOS` and the renderer stays where it was (`nothing-remote-playable`).
- Index and position: an explicit item -> its index and its saved position; else the bookmark (`rotateToBookmark`, as
  today). `startedPlaying` (Nytt removal) as on the phone.
- `playIndex(idx, startS)`: build the body **once** (1367-1371), `transport.play`; on success `index = idx`, `pending =
  (itemKey, now)`, `optTr = (PLAYING, now)`, seed the snapshot `{PLAYING, relS = startS, durS = item duration with
  durFromListing = true, uri = body.uri, ours = true, reachable = true, seq = -1, staleS = 0}`; when `startS >= 5` and
  not `sought` -> `bmHold = (itemKey, startS, now)` (1395-1405). A fault -> `Message.CANT_PLAY` and a rescan is offered.

### 4.3 The poll tick (`_sonos_poller`, 4310-4530)
In order, whole tick guarded (4523-4530): take the snapshot; **duration carry** - `durS` falsy -> the listing's duration
with `durFromListing`, else carry the previous value only for the same uri (4398-4406); **optimistic transport** `optTr`
confirmed or expired after 8 s, else it overrides the snapshot's transport (4408-4420); **optimistic position** `optPos`
cleared when `|rel - target| <= 6`, after 12 s, or when the uri changed (4421-4432); **migration follow** (§4.8) runs
before the ours-gate; then the gate `staleS < 12 && ours` (4465-4468); **index** by `normUri` equality on the queue
(url kind); **bookmark** (§4.4, throttled); **ends_near**: count PLAYING/PAUSED polls with `rel > dur - 20`; url kind +
STOPPED + ends_near -> the next item at 0, or at the queue's end `transport.stop` and the local `queueEnded()` path
(`markDone`, clear partial positions, now-playing cleared; `PlaybackService.kt:758-772`) (4499-4520). Position for the
UI and the session (`_sonos_position`, 1137-1162): `optPos` wins while <= 12 s old and same uri; null unless PLAYING/
PAUSED; PLAYING and poll age < 60 s -> `rel + age + staleS`, clamped to `dur`.

### 4.4 Bookmarks (`_sonos_bookmark_now`, 1177-1268; pure in `SonosBookmarks.kt`)
Needs an index, `ours`, a **measured** `relS` (PLAYING adds `staleS`), not STOPPED. Written on **every poll** (user,
2026-10-11: as fine-grained as possible; vibb's 25 s throttle was SD hygiene), so every 15 s at cruise and every 5 s
near the end or after a verb, and forced before any Stop or switch. Rules, in order: the refused-seek **hold** `(itemKey, minPos,
at)` keeps a lower position from being written, bounded by `BM_HOLD_MAX_S = 120` of real listening (1216-1231); the
**regression guard**: `rel < BM_RESTART_FLOOR_S (90)` while the saved position is `> BM_REGRESS_MIN_S (300)` and no
user seek within `BM_USER_GRACE_S (180)` -> keep the bookmark ("the speaker restarted the track", 1232-1258); then
`Bookmarks.save(itemKey, rel, duration = speaker-measured only, null when durFromListing)` (1259-1268: a listing length
short by half a minute must never mark a ten-hour book finished). **Always before any Stop or renderer change**: after a
Stop the transport reads 0:00 (1178-1180). Written also by `saveTick` and on `onDestroy`.

### 4.5 Verbs (`_sonos_command`, 2066-2126; `pause`/`unpause`/`stop`, 2128-2231)
- **playpause**: not (ours and PLAYING/PAUSED) -> `start(entry)` again (a STOPPED speaker resumes from the bookmark, not
  from 0); PLAYING -> bookmark, `pause`, optimistic PAUSED with the extrapolated position frozen and `staleS = 0`;
  PAUSED -> `resume`; both set `optTr` and wake the poller. `pause()`/`play()` are ours-gated: not ours -> nothing is
  sent (2140, 2184).
- **Same tile** (`play()`, 792-862; `sonos_same_tile.py`): tapping the loaded entry while fresh, ours, index known and
  nothing settling (`pending`/`optTr` < 8 s): PLAYING -> no-op; PAUSED -> one `resume`. Anything else (stale, grouped
  away, an explicit item) -> a full start **to our own uid**, never the coordinator (190-199).
- **next/previous** (`sonos_step`, 1568-1623): previous with no pending step and position > 5 s restarts the current
  item; else `(base + delta) mod n` where base is the coalesced want; optimistic index at once; one worker jumps to the
  **latest** want with `playIndex(want, 0)`; the worker catches everything (never leaves `stepping` true). The wrap and
  long-form rules are `M/logic/Transport.kt`'s, as on the phone.
- **seek** (1945-2002, 2046-2064): target = clamp(abs or base + delta, 0, dur - 5); no duration -> refused (`live`);
  coalesced worker posts `seek` latest-wins with 0.7 s spacing; `optPos = (target, now, uri)`, `bmHold` cleared,
  `seekAt` stamped. The guess is never written into the snapshot.
- **stop** (`onDestroy`, suspension): bookmark, ours-gate, `stop`.
- **volume** (§5.3): `verb volume(v)` with the parent's cap applied as `v <= cap%` of 100; no `_sonos_vol_opt` needed
  beyond the aux write-through.

### 4.6 Switching (`_renderer_to_sonos`, 1625-1667; `_renderer_to_box`, 1669-1708)
- **Phone -> room**: `LocalRenderer.savePosition()` (the mpv bookmark flush), pause Exo, write `renderer.json`, clear
  the output choice's audio effects, `MediaSession.setPlayer(KidPlayer(sonos.player))`, then `start(current entry)` at
  the saved position; an error -> `Message.CANT_PLAY_ON_SONOS` and the renderer reverts to local, paused.
- **Room -> room** (1636-1644): one live classify, bookmark, `stop` to the **old** uid, then the start above.
- **Room -> phone** (1669-1708): live classify (`/state?live=1`: one classify with a 1.5 s budget, falling back to the
  snapshot, 1021-1039), bookmark, `stop`, write local, clear the Sonos state, `setPlayer(KidPlayer(exo))`, and
  **continue on the phone from that bookmark** - the switch is the kid's own tap, so it is not "starting by itself"
  (`music/CLAUDE.md`); the phone's volume cap applies again.
- The output sheet's choice while a headset arrives or leaves (`deviceCallback`, 586-605) is local-only and doesn't touch
  the renderer.

### 4.7 Adopt after a restart (`_sonos_poller` start, 4254-4310)
`PlaybackService.onCreate`: when `renderer.json` says sonos, one classify of that uid (budget 1.5 s, off the main
thread). Armed-equivalent and PLAYING/PAUSED and `normUri` equality with the stored uri -> `adopt` (no transport
commands), rebuild the queue from the stored entry with the speaker-playable filter, index by uri equality, duration
from the listing with `durFromListing`, `setPlayer(sonos)`. Otherwise -> local, nothing starts, the now-playing bar
comes back paused as today (`NowPlayingRow`). The service is alive while the speaker plays because the renderer reports
playing (Media3 keeps the `mediaPlayback` FGS up); adopt is for the process dying anyway.

### 4.8 Grouped away, taken over, lost, moved (`sonos_grouped.py`, `sonos_migrate.py`)
- `rendererState` for the UI and `playing = false`: `GROUPED_AWAY` (x-rincon: uri or the aux topology), `TAKEN_OVER`
  (not ours, non-empty uri), `LOST_SESSION` (STOPPED, empty uri), `UNREACHABLE` (stale) - in that precedence
  (2627-2632). The player screen shows one line under the title (§5.2); nothing acts on it but the kid.
- **Migration follow** (`_sonos_stream_moved`, 1090-1135): a `streamMoved` hint with `uid != current`, snapshot uid ==
  renderer uid, `staleS < 12` -> `adopt(hint.uid, kind, hint.uri)` **first**, then `renderer.json` with the new uid and
  name; later verbs go to the new uid. A failed adopt changes nothing and is retried next tick.

## 5. UI, strings, server

### 5.1 "Spill av på" (`OutputSheet`, `M/ui/PlayerScreen.kt:197-230`)
Rows: "Telefonen" + connected headsets as today, then the Sonos rooms from the cache (grouped rows as §2.1, icon a
speaker glyph from `scripts/material-symbols.sh`, subtitle "Sonos" / "Sonos · svarer ikke" when the refresh found
nobody), then "Se etter høyttalere" (rescan; shows a spinner for the 3 s scan). Opening the sheet refreshes the topology
once. The player's bottom bar shows the room name highlighted like a headset. Strings in `values` and `values-nb`
(`SourceScanTest`), and `FitTest` for the bar with a long room name ("Bad 2. etasje").

### 5.2 The player on Sonos
Everything as today, plus: `rendererState` as one quiet line under the entry name ("Spilles i en annen gruppe",
"Noen andre bruker høyttaleren", "Høyttaleren stoppet", "Får ikke kontakt med høyttaleren"); a stale snapshot keeps the
last position and shows the play button (a tap starts over, §4.5). The sleep timer ends with a **short fade**: three
`SetVolume` steps (2/3, 1/3, 0 of the current volume) 1 s apart, `pause`, then the volume restored, so the next
play isn't silent (user, 2026-10-11: a fade, as long as it is short; the local 10 s fade would be ten SOAP calls).

### 5.3 Volume
`DeviceInfo(PLAYBACK_TYPE_REMOTE)` makes the phone's volume keys drive `setDeviceVolume` while Sonos renders; the
renderer sends `SetVolume` clamped to the parent's cap (`volume_cap_pct`, read from the managed config as `mobile_data`
is) applied to the speaker's 0..100 scale; vibb's "no cap on Sonos" (1004-1028) is a deviation (§9) because the user's
answer in design 21 says the cap applies to every output. Confirmed by the user 2026-10-11.

### 5.4 Server: the NRK program id (one commit, both directories)
NRK series items play on Sonos only through the NRK service, which needs the program id. Today the item key is that id
for a psapi listing but `sha1(guid)[:12]` for the RSS fallback, and the phone can't tell (`server/src/music_sources.rs:
87-89, 474-476`). Add an optional `nrk_program_id` on listing items (psapi listings only), in `server/testdata/
music_listing.json` and the music app's copy (`ContractFilesTest`), `ListingItem.nrkProgramId` lenient (absent = null).
No other server change in 1b: the report stays as it is (the server sees no now-playing, design 20 decision 5).

## 6. Phone-specific rules

1. **Time rules and suspension.** The launcher pauses the session, then suspends the app (`L/music/MusicSessions.kt:
   179-190`, design 21 step 4). On Sonos a pause from the session stays a `Pause` (vibb), but `MY_PACKAGE_SUSPENDED`
   (`SuspendedReceiver`, `M/MusicApp.kt:64-68`) -> `bookmarkNow(force)`, `stop`, disarm: a paused speaker could be
   resumed from its own button or the Sonos app, and the suspended app can't see it. `playAllowed(suspended)` gates
   every Sonos play path as it gates the local ones (`GuardsTest`).
2. **Process death while Sonos plays** leaves the speaker playing (there is no sidecar to keep polling). Accepted and
   documented; the next start adopts (§4.7). `onTaskRemoved`/`onDestroy` bookmark first, as today (176-197).
3. **Doze and the screen.** The poller runs in the `mediaPlayback` FGS with the phone silent, at vibb's cadence
   whatever the screen does, and bookmarks on every poll (§4.4): the saved position stays as fine as on the Pi. What
   idles with the screen off is only the UI side, which already does (`livePosition` polls only while a screen is
   shown, `MusicRoot.kt:166-182`); the `SimpleBasePlayer` state still updates per poll, since the launcher's lock view
   reads it when the screen comes back. [device check] 1 h screen off: are polls deferred in Doze, do bookmarks land
   every 15 s? If Doze defers them, the launcher grants the music app the battery-optimisation exemption design 19b
   gives itself (device owner), rather than accepting coarser bookmarks.
4. **Network changes.** Wi-Fi gone -> the speaker reads UNREACHABLE (backoff, disarm after 600 s); the kid's phone away
   from home keeps the cache (the "cabin case", `sonosd.py:1050-1053`): the sheet lists home rooms marked stale.
5. **Permissions:** `CHANGE_WIFI_MULTICAST_STATE` (the `MulticastLock`) and `ACCESS_WIFI_STATE` in the manifest. The
   launcher's `KidVpnService` routes only `192.0.2.2/32`, so SSDP and LAN SOAP bypass the tun (design 26 review #3);
   DNS is not involved (ips only). Still a [device check] with the VPN up.
6. **Kiosk.** Nothing opens: no Sonos app, no links (`SourceScanTest`). The NRK service precondition is the household's,
   set once from a parent's Sonos app, not from the phone.

## 7. Tests (JVM; vibb's tests ported, one line each in `docs/design/26a` -> the test names)

- `sonos_contract.json` (from `tests/sonos_contract.py:14-143`): KINDS, TRANSPORTS, the required/armed field sets,
  PLAY_URL/PLAY_NRK and STATE_PLAYING/FOREIGN/UNREACHABLE/LOST/IDLE/GROUPED/MOVED (the SHARE ones wait for phase 3).
  `SonosContractTest` validates every canned snapshot the other tests use against it (the "two-fakes trap", 1-10).
- `DidlTest`: the exact DIDL of PLAY_URL and PLAY_NRK (escaping once, both artist tags, duration attribute, the desc).
- `SonosUrisTest`: `normUri` on the four prefixes and percent-encoding; `hms`/`hmsToS` incl. `"0:00:00"` -> 0.0.
- `TopologyTest` (`sonos_groups.py:77-108, 120-136`): ip/name healed, invisible dropped, groups replaced coordinator-first,
  nobody answering -> stale cache, picker rows ("Kjøkken + Stua" -> coordinator, absorbed members not repeated, unknown
  member -> no row).
- `SpeakerCacheTest` (`sonos_hold_scan.py:71-99`, `pipewire`): nobody answering -> the old rows at once, `stale`, one
  background scan started and `scanning` true, the call returns in under 2 s; a second refresh while it runs starts no
  second scan; after the round the new rows show and `scanning` is false; a later refresh that fails again keeps the
  rows and scans once more.
- `SonosSessionTest` (`sonos_grouped.py:242-260`, `sonos_migrate_probe.py:200-307`): x-rincon -> grouped away on the same
  poll, mp3radio not grouped, empty uri keeps, own uri clears; no probe for STOPPED+x-rincon / uri retained / PLAYING+empty
  / window past; url match by `normUri` against a live candidate, hint carries the session uri and rides every publish;
  a STOPPED candidate never qualifies; exactly 3 probes; ours+live re-arms; adopt clears; cadence 5 s only while a
  transition pends; stall after 3 frozen polls, one retry; disarm after 600 s; `staleS` from elapsed time.
- `SonosTransportTest` with a fake `SpeakerIo` recording calls (`sonos_hush.py:72-119`): start 120 -> `[mute true,
  setUri, play, seek 0:02:00, mute false]`, already muted stays muted, start < 5 never touches mute, a SetURI fault
  restores mute and surfaces; 701 retried then degrade.
- `SonosRendererTest` (`sonos_renderer.py:93-167`, `sonos_same_tile.py:113-202`, `sonos_grouped.py:288-331`,
  `sonos_migrate.py:62-115`, `storytel_sonos.py` non-Storytel parts 184-203, 230-243, 255-299, 372-381, 409-432): a
  start posts one contract-valid body, never touches Exo; fresh PLAYING -> playing, position ≈ rel + age, duration; stale
  -> not playing, position kept, UNREACHABLE; foreign -> TAKEN_OVER; grouped away -> GROUPED_AWAY not shadowed; switch
  back bookmarks before exactly one stop; same tile PLAYING no-op, PAUSED one resume, untrusted/stale/explicit -> start
  to our uid; pause freezes the extrapolated position; the bm hold keeps 3120 over 39 and releases after 120 s; the
  regression guard's table (3120->5 kept, 3120->2400 written, 10800->9000 written, seeked 5 written, 200->5 written);
  duration carry only for the same uri, falsy never erases; playpause STOPPED -> start, PAUSED -> resume, PLAYING ->
  pause, not ours -> start; seek hold TOL/HOLD and the guess never in the snapshot; migration follow adopts first then
  writes the renderer, no-ops on same uid / other uid / stale / absent, a failed adopt changes nothing; suspension ->
  bookmark + stop.
- `PlayBodyTest`: art only when http, NRK series with a program id -> `nrk_program`, without -> filtered out, listing
  duration rides along.
- Existing: `GuardsTest` pins updated, `ReportTest`/`BridgeTest` unchanged (no report change), `FitTest` + a long room
  name, `SourceScanTest` (no `Uri.parse("http`), `ContractFilesTest` for the listing field. Launcher: none (no AIDL
  change); `MusicMediaPlanTest` gains a remote-session case (position with update time, speed 1).

## 8. Steps (each a commit set with its tests; branch `music-1b-sonos`)

1. **`sonos/` core**: §2 entirely (Ssdp, Soap, Topology, SpeakerCache, Didl, SonosUris, NrkService, SonosSession,
   SonosTransport, PlayBody, RendererState), the manifest permissions, `sonos_contract.json`, the §7 tests down to
   `PlayBodyTest`. No change to `PlaybackService`. A fake speaker for the emulator: `scripts/fake-sonos.py` (SSDP replier
   + the SOAP actions above with a scripted transport; the fake sidecar idea of `sonos_renderer.py` moved one layer down)
   and `docs/testing/emulator.md` §6g.
2. **Renderer axis, local only**: `Renderer`, `LocalRenderer`, `MediaSession.setPlayer`, the `Output` key widening, the
   `GuardsTest` pins; all 126 music tests green plus the new ones; no behaviour change (emulator smoke: §6f).
3. **`SonosRenderer`**: §3.2-3.3, §4, §5.1-5.3, §6; `SonosRendererTest`; emulator against the fake speaker (switch both
   ways, same tile, grouped-away and taken-over scripts, migration, restart adopt, suspension stop).
4. **NRK program id** (server + music, one commit, §5.4), `music/CLAUDE.md` (the renderer axis, the Sonos rules),
   NOTICE.md (the `sonosd.py`/`daemon.py` port, as the existing vibb paragraph), design 21's "Sonos is 1b" note ->
   points here, `docs/design/26` status. Then `music-v0.2.0-rc.1`.

## 9. Deviations from vibb (each with its reason)

| vibb | Here | Why |
|---|---|---|
| Sidecar on 127.0.0.1:3681, `SidecarDown`, `/state?live=1` thread | one process, a `Mutex`, a 1.5 s-budget classify | no second process on Android |
| SoCo | own SSDP + SOAP (§2.2) | no GPL-compatible maintained UPnP library; the surface is ten actions |
| `soco.discover` | SSDP for one ip, then GetZoneGroupState | that is what discover does; done in the open |
| bookmark throttle 25 s | a bookmark on every poll | user 2026-10-11: as fine-grained as possible; SD hygiene was the Pi's reason |
| no volume cap on Sonos | the parent's cap, clamped on `SetVolume` | design 21 user answer: the cap applies to every output (confirmed 2026-10-11) |
| volume via a Pi card | the phone's volume keys (remote `DeviceInfo`) | the mockup has no volume UI |
| shuffle unsupported on Sonos (1892-1901) | supported for url kind | the phone sequences the queue itself, shuffle is only an order |
| sleep timer fades `exo.volume` over 10 s | three `SetVolume` steps over 3 s, pause, volume restored | user 2026-10-11: a short fade; ten SOAP calls for a 10 s ramp is too chatty |
| `content.PREFER_REMOTE` global, `_EXPAND_CACHE` | the renderer passed explicitly to the plan filter | the thread race at `daemon.py:1529` |
| `_hms`-formatted `start_s` in a Python float | `Long` ms internally, `H:MM:SS` at the SOAP edge | Kotlin |
| `openssl`, ALSA, btwatchd, mpv, go-librespot, `sonos_keep_playing` | n/a | Pi-only |
| `uid` 404 triggers nothing (docstring says rescan) | a failed play offers "Se etter høyttalere" | the code's actual behaviour, made visible |
| process death keeps polling (sidecar) | the speaker plays on until the next start adopts | no sidecar (§6.2) |

## 10. User answers (2026-10-11)

1. **Volume keys control the speaker, with the parent's cap** (§5.3): yes.
2. **Sleep timer**: a fade, as long as it is short -> the 3 s `SetVolume` ramp in §5.2.
3. **The NRK Radio service**: the user expected plain streaming links plus DIDL to be enough. True for RSS and NRK
   podcasts (mp3). NRK *series* are HLS manifests, which the Pi downloads locally with ffmpeg and plays on Sonos only
   through the NRK service (`daemon.py:1270-1290`); the household already has it linked (`sn=14` is its known-working
   value). So: no new setup, and §2.3 stays as the port of what works.
4. **Positions with the screen off**: saved as finely as ever; only the screen needn't follow -> the cadence is
   vibb's, a bookmark on every poll, the UI idles (§4.4, §6.3). If Doze defers the polls, the launcher grants the
   battery exemption rather than the bookmarks getting coarser.

## Device checks (Jelly Star, real speakers; after step 3)
- Picker: rooms and a group row; a renamed or re-addressed speaker (DHCP) heals on the next refresh; "Se etter
  høyttalere" after a cold cache; the cabin case (home rooms listed stale, nothing plays).
- Play an RSS episode and an NRK series episode at 0 and from a 12-minute bookmark (the hush: no audible second from
  0:00); the Sonos app shows title/artist/art; `didl accepted` in logcat.
- Pause/resume from the phone, from the Sonos app, and from the speaker's buttons; next/previous mash; seek drag.
- Switch phone -> room -> other room -> phone: position within 3 s each time; both rooms silent when they should be.
- Group the speaker from the Sonos app (grouped away), play something else on it (taken over), remove our coordinator
  from its group (migration follow), pull its power (unreachable, backoff, disarm after 10 min).
- Kill the music app while Sonos plays: the speaker plays on; reopen -> adopt, correct position; reboot the phone ->
  the bar comes back paused.
- A bedtime edge mid-episode on Sonos: the speaker stops; its play button does nothing we care about (the app is
  suspended); the lock's media view and Home card went away.
- Screen off 1 h in Doze while Sonos plays: poll timestamps in logcat every 15 s, the bookmark after a forced stop
  within 15 s of the truth; the sleep timer's fade and the volume restored after it.
- VPN filter on: discovery and control still work; volume keys and the cap.
