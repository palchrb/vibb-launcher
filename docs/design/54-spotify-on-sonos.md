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

## Hand-over timing (2026-10-25, music round r13, branch `music-r13`)

PLAN's music queue (the user, 2026-10-25: a first play on a room "after a while" waits several seconds with nothing
shown, "så barnet ikke tror den bare henger", and felt slower than the spike `sonos-play-test.py --spotify`). Measure
first, cut afterwards: this round adds the measurement and the player's quiet line, and **cuts nothing**.

**What can be measured today.** Round 54-a2 (the controller's share-link path) is not built: `speakerDropReason` still
returns `"spotify"`, so a Spotify tile on a room says "Kan ikke spilles på Sonos" at once and `SonosTransport.playShare`
is reached only by its tests. What the user saw was therefore either a podcast, NRK or Storytel play on the room (the
same hand-over machinery: the actor job, `pending`, the landing poll) or the phone's web player starting cold (the
page, the SDK from `sdk.scdn.co`, the token through the launcher, the Connect device, `PUT /me/player/play` - design
26c). The user's words name a Spotify playlist and the spike's `--spotify`, so the web player is the likelier one
(qa-music-r13-code Q-3); the user decided (2026-10-25) that the phone's path gets the same quiet line too (below). The
timing below measures the room's hand-over today, and logs the share link's own steps already, so 54-a2's first run on
a real room measures them without another round.

**The step list** (one line per step under the logcat tag `VibbHandover`, `#n +N ms` - the timeline's number and the
time since its tap (fix round, Q-1: a second tap while the first play runs is `#n+1`, and the first play's job keeps
printing under `#n`, timed from its own tap; the service marks the tap whose command it runs, the controller's play
continues only that one, the play job carries its timeline onto the actor); step names, kinds,
counts, states and positions only - never an id, a uri or a title, so a release build logs them too;
`sonos/HandOverTiming.kt`):

