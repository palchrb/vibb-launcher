# Spotify engine spike: the throwaway APK and its checklist

Design 26 phase 3, first step. It decides whether Spotify can be a hidden "engine" on the kid's kiosk phone
(design 20 §4, `docs/design/vibb-spotify-options.md` option A), and also whether the phone needs no Spotify
app at all (item 10, the Web Playback SDK in a WebView). Nothing else in phase 3 starts before these answers.

- Branch `spotify-spike`. The app is `spike/spotify/` (package `me.vibb.spotifyspike`, label "Vibb engine
  spike"); the manual workflow is `.github/workflows/spotify-spike.yml`.
- **The user's hard rule** (design 20 §4 "Never shown"): the kid never sees the Spotify app. Items 1-3 are kill
  criteria: if App Remote can't connect, play and reconnect without a Spotify screen, the App Remote source is
  dropped (item 10 may still save a Spotify source).
- The spike is a debug APK. It runs on the emulator, and on the Jelly Star **only while the Jelly Star is a test
  device**: never on a phone a kid is using. The spike never talks to the handy server.

## 1. Terms (read 2026-10-12)

Sources: github.com/spotify/android-sdk at its last commit `5aa4d62` (2023-07-04, App Remote 0.8.0): `LICENSE`,
`NOTICE`, `README.md`, `app-remote-lib/README.md`; developer.spotify.com/terms (Developer Terms, version 10,
effective 15 May 2025); developer.spotify.com/policy (Developer Policy, effective 15 May 2025);
spotify.com/no/legal/end-user-agreement.

**The SDK's own licence is Apache-2.0, not a proprietary licence.** The repository's `LICENSE` is Apache 2.0
("Copyright 2015 Spotify AB"), the README's licence section says the same, and `NOTICE` says "The following
licenses apply to Spotify App Remote for Android and its dependencies in compiled form" (Apache-2.0 for App Remote,
Jackson and Gson). The AAR ships compiled only (no source in the repository). Apache-2.0 is compatible with
GPL-3.0. But the README adds: "by using Spotify developer tools, you accept our Developer Terms of Use", and those
terms restrict copying the SDK:

- Developer Terms §II: "Spotify Platform" means "our developer tools accessible (e.g. APIs, SDKs, Widgets)".
- §III.1(b), the licence: "Distribute the Spotify Platform only in binary form as part of an SDA, make a reasonable
  number of copies of the Spotify Platform, and use the Spotify Platform ... as necessary for the sole purpose of
  operating the SDA." (SDA = "any application, website or service that ... incorporates, the Spotify Platform".)
- §IV.2(a), misuse: "distributing or making copies of the Spotify Platform or making the Spotify Platform available
  to any person, except as expressly provided in these Developer Terms".

Verdict:

