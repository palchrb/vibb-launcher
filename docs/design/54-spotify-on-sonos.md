# 54 - Spotify on Sonos in the Vibb music app: vibb's `spotify_sharelink` (architect; revised after QA and the 54-0 measurement)

Architect draft (2026-10-21), revised the same day after `docs/design/qa-54-design.md` (3 High, 6 Medium, 5 Low - all
taken, see "Revision after QA") and the user's measuring run on the Pi (§0.3). This is design 26c's "later step 9"
(§11), now its own note. The user's decision of 2026-10-21: "Jeg er ganske så sikker på at jeg har lagt inn at Vibb pi
sender en spotify sharelink direkte til høyttaleren og da spilles den av på nydelig vis ... Da får man med hele
playlisten og det er bare godt stemning!" - and, for the scope: "jeg er ikke interessert i å endre i kø-logikken fra
vibb for podcasts". **RSS/NRK podcasts and Storytel are untouched:** one URI set directly on the speaker, our queue on
the phone, vibb's `url`/`nrk_program` path and DIDL exactly as today.

Inputs: `CLAUDE.md`, `music/CLAUDE.md` (the user's rules: behaviour follows vibb branch `pipewire` except the user's
decisions; nothing starts by itself on Sonos; every play is gated; the DIDL is vibb's and never extended), designs
26a (Sonos), 26c (Spotify on the phone; §1's table row and §11 step 9), 49 (shuffle and repeat), 50 ("Til slutten"),
53 (the event socket, the anchor, grouping - built, with its status sections), PLAN's design 53 entry and "Design 54's
measuring run done early". vibb `pipewire`: `pi/sonosd.py` (`Session.play`'s `spotify_sharelink` branch 520-566,
`verb("queue_play")` 664-690, `_classify` 688-810, the stall rule 895-899, `_probe_migration` 953-1000), `pi/daemon.py`
(`sonos_map_trusted` 531, the same-tile `intact` 950-1000, `_sonos_bookmark_now` 1330-1364, `_sonos_play_entry`
1477-1520, `_sonos_start_spotify` 1572-1665, `sonos_step` 1726-1790, `status` 2745-2820, the adopt and `_sonos_poller`
4497-4760, `_sonos_on_term` 5631-5655), `tests/sonos_spotify_queue.py` (7 cases), `tests/sonos_migrate_probe.py` case
3, `tests/sonos_hush.py` case 5, `tests/sonos_contract.py`; SoCo (MIT) `soco/plugins/sharelink.py` and `soco/core.py`
(`play_from_queue`, `clear_queue`, `queue_size`, `play_mode`, `PLAY_MODES`, `reorder_queue`); the captures
`music/app/src/test/resources/sonos/ws/capture_2026-10-21.json` (direct URIs) and the 54-0 run's
`ws-spotify-2026-10-21.json` + `spotify-playtest-2026-10-21.txt` (a Spotify queue; session scratchpad, to be scrubbed
into fixtures by 54-a1); the server's listing (`server/src/spotify.rs`); the music app's `sonos/`, `spotify/`,
`playback/`.

`M/` = `music/app/src/main/java/me/vibb/music/`. Markers: **[D n]** = an owed device check (§5); **[vibb]** = ported
behaviour; **[ours]** = a deviation, listed in §6.

## 0. Facts

### 0.1 What vibb does (`pipewire`)

- **The speaker holds the queue, vibb owns the logic** (v2, the owner's decision 2026-08-09): one ShareLink add of the
  whole context, then positional jumps. `Session.play(kind="spotify_sharelink")`: `play_mode = "NORMAL"` (errors
  ignored - "NORMAL kills family shuffle/repeat leftovers AND makes the queue's play order equal its index order - the
  legality of every positional jump rests on this"), `clear_queue()`, `ShareLinkPlugin(spk).add_share_link_to_queue(uri)`
  whose answer is `FirstTrackNumberEnqueued` = `q_base` ("never assume the queue starts at 1, even after clear_queue";
  unparsable -> 1), `q_len = spk.queue_size`, `play_from_queue(q_base - 1 + track_index)`, all inside `_hush`, then
  `_seek_settled` for a start of 5 s or more.
- **`queue_play {index, start_s}`**: `absidx = base - 1 + index`, clamped to `[base - 1, base - 2 + q_len]`;
  `play_from_queue(absidx)` and the settled seek, hushed (`sonos_hush.py` 5).
- **Classify**: `ours` for the sharelink kind is a **prefix rule** - the TrackURI starts with `x-sonos-spotify:` or
  `x-sonosprog-spotify:`. Extra fields: `track_no` (`Track`, 1-based), `base`, `queue_len`, `track_spotify_uri` (the
  TrackURI after the first `:`, before `?`, percent-decoded), and `track_title`/`track_artist`/`track_art` from
  `TrackMetaData`. No `lost_session`, no DIDL check, **no stall retry** for this kind. The migration probe follows a
  sharelink stream **only on the exact decoded track and position continuity** (`base - 5 <= rel <= base + 45`),
  never on the prefix, and never with no recorded track (`sonos_migrate_probe.py` 3).
- **The start** (`_sonos_start_spotify`): the listing is go-librespot's `context_tracks` (the context in Spotify's own
  order); the bookmark's track picks `idx` and its position `start_s`; then `sonos_map_trusted = (qlen is None or qlen ==
  len(rows)) and not listing.stale`.
- **Steps and picks** (`_sonos_play_entry`): checks **only** `sonos_map_trusted` - trusted -> `/queue_play`; refused ->
  `sonos_map_trusted = False` ("do NOT advance sonos_idx onto a track that is not playing"); untrusted -> **a fresh
  transfer at the wanted track on this explicit press, never on a timer** (arch R3a). `sonos_step` coalesces a mash and
  wraps (`% n`); prev more than 5 s in restarts.
- **The poller's tick**: a different `track_spotify_uri` within 8 s of a jump is the old track; then
  `track_spotify_uri` is "the inbound authority": its row becomes `sonos_idx`; a uri not in the listing while ours ->
  untrusted; a missing uri leaves the index. The bookmark every tick, throttled to 25 s (the app writes at most every
  5 s, design 53 - ours and fine). The sharelink kind never runs the `url` kind's STOPPED-near-the-end advance.
- **The card** reads vibb's list; only with the map untrusted or the index unknown does it show the speaker's DIDL title
  and art ("demoted fallback, never the primary", `sonos_spotify_queue.py` 2). A track of the queue vibb's list
  doesn't name stays **ours** (the prefix) and plays on.
- **The same tile** (`play()` 963-975): `ours`, fresh and "not settling" plus `sonos_map_trusted` - a tap on the
  playing entry is a no-op or one resume only for a steady live session of ours; "a sharelink press with
  sonos_map_trusted False is the RE-SYNC and must not be swallowed" - the full transfer is the heal.
- **Shutdown** (`_sonos_on_term`): a Stop gated on the prefix rule (every track of the queue counts). vibb has no park
  and no time rule.
- **Not in vibb**: any play mode but NORMAL, shuffle on Sonos, a reversed order, a Sonos account check, the speaker's
  own Next/Previous verbs.

### 0.2 SoCo's ShareLink, exactly (what the app ports - it has no SoCo)

- `SpotifyShare.canonical_uri`: `spotify.*[:/](album|episode|playlist|show|track)[:/](\w+)` -> `spotify:<type>:<id>`;
  **no `artist`** (vibb's own check lets an artist through, SoCo then raises: an artist never played on vibb's Sonos).
  `extract`: every `:` of the canonical uri replaced by `%3a` (lower case).
- `magic()` per type (`EnqueuedURI` = prefix + encoded; the metadata's item id = key + encoded; class):

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
- The service number `sn` is a **service type**: `2311` (Spotify, sid 9: 9 x 256 + 7) first; **only on a UPnP fault**
  (SoCo retries after a `SoCoException`) the next, `3079` (Spotify US, sid 12); the second's fault is the failure. A
  network failure (a timeout, a refused connection) ends the add at once - no second try.
- `AddURIToQueue(InstanceID 0, EnqueuedURI, EnqueuedURIMetaData, DesiredFirstTrackNumberEnqueued 0, EnqueueAsNext 0)`
  -> `FirstTrackNumberEnqueued` (SoCo returns only this), `NumTracksAdded`, `NewQueueLength`.
- `play_from_queue(index)`: `SetAVTransportURI("x-rincon-queue:<uid>#0", "")`, `Seek(Unit TRACK_NR, Target index + 1)`,
  `Play(Speed 1)`. `clear_queue` = `RemoveAllTracksFromQueue`. `queue_size` = ContentDirectory `Browse(ObjectID "Q:0",
  BrowseFlag "BrowseMetadata", Filter "*", StartingIndex 0, RequestedCount 1, SortCriteria "")` -> the container's
  `childCount` (**six arguments**; a speaker may answer 402 without `SortCriteria`). `get_queue` = the same with
  `BrowseDirectChildren`, an item's `res` holding its track uri; every Browse answer carries `UpdateID`. `play_mode` =
  `SetPlayMode(NewPlayMode)`. `reorder_queue(start, n, insert_before)` (all 0-based) = `ReorderTracksInQueue(StartingIndex
  start + 1, NumberOfTracks n, InsertBefore insert_before + 1, UpdateID 0)`.

### 0.3 The 54-0 measurement (the user's Pi, 2026-10-21; speaker firmware 86.10)

`sonos-play-test.py --volume 0 --spotify <an editorial playlist link>` with `sonos-ws-probe.py --save` alongside (53
socket messages):

- **`/status/accounts` is empty** (`<ZPSupportInfo></ZPSupportInfo>`) although Spotify plays: no account check is
  possible on this firmware (and `NrkService.serial` lives on its `sn=14` fallback there - PLAN's NRK item).
- **The add**: `sn=2311` took the ShareLink in **0.8 s**: `FirstTrackNumberEnqueued` 1, `NumTracksAdded` 30,
  `NewQueueLength` 30; `GetMediaInfo` `NrTracks` 30. TrackURIs `x-sonos-spotify:spotify%3atrack%3a<id>?sid=9&flags=8232&sn=6`.
  An **editorial playlist** (one no family account may list in dev mode: context mode for us) played through the
  household's account.
- **Jumps**: `TRACK_NR` works while PLAYING and while PAUSED (paused, it stays paused on the new track at 0:00).
- **The natural advance** is gapless; the socket saw it **0.5 s before UPnP** (`playbackStatus` PLAYING with a new
  `itemId` and **no BUFFERING**; `previousPositionMillis` 230998 for a 230000 ms track). A jump of ours goes BUFFERING ->
  PLAYING (`previousPositionMillis` the old place).
- **`queueVersion`** moves **only when the queue is edited**: 37 (the household's old queue) -> 39 after our clear + add
  -> 40 after the park's clear; unchanged through jumps, pauses, the natural advance and play mode changes.
- **`metadataStatus` on a Spotify queue names the track**: `currentItem.track.id.objectId` = `spotify:track:<id>`
  (`serviceId` 9, `accountId` `sn_6`), `name`, `artist.name`, `album.name`, `imageUrl`, `durationMillis`; `nextItem` the
  same for the queue's next; `container.id.objectId` = **`spotify:playlist:<our id>`**, `container.type` `playlist`. It
  comes with every change (after the `playbackStatus` at a natural advance, with the BUFFERING at a jump).
- `availablePlaybackActions.canSkip`/`canSkipToPrevious` true on the queue (`canSkipToPrevious` false at position 1).
- **Play modes**: `REPEAT_ALL` and `NORMAL` set at once and show at once in `playModes.repeat`. The household's parked
  queue carried `repeat: true` (also in the first capture): vibb's NORMAL is needed in this house.
- `itemId` changes with every track and is stable per queue position (the scrub keeps it consistent: track 3 was the
  same id twice). `previousItemId` is **not** the last `itemId` (another id space): never read.
- Not measured yet: a Browse of the queue, the queue's end, the speaker's own Next/Previous, `REPEAT_ONE`,
  `ReorderTracksInQueue`, a show, a refused or empty add [D3, D7, D9, D8, D10, D11, D12].

### 0.4 What the app does today (read 2026-10-21)

- `PlayBodies.speakerDropReason` (`M/sonos/PlayBody.kt:130`): a Spotify entry's item is `"spotify"`, so a room's queue
  is empty: `queueFor` emits `CANT_PLAY_ON_SONOS` and the phone keeps playing. `comeHome` and `phoneRendererFor` assume
  a Spotify entry never reaches a room.
- One SOAP play per item; a step is a new play; the advance is `pollTick`'s STOPPED near the end or the socket's IDLE.
- `Kind` is `URL`, `NRK_PROGRAM`. `sameStream` is the ours rule, the probe's match and adopt's; `adoptIndex` finds an
  item by its built uri; `storedUri` stores the built uri. The stall retry and `lostSession` apply to every kind.
- **Every gate that stops the speaker reads `snap.ours`**: the park's `parkGate()` (`SonosController.kt:1636-1640`),
  `handOff`'s Stop (`:1618`), `abandon()` (`:1658`), the standalone suspension's `roomVerdict` (`SonosLifecycle.kt:48`);
  a snapshot not ours is TAKEN_OVER (`:459`) and `spec()` shows it paused (`:515`). `stepTo` sets `pending` before its
  worker plays (`:930`). `lost()` emits `CANT_PLAY_ON_SONOS` unconditionally (`PlaybackService.kt:2289`).
- No `AddURIToQueue`, `Browse`, `SetPlayMode`, `ReorderTracksInQueue`, `GetMediaInfo`, `GetTransportSettings`; `Soap` has
  no ContentDirectory. `SonosTransport.park` = Stop + `x-rincon-queue:<uid>#0`.
- `SocketEvent.Playback` (`SonosEvents.kt:61`) carries state and position only; `metadataStatus` is not parsed.
  `MigrationProbe.live` returns (state, TrackURI) - no position. `SonosHome.log` is `Log.i` in a release build.
- After `suspendStop` the service comes home; a park that ended NO_ANSWER is retried only at the next start (26a M1).

## 1. Shape

A Spotify entry on a room is vibb's `spotify_sharelink`: **one ShareLink add of the whole playlist, album or show into
the speaker's own queue, then positional jumps; the speaker plays the queue by itself** (gapless, the household's
Spotify account linked in the Sonos app, no token, no Web API, no web player). The app **reads the queue once** after
the add to know which position holds which of our items (the map). The phone keeps what it has for the kid - our list,
our numbers and pictures, our bookmarks per track, our skip rules - and **follows** the speaker: a track change the
speaker made (its own advance, its buttons, the Sonos app) is confirmed by UPnP (`Track` + the decoded `TrackURI`),
found quickly because the socket's new `itemId` and its `metadataStatus` (which names the Spotify track) wake the
confirm polls. Design 53's rule holds: UPnP commands, verifies and bookmarks; the socket wakes, pairs and cross-checks.
**Every play stays ours and gated**: NORMAL play mode (the speaker never wraps or repeats by itself), repeat done by our
gated advance, and a belt that pauses the speaker's own advance whenever playing isn't allowed.

