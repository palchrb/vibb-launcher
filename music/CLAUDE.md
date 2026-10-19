# Vibb music - project context

The kid's music and podcast player (`me.vibb.music`, debug `me.vibb.music.debug`): a separate APK next to the
launcher, released as `music-v*` and installed through the launcher's catalog. Designs: `docs/design/20-vibb-music.md`
(the GUI decisions, locked to mockup v12 in `docs/design/mockups/vibb-music/`), `21-vibb-music-phase1.md` (§4, §6
step 2, QA decisions, the step 2 status) and `21b-music-server-sweep.md` §6 (the server lists NRK/RSS; the phone never
does); Sonos (phase 1b) is `26a-sonos.md` (§0-§6 the spec, §9 the deviations from vibb, then each step's status and
choices); Storytel (phase 2) is `26b-storytel.md` (§0-§6 the spec, §9 the deviations, then each step's status and
choices); Spotify (phase 3, new ground: Spotify's Web Playback SDK in a headless WebView) is `26c-spotify.md` (§0-§10
the spec, §12 the deviations from vibb, then each step's status with its fix round and the two merges of main - the
status sections say what was built where the spec differs); a new entry as Nytt news is
`29-music-news-new-entries.md` (ours, not vibb). Behaviour comes from
palchrb/vibb (MIT), ported, never reinvented: each ported file credits it.

## Stack and layout

Kotlin, one Gradle module (`app/`), the launcher's AGP 8.13 / Kotlin 2.1.20, minSdk 34, SDK 36. Compose
(foundation only, no Material), Media3 (ExoPlayer, HLS, session; OkHttp data source), Room (KSP; schemas in
`app/schemas/`), WorkManager, kotlinx.serialization. No Firebase or Play services (`checkReleaseHasNoGoogleServices`).

- `model/` - the library and listing documents (lenient: a bad element is dropped, a non-`v: 1` document refused).
- `logic/` - every rule, pure and unit-tested: resume (`Bookmarks`), play order, queue/shuffle, previous/next,
  keep/prune (`Offline`), NY/Nytt, the network gate, cache keys, the public-address guard, the report, the screens'
  words. Put new rules here, not in the Android classes.
