# Design QA: 120, Spotify recovers by itself after a network change (revision 1)

Adversarial review of `docs/design/120-spotify-recovers-by-itself.md` (branch `design-120`, 04145bb99) against `music/`
and `launcher/` on that commit, Chromium `main` (`net/android/.../NetworkChangeNotifierAutoDetect.java`,
`media/base/android/.../MediaDrmBridge.java`, read 2026-11-19) and Android's published behaviour changes for 15 and 17
([Android 17 background audio hardening][bga]). The user's answer to ASK 1 (yes: a fresh web player at the first start
after any network change) is reviewed too. Read only; nothing was built or run.

**Counts: H 2, M 6, L 9.**

## What holds (checked, no finding)

- **Chromium doesn't hear an in-place VPN change.** `NetworkChangeNotifierAutoDetect.connectionTypeChangedTo`
  (l. 1082-1090) notifies only on another connection type, netId or private DNS. `AndroidRDefaultNetworkCallback`'s
  `onLinkPropertiesChanged` only feeds that test (l. 118-121). A VPN's type comes from its transports (l. 144-181),
  so Wi-Fi to mobile under the VPN *is* heard. §2.2 is right, and this matters for M1 below.
- **The deferrer is process-wide.** `sMediaCryptoDeferrer` is `static final` (l. 139). While one bridge provisions,
  every other `createMediaCrypto()` waits (l. 334-345). §2.3 is right.
- **`setNetworkAvailable` is correctly refused.** So is binding the process to the underlying network (it would
  bypass the DNS filter: an insecure fallback).
- **No `setDataDirectorySuffix` is needed** once the main process calls `WebView.disableWebView()`. Only `:web` then
  uses `app_webview`, so cookies, web storage, the SDK's device id and any Widevine origin data stay where they are.
  Nothing else in `music/` touches `android.webkit` (grep: `SpotifyWebHost.kt` only, plus comments). `MediaCache`'s
  clients (the debug proxy) don't open the `SimpleCache`, so `:web` never takes the cache's folder.
- **Providers stay in the main process.** The FileProvider and androidx.startup's provider have no `android:process`.
  `MusicApp`'s early return in `:web` covers the rest.
- **Wake and Wi-Fi locks, network policy, Doze and standby** are per UID. They stay in the main process
  (`SpotifyLocks`) and still cover audio made in `:web`.
- **No double sound.** A recycle kills the old page's process, which is a stronger guarantee than today's
  `destroy()`. Only one start is ever in flight (`start`), and R2/R3 replace a `fail`, never run beside one.
- **Spotify's terms.** The SDK is used as today, loaded at every page and never copied. A recycle is one retry of a
  play the kid asked for, and it adds no automated playback. At most one recycle per kid action (see L1) means no
  storm against the Web API: about 3-4 calls per recycle (shuffle, repeat, play, maybe a transfer).
- **Copy.** Nothing new for the kid or the parent. The four diagnostic lines are fixed words.
- **A side benefit.** A WebView provider update kills every process that uses WebView. Today that is the whole music
  app, the kid's screen and session included. After the change it is only `:web`, which becomes `Gone`.

## H

### H1 - §4.2: one fixed `:web` process name doesn't give "a fresh process per page"

**The problem.** `android:process=":web"` names one process. AMS starts no second process with that name while the
first is alive. It binds a new connection into the existing `ProcessRecord`. The note's `destroy()` keeps the old
process alive for the SDK's `disconnect()` (`DISCONNECT_SETTLE_MS` 300 ms, up to `DISCONNECT_WAIT_MS` 1 s) and only
then calls `killProcess`. Every path the design adds is a destroy followed at once by an open:

- R2 and R3 recycle (destroy, then a new start whose `awaitReady` calls `host.open()`);
- R4's "destroy it first and make a fresh one";
- `forgetAccount()`: `destroyHost(); startClear()` back to back (`SpotifyRenderer.kt` l. 957-959), and then the
  next page after the clear.

In each case the new bind lands in the dying process. The new page is made in the old process, which is exactly the
state fact 3 shows failing. The pending `killProcess` then kills the new page too. The main process sees an unasked
death, which becomes `Gone` and "Stoppet - trykk spill", in the very path meant to recover.

Two more holes of the same kind:

- **AMS restarts a bound service whose process died.** If `:web` crashes or is killed by LMK while the main process
  still holds a `BIND_AUTO_CREATE` binding, AMS restarts the service in a new, empty `:web`. That process stays bound
  and idle, and `onServiceConnected` fires again with a page-less binder.
- **The two binders aren't ordered against each other.** `destroy()` is a `oneway` call on our binder. `onUnbind`
  and `onDestroy` come over the ApplicationThread binder, so the web side may see the unbind first.

