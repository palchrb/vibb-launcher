# QA 39 (design) - pictures in the episode list, and a kid-side order flip

Reviewer: design QA, 2026-10-19. Branch `design-39` at `7611809d`. I read it against:
- `docs/design/39-music-list-pictures-and-order.md`; the root `CLAUDE.md`; `music/CLAUDE.md` (design 27's paragraph,
  shuffle, the renderers, Sonos); `docs/design/27-music-episode-art.md`; the memory rule that the music app follows
  vibb except where the user decides - here the user decided (the mockup, "ja det blir bra!").

I also read the code it names:
- music: `logic/EpisodeArt.kt` (`showsCarousel`, `wantedArt`, `recentArt`, `coversToDelete`), `sync/ArtPlan.kt`
  (`artWant`, `ArtWants.compute`), `sync/Sync.kt` (the prune and the end-of-sync send), `bridge/Bridge.kt`
  (`LiveBridge.wantArt`, main thread, bound by the service and the activity), `ui/Widgets.kt` (`Cover`, `coverBitmap`,
  the 16 MiB LRU, `loadCover` on `Dispatchers.IO`), `ui/ListScreens.kt` (`Header`'s trailing slot, `ListScreen`,
  `ListRowView`), `ui/MusicRoot.kt` (`Screen.LIST`), `ui/UiModel.kt` (`listOrder`, `playerNumber`, `countedKeys`,
  `nowView`, `entryUi`), `logic/LibraryView.kt` (`listRows`, `countedOrder`, `hint`, `overallProgress`),
  `data/Snapshot.kt`, `logic/PlayOrder.kt`, `logic/Offline.kt` (`keepSet`), `logic/Queue.kt`, `logic/NewItems.kt`,
  `logic/Storytel.kt`, `data/MusicDb.kt` (version 4, `ShuffleRow`, schemas 1-4), `data/Store.kt` (the combine),
  `playback/PlaybackService.kt` (`toggleShuffle`, `activeFor`, `queueFor`, every `snap.order` caller, `sendWantArt`),
  `playback/Renderer.kt`, `LocalRenderer.reorder`, `sonos/SonosRenderer.reorder`, `SonosController.reorder`,
  `spotify/SpotifyRenderer.kt` (`reorder`, `load`, the start path, `playWindow`, the one-ahead add), strings nb/en,
  `ui/Theme.kt` (`Palette`, `Icons`), `ui/FitTest.kt`, `SourceScanTest` (the translation check).
- launcher: `music/MusicPlan.kt` (`wantArtLists`, `artToFetch`: `later` only unmetered or with downloads on mobile data).
- server: `music.rs` (`effective_order`), `music_sweep.rs` (`ART_WINDOW`, `ART_PER_CHECK`, the art pass's order and
  judgement, `series_picture`), `photos.rs` (`print_image`, `low_detail`, `same_tone`).
- `scripts/fake-audio.py`, `docs/testing/emulator.md` (§6f, §6h, §6l; the last section today is §6n).

No code was built or changed.

