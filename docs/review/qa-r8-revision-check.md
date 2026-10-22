# Revision check of design r8 against qa-r8-design.md

Checked `docs/design/r8-music-small-items.md` (the revision) against `qa-r8-design.md`, and the new file:line names against
`music/app/src/main/java/me/vibb/music/`. All twelve findings are in the note as the QA asked. Two small contradictions and
one missed test remain.

## Findings

- R8-1 resolved. §1.2 first bullet makes the scope the hearts ∩ `snap.listed`, never the renderer's subset. §1.3's KDoc
  says `keys` is only the plan's order. `Active.favourites`, `State.favourites`, `NowView.favourites` and the row column all
  hold that scope. (Wording: the row's "the scope's keys" can be misread as `FavouritesScope.keys`; say "the scope".)
- R8-2 resolved. §1.1 and §1.3 run `coverTapAwaitsBook(entry, bookmark?.takeIf { it.itemKey in scope }, ...)` before
  `favouritesScope`, in both `playEntry` and `playSwitch`. The `FavouritesTest` case is named. The takeIf lives in the
  callers, so that case pins `coverTapAwaitsBook`'s input, not the wiring; fine, say so in the brief.
- R8-3 resolved. §1.3 "The other callers of `playEntry`" names `resumeLast` (`current.favourites != null`, :776 true) and
  `restartIfStale` (`false`, :522 true).
- R8-4 resolved in §1.3 (`playItem`, `playSwitch` item branch, hub state) and tests; one wording problem, see N1.
- R8-5 resolved in §1.1 and §1.3 `entryUi`, plus a `UiModelTest` case. One gap, see N2.
- R8-6 resolved. §1.1 and §1.3: `renderer.saveNow(force = true)` on a scope change, before `loadForRenderer()`;
  `Renderer.saveNow(force)` exists for all renderers.
- R8-7 resolved. `queueEnded` clears `a.items`' keys when scoped (§1.3); §1.1 has "One bookmark per entry". The new DAO
  query `clearPartialPositionsOf` is named next to the existing `clearPartialPositions` (MusicDb.kt:328).
- R8-8 mostly. Field appended last, `Room6Test` line dropped, 6 -> 7 case in the same class. But a second assertion of
  `version = 6,` exists, see N3.
- R8-9 resolved. 400 dp/s in §2.1 and §2.2; check 8 now has 40 dp in 40 ms (quick, 1000 dp/s) and 40 dp in 1000 ms (slow,
  40 dp/s, under the 56 dp floor). The renumbering from 7 to 8 is consistent.
- R8-10 resolved. `semantics { dismiss { onDismiss(); true } }` on `Sheet`'s Column in §2.1 and §2.2; the Column is at
  `ui/Widgets.kt:387-393`.
- R8-11 resolved. Accepted in the §1.1 same-tile bullet (R8-11 named).
- R8-12 resolved. `ui/UiModelTest.kt` (:87-92 is the `playSwitch` case); no `dragOffset` test; pre/post take rules kept. Line
  drifts: `saveBookmark` :1710 and row :1714, `comeHome` :2237 and `homePlan` :2264, `flippedNowPlaying` MusicDb.kt:146 all hold.

## New problems

- N1 (Low) The list-pick rule is stated two ways. §1.1, §4.3 and §6.1 say "a pick of a hearted row stays in the queue";
  §1.3 `playItem`/`playSwitch` test `key in Active.favourites`, the hearts at the tap. They differ after a heart change
  while it plays (a row hearted since the tap is not in the queue, so it plays the whole entry; a row unhearted since still
  stays in). Fix: word §1.1/§6.1 as "a row in the loaded queue's scope" ("hearted when the queue was made") and keep
  §1.3. Add that case to the `UiModelTest` item, or leave it out on purpose.
- N2 (Low) `entryUi` (§1.3) runs `favouritesScope` "over the entry's counted order". `countedOrder` for Storytel is the
  series' reading order (LibraryView.kt:39), not the play order. The scope's first hearted item must come from the play
  order (`snap.order(entry)`, the order `coverTapPlan` uses), or the tile promises another book than the tap starts. Fix:
  call `favouritesScope` over the play-order keys, then use `countedOrder` only for the number and `of` (and `started` /
  `overallProgress`). The `duration` lookup in `entryUi` (:96) must also use the scope's bookmark.
- N3 (Medium, a test the plan breaks) `OfflineTest.kt:334` also asserts `db.contains("version = 6,")` (the Room 4
  schema test), so it fails at version 7. §1.4 names only `Room6Test`. Fix: add `OfflineTest.kt:334` to §1.4 (change it to
  `version = 7,` or drop it).
- N4 (Nit) Two line drifts: `playEntry`'s `awaitsBook` is at `:753` (the note says `:755`, which is `val shuffle`);
  `playSwitch`'s `coverTapAwaitsBook` is at `UiModel.kt:193` (the note says `:191`). Both were in the QA too.
- N5 (Nit) `ui/SwitchViewTest.kt` does exist (R8-12 said it doesn't); its cases are about the held view, so adding the
  `playSwitch` cases to `UiModelTest.kt` is still the right place. No change needed.

## Spot-checks that hold
`Commands.ARG_ENTRY` is at `PlayerHub.kt:70`. `PLAY_ENTRY` is read at `PlaybackService.kt:565`. `MusicRoot.kt` :105, :220,
:259, :273 and `MainActivity.kt` :122, :170 match. `Snapshot.favourites: Map<String, Set<String>>` (Snapshot.kt:111) and
`Snapshot.listed` exist. `NowPlayingRow` has only the two positional constructors in main (`LocalRenderer.kt:295`,
`PlaybackService.kt:1714`) and tests that pass 8 arguments, so the appended default field compiles. `GuardsTest:206-209`
counts `NowPlayingRow(` once in the service and checks only `contains`, so the added argument passes.
