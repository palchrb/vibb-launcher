# 117 - Storytel books sealed on the phone

Status: design, revision 1, 2026-11-18. Approved item: PLAN.md, "Storytel books encrypted at rest on the phone"
(approved 2026-11-18). The user said "Tenkte mest ift et sånn at an ikke lager en downloader-tankegang", and then
"Ja takk. Det er potensielt bedre enn det storytel selv gjør i dag..".

Music app only. Nothing changes in the API, the bridge, the policy, the report, the server or the PWA. Paths: `M/` =
`music/app/src/main/java/me/vibb/music/`. This was read against `main` at b96e0a3d7, with Media3 1.7.1 (the class
names below were checked in its `media3-datasource` jar).

**Inputs:** root `CLAUDE.md`; `music/CLAUDE.md` ("A stream is written to the cache as it plays", the cache and prune
rules); `docs/notes/music/storytel.md`; design 26b §3.3, §4.3-§4.4; design 58 (the mint, unchanged here); design 30
§1.4-§1.8 (the cache, the held set, the sweep); design r10 (the launcher's Keystore wrap: the pattern and the lessons
from a lost key).

## 0. The shape

Today a Storytel book is the CDN's plain mp3 in the app's one Media3 `SimpleCache`
(`noBackupFilesDir/media/*.exo`, 5 MiB span files). With this design every Storytel book is **sealed**:

- **One Keystore key, `music_books_v1`.** It is AES-256 with GCM. It is not exportable, it lives in StrongBox
  where the phone has one and in the TEE otherwise, and it needs no user authentication.
- **A data key per book.** Each book gets its own random AES-256 key. That key is stored only in wrapped form, sealed
  with GCM by the Keystore key, in a 74-byte **seal record** kept in the cache's content metadata for that book.
- **AES-256-CTR at the byte's own position.** The counter for each byte comes from where the byte sits in the book.
  The sealed bytes are the same length and at the same offsets as the plain ones. So the Range resume, `cachedBytes`,
  the storage limit, the length guard and the `SimpleCache` itself work unchanged.
- **Sealing starts at the first byte.** Media3's cache sink receives only sealed bytes, so no plain byte of a book
  reaches the disk. This holds for a partial download too.
- **Decryption happens only while reading.** The player's cache-read source decrypts in memory. The download job
  reads nothing back.
- **A sealed book has its own cache key.** A sealed book is stored under `sealed:<entry key>:<cid>`. A book stored
  under the old key is plain. The one-time migration copies each plain book into its sealed key and checks the copy.
  It then switches the book's download row to the sealed key in one Room write, and only after that removes the plain
  copy.

**What it protects, and what it doesn't.** A copy of the app's files is unreadable without the key. That includes
root, a forensic image, a debuggable build's `run-as` and any future backup slip. Because the key never leaves the
phone's Keystore, the files are useless off the phone. No file on the phone plays outside Vibb, so the phone holds no
mp3s, which was the user's "downloader" concern.

It does **not** protect against code running as our app on the phone, which can ask the Keystore just as we do. Root
on a running phone can do that too. It also does not protect the audio while it plays, or the minted URL in flight:
the minted URL is Storytel's own plain-mp3 link, and we never store it (26b §4.2). r10 drew the same line.

**Storage, not behaviour.** The music app follows the Vibb Pi player (palchrb/vibb), which keeps a book as a plain
mp3 per cid in its shelf directory. This design changes only how the phone stores the bytes. Which books are kept,
when they download, how they play and resume, and what the screens say all stay vibb's. It joins 26b §9's deviation
list (step 2 adds the row) with this reason: the vibb box is the family's own machine, while the phone is the kid's
pocket device.

