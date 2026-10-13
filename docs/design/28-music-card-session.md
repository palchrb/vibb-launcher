# 28 - The music card follows a listening session; the Play Store in the drawer during a window (buildable spec)

Architect, 2026-10-13, on `main` at `fff8c287` (designs 25, 26a, 26b and 27 are in). Launcher only: **no music app
change, no AIDL change, no server change.** Sources: design 21 §3 and its step 4 status, design 25 §1.1 (the tile,
user decision 25b-G), design 07 §4 (Play), design 24b (the windows), the code named below.

## The request (user, 2026-10-13) and the decisions (final)

**A. The Home "Spilles nå" card and the lock's music tile.** Today the card is on Home whenever the session plays or
is paused, else from the last `setNowPlaying` snapshot. So once anything has played, it never goes away. The lock's
tile shows any live session, playing or paused (25b-G).
1. The card shows while music **plays**.
2. After a pause or stop it stays **10 minutes**, counted from when playback stopped, then hides. A paused but still
   live media session older than 10 minutes hides too.
3. When a headset connects (wired or Bluetooth audio; a BT speaker counts like any A2DP device), the card shows (the
   snapshot or the live paused session) for **10 minutes** from the connection, then hides unless playing started.
   Devices already connected at the launcher's process start or at boot are not a fresh connection.
4. The lock glance page's music tile follows **the same rule**.

**B. The Play Store in the drawer during the pause or the override.** `RestrictionsPause` and `OfflineOverride`
unsuspend the Play Store. Install mode then refuses with NOT_NEEDED, and `playPackageLaunchable` hides the store
everywhere except in install mode. So Play is open but can't be reached. Decision: while either window is open, the
Play Store (only the store, never Play services or GSF) is on Home and in the drawer.

## 0. In short

- One pure rule, `musicShownUntil`, in a new `music/MusicWindow.kt`, decides for both surfaces. It shows while
  playing, and otherwise until 10 minutes after the later of "last known playing" and "a fresh headset connection".
  Home's card and the lock's tile both read it.
- **"Last known playing"** is stamped by both feeds:
  - the bridge's `setNowPlaying`, which the music app already sends on every play/pause flip, every 15 s while playing,
    and paused from its `onDestroy`;
  - the token's death (`playerGone`);
  - the platform session, while a surface watches it.
- **The clock:** `elapsedRealtime` plus `BOOT_COUNT`, persisted. A launcher restart inside the 10 minutes brings the
  card back for the rest of the window. A reboot never does.
- **The headset:** `AudioManager.registerAudioDeviceCallback` (no permission) in `MusicMedia.init`. The devices present
  at registration are the baseline. A connection in the first 2 minutes after boot doesn't count either.
- **The re-render:** one delayed callback at the window's end, posted only while the surface is shown, paused and
  inside the window.
- **Part B:** `playPackageLaunchable(pkg, storeListable)` with `storeListable = install mode || override || pause`,
  read in `play/` (`PlayRuntime.storeListable`). `apps/AppFilter.kt` changes one call. The drawer and Home already
  re-filter when Play is unsuspended or suspended.

## 1. Part A - behaviour

| Situation | Home card | Lock tile |
|---|---|---|
| The session plays (`sessionPlay` = PLAYING: also buffering, connecting, skipping) | live, ticking | live |
| Paused or stopped, at most 10 min ago | the live session paused, else the snapshot | the live session paused, else none |
| Paused or stopped more than 10 min ago, no fresh headset | none | none |
| A headset connected at most 10 min ago | the live session paused, else the snapshot | the live session paused, else none |
| The launcher restarted (crash, self-update) inside a window | back for the rest of the window | same |
| After a reboot | none until something plays or a headset connects (after the 2-min grace) | same |
| Queue's end (`setNowPlaying(null)`, session STOPPED) | none (as today: no snapshot) | none |
| No listener access, the music app suspended or gone, the PIN lock LOCKED | none (unchanged) | none (unchanged) |

- **One window, two contents.** The lock's tile has only the session's transport controls. A snapshot has no session,
  so its play would have to open the player over the lock, which `LockMediaGuardTest` forbids. So the lock shows a
  live session inside the window and never the snapshot, as today. That is "the same rule": the same
  `musicShownUntil` gate. It is not a new content source.
