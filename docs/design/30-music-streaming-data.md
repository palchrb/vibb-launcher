# 30 - Vibb music: streaming and downloads on mobile data, and the stream saved as it plays (buildable spec)

Architect, 2026-10-13. Branch `design-30` from `main` (`3292cd32`).

Sources:
- design 21 §4.5-§4.6 (keep/prune, "Streams aren't cached", the network gate), deviation 5 (depth 0 keeps nothing),
  21b §6, 26a (Sonos), 26b §3.3-§4.5 (Storytel: download-only, the 1.5 GB floor), design 13 (the gate), design 29
  (branch `design-29-impl`: Room 3, `Sync.kt`, `Downloads.kt`);
- the code on `main`:
  - server: `migrations/0049_music.sql` (`device_policy.music_mobile_data`), `models.rs` `DevicePolicy`,
    `music.rs` `MusicPolicy` / `policy_music` / `MusicState` / `sanitize_music_state`, `handlers/music.rs`
    `MusicCard` / `device_card` / `save_device_settings`, `templates/device_detail.html` (`#music-settings`),
    `templates/music_entry.html`, `tests/device_api.rs` `policy_json_keys_snapshot`, `tests/music.rs`,
    `testdata/music_state.json`;
  - launcher: `music/MusicPlan.kt` (`MusicRestrictions`, `MusicPolicy`, `musicPolicy`, `restrictionsFor`,
    `musicState`, `artToFetch`), `music/MusicSync.kt` (`mobileData()`, `artFetchable`), `music/MusicFileDownloads.kt`,
    `PolicyResponseCompatTest`, `MusicPlanTest`, `test/resources/bridge_contract.json`;
  - music: `bridge/BridgeConfig.kt`, `data/Store.kt` (`mobileData`), `logic/NetGate.kt`, `logic/Offline.kt`,
    `logic/Report.kt`, `media/MediaCache.kt`, `media/ItemMediaSourceFactory.kt`, `sync/DownloadPlan.kt`,
    `sync/DownloadRun.kt`, `sync/Downloads.kt`, `sync/Sync.kt`, `data/MusicDb.kt`, `data/Snapshot.kt`,
    `playback/PlaybackService.kt` (`mediaItem`, `playable`, `startedPlaying`, `onError`), `playback/LocalRenderer.kt`,
    `playback/PlayerHub.kt`, `debugfeed/DebugFeedReceiver.kt`.

**The user's decisions (final, 2026-10-13):**
1. Mobile data splits in two per-phone switches on the device page's Music card: streaming (an item that isn't
   downloaded, off Wi-Fi) and downloads (off Wi-Fi, Storytel books included). On Wi-Fi both are always allowed; never
   while roaming; what's downloaded plays anywhere. The existing value is migrated.
2. A streamed item's bytes are written to the music app's cache as they play: what was played is offline at once and
   isn't downloaded twice; the download job completes the rest. **Correction (same day):** the keep set (the newest
   N, the bookmarked item and the next) is always kept as today; a played item outside it is a **played extra**, kept
   while there is room and evicted only when space is needed - least recently played first, never the item playing.
3. "Ingen" (depth 0) still keeps nothing, the item in progress included (design 21 deviation 5).

## 0. Today, and the change

**Today** one switch, `device_policy.music_mobile_data` ("Music may use mobile data: downloads and streaming also off
Wi-Fi"), reaches the phone as the policy's `music.mobile_data`, the launcher's managed-config key `mobile_data`, and
the music app's `Store.mobileData`. It gates, with the same formula (design 13's, ported: internet, a physical
transport, not roaming, NOT_METERED unless the switch):
- music: a stream (`streamAllowed`, `playability`), the download job (`Downloads.enqueue`: `UNMETERED` or
  `NOT_ROAMING`; `downloadWaiting` per item), the report's `waiting`;
- launcher: the own files it fetches for the music app (`MusicFileDownloads.gate`) and the `later` episode pictures
  (`artToFetch`: "the audio's download gate, since kept art goes with kept audio").

Playback reads a copy first and streams the rest **without writing** (`playbackFactory`:
`setCacheWriteDataSinkFactory(null)`; 21 §4.5 "Streams aren't cached", as vibb). Only the download job writes the one
`SimpleCache` (`NoOpCacheEvictor`, keyed per item). Every sync prunes every copy outside its entry's keep set.

**The change:** two switches; a stream at depth above 0 writes the cache; "on the phone" still means a finished copy
(a `done` row), which a fully streamed item becomes without a download; copies outside the keep set that were played
stay as extras until space is needed.

