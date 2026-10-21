# Code QA - design 54, round 54-a1 (Spotify on Sonos: the pure rules, the SOAP and the fake - nothing changes for the kid)

- Commit on branch `d54-a1` over `main`'s `22bff6a7`: `c1562b7e` (`sonos/ShareLink.kt`, `logic/SonosQueue.kt`,
  `Kind.SPOTIFY_SHARELINK`, `Soap`'s ContentDirectory and the queue verbs, `SonosTransport.playShare`/`findInQueue`/
  `readQueue`/`learnQueue`/`shareBaseline`/`queuePlay`/`setPlayMode`/`playMode`/`sleepTimer`, the park's clear, our Stop's
  and the park's sleep-timer clear, `SonosSession`'s classify/stall/probe for the kind, `SonosEvents`' new fields, the
  fixtures and goldens, `fake-sonos.py`'s queue, `sonos-play-test.py --more`/`--add-only`/the share goldens,
  `sonos-ws-probe.py`'s Spotify-id scrub, NOTICE + `MIT-SoCo.txt`, the note's "54-a1 status", PLAN).
- Read: root and `music/CLAUDE.md`; `docs/design/54-spotify-on-sonos.md` whole (§0-§8, both revision sections, "54-a1
  status"); `qa-54-design.md`, `qa-54-revision-check.md`; design 53's 53-d status (the renamed `CONFIRM_*` constants);
  vibb `pi/sonosd.py` (`Session.play`'s `spotify_sharelink` branch, `verb("queue_play")`, `_classify`,
  `_stall_bookkeeping`, `_probe_migration`); SoCo master's `soco/plugins/sharelink.py` and `soco/core.py`
  (`play_from_queue`, `play`, `clear_queue`, `queue_size`, `get_queue`, `set_sleep_timer`, `get_sleep_timer`) and its
  `LICENSE.rst`; the full diff and the code around it (`SonosTransport.play`/`adoptLive`/`verb`/`park`/`stop`,
  `SonosSession` whole, `SonosLifecycle.roomVerdict`/`suspensionStop`, `PlaybackService`'s adopt probe, `Soap.call`).
- Ran: `testDebugUnitTest -PwarningsAsErrors=true` (full music suite): **BUILD SUCCESSFUL, 1161 tests, 0 failures, 0
  skipped**. `python3 -I scripts/fake-sonos.py --selftest`: **OK** (step 6, the queue). SoCo master's `sharelink.py`
  run with stub base classes over the golden inputs: **all five `sharelink_*.xml` byte-identical**, and its retry is
  2311 then 3079 on a `SoCoException`. The fixtures diffed string by string against `~/handy-captures/`. Then 25
  one-line mutations against the ten touched test classes, each reverted; the tree is clean but for this file.

## Findings

**1 (Medium) - for a share link, `ours` replaces vibb's rule instead of adding to it: a transfer that ends before it
lands leaves the previous stream "not ours".** `SonosSession.kt:567`: `val ours = if (shareKind) shareIsOurs(io,
spotify) else sameStream(uri, trackUri)`. vibb's `_classify` is `_norm_uri(track_uri) == _norm_uri(self.uri) or
(self.kind == "spotify_sharelink" and track_uri.startswith(...))` - the uri equality always counts. That matters because
`playShare` calls `session.beginPlay(uid, SPOTIFY_SHARELINK)` first (`SonosTransport.kt:235`, as `play` and vibb do:
uid and kind before any SOAP) and sets the uri only at `played` (`:267`). Every exit in between - the first gate
(`:240`), the second gate (`:265`), `ShareRefused` (both faults, 0 added), a `SpeakerUnreachable` or `UpnpFault` from
the clear or the add - leaves the session with kind `SPOTIFY_SHARELINK` and the **previous** uri. If a podcast of ours was
playing on that room (the kid taps a playlist tile while an episode plays there: `RemoveAllTracksFromQueue` doesn't touch
a transport playing a direct uri), every later classify of that podcast reads `trackSpotifyUri == null` -> not ours. In
54-a2 a refused share link ("Kan ikke spilles på Sonos - spør en voksen", back to the phone) then can't stop it (every
Stop gate reads `ours`), and a time-rule edge that lands between the two gates leaves the podcast playing past bedtime -
the park's gate skips it. `play()` has the same "kind before the SOAP" shape but never had the problem (both of its kinds
use `sameStream`). Fix: vibb's OR - `val ours = sameStream(uri, trackUri) || (shareKind && shareIsOurs(io, spotify))`
(the queue marker never equals a TrackURI, so a landed share session is unchanged), and set `lastOursTrack`/`lastOursRel`
only when `spotify != null`. Test (`SonosSessionTest`/`SonosTransportTest` `d54`): a url session playing, `playShare`
ending at the second gate, then with `ShareRefused`, then with a network failure in the add - the next classify of the
podcast's TrackURI is ours (and `park()` sends its Stop).

**2 (Low) - a transfer stopped by its second gate replaced the queue but tells nobody.** `SonosTransport.kt:265`
returns null after `SetPlayMode`, the clear, the add and the start's Browse - the status lists it under "54-a2 inherits",
and §2.3 wants the controller to take "the map of every transfer that landed". Cheap to make right here, where the data
is: call `session.shareSet(link.canonical, listed)` and `session.shareRead(read)` right after the add/find (the queue
**is** the target's from then on, whatever the gate says - the classify then knows it if someone presses Play on the
speaker), and return the start instead of null (e.g. `PlayResult(..., share = start)` with a `gated = true` flag, or a
small sealed result), so a2's controller can keep the map and never re-add on the next press. Test: the second-gate case
of `d54 a closed gate sends nothing more` also asserts the session's `shareTarget` and the returned start.

**3 (Low) - a queue edited twice while it is read is accepted as one consistent read.** `readRest`
(`SonosTransport.kt:331-352`) starts over once when a page's `UpdateID` differs; after that (`again = false`) a further
change is ignored, and the read keeps the newest page's `UpdateID` as its baseline - so `versionCheck` says SAME for a map
whose slots mix two queues. The confirm's verification (§2.5) heals it at the next disagreeing poll, but a step or pick in
between jumps to the wrong position. Fix: a change after the restart returns the read with `updateId = null` (no
baseline: `versionCheck` REREAD, untrusted until the controller re-reads) and logs it. Test: `spk.updateId++` between
both re-read pages -> the result's `updateId` is null.

**4 (Low) - a page shorter than asked leaves holes nothing reads.** `findInQueue` and `readRest` step by `QUEUE_PAGE`
(`SonosTransport.kt:337`, `:349`) whatever came back. UPnP lets a ContentDirectory answer fewer items than
`RequestedCount` (`NumberReturned`). A speaker that caps a Browse lower
(the D3 Browse hasn't run yet) leaves positions 51-100, 151-200, ... unread for good: `complete` is never true, those
items get no slot (they "can't play on this room"). Fix: advance by `page.res.size` (stop on an empty page, as
`sonos-play-test.py`'s `browse_queue` already does), and in `findInQueue` read the uncovered rest of the home page before
moving outward. Test: a `FakeSpeaker` whose `browseQueue` returns at most 50 -> the read of 150 is complete.

**5 (Low) - the start's position and the map can disagree on a duplicate.** `findInQueue` takes the occurrence nearest
the guess (`positionNearest`, `SonosQueue.kt:149`; called at `SonosTransport.kt:259`), the map the k-th occurrence for our
k-th (`QueueMap`, §2.3). Our listing `[A, A]` over a queue `[A, x, x, x, A]` (extras before the second A, the very case
§2.3 is built for): a start at our second A guesses 2, plays position 1 at the second A's saved place; the read's slots
then say position 1 is item 0, so a2's index moves back to item 0 and the kid hears the stretch between again. Fix: when
the pages read hold every earlier occurrence of the uri (always true for a queue of one page), use `QueueMap(listed,
read).slotOf(trackIndex)`; nearest only as the fallback for a partial read. Test: that queue, index 1 -> position 5.

**6 (Low) - a failing `BrowseMetadata` is asked again at every poll.** `shareIsOurs` (`SonosSession.kt:641-660`) caches
the verdict per unknown track only when the Browse answered; a fault or timeout (`:656`, "no answer decides nothing")
leaves `sh.unknown` unset, so while such a track plays every poll - the 1 s display poll included, and every bedtime look
- spends one more SOAP (and up to its timeout) on it. Fix: cache the null too (`Pair<String, Boolean?>`; one try per
track, or again after 60 s). Test: `silent` classified twice -> one `getqueuemeta`.

**7 (Low) - `lastOursRel` is overwritten with null.** `SonosSession.kt:604-607` sets `lastOursRel = rel` on every ours +
live sighting; vibb keeps the last value when RelTime doesn't parse (`if rel is not None`) and keeps the last track when
the uri has none (`track_spotify_uri or last`). One poll with `NOT_IMPLEMENTED` (a transition) right before a group move
and the probe never follows (`shareProbeMatches` is false on a null `lastRelS`). Fix: `rel?.let { lastOursRel = it }`,
`lastOursTrack = spotify ?: lastOursTrack`. Test in `d54 sonos_migrate_probe 3`: a live sighting with no RelTime between,
and a live sighting that isn't ours (another container) leaves both unchanged - nothing pins the `ours` condition now
(mutation M20 survived).

**8 (Low) - the park's clear: before the queue uri, and for NRK it deletes the household's queue.** `park`
(`SonosTransport.kt:616-631`) now sends Stop, `RemoveAllTracksFromQueue`, then `SetAVTransportURI(x-rincon-queue)`, all
within the bedtime budget (`suspensionStop` passes what is left, at least 500 ms). (a) The new SOAP comes before the one
26a M5 needs: a slow clear can leave the queue uri refused with "time budget spent" - "stopped only", and the speaker's
own Play button plays our episode from 0:00 again. (b) The note's reason "an empty queue costs nothing" holds for `url`
(vibb's url play clears the queue at every start, `:166-169`) and for the share kind, but an `nrk_program` play never
clears it: a queue the household built in the Sonos app is now deleted at the kid's bedtime park whenever an NRK
programme was ours (the user's "RSS/NRK podcasts ... untouched"). Fix: Stop, the queue uri, then the clear; and clear
only when the session's kind is `SPOTIFY_SHARELINK` (the only kind whose queue the Play button would play) - or keep
"every kind" and say the NRK consequence in §2.9. Update `SonosLifecycleTest`'s bedtime list and the park test.

**9 (Low) - for 54-a2: adopt's re-adopt turns a share session into a one-track `url` session.** Checked that
`kindOfUri`'s new `x-rincon-queue:` branch changes nothing today: `renderer.json` holds the built uri (never the queue
marker), `roomVerdict` matches with `sameStream`, and `adoptLive`'s kind is only used for the classify. But
`adoptLive` re-adopts with `session.adopt(uid, kindOfUri(kept), kept)` (`SonosTransport.kt:578`) whenever `keep`
returns another uri, and the service's `keep` returns the **live TrackURI** (`PlaybackService.kt:2030`). For a share
session that is `x-sonos-spotify:...` -> `Kind.URL` with one track's uri: its next track is "not ours". Add it to the
note's "54-a2 inherits": for a queue marker `keep` returns the stored marker (or `adoptLive` keeps `SPOTIFY_SHARELINK`
when that was the kind it adopted with).

**10 (Low) - the probe's Spotify-id scrub misses forms the 54-0 remainder will capture.** `sonos-ws-probe.py:107`
matches `spotify:` and `spotify%3a` only. Not covered: the double-encoded `spotify%253atrack%253a<id>` (Sonos' art paths,
`/getaa?...u=x-sonos-spotify%3aspotify%253atrack...` - `browse_queue_page.xml` shows the form), an
`open.spotify.com/<kind>/<id>` link, and `spotify:user:<name>:playlist:<id>` (the username stays, the id too). This
capture is clean - checked string by string against `~/handy-captures/ws-spotify-2026-10-21.json`: its five real
Spotify ids are replaced, the other strings were already scrubbed at save (uids, IPs, names, serials, devices, images),
and no raw id appears in the diff or the tree (`git grep` of the five) - but `--more`'s run will save more. Fix:
`(?i)(spotify(?::|%3a|%253a|/))(track|episode|playlist|album|show|artist)(:|%3a|%253a|/)(<id>)`, and
`spotify:user:[^:"&]+` -> `spotify:user:fakeuser`; one `--rescrub` selftest line per form.

**11 (Nit) - `MediaInfo` prints `CurrentURI`.** `Soap.kt:69` is a plain data class; for a Storytel play `CurrentURI` is
the signed URL (26b §0). Nothing logs one yet, but every other uri holder redacts in its `toString`. Fix:
`override fun toString() = "MediaInfo(nrTracks=$nrTracks, currentUri=${Fields.shown(currentUri)})"`.

**12 (Nit) - small ones.** `SonosTransport.play` calls `beginPlay(body.uid, body.kind)` (`:157`) before it throws for a
share body (`:188`) - check the kind first, before the session or the speaker is touched. `music/CLAUDE.md`'s "The
phone's lifecycle on a room" (line 495) still describes the park as "Stop, `x-rincon-queue:<uid>#0`": the clear is
today's behaviour since this round (the rest of the docs rightly wait for 54-a2). The classify logs "not-ours?" for the
share kind where vibb doesn't (`_classify`'s `self.kind != "spotify_sharelink"`) - harmless (prefix only), keep or drop.

## The checks, with the evidence

- **The ShareLink request is SoCo's, byte for byte.** `ShareType` = SoCo's `magic()` for the five Spotify types (`song`
  is Apple Music's, rightly absent); the pattern is `SpotifyShare.canonical_uri`'s (no `artist`; Kotlin's `find` = Python's
  `re.search`, `\w` the same over base62 ids); `extract`'s `:` -> `%3a`; the template field for field with `dc:title`
  empty. On the wire: `InstanceID 0, EnqueuedURI, EnqueuedURIMetaData, DesiredFirstTrackNumberEnqueued 0, EnqueueAsNext
  0` in SoCo's order (`ShareLinkTest`'s wire test parses the request). The goldens are `share_request`'s, and SoCo master's
  own plugin writes the identical five files (run here); the Pi run with vibb's SoCo stays owed (README). Retry: 2311,
  then 3079 only after a `UpnpFault` (SoCo: a `SoCoException`, which a UPnP fault is); a `SpeakerUnreachable` - a
  timeout, a refused connection, an HTTP error without a SOAP fault, which SoCo raises as `requests` errors - ends the add
  at once.
- **`play_from_queue`, `clear_queue`, Browse.** `SetAVTransportURI(x-rincon-queue:<uid>#0, "")`, `Seek TRACK_NR`
  1-based, `Play` - SoCo's. `RemoveAllTracksFromQueue`. `Browse` on ContentDirectory with SoCo's six arguments in its
  order, no `InstanceID` (`SonosFixturesTest` pins the wire, and the fake answers 402 without `SortCriteria`).
  `ConfigureSleepTimer` `H:MM:SS` as `format(timedelta)`, `""` to cancel; `GetRemainingSleepTimerDuration`.
- **`playShare`'s order** is §2.2's: hush, the row's cap, gate, NORMAL (errors ignored, vibb's `try`), clear, add, the
  start's page, gate, `play_from_queue`, `played` + `shareSet` + `shareRead`, the settled seek only at a found position,
  the sleep timer (set, or our own cleared), unhush; the rest of the queue is `readQueue`'s own job. A closed first gate
  sends nothing that changes the room; a closed second gate sends no Seek/Play (finding 2 for what it leaves). Not found:
  position `first`, no seek (N4); `first` unparsable -> 1; `NumTracksAdded` 0 or `NewQueueLength` 0 -> `ShareRefused`
  before any Seek.
- **The pure rules against the note.** `trackSpotifyUri` = vibb's expression with Python's `unquote`. `shareOurs`: the
  prefix unless the container contradicts or (neither list, a moved version) - a moved version alone, no answer, no
  baseline keep ours (N2). `containerContradicts`: only a `spotify:playlist:` container other than our target is
  evidence; absent, empty, `vibb-1`, an album's or a show's form is none (only a playlist's was measured). `boundary`:
  NATURAL only on the anchor, NATURAL_GUESS (done mark, never the correction) on `endsNear` alone (N8); a paused anchor
  doesn't move. `anchorNearEnd` (N1b). `atEndAction`: "Til slutten", then repeat one, then our last only when a slot
  follows; none at the queue's last position (54-8). `sleepTimerFor`: none for 0, past, or more than a day; rounded up,
  capped at 23:59:59; `sleepTimerOurs` within 30 s; the clear asks `GetRemainingSleepTimerDuration` only when one of ours
  is set, and forgets ours either way. `versionCheck` keeps the source (N5). `QueueMap`'s slots are exact for a gap, an
  extra, a duplicate, a reversed capped show and a relinked id.
- **The session.** No lost-session, no DIDL check and no stall retry for the kind (vibb 895-899); `trackNo`,
  `trackSpotifyUri`; one `BrowseMetadata` per unknown track, cached when it answers (finding 6); the probe follows only
  the exact decoded track within `-5..+45` s and never without a recorded track (`sonos_migrate_probe.py` 3); `adopt`
  and a play of another kind forget the share knowledge. `queuePlay` re-arms (`jumped`) where vibb's verb doesn't - needed
  here because our disarm (the 10-minute pause, our Stop) would otherwise leave a jump unwatched; stated in the status.
- **`kindOfUri`** maps `x-rincon-queue:` to the share kind; dormant today (finding 9 for what a2 must add). The park is
  still gated on `ours` (the controller's `parkGate`, `roomVerdict`): a guest's Spotify queue at bedtime is neither
  stopped nor cleared - but see finding 8 for an NRK session's household queue.
- **Fixtures.** `ws/spotify_queue_2026-10-21.json`: 194 distinct strings, 189 shared with the saved capture (already
  probe-scrubbed: `RINCON_000000000000014xx`, `10.0.0.x`, `Navn N`, `FAKE-SERIAL-N`, `FAKEDEVICENN`, `item-N`,
  `images.example.invalid`), the other five are the real Spotify ids, replaced by `server/testdata/spotify/`'s fakes
  (`0FakePlaylistKveld0001`, `1FakeTrack{A,C,D,E}...`). `add_uri_to_queue.xml` and `media_info_queue.xml` carry only
  counts and the repo's existing fake uid (`RINCON_000E58A0B1C201400`); the hand-written ones fake ids and titles only.
- **The fake.** SoCo's add from `server/testdata/spotify/` (2311/3079, fault 800 for an unknown id or no service type),
  the clear (STOPPED when the queue was the source), the queue as the source, `TRACK_NR`, its own advance under each mode
  (NORMAL ends STOPPED with its track kept - provisional D7), Browse paged with `UpdateID` moving on every edit, 402
  without `SortCriteria`, `GetMediaInfo`, the sleep timer pausing at its end (provisional D21), the shaping options and
  the script events; a migration's `move` carries the queue. `--selftest` step 6 drives each.
- **Licence.** `MIT-SoCo.txt` is SoCo's `LICENSE.rst` verbatim; NOTICE.md names the ported parts and files; the three
  headers credit SoCo (`ShareLink.kt`, `Soap.kt`, `SonosTransport.kt`). The app has no licences screen: the notice ships
  in the APK's assets beside vibb's, as before.
- **Log hygiene.** The `share:` lines carry kinds, counts, positions and service types; `ShareLink`, `PlayBody` (a share
  body), `QueueRead`, `QueueMap`, `QueuePage`, `WsTrack`, `Metadata`, `ProbeLive`, `PositionInfo` and `Fields` print no
  Spotify id or title (`Fields.shown`: a queue track by its prefix only); `SourceScanTest`'s `d54` pins the share lines and
  the holders. The Browse answer, the metadata and `listed` are never logged.
- **Nothing changes for the kid.** `speakerDropReason` still returns `"spotify"` (`PlayBodyTest` pins it); nothing calls
  `playShare`, `queuePlay`, `readQueue`, `learnQueue`, `sleepTimer` or `PlayBodies.share`. The one behaviour change today
  is the park's clear (finding 8).
- **Tests** are lean and behavioural: the transfer's exact SOAP order, both gates, the retry and both failure kinds, N4,
  the re-read, `queuePlay`'s hush and cap, the park and the sleep timer's ownership, the session's classify cases and the
  probe, the pure rules with their boundaries, the socket's fields from the real capture.

## Mutations

| # | Mutation | Result |
|---|---|---|
| M1 | `ALBUM`'s item key `00040000` -> `1004206c` | caught - `ShareLinkTest` (goldens, wire) |
| M2 | a network failure (`SpeakerUnreachable`) in the add tries 3079 too | caught - `d54 3079 only after a UPnP fault` |
| M3 | only service 2311 is tried | caught - the same |
| M4 | no second gate before `SetAVTransportURI` | caught - `d54 a closed gate sends nothing more` |
| M5 | the settled seek also when the start item wasn't found | caught - `d54 N4` |
| M6 | `NewQueueLength 0` no longer refused | survived - equivalent: `length < first` throws the same `ShareRefused` |
| M7 | no re-read when `UpdateID` moves during the read | caught - `d54 the rest of the queue is its own job ... read again once` |
| M8 | `shareOurs`: no answer (null) counts as moved | caught - `SonosQueueTest` N2 and `SonosSessionTest` `d54 classify` |
| M9 | any Spotify container (an album's) contradicts | caught - the same two |
| M10 | the probe's continuity `+45 s` -> `+450 s` | caught - `sonos_migrate_probe 3` (both classes) |
| M11 | the stall retry for a share link | caught - `d54 a share link has no lost-session and no stall retry` |
| M12 | lost-session for a share link | caught - the same |
| M13 | the park doesn't clear the queue | caught - `SonosLifecycleTest` bedtime and `d54 the park clears the queue` |
| M14 | our Stop clears the speaker's timer whoever set it | caught - `d54 the park clears the queue ... only while it is ours` |
| M15 | the at-end END armed at the queue's last position | caught - `d54 54-8 the at-end arming` |
| M16 | the start's guess is `first` (not `first + trackIndex`) | caught - `d54 a transfer` (position 4) |
| M17 | the container never contradicts | caught - `d54 classify a share link` |
| M18 | a sleep timer for an edge more than a day away | caught - `d54 N1 the speaker's sleep timer` |
| M19 | `SetPlayMode REPEAT_ALL` instead of NORMAL | caught - `d54 a transfer` (the SOAP order) |
| M20 | `lastOursTrack`/`lastOursRel` recorded for a live track that isn't ours | **NOT caught** (finding 7's test should add a live, not-ours sighting) |
| M21 | a page's items placed one position early | caught - `d54 a transfer` and the re-read test |
| M22 | `Fields.shown` prints a queue track whole | caught - `d54 classify a share link` (no id in a line) |
| M23 | the unknown track's Browse verdict not cached | caught - the same ("read once per such track") |
| M24 | `playShare` never sets the sleep timer | caught - `d54 a transfer` and the park/sleep-timer test |
| M25 | duplicates all slotted to the first occurrence | caught - `SonosQueueTest` slots and confirm |

## Verdict

**0 High, 1 Medium, 9 Low, 2 Nit.** The round does what §4 gives it: the ShareLink request is SoCo's byte for byte
(checked against SoCo master's own plugin, retry included), `play_from_queue`/`clear_queue`/Browse are SoCo's with all
six Browse arguments, `playShare` follows §2.2's order with both gates and keeps `ShareRefused` and a network failure
apart, N4's not-found start plays `first` with no seek, the pure rules match the note (only a playlist container is
evidence, natural vs pressed on the anchor, the at-end arming, the sleep timer's values and its "only while ours"), the
session has vibb's no-stall/no-DIDL/no-lost rules and the exact-track probe, the fixtures carry no real id, address or
name, the fake and its selftest cover the queue, the SoCo notice ships, release lines carry kinds, counts and positions,
and the kid sees nothing new (`speakerDropReason` still refuses). Fix finding 1 before 54-a2 builds on `classify` (it is
vibb's rule: one line and a test); 2-8 in the same fix round (small, each where the data is); 9 into the note's "54-a2
inherits"; 10 before the 54-0 remainder's capture is saved; the nits with them. Mutations: 23 of 25 caught, M6 survived
as an equivalent (the `length < first` check throws the same), M20 not caught (finding 7). Owed unchanged: the Pi run of
`--golden` with vibb's SoCo and the 54-0 remainder (`--more`, `--listing`, `--add-only`).