- **No fallback outside the window.** A live paused session outside the window hides the card. It never falls back to
  the snapshot.
- **Reconciling 21/25.**
  - Design 21 step 4's lock rule ("appears while playing, stays paused, none for a paused session at a lock-up") was
    already replaced by 25b-G (any live session, also at a lock-up).
  - This design replaces 25b-G's "paused, for as long as the session lives" with the window. A lock-up inside the
    window shows the paused session; one after the window shows nothing.
  - There is no PLAYING-only first-show rule any more. `glanceTile(session)` stays `session != null`; the session it
    gets is already windowed (§3).
  - `GlancePanel.resumeNow`'s `tileUp = false` at a lock-up is harmless (`renderNow` sets it at once). Leave it.
- **`EXTRA_RESUME` is unchanged.** The snapshot card (now only inside a window) opens the player with `EXTRA_RESUME`
  from both its tap and its button. After a headset connects, this is the "put the buds on, press play" path.
- **Live states.**
  - `sessionPlay` already maps BUFFERING, CONNECTING, FAST_FORWARDING/REWINDING and SKIPPING_* to PLAYING (the intent
    to play). Those count as playing: no window, the card ticks.
  - PAUSED is paused.
  - NONE, STOPPED and ERROR are not live. The card falls back to the snapshot inside the window. The stop is stamped
    when it was playing before.

## 2. Part A - the settled questions

### 2.1 Where "last known playing" comes from: both feeds, no AIDL change
- **The bridge is the primary feed. It works when no surface is shown.**
  - `PlaybackService.reportNowPlaying` (music) sends on every change of `(entry, item, playing)` and on the 15 s
    `saveTick` with `refresh = true`, for every renderer.
  - It sends paused (`forcePaused`) from `onDestroy`.
  - `playing` is `renderer.showsPlaying`: on a Sonos room that is fresh + reachable + ours + PLAYING.
  - `LiveBridge` re-sends the last state after a rebind (a launcher update or restart).
  - The token's death calls `MusicNowPlaying.playerGone`.
  - So the launcher sees every playing -> not-playing edge without being on screen, at worst 15 s late when the edge
    report itself was lost.
  - `MusicNowPlaying.store` and `playerGone` already know `wasPlaying` (`playing_at` in prefs) and the new `playing`.
- **The platform session is the second feed, while watched.** `MusicSessions` listens only while Home or the lock shows
  a surface. While it does, it stamps on its own transitions in `pick`, `update` and `onSessionDestroyed`.
  - This matters on Sonos. There the session can say PLAYING while `showsPlaying` is already false: §3.2 of 26a holds
    `playWhenReady` through the first failed polls.
  - The card shows "playing" from the session. When the session then flips to paused, its own stamp starts the
    10 minutes. The bridge's older stamp would have hidden the card at once.
- **The stamp rule (pure `playedStampAfter`).** An observation with `playing` sets the stamp to now. An observation with
  `!playing && wasPlaying` sets it to now (the end of play). Anything else keeps it.
  - So the stamp is "the last moment we knew it played". While playing, the surfaces show anyway.
- **Stale flags.** "Playing now" for the surfaces is the live session only, never the bridge's `playing_at`.
  - That flag can stay stale when both processes died while the launcher was down.
  - It still feeds only a stamp, so at worst it gives one 10-minute window.
  - Without listener access there are no surfaces at all (21 §3), so the session is always there when it matters.
- **What doesn't stamp.** The music app restoring itself paused ("Nothing starts by itself", playing=false after
  playing=false) never stamps, so its restart or a reboot shows no card.

### 2.2 The clock: elapsedRealtime + BOOT_COUNT, persisted
- `BootStamp(elapsedMs, bootCount)`: `SystemClock.elapsedRealtime()` and `BootClock.bootCount()`
  (`server/RestrictionsPause.kt`).
- **Not the wall clock.** The window is a duration. NTP corrections and time-zone changes must not stretch or cut it.
- **Not `timedWindowActive`.** Its wall-clock leg is there against tampering. Here it would only hide the card on a
  clock jump.
