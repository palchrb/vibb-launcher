# 39 - Pictures in the episode list, and a kid-side order flip (proposal)

Asked 2026-10-19. Builds on design 27 (episode pictures, the carousel rule) and design 21/21b (the entry's `order`,
the listing's `keep_end`). One step, `music/` only: no server change, no contract change, no launcher change.

## 0. Goal

The user's words (2026-10-19): "lurer jeg på om vi kunne gjort oversikten over køen finere og bedre om vi også kunne
vise minibilder av album art der? ... Bare interresant om det faktisk er forskjellig album art per spor da, ikke om det
er et album med kun 1 album art", and "på den kølista ... så kunne man jo latt barnet selv velge å snu rekkefølgen? en
egen liten knapp oppe til høyre feks?" The mockup (three artboards, 320x569 dp) was approved: "ja det blir bra!".

- **A** - an RSS podcast whose episodes have their own pictures: a 40 dp picture (10 dp corners) in place of the
  number circle; the playing row on `Palette.selected` with a 2 dp peach ring around its picture; title, sub-line and
  the download circle unchanged. The header's trailing 44 dp slot holds a round button (card background) with an
  up/down two-arrow glyph. The centred line reads "61 av 212 lastet ned · eldste først".
- **B** - the same list flipped: the button filled peach, glyph in `Palette.bg`; "… · nyeste først"; the list newest
  first; the now-playing bar unchanged.
- **C** - an entry whose episodes share the series cover (an NRK podcast): number circles exactly as today; the order
  button is still there.

## 1. Decisions (given by the user/coordinator; the architect's calls are marked)

1. **Pictures only where they say something**: design 27's carousel rule, reused - more than one item and at least
   two different pictures among them, the entry's cover counting as one. In such an entry a row without its own
   picture shows the series cover; an entry with one picture keeps the numbers. **A stated deviation from vibb**,
   whose episode list shows own art only in the highlighted row (design 27 §0) - the user's decision.
2. **The picture is the file the app already keeps** (`covers/<hash>`, 512 px), decoded at row size. Rows whose
   picture is not on the phone ask for it when they come on screen, in the want set's `later` list (the audio's
   network gate: never on mobile data when downloads are off); meanwhile the row shows the series cover. Bounded:
   at most `LIST_ART_MAX` (60) pictures beyond the rest of the want set, for one entry at a time (§2.2).
3. **The flip is per entry per phone**, in the app's Room database, never synced or reported. It flips the list and
   the play direction (a tapped row continues in the shown direction; previous/next follow it), and the numbers
   follow the shown order. The keep set and the download plan do **not** follow it: the parent's order decides
   what is kept. *Architect's call:* **a change of the parent's effective order resets the flip** (§3.1).
4. **The button** is the header's trailing slot, icon only, content description "Snu rekkefølgen" / "Reverse the
   order", filled peach when flipped; the line under the header gains the order words. No settings screen, no
   long-press.
5. **Sources** (architect's call): Spotify list mode gets the pictures (every track carries its own) and the flip;
   a Spotify context has no list - neither. A Storytel series gets the pictures (a book's cover is its own `artHash`,
   26b §5.5) and the flip; "Del N av M" stays the series' reading order. Own files: the same rule (they rarely have
   item art, so mostly numbers) and the flip, except while shuffled.
6. **No server change.** The listing's `art` already exists; `music_listing.json`, `music_state.json`, the bridge
   and `bridge_contract.json` are untouched. One consequence, accepted: the server fetches pictures only for the
   first 200 items in the **parent's** play order (`ART_WINDOW`, design 27 §1.4), so a flipped 1000-item feed shows
   the series cover at its far end - the same as today's carousel there.
7. **Look**: calm, `Palette` only, the mockup's sizes; the new glyph is one more stroked path in `ui/Theme.kt`'s
   `Icons` (own GPL code, no asset).

## 2. Pictures in rows

### 2.1 The rule (`logic/EpisodeArt.kt`, pure)

- `rowPictures(items: List<PlayItem>, cover: String?): Boolean = showsCarousel(items, cover)` - a named alias, so the
  carousel and the list can never disagree on the same items. Over the listing's hashes (not the files on the
  phone), so a row never flips between a number and a picture as pictures land. `items` = the rows shown (§4).
- Never for a Spotify context (no list of its own); the shuffled list (own files) follows the rule like any other.

### 2.2 Fetching what the list shows (the want-set extension)

