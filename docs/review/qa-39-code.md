# QA 39 (code): pictures in the episode list and the order flip

Code QA, 2026-10-19. Under review: `3ea773b6` (branch `music-list-39`, two commits on `b471096d`: `23fae955` server,
`3ea773b6` music app, fake, docs). I read it against `docs/design/39-music-list-pictures-and-order.md` (§1-§4, the
Revision section and its "Implementation status" with the seven listed choices), `docs/design/qa-39-design.md`'s
"Final check (e002e9b3)" (M6, L12, N7), `music/CLAUDE.md`'s design 27 and design 39 paragraphs,
`docs/notes/server/music.md`'s episode-art section and `docs/testing/emulator.md` §6q. Line numbers are `3ea773b6`'s.
I changed no product code. Every mutation and probe was reverted, and the tree was clean before this file was written.

**Verdict: sound, merge after a small fix round.** What the user asked for holds:
- pictures show only where they say something, and the list and the carousel use one rule;
- the want set stays bounded (60 held, ±10 rows), and kept pictures are never pruned because of the list;
- the flip goes through one seam (`Snapshot.order`). Everything kept (`keepSet`, downloads, the art keep sets,
  `NewItems`) stays in the parent's order, which a mutation proved;
- the server walks every item under the unchanged budgets, with M6's reserve;
- the fake's pictures are judged `own` by the real server path. I repeated the implementer's throwaway check.

What has to change:
- **M1**: a flip while Spotify plays restarts the track at the last SDK measurement, not where it actually is. The
  kid hears up to ~30 s again (a probe test showed 10 s replayed).
- **L1**: 4 of 15 mutations survive, all of them glue code with no test: the LiveBridge send gate, the service's
  queue for a flip, the list's picture rule at its call site, and `setFlipTx`'s rewrite of the stored queue.

Counts: **0 High, 1 Medium, 3 Low, 4 nits.**

## Runs (all local, under `flock ~/build.lock`, 4.6-5.6 GB available)

| Check | Result |
|---|---|
| server `cargo test -q -j 2` | ok, **1032 passed**, 0 failed |
| server `cargo fmt --check` | clean |
| server `cargo clippy --all-targets -q` | 13 warnings, the known ones (`models.rs`, `dns_engine.rs`, `storytel.rs`, `handlers/devices.rs`); none in the changed files |
| music `./gradlew --no-daemon --max-workers=2 testDebugUnitTest assembleDebug -PwarningsAsErrors=true` | BUILD SUCCESSFUL, **913 tests**, 0 failed, 0 skipped |
| `python3 -I scripts/fake-audio.py --selftest` | OK (selftest 3: 251 pictures, at least 13 bits apart) |
| the fake through the server's real path (throwaway test in `photos::tests`, not committed) | all 251 `--feed 250` pictures: `process(Square)` -> q85 JPEG -> `print` equals the modelled print, none `low_detail`, min distance 13 (> `SAME_PICTURE_BITS` 5) |

Nothing went through CI.

## What I checked in the code

1. **The picture rule.**
   - `rowPictures` is `showsCarousel` itself (`EpisodeArt.kt:101`), so a one-picture entry keeps the numbers.
   - A row with `artHash == null` in a pictured entry is the series `Cover` with its number on `Palette.scrim`
     (`ListScreens.kt` `ListRowView`), decided by the listing's hash, not by the file.
   - Colours: Palette only. The button is 44 dp (`RoundButton(..., 44.dp, 20.dp)`) with `flip_order` as its content
     description, `selected` and `stateDescription` = the order words.
   - The line's fit rule measures at the font scale (`ListLine`: `rememberTextMeasurer` + `orderWordsFit`; `FitTest`).
   - `Cover(decodeDp)`: `inSampleSize` from the bounds, its own LRU and slot. At density 2.75 a row is 110 px ->
     sample 4 -> 64 KB a thumb.
   - The rule reads the rows, the carousel reads the queue: nit N1.