**Verdict: good shape, approve after a revision of the note.** One pure rule shared with the carousel, a bounded
held list riding the existing want set and prune, one seam (`Snapshot.order`) with the keep set and the download
plan deliberately outside it, no server or contract change - all right, and the claims check out: `showsCarousel` is
exactly "more than one item and two different pictures, the cover counting as one"; a Spotify playlist and a Storytel
series pass it (an album of one cover doesn't); every play path the note lists reads `snap.order`, while `keepSet`,
`storytelKeepSet`, `downloadPlan`, `artWant`'s keep sets and `NewItems` call `playOrder` directly; Room is at 4 with
`shuffle` the model table; the header's trailing slot exists and is the empty 44 dp `Box` on the episode list;
`RoundButton`'s signature and the shuffle idiom match; strings live in `values/` (en) and `values-nb/`. What must
change before implementation is the forced send (H1), the Sonos and Spotify reorders (M1, M2), what a row without its
own picture shows (M3) and where the list scrolls after a flip (M4); and the emulator fake as proposed can't produce
pictures the server keeps (L1).

Counts: 1 High, 4 Medium, 8 Low, 4 Nits.

## High

### H1. The list's `later` is never sent while the now window is on the phone - the usual case

**What.** `LiveBridge.wantArt` returns at once when `now.isEmpty() || now == sentNow`. `now` is
`wantedArt(...).now` - the now window **minus what the phone holds**. While the kid plays a downloaded episode whose
neighbours' pictures came with their audio (the keep set's art), `now` is empty, so a `force` that only skips the
`now == sentNow` test still sends nothing; the list's pictures then arrive only at the next sync's end
(`BridgeSync.wantArt`), minutes to hours later. §2.2 says `wantArt` "gains `force`", which most readers will implement
as that second test. No JVM test listed would see it (LiveBridge isn't unit-tested).

**Fix to the note.** Spell the send rule as a pure function and test it: `wantArtSend(now, later, sentNow, sentLater,
force): Boolean` - not forced: today's rule; forced: `later` has a hash `sentLater` didn't, **whatever `now` is**
(an empty `now` array is fine for the launcher: `wantArtLists` takes it). LiveBridge keeps `sentLater`. Say that
`ListArtWants.note` computes off main and posts the call to the main thread (LiveBridge is main-thread only), and
that `ArtWants.compute` itself reads `list_art.txt` - so the player's send 2 s after an item change and the sync's
send carry the list too, never a `later` without it (which would drop list pictures still queued, QA L4 of 27).

## Medium

### M1. A flip on a Sonos room must keep the speaker's queue speaker-playable

**What.** §3.3 copies `toggleShuffle`, which builds the new queue from `a.order` (every visible item) and maps it back
through `a.order`. On a room `a.items` was filtered by `PlayBodies.speakerPlayable` (`activeFor`: HLS outside an NRK
series, withdrawn items, private addresses of a public entry drop out); the copy puts them back, and the room then
tries to play what it can't. Shuffle never hit this: `canShuffle` is own files without resume, and own files never
play on a room - so `SonosRenderer.reorder`/`SonosController.reorder` have never run in practice; §6q check 3 on the
fake Sonos is their first use. (`Active.order` is also a `val`.)

**Fix.** The new queue is `rotatedTo(newOrder.filter { it.key in currentQueueKeys }, current)` - the current queue's
own keys in the new direction - one pure function (`flippedQueue`) used on every renderer, with a `QueueTest` case
where the queue lacks some of the order's keys. `a.order` becomes a `var` (or the Active is rebuilt).

### M2. Spotify's reorder: a PUT starts playback, and a paused queue would resume in the old order

**What.** `PUT /me/player/play` plays. "One PUT from the current track at its position" is right while playing, but:
(a) paused, the PUT would start the music - and if the renderer only swaps its tracks, the next Play resumes through
the SDK (`play()`: "a paused track of ours on a live device resumes through the SDK") inside Spotify's old window, whose
next track is now the wrong neighbour; (b) the one-ahead add past the window (`nextQueueAdd`) sits in Spotify's user
queue, which a new play call doesn't clear - it plays next, out of the new order; (c) several taps in a row are several
PUTs.

**Fix to §3.3.** Spotify list mode: the renderer replaces `tracks`/`mediaItems` (index 0 = current); **playing** - a
fresh start at the tracker's measured place through the existing start path (supersede, the 300 ms step coalescing,
the leftover-add skip `skippedAdd`), one PUT per settled flip; **paused or not started** - no call, and the next Play
is a fresh start at the place (not an SDK resume). One call per flip is well inside the budget once coalesced.
Emulator §6q 3 adds "flip while paused: nothing plays; Play continues the current track, then the shown direction" and
"flip twice fast: one play call in the fake's log".

### M3. A row without its own picture loses its number - for a flipped big feed, most of the list