**Storage, as the code has it** (the brief said "downloads refused below 1 GB free"; that is the server's upload
rule, 21 §1): the phone's download job needs WorkManager's storage-not-low, the report says `storage` below 500 MiB
(`Sync.LOW_STORAGE_BYTES`), and a Storytel book needs 1.5 GB free (`STORYTEL_FREE_FLOOR`). This design adds one
constant, `EXTRAS_FREE_FLOOR` (1 GiB, §1.8).

## 1. Decisions

### 1.1 What each switch gates

| | Wi-Fi (NOT_METERED) | mobile data, not roaming | roaming |
|---|---|---|---|
| a copy plays | yes | yes | yes |
| a stream (music app: `streamAllowed`, `playability`) | yes | **streaming** switch | no |
| the download job, Storytel books included (`downloadWaiting`, `Downloads.enqueue`) | yes | **downloads** switch | no |
| launcher: own files for the music app (`MusicFileDownloads`) | yes | **downloads** switch | no |
| launcher: `later` episode pictures (`artToFetch`) | yes | **downloads** switch | no |
| launcher: `now` pictures (the now-playing window, ~60 KB each) | yes | yes | yes (unchanged) |
| writing a stream's bytes to the cache (§1.4) | follows the stream: no extra traffic | | |

A stream on mobile data under the streaming switch writes its bytes too: they arrived anyway. It never starts a
download on mobile data without the downloads switch: the rest of a keep-set item waits for the download gate.

### 1.2 The migration: both switches take the old value

Migration `0073_music_mobile_data_split.sql` (**renumbered at the merge** if another branch takes 0073 first; main
is at 0072, design 29 adds none): add `music_stream_mobile_data` and `music_download_mobile_data` (`INTEGER NOT NULL
DEFAULT 0`), copy `music_mobile_data` into both, drop `music_mobile_data` (precedent: 0012, 0017; no trigger or index
names it). Why both: the old switch allowed exactly both, so every phone behaves the same until the parent changes
something. Off stays off (nothing new is spent); on stays on for both (turning streaming off for a phone that streamed
yesterday would silently break it, and downloads off would leave Storytel books undelivered without the parent
having touched anything).

### 1.3 Compatibility: the old key stays, as "both"

- **Policy JSON** (`PolicyResponse.music`): keeps `mobile_data` and adds `stream_mobile_data` and
  `download_mobile_data`. `mobile_data` = `stream && download`. An older launcher's `musicPolicy` requires
  `mobile_data` (missing = the whole object refused, qa-21-step3-code M4), so it can never go.