**What doesn't change:**
- podcasts, NRK and radio, which keep their plain cache and keys;
- Sonos, which plays the minted URL with nothing from the phone;
- the server, which never fetches audio (it still doesn't);
- the report (§9), the PWA, and the kid's screens: no new words, nothing new on the kid's screen.

## 1. What the code has today

- `M/media/MediaCache.kt`: there is one `SimpleCache(noBackupFilesDir/media, NoOpCacheEvictor,
  StandaloneDatabaseProvider)`.
  - `downloadFactory` is a `CacheDataSource.Factory` with the http upstream, the default `CacheDataSink` and the
    default `FileDataSource` for reads. For Storytel it uses the no-redirect clients.
  - `playbackFactory(copyOnly = true)` is the Storytel playback path: no upstream, no sink, flags 0.
  - The OkHttp clients have no disk cache.
- `M/sync/Downloads.kt` `fetch`: for Storytel this is a `ProgressiveDownloader` with `customCacheKey =
  itemCacheKey(entry.key, cid)`. Media3's `CacheWriter` fetches only the gaps, so a partial resumes with Range against
  a fresh mint.
- `M/sync/DownloadRun.kt`: one book at a time, the 1.5 GB floor (`STORYTEL_FREE_FLOOR`), and the row named by
  `storytel:book:<cid>`. On `http_416` it removes the copy and fetches once from 0.
- `M/playback/PlaybackService.kt:1640-1646`: a book's `MediaItem` is `storytel:book:<cid>` with
  `ItemTag(cacheKey, copyOnly = true)`, and the cache key is computed with `itemCacheKey`.
- `M/sync/CacheThrough.kt`: `remove`/`removeCopy`/`evict`/`sweepOrphans`, each re-reading the held set. The sweep
  removes every resource key that no row names (`logic/Offline.kt:239 orphanResources`).
- Backup and device transfer exclude every domain (`res/xml/data_extraction_rules.xml`), so neither the cache nor a
  Keystore key ever travels.

## 2. The key

### 2.1 The Keystore key (the KEK)

```kotlin
KeyGenParameterSpec.Builder("music_books_v1", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setKeySize(256).setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
    .setIsStrongBoxBacked(strongBox)   // see below
    .build()   // the default randomized IV; no user auth; no setUnlockedDeviceRequired
```

- **StrongBox where present, the TEE otherwise.** We try StrongBox only when
  `PackageManager.hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` is true. A `StrongBoxUnavailableException` falls back
  to the TEE, the same spec without the flag.
  - This differs from r10, where the user chose TEE only. The reason there was a secure-element round trip on the main
    thread for the kid's PIN. Here the key is used once per book per process, off the main thread (§2.3), so a 50-200
    ms StrongBox unwrap at a book's first open costs nothing the kid notices.
  - The Jelly Star is a MediaTek phone and probably has no StrongBox. The device check reads the feature flag.
- **Never a key in the app's own files or prefs.** A Keystore key at software level is accepted only because no
  Android 14+ CTS phone has one: KeyMint in a TEE is mandatory. We log it once, and that is all. Refusing it would
  only mean no Storytel on such a phone, with no safety gained over the app's private directory.
- **No user authentication and no `setUnlockedDeviceRequired`.** Books play with the screen locked and in the
  background, and the download job runs while the phone is locked. Such a key also survives a change of the phone's
  PIN, as r10 §1 notes.
- **Made only when it is missing.** We call `KeyStore.containsAlias`, and the check runs under one process-wide lock.
  The key is made only on the path that seals a new book, never on a read. **The key is never remade because an unwrap
  failed**: that would destroy every book still readable.

### 2.2 Why a data key per book, not the Keystore key itself

Every Keystore operation goes over binder to keystore2 and on into the TEE or the secure element. Bulk AES that way
runs at a few MB/s in a TEE and much less in StrongBox. It also holds one of KeyMint's few operation slots for as long
as a book plays or downloads, and a slot pruned under pressure would stop playback.

Keystore CTR with our own IV would also need `setRandomizedEncryptionRequired(false)`. Android 17 caps the number of
keys an app may own (50,000 when targeting 37), which rules out a Keystore key per book as well. Envelope encryption
avoids all of this:
- the Keystore key wraps 32 bytes once per book;
- the bulk work is Conscrypt's AES-CTR in the app's memory, using the CPU's AES instructions (§10).

This is the pattern Jetpack Security's `EncryptedFile` used, with a Tink keyset wrapped by a Keystore master key.
That library is deprecated, and its chunked format would break the offset identity (§17).

### 2.3 The data key's life

- **Made** with `KeyGenerator.getInstance("AES").init(256)`, using the platform `SecureRandom`, when a sealed key
  has no span (§4.3).
- **Wrapped** at once: `Cipher("AES/GCM/NoPadding")` with the Keystore key, and the Keystore picks the 12-byte IV.
  The AAD is §3's header plus the cache key.
- **Kept on disk** only inside the seal record (§3).
- **In memory**, a memo holds up to 4 unwrapped keys (an LRU), keyed by the sealed cache key. It is filled at a
  book's first open by the player or the job, on the loader thread or the worker, never the main thread. It is
  cleared for a key when the book is removed or its seal is found lost.
  - The JVM can't promise to zero a `SecretKeySpec`, so this is best effort. Anyone who can read our memory is our
    uid or root anyway.
  - The holder prints redacted (`toString()`), and `SourceScanTest` pins that, as with the Storytel secrets.

## 3. The format

### 3.1 The seal record (the "header")

The record is stored as the resource's Media3 `ContentMetadata` value `vibb_seal` (bytes). It goes into
`exoplayer_internal.db` beside the `exo_len` that Media3 keeps there. It is written with
`applyContentMetadataMutations`, which stores the index synchronously.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `VBSL` |
| 4 | 1 | version `1` |
| 5 | 1 | suite `1` = AES-256-CTR data, its key wrapped by AES-256-GCM under `music_books_v1` |
| 6 | 8 | CTR nonce (random, `SecureRandom`) |
| 14 | 12 | the wrap's GCM IV (the Keystore's) |
| 26 | 48 | the wrapped data key (32 bytes + a 16-byte tag) |

