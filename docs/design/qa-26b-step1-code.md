# QA 26b step 1 code review - the Storytel core (2026-10-12)

Scope: branch `music-2-storytel`, `685a6ae..2669c08` (`384cd5d`, `19041df`, `98beff2`, `2669c08`) under `music/`: the new
`storytel/` package (`StorytelApi.kt`, `StorytelHttp.kt`, `StorytelEndpoints.kt`), `logic/Storytel.kt`, the
`Source.STORYTEL` changes (`Library.kt`, `PlayOrder.kt`, `NetGate.kt`, `LibraryView.kt`, the screens, `UiModel.kt`,
`MusicRoot.kt`), `StorytelLogin.Login.toString`, the strings, the fixtures and the tests, plus the step 1 status in
design 26b. Read against design 26b (§0, §1.1, §3, §4.1-4.2, §6, §7, §8 step 1, §9, §13 and the status's twelve
choices), `qa-26b-design.md` (M5, M7, L6, L9), the CLAUDE.md files, and palchrb/vibb `pipewire` `1fcadac`
(`pi/vibb/storytel.py` 220-417 and 700-828, `pi/daemon.py` 2084-2105 and 4657-4672, `tests/storytel_api.py`,
`storytel_expand.py`, `storytel_sync.py`, `storytel_routes.py`). `server/` is out of scope. Paths are under
`music/app/src/` unless they say otherwise; line numbers are `2669c08`'s.

Build, here: `./gradlew testDebugUnitTest -PwarningsAsErrors=true --rerun` green, 411 tests in 35 classes, 0
failures; `./gradlew lintDebug -PwarningsAsErrors=true` green (its one warning in a touched file, `PluralsCandidate`
on `some_downloaded`, predates the step). The AES vectors re-run with `openssl enc -aes-128-cbc` and vibb's key and IV:
all four of §7 match byte for byte, and 15-byte, 32-byte (a full padding block) and 4-byte-UTF-8 inputs agree with JCA's
`PKCS5Padding` too. Mutations (each reverted): the cooldown back to vibb's `1 until` turns `10b` red; dropping the
re-read under the login lock turns the L6 test red; dropping `StorytelRequest`'s `toString` leaves `SourceScanTest`
green (Low 2) and only `StorytelApiTest`'s behavioural check catches it.

Counts: no High, no Medium, 8 Low. The step does what §8 step 1 asks, holds against vibb line by line except where
noted, and changes nothing for NRK, RSS or own files.

## Low

1. **Low: a refused login can throw out of a mint.**
   - Where: `main/java/me/vibb/music/storytel/StorytelApi.kt:205` calls `cooldown.setRefused(...)` bare; `refusedAt` is
     wrapped (`:219`), the seam is wrapped (`:197-201`), the doc says "Never throws" (`:103`).
   - vibb swallows it: `_cooldown_set` catches `OSError` (`storytel.py:285-289`).
   - Failure path: step 3's `CooldownStore` writes `storytel_refused.json` with tmp + rename; a full disk or a failed
     rename throws `IOException` out of `assetUrl`. In the download worker the job fails and, worse, the stamp is
     missing, so the next run logs in with the refused password again - the hammering the cooldown exists to stop, for
     as long as the write fails. In the Sonos actor the job's `run` catches it (`SonosActor.kt:76-82`), so only the
     contract breaks there.
   - Fix: `runCatching { cooldown.setRefused(stored.generation, at) }` and still return `Refused`. Test: a store whose
     `setRefused` throws -> `Mint.Refused`, no exception.

