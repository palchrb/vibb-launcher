# QA 27 (code) - per-episode art and the Now Playing carousel

Reviewer: code QA, 2026-10-12. Branch `music-episode-art-27` (`e3b61813..3aca12a5`, 8 commits), against
`docs/design/27-music-episode-art.md`: its design QA, the decisions after it, the user's decisions and the
implementation status. The vibb reference is palchrb/vibb branch `pipewire` (the scratchpad copy). Nothing was changed
in the implementation, and no probe tests were left behind.

**Result:** no High findings, 2 Medium and 12 Low. The contracts, the migration, the courier's caps and gates, the
carousel's rules and the layout are sound. Both Medium findings are in the server's art pass: the day budget can be
bypassed, and a dead image host is retried with no outage guard.

## Test runs (one run each, under the shared build lock)

- **Server** (`server-target-27`):
  - `cargo test`: 611 passed.
  - `cargo fmt --check`: clean.
  - `cargo clippy --all-targets`: 12 warnings, all in old code (`models.rs`, `dns_engine.rs:54/55`,
    `devices.rs:1321`). That is the same as the baseline.
- **Launcher**, with the stub `tsnet.aar` (removed afterwards):
  - `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`: BUILD SUCCESSFUL.
  - 1010 tests, 0 failures.
- **Music app:**
  - `testDebugUnitTest assembleDebug assembleRelease lint -PwarningsAsErrors=true`: BUILD SUCCESSFUL.
  - 185 tests, 0 failures. Lint added nothing new.
- **How the phone tests ran:** Gradle reported `testDebugUnitTest` UP-TO-DATE. The inputs were identical to the
  implementer's last run on this HEAD, so the counts are from that run's results, which Gradle re-validated.
- **Byte-identical (`cmp`):**
  - `server/testdata/music_listing.json` = `music/app/src/test/resources/music_listing.json`;
  - `music_library.json` (unchanged);
  - both `bridge_contract.json`;
  - both `IMusicBridge.aidl` and both `IStorytelCallback.aidl`.
  - `wantArt` is appended last.

## Medium

### M1. The 400-a-day budget counts rows, not requests, so a feed whose picture URLs change on every fetch is fetched again at every check
- **Where:**
  - `server/src/music_sweep.rs:2241-2246` (`art_fetched_today`: `COUNT(*) FROM music_items WHERE art_fetched_at > ?`);
  - `:2333-2336` (`art_due`: `art_from != art_url` means untried);
  - `:2157` (`merge_feed` takes every fresh feed's `art_url` for a known item);
  - `:2589` (the one stamp per row, overwritten at each fetch).
- **Scenario:**
  - A private or podcast-host feed signs or cache-busts its image URLs (`?token-time=…&token-hash=…`, `?t=<ts>`), so
    every item's `itunes:image` is a new URL at each fetch.
  - At every check, the first 20 items in play order are "untried" again and fetched again: 20 GETs a check, every
    check, every day.
  - The day counter still sees about 20 rows, so `ART_PER_DAY` never stops it.
  - Items 21-200 never get a picture, because the same newest 20 are always due first.
  - vibb keys its cache by episode (`_episode_image_file(dirname, eid)`, "exists: continue"), so the Pi never fetches
    again on a URL change. This breaks "not more than the Pi", and the "at most 400 a day" in `server/CLAUDE.md`,
    `server/DEPLOY.md` and the PWA help text.
- **Fix:**
  - Count requests, not rows: a day counter in `music_settings`, or a small `music_art_requests(at)` log pruned after
    24 h, incremented at every GET.
  - An item already judged (`own`/`same`) whose URL changed only in its query (same scheme, host and path) keeps its
    result and takes the new `art_from` without a request. Alternatively, retry a judged item's changed URL at most
    weekly.
- **Test:** a feed whose item URLs carry `?t=<n>`, changing at each check. The second check sends no image GET, and
  the day counter counts requests.

### M2. The art pass has no outage guard: a blackholed image host holds the sweep for the check's full 600 s and uses up every picture's 3 tries
- **Where:**
  - `music_sweep.rs:2586-2610`: any non-answer (`Got::Failed`, a timeout) becomes `art_failed` (`:2364-2371`, which
    adds 1 to `art_fails`), and the loop goes on.
  - `Kind::Image` waits up to 60 s (`music_net.rs:56`).
  - The manifests have exactly this guard (`MANIFEST_NETWORK_FAILURES`, `:1881-1896`); the art pass doesn't.
- **Scenario:**
  - gfx.nrk.no, or an RSS feed's image CDN, times out while the listing host still answers.
  - Each check tries up to 20 pictures at 60 s each until `MAX_CHECK_TIME` stops it, so every such entry's check takes
    about 10 minutes, and the sequential sweep waits behind it.
  - Each outage day also counts as one of the URL's 3 tries. After three such days the picture is given up for good
    (`art_fails < ART_MAX_FAILS`), where the Pi would simply retry at its next sync.
  - The launcher's courier already makes this distinction (a network error doesn't count).
