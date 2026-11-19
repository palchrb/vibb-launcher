# 117 - Storytel books sealed on the phone

Status: design, revision 2, 2026-11-18.
- **Approved item:** PLAN.md, "Storytel books encrypted at rest on the phone" (approved 2026-11-18). The user said
  "Tenkte mest ift et sånn at an ikke lager en downloader-tankegang", and then "Ja takk. Det er potensielt bedre enn
  det storytel selv gjør i dag..".
- **Design QA of revision 1:** `117-design-qa.md` (1 H, 6 M, 10 L). Every finding is applied or moot; §16 says
  which.
- **Scope cut by the user** (2026-11-18): "akkurat den overgangen fra ukrypterte til kryptert er ikke viktig, husk at
  vi har kun emu telefoner så langt". No real phone holds a plain book yet, so **there is no migration**. Revision 1's
  migration job, its crash table, the sweep and eviction exemptions, and ASK 2 are gone. A plain Storytel copy found
  after the update is simply removed and downloaded again sealed (§8).
- **Other changes from revision 1:**
  - The Keystore key's alias now carries an id, and that id is in each book's record. A key is never made over an
    alias that might exist, and "lost" is decided only by definite signals with a canary check (H1, M1).
  - The TEE only, no StrongBox (L6). This follows r10's choice.
  - The plain path fails closed for sealed keys (M5, L10).

Music app only. Nothing changes in the API, the bridge, the policy, the report, the server or the PWA. Paths: `M/` =
`music/app/src/main/java/me/vibb/music/`. This was read against `main` at b96e0a3d7, with Media3 1.7.1 (the class
names and the internals cited below were checked in its `media3-datasource` jar, with `javap`).

**Inputs:** root `CLAUDE.md`; `music/CLAUDE.md` ("A stream is written to the cache as it plays", the cache and prune
rules); `docs/notes/music/storytel.md`; design 26b §3.3, §4.3-§4.4; design 58 (the mint, unchanged here); design 30
§1.4-§1.8 (the cache, the held set, the sweep); design r10 (the launcher's Keystore wrap: the pattern, the user's
choice of TEE only, and the lessons from a lost key).

## 0. The shape

Today a Storytel book is the CDN's plain mp3 in the app's one Media3 `SimpleCache` (`noBackupFilesDir/media/*.exo`,
in 5 MiB span files). With this design every Storytel book is **sealed**:

- **One TEE Keystore key, `music_books_<id>`.** It is AES-256 with GCM, not exportable, and needs no user
  authentication. `<id>` is 8 random hex digits, and a new key always gets a new id.
- **A data key per book.** Each book gets its own random AES-256 key. That key is stored only in wrapped form, sealed
  with GCM by the Keystore key, in a 78-byte **seal record** kept in the cache's content metadata for that book. The
  record names its Keystore key's id.
- **AES-256-CTR at the byte's own position.** The sealed bytes are the same length and at the same offsets as the
  plain ones. So the Range resume, `cachedBytes`, the storage limit, the length guard and the `SimpleCache` itself
  work unchanged.
- **Sealing starts at the first byte.** Media3's cache sink receives only sealed bytes, so no plain byte of a book
  reaches the disk, a partial download included. The plain path refuses a sealed key, so a refactor can't quietly
  make it plain again (§4.5).
- **Decryption happens only while reading.** The player's cache-read source decrypts in memory. Nothing Storytel is
  ever read plain.
- **A sealed book has its own cache key**, `sealed:<entry key>:<cid>`. A Storytel copy under the old key, which only
  the emulators have, is removed at the update and downloaded again sealed (§8).
- **A lost key means the books download again, never a crash.** A Keystore hiccup never deletes a book.

**What it protects, and what it doesn't.** A copy of the app's files is unreadable without the key. That includes an
image of the phone's storage, a debuggable build's `run-as`, and any future backup slip. Because the key never leaves
the phone's Keystore, the files are useless off the phone. No file on the phone plays outside Vibb, so the phone holds
no mp3s, which was the user's "downloader" concern.

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
  - `downloadFactory` is a `CacheDataSource.Factory` with the http upstream and **no sink factory**. Media3 1.7.1's
    `createDataSourceInternal` then makes a default plain `CacheDataSink`; Media3 fails open (QA M5).
  - `playbackFactory(copyOnly = true)` is the Storytel playback path: no upstream, and so no sink.
  - The OkHttp clients have no disk cache.
- `M/sync/Downloads.kt` `fetch`: for Storytel this is a `ProgressiveDownloader` with `customCacheKey =
  itemCacheKey(entry.key, cid)`. Media3's `CacheWriter` fetches only the gaps, so a partial resumes with Range against
  a fresh mint.
- `M/sync/DownloadRun.kt`: one book at a time, the 1.5 GB floor (`STORYTEL_FREE_FLOOR`), and the row named by
  `storytel:book:<cid>`. The mint (`urlFor`) runs before the fetch. On `http_416` it removes the copy and fetches once
  from 0.
- `M/playback/PlaybackService.kt:1640-1646`: a book's `MediaItem` is `storytel:book:<cid>` with
  `ItemTag(cacheKey, copyOnly = true)`, and the cache key is computed with `itemCacheKey`.
