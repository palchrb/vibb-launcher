# Design QA: 117, Storytel books sealed at rest (revision 1)

Read-only review of `docs/design/117-storytel-sealed-books.md` against `music/` on `main` (d711a5bbf), Media3 1.7.1.
I checked the Media3 claims in the 1.7.1 `media3-datasource` classes with `javap -c`, since there is no sources jar.

**Counts: H 1, M 6, L 10.**

## What holds (checked, no finding)

- **The CTR layout.** The IV is `nonce(8) || be64(p/16)`, and the cipher drops `p % 16` bytes. Each seal gets a fresh
  random AES-256 data key, and every start-over (no spans) mints a new key. That gives keystream uniqueness across
  re-downloads, the 416 refetch, the lost path and the migration's mismatch restart. A resume keeps the key, but it
  only fills holes at new positions, and rewriting a range with the same book bytes gives the same ciphertext, so it
  leaks nothing. The counter never carries into the nonce for any real file size.
- **The GCM wrap.** It uses a Keystore-chosen IV, a 128-bit tag, and an AAD of the header plus the cache key. 4+1+1+8+12+48
  = 74 bytes is correct. A moved or edited record fails the tag before any byte is decrypted.
- **The Media3 wiring, checked in 1.7.1 bytecode.**
  - `CacheDataSource.openNextSource` builds a cached span's DataSpec as `setUri(file).setUriPositionOffset(span.position).setPosition(readPosition - span.position)`
    with the request's `key`, so `uriPositionOffset + position` is the book offset.
  - The upstream DataSpec is `requestDataSpec.buildUpon().setPosition(readPosition)`, with uriPositionOffset 0.
  - `TeeDataSource` hands the reader's own buffer to the sink, so "never in place" is right.
  - `CacheDataSink` splits fragments below the wrapper, so its splits are invisible to a sink outside it.
  - `SimpleCache.applyContentMetadataMutations` stores the index synchronously, so the record is on disk before the
    first span.
  - Init runs `removeEmpty()`, and `releaseHoleSpan` runs `maybeRemove()`, which drops an empty content together with
    its record. This matches §8.3 row 1.
- **Plain bytes on disk for new books: none found.**
  - The OkHttp clients have no disk cache. CacheWriter's buffer is in memory, and so is `ReusableBufferedOutputStream`.
  - With the no-redirect client, `exo_redir` holds no URL.
  - No download notification exists (the job is a plain WorkManager worker, and NewNotifier is "Nytt" only).
  - `failureLine` redacts URLs, and the download errors are codes only.
  - Backup and transfer are excluded (`allowBackup=false` plus `data_extraction_rules.xml`).
  - The copy-only playback factory has no upstream, so it has no sink (`createDataSourceInternal`: no upstream means
    no sink).
- **Android 14-17 table.** No API above 33 is used, and nothing version-dependent was missed that I can see.
- **Performance figures (§10).** They are plausible: 64 kbit/s is about 29 MB/h, AES-CE on an A55 is well over
  100 MB/s, and the migration's 1.2 GB of I/O per 300 MB book is a few seconds on UFS.

## H

**H1 - "alias missing" and `UnrecoverableKeyException` aren't reliable lost signals. The key could be remade over a
live one, which would destroy every book on a Keystore hiccup.** (§2.1 line 102, §7 lines 289-291)
- On keystore2 (Android 12+), `AndroidKeyStoreSpi.engineContainsAlias` is `getKeyMetadata(alias) != null`, and
  `getKeyMetadata` returns null for **any** `KeyStoreException`. It logs a warning for anything but KEY_NOT_FOUND. So a
  binder or keystore2 restart, `SYSTEM_ERROR` or `BUSY` reads as "missing".
- `loadAndroidKeyStoreKeyFromKeystore` wraps every error code other than KEY_NOT_FOUND and KEY_PERMANENTLY_INVALIDATED
  in `UnrecoverableKeyException`, transient ones included. Both of these are on the design's Lost list.