- A stamp counts only when its `bootCount` is today's (and not -1) and its `elapsedMs <= now`. Anything else is
  nothing, which fails closed to hidden.
- **Persisted** in `MusicStore.prefs` (CE, `music_bridge`), keys `listen_played_ms`, `listen_played_boot`,
  `listen_headset_ms`, `listen_headset_boot`.
  - Launcher process restart inside the window: the card comes back for the rest of it. A crash, a self-update or
    design 24b's relock restart is invisible to the kid, and the card mustn't blink away mid-window. The snapshot
    already survives restarts the same way.
  - Reboot: the boot count differs, so no card.
  - Every writer runs after the first unlock: the bridge, `MusicSessions` and the headset callback (`initRest`). So CE
    storage is there.

### 2.3 The headset: which devices, and what is "fresh"
- **`AudioManager.registerAudioDeviceCallback(callback, main)`.** It needs no permission, and `VolumeCap` already uses
  one. It goes in a new `MusicListening.init(app)`, called from `MusicMedia.init` **after `MusicCalls.init(app)`**
  (`MusicMediaPlanTest` pins `VolumeCap.init(app)\n            MusicCalls.init(app)` as adjacent lines).
  - It is not `ACTION_HEADSET_PLUG` (wired only) and not the BT profile broadcasts (`BLUETOOTH_CONNECT` permission,
    per profile).
- **Types (output sinks only, `isSink`)** are exactly the music app's own `OutputKind.BLUETOOTH`/`WIRED` mapping
  (`PlaybackService.kt`):
  - `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES` and `TYPE_USB_HEADSET`;
  - `TYPE_BLUETOOTH_A2DP` (buds, headsets, BT speakers);
  - `TYPE_BLE_HEADSET` and `TYPE_BLE_SPEAKER` (LE audio).
- **Not counted:**
  - `TYPE_BLUETOOTH_SCO`: calls only. A BT headset also brings A2DP, and an HFP-only car kit is not for listening.
  - `TYPE_BLE_BROADCAST`: an Auracast source, not a connection.
  - `TYPE_HEARING_AID`: worn all day and reconnects often. It would show the card again and again.
  - `TYPE_USB_DEVICE`/`_ACCESSORY`, HDMI/ARC/eARC, dock, line, bus, remote submix, built-ins, and every source.
- **Fresh connection (pure `headsetStep`).** The glue keeps `known: Set<Int>` of output ids (`AudioDeviceInfo.getId()`
  is per connection, so a reconnect is a new id).
  - At `init`, `known` is `getDevices(GET_DEVICES_OUTPUTS)` ids, read **before** registering. AOSP calls
    `onAudioDevicesAdded` once at registration with every current device. Those are all in `known`, so no stamp.
  - A device added between the read and the registration is a real connection and counts.
  - `onAudioDevicesAdded`: any counted type with an id not in `known` and `nowElapsed >= HEADSET_BOOT_GRACE_MS`
    stamps `headsetAt`. Every added id joins `known`.
  - `onAudioDevicesRemoved` drops the ids.
- **Boot.** `initRest` runs only after the first unlock, so devices connected before it are the baseline. Bonded BT
  devices reconnect on their own some seconds after the adapter comes up, which can be after our registration. So
  connections while `elapsedRealtime < 2 min` (`HEADSET_BOOT_GRACE_MS`) are baseline too. Buds already in the ears at
  boot give no card.
- **A launcher process restart** never stamps (baseline). An earlier persisted headset stamp still runs out its window
  (§2.2).

### 2.4 The re-render at the window's end: one delayed callback
- Both surfaces post **one** `Runnable` at `hideAtElapsedMs - now` (pure `musicRecheckInMs`). They post it only while
  shown, not playing and inside a window.
  - It is removed at every render (re-posted for the new end) and on stop or inactive.
  - While playing, the existing 1/s ticker runs instead and no expiry is posted.
- `Handler` delays use uptime. That only differs from elapsed time in deep sleep, and neither surface is active then:
  Home is stopped, and the lock's `stop()` runs at screen-off. The next `onStart` renders again from the clock.
