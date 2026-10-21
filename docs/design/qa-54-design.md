# Design QA - 54 Spotify on Sonos in the Vibb music app: vibb's `spotify_sharelink`

Reviewed on `main` at d7765e0b (2026-10-21). This is the one design QA round. I read
`docs/design/54-spotify-on-sonos.md` against:
- `CLAUDE.md` and `music/CLAUDE.md`. The rules that matter here: vibb unless the user decided; "nothing starts by itself
  on Sonos"; every play is gated; the DIDL is vibb's and never extended; RSS/NRK/Storytel stay one direct URI; and
  26c §7.1's Spotify logging rule.
- Designs 26a (§6.1 the park, the M1 status), 26c (§1's table, §11 step 9), 53 and its status, and PLAN's design 53
  entry with the measured socket facts.
- vibb `pipewire`:
  - `pi/sonosd.py`: `Session.play` 478-566, `verb("queue_play")` 664-690, `_classify` 688-810, the stall rule 895-899,
    `_probe_migration` 953-1000.
  - `pi/daemon.py`: the same-tile `intact` 950-1000, `_sonos_bookmark_now` 1330-1364, `_sonos_play_entry` 1477-1520,
    `_sonos_start_spotify` 1572-1665, `sonos_step` 1726-1790, `status` 2745-2820, the adopt 4497-4590, `_sonos_poller`
    4497-4760, `_sonos_on_term` 5631-5655.
  - `tests/sonos_spotify_queue.py`, fetched.
- SoCo master (MIT), fetched: `soco/plugins/sharelink.py`, and in `soco/core.py` `play_from_queue`, `queue_size`,
  `reorder_queue` and `PLAY_MODES`.
- The user's capture `music/app/src/test/resources/sonos/ws/capture_2026-10-21.json`.
- The code the note relies on: `M/sonos/*` (`PlayBody`, `SonosSession`, `SonosTransport`, `SonosController`,
  `SonosLifecycle`, `SonosSuspension`, `SonosEvents`, `NrkService`, `SonosHome`), `M/playback/PlaybackService.kt`
  (`suspendStop`, `toRoom`, `comeHome`, `lost`), `M/logic/PlayOrder.kt`, `M/logic/Guards.kt`.
- The server's Spotify listing (`server/src/spotify.rs`).
- `scripts/sonos-play-test.py` (its `--spotify` run, 48ef55a0) and `scripts/fake-sonos.py`.

Paths: `M/` = `music/app/src/main/java/me/vibb/music/`. "Note" = the design note; its line numbers are at d7765e0b. I
only read; nothing was built.

## What holds

- **The shape is vibb's v2, and it is the right one.**
  - One ShareLink add of the whole context, then positional jumps. The speaker plays the queue by itself.
  - Our list stays the screen's truth (vibb's "the card reads OUR list", `sonos_spotify_queue.py` 2).
  - The socket only wakes polls, and UPnP commands, verifies and bookmarks.
  - RSS/NRK/Storytel are untouched: `builtDidl` stays for `url`/`nrk_program`. The user's queue rule for podcasts is
    respected.
- **The ShareLink request matches SoCo byte for byte.** I compared §0.2 with `sharelink.py` master:
  - `canonical_uri`'s regex, and no artist.
  - `extract` (`:` -> `%3a`).
  - The five `magic()` rows: prefix, key and class.
  - The metadata template, with `dc:title` empty because vibb passes no `dc_title`.
  - The `SA_RINCON{sn}_X_#Svc{sn}-0-Token` desc.
  - `AddURIToQueue`'s five arguments.
  - The service order: `SpotifyShare` 2311, then `SpotifyUSShare` 3079. TIDAL, Deezer and Apple never match a Spotify
    uri, so the second fault really is the failure.

  `play_from_queue` is `SetAVTransportURI(x-rincon-queue:<uid>#0, "")`, `Seek TRACK_NR index+1`, `Play`, as the note
  says. Treating this metadata under the DIDL rule ("nothing added") is the right reading of the user's rule.
