# 39 - Pictures in the episode list, and a kid-side order flip (proposal)

Asked 2026-10-19. Builds on design 27 (episode pictures, the carousel rule) and design 21/21b (the entry's `order`,
the listing's `keep_end`). One step, `server/` (the art pass, §2.4) and `music/` (+ `scripts/fake-audio.py`) in one
branch: no API, contract or launcher change. Revised after the design QA (`docs/design/qa-39-design.md`) and its
revision check; the Revision section at the end lists each finding.

## 0. Goal

The user's words (2026-10-19): "lurer jeg på om vi kunne gjort oversikten over køen finere og bedre om vi også kunne
vise minibilder av album art der? ... Bare interresant om det faktisk er forskjellig album art per spor da, ikke om det
er et album med kun 1 album art", and "på den kølista ... så kunne man jo latt barnet selv velge å snu rekkefølgen? en
egen liten knapp oppe til høyre feks?" The mockup (three artboards, 320x569 dp) was approved: "ja det blir bra!".

**A** - an RSS podcast with own pictures: a 40 dp picture (10 dp corners) in place of the number circle, the playing
row on `Palette.selected` with a 2 dp peach ring, title/sub-line/download circle unchanged; the header's trailing 44 dp
slot a round card-coloured button with an up/down two-arrow glyph; the line "61 av 212 lastet ned · eldste først".
**B** - flipped: the button peach, glyph `Palette.bg`, "… · nyeste først", newest first, the bar unchanged. **C** - an
entry sharing the series cover (an NRK podcast): number circles as today, the button still there.

## 1. Decisions (given by the user/coordinator; the architect's calls are marked)

1. **Pictures only where they say something**: design 27's carousel rule, reused - more than one item and at least
   two different pictures among them, the entry's cover counting as one. An entry with one picture keeps the numbers.
   **A stated deviation from vibb**, whose episode list shows own art only in the highlighted row (design 27 §0) -
   the user's decision. In a pictured entry a row with its own picture shows no number (the mockup); a row **without**
   its own picture yet (`artHash` null: the server hasn't fetched it, or the item has none) shows the series cover with its
   number over it (§4; coordinator, after QA M3).
2. **The picture is the file the app keeps** (`covers/<hash>`), decoded at row size; a missing one is asked for in
   `later` when its row shows (the downloads' network gate), the series cover meanwhile; at most 60 held (§2.2).
3. **The flip is per entry per phone**, in the app's Room database, never synced or reported. It flips the list and
   the play direction (a tapped row continues in the shown direction; previous/next follow it), and the numbers
   follow the shown order. The keep set and the download plan do **not** follow it (Q2, answered: today's rule).
   *Architect's call:* a change of the parent's effective order resets the flip (§3.1).
4. **The button**: the header's trailing slot, icon only, "Snu rekkefølgen", peach when flipped; the line under the
   header gains the order words per kind (Q1, answered: keep them). No settings screen, no long-press.
5. **Sources** (architect's call): Spotify list mode gets the pictures (every track carries its own) and the flip;
   a Spotify context has no list - neither. A Storytel series gets both; its "Del N av M" stays the reading order.
   Own files: the same rule (mostly numbers: little item art) and the flip, except while shuffled.
6. **Every item gets its picture on the server** (the user, 2026-10-19: "ikke bare hent for de 200 første takk, hent
   for alle ..." and "om jeg ikke har valgt nedlasting, må den uansett hente ordentlig cover ved avspilling vel, helst
   i forkant"). Design 27's `ART_WINDOW` (the first 200 `ok` items in play order) goes, for every entry whatever its
   `cache`: the art pass walks all `ok` items in play order under the unchanged budgets (§2.4). No API or contract
   change: `music_listing.json`'s `art`, `music_state.json`, the bridge and `bridge_contract.json` are untouched.
   Until a long feed has filled (days, §2.4), its unfetched rows show the numbered series cover (decision 1) - the
   top of a flipped long feed fills last, since the pass works from the parent's near end.
7. **Look**: calm, `Palette` only; the glyph is one more stroked path in `Icons` (own GPL code, no asset).

## 2. Pictures in rows

### 2.1 The rule (`logic/EpisodeArt.kt`, pure)

`rowPictures(items: List<PlayItem>, cover: String?): Boolean = showsCarousel(items, cover)` - a named alias, so the
carousel and the list can't disagree. Over the listing's hashes (not the files on the phone): a row never flips
between styles as pictures land. `items` = the rows shown. Never for a Spotify context.

### 2.2 Fetching what the list shows

- **The window**: `listArtWindow(shown, first, last, margin = LIST_ART_MARGIN /*10*/): List<String?>` - the art of
  the visible rows plus 10 each side, clamped.
- **The held list** (as `recentArt`): `listArtAfter(before: ListArt?, entryId, window): ListArt` - the window first,
  then what was there, deduplicated, own hashes only (`isArtHash`, not a library cover), **capped at 60 whatever else
  wants them**; another entry's list starts empty. Kept in `filesDir/music/list_art.txt` (first line the entry id;
  atomic write), so the sync's prune sees it and a reopened list doesn't fetch again.
- **`ArtWants.compute` reads `list_art.txt` itself** and passes it to `artWant` -> `wantedArt(..., list)`. So every
  sender - the player 2 s after an item change, the sync's end, the list - carries the list; no `later` ever goes
  without it (which would drop list pictures in flight, design 27 QA L4).
- **`wantedArt`**: the wire order is `now`, then `list`, then the keep sets, Nytt, Spotify ahead, within
  `WANT_ART_MAX` (600). `keep` (the prune's universe) is the 600 computed **without** the list, **plus** the list: the
  list never pushes kept art out of `keep`, so the prune never deletes a kept picture because of it (QA L2).
- **Eviction**: the existing prune (`coversToDelete`) - a hash out of the held list and in no other part of the want
  set goes at the next prune. A 1000-item feed holds at most 60 extra pictures (~3 MB).
- **Sending**: `ListScreen` reports the visible range (`snapshotFlow` on `layoutInfo`, settled 1 s) only when
  `rowPictures` is true. `ListArtWants.note(context, entryId, window)`, off main: updates the held list; if the
  window holds no hash the phone lacks, stops (no `ArtWants.compute` per scroll); else computes and **posts**
  `LiveBridge.wantArt(now, later, force = true)` to the main thread (LiveBridge is main-thread only).
- **The send rule, pure** (`logic/EpisodeArt.kt`): `wantArtSend(now, later, sentNow, sentLater, force): Boolean` -
  not forced: today's (`now` non-empty and not `sentNow`); forced: `later` has a hash `sentLater` didn't, **whatever
  `now` is** (an empty `now` is fine: the launcher's `wantArtLists` takes it). LiveBridge keeps `sentLater` too.
  This is the usual case: a downloaded episode plays, its neighbours' art came with their audio, `now` is empty.
- **Offline / gated**: nothing fetched; the series cover shows; the picture swaps in when it lands (`coversVersion`).

### 2.3 Decoding

`Cover(decodeDp: Dp? = null)`: with it, `inSampleSize` (the largest power of two keeping >= `decodeDp x density`: 8
for 60 px, 4 for 120 px; not `inScaled`, which decodes all 512 px first) into a second LRU (`thumbs`, 2 MiB, key
`"$hash@$px"`), off main; the fallback decodes the same way; its own slot (`CoverSlot`/`coverShown` keyed on hash and
px). No session small copies (`cacheDir/art`) are made for rows.

### 2.4 The server: the art pass walks every item (`server/src/music_sweep.rs`)

- **The change**: in `art_pass` the `.take(ART_WINDOW)` (~line 3552) goes; the candidates are every `art_candidate`
  in play order (`effective_order`: the parent's near end first, so what the kid reaches first fills first), this
  check's new items first, then the older ones while the backlog is on. `ART_WINDOW` (line 120) is **removed**, not
  left unused (a dead constant invites a reader to think it still caps); the doc comments at lines 27, 3236 and 3448
  drop "the first [ART_WINDOW]". Nothing else changes: `ART_PER_CHECK` (20), `ART_PER_CHECK_SPOTIFY` (100),
  `ART_PER_DAY` (400, server-wide), the backlog rule (`art_backlog` off after five `same`, back monthly), `art_due`'s
  retries, the `same`/`own` judgement. Storytel was windowed too and is now unwindowed (a series past 200 books is
  rare, so no visible change); a Spotify list likewise, at its 100 a check.
- **Why the cap is safe to drop**: the budgets, not the window, bound the rate - at most 20 GETs a check (100 for
  Spotify) and 400 a day in all. The window was design 27's cost cap for a Pi; the user's server isn't one. Storage:
  ~50 KB a picture, so a 1000-item feed with own art is ~50 MB on the server, once for all phones; the phone holds
  only its want set (§2.2), unchanged.
- **Fill time**: a 1000-item RSS feed with own art needs ~1000 GETs (fewer where items share a URL). At the entry's
  interval (RSS: the sweep setting, default 6 h: 4 checks, 80 GETs a day) about 12 days; at 1 h about 3 days (24 x 20
  = 480, capped by the 400 a day shared with every entry). Check now helps (once per entry per 15 min). Steady state:
  one GET per new episode with a new URL, as today. During a big backfill the day budget can run out and other
  entries' new pictures wait for the next day - true of today's 200-backfill too.
- **Changing nothing back**: an item never loses its picture to the pass for being late in the order - only a
  reference change (`rejudge`), a source that drops the URL, or the item leaving the listing (the prune after
  `finish`) touch existing art. No migration, nothing stored.
- **The entry page**: "212 episodes · 61 with episode art" already counts what the server has; it simply grows. No
  text names 200.
- **The phone already fetches ahead of play** (the user's "helst i forkant"): `wantedArt`'s `now` list is the
  playing item and two each side (`nowWindow`, `NOW_REACH` 2), sent 2 s after every item change and at each sync's
  end; the launcher fetches `now` on **any** network, roaming too (`artToFetch`: `later` alone waits for unmetered
  or downloads on mobile data). So the next episode's picture is on the phone while the current one plays. The only
  gaps: a picture the server hasn't fetched yet, and the very first item of a fresh start (fetched ~2 s in, swapped
  in place by `Renderer.replaceItem`) - the series cover meanwhile, as today.

## 3. The order flip

### 3.1 Storage and lifetime

- Room 5 (auto migration 4 -> 5, a new table only): `order_flips(entryKey TEXT PRIMARY KEY, parentNewestFirst
  INTEGER)` - `FlipRow`, beside `ShuffleRow`. Keyed by `entry.key`. `allowBackup="false"`: it never leaves the phone.
- **Written by the UI side** (`Store.setFlip`, on the writer), not by the service: the button always works, also
  when there is nothing to restore (QA N2). The service, when live, only reorders its queue (§3.3).
- **The stored queue flips with it** (QA M5): restore, `RESUME`, Home's card and the bar's play go through
  `storedPlan`, which keeps the saved key order. So `Store.setFlip`, in the same transaction as the row, rewrites the
  saved `NowPlayingRow` when it is this entry's and not shuffled: keys = `flippedQueue(newOrder, storedKeys,
  current)`, index 0, position kept. When the service is live it saves its own reordered queue afterwards anyway.
- `parentNewestFirst` = `newestFirst(entry)` at the tap. **Pure**: `shownNewestFirst(parentNewestFirst: Boolean,
  flip: Boolean?): Boolean` - `flip == parentNewestFirst` -> the opposite, else (no row, or stale) the parent's.
- **The reset**: when the parent's effective direction changes, the row is stale - ignored at once, deleted at the
  next sync (`pruneFlips`, which also drops gone entries' rows). Why: the parent's change is a fresh decision. What
  the kid sees: the kid had flipped to the opposite of the old parent order, which is the new one, so **the list
  doesn't move; only the button goes unfilled**. The same for an `auto` NRK serie that turns newest first past 100
  episodes (`effective_order`): a kid who had flipped it to newest first goes on seeing newest first. A change that
  keeps the effective direction (`auto` -> the same explicit one) keeps the flip.
- `Snapshot` gains `flips: Map<String, Boolean>`, fed by `db.flipsFlow()` in `Store`'s combine.

### 3.2 One seam: `Snapshot.order`

- `Snapshot.order(entry)` reverses `visible(entry)` by `shownNewestFirst(newestFirst(entry), flips[entry.key])`.
  Every play path reads it: the cover tap, a pick, restore, adopt, the Sonos suspension, the Home resume,
  `listOrder`, the cover's hint and progress (`entryUi`), the Spotify `ahead` pictures. A tapped row continues in the
  shown direction, previous/next follow it; no change in `Queue.kt`'s plans.
- **Unchanged on purpose**: `playOrder(entry, …)`, used by `keepSet` (the kept window and the bookmark's "next"),
  `storytelKeepSet`/`downloadPlan`, `artWant`'s keep sets, `NewItems`. The server's `keep_end` window is the parent's:
  a flipped capped NRK serie shows the same 100 items, the other way round. Offline after a flip the kid's "next" may
  not be downloaded; the player skips to the next downloaded one ("Ikke lastet ned"), as today.
- Unstarted or resume-off, flipped: a cover tap starts at the top of the shown list.

### 3.3 Flipping while it plays

The list is always the loaded entry's. The UI sends `Commands.FLIP_ORDER` when the service is live, **after
`setFlip` has returned** (QA L9), and the command carries the entry key and the new shown direction, which the
service uses rather than a snapshot that might predate the row (a command for another entry is dropped). Not live:
nothing more (the stored queue was flipped by `setFlip`). The service: `a.order` becomes a `var`, set to the new
order; the new queue is **`flippedQueue(newOrder, queueKeys, current)` = `rotatedTo(newOrder.filter { it in
queueKeys }, current)`** - the current queue's own keys in the new direction, so a room's speaker-playable filter
(`PlayBodies.speakerPlayable`) holds and nothing the speaker can't play comes back (QA M1); then
`renderer.reorder(index, …)`, `saveNow()`, `publish()`. Phone and Sonos implement `reorder` (Sonos' has never run:
own files never play on a room, so §6q 3 is its first use). Hidden while shuffled and below two rows.

**Spotify list mode** (`SpotifyRenderer.reorder`, today a logged no-op; QA M2): the renderer replaces its
`tracks`/`mediaItems` (index 0 = current) at once, then:
- **playing**: a fresh start at the tracker's measured place through the existing start path (supersede, the
  `SPOTIFY_STEP_COALESCE_MS` (300 ms) coalescing - quick repeated taps are one `PUT /me/player/play`, of the last
  direction). Nothing restarts from 0:00. The one-ahead add already in Spotify's user queue is handled by list
  mode's existing add tracking (QA L10; `skippedAdd` is context mode's): the tracker's `adds` are kept across the
  reorder, and when the old add surfaces it is `isAdd` + `notOurs(goOn = true)` - a pause, then a new window from the
  current item at its place in the new order: one more play call and a short hitch, accepted.
- **paused or not started**: no call at all. The renderer marks its place stale, so the next Play is a fresh start at
  the place in the new order - never an SDK resume inside Spotify's old window.

### 3.4 Numbers, the cover, Home, the lock

- The rows' numbers (`listRows`, i + 1) and the player's "N av M" (`playerNumber` -> `countedKeys` -> `listOrder`)
  follow the shown order: flipped to oldest first, the oldest episode is "1". Storytel's "Del N av M" stays the
  reading order; in a flipped series a numbered row ("1" = the last book) disagrees with it - rows there are nearly
  always pictures, so it rarely shows.
- **The carousel cover's hint and progress** (`hint`/`overallProgress` over `snap.order`) follow too: a podcast whose
  bookmark is the newest episode at 0:00, flipped, turns "fortsetter der du slapp" into "episode 212 av 212" with a
  full progress line. Consistent with the numbers; accepted, looked at in §6q 3.
- The bar shows the current item only; the bridge's `now_playing` carries no order: Home's card and the lock tile
  step through the media session and follow the flipped queue unchanged.

## 4. UI (`ui/ListScreens.kt`, `ui/MusicRoot.kt`, `ui/Theme.kt`, strings)

- `ListScreen(entry, rows, summary, order: OrderUi?, pictures: Boolean, onFlip, onVisible, …)`; `MusicRoot` passes
  `pictures = rowPictures(listOrder(s, n), n.entry.cover)` and `order` (null for a Spotify context, shuffled, or
  fewer than two rows).
- **Header**: `RoundButton(Icons.flipOrder, stringResource(R.string.flip_order), onFlip, 44.dp, 20.dp, peach/card,
  bg/fg, Modifier.semantics { selected = flipped; stateDescription = <the order words> })` - the shuffle idiom; TalkBack
  says "Snu rekkefølgen, nyeste først". Without `order`, the default 44 dp spacer keeps the title centred.
- **After a flip** the list scrolls to the playing row (`scrollToItem(indexOfFirst { it.playing })`, the top when
  nothing plays), in one `LaunchedEffect` keyed on the shown direction (the keyed `LazyColumn` would otherwise hold
  its first visible item and the playing row could leave the screen).
- **The line**: summary and order words joined by " · " (`R.string.summary_with_order`); Spotify list mode (no
  summary): the words alone. 12 sp, 800, `Palette.sub`. **When it doesn't fit, the order words drop** (the button's
  look and state carry it): a pure `Fit.kt` rule, `FitTest` with the longest pair ("40 of 40 downloaded · from the last
  book", en and nb) at 320 dp, font scale 1.0 and 1.3.
- **The words**, pure `orderWords(entry, shownNewestFirst)` (by kind; for the dated kinds they name the shown
  direction, for the others they compare it with the listing's order); in `values/` and `values-nb/`:

  | Entry | listing order shown | reversed |
  |---|---|---|
  | NRK, RSS, Spotify show (dated) | "eldste først" / "oldest first" | "nyeste først" / "newest first" |
  | Storytel series | "fra bok 1" / "from book 1" | "fra siste bok" / "from the last book" |
  | Own files, Spotify album/playlist | "i rekkefølge" / "in order" | "baklengs" / "reversed" |

- **A picture row**: `Cover(row.item.artHash ?: entry.cover, category, 10.dp, 0.dp, Modifier.size(40.dp), fallback =
  entry.cover, decodeDp = 40.dp)` in place of the 30 dp circle; the playing row adds a 2 dp peach border (10 dp
  corners). **`artHash` null**: the same `Cover` with `content = { Box(fill, Palette.scrim) { Line(number or ✓, 14,
  800, Palette.fg) } }` - the count stays visible on the series cover; decided by the listing's hash, so it never
  changes as files land. A row with its own picture shows no number; heard keeps "Hørt · 12:34" in the sub-line.
  Row height stays min 56 dp. The picture is decorative (no content description).
- `Icons.flipOrder`: two stroked arrows (up left, down right), 24-unit box, 2 px stroke, round caps.

## 5. Tests (lean, JVM; the app has no Compose test harness)

- `EpisodeArtTest`: `rowPictures` (one item; all the cover; one own + cover -> true; one own hash for all -> false);
  `listArtWindow` clamps; `listArtAfter` caps at 60, window first, another entry resets; `wantedArt` with `list`:
  after `now`, before kept, and over 600 kept pictures `keep` still holds all 600 kept plus the list (L2);
  `wantArtSend`: forced with an empty `now` and a new `later` hash -> true (**a downloaded episode plays, the list
  scrolls, the list's pictures are sent**), forced with nothing new -> false, not forced -> today's rule.
- `QueueTest`: `flippedQueue` where the queue lacks some of the order's keys (a room's filtered queue) - they stay out.
- A new `OrderFlipTest`: `shownNewestFirst` (no row; fresh flip both ways; stale after the parent's change; `auto` ->
  same explicit direction keeps it); `Snapshot.order` flipped while `keepSet` is not; `orderWords` per kind; the
  stored queue after a flip with nothing live (M5: `storedPlan` then restores the new direction; a shuffled row and
  another entry's row are left alone).
- `UiModelTest`: `playerNumber` flipped; Storytel's stays the reading order. `FitTest`: the sub-line rule (§4).
- `SpotifyRendererTest` (fake ports): a paused flip makes no call, the next Play is a fresh start in the new order;
  two flips within 300 ms while playing are one play call. `SpotifyTrackerTest`: flip, then the old add surfaces -
  paused, a new window, the new order goes on (L10).
- Server, `tests/music_art.rs`: `the_window_is_200_in_play_order_…` becomes "every `ok` item in play order, gone items
  get nothing new" (210 items, all get art at an unlimited per-check knob); a new test: 250 items at the default 20 a
  check get candidates past item 200 over successive checks, the near end first, whatever the entry's `cache`.

## 6. Emulator checks (`docs/testing/emulator.md`, the next free letter at implementation - §6q after 37/38)

**The fake** (`fake-audio.py --feed N`): `GET /feed.xml`, an RSS of N items, each with its own `itunes:image`, every
third pointing at the channel picture (judged `same` by URL); `GET /img/<n>.png`, 512 px PNGs (stdlib `zlib`/
`struct`) that the server's art pass judges `own`. The server prints the stored JPEG's grey (`to_luma8`, square crop,
<= 512 px, q85) shrunk to 9x8 with a triangle filter (`photos::print_image`), so fine stripes alias and vertical-only
patterns repeat per row (QA L11). Each picture is therefore a **9x8 grid of blocks** (one per hashed cell), two grey
levels at least 64 apart, seeded per n so both directions vary; the channel picture is another seed. The
`--selftest` computes the print from the block levels and asserts 12-52 bits set (`low_detail` is <= 8 or >= 56),
every compared neighbour pair at least 32 levels apart (so the filter and JPEG can't flip a bit), and more than 12
bits between any two pictures, the channel picture included (`SAME_PICTURE_BITS` is 5). **`--hls K`**: item K's
enclosure is `/audio/<key>.m3u8` (the server lists it `hls: true`); the selftest asserts the URL; nothing has to
play behind it. **Addresses**: image URLs from the feed request's `Host` header (the address the server used),
enclosures from `--advertise` (10.0.2.2, the emulator's view); the loopback feed is judged `lan`. **Budget**:
`ART_PER_CHECK` = 20 GETs a check and the channel-URL rows cost none: `--feed 20` fills in one check (13 GETs);
`--feed 250` in ~9 (~167 GETs; ~13 if every item had its own URL), all 250 - the pass is unwindowed (§2.4) and the
entry's offline choice doesn't matter for pictures. Check now: once per entry per 15 min, so ~2.5 h. At 320x569 dp,
nb and en:

1. `--feed 20`, after one check: pictures in the list; the channel-picture rows show the series cover with their
   number; the playing row ringed. Play a downloaded episode, scroll: logcat shows a `wantArt` with an empty `now`
   and the list's hashes; at most 60 extra pictures in the covers dir after a fling.
2. `--feed 250`, the entry's offline choice "none": after the first checks the newest rows have pictures, the rest
   numbered series covers; flipped to oldest first, the top rows are numbered covers until the later checks reach
   them; after ~9 checks every row's picture is there (in every part, every third row - the channel picture, `same` -
   stays a numbered cover, N6). The entry page's "N with episode art" grows to ~167.
3. A one-picture entry (the plain `fake-audio.py` listing, or an NRK podcast): numbers as today, the button there.
4. Flip the RSS entry while episode 5 plays: playback goes on, "nyeste først", the playing row stays in view; next
   goes up the shown list; "N av M" and the carousel cover's hint and progress count from the new top. Leave, reopen,
   restart the app: still flipped. Tap a row: continues in the shown direction. The same on a fake Sonos room (§6h:
   with `--hls K`, item K is out of the room's queue before and after the flip) and a fake Spotify playlist (§6l: "i
   rekkefølge"/"baklengs"; no restart from 0:00; flip while paused: nothing plays, Play continues the current track
   and then the shown direction; flip twice fast: one play call in the fake's log). TalkBack reads the state. Flip
   with the app swiped away, then play from the bar: next goes the shown way (M5).
5. Change the entry's order in the parent's PWA to the direction the kid had flipped to: after the sync the list
   doesn't move, the button goes unfilled. Where an `auto` NRK serie with just under 100 episodes is at hand, flip it
   to newest first and let it pass 100: the same (logic covered by `OrderFlipTest`'s stale case otherwise).
6. Downloads on Wi-Fi only, mobile data on, Wi-Fi off: open the list and scroll - series covers, no picture request in
   the launcher's log; Wi-Fi back: the pictures swap in without a scroll jump.
7. The keep set: the downloaded episodes are the same before and after a flip.

## 7. Open questions - none: Q1, Q2 and M3 answered by the coordinator as the user's lean (overrulable)

## 8. Steps

One step, one branch, `server/` and `music/` (+ `scripts/fake-audio.py`, the emulator runbook). Server: §2.4 (the
window removed, the doc comments, the two tests; `server/CLAUDE.md` ~line 1067's "first `ART_WINDOW` (200)"; a version bump
with the next release). Music: §2 (rule, window, held list read by
`ArtWants.compute`, `wantedArt`'s `keep`, `wantArtSend` + `LiveBridge.sentLater`, `Cover.decodeDp` with
`inSampleSize`), §3 (Room 5 `order_flips` with `app/schemas/.../5.json`, `Snapshot.flips`, `shownNewestFirst`,
`Store.setFlip`, `FLIP_ORDER`, `flippedQueue`, `SpotifyRenderer.reorder`), §4 (UI, scroll after a flip, the fit rule,
`Icons.flipOrder`, strings nb/en), §5's tests, §6's fake and runbook; `music/CLAUDE.md` (design 27 paragraph: the
list's pictures and held list; a design 39 line: the flip's one seam and what it doesn't touch); PLAN.md. Build in
`music/`: `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`; in `server/`:
`cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`.

## Revision (after `docs/design/qa-39-design.md`, 2026-10-19)

- H1: pure `wantArtSend` (forced: a new `later` hash, whatever `now`), posted to main, `ArtWants.compute` reads
  `list_art.txt`; the "downloaded episode, list scrolls, pictures sent" test (§2.2, §5).
- M1: `flippedQueue` from the current queue's keys, `a.order` a var, a filtered-queue test, a Sonos HLS check (§3.3).
- M2: Spotify: no call while paused (next Play a fresh start), 300 ms coalescing; the add: see L10 (§3.3).
- M3: taken (coordinator): a row without its picture yet shows the series cover with its number under
  `Palette.scrim`; §6q 2 (§1, §4). The 200-item window itself is gone (addendum below).
- M4: scroll to the playing row (or the top) after a flip (§4, §6q 4).
- L1: `--feed` patterns judged `own` with a dHash selftest, image URLs from `Host`, `ART_PER_CHECK` counted (§6).
- L2: `keep` = the 600 without the list plus the list; a test (§2.2, §5).
- L3: the reset described (the list doesn't move, the button unfills; the `auto` NRK serie past 100); §6q 5 (§3.1).
- L4: `inSampleSize`, the fallback in `thumbs`, the thumbs' own slot (§2.3).
- L5: the order words drop when the line doesn't fit; `FitTest` (§4, §5).
- L6: `stateDescription` = the order words (§4).
- L7: no compute when the window lacks nothing; the held list caps at 60 whatever else wants them (§2.2).
- L8: the cover's hint and progress after a flip stated and checked (§3.4, §6q 4).
- N1: the runbook letter is taken at implementation (§6). N2: the row is written by the UI side (§3.1).
- N3: a flipped Storytel series' row numbers vs "Del N" stated (§3.4). N4: the tests added (§5).
- L1 (and L11 of the revision check): see L11 below.

Addendum and revision check (2026-10-19; the user's two widening requests, `qa-39-design.md` "Revision check (04382327)"):

- Server art for every item: `ART_WINDOW` removed, the pass walks every `ok` item in play order under the unchanged
  budgets, whatever `cache`; fill time, why it's safe, the phone's ahead-of-play `now` fetch stated; two server tests;
  §1.6, §2.4, §6q 2 (user, via the coordinator).
- M5: `Store.setFlip` rewrites the saved now-playing queue with `flippedQueue` in the same transaction; a test (§3.1).
- L9: `FLIP_ORDER` sent after `setFlip` returns, carrying the entry key and the new direction (§3.3).
- L10: the leftover add goes through list mode's `isAdd`/`notOurs`, not `skippedAdd`; `adds` kept; a tracker test (§3.3).
- L11: the fake draws 9x8 block grids; the selftest models the print (12-52 bits, 32-level neighbours, > 12 bits
  apart, the channel picture included); `--hls K` with its selftest (§6).
- N5: the budget recounted (`--feed 250`: ~9 checks for all 250). N6: every third row stays a numbered cover (§6q 2).