- **Listeners.** `MusicListening` notifies (on main) only when a window may **open**: a fresh headset, or an
  end-of-play stamp (`wasPlaying && !playing`).
  - A playing stamp (every 15 s) notifies no one. The surfaces already show while playing, and a 15 s panel render
    would refit the lock's page.
  - Home's card and `MusicLockPanel` both add the listener between start and stop.
  - A stamp is written before the feed's own `changed()`, so that render already sees it.

### 2.5 Sonos and Storytel
- The music app has one `MediaSession` over whichever renderer plays (`KidPlayer(renderer.player)`; on a room the
  `SonosRenderer` `SimpleBasePlayer`). The launcher reads it as it reads the phone's (`MusicMediaPlanTest` already pins
  that nothing filters on the playback type). The bridge's `playing` is `showsPlaying` for every renderer.
- **A pause from the Sonos app or the speaker's button** reaches the session through `SonosPoller`. `publish` ->
  `setNowPlaying(playing=false)` -> stamp. The window runs from there.
- **A room taken over** by another source or grouped away makes `showsPlaying` false. That counts as the end of play.
- **A stale speaker** (`playWhenReady` held): the session says PLAYING and the card shows playing. When it flips, the
  session's stamp starts the window (§2.1).
- **A crashed music process** while a room plays: `playerGone` stamps, and the card goes 10 minutes later although the
  speaker may play on. This is 26a's known crash case: the next start adopts it, or a suspension edge stops it.
  Accepted.
- **A headset** while a room plays changes nothing: it is playing anyway.
- **Storytel** books are ordinary items to the launcher. Nothing to do.

## 3. Pure functions (`launcher/.../music/MusicWindow.kt`, new; `MusicWindowTest`)

```kotlin
data class BootStamp(val elapsedMs: Long, val bootCount: Int)
data class Listening(val playedAt: BootStamp?, val headsetAt: BootStamp?)
const val MUSIC_LINGER_MS = 10 * 60_000L
const val HEADSET_BOOT_GRACE_MS = 2 * 60_000L

/** Long.MAX_VALUE while [playing]; else the elapsed end of the later valid window, or null (hidden). */
fun musicShownUntil(playing: Boolean, listening: Listening, now: BootStamp): Long?
/** The live session a surface may show: shownSession's result, kept while playing or inside a window. */
fun windowedSession(session: MusicSession?, listening: Listening, now: BootStamp): MusicSession?
/** The stamp after an observation (§2.1), and whether it notifies (only wasPlaying && !playing). */
fun playedStampAfter(previous: BootStamp?, wasPlaying: Boolean, playing: Boolean, now: BootStamp): BootStamp?
fun playStampNotifies(wasPlaying: Boolean, playing: Boolean): Boolean
data class AudioOut(val id: Int, val type: Int, val sink: Boolean)
fun countsAsHeadset(out: AudioOut): Boolean          // §2.3's types, sinks only
/** New known ids, and whether a fresh headset connected. */
fun headsetStep(known: Set<Int>, added: List<AudioOut>, nowElapsedMs: Long): Pair<Set<Int>, Boolean>
/** Delay to the window's end for a shown, non-playing surface; null while playing, hidden or no end. */
fun musicRecheckInMs(hideAtElapsedMs: Long?, nowElapsedMs: Long): Long?
```

`AudioDeviceInfo.TYPE_*` are compile-time constants, so the JVM tests run without Android (as `PlaybackState` in
`MusicMediaPlan.kt`).

`ui/home/MusicCard.kt`:
- `musicCard(...)` gains `listening: Listening` and `bootCount: Int` (`now` = `BootStamp(nowElapsedMs, bootCount)`).
  After the existing locked/access/usable checks it computes
  `until = musicShownUntil(live?.playing == true, listening, now) ?: return null`, where `live` is `shownSession(...)`.
- `MusicCardModel` gains `hideAtElapsedMs: Long?` (null while playing). Nothing else changes.

`lock/LockGlance.kt`: `glanceTile` keeps its code. Its comment says the session is the windowed one (design 28).

