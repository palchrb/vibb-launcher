# QA (code): the merge of `music-1b-sonos` and main into `music-2-storytel` (2026-10-12)

Reviewed: `49770d8` (merge of `39b59dd`, which holds `origin/main` `444ca58`, into `7d93478`), `e14bf36` (migration
0066 -> 0072), `24ce749` (emulator §6h -> §6i), `6566d8b` (a tap's switch reads the renderer's view), `d5da1c2` (the
two Lows from the main session's review of step 3), `86b2271` (26b's merge section and Release). Read against root,
`music/` and `server/` `CLAUDE.md`, 26b §3.2, §3.5, §4.2, §4.3, §5, §13 M5, the step 3 status and fix round and the
new merge section, 26a's correction round and merge sections, and `qa-26a-merge-code.md`. No product code changed here.

## Runs (this checkout, 2026-10-12)

- `server/`: `cargo test` **628 passed, 0 failed** (Rust 1.97 locally; CI pins 1.99.0). No `parent_pin` failure today.
- `music/`: `./gradlew testDebugUnitTest lintDebug -PwarningsAsErrors=true` green; tests re-run with `--rerun`:
  **499 tests, 0 failures**; lint 0 errors, 40 warnings (none in the touched code: `libs.versions.toml`, the manifest,
  and the old `UsableSpace`/`UseKtx` lines).
- `python3 -I -B scripts/fake-sonos.py --selftest` and `python3 -I -B scripts/fake-storytel.py --selftest` OK.
- `launcher/`: `./gradlew testDebugUnitTest` **1012 tests, 0 failures**.
- Mutations (restored afterwards): `playSwitch` reading `snap` instead of `snap.forRenderer(onRoom)` turns
  `SwitchViewTest`'s room case and `GuardsTest`'s pin red; `DownloadRun.fail` with `grew = false` turns
  `StorytelDownloadTest`'s new case red. `StorytelAccount.mint` back on `withContext` is caught by `GuardsTest`'s pin
  only (`StorytelApiTest` exercises `runInterruptible` over `StorytelApi` itself) - enough for a one-line rule.
- The merge commit alone: `49770d8`'s server in a worktree - see Low 3.

## Findings

No High, no Medium.

### Low 1 - `runInterruptible` does nothing for "4b's superseded play": the room's actor never cancels a job

