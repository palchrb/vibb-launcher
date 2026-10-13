# QA 30 design review - streaming and downloads on mobile data, the stream saved as it plays (2026-10-13)

Scope: `docs/design/30-music-streaming-data.md` at `e0949bb5` (branch `design-30`), read against the root, server,
launcher and music `CLAUDE.md`, the user's four final decisions, and the code the spec builds on: `main` at `3292cd32`
(server `music.rs`, `handlers/music.rs`, `models.rs`, migrations to 0072, `templates/device_detail.html`,
`static/scroll-restore.js`; launcher `music/MusicPlan.kt`, `MusicSync.kt`, `MusicFileDownloads.kt`, the contract
tests; music `media/`, `sync/`, `logic/Offline.kt`, `NetGate.kt`, `CacheKeys.kt`, `playback/PlaybackService.kt`,
`LocalRenderer.kt`, `PlayerHub.kt`, `bridge/BridgeConfig.kt`) and design 29's diff for the music app
(`git diff 73787e7d..design-29-impl -- music/`: Room 3, `Sync.runLock`, the two sync phases, `Downloads.kt`). The
Media3 claims were checked against the 1.7.1 classes in the Gradle cache (`javap -c`): `CacheDataSource`,
`CacheDataSink`, `ProgressiveMediaPeriod$ExtractingLoadable`, `ExoPlayerImpl`, `DefaultTrackSelector$AudioTrackInfo`.
Line numbers are `main`'s unless a branch is named. QA wrote no product code.

**Verdict: buildable after a spec fix round.** The shape is right and matches the user's decisions: two switches with
the old value in both and AND on the old wire key, cache-through above depth 0, "on the phone" still meaning a `done`
row, a played-extras class that never touches the keep set. The Media3 mechanics the spec leans on hold in 1.7.1 (the
hole lock, `CacheWriter` fetching only gaps, copy-only reads, the audio track pinning). One High: the spec treats "what
the player holds" as the current item only, but ExoPlayer opens the next item before it plays, and the settle step
blocks on the cache's locks. That can stall the download job, and with design 29's `runLock` every sync, until the
player lets go. The Mediums are spec edits, not a redesign. Counts: 1 High, 5 Medium, 8 Low.

## High

