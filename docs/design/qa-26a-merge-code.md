# QA (code): the merge of main into `music-1b-sonos` (2026-10-12)

Reviewed: `0ba355b` (merge of `origin/main` at `444ca58` into `dadea05`), `0118da2` (migration 0065 -> 0071),
`f58703a` (emulator §6g -> §6h), `9f70186` (main's instant switch on the renderer axis), `8890855` (26a's merge
section and Release). Read against root and `music/CLAUDE.md`, design 26a §3, §4, §6 and "Merge of main", main's
`logic/Switch.kt` and its tests, vibb's `pi/daemon.py` (`sonos_step`, 1726-1781). No product code changed here.

## Runs (this worktree, 2026-10-12)

- `server/`: `cargo test` **594 passed, 0 failed** (Rust 1.97 locally; CI pins 1.99.0); `cargo fmt --check` clean.
- `music/`: `./gradlew testDebugUnitTest lintDebug -PwarningsAsErrors=true` green; the tests re-run with `--rerun`:
  **410 tests, 0 failures**; lint 0 errors, 40 warnings (as 26a says).
- `launcher/`: `./gradlew testDebugUnitTest --rerun` **1012 tests, 0 failures**; `compileDebugKotlin
  compileDebugUnitTestKotlin -PwarningsAsErrors=true` green.
- `python3 -I -B scripts/fake-sonos.py --selftest` OK.
- Not run locally: the launcher's `assembleRelease` (L13's `checkReleaseHasTsnet` needs `app/libs/tsnet.aar`). CI
  covers it: `launcher.yml`'s `launcher-build` runs `./.github/actions/build-tsnet` and then
  `./gradlew build -PrequireTsnet=true -PwarningsAsErrors=true` (debug, release, lint, tests), and the merge
  touches `launcher/`, so the job runs.

## Findings

### Medium 1 - The migration gap at 0070 is reserved for a claim nobody made; filled later, it runs without its backup

- **Where:** `server/migrations/0071_music_sonos_rooms.sql:7-9` ("0070 is claimed by design 27's episode art"),
  `docs/design/26a-sonos.md:1572` ("it claims migration 0070") and `:1592-1593`; `server/src/startup.rs:260-270`.
- **Evidence:** design 27 claims no number: `27-music-episode-art.md:62` says `00NN_music_episode_art.sql`,
  "numbered after the final rebase". No branch has a 0070 (`origin/main`, `origin/music-2-storytel` checked).
  `startup::pre_migration_backup` decides "migrations pending" from `MAX(version)` of `_sqlx_migrations` against the
  highest compiled version (`from >= latest` -> no backup). sqlx 0.8.6's `Migrator::run`
  (`sqlx-core/src/migrate/migrator.rs:168-181`) applies every unapplied version, lower ones included.
- **Failure path:** this branch lands, a server with 0071 runs on the Pi; design 27's implementer reads 26a (or the
  migration header) and names theirs `0070_...`. The next server start applies 0070 after 0071, and
  `pre_migration_backup` sees `from = 71 >= latest = 71` and takes **no `pre-migrate-*.db`**: design 22 §6's rule "a
  migration without its backup would leave no way back" is broken without any test or log noticing. (With 0072 nothing
  goes wrong; the hazard is the wording that points at 0070.)
- **Fix:** rename to `0070_music_sonos_rooms.sql` - the next number after main's 0069, as the root rule asks - and
  drop the "claimed by design 27" text from the header and 26a (1572, 1592-1593), with the number in 26a:1645,
  `server/CLAUDE.md:523`, `music.rs:821,914` and PLAN.md:108; or keep 0071 and say in the header and 26a "0070 is never used (skipped, like
  0058-0060)". Either way, design 27 numbers above the highest migration on `main` at its final rebase.

### Low 1 - The screens' use of `hub.waiting` and `onRoom` is not pinned by any test

- **Where:** `music/.../ui/MusicRoot.kt:67`, `:69-71` (the hold and its effect read `hub.waiting`), `:75`, `:87`,
  `:92`, `:103` (`onRoom` into `playSwitch`/`skipSwitch`).