**What.** In a picture list a row whose `artHash` is null shows the series cover and no number (decision 1). The
server fills `art` only for the first `ART_WINDOW` (200) items in the **parent's** order, so a 1000-item RSS feed
(newest first) flipped to oldest first shows 800 identical series covers at the **top** - where the flipped list
starts - not at "its far end" as §1.6 says. Offline (or gated) rows whose picture isn't on the phone look the same.

**Fix.** Either (recommended) a row whose `artHash` is null draws its number centred on the series cover under a
`Palette.bg` scrim (the look stays "every row a picture", decided by the listing's hash, so it never flips as files
land), or keep decision 1 and say it: correct §1.6's "far end" to "the top of a flipped long feed", put one sentence in
the ⓘ the parent sees for the entry, and add an emulator check (`--feed 250`, flipped). Ask the user which; the first
costs one `Box`.

### M4. Where the list is after a flip

**What.** The `LazyColumn` is keyed by item key, so after `rows` reverses it holds the first visible item in place -
the playing row (the list opens on it) can leave the screen, and the kid sees other neighbours around the same row.
The note doesn't say what the list shows after the tap.

**Fix.** After a flip the list scrolls to the playing row (as at open: `scrollToItem(indexOfFirst { it.playing })`),
in one `LaunchedEffect` keyed on the shown direction. Emulator §6q 3: "the playing row stays in view".

## Low

### L1. The proposed `--feed` can't give pictures the server keeps, and the server can't fetch them

- **Solid colours are one picture to the art pass.** `print_image` is a 9x8 dHash: a flat image prints 0, and
  `series_picture(print, refs)` has no `low_detail` guard - if the channel picture is flat too (the same fake), every
  item is judged `same` and `art` is null: no pictures at all. Make every PNG structured: e.g. per item a two-colour
  pattern whose split position or stripe count depends on n, the channel picture another pattern; the selftest asserts
  pairwise dHash distance > 5 bits (`SAME_PICTURE_BITS`) and not low-detail, with a 20-line Python dHash (resize by box
  average to 9x8, compare neighbours). Every third item pointing at the channel URL is judged `same` by URL (step 1).
