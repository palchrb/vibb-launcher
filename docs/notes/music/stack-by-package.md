# Music notes: the per-package part of the stack and layout list

Moved from music/CLAUDE.md on 2026-11-01.

## Stack and layout (continued): the `sonos/`, `storytel/`, `spotify/`, `bluetooth/`, `ui/` and `src/debug/` packages

- `sonos/` - the Sonos port (26a §1-§2, from vibb `pi/sonosd.py`, `pi/vibb/renderer.py` and `pi/daemon.py`'s
  `_sonos_*`): Android-free `Ssdp`, `Soap` (OkHttp + javax.xml DOM, our own UPnP client, no library; a reply at most 1 MiB, every
  call within a deadline - 10 s without a budget -, a DOCTYPE/ENTITY refused before parsing: security review S-L1), `Topology`,
  `SpeakerCache` (`noBackupFilesDir/sonos.json`; players merge, groups replace) + `RoomDirectory` (the sheet's rows,
  stale + one background SSDP round), `Didl`, `SonosUris`, `NrkService`, `PlayBody` (the speaker-playable filter),
  `SonosSession` (vibb's poll machine: classify, cadence, stall, migration probe, disarm), `SonosTransport` (play with
  the hush and the settled seek, verbs, adopt, the park), `SonosBookmarks` (holds and the regression guard),
  `SonosController` (the controller, main-thread, pure), `SonosLifecycle` (the startup and suspension verdicts),
  `RoomScope`, `RendererChoice`; the Android side `SonosRenderer` (a Media3 `SimpleBasePlayer`), `SonosActor` +
  `SonosPoller`, `SonosHome` (Wi-Fi tracking as a gate; no socket is ever bound to a `Network`: the launcher's
  always-on VPN allows no bypass, so the bind is EPERM - 26a §6.5, `SourceScanTest`), `SsdpScanner` (MulticastLock for
  the round only), `SonosLocks`, `SonosSuspension`, `SonosTail`; the event socket (design 53 §2.2): pure `SonosEvents`
  (the messages, the handshake reducer `Plan`, `SocketEvent`) and `SonosSocket` (OkHttp's own WebSocket: `socketWanted`,
  the host gate, the speaker's pinned trust (53-c), the backoff, the parks, the liveness rule); the bar's anchor is
  `logic/SonosAnchor.kt`. Grouping (design 53-b): `GroupVerbs` (soco's join/leave on other rooms, off the actor, one
  Mutex) and `RoomVolumes` (the members' volumes, latest-wins per room, off the actor); the group's volume rules are
  `logic/GroupVolume.kt`; the scope's and the sheet's belts (`sessionAllowed`, `groupingOffered`, `joinOffered`,
  `leaveOffered`) are in `RoomScope.kt`, the sheet's rows in `ui/OutputRows.kt` (`groupSheet`). `HandOverTiming` logs a
  room hand-over's steps under `VibbHandover` (`#n +N ms`: each tap's or play's numbered timeline, the play job carrying its own; step names, kinds, counts and positions only -
  measurement, it decides nothing; `SourceScanTest` bans titles, uris, ids and tokens in its calls; design 54 "Hand-over
  timing (2026-10-25)").
- `storytel/` - the phone's Storytel client (26b §4, from vibb `pi/vibb/storytel.py`; the server reads the shelf and
  lists it, the phone never does; since design 58 the phone never logs in either): `StorytelApi` (pure: the asset
  call's 302 captured as a `Mint`, the fake's `/fake/jwt` and `jwtAnswer`), `StorytelSession` (pure: the server's JWT
  in memory, the one ask, the waits, the 401 rule, the room's prefetch), `StorytelHttp` (the seam and
  `OkHttpStorytelHttp`), `StorytelEndpoints` (Storytel's API host and the debug switch), `StorytelAccount` (Android: the
  process's one session and API, `mint`, `prefetchIfDue`, `state`). `bridge/StorytelBridgeToken.kt` is the AIDL
  session call, `bridge/StorytelLegacy.kt` deletes the old stored login. The rules are in `logic/Storytel.kt`.
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
- `bluetooth/` - design 52 (connect a paired headset from "Spill av på"): `BluetoothAudio` (pure: the A2DP-sink
  filter matched against the routed outputs' addresses, the rows, the connect reducer, `headsetTapPlan`,
  `failureWords`) and `BluetoothLink` (Android: the A2DP proxy awaited off main, `BluetoothA2dp.connect` /
  `setActiveDevice` by reflection on IO). The view model holds the state (`headsetConnect`), the A2DP broadcast
  receiver and the routed-output callback; the proxy lives while the sheet is open or a call runs.
- `ui/` - the screens of `Main.dc.html` v12; `MusicGlyphs.kt` and `res/drawable/music_glyph_*` are generated by
  `scripts/material-symbols.sh`. The launch splash (music rounds r13, M1, M1b): the theme's `windowSplashScreen*` shows
  `res/drawable/splash_vibb_mark.xml` (the launcher's Home splash at rest) alone - no branding image, which Android pins
  to the screen's foot - and goes at the app's first frame, which is the app's own splash (`ui/LaunchSplash.kt`, the
  whole window, no insets): the night ground, the same drawable at the splash's place and size (`SPLASH_MARK_DP` 288 dp
  centred: WMShell's 192 dp icon view, its drawable drawn 1.5x) and `splash_wordmark.xml` ("vibb music", Nunito
  ExtraBold as paths) 24 dp under the rings' lowest ink (`SPLASH_RINGS_BOTTOM_DP` 83.24) - `logic/Splash.kt`,
  `SplashTest` reads both drawables; both written by `scripts/music-splash.py` (`--selftest`). `MainActivity` fades the
  system splash itself (`setOnExitAnimationListener` -> `systemSplashExit`, 250 ms, always `remove()`: the shell's
  default exit slides the app's window up 20 dp); the app's splash covers the screens until `MusicRoot`'s first content
  frame - the snapshot in, the stored row read and the carousel on its start tile (`contentReady`; an empty library's
  empty state at once; 3 s cap, the backstop only) - **and** "vibb music" has stood whole `SPLASH_HOLD_MS` (400 ms;
  round M1b, the user) after the system splash's fade ended (none handed over within 500 ms - a start without one - the
  hold and that wait run concurrently from the first frame: 500 ms; the 3 s cap, from the first frame, wins over the
  hold), then fades over it (`splashHoldEnd`, `splashFadeDue`: pure, the composable feeds it ms since its first frame;
  once due it never turns back), so the wordmark is seen whole and still. Whether it is owed is
  `MusicViewModel.contentShown`: a configuration change shows none, a restored task after a process death shows it again.
  The rings are still (the launcher's breathe AVD is one inhale; a loop would snap back each breath). A failed item's
  real cause is logged (`VibbPlayer`, `logic/PlayFailure.kt`'s `failureLine`: Media3 and `Log` hide an
  `UnknownHostException`), and a name that can't be looked up says "Trenger internett" instead of "Kan ikke spilles nå"
  (`noNetwork`, `failureMessage`; never the guard's `PrivateAddressException`). Accepted residual: a host the parent's Web
  Filter blocks fails the same way, so it says "Trenger internett" too. On a room the player's seek line shows
  "Kobler til høyttaleren…" instead of the times from the tap until the room plays, and the phone's web player
  "Kobler til…" until its first playing state (`logic/HandOver.kt` `timeRow`, `PlayerHub.State.connecting` =
  `SonosController.connecting` or `SpotifyRenderer.starting`; no source named).
- `src/debug/` - the debug feed (`DebugFeedReceiver`, docs/testing/emulator.md §6f; `debug-feed/sonos.json` seeds the
  speaker cache with ip and port for the fake speakers, §6h; `DebugStorytel.kt`: `config.json`'s `storytel_base` for the
  fake Storytel (an old `storytel.json` is deleted unread), §6i; `DebugSpotify.kt`: `config.json`'s `spotify_base` for the
  fake Spotify and `record_spotify_states` for the state recorder, §6l). Never in a release
  (`checkReleaseHasNoDebugFeed` scans the APK; `SourceScanTest`).
