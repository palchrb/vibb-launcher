# Code QA - music round, part B (design 50, design 46 §1.4, the merge of design 49)

- Commits on branch `music-r-b`, from `d53edc81`:
  - `0310adfd`: 50 §1.1-§1.4, 46 §1.4 and the bridge key.
  - `a0df5dda`: A's Room 6 migration, cherry-picked.
  - `b18115d8`: favourites, 50 §1.5.
  - `be01779f`: the merge of `music-r-a` at `1aaa7adb`, with ten resolved conflicts.

  Design 49 itself is reviewed separately on `music-r-a`. I read its code only where 50 or the merge touches it.
- Read:
  - the root and `music/CLAUDE.md`;
  - `50-player-and-library-picks.md` (whole, incl. the revision and the status);
  - `46-context-queue-and-play-marks.md` §1.4 and its status;
  - `49-shuffle-and-repeat.md` §1.1, §1.2, §1.9 and its status;
  - `qa-music-round-design.md` (50-1 to 50-7, 46-10, the interactions);
  - `git diff d53edc81..be01779f` in full;
  - `git show be01779f --cc` against both parents.
- Ran:
  - The music app's `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 960 tests, 0 failures, 0
    errors, 0 skipped** (`be01779f`, clean tree).
  - The launcher's `MusicPlanTest`/`BridgeTest` filter: **BUILD SUCCESSFUL, `MusicPlanTest` 41 tests, 0 failures**.
    The launcher has no `BridgeTest` class; the contract check is `MusicPlanTest`'s.
  - 18 one-line mutations against the targeted classes, each reverted. **15 were caught; 3 survived** (two are
    findings, one is equivalent). The tree is clean again (`git status` empty apart from this file).
- Contract:
  - `bridge_contract.json` is byte-identical in `music/` and `launcher/` (`diff`).
  - `server/` is untouched (`server/testdata/music_state.json` unchanged), and no other `music_state` field moved.
- Process note (no effect on this branch):
  - My first mutation script used the shared scratchpad name `mut.py`. That overwrote the design-49 reviewer's
    script of the same name. Its process was already running from memory.
  - I stopped my run, reverted my one live mutation, and left `mut.py` as a stub that exits with an explanation. My
    script is now `qa50b-mut.py`. The design-49 reviewer should check that its own run and log are intact (`mut.log`
    was written by both for about a minute).

## Findings

**1 (Medium) - "Til slutten" is lost at every queue load, not only at a new choice.** `start()` ends it
(`PlaybackService.kt:825`, `endSleepAtEnd(SleepEvent.NEW_QUEUE)`). But `start()` is also the path for loads that are
no new choice by the kid:
- **The first play of a restored queue.** The player shows the stored queue paused and offers the sheet. The kid
  picks "Til slutten", then presses play. That sends `RESUME` -> `resumeLast` -> `start` (`:742-751`). The same
  happens through `restoreThen` (`:760-763`) and through a stored Spotify queue's playback resumption (`:599`). The
  choice silently disappears at the first tap.
- **A switch of output.**
  - Phone -> room: `attach(sonosRenderer(...))` (`:2010`) re-arms the new renderer (`:446`), and `start(a, p)`
    (`:2018`) clears it a few lines later.
  - Room -> phone: `comeHome` does the same (`:2052` then `:2077`).

  The status says "a renderer switch moves it to the new renderer (`attach`)". `NowPlayingTest` pins only that source
  line, so the test is green while the behaviour is the opposite. Only a room's adopt (no `start`) keeps it.

The minute timer survives all of these. 50 §1.2 says "cleared by a new queue" in the sense of the kid's new pick.