- **Fix:**
  - End the art pass after 2-3 network errors in a row (as the manifests do).
  - A network error or timeout doesn't add to `art_fails`: stamp `art_at` for the day's wait, or leave the item
    untried. Only an HTTP error or an undecodable picture counts.
- **Test:** a `CannedFetch` image host that fails with a network error. Expect the pass to stop after N, and
  `art_fails` unchanged.

## Low

### L1. The one-time 512 px refresh judges existing NRK entries without the 16:9 reference print for up to a week (QA M2 b comes back)
- **Where:**
  - `music_sweep.rs:1551` (`root_due`: only on a first fill or after 7 days);
  - `:2276` (`alt_candidate` comes only from a root read).
- **Scenario:**
  - 0070 leaves `cover_alt_print` NULL. The first checks after the update run the refresh and the backfill without a
    root read.
  - An episode whose art falls back to the series' 16:9 `image`, centre-cropped, is judged `own`, stored and listed. The
    phone shows a carousel of the square cover against the banner crop.
  - About a week later the root read brings `alt_print`, the re-judge turns it back to `same`, and the carousel goes
    away again.
  - `nrks_wide_series_image_is_a_series_picture` covers only a first fill.
- **Fix:** make the root due while `art_refresh` is set or `cover_alt_at IS NULL`. That is one request per NRK entry,
  once.

### L2. With the backlog off, a new item that wasn't fetched in its own first check waits a month
- **Where:** `music_sweep.rs:2530-2537`. "New" is `first_seen_at == this_check`, and `older` is dropped while
  `!backlog`.
- **Scenario:**
  - A new episode of a backlog-off entry is first seen while the day's 400 are used up (the backfill week), or when
    `job.spent()`, or when its one fetch failed.
  - From the next check on it counts as "older", so neither it nor its daily failure retry runs until
    `ART_BACKLOG_RETRY_DAYS` (30).
  - A podcast that just started using episode art gets its carousel a month late.
- **Fix:** with the backlog off, also treat items with `first_seen_at > art_backlog_at` as new.

### L3. The flaky-test fix (af494ab7) is more robust, but still timing-based
- **Where:** `server/src/tests/music_sweep.rs:2909` (`flags_are_kept_until_their_item_is_asked`).
- **What changed:** waiting for `CannedFetch::in_flight() > 0` removes the "flagged before the rows were read" half of
  the race.
- **What remains:** the flag's two statements must still land before the check commits, about 600 ms later (the
  in-flight page and the manifest, at 300 ms each). That is very likely, but it is still a deadline.
- **Similar:** `the_store_lock_is_held_until_the_commit` (`tests/music_art.rs:1074`, a 500 ms sleep) also passes when
  the lock isn't held, whenever the check takes longer than 500 ms. It is weak, not flaky.
- **Fix:** a gate in `CannedFetch` (a `Notify` or `Semaphore` that `get` awaits when armed). The test arms it, waits
  for `in_flight`, flags, then releases.

### L4. Uncertain: a 9x8 dHash can't separate pictures that differ only in small text, and flat pictures collide
- **Where:**
  - `photos.rs:423` (`print_image`);
  - the twin merge in `art_own` (`music_sweep.rs:2375-2390`);
  - `series_picture`.