- The worse path is §2.1's "made only when it is missing", checked with `containsAlias`. A new book's `prepare` that
  hits a transient failure would call `generateKey("music_books_v1")`. keystore2 rebinds an existing alias to the new
  key, so every sealed book's record then fails with `AEADBadTagException`, which is classed Lost, and the whole
  library is deleted and downloaded again (possibly over mobile data). That is r10's "a passing hiccup must not cost
  the kid a book", at library scale.

Fix:
- (a) "Missing" only when `keyStore.getKey(alias, null) == null`. That returns null only for KEY_NOT_FOUND and throws
  otherwise. Never use `containsAlias`.
- (b) Classify `UnrecoverableKeyException`, `InvalidKeyException` and `ProviderException` by walking the cause chain
  to the `android.security.KeyStoreException`: Lost only for `ERROR_KEY_DOES_NOT_EXIST` with `!isTransientFailure()`,
  else Transient.
- (c) Never generate over an alias that might exist. Version the KEK alias (`music_books_<id>`, with a 4-byte random
  id), and put the id in the record header (in the AAD, so the record grows to 78 bytes). A new KEK is then always a
  new alias, and a record is Lost only when *its* alias reads null. A mistaken "missing" then costs one extra key,
  never the library.
- Add `SealFailureTest` rows for an `UnrecoverableKeyException` with a transient cause, and for `getKey` throwing
  versus returning null.

## M

**M1 - The 3-times-in-24-hours Lost rule has no stored state and no health check, so a broken Keystore deletes every
book for nothing.** (§7 lines 296-297)
- The count has to outlive the process, because `check` runs "once per process per book". The design doesn't say
  where it lives.
- When the Keystore itself is down (the OEM fault r10 saw), every book trips the rule after 24 h. Deleting doesn't
  help: the re-download's `prepare` can't wrap either, so the kid ends up with nothing in place of books that come
  back after the next OTA. Meanwhile each retry still mints at Storytel (`DownloadRun.urlFor` runs before `fetch`).

Fix:
- Keep `{first_failure_at, count}` per sealed key in Room (or prefs), and reset it on success.
- Count a failure towards Lost only when a **canary** succeeds in the same check: a wrap and unwrap of a fixed test
  blob under the same KEK. That way the rule catches "this record is bad", never "the Keystore is down".
- Have `prepare` fail before the mint. Move the seal step ahead of `urlFor` in `DownloadRun.fetchOne`, or give it a
  cheap `BookKeys.ready()` gate.

**M2 - The migration's copy writes through a bare `CacheDataSink`, which Media3 refuses.** (§8.2 step 3, lines 336-338)
- `SimpleCache.startFile` does `checkState(cachedContent.isFullyLocked(position, length))`, which I checked in the
  bytecode. A `SealDataSink` over a `CacheDataSink` "opened at that position with that length" throws
  `IllegalStateException` at the first write, unless the range is locked first.
- Fix: drive `S` through a `CacheDataSource`, with
  - upstream = a `DataSource` reading `K` plain (the copy-only factory for K),
  - sink = `SealDataSink(CacheDataSink)`,
  - read source = `SealDataSource`,
  - and run a `CacheWriter` over it, once per cached range of `K`, with a bounded DataSpec (position and length of
    that range).
- That locks the holes, skips what `S` already has (the resume) and never asks K's holes.
- Set `S`'s `exo_len` **before** the copy, not after. With the length known, a crash mid-copy leaves `S` with the
  same guard as `K`.
- Size step 2 for this: about +80 lines.

**M3 - The switch and removal race the player's loaded queue, not just the held set.** (§8.2 steps 5-6, lines 347-350;
§6 line 270)
- The service builds the **whole queue's** `MediaItem`s at load, with the cache key fixed in each `ItemTag`
  (`PlaybackService.kt:1520`, `:2360`, `:911`, `:1365`, from `a.downloads` read at `:928`).
- The held set is only the current item, the next and 75 s ahead (`Offline.kt heldAhead`). A Storytel entry with
  several books (a series) can have a later book migrated while its queue still names `K`. When the kid gets to it,
  `K` is gone, the copy-only read fails, and the book shows "Kan ikke spilles nå" or is skipped.
