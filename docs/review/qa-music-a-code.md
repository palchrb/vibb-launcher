# Code QA - music round, part A (design 49 shuffle and repeat, Room 6)

- Commits: `d53edc81..1aaa7adb` on branch `music-r-a` - `6c17f55c` (Room 6: the `repeat` and `favourites` tables, one
  auto migration 5 -> 6, `schemas/6.json`, the DAO calls for both) and `1aaa7adb` (the behaviour, the docs, the fake).
- Read: root and `music/CLAUDE.md` (whole); `49-shuffle-and-repeat.md` (whole, with its Implementation status and
  deviations); `qa-music-round-design.md` 49-1...49-11 and the interactions; `50-player-and-library-picks.md` §1.1 and
  §1.5; `26c-spotify.md` §4.2 and §5.4/§5.9; `qa-r6-b-code.md` for the format. Then the full diff of both commits, and
  the code around it: `onItemChanged`, `onError`, `attach`/`toRoom`/`comeHome`, the session's `KidPlayer`,
  `SpotifyRenderer.load`/`reorder`/`apply`/`moveOnTo`, `SpotifyTracker.onState`/`ourState`/`playedOut`/`end`,
  `SonosController.advance` and its `deliberateStart` guard.
- Ran: `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 942 tests, 0 failures, 0 errors, 0 skipped**.
  After the mutations a `--rerun` of the whole suite: 942 tests, **1 failure**:
  `SpotifyRendererTest` "L4 - the page gone while the play call runs" (`:1284`). This test is older than this round
  (26c step 5a). The diff doesn't touch it, and it passed 3 of 3 times alone with `--rerun`. It is a timing flake
  under VM load (another agent was building), not a regression (see 8). `python3 -I scripts/fake-spotify.py
  --selftest`: OK.
  Room 6 schema check (a script over the JSON): `5.json` -> `6.json` only adds `repeat` and `favourites`. Every v5
  entity is byte-identical in v6, and the only other change is the identity hash in `setupQueries`. `5.json` is
  unchanged from the base.
- Mutations: 15 one-line mutations against the targeted classes (`*QueueTest*`, `*SpotifyRendererTest*`,
  `*SpotifyTrackerTest*`, `*SonosControllerTest*`, `*KidPlayerTest*`, `*FitTest*`, `*Room6Test*`, `*SdkStateTest*`,
  `*SpotifyApiTest*`, `*GuardsTest*`) and the fake's selftest. Each was reverted with `git checkout --`, and the tree is
  clean (`git status` empty).
- Contracts: `server/` and `launcher/` untouched. `bridge_contract.json`, the AIDL, `music_*.json`,
  `spotify_hosts.json` and `server/testdata/music_state.json` are all untouched, and no `music_state` field was added.
  The new log lines carry no identifiers ("sonos: episode ended - repeat", "spotify: shuffle or repeat isn't the
  entry's ..."). The new strings (Gjenta/Av/Alle/Én sang, På/Av) don't contain the word Spotify, and `SourceScanTest`
  passes. The new fixture shapes use the existing fake device id and token.

## Findings

**1 (High) - with repeat all, the error move-on loops forever when every item fails at play time.**
`playback/PlaybackService.kt:1513` (`onError` -> `moveOnFrom`) and `logic/Queue.kt:69` (`moveOnIndex`). The one-lap
guard works within one call: it scans the other items once, and only by the *gate* (`playable`: copy/url/network).
`onError`'s move-on is a chain of calls instead: error at i -> seek to the next gate-playable j -> j errors -> ...
With `Repeat.ALL` the chain wraps from the last item to 0 and never stops. That is exactly 49-7's "a plain wrap with
every item unplayable loops forever".

Own music, the one phone entry kind with repeat, is the case that bites. Its items carry a `fileId`, so `onError`
takes the `item.fileId != null -> Unit` branch (`:1491`): no copy is dropped and no item error is recorded, so the
next lap's gate still says playable. Two examples: an album the parent uploaded in a format ExoPlayer can't decode,
or copies whose files are gone. Either one gives a tight seek/prepare/play/error loop, with a "Kan ikke spilles"
message and a `saveNow` per turn. Before this round the chain stopped at the queue's end. Spotify is bounded by the
tracker's "three unplayable in a row - stopped".

Fix: count the lap across calls. Keep the run's origin in `Active` (`failRunFrom: Int?`): set it at the first failure
and clear it when an item really plays (`onIsPlayingChanged(true)`, `:1458`) and on a new queue. Then
`moveOnIndex(n, from, repeat, origin, playable)` returns null once the next candidate would reach or pass the origin.
Add a `QueueTest` that feeds every result back as the next `from` with all items failing and ends in null within
n-1 steps. Add a `KidPlayerTest`-style source assert that `onError` passes the origin.

**2 (Medium) - Spotify list mode: the move-on's wrap to item 0 sends no play.**
`spotify/SpotifyRenderer.kt:1545`. `moveOnTo` reads `target <= tracker.index` (mode OURS/STARTING) as "Spotify
already plays it or past it" and only moves the index. That assumption holds only for forward moves. With repeat all,
an unplayable last track makes the service's `moveOnFrom` wrap to 0. Then 0 <= tracker.index, so nothing is sent,
and Spotify has no track after the last one. The screen shows item 0 while nothing plays (or Autoplay's pause
follows). I found this by reading the code; no test covers the error move-on with a wrap.

Fix: treat a move backwards as a fresh start: `target in index..tracker.index` (the renderer's `index` is the failed
item at that point, `unplayable` moved it). Add a `SpotifyRendererTest`: repeat all, an `Error("unplayable")` on the
last track, and expect one play of the window from 0.

**3 (Medium) - deviation 2 leaves the settings "known" after another device or app had the account.**
`spotify/SpotifyRenderer.kt:1444` (`Event.Elsewhere`) and `:1408` (`Event.NotOurs`). `needSettings = lastSet !=
tracker.wanted`, and `lastSet` is cleared only on a new device id or a differing state that the tracker *reads*. The
tracker compares only in STARTING/OURS. After `Elsewhere` (the account played on another phone) or a foreign track
(the kid's own Spotify app), the mode is LOST and nothing is compared. Those are exactly the moments the account's
shuffle and repeat may have changed. The kid's next play then goes out without settings: Spotify plays with the
other device's shuffle/repeat until the first state shows they differ, and `ShuffleOrRepeat` sends a second
start. That is the audible correction 49-2 asked §1.5 to prevent. In the steady state the deviation does keep §1.5's
intent: a change elsewhere while we play is corrected at the next state (tested), and the account setting can't
stay wrong while we own the player.

Fix: `lastSet = null` in the `Elsewhere` and `NotOurs` branches (and wherever the tracker goes LOST to another
player). It costs two calls only after the account was used elsewhere. Extend `d49 - the start sends ...` with
Elsewhere and then a play: the settings precede the play.

**4 (Low) - the Sonos regression guard's "deliberate" on a wrap/replay has no test (mutation e survives).**
`sonos/SonosController.kt:1118` and `:1124`. Repeat one replays an item whose saved place is near its end, and repeat
all wraps to an item 0 that may have a saved place. Both start below the saved place. Without `deliberate = again`,
the bookmark's hold keeps the old place against the new start at 0. The code is right, but setting both lines to
`false` leaves every test green. Fix: in `SonosControllerTest`'s `d49` case, give item 0 a saved place first (or
replay the item after it played to 0:24:00), then assert the bookmark/position reads the new 0 after the next poll,
for both the `mayPlay` path and the waiting (`startPending`) path.

**5 (Low) - `Room6Test` checks strings, not the 5 -> 6 diff.** `test/.../data/Room6Test.kt`. It follows the Room-5
precedent (`OrderFlipTest:148`), and Room's processor builds the auto migration from the real `5.json` at compile
time, so the migration itself is proven. Nothing pins "only added tables", though. Fix: parse both schema files and
assert that every v5 entity is equal in v6 and that the added names are exactly `{repeat, favourites}` (the check I ran
by hand). The favourites DAO is right for design 50:
- composite PK `(entryKey, itemKey)`
- `insertFavourite` with IGNORE
- `deleteFavourite` for one item
- `deleteFavourites(entryKey, itemKeys)` for the prune
- the flow and the one-shot read

**6 (Low) - stale texts.**
- `docs/design/26c-spotify.md:915`: "Two Web API requests per device session". It is now also after a change and
  after a differing state.
- `26c-spotify.md:1776` (§12): "**No shuffle** for Spotify entries (phase 1's rule)". Overridden by design 49; say so
  there too.
- `scripts/fake-spotify.py:1457`: the `--repeat` help says "until the phone turns it off".
- `49-shuffle-and-repeat.md:156`: "the design QA file `docs/review/qa-music-round-design.md` was not in the tree".
  It was there, at `docs/design/qa-music-round-design.md`, and is an ancestor of the base. The Revision list covered
  every item anyway; I checked 49-1...49-11 against the code.

**7 (Low) - vibb-parity: the implementer's deviations need the user's yes.** 49 §1.2 marks only the on-phone list
shuffle and the repeat as the user's deviations. The status adds:
- **Deviation 1** (stroked glyphs instead of Material Symbols `repeat`/`repeat_one`): a look change that §1.2 named
  explicitly.
- **Deviations 2-3**: the "known" settings and the immediate re-start on a change.
- **Deviation 4** (a context paused under repeat shows paused, no end).

Deviation 4 follows from §1.7 ("only Autoplay evidence ends it") and is fine. Deviations 5-6 are about tests and a
design that hasn't been built yet. Fix: put 1-3 in front of the user (1 is a one-line swap through
`scripts/material-symbols.sh` if the answer is no), and record the answer in 49's status.

**8 (Low, pre-existing) - `SpotifyRendererTest` "L4 - the page gone while the play call runs" is timing-sensitive.**
It failed once in a full `--rerun` under load (`expected SPOTIFY_STOPPED but was null`, `:1284`) and passed 3 of 3
times alone. It is from 26c step 5a and this diff doesn't touch it. Fix: wait for the latch-released call's result
explicitly (a bounded `settle` until `start == null`) rather than one `settle()`. Backlog item.

### Checked and right

- **§1.1**:
  - `controlSlots` by `kindOf` and `resume`; own music keeps shuffle; a Spotify show is SKIP.
  - `canShuffle` excludes an artist context (`contextResumes`), and `canRepeat` doesn't.
  - Tests: `QueueTest` d49.
- **§1.2**:
  - The `repeat` table with the off/all/one words (an unknown word reads as off).
  - `Snapshot.repeatOf` is off where `canRepeat` is false.
  - The row is peach when not off, with `selected` and a state description.
  - `FitTest` checks the row at 320 dp.
- **§1.3**:
  - `REPEAT`/`THEN_REPEAT` are in `Commands.ALL`.
  - `KidPlayer` removes both Player commands, answers `isCommandAvailable` false and makes both setters no-ops.
- **§1.4**:
  - `exoRepeatMode`, and `setModes` after each of the three `active = a`.
  - `toRoom`/`comeHome` go through `start` -> the second one.
  - `REASON_REPEAT` is emitted as `auto = false`, so no done mark.
  - An ExoPlayer wrap with several items is `REASON_AUTO`: the last item is marked done, which is correct.
- **§1.5-§1.7**:
  - `spotifyModes` (list: shuffle off, one = `track`, all = ours; context: its own).
  - `differs(wanted)` replaces `reordered`.
  - `end()` checks `wrap` before `Ended`.
  - `repeatHeld` in both `ourState` and the context end rule; Autoplay still ends a context.
  - The renderer's repeat-all window plays only with `pwr && mayPlay`.
  - A change while playing is a coalesced fresh start at `positionMs()`, which `load` cancels.
  - Tests: the tracker/renderer `d49` cases.
- **§1.8**: `coverTapPlan` puts the bookmarked item first at its place; `coverTapStart` knows it.
- **§1.9**: `advanceIndex` in `advance()`; the same item sets no auto flag.
- **§1.10**: the flip stays hidden while shuffled (unchanged).

The end rules gave no wrong "end" that clears a saved place with repeat on. They also gave no wrong "not end" with
repeat off: the `repeat != 0` and `wrap` checks only add branches ahead of the old ones.

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `controlSlots`: a resuming own entry gets SHUFFLE_REPEAT | caught - `QueueTest` d49 controlSlots, slots |
| b | `moveOnIndex`: `1..n` (the lap may return to the failed item) | caught - `QueueTest` d49 move-on |
| c | `advanceIndex`: repeat all at the end -> null | caught - `QueueTest` (2), `SonosControllerTest` d49 |
| d | `coverTapPlan` shuffle+resume: no first item (`shuffledWithFirst(order, null, ...)`) | caught - `QueueTest` d49 shuffle with resume |
| e | `SonosController.advance`: `deliberate = false` on a wrap/replay | **NOT caught** (finding 4) |
| f | `SonosController.advance`: the auto flag on a replay too | caught - `SonosControllerTest` d49 |
| g | `LocalRenderer.setModes`: `exoRepeatMode(Repeat.OFF)` | caught - `KidPlayerTest` (source) |
| h | `KidPlayer.setRepeatMode` forwards to the player | caught - `KidPlayerTest` refused |
| i | tracker `ourState`: the repeat check never fires | caught - `SpotifyTrackerTest` d49 repeat one |
| j | tracker context end: `contextEnd()` under repeat | caught - `SpotifyTrackerTest` d49 context with repeat |
| k | tracker `end()`: no wrap (`queue.isEmpty()`) | caught - `SpotifyTrackerTest` + `SpotifyRendererTest` d49 repeat all |
| l | renderer `NewWindow`: the wrap is not an auto change | caught - `SpotifyRendererTest` d49 repeat all |
| m | `spotifyModes`: list-mode repeat all -> Spotify `context` | caught - `SpotifyRendererTest` d49 (2) |
| n | renderer `ShuffleOrRepeat` keeps `lastSet` | caught - `SpotifyRendererTest` H1 (shuffle switched on elsewhere) |
| o | fake: `REPEAT["track"] = 1` | caught - `fake-spotify.py --selftest` (the pushed states) |

## Verdict

**Approved with fixes.** The predicate, Room 6, the session guard, the end rules and the contracts are sound, and 14
of 15 mutations are caught. Before the merge: finding 1 (High: the error move-on must count its lap across calls)
and finding 2 (the Spotify wrap that sends nothing), each with its test. Finding 3 is a two-line change worth doing
with them. Findings 4-6 are small. Finding 7 is a question for the user. Finding 8 goes to the backlog.
