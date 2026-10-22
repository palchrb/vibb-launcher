# QA of design r8 (music small items) - design review

Reviewed `docs/design/r8-music-small-items.md` against main at e3bbb593. I checked every file:line in the note. They hold,
with small drifts: `saveBookmark` is at `:1710` (`:1714` is its row), `comeHome` at `:2237` (its `homePlan` at `:2264`),
and the flip's `row.copy` is `flippedNowPlaying` (`data/MusicDb.kt:146`), not `Store.setFlip`.

Checked and sound:
- `activeFor`/`queueFor` have six callers (`:757 :768 :828 :2090 :2188 :2267`), and the note names all six.
- `persistPlace` (`LocalRenderer.kt:295`) is the one row writer for all three renderers, plus `saveBookmark`.
- `Active.order` is read only by `toggleShuffle`, `flipOrder` and `SonosRenderer.reorder`, so filtering it keeps all
  three inside the hearts. The list reads `snap.order`, not `Active.order`.
- The room controller advances through `a.items` (`SonosRenderer.kt:288`).
- The sheets are siblings of the player in `MusicRoot.kt:149-180`, not children, so the scrim does take every touch.
- No `systemGestureExclusion` in `music/`, and the player has no other vertical gesture.

## Findings

**R8-1 (Medium) - What is stored as the scope contradicts itself.** §1.2 (Storytel) says the scope is stored as the
hearted keys, so a room gets every hearted book and the phone the downloaded ones. §1.3 stores "the scope's keys", i.e.
`FavouritesScope.keys` = `order` filtered to the hearts, and the service's `order` is the renderer's view. Built as
§1.3 says:
- Phone -> room for Storytel (`toRoom`'s re-plan `:2172-2175` filtered to the phone's keys) plays only the downloaded
  hearted books and ends early. That is the bug design 26b §5.6 fixed there.
- A row written on a room and restored on the phone has lost the hearted own files and NRK HLS items.

Fix to the note: `Active.favourites` and `NowPlayingRow.favourites` = the hearts at the tap, intersected with the
entry's listed keys (`snap.listed`, the same in both views), never `FavouritesScope.keys`. `FavouritesScope.keys` is
only the plan's order in this renderer. Say so in §1.3 and in the KDoc of `favouritesScope`.

**R8-2 (Medium) - Storytel's "wait for the bookmark's book" is lost under favourites.** The bookmark is on a hearted book
that isn't on the phone. That book is not in `keys` (the phone's view), so `favouritesScope` replaces the bookmark with
the first downloaded hearted book, and the phone starts another book. That breaks 26b §5.6 and qa-26b-step4b-code M1,
which §1.2 means to keep. The `awaitsBook` check at `:755` (and `UiModel.kt:191`) runs before the scope and reads the
raw bookmark, so built literally it errs the other way: it waits on an unhearted book. Fix: both places call
`coverTapAwaitsBook(entry, bookmark?.takeIf { it.itemKey in hearted }, shown, listed)` before `favouritesScope`. Add one
JVM case: the bookmark on a hearted book that isn't on the phone waits; on an unhearted one it doesn't.