- **Why AND for the old key:** an older launcher or music app has one switch for both. With AND it never spends data
  the parent turned off; with OR, "streaming on, downloads off" would download Storytel books over mobile data. AND's
  cost (an older phone doesn't stream off Wi-Fi while only streaming is on) is visible in the PWA (below).
- **Managed config** (launcher -> music app): the launcher writes `mobile_data` (= both, for an older music app) plus
  `stream_mobile_data` and `download_mobile_data` (Booleans: storable, qa-21-step3-code H1). The contract
  `bridge_contract.json` gains both keys (appended to `restrictions`), byte-identical in `launcher/` and `music/`, in
  one commit with the music app's two `RestrictionKeys` constants (`BridgeTest` checks constants against the file).
- **Reading, both apps** (one pure rule each): a new key that is a Boolean is used; a new key that is **absent** falls
  back to `mobile_data` (an older server or launcher); in the launcher a new key that is present but **not a Boolean**
  refuses the music object like any malformed field (`musicPolicy` returns null: keep what is there). The music app's
  `bridgeConfig` reads restrictions as today: anything but `true` is off.

| server | launcher | music app | result |
|---|---|---|---|
| new | new | new | two switches |
| new | new | old | the old app reads `mobile_data` = both |
| new | old | any | the old launcher reads `mobile_data` = both, writes only it; a new app falls back to it |
| old | new | new | no new keys: both = `mobile_data` = the old switch |
| old | old | new | `mobile_data` only: both = it |

- **The PWA says when a phone is older:** the music app's report gains `extra_bytes` (§1.10). An older music app
  never sends it, and an older launcher drops it (its `musicState` passes known keys only). So a `music_state` without
  `extra_bytes` means exactly the cases where the phone runs on `mobile_data` = both. When the two switches differ and
  the phone's last report lacks it, the card says: "This phone's launcher or music app is older: it uses mobile data
  for music only when both switches are on. Update both." (No report yet: nothing.)

### 1.4 Cache-through: who writes

A stream writes the cache (`CacheDataSource` with its default `CacheDataSink`, the item's own key factory) when
`cacheThrough` says so (pure, `logic/Offline.kt`):
- the entry's depth is not 0 (`entry.cache != 0`);
- it is an NRK or RSS item (not an own file - a local file; not a Storytel book - copy-only, never streamed on the
  phone, 26b §4.4);
- free space where the cache lives is at least `EXTRAS_FREE_FLOOR` (1 GiB) when the item is loaded. A full disk would
  fail the sink's write and with it the playback (Media3 throws a sink error for one read cycle), so below the floor
  the stream reads without writing, as today. With 1 GiB of margin, one item can't fill the disk mid-play.

Decided per media item when it is built (`mediaItem` -> `ItemTag.cacheThrough`), so an entry set to "Ingen" while its
item plays writes until that item is left; the next sync prunes it (§1.9).

**Depth 0: never written** (not "write, then prune at once"). "Ingen" promises nothing stays on the phone. Write-then-
prune would leave the bytes until the next prune (a sync, up to 6 h), and the item being played can't be pruned
under the player (§1.6), so "at once" isn't possible. Not writing is exactly today's read-only path: no new code and
no flash writes. The cost is the one the parent chose: a replay streams again.

**Sonos:** a room fetches the URL itself (26a): nothing passes through the phone's cache, nothing is written or marked
played. **Storytel:** downloads only, under the downloads switch; a book is never streamed on the phone.

### 1.5 "On the phone" is still a finished copy: a fully streamed item is settled into one

`Snapshot.onPhone`, `visible()`, `playability(hasCopy)`, `offlineSummary`, the report's `have` and design 29's "can
play" all read `downloads.done`. That stays the only meaning of "on the phone". New:
- **`downloads.playedAt`** (Room, nullable): when the phone's own player last started the item (stream or copy). The
  player's `markPlayed` inserts a row for a cache-through stream that has none (`done = false`, `url` = the streamed
  url, `bytes` 0) and sets `playedAt`; for an existing row it only sets `playedAt`. Every byte in the cache thus has a
  row, so prune and eviction see it (a stream with no row would leak).
- **Settle** (`sync/CacheThrough.kt` `settle`): checks, without the network, whether a not-done row's item is
  complete in the cache, by running the download's own `Downloads.fetch` with a **copy-only** factory (no upstream, no
  sink - `MediaCache.copyOnlyFactory`, as a Storytel book plays): Media3's `CacheWriter` skips every cached span and
  fails at the first hole. Success = a finished copy: the row becomes `done` with its bytes and, for HLS, the variant
  (`pickVariant` on the cached multivariant playlist, the download's own rule). It runs when the phone's player
  leaves a cache-through item (item change, a new queue, the end, the service's release) and, as catch-up, at every
  sync for every played, not-done row not held by the player. Cheap: span lookups and two cached playlists.
- An item streamed to its end is therefore on the phone at once: offline it plays (`LOCAL`), a withdrawn one stays
  visible, and the download plan doesn't want it.

### 1.6 The download job and a stream of the same item

- **Resume from what the stream wrote:** `ProgressiveDownloader` and `HlsDownloader` go through `CacheWriter`, which
  reads only the uncached ranges (progressive) and segments (HLS) - the same mechanism that resumes a partial download
  today. The row's `url` equals the item's, so `DownloadRun` keeps the partial (qa-21-step2-code L7 drops only a
  partial from another url). Nothing is downloaded twice.
- **A stream's stale partial:** when a queue is built (`activeFor`), a not-done row whose `url` isn't the item's url
  any more (the source re-hosted it) is deleted with its bytes before the player opens the item (`staleThrough`). Else
  the player would read the old file's spans beside the new file's bytes. (Today's read-only playback has the same
  hazard with a failed download's partial; this closes it too.)
- **The item the player holds** (`PlayerHub.loadedLocal`: the item the phone's ExoPlayer has loaded, playing or
  paused; null on a Sonos room):
  - **never pruned or evicted** (any kind): deleting spans under a reading player breaks playback;
  - **not fetched while it is a progressive stream that isn't done** (`busy`): the player's `CacheDataSource` holds
    `SimpleCache`'s lock on the hole it streams into (`[position, end)`) for as long as its data source is open - the
    whole item, paused included - and the downloader (`createDataSourceForDownloading`, `FLAG_BLOCK_ON_CACHE`) would
    block on it until WorkManager stops the job. HLS isn't held back: each segment is its own resource and its lock
    lasts one segment load, so the job waits seconds at most and then the player reads what the job wrote.
  - **The cost, stated:** a progressive keep-set item streamed, paused and left loaded isn't completed in the
    background until the player lets go of it (another item, another entry, the service's end - then `settle`
    enqueues the job). It still has what the player buffered (~50 s past the pause). The alternative - the job and the
    stream both fetching the same bytes - is what decision 2 removes.
- **The reverse** (the job fetches X, then the kid plays X): the player can't take the job's lock and reads that
  range from the network without writing - today's behaviour, unchanged.

### 1.7 HLS: the stream plays the rendition the download keeps

A download keeps one rendition (`pickVariant`: the highest up to `HLS_MAX_BITRATE` 192 kbit/s, else the lowest), and a
copy plays with that stream key. A stream with adaptive selection would write segments of whichever rendition the
bandwidth picked. So the phone's player is pinned: `TrackSelectionParameters` with `setMaxAudioBitrate(HLS_MAX_BITRATE)`
and `setForceHighestSupportedBitrate(true)` (`LocalRenderer`, player-wide) - the same choice as `pickVariant` (out of
constraints, Media3 prefers the lowest bitrate, as `pickVariant` does). Nothing is lost: these are audio renditions
of at most 24 kB/s, and progressive items already stream at one fixed bitrate. A stray rendition (bitrates unknown)
only wastes bytes under the item's `hls:` prefix: they count as the item's and go with it. Playlists go through the
cache as today (keys without the query, `CacheKeys.kt`).

### 1.8 Two classes: the keep set and the played extras

`downloadPlan` classifies every row of an entry with a parsed listing:
- **keep set** (`keepSet` / `storytelKeepSet`, unchanged): kept, and fetched when missing, as today;
- **played extra**: not in the keep set, `playedAt != null`, the item still listed, the entry's depth not 0. Kept;
  never fetched; a partial one stays partial (it saves those bytes on a replay but doesn't play offline). A copy
  downloaded for the keep set, played, and then left behind by a newer episode is an extra too: the rule is "played on
  the phone", not "came from a stream" (one rule; the same bytes are worth the same to the kid);
- **everything else** outside the keep set is pruned, as today: never played, no longer listed, or depth 0.

**Eviction** (pure `extrasToEvict`, `logic/Offline.kt`): least recently played first (`playedAt`, then entry id and
item key for a stable order), never the item the player holds, while
- the extras' total bytes exceed `EXTRAS_MAX_BYTES` (**1 GiB**, about 18 hours at 128 kbit/s), or
- free space is below the floor: `EXTRAS_FREE_FLOOR` (**1 GiB**), or `STORYTEL_FREE_FLOOR` (1.5 GB) while the plan
  wants a Storytel book (`extrasFloor`): extras give way to the keep set before Storytel's floor or Android's storage-
  low stop a download.

Each evicted extra lowers the total and raises the free space by its bytes; eviction stops when both hold or no
candidate is left. **No age limit:** with a byte cap the extras are bounded, and an age limit would delete what the
kid replays while the phone has room - the opposite of "kept opportunistically". The keep set is never evicted: if
the phone is still short, downloads wait (`waiting: storage`) as today.

Where it runs: the sync (after the listings, with the prune) and the download job (each planning round, before
fetching, so a Storytel book can get its floor). Bytes per extra come from one pass over the cache's keys
(`MediaCache.bytesByItem`).

### 1.9 Removals

- An entry the library no longer names: every row goes, extras included (`removedEntries`, as today).
- An entry set to "Ingen": keep set empty, no extras (§1.8) - every row is pruned at the next sync, except the item the
  player holds, which goes at the first sync after it is left.
- An item the listing no longer names: pruned (not an extra), as today.
- An entry whose listing isn't there or doesn't parse: keeps everything, extras included, and evicts nothing (QA #3).
- Positions and bookmarks: unchanged (never deleted by any of this).

### 1.10 The report and the PWA

- `cache_bytes` stays the whole cache plus own files and covers: extras (partial too) are in it.
- New `extra_bytes`: the played extras' bytes after this run's eviction, **always sent** (0 when none). It goes into
  `server/testdata/music_state.json` (every `music_state` field), the server's `MusicState` (clamped like
  `cache_bytes`), the launcher's `musicState` key list, and the music app's `stateReport`, in one commit.
- The device card's report line: "Stored on the phone: 1234.5 MB" becomes, when `extra_bytes > 0`, "Stored on the
  phone: 1234.5 MB, of which 210.0 MB was played beyond the offline depth (kept while there is room)."
- Per entry `have`/`want` and `downloads_waiting` count the keep set only, as today.

### 1.11 Deviations from vibb

- **Streams are cached** (vibb: "Streams aren't cached", 21 §4.5): the Pi has a disk and Wi-Fi; a phone on mobile
  data pays for a second download, so a played item is kept from its stream.
- **`prune_cache` keeps played extras** outside the keep set until space is needed (vibb deletes everything outside
  it). Depth 0 still keeps nothing, as vibb's cache 0.
- `logic/Offline.kt`'s header says which functions are ours (`cacheThrough`, `extrasToEvict`, `extrasFloor`,
  `staleThrough`); `NOTICE.md` needs no change.

## 2. Behaviour (depth 5 unless noted; S = streaming switch, D = downloads switch)

| Situation | Today | Design 30 |
|---|---|---|
| Wi-Fi, S off, D off | streams and downloads | the same; what streams is written |
| Mobile data, S on, D off; a not-downloaded episode | plays only with the one switch | plays; written as it plays; the rest waits for Wi-Fi |
| Mobile data, S off, D on | (one switch) | a not-downloaded episode: "Ikke lastet ned - trenger Wi-Fi"; downloads and Storytel books run |
| Roaming, both on | nothing over the network | the same; copies play |
| Episode 6 (outside the newest 5) streamed to its end on Wi-Fi | gone when it ends | on the phone: plays in airplane mode; an extra |
| Keep-set episode half streamed, then the next item | the job downloads it all | the job fetches the second half only |
| Depth 0, an episode streamed | nothing kept | nothing written, nothing kept |
| Extras pass 1 GiB | - | the least recently played go at the next sync or download round |
| Free space under 1 GiB | - | extras go first; the keep set stays |
| An entry set to "Ingen" | its copies go at the next sync | its copies and extras go; the playing item after it is left |

## 3. Server (step 1)

- `migrations/0073_music_mobile_data_split.sql` (§1.2; its header says the number may move at the merge).
- `models.rs` `DevicePolicy`: `music_stream_mobile_data`, `music_download_mobile_data` replace `music_mobile_data`.
- `music.rs`:
  - `MusicPolicy { library_version, mobile_data, stream_mobile_data, download_mobile_data, volume_cap_pct,
    storytel_generation }` (field order = JSON order); `policy_music` fills them via pure
    `mobile_data_wire(stream, download) -> (bool, bool, bool)` (`mobile_data` = both).
  - `MusicState.extra_bytes: Option<i64>` (after `cache_bytes`), clamped in `sanitize_music_state` like `cache_bytes`.
  - pure `older_phone_note(stream, download, state: Option<&MusicState>) -> Option<&'static str>` (§1.3).
- `handlers/music.rs`: `MusicCard.stream_mobile_data`, `.download_mobile_data`, `.data_note`; the stored-bytes line
  (§1.10); `save_device_settings` reads `stream_mobile_data` and `download_mobile_data` (a missing checkbox is off),
  one `UPDATE`, nudge, event `music_phone_settings` "device {id}: streaming on mobile data {s}, downloads on mobile
  data {d}, volume cap ..., Storytel ...", redirect `#music-settings` (unchanged; `scroll-restore.js` keeps the place
  - the user rule: a save never jumps to the top).
- `templates/device_detail.html`, in `#music-settings` where the one checkbox is, two auto-saving checkboxes:
  - "**Streaming may use mobile data**: something that isn't downloaded can play off Wi-Fi too. What plays is saved on
    the phone as it plays, so it isn't fetched twice."
  - "**Downloads may use mobile data**: the offline episodes and Storytel books also download off Wi-Fi - a Storytel
    book is often several hundred MB."
  - a subtitle under both: "Never while roaming. On Wi-Fi both always work, and what's downloaded plays anywhere.";
    then `data_note` when set.
- `templates/music_entry.html`: "Downloads wait for Wi-Fi unless the phone's 'Downloads may use mobile data' is on
  (its Music card)."
- `testdata/music_state.json`: `"extra_bytes": 23456789` after `cache_bytes`. Same commit: the launcher's
  `musicState` key list gains `extra_bytes`; the music app's `stateReport` gains `extraBytes: Long` (`put` always;
  `Sync.writeReport` passes 0 until step 3b).

## 4. Launcher (step 2)

- `music/MusicPlan.kt`:
  - `MusicRestrictions.STREAM_MOBILE_DATA = "stream_mobile_data"`, `DOWNLOAD_MOBILE_DATA = "download_mobile_data"`;
  - `MusicPolicy(libraryVersion, streamMobileData, downloadMobileData, volumeCapPct, storytelGeneration)`;
  - `musicPolicy`: `mobile_data` still required; each new key Boolean -> used, absent -> `mobile_data`, else null;
  - `restrictionsFor`: `mobile_data` = both, then the two keys;
  - `artToFetch(..., downloadMobileData)` (the parameter renamed; the rule is the same).
- `music/MusicSync.kt`: `mobileData()` -> `downloadMobileData()`; `artFetchable` passes the downloads switch.
- `music/MusicFileDownloads.kt`: the gate reads `MusicSync.downloadMobileData()`.
- `test/resources/bridge_contract.json` and `music/app/src/test/resources/bridge_contract.json`: `restrictions` gains
  `"stream_mobile_data": "boolean", "download_mobile_data": "boolean"`; the same commit adds the two constants to the
  music app's `RestrictionKeys` and to `BridgeTest`'s key list (reading them is step 3a).
- `PolicyResponseCompatTest`: today's music shape with the three keys.

## 5. Music app (steps 3a and 3b)

### 5.1 Pure (`logic/`), with their tests

- `bridge/BridgeConfig.kt`: `BridgeConfig.streamMobileData`, `.downloadMobileData` replace `mobileData`; `bridgeConfig`
  reads each new key when present, else `mobile_data` (pure `mobileSwitch(values, key)`).
- `logic/NetGate.kt`: `streamAllowed(caps, streamMobileData)`; `downloadAllowed(caps, downloadMobileData)` (the same
  formula); `downloadWaiting(caps, downloadMobileData, storageLow)` uses `downloadAllowed`; `playability(...,
  streamMobileData)`. The header names both switches.
- `logic/Offline.kt`:
  - `EXTRAS_MAX_BYTES = 1L shl 30`, `EXTRAS_FREE_FLOOR = 1L shl 30`;
  - `cacheThrough(entry, ownFile, copyOnly, freeBytes): Boolean` (§1.4);
  - `staleThrough(done: Boolean, rowUrl: String, url: String?): Boolean` = a not-done row from another url (§1.6);
  - `data class Extra(entryId, itemKey, bytes, playedAt)`; `extrasToEvict(extras, held: ItemRef?, freeBytes,
    freeFloor, maxBytes = EXTRAS_MAX_BYTES): List<Extra>` (§1.8);
  - `extrasFloor(wantsStorytel: Boolean): Long`.
- `sync/DownloadPlan.kt` (pure): `downloadPlan(snap, rows, nowMs, held: ItemRef? = null, busy: ItemRef? = null)`;
  `DownloadPlan` gains `extras: List<DownloadRow>`; `prune` skips `held`; `wanted` skips `busy`; `wantsStorytel`.
- `logic/Report.kt`: `stateReport(..., extraBytes)` (step 1 added the parameter).

### 5.2 Glue

- `data/Store.kt`: `streamMobileData`, `downloadMobileData` (live from the managed config; unmanaged, the debug feed's
  prefs). `debugfeed/DebugFeedReceiver.kt`: `config.json` `stream_mobile_data`, `download_mobile_data`; a bare
  `mobile_data` sets both (old scripts).
- Callers: `PlaybackService.playable`/`playableFrom`/`onError` -> the streaming switch; `Downloads.enqueue`,
  `DownloadWorker`, `Sync` (`enqueue`, `downloadWaiting`) -> the downloads switch.
- `data/MusicDb.kt`: `DownloadRow.playedAt: Long? = null`; Room **4** (design 29 makes 3), `AutoMigration(3, 4)`,
  `schemas/.../4.json`. DAO: `markPlayed(row, at)` (`@Transaction`: `INSERT OR IGNORE` + `UPDATE ... SET playedAt`);
  `upsertDownload` becomes a `@Transaction` that keeps the stored `playedAt` (the job's rows don't carry it, and a
  read-then-replace would race the player).
- `media/MediaCache.kt`: `playbackFactory(..., cacheThrough)` keeps Media3's default sink when true (else `null`, as
  today); `copyOnlyFactory(context, keys)`; `bytesByItem(): Map<String, Long>` (one pass, HLS keys grouped by
  `hlsKeyPrefix`). The header loses "streams aren't cached".
