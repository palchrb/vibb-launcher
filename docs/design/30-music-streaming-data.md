# 30 - Vibb music: streaming and downloads on mobile data, the stream saved as it plays, a storage limit (buildable spec)

Architect, 2026-10-13. Branch `design-30` from `main` (`3292cd32`). Revised the same day after the design QA
(`docs/design/qa-30-design.md`) and two user corrections. What changed is listed under "Design QA" at the end.

Sources:
- design 21 §4.5-§4.6 (keep/prune, "Streams aren't cached", the network gate), deviation 5 (depth 0 keeps nothing),
  21b §6, 26a (Sonos), 26b §3.3-§4.5 (Storytel: download-only, the 1.5 GB floor), design 13 (the gate), design 27 (the
  art want set), design 29 (branch `design-29-impl`: Room 3, `Sync.runLock`, `Downloads.kt`);
- the code on `main`:
  - server: `migrations/0049_music.sql` (`device_policy.music_mobile_data`), `models.rs` `DevicePolicy`,
    `music.rs` `MusicPolicy` / `policy_music` / `MusicState` / `sanitize_music_state` / `megabytes`,
    `handlers/music.rs` `MusicCard` / `device_card` / `save_device_settings`, `templates/device_detail.html`
    (`#music-settings`), `templates/music_entry.html`, `static/scroll-restore.js`, `src/tests/device_api.rs`
    `policy_json_keys_snapshot`, `src/tests/music.rs`, `src/tests/docker.rs` `old_database`,
    `testdata/music_state.json`;
  - launcher: `music/MusicPlan.kt` (`MusicRestrictions`, `MusicPolicy`, `musicPolicy`, `restrictionsFor`,
    `musicState`, `artToFetch`), `music/MusicSync.kt` (`mobileData()`, `artFetchable`), `music/MusicFileDownloads.kt`,
    `PolicyResponseCompatTest`, `MusicPlanTest`, `test/resources/bridge_contract.json`;
  - music: `bridge/BridgeConfig.kt`, `data/Store.kt` (`mobileData`), `logic/NetGate.kt`, `logic/Offline.kt`,
    `logic/CacheKeys.kt`, `logic/Report.kt`, `logic/EpisodeArt.kt`, `media/MediaCache.kt`,
    `media/ItemMediaSourceFactory.kt`, `sync/DownloadPlan.kt`, `sync/DownloadRun.kt`, `sync/Downloads.kt`,
    `sync/Sync.kt`, `sync/ArtPlan.kt`, `data/MusicDb.kt`, `data/Snapshot.kt`, `playback/PlaybackService.kt`
    (`mediaItem`, `activeFor`, `playable`, `startedPlaying`, `onError`), `playback/LocalRenderer.kt`,
    `playback/PlayerHub.kt`, `debugfeed/DebugFeedReceiver.kt`; Media3 1.7.1's `CacheDataSource`, `CacheDataSink`,
    `CacheWriter` (QA checked them with `javap`).

**The user's decisions (final, 2026-10-13):**
1. Mobile data splits in two per-phone switches on the device page's Music card: streaming (an item that isn't
   downloaded, off Wi-Fi) and downloads (off Wi-Fi, Storytel books included). On Wi-Fi both are always allowed; never
   while roaming; what's downloaded plays anywhere. The existing value is migrated.
2. A streamed item's bytes are written to the music app's cache as they play: what was played is offline at once and
   isn't downloaded twice; the download job completes the rest. The keep set (the newest N, the bookmarked item and
   the next) is always kept, as today. A played item outside it is a **played extra**: kept opportunistically,
   evicted least recently played first, never the item playing.
3. "Ingen" (depth 0) still keeps nothing, the item in progress included (design 21 deviation 5).
4. **A per-phone "Music storage limit"** set by the parent on the Music card, carried in the policy and the managed
   config. Over it, extras are evicted. The keep set and the items the player holds are never evicted, even when they
   alone exceed it; the card then says so. A fixed free-space floor stays as a separate safety rule.

## 0. Today, and the change

**Today** one switch, `device_policy.music_mobile_data` ("Music may use mobile data: downloads and streaming also off
Wi-Fi"), reaches the phone as the policy's `music.mobile_data`, the launcher's managed-config key `mobile_data`, and
the music app's `Store.mobileData`. It gates, with one formula (design 13's, ported: internet, a physical transport,
not roaming, NOT_METERED unless the switch):
- music: a stream (`streamAllowed`, `playability`), the download job (`Downloads.enqueue`: `UNMETERED` or
  `NOT_ROAMING`; `downloadWaiting` per item), the report's `waiting`;
- launcher: the own files it fetches for the music app (`MusicFileDownloads.gate`) and the `later` episode pictures
  (`artToFetch`).

Playback reads a copy first and streams the rest **without writing** (`playbackFactory`:
`setCacheWriteDataSinkFactory(null)`; 21 §4.5 "Streams aren't cached", as vibb). Only the download job writes the one
`SimpleCache` (`NoOpCacheEvictor`, keyed per item). Every sync prunes every copy outside its entry's keep set.

**The change:**
- two switches;
- above depth 0 a stream writes the cache;
- "on the phone" still means a finished copy (a `done` row). A fully streamed item becomes one without a download;
- played copies outside the keep set stay as extras, within the parent's storage limit and a free-space floor.

**Storage, as the code has it** (the brief's "1 GB free" is the server's upload rule, 21 §1):
- the phone's download job needs WorkManager's storage-not-low;
- the report says `storage` below 500 MiB (`Sync.LOW_STORAGE_BYTES`);
- a Storytel book needs 1.5 GB free (`STORYTEL_FREE_FLOOR`).

This design adds the parent's limit (§1.8) and one floor, `EXTRAS_FREE_FLOOR` (1 GiB, §1.4, §1.8).

## 1. Decisions

### 1.1 What each switch gates

| | Wi-Fi (NOT_METERED) | mobile data, not roaming | roaming |
|---|---|---|---|
| a copy plays | yes | yes | yes |
| a stream (music app: `streamAllowed`, `playability`, the upstream gate of §1.6) | yes | **streaming** switch | no |
| the download job, Storytel books included (`downloadWaiting`, `Downloads.enqueue`) | yes | **downloads** switch | no |
| launcher: own files for the music app (`MusicFileDownloads`) | yes | **downloads** switch | no |
| launcher: `later` episode pictures (`artToFetch`) | yes | **downloads** switch | no |
| launcher: `now` pictures (the now-playing window, ~60 KB each) | yes | yes | yes (unchanged) |
| writing a stream's bytes to the cache (§1.4) | follows the stream: no extra traffic | | |

A stream on mobile data under the streaming switch writes its bytes too: they arrived anyway. It never starts a
download on mobile data without the downloads switch. The rest of a keep-set item waits for the download gate.

The downloads switch turned off while the phone is on mobile data stops a running download at the next sync
(the launcher's nudge; `stopRunningDownload`, qa-30-s3a-code L1): the job is replaced and waits for Wi-Fi, where the
partial item resumes with Range. On Wi-Fi a running job is never interrupted.

### 1.2 The migration: both switches take the old value

Migration `0073_music_data_and_storage.sql` (**renumbered at the merge** if another branch takes 0073 first; main is
at 0072, design 29 adds none). It does three things:
- adds `music_stream_mobile_data` and `music_download_mobile_data` (`INTEGER NOT NULL DEFAULT 0`), copies
  `music_mobile_data` into both, and drops `music_mobile_data`. There are precedents (0012, 0017), and no trigger or
  index names the column;
- adds `music_storage_limit_gb INTEGER DEFAULT 2` (§1.8; NULL = no limit), so every existing phone gets 2 GB.

Why both switches take the old value: the old switch allowed exactly both, so every phone behaves the same until the
parent changes something.
- Off stays off: nothing new is spent.
- On stays on for both. Turning streaming off would silently break a phone that streamed yesterday. Turning downloads
  off would leave Storytel books undelivered, though the parent touched nothing.

The limit changes nothing at once either: no phone has extras before step 3b, and the limit never touches the keep set.

### 1.3 Compatibility: the old key stays, as "both"; the new keys fall back

**Policy JSON** (`PolicyResponse.music`):
- `mobile_data` stays, and means `stream && download`. An older launcher's `musicPolicy` requires it (missing = the
  whole object refused, qa-21-step3-code M4), so it can never go.
- It gains `stream_mobile_data`, `download_mobile_data` and `storage_limit_gb` (§1.8).

**Why AND for the old key:** an older launcher or music app has one switch for both. With AND it never spends data the
parent turned off. With OR, "streaming on, downloads off" would download Storytel books over mobile data. AND's cost is
that an older phone doesn't stream off Wi-Fi while only streaming is on. The PWA shows that (below).

**Managed config** (launcher -> music app):
- the launcher writes `mobile_data` (= both, for an older music app) plus `stream_mobile_data`,
  `download_mobile_data` (Boolean) and `storage_limit_gb` (Int: 0 = no limit). All are storable types
  (qa-21-step3-code H1);