## 4. Android glue

- **`music/MusicListening.kt`** (new, an object, `@Synchronized` state, listeners on main):
  - `init(app)`: load the stamps, the baseline, then register the device callback (§2.3).
  - `listening(): Listening`.
  - `observePlay(context, wasPlaying, playing)`: `playedStampAfter`; persist on change (`apply()`); notify when
    `playStampNotifies`.
  - `devicesAdded(outs)` / `devicesRemoved(ids)`: `headsetStep`; persist, then notify on a fresh one.
  - `addListener` / `removeListener`.
  - A failure to register logs and leaves only the play feeds.
- **`music/MusicBridgeService.kt`**: `MusicNowPlaying.store` calls `MusicListening.observePlay(context, wasPlaying,
  playing)` before its `changed()`. `playerGone` calls it with `playing = false`.
- **`music/MusicSessions.kt`**:
  - `pick`, `update` and `onSessionDestroyed` call `observePlay(app, previous?.playing == true, new?.playing == true)`
    before `changed()`, where `previous` is the `reading.session` they replace. (`MusicSessions` needs the app context:
    keep it from `watch`.)
  - `MusicMedia.init` adds `MusicListening.init(app)` after `MusicCalls.init(app)`.
- **`music/MusicHomeCard.kt`**:
  - `render` passes `MusicListening.listening()` and `BootClock.bootCount()`.
  - The `expiry` Runnable (`render()`) is posted at `musicRecheckInMs(card.hideAtElapsedMs, now)` and removed with the
    ticker.
  - `setActive` adds and removes the `MusicListening` listener.
