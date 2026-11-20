# Code QA: `spotify-output-recover` (2026-11-20)

Branch `spotify-output-recover`, 4 commits on e78742e4f (746faa5fd, 5d2e750e6, 7bd2baa6e, 82ef79410). Read against
design 120 (with §10), 26c §5.6, `docs/notes/music/spotify.md`, music/CLAUDE.md. Static review only: no Gradle, no
emulator.

Paths: `M/` = `music/app/src/main/java/me/vibb/music/`.

## Verdict

No H. Three M findings, all small fixes. The lifecycle (one page per process, the exit wait, the per-binding sinks,
asked and unasked deaths) holds up. I found no double bind, no page in two processes, no WebView in the main process,
no duplicated calls across the AIDL, no double start after a recycle, and no recycle loop without a state in between.
The BT "Telefonen" path catches everything and reads the real route afterwards, so a refusal on 14-17 (or a blocked
hidden API on 17) ends in the honest line. Nothing is exported, and the kid-visible strings never say "Spotify".

## M

### M1. An account change's clear can be lost: `clearFirst` is consumed before any page has used it

`M/spotify/RemoteSpotifyHost.kt:141` (`open` takes `clearNext`), `:207-208` (`onConnected` sends it and resets
`c.clearFirst = false`), `:225-237` (Stale rebinds), `:241-262` (death before the link), and
`M/spotify/SpotifyRenderer.kt:1153-1160` (the marker is written at once).

The renderer's clear is now only a flag. `clearStorage` sets `clearNext`, completes the renderer's `clearing` wait at
once, and the renderer then writes the new account's marker (`storePageAccount(token.account)`). The real clear
happens only when a `:web` process opens a page with `clearFirst = true`. The flag is lost in two ways:

- **Stale.** The bind lands in a process that already had a page. This happens after the 4 s give-up, or when a
  leftover `:web` from a renderer released a moment earlier is still disconnecting. `onConnected` has already sent
  `open(clearFirst = true)` and reset the flag. That process refuses the open (it drops the open, clear included), and
  the rebind sends `open(clearFirst = false)`.
- **Early death.** The process dies before it connects. `onDied` sets `page = null` and the flag goes with that
  `Conn`. The next `open()` reads `clearNext`, which is already false.

In both cases the new account's page loads on the previous account's cookies and web storage. The marker already
names the new account, so no later page clears either. This breaks 26c §5.2 / qa-26c-step5a-code M1. The path is
rare, but it is the account boundary.

Fix: keep the flag until a page has really opened with it.
- In `onConnected`, don't reset `c.clearFirst`. Reset it only once a signal from that binding proves the page was
  opened, for example the first `WebSignal.Page`. An `opened`/`cleared` event would be cleaner; design 120 §4.2 had a
  `cleared()` event that the build dropped.
- On Stale, keep `c.clearFirst` for the rebind.
- In `onDied` for a `Conn` that never linked, put the flag back into `clearNext`.

Add a `RemoteSpotifyHostTest` row for each: clear, then Stale, then rebind opens with `clear=true`; clear, then death
before connect, then the next open has `clear=true`.

### M2. After a recycle fails, the silent page is kept, and every later tap reuses it until the 5-minute idle close

`M/spotify/SpotifyRenderer.kt:1860-1863` (`silent`'s refusal goes to `fail(UNREACHABLE)`), `:1348-1371` (`fail` keeps
the page for UNREACHABLE), `:1086` and `:1302` (`pageFromStart` is true only for the start that opened the page),
`:1535` (`recycleUnproven` clears only on a playing state of ours).

The scenario: R2 recycles, and the new process's page reaches `ready` but stays silent after the play's 204. That is
`silent(freshPage = true)`, then `fail(UNREACHABLE)`, and the page stays up. The kid taps play. `resumable()` is
false (the tracker is LOST), so this is a fresh start on the page that is up, with `pageFromStart = false`. The play
goes out, and 10 s later comes `silent(freshPage = false)`. `recycleUnproven` is still true, so there is no recycle,
again "Trenger internett", and the page stays. Every tap does the same. `begin` cancels the idle timer, so this lasts
as long as the kid keeps trying within 5 minutes.

This is the original fact 2 (every start dies on a dead page) coming back after one recycle. R1 already destroys a
page that wasn't ready "so the kid's next tap gets a new process". The same reasoning applies here, and design 120
§4.3 R1 says it in words.

Fix: in `silent`'s refusal path, when the refusal is because of `freshPage` or `recycleUnproven`, destroy the page
before `fail`. The next tap then makes a fresh process for about 1 s, and the guard still allows no automatic second
recycle. Extend the gap test (`held through a 30 s gap ...`): after "no other", a tap makes `page.opens == 3`.