| Question | Answer |
|---|---|
| (a) Commit the AAR to this GPL repo? | **No.** The Apache licence alone would allow it, but the Developer Terms we accept by using it allow distribution only "in binary form as part of an SDA" (§III.1(b)); a copy in a repository is the SDK made "available to any person" outside an app (§IV.2(a)). `spike/spotify/.gitignore` ignores `*.aar`. |
| (b) Download it at build time? | **Yes.** `fetchAppRemoteAar` downloads `app-remote-lib/spotify-app-remote-release-0.8.0.aar` from Spotify's own repository (raw.githubusercontent.com, pinned to commit `5aa4d62`) and checks SHA-256 `b5a6dd88...4c198` into the gitignored `app/libs/`. The APK that contains it is an SDA, which §III.1(a)/(b) allow "for private personal use". Without network the build stops and names the file to drop there. |
| The auth library | `com.spotify.android:auth` (Maven Central, 5.0.0 of 2026-07-10; github.com/spotify/android-auth, Apache-2.0, open source). **The spike doesn't use it**: App Remote's built-in authorization (`showAuthView`) and the in-app PKCE flow of item 10 need no library. The same Developer Terms apply if Vibb ever uses it. |
| The Web Playback SDK (item 10) | Loaded at run time from `https://sdk.scdn.co/spotify-player.js` into the WebView, never copied into the repository or the APK. |
| Kids, under 13 | The Developer Terms have no age clause. The Developer Policy §III ("Some prohibited applications"): "Do not build products and services which are targeted to children. If you have actual or suspected knowledge that specific people using your SDA or online service are children, you must block the use of the Spotify Platform for those people." That is the risk the user accepted on 2026-10-06 (private dev-mode use; the sanction is a revoked client ID). Separately, Spotify's own Norwegian user terms: "være minst 13 år gammel ... ha samtykke fra foreldre eller foresatte dersom du er mindreårig" - a kid under 13 can't hold an ordinary account (managed accounts aren't offered in Norway, `vibb-spotify-options.md`). |
| Replicating Spotify's player | Policy §III: "Do not build products or services that mimic, or replicate or attempt to replace a core user experience of Spotify ... Your product or service must add independent value." Vibb's curated kid UI is the "independent value", but a Spotify player screen is close to the line. |
| New finding | Policy §III also says "Do not create any product or service which is integrated with streams or content from another service." Vibb plays NRK, Storytel and own files in the same player as Spotify. Not in `vibb-spotify-options.md`; the same accepted-risk class as the children bullet. |
| Also binding | Policy §IV: streaming only for Premium subscribers (Terms §II: "Streaming" includes "using the Spotify Platform to control a background Spotify application" - App Remote is streaming). Policy §II: Spotify attribution, and "no playback of Spotify Content without showing relevant cover art and metadata". Policy §VI: an app name must not begin with "Spot" (hence "Vibb engine spike"). Terms §V: a way to disconnect the account, an end-user agreement and a privacy policy (here: the parent, private use). |
| GPL note for a public Vibb | Not for the spike (never distributed). A public GPL-3.0 Vibb that bundles the binary-only AAR would need a linking permission (GPL-3.0 §7) from its authors, and the Developer Terms' distribution limit would still apply. Design 20 already ships the Spotify source off and unbranded in public builds. |

## 2. What the spike does

One screen (`MainActivity`) and the Web Playback screen. Everything goes into one log.

- **Client ID** (a password field; saved in SharedPreferences, backup off, never logged) and the fixed **redirect URI**
  `vibb-spotify-spike://callback`, shown for the dashboard.
- **Setup: authorize** - `connect(showAuthView = true)`: may open Spotify's consent screen. Parent only, once.
- **Connect** - `connect(showAuthView = false)`: the kid-mode connect. **Reconnect after force-stop** - the same, logged
  as the kill test's reconnect. **Disconnect**. **Auto-reconnect** (checkbox): after a lost connection, retries with
  `showAuthView = false` after 5 s, 15 s, 60 s and 5 min.
- **Play URI**, **Skip to N**, **Seek**, **Pause**, **Resume**, **Capabilities** (`canPlayOnDemand`), and the two resume
  sequences of item 4: **Resume A** = `play(uri)` -> `skipToIndex(uri, N)` -> `seekTo(ms)`, **Resume B** =
  `skipToIndex(uri, N)` -> `seekTo(ms)`, each step after the previous one answered.
- **Player state**: `subscribeToPlayerState` and `subscribeToPlayerContext` live (track, position, paused, context,
  time of the last update and its age), and a `getPlayerState` poll every 60 s while connected, so a long run reads
  from the log.
- **Keep alive** (checkbox): a `mediaPlayback` foreground service that keeps the process alive like Vibb's playback
  service would. Without it the spike is an ordinary background app (Android may freeze it). The same service also
  runs, without the checkbox, whenever a Web Playback player is loaded (it owns that player), and stops when neither
  needs it.
- **Probes**: **Spotify package** (installed / HIDDEN / SUSPENDED / stopped, also logged at every connect);
  **Media sessions** (the active sessions in the system's order, each session's state and where a tap on its card
  lands: `tap-><creator package> (activity=true)`); **Pause Spotify's session** (what handy's bedtime rule would do:
  `transportControls.pause()` on Spotify's `MediaController`). Session changes and Spotify's notifications (id,
  channel, flags, media session, tap target - never their text) are logged while the probe has notification-listener
  access.
- **Log**: timestamped lines in logcat (tag `SpotifySpike`), on screen (newest first) and in
  `/sdcard/Android/data/me.vibb.spotifyspike/files/spike-log.txt`; **Copy log** puts the last 3000 lines on the
  clipboard. The file is opened and closed for every line; a line that can't be written waits and goes out with the
  next one (one logcat warning per failure streak). After three failed writes in a row, or without an external-files
  dir, the log moves to the internal files dir for the rest of the run
  (`adb exec-out run-as me.vibb.spotifyspike cat files/spike-log.txt`). Both screens show
  `log file: <path>, <n> lines, last write <time>` (and `WRITES FAILING ...` while they do), so a dead file log is
  visible at once. Also logged: process start, the activity's lifecycle and **window focus** (a `window focus LOST` right
  after a kid-mode connect means something came up over the spike), screen on/off, network changes.
- **Web Playback probe** (item 10): the player is a WebView made with the application context and **owned by the
  keep-alive service**, like Vibb's playback service owns its player. **Load player** starts the service; the probe
  screen only shows the WebView while it is visible (attached in onStart, detached in onStop, never destroyed), so the
  player keeps playing after Back, the screen's destruction, the screen going off and the launcher's lock - only
  **Unload** (or the service stopping) destroys it. The option **Also hold a partial wake lock** (default off) makes
  the service hold one (log `wake lock: held` / `released`). Both screens show
  `web player: ALIVE since <time>, on screen | headless (no screen attached), keep-alive running` or `web player: none`,
  and the log notes every attach, detach and destroy. While a player is loaded, a line a minute gives the app's
  network bytes (`net: rx=<MB> tx=<MB> (delta +<MB>/+<MB> in <n> min, playing: ~<n> MB/hour)`, TrafficStats for the
  spike's uid - the WebView's network stack runs in the app's process) and `webview cache: ...` (the WebView's disk
  cache, `cache/WebView` and `app_webview`, measured off the main thread); the probe screen shows the latest totals.
  Details in §7, items 10 and 10b.

The app can't see another app's activities, so whether Spotify showed anything is read from the system
(§5, "Watching for Spotify").

