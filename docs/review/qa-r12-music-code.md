# Code QA of round r12 (music: plain series cover, skip 15/15, one "Favoritter" tile)

Branch `r12-music` at 840c85d9 against base 6024e396 (27257413 design note, dec85bf3 §1, 35ae07a4 §2, 1058bdee §3,
840c85d9 docs), checked against `docs/design/r12-music-favourites-tile.md` (including "As built"), r8 §1, 26a/26b/26c
where the note points and the user's decisions in the brief. Paths are under `music/app/src/main/java/me/vibb/music/`
unless they say otherwise. Line numbers are the branch's.

**Run** (from the worktree, `flock ~/build.lock`, `--no-daemon --max-workers=2 -PwarningsAsErrors=true`):

- music `assembleDebug testDebugUnitTest`: `BUILD SUCCESSFUL in 2m 21s`, `52 actionable tasks: 52 executed`;
  1204 tests, 0 failures, 0 errors, 0 skipped (counted from the JUnit XML; this includes the music app's `BridgeTest`
  and `ContractFilesTest`). The build left the tree clean: the exported `schemas/me.vibb.music.data.MusicDb/8.json`
  matches what KSP writes.
- launcher `testDebugUnitTest --tests '*MusicPlanTest' --tests '*BridgeTest'`: `BUILD SUCCESSFUL in 1m 36s`,
  `36 actionable tasks: 36 executed`; `com.kidslauncher.mdm.music.MusicPlanTest` 42 tests, 0 failures. `BridgeTest`
  lives only in `music/` (it ran in the suite above). The filter matched no launcher class.
- Contracts: `git diff 6024e396..r12-music -- launcher server music/app/src/main/aidl music/app/src/test/resources` is
  empty. `diff -r` of both apps' `aidl/me/vibb/bridge/` and `cmp` of both `bridge_contract.json` show them identical.
  `music_state.json` and `server/testdata/music_*.json` are untouched, so the listing shape didn't change. No Blocker.

**Verdict**: the run model holds up. `active` is always an ordinary one-entry queue, `mix` rides beside it, and every
cross-run step goes through `mixLater`'s lock plus the `active === a && mix === m` token. I traced the three end paths
(ExoPlayer `ENDED` and its "Til slutten" pause, the web player's list end, the room's `advance()` Stop) and the
duplicate `runEnded` launches they can produce: the token leaves exactly one live. The bookmark rule holds on every
writer reachable from a loaded favourites queue (`persistPlace`, `saveBookmark`, `SonosSuspension`, the Sonos and
Spotify `saveNow`s, `attach`'s forced save). The one exception is deviation 5's fallback (Q-4). Room 8 is a clean
two-column auto migration. §1 and §2 are exactly as written.

There are **two Medium** findings, both about the sleep timer. A run left past an item that can't play cancels the timer
(Q-1). On a room, "Til slutten" stays armed in the renderer after the queue's own run-end hand-off (Q-2). The rest are
Low or Note. The round is not ready to merge until Q-1 and Q-2 are fixed. Both are small, and the emulator pass (§6s)
should then add the two checks in Q-11.

## The implementer's deviations (the note lists twelve, not ten)

1. **`mixPlayableFrom` takes `wrap`, not the repeat.** Accepted. A step always wraps (as `goTo`), and a run's end
   wraps only with repeat ALL (`runEnded`, `mixPastRun`, `mixSpotifyLine` pass `m.repeat == Repeat.ALL`). One exception
   is `restartIfStale` (Q-5).
2. **`MixQueue.all` beside `items`.** Accepted, and needed. `mixToRoom`/`comeHome` re-filter from `all` through
   `restoredMix(m.all, ...)` (`:1197`, `:2765`), so own files and Spotify come back home. `now_playing.mix` stores
   `all` (`Active.storedMix`).
3. **A shuffle toggle cuts the run to the following refs the loaded run already holds.** Accepted. I checked the "does
   it double-play" question with unshuffled [A1 A2 A3 B1], playing A1:
   - On -> [A1 A3 A2 B1] keeps the run [A1 A3 A2].
   - On -> [A1 B1 A2 A3] cuts it to [A1]. After B1, `segmentAt(…, 2)` builds the run [A2 A3] from its own first index.
   - Nothing plays twice going forward. When the next index of the same entry continues the cut run, `segmentAt` walks
     back over the already-played current item, and `startRun`'s `index - run.from` starts past it.
   - Shuffle off rotates `order` to the current item, as an entry's `toggledQueue` does. Items before it come at the
     end, the entry rule's semantics.
4. **A restored queue's unshuffled order isn't stored.** Accepted (`mixQueueOf`, `:1160`). An unhearted ref drops at the
   next shuffle-off, never the current one (`toggledMix`'s `listOf(current) + order`).
5. **Adopt rebuilds the queue only around the adopted item itself.** Accepted as the rule. When it applies, the
   speaker's queue is the stored run (`adoptPlan` over the row's keys), and the run's end hands on to the next run
   through `queueEnded`, so the favourites queue goes on. Its fallback is not right; see Q-4.
6. **`SonosSuspension` writes no bookmark for a mix row.** Accepted, and better than the note
   (`sonos/SonosSuspension.kt:105`).
7. **A pick in the favourites list always starts its run fresh.** Accepted: it matches an entry's list pick (`playItem`
   reloads too). A pick of the *playing* row restarts it from the DB's place. `renderer.saveNow()` (not forced) races
   `loadForRenderer()`'s read, so the restart can be up to one 15 s save older. Same as an entry pick today; Note only.
