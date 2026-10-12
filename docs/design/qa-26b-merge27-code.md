# QA (code): the merge of `music-1b-sonos` (design 27) into `music-2-storytel` (2026-10-12)

Reviewed: `45ca74e` (the merge of `music-1b-sonos` at `50958b0` - which carries `origin/main` `620e578` with design 27
and 26a's adaptation and fix round - into `7ad8b26`, with the conflict resolutions and the adaptation inside it) and
`c431b61` (26b's section "Merge of music-1b-sonos with design 27", the rewritten §5.5 and §8 step 4a, the Release
note). Read against root, `music/` and `server/CLAUDE.md`, 26b §3.5, §5.5, §8, §13, 26a's "Merge of main with design
27" and its fix round (incl. "The Storytel seam"), `qa-26a-merge27-code.md`, design 27 and `qa-27-code.md` where the
art path is concerned. No product code changed here.

**Separating the adaptation.** `git merge-tree --write-tree 7ad8b26 50958b0` gives tree `286a09e` with conflicts in
eight files (`UiModel.kt`, `PlayerScreen.kt`, `PlaybackService.kt`, `SwitchViewTest`, `DebugFeedReceiver.kt`,
`server/src/tests/mod.rs`, `music_sweep.rs`, `server/CLAUDE.md`), as 26b says. `git diff 286a09e 45ca74e` (13 files,
+103 -89) was read in full: the eight resolutions plus the adaptation (`ArtPlan.kt` + `EpisodeArtTest`, the new
`SwitchViewTest` case, `music_storytel.rs`'s null `art`, PLAN.md, `music/CLAUDE.md`). For the 26 files both sides
changed since the merge base `39b59dd`, a line-multiset check (every line either side added is in the merge, no line
either side removed is back) flags only the eight conflict hunks, each an intended resolution.

## Runs (this worktree, 2026-10-12)

- `server/`: `cargo test` **655 passed, 0 failed** (the time-dependent `parent_pin` passed this run);
  `cargo fmt --check` clean.
- `music/`: `./gradlew testDebugUnitTest lintDebug -PwarningsAsErrors=true`, then `testDebugUnitTest --rerun`:
  **542 tests, 0 failures** (`ContractFilesTest` 15, `BridgeTest` 9, `EpisodeArtTest` 12, `SwitchViewTest` 13); lint
  0 errors, 40 warnings (as 26b says).
- `python3 -I -B scripts/fake-sonos.py --selftest` and `scripts/fake-storytel.py --selftest`: OK.
- `launcher/`: `./gradlew testDebugUnitTest` **1035 tests, 0 failures** (`MusicPlanTest` 28).
- Mutation check (reverted, `git status` clean): (A) `playerNumberOf` over `listOrder` alone - 3 `SwitchViewTest`
  red, the new "3 av 5" case among them; (B) `playerNumber`'s M from `listOrder(...).size` - the same 3 red; (C)
  `artWant` without its Storytel arm - `EpisodeArtTest`'s new case red. The new pins bite.
- Not run: `cargo clippy` (the merge changes no non-test Rust but a doc comment), the launcher's `assembleRelease`
  (needs `app/libs/tsnet.aar`; CI builds it).

## Findings

0 High, 1 Medium, 4 Low. The merge itself is correct; the Medium and three of the Lows are in the rewritten step-4a
plan (no code yet), one Low is a missing note in 26b's merge section.

### Medium 1 - Step 4a's planned test can't catch the `storytel_check` carry-over it asks for, and the plan names the wrong symptom

- **Where:** 26b §8 step 4a (`docs/design/26b-storytel.md:857-878`) and the merge section's "Step 4a, rebuilt on
  design 27"; server `music_sweep.rs:2365-2384` (`storytel_check` builds each `Item` from `..Item::new(..)`),
  `:2587-2618` (`art_due`), `:2719-2750` (`known_art`), `:2866-2885` (the pass's steps 1-2).
- **Evidence:** the plan says that without carrying the `art_*` fields over "every check drops the hash and fetches
  the picture again", and pins it with "a second check fetches nothing and keeps the hashes". The drop is real
  (`Item::new` sets every `art_*` to None/0), but for an `own` or `same` book the pass does **not** fetch again:
  `art_due` is true (`art_from` None), and step 2's `known_art` falls back to
  `SELECT art_hash, art_print, art_tone FROM music_items WHERE art_from = ? ...` - the previous check's row of the
  same book is still in the table until `finish` writes, so `art_own(..., None, ..)` restores the hash with no
  request (a `same` on the series cover's URL is step 1, no request either). So the planned test passes with or
  without the carry-over.
- **Failure path without the carry-over:** a book whose cover failed (a 4xx, an undecodable picture) or whose host
  was down has no stored print, so `known_art` finds nothing; with `art_fails` back at 0 and `art_at` None it is due
  at **every** check (not a day later, not capped at `ART_MAX_FAILS`), and each check spends one image request on
  it out of `ART_PER_CHECK`/`ART_PER_DAY`, which NRK/RSS share. Also `art_fetched_at` (not in the plan's field list)
  and `art_at` are lost/restamped each check.
- **Fix (plan text, then 4a):** build the kept book from the old item (`old.cloned()` with `state`, `title`, `url`,
  `hls`, `duration_ms`, `art_url`, `first_seen_at` set; else `Item::new`), so every `art_*` field - `art_fetched_at`
  included - and anything added later survives. Pin it with a failing cover: book 1's cover answers 404; check 1
  sends one image request and leaves `art_state = failed`, `art_fails = 1`; check 2 the same day sends **no** image
  request and `art_fails` is still 1. Keep "a second check keeps the hashes" as well, but say it pins the result,
  not the carry-over.

### Low 1 - Step 4a widens `art_in_library` but not `restamp_versions`

- **Where:** `server/src/music_sweep.rs:961-966` (`restamp_versions`: `e.source IN ('nrk', 'rss')`), called from
  `photos.rs:401-411` after `MUSIC_ART.recover_missing` forgot pictures (design 27 code QA L6); 26b §8 4a lists only
  `music.rs:1140`.
- **Failure path:** after 4a, a restore (art is not backed up) forgets a book's hash in `music_items`, but the
  Storytel listing's stored version isn't re-stamped, so the library keeps naming the old listing: the phone keeps
  the book's hash, wants it, the cover route answers 404 (`art_in_library` false), the launcher records two
  failures and backs that hash off for 24 h (`MusicPlan.kt:244-245`, `artBackedOff`). It heals after the next
  Storytel check re-fetches the same picture and the backoff runs out - up to a day of the entry cover. Cosmetic.
- **Fix:** 4a adds `'storytel'` to `restamp_versions`' source list together with `art_in_library`'s (the only two
  `IN ('nrk', 'rss')` lists left in `server/src`), with a `music_art.rs`-style restore case for a Storytel entry.

### Low 2 - "`the_sweep_lists_a_series_like_a_feed`'s null `art` becomes the hash" is wrong for that item

- **Where:** 26b §8 step 4a and the merge section; `server/src/tests/music_storytel.rs:1058, 1061, 1080-1086, 1123`.
- **Evidence:** the assertion is on `items[1]`, whose `art_url` is `COVER_2` - the series' own cover
  (`listing.cover_url == COVER_2`). After 4a the pass judges it `same` at step 1 (no request, no hash), so it stays
  null - correctly (the entry cover is the same picture). The hash appears on `items[0]` (`1.jpg`). The same test's
  `report.requests == 1` and `fetch.calls == [COVER_2]` also change once the pass fetches `1.jpg` (it needs a canned
  answer, or `set_art_per_check(0)` there and the art cases in their own test).
- **Fix:** the plan text: "`items[1]` stays null (`same`, the series cover); `items[0]` becomes the hash; the request
  count and the canned answers follow". The comment the merge put on that assertion ("reaches no phone until step
  4a") should then say why it stays null.

### Low 3 - Step 4a's emulator check can't run as written

- **Where:** 26b §8 step 4a "Emulator (§6i): a series' books show their own covers in the carousel and in Nytt (the
  fake Storytel's shelf with per-book covers)"; `docs/testing/emulator.md` §6i; `scripts/fake-storytel.py:120`.
- **Evidence:** on the emulator the server isn't in Storytel's loop: the listing comes from
  `fake-storytel.py --print-listing 5`, which writes `"art": null` for every book, and the server's bookshelf URL is a
  constant (`storytel.rs:49`) with no fake switch - and its art pass would refuse a private/loopback cover host
  (`Reach::Public`, `public_media`) anyway. So no book can show its own cover there.
- **Fix:** either 4a gives the fake an `--art` option (a small JPEG per book written to `debug-feed/covers/<sha256>`,
  the hashes in `--print-listing`, covered by its selftest) and §6i a line for it, or the plan drops that emulator
  line and leans on the server tests plus design 27's carousel check (any listing hash). Then "Owed: none" holds.

### Low 4 - 26b's merge section doesn't say a dev database from before this merge won't open

- **Where:** `docs/design/26b-storytel.md` "Merge of music-1b-sonos with design 27" (from line 1882); the earlier
  merge's note at line 1742 covers only the old 65/66.
- **Evidence:** this merge brings 1b's edited 0071 header (`git diff 39b59dd 50958b0 --
  server/migrations/0071_music_sonos_rooms.sql`, 26a's L2: frozen since). A `server/data/kidphone.db` that ran this
  branch at `7ad8b26` or earlier holds 0071's older text and 0072 without 0070: the merged server stops at
  `VersionMismatch(71)` (and would otherwise apply 0070 out of order with no pre-migration backup). No deployed server
  ran this branch.
- **Fix:** one line in the merge section: delete `server/data/` (or restore a backup taken on `main`) before running
  this branch's server, as the 2026-10-12 note above it says for 65/66.

## Verdicts

1. **Nothing lost or duplicated - OK.** Storytel's `mediaItem` branch (`copyOnly`, the source id as uri,
   `PlaybackService.kt:690-695`) is followed by design 27's `artUri(a, item)` like every item (`:715`); `load()` keeps
   `forRenderer(false)` (`:510`) and every service path goes through it (only `NewNotifier.update` and
   `ArtWants.compute` read `store.snapshot()` raw, both view-independent); `storedUri`, `consumableIdOf`,
   `STORYTEL_FREE_FLOOR`, `runInterruptible` present. 1b's `refreshArt` (`Renderer.itemAt`/`replaceItem`), `skipTo`
   -> `goTo` -> `renderer.seekTo`, `toRoom`'s key lookup (`:1469`), `onRoom` in `swipeSwitch`/`toIndex`, `waiting`
   and the start cap present; 27's carousel, `ReapplyListings`, `wantArt` present. A swipe on a Storytel queue on the
   phone can only land on a downloaded book (the queue is `forRenderer(false)`'s), and `playable` for a book without
   a copy is false (`url` null, no stream), so `goTo` skips on with "Ikke lastet ned" as next/previous do. No
   Storytel call site bypassed. `PrefetchCovers` lives on in `EpisodeCarousel` (27's), no duplicate.
2. **The numbering seam - OK.** `countedKeys` is private (`UiModel.kt:213-217`), `playerNumberOf` and `playerNumber`
   both read it (`:198`, `:206`), `MusicRoot.kt:113` keeps `{ item -> playerNumberOf(s, n, item) }, of`,
   `PlayerScreen` takes `numberOf` with Storytel's `part_of` line. Mutations A and B turn the "3 av 5" case red. For
   non-Storytel entries `countedOrder` is `listOrder`'s keys, so `indexOf(key)` equals 1b's `indexOfFirst` and M is
   the same size - unchanged; a newest-first podcast counts in its list order as before, and a Storytel series
   played newest first counts the reading order (`LibraryViewTest`'s L5 case).
3. **Art for a series - OK.** `artWant`'s Storytel arm (`ArtPlan.kt:35-39`) uses `storytelKeepSet` +
   `storytelWanted(keep, keys, emptySet(), bookmark)` - the same keep set `downloadPlan` keeps (first N, all for -1,
   nothing for 0, plus the bookmarked book and the next) in its fetch order. `have` is empty as in the NRK/RSS arm, so
   the set doesn't move as books download (no `wantArt` churn; it moves with the bookmark, as the downloads do).
   `EpisodeArtTest:92` asserts it (wants 4, 1, 2, 5, not 3) and mutation C turns it red. Today a Storytel item has no
   hash (the listing's `art` is null), `isArtHash` drops it, so no Storytel want reaches the launcher at all; after 4a
   a refused hash is a 404 (`music_api.rs:151-158`) -> `artAnswer` false -> two failures then a 24 h backoff
   (`MusicPlan.kt:244-245, 270-271`): no hammering. `the_sweep_lists_a_series_like_a_feed` expecting null is right
   until 4a (see Low 2 for after).
4. **Contract files - OK.** `cmp`: both `IMusicBridge.aidl` and `IStorytelCallback.aidl`, both
   `bridge_contract.json`, and `music_library.json`/`music_listing.json`/`music_icons.json` (server vs music) are
   byte-identical. No snapshot needed regenerating: the library changed only on this side (the Storytel entry), the
   listing only on main's (the hash), and the listing fixture has no Storytel item; `music_library_snapshot` and
   `music_listing_snapshot` pass on the merged tree.
5. **Migrations - OK (Low 4).** 0070 equals main's, 0071 equals 1b's, `0072_music_storytel.sql`'s log is `623183b`,
   `b219400`, `e14bf36` (the rename) and nothing after; the merge touches no migration. §2.8's block equals the file
   byte for byte. The deploy-order and backup-hazard notes in 26a, 26b's "Release" and `server/CLAUDE.md` agree (0070
   before 0071 before 0072; never deploy a pre-merge build of either branch).
6. **The rewritten 4a plan - complete with the fixes above.** Checked against the code: `art_candidate` needs `ok` +
   `url` + `art_url` (`music_sweep.rs:2581-2583`); the order arm makes `auto` newest first for every non-NRK-series
   link (`:2826-2831`; a Storytel entry is `auto`, `keep_end` `first`); `storytel_check` drops every `art_*` field
   (`:2375-2382`; Medium 1 for what that actually costs); `art_in_library` is `('nrk', 'rss')` (`music.rs:1140`);
   `photos::MUSIC_ART` has its own store and lock (`photos.rs:113-125`), taken by `finish` (`music_sweep.rs:3039`).
   "The phone needs nothing" is true: `Listing.kt` keeps a 64-hex `art` for any source, `Snapshot`/`PlayItem` carry
   `artHash` for every item, and the carousel (`showsCarousel`), the player, `artUri`/`refreshArt` (`itemArt`),
   `setNowPlaying` (`artOrCover`), Nytt and search all read `artHash ?: entry.cover`; the DIDL takes art only when it
   is http (`PlayBody.kt:50`), so a hash never reaches a speaker. **What 4a's implementer must not miss:** (a) build the
   kept book from the old item, all `art_*` incl. `art_fetched_at`, and pin it with a failing cover (Medium 1);
   (b) `restamp_versions` gets `'storytel'` with `art_in_library` (Low 1); (c) key the candidate on the link, not on
   `url` NULL - `art_pass` already has `link`, so `art_candidate(item, storytel)` with
   `matches!(link, Link::Storytel { .. })` (a withdrawn NRK/RSS item is `gone`, not `ok`, so `ok` + no url is a book
   today, but the link says it outright); (d) the order: e.g. `series = matches!(link, Link::NrkSeries { .. } |
   Link::Storytel { .. })`, so `auto` with `keep_end` `first` reads oldest first, an explicit order as given;
   (e) `the_sweep_lists_a_series_like_a_feed`: `items[1]` stays null, the request count changes (Low 2); (f) the
   emulator line (Low 3); (g) a book whose cover is the series' must stay `same` (step 1) - don't "fix" that null.
7. **Hygiene - OK.** `-PwarningsAsErrors=true` green, lint 0 errors/40 warnings, both fakes' selftests OK,
   `cargo fmt` clean. 26b's merge section is accurate (36 commits after `39b59dd`, the eight conflicts, the test
   counts match these runs) except Low 4's missing note. The Release note says 0072 above 0070/0071, versionCode
   2999 above 1999/1/2 (tags checked: `music-v0.0.1`, `music-v0.0.2`, `server-v0.19.0`; `Cargo.toml` 0.24.0). "No
   art to the speaker" is in §5.5, §7's rows, §8 4b's emulator line, §9's new row and the Sonos device check.

## Not worth fixing

- 26b §1.3's port-table row for `_sonos_body` still lists vibb's `art_url` with no "no art" in the Home column; §9's
  new row says it.
- `playerNumber` builds `countedKeys` twice, and every carousel page's `numberOf` rebuilds `snap.order` - a few
  dozen keys per recomposition, as 1b's `listOrder` did.
- The Storytel card's count line ("N books") has no "with their own cover" part (`handlers/music.rs:2920-2926`
  returns early); 4a may add it, nothing needs it.
- `server/CLAUDE.md`'s Storytel section still says "Step 2a leaves Storytel out of the library, the sweep and the
  cards (step 2b)" - stale since 2b, not this merge's.
- The plan's reason for keying the candidate on the source ("the `Item` alone can't tell a book from a withdrawn
  episode") is not quite right today (withdrawn items are `gone`), but the advice stands (Verdict 6 c).