- **Addresses.** `listing()` builds URLs from `--advertise` (10.0.2.2, the emulator's view); the server on the VM
  fetches `itunes:image` and can't reach 10.0.2.2. Build the image URLs from the feed request's `Host` header (what the
  server used), the enclosures from `--advertise`. Check that a loopback feed is judged `lan` by the first check.
- **Budget.** `ART_PER_CHECK` is 20: `--feed 80` needs four checks (Check now four times, within its hourly limit) -
  say it in §6q 1, or start with `--feed 20`.

### L2. The list can push kept art out of a full want set

`list` goes before the keep sets inside `take(WANT_ART_MAX)`. A library already over 600 (an entry with cache "all"
on a long feed is) then drops up to 60 kept pictures from `keep`, the prune deletes them and re-fetches them when the
list moves on. Keep the wire order (now, list, kept, ...) but compute `keep` as the 600 without the list **plus** the
list - the prune never deletes kept art because of the list. One more `EpisodeArtTest` case.

### L3. Say what the reset looks like

The reset is invisible at the moment it happens: the kid had flipped to the opposite of the old parent order, which
is the new parent order - the list doesn't move, only the button goes unfilled. The same holds for the `auto` NRK
series that turns newest first past 100 episodes (`effective_order`): a kid who had flipped it to newest first keeps
seeing newest first. Both are right; §3.1 should say so and §6q 4 should check "the list doesn't move, the button goes
off" (not "the words follow", which suggests a change).

### L4. Decoding

The decode is already off the main thread (`produceState` + `Dispatchers.IO`), so the list never blocks; 2 MiB is
enough (60 px = 14 KB, ~145 thumbs; 120 px at xxhdpi = 58 KB, ~36, still over two screens). But `loadCover`'s
`inScaled` path decodes the whole 512 px file first. For thumbs use `inSampleSize` (the largest power of two keeping
>= the target: 8 for 60 px, 4 for 120 px) - a quarter of the work and memory per row in a fast scroll. Say the
fallback (series cover) is decoded at the same size into `thumbs`, and give the thumbs path its own `coverShown` slot
logic (the existing one keys on the hash only).

### L5. The line under the header needs a fit test

"40 of 40 downloaded · from the last book" (en) is ~286 dp of 288 at 320 dp and font scale 1.0, and over at 1.3; the
`Line` ellipsises, so the order words go first. Add a `FitTest` case as for the player's state line (nb and en, 1.0
and 1.3, the longest pair), and say what gives way (recommend: the order words drop, the button's state carries it).

### L6. TalkBack

`selected` alone reads "Snu rekkefølgen, valgt". Add `stateDescription` = the order words ("nyeste først"), so the
button says which way the list goes.

### L7. Smaller send rules

`ListArtWants.note` runs `ArtWants.compute` (the whole download plan) at every settled scroll: skip it when the window
holds no hash the phone lacks. "The 60 are beyond the want set" contradicts "at most 60 in the held list": say the held
list caps at 60 whatever else wants them.

### L8. The cover's hint and progress after a flip

`hint`/`overallProgress` read `snap.order`, so flipping a podcast whose bookmark is the newest episode at 0:00 turns
"fortsetter der du slapp" into "episode 212 av 212" and a full progress line (`started` = index > 0). Consistent with
the numbers, but say it in §3.4 and look at it in §6q 3.

## Nits

- N1. §6q: designs 37/38 are queued before 39; take the next free letter at implementation.
- N2. If `RESTORE` finds nothing to load, `THEN_FLIP_ORDER` never writes the row: write it from the UI side (the Store)
  and let the service only reorder a live queue - the button then always works.
- N3. A flipped Storytel series' list numbers ("1" = the last book) disagree with the player's "Del N av M" (reading
  order); rows there are mostly pictures, so it rarely shows. One sentence in §3.4.
- N4. Tests to add for the High/Medium paths: `wantArtSend` (H1), `flippedQueue` with a speaker-filtered queue (M1),
  the Spotify renderer's paused flip with no play call (M2, its fake ports), and `keep` over 600 (L2).

## What is right and stays

- `rowPictures = showsCarousel`, over the listing's hashes: the carousel and the list can't disagree and no row
  flickers. A one-cover album and an NRK podcast without episode art keep the numbers (artboard C).
- Pictures follow the **download** switch, not the stream switch: right within this step. `later` is the launcher's
  only gated class (unmetered, or downloads on mobile data; never roaming), so the stream switch would need a third
  class in the bridge contract - a launcher change the note rightly avoids. 60 pictures (~3 MB) per list is a
  download-sized cost; the now window still shows the current item's neighbours on any network.
- The held list's bound (60, one entry at a time, pruned by the existing prune) and the 1 s settle: a fling is one
  report, slow scrolling over a long list asks at most ~30 new hashes a second, and nothing beyond `ART_WINDOW`.
- The keep set, the download plan and Nytt stay in the parent's order; the reset on a change of the parent's direction.
- The bridge needs nothing: `now_playing` carries no order, Home's card and the lock tile step through the session.

## Answers to the open questions

- **Q1** - agree: "eldste først / nyeste først" for NRK, RSS and Spotify shows; "fra bok 1 / fra siste bok" for a
  Storytel series; "i rekkefølge / baklengs" for own files and Spotify albums/playlists - and keep the words (a
  filled button alone doesn't say which way the list goes).
- **Q2** - keep today's keep-set rule (the parent's next): the flip changes nothing in what is kept, and offline the
  player already skips to the next downloaded item with "Ikke lastet ned"; revisit only if the kid meets it.

## Revision check (04382327)

I re-read the revised note at `04382327` (merged into `qa-design-39`) against the same code. I also checked these:
`SpotifyTracker` (`load`, `adds`, `isAdd`, `leftoverAdd`, `notOurs`), `SpotifyRenderer` (`seekTo(index, ms)`'s
`SPOTIFY_STEP_COALESCE_MS`, `skippedAdd`), `Palette.scrim`, `Cover`'s `content`, `storedPlan`/`restoredQueue`, the
server's RSS `hls` flag (`music_sweep.rs`: the enclosure's path ends in `.m3u8`), `PlayBodies.speakerDropReason`,
`photos::print_image`/`low_detail`/`SAME_PICTURE_BITS`, the art pass's budget and backlog (`art_backlog` defaults to
1), and `CHECK_NOW_PER_HOUR` (12).