2. **Low: the `toString` pin can be satisfied by the next class.**
   - Where: `test/java/me/vibb/music/SourceScanTest.kt:138-150`, used at `:153-166`. `body()` takes
     `text.indexOf('{', start)`, the next brace anywhere after the header, and the header regex `[^{]*\)` can run across
     declarations.
   - Reproduced: with `StorytelRequest`'s body removed (a bare `data class`, whose generated `toString` prints the
     `pwd=` URL and the bearer), `body()` returns `StorytelResponse`'s body, which has a `toString`, and the pin stays
     green. `class Url(` likewise borrows `StorytelApi`'s body (it holds `Jwt`'s `toString`). The status says "a mutation
     dropping one turns it red"; that holds only while the braces stay.
   - Today the behavioural checks in `StorytelApiTest` (`secrets never reach a toString`) cover `StorytelRequest`,
     `StorytelResponse`, `Mint.Url` and `Login`; `Jwt` (private) has only this pin, and for it the pin works.
   - Fix: match the header up to its own opening brace (`Regex.escape(c) + "[^)]*\\)\\s*(:[^{\\n]*)?\\{"`, the body
     from that brace) and fail when the class has no body. Prove it with the mutation above.

3. **Low: the minted `Location` goes through the listing's `safeUrl`.**
   - Where: `StorytelApi.kt:145` -> `main/java/me/vibb/music/model/Listing.kt:48-55` (`java.net.URI`, at most 2000
     characters, `MAX_URL` at `:23`).
   - 1b's code QA (L6) moved the speaker filter to `toHttpUrlOrNull()` because `java.net.URI` refuses URLs that OkHttp
     and ExoPlayer take (an unencoded `|`, `^`, a space). Here a refusal makes the 302 `NoAudio` - item error
     `no_audio`, a day's wait - and it would hit every book of every series at once if the CDN's token shape trips the
     parser. The 2000 cap is the listing's storage rule; a minted URL is never stored, and signed CDN URLs (CloudFront's
     custom policy, for one) can run long. Nothing in vibb checks the Location at all.
   - Fix: a `mintedUrl(location)` in `StorytelApi`: `toHttpUrlOrNull()`, scheme http(s), a host, no username or
     password, at most 8 KB, returning the parsed `HttpUrl.toString()` (canonically encoded, which the speaker also
     needs in step 4b); §5.1's check uses the same function. Tests: a Location with `|` and with a space is a `Url`;
     userinfo, a relative and an `ftp:` Location stay `NoAudio`.