## 3. Building

Local (JDK 17+, Android SDK; `spike/spotify/local.properties` with `sdk.dir=...`, gitignored):

```sh
cd spike/spotify
./gradlew assembleDebug lintDebug -PwarningsAsErrors=true   # -> app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads the App Remote AAR into `app/libs/` (see §1). Offline it fails with the URL, the folder and
the SHA-256 to check by hand. The APK is a debug build with package `me.vibb.spotifyspike` (no `.debug` suffix:
Spotify's dashboard pins the package).

**The SHA-1 for the dashboard.** By default the APK is signed with the machine's debug key:

```sh
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
# or from the APK itself:
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk | grep SHA-1
```

To sign with a key of your own (the one CI uses, so local and CI builds share a SHA-1), put it in
`~/.gradle/gradle.properties` (never in the repository): `spike.keystore.file=/path/spike.p12`,
`spike.keystore.password=...` (PKCS12, alias `spike`), or set `SPOTIFY_SPIKE_KEYSTORE_FILE` /
`SPOTIFY_SPIKE_KEYSTORE_PASSWORD`.

**CI**: Actions -> "Spotify spike" -> Run workflow (any branch, e.g. `spotify-spike`). The artifact
`vibb-engine-spike-<sha>` is the APK; the job summary prints the signing SHA-1. Signing (the dashboard pins the SHA-1,
so the key must not change):

1. Set the repository secret `SPOTIFY_SPIKE_KEYSTORE_PASSWORD` (a long random string) **first**.
2. Run the workflow: it makes a new keystore protected by that password and uploads it as the artifact
   `spotify-spike-keystore` (kept 1 day). Download it, `base64 -w0 spike-keystore.p12`, store the output as the secret
   `SPOTIFY_SPIKE_KEYSTORE_B64`, delete the downloaded file.
3. Every later run signs with that key; the summary's SHA-1 stays the same.

Without any secret the job signs with a throwaway key that is never uploaded (its SHA-1 changes every run). The
keystore artifact is only ever made with the password secret, because a public repository's artifacts can be
downloaded by any signed-in GitHub user. The spike's key is never the launcher's release key.

## 4. Preparation (the user, before the first run)

**Spotify developer dashboard** (developer.spotify.com/dashboard, logged in as the parent; the app's owner needs
Premium):
1. Create app: name "Vibb engine spike" (not starting with "Spot", Policy §VI), any description, website empty.
2. Redirect URI: exactly `vibb-spotify-spike://callback`.
3. APIs used: **Android**, **Web API** and **Web Playback SDK** (the last two for item 10).
4. Android package: `me.vibb.spotifyspike` with the SHA-1 from §3. Simplest is one key for every build (the CI key,
   also used locally per §3); a second fingerprint needs a second package entry, if the dashboard accepts one.
5. The app stays in **Development mode**. User Management: add the **kid's Spotify account** (name and the account's
   e-mail); at most 5 users. Whether this list gates App Remote's consent is one of the answers (item 1).
6. Copy the **Client ID** (not the secret; the spike needs no client secret). Keep it out of chats, logs and the
   repository.

**Accounts and content**: the kid's account is a **Premium Family** member (on-demand play, downloads; App Remote
streaming is Premium only). The parent owns two test playlists (10+ tracks each), the kid follows them; one is set
to Download on the test device during setup (item 3). Write the URIs down (`spotify:playlist:...`; Share -> Copy link,
then the id after `/playlist/`).

**Today's launcher has no "engine" state** (design 20 §4 is phase 3 work). `computeEnforcementPlan` hides **and**
suspends every package that isn't allowed, and pins every allowed one. So "installed, not hidden, not suspended, not
pinned" can't be set today, and the spike uses these configurations instead (each check says which):

| Config | How | Spotify is |
|---|---|---|
| **K0** unmanaged | the emulator before enrolling (or the override/pause) | free; baseline |
| **K-** not allowed | Spotify not ticked in the device page's Apps card | **hidden + suspended**: App Remote is expected to fail (`CouldNotFindSpotifyApp`) - proves the engine state is needed |
| **K1** allowed | Spotify ticked Allowed | not hidden, not suspended, **but pinned and on Home**: any Spotify activity start succeeds visibly, so the checks watch for *any* start (a start = fail, blocked or not) |
| **K2** time rule | K1 plus a custom time rule active now whose only exempt app is the spike; open the spike from the lock screen's usable apps or with `adb shell am start -n me.vibb.spotifyspike/.MainActivity` | **suspended, not hidden, not pinned** (the lock's usable apps are pinned, the rest suspended): mitigation (a) of item 6 through the device owner's real path |

The kiosk app block ("Play and kiosk": block on) stays on throughout, as on the kid's phone.

**Test device** (emulator per §5, then the Jelly Star per §6):
1. Spotify from Play (on an enrolled phone: Settings -> "Install from Play" with the PIN, or before enrolling),
   logged in with the **kid's** account. On K0/K1 Spotify's UI is reachable for this setup step; this is what design
   20's PIN setup mode will do.