- `bridge_contract.json` gains the three keys, appended to `restrictions`. Both copies stay byte-identical, changed in
  one commit with the music app's `RestrictionKeys` constants (`BridgeTest` checks the constants against the file).

**Reading the switches,** one pure rule each side:
- a new key that is a Boolean is used;
- a new key that is **absent** falls back to `mobile_data` (an older server or launcher);
- in the launcher, a key that is present but **not a Boolean** refuses the music object like any malformed field
  (`musicPolicy` returns null: keep what is there). The music app's `bridgeConfig` reads restrictions as today:
  anything but `true` is off.

**Reading the limit:**
- launcher: `storage_limit_gb` absent -> 2 (an older server); `null` -> no limit; an integer 1..1000 -> it; anything
  else refuses the object;
- music app: an Int 1..1000 -> that many GB; 0 -> no limit; absent or anything else -> 2 GB (an older launcher).

| server | launcher | music app | result |
|---|---|---|---|
| new | new | new | two switches, the parent's limit |
| new | new | old | the old app reads `mobile_data` = both; it keeps no extras, so no limit is needed |
| new | old | any | the old launcher reads `mobile_data` = both and writes only it; a new app falls back to it, limit 2 GB |
| old | new | new | no new keys: both = `mobile_data`, limit 2 GB |
| old | old | new | `mobile_data` only: both = it, limit 2 GB |

**The PWA says when a phone is older.** From step 3a the music app's report carries `extra_bytes` and `audio_bytes`
(§1.10). That ships in the same commit that makes it read the two switches, and the launcher passes the fields on from
the same commit (M4). An older music app never sends them, and an older launcher drops them: its `musicState` passes
known keys only.

So "an app report without `extra_bytes`" means exactly the phones that run on `mobile_data` = both. An app report is
one with `cache_bytes`, which the app has always sent. Before the app's first report the launcher sends only
`package`, `version_code` and `bridge`, and that is not an app report.

When the two switches differ and the phone's app report lacks `extra_bytes`, the card says: "This phone's launcher or
music app is older: it uses mobile data for music only when both switches are on. Update both." With no app report it
says nothing.

### 1.4 Cache-through: who writes, decided at every write

The phone's playback factory gets a **guarded sink** (`MediaCache.ThroughSink`). It wraps Media3's `CacheDataSink` and
decides at each `open` (one per hole of a progressive item, one per HLS segment or playlist) from live state, with the
pure `throughWrites`:
- the item is NRK or RSS. It is not an own file (a local file), and not a Storytel book: those are copy-only and never
  streamed on the phone (26b §4.4). This is the static part, in `ItemTag.through`;
- the entry's depth in the **current** library (`Store.library()`) is not 0, and the entry is still there;
- at least `EXTRAS_FREE_FLOOR` (1 GiB) is free where the cache lives, read at this `open`.