### M3. `:web` killed while paused now shows "Stoppet - trykk spill"

`M/spotify/SpotifyRenderer.kt:1438-1450` (Gone: `playing` includes a paused track of ours, `tracker.mode == OURS`, so
it goes to `silent` with `wanted = false` and then `fail(STOPPED)`), `M/spotify/RemoteSpotifyHost.kt:255-257`.

Before B, a low-memory kill took the whole app: the WebView's browser side lived in the main process. The restart
showed the paused now-playing bar with no line. `:web` is now a separate process that holds the WebView's
browser-side heap, and it has the same importance as its paused, non-foreground client, so lmkd will usually pick it
first. Its death while the music is paused is an unasked death with `wasUp`. That gives `HostEvent.Gone`, then
`fail(STOPPED)`, which sets `stopped = true`, IDLE and the line "Stoppet - trykk spill". The kid comes back to an
error for something they need not know about: the next tap makes a fresh page anyway. "Words in the UI" says to
speak up only when something is wrong, and design 120 §5 says "no new words".

Fix: in the Gone branch, when `!pwr && start == null`, drop the page quietly: `deviceId = null`, `tracker.lost()`,
`destroyHost()`, no `fail`. Keep `fail(STOPPED)` for a death under a play that can't be recycled. Add a
`SpotifyRendererTest` row: paused, Gone, so no line and still READY; then a tap opens a new page and plays at the
place.

## L

- **L1. A page made by a dropped start counts as not fresh** (`M/spotify/SpotifyRenderer.kt:1086`, `:1302`). A start
  opens the page (`pageFromStart = true`). It is dropped before its calls, for example by a quick list step or a new
  queue. The next `begin` resets `pageFromStart = false` and finds the page alive. Its silence then recycles a page
  made a second earlier, at the cost of one extra process before UNREACHABLE. Fix: mark the page as fresh when it is
  opened (a renderer field set in `awaitReady` and cleared by `destroyHost`), not per start.
- **L2. The deviation (recycle on death) is sound, but the loop guard is weak against a deterministic crash**
  (`:1535`). Any playing state of ours re-arms recycling. A `:web` or renderer crash that comes a few seconds into
  playback, at the same point each time (a DRM or decoder crash on one track, or memory pressure while playing),
  recycles forever. Each cycle plays a little and replays from the place. It stops only at the kid's pause. The gates
  (call, gate, suspension, detach) are right, and a death during a start correctly stays "Stoppet". Suggest re-arming
  only after the recycled player has played for a while (say 30 s of position), or capping recycles at two per 10
  minutes. The design's rule is met as written, so this is the user's call.
- **L3. `killProcess` runs inside `onDestroy` before `super.onDestroy()`** (`M/spotify/SpotifyWebService.kt:83-87`,
  `:158-159`). When the disconnect has already finished, `maybeExit()` kills the process before ActivityThread
  reports `serviceDoneExecuting`. AMS copes, because the service has no connections and was never started. Still,
  posting the kill (`main.post { maybeExit() }`) would really do "only after the service's onDestroy", as §10 says.
- **L4. A successful "Telefonen" move doesn't clear an earlier refusal** (`M/ui/MusicViewModel.kt:331`, `:412`).
  `routesChanged` skips the reset while a move is in flight, and the success path never sets
  `phoneRefused = false`. The line can't show while no Bluetooth headset is routed, but after "Bytt til" a stale
  `true` survives if that route change also happens during a move. Set `phoneRefused = false` when
  `bluetoothOffTook` is true.
- **L5. "Ta ut ledningen først" is the phone row's sub-line whenever a wired headset is in under the web player**,
  even when the kid hasn't picked "Telefonen" (`M/ui/PlayerScreen.kt:474`, `phoneRowLine`). It describes the row, so
  it is defensible. Check it against "speak up only when something is wrong": a wired headset in use isn't wrong. The
  alternative is to show it only after a refused pick, as Bluetooth does.
- **L6. R5 proves only that a state came, not that the sound goes on** (`M/spotify/SpotifyRenderer.kt:1896`). If
  Chromium's audio sink died but the SDK still posts "playing" states, R5 passes, and so does the 30 s gap watch.
  The user's case showed no states at all, so this is covered as it stands. If the phone check shows the other shape,
  compare the position after the change with the one before.
- **L7. `attached(pid)` is sent and ignored** (`M/spotify/RemoteSpotifyHost.kt:217`). The main process finds the
  process to kill by name (`webPid`). Either use the pid (more exact than the name), or drop the call from the AIDL.
