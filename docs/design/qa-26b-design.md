# QA 26b design review - Storytel (music phase 2) (2026-10-12)

Scope: `docs/design/26b-storytel.md` at `7d66122` (branch `music-2-storytel`), read against the root, music, server
and launcher `CLAUDE.md`, design 26 (the brief, review item 4 "decided: option A" and "Accounts and sessions", "How to
work"), designs 20 (decision sections), 21, 21b, 26a (§11 is the form), and checked line by line against vibb
`pipewire` `1fcadac` (`pi/vibb/storytel.py` all 828 lines, `bookmarks.py`, the cited parts of `library.py`, `content.py`,
`daemon.py`, `pi/web/app.js`, `tests/storytel_{api,expand,routes,sync,bookmark_sync,subprocess,sonos}.py`) and the code
the spec builds on (`server/src/music*.rs`, `handlers/music*.rs`, migrations to 0065; `music/app/src/main/java/me/vibb/
music/{sync,media,logic,model,data,bridge,playback,sonos}`; `launcher/.../music/`; the contract files). Line numbers are
`7d66122`'s. QA wrote no product code.

**Verdict: buildable after a short fix round.** The shape (option A, the server lists, the phone mints and downloads,
the marker in renderer.json, adopt by `consumableId`) is right and is vibb's. Two Highs: an instruction that would brick
every upgraded server (H1), and the per-renderer visibility missing the adopt and phone -> room paths, which stops the
speaker on a restart while an undownloaded book plays (H2). The Mediums are spec edits, not redesigns (M8 is a step
split, M9 a device check). Counts: 2 High, 9 Medium, 14 Low.

## High

1. **H1: §2.8 tells the implementer to edit an applied migration.** "the comment "ok (it has a URL)" becomes "ok
   (listed and playable; a Storytel book has no URL)"": that comment exists only in `server/migrations/0051_music_sweep.sql:71`.
   The server runs `sqlx::migrate!("./migrations")` (`server/src/main.rs:1121`, `.migrate()` at `:242`), which checks the
   checksum of every applied migration: one changed comment makes `migrate` fail with a version mismatch on every
   existing install, so the server doesn't start after the update. Every test runs on a fresh database, so no suite
   catches it. **Fix:** never touch 0051; put the note in 0066's header comment and on `music_sweep::Item`'s doc; add
   to §2.8 "applied migrations are never edited (sqlx checksums)".

2. **H2: visibility per renderer (§3.2, §5.3, §5.6) misses the adopt and phone -> room paths.** The spec lists
   `queueFor/activeFor/playEntry/playItem/restore/toRoom` and "the standalone suspension `true`", but:
   - **Adopt** (`PlaybackService.lookAtRoom`, `:1196`) loads the plain snapshot (`load()`, `:489`) and looks the
     entry up in `snap.entries` and `snap.order(entry)` (`:1202-1203`), and `adoptedQueue` does the same (`:1257-1260`).
     Locally a Storytel book without a copy is invisible and a series with no downloaded book isn't in `entries` at all
     (`Snapshot.kt:71-85`). So after an app restart while an undownloaded book plays on a room (the headline Storytel-on-
     Sonos case: "a book need not be downloaded at all", vibb `storytel_sonos.py` 2, 7, 13), the entry or the item isn't
     found, the queue is null and `lookAtRoom` sends `sonos.stopUnadopted()` (`:1213`): the kid's book is stopped, and
     §8 step 4's own emulator check ("the app killed and restarted while Stua plays: adopt by `consumableId`") fails.
   - **Phone -> room** (`toRoom`, `:1316`): with a queue loaded, the room's plan is built from `a0.items` (`:1331-1336`),
     the local queue = the downloaded books only. The room then skips every undownloaded book, and its queue ends early
     at the last downloaded one, where 1b's natural end runs vibb's `clear_state` (partial positions cleared). vibb
     re-expands with `PREFER_REMOTE` (`daemon.py:1687-1688`).

   **Fix:** name every call site in §5.6 with the renderer it passes: `lookAtRoom` and `adoptedQueue` read
   `forRenderer(true)` (the stored room is a room); `toRoom` re-plans over the entry's remote order, keeping the current
   item and its position (not `a0.items`); `SonosSuspension.entryMatcher`/`bookmark` `true`; `restore`/`playEntry`/
   `playItem` by `renderer.kind`; `comeHome`'s `homePlan` `false` (it is the phone). Tests: a Storytel adopt of an
   undownloaded book keeps playing (no Stop); phone -> room with books 1 and 3 downloaded puts all five on the room.

## Medium

1. **M1: a renderer.json writer the spec doesn't name would persist the signed URL** (§0, §5.3). `lookAtRoom` writes
   `if (uri != choice.uri) home.write(choice.copy(uri = uri, ...))` (`PlaybackService.kt:1224`). Once adopt takes the
   live TrackURI (§5.3) the live uri differs from the marker, so this line writes the minted URL into renderer.json - and
   on the no-stored-uri branch (`:1210`) it does already. The spec names only `host.played` (`SonosRenderer.kt:92-94`;
   `followed`, `:97-99`, keeps the stored uri, fine). **Fix:** every write of `RendererChoice.uri` goes through
   `storedUri(entry, item, uri)` - `host.played` and `lookAtRoom` (`:1224`); a `GuardsTest` pin; a `SonosLifecycleTest`
   that after a Storytel adopt renderer.json still holds `storytel:book:<cid>`.

2. **M2: the emulator chain (§4.6, steps 3-4) can't work as written.** The fake answers at `10.0.2.2` (10/8, private):
   `StorytelHttp` is built over `MediaCache.guarded` (refuses private names and connections, `MediaCache.kt:67-83`),
   downloads use `downloadFactory(publicOnly = true)` (§4.3 step 3), and §5.1 refuses a minted URL that is a private
   literal - so step 3's downloads and step 4's "a book not downloaded plays on Stua" all fail. The spec only says "its
   CDN to the LAN client". A hook that relaxes the address guard is security-relevant and must be exact. **Fix:** one
   debug-only `StorytelEndpoints.debugBase` that (a) points the login and API at the fake, (b) gives `StorytelHttp` the
   LAN client, (c) makes Storytel downloads `publicOnly = false`, (d) lets §5.1 accept a private-literal minted URL - all
   four reading that one field, assignable only from `src/debug` (`SourceScanTest` pins both). `OkHttpStorytelHttp`
   takes its `OkHttpClient` as a parameter (StorytelHttpTest against `TinyHttp` on 127.0.0.1 needs it too).

3. **M3: §2.4 and step 2b miss places that filter Storytel out.** Beyond the ones named (`LIBRARY_ENTRIES`
   `music.rs:696`, `cover_in_library` `:1007/:1015`, `device_listing` `music_sweep.rs:777`, `check_entry` `:1015`):
   `music_sweep::schedule`'s `WHERE e.source IN ('nrk', 'rss')` (`music_sweep.rs:473`) - a Storytel entry is never
   scheduled; `handlers/music.rs:2269` `SweepView::card` returns `None` (no status, no "N books", no per-phone lines);
   `:2459` `check_now` refuses ("Only NRK and RSS entries are checked"); `:2573` `/music/cards`; `:2487` `save_offline`
   (the library card's offline select: §2.3 gives Storytel's choices and 0 -> -1 only for the entry page). Step 2b's
   files don't include `handlers/music.rs`. Also `LIBRARY_ENTRIES`' `IN ('nrk', 'rss', 'own')` must gain `'storytel'`
   (§2.7 shows only the added `AND`). **Fix:** add these to §2.4/§2.7 and step 2b's files; acceptance "Check now on a
   Storytel card lists the series".

4. **M4: `waiting: login` and the 1.5 GB floor never reach the report** (§2.5, §4.3, §4.5). `Sync.writeReport`
   (`Sync.kt:200-207`) computes one `waiting` for every entry from the network caps and `LOW_STORAGE_BYTES` (500 MB,
   `:62`); the worker's decisions aren't stored. So a phone without a login or between 0.5 and 1.5 GB free reports
   `waiting: null` (or `wifi`) for its Storytel entries. **Fix:** a pure `storytelWaiting(state, freeBytes)` applied per
   Storytel entry in `writeReport` - `login` when `StorytelAccount.state() != "ok"`, `storage` below the floor, else the
   shared rule - pinned in ReportTest. Say that the floor stops Storytel books only (vibb's floor is Storytel's own,
   `storytel.py:701, 789-791`), not the NRK/RSS downloads.

5. **M5: "Lydbok · del N av M" counts the visible books** (§3.5). `hint()` and `overallProgress()`
   (`LibraryView.kt:44-56`) get `snap.order(entry)` (`UiModel.kt:30`) = the downloaded books locally: book 3 of 5 with
   books 1 and 3 downloaded reads "del 2 av 2"; a bookmark on an undownloaded book (heard on Sonos) isn't `started`, so
   the cover says "fortsetter der du slapp" with no progress line. **Fix:** for Storytel, N, M, `started` and the
   progress line (and the player's "Del N av M") come from the listed reading order (`snap.listed`), not the visible
   list; LibraryViewTest rows for a partial download and a bookmark on an undownloaded book.

6. **M6: nothing gates a play between the mint and the SOAP** (§5.1, §5.2). `playIndex` checks `mayPlay()`
   (`SonosController.kt:689`) and queues the job; the generation is checked only on the result (`:734`). The mint adds
   up to 10 s before `transport.play`, so: a superseded play (a step during a mint) still mints and sends
   `SetAVTransportURI` + `Play`; a play queued before `onDestroy` runs during the actor's drain (`SonosActor.close`,
   `DRAIN_MS` 30 s), possibly after the standalone suspension's park, which waits only `TAIL_WAIT_MS` 5 s for the tail
   (`SonosSuspension.kt:24`) - a book starts on the speaker after bedtime. `HANDOFF_MS` 25 s also outlives the
   receiver's `SUSPEND_BUDGET_MS` 9 s (`MusicApp.kt:89`), fine on the service path but worth saying. **Fix:** the play
   job checks a thread-safe token (generation, closed, `playAllowed`) after the mint and before `SetAVTransportURI`, and a
   superseded job skips the mint; recheck `DRAIN_MS >= MINT_BUDGET_MS` + a hushed play. SonosControllerTest: a step during
   a mint gives one `SetAVTransportURI`; a suspension during a mint gives no `Play`.

7. **M7: the phone's failure classes** (§4.1, §4.3, §3.3). (a) `NoJwt` and a transport failure during the login map to
   `Unreachable` -> item error `network`, and the next book logs in again: a 20-book series is 20 logins per run, each
   retried 2 min doubling. vibb's `sync` stops on a login failure (it catches only `OSError`, `storytel.py:802-807`;
   "no jwt" is a `RuntimeError`, `:317-319`). (b) `NoAudio(status)` is permanent for a day whatever the status, so an API
   5xx, a 429 or a 401 after the re-login parks every book for a day. **Fix:** a `Mint.LoginFailed` (no JWT, login
   unreachable) that stops Storytel for the run like `Refused` (no item errors, `waiting: login`); asset answers:
   3xx without `Location`, 403, 404, 410, 451 -> `no_audio` (permanent); 401 after the re-login, 408, 429, 5xx ->
   transient `http_<code>`. StorytelApiTest/StorytelDownloadTest rows.