- `M/sync/CacheThrough.kt`: `remove`/`removeCopy`/`evict`/`sweepOrphans` each re-read the held set. `removeCopy` does
  nothing while the player holds the key (`:236`), and `heldCacheKeys` (`:192-197`) knows the plain keys only. The
  sweep removes every resource key that no row names (`logic/Offline.kt:239 orphanResources`).
- `M/media/ItemMediaSourceFactory.kt`: an item with no tag, or an NRK/RSS item with no `ref`, gets `through = null`.
  With an upstream that is Media3's default plain sink, despite the comment "writes nothing (fails closed)" (QA L10).
- Backup and device transfer exclude every domain (`res/xml/data_extraction_rules.xml`), so neither the cache nor a
  Keystore key ever travels.

## 2. The key

### 2.1 The Keystore key (the KEK)

```kotlin
KeyGenParameterSpec.Builder("music_books_$id", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setKeySize(256).setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
    .build()   // the TEE; the default randomized IV; no user auth; no setUnlockedDeviceRequired; no StrongBox
```

- **The TEE, no StrongBox** (QA L6). This is r10's choice, which the user made for the launcher.
  - Against this threat model StrongBox buys nothing: the key never leaves either one, and root on a running phone
    wins either way.
  - StrongBox would add 100-500 ms per book open on some phones, and another family of transient errors for §7's
    rules to tell apart.
  - The Jelly Star (MediaTek) probably has no StrongBox anyway.
  - The PLAN line said "StrongBox where present". This revision departs from it for those reasons, and says so (§15,
    note).
- **Never a key in the app's own files or prefs.** A Keystore key at software level is accepted only because no
  Android 14+ CTS phone has one: KeyMint in a TEE is mandatory. We log it once (from `KeyInfo.securityLevel`), and
  that is all.
- **No user authentication and no `setUnlockedDeviceRequired`.** Books play with the screen locked and in the
  background, and the download job runs while the phone is locked. Such a key also survives a change of the phone's
  PIN, as r10 §1 notes.

### 2.2 The alias with an id, and "missing" only when it is certain (QA H1)

On keystore2, `containsAlias` returns false for **any** Keystore error, not only for a key that isn't there. It is
`getKeyMetadata(alias) != null`, and `getKeyMetadata` returns null after any `KeyStoreException`. `getKey` wraps every
error except "not found" and "permanently invalidated" in an `UnrecoverableKeyException`, so that exception may be
transient too.

If revision 1's "made when missing" ran on a hiccup, it would generate a new key over the live alias. keystore2
rebinds the alias, so every book's record would then fail and the whole library would download again. So:

- **The current id** lives in the app's prefs, `seal_kek_id`. The prefs hold the id, which is no secret, never a key.
  Clearing the app's data clears the prefs and the Keystore keys together.
- **"Missing" means `keyStore.getKey(alias, null)` returned null.** It returns null only for KEY_NOT_FOUND and
  throws otherwise. `containsAlias` is never used.
- **A new key always gets a new alias.**
  - Made only on the path that seals a new book, when there is no current id or the current alias read null.
  - The id is 8 random hex digits (`SecureRandom`), retried if `getKey` of that alias isn't null.
  - The key is generated, its canary is written (§2.3), and the id is stored in that order. A crash in between leaves
    an unused alias, which the clean-up below removes.
  - **No alias is ever generated over again.** At worst, a mistaken "missing" costs one extra Keystore key, never a
    book.
- **Old aliases:** an alias that no record names any more, and that isn't the current one, is deleted
  (`deleteEntry`). This runs at the end of `BookKeys.check`, and only when the check read every record without a
  transient failure. Android 17's per-app key cap (§11) is never near: there is one key, and briefly two.

### 2.3 The canary (QA M1)

When a key is made, we wrap a fixed 16-byte test blob (`VIBB-SEAL-CANARY`, AAD `canary:<id>`) under it and store the
result in prefs as `seal_canary_<id>`. Unwrapping the canary shows whether **this key and the Keystore** work right
now.

§7 counts a book's failure towards Lost only when the canary of the same key succeeds in the same check. That way a
broken Keystore (the OEM fault r10 saw) is never mistaken for a broken record.

### 2.4 Why a data key per book, not the Keystore key itself

Every Keystore operation goes over binder to keystore2 and on into the TEE. Bulk AES that way runs at a few MB/s. It
also holds one of KeyMint's few operation slots for as long as a book plays or downloads, and a slot pruned under
pressure would stop playback.

Keystore CTR with our own IV would also need `setRandomizedEncryptionRequired(false)`. Android 17 caps the number of
keys an app may own, which rules out a Keystore key per book as well. Envelope encryption avoids all of this:
- the Keystore key wraps 32 bytes once per book;
- the bulk work is Conscrypt's AES-CTR in the app's memory, using the CPU's AES instructions (§10).

This is the pattern Jetpack Security's `EncryptedFile` used, with a Tink keyset wrapped by a Keystore master key.
That library is deprecated, and its chunked format would break the offset identity (§14).

### 2.5 The data key's life

- **Made** with `KeyGenerator.getInstance("AES").init(256)`, using the platform `SecureRandom`, when a sealed key
  has no span (§4.3).
