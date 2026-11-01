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
  stays the phone's and always goes to `LocalRenderer`. "Spill av på"'s rows: the phone, the routed outputs, the paired
  headsets not routed now ("Koble til" / "Bytt til", design 52), the rooms - on a live room session our group's block
  and a "+" on every other row (design 53 §2.7, `groupSheet`) -, "Se etter høyttalere" (`outputSheetRows`/`outputSheetItems`). The third renderer is `spotify/SpotifyRenderer` (26c §5);
  `RendererRules.kt` holds the pure rules of which renderer an entry gets, a Spotify item's playability, the queue's
  kind and its claim, and the call test (`RendererRulesTest`).

(The `sonos/`, `storytel/`, `spotify/`, `bluetooth/`, `ui/` and `src/debug/` bullets of this list moved on 2026-11-01: `docs/notes/music/stack-by-package.md`.)

## Build and test

From `music/` with JDK 17 and `local.properties` (`sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` (CI: `./gradlew build`). The
shared files in `src/test/resources/` must equal `server/testdata/music_{library,listing,icons}.json` and
`server/testdata/spotify_hosts.json` (`ContractFilesTest`; `SpotifyHostsTest` checks the constant against it). A release build without the signing properties is unsigned, as the launcher's. Dependencies
are verified against `gradle/verification-metadata.xml` and the wrapper pins its distribution (security review L9,
root `CLAUDE.md` "Build and test"; `BuildChainTest`). The canned SOAP replies in `src/test/resources/sonos/`
(`SonosFixturesTest`) are also what `scripts/fake-sonos.py` answers from (vibb's "two fakes" rule; the fake has no event
socket - the app's connect to 1443 is refused and parks after five, so the emulator checks the polling fallback;
`sonos/ws/` holds the socket's messages - the user's capture of 2026-10-21 and hand-written cases -, `scripts/sonos-ws-probe.py --save`
captures real ones with every id, address, name, serial, device, location, queue and item id and picture URL replaced
(`--rescrub IN OUT` re-applies the rules to an earlier capture); design 53-b: the fake answers the grouping verbs - `x-rincon:` joins, `BecomeCoordinatorOfStandaloneGroup`
from `become_standalone.xml`): change a fixture
and run `python3 -I scripts/fake-sonos.py --selftest` (CI does, `music.yml`). On the emulator the app plays on the
fake speakers (docs/testing/emulator.md §6h; no multicast there, so no SSDP: the debug feed seeds the cache). Likewise
`src/test/resources/storytel/` (the asset 302 and the locked 403) and `storytel_token_answers.json` (the server's
session route, `/fake/jwt`'s answers) are what `scripts/fake-storytel.py` answers from (`--selftest`, CI too; its login
and bookshelf are the server's, `server/testdata/storytel/`): its options `--books cid:MB,...`, `--locked`, `--refuse`,
`--jwt-ttl`, `--off`, `--busy S`, `--jwt-down`, `--revoke-after K`, `--token-ttl`, `--drop-at`, `--slow-mint`,
`--no-download` (minted but never on the phone, for a room) and `--print-listing ENTRY` (the debug feed's listing)
drive docs/testing/emulator.md §6i; every value in it is fake (`FAKE-JWT-<n>`, sessions `1:<16 hex>`,
`FAKE-SIG-<n>`, `k@example.invalid`, never `eyJ...`). `scripts/fake-audio.py` is a fake podcast
host for design 30's checks (silent WAV episodes with Range; `--vary` serves another length per request, the
changed-source check; `--print-listing ENTRY`; `--selftest`, CI too; docs/testing/emulator.md §6f). Spotify's
`src/test/resources/spotify/` (`play_request.json` - the exact play, context play, transfer, queue, shuffle and repeat
requests -, `play_answers.json`, `token_answers.json`, and `sdk_states/*.json`, the state sequences
`SpotifyTrackerTest` replays) and `server/testdata/spotify/` (the accounts and Web API answers) are what
`scripts/fake-spotify.py` answers from (`--selftest`, CI too): the accounts service with PKCE (`--as`, `--deny`,
`--rotate`, `--access-ttl`, `--expire-refresh`, `--bad-client`), the Web API the server and the phone use
(`--snapshot`, `--rate-limit N`, `--quota`, `--not-premium`, `--offset-404`, `--audiobook` - the show as an audiobook's unplayable chapters) and a stub Web Playback SDK playing a tone
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
  Sonos URLs from IP literals); trust is the system's CAs only - **with one exception** (design 53 §2.2, round 53-c): the
  Sonos event socket's own client (`speakerSocketClient`, `SonosSocket.kt`) trusts **the pinned Sonos Device
  Authentication Root CA alone** (`SONOS_ROOT_PEM`, its SHA-256 checked by `SonosTrustTest`; a `KeyStore` holding only
  it - never the system's CAs, never trust-all; intermediates the speaker sends are path-built under it) **and the
  leaf's SAN `urn:sonos:udn:` must equal the uid we dial** (`speakerCertRefusal`, in the handshake, before any message),
  only for `wss` to the cached ip literal of that uid, in a speaker's range (`speakerLiteral`: RFC 1918, link-local,
  IPv6 ULA/link-local - never loopback or the tailnet), port 1443, `/websocket/api` (`socketGate`, checked before any
  connect), its host name verifier accepting exactly that ip. A refused certificate counts as a refused connect (five
  in a row at one ip park the uid; polling stays) but `dates` (the phone's clock, likely: backed off, never parked), and
  logs its reason class only (`chain`, `dates`, `no udn`, `udn mismatch`) - never the certificate's identifiers (its
  SAN holds the household, a user, the serial); a debug build logs the leaf's subject CN, issuer CN and last year once
  per uid and verdict. `SpeakerTrust` stays a plain `X509TrustManager` calling the 2-argument check (no host reaches the
  platform's trust manager: no per-host policy, no Certificate Transparency - qa-53c-code 6). No other client gets
  that trust, and a URL from a socket message (a `MOVED`'s, a player's `websocketUrl`) is never dialled
  (`SourceScanTest`'s `d53`).
- Positions are keyed by (entry key, item key), never by URL.
- A public entry's audio never comes from a private address (21b §2.7): every entry except one the library marks
  `lan` (an RSS feed on the LAN or tailnet).
- Behaviour questions default to what the vibb Pi player does (`pi/daemon.py`, `pi/vibb/*.py`, `tests/`).
- The kid's screens open nothing else: no settings, links, share or web views (`SourceScanTest`; the one WebView is
  the Spotify web player's, headless and never attached to a window - below).
- The bridge (design 21 step 3, `bridge/`): the launcher is the only way to the server. `BridgeSync.pull` runs first
  in every sync (library, changed listings, covers, own files copied + checked + released; Storytel's session is asked
  only when a mint needs it, design 58) and `push` sends the report (with the own files held) at its end; `LiveBridge` sends
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

## Where the rest is

Area notes, moved verbatim from this file on 2026-11-01; read only the ones your task touches. The core above keeps the stack and layout, build and test, and the rules that bind everything (playback and network, the cache, cleartext, positions, the bridge contract, the media surfaces).

- `docs/notes/music/stack-by-package.md` - Stack and layout, continued: `sonos/` (26a), `storytel/` (26b), `spotify/` (26c), `bluetooth/` (design 52), `ui/`, `src/debug/`
- `docs/notes/music/renderers-and-sonos.md` - Rules: the renderer axis (26a §0, §3-§4); Sonos threading; renderer.json; the DIDL is vibb's; nothing starts by itself on Sonos; the Sonos event socket (design 53); never a guess in Room; the phone's lifecycle on a room; volume; Bluetooth headphones (design 52); calls (26a §6.7); room scope; grouping in "Spill av på"; discovery never on a timer; the instant switch on a room
- `docs/notes/music/storytel.md` - Rules: Storytel is download-only on the phone; the account and the mint (26b, design 58); downloads; secrets; the debug switch; Storytel on a room
- `docs/notes/music/spotify.md` - Rules: Spotify is the third renderer; only our tracks are ours; nothing starts by itself; context mode; the streaming switch; the lines and the word; no source on the kid's screens; the web player's WebView; the token; secrets; the debug switch; owed for Spotify
- `docs/notes/music/episode-art-and-lists.md` - Rules: episode pictures (design 27); the episode list's pictures and the order flip (design 39); shuffle and repeat (design 49); the player and library picks (design 50 + 46 §1.4)
- `docs/notes/music/library-ui.md` - Rules: a new entry is news (design 29); rows that fit 320 dp (`ui/Fit.kt`); the library's height (design 34 P10 b); quiet look (design 34 P10 a, c)