8. **M8: step 4 is too big for one sitting** (§8). 26a split its step 3 for the same reason (26a §11 M8). Step 4 holds
   the Room version 2 auto-migration and schema JSON, art for every source (independent of Storytel), the mint in the
   actor, `sameStream` in four readers, adopt's live uri, visibility per renderer in the service and the UI, coming home,
   `HANDOFF_MS`. **Fix:** 4a - `items.art` (MusicDb v2, `2.json`, `Sync`, `PlayItem`, `PlayBodies` art for every source;
   the schema test, PlayBodyTest); 4b - Storytel on Sonos.

9. **M9: phone realities for gigabytes aren't measured** (§4.3, §6.3, device checks). A series is several GB; a
   WorkManager job runs at most 10 min, with the screen off only in Doze maintenance windows, and under App Standby's job
   quotas if the app falls to the rare/restricted bucket. The launcher has already seen WorkManager not firing overnight
   (`launcher/CLAUDE.md:477`). The "resumes in each job" design is right; nothing checks the rate. **Fix:** a device check:
   a 5-book series, screen off overnight, on and off the charger, with `adb shell am get-standby-bucket me.vibb.music` and
   `dumpsys jobscheduler`, and the expected outcome written down; the card's have/want shows slow progress; if it stalls,
   a user decision (e.g., downloads only while charging, or design 19b's Doze exemption for the music app).

## Low

1. **L1: `normalize_shelf` details** (§2.1). `seriesInfo.id` must be stringified like `model.id` (a JSON number in
   decimal, a string as is - `serde_json::Value::to_string()` would add quotes and every series target would fail the
   grammar and be dropped); Python's falsy rules (`sid` "" or 0 -> a standalone book; `orderInSeries` null -> 0); `geo`
   is the abook format's `isGeoRestricted` (`storytel.py:460`); a consumable id seen twice keeps the first (21b's rule),
   or `music_items`' primary key (`0051_music_sweep.sql:91`) fails the check's write; a missing `model.id`
   is skipped where vibb writes `"None"` (`:453`) - a §9 row.
