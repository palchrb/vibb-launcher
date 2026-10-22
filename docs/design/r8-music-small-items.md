# Music round 8 - small items (proposal, revised after `qa-r8-design.md`)

Asked 2026-10-21 (PLAN.md, "Music: what a heart means" and "Music: the output and sleep sheets don't swipe down").
Music app only. No server, launcher, PWA or contract change. Three items, each with its own tests, one round:
§1 a "Favoritter" cover tap plays only that entry's hearted items (option C); §2 the two sheets swipe down to close;
§3 a drag down on the player returns to the library. Paths are under `music/app/src/main/java/me/vibb/music/`.

## 1. Favourites, option C

### 1.1 Behaviour

- Hearts stay per track/episode/book (Room `favourites`, design 50 §1.5). Nothing changes outside "Favoritter".
- Under "Favoritter", a cover tap plays **only that entry's hearted items, in the entry's own order** (the order the
  list shows, so the parent's order and the kid's flip apply). One entry, one source, no mixing.
- **Where it starts.** The entry's bookmark, when that item is hearted (at its place, as today). Otherwise the first
  hearted item: at its own saved place when the entry resumes, from 0 when it doesn't. Shuffle on (music): a fresh
  permutation of the hearted items, as `coverTapPlan` does today. Storytel: the bookmark on a hearted book that isn't
  on the phone waits ("Ikke lastet ned"), as design 26b §5.6 does for the whole entry; a bookmark on an unhearted book
  never waits.
- **One bookmark per entry.** A favourites run moves the entry's bookmark, and its end drops it, so the next "Alle"
  tap starts from the top. A half-heard episode outside the run keeps its own place, and a pick from the list resumes it.
- **The queue is fixed at the tap**, like any queue. A heart set or cleared while it plays changes nothing in the
  playing queue; the current item plays on even when its heart goes. The next "Favoritter" tap that starts a new
  queue uses the hearts of that moment.
- **The same-tile rule** (a tap on the loaded entry unpauses, never restarts) holds when the tap's scope is the
  loaded queue's: a "Favoritter" tap on a loaded favourites queue unpauses it, as does an "Alle" (or category, or
  search) tap on a whole-entry queue. A tap with the other scope is a new choice: the place is saved first (forced),
  then a new queue from the rules above, so a resuming entry goes on at the same second when that item is in the
  new scope. For a few hundred ms the old pages stay, until the service publishes (accepted, R8-11).
