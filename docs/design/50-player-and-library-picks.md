# 50 - Skip buttons, "Til slutten", the carousel on the last played tile, dimmed tiles, favourites (proposal)

Asked 2026-10-20 (the user's picks from a list of suggestions: "Jeg liker 1, 4 ... Ang 5 ... husker hvilken tile som
ble spilt ved siste avslutning ... Ang 6 ... grey out de tilesene man vet man ikke får spilt av ... Ang 7 ... en
favoritt-kategori"; the rewind-after-pause idea declined). Music app, plus the lock tile's bridge field. Builds on
`PlayerScreen`'s control row (`Fit.kt`), the sleep sheet (`PlayerScreen.kt:232`), `PlaybackService`'s sleep timer,
`MusicViewModel.carouselIndex`, `EntryUi`/`CoverBadges`, `logic/NetGate.kt` (`Playability`), the launcher's lock tile
(design 42/r6), Room (design 39's per-entry tables as the pattern). Ships in one round with design 49.

## 1. Decisions

1. **Skip buttons for what resumes.** Which entries get them is design 49 §1.1's `controlSlots(entry)`: `SKIP` for
   `Kind.PODCAST`, `Kind.AUDIOBOOK` and a resuming `Kind.OWN` entry; the others get shuffle/repeat. The two side slots
   are "15 s tilbake" (left) and "30 s fram" (right) - Material Symbols `replay`/`forward_media` glyphs
   (`scripts/material-symbols.sh`) with our own "15"/"30" as text over them, a fixed size (not scaled sp) in the 44 dp
   side button with its 20 dp glyph. The step is a `seekTo` on the renderer (the phone's player; a Sonos room's settled
   seek; Spotify's SDK seek), clamped to the item; across an item boundary it stays inside the item. A context item
   has no listing duration (`contextItem`, `logic/Spotify.kt:102-103`): the clamp uses the live state's duration.
   Previous/Next stay vibb's (`previousAction`: an item over 30 min never restarts). **On the launcher's lock tile**
   the bridge's `setNowPlaying` carries the decision, not the kind: an appended field `controls: "skip"|"music"`,
   the key in `bridge_contract.json`'s `now_playing` (`entry,item,position_ms,duration_ms,playing,art` + `controls`;
   the value names are ours: `skip`/`music`) and the music app's send in one commit (no AIDL change: `setNowPlaying`
   takes a Bundle, `BridgeConfig.kt` holds the constants), so the lock tile needs no kind logic and the same two buttons
   replace previous/next when it says `skip` - the lock tile's use of the key is the later launcher step.
2. **"Til slutten" in the sleep sheet.** A fourth choice, a full-width row under the 2x2 minutes (15/30/45/Av): the
   timer ends at the end of the current item (track, episode, book part). It lives in `PlaybackService`
   (`setSleep`/`sleepTick`) as its own state, `sleepAtEnd: Boolean` (and a separate command argument, not the
   minutes; `PlayerHub.State` gets the field). The service does not time a fade from the position: the renderer pauses
   AT the item's end by its own means - the phone's player with `setPauseAtEndOfMediaItems`, a room's `advance()`
   holding at the next item at 0 as its `!mayPlay` branch does, Spotify through the tracker's end with nothing re-sent
   (in list mode Spotify advances inside the window by itself, so the fade must have brought it to a pause before the
   end; the pause never depends on the fade's timing) - and the fade is the renderer's own (`fadeMs`: 10 s on the phone and Spotify,
   3 s on a room), started by the renderer when its remaining time crosses it, recomputed after a seek. It beats
   repeat-one (design 49). The choice survives a manual step like the minute timer (finish what plays - the user's call); it is cleared by
   a new queue, by the pause it causes, and by a kid's own pause (`PauseCause.USER`, as the minute timer is cleared at
   `PlaybackService.kt:1278`). For an item with no duration (live, or a context before its first state)
   the choice is hidden. The bar shows "Sov · til slutten" and the item's remaining time; it must fit at 320 dp
   (`FlexBar` never cuts Sov, `FitTest`).
3. **The carousel opens on the last played tile.** At start `carouselIndex` is the index of the stored now-playing
   entry (`NowPlayingRow`'s entry, shown paused as today) in the shown order; 0 when none or filtered out. It is set
   once by a one-shot `LaunchedEffect` in `MusicRoot` (or a `combine` in the view model) that runs when both the
   snapshot and the now-row are non-null (the view model's `snapshot`/`nowRow` are `stateIn` flows with no init;
   `MusicRoot.kt:214` passes `startIndex = vm.carouselIndex`, the pager reads `initialPage` once keyed on `jumps`), sets
   `carouselIndex` and bumps `carouselJumps`, guarded by a `started` flag that `onIndex` and `backToLibrary` set - so
   never after the kid turned the carousel or came back from the player
   (`backToLibrary` wins).
4. **A tile the phone can't play right now is dimmed.** `EntryUi` gains `unplayableNow`, computed in the view model
   from the current `NetCaps` read when the library screen (re)appears and on each snapshot; there is no network
   callback in the screens, so a small `ConnectivityManager` watcher in `PlayerHub`/the view model bumps a
   `netVersion`. Cases: NRK/RSS entries with no copy under a refused gate (`playability(hasCopy, hasUrl, caps,
   streamMobileData)`), and Spotify under `spotifyPlayability`'s refusal. Storytel and own entries without a copy are
   already hidden on the phone (`Snapshot.kt:117-125`). On a Sonos room nothing is dimmed (the phone's gate does not
   apply; Spotify can't play there and already says so). The cover draws at 60 % (multiplying the pager's neighbour
   alpha) and hides design 46's play arrow. The tile's quiet line is the existing Spotify line (`spotifyTileState`);
   for NRK/RSS a new tile line "Trenger Wi-Fi" in the same place (not the toast) - the line takes the hint's place, as
   `CarouselTitle` shows a state or the hint (`LibraryScreen.kt:318`) - and when both apply the account line wins over
   "Trenger Wi-Fi". An account line of its own ("Spør en voksen", `ASK_ADULT`) also dims the tile: the kid can't play
   it either. One line only, so `libraryFit`'s two measured lines and the cover don't move. The tap
   still answers as today (never a silent block); nothing is hidden, no reorder.
5. **Favoritter.** A heart per track/episode in the list screen's row (a small trailing heart, peach when set) and in
   the player under the title; stored in a new Room table `favourites(entryKey, itemKey)` (keyed by the entry KEY and
   item key like every per-entry state; Room 6 holds both this and design 49's `repeat`, one migration, since the two
   ship together); pruned only on a parsed listing that no longer names the item (a hidden or withdrawn item kept with
   its copy keeps its heart); no heart on a context's synthetic item. A category tile "Favoritter" first after "Alle" in
   the category row (the row scrolls past four) with a sentinel category id (-1) in `CategoryTiles` that
   `tapCategory`/`effectiveCategory`/`backToLibrary` understand, showing the entries that hold a favourite; a tap on
   it with one entry opens that entry (a proposal - drop if unwanted); a cover tap under Favoritter plays the entry as usual, not a favourites queue.
   Phone-only, never synced or reported. The "Bare favoritter" filter row is dropped (beyond PLAN). Nothing in the
   parent's PWA.
6. **Not taken**: the "Fortsett" strip (3 covers it), the offline check badge (4 covers it the user's way), the
   rewind after a long pause (declined), playback speed, themes.

## 2. Tests (required)

`FitTest`: the row with the skip pair at 320 dp, the sleep bar "Sov · til slutten" at 320 dp. `TransportTest` (new or
in QueueTest): the ±15/30 s clamp inside the item, a context item clamped by the live duration. `NowPlayingTest`/
service tests: `sleepAtEnd` pauses at the item's end per renderer with the renderer's own fade, survives a step,
cleared by a new queue and by its pause. `MusicViewModelTest`/`LibraryViewTest`: the start index from the stored
entry, 0 without one or when filtered out, never after the kid moved the carousel. `UiModelTest`: `unplayableNow` per
source under offline/cell/Wi-Fi, the `netVersion` bump, no dimming on a room, the dimmed cover, the arrow hidden and
the single line (account line first). Room: the 5 -> 6 migration with `favourites` and `repeat`; `FavouritesTest`:
set/unset keyed by entry key, the Favoritter tile's entries and sentinel id, the prune under the parsed-listing rule,
no heart on a context item. `SourceScanTest` and `LockGlanceGuardTest`-style guards unchanged. Bridge: `controls` in
`setNowPlaying`, `BridgeTest`/`MusicPlanTest` on both sides and `bridge_contract.json`.

## 3. Device checks

Emulator: a podcast shows ±15/30 and they work on the phone and (§6h) on a fake Sonos room; "Til slutten" stops
after the episode with the fade, also after a manual step; the app opens on the last played tile; on cell with
streaming off the Spotify and NRK tiles dim and a tap says the line; a heart set in a list shows the entry under
Favoritter.

## Revision (2026-10-20, after qa-music-round-design.md)

- 50-1: §1.2 "Til slutten" in `PlaybackService` as `sleepAtEnd`, the renderer pauses at the item's end with its own fade; survives a step (the user's simpler rule), cleared by a new queue or its pause.
- 50-2: §1.4 `unplayableNow` in the view model with a `netVersion` watcher; the cases, the room exception, one tile line, the arrow hidden.
- 50-3: §1.5 favourites keyed by entry key + item key, parsed-listing prune, no heart on the context item, sentinel id -1.
- 50-4: §1.5 the "Bare favoritter" filter row dropped; a cover tap under Favoritter plays the entry.
- 50-5: §1.3 the hook is where the snapshot is first applied; `carouselJumps`; `backToLibrary` wins.
- 50-6: §1.5 Room 6 holds both because 49 and 50 ship together; §1.1 the bridge carries `controls: "skip"|"music"`.
- 50-7: §1.2 the full-width row under the 2x2; §1.1 fixed glyph size and the live duration for a context item.
- Interactions: §1.1 refers to 49's `controlSlots`; §1.4 the account line wins over "Trenger Wi-Fi".

## Implementation status (2026-10-20, branch `music-r-b`)

Built on `main` at `d53edc81` in parallel with design 49 (branch `music-r-a`). §1.1-§1.4 first, §1.5 last on 49's Room 6.

- **§1.1 the skip pair - built.** `PlayerScreen`'s side slots follow `controlSlots(entry)` (49 §1.1; until 49's commit
  is on this branch a stub with the same signature and rule, `logic/ControlSlotsStub.kt`): `SKIP` draws `SkipButton`
  with Material Symbols `replay`/`forward_media` (new `PLAYER_GLYPHS` in `scripts/material-symbols.sh`,
  `res/drawable/player_glyph_*.xml`, the "Converted from" header) at 20 dp in the 44 dp slot, "15"/"30" at a fixed
  7 dp (`SKIP_NUMBER_DP.dp.toSp()`). The target is `skipWithin(pos, ±delta, duration)` (`logic/Transport.kt`) from the
  live position read at the tap, sent through the existing `seekTo` (live: the controller's seek - the phone, a room's
  settled seek, the SDK; restored: `RESTORE` + `THEN_SEEK`), so no new command. The bridge: `NowPlayingKeys.CONTROLS`
  (`controls`: `skip`/`music`, `nowPlayingControls`) in every `setNowPlaying`; `bridge_contract.json` (both copies)
  gets `controls` appended to `now_playing` and a `now_playing_controls` list; the launcher only spells the key
  (`MusicNowPlaying.CONTROLS`, `MusicPlanTest`), its lock tile's use is the later step.
  - Deviation: "clamped to the item" is 1 s short of the end (`SKIP_END_MARGIN_MS`) - a seek to the very end would end
    the item and move on, which §1.1 rules out; already within that second, forward stays put. With no duration
    known (a context before its first state, live) forward does nothing and back still goes back.
- **§1.2 "Til slutten" - built.** The sheet's full-width row under the 2x2 (`SleepOption`), shown only for an item
  with a duration (`sleepAtEndOffered`; kept while it is on); a second tap turns it off. `Commands.SLEEP` with
  `ARG_AT_END`, the service's `sleepAtEnd` (`PlayerHub.State.sleepAtEnd`), cleared per `sleepAtEndAfter` (a step keeps
  it; a new queue - `start()` -, its pause, a kid's `PauseCause.USER` pause, the queue's end, a minute choice end it). The
  renderers: `Renderer.pauseAtItemEnd(done)` - the phone `pauseAtEndOfMediaItems` and its `END_OF_MEDIA_ITEM` pause
  (emitted as `Paused(OTHER)`, then done), the gain from the time left over 10 s; a room `SonosController.holdAtEnd`
  in `advance()` (the `!mayPlay` branch's hold at the next item at 0) and the room's 3 s ramp started when the time
  left crosses `fadeMs` (cancelled by a seek back or a step; whichever of the ramp's Pause and the hold comes first
  ends it); the web player pauses itself `AT_END_SPOTIFY_PAUSE_MS` (400 ms) before the end with its 10 s gain, and a
  belt pauses at 0 a track Spotify advanced to anyway. A renderer switch moves it to the new renderer (`attach`).
  - Deviation: the bar says "Sov · til slutten" (measured at 320 dp, `FitTest`); the item's remaining time is the
    seek bar's own two times - a third time in the bar would cut the output's name to its glyph at 320 dp.
  - Repeat-one (49) on the phone: `pauseAtEndOfMediaItems` pauses at the item's end in every repeat mode; on a room the
    hold is checked before 49's wrap in `advance()` (to be kept so at the merge).
- **§1.3 the carousel's start - built.** `CarouselStart` (`logic/LibraryView.kt`) + `MusicViewModel.startOn`, from a
  `LaunchedEffect` in `MusicRoot` once both the snapshot and the stored row are non-null; `turned` (a real turn),
  `pickCategory` and `backToLibrary` mark it moved. The pager reads `initialPage` again through `carouselJumps`.
- **§1.4 dimmed tiles - built.** `EntryUi.unplayableNow`/`needsWifi` from `NetNow(netCaps, streamMobileData, onRoom)`
  (`unplayableNow` in `ui/UiModel.kt` reuses `itemPlayable`/`spotifyPlayability`); `MusicViewModel.netVersion` moves
  on a default-network callback and when the library is started again. Cases as written; `ASK_ADULT` dims too.
- **§1.5 favourites - built** on 49's Room 6 (`FavouriteRow`, its DAO; cherry-picked from `music-r-a` 6c17f55c,
  unchanged). `Snapshot.favourites` (`Store.snapshots` combines `favouritesFlow`), `MusicViewModel.setFavourite`
  (IO; refused for a context's synthetic item), `HeartButton` in the list rows (40 dp target, 18 dp heart) and beside
  the player's number line (32 dp); the prune in `Sync` (`applyListing`: what the parsed listing no longer names; own
  files against the parsed library; a gone entry's hearts when every entry was read - design 39's flip rule). The
  "Favoritter" tile (`CategoryTiles`, the `favorite` glyph) shows while an entry holds a heart; `FAVOURITES_CATEGORY`
  = -1 in `shownEntries`/`effectiveCategory`/`backToLibrary`/`startOn`. "Bare favoritter" is not built (dropped, §1.5).
  - "A tap on it with one entry opens that entry" is built as: the tile is selected and that entry plays as a cover
    tap would (the player opens) - the list screen only ever shows the playing entry, so "open" can't mean the list.
    Drop `favouritesTapOpens` if unwanted.
- **Tests**: `TransportTest` (`d50` clamp, context duration), `NowPlayingTest` (`d50` gain/look/offered, the clearing
  rule, the renderers' means by source), `SpotifyRendererTest` (`d50` fade + pause before the end with nothing sent;
  the belt), `SonosControllerTest` (`d50` hold at the next item at 0, once), `LibraryViewTest` (`d50` start index),
  `UiModelTest` (`d50` per source/network, room, the single line, ASK_ADULT; `d46` arrow), `FitTest` (`d50` the skip
  pair's slots and number, "Sov · til slutten" at 320 dp), `FavouritesTest` (set/unset by entry key, the tile and its
  sentinel, the prune rule, no heart on a context item), `Room6Test` (49's, the migration), `BridgeTest`/`MusicPlanTest` (`controls`), and the
  guards that pinned the old lines (`GuardsTest`, `SourceScanTest`) moved to the new code.
- **Device checks owed** (§3): the emulator list as written.
