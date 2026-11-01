# Music notes: episode pictures, list order, shuffle and repeat, the player and library picks

Moved from music/CLAUDE.md on 2026-11-01.

## Rules that are easy to break (continued): episode pictures, list order, shuffle and repeat, picks

- Episode pictures (design 27): an item's own art is `items.artHash` (Room 2; its auto migration empties every
  listing version so the next sync re-applies them) and `PlayItem.artHash`; every `Cover` gets the entry's cover as
  `fallback`. `logic/EpisodeArt.kt` has the want set (`wantedArt`; `now` is streaming's - the user's rule of 2026-10-19, the
  launcher fetches it where streaming is allowed - built by `nowArt`: the now window ±5 (`NOW_REACH`), the next
  `NOW_AHEAD` (50) items the queue reaches, then the playing Spotify list-mode entry's next `SPOTIFY_ART_AHEAD` (50)
  tracks from where a cover tap starts it - a Spotify entry keeps nothing, and the server fetches all of its pictures,
  a check after another; every other Spotify entry's look-ahead is `later`'s (qa-art-asap-code M3); at most
  `WANT_ART_NOW_MAX` 500. `later` (fetched by the launcher on Wi-Fi only): the keep sets' art, Nytt, and **on an unmetered network every
  item's picture of every ticked entry in play order** (`all`, `allLater`; the addendum of 2026-10-19, "alle bilder
  lastes ned ASAP så lenge den er på wifi" - `ArtWants.compute` reads `netCaps`; a sync on a metered network that
  leaves pictures for Wi-Fi enqueues the unique work `music-wifi-art`, a sync with an UNMETERED constraint,
  `wifiSyncWanted`); only what the phone lacks goes on the wire, at most `WANT_ART_MAX` 1000 hashes a call (one oneway
  Binder call, ~136 bytes a hash, ~136 KB) - as pictures arrive the next send names the next ones, so it is no cap on
  the pictures. A picture the launcher passes over three syncs in a row (`artSkipsAfter`/`skippedArt`, `ArtSkips` in
  memory: the sync's send records its page, the next sync's pull counts; a restart tries them again) leaves the `later`
  wire, not `keep` (qa-art-asap-code L6). A picture is ~40-70 KB: a 1000-item feed costs ~50 MB on the phone, kept
  while the entry is ticked - counted in the report's `cache_bytes` (the covers dir is in it; the PWA's "stored"
  line), never in the audio limit's `audio_bytes`, never evicted for audio (L4). Sent as
  `wantArt` at each sync's end (after the listings are applied, so a listing that brings the playing entry new
  `artHash`es sends them at once) and from the player at once on an item change (`wantArtDelay`: a run of skips sends
  once more at the end of `WANT_ART_GAP_MS` 2 s); `sync/ArtPlan.kt`'s `artWant` takes a
  Storytel series' keep set from the download plan's rule, `storytelKeepSet` + `storytelWanted`: the books on the
  phone; a book's cover is its own `artHash` from the server's art pass, design 26b §5.5), the prune (covers dir and the
  small copies: neither a library cover nor in the want set's universe, only on a parsed library; the universe
  `keep` has no cap and holds every picture of every ticked entry on any network, so Wi-Fi's pictures stay on mobile
  data), `artOrCover` (the
  session, `setNowPlaying`) and the carousel's rules: `showsCarousel` (more than one item and at least two different
  pictures on its pages, the entry's cover counting as one), `swipeSkip`, `pagerFollow`, `swipeTarget`. A swipe is a
  `PlayRequest` (`swipeSwitch`) and the `SKIP_TO` command (not in `PLAYS`; via `RESTORE` + `THEN_SKIP_TO` before play).
  `Store.coversVersion` moves when a cover lands or goes: slots retry, the player swaps the current item's artwork
  in place (`Renderer.replaceItem`: on the phone `replaceMediaItem`, the same source so playback goes on; on a Sonos
  room only the session player's item, no SOAP). **The session's artwork is a `content://` URI** (2026-11-01: SystemUI
  read the old `file://` path with its own identity and failed with ENOENT at every state change): `artUri` is the
  small copy (`cache/art/<hash>.jpg`, checked to exist at every build and item change) through the manifest's
  non-exported FileProvider (`${applicationId}.art`, `res/xml/art_paths.xml`: `cache/art/` only), read-granted once per
  picture and process to `SESSION_ART_READERS` (`com.android.systemui`); Media3's notification reads the same URI in
  this process. Not `artworkData`: the bytes would ride on every queue item's metadata through the session's Binder.
  `setNowPlaying` still reads the file (`artFile`). `SourceScanTest` pins it.
- **The episode list's pictures and the kid's order flip** (design 39; **the user's decisions, not vibb's**: vibb's
  list shows own art only in the highlighted row and follows the parent's order only). **Pictures**: `rowPictures` =
  `showsCarousel` over the rows shown (never a Spotify context) - a 40 dp `Cover(..., decodeDp = 40.dp)` per row,
  the playing one ringed; a row whose `artHash` is null shows the series cover plain, no number (r12 §1, `rowBadge`; a list
  without pictures keeps its numbers and ✓) - by the listing's hash, so it never changes as files land. The list judges the rows it shows, the carousel the queue: on a room's
  speaker-playable queue (or a stored queue that lost items) the two may differ (qa-39-code N1). `decodeDp` decodes with `inSampleSize`
  (`thumbSampleSize`) into its own 2 MiB LRU (`thumbs`, `"$hash@$px"`), its own slot; no small session copy. The
  list reports its visible rows (settled 1 s) to `ListArtWants.note`: the held list `filesDir/music/list_art.txt`
  (`listArtAfter`: the window - the visible rows and `LIST_ART_MARGIN` 10 each side - first, own hashes only, at most
  `LIST_ART_MAX` 60, another entry's list starts empty); only when the window lacks a picture is the want set computed
  and posted to main as a forced `LiveBridge.wantArt` (`wantArtSend`: forced = a `later` hash the last send didn't
  have, whatever `now` is; LiveBridge keeps `sentLater`). `ArtWants.compute` reads `list_art.txt` itself, so every
  sender carries the list (`wantedArt`'s `list`: on the wire after `now`, and in `keep`, QA L2). **The flip**: Room 5 `order_flips` (`FlipRow(entryKey,
  parentNewestFirst)`, auto migration 4 -> 5, never synced or reported), written by the UI side
  (`MusicViewModel.flip` -> `Store.setFlip` -> `MusicDao.setFlipTx`, which in the same transaction rewrites the stored
  `NowPlayingRow` when it is this entry's and unshuffled: `flippedNowPlaying` = `flippedQueue` from its current item,
  index 0, the place kept - restore, `RESUME`, Home's card and the bar continue the shown way, QA M5); then, only to a
  live player, `Commands.FLIP_ORDER` with the entry key and the new direction (L9): `PlaybackService.flipOrder` reverses
  `Active.order` (a `var`, with `shownNewestFirst`), the queue becomes `flippedQueue(newOrder, queueKeys, current)` (a
  room's speaker-playable queue stays that, M1) and `renderer.reorder` - the phone's ExoPlayer, the Sonos controller's
  bookkeeping (no SOAP), and Spotify list mode (`SpotifyRenderer.reorder`: tracks replaced at once; playing, a fresh
  start at the current place (`positionMs()`, the measurement carried on - never the last state's raw position,
  qa-39-code M1) coalesced like a step; paused, no call and `windowStale` so the next Play is a fresh
  start, never an SDK resume in Spotify's old window; `SpotifyTracker.reorder` keeps the adds, L10). **The one seam**
  is `Snapshot.order` (`shownNewestFirst(newestFirst(entry), flips[key])`: a row whose parent direction no longer
  matches is stale - ignored, pruned at the next sync with a gone entry's, `flipsToDelete`): every play path, the
  numbers, "N av M", the cover's hint and progress and Spotify's `ahead` follow it; `playOrder` - the keep set, the
  download plan, the art's keep sets, `NewItems`, the server's `keep_end` - stays the parent's. The header's trailing
  slot is the button (`Icons.flipOrder`, `R.string.flip_order`, peach when flipped, `selected` + the order words as
  its state description; hidden for a Spotify context, while shuffled, below two rows); the line under it joins the
  summary and `orderWords` (by kind) with " · ", dropping the words when they don't fit (`orderWordsFit`, measured
  at the font scale). After a flip the list scrolls to the playing row. Tests: `OrderFlipTest`, `EpisodeArtTest`'s
  `d39`, `QueueTest`, `FitTest`, `SpotifyRendererTest`/`SpotifyTrackerTest`'s `d39`, `SonosControllerTest`'s `d39`;
  the emulator round is `docs/testing/emulator.md` §6q.
- **Shuffle and repeat** (design 49; **the user's decisions, not vibb's**: vibb has no repeat and its Spotify shuffle is
  Spotify's own): one predicate, `controlSlots(entry)` (`logic/Queue.kt`, shared with design 50) - music (Spotify's
  `Kind.MUSIC`, own files that don't resume) gets shuffle (left) and repeat (right: off -> all -> one), what resumes
  design 50's skip pair; a Spotify artist context repeat only. Both remembered per entry on the phone (`shuffle`, Room
  6's `repeat` - `Snapshot.repeatOf`; never synced or reported), changed only by `Commands.SHUFFLE`/`REPEAT` (and
  `THEN_*` on a restored queue): `KidPlayer` refuses the Player's set-mode commands. Every queue hands its modes to the
  renderer (`Renderer.setModes`, after each `active = a`). **The phone**: ExoPlayer's repeat mode (`exoRepeatMode`), so
  `clear_state` runs only with repeat off; the move-on past an unplayable item wraps only with repeat all, one lap at
  most - counted across `onError`'s calls from where the run of failures began (`moveOnIndex`'s origin,
  `Active.failRunFrom`, cleared by 15 s of real play or a natural end). **A room**: the controller's `advance()` wraps (all) or plays the item again (one) through
  `advanceIndex`, gated, a deliberate start. **Spotify**: list mode's shuffle is our order (`reorder`), Spotify's off;
  repeat one is Spotify's `repeat=track`, repeat all a gated `NewWindow(0, wrap)` at the list's end, before `Ended`; a
  context uses Spotify's own shuffle and `repeat=context|track`, and its end rules never read a repeating track as the
  end (`repeatHeld`; only Autoplay evidence ends it). Shuffle with resume changes the order only: the resumed item first
  at its place (`coverTapPlan`). The flip stays hidden while shuffled; repeat applies after either.
- **The player and library picks** (design 50 + 46 §1.4; **the user's picks of 2026-10-20, not vibb's**):
  - **The skip pair**: `controlSlots(entry)` (design 49 §1.1, `logic/Queue.kt`) gives what resumes (podcasts, books, a
    resuming own entry) "15 s tilbake" / "15 s fram" (r12 §2; the number is `skipSeconds` of the step) in the control row's side slots (`SkipButton`: Material Symbols
    `replay`/`forward_media` from `scripts/material-symbols.sh`, `player_glyph_*`, the number at a fixed 7 dp, never
    sp); a step is `skipWithin` (`logic/Transport.kt`: clamped inside the item, 1 s short of its end; the live
    duration - a context item has no listed one) through the existing `seekTo` (the phone, a room's settled seek,
    the SDK). Previous/next stay vibb's. The lock tile gets the decision, not the kind: `setNowPlaying`'s appended
    `controls` (`skip`/`music`, `nowPlayingControls`; `bridge_contract.json` `now_playing` + `now_playing_controls`).
  - **"Til slutten"**: the sleep sheet's full-width fourth row (only for an item with a duration,
    `sleepAtEndOffered`); `SLEEP` with `ARG_AT_END`, the service's `sleepAtEnd` (`PlayerHub.State.sleepAtEnd`, the bar
    "Sov · til slutten"). The renderer pauses AT the item's end by its own means (`Renderer.pauseAtItemEnd`): the
    phone `pauseAtEndOfMediaItems` (+ `END_OF_MEDIA_ITEM`), a room `SonosController.holdAtEnd` in `advance()` (the
    next item waits at 0) and its 3 s ramp, the web player pausing itself `AT_END_SPOTIFY_PAUSE_MS` before the end
    (a belt pauses Spotify's own advance at 0); each fades over its own `fadeMs` from the time left (`atEndGain`,
    looked at every second, 250 ms inside the fade, so a seek recomputes it). `sleepAtEndAfter`: it survives a manual
    step (the user's call) and every load that is no new pick (the stored queue's first play, a resumption, an output
    switch: `start`'s `newChoice` is only `playEntry`/`playItem`'s); the kid's new pick, its pause, a kid's pause, the
    queue's end, the minute timer and a step onto a live item end it. While it is on, Spotify's repeat is off.
  - **The carousel opens on the last played tile** (`CarouselStart`, `MusicViewModel.startOn`): once, when the first
    snapshot and the stored now-playing row are in; never after the kid turned it, picked a category or came back from
    the player (`backToLibrary` wins); 0 when the entry isn't shown.
  - **Dimmed tiles**: `EntryUi.unplayableNow` (`unplayableNow`: NRK/RSS with no visible copy under a refused gate,
    Spotify under `spotifyPlayability`'s refusal, and an `ASK_ADULT` account line; never on a room) from `NetNow`, read
    when the library (re)appears, on each snapshot and on the view model's network callback (`netVersion`). The cover
    at `UNPLAYABLE_ALPHA` (60 %, times the neighbour's alpha), no arrow; one line: the account/state line, else "Trenger
    Wi-Fi" (`tile_needs_wifi`), else the hint - `libraryFit`'s two lines never change. The tap answers as before.
  - **Favourites**: Room 6 `favourites(entryKey, itemKey)` (one auto migration with design 49's `repeat`; Room 8 adds
    `heartedAt`, design r12), read into `Snapshot.favourites`/`heartedAt`; a heart (`HeartButton`, Material Symbols
    `favorite`, peach when set) trailing each list row and beside the player's number line, never on a context's
    synthetic item (`canFavourite`). Pruned only when a parsed listing (own files: the parsed library) no longer names
    the item, a gone entry's when every entry was read (`favouritesToPrune`, `favouriteEntriesGone`, `Sync`).
    "Favoritter" is the category chip first after "Alle" while a heart is listed (`Snapshot.favouritesOrder` not
    empty), the sentinel `FAVOURITES_CATEGORY` (-1) that `shownEntries`/`effectiveCategory` (and so `backToLibrary`,
    `startOn`) take with the hearted entries' keys; the chip itself only selects (no category chip starts sound).
    Phone-only: never synced, reported or in the PWA.
  - **One "Favoritter" tile: every heart of every entry in one queue** (design r12 §3, option B, the user's decisions of
    2026-10-24 - replacing r8's option C, whose cover tap under the chip is gone). Under the chip the carousel is one
    tile (`FavouritesCarousel`: the chip's heart in peach on the card ground, never a mosaic; "Favoritter" and
    `favourites_count`, every listed heart; dimmed with "Trenger Wi-Fi" when nothing of the phone's view can play now,
    `favouritesUi`); its tap is `PLAY_FAVOURITES` (in `PLAYS` and `MOVES`; the loaded favourites queue unpauses, else a
    new one from the hearts now). **The queue** (`logic/Mix.kt`, pure, `MixTest`): `mixOrder` - newest heart first
    (`heartedAt`), the hearts from before Room 8 (0) after them in library order (`sort`, `id`), then reading order;
    `mixView` - this renderer's view (`Snapshot.holds`: the phone's `visible`, a room's speaker filter: no own file, no
    Spotify, every Storytel book); fixed at the tap (a heart set or cleared while it plays changes nothing in it),
    shuffled only with its own row (`MIX_KEY` "vibb:favourites" in `shuffle`/`repeat`, never an entry key). **Runs**:
    the service keeps a `MixQueue` beside `active`; `active` is always an ordinary one-entry queue - the consecutive refs
    of the playing item's entry (`segmentAt`), scoped to those keys through r8's `Active.favourites` - on that entry's
    renderer (`startRun` -> `queueFor` -> `start`: the switch between Spotify and a URL item is today's switch between
    entries). A run's natural end (`queueEnded`, never `clear_state` for a run; "Til slutten" at its last item on the
    phone, `sleepAtEndReached`) launches `runEnded` under the switch lock with a token (the same run still loaded):
    `afterRun` (repeat ALL wraps the queue, ONE is the run's own), `mixPlayableFrom` past what can't play ("Ikke lastet
    ned" once), the next run - held paused at its start after "Til slutten" - or the end (`mixEnded`: only the stored
    queue goes). Next/previous (`KidPlayer` launches them under the lock with the screens' hold, `mixLater`; commands
    run them in place), the swipe (`SKIP_TO` by entry key + item key) and the favourites list's pick (`PLAY_ITEM` with
    `ARG_IN_FAVOURITES`) count over the queue: inside the run a seek, outside a new run, the play state kept, from 0 on
    a playing room. A web player account line (`mixDropsSpotify`) drops Spotify for the queue ("Kan ikke spilles nå"
    once). **Places**: positions per (entry key, item key) as always, but a run writes no bookmark (`persistPlace`'s
    `bookmark = a.mix == null`, `saveBookmark`, and the suspension's standalone place when the stored row is a mix) and
    its end clears nothing: "Alle" continues each entry where the kid left it. **Stored**: Room 8's nullable
    `now_playing.mix` (`encodeMix` of the whole queue); the row's other fields are the run's, so Home's card, the bar and
    every reader of an entry's row work; `flippedNowPlaying` leaves a mix row alone; `restore` brings the queue back in
    the renderer's view (`restoredMix`: the row's item at its place, else the next ref's own - a book not on the phone is
    skipped, never waited for; a gone entry's ref found by its item key; an unreadable `mix` is still a favourites
    row, `storedMix` - so no rebuild ever falls back to an entry's queue and moves its bookmark). The service's own pauses
    (a move-on past a run, a run loading paused) go through `selfPause`, never the kid's (the sleep timer stays). **A room**: `toRoom` keeps the room's view of the queue (own files and Spotify out, the
    log line), `comeHome` the phone's again (they are back in), adopt the stored queue around the adopted run; Spotify
    on a room stays out until round 54-a2 (r12 §3.5's hand-over: one share link per track). **The player**: the header
    is the playing item's entry, "Favoritt N av M" over the queue, each carousel page its own entry's picture, the heart
    the playing item's, the side slots `controlSlots` of the playing item's entry (shuffle/repeat on a song acting on the
    whole queue); `NowView.favourites`/`entries`, `PlayerHub.State.favouritesQueue`/`favouritesIndex`/
    `favouritesShuffled`/`favouritesRepeat` (`entryId`/`keys`/`index` stay the run's); "Liste" the queue pictured
    (`favouritesRows`, each sub-line "<entry> · 12:34", lazy keys `pageKey` = entry id + item key). Back from the player
    and the first start with a stored favourites queue land on the chip. `setNowPlaying` and the bridge are unchanged.
  - **A drag down closes** (design r8 §2-§3, `ui/DragDown.kt`, `DragDownTest`): `Modifier.dragDownToClose` on
    `Sheet` (the output and sleep sheets; plus TalkBack's `dismiss`) and on the player's root (= `backToLibrary`).
    Past a quarter of the height (>= 56 dp) or a downward fling > 400 dp/s closes, an upward fling never; else it
    springs back (snaps with animations off). A scrolling list inside scrolls first (nested scroll: a finger's
    leftover at the top moves the sheet, a fling's never). Horizontal drags (the sliders, the seek bar, the
    carousel) stay theirs; the back button, Back and the edge gesture stay.
  - **The play arrow means "tap to play"** (design 46 §1.4): on every tile (`showsPlayMark`), not only a started one;
    never on a dimmed tile. The progress line alone says "started". 46's queue view (§1.1-§1.3) is parked (§0b).