Fix:
- Clear it where the kid makes a new choice (`playEntry`/`playItem`'s start, or a `newChoice` argument to `start`),
  not in `start` itself. Keep `start` neutral for restore, resume and the two switches.
- Decide, and say in §1.2, whether a list pick of another item in the same entry is a "step" (like the swipe, which
  keeps it) or a new queue (today it is a new queue).
- Add a guard that `toRoom`/`comeHome`/`resumeLast`/`restoreThen` don't clear it.

**2 (Medium) - "it beats repeat-one" is untested on the room, and is the emulator's job on the phone and Spotify.**
Mutation e survives: `if (mayPlay() && (held == null || again))` lets repeat's wrap or replay win over the hold, and
nothing fails. The merged `advance()` is right as written: the hold is read before `playIndex`, for `next ==
cur.index` (ONE) and for the wrap to 0 (ALL), with `deliberateStart = again` so the regression guard takes the 0.

Fix: add a `SonosControllerTest` case with `repeat = ONE` and `holdAtEnd` set (held, nothing played, the same index at
0), and another for `ALL` at the last item (held at 0).

On the other renderers:
- **The phone.** Media3's contract for `setPauseAtEndOfMediaItems`: "pause at the end of each window", signalled as
  `onPlayWhenReadyChanged(false, END_OF_MEDIA_ITEM)`. `ExoPlayerImplInternal` sets its pending pause for the period
  that is last in its window, whatever the repeat mode. So it pauses under `REPEAT_MODE_ONE` too, and the kid's next
  play is the `REASON_REPEAT` replay. At the last item with repeat off it pauses and then also goes `STATE_ENDED`, so
  `queueEnded` runs after `done`.
- **Spotify.** See finding 3.

Owed on the emulator:
- the phone with repeat one + Til slutten: one play, then a pause with the fade, no second start;
- the last item with repeat off: one pause, `clear_state`, the done mark exactly once;
- repeat all at the last item: a pause, no wrap.

**3 (Low/Medium) - Spotify: the belt does not cover `repeat=track`, nor list mode's repeat-all wrap.**
- The web player pauses itself when the look sees `left <= 400 ms` (`SpotifyRenderer.kt:750`). The look runs every
  250 ms inside the fade, on the extrapolated `place`.
- The belt (`atEndBelt`, `:1443`) fires only on an automatic `ItemChanged`.
- Under Spotify's `repeat=track` (repeat one in list mode, or a context's `track`), the loop is the same uri. It is no
  item change. If the look misses the 400 ms window (the estimate lags Spotify's clock, or a state comes late), the
  track restarts with the gain back at 1, and Til slutten stops only after the second play.
- List mode's repeat all is a `NewWindow(0, wrap)` and not an `ItemChanged` either, so it has no belt.

Fix: while Til slutten is on, treat a jump back to about 0 on the same track as the end, or the `repeatHeld`-style
look. The simplest alternative is to send `repeat=off` with the choice. Emulator: `fake-spotify.py --repeat track`
with Til slutten, a track run to its end.

**4 (Medium, a scope question for the user) - a tap on the "Favoritter" tile can start audio.** With one entry
holding a heart, `onCategory` calls `playEntry` (`MusicRoot.kt:254`, `favouritesTapOpens`). That opens the player and
plays. On the loaded, paused entry it is `renderer.play()`, so a tap on a category resumes playback.
- Design 50 §1.5 marked "opens that entry" as "a proposal - drop if unwanted", and design QA 50-4 said to keep it
  only if the user agrees. The status records the reading, but not the user's agreement.
- No other category tile starts sound, and vibb's tiles never do.
- The filtered carousel already shows that one entry centred, which is a fair reading of "opens".

Fix: drop the play (keep the filter) unless the user confirms this one.

**5 (Low) - nothing pins the `controls` word per kind (mutation p survives).** Swapping `skip`/`music` in
`nowPlayingControls` leaves the suite green:
- `BridgeTest` checks the contract's two words;
- `SourceScanTest` checks the `putString` line;
- no test checks which kind sends which word.

The lock tile will act on it in the later step. Fix: a `BridgeTest` line each, for a podcast (`skip`), a book
(`skip`), a resuming own entry (`skip`), own music (`music`) and Spotify (`music`).

**6 (Low) - `netVersion` moves far more often than the network does.**
- `onCapabilitiesChanged` also fires for signal-strength and bandwidth updates. On cell that is every few seconds.
- Each bump recomposes the library and maps `entryUi` for every entry.
- It also reads the managed config (`store.streamMobileData` -> `Bridge.config`, a binder call) on the main thread.

There is no leak: one callback per view model, registered in `init` and unregistered in `onCleared`, and
`bumpNet` after `onCleared` lands in a cancelled scope.

Fix: compute `NetNow`'s inputs (validated, metered, none) in the callback and bump only when they change.

**7 (Low) - the player's heart moves the layout.** The heart (`PlayerScreen.kt:140`) sits in a `Row` with "Episode N
av M":
- The 32 dp target makes that line about 14 dp taller for every item, so the weighted art shrinks.
- The number is no longer centred: it moves 18 dp left.
- The target is 32 dp, below the list's 40 dp and the 44 dp of the other controls.

Fix: overlay the heart at the line's end, so the number stays centred and the line keeps its height. Or put it
beside the title. Use 40 dp. Check the look on the emulator at 320 dp.

**8 (Low, notes)**
- Mutation k (`unplayableNow` without its `onRoom` line) survives, but it is equivalent: `entryUi` guards the room
  again (`net?.onRoom != true`). One of the two guards can go.
- Til slutten that survives a step onto an item with no duration (live) never ends, and the bar keeps "Sov · til
  slutten". This is rare today. Ending it at a step onto such an item would match §1.2's "hidden" rule.
- The bar's "Sov · til slutten" without the remaining time is a documented deviation, and the reason holds (the
  output's name would be cut at 320 dp). It needs the user's nod alongside finding 4.

## The checks

1. **50 §1.1, the skip pair: holds.**
   - `controlSlots` is the one predicate, and `SKIP` draws `SkipButton` with Material Symbols glyphs and a fixed 7 dp
     number.
   - `skipWithin` keeps a step inside the item: never below 0, never past `duration - 1 s`, forward stays put inside
     the last second, no forward without a duration. This is mutations a and b.
   - The target comes from the live position (`PlayerHub.positionMs()`) and the live duration (`hub.durationMs` is
     the renderer's, so a context's live track).
   - The step goes through the existing `seekTo` (`RESTORE` + `THEN_SEEK` when not live).
   - Previous/next are unchanged.
2. **50 §1.2, "Til slutten": right per renderer, wrong in the service's clearing (finding 1).** Each renderer pauses
   by its own means, and `done` runs once:
   - The phone: `pauseAtEndOfMediaItems` and the `END_OF_MEDIA_ITEM` branch, which emits `Paused(OTHER)` and then
     clears itself.
   - A room: `holdAtEnd` is cleared before `held()`. The ramp and the hold race through `finishAtEnd`'s `atEnd`
     guard.
   - Spotify: `finishAtEnd`'s guard.

   "A pause that never comes":
   - an item with no end event has no duration, so the choice is hidden (`sleepAtEndOffered`);
   - the looks keep running at 1 s while paused and stop at `pauseAtItemEnd(null)` and at `release()`.

   "Comes twice": the second pause finds `atEnd == null`, and the phone's flag is off again.

   Clearing: the kid's pause (`USER` -> `setSleep(0)` -> `TIMER_SET`), its own pause, `queueEnded` and a minute
   choice are right. `start()` is too broad (finding 1). Repeat: finding 2 and finding 3.
3. **50 §1.3, the carousel's start: holds.**
   - `CarouselStart` answers once.
   - `turned` marks a real turn only. The pager reports its start page, and after `startOn` that is the new index.
   - `pickCategory` and `backToLibrary` mark it moved.
   - The `LaunchedEffect` keyed on `snap != null, row != null` runs again after an activity recreate, but `first()`
     answers null then, so no jump.
   - After process death the view model is new and opens on the last played tile again. That is the intended start,
     not a jump after a move.
   - Mutations g, h and i are caught. h and i are caught by source lines in `LibraryViewTest`, since there is no view
     model test.
4. **50 §1.4 and 46 §1.4, dimmed tiles and the arrow: hold.**
   - `unplayableNow` uses `itemPlayable(LOCAL, ...)` over the visible items, and `spotifyPlayability` for Spotify.
   - `ASK_ADULT` dims too; nothing dims on a room.
   - There is one line: the state, else "Trenger Wi-Fi", else the hint. So `libraryFit`'s two lines are unchanged.
   - The cover is at 60 % times the neighbour's alpha. The arrow is `!unplayableNow`, its own condition, and the
     progress line is unchanged. The tap is unchanged.
   - Mutations j and l are caught; k is equivalent (finding 8).
5. **50 §1.5, favourites: hold, apart from the one-entry tap (finding 4).**
   - The key is entry key + item key, and Room 6 is one auto migration (`schemas/6.json`, `Room6Test`).
   - The prune only names what a parsed listing (`applyListing`) or the parsed library's own files no longer name.
     The listing includes withdrawn items ("withdrawn ones included", `Snapshot.kt:89`), and `items` is replaced from
     that same listing. So a withdrawn item kept with its copy keeps its heart, and a heart never outlives its row.
   - A gone entry's hearts go only when every entry was read (design 39's flip rule).
   - There is no heart on a context item: `canFavourite` covers the player and `setFavourite`, and `takeIf {
     !isSpotifyContext }` covers the list.
   - The sentinel -1 lives only in `vm.category` and the pure filters. It is never stored or saved, and never in
     `Sync`, the report, the bridge or `ListArtWants` (grep).
   - Favourites are never synced or reported. `Snapshot.favourites` reaches only the UI and `Sync`'s prune.
   - Mutations m, n, o and q are caught.