- **L8. A bind that never comes up waits the full ready window** (`M/spotify/RemoteSpotifyHost.kt:258-260`). That is
  10 s on a recycle, 20 s otherwise, before "Trenger internett", although the host already knows the process is dead.
  The design accepted this. An event to the renderer (for example `HostEvent.Gone` with a "never up" flag that
  completes `readyWait` with null) would shorten it.
- **L9. The docs are behind in one place.** `docs/notes/music/stack-by-package.md:41` still says
  "`SpotifyWebHost` (the one headless WebView)" with no `:web`, `SpotifyWebService` or `RemoteSpotifyHost`.
- **Process.** PLAN.md isn't touched on the branch. Mark the queue items (the Spotify output move, design 120 B, R2
  and R5) built in the merge commit (CLAUDE.md, "The todo list is mine to keep current").

## Checked and fine

- **Lifecycle.** Each binding has its own `Sink`, and an old process's callbacks reach only its log line or the
  exit wait. `open` waits during `exitWait`. After an unbind, the death recipient still ends the wait. The kill (by
  name, our uid, at 3 s) only ever hits an unbound process, so Android never restarts it. A frozen or hung process is
  killed at 3 s. The give-up at 4 s is backed by Stale. AMS's predecessor handling also serialises the same process
  name. WebView's data-directory lock is safe: only `:web` uses WebView, and only one `:web` exists at a time.
- **The main process.** `disableWebView()` is the first call after `super.onCreate()`, and no main-process code
  touches `android.webkit` (grep). Content providers stay in the main process. `MusicApp` returns early in `:web`.
- **The AIDL.** All calls are one-way, on one binder per direction, so they arrive in order. Calls queue until
  connect and are replayed once, and a queued token is dropped with its page. The calls are typed, and
  `COMMANDS` is checked on both sides. Every renderer call name (`pause`, `resume`, `next`, `previous`, `seek`,
  `state`, `volume`, `token`) is covered. No log line carries an argument.
- **Security.** `SpotifyWebService` is `exported="false"` with no intent filter. Both stubs check
  `getCallingUid() == myUid()`. The events binder is handed only to `:web`. `fakeBase` is ignored unless
  `BuildConfig.DEBUG`, and every reader goes through the fake-homes parse.
- **Recycle gates.** `spotifyRecycles` gates on `wanted = pwr && !ended`, `mayPlay` (suspension, which carries the
  time rules), `inCall`, `detached`, the network gate, `freshPage` and `recycleUnproven`. Every caller requires
  `start == null`. A death during a start stays STOPPED.
- **Tracker after a recycle.** `destroyHost` calls `tracker.lost()`, then `newDevice()`. The new page's `ready`
  calls `newDevice()` again. States from before the play are held and fed "for nothing" before `played()`, so
  `prePlay`, `carriedOn` and `follow` still see a restored session on the same device id. `stepAfterStart` carries
  into the recycled request and is sent once at `onStarted`. A list step during the recycle's start supersedes it or
  queues behind it. No double start.
- **Audio focus and the media session.** Focus is per client, not per process, and Chromium's `AudioFocusDelegate`
  only moves to `:web`. The session stays Media3's in the main process. `BIND_INCLUDE_CAPABILITIES` is there. Android
  15+'s focus hardening and 17's background audio are owed on the phone (design §10 says so).
- **Battery.** The idle close destroys the page and the process ends itself. The exit wait polls at most 16 times.
  `:web` is never left without a page: the 5 s ceiling and `onDestroy` both end it.
- **The reflection.** `callA2dp` catches `Exception`, which covers `NoSuchMethodException`, the
  `InvocationTargetException` around a `SecurityException`, and a null proxy. Kotlin's vararg passes `[null]` to
  `setActiveDevice`. The 3 s route re-read turns any refusal (a version, an LE Audio headset, a re-activation) into
  "Slå av hodetelefonene først".
- **`BLUETOOTH_CONNECT`.** Without the grant, the pick is refused at once with the same honest line, and the proxy is
  ref-counted (`calls`).
- **Strings.** nb "Ta ut ledningen først", "Slå av hodetelefonene først"; en "Unplug the headphones first", "Turn the
  headphones off first". They are short, and neither says "Spotify". The diagnostic lines are fixed words.
- **Tests.** The pins are right as far as they go. `RemoteSpotifyHostTest` has no row for the clear across Stale or
  an early death (M1). The gap test stops before the kid's next tap (M2). The renderer tests have no paused-death row
  (M3).