- **Scenario:**
  - Episode art that is "one background + the episode's title", or a near-flat field (about 0 bits set), prints within
    5 bits of the others.
  - The twin rule then gives every episode the first one's hash, and they can also be judged `same` as a flat series
    cover.
  - dHash ignores colour, so a recoloured season variant counts as the same picture. That may be what is wanted.
- **Fix, if wanted:** a tighter line for the twin merge (≤ 2 bits), plus a coarse colour check (a 4x4 RGB mean within a
  tolerance), or no twin merge for low-contrast pictures.

### L5. `squareImage: null` or `[]` doesn't fall back to `image` (an old divergence from vibb that now matters for art)
- **Where:** `music_sources.rs:113`, `:163` (`episode.get("squareImage").or_else(|| episode.get("image"))`), and `:168`
  (`wide_image`).
- **Scenario:**
  - `get` returns `Some(Null)` or `Some([])`, so `pick_image` gives None and the episode has no art.
  - vibb's `ep.get("squareImage") or ep.get("image")` falls back, because Python's `or` treats None and `[]` as
    false.
  - Whether psapi ever sends such a value is unverified: the recorded fixtures don't contain one.
- **Fix:** a helper that treats null and an empty array as missing, for both the episode and the root.

### L6. After a restore, the forgotten art rows make the served listing's version differ from the stored one until the entry's next check
- **Where:**
  - `photos.rs:117` (`MUSIC_ART.forget_sql` changes `music_items` outside `finish`);
  - `device_listing` builds the version from the rows it serves.
- **Scenario:**
  - The library keeps advertising the stored `l.version`, while the document served has another.
  - The launcher's `listingsToFetch` asks again at every sync and gets a 304 each time.
  - It heals at the entry's next check.
- **Fix:** after `MUSIC_ART.recover_missing` forgot rows, recompute and store those entries' listing versions, or
  mark the entries requested.

### L7. An episode picture that arrives with a listing update during a queue never reaches the session, the lock's tile or Home's card
- **Where:**
  - `music/.../playback/PlaybackService.kt:484-500` (`refreshArt`);
  - `:474` (`artUri`);
  - `:922-935` (`reportNowPlaying`).
  - All three read `item.artHash` from `active.items`, built once in `activeFor` (`:309-327`). Nothing updates it when
    a listing is re-applied.
- **Scenario:**
  - During the backfill week (or the 512 px refresh) a podcast queue is playing.
  - The server's pass gives the current episode its hash, and the listing moves. The music app re-applies it, wants the
    picture, and copies it.
  - The player screen shows the picture (NowView reads the fresh snapshot). But `refreshArt` finds no
    `item.artHash` (the stale `PlayItem` has null), so there is no `replaceMediaItem`, and `setNowPlaying` goes on
    sending the series cover.
  - The notification, the lock's tile and Home's card stay on the series cover until the next queue start, which is
    exactly the mismatch QA H2 set out to remove.
  - New episodes are not affected: their hash is in the listing that introduces them.
- **Fix:** in `refreshArt` (on `coversVersion`) and in `reportNowPlaying`, read the current item's art hash from the
  store's current snapshot (`snap.item(entry.id, key)?.artHash`), not from `active.items`. Or refresh
  `active.items[i].artHash` when a listing is applied.