- **Evidence:** `SwitchTest`/`SwitchViewTest` test the pure functions with `waiting`/`onRoom` passed in; `GuardsTest`
  pins the service side (count before `scope.launch`, `invokeOnCompletion`, `publish`, `MOVES`). Nothing reads
  `MusicRoot.kt`: deleting `hub.waiting` from `requestStands(...)` or from `LaunchedEffect`'s keys / `holdEndsAt`, or
  passing no `onRoom`, leaves all 410 tests green - and restores exactly the bug the adaptation fixes (the hold runs
  out into the old item at 3 s on a room).
- **Fix:** a `GuardsTest` (or `SourceScanTest`) pin on MusicRoot: `requestStands(` with `hub.waiting`, `holdEndsAt(`
  with `hub.waiting`, `hub.waiting` among the `LaunchedEffect` keys, `onRoom = hub.output.kind == OutputKind.SONOS` and
  the three `onRoom)` call sites.

### Low 2 - `SWITCH_WAIT_MAX_MS` (20 s) is below the start-up worst case it was sized for

- **Where:** `music/.../logic/Switch.kt:22-30`.
- **Evidence:** the KDoc counts "the look at a recorded Sonos room, up to 4 s" and "a room switch's hand-off, up to
  15 s". A command at a fresh service start waits on `rendererReady`, which waits for `adopt` ->
  `lookAtRoom` -> `SonosTail.join()` (`SonosTail.MAX_MS` = 16 s, the destroyed service's hand-off) and then
  `ADOPT_MS` (4 s), then `load()`: up to ~20 s plus the IO, so the bound can end first.
- **Failure path:** the service was destroyed with a room playing and its tail's Stop is slow (the actor busy); the
  kid opens the app and taps a cover at once. After 20 s the screens show the stored row (the old item) until the
  play lands a moment later. Cosmetic, and only in that narrow case.
- **Fix:** 25 s (and name `SonosTail.MAX_MS + ADOPT_MS` in the KDoc), or accept and say so in the KDoc.

### Low 3 - PLAN.md's status still waits for `music-v0.1.0`

- **Where:** `PLAN.md:102-103` ("its Jelly Star checks and `music-v0.1.0` still to come").
- **Evidence:** phase 1 shipped as `music-v0.0.1` and `music-v0.0.2` (on `22a6265`), which 26a's Release itself says.
- **Fix:** "released as `music-v0.0.2`; its Jelly Star checks still to come".

## Verdicts per area

1. **The merge - clean.** Re-running the merge (`git merge-tree --write-tree dadea05 444ca58`) and comparing with
   `0ba355b`'s tree: the recorded merge differs from git's own auto-merge only in the four conflicted files (root and
   music `CLAUDE.md`, `emulator.md`, `MusicRoot.kt`), so nothing outside the conflicts was edited in the merge
   commit. Each resolution is the union: the root CI paragraph has main's `music/gradle/` + `music.yml` trigger and
   this branch's `fake-sonos.py` + selftest; music "Build and test" has main's verification-metadata sentence, then
   the fake-speaker paragraph; `emulator.md`'s §6g is byte-identical with main's and §6h is byte-identical with this
   branch's old §6g, each once; `MusicRoot.kt` takes main's `position` and this branch's `hub.rendererState`. The
   auto-merged files carry both sides: `PlayerScreen.kt` (main's `PrefetchCovers`, `now.cover`, `now.itemTitle`
   beside the room line), `MainActivity.kt` (main's dark system bars, this branch's `output(key)`/`refreshRooms`/
   `rescanRooms`), `MusicSessions.kt` (main's `VisibleSender.options()`, this branch's `MusicCalls.init`),
   `music.yml` (wrapper validation and the selftest step), `launcher.yml` (main's only; its trigger list covers every
   music file the launcher's tests read - AIDL, manifest, `bridge_contract.json`, `music/gradle/`, `music.yml`).
   `MusicViewModel.kt` was changed by main alone. No dead branch found. Verification metadata: this branch changes no
   Gradle file in `music/` or `launcher/` (`git diff 0c9d6ca dadea05 -- '*.gradle*' '*/gradle/*'` is empty) and adds
   no dependency (`javax.xml`, OkHttp and Media3 were already there), so main's `verification-metadata.xml` needs no
   entry; the local runs resolved and verified everything they touched.
2. **Migration 0071 - correct as a rename, but see Medium 1.** `git diff 0ba355b 0118da2 -M` shows one rename at 77 %
   similarity with only the header comment changed; main's 0065-0069 untouched. Every reference that meant the rooms
   migration says 0071 (`server/CLAUDE.md:523`, `music.rs:821,914`, PLAN.md:108, 26a 1592/1645, design 26); the
   remaining `0065` hits are main's command-receipts migration or historical lines in 26a's step 3c/4 status that
   name the renumbering (1305-1307, 1407). `cargo test` 594 green.
3. **§6g -> §6h - complete on this side.** `music.yml`, `fake-sonos.py`, `DebugFeedReceiver.kt`, root and music
   `CLAUDE.md`, 26a (ten) and 26 point at §6h; main's §6g references (security review, `qa-launcher-security-wave.md`,
   `launcher/CLAUDE.md`, emulator §6g) are untouched and still right. The only old-number leftovers are
   `qa-26a-step1-code.md:5` and `:100`, a review record of the fake; nothing points a reader there for a checklist
   (26a's "Owed before the stable tag" and every step status name §6h), so they can stay.
4. **The adaptation - correct; Low 1 and Low 2.**
   - *Waiting count:* counted after both refusal checks (`playAllowed`, unknown action) and before `scope.launch`,
     released in `invokeOnCompletion`, which runs for a normal end, an exception and a cancel; a `Main.immediate` body
     that finishes inside `launch` gets the handler at once. No `NonCancellable` in the service, so `onDestroy`'s
     `scope.cancel()` releases in place, after `PlayerHub.mutableState.value = State()` (waiting false). The counter
     is per instance and `publish` rebuilds `waiting` from it. No path leaves `waiting` stuck, and `requestStands`
     is bounded by `holdEndsAt` regardless; the `LaunchedEffect` re-keys on `hub.waiting`, so a command that ran after
     3 s ends the hold at once and one still waiting extends it.
   - *`playSwitch`/`skipSwitch` on a room* match the service: cover and pick plans start from the whole play order
     (`coverTapPlan`, `pickPlan` rotated), and `speakerPlan`'s "next kept after the start, wrapping, from 0" equals
     "next in play order, wrapping" for a rotated order. Nothing playable -> no switch (the service emits
     CANT_PLAY_ON_SONOS). A step: `stepTo` playing -> `playIndex(want, 0.0)` (vibb `sonos_step` always 0), not playing
     -> the phone's `startPos` (this branch's documented deviation); `skipSwitch` does the same. Its `now.playing` is
     `showsPlaying`, `stepTo`'s test is `effectiveTransport == PLAYING && mayPlay()`; they differ while stale or
     grouped away, but `stepTo` flips the index synchronously, the hub publishes, and the hold ends on arrival, so the
     difference never shows. The skip target is counted in `now.queue` = the hub's keys = `Active.items`, the list
     `skipNext`/`skipPrevious` count; on a room it is speaker-playable in every state: `queueFor`, `adoptPlan`
     (`SonosLifecycle.kt:67`) and `restore` filter, `lost()` goes home, and the one unfiltered rebuild,
     `toggleShuffle`, can't run on a room (`canShuffle` is OWN-only, and an OWN entry is never speaker-playable).
   - *`playerNumber`* is `listOrder`'s index and size, the same expression `MusicRoot` had inline, now shared by the
     player's own view and a switch.
   - *Tests:* `SwitchTest`'s new case fails if `waiting` is ignored; `SwitchViewTest`'s three fail without the filter,
     the move, the 0, the empty check (a `NoSuchElementException`) or the playing-room 0; `SonosControllerTest`'s
     fails if a step seeks to the resume place or doesn't flip the index before the speaker answers; `GuardsTest`
     pins the service wiring. The UI wiring is unpinned (Low 1).
5. **Main's behaviours vs the renderer axis - clean.** Main changed nothing in `playback/` (`git diff 0c9d6ca 444ca58
   -- music/.../playback` is empty); the only `ExoPlayer` in the app is `LocalRenderer.kt:51`. Design 25's lock tile
   (`MusicLockPanel`) and the Home card read the same `MusicSessions` reading (`sessionPlay(platform state)`): on a
   room they show what §3.2's table publishes (BUFFERING with `playWhenReady` held through the first failed polls
   reads as playing, grouped away/taken over as paused with no position) - the trade 26a made for the FGS, the same on
   both surfaces; `setNowPlaying.playing` stays `showsPlaying`. The security wave's `VisibleSender` only changes the
   send options of the session activity's PendingIntent; `MusicCalls` sends `CALL_HOLD`/`CALL_RELEASE` through the
   platform session's `transportControls.sendCustomAction`, untouched by main (its listeners `VoipCalls`/
   `OngoingCalls` keep their API), and the music app grants them only to a launcher signed like it.
6. **Hygiene - clean.** Warnings-as-errors and lint green (above); 26a's Release is right: server 0.24.0 on both
   sides (`Cargo.toml`), highest server tag `server-v0.19.0`, migration 0071 (Medium 1 may make it 0070),
   `music-v0.2.0-rc.1` -> versionCode 0*1e6 + 2*1e3 + 0 - 1 = 1999 > 2 (`music-v0.0.2`, on `22a6265`), never latest;
   the launcher release before or with the RC. AIDL and `bridge_contract.json` copies are byte-identical (md5).

## For the Storytel merge (`origin/music-2-storytel`, based on 26a step 4 `9ecdad8`)

- `ui/UiModel.kt`: both branches add `playerNumber(snap, now): Pair<Int, Int>` right after `listOrder`, with
  different KDoc and bodies - a textual conflict. **Storytel's body wins** (`countedOrder(entry, listOrder keys,
  readingOrder)` returns the list order for every non-Storytel entry, so it is a superset); keep this branch's KDoc
  sentence about a room's queue. `MusicRoot.kt` will conflict too (Storytel lacks main's switch); the room's
  `onRoom`/`hub.waiting` wiring must survive it (Low 1's pin would catch it). `NowView` gained only a defaulted
  parameter (`itemKnown`), so Storytel's positional test constructors still compile.
- Migrations: Storytel carries `0065_music_sonos_rooms.sql` (follows this branch's rename) and
  `0066_music_storytel.sql`, which collides with main's `0066_lock_glance.sql`; it must become the next number above
  this branch's (0071 or 0070, after Medium 1).

## Not worth fixing

- `skipSwitch`'s `now.playing` vs `stepTo`'s transport test (stale, grouped away): invisible, the index flips
  synchronously and the hold ends on arrival.
- At a cold start `onRoom` is false until adopt finishes (the hub says phone); a tap during the 4 s look that the room
  then plays uses the phone's rule - different only when the start item isn't speaker-playable, and the "player went
  elsewhere" rule ends the hold.
- A destroyed instance's late `waitingMoves(-1)` could overwrite a newer instance's `waiting` until its next
  `publish` - unreachable today (no `NonCancellable`; the cancel completes inside `onDestroy`).
- `toggleShuffle`/`SonosRenderer.reorder` don't apply the speaker filter: unreachable while only OWN entries shuffle.
  If `canShuffle` ever widens, filter there (a step onto an unplayable index snaps back in `failed` and `next` would
  stick on it).
- `SwitchTest`'s `SWITCH_WAIT_MAX_MS > 15_000` hard-codes `HANDOFF_MS` (private); fine as a reminder.
- `qa-26a-step1-code.md`'s "§6g" (a historical review record).
