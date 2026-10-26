# QA 26c step 5a code review - the music app's WebView host and the Spotify renderer

Scope: branch `music-3-spotify`, `17a2543..afaa351` (one commit, 18 files: `music/.../spotify/{SpotifyWebHost,
SpotifyRenderer,SpotifyLocks}.kt`, `playback/{Renderer,PlayerModel}.kt`, `ui/PlayerScreen.kt`, both manifests,
`src/debug/.../{DebugSpotify,DebugStorytel,DebugFeedReceiver}.kt`, the strings, `SpotifyRendererTest` (new),
`SourceScanTest`, `GuardsTest`, `OutputKeyTest`, the design's status), read in full against root and `music/CLAUDE.md`,
design 26c (§0.4, §1.1, §5.1-§5.10, §6.2, §7.1-§7.2, §9.3, §11 steps 5a and 5b, the design QA fix round and every
status section, "Step 5a implementation status" with its 11 deviations, its spec findings and "Owed by step 5b"),
`qa-26c-step4-code.md`, `qa-26b-step4b-code.md` and `qa-26a-step3a/3b-code.md` (the template and the renderer-axis
rules), `spotify-spike.md` §7b and the spike's `WebPlayer.kt` (branch `spotify-spike`, archived as tag `archive/spotify-spike`), and the step 4 code it builds on
(`SpotifyTracker.kt`, `TokenCache.kt`, `SpotifyApi.kt`, `WebPolicy.kt`, `assets/spotify/player.{html,js}`) beside
`SonosRenderer.kt` and `LocalRenderer.kt`. Line numbers are `afaa351`'s.

Sources read today (2026-10-13), developer.android.com reference: `WebView.loadDataWithBaseURL` ("The base URL is used
both to resolve relative URLs and when applying JavaScript's same origin policy"; the base URL is sent as `Referer`),
`WebViewClient.shouldInterceptRequest` ("invoked for a variety of URL schemes"; **"In the case of redirects, this is
only called for the initial resource URL, not any subsequent redirect URLs"**), `WebViewClient.onRenderProcessGone`,
`ServiceWorkerController.setServiceWorkerClient` ("should be set before any service workers are active, e.g. a safe
place is before any WebView instances are created"), `ServiceWorkerWebSettings` (its own file/content access),
`WebChromeClient.onPermissionRequest`, `WebSettings.setSafeBrowsingEnabled` (the manifest tag has lower precedence),
`setAllowFileAccess` (default false from API 30), `WebStorage.deleteAllData`, `CookieManager.removeAllCookies`
("This method is asynchronous"), and `WifiManager`: **`WIFI_MODE_FULL_HIGH_PERF` "deprecated in API level 34 ... any
request to the WIFI_MODE_FULL_HIGH_PERF will now obtain a WIFI_MODE_FULL_LOW_LATENCY lock instead"; LOW_LATENCY "is
only active when the screen is on" and "only active when the acquiring app is running in the foreground"**.

Runs, here (the worktree, JDK 21, `music/local.properties`):
- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD SUCCESSFUL:
  **723 of 723** JVM tests (29 in `SpotifyRendererTest`), lint 0 errors / 39 warnings, none in a file this step touched
  (all Gradle/dependency-version notes and the activity's locked orientation, as before).
- `python3 -I scripts/fake-spotify.py --selftest`, `fake-storytel.py --selftest`, `fake-sonos.py --selftest`: OK.
- `git diff --stat 17a2543..afaa351 -- server launcher music/gradle music/app/build.gradle.kts music/app/src/test/resources
  music/app/src/main/aidl`: empty (no dependency, no contract file). `PlaybackService.kt`, `PlayerHub.kt`, `data/`,
  `logic/Report.kt`: untouched. `SPOTIFY_PLAYABLE` still `false` (`logic/Spotify.kt:29`).
- The implementer's mutations, re-done (each reverted, `git status` clean): the move-on guard in `play()` disabled
  (`SpotifyRenderer.kt:369`) -> red (`three unplayable in a row stop`); no step coalescing (the step timer at 0 ms,
  `:414`) -> red (`steps within 300 ms coalesce`). Two of my own: states never held during a start's calls (`:861`) ->
  **all 29 green**; the "only `NotOurs` during a start or a step" filter removed (`:951`) -> **all 29 green** (L9).
- Counter-examples as JVM tests against the renderer (a scratch `QaCounterTest` with `SpotifyRendererTest`'s harness,
  not committed; each quoted below): **red** - a paused player started by a shuffle toggle (H1), a pause after a
  superseded step undone (H1), a transient focus loss during a start (M2, two variants), a pick near a track's end
  read as a skip (M3, with and without the held states), a new process with another account (M1, deviation 9);
  **green** - the page gone during a step coalescing, a token expiring inside a 25 s gap with the bridge down (the
  gap still ends it at 30 s), a kid's play after the third unplayable (plays once).

**Verdict: not ready; a fix round first.** Counts: **1 High, 3 Medium, 10 Low.** Most of the step is right and
carefully built: the WebView host is the spike's proven recipe plus every §7.2 lock (the same `loadDataWithBaseURL`
origin as the spike, the interceptor on every frame and on service workers installed before the first WebView, the
bridge exactly `FROM_PAGE` with each input validated and capped, the protected-media grant only, navigation refused,
`Gone` handled, Safe Browsing and metrics off in code and manifest, the debug proxy GET-only and host-only in its log),
the renderer's start machinery (one start, no call after a pause before the calls, a sent play followed by
`vibb.pause()`, a second tap sending nothing, 300 ms coalescing, a superseded result dropped, the token deadline
leaving the call its room, `settings` on a new device and after `ShuffleOrRepeat`), §5.4's table, the locks following
what the player shows, and positions saved only from the tracker's measurement. The High is the renderer turning two
tracker events into a play **whatever the kid's intent**: a paused Spotify starts by itself when the account's other
app switches shuffle or repeat on, or after a pause that lands behind a superseded step. The Mediums: deviation 9
(the first page of a new process runs on the previous account's storage), a transient focus loss during a start
doesn't stop the start from sounding, and a stale state of Spotify's own advance landing in a start is read as skipped
tracks of ours.

## Findings

### H1 (High) - `NewWindow` and `ShuffleOrRepeat` start a play while the player is paused: "nothing starts by itself" breaks

- **Where:** `M/spotify/SpotifyRenderer.kt:983-988` (`Event.NewWindow` -> `request(...)` gated by `ports.mayPlay()`
  only), `:990-994` (`Event.ShuffleOrRepeat` -> `request(..., settings = true)`, the same), `:714-715` and `:737`
  (`startDone`: a superseded start's sent play is neither paused nor read when the kid paused it, `s.cancelled`
  ignored); the tracker emits both events whatever the play intent (`SpotifyTracker.kt:255-258` for any state with
  shuffle/repeat on, paused included; `:340-352` `notOurs(goOn = true)`).
- **Failure path 1 (no timing window):** the kid pauses; within the page's 5 idle minutes anyone using the account's
  Spotify app (the kid's tablet, the parent) toggles shuffle or repeat. Vibb is the account's active device, so the
  page posts a paused state with `shuffle: true`; the tracker emits `ShuffleOrRepeat(0)`; `apply` turns shuffle and
  repeat off and **plays** from the measured place - the phone in the kid's pocket starts sounding, focus taken, wake
  lock held. Test (red: one new `PUT`, `showsPlaying` true):
  ```kotlin
  val r = playing(); post(state(0, 20_000)); r.pause(); post(state(0, 21_000, paused = true))
  advance(60_000); post(state(0, 21_000, paused = true, shuffle = true)); advance(2_000)
  assertFalse(r.showsPlaying)   // fails
  ```
- **Failure path 2 (a narrow window, mashing):** playing item 0; a step to 3 (its call in flight), a pick of 1 (the
  first start is superseded, `queued` = 1), then the kid pauses (`cancelled`, `queued` dropped, `vibb.pause()` for
  item 0). The first call's 204: `startDone` takes the `superseded` branch and does nothing, so Spotify plays item 3
  with the tracker still in the old play (`OURS`, index 0). Item 3's state is "a later uri of ours without evidence"
  -> `NotOurs` + `NewWindow(0, resume = true)` -> a third `PUT` from item 0 at its old place: the kid's pause is undone
  and the pick lost. Test (red: events end `ItemChanged(0), Resumed, IsPlaying(true)`, three plays).
- **Fix:** a play the renderer starts on its own is a continuation of an intent, never a new one: in `apply`,
  `NewWindow` and `ShuffleOrRepeat` call `request` only while `pwr` (and not `suppressed`, M2); while paused they only
  move the index/place and set `needSettings = true` (the next play turns them off). In `startDone`, a superseded
  start whose result was `Sent` and whose successor was dropped (no `queued`) is followed by `vibb.pause()` and fed to
  the tracker as `played(...)` + `pauseSent()` like a cancelled one (or `tracker.lost()`), so its sound is stopped and
  its states aren't read against the old play. Both tests above as `SpotifyRendererTest` cases; a `GuardsTest`-style
  scan that every `request(` in `apply` sits under the `pwr` test.

### M1 (Medium) - deviation 9: the first page of a new process runs on the previous account's cookies and storage

- **Where:** `SpotifyRenderer.kt:228` (`hostAccount`, memory only), `:662-676` (the page is opened by `awaitReady` in
  parallel with the token, and compared only when `hostAccount != null`), `:914-917` (`accountChanged` false while a
  start runs, so the SDK's own `needToken` during a start gets whatever the cache holds), `:901-907`.
- **Why it matters:** §5.2's rule ("when the token's `account` changes ... the old account's state must not linger",
  §7.2 "cleared on an account change") is met only within one process. The usual way the account changes is the
  parent rebinding the phone (or re-logging the kid in, a new generation) in the PWA - when the music app's process is
  most often not running. Then the next cold start opens the page on the old account's on-disk `sdk.scdn.co`
  storage and `spotify.com` cookies (the SDK keeps its device state there; Spotify's own cookies are opaque to us), and
  the new account's token is handed to that page. Not a token leak in our code (tokens are memory only), but exactly
  the cross-account linger §5.2 forbids, between two family members' accounts. Test (red: `destroys == []`):
  a fresh renderer, `account = "3:1"`, a cold start - expected a `destroy(clear = true)` before the first page.
- **Fix:** persist the page's account marker (the token's `"<id>:<generation>"`, already printed by
  `TokenResult.Ok.toString`, not a secret) through a port to `noBackupFilesDir` (tmp + rename), written when a page is
  opened under an account, deleted by `forget()`/`OFF`. When the in-memory `hostAccount` is null (a process's first
  start), the start awaits the token **before** `host.open()`, compares it with the stored marker, and clears first when
  it differs or no marker exists (an upgrade, a crash before the write); afterwards the parallel path as now. In
  `answerToken`, a token whose account differs from `hostAccount` is never handed to the page, start or not (answer
  `null`; the start's own check clears). `SpotifyRendererTest`: a new renderer with a stored `2:1` and a `3:1` token
  clears before `opens == 1`; the same account doesn't clear; no marker clears once.

### M2 (Medium) - a transient focus loss during a start doesn't stop the start: Spotify sounds through the interruption

- **Where:** `SpotifyRenderer.kt:1100-1109` (`LOSS_TRANSIENT` pauses only when the tracker is `STARTING`/`OURS`; during
  a start's page/token/calls phase it is neither, and the start isn't touched), `:716-726` (`startDone`'s `Sent` branch
  ignores `suppressed`), `:1119-1129` (the gain resumes only an `OURS` track), `:1156-1166` (the first-state timer then
  fails a suppressed start as "trenger internett").
- **Failure path:** a cold start (page + SDK + token + shuffle/repeat/play, seconds) and the phone rings, a VoIP call
  starts, or a navigation prompt speaks: `suppressed = true`, play-when-ready held, nothing sent to the page; the
  play call's 204 arrives and Spotify plays our track at full volume over the call. Focus is the only guard on the
  phone for a system call without the launcher's listener access (§5.6: "the audio-mode backstop stays Sonos-only
  (focus covers a system call here)"). Two tests red: the loss while the play call is in flight, and while the page
  loads (`calls=[]`, one `PUT`, nothing paused).
- **Fix:** a transient loss during a start marks it (like `cancelled`, but play-when-ready kept): after a `Sent` result
  the renderer sends `vibb.pause()` + `pauseSent()`; the first-state timer doesn't fail a suppressed start; the gain
  resumes a `STARTING` or `OURS` track (`vibb.resume()`), or makes a fresh start at the place when the start was
  dropped. Also H1's rule: no self-started play while `suppressed`. The two counter-examples as `SpotifyRendererTest`
  cases.

### M3 (Medium) - Spotify's own advance landing in a start is read as skipped tracks of ours

- **Where:** `M/spotify/SpotifyTracker.kt:280-304` (`onStarting`: a later window uri, playing, with every item between
  named in `prev`, is "Spotify skipped them"), `:307-311` (`stale`: only `prePlayUri`, the one uri of the last state
  before the call, `:193`); in the renderer the held states are replayed after `played()` (`SpotifyRenderer.kt:712-737`),
  which widens the window from the 204's arrival to the whole call.
- **Failure path:** the kid has listened to items 0-3 in a row; in the last second of item 3 they pick item 1 from the
  list (or press previous twice). While the play call is on its way Spotify's old play advances to item 4 and posts
  `state(4, prev = [1, 2, 3])`. After `played(1, ..)` that state is not stale (`prePlayUri` is item 3), it names a
  later uri of the new window, and `prev` "proves" 1, 2 and 3 skipped: `Unplayable(1)` (the kid's pick gets
  `item_errors` `unplayable`, the parent's card and "Kan ikke spilles"), the move-on sends another `PUT` from item 2, and
  the tracker's run counter sits at three (the next genuine unplayable stops at once). Red with the states held and
  without (the state posted just after the 204): events `..., ItemChanged(1), Error(unplayable), ItemChanged(2)`,
  three plays. Only one error reaches the service because `apply` drops the rest during the move-on's start - luck,
  not design.
- **Fix (tracker, step 4's rule extended):** at `played()` also remember the old play's expected next uri (and a
  pending add of ours) and pass over states naming it in `STARTING` like `prePlayUri` - Spotify's own advance can only
  go there - unless it is the sent track (the existing near-the-sent-position exception). `SpotifyTrackerTest` with the
  sequence above plus an `sdk_states/` file; the renderer test above. §5.4's start rule says so.

### L1 (Low) - the Wi-Fi lock does nothing with the screen off on the app's minSdk; §5.7 and §14 item 6 rest on it

- **Where:** `M/spotify/SpotifyLocks.kt:27` (`WIFI_MODE_FULL_LOW_LATENCY`); design §5.7 ("the Wi-Fi lock stays: Wi-Fi
  power save is what stalls a stream"), §14 item 6, QA checklist item 6. The same holds for `SonosLocks`.
- **Why:** per `WifiManager` (above), on API 34+ every Wi-Fi lock is a LOW_LATENCY lock, active only with the screen on
  and the app in the foreground. Deviation 3's choice is right (HIGH_PERF is the same lock now), but the lock can't keep
  Wi-Fi out of power save in the case it exists for. Harmless to keep.
- **Fix:** §5.7/§14 say the Wi-Fi lock is inactive with the screen off and that only the Jelly Star run shows whether
  Wi-Fi power save stalls the stream; QA checklist item 6 looks for audio gaps/state gaps rather than assuming the lock;
  `SpotifyLocks`' comment says it.

### L2 (Low) - a redirect from a Spotify host isn't seen by the interceptor; §5.2/§7.2 promise more than the WebView gives

- **Where:** `SpotifyWebHost.kt:280-289` (`Pass` returns `null`); §5.2 ("everything else gets an empty 403"), §7.2
  ("Loads only our page and Spotify's hosts").
- **Why:** `shouldInterceptRequest` "is only called for the initial resource URL, not any subsequent redirect URLs". A
  Spotify host answering 3xx to a third-party CDN (or Google for Widevine) is loaded unseen and never counted in
  `blocked`. Spotify's own code chooses those; the DNS filter still bounds them, as for WebSockets.
- **Fix:** spec text: redirects beside WebSockets ("bounded by the DNS filter only"), and §9.4 part B / QA checklist
  item 15 read the parent's DNS log for such hosts. No code change.

### L3 (Low) - `Reauthorize` at the SDK's own token ask shows "trenger internett" and stores `unreachable`

- **Where:** `SpotifyRenderer.kt:901-907` (`answerToken`: only `Off` acts; `Reauthorize`/`Busy`/`Unreachable` answer
  `null`), `logic/Spotify.kt:178-183` (`authentication` -> no problem), `:1224-1227` (the prefetch's result).
- **Failure path:** a player running when the server's refresh token ends (`invalid_grant`): the prefetch and the
  SDK's ask get `Reauthorize`, the SDK reports `authentication_error` and stops; 30 s later the gap rule shows "Spotify -
  trenger internett" and stores `unreachable`. §5.8's row says `SPOTIFY_ASK_ADULT` and `reauthorize` - and §5.5 says the
  renderer "reads [the authentication error] through the token result it already has", which it doesn't.
- **Fix:** keep the last token result; on `authentication` (or when the SDK's ask can only be answered `null` and the
  kept token has expired) `fail(result.problem())` - `REAUTHORIZE` for `Reauthorize`, `UNREACHABLE` otherwise. A test.

### L4 (Low) - an SDK problem during a start's calls is overwritten by the call's outcome

- **Where:** `SpotifyRenderer.kt:710-730` (`problem` is read only in the `Sent` branch).
- **Failure path:** the renderer process dies (`Gone`, `:873-875`) or the SDK says `initialization`/`account` while the
  play call runs: the call fails against the dead device (404, transfer, 3 s) and `startDone` shows the call's
  "trenger internett" instead of "Spotify stoppet - trykk spill" / "virker ikke på denne telefonen", and `stopped`
  (IDLE) isn't set.
- **Fix:** `problem` (the SDK's) wins over a failed call's problem in every branch but `superseded`/`cancelled`. A test
  with `Gone` while the gated call runs.

### L5 (Low) - `destroy()` sends `vibb.disconnect()` and kills the page in the same turn: the device stays in the account's Connect list

- **Where:** `SpotifyWebHost.kt:143-161`.
- **Why:** `evaluateJavascript` is asynchronous and the SDK's `disconnect()` has to send a message to Spotify; the view is
  destroyed before either can run, so "Vibb" lingers in the account's device list until Spotify times it out - against
  §5.2's "the device then leaves the account's Connect list" (cosmetic for the kid, visible to the parent's Spotify app;
  a transfer to the dead device can't sound, so §5.7's suspension rule still holds).
- **Fix:** take the view out at once (`view = null`, the generation bumped - late calls are already dropped), and
  `destroy()` it from `evaluateJavascript`'s callback or after a short timeout (~1 s), whichever first. Or say in §5.2 that
  the device leaves when Spotify notices.

### L6 (Low) - clearing: the asynchronous clears race the next page; the HTTP cache isn't cleared without a view; the service workers' own settings

- **Where:** `SpotifyWebHost.kt:149-155`; `SpotifyRenderer.kt:669-674` (a new page opened right after a clear).
- **Why:** `removeAllCookies` "is asynchronous" and `WebStorage.deleteAllData` is too; at an account change at a start the
  new page is created in the same turn, so it can read state the clear hasn't removed yet. With no page alive (`forget()`
  after an idle destroy, M1's first-start clear) `clearCache(true)` is skipped, so the SDK's HTTP cache of the old
  account survives (§7.3 "cleared on an account change"). `ServiceWorkerWebSettings` has its own file/content access
  (content access on by default); `WebPolicy` blocks those schemes in the service-worker client anyway.
- **Fix:** open the next page from `removeAllCookies`' callback (a `clear` that completes a deferred the start awaits);
  clear the HTTP cache without a live page through a throwaway `WebView(app).clearCache(true)` + `destroy()` (or say
  in §5.2 that only a live page's cache is cleared); set `ServiceWorkerController.getInstance().serviceWorkerWebSettings`
  `allowFileAccess`/`allowContentAccess` false beside the client (pinned by `SourceScanTest`).

### L7 (Low) - `SourceScanTest`: `SpotifyWebHost.kt` is exempt from the whole escape scan, and Storytel's assignment scan got narrower

- **Where:** `SourceScanTest.kt:79-82` (`if (f.name == "SpotifyWebHost.kt") continue` skips every banned token -
  `ACTION_VIEW`, `Settings.ACTION_`, `createChooser`, `Uri.parse("http` ... - where only `WebView` needed exempting);
  `:346` (`debugBase\s*=` became `StorytelEndpoints\.debugBase\s*=`, so an unqualified assignment through an import of
  the member is no longer seen).
- **Fix:** scan `SpotifyWebHost.kt` with the banned list minus `"WebView"`; for the debug bases, require every
  `debugBase\s*=[^=]` in `src/debug` to be one of the two qualified forms, each in its own file.

### L8 (Low) - the SDK's error message reaches the log port unconditionally

- **Where:** `SpotifyRenderer.kt:885` (`"spotify: the web player said $kind: $message"`); §7.1 ("never ... the SDK's
  error message in a release").
- **Why:** the release rule rests entirely on 5b passing a debug-only `log`; nothing in the app has a debug/release
  switch for logs today (no `BuildConfig.DEBUG` anywhere in `main/`). The message is Spotify's text (and, for `csp`,
  our directive and an origin), not a token - but it is the one line in `spotify/` that carries foreign text.
- **Fix:** `if (BuildConfig.DEBUG)` around the message part in the renderer (it already imports `BuildConfig`), and a
  `SourceScanTest` pin; or 5b's status names the logger as debug-only with a test.

### L9 (Low) - tests: deviation 7 is unpinned, and the scripted page's cold start skips the SDK's token ask

- **Where:** `SpotifyRendererTest.kt:138-162` (`Page.open` emits `ready` at once; `player.js` and the stub do
  `connect` -> `getOAuthToken` -> `needToken` -> `vibb.token` -> `ready`), and no case holds a state during a call.
- **Why:** both deviation 7 mechanisms (holding states through the calls, `:861`; dropping the old play's events but
  `NotOurs`, `:951`) can be removed with all 29 green. The realistic cold start - the page's `needToken` racing the
  start's own token ask and the account check (M1) - is never run.
- **Fix:** the scripted `Page` emits `NeedToken(n)` at `open()` and `Ready` only after a non-null `vibb.token(n, ..)`
  (as the stub does); cases for our first state arriving before the 204 (held, read after `played()`: `Started`, not
  `NotOurs`) and for an old-play event during a step (ignored). M1-M3 and H1's counter-examples join the suite.

### L10 (Low) - the spec body lags the step's deviations

- **Where:** design 26c §5.4 (the states held through a start's calls, IDLE only for the page-destroying errors, the
  renderer's own gate), §5.7 (`FULL_HIGH_PERF`, L1), §5.8 (`SPOTIFY_UNAVAILABLE` as its own state), §7.1 (no `Log.` in
  `spotify/`, the `log` port), §5.10 (the `onError` move-on read by the renderer, deviation 6) - only the status section
  changed (`git diff` of the design: one hunk, at the end).
- **Fix:** bring §5.4/§5.7/§5.8/§7.1/§5.10 in line with the accepted deviations and with H1, M1-M3, as step 4's fix
  round did (its L8).

## The implementer's deviations (11, all judged)

1. **`SPOTIFY_UNAVAILABLE`** (the quota's line its own value, not `remote`): accept. An enum value carries one string;
   `callStep` reads it as paused like the other Spotify values; `rendererStateOf` is exhaustive.
2. **`PlayerScreen.kt` touched** (seven `when` lines): accept - required to compile, nothing else in the UI moved;
   `SourceScanTest` pins one line per value.
3. **`WIFI_MODE_FULL_LOW_LATENCY`, a timer for its timeout**: accept (HIGH_PERF is the same lock on API 34+), with L1:
   the lock is inactive with the screen off, so the spec's reason for it doesn't hold.
4. **No `Log.` in `spotify/`, a `log` port**: accept - the stricter rule; with L8 for the one foreign-text line.
5. **IDLE only for no Widevine and the renderer process gone; every other error paused READY**: accept. A paused READY
   with play-when-ready false is exactly a kid's pause to Media3 (no foreground hold: Media3's ongoing test needs
   play-when-ready), and to design 28 `showsPlaying` false is a pause (the listening window's 10 minutes after it, as
   §5.4's table already says for "no state for 30 s"). IDLE for the two that destroy the page is right: there is
   nothing to resume, and `COMMAND_PREPARE` isn't offered, so Media3's play button goes to `play()`, which starts afresh.
6. **The unplayable move-on rides on the service's `onError`**: accept. `flush()` sets `moveOn` only around the one
   `onEvent(Error)` call, and `PlaybackService.onError` (`:1147-1178`) calls `saveNow`, `seekTo`, `prepare`, `play`
   synchronously inside it on the main thread, so a kid's tap (a session command, a later main-thread message) can't
   fall inside the window and is neither swallowed nor doubled (my test: a tap after the third stop plays once). The
   condition for 5b: `onError` must stay synchronous for a Spotify item - a `post`/`launch` around the move-on would
   turn it into an unguarded play (a 5b `GuardsTest` pin).
7. **States held during a start's calls; the old play's events ignored but `NotOurs`**: accept the mechanism (our new
   play's first state can beat the 204; in `STARTING` a foreign state is ignored by the tracker, so a foreign track's
   pause isn't lost - the new play replaces it - and our own first state is never dropped: `HELD_MAX` 32, replayed after
   `played()`, dropped only when the start was superseded), but it is untested (L9) and it widens M3's window.
8. **The renderer gates too** (`playAllowed`, the network line, Spotify's own resume of our track): accept - the same
   inputs as 5b's gate (`spotifyNetworkLine(netCaps, store.mobileData)`, `isPackageSuspended`), read moments apart, so
   they can only disagree across a network change, and then the renderer's answer is the later and more correct one.
   Resuming our paused track from the account's other app is shown as playing only with `playAllowed` and the focus;
   that is the account's own remote control, not "by itself" - unlike H1's renderer-made plays.
9. **The page's account in memory only**: **reject** - M1.
10. **`reorder()` a logged no-op**: accept (no shuffle for Spotify; the service calls `reorder` only from the shuffle
    toggle, which `canShuffle` refuses for a resume-on entry).
11. **The focus branch left to the device check**: accept; the focus changes are logged for the Jelly Star (§5.6).

## The spec findings (6)

All right: §5.7's HIGH_PERF (and further: L1), §5.8's shared carrier, §7.1's "debug lines in `spotify/`" vs the scan,
§5.10's `onError` move-on double-sending (the renderer's reading is the safe one), §5.4's silence on states between the
call and its 204, and `OutputKeyTest`/the Storytel scan needing adjustment (L7 for the scan's new blind spot). Nothing
in 5a needed `androidx.webkit`. Added here: the interceptor never sees redirects (L2); `Reauthorize` while playing has
no path to its line (L3).

## Checked and fine

- **The origin**: `loadDataWithBaseURL("https://appassets.androidplatform.net/spotify/", ...)` gives the page the
  origin `https://appassets.androidplatform.net` ("the base URL is used ... when applying JavaScript's same origin
  policy") - the spike's exact origin (its `WebPlayer.kt` used the same call with `/` as the path and no asset
  loader), a secure context for EME. `player.js` resolves to `/spotify/player.js` on that origin and `WebPolicy`'s
  `ORIGIN_HOST` branch serves it (`Asset`), anything else there 404; the CSP's `script-src https://appassets...` is
  that origin. The page's `Referer` to `sdk.scdn.co` is that base URL - no secret.
- **Interception**: `shouldInterceptRequest` on every frame (`Client`, `:202-204`) and `ServiceWorkerController`'s client
  (`:271-277`), installed in `open()` before `WebView(app)` - the documented safe point; one process-wide client, the
  app has no other WebView. `Block` is an empty 403 with the host counted; `NotFound` 404; `Pass` null.
- **Settings** (`:116-131`): JavaScript, DOM storage, media without a gesture, file and content access off,
  geolocation and multiple windows off, Safe Browsing off in code (which outranks the manifest tag) and manifest,
  metrics opt-out, `RENDERER_PRIORITY_IMPORTANT`, third-party cookies (the SDK's iframe), default cache and UA, mixed
  content left at `NEVER_ALLOW` (`SourceScanTest` bans `mixedContentMode`). Never added to a view (`addView` banned
  everywhere).
- **The bridge** (`:170-200`): exactly `ready`/`notReady` (`[A-Za-z0-9]{1,64}`), `needToken` (1..10^9), `state` (8 KB in
  UTF-16 characters, as `SdkState.MAX_BYTES`, parsed off main and posted), `error` (an `ERROR_KINDS` word, the message
  cut to 200); every post is dropped when its generation is gone; nothing privileged. `SourceScanTest` pins the method
  list to `SpotifyPage.FROM_PAGE` and the only `@JavascriptInterface`s. Every page call is `SpotifyPage.call` (quoted
  JSON) through `evaluateJavascript` on main.
- **Navigation and windows**: `shouldOverrideUrlLoading` true; `setSupportMultipleWindows(false)` with no
  `onCreateWindow` override means `window.open` creates nothing (the default `onCreateWindow` returns false).
- **The permission** (`:231-239`): only `RESOURCE_PROTECTED_MEDIA_ID`, only for `https` `sdk.scdn.co` or our origin, the
  asking host logged for 5b's device check; everything else denied. No other resource is ever requested by our page;
  if Spotify's iframe asked for the microphone or camera it is denied.
- **`onRenderProcessGone`**: the view nulled, destroyed, `Gone` posted, `true` returned; the renderer pauses with
  `SPOTIFY_STOPPED`, IDLE, a new page at the next play (test, and my step-time variant).
- **The debug proxy** (`:322-349`): reachable only through `WebDecision.Proxy` (the switch); GET only (405), `Range`
  forwarded; status, `Content-Type` + charset, `Content-Range`, `Accept-Ranges`, `Access-Control-Allow-Origin` back; a
  3xx or a transport failure 502 (`WebResourceResponse` refuses 3xx); the LAN client with redirects off; the host only
  in the log. `DebugSpotify.kt` is the only writer, from the receiver and `DebugStartup` (pinned exactly).
- **Logs**: no `Log.` in `spotify/`; every `log` line is fixed words, a host, a status, an enum name or an exception
  class - no URL, token, uri list or state (L8 for the SDK message).
- **The renderer against §5.4** (with H1/M2/M3 as the exceptions): lazy `SimpleBasePlayer` on the main looper, its State
  from `spec()`; handlers only set intent and post a flush; BUFFERING + play-when-ready + `showsPlaying` through a start
  (the 25 s budget, 20 s for `ready`, 10 s for our first state) and while a step coalesces; READY held through a 29 s
  gap, paused with `unreachable` past 30 s; a second tap during a start sends nothing; a pause before the calls cancels
  the job, during them follows the 204 with `vibb.pause()`; a new queue supersedes, its play goes after the old call;
  steps coalesce in 300 ms, a paused step only moves; the window's end adds one ahead and `queued` on the 204;
  `settings` on a new device id (`newDevice()`) and after `ShuffleOrRepeat`, decided when the calls begin;
  `played(.., positionMs, fresh)`; `TokenRefused` -> `SPOTIFY_ASK_ADULT`/`refused`; three unplayable -> paused, the
  third's move-on only moves; `Ended` -> ENDED with no pause event, Autoplay after it paused and never saved;
  `Elsewhere` -> the bookmark, `SPOTIFY_ELSEWHERE`, never a pause of the other device; the 5-minute idle page;
  `suspended()` (bookmark with force, pause, page destroyed) and `forget()`; `release()` lets everything go.
- **Positions**: `saveNow` writes only `tracker.measured` of the current index (never before our first state, never a
  foreign or stale state, never the seek target or the load position), the SDK's duration else the listing's, through
  `persistPlace` (`GuardsTest` adds the renderer to the callers; `writePlace` callers unchanged). `positionMs`
  extrapolates for display only, capped at the 30 s hold.
- **Coroutine hygiene**: one `CoroutineScope(SupervisorJob() + Dispatchers.Main)`; every Web API call inside
  `runInterruptible(Dispatchers.IO)` (step 4's `SpotifyApi`, `queueAdd`), the bridge ask on IO; nothing blocks main
  but `persistPlace(force)`'s `runBlocking(writer)` - the existing pattern of both other renderers. A start's job is
  cancelled only from main and only before its calls (`calling` is never cancelled), never from inside itself;
  `startDone` runs in the job after the calls, with no suspension between the call's return and it. The WebView is
  touched only on main (host events are posted; the interceptor and the bridge don't touch the view).
- **Focus, noisy, the fade, calls**: `AUDIOFOCUS_GAIN` with the right content type, `setWillPauseWhenDucked(true)`
  and the SDK's volume for a duck; a loss pauses (`FAULT`), a transient loss suppresses and the gain resumes (outside
  a start - M2); `ACTION_AUDIO_BECOMING_NOISY` registered `RECEIVER_NOT_EXPORTED` (system broadcasts still arrive, as
  Media3's own receiver does) only while playing; the 10 s fade in 250 ms steps, pause, volume back; `CALL_HOLD`/
  `RELEASE` reach `pause()`/`play()` (5b).
- **`SpotifyLocks`**: PARTIAL wake lock (never a screen one), non-reference-counted, `acquire(30 min)` renewed by every
  `saveNow` while held (a non-counted lock's re-acquire restarts its releaser), the Wi-Fi lock's 30 minutes a main
  timer; held exactly while `pwr && !ended` (a start included), released on every pause, end, error, `Elsewhere` and
  `release()`; no exception path leaves `locksHeld` and the lock apart (`hold` catches inside, `syncHolds` is the one
  caller). On Doze: with the session's `mediaPlayback` FGS the app's partial wake lock should not be disabled in idle
  (the platform disables idle-mode wake locks only for processes below the foreground-service state - from the
  platform source as I know it, not re-read today), and while audio plays the audio server keeps the CPU up anyway -
  the spike's forced-Doze pass without a lock is consistent with that; the lock covers the gaps (a start, a track
  change, a stall). Fine as a belt; the Jelly Star run (§14 item 6) decides.
- **The axis types**: `RendererKind(onPhone)` (`LOCAL`, `SPOTIFY` true), `RendererState(remote)` (the Sonos four
  only), the seven Spotify lines in nb as the app spells them ("Spotify - trenger Wi-Fi" ... "Spotify stoppet - trykk
  spill"; `SourceScanTest` pins each) and English; the `Typos` suppression is on that one string with the reason
  ("internett" is bokmål's lower-case noun). `OutputKeyTest` still pins the full list (its point: a new value needs its
  line) and adds `remote`/`onPhone`. The manifest's two meta-data names and values are right.

## Owed by step 5b (not findings; from this review)

- `PlaybackService.onError` stays synchronous for a Spotify item (deviation 6), pinned.
- The `log` passed to `SpotifyRenderer.create` is debug-only (or L8's code fix), named in the status.
- `persistPlace(force)` from `suspended()` runs on main as the other renderers' do; `suspended()` before the launcher's
  suspension lands, as §5.7 says.
- §6j part A: the permission line's origin, the proxy's `Range` seek, the blocked-hosts counter, whether a redirect
  ever shows in the DNS log (L2), and a recorded cold start's `needToken` -> `ready` order (L9).

## Fix-round checklist

- [ ] H1: `NewWindow`/`ShuffleOrRepeat` play only while `pwr` (and not suppressed); paused they move and set
      `needSettings`; a superseded `Sent` start with no successor is paused and fed to the tracker; the two
      counter-examples as tests.
- [ ] M1: the page's account marker persisted; a process's first start takes the token before the page and clears on
      a differing or missing marker; no token of another account handed to a page during a start; tests.
- [ ] M2: a transient focus loss during a start: the sent play paused, no first-state failure while suppressed, the
      gain resumes a `STARTING`/`OURS` track or starts afresh; the two counter-examples.
- [ ] M3: the old play's expected next (and pending add) is stale at a start; `SpotifyTrackerTest` + `sdk_states/` and
      the renderer test; §5.4.
- [ ] L1: §5.7/§14/QA item 6 on the Wi-Fi lock's screen-off inactivity; `SpotifyLocks`' comment.
- [ ] L2: §5.2/§7.2: redirects are bounded by the DNS filter only.
- [ ] L3: `Reauthorize` (and the kept token's expiry) at the SDK's ask -> `SPOTIFY_ASK_ADULT`/`reauthorize`; a test.
- [ ] L4: the SDK's problem wins over a failed call's; a test with `Gone` during the call.
- [ ] L5: `disconnect()` given its moment before `destroy()` (or §5.2 says otherwise).
- [ ] L6: the next page after the async clears; the HTTP cache cleared without a page (or §5.2 says not); the service
      workers' file/content access off, pinned.
- [ ] L7: `SpotifyWebHost.kt` scanned for every banned token but `WebView`; the debug-base assignment scans exact.
- [ ] L8: the SDK's message only in a debug build (code or 5b's pinned logger).
- [ ] L9: the scripted page's `needToken` -> `token` -> `ready`; deviation 7's two cases.
- [ ] L10: §5.4, §5.7, §5.8, §5.10, §7.1 brought in line.
- [ ] The music suite with `--rerun-tasks`, the three selftests.

No question for the user: each fix keeps a rule the design already states ("nothing starts by itself", §5.2's
clearing, §5.6's focus, §5.4's skip evidence).