### L8. A small copy that the prune deleted stays in the queue's art map: the notification and Home lose the picture, not even the series cover
- **Where:**
  - `Store.kt:97-107` (`pruneCovers` deletes small copies outside the universe);
  - `PlaybackService.kt:313-319` (the queue's `art` map takes every small copy that exists at build time);
  - `:486` (`refreshArt` skips a hash already in `a.art`);
  - `:474` (`artUri` returns the map's `Uri` without checking the file).
- **Scenario:**
  - A long queue is built while small copies exist for episodes played days ago.
  - A sync prunes those that left the 20 recent pictures and the keep set (the cover file goes too).
  - The kid swipes or skips back to one of them. Its picture is wanted and copied again, but `refreshArt` sees the hash
    already "in `a.art`" and makes no new copy.
  - `artUri` points at a deleted file, so the session has no artwork, and `reportNowPlaying` sends no `ART` (its
    `isFile` check fails). That is worse than the series-cover fallback.
- **Fix:**
  - `artUri` and `refreshArt` treat a map entry whose file is gone as missing: drop it, fall back to the entry cover,
    and remake it when the cover lands.
  - Or the prune keeps the small copies of the live queue's hashes.

### L9. The launcher can apply two want sets out of order
- **Where:** `launcher/.../music/MusicBridgeService.kt:137-142`. Each `wantArt` launches its own coroutine on
  `Dispatchers.IO`, and `setWantArt` runs in whatever order they get the lock.
- **Scenario:**
  - The sync's `wantArt` (end of `Sync.run`) and the player's debounced one arrive close together. An item change
    while the sync runs is enough.
  - The older set can be stored last. The courier then deletes the newer `now` pictures it just fetched
    (`artToDelete`) and doesn't fetch them.
  - `LiveBridge.sentNow` already equals the newer `now`, so the player doesn't send it again until the next item
    change or sync.
- **Fix:**
  - Store the set synchronously in the binder call (`setWantArt` is a small file write; oneway calls from one client
    arrive in order), and launch only the courier check.
  - Or serialise `wantArtChanged` on a single-thread dispatcher.

### L10. Quick swipes on a restored (not live) queue race two `RESTORE`s
- **Where:**
  - `music/.../ui/MainActivity.kt:137-145` (each swipe while `!live` sends `RESTORE` + `THEN_SKIP_TO`);
  - `PlaybackService.kt:268` (`scope.launch { restoreThen(..) }`);
  - `:363-366` (`active == null` is checked before the suspending `restore()`).
- **Scenario:**
  - After a restart the player shows the stored queue, paused. The kid swipes twice within the restore's Room load.
  - Both coroutines see `active == null`. Both restore and call `start(play = false)`, and the skips apply in the order
    the loads finish, so the queue can end on the first swipe's target, not the second.
  - Nothing starts playing, so the "never by itself" rule holds. The same race exists for quick next/previous presses
    today, but the carousel makes double gestures likelier.
- **Fix:** serialise `RESTORE` (a `Mutex` around `restoreThen`, or re-check `active` after `restore()` and skip the
  second `start`).

### L11. The launcher counts an HTTP 5xx toward a picture's day-long backoff
- **Where:** `launcher/.../music/MusicSync.kt` `fetchCover` (`!response.isSuccessful -> false`), then `syncArt`'s
  `recordArtFailure`.
- **Scenario:**
  - The Pi restarts during an update, or a reverse proxy answers 502/503 for a moment.
  - Two courier runs in a row (one sync plus one item change is enough) put every wanted `now` picture into a 24 h
    wait. The kid sees series covers all day.
  - QA M6 meant the backoff for pictures that can't be served (404, a wrong hash), and a network error already doesn't
    count.
- **Fix:** count only 4xx and a wrong hash or size. Treat 5xx like a network error (`null`).

### L12. The decode size never shrinks a 512 px picture on a real phone, and the docs say it does
- **Where:** `music/.../ui/Widgets.kt:146-151` (`need = 224 dp × density`, halve while `min/2 ≥ need`).
- **Scenario:**
  - At density 1.5 (the Jelly Star), `need` is 336 px and 512/2 = 256 < 336, so `inSampleSize` stays 1, and every
    picture decodes at 512 px (1 MiB).
  - It only samples below density 1.15.
  - The implementation status ("on the Jelly Star a 512 px picture decodes at 256 px") and the comment are wrong.
  - Memory is still bounded by the 16 MiB cache plus the composed pages (about 7 MiB with the pager). That is fine on
    the Jelly Star; it is the claim that's off.
- **Fix:** correct the comment and the status line. To actually save memory, decode for the size shown (`artDp`, 154
  dp on the Jelly Star → 231 px → `inSampleSize` 2 gives 256 px). Or decode as `RGB_565`, which halves it with no
  visible loss for photos.

## Missing tests

- **Server:**
  - URL churn against the day budget (M1);
  - an image-host outage: stop after N, `art_fails` unchanged (M2);
  - the refresh with the 16:9 reference on an existing entry (L1);
  - a backlog-off entry's new item that missed its first check (L2);
  - a deterministic gate for the flag test and the lock test (L3).
- **Music app:**
  - `refreshArt` or `setNowPlaying` with a listing applied mid-queue (L7);
  - a pruned small copy (L8);
  - `RESTORE` twice (L10).
  - The pager glue in `EpisodeCarousel` (the `own` page, the hold, the settle) is only covered through its pure
    functions. There is no Compose test harness, so the §6 device checks (fling = one skip, follow during a drag,
    TalkBack scroll) carry it.
  - `replaceMediaItem` without a glitch is a device check. I checked Media3 1.7.1's `ExoPlayerImpl` bytecode:
    - same window uid → no `onMediaItemTransition`;
    - `ProgressiveMediaSource`/`HlsMediaSource.canUpdateMediaItem` compare only the URI, cache key and stream keys,
      which `buildUpon()` keeps.
- **Launcher:**
  - the order of two `wantArt` calls (L9);
  - a 5xx not counting (L11);
  - the "held" nudge in `wantArtChanged` happens once per set (it isn't a pure function today).

## Coordination (Sonos and Storytel will rebase onto this)

Nothing is needlessly hard, but the rebasing session should know:
- **Room:** this branch takes version 2 (`schemas/.../2.json`, the `ReapplyListings` auto migration). A later bump is
  version 3 with its own `AutoMigration(2, 3)`. Don't edit 2.json.
- **Server migration:** 0070 is taken.
- **AIDL:** append after `wantArt` in both copies, and update `MusicPlanTest`'s method count (12).
- **Changed signatures:**
  - `Actions.skipTo` (every `Actions` implementation and fake);
  - `PlayerScreen(numberOf, …, onSkipTo)`, which conflicts with the Sonos output-picker edits only at the call site in
    `MusicRoot`;
  - `Cover(…, fallback, content)`: `fallback` comes before `content`, so a positional `content` argument breaks;
  - `Commands.ALL`.
- **Sonos:** `skipTo` and `goTo` drive `exo` directly. The Sonos output must route `SKIP_TO` (and `THEN_SKIP_TO`) as
  it routes next.
- **Storytel per-book covers:**
  - `music::art_in_library` (`music.rs` ~978) serves only `source IN ('nrk','rss')`, and `art_candidate` skips items
    without a url.
  - Setting `music_items.art_hash` for books needs both widened.
  - On the phone, `PlayItem.artHash` and `showsCarousel` work as they are.

## Verified OK

- **Contracts:**
  - The listing snapshot is byte-identical in both copies, with one own hash and one null; version
    `a8146790d70b89a3` is pinned in `MusicPlanTest` (QA M4).
  - `Listing.kt` keeps `art` only as `[0-9a-f]{64}`: an old server's URL becomes null, and an old app's `safeUrl`
    turns a hash into null.
  - The AIDL is appended, `oneway`, and identical in both apps.
  - `bridge_contract.json` `want_art` {600, 5} is pinned on both sides (`MusicPlanTest`, `BridgeTest`).
- **H1, old phones:**
  - Room 2 adds `items.artHash` (nullable, no default) through an `AutoMigration` with `onPostMigrate` running
    `UPDATE listings SET version = ''`.
  - The next sync's `listingsWanted` pulls every listing the launcher has, and `applyListing` re-applies it.
  - `markSeen` adds no Nytt for already-seen keys, and item errors of the same version stay.
  - The rows are kept, so the prune still works.
  - An older launcher drops `wantArt`, and the app shows series covers.
- **Server:** checked by the server half of this review.
  - **Migration 0070:** the columns, `CHECK`, defaults and partial indexes are right; `art_refresh = 1` only for NRK.
    `ITEM_COLUMNS` (21) and the binds agree.
  - **The listing and covers route:** `build_listing`'s `art = art_hash` moves the version. `art_in_library` requires
    the phone's ticked NRK/RSS entry, a non-pending item, a listed version and the hash; the hash is checked by
    `path_for`, and `cover_in_library` goes first.
  - **Budgets:** 20 per check (`min` with the day's room); steps 1, 2 and 6 send no request and aren't stamped;
    `Got::Skipped` leaves the item untried; `job.spent()` is honoured; the pass runs after the manifests and not on
    recheck-only checks; the 16:9 fetch only on a root read, at most daily.
  - **Steps 1-6 and the re-judge:** `own` → `same` with the file pruned; `same` → untried; up to 3 old covers'
    prints. A copied hash is checked for its file under the lock at the commit.
  - **dHash:** standard left > right bits, row-major, on the processed 512 px JPEG; Hamming by `count_ones`.
  - **The refresh:** only known keys' `art_url`, untried; no adds or drops; cleared only after a complete walk;
    skipped for a program chain and the RSS fallback.
  - **The store:** `MUSIC_ART` has its own lock and directory, held from writing to the commit, with the lock order
    covers → art everywhere. It prunes after a changing `finish` and after an entry delete, isn't in the backup zip,
    and `recover_missing` forgets the rows so they are fetched again.
  - **The rest:** the PWA count line, the help text and the log line.
- **Launcher:**
  - `wantArt` checks the caller first, then keeps only 64-hex hashes, each once, `now` first, at most 5 and 600 in
    all.
  - `artToFetch`:
    - nothing offline;
    - `now` on any network, including roaming, as decided;
    - `later` only on an unmetered network, or with `mobile_data` and not roaming;
    - never one it holds or one backed off;
    - at most 50 a run.
  - `artToDelete` never deletes a library cover or a picture in the current want set. It reads the set again after the
    fetch.
  - The music app's set leaves out what it holds, so a picture goes only after the copy. An open PFD survives an
    unlink.
  - No library: the want set and failures are cleared too, and `clearAll` removes `want_art.json`.
  - Nudge loops are bounded: a failing picture produces no "changed", and the held nudge happens once per set.
- **Music app:**
  - **The want set:**
    - `nowWindow` (±2, wrapping, deduplicated);
    - the keep sets with the same `keepSet`/`wantedDownloads` order as the downloads;
    - Nytt;
    - library covers left out;
    - held ones left out of the lists but in the prune's universe, with the 20 recent pictures;
    - prune only on a parsed library, after the listings are applied;
    - the small copies pruned with the same rule (QA L3);
    - the covers counted in `cache_bytes`.
  - **`showsCarousel`:** more than one item and at least two pictures, the fallback counting as one. An album with one
    shared cover and a podcast without art look as today.
  - **Swipe routing and the switch view:**
    - `SKIP_TO` is in `Commands.ALL` and not in `PLAYS`. It is ignored unless the entry is loaded and the key is in
      the queue and isn't playing (`swipeTarget`). It never restarts.
    - An unplayable target skips on in the swipe's direction (`firstPlayable` equals the old loop).
    - It only seeks, so paused stays paused, the sleep timer isn't cancelled, and a tap on the centre is the
      play/pause button (which cancels the timer as before).
    - `THEN_SKIP_TO` on a restored queue stays paused.
    - The swipe is a `PlayRequest`, and `via` lets the player pass through an earlier swipe's target.
    - A swipe back to the item playing while the first request stands is a real skip back, because the switch view
      has already moved `now.index`.
  - **The pager:** it follows the player (headset, auto-advance, buttons, a list pick) never while scrolling, and
    re-keys on a new key list (shuffle, a dropped item, another entry).
  - **`replaceMediaItem`:** the same source, so no transition and no restart (see "Missing tests"). The queue build
    decodes nothing on the main thread, and small copies are made on `writer`.
  - **Decoding and slots:** `coverBitmap` decodes on IO, and `coversVersion` retries a missing slot once per version
    (QA M5). `Cover`'s fallback means a missing picture never shows the glyph.
  - **TalkBack:** "Pause: / Spill: <title>" for the centre and "Forrige: / Neste: <title>" for the neighbours, in nb
    and en; the pager's scroll actions land in the same settle path.
  - **Layout (`Fit.kt`):**
    - A = min(224, the room, width − 88). At 288, 328 and 379 dp the row is 2 peeks + 2 gaps + A, the peeks are
      ≥ 36 dp, the neighbours' pull is A·0.1335, and pages beyond the neighbours fade to 0.
    - Below 96 dp it falls back to the single cover.
    - The control row, `requiredSize` round buttons and the bottom bar are unchanged, so the 288 dp rule holds.

## Fixes (2026-10-12)

| Finding | Fix | Commit |
|---|---|---|
| M1 | The day's 400 counts requests: one `music_art_requests` row per image GET (the 16:9 reference too), dropped after a day. A judged or failed item whose URL changed only in its query (same scheme, host, port and path, `same_resource`; vibb keys by episode) keeps its result and takes the new URL with no request. A judged picture whose path changed is fetched again at most weekly (`ART_MOVED_RETRY_DAYS`). Test `churning_picture_urls_are_not_fetched_again`; the 20/400 test counts requests. | `5befd388`, `c93bc466` |
| M2 | A network error, timeout or 5xx uses none of a picture's 3 tries (the item waits a day and keeps its result); 3 host errors in a row (`ART_NETWORK_FAILURES`) end the art pass. Only a 4xx, a refused address or an undecodable picture counts. Test `a_dead_image_host_ends_the_pass_and_costs_no_tries`. | `5befd388` |
| L1 | The NRK root is read while `art_refresh` is set or `cover_alt_at` is NULL (once per entry; a root without a 16:9 picture stamps it too). Test `an_existing_nrk_entry_reads_its_wide_picture_before_the_refresh`. | `96c547b9` |
| L2 | With the backlog off, items first seen after `art_backlog_at` still count as new. Test `with_the_backlog_off_a_new_item_that_missed_its_check_stays_new`. | `9939db89` |
| L3 | Both tests wait on gates: `CannedFetch::hold`/`release` + `wait_in_flight` for the flag test; `Sweep::pause_before_commit` (test only) stops a check before its commit, where the lock test asserts the store lock is held and a prune keeps the picture (it fails without the lock). | `f49677f3` |
| L4 | The twin merge needs prints within 2 bits, the same coarse colour (`photos::tone_image`, a 2x2 RGB mean at 5 bits, `music_items.art_tone`) and a print with structure (`low_detail`: not ≤ 8 or ≥ 56 bits set). **Known limit, documented in `server/CLAUDE.md`:** "same as the series picture" stays dHash at 5 bits, so a flat episode picture can count as a flat series cover (the cover shows), and a recoloured series picture counts as the same. | `5677893c` |
| L5 | `squareImage`/`image` that are null or `[]` count as missing, on episodes and the root (vibb's `or`). Tested in `music_sources`. | `bcc471e1` |
| L6 | `recover_missing` returns what it forgot; then `restamp_versions` stores each listed entry's version again from its rows. Tested in `episode_art_is_pruned…`. | `654403ba` |
| L7 | `refreshArt` reads the current item's art from the store (`MusicDao.itemArt`) and updates the queue (`withItemArt`), so a picture a listing gave it mid-queue reaches the session, the lock's tile and Home. | `7906a0cc` |
| L8 | A map entry whose small copy is gone counts as missing (`hasCopy`, `smallArtMissing`): the series cover shows, and the copy is remade when its cover lands; the prune moves `coversVersion` for small copies too. | `7906a0cc` |
| L9 | `MusicBridgeService.wantArt` stores the set in the binder call (oneway calls from one client arrive in order); only the nudge and the courier check run later. The held nudge is the pure `heldNudge`, tested. | `7906a0cc` |
| L10 | `restoreThen` runs under a `Mutex`: two quick swipes on a restored queue load it once, then skip in order. | `7906a0cc` |
| L11 | A 5xx is "try later" like a network error (`artAnswer`); only a 4xx or a wrong hash or size counts toward the day's wait. Tested. | `7906a0cc` |
| L12 | Covers decode scaled to the player's 224 dp (`coverDecodePx`: 336 px at density 1.5), never above the file; the comment and the implementation status say so. | `7906a0cc` |

After the fixes, rebased onto `main` `7335a66a`, under the build lock: server `cargo test` 618 passed, `fmt --check`
clean, clippy 12 (all old); launcher `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true` (stub
`tsnet.aar` in, then removed) 1027 tests; music `testDebugUnitTest assembleDebug assembleRelease lint
-PwarningsAsErrors=true` 189 tests, lint nothing new.

Coordination: the list above is in design 27's "Implementation status" for the other session.
