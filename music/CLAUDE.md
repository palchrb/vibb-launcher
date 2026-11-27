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

- `model/` - the library and listing documents (lenient). `logic/` - every rule, pure and unit-tested: put new rules
  here, not in the Android classes. `data/` - `Store` (files), Room (`MusicDb`), `Snapshot`. `sync/` - the sync and
  download workers (WorkManager; never a dataSync FGS). `media/` - the one `SimpleCache`, the guarded OkHttp clients,
  design 30's cache wrappers and design 117's sealed books. `playback/` - `PlaybackService`, `PlayerHub` and the
  renderers (`LocalRenderer`, Sonos, `spotify/SpotifyRenderer`; `RendererRules.kt`). Each package in full, and
  `sonos/`, `storytel/`, `spotify/`, `bluetooth/`, `ui/`, `src/debug/`: `docs/notes/music/stack-by-package.md`.

## Build and test

From `music/` with JDK 17 and `local.properties` (`sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` (CI: `./gradlew build`). The
files it shares with the server and the launcher are read in place from `../contracts/music/` (design 133,
`contracts/README.md`): `music_{library,listing,icons}.json` (`ContractFilesTest`, which also fails on a copy in
`src/test/resources/`) and `spotify_hosts.json` (`SpotifyHostsTest` checks the constant against it). A release build without the signing properties is unsigned, as the launcher's. Dependencies
are verified against `gradle/verification-metadata.xml` and the wrapper pins its distribution (security review L9,
root `CLAUDE.md` "Build and test", `docs/notes/ci.md`; `BuildChainTest`).
The test fixtures the emulator fakes answer from, and every fake value's shape: `docs/notes/music/test-fakes.md`.

## Rules that are easy to break

- Nothing ever starts playback by itself: a restart brings the now-playing bar back paused (`NowPlayingRow`).
- Playback never waits for the network: a copy plays as it is, a stream only through the gate (Wi-Fi unless the
  parent's "Stream on mobile data" switch, never roaming), else "Ikke lastet ned - trenger Wi-Fi" and the
  queue moves on. The download job follows the other switch, "Download on mobile data" (design 30 §1.1:
  `streamAllowed`/`downloadAllowed` in `logic/NetGate.kt`, one formula, `Store.streamMobileData` /
  `downloadMobileData`). The player's upstream asks the streaming switch again at every open (`StreamGate`, design
  30 §1.6): a copy's holes and the preloaded next item too; its refusal is "Ikke lastet ned", never an item error.
- **A stream is written to the cache as it plays** (design 30; ours, not vibb's), never for Sonos, Storytel or
  Spotify; a cache error never fails playback; the player's **held set** (`PlayerHub.heldLocal`) is never pruned,
  evicted, swept or settled - every removal reads it again right before it removes. **A Storytel book is sealed**:
  only through `SealDataSink`/`SealDataSource`, every plain factory passes an explicit sink, never Media3's default
  `CacheDataSink`. In full: `docs/notes/music/core-rules.md`.
- Prune only on a parsed listing, and only what the library no longer names; positions and bookmarks are never
  deleted except by vibb's `clear_state`; the keep set and the held set are never evicted (the played extras:
  `docs/notes/music/core-rules.md`).
- **Cleartext http is allowed** (Sonos, http enclosures, `lan` feeds, the fakes); what may be fetched is the app's own
  rule. Trust is the system's CAs only, except the Sonos event socket's client, which trusts the pinned Sonos root
  alone and checks the leaf's udn against the uid it dials; no other client gets that trust, and a URL from a socket
  message is never dialled. In full: `docs/notes/music/core-rules.md`.
- Positions are keyed by (entry key, item key), never by URL.
- A public entry's audio never comes from a private address (21b §2.7): every entry except one the library marks
  `lan` (an RSS feed on the LAN or tailnet).
- Behaviour questions default to what the vibb Pi player does (`pi/daemon.py`, `pi/vibb/*.py`, `tests/`).
- The kid's screens open nothing else: no settings, links, share or web views (`SourceScanTest`; the one WebView is
  the Spotify web player's, headless and never attached to a window - `docs/notes/music/spotify.md`).
- **The bridge** (design 21 step 3, `bridge/`): the launcher is the only way to the server. Bind only the
  restrictions' launcher, only when it is signed like this app; honour a nudge only from such a launcher. The switches
  and limits are read live from the managed config. The AIDL is the launcher's own,
  `../contracts/shipped/bridge-aidl/` - append, never reorder; shared names are in
  `../contracts/music/bridge_contract.json`. In full: `docs/notes/music/core-rules.md`.
- The session gives another app's controller (the launcher, the system UI, Bluetooth, any app) the transport only:
  no `COMMAND_SET_MEDIA_ITEM`/`COMMAND_CHANGE_MEDIA_ITEMS`, and `onAddMediaItems`/`onSetMediaItems` refuse every
  package but ours (security review S-L2, `foreignPlayerCommands`, `mayChangeItems`). Only a trusted controller reads
  what plays (`READ_COMMANDS`: the timeline, the metadata, the current item): the launcher (same signer) and what
  Media3 calls trusted (`ControllerInfo.isTrusted`: the system UI, Bluetooth through the platform session, or an app
  with notification access); any other app gets play, pause and skip without the queue. Emulator rows owed (Android 14
  and 16): an outside app's Media3 controller gets no title or queue and can still pause; System UI's media card still
  shows title and art; the launcher's card unchanged. Nothing goes to a backup or a
  device transfer (`data_extraction_rules.xml`, `backup_rules.xml`: every domain excluded; P-L3).
- The launcher's media surfaces (design 21 step 4) read the platform session and `setNowPlaying` (also sent with
  every 15 s position save while playing); its Home card opens `MainActivity` with `EXTRA_OPEN` = player and, on its
  snapshot, `EXTRA_RESUME` (the RESUME command: the stored queue where it stopped, or play on) - honoured once, never
  from Recents or a restored instance (`resumeRequested`). Listening never counts as screen time, so the app never
  keeps the screen on (`SourceScanTest`). While the launcher has suspended the app (a time rule) nothing starts
  playing (`playAllowed` in `KidPlayer`, the play commands and playback resumption; `GuardsTest`).