8. **The held run starts paused at its first item's own place.** Accepted: it follows "every later item starts the same
   way".
9. **`heartsListed`/`favouriteEntryKeys` are gone, and "Favoritter" stays while any heart is listed.** Accepted. The
   tile counts a book that isn't on the phone, and its tap says "Ikke lastet ned" (`favouritesNothingPlays`).
10. **`Actions.skipTo` carries the entry key, and `playItem` takes `inFavourites` = the entry key.** Accepted. Every
    caller is updated (`ui/MusicRoot.kt:118, 135, 204, 232, 240, 251`), and the restore path forwards the bundle
    (`MainActivity.skipTo` -> `RESTORE` with `THEN_SKIP_TO` -> `skipTo(args)`).
11. **The account-line drop is read on `StateChanged` (`mixSpotifyLine`).** Accepted. `SpotifyRenderer.load` clears
    `line` (`spotify/SpotifyRenderer.kt:480`), so a stale line from an earlier queue can't drop Spotify from a new one.
    The room's log line comes at the tap and at "Spill av på".
12. **`PLAY_ITEM` on a stored (not loaded) favourites queue restores it paused first.** Accepted. A stored entry row
    makes `restored.active.mix` null, and the pick falls to `playItem`. The claims taken by that unused restore end at
    `playItem`'s own load.

## Findings

### Q-1 (Medium) A run left past an item that can't play cancels the sleep timer, and the queue plays on

Evidence:
- `playback/PlaybackService.kt:1033-1034`: `mixPastRun` calls `renderer.pause()` without `selfPausing`.
- `:1871`: a `PauseCause.USER` pause with a timer or "Til slutten" on runs `setSleep(0)`.
- `LocalRenderer.pause()` is `exo.pause()` (`PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST` -> `PauseCause.USER`,
  `playback/LocalRenderer.kt:109-115, 170`). `SpotifyRenderer.pause()` is `pause(PauseCause.USER)` (`:614-615`).
- Callers: `onItemChanged`'s move-on at a run's last item (`:1941`) and `onError`'s (`:2089`).

Scenario: the kid sets the sleep sheet to 30 minutes (or "Til slutten") and plays "Favoritter" with mobile data and the
streaming switch off. ExoPlayer advances inside a run to an RSS episode with no copy, so `onItemChanged` finds nothing
after it in the run, and `mixPastRun` pauses (USER). `setSleep(0)` clears the timer, and the launched `mixMoveOn`
starts the next run *playing*. The player shows no timer any more, and the queue plays to its end. The same happens when
a Spotify track at a run's last position is `unplayable` (`onError`). Before r12 the same pause ended the queue, so
cancelling the timer was harmless. Now it is a move-on, and the parent's or kid's "stop in 30 minutes" is lost.