If it declines, the open writes nothing (the same no-op as `CacheDataSink`'s own unknown-length path). Network rules
need no check here: the sink only sees bytes the stream already fetched, and the stream passed the upstream gate
(§1.6).

**The row comes first** (M1). Before its first write for an item, the sink does an `INSERT OR IGNORE` of a not-done
`downloads` row: `url` = the item's url, `playedAt` null, `bytes` 0. It is a blocking DAO call on ExoPlayer's loader
thread (never main), once per item per sink. So every byte the sink writes has a row, and prune, eviction and
`removedEntries` see it. An unplayed row outside the keep set (a preloaded next item, a queue loaded paused) is pruned
by today's rule.

**A full disk never fails playback.** The playback factory sets `FLAG_IGNORE_CACHE_ON_ERROR`: a sink error (a disk that
filled faster than the floor allows for) makes the read go on uncached, never a playback error.

**A host without `Content-Length`** writes nothing: `CacheDataSink` skips an unknown-length spec (the player's flags
include `DONT_CACHE_IF_LENGTH_UNKNOWN`). Such an item never settles. It streams as today.

**Depth 0: never written** (not "write, then prune at once"). "Ingen" promises nothing stays on the phone.
Write-then-prune would leave the bytes until the next prune (a sync, up to 6 h). The held items can't be pruned under
the player either (§1.6), so "at once" isn't possible. Declining at the sink is today's read-only path: no new code and
no flash writes. The cost is the one the parent chose: a replay streams again.

An entry set to "Ingen" while it plays stops writing at the next `open`. A progressive item's open hole runs to the
item's end, so that item may still be written to its end. Its row (inserted first) is pruned at the first sync after
the player lets go of it (§1.9).

**Sonos:** a room fetches the URL itself (26a). Nothing passes through the phone's cache, nothing is written or marked
played. **Storytel:** downloads only, under the downloads switch; a book is never streamed on the phone and never an
extra.

### 1.5 "On the phone" is still a finished copy: a fully streamed item is settled into one

`Snapshot.onPhone`, `visible()`, `playability(hasCopy)`, `offlineSummary`, the report's `have` and design 29's "can
play" all read `downloads.done`. That stays the only meaning of "on the phone". Two things are new.

**`downloads.playedAt`** (Room, nullable): when the phone's own player last started the item. `markPlayed`
(`startedPlaying`, local renderer only) **only sets** `playedAt` on an existing row. The sink created the row for a
stream, the job for a copy. A Storytel book (`ItemTag.copyOnly`) and an own file are skipped. The current depth isn't
read: an item played at depth 0 has its row pruned anyway (§1.8). Rows the job writes keep a stored `playedAt` through
a pure merge, `keepPlayedAt(stored, incoming)`, inside a transactional `upsertDownload`. Today's `REPLACE` would wipe
it.

**Settle** (`sync/CacheThrough.kt`) is a **non-blocking span check**. It never runs the downloader and never takes a
cache lock (H1):
- **progressive:** the stored content length (`ContentMetadata.getContentLength(cache.getContentMetadata(key))`) is
  known and `cache.isCached(key, 0, length)`;
- **HLS:**
  1. The cached playlist at the item's url is parsed through a copy-only data source (`MediaCache.copyOnlyFactory`:
     no upstream, no sink, `createDataSource()` without `FLAG_BLOCK_ON_CACHE`).
  2. A multivariant playlist gives the variant (`pickVariant`, the download's rule), and its media playlist is parsed
     the same way.
  3. Every segment, and an initialization segment if any, must be cached: `isCached(hlsResourceKey(...), offset,
     length)`, the length from the segment's byte range or the key's stored length. An unknown length means not
     complete.
- Complete: the row becomes `done` with its bytes (`MediaCache.cachedBytes`) and the HLS variant. Not complete: it
  stays as it is.

Settle runs:
- when an item **leaves the held set** (§1.6), launched on `Dispatchers.IO` in the service's scope. Never on the
  service's `writer`, where positions and bookmarks are written;
- at every sync, **before** `Sync.runLock` is taken (design 29's mutex; `CacheThrough` has its own small mutex), as
  `catchUp`: every row that is not done, has `playedAt`, and is not held. It enqueues the download job **once** if any
  row is still incomplete. A partial extra is re-checked at every sync, which is cheap as a span check;
- never for a held item. The service's release can't settle (its scope is cancelled), so the next sync's `catchUp`
  covers it.

An item streamed to its end is therefore on the phone at once. Offline it plays (`LOCAL`), a withdrawn one stays
visible, and the download plan doesn't want it.

### 1.6 The player's held set, the download job, and changed sources

**The held set** (`PlayerHub.heldLocal: Set<ItemRef>`, immutable and `@Volatile`, written on main) is every item the
phone's ExoPlayer may have a period open for:
- the current item and the next one in its timeline (shuffle included);
- empty on a Sonos room or with nothing loaded.

ExoPlayer opens the next item's period in the current item's last ~50 s, and with a sink a progressive period keeps
`SimpleCache`'s hole lock `[position, end)` while its source is open, paused included (QA H1). The service **claims**
the planned start item and its next (added to the set) **before** it reads rows for a new queue (`activeFor`). After
`renderer.load`, and at every item or timeline change, it sets the set from the timeline.

Rules for the held set:
- **never pruned, evicted, swept or settled.** Every `MediaCache.remove` in prune, eviction, the orphan sweep and the
  stale-partial delete re-reads `PlayerHub.heldLocal` right before it removes (L4). A plan computed earlier is not
  enough;
- **busy = the held items that are progressive and not done: never fetched.** The downloader
  (`createDataSourceForDownloading`, `FLAG_BLOCK_ON_CACHE`) would block on the player's lock until WorkManager stops
  the job. The plan gets the set from `DownloadWorker` and `Sync`, both reading `PlayerHub`. HLS isn't held back: a
  segment's lock lasts one segment load;
- **the cost, stated:** a progressive keep-set item streamed, paused and left loaded isn't completed in the background
  until the player lets go of it. It still has what the player buffered (~50 s past the pause). The alternative, the
  job and the stream both fetching the same bytes, is what decision 2 removes.

**The job completes a stream's partial.** `ProgressiveDownloader` and `HlsDownloader` go through `CacheWriter`, which
opens the source only for uncached ranges (progressive) and skips cached segments (HLS). It is the mechanism that
resumes a partial download today. The reverse case is unchanged: the job fetches X, then the kid plays X. The player
can't take the job's lock, so it reads that range from the network without writing, as today.

**Same source** (L5) is the pure `sameSource(a, b)` in `CacheKeys.kt`:
- two http(s) URLs: equal when `withoutQuery` is equal (lower-cased scheme, raw authority, raw path; no query or
  fragment). It is the same normalisation the HLS cache keys use, so a re-resolved NRK token or a podcast host's new
  tracking query keeps the partial;
- anything else (a Storytel source id `storytel:book:<cid>`): the whole strings.

It replaces the full-URL comparisons in `DownloadRun.fetchOne` (the "partial from another source" drop, and
`lastBytes`) and is used by `staleThrough`. `mayRetry` keeps comparing full URLs: a new URL retries a failure at once,
as 21b §6 says. A file that changes under the same URL is caught by the length guard (below).

**A stale partial at load** (`staleThrough(done, rowSource, itemUrl, cachedBytes)`, in `activeFor`): an item without a
done copy whose cache holds bytes (`MediaCache.cachedBytes`, a span lookup), either with no row (an orphan, M1) or with
a row whose source isn't `sameSource` as the item's url.
- Its row is deleted, and its bytes too if no open period holds it (the previous held set).
- It plays in this queue with `ItemTag.uncached`: plain http through the gates, no cache read or write. Bytes left
  behind go with the next orphan sweep.

**The length guard** (M5) is a wrapper (`MediaCache.LengthGuard`) around the shared OkHttp data source, so the player
and the job both get it. **Assumption: a source serves the same bytes for every request.** A host that stitches ads
per request breaks that. The guard catches it by length, the cheap signal.
- On a response to a request at position > 0, the response's total length is read: `Content-Range`'s after the `/`, or
  for a `200` the `Content-Length`.
- It is compared with the stored content length of the request's `DataSpec.key` (`CacheDataSource` sets the key).
  Pure: `sourceChanged(storedLength, status, contentRange, contentLength, position)`.
- An unknown on either side (`*`, missing, no stored length) is no change.
- A changed total throws a synthetic `HttpDataSource.InvalidResponseCodeException` with status 416, so `errorCode`
  gives `http_416` with no new mapping:
  - **the job:** `DownloadRun`'s existing `http_416` path drops the partial and fetches once from 0;
  - **the player** (`onError`, an item without a done copy, code `http_416`): its row is deleted (its bytes become
    orphans, swept when not held). The item is replaced in place (`renderer.replaceItem`, a new source with
    `ItemTag.uncached`) and sought to the saved position. It plays on, and no item error is recorded.

  Same-length re-stitching isn't caught. The copy then plays with a jump, and a parse failure still drops it
  (`bad_media`).

**The upstream gate** (L4, makes the streaming switch exact): the playback factory's upstream is wrapped in
`MediaCache.StreamGate`, which at each `open` asks `streamAllowed(netCaps, store.streamMobileData)`. If that says no,
it throws `StreamGateException` (an `IOException`). ExoPlayer opens the next item's upstream before it plays, and a
copy with holes would otherwise stream them. `onError` treats this exception as "Ikke lastet ned - trenger Wi-Fi" and
records no item error. The download factory has no gate: the job has its own (`downloadWaiting`).

### 1.7 HLS: the stream plays the rendition the download keeps

A download keeps one rendition (`pickVariant`: the highest up to `HLS_MAX_BITRATE` 192 kbit/s, else the lowest), and a
copy plays with that stream key. A stream with adaptive selection would write segments of whichever rendition the
bandwidth picked. So the phone's player is pinned, player-wide in `LocalRenderer`:
- `TrackSelectionParameters` with `setMaxAudioBitrate(HLS_MAX_BITRATE)` and `setForceHighestSupportedBitrate(true)`;
- this is the same choice as `pickVariant`: out of constraints Media3 prefers the lowest, as `pickVariant` does. QA
  checked `AudioTrackInfo` in 1.7.1. They differ only on a mix like [256 k, unknown], where Media3 takes the unknown
  one; those bytes stay under the item's prefix.

Nothing is lost: these are audio renditions of at most 24 kB/s, and progressive items already stream at one fixed
bitrate. Playlists go through the cache as today (keys without the query, `CacheKeys.kt`).

### 1.8 Two classes, the parent's limit, and the floor

`downloadPlan` classifies every row of an entry with a parsed listing:
- **keep set** (`keepSet` / `storytelKeepSet`, unchanged): kept, and fetched when missing, as today;
- **played extra**: all of these hold:
  - not in the keep set;
  - `playedAt != null`;
  - the item is still listed;
  - the entry's depth is not 0;
  - the entry is not Storytel. A book is never streamed and never marked played (§1.5).

  An extra is kept and never fetched. A partial one stays partial: it saves those bytes on a replay but doesn't play
  offline. A copy downloaded for the keep set, played, and then left behind by a newer episode is an extra too. The
  rule is "played on the phone", not "came from a stream": the same bytes are worth the same to the kid;
- **everything else** outside the keep set is pruned, as today: never played, no longer listed, or depth 0. The held
  set is skipped, re-checked before each removal.

**The parent's storage limit** (user decision 4) is per phone, a select on the Music card: **1 GB, 2 GB, 5 GB, 10 GB,
No limit**, default **2 GB**. Units are decimal (1 GB = 10^9 bytes), like the card's MB.
- **2 GB as the default:** it is about 35 hours of audio at 128 kbit/s. That holds a typical keep set (a handful of
  series at depth 5, ~50 MB an episode) plus a day or two of played extras. On a 128-256 GB phone it is small, so a
  parent who doesn't look is never surprised.
- **The other choices:** 1 GB for a nearly full phone; 5 and 10 GB for a Storytel series or long trips;
  "No limit" leaves only the floor.

**What the limit counts:** the music app's whole audio cache (`MediaCache.totalBytes`): downloads, extras and partials,
**Storytel books included**. They are audio in the same cache, the biggest items, and what the parent wants to see on
one line. Leaving them out would let "2 GB" mean 6 GB. Own files (the parent's uploads, `noBackupFilesDir/own`) and
pictures are outside the cache and don't count. They show in `cache_bytes` as today.

**What the limit does:**
- over it, extras are evicted;
- it never stops a keep-set download and never evicts the keep set or the held set. The offline depth is the
  parent's control for those. When they alone exceed the limit, the card says so (§1.10).

**The floor** is a fixed safety rule beside the limit. It protects the phone, not the parent's budget. Free space where
the cache lives must stay at least `EXTRAS_FREE_FLOOR` (**1 GiB**). While the plan wants a Storytel book **and the
phone's Storytel login is `ok`**, the floor is `STORYTEL_FREE_FLOOR` (1.5 GB). It is pure, `extrasFloor(wantsStorytel,
loginOk)` (L2): with no login the books can't download, and holding 1.5 GB for them would evict extras for nothing.

**Eviction** is the pure `extrasToEvict(extras, held, totalBytes, limitBytes?, freeBytes, floorBytes)`:
- candidates are the extras not held, least recently played first (`playedAt`, then entry id and item key, a stable
  order);
- while `(limitBytes != null && totalBytes > limitBytes) || freeBytes < floorBytes`, evict the next candidate. Each one
  lowers `totalBytes` and raises `freeBytes` by its bytes;
- it stops when both hold or no candidate is left.

So extras go when space is needed (the floor) **or** when the cache is over the parent's limit. There is no age limit:
the limit and the floor bound the extras, and an age limit would delete what the kid replays while there is room.

Where eviction runs:
- the sync: inside `runLock`, after the listings, with the prune. It never waits on a lock: held items are skipped, and
  removing an unheld resource takes `SimpleCache`'s monitor only briefly;
- the download job: each planning round, before fetching, so a Storytel book gets its floor;
- both re-check the held set before each removal.

Bytes per item come from one pass over the cache's keys (`MediaCache.bytesByItem`, HLS keys grouped by
`hlsKeyPrefix`).

**The orphan sweep** (M1's backstop) runs at every sync: the pure `orphanResources(resourceKeys, rowCacheKeys,
heldCacheKeys)`.
- A resource key is an orphan when it is no row's `cacheKey` and doesn't start with any row's `hlsKeyPrefix`, and isn't
  held.
- The cache's keys are read **before** the rows. The sink inserts its row before it writes, so a key seen first has
  its row by the time the rows are read.
- It removes what a still-open `CacheDataSource` committed after a prune, and a stale partial left by §1.6.

### 1.9 Removals

- An entry the library no longer names: every row goes, extras included (`removedEntries`, as today). The orphan
  sweep takes any bytes without a row.
- An entry set to "Ingen": keep set empty, no extras (§1.8). Every row is pruned at the next sync, except the held
  items, which go at the first sync after the player lets go of them.
- An item the listing no longer names: pruned, never an extra, as today.
- An entry whose listing isn't there or doesn't parse: keeps everything, extras included, and evicts nothing from it
  (QA #3).
- Positions and bookmarks: unchanged; none of this ever deletes them.

### 1.10 The report and the PWA

- `cache_bytes` stays: the audio cache plus own files and pictures. Extras and partials are in it.
- New: `audio_bytes` (the audio cache, `MediaCache.totalBytes`: what the limit counts) and `extra_bytes` (the played
  extras' bytes after this run's eviction). Both are **always sent** (0 when none), from step 3a.
  - `writeReport` computes them itself from `downloadPlan`'s `extras` and `bytesByItem`, so the sync and the job both
    send the true value (L1).
  - Both go into `server/testdata/music_state.json`, the server's `MusicState` (clamped like `cache_bytes`), the
    launcher's `musicState` key list and the music app's `stateReport`, in **one commit, in step 3a** (M4).
- The device card's storage line (`storage_line`, pure, `music.rs`):
  - no `audio_bytes` (an older app): "Stored on the phone: 1234.5 MB", as today;
  - with it: "Stored on the phone: 1234.5 MB. Music downloads: 980.0 MB of the 2 GB limit, 210.0 MB of it played
    beyond the offline depth (kept while there is room)." The last clause only when `extra_bytes > 0`; "No limit"
    reads "980.0 MB (no limit)";
  - when `audio_bytes - extra_bytes` exceeds the limit, a **warning**: "Kept episodes and books alone use 3.2 GB of
    the 2 GB limit - lower an entry's offline depth or raise the limit." The limit is the server's current one, not
    what the phone last saw. A just-changed limit can show a stale verdict until the phone's next report, which is
    acceptable.
- Per-entry `have`/`want` and `downloads_waiting` count the keep set only, as today.

### 1.11 Episode pictures of extras (L3)

Design 27's rule is "a kept episode's picture stays as long as its audio". `artWant` (`sync/ArtPlan.kt`) puts
`plan.extras`' `artHash` in `keep` (fetched as `later`, under the downloads switch), so the art prune keeps them.

### 1.12 Deviations from vibb

- **Streams are cached** (vibb: "Streams aren't cached", 21 §4.5). The Pi has a disk and Wi-Fi; a phone on mobile
  data pays for a second download, so a played item is kept from its stream.
- **`prune_cache` keeps played extras** outside the keep set within the parent's limit and the floor (vibb deletes
  everything outside it). Depth 0 still keeps nothing, as vibb's cache 0.
- **A storage limit** (vibb has none).
- `logic/Offline.kt`'s header names which functions are ours: `throughWrites`, `staleThrough`, `extrasToEvict`,
  `extrasFloor`, `storageLimitBytes`, `orphanResources`, `keepPlayedAt`. `NOTICE.md` needs no change.

## 2. Behaviour (depth 5, limit 2 GB unless noted; S = streaming switch, D = downloads switch)

| Situation | Today | Design 30 |
|---|---|---|
| Wi-Fi, S off, D off | streams and downloads | the same; what streams is written |
| Mobile data, S on, D off; a not-downloaded episode | plays only with the one switch | plays and is written as it plays; the rest waits for Wi-Fi |
| Mobile data, S off, D on | (one switch) | a not-downloaded episode: "Ikke lastet ned - trenger Wi-Fi"; downloads and Storytel books run |
| Mobile data, S off; a copy with holes | streams its holes | holes refused by the gate: "Ikke lastet ned - trenger Wi-Fi" |
| Roaming, both on | nothing over the network | the same; copies play |
| Episode 6 (outside the newest 5) streamed to its end on Wi-Fi | gone when it ends | on the phone: plays in airplane mode; an extra |
| Keep-set episode half streamed, then another entry | the job downloads it all | the job fetches the second half only |
| Depth 0, an episode streamed | nothing kept | nothing written, nothing kept |
| The cache passes 2 GB | - | the least recently played extras go at the next sync or download round |
| Kept items alone are 3.2 GB, limit 2 GB | - | all extras go; the keep set stays; the card warns |
| Free space under 1 GiB | - | extras go first; the keep set stays; the stream stops writing |
| An entry set to "Ingen" | its copies go at the next sync | its copies and extras go; the playing item and the next after the player lets go |
| A host serves a different length on a resume | patchwork is impossible (no cache-through) | the partial is dropped, the item plays on uncached |

## 3. Server (step 1)

- `migrations/0073_music_data_and_storage.sql` (§1.2; its header says the number may move at the merge).
- `models.rs` `DevicePolicy`: `music_stream_mobile_data`, `music_download_mobile_data` replace `music_mobile_data`;
  `music_storage_limit_gb: Option<i64>`.
- `music.rs`:
  - `MusicPolicy { library_version, mobile_data, stream_mobile_data, download_mobile_data, volume_cap_pct,
    storage_limit_gb, storytel_generation }`, in JSON order. `policy_music` fills them through pure functions:
    - `mobile_data_wire(stream, download) -> (bool, bool, bool)` (`mobile_data` = both);
    - `storage_limit(stored: Option<i64>) -> Option<i64>`: a choice -> it, NULL -> `None` (no limit), any other stored
      value -> `Some(2)`;
  - `STORAGE_LIMITS: [i64; 4] = [1, 2, 5, 10]`, `DEFAULT_STORAGE_LIMIT_GB = 2`;
  - pure `older_phone_note(stream, download, state: Option<&MusicState>) -> Option<&'static str>`: the switches differ,
    `cache_bytes` is present, `extra_bytes` is absent (§1.3). `MusicState` gets `extra_bytes` only in step 3a (the
    fixture test needs every field at once), so from step 1 to 3a the field is always absent. The note then shows for
    every phone with an app report while the switches differ, which is true: no phone reads two switches before 3a;
  - pure `storage_line(state, limit_gb) -> (String, Option<String>)`: the line and the warning of §1.10, reading
    `audio_bytes`/`extra_bytes` once 3a adds them.
- `handlers/music.rs`:
  - `MusicCard` gains `stream_mobile_data`, `download_mobile_data`, `storage_limit`, `storage_limits` (as
    `volume_caps`), `data_note`, and the storage line and warning;
  - `save_device_settings`: the switches come from the pure `mobile_switches(form)`:
    - with the hidden `v=2` (today's page): `stream_mobile_data`, `download_mobile_data`, a missing checkbox off;
    - without it (a page opened before the update, L6): its `mobile_data` sets both, which is what that page meant;
  - `storage_limit` is `none` or one of `STORAGE_LIMITS`, else 400 like the volume cap. Missing (an old page) = the
    stored value unchanged;
  - one `UPDATE`, nudge, event `music_phone_settings`: "device {id}: streaming on mobile data {s}, downloads on mobile
    data {d}, storage limit {2 GB|no limit}, volume cap ..., Storytel ...". Redirect `#music-settings`, unchanged.
- `templates/device_detail.html`, in `#music-settings` (same `id`, same redirect anchor, `scroll-restore.js`: **a save
  never jumps to the top**), where the one checkbox is. A hidden `<input name="v" value="2">`, then:
  - checkbox "**Streaming may use mobile data**: something that isn't downloaded can play off Wi-Fi too. What plays is
    saved on the phone as it plays, so it isn't fetched twice (not for an entry that keeps nothing)."
  - checkbox "**Downloads may use mobile data**: the offline episodes and Storytel books also download off Wi-Fi - a
    Storytel book is often several hundred MB."
  - subtitle: "Never while roaming. On Wi-Fi both always work, and what's downloaded plays anywhere." Then `data_note`
    when set;
  - select "**Music storage limit**" (`storage_limit`, `onchange="this.form.submit()"`): 1 GB / 2 GB / 5 GB / 10 GB /
    No limit, with the subtitle "Downloads, Storytel books and what was played. Over the limit, episodes played beyond
    the offline depth are removed first; the offline episodes always stay."
- `templates/music_entry.html`: "Downloads wait for Wi-Fi unless the phone's 'Downloads may use mobile data' is on
  (its Music card)."
- `src/tests/mod.rs`: `old_database` moves here from `src/tests/docker.rs` (L7), used by both.

## 4. Launcher (step 2)

- `music/MusicPlan.kt`:
  - `MusicRestrictions.STREAM_MOBILE_DATA = "stream_mobile_data"`, `DOWNLOAD_MOBILE_DATA = "download_mobile_data"`,
    `STORAGE_LIMIT_GB = "storage_limit_gb"`;
  - `MusicPolicy(libraryVersion, streamMobileData, downloadMobileData, volumeCapPct, storageLimitGb: Int?,
    storytelGeneration)`, where null = no limit;
  - `musicPolicy`: `mobile_data` still required. Each switch key: Boolean -> used, absent -> `mobile_data`, else null.
    `storage_limit_gb`: absent -> 2, `null` -> no limit, an integer 1..1000 -> it, else null;
  - `restrictionsFor`: `mobile_data` = both, then the two switches and `storage_limit_gb` (`storageLimitRestriction`:
    the GB, 0 = no limit);
  - `artToFetch(..., downloadMobileData)`: the parameter is renamed, the rule is the same.
- `music/MusicSync.kt`: `mobileData()` -> `downloadMobileData()`; `artFetchable` passes the downloads switch.
- `music/MusicFileDownloads.kt`: the gate reads `MusicSync.downloadMobileData()`.
- `test/resources/bridge_contract.json` and `music/app/src/test/resources/bridge_contract.json`: `restrictions` gains
  `"stream_mobile_data": "boolean", "download_mobile_data": "boolean", "storage_limit_gb": "int"`. The same commit
  adds the three constants to the music app's `RestrictionKeys` and to `BridgeTest`'s key list; reading them is step
  3a.
- `PolicyResponseCompatTest`: today's music shape with the new keys.

## 5. Music app (steps 3a and 3b)

### 5.1 Pure, with their tests

- `bridge/BridgeConfig.kt` (3a): `streamMobileData` and `downloadMobileData` replace `mobileData`, and
  `storageLimitGb: Int?` is added (null = no limit). `bridgeConfig` reads each switch when present, else
  `mobile_data` (`mobileSwitch(values, key)`), and the limit by §1.3 (`storageLimit(values)`).
- `logic/NetGate.kt` (3a):
  - `streamAllowed(caps, streamMobileData)`;
  - `downloadAllowed(caps, downloadMobileData)`, the same formula;
  - `downloadWaiting(caps, downloadMobileData, storageLow)` uses `downloadAllowed`;
  - `playability(..., streamMobileData)`;
  - the header names both switches.
- `logic/Report.kt` (3a): `stateReport(..., audioBytes, extraBytes)`, both always `put`.
- `logic/CacheKeys.kt` (3b): `withoutQuery` made internal; `sameSource(a, b)` (§1.6).
- `logic/Offline.kt` (3b):
  - `EXTRAS_FREE_FLOOR = 1L shl 30`;
  - `storageLimitBytes(gb: Int?): Long?` = gb x 10^9;
  - `throughWrites(through: Boolean, depth: Int?, freeBytes: Long): Boolean` (§1.4; null depth = the entry is gone);
  - `staleThrough(done: Boolean, rowSource: String?, itemUrl: String?, cachedBytes: Long): Boolean` (§1.6);
  - `sourceChanged(storedLength: Long?, status: Int, contentRange: String?, contentLength: Long?, position: Long):
    Boolean` (§1.6);
  - `keepPlayedAt(stored: Long?, incoming: Long?): Long?` (the later non-null; §1.5);
  - `data class Extra(entryId, itemKey, cacheKey, bytes, playedAt)` and `extrasToEvict(extras, held: Set<ItemRef>,
    totalBytes, limitBytes: Long?, freeBytes, floorBytes): List<Extra>` (§1.8);
  - `extrasFloor(wantsStorytel: Boolean, loginOk: Boolean): Long`;
  - `orphanResources(resourceKeys: Set<String>, rowCacheKeys: Set<String>, heldCacheKeys: Set<String>): Set<String>`.
- `sync/DownloadPlan.kt` (3b, pure): `downloadPlan(snap, rows, nowMs, held: Set<ItemRef> = emptySet())`.
  - `DownloadPlan` gains `extras: List<DownloadRow>` and `wantsStorytel`.
  - `prune` skips `held`, and the extras of §1.8 are neither pruned nor wanted.
  - `wanted` skips the busy items: held, progressive, not done.

### 5.2 Glue

Step 3a (the switches and the report fields):
- `data/Store.kt`: `streamMobileData`, `downloadMobileData`, `storageLimitGb` (live from the managed config; unmanaged,
  the debug feed's prefs). `debugfeed/DebugFeedReceiver.kt`: `config.json`'s `stream_mobile_data`,
  `download_mobile_data` and `storage_limit_gb`; a bare `mobile_data` sets both switches (old scripts).
- Callers:
  - `PlaybackService.playable`/`playableFrom`/`onError` -> the streaming switch;
  - `Downloads.enqueue`, `DownloadWorker`, `Sync` (`enqueue`, `downloadWaiting`) -> the downloads switch.
- The `audio_bytes`/`extra_bytes` contract commit (§1.10):
  - `server/testdata/music_state.json` gets `"audio_bytes": 100000000, "extra_bytes": 23456789` after
    `cache_bytes`;
  - the server's `MusicState` gets both fields, clamped like `cache_bytes`, and `older_phone_note`/`storage_line` read
    them;
  - the launcher's `musicState` key list gets both;
  - the music app's `stateReport`: `writeReport` sends `MediaCache.totalBytes` and 0 for extras until 3b.

Step 3b (cache-through, extras, the limit):
- `data/MusicDb.kt`:
  - `DownloadRow.playedAt: Long? = null`; Room **4** (design 29 makes 3), `AutoMigration(3, 4)`,
    `schemas/.../4.json`;
  - DAO: `insertThrough(row)` (blocking, `INSERT OR IGNORE`), `markPlayed(entryId, itemKey, at)` (an `UPDATE` only),
    `upsertDownload` as a `@Transaction` through `keepPlayedAt`.
- `media/MediaCache.kt`:
  - `playbackFactory(...)` gets the `ThroughSink`, the `StreamGate` upstream and `FLAG_IGNORE_CACHE_ON_ERROR`;
  - `uncachedFactory(publicOnly)`: plain http through the gate and the length guard;
  - `copyOnlyFactory(keys)`, `bytesByItem()`;
  - `LengthGuard`, used in `http(...)` for both factories;
  - the header loses "streams aren't cached".
- `media/ItemMediaSourceFactory.kt`: `ItemTag.through` (the static part of §1.4) and `ItemTag.uncached`.
- `playback/LocalRenderer.kt`: the track selection of §1.7.
- `playback/PlayerHub.kt`: `heldLocal: Set<ItemRef>` and `claim(refs)`.
- `playback/PlaybackService.kt`:
  - `activeFor`: `claim` the start and its next, then read rows, then `staleThrough` (§1.6);
  - `mediaItem`: `ItemTag(..., through = <NRK/RSS>, uncached = <stale>)`;
  - every item and timeline change sets `heldLocal`. An item leaving it is settled on IO (§1.5);
  - `startedPlaying` (local renderer): `markPlayed` beside design 29's `deleteNews`;
  - `onError`: the length guard's `http_416` path and the gate's exception (§1.6).
- `sync/CacheThrough.kt` (new): `settle(context, ref)`, `catchUp(context)` (one enqueue per sync),
  `evict(context, plan)` (re-checks `heldLocal` before each remove) and `sweepOrphans(context)`.
- `sync/Downloads.kt`: the worker plans with `PlayerHub.heldLocal` and evicts each round before fetching, with
  `extrasFloor(plan.wantsStorytel, StorytelAccount.state() == "ok")` and the limit.
- `sync/Sync.kt`: `catchUp` before `runLock`. Inside it, after the listings: the plan with `held`, the prune
  (re-checking held), `evict`, `sweepOrphans`. `writeReport` computes `extra_bytes` from the plan (L1).
- `sync/ArtPlan.kt`: extras' art in `keep` (§1.11).

**Design 29 coordination:** step 3b builds on design 29 merged into main: Room 3 -> 4, design 29's `Sync.kt` (its two
sync phases, `Sync.runLock`), `Downloads.kt` (the notifier after each finished tray item) and its `startedPlaying`
(`deleteNews`). Nothing in design 29's rules changes:
- a settled stream becomes a `done` row like a download;
- a Storytel "can play" is untouched (never streamed);
- an NRK/RSS item's eligibility is its url, as before.

If design 29 isn't on main when 3b starts, the orchestrator decides the base. Room's version is 29's + 1 either way.

## 6. Tests (by name)

Server (`cargo test`):
- `server/src/tests/device_api.rs` `policy_json_keys_snapshot`: `music` = `{library_version: null, mobile_data: false,
  stream_mobile_data: false, download_mobile_data: false, volume_cap_pct: null, storage_limit_gb: 2,
  storytel_generation: 0}`.
- `server/src/tests/music.rs`:
  - `the_policy_carries_a_small_music_object` (updated: the switches save alone; a missing checkbox is off; the limit's
    choices; a bad one is 400; a stored non-choice is sent as 2);
  - `mobile_data_on_the_wire_is_both_switches` (on/off, off/on, on/on, off/off);
  - `migration_0073_gives_both_switches_the_old_value_and_every_phone_2_gb` (`old_database` at 0072, rows on and off;
    `music_mobile_data` gone);
  - the 0049 defaults test (`:600-603`) reads the new columns;
  - `a_page_from_before_the_update_sets_both_switches_from_mobile_data` (no `v`; the limit unchanged);
  - `the_card_shows_two_switches_and_the_limit_and_saves_back_to_music_settings` (labels, `onchange` submit, the
    hidden `v`, the redirect anchor);
  - `the_card_says_an_older_phone_uses_mobile_data_only_with_both`: differing switches and an app report without
    `extra_bytes`; none when equal, when the report has it, or when only the launcher's fields are there;
  - `the_storage_line_shows_the_limit_and_the_extras` and `the_card_warns_when_kept_items_alone_pass_the_limit`
    (no-limit and older-app variants).
- `music.rs` unit: `music_state_keeps_every_field_the_phone_sends` (the fixture with the two fields, 3a),
  `music_state_clamps_audio_and_extra_bytes`, `storage_limit_reads_choices_null_and_junk`.

Launcher (`testDebugUnitTest`):
- `MusicPlanTest`:
  - `the policy's music object - two switches, an older server's mobile_data for both, a malformed switch keeps what
    is there`;
  - `the storage limit - absent is 2 GB, null is none, junk refuses`;
  - `the managed config carries both switches, the limit and mobile_data as both`;
  - `later pictures follow the downloads switch`;
  - `the managed config keys are the shared contract's` (existing, with the three keys);
  - `music_state passes audio_bytes and extra_bytes` (the fixture test, existing, 3a).
- `PolicyResponseCompatTest`: `music never fails the policy and is carried as is (21)` (today's shape).
- `MusicFileDownloadsTest`: `own files follow the downloads switch`.

Music app (`testDebugUnitTest -PwarningsAsErrors=true`):
- `BridgeTest`: `the managed config the launcher writes` (all keys); `an older launcher's mobile_data sets both
  switches and the limit is 2 GB`; `the storage limit - 0 is none, junk is 2 GB`; the contract test (existing, with
  the three constants).
- `NetTest`: `streaming follows the streaming switch only`; `downloads follow the downloads switch only`; `neither
  while roaming, both on Wi-Fi`.
- `CacheKeysTest`: `the same source ignores the query of an http url, compares a source id whole`.
- `OfflineTest`:
  - `the sink writes only for NRK and RSS above depth 0, for an entry still there, above the free floor`;
  - `a stale partial - bytes with no row, or a row from another source`;
  - `a different total length at a resume is a changed source; unknown lengths are not`;
  - `the played time survives the job's row`;
  - `over the limit the least recently played extras go until under it`;
  - `under the free floor extras go until it holds, or none is left`;
  - `no limit - only the floor evicts`;
  - `the keep set and the held items are never evicted, even over the limit`;
  - `the Storytel floor only while a book is wanted and the login is ok`;
  - `orphans - keys of no row and of no row's HLS prefix, never a held one`.
- `OfflineTest` (plan):
  - `a played item outside the keep set is an extra, an unplayed one is pruned`;
  - `depth 0 has no extras - every row is pruned but the held ones`;
  - `the held set is the current item and the next - neither pruned, the progressive ones not wanted`;
  - `an item the listing dropped is pruned even when played`;
  - `a removed entry takes its extras`;
  - `an entry without a listing keeps its extras`;
  - `a Storytel book is never an extra`;
  - `a settled stream counts as have and isn't wanted`.
- `StorytelDownloadTest` / a `DownloadRun` test: `a changed source at a resume drops the partial and fetches from 0`
  (the guard's 416 through the existing path); `a partial from the same url with a new query is kept`.
- `ReportTest`: `audio_bytes and extra_bytes are always in the report` (the key set = the fixture's, existing).
- `EpisodeArtTest` / `ArtPlan`: `an extra's picture is kept`.
- `GuardsTest` / `SourceScanTest`:
  - `the stream switch is read live from the managed config` (the existing `mobile_data` pin, renamed);
  - `playback writes the cache only through ThroughSink, with FLAG_IGNORE_CACHE_ON_ERROR`;
  - `settle and catchUp never run on writer, and catchUp runs outside runLock`;
  - `DownloadWorker and Sync plan with PlayerHub's held set`;
  - `every MediaCache.remove outside PlaybackService's own load re-reads heldLocal`.

## 7. Steps (each one implementer sitting; server -> launcher -> music)

1. **Server** (§3): the migration, the policy keys, the card (switches, limit, notes), the save handler with `v=2`.
   `cargo test`, `fmt`, `clippy`. Docs: server/CLAUDE.md (Vibb music: data, PWA, wire).
2. **Launcher** (§4): with both contract files and the music app's three constants in one commit. Docs:
   launcher/CLAUDE.md (`MusicSync`, `wantArt`/`syncArt`, `MusicFileDownloads`).
3. **Music**, in two sittings:
   - **3a, the switches and the report fields**: §5.1's `BridgeConfig`, `NetGate` and `Report`; §5.2's 3a list,
     including the `audio_bytes`/`extra_bytes` commit across `server/`, `launcher/` and `music/` (M4: the fields that
     mark a new phone ship with the code that reads the switches).
   - **3b, cache-through, extras, the limit**: the rest of §5 (needs design 29 on main, §5.2). It includes
     `scripts/fake-audio.py` for §8 check 10: a stdlib HTTP server with Range, `--vary` changing the length per
     request, and `--selftest`. Docs: music/CLAUDE.md (the rules "Playback never waits" and "Prune only on a parsed
     listing", the media and sync sections), design 21 §4.5 (a pointer here), emulator.md §6f (`config.json`'s keys,
     §8's checks), PLAN.md, this doc's status.

The order is the dependency order: the server sends the keys, the launcher carries them, the app reads them. Every
step works against the others' old versions (§1.3).

## 8. Device checks (emulator, docs/testing/emulator.md §6f with the launcher's bridge; §6i for Storytel)

Useful commands:
- network: Wi-Fi is unmetered through the launcher's VPN (design 13); `adb shell svc wifi disable` leaves the emulated
  LTE (metered); `adb emu gsm data roaming` / `adb emu gsm data home` for roaming;
- cache size: `adb shell run-as me.vibb.music.debug du -s no_backup/media`;
- the report: `run-as ... cat files/music/report.json`.

Checks:
1. **PWA:** the Music card shows the two switches and the limit. Each saves and the page stays at the card (no jump);
   the event log names them. `curl` the policy: `mobile_data` is true only with both on, and `storage_limit_gb` is 2. A
   server with the old switch on comes up with both on and 2 GB after the upgrade. A device page opened before the
   upgrade and saved after it sets both switches from its one checkbox.
2. **Streaming switch only** (S on, D off), Wi-Fi off: a not-downloaded episode plays and `du` grows while it plays.
   `report.json` `downloads[].waiting` is `wifi`, and no download runs (`adb logcat -s WM-WorkerWrapper`).
3. **Downloads switch only** (S off, D on), Wi-Fi off: the episode says "Ikke lastet ned - trenger Wi-Fi" and the queue
   moves on. Downloads run, and with §6i's fake Storytel a book downloads.
4. **Roaming**, both on: nothing streams or downloads; copies play.
5. **Offline at once:** depth 5, play episode 6 (not downloaded) to its end on Wi-Fi, then the next item. In airplane
   mode it plays and its list shows it on the phone. `extra_bytes` > 0 and the card's line says so.
6. **Not twice:** S on, D off, on mobile data: stream half of a not-downloaded keep-set episode, then play another
   entry. Wi-Fi on: the job completes it. The app's Wi-Fi traffic for it (`adb shell dumpsys netstats detail`, the
   app's uid) is about the second half; after it, `downloads[].have` counts it.
7. **Depth 0:** set an entry to "Ingen", stream an episode: `du` doesn't grow. Any old copies are gone after the next
   sync; the one playing and its next go after the player lets go.
8. **HLS:** an NRK serie episode streamed to the end plays in airplane mode with the copy's rendition.
9. **The limit and the floor:**
   - with a few extras on the phone, set the limit to 1 GB and sync: the least recently played extras go first, the
     keep set and the playing item stay;
   - set a depth whose keep set passes 1 GB: the card warns;
   - fill the emulator's storage to under 1 GiB free (a large file pushed to `/sdcard/Download`) and sync: extras go;
     a stream stops writing and still plays.
10. **A changed source:** `scripts/fake-audio.py --vary` behind a `lan` entry in the debug feed. Stream half, pause
    long enough to drop the connection, play on: playback goes on uncached, the row is gone, and the next sync's
    orphan sweep empties its bytes.
11. **Older phone:** the new server with the launcher from `main` (or the music app from `main`): with only streaming
    on, nothing streams off Wi-Fi, and the card shows the older-phone note. A fresh phone before its app's first
    report shows no note.

## 9. Defaults chosen (veto if wrong)

- Both switches take the old value; the old wire key is "both" (AND), never OR.
- `later` pictures and the launcher's own-file downloads follow the **downloads** switch.
- Played extras include a downloaded copy that was played and left the keep set, not only streams.
- The limit's choices are 1/2/5/10 GB/No limit, default 2 GB for new and existing phones. It counts Storytel books and
  not own files.
- The floor is 1 GiB (1.5 GB while a Storytel book is wanted and the login is ok); there is no age limit.
- A partial extra is kept (it saves bytes on a replay) but doesn't play offline.
- The phone's player is pinned to the download's HLS rendition (no adaptive bitrate).
- A paused progressive stream isn't completed in the background until the player lets go of it (§1.6).
- A page opened before the update maps its `mobile_data` to both switches (not a refusal).
- The upstream gate makes the streaming switch exact for a copy's holes and the preloaded next item.

## Design QA (2026-10-13, `docs/design/qa-30-design.md`; every finding accepted, with the orchestrator's decisions)

**User decision (2026-10-13), replaces M3:** a per-phone **Music storage limit** on the Music card (1/2/5/10 GB/No
limit, default 2 GB), carried in the policy (`storage_limit_gb`) and the managed config (`storage_limit_gb`, Int, 0 =
none; absent = 2 GB). It counts the whole audio cache, Storytel books included. Over it, extras go least recently
played first; the keep set and the held set never go, and the card warns when they alone exceed it. The 1 GiB /
1.5 GB free floor stays as a fixed safety rule. Changes: §1.2 (the column in 0073), §1.3 (keys, fallbacks, the table),
§1.8 (the limit, what it counts, `extrasToEvict`), §1.10 (`audio_bytes`, the line and the warning), §3, §4
(`bridge_contract.json`'s third key), §5, §6, §8 check 9, §9. `EXTRAS_MAX_BYTES` is gone.

- **H1 (the held set; settle blocks):**
  - `PlayerHub.heldLocal` is a set: the current item and the next in the timeline. `held` (never pruned, evicted,
    swept or settled) and `busy` (held, progressive, not done: never fetched) take it.
  - Settle is a non-blocking span check (content length + `isCached`; HLS through copy-only playlists), on IO in the
    service's scope, never on `writer`. `catchUp` runs before `Sync.runLock`. The service's release leaves it to
    `catchUp`.
  - §1.5, §1.6, §5, tests.
- **M1 (bytes without a row):** the sink inserts the not-done row before its first write; `markPlayed` only sets
  `playedAt`, never by the current depth; a pure `orphanResources` sweep at every sync, keys read before rows (§1.4,
  §1.5, §1.8).
- **M2 (decided per queue; a full disk fails playback):** `ThroughSink` decides at each `open` from the live depth,
  the static tag and free space. The playback factory sets `FLAG_IGNORE_CACHE_ON_ERROR`. Network rules are the
  upstream gate's (§1.4).
- **M3 (the byte cap vs "only when space is needed"):** superseded by the user's storage limit (above). §1.8 now says
  extras go when space is needed **or** when the cache is over the parent's limit.
- **M4 (`extra_bytes` ships too early; a launcher-only report reads as "older"):** the `audio_bytes`/`extra_bytes`
  contract commit moves to step 3a, with the switches. The note needs an app report (`cache_bytes` present) and no
  `extra_bytes` (§1.3, §3, §7).
- **M5 (patchwork copies):**
  - the assumption is stated;
  - `LengthGuard` compares the response's total with the stored length and throws a synthetic 416: the job takes the
    existing `http_416` path, and the player drops the row and plays on uncached;
  - the test is `a changed source at a resume drops the partial and fetches from 0`, and the device check uses
    `scripts/fake-audio.py --vary` (§1.6, §6, §8 check 10).
- **L1:** `writeReport` computes `extra_bytes` (and `audio_bytes`) itself, so the job's report doesn't send 0 (§1.10).
- **L2:** the Storytel floor applies only while the login is `ok` (`extrasFloor(wantsStorytel, loginOk)`).
- **L3:** `artWant` keeps extras' pictures (§1.11).
- **L4:** the service claims the start item and its next before reading rows. Every removal re-reads the held set.
  The upstream `StreamGate` makes the streaming switch exact (§1.6).
- **L5:** `sameSource` compares `withoutQuery` (lower-cased scheme, raw authority, raw path) for http(s) URLs, the
  whole string otherwise. It is used by `staleThrough` and by `DownloadRun`'s partial drop and `lastBytes`; `mayRetry`
  keeps full URLs (§1.6).
- **L6:**
  - a hidden `v=2` marks today's page; a post without it maps `mobile_data` to both switches and leaves the limit;
  - the streaming label says "(not for an entry that keeps nothing)";
  - the form keeps its `id`, its anchor and `scroll-restore.js` (§3).
- **L7:** `old_database` moves to `src/tests/mod.rs`; the 0049 defaults test changes; the server test paths are
  `server/src/tests/...`; `keepPlayedAt` is pure and tested; a pin that the worker and the sync pass `PlayerHub`'s
  set (§3, §6).
- **L8:**
  - Storytel books are never extras (§1.8);
  - `catchUp` enqueues the job once per sync;
  - partial extras are re-checked at every sync, which is cheap as a span check (§1.5);
  - a host without `Content-Length` never writes and never settles (§1.4).

## Step 1 implementation status (2026-10-13, branch `design-30-s1`)

Branch `design-30-s1` from `c1b3068f` with `main` (designs 28 and 29) merged in, no conflicts. Not pushed. Server only
(§3); nothing of steps 2, 3a or 3b.
- `migrations/0073_music_data_and_storage.sql` (main was at 0072): the two switches from `music_mobile_data`, the old
  column dropped, `music_storage_limit_gb INTEGER DEFAULT 2`.
- `models.rs` `DevicePolicy`; `music.rs`: `MusicPolicy` in §3's order, `mobile_data_wire`, `STORAGE_LIMITS`,
  `DEFAULT_STORAGE_LIMIT_GB`, `storage_limit`, `storage_limit_label`, `older_phone_note`, `storage_line`, `gigabytes`.
- `handlers/music.rs`: `MusicCard` (the switches, `data_note`, `storage_limit(s)`; the storage line and its warning go
  into the card's `lines`/`warnings`), `mobile_switches`, `save_device_settings` (one `UPDATE ... RETURNING` that keeps
  the stored limit when the post has none, so the event names the limit in force).
- `templates/device_detail.html` (`#music-settings`, same id and redirect anchor, scroll-restore untouched; the older
  phone note is an `error` block, like the card's other warnings), `templates/music_entry.html`.
- **Interpreted:**
  - `older_phone_note` and `storage_line` take the report's numbers (`cache_bytes`, `extra_bytes`, `audio_bytes`) as
    plain `Option<i64>`s instead of `&MusicState`: `MusicState` has no `audio_bytes`/`extra_bytes` until 3a, and this
    way both rules are complete and unit-tested now. The card passes `None` for the two until 3a, which then only
    wires `report.audio_bytes`/`report.extra_bytes` in (two marked call sites in `device_card`).
  - The 0073 test builds its 0072 database with `tests::cleanup::migrated_before(73)` + `finish`, the helper every
    other migration test uses (0048, 0049, ...); `old_database` (L7) stays in `docker.rs`, where its admin row and
    session are what the Docker tests need. Nothing else needed a 0072 database.
  - A `storage_limit` field that is present but not a choice (an empty string too) is 400; only a missing field keeps
    the stored limit.
- Tests: `music::tests` `storage_limit_reads_choices_null_and_junk`, `older_phone_note_needs_an_app_report_without_
  extra_bytes`; `src/tests/music.rs` `the_policy_carries_a_small_music_object` (updated), `mobile_data_on_the_wire_is_
  both_switches`, `migration_0073_gives_both_switches_the_old_value_and_every_phone_2_gb`, the 0049 defaults test,
  `a_page_from_before_the_update_sets_both_switches_from_mobile_data`, `the_card_shows_two_switches_and_the_limit_and_
  saves_back_to_music_settings`, `the_card_says_an_older_phone_uses_mobile_data_only_with_both` (the "report has
  `extra_bytes`" case is the pure test's until 3a), `the_storage_line_shows_the_limit_and_the_extras`,
  `the_card_warns_when_kept_items_alone_pass_the_limit`; `device_api.rs` `policy_json_keys_snapshot`;
  `music_storytel.rs` `library_gates_storytel` (the help text moved to the downloads switch).
  `music_state_keeps_every_field_the_phone_sends` / `music_state_clamps_audio_and_extra_bytes` are 3a's.
- The launcher's `PolicyResponseCompatTest` and `MusicPlanTest` literals keep today's four keys until step 2 (§4): the
  launcher carries `music` as a raw object, so the new keys break nothing.
- Docs: `server/CLAUDE.md` (Vibb music library: data, PWA, wire; the Storytel note).
- Not device-tested: §8 check 1.

## Step 2 implementation status (2026-10-13, branch `design-30-s2`)

Branch `design-30-s2` from step 1 (`97b197f1`). Not pushed. Launcher only (§4), plus the shared contract file and the
music app's three constants; nothing of steps 3a or 3b (the music app reads none of the new keys yet).
- `music/MusicPlan.kt`: `MusicRestrictions.STREAM_MOBILE_DATA`/`DOWNLOAD_MOBILE_DATA`/`STORAGE_LIMIT_GB`;
  `MusicPolicy(libraryVersion, streamMobileData, downloadMobileData, volumeCapPct, storageLimitGb, storytelGeneration)`
  with `mobileData` = both as a derived property; `musicPolicy` (the private `mobileSwitch`; `DEFAULT_STORAGE_LIMIT_GB`
  = 2, `MAX_STORAGE_LIMIT_GB` = 1000); `restrictionsFor` appends the two switches and `storage_limit_gb`
  (`storageLimitRestriction`, 0 = no limit) after `volume_cap_pct`, the contract's order; `artToFetch(...,
  downloadMobileData, ...)`.
- `music/MusicSync.kt`: `downloadMobileData()` replaces `mobileData()`; `artFetchable` passes the downloads switch.
  `music/MusicFileDownloads.kt`: the gate reads it. `server/dto/PolicyResponse.kt`: the `music` KDoc names the keys.
- `bridge_contract.json` (both copies, byte-identical): `restrictions` gains the three keys, appended. The music app's
  `RestrictionKeys` gets the three constants and `BridgeTest`'s key list them, in the same commit.
- **Interpreted:**
  - A switch key that is present but a JSON string (`"true"`), a number or `null` refuses the object: only a JSON
    Boolean counts. `mobile_data` keeps today's lenient read (`booleanOrNull`, so a `"true"` string still passes), to
    change nothing for it.
  - `storage_limit_gb` must be a non-string JSON integer in 1..1000; `0`, a fraction, a string, a Boolean or anything
    above 1000 refuses.
  - `MusicPolicy` keeps a `mobileData` getter (`stream && download`) for what the managed config's old key writes, so
    no caller computes the AND itself.
  - No `streamMobileData()` reader in `MusicSync`: the launcher has no streaming of its own.
- Tests: `MusicPlanTest` `the policy's music object - two switches, an older server's mobile_data for both, a malformed
  switch keeps what is there`, `the storage limit - absent is 2 GB, null is none, junk refuses`, `the managed config
  carries both switches, the limit and mobile_data as both`, `later pictures follow the downloads switch` (behaviour,
  plus a pin on `artFetchable`), the contract test and the existing managed-config tests with the new constructor;
  `PolicyResponseCompatTest` `music never fails the policy and is carried as is (21)` (today's shape, read through
  `musicPolicy`, and a malformed new key still decodes the policy); `MusicFileDownloadsTest` `own files follow the
  downloads switch` (the gate on metered/roaming/Wi-Fi from the policy, plus a pin on the glue); music `BridgeTest`
  (the contract's key set with the three constants). Launcher `testDebugUnitTest`: 1078 tests, 0 failures;
  `assembleDebug assembleRelease -PwarningsAsErrors=true` green (release with the stub tsnet). Music
  `testDebugUnitTest -PwarningsAsErrors=true`: 614 tests, 0 failures.
- Docs: `launcher/CLAUDE.md` (`MusicSync`, its `musicPolicy` rules, `MusicFileDownloads`, `syncArt`).
- Not device-tested: nothing of §8 is step 2's alone; check 11's "launcher from main" case is the old launcher.

## Step 3a implementation status (2026-10-13, branch `design-30-s3a`)

Branch `design-30-s3a` from step 2 (`777e4434`). Not pushed. The music app reads the two switches and the limit, the
gate and the download job follow them, and the `audio_bytes`/`extra_bytes` contract commit across `server/`,
`launcher/` and `music/` (M4); nothing of step 3b (no cache-through, extras, eviction or Room change).
- `bridge/BridgeConfig.kt`: `streamMobileData`/`downloadMobileData` replace `mobileData`; `storageLimitGb: Int?`
  (default `DEFAULT_STORAGE_LIMIT_GB` = 2, null = none); the pure `mobileSwitch(values, key)` and
  `storageLimit(values)` (`MAX_STORAGE_LIMIT_GB` = 1000, the launcher's bound).
- `logic/NetGate.kt`: `streamAllowed(caps, streamMobileData)`, `downloadAllowed(caps, downloadMobileData)` (one
  private formula), `downloadWaiting(caps, downloadMobileData, storageLow)` through `downloadAllowed`,
  `playability(..., streamMobileData)`; the header names both switches.
- `logic/Report.kt`: `stateReport(versionCode, libraryVersion, cacheBytes, audioBytes, extraBytes, ...)`, both always
  `put` after `cache_bytes`. `Sync.writeReport` sends `MediaCache.totalBytes` and 0 (the download job writes the
  report through the same function).
- `data/Store.kt`: `streamMobileData`, `downloadMobileData`, `storageLimitGb` (live from the managed config;
  unmanaged, the debug feed's prefs). `DebugFeedReceiver`: `config.json`'s `stream_mobile_data`,
  `download_mobile_data`, `storage_limit_gb`; a bare `mobile_data` sets both switches.
- Callers: `PlaybackService.playable`/`playableFrom`/`onError` read the streaming switch; `Downloads.enqueue`'s network
  constraint, `DownloadWorker` and `Sync` (`enqueue`, `downloadWaiting`) the downloads switch.
- The contract commit: `server/testdata/music_state.json` (`"audio_bytes": 100000000, "extra_bytes": 23456789` after
  `cache_bytes`); `music.rs` `MusicState.audio_bytes`/`extra_bytes`, clamped like `cache_bytes`; `handlers/music.rs`
  `device_card` passes the report's values to `storage_line` and `older_phone_note` (step 1's two marked call sites);
  the launcher's `musicState` key list.
- **Interpreted:**
  - `audioBytes`/`extraBytes` are required parameters of `stateReport` (no defaults), so no caller can leave them out.
  - The music app's switch read: a key the launcher wrote counts as `== true` (a non-Boolean is off, as every
    restriction); only an absent key falls back to `mobile_data`. The limit: only an `Int` counts (a `Long` is junk,
    read as 2 GB; the launcher writes an Int, qa-21-step3-code H1).
  - Unmanaged (the debug feed), a switch never set falls back to the old `mobile_data` pref, so an emulator fed before
    this step keeps its setting.
  - `Store.storageLimitGb` is read by nothing yet (3b's eviction).
  - A report whose `audio_bytes`/`extra_bytes` isn't a number is refused whole by the server, as `cache_bytes` is.
  - In 3a `extra_bytes` is 0, so the card's warning reads the whole audio cache as kept; true until 3b keeps extras.
- Tests: music `BridgeTest` `the managed config the launcher writes`, `an older launcher's mobile_data sets both
  switches and the limit is 2 GB`, `the storage limit - 0 is none, junk is 2 GB`, the contract test (the three keys
  read), the report-keys fixture test; `NetTest` `streaming follows the streaming switch only`, `downloads follow the
  downloads switch only`, `neither while roaming, both on Wi-Fi`; `ReportTest` `audio_bytes and extra_bytes are always
  in the report` (+ the key set); `GuardsTest` `the stream switch is read live from the managed config`; launcher
  `MusicPlanTest` `music_state passes audio_bytes and extra_bytes` (and the fixture end-to-end test); server
  `music::tests` `music_state_keeps_every_field_the_phone_sends` (the new fixture), `music_state_clamps_audio_and_
  extra_bytes`; `src/tests/music.rs` `the_card_says_an_older_phone_uses_mobile_data_only_with_both` (now with a
  report that has `extra_bytes`), `the_storage_line_shows_the_limit_and_the_extras` (a 3a report through the card,
  the line and the warning, then a higher limit).
  Server `cargo test`: 671 passed, `fmt --check` clean, `clippy --all-targets` 13 warnings, all in code this step doesn't
  touch (none new). Launcher `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: 1079 tests, 0 failures. Music
  `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`: 621 tests, 0 failures.
- Docs: `music/CLAUDE.md` (the gate rule, the bridge's keys and report fields, Storytel's switch), `server/CLAUDE.md`
  (the storage line), `launcher/CLAUDE.md` (`music_state`), `docs/testing/emulator.md` §6f (`config.json`'s keys).
- Not device-tested: §8 checks 2, 3, 4 and 11 are 3a's (with 3b's write-through for check 2's `du`).

**Code QA fix round** (`qa-30-s3a-code.md`, 0 High, 0 Medium, 2 Low, 2 Nits; all fixed, its "Fixes" table):
- L1: `logic/NetGate.kt` `stopRunningDownload(caps, downloadMobileData)` (= the network doesn't allow downloads now);
  the sync passes it as `Downloads.enqueue(..., stopRunning)`, which then `REPLACE`s a running job instead of
  appending (WorkManager stops it; the new job waits for its constraint; the partial resumes with Range). The
  worker's own re-enqueues never stop. Only where the switch forbids the network, so a Wi-Fi download is never
  interrupted by a sync. §1.1 says so.
- L2: `Store`'s unmanaged path is the managed config's own rules on the feed's prefs (`mobileSwitch(prefs.all, key)`,
  `storageLimit(prefs.all)`), pinned in `GuardsTest` with the debug feed's order (a bare `mobile_data` first, a key of
  its own after it).
- N1: `Downloads.kt`'s header names the downloads switch. N2: the debug feed stores `storage_limit_gb` as given; the
  older-phone card test asserts the 3a report's storage line, so it proves the report was stored.
- Music 622 tests, launcher 1079, server 671 (fmt clean, clippy unchanged at 13, none in this step's code); QA's M4
  and two more mutations (the feed's order, `enqueue` ignoring `stopRunning`) go red.