### The findings, one line each

- H1 resolved: the pure `wantArtSend` sends when forced with a new `later` hash whatever `now` holds, posts on main,
  `ArtWants.compute` reads `list_art.txt`, and a test covers the case that matters.
- M1 resolved: `flippedQueue` builds from the current queue's keys, `a.order` becomes a `var`, there is a
  filtered-queue test, and a Sonos HLS check.
- M2 resolved: no call while paused (the next Play is a fresh start), 300 ms coalescing through the step timer, and a
  renderer test. The leftover-add mechanism is named wrongly (L10).
- M3 resolved: rows with null art show the number over the cover under `Palette.scrim` (fg, 14 sp, 800). `Cover`
  takes `content` and the scrim exists. §1.6 now says where the 200-item window shows.
- M4 resolved: the list scrolls to the playing row after a flip, keyed on the shown direction (a reset that keeps the
  direction doesn't scroll).
- L1 partly resolved: structured patterns, image URLs from `Host`, and the budget is counted. The selftest's claim
  isn't faithful to the server (L11).
- L2 resolved: `keep` = the 600 without the list, plus the list, with a test.
- L3 resolved: the reset is described, and §6q 5 checks it.
- L4 resolved: `inSampleSize`, the fallback in `thumbs`, and their own slot.
- L5 resolved: the order words drop when the line doesn't fit, with a `Fit.kt` rule and a `FitTest`.
- L6 resolved: `stateDescription` = the order words.
- L7 resolved: no compute when the window lacks nothing, and the held list caps at 60.
- L8 resolved: the hint and progress after a flip are stated and checked.
- N1, N3, N4 resolved.
- N2 resolved: the UI side writes the row. This opens M5 and L9.

### The new mechanics

- **`wantArtSend`, `keep`, `flippedQueue`, the scroll, the scrim rows, the fit rule**: sound as written.
- **Spotify**:
  - Paused: no call, the place marked stale. Playing: a fresh start through the step timer, which already coalesces
    taps within 300 ms. Both sound.
  - The leftover add, see L10.
- **The art budget**:
  - `--feed 20`: one check. 7 of the 20 rows point at the channel URL and are judged `same` with no request; 13
    GETs fit in 20.
  - `--feed 250`: only the newest 200 count (`ART_WINDOW`), about 133 GETs, so seven checks, not ten. The backlog is
    on by default, and seven fits the hourly limit of 12. Harmless overcount (N5).
- **The fake's pictures**: L11.

### The architect's flagged assumption: the Sonos HLS case in §6q 4

It works as written.
- **The server**: an RSS item whose enclosure path ends in `.m3u8` is listed with `hls: true`
  (`music_sweep.rs`, the feed merge).
- **The music app**: `speakerDropReason` returns `"hls"` for a non-NRK-series entry. `activeFor` leaves such an item
  out of a room's queue from the start, and logs "1 item(s) a speaker can't play left out (hls)".
- **The address**: the lan entry's `10.0.2.2` enclosures pass on a room, because `publicOnly` is false for `lan`.
  A loopback feed is judged `lan`, since `is_private` covers 127/8.
- **What it proves**: the item is absent before the flip and must still be absent after it. That is M1's
  regression.
- **Owed**: the `--feed` option must be named (e.g. `--hls K`: item K's enclosure is `/audio/<key>.m3u8`), and its
  selftest should assert the URL. The check needs no playable playlist behind it: the room never asks for it, and on
  the phone that item may fail like any bad stream.

### New findings

#### M5. A flip while nothing is live leaves the stored queue in the old direction

**What.** "Not live: nothing more - the next play reads `snap.order`" holds only for a fresh play. The now-playing
bar's place comes back from `NowPlayingRow`. `RESUME`, Home's card, the bar's play and every `restoreThen` go
through `storedPlan(stored, …, snap.order(entry).map { it.key }.toSet())`, which keeps the **stored** key order and
uses the order only as a set. So after a flip with the service down:
- the list shows the new direction, and "N av M" follows it (`listOrder`);
- but the restored queue's next and previous go the old way;
- until the next cover tap or pick.

The kid sees the list flipped while playback isn't.

**Fix.**
- `Store.setFlip` also rewrites the stored row's keys to `flippedQueue(newOrder, storedKeys, current)`, in the same
  transaction. This applies when the row is that entry's and not shuffled; the index becomes 0 and the position is
  kept.
- Alternatively, the restore re-derives an unshuffled queue through `flippedQueue(snap.order, stored, current)`.
- An `OrderFlipTest` case covers it.

#### L9. `FLIP_ORDER` must not race the row's write

`Store.setFlip` runs on the writer. If the UI sends `FLIP_ORDER` at once, the service can rebuild `a.order` from a
snapshot that doesn't have the row yet. The list then flips and the queue doesn't. Fix: send after `setFlip`
returns, or carry the entry key and the new shown direction in the command and let the service use those.

#### L10. Spotify's leftover add goes through list mode's `isAdd` rule, not `skippedAdd`

`skippedAdd` handles context mode's `Event.LeftoverAdd`. In list mode the tracker's `adds` survive `load`. A
surfacing add then takes `isAdd` and `notOurs(goOn = true)`: paused, then a `NewWindow` from the current item at its
place, or from the next one when ours had just run out. That works after a flip, at the cost of one more play call
and a short hitch. Fix: correct the wording in §3.3, keep `adds` across the reorder, and add a `SpotifyTrackerTest`
case: "flip, then the old add surfaces: paused, a new window, the new order goes on".

#### L11. The fake's dHash selftest doesn't model the server

The server prints `to_luma8` of the stored JPEG (square crop, at most 512 px, q85), downscaled to 9x8 with
`FilterType::Triangle`. The `--selftest`'s box average differs from that. Two problems:
- **Fine stripes alias.** "Stripe count depends on n" gives many stripes per 57 px cell, which average to an even
  grey. The neighbour compares are then decided by JPEG noise: unstable prints, or `low_detail`.
- **Vertical-only stripes repeat each row.** Every row of the print is the same, so the set bits come in multiples
  of 8. A single light/dark edge gives exactly 8 bits, which counts as `low_detail` (at most 8 bits).

Only about 238 row patterns with 2-6 bits exist, too few with margin for 250 items.

**Fix.**
- Draw each picture as a 9x8 grid of blocks, one block per hashed cell, with two grey levels at least 64 apart,
  seeded per n, so both directions vary.
- The selftest computes the print from those block levels and asserts:
  - 12-52 bits set;
  - every compared neighbour pair at least 32 levels apart, so the tent filter and JPEG can't flip a bit;
  - every pair of items, and each item against the channel picture, more than 12 bits apart (well over
    `SAME_PICTURE_BITS` 5).
- Colour doesn't matter to `series_picture`, which is grey only.

#### Nits

- N5. The budget: `--feed 250` needs about seven checks, not ten.
- N6. §6q 2: inside the pictured part, every third row (the channel picture, `same`) is also a numbered cover.

### Verdict

Approve. No new High; one new Medium (M5) and three Lows (L9-L11). M5 and L9 are a few lines in §3.1 and §3.3, and
belong in the note or the implementation brief before coding. L10 and L11 can be settled in the step: L11 before
the fake is written, since §6q 1-2 depend on it.