2. **L2: the listing's anchor** (§2.4). "`anchored_at_start = true` ... `keep_end`/`capped` unset" contradicts the code:
   `finish()` and `device_listing` derive the anchor from `keep_end == "first"` (`music_sweep.rs:2074`, `:791`). Set
   `keep_end = 'first'` for Storytel (harmless: `effective_order` looks for `/serie/`).
3. **L3: Add's repeated `target`** (§2.3) needs `Form<Vec<(String, String)>>` as `handlers/music.rs:3066` does;
   `FormMap` is a `HashMap` (`:35`) and keeps one.
4. **L4: the probe** (§2.3): it waits for the one gate (behind a feed fetch of up to 120 s), then login 20 s + shelf
   30 s - bound it (e.g., 45 s, then "Saved, but Storytel couldn't be reached"); `Account::login(state)` reads the DB, so
   the probe needs a `login_with(email, password)`; `login_error` holds a code, never text; `KeyUnusable` needs its own
   code and card text (§2.1 maps only `storytel_off`).
5. **L5: Check now and the shelf cache** (§2.4): Check now through `shelf(300 s)` misses a book added in Storytel's app
   less than 5 min after the last fetch (the owed device check) - use `max_age = 0` for Check now; say the cache's clock
   is `tokio::time::Instant` like the JWT (the tests' `Sweep::now()` jumps).
6. **L6: the minter's signature** (§4.2, §5.1, §7 storytel_sonos 3): `mint(cid, MINT_BUDGET_MS)` vs "one call with 6 s"
   vs `mint(cid, timeoutMs, deadlineMs)`. Settle on asset 6 s + deadline 10 s, and say the login `Mutex` wait counts
   against the deadline (a download's login holds it up to 20 s).
7. **L7: `http_416`** (§4.3): phase 1's retry reuses the URL (`Downloads.kt:148-152`); vibb calls the token single-use
   (`storytel.py:799-801`) - re-mint before the retry from 0.
8. **L8: the phone's User-Agent** (§1.1): `VibbMusic/<version>` is set on MediaCache's `OkHttpDataSource.Factory`
   (`MediaCache.kt:88-91`), not on the client; `OkHttpStorytelHttp` must set it itself (else `okhttp/x`).
9. **L9: `toString` and `Debug`** (§0): `StorytelLogin.Login` (phase 1) is a data class whose `toString` prints email and
   password; the new `StorytelRequest` (the `pwd=` URL), `StorytelResponse` (the JWT body) and `Mint.Url` would print
   theirs. Redacted `toString()` and a pin; on the server no `Debug` on the JWT holder or `StoredLogin`.
10. **L10: logs outside the code paths** (§0, §5.7): reqwest logs each redirect's URLs at `debug` (`RUST_LOG=debug`
    prints `login.action?...pwd=`; the default is `info`, `main.rs:132-135`) - floor `reqwest=info` or document it; Media3's
    `CacheDataSource` stores a redirected upstream URI in the cache index (`exo_redir`) if the CDN redirects - say so.
11. **L11: §9 misses three deviations**: phase 1's keep rule deletes finished books outside first N + bookmark + next
    (vibb never deletes single books, only the whole target at logout or removal); vibb's picker adds `order:
    oldest_first` (`app.js:978`), here `auto`; the missing-id skip (L1).
12. **L12: step 2b's test churn**: other music tests string-replace in `music_library.json` (ContractFilesTest's H1 and
    cache tests assert `entryIds == {1, 2, 3}` and three entries; `OfflineTest.kt:115`, `BookmarksTest.kt:55`) - name them.
13. **L13: the debug feed's `storytel.json`** (§4.6) puts a plaintext password on shared external storage: fake values
    only (`k@example.invalid`), deleted once read.
14. **L14: mobile data** (§4.3): with the parent's mobile-data switch on, GB-sized books download over mobile data
    (design 20 decision 4 covers "downloads and streaming") - confirm with the user or say it on the card.

## Checked and fine

- **AES vectors** (§7): `openssl enc -aes-128-cbc -K <hex of VQZBJ6TD8M9WBUWT> -iv <hex of joiwef08u23j341a>` gives
  `AC6C4AAACBF142D09469371FC6BB8BE6` ("hemmelig", vibb `storytel_api.py:41`), `CC463B52007F99B86F54B5776C8018CF` (""),
  `2C9375205F4CAAD6C54EE8424656C741FE9BB4A407D56646E9CD60C083BF84A3` ("0123456789abcdef") and
  `4A2C4FFEE84E168CEAEB27B612E2EB30` ("pass æøå", UTF-8) - all four as the spec says.
- **The behaviour map** (§1) against `storytel.py` line by line, and every cited range of `library.py`, `content.py`,
  `daemon.py` and `app.js` (the cache choices -1/5/10/20, the kids filter, `in_library` vs `downloaded`). **The test
  table** (§7): every assertion of the seven vibb files lands or is a §9 row, except the small gaps in L1/L11.
- `aes` 0.9.3 and `cipher` 0.5 are in `Cargo.lock`, no `cbc` crate; `form_urlencoded` is already a direct dependency;
  `serde_json` has no `indexmap` (no `preserve_order`), so the document-order visitor is needed as §11 says;
  `reqwest::Error::without_url` exists and `network_error` does put the URL in today (`music_net.rs:340-355`).
- `StoredLogin` is `pub(crate)` (`handlers/music_api.rs:222`); `word()` already accepts `refused`; `music_state.json`,
  `music_listing.json`, `bridge_contract.json`, the AIDL and the policy snapshot need no change; the library snapshot is
  the one that does.
- **No launcher code** holds: `libraryHead` passes every non-`own` entry's `items` (`MusicPlan.kt:112-118`), the
  listing courier is source-blind, and `music_state` values pass through verbatim (`MusicPlan.kt:222`).
- **Resume across a re-mint**: `MediaCache.keyFactory` keys a progressive item by `<entry key>:<item key>` whatever the
  URL; `CacheWriter` requests only the uncached range with `Range`; the only thing that would discard the partial is
  `fetchOne`'s `old.url != url` (`Downloads.kt:145`), which the source id fixes (§3.3). A `CacheDataSource` without an
  upstream gets Media3's placeholder (fails to open), so `copyOnly` never dials out. `playable()` is true on a room
  (`PlaybackService.kt:630-636`), so undownloaded books aren't skipped there.
- The older-app path: `LibraryParser` keeps an unparsed entry's id (`rawIds`), so a 1b app hides Storytel and prunes
  nothing (§3.1). Step order 1 -> 2a -> 2b -> 3 -> 4 has no forward dependency; 2b's three suites change in one commit.