That makes 74 bytes. The **GCM AAD** is bytes 0-13 followed by the UTF-8 sealed cache key. This means a record
copied onto another book, or a changed nonce, fails the unwrap and is never used.

An unknown magic, version or suite counts as unreadable, which is the lost path in §7. A later version is read only
by a later build. A downgrade is not a supported path: `adb install -d` on the emulator leaves the books unplayable
until they are re-downloaded.

### 3.2 The cipher: AES-256-CTR, counter = position / 16

- The IV of the 16-byte block holding byte `p` is `nonce (8 bytes) || uint64_be(p / 16)`.
- A read or write that starts at `p` initialises the cipher at block `p / 16` and drops `p % 16` bytes of keystream.
- `M/logic/Seal.kt`'s `SealCipher` (our own, about 50 lines, javax.crypto `AES/CTR/NoPadding`) handles any start
  and any length, carrying the partial block across calls.
- Media3 has `AesFlushingCipher`, which does the same with an FNV hash of the key as the nonce. We don't use it: it is
  `@UnstableApi`, and a Media3 bump that changed its IV layout would silently make every book unreadable. Our own
  cipher is pinned by `seal_vectors.json` (§13).
- **The nonce is unique per data key, and the data key per seal.** Keystream is never reused:
  - `SimpleCache` never writes a range of a key twice (a writer locks a hole, and a cached range is never rewritten);
  - a book that starts over gets a new data key (§4.3);
  - the random nonce is a second guard should a bug ever reuse a data key.

### 3.3 Why CTR with no per-chunk tags and no whole-file MAC

There are no chunks: CTR's unit is the 16-byte block, so seeking and resuming work at any byte. The choice was made
for seeking and resuming:

- **Offsets stay identical.** The cache's position `p` holds the sealed form of the book's byte `p`, which is the
  byte the CDN's Range request returns at `p`. That keeps everything design 26b, 30 and 58 built on positions:
  - the resume (`CacheWriter` fills holes at arbitrary offsets: wherever a connection dropped);
  - `cachedBytes`, `grewEnough`, `audio_bytes` and the storage limit;
  - the `LengthGuard`'s stored length;
  - `SimpleCache`'s own spans.

  Per-chunk GCM (say 64 KiB chunks with 16-byte tags) would move every offset. A resume in the middle of a chunk would
  need to read, modify and rewrite the last chunk, against a cache that never rewrites a range. And every
  length-based rule would need a translation.
- **What a tag would buy is small here.** A tag detects tampering. But only our uid or root can write
  `noBackupFilesDir`, and both can call the Keystore anyway. Bit rot or a torn write behaves as it does today: the mp3
  decoder skips the bad frame or the item fails. Today's plain copies have no check either, so there is no loss.
- **A wrong or lost key is caught anyway, and loudly.** The record's GCM unwrap authenticates the data key before a
  single byte is decrypted. CTR under a wrong key would give noise; it never gets that far.
- **No whole-file MAC is stored.** It could only be checked by reading the whole book, so it couldn't guard a seek.
  The one place a full check matters is the migration, before a plain copy goes, and there we compare the two copies
  directly (§8.2). Nothing derived from the plaintext, such as a hash of the mp3, is ever stored: it would identify the
  book.

## 4. Where the seal lives in the cache

### 4.1 The sealed cache key

- `logic/Seal.kt`:
  - `sealedCacheKey(entryKey, cid) = "sealed:" + itemCacheKey(entryKey, cid)`;
  - `isSealedKey(key) = key.startsWith("sealed:")`;
  - `sealStagingKeys(rows)`: for every Storytel row (`url` starts `storytel:book:`) whose `cacheKey` isn't sealed,
    its `sealed:` twin (§8).
- **A sealed key is never read plain, and a plain Storytel key is never written again.** Whether a book is sealed
  follows from its key, not from metadata that could be missing. A sealed key without a readable record is a lost
  book (§7), never a plain read.
