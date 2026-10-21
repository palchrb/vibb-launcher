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
`ws-spotify-2026-10-21.json` + `spotify-playtest-2026-10-21.txt` (a Spotify queue; kept outside the repo in
`~/handy-captures/`, §0.3); the server's listing (`server/src/spotify.rs`); the music app's `sonos/`, `spotify/`,
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
- **The natural advance** is gapless; the socket saw it **0.5-1.0 s before UPnP** (the run's 1 s poll still showed the
  old track 0.5 s before the socket's event and the new one 0.5 s after it - a bound, not the lag; [D6+] owes the
  number) (`playbackStatus` PLAYING with a new
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
  `ReorderTracksInQueue`, a show, a refused or empty add [D3, D7, D9, D8, D10, D11, D12]. **The container was seen for a
  playlist only** (`container.type` `playlist`): an album's, a show's, a track's and an episode's container form is
  owed [D3, D11]; it is empty on IDLE and after the park, and absent right after a transfer.
- **The raw files** (`ws-spotify-2026-10-21.json`, `spotify-playtest-2026-10-21.txt`, and the first capture) have a
  durable copy **outside the repo** in `~/handy-captures/`. They hold real Spotify ids: 54-a1 scrubs them into fixtures
  (§3) and never commits them raw.

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
gated advance, a belt that pauses the speaker's own advance whenever playing isn't allowed, and the speaker's own
sleep timer set to the next time-rule edge, so a missed bedtime stop can't let the queue play on (§2.9). A context-mode
playlist (one our server can't list) goes the same way in the same delivery: the speaker plays the share link through
the household's account whatever our server can read, and the socket's metadata names what plays (§2.12).

## 2. Decisions

### 2.1 What may go to a room

- **List mode** (26c §3.3): speaker-playable when the target's kind has a ShareLink type - `playlist`, `album`,
  `track`, `show`, `episode`. **An artist never** (no ShareLink type). `speakerDropReason` returns null for the five,
  `"spotify artist"` for an artist.
- **Context mode** (26c §5.9; the user's Q3: "vi sender jo bare hele sharelinken"): a playlist context passes (§2.12,
  delivered with list mode in 54-a2); an artist context stays refused.
- **Shows and episodes** go by the ShareLink too (vibb did; Q4, decided: "ja ... samme oppførsel som vibb"). RSS and NRK podcasts and Storytel are not touched.
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
   (§0.2). The second fault, or an answer with `NumTracksAdded` 0 or `NewQueueLength` 0, throws `ShareRefused`; a
   network failure throws its own `SpeakerUnreachable` (no second try) - both before any Seek, a failed start (§2.13). `first` = `FirstTrackNumberEnqueued` (unparsable -> 1), `len`
   = `NewQueueLength`, else vibb's `queue_size`.
7. **The start's position** (§2.3): `findInQueue(listed[trackIndex], near = first + trackIndex)` - one
   `BrowseDirectChildren` page of 100 around the guess, then the others until the hit, at most 10 pages. **Not found**
   (vibb's "hit is None -> idx 0"; qa-54-revision N4): position `first` at 0 with **no seek** (step 10 is skipped: the
   saved second belongs to another track), no bookmark and no index move - the read's slots tell the controller what
   plays (§2.3); a pick of that item also shows `CANT_PLAY_ON_SONOS`. A context resume (§2.12) is the same.
8. **`gate()`** again, then `play_from_queue(position)` as SoCo: `SetAVTransportURI(queueUri(uid), "")`, `Seek(TRACK_NR,
   position)`, `Play`.
9. `session.played(queueUri(uid), hint)` and `session.shareSet(target)`; armed.
10. A start of 5 s or more at a found position: `seekSettled` (REL_TIME); false arms the controller's refused-seek hold.
10b. The speaker's sleep timer to the next suspension edge, or cleared (§2.9, `ConfigureSleepTimer`; errors logged).
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
  (§2.2 step 7); the rest is read right after Play, off the hush. **While the read runs** (qa-54-revision N6) the map
  counts as trusted for choosing a `queuePlay` - never a transfer, which would make a mash re-add - and a `queuePlay`
  job looks its position up when it runs: the read is ahead of it in the actor, so it waits for it.
- **`slots`**: for each item of our listing, its queue position or none - duplicates matched in order (the k-th
  occurrence of a uri in our listing to the k-th in the queue); exact for gaps, extras, either direction and any cap.
  `itemAt(position)` is the inverse. No direction field, no formula.
- **An item with no slot** can't play on that room: a step skips it the way an unplayable item is skipped
  (`playableIndex`'s rule, in the step's direction). A pick of it: when our listing changed since the read (an item
  added by a sync while the room plays) one fresh transfer, whose start decides (§2.2 step 7); otherwise - the item a
  transfer would not give, relinked or unplayable for the household - refused with `CANT_PLAY_ON_SONOS`, nothing changes.
- **`version`**: the queue's version at the read, **kept with its source** (qa-54-revision N5): the Browse's
  `UpdateID` always (it is what the standalone path and adopt can read, §2.9), and the socket's `queueVersion` when the
  socket was healthy at the read; a later value is compared only with the same source, and a source the read lacks (the
  socket came back) means "read the version again" (one `BrowseMetadata`), never a mismatch [D10: whether the two are one
  number]. **Trusted** = a read exists for this entry and uid and the version hasn't moved since it, except by an edit of
  ours: 54-b's job counts its own moves and ends with a re-read, which becomes the version (a guest's edit during the
  job shows as a read that doesn't match the applied moves -> untrusted).
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
    prefix) **and not contradicted** (qa-54-revision N2). Contradicted means one of two things. (a) The socket's last
    `metadataStatus` container names a Spotify object other than our target. An absent container, an empty one (IDLE,
    right after a transfer) or one of a form we haven't seen (only a playlist's was measured, §0.3) is **no evidence**,
    never "not ours". (b) UPnP: the playing track is neither in our listing nor in the queue we read, **and** the
    queue's version moved by an edit not ours. That takes one `Browse Q:0 BrowseMetadata` `UpdateID`, read only when an
    unmapped track shows up. A moved version alone only makes the map untrusted. Every Stop, park, lock, disarm,
    follow and "taken over" gate reads `ours`. So an unlisted track of our playlist is still ours, and the park stops
    it; a queue replaced from the Sonos app is not. The socket's part only ever withholds a Stop, and the park's final
    verdict is UPnP's (§2.9).
  - **`mapped`** (the item): the polled `Track` holds one of our items through the slots, its uri verified. The index,
    the bookmark, the done mark and the now-playing row read this. A track that is ours but not mapped gets no bookmark
    and no index move; what it shows is Q6 (decided, vibb's: §2.8).
- **`SonosSession.classify` for this kind**: `ours` as above (the session holds the target and the version it read;
  the controller feeds it the socket's container and version), `Fields.trackNo`, `Fields.trackSpotifyUri`; no
  `lostSession`, no DIDL check, and `stallBookkeeping` returns null for this kind (vibb 895-899). `lastOursTrack`/
  `lastOursRel` kept on every ours + live sighting.
- **The socket's parts** (`SocketEvent.Playback` gains `itemId`, `queueVersion`, `playModes`, `previousPositionMs`; a new
  `SocketEvent.Metadata` carries `currentItem`'s Spotify uri, name, artist, `durationMillis`, `nextItem`'s uri and the
  container's uri, parsed only for `serviceId` 9 or a `spotify:` object id):
  - an **`itemId`** different from the one paired with `playing`, while a queue session of ours plays and no play of
    ours is pending, is a track change the speaker made: a confirm poll **at once**, then **re-polls every
    `IDLE_RECHECK_MS` until a poll names a `Track` or a uri other than `playing`'s** (either: a duplicate at the next
    position has the same uri; qa-54-revision N6) **- the one the socket's metadata named, when it did - or
    `IDLE_RECHECK_UNTIL_MS`** (design 53's IDLE recheck, qa-53a-code 4: UPnP lags 0.3-2.6 s; 0.5-1.0 s in the 54-0 run);
    `wsBlocked` clears only on that confirming poll, never on a lagging poll of the old track;
  - **pairing**: `playing` is paired with the `itemId` seen when it was confirmed; a socket measurement (PLAYING/PAUSED
    with `positionMillis`) counts only when its `itemId` equals the pair - so the new track's 0:00 never re-bases the
    old track's bar;
  - the metadata's track, container, `nextItem` and `previousPositionMillis` are **cross-checks** (logged in a debug
    build when they disagree with UPnP); they decide nothing that UPnP decides.
- **`pollTick` for this kind**, after the existing landing, disarm, accept, `optTr`/`optPos`, the follow and the
  `fresh && ours` gate, in this order:
  1. **The belt** (§2.9): `mayPlay()` false on an ours PLAYING poll -> one Pause (once per generation and track).
  2. **STOPPED near the end** is the queue's end (§2.7) - before anything reads `Track` [D7]. For this kind "near the
     end" is `endsNear > 0` **or the anchor**: the last ours measurement of the playing track, extrapolated to the
     STOPPED poll's `measuredAt` (or the IDLE event's `at`), within `ENDS_NEAR_S` of its duration (qa-54-revision N1b -
     chosen over keeping the tail cadence: no extra polls, and with a healthy socket the anchor is exact and the IDLE
     wakes the confirm at once; without one the tail cadence still runs, §2.11).
  3. **A track change** (the polled `Track` or uri isn't `playing`'s, no play of ours pending) -> `queueMoved`: `index` and
     `playing` follow `itemAt(Track)` (`Playing(entryId, index, key, polled TrackURI)`, paired with the socket's current
     `itemId`), `endsNear = 0`, the map verified; not mapped -> the unmapped look (§2.8), the index stays.
     **Natural or pressed** (`boundary`, pure, on our own measurements): natural iff the previous track's anchor,
     extrapolated to the change's time (the `itemId` event's `at`, else the confirming poll's `measuredAt`), is within
     `NATURAL_END_MS` (3 s) of its duration (the anchor exists with or without a socket: polls re-base it too); when
     the anchor can't say (no ours measurement of that track), the fallback is `endsNear > 0` - a press in the last
     20 s then counts as natural, which only adds a done mark: **the correction (§2.6) runs only on the anchor's
     verdict, never on this fallback** (qa-54-revision N8). Natural -> `autoFlag` (the service marks the finished track
     done), then §2.6 and §2.7. Pressed (the speaker's buttons, the Sonos app) -> followed as it is: no done mark, no
     correction.
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
- **When it doesn't** (round 54-a, the correction): at a **natural** boundary (the anchor's verdict) whose new track
  isn't our expected next,
  one `queuePlay` of our next at 0 (`PlayWhy.ADVANCE`, gated) - **but first the mode**: the socket's last `playModes`, or
  one `GetTransportSettings` without a socket; a mode we didn't set (shuffle or repeat-one from the Sonos app) is
  followed, never corrected. About 0.5-1.5 s of the speaker's next is heard [D6]: for a shuffled kids' list, every track.
  A pressed change is never corrected.