Fix: make it the service's own pause: `selfPausing = true; renderer.pause(); selfPausing = false` in `mixPastRun`, as
`attach` and `callStep` do. Add a `FavouritesTest` source line beside the r12 service case
(`body("private fun mixPastRun(").contains("selfPausing = true")`), and a check in emulator.md §6s (Q-11).

### Q-2 (Medium) On a room, "Til slutten" stays armed in the renderer after a run's held end

Evidence:
- `playback/PlaybackService.kt:2151-2160`: `queueEnded`'s favourites branch calls `sleepAtEndReached()` when `sleepAtEnd`
  is on, but never `renderer.pauseAtItemEnd(null)`. The old first line it moved below, `endSleepAtEnd(QUEUE_ENDED)`,
  did that (`:1751-1755`).
- `sleepAtEndReached` (`:1762`) only flips the service's flag (`sleepAtEndAfter(_, ITEM_END_PAUSE)` is false).
- `SonosController.advance()` with no next item sends Stop and `Ended` without touching `holdAtEnd`
  (`sonos/SonosController.kt:1698-1708`).
- `SonosRenderer.load` resets neither `atEnd`, `controller.holdAtEnd` nor the posted look (`sonos/SonosRenderer.kt:289-296,
  388-421`).

Scenario: on the fake or real room, the kid picks "Til slutten" during the last item of a favourites run. The item runs
off its end before the 3 s ramp's Pause lands, so `advance()` stops and the room sends `Ended`. `queueEnded` holds the
next run paused, and the sleep sheet shows the timer off. The `SonosRenderer` still has `atEnd`, `controller.holdAtEnd`
and the look armed, so after the kid presses play, the next run's first item ramps down and pauses at its end
(`advance()` holds at the next item, or the ramp pauses it) as if "Til slutten" were still on. A second, unasked-for
stop.

The phone isn't affected: its renderer disarms itself before `done()` (`LocalRenderer.kt:100-106`, Spotify's
`finishAtEnd`). The duplicate `runEnded` from ExoPlayer's `ENDED` + `END_OF_MEDIA_ITEM` pair is absorbed by the token.

Fix: in the favourites branch, disarm before the hand-off: `if (held) { renderer.pauseAtItemEnd(null); sleepAtEndReached() }`.
Or let `sleepAtEndReached` call `renderer.pauseAtItemEnd(null)` whenever the flag turned off, which is harmless on the
phone, where it is already disarmed. Add a source pin, and a room "Til slutten" check in §6s (Q-11). The design note's
§3.4 prescribed exactly this order, so the gap is the note's as much as the code's.

### Q-3 (Low) `startRun` commits the run before `start` can refuse it, and a refusal ends the whole queue

Evidence:
- `playback/PlaybackService.kt:1010-1015`: `a.mix`, `m.run` and `m.index` are set before `start`.
- `start` returns early without loading when the run's first item can't play by `Active`'s copies (`:1283-1287`).
- `mixPlayable` (`:930-940`) judges by `snap.hasCopy`, while `activeFor` may drop a stale copy
  (`CacheThrough.queueRows`' `stale`, `:775-785`).
- `mixMoveOn` answers a `false` with `mixEnded()` (`:1055`). `mixGoTo` ignores the result (`:1111`).

Scenario: the next favourite's cached bytes turn out stale (design 30's changed source) while streaming isn't allowed.
`mixPlayable` says yes and `start` says no ("Ikke lastet ned"). At a run's end `mixEnded()` then clears the stored
queue and the player: the whole favourites queue ends on one item instead of moving past it. From a step
(`mixGoTo`), the old run keeps playing while `m.run`/`m.index` point at the target. `mixIndexNow` then falls back to
`m.index`, and the player's "Favoritt N av M" names the target while the old item sounds. Rare: it needs a sync's
eviction or a stale copy between the snapshot and the claim.

Fix: in `startRun`, keep the old `run`/`index` and put them back when `active !== a` after `start`. In `mixMoveOn`,
retry `mixPlayableFrom` from `end.index + 1` (one lap, the same origin) before `mixEnded()`.