- **The tile under "Favoritter" describes the hearted run**: its hint and progress line come from the scope's start
  (the numbering stays the entry's, "episode 2 av 40"), and it dims only when no hearted item can play now.
- **Player, next, previous, the queue.** The carousel pages and the dots are the queue: the hearted items only. Next
  and previous step through them. Repeat all wraps inside them, repeat one repeats the item. With repeat off, the
  last hearted item's natural end is the queue's end ("Til slutten" ends there too). **Default (open question 2):**
  "Episode N av M" keeps today's rule (`playerNumber`, `ui/UiModel.kt:302`): the entry's own numbering
  ("Episode 7 av 40"); a room's speaker-playable queue already counts this way.
- **The list** (Liste). **Default (open question 1):** unshuffled it shows the whole entry with its hearts, so the kid
  can heart more; shuffled, the queue (as today). A pick of a hearted row while that entry's favourites queue is
  loaded stays in it, from that row (shuffled: within the queue's permutation, as `pickPlan` does). A pick of an
  unhearted row plays the whole entry from that row.
- **The queue's end** (vibb `clear_state`, `queueEnded`, `playback/PlaybackService.kt:1661`) drops the bookmark as
  today, but clears only the partial positions of **the items the queue held** (`a.items`).
- **After a restart** the stored queue comes back paused with its scope (§1.3's new column). Shuffle, the flip, a list
  pick, the end and a room hand-over still keep to the hearted items.

### 1.2 Per play path

The queue the service builds is a list of keys (`QueuePlan`), and every renderer already plays "the items it was
given" (a room's queue is a subset today). The change is building the subset and keeping it on every rebuild.

- **The scope is the hearts at the tap, intersected with the entry's listed keys** (`snap.listed`, the same in both
  views). It is never the renderer's subset. Each renderer then takes its own view of it: the phone the playable
  ones, a room the speaker-playable ones. So a row written on a room and restored on the phone keeps the hearted own
  files and HLS items, and a Storytel queue moved to a room gets every hearted book.
- **Phone, Media3 (own files, NRK, RSS).** `start` (`PlaybackService.kt:852`) loads the subset. An item that can't play
  now is skipped as today ("Ikke lastet ned"). Own files are phone-only and play here only.
- **Sonos room (design 26a).** `queueFor` (`:724`) drops what a speaker can't play. `SonosController` sends URL items
  one at a time and advances through `a.items` (`sonos/SonosRenderer.kt:288`), which is the subset. A favourites queue
  of only own files says "Kan ikke spilles på Sonos" and nothing moves (the existing path). Phone to room (`toRoom`,
  `:2148`) keeps `a0.items`. **Two re-plans read the whole order today and must take the scope**: `comeHome`
  (`:2237`, its `homePlan` at `:2264`) and `toRoom`'s Storytel re-plan over the remote order (`:2172-2175`).
- **Storytel (a book is one item).** The phone's view holds only downloaded books, a room's every book. With the
  scope above, a room gets every hearted book and the phone the hearted downloaded ones.
- **Spotify on the phone (design 26c, Web Playback SDK).** `SpotifyRenderer` plays the uri window of `a.items`
  (`playWindow`): the subset works as is. A Spotify context has one synthetic item with no heart
  (`canFavourite`), so it never shows under "Favoritter".
- **Spotify on a room.** Not on today: `PlayBodies.speakerDropReason` (`sonos/PlayBody.kt:160`) refuses it, so
  "Kan ikke spilles på Sonos" as for any Spotify entry. **Hand-over to design 54**: its share link puts the whole
  list in the speaker's queue; a favourites queue is then "our order" (54 §2.6), and 54-a2's tests must cover one -
  the correction at each boundary, 54-b's reorder, and §2.7's end after the last hearted track.
- **Nothing hearted is playable here** (the scope is empty in this renderer's view: hearted books not downloaded,
  hidden withdrawn items): nothing starts and the current queue stays. The phone says "Ikke lastet ned" (as `start`
  says for an entry with nothing playable); a room says "Kan ikke spilles på Sonos" (as `queueFor` does).
- **A hearted item gone from the entry.** At the tap, a heart on an item the listing dropped is ignored (the
  intersection with `snap.listed`) until `Sync` prunes it. While a queue plays, an item that leaves the listing is
  handled as for any queue today; a stale key in the stored scope is harmless.

### 1.3 Code

- `logic/Favourites.kt`: one pure function that the service and the screens both call, so they can't disagree:
  ```kotlin
  data class FavouritesScope(val keys: List<String>, val bookmark: Bookmark?)
  fun favouritesScope(order: List<String>, scope: Set<String>, bookmark: Bookmark?, resume: Boolean,
                      position: (String) -> ItemPosition?): FavouritesScope
  ```
  KDoc: `scope` is the stored scope (§1.2); `keys` is only this renderer's plan order (`order` filtered to `scope`),
  never what is stored. `bookmark` = the entry's bookmark when its item is in `keys`, else (resume on)
  `Bookmark(keys.first(), resumeFrom(position(it)))`, else null. The result goes to the unchanged `coverTapPlan`
  (`logic/Queue.kt`) and `coverTapStart` (`logic/Switch.kt:65`).
- **The Storytel wait**: before `favouritesScope`, both `playEntry` (`:755`) and `playSwitch` (`ui/UiModel.kt:191`) call
  `coverTapAwaitsBook(entry, bookmark?.takeIf { it.itemKey in scope }, shown, listed)`.
- `data/MusicDb.kt`: `NowPlayingRow` gets `val favourites: String? = null` as its **last** field (the scope's keys
  joined by `'\n'`; null = the whole entry), so the two positional constructors only gain an argument. Room 7,
  `AutoMigration(from = 6, to = 7)` (one nullable `ADD COLUMN`; old rows read null = today), the exported
  `schemas/.../7.json`. A DAO query `clearPartialPositionsOf(entryKey, itemKeys)`.
- `PlaybackService.kt`:
  - `Active` (`:154`) gets `val favourites: Set<String>?` (the scope). `activeFor` (`:672`) and `queueFor` (`:724`) take
    it; `Active.order` becomes `snap.order(entry)` filtered to it. Then `toggleShuffle` (`:1196`), `flipOrder`
    (`:1246`) and `SonosRenderer.reorder` (`sonos/SonosRenderer.kt:292`), the only readers of `a.order`, stay inside
    the hearted items with no change of their own.
  - `playEntry(entryId, favourites: Boolean)` (`:740`): the same-tile check also compares
    `(current.favourites != null) == favourites`. On a scope change it runs `renderer.saveNow(force = true)` before
    `loadForRenderer()`. With `favourites`: the scope = `snap.favourites[entry.key]` ∩ the listed keys, the Storytel
    wait above, `favouritesScope`, the empty case's message, then `coverTapPlan` as today. `Commands.ARG_FAVOURITES`
    (boolean, `playback/PlayerHub.kt:70`), read at `:565`. The service reads the hearts from its own snapshot; the
    bundle carries only the flag.
  - **The other callers of `playEntry`**: `resumeLast` (`:776`, Home's RESUME and the bar on a Spotify entry) passes
    `current.favourites != null`; `restartIfStale` (`:522`, a list <-> context change) passes `false`.
  - `playItem` (`:761`): `val scope = active?.takeIf { it.entry.id == entryId }?.favourites?.takeIf { key in it }`;
    with a scope, `pickPlan` over the scoped order (and the current shuffled queue when shuffled) and the new queue
    keeps the scope; else the whole entry, as today.
  - The scope is carried through `restore` (`:821`, from the row), `adoptedQueue` (`:2086`, the row's; `adoptPlan`
    gets the scoped order), `toRoom` (`:2165-2187`, `a0.favourites` or the row's) and `comeHome` (`:2237`, the order
    filtered to `a0.favourites` before `homePlan`).
  - The row writers store it: `saveBookmark` (`:1710`, its row at `:1714`) and `persistPlace`
    (`playback/LocalRenderer.kt:295`, all three renderers). The flip's `flippedNowPlaying` (`data/MusicDb.kt:146`)
    uses `row.copy` and keeps it.
  - `queueEnded` (`:1661`): `clearPartialPositionsOf(entryKey, a.items' keys)` when scoped, else
    `clearPartialPositions` as today.
  - `publish()` puts the scope in `PlayerHub.State.favourites: Set<String>?`; `nowView` (`ui/UiModel.kt`) copies it
    (live) or the row's (stored) into `NowView.favourites`, for `playSwitch`'s item branch.
- UI:
  - `ui/MusicRoot.kt:105` `playEntry` takes `favourites: Boolean`; `Library`'s `onPlay` (`:273`) passes
    `selected == FAVOURITES_CATEGORY`, search's `EntryHit` (`:220`) false. `Actions.playEntry`
    (`ui/MainActivity.kt:122,170`) puts the flag in the bundle.
  - `playSwitch` (`ui/UiModel.kt:188`) gets `favourites: Set<String>?`. The cover branch runs the wait and
    `favouritesScope` on its `all`; the item branch takes `current?.favourites` when the pick is inside it, as
    `playItem` does. So the held view shows the hearted queue at once.
  - `entryUi` (`ui/UiModel.kt:89-117`) gets `favourites: Set<String>?`, passed by `Library` (`MusicRoot.kt:259`)
    under "Favoritter". With it, `hint` and `overallProgress` use `favouritesScope(...).bookmark` over the entry's
    counted order, and `unplayableNow` runs over the hearted visible items.
- `music/CLAUDE.md`'s Favourites bullet: "a cover tap there plays the entry as usual" becomes this rule.

### 1.4 Tests (JVM)

- `FavouritesTest`: `favouritesScope` keeps the scope's keys in the given order (a flipped order stays flipped);
  the bookmark when it is in the scope, else the first item at its own place (resume) or no bookmark (resume off);
  nothing of the scope in the order -> empty. The Storytel wait: a bookmark on a hearted book that isn't on the
  phone waits; on an unhearted one it doesn't.
- `ui/UiModelTest.kt` (next to its `playSwitch` cases, `:87-92`): a "Favoritter" `playSwitch` shows only the hearted
  items, and on a room only the speaker-playable ones (an entry of only hearted own files is no switch); a pick of a
  hearted row inside a loaded favourites queue stays in it, an unhearted one doesn't; `entryUi` under "Favoritter"
  gives the hint of the scope's start.
- `data/Room6Test.kt`: drop its `version = 6,` line; one more case in the same class: 6 -> 7 adds only
  `now_playing.favourites` (nullable), every other table unchanged, `version = 7,` and `AutoMigration(from = 6, to = 7)`.

## 2. The output and sleep sheets swipe down

### 2.1 Behaviour

- The sheet follows the finger down from anywhere on it (the handle, the title, the rows). On release it closes when
  dragged past a quarter of its height (at least 56 dp), or on a downward fling faster than 400 dp/s. Otherwise it
  springs back (snaps back with animations off, `animationsOn`, `ui/LibraryScreen.kt:111`). It never moves above its
  place. An upward fling never closes it.
- A close is the scrim tap's `onDismiss` (no exit animation, as today). The scrim tap and Back stay. TalkBack gets a
  "dismiss" action on the sheet (its actions menu), besides Back.
- **The output sheet's rows scroll** (`ui/PlayerScreen.kt:404`). Inside them the list scrolls first, as Android's
  sheets do: a drag down with the rows at their top moves the sheet; a drag back up while the sheet is moved takes
  the sheet back first, then scrolls; a release with the sheet moved settles the sheet (the list doesn't fling).
  A fling that scrolls the rows up to their top stops there and never pulls the sheet.
- The volume sliders (`ui/Widgets.kt:337`) claim only horizontal drags and the rows only taps, so a vertical drag on
  them moves the sheet (or the rows).

### 2.2 Code

- New `ui/DragDown.kt` (GPL), shared with §3:
  - pure: `dragCloses(offsetPx, velocityPxPerS, extentPx, density): Boolean` (constants `DRAG_CLOSE_SHARE = 0.25f`,
    `DRAG_CLOSE_MIN_DP = 56`, `DRAG_CLOSE_FLING_DP = 400`), `dragOffset(offset, delta)` (clamped at 0), and the
    nested-scroll rules `preScrollTake(offset, dy)` (an upward delta while moved) and `postScrollTake(dy, source)`
    (a downward leftover of `NestedScrollSource.UserInput`, never of `SideEffect`, foundation 1.8 / BOM 2025.06.01).
  - `@Composable fun Modifier.dragDownToClose(onClose: () -> Unit): Modifier`: an `Animatable` offset, the height
    from `onSizeChanged`, then `draggable(Orientation.Vertical, onDragStopped = settle)`, then
    `nestedScroll(connection)`, then `graphicsLayer { translationY = offset }`. The draggable sits **outside** the
    layer, so its deltas don't shift as the layer moves. The connection: `onPreScroll` and `onPostScroll` use the two
    pure rules; `onPreFling` settles and takes the velocity when the sheet is moved.
- `ui/Widgets.kt:384` `Sheet`: the sheet's `Column` gets `.dragDownToClose(onDismiss)` before its `clip`, and
  `semantics { dismiss { onDismiss(); true } }`. Its `clickable {}` that swallows taps stays.

### 2.3 Tests (JVM)

- `DragDownTest`: past the share closes; a short slow drag springs back; a short fast downward fling closes; an
  upward fling after a long drag doesn't; a small sheet uses the 56 dp floor; `preScrollTake` takes an upward delta
  only while moved (at most the offset); `postScrollTake` takes a drag's downward leftover, never a fling's.

## 3. Drag down on the player returns to the library

### 3.1 Behaviour

- A vertical drag down anywhere on the player follows the finger (the whole player moves down over the plain
  background). Released past §2.1's rule (the extent is the player's height), it is `backToLibrary`: the same as the
  back button, the BackHandler (`ui/MusicRoot.kt:97`) and the edge back gesture, so the carousel lands on the
  playing tile as today. Otherwise it springs back. The library is not drawn under the moving player (§5).
- **Nothing is removed.** The back button, Back and the edge back gesture stay.
- **The seek bar** (`ui/Widgets.kt:276`, `detectHorizontalDragGestures` + taps) and **the episode carousel**
  (`HorizontalPager`, `ui/EpisodeCarousel.kt:160`) claim only horizontal drags. Compose gives a drag to whoever
  passes its own touch slop first: a horizontal scrub or swipe stays theirs; a vertical one is the player's. A drag
  that starts on a button cancels its press, so it never clicks.
- **Volume**: the player screen has no volume control (the room sliders are in the output sheet; the keys are
  Android's). The sheets are siblings over the player (`MusicRoot.kt:149-180`): while one is open its scrim takes
  every touch, so the player's drag can't start under it.
- **Gesture navigation** (the Jelly Star): the back swipe is from the side edges and horizontal; Android takes it
  before the app. The app sets no `systemGestureExclusion` (none in `music/`), so the pager can't eat it, and this
  round adds none. Home is a swipe up from the bottom: the other direction. The shade is pulled from the status
  bar, which is outside our content (`safeDrawing` padding, `MusicRoot.kt:80`).
- TalkBack: the back button stays the way back; the drag adds nothing to read.

### 3.2 Code

- `ui/PlayerScreen.kt:132`: the root `Column` gets `.dragDownToClose(onBack)` (`onBack` is `backToLibrary`,
  `MusicRoot.kt:133`). The List screen is unchanged. `DragDownTest` (§2.3) covers the decision; no test for the wiring.

## 4. Emulator checks for the user

Debug build, the debug feed or the bridge (`docs/testing/emulator.md` §6f), an entry of 5+ items. Swipes are in px:
px = dp × density / 160, from `adb shell wm size` and `adb shell wm density` (the 320x569 dp emulator of §3b: W, H).

1. **Favourites**: in the list, heart items 2 and 4. Library -> "Favoritter": the tile's hint names item 2 (or the
   bookmark if hearted). Tap the cover: two pages; next goes 2 -> 4; at 4's end (repeat off) the queue ends.
   "Alle" -> the same cover: the whole entry starts.
2. Hearts while it plays: unheart the current item - it plays on; heart item 5 - not in this queue.
3. List picks during the favourites queue: a hearted row stays in the favourites (unshuffled and shuffled); an
   unhearted row plays the whole entry from it.
4. Restart: play the favourites queue, pause, `adb shell am force-stop me.vibb.music.debug`, open the app, RESUME from
   the bar (and from Home's card): still only 2 and 4; shuffle on and off - still only those.
5. A resuming podcast: hear half of a non-hearted episode under "Alle", then play the favourites to the end: the
   half-heard episode still shows its progress; "Alle" then starts from the top.
6. Sonos (`scripts/fake-sonos.py`, §6h): "Spill av på" a room during the favourites queue - the room plays only the
   hearted items and advances through them; back to the phone - still only those. An entry of only hearted own
   files on the room: "Kan ikke spilles på Sonos".
7. Storytel (`scripts/fake-storytel.py`, §6i): heart books 1 and 3, only book 1 downloaded. On the phone the tap plays
   book 1 only; moved to a room, books 1 and 3. With the bookmark on book 3 the phone tap waits ("Ikke lastet ned").
8. **Sheets**: open "Sov". A long drag closes it: `adb shell input swipe <x> <y> <x> <y+200dp> 300`. A short slow drag
   springs back: `<y> -> <y+40dp>` in 1000 ms (40 dp/s, under the floor). A quick flick closes: `<y> -> <y+40dp>` in
   40 ms (1000 dp/s). Scrim tap and Back still close it; TalkBack's actions menu offers "dismiss".
9. "Spill av på" with many rows (several fake rooms, or font scale 1.3, §3b): scroll the rows down, then drag down -
   the rows scroll back to their top first, then the sheet moves; a fling up the rows never pulls the sheet. A room
   slider moved sideways never moves the sheet.
10. **Player**: `adb shell input swipe <W/2> <H*0.3> <W/2> <H*0.8> 300` -> the library, on the playing tile. A short
    slow drag springs back. Scrubbing the seek bar and swiping the carousel never drag the player down.
11. On the Jelly Star (gesture navigation): the edge back swipe from the player still goes to the library, also when
    started over the carousel; swiping up from the bottom still goes Home.

## 5. Possible widenings (not in scope)

- Draw the library under the dragged player (Spotify's look; both screens composed). The same drag on the List screen.
- A "Favoritter" tile hint of its own ("3 favoritter"); a same-scope tap that picks up hearts added since.
- Option B (design 50's mix tile across entries), as its own design - NRK/RSS and Storytel first.

## 6. Open questions (defaults written in; the user can flip either)

1. **The list under a favourites queue** shows the whole entry with its hearts; a pick of a hearted row stays in the
   favourites queue (from that row), a pick of an unhearted row plays the whole entry from that row.
2. **"Episode N av M"** keeps the entry's numbering under a favourites queue (the dots show how many are queued).

## QA fixes (`docs/review/qa-r8-design.md`)

- R8-1: the scope is the hearts ∩ `snap.listed`, never `FavouritesScope.keys` - §1.2 first bullet, §1.3 KDoc.
- R8-2: the Storytel wait uses the bookmark only when hearted, before the scope - §1.1, §1.3, one `FavouritesTest` case.
- R8-3: `resumeLast` and `restartIfStale` named with their flags - §1.3.
- R8-4: a pick inside the loaded scope stays in it (the decided default) - §1.1 list, §1.3 `playItem`/`playSwitch`/hub.
- R8-5: the "Favoritter" tile's hint, progress and dimming from the scope - §1.1, §1.3 `entryUi`, `UiModelTest`.
- R8-6: a forced save before the scope change's load - §1.1, §1.3 `playEntry`.
- R8-7: the end clears `a.items`' partial positions; one bookmark per entry said - §1.1, §1.3 `queueEnded`.
- R8-8: the field appended last with a default; the 6 -> 7 case in `Room6Test`, its `version = 6,` line dropped - §1.3, §1.4.
- R8-9: fling 400 dp/s; check 8's swipes in dp - §2.1, §2.2, §4.8.
- R8-10: `semantics { dismiss }` on `Sheet` - §2.1, §2.2.
- R8-11: the short-lived old pages on a scope change accepted - §1.1.
- R8-12: `UiModelTest` instead of `SwitchViewTest`; no `dragOffset` test; line drifts fixed - §1.3, §1.4, §2.3.