**R8-3 (Medium) - The other two callers of `playEntry` are not named.** `resumeLast` calls
`playEntry(current.entry.id)` for every Spotify entry (`:776`, Home's RESUME and the bar). With the new same-tile
check, a `false` flag turns RESUME of a loaded Spotify favourites queue into a restart of the whole entry.
`restartIfStale` (`:522`) restarts an entry whose mode changed (list <-> context). There a scoped restart over a
context finds no hearts, says "Ikke lastet ned" and plays nothing. Fix: `resumeLast` passes `current.favourites != null`;
`restartIfStale` passes `false`. Add both to §1.3's list.

**R8-4 (Medium) - A pick from the shuffled list leaves the hearted queue.** Shuffled, the list shows the queue
(`listOrder`, `UiModel.kt:288`). `playItem` then calls `pickPlan(whole order, pick, shuffleQueue = the hearted
subset)`. The sets differ, so it falls to `shuffledWithFirst(order, …)` (`Queue.kt:127-131`): the kid taps one of 3
rows and gets the whole entry of 40, shuffled. Fix (it is also my recommendation for judgment call 1 below): `playItem`
keeps the loaded queue's scope when the pick is inside it, `active?.takeIf { it.entry.id == entryId
}?.favourites?.takeIf { key in it }`, and runs `pickPlan` over the scoped order. A pick outside the scope plays the
whole entry. `playSwitch`'s item branch mirrors this.

**R8-5 (Medium) - The "Favoritter" tiles still describe the whole entry.** `entryUi` (`UiModel.kt:97-117`) builds the hint,
the progress line and the dimming from the whole entry and its bookmark. So the tile says "Podkast · episode 7 av 40"
with a progress bar while the tap starts episode 2. It also dims, or doesn't, by items that aren't in the scope (all
hearted items needing Wi-Fi, the rest downloaded: not dimmed, and the tap says "Ikke lastet ned"). For a kid the hint is
the tile's promise. Fix: `entryUi` takes the hearts when `selected == FAVOURITES_CATEGORY`
(`MusicRoot.kt:259`). `hint` and `overallProgress` use `favouritesScope(...).bookmark`; the numbering stays the
entry's, consistent with call 2. `unplayableNow` runs over the hearted items. One `UiModelTest` case.

**R8-6 (Low) - "The place was saved first" doesn't hold.** `playEntry` loads its snapshot (`:753`) before `start`'s
`saveNow` (`:853`). Only the Spotify branch saves first, with force (`:750`), and an unforced save is async on the
writer (`LocalRenderer.kt:300`). So a scope change on the loaded entry can plan from a bookmark up to 15 s old (the
save tick). Fix: on a scope change, `renderer.saveNow(force = true)` before `loadForRenderer()`.

**R8-7 (Low) - Which keys the queue's end clears.** If the scope is the hearted set (R8-1), clearing "the scope's" partial
positions also wipes a hearted book that was heard halfway on a room but never in this phone run. Fix: clear
`a.items`' keys, the queue that actually played. Also say in §1.1 that the entry has one bookmark: a favourites run
moves it, and its end drops it, so the next "Alle" tap starts from the top. The half-heard episode keeps its own place,
and a pick resumes it.

**R8-8 (Low) - Room 7 test details.**
- `Room6Test.kt:28` asserts `db.contains("version = 6,")`, which fails at version 7. Name it: drop that line, and
  `Room7Test` asserts `version = 7,` and `AutoMigration(from = 6, to = 7)`.
- Append `favourites: String? = null` as `NowPlayingRow`'s last field, so the two positional constructors
  (`LocalRenderer.kt:295`, `PlaybackService.kt:1714`) still compile and only gain the argument.
- The auto migration of one nullable column is a plain `ADD COLUMN`; the existing `Room6Test` pattern compares the
  rest. Leaner still: put the 6->7 check into the same class.

**R8-9 (Low) - The fling number and check 7 don't fit together.**
- On the user's 320x569 dp emulator, check 7's "quick flick" (0.62H -> 0.70H in 60 ms) is about 46 dp at about
  760 dp/s: under the 56 dp floor and under 1000 dp/s, so it springs back and the check fails as written.
- 1000 dp/s is also stiff for a kid. Compose Material 3's sheet uses, as far as I recall, 56 dp of travel and
  125 dp/s, and the user asked for "as Android's sheets do".
- Fix: lower `DRAG_CLOSE_FLING_DP`, for example to 400. The slow check (about 40 dp/s) still springs back. Then give
  check 7 its swipes in dp (about 40 dp in 40 ms), worked out from `wm size` and `wm density`.

**R8-10 (Low) - TalkBack has no way to close a sheet except system Back.** The scrim has no label. Fix: one line,
`semantics { dismiss { onDismiss(); true } }` on `Sheet`'s Column, so TalkBack's actions menu offers it. The player
needs nothing: its back button is the way back, as §3.1 says.

**R8-11 (Nit) - A scope change on the loaded entry shows no held view.** The cover tap's request has `itemKey` null and
the player already shows that entry, so `requestStands` counts it as reached at once (`Switch.kt:92`). The old
40-page carousel stays until the service publishes, then the pager restarts (`pagerKey`). That lasts a few hundred
ms. Fix: say it is accepted.

**R8-12 (Nit) - Test names.** `SwitchViewTest` doesn't exist: the `playSwitch` cases live in `ui/UiModelTest.kt:87-92`,
so add to that file. In `DragDownTest`, the `dragOffset` clamp needs no test. The pre/post take rules are worth
keeping because they pin the sign and the source (`NestedScrollSource.UserInput` vs `SideEffect` in foundation 1.8,
BOM 2025.06.01).

## The two judgment calls (to recommend, not decide)

1. **The list shows the whole entry; a pick plays the whole entry.**
   - Showing the whole entry is sound: the list is the only place to heart more.
   - Playing the whole entry on every pick would confuse a kid: tap a hearted row from inside the favourites queue and
     it silently turns into all 40. Shuffled, it is already a bug (R8-4).
   - Recommend R8-4's rule: a pick of a hearted row keeps the favourites queue, a pick of an unhearted row plays the
     whole entry.
2. **"Episode N av M" keeps the entry's numbering.** Sound. The number names the episode ("Episode 7"), and the dots
   show how many are queued. A room's speaker-playable queue already counts this way, and with R8-5 the tile agrees.
   Recommend keeping it.

## Tests

The pure seam `favouritesScope` is right. Add one case to it: R8-2's await. The risky parts are service wiring with no
JVM seam (the same-tile check, the six re-plan sites, restore). Check §4's points 1-6 carry those, so a matrix of unit
tests would add little. Add to §4:
- a list pick of a hearted row, shuffled and unshuffled (R8-4);
- RESUME from the bar after a force-stop on a favourites queue (point 3 covers part of it);
- the "Favoritter" tile hint (R8-5).

## Not in scope (proposals)

- A "Favoritter" tile hint of its own ("3 favoritter") instead of the episode line.
- The library drawn under the dragged player (§5 already).
- A "Favoritter" tap on the loaded favourites queue that picks up hearts added since. Today it unpauses the old queue;
  that is consistent, but a kid who just hearted a 4th item may expect it.