- NRK, RSS and HLS keys are unchanged. `itemOfResource` and `hlsKeyPrefix` see a `sealed:` key as a progressive key
  (it doesn't start with `hls:`).

### 4.2 The sink and the source (`M/media/SealStreams.kt`, about 120 lines)

- **`SealDataSink(inner: CacheDataSink, keys: BookKeys)`**, the download's cache sink:
  - `open(spec)` takes the memo's data key for `spec.key`, which must be a sealed key with a readable record (§4.3).
    It sets the cipher at `spec.uriPositionOffset + spec.position`, then calls `inner.open(spec)`.
  - `write` encrypts into its own 64 KiB scratch buffer (looping) and writes the scratch. Never in place: the
    `TeeDataSource` hands the same buffer to the reader.
  - The cipher state runs continuously across the `CacheDataSink`'s 5 MiB file splits.
  - No record, or a key that isn't sealed, throws an `IOException`. That can only happen through a bug, since §4.3
    prepares the record first.
- **`SealDataSource(upstream: FileDataSource, keys)`**, the cache-read source:
  - `open(spec)`: Media3 1.7.1's `CacheDataSource.openNextSource` gives a cached span as `uri = the span file`,
    `uriPositionOffset = span.position`, `position = the offset in the file`, with `key` set. The book's position is
    therefore `uriPositionOffset + position`, the same formula as Media3's own `AesCipherDataSource`.
  - `read` reads from the file into the caller's buffer and decrypts in place (the buffer is the reader's own).
- **Factories.** In `MediaCache`:
  - `storytelDownloadFactory(...)` is `downloadFactory` with `setCacheWriteDataSinkFactory { SealDataSink(...) }`
    and `setCacheReadDataSourceFactory { SealDataSource(...) }`;
  - `storytelPlaybackFactory(keys)` is today's copy-only factory with the `SealDataSource` read factory.
  - `ItemMediaSourceFactory` picks it for `copyOnly`. The plain `downloadFactory`/`playbackFactory` keep NRK and RSS.
  - `SourceScanTest`: `Source.STORYTEL` and `copyOnly` reach only the `storytel*` factories.
- **Seeking.** ExoPlayer's seek is a new open at a new position, and the source starts the cipher at that block. The
  mp3 extractor's constant-bitrate or Xing seek works as today, because the plain offsets are the sealed offsets.

### 4.3 Preparing a book's seal (`BookKeys.prepare(sealedKey)`)

The download calls this before `ProgressiveDownloader` starts, on the worker:
1. **No spans:** mint a new data key, wrap it, and write a new record, overwriting any leftover. A book that starts
   over (a first download, the `http_416` refetch after `removeCopy`, the lost path) therefore always gets a new key.
2. **Spans and a readable record:** load it into the memo. This is a resume, and the same key continues.
3. **Spans and an unreadable record:** for a lost record (§7), drop the spans (`removeCopy`, which re-reads the held
   set) and go to 1. For a transient failure, throw: the attempt fails with `network` and `mayRetry` waits as for any
   other transient failure.

### 4.4 The resume: the HTTP Range offset is the sealed offset

`CacheWriter` asks the CDN for `Range: bytes=p-`, where `p` is the end of what is cached. The answer's first byte is
the book's byte `p`. The sink seals it at counter `p / 16` (dropping `p % 16`) and the `CacheDataSink` writes it at
`p`. No offset maps to another anywhere.

The `LengthGuard` compares the 206's total with `exo_len`, which is unchanged, so a changed book is still a 416. The
rule "a partial survives a re-mint" (26b §4.3) holds as it is.

## 5. The download path

- `DownloadRun.fetchOne` for a Storytel book:
  - its cache key is `sealedCacheKey(...)`;
  - **a row whose `cacheKey` is the plain one is skipped this run**: no error, no row write. The seal job (§8) moves
    it first and starts the downloads when it is done.
  - Everything else is unchanged: the floor, the mint, the 416 refetch with a fresh mint, the failures.
- `Downloads.fetch` for Storytel: `prepare` (§4.3), then `ProgressiveDownloader` with `customCacheKey = sealedKey` on
  `storytelDownloadFactory`.
- `WorkerIo` and `DownloadIo` gain nothing. `prepare` sits inside `fetch`, behind the seam that `StorytelDownloadTest`
  already fakes.

## 6. The playback path

- `PlaybackService.mediaItem` for a Storytel book uses **the row's** `cacheKey` (`a.downloads[item.key]?.cacheKey`).
  It no longer calls `itemCacheKey`. A book plays only with its done row (26b §4.4); with no row it starts nothing, as
  today (`bookNotOnPhone`).
  - During the migration the row still names the plain key, so the book plays plain until the switch.
  - The `SealDataSource` passes a plain Storytel key through unchanged. That happens only until that book's switch.
- A book that is open while its key is lost: `SealDataSource.open` throws `SealLostException` (an `IOException`).
  - `PlayFailure.failureMessage` maps it to `NOT_DOWNLOADED`, the existing "Ikke lastet ned". That is true, since the
    book will be fetched again.
  - The service asks for a sync; §7 drops the row. Nothing new on screen, never a crash.
  - A **transient** Keystore failure at an open is an ordinary item failure, "Kan ikke spilles nå". A tap retries it.