## Where the rest is

Area notes, moved verbatim from this file on 2026-11-01 and 2026-11-27; read only the ones your task touches. The core
above keeps the stack, build and test, and the rules that bind everything, the long ones in short.

- `docs/notes/music/core-rules.md` - the four long binding rules in full: the stream written through the cache (the held set, `sameSource`, `LengthGuard`, sealed Storytel books); prune only on a parsed listing and the played extras; cleartext, https-first and the Sonos socket's pinned root; the bridge (the pull and push, the nudge's report, the managed config, the AIDL)
- `docs/notes/music/test-fakes.md` - the test fixtures `fake-sonos.py`, `fake-storytel.py`, `fake-audio.py` and `fake-spotify.py` answer from, their options, and the fake values rule
- `docs/notes/music/stack-by-package.md` - Stack and layout in full: `model/`, `logic/`, `data/`, `sync/`, `media/`, `playback/`, `sonos/` (26a), `storytel/` (26b), `spotify/` (26c), `bluetooth/` (design 52), `ui/`, `src/debug/`
- `docs/notes/music/renderers-and-sonos.md` - Rules: the renderer axis (26a §0, §3-§4); Sonos threading; renderer.json; the DIDL is vibb's; nothing starts by itself on Sonos; the Sonos event socket (design 53); never a guess in Room; the phone's lifecycle on a room; volume; Bluetooth headphones (design 52); calls (26a §6.7); room scope; grouping in "Spill av på"; discovery never on a timer; the instant switch on a room
- `docs/notes/music/storytel.md` - Rules: Storytel is download-only on the phone; the account and the mint (26b, design 58); downloads; secrets; the debug switch; Storytel on a room
- `docs/notes/music/spotify.md` - Rules: Spotify is the third renderer; only our tracks are ours; nothing starts by itself; context mode; the streaming switch; the lines and the word; no source on the kid's screens; the web player's WebView; the token; secrets; the debug switch; owed for Spotify
- `docs/notes/music/episode-art-and-lists.md` - Rules: episode pictures (design 27); the episode list's pictures and the order flip (design 39); shuffle and repeat (design 49); the player and library picks (design 50 + 46 §1.4)
- `docs/notes/music/library-ui.md` - Rules: a new entry is news (design 29); rows that fit 320 dp (`ui/Fit.kt`); the library's height (design 34 P10 b); quiet look (design 34 P10 a, c)