2. The spike: `adb install -r app-debug.apk` (device page: "Block USB debugging" off), then tick **Vibb engine spike**
   Allowed in the device page's Apps card (it is hidden until then, `enforceOnNewPackage`). Alternatively upload it
   as a manual catalog app (Apps -> new app, source "manual") and select it for the phone (that also allows it).
3. Spotify **allowed (K1)**, not on the kid's phone in use, never pinned outside these tests. Allowed puts it on Home
   - that's an artefact of today's launcher, not part of what is tested.
4. The media probe's access (also on the Jelly Star: the device owner doesn't grant it):
   `adb shell cmd notification allow_listener me.vibb.spotifyspike/me.vibb.spotifyspike.SpikeNotificationListener`
5. In the spike: paste the Client ID, the playlist URI, Save. Then **Setup: authorize**, accept the
   `app-remote-control` consent on Spotify's screen, and check the log says `connected`. **Disconnect.** Setup is done;
   from here on only `Connect` (showAuthView = false) is used.

## 5. The emulator first

A Pixel AVD with a **Google Play** system image (Play Store; `docs/testing/emulator.md` §6b), Android 14+ (16 like the
other tests), the debug launcher enrolled per `emulator.md` §1-§4 (its order for a Google account: wipe ->
`dpm set-device-owner` -> Google account and Spotify from Play -> enroll). Take a snapshot "spike-ready" after §4
step 5. If Play says Spotify isn't compatible with the image, go straight to the Jelly Star.

