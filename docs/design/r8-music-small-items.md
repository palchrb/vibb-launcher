# Music round 8 - small items (proposal)

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
  permutation of the hearted items, as `coverTapPlan` does today.
- **The queue is fixed at the tap**, like any queue. A heart set or cleared while it plays changes nothing in the
  playing queue; the current item plays on even when its heart goes. The next "Favoritter" tap uses the hearts of
  that moment.
- **The same-tile rule** (a tap on the loaded entry unpauses, never restarts) holds when the tap's scope is the
  loaded queue's: a "Favoritter" tap on a loaded favourites queue unpauses it, as does an "Alle" (or category, or
  search) tap on a whole-entry queue. A tap with the other scope is a new choice: a new queue from the rules above
  (the place was saved first, so a resuming entry goes on at the same second when that item is in the new scope).
- **Player, next, previous, the queue.** The carousel pages and the dots are the queue: the hearted items only. Next
  and previous step through them. Repeat all wraps inside them, repeat one repeats the item. With repeat off, the
  last hearted item's natural end is the queue's end ("Til slutten" ends there too). "Episode N av M" keeps today's
  rule (`playerNumber`, `ui/UiModel.kt:302`): unshuffled, the entry's own numbering ("Episode 7 av 40"); shuffled,
  the queue's. A room's speaker-playable queue already counts this way (`UiModel.kt:288-297`).
- **The list** (Liste) is unchanged. Unshuffled it shows the whole entry with its hearts, so the kid can heart more.
  A pick from it plays the whole entry from that row (`pickPlan`, as today). Shuffled, it shows the queue.
- **The queue's end** (vibb `clear_state`, `queueEnded`, `playback/PlaybackService.kt:1661`) drops the bookmark as
  today, but clears the partial positions **of the queue's scope only**: an episode half heard under "Alle" keeps its
  place when the favourites run ends.
- **After a restart**: the stored queue comes back paused with its scope (§1.2's new column). Shuffle, the order flip,
  the end and a room hand-over still keep to the hearted items.

### 1.2 Per play path

The queue the service builds is just a list of keys (`QueuePlan`). Every renderer already plays "the items it was
given" (a room's queue is a subset today). So the change is building the subset and keeping it on rebuilds.

- **Phone, Media3 (own files, NRK, RSS).** `start` (`PlaybackService.kt:852`) loads the subset. An item that can't play
  now is skipped as today ("Ikke lastet ned"). Own files are phone-only and play here only.
- **Sonos room (design 26a).** `queueFor` (`:724`) drops what a speaker can't play from the subset. `SonosController`
  sends URL items one at a time and advances through `a.items`, which is the subset. A favourites queue of only own
  files says "Kan ikke spilles på Sonos" and nothing moves (the existing path). Phone to room (`toRoom`, `:2148`)
  already keeps `a0.items`. **Two re-plans read the whole order today and must take the scope**: `comeHome`'s
  `homePlan(order, …)` (`:2258-2264`) and `toRoom`'s Storytel re-plan over the remote order (`:2172-2175`).
- **Storytel (a book is one item).** The phone's view holds only downloaded books, a room's every book. The scope is
  stored as the hearted keys (not the queue's keys), so a room gets every hearted book and the phone the hearted
  downloaded ones. The "wait for the bookmark's book" rule (`coverTapAwaitsBook`, `logic/Storytel.kt:145`) uses the
  bookmark only when that book is hearted; otherwise the first hearted book on the phone starts.
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
- **A hearted item gone from the entry.** At the tap, only hearted items in the entry's current order count (a heart
  on an item the listing dropped is ignored until `Sync` prunes it). While a queue plays, an item that leaves the
  listing is handled as for any queue today.

### 1.3 Code

- `logic/Favourites.kt`: one pure function both the service and the screens call, so they can't disagree:
  ```kotlin
  data class FavouritesScope(val keys: List<String>, val bookmark: Bookmark?)
  fun favouritesScope(order: List<String>, hearted: Set<String>, bookmark: Bookmark?, resume: Boolean,
                      position: (String) -> ItemPosition?): FavouritesScope
  ```
  `keys` = `order` filtered to `hearted`. `bookmark` = the entry's bookmark when its item is in `keys`, else (resume
  on) `Bookmark(keys.first(), resumeFrom(position(it)))`, else null. The caller passes the result to the unchanged
  `coverTapPlan` (`logic/Queue.kt`) and `coverTapStart` (`logic/Switch.kt:65`).