- **`music/MusicLockPanel.kt`**:
  - `lookUp()` and `render()` take `windowedSession(shownSession(...), MusicListening.listening(), now)`.
  - `lookUp()` posts its `expiry` (-> `changed`, i.e. `lookUp` + `onChange`, so the panel refits without the tile).
  - `start`/`stop` add and remove the listener and the expiry.
  - It still calls nothing new on `MusicSessions` (`LockMediaGuardTest`'s call set is unchanged).
- **Debug hook** (the emulator has no headset):
  - `launcher/app/src/debug/java/com/kidslauncher/mdm/music/HeadsetDebugReceiver.kt` calls
    `MusicListening.devicesAdded(listOf(AudioOut(id = <unused negative id>, TYPE_BLUETOOTH_A2DP, sink = true)))`,
    which goes through the same step and grace.
  - It is exported and guarded by DUMP in `src/debug/AndroidManifest.xml`.
  - `HeadsetDebugReceiver` is added to `CheckDebugHook`'s `names` (`app/build.gradle.kts`) and to
    `DebugHookAbsentTest`'s banned list.

## 5. Part B - the Play Store during the pause and the override

Verified on `main`:
- **Callers.** `playPackageLaunchable` has exactly one caller, `AppFilter.kidListable` (`apps/AppFilter.kt:99`). It is
  fed by `PlayRuntime.installModeActive(context)` at `AppFilter.kt:37`. `AppFilter` serves Home's grid
  (`HomeActivity.refreshApps`) and the drawer (`ListFragmentApps`, `AppsRecyclerAdapter`). Nothing else decides
  whether Play is listed: the `PLAY_CORE` uses elsewhere are about enforcement, kiosk helpers and the update fence.
- **Suspension in a window.** `OfflineOverride.isActive() || RestrictionsPause.isActive()` is `overrideActive` in
  `buildEnforcementPlan`, which makes `appsManaged = false` and `locked = false`. So `playStoreSuspended` is false,
  `locked && !updateWindow` is false, and the store is **unsuspended**.
- **Kiosk.** `kioskPackages = null`, so `LockTaskChrome.writeLockTask` sets no lock-task packages and the kiosk is off
  for the whole window. Play needs no pin.
- **Covered.** The PWA's `start_pause` is a `RestrictionsPause` too.
- **Link blocker.** `PlayLinkBlockedActivity` stays the persistent preferred activity for `market://` and
  `play.google.com/store` (never cleared, QA #2). In a window `playStoreOpen()` is true (not suspended), so it forwards
  the link to the store explicitly. No change; documented.
- **Refresh.**
  - The pause's `reapply` / the override's apply unsuspends Play. `LauncherApps.Callback.onPackagesUnsuspended` ->
    `Application.loadApps()` -> `apps` LiveData. Home's `appsObserver` and the drawer's adapter re-filter at once (it is
    not a camera-lock change, so `CameraLock.ownChange` is false).
  - At the end ("Lock again", the boundary alarm, `end_windows`), the relock's `apply()` suspends Play.
    `onPackagesSuspended` -> reload. `isMdmSuspended` hides a suspended Play whatever the window flag says, so the
    order of the flag and the suspension doesn't matter.

Change:
- `play/PlayPolicy.kt`:
  - add `fun playStoreListable(installMode: Boolean, windowOpen: Boolean): Boolean = installMode || windowOpen`;
  - rename `playPackageLaunchable`'s second parameter to `storeListable` (the same body);
  - update the file header ("never on Home ... except in install mode and while the override or pause is open").
- `play/PlayRuntime.kt`: add `fun storeListable(context): Boolean = playStoreListable(installModeActive(context),
  OfflineOverride.isActive() || RestrictionsPause.isActive())`.
- `apps/AppFilter.kt` (MIT directory, one call and two comments, no new logic):
  - `PlayRuntime.installModeActive(context)` -> `PlayRuntime.storeListable(context)`;
  - the local becomes `playStore`, and `kidListable`'s parameter `installMode` becomes `playStoreListable` (callers
    are positional).
- The Settings "Install from Play" toast for NOT_NEEDED stays. It is also the unmanaged phone's answer, where Play
  stays off the drawer.

## 6. Tests

`MusicWindowTest` (new, `launcher/app/src/test/java/com/kidslauncher/mdm/music/`):
- `playing shows with no end`
- `a stop shows for 10 minutes from the stop, then hides`
- `a live paused session older than 10 minutes hides`
- `a fresh headset shows for 10 minutes and the later window wins`
- `a stamp from another boot, an unknown boot count or a future stamp counts as nothing`
- `no stamp - only playing shows`
- `the stamp moves on playing and on the end of play, not on paused after paused`
- `only the end of play notifies`
- `windowedSession keeps a playing session, a paused one inside the window, nothing else`
- `headset types - wired, USB headset, A2DP, LE headset and speaker; not SCO, hearing aid, broadcast, USB device, HDMI, built-ins or sources`
- `devices at registration are the baseline, never fresh`
- `a reconnect (new id) is fresh, a removed id is forgotten`
- `connections in the first 2 minutes after boot are baseline`
- `the recheck delay is the time left, null while playing or without an end`

`MusicCardTest` (the helper gets an open window by default):
- `the card shows only while playing or inside the window`
- `a paused live session outside the window hides the card - never the snapshot instead`
- `hideAt is the window's end while paused and null while playing`

`MusicMediaPlanTest` / `LockMediaGuardTest`:
- `Home's card and the lock tile read the same window`: a source scan, `MusicHomeCard.kt` with `musicCard(` and
  `MusicListening.listening()`, `MusicLockPanel.kt` with `windowedSession(` and `MusicListening.listening()`.
- `the stamps are fed by the bridge and the session`: `observePlay` in `MusicNowPlaying.store`, `playerGone`, and
  `MusicSessions`' `pick`/`update`/`onSessionDestroyed`.
- The existing `VolumeCap.init`/`MusicCalls.init` pin stays green.

`DebugHookAbsentTest`: `HeadsetDebugReceiver` banned from main and release.

`PlayPolicyTest`:
- the existing `playPackageLaunchable` test with the renamed parameter;
- `the Play Store is listable in install mode and while the override or the pause is open`
  (`playStoreListable` truth table);
- `Play services and GSF are never listable, in a window or not`.

`AppFilterTest`:
- `the filter asks the Play rule with the windows`: a source scan, `PlayRuntime.storeListable(context)` in
  `AppFilter.kt` and no `installModeActive` there;
- `kidListable lists the store only when it is listable`.

## 7. Steps (one implementer sitting each; each green on its own:
`./gradlew assembleDebug assembleRelease testDebugUnitTest` in `launcher/`)