## 7. A lost or broken key: the books download again, never a crash

**When it happens:** a Keystore wipe, or an OEM Keystore fault after an OTA (r10 §5 saw these). Not a backup restore:
nothing travels (§1). Not clearing the app's data or uninstalling: those take the files with the keys. Not a PIN
change: the key has no authentication (§2.1).

**Classification** (`logic/Seal.kt`, pure: `sealFailure(e): Lost | Transient`):
- **Lost:**
  - the alias is missing;
  - `KeyPermanentlyInvalidatedException`;
  - `UnrecoverableKeyException`;
  - `AEADBadTagException` (a record not made by this key, or damaged);
  - a record with an unknown magic or version;
  - `KeyStoreException` with `!isTransientFailure()` and an error code saying the key doesn't exist (API 33 and
    later, so no SDK gate);
  - any other failure that repeats on the same book at least 3 times over at least 24 h (a fault we can't classify
    must not keep a book unreadable forever).
- **Transient:** `KeyStoreException.isTransientFailure()`, a binder or keystore2 failure, and everything else below
  that bar. A transient failure never deletes anything. r10's rule applies: a passing Keystore hiccup must not cost
  the kid a book.

**The check:** `BookKeys.check` runs at every sync, before the downloads are planned, once per process per book. It
unwraps each sealed Storytel row's record (an LRU hit costs nothing). For a lost book:
- its resource goes (`removeCopy`: never while the player holds it, so the next sync tries again);
- its download row goes (`deleteDownload`).

The plan then wants the book again and the job fetches it sealed, under a new data key and, if the alias was missing,
a new Keystore key.

Positions and bookmarks are keyed by entry and item (`music/CLAUDE.md`), so **the book comes back where the kid left
it**. The download follows the parent's switches as any download does: Wi-Fi, or mobile data when the parent allows
it (ASK 1).

## 8. The one-time migration of plain books

### 8.1 The job

`M/sync/SealJob.kt` is a WorkManager unique work, `music-seal`, `ExistingWorkPolicy.KEEP`, with one constraint:
`setRequiresStorageNotLow`. It needs no network, so a phone on mobile data with downloads off still migrates.

- **Enqueued** by the sync whenever `sealStagingKeys(rows)` isn't empty, which includes the first sync after the
  update (`PackageReplacedReceiver` syncs at once).
- **Ends** by enqueuing the downloads (the rows it moved may be partials).
- **Order:** one book at a time, done books in reading order first, then partials. A book the player holds is skipped
  and taken last, or at the next run.
- **No foreground service.** WorkManager's 10-minute stop is safe, because every state below resumes.

### 8.2 One book (plain key `K`, sealed key `S`, its row `R`)

1. **Room:** for a done book, check `free ≥ cachedBytes(K) + STORYTEL_FREE_FLOOR` (one book's second copy above
   the Storytel floor). If there isn't room:
   - a done book stays plain and playable, and is tried again at the next sync (ASK 2);
   - a partial's plain bytes are dropped (`removeCopy(K)`) and its row moved to `S` with `bytes = 0`, so the download
     job fetches it sealed. That costs only data, which is the fetch it was going to do anyway.