- **Wrapped** at once: `Cipher("AES/GCM/NoPadding")` with the Keystore key, and the Keystore picks the 12-byte IV.
  The AAD is §3.1's header plus the cache key.
- **Kept on disk** only inside the seal record (§3.1).
- **The memo is keyed by the record, not the cache key** (QA L3).
  - Every open of the sink or the source reads `vibb_seal` from `cache.getContentMetadata(key)`, which is in memory.
    With no record it throws.
  - The memo holds up to 4 unwrapped keys (an LRU), keyed by the record's nonce and wrapped key. A dropped and
    re-minted record can therefore never be paired with an old key.
  - It is filled at a record's first unwrap, on the loader thread or the worker, never the main thread.
  - The JVM can't promise to zero a `SecretKeySpec`, so this is best effort. Anyone who can read our memory is our
    uid or root anyway.
  - The holder prints redacted (`toString()`), and `SourceScanTest` pins that, as with the Storytel secrets.
- **The cipher checks itself on the phone** (QA L7). At first use per process, `BookKeys` runs one vector from
  `seal_vectors.json` (built into `logic/Seal.kt` as constants) through Conscrypt. That covers a start mid-block and a
  counter carry past a 2^32 boundary, and checks that `update()` returns as many bytes as it is given. On a mismatch,
  nothing is sealed or read: the failure is Transient (§7), logged once, and books stay where they are.

## 3. The format

### 3.1 The seal record (the "header")

The record is stored as the resource's Media3 `ContentMetadata` value `vibb_seal` (bytes). It goes into
`exoplayer_internal.db` beside the `exo_len` that Media3 keeps there. It is written with
`applyContentMetadataMutations`, which stores the index synchronously, so the record is on disk before the first span.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `VBSL` |
| 4 | 1 | version `1` |
| 5 | 1 | suite `1` = AES-256-CTR data, its key wrapped by AES-256-GCM under `music_books_<id>` |
| 6 | 4 | the Keystore key's id (the alias's 8 hex digits as 4 bytes) |
| 10 | 8 | CTR nonce (random, `SecureRandom`) |
| 18 | 12 | the wrap's GCM IV (the Keystore's) |
| 30 | 48 | the wrapped data key (32 bytes + a 16-byte tag) |

That makes 78 bytes. The **GCM AAD** is bytes 0-17 followed by the UTF-8 sealed cache key. This means a record copied
onto another book, a changed nonce, or a changed key id fails the unwrap, and is never used.

An unknown magic, version or suite, or the wrong length, counts as unreadable (§7). A later version is read only by a
later build. A downgrade is not a supported path: `adb install -d` on the emulator leaves the books unplayable until
they are re-downloaded.

When the empty content with its record is dropped (at the cache's start, `removeEmpty`, or by `releaseHoleSpan`'s
`maybeRemove`), the next `prepare` mints a new record (§4.3).

### 3.2 The cipher: AES-256-CTR, counter = position / 16

- The IV of the 16-byte block holding byte `p` is `nonce (8 bytes) || uint64_be(p / 16)`.
- A read or write that starts at `p` initialises the cipher at block `p / 16` and drops `p % 16` bytes of keystream.
- `M/logic/Seal.kt`'s `SealCipher` (our own, about 50 lines, javax.crypto `AES/CTR/NoPadding`) handles any start
  and any length, carrying the partial block across calls.
- Media3 has `AesFlushingCipher`, which does the same with an FNV hash of the key as the nonce. We don't use it: it is
  `@UnstableApi`, and a Media3 bump that changed its IV layout would silently make every book unreadable. Our own
  cipher is pinned by `seal_vectors.json` (§13) and checked on the phone (§2.5).
- **The invariant that keeps CTR safe** (QA L1): under one data key, a position always carries the same plain byte.
  - `SimpleCache` *can* write a range again. After a span file vanishes, `removeStaleSpans` drops it and the resume
    fetches it again under the same key. Rewriting the same book bytes gives the same ciphertext, so that leaks
    nothing.
  - The invariant would break only if the CDN served different bytes at the same total length, which the
    `LengthGuard` doesn't catch, and then only against an attacker who kept the old span.
  - Every start-over gets a new data key and nonce (§4.3): the first download, the 416 refetch (the length guard) and
    the lost path.

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
  need to read, modify and rewrite the last chunk. And every length-based rule would need a translation.
- **What a tag would buy is small here.** A tag detects tampering. But only our uid or root can write
  `noBackupFilesDir`, and both can call the Keystore anyway. Bit rot or a torn write behaves as it does today: the mp3
  decoder skips the bad frame or the item fails. Today's plain copies have no check either, so there is no loss.
- **A wrong or lost key is caught anyway, and loudly.** The record's GCM unwrap authenticates the data key before a
  single byte is decrypted.
- **No whole-file MAC is stored.** It could only be checked by reading the whole book, so it couldn't guard a seek,
  and with no migration there is no plain copy to compare against.

## 4. Where the seal lives in the cache

### 4.1 The sealed cache key

- `logic/Seal.kt`:
  - `sealedCacheKey(entryKey, cid) = "sealed:" + itemCacheKey(entryKey, cid)`;
  - `isSealedKey(key) = key.startsWith("sealed:")`.