- `media/ItemMediaSourceFactory.kt`: `ItemTag.cacheThrough`.
- `playback/LocalRenderer.kt`: the track selection of §1.7.
- `playback/PlayerHub.kt`: `loadedLocal: ItemRef?` (set by the service: the local renderer's current item, null on a
  room or with nothing loaded).
- `playback/PlaybackService.kt`:
  - `mediaItem`: `ItemTag(..., cacheThrough = cacheThrough(entry, ..., Downloads.freeBytes(this)))` (free bytes read
    once per queue build, off the main thread);
  - `activeFor`: `staleThrough` rows deleted with their bytes before the queue loads;
  - `startedPlaying` (local renderer only): `markPlayed` - for a cache-through stream a new row, for a copy
    `playedAt` only; nothing for an own file, a Storytel book or depth 0;
  - when `loadedLocal` changes away from a cache-through item: `CacheThrough.settle(previous)`.
- `sync/CacheThrough.kt` (new): `settle(context, entryId, itemKey)` = copy-only `Downloads.fetch` (§1.5) -> `done`
  row, else `Downloads.enqueue(downloadMobileData)` (the job re-plans; an unwanted item costs nothing);
  `catchUp(context, held)` for the sync; `evict(context, plan, held)` = `extrasToEvict` + `MediaCache.remove` + row
  delete, returning the extras' remaining bytes.
