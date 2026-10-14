# QA 26c step 5b code review - Spotify in the music app's service, UI and report; emulator §6k

Scope: branch `music-3-spotify`, `1bebd77..1d30a0f` (one commit, 29 files: `playback/{PlaybackService,PlayerHub}.kt`,
the new `playback/RendererRules.kt`, `data/{Snapshot,Store}.kt`, `ui/{LibraryScreen,ListScreens,MusicRoot,
MusicViewModel,PlayerScreen,UiModel}.kt`, `logic/{Spotify,Report}.kt`, `sync/Sync.kt`, `spotify/SpotifyRenderer.kt`,
`DebugSpotify.kt`, `scripts/fake-spotify.py`, `docs/testing/emulator.md` §6k, `music.yml`, root `CLAUDE.md`, the tests,
the design's status). Read against root and `music/CLAUDE.md`, design 26c (§4, §5.2-§5.10, §7.1-§7.3, §9.3-§9.4, §10,
§11 steps 5a/5b/6, the design QA fix round and every status section, esp. 5a's "Owed by step 5b", its fix round's
"Pinned for step 5b", and "Step 5b implementation status" with its 11 deviations and its spec findings),
`qa-26c-step5a-code.md`, `qa-26b-step4b-code.md` (the template and the service-site rules it enforced for Storytel),
design 28 (§0-§1: the window stamps from `setNowPlaying`), `emulator.md` §6h, §6i and the new §6k, and the code each
of them names (`SpotifyRenderer.kt`, `SpotifyTracker.kt`, `TokenCache.kt`, `SpotifyWebHost.kt`, `LocalRenderer.kt`,
`Sync.kt`, `NewItems.kt`, `DebugFeedReceiver.kt`, the server's `spotify_binding_card`). Line numbers are `1d30a0f`'s.

Runs, here (the worktree, JDK 21, `music/local.properties`; no emulator, no VM, no account):
- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD SUCCESSFUL, then
  the same with `--rerun-tasks`: **759 of 759** JVM tests, lint 0 errors / 39 warnings (the same 39 as step 5a, none in
  a line this step wrote).
- `python3 -I scripts/fake-spotify.py --selftest`, `fake-storytel.py --selftest`, `fake-sonos.py --selftest`: OK.
  `--print-library`, `--print-listing 6` (3 tracks, keys unique: the duplicate fix holds) and `--print-listing 6
  --tracks 60` (60 unique `FAKEtrack…` keys, version `11ee11ee11ee11ee` - the same as the 3-track one; harmless: the
  sync applies every inbox listing whatever its version, `Sync.kt:136-150`).
- `git diff --stat 1bebd77..1d30a0f -- server launcher music/gradle music/app/build.gradle.kts
  music/app/src/test/resources music/app/src/main/aidl music/app/src/main/AndroidManifest.xml music/app/src/main/res`:
  empty - no dependency, no contract file, no string, `launcher/` and `server/` untouched.
- Mutations (each reverted, `git status` clean): two of the implementer's three re-done - the natural advance's seek on
  every phone renderer (`RendererRules.kt:73`, `kind.onPhone`) -> red (`RendererRulesTest`, the M4 case); a Spotify
  start moving to another item (`playableIndex` -> `firstPlayable` for every entry) -> red (`RendererRulesTest`, all or
  none). One of mine: a context-mode entry visible (`Snapshot.kt:102` without the mode test) -> red
  (`SpotifyRulesTest`).
- Service-level sequences, built on `SpotifyRendererTest`'s harness playing the service's part (a scratch copy, not
  committed): **red** - a detached web player resumed by the account's other app (M1, quoted there); **green** - a
  package suspension while the play call is in flight (nothing sounds, the page stays destroyed, one play call, no
  focus, no locks), the token's `off` at the SDK's ask while playing (paused, `none` stored, the page cleared, the
  marker gone). A Spotify tap while a room renders and a headset's play after a restart with a stored Spotify queue
  offline were walked in the code (below, "Checked and fine"); the service has no JVM harness (deviation 10).
- Media3 1.7.1's resumption callback, disassembled from the Gradle cache (`MediaSessionImpl$1.onFailure`): after a
  failed `onPlaybackResumption` future it calls `Util.handlePlayButtonAction(playerWrapper)` - see "Checked and fine".
- Text widths: Nunito Bold from `res/font/nunito_bold.ttf`'s advances (a 40-line script; it reproduces `FitTest`'s own
  numbers exactly, 184.8 and 191.8 dp) - L1.