### Q-4 (Low) Deviation 5's fallback plays the run as a plain scoped queue: it moves the entry's bookmark and clears at its end

Evidence:
- `playback/PlaybackService.kt:2598-2618`: with `same` false, `a.mix` stays null and `a.favourites = row.scope()`.
- `persistPlace` writes the bookmark while `a.mix == null` (`LocalRenderer.kt:297`), and so does `saveBookmark`.
- At the run's end `queueEnded` takes the non-favourites branch: `deleteBookmark(entryKey)`,
  `clearPartialPositionsOf(entryKey, run keys)` and `clearNowPlaying()` (`:2172-2176`).
- `restore()` behaves the same way for a `mix` that decodes to nothing (`:1245`, `decodeMix` null): the row then
  restores as an r8-style scoped entry queue.

Scenario: hard to reach. `adoptPlan` already needs the playing key in the entry's order, so `same` is false only when the
listing dropped the item between the store and the adopt, or with a corrupted `mix` string. But when it happens, it
breaks the user's rule ("never an entry's bookmark moved"; "the end clears nothing"): the podcast's own bookmark is
deleted, and the half-heard episodes of that run lose their places.

Fix: when the row has a `mix` (decodable or not), never fall back to an entry queue. Either give `a` a one-run
`MixQueue` of the run's own refs (`MixQueue(refs, refs, false, OFF)`, so the writers and `queueEnded` take the
favourites paths and the end is `mixEnded`), or carry `Active.noBookmark`. Say the result in deviation 5's text.

### Q-5 (Low) A stale Spotify run: RESUME and the tile skip `restartIfStale`, and its move-on ignores repeat

Evidence:
- `playback/PlaybackService.kt:892-893`: `resumeLast` with a mix calls `renderer.play()`, not the session's play.
- `:956-957`: `playFavourites`' same-tile branch does the same.
- Neither passes through `KidPlayer.play`, where `restartIfStale` is checked (`:568`), although the comment at `:891`
  says "a stale run moves on at play, restartIfStale".
- `:592`: `restartIfStale`'s mix branch moves on with `wrap = true` whatever the repeat, through `mixMoveOn`, which says
  "Ikke lastet ned" for the skip (`:1053`).

Scenario: a Spotify playlist entry in list mode is part of a paused favourites queue. The parent switches it to context
mode. Home's RESUME or the tile then plays the old list-mode tracks (still valid tracks, now outside the listing),
while the bar's play would move on. And at the queue's last ref with repeat off, the bar's play wraps the queue to its
first favourite instead of ending.

Fix: in both places, call `restartIfStale()` before `renderer.play()` (it returns true when it moved on). In its mix
branch, pass `wrap = m.repeat == Repeat.ALL` and say nothing for a stale ref (or `CANT_PLAY`).

### Q-6 (Low) The next run on an `ENDED` ExoPlayer plays even when `play` is false

Evidence:
- `playback/PlaybackService.kt:1055`: `mixMoveOn` passes `end.play && playAllowed(suspendedNow())`.
- `start(..., play = false)` -> `LocalRenderer.load` is `setMediaItems` + `prepare` (`playback/LocalRenderer.kt:141-145`).
- A queue that ran into `STATE_ENDED` keeps `playWhenReady = true`, so the prepared run sounds anyway. The held run is
  safe: "Til slutten"'s `END_OF_MEDIA_ITEM` already set `playWhenReady` false.

Scenario: a URL run ends on the phone just as the time rule suspends the app (the window between the launcher's
suspension and the service's own stop). The next URL run starts playing although the code (and `FavouritesTest`'s pin
"the next favourite is never started while suspended") says it loads paused. Narrow, but the pin promises more than
the code does.

Fix: in `mixMoveOn`, when `!play`, pause the current renderer as the service's own pause before `startRun`
(`selfPausing`). Or make `start` set `playWhenReady` false when `play` is false.

### Q-7 (Low) A stored favourites queue whose current item is gone: the bar disappears, and a restart starts over