- **A Storytel book is read and written only under a sealed key.** Whether a book is sealed follows from its key, not
  from metadata that could be missing. A sealed key without a readable record is §7's path, never a plain read.
- NRK, RSS and HLS keys are unchanged. `itemOfResource` and `hlsKeyPrefix` see a `sealed:` key as a progressive key
  (it doesn't start with `hls:`).
- `heldCacheKeys` (`CacheThrough.kt:192-197`) adds the `sealed:` key for a Storytel entry's held items (QA L4). The
  sweep and every removal then spare a held book, its row present or not: §7's lost path drops the row even when
  `removeCopy` declines.

### 4.2 The sink and the source (`M/media/SealStreams.kt`, about 120 lines)

- **`SealDataSink(inner: CacheDataSink, keys: BookKeys)`**, the Storytel cache sink:
  - `open(spec)` requires a sealed `spec.key`, a readable record (§2.5: read at every open) and
    `spec.uriPositionOffset == 0`. It throws otherwise.
  - It sets the cipher at **`spec.position`**, which is exactly where `CacheDataSink` writes (QA L2: the sink writes
    at `spec.position + bytesWritten` and ignores `uriPositionOffset`). Then it calls `inner.open(spec)`.
  - `write` encrypts into its own 64 KiB scratch buffer (looping) and writes the scratch. Never in place: the
    `TeeDataSource` hands the reader's own buffer to the sink.
  - The cipher state runs continuously across the `CacheDataSink`'s 5 MiB file splits, which happen below it.
- **`SealDataSource(upstream: FileDataSource, keys)`**, the Storytel cache-read source:
  - `open(spec)`: Media3 1.7.1's `CacheDataSource.openNextSource` gives a cached span as `uri = the span file`,
    `uriPositionOffset = span.position`, `position = the offset in the file`, with `key` set. The book's position is
    therefore `uriPositionOffset + position`, the same formula as Media3's own `AesCipherDataSource`.
  - `read` reads from the file into the caller's buffer and decrypts in place (the buffer is the reader's own).
  - A key that isn't sealed (a plain Storytel copy the update hasn't removed yet, §8) or a sealed key with no readable
    record throws `SealLostException` or a transient `IOException` (§7). **Nothing Storytel is read plain.**
- **Seeking.** ExoPlayer's seek is a new open at a new position, and the source starts the cipher at that block. The
  mp3 extractor's constant-bitrate or Xing seek works as today, because the plain offsets are the sealed offsets.

### 4.3 Preparing a book's seal (`BookKeys.prepare(sealedKey)`, under `BookKeys`' lock)

The download (§5) calls this before any byte is written:
1. **The key:** the cipher self-check (§2.5) and the current key (§2.2, which may make a new one). If either fails
   transiently, throw Transient.
2. **No spans:** re-read `cache.getCachedSpans(S).isEmpty()` right before the write, under the lock (QA M4). Then mint
   a new data key, wrap it, and write a new record, overwriting any leftover. A book that starts over therefore always
   gets a new key.
3. **Spans and a readable record:** unwrap it into the memo. This is a resume, and the same key continues.
4. **Spans and an unreadable record:**
   - for a **Lost** record (§7), drop the spans with `removeCopy`, which re-reads the held set. Then **check again**:
     if any span is left (the player holds the book), throw Transient. Never mint over remaining spans: one book under
     two keys would decrypt to noise.
   - for a **Transient** failure, throw Transient.

### 4.4 The resume: the HTTP Range offset is the sealed offset

`CacheWriter` asks the CDN for `Range: bytes=p-`, where `p` is the end of what is cached. The answer's first byte is
the book's byte `p`. The sink seals it at counter `p / 16` (dropping `p % 16`) and the `CacheDataSink` writes it at
`p`. No offset maps to another anywhere.

The `LengthGuard` compares the 206's total with `exo_len`, which is unchanged, so a changed book is still a 416. The
rule "a partial survives a re-mint" (26b §4.3) holds as it is.

### 4.5 The factories, failing closed (QA M5, L10)

In `MediaCache`:
- **`storytelDownloadFactory(...)`:** the http upstream (no-redirect), with `setCacheWriteDataSinkFactory {
  SealDataSink(...) }` and `setCacheReadDataSourceFactory { SealDataSource(...) }`.
- **`storytelPlaybackFactory(keys)`:** copy-only, with no upstream (and so no sink), and the `SealDataSource` read
  factory.
- **The plain factories never write a sealed or Storytel key, and never make Media3's default sink.**
  - `downloadFactory`, `playbackFactory` and the HLS path always pass an explicit sink factory: the `ThroughSink`, or a
    new `NoWriteSink` that drops every write when there is nothing to write through.
  - That closes L10's gap, where a tagless item, or an NRK/RSS item with no `ref`, got Media3's plain sink.
  - Each plain sink's `open` refuses a `spec.key` that starts with `sealed:`, and a spec whose uri is a Storytel
    source id. It throws, and the item fails rather than storing a plain book.
- **`ItemMediaSourceFactory`** sends any item whose cache key is sealed or Storytel's, by its tag or its
  `customCacheKey`, to the `storytel*` factory. Only items with neither use the plain ones.