- **Round 54-b** (Q1, decided: "enig"; gated on [D10]): **one reorder of the rest of the queue into our order**. Right after
  a start (and again after `reorder` - a flip, a shuffle), a background actor job places our items after the current
  one in our order with `ReorderTracksInQueue` (one call per item out of place, 50-100 ms each, the job yielding to steps
  between calls), the slots updated per move and re-read at the end (the version after it is ours). Then the speaker's
  own advance, its Next/Previous and the Sonos app's queue view all follow the kid's list. Skipped while a mode we
  didn't set is on; a fault -> untrusted, back to the correction.

### 2.7 The end of the list, repeat, "Til slutten"

- **Play mode: `NORMAL` always** (vibb; Q5, decided: "ok"; the one exception is a context's shuffle, §2.12). The kid's repeat (design 49) is **our gated advance**, as on a room
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
  (`sonos_spotify_queue.py` 2). **Unmapped but ours** (Q6, decided "ok": vibb's demoted fallback): the player shows the
  speaker's title - the socket's `name — artist`, else `TrackMetaData`'s `dc:title` and `dc:creator`, in the server's
  `"<title> — <artists>"` form - over the entry's cover, with the speaker's position, no number and no heart, through the
  renderer's in-place item swap (`Renderer.replaceItem`); it plays on and nothing is written. No art is ever fetched from
  the speaker or the socket's `imageUrl`.

### 2.9 Ours, adopt, the suspension, the park, the gate on the speaker's own advance

- **The stored marker and the baseline** (qa-54-revision N3): `renderer.json`'s uri for this kind is `queueUri(uid)`
  (`storedUri` returns the uri as played), and `RendererChoice` gains two optional fields written with it and after
  each read: `shareTarget` (the canonical target) and `queueUpdateId` (the read's `UpdateID`, §2.3 - the UPnP source,
  the one a restart can read again). An old file without them reads as "no baseline" (vibb's prefix rule then).
  `kindOfUri("x-rincon-queue:...")` = `SPOTIFY_SHARELINK`; `sameStream(marker, live)` = the live TrackURI is a Spotify
  queue track; `adoptIndex` for a Spotify entry = the item whose `uriOf(kind, key)` is the decoded uri. With a queue
  marker the item is **always found from the live uri**, never `RendererChoice.itemKey` (the queue moved on).
  **`roomVerdict` is OURS_LIVE for a Spotify queue track under a queue marker** (54-1: the park must stop an unlisted
  track of our playlist) **unless contradicted by UPnP**: with a baseline, one `Browse Q:0 BrowseMetadata` in the look's
  budget - an `UpdateID` that moved **and** a live track not in the stored entry's listing is a queue someone replaced
  (NOT_OURS: not stopped; the guest's music plays on). No answer to that Browse decides nothing against the Stop
  (OURS_LIVE). `SonosSuspension.bookmark` writes only for a mapped track.
- **Adopt at start** (`adoptLive`, zero transport commands): kept for our live PLAYING/PAUSED queue under the marker;
  one `GetMediaInfo` in the look, inside its budget, confirms `CurrentURI == queueUri(uid)` (Spotify Connect,
  `x-sonos-vli:`, is never ours), and the same baseline test as `roomVerdict` - a queue built in the Sonos app has the
  same `x-rincon-queue:<uid>#0`, so the `CurrentURI` alone never tells a guest. Then `learnQueue` as its own actor job
  (reads only): the read of §2.3 -> the slots and the version; it becomes the baseline only when the adopt kept the
  session (never a guest's queue).
- **The migration probe** for this kind (vibb `sonos_migrate_probe.py` 3): `MigrationProbe.live` returns the position
  too; a candidate matches only on `trackSpotifyUri == lastOursTrack` with `lastOursRel - 5 <= rel <= lastOursRel + 45`;
  no recorded track -> never follows. The follow adopts with the new uid's marker (`queueUri(new uid)`, not
  `Moved.uri`), keeps the slots and re-reads the version [D13].
- **The park** (26a §6.1's; vibb has none): Stop, **`RemoveAllTracksFromQueue`**, then `x-rincon-queue:<uid>#0` - for
  every kind (an empty queue costs nothing; for this kind the speaker's own Play button would otherwise play the
  playlist during a time rule). A refused clear is logged; the Stop already happened. **A normal Stop** (the kid's stop,
  a switch home) leaves the queue, as vibb.
- **The gate on the speaker's own advance** [ours; vibb has no time rule] - today a room's advance is our own gated
  play; with a queue the speaker advances by itself, so the gate is rebuilt four ways:
  1. **NORMAL** (§2.7): a missed park ends at the queue's end, never all night.
  2. **The controller's belt**: on every confirmed boundary (natural or pressed) and every ours PLAYING poll with
     `mayPlay()` false (the time rule, or the room scope's belt), one Pause - also in the 54-a correction's place.
  3. **A missed park is retried**: when the suspension's park ends NO_ANSWER (no Wi-Fi at the edge, a Stop that failed
     or came late) and `renderer.json` still names a room, a unique WorkManager chain `sonos-park-retry` runs the existing
     `suspensionStop` on Wi-Fi's return and every 5 minutes, for at most 2 hours after the edge, while the app is still
     suspended; it ends at the first definite verdict. For every kind (harmless for a `url` play). [D16+: whether
     Android runs a suspended package's work at all.]
  4. **The speaker's own sleep timer** (qa-54-revision N1, the coordinator's decision; the independent bound - it needs
     no phone, no Wi-Fi and no running process at the edge): AVTransport `ConfigureSleepTimer(InstanceID 0,
     NewSleepTimerDuration "H:MM:SS")` on our coordinator, `""` to cancel (SoCo `set_sleep_timer`); it stops the
     group's playback when it runs out [D21]. **Set** to the time left until the next instant the launcher will suspend
     the music app (`suspendAtMs`, below) at a transfer, at every confirmed boundary when it differs from our own last
     set by more than 30 s (the timer counts down by itself: no SOAP otherwise), after a follow (on the new
     coordinator), and when the restriction changes while a session plays; only for this kind (a `url` play's missed
     park costs at most one episode; podcasts stay untouched), never at adopt (zero transport commands - the first
     boundary or restriction change after it sets it). Capped at 23:59:59; no edge known, or an edge more than a day
     away, sets none. **Cleared** (`""`) by our own Stop, the park and a hand-off, and when the edge goes away (a
     parent's change), but only while `GetRemainingSleepTimerDuration` is within 30 s of what our own last set would
     have left - a timer someone set in the Sonos app afterwards is theirs and stays. A timer the household had set
     before our transfer is replaced while our session plays (stated in §2.13). **The edge, `suspendAtMs`**: a new
     restriction key from the launcher (epoch ms, 0 = none; §2.14): the next instant its time rules suspend the music
     app (`TimeRulesRuntime.nextBoundary` and the policy at that instant), and the screen-time budget's lock only while
     the launcher can foresee it as an instant - listening doesn't spend the budget, so with the screen off it has no
     edge, and its lock comes while the phone is in use (the service up, the ordinary park). **What the Sonos app
     shows** [D21]: its own sleep-timer countdown for the room; a household member can change or cancel it there (our
     next set at a boundary puts it back - the parent's rule is the edge). **A parent's extension after our service has
     gone** leaves the old timer: the room stops at the old edge, and the next play from the phone sets it anew.
  **The fallback, truthfully**: with no edge known (the budget) and no retry run (D16+ fails), a missed park lets the
  rest of the queue play; NORMAL ends it at the queue's end (at most the server's 1000 tracks). The belt protects only
  while the service runs.

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
- **The kid's sleep timer** (minutes): unchanged - the app's own fade and Pause, never the speaker's timer, which is the
  time rule's bound only (§2.9 item 4; whichever comes first stops the music). **"Til slutten"**: §2.7. **Calls**: the
  same pause and resume verbs.
- **The cadence**: for this kind the 5 s tail cadence (45 s before a track's end - a quarter of a 3-minute song) runs
  only **without a healthy socket, or while an at-end action is armed**; with a healthy socket the `itemId` confirm
  covers the boundary, and the queue's end is found from the anchor (§2.5, qa-54-revision N1b). The 1 s display poll
  is design 53's.
- **Locks**: a self-advancing queue holds the phone's partial wake lock and Wi-Fi lock for as long as it plays (today's
  rule: armed and playing) - hours for a long playlist. Accepted; the disarm after 10 minutes paused is unchanged.

### 2.12 Context mode on a room (in 54-a2 - the user's Q3)

The user (2026-10-21): "vi sender jo bare hele sharelinken selv om ikke vår konto kan lese den vel? så vi burde vel
spille av den? ... jeg ser ingen grunn til at det ikke går i samme runde nå". Right: the speaker plays the share link
through the household's account whatever our server can list. Most playlists a parent pastes are context mode in dev
mode (26c §3.3), and the 54-0 run played exactly such an editorial playlist. So context mode ships with list mode, in
54-a2. The one part behind a device check is the context's shuffle [D8+].

- **The transfer** is §2.2's, with no listing: no `listed`, no slots - the queue read (§2.3) is kept as the queue's own
  uri list (for `findInQueue` and `ours` (b)), and every track of our queue is the one synthetic item `context`.
- **Ours**: §2.5's rule. The container is the strongest evidence here - the 54-0 run showed it naming our playlist -
  and (b) reads "not in the queue we read". The baseline is persisted as in list mode (§2.9), so a guest's queue is
  told apart after a restart and in the standalone path too.
- **What the app shows** (the same as the web player's context mode, 26c §5.9): the entry's tile and one item, titled
  by the live track - the socket's `metadataStatus` `name — artist` (the server's `"<title> — <artists>"` form), else
  UPnP's `TrackMetaData` `dc:title`/`dc:creator` - written as the live track (uri + title) through the store port the
  web player uses (`spotify_context.json`), on a change only. The picture is **the entry's cover** (the web player's
  context shows the same; the metadata's `imageUrl` is not fetched - no new fetch path). The position and duration are
  the speaker's (`durationMillis`, else `TrackDuration`); no "Spor N av M", no heart, no number. The socket's `nextItem`
  is not shown (a cross-check only).
- **Resume**: the context bookmark is the synthetic item's position plus the live track (26c's model). A start looks
  for the live track with `findInQueue`. Found, it plays at the saved place. Not found, it plays position 1 with no
  seek (§2.2 step 7; 26c's "one more play from the top"). Phone <-> room continue on the same track. The web player
  gets `context_uri` + `offset.uri` + the position; the room gets `findInQueue`. Every confirmed track change
  rewrites the live track; a bookmark is the position of the track playing.
- **Steps**: `stepsOnWebPlayer(entry)` routes next/previous to the room's controller: `play_from_queue(Track ± 1)`
  (gated; previous more than 5 s in restarts at 0, vibb); past the queue's ends nothing. Repeat as §2.7 (NORMAL, our
  gated advance: repeat all = position 1 after the end; repeat one = the at-end seek to 0).
- **Shuffle** (design 49 gives a context Spotify's own shuffle on the phone): on a room the speaker's own
  `SHUFFLE_NOREPEAT` - **the one exception to "NORMAL always"** (§6; qa-54-revision N7): a shuffle that never repeats
  by itself. While shuffled a step is AVTransport `Next`/`Previous` (the speaker's pick; gated), and turning shuffle off
  sends `NORMAL`; every transfer starts with NORMAL (or `SHUFFLE_NOREPEAT` when the entry is shuffled). Gated on
  [D8+] (`Track`, `TRACK_NR` and `Next` under shuffle); until it passes, a context on a room plays in the playlist's
  order and the shuffle slot does nothing there.
- **End**: the queue's end -> `ended` (a context item is never marked done, `keepsDuration`). An artist context:
  refused (`CANT_PLAY_ON_SONOS`).

### 2.13 The Sonos household

- **What it needs**: Spotify linked in the Sonos app (Settings › Services › Spotify) - **the household's account**,
  often a parent's: that account plays on the speaker, not the kid's phone account, and the kid's listening lands in
  its history and recommendations (the user, Q2: "enig at det er greit at lytting havner på tilkoblet spotify konto"). A playlist the linked account can't open fails the add or adds nothing [D12]. **One
  stream per account**: that account playing elsewhere stops the room - followed as a stop we didn't make ("Høyttaleren
  stoppet"), nothing restarts by itself [D18].
- **No account document**: `/status/accounts` is empty on the house's firmware (§0.3), so it is not read for Spotify
  at all. **The add's answer decides**: both service types refused, or 0 tracks added -> `ShareRefused` -> the failed
  start (26a §4.2: back to the phone, paused) with `SONOS_ASK_ADULT`, "Kan ikke spilles på Sonos - spør en voksen" /
  "Can't play on Sonos - ask a grown-up". `lost()` takes the reason (today it always emits `CANT_PLAY_ON_SONOS`); a
  network failure during the add is its own exception (`SpeakerUnreachable`) and the ordinary `CANT_PLAY_ON_SONOS`.
- **The speaker's sleep timer** (§2.9 item 4): while a session of ours plays before a time-rule edge, the room's
  sleep timer in the Sonos app shows our countdown to that edge; a timer the household set before is replaced while
  our session plays, one set after it is left alone.
- **The parent** learns it from the kid, and from one line of copy on the PWA's Spotify page ("On Sonos, a link plays
  through the Spotify account linked in the Sonos app") - server copy only (§2.14). No report field (a contract).

### 2.14 Server, PWA, contracts, launcher, logging, licences

- **Server/PWA**: no API, no migration, no policy key; the room scope applies unchanged; no parent switch (Q2,
  decided). Optional: the Spotify page's one line of copy, a server-only commit with its test.
- **One shared contract change** (for §2.9 item 4's edge; qa-54-revision N1): a restriction key `suspend_at_ms` (long,
  epoch ms, 0 = none) in `bridge_contract.json`'s `restrictions`, both byte-identical copies, written by the launcher's
  restrictions writer, read live by the music app (`BridgeConfig`, like `volume_cap_pct`) and re-read at
  `BRIDGE_CHANGED`. Absent (an older launcher) = 0. Launcher and music in one commit, with `MusicPlanTest`/`BridgeTest`
  (CLAUDE.md's rule for the bridge). No other contract changes: `server/testdata/*`, `music_state.json`, the policy
  JSON, the AIDL and `spotify_hosts.json` stay. The fake speaker reads `server/testdata/spotify/` as test
  data (§3), not a contract (`music.yml` already triggers on it).
- **Launcher**: only `suspend_at_ms` - computed from `TimeRulesRuntime.nextBoundary` (`L/timerules/`) and whether the
  policy suspends the music app at that boundary, written again whenever it changes the restrictions (a policy, a
  boundary passed, a parent's extension), and the budget's lock only when it is a foreseeable instant. The phone sends
  only SOAP to the LAN; the speaker reaches Spotify itself, outside the
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
  fixtures name the same tracks; the raw files stay in `~/handy-captures/`, never committed). **New UPnP fixtures** in `sonos/` (from the run's answers where it read them - the add,
  `GetMediaInfo`, GetPositionInfo while the queue played -, else hand-written in SoCo's shapes, marked provisional in
  `SonosFixturesTest` until the 54-0 remainder replaces them): `add_uri_to_queue.xml` (1, 30, 30), `add_uri_to_queue_empty.xml`
  (0 added), `browse_queue_page.xml` (`Q:0/n` items with `res` `x-sonos-spotify:spotify%3atrack%3a<id>?sid=9&amp;flags=8232&amp;sn=6`,
  a duplicate, an extra track, `UpdateID`), `browse_queue_meta.xml` (`childCount`, `UpdateID`), `position_spotify_queue.xml`,
  `transport_settings.xml`, `media_info_queue.xml`, `sleep_timer.xml` (`GetRemainingSleepTimerDuration`),
  `fault_800.xml` (code provisional, [D12]); the goldens
  `sharelink_{playlist,album,track,show,episode}.xml` written by SoCo (§2.2). `accounts.xml` stays NRK's only.
- `ShareLinkTest`: each type's `EnqueuedURI` and metadata byte for byte against SoCo's goldens; artist and junk -> null;
  2311 then 3079 on a fault, not on a network failure.
- `SonosQueueTest` (`logic/`, pure): `trackSpotifyUri` (both prefixes, `+` kept, junk null); **the slots** for a gap, an
  extra track, a duplicate, a capped reversed show and a relinked id; `itemAt`; trust by version; the confirm's
  verification; the nearest occurrence when untrusted; `follows`; `boundary` (the anchor within 3 s of the duration;
  `endsNear` without a socket); the at-end arming (not for our last item at the queue's last position; yes when a slot
  follows); the unmapped title form; **the anchor's `endsNear`** at a STOPPED with no poll in the last 20 s (N1b);
  **ours's contradiction** - an absent, empty or unknown-form container is no evidence, another Spotify container is
  (N2); the version kept with its source and a missing source re-read, never a mismatch (N5); **the sleep timer's
  value** from `suspendAtMs` (none for 0 or past a day, the 30 s re-set threshold, the clear only for our own).
- `SonosTransportTest` `d54`: a transfer's SOAP order (the hush around it, the cap before `SetPlayMode`, the gate before
  `SetPlayMode` and again before `SetAVTransportURI` - a closed gate sends nothing more; NORMAL; the 3079 retry on a
  fault only; both faults and a 0-track add throw `ShareRefused` with no Seek, a network failure `SpeakerUnreachable`
  with no retry; a start item not in the queue plays position `first` with no settled seek (N4); `ConfigureSleepTimer`
  after Play with the value given, `""` on our Stop and the park; the start's page; `play_from_queue`'s
  three calls; the settled seek; `readQueue` afterwards, outside the hush); every `Browse` carries `SortCriteria`;
  `queuePlay` hushed as vibb's `sonos_hush.py` 5; `park` = Stop, clear, the queue uri.
- `SonosSessionTest` `d54`: classify for the queue kind (ours for an unlisted Spotify track of our queue; not ours for
  `x-sonos-vli:` or another container; a moved version alone keeps ours; `trackNo`; no `lostSession`; no stall retry); vibb's `sonos_migrate_probe.py` 3.
- `SonosEventsTest` `d54`: `itemId`, `queueVersion`, `playModes`, `previousPositionMillis` and `metadataStatus`'s
  current/next/container parsed from `spotify_queue_2026-10-21.json`; absent fields null; another service's metadata
  ignored.
- `SonosControllerTest` `d54` - vibb's `sonos_spotify_queue.py` ported: (1) a start resumes the bookmarked track at its
  second with **one** transfer; (2) the shown item is our list's, never the speaker's metadata; (2b) a guest's Spotify
  (another container; a replaced queue - moved version and a track in neither list) is not ours; (3) a step is one `queuePlay`, never an add - **and a second step
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
  bookmark. Revision check: a duplicate at the next position triggers the recheck by `Track` (N6); a step before the
  read finished is a `queuePlay` that waits for it, never a transfer (N6); a pick of an item added since the read is one
  transfer (N6); a pressed change near the end without the anchor's verdict is never corrected (N8); the queue's end
  found by the anchor with a healthy socket and no tail poll (N1b); the sleep timer set at a transfer and at a boundary
  when the edge moved by more than 30 s, never at adopt, re-set after a follow, cleared by our Stop only while it is
  ours (N1). **Context mode** (Q3, in 54-a2): the live track from the metadata, then `TrackMetaData`; resume by
  `findInQueue`, not found = position 1 with no seek; steps ±1 through `play_from_queue`; shuffle `SHUFFLE_NOREPEAT` and
  `Next`/`Previous` only behind its switch; the end never marks done.
- `SonosLifecycleTest`: `kindOfUri`, `sameStream`, `adoptIndex` for a queue marker; `roomVerdict` OURS_LIVE for an
  unlisted queue track under a queue marker; NOT_OURS with a persisted baseline, a moved `UpdateID` and a track not in
  the stored listing; OURS_LIVE when that Browse doesn't answer (N3); an old `renderer.json` without the baseline reads
  as vibb's prefix rule; the standalone bookmark only for a mapped track. `SonosSuspensionTest`: a
  NO_ANSWER park schedules the retry, a later OURS_LIVE parks, a definite verdict ends it.
- `MusicPlanTest` (launcher) and `BridgeTest` (music): `suspend_at_ms` in both `bridge_contract.json` copies; the
  launcher's value from its next boundary only when that boundary suspends the music app (a lock), 0 otherwise.
- `PlayBodyTest`/`RendererRulesTest`: the drop reasons by kind and mode. `GuardsTest`: `comeHome` reaches the web
  player for a Spotify entry; `lost()`'s reason. `SourceScanTest`: the new words name no source; `x-rincon-cpcontainer`
  only in `ShareLink.kt`; no Spotify id or title in a release-built Sonos log line.
- **`scripts/fake-sonos.py`** (the queues come from `server/testdata/spotify/` - `playlist_items.json`,
  `album_tracks.json`, `show_episodes.json`, `track.json`, `episode.json`): `AddURIToQueue` (the ShareLink uris),
  `RemoveAllTracksFromQueue`, `SetAVTransportURI x-rincon-queue:`, `Seek TRACK_NR`, `SetPlayMode`/`GetTransportSettings`,
  `Browse Q:0` (both flags, paged, `UpdateID` moving on each edit), `GetMediaInfo`, `ReorderTracksInQueue` (54-b),
  `ConfigureSleepTimer`/`GetRemainingSleepTimerDuration` (the timer stops playback when it runs out), the
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
- **54-a2 - Spotify on a room, list and context mode** (the feature on; context mode folded in by the user's Q3; if
  its size calls for it, split into a2 = list mode and a2' = context mode, delivered together): `SonosController` (the map, ours/mapped, steps vs transfer, the tile,
  `queueMoved`, the boundary, the correction, the at-end timer, repeat by our advance, the belt, the `itemId` recheck and
  pairing, the unmapped look, `learnQueue` at adopt), `SonosRenderer` (`setModes`, `pauseAtItemEnd`), the service
  (`speakerDropReason`, `lost()`'s reason and `SONOS_ASK_ADULT`, `comeHome`, the listing's keys handed to `load`),
  `SonosLifecycle`/`SonosSuspension` (OURS_LIVE and the baseline, the park retry), the speaker's sleep timer and the
  `suspend_at_ms` contract commit (launcher + music, §2.14), context mode (§2.12), the strings, the logging,
  `GuardsTest`/`SourceScanTest`, emulator.md §6h, the docs. Its code QA takes the controller and the lifecycle in separate passes (qa-54 L14).
- **54-b - our order without the blip** (Q1, decided; gated on [D10]): the one-pass reorder, its version accounting
  (§2.3), the fake's reorder.

(The former 54-c, context mode, is in 54-a2 - the user's Q3.)

Docs with 54-a2: `music/CLAUDE.md` ("Spotify is the third renderer": a room plays a Spotify entry through the ShareLink;
a rule block "Spotify on a room (design 54)" - the read map, ours vs mapped, the heal, NORMAL and the belt, the park's
clear and its retry, the logging, the words; the DIDL rule's sentence naming the ShareLink metadata as SoCo's template,
pinned by SoCo-written goldens), design 26c §11/§12/§13/§14 pointing here, design 26a's §9 row and its "not ported"
`sonos_migrate_probe.py` 3 / `sonos_hush.py` 5 note, `NOTICE.md` (SoCo), `PLAN.md`.

## 5. Owed device checks (the user's speakers and Pi, the Jelly Star, a debug build; logcat `VibbSonos`)

Through the play test unless "the app" is named. Done in 54-0 (§0.3): D1 (the accounts document - empty, dropped), D2's
add (0.8 s, 30 tracks, first 1), D4 (TrackURI, Track, TrackDuration), D5 (TRACK_NR playing and paused), D6's natural
advance (gapless, a 0.5-1.0 s socket lead, no BUFFERING, `queueVersion` unchanged), D8's NORMAL/REPEAT_ALL.

1. **D2+** The add's time for 300 and 1000 tracks; the largest queue one ShareLink gives; `NumTracksAdded` and
   `NewQueueLength` for a private playlist of the kid's.
2. **D3** A full Browse against the server's listing for three or four of the house's playlists and a show: relinked
   ids, unplayable and local tracks, episodes, duplicates, the order; a Browse item's `res` equal to the TrackURI.
3. **D5+** `TRACK_NR` while STOPPED; the settled REL_TIME seek after a jump (701), heard muted.
4. **D6+** The `itemId` change on a press; how long GetPositionInfo lags it; the correction's audible length (the app).
5. **D7** The queue's end under NORMAL: the transport, `Track` and `TrackURI` after it, the socket's IDLE.
6. **D8+** `REPEAT_ONE` on a queue; the household's crossfade (`playModes.crossfade`) and its effect on the boundary and
   the at-end timer; `TRACK_NR`, `Track` and AVTransport `Next`/`Previous` under `SHUFFLE_NOREPEAT` (set by us and by
   the Sonos app) - **the gate for a context's shuffle on a room** (§2.12); the container's form for an album, a show
   and a track (N2).
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
19. **D21** The speaker's sleep timer (§2.9 item 4): `ConfigureSleepTimer` and `GetRemainingSleepTimerDuration` on a
    coordinator playing our queue; what the speaker does when it runs out (pause or stop, the group with it); how the
    Sonos app shows it (the room's countdown) and whether a change there reads back; whether a transfer, a `TRACK_NR`
    jump, a follow or our park keeps or resets it; that it ends a queue whose park was missed (the edge with the phone
    off Wi-Fi). If it fails, the fallback of §2.9 stands as written, and the user decides whether that residual is
    accepted.

## 6. Deviations from vibb

| vibb | Here | Why |
|---|---|---|
| the map is positional from `q_base` over go-librespot's own listing | the map is one read of the queue (slots), trusted by the queue's version | our listing is the server's, filtered (local, unplayable, relinked, capped, duplicates) |
| trusted iff `qlen == len(rows)` | trusted while the queue's version is ours | an exact map holds whatever the lengths; only an outside edit breaks it |
| `ours` = the prefix | the prefix unless contradicted (another container; or a moved version with a track in neither list), the baseline persisted in `renderer.json`; "mapped" separate | a guest's queue is detected, not assumed, also after a restart; our own unlisted tracks stay ours (the park) |
| a track change adopted at the next poll | the socket's `itemId`/metadata wake the confirm and its recheck; natural vs pressed by our anchor | design 53's events; the user's expectation for the speaker's buttons |
| Spotify's order only; `sonos_step` wraps `% n` | our order through the correction (54-a) or one reorder (54-b); the service's skip rules | designs 39, 49 and 50 are the user's |
| no time rule; `_sonos_on_term`'s Stop | the park clears the queue; the belt pauses the speaker's own advance; a missed park is retried; the speaker's own sleep timer runs to the next time-rule edge | our time rule with a queue (26a §6.1) - a consequence, not a choice |
| no account check | none either - the add's answer picks the words | `/status/accounts` is empty on the house's firmware |
| a context resumes through go-librespot's listing | through `findInQueue` of the live track (in 54-a2) | no listing on the phone |
| NORMAL always, no shuffle on Sonos | a context's shuffle is the speaker's `SHUFFLE_NOREPEAT`, its steps AVTransport `Next`/`Previous`; every other case NORMAL | design 49's context shuffle is Spotify's own on the phone; a context has no list of ours to order (gated on [D8+]) |

Kept as vibb (Q5, decided): NORMAL always. Kept as vibb (Q6, decided): an unlisted track of our queue is ours and
shows the speaker's title.

## 7. What this design does NOT do

- No change to RSS/NRK podcasts or Storytel on a room (one URI, our queue, vibb's DIDL) - the user's rule.
- No Spotify Connect to the speaker (`x-sonos-vli:`), no Web API call for a room, no web player, no token.
- No artist on a room. No Liked Songs, no Sonos playlists or favourites.
- No art fetched from the speaker or the socket; no speaker metadata shown for a mapped track.
- No parent switch, no report field; no contract change but `suspend_at_ms` (§2.14); no `/status/accounts` read for
  Spotify.
- No fight with the Sonos app: a play mode, a shuffle, a queue edit or a volume set there is followed, never undone
  until the kid's next explicit press.

## 8. The user's answers (2026-10-21) - all six decided

- **Q1. Our order on a room** (a shuffled, flipped or "newest first" list over a queue in Spotify's order): the user
  "enig" - **one reorder of the rest of the queue into our order right after the start** (round 54-b, gated on [D10]);
  until then, and for good if [D10] fails, the short correction at each natural track change (§2.6).
- **Q2. A parent switch**: "enig at det er greit at lytting havner på tilkoblet spotify konto" - **no switch**; it plays
  wherever the Sonos app has Spotify linked, on the rooms the room scope allows, with the linked account (§2.13).
- **Q3. Context-mode playlists on a room**: "vi sender jo bare hele sharelinken selv om ikke vår konto kan lese den
  vel? så vi burde vel spille av den? ... jeg ser ingen grunn til at det ikke går i samme runde nå" - **yes, in 54-a2**,
  not a later round: the speaker plays the share link through the household's account whatever our server can read;
  the app shows the live track from the socket's metadata and resumes at the last track (§2.12).
- **Q4. Spotify shows and episodes**: "ja ... samme oppførsel som vibb" - **share links into the speaker's queue**, each
  episode resuming at its own place; RSS/NRK podcasts and Storytel untouched (§2.1).
- **Q5. Repeat on a room**: "ok" - **vibb's NORMAL always, repeat by our gated advance** (§2.7).
- **Q6. A track of our queue that our list doesn't have**: "ok" - **vibb's: it stays ours and plays on**, the speaker's
  title over the entry's cover, no bookmark (§2.8).

Still open with the user only if a device check fails: [D21] (the speaker's sleep timer) and [D16+] (a suspended app's
work) - if both fail, whether the residual of §2.9's fallback is accepted.

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

## Revision check addendum (2026-10-21, `qa-54-revision-check.md`, and the user's answers)

All ten taken; none disputed.

- **N1** the speaker-side `ConfigureSleepTimer` is the independent bound (§2.9 item 4): set to the next suspension edge
  at a transfer, a boundary (when it moved more than 30 s), a follow and a restriction change; cleared by our Stop,
  the park and a hand-off, only while it is ours; the edge comes from the launcher as the new restriction `suspend_at_ms`
  (§2.14 - the one contract change, launcher + music in one commit). The fallback is now written truthfully (a missed
  park with no edge and no retry plays the rest of the queue, NORMAL ends it at the queue's end); [D21] and [D16+]
  owed.
- **N1b** `endsNear` also from the anchor (§2.5 step 2), chosen over keeping the tail cadence: no extra polls, exact
  with a healthy socket whose IDLE wakes the confirm; without a socket the tail cadence runs anyway.
- **N2** an absent, empty or unknown-form container is no evidence; only another Spotify container, or a moved version
  with a track in neither list, contradicts `ours`; the park's final verdict is UPnP's; only a playlist's container was
  measured (§0.3, §2.5).
- **N3** the target and the read's `UpdateID` are persisted in `renderer.json`; adopt and the standalone suspension
  compare them with one `BrowseMetadata` (§2.9); `learnQueue` becomes the baseline only for a kept session.
- **N4** a start item not in the queue: position `first`, no seek, no bookmark, no index move; the read sets the index
  (§2.2 step 7).
- **N5** the version is kept with its source; a missing source is a re-read, never a mismatch; 54-b counts its own
  moves and ends with a re-read (§2.3).
- **N6** while the read runs, steps are `queuePlay`s that wait for it in the actor; a pick of an item added since the
  read is one transfer; the recheck triggers on `Track` or the uri (duplicates) (§2.3, §2.5).
- **N7** a context's `SHUFFLE_NOREPEAT` and `Next`/`Previous` are the one exception to NORMAL, in §6, gated on [D8+]
  (§2.12).
- **N8** the correction runs only on the anchor's natural verdict, never on the `endsNear` fallback (§2.5, §2.6).
- **N9** the lag is 0.5-1.0 s, bounded by the run's 1 s poll ([D6+] owes it); a network failure in the add is its own
  exception with the ordinary words (§0.3, §2.2, §2.13).
- **N10** the raw 54-0 files are kept outside the repo in `~/handy-captures/`; 54-a1 scrubs them into fixtures and never
  commits them raw (§0.3, §3).
- **The user's answers** (§8): Q1 one reorder (54-b), Q2 no switch, Q3 context mode in 54-a2 (§2.12 rewritten: what the
  app shows - the live track's title over the entry's cover, the speaker's position - and how it resumes), Q4 shows and
  episodes as share links, Q5 NORMAL, Q6 vibb's fallback.

## 54-a1 status (2026-10-21, branch `d54-a1`)

Built as §4 lists it; nothing changes for the kid (`speakerDropReason` still `"spotify"`, pinned by `PlayBodyTest`).
53-d's renamed constants (`CONFIRM_EVERY_MS`/`CONFIRM_UNTIL_MS`) stand for §2.5's `IDLE_RECHECK_*`; a1 touches neither.

- **`sonos/ShareLink.kt`**: SoCo's share types, `canonical_uri`'s pattern (no artist), `EnqueuedURI` and the metadata
  template with `dc:title` empty; `shareLinkUri`/`shareLinkMetadata`; the only file spelling `x-rincon-cpcontainer`
  (`SourceScanTest`). **The goldens** `sharelink_<type>.xml` hold `AddURIToQueue`'s five arguments in SoCo's order with
  a `writer` attribute (the note named the files, not their content): `sonos-play-test.py --golden` writes them with the
  real SoCo when importable, else `share_request`. The committed ones are `share_request`'s; this session ran SoCo
  master's `sharelink.py` (stub base classes) over the same inputs - byte-identical. The Pi run with vibb's SoCo is owed
  (`sonos/README.md`).
- **`logic/SonosQueue.kt`** (pure): `trackSpotifyUri`, `QueueRead` (+ pages), `versionCheck` (N5), `QueueMap` (slots,
  `itemAt`, `verifies`, `nearest`, `follows`), `queuePagesAround`/`positionNearest`, `containerContradicts` +
  `shareOurs` (N2), `shareProbeMatches`, `boundary`/`Boundary` (N8: `correctable` only on the anchor), `anchorNearEnd`
  (N1b), `atEndAction`/`atEndDelayMs` (54-8), `unmappedTitle`, `sleepTimerFor`/`sleepTimerResetDue`/`sleepTimerOurs`
  (N1). Only a playlist container counts as evidence: any other form is no evidence until [D8+].
- **`Kind.SPOTIFY_SHARELINK`**; `builtDidl` throws for it, `SonosTransport.play` refuses it; `kindOfUri` maps
  `x-rincon-queue:` to it (dormant: nothing stores the marker before 54-a2). `PlayBody` gains `trackIndex` and `listed`
  (its `toString` prints counts for a share body); `PlayBodies.share` builds one (pure, not called yet).
- **`Soap`**: the ContentDirectory service, `Browse Q:0` with all six arguments (no `InstanceID`), `parseQueuePage`/
  `parseQueueMeta`. **`SpeakerControl`**: `setPlayMode`, `playMode`, `addUriToQueue`, `seekTrack`, `browseQueue`,
  `mediaInfo`, `configureSleepTimer`, `remainingSleepTimer`; `SpeakerIo.queueMeta` (the classify's one read). Not built:
  AVTransport `Next`/`Previous` (54-a2's context shuffle, [D8+]) and `ReorderTracksInQueue` (54-b).
- **`SonosTransport`**: `playShare` (§2.2's order; `ShareRefused` for both faults, 0 added or no share type;
  `SpeakerUnreachable` without a second try; not found = `first`, no seek), `findInQueue`, `readQueue` (an `UpdateID`
  that moves between pages reads the queue again once), `learnQueue` + `shareBaseline` (adopt's read; the baseline only
  through the second), `queuePlay` (hushed, the cap on a START, `session.jumped` re-arms a disarmed session),
  `setPlayMode`, `playMode`, `sleepTimer`. **The park clears the queue for a share link only** (Stop, the queue uri,
  then the clear; a refused clear logged) - the fix round's change of §2.9's "every kind" (qa-54a1-code 8): a queue the
  household built survives an NRK or podcast session's bedtime park, and a slow clear never costs 26a M5's queue uri.
  **The speaker's sleep timer is
  in a1 as SOAP**: `playShare`'s step 10b (`sleepTimer`; null clears only ours), and our own Stop and the park clear it
  only while `GetRemainingSleepTimerDuration` is within 30 s of what our last set has left (`session.ownSleepTimer`); a
  hand-off clears it through its Stop. Nothing sets one yet: the edge (`suspend_at_ms`) and when to set it are 54-a2's.
- **`SonosSession`**: `shareSet`/`shareRead`/`shareContainer`/`jumped`; classify for the kind (§2.5's `ours`, one
  `BrowseMetadata` per unknown track, cached; `trackNo`, `trackSpotifyUri`; no lost-session, no DIDL check);
  `stallBookkeeping` null for it; `lastOursTrack`/`lastOursRel` and the probe's match (`MigrationProbe.live` now returns
  `ProbeLive` with the position). `adopt` and a play of another kind clear the share knowledge.
- **`SonosEvents`**: `Playback` gains `queueVersion`, `playModes` (`WsPlayModes`), `previousPositionMs`; the existing
  53-d `Metadata` gains `track` (`WsTrack`: uri, name, artist, duration - service 9 or a `spotify:` id) and `nextUri`
  instead of a new event. Both print no id or title.
- **Logging (54-11)**: share lines carry kinds, counts and positions; `Fields`, `PositionInfo`, `ProbeLive` and the
  session's "not-ours?" line print a Spotify queue track by its prefix only (`Fields.shown`).
- **Fixtures**: `ws/spotify_queue_2026-10-21.json` (the 53 messages, the probe's new Spotify-id rule on top of
  `--rescrub`: ids of `server/testdata/spotify/`, positions 1-4 = `1FakeTrackA`, `C`, `D`, `E`; the raw files stay in
  `~/handy-captures/`); UPnP `add_uri_to_queue.xml` and `media_info_queue.xml` from the run, the rest hand-written and
  provisional (`sonos/README.md`).
- **`scripts/fake-sonos.py`**: the queue from `server/testdata/spotify/` (a playlist's episode in the queue is an
  extra), SoCo's add (2311/3079, fault 800), the clear, the queue as the source, `TRACK_NR`, its own advance under the
  play modes (the end STOPPED with its track kept - provisional [D7]), Browse both flags paged (402 without
  `SortCriteria`), `UpdateID`, `GetMediaInfo`, the sleep timer (it pauses when it runs out - provisional [D21]),
  `--refuse-share`, `--empty-share`, `--queue-reversed`, `--queue-extra`, `--queue-gap`, `--track-seconds`, script events
  `next`, `previous`, `queue-insert`, `shuffle`; a migration's `move` carries the queue. `--selftest` step 6.
- **`scripts/sonos-play-test.py`**: `--more` (the 54-0 remainder: the full Browse against `--listing`, the queue's end,
  `REPEAT_ONE`, `SHUFFLE_NOREPEAT` with `Next`/`Previous`, `ReorderTracksInQueue`, the sleep timer), `--add-only LINK`
  ([D12]), the share goldens.
- **NOTICE.md and `assets/licenses/MIT-SoCo.txt`** now, not with 54-a2's docs: SoCo's code ships in the APK from this
  round.

**54-a2 inherits**: `adopt` clears the share knowledge, so the adopt's classify runs on vibb's prefix rule - with a
persisted baseline the look must call `shareSet` + `shareRead` before it classifies (an `adoptLive` parameter);
`adoptLive` re-adopts with `kindOfUri(kept)` whenever `keep` names another uri, and the service's `keep` returns the live
TrackURI - for a share session that is an `x-sonos-spotify:` track, so the session would become a one-track `url` one
whose next track is "not ours": for a queue marker `keep` returns the stored marker, or `adoptLive` keeps
`SPOTIFY_SHARELINK` when it adopted with it (qa-54a1-code 9); a `playShare` whose second gate closed returns its start
with `played = false` (the queue was replaced: the controller keeps that map, §2.3); the controller feeds `shareContainer` and decides trust with `versionCheck`; the sleep
timer's edge, its re-set at boundaries and follows (`sleepTimer`), and `GetTransportSettings` before a correction
(`playMode`); music/CLAUDE.md, emulator.md §6h and the rest of §4's docs.

**54-a1 fix round** (`docs/review/qa-54a1-code.md`, 0 High, 1 Medium, 9 Low, 2 Nit - all taken): `ours` for a share link
adds to vibb's uri equality instead of replacing it (1: a transfer that never landed keeps the previous stream ours, so
the park and every Stop gate still stop it), `lastOursTrack`/`lastOursRel` only from an ours, live queue track and never
overwritten with nothing (1, 7); the second gate's transfer keeps its map in the session and returns it (2); a second
queue edit during a read leaves no baseline (3); short Browse pages read on from where they ended (4); the start takes
the map's slot of a duplicate once every earlier position is read, the nearest occurrence only on a partial read (5); an
unanswered `BrowseMetadata` is cached, asked again after 60 s (6); the park sends the queue uri before its clear and
clears for a share link only (8); 9 above; the probe's scrub covers `%253a`, `open.spotify.com/<kind>/<id>` and
`spotify:user:<name>` (`--selftest`; both committed captures re-scrub byte-identical) (10); `MediaInfo` prints its uri as
the other holders do, `play()` refuses a share body before it touches the session, music/CLAUDE.md's park line, and the
"not-ours?" line is vibb's: none for the share kind (11, 12).
