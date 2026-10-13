# QA 30 step 3b (code) - cache-through, played extras, the storage limit

Reviewer: code QA, 2026-10-13. Branch `design-30-s3b`: `cd8591fa` (step 1's fix round `8f55d2b7` merged onto step
3a's `70a7e801`) and `d3c8b648` (step 3b). Read against `docs/design/30-music-streaming-data.md` (§1.4-§1.12, §2, §5,
§6, §8, the Design QA section and the step 3b status), `qa-30-design.md`, `qa-30-s1-code.md`, `qa-30-s3a-code.md`
(`qa-30-s2-code.md` from `design-30-s2`'s history), and the root and music `CLAUDE.md`.

I also read the code around the step:
- music: `media/{MediaCache,ItemMediaSourceFactory}.kt`, `sync/{CacheThrough,DownloadPlan,DownloadRun,Downloads,Sync,
  ArtPlan}.kt`, `playback/{PlaybackService,PlayerHub,LocalRenderer,Renderer}.kt` (`activeFor`, `start`,
  `onPlaybackResumption`, `mediaItem`, `refreshArt`, `onItemChanged`, `onIsPlayingChanged`, `onError`,
  `playOnUncached`, `markPlayed`, `updateHeld`, `onDestroy`, `comeHome`), `logic/{Offline,CacheKeys}.kt`,
  `data/{MusicDb,Store}.kt`, `model/Library.kt`, the manifests (main, debug, both merged), `schemas/.../{3,4}.json`;
- the new and changed tests, `scripts/fake-audio.py`, `music.yml`, `docs/testing/emulator.md` §6f;
- Media3 1.7.1 and OkHttp 4.12.0 bytecode (`javap`) where a finding depends on them: `ExoPlayerImpl.replaceMediaItems`,
  `ProgressiveMediaSource`/`WrappingMediaSource.canUpdateMediaItem`, `DefaultLoadErrorHandlingPolicy
  .getRetryDelayMsFor`, `CacheDataSource`, `CachedContentIndex.maybeRemove`, `ExoPlayerImplInternal`, OkHttp's
  `AndroidPlatform.isCleartextTrafficPermitted`.

Line numbers are `d3c8b648`'s. No product code was changed. The mutations below were temporary, each reverted with
`git checkout -- music/app/src/main`, and `git status` was clean before this file was written.

**Verdict: not ready to merge as is - one High in 3b's own code (H1), small to fix.** The rules are right and well
tested: every pure mutation went red, the held set is re-read before every removal, the sweep can't reach a row's
bytes, the migration is a clean auto migration, and "Ingen" writes nothing. But the player's recovery from a changed
source (`playOnUncached`) never leaves the cache-backed source, so the length guard's case - a podcast host that
stitches ads per request, after a pause - ends in a skipped episode and an item error (H1). H2 is older than design 30
and outside 3b's diff, but it stops §8 check 10 (and §6h, §6i, and Sonos on a real phone) before it starts. M1 is a
test gap on the user's "never evict" rule.

Counts: 2 High (H2 pre-existing), 1 Medium, 6 Low, 4 Nits, plus notes.

## What I ran (under the shared build lock, `--no-daemon --max-workers=2`)

- **Unchanged `d3c8b648`**, from `music/`: `assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 654 tests, 0 failures** (`checkReleaseHasNoDebugFeed` passed, lint
  clean). `python3 -I scripts/fake-audio.py --selftest`: **OK**.
- **Mutation runs.** Each group was a temporary product-code edit, run against the test classes named, then reverted.
  The first group's nine mutations each have their own test (the red test names map one to one).

  | Mutation | Tests run | Result |
  |---|---|---|
  | M2 `extrasToEvict` ignores `held` | `OfflineTest`, `ThroughSinkTest` | red: `the keep set and the held items are never evicted, even over the limit` |
  | M3 `extrasFloor` ignores `loginOk` | (same run) | red: `the Storytel floor only while a book is wanted and the login is ok` |
  | M4 `staleThrough`: no row is not stale | (same run) | red: `a stale partial - bytes with no row, or a row from another source` |
  | M5 `sourceChanged` without the `200 -> contentLength` branch | (same run) | red: `a different total length at a resume is a changed source - unknown lengths are not` |
  | M6b `orphanResources` ignores the held keys | (same run) | red: `orphans - keys of no row and of no row's HLS prefix, never a held one` |
  | M7 `throughWrites` without `depth != 0` (the "Ingen" guard) | (same run) | red: `the sink writes only for NRK and RSS above depth 0, ...` |
  | M15 `storageLimitBytes` in GiB | (same run) | red: `over the limit the least recently played extras go until under it` |
  | M17 `keepPlayedAt` = incoming | (same run) | red: `the played time survives the job's row` |
  | M18 `ThroughSink` makes the row even when it declines | (same run) | red: `ThroughSinkTest` `the sink makes the row once, ...` |
  | **M1** `downloadPlan`: a **played keep-set row** (newest N, bookmark, next) is an extra too | `OfflineTest`, `StorytelDownloadTest`, `EpisodeArtTest`, `ReportTest`, `GuardsTest` | **survives** (M1 below) |
  | M8 `downloadPlan` without `entry.cache != 0` (depth 0 keeps extras) | `OfflineTest` | red: `depth 0 has no extras - every row is pruned but the held ones` |
  | M12 `downloadPlan` without the busy rule | (same run) | red: `the held set is the current item and the next - ...` |
  | M6 `sweepOrphans` without the held re-check before each removal | `GuardsTest` | red: `every MediaCache remove outside PlaybackService's own load re-reads heldLocal` |
  | M9 `CacheThrough.remove` without the held re-check | `GuardsTest` | red: the same test |
  | M10 `settleLocked` without its held check | `GuardsTest` | red: `settle and catchUp never run on writer, and catchUp runs outside runLock` |
  | M11 `evict` passes `emptySet()` to `extrasToEvict` instead of `heldLocal` | `GuardsTest`, `OfflineTest` | **survives** (N1) |
  | M13 no `markPlayed` at `onIsPlayingChanged(true)` | `GuardsTest` | red: `the phone's player marks what it played, an existing row only` |
  | M14 `queueRows` removes the bytes of the items held **before** (the check flipped) | `GuardsTest` | red: `every MediaCache remove ...` |
  | M21 `start()`'s give-up branch without `updateHeld(loaded = true)` | `GuardsTest` | **survives** (L6) |
  | M22 `onPlaybackResumption` without its posted `updateHeld(loaded = true)` | `GuardsTest` | **survives** (L6) |
  | M23 `catchUp`'s row filter without the held check | `GuardsTest`, `OfflineTest` | survives - harmless: `settleLocked` checks again under the lock (M10 pins that one) |

## The implementer's deviations (the step 3b status, "Interpreted")

1. **`markPlayed` also at `onIsPlayingChanged(true)`.** Agreed, and needed: `start()` calls `startedPlaying` right
   after `renderer.load`, before the loader's first open, so for a fresh stream the `UPDATE` would find no row. Two
   consequences, both acceptable: every resume after a pause or a seek re-stamps `playedAt` (that is what "least
   recently played" should mean), and an item the kid starts for a second - a carousel swipe-through while playing -
   becomes a partial extra of ~1 MB (the 50 s buffer). See L3 for what that costs at every sync.
2. **Claims with a lifetime.** Agreed. Every path from `activeFor` (LOCAL) reaches `updateHeld(loaded = true)`:
   `start()` on both branches, `onPlaybackResumption`'s post (Media3 runs the session's `setMediaItems` inline when
   the future is set on the main thread, so the post comes after it), and `onDestroy`. Only an exception between the
   claim and `start` leaves a claim behind, and two of the ends are unpinned (L6).
3. **The stale delete checks the held set from before the claim.** Agreed: re-reading `heldLocal` would always find
   the queue's own claim. `before` is exactly "an item an old period may still have open".
4. **A held extra counts in `extra_bytes` but is never evicted.** Agreed. The PWA's "kept alone" figure (`audio_bytes
   - extra_bytes`) then leaves out a played extra the kid is listening to, which is right: it goes once let go.
5. **The mutex per row; the sweep without one.** Agreed: no deadlock (nothing under it waits on another lock or on the
   player), `queueRows` waits at most for one row's removal. The sweep's reasoning in the code is slightly off but its
   behaviour is safe (N4).
6. **The job keeps a partial the player holds.** Agreed for the "another source" drop. For the job's `http_416`
   refetch of a held HLS item the kept segments make CacheWriter skip them and the guard trip again, so the attempt
   is recorded as a failure and retried later. Rare (HLS segments are immutable); acceptable.
7. **`playOnUncached` never calls `play()`; a fifth `positionMs()` reader.** Agreed on both (the seek back stores
   nothing; `prepare()` keeps `playWhenReady`). But the replacement itself doesn't work: H1.
8. **`evict` takes the limit and the floor.** Agreed.
9. **A stricter settle** (HLS needs `#EXT-X-ENDLIST`, no separate audio renditions, key files cached). Agreed: each
   only makes settle say "not complete" where the span check couldn't vouch for the copy. A partial then stays a
   partial and the job completes keep-set items as before.
10. **Test names.** Fine.

## The two risks the implementer flagged

- **Cleartext: yes, it is blocked - everywhere, not only on the emulator.** H2.
- **The synthetic 416 is retried.** Confirmed from `DefaultLoadErrorHandlingPolicy` in 1.7.1: only
  `ParserException`, `FileNotFoundException`, `CleartextNotPermittedException`, `UnexpectedLoaderException` and a
  position-out-of-range cause are final; anything else is retried with delays of 0, 1 and 2 s. L1. More important is
  what happens after the retries (H1).

## High

### H1. `playOnUncached` keeps the cache-backed source: a changed source ends in a skipped episode and an item error

- **Where.** `PlaybackService.playOnUncached` (`PlaybackService.kt:1229-1236`) replaces the item with
  `renderer.replaceItem(index, mediaItem(a, item))` (`LocalRenderer.kt:138`: `exo.replaceMediaItem`). `mediaItem`
  (`:766-773`) builds the same uri and the same `customCacheKey`; only the tag changes (`ItemTag.uncached = true`).
- **Why it doesn't switch.** Media3 1.7.1's `ExoPlayerImpl.replaceMediaItems` first asks
  `canUpdateMediaSourcesWithMediaItems`; when every existing source says `canUpdateMediaItem`, it only updates the
  sources' `MediaItem` and keeps them (`updateMediaSourcesWithMediaItems`). `ProgressiveMediaSource.canUpdateMediaItem`
  compares the uri, `imageDurationMs` and `customCacheKey`; `HlsMediaSource`'s the uri, stream keys, DRM and live
  configuration; `MaskingMediaSource` delegates. The tag is never compared. That is exactly what design 27's art swap
  relies on ("the same source so playback goes on"), and it is why `refreshArt` works. So `ItemMediaSourceFactory` is
  never asked again: the item keeps `playbackFactory`'s `CacheDataSource`, its `ThroughSink` and the `LengthGuard`.
- **Scenario** (§2's last row; §8 check 10).
  1. An Acast-style podcast that stitches ads per request. The kid streams half an episode (written through), pauses
     for 20 minutes; the host drops the idle connection.
  2. Play: the loader reopens at its buffered end X > 0; the response's total differs from the stored length; the
     guard throws its 416. Retries for ~3 s (L1), then the error surfaces when the buffer runs dry.
  3. `onError` takes the 416 branch: the row goes, `a.uncached += item.key`, `replaceItem` (a no-op for the source),
     `seekTo`, `prepare()`.
  4. The new period comes from the same source: cache up to X, upstream at X, the guard's 416 again.
  5. Now `item.key in a.uncached`, so the ordinary path runs: `ItemErrors.record(..., "http_416")`, "Kan ikke spilles",
     and the queue moves on to the next episode.

  On a host that does this, every resume of a partially streamed episode after a pause long enough to drop the
  connection skips the episode and records an item error the parent sees. Before 3b the same resume played on (as
  patchwork). It heals only at the next queue load, where `staleThrough` (bytes, no row) plays it uncached. HLS goes
  the same way.
- **Fix (small).** Make the uncached item a different source in Media3's eyes. In `mediaItem`, for `item.key in
  a.uncached`, don't set the cache key (`setCustomCacheKey(null)`, or `"uncached:" + cacheKey`):
  `uncachedFactory` never reads it, and `canUpdateMediaItem` then says no, so `replaceMediaItems` removes and adds,
  and `createMediaSource` builds the uncached factory. The alternative is an explicit `removeMediaItem` +
  `addMediaItem`. Pin it in `GuardsTest` beside the `playOnUncached` pins (the uncached branch's `MediaItem` differs
  in `customCacheKey`), with a comment naming `canUpdateMediaItem`, so the next reader doesn't "simplify" it back. §8
  check 10 then shows it on the emulator - after H2.

### H2 (pre-existing, outside 3b's diff). The music app can't make a cleartext request: http enclosures, `lan` feeds, Sonos control and the three fakes

- **Where.** `music/app/src/main/AndroidManifest.xml` has neither `android:usesCleartextTraffic` nor a
  `networkSecurityConfig`, and `targetSdk = 36` (`app/build.gradle.kts:35`). Both merged manifests (debug and
  release, `build/intermediates/merged_manifest*`) have neither either. For an app targeting API 28+ Android's
  default network security config refuses cleartext to every host. OkHttp 4.12 asks it on every plain-http
  connection (`AndroidPlatform.isCleartextTrafficPermitted` -> `NetworkSecurityPolicy.isCleartextTrafficPermitted`,
  checked in the jar) and fails with `UnknownServiceException: CLEARTEXT communication to <host> not permitted by
  network security policy`. Media3's `OkHttpDataSource` and our `Soap` both go through OkHttp.
- **What it stops.**
  - design 30 §8 check 10 (`fake-audio.py` at `http://10.0.2.2:8731`), and the "Offline at once" and limit checks if
    they use the fake;
  - §6i's fake Storytel (`http://10.0.2.2:8730`) and §6h's fake speakers;
  - **Sonos on a real phone:** `Soap.kt:103` builds `http://<ip>:1400/...` - every SOAP call of phase 1b is refused;
  - RSS: an enclosure URL that is `http://` (still common; the refusal comes before any redirect to https), and every
    `lan` feed on the home LAN served over http. They fail as `network` item errors.
- **Why nobody saw it.** The music app never talks to the server itself (the bridge does, and the launcher has
  `usesCleartextTraffic="true"`), NRK is https, and PLAN.md lists the emulator checks of 1b, 2 and design 30 as owed.
- **Fix.** `res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true">` and the system
  trust anchors only, referenced from the manifest. A network security config can't say "private addresses only",
  and it doesn't need to: where audio may come from is already the app's own rule (the public-only client's DNS and
  connected-address checks, Sonos's IP-literal URLs). Pin it in `SourceScanTest` (cleartext on, no user CAs), then run
  §6h, §6i and §8 check 10. This belongs before any `music-v*` release with Sonos; whether it goes into 3b's fix round
  or a separate commit is the orchestrator's call.

## Medium

### M1. "Never evict the newest N, the bookmark or the next" is untested for played items (mutation M1 survives)

- **Where.** The only thing between a played keep-set row and eviction is the filter at `DownloadPlan.kt:78`
  (`entryRows.filter { it.itemKey !in keep }`). In production nearly every keep-set row has a `playedAt`: `markPlayed`
  stamps a downloaded copy the moment the kid plays it. But every keep-set row in the tests is a fixture `done(...)`
  without `playedAt`: `the keep set and the held items are never evicted, even over the limit` (`OfflineTest:368`)
  and `a played item outside the keep set is an extra, an unplayed one is pruned` (`:403`) included. The first test's
  name promises what it doesn't exercise.
- **Scenario.** A later refactor computes `extras` over all rows (M1: played rows first, keep-set check after). Every
  test stays green; on the phone, with the cache over the limit, the kid's current bookmark, its next and the newest
  episodes - all played - are evicted least recently played first. That is the one rule the user said "never" about.
- **Fix.** In the keep-set test, give the newest N, the bookmarked item and its next a `playedAt` (older than the
  extras', so they would go first), and assert: none of them is in `p.extras` or `p.prune`, and `extrasToEvict` over
  the limit with them in the cache evicts only the real extras. M1 then goes red.

## Low

### L1. The synthetic 416 is retried for ~3 s before the player sees it

- **Where.** `MediaCache.kt:352` throws a plain `InvalidResponseCodeException(416)`; the player uses Media3's default
  `LoadErrorHandlingPolicy`.
- **Scenario.** At every changed-source resume the loader retries three times (0, 1, 2 s), each a new request to the
  host whose response the guard closes; the error then waits for the buffer to drain. It costs a stall and three
  requests, nothing else (the job takes its path at once).
- **Fix.** Throw a subclass (`SourceChangedException : InvalidResponseCodeException(416, ...)`; `errorCode` still
  gives `http_416`) and give the player's `ExoPlayer.Builder` a `LoadErrorHandlingPolicy` that returns
  `C.TIME_UNSET` from `getRetryDelayMsFor` when the cause chain holds it, else the default. Don't fake a
  position-out-of-range cause: other Media3 code reads that one.

### L2. The sync evicts before it sweeps: orphans count against the limit and the floor

- **Where.** `Sync.kt:193-195`: `remove`, `evict`, `sweepOrphans`, in that order (as §5.2 lists them).
  `MediaCache.totalBytes`/`freeBytes` in `evict` still include the orphans the sweep frees a moment later.
- **Scenario.** Limit 2 GB; the cache is 2.1 GB, of which 300 MB are an HLS item's orphaned segments (a row dropped by
  the 416 path or a stale queue load). `evict` removes the least recently played extras until the cache is under 2 GB,
  then the sweep frees 300 MB more: the cache ends at ~1.7 GB with extras deleted for room that was coming anyway.
- **Fix.** Sweep before evicting (`sweepOrphans` needs no plan). Change §5.2's order with it.

### L3. `catchUp` enqueues the download job after every sync while any partial extra exists

- **Where.** `CacheThrough.kt:61-69`: `incomplete` counts every played, unheld, not-done row, extras included, and any
  count enqueues the job (`Downloads.enqueue`, `REPLACE` when none runs).
- **Scenario.** Partial extras are normal (deviation 1: an episode the kid started for a second is one) and they stay
  until evicted. So every sync - the nudge, every process start, the 6 h backstop - starts a job that plans, evicts,
  finds nothing wanted and writes the report. The `REPLACE` also drops a waiting job's retry delay, so it runs at once
  and re-enqueues itself with the delay. Correct, but a job run per sync for nothing, forever.
- **Fix.** Enqueue only for an incomplete row that the plan wants - or drop `catchUp`'s enqueue: the sync builds its
  plan right after `catchUp` and already enqueues the job when `wanted` isn't empty, which is exactly the incomplete
  keep-set items. Say so in §1.5 ("enqueues the job once" becomes the sync's own enqueue).

### L4. A keep-set item let go half streamed isn't completed until the next sync

- **Where.** `updateHeld` (`PlaybackService.kt:1402-1420`) settles an item that leaves the held set but never enqueues
  the job; while it was held the busy rule kept it out of every plan.
- **Scenario.** On Wi-Fi in the evening the kid streams half of a not-downloaded keep-set episode, pauses, and the
  service ends (or the kid moves to another entry). No sync runs until the next nudge, process start or the 6 h
  backstop. Next morning on the bus, with only the downloads switch on, the episode says "Ikke lastet ned". §2's "Not
  twice" row ("Wi-Fi on: the job completes it") holds only if a sync came between.
- **Fix.** When a left item's settle returns null and its row exists, call `Downloads.enqueue(context,
  store.downloadMobileData)`; the job's own plan decides whether the item is wanted (with L3's change, that is the
  only job a stream starts).

### L5. Short items: ExoPlayer can have more periods open than the held set's two

- **Where.** `heldItems(..., current, next)` (`Offline.kt`; `updateHeld`). Media3's loading period moves on whenever
  the previous one is fully loaded and less than the buffer's 50 s is buffered, so with items shorter than that (NRK
  news clips, trailers, short RSS items) item i+2 and beyond are open, each with a `ThroughSink`.
- **Scenario.** A sync runs while i+2 is being written: its unplayed row is outside the keep set and not held, so it
  is pruned; its sink has `rowMade` and never makes a new row. The item then plays, `markPlayed` finds no row, and its
  bytes are swept as orphans. Nothing is corrupted (a pruned copy was never "done"), but a played short item isn't
  kept, and in the same window an extra evicted under an open sink loses its row.
- **Fix.** Hold every item whose start is within the load control's buffer (50 s) of the current position, or
  simply the current item and every following item up to a cumulative 60 s. Or accept it and say so in §1.6.

### L6. The ends of a queue's claims are unpinned (M21, M22 survive), and an exception skips them

- **Where.** A claim ends only at `updateHeld(loaded = true)`: after `renderer.load` (pinned), on `start()`'s give-up
  branch (`PlaybackService.kt:702`) and in `onPlaybackResumption`'s post (`:512`) - the last two unpinned. And
  nothing ends it when something throws between `PlayerHub.claim` (`:552`) and `start` (a Room failure in
  `queueRows`).
- **Scenario.** A later edit drops the give-up line (M21; every test stays green). The kid taps a cover on mobile data
  whose start item isn't playable: `start()` returns, the claim stays until the next queue load. The claimed items are
  never pruned, never evicted, and - the costly part - a claimed progressive keep-set item that isn't downloaded is
  "busy", so the job never fetches it, possibly for days if the kid doesn't play anything else.
- **Fix.** Pin both calls in `GuardsTest`'s held-set test (as the `renderer.load` one is), and end the claims in a
  `catch` around the code between `claim` and `start` (`updateHeld(loaded = true)`, then rethrow).

## Nits

- **N1.** `evict`'s `extrasToEvict(extras, PlayerHub.heldLocal, ...)` (`CacheThrough.kt:148`) is unpinned (M11
  survives). Without it nothing is lost - `remove` re-checks the held set - but a held extra counted as freed makes
  `evict` stop early, over the limit until the next round. One `GuardsTest` line.
- **N2.** `docs/testing/emulator.md` §6f: "Offline at once: play episode 6" with `fake-audio.py`'s default three
  episodes at depth 5 never makes an extra (all three are kept); say `--episodes ep1,...,ep7` or depth 1. And every
  check that uses the fake needs H2 first.
- **N3.** The sink writes down to 1 GiB free, while the job keeps 1.5 GB free while a Storytel book is wanted and the
  login works: between the two floors a stream is written and then evicted at the next job round. The spec's choice;
  `throughWrites` could take `extrasFloor` if the churn matters.
- **N4.** `sweepOrphans`' KDoc and §1.8 say a key seen before the rows has its row by then. Not quite: `SimpleCache`
  adds the key at `startReadWrite*` (the hole lock), before the upstream open and the sink's `firstWrite`. It is
  harmless - such a key has no committed span, and `removeResource` of a locked, empty content removes nothing - but
  the comment should say that instead.

## Notes (no action)

- **Room 3 -> 4.** `4.json` differs from `3.json` only in `downloads.playedAt INTEGER` (nullable) and the identity
  hash; `AutoMigration(3, 4)` adds it. Existing rows get null, so the first sync prunes outside the keep set as
  before. No migration test runs it (as for 2 -> 3); the schema-text pin is what the module has.
- **"Ingen".** The sink declines at every open at depth 0, so no audio is written. `CacheDataSource` still takes the
  hole lock and writes the content metadata (length, redirect) while its tee is open, but
  `CachedContentIndex.maybeRemove` drops the empty content when the lock is released, so nothing stays. Rows: none
  are made (the sink makes them only when it writes), and a held one from before the switch goes at the first sync
  after the player lets go (§1.4).
- **The two switches.** The `StreamGate` reads the streaming switch at every open of the player's upstream (the
  uncached factory too); `ThroughSink` writes whatever the gate let through; `catchUp` and the job use the downloads
  switch. Nothing in 3b fetches on mobile data without the right switch.
- **The limit.** Decimal GB (`storageLimitBytes`, pinned by M15), 0 = none from the managed config, the whole cache
  counted (Storytel books and partials included), extras least recently played first. `extra_bytes` is the plan's
  extras after this run's eviction, from the report's own plan, so the job's report sends it too; the server's
  `music_state.json` contract is 3a's and unchanged.
- **The preloaded next item behind the gate.** On mobile data without the streaming switch, the gate refuses the
  next item's preload while a copy plays. As far as `ExoPlayerImplInternal`'s bytecode shows, 1.7.1 only stops
  loading a non-playing period with an error (`isLoadingPossible`) and throws a prepare error for the playing period,
  so `onItemChanged` (the copy marked done, the bookmark moved) should run before `onError(gated)` skips on. I
  couldn't confirm that without a device. Worth watching in §8 check 3: the copy ends normally, gets its ✓, and the queue
  moves on with "Ikke lastet ned".
- **Threads.** `insertThrough` runs on ExoPlayer's loader threads (playlists, segments, progressive loads), never on
  main; it can wait behind a long sync transaction, which the buffer covers. Settle and the removals run on IO, never
  on `writer`; `catchUp` runs before `runLock`. No leaks found: the sink factory holds the application context, and
  the settle jobs die with the service's scope.