Evidence:
- `ui/UiModel.kt:378-384`: `nowView`'s stored path uses `refs.indexOf(current)`. When the row's item isn't held (a
  Storytel book heard on a room, not on the phone, or an item the listing dropped), `favouritesView` gets -1 and returns
  null, so there is no bar, no player and no "Liste".
- The service's `restoreMix` (`:1172-1187`) moves on to the next surviving ref (`restoredMix`), so Home's RESUME (and
  `Resumable`) would play a queue the screens don't show.
- When the row's *entry* is gone from the library, `current` is null (`:1173-1174`), and `restoredMix` restarts at the
  queue's first ref instead of after the gone one.

Fix: in `nowView`, use `restoredMix(stored, current) { snap.holds(it) }` for the index, as the service does, so the
screen shows what RESUME plays. In `restoreMix`, when `rowEntry` is null, find the current ref by the row's item key
among `stored` (first match after a ref of a gone entry), or accept and say so.

### Q-8 (Low) `favouritesSwitch` treats a stored queue as a new tap, but no test pins the live-only rule (M10 survives)

Evidence: `ui/UiModel.kt:284` (`current.live` in the same-tile check). Mutation M10 drops `&& current.live`, and
`UiModelTest` stays green.

Scenario: what the code does is right. After a restart the stored favourites queue isn't loaded, so the service's
`playFavourites` builds a new queue and the switch shows its first ref. Nothing guards it, though: dropping the
condition would leave the player on the stored item while the service starts another one (a visible jump).

Fix: one line in the r12 `UiModelTest` case: `favouritesSwitch(mixed, stored, 1_000)` is non-null for the stored view
(`stored` is already built there).

### Q-9 (Note) "Til slutten" on a room mostly ends at the last item's end, not at the next favourite

Evidence: `sonos/SonosRenderer.kt:398-410`: the room's 3 s ramp pauses at about the item's end and calls
`finishAtEnd`. `sleepAtEndReached`'s favourites branch is phone-only (`:1769`).

Behaviour: on a room the usual outcome is the ramp's Pause just before the run's last item ends. The room sits paused
on that item, and play plays its last seconds and then the next run. The `queueEnded` "held" path (Q-2) is the rarer
order. Inside an entry's queue a room behaves the same way, so this is consistent. §6s 10 describes the phone only;
say the room's outcome in §6s 12.

### Q-10 (Note) Small wording and cost points

- A gone entry's refs, and a stale run's, are skipped with "Ikke lastet ned" (`mixMoveOn`'s `end.index != from`,
  `:1053`). The note's §3.9 says "no toast" for a gone entry. Either is fine; make the note and the code agree.
- `favouritesUi`, `Snapshot.favouritesOrder()` and `nowView`'s stored path run on every recomposition of the library or
  root, and each ref costs a `visible(entry)` or `listed` scan, so O(hearts x items). `persistPlace` writes
  `encodeMix(all)` at every 15 s save (about 100 bytes a heart with long RSS keys). Fine for tens of hearts. If hearts
  ever reach the hundreds, `remember` them keyed on the snapshot.
- Hearts order by wall clock (`System.currentTimeMillis()`), so a phone clock set back puts new hearts after older
  ones. Accepted.
- A Room downgrade (8 -> 7) still throws (no `fallbackToDestructiveMigrationOnDowngrade`), as for every earlier
  version. Unchanged policy, noted for completeness.

### Q-11 (Note) Docs

- The note's "As built" lists **twelve** deviations, so the brief's "ten" is off. Deviation 5 should also say what the
  fallback does at the run's end (Q-4).
- emulator.md §6s needs two more checks for Q-1 and Q-2:
  - 7b: the 30-minute timer on, Wi-Fi off and mobile data on, a favourites run whose last item is an uncopied stream:
    the queue moves on and the timer is still shown and still fires.
  - 12b: on the fake room, "Til slutten" during a run's last item, then play: the next run's first item plays through
    without a second stop.
