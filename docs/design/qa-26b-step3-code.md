# QA 26b step 3 - code review (the account, downloads, local playback, the report, the debug feed, the fake)

Scope: branch `music-2-storytel`, `83026a0..899d807` (`8a5850f` the app, `18596d9` `scripts/fake-storytel.py` +
`music.yml`, `cb4809d` `emulator.md` §6h, `899d807` status), read in full against `26b-storytel.md` §0, §3.2-3.4,
§4.2-4.6, §6, §7, §8 step 3, §9, §13 (M2, M4, M7, M9, L7, L13, L14), step 1's status and fix round,
`qa-26b-step1-code.md` (its notes for step 3) and vibb `pipewire` `1fcadac` (`storytel.py` `sync`, `_download`,
`_free_bytes`, `forget_downloads`; `library.py` 262-292, 372-392; `tests/storytel_sync.py`, `storytel_expand.py`).
Media3 1.7.1's `OkHttpDataSource` and `CacheDataSource` were read in bytecode where the behaviour matters (redirects,
`exo_redir`, the upstream-less cache).

Runs (this review): music `./gradlew testDebugUnitTest --rerun lintDebug -PwarningsAsErrors=true` green, **443 of 443**
(fresh results, not the up-to-date ones); lint adds nothing in a step-3 file (the `UsableSpace` in `Sync.kt:207` and the
`UseKtx` in `Downloads.kt:115` predate the step; `Downloads.freeBytes` suppresses its own with a reason). `python3 -I
scripts/fake-storytel.py --selftest` OK in about 3 s (every step printed, the fixtures, the login, the 302, Range/206/416,
`--slow-mint`, `--drop-at`, `--token-ttl`, `--refuse`, `--no-jwt`, `--locked`). `git status` clean before this file.

Verdict: **good to go after one Medium fix** (the Nytt notification never learns that a new book arrived). No High: no
path lets an undownloaded book reach ExoPlayer or a minted URL reach a log, Room, the report or the cache index, and
the one-`StorytelApi` and off-main conditions from step 1's QA hold. The Lows are small.

## Findings

### M1 (Medium) - a new book never posts the "N nye i Musikk" notification until the next sync

`NewNotifier.update` (`sync/NewNotifier.kt:41-42`) counts the tray's **visible** items with `forRenderer(false)`, so a
new Storytel book counts only once its copy is done (§3.4, correct). But `update` is called from `Sync.runSync`
(`Sync.kt:159`, right after `applyListing` and **before** the download job runs), `clearNews`, and
`PlaybackService.startedPlaying` - never from the `DownloadWorker` (`sync/Downloads.kt:150-161` writes the report, not
the notification). Phase 1 never needed it: a new NRK/RSS episode has a url and is visible at the sync that lists it.