**The fix.** Write the lifecycle down as a state machine in `RemoteSpotifyHost`, with tests:

1. **Destroy.** Send `destroy()`, unbind, keep the proxy and wait for its `binderDied`.
2. **Next bind.** `open()` and `clearStorage()` bind again only after that death. They queue meanwhile, with a ceiling
   such as `DISCONNECT_WAIT_MS` + 2 s. Past the ceiling, log it and still don't bind into the old process.
3. **The web side ends itself on its own unbind** (`onUnbind`/`onDestroy`), with or without `destroy()`: the
   disconnect, then `killProcess`. It kills itself only after `onDestroy`, so AMS holds no binding that would
   restart it.
4. **A process is refused a second page.** If an `open` arrives in a process that already had a page (or a clear),
   it answers `gone` and ends. The main process then binds again after the death.
5. **On an unasked death** (`binderDied` or `onServiceDisconnected`; `onBindingDied`/`onNullBinding` too), unbind at
   once, so AMS's restart is never kept.

Add a renderer test (scripted host with a "dying" phase): recycle, then open waits for the death, and no event of
the old process reaches the new page.

### H2 - §4.3 R4 and R2, §9: the common case is a resume through the SDK, not a start

**The problem.** "A pause, a network change and a play" doesn't start anything. `play()` calls `resumable()`
(l. 997: page up, device known, our track, no settings or new order to send), then `resume()` (l. 1004), which is
`sdk("resume")` and `armGap()`. There are no Web API calls, no `begin`, and no 10 s first-state watch. R4 says "the
next start destroys it first", so built as written it never fires in the case it exists for. R2 then catches the
dead page only through the **30 s** gap watch. So §9's "without it, the first start waits 10 s" is really 30 s plus
the recycle. The user answered ASK 1 against the 10 s figure. The answer stands, and is stronger with the right
figure.

**The fix.**

- A stale page is not resumable. Add `"the network changed"` to `resumable()`/`notResumable()` beside `windowStale`.
  The play then becomes a fresh start, which destroys the page, opens a new process and calls.
- R2 applies to the gap watch after an SDK resume as well, which the note's text half says. Name it.
- Correct §9's figure to "up to 30 s".
- Test it: paused, a network change, then play gives a fresh start on a new page, never `sdk("resume")`.

## M

### M1 - §4.1: the launcher-side fix is rejected on a premise §2.2 itself refutes; it may be the smaller fix

**The problem.** The table says the launcher fix "would only cover this trigger. A real phone has others (Wi-Fi to
mobile, the sink starting when Wi-Fi drops)". By §2.2 and Chromium's code, both of those *are* heard:

