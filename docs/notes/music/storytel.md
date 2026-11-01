# Music notes: Storytel (design 26b, 58)

Moved from music/CLAUDE.md on 2026-11-01.

## Rules that are easy to break (continued): Storytel

- **Storytel is download-only on the phone** (design 26b §3-§4): a book is listed by the server with `url: null` and
  plays on the phone only from its copy - `mediaItem` gives it the uri `storytel:book:<cid>` and `ItemTag.copyOnly`
  (a `CacheDataSource` with no upstream: a missing part is an error, never a request). Visibility follows the renderer
  (vibb `entries_for(remote)`): `Snapshot.forRenderer(remote)` shows a Storytel book on the phone only with its copy,
  on a room always; every caller passes the renderer explicitly - the service per call site (below), the screens from
  the shown output, the Nytt notifier `false`. "Del N av M", `started` and the progress line count the series' reading
  order (`snap.listed`, every book), not the visible books (`countedOrder`, and `countedKeys` for the player).
- **The Storytel account and the mint** (design 26b §4.1-§4.2, design 58 §5.1-§5.3): **the phone never logs in and
  never holds the family's login** - the JWT is the server's, through the launcher's `getStorytelToken`
  (`StorytelBridgeToken`, 10 s; the launcher asks `POST /api/devices/music/storytel/token` and keeps nothing), in
  memory only. One `StorytelAccount.get(context)` for the process holds one `StorytelSession` and one `StorytelApi`. The
  session uses a JWT while the managed config's `storytel_generation` is the one it was asked under and it hasn't
  expired (the server took its margin off); generation 0 is off, with no ask; a new generation drops the JWT and every
  wait. One ask at a time, in the session's own scope with the bridge's whole 10 s: a waiter shares it and waits
  `min(10 s, deadline - now - timeoutMs)` (a room 4 s, a download 10 s; under 1 s, `deadline` with no ask); an answer
  its waiter gave up on is kept. The waits, with no ask inside: `off` 2 min (`NotConfigured`), `refused` the server's
  wait (`Refused`), `busy` its wait and `key` 10 min (`LoginFailed`), and a 30 s hold after an `unreachable`; one
  prompt retry 5 s after an `unreachable` inside a mint when it still fits (a download's always does). Storytel's 401:
  the JWT is dropped and asked for once with `stale` = its session; another JWT is retried once, a declined force is
  its wait, the same JWT back is `Failed(401)` at once; a second 401 drops the new one too. `mint(cid, timeoutMs,
  deadlineMs)` is a plain suspend, never on main: downloads `(15 s, +60 s)`, a room `(6 s, +10 s)`; only the asset
  request is `runInterruptible`, so a download job WorkManager stops ends at once and the ask runs on. A room's actor
  never cancels a job (a superseded play is dropped by its token, a running mint finishes). The asset call never
  follows the 302 (`follow = false`); a `Location` counts only on a 3xx and only through `mintedUrl` (OkHttp's
  canonical form). A URL is minted per download attempt (the `http_416` refetch too) and per Sonos play, used once,
  never stored. **The old login goes**: `StorytelLegacy.purge` deletes `storytel.bin`, `storytel_refused.json` (and
  their `.tmp`) and the Keystore key `vibb_storytel`, once per process from `Sync.writeReport` - never on a mint's path
  (`SourceScanTest`).
- **Storytel downloads** (design 26b §3.3, §4.3, §4.5): the keep set is the first `cache` books in reading order plus
  the bookmarked book and the next; the bookmarked book downloads first, then reading order, one book at a time, none
  below `STORYTEL_FREE_FLOOR` (1.5 GB). Download rows, failures and `mayRetry` name the book by `sourceId`
  (`storytel:book:<cid>`), never the minted URL, so a partial copy resumes with Range against a fresh token. A
  Storytel book's CDN request never follows a redirect (Media3 would write the target, a token, into `exo_redir`).
  `NotConfigured`, `Refused` and `LoginFailed` stop Storytel for the run with no item error, and the job comes back by
  itself at `DownloadRun.storytelBackAt` (a refusal's end, `LoginFailed.backAtWallMs` - 2 min after an outage, doubling
  to 30 over consecutive outages until a JWT comes, `busy`'s
  wait, 10 min for `key` -, 2 min after a 401 stop; design 58 §5.3); `NoAudio` is `no_audio`
  (a day); `Failed`/`Unreachable` are transient (`http_<n>`/`network`, 2 min doubling, at most 6 h). A failure after
  the partial grew by at least max(1 MiB, 1 %) since the last one counts as the first again (`grewEnough`,
  `GROWTH_FLOOR_BYTES`; every source). **With the parent's "Download on mobile data" switch on, Storytel books
  download over mobile data like every other download** (often several hundred MB each; the phone's Music page in the PWA says so). The report's
  `storytel` is `StorytelAccount.state()` (`none` while off or `off` answered, `refused` in the server's cooldown,
  else `ok`) and a Storytel entry's `waiting` is
  `storytelWaiting` (`login`, `storage` below the floor, else the shared rule).
- **Storytel secrets** (design 26b §0, §6.1, design 58 §7.1): the JWT (a bearer for the family's whole account), its
  session and a minted URL (its `token=` is a bearer for the book) never reach a file, a log, Room, `renderer.json`,
  `music_state`, the report or a `toString`: `JwtResult.Ok`, `StorytelSession.Jwt`, `StorytelRequest`,
  `StorytelResponse` and `Mint.Url` print redacted (an explicit `toString()` each, pinned by `SourceScanTest`, with the
  Sonos holders below); no `Log.` in `storytel/`, and no log line in `sync/` names a secret; `Authorization`/`Bearer`
  appear only in `StorytelApi.kt` (and `SpotifyApi.kt`), and `MediaCache` never adds an `Authorization`, so no bearer
  reaches the CDN. The e-mail and the password never reach the phone at all.
- **The Storytel debug switch** (design 26b §4.6): `StorytelEndpoints.debugBase` is null unless the debug feed's
  `config.json` sets `storytel_base` (no feed in a release, so always null there). Only `src/debug`'s
  `DebugStorytel.kt` assigns it - from the receiver and from `DebugStartup` (a non-exported provider,
  `android:initOrder="1000"`, before WorkManager's), at every process start, since a download job often runs in a
  fresh process. Exactly five readers, all in `StorytelEndpoints.kt`: `endpoints()`, `apiOverLan()`,
  `publicDownloads()`, `privateMintAllowed()`, `debugJwt()` (the fake's `/fake/jwt`, asked instead of the launcher only
  while the app is unmanaged, under `DEBUG_GENERATION` 1; a managed phone's JWT always comes through the bridge), each
  taking the base only for `10.0.2.2` or loopback. With the switch set only a `FAKE-JWT-` goes on, whichever way it
  came (`debugSwitchJwt`): a `config.json` left on a real phone never sends the family's JWT elsewhere. A
  `storytel.json` from before design 58 is deleted unread (`SourceScanTest` pins all of it).
- **Storytel on a room** (design 26b §5): a book is speaker-playable downloaded or not (`speakerDropReason`); its
  URL is minted **inside the play's actor job** (`StorytelAccount.mint`, 6 s for the asset call, 10 s in all), never
  for a resume, a seek, the stall retry, adopt or a follow. Every play job carries a token (`!closed && mayPlay()`,
  for a book also its generation), asked before the job's work, after the mint and right before `SetAVTransportURI`
  (`transport.play`'s `gate`): a superseded, released or suspended play sends nothing. The minted URL gets the
  speaker filter's address checks (`mintedDropReason`; a private literal only with the debug switch). A failed mint
  sends nothing, touches no volume and is 1b's failed play (`no_audio` the book's item error). The actor never
  cancels a job, so everything behind a mint waits up to 10 s: `HANDOFF_MS` 25 s, `SonosTail.MAX_MS` 26 s,
  `SWITCH_WAIT_MAX_MS` 35 s. **The prefetch** (design 58 §5.1, QA 58-1): `SonosRenderer` calls
  `StorytelAccount.prefetchIfDue()` (on IO, off the actor) at a start (`load` with `playNow`, `play()`) before the job
  is queued, and at every poll tick while a Storytel book shows playing - an ask when the kept JWT has less than
  `min(10 min, its life / 3)` left (never for a life of 240 s or less) or none is kept, with no ask in flight and no wait
  running, backing off 2, 4, 8 minutes after a failed one or the same JWT back - so a book's natural end finds the JWT
  in memory and the advance asks nothing; the room's 4 s ask is the rare path. Each service call site reads the renderer's view (`load(remote)`, §5.6): the room's for
  the look, adopt, phone -> room (a series re-planned over the room's order) and the standalone suspension; the
  phone's coming home. **On the phone a Storytel place on a book that isn't downloaded starts nothing** - the cover
  tap, RESUME, `restoreThen`, a headset's play, coming home: "Ikke lastet ned", never another book, the bookmark
  unmoved (`bookNotOnPhone`). A signed URL is never logged or printed (`redactUri`; the `toString`s of `PlayBody`,
  `PlayResult`, `Fields`, `Moved`, `PositionInfo`).