**Verdict: not ready; a short fix round first.** Counts: **0 High, 2 Medium, 6 Low.** The step is carefully built and
mostly right: every row of §5.10's table has its site and its rule (the pure rules in `RendererRules.kt`, each asked
at its site and pinned there), the web player is made once and attached only through `attach()` with the Exo stood
down, `onError` stays synchronous on main for a Spotify item and the pin is real, a Spotify MediaItem can't reach
`ItemMediaSourceFactory`, playback resumption gives a stored Spotify queue the room's shape, every play path reaches
the network gate (the service's line or the renderer's belt), the outcome file, the report's word and the tiles'
lines follow §4.4/§5.8, context entries are absent everywhere, and the fake and §6k are a usable loop. The two
Mediums: a **detached** web player (the kid moved on to NRK, or a room was adopted) still answers the account's other
Spotify app within its 5 idle minutes - it takes the audio focus from the phone's own player and plays, unseen, with
no session that shows or stops it; and the WebView's console goes to logcat in a release build too (the channel
§7.1 says doesn't exist). §11's acceptance ("§6j part A runs end to end") stays open on the user's emulator: nothing
of §6k could run here, and L3 lists the lines that would not work as written.

## Findings

### M1 (Medium) - a detached web player is started by the account's other Spotify app: it takes focus and plays unseen

- **Where:** `M/spotify/SpotifyRenderer.kt:1081-1090` (`Event.Resumed` while `!pwr`: `ports.mayPlay() &&
  acquireFocus()` -> `pwr = true`, whatever the renderer's attachment); `M/playback/PlaybackService.kt:382-401`
  (`attach`: the old renderer saved, paused, its `onEvent` cut - nothing more for the web player) and `:743-749`
  (`usePhoneRenderer`); `:752-758` (`queue = { active.takeIf { renderer === spotify } }`, deviation 5); `:1500` (an
  adopted room attaches over the web player the same way).
- **Why it matters:** the page outlives a detach by up to 5 minutes (§5.2; the idle timer, `SpotifyRenderer.kt:1327-1337`). The tracker
  still holds the detached queue and reads a resume of our paused track from the account's other controls (the kid's
  tablet or PC, which lists "Vibb" as the active device) as `Resumed`. The renderer then requests `AUDIOFOCUS_GAIN` -
  the phone's ExoPlayer, playing NRK now, loses focus and pauses - holds the wake and Wi-Fi locks, cancels its idle
  timer and plays the rest of its window. Nothing on the phone shows it: the session wraps `LocalRenderer`, the
  notification, Home's card and the lock's tile show NRK paused, `setNowPlaying` says paused (design 28's window
  closes), and the kid has no pause button for it - only playing something else (the focus loss pauses it) or the
  other device. Deviation 5 stopped the wrong-entry writes (`saveNow` reads `queue()`: right, re-checked - `ports.save`
  is called only there); the sound itself isn't gated. While the web player is attached the same resume is fine (5a's
  deviation 8: the session shows it and the kid can pause it).
