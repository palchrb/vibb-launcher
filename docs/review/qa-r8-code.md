# Code QA of round r8 (music small items)

Branch `r8-music` at 78540812 (code 1e17fcf9) against base 351a0219, checked against `docs/design/r8-music-small-items.md`,
`qa-r8-design.md` and `qa-r8-revision-check.md`. Paths are under `music/app/src/main/java/me/vibb/music/`.

**Run**: one full `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1180 tests, 0 failures. The
build left the exported `schemas/.../7.json` unchanged; 6 -> 7 differs only in `now_playing.favourites TEXT` (nullable),
every other table identical.

**Verdict**: the round does what the note says. The scope is fixed at the tap and carried through every rebuild I traced
(restore, adopt, phone -> room in both branches, room -> phone, shuffle, the flip, `persistPlace`/`saveBookmark`,
`flippedNowPlaying` and `SonosSuspension.bookmark` through `row.copy`). `activeFor` keeping `Active.order` to the scope is
the one real guard: every queue built goes through it. No High or Medium. One behaviour bug on a rare Spotify path, one
tile/tap mismatch on a room, three untested carries (the mutations), and small DragDown and code nits.

## The implementer's deviations

1. **A pick with nothing loaded uses the stored queue's scope** (`playback/PlaybackService.kt:822-827`). Accepted: the
   player and `playSwitch` show the stored row's scope (`nowView` -> `NowView.favourites`), so the service now agrees
   with what the kid sees. One extra Room read per pick, only when nothing is loaded.
2. **The tile keeps the raw bookmark when the tap would wait** (`ui/UiModel.kt:110-114`). Accepted: the tap then says
   "Ikke lastet ned" on that book, so the tile names the book the tap is about. Same rule as `playEntry`.
3. **`playSwitch` takes `favourites: Boolean` and reads the hearts from `shown`** (`ui/UiModel.kt:207-214`). Accepted:
   `heartsListed` is the same in both views (pinned in `FavouritesTest`), and the service reads its own snapshot the
   same way, so the two can't drift on what the scope is.
4. **A float state with `DraggableState.drag` instead of an `Animatable`** (`ui/DragDown.kt:70-83`). Accepted, it is less
   code: a draggable drag (UserInput) cancels the spring through the state's mutex, and a spring started from the
   list's `onPreFling` runs in the list's fling job, so a new touch on the list cancels it. One gap left, R8C-5.
5. **A GuardsTest-style source guard of the service wiring** (`FavouritesTest.kt`, last case). Accepted as the house style
   (the service has no JVM harness). It pins the lines it names; R8C-3 lists the carries it doesn't name.

## Findings

### R8C-1 (Low) RESUME on a stale Spotify favourites queue plays nothing
Evidence: `playback/PlaybackService.kt:840` (`resumeLast` passes `current.favourites != null`), `:783-790` (the stale
path inside the same-scope branch falls through with `favourites` still true), `:796` (`heartsListed` of the *new*
listing), `:802-805` (empty -> message, return). `restartIfStale` (`:537`) handles the same staleness with
`favourites = false`.
Scenario: a Spotify playlist entry in list mode with two hearted tracks; the kid plays it under "Favoritter". The parent
switches the entry to context mode (or another target, `loadedPlayStale`). The kid taps RESUME on Home's card:
`playEntry(id, true)` -> stale -> the new listing is one synthetic context item with no heart -> the scope is empty ->
"Ikke lastet ned" and nothing plays. Before r8 (and through `restartIfStale` today) the new context starts.
Fix: in `playEntry`, a stale loaded play starts the whole entry, as `restartIfStale` does (the hearts belong to the old
listing): e.g. `var whole = !favourites` set to true in the stale branch, and `scopeOf` reads `takeIf { !whole }`. One
GuardsTest line next to the existing N5 one.

### R8C-2 (Low) On a room, a Storytel "Favoritter" tile can name another book than its tap plays
Evidence: `ui/MusicRoot.kt:271` passes the phone-view snapshot `s` to `entryUi`; `ui/UiModel.kt:105-114` finds the scope's
start in `snap.order(entry)` (downloaded books only); `playEntry` on a room plans over the room's view (`:794-801`,
`loadForRenderer` -> every book).
Scenario: series b1, b2, b3 (oldest first), hearts on b1 and b3, only b3 downloaded, the bookmark on unhearted b2, a room
selected. The tile says "del 3 av 3" (b3, the first hearted book on the phone); the tap starts b1 on the room (the first
hearted book in the room's order). Design r8 §1.1: "the tile describes the hearted run".
Fix: in `entryUi`, take `visible` for the scope (and the wait) from `snap.forRenderer(net?.onRoom == true)`; keep
`countedOrder` for the number. A `UiModelTest` line with `net(onRoom)`.

### R8C-3 (Low) Three scope carries no test sees (mutations M2, M4, M5, M13)
Evidence: `playback/PlaybackService.kt:2255` (`toRoom`'s stored-row branch `row.scope()`), `:2156` (`adoptedQueue`'s
`row?.scope()`), `:1736` (`queueEnded`'s `held`), `:827` (`playItem`'s `key in it`). Each mutated away, the full suite
stays green.
Scenario (M13, the restart case the brief names): a favourites queue on a room, the app killed, reopened -> `lookAtRoom`
adopts it with `favourites = null`. The room still plays the hearted items (the stored keys), but the next shuffle or
flip rebuilds over the whole entry, the next `persistPlace` stores the row without its scope, and the end clears every
partial position. M2 is the same after "Spill av på" from a stored queue; M4 loses the half-heard episode of §4.5; M5
inverts §4.3's list rule in the service while `playSwitch` (whose rule is tested) shows the opposite.
Fix: four `contains` lines in `FavouritesTest`'s service case, beside the ones it has:
`Triple(entry, it, row.scope())`, `val favourites = row?.scope()`, `val held = a.favourites?.let { a.items.map { it.key } }`
and `val scope = queueScope?.takeIf { key in it }`.

### R8C-4 (Nit) The empty-plan message now also fires for "Alle"
Evidence: `playback/PlaybackService.kt:802-805`: the `?: run { tryEmit(...) }` covers `scope == null` too; before r8 an
empty `coverTapPlan` returned silently.
Scenario: an "Alle" tap on a Storytel series with no book downloaded and no bookmark, or on a feed listed with no items yet,
now says "Ikke lastet ned" (on a room "Kan ikke spilles på Sonos"). That's arguably better for Storytel, but nobody
decided it, and for an empty feed it says the wrong thing.
Fix: either emit only when `scope != null`, or keep the message and say so in `music/CLAUDE.md`'s bullet.

### R8C-5 (Nit) A spring started by the sheet's own drag isn't stopped by a drag in the output rows
Evidence: `ui/DragDown.kt:80` (the spring holds the state's mutex at `Default`), `:90,96` (`dispatchRawDelta` from the
nested scroll bypasses the mutex), `:81` (`dragBy(value - offset)` puts the spring's value back every frame).
Scenario: a short drag on the "Spill av på" header, released, springs back (~300 ms); within it the kid drags the rows
(at their top) down. The sheet ignores the finger until the spring ends, then jumps to it. Cosmetic and short.
Fix: when the connection takes a non-zero delta while a spring runs, cancel it, e.g. keep the spring's `Job` from a
`rememberCoroutineScope()` launch and `cancel()` it in `onPreScroll`/`onPostScroll`. Or accept it.

### R8C-6 (Nit) Two `scoped(...)` re-plans duplicate `activeFor`'s own filter
Evidence: `playback/PlaybackService.kt:2329` (`comeHome`) and `:2244` (`toRoom`'s Storytel branch) scope the order before
`homePlan`. `homePlan` rotates to the current item at index 0, and `activeFor` (`:700`) maps the keys through the
scoped order anyway. Mutation M10 (the whole order in `comeHome`) is green, and it behaves the same. `adoptedQueue`'s
`scoped` (`:2157`) is the same case: its stored keys are already the scope.
Fix: optional. Dropping them is three fewer reasons to read twice. If they stay, the KDoc on `activeFor` should say
that it is the guard and these only state the intent. Keep the scoping in `playItem` (`:829`): `pickPlan`'s shuffled
branch compares the order's set with the queue's.

## Checked and fine
- `playEntry`'s same-tile branch: the comparison is on the scope's presence, as the note says. The Spotify stale check
  runs only for the same scope, and the forced save (`runBlocking(writer)` in `persistPlace`) lands before
  `loadForRenderer()` on both the scope change and the stale path. On an empty scope the old queue stays, its place saved.
- `toRoom`'s Triple: the Storytel re-plan over the room's order keeps `a0.favourites`, so a room gets every hearted book;
  the other branch keeps `a0.items`.
- `clearPartialPositionsOf(... IN (:itemKeys))` unchunked: the list is `a.items` of a favourites queue, at most the
  entry's hand-set hearts, far below SQLite's 999-variable limit on old Android. Fine.
- Room 7: an auto migration with one nullable `ADD COLUMN`, the field last with a default, so both positional
  constructors compile; `Room6Test` 6 -> 7, `OfflineTest:334` moved to `version = 7,` (revision check N3).
- DragDown: the draggable sits outside the `graphicsLayer`; `onPreFling` settles only while moved; `SideEffect` leftovers
  are never taken (a fling up to the top doesn't pull). The seek bar, the sliders and the pager claim only horizontal
  slop, so a vertical drag passes to the parent and cancels their taps. `startDragImmediately` stays false, so taps on
  the player still reach its buttons. A close sets the offset back to 0, and the surface leaves composition in the same
  frame. TalkBack's `dismiss` sits on the same node as the sheet's swallowing `clickable`.
- `PLAY_ENTRY` has no other sender (the bundle's missing flag reads false). Search passes false.

## Mutations (each reverted; full `testDebugUnitTest` per mutation)

| # | Mutation | Result |
|---|---|---|
| M1 | `restore` passes `null` for `row.scope()` | caught (GuardsTest 26c 5_10) |
| M2 | `toRoom` stored-row branch drops `row.scope()` | **survives** (R8C-3) |
| M3 | `toRoom` Storytel Triple drops `a0.favourites` | caught (GuardsTest 5_6 H2) |
| M4 | `queueEnded`: `held = null` (clears every partial) | **survives** (R8C-3) |
| M5 | `playItem`: `key !in it` (list-pick rule flipped, service) | **survives** (R8C-3) |
| M6 | the forced `saveNow(force = true)` dropped | caught (FavouritesTest service case) |
| M7 | `dragCloses` without the 56 dp floor | caught (DragDownTest) |
| M8 | `playEntry`'s Storytel wait ignores the scope | caught (GuardsTest 26b M1) |
| M9 | `entryUi` passes `null` as the scope | caught (UiModelTest r8 tile) |
| M10 | `comeHome` re-plans over the whole order | survives, harmless (R8C-6) |
| M11 | `playSwitch`: `itemKey !in it` (list-pick rule flipped, UI) | caught (UiModelTest r8 pick) |
| M12 | `resumeLast` passes `false` | caught (GuardsTest "every way to start playing") |
| M13 | `adoptedQueue` drops `row?.scope()` | **survives** (R8C-3) |
| M14 | `postScrollTake` takes fling leftovers | caught (DragDownTest) |

9 of 14 caught. Of the 5 survivors, the 4 with an effect are R8C-3's four lines; M10 is a no-op.