## 2. Decisions

### 2.1 What may go to a room

- **List mode** (26c §3.3): speaker-playable when the target's kind has a ShareLink type - `playlist`, `album`,
  `track`, `show`, `episode`. **An artist never** (no ShareLink type). `speakerDropReason` returns null for the five,
  `"spotify artist"` for an artist.
- **Context mode** (26c §5.9): `"spotify context"` until round 54-c (§2.12), then a playlist context passes and an
  artist context stays refused.
- **Shows and episodes** go by the ShareLink too (vibb did; Q4). RSS and NRK podcasts and Storytel are not touched.
- **No household pre-check** (§2.13): the add's answer decides.
- Refused by kind: the phone keeps playing (`toRoom`'s "nothing speaker-playable" path), `CANT_PLAY_ON_SONOS` ("Kan
  ikke spilles på Sonos"). Refused by the speaker (§2.2): the failed start, `SONOS_ASK_ADULT` ("Kan ikke spilles på Sonos
  - spør en voksen"). Neither names a source (26c step 8; `SourceScanTest`).

### 2.2 The transfer (vibb's `/play` for `spotify_sharelink`; `SonosTransport.playShare`, in the actor)

`Kind.SPOTIFY_SHARELINK("spotify_sharelink")` joins `URL` and `NRK_PROGRAM` (`builtDidl`'s `when` gets a throwing branch
for it: its metadata is §0.2's, never `didl()`). Its body (`PlayBody` with `uri` = the canonical target
`SpotifyRef.target`, and two new fields): `trackIndex` (the start item's index in the **listing's own order**) and
`listed` (the listing's track uris in that order, `uriOf(kind, key)`; never logged - `PlayBody.toString` leaves it
out). The sequence, under the transport's mutex:

1. `hush(startS)` (vibb: the whole play is inside it).
2. On a START: the cap (`capVolume`, every room of the row; §2.11) - as `play`.
3. **`gate()`** (the first command that changes the room comes next).
4. `SetPlayMode(NORMAL)` (errors ignored, vibb's `try`; §2.7).
5. `RemoveAllTracksFromQueue`.
6. `AddURIToQueue` with `shareLinkUri(ref)` and `shareLinkMetadata(ref, 2311)`; on a `UpnpFault` the same with `3079`
   (§0.2). The second fault, a network failure, or an answer with `NumTracksAdded` 0 or `NewQueueLength` 0 throws
   `ShareRefused` before any Seek: a failed start (§2.13). `first` = `FirstTrackNumberEnqueued` (unparsable -> 1), `len`
   = `NewQueueLength`, else vibb's `queue_size`.
7. **The start's position** (§2.3): `findInQueue(listed[trackIndex], near = first + trackIndex)` - one
   `BrowseDirectChildren` page of 100 around the guess, then the others until the hit, at most 10 pages; not found ->
   position `first` at 0 (vibb's "hit is None -> idx 0").
8. **`gate()`** again, then `play_from_queue(position)` as SoCo: `SetAVTransportURI(queueUri(uid), "")`, `Seek(TRACK_NR,
   position)`, `Play`.
9. `session.played(queueUri(uid), hint)` and `session.shareSet(target)`; armed.
10. A start of 5 s or more: `seekSettled` (REL_TIME); false arms the controller's refused-seek hold, as today.
11. Unhush. `PlayResult(uri = queueUri(uid), queue = QueueRead.partial(first, len, the pages read), ...)`.
12. **Then, as its own actor job outside the hush** (`readQueue`): the rest of the queue's pages (§2.3) -> the full map.

`shareLinkUri`/`shareLinkMetadata` are pure (`M/sonos/ShareLink.kt`, credited to SoCo). The goldens
`sonos/sharelink_<type>.xml` are written **by the real `ShareLinkPlugin`** on the user's Pi (vibb's venv has SoCo):
`sonos-play-test.py --golden DIR` drives it with a recording `avTransport` and saves `AddURIToQueue`'s arguments; the
script's own transcription is the fallback only without SoCo, and each file says which wrote it. **This is the
metadata vibb's sidecar sends through SoCo, field for field**; the user's DIDL rule applies to it the same way -
nothing added.

### 2.3 The map: one read of the queue (`QueueMap`, `M/logic/SonosQueue.kt`)

vibb's listing was go-librespot's own context, so `base + idx` held. Ours is the server's: it drops local files,
unplayable tracks and episodes inside a playlist (`spotify.rs:2385-2397`, `:2480-2494`), lists relinked tracks under
their relinked ids (`:1965-1984`), caps a list at 1000 tracks and a show at its 100 newest episodes (`:105`, `:107`), and
a kids' playlist often holds a track twice. A position formula drifts from the first gap. So [ours]:

- **The read**: paged `Browse Q:0 BrowseDirectChildren` (100 a call, at most 10 calls - the server's cap), each item's
  `res` decoded by `trackSpotifyUri` (§2.5), and the answer's `UpdateID`. The start needs only the page of its track
  (§2.2 step 7); the rest is read right after Play, off the hush.
- **`slots`**: for each item of our listing, its queue position or none - duplicates matched in order (the k-th
  occurrence of a uri in our listing to the k-th in the queue); exact for gaps, extras, either direction and any cap.
  `itemAt(position)` is the inverse. No direction field, no formula.
- **An item with no slot** can't play on that room: a step skips it the way an unplayable item is skipped
  (`playableIndex`'s rule, in the step's direction); a pick of it is refused with `CANT_PLAY_ON_SONOS`, nothing changes.
- **`version`**: the queue's version at the read - the socket's `queueVersion` (it moves only on an edit, §0.3), else
  the Browse's `UpdateID` [D10: whether the two are the same number]. **Trusted** = a read exists for this entry and uid
  and the queue's version hasn't moved since it, except by an edit of ours (our 54-b moves re-read).
- **The confirm verifies**: an ours poll's `Track = p` and uri `u`; trusted and `u != listed[itemAt(p)]` -> untrusted
  (logged once). The index comes from `Track` through the slots; the uri only verifies it. Untrusted, the item is found
  by uri, the occurrence nearest the expected one.
- **Re-read, never re-added**: a `load` of the same entry with a changed listing rebuilds the slots from the read it has
  (a Browse again only when the version moved); so do adopt's `learnQueue`, a follow and every 54-b move.
- **Untrusted is healed only by an explicit press** - a step, a pick, a tile tap - by a **fresh transfer** at the wanted
  track (vibb R3a), never a timer, never the speaker's own advance. With the slots exact, untrusted now means "the queue
  was edited outside the app" (a queue edit from the Sonos app, a reorder fault), not "our listing differs": the heal
  happens once.
- **A superseded transfer still replaced the speaker's queue**: the controller takes the map of every transfer that
  landed, in actor order, generation or not (as `playing` today).

### 2.4 Steps, picks and the tile (vibb's `queue_play`; `SonosTransport.queuePlay`)

- `queuePlay(position, startS, cap, members, gate)`: hush, the cap on a START only, `gate()`, `play_from_queue(position)`,
  the settled seek for 5 s or more, unhush (`sonos_hush.py` 5). `session.stampVerb()`.
- **Steps, picks, the advance and corrections need only the map trusted, this entry and this uid** (vibb's
  `_sonos_play_entry`): trusted -> `queuePlay`; untrusted -> a transfer. This covers a step (`stepTo` while playing, the
  coalescing worker - one per settled want, never wedged; `PlayWhy.STEP`, no cap), a second step inside the first one's
  settle (still a `queuePlay`), the gated advance and corrections (§2.6-§2.7, `PlayWhy.ADVANCE`), a pick in the list or
  a carousel swipe on the loaded entry (`load` of the same entry keeps the map; the play is a START: capped, hushed, at
  the item's saved place - no clear, no add), and the first play after a skip on a paused room.
- **The tile alone** (`playTile`, vibb's same-tile) also needs ours, fresh and nothing settling: steady + PLAYING ->
  nothing; steady + PAUSED -> one resume; anything else -> `restart()`, which for this kind is always a **transfer** (the
  heal). `playPause`'s "not live" restart likewise.
- **A refused `queuePlay`** (a fault): untrusted, the index stays on what plays; the next press heals.
- Skip rules are the service's (`skipTarget`; no wrap without repeat all, design 49) - not vibb's `% n`.

### 2.5 Following the speaker (ours and mapped, the confirm, the boundary)

- **`trackSpotifyUri(trackUri)`** (pure): `x-sonos-spotify:` or `x-sonosprog-spotify:` -> the part after the first `:`,
  before `?`, `pyUnquote`d (vibb's expression); anything else null. The same decode reads a Browse item's `res`.
- **Two meanings, split** [vibb's, made explicit]:
  - **`ours`** (the session - the speaker plays the queue of our transfer): `trackSpotifyUri` is not null (vibb's
    prefix) **and** the queue isn't known to be someone else's: the socket's last `metadataStatus` container is our
    target, and its `queueVersion` hasn't moved except by us; without a socket, the last read's `UpdateID` stands (an
    unmapped track triggers one `Browse Q:0 BrowseMetadata` to compare it). Every Stop, park, lock, disarm, follow and
    "taken over" gate reads this - an unlisted track of our playlist is still ours, so the park stops it.
  - **`mapped`** (the item): the polled `Track` holds one of our items through the slots, its uri verified. The index,
    the bookmark, the done mark and the now-playing row read this. A track that is ours but not mapped gets no bookmark
    and no index move; what it shows is Q6 (default vibb's: §2.8).
- **`SonosSession.classify` for this kind**: `ours` as above (the session holds the target and the version it read;
  the controller feeds it the socket's container and version), `Fields.trackNo`, `Fields.trackSpotifyUri`; no
  `lostSession`, no DIDL check, and `stallBookkeeping` returns null for this kind (vibb 895-899). `lastOursTrack`/
  `lastOursRel` kept on every ours + live sighting.
- **The socket's parts** (`SocketEvent.Playback` gains `itemId`, `queueVersion`, `playModes`, `previousPositionMs`; a new
  `SocketEvent.Metadata` carries `currentItem`'s Spotify uri, name, artist, `durationMillis`, `nextItem`'s uri and the
  container's uri, parsed only for `serviceId` 9 or a `spotify:` object id):
  - an **`itemId`** different from the one paired with `playing`, while a queue session of ours plays and no play of
    ours is pending, is a track change the speaker made: a confirm poll **at once**, then **re-polls every
    `IDLE_RECHECK_MS` until a poll names a uri other than `playing`'s - the one the socket's metadata named, when it
    did - or `IDLE_RECHECK_UNTIL_MS`** (design 53's IDLE recheck, qa-53a-code 4: UPnP lags 0.3-2.6 s; 0.5 s measured);
    `wsBlocked` clears only on that confirming poll, never on a lagging poll of the old track;
  - **pairing**: `playing` is paired with the `itemId` seen when it was confirmed; a socket measurement (PLAYING/PAUSED
    with `positionMillis`) counts only when its `itemId` equals the pair - so the new track's 0:00 never re-bases the
    old track's bar;
  - the metadata's track, container, `nextItem` and `previousPositionMillis` are **cross-checks** (logged in a debug
    build when they disagree with UPnP); they decide nothing that UPnP decides.
- **`pollTick` for this kind**, after the existing landing, disarm, accept, `optTr`/`optPos`, the follow and the
  `fresh && ours` gate, in this order:
  1. **The belt** (§2.9): `mayPlay()` false on an ours PLAYING poll -> one Pause (once per generation and track).
  2. **STOPPED near the end** (`endsNear > 0`) is the queue's end (§2.7) - before anything reads `Track` [D7].
  3. **A track change** (the polled uri isn't `playing`'s, no play of ours pending) -> `queueMoved`: `index` and
     `playing` follow `itemAt(Track)` (`Playing(entryId, index, key, polled TrackURI)`, paired with the socket's current
     `itemId`), `endsNear = 0`, the map verified; not mapped -> the unmapped look (§2.8), the index stays.
     **Natural or pressed** (`boundary`, pure, on our own measurements): natural iff the previous track's anchor,
     extrapolated to the change's time (the `itemId` event's `at`, else the confirming poll's `measuredAt`), is within
     `NATURAL_END_MS` (3 s) of its duration; without a socket iff `endsNear > 0` (a press in the last 20 s counts as
     natural - it only adds a done mark). Natural -> `autoFlag` (the service marks the finished track done), then §2.6
     and §2.7. Pressed (the speaker's buttons, the Sonos app) -> followed as it is: no done mark, no correction.
  4. The bookmark (§2.8), then `endsNear` as today.
- **The pending play** of a `queuePlay` or a transfer carries the target's Spotify uri and lands when a poll's
  `trackSpotifyUri` equals it (`sameTrack`), else at `SETTLE_MS` after the answer; until then a different track is the
  old one (vibb's 8 s settle).

### 2.6 Our order on a room

The speaker's own advance is the queue's next position (NORMAL keeps play order = index order). Our next is the next
item of **our** order: the parent's order setting, the kid's flip (design 39) and shuffle (design 49).

- **`follows`** (pure, per `load`/`reorder`/`setModes`/re-read): for our current item, the next queue position holds
  our next item (through the slots). While it does, the speaker's own advance is ours: gapless, nothing sent. A show
  listed newest first over a newest-first queue follows; a flipped or shuffled playlist doesn't.
- **When it doesn't** (round 54-a, the correction): at a **natural** boundary whose new track isn't our expected next,
  one `queuePlay` of our next at 0 (`PlayWhy.ADVANCE`, gated) - **but first the mode**: the socket's last `playModes`, or
  one `GetTransportSettings` without a socket; a mode we didn't set (shuffle or repeat-one from the Sonos app) is
  followed, never corrected. About 0.5-1.5 s of the speaker's next is heard [D6]: for a shuffled kids' list, every track.
  A pressed change is never corrected.
- **Round 54-b** (Q1's default when [D10] holds): **one reorder of the rest of the queue into our order**. Right after
  a start (and again after `reorder` - a flip, a shuffle), a background actor job places our items after the current
  one in our order with `ReorderTracksInQueue` (one call per item out of place, 50-100 ms each, the job yielding to steps
  between calls), the slots updated per move and re-read at the end (the version after it is ours). Then the speaker's
  own advance, its Next/Previous and the Sonos app's queue view all follow the kid's list. Skipped while a mode we
  didn't set is on; a fault -> untrusted, back to the correction.

### 2.7 The end of the list, repeat, "Til slutten"

- **Play mode: `NORMAL` always** (vibb; Q5's default). The kid's repeat (design 49) is **our gated advance**, as on a room
  today: the speaker never wraps or repeats by itself.
  - **Repeat all**: at the end of our list our first item plays by a gated `queuePlay` (about 1 s of gap at the wrap).
  - **Repeat one**: the at-end timer (below) sends a gated `Seek REL_TIME 0:00:00` 400 ms before the track's end; the
    belt at a boundary is a `queuePlay` of the same position.
  - A mode set in the Sonos app is not fought (§2.6); the next transfer sets NORMAL again.
- **The at-end timer** (one mechanism): on every measurement of a PLAYING mapped track while an at-end action is armed,
  `work.after(dur - pos - AT_END_QUEUE_PAUSE_MS)` (400 ms, the web player's `AT_END_SPOTIFY_PAUSE_MS`), re-armed by each
  measurement, firing only for the same generation and item. Armed for:
  - **"Til slutten"** (design 50): bookmark, Pause, `index` = our next at 0 with `startPending`, `held()`. The fade is
    the service's `atEndGain`. The belt: a natural boundary seen while `holdAtEnd` is set -> Pause at once, our next
    waits at 0.
  - **Our last item, when the queue goes on after it** (a slot after it exists): repeat off -> Pause, then `ended`;
    repeat all -> a gated `queuePlay` of our first item. Never a Stop of a queue that would go on.
  - **Repeat one** (above).
- **Our last item at the queue's last position** (a full, following listing): no timer - the speaker stops by itself
  (vibb), STOPPED near the end -> `advance()`: no next -> `ended`, the service's `queueEnded`; repeat all -> our first
  item. `advance()` plays through `queuePlay` when trusted (a transfer otherwise), gated as today [D7].

### 2.8 Bookmarks, duration, the bar, the shown track

- **The bookmark is today's `bookmarkNow`** - vibb's sharelink branch one to one: a measured position of the mapped
  track the speaker plays (`playing` follows the queue), through `SonosBookmarks.decide` (the refused-seek hold,
  `sonos_spotify_queue.py` 4, and the regression guard), per item key, the entry's bookmark and the stored queue's row -
  as the web player keeps them (26c §5.4), so phone <-> room continue at the same track and second. An unmapped track
  writes nothing. **The socket never writes** (design 53): `previousPositionMillis` is not a bookmark.
- **The duration**: the speaker's `TrackDuration`, else the listing's through `accept`'s carry (flagged, never decides
  "heard to the end") - unchanged. `cadenceHintS` for this kind is the **playing** item's listed duration, handed to the
  session at each confirmed change (never the start item's after the speaker advanced).
- **The bar**: `sonosAnchor` unchanged; the anchor key changes with `playing`'s item; socket measurements only through
  the `itemId` pair (§2.5).
- **The shown track** is our list's item (title, picture, numbers, "Spor N av M"), never the speaker's metadata
  (`sonos_spotify_queue.py` 2). **Unmapped but ours** (Q6, default vibb's demoted fallback): the player shows the
  speaker's title - the socket's `name — artist`, else `TrackMetaData`'s `dc:title` and `dc:creator`, in the server's
  `"<title> — <artists>"` form - over the entry's cover, with the speaker's position, no number and no heart, through the
  renderer's in-place item swap (`Renderer.replaceItem`); it plays on and nothing is written. No art is ever fetched from
  the speaker or the socket's `imageUrl`.

### 2.9 Ours, adopt, the suspension, the park, the gate on the speaker's own advance

- **The stored marker**: `renderer.json`'s uri for this kind is `queueUri(uid)` (`storedUri` returns the uri as played);
  `kindOfUri("x-rincon-queue:...")` = `SPOTIFY_SHARELINK`; `sameStream(marker, live)` = the live TrackURI is a Spotify
  queue track; `adoptIndex` for a Spotify entry = the item whose `uriOf(kind, key)` is the decoded uri. With a queue
  marker the item is **always found from the live uri**, never `RendererChoice.itemKey` (the queue moved on).
  **`roomVerdict` is OURS_LIVE for any Spotify queue track under a queue marker** (54-1: the park must stop an unlisted
  track of our playlist); `SonosSuspension.bookmark` writes only for a mapped one.
- **Adopt at start** (`adoptLive`, zero transport commands): kept for our live PLAYING/PAUSED queue under the marker;
  one `GetMediaInfo` in the look, inside its budget, confirms `CurrentURI == queueUri(uid)` (Spotify Connect,
  `x-sonos-vli:`, is never ours). Then `learnQueue` as its own actor job (reads only): the read of §2.3 -> the slots and
  the version. The next press is a `queuePlay` when trusted.
- **The migration probe** for this kind (vibb `sonos_migrate_probe.py` 3): `MigrationProbe.live` returns the position
  too; a candidate matches only on `trackSpotifyUri == lastOursTrack` with `lastOursRel - 5 <= rel <= lastOursRel + 45`;
  no recorded track -> never follows. The follow adopts with the new uid's marker (`queueUri(new uid)`, not
  `Moved.uri`), keeps the slots and re-reads the version [D13].
- **The park** (26a §6.1's; vibb has none): Stop, **`RemoveAllTracksFromQueue`**, then `x-rincon-queue:<uid>#0` - for
  every kind (an empty queue costs nothing; for this kind the speaker's own Play button would otherwise play the
  playlist during a time rule). A refused clear is logged; the Stop already happened. **A normal Stop** (the kid's stop,
  a switch home) leaves the queue, as vibb.
- **The gate on the speaker's own advance** [ours; vibb has no time rule] - today a room's advance is our own gated
  play; with a queue the speaker advances by itself, so the gate is rebuilt three ways:
  1. **NORMAL** (§2.7): a missed park ends at the queue's end, never all night.
  2. **The controller's belt**: on every confirmed boundary (natural or pressed) and every ours PLAYING poll with
     `mayPlay()` false (the time rule, or the room scope's belt), one Pause - also in the 54-a correction's place.
  3. **A missed park is retried**: when the suspension's park ends NO_ANSWER (no Wi-Fi at the edge, a Stop that failed
     or came late) and `renderer.json` still names a room, a unique WorkManager chain `sonos-park-retry` runs the existing
     `suspensionStop` on Wi-Fi's return and every 5 minutes, for at most 2 hours after the edge, while the app is still
     suspended; it ends at the first definite verdict. For every kind (harmless for a `url` play). [D16+: whether
     Android runs a suspended package's work at all - if not, the retry is the next start's adopt as today, and the
     belt plus NORMAL bound the cost.]

### 2.10 Hand-over

- **Phone (the web player) -> room**: `toRoom` as today: `old.saveNow(force = true)`, `queueFor(..., SONOS)` (now
  speaker-playable), `renderer.json`, `attach` (the detached web player is paused and owns nothing, 26c), `start` -> a
  transfer at the item and second (hushed, settled seek).
- **Room -> phone**: `handOff` (the live classify, the bookmark, the Stop - gated on `ours`, §2.5) then `comeHome`,
  whose `phoneRendererFor(entry)` is now really `SPOTIFY`: the web player at the item and place, playing only if the
  room was PLAYING and `playAllowed`; under the phone's streaming gate it loads paused with "Trenger Wi-Fi" (26c). The
  comments of `comeHome` and `phoneRendererFor` are corrected.
- **Room -> room**: the old room's bookmark and Stop (its queue left), a transfer on the new one.
- No token, no page and no Connect device for a room.

### 2.11 Grouping, volume, the sleep timer, calls, locks, the cadence

- **Grouping** (53-b): unchanged verbs. A "+" joins a room to our coordinator's stream; a "−" on our coordinator moves
  the stream and its queue to a member -> the follow (§2.9) [D13, D14].
- **The cap**: a transfer and a pick are STARTs (every room of the row capped inside the hush before Play); a step, an
  advance, a correction or the speaker's own advance never touch a volume.
- **The sleep timer** (minutes): unchanged. **"Til slutten"**: §2.7. **Calls**: the same pause and resume verbs.
- **The cadence**: for this kind the 5 s tail cadence (45 s before a track's end - a quarter of a 3-minute song) runs
  only **without a healthy socket, or while an at-end action is armed**; with a healthy socket the `itemId` confirm
  covers the boundary. The 1 s display poll is design 53's.
- **Locks**: a self-advancing queue holds the phone's partial wake lock and Wi-Fi lock for as long as it plays (today's
  rule: armed and playing) - hours for a long playlist. Accepted; the disarm after 10 minutes paused is unchanged.

### 2.12 Context mode on a room (round 54-c; Q3)

Most playlists a parent pastes are context mode in dev mode (26c §3.3); the 54-0 run played exactly such an editorial
playlist through the household account. This is the round that delivers "hele playlisten" for those.

- A playlist context is a transfer of the playlist like list mode, with no listing: no slots for our items. **Ours** =
  the prefix and, with the socket, `metadataStatus`'s container = our target and `queueVersion` unmoved; without it the
  read's `UpdateID` at the start and at adopt. (A guest's Spotify that replaced our queue in the Sonos app is then not
  ours - QA's Q3 note: the bedtime park never stops a parent's music.)
- **Resume**: the context's live track (`spotify_context.json`, 26c §5.9) -> `findInQueue(uri)` -> its position, at the
  synthetic item's saved place; not found -> position 1 at 0.
- **The shown track**: the socket's metadata `name`/`artist` (else `TrackMetaData`) in the server's form, written as the
  live track through the store port the web player uses, on a change only; the synthetic item's title follows it. No
  art from the speaker (the entry's cover).
- **Steps**: `stepsOnWebPlayer(entry)` routes next/previous to the room's controller: `play_from_queue(Track ± 1)`;
  previous more than 5 s in restarts at 0 (vibb). Shuffle for a context (design 49: Spotify's own on the phone) is the
  speaker's own here (`SHUFFLE_NOREPEAT`), and while shuffled a step is AVTransport `Next`/`Previous`. Repeat as §2.7.
- The bookmark: the synthetic item's position + the live track (26c's model), so phone <-> room continue on the same
  track. End: the queue's end -> `ended` (never marked done). An artist context: refused.

### 2.13 The Sonos household

- **What it needs**: Spotify linked in the Sonos app (Settings › Services › Spotify) - **the household's account**,
  often a parent's: that account plays on the speaker, not the kid's phone account, and the kid's listening lands in
  its history and recommendations. A playlist the linked account can't open fails the add or adds nothing [D12]. **One
  stream per account**: that account playing elsewhere stops the room - followed as a stop we didn't make ("Høyttaleren
  stoppet"), nothing restarts by itself [D18].
- **No account document**: `/status/accounts` is empty on the house's firmware (§0.3), so it is not read for Spotify
  at all. **The add's answer decides**: both service types refused, or 0 tracks added -> `ShareRefused` -> the failed
  start (26a §4.2: back to the phone, paused) with `SONOS_ASK_ADULT`, "Kan ikke spilles på Sonos - spør en voksen" /
  "Can't play on Sonos - ask a grown-up". `lost()` takes the reason (today it always emits `CANT_PLAY_ON_SONOS`); a
  network failure during the add is the ordinary `CANT_PLAY_ON_SONOS`.
- **The parent** learns it from the kid, and from one line of copy on the PWA's Spotify page ("On Sonos, a link plays
  through the Spotify account linked in the Sonos app") - server copy only (§2.14). No report field (a contract).

### 2.14 Server, PWA, contracts, launcher, logging, licences

- **Server/PWA**: no API, no migration, no policy key; the room scope applies unchanged; no parent switch (Q2's
  default). Optional: the Spotify page's one line of copy, a server-only commit with its test.
- **Shared contracts: none.** No change to `server/testdata/*` contract files, `music_state.json`, the policy JSON, the
  bridge AIDL, `bridge_contract.json` or `spotify_hosts.json`. The fake speaker reads `server/testdata/spotify/` as test
  data (§3), not a contract (`music.yml` already triggers on it).
- **Launcher**: untouched. The phone sends only SOAP to the LAN; the speaker reaches Spotify itself, outside the
  phone's DNS filter and VPN. No token is asked for a room.
- **Logging** (26c §7.1's rule for Spotify; `SonosHome.log` is `Log.i` in a release): a release's Sonos lines for this
  kind carry kinds, counts, positions and verdicts only - the target and a track id only under `BuildConfig.DEBUG`; no
  title of a Spotify item in a release line (`"sonos: playing [i/n]"` drops it for this kind); a Browse answer, a
  `metadataStatus` and the `listed` list are never logged; `PlayBody.toString` leaves `listed` out. `SourceScanTest`
  pins it.
- **Secrets**: none new. A TrackURI's `sn` and the metadata's `accountId` are serials, not secrets.
- **Licences**: SoCo (MIT, the SoCo team) for the ShareLink table and template, `play_from_queue`, `queue_size`,
  `get_queue` and `reorder_queue` - credited in each ported file's header and in `NOTICE.md`; vibb (MIT) as today.

## 3. Tests (lean; JVM, fixtures, the fake)

- **Fixtures from the 54-0 run** (54-a1): `sonos/ws/spotify_queue_2026-10-21.json` = the 53 socket messages, scrubbed by
  `sonos-ws-probe.py --rescrub`'s rules **plus the Spotify ids** (the probe gains the rule: every `spotify:<kind>:<id>`
  and every `%3a<id>` replaced consistently by ids of `server/testdata/spotify/playlist_items.json`, so the fake and the
  fixtures name the same tracks). **New UPnP fixtures** in `sonos/` (from the run's answers where it read them - the add,
  `GetMediaInfo`, GetPositionInfo while the queue played -, else hand-written in SoCo's shapes, marked provisional in
  `SonosFixturesTest` until the 54-0 remainder replaces them): `add_uri_to_queue.xml` (1, 30, 30), `add_uri_to_queue_empty.xml`
  (0 added), `browse_queue_page.xml` (`Q:0/n` items with `res` `x-sonos-spotify:spotify%3atrack%3a<id>?sid=9&amp;flags=8232&amp;sn=6`,
  a duplicate, an extra track, `UpdateID`), `browse_queue_meta.xml` (`childCount`, `UpdateID`), `position_spotify_queue.xml`,
  `transport_settings.xml`, `media_info_queue.xml`, `fault_800.xml` (code provisional, [D12]); the goldens
  `sharelink_{playlist,album,track,show,episode}.xml` written by SoCo (§2.2). `accounts.xml` stays NRK's only.
- `ShareLinkTest`: each type's `EnqueuedURI` and metadata byte for byte against SoCo's goldens; artist and junk -> null;
  2311 then 3079 on a fault, not on a network failure.
- `SonosQueueTest` (`logic/`, pure): `trackSpotifyUri` (both prefixes, `+` kept, junk null); **the slots** for a gap, an
  extra track, a duplicate, a capped reversed show and a relinked id; `itemAt`; trust by version; the confirm's
  verification; the nearest occurrence when untrusted; `follows`; `boundary` (the anchor within 3 s of the duration;
  `endsNear` without a socket); the at-end arming (not for our last item at the queue's last position; yes when a slot
  follows); the unmapped title form.
- `SonosTransportTest` `d54`: a transfer's SOAP order (the hush around it, the cap before `SetPlayMode`, the gate before
  `SetPlayMode` and again before `SetAVTransportURI` - a closed gate sends nothing more; NORMAL; the 3079 retry on a
  fault only; both faults and a 0-track add throw `ShareRefused` with no Seek; the start's page; `play_from_queue`'s
  three calls; the settled seek; `readQueue` afterwards, outside the hush); every `Browse` carries `SortCriteria`;
  `queuePlay` hushed as vibb's `sonos_hush.py` 5; `park` = Stop, clear, the queue uri.
- `SonosSessionTest` `d54`: classify for the queue kind (ours for an unlisted Spotify track of our queue; not ours for
  `x-sonos-vli:` or a moved version; `trackNo`; no `lostSession`; no stall retry); vibb's `sonos_migrate_probe.py` 3.
- `SonosEventsTest` `d54`: `itemId`, `queueVersion`, `playModes`, `previousPositionMillis` and `metadataStatus`'s
  current/next/container parsed from `spotify_queue_2026-10-21.json`; absent fields null; another service's metadata
  ignored.
- `SonosControllerTest` `d54` - vibb's `sonos_spotify_queue.py` ported: (1) a start resumes the bookmarked track at its
  second with **one** transfer; (2) the shown item is our list's, never the speaker's metadata; (2b) a guest's Spotify
  (another container, a moved version) is not ours; (3) a step is one `queuePlay`, never an add - **and a second step
  inside the first one's settle too**; (4) a refused seek holds the bookmark until playback passes it; (5) an untrusted
  map makes the next press a transfer; (6) an artist is refused by name; (7) the step worker survives a failed play.
  Ours: the park and `handOff` stop an unlisted track of our queue (54-1); a natural boundary with `mayPlay()` false
  sends one Pause and no `queuePlay` (54-2); the `itemId` lag - a lagging poll of the old track keeps `wsBlocked` and the
  anchor, the recheck follows within 1 s and confirms the new track, and a measurement with another `itemId` never
  counts (54-4); natural vs pressed by the anchor; no correction under a foreign SHUFFLE or REPEAT_ONE (54-5); no
  at-end pause when our last item is the queue's last position; past our last item with a slot after it, repeat off
  pauses and ends, repeat all plays our first item (54-8); repeat one seeks to 0 gated; "Til slutten" pauses 400 ms
  before the end and the belt at a boundary; duplicates - the index from `Track` through the slots (54-3); the tile with
  an intact queue sends nothing or one resume, else a transfer; a pick on the loaded entry is a `queuePlay`; a
  superseded transfer's map is taken; adopt sends no transport command and reads the map; the unmapped look writes no
  bookmark.
- `SonosLifecycleTest`: `kindOfUri`, `sameStream`, `adoptIndex` for a queue marker; `roomVerdict` OURS_LIVE for an
  unlisted queue track under a queue marker; the standalone bookmark only for a mapped track. `SonosSuspensionTest`: a
  NO_ANSWER park schedules the retry, a later OURS_LIVE parks, a definite verdict ends it.
- `PlayBodyTest`/`RendererRulesTest`: the drop reasons by kind and mode. `GuardsTest`: `comeHome` reaches the web
  player for a Spotify entry; `lost()`'s reason. `SourceScanTest`: the new words name no source; `x-rincon-cpcontainer`
  only in `ShareLink.kt`; no Spotify id or title in a release-built Sonos log line.
- **`scripts/fake-sonos.py`** (the queues come from `server/testdata/spotify/` - `playlist_items.json`,
  `album_tracks.json`, `show_episodes.json`, `track.json`, `episode.json`): `AddURIToQueue` (the ShareLink uris),
  `RemoveAllTracksFromQueue`, `SetAVTransportURI x-rincon-queue:`, `Seek TRACK_NR`, `SetPlayMode`/`GetTransportSettings`,
  `Browse Q:0` (both flags, paged, `UpdateID` moving on each edit), `GetMediaInfo`, `ReorderTracksInQueue` (54-b), the
  queue's own advance (NORMAL, the end STOPPED), GetPositionInfo from `position_spotify_queue.xml`; `--refuse-share`
  (the add's fault) and `--empty-share` (0 added); `--queue-reversed`, `--queue-extra` (a track our listing lacks),
  `--queue-gap` (one of ours missing); script events `next`, `previous` (the speaker's buttons), `queue-insert` (an edit
  from the Sonos app), `shuffle`; `--selftest` covers them. No socket (design 53: the emulator checks the polling path);
  `docs/testing/emulator.md` §6h gets the Spotify-on-a-fake-room loop (§6l's A2 debug feed with `fake-spotify.py
  --print-listing`).

## 4. Rounds (sequential, each with its code QA and fix round; branch per round)

- **54-0 - measure** (the user's Pi): **done 2026-10-21** for the transfer, jumps, the natural advance, the socket and
  play modes (§0.3). **The remainder** (one more run, scripted in 54-a1): a full Browse against the server's listing for
  three or four of the house's playlists and a show, the queue's end, the speaker's own Next/Previous (`--wait`),
  `REPEAT_ONE`, `ReorderTracksInQueue` while playing, a refused and an empty add, the goldens from SoCo (`--golden`).
- **54-a1 - the pure rules, the SOAP, the socket's fields and the fake** (nothing changes for the kid:
  `speakerDropReason` still refuses): `ShareLink.kt`, `logic/SonosQueue.kt`, `Kind.SPOTIFY_SHARELINK`, `Soap`'s
  ContentDirectory, `SpeakerControl`'s new verbs, `SonosTransport.playShare`/`queuePlay`/`readQueue`/`learnQueue`/
  `findInQueue` and the park's clear, `SonosSession`'s classify/probe for the kind, `SonosEvents`' new fields and
  `Metadata`, the probe's Spotify-id scrub, the fixtures, `fake-sonos.py`, the play test's remainder steps, the tests
  above that need no controller.
- **54-a2 - list mode on a room** (the feature on): `SonosController` (the map, ours/mapped, steps vs transfer, the tile,
  `queueMoved`, the boundary, the correction, the at-end timer, repeat by our advance, the belt, the `itemId` recheck and
  pairing, the unmapped look, `learnQueue` at adopt), `SonosRenderer` (`setModes`, `pauseAtItemEnd`), the service
  (`speakerDropReason`, `lost()`'s reason and `SONOS_ASK_ADULT`, `comeHome`, the listing's keys handed to `load`),
  `SonosLifecycle`/`SonosSuspension` (OURS_LIVE, the park retry), the strings, the logging, `GuardsTest`/`SourceScanTest`,
  emulator.md §6h, the docs. Its code QA takes the controller and the lifecycle in separate passes (qa-54 L14).
- **54-b - our order without the blip** (Q1, gated on [D10]): the one-pass reorder, the fake's reorder.
- **54-c - context mode** (Q3): §2.12.

Docs with 54-a2: `music/CLAUDE.md` ("Spotify is the third renderer": a room plays a Spotify entry through the ShareLink;
a rule block "Spotify on a room (design 54)" - the read map, ours vs mapped, the heal, NORMAL and the belt, the park's
clear and its retry, the logging, the words; the DIDL rule's sentence naming the ShareLink metadata as SoCo's template,
pinned by SoCo-written goldens), design 26c §11/§12/§13/§14 pointing here, design 26a's §9 row and its "not ported"
`sonos_migrate_probe.py` 3 / `sonos_hush.py` 5 note, `NOTICE.md` (SoCo), `PLAN.md`.

## 5. Owed device checks (the user's speakers and Pi, the Jelly Star, a debug build; logcat `VibbSonos`)

Through the play test unless "the app" is named. Done in 54-0 (§0.3): D1 (the accounts document - empty, dropped), D2's
add (0.8 s, 30 tracks, first 1), D4 (TrackURI, Track, TrackDuration), D5 (TRACK_NR playing and paused), D6's natural
advance (gapless, 0.5 s socket lead, no BUFFERING, `queueVersion` unchanged), D8's NORMAL/REPEAT_ALL.

1. **D2+** The add's time for 300 and 1000 tracks; the largest queue one ShareLink gives; `NumTracksAdded` and
   `NewQueueLength` for a private playlist of the kid's.
2. **D3** A full Browse against the server's listing for three or four of the house's playlists and a show: relinked
   ids, unplayable and local tracks, episodes, duplicates, the order; a Browse item's `res` equal to the TrackURI.
3. **D5+** `TRACK_NR` while STOPPED; the settled REL_TIME seek after a jump (701), heard muted.
4. **D6+** The `itemId` change on a press; how long GetPositionInfo lags it; the correction's audible length (the app).
5. **D7** The queue's end under NORMAL: the transport, `Track` and `TrackURI` after it, the socket's IDLE.
6. **D8+** `REPEAT_ONE` on a queue; the household's crossfade (`playModes.crossfade`) and its effect on the boundary and
   the at-end timer; `TRACK_NR` and `Track` with shuffle switched on in the Sonos app.
7. **D9** The speaker's own Next and Previous (the button, the Sonos app): the app follows within ~1 s with the socket
   and at the next poll without; no done mark, no correction (the app).
8. **D10** `ReorderTracksInQueue` while playing: the current track plays on without a gap, the positions after the move,
   the speaker's next is the moved track; `UpdateID` against `queueVersion` after our add, our reorder and an edit from
   the Sonos app.
9. **D11** A show's and an episode's ShareLink: the queue's order, the TrackURI prefix, a resume seek in an episode.
10. **D12** A playlist the linked account can't open: the add's fault code or 0 tracks; the app's words.
11. **D13** Hold-Play our coordinator out of a group while the queue plays: the queue moves with the same positions; the
    app follows and the next press is a `queuePlay` (the app).
12. **D14** "+" a room while the queue plays; "−" a member (the app).
13. **D15** Hand-over at the same track and second: the web player -> Stua, Stua -> the web player (playing only if Stua
    was), Stua -> Kjøkken (the app).
14. **D16** The suspension while the queue plays: stopped, the queue cleared, the speaker's own Play button plays
    nothing; **D16+** the edge with the phone off Wi-Fi, then Wi-Fi back: the room parked by the retry - and whether
    Android runs a suspended package's WorkManager work at all (the app).
15. **D17** An app restart while the queue plays: adopted with no transport command, the map read, the next press a
    `queuePlay` (the app).
16. **D18** The linked account started on another device while the room plays (the app).
17. **D19** The cap at a transfer, the minute timer's fade, "Til slutten" at a track's end, a call's pause and resume,
    repeat all's wrap and repeat one (the app).
18. **D20** Five nexts within 2 s on a playing queue: one `queuePlay` per settled want, no add, the title never flaps
    back (the app).

## 6. Deviations from vibb

| vibb | Here | Why |
|---|---|---|
| the map is positional from `q_base` over go-librespot's own listing | the map is one read of the queue (slots), trusted by the queue's version | our listing is the server's, filtered (local, unplayable, relinked, capped, duplicates) |
| trusted iff `qlen == len(rows)` | trusted while the queue's version is ours | an exact map holds whatever the lengths; only an outside edit breaks it |
| `ours` = the prefix | the prefix and the queue still ours (the socket's container and version, else `UpdateID`); "mapped" separate | a guest's queue is detected, not assumed; our own unlisted tracks stay ours (the park) |
| a track change adopted at the next poll | the socket's `itemId`/metadata wake the confirm and its recheck; natural vs pressed by our anchor | design 53's events; the user's expectation for the speaker's buttons |
| Spotify's order only; `sonos_step` wraps `% n` | our order through the correction (54-a) or one reorder (54-b); the service's skip rules | designs 39, 49 and 50 are the user's |
| no time rule; `_sonos_on_term`'s Stop | the park clears the queue; the belt pauses the speaker's own advance; a missed park is retried | our time rule with a queue (26a §6.1) - a consequence, not a choice |
| no account check | none either - the add's answer picks the words | `/status/accounts` is empty on the house's firmware |
| a context resumes through go-librespot's listing | through `findInQueue` of the live track (54-c) | no listing on the phone |

Kept as vibb (Q5's default): NORMAL always. Kept as vibb (Q6's default): an unlisted track of our queue is ours and
shows the speaker's title.

## 7. What this design does NOT do

- No change to RSS/NRK podcasts or Storytel on a room (one URI, our queue, vibb's DIDL) - the user's rule.
- No Spotify Connect to the speaker (`x-sonos-vli:`), no Web API call for a room, no web player, no token.
- No artist on a room. No Liked Songs, no Sonos playlists or favourites.
- No art fetched from the speaker or the socket; no speaker metadata shown for a mapped track.
- No parent switch, no report field, no contract change; no `/status/accounts` read for Spotify.
- No fight with the Sonos app: a play mode, a shuffle, a queue edit or a volume set there is followed, never undone
  until the kid's next explicit press.

## 8. Questions for the user (the body builds each default)

- **Q1. Our order on a room** - a shuffled, flipped or "newest first" list: the speaker's queue is in Spotify's order.
  **Default: one reorder of the rest of the queue into our order right after the start (round 54-b, if the device
  check shows the speaker plays on through it)**; until then - and for good if it doesn't - a short correction at each
  track change: about a second of the speaker's next track, for a shuffled list at every track. Other options: move only
  the next track into place after each track (the same result, more moving parts); our order as single-track adds (one
  per track, slow for long lists); correction only; or play a room in the playlist's own order with the speaker's own
  shuffle (simple, but the list the kid sees and what plays next differ).
- **Q2. A parent switch for Spotify on Sonos** - **Default: none.** It plays wherever the Sonos app has Spotify linked,
  on the rooms the room scope allows, with the linked account - often a parent's: the kid's listening lands in that
  account's history and recommendations, and that account playing elsewhere stops the room. A switch would be a policy
  key, a launcher change and a contract.
- **Q3. Context-mode playlists on a room** (an editorial or a friend's playlist, which no family account may list - the
  54-0 run played one) - **Default: yes (round 54-c)**, resuming at the last track by finding it in the speaker's queue,
  titled from the speaker; a guest's Spotify that replaced our queue is told apart by the socket's container and the
  queue's version. Alternative: refused on a room until a family account collaborates on the playlist.
- **Q4. Spotify shows and episodes on a room** - **Default: through the ShareLink like music (vibb did)**, each episode
  resuming at its own place; the show's order and its 100-episode cap are handled by the read map. RSS/NRK podcasts and
  Storytel keep their direct URI. Alternative: refuse Spotify shows on a room.
- **Q5. Repeat on a room** - **Default: vibb's NORMAL always, repeat done by our gated advance** (about a second of gap
  at a repeat-all wrap; repeat one as a seek to 0 at the track's end), so nothing repeats on the speaker by itself - not
  even all night after a missed bedtime stop. Alternative: the speaker's own REPEAT_ALL/REPEAT_ONE (gapless, but never
  gated, and shown and changeable in the Sonos app).
- **Q6. A track of our queue that our list doesn't have** (the server leaves out local files and unplayable tracks, a
  track may be relinked, a list is capped) - **Default: vibb's: it stays ours and plays on, the player shows the
  speaker's title over the entry's cover, no bookmark.** Alternative: show it as "used by something else" and stop
  following until a listed track plays (but the bedtime stop still stops it).

## Revision after QA (2026-10-21, `qa-54-design.md`) and the 54-0 measurement

Each finding was checked against the code and the sources before it was taken; all were taken, none disputed.

- **54-1 (High)** ours vs mapped split (§2.5, §2.9): Stop, park, lock and follow read `ours` (prefix + the queue still
  ours by the socket's container/version or `UpdateID`); index, bookmark, done mark and the shown item read `mapped`;
  `roomVerdict` OURS_LIVE for an unlisted queue track. Confirmed: `parkGate`, `handOff`, `abandon`, `roomVerdict` all
  read `ours`.
- **54-2 (High)** the speaker's own advance gated three ways (§2.9): NORMAL (Q5), the controller's belt on every
  boundary and ours PLAYING poll, and a missed park retried by `sonos-park-retry` - with a device check on whether a
  suspended package's work runs at all (the one part I can't settle from the code).
- **54-3 (High)** the map is one paged read of the queue (§2.3): slots exact for gaps, extras, duplicates, direction and
  cap; trusted by the queue's version; the probe, the direction and the formula are gone. The 54-0 run confirmed
  `queueVersion` moves only on edits.
- **54-4 (Medium)** after an `itemId` change the confirm re-polls every `IDLE_RECHECK_MS` until UPnP names the new track
  (the socket's metadata says which); `wsBlocked` clears only then; socket measurements paired by `itemId` (§2.5). The
  run measured the lag (0.5 s).
- **54-5 (Medium)** natural vs pressed on our anchor (or `endsNear`), the socket's fields only cross-checks (also: the
  run showed `previousItemId` is another id space); the mode read before any correction, a foreign mode followed (§2.5,
  §2.6).
- **54-6 (Medium)** steps, picks, the advance and corrections need only the map trusted, this entry and this uid; the
  tile alone needs ours, fresh and nothing settling (§2.4). Confirmed: `stepTo` sets `pending` before its worker.
- **54-7 (Medium)** no household pre-check at all - the run found `/status/accounts` empty on 86.10; the add's answer
  (both faults, or 0 tracks) gives `SONOS_ASK_ADULT` through a reason passed to `lost()` (§2.13).
- **54-8 (Medium)** the at-end timer armed only when the queue goes on after our last item (or repeat one, or "Til
  slutten"); repeat all past a capped or non-following end plays our first item; `advance()` uses `queuePlay` when
  trusted (§2.7).
- **54-9 (Medium)** NORMAL by default, repeat by our gated advance; asked as Q5 (§2.7, §8).
- **54-10 (Low)** Q1 now offers the one-pass reorder (the new 54-b default) and single-track adds (§2.6, §8).
- **54-11 (Low)** release logging: kinds, counts, positions only; ids under `BuildConfig.DEBUG`; no Browse answer,
  metadata or `listed` logged; `PlayBody.toString` without `listed`; `SourceScanTest` pins it (§2.14).
- **54-12 (Low)** `Browse` with `SortCriteria` (six arguments), `InsertBefore insert_before + 1` (checked in SoCo's
  `_reorder_queue`), 3079 only after a fault, goldens written by the real `ShareLinkPlugin` on the Pi, `builtDidl`'s
  throwing branch (§0.2, §2.2).
- **54-13 (Low)** `cadenceHintS` from the playing item; the tail cadence only without a healthy socket or with an
  at-end action armed; the locks' hours stated (§2.8, §2.11).
- **54-14 (Low)** §6 no longer calls the park vibb's (vibb has `_sonos_on_term` only); vibb's 25 s bookmark throttle
  noted (§0.1); 54-a2's code QA in two passes (§4).
- **The 54-0 run** (§0.3): used beyond the findings - the socket's `metadataStatus` names the Spotify track, the next
  one and our playlist as the container, which gives `ours` its container check (54-1, Q3), the recheck its target
  (54-4) and context mode its title (§2.12); the rounds start from its facts and owe only the remainder (§4, §5).
