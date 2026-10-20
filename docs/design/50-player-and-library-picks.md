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
