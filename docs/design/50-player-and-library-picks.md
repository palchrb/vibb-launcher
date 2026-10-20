# 50 - Skip buttons, "Til slutten", the carousel on the last played tile, dimmed tiles, favourites (proposal)

Asked 2026-10-20 (the user's picks from a list of suggestions: "Jeg liker 1, 4 ... Ang 5 ... husker hvilken tile som
ble spilt ved siste avslutning ... Ang 6 ... grey out de tilesene man vet man ikke får spilt av ... Ang 7 ... en
favoritt-kategori"; the rewind-after-pause idea declined). Music app only. Builds on `PlayerScreen`'s control row
(`Fit.kt`), the sleep sheet (`PlayerScreen.kt:232`, `SLEEP_FADE_MS`), `MusicViewModel.carouselIndex`, `EntryUi`/
`CoverBadges`, `logic/NetGate.kt` (`Playability`), the launcher's lock tile (design 42/r6 for the skip buttons there),
Room (design 39's per-entry tables as the pattern).

## 1. Decisions

1. **Skip buttons for what resumes.** For `Kind.PODCAST`, `Kind.AUDIOBOOK` and a resuming `Kind.OWN` entry the
   control row's two side slots are "15 s tilbake" (left) and "30 s fram" (right) - Material Symbols `replay_10`-style
   glyphs drawn with our own "15"/"30" (`scripts/material-symbols.sh` for `replay`/`forward_media`, the number as text
   over it, as the stock players do); `Kind.MUSIC` entries get design 49's shuffle/repeat in those slots. The step is a
   `seekTo` on the renderer (the phone's player; a Sonos room's settled seek; Spotify's SDK seek - all exist), clamped
   to the item; across an item boundary it stays inside the item (never a step to the previous part). Previous/Next
   stay vibb's (`previousAction`: an item over 30 min never restarts). **On the launcher's lock tile** the same two
   buttons replace previous/next for such entries (the launcher reads the entry's kind from `setNowPlaying`'s fields;
   if the kind isn't carried today it is added to the bridge's now-playing payload - an appended field, both apps in one
   commit, `bridge_contract.json`) - the launcher part is a separate step after the music app's.
2. **"Til slutten" in the sleep sheet.** A fourth row after the minute choices: the timer ends at the end of the
   current item (track, episode, book part), fading the last 10 s as the minute timer does (`SLEEP_FADE_MS`), then
   pauses; the bar shows "Sov · til slutten" and the remaining time of the item; the choice is cleared by any new
   queue or a manual step (a step means the kid is awake). One rule in `logic/NowPlaying.kt` (`sleepAtEnd`), tested.
3. **The carousel opens on the last played tile.** At start `carouselIndex` is the index of the stored now-playing
   entry (`NowPlayingRow`'s entry, shown paused as today) in the shown order; 0 when none or filtered out. No new
   state; `MusicViewModel.kt:78` sets it once the snapshot is loaded.
4. **A tile the phone can't play right now is dimmed.** `EntryUi` gains `unplayableNow`: true when no visible item of
   the entry is playable under the current network - `playability(hasCopy, hasUrl, caps, streamMobileData)` is
   `NEEDS_NETWORK`/`GONE` for every item (a Spotify entry: `spotifyPlayability` refuses; a Storytel book: no copy). The
   cover draws at 60 % with its quiet line already in place ("Trenger Wi-Fi", "Ikke lastet ned") under the hint; the
   tap still answers as today (the line, never a silent block), nothing hidden, no reorder. Recomputed on the network
   callback the snapshot already observes.
5. **Favoritter.** A heart per track/episode in the list screen's row (a small trailing heart, peach when set) and in
   the player under the title; stored in a new Room table `favourites(entryId, itemKey)` (Room 6 with design 49's
   `repeat`, one migration); a category tile "Favoritter" first after "Alle" in the category row (the row scrolls past
   four), showing the entries that hold a favourite, and the entry's list screen offers "Bare favoritter" as a filter
   row at the top when it has any; a tap on the Favoritter tile with one entry opens it; phone-only, never synced or
   reported. A favourite of an item that leaves the listing is pruned with it. Nothing in the parent's PWA.
6. **Not taken**: the "Fortsett" strip (3 covers it), the offline check badge (4 covers it the user's way), the
   rewind after a long pause (declined), playback speed, themes.

## 2. Tests (required)

`FitTest`: the row with the skip pair at 320 dp. `TransportTest` (new or in QueueTest): the ±15/30 s clamp inside the
item. `NowPlayingTest`: `sleepAtEnd` fades and pauses at the item's end, cleared by a step or a new queue.
`MusicViewModelTest`/`LibraryViewTest`: the start index from the stored entry, 0 without one or when filtered out.
`UiModelTest`: `unplayableNow` per source under offline/cell/Wi-Fi, the dimmed cover and the line. Room: the 5 → 6
migration with `favourites` and `repeat`; `FavouritesTest`: set/unset, the category tile's entries, the filter row,
the prune with a gone item. `SourceScanTest` and `LockGlanceGuardTest`-style guards unchanged. Bridge: if the kind is
added to `setNowPlaying`, `BridgeTest`/`MusicPlanTest` on both sides and `bridge_contract.json`.

## 3. Device checks

Emulator: a podcast shows ±15/30 and they work on the phone and (§6h) on a fake Sonos room; "Til slutten" stops after
the episode with the fade; the app opens on the last played tile; on cell with streaming off the Spotify and NRK tiles
dim and a tap says the line; a heart set in a list shows the entry under Favoritter.