- The same staleness hits a book whose row the lost path dropped. That is harmless there, since it is "Ikke lastet
  ned" anyway.
- Step 6's `removeResource(K)` also skips `CacheThrough`'s held re-read.

Fix:
- Leave out every book of the entry whose queue is loaded on the phone (not just held items), and take them at the
  next run. Or make the Storytel copy-only `CacheKeyFactory` resolve the row's current key at each open (it runs on
  the loader thread).
- In step 6, use `CacheThrough.removeCopy(K)`. That re-reads the held set, and `heldCacheKeys` already includes the
  library's plain `K`. Leave a refusal to the sweep.
- Add a `SealMigrationTest` row: an unheld book in a loaded queue is not switched.

**M4 - `prepare` case 3 can mint a new record over spans that `removeCopy` declined to drop, which seals one book
under two keys.** (§4.3 step 3, line 243)
- `removeCopy` deliberately does nothing when the player holds the key (`CacheThrough.kt:236`). "Then go to 1" would
  mint a new key while the old spans stay. CacheWriter skips them as cached, so the book becomes a mix: old spans
  decrypted under the new key are noise.
- The mp3 extractor finds false sync words in random data, so the kid can hear static before `bad_media` deletes the
  copy.
- Reachability is narrow (the plan leaves out held items), but the invariant is cheap.

Fix:
- Mint only when `cache.getCachedSpans(S).isEmpty()` holds, re-read right before the record write under `BookKeys`'
  lock.
- After `removeCopy`, check again. If spans remain, throw Transient.
- Pin it in `StorytelDownloadTest`: a refused `removeCopy` with an unreadable record leads to no new record and a
  `network` failure.

**M5 - The tests can't see a plain write, which is the one thing the design exists to stop.** (§13, §14 step 1 lines
486-487)
- Media3 fails open. With a null `cacheWriteDataSinkFactory` and an upstream, `CacheDataSource.Factory.createDataSourceInternal`
  creates a **default plain `CacheDataSink`** (checked in the bytecode). Today's `downloadFactory` relies on exactly
  that (`MediaCache.kt:191-195`). A refactor that drops the `setCacheWriteDataSinkFactory` from
  `storytelDownloadFactory`, or that routes Storytel through `downloadFactory`, writes plain books with every §13 test
  still green.
- The emulator check "no span starts with `RIFF`/`ID3`, and `ffprobe` accepts none" only proves span 0. Every later
  span is headerless (mid-file PCM or mp3 frames), so `ffprobe` rejects it whether it is sealed or not.

Fix:
- (a) A JVM test that builds `storytelDownloadFactory(...)`, calls `createDataSourceForDownloading()` and asserts by
  reflection that the `CacheDataSource`'s `cacheWriteDataSink` is a `SealDataSink` and its `cacheReadDataSource` a
  `SealDataSource`. The playback factory gets the same check.
- (b) A fail-closed guard. Give the plain factories an explicit sink wrapper that refuses any `spec.key` starting with
  `sealed:`, and refuses a Storytel source id. Then route any `sealed:` key (from a tag **or** `customCacheKey`) in
  `ItemMediaSourceFactory` (`:52-57`) to the storytel factory.
- (c) §6ba step 1: pull every span and compare it 16-byte block by block with the fake's file at the same offset (the
  fake knows its bytes). Zero matching blocks is required. Or, for the fake's silent WAV, require each span's
  zero-byte share under 1 %.

**M6 - ASK 2 is framed against the goal it is meant to serve.** (§16 lines 523-528)
- The user's rule is that no plain book may ever be on disk. The recommended answer, "keep it plain until there is
  room", leaves exactly that, with no end date on a full phone. The ASK doesn't say so.
- It also leaves out the option the design's own rules make natural: let the migration evict **played extras**
  (podcast or NRK copies that stream again) to make room, as the download job already does for Storytel's floor.
- The room rule (`free >= cachedBytes(K) + 1.5 GB`) is there to keep the extras floor untouched during the transient
  copy (§8.4). That is a choice to say out loud, not a law.