`SourceScanTest`: `Source.STORYTEL` and `copyOnly` reach only the `storytel*` factories, and no `CacheDataSource.Factory`
in `media/` is built without an explicit sink factory unless it has no upstream.

## 5. The download path

- **`DownloadRun.fetchOne` for a Storytel book:**
  - Its cache key is `sealedCacheKey(...)`.
  - **An older row under another cache key** (a plain partial §8 hasn't removed yet) has its copy dropped, as a
    partial from another source is today. The book starts over sealed.
  - **The seal is prepared before the mint** (QA M1). `DownloadIo` gains `prepareSeal(cacheKey): SealReady`, which
    `WorkerIo` maps to `BookKeys.prepare`, and `fetchOne` calls it before `urlFor`. A Transient failure records
    `network` on the book, with no mint at Storytel and `mayRetry`'s usual wait, and the run goes on. With the
    Keystore down, every book fails the same cheap way and nothing is minted.
  - Everything else is unchanged: the floor, the mint, the 416 refetch with a fresh mint (whose `removeCopy` empties
    the key, so `fetch` prepares a new record), the failures.
- **`Downloads.fetch` for Storytel:** `ProgressiveDownloader` with `customCacheKey = sealedKey` on
  `storytelDownloadFactory`. It calls `prepare` again, which is a cheap resume after `prepareSeal`. It covers the 416
  path's empty key.

## 6. The playback path

- **The book's `MediaItem`:** `PlaybackService.mediaItem` takes the book's cache key from its done row
  (`a.downloads[item.key]?.cacheKey`), which is always sealed after §8. It no longer computes it with `itemCacheKey`.
  A book plays only with its done row (26b §4.4); with none it starts nothing, as today (`bookNotOnPhone`).
- **A book whose key is lost while it is open:** `SealDataSource.open` throws `SealLostException` (an `IOException`).
  - `SourceChangedPolicy` returns `C.TIME_UNSET` for it, as it does for a changed source (QA L5), so it is not
    retried at 1, 2 and 3 s.
  - `LocalRenderer` (`:137-138`) passes a `lost` flag on `RendererEvent.Error`, beside the existing `gated` flag. The
    service maps it to `NOT_DOWNLOADED`, the existing "Ikke lastet ned". That is true, since the book will be fetched
    again.
  - The service asks for a sync, and §7 drops the row. Nothing new on screen, never a crash.
  - A **Transient** failure at an open is an ordinary item failure, "Kan ikke spilles nå". A tap retries it.

## 7. A lost or broken key: the books download again, never on a hiccup

**When it happens:** a Keystore wipe, or an OEM Keystore fault after an OTA (r10 §5 saw these). Not a backup restore:
nothing travels (§1). Not clearing the app's data or uninstalling: those take the files with the keys and the prefs.
Not a PIN change: the key has no authentication (§2.1).

**Classification** (`logic/Seal.kt`, pure: `sealFailure(...) -> Lost | Transient | Unclassified`):
- **Lost, definitely:**
  - `getKey(alias_of(record))` returned null (§2.2);
  - `KeyPermanentlyInvalidatedException`;
  - an exception whose cause chain reaches an `android.security.KeyStoreException` with `ERROR_KEY_DOES_NOT_EXIST` and
    `!isTransientFailure()` (QA H1(b));
  - a record with an unknown magic, version, suite or length.
- **Lost, only with a good canary:** an `AEADBadTagException` on the record, when the same key's canary (§2.3)
  unwrapped in the same check. The key works, so this record is bad.
- **Transient:**
  - `isTransientFailure()`;
  - an `UnrecoverableKeyException`, `InvalidKeyException` or `ProviderException` without the definite cause above;
  - a binder or keystore2 failure;
  - the cipher self-check failing;
  - any failure while the canary fails.

  A transient failure never deletes anything. r10's rule applies: a passing Keystore hiccup must not cost the kid a
  book.
- **Unclassified:** anything else with a good canary. It counts in prefs as `seal_fail_<sealed key>` = `{first_at,
  count}`, and a success resets it. At 3 or more over at least 24 h it becomes Lost: a fault we can't name must not
  keep a book unreadable for ever, but it is never counted while the Keystore itself is down.

**The check:** `BookKeys.check` runs at every sync, before the downloads are planned.
- It parses every sealed Storytel row's record (in memory).
- It unwraps each key's canary once.
- It unwraps a record only the first time per process, or after a failure. A memo hit costs nothing.

For a book that is **Lost**:
- its resource goes (`removeCopy`: never while the player holds it, so the next sync tries again);
- its download row goes (`deleteDownload`);
- its prefs counter goes.

The plan then wants the book again, and the job fetches it sealed under a new data key, and under a new Keystore key
when its alias is gone.

Positions and bookmarks are keyed by entry and item (`music/CLAUDE.md`), so **the book comes back where the kid left
it**. The download follows the parent's switches as any download does (ASK 1). A Keystore wipe loses every book at
once, which is several GB, and they come back over mobile data too when the parent's "Download on mobile data" switch
is on.

## 8. Plain copies from before: removed, not migrated (the user's scope cut)

Only the emulators hold plain Storytel books (the user, 2026-11-18), so there is no migration. One small function
does the whole job:

`CacheThrough.dropPlainStorytel(context)` runs at every sync, before `BookKeys.check` and the plan. It is a no-op once
nothing is left:
- **Which rows:** pure `plainStorytelRows(rows)`, in `logic/Seal.kt`: the Storytel rows (`url` starts with
  `storytel:book:`) whose `cacheKey` isn't sealed.
- **What happens to each:** `removeCopy(cacheKey)`, which re-reads the held set, then `deleteDownload`. The plan then
  wants the book again and the job fetches it sealed.
- **A held book** keeps its row and is tried again at the next sync. The player can't read it anyway (§4.2), so it
  shows "Ikke lastet ned" until it is back.
- **Plain Storytel bytes with no row** are already removed by the orphan sweep.

Deleting the spans is the whole removal. On the emulators no overwrite is wanted, and on flash an overwrite wouldn't
be reliable anyway: f2fs, which is log-structured, and any FTL write new blocks.

## 9. Free space, the limit and the report

- **Downloads:** a sealed book is exactly as large as the plain one, plus 78 bytes in the index database. Every floor
  and limit is unchanged: 1.5 GB for Storytel, 1 GiB for the extras, the parent's limit.
- **The report and the PWA:** nothing new.
  - `audio_bytes` keeps its meaning.
  - No new `item_errors` code: a transient seal failure is `network`, and a lost book is no error. The row goes, and
    the download shows as any download does.
  - The debug log gives counts only (for example "dropped 2 plain, 1 lost"). Never a key, and never a record's bytes.

## 10. Performance on the Jelly Star

The phone has a Helio G99 (2 x Cortex-A76 at 2.2 GHz, 6 x Cortex-A55 at 2.0 GHz), ARMv8.2 with the Cryptography
Extension, and UFS storage. It runs Android 16. Storytel's mp3 is about 64 kbit/s, which is about 29 MB per hour; a
10-hour book is about 290 MB, matching 26b's "300 MB".

- **AES-256-CTR:** BoringSSL's ARMv8 path (`aes_hw_ctr32`) costs roughly 1.5-2 cycles per byte on an A55, which is
  about 1 GB/s on one little core. At 10x worse (JNI per small read, short buffers), 100 MB/s.
- **Playback:** 29 MB per hour of listening is 0.03-0.3 s of one little core per hour, under 0.01 %. Battery: nothing
  measurable.
- **Download:** at 100 Mbit/s Wi-Fi (12.5 MB/s), 1-12 % of one little core while the download runs. The network is
  the limit.
- **Keystore:** one GCM unwrap of 48 bytes per book per process, about 5-30 ms in a MediaTek TEE. It runs on the loader
  thread at a book's first open, covered by the play start's own buffering. `check` adds one canary unwrap per sync.
- **Memory:** a 64 KiB scratch buffer per open sink and up to 4 keys.

## 11. Android 14-17

Checked 2026-11-18 against each version's behaviour changes. Every API used is at API 33 or lower, so there is no
`SDK_INT` gate, and lint's NewApi confirms that.

| Version | What touches us | Verdict |
|---|---|---|
| 14 (34) | keystore2 and KeyMint; AES-256-GCM in the TEE is mandatory; `KeyStoreException.isTransientFailure`/`getNumericErrorCode` (33); `containsAlias` reads any error as "missing" (all of 12+) | fine, with `getKey` alone (§2.2); the lowest version, gate run |
| 15 (35) | `setUnlockedDeviceRequired` was reworked | not used (§2.1) |
| 16 (36) | `KeyStoreManager` (key grants between apps) | not used; the Jelly Star's version, device check |
| 17 (37) | a per-app cap on Keystore keys (50,000 when targeting 37, 200,000 otherwise; `ERROR_TOO_MANY_KEYS`), [behavior changes: all apps](https://developer.android.com/about/versions/17/behavior-changes-all) | one key, briefly two (§2.2); the highest version, gate run |

Media3's `CacheDataSource`, `CacheDataSink`, `CacheWriter` and `FileDataSource` don't depend on the version.

## 12. What stays as it is (each said once)

- NRK, RSS and radio: plain cache, the `ThroughSink`, `HttpsFirst` and every key. Their factories now pass an
  explicit sink (§4.5), which changes no behaviour except to close L10's gap. Own files: their files and their SHA-256
  in `own/`.
- Sonos and Storytel on a room: the minted URL goes to the speaker, and the phone serves nothing.
- The mint, the JWT courier, the token's secrecy (design 58) and the no-redirect CDN client.
- The server, which never fetches audio, and the bridge, the policy, the report and the PWA.
- Positions, bookmarks, keep and prune, the held set, design 30's rules, and the Storytel floor.
- The kid's screens: no new word or state. The only words a sealed book can bring are today's "Ikke lastet ned" and
  "Kan ikke spilles nå" (§6).

## 13. Tests (JVM, no real-looking keys)

The keys are `SHA-256("FAKE-SEAL-KEY-<n>")`, and the nonces and IVs are spelled-out fakes. No Keystore in JVM tests:
`BookKeys` sits behind a `KeyStoreSeam` (`getKey(alias)` returns a key, null, or throws; `generate(alias)`;
`wrap`/`unwrap`; `delete`). Its test version is software AES-GCM under a fake-phrase key, and can throw anything on
request.

- **`SealFormatTest`** (`logic/Seal.kt`):
  - a record encodes to exactly 78 bytes, and decoding gives the record back;
  - a wrong magic, version, suite or length is unreadable;
  - the AAD covers the cache key, the nonce and the key id: changing any one fails the unwrap;
  - the cipher at any start and length (random, non-aligned, crossing a 5 MiB split, crossing a 2^32-block carry)
    equals slicing one pass from 0;
  - `seal_vectors.json` (`music/app/src/test/resources/`, the music app's own file, not a shared contract) pins the
    record bytes and the sealed bytes of a fixed plaintext at offsets 0, 15, 16, 17, 5 MiB-1 and past the carry. The
    same vector is the phone's self-check constant (§2.5), and the test checks that the two agree;
  - `plainStorytelRows` picks exactly the Storytel rows whose key isn't sealed: that is §8's one test.
- **`BookKeysTest`** (QA H1, M1, M4):
  - `getKey` returning null makes a new alias with a new id; `getKey` throwing never generates;
  - a generated id that collides is retried;
  - no alias is ever generated twice;
  - the order is key, canary, id, and a crash at each point leaves the books readable;
  - old aliases are deleted only after a clean full check;
  - `prepare` re-reads the spans under the lock: a refused `removeCopy` with a Lost record means no new record and a
    Transient failure.
- **`SealFailureTest`:**
  - `UnrecoverableKeyException` with a transient cause is Transient, and with `ERROR_KEY_DOES_NOT_EXIST` and not
    transient it is Lost;
  - `AEADBadTagException` is Lost with a good canary and Transient with a bad one;
  - Unclassified becomes Lost at 3 over 24 h with a good canary, never while the canary fails, and a success resets
    it (the prefs counter behind a seam).
- **`SealStreamsTest`** (`media/`):
  - `SealDataSink` and `SealDataSource` with fake inner sinks and sources, and the `DataSpec` built as
    `ThroughSinkTest` builds it, in both of Media3's shapes: an upstream open (`uriPositionOffset 0`) and a span read
    (`uriPositionOffset = span.position`);
  - the sink refuses `uriPositionOffset != 0`, never writes plain bytes and never changes the caller's buffer;
  - the memo, keyed by the record, never pairs a new record with an old key;
  - the source refuses a key that isn't sealed, and a sealed key without a record.
- **`StorageFactoriesTest`** (QA M5(a), (b)):
  - `storytelDownloadFactory(...).createDataSourceForDownloading()` is checked by reflection: its
    `cacheWriteDataSink` must be a `SealDataSink` and its `cacheReadDataSource` a `SealDataSource`. The playback
    factory gets the same check;
  - every plain factory's sink refuses a `sealed:` key and a Storytel source id;
  - `ItemMediaSourceFactory` sends a `sealed:` `customCacheKey` with no tag to the Storytel factory;
  - a tagless item, and an NRK item with no `ref`, get `NoWriteSink`.
- **`StorytelDownloadTest` (extended):**
  - `prepareSeal` runs before the mint, and a Transient failure records `network` and mints nothing;
  - a plain-key partial's copy is dropped and the book starts over sealed;
  - a 416 makes a new record (new nonce); a resume keeps the record.
- **`OfflineTest`:** `heldCacheKeys`' sealed keys are spared by the sweep.
- **`SourceScanTest`:**
  - `AndroidKeyStore` and `KeyGenParameterSpec` appear only in `media/BookKeys.kt`, and `containsAlias` nowhere;
  - §4.5's factory rules;
  - the key holders' `toString()` are redacted;
  - no `Log.` names a record or key;
  - the key-deleting debug receiver (§14) exists only in `src/debug`.

## 14. The emulator gate and the device checks

**Emulator:** this waits for the user's OK, under the rule "Emulator: ask first". It runs on API 34 and API 37,
because the Keystore varies by version, using the images from 2026-11-16. Debug build, `scripts/fake-storytel.py
--books 111:20,222:5`, `docs/testing/emulator.md` §6i, with a new §6ba "Sealed books":
1. **Every span sealed** (QA M5(c)). Download both books, then `run-as me.vibb.music.debug` and pull every span file in
   `no_backup/media`. The fake serves silent WAV, which is mostly zero bytes. **Every** span must have a zero-byte
   share under 1 %, as AES output does (about 0.4 %). A plain span, however far into the file, is mostly zeros, so this
   catches what `ffprobe` and the first-bytes check can't.
2. Play, seek to the middle and to the last minute, lock the screen, and wait out a 10-minute background play.
3. `--drop-at` cuts a download partway; the next job resumes it with Range, and step 1's check passes on all spans.
4. **The update from before:** install `main`'s debug build from before 117, download both books (plain), then
   install this build and sync. The plain copies and rows are gone, both books download again sealed, and step 1
   passes.
5. **Lost key:** a debug-only receiver deletes the current `music_books_<id>`, which is the only way to simulate a
   wipe. Playing shows "Ikke lastet ned" at once (no retries); the sync drops both rows and the job downloads them
   again under a new id, and each book resumes at its bookmark.

**Jelly Star**, after the release:
- the key's security level, logged once (TEE expected);
- a 10-hour book played and sought.

**Considered and not chosen:**
- **A file format of our own outside `SimpleCache`** (a header, then 64 KiB GCM chunks). It would replace the resume,
  `cachedBytes`, the sweep, the eviction, the length guard and the copy-only player path that design 30 hardened, and
  every offset would need translating (§3.3).
- **Media3's `AesCipherDataSink`/`AesCipherDataSource` as they are.** Their nonce is an FNV hash of the cache key, and
  `AesFlushingCipher` is `@UnstableApi` (§3.2).
- **Keystore AES-CTR for the bulk work**, and **a Keystore key per book** (§2.4).
- **StrongBox where present** (§2.1).
- **Tink / Jetpack Security `EncryptedFile`:** a new dependency, deprecated, and chunked (§2.4).
- **A migration of plain books** (revision 1 §8): dropped by the user, since only emulators hold any (§8).

## 15. Steps (one branch `music-117-seal`, one music release)

| Step | What | Size |
|---|---|---|
| 1 | `logic/Seal.kt` (record, cipher, keys, failure classes, the self-check vector, `plainStorytelRows`); `media/BookKeys.kt` (key ids, `getKey`-only, the canary, the prefs counter, wrap, memo, `prepare`, `check`); `media/SealStreams.kt`; §4.5's factories, `NoWriteSink` and the plain sinks' refusal (L10 included); `ItemMediaSourceFactory`'s routing; `Downloads.fetch`, `DownloadRun` (sealed key, `prepareSeal` before the mint); `PlaybackService` taking the row's key; `SourceChangedPolicy` and `LocalRenderer`'s `lost` flag; `heldCacheKeys`' sealed keys; `dropPlainStorytel` and `BookKeys.check` in the sync; tests §13 | M: about 540 lines of code and 480 of tests |
| 2 | Notes: `music/CLAUDE.md` (the cache paragraph: a Storytel book is sealed; the plain factories fail closed); `docs/notes/music/storytel.md` (a new "Sealed books" rule); 26b §9's deviation row; `docs/testing/emulator.md` §6ba. Then the emulator gate on 34 and 37 once the user says yes | S |

**A note for the record, not a question:** the PLAN line said "StrongBox where present". Revision 2 uses the TEE
alone, as the user chose for the launcher in r10. StrongBox adds nothing against this threat model, but it adds
latency and failure modes (§2.1).

## 16. ASKs (only what the user would notice)

1. **A lost key means the books download again by themselves.** This is rare: a Keystore wipe or an OEM fault, never
   a passing Keystore hiccup (§7). It loses **every book at once, often several GB**.
   - The books come back over the parent's usual download rules: Wi-Fi, or **mobile data too if "Download on mobile
     data" is on**.
   - Each book resumes at its bookmark. Until it is back it shows "Ikke lastet ned".
   - The parent is not told.

   **Recommendation: yes, as described.** Nothing is wrong that the parent can act on, and the parent's own switch
   already decides whether GB-sized downloads may use mobile data (26b §4.3 says so on the PWA's Music page).

## 17. Design QA applied (`117-design-qa.md`)

| Finding | Where |
|---|---|
| H1 `containsAlias`/`UnrecoverableKeyException` aren't "lost"; a key remade over a live alias | §2.2 (`getKey`-only, id in the alias and the record, never generated over), §3.1 (78 bytes), §7, §13 `BookKeysTest`/`SealFailureTest` |
| M1 the 24 h rule without state or health check; mint before prepare | §2.3 canary, §7 (prefs counter, canary gate), §5 `prepareSeal` before `urlFor` |
| M2 a bare `CacheDataSink` is refused | moot: no migration (the user) |
| M3 loaded queues and removal race | migration part moot: no migration (the user). The removal part applies: §8 uses `removeCopy`, and a held book waits |
| M4 a record minted over spans `removeCopy` kept | §4.3 (re-read under the lock, Transient if spans remain), §13 |
| M5 tests can't see a plain write | §4.5 fail-closed factories, §13 `StorageFactoriesTest`, §14 step 1 (every span's zero share) |
| M6 ASK 2 framed against the goal | ASK 2 moot: no migration (the user). ASK 1's size note added |
| L1 the reuse invariant | §3.2 |
| L2 the sink's position | §4.2 (`spec.position`, `uriPositionOffset == 0`) |
| L3 memo bound to the record | §2.5 |
| L4 `heldCacheKeys` without the sealed key | §4.1 |
| L5 a lost book retried, and the mapping's path | §6 (`SourceChangedPolicy`, `LocalRenderer`'s `lost` flag) |
| L6 StrongBox | §2.1 TEE only; noted in §15 |
| L7 the cipher pinned only on the JVM | §2.5 the on-phone self-check, §13 |
| L8 the FBE wording, the plaintext-hash reason | the migration's removal wording is moot (§8 says only "delete"); §3.3's plaintext-hash reason dropped |
| L9 storage-not-low blocks the freeing path | moot: no migration job (the user) |
| L10 a tagless item gets Media3's plain sink | §4.5 `NoWriteSink`, built in step 1 |