Failure path: the sweep lists book 6 -> the launcher nudges -> the sync marks it new (`newsEligible`, tray) and calls
`update`: count 0 (not on the phone), no notification -> the download job fetches the book (minutes, or several jobs)
-> nothing posts. The notification (and the launcher's badge, which counts its `setNumber`) appears at the next sync:
the next bridge change or the 6 h backstop (`Sync.kt:89`). The in-app bell and NY are right (they read the snapshot
flow; `LibraryViewTest`'s new row tests those, and `NewNotifier.count` - not the post). Design 20's "New content"
promise for a book is the notification; the kid sees it hours late, or never if the tray is acknowledged in the app
first.

Fix: in `DownloadWorker.doWork`, call `NewNotifier.update(applicationContext, store.snapshot())` beside both
`Sync.writeReport` calls (`Downloads.kt:151`, `:160`) - cheap (`update` returns early when the count is unchanged).
Pin it in `GuardsTest` beside the `forRenderer(false)` row ("the download job refreshes the Nytt notification").

### L1 (Low) - a malformed `storytel.json` logs its own content (the password) and aborts the whole feed

`DebugStorytel.seedLogin` (`src/debug/.../DebugStorytel.kt:43-58`) parses with `JSON.parseToJsonElement` outside any
catch; the `finally` deletes the file, then the exception leaves `load` and `DebugFeedReceiver` logs it with its stack
(`DebugFeedReceiver.kt:59`, `Log.e(TAG, "debug feed failed", e)`). kotlinx.serialization 1.8.1's
`JsonDecodingException` message carries the input (`"... JSON input: {"email": "...", "password": "..."}"`, checked in
the jar: `JsonExceptionsKt`). So a trailing comma prints the email and the password to logcat - fake values by the
rule, but the rule is only a comment (§4.6, L13), and a developer testing a debug build on the Jelly Star with the
family's login is the case L13 was about. The same exception also skips `sonos.json`, the library, the listings and
the sync for that broadcast. The `SourceScanTest` pin ("no email in a log line") can't see it: the line is in the
receiver and interpolates nothing.

Fix: in `seedLogin`, `runCatching { JSON.parseToJsonElement(...) }.getOrNull()` and on null log a fixed line
("storytel.json: not a JSON object"), keeping the `finally` delete. Extend the pin: `seedLogin`'s parse sits inside a
`runCatching`.

### L2 (Low) - `DebugStartup` has no `initOrder`, and it applies a leftover base on every start of any debug build

Two small things in `src/debug/AndroidManifest.xml:13-16` / `DebugStorytel.applyBase`:
- Providers are created in `initOrder` order (default 0, unspecified among equals). WorkManager's own
  `InitializationProvider` can start a constraint-met job from its background executor (GreedyScheduler) before
  `DebugStartup.onCreate` has run in the same process. That job reads `debugBase == null`, builds the account for the
  real hosts and logs in at `www.storytel.com` with the seeded fake login: a request to the real Storytel from the
  emulator (exactly what choice 4 prevents) and a refusal stamped for that generation, so the fake then sees no login
  for an hour - a confusing §6h run. Rare, but the fix is one attribute: `android:initOrder="1000"` on the provider
  (higher runs first).
- `applyBase` honours any http(s) base on every process start. On a real phone running a debug build with the real
  launcher (a developer's check), a `config.json` left in the app's external folder sends the family's real login
  (`pwd=` is as good as the password) over plain http through the LAN client to that host, silently, at every start.
  Only adb can put the file there, so this is hygiene: accept only `http://10.0.2.2:<port>` and loopback in `parse`
  (the fake's only homes; a real phone can't reach either), or log the base loudly at every start (it already logs on
  a change).

### L3 (Low) - §6h's secret grep misses Room's WAL and greps a file that doesn't exist; two checks are worded so they can't be done

`docs/testing/emulator.md:622`: `cat no_backup/media/*.exi databases/exoplayer_internal.db databases/music.db`.
- Room opens `music.db` in WAL mode (`JournalMode.AUTOMATIC`), and the platform's SQLite helper behind
  `StandaloneDatabaseProvider` may too: recent rows (a failed book's `failedUrl`, the item errors) sit in
  `music.db-wal` until a checkpoint, so the grep can pass while the database holds a match. `*.exi` is the file index
  Media3 uses *without* a database provider; with `StandaloneDatabaseProvider` there is none, the glob stays literal
  and `cat` complains. Use one recursive grep over the app's private data:
  `adb shell run-as me.vibb.music.debug sh -c 'grep -raE -l "token=|FAKE-|pwd=|k@example|hemmelig" .'` (no file may be
  named; `storytel.bin` is sealed and the WAV spans are zeros) - it covers `databases/*-wal`, `shared_prefs`,
  `files/music/`, `no_backup/` and `renderer.json` at once.
- "**Copy only**: a book plays with the fake stopped (Ctrl+C): no request in its log during playback" - a stopped fake
  has no log. Split it: with the fake running, play a downloaded book and seek around - no `cdn` line appears; then
  stop the fake - the book still plays.
- §4.3 and §6.3 name the case the design was built around (a job stopped at its 10-minute limit mid-book resumes in
  the next job with a fresh mint); §6h has no line for it. Add: `--books 111:300`, and while the CDN line streams,
  `adb shell cmd jobscheduler timeout me.vibb.music.debug` - the next job's `asset 111` mints a new token and the CDN
  shows `206 Range bytes=<N>-`.

### L4 (Low) - three wiring points are pinned by nothing (each passes with its line reverted)

The pure parts are tested; the lines that use them in the Android classes are not, and the project pins such lines in
`GuardsTest`:
- `Sync.kt:173` `markSeen(..., newsEligible(entry, listing.items))` - reverting to phase 1's `url != null` filter
  keeps `NewItemsTest` green and makes no book ever news.
- `Sync.kt:210-212` the report's `storytelEntries` and `reportWaiting(...)` - passing `emptySet()` keeps `ReportTest`
  and `StorytelDownloadTest` green and the card never says `login`/`storage`.
- `Downloads.kt:158` `listOfNotNull(nextRetryAt, run.storytelRefusedUntil)` - dropping the second term keeps every
  test green and the refused login waits for the next sync or the 6 h backstop (choice 7 undone).
Fix: one `GuardsTest` row with the three `contains` checks (and M1's line).

### L5 (Low) - the clean cache index rests on the minted URL's canonical form; pin it

`CacheDataSource` writes `exo_redir` whenever `!dataSpec.uri.equals(upstream.getUri())`, and Media3 1.7.1's
`OkHttpDataSource.getUri()` returns `Uri.parse(response.request().url().toString())` - OkHttp's canonical form, with or
without a redirect. So no-follow (choice 6) is only half of "no token in `exo_redir`": the other half is that the URL
handed to the downloader is already canonical, which `StorytelApi.mintedUrl` (`StorytelApi.kt:268-273`) does today
(`url.toString()` of the parsed `HttpUrl`). A later change that returns the raw `Location` (e.g. to keep the CDN's
exact bytes) would write every token into the index with no redirect at all. The `MediaCache.kt:86-92` comment names
only the redirect.
Fix: one `StorytelApiTest` row over step 1's Location rows: `mintedUrl(x)!!.toHttpUrl().toString() == mintedUrl(x)`,
and a sentence in the `MediaCache` comment ("and the minted URL is canonical, so `getUri()` equals the request").

### L6 (Low, hygiene) - `Snapshot.kt` ports vibb's `entries_for(remote)` without the credit line

`data/Snapshot.kt:69-93` (`remote`, `forRenderer`, the Storytel branch of `visible`) is §1.2's port of `content.py`
`expand_entries` + `PREFER_REMOTE` / `storytel.py` `entries_for(remote)` and names it in a KDoc, but the file has no
MIT credit line (`music/CLAUDE.md`: "each ported file credits it"; `Offline.kt` and `NewItems.kt` do). Add the usual
header line.

## The implementer's choices (the status lists 13; the brief said 10 - all 13 judged)

1. **`DownloadRun` (pure) behind `DownloadIo`**: accept. It is what makes §7's "fetchOne's decisions with a fake minter
   and a fake fetch" possible, NRK/RSS go through the same code and keep phase 1's behaviour (traced line by line:
   the partial-from-another-url drop, the 416 refetch from the same url, the attempts count, the row written before
   the fetch). The cost is L4: the glue left in the worker is unpinned.
2. **The four readers as functions in `StorytelEndpoints`**: accept. Each reads the field, the pin checks the body
   and the callers (`StorytelAccount.kt` twice, `Downloads.kt`, and `SonosController.kt` reserved for 4b). An
   import alias could evade the per-caller check; heuristic, fine.
3. **`StorytelAccount` rebuilt only when (a)/(b) change**: accept. Release: `parse(null)` gives `Endpoints.STORYTEL`
   and `false` on every call, a data-class `equals`, so one instance per process under `synchronized`; the
   single-process manifest (no `android:process`) makes "per process" "per app". Debug: a changed base swaps the
   instance; a mint in flight finishes on the old one (two locks for a moment) - debug only.
4. **`DebugStartup`**: accept - right diagnosis (a receiver-only switch is null in the worker's fresh process), the
   provider is debug-only (`checkReleaseHasNoDebugFeed` scans for `me.vibb.music.debugfeed`), not exported, has no
   permission surface and answers nothing. With L2's `initOrder`.
5. **`storytel.json` seeds generation + 1**: accept (the bridge stores on `!=`, so a real launcher still wins).
6. **Storytel CDN requests never follow a redirect**: accept, with the device check it names. Verified: Media3's
   `OkHttpDataSource` has no redirect logic of its own (only the client's), so the no-redirect clients make a 3xx an
   `InvalidResponseCodeException` -> `http_302`, transient, the next attempt mints again; the server's sanitizer
   takes `http_3xx`. Note it is a deviation from vibb (`_download` uses `urlopen`, which follows) that §9 doesn't list,
   and it has a real failure mode (a geo-routing CDN that 302s to an edge would fail every book, a login per run at
   2 min doubling). If the device check finds a redirect, the fix is not to follow blindly but a thin `DataSource`
   wrapper whose `getUri()` returns the requested URI (no `exo_redir`, L5) - worth one sentence in §9 now.
7. **A refused login brings the job back at the cooldown's end**: accept (with L4's pin). Traced: the worker
   re-enqueues while RUNNING (`APPEND_OR_REPLACE`), a sync in the hour replaces it with an immediate job that mints
   with no request and re-adds the delayed one.
8. **`Unreachable` goes on to the next book**: accept (vibb parity; the per-item `downloadWaiting` check ends the run
   when the network goes).
9. **`Failed(401)` gets `http_401` and stops; a mint failure writes a not-done row**: accept. The row holds no bytes,
   is not in `onPhone`, is pruned like any row outside the keep set, and makes `mayRetry` wait by Storytel's classes.
10. **`state()` on every report write, `usableSpace` for the floor**: accept. Both run on IO (`writeReport` is
    `withContext(IO)`; its callers are the sync and download workers). vibb's `_free_bytes` returns "huge" on an error
    and ours `0` (blocks) - `noBackupFilesDir` always exists; not worth a change.
11. **`NewNotifier.count` takes the snapshot it is given; `update` passes `forRenderer(false)`**: accept - but see M1:
    the rule is right, the call is missing after a download.
12. **No log line in `StorytelAccount`**: accept (the "no `Log.` in `storytel/`" pin is the stronger rule).
13. **The fake's defaults** (20/5/5 MB, a wrong login refused like `--refuse`, an unknown cid answered like a locked
    one, `--print-listing` for entry 5): accept. `00ff00ff00ff00ff` and entry 5 match `server/testdata/
    music_library.json` (checked).

## The spec problems the status lists (6)

All six are right; each is a text fix for step 5's spec touch:
1. §8 step 3's "`no_backup/media/*.exo` metadata": right, the metadata is in `databases/exoplayer_internal.db` - and
   with L3, grep the WAL files too (better: the whole data dir).
2. §4.6's receiver-only switch: right (choice 4); §4.6 should name `DebugStartup` and its `initOrder`.
3. §0 accepts a CDN redirect target in `exo_redir`, the brief forbids following: right; §0 should say "never
   followed; a redirecting CDN fails as `http_30x`" and §9 gain the row (choice 6).
4. §4.3 step 2 vs §4.5 on `LoginFailed`: right, built as §4.5 (M4's decision); §4.3 should name `NotConfigured` and
   `Refused` only.
5. §8's "bookmark's book first" needs a drop and re-add before 4b: right; §6h does it that way.
6. §4.2's context-free `mint`: right (`StorytelAccount.get(context).mint(...)`).

## Checked and fine

- **Downloads (§4.3)**: a fresh mint per attempt and a second one for the 416 retry (L7), the row and `mayRetry` keyed
  by `storytel:book:<cid>` (`DownloadPlan.kt:69-71`, `DownloadRun.kt:102-103`), never the URL (tested; the
  `item.url` mutation the status names turns four rows red). The resume is Media3's: `ProgressiveDownloader` ->
  `CacheWriter` reads the cached spans under the item key and opens the upstream at the first hole with
  `Range: bytes=N-`, whatever the URI; a dropped connection or a cancelled job closes the `CacheDataSource`, which
  commits the partial span. `LoginFailed`/`NotConfigured`/`Refused` stop Storytel for the run with no row and no
  item error while an RSS episode goes on; `NoAudio` -> `no_audio` (a day), `Failed` -> `http_<status>`,
  `Unreachable` -> `network` (2 min doubling) through `retryDelayFor`. The 1.5 GB floor before each book, Storytel
  only (`DownloadRun.kt:88-93`). Mobile data follows the parent's switch through the same constraint and per-item
  check as every download (L14). Keep = first N in reading order + bookmark + next (`storytelKeepSet`), prune by
  phase 1's rules (a book that left the list, every book of a dropped entry: `OfflineTest`), download order bookmark
  first then reading order (tested with `newest_first`, which phase 1's order would fail). News: `newsEligible` makes
  a Storytel book eligible though url-less.
- **WorkManager / Doze**: the run is `DownloadWorker` (a `CoroutineWorker` on IO, unique work, `UNMETERED` or
  `NOT_ROAMING` by the switch, storage-not-low, no battery or charging constraint - the device check "Downloads under
  Doze" decides that, §6.3). At the 10-minute limit the coroutine is cancelled, `runInterruptible` interrupts the
  Okio read (`InterruptedIOException`), the partial is committed, `fetchFrom` rethrows the cancellation (no failure
  recorded), WorkManager reruns the stopped work, and the next job mints again and resumes. The floor is read before
  each book's mint. Playback reads other keys of the same `SimpleCache` meanwhile (thread-safe).
- **One `StorytelApi`, off main**: `StorytelApi(` appears only in `StorytelAccount.build` (pinned); `get` is
  `synchronized`; `mint` is `withContext(IO)`; `state()` and `StorytelLogin.load` (the Keystore) run only inside
  `assetUrl` (IO) and `writeReport` (IO). Nothing calls either from main; the only main-thread work added is
  `DebugStartup`'s file read (debug).
- **Local playback (§4.4)**: `mediaItem` gives a book `storytel:book:<cid>` + `customCacheKey` + `copyOnly`; the
  factory builds a `CacheDataSource` with no upstream, which Media3 replaces with `PlaceholderDataSource` (fails to
  open, checked in bytecode). An undownloaded book never reaches ExoPlayer: the queue is `order()` of a phone
  snapshot (done copies only), `playable` needs a copy for a url-less item, `storedPlan` filters by the visible keys.
  Even an untagged item with that URI fails closed (`OkHttpDataSource` can't parse `storytel:`). A pruned copy still
  in a queue fails as `CANT_PLAY` and moves on (phase 1's path). Every snapshot the service, the screens and the
  notifier read is the phone's view; on a room a Storytel book is still dropped as "withdrawn" by
  `speakerDropReason` until 4b.
- **The report (§4.5)**: `storytel` = `none`/`refused`/`ok`, `waiting` per Storytel entry through the pure
  `reportWaiting` (`login`, `storage`, else the shared rule), the field set unchanged (`music_state.json` untouched;
  the server's `word()` and waiting filter take `refused` and `login`); no secret.
- **Secrets**: no log line in `storytel/`, `sync/` or `media/` prints a URL; `From.Url` and `Mint.Url` print nothing
  of it; Room holds `storytel:book:<cid>` only; WorkManager carries no input data; no `DownloadManager` (no download
  index with a URI); `exo_redir` is never written (no redirect followed, canonical URL - L5); the fake and the
  fixtures hold `k@example.invalid`/`hemmelig`/`FAKE-JWT-1`/`FAKE-SIG-<n>` and `10.0.2.2`/`127.0.0.1` only.
- **Debug pieces (M2)**: `debugBase` declared once, read only by the four functions (pinned), assigned only in
  `DebugStorytel.kt`; `DebugFeedReceiver` keeps `android.permission.DUMP` (shell only) and `goAsync` + a thread;
  `storytel.json` deleted in a `finally`.
- **The fake**: `python3 -I`, stdlib, GPL header with vibb's credit, binds `127.0.0.1` and advertises `10.0.2.2`, the
  answers built from the step-1 fixtures (the `pwd` pinned to an `aes_vectors.json` vector that `StorytelApiTest`
  computes - stdlib has no AES), the bearer checked, a per-mint token per book, TTL from the mint, `--drop-at` on each
  book's first transfer, Range/206/416 with `Content-Range`; the selftest exercises every flag against itself.
  ("bookshelf form Content-Type" from the brief doesn't apply: the phone never reads the shelf, so the fake has no
  bookshelf route.) `music.yml`'s filter and its selftest step mirror fake-sonos's; the fixtures are under `music/`
  already.
- **Tests vs §7**: `storytel_sync.py` 1/1b (every book minted, `no_audio` skips one), 2 (one mint per attempt, the row
  names the book, the partial survives a new URL), 3, 4, 5 (floor before downloading, and before each book);
  `storytel_expand.py` 1 (step 1's row with books 1 and 3, plus `forRenderer` both ways), 3/4, 8; `storytel_routes.py`
  5; M4, M7, L4, L7 rows. They assert behaviour, and the discriminating ones fail with phase 1's rules
  (`keepSet` instead of `storytelKeepSet`, `wantedDownloads` instead of `storytelWanted`, the 416 without a re-mint).
  What the fakes can't prove, and §6h must: the Range resume against a new token never restarts from 0 (Media3's
  `CacheWriter`), the partial survives a dropped connection and a job timeout (L3's added line), nothing reaches the
  fake while a downloaded book plays, and the cache index and Room hold no token (L3's grep).
- **Phase 1 / 1b**: NRK/RSS plans are identical (`fetchable` = `url != null`, `sourceId` = the url, the same
  `wantable`), `DownloadRun` reproduces `fetchOne`/`attempt` step for step, the HLS path is untouched but for
  `followRedirects = true`; `Snapshot` defaults to `remote = false`, so every `sonos/` caller (`SonosSuspension`) reads
  what it read before.
- **Hygiene**: `-PwarningsAsErrors=true` green, lint unchanged, no new strings (the card's words are the server's),
  vibb credited on `StorytelAccount.kt`, `DownloadRun.kt`, `StorytelDownloadTest.kt` and the fake (L6 for
  `Snapshot.kt`); §6h matches the fake's flags, defaults, log lines and port.

## Not worth fixing

- A new book outside "first N" joins the tray and is never shown (never downloaded); it only takes a tray slot.
- A locked book with a partial copy keeps it until the book leaves the list (a 403 before any bytes is the norm).
- `LoginFailed("no jwt")` reads as the shared waiting (nothing on the card while Storytel answers 200 without a JWT):
  M4's decision; the phone's `storytel` stays `ok`.
- `freeBytes` 0 on an error blocks Storytel where vibb's "unknown" doesn't; `noBackupFilesDir` always exists.
- No progress inside a book on the card (vibb's 20 MB heartbeat): `have`/`want` count books.
- The floor isn't tested with an RSS item beside it (`if (storytel)` in `fetchOne`, read).
- The fake: `--drop-at` closes at 0 bytes when a book's first transfer after a fake restart starts past the cut;
  `logins` counts without the lock.
- The four-reader pin can be evaded by an import alias; heuristic like the log scan.