- **The window**: `listArtWindow(shown: List<PlayItem>, first: Int, last: Int, margin: Int = LIST_ART_MARGIN): List<String?>`
  - the art of the visible rows `first..last` plus `LIST_ART_MARGIN` (10) each side, clamped.
- **The held list** (as `recentArt`): `listArtAfter(before: ListArt?, entryId: Long, window: List<String?>): ListArt`
  - `ListArt(entryId, hashes)`: the window first, then what was there, deduplicated, own hashes only (`isArtHash`, not
  a library cover), at most `LIST_ART_MAX` (60). Another entry's list starts empty: one entry at a time. Kept in
  `filesDir/music/list_art.txt` (first line the entry id, then hashes; atomic write like `recent_art`), so the prune
  run by the sync worker sees it and a reopened list doesn't fetch again.
- **`wantedArt(..., list: List<String> = emptyList())`**: `list` goes into `later` right after the `now` hashes
  (the kid is looking at them) and before the keep sets', within `WANT_ART_MAX` (600), and into `keep`. The 60 are
  "beyond the want set": what is already wanted for another reason doesn't count against them.
- **Eviction**: the existing prune (`coversToDelete`, `Store.pruneCovers`) - a hash that has left the held list (pushed
  out past 60, or another entry's list opened) and is in no other part of the want set is deleted at the next prune.
  A 1000-item feed therefore holds at most 60 extra pictures (~3 MB), never all.
- **Sending**: `ListScreen` reports its visible range (`snapshotFlow` on `LazyListState.layoutInfo`, settled 1 s)
  only when `rowPictures` is true; `ListArtWants.note(context, entryId, window)` updates the held list off main,
  computes `ArtWants.compute` and sends both lists through `LiveBridge`. `LiveBridge.wantArt` today sends only when
  `now` changed; it gains `force: Boolean` for this caller, sent when the new `later` has a hash the last call didn't
  (both lists always go - QA L4). The launcher's side is unchanged: `later` is fetched with the audio's gate.
- **Offline / gated**: nothing is requested; the row shows the series cover (`Cover`'s `fallback`), and the picture
  swaps in when it lands (`Store.coversVersion`, as every slot does today).

### 2.3 Decoding

`Cover` gains `decodeDp: Dp? = null`; with it the file is decoded at `decodeDp x density` (40 dp = 60 px on the
Jelly Star) into a second LRU (`thumbs`, 2 MiB, key `"$hash@$px"`) instead of the 16 MiB player cache (a 336 px
decode per row would evict the carousel's). The media session's small copies (`cacheDir/art`) are not made for rows.

## 3. The order flip

### 3.1 Storage and lifetime

- Room 5 (auto migration 4 -> 5, a new table only): `order_flips(entryKey TEXT PRIMARY KEY, parentNewestFirst INTEGER)`
  - `FlipRow`, beside `ShuffleRow` (the same kind of per-entry, per-phone flag). Keyed by `entry.key` like shuffle and
  bookmarks. `allowBackup="false"` already: it never leaves the phone.
- `parentNewestFirst` is `newestFirst(entry)` when the kid flipped. **Pure**: `shownNewestFirst(parentNewestFirst:
  Boolean, flip: Boolean?): Boolean` - `flip == parentNewestFirst` -> the opposite, else (no row, or a stale one) the
  parent's. **Why reset**: the parent changing "Nyeste først" to "Eldste først" is a fresh decision; keeping the
  flip would silently turn it back. A change that leaves the effective direction (`auto` -> the same explicit
  direction) keeps the flip - nothing for the kid to notice. A stale row is ignored at once and deleted at the
  next sync (`pruneFlips`); a gone entry's row likewise.
- `Snapshot` gains `flips: Map<String, Boolean>`, fed by `db.flipsFlow()` in `Store`'s combine.

### 3.2 One seam: `Snapshot.order`

- `Snapshot.order(entry)` becomes `if (shownNewestFirst(newestFirst(entry), flips[entry.key])) visible.reversed()
  else visible` (via `playOrder`'s rule). Every play path already reads `snap.order`: the cover tap
  (`coverTapPlan`), a pick (`pickPlan`), the restore and the adopt, the Sonos suspension, the Home resume, `listOrder`,
  the carousel cover's hint and progress (`entryUi`), the Spotify `ahead` pictures (`artWant`: the tracks a tap plays
  next). So a tapped row continues in the shown direction and previous/next follow it, with no change in `Queue.kt`.
- **Unchanged on purpose**: `playOrder(entry, …)` itself, used by `keepSet` (Offline.kt: the kept window and the
  bookmark's "next"), `storytelKeepSet`/`downloadPlan`, `artWant`'s keep sets and `NewItems` (which item of a new
  entry is news). The server's `keep_end` window is the parent's too: a flipped capped NRK serie shows the same 100
  items, the other way round.
- An unstarted resume entry, or a resume-off one, flipped: a cover tap starts at the top of the shown list (e.g. the
  oldest episode) - what the kid asked for.

### 3.3 Flipping while it plays

The list is always the loaded entry's (`Screen.LIST` from the player). The button sends `Commands.FLIP_ORDER`
(`THEN_FLIP_ORDER` via `RESTORE` when the service isn't live), exactly as `toggleShuffle`: the service writes the
row, rebuilds `a.order`, and keeps the current item playing with `rotatedTo(newOrder, current)` after it through
`renderer.reorder(index, …)`, then `saveNow()` and `publish()`. Phone and Sonos already implement `reorder`
(shuffle). `SpotifyRenderer.reorder` is a logged no-op today; it becomes one `PUT /me/player/play` with the new
window from the current track at its position (the existing step path, so nothing restarts from 0:00; a short hitch
at most). Hidden while shuffled (the queue isn't an order) and below two rows (nothing to flip).

### 3.4 Numbers, the player, Home, the lock

- The rows' numbers (`listRows`, i + 1) and the player's "N av M" (`playerNumber`/`playerNumberOf`, through
  `countedKeys` -> `listOrder` -> `snap.order`) follow the shown order: flipped to oldest first, the oldest episode
  is "1". Storytel's "Del N av M" stays the reading order (`countedOrder`); untouched.
- The now-playing bar shows the current item only - unchanged. The bridge's `now_playing` (entry, item, position,
  duration, playing, art) carries no order: the launcher's Home card and lock tile are unchanged, and their
  previous/next go through the media session, so they follow the flipped queue for free.

## 4. UI (`ui/ListScreens.kt`, `ui/MusicRoot.kt`, `ui/Theme.kt`, strings)

- `ListScreen(entry, rows, summary, order: OrderUi?, pictures: Boolean, onFlip, onVisible, …)`; `MusicRoot` passes
  `pictures = rowPictures(listOrder(s, n), n.entry.cover)` and `order = orderUi(n)` (null for a Spotify context,
  shuffled, or fewer than two rows).
- **Header**: `trailing = { RoundButton(Icons.flipOrder, stringResource(R.string.flip_order), onFlip, 44.dp, 20.dp,
  if (flipped) Palette.peach else Palette.card, if (flipped) Palette.bg else Palette.fg, Modifier.semantics {
  selected = flipped }) }` - the shuffle button's idiom (PlayerScreen). Without `order`, the default 44 dp spacer keeps
  the title centred.
- **The line**: the summary and the order words joined by " · " (`R.string.summary_with_order`, "%1$s · %2$s"); for
  Spotify list mode (no summary) the order words alone, centred, same style (12 sp, 800, `Palette.sub`).
- **The words**, pure `orderWords(entry, shownNewestFirst): OrderWords` (by kind; Q1):

  | Entry | listing order shown | reversed |
  |---|---|---|
  | NRK, RSS, Spotify show (dated) | "eldste først" / "oldest first" | "nyeste først" / "newest first" |
  | Storytel series | "fra bok 1" / "from book 1" | "fra siste bok" / "from the last book" |
  | Own files, Spotify album/playlist | "i rekkefølge" / "in order" | "baklengs" / "reversed" |

  For a dated entry the words name the shown direction; for the others they compare it with the listing's order.
  Strings in `res/values/strings.xml` and `res/values-nb/strings.xml`, as every string in the app.
- **A picture row**: `Cover(row.item.artHash ?: entry.cover, category, 10.dp, 0.dp, Modifier.size(40.dp), fallback =
  entry.cover, decodeDp = 40.dp)` in place of the 30 dp circle; the playing row adds `.border(2.dp, Palette.peach,
  RoundedCornerShape(10.dp))`. A heard row keeps its sub-line "Hørt · 12:34" (the ✓ lived in the circle). Row height
  stays `min 56 dp` (40 + 2 x 8 padding fits). Decorative: no content description.
- `Icons.flipOrder`: two stroked arrows (up on the left, down on the right), 24-unit box, 2 px stroke, round caps.

## 5. Tests (lean, JVM; the app has no Compose test harness)

- `EpisodeArtTest`: `rowPictures` (one item; all the cover; one own + cover -> true; all one own hash -> false;
  nulls); `listArtWindow` clamps at both ends; `listArtAfter` caps at 60, newest window first, another entry resets;
  `wantedArt` with `list`: after `now`, before kept, the 60 not counting what is already wanted, in `keep`;
  `coversToDelete` drops a hash pushed out of the held list.
- `QueueTest` (or a new `OrderFlipTest`): `shownNewestFirst` (no row; fresh flip both ways; stale row after the
  parent's change; `auto` -> same explicit direction keeps it); `Snapshot.order` flipped while `keepSet` is not
  (one test over a 5-item entry with cache 2 and a bookmark); `orderWords` per kind.
- `UiModelTest`: `playerNumber` counts in the flipped order; Storytel's stays the reading order.
- `FitTest`: unchanged (the header slot already holds 44 dp).

## 6. Emulator checks (`docs/testing/emulator.md` new §6q)

`fake-audio.py` serves silent audio and a debug-feed listing with `"art": null` - no pictures. The step adds
`--feed N` to it: `GET /feed.xml` (an RSS of N items, each with its own `itunes:image`, every third pointing at the
channel picture) and `GET /img/<n>.png` (a 512 px solid-colour PNG per item, stdlib `zlib`/`struct`; the server's art
pass decodes PNG - `music_art.rs`'s tests use PNGs), with a `--selftest` case; `music.yml` already runs its selftest.
Added as a `lan` RSS entry on the server (the art pass fills `art`), at 320x569 dp, nb and en:

1. `--feed 80`: the list shows pictures (the channel-picture rows show the series cover), the playing row ringed;
   a fling to the bottom: logcat `wantArt` carries at most 60 extra hashes; covers dir grows by at most that many.
2. A one-picture entry (the default `fake-audio.py` listing, or an NRK podcast): numbers as today, the button there.
3. Flip on the RSS entry while episode 5 plays: playback goes on, the list and the line read "nyeste først", next
   goes up the shown list, "N av M" counts from the new top; leave and reopen the list, restart the app: still
   flipped. Tap a row: it continues in the shown direction. The same on a fake Sonos room (§6h) and a fake Spotify
   playlist (§6l, "i rekkefølge"/"baklengs", no restart from 0:00).
4. Change the entry's order in the parent's PWA (newest -> oldest): after the sync the flip is gone, the words follow.
5. Downloads on Wi-Fi only, mobile data on, Wi-Fi off (or airplane mode): open the list and scroll: series covers,
   no picture request in the launcher's log; Wi-Fi back: the pictures swap in without a scroll jump.
6. The keep set: the downloaded episodes are the same before and after a flip (`adb shell ls` of the cache dir).

## 7. Open questions for the user

- **Q1 - the words for lists without dates**: "eldste først / nyeste først" for podcasts, but for a Storytel series
  "fra bok 1 / fra siste bok" and for own files and Spotify albums/playlists "i rekkefølge / baklengs" - ok, or
  would you rather hide the order words there and keep only the button's look?
- **Q2 - offline after a flip**: the phone keeps the bookmarked episode and the *parent's* next one. Flipped, the
  kid's "next" is the other neighbour, which may not be downloaded - offline the player skips to the next one that
  is. Keep it so (your rule: the flip changes nothing in what is kept), or keep both neighbours of the bookmark for a
  flipped entry (one more episode on the phone)? Recommended: keep it so.

## 8. Steps

One step, `music/` (+ `scripts/fake-audio.py`, `docs/testing/emulator.md` §6q): §2 (rule, window, held list,
`wantedArt`, `LiveBridge.force`, `Cover.decodeDp`), §3 (Room 5 `order_flips` with its exported schema
`app/schemas/.../5.json`, `Snapshot.flips`, `shownNewestFirst`, `FLIP_ORDER`, `SpotifyRenderer.reorder`), §4 (UI,
`Icons.flipOrder`, strings nb/en), §5's tests, §6's script flag and runbook; `music/CLAUDE.md`'s design 27 paragraph
(the list's pictures, the held list) and a design 39 line (the flip's one seam, what it doesn't touch); PLAN.md.
Build: `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` in `music/`.