- **The vibb facts in §0.1 are accurate.** I checked each against the sources:
  - The NORMAL comment.
  - `q_base` from `FirstTrackNumberEnqueued`, with unparsable read as 1.
  - `queue_play`'s clamp.
  - The prefix `ours`.
  - No stall retry and no `lost_session` for this kind.
  - The migration probe's exact track plus `base - 5 <= rel <= base + 45`.
  - `sonos_map_trusted = (qlen is None or qlen == len(rows)) and not stale`.
  - A refused `queue_play` distrusts the map.
  - The 8 s settle with "a missing uri leaves the index".
  - The demoted DIDL fallback.
  - The same-tile `intact`.

  §0.4 matches the code: `PlayBody.kt:130`, `SonosSession.kt:22-25`, `SonosEvents.kt:61`, `MigrationProbe.live`
  (`SonosSession.kt:85`) and `SonosTransport.park` (`:294-303`).
- **The transfer's order is sound.** It runs hush, cap, gate, SetPlayMode, clear, add (2311 then 3079), probe, gate
  again, play, armed, settled seek, unhush. The second gate after the long add is a real improvement over vibb. A
  superseded transfer's map is taken in actor order (§2.3, last bullet), which mirrors `playing` today.
- **The bookmark is today's `bookmarkNow`** with vibb's hold and regression guard. The socket never writes. The
  duration carry is unchanged. This is vibb's sharelink branch one to one.
- **The migration probe for this kind** is `sonos_migrate_probe.py` 3 exactly, with the new uid's marker. That avoids
  the stored-hint trap.
- **Shared contracts: none.** I agree. The fake speaker reading `server/testdata/spotify/` is test data, and
  `music.yml` already triggers on that directory.
- **54-0 first** is right, as for design 53. `sonos-play-test.py --spotify` already exists, and it prints only the
  accounts document's service types, never serials or names.

## Findings

**54-1 (High) - "ours only for a listed track" turns off our own Stop and park.** When the speaker plays a track of
our queue that our list doesn't name, the time rule's park, the room -> phone Stop and the failed-start Stop all skip
the speaker, and it plays on.

The note's rule (§2.5 note:249-252, §2.9 :341-347): `ours` = the decoded TrackURI is one of the session's `listed`
uris, and adopt and the standalone suspension use "the prefix **and** an item of the stored entry". Every gate that
stops the speaker reads `ours`:
- the park (the time rule, with the service up): `parkGate()` = `snap.ours && live` (`SonosController.kt:1636-1640`);
- `handOff`'s Stop (room -> phone, room -> room, `lost()`): `snap?.ours == true || pending != null` (`:1618`);
- `abandon()` (`:1658`);
- the standalone suspension: `roomVerdict` -> NOT_OURS -> no park (`SonosLifecycle.kt:48`, `:153-168`).

A track that is not ours also makes `rendererState` TAKEN_OVER (`:459`). `spec()` gives READY with
`playWhenReady = false` (`:515`), so the renderer emits `Paused`, `showsPlaying` is false and the locks drop - while
our own playlist plays.

Such tracks are common, because the server's listing is filtered on purpose:
- it drops local files, `is_playable: false` tracks and podcast episodes inside a playlist (`spotify.rs:2385-2397`,
  `:2480-2494`);
- it reads with `market=from_token`, so a relinked track is listed under its relinked id (`:1965-1984`);
- it caps a list at 1000 tracks and a show at its 100 newest episodes (`:105`, `:107`).

The household's queue holds Spotify's own context. So at bedtime, on such a track, the suspension leaves the
playlist playing.

vibb's own Stop at shutdown (`_sonos_on_term`, daemon.py 5631-5655) is gated on the prefix rule, which counts every
track of the queue. Its test 2b guards only what the screen calls "playing".