- **Counter-example** (red: `[focus, locks, showsPlaying] = [true, true, true]`, no `vibb.pause()` after the detach's):
  ```kotlin
  val r = playing(); post(state(0, 20_000))
  r.saveNow(force = true); r.pause(); r.cancelFade(); r.onEvent = null; active = null   // the service's attach(local)
  post(state(0, 20_000, paused = true)); advance(60_000); ports.focusHeld = false
  post(state(0, 20_500))                                                              // the other app's play on "Vibb"
  assertEquals(listOf(false, false, false, "vibb.pause()"), listOf(ports.focusHeld, ports.locks, r.showsPlaying, page.calls.last()))
  ```
- **Fix:** a detached web player owns nothing: the service tells it (`spotify.detached()` from `attach()` when the old
  renderer is the web player, or the renderer reads `queue() == null`), and then `tracker.lost()` plus a `detached` flag
  make every later playing state not ours - `vibb.pause()` at once, never focus, locks or `pwr` - until the next
  `load()` (a re-attach always loads). `Resumed` while detached is paused like `NotOurs`. The counter-example as a
  `SpotifyRendererTest` case, and a `GuardsTest` pin that `attach` tells the web player.

### M2 (Medium) - the WebView's console goes to logcat, in a release build too

- **Where:** `M/spotify/SpotifyWebHost.kt:259-270` (`Chrome` overrides only `onPermissionRequest`; no
  `onConsoleMessage`); design §7.1 ("the token ... never into a URL, the DOM or a log"; "no `Log.` in `spotify/`" and
  5b's debug-only `spotifyLog`), §6k's secrets grep (part A only).
- **Why it matters:** `WebChromeClient.onConsoleMessage` returns false by default, and the WebView then writes every
  console message of the page - Spotify's iframe included - to logcat (`I/chromium: [INFO:CONSOLE(n)] "…", source: …`),
  in a release build, outside every rule this design wrote for logs. What Spotify's SDK prints is Spotify's choice and
  changes in place (it is never pinned, §7.2). The case that matters: a failed WebSocket is reported to the console with
  its URL ("WebSocket connection to 'wss://…' failed"), and the SDK's dealer connection carries the access token in its
  query (`wss://…dealer.spotify.com/?access_token=…` - from memory of the web player, **not re-verified today**; the
  stub can't show it). A dropped Wi-Fi in Doze is exactly when that happens: the kid's account token (an hour, the
  account's whole scope set, §7.1 L2) in logcat, read by adb and bug reports. The channel exists whatever the URL holds;
  the URL is why it is Medium.
- **Fix:** `Chrome.onConsoleMessage` returns true (handled) always; in a debug build it may log the level and the
  source's host only - never the message text or the source URL (a `console.error` of a URL is the same leak). A
  `SourceScanTest` pin (the override present in `SpotifyWebHost.kt`, returning true, no `message()` in a log line).
  §6k part B adds `adb logcat -d | grep -E 'access_token|BQ[A-Za-z0-9_-]{20}'` after a Wi-Fi drop while playing, and
  §7.1 names the console channel.

### L1 (Low) - "Spotify virker ikke på denne telefonen" doesn't fit the player's state line at 320 dp; `FitTest` doesn't measure the Spotify lines

- **Where:** `res/values-nb/strings.xml:71`; `ui/PlayerScreen.kt:99` (12 sp Bold under the entry name, in the header's
  column: 320 - 2×16 - 44 - 44 = 200 dp); `test/.../ui/FitTest.kt:127-140` (the Sonos four only). Step 5b is the step
  that puts the seven lines on the screen.
- **Measured** (Nunito Bold 12 sp, the font's advances): nb "Spotify virker ikke på denne telefonen" **211.7 dp** (>
  200: ellipsized on the Jelly Star); the rest fit - en "Spotify doesn't work on this phone" 194.7, "Spotify is playing
  somewhere else" 190.6, "Spotify isn't available right now" 177.7, nb "Spotify er ikke tilgjengelig nå" 163.5, the
  others under 160. On the tiles (13 sp, 288 dp) all fit.
- **Fix:** nb "Spotify virker ikke på telefonen" (174.2 dp; same meaning), and the seven Spotify lines in both languages
  added to `FitTest`'s map.

### L2 (Low) - deviation 7 sends a real server token to the debug base on a managed phone

- **Where:** `SpotifyRenderer.kt:1606-1615` (with the switch set and the app managed, the token comes from the bridge,
  while `apiBase()`, `apiOverLan()` and the page's `sdkUrl()`/`webProxy()` still point at the debug base); design §9.3
  ("a `config.json` left on a real phone running a debug build must never send a token elsewhere").
- **Why:** before deviation 7 the switch implied the fake's own tokens, so a real token never met the debug base. Now
  a debug build on a managed phone with a leftover `spotify_base` sends the real server's token over plain http to
  `10.0.2.2:8731` (the play calls' `Authorization`, and the stub's `connect?token=` through the proxy) - on a real
  phone a LAN address. Debug builds only, and 26b accepted the same base rule for Storytel; still a new path the
  section's argument doesn't cover.
- **Fix:** with the switch set, a bridge token is used only when it is a fake one (`FAKE-ACCESS-` prefix - every fake
  value is shaped so, §7.1); anything else answers `Unreachable` with one debug log line ("a real token with the debug
  switch - not sent"). A `SpotifyRulesTest`/`SourceScanTest` pin; §9.3 says so.

### L3 (Low) - §6k: lines that don't work as written

Each checked against `scripts/fake-spotify.py`'s routes and log lines, the app's log strings and the platform:
- `emulator.md:918` "`authorize: consented as fakeparent`" - the fake logs the consent key: `consented as parent ->
  FAKE-CODE-1` (`fake-spotify.py:394`), and `/fake/as?user=kid` logs `the next login consents as kid` (`:401`).
- `:949` "`device …: getOAuthToken ok`" in a cover tap - logged only by the stub's renewal timer (`_renew`, every
  `--token-ttl` seconds, `:616`); the default `--token-ttl 0` never logs it. Drop it, or run A1 with `--token-ttl 60`.
- `:928` "`GET /v1/playlists/…/items?…: 200 (fakeparent)`" - the fake logs the path without its query (`:503`).
- `:968-970` "`adb logcat -s ExoPlayerImpl` stays silent" - it can't: the service builds `LocalRenderer`'s ExoPlayer in
  `onCreate` (`LocalRenderer.kt:51`), which logs `ExoPlayerImpl: Init …` at every service start. Check instead that the
  fake logs one `play:` and `adb shell dumpsys media_session` shows the Spotify title in the active session. Also: after
  `am force-stop` the package is in the stopped state, and whether the platform's media-button dispatch reaches a
  stopped package's receiver isn't known here - if `MEDIA_PLAY` reaches nothing, repeat the check after a reboot.
- `:992` "log both accounts in again, play: `ok`" - the music app still holds `TokenCache`'s 10-minute `reauthorize`
  cooldown in memory (`TokenCache.kt:167`, `:197`): force-stop it first (the section's top note says so for a fake
  restart; this line reads as if it plays at once).
- `:1030` A2's "`report.json` after the next sync" - unmanaged, only the debug feed's broadcast runs a sync: say
  "broadcast the receiver, then".
- `:1057` "No Spotify entry left" - the clearing is the service's (`PlaybackService.kt:291-300`); with the service down
  it happens at its next start. Say "with the app open (the service up)".
- `:1065` part B's "logcat `spotify: the real hosts`" - `DebugSpotify.applyBase` logs only when the base changes in
  the running process; a fresh process with no key logs nothing.
- Fix: the lines as above. (Everything else in A1/A2 matches: the fake's `play:`, `queue:`, `connect:`, `sdk:`,
  `phone token for kid:`, `audio <id>: <status> Range …`, `device …: -> elsewhere`, `… 429 QUOTA_EXCEEDED (--quota)`
  and `token: 400 invalid_grant …` lines; the app's `spotify: the web player is ready`, `wake and Wi-Fi locks held`,
  `web player destroyed`, `a track that isn't ours was paused`, `web player's cookies, storage and cache cleared`,
  `permission asked by …`, `blocked a request to …`; the logcat tag `VibbSpotify`; the wake lock tag `vibb:spotify`;
  the strings; the 60-track listing's keys.)

### L4 (Low) - deviation 8: the last word outlives the binding

- **Where:** `SpotifyRenderer.kt:585-590` (`forget()` writes no word); `PlaybackService.kt:291-300`; design §4.4
  ("`none` (not bound, or never played)").
- **Why:** an unbound phone keeps reporting its last word (`ok`, `not_premium` …). The server hides it while unbound
  (`spotify_binding_card` reads `reported` only under a bound account), but a later binding - another child's account
  - shows the old account's word on the new binding's card ("On the phone: Spotify says this account has no Premium")
  and "Spotify - spør en voksen" on every tile until the first play under the new account. Deviation 8's reason (the
  removed links leave a true word) holds as well for `none`: with no Spotify entry the phone can't play Spotify.
- **Fix:** `forget()` from the service's "no Spotify entry" path writes `none` too (the token's `off` already does);
  deviation 8 rejected. A rebind without an unbind still shows the old word until a play - inherent (the app learns the
  account only from a token), and §5.8's "until a play succeeds" says so.

### L5 (Low) - tests: a vacuous pin, a vacuous assert, and the detach the service does is never exercised

- `GuardsTest.kt:706`: `PlayBody.kt` "contains `spotify`" passes on a comment; the real pin is step 3's
  `SpotifyRulesTest` (`speakerDropReason` = `spotify`). Pin the function's answer there, or drop the line.
- `LibraryViewTest.kt:66`: `snap.onPhone[6].isEmpty()` asserts the fixture, not "no offline marks"; assert
  `listRows(...).none { it.offline }` for a Spotify entry (the list's summary is already pinned in `GuardsTest`).
- `SpotifyRendererTest.kt:1180` (deviation 5's case) nulls `active` but doesn't detach as `attach()` does (pause,
  `onEvent = null`) and never posts a later state - M1's case belongs beside it.

### L6 (Low) - the spec body lags the step's accepted deviations

- §5.10's `start` row ("no load or play": deviation 2's load paused), `onItemChanged` row (deviation 4), the
  "Which renderer" bullet (deviation 5's detached queue, and M1's detach); §9 and §9.4's headings and §11's step 5b
  ("§6j": deviation 1 - the status says so, the body doesn't); §9.3 (deviation 7, and L2); §9.4 part B's "each compact
  state post logged" against §7.1 (below); §4.4 with L4; §7.1 with M2.
- **Fix:** as step 4's L8 and 5a's L10: bring those paragraphs in line in the fix round.

## The implementer's deviations (11, all judged)

1. **§6k, not §6j**: accept. Every pointer that names the Spotify section in code, CI and root `CLAUDE.md` now says
   §6k (`grep` of `music/`, `scripts/`, `.github/`, `docs/testing/`: only design 28's §6j remains, correctly); the
   design body still says §6j (L6).
2. **A refused start loads the queue paused**: accept. Checked: `start()` (`PlaybackService.kt:705-733`) loads only
   after `usePhoneRenderer`; `load()` opens no page and sends nothing; the load's `ItemChanged` reaches `onItemChanged`
   with `auto = false` (no gate), whose `saveNow()` finds no measurement after `tracker.load` (`measured = null`,
   `SpotifyTracker.kt:172`) - no bookmark at 0, no `NowPlayingRow`; `startedPlaying` isn't called (the tray item
   stays). Two side effects, both right for a tap: the phone's own player is paused, saved and stood down (the tap's
   intent), and `unreachable` is stored (§5.8's row). After a restart the bar shows the queue that last played (the
   refused Spotify queue was never persisted) - consistent with "nothing played".
3. **`SpotifyRenderer.refused(line)`**: accept - the private `refuse` behind a `released` guard; the word as §5.8
   (`NO_WIFI` none, `UNREACHABLE` `unreachable`); called only through `refuseSpotify()` with the renderer cast.
4. **`onItemChanged` gates only Spotify's own advance**: accept. Every load path was walked: `start` (gated, play only
   with the gate), `restoreThen` (`play = false`, then `goTo`/`seekTo(ms)`, gated or no play), `comeHome` (never a
   Spotify entry), playback resumption (through `start`), `resumeLast`/a tile on the loaded entry/`KidPlayer.play`/
   `CALL_RELEASE` (`renderer.play()` -> `begin()`/`resume()`, both asking `networkLine()` first), the renderer's own
   `NewWindow`, `ShuffleOrRepeat` and the unplayable move-on (`request` -> `begin`). No play reaches the page without a
   gate; the service's gate adds the line.
5. **A detached web player sees no queue**: accept for what it does - `saveNow` is the renderer's only `ports.save`
   (`SpotifyRenderer.kt:488`), so no position can reach another entry in the 5-minute window; and re-attaching for
   another entry can't write the old place into the new `Active` (`attach` -> `active = a` -> `load` is one main-thread
   turn, and `tracker.load` drops the measurement). Not enough on its own: M1.
6. **A context-mode entry shows nothing**: accept. Absent everywhere: `visible()` empty (`Snapshot.kt:102`) -> not in
   `entries` (the carousel, `categoryTiles`, search), the Nytt rows and the bell count filter by `visible`
   (`MusicRoot.kt:213-216`, `NewNotifier.kt:36-37`), `UiModel` counts only visible keys, a stored queue of it restores
   `Empty` (`restore()` finds no entry), `downloadPlan` skips Spotify. The phone reports nothing about it (no
   `downloads` row, no item error) - the parent's card says "Spotify picks the order" and the phone shows no tile until
   step 6; §6k says so (`emulator.md:944`). A context listing has no items (§3.3), so no news either.
7. **The fake's token route only when unmanaged**: accept for §6k part A - with it, A1's chain is real (the server's
   token from the fake's accounts base, through the launcher's courier, to the fake's Web API); A2 runs unmanaged and
   uses `/fake/token`. The managed path needs nothing the user can't set up (§2's server with the two loopback bases,
   §4's device-owner debug launcher, `fakekid` bound). With L2's guard.
8. **"No Spotify entry left" doesn't write `none`**: reject - L4.
9. **"Liste" says "Spotify"**: accept - the attribution (§7.3) at no new string; the rows show no offline mark anyway
   (`row.offline` is `key in onPhone`, never true for Spotify).
10. **The service tests are rules, renderer seams and source pins**: accept - no Robolectric without a new dependency.
    Each of §5.10's five service tests is covered by a rule test, a renderer test and a site pin, in that order; the
    pins are exact-text and break on any rewrite (that is their cost, not a hole). The synchronous `onError` pin is
    real: `attach` sets `next.onEvent = { onRendererEvent(it) }` (`GuardsTest.kt:80`), `onRendererEvent` calls
    `onError(event.code)` directly (`:712`), and the move-on after `renderer.saveNow()` holds no `launch`, `post`,
    `withContext`, `async`, `handler.` or `delay(` (`:720`), with exactly two `scope.launch` in `onError` (the copy
    and the record).
11. **The fake's duplicate and `audio` line**: accept - the server's listing keys a track once
    (`spotify::tests` "duplicates once"), and the selftest now checks unique keys; the `audio` line prints the id, the
    status and the `Range` header only (no token: the tone's URL has none).

## The spec findings (7)

All right: §6j taken by design 28; §5.10's `start` row can't both load nothing and carry the line on the loaded tile;
5a's `debugTokens()` made A1's token path impossible; §9.4 part B's state logging contradicts §7.1; `UiModelTest`
didn't exist; `--print-listing 6`'s duplicate key; the restarted fake reads `refused`, not `reauthorize` - right: the
server's token route hands out its kept access token unchecked, the new fake answers it 401, `TokenCache` gets the
same token back and ends the start as `TokenRefused` (§5.4 L4's limit), so §6k restarts the server for the
`reauthorize` check (it must then refresh, and the new fake knows no refresh token: `invalid_grant`, `needs_login`).
Added here: a detached web player can still be started from outside (M1); the WebView's console channel (M2).

## The state-recording gap (§9.4 part B vs §7.1) - decided

§7.1 is right and stays: no state JSON in a log line, debug or not. Real `sdk_states/` sequences still need recording,
and logcat is the wrong place (unbounded, shared, rotated). A debug-only recorder:
- **Where**: a port, `SpotifyPorts.recordState(json: String)`, called by `SpotifyWebHost`'s bridge `state()` with the
  compact JSON as received (already capped at 8 KB and validated); the main source set's implementation is a no-op;
  `src/debug` (`DebugSpotify.kt`) supplies the writer - so a release has none (`checkReleaseHasNoDebugFeed` and a
  `SourceScanTest` pin that the main implementation is empty).
- **When**: only while the debug feed's `config.json` says `"record_spotify_states": true`, read at the receiver and at
  `DebugStartup` like `spotify_base`.
- **What and where to**: one JSON line per state, `{"t": <elapsed ms>, "state": <the compact JSON>}`, plus the page's
  `ready`/`notReady`/`error(kind)` events and our own Web API calls by name only (`play from 3`, `queue 51`) so a
  sequence reads like `sdk_states/*.json`, appended to `getExternalFilesDir("debug-feed")/sdk_states/<start time>.jsonl`
  (adb-pullable, outside the app's data). Never a token (none passes through `state()`), never the SDK's error text.
- **Which step**: **6**. Step 6 needs real context sequences (`context` uris, `next` names, the artist's track window)
  for its tracker tests and its own device checks, and part B - owed since 5b - is the run that records them; step 7 is
  docs and release. The recorder plus one `SourceScanTest` pin is small enough to ride with step 6.

## Checked and fine

- **Which renderer**: `usePhoneRenderer` (`PlaybackService.kt:743-749`) runs in `start` after the old renderer's own
  save and before the queue changes; a room keeps its renderer; the change is `attach` (saved with force, paused under
  `selfPausing`, the fade cancelled, `onEvent` cut) and `local.standDown()` follows for the web player (a paused Exo
  keeps focus). Made once (`spotifyRenderer()`, the only `SpotifyRenderer.create(`), released in `onDestroy` beside
  `local.release()` and before `writer.close()`. A Spotify tap while a room renders: `queueFor(…, SONOS)` drops
  everything (`speakerDropReason` `spotify`) and emits `CANT_PLAY_ON_SONOS`, the room plays on - §4.2/L1's path; the UI's
  `playSwitch(onRoom = true)` shows no switch. Vice versa, a tap on NRK while the web player renders: `queueFor`/
  `activeFor` by `onPhone`, `start` attaches the phone's own player.
- **`playable`/`playableFrom`**: `itemPlayable` asks `!onPhone` first, then Spotify (the gate only, never `GONE`), then
  the old rule - so a Spotify item is never "Ikke lastet ned", whichever phone renderer is current when `start` asks
  (before `usePhoneRenderer`); the mobile-data switch and roaming via `streamAllowed`; `playableIndex` all or none.
- **`goTo`**: refused -> the line, no jump, no `NOT_DOWNLOADED`; while playing on a network the gate now refuses, the
  current track is paused with the line (right: the gate's rule).
- **`onItemChanged`**: Spotify's own advance refused -> `refused`, which pauses our track on the page (`tracker.lost()`)
  and moves nothing; `markDone` of the finished track still runs; `resumesOnAdvance` is `LOCAL` only (QA M4).
- **`onError`**: `recordsItemError` records `unplayable` through `ItemErrors.record` (the listing's version; one row per
  track; dropped with the next listing version; the server accepts `unplayable` as an item error and words it, step
  3); `CANT_PLAY`, never `NOT_DOWNLOADED`; the move-on synchronous (above); no fourth call after the third (5a's
  `moveOnTo` with `stop`).
- **`mediaItem`**: the Spotify branch before Storytel's and the feeds' - `spotifyItemUri`, no `ItemTag`, no cache key;
  only `LocalRenderer` builds `ItemMediaSourceFactory`, `renderer.load(` exists once (in `start`, after
  `usePhoneRenderer`), a room's adopted queue is built for the room, and resumption hands Media3 items only after
  `usePhoneRenderer` attached the phone's own player for a non-Spotify entry.
- **`onPlaybackResumption`**: `!onPhone` keeps 26a's branch; a stored Spotify queue goes through `start` (the gate, the
  renderer's belt, `playAllowed` before) and fails the future. Media3 1.7.1 then calls `handlePlayButtonAction` on the
  session's current player (`MediaSessionImpl$1.onFailure`), which `start` has just made `KidPlayer(spotify.player)`:
  online, `play()` while the start runs is a no-op; offline, `play()` -> `begin()` -> `networkLine()` refuses again
  with the same line and no new word (written only on change); suspended, `KidPlayer.play` refuses. ExoPlayer never
  gets the items. A headset's play with a stored Spotify queue offline: "trenger internett", nothing played, nothing
  persisted.
- **`suspendStop`**: `spotify?.suspended()` first whatever renders (the bookmark with force, the pause, the page
  destroyed), then 26a's path; the attached web player isn't paused twice. Mid-start: green (above).
- **The token's `off` while playing**: the prefetch only notes it; the SDK's ask at expiry fails with `OFF` (paused,
  `none`, the page and its stores cleared, the marker deleted) - green; the next library without the entries then
  `forget()`s once more (harmless).
- **The library collector** (`:291-300`): only a parsed library without a Spotify entry; also with only a marker from
  an earlier run (then the renderer is made just to clear; its throwaway-view clear runs on main); `distinctUntilChanged`
  so it runs at the edge only; an unreadable library keeps everything.
- **`callStep`**: `pausedForCall` = `state?.remote != true` (every Spotify line counts as paused; the Sonos four don't);
  the audio-mode backstop stays Sonos-only (focus covers a system call on the phone).
- **The room looks, `comeHome`, `toRoom`**: by `onPhone`; a recorded room is looked at before a Spotify start; `comeHome`
  passes `phoneRendererFor` (never a Spotify entry from a room); `toRoom` from a Spotify entry keeps the phone playing
  with `CANT_PLAY_ON_SONOS` (`place != null && queue == null`). The `RendererKind.`/`=== local` reads are pinned as an
  exact list.
- **`Store`**: `spotify_state.json` in `noBackupFilesDir`, `{"outcome","at"}` only, tmp + rename, written only when the
  word changes, unknown words never; read lazily off main; the live flow for the tiles; the report reads the file
  through `SpotifyOutcome.reported` (`none` without it or for junk). The page's account marker stays the renderer's
  port's file (`noBackupFilesDir/spotify_page_account`, `<id>:<generation>`); the service only asks whether it exists.
- **The report**: the word is exactly `SpotifyWords.ALL` = the server's `SPOTIFY_WORDS` = step 3's `music_state.json`
  list; `none` by default; written by the renderer at each outcome (`ok` at our first state, each failure's word, `none`
  on `off`) and sent with every sync's report (`Sync.writeReport` -> `BridgeSync.push`).
- **`SPOTIFY_PLAYABLE = true`**: list-mode items visible in both views (a room's queue drops them); news eligible from
  now on (listings acknowledged before the flip stay acknowledged); `downloadPlan` skips Spotify (no `downloads` row, no
  "N av M lastet ned"); the tests keep a `spotifyPlayable = false` snapshot for the old behaviour.
- **The UI**: the loaded tile shows its web player's line (`PlayerHub.State.entryId` + `rendererState`, never a room's -
  `remote`), every Spotify tile the stored account line (`reauthorize`/`refused`/`not_premium`/`no_widevine` ->
  "Spotify - spør en voksen", `quota` -> "Spotify er ikke tilgjengelig nå"; `ok`/`unreachable`/`none` nothing), else the
  hint "Spotify · …"; every line starts with or names "Spotify", so the attribution never leaves the tile. The player:
  the quiet line under the entry name, "Spotify" (11 sp) under the number line - the art keeps `weight(1f)`, so the
  extra line shrinks the picture, not the controls. "Liste": "Spotify" for the summary, no marks. The words are the
  app's own spelling ("trenger Wi-Fi", "Kan ikke spilles", "fortsetter der du slapp"). `countedKeys`/`playerNumber`
  unchanged (`UiModelTest`: "Spor 2 av 3", `newest_first` numbered in play order).
- **Design 28**: the window stamps from `setNowPlaying(playing = renderer.showsPlaying)` - the web player's intent
  during a start, held through a 30 s gap, false on every error line (READY and paused: the paused `setNowPlaying`
  starts the 10 minutes, and Media3's platform state is PAUSED, so no window stays open on a line); the 15 s tick runs
  for it (`IsPlaying` flips arm `saveTick`, which also renews the locks); `onDestroy` reports paused. A failed start
  after its intent shows (a token refused at 10 s) opens a 10-minute window for a play that never sounded - the same
  as a room's failed start, accepted.
- **The debug log**: `spotifyLog` is `Log.d` under `BuildConfig.DEBUG` only, the service's one `Log.` line, passed to
  the renderer, the host and the locks.
- **The fake and §6k part A** (beyond L3): the A1 chain is consistent end to end (the server's bases, the fake's
  accounts and Web API, the launcher's courier, the debug switch's six readers, the stub through the proxy); the
  restarted-fake note is right; the secrets greps cover logcat, the app's data folder (Room, the report,
  `no_backup/`, the WebView's folders) and the server's journal - the stub carries its fake token in the connect query
  only to the fake, through the proxy whose log names the host only (with M2's fix no console line can echo it).

## Fix-round checklist

- [ ] M1: a detached web player owns nothing - `attach` tells it (or it reads `queue() == null`); every later playing
      state paused at once, never focus, locks or `pwr`, until the next `load()`; the counter-example as a test; a
      `GuardsTest` pin.
- [ ] M2: `SpotifyWebHost.Chrome.onConsoleMessage` returns true always (debug: level and source host only); a
      `SourceScanTest` pin; §6k part B's logcat grep for `access_token`/`BQ…` after a Wi-Fi drop; §7.1.
- [ ] L1: nb "Spotify virker ikke på telefonen"; the seven Spotify lines (nb, en) in `FitTest`'s state-line map.
- [ ] L2: with the debug switch, only a `FAKE-ACCESS-` token is used; a pin; §9.3.
- [ ] L3: §6k's eight lines corrected (the consent key, `getOAuthToken ok`, the query, `ExoPlayerImpl` and the stopped
      package, the cooldown force-stop, A2's sync, the service up, `the real hosts`).
- [ ] L4: `forget()` from the "no Spotify entry" path writes `none`; deviation 8 withdrawn in the status.
- [ ] L5: the `PlayBody.kt` pin replaced, the `LibraryViewTest` assert made real, M1's case beside deviation 5's.
- [ ] L6: §4.4, §5.10 (start, onItemChanged, which renderer), §7.1, §9 (headings), §9.3, §9.4 part B (the recorder:
      step 6, as decided above), §11 5b brought in line.
- [ ] The music suite with `--rerun-tasks`; the three selftests under `python3 -I`.
- [ ] Still owed after the fix round (unchanged): §6k part A1 and A2 on the user's emulator (§11's acceptance), part B
      with the user's account, the Jelly Star's item-10 Doze run before 5b's acceptance closes, QA checklist items
      4-17 and 19-21.

No question for the user: each fix keeps a rule the design already states ("nothing starts by itself" and the kid's
controls, §7.1's logs, §9.3's switch, §4.4's `none`).