6. **The merge: clean.** Every resolution keeps both sides:
   - `Snapshot`: `favourites` then `repeat`, both fields and both helpers.
   - `Store`: the nested `combine` for shuffle/flips/repeat, plus `favouritesFlow`, and `buildSnapshot(..., flips,
     hearts, repeat)`.
   - `SonosController.advance`: 49's `advanceIndex`, `again` and `deliberate`, with 50's hold read first and
     `deliberateStart = again` in the held branch too.
   - `SonosRenderer`: both sides' imports.
   - `PlayerScreen`: `SKIP` -> the pair, else `canShuffle` / `canRepeat` / spacer, the same on both sides.
   - `UiModel`: `canRepeat` and `slots`.
   - `music/CLAUDE.md`: both bullets.
   - The three tests: both sides' cases in `SonosControllerTest` and `FitTest`; `GuardsTest` pins the merged `Store`
     lines exactly.

   `ControlSlotsStub.kt` is deleted, and there is one `enum class ControlSlots` (`logic/Queue.kt:21`). The 49 code I
   passed through looked sound. One note for its reviewer: `toggleShuffle` for a Spotify context only flips
   `a.shuffled` and relies on `applyModes`, which is right.
7. **Privacy and contract: hold.**
   - `SourceScanTest` is green, and no new kid-facing string says "Spotify".
   - The new strings are `Favoritt`, `Favoritter`, `Trenger Wi-Fi`, `Til slutten`, `Sov · til slutten` and the two
     skip labels.
   - No fixture looks like a secret. `play_request.json`'s change is 49's.
   - The bridge key needs no AIDL change. The `controls` word is not shown as text (`SourceScanTest`'s list).
8. **vibb parity.** The deviations are the user's picks of 2026-10-20 as the notes mark them: the skip pair,
   Til slutten, the carousel's start, the dimming, favourites, and the arrow on every tile. Three are the
   implementer's own reading and need the user's word:
   - the one-entry Favoritter tap that plays (finding 4);
   - the bar without the remaining time (finding 8);
   - the 1 s margin on forward. That one is sound: §1.1 forbids crossing the item.

   46's queue view is parked as §0b says.

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `skipWithin` without the 1 s end margin | caught - `TransportTest` (2 tests) |
| b | forward with no duration moves anyway | caught - `TransportTest` context/unknown |
| c | `sleepAtEndAfter` keeps it on a new queue | caught - `NowPlayingTest` clearing rule |
| d | `start()` without `endSleepAtEnd(NEW_QUEUE)` | caught - `NowPlayingTest` (source line) |
| e | room: `mayPlay() && (held == null \|\| again)` - repeat beats the hold | **NOT caught** (finding 2) |
| f | room: `holdAtEnd` not cleared before `held()` | caught - `SonosControllerTest` d50 |
| g | `CarouselStart.first` answers more than once | caught - `LibraryViewTest` d50 |
| h | `turned` never marks a move | caught - `LibraryViewTest` (source line) |
| i | `backToLibrary` doesn't mark a move | caught - `LibraryViewTest` (source line) |
| j | `showsPlayMark` = `true` (arrow on a dimmed tile) | caught - `UiModelTest` d46 |
| k | `unplayableNow` without `net.onRoom -> false` | not caught - equivalent (`entryUi` guards the room) |
| l | `needsWifi = refused` (Wi-Fi line over the account line) | caught - `UiModelTest` d50 one line |
| m | `favouritesToPrune` drops listed hearts too | caught - `FavouritesTest` prune |
| n | gone entries' hearts pruned without `allRead` | caught - `FavouritesTest` prune |
| o | `FAVOURITES_CATEGORY` = 0 | caught - `FavouritesTest` tile |
| p | `nowPlayingControls` with `skip`/`music` swapped | **NOT caught** (finding 5) |
| q | `canFavourite` = `true` (heart on a context item) | caught - `FavouritesTest` |
| r | Spotify without `atEndBelt()` on an automatic change | caught - `SpotifyRendererTest` d50 belt |

## Verdict

**Approved with fixes.** There is no High finding, and the merge is clean.

Before merging to `main`:
- Fix finding 1. Til slutten must survive the first play of a restored queue and an output switch, and a guard must
  pin that.
- Add the two tests for findings 2 and 5. Both are small.
- Ask the user about finding 4: keep or drop the play on the one-entry Favoritter tap.

Finding 3 needs an emulator check with the fake's `--repeat track`. If it reproduces, the cheapest fix is `repeat=off`
while Til slutten is on. Findings 6 to 8 can go to the backlog. The emulator list of 50 §3 is still owed, plus the
repeat × Til slutten cases in finding 2.