Fix (§2.5, §2.9, §6). Split the two meanings:
- **`ours`** (the session): the speaker plays the queue of our transfer. That is the Spotify prefix (vibb), and the
  queue unchanged since our transfer. The `UpdateID` of a `Browse Q:0` answer, or the socket's `queueVersion` moving
  with no edit of ours, tells a queue someone replaced from the Sonos app - vibb's 2b guest, now detected and not
  assumed. A `GetMediaInfo` `CurrentURI == queueUri(uid)` is the extra check at adopt. Every Stop, park, lock, disarm
  and follow gate reads this.
- **`mapped`** (the item): the uri is a listed one, found through the map (54-3). The index, the bookmark, the done
  mark and the now-playing row read this. A track that is ours but not mapped gets no bookmark and no index move. What
  it shows is Q6: vibb shows the speaker's `TrackMetaData` title as its demoted fallback (`status` 2809-2818).

Tests:
- `SonosControllerTest` `d54`: the park and `handOff` stop the speaker on an unlisted track of our queue.
- `SonosLifecycleTest`: `roomVerdict` is OURS_LIVE for an unlisted track under a queue marker.
- A queue whose `UpdateID` changed is not ours (no Stop, no bookmark).

**54-2 (High) - the speaker's own advance is not gated, and a missed bedtime park now leaves a whole playlist playing
(with REPEAT_ALL or REPEAT_ONE, until someone stops it).**

How it works today:
- Today's room advance is our own gated play (`advance()`: `mayPlay() && held == null`, `SonosController.kt:1530`).
- After `suspendStop` the service comes home (`PlaybackService.kt:2112-2131`), so no controller watches the room
  any more.
- A park can end NO_ANSWER: no Wi-Fi at the edge (`SonosSuspension.kt:51-69`), or a Stop that fails or comes late
  (`SonosLifecycle.kt:162-167`). It is retried only at the next start, or at a play on the phone (26a status M1,
  `26a-sonos.md:1307-1309`). While the app is suspended, neither happens.

The cost of a missed park:
- With a `url` play, the rest of one episode, and then silence.
- With a queue, the rest of the playlist (up to 1000 tracks).
- With the note's `modeFor` (§2.7, note:307-311), REPEAT_ALL never ends and REPEAT_ONE replays one track all night.