1. **Part B.** `PlayPolicy.kt`, `PlayRuntime.kt`, the one-call edit in `AppFilter.kt`, the tests in §6. Also:
   - launcher `CLAUDE.md`, "Push, battery and Play": "never pinned or shown on Home/drawer ... except in install mode
     **and, shown only, while the override or the pause is open (design 28)**";
   - a line in design 07's status.
2. **Part A rules and feeds.** `MusicWindow.kt` + `MusicWindowTest`, `MusicListening.kt`, the bridge and session
   feeds, `MusicMedia.init`, the debug receiver with its manifest, gradle and test entries. No surface reads the
   window yet, so the behaviour is unchanged after this step.
3. **Part A surfaces and docs.**
   - `musicCard` + `MusicCardModel`, `MusicHomeCard`, `MusicLockPanel`, `glanceTile`'s comment, `MusicCardTest` and
     the guard tests.
   - Launcher `CLAUDE.md`: the "media surfaces and time" bullets for Home and the lock, and the glance section's tile
     rule.
   - A pointer to design 28 in design 21's step 4 status and design 25 §1.1.
   - `docs/testing/emulator.md` §6j (below).
   - Status here.

## 8. Device checks (Android 16 Google Play emulator, `docs/testing/emulator.md`; new §6j)

Set up: a managed phone with the music app and the debug feed (§6f), listener access granted, `adb shell svc power
stayon true` for the waits. Check the boot time with `adb shell cat /proc/uptime`.

**Part A**
1. Play an episode. The Home card ticks. Screen off and on: the glance tile shows, playing.
2. Pause in the player and go Home. Note the time. The card shows paused. Don't touch the phone: at +10 min it goes
   by itself. Lock the screen and wake it: no tile.
3. Play, pause, and at about +5 min crash the launcher (`adb shell am crash me.vibb.launcher.debug`). Home comes back
   with the card, and it goes at the original +10 min.
4. Play, pause, reboot. No card and no tile after boot.
5. With uptime > 2 min, send `adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.music.HeadsetDebugReceiver`
   on a visible Home. The snapshot card appears at once. Its play button opens the player playing (`EXTRA_RESUME`).
   Pause: the card goes 10 min after the pause.
6. With the music app's session still live and paused, outside a window: the broadcast brings the glance tile back
   on the lock (play works from it). Without a live session (force-stop the music app) the lock shows no tile, and
   Home shows the snapshot.
7. Reboot and send the broadcast while uptime < 2 min: no card.
8. Sonos (§6h, fake speaker): play on the fake room, so the card is playing. Pause from the player: the card stays
   paused and goes at +10 min. Kill `fake-sonos.py` while it plays: the card follows the session. It shows playing
   while the player holds `playWhenReady` and paused once the player gives up, and it goes 10 min after that flip, not
   10 min after the last good poll.
9. The real phone (Jelly Star; not on the emulator): BT buds connected with nothing playing for an hour show the card
   for 10 min. Buds in the ears at boot give no card. A wired or USB-C headset behaves the same. An SCO-only car kit
   gives no card.

**Part B**
1. Managed, kiosk on: no Play Store in the drawer or on Home.
2. Settings -> "Pause all restrictions" (parent PIN). The Play Store appears in the drawer and on Home within about a
   second, without reopening. It opens with no "App is not available" and no lock-task toast. Run
   `adb shell am start -a android.intent.action.VIEW -d market://details?id=org.example`: Play opens.
3. "Lock again" (the banner): Play leaves the drawer at once. The market link shows "The Play Store is closed".
4. The same with a bedtime rule and the time lock's "Enter unlock code" (the override), and with the PWA's pause.
5. In no case do Play services or GSF show. Install mode is unchanged (§6b).

## 9. Defaults chosen (veto if wrong)

- The lock never shows the snapshot (no controls without opening the app). Home does.
- Hearing aids and SCO-only devices are not headsets. LE broadcast is not a connection.
- The boot grace is 2 minutes of elapsed time.
- A launcher restart inside the window brings the card back for the rest of it.