- **Where:** `music/.../storytel/StorytelAccount.kt:33-40` (KDoc: "a cancelled caller - ... step 4b's superseded Sonos
  play - interrupts the blocked thread"), `d5da1c2`'s message, `docs/design/26b-storytel.md:1742-1744`;
  `music/.../sonos/SonosActor.kt:35` (`for (job in jobs) job()`), `:63-69` (`close`), `:76-82` (`run`).
- **Evidence:** the actor runs each job inline in its one consumer coroutine; nothing cancels a single job. A
  superseded play is dropped by the generation check when its result is posted (`SonosController.kt:763`), and §5.1's
  token makes a superseded job skip the mint or the SOAP - but a mint already in flight runs to its end (up to
  `MINT_BUDGET_MS` 10 s). The only cancellation is `close()`'s `scope.cancel()` after the drain, which ends the
  consumer anyway. For the download worker the change is right and works (WorkManager's stop cancels `doWork`, the
  thread is interrupted, the lock is released or never taken: `lockWithin` returns false without `unlock`, `bearer`'s
  `finally` unlocks the holder; an interrupted login is `LoginFailed` with no cooldown and no `jwt` write, an
  interrupted asset call `Unreachable`; the caller gets `CancellationException` from `withContext`'s completion, and
  `DownloadRun` writes no row or item error).
- **Failure path (4b):** an implementer reads the KDoc and relies on interruption to make a step during a slow mint
  responsive; it isn't, so polls, pause, volume and the next play wait behind the mint for up to 10 s. If 4b then adds
  per-job cancellation (a child `Job`/`async` cancelled on supersede), the mint's `CancellationException` comes out of
  `job()`, `SonosActor.run` rethrows it (`catch (e: CancellationException) { throw e }`), and the consumer loop ends
  silently: later `submit`s queue forever, `call` never completes, the poller hangs - a dead room with no log line.
- **Fix:** reword the KDoc and the merge section to "a download job WorkManager stops" (4b's superseded play is
  handled by the token, not by interruption). In 4b, either keep the mint uncancellable (the token is enough), or run a
  cancellable job in its own child and turn its cancellation into a `Result.failure` inside the job, never letting it
  out of `run` while the consumer's own job is active (`currentCoroutineContext().isActive`); a `SonosActorTest` row
  for "a job cancelled from outside, the next job still runs".

### Low 2 - A partial that grows by a byte resets the backoff every time

- **Where:** `music/.../sync/DownloadRun.kt:170-171`.
- **Evidence:** `grew = cached > row.bytes` - any growth, however small, sets `attempts` to 1 (2 minutes). Readers of
  `DownloadRow.bytes` checked: nothing else reads it (`cache_bytes` is `MediaCache.totalBytes`, Sync.kt:218; "N av M
  lastet ned" and prune go by `done`/`onPhone`; `OfflineTest` doesn't touch it; done rows are deleted, never re-fetched
  through `fail()`), so the second meaning is safe.
- **Failure path:** a CDN or RSS host that sends a few KB and then resets (a throttling proxy, a captive Wi-Fi that
  lets a trickle through) keeps the item at the 2-minute wait for as long as each attempt adds anything; for a Storytel
  book every attempt is a mint (and after an hour a login), so the account sees an asset call every 2 minutes for a
  300 MB book instead of backing off to 6 h. Bounded only by the file's size.
- **Fix:** count growth only above a floor, e.g. `cached - row.bytes >= max(1 MiB, row.bytes / 100)`, and one
  `StorytelDownloadTest` row (a 10 KB step counts up). NRK/RSS keep the same rule (no regression otherwise: a partial
  from another url is dropped and starts at 0 as before, where the old rule also restarted the count).

### Low 3 - The merge commit `49770d8` alone fails the server's CI

- **Where:** `49770d8:server/migrations/` has `0066_lock_glance.sql` and `0066_music_storytel.sql`.
- **Evidence:** `cargo test` on that tree (a worktree, same target dir): every test on a migrated database panics with
  `failed to run migrations: ... UNIQUE constraint failed: _sqlx_migrations.version` (398 panics). sqlx 0.8.6's
  `Migrator::run` applies both version-66 files on a fresh database; the second `_sqlx_migrations` insert fails.
  `e14bf36` fixes it; the head is green. CI runs on the pushed head (and a PR's merge ref), so no workflow sees
  `49770d8` - but `git bisect` does.
- **Fix:** none to the history; one sentence in the merge section ("`49770d8` alone has two 0066 migrations and fails
  `cargo test`: `git bisect skip` it").

### Low 4 - A dev database from either branch before its merge no longer opens

- **Where:** `server/data/kidphone.db` on the emulator VM (`scripts/dev-rebuild.sh` runs `cargo run`, default
  `sqlite://data/kidphone.db`); `docs/testing/emulator.md` §2.
- **Evidence:** a database that ran `music-2-storytel`'s server before the merge holds version 65 (Sonos rooms) and 66
  (Storytel) with those files' checksums; the merged server has 65 = command receipts and 66 = lock glance, so
  `Migrator::run` stops at `VersionMismatch(65)` and `main` exits 1 after taking a `pre-migrate-0066-0072.db`. The same
  held for `music-1b-sonos` before its merge (qa-26a-merge-code didn't note it). Production is unaffected (neither
  branch was deployed).
- **Failure path:** the user starts the §6i/§6h emulator loop on the VM's existing `data/` and the server refuses to
  start with a checksum error that names no branch.
- **Fix:** one line in the merge section and `emulator.md` §2: "a `data/` that ran a music branch's server before
  2026-10-12 won't open (migration 65 changed): start from a fresh `data/` or a backup from `main`".

### Low 5 - 26b's spec text still describes the old mint and the old retry

- **Where:** `docs/design/26b-storytel.md:476` (§4.2: "`mint` is `withContext(Dispatchers.IO) { api.assetUrl(...) }`"),
  `:383` (§3.3: "2 min doubling, at most 6 h" with no word on growth), `:656` (§5.7 names `SonosSession.kt:327`; the
  line is `:329` after the merge).
- **Evidence:** `d5da1c2` changed both rules; only the merge section at the end says so. 4b's implementer reads §4.2
  and §5 first.
- **Fix:** §4.2 "`runInterruptible(Dispatchers.IO) { ... }` (main session's review of step 3, Low 2)"; §3.3 "... a
  failure after the partial grew counts as the first again (Low 1)"; §5.7 by name ("the not-ours? line in
  `SonosSession.classify`") rather than a line number.

### Low 6 - The switch's speaker-filter parameter is a test hook, and step 4b's list misses three items

- **Where:** `music/.../ui/UiModel.kt:101` (`speakerPlayable: (Entry, PlayItem) -> Boolean =
  PlayBodies::speakerPlayable`); `docs/design/26b-storytel.md`, the merge section's "For step 4b".
- **Evidence (parameter):** production always takes the default; only `SwitchViewTest`'s `speaker4b` lambda passes
  another. 4b's change is inside `PlayBodies.speakerDropReason` (§5.1), which `queueFor`/`activeFor` read directly - so
  the seam doesn't tie the screens' filter to the service's; it only lets a test pre-play 4b. Harmless today.
- **Evidence (list):** see "4b readiness" below - the serial actor behind a mint (Low 1), `SWITCH_WAIT_MAX_MS` vs
  §5.1's `HANDOFF_MS` 25 s, and where the post-mint address checks live.
- **Fix:** in 4b, drop the parameter once `speakerDropReason` has the Storytel branch and let `SwitchViewTest` use the
  real filter (its assertions stay); add the three items to the merge section's 4b list.

## Verdicts per area

1. **The merge - clean.** `git merge-tree --write-tree 7d93478 39b59dd` differs from `49770d8`'s tree only in the
   three conflicted files (`UiModel.kt`, `MusicRoot.kt`, `PlayerScreen.kt`). Each resolution keeps both sides:
   `UiModel.kt` has main's `itemKnown`/`cover`/`itemTitle`/`ref`, `Switch`, `playSwitch`/`skipSwitch` with `onRoom`,
   Storytel's `countedOrder` in `entryUi` (with `isNew` on the visible keys) and Storytel's `playerNumber` body under
   the joined KDoc, both imports; `MusicRoot.kt` has main's `position`/`skip`/`requestStands`/`holdEndsAt` with 1b's
   `onRoom` and `hub.waiting` (GuardsTest's qa-26a L1 pins all hold) and the shared `playerNumber(s, n)` call;
   `PlayerScreen.kt` has main's `PrefetchCovers`, `now.cover` and `now.itemTitle` with Storytel's
   `track_of`/`part_of`/`episode_of` line. For every auto-merged file (`PlaybackService.kt`, `MusicViewModel.kt`,
   `LibraryScreen.kt`, `DebugFeedReceiver.kt`, `SourceScanTest`, `GuardsTest`, `server/src/{main,music}.rs`,
   `server/src/tests/mod.rs`, `device_detail.html`, `music.yml`, `emulator.md`, all three `CLAUDE.md`, PLAN.md,
   design 26) the set of changed lines from each side equals that side's own diff from the base `9ecdad8`, so no pin
   or hunk was lost. `Snapshot.kt`, `PlayBody.kt`, `SonosController.kt`, `SonosLifecycle.kt`, `SonosSession.kt`,
   `Sync.kt`, `DownloadPlan.kt` were changed by one side only; read for semantic clashes: the service still reads
   `forRenderer(false)` at `load()` (pinned), 1b's `PlayBody` tailnet filter and `members` don't touch
   Storytel (a book has no url until 4b), `hidesPosition` compares in-memory minted uris (4b, below). `music.yml` runs
   main's wrapper validation first, then the build, then both selftests; its path filter has both fakes. Root
   `CLAUDE.md` still names only `fake-sonos.py` in "scripts/" and the CI paragraph - step 5's docs, as 26b says.
2. **Migration 0072 - correct (Low 3, Low 4).** `git diff -M 49770d8 e14bf36 -- server/migrations/` is one rename
   (67 %) with only the header changed ("from 0072 on", the renumbering, the hazard); main's 0065-0069 and 1b's 0071
   untouched (`git diff 39b59dd 86b2271 -- server/migrations/` adds 0072 only). §2.8's block equals the file byte
   for byte. Every `0066` left means the lock glance or names the renumbering (`models.rs:113`, `server/CLAUDE.md`,
   26b's historical step lines with "(now 0072)", PLAN.md:204). The backup hazard is stated in the header,
   `server/CLAUDE.md` "Data model" and "Release".
3. **§6i - complete.** `24ce749` moved every Storytel reference (26b fourteen times, `music.yml`'s comment,
   `fake-storytel.py`'s header, `DebugStorytel.kt`, `DebugFeedReceiver.kt`); 26b's "§6g + §6h" for step 4b became
   "§6h + §6i" and Release's "§6h, §6g with Storytel" became "§6i, and §6h with Storytel" - Sonos references still mean
   Sonos. `emulator.md` against `39b59dd` adds §6i only; main's §6g is untouched. Order in the file: 6f, 6g, 6h, 6i,
   then the old 6b (as before).
4. **The adaptation - correct (Low 6).**
   - *What the kid sees on a Storytel tap on a room today:* `playSwitch` returns null, so no hold; `MusicRoot` still
     opens the player, which shows what the player has (the room's current item, or the library when there is none),
     and the service - `load()` is the phone's view, `queueFor(SONOS)` finds nothing speaker-playable - emits
     `CANT_PLAY_ON_SONOS`, shown 3 s. An entry with no copy isn't in the phone's carousel, so it can't be tapped. That
     is 26a's behaviour for an own-files entry on a room and vibb's `nothing-remote-playable` (the renderer stays);
     right until 4b.
   - *`skipSwitch` vs `playerNumber`:* the target is counted in the hub's keys (`Active.items`, the list
     `skipNext`/`skipPrevious` count) and the number is the target's index in `snap.listed` (reading order), so they
     always name the same book: phone playing or paused (only the start position differs, as the service's), a room
     playing (0) or paused, and newest-first (`order: newest_first` plays 5..1: next from book 3 goes to book 2 and
     reads "Del 2 av 5" - consistent, if counting down). `nowView`'s live path builds the queue from `snap.item`
     (listed), so a room's queue with books not on the phone still shows (the test proves it).
   - *Tests:* the two `SwitchViewTest` cases and the `GuardsTest` pin go red with the rule removed (Runs).
5. **The two Lows - correct (Low 1, Low 2, Low 5).** (a) see Low 2: `bytes`' second meaning has no other reader; the
   attempt row carries it only for the same source; a 416 refetch counts one extra failure at worst. The commit's "a
   job stopped at its 10-minute limit" isn't a `fail()` (the stop is a cancellation, no row write), but its growth
   still counts at the next real failure, so the claim holds in effect. (b) the lock is never left held and the JWT
   state stays consistent under interruption (Low 1's evidence); `runInterruptible` clears the interrupt flag it set,
   and `lockWithin`'s re-set flag stays inside it, so no pooled IO thread is poisoned; `StorytelApi` catches every
   exception, so the only `CancellationException` is the caller's own. The actor gains nothing from it today (Low 1).
6. **4b readiness - the six items hold; three more.**
   - *Late mint in the job:* confirmed - `PlayBodies.build` runs on main in `playIndex` before `work.submit`, the cap
     and the hush are inside `SonosTransport.play` before `SetAVTransportURI`, so a mint before `transport.play` that
     fails touches nothing; the superseded branch keeps `Playing(..., res.uri)` in memory only.
   - *`sameStream`:* confirmed at `SonosLifecycle.kt:44` (`roomVerdict`) and `:78-83` (the built-uri match), and for
     the adopt path: `adoptLive` first adopts the stored uri (the marker) and classifies against it, so `ours` is
     false until `keep` returns the live uri - `keep` must match by `sameStream` and return the live TrackURI (which
     then re-adopts and republishes `ours = true`, `SonosTransport.kt:243-247`); the standalone bedtime look passes
     `keep { null }` and decides through `roomVerdict`. Since the session's uri is the minted one after a play or such
     an adopt, `classify`, the landed check (`SonosController.kt:883`), the bookmark check (`:1023`) and the migration
     probe's member match (`SonosSession.kt:485`) compare in-memory minted uris and need no change - so a fresh
     re-mint of ours (a step, `restart()` after a failed resume) counts as ours: `pending` covers the settle and
     `played` installs the new uri before the next poll (polls run in the same serial actor). `classify`'s not-ours
     line fires on every Storytel adopt with the marker and prints the live TrackURI with its token - §5.7's
     redaction is required, not cosmetic.
   - *The migration probe's write through `storedUri`:* confirmed (`lookAtRoom`'s rewrite, `PlaybackService.kt:1249`,
     and `SonosRenderer.kt:94` are the writes).
   - *`speakerDropReason`'s Storytel branch:* confirmed; note that it can't check the minted URL (none exists when the
     queue is built) - the private-literal (unless `privateMintAllowed()`) and tailnet checks of the minted URL go in
     the actor job after the mint, best through one shared helper with `speakerDropReason`'s literal checks.
   - *`MusicViewModel` following the renderer:* confirmed (`MusicViewModel.kt:33`; the stored-row `nowView` reads
     `snap.entries`).
   - *The start cap delayed by the mint:* confirmed.
   - **Missed 1 - the serial actor:** a mint of up to 10 s holds every job behind it - polls, pause, volume keys, a
     hand-off (§5.1 raises `HANDOFF_MS` for that one) - and nothing can interrupt it (Low 1). Decide in 4b whether that
     is acceptable or the mint moves before `submit` with the token checks around it.
   - **Missed 2 - `SWITCH_WAIT_MAX_MS` (25 s, `logic/Switch.kt:32`) is sized for `HANDOFF_MS` 15 s:** main's switch
     came in with this merge, after §5.1 was written. With 4b's `HANDOFF_MS` 25 s a tap during a room switch waits
     behind a hand-off of up to 25 s plus the load, so the hold can run out into the old item (cosmetic, as qa-26a's
     L2); raise it with `HANDOFF_MS` and update `SwitchTest:55`'s `> 15_000` (it names `HANDOFF_MS`).
   - **Missed 3 - `SonosSuspension.kt:72,84`** read `store.snapshot()` (the phone's view) for `entryMatcher` and the
     bookmark; §5.6's table says `true`, the merge section's list doesn't (1b's suspension changes didn't touch them).
7. **Hygiene - clean.** Warnings-as-errors and lint green (Runs); the AIDL copies (md5 pairs), `bridge_contract.json`,
   `music_{library,listing,icons}.json` and `phone_vectors.json` are byte-identical. 26b "Release": migration 0072,
   server 0.24.0 untagged with the highest tag `server-v0.19.0` (as 26a found), `music-v0.3.0-rc.1` -> versionCode
   0*1e6 + 3*1e3 + 0 - 1 = 2999 > 1999 (`music-v0.2.0-rc.1`) > 2 (`music-v0.0.2`), never latest, the backup hazard
   (0070 or 0071 after 0072) stated. The merge section at the end of the document is acceptable: "Release" sits before
   §12 by the design's own layout, and the section says so. Its run counts match this review's.

## Not worth fixing

- The 416 path inside one attempt: the fresh partial (from 0) is compared with the old size, so that one failure
  counts up although it fetched; the next failure compares against the new size.
- A cold start on a room: `onRoom` is false until adopt finishes (qa-26a's note); with 4b a Storytel tap in those 4 s
  builds the phone's view (downloaded books only) while the room plays the series - the "player went elsewhere" rule
  ends the hold.
- `playerNumber` for a newest-first Storytel series counts down on "next" ("Del 2 av 5" after "Del 3 av 5"): it is
  §3.5's reading order by design.
- `d5da1c2`'s "a job stopped at its 10-minute limit" wording (verdict 5): true in effect.
- Root `CLAUDE.md` and `music/CLAUDE.md` don't name `fake-storytel.py`, §6i or Storytel's CI selftest yet: step 5's
  docs, as 26b's merge section says.
- QA records (`qa-26b-step*`) keep "§6h" and "0066": they were written against those numbers.