- **Wi-Fi to mobile** changes the VPN's transports, so `getConnectionType` changes;
- **into the sink** ends with lockdown on, a new netId (§2.1's order).

The only final change Chromium can't see is **leaving the sink**: lockdown off first (a new network that still has
the sink's routes, so Chromium reconnects into the sink), then the filter's tunnel in place. The launcher alone makes
that order (`switchTo`, `alwaysOnBeforeEstablish`/`alwaysOnAfterEstablish`).

Reversing it on the way out is fail-closed: the filter's tunnel in place first while lockdown still blocks
everything outside the VPN, then lockdown off as the last, heard change. A heard netId change flushes Chromium's
socket pools (`ERR_NETWORK_CHANGED`), its host cache and its QUIC sessions. That would also fail a hung provisioning
fetch, so it covers H1 and H2 of §3. It helps **every** Chromium on the phone too (Chrome for logins, design 102's
sign-in window, other apps' WebViews), not only Vibb music.

It is a few lines in `KidVpnService`. The process split is about 2 build days, a new bridge surface, and the Android
15/17 audio questions of M2.

**The fix.** Add an experiment M0 next to M1: rc.4 unchanged, with a launcher debug build that leaves the sink with
the tunnel first and lockdown last. Then "Bare Vibb" and back, then play. Correct §4.1's row either way.
**Question for the user (ASK 2), below.**

### M2 - §4.4: Android 15's focus rule and Android 17's background audio hardening are not checked for audio made in `:web`

**The problem.** The WebView owns the audio and the audio focus (26c §5.6), so both move to `:web`, a process with
no foreground service of its own.

- **Android 15**, for apps targeting 35+ (music targets 36): `requestAudioFocus` fails unless the app is the top app
  or runs a foreground service.
- **Android 17**, for all apps: playback (`AudioTrack.write`, AAudio) is silenced, focus fails and volume calls are
  ignored unless the app has a visible activity or a non-short FGS. Targeting 37, it also needs a while-in-use FGS
  ([source][bga]).

§4.4 says "audio focus is per UID, the same as today" with no source. If AudioService judges the calling *process*
and not the UID, Spotify goes silent on 17 in the background: the page posts playing states and no sound comes. The
watchdogs would not even see it.

**The fix.**

- Name both rules in §4.4, with the per-UID assumption marked unverified.
- Bind with `BIND_INCLUDE_CAPABILITIES` (API 29), so `:web` inherits the main process's while-in-use capability if
  the check turns out per process.
- Add to M4 on the 17 image (or 16 with the flag): `adb shell cmd audio set-enable-hardening enable`, then `throw`.
  Play with the screen off for 15 minutes, and look for `AudioHardening` lines naming the package in `dumpsys audio`.
- On 15: a start from the lock screen's media controls with the app in the background, checking that the focus is
  granted (`dumpsys audio`, focus stack).

### M3 - §4.2 "Storage": a clear-only process that kills itself may cut the clear short

**The problem.** `WebStorage.deleteAllData()` has no completion callback. Today it runs in the process that later
loads the next page, so Chromium's own sequencing orders the delete before the new page's reads. In a process that
ends itself after `removeAllCookies`/`flush`, the web-storage delete (and `clearCache`) may not have reached disk.
The old account's storage, the SDK's device state among it, could survive into the next account's page: 26c §5.2's
rule.

**The fix.** Do the clear in the page's own process before it loads: `open(sdkUrl, debugBase, clearFirst)`. The
renderer already forces the clear at the next page whenever the marker is missing or names another account
(`storePageAccount(null)` in `forgetAccount`; "a differing or missing marker clears"). So `forget()` needs no process
of its own, and H1's death wait covers the rest. If a clear-only process is kept anyway, it must hold the process
after the clear (for example until the next page or a fixed few seconds) and say why.

### M4 - §4.2: `open()` becomes asynchronous; the window before the connection and a failed bind are unspecified

**The problem.** The renderer reads `host.alive` right after `open()` and may `call()` at once (`applyVolume()`, the
token answer, `pause`). `bindService` connects later, or never: it returns `false`, or `onNullBinding`/
`onBindingDied` fires. The note doesn't say what `alive` is in that window, whether calls queue, or what a failed bind
becomes. Today's `Gone` would show "Stoppet - trykk spill", which is the wrong word for a bind that never came up.

**The fix.**

- `alive` is true from `open()`.
- Calls queue in order until connected. Queued `token` answers are dropped if the page dies.
- A failed bind is no `ready`: R1's 20 s path gives UNREACHABLE, and the kid's next tap gets a new process. It is
  never `Gone`.
- Test each case in `RemoteSpotifyHostTest`.

### M5 - §7 M1 (and §3's ranking): not decidable as written

**The problems.**

- **No line marks a finished provisioning.** In Chromium's `MediaDrmBridge`, "Not provisioned during openSession()"
  (l. 332) and "Provisioning origin ID" (l. 1459) are `Log.i` and survive in a release WebView. Success has no
  retained line: `processProvisionResponse()` (l. 1492) and `startProvisioning` (l. 1403) are `Log.d`, which
  Chromium strips from release builds. Only failures are `Log.e`. So "provisioning complete, but no token request ->
  H2" can't be observed. The log tag is `cr_MediaDrmBridge`.
- **"From the music process" misses evidence.** That filter drops the WebView's sandboxed renderer and the DRM HAL's
  logs.
- **§3 ranks H2 low because "the page and the SDK's iframe still loaded".** Both may come from WebView's HTTP cache
  (the default cache mode), so loading proves no network path.

**The fix. Decidable criteria:**

- No "Not provisioned during openSession()" from the failing page means no provisioning ran, so H1 is out.
- Present, with no `Log.e` provisioning failure: compare with a healthy page after the force stop. A healthy page
  that provisions every time says WebView provisions per page.
- `logcat -b main,system` unfiltered, grepped for `cr_MediaDrm`, `MediaDrm`, `WVCdm`, `chromium`.
- `adb shell cat /proc/net/tcp6 /proc/net/udp6` for the music UID before and after the switch. Stuck H2 shows
  established sockets with a growing send queue, or sockets to the sink.

Also, in this build, add `setWebContentsDebuggingEnabled`-free page diagnostics as `diag` lines in fixed words in
`player.js`:

- whether `player.connect()`'s promise resolved, and to what (today it is ignored);
- the time from `connect` to the first `getOAuthToken`.

That makes the next field report self-explaining. M1 stays optional for the build, as the note says, but M0 (M1
above) is cheap and should run with it.

### M6 - §4.3 R4: the trigger needs a baseline and a definition of "any"

**The problems.**

- **The first callbacks fire at registration.** `registerDefaultNetworkCallback` delivers `onAvailable`,
  `onCapabilitiesChanged` and `onLinkPropertiesChanged` at once. As an event flag, that marks the page stale at
  registration.
- **R4's list is narrower than the user's "any".** "A new network, or another interface, routes or DNS" leaves out
  Wi-Fi to mobile under the VPN (only the VPN's transports change).
- **A change while playing is forgotten.** The page is "left alone" and not marked stale, so a later pause and play
  resumes a page that may be broken.

**The fix.**

- At page creation, store a snapshot: the network handle, the interface name, the routes, the DNS servers, and the
  transport set without `TRANSPORT_VPN`. The page is stale when the current snapshot differs.
- Never react to bandwidth, signal or validation flips, which `onCapabilitiesChanged` reports often.
- Mark a page stale while it plays too. It is only recycled at the next play (H2's not-resumable), never during
  playback.
- Test the initial callbacks, a bandwidth-only change (not stale), a transport change (stale) and an in-place route
  change (stale).

## L

- **L1 - §4.3, "one recycle per start".** Starts, resumes and the retry's own start make the rule ambiguous. Define
  `fresh` per page: made by this start, or by a recycle. A page made by a recycle is never recycled. At most one
  recycle per kid action across R2, R3 and R4. This is loop-free whatever "start" means.
- **L2 - §4.3 R3, "a new device id".** Fact 4 says the SDK's device id survives in web storage, so the new process
  likely reports the same id. Call `tracker.newDevice()` and reset `lastSet` explicitly at a recycle (`onHost` does
  it only when the id differs), and don't claim a new id.
- **L3 - §4.2, event order.** Today the bridge methods (the JavaBridge thread), the client callbacks (UI) and the
  interceptor's log (IO) all post to one looper. On the web side, post every web -> main event to its main looper and
  send from there. Then the binder driver's per-node `oneway` order holds end to end. The main side posts on arrival.
- **L4 - §4.2, `call(name, argsJson)`.** Give each name its argument types: `token(Int, String?)`, `seek(Long)`,
  `volume(Double)`, `connect(String)`, the rest none. Decode to exactly those before `SpotifyPage.call`, never from
  generic JSON numbers. Test that the token appears in no log line on either side.
- **L5 - §4.2, the AIDL's place.** AIDL lives in `src/main/aidl/me/vibb/music/spotify/web/`, not under `M/`. Keep it
  out of `me/vibb/bridge/`, which `BridgeTest`/`MusicPlanTest` compare with the launcher.
- **L6 - §6, `RemoteSpotifyHostTest` "with a fake binder pair".** The JVM tests run with `isReturnDefaultValues =
  true` and no Robolectric, so a Binder can't transact. Keep the binder glue a thin adapter over a Kotlin interface
  and test the logic through that interface (the H1 state machine, M4's queue, L3/L4).
- **L7 - §4.3 R2 in a real outage.** The retry's page waits up to `SPOTIFY_READY_MS` (20 s) before UNREACHABLE. In
  a true outage the kid waits about 30 s more for "Trenger internett". Cap the retry's ready wait (for example 8 s)
  or skip R2 when the default network lacks `VALIDATED`.
- **L8 - §4.2, the renderer dying inside `:web`.** On `onRenderProcessGone` the web side should end its process too
  (one page per process). The main process then sees one `Gone`: the event or the death, not both.
- **L9 - §7, what needs the real SDK.** M4 (start, pause, a call, the fade, the idle close leaving no `:web`) and M6
  (the clear) test the process, not Spotify. They can run on our emulator with `fake-spotify.py` (§6l loop A) and no
  Premium account; only M1-M3 need the user's. Also add to M2: the parent's Spotify device picker shows one "Vibb"
  after a recycle, not two.

## Questions for the user

**ASK 2 (from M1): try the launcher's order first?** Leaving "Bare Vibb", the launcher would put the filter's tunnel
in place first and turn lockdown off last. Then every app's Chromium hears the change and resets its connections.
That is a few lines in the launcher and fail-closed, and it may fix the bug for Vibb music without the separate
process.

**Recommendation:** build the launcher change in any case, since it helps every app on the phone. Run M0 (the
change plus music rc.4, Bare Vibb and back, play). If music recovers, ship that first and keep 120's process split
(fixed for H1-H2, M2-M6) as the next step for triggers we haven't seen. If it doesn't recover, build 120 as fixed
here.

ASK 1 stands as answered (yes). Per H2, the wait it avoids is up to 30 s, not 10 s.

[bga]: https://developer.android.com/about/versions/17/changes/bg-audio