- `sync/Downloads.kt`: `fetch(..., copyOnly = false)`; the worker evicts each planning round before fetching
  (`extrasFloor(plan.wantsStorytel)`) and plans with `held`/`busy` from `PlayerHub`.
- `sync/Sync.kt`: after the listings: `catchUp`, the plan with `held`, the prune (skipping `held`), `evict`; the report
  passes `extra_bytes`.

**Design 29 coordination:** step 3b builds on design 29 merged into main: Room 3 -> 4, and design 29's `Sync.kt`
(its two sync phases, `Sync.runLock`) and `Downloads.kt` (the notifier after each finished tray item) are the base.
Nothing in design 29's rules changes: a settled stream becomes a `done` row like a download, so a Storytel "can play"
is untouched (never streamed), and an NRK/RSS item's eligibility is its url, as before. If design 29 isn't on main
when 3b starts, the orchestrator decides the base; Room's version is 29's + 1 either way.

## 6. Tests (by name)

Server (`cargo test`):
- `tests/device_api.rs` `policy_json_keys_snapshot`: `music` = `{library_version: null, mobile_data: false,
  stream_mobile_data: false, download_mobile_data: false, volume_cap_pct: null, storytel_generation: 0}`.
- `tests/music.rs`:
  - `the_policy_carries_a_small_music_object` (updated: the two fields save alone; a missing checkbox is off);
  - `mobile_data_on_the_wire_is_both_switches` (on/off, off/on, on/on, off/off -> `mobile_data`);
  - `migration_0073_gives_both_switches_the_old_value` (rows on and off before it; `music_mobile_data` gone);
  - `the_card_shows_two_switches_and_saves_back_to_music_settings` (labels, `onchange` submit, redirect anchor);
  - `the_card_says_an_older_phone_uses_mobile_data_only_with_both` (differing switches, report without `extra_bytes`;
    none when equal, when the report has it, or with no report);
  - `the_card_shows_the_played_extras_in_the_stored_line` (`extra_bytes` 0 -> the old line).
