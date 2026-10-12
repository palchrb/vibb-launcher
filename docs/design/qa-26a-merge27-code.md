# QA (code): the merge of main with design 27 into `music-1b-sonos` (2026-10-12)

Reviewed: `89b78b5` (the merge of `origin/main` at `620e578` into `39b59dd`, with the conflict resolution and the
adaptation inside it) and `4b24709` (26a's section "Merge of main with design 27", the Release note, PLAN.md). Read
against root and `music/CLAUDE.md`, design 26a §2.3, §2.5, §3, §4, §9 and both merge sections, design 27,
`qa-27-code.md` (Fixes, Coordination), `qa-26a-merge-code.md`, design 21's own-files-on-Sonos decision, and the
Storytel branch (`origin/music-2-storytel` at `7ad8b26`) for the numbering seam. No product code changed here.

**Separating the adaptation.** `git merge-tree --write-tree 39b59dd 620e578` gives tree `d7ea66f` with conflicts in
eight files (`music/CLAUDE.md`, `Switch.kt`, `PlaybackService.kt`, `PlayerHub.kt`, `MusicRoot.kt`, `PlayerScreen.kt`,
`FitTest`, `SwitchViewTest`). `git diff d7ea66f 89b78b5` (19 files, +216 -162) is the resolution plus the adaptation;
every hunk was read. Beyond the conflict hunks it adds: `Renderer.itemAt`/`replaceItem` (+ both implementations), the
`exo` -> `renderer` rewrites in `refreshArt`/`sendWantArt`/`skipTo`, `SKIP_TO` under `switching` and in `MOVES`,
`wantArtSoon`/`refreshArt` in our `onItemChanged`, `onRoom` through `toIndex`/`swipeSwitch`, `playerNumberOf`, the
new tests (`GuardsTest`, `SwitchTest`, `SwitchViewTest`, `PlayBodyTest`, `EpisodeCarouselTest`'s pin, the launcher's
`MusicSuspensionGuardTest`), and the 0071 / `server/CLAUDE.md` wording.

## Runs (this worktree, 2026-10-12)

- `server/`: `cargo test` **621 passed, 0 failed** (local Rust; CI pins 1.99.0; `parent_pin` passed this run);
  `cargo fmt --check` clean.
- `music/`: `./gradlew testDebugUnitTest --rerun lintDebug -PwarningsAsErrors=true` green: **450 tests, 0 failures**;
  lint 0 errors, 40 warnings (as 26a says).
- `python3 -I -B scripts/fake-sonos.py --selftest` OK.
- `launcher/`: `./gradlew testDebugUnitTest --rerun` **1035 tests, 0 failures**.
- Mutation check (reverted, `git status` clean): `MOVES` without `SKIP_TO`, `swipeSwitch` passing `false` for
  `onRoom`, `requestStands` back to this branch's `current != request.from`, and `SonosRenderer.replaceItem` without
  `sbp.refresh()` - **6 tests red** (`GuardsTest` x2, `SwitchTest` x2, `SwitchViewTest` x2). The new pins bite.
- Not run: the launcher's `assembleRelease` (needs `app/libs/tsnet.aar`; CI builds it).

## Findings

0 High, 0 Medium, 4 Low.

### Low 1 - A room switch can start the wrong item when the late picture swaps `Active.items` mid-switch

- **Where:** `music/app/src/main/java/me/vibb/music/playback/PlaybackService.kt:1451` (`current` = `a0 to
  a0.items[old.currentIndex()]`), `:1457` (`a0.items.indexOf(item)`); `:736` (`refreshArt`: `a.items =
  withItemArt(a.items, item.key, fresh)`).
- **Evidence:** `toRoom` captures the current `PlayItem` object, then suspends (`handOff(old)` up to `HANDOFF_MS`
  15 s room -> room; `load()` on IO either way) and looks the object up again with `indexOf` (data-class equality,
  `artHash` included). `refreshArt` is not under `switching`: it runs from `onItemChanged` and from every
  `Store.coversVersion` change, and when the store has art the queued copy lacks (design 27 QA L7: a listing applied
  mid-queue - the usual case for a new episode whose picture the server's art pass fetched a sweep later) it replaces
  `a.items` with copies. Before design 27 nothing outside `switching` reassigned `a.items` (`toggleShuffle` is a
  custom command), so this is new with the merge.
- **Failure path:** the kid plays a new episode X (index 4) on the phone; a sync applies the listing that gives X its
  picture and copies the cover; the kid picks "Stua" at that moment. `refreshArt` lands inside `toRoom`'s window,
  `indexOf(item)` is -1, `PlayBodies.speakerPlan` coerces the start to 0 and keeps `plan.startPosMs` for step 0 - the
  speaker plays item 0 from X's position, and its measured position is then bookmarked for item 0. Rare (the first
  `refreshArt` after such a listing has to fall into a window of tens of ms, or the hand-off's seconds), recoverable
  by a tap, but it moves another episode's place.
- **Fix:** look the item up by key - `a0.items.indexOfFirst { it.key == item.key }` (keys are unique in a queue; that
  is how `comeHome` and `homePlan` already do it) - or capture `old.currentIndex()` with the item. One `GuardsTest`
  pin (`toRoom` has no `indexOf(item)`).

### Low 2 - 0071's bytes changed again: a database that ran an earlier 0071 refuses to start

- **Where:** `server/migrations/0071_music_sonos_rooms.sql:7-13` (header reworded in `89b78b5`, and before that in
  `39b59dd`).
- **Evidence:** sqlx 0.8.6 stores a SHA-384 of the whole file (`sqlx-core/src/migrate/migration.rs:25`, comments
  included) and `Migrator::run` fails with `VersionMismatch(71)` when an applied version's checksum differs
  (`migrator.rs:173-176`).
- **Failure path:** no released server has 0071 (highest tag `server-v0.19.0`), so production is safe. A dev or
  emulator-loop database (`scripts/dev-rebuild.sh`, `docs/testing/emulator.md` §6h's owed checks) that ran the branch's
  server at `0118da2`/`39b59dd` won't start on this build until its `_sqlx_migrations` row is fixed or the database is
  recreated.
- **Fix:** freeze 0071 now - no more wording in the file; later notes go into `server/CLAUDE.md`/26a. Optionally one
  line in `server/CLAUDE.md`'s migrations paragraph: "a migration's bytes are final once any server ran it (sqlx
  checksums the file)". If the user's test Pi ran an earlier 0071: `UPDATE _sqlx_migrations SET checksum = <new>
  WHERE version = 71` or a fresh database.

### Low 3 - The speaker's missing art isn't in §9 (a deviation from vibb)

- **Where:** `docs/design/26a-sonos.md` §9 (from line 654); §2.5 line 210 still describes vibb's rule ("art =
  `item.art` only when it starts with `http`") as if it applied.
- **Evidence:** vibb's `_sonos_body` sends the item's http art as `upnp:albumArtURI` (§2.3, §2.5). This port never
  has: step 3 passed no art (`ItemRow` had no URL, deviation note 7 at line 829), and since design 27 there is no URL
  to pass (see "Art to the speaker" below). The merge section says so, but the table of deviations - the place a later
  reader checks - doesn't.
- **Fix:** a §9 row: "`albumArtURI` from the item's art URL | no art in the DIDL | the listing carries a hash the
  server serves only to the phone's bearer (design 27 §1.5); see 'Merge of main with design 27' for the path back".

### Low 4 - Two texts still say `replaceMediaItem` for what is now `Renderer.replaceItem`

- **Where:** `PlaybackService.kt:722-725` (`refreshArt`'s KDoc: "(`replaceMediaItem`: the same source, so playback
  goes on)"); `music/CLAUDE.md:160` (design 27's bullet: "the player swaps the current item's artwork in place
  (`replaceMediaItem`)") - two bullets above, the renderer bullet says `Renderer.replaceItem`.
- **Failure path:** a reader of the KDoc reaches for `exo` again; `GuardsTest` would catch the code, not the reader.
- **Fix:** "(`Renderer.replaceItem`: on the phone `replaceMediaItem`, the same source so playback goes on; on a room
  only the session player's item, no SOAP)" in both.

## Verdicts per area

### 1. Nothing of main's lost or duplicated - OK

- Every `+` line of main's `PlaybackService` diff (`444ca58..620e578`) is in `89b78b5` except the ones replaced on
  purpose: the `restoring` mutex and `restoreThenLocked`, and the six `exo.*` calls now through the renderer. None of
  main's removed lines came back (the old `art` map built per queue, the inline `firstPlayable` loop, the `cacheDir`
  small copies). `EpisodeArt.kt`, `ArtPlan.kt`, `EpisodeCarousel.kt`, `MusicDb.kt` and the schemas equal main's;
  `schemas/.../2.json` untouched, no 3.json; `ReapplyListings` intact.
- Carousel, swipe -> `SKIP_TO`, `THEN_SKIP_TO` from `restoreThen`, late art (`refreshArt` -> `replaceItem`),
  `wantArtSoon` at `start` and `onItemChanged`, `sendWantArt`, the `coversVersion` collector in `onCreate`,
  `smallArtMissing`/`hasCopy` (QA L8), `withItemArt` (QA L7): all present, on the renderer.
- Ours: `attach()`, `saveNow()`, `showsPlaying`, rooms, `waiting` (counted before the launch, released in
  `invokeOnCompletion`), `onRoom` at all four switch call sites, the start cap, suspension (`suspendStop`,
  `playAllowed` + `mayPlay`), the call commands: unchanged by the merge (`git diff 39b59dd 89b78b5` touches none of
  their code).
- **`restoring` -> `switching`: a superset.** Every custom command runs inside `switching.withLock` after
  `rendererReady` (`PlaybackService.kt:444-464`). Two quick swipes on a restored queue: RESTORE + THEN_SKIP_TO twice;
  the second waits for the lock, finds the queue loaded (`renderer.itemCount() > 0`) and only skips - main's L10.
  A swipe while a restore runs (the UI already `live`, so a plain `SKIP_TO`): on main it ran at once outside
  `restoring` and was dropped (`exo.mediaItemCount == 0`); now it waits and lands - strictly better. A restore then a
  swipe on a room: `restoreThen` loads the controller paused, `skipTo` -> `goTo` -> `SonosController.stepTo` takes
  the not-playing branch (index and start only, no SOAP). Nothing inside the lock takes it again (`skipTo`, `goTo`,
  `restoreThen` don't), so no self-deadlock.
- **`PlayerHub` commands.** `ALL` = PLAY_ENTRY, PLAY_ITEM, RESUME, RESTORE, SHUFFLE, SLEEP, OUTPUT, ROOMS_REFRESH,
  ROOMS_RESCAN, SKIP_TO; with `LAUNCHER` (CALL_HOLD/RELEASE) that is every branch of the `when`. `SKIP_TO` in `MOVES`,
  not `PLAYS`, is right: `MOVES` only drives the screens' waiting hold, and a swipe starts nothing - on a playing room
  it is a step (SOAP), gated where every step is: `stepTo` checks the controller's `mayPlay` (`playAllowed &&
  roomAllowed`, `SonosRenderer.kt:115`) and otherwise only moves the index (`SonosController.kt:663`). The launcher
  can't send it (not in `LAUNCHER`).

### 2. The adaptation - OK (Low 1 aside)

- `skipTo` counts in `renderer.currentIndex()`/`itemCount()` over `Active.items`; on a room those are the controller's
  items (the speaker-playable queue, `activeFor(..., SONOS)`), the hub's `keys` and so the carousel's pages - one
  index space. `goTo` -> `renderer.seekTo(target, startPos)` -> `stepTo(idx, ms)`: from 0 while effectively PLAYING
  (vibb `sonos_step`), the saved place while paused (26a §4.5, open question 3a (a)). `playable()` is true on a room,
  so no "skip on" there, as next.
- A swipe behind a room switch's hand-off waits on `switching`; `hub.waiting` holds the swipe's `PlayRequest` up to
  `SWITCH_WAIT_MAX_MS`, and the carousel follows the held view (its own `SWITCH_HOLD_MS` pager hold only matters while
  the old index shows, which the held view replaces at once).
- **`replaceItem` on the phone:** `exo.replaceMediaItem` with `old.buildUpon()` keeps the local configuration;
  `ItemMediaSourceFactory` returns plain `ProgressiveMediaSource`/`HlsMediaSource`, whose `canUpdateMediaItem`
  compares URI, cache key and stream keys (qa-27-code checked 1.7.1's bytecode): updated in place, same window uid, no
  transition, no re-prepare, position kept. A glitch-free swap stays a device check (as design 27 left it).
- **`replaceItem` on a room:** replaces the `MediaItem` in `mediaItems` (mediaId checked) and `invalidateState()`s.
  `getState` builds the playlist with uids from `playlistPlan` = the mediaIds, unchanged, so `SimpleBasePlayer` sees
  the same window: no media-item transition, no discontinuity (the position is the poll-anchored supplier), a
  timeline/metadata change -> `onMediaMetadataChanged` -> the session's notification, Home's card and the lock's tile
  get the new `artworkUri`. `showsPlaying`/`playWhenReady` come from the controller, untouched. No SOAP, no
  `controller.` call (pinned). `SonosController.items` keeps the old `PlayItem` copies; nothing compares them with
  `Active.items` except Low 1's `indexOf` in the service.
- **`swipeSwitch(..., onRoom)` / `toIndex(onRoom)`:** the same rule as `skipSwitch` (0 on a playing room, the place
  paused or on the phone). `playerNumberOf` counts in `listOrder` like `playerNumber` (which now delegates to it), so
  the landing page's "N av M" equals the player's once it arrives (pinned in `SwitchViewTest`).
- **The Storytel seam.** Storytel's `playerNumber` (`origin/music-2-storytel`, `UiModel.kt:170-177`) counts
  `countedOrder(entry, listOrder keys, listed keys)`; this branch's shape is two functions over `listOrder`. At that
  merge **Storytel's order wins inside, this branch's shape outside**: one private `countedKeys(snap, now)` =
  `countedOrder(now.entry, listOrder(snap, now).map { it.key }, snap.listed[now.entry.id].orEmpty().map { it.key })`;
  `playerNumberOf(snap, now, item) = countedKeys(snap, now).indexOf(item.key) + 1`; `playerNumber(snap, now) =
  playerNumberOf(snap, now, now.item) to countedKeys(snap, now).size`. `MusicRoot` keeps this branch's call
  (`{ item -> playerNumberOf(s, n, item) }, of`), not Storytel's `number, of` (main's `PlayerScreen` takes
  `numberOf`). Add a Storytel case to `SwitchViewTest`: books 1 and 3 of 5 on the phone, a swipe to book 3 says
  "3 av 5" on the landing page and after arrival. If only `playerNumber` gets `countedOrder`, the carousel says
  "2 av 5" while the player says "3 av 5".
- **Tests red with the rule removed:** yes (the mutation run above). `PlayBodyTest`'s "a hash never reaches the
  DIDL" pins the controller's `PlayBodies.build(e, item, uid, startS)` (no art argument); `build` itself drops a
  non-http art anyway. The launcher's test is in class `MusicSuspensionGuardTest` (file `MusicMediaPlanTest.kt`),
  pinning that neither `MusicSessions` nor `MusicLockPanel` reads `playbackInfo`/`PLAYBACK_TYPE`.

### 3. Art to the speaker - the claim holds; "none for now" is acceptable

- **Verified:** the listing item's `art` is `art_hash` (`music_sweep.rs:738-748, 793`;
  `server/testdata/music_listing.json` has a hash and a null, no URL, byte-equal to main's and to the music copy);
  the library's `cover` is a hash too. The origin URLs stay in the server's database only
  (`music_listings.cover_url`, `music_items.art_url`/`art_from`). The pictures are served at `GET
  /api/devices/music/covers/{hash}` (device bearer, `handlers/music_api.rs`) and to the parent at `/music-covers/{hash}`
  (admin session); neither is reachable by a speaker. The phone's copy is a file. True for every source (NRK, RSS
  `itunes:image`, own files' embedded art).
- **No LAN mechanism exists.** The short-lived signed LAN URL was design 21's QA #12 proposal for own files; the user
  decided on 2026-10-09 that own files on Sonos are **out of 1b** and "come later only if missed" via a LAN-only
  listener (21 "Decisions after QA review"; 26a's header "Out: own files on Sonos"). No such listener or route exists.
- **Acceptable now:** nothing regresses - this branch never sent art (step 3's deviation note 7), the speaker plays
  regardless, and every surface the kid uses (player, carousel, notification, Home, lock tile) has the picture. It is
  a cosmetic gap in the Sonos app only (Low 3: record it in §9).
- **Cheapest correct path (the target for later, not for Storytel's 4a):** the source's own public URLs, not a new
  server listener. The speaker already fetches the audio from the origin; fetching the picture from the origin is the
  same trust and privacy shape, and vibb does exactly that. Concretely:
  - the listing gains an optional per-item `art_src` (http(s)) set **only when `art_hash` is set and frozen with it**
    - not `art_from`, which follows query-only URL churn (`music_sweep.rs:2677-2679`, design 27 QA M1) and would move
    the listing's version on every signed-URL refresh; a stale signed URL just gives the speaker's placeholder;
  - the series fallback: the listing (or library) gains `cover_src` from `music_listings.cover_url`;
  - the phone stores them (Room: a column - do it in the same version bump as the next Room change, not a v4 of its
    own) and `PlayBodies.build` takes `item.artSrc ?: entry.coverSrc` through the same filter as the audio (a
    `publicOnly` entry's private literal, a `lan` entry's tailnet literal drop out);
  - both snapshot pairs regenerated in one commit; old phones ignore the keys (lenient parsing).
  The signed LAN picture URL is only worth it together with own files on Sonos (their art has no origin URL at all) -
  then the one LAN listener serves both.
- **For the Storytel branch's 4a:** the target is design 27's hash path as 26a's merge section says (item `art` hash,
  `wantArt`, the courier; widen `art_in_library` and `art_candidate`). Nothing Sonos-specific is owed in 4a: a book on
  a room gets no art in the DIDL like every other item, and if the `art_src` path above is built later, Storytel's
  public cover URL fits it unchanged.

### 4. Migrations - OK (Low 2)

`0070_music_episode_art.sql` equals main's; 0071's diff against `39b59dd` is the header only. The deploy-order note
is right now: a build after this merge applies 0070 before 0071; the only hazard is a pre-merge build of this branch
(0071 without 0070), which the Release note and `server/CLAUDE.md` forbid. `server/Cargo.toml` 0.24.0 on both sides;
highest tag `server-v0.19.0`. `cargo test` 621.

### 5. Contract files - OK

Both `IMusicBridge.aidl` and `IStorytelCallback.aidl` copies byte-identical and equal to `620e578`'s (this branch
appended nothing). Both `bridge_contract.json` copies byte-identical: main's `want_art` {600, 5} after this branch's
`volume_cap_pct` and `call_commands`, which `BridgeTest`/`MusicPlanTest` still read (green). `music_library`,
`music_listing`, `music_icons` equal the server's (`cmp`); the listing equals main's, the library and
`music_state.json` differ from main only by this branch's `sonos_rooms` (and the library's `version` that follows
it). `MusicPlanTest`'s count of the AIDL methods is green.

### 6. The launcher - OK

`MusicLockPanel`/`MusicSessions` didn't change; they read the one platform session whatever renders it, with no
playback-type filter, and the new test pins that. Main's launcher changes in this range (the SOS yield's narrowed lock
task, `callForCamera`, `AppEnforcer` caching the SOS package, `PERMISSION_CHECKED_FROM_15`, wallpaper ink, the art
courier in `MusicSync`/`MusicStore`/`MusicBridgeService`) don't touch `MusicCalls`, `CALL_HOLD`/`CALL_RELEASE`,
`VolumeCap` or the restrictions bundle: `MusicSync` runs `syncArt` after `syncCovers` and still builds the bundle with
`VolumeCap.pctInForce`; `capChanged` is unchanged.

### 7. Hygiene - OK (Low 4)

`-PwarningsAsErrors=true` compile and lint clean (0 errors, the same 40 warnings). `music/CLAUDE.md` carries both
sides' rules (the renderer axis, threading, rooms, the instant switch with design 27's swipe, episode pictures, Fit).
26a's merge section matches the code; its launcher count "1035 (1012 before; +1)" means +22 from main and +1 here.
PLAN.md names `620e578` and the swipe through the renderer.

## Not worth fixing

- `playerNumber`/`playerNumberOf` sort the entry's items again per call (two to three `listOrder`s per recomposition,
  main computed it once): a few hundred items, negligible.
- A swipe built while a phone -> room switch is still waiting is built for the phone (`onRoom` reads `hub.output`, still
  the phone): the held view shows the item's place, the room then starts it at 0 if it plays. Cosmetic, the same as a
  tap's switch since the first merge, and the player corrects it on arrival.
- A swipe on the view of a cover tap that is still waiting: the cover tap's first item isn't in `via` (its key is
  null), so the player flashes it before the swipe's `SKIP_TO` runs. On main that swipe was dropped outright.
- On a stale poll, `now.playing` (`showsPlaying`) and the controller's effective PLAYING can disagree for a moment, so
  the switch view's 0 vs place can differ from what the speaker does; the player's publish corrects it.
- `SonosController.items` keeps `PlayItem` copies without a late `artHash`: the controller reads no art.
- The adaptation lives inside the merge commit (harder to review; this report separated it with `merge-tree`); no
  change needed now.

## Verdict

The merge is correct and can ship: design 27 is complete on the renderer axis, nothing of either side is lost, the
`switching` lock covers main's `restoring` case and more, the contracts are byte-identical, and the tests (621 / 450 /
1035) are green with pins that fail when the rules are removed. Fix Low 1 (one line + a pin) before the RC; Low 2-4
are text. "No art on the speaker" is right for now; the later path is the source's public URL frozen with the hash,
not a LAN listener.
