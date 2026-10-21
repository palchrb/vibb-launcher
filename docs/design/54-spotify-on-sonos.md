# 54 - Spotify on Sonos in the Vibb music app: vibb's `spotify_sharelink` (architect)

Architect draft (2026-10-21). This is design 26c's "later step 9" (§11), now its own note. The user's decision of
2026-10-21: "Jeg er ganske så sikker på at jeg har lagt inn at Vibb pi sender en spotify sharelink direkte til
høyttaleren og da spilles den av på nydelig vis ... Da får man med hele playlisten og det er bare godt stemning!" -
and, for the scope: "jeg er ikke interessert i å endre i kø-logikken fra vibb for podcasts". **RSS/NRK podcasts and
Storytel are untouched:** one URI set directly on the speaker, our queue on the phone, vibb's `url`/`nrk_program`
path and DIDL exactly as today.

Inputs: `CLAUDE.md`, `music/CLAUDE.md` (the user's rules: behaviour follows vibb branch `pipewire` except the user's
decisions; nothing starts by itself on Sonos; every play is gated; the DIDL is vibb's and never extended), designs
26a (Sonos), 26c (Spotify on the phone; §1's table row and §11 step 9), 49 (shuffle and repeat), 50 ("Til slutten"),
53 (the event socket, the anchor, grouping - built, with its status sections), PLAN's design 53 entry (the measured
socket facts, the key). vibb `pipewire`: `pi/sonosd.py` (`Session.play`'s `spotify_sharelink` branch 520-566,
`verb("queue_play")` 664-690, `_classify` 700-810, the stall rule 896, `_probe_migration`'s sharelink branch 985-1000),
`pi/daemon.py` (the orchestrator's `sonos_map_trusted` 531, the same-tile `intact` 961-1000, `_sonos_bookmark_now`'s
sharelink branch 1349-1364, `_sonos_play_entry`'s sharelink path 1478-1520, `_sonos_start_spotify` 1572-1665,
`sonos_step` 1725-1790, `status`'s demoted DIDL fallback 2809-2818, the adopt 4570-4590, `_sonos_poller`'s sharelink
tick 4620-4740, `_spotify_bookmarker` 4836-4890), `tests/sonos_spotify_queue.py` (7 cases), `tests/sonos_migrate_probe.py`
case 3, `tests/sonos_hush.py` case 5, `tests/sonos_contract.py` (`PLAY_SHARE`, `STATE_SHARE`, `QUEUE_PLAY_REQUIRED`);
SoCo (MIT) `soco/plugins/sharelink.py` (`ShareLinkPlugin`, read 2026-10-21 on master) and `soco/core.py`
(`play_from_queue`, `clear_queue`, `queue_size`, `play_mode`, `PLAY_MODES`, `reorder_queue`); the user's socket capture
`music/app/src/test/resources/sonos/ws/capture_2026-10-21.json`; the music app's `sonos/`, `spotify/`, `playback/`.

`M/` = `music/app/src/main/java/me/vibb/music/`. Markers: **[D n]** = an owed device check (§5); **[vibb]** = ported
behaviour; **[ours]** = a deviation, listed in §6.

## 0. Facts

### 0.1 What vibb does (`pipewire`)

- **The speaker holds the queue, vibb owns the logic** (v2, the owner's decision 2026-08-09): one ShareLink add of the
  whole context, then positional jumps. `Session.play(kind="spotify_sharelink")`: `play_mode = "NORMAL"` (errors
  ignored - "NORMAL kills family shuffle/repeat leftovers AND makes the queue's play order equal its index order - the
  legality of every positional jump rests on this"), `clear_queue()`, `ShareLinkPlugin(spk).add_share_link_to_queue(uri)`
  whose answer is `FirstTrackNumberEnqueued` = `q_base` ("never assume the queue starts at 1, even after clear_queue"),
  `q_len = spk.queue_size`, `play_from_queue(q_base - 1 + track_index)`, all inside `_hush`, then `_seek_settled` for a
  start of 5 s or more. The session's `uri` is the share target. The answer carries `base`, `queue_len`, `play_mode`.
- **`queue_play {index, start_s}`**: 0-based `index` -> `absidx = base - 1 + index`, clamped to
  `[base - 1, base - 2 + q_len]`; `play_from_queue(absidx)` and the settled seek, hushed (`sonos_hush.py` 5).
- **Classify** (`_classify`): `ours` for the sharelink kind is a **prefix rule** - the TrackURI starts with
  `x-sonos-spotify:` or `x-sonosprog-spotify:`. Extra fields: `track_no` (`Track`, 1-based), `base`, `queue_len`,
  `track_spotify_uri` (the TrackURI after the first `:`, before `?`, percent-decoded: "exact under every queue divergence,
  zero extra SOAP"), and `track_title`/`track_artist`/`track_art` from `TrackMetaData` (`/getaa?` made absolute). No
  `lost_session`, no DIDL check, **no stall retry** for this kind. The migration bookkeeping keeps `_last_ours_track` and
  `_last_ours_rel`; the probe follows a sharelink stream **only on the exact decoded track and position continuity**
  (`base - 5 <= rel <= base + 45`), never on the prefix, and never with no recorded track (`sonos_migrate_probe.py` 3).
- **The start** (`_sonos_start_spotify`): the kinds `track album playlist artist show episode` pass vibb's own check
  (episodes "field-verified 2026-08-09"); the listing is go-librespot's `context_tracks` (a track/episode is a one-row
  list); the bookmark's track picks `idx` and its position `start_s` (an explicit episode pick starts at 0); `/play`;
  then `sonos_map_trusted = (qlen is None or qlen == len(rows)) and not listing.stale` - "never trust positional jumps
  on it - the drift resync heals". The pending seed, `opt_tr`, the refused-seek hold as for `url`.
- **Steps and picks** (`_sonos_play_entry`): map trusted -> `/queue_play`; refused -> `sonos_map_trusted = False`
  ("do NOT advance sonos_idx onto a track that is not playing"); map not trusted -> **a fresh transfer at the wanted
  track on this explicit press, never on a timer** (arch R3a, `sonos_spotify_queue.py` 5). `sonos_step` coalesces a
  mash into one worker and wraps (`% n`); prev more than 5 s in restarts the track.
- **The poller's tick** (`_sonos_poller`): while a jump of ours settles (8 s) a different `track_spotify_uri` is the old
  track - its position, duration and title are not taken; then `track_spotify_uri` is "the inbound authority": its row
  in the listing becomes `sonos_idx`; a uri not in the listing while ours -> `sonos_map_trusted = False`; a missing uri
  leaves the index ("coercing to 0 would rewrite the bookmark to track 1"). The bookmark every tick, throttled to 25 s.
  The sharelink kind never runs the `url` kind's STOPPED-near-the-end advance: the speaker advances its own queue.
- **The card** reads vibb's list (title, artwork), never the speaker's DIDL; only with the map untrusted or the index
  unknown does it fall back to the DIDL's title and art ("demoted fallback, never the primary", `sonos_spotify_queue.py`
  2). A guest's Spotify (not ours) is not playing (2b).
- **The bookmark** (`_sonos_bookmark_now`, sharelink branch): the context's bookmark (track uri, position in ms,
  duration, name, art) under the refused-seek hold; `_spotify_bookmarker` is the box's own player's (go-librespot) and
  never runs for Sonos.
- **The same tile** (`play()` 961-1000): a tap on the playing entry is a no-op (PLAYING) or one resume (PAUSED) only for
  a steady live session of ours whose map is intact; "a sharelink press with sonos_map_trusted False is the RE-SYNC and
  must not be swallowed" - the full transfer is the heal.
- **Adopt at start** (4574-4590): the queue rebuilt from the listing; a listing failure distrusts the map.
- **Not in vibb**: any play mode but NORMAL on Sonos, shuffle on Sonos, a reversed order (vibb's Spotify order is always
  `auto`), a Sonos account check, any use of the speaker's own Next/Previous.

### 0.2 SoCo's ShareLink, exactly (what the app ports - it has no SoCo)

- `SpotifyShare.canonical_uri`: `spotify.*[:/](album|episode|playlist|show|track)[:/](\w+)` -> `spotify:<type>:<id>`;
  **no `artist`** (vibb's own check lets an artist through, SoCo then raises "Unsupported URI": an artist never played
  on vibb's Sonos). `extract`: the encoded uri is the canonical one with every `:` replaced by `%3a` (lower case).
- `magic()` per type (`EnqueuedURI` = prefix + encoded; the DIDL's item id = key + encoded; class):

| type | prefix | key | `upnp:class` |
|---|---|---|---|
| `playlist` | `x-rincon-cpcontainer:1006206c` | `1006206c` | `object.container.playlistContainer` |
| `album` | `x-rincon-cpcontainer:1004206c` | `00040000` | `object.container.album.musicAlbum` |
| `show` | `x-rincon-cpcontainer:1006206c` | `1006206c` | `object.container.playlistContainer` |
| `track` | (none) | `00032020` | `object.item.audioItem.musicTrack` |
| `episode` | (none) | `00032020` | `object.item.audioItem.musicTrack` |

- The metadata, byte for byte (`dc:title` empty: vibb passes no `dc_title`):
  `<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
  xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item
  id="{key}{encoded}" parentID="-1" restricted="true"><dc:title></dc:title><upnp:class>{class}</upnp:class><desc
  id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON{sn}_X_#Svc{sn}-0-Token</desc></item></DIDL-Lite>`
  (one line; the wrapping here is the note's).
- The service number `sn` is a **service type**, not an account serial: `2311` (Spotify, sid 9: 9 x 256 + 7) first; on
  a UPnP fault the next service, `3079` (Spotify US, sid 12); the second's fault is the failure. (The NRK path's
  `70919` is the same formula, `NrkService.SVC_TYPE`.)
- `AddURIToQueue(InstanceID 0, EnqueuedURI, EnqueuedURIMetaData, DesiredFirstTrackNumberEnqueued 0, EnqueueAsNext 0)`
  -> `FirstTrackNumberEnqueued` (SoCo returns only this; the UPnP answer also names `NumTracksAdded` and
  `NewQueueLength` [D2]).
- `play_from_queue(index)`: `SetAVTransportURI("x-rincon-queue:<uid>#0", "")`, `Seek(Unit TRACK_NR, Target index + 1)`,
  `Play(Speed 1)` - the source is set every time. `clear_queue` = `RemoveAllTracksFromQueue`. `queue_size` =
  ContentDirectory `Browse(ObjectID "Q:0", BrowseFlag "BrowseMetadata", Filter "*", StartingIndex 0, RequestedCount 1)`
  -> the container's `childCount`. `get_queue` = `Browse("Q:0", "BrowseDirectChildren", ...)`, an item's `res` holding its
  track uri. `play_mode` = `SetPlayMode(NewPlayMode)`; `PLAY_MODES`: `NORMAL`, `REPEAT_ALL`, `REPEAT_ONE`, `SHUFFLE*`.
  `reorder_queue(start, n, insert_before)` = `ReorderTracksInQueue(StartingIndex start + 1, NumberOfTracks n,
  InsertBefore, UpdateID 0)`.

### 0.3 The socket and a queue (the capture of 2026-10-21, direct URIs only)

The capture played direct URIs, so it shows a queue only parked: every `playbackStatus` with the speaker's own
queue as its source carries `queueVersion` ("33", "34", "37"), `queueId` and `itemId: "0"`, and the household's queue
had **`playModes.repeat: true`** (vibb's NORMAL is needed in this house); a direct URI's status has no `queueVersion`,
`canSkip: false`, and `itemId` per item. Every change carries `previousItemId` and `previousPositionMillis` (a "next"
pressed at 65.9 s: `previousPositionMillis` 65871; the natural end: the full length). Not captured: `itemId` while a
queue plays, `canSkip` there, a queue's natural boundary, the queue's end, `queueVersion` around a jump, a play mode
change, a reorder [D6-D10]. Design 53's rule stands: the socket is events only - UPnP commands, verifies and bookmarks.

### 0.4 What the app does today (read 2026-10-21)

- `PlayBodies.speakerDropReason` (`M/sonos/PlayBody.kt:130`): a Spotify entry's item is `"spotify"`, so a room's queue
  is empty: `queueFor` emits `CANT_PLAY_ON_SONOS` and the phone keeps playing (`toRoom`, 26c §5.10 QA L1).
  `comeHome` assumes a Spotify entry never reaches a room (its comment). `phoneRendererFor` likewise.
- One SOAP play per item: `SonosController.playIndex` -> `PlayBodies.build` -> `SonosTransport.play`
  (`RemoveAllTracksFromQueue`, `SetAVTransportURI` with vibb's DIDL, `Play`, the settled seek); a step is a new play; the
  advance is `pollTick`'s STOPPED near the end (or the socket's IDLE's confirm poll).
- `Kind` (`SonosSession.kt:24`) is `URL`, `NRK_PROGRAM` ("`spotify_sharelink` is phase 3"). `sameStream` (normUri, or a
  Storytel marker by consumableId) is the ours rule, the probe's match and adopt's. `adoptIndex` finds an item by its
  built uri. `storedUri` stores the built uri (a Storytel marker). The stall retry and `lostSession` apply to every kind.
- `SpeakerControl` has no `AddURIToQueue`, `Browse`, `SetPlayMode`, `ReorderTracksInQueue`, `GetMediaInfo`; `Soap` has no
  ContentDirectory service. `SonosTransport.park` = Stop + `x-rincon-queue:<uid>#0` (the queue is empty after our own
  `url` plays: the speaker's Play button plays nothing).
- `SocketEvent.Playback` (`SonosEvents.kt:61`) carries state and position only - no `itemId`, `queueVersion`, play modes.
- `MigrationProbe.live` returns (state, TrackURI) - no position.
- `NrkService` already reads `/status/accounts` (cached per ip, `Account Type="70919"`); `accounts.xml` is a
  hand-written fixture (design 26a step 1), not a capture.

## 1. Shape

A Spotify entry on a room is vibb's `spotify_sharelink`: **one ShareLink add of the whole playlist, album or show into
the speaker's own queue, then positional jumps; the speaker plays the queue by itself** (gapless, the household's
Spotify account linked in the Sonos app, no token, no Web API, no web player). The phone keeps what it has today for
the kid: our list (the server's listing), our numbers and pictures, our bookmarks per track, our skip rules - and it
**follows** the speaker: every track change the speaker makes by itself (the natural advance) or that someone makes on
the speaker (its buttons, the Sonos app) is found by a confirm poll (`Track` + the decoded `TrackURI`), woken at once by
the socket's new `itemId`. Everything else - the event socket, the anchor, grouping, the follow, the cap, the sleep
timer, calls, the locks, the room scope - is design 26a/53's, unchanged except where §2 says.

## 2. Decisions

### 2.1 What may go to a room

- **List mode** (26c §3.3; the entry's items are tracks or episodes): speaker-playable when the target's kind has a
  ShareLink type - `playlist`, `album`, `track`, `show`, `episode`. **An artist never** (SoCo has no artist share, so
  vibb never played one either): an artist in list mode (the `SPOTIFY_ARTIST_ALBUMS` option, tracks of many albums) has
  no single link. `speakerDropReason` returns null for the five, `"spotify artist"` for an artist.
- **Context mode** (26c §5.9): `"spotify context"` until round 54-c (§2.12), then a playlist context passes and an
  artist context stays refused.
- **Shows and episodes** go by the ShareLink too (vibb did; Q4). This is Spotify's own path, not the podcast queue the
  user keeps: RSS and NRK podcasts and Storytel are not touched.
- **The household** (§2.13): a room whose household's `/status/accounts` names no live Spotify account refuses a
  Spotify entry before any SOAP; unknown tries.
- Refused: the phone keeps playing (`toRoom`'s existing "nothing speaker-playable" path), with `CANT_PLAY_ON_SONOS`
  ("Kan ikke spilles på Sonos") for the kind and the new `SONOS_ASK_ADULT` ("Kan ikke spilles på Sonos - spør en voksen")
  for the household (§2.13). Neither names a source (26c step 8; `SourceScanTest`).

### 2.2 The transfer (vibb's `/play` for `spotify_sharelink`; `SonosTransport.playShare`, in the actor)

`Kind.SPOTIFY_SHARELINK("spotify_sharelink")` joins `URL` and `NRK_PROGRAM`. Its body (`PlayBody` with `kind`
`SPOTIFY_SHARELINK`, `uri` = the canonical target `spotify:<kind>:<id>` as `SpotifyRef.target` gives it, and two new
fields): `trackIndex` (the start item's index in the **listing's own order**, §2.3) and `listed` (the listing's track
uris in that order, `uriOf(kind, key)`; never logged whole). The sequence, under the transport's mutex, in this order:

1. `hush(startS)` (vibb: the whole play is inside it).
2. On a START: the cap (`capVolume`, every room of the row; §2.11) - as `play`.
3. **`gate()`** (the job's token; the first command that changes the room comes next).
4. `SetPlayMode(NewPlayMode = modeFor(repeat))` (§2.7; errors ignored, vibb's `try`).
5. `RemoveAllTracksFromQueue`.
6. `AddURIToQueue` with `shareLinkUri(ref)` and `shareLinkMetadata(ref, 2311)`; on a `UpnpFault` the same with `3079`;
   the second fault throws (a failed start, §2.13). `first` = `FirstTrackNumberEnqueued` (unparsable -> 1, vibb), `len`
   = `NewQueueLength`, else vibb's `queue_size` Browse [D2].
7. **The probe** (§2.3): one or two one-item Browses of `Q:0` -> the `QueueMap`.
8. **`gate()`** again, then `play_from_queue(position(trackIndex))` as SoCo: `SetAVTransportURI(queueUri(uid), "")`,
   `Seek(TRACK_NR, position)`, `Play`.
9. `session.played(queueUri(uid), cadenceHintS)` and `session.shareSet(listed)`; armed.
10. A start of 5 s or more: `seekSettled` (REL_TIME); false arms the controller's refused-seek hold, as today.
11. Unhush. `PlayResult(uri = queueUri(uid), queue = map, ...)`.

`shareLinkUri`/`shareLinkMetadata` are pure (`M/sonos/ShareLink.kt`, SoCo's table and template, credited) and pinned by
goldens `sonos/sharelink_<type>.xml` that `scripts/sonos-play-test.py --golden` writes from SoCo's template copied into
the script (MIT, credited) - regenerate, never hand-edit. **This is not vibb's `didl()`**: it is the metadata vibb's
sidecar sends through SoCo, field for field, and the user's DIDL rule applies to it the same way - nothing added (no
title, no art, no duration). `builtDidl` stays for `url` and `nrk_program` only.

### 2.3 The map (vibb's `q_base`/`q_len`/`sonos_map_trusted`, generalised to our listing)

`QueueMap` (pure, `M/logic/SonosQueue.kt`): `first` (1-based position of the first enqueued track), `len`,
`n` (our listing's size), `direction` (`FORWARD`/`REVERSED`), `trusted`. Position of listing index `i`:
`first + i` forward, `first + (n - 1 - i)` reversed, clamped to `[first, first + len - 1]` (vibb's clamp).

- **Why a direction** [ours]: vibb's listing was go-librespot's, in the speaker's own order. Ours is the server's: a
  playlist top first and an album in track order (the queue's order) - but a show is listed newest first and nothing
  says Spotify's show container is [D11]. One Browse settles it instead of a guess.
- **The probe** at a transfer: `u1` = the uri at position `first` (`Browse Q:0 BrowseDirectChildren`, `StartingIndex
  first - 1`, `RequestedCount 1`; the item's `res` decoded as a TrackURI, §2.5). `u1 == listed[0]` -> forward; `n > 1 &&
  u1 == listed[n - 1]` -> reversed; else untrusted. Trusted also needs `len >= n` (vibb's `qlen == len(rows)`, relaxed:
  a server listing capped at its first N tracks still maps) and, when `trackIndex != 0` (or `!= n - 1` reversed), a
  second one-item Browse at the start's position naming `listed[trackIndex]` - so the first play lands exactly.
- **Untrusted at a transfer** (the household's playlist differs from our listing - changed since the last sync,
  relinked, private to another account): `findInQueue(listed[trackIndex])` - paged `BrowseDirectChildren` of 100 from
  position `first`, at most 10 pages, stopping at the hit - plays there; not found -> position `first` at 0 (vibb's
  "hit is None -> idx 0"). The map stays untrusted.
- **Verified at every confirm** (vibb's "inbound authority"): an ours poll with `Track = p` and decoded uri `u`; if the
  map is trusted and `u != listed[indexAt(p)]` -> untrusted (logged once). A uri of our listing at a position the map
  doesn't predict is the same drift.
- **Untrusted is healed only by an explicit press** - a step, a pick, a tile tap - which is a **fresh transfer** at the
  wanted track (vibb R3a), never a timer, never the natural advance. While untrusted the speaker's own flow is followed
  by uri (§2.5) and every press costs one transfer (~1-2 s gap [D2]): the same as vibb, and it ends at the next sync.
- **A superseded transfer still replaced the speaker's queue**: the controller takes the map of every transfer that
  landed, in actor order, generation or not (as `playing` today) - never a map of a queue that isn't on the speaker.

### 2.4 Steps, picks and the tile (vibb's `queue_play`; `SonosTransport.queuePlay`)

- `queuePlay(position, startS, cap, members, gate)`: hush, the cap on a START only, `gate()`, `play_from_queue(position)`,
  the settled seek for 5 s or more, unhush (`sonos_hush.py` 5). `session.stampVerb()`.
- **Intact** (vibb's `intact`): the controller's `queue` is trusted, of this entry and this uid, and the last fresh
  snapshot is ours (§2.9) with nothing settling. **Intact -> `queuePlay`; not intact -> a transfer.** One rule for:
  - a step (`stepTo` while playing, the coalescing worker - one per settled want, never wedged; `PlayWhy.STEP`, no cap);
  - the natural-advance corrections of §2.6 and §2.7 (`PlayWhy.ADVANCE`, gated);
  - a pick in the list or a carousel swipe on the loaded entry while its queue is on the speaker (`load` of the same
    entry keeps `queue`; the play is a START: capped, hushed, at the item's saved place - no clear, no add);
  - the first play after a skip on a paused room (`start()` with `startPending`, today's rule below).

  `restart()` is not on this list: after a stop, a disarm or anything not steady it is always a transfer (the tile).
- **The tile** (`playTile`, vibb's same-tile): steady + PLAYING -> nothing; steady + PAUSED -> one resume; anything
  else -> `restart()`, and for this kind `restart()` is always a **transfer** (vibb: "the full transfer IS the heal").
- **A refused `queuePlay`** (a fault): `queue.trusted = false` and the index stays on what plays (vibb: "do NOT advance
  sonos_idx onto a track that is not playing"); the next press heals.
- Skip rules are the service's, unchanged (`skipTarget`: previous more than 5 s in restarts; no wrap without repeat all,
  design 49) - not vibb's `% n` wrap (§6).
- `stepTo` on a paused, stopped or not-started room only moves where the next play starts (today's rule), which is then
  a `queuePlay` when intact.

### 2.5 Following the speaker (classify, the confirm, the boundary)

- **`trackSpotifyUri(trackUri)`** (pure): `x-sonos-spotify:` or `x-sonosprog-spotify:` -> the part after the first `:`,
  before `?`, `pyUnquote`d (vibb's exact expression); anything else null. The same decode reads a Browse item's `res`.
- **`SonosSession.classify` for this kind** (vibb's fields): `ours` = the decoded uri is not null **and** is one of the
  session's `listed` uris [ours: stricter than vibb's prefix, §6]; `Fields.trackNo` (`Track`), `Fields.trackSpotifyUri`;
  no `lostSession`, no DIDL check, and `stallBookkeeping` returns null for this kind (vibb 896). `oursAt`, and the new
  `lastOursTrack`/`lastOursRel`, are kept on every ours + live sighting.
- **The confirm**: today every `playbackStatus` wakes a confirm poll (at most once a second). New in
  `SocketEvent.Playback`: `itemId`, `queueVersion`, `previousItemId`, `previousPositionMs`, `playModes` (parsed,
  optional). An **`itemId` different from the last one of our group, while a queue session of ours plays and no play of
  ours is pending**, is a track change the speaker made: the confirm poll goes **at once** (as IDLE and `MOVED` do),
  and no socket measurement counts until an ours poll (`wsBlocked`, as for a BUFFERING we didn't cause) - the event's
  `positionMillis` belongs to the new track, which the controller doesn't know yet. `itemId` is compared, never decoded
  [D6]. Without a socket the change is seen at the next poll (5 s within 45 s of the end, the 1 s display poll while the
  player shows, else the 15 s cruise).
- **`pollTick` for this kind**, after the existing landing, disarm, accept, `optTr`/`optPos`, the follow and the
  `fresh && ours` gate, in this order:
  1. **STOPPED near the end** (`endsNear > 0`) is the queue's end (§2.7) - before anything reads `Track`, which a
     stopped queue may have reset to 1 [D7].
  2. **A track change**: the polled uri isn't `playing`'s and no play of ours is pending -> `queueMoved`:
     - the item is the one of our list with that uri (`adoptIndex`, §2.9); `index` and `playing` follow it
       (`Playing(entryId, index, key, polled TrackURI)`), `endsNear = 0`, the map verified (§2.3);
     - **natural or pressed** (`boundary`, pure): with the socket, natural iff the change's `previousPositionMs` is
       within `NATURAL_END_MS` (3 s) of the previous track's duration; without it, iff `endsNear > 0` (a press in the
       last 20 s counts as natural - accepted, it only adds a done mark). Natural -> `autoFlag` (the service marks the
       finished track done, as for every renderer's automatic change), then §2.6's next-track check. Pressed (the
       speaker's buttons, the Sonos app) -> followed as it is: no done mark, no correction - the user's "a press on the
       speaker's own next/prev now has a queue to move in".
  3. The bookmark (§2.8), then `endsNear` as today.
- **A track that isn't in our list** (the household's playlist has a track our listing doesn't - changed since the
  sync) is not ours: the player shows the remote line it shows for any stream not ours, nothing is bookmarked or
  advanced, and the next listed track is ours again. Accepted for list mode (the listing catches up at the next sync);
  context mode has no list (§2.12).
- **The pending play** (`Pending`) of a `queuePlay` or a transfer carries the target's Spotify uri; it lands when a
  poll's `trackSpotifyUri` equals it (`sameTrack`), else at `SETTLE_MS` after the answer as today. Until then a
  different track is the old one (vibb's 8 s settle; the seed stands).
- **The now-playing** (title, picture, numbers, "Spor N av M") is always our list's item; the speaker's `TrackMetaData`
  is never shown in list mode (`sonos_spotify_queue.py` 2) and the speaker's `/getaa` art is never fetched.

### 2.6 Our order on a room

The speaker's natural advance is the queue's next position (NORMAL and the repeat modes keep play order = index
order). Our next is the next item of **our** order: the parent's order setting, the kid's flip (design 39) and shuffle
(design 49) - not vibb's (vibb plays Spotify's order only).

- **`follows`** (pure, per `load`/`reorder`/`setModes`): every consecutive pair of our items sits at consecutive
  positions of the map (a show listed newest first over a newest-first queue follows; a flipped playlist doesn't). While
  it holds, the speaker's own advance is ours: gapless, nothing sent.
- **When it doesn't** (round 54-a): at a **natural** boundary (§2.5) whose new track isn't our expected next, one
  `queuePlay` of our next at 0 (`PlayWhy.ADVANCE`, gated; `mayPlay()` false -> a pause instead: the speaker started a
  track nobody chose). About 0.5-1.5 s of the speaker's next is heard [D6]. A pressed change is never corrected.
- **Round 54-b** (Q1's default): **the next track moved into place**. Right after each track of ours is confirmed
  playing, when the position after it doesn't hold our next item, one `ReorderTracksInQueue` moves our next there
  (SoCo's `reorder_queue`), and the map becomes a permutation (`QueueMap.slots`, each move applied to it). Then the
  speaker's own advance plays our next gaplessly, and its own Next and Previous buttons step through our order. Only
  with a trusted map and the socket's last `playModes.shuffle` false (someone switched shuffle on in the Sonos app: never
  fought, followed by uri); a fault -> untrusted, back to the correction. Gated on [D10]: the current track plays on
  through a move, the positions after it are as computed.

### 2.7 The end of the list, repeat, "Til slutten"

- **Play mode** (`modeFor`): repeat off -> `NORMAL`, all -> `REPEAT_ALL`, one -> `REPEAT_ONE` [ours: vibb always NORMAL;
  all three keep play order = index order, the reason vibb gives for NORMAL]. Never `SHUFFLE*` in list mode. Sent at
  every transfer, and as one verb when the kid changes repeat during a live queue session (`Renderer.setModes` ->
  `SonosController.setRepeat` -> `transport.setPlayMode`). A mode changed in the Sonos app is not fought; the next
  transfer sets ours again.
- **The queue's end** with `NORMAL`: the speaker stops after its last position -> STOPPED near the end -> today's
  `advance()`: no next -> `ended`, the service's `queueEnded`; repeat all whose mode didn't take -> the gated wrap as
  today (a belt). [D7: the transport, `Track` and `TrackURI` after the end; the socket's IDLE.]
- **Our list ends before the queue does** (a capped listing, a non-following order, untrusted): when the item now
  playing is our last and repeat is off, the controller **pauses at its end** - the "Til slutten" mechanism below - and
  then reads as `ended`. Never a Stop of a queue that would go on: a pause keeps the speaker's place.
- **"Til slutten"** (design 50): the speaker doesn't stop between tracks, so `advance()`'s `holdAtEnd` never runs. For
  this kind `pauseAtItemEnd` arms **an at-end pause**: on every measurement while PLAYING, `work.after(dur - pos -
  AT_END_QUEUE_PAUSE_MS)` (400 ms, as the web player's `AT_END_SPOTIFY_PAUSE_MS`), re-armed by each measurement, firing
  only for the same generation and item: bookmark, Pause, `index` = our next at 0 with `startPending`, then `held()`. The
  fade is the service's `atEndGain`, unchanged. The belt: a natural boundary seen while `holdAtEnd` is set -> Pause at
  once, our next waits at 0 (the speaker's ~1 s of it is replayed from 0 by the next play's `queuePlay`). While "Til
  slutten" is on, `REPEAT_ONE` is not sent (design 50's "repeat off while it is on").

### 2.8 Bookmarks, duration, the bar

- **The bookmark is today's `bookmarkNow`** - vibb's sharelink branch maps onto it one to one: a measured position of
  the track the speaker plays (`playing` follows the queue, §2.5), through `SonosBookmarks.decide` (the refused-seek
  hold, `sonos_spotify_queue.py` 4, and the regression guard), per item key, the entry's bookmark and the stored
  queue's row - exactly as the phone's web player keeps them (26c §5.4), so phone <-> room continue at the same track
  and second. The cadence is the poll's (`BOOKMARK_MIN_MS`, at once on a transport or track change). `_spotify_bookmarker`
  has no counterpart (it is go-librespot's). **The socket never writes** (design 53): `previousPositionMillis` is not a
  bookmark - the old track's last bookmark is the last ours poll's.
- **The duration**: the speaker's `TrackDuration` (it knows a Spotify track's), else the listing's through `accept`'s
  carry, flagged `durFromListing` (never decides "heard to the end") - unchanged.
- **The bar**: `sonosAnchor` unchanged; the anchor key changes with `playing`'s item, so a track change jumps.

### 2.9 Ours, adopt, the suspension, the park

- **Ours** (§2.5): a Spotify queue TrackURI whose uri is in our listing. The queue kind's stored marker in
  `renderer.json` is `queueUri(uid)` (`storedUri` for a Spotify entry: the uri as played, which for this kind is the
  marker); `kindOfUri("x-rincon-queue:...")` = `SPOTIFY_SHARELINK`; `sameStream(marker, live)` = the live TrackURI is a
  Spotify queue track (the prefix); `adoptIndex` for a Spotify entry = the item whose `uriOf(kind, key)` is the
  decoded uri - so "ours" for adopt, the standalone suspension and the scope's look is the prefix **and** an item of
  the stored entry. With a queue marker stored, **the item is always found from the live uri** (`adoptIndex`), never
  `RendererChoice.itemKey` (the queue moved on since the last play): `SonosSuspension.bookmark` and the adopt.
- **Adopt at start** (`adoptLive`, zero transport commands): kept for our live PLAYING/PAUSED queue track of the stored
  entry; one `GetMediaInfo` in the look, inside its budget, confirms `CurrentURI == queueUri(uid)` (a read; a speaker playing the same
  playlist through Spotify Connect, `x-sonos-vli:`, is never ours). Then, as a separate actor job (reads only):
  `learnQueue` - `queue_size` and the one-item Browse at position 1 -> the map (`first` = 1: our transfers always clear;
  trust as §2.3). Nothing is sent; the next press is a `queuePlay` when trusted, else the heal.
- **The migration probe** for this kind (vibb `sonos_migrate_probe.py` 3): `MigrationProbe.live` returns the position
  too; a candidate matches only on `trackSpotifyUri == lastOursTrack` with `lastOursRel - 5 <= rel <= lastOursRel + 45`;
  no recorded track -> never follows. The follow adopts with the new uid's marker (`queueUri(new uid)`, not the hint's
  old one - `Moved.uri` is the session's) and keeps the map (the queue moves with the stream [D13]; verified at the
  next confirm).
- **The park** (the suspension edge, 26a §6.1): Stop, **`RemoveAllTracksFromQueue`**, then `x-rincon-queue:<uid>#0` -
  for every kind (an empty queue costs nothing; for this kind the queue would otherwise hold the playlist, and the
  speaker's own Play button would play it during a time rule). Inside the existing budget; a refused clear is logged,
  the Stop already happened. **A normal Stop** (the kid's stop, a switch home, the session's end) leaves the queue: the
  household's speaker, its Play button resumes the playlist - as vibb.

### 2.10 Hand-over

- **Phone (the web player) -> room**: `toRoom` as today: `old.saveNow(force = true)` (the web player's measured
  place), the room's queue through `queueFor(..., SONOS)` (now speaker-playable), `renderer.json`, `attach` (the
  detached web player is paused and owns nothing, 26c), `start` -> a transfer at the item and second (hushed, settled
  seek). `local.standDown()` stays; the web player's page idles out after 5 minutes as today.
- **Room -> phone**: `handOff` (the live classify, the bookmark, the ours-gated Stop) then `comeHome`, whose
  `phoneRendererFor(entry)` is now really `SPOTIFY` for this entry: the web player starts at the item and place, playing
  only if the room was PLAYING and `playAllowed`; under the phone's streaming gate it loads paused with "Trenger Wi-Fi"
  (26c). `comeHome`'s comment and `phoneRendererFor`'s KDoc are corrected.
- **Room -> room**: today's: the old room's bookmark and Stop (its queue left), a transfer on the new one.
- No token, no page and no Connect device for a room: the speaker plays with the household's account (§2.13).

### 2.11 Grouping, volume, the sleep timer, calls, locks, the socket

- **Grouping** (53-b): unchanged verbs. A "+" joins a room to our coordinator, which plays our queue's stream; a "−" on
  our coordinator promotes a member and the stream with its queue moves there -> the follow (§2.9) [D13, D14].
- **The cap**: a transfer and a pick are STARTs - every room of the row brought down to the cap inside the hush before
  Play; a step, an advance correction or the speaker's own advance never touch a volume. The group's volume rules, the
  joiner's cap and `capChanged` are unchanged.
- **The sleep timer** (minutes): the fade and Pause, unchanged. **"Til slutten"**: §2.7. **Calls**: `CALL_HOLD`'s pause
  and `CALL_RELEASE`'s resume are the same verbs on a queue. **Locks, disarm (10 min), the socket's lifetime**: unchanged.
- **The tail cadence** (5 s within 45 s of the end) catches a natural boundary within 5 s without a socket; with one,
  the `itemId` confirm does it within ~1 s.

### 2.12 Context mode on a room (round 54-c; Q3)

Most playlists a parent pastes are context mode in dev mode (26c §3.3: only playlists a family account owns or
collaborates on are listed), so this is the round that delivers the user's "hele playlisten" for an editorial or a
friend's playlist.

- A playlist context is a transfer of the playlist like list mode, with no listing: **no map**. Ours = vibb's prefix
  rule plus `GetMediaInfo`'s queue source at adopt (vibb's own rule; a guest who replaces our queue with other Spotify
  from the Sonos app reads as ours until the next transfer - vibb's accepted risk, §6).
- **Resume**: the context's live track (`spotify_context.json`, the store's, 26c §5.9) -> `findInQueue(uri)` (§2.3's
  paged Browse; ours - vibb had a listing) -> its position, at the synthetic item's saved place; not found -> position 1
  at 0 (26c's "one more play from the top").
- **The shown track**: `TrackMetaData`'s `dc:title` and `dc:creator` -> `"<title> — <artist>"` (the server's form)
  written as the live track through the same store port the web player uses, on a change only; the synthetic item's
  title follows it. No art from the speaker (the entry's cover).
- **Steps**: `stepsOnWebPlayer(entry)` routes next/previous to the room's controller (`queueStep(±1)`: `Seek TRACK_NR`
  at the live `Track` ± 1 through `play_from_queue`; previous more than 5 s in restarts at 0, vibb's rule). Shuffle for
  a context (design 49: Spotify's own on the phone) is the speaker's own here: `SHUFFLE_NOREPEAT` / `SHUFFLE` /
  `SHUFFLE_REPEAT_ONE`, and while shuffled a step is AVTransport `Next`/`Previous` (the speaker's own pick) instead of
  ±1.
- The bookmark: the synthetic item's position + the live track (26c's model), so phone <-> room continue on the same
  track (`context_uri` + `offset.uri` on the phone, §5.9). End: the queue's end -> ended (never marked done,
  `keepsDuration`). An artist context: refused (`CANT_PLAY_ON_SONOS`).

### 2.13 The Sonos household

- **What it needs**: Spotify linked in the Sonos app (Settings › Services › Spotify) - **the household's account**,
  often a parent's; it is the account that plays on the speaker, not the kid's phone account (26c step 8's rule is
  about our server and the phone; the Sonos app is outside it). A playlist the linked account can't open (a kid's
  private playlist) fails the add or adds nothing [D12]. **One stream per account**: the linked account playing
  somewhere else stops the room - the app then sees a stop it didn't make (followed as today: "Høyttaleren stoppet"),
  and nothing restarts by itself [D18]. Premium is assumed for track-level play [unverified; D12].
- **Detection** (`SonosHome.spotifyOn(uid)`, IO): `/status/accounts` from the coordinator's ip (as `NrkService`), parsed
  by `spotifyLinked(xml)`: `LINKED` = an `<Account Type="2311">` or `"3079"` without `Deleted="1"` (the type tried first
  is the one present, SoCo's order otherwise), `ABSENT` = the document parsed and has none, `UNKNOWN` = no answer. LINKED
  cached per ip for the process; ABSENT re-read after 60 s (a parent who just linked it). The real document's shape is
  [D1] (the fixture is hand-written).
- **What the kid sees**: ABSENT -> nothing is sent, the phone keeps playing, `SONOS_ASK_ADULT` "Kan ikke spilles på
  Sonos - spør en voksen" / "Can't play on Sonos - ask a grown-up". UNKNOWN -> the transfer is tried; a fault of the
  add on both service types -> the failed start (26a §4.2: back to the phone, paused) with the same words. A dropped
  kind (an artist, a context before 54-c) -> `CANT_PLAY_ON_SONOS` as today.
- **The parent** learns it from the kid, and from one line of copy on the PWA's Spotify page ("On Sonos a link plays
  through the Spotify account linked in the Sonos app") - server copy only, no API (§2.14). No report field (a contract).

### 2.14 Server, PWA, contracts, launcher, secrets, licences

- **Server/PWA**: no API, no migration, no policy key. The room scope (`sonos_rooms`) applies to Spotify unchanged. No
  parent switch (Q2's default). Optional: the Spotify page's one line of copy (§2.13) with its test - a server-only
  commit.
- **Shared contracts: none.** No change to `server/testdata/*` contract files, `music_state.json`, the policy JSON, the
  bridge AIDL or `bridge_contract.json`, `spotify_hosts.json`. The fake speaker reads `server/testdata/spotify/` as test
  data (§3), which is not a contract.
- **Launcher**: untouched. The phone sends only SOAP to the LAN; the speaker reaches Spotify itself, outside the
  phone's DNS filter and VPN. No token is asked for a room.
- **Secrets**: none new. `Authorization`/`Bearer` stay where they are. A TrackURI's `sn` is a serial, not a secret. Log
  lines name a target or one track uri, never the `listed` list.
- **Licences**: SoCo (MIT, the SoCo team) for the ShareLink table and template, `play_from_queue`, `queue_size` and
  `reorder_queue` - credited in each ported file's header and in `NOTICE.md`; vibb (MIT) as today.

## 3. Tests (lean; JVM, fixtures, the fake)

- **New UPnP fixtures** in `music/app/src/test/resources/sonos/` (hand-written in the shapes SoCo reads, marked
  provisional in `SonosFixturesTest`'s header until 54-0's capture replaces them in one commit): `add_uri_to_queue.xml`
  (`FirstTrackNumberEnqueued` 1, `NumTracksAdded` 3, `NewQueueLength` 3), `browse_queue_item.xml` (one `Q:0/2` item,
  `res` `x-sonos-spotify:spotify%3atrack%3a<id>?sid=9&amp;flags=8224&amp;sn=1`), `browse_queue_meta.xml` (`childCount`),
  `position_spotify_queue.xml` (`Track` 2, that TrackURI, a real `TrackDuration`, `TrackMetaData` with `dc:title`,
  `dc:creator`, a relative `albumArtURI`), `transport_settings.xml` (`PlayMode`), `media_info_queue.xml`
  (`CurrentURI` `x-rincon-queue:RINCON_...#0`), `accounts_spotify.xml` (a live 2311 and NRK), `fault_800.xml` (the
  add's refusal - code provisional, [D12]); the goldens `sharelink_{playlist,album,track,show,episode}.xml`; and
  `ws/queue_events.json` (the capture's shapes: a queue's status with `queueVersion` and `itemId`, a natural boundary -
  BUFFERING with a new `itemId`, `previousPositionMillis` the duration -, a pressed next mid-track, `playModes`).
  `SonosFixturesTest` parses each.
- `ShareLinkTest`: each type's `EnqueuedURI` and metadata byte for byte against its golden; artist and junk -> null;
  2311 then 3079.
- `SonosQueueTest` (`logic/`, pure): `trackSpotifyUri` (both prefixes, `+` kept, junk null); the probe - forward,
  reversed, untrusted, `len < n`, the start check; `position` and its clamp; the confirm's verification; `follows`
  (natural, flipped, shuffled, a show reversed over a reversed queue); `boundary` (socket: `previousPositionMs` within
  3 s of the duration; UPnP: `endsNear`); `modeFor` (and no `REPEAT_ONE` under "Til slutten"); `spotifyLinked` (live,
  deleted, absent, junk); 54-b's `slots` after moves (forward, backward, around the current).
- `SonosTransportTest` `d54`: a transfer's exact SOAP order (the hush around it, the cap before `SetPlayMode`, the gate
  before `SetPlayMode` and again before `SetAVTransportURI` - a closed gate sends nothing more; the 3079 retry; both
  faults throw with nothing played; the probe's Browses; `play_from_queue`'s three calls; the settled seek); `queuePlay`
  hushed as vibb's `sonos_hush.py` 5 (`mute, jump, seek 0:00:45, unmute`); `park` = Stop, `RemoveAllTracksFromQueue`,
  the queue uri.
- `SonosSessionTest` `d54`: classify for the queue kind (ours only for a listed uri; `x-sonos-vli:` and an unlisted
  Spotify track not ours; `trackNo`; no `lostSession`; no stall retry on a frozen position); vibb's
  `sonos_migrate_probe.py` 3 (no recorded track never follows; exact track + continuity follows; a discontinuity doesn't).
- `SonosControllerTest` `d54` - vibb's `sonos_spotify_queue.py` ported: (1) a start resumes the bookmarked track at
  its second with **one** transfer; (2) the shown item is our list's, never the speaker's `TrackMetaData`; (2b) a
  guest's Spotify is not ours; (3) a step is one `queuePlay` and never a new add; (4) a refused seek holds the
  bookmark until playback passes it; (5) an untrusted map makes the next press a transfer; (6) an artist is refused by
  name; (7) the step worker survives a failed play. Ours: an `itemId` change wakes an immediate confirm and no event
  measurement counts before it; the confirm moves `index` and `playing`, `autoFlag` only for a natural boundary; a
  pressed change is never corrected; a natural change into a non-following order sends one gated `queuePlay` (and a
  pause when `mayPlay` is false); the queue's end -> `ended` before `Track` is read; "Til slutten" pauses 400 ms
  before the end and the belt at a boundary; repeat -> `SetPlayMode`; the tile with an intact queue sends nothing or one
  resume, else a transfer; a pick on the loaded entry is a `queuePlay` (capped, no add); a superseded transfer's map is
  taken; adopt sends no transport command and learns the map with reads only.
- `SonosEventsTest` `d54`: `itemId`, `queueVersion`, `previousItemId`, `previousPositionMillis`, `playModes` parsed
  from the capture and `queue_events.json`; absent fields are null.
- `PlayBodyTest`/`RendererRulesTest`: the drop reasons by kind and mode; `SonosLifecycleTest`: `kindOfUri`,
  `sameStream` and `adoptIndex` for a queue marker, the standalone bookmark's item from the live uri.
- `GuardsTest`: `comeHome` reaches the web player for a Spotify entry; `toRoom`'s household check before
  `renderer.json`. `SourceScanTest`: the new words in both languages name no source; `x-rincon-cpcontainer` only in
  `ShareLink.kt`.
- **`scripts/fake-sonos.py`** (one truth: the queues come from `server/testdata/spotify/` - `playlist_items.json`,
  `album_tracks.json`, `show_episodes.json`, `track.json`, `episode.json` - so the fake Spotify's listing and the fake
  speaker's queue name the same tracks): `AddURIToQueue` (the ShareLink uris, answering from `add_uri_to_queue.xml`),
  `RemoveAllTracksFromQueue`, `SetAVTransportURI x-rincon-queue:`, `Seek TRACK_NR`, `SetPlayMode`/
  `GetTransportSettings`, `Browse Q:0` (both flags), `GetMediaInfo`, `ReorderTracksInQueue` (54-b), the queue's own
  advance (NORMAL, `REPEAT_ALL`, `REPEAT_ONE`; the end STOPPED), GetPositionInfo from `position_spotify_queue.xml`;
  `/status/accounts` from `accounts_spotify.xml`, `--no-spotify` from `accounts.xml` with the add answering
  `fault_800.xml`; `--queue-reversed` (a show's queue newest first); script events `next`, `previous` (the speaker's
  buttons), `queue-insert` (someone adds a track: drift), `shuffle` (the Sonos app); `--selftest` covers them. No socket
  (design 53: the emulator checks the polling path); `docs/testing/emulator.md` §6h gets the Spotify-on-a-fake-room loop
  (§6l's A2 debug feed with `fake-spotify.py --print-listing`, the fake speaker's queue from the same files).

## 4. Rounds (sequential, each with its code QA and fix round; branch per round)

- **54-0 - measure first** (the user's Pi, like design 53's capture; no app change): `scripts/sonos-play-test.py
  --spotify <link> [--index K --start S] --save DIR` - the accounts document; the transfer as SoCo (timed, for 50, 300 and
  1000 tracks); the probe's Browses; `Track`/`TrackURI`/`TrackDuration`/`TrackMetaData` while the queue plays; jumps by
  `TRACK_NR` playing, paused and stopped; a seek to 10 s before a track's end (the natural boundary) and before the last
  track's end (the queue's end); the play modes; a prompt to press Next and Previous on the speaker; 54-b's
  `ReorderTracksInQueue` while playing; with `sonos-ws-probe.py`'s watch alongside, every socket message saved; all
  scrubbed (`--rescrub`'s rules plus track ids) into `sonos/queue/` and `ws/queue_capture_<date>.json`. `--golden DIR`
  also writes the ShareLink goldens. Answers [D1-D11] before the Kotlin rounds read them; 54-a1 may start on the
  provisional fixtures meanwhile.
- **54-a1 - the pure rules, the SOAP and the fake** (nothing changes for the kid: `speakerDropReason` still refuses):
  `ShareLink.kt`, `logic/SonosQueue.kt`, `Kind.SPOTIFY_SHARELINK`, `Soap`'s ContentDirectory, `SpeakerControl`'s
  `addUriToQueue`/`browseQueue`/`queueSize`/`setPlayMode`/`mediaInfo`, `SonosTransport.playShare`/`queuePlay`/`setPlayMode`/
  `learnQueue`/`findInQueue` and the park's clear, `SonosSession`'s classify/probe for the kind, `SonosEvents`' new
  fields, the fixtures and goldens, `fake-sonos.py`, the tests above that need no controller.
- **54-a2 - list mode on a room** (the feature on): `SonosController` (`queue`, intact, the transfer vs `queuePlay`,
  `queueMoved`, the boundary, the 54-a correction, the end, `setRepeat`, the at-end pause, the `itemId` confirm, the
  adopt's `learnQueue`), `SonosRenderer` (`setModes`, `pauseAtItemEnd`), the service (`speakerDropReason`, `queueFor`'s
  household check and `SONOS_ASK_ADULT`, `comeHome`, `natural` keys handed to `load`), `SonosLifecycle`/`Suspension`,
  the strings, `GuardsTest`/`SourceScanTest`, emulator.md §6h, the docs (below). Delivers the user's ask for every
  list-mode playlist, album, track, show and episode.
- **54-b - our order without the blip** (Q1, gated on [D10]): `ReorderTracksInQueue`, `QueueMap.slots`, the placement
  after each confirmed track, the fake's reorder.
- **54-c - context mode** (Q3): §2.12.

Docs with 54-a2: `music/CLAUDE.md` ("Spotify is the third renderer": a room plays a Spotify entry through the
ShareLink, not "a room's queue drops Spotify"; a new rule block "Spotify on a room (design 54)" with the map, the
heal, ours, the park's clear and the household words; the DIDL rule's sentence naming the ShareLink metadata as SoCo's
template, pinned by goldens), design 26c §11 step 9 and §12/§13/§14 pointing here, design 26a's §9 row and its "not
ported" `sonos_migrate_probe.py` 3 / `sonos_hush.py` 5 note, `NOTICE.md` (SoCo), `PLAN.md`.

## 5. Owed device checks (the user's speakers and Pi, the Jelly Star, a debug build; logcat `VibbSonos`)

Through 54-0's script unless "the app" is named.

1. **D1** `/status/accounts` on a speaker of the house: the Spotify account's element (`Type`, `SerialNum`,
   `Deleted`), and whether a newer firmware still serves it (if not, the check reads UNKNOWN and the add decides).
2. **D2** The transfer: after `RemoveAllTracksFromQueue`, `FirstTrackNumberEnqueued` (1?), `NumTracksAdded`,
   `NewQueueLength` against `queue_size`; the time of the add for 50, 300 and 1000 tracks (the hush and the
   kid's wait; `HANDOFF_MS` 25 s and `SWITCH_WAIT_MAX_MS` 35 s already cover a slow mint).
3. **D3** A Browse item's `res` against `GetPositionInfo`'s `TrackURI` for the same position (the same decode).
4. **D4** While the queue plays: `Track` 1-based, `TrackDuration` real, `TrackMetaData`'s title and creator.
5. **D5** `Seek TRACK_NR` while PLAYING, PAUSED and STOPPED; the settled REL_TIME seek after it (701), heard muted.
6. **D6** A natural boundary: the socket's messages (a new `itemId`? `previousPositionMillis` near the length?
   `queueVersion` unchanged?), UPnP's view, whether the advance is gapless; the 54-a correction's audible length.
7. **D7** The queue's end under NORMAL: the transport, `Track` and `TrackURI` after it, the socket's IDLE.
8. **D8** `REPEAT_ALL`/`REPEAT_ONE` on a queue, and the socket's `playModes`; the house's leftover mode.
9. **D9** The speaker's own Next and Previous (the button, the Sonos app) on our queue: the app follows within ~1 s
   with the socket and at the next poll without; no done mark, no correction (the app).
10. **D10** `ReorderTracksInQueue` while playing (54-b): the current track plays on without a gap; the positions after
    the move; the speaker's next is the moved track.
11. **D11** A show's and an episode's ShareLink: the queue's order (newest first?), the TrackURI prefix
    (`x-sonosprog-spotify:`?), a resume seek inside an episode.
12. **D12** A playlist the linked account can't open, and a household without Spotify (if one can be tried, e.g. a
    second household or a speaker reset): the add's fault code or `NumTracksAdded 0`; the app's words.
13. **D13** Hold-Play our coordinator out of a group while the queue plays: the queue moves to the member with the same
    positions; the app follows ("stream moved") and the next press is a `queuePlay` (the app).
14. **D14** "+" a room while the queue plays: it joins the stream; "−" a member: the rest play on (the app).
15. **D15** Hand-over at the same track and second: the web player -> Stua, Stua -> the web player (playing only if Stua
    was), Stua -> Kjøkken (the app).
16. **D16** The suspension (a time rule) while the queue plays: stopped, the queue cleared, the speaker's own Play button
    plays nothing (the app).
17. **D17** An app restart while the queue plays: adopted with no transport command (logcat), the map re-learned, the
    next press a `queuePlay` without a gap (the app).
18. **D18** The linked account started on another device while the room plays: what the room does, what the app shows.
19. **D19** The cap at a transfer (a room at 80 with the cap 40 starts at 40, no loud blip), the minute timer's fade,
    "Til slutten" pausing at the track's end, a call's pause and resume (the app).
20. **D20** Rapid steps (five nexts within 2 s) on a playing queue: one `queuePlay` per settled want, the title never
    flaps back (design 53's check 2 for a queue; the app).

## 6. Deviations from vibb

| vibb | Here | Why |
|---|---|---|
| the listing is go-librespot's, in the queue's order; the map is positional from `q_base` | the server's listing; the map has a direction and is probed by one or two one-item Browses | our listing may be the reverse of the queue (a show); one read instead of a guess |
| trusted iff `qlen == len(rows)` | `len >= n`, the first track (and the start's) verified | a listing capped at its first N still maps; the first play lands exactly |
| an untrusted start plays `base - 1 + idx` | `findInQueue` (a paged Browse) for the start, else the first track | the start is the kid's track, not a guess |
| `ours` = any Spotify queue TrackURI (the prefix) | list mode: the decoded uri is in our listing; context mode: the prefix (vibb's) | a Sonos-app Spotify queue that isn't ours is never bookmarked or advanced |
| NORMAL always | NORMAL, `REPEAT_ALL`, `REPEAT_ONE` from the entry's repeat (design 49); a context's shuffle the speaker's own | the user's repeat and shuffle; the three list modes keep play order = index order |
| Spotify's order only; `sonos_step` wraps `% n` | our order (the parent's, the flip, shuffle) through the correction (54-a) or the placed next (54-b); the service's skip rules | designs 39, 49 and 50 are the user's |
| a track change the speaker made is adopted at the next poll (5-15 s) | the socket's `itemId` wakes the confirm at once; natural vs pressed by `previousPositionMillis` | design 53's events; the user's expectation for the speaker's buttons |
| the park: Stop + the queue uri | Stop + clear + the queue uri | the queue holds the playlist: its Play button must not play it in a time rule |
| no household check | `/status/accounts` -> "spør en voksen" before any SOAP | the kid gets a reason, not a silent failure |
| a context resumes through go-librespot's listing | through `findInQueue` of the live track (54-c) | no listing on the phone |

## 7. What this design does NOT do

- No change to RSS/NRK podcasts or Storytel on a room (one URI, our queue, vibb's DIDL) - the user's rule.
- No Spotify Connect to the speaker (`x-sonos-vli:`), no Web API call for a room, no web player, no token.
- No artist on a room (no ShareLink type). No Liked Songs, no Sonos playlists, no Sonos favourites.
- No art or metadata fetched from the speaker; no `TrackMetaData` shown in list mode.
- No parent switch, no report field, no contract change (Q2).
- No fight with the Sonos app: a play mode, a shuffle, a queue edit or a volume set there is followed, never undone
  until the kid's next explicit press.

## 8. Questions for the user (each with the default this design builds)

- **Q1. Our order on a room** - a shuffled, flipped or "newest first" list: the speaker's queue is in Spotify's order.
  **Default: our order, by moving the next track into place in the speaker's queue right after each track starts
  (round 54-b: gapless, and the speaker's own Next and Previous follow our order)**; until 54-b, a short correction at
  each track change (about a second of the speaker's next track). Alternatives: keep the correction only (no 54-b); or
  play a room in the playlist's own order and use the speaker's own shuffle (simpler, but the list the kid sees and
  what plays next differ).
- **Q2. A parent switch for Spotify on Sonos** - **Default: none.** It plays whenever the Sonos app has Spotify linked,
  on the rooms the room scope allows, with the linked account (often a parent's: the kid's music shows in that
  account's history, and that account playing elsewhere stops the room). A switch would be a policy key, a launcher
  change and a contract.
- **Q3. Context-mode playlists on a room** (an editorial or a friend's playlist, which no family account may list) -
  **Default: yes (round 54-c)**, resuming at the last track by finding it in the speaker's queue, titled from the
  speaker. Alternative: refused on a room ("Kan ikke spilles på Sonos") until a family account collaborates on it.
- **Q4. Spotify shows and episodes on a room** - **Default: through the ShareLink like music (vibb did)**, each episode
  resuming at its own place; RSS/NRK podcasts and Storytel keep their direct URI. Alternative: refuse Spotify shows on
  a room.