- music/CLAUDE.md's new bullet, design 39's and 50's "superseded" boxes, r8's box and PLAN's "Built" line read
  correctly against the code. No stale "30 s fram", numbered-cover, `heartsListed`, `ARG_FAVOURITES`, `coverTapScope`
  or `bookmarkIn` reference is left outside `docs/design`/`docs/review` (grep).

## Checked and fine

- **Run boundaries.**
  - `queueEnded`'s favourites branch returns before any `deleteBookmark`/`clearPartialPositions`/`clearNowPlaying`
    (pinned).
  - `mixEnded` clears only `now_playing`, ends "Til slutten" (`endSleepAtEnd(QUEUE_ENDED)`), clears the renderer and
    `Resumable`.
  - `runEnded`, `mixPastRun`, `mixSpotifyLine` and `restartIfStale` all check `active === a && mix === m` inside the
    lock, and `mixMoveOn` checks `mix !== m` again after its IO.
  - ExoPlayer's `ENDED` + `END_OF_MEDIA_ITEM` pair under "Til slutten" launches `runEnded` up to three times. Only the
    first gets past the token.
- **Spotify `onError` at a run's last track** pauses inside the Error (the move-on without a step, `SpotifyRendererTest`'s
  new case), then the launched `mixMoveOn` starts the next run. On `Main.immediate` it runs until `loadForRenderer`'s
  IO, so nothing else is sent inside the Error. I found no silent stall. A failing lap ends at `m.failRunFrom` and then
  `mixEnded` (with "Kan ikke spilles"). `startRun`'s refusal is Q-3.
- **The transport switch** is `start` -> `usePhoneRenderer` -> `attach` per run, today's switch between entries:
  - The old renderer is force-saved (no bookmark for a run) and self-paused; `cancelFade` and `pauseAtItemEnd(null)` put
    its volume back.
  - The web player is `detached()` (its `load` resets `line`, the window and `place`), and ExoPlayer stands down
    (`stop` + `clearMediaItems`, so it holds no focus) after its pause.
  - The session gets the new `KidPlayer`. The minute timer and "Til slutten" are re-posted and re-armed on the new
    renderer.
  - The run's audio attributes, wake mode and modes (`runRepeat`: never ALL) are set in `start`.
  - "Favoritt N av M" lives only in the app's `NowView`. The session's metadata is the playing item's own
    `MediaItem`, so nothing carries over between the two players.
- **Steps.**
  - `KidPlayer`'s next/previous run through `mixLater(moves = true)`, which holds `PlayerHub.State.waiting` until done.
    `restoreThen` runs `mixStep` in place.
  - `mixStep` uses vibb's `nextAction`/`previousAction` at the queue's index. Inside the run it seeks (`seekInRun`,
    with `prepare` from IDLE); outside the run it calls `startRun`, keeping `playWhenReady` and starting from 0 on a
    playing room.
  - `skipTo` is by (entry key, item key). `hasNext/PreviousMediaItem` count the queue.
- **Restore, adopt, the room.**
  - `restore` decodes `mix` first and never returns `NotOnPhone` for a favourites row. A stored ref whose item is gone
    or whose entry left the catalog fails `holds` and drops.
  - `onPlaybackResumption` sets `mix = a.mix` on both its branches (Spotify through `start`).
  - `adoptedQueue` with `same` sets `a.mix`, `m.run` and `runRepeat` on the stored run's `Active`, so the speaker plays
    the run, then `queueEnded` hands on to the next run.
  - `mixToRoom` writes renderer.json for the first held ref before any SOAP, logs the left-out count and keeps the phone
    playing when nothing is held. `comeHome` re-filters from `all` (own files and Spotify back), says "Ikke lastet ned"
    once for a book not on the phone, and passes `play` (`toPhone` gates it with `playAllowed`).
  - `encodeMix`/`decodeMix` round-trip, and a malformed line drops. Unit separator + newline can't occur in server keys
    or Spotify URIs.
- **The bookmark rule.** `persistPlace` (`bookmark = a.mix == null`, `writePlace` skips a null bookmark),
  `saveBookmark` (`takeIf { a.mix == null }`), `SonosSuspension` (`stored?.mix == null`), the Sonos `saveNow` for a
  stale `a` (its own `a.mix`), Spotify's `persistPlace` and `attach`'s forced save. `flippedNowPlaying` returns null
  for a mix row (pinned). The 15 s saves still write the item's own `PositionRow`, and so do `markDone`'s.