| # | Line | Where |
|---|------|-------|
| 0 | `tap: PLAY_ENTRY` (`PLAY_ITEM`, `PLAY_FAVOURITES`, `RESUME`, `RESTORE`, `SKIP_TO`, `OUTPUT`) - t = 0; a play without a tap (a step, the advance, the play button) starts its own timeline, `(no tap)` | `PlaybackService.onCustomCommand` |
| 1 | `the service runs it (the renderer settled, the switch lock held)` | the command's coroutine |
| 2 | `the play queued for the room's actor [START\|STEP\|ADVANCE, url\|nrk_program\|storytel\|spotify_sharelink]` | `SonosController.playIndex` |
| 3 | `the actor runs the play` (the wait behind a poll, a live read, a mint already queued) | the play job |
| 3b | `the Storytel URL minted` | the play job |
| 4 | `the speaker resolved (its description read)` - `speaker(uid, verify = true)` | `SonosTransport.play`/`playShare` |
| 5 | `hush and cap done` (a start at 5 s or more, or a cap in force) | idem |
| 6 | url: `RemoveAllTracksFromQueue answered`; share: `SetPlayMode NORMAL answered`, `RemoveAllTracksFromQueue answered`, `the ShareLink request sent (AddURIToQueue, service 2311)`, `the ShareLink request answered: N added (first F, queue L) by service S`, `the start item's page read (Browse): position P` | idem |
| 7 | url/NRK: `SetAVTransportURI answered`, `Play answered`; share: `SetAVTransportURI (the queue) answered`, `Seek TRACK_NR P answered`, `Play answered` | idem |
| 8 | `the settled seek done (sought: true)` (a start at 5 s or more); share: `the speaker's sleep timer set or cleared` | idem |
| 9 | `the play job done, a poll woken` | idem |
| 10 | `the play's answer on main - it settles until a poll sees our stream (the seed shows)` | `SonosController.played` |
| 11 | each settle poll: `a settle poll: <state> [(transitioning)], still the old stream \| our stream - landed \| not ours - the settle ran out` | `pollTick` |
| 12 | after the landing, each poll until the first PLAYING: `a poll: <state> (transitioning), ours: true`; then `the first PLAYING poll of our stream` | `pollTick` |
| 13 | the socket: `the event socket healthy`, `a socket event: BUFFERING`, `the first PLAYING socket event [(parked: the play still settles)]`, `the early hint: ...` (53-d's `vibb-1`) | `onSocket`, `earlyHint` |
| 14 | `a confirm poll (53-d) doesn't show the socket's change yet - another in 1000 ms` | `confirmed` |
| 15 | `the player shows playing (the now-playing update)` - `showsPlaying` turns true (the play button, `setNowPlaying`) | `SonosRenderer.flush` |
| 16 | `the connecting line shows` / `the connecting line ends` | idem |
| - | `the play failed (<class>)`, `the play dropped by its token` | `failed`, `dropped` |

A timeline closes 30 s after its tap. Lines 11-12 are the hand-over's "confirm poll rounds": a play of ours never
opens a 53-d confirm (`causedByUs` while `pending`), so line 14 shows only for a change made outside the app.

**What the spike does** (`sonos-play-test.py --spotify`, `spotify_scenario`): its socket is subscribed and a 1 s UPnP
poll runs (`upnp_watch`) **before** the transfer; then `SetPlayMode NORMAL`, `RemoveAllTracksFromQueue`,
`AddURIToQueue` (0.8 s on the user's room, §0.3), `GetMediaInfo` (printed only), `SetAVTransportURI` (the queue), `Seek
TRACK_NR` to `first + start` (no Browse), `Play` - seven calls; then it sleeps 8 s and reads. No description read, no
hush, no cap, no Browse, no sleep timer, no settle: what it hears is the speaker itself.

**What the app adds** (today's url/NRK/Storytel path, and 54-a2's share path as `playShare` stands):

1. Before the actor: the service's renderer gate and switch lock, and on an `OUTPUT` the room switch (the phone's
   hand-off, the look); on a new pick with a measurement older than 15 s, `bookmarkLiveFirst`'s live read
   (GetPositionInfo + GetTransportInfo) queued ahead of the play (53-d fix 1). Line 3 minus line 2 is that wait.
2. `speaker(uid, verify = true)`: an HTTP GET of the speaker's device description before every play (line 4).
3. A start at 5 s or more: the hush (GetMute, SetMute, and SetMute back at the end); a START with a cap: GetVolume
   (+ SetVolume) on every room of the row.
4. Share only: the start item's Browse page (100 items) before the Seek - the spike seeks to the formula.
5. **The settled seek** (a start at 5 s or more - every resume of a bookmarked item): GetTransportInfo every 0.3 s until
   PLAYING (up to 8 s), then `Seek REL_TIME`, all inside the job: the play's answer reaches main only after the speaker
   already plays. vibb's own `_seek_settled`, same order.
6. Share only: `ConfigureSleepTimer` inside the job (step 10b), before the answer.
7. Share only: `readQueue` (the rest of the queue, up to 10 Browse pages) as the next actor job - **polls are actor
   jobs too** (`SonosPoller` through `SonosActor`, the transport's mutex), so the landing poll waits behind it.
8. **The landing**: `pending` holds until a poll reads our TrackURI. The job's end wakes one poll at once; when that
   poll still reads TRANSITIONING the next comes **5 s later** (`POLL_MS`: both TRANSITIONING and the 30 s verb window
   poll at 5 s; the 1 s display poll needs the player shown, no healthy socket and a snapshot reading PLAYING - a
   TRANSITIONING one reads the last real state, STOPPED after a while). The socket's PLAYING wakes a poll
   within 1 s - but the socket opens only once the session is armed (`socketWanted`), i.e. after this very play on a
   fresh renderer, so the first play "after a while" (the renderer made anew, or the socket backed off) is likely to
   wait out the 5 s. The spike polls every second from before the start.
9. 53-d's confirm polls add nothing to our own play (above); its early hint (`vibb-1` and the new item on the socket)
   shows READY before UPnP for a direct URI - never for a share link, whose queue items aren't our DIDL.

**Recommendation, once measured** (the coordinator on the emulator with `fake-sonos.py --transition-s 4`, emulator.md
§6u; the user on the Jelly Star against a real room, a cold and a warm tap; compare lines 9, 10, 12 and 13):

- (a) **The likely cut**: after our play's answer, poll every 1 s (`POLL_PRECISE_MS`) while `pending` or the snapshot is
  TRANSITIONING, for at most `SETTLE_MS` - the spike's cadence - instead of 5 s; at most 8 extra polls per play. Take it
  when line 12's first PLAYING poll trails line 13's first PLAYING event (or the speaker's own start) by seconds.
- (b) When line 4 costs more than ~150 ms: skip the description read when the poller read that uid within the last
  minute (a poll proves the address).
- (c) For 54-a2: the sleep timer (6) after the answer, outside the start; `readQueue` (7) after the landing poll, not
  before it; the start's Browse (4) kept unless line 6's page read proves slow - then Seek to `first + index` at once and
  correct with one `queuePlay` when the page says otherwise.
- (d) When the first play's socket comes up late (line 13's `healthy` after line 10): open the socket at the room
  renderer's attach rather than at arming - design 53's `socketWanted`, so a design note first.
- (e) The settled seek (5) stays: it is vibb's, and the line covers its wait.

**The player's line** (PLAN item (b); `logic/HandOver.kt` `timeRow`, `HandOverTest`): from the tap until the room
reports PLAYING, the seek line's times give way to "Kobler til høyttaleren…" ("Connecting to the speaker…") - while the
service still has the tap's command waiting (`PlayerHub.State.waiting` with the switch held), then while
`SonosController.connecting`: the play's settle (`pending`; not once the early hint shows the new item), or after the
landing the speaker's TRANSITIONING of our stream within `SETTLE_MS` of the play's answer, while the kid wants it
playing. The room's own line (unreachable, grouped away, taken over, lost) wins and the times come back under it; a
failed start keeps today's "Kan ikke spilles på Sonos" and the phone. No new timeout, no change to the hand-over. **The
same line for every source**: PLAN asked "Kobler til Spotify…" for a Spotify playlist, but no string may name a source
(design 26c step 8, the user's decision of 2026-10-14; `SourceScanTest` fails on any string containing "Spotify"), so a
Spotify hand-over will read "Kobler til høyttaleren…" too until the user says otherwise; "Fikk ikke kontakt med
Spotify" likewise stays today's failure words. **The phone's web player** (fix round, Q-3, the user's decision): from the
tap until its first playing state - the start's calls (the page, the SDK, the token, the Connect device, the play call)
and the tracker's wait for our first state, `SpotifyRenderer.starting` - the same place shows "Kobler til…"
("Connecting…"; no source named, "høyttaleren" wrong on the phone), its own line ("Trenger internett", "Spør en voksen",
...) winning: a refused start sets one, the existing failure path. The line ends with the state that drives it, read at
the renderer's next flush or poll: a room's 8 s window or freshness running out shows at the next poll, up to 5 s
late while TRANSITIONING - bounded, no timer of its own (Q-12).

## Revision for 54-a2 (2026-11-20)

The user reopened Spotify on Sonos on 2026-11-20. Their words: "Det skal absolutt gå", and for the scope, "As designed".
This section brings the note up to date with `main` at e78742e4f. It gives 54-a2's build list. Where it differs from
§1-§8, this section wins. Paths: `M/` = `music/app/src/main/java/me/vibb/music/`, `L/` =
`launcher/app/src/main/java/com/kidslauncher/mdm/`.

### R1. What changed since 2026-10-21

1. **54-a1 is on `main`** (c1562b7e2 and its fix round 6592849b6, both 2026-10-21). Every pure rule, verb and fake
   part that "54-a1 status" lists is present: `ShareLink.kt`, `logic/SonosQueue.kt`, `SonosTransport.playShare`,
   `queuePlay`, `readQueue`, `learnQueue`, `shareBaseline`, `setPlayMode`, `playMode` and `sleepTimer`, the session's
   share classify, `SonosEvents`' new fields, and `fake-sonos.py`'s queue. **Nothing calls any of it**:
   `SonosController.kt` (2459 lines) has no share path.
2. **The park clears the queue for a share session only.** This was a1's fix round, qa-54a1-code 8. §2.9's "for every
   kind" is superseded.
3. **The hand-over timing round r13** (2026-10-25) added the `VibbHandover` lines. `playShare` already logs its steps.
   It also added "Kobler til høyttaleren…" for every room start, which a Spotify start gets with no change. Recommendation
   (c) (move the sleep timer and `readQueue` after the landing) **is not taken in 54-a2**: nothing was measured, and N6
   relies on the read being ahead of a step in the actor. Line 6 and line 12 of the first real run decide it later.
4. **Favourites (r12, 2026-10-24) handed 54-a2 a rule** (r12 §3.5): on a room, each Spotify favourite is **its own
   run**, one share link per track (`track` or `episode`, `listed = [uri]`, index 0). It is never the whole playlist.
   Today `Snapshot.holds` filters Spotify out of the room's view through `speakerPlayable`. Once that returns null,
   `segmentAt` would join consecutive refs of one playlist into one run. So the room's split must be explicit (R2).
5. **Spotify was parked on 2026-10-25** (`docs/plan/plan-archive.md`, "A faster Spotify start ... Nothing
   Spotify-related is built until the user reopens it"). It was reopened on 2026-11-20 (PLAN line 39, decision (1)).
6. **Design 120 and the web player.** Fix A was built in the launcher on 2026-11-19. On 2026-11-20 the user also chose
   B (the web player in its own process), R2 and R5. 54-a2 does not touch the web player's insides. It touches
   `PlaybackService.comeHome`, `toRoom`, the adopt look and `lost()`, and B touches the same file
   (`spotifyRenderer()`, `attach`). **Build them one after the other, not in parallel worktrees.**
7. **The tracker fixes of 2026-11-19** (38ceac3ea/18baaf37b, 9803f7c4b, 278c54730): a start at a track's end is now
   taken, a step is no longer dropped while a start waits, and a 502 before the transfer reads as "not active yet".
   None of them changes the hand-over. A room that hands back a place 1 s before a track's end now starts on the phone
   as it should. That case is a test (R6), not code.
8. **"No backwards compatibility on the phones yet"** (2026-11-18). §2.9 says "an old file without them reads as no
   baseline". That is only the default of two nullable fields. It is not a migration path, and no code is written for
   an older file or an older launcher.
9. **Android 14-17 and "Words in the UI"** (2026-11-16, 2026-11-17) now bind every note. See R4 and R5.
10. **The bridge's store can't hold a Long.** `restrictionsFor` (`L/music/MusicPlan.kt`) writes Boolean, Int and
    String only: `UserManagerService.writeBundle` drops the **whole** bundle for anything else (qa-21-step3-code H1).
    `spotify_diag_until` uses epoch **seconds** as an Int for this reason. §2.14's "`suspend_at_ms` (long, epoch ms)"
    would wipe every music restriction on the first write. **The key becomes `suspend_at_s`, `"int"`, epoch seconds**
    (good until 2038, as `spotify_diag_until`), 0 = none.
11. **The screen-time budget never suspends the music app.** `decideTimeLock`'s SCREEN_TIME lock keeps `musicApps`
    usable (design 21 §3), so the budget has no edge. Only a time rule whose exempt apps leave out the music app
    suspends it. §2.9's and §2.14's budget clauses are dropped.
12. **Code drift.** Line numbers in §0.4 have moved:
    - `speakerDropReason` is at `PlayBody.kt:152`;
    - `lost()` is at `PlaybackService.kt:3133`, `comeHome` at `:3070`, `queueFor` at `:1009`;
    - `phoneRendererFor` is at `RendererRules.kt:30`.

    Three facts §0.4 did not have:
    - `SonosRenderer.kt:549` drops the failed start's exception (`takeLost() != null`). `failed`'s "refused" path, where
      the renderer stays, emits `CANT_PLAY_ON_SONOS` at `:550`. So both need the reason.
    - **`PlayBodies.build` falls through to `item.url!!`** for a Spotify item once `speakerDropReason` returns null.
      That is a crash, so `build` must refuse Spotify itself (R2).
    - **`roomVerdict` compares the stored uri with the live TrackURI through `sameStream`.** For the queue marker
      `x-rincon-queue:<uid>#0` against `x-sonos-spotify:...` that is NOT_OURS. With no fix, the bedtime park and the
      adopt look would never stop or adopt our own queue. 54-a1 named only `adoptLive`'s half of this (qa-54a1-code 9).
      The verdict and the service's look need it too (R2).
13. **`RendererChoice` has no `shareTarget` and no `queueUpdateId` yet.** §2.9's baseline is 54-a2's to add.
14. **vibb is unchanged.** On `pipewire`, `pi/sonosd.py` was last changed 2026-09-05 (fd6633a88) and `pi/daemon.py`
    2026-09-21 (1fcadac0d), both before this note. §0.1 still transcribes the `spotify_sharelink` path exactly.
15. **`fake-sonos.py` serves no event socket**, as §3 says. Everything that rests on the socket can be checked only by
    the JVM tests from `ws/spotify_queue_2026-10-21.json` and on a real room:
    - the `itemId` wake and recheck;
    - the container's evidence for `ours` (a);
    - the metadata title in context mode.

### R2. The build list

54-a2 is built in **two rounds on one branch line**, delivered together in one release. **54-a2** is list mode,
favourites, the lifecycle and the edge contract. **54-a2'** is context mode (§4 allowed the split, and the size calls
for it). Each round has its code QA, and the controller and the lifecycle are reviewed in separate passes (qa-54 L14).
`speakerDropReason` lets a context through only in a2'. Until then a context on a room keeps today's
`CANT_PLAY_ON_SONOS`.

**Music app, round 54-a2:**

- `M/sonos/PlayBody.kt`
  - `speakerDropReason`: a Spotify entry whose target has a ShareLink type (`playlist`, `album`, `track`, `show`,
    `episode`) returns null. An artist returns `"spotify artist"`. A target with no share type returns
    `"spotify"`. A context-mode entry returns `"spotify context"` until a2'.
  - `build` returns null for `Source.SPOTIFY` before any `url` is read. This removes the `item.url!!` crash (R1.12).
  - `share` stays as it is. Its doc comment drops "nothing calls it".
  - New `shareOne(entry, key, uid, startS, hintS)` makes a favourite's one-track body: `uriOf(kind, key)` as the target
    (`track`, or `episode` for a show), `listed = [that uri]`, `trackIndex` 0.
- `M/playback/PlaybackService.kt`
  - `activeFor`: a room's Active for a Spotify entry also carries **`listed`**, the keys of `snap.visible(entry)` in
    the stored listing's own order. It never uses `order()`'s direction or the favourites scope. New field
    `Active.listed: List<String>?`, null for every other entry.
  - `lost()` becomes `lost(askAdult: Boolean)`. It emits `SONOS_ASK_ADULT` for a `ShareRefused`, and
    `CANT_PLAY_ON_SONOS` for anything else. `onRendererEvent`'s `RendererEvent.Lost` carries the flag.
  - The adopt look (`:2785-2830`):
    - `ofEntry` and `adoptedQueue` go through the new Spotify `adoptIndex` (below).
    - The probe's `keep` returns the **stored marker** for a queue marker, never the live TrackURI.
    - `sonos.adopt(...)` gets the marker as the session's uri.
    - An ours share track that `adoptIndex` doesn't find (unlisted, the Q6 case) adopts at the stored `itemKey`'s
      index, else 0, with the unmapped look. **It is never `stopUnadopted()`**: that would stop our own playlist on a
      restart.
  - `startRun` and `mixToRoom`: on a room (`!renderer.kind.onPhone`), a Spotify ref is a run of one
    (`segmentAt(queue, index, single = ...)` or a room-side split in `startRun`). The room plays it through
    `PlayBodies.shareOne`.
  - The managed config's change (`BRIDGE_CHANGED`) calls `(renderer as? SonosRenderer)?.edgeChanged()`.
  - `comeHome`: its comment says a Spotify entry does reach a room. The code is unchanged: `phoneRendererFor` already
    gives the web player.
- `M/playback/RendererRules.kt`: `phoneRendererFor`'s doc comment. Nothing else.
- `M/playback/PlayerHub.kt`: `Message.SONOS_ASK_ADULT`. `M/ui/MusicRoot.kt`: its row (`R.string.cant_play_on_sonos_ask_adult`).
- `M/bridge/BridgeConfig.kt`:
  - new key `RestrictionKeys.SUSPEND_AT_S` (`"suspend_at_s"`);
  - new field `BridgeConfig.suspendAtS: Long` (0 = none);
  - `bridgeConfig()` reads an `Int > 0`, else 0.
- `M/sonos/SonosRenderer.kt`:
  - `load` hands `Active.listed` to the controller;
  - `adopt` takes the marker;
  - `setModes` re-arms the controller's at-end action (§2.7; repeat one and repeat all);
  - `pauseAtItemEnd` for a share session: the room's ramp stays, and the controller's at-end timer (`dur - pos - 400
    ms`) is the Pause's deadline. The speaker advances gaplessly by itself, so a ramp that lands after the boundary
    would let the next track sound;
  - `edgeChanged()`;
  - lines 549-550 carry the reason (`takeLost()`'s exception is `ShareRefused`; `takeRefused()` becomes a reason).
- `M/sonos/SonosController.kt` (the bulk, §2.3-§2.11 as written):
  - The constructor takes `suspendAt: () -> Long` (epoch ms from `suspendAtS * 1000`, 0 = none).
  - The share start. `playIndex` branches **before** `PlayBodies.build`: a Spotify entry goes through
    `PlayBodies.share(entry, listed, indexInListed, ...)` (or `shareOne` in a favourites run) and
    `transport.playShare`. Its `sleepTimer` argument comes from `sleepTimerFor(suspendAt(), now)`.
  - The map. `QueueMap(listed, read)` comes from `ShareStart.read`, then `readQueue` as the next actor job. The
    controller keeps every landed transfer's map, generation or not (§2.3; `ShareStart.played = false` included).
    Trust uses `versionCheck`.
  - Steps and picks. Trusted, it is `transport.queuePlay(position, ...)`; untrusted, a transfer. The tile follows §2.4.
  - Following (§2.5's pollTick order: the belt, the queue's end, `queueMoved`, the bookmark):
    - the socket feeds `session.shareContainer` through the transport, and the `itemId` recheck runs on
      `CONFIRM_EVERY_MS`/`CONFIRM_UNTIL_MS` (53-d's names for §2.5's `IDLE_RECHECK_*`);
    - `boundary()` decides natural vs pressed, the correction (§2.6) runs only on a correctable natural boundary, and
      the mode is read before it (`transport.playMode` without a socket);
    - the at-end timer (`atEndAction`/`atEndDelayMs`) handles repeat by our advance;
    - the unmapped look uses `unmappedTitle` and `Renderer.replaceItem`;
    - adopt's `learnQueue` and `shareBaseline` run only when the adopt kept the session;
    - the sleep timer is re-set at boundaries (`sleepTimerResetDue`), after a follow, and at `edgeChanged()`.
  - **The pending play's landing (new, R6 QA-7)** lands in either of two cases:
    - a poll reads the start position's `Track` with the target's uri;
    - once the play's answer is in, a poll reads a later `Track` whose uri the map names. A start at a stored place
      near the track's end has already moved on by then. The pending lands at the start position, then
      `queueMoved` follows the advance as natural. Before this, such a poll counted as "the old track" for 8 s.
- `M/sonos/SonosTransport.kt`:
  - `adoptLive(..., share: ShareBaseline? = null)` calls `session.shareSet` + `session.shareRead` (a `QueueRead` holding
    only the stored `UpdateID`) before it classifies;
  - with the kind `SPOTIFY_SHARELINK`, its re-adopt keeps that kind and never switches to `kindOfUri(kept)`
    (qa-54a1-code 9);
  - AVTransport `Next`/`Previous` are not built: they gate on [D8+] (§2.12), see a2'.
- `M/sonos/SonosLifecycle.kt`:
  - **`roomVerdict` for a queue marker**: ours = `live.ours`, the session's share classify with the baseline the look
    set. It never uses `sameStream(marker, TrackURI)`. NOT_OURS needs the moved `UpdateID` plus a live track in
    neither list (§2.9). A Browse that doesn't answer means OURS_LIVE.
  - **`adoptIndex` for a Spotify entry**: the item whose `uriOf(kind, key)` equals `trackSpotifyUri(uri)`. The first
    occurrence is fine here: `learnQueue`'s slots correct the index from `Track`.
  - `suspensionStop` passes the baseline into `adoptLive`.
- `M/sonos/SonosSuspension.kt`:
  - the standalone bookmark takes its item from the live uri (`adoptIndex`), never `choice.itemKey` (the queue moved
    on), and writes only for a mapped track;
  - **the park retry**: a NO_ANSWER verdict with `renderer.json` still on a room schedules the unique work
    `sonos-park-retry` in a new file `M/sonos/SonosParkRetry.kt`. It is WorkManager with a `CONNECTED` constraint and
    a 5-minute backoff. The worker checks for Wi-Fi itself (`SonosHome.awaitWifi`), runs `SonosSuspension.standalone`,
    ends at a definite verdict or 2 h after the edge, and does nothing while the app isn't suspended. The service's
    own suspension path schedules the same work after a NO_ANSWER park. It applies to every kind (harmless for a
    `url` play).
- `M/sonos/RendererChoice.kt`: two nullable fields, `@SerialName("share_target") shareTarget` and
  `@SerialName("queue_update_id") queueUpdateId`. They are written with the marker at `played` and after each read
  (`SonosRenderer`'s host). `local()` drops them.
- `M/spotify/SpotifyRenderer.kt`: **no change** (checked, R6 QA-5). A detached web player saves nothing:
  - `queue` is `active.takeIf { renderer === spotify }` (`PlaybackService.kt:1550`);
  - `TrackChanged` comes only in the tracker's STARTING/OURS modes, and `detached()` sets LOST.
- Strings: `cant_play_on_sonos_ask_adult` in `values/` and `values-nb/` (R4).
- Tests: §3's controller, lifecycle and suspension list, and R6's additions.

**Music app, round 54-a2' (context mode, §2.12):**
- `speakerDropReason` lets a playlist context through. An artist context stays refused.
- `skipNext`/`skipPrevious`: `stepsOnWebPlayer(entry)` becomes the context rule for both renderers. On a room, a new
  `SonosRenderer.stepContext(forward)` calls the controller: `queuePlay(Track ± 1)` with the step coalescer, and
  previous more than 5 s in restarts at 0.
- The controller's context branch (§2.12): the live track from the socket's metadata, else `TrackMetaData`. It is
  written through `store.recordSpotifyContext` at a change only. **At `handOff` the last write is forced** with
  `runBlocking(writer)`, as `persistPlace(force = true)` does (R6 QA-6). Resume uses `findInQueue`.
- The context's shuffle on a room stays off until [D8+] (Q2 below).

**Launcher (in the same commit as the contract):**
- `L/music/MusicPlan.kt`: new key `MusicRestrictions.SUSPEND_AT_S`, and `restrictionsFor(..., suspendAtS: Int)` writes
  it as an Int.
- **`L/music/MusicSuspendEdge.kt`** (new, pure): `musicSuspendEdgeS(policy, nowMs, zone, musicPkg, liftsAt,
  overrideEndMs, extra): Int`.
  - It walks `timerules/TimeRules.nextBoundary` forward from now, with the instants `TimeRulesRuntime.nextBoundary`
    adds: lift expiries, window ends, `until_ms`, phone pauses.
  - At each boundary `b` it evaluates `decideTimeLock` with:
    - `policy.inForceAt(b)`;
    - the lifts still valid at `b` (a lift whose expiry is at or before `b` no longer lifts);
    - `overrideActive = overrideEndMs > b`;
    - no budget.
  - The first `b` where the lock is in force and `musicPkg !in usableApps` is the edge.
  - The walk stops at 64 steps or 8 days, whichever comes first. It returns 0 when there is no edge, or when the music
    app is suspended already.
- `L/timerules/TimeRulesRuntime.kt`: `musicSuspendAtS(context)` gathers those inputs, and `recheck` calls the new
  `MusicSync.restrictionsChanged`.
- `L/music/MusicSync.kt`:
  - `capChanged` is renamed `restrictionsChanged` (a write only when the bundle differs, then the nudge);
  - `writeRestrictions` computes the edge on every write.
- `L/music/VolumeCap.kt`: `refresh` calls `restrictionsChanged` on every refresh, not only when the cap changed.
  `apply()` runs at the override's and the pause's start and end, and the write is compare-first, so this is cheap.
- **The contract**: both byte-identical `bridge_contract.json` copies get `"suspend_at_s": "int"` in `restrictions`, in
  one commit with the launcher and the music app. `MusicPlanTest` checks the launcher's constant and that the value is
  an Int. `BridgeTest` checks the music app's constant and `bridgeConfig`. A new `MusicSuspendEdgeTest` covers:
  - a rule that exempts the music app (no edge);
  - one that doesn't (the edge);
  - two overlapping rules, intersecting their exempt apps;
  - a lift expiring before the rule ends;
  - an override ending inside a rule;
  - a turned-on rule's `until_ms` (design 93);
  - a DST day;
  - the budget (never an edge);
  - nothing within 8 days (0).

**Server**: nothing. The optional PWA line is Q3.

**Docs with 54-a2:**
- the files §4 lists;
- `docs/notes/music/episode-art-and-lists.md`'s r12 line ("stays out until round 54-a2");
- `docs/testing/emulator.md` §6u item 5 and §6h (R5);
- `docs/notes/launcher/music-bridge.md` (the new restriction);
- PLAN's queue entry.

### R3. The bedtime guard and the speaker's sleep timer (§2.9, as it stands now)

There are four layers, as designed. Here is what each one rests on in today's code:

1. **The park.** The launcher suspends the music app at a rule's edge (`AppEnforcer`, usable apps). The app receives
   `MY_PACKAGE_SUSPENDED`. With the service up, it runs `handOff(park = true)`; without it, it runs
   `SonosSuspension.standalone`. The park is Stop, then the queue uri, then the share session's queue clear, then our
   own sleep timer cleared. 54-a2's part is the queue marker's `roomVerdict`/`adoptLive` fix (R1.12). Without it the
   park would see NOT_OURS and leave the playlist playing.
2. **NORMAL.** Every transfer sets it, so a missed park ends at the queue's end (at most 1000 tracks).
3. **The belt.** `mayPlay()` (`playAllowed(suspendedNow())` and the room scope) is checked on every ours PLAYING poll and
   every confirmed boundary. It sends one Pause per generation and track. It guards only while the service runs.
4. **The speaker's own sleep timer.** It is `ConfigureSleepTimer` on our coordinator, set to `suspend_at_s - now`
   (`sleepTimerFor`: none past 24 h, capped at 23:59:59). It is set at four points:
   - the transfer;
   - a boundary, when the edge moved by more than 30 s (`sleepTimerResetDue`);
   - a follow;
   - `edgeChanged()`: a lift, a parent's answer to a request for more time, the override, a new policy.

   It is cleared by our Stop, the park and a hand-off, and only while it is still ours (`sleepTimerOurs`). It is never
   set at adopt. It covers a park missed with the phone off Wi-Fi, asleep, or with the process gone. It needs nothing
   from the phone at the edge.

**What the launcher writes**: `suspend_at_s` is the first instant at which the music app becomes suspended. That is a
rule whose exempt apps leave out the music app. It is rewritten at:
- every recheck (each boundary, screen-on);
- every policy sync;
- every `apply()` (the override, the pause);
- the phone's own pauses (design 98).

The budget never gives an edge (R1.11). With no music edge in the next 8 days it is 0, and the speaker gets no timer.

**The park retry** (layer 1's backstop, `sonos-park-retry`). AOSP's `JobSchedulerService` has no check for a suspended
package, either on `android14-release` or on `main`, read 2026-11-20 for Android 14 to 16. So WorkManager most likely
runs a suspended app's work. That is now an **emulator check on 14 and 17** (R5 step 7) as well as [D16+] on the Jelly
Star's 16. If it fails, layers 2-4 stand. The timer is the independent bound, and §2.9's "fallback, truthfully" holds.

**What stays true for the household**: while our session plays before a rule's edge, the room's sleep timer in the Sonos
app counts down to that edge. A timer the household set before our play is replaced; one set after it is left alone
(Q1).

### R4. What the kid sees and reads

Spotify is never named to the kid. `SourceScanTest` fails on any string containing "Spotify". "Sonos" is the
speaker's name the output sheet already shows, so it is allowed. No new state line is added. Only one message is new.

| When | nb | en | String |
|---|---|---|---|
| The start was refused by kind (an artist, a context before a2', no share type) | Kan ikke spilles på Sonos | Can't play on Sonos | `cant_play_on_sonos` (exists) |
| The speaker refused the link (both service types, or 0 tracks: the account linked in the Sonos app can't play it) | Kan ikke spilles på Sonos – spør en voksen | Can't play on Sonos – ask a grown-up | `cant_play_on_sonos_ask_adult` (new) |
| No answer during the add | Kan ikke spilles på Sonos | Can't play on Sonos | (exists) |
| From the tap until the room plays | Kobler til høyttaleren… | Connecting to the speaker… | `connecting_speaker` (exists) |
| A track of the queue our list doesn't have (Q6) | the speaker's "Sang — Artist", with no number and no heart | the same | none |
| Context mode | the live track's "Sang — Artist" over the entry's cover | the same | none |

Each message is one short line, in the place the existing messages show (`MusicRoot`'s bottom box), and it shows only
when something went wrong. A start that plays shows nothing new. The account playing elsewhere [D18] uses whichever
existing room line the classify gives: no new string, and none promised before D18.

### R5. The test plan

**JVM** (no emulator): §3's list as written, and R6's additions. `MusicSuspendEdgeTest` is in the launcher;
`BridgeTest` and `MusicPlanTest` run on both sides.

**The emulator** needs the user's OK before it runs (the standing rule). It runs on both the lowest and the highest
version it touches: the version-dependent part (step 7) on **Android 14 and 17**, the rest on one image (jelly36 for
anything the user sees).

Set up the speakers as §6h does, with the queue's tracks short:

```
python3 -I scripts/fake-sonos.py --selftest
python3 -I scripts/fake-spotify.py --selftest
python3 -I scripts/fake-spotify.py --print-listing 6 > debug-feed/items/6.json   # the fixture playlist, as §6l A2
python3 -I scripts/fake-sonos.py --track-seconds 20 | tee /tmp/fake-sonos.log    # Stua, Kjøkken; queues from server/testdata/spotify/
python3 -I scripts/fake-spotify.py --track-seconds 20 | tee /tmp/fake-spotify.log  # only for the hand-over to the phone's web player
```

1. **The transfer.** Use the Spotify tile with Stua chosen. The fake logs `SetPlayMode NORMAL`,
   `RemoveAllTracksFromQueue`, `AddURIToQueue` (2311), `Browse Q:0`, the queue uri, `Seek TRACK_NR`, `Play`, and
   `ConfigureSleepTimer` only on a managed phone with a rule ahead. `VibbHandover` shows the share lines. The player
   shows our track, its number and its picture.
2. **Steps, picks and following.** Five Nexts in 2 s give one `queuePlay`, no add, and a title that doesn't flap. A
   pick in the list is a `queuePlay`. `--script "t+30 next"` (the speaker's own button) is followed at the next poll,
   with no done mark. The queue's own advance at 20 s is followed and marks the finished track done.
3. **The map:**
   - `--queue-extra`: the unmapped look plays on, with no bookmark;
   - `--queue-gap`: a step skips the missing item, and a pick of it says "Kan ikke spilles på Sonos";
   - `--queue-reversed`: the correction at each natural boundary;
   - `--script "t+40 queue-insert"`: untrusted, and the next press is a transfer.
4. **Repeat and the end.** Repeat off: the queue's end gives `ended`. Repeat all wraps through a `queuePlay` of item 1.
   Repeat one seeks to 0 before the end. "Til slutten" pauses before the boundary. The fake logs no next track playing.
5. **Refusals.** `--refuse-share` gives "Kan ikke spilles på Sonos – spør en voksen" and the phone, paused.
   `--empty-share` gives the same. An artist entry gives "Kan ikke spilles på Sonos" and nothing changes.
6. **Hand-over and restart:**
   - the web player to Stua at the same track and second; Stua back to the web player, playing only if Stua played;
   - Stua to Kjøkken;
   - a start from a place 1 s before a track's end (lands, then follows; R6 QA-7);
   - `am force-stop` while Stua plays, then open the app: adopted, no transport command in the fake's log, the map
     read, and the next press a `queuePlay`;
   - favourites with two Spotify hearts and one podcast: one share link per heart, the podcast run in between, and
     `Ended` at each one-track share.
7. **Bedtime** (managed: the debug launcher as Device Owner, a time rule 3 minutes ahead without the music app among its
   exempt apps):
   - the fake logs `ConfigureSleepTimer` at about 0:03:00 at the transfer (the music app reads `suspend_at_s` through
     `BridgeConfig`; `BridgeTest` and `MusicPlanTest` pin the value itself);
   - at the edge: Stop, the queue uri, `RemoveAllTracksFromQueue`, `ConfigureSleepTimer ""`;
   - again, with `adb shell svc wifi disable` 30 s before the edge: the park is NO_ANSWER and the fake's sleep timer
     pauses Stua at the edge;
   - `svc wifi enable`: `sonos-park-retry` parks within 5 minutes, which answers whether **Android 14 and 17 run a
     suspended package's WorkManager work**;
   - a lift from the PWA before the edge: the edge moves, and the fake's timer is re-set or cleared;
   - a rule that exempts the music app: no timer.
8. **Context mode (a2')**, with a context entry from the debug feed (`--print-listing 6 --tracks 0`): the transfer has
   no listing, the title comes from `TrackMetaData`, steps go ±1, a resume finds the live track, and its end never
   marks done.

The fake has no socket, so the `itemId` recheck, the container check and the metadata title are left to the JVM tests
and the real room.

### R6. What only a real Sonos with the household's Spotify linked can confirm

This is §5's list, unchanged in substance. Priorities for the first real run:
- **D12** (a playlist the linked account can't open: which of the two refusals);
- **D7** (the queue's end under NORMAL);
- **D9** (the speaker's buttons);
- **D21** (the sleep timer: pause or stop, the group, what the Sonos app shows, whether a `TRACK_NR` jump or the park
  resets it);
- **D16** (the bedtime park with the queue cleared, and the speaker's own Play button playing nothing);
- **D11** (a show's and an episode's queue, and its order against `visible()`'s, which `listed` uses);
- **D3** (a full Browse against our listing).

D16+ is now partly answered by the emulator (R5 step 7). D8+ gates a context's shuffle. D2+ (300 and 1000 tracks)
and D18 (the account playing elsewhere) need the house's own account and playlists. The 54-0 remainder on the Pi
(`sonos-play-test.py --more`, `--add-only`, `--golden` with vibb's SoCo) can run before or alongside the build, and
should run before 54-a2's code QA so the goldens are SoCo's own.

### R7. Design QA of this revision (as a separate reviewer)

I re-read R1-R6 against the code. Findings, all fixed above:

- **QA-1 (High)**: `suspend_at_ms` as a Long would make the launcher's write throw (`restrictionsBundle` refuses it).
  If forced through, it would drop the whole music bundle. -> `suspend_at_s`, Int (R1.10).
- **QA-2 (High)**: the queue marker against `sameStream` is NOT_OURS. The bedtime park, both paths, would never stop
  our playlist, and adopt would stop it instead of adopting it. -> `roomVerdict` and the look read `live.ours` with
  the baseline, and `keep` returns the marker (R2).
- **QA-3 (High)**: once Spotify passes the filter, `PlayBodies.build` reaches `item.url!!` and throws. -> `build`
  refuses Spotify, and `playIndex` branches to the share body first (R2).
- **QA-4 (Medium)**: an unlisted but ours track at a restart has no `adoptIndex`. The service's look would call
  `stopUnadopted()` and stop our own queue. -> adopt at the stored item with the unmapped look (R2).
- **QA-5 (Medium, a race with the tracker, checked and found safe)**: phone to room while the web player's start is in
  flight. `attach` -> `detached()` supersedes the start. A `PUT play` already sent makes the phone's "Vibb" device
  play briefly, and its first state is paused by the detached rule. The detached player can't save onto the room's
  Active (`queue` is gated on `renderer === spotify`) and emits no `TrackChanged` (LOST). No code change. R5 step 6
  runs it with `fake-spotify.py --slow-ready 3`.
- **QA-6 (Medium, a race with the tracker)**: room to phone in context mode. Through the web player's port the room's
  last live-track write would be a `scope.launch(writer)`, while the web player's `load` reads `spotify_context.json`
  at once. It could start from the
  previous track. -> the hand-off's write is forced (`runBlocking(writer)`, R2 a2').
- **QA-7 (Medium)**: a start at a stored place near the track's end. Since 2026-11-19 the web player hands such places
  over, and the room gets one. The speaker advances inside the 8 s settle, and §2.5's "a different track is the old one"
  would show the wrong title for up to 8 s and lose the natural boundary. -> the landing rule in R2.
- **QA-8 (Medium)**: the edge needs the lifts, the override and the phone pauses **as they will be at that instant**,
  not as they are now. `ruleLifts` is built for now. -> the walk takes `liftsAt(b)` and `overrideEndMs` (R2), and its
  test rows cover a lift expiring before the rule.
- **QA-9 (Medium)**: `VolumeCap.refresh` re-pushes only when the cap changed, so an override would leave a stale edge.
  -> `restrictionsChanged` on every refresh and recheck, compare-first (R2).
- **QA-10 (Low)**: `listed` taken from `Active.order` would carry the kid's flip and the favourites scope, and the k-th
  duplicate matching would pair the wrong slots. -> `Active.listed` from `visible()` (R2). Whether a show's listing
  order matches its queue is [D11].
- **QA-11 (Low)**: on a room, `segmentAt` would merge consecutive hearts of one playlist into one run. That
  contradicts r12 §3.5. -> a run of one per Spotify ref on a room (R2).
- **QA-12 (Low)**: the "Til slutten" ramp can end after the speaker's gapless boundary. -> the at-end timer is the
  deadline for its Pause, and the belt pauses at a natural boundary while `holdAtEnd` is set (R2, §2.7).
- **QA-13 (Low, Android 14-17)**:
  - no new platform surface: SOAP over the Wi-Fi network as every room play today, `MY_PACKAGE_SUSPENDED` (14-17),
    managed config;
  - WorkManager is JobScheduler on all four. Android 16's quota change for jobs started while a foreground service
    runs doesn't touch a one-shot retry;
  - the suspended-package question is the R5 step 7 check on 14 and 17;
  - `isPackageSuspended` and `RestrictionsManager` behave the same on 14 to 17;
  - the build list adds no API above 34, so lint's NewApi has nothing to gate.
- **QA-14 (Nit)**: §0.4's line numbers and §2.9's "every kind" park are stale. R1 records the current ones, and the
  body is left as history.

**Build size**: **L, two rounds** (as estimated on 2026-11-20).
- **54-a2** is about 1.5 rounds: the controller is most of it (an estimated 900-1200 new lines); the lifecycle, the
  service, the launcher's edge and the contract about 400; the tests about 1200.
- **54-a2'** (context mode) is about 0.5.
- Each has its code QA in two passes, then a fix round. The emulator gate (R5) runs once, after a2'.
- 54-b (the reorder, gated on [D10]) stays later.

### R8. Questions for the user (visible choices; not decided here)

- **Q1. The speaker's sleep timer shows in the Sonos app.** While the kid's playlist plays on a room before bedtime, the
  room shows a sleep-timer countdown to the rule's edge in the Sonos app. A timer the family set before the kid's play
  is replaced. This is the bound that stops the room when the phone misses bedtime (no Wi-Fi, asleep). Keep it as
  designed? **Recommendation: yes.**
- **Q2. A context playlist's shuffle on a room** (54-a2', until the device check [D8+] passes): hide the shuffle button
  on a room (**recommended**), or show it doing nothing.
- **Q3. One line for the parent on the PWA's Spotify page**, behind its (i) only: "On Sonos, Spotify plays through the
  account linked in the Sonos app" (nb: "På Sonos spilles Spotify med kontoen som er koblet til i Sonos-appen"). Add it
  (server-only commit), or leave it out?
- **Q4. The new message**: "Kan ikke spilles på Sonos – spør en voksen" / "Can't play on Sonos – ask a grown-up", when
  the account linked in the Sonos app can't play the playlist. Keep it, or the shorter "Spør en voksen" alone?

## 54-a2 and 54-a2' status (2026-11-20, branch `sonos-spotify-54a2`)

Built as R2 lists it, with R8's recommended answers (each one constant or one line to change): Q1 the speaker's sleep
timer to the rule's edge replaces a family timer; Q2 a context's shuffle is hidden on a room (`CONTEXT_SHUFFLE_ON_ROOM`,
`shuffleShown`); Q3 the PWA's (i) line on the Spotify page's accounts group (server-only commit); Q4 "Kan ikke spilles
på Sonos – spør en voksen" / "Can't play on Sonos – ask a grown-up" (`cant_play_on_sonos_ask_adult`).

Where the build differs from the letter of R2:
- The map lives in `SonosController` as `QMap` (one per landed transfer; `trusted`, `reading`, the listing at the
  transfer). A jump queued while the read runs passes the listing and index to `SonosTransport.queuePlay`, which looks
  the slot up in the session's last read under the mutex (N6); no slot is `NotInQueue`, nothing sent.
- The at-end timer is armed from polls only (each poll re-arms it); a socket measurement doesn't re-arm it.
- The unmapped look swaps the session's `MediaItem` title (`unmappedShown`, the notification, the launcher's surfaces);
  the in-app player screen still shows our item's title and number - owed.
- `wsBlocked` still clears on any ours poll (53-d's rule); the pairing by `itemId` (`pairedItem`) keeps a new item's
  measurement off the old track's bar.
- The service's suspension keeps `renderer.json` on the room after a park with no answer (`SonosController.parkNoAnswer`)
  and schedules `sonos-park-retry`; the receiver's standalone path does the same.
- `musicSuspendAtS` takes the policy and the package (the restrictions writer works per music package).

**Emulator, 2026-11-20** (the debug music app unmanaged, the debug feed with `fake-spotify.py --print-library` /
`--print-listing 6` - its `sonos_rooms` removed and entry 6's target set to the fake speaker's fixture playlist
`0FakePlaylistKveld0001` -, `fake-sonos.py --track-seconds 20`/`60`):
- jelly36 (Android 16): R5 step 1 (the transfer: NORMAL, the clear, `AddURIToQueue` 2311, one Browse page, the queue
  uri, `Seek TRACK_NR`, `Play`; the player shows our track, its number, the bar); the queue's own advance onto the
  fixture's unlisted episode at position 2 followed as natural and corrected to our next at position 3 (§2.6), the
  same past the duplicate at position 4; two quick Nexts were two jumps, no add; step 5 (`--refuse-share`: "Can't play
  on Sonos – ask a grown-up" and the phone, paused); step 6's force-stop and reopen (adopted, no transport command,
  the map read, an unmapped adopt on the episode followed as pressed, the next press a jump); the queue's end (STOPPED,
  "queue finished - stop"); the bedtime park with Wi-Fi (`pm suspend`: Stop, the queue uri, the clear); and step 7's
  retry without the launcher: Wi-Fi off, `pm suspend` - the service's park got no answer and scheduled
  `sonos-park-retry`, whose first run came at once over the mobile network while the package was suspended (NO_ANSWER,
  no Wi-Fi), and with Wi-Fi back its next run 5 min 44 s later parked the room (OURS_STOPPED: Stop, the queue uri, the
  clear). **Android runs a suspended package's WorkManager work on 16.**
- compat34 (Android 14): the same retry check - the service's park with Wi-Fi off scheduled the retry, its first run
  came at once while suspended (NO_ANSWER), and with Wi-Fi back the next run 5 min 32 s later found the queue still
  playing and parked it (OURS_LIVE with the baseline's `BrowseMetadata`: the bookmark, Stop, the queue uri, the clear).
  **Android 14 runs it too.**
- compat372 (Android 17): its system_server restarted every few seconds on this VM (the image, not the app: it
  happened before the app's first activity); the Android 17 check is owed.
- Not run: steps 2's `--script` and 3's `--queue-*` options, 4 (repeat, "Til slutten"), 6's hand-overs (the web player
  on the emulator said "Needs the internet" with the fake), 7's managed parts (`ConfigureSleepTimer` from a real
  `suspend_at_s`: needs the debug launcher as Device Owner with a time rule), 8 (context mode).

**The code QA's fix round** (2026-11-20, both passes; all taken):
- H1: "Til slutten" at our last item (the at-end timer or a natural boundary) ends the queue paused without
  `held()`, so the service's `queueEnded` holds - a favourites run no longer starts the next heart.
- M1 (music): `load` keeps a map only when it was built for the same target - a heart's one-track map never serves
  the playlist. M2: the park takes our STOPPED share queue too (its clear). M3: our own sleep timer is cleared when the
  session ends without a Stop of ours (our list's end, stood down, a takeover), only while it is still ours.
- M1 (launcher and music): while the music app is suspended now, `musicSuspendEdgeS` is null and the written
  `suspend_at_s` stays (no rewrite, no nudge at the edge); the music app never clears its own timer that ends within
  30 s for a 0 edge. L1: a policy without a music object still has its `suspend_at_s` refreshed. L2: an edge within
  2 s of the written one keeps it (`musicEdgeToWrite`).
- L1-L6 (music): an adopt's item paired at its first confirming poll; `itemChangedAt` cleared at the landing and when
  the socket goes; the done mark only when the correction ends on another item; a context's jump lands on its
  position; no share type is the plain "Kan ikke spilles på Sonos"; `load` re-indexes a repeated track through the map.
- L3 (server): the PWA test pins the line right after the accounts group's login line.

**Still owed**: Android 17 (its emulator image restarted on this VM); the rest of R5 (steps 2-4, 6's hand-overs, 7's
managed sleep timer, 8); the real room (R6: D12, D7, D9, D21, D16, D11, D3 first, then D8+, D13/D14, D18 and the
socket-only paths); the in-app player's unmapped title and number (the session's metadata has it).