Fix: reword ASK 2 with three options and the conflict named:
- (a) keep it plain, which breaks "no plain book" until there is room;
- (b) evict played extras first, then seal, and fall back to (a) only if that isn't enough;
- (c) drop the book and download it again sealed.
- Recommend (b).
- ASK 1 is framed right. Add the magnitude: all books at once, several GB on mobile data if that switch is on.

## L

- **L1 - The "never reused" argument (§3.2 lines 168-171) states the wrong invariant.**
  - `SimpleCache` *can* rewrite a range. `removeStaleSpans` drops a span whose file vanished, and the resume then
    fetches it again under the same key.
  - The real invariant is that a given key and position always carry the same plain byte. A rewrite of the same book
    bytes is harmless.
  - It breaks only if the CDN serves different bytes at the same length (the LengthGuard says that "same-length
    re-stitching isn't caught"), and then only against an attacker who kept the old span.
  - Fix: state the invariant as it is. Optionally, drop the record and spans (new key) whenever the 416 or length
    guard fires, which the design already does.
- **L2 - Sink position (§4.2 line 216).** `CacheDataSink` writes at `spec.position + bytesWritten` and ignores
  `uriPositionOffset` (bytecode). A sink that ciphers at `uriPositionOffset + position` disagrees with it whenever a
  caller passes a span-shaped DataSpec, for example a careless migration copy.
  - Fix: the sink uses `spec.position` and throws when `uriPositionOffset != 0`.
- **L3 - Bind the memo to the record (§2.3 line 128, §4.2 line 216).** The sink and source take the key "from the
  memo", keyed by cache key.
  - If a record is dropped while the memo stays (`maybeRemove` after a failed first open, `CacheWriter`'s
    bounded-then-unbounded reopen at `readBlockToCache`), spans can be sealed under a key stored nowhere. That is
    detected later as Lost, and the book downloads again.
  - Fix: at every open, read `vibb_seal` from `cache.getContentMetadata(key)` (in memory) and key the memo by the
    record's bytes (nonce plus wrapped key). With no record, throw.
- **L4 - `heldCacheKeys` knows only the plain key (`CacheThrough.kt:197`).** For a held Storytel book whose row is
  gone (the lost path drops the row even when `removeCopy` declines), the sweep can remove `S` from under the player.
  - Fix: add `sealedCacheKey(...)` for Storytel entries there.
- **L5 - A lost book's error should fail fast.** `SealLostException` is an `IOException`, so `DefaultLoadErrorHandlingPolicy`
  retries it at 1, 2 and 3 s, with a Keystore unwrap each time, before the kid sees "Ikke lastet ned".
  - Fix: `SourceChangedPolicy` (`MediaCache.kt:383-386`) returns `C.TIME_UNSET` for it too.
  - The mapping itself goes through `LocalRenderer.kt:137-138` (`RendererEvent.Error`'s `gated` flag, or a new one).
    `PlayFailure.failureMessage` alone can't see it. Name that in step 1.
- **L6 - StrongBox (§2.1 lines 89-95).** It buys nothing over the TEE against this threat model: the KEK never leaves
  either one, and root on the phone wins either way. It adds 100-500 ms per book open on some phones, and
  `check`'s per-sync unwrap of every book (20 books are seconds in StrongBox). It also brings another family of
  transient errors.
  - Fix: TEE only, as r10 chose, which also keeps one code path for the Lost and Transient rules. Otherwise, at least
    make `check` parse-only, plus one canary unwrap (M1), with the unwrap left to the first open.
- **L7 - The cipher is pinned only on the JVM.** `seal_vectors.json` runs under SunJCE, but the phone runs Conscrypt
  (BoringSSL `ctr32` with carry). The semantics should match, but nothing proves it on the device.
  - Fix: `BookKeys` checks one vector on the first use per process (microseconds) and refuses to seal on a mismatch.
    Assert `update()` output length == input length.
- **L8 - The FBE wording overclaims (§8.3 lines 365-367).** With root on an unlocked phone, a deleted span's blocks and
  its inode nonce can survive on f2fs until GC. "The phone can no longer tie them to a key" isn't guaranteed.
  - It doesn't matter for the threat model, since root can ask the Keystore anyway, but say "best effort; root on a
    running phone is out of scope (§0)".
  - Likewise §3.3's "nothing derived from the plaintext ... would identify the book": the cache key already names the
    cid, so drop that reason.
- **L9 - The seal job's `setRequiresStorageNotLow` (§8.1 line 319)** blocks the no-room partial path, which only frees
  space. On a low-storage phone, plain partials stay forever.
  - Fix: run the job without the constraint, and gate only the copy on the room check (step 1).
- **L10 - An existing mismatch, outside 117 but the same trap.** `ItemMediaSourceFactory`'s comment says "an item
  without a tag ... writes nothing (fails closed)". A tagless item gets `through = null`, though, and a null sink
  factory with an upstream is Media3's default plain `CacheDataSink` (`MediaCache.kt:164-170`). The same goes for any
  NRK or RSS item with no `ref`.
  - Fix: when `through` is null, pass an explicit `DataSink` that drops every write, or call
    `setCacheReadOnly(true)` on that factory.

## Sizes

- Step 1 (about 450 + 400) is plausible. Add H1's alias versioning (record 78 bytes) and M5(a)/(b): about +60 lines of
  code and +60 of tests.
- Step 2 (about 300 + 300) is light once the copy runs through `CacheDataSource`/`CacheWriter` with locking (M2) and
  the loaded-queue guard (M3). Call it 380 + 350, still M-.
- Step 3 is unchanged.

## Resolution (revision 2, 2026-11-18)

The user cut the migration (only emulators hold plain books), so findings that touch only the migration are moot.

- **H1**, applied:
  - "missing" is `getKey() == null` only, never `containsAlias`;
  - every error is classified through its cause chain;
  - the alias is versioned (`music_books_<id>`) and the id sits in the record and its AAD (78 bytes);
  - a key is never generated over an alias that might exist (§2.2, §3.1, §7).
- **M1**, applied:
  - a canary per key gates Lost, so a down Keystore deletes nothing;
  - the Unclassified counter is kept in prefs and reset on success;
  - `prepareSeal` runs before the mint (§2.3, §5, §7).
- **M2**: moot, no migration (the user).
- **M3**: the migration part is moot, no migration (the user). Plain copies are dropped through `removeCopy`, and a
  held book waits for the next sync (§8).
- **M4**, applied: `prepare` re-reads the spans under the lock, and spans that remain after `removeCopy` make it
  Transient, never a new record (§4.3).
- **M5**, applied:
  - the Storytel factories are checked by reflection;
  - the plain sinks refuse `sealed:` keys and Storytel source ids, and `ItemMediaSourceFactory` routes sealed keys;
  - the emulator checks every span's zero-byte share (§4.5, §13, §14).
- **M6**: ASK 2 is moot, no migration (the user). ASK 1 now carries the size note: every book at once, several GB,
  over mobile data if that switch is on.
- **L1**, applied: the invariant is restated as "a position always carries the same plain byte under one data key"
  (§3.2).
- **L2**, applied: the sink ciphers at `spec.position` and refuses `uriPositionOffset != 0` (§4.2).
- **L3**, applied: the record is read at every open, and the memo is keyed by the record's bytes (§2.5).
- **L4**, applied: `heldCacheKeys` includes the sealed keys of held Storytel books (§4.1).
- **L5**, applied: `SourceChangedPolicy` doesn't retry `SealLostException`, and `LocalRenderer`'s `lost` flag maps it
  to "Ikke lastet ned" (§6).
- **L6**, applied: TEE only, as in r10, and the departure from the PLAN's "StrongBox where present" is noted (§2.1,
  §15).
- **L7**, applied: a vector self-check on the phone at first use, with length equality, and a mismatch is Transient
  (§2.5).
- **L8**: the FBE and secure-removal wording is moot with the migration. §3.3's plaintext-hash reason is dropped.
- **L9**: moot, no migration job (the user).
- **L10**, applied in step 1: the plain factories always pass an explicit sink, and a tagless item, or one with no
  `ref`, gets `NoWriteSink` (§4.5).