- `music.rs` unit: `music_state_keeps_every_field_the_phone_sends` (the fixture with `extra_bytes`),
  `music_state_clamps_extra_bytes`.

Launcher (`testDebugUnitTest`):
- `MusicPlanTest`: `the policy's music object - two switches, an older server's mobile_data for both, a malformed
  switch keeps what is there`; `the managed config carries both switches and mobile_data as both`; `later pictures
  follow the downloads switch`; `the managed config keys are the shared contract's` (existing, with the two keys);
  `music_state passes extra_bytes` (the fixture test, existing).
- `PolicyResponseCompatTest`: `music never fails the policy and is carried as is (21)` (today's shape, six keys).
- `MusicFileDownloadsTest`: `own files follow the downloads switch`.

Music app (`testDebugUnitTest -PwarningsAsErrors=true`):
- `BridgeTest`: `the managed config the launcher writes` (both keys); `an older launcher's mobile_data sets both
  switches`; the contract test (existing, with the two constants).
- `NetTest`: `streaming follows the streaming switch only`; `downloads follow the downloads switch only`; `neither
  while roaming, both on Wi-Fi`.
- `OfflineTest`:
  - `cache-through only above depth 0, never an own file or a Storytel book, never under the free floor`;
  - `a stale stream partial is one from another url and not done`;
  - `extras go least recently played first until under the cap`;
  - `extras go until the free floor holds, or none is left`;
  - `the held item is never evicted, even when it is the oldest`;
  - `the keep set is never in the extras`;
  - `the Storytel floor applies while a book is wanted`.
- `OfflineTest` (plan): `a played item outside the keep set is an extra, an unplayed one is pruned`; `depth 0 has no
  extras - every row is pruned but the held item`; `an item the listing dropped is pruned even when played`; `a
  removed entry takes its extras`; `an entry without a listing keeps its extras`; `the busy item isn't wanted, the
  held item isn't pruned`; `a settled stream counts as have and isn't wanted`.
- `ReportTest`: `extra_bytes is always in the report` (the key set = the fixture's, existing).
- `GuardsTest` / `SourceScanTest`: `the stream switch is read live from the managed config` (the existing
  `mobile_data` pin, renamed); `playback writes the cache only through ItemTag.cacheThrough`; `the downloader never
  runs with the player's held item`.

## 7. Steps (each one implementer sitting; server -> launcher -> music)

1. **Server**, plus the `extra_bytes` contract commit (fixture, `MusicState`, the launcher's key list, the music
   report's parameter - one commit). `cargo test`, `fmt`, `clippy`; launcher and music tests for the contract.
   Docs: server/CLAUDE.md (Vibb music: data, PWA, wire).
2. **Launcher**: §4, with the contract files and the music app's two constants in one commit. Docs:
   launcher/CLAUDE.md (`MusicSync`, `wantArt`/`syncArt`, `MusicFileDownloads`).
3. **Music**, in two sittings:
   - **3a, the switches**: §5.1's `BridgeConfig` and `NetGate`, `Store`, the callers, the debug feed.
   - **3b, cache-through and extras**: everything else in §5 (needs design 29 on main, §5.2). Docs: music/CLAUDE.md
     (the rules "Playback never waits", "Prune only on a parsed listing", the media and sync sections), design 21
     §4.5 (a pointer here), emulator.md §6f (`config.json`'s keys, §8's checks), PLAN.md, this doc's status.

The order is the dependency order: the server sends the keys, the launcher carries them, the app reads them; every
step works against the others' old versions (§1.3).

## 8. Device checks (emulator, docs/testing/emulator.md §6f with the launcher's bridge; §6i for Storytel)

Network: Wi-Fi is unmetered through the launcher's VPN (design 13); `adb shell svc wifi disable` leaves the emulated
LTE (metered); `adb emu gsm data roaming` / `adb emu gsm data home` for roaming. Cache size: `adb shell run-as
me.vibb.music.debug du -s no_backup/media`; the report: `run-as ... cat files/music/report.json`.

1. **PWA:** the Music card shows the two switches; toggling each saves and the page stays at the card (no jump); the
   event log names both; `curl` the policy: `mobile_data` true only with both on. A server with the old switch on
   comes up with both on after the upgrade.
2. **Streaming switch only** (S on, D off), Wi-Fi off: a not-downloaded episode plays and `du` grows while it plays;
   `report.json` `downloads[].waiting` = `wifi`; no download runs (`adb logcat -s WM-WorkerWrapper`).
3. **Downloads switch only** (S off, D on), Wi-Fi off: the episode says "Ikke lastet ned - trenger Wi-Fi" and the
   queue moves on; downloads run (and with §6i's fake Storytel, a book downloads).
4. **Roaming**, both on: nothing streams or downloads; copies play.
5. **Offline at once:** depth 5, play episode 6 (not downloaded) to its end on Wi-Fi; airplane mode: it plays, its
   list shows it on the phone; `extra_bytes` > 0 and the device card's stored line says so.
6. **Not twice:** S on, D off, on mobile data: stream half of a not-downloaded keep-set episode, skip to the next.
   Wi-Fi on: the job completes it, and the app's Wi-Fi traffic for it (`adb shell dumpsys netstats detail`, the
   app's uid) is about the second half; after it, `downloads[].have` counts it.
7. **Depth 0:** set an entry to "Ingen", stream an episode: `du` doesn't grow; any old copies are gone after the next
   sync, the one playing after it is left.
8. **HLS:** an NRK serie episode streamed to the end plays in airplane mode with the copy's rendition.
9. **Eviction:** with a few extras on the phone, fill the emulator's storage to under 1 GiB free (a large file
   pushed to `/sdcard/Download`) and sync: the least recently played extras go first, the keep set and the playing
   item stay; `extra_bytes` drops.
10. **Older phone:** the new server with the launcher from `main` (or the music app from `main`): with only streaming
    on, nothing streams off Wi-Fi, and the card shows the older-phone note.

## 9. Defaults chosen (veto if wrong)

- Both switches take the old value; the old wire key is "both" (AND), never OR.
- `later` pictures and the launcher's own-file downloads follow the **downloads** switch.
- Played extras include a downloaded copy that was played and left the keep set, not only streams.
- `EXTRAS_MAX_BYTES` 1 GiB, `EXTRAS_FREE_FLOOR` 1 GiB (1.5 GB while a Storytel book is wanted); no age limit; no
  parent setting for either.
- A partial extra is kept (it saves bytes on a replay) but doesn't play offline.
- The phone's player is pinned to the download's HLS rendition (no adaptive bitrate).
- A paused progressive stream isn't completed in the background until the player lets go of it (§1.6).