1. **H1: "held" and "busy" are the current item only, but the player also holds the next one, and settle blocks on
   the cache's locks** (§1.5, §1.6, §5.1 `downloadPlan(..., held, busy)`, §5.2 `PlayerHub.loadedLocal`,
   `CacheThrough.settle`/`catchUp`).
   - **What the spec misses.** When the current item is fully buffered (its last ~50 s, `DefaultLoadControl`),
     ExoPlayer creates and loads the next item's period. A progressive load keeps its data source open while it waits
     on its load condition: `ExtractingLoadable.load` blocks in `ConditionVariable.block` between reads and closes the
     source only after the loop. With a cache sink, `CacheDataSource` keeps the hole lock `[position, end)` all that
     time (1.7.1 `openNextSource`: `releaseHoleSpan` only when `cacheWriteDataSource == null`). So a pause in the last
     ~50 s of item A, which the sleep timer often causes, leaves item B (A's successor, not `loadedLocal`) locked for
     hours.
   - **(a) The job.** B is the bookmark's next, so it is in the keep set and wanted. It isn't `busy`. `Downloads.fetch`
     -> `ProgressiveDownloader` -> `createDataSourceForDownloading` (`FLAG_BLOCK_ON_CACHE`) waits in
     `SimpleCache.startReadWrite` until the player closes B or WorkManager stops the job at 10 minutes. Every item after
     B in that run waits too (`Downloads.kt:146-156`). The interrupt surfaces as an `InterruptedIOException`
     (`CacheDataSource` re-interrupts and throws it), not an `InterruptedException`, so `DownloadRun.fetchFrom`
     (`DownloadRun.kt:153-161`) treats it as an ordinary failure inside a cancelled coroutine. The next run blocks again.
   - **(b) The sync.** If B is a played, not-done row (a partial extra, or any item under shuffle, where the next isn't
     the keep set's next), `catchUp` runs its copy-only `Downloads.fetch` on B. That is again `ForDownloading` and
     blocks the same way. It runs inside design 29's `Sync.runLock` (`design-29-impl` `Sync.kt:101-103`), so every
     later sync (library, listings, news, report) queues behind it until the kid moves on. A coroutine cancel doesn't
     wake `Object.wait` unless the call is `runInterruptible`. The same blocking hits settle at "the player leaves an
     item". If that runs on the service's `writer` (one thread, `PlaybackService.kt:172`, where every position and
     bookmark write goes), position saves stall. It also hits a new queue that starts on the same item (a tap on the
     episode playing): the new period takes the lock before settle reads.
   - **(c) Deletes.** Prune and eviction skip only `held`. A preloaded B that is an extra (least recently played: the
     first to go) is deleted under its reading `CacheDataSource`. When B becomes current, its "copy" (`playability` ->
     `LOCAL`, the gate never asked) refetches the holes from upstream: a stream on mobile data with the streaming switch
     off.
   - **Fix.**
     - `PlayerHub.loadedLocal` becomes a set: every item the local player may have a period for, which in practice is
       the current item and the next one in its timeline. `held` (never pruned, evicted or settled) and `busy` (never
       fetched while a progressive stream isn't done) both take the set.
     - Settle never blocks. Prefer a non-blocking check over the downloader:
       - progressive: the stored content length (`ContentMetadata.getContentLength`) and `Cache.isCached(key, 0, len)`;
       - HLS: the two cached playlists parsed through a copy-only `createDataSource()` (not `ForDownloading`), then
         `isCached` for every segment of `pickVariant`'s variant.
     - If the copy-only `Downloads.fetch` stays instead, it runs in `runInterruptible` on IO with a timeout, outside
       `runLock` and never on `writer`, and never for an item in the held set.
     - The service's release can't settle (its scope is cancelled), so the sync's catch-up covers it. Say so.
     - Tests: `OfflineTest` plan rows with held = {current, next}, and a `GuardsTest` pin that settle and `catchUp`
       don't run on `writer`.

## Medium

1. **M1: not every written byte gets a row, and a key without a row is never deleted** (§1.5 "Every byte in the
   cache thus has a row ... (a stream with no row would leak)"). `markPlayed` runs from `startedPlaying`
   (`PlaybackService.kt:693, 1031, 1078`), but the sink writes from the moment a period loads:
   - the preloaded next item (H1) that never starts: the kid leaves in A's last ~50 s, or the queue is replaced (with
     shuffle that item is outside the keep set);
   - a queue loaded paused: `start(..., play = false)` -> `renderer.load` -> `exo.prepare()`
     (`LocalRenderer.kt:111-113`) buffers ~50 s with no play;
   - an entry set to "Ingen" mid-queue (M2: the whole queue keeps writing). If `markPlayed` reads the entry's current
     depth ("nothing for ... depth 0"), those items get no row;
   - a span committed by a still-open `CacheDataSource` after prune or eviction removed the resource and the row.

   Prune, eviction and `removedEntries` all iterate rows (`DownloadPlan.kt:47, 58`), so these keys stay until the app
   is uninstalled, even after their entry leaves the library. That is unbounded growth, and the depth-0 promise breaks
   on the third path.

   **Fix:** create the row when bytes can first be written. The sink of M2 does an `INSERT OR IGNORE` of a not-done
   row with `playedAt` null before it opens. An unplayed row outside the keep set is then pruned by today's rule, and
   `markPlayed` only sets `playedAt`, keyed off the item's tag and never the current depth. Add a backstop: a pure
   `orphanKeys(bytesByItem.keys, rows' cacheKeys, held)` swept at every sync, with an `OfflineTest`.

2. **M2: cache-through is decided per queue, not per item, and a full disk still fails playback** (§1.4 "Decided per
   media item when it is built ... writes until that item is left", §5.2 "free bytes read once per queue build").
   `ExoPlayerImpl.setMediaItems` builds every item's `MediaSource` at once (1.7.1: `createMediaSources` ->
   `MediaSource.Factory.createMediaSource` per item). `ItemMediaSourceFactory.createMediaSource`
   (`ItemMediaSourceFactory.kt:40-47`) binds the data source factory, and with it the sink choice, right then.
   - The 1 GiB margin is checked once for a queue that can auto-advance for hours: an NRK series at 192 kbit/s writes
     ~86 MB an hour. Eviction runs only at a sync or a download round.
   - "Ingen" set mid-queue keeps writing for every remaining item of the queue, not just the current one.
   - When the disk does fill, the `CacheDataSinkException` fails the read. Without `FLAG_IGNORE_CACHE_ON_ERROR`, each
     load retry writes to the same sink again until the item errors ("Kan ikke spilles", the queue moves on).

   **Fix:** `playbackFactory` gets `setCacheWriteDataSinkFactory(guarded)`, a `DataSink` that decides at each `open`
   (per hole or segment) from live state:
   - the entry's depth in the current library;
   - the tag's static part (NRK/RSS, not an own file or a Storytel book);
   - free space at least `EXTRAS_FREE_FLOOR` now.

   If it declines, it doesn't write: `CacheDataSink`'s own `dataSpec = null` path. The playback factory also sets
   `FLAG_IGNORE_CACHE_ON_ERROR`, so a sink error degrades to plain streaming. Correct §1.4's sentence to match.

3. **M3: the extras byte cap contradicts decision 4** (§1.8, §9 bullet 4). Decision 4 says "Extras go only when space
   is needed". `EXTRAS_MAX_BYTES` (1 GiB) evicts the least recently played extras when they pass 1 GiB, even with
   40 GB free. §9 lists the cap as a default to veto, but doesn't say that it overrides one of the user's final
   decisions. **Fix:** drop the cap and let the free floor alone bound the extras. They never push free space below
   1 GiB (1.5 GB while a Storytel book is wanted), and they grow only by what the kid plays. Or put the trade-off to
   the user explicitly. If the cap stays, the card's line says "up to 1 GB".

4. **M4: `extra_bytes` as the version signal ships before the behaviour it signals, and a launcher-only report reads
   as "older"** (§1.3, §3, §7 step 1).
   - (a) Step 1's contract commit makes the music app always send `extra_bytes` and the launcher pass it on. The music
     app reads the two switches only from step 3a, and the launcher writes them only from step 2. A music app or
     launcher released in between sends `extra_bytes` while it still runs on `mobile_data` = both. The card stays
     silent, and with only streaming on, nothing streams off Wi-Fi.
   - (b) Before the app's first report, `musicState` (`launcher/.../music/MusicPlan.kt:220-229`) sends `package`,
     `version_code` and `bridge` with no app fields. With no `extra_bytes`, a brand-new phone gets the "older" note.
     The spec's "No report yet: nothing" can't be told apart on the server as written.

   **Fix:** move the `extra_bytes` contract commit to step 3a: the fixture, the launcher's key list and the music
   app's `stateReport`, all in one commit (the server's `MusicState` field can land in step 1). `older_phone_note`
   requires an app report (`cache_bytes.is_some()`, always sent since 21) and a missing `extra_bytes`. Server tests
   cover both cases.

5. **M5: a source that serves different bytes per request makes patchwork copies** (§1.5, §1.6 "Nothing is downloaded
   twice", §2 "the job fetches the second half only").
   - The cache key is per item (`itemCacheKey`, `CacheKeys.kt`), not per response. `CacheDataSource` sends every
     upstream request to the original uri; the redirect it stores (`exo_redir`) is only reported.
   - A host that stitches ads per request (dynamic ad insertion, common for commercial podcast hosts) returns
     different bytes at the same offsets. A resume on another day, or a seek, writes spans of a second file next to the
     first. Settle, or the job filling the gaps, then marks the patchwork `done`, and it plays with jumps for good.
     Only a parse failure (`bad_media`, `PlaybackService.kt:1157-1165`) drops a copy.
   - `DownloadRun`'s `http_416` path (`DownloadRun.kt:116-123`) catches only a file that shrank under the partial.
     Today this needs an interrupted download; with cache-through it is every partial listen followed by a resume.

   **Fix:** a total-length guard in the shared http factory (`MediaCache.http`). A response at a position above 0
   whose `Content-Range` total differs from the stored content length means the file changed:
   - for the job, it goes through the existing `http_416` path (drop the partial, fetch from 0);
   - for the player, the item's spans are dropped and the item plays on uncached;
   - or the spec decides that such hosts get no cache-through.

   State the assumption ("a source serves the same bytes for every request") in §1.6. Add a device check against a
   fake host whose length changes per request.

## Low

1. **L1: the download job's report sends `extra_bytes` 0.** The report is one document, and `DownloadWorker` writes it
   too (`Downloads.kt:152, 164`). The spec has only the sync pass `evict`'s total, so every job run would overwrite
   the value with 0 and the card's line would flap. **Fix:** `writeReport` computes it itself (`plan.extras` x
   `bytesByItem`).
2. **L2: the Storytel floor without a login.** `downloadPlan` wants a Storytel entry's books whether or not the phone
   has a login (`DownloadRun` stops on `LOGIN` only later). So `extrasFloor(plan.wantsStorytel)` holds 1.5 GB for good
   on a phone with a Storytel entry ticked and no login, or a refused one, and evicts extras for books that can't
   download. **Fix:** `wantsStorytel && StorytelAccount.state() == "ok"`.
3. **L3: an extra's episode picture is pruned.** `artWant` (`sync/ArtPlan.kt:26-47`) keeps the now window, the keep
   sets, Nytt and the last 20 shown (`RECENT_ART_MAX`, `EpisodeArt.kt:24`). An extra beyond those loses its picture,
   which breaks design 27's rule "a kept episode's picture stays as long as its audio" (`Sync.kt` art prune comment).
   **Fix:** `artWant` puts `plan.extras`' art in `keep` (fetched as `later`).
4. **L4: `held` is read before the deletes, and the copy path has no gate.** The sync computes its plan and then
   deletes. The service reads rows in `load()` and sets `loadedLocal` only after `renderer.load`. A copy deleted
   inside that window plays as `LOCAL` and streams its holes from upstream. **Fix:** the service claims the item
   before it reads rows, and prune/evict re-check the held set right before each `MediaCache.remove`. Worth noting:
   ExoPlayer already opens the next item's upstream in the current item's last ~50 s, whatever `playable` says (on
   `main` today too). A gate inside the upstream factory (an error when `streamAllowed` says no) would make the
   streaming switch exact.
5. **L5: a re-resolved NRK URL drops a half-streamed HLS partial.** `staleThrough` (§1.6) and `DownloadRun.kt:111`
   compare full URLs, but HLS cache keys drop the query on purpose (`CacheKeys.kt` header, `hlsResourceKey`). When the
   sweep re-resolves an item (expiry recheck, `music_sweep.rs:2058`), the new token deletes a good partial and it is
   fetched twice. **Fix:** compare `withoutQuery` for HLS.
6. **L6: the PWA.**
   - A device page opened before the update posts only `mobile_data`. "A missing checkbox is off", so that save turns
     both switches off (`save_device_settings`, `handlers/music.rs:3722-3765`). Add a hidden `v=2` field and refuse a
     post without it ("reload the page"), or map `mobile_data` to both.
   - The streaming label's "What plays is saved on the phone as it plays" isn't true at "Ingen" or below 1 GiB free.
     Add "(not for an entry that keeps nothing)".
   - No scroll jump: the form keeps `id="music-settings"`, the redirect anchor and `scroll-restore.js`.
7. **L7: tests.**
   - The 0073 test needs a database at 0072. `old_database` in `server/src/tests/docker.rs:15` builds one; move it to
     `tests/mod.rs`.
   - `server/src/tests/music.rs:600-603` (0049's defaults) selects `music_mobile_data` and must change too.
   - The spec's server test paths are `server/src/tests/...`, not `tests/...`.
   - The `playedAt` rules of `markPlayed` and `upsertDownload` have no test, and `music/` has no Room tests. Make the
     merge a pure function (`keepPlayedAt(stored, incoming)`) and unit-test it.
   - "the downloader never runs with the player's held item" should pin that `DownloadWorker` passes `PlayerHub`'s
     set, since the pure plan test covers the rule only.
8. **L8: small gaps.**
   - Say in §1.8 that Storytel books never become extras (`markPlayed` skips them).
   - `catchUp` should enqueue the job once per sync, not once per row.
   - Partial extras are re-checked at every sync forever (cheap once settle is a span check, H1).

## The §9 defaults

- Both switches take the old value; the old key is AND: **agree** (OR would download Storytel books over mobile data
  under "streaming only").
- `later` pictures and the launcher's own-file downloads follow the downloads switch: **agree**.
- Played extras include a played copy that left the keep set: **agree** (decision 4, first bullet).
- `EXTRAS_MAX_BYTES` 1 GiB: **veto, or ask the user** (M3). The 1 GiB / 1.5 GB floors: agree. No parent setting:
  agree.
- A partial extra is kept but doesn't play offline: **agree**.
- The player is pinned to the download's HLS rendition: **agree** (checked below).
- A paused progressive stream isn't completed in the background: **agree**, with held/busy as a set (H1).

## Step order

Server -> launcher -> music 3a -> 3b is the dependency order, and every step works against the others' old versions.
The exception is `extra_bytes`, which must ship with step 3a (M4). Step 3b on top of design 29 merged into main is
right: Room 3 -> 4 is one nullable column (`AutoMigration(3, 4)` is enough), and `startedPlaying` there is design
29's `deleteNews` version (`design-29-impl` `PlaybackService.kt:1181`), which `markPlayed` joins. Step 2 touches
`music/` (two constants, `BridgeTest`), so `music.yml` runs on it.

## Checked and fine

- **The hole lock** (§1.6): in 1.7.1 a read-only `CacheDataSource` releases the hole span at once. With a sink it
  keeps the lock while it reads the hole. The download data source sets `FLAG_BLOCK_ON_CACHE`, and the player's
  doesn't (it reads upstream past a locked range, re-checking every 100 KB). The progressive player's `DataSpec` flags
  are 6 (`DONT_CACHE_IF_LENGTH_UNKNOWN | ALLOW_CACHE_FRAGMENTATION`). `CacheDataSink.open` skips an unknown-length
  spec, so a host without `Content-Length` writes nothing and never settles. That is consistent with the spec, and
  worth a sentence.
- **`CacheWriter` fetches only gaps.** Progressive: `getCachedLength` skips cached blocks and opens the source only
  for a hole. HLS: `SegmentDownloader` drops fully cached segments before writing. A partial written by a stream is
  completed with Range requests, and the row's url equals `sourceId` (`Storytel.kt:84`), so `DownloadRun.kt:111`
  keeps it.
- **Settle works as a check** (§1.5). With no upstream, `CacheDataSource` uses the placeholder, whose `open` throws.
  Nothing opens for cached spans, and the content length (written by the sink at open or EOF) bounds the loop. HLS
  works too: the cached multivariant and media playlists have stored lengths. It isn't a documented Media3 pattern,
  but it is sound, apart from the blocking (H1).
- **HLS pinning** (§1.7). `forceHighestSupportedBitrate` makes `AudioTrackInfo` fixed-only (not adaptive).
  `maxAudioBitrate` filters on `Format.bitrate` (= `BANDWIDTH`, what `pickVariant` reads). Out of constraints the
  ordering reverses to the lowest, as `pickVariant` does. They differ only on a mix like [256 k, unknown], where Media3
  takes the unknown one. The playlist tracker always loads `variants[0]`'s media playlist first, a stray of a few KB
  under the item's prefix.
- **Room**: today's `upsertDownload` is `REPLACE`, so a job's row would wipe `playedAt`. The spec's transactional
  upsert is required and enough.
- **Migration 0073**: `DROP COLUMN` on `device_policy` has precedents (0012, 0017). No index or trigger names
  `music_mobile_data` (0071 and 0072's triggers name other columns; 0028 and 0066 insert into other tables). sqlx's
  bundled SQLite supports it, `pre_migration_backup` runs first, and a rollback is the backup (DEPLOY.md:375).
- **The gate**: design 13's formula as ported (`NetGate.kt:26-35`, launcher `downloadGate`). The job's WorkManager
  constraint is fixed at enqueue (`UNMETERED` / `NOT_ROAMING`, `Downloads.kt:74-87`) and re-checked live before each
  item (`:148`). A switch turned on reaches a waiting job through the sync's re-enqueue (REPLACE).
- **Cross-version**: the launcher's `musicPolicy` requires `mobile_data` (`MusicPlan.kt:83`), so the old key stays,
  and AND never spends what the parent turned off. Every row of §1.3's table holds. `musicState` passes known keys
  only (`MusicPlan.kt:222`).
- **Contracts**: both apps' contract tests compare `restrictions`' key set with what they write and read
  (`MusicPlanTest`, `BridgeTest`), and the two `bridge_contract.json` files are byte-identical today.
  `music_state.json` is checked on all three sides; `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin the
  music object.
- **Depth 0** writes nothing through the read-only path; the only leaks are M1's.