2. **The want set.**
   - The window is the visible rows ±10, clamped. `listArtAfter` caps at 60 (`LIST_ART_MAX`), own hashes only, and
     another entry starts empty. It is written atomically to `list_art.txt`, and only when it changed.
   - `ArtWants.compute` reads the list itself, so every sender carries it (the source-scan in `EpisodeArtTest`).
   - On the wire: `now`, then the list, then the keep sets, within 600. `keep = base + list + recent`, where `base`
     is the 600 computed without the list.
   - The forced send rule is pure and posted to main (`ListArtWants.note`: `Handler(Looper.getMainLooper())`).
     `wantArtSend` with `force == false` is exactly the old early return.
   - **Nothing changed for `now` or the gate.**
     - `nowAll` is still first in `ordered`, so `now` is the same list as before.
     - The list's hashes only ever go in `later`, which the launcher fetches with the downloads' network gate
       (`artToFetch`, untouched; no launcher change in the diff).
     - The player's sends are the non-forced path, unchanged.
3. **The flip.**
   - `Snapshot.order` is the only reader of `flips`, apart from `flipped`/`shownNewestFirst` for the button.
     `keepSet`, `storytelKeepSet`, `downloadPlan`, `artWant`'s keep sets and `NewItems` still call `playOrder`
     (`Offline.kt:48`, `Storytel.kt:66`, `NewItems.kt:138`). Mutation 5 (the download plan's keep set following the
     flip) is caught.
   - `setFlipTx` writes the row and the stored queue in one `@Transaction`, with `flippedNowPlaying` from the stored
     row's current item, index 0 and the place kept. A shuffled row or another entry's row is untouched.
   - The reset works because the library's `order` is the server's `effective_order` (`music.rs:1169`). So an `auto`
     NRK series crossing 100 arrives as `newest_first`, the row goes stale, and `flipsToDelete` prunes it. A gone
     entry's row goes only when `entries` covers `entryIds`, which includes the unreadable ones.
   - The race:
     - The VM toggles under a mutex, reading the row each time.
     - `FLIP_ORDER` goes after the write and carries the key and the direction.
     - The service drops another entry's command, and a command for the direction it already has.
     - Either ordering of PLAY/FLIP ends in the shown direction. `flippedQueue` is idempotent, so a stored queue
       rewritten after the service already saved the new order is unchanged.
   - Numbers and "N av M" follow (`OrderFlipTest`). Storytel's "Del N av M" stays the reading order. The scroll goes
     to the playing row once per direction change.