- `data/MusicDb.kt`: `NowPlayingRow.favourites: String?` (the scope's keys joined by `'\n'`; null = the whole entry).
  Room 7, `AutoMigration(from = 6, to = 7)` (a nullable column; existing rows read null = today's behaviour), the
  exported `schemas/.../7.json`. A DAO query `clearPartialPositionsOf(entryKey, itemKeys)`.
- `PlaybackService.kt`:
  - `Active` (`:154`) gets `val favourites: Set<String>?`. `activeFor` (`:672`) and `queueFor` (`:724`) take it;
    `Active.order` becomes `snap.order(entry)` filtered to it. Then `toggleShuffle` (`:1196`), `flipOrder` (`:1246`)
    and `SonosRenderer.reorder` (`sonos/SonosRenderer.kt:292`), which all work on `a.order`, stay inside the hearted
    items with no change of their own.
  - `playEntry(entryId, favourites: Boolean)` (`:740`): the same-tile check also compares
    `(current.favourites != null) == favourites`; with `favourites`, `favouritesScope` over
    `snap.order(entry)` and `snap.favourites[entry.key]`, the empty case's message, then `coverTapPlan` as today.
    `Commands.ARG_FAVOURITES` (boolean, `playback/PlayerHub.kt:70`), read at `:565`. The service reads the hearts from
    its own snapshot; the bundle carries only the flag.
  - The scope is carried through `restore` (`:821`, from the row), `adoptedQueue` (`:2086`, the row's; `adoptPlan` gets
    the scoped order), `toRoom` (`:2165-2187`, `a0.favourites` or the row's) and `comeHome` (`:2258`, the order
    filtered to `a0.favourites` before `homePlan`). `playItem` (`:761`) passes null.
  - The two `NowPlayingRow` writers store it: `saveBookmark` (`:1714`) and `playback/LocalRenderer.kt:295`.
    `Store.setFlip`'s `row.copy` keeps it.
  - `queueEnded` (`:1661`): `clearPartialPositionsOf(entryKey, scope)` when scoped, else `clearPartialPositions` as today.
- UI: `ui/MusicRoot.kt:105` `playEntry` takes `favourites: Boolean`; `Library`'s `onPlay` (`:273`) passes
  `selected == FAVOURITES_CATEGORY`, search's `EntryHit` (`:220`) false. `playSwitch` (`ui/UiModel.kt:188`) gets
  `favourites: Set<String>?` and runs `favouritesScope` on its `all` before the rest, so the held view shows the
  hearted queue at once. `Actions.playEntry` (`ui/MainActivity.kt:122,170`) puts the flag in the bundle.
- `music/CLAUDE.md`'s Favourites bullet: "a cover tap there plays the entry as usual" becomes this rule.

### 1.4 Tests (JVM)

- `FavouritesTest`: `favouritesScope` keeps the hearted keys in the given order (a flipped order stays flipped);
  the bookmark when hearted, else the first hearted item at its own place (resume) or no bookmark (resume off);
  no hearted key in the order -> empty.
- `SwitchViewTest`: a "Favoritter" `playSwitch` shows only the hearted items; on a room only the speaker-playable
  ones (a hearted own file drops out, a hearted own-file-only entry is no switch).
- `Room7Test` (as `data/Room6Test.kt`): 6 -> 7 adds only `now_playing.favourites`, nullable; every other table unchanged.

## 2. The output and sleep sheets swipe down

### 2.1 Behaviour

- The sheet follows the finger down from anywhere on it (the handle, the title, the rows). On release it closes when
  dragged past a quarter of its height (at least 56 dp), or on a downward fling faster than 1000 dp/s. Otherwise it
  springs back (snaps back with animations off, `animationsOn`, `ui/LibraryScreen.kt:111`). It never moves above
  its place. An upward fling never closes it.
- A close is the scrim tap's `onDismiss` (no exit animation, as today). The scrim tap and Back stay as they are.
- **The output sheet's rows scroll** (`ui/PlayerScreen.kt:404`). Inside them, the list scrolls first, as Android's
  sheets do: a drag down with the rows at their top moves the sheet; a drag back up while the sheet is moved takes
  the sheet back first, then scrolls; a release with the sheet moved settles the sheet (the list doesn't fling).
  A fling that scrolls the rows up to their top stops there and never pulls the sheet.
- The volume sliders (`ui/Widgets.kt:337`) and the rows are unchanged: the sliders claim only horizontal
  drags, the rows only taps, so a vertical drag on them moves the sheet (or the rows).

### 2.2 Code

- New `ui/DragDown.kt` (GPL), shared with §3:
  - pure: `dragCloses(offsetPx, velocityPxPerS, extentPx, density): Boolean` (the §2.1 numbers as constants
    `DRAG_CLOSE_SHARE = 0.25f`, `DRAG_CLOSE_MIN_DP = 56`, `DRAG_CLOSE_FLING_DP = 1000`), `dragOffset(offset, delta)`
    (clamped at 0), and the nested-scroll rules `preScrollTake(offset, dy)` (an upward delta while moved) and
    `postScrollTake(dy, fromDrag)` (a downward leftover of a drag, never of a fling).
  - `@Composable fun Modifier.dragDownToClose(onClose: () -> Unit): Modifier`: an `Animatable` offset, the height
    from `onSizeChanged`, then `draggable(Orientation.Vertical, onDragStopped = settle)`, then
    `nestedScroll(connection)`, then `graphicsLayer { translationY = offset }`. The draggable sits **outside** the
    layer, so its deltas don't shift as the layer moves. The connection: `onPreScroll` and `onPostScroll` use the two
    pure rules; `onPreFling` settles and takes the velocity when the sheet is moved.
- `ui/Widgets.kt:384` `Sheet`: the sheet's `Column` gets `.dragDownToClose(onDismiss)` before its `clip`. Its
  `clickable {}` that swallows taps stays.

### 2.3 Tests (JVM)

- `DragDownTest`: past the share closes; a short slow drag springs back; a short fast downward fling closes; an
  upward fling after a long drag doesn't; a small sheet uses the 56 dp floor; `preScrollTake` takes an upward delta
  only while moved (and at most the offset); `postScrollTake` takes a drag's downward leftover, never a fling's.

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
  Android's). While a sheet is open its scrim takes every touch, so the player's drag can't start under it.
- **Gesture navigation** (the Jelly Star): the back swipe is from the side edges and horizontal; Android takes it
  before the app. The app sets no `systemGestureExclusion` (none in `music/`), so the pager can't eat it, and this
  round adds none. Home is a swipe up from the bottom: the other direction. The shade is pulled from the status
  bar, which is outside our content (`safeDrawing` padding, `MusicRoot.kt:80`).
- TalkBack: the back button stays the way back; the drag adds nothing to read.

### 3.2 Code

- `ui/PlayerScreen.kt:132`: the root `Column` gets `.dragDownToClose(onBack)` (`onBack` is `backToLibrary`,
  `MusicRoot.kt:133`). The List screen is unchanged.

### 3.3 Tests

`DragDownTest` (§2.3) covers the decision. No new test for the wiring.

## 4. Emulator checks for the user

Debug build, the debug feed or the bridge (`docs/testing/emulator.md` §6f), an entry of 5+ items. Get the screen
size first: `adb shell wm size` (below: W x H in px; adjust the numbers).

1. **Favourites**: in the list, heart items 2 and 4. Library -> "Favoritter" -> tap the cover. The player shows two
   pages; next goes 2 -> 4; at 4's end (repeat off) the queue ends. "Alle" -> the same cover: the whole entry starts.
2. Hearts while it plays: unheart the current item - it plays on; heart item 5 - not in this queue; the next
   "Favoritter" tap (after another entry) has it.
3. Restart: play the favourites queue, pause, `adb shell am force-stop me.vibb.music.debug`, open the app, play from the
   bar: still only 2 and 4; turn shuffle on and off - still only those.
4. A resuming podcast: hear half of a non-hearted episode under "Alle", then play the favourites to the end: the
   half-heard episode still shows its progress in the list.
5. Sonos (`scripts/fake-sonos.py`, §6h): "Spill av på" a room during the favourites queue - the room plays only the
   hearted items and advances through them; back to the phone - still only those. An entry of own files only, hearted:
   on the room it says "Kan ikke spilles på Sonos".
6. Storytel (`scripts/fake-storytel.py`, §6i): heart books 1 and 3 of a series with only book 1 downloaded; on the
   phone the tap plays book 1 only; on a room books 1 and 3.
7. **Sheets**: open "Sov", drag it down from the handle - it closes: `adb shell input swipe <W/2> <H*0.62> <W/2> <H*0.95> 300`.
   A short slow drag springs back: `adb shell input swipe <W/2> <H*0.62> <W/2> <H*0.66> 600`. A quick flick closes:
   `adb shell input swipe <W/2> <H*0.62> <W/2> <H*0.70> 60`. Scrim tap and Back still close it.
8. "Spill av på" with many rows (several fake rooms, or font scale 1.3, §3b): scroll the rows down, then drag down -
   the rows scroll back to their top first, then the sheet moves; a fling up the rows never pulls the sheet. Move a
   room slider sideways: the sheet doesn't move.
9. **Player**: `adb shell input swipe <W/2> <H*0.3> <W/2> <H*0.8> 300` -> the library, on the playing tile. A short
   drag springs back. Scrub the seek bar sideways and swipe the carousel: no drag-down. A diagonal mostly-vertical drag
   on the seek bar is a drag-down (as in Spotify).
10. On the Jelly Star (gesture navigation): the edge back swipe from the player still goes to the library, also when
    started over the carousel; swiping up from the bottom still goes Home.

## 5. Possible widenings (not in scope)

- Draw the library under the player while it is dragged (Spotify's look); it would need both screens composed.
- The same drag on the List screen back to the player.
- Option B (design 50's mix tile across entries), as its own design - NRK/RSS and Storytel first.

## 6. Open questions

None. Two calls made here, to undo if the user disagrees: the list stays the whole entry (a pick there plays the
whole entry), and "Episode N av M" keeps the entry's numbering under a favourites queue.