- `data/` - `Store` (files: library, received listings, covers, own-file copies in noBackupFilesDir - each with the
  SHA-256 checked on arrival in `own/<id>.sha256`, so a copy under a reused file id is fetched again, design 29 step
  4, `logic/OwnFiles.kt` - the report),
  Room (`MusicDb`), `Snapshot` (what the screens and the player read; `visible()` hides url-null items without a copy;
  `forRenderer(remote)` is the view for a renderer - on a room every Storytel book, on the phone only those with a copy;
  a listed Spotify item shows in both views, a context-mode Spotify entry as one synthetic item). Spotify's own files
  in noBackupFilesDir: `spotify_state.json` (the account word), `spotify_context.json` (a context's live track), both
  the store's, and the web player's `spotify_page_account`.
- `sync/` - the sync and download workers (WorkManager; never a dataSync FGS, QA #1), `DownloadPlan` and the pure
  `DownloadRun` (the worker's decisions behind a `DownloadIo` seam), `ArtPlan` (the art want set), `Inbox` (what the
  bridge hands over, checked), the Nytt notification, `CacheThrough` (design 30: a played stream settled into a
  copy, the extras' eviction, the orphan sweep, a new queue's stale bytes).
- `media/` - the one `SimpleCache` (no evictor; item keys, an HLS key factory), the guarded OkHttp clients, and design
  30's wrappers: `ThroughSink` (the player's cache sink), `StreamGate` (the player's upstream asks the streaming
  switch), `LengthGuard` (a changed source at a resume is a synthetic 416).
- `playback/` - `PlaybackService` (MediaSessionService; FGS only from a tap or a media command), `PlayerHub` (the
  live state for the screens, same process) and the renderer axis (26a §0, §3): `Renderer` (who makes the sound),
  `LocalRenderer` (the ExoPlayer, the phone's 10 s sleep fade, audio focus) and `RendererGate` (the startup gate).
  The output axis (`Output` keys `phone`/`dev:<id>`/`sonos:<uid>`, `setPreferredAudioDevice`, the headset callbacks)
  stays the phone's and always goes to `LocalRenderer`. The third renderer is `spotify/SpotifyRenderer` (26c §5);
  `RendererRules.kt` holds the pure rules of which renderer an entry gets, a Spotify item's playability, the queue's
  kind and its claim, and the call test (`RendererRulesTest`).
- `sonos/` - the Sonos port (26a §1-§2, from vibb `pi/sonosd.py`, `pi/vibb/renderer.py` and `pi/daemon.py`'s
  `_sonos_*`): Android-free `Ssdp`, `Soap` (OkHttp + javax.xml DOM, our own UPnP client, no library), `Topology`,
  `SpeakerCache` (`noBackupFilesDir/sonos.json`; players merge, groups replace) + `RoomDirectory` (the sheet's rows,
  stale + one background SSDP round), `Didl`, `SonosUris`, `NrkService`, `PlayBody` (the speaker-playable filter),
  `SonosSession` (vibb's poll machine: classify, cadence, stall, migration probe, disarm), `SonosTransport` (play with
  the hush and the settled seek, verbs, adopt, the park), `SonosBookmarks` (holds and the regression guard),
  `SonosController` (the controller, main-thread, pure), `SonosLifecycle` (the startup and suspension verdicts),
  `RoomScope`, `RendererChoice`; the Android side `SonosRenderer` (a Media3 `SimpleBasePlayer`), `SonosActor` +
  `SonosPoller`, `SonosHome` (Wi-Fi tracking as a gate; no socket is ever bound to a `Network`: the launcher's
  always-on VPN allows no bypass, so the bind is EPERM - 26a §6.5, `SourceScanTest`), `SsdpScanner` (MulticastLock for
  the round only), `SonosLocks`, `SonosSuspension`, `SonosTail`.
- `storytel/` - the phone's Storytel client (26b §4, from vibb `pi/vibb/storytel.py`; the server reads the shelf and
  lists it, the phone never does): `StorytelApi` (pure: AES `pwd=`, the login with the refused-login cooldown, the JWT,
  the asset call's 302 captured as a `Mint`, the login lock), `StorytelHttp` (the seam and `OkHttpStorytelHttp`),
  `StorytelEndpoints` (Storytel's hosts and the debug switch), `StorytelAccount` (Android: the process's one
  `StorytelApi`, the sealed login, `FileCooldownStore`, `mint`, `state`). The rules are in `logic/Storytel.kt`.
- `spotify/` - the phone's Spotify player (26c §4-§5; the server lists, the phone never does): pure `SpotifyRef` (the
  stored target `spotify:<kind>:<id>`, `uriOf`), `SpotifyApi` + `SpotifyHttp` (the Web API's play, transfer, queue,
  shuffle and repeat calls behind a seam, their outcomes and the start's retries), `SdkState` (the page's compact
  state, capped), `SpotifyTracker` (the state machine: what is ours, ends, unplayable, elsewhere, context mode),
  `SpotifyHosts` + `WebPolicy` (the host list = `spotify_hosts.json`; what the WebView may load; the page's CSP and
  bridge names), `SpotifyEndpoints` (Spotify's bases and the debug switch), `TokenCache` (the access token, memory
  only); Android: `SpotifyWebHost` (the one headless WebView), `SpotifyRenderer` (the `Renderer` with its
  `SimpleBasePlayer`, focus, noisy, the fade), `SpotifyLocks`, `SpotifyRecording` (the debug-only recorder's hook).
  `assets/spotify/player.html` + `player.js` are our page and its glue (GPL; the SDK is loaded from Spotify at run
  time, never copied); `bridge/SpotifyBridgeToken.kt` is the AIDL token call. The rules are in `logic/Spotify.kt`.
- `ui/` - the screens of `Main.dc.html` v12; `MusicGlyphs.kt` and `res/drawable/music_glyph_*` are generated by
  `scripts/material-symbols.sh`.
- `src/debug/` - the debug feed (`DebugFeedReceiver`, docs/testing/emulator.md §6f; `debug-feed/sonos.json` seeds the
  speaker cache with ip and port for the fake speakers, §6h; `DebugStorytel.kt`: `config.json`'s `storytel_base` and
  `storytel.json`'s fake login for the fake Storytel, §6i; `DebugSpotify.kt`: `config.json`'s `spotify_base` for the
  fake Spotify and `record_spotify_states` for the state recorder, §6l). Never in a release
  (`checkReleaseHasNoDebugFeed` scans the APK; `SourceScanTest`).

## Build and test

From `music/` with JDK 17 and `local.properties` (`sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` (CI: `./gradlew build`). The
shared files in `src/test/resources/` must equal `server/testdata/music_{library,listing,icons}.json` and
`server/testdata/spotify_hosts.json` (`ContractFilesTest`; `SpotifyHostsTest` checks the constant against it). A release build without the signing properties is unsigned, as the launcher's. Dependencies
are verified against `gradle/verification-metadata.xml` and the wrapper pins its distribution (security review L9,
root `CLAUDE.md` "Build and test"; `BuildChainTest`). The canned SOAP replies in `src/test/resources/sonos/`
(`SonosFixturesTest`) are also what `scripts/fake-sonos.py` answers from (vibb's "two fakes" rule): change a fixture
and run `python3 -I scripts/fake-sonos.py --selftest` (CI does, `music.yml`). On the emulator the app plays on the
fake speakers (docs/testing/emulator.md §6h; no multicast there, so no SSDP: the debug feed seeds the cache). Likewise
`src/test/resources/storytel/` (the login request and answers, the asset 302 and the locked 403, the AES vectors) is
what `scripts/fake-storytel.py` answers from (`--selftest`, CI too): its options `--books cid:MB,...`, `--locked`,
`--refuse`, `--no-jwt`, `--token-ttl`, `--drop-at`, `--slow-mint`, `--no-download` (minted but never on the phone, for
a room) and `--print-listing ENTRY` (the debug feed's listing) drive docs/testing/emulator.md §6i; every value in it
is fake (`FAKE-JWT-1`, `FAKE-SIG-<n>`, `k@example.invalid`, never `eyJ...`). `scripts/fake-audio.py` is a fake podcast
host for design 30's checks (silent WAV episodes with Range; `--vary` serves another length per request, the
changed-source check; `--print-listing ENTRY`; `--selftest`, CI too; docs/testing/emulator.md §6f). Spotify's
`src/test/resources/spotify/` (`play_request.json` - the exact play, context play, transfer, queue, shuffle and repeat
requests -, `play_answers.json`, `token_answers.json`, and `sdk_states/*.json`, the state sequences
`SpotifyTrackerTest` replays) and `server/testdata/spotify/` (the accounts and Web API answers) are what
`scripts/fake-spotify.py` answers from (`--selftest`, CI too): the accounts service with PKCE (`--as`, `--deny`,
`--rotate`, `--access-ttl`, `--expire-refresh`, `--bad-client`), the Web API the server and the phone use
(`--snapshot`, `--rate-limit N`, `--quota`, `--not-premium`, `--offset-404`) and a stub Web Playback SDK playing a tone
per track through the WebView (`--elsewhere`, `--autoplay`, `--transfer-in`, `--unplayable`, `--relink`, `--no-eme`,
`--slow-ready`, `--shuffle`, `--repeat`, `--token-ttl`, `--track-seconds`), plus `--print-library` and
`--print-listing ENTRY [--tracks N]`; they drive docs/testing/emulator.md §6l. Every value in it is fake
(`FAKE-ACCESS-<n>`, `FAKE-REFRESH-<n>`, `FAKE-CODE-<n>`, client id `0123456789abcdef0123456789abcdef`, users
`fakeparent`/`fakekid`, never a `BQ...`/`AQ...` token shape).

## Rules that are easy to break

- Nothing ever starts playback by itself: a restart brings the now-playing bar back paused (`NowPlayingRow`).
- Playback never waits for the network: a copy plays as it is, a stream only through the gate (Wi-Fi unless the
  parent's "Stream on mobile data" switch, never roaming), else "Ikke lastet ned - trenger Wi-Fi" and the
  queue moves on. The download job follows the other switch, "Download on mobile data" (design 30 §1.1:
  `streamAllowed`/`downloadAllowed` in `logic/NetGate.kt`, one formula, `Store.streamMobileData` /
  `downloadMobileData`). The player's upstream asks the streaming switch again at every open (`StreamGate`, design
  30 §1.6): a copy's holes and the preloaded next item too; its refusal is "Ikke lastet ned", never an item error.
- **A stream is written to the cache as it plays** (design 30 §1.4-§1.7; ours, not vibb's "streams aren't cached"):
  the `ThroughSink` decides at every open (`throughWrites`: NRK/RSS, the entry's live depth above 0 - "Ingen" writes
  nothing - and 1 GiB free), makes the item's not-done row before its first byte (`insertThrough`), and a cache
  error never fails playback (`FLAG_IGNORE_CACHE_ON_ERROR`). "On the phone" is still a done row: a stream played to
  its end is settled into one by a non-blocking span check (`CacheThrough.settle` when it leaves the held set, on
  IO, never the service's `writer` - a played one left incomplete asks for the job; `catchUp` at every sync, before
  `runLock`, enqueues nothing); the job completes a partial with Range. The player's **held set**
  (`PlayerHub.heldLocal`: the current item, the next, and every item starting within `HELD_AHEAD_MS` of the position
  - with short items ExoPlayer opens more than the next - plus a new queue's claims, which end at its load, its
  give-up or an exception) is never pruned, evicted, swept or settled - every removal reads it again right before it removes - and
  its progressive items that aren't done are never fetched (the downloader would wait on the player's lock).
  `sameSource` ignores an http url's query; another source's partial, or bytes with no row, play uncached
  (`staleThrough`); a resume against another total length is the `LengthGuard`'s synthetic `http_416` (its cause
  `SourceChangedException`, never retried by the player's `SourceChangedPolicy`; the job refetches from 0, the player
  drops the row and loads the queue again with the item uncached and without a cache key - never `replaceItem`,
  which Media3 turns into an in-place update of the same cache-backed source). The HLS rendition is pinned to the download's
  (`setMaxAudioBitrate(HLS_MAX_BITRATE)` + highest). Sonos, Storytel and Spotify never write through.
- Prune only on a parsed listing, and only what the library no longer names: an entry or file the app can't read is
  hidden with everything kept (qa-21-step2-code H1). Positions and bookmarks are never deleted except by vibb's
  `clear_state` at a queue's natural end (partial positions only). Above depth 0 the bookmarked item is never
  deleted; depth 0 ("Ingen") keeps nothing. A kept withdrawn copy plays. Design 30 §1.8: a row outside the keep set
  that the phone played (`playedAt`, Room 4; set by `markPlayed` only, kept by `upsertDownload`'s `keepPlayedAt`),
  still listed, above depth 0 and not Storytel is a **played extra**: kept, never fetched, evicted least recently
  played first (`extrasToEvict`) while the audio cache is over the parent's storage limit or free space under the
  floor (`extrasFloor`: 1 GiB, 1.5 GB while a Storytel book is wanted and the login is ok) - by the sync and by the
  job before each round. The keep set and the held set are never evicted. Cache bytes no row owns are swept at
  every sync (`orphanResources`, keys read before rows).
- **Cleartext http is allowed** (`res/xml/network_security_config.xml`, qa-30-s3b-code H2; `SourceScanTest`): Sonos
  SOAP to a speaker's LAN address, http enclosures, `lan` feeds and the emulator's fakes all need it, and a config
  can name hosts but not ranges. What may be fetched is the app's own rule (the public-only client's address checks,
  Sonos URLs from IP literals); trust is the system's CAs only.
- Positions are keyed by (entry key, item key), never by URL.
- A public entry's audio never comes from a private address (21b §2.7): every entry except one the library marks
  `lan` (an RSS feed on the LAN or tailnet).
- Behaviour questions default to what the vibb Pi player does (`pi/daemon.py`, `pi/vibb/*.py`, `tests/`).
- The kid's screens open nothing else: no settings, links, share or web views (`SourceScanTest`; the one WebView is
  the Spotify web player's, headless and never attached to a window - below).
- The bridge (design 21 step 3, `bridge/`): the launcher is the only way to the server. `BridgeSync.pull` runs first
  in every sync (library, changed listings, covers, own files copied + checked + released, the Storytel login sealed
  under a Keystore key) and `push` sends the report (with the own files held) at its end; `LiveBridge` sends
  `setNowPlaying`/`setForeground` and retries a bind. Bind only the restrictions' launcher, and only when it is
  signed like this app; honour `BRIDGE_CHANGED` only from a launcher. An in-place update syncs at once (`PackageReplacedReceiver` on `MY_PACKAGE_REPLACED`; the launcher nudges too), so Device › Music never keeps the old app's report. The two mobile-data switches
  (`stream_mobile_data`, `download_mobile_data`; a launcher from before design 30 writes neither, and then the old
  `mobile_data` counts for both - `mobileSwitch`), the storage limit (`storage_limit_gb`, 0 = none, absent or junk =
  2 GB - `storageLimit`; the played extras' eviction counts against it) and `volume_cap_pct` (the parent's cap on our
  own Sonos volume sets, design 26a §5.3) are read live from the managed config. The report always carries
  `audio_bytes` (the audio cache: what the limit counts) and `extra_bytes` (the played extras now, from the
  report's own plan, so the job's report says it too): their presence tells the PWA the app reads both switches
  (design 30 §1.3). The AIDL is
  byte-identical with `launcher/` - append, never reorder; shared names are in `bridge_contract.json`.
- The launcher's media surfaces (design 21 step 4) read the platform session and `setNowPlaying` (also sent with
  every 15 s position save while playing); its Home card opens `MainActivity` with `EXTRA_OPEN` = player and, on its
  snapshot, `EXTRA_RESUME` (the RESUME command: the stored queue where it stopped, or play on) - honoured once, never
  from Recents or a restored instance (`resumeRequested`). Listening never counts as screen time, so the app never
  keeps the screen on (`SourceScanTest`). While the launcher has suspended the app (a time rule) nothing starts
  playing (`playAllowed` in `KidPlayer`, the play commands and playback resumption; `GuardsTest`).
- **The renderer axis** (26a §0, §3-§4): `attach(next)` is the one place the renderer changes (the old one saved
  with `force` and paused under `selfPausing` before it is detached; the session always wraps
  `KidPlayer(renderer.player)`). `Renderer.saveNow` is the only persistence path: the phone's `savePosition`, on Sonos
  `bookmarkNow` (a measured position of the item the speaker plays, through vibb's hold and regression guard; never an
  optimistic, extrapolated or seeded value, never a listing duration). `positionMs()` is for display only (an
  allowlist of readers in `GuardsTest`). `showsPlaying` (fresh, reachable, ours, PLAYING) is what `publish` and
  `setNowPlaying.playing` report, not `playWhenReady`, which §3.2's table holds through the first failed polls so the
  FGS survives a transient.
- **Sonos threading** (26a §0): controller state is main-confined (`SonosController`, inside the `SimpleBasePlayer`'s
  looper); every SOAP sequence runs in the room's one `SonosActor` (a Channel consumed on IO), its results posted back
  to main before the next job runs. Handlers return a completed future at once and express intent through the
  optimistic fields; nothing waits on SOAP on main. A play generation drops a superseded result.
- **renderer.json** (`RendererChoice`, `noBackupFilesDir`): the room, the entry, the item and the **built** uri -
  for a Storytel book its marker `storytel:book:<cid>`, never the signed URL: a uri is written only by
  `RendererChoice.played`/`followed`/`adopted`, each through `storedUri` (design 26b §5.3, `GuardsTest`) -
  written before a switch's first SOAP; it goes back to the phone only on a definite answer (`roomVerdict`). At start
  `RendererGate` holds every custom command and playback resumption until one look at the stored room has decided
  (`adoptLive`: vibb's adopt, **zero transport commands**, kept only for our own live PLAYING/PAUSED stream - matched
  through `sameStream`, a marker by the live URL's `consumableId` - the session going on with the live TrackURI; else
  the phone, paused). On a room, resumption starts the speaker (gated) and returns no local items.
- **Storytel is download-only on the phone** (design 26b §3-§4): a book is listed by the server with `url: null` and
  plays on the phone only from its copy - `mediaItem` gives it the uri `storytel:book:<cid>` and `ItemTag.copyOnly`
  (a `CacheDataSource` with no upstream: a missing part is an error, never a request). Visibility follows the renderer
  (vibb `entries_for(remote)`): `Snapshot.forRenderer(remote)` shows a Storytel book on the phone only with its copy,
  on a room always; every caller passes the renderer explicitly - the service per call site (below), the screens from
  the shown output, the Nytt notifier `false`. "Del N av M", `started` and the progress line count the series' reading
  order (`snap.listed`, every book), not the visible books (`countedOrder`, and `countedKeys` for the player).
- **The Storytel account and the mint** (design 26b §4.1-§4.2): one `StorytelAccount.get(context)` for the process,
  holding exactly one `StorytelApi` (the login lock and the JWT live in the instance; one per run would log in every
  run). Login: the refused-login cooldown first (`0 <= now - at < 1 h` for the stored generation,
  `noBackupFilesDir/storytel_refused.json`; no request inside it), the JWT in memory only (1 h, per login generation),
  one re-login on a 401; a new generation from the bridge clears both. `mint(cid, timeoutMs, deadlineMs)` is
  `runInterruptible(Dispatchers.IO)`, never on main: downloads `(15 s, +60 s)`, a room `(6 s, +10 s)`; the login
  lock's wait counts against the deadline. `runInterruptible` ends the wait of a download job WorkManager stops; a
  room's actor never cancels a job (a superseded play is dropped by its token, a running mint finishes). The asset
  call never follows the 302 (`follow = false`); a `Location` counts only on a 3xx and only through `mintedUrl`
  (OkHttp's canonical form). A URL is minted per download attempt (the `http_416` refetch too) and per Sonos play,
  used once, never stored.
- **Storytel downloads** (design 26b §3.3, §4.3, §4.5): the keep set is the first `cache` books in reading order plus
  the bookmarked book and the next; the bookmarked book downloads first, then reading order, one book at a time, none
  below `STORYTEL_FREE_FLOOR` (1.5 GB). Download rows, failures and `mayRetry` name the book by `sourceId`
  (`storytel:book:<cid>`), never the minted URL, so a partial copy resumes with Range against a fresh token. A
  Storytel book's CDN request never follows a redirect (Media3 would write the target, a token, into `exo_redir`).
  `NotConfigured`, `Refused` and `LoginFailed` stop Storytel for the run with no item error; `NoAudio` is `no_audio`
  (a day); `Failed`/`Unreachable` are transient (`http_<n>`/`network`, 2 min doubling, at most 6 h). A failure after
  the partial grew by at least max(1 MiB, 1 %) since the last one counts as the first again (`grewEnough`,
  `GROWTH_FLOOR_BYTES`; every source). **With the parent's "Download on mobile data" switch on, Storytel books
  download over mobile data like every other download** (often several hundred MB each; the phone's Music page in the PWA says so). The report's
  `storytel` is `StorytelAccount.state()` (`none`/`ok`/`refused`) and a Storytel entry's `waiting` is
  `storytelWaiting` (`login`, `storage` below the floor, else the shared rule).
- **Storytel secrets** (design 26b §0, §6.1): the password, `pwd=` (AES with a public key: as good as the password),
  the e-mail, the JWT and a minted URL (its `token=` is a bearer for the book) never reach a log, Room, `renderer.json`,
  `music_state`, the report or a `toString`: `StorytelLogin.Login`, `StorytelRequest`, `StorytelResponse`, `Mint.Url`
  and the JWT holder print redacted (an explicit `toString()` each, pinned by `SourceScanTest`, with the Sonos holders
  below); no `Log.` in `storytel/`, and no log line in `sync/` names a secret; `Authorization`/`Bearer` appear only in
  `StorytelApi.kt`, and `MediaCache` never adds an `Authorization`, so no bearer reaches the CDN.
- **The Storytel debug switch** (design 26b §4.6): `StorytelEndpoints.debugBase` is null unless the debug feed's
  `config.json` sets `storytel_base` (no feed in a release, so always null there). Only `src/debug`'s
  `DebugStorytel.kt` assigns it - from the receiver and from `DebugStartup` (a non-exported provider,
  `android:initOrder="1000"`, before WorkManager's), at every process start, since a download job often runs in a
  fresh process. Exactly four readers, all in `StorytelEndpoints.kt`: `endpoints()`, `apiOverLan()`,
  `publicDownloads()`, `privateMintAllowed()`, each taking the base only for `10.0.2.2` or loopback (a `config.json`
  left on a real phone must never send the real login elsewhere). `storytel.json` seeds a fake login (the next
  generation) and is deleted once read (`SourceScanTest` pins all of it).
- **Storytel on a room** (design 26b §5): a book is speaker-playable downloaded or not (`speakerDropReason`); its
  URL is minted **inside the play's actor job** (`StorytelAccount.mint`, 6 s for the asset call, 10 s in all), never
  for a resume, a seek, the stall retry, adopt or a follow. Every play job carries a token (`!closed && mayPlay()`,
  for a book also its generation), asked before the job's work, after the mint and right before `SetAVTransportURI`
  (`transport.play`'s `gate`): a superseded, released or suspended play sends nothing. The minted URL gets the
  speaker filter's address checks (`mintedDropReason`; a private literal only with the debug switch). A failed mint
  sends nothing, touches no volume and is 1b's failed play (`no_audio` the book's item error). The actor never
  cancels a job, so everything behind a mint waits up to 10 s: `HANDOFF_MS` 25 s, `SonosTail.MAX_MS` 26 s,
  `SWITCH_WAIT_MAX_MS` 35 s. Each service call site reads the renderer's view (`load(remote)`, §5.6): the room's for
  the look, adopt, phone -> room (a series re-planned over the room's order) and the standalone suspension; the
  phone's coming home. **On the phone a Storytel place on a book that isn't downloaded starts nothing** - the cover
  tap, RESUME, `restoreThen`, a headset's play, coming home: "Ikke lastet ned", never another book, the bookmark
  unmoved (`bookNotOnPhone`). A signed URL is never logged or printed (`redactUri`; the `toString`s of `PlayBody`,
  `PlayResult`, `Fields`, `Moved`, `PositionInfo`).
- **Spotify is the third renderer, the web player** (design 26c §5, §5.10): a Spotify entry on a phone output gets
  `SpotifyRenderer` (`phoneRendererFor`; made once per service and kept; `local.standDown()` when it attaches), every
  other entry the `LocalRenderer`, a room its `SonosRenderer` - a room's queue drops Spotify (`speakerDropReason`
  `spotify`, "Kan ikke spilles på Sonos"; Spotify on Sonos is 26c's later step 9). `RendererKind.onPhone` (LOCAL,
  SPOTIFY) replaces `=== local` at every site `GuardsTest` lists. The sound is local audio (noisy, the volume cap,
  the 10 s sleep fade through `vibb.volume`; no hush). **The audio focus is the WebView's own** (26c §5.6 as the
  emulator check §6l A answered it, F2): Chromium's media session takes it when the page's audio starts, pauses on a
  loss, ducks, and plays again after a transient loss - the renderer requests none (a request of its own was taken by
  its own WebView at every play) and reads those pauses (`spotifyPauseCause`: not ours is `FAULT`) and resumes
  (`spotifySelfResumeShown`) from the page's states. A pause the WebView made while the kid wanted it playing is kept
  as `heldByPage` (no 5-minute idle close; the call rule's `callStep` counts it as playing only within
  `SPOTIFY_HELD_RECENT_MS` (5 s) of its start - `heldRecently`, so `CALL_RELEASE` plays again whichever of `CALL_HOLD` and
  the ring's pause came first, but another app that took the audio for good is never started again by a later call's
  end: qa-26c-emu-music-code M1), and a pause asked during it (`askedPaused`: the kid,
  the sleep timer, the call rule) pauses Chromium's own resume at once; during a call (`inCall`: the launcher's, or a
  system call by the audio mode - `spotifyInCall`, and `callOn` lets the mode drive the call rule on the web player as on
  a room) no start goes out and the player shows paused (qa-26c-6l-fixes-code H1, M1, L2). The session never says playing
  while nothing plays (`pwr && !ended`); the launcher sends `CALL_HOLD` at every call's begin instead (below). **The session's `SpotifyPlayer` calls the renderer only qualified**
  (`this@SpotifyRenderer.play()`): unqualified, `play()` is the SimpleBasePlayer's own and recursed (F1;
  `SpotifyRendererTest` drives it through Media3's `Player` API with the test-only `android.os.Looper`,
  `android.util.Pair` and `SparseBooleanArray` in `src/test/java/android/`). Those stand-ins shadow the mockable
  android.jar for every JVM test of the module (the jar is last on the test classpath): a new one there needs its reason
  in its header, and must keep the mockable jar's answers except where a test needs the platform's. **List mode plays our list**: `PUT /me/player/play` with
  the track uris of a window of at most 50 from the current item and `position_ms` (the resume place, so nothing
  sounds from 0:00), past the window the next item added with `POST /me/player/queue` one track ahead, shuffle and
  repeat turned off first (a device session's first play, and whenever a state says either is on). Pause, resume and
  seek are the SDK's (no Web API call); steps within 300 ms coalesce into one play; Spotify's own advance sends
  nothing (the natural-advance seek is the phone's own player's only, `resumesOnAdvance`).
- **Only our tracks are ours** (`SpotifyTracker`, 26c §5.4): a state counts only when it names the uri we sent for the
  current item or the expected next one (by uri, the SDK's `linked`, or the server's title + a duration within 2 s);
  anything else - Autoplay after the list, another Spotify app transferring to "Vibb", our own queue add left in
  Spotify's queue, a stale state from before a start - is paused at once, never saved, never mapped to an index;
  after `Ended` every playing state is paused. Unplayable comes only from the SDK (a `playback_error`, or a skip the
  state's `prev` proves), three in a row stop; the play call's 403 is an account matter (`refused`/`not_premium`,
  "Spør en voksen"), never an item error. A `null` state counts only when a second look 2 s later confirms it:
  "Spilles et annet sted", the bookmark kept. `saveNow` writes only a measured position (the last state's
  of the current item, as received).
- **Nothing starts by itself on Spotify either**: the page - and with it the Connect device "Vibb" - exists only from
  the first Spotify play, never at a restore (a stored Spotify queue loads paused: no page, no network); a play the
  renderer would start on its own (`NewWindow`, `ShuffleOrRepeat`, the move-on after an unplayable, a step Spotify plays
  while paused) goes on only with a play the kid wants now (`playWhenReady`, `playAllowed`); a step the network gate
  refuses says the line and stays (`seekTo(index, ms)`'s belt; the service tells the screens `PlayerHub.Message.STAYED`,
  which ends the instant switch's hold with no toast - F3). The page is destroyed at every suspension (`suspended()`: the
  bookmark with force, the pause - no Connect device while suspended), 5 minutes after the last pause (the SDK's
  `disconnect()` first), when the renderer process dies, on the token's `off` and when the token's account changes.
  **A detached web player owns nothing**: its queue is null while another renderer plays (`queue = {
  active.takeIf { renderer === spotify } }`) and `detached()` pauses every playing state until its next `load`
  (qa-26c-step5b-code M1). `onError` stays synchronous on main for a Spotify item: the renderer reads the
  `seekTo`/`prepare`/`play` made inside its `Error("unplayable")` as its own move-on (`GuardsTest`).
- **Context mode** (26c §5.9; the library's `spotify: "context"`: an artist, a playlist no family account may list):
  the snapshot gives the entry one synthetic item (`contextItem`: key `context`, no url, no duration, titled by the
  live track once one played), so the tile, the stored queue and resume work. A start is `context_uri` - a playlist
  from `offset.uri` = the live track at its place (an offset no longer in it: one more play from the top), an artist
  from Spotify's start (no resume: `contextResumes`, its place shows 0). Next and previous are the SDK's, sent only
  while a track of our context is on the player. Every track of the context is ours (a `null` context only for our
  live track or the one named next); anything else is paused; the end only on evidence; no unplayable item, no
  "Spor N av M", never marked done (`keepsDuration`). The live track (uri and title) is
  `noBackupFilesDir/spotify_context.json` - the store's, written through the renderer's ports, pruned on a parsed
  library, dropped at the context's end.
- **Spotify streams under the streaming switch** (design 30's `stream_mobile_data`, never the downloads switch or the
  storage limit; `spotifyPlayability` = `streamAllowed`, never `GONE`; the web player's own `networkLine` reads the
  same switch). Refused, the queue is loaded paused at its place with "Trenger Wi-Fi" / "Trenger internett"
  (`refused(line)`), never `NOT_DOWNLOADED`, never another item. Nothing of Spotify is fetchable, kept, written
  through, a played extra or a claim: `KeepItem.fetchable` false, `downloadPlan` prunes any Spotify row,
  `markPlayed`/`updateHeld` only on the phone's own player, and a queue's claim follows **its** kind -
  `queueKind(renderer.kind, entry)` in `playEntry`, `playItem` and `restore`, then `claimsQueue` (never the attached
  renderer's kind, which is still the web player right after a Spotify play: qa-26c-merge-code M1). No shuffle
  (every Spotify entry resumes).
- **The lines and the word**: the renderer's `RendererState` (`SPOTIFY_*`, `remote` false - `callStep` reads
  `remote != true`) is the player's quiet line and the loaded tile's; the account word (`ok`, `reauthorize`,
  `refused`, `not_premium`, `no_widevine`, `unreachable`, `quota`; `none` from the token's `off` and from `forget()`)
  is `noBackupFilesDir/spotify_state.json`, written only on a change, shown on **every** Spotify tile and sent as the
  report's `spotify`.
- **No source on the kid's screens** (26c step 8, the user's decision of 2026-10-14): a Spotify entry is a tile like any
  other. No string value says "Spotify" (`SourceScanTest` pins both languages, the `ui/` sources' literals, and those of
  `playback/`, `bridge/` and `sync/` - the notification, the lock screen and Home's card - with two allowlisted non-kid
  literals; it also pins the session's metadata, `setNowPlaying` and the web player's live title to the entry's name and
  the titles; string *names* like `renderer_spotify_*` and `hint_spotify_picks` are code and stay). The kind is the
  content's: `kindOf` gives a Spotify show or episode `Kind.PODCAST` ("Podkast", episodes) and a playlist, album, track
  or artist `Kind.MUSIC` (`kind_music` "Musikk", tracks) - there is no `Kind.SPOTIFY`. The player has no source line
  under the title, "Liste" shows nothing in the offline summary's place (a context: "Musikk · rekkefølgen velges for
  deg"), and the web player's lines are plain ("Trenger Wi-Fi", "Trenger internett", "Ikke tilgjengelig nå", "Spør en
  voksen", "Virker ikke på telefonen", "Spilles et annet sted", "Stoppet - trykk spill"). This is a stated deviation
  from Spotify's Developer Policy II (attribution where its content is shown; 26c §7.3): the attribution is the parent's
  PWA's only. Don't add the word, a logo or a source line back without the user.
- **The web player's WebView** (26c §5.2, §7.2): one WebView in the process, `SpotifyWebHost`'s, made with the
  application context by the service and **never attached to a window** (`SourceScanTest`: `WebView(`,
  `android.webkit` and `addJavascriptInterface` only in that file, no `addView`); JavaScript and DOM storage on, media
  without a gesture, file and content access off, no geolocation or multiple windows, `RENDERER_PRIORITY_IMPORTANT`,
  third-party cookies (the SDK's iframe), Safe Browsing and metrics off (the manifest), no `androidx.webkit`. Our page
  is `assets/spotify/player.html` under `https://appassets.androidplatform.net/spotify/` with a CSP; the SDK comes from
  `https://sdk.scdn.co/spotify-player.js` at every load, **never pinned and never copied** (Spotify's terms). Every
  request of every frame, and of service workers (`ServiceWorkerController`), passes `WebPolicy.decide`: our two
  assets, https on 443 to a host of the Spotify list, `blob:`/`data:`, else an empty 403 (counted; the host only, in
  the debug log). WebSockets and a redirect's target bypass it - bounded only by the launcher's DNS filter and the
  CSP. No navigation; the protected-media permission only, only to `sdk.scdn.co` or our origin. The bridge
  `VibbSpotify` exposes exactly `SpotifyPage.FROM_PAGE` (`ready`, `notReady`, `needToken`, `state` at most 8 KB,
  `error`), each validating and posting to main; calls into the page go through `SpotifyPage.call`, every argument
  quoted. The stores (web storage, the HTTP cache, cookies - awaited) are cleared on an account change, the token's
  `off` and a library without Spotify (`forget()`); the page's account (`"<id>:<generation>"`) is kept in
  `noBackupFilesDir/spotify_page_account`, so no token of another account reaches a page whose storage is the old one's.
- **The Spotify token** (26c §5.5, §6.1): from the launcher only - `getSpotifyToken` (appended to the AIDL, answered on
  `ISpotifyTokenCallback`, 10 s; the launcher asks `POST /api/devices/music/spotify/token` and caches nothing) ->
  `TokenResult`. `TokenCache` keeps it in memory only (never a file, Room, the report or a `toString`), hands out one
  with 5 minutes left, shares one call in flight, cools down (`reauthorize` 10 minutes, `busy` its wait), asks once
  more 5 s after an `unreachable` at a start (the server's 8 s budget), and while playing refreshes at 10 minutes left
  (again every 2 minutes until the old one expires), so the SDK's hourly `getOAuthToken` is answered from memory
  with the screen off. A 401 renews once; the same token back or a second 401 is `refused`. `Authorization`/`Bearer`
  appear only in `StorytelApi.kt` and `spotify/SpotifyApi.kt`; the Web API calls use the guarded public-only client.
- **Spotify secrets** (26c §7.1): the access token, the SDK's device id and the page's state never reach a log, Room,
  `music_state`, the report or a `toString`. **No `Log.` in `spotify/`** at all: the host, the locks and the renderer
  log through the function the service gives them, `spotifyLog` (`Log.d` under `BuildConfig.DEBUG` - the service's one
  Spotify log line; a release logs nothing), with fixed words only (hosts, statuses, kinds; never a token, a uri list
  or a state JSON), the SDK's error text only in a debug build. **The page's console is a log channel too**:
  `onConsoleMessage` returns true always (a debug build notes the level and the source's host only). The token
  reaches the page only as a quoted `evaluateJavascript` argument. **The state recorder is debug-only**:
  `SpotifyRecording.recorder` is null in main and assigned only by `DebugSpotify.kt` while `config.json` says
  `"record_spotify_states": true` (one daemon thread, `debug-feed/sdk_states/<UTC>.jsonl`, 32 MiB a file, the page's
  compact states, its events and our calls by name - never a token or the SDK's text); `HostEvent.State`'s
  `toString` leaves the JSON out.
- **The Spotify debug switch** (26c §9.3, 26b's pattern): `SpotifyEndpoints.debugBase` is null unless the debug feed's
  `config.json` sets `spotify_base` (`http://10.0.2.2:8731`); only `DebugSpotify.kt` assigns it (the receiver and
  `DebugStartup`). Exactly six readers, all in `SpotifyEndpoints.kt`, each taking the base only for 10.0.2.2 or
  loopback: `apiBase()`, `apiOverLan()`, `sdkUrl()` (`https://fake-spotify.invalid/sdk/spotify-player.js`),
  `webHosts()`, `webProxy()` (the interceptor fetches `https://fake-spotify.invalid/<path>` from the base over the LAN
  client, `Range` forwarded, so the page sees https only and mixed content stays blocked) and `debugTokens()` (the
  fake's token route, only while the app is unmanaged; a managed phone's token comes through the bridge). With the
  switch set only a `FAKE-ACCESS-` token goes on (`debugSwitchToken`). The cleartext config doesn't widen the web
  player: `WebPolicy` refuses every http URL. The emulator loop is docs/testing/emulator.md §6l: A1 the whole chain
  (the PWA, the server with its loopback bases, the debug launcher, the managed music app), A2 the phone alone on the
  debug feed, B the real SDK with the user's account (the recorder, the DNS capture).
- **Owed for Spotify** (26c "Release"): §6l A ran once (2026-10-14, `docs/testing/2026-10-14-spotify-6l-a.md`; its
  fixes are 26c's "Fixes after the emulator check §6l A") - its re-run is steps 5b and 6's acceptance; part B, its recorded real `sdk_states/` and the DNS capture that replaces the provisional host list
  (one commit of the list, the constants and the comment: the three `spotify_hosts.json` copies, the six constants -
  this app's two in `SpotifyHosts.kt` among them - as 26c's "Release" names it); the Jelly Star checks of 26c's QA
  checklist (real Doze for 2.5 hours with the token refreshed ahead of expiry, the WebView's own audio focus with the real SDK
  (the fake's stub answered it, §5.6), the protected-media origin, episodes in a `uris` list - no switch is built: if the SDK refuses them, the server's parser
  must refuse shows and episodes -, the `context` field around track changes,
  Autoplay after a list and after a context, the leftover queue add, shuffle and repeat).
- **Nothing starts by itself on Sonos either**: adopt sends nothing, a stale or unreachable speaker shows the play
  button, room -> phone plays on only when the speaker was PLAYING. **Every play path is gated** (`playAllowed`, then
  the controller's `mayPlay` before any SOAP): a start, a resume, a step, a natural advance, OUTPUT to a `sonos:` key
  (it starts the speaker, so it is a play) and resumption; a skip on a paused, stopped or not-yet-started room only
  moves where the next play starts.
- **Never a guess in Room**: `optPos`, the pause freeze, the seed of a play and the age term (capped at 5 s, only
  fresh + PLAYING) never reach a stored position; a bookmark always precedes a Stop or a renderer change (after a
  Stop the speaker reads 0:00).
- **The phone's lifecycle on a room** (26a §6): `MY_PACKAGE_SUSPENDED` (`SuspendedReceiver`, `goAsync`) stops our
  stream with the service up (`suspendStop`) or without it (`SonosSuspension.standalone`): live classify, bookmark,
  Stop, `x-rincon-queue:<uid>#0` (the speaker's own Play button plays nothing), disarm. `onDestroy` runs the hand-off
  (bookmark, ours-gated Stop) in a tail after the service; the next adopt and the standalone path wait for it
  (`SonosTail`). A crash leaves the speaker playing until the next start adopts it or a suspension edge stops it.
  `SonosLocks` holds a partial wake lock (timed, renewed) and a Wi-Fi lock while a room is armed and playing or a
  verb is settling; never a screen wake lock (`SourceScanTest`).
- **Volume on a room** (26a §5.3): the keys and slider reach the speaker (remote `DeviceInfo`, ±3 from our own last
  set); every set of ours is clamped to `volume_cap_pct`, read live from the managed config; a session's start (a
  pick, a start, a restart) brings every room of the row louder than the cap down to it once, before Play, inside the
  hush (`SonosTransport.capVolume`); during the session a louder volume set from the Sonos app is never pulled down,
  but a cap lowered at a restrictions broadcast pulls our own louder set (`volOpt`) down once (`capChanged`). No
  audio focus while a room renders (`LocalRenderer.standDown()`).
- **Calls** (26a §6.7): the launcher sends `CALL_HOLD` / `CALL_RELEASE` (session custom actions, granted only to a
  launcher signed like this app, names in `bridge_contract.json`) - since the emulator round of 2026-10-14 `CALL_HOLD`
  means "a call began" (every call's begin while the music app has a session, playing or not; 26c N3); `logic/CallPause.kt`
  decides: a call that begins while nothing plays holds nothing, our own pause (the
  sleep timer runs on), played again only what we paused, never after the kid's own play, a timer that ended or
  while `playAllowed` says no. The audio-mode backstop (MODE_IN_CALL / MODE_RINGTONE, never IN_COMMUNICATION) covers
  a system call on a room and on the web player (`callOn`) without the launcher's listener access or with an older one.
- **Room scope** (26a §5.5): the library's `sonos_rooms` (absent or null = every room; anything else not a list =
  none) limits the sheet, the pick (`ROOM_NOT_ALLOWED`), the controller's belt and a running session (bookmarked,
  stopped, home paused). **A group needs every member allowed** (`groupAllowed`; a `Room`'s members are the whole
  group, uids the cache can't name included, and those are never allowed), so an allowed room grouped with a forbidden
  or unknown one leaves the sheet; under a limited scope the groups are re-read every 60 s while a room plays (one
  GetZoneGroupState, never discovery). The report's `sonos_rooms` (uids `RINCON_…`, names cut as the server cuts
  them, at most 32, never an ip) feeds the parent's card.
- **Discovery never on a timer**: a cache miss, the sheet's "Se etter høyttalere" row or a failed play; opening the
  sheet is one topology refresh. Every URL is built with `HttpUrl.Builder` from an IP literal (no `Uri.parse("http`);
  SOAP is LAN control traffic and has no address guard, but the speaker-playable filter keeps a `publicOnly` entry's
  private-literal audio, a `lan` entry's tailnet-literal audio (100.64/10, fd7a:115c:a1e0::/48: no speaker reaches
  the tailnet), own files, withdrawn items and non-series HLS off the speaker.
- **The instant switch on a room** (`logic/Switch.kt`, `ui/UiModel.kt`): a tap's view is built for the renderer that
  plays (`onRoom`: the speaker-playable queue, a moved start at 0, a step on a playing room from 0) and counted with
  `playerNumber`; the hold lasts while the service still has the tap's command waiting (`PlayerHub.State.waiting`,
  `Commands.MOVES`, at most `SWITCH_WAIT_MAX_MS`) - a room switch or the look at a room delays a play by seconds.
  Design 27's carousel swipe (`SKIP_TO`, `THEN_SKIP_TO`) is in `MOVES` and goes through the `Renderer` (`goTo` ->
  `renderer.seekTo(index, ms)`: on a room `stepTo`, from 0 while playing - `swipeSwitch(..., onRoom)` shows that),
  never ExoPlayer directly; its pages on a room are the speaker-playable queue, numbered by `playerNumberOf` (the
  play order; a Storytel series' reading order - it and `playerNumber` both read one `countedKeys`, design 26b). A
  late episode picture swaps the session player's item in place (`Renderer.replaceItem`); the speaker's DIDL carries
  no art - a listing's `art` is a hash the server serves only to the phone's bearer, so there is no http URL a speaker
  could fetch (design 26a, "Merge of main with design 27").
- Episode pictures (design 27): an item's own art is `items.artHash` (Room 2; its auto migration empties every
  listing version so the next sync re-applies them) and `PlayItem.artHash`; every `Cover` gets the entry's cover as
  `fallback`. `logic/EpisodeArt.kt` has the want set (`wantedArt`: the now window ±2, the keep sets' art, Nytt, then each Spotify list-mode entry's next `SPOTIFY_ART_AHEAD` (50) tracks from where a cover tap starts it, in `later` - a Spotify entry keeps nothing, and the server fetches up to `ART_PER_CHECK_SPOTIFY` (100) of its pictures a check; sent as
  `wantArt` at each sync's end and from the player 2 s after an item change; `sync/ArtPlan.kt`'s `artWant` takes a
  Storytel series' keep set from the download plan's rule, `storytelKeepSet` + `storytelWanted`: the books on the
  phone; a book's cover is its own `artHash` from the server's art pass, design 26b §5.5), the prune (covers dir and the
  small copies: neither a library cover nor in the want set's universe, only on a parsed library), `artOrCover` (the
  session, `setNowPlaying`) and the carousel's rules: `showsCarousel` (more than one item and at least two different
  pictures on its pages, the entry's cover counting as one), `swipeSkip`, `pagerFollow`, `swipeTarget`. A swipe is a
  `PlayRequest` (`swipeSwitch`) and the `SKIP_TO` command (not in `PLAYS`; via `RESTORE` + `THEN_SKIP_TO` before play).
  `Store.coversVersion` moves when a cover lands or goes: slots retry, the player swaps the current item's artwork
  in place (`Renderer.replaceItem`: on the phone `replaceMediaItem`, the same source so playback goes on; on a Sonos
  room only the session player's item, no SOAP).
- **The episode list's pictures and the kid's order flip** (design 39; **the user's decisions, not vibb's**: vibb's
  list shows own art only in the highlighted row and follows the parent's order only). **Pictures**: `rowPictures` =
  `showsCarousel` over the rows shown (never a Spotify context) - a 40 dp `Cover(..., decodeDp = 40.dp)` per row,
  the playing one ringed; a row whose `artHash` is null shows the series cover with its number under `Palette.scrim`
  (by the listing's hash, so it never changes as files land). `decodeDp` decodes with `inSampleSize`
  (`thumbSampleSize`) into its own 2 MiB LRU (`thumbs`, `"$hash@$px"`), its own slot; no small session copy. The
  list reports its visible rows (settled 1 s) to `ListArtWants.note`: the held list `filesDir/music/list_art.txt`
  (`listArtAfter`: the window - the visible rows and `LIST_ART_MARGIN` 10 each side - first, own hashes only, at most
  `LIST_ART_MAX` 60, another entry's list starts empty); only when the window lacks a picture is the want set computed
  and posted to main as a forced `LiveBridge.wantArt` (`wantArtSend`: forced = a `later` hash the last send didn't
  have, whatever `now` is; LiveBridge keeps `sentLater`). `ArtWants.compute` reads `list_art.txt` itself, so every
  sender carries the list (`wantedArt`'s `list`: on the wire after `now`; `keep` = the 600 without it plus it, so the
  prune never loses a kept picture to it, QA L2). **The flip**: Room 5 `order_flips` (`FlipRow(entryKey,
  parentNewestFirst)`, auto migration 4 -> 5, never synced or reported), written by the UI side
  (`MusicViewModel.flip` -> `Store.setFlip` -> `MusicDao.setFlipTx`, which in the same transaction rewrites the stored
  `NowPlayingRow` when it is this entry's and unshuffled: `flippedNowPlaying` = `flippedQueue` from its current item,
  index 0, the place kept - restore, `RESUME`, Home's card and the bar continue the shown way, QA M5); then, only to a
  live player, `Commands.FLIP_ORDER` with the entry key and the new direction (L9): `PlaybackService.flipOrder` reverses
  `Active.order` (a `var`, with `shownNewestFirst`), the queue becomes `flippedQueue(newOrder, queueKeys, current)` (a
  room's speaker-playable queue stays that, M1) and `renderer.reorder` - the phone's ExoPlayer, the Sonos controller's
  bookkeeping (no SOAP), and Spotify list mode (`SpotifyRenderer.reorder`: tracks replaced at once; playing, a fresh
  start at the measured place coalesced like a step; paused, no call and `windowStale` so the next Play is a fresh
  start, never an SDK resume in Spotify's old window; `SpotifyTracker.reorder` keeps the adds, L10). **The one seam**
  is `Snapshot.order` (`shownNewestFirst(newestFirst(entry), flips[key])`: a row whose parent direction no longer
  matches is stale - ignored, pruned at the next sync with a gone entry's, `flipsToDelete`): every play path, the
  numbers, "N av M", the cover's hint and progress and Spotify's `ahead` follow it; `playOrder` - the keep set, the
  download plan, the art's keep sets, `NewItems`, the server's `keep_end` - stays the parent's. The header's trailing
  slot is the button (`Icons.flipOrder`, `R.string.flip_order`, peach when flipped, `selected` + the order words as
  its state description; hidden for a Spotify context, while shuffled, below two rows); the line under it joins the
  summary and `orderWords` (by kind) with " · ", dropping the words when they don't fit (`orderWordsFit`, measured
  at the font scale). After a flip the list scrolls to the playing row. Tests: `OrderFlipTest`, `EpisodeArtTest`'s
  `d39`, `QueueTest`, `FitTest`, `SpotifyRendererTest`/`SpotifyTrackerTest`'s `d39`, `SonosControllerTest`'s `d39`;
  the emulator round is `docs/testing/emulator.md` §6q.
- A new entry is news (design 29, not vibb): a key the phone never knew, once a play has been stored, puts one item
  (the first that can play; Storytel: book 1) in Nytt at its first listing, with a 24 h settle; until that first
  play every listing is only acknowledged (the baseline is open: no news at all); known keys only grow;
  an id that changes key is reset like a removed entry; the sync runs under `Sync.runLock`. The rules are the pure
  `newsBefore`/`newsAfter` in `logic/NewItems.kt` (`NewItemsTest`'s `d29` tests); `Sync` only runs them in
  `MusicDao.newsBeforeTx` (before the inbox loop) and `newsAfterTx` (after it, the tray read inside the
  transaction), and every other tray write is one statement or one transaction (`deleteNews`, `clearNews`,
  `pruneNews`). A `no_audio` on a tray item asks for a sync (`repickWanted`), which re-picks a new entry's item.
- Rows that must fit 320 dp (288 inside the sides) follow `ui/Fit.kt` (`FitTest` at 320/360/411 dp): the player's
  control row (v12's sizes where 332 dp fit, else mockup v15's 44/52/72/52/44 with 6 dp gaps), the bottom bar's flex
  widths, the category tiles, the carousel, the bar's room label (a three-member group) and the Sonos state lines.
  `RoundButton` is `requiredSize`: give it the room, it never shrinks.
- **The library's height** (design 34 P10 b, QA L1): the header, the category tiles and the now-playing bar keep their
  own height; the carousel fills the weighted slot between them (`BoxWithConstraints`). Its cover is
  `libraryFit(slotPx, linesPx, reservePx, density)`, in whole pixels with every fixed part rounded as Compose rounds a
  Dp (`libraryFixedPx`; at density 1.5 a float-dp model overflowed by 2 px) - min(180, the slot less the title and
  hint lines measured with a `TextMeasurer` at the font scale, less the fixed rest), floor `LIBRARY_COVER_MIN_DP` 64
  (120 until the emulator round's N6: Android 16's status bar grows with the font scale; below 120 dp the cover drops
  its kind chip, `coverShowsKind`); below the floor the slot scrolls (the `verticalScroll` is applied only then: it clips, and would cut the cover's
  shadow), so the tiles never slide under the bar. Without the bar its room (`nowBarRoomPx`) is still kept out of the
  cover, so the cover never changes size when the bar comes or goes. The progress line's room is always kept (the cover never
  resizes on a turn). The pager, its side padding, the pull and the cover glyph all take that cover (`carouselSide`,
  `carouselPeek`, `carouselPull`, `carouselGlyph`); `coverDecodePx` stays at the player's 224 dp (the largest a cover
  is shown, one decoded bitmap per hash for both screens). No fixed `180.dp`/`184.dp` in `LibraryScreen` (`FitTest`).
- **Quiet look** (design 34 P10 a, c): an empty library, Nytt and search show `EmptyState` - a 104 dp night disc with
  the headphones/bell/search glyph in peach (decorative, no description: QA L7), the headline and, for the library,
  "Mamma eller pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv."; no animation, no button.
  Soft shadows only: 12 dp under the centred cover (v12), P2's 2 dp under a neighbour (following the page offset),
  the now-playing bar and the disc (`EmptyStateTest`, `FitTest`). The kid screens are always dark.