4. **Reorders while playing.**
   - The phone: `LocalRenderer.reorder`, as shuffle does.
   - Sonos: `flippedQueue(newOrder, a.items keys, current)` keeps the room's speaker-playable queue, so the HLS item
     stays out. `SonosRenderer.reorder` maps keys through the full `a.order` and sends no SOAP (`SonosControllerTest`
     d39). No test pins that the service passes the queue's keys rather than the order's: mutation 6, L1.
   - Spotify list mode:
     - Paused, it makes no call (the timer's own `pwr` check), and `windowStale` makes the next Play a fresh start.
     - Playing, it does one coalesced `PUT /me/player/play`, since `cancelStep` cancels the earlier timer.
     - `SpotifyTracker.reorder` sets `known` to the end and `addAsked = -1`, so no queue add goes out between a flip
       and the next play. The adds are by URI, so the leftover add is still recognised.
     - The API budget is one play per settled flip, nothing else.
     - The restart position is wrong: M1.
   - **The implementer's "flip during the move-on" gap is not a real risk.** `moveOn` is set only around
     `onEvent?.invoke` inside the synchronous flush (`SpotifyRenderer.kt:1730-1734`). `FLIP_ORDER` is a separate
     main-thread command, so it can't land inside it. A flip after the move-on finds the move-on's start in flight:
     `reorder` supersedes it and schedules a fresh start of the current item, which is the move-on's target.
5. **The server.**
   - `.take(ART_WINDOW)` is gone, and the constant is removed. Candidates are every `art_candidate` in play order,
     new ones first.
   - The budgets are untouched: `art_gets`, 20 / 100, 400.
   - The backlog's five-`same` stop has no diff.
   - `ART_DAY_RESERVE = 100` is checked per request against `left` (the day's room at the pass's start, decremented
     with `gets`), so a backfill stops at 300 used.
   - **`fresh`** is `is_new`. With the backlog on, that is first seen in this check. With the backlog off, older
     items aren't candidates at all, so the "first seen since it went off" clause only decides candidacy, never the
     reserve. That is M6's intent. One edge: a brand-new entry's first check, L2.
   - **The changed `twenty_a_check_and_400_a_day_…` is justified.** With the reserve, the old 5 backlog requests at
     395 used are now correctly 0.
     - The test now brings 10 new items and asserts that the day's last 5 go to them.
     - It still asserts the 400 cap and the untried count (5 new + 10 older).
     - Mutation 3 (fresh always false) makes it fail. So it guards new items' access to the reserve and hides no
       regression.
   - `restamp_versions`, the prune and "N with episode art" have no diff.
   - `music_listing.json` and `music_library.json` are untouched (the diff names no testdata).
6. **The fake.**
   - `--feed N`: image URLs are on the request's `Host` and enclosures on `--advertise`. Every third item is the
     channel URL.
   - `--hls K`: the selftest asserts item K's `.m3u8` URL.
   - The real-path check is in the Runs table.
   - One doc nit in §6q: L3.

## Findings

### Medium

**M1. A flip while Spotify plays restarts at the last measurement, up to ~30 s back** (`SpotifyRenderer.kt:487`).
- **What fails.** `val at = tracker.measured?.takeIf { it.index == index }?.positionMs ?: startPosition()` takes the
  raw position of the SDK's last state. States come on events (play, pause, seek, a track change), and the renderer
  only extrapolates between them in `positionMs()`, capped at `SPOTIFY_STATE_GAP_MS` (30 s). So the coalesced
  `PUT /me/player/play` restarts the track where the last state was, and the kid hears that stretch again.
- **Evidence.** A probe test (not committed): `playing(at = 2)`, `post(state(2, 40_000))`, `advance(10_000)`, flip,
  `advance(300)`. The play's `position_ms` was **40000**, where ~50 000 was expected.
- **Why the tests missed it.** The two d39 renderer tests post a state right before the flip, so this never shows.
  The design says "a fresh start at the tracker's measured place … Nothing restarts from 0:00". The intent is the
  current place, and the sync `place` is already the extrapolated one.
- **Fix.** Take `val at = positionMs()` before `index`/`place` are replaced. It already folds in the measurement
  (`syncPlace`) and the moving extrapolation. Add the probe as a test, asserting `position_ms >= 49_000`.

### Low

**L1. Four survivors: glue that no test pins.** Add one test each:
- **a. The LiveBridge send gate** (`Bridge.kt:166`). Mutation 3b restored the old `if (now.isEmpty() || now == sentNow)
  return`, and everything passed. The pure `wantArtSend` is tested, but whether `LiveBridge` calls it isn't. This is
  H1 of the design QA (the list's pictures never sent while a downloaded episode plays). Add a source-scan assertion
  in `EpisodeArtTest`'s d39 block for `if (!wantArtSend(now, later, sentNow, sentLater, force)) return` and
  `sentLater = later.toSet()`.
- **b. The service's flip queue** (`PlaybackService.kt:1149`). Mutation 6 built `flippedQueue` from `a.order`'s keys
  instead of `a.items`' keys, so the HLS item comes back into a room's queue, which is QA M1. Everything passed:
  `QueueTest` pins the function, not the call. Either extract a pure `flipPlan(order, items, index)` into `Queue.kt`
  and test it with a filtered queue, or add a source-scan for `flippedQueue(a.order.map { it.key }, a.items.map {
  it.key }, current.key)`.
- **c. The list's picture rule at its call site** (`MusicRoot.kt:148`). Mutation 9 (`pictures = false`, so the
  carousel alone had the rule) passed. Add a source-scan in `EpisodeArtTest` for
  `rowPictures(shown, n.entry.cover)` and `ListScreen(..., pictures = pictures`.
- **d. The stored queue's rewrite in `setFlipTx`** (`MusicDb.kt:411`). Mutation 10 skipped the
  `flippedNowPlaying(...)?.let { upsertNowPlaying(it) }`, which is QA M5, and passed. `OrderFlipTest` covers
  `flippedNowPlaying` only. Add a source-scan of `setFlipTx`'s body, as `OfflineTest` does for the migrations.

**L2. A brand-new entry's first check counts every item as new** (`music_sweep.rs:3556`, `3631`).
- **What happens.** `first_seen_at == this_check` holds for the whole listing at its first check, so a newly added
  1000-item feed's first pass may spend into the 100 kept for new items. That is 20 for RSS. For a Spotify list it is
  `min(100, left/2)`: with 150 left it takes 75, the reserve's 50 included. Bounded, and only once per entry.
- **Fix.** Either accept it and say so in `docs/notes/server/music.md`'s reserve paragraph, or count an item as new
  only when the entry had a previous listing (`old.items` non-empty). Then a new entry's own backfill also stops at
  300. I lean towards the second, with one line in `a_backfill_leaves_the_days_reserve_to_new_items`.

**L3. §6q step 4 runs two fakes on one port** (`emulator.md:1512`). `fake-audio.py` and `fake-spotify.py` both
default to 8731, and step 4 asks for the RSS feed and a fake Spotify playlist in one round. Say `--port 8732` for
`fake-audio.py` there (the PWA's feed URL becomes `http://127.0.0.1:8732/feed.xml`, and the enclosures follow
`--port`).

### Nits

- **N1.** The list's `rowPictures` reads the visible rows in the shown order (`listOrder`), and the carousel reads the
  queue (`NowView.queue`). They agree whenever the queue is the visible set. They can differ on a room's
  speaker-playable queue, or on a stored queue that lost items, when the only differing picture is on an item that is
  out of the queue. The design says "items = the rows shown", so this is accepted. One line in the CLAUDE.md
  paragraph would stop a reader from assuming they are identical.
- **N2.** `flippedNowPlaying` (`MusicDb.kt:128`) with a stored current item that is no longer in `newOrder` (gone
  from the listing): `rotatedTo` returns the order unrotated, so index 0 is another item while `posMs` keeps the old
  item's place. A restore would start that episode at the old place. Return null when `current !in newOrder`.
- **N3.** `begin` clears `windowStale` before its gates (`SpotifyRenderer.kt:877`: detached, `mayPlay`, `inCall`,
  `networkLine`). A flip's coalesced start that one of them refuses leaves the window "fresh" for a later SDK resume.
  `refuse` calls `tracker.lost()`, and the other gates destroy the page or don't arise while playing, so I found no
  reachable path. Still, clear it after the gates, where the start really goes out.
- **N4.** The visible-range flow (`ListScreens.kt:145`) has `distinctUntilChanged` on `(first, last)`. After a flip,
  if the scroll to the playing row lands on the same index range, the new rows' window isn't reported until the next
  scroll. That is rare (the playing row moves to the top), and `pictures` stays keyed on the entry. Add the shown
  direction to the key.

## Mutations (each one reverted)

| # | Mutation | Caught by |
|---|---|---|
| S1 | `ART_DAY_RESERVE = 0` | `a_backfill_leaves_the_days_reserve_to_new_items` |
| S2 | `fresh` always true (the reserve never applies) | `a_backfill_leaves_the_days_reserve_to_new_items` |
| S3 | `fresh` always false (new items held by the reserve) | `twenty_a_check_and_400_a_day_in_play_order`, `a_backfill_…`, `a_day_of_backfill_stays_within_the_budgets` |
| 1 | `rowPictures` = `items.size > 1` (a one-picture entry gets pictures) | `EpisodeArtTest` d39 rule |
| 2 | `listArtAfter` without `.take(LIST_ART_MAX)` (unbounded) | `EpisodeArtTest` d39 held list |
| 2b | `LIST_ART_MARGIN = 0` | `EpisodeArtTest` d39 window |
| 3 | `wantArtSend` ignores `force` (the old rule only) | `EpisodeArtTest` d39 send rule |
| 3b | `LiveBridge.wantArt` back to the old early return | **survived** (L1 a) |
| 4 | `keep = ordered + recent` (the prune's keep without the list's guarantee) | `EpisodeArtTest` d39 L2 |
| 5 | the download plan's `keepSet` follows the flip | `OrderFlipTest` "Snapshot order flips, the keep set and the downloads do not" |
| 6 | the service's `flippedQueue` from the order instead of the current queue (Sonos HLS back) | **survived** (L1 b) |
| 7 | Spotify `reorder` schedules the start while paused (outer `pwr` removed) | survived, **equivalent**: the timer checks `pwr` again |
| 7b | the same with the timer's `pwr` removed too (a play while paused) | `SpotifyRendererTest` d39 paused |
| 8 | `reorder` leaves `windowStale` false (an SDK resume in the old window) | `SpotifyRendererTest` d39 paused |
| 9 | `MusicRoot`'s `pictures = false` (the rule for the carousel only) | **survived** (L1 c) |
| 10 | `setFlipTx` skips the stored queue's rewrite | **survived** (L1 d) |
| P | probe: flip 10 s after the last state | position 40 000 (M1) |

Score: **11 of 15** non-equivalent mutations caught (server 3/3, music 8/12). The four survivors are L1.

## The implementer's deviations

1. **`FLIP_ORDER` carries `ARG_ENTRY_KEY` and `ARG_NEWEST_FIRST`, and `Active.shownNewestFirst` makes a command to
   the current direction a no-op.** Right. This is what makes both orderings of a PLAY and a FLIP land on the shown
   direction.
2. **`setFlip`'s new order is the entry's listed keys** (a superset). Right: `flippedQueue` keeps only the stored
   queue's keys, so a room's queue (Storytel books not on the phone) is never widened.
3. **The VM toggles under a mutex, reading the row.** Right: two quick taps are two flips, and the service applies
   both commands in order.
4. **A gone entry's row is deleted only when every named entry was read.** Right, and consistent with the app's prune
   rule (`entryIds` includes the unreadable entries).
5. **`SpotifyTracker.reorder` sets `known` to the queue's end.** Right: no queue add between a flip and the next
   play, whose `played` resets it.
6. **The fake's selftest models the triangle filter.** Right, and confirmed on the real path (Runs table).
7. **The `playerNumber`/Storytel cases are in `OrderFlipTest`, not `UiModelTest`.** Fine.

The design QA's final check is in: M6 (the reserve, with its test), and L12 and N7 (stated in the notes' art
section).

## Owed to device checks

- The emulator round §6q, all of it (port: L3), on the fake Sonos room (the first real use of `SonosController.reorder`)
  and the fake Spotify playlist (after M1's fix: no replay after a flip).
- On the Jelly Star:
  - the row pictures' decode and scroll with ~60 thumbs;
  - TalkBack's "Snu rekkefølgen, nyeste først" and its selected state;
  - the font scale 1.3 line;
  - the real Spotify SDK through a flip (the coalesced play, the leftover add surfacing);
  - a real Sonos room's first `reorder`.
- The server with a real long feed: the fill over days under the reserve, and L2's first check of a new big Spotify
  list.