- **Same-tile rules.**
  - `playEntry`'s unpause needs `mix == null`. Otherwise it force-saves, and the new entry queue's `start` sets
    `mix = null`.
  - `playFavourites` unpauses only a *loaded* queue. A heart removed while playing changes nothing (the queue is fixed,
    and `mixPlayable` never reads hearts).
  - Zero hearts: no chip (`favouritesTile = order.isNotEmpty()`), and `effectiveCategory` falls back to "Alle".
    `favouritesUi` is null, and the entries list under the chip is empty by construction.
  - `flipOrder` refuses a run (`a.mix != null`).
- **Room 8.**
  - `AutoMigration(7 -> 8)`. `favourites.heartedAt INTEGER NOT NULL DEFAULT 0` sorts after every real time, with ties
    by library order (`sort`, `id`) and then reading order (`MixTest`, mutation M4).
  - `now_playing.mix TEXT` is nullable and last, so the positional constructors still compile.
  - `Room6Test` compares every other table unchanged. `insertFavourite` stays `IGNORE`, so a re-set heart keeps its
    first time.
- **UI.**
  - `favouritesView` builds each page from its own entry. `entryOf`/`categoryOf` feed the carousel's cover and
    fallback, the heart, the list's sub-line ("<entry> · 12:34") and `toIndex`'s place.
  - `pageKey` (entry id + item key) is the lazy key, the Crossfade key and the pager key.
  - `listOrderUi` and the order button are off for the queue, and `playerNumber`/`playerNumberOf` count over the queue.
  - `FavouritesCarousel` uses the cover's own gradient formula on `Palette.card`, the chip's heart in `Palette.peach`
    at `carouselGlyph`, the same 32 dp peach play mark, shadow, dimming and reserved progress and dots room. Calm,
    no motion, nothing that changes with each heart: within the launcher-look rule.
  - Strings: `favourite_of` and `favourites_count` (one/other) in `values/` and `values-nb/`, and `skip_forward`
    "15 sekunder fram" / "Forward 15 seconds".
  - `listShown(FAVOURITES_CATEGORY, …)` only keys the held list's owner (`listArtAfter`), so -1 is harmless.
- **§1 and §2.**
  - `rowBadge` is pure and used once. The pictured branch draws no content, and the plain branch's `number!!` is safe
    there.
  - §2's table is complete: `SKIP_FORWARD_MS = 15_000L`, `skipSeconds` on both buttons, both strings, `TransportTest`'s
    boundary moved to 590 s -> 599 s, `FitTest`'s comment and music/CLAUDE.md. There is still no media-session seek
    increment anywhere.

## Mutations (each reverted; the named test class only)

| # | Mutation | Test class | Result |
|---|---|---|---|
| M1 | `rowBadge`: a pictured row without own art gets its number again (`pictures && artHash != null -> null`) | LibraryViewTest | caught |
| M2 | `skipSeconds`: `ms / 100` | TransportTest | caught |
| M3 | `mixPlayableFrom`: the origin check dropped | MixTest | caught |
| M4 | `mixOrder`: oldest heart first (`compareBy` for `compareByDescending`) | MixTest | caught |
| M5 | `toIndex`: the playing entry's place, not the target's own entry's | UiModelTest | caught |
| M6 | `favouritesSwitch`: the item known only when shuffled | UiModelTest | caught |
| M7 | `decodeMix`: an empty item key accepted | MixTest | caught |
| M8 | `mixPlayableFrom`: wraps even with `wrap = false` | MixTest | caught |
| M9 | `restoredMix`: a surviving current loses its stored place (`same = false`) | MixTest | caught |
| M10 | `favouritesSwitch`: a stored (not live) favourites view counted as the same tile | UiModelTest | **survives** (Q-8) |
| M11 | `encodeMix`: another separator than `decodeMix` reads | MixTest | caught |

10 of 11 caught. The survivor is Q-8's one-line gap.