Runs on the emulator: items 1, 2 (with forced Doze), 3 (reboot; offline via airplane mode or `svc`), 4, 5, 6 (both
mitigations, and 6c with the debug override), 7 (volume, and DNS through the emulator's `-tcpdump`), 8, 9 (with the
debug music app for Vibb's card), 10 (WebView Widevine L3 on the emulator). The Jelly Star repeats everything: real
OEM battery management, real Doze, mobile data, Bluetooth, and its WebView/Widevine.

**Watching for Spotify.** Start this capture before every kill-criterion step and keep it running:

```sh
adb logcat -c
adb logcat -v time | grep -E --line-buffered "SpotifySpike|START u0|com\.spotify|BlockedAppActivity|LockTask" | tee spike-logcat-itemN.txt
```

A Spotify screen shows up as `ActivityTaskManager: START u0 {... cmp=com.spotify.music/...}` (or a start that lands on
`BlockedAppActivity`). Right after a step: `adb shell dumpsys activity activities | grep -iE "spotify|BlockedApp"`
(nothing = pass) and `adb logcat -b events -d | grep -iE "wm_(create|resume)_activity.*spotify"`. The spike's own
log shows `window focus LOST` if anything covered it. Screenshots: `adb exec-out screencap -p > itemN.png`.

## 6. The Jelly Star

Only while it is a test device (not the phone a kid is using), with the **release** launcher as today (no debug
launcher on a real phone), enrolled to the test server, USB debugging allowed on the device page for the duration.
The same preparation (§4). Remove the spike, its listener access and Spotify (or put the phone back to its earlier
state) before the phone goes back to a kid. Item 6c (shade off) can't be set with the release launcher; it's
emulator-only.

## 7. The checklist

Items 1-7 are `vibb-spotify-options.md`'s "Spike for A", items 8-9 are new, 10 is the Web Playback alternative.
Record for each: config (K0/K1/K2/K-), device, PASS/FAIL, and the log lines that show it.

### Item 0 - the not-allowed baseline (2 min)
K-: untick Spotify, wait for the sync. Spike: **Spotify package** -> `HIDDEN`. **Connect** -> expected
`connect FAILED ... CouldNotFindSpotifyApp`. Tick Spotify again (K1). This records why handy needs the engine state.

### Item 1 - connect without Spotify being shown (KILL CRITERION)
K1, consent given in setup (§4 step 5), capture running.
1. `adb shell am force-stop com.spotify.music` (cold Spotify). Spike in front: **Connect**.
2. Observe: `connected after N ms`; no `START u0 ... com.spotify`; no `window focus LOST` between the connect and
   `connected`; `dumpsys activity activities` shows no Spotify activity. **Play URI** -> music plays, the state view
   follows.
3. Repeat with Spotify warm (it played a minute ago), and once with the spike started from Home right after
   unlocking.
4. Revoked consent: spotify.com/account/apps (kid's account) -> remove "Vibb engine spike"; force-stop Spotify;
   **Connect**. Expected: `UserNotAuthorizedException` (or `AuthenticationFailedException`) **and no Spotify start**
   - this is design 20's "ask an adult" path. Then give consent again with **Setup: authorize**.
5. Dashboard user list: if possible, connect once with a Spotify account that is *not* on the 5-user list (another
   Family member) and record the error (answers "[unverified: list applies to App Remote consent]").

PASS: every kid-mode connect connects (or fails with an error) without any Spotify activity start. FAIL (kill): any
Spotify activity start, or no connect without one.

### Item 2 - background, screen off, 1 h; reconnect after a kill (KILL CRITERION)
K1. Two runs of 60 min each: **2a** Keep alive off, **2b** Keep alive on (Vibb will have its service).
1. **Connect**, **Play URI** (a long playlist; repeat on in Spotify if it's short), press Home, power button (screen
   off). Emulator Doze: `adb shell dumpsys deviceidle force-idle` (back: `adb shell dumpsys deviceidle unforce`).
2. After 60 min, wake and unlock; open the spike.
3. Observe: audio played the whole hour (listen at intervals, or note the track at the end vs the start); the log's
   `poll:` lines every minute with a growing position (2b; in 2a they may stop while the spike is frozen - record);
   `connection LOST` lines (should be none); whether **Pause**/**Resume** still work at once or need a reconnect.
4. **2c kill and reconnect, foreground**: while playing, `adb shell am force-stop com.spotify.music`. Expected log:
   `connection LOST ... SpotifyDisconnectedException` (or `...ConnectionTerminatedException`). **Reconnect after
   force-stop** -> `connected`, no Spotify start (capture), **Play URI** works.
5. **2d kill and reconnect, background**: Auto-reconnect and Keep alive on, playing, spike in the background, screen
   off; force-stop Spotify from adb. Expected: `auto-reconnect: attempt 1 in 5 s` -> `connected` - from the
   background, with no Spotify start. Also `adb shell am kill com.spotify.music` (a low-memory kill instead of a
   force-stop) and record the difference.

PASS: audio for 60 min with the screen off (at least in 2b), and reconnects in 2c and 2d without a Spotify activity.

### Item 3 - reboot online, then offline (KILL CRITERION for 3a)
1. **3a**: mobile data (Jelly Star) or the emulator's network on. Reboot (power menu restart or
   `adb shell svc power reboot`), unlock handy's lock, open the spike from Home **without opening Spotify**, capture
   running, **Connect**, **Play URI**. PASS (kill criterion): connected and playing with no Spotify activity since boot.
2. **3b downloaded, offline**: in setup (K1, Spotify's UI) set playlist 1 to Download, wait until it's downloaded,
   back to the spike. Connected: airplane mode on - `adb shell cmd connectivity airplane-mode enable` (if the device
   page's hardening "airplane mode" switch blocks it: `adb shell svc wifi disable; adb shell svc data disable`).
   **Play URI** playlist 1 -> plays? Playlist 2 (not downloaded) -> record the error/behaviour. **Resume A** on
   playlist 1 -> lands right?
3. **3c another context**: offline, play a track that was downloaded via playlist 1 from its *album* URI (put the
   album URI in the field) -> plays from the download? (design 20 §4 "[spike: downloaded tracks play offline from
   another context]")
4. **3d reboot offline**: airplane mode on, reboot, **Connect** -> expected: fails (record the exception: App Remote
   needs the network to connect). Then airplane mode off -> **Connect** works, still no Spotify start.
5. **3e background download** (design 26 offline decision): the parent adds two new tracks to playlist 1 (desktop or
   web). On Wi-Fi, the spike connected and playing something else, Spotify never shown, wait 30 min; then airplane mode
   and **Skip to N** to a new track. Does it play (downloaded in the background), and after how long?

### Item 4 - resume accuracy
K1, connected, playlist 1, N = 4, seek = 95000.
1. Spotify paused on another context. **Resume A**: listen for a blip of track 0 before track 4; the log has each
   step's `ok (N ms)` and the `state:` lines in between.
2. Same with **Resume B**. Then both again with Spotify cold (force-stop, **Reconnect after force-stop**, then the
   resume), and once with shuffle on in Spotify (the `state:` line shows `shuffle=true`: does index N still mean the
   playlist's Nth track?).

PASS: the final `state:` shows playlist 1's track at index 4 and a position within 2 s of 95 s, with no audible blip.
Record which variant is silent and the total time from tap to the final state.

### Item 5 - notification and media-control taps; promo notifications
1. K1, playing: open the shade, **Media sessions** in the spike, and note Spotify's `tap->com.spotify.music
   (activity=true)` and its notification lines (`Spotify notification posted: ... flags=[ongoing, fgs] media=true`).
   Tap the Spotify media card: with K1 it opens Spotify (expected - Spotify is pinned today). The probe line is the
   answer: the tap target is a Spotify **activity**, so with the engine state (unpinned, block on) it becomes
   `BlockedAppActivity` ("App is not available") like every unpinned start (`emulator.md` §6 item 8).
2. K2 (Spotify suspended, unpinned), if it still plays (item 6b): tap the card -> the system's "app paused"
   dialog or BlockedAppActivity? Screenshot.
3. Design 20 layer 3 (no promo notifications): `adb shell pm revoke com.spotify.music android.permission.POST_NOTIFICATIONS`,
   play again: does the media card still show (media-session notifications are exempt), does playback go on, do other
   Spotify notifications stop? (The step 11 cancel rule only touches packages that aren't allowed, so it can't be
   tested on K1.)

### Item 6 - the bedtime rule: pause, or suspend
1. **6a pause via the session** (handy's plan): K1, playing, spike in front or background: **Pause Spotify's session**
   -> audio stops, App Remote reports `paused=true`. Then can the kid resume it? From the shade's media card (yes,
   expected) - that's why 6b/6c matter.
2. **6b suspend while playing** (mitigation (a)): K1, playing via App Remote: `adb shell pm suspend com.spotify.music`.
   Observe: does audio continue? Does App Remote stay connected (no `connection LOST`), do **Pause**/**Play URI** still
   work, does a fresh **Connect** work while suspended? Does the media card disappear from the shade (**Media
   sessions**: is Spotify's session still listed)? `adb shell pm unsuspend com.spotify.music` afterwards. Then the
   device owner's path: switch to **K2** (a custom time rule now, exempt only the spike) while Spotify plays and
   record the same (DPM suspension may differ from the shell's; the launcher's `pauseBeforeSuspend` only pauses
   music packages, so Spotify isn't paused first).
3. **6c keep the shade off** (mitigation (b), emulator only, debug launcher):
   `adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.lock.LockTaskOverrideReceiver --ei clear_features 2`
   (NOTIFICATIONS off; `emulator.md` §6e), reset with `--ez reset true`. Observe: no shade, so no media card and no
   active-apps list; anything else of Spotify's reachable? Note: this removes a stock gesture from the kid's phone -
   a decision the user makes, not the spike.

PASS for 6b: audio stops on suspension, or keeps playing *and* App Remote keeps working while the card is gone (then
suspension is the engine's "never shown" layer). Record exactly which.

### Item 7 - volume cap and DNS hosts
1. Volume: the device page's music card -> volume cap 60 %. Play via App Remote, press volume up to the top. The cap
   holds: `adb shell dumpsys audio | grep -A4 "STREAM_MUSIC"` (the index stays at the cap). Jelly Star: also with a
   Bluetooth headset (absolute volume).
2. DNS: the device page's DNS filter with the kid's categories on, and the blocked-domain log on (DNS log page). Play
   playlists, cover art, a downloaded one, skip around for 10 min. Record every blocked domain that looks like Spotify
   (`spotify`, `scdn`, `spotifycdn`, `pscdn`, `akamaized`, `fastly`, `googleapis` ...) and whether playback broke.
   Emulator, all hosts: start it with `emulator -avd <name> -tcpdump spike.pcap`, then
   `tshark -r spike.pcap -Y "dns.flags.response == 0" -T fields -e dns.qry.name | sort | uniq -c | sort -rn`.

### Item 8 - the "active apps" list (Android 13+ FGS task manager)
K1, unlocked, kiosk on (the plan's shade is on today), Spotify playing via App Remote, Keep alive on and off.
Pull the shade fully down; the footer "N active apps" (or the quick settings' button) lists apps with a foreground
service. Observe: is **Spotify** listed with **Stop**? Is the spike listed (its keep-alive service - Vibb's own would
be too)? Tap Stop on Spotify: does playback stop, what does the spike log (`connection LOST`?), does any Spotify screen
open? Screenshot. PASS: Spotify isn't listed, or Stop only stops playback and nothing of Spotify's UI opens (then it
is a "kid can stop the music" question, not a "never shown" breach).

### Item 9 - the system media controls
1. Vibb's card next to Spotify's: install the debug music app and play something with the debug feed
   (`emulator.md` §6f), pause it, then play via the spike. Pull the shade: are there two media cards (Vibb's and
   Spotify's) in the quick-settings carousel? Which is first? **Media sessions** lists the order.
2. Tap each part of Spotify's card: the body (where does it land? K1: Spotify; the probe's `tap->` line tells what
   an unpinned Spotify would do), play/pause/next (work without opening anything?), the output switcher, a long press
   (its gear opens Android's media settings: BlockedAppActivity, Settings isn't pinned).
3. handy's PIN lock LOCKED: no shade, no keyguard, so no card should be reachable - confirm. Record whether the
   Jelly Star shows media controls anywhere else (always-on display, side key).

PASS: no path from a card to a Spotify screen except those that the engine state turns into BlockedAppActivity.

### Item 10 - Spotify's Web Playback SDK inside the spike's WebView (no Spotify app needed)
If this works, the phone needs no Spotify app at all - "never shown" by construction.

**DRM facts** (from the coordinator's research for this step, not re-checked here; the probe is the check):
Widevine L3 (software) is enough for Spotify's audio. Spotify's own web player officially supports Chrome 87+,
Samsung Internet 17+ and Firefox 115+ on Android; the Web Playback SDK on Android Chrome is reported working once
Widevine L3 is available on the device. A WebView is the same Chromium engine with Widevine, and the usual missing
piece is the app granting the page's protected-media permission (the default `WebChromeClient` denies it, which shows
as "EMEError: No supported keysystem"). There is no published success with Spotify's SDK in a WebView, and Spotify
lists the SDK for desktop browsers only - hence the probe. The spike's page runs under an https origin
(`https://appassets.androidplatform.net/`, a secure context), grants only `RESOURCE_PROTECTED_MEDIA_ID`, allows media
without a user gesture, keeps the renderer important while no screen shows it, and first probes
`requestMediaKeySystemAccess('com.widevine.alpha')` on its own.

Setup: the dashboard app has **Web API** and **Web Playback SDK** checked (§4). Item 10 can run in **K-** (Spotify's
app hidden) - that is the point.
1. Main screen: Save the client ID and a playlist URI. **Web Playback probe**.
2. Token: **Get token (PKCE)** opens Spotify's login full-screen in an in-app WebView (Back cancels it; log in with the kid's account by e-mail and
   password; Google/Facebook/Apple sign-in may refuse a WebView) and fills the token field after the redirect - scopes
   `streaming user-read-email user-read-private user-modify-playback-state user-read-playback-state`. Or paste a token
   with those scopes obtained elsewhere. The token is never logged (it is scrubbed from every log line).
3. **Load player** with the default WebView user agent. Log lines to look for: `secure context=true, EME=function`;
   `EME probe: com.widevine.alpha OK` or `FAILED <name>: <message>`; `permission request ... -> granting
   [android.webkit.resource.PROTECTED_MEDIA_ID]`; `SDK script ready`; then `ready: device_id ...` or one of
   `initialization_error` / `authentication_error` / `account_error` / `playback_error` with Spotify's message.
4. **Play on this device** (Web API `PUT /v1/me/player/play?device_id=...` with the playlist): expected `HTTP 204`, then
   `player_state_changed: paused=false ...` and audio. **Pause/resume**, **State**.
5. Tick **Desktop user agent**, **Load player** again, repeat 3-4. Record both results (the log names the UA used).
6. Screen off: playing (the keep-alive service runs since **Load player**), leave the probe screen with **Back** (the
   status line on the main screen says `web player: ALIVE ... headless`), power button; 30 min (emulator: force Doze
   as in item 2). The page logs a `poll:` line a minute. Then wake, unlock handy's lock and check that audio never
   stopped. **Unload** ends the player.
   **Jelly Star, real Doze, twice:** run A with **Also hold a partial wake lock** off, run B with it on (the log says
   `wake lock: held`), each 60+ min with the screen off and the phone still (no charger: Doze needs it unplugged).
   Compare the `poll:` lines in `adb logcat -s SpotifySpike` (and the file log): one a minute, or gaps? Gaps in A but
   not in B while audio plays on = Vibb's web player needs a partial wake lock while playing; no gaps in A = it
   doesn't. Note battery % at start and end of each run.
7. Item 7's DNS log for the web path (expected: `sdk.scdn.co`, `api.spotify.com`, `accounts.spotify.com`, Spotify's
   CDNs and the Widevine licence host) - record what was blocked.

PASS: `ready` fires and audio plays, and audio continues for 30 min with the screen off. FAIL: record the exact
message of any `EMEError`, `initialization_error` or `account_error`, the EME probe's line, and the WebView version
(`adb shell dumpsys webviewupdate | grep -i "current webview"`).

Trade-offs if it passes (for the decision, not the spike): streaming only - no offline at all (the SDK has no
downloads); Premium only; every command (play, skip, seek) is a Web API call over the network under development-mode
limits (5 users, rate limits, 6-month refresh tokens for the server's token); the token must be refreshed (a
server-side job or PKCE refresh on the phone); and Spotify can break it at any time since mobile isn't supported.

### Item 10b - does the web player cache audio? (mobile data)
The spike's minute lines: `net: rx=... (delta +rx/+tx in 1.0 min, playing: ~N MB/hour)` and `webview cache: ...`.
Wi-Fi or mobile data, it doesn't matter (the counter is per app, not per network).
1. **Load player**, **Play on this device** with a playlist. Note `~N MB/hour` from three or four minutes of playing:
   the streaming rate on this device (Spotify's web quality; compare with the App Remote path's Spotify data use in
   Android's Settings -> Network -> Data usage, if items 1-9 ran).
2. **Play on this device** again so the playlist starts from its first track, and let that track play to its end;
   note the `rx` total at the start and at the end of that track, and the `webview cache` size.
3. Play the **same** track again from its start: **Play on this device** with the same context once more (it restarts
   at the first track), and let it play through. Note the rx delta again.
4. Compare: a second-run delta near zero (only the licence and API calls, tens of KB) = the WebView served the audio
   from its cache; about the same as the first run = no audio caching. Also note whether `webview cache` grew by
   about the track's size during step 2.

What a cache can and can't do: it saves data when the same tracks repeat (a kid's favourites), but it is **not
offline**. Each playback session still needs Spotify's Widevine licence and the Connect session from the network, so
in airplane mode the web player fails even for cached tracks (check it: airplane mode on, **Play on this device** ->
record the error). Offline play stays the App Remote path's (Spotify's downloads, item 3) or own files'.

## 7b. Results so far (the user's runs, recorded by the main session)

**2026-10-12/13, emulator `jellystar` (Play image, Android System WebView 153.0.8010.57), the debug launcher with the
kiosk on, the spike ticked Allowed.**

- **Items 1-9 (App Remote): not run.** Spotify's app refuses the login on the emulator: password login answers
  "wrong username or password" (the same credentials work on open.spotify.com in Chrome on the same device, so it is
  the app's device check, not the account), and the e-mail-code flow reaches the code screen and then opens a Google
  Play services activity, which lock task blocks ("App is not available - Google Play services is not available right
  now"). Two findings for the design: a real "Spotify setup mode" must pin `com.google.android.gms` with Spotify while
  the login runs, and items 1-9 need the Jelly Star.
- **Item 10 (Web Playback SDK in the spike's WebView): PASS on the emulator, forced Doze included.**
  `Get token (PKCE)` logged in with e-mail and password in the full-screen WebView; `Load player` (default user agent)
  reached `ready` with a device id; `Play on this device` with a playlist the user owns played audio; pause/resume
  worked.
  - **Screen off, forced Doze:** with Keep alive on, the screen off and `adb shell dumpsys deviceidle force-idle`,
    audio continued across track changes, and the SDK's JavaScript kept running: logcat has the page's `poll:` line
    every minute and the `player_state_changed` events throughout.
  - **The night (about 10 h, screen off, the app in the background):** the player was **paused** - the silence
    21:58-05:13 is a paused player, not a frozen one; the file log was alive then (its `poll:` lines are there). On
    waking, play/pause on the probe screen resumed at once: the SDK's Connect session survived the night.
  - **The file log died, the player didn't:** in the forced-Doze run the spike's log file stopped at 07:16 while
    logcat kept every line through 07:46 and later. The "JS frozen with the screen off" worry came from that gap; it
    was the spike's file writer (an exception swallowed on every line), fixed since: the file is reopened per line,
    a failed line is retried with the next, failures go to logcat, and the screens show the file's state (§2).
  - **Token refresh:** `Load player` after the night failed because the pasted access token had expired (a rebuilt
    player needs a valid token); `Get token (PKCE)` + `Load player` played again without a new password. Consequence
    for a real build: refresh the access token (PKCE refresh token) before each hour ends; a running player doesn't
    need it, a rebuilt one does.
- **Still to run for item 10:** the desktop-user-agent variant, the DNS log (item 7's host list for the web path),
  and the same probe on the Jelly Star, where Doze is real. **Open question for the Jelly Star:** does the page's
  JavaScript keep running under real Doze without a wake lock? The keep-alive service holds **no wake lock** unless
  the probe's **Also hold a partial wake lock** option is on, so item 10 step 6 runs both and compares. Read it from
  `adb logcat -s SpotifySpike`: a `poll:` line every minute = yes. If the run without the wake lock shows gaps in the
  polls while audio continues, the design consequence is a partial wake lock held while the web player plays.
- **Spike change after the night run:** the emulator log showed `WebPlaybackActivity onStop (player keeps running)`
  then `onDestroy: player destroyed` after Back, and the music stopped - the WebView belonged to the activity. Since
  then the keep-alive service owns the player (§2), so Back and the lock no longer end it; only **Unload** does.

## 8. What to send back

1. The spike's log: `adb pull /sdcard/Android/data/me.vibb.spotifyspike/files/spike-log.txt` (or, if the screen's
   `log file:` line names the internal path, `adb exec-out run-as me.vibb.spotifyspike cat files/spike-log.txt >
   spike-log.txt`; or **Copy log** and paste), per device, plus `adb logcat -d -s SpotifySpike > spike-logcat.txt`
   (logcat has every line too), plus the `spike-logcat-item*.txt` captures and the screenshots.
2. PASS/FAIL per item 0-10 with the config used (K-/K0/K1/K2) and the device (emulator / Jelly Star), and for the
   kill criteria (1, 2, 3a) the log lines that prove no Spotify activity started.
3. The DNS hosts from items 7 and 10 (blocked ones from the DNS log; all queried ones from the emulator capture).
4. Item 10b: the web player's MB/hour, the two rx deltas of the repeated track and the cache sizes. Item 4: which
   resume variant is silent and accurate. Items 6/8/9: the screenshots of the media card, the active
   apps list and where each tap landed. Item 10: the EME probe line, the SDK's events and the WebView version.
5. The open questions from `vibb-spotify-options.md` (the decision of 2026-10-06 answered 1 and 2 - confirm or
   revise):
   1. Must Spotify be inside Vibb, or is own library + NRK/Storytel (+ plain Spotify app for an older kid, option C)
      enough? (Decided 2026-10-06: inside Vibb, option A.)
   2. Accept the Developer Policy III.8 risk (revoked client ID) for private use, with Spotify off in public Vibb
      builds? (Accepted 2026-10-06; §1 adds the "integrated with another service" bullet to the same risk.)
   3. Kids' Spotify accounts: Premium Family members (needed for on-demand play in A and for downloads)?
   4. How much offline matters (cabin weeks with no coverage?) given A only plays downloads after connecting online -
      and item 10's web path has no offline at all.
   5. OK for the parent to open Spotify's UI on the kid's phone in a PIN setup mode (login, consent, download toggles)?
   6. Willing to buy (Qobuz/Bandcamp/iTunes) or rip CDs for the kids' favourites? Shared library with the Pi box?
   7. Own-file transfer: share the launcher's tsnet proxy with Vibb, or let the launcher download and hand over files?
6. New from this spike: how old is each kid (Spotify's Norwegian terms require 13+ for an ordinary account), and is
   the shade's removal in kiosk (item 6c) acceptable if suspension doesn't work?