vibb has no time rule, so there is no vibb answer to copy. The gate the user requires ("every play path is gated ...
a natural advance") has to be rebuilt for an advance we no longer send. Fix:
1. **A missed park of a queue session is retried** while the app is suspended and renderer.json still names the room:
   on Wi-Fi's return (the `SonosHome` network callback) and every few minutes, for a bounded time (for example 2 h).
   Use a unique WorkManager chain, or the receiver's own retry. Each try is the existing `suspensionStop`.
2. **The controller's belt**: at every confirmed boundary (natural or pressed) and on any ours PLAYING poll, if
   `mayPlay()` is false, Pause at once. Today the note pauses only in the 54-a correction path (§2.6, note:294-296),
   and the room-scope belt then also covers the speaker's own advance.
3. **NORMAL by default** (Q5, 54-9): a missed park then ends at the end of the queue.

Add to D16 "the edge with the phone off Wi-Fi: the room parked on Wi-Fi's return".

Tests:
- `SonosLifecycleTest`/`SonosSuspensionTest`: NO_ANSWER schedules a retry, and a later OURS_LIVE parks.
- `SonosControllerTest` `d54`: a natural boundary with `mayPlay()` false sends one Pause and no `queuePlay`.

**54-3 (High) - the positional map drifts as a matter of course, and "untrusted" never heals: every press becomes a
transfer of seconds.** The map is `first + i`, or `first + (n - 1 - i)` reversed (§2.3, note:198-213). It is trusted
on `len >= n` plus one or two one-item probes. Against our listing:
- **A gap in the middle shifts every later position**: a local file, an unplayable track or an episode the server
  dropped (54-1), or a relinked id that the probes didn't touch. The map still reads trusted, so a step jumps to the
  wrong track and the kid hears it. The 8 s pending treats it as "the old track" (§2.5, note:279-281). Only then does
  the confirm distrust the map.
- **Reversed with a cap can't map.** A show keeps its 100 newest episodes (`MAX_SHOW_EPISODES`, `spotify.rs:107`).
  If the show's queue is oldest first [D11], its position `first` is the show's oldest episode, not `listed[n-1]`.
- **Duplicates.** A track twice in a playlist is common in kids' playlists. `queueMoved` takes the item by uri
  (`adoptIndex`, note:266). The second occurrence maps to the first, so `index`, the bookmark row and "our next" jump
  back. In a non-following order, the 54-a correction then loops between the two occurrences.
- **"It ends at the next sync"** (note:217-219) holds only for a changed playlist. The causes above are permanent,
  so every step, pick and tile tap stays a full SetPlayMode + clear + add (1-5 s of silence, [D2]) plus up to 10
  Browses, for as long as the entry exists.

vibb's listing was go-librespot's context, much closer to what the speaker holds. This divergence is ours, by
construction.

Fix (§2.3):
- **The map is one read of the queue**: paged `Browse Q:0 BrowseDirectChildren`, 100 at a time, at most 10 pages,
  each `res` decoded as §2.5 does. It runs as its own actor job right after Play and outside the hush. The start
  itself needs only `findInQueue`'s early stop, or the probe.
- That gives `slot[listIndex]` (a position, or none), exact for gaps, extras, duplicates (matched in order), either
  direction and any cap. The `direction` field and the "reversed" formula go.
- An item with no slot can't be played on that room: a step skips it, as an unplayable item.
- **Trusted = the queue unchanged since the read** (`UpdateID`, or the socket's `queueVersion`, as in 54-1), verified
  at each confirm as now. When trusted, the index comes from the polled `Track` through the slots and the uri only
  verifies it; a uri search is the untrusted fallback, preferring the occurrence nearest the expected one.
- A `load` of the same entry with a changed listing rebuilds the slots from the read (no new transfer). So do adopt's
  `learnQueue`, a follow and every 54-b move.

Device checks: for three or four real playlists and a show of the house, compare a full Browse with the server's
listing: relinked ids, unplayable tracks, local files, episodes, duplicates and the show's order.

Tests: `SonosQueueTest`, the slots for a gap, an extra track, a duplicate, a capped reversed show and a relinked id.

**54-4 (Medium) - the `itemId` follow meets UPnP's lag.** The socket sees the change 0.3-2.6 s before
GetPositionInfo does (design 53 §0.1). The note's immediate confirm poll (§2.5, note:253-260) can therefore read the
**old** track. Three things go wrong:
- That poll is ours, so it clears `wsBlocked` (`SonosController.kt:1250`). The new track's next socket PLAYING
  (`positionMillis` near 0) then re-bases the old track's anchor, and the bar jumps to 0:00 under the old title.
- Nothing polls again until the cadence: 15 s cruise after a press mid-track, 5 s at a natural end. The note's
  "within ~1 s" (§2.11, note:386-387) holds only when the first poll happens to be late enough.
- Design 53 hit exactly this for IDLE and added the recheck (`:1288-1296`, qa-53a-code 4).

Fix:
- After an `itemId` change, re-poll every `IDLE_RECHECK_MS` until a poll names a uri other than `playing`'s, or
  until `IDLE_RECHECK_UNTIL_MS`.
- `wsBlocked` clears only on that poll.
- Pair `playing` with the `itemId` seen when it was confirmed, and count a socket measurement only when its `itemId`
  equals the pair.

Test: an `itemId` event, then a lagging ours poll of the old track, then a PLAYING event with 500 ms. The anchor is
untouched, a second poll follows within 1 s, and the new track is confirmed.

**54-5 (Medium) - "natural or pressed" is decided on a socket field, and the 54-a correction overrides the Sonos
app's play modes.**
- `boundary` takes `previousPositionMillis` within 3 s of the duration (note:268-273). That is a decision taken on
  the socket, against design 53's rule "every decision that matters is still taken on a UPnP snapshot". The field is
  also unmeasured for a queue [D6]: the capture has it only for direct URIs.
- The correction fires whenever the new track "isn't our expected next" (note:294-296). Someone who switches on
  shuffle or repeat-one in the Sonos app makes every natural boundary "not our next", and the app corrects each one.
  That is a fight §7 rules out (note:599-600).
- Without a socket the app never knows the mode: the note reads `playModes` from the socket only (note:300-302).

Fix:
- Natural = the previous track's anchor, extrapolated to the change's time (the event's `at`, or the confirming
  poll's `measuredAt`), within `NATURAL_END_MS` of its duration; or `endsNear > 0` without a socket.
  `previousPositionMillis` is only a cross-check, logged in a debug build.
- Before any correction (and before 54-b's move), read the mode: one `GetTransportSettings`, or the socket's last
  `playModes`. A mode we didn't set (SHUFFLE*, or REPEAT_ONE we didn't send) is followed, never corrected.

Test: a natural boundary under a foreign SHUFFLE sends no `queuePlay`.

**54-6 (Medium) - as written, every step is a transfer.** "Intact" includes "the last fresh snapshot is ours with
nothing settling", and the same rule covers steps, picks and corrections (§2.4, note:227-233). But `stepTo` sets
`pending = Pending(...)` before its worker plays (`SonosController.kt:930`, `:952`), so a step always finds something
settling. Even a step that missed its own pending meets the previous `queuePlay`'s pending during a mash, so the
second press re-adds the playlist.

vibb has two checks:
- steps and picks check only `sonos_map_trusted` (`_sonos_play_entry`, daemon.py 1484-1490);
- `ours`, fresh and "not settling" belong to the same-tile rule alone (963-975).

Fix: steps, picks and corrections need only the map trusted, this entry and this uid. The tile alone needs ours,
fresh and nothing settling.

Test: vibb's case 3, with a second step inside the first one's settle: two `queuePlay`s and no add.

**54-7 (Medium) - the household check can block a household where Spotify works.**
- Detection reads `/status/accounts`, and ABSENT refuses before any SOAP, re-read every 60 s (§2.13,
  note:421-427). The document's shape on current firmware is unknown [D1]: the fixture is hand-written.
- The repo's own `accounts.xml` names `Type="2311" ... Deleted="1"`. Under §2.13 that household reads ABSENT.
- If a linked Spotify isn't listed there, the feature is off for good, and the kid is told to ask an adult who can do
  nothing.
- The check saves one refused `AddURIToQueue`, a tenth of a second. The failed add already gives the same words.

26c step 9 asked for "when the room's household has Spotify", but not as a pre-block.

Fix:
- Never refuse on the document. Always try the add.
- The document only orders 2311/3079. After both faults, it picks `SONOS_ASK_ADULT` over `CANT_PLAY_ON_SONOS`; that
  needs a reason passed to `lost()`, which emits `CANT_PLAY_ON_SONOS` unconditionally today (`PlaybackService.kt:2289`).
- An add that answers `NumTracksAdded 0` or `NewQueueLength 0` (a playlist the linked account can't open, [D12]) is
  the same failed start, before any Seek.
- The document is never logged or kept: `UN`/`NN` hold account names. Only the verdict is cached.

**54-8 (Medium) - the end of our list with a queue that goes on is half specified.**
- **The pause at our last item** (§2.7, note:315-317) is armed whenever our last item plays with repeat off, also
  when the speaker would stop by itself anyway (a full forward listing, our last item at the queue's last position).
  vibb lets the speaker stop there. The pause leaves a PAUSED queue 0.4 s before the end, and the last track gets no
  natural boundary, so no done mark. Arm it only when the slot after it exists or a repeat mode is on.
- **Repeat all with a capped or non-following map**: after our last item the speaker plays position n+1, an unlisted
  track. Under the note's rule that is "observe, never act" (`pollTick :1248`), so the room plays unlisted tracks to
  the end of the queue and only then wraps. The at-end mechanism should play our first item (gated `queuePlay`) when
  the speaker's next isn't it.
- **`advance()` at the end of the queue** plays through `playIndex` (`:1531`), which for this kind is a transfer.
  Say that `advance()` uses `queuePlay` when the map is trusted.

**54-9 (Medium) - repeat mapped onto the speaker's play modes is a choice, not a consequence (Q5).**
- vibb always sends NORMAL ("NORMAL kills family shuffle/repeat leftovers", sonosd.py 522-529). The capture shows the
  leftover in this house: the parked queue carries `repeat: true` (`capture_2026-10-21.json` messages 3, 30, 55).
- Design 49's repeat on a room is today our own gated `advance()`: it wraps for all and replays for one. NORMAL keeps
  that:
  - repeat all = the gated wrap at the end of the queue or of our list (a STOPPED, then a `queuePlay`: about 1 s of
    gap, gated);
  - repeat one = a gated `Seek REL_TIME 0:00:00` from the at-end timer (the "Til slutten" mechanism, 400 ms early),
    or a `queuePlay` of the same position at the boundary.
- What REPEAT_ALL/REPEAT_ONE on the speaker add: gapless repeat. What they cost: every repeat is ungated (54-2: all
  night after a missed park), and the Sonos app shows and can change them.

Recommend NORMAL (vibb), with repeat as our gated advance, and ask the user (Q5).

**54-10 (Low) - Q1 is missing an option, and 54-b is a state machine where one pass would do.**
- 54-b moves our next item into place after every confirmed track (note:297-303): a permutation kept up to date track
  by track, moves around the current track, and the Sonos app's queue view changing every few minutes.
- One reorder of the whole queue into our order right after the start does the same with one rule. It runs as N
  `ReorderTracksInQueue` calls in a background actor job, computed from 54-3's slots, and runs again on `reorder`
  (flip, shuffle). Then the speaker's own advance, its Next/Previous and the Sonos app's queue all follow the kid's
  list. It is gated on D10 like 54-b, and it costs about 50-100 ms per move.
- A third option: our order as single-track ShareLink adds, one `AddURIToQueue` per track. That is SoCo's "track"
  type, nothing added to the metadata, but slow for long lists.

Put both into Q1.

**54-11 (Low) - logging and `toString`.**
- `SonosHome.log` is `Log.i` in a release build (`SonosHome.kt:137-139`). 26c §7.1 (in `music/CLAUDE.md`) keeps a
  release silent about Spotify: fixed words only, never a uri list. The note lets a line "name a target or one track
  uri" (note:443-444).
- Fix: in a release, Spotify-on-a-room lines carry kinds, counts and positions. A target or track id only under
  `BuildConfig.DEBUG`.
- `PlayBody.toString` is explicit (`PlayBody.kt:52-55`) and must leave `listed` out. Browse answers (the household's
  whole queue) and the accounts document are never logged.
- `SourceScanTest` pins it.

**54-12 (Low) - SoCo details and real goldens.**
- §0.2:
  - `Browse` has a sixth argument, `SortCriteria ""` (SoCo `queue_size`); without it a speaker may answer 402.
  - `reorder_queue` sends `InsertBefore insert_before + 1`; the note's formula drops the `+ 1`.
  - SoCo tries 3079 only after a `SoCoException`, so a timeout is not retried. State that a network failure ends the
    add at once.
- **The ShareLink goldens are a copy of a copy.** `--golden` builds them from `SHARE_MAGIC` and a template typed into
  `sonos-play-test.py` (`:342-367`), so `ShareLinkTest` compares one transcription with another. On the user's Pi,
  where vibb's SoCo is installed, write them from the real `ShareLinkPlugin` with a recording `avTransport` (capture
  `AddURIToQueue`'s arguments). Fall back to the copy only without SoCo, and say in the file which one wrote it.
- `builtDidl`'s exhaustive `when` (`PlayBody.kt:65-71`) gets a throwing branch for the new kind.

**54-13 (Low) - the cadence hint and the battery.**
- `cadenceHintS` is the duration of the play's start item (`SonosSession.kt:520`). After the speaker's own advance it
  belongs to another track. For this kind, use the item now playing's listed duration, or none.
- The 5 s tail cadence covers the last 45 s of every track: for 3-minute songs, a quarter of the time at 5 s. With a
  healthy socket, the `itemId` confirm (54-4) covers the boundary. Keep the tail cadence for this kind only without a
  healthy socket, or while an at-end action is armed.
- A self-advancing queue holds the phone's wake and Wi-Fi locks for hours. That is today's rule; worth a sentence in
  §2.11.

**54-14 (Low) - doc accuracy.**
- §6's row "vibb: the park: Stop + the queue uri" (note:588): vibb has no park. The park is 26a §6.1's. vibb's
  nearest is `_sonos_on_term`, a Stop gated on the prefix rule. The clear is the necessary consequence of our time
  rule with a queue (see the table below), not a deviation from vibb.
- §0.1 "the bookmark ... under the refused-seek hold" is right. Add that vibb throttles it to 25 s while the app
  writes at most every 5 s (design 53): that is ours and fine.
- 54-a2 is the bulk (the controller, the service, the lifecycle, the strings, the docs). Its code QA should take the
  controller and the lifecycle in separate passes.

## The deviations from vibb - consequence or choice

| Deviation | Verdict |
|---|---|
| "ours" only for listed tracks | **A fix, and part of it is a choice.** Splitting ours from mapped is needed (54-1). What an unmapped track of our queue shows is **Q6**: vibb's DIDL title, or "brukes av noe annet". |
| repeat -> REPEAT_ALL / REPEAT_ONE on the speaker | **A choice for the user** (Q5, 54-9). Design 49 needs repeat; it doesn't need the speaker's modes. |
| our order through the 54-a correction / 54-b placement | **A consequence of designs 39/49**, the user's. The mechanism is the choice (Q1 + 54-10). The correction must never override a mode set in the Sonos app (54-5). |
| the at-end pause 400 ms before the end for "Til slutten" | **A consequence of design 50** (the speaker doesn't stop between tracks). It is the web player's own mechanism. No question. |
| the at-end pause at the end of our list | **A consequence only when the queue would go on** (capped, non-following, untrusted). Otherwise keep vibb's natural stop (54-8). No question. |
| the park clears the queue | **A consequence of our time rule** (the park is 26a's, not vibb's). No question. It needs 54-1's gate. |
| the map's direction, probes, `findInQueue` | **A consequence of our listing** (the server's, filtered). Replace them with the read of the queue (54-3). |
| the household check | **Ours, anticipated by 26c step 9.** Keep it advisory only (54-7). |
| natural vs pressed by `previousPositionMillis` | **Ours, against design 53's rule.** Decide on UPnP (54-5). |
| the skip rules (no wrap without repeat all) | **A consequence of design 49.** |

## Tests - add to §3

- `SonosControllerTest` `d54`:
  - the park and `handOff` stop an unlisted track of our queue (54-1);
  - a natural boundary with `mayPlay()` false: one Pause, no `queuePlay` (54-2);
  - a second step within the first one's settle is a `queuePlay`, not an add (54-6);
  - the `itemId` lag: the anchor is kept, the recheck runs, the new track is confirmed (54-4);
  - no correction under a foreign SHUFFLE or REPEAT_ONE (54-5);
  - no at-end pause when our last item is the queue's last position (54-8);
  - repeat all past our last item on a capped map plays our first item (54-8);
  - duplicates: index from `Track` through the slots (54-3).
- `SonosLifecycleTest`: `roomVerdict` OURS_LIVE for an unlisted queue track under a queue marker (54-1).
  `SonosSuspension`: a NO_ANSWER park is retried (54-2).
- `SonosQueueTest`: the slots for a gap, an extra track, a duplicate, a capped reversed show and a relinked id; trust
  by `UpdateID` (54-3).
- `ShareLinkTest` against goldens that SoCo wrote (54-12). `SonosTransportTest`: `Browse` carries `SortCriteria`; an
  add of 0 tracks is a failed start with nothing played (54-7, 54-12).
- `SourceScanTest`: no Spotify id in a release-built Sonos log line (54-11).

## Device checks - add to §5

- D1+: whether a Spotify linked in the Sonos app appears in `/status/accounts` at all on the house's firmware (54-7).
- D2+: `NumTracksAdded`/`NewQueueLength` for a private playlist of the kid's (54-7). The largest queue Sonos takes
  from one ShareLink (a 1000+ playlist).
- D3+: a full Browse against the server's listing for three or four real playlists and a show: relinked ids,
  unplayable and local tracks, episodes, duplicates, the show's order (54-3).
- D6+: the `itemId` change on a natural boundary and on a press, and how long GetPositionInfo lags it (54-4).
- D8+: the household's crossfade setting (`playModes.crossfade`) - its effect on the boundary and on the at-end
  pause. `Seek TRACK_NR` and `Track` with SHUFFLE switched on in the Sonos app (54-5).
- D10+: the queue's `UpdateID` (Browse) and the socket's `queueVersion` after our add, our reorder and an edit from
  the Sonos app (54-1, 54-3).
- D16+: the suspension edge with the phone off Wi-Fi, then Wi-Fi back: the room is parked by the retry (54-2).

## Questions for the user

- **Q1 (our order on a room)**: a real choice, and the default (54-b) is reasonable. Two changes:
  - Add the options from 54-10: one reorder of the whole queue after the start, and single-track adds in our order.
  - Say plainly what the interim costs: until 54-b (and for good if D10 fails), every track change of a shuffled or
    flipped list plays about a second of a wrong track. For shuffled kids' music, that is every track.

  I'd recommend the one-pass reorder if D10 holds, else the correction.
- **Q2 (no parent switch)**: a real choice, and the default is sensible. Add one fact for the user: the kid's
  listening lands in the linked account's history and recommendations, often a parent's.
- **Q3 (context mode on a room)**: a real choice, and the default (yes, 54-c) is sensible. With no listing, "ours" is
  vibb's prefix rule. With 54-1's `UpdateID` check, a guest's Spotify that replaced our queue in the Sonos app is no
  longer taken for ours. Without it, the kid's bedtime park would stop a parent's music.
- **Q4 (shows and episodes)**: the default is vibb's, so this is a confirmation more than a choice. Fine to ask. Note
  that the show's queue order [D11] and the 100-episode cap are handled by 54-3, not by a guessed direction.
- **Add Q5 (repeat on a room)** (54-9): vibb's NORMAL always, with repeat done by our gated advance (about 1 s of gap
  at a repeat-all wrap; repeat one as a seek to 0 at the track's end)? Or the speaker's REPEAT_ALL/REPEAT_ONE (gapless,
  but never gated, and all night after a missed park)? Recommend NORMAL.
- **Add Q6 (a track of our queue that our list doesn't have)** (54-1): vibb shows the speaker's title for it (its
  demoted fallback; no picture from the speaker, the entry's cover), keeps it ours, writes no bookmark, and plays on.
  The note shows "Høyttaleren brukes av noe annet" and drops the play state. Recommend vibb's.

## Verdict

**Approve with changes.** The port of vibb's v2 is faithful where it ports: the ShareLink request to the byte, the
classify, the probe, the bookmark, the transfer as the heal. RSS/NRK/Storytel are left alone, as the user asked.
Before 54-a1, the note needs:
- the ours/mapped split, with Stop and park gated on our queue (54-1);
- the speaker's own advance gated, and a missed park retried (54-2);
- the map as a read of the queue, trusted by its `UpdateID` (54-3). This changes `SonosQueue`'s shape, which 54-a1
  builds.

Then the Mediums:
- the `itemId` recheck and pairing (54-4);
- the boundary on UPnP, and no override of the Sonos app's modes (54-5);
- the step rule (54-6);
- the household check advisory only (54-7);
- the end cases (54-8);
- repeat as a question (54-9).

Ask Q5 and Q6 together with Q1-Q4. Take the Lows into the note as they apply.