2. **The seal:** `prepare(S)` (§4.3). If `S` has spans and a readable record, this is a resume.
3. **Copy:** for each cached span of `K`, in order, copy the part `S` doesn't have yet (`cache.getCachedLength(S, pos,
   len)`). The copy reads `K` through a plain copy-only `CacheDataSource` and writes `S` through a `SealDataSink` over
   a `CacheDataSink`, opened at that position with that length. Holes in a partial stay holes. After the copy, `K`'s
   `exo_len` is copied to `S`, so a partial's resume keeps its length guard.
4. **Verify:**
   - `S`'s cached ranges must equal `K`'s;
   - every range must read back through `SealDataSource` to the same bytes as `K`, compared as a SHA-256 of each side
     in memory in 64 KiB blocks, with nothing stored.

   On a mismatch (a torn tail after a power cut, say), remove `S` and start this book over, at most once per run. A
   second mismatch leaves the book plain and logs it.
5. **Switch:** first re-read the held set, and if the player holds `R` now, stop and try later. Then in one Room
   transaction, if `R` still names `K`, it becomes `R.copy(cacheKey = S)`. If `R` is gone (pruned meanwhile), remove
   `S`.
6. **Remove `K`:** `removeResource(K)`. That is the plain copy going.

### 8.3 Every crash point resumes

| Crash after | What is on disk | What happens next |
|---|---|---|
| 1-2 | `S` has a record and no spans | `SimpleCache` drops an empty content at start; the next run's `prepare` mints again |
| 3 (part) | `S` partly sealed, `R` names `K` | The sweep spares `S` (`sealStagingKeys` joins `orphanResources`' kept keys); the next run copies only what's missing |
| 4 | `S` complete, not yet compared | Compared again (seconds) |
| 5 | `R` names `S`; `K` still there | `K` has no row, so the job's own cleanup removes it, and the sync's sweep is the backstop. Either way it only goes after the switch, which came after the check |

"Plain removed only after the sealed copy verifies" holds in every row.

**"Securely removed"** means deleting the span files. That is the only reliable way on flash, and the phone's own
encryption does the rest:
- Android 14+ phones use file-based encryption, with a per-file key derived from the user's key and a nonce in the
  file's inode, so the deleted span's old blocks are ciphertext the phone can no longer tie to a key once the inode is
  gone.
- An overwrite pass before the delete is not done. On f2fs, which is log-structured and the usual `/data` of MediaTek
  phones, it writes new blocks and leaves the old ones. On any FTL it isn't guaranteed either, and it would cost one
  extra full write of every book.

### 8.4 What the migration does to the other rules

- **The sweep:** `orphanResources(keys, rowKeys + sealStagingKeys(rows), held)`. This is pure, with a new row in
  `OfflineTest`.
- **The storage limit and the extras:** while `S` is being written, the cache temporarily holds the book twice.
  - `CacheThrough.evict`'s total, and the report's `audio_bytes`, subtract the bytes under `sealStagingKeys(rows)`.
    Otherwise the played podcast extras would be evicted for a copy that lasts seconds.
  - The free-space floor still sees the real disk, which is what it is for.
- **The download job:** it skips a plain-key row (§5), and the seal job only touches plain-key rows, so they never
  work on the same book.
- **Prune** of a book that is being migrated: `CacheThrough.remove` goes ahead (`K`'s row and bytes). Then step 5
  finds `R` gone and removes `S`, or the sweep does: `S` is no longer a staging key once its row is gone.

## 9. Free space, the limit and the report

- **Downloads:** a sealed book is exactly as large as the plain one, plus 74 bytes in the index database. Every floor
  and limit is unchanged: 1.5 GB for Storytel, 1 GiB for the extras, the parent's limit.
- **Migration:** one book's second copy at a time, above the Storytel floor (§8.2 step 1).
- **The report and the PWA:** nothing new.
  - `audio_bytes` keeps its meaning (§8.4).
  - No new `item_errors` code: a transient seal failure is `network`, and a lost book is no error. The row goes, and
    the download shows as any download does.
  - A phone that is migrating, or that has a book left plain for lack of room, says nothing. Nothing is wrong that the
    parent can act on: room comes back by itself, or with the storage the PWA already reports.
  - The debug log line in `SealJob` gives counts only ("sealed 3, plain 1, skipped held 1"). Never a key, and never a
    record's bytes.

## 10. Performance on the Jelly Star

The phone has a Helio G99 (2 x Cortex-A76 at 2.2 GHz, 6 x Cortex-A55 at 2.0 GHz), ARMv8.2 with the Cryptography
Extension, and UFS storage. It runs Android 16. Storytel's mp3 is about 64 kbit/s, which is about 29 MB per hour; a
10-hour book is about 290 MB, matching 26b's "300 MB".

- **AES-256-CTR:** BoringSSL's ARMv8 path (`aes_hw_ctr32`) costs roughly 1.5-2 cycles per byte on an A55, which is
  about 1 GB/s on one little core. At 10x worse (JNI per small read, short buffers), 100 MB/s.
- **Playback:** 29 MB per hour of listening is 0.03-0.3 s of one little core per hour, under 0.01 %. ExoPlayer's reads
  are a few hundred bytes to a few KB each, so the JNI call costs more than the AES, and still well under a millisecond
  a second. Battery: nothing measurable.
- **Download:** at 100 Mbit/s Wi-Fi (12.5 MB/s), 1-12 % of one little core while the download runs. The network is
  the limit.
- **Keystore:** one GCM unwrap of 48 bytes per book per process, about 5-30 ms in a MediaTek TEE (50-200 ms in
  StrongBox elsewhere). It runs on the loader thread at a book's first open, which is covered by the play start's own
  buffering.
- **Migration:** per 300 MB book, 1.2 GB of I/O: read `K`, write `S`, read both to compare. On UFS (about 800 MB/s
  read and 250 MB/s write) that is about 3-5 s plus about 1 s of CPU, so 10 books take under two minutes, once. The
  device check measures it.
- **Memory:** a 64 KiB scratch buffer per open sink and up to 4 keys. Nothing else.

## 11. Android 14-17

Checked 2026-11-18 against each version's behaviour changes. Every API used is at API 33 or lower, so there is no
`SDK_INT` gate, and lint's NewApi confirms that.

| Version | What touches us | Verdict |
|---|---|---|
| 14 (34) | keystore2 and KeyMint; AES-256-GCM in the TEE is mandatory; `KeyStoreException.isTransientFailure`/`getNumericErrorCode` (33); `setIsStrongBoxBacked` (28) | fine; the lowest version, gate run |
| 15 (35) | `setUnlockedDeviceRequired` was reworked | not used (§2.1) |
| 16 (36) | `KeyStoreManager` (key grants between apps) | not used; the Jelly Star's version, device check |
| 17 (37) | a per-app cap on Keystore keys (50,000 when targeting 37, 200,000 otherwise; `ERROR_TOO_MANY_KEYS`), [behavior changes: all apps](https://developer.android.com/about/versions/17/behavior-changes-all) | one key in all: the per-book keys are not Keystore keys (§2.2); the highest version, gate run |

Media3's `CacheDataSource`, `CacheDataSink` and `FileDataSource` don't depend on the version. The FBE argument (§8.3)
holds on every version: file-based encryption has been mandatory since Android 10.

## 12. What stays as it is (each said once)

- NRK, RSS and radio: plain cache, the `ThroughSink`, `HttpsFirst` and every key. Own files: their files and their
  SHA-256 in `own/` (no change; these are the parent's own files, not a store's).
- Sonos and Storytel on a room: the minted URL goes to the speaker, and the phone serves nothing.
- The mint, the JWT courier, the token's secrecy (design 58) and the no-redirect CDN client.
- The server, which never fetches audio, and the bridge, the policy, the report and the PWA.
- Positions, bookmarks, keep and prune, the held set, design 30's rules, and the Storytel floor.
- The kid's screens: no new word or state. The only words a sealed book can bring are today's "Ikke lastet ned" and
  "Kan ikke spilles nå" (§6).

## 13. Tests (JVM, no real-looking keys)

The keys are `SHA-256("FAKE-SEAL-KEY-<n>")`, and the nonces and IVs are spelled-out fakes. No Keystore in JVM tests:
`BookKeys` sits behind a `KeyWrap` seam (`wrap(aad, key)`/`unwrap(aad, blob)`). Its test version is software
AES-GCM under a fake-phrase key and can throw on request: lost, transient, or a bad tag.

- **`SealFormatTest`** (`logic/Seal.kt`):
  - a record encodes to exactly 74 bytes, and decoding gives the record back;
  - a wrong magic, version, suite or length is unreadable;
  - the AAD covers the cache key: moving a record to another key fails the unwrap;
  - the cipher at any start and length (random, non-aligned, crossing a 5 MiB split) equals slicing one pass from 0;
  - `seal_vectors.json` (`music/app/src/test/resources/`, the music app's own file, not a shared contract) pins the
    record bytes and the sealed bytes of a fixed plaintext at offsets 0, 15, 16, 17 and 5 MiB-1. A cipher change
    fails here before a single book on a phone does.
- **`SealStreamsTest`** (`media/`):
  - `SealDataSink` and `SealDataSource` with fake inner sinks and sources, and the `DataSpec` built as
    `ThroughSinkTest` builds it, in both of Media3's shapes: an upstream open (`uriPositionOffset 0`) and a span read
    (`uriPositionOffset = span.position`);
  - the sink never writes plain bytes and never changes the caller's buffer;
  - a plain Storytel key passes through the source, and a sealed key without a record throws `SealLostException`.
- **`SealFailureTest`:** every exception to Lost or Transient, including the 3-times-over-24-hours rule.
- **`StorytelDownloadTest` (extended):** a plain-key row is skipped with no write; a 416 makes a new record (new
  nonce); a resume keeps the record.
- **`SealMigrationTest`:**
  - the job's decisions (pure, behind a seam like `DownloadIo`) for room, held, done and partial books;
  - a crash after each step of §8.2, then a fresh run: every row of §8.3, never `K` removed before the switch, never a
    sealed key read plain;
  - a mismatch starts the book over once, then leaves it plain.
- **`OfflineTest`:** `orphanResources` spares the staging keys; the evict total leaves them out.
- **`SourceScanTest`:**
  - `AndroidKeyStore` and `KeyGenParameterSpec` appear only in `media/BookKeys.kt`;
  - `Source.STORYTEL`/`copyOnly` reach only the `storytel*` factories;
  - the key holders' `toString()` are redacted;
  - no `Log.` names a record or key.

## 14. The emulator gate and the device checks

**Emulator:** this waits for the user's OK, under the rule "Emulator: ask first". It runs on API 34 and API 37,
because the Keystore varies by version, using the images from 2026-11-16. Debug build, `scripts/fake-storytel.py
--books 111:20,222:5`, `docs/testing/emulator.md` §6i, with a new §6ba "Sealed books":
1. Download both books, then `run-as me.vibb.music.debug` and list `no_backup/media`. No span starts with `RIFF` (the
   fake's WAV) or `ID3`, and none of them is accepted by `ffprobe` on the VM.
2. Play, seek to the middle and to the last minute, lock the screen, and wait out a 10-minute background play.
3. `--drop-at` cuts a download partway; the next job resumes it with Range, and the pulled spans are still sealed.
4. **Upgrade:** install `main`'s debug build from before 117, download both books (plain), install this build, and
   sync. The seal job moves them; the books play throughout; afterwards no plain key remains.
5. **Killed during the migration:** `am kill` mid-copy and between the switch and the removal. The next run finishes
   it, as in §8.3.
6. **Lost key:** a debug-only receiver deletes `music_books_v1`, which is the only way to simulate a wipe. Playing
   shows "Ikke lastet ned"; the sync drops both rows and the job downloads them again, and each book resumes at its
   bookmark. A debug-only receiver is allowed in `src/debug` (like `DebugStorytel`), never in a release:
   `SourceScanTest`.

**Jelly Star**, after the release:
- whether it has StrongBox (`pm has-feature android.hardware.strongbox_keystore`);
- the key's security level, logged once;
- the migration's time per book, measured on the family's real books (the user's phone, the release that brings
  this);
- a 10-hour book played and sought.

## 15. Steps (one branch `music-117-seal`, one music release with both steps)

| Step | What | Size |
|---|---|---|
| 1 | `logic/Seal.kt` (record, cipher, keys, failure classes); `media/BookKeys.kt` (Keystore key, wrap, memo, `prepare`, `check`); `media/SealStreams.kt`; the `storytel*` factories; `Downloads.fetch`, `DownloadRun` (sealed key, plain rows skipped); `PlaybackService` reading the row's key; `PlayFailure`'s lost mapping; `BookKeys.check` in the sync; tests §13 except migration | M: about 450 lines of code and 400 of tests |
| 2 | `sync/SealJob.kt` and its pure decisions; `sealStagingKeys` in the sweep, the evict total and `audio_bytes`; the sync enqueues it; the migration tests | M-: about 300 + 300 |
| 3 | Notes: `music/CLAUDE.md` (the cache paragraph: a Storytel book is sealed); `docs/notes/music/storytel.md` (a new "Sealed books" rule); 26b §9's deviation row; `docs/testing/emulator.md` §6ba. Then the emulator gate on 34 and 37 once the user says yes | S |

Step 1 alone already seals every new book, but the old plain ones would wait. Both steps ship in one `music-v*`.

## 16. ASKs (only what the user would notice)

1. **A lost key means the books download again by themselves.** This is rare (a Keystore wipe or an OEM fault). Each
   book comes back at its bookmark over the parent's usual download rules: Wi-Fi, or mobile data if "Download on
   mobile data" is on. Until then the book shows "Ikke lastet ned". The parent is not told.
   **Recommendation: yes, as described.** Nothing is wrong that the parent can act on, and the existing download
   rules already govern the data.
2. **A plain book with no room to re-seal stays plain until there is room.** Re-sealing needs one book's second copy
   above the 1.5 GB floor. On a nearly full phone a book already downloaded would stay as it is today, playable,
   until space frees up. The alternative is to delete it at once and download it sealed when there is room, which
   leaves the kid without the book meanwhile.
   **Recommendation: keep it plain until there is room.** The kid keeps the book, and the case needs a phone with
   less than about 1.8 GB free.

## 17. Considered and not chosen

- **A file format of our own outside `SimpleCache`** (a header, then 64 KiB GCM chunks). It would have a real header
  and real tags. But it would replace `ProgressiveDownloader`/`CacheWriter`'s resume, `cachedBytes`, the sweep, the
  eviction, the length guard and the copy-only player path, all of which design 30 hardened, and every offset would
  need translating (§3.3).
- **The same key for sealed and plain books, swapping in place.** `SimpleCache` has no rename. Copying back through a
  staging file doubles the writes and leaves a window where the book can't play. A span-by-span in-place re-seal
  needs a "sealed up to here" marker, and a crash between the write and the marker would encrypt a sealed span twice,
  which under CTR turns it back into plain bytes.
- **Media3's `AesCipherDataSink`/`AesCipherDataSource` as they are.** They take the raw key in memory, which is fine
  for the data key, but their nonce is an FNV hash of the cache key and `AesFlushingCipher` is `@UnstableApi` (§3.2).
- **Keystore AES-CTR for the bulk work**, and **a Keystore key per book** (§2.2).
- **Tink / Jetpack Security `EncryptedFile`:** a new dependency (and its verification metadata), deprecated, and
  chunked (§2.2).
- **Dropping the plain books and downloading them again** instead of re-sealing: 300 MB per book of data, and the
  books gone until Wi-Fi. The local re-seal costs seconds of I/O.