4. **Low: a persistent 401 costs one login per book.**
   - Where: `main/java/me/vibb/music/logic/Storytel.kt:109` (`mintStopsRun` leaves `Failed` out) and
     `StorytelApi.kt:112-119` (the token that just got the second 401 stays the kept JWT).
   - Failure path: Storytel answers the asset call 401 even to a fresh JWT (an auth change on their side - vibb's "what
     can drift"). Each book: the kept JWT 401s, the forced re-login succeeds (200, so no cooldown), the retry 401s ->
     `Failed(401)`, transient, next book. A 20-book series is 20 logins per run, and runs come back at 2 min doubling:
     about a hundred logins in the first hour, through the success path the 10b cooldown doesn't see. vibb does the same
     (`sync` catches the `OSError` and goes on), but M7 already broke from vibb for exactly this pattern on the login.
   - Fix: `mintStopsRun(Mint.Failed(401))` = true (that book gets `http_401`, the rest wait for the next run), and drop
     the JWT when the retry also gets a 401 (`jwt = null` if it is still the one used), so the next run starts with
     one login instead of a 401 round trip and a forced one. Rows in `StorytelRulesTest` and `StorytelApiTest`.

5. **Low: "Del N av M" counts the play order, not the reading order.**
   - Where: `main/java/me/vibb/music/ui/UiModel.kt:33` and `:94-100` pass `playOrder(entry, snap.listed[...])` to
     `countedOrder` (`logic/LibraryView.kt:34-35`).
   - §3.5 says "count the series' reading order (`snap.listed`, every book)". With "auto" the two agree; with the order
     select at newest first (§2.3 offers it for a Storytel entry), book 5 reads "Lydbok · del 1 av 5" and the player
     "Del 1 av 5". For a book series the number is the book's place in the series.
   - Fix: pass `snap.listed[entry.id].orEmpty().map { it.key }` (the listing order is the reading order) as the counted
     order for Storytel in both places; `started` and the progress line then mean "how far into the series", which is
     what a series means. Or keep the play order and add it to the status's choices. A `LibraryViewTest` row with
     `order = "newest_first"`.

6. **Low: `durationByConsumableId` returns a listed 0.**
   - Where: `Storytel.kt:183-187` returns `durationMs` as listed.
   - vibb's caller takes the lookup only when truthy (`if known:`, `daemon.py:4663`) and otherwise falls to the carry
     (`:4667-4672`). A book listed with `duration_ms` 0 (the shelf's "else 0", §2.1) would come back as an
     authoritative 0 and, as step 4b wires "authoritative first", stop the carry from filling the bar.
   - Fix: `?.takeIf { it > 0 }`; a `StorytelRulesTest` row (a listed 0 -> null).

7. **Low: phase 1's `mayRetry` still decides with phase 1's classes.**
   - Where: `main/java/me/vibb/music/logic/Offline.kt:76-77` calls `retryDelayMs` itself, and `sync/DownloadPlan.kt:57`
     computes `nextRetryAt` with it too; `retryDelayFor` (`Storytel.kt:97-98`) is beside them, unused (choice 10).
   - Failure path: step 3 swaps the URL for `sourceId` in `mayRetry` (it has to: `item.url!!` at `DownloadPlan.kt:54`
     would throw for a book) and keeps the call. A CDN 403 from an expired token (`http_403`) is then permanent for a
     day - the exact M7 case. The `StorytelRulesTest` row at `:154` uses `http_503`, which both rules call transient,
     so it can't tell the two apart.
   - Fix now (pure, cheap): `mayRetry(..., delayMs: (String?, Int) -> Long = ::retryDelayMs)` (or pass the entry), the
     same function for `nextRetryAt`; a row: `http_403` on a Storytel book may retry after 2 min, on an RSS item after
     a day.

8. **Low: the ported tests don't credit vibb.**
   - Where: `test/java/me/vibb/music/storytel/StorytelApiTest.kt:25-29`, `logic/StorytelRulesTest.kt:19-23`,
     `storytel/StorytelHttpTest.kt:15-19` port `tests/storytel_api.py`, `storytel_expand.py`, `storytel_sync.py` and
     `storytel_sonos.py` but name only the files. Phase 1's and 1b's ported tests carry "(MIT, Copyright (c) 2026
     palchrb)" (`BookmarksTest.kt:12`, `PlayBodyTest.kt:13`), and the ported main files here do.
   - Fix: add the credit line to the three headers.

## Confirmed

- **AES**: `encryptPassword` = vibb's `_encrypt_password` (UTF-8 in, PKCS#7, UPPERCASE hex); the four vectors are in
  the fixture and asserted, plus vibb's own literally.
- **Login URL**: `addQueryParameter` in vibb's order; `@` -> `%40` as Python's `urlencode`, `+` -> `%2B` in both (run
  here against OkHttp 4.12); a space is `%20` where Python writes `+` - the same on decode. Follow on, 20 s or less, no
  bearer on the login; `StorytelHttpTest` checks it over sockets.
- **JWT**: in memory, `elapsed + 1 h`, expired at exactly 1 h (tested at -1 ms and 0), the wall clock never ages it, a
  new generation logs in again, a cleared login stops using it.
- **401**: one forced re-login, one retry, `Failed(401)` after (tested); the forced re-login race traced through: a
  401 whose token another mint has replaced takes the new one under the lock or before it, never a second login (the
  re-entrant test drives that path). A refused re-login stamps the cooldown and the waiter behind it sees the stamp
  (the `0 <=` boundary is what makes that work at ms resolution).
- **Lock**: `tryLock` for what is left of the deadline; the re-read under the lock serves a waiter (mutation-checked);
  `finally` releases on every path, an exception included; the asset call holds no lock; a non-fair lock with two
  callers can't starve. Nothing here can run on main by itself (see the step 3 notes).
- **Cooldown**: per generation, wall clock, no request inside it (the cooldown is read under the lock before every
  login), a future stamp is none, it ends at 1 h, a 5xx is a refusal (vibb).
- **Mint**: the 302 captured (`follow = false` both redirect switches), the CDN never touched (seam and loopback),
  `Authorization` only on the API (`SourceScanTest`), the classes per M7 with every listed status tested, the deadline
  over the login, the lock and the asset call, `(timeoutMs, deadlineMs)` as §4.2/L6 settle it, a seam exception
  (`IOException` or other) never escapes, reasons are fixed words.
- **Rules**: the grammar and junk (newline, case, slash, space), `sourceId`, the keep set (first N in reading order
  whatever the play order, bookmark + next with phase 1's wrap, depth 0), the download order, the retry classes,
  `mintItemError`/`mintStopsRun`, the 1.5 GB floor at vibb's boundary (`< DISK_FLOOR` stops), `storytelWaiting`,
  `consumableIdOf` as `parse_qs` (exact, first non-blank, `+` and `%XX` decoded; vibb's ambiguous token), `sameStream`,
  `storedUri` (never the signed URL, null with nothing to name), `redactUri` (query, fragment and userinfo cut).
- **M5**: book 3 of 5 with books 1 and 3 on the phone reads "del 3 av 5", a bookmark on an undownloaded book is
  started, the player says "Del 3 av 5" (behavioural tests; Low 5 is about the order only).
- **Secrets**: no log line in `storytel/` at all; every printing type redacted; the fixtures and tests use
  `k@example.invalid`/`hemmelig`, `FAKE-JWT-1`, `FAKE-SIG-1`, `*.example.invalid`, `example.org` and loopback - no
  `eyJ`, no real account; the only real hosts are `Endpoints.STORYTEL`'s.
- **Phase 1**: `entryUi` and `playerNumber` reduce to the old expressions for NRK, RSS and own files (the NY badge still
  looks at the visible items); `kindOf`, `kindText`, `hintText`, the Nytt row and the player line are exhaustive
  `when`s; the parser change touches only `"storytel"`. A Storytel entry before step 3: parsed, listed through the
  bridge, `url` null, so hidden (`Snapshot.visible`), out of the carousel and the search (`s.entries`), not news
  (eligibility is `url != null`), not wanted and nothing pruned (`DownloadPlan` keeps `url != null`) - tested.
- **Hygiene**: four strings in `values` and `values-nb` (nb pinned), vibb credited (MIT) on every ported main file, the
  UPSTREAM note kept, nothing in the launcher's MIT directories, no warnings.

## The twelve choices

1. **The login lock in `StorytelApi`** (`ReentrantLock`, `tryLock` with the deadline): accept. Two conditions for
   step 3, to write into §4.2: `StorytelAccount` holds exactly one `StorytelApi` for the process (the lock and the JWT
   live in the instance; an instance per worker run or per read of `debugBase` would lose both and log in every run),
   and `mint` is `withContext(Dispatchers.IO) { api.assetUrl(...) }` (the lock blocks a thread for up to the deadline).
2. **`state()` on `StorytelApi`**: accept. It decrypts the stored login (Keystore) and reads the cooldown file:
   off-main only (`writeReport` runs in the sync worker: fine).
3. **The cooldown at `0 <=`**: accept; it is what lets the waiter behind a refused re-login see the stamp, and `10b`
   pins it. §1.1's "active while `0 < now - at < 1 h`" should read `0 <=` for both sides (the server's stamps are
   `Sweep::now()` text; the same rule costs nothing there).
4. **A `Location` only on a 3xx, and it must pass `safeUrl`**: accept 3xx-only (a 4xx/5xx carrying a Location is
   decided by its status - stricter than vibb, never worse); change the validator (Low 3).
5. **A malformed consumable id is `NoAudio(0)` without a request**: accept.
6. **Any seam exception is `Unreachable` / `LoginFailed("unreachable")`, reasons are fixed words**: accept; extend it
   to the cooldown write (Low 1).
7. **Where the types live** (`StorytelRequest`/`StorytelResponse` in `StorytelHttp.kt`, plain classes; `Endpoints` in
   `StorytelEndpoints.kt`; no request body): accept.
8. **M5 wired in step 1**: accept (pure, no effect until a book can be downloaded, tested); with Low 5.
9. **`Waiting.LOGIN` now**: accept (nothing reports it before step 3; the server's sanitizer learns it in step 2b).
10. **The retry helpers beside phase 1's, `mayRetry` untouched**: accept the split, but give `mayRetry` its delay
    function now so step 3 can't wire it wrong (Low 7).
11. **`storedUri(entry, itemKey, uri)`, durations in ms**: accept; add the `> 0` rule to `durationByConsumableId`
    (Low 6).
12. **`SourceScanTest` beyond step 1's list**: accept (the §6.1 pins are free now and guard steps 3-4); fix the
    `toString` pin's brace matching (Low 2). The `debugBase` "read nowhere" pin is the right form until step 3.

## The spec problems the status lists

All six are right, and each is a text fix for the next spec touch (none needs a code change beyond the above):
§4.1's code block should put `StorytelRequest`/`StorytelResponse` in `StorytelHttp.kt` (7) and say a `Location` counts
only on a 3xx (4); §4.2 should move the lock into `StorytelApi` with the one-instance and IO conditions of choice 1;
§1.1 (and §2.1's server rule) `0 <=` (3); §13's M5 line "done in step 3" -> step 1 (8); §7's `storytel_expand.py` 1
row is `Snapshot.visible` in step 1 and `forRenderer(false)` in step 3; §7's 10b "a new generation clears" holds
through the generation-keyed store, and step 3's file store may simply keep one `{generation, at}` record.

## Notes for steps 3 and 4b (not findings)

- A `GuardsTest` pin in step 3 (downloads) and 4b (the actor job): `assetUrl(`/`.mint(` called only from the download
  worker and the Sonos play job, `StorytelAccount.mint` a `suspend` on IO. The API swallows a
  `NetworkOnMainThreadException` as "unreachable", so a main-thread caller would fail quietly, after blocking main on
  the lock.
- `stored()` decrypts the sealed login twice per mint (before and under the lock); `StorytelLogin.generation()` reads
  the clear generation without the Keystore and could serve `fresh()`. Optional.
- A transient Keystore failure reads as `NotConfigured` -> the run stops with `waiting: login` ("no login on the
  phone" on the card) until the next run; acceptable, worth one log line (no detail) in `StorytelAccount`.
- `Unreachable` on the asset call goes on to the next book (vibb parity): with the network gone mid-run every remaining
  book gets `network`. Step 3 may stop the run on the first one; the spec doesn't ask.

## Not worth fixing

- `consumableIdOf` reads the query before the fragment, where `urlsplit` cuts the fragment first: differs only for a
  `#` before the `?`, which no signed CDN URL has.
- `redactUri` keeps the path, so a token carried in the path would show: §5.7's rule; Storytel's token is in the query
  (vibb's fixture and field notes), and the device check greps logcat for `token=`.
- The L6 test bounds the Sonos give-up at `< 5 s` and starts the waiter after a 100 ms sleep: it can only pass
  spuriously (a waiter arriving after the login), never fail spuriously; the mutation shows it catches the real bug.
- The log scan is per line (a multi-line `Log` call escapes it): a heuristic beside the strong "no `Log.` in
  `storytel/`" pin.
- After a forced re-login that fails (`LoginFailed`) the 401'd JWT stays kept: one extra round trip later; Low 4 covers
  the 401 case that matters.
- `bearer()` clears `jwt` outside the lock when no login is stored: benign (the login is gone either way).
- `storytelKeepSet` repeats `keepSet`'s bookmark-and-next lines: cosmetic.
- `NOTICE.md` has no Storytel paragraph yet, though the headers point to it: step 5 owns it (§8), before any release.
