# 120 - Spotify recovers by itself after a network change

Status: revision 2, 2026-11-20 - option B, R2 and a new trigger R5 built on `spotify-output-recover` (§10). Revision 1,
2026-11-19, was design only.

Inputs: the user's emulator logs of 2026-11-19 (music-v0.7.0-rc.3, Android 16 AVD, the Vibb launcher as Device Owner
with its VpnService DNS filter); design 26c (the web player), 95 and 97 (the launcher's VPN shapes); the music notes
`docs/notes/music/spotify.md`. Read against `design-120` at 38ceac3ea.

Paths: `M/` = `music/app/src/main/java/me/vibb/music/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`.
AOSP = `android.googlesource.com` main, Chromium = `chromium.googlesource.com/chromium/src` main, both read on
2026-11-19.

Folded in and already built: the start at a track's end (bug b), the double start (bug a) and a context step that
was dropped while a start waited are commit 38ceac3ea (music rc.4). They are left out of this note.

## 1. The evidence

From the user's emulator. The Spotify path is a WebView page that runs Spotify's Web Playback SDK, plus Web API calls
the app makes itself over OkHttp.

1. The kid played, then paused. The parent switched the phone's mobile data choice to "Bare Vibb" and back. During the
   switch, one Web API call failed with `UnknownHostException`, which is expected: the sink answers DNS with
   `REFUSED`.
2. After that, every start went `PUT shuffle 200`, `repeat 200`, `play 204`, then "no state of ours after the play call
   (10000 ms)" and UNREACHABLE. The SDK sent no state and no `not_ready`.
3. After 5 minutes idle the page was destroyed, and a new one was made in the same process. The page loaded, and
   `sdk.scdn.co` asked for protected media, which was granted. A `MediaDrmBridge` was created. Then the page never
   asked for its token, and after 20 s the log said "the web player wasn't ready in time". In a healthy start the
   token request comes about 100 ms after the grant, and `ready` comes about 300 ms after that.
4. `am force-stop me.vibb.music` (a new process) fixed it at once. Swiping the app from recents doesn't, because the
   media service keeps the process alive.
5. Earlier the same day, in an older process after LTE mode changes, `PUT shuffle` answered `502 "Bad gateway."` on a
   page that had been up for a while. Most likely this was the same dead player, with Spotify's backend unable to reach
   it.

So the state that breaks is the process's. The app's own HTTP kept working.

## 2. What the sources say

### 2.1 The launcher's switch: two network changes an app sees, and one it doesn't

`KidVpnService.establish` (`L/server/KidVpnService.kt`) changes shape by calling `Builder.establish()` again on the
same service. In every shape the only disallowed app is the launcher. The music app is inside the VPN in every shape:
FILTER routes only the fake DNS server, while SINK routes `0.0.0.0/0` and `::/0` and answers DNS `REFUSED`, TCP with
RST and drops the rest (`SinkPackets.kt`; design 97 D4: the music app is not excluded).

- AOSP `services/core/java/com/android/server/connectivity/Vpn.java`, `establish()` then
  `updateLinkPropertiesInPlaceIfPossible()`: when `allowBypass` and the allowed and disallowed app lists are unchanged,
  the new tun is handed over in place (`agent.sendLinkProperties`). It is the same `Network` with new LinkProperties
  (interface, routes, addresses). The launcher's own comment says the same thing: "Android keeps the VPN's network, so
  apps never see it go".
- The sink sets lockdown and every other shape clears it (`MobileNetwork.kt`, `alwaysOnFor`). AOSP `Vpn.java`,
  `setAlwaysOnPackageInternal()`: "changes in lockdown need to restart the agent". It calls `startNewNetworkAgent(...,
  "Lockdown mode changed")`, which gives a new VPN network with a new netId. ConnectivityService's
  `updateVpnUids()` -> `maybeCloseSockets()` then destroys the covered UIDs' live TCP sockets
  (`destroyLiveTcpSockets`, Android 14 and later).
- The order (`switchTo`, `alwaysOnBeforeEstablish`/`alwaysOnAfterEstablish`):
  - **into the sink**, the tunnel is handed over in place first, then lockdown comes on (a new network);
  - **out of it**, lockdown goes off first (a new network that still has the sink's routes), then the filter's tunnel
    is handed over in place.

  So after "Bare Vibb and back", the last change an app could notice was a new network that dropped everything. The
  move back to the filter's routes kept the same network and gave no notice.

### 2.2 WebView keeps its network stack per process and doesn't see an in-place change

- `android_webview/lib/aw_main_delegate.cc`: `content::ForceInProcessNetworkService()`. WebView's network service
  (socket pools, the host cache, HTTP/2 and QUIC sessions, WebSockets) runs inside the app's own process, and it
  outlives every page.
- `android_webview/.../AwNetworkChangeNotifierRegistrationPolicy.java`: WebView listens for network changes only while
  some WebView exists. `net/android/.../NetworkChangeNotifierAutoDetect.java`, `AndroidRDefaultNetworkCallback` (used
  from Android 11 on) and `connectionTypeChangedTo()`: observers hear only about a change of connection type, netId or
  private DNS. A VPN whose LinkProperties change in place is the same type and the same netId, so Chromium hears
  nothing.
- `AwContents.setNetworkAvailable()` (`WebView.setNetworkAvailable`) only sets `navigator.onLine`, and it turns
  WebView's own detection **off** (`setAutoDetectConnectivityState(false)`). It is no fix, and it must never be called.

### 2.3 Protected media has state that one stuck request holds for the whole process

`media/base/android/java/src/org/chromium/media/MediaDrmBridge.java`: `sMediaCryptoDeferrer` is **static**. While one
bridge provisions, every other bridge's `createMediaCrypto()` is deferred until that provisioning ends.
`content/browser/media/url_provision_fetcher.cc` fetches the provisioning response through the in-process network
service with a `SimpleURLLoader` and sets no timeout. If that request hangs, every later `MediaKeys` in the process
waits for it.

### 2.4 The music app

Nothing binds the process or a socket to a network: there is no `bindProcessToNetwork`, `setProcessDefaultNetwork` or
`bindSocket` in `M/` (SonosHome's comment says the always-on VPN refuses a bind anyway). OkHttp keeps its own pool and
uses the system resolver per call, so a dead connection fails and the next call opens a new one. That is why the Web
API calls kept working.

## 3. The root cause: what is proven and what isn't

Proven from the sources (§2.1, §2.2): the parent's switch ends with a change WebView can't see, and every piece of
state a page depends on below its own JavaScript lives in the process: the network service, the network-change state
and the protected-media deferrer. A new page in the same process inherits all of it, and a new process inherits none
of it. That matches facts 3 and 4.

Not proven: which piece breaks. In order of fit:

- **H1, a protected-media request that never ends.** It fits fact 3 exactly: a `MediaDrmBridge` is created, and the
  SDK stops before it asks for its token. The old page's player (fact 2) would stall at its next session the same way.
  It needs provisioning to have been started at all, and it is unknown whether WebView provisions per origin.
- **H2, a dead connection the network service keeps.** An HTTP/2 or WebSocket session opened during the sink, or a
  request without a timeout on a socket that gets no answer. This fits facts 2 and 5 (Spotify's backend can't reach the
  device). Alone it fits fact 3 less well, since the page and the SDK's iframe still loaded.
- **H3, Spotify's side.** The account's Connect state for the old device id. This doesn't explain fact 3 (a new page
  never reached Spotify) or fact 4 (the stored device id survives a force stop in web storage).

M1 (§7) tells H1 from H2. The design below doesn't depend on the answer. H1 and H2 are both process state, so the fix
is a process the app can throw away.

## 4. The design

### 4.1 Smaller fixes that don't work

| Option | Why not |
|---|---|
| A new page in the same process | Fact 3: it failed the same way. |
| `WebView.setNetworkAvailable` | It only sets `navigator.onLine`, and it turns off WebView's own network detection (§2.2). |
| Flush Chromium's pools, host cache or DRM state | No public API does this (we use the platform WebView only, no `androidx.webkit`). |
| Bind the process to the underlying network | It would bypass the launcher's DNS filter: an insecure fallback, refused. |
| Restart the whole music process | The kid's screen and the media session would vanish with it. |
| The launcher ends each switch with a new network (lockdown last) | It would only cover this trigger. A real phone has others (Wi-Fi to mobile, the sink starting when Wi-Fi drops), and H1 and H2 aren't tied to one switch. It is noted as a possible follow-up in the launcher and is not part of this design. |

### 4.2 The web player in its own process, one process per page

- A new service, `SpotifyWebService` (`M/spotify/`), declared with `android:process=":web"` and
  `android:exported="false"`. It is a bound service only, never started and never in the foreground. It owns the one
  `SpotifyWebHost` exactly as today: the WebView with the application context, never attached to a window, the same
  settings, the interceptor (`WebPolicy`), the service-worker client and the page bridge.
- The main process gets `RemoteSpotifyHost : SpotifyHost`. The renderer keeps talking to the `SpotifyHost` interface
  and doesn't know about the move. `SpotifyRendererTest`'s scripted host stays as it is.
- **One process per page.** `open()` binds the service (`BIND_AUTO_CREATE`) and asks it to create the page.
  `destroy()` asks for the SDK's `disconnect()`, waits for it as today (`DISCONNECT_SETTLE_MS`, at most
  `DISCONNECT_WAIT_MS`), then the service destroys the WebView and ends its own process
  (`Process.killProcess(Process.myPid())`). The main process has unbound and unlinked first. Every new page starts in a
  fresh process, which is what the force stop did.
- **The bridge** is an AIDL pair private to the music app (`M/spotify/web/`; this is not the launcher bridge, so
  `bridge_contract.json` doesn't change). All calls are `oneway`:
  - main -> web: `open(sdkUrl, debugBase)`, `call(name, argsJson)`, `destroy()`, `clearStorage(done)`;
  - web -> main: `ready`, `notReady`, `needToken`, `state(json)`, `error(kind, message)`, `log(line)`, `cleared()`.

  `call` carries the name and the JSON arguments. The web side checks the name against the page's call names and
  builds the script with `SpotifyPage.call`, so every argument is still quoted where it is evaluated. The page bridge's
  checks (`PageBridge`: device id, request id, the 8 KB state cap, error kinds) stay in the web process, before
  anything crosses.
- **Process death.** `linkToDeath` in the main process. A death the main process didn't ask for becomes
  `HostEvent.Gone`, which the renderer already handles ("Stoppet - trykk spill"), the same as `onRenderProcessGone`.
- **Logs.** The web process has no log gate of its own. Its fixed lines go to the main process over `log(line)`, and
  the service's `spotifyLog` decides there (the diagnostic switch is main-process state). The host's log words are
  unchanged, and there is still no `Log.` in `spotify/`.
- **The debug switch.** `SpotifyEndpoints.debugBase` is assigned only in the main process (`DebugSpotify.kt`). `open()`
  passes it over (null in a release), and the web side sets its own copy before `WebPolicy.current()` is read. The
  six readers' loopback-only rule is unchanged.
- **Storage.** WebView's data stays in `app_webview`. Only one process ever uses WebView, so no
  `setDataDirectorySuffix` is needed and nothing moves. `clearStorage` (web storage, cache, cookies, awaited) runs in
  the web process. With no page up, it binds a process just for the clear, which ends itself afterwards.
- **The main process can't make a WebView.** `MusicApp.onCreate` calls `WebView.disableWebView()` in the main process
  (API 28; an accidental WebView there throws). The call sits in `SpotifyWebHost.kt`'s companion, the one file
  `SourceScanTest` lets name WebView.
- **The web process runs nothing else.** `MusicApp.onCreate` returns at once when `Application.getProcessName()` ends
  in `:web`: no notification channel, no config, no sync, no receiver. Providers (WorkManager's startup) are created
  in the main process only, as today.

### 4.3 Recovery: detect a dead player, throw it away, start once more

A page is "fresh" when it was created by the start now running. The rules (the renderer, `M/spotify/SpotifyRenderer.kt`;
the words in `logic/`):

- **R1, `ready` not in time (20 s).** As today: destroy the page and report UNREACHABLE. The difference is that the
  kid's next tap now gets a new process, so it works.
- **R2, no state of ours after a 204 play (10 s), or the 30 s gap watch, on a page that isn't fresh.** Destroy the page
  and make **one** new start at the same place. This only happens while the kid still wants it playing (`pwr`,
  `playAllowed`, the network gate, no call), and it never starts anything by itself. A second failure is UNREACHABLE,
  as today. Log: "spotify: the web player gave no state - a new one, once".
- **R3, a 502 from the Web API on a page that isn't fresh.** This covers before the transfer (rc.3 reads that as
  "device unknown") and after it. Destroy the page and make one new start (a new device id: `tracker.newDevice()`),
  in place of today's transfer and retry. On a fresh page, rc.3's rule stays.
- **R4, a network change while a page is up and not playing.** The service watches the default network
  (`registerDefaultNetworkCallback`): a new network, or the same network with another interface name, routes or DNS
  servers. This is exactly the change WebView can't see. The page is marked stale, and the next start destroys it
  first and makes a fresh one. A playing page is left alone; if it dies, R2 catches it. This spares the kid R2's 10 s
  wait in the common case: the parent changes something while the music is paused. **R4's cost is the user's
  choice (§9).**

Each rule recycles a page at most once per start, so there is no loop. The renderer's watchdog numbers don't change.

### 4.4 Android 14 to 17

- `android:process`, bound services, `linkToDeath` and `Process.killProcess` on a process of the app's own UID behave
  the same on 14, 15, 16 and 17. `disableWebView` and `getProcessName` are API 28.
- Importance: the web process is bound by the media service's process while it plays, so it takes on that process's
  priority. The WebView's own renderer keeps `RENDERER_PRIORITY_IMPORTANT`. Network policy, Doze, standby and the
  audio focus are per UID, the same as today. When unbound the process ends itself and is never left cached, so
  Android 14's cached-app freezer never holds it.
- WebView's own rules (one data directory per process, Android 9 and later) are met: a single process uses WebView.
- Checked against the sources and documentation only. M4 runs the emulator on 14 and on the highest image available (17
  if one exists, else 16).

### 4.5 What doesn't change

The launcher, the server, the policy, the token's path (`getSpotifyToken`, `TokenCache` in the main process), the
page's assets, `WebPolicy`, the CSP and the kid's screens. The token now crosses one more binder, to our own process,
as a `oneway` argument. It is never logged, stored or kept past the call, so 26c §7.1 holds.

## 5. Copy

None for the kid or the parent. The kid sees play start, maybe a few seconds later. Only diagnostic lines are new, in
fixed words: "the web player gave no state - a new one, once", "a 502 on a page that is up - a new one", "the network
changed under an idle page - a new one at the next start", "the web player's process ended" (an unasked death).

## 6. Tests

- `SpotifyRendererTest` (the scripted host):
  - R2: a non-fresh page, play 204 and no state for 10 s give one destroy, one new open and one new play call at the
    same place, and the second failure is UNREACHABLE. The same failure on a fresh page gives no retry. Paused, in a
    call or with the gate shut, there is no retry.
  - R3: a 502 on a non-fresh page means recycle then start, with no transfer and retry. On a fresh page, rc.3's
    transfer and retry are kept.
  - R4: a network change while paused means the next start opens a new page first. While playing it does nothing.
  - One recycle per start.
- `RemoteSpotifyHostTest` (JVM, a fake binder pair): every `HostEvent` crosses unchanged. A late event of a destroyed
  page is dropped (generation). An unasked death becomes `Gone` and an asked one is silent. `call`'s name check works,
  the arguments are quoted at the far end, and the token never appears in a log line.
- `SourceScanTest`: `WebView(`, `android.webkit` and `addJavascriptInterface` stay in `SpotifyWebHost.kt` only (the
  service file holds no WebView code). The manifest has the service in `:web`, not exported, and nothing else in that
  process. `MusicApp` returns early in `:web` and disables WebView in the main process.
- `GuardsTest`: no `Log.` in `spotify/` (the new files included).

## 7. Emulator gates

The real SDK and a Premium account are needed: the fake's stub fetches through OkHttp (`DebugProxy`), not through
Chromium's network service, so §6l's loop A can't show this bug. The gates therefore run on the user's emulator, or on
ours with the user's account, and are not run in this round.

- **M1 (settles §3, before the build if the user has 10 minutes).** On rc.4 (no process change), reproduce facts 1 to
  3 with "Bare Vibb" and back. Collect `adb logcat` from the music process for `cr_MediaDrmBridge`, `cr_MediaDrm*` and
  `chromium`, and run `adb shell dumpsys connectivity` before and after each switch.
  - "Not provisioned during openSession()" / "Provisioning origin ID" with no end -> H1.
  - Provisioning complete, but no token request -> H2.
  - In both cases, check from dumpsys that lockdown changed the VPN's netId and that the last switch kept it (§2.1).
- **M2.** On the build: the same steps. The next start after the switch plays (R4), with no force stop. Then toggle
  while playing: R2 brings the music back within about 15 s, or leaves "Trenger internett" only if the network is
  really down.
- **M3.** A 502 on a page that is up gives one recycle and the play (if Spotify still gives the 502 in M1's state).
- **M4.** On Android 14 and on the highest image: a start, pause and resume, a call (`CALL_HOLD`/`CALL_RELEASE`), the
  10 s sleep fade, the screen off for 15 minutes while playing (the audio focus and the media session, from the web
  process), and the 5-minute idle close, which leaves no `:web` process (`adb shell ps -A | grep me.vibb.music`).
- **M5.** The cost: the time from tap to sound for a start with a new page in a new process, against today's new page
  (target: at most 1.5 s more), and the extra memory (`dumpsys meminfo me.vibb.music:web` while playing).
- **M6.** Account change and `forget()`: the clear runs in a process of its own, and the next page starts with empty
  storage (26c §5.2's marker).

## 8. Cost

- Build: one service, one AIDL pair, `RemoteSpotifyHost` (about 250 lines) and R2 to R4 in the renderer (about 120
  lines), plus their tests. `SpotifyWebHost` moves unchanged. This is about two build days with QA, plus the emulator
  gates.
- Runtime: a second app process while a page is up. M5 measures it; WebView's browser-side memory moves out of the main
  process rather than doubling. Each page start pays a process start, a few hundred ms (M5).
- Risk: the bridge is new surface. It is narrow (5 events, 4 calls), private, and not exported.

## 9. The user's choice

**ASK 1, R4: a fresh web player at the next start after any network change.** It costs about 1 to 2 s at that start (a
new process and page; M5 measures it). Without it, the first start after such a change waits 10 s for R2 and then
plays. Recommended: **yes**. A pause, a network change and a play is the common case, and a 10 s silence is worse than
a slower start.

Everything else follows from the evidence and needs no choice.

## 10. Revision 2 (2026-11-20): option B, R2 and R5 built

**Why now.** Fix A (the launcher's order) fixed the network trigger, and B was parked "until the bug shows on another
trigger". It did on the user's Jelly Star on 2026-11-20: the first time a Bluetooth headset paired while Spotify
played, the player went silent, the app said "Trenger internett", and only killing the app helped. That is this note's
dead player (§1), with the audio route as the trigger (scratchpad `sonos-bt.md` §3b). vibb has the same class of bug on
the Pi and heals it the same way: `_go_output_rebuild` rebuilds go-librespot's output and replays from its place. The
user chose B + R2 + R5 the same day.

**Built.**

- **B, as §4.2 with the design QA's fixes.** `SpotifyWebService` runs in `:web` (not exported, bound only) and holds the
  unchanged `SpotifyWebHost`. The renderer's host is `RemoteSpotifyHost` over a private AIDL pair in
  `src/main/aidl/me/vibb/music/spotify/web/` (L5). The binder glue (`AndroidWebConnector`) is thin, so the logic is
  JVM-tested through Kotlin seams (L6).
  - **The lifecycle (H1).** Each page gets its own process. A destroy sends `destroy()` and unbinds. The next bind waits
    until Android no longer lists the old process; past 3 s the main process kills it (our own uid), and past 4 s it
    binds anyway. The web side ends itself only after its service's `onDestroy`. A process that already had a page
    answers `stale`, and the main process binds a fresh one, once. Each binding has its own sink, so an old process's
    events never reach a new page.
  - **Calls (M4, L4).** `alive` is true from `open()`. Calls queue in order until the process is connected, and a queued
    token is dropped with its page. Calls are typed: `token(id, token?)`, `seek(ms)`, `volume(level)`, and
    `command(name)` for pause, resume, next, previous and state. `connect` and `disconnect` belong to the web side.
  - **Deaths (M4, L8).** An unasked death unbinds at once. If the page was up it is `Gone`. A bind that never came up
    gives no `ready`, and R1's wait applies. The renderer dying in `:web` gives one `Gone` and ends that process.
  - **The clear (M3).** The clear runs in the next page's own process before the page loads (`open`'s `clearFirst`).
  - **The process itself (§4.2).** Events are sent from `:web`'s main looper (L3). The main process calls
    `WebView.disableWebView()`, and `MusicApp` returns at once in `:web`. The bind uses `BIND_AUTO_CREATE |
    BIND_INCLUDE_CAPABILITIES` (M2).
- **The debug switch.** `:web` has no debug feed, so the main process passes the base at each `open`. A seventh reader,
  `SpotifyEndpoints.webBase()`, returns it, and it goes through the fake-homes parse. The web side assigns its own copy
  only in a debug build. That assignment is the one in `src/main`, and SourceScanTest pins it.
- **R2, with L1, L2, L7 and H2.** These silences recycle the page with its process and make one new start at the same
  place, with shuffle and repeat set again, `tracker.newDevice()` and `lastSet = null`:
  - no state of ours within 10 s of a play call, on a page the start didn't make;
  - the 30 s gap watch, which also covers a resume through the SDK;
  - and, new in this revision, the page's process ending under a play the kid wants. Without this, a killed `:web`
    would only say "Stoppet - trykk spill". With it, the music goes on, and a second death before any state is
    "Stoppet" as before.

  The rule is `spotifyRecycles`. It recycles only while the kid still wants the music playing: `pwr` is set and the
  track hasn't ended, playing is allowed, there is no call, the renderer isn't detached and the network gate is open.
  It never recycles on a page the failing start made (a new process didn't help: UNREACHABLE as before). It never
  recycles twice without a state of ours in between, which is the loop guard and replaces L1's "per kid action". A
  recycled start waits at most 10 s for `ready` (L7).
- **R5 (new).** An audio output that is added or removed (the service's `AudioDeviceCallback`, a headset sink) while the
  web player plays asks the page for its state at once (`vibb.state()`). If no state of ours comes within 8 s, the page
  is recycled once by the same rule. A headset that is removed pauses first (becoming noisy), so in practice R5 covers a
  headset arriving.

**Not built.** R3 (a 502 on a page that is up) and R4 (the network snapshot) are not built. Fix A covers the network
trigger, and R2 now catches any dead page within 30 s, so a remaining case costs at most that wait. M5's new
`player.js` diagnostic lines are not built either.

**Words.** None for the kid or the parent. The fixed diagnostic lines:
- "the web player gave no state - a new one, once"
- "the web player's process ended - a new one, once"
- "no state of ours after the output change"
- "a new web player didn't help - no other"
- "the old web player's process is still ending - the new one waits"
- "the old web player's process outlived its destroy - ended"
- "the web player's process ended"
- "the web player's process didn't come up"

**Android 14 to 17.** `android:process`, bound services, `linkToDeath`, killing a process of our own uid,
`getRunningAppProcesses` for our own processes, `disableWebView` and `getProcessName` (API 28) behave the same on all
four. Two things are not verified:
- whether audio focus and Android 17's background-audio hardening judge `:web` by its uid or by its own process (QA
  M2). The binding includes capabilities, and the phone check below decides;
- the Jelly Star's real SDK, with Widevine in a second process.

**Tests.**
- `RemoteSpotifyHostTest`: the lifecycle against a fake glue.
- `SpotifyRendererTest`: R2 on a step, on the gap after a resume, and on a process death; the gates; R5; the second
  silence stopping.
- `SpotifyRulesTest`: `spotifyRecycles`.
- `SourceScanTest`: the manifest, `MusicApp`'s early return, the bind flags, the debug base's one assignment and the
  AIDL's folder.

**Emulator (§6l A2, `fake-spotify.py`, unmanaged, `compat34` Android 14 and `aosp36` Android 16, both wiped).**
- A play runs in `me.vibb.music.debug:web`, with the clear before the first page in that process. From a tap to the
  first state with a new process took about 1.1 s on 14.
- `kill -9` of `:web` while playing: a new process, and play went on from the same place (34 s on 14, 19 s on 16)
  within about 1.3 s.
- `kill -STOP` of `:web`, the hung player: the 30 s gap watch recycled it, the frozen process was killed at the 3 s
  ceiling, and play went on from the place (66 s on 14, 28 s on 16).
- On 16 with the app in the background (Home), a recycle's new `:web` got the audio focus: `dumpsys audio` showed
  Chromium's `AudioFocusDelegate` of the app's uid on top.
- On 14, a pause and a resume went through the page, the 5-minute idle close left no `:web` process, and the next play
  made a new one.

R5 can't be triggered on the emulator, which has no headset to plug in.

**Owed on the phone.**
- The Bluetooth pairing that started this (R5 or R2 should bring the music back within about 8-30 s).
- The real SDK and Widevine in `:web`.
- The audio focus with the screen off on Android 16, and M4's background-audio check on Android 17 when an image exists.

### 10.1 After the code QA (`docs/review/2026-11-20-spotify-output-recover-code-qa.md`)

These change some rules above; where they disagree, this subsection wins.

- **The clear (M1).** A clear asked by an account change is kept until a page has really opened with it: the first page
  event from that binding proves it. A process that refuses the page (`stale`), a death before the link, or a page
  dropped before any event hands the clear on to the next page.
- **A failed recycle (M2).** When a recycle is refused only because the page never played or the last recycle hasn't
  proven itself, the dead page goes with its process anyway (after the line). The kid's next tap then gets a fresh
  process, not the same silence. There is still no automatic second recycle.
- **A death while paused (M3).** `:web` ending while the music is paused (lmkd picks it first) drops the page quietly,
  with no line. The next play makes a fresh page at the place.
- **The loop guard (L1, L2).**
  - A page counts as fresh from its open until its first playing state of ours. It no longer depends on which start
    opened it.
  - A recycle re-arms only after the recycled player has played 30 s on end (`SPOTIFY_RECYCLE_PROOF_MS`), and there
    are at most 2 recycles in 10 minutes (`SPOTIFY_RECYCLE_MAX`, `SPOTIFY_RECYCLE_WINDOW_MS`). A player that crashes at
    the same point each time stops at "Stoppet" or "Trenger internett".
- **R5's proof (L6).** A state alone is no proof. The place must have moved on at least 1 s past where the last
  measurement put it at the change, or the item must have changed (`spotifyPlayedOn`). A player whose sink died while
  the SDK still ticks "playing" is recycled too.
- **A bind that never comes up (L7, L8).** The host tells the renderer at once (`HostEvent.NeverUp`), so the start says
  "Trenger internett" without the 10-20 s wait. `attached(pid)` is gone from the AIDL; the kill finds the process by its
  name and our uid.
- **The process's end (L3).** `:web` kills itself from a posted call, after its service's `onDestroy` has returned.
- **"Telefonen" (L4, L5).** "Ta ut ledningen først" shows only after the kid picked "Telefonen" with a wired headset
  in, never unasked. A successful Bluetooth move clears an earlier refusal. The word ends at a real change of the
  output devices, never at the route callback's first call when it is registered.
- **Emulator after the fixes (`aosp36`, Android 16).**
  - `kill -9` of `:web` while paused: no line, still paused at 0:17. The next play made a fresh process at 17 s.
  - A suspension destroyed the page, and `:web` ended itself with the posted kill. There was no "outlived its destroy"
    line.
