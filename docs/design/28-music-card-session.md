# 28 - The music card follows a listening session; the Play Store in the drawer during a window (buildable spec)

Architect, 2026-10-13, on `main` at `fff8c287` (designs 25, 26a, 26b and 27 are in). Revised the same day after the
design QA (`qa-28-design.md`; "Design QA" at the end). Launcher only: **no music app change, no AIDL change, no server
change.** Sources: design 21 §3 and its step 4 status, design 25 §1.1 (the tile, user decision 25b-G), designs 07 §4 and
09 B4 (Play), design 24b (the windows), and the code named below.

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

**B. The Play Store in the drawer during the pause or the override.** `RestrictionsPause` and `OfflineOverride` lift
every app restriction. Install mode then refuses with NOT_NEEDED, and `playPackageLaunchable` hides the store
everywhere except in install mode. So Play is open but can't be reached. Decision: while either window is open, the
Play Store (only the store, never Play services or GSF) is **in the app drawer** (QA L8: the drawer only, not Home's
grid, §5).

## 0. In short

- One pure rule, `musicShownUntil`, in a new `music/MusicWindow.kt`, decides for both surfaces. It shows while
  playing, and otherwise until 10 minutes after the later of "the end of play" and "a fresh headset connection". Home's
  card and the lock's tile both read it.
- **The end of play** is stamped by one feed: the bridge's `setNowPlaying`, which the music app already sends on every
  play/pause flip, every 15 s while playing, and paused from its `onDestroy`. The token's death (`playerGone`) stamps
  too. An end of play counts only after playing was seen in this boot (QA L1).
- **The clock:** `elapsedRealtime` plus `BOOT_COUNT`, persisted. A launcher restart inside the 10 minutes brings the
  card back for the rest of the window. A reboot never does.
- **The headset:** `AudioManager.registerAudioDeviceCallback` (no permission). The devices present at registration are
  the baseline. A connection in the first 2 minutes after boot doesn't count. A device that comes back within 60 s of
  going (a BT blip, a codec change) doesn't count either (QA M2).
- **The re-render:** one delayed callback at the window's end, while the surface is shown, paused and inside the
  window. Home also renders the card at every resume (QA L5).
- **Part B:**
  - `playPackageLaunchable(pkg, installModeActive)` is fed by `PlayRuntime.storeListable` = install mode, or the
    override, or the pause. `apps/AppFilter.kt` changes one call.
  - Home's grid drops the store unless install mode is on (`playPackageOnHome`, in `HomeActivity`), so in a window it
    is in the drawer only.
  - A `WindowRelock` listener in `Application` reloads the app list at every window edge. The refresh no longer rests
    on Play's suspension, which on GMS phones never happens (QA M1).

## 1. Part A - behaviour

| Situation | Home card | Lock tile |
|---|---|---|
| The session plays (`sessionPlay` = PLAYING: also buffering, connecting, skipping) | live, ticking | live |
| Paused or stopped, at most 10 min ago | the live session paused, else the snapshot | the live session paused, else none |
| Paused or stopped more than 10 min ago, no fresh headset | none | none |
| A headset connected at most 10 min ago | the live session paused, else the snapshot | the live session paused, else none |
| The launcher restarted (crash, self-update) inside a window | back for the rest of the window | same |
| After a reboot (also one while it played: a dead battery) | none until something plays or a headset connects (after the 2-min grace) | same |
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
  - NONE, STOPPED and ERROR are not live. The card falls back to the snapshot inside the window.

## 2. Part A - the settled questions

### 2.1 Where the end of play comes from: the bridge, no AIDL change
- **The feed.** `PlaybackService.reportNowPlaying` (music) sends:
  - on every change of `(entry, item, playing)`;
  - on the 15 s `saveTick` with `refresh = true`, for every renderer;
  - paused (`forcePaused`) from `onDestroy`.

  `playing` is `renderer.showsPlaying`: on a Sonos room that is fresh + reachable + ours + PLAYING. `LiveBridge`
  re-sends the last state after a rebind (a launcher update or restart). The token's death calls
  `MusicNowPlaying.playerGone`. So the launcher sees every playing -> not-playing edge without being on screen, at
  worst 15 s late when the edge report itself was lost. `MusicNowPlaying.store` and `playerGone` already know
  `wasPlaying` (`playing_at` in prefs) and the new `playing`.
- **The stamp rule (pure `playedStampAfter`).**
  - An observation with `playing` sets `playedAt` to now.
  - An observation with `!playing && wasPlaying` sets it to now (the end of play), **but only when the previous
    `playedAt` is valid in this boot** (QA L1). A battery that dies mid-play leaves `playing_at` set across the boot,
    and the music app's first report after it (`setNowPlaying(null)` or a paused restore) must not open a window.
  - **And only when the previous `playedAt` is younger than `END_OF_PLAY_STAMP_MAX_AGE_MS` (60 s)** (step 2 code QA
    L2). While it plays the bridge refreshes the stamp every 15 s, so a real pause is always within that. An older
    stamp means a stale `playing_at` in this boot: both processes died while the launcher was down, or a oneway
    "playing" report was handled after the token's death. The music app's next report (hours later, after a tap or a
    media command) then opens no window. The cost: a stop the bridge reports more than 60 s after the last playing
    report (none is expected; a Sonos stop noticed only after a long deep sleep would be one) opens no window either.
  - Anything else keeps the stamp.
  - So the stamp is "the last moment we knew it played, in this boot".
- **No session feed** (QA L2). On the phone a PLAYING session implies `showsPlaying`. On Sonos the session holds
  `playWhenReady` for at most 3 failed polls (15 s, 26a §3.2) after `showsPlaying` went false. So the bridge's stamp is
  at most about 15-45 s older than the session's flip: a 9.5-minute window instead of 10. That isn't worth three call
  sites and a kept context in `MusicSessions`. A bridge that never binds gives no stamps, and then the card shows only
  while playing (fail closed).
- **Stale flags.** "Playing now" for the surfaces is the live session only, never the bridge's `playing_at`. That
  flag can stay stale when both processes died while the launcher was down (and the end-of-play rule ignores it after
  60 s, above). Without listener access there are no
  surfaces at all (21 §3), so the session is always there when it matters.
- **What doesn't stamp.** The music app restoring itself paused ("Nothing starts by itself", playing=false after
  playing=false) never stamps, so its restart or a reboot shows no card.

### 2.2 The clock: elapsedRealtime + BOOT_COUNT, persisted
- `BootStamp(elapsedMs, bootCount)`: `SystemClock.elapsedRealtime()` and `BootClock.bootCount()`
  (`server/RestrictionsPause.kt`). The boot count can't change within a process, so `MusicListening` reads it **once**
  when its state loads (QA L8 nit). There is no `Settings.Global` read per tick.
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
- **Loading** (QA L4). The state loads lazily under `MusicListening`'s lock on first use (`listening()`,
  `observePlay`, `devicesAdded`), so the bridge works even when `MusicMedia.init` threw before `MusicListening.init`.
  Every writer runs after the first unlock (the bridge and the headset callback from `initRest`), so CE storage is
  there.

### 2.3 The headset: which devices, and what is "fresh"
- **`AudioManager.registerAudioDeviceCallback(callback, main)`.** It needs no permission, and `VolumeCap` already uses
  one. It goes in a new `MusicListening.init(app)`, called from `MusicMedia.init` **after `MusicCalls.init(app)`**
  (`MusicMediaPlanTest` pins `VolumeCap.init(app)\n            MusicCalls.init(app)` as adjacent lines).
  - `init` catches its own failures: it only reads the baseline and registers.
  - It is not `ACTION_HEADSET_PLUG` (wired only) and not the BT profile broadcasts (`BLUETOOTH_CONNECT` permission,
    per profile).
- **Types (output sinks only, `isSink`)** are exactly the music app's own `OutputKind.BLUETOOTH`/`WIRED` mapping
  (`PlaybackService.kt`):
  - `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES` and `TYPE_USB_HEADSET`;
  - `TYPE_BLUETOOTH_A2DP` (buds, headsets, BT speakers);
  - `TYPE_BLE_HEADSET` and `TYPE_BLE_SPEAKER` (LE audio, and so **LE Audio hearing aids**: they enumerate as
    `TYPE_BLE_HEADSET`, and the launcher can't tell them apart without Bluetooth permissions; §9).
- **Not counted:**
  - `TYPE_BLUETOOTH_SCO`: calls only. A BT headset also brings A2DP, and an HFP-only car kit is not for listening.
  - `TYPE_BLE_BROADCAST`: an Auracast source, not a connection.
  - `TYPE_HEARING_AID` (ASHA): worn all day and reconnects often.
  - `TYPE_USB_DEVICE`/`_ACCESSORY`, HDMI/ARC/eARC, dock, line, bus, remote submix, built-ins, and every source.
- **Fresh connection (pure `headsetStep`).** `AudioOut(id, type, sink, address)`, where `address` is
  `getAddress()`: stable per BT device (anonymised to its last octets without `BLUETOOTH_CONNECT`, which is enough) and
  empty for wired. A device's key is `type + address`, so a wired device keys by its type. The glue keeps `known`
  (output ids; `getId()` is the audio port, new for every `AVAILABLE`) and `recentlyRemoved` (key -> removed at
  elapsed).
  - At `init`, `known` is the `getDevices(GET_DEVICES_OUTPUTS)` ids, read **before** registering (pure
    `headsetBaseline`). AOSP calls `onAudioDevicesAdded` once at registration with every current device; those are all
    in `known`, so no stamp. A device added between the read and the registration is a real connection (after the
    grace below).
  - An added device stamps `headsetAt` when it is a counted type, its id is not in `known`,
    `nowElapsed >= freshFromMs` (the grace, below), and its key was **not removed in the last `HEADSET_REJOIN_MS` (60 s)**
    (QA M2). A link drop and auto-reconnect, an A2DP codec re-negotiation (AudioPolicyManager toggles the device
    UNAVAILABLE -> AVAILABLE, which is removed + added with a new id) and an active-device switch between two connected
    sinks are all a re-add of the same key within seconds. A second device with another address is fresh.
  - Every added id joins `known`. `onAudioDevicesRemoved` drops the ids and records each counted key's removal time.
    Entries older than the rejoin window go at the next step.
- **Boot.** `initRest` runs only after the first unlock, so devices connected before it are the baseline. Bonded BT
  devices reconnect on their own some seconds after the adapter comes up, which can be after our registration. So
  connections while `elapsedRealtime < 2 min` (`HEADSET_BOOT_GRACE_MS`) are baseline too. Buds already in the ears at
  boot give no card.
- **The registration** (step 2 code QA L3). The watch starts only at the first unlock, which can be long after the boot,
  and a phone's Bluetooth stack may connect A2DP only after it. So connections within 2 minutes of the registration are
  baseline too: `HeadsetWatch.freshFromMs` is the later of boot + 2 min and registration + 2 min (`headsetBaseline`).
  The same holds after a launcher process restart: a headset put on in the first 2 minutes of the new process gives no
  card.
- **A launcher process restart** never stamps (baseline). An earlier persisted headset stamp still runs out its window
  (§2.2).

### 2.4 The re-render at the window's end
- **The boundary** (QA L7): shown iff `now < end`; the recheck delay is `(end - now).coerceAtLeast(1)`. The two
  functions agree, so the end is never re-rendered in a loop. Tests sit at exactly 10 min and at 10 min - 1 ms.
- **The callback.** Both surfaces post **one** `Runnable` at `musicRecheckInMs(hideAtElapsedMs, now)`, only while
  shown, not playing and inside a window.
  - It is removed at every render (re-posted for the new end) and on stop or inactive.
  - While playing, the existing 1/s ticker runs instead.
- **Sleep.** `Handler` delays use uptime. The lock renders from the clock at every `resume()`. Home now does too:
  `resumeContent` calls `renderMusicCard()` (an active card re-renders, `MusicHomeCard.setActive`) (QA L5). So a sleep
  that delayed the runnable never leaves the card past its window.
- **Listeners.** `MusicListening` notifies (on main) only when a window may **open**: an end-of-play stamp, or a fresh
  headset.
  - A playing stamp (every 15 s) notifies no one. The surfaces already show while playing, and a 15 s panel render
    would refit the lock's page.
  - Home's card and `MusicLockPanel` add the listener between start and stop.
  - The stamp is written before `MusicNowPlaying.store`'s own `changed()`, so that render already sees it.

### 2.5 Sonos and Storytel
- The music app has one `MediaSession` over whichever renderer plays (on a room the `SonosRenderer`
  `SimpleBasePlayer`). The launcher reads it as it reads the phone's (`MusicMediaPlanTest` already pins that nothing
  filters on the playback type). The bridge's `playing` is `showsPlaying` for every renderer.
- **A pause from the Sonos app or the speaker's button** reaches the session through `SonosPoller`. `publish` ->
  `setNowPlaying(playing=false)` -> the end-of-play stamp.
- **A room taken over** by another source or grouped away makes `showsPlaying` false. That counts as the end of play.
- **A stale speaker** (`playWhenReady` held for up to 3 polls): the session still says PLAYING and the card shows
  playing. The window then runs from the bridge's earlier flip, seconds shorter (§2.1).
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
const val HEADSET_REJOIN_MS = 60_000L
const val END_OF_PLAY_STAMP_MAX_AGE_MS = 60_000L     // step 2 code QA L2

/** Long.MAX_VALUE while [playing]; else the elapsed end of the later valid window when now < end, or null (hidden). */
fun musicShownUntil(playing: Boolean, listening: Listening, now: BootStamp): Long?
/** The live session a surface may show: shownSession's result, kept while playing or inside a window. */
fun windowedSession(session: MusicSession?, listening: Listening, now: BootStamp): MusicSession?
/** The stamp after a bridge observation (§2.1): an end of play only after a stamp valid in [now]'s boot, < 60 s old. */
fun playedStampAfter(previous: BootStamp?, wasPlaying: Boolean, playing: Boolean, now: BootStamp): BootStamp?
/** Notify only when an end of play moved the stamp. */
fun playStampNotifies(previous: BootStamp?, next: BootStamp?, playing: Boolean): Boolean
data class AudioOut(val id: Int, val type: Int, val sink: Boolean, val address: String)
fun countsAsHeadset(out: AudioOut): Boolean          // §2.3's types, sinks only
data class HeadsetWatch(val known: Set<Int>, val removedAt: Map<String, Long>,  // key "type/address" -> elapsed
                        val freshFromMs: Long = HEADSET_BOOT_GRACE_MS)
/** The watch at registration: the ids present are known, and fresh only from registration + 2 min (step 2 code QA L1, L3). */
fun headsetBaseline(watch: HeadsetWatch, ids: List<Int>, nowElapsedMs: Long): HeadsetWatch
/** The watch after added devices, and whether a fresh headset connected (baseline, grace, rejoin). */
fun headsetAdded(watch: HeadsetWatch, added: List<AudioOut>, nowElapsedMs: Long): Pair<HeadsetWatch, Boolean>
fun headsetRemoved(watch: HeadsetWatch, removed: List<AudioOut>, nowElapsedMs: Long): HeadsetWatch
/** (end - now).coerceAtLeast(1) for a shown, non-playing surface; null while playing, hidden or no end. */
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

- **`music/MusicListening.kt`** (new, an object, `@Synchronized` state loaded lazily (§2.2), listeners on main):
  - `init(app)`: its own try; the baseline, then register the device callback (§2.3).
  - `listening(): Listening` and `bootCount()` (read once).
  - `observePlay(context, wasPlaying, playing)`: `playedStampAfter`; persist on change (`apply()`); notify when
    `playStampNotifies`.
  - `devicesAdded(outs)` / `devicesRemoved(outs)`: `headsetAdded` / `headsetRemoved`; persist a fresh stamp, then
    notify.
  - `addListener` / `removeListener`.
  - Its KDoc must not name the debug receiver: `DebugHookAbsentTest` matches raw text, comments included (QA L3).
- **`music/MusicBridgeService.kt`**: `MusicNowPlaying.store` calls `MusicListening.observePlay(context, wasPlaying,
  playing)` before its `changed()`. `playerGone` calls it with `playing = false`.
- **`music/MusicSessions.kt`**: only `MusicMedia.init` changes. It adds `MusicListening.init(app)` after
  `MusicCalls.init(app)`. There is no session feed (§2.1).
- **`music/MusicHomeCard.kt`**:
  - `render` passes `MusicListening.listening()` and `MusicListening.bootCount()`.
  - The `expiry` Runnable (`render()`) is posted at `musicRecheckInMs(card.hideAtElapsedMs, now)` and removed with the
    ticker.
  - `setActive` adds and removes the `MusicListening` listener.
- **`ui/HomeActivity.kt`**: `resumeContent` calls `renderMusicCard()` (QA L5).
- **`music/MusicLockPanel.kt`** (QA L7):
  - One private `shown(): MusicSession?` = `windowedSession(shownSession(...), MusicListening.listening(), now)`, used
    by both `lookUp()` and `render()`.
  - `lookUp()` posts its `expiry` (-> `changed`, i.e. `lookUp` + `onChange`, so the panel refits without the tile).
  - `start`/`stop` add and remove the listener and the expiry.
  - It still calls nothing new on `MusicSessions` (`LockMediaGuardTest`'s call set is unchanged).
- **Debug hook** (the emulator has no headset):
  - `launcher/app/src/debug/java/com/kidslauncher/mdm/music/HeadsetDebugReceiver.kt` gives each broadcast its own id
    (a counter down from -1000) and its own address (`"debug-<n>"`) (QA L3). It calls
    `MusicListening.devicesAdded(listOf(AudioOut(id, TYPE_BLUETOOTH_A2DP, sink = true, address)))`, which goes through
    the same step, grace and rejoin rule.
  - It is exported and guarded by DUMP in `src/debug/AndroidManifest.xml`.
  - `HeadsetDebugReceiver` is added to `CheckDebugHook`'s `names` (`app/build.gradle.kts`) and to
    `DebugHookAbsentTest`'s banned list.

## 5. Part B - the Play Store in the drawer during the pause and the override

Verified on `main` (QA M1 corrected the suspension part):

**Callers.** `playPackageLaunchable` has exactly one caller, `AppFilter.kidListable` (`apps/AppFilter.kt:99`). It is
fed by `PlayRuntime.installModeActive(context)` at `AppFilter.kt:38`. `AppFilter` serves both:
- the drawer (`ListFragmentApps`, `AppsRecyclerAdapter`);
- Home's grid (`HomeActivity.refreshApps`, which also drops our own package after the filter).

Nothing else decides whether Play is listed: the `PLAY_CORE` uses elsewhere are about enforcement, kiosk helpers and
the update fence.

**In a window.** `OfflineOverride.isActive() || RestrictionsPause.isActive()` is `overrideActive` in
`buildEnforcementPlan`, which makes `appsManaged = false` and `locked = false`.
- `kioskPackages = null`, so the kiosk is off for the whole window and Play needs no pin.
- The PWA's `start_pause` is a `RestrictionsPause` too.

**Two kinds of phone.**
- **`play_store_suspendable` = true** (no GMS verifier):
  - Outside a window the store is suspended (`playStoreSuspended`).
  - In a window it is unsuspended.
  - `PlayLinkBlockedActivity` (the persistent preferred activity for `market://` and `play.google.com/store`, never
    cleared) shows "The Play Store is closed" outside a window and forwards the link inside one.
- **`play_store_suspendable` = false** (GMS: the Play Store is the package verifier, design 09 B4 - the Jelly Star and
  the Google Play emulator):
  - The store is never suspended. Outside a window only the kiosk blocks it, and the drawer filter keeps it out of
    sight.
  - The blocker always forwards. With the kiosk on, lock task stops the start; with the kiosk off, Play opens. That is
    the B4 status quo, unchanged here.

**The refresh comes from the window's own edges, not from Play's suspension.**
- `Application.initRest` adds `WindowRelock.addListener { loadApps() }`. `WindowRelock` notifies on main:
  - at a window's open (`opened`, after the lift is committed);
  - after a relock (`runRelock`: the boundary alarm, a sync, a screen-on);
  - after "Lock again" (`lockAgain`: the banner, the notification, the Settings switch, `end_windows`).
- `loadApps()` posts the `apps` LiveData. The drawer's adapter (`apps.observe` -> `updateAppsList`, also while open)
  and Home's `appsObserver` both re-filter.
- The window flags are written **before** these notifications (`RestrictionsPause.start` -> `WindowRelock.opened {
  lift }`; `lockAgain` clears the flags before its `check`). So the filter reads the new state, and the order is right
  on both kinds of phone.
- On a suspendable phone, `onPackagesUnsuspended`/`Suspended` reloads once more, which is harmless. `isMdmSuspended`
  keeps a still-suspended store hidden until the apply has run.

**Change:**
- `play/PlayPolicy.kt`:
  - add `fun playStoreListable(installMode: Boolean, windowOpen: Boolean): Boolean = installMode || windowOpen`;
  - add `fun playPackageOnHome(packageName: String?, installMode: Boolean): Boolean = packageName != PLAY_STORE ||
    installMode`;
  - `playPackageLaunchable`'s parameter keeps its name (`installModeActive`, so `PlayPolicyTest` and `AppFilterTest`
    stay as they are - QA L6). Its doc comment says it now means "the store may be listed: install mode or a window";
  - the file header says "never on Home except in install mode; in the drawer also while the override or the pause is
    open".
- `play/PlayRuntime.kt`: add `fun storeListable(context): Boolean = playStoreListable(installModeActive(context),
  OfflineOverride.isActive() || RestrictionsPause.isActive())`.
- `apps/AppFilter.kt` (MIT directory: one call and a comment, no new logic):
  - `PlayRuntime.installModeActive(context)` -> `PlayRuntime.storeListable(context)`;
  - the local stays `installMode`, so `AppFilterTest`'s text pin of the `kidListable(...)` line holds. Its comment
    says it is the store's listability.
- `ui/HomeActivity.kt` (GPL) `refreshApps`: after `AppFilter`, also `.filter { playPackageOnHome(pkg, installMode) }`,
  with `installMode = PlayRuntime.installModeActive(context)` read in the same background block.
- `Application.kt`: the `WindowRelock` listener above.
- The Settings "Install from Play" toast for NOT_NEEDED stays. It is also the unmanaged phone's answer, where Play
  stays out of the drawer.

## 6. Tests

`MusicWindowTest` (new, `launcher/app/src/test/java/com/kidslauncher/mdm/music/`):
- `playing shows with no end`
- `a stop shows until 10 minutes after it - shown at 10 min - 1 ms, hidden at exactly 10 min`
- `a live paused session older than 10 minutes hides`
- `a fresh headset shows for 10 minutes and the later window wins`
- `a stamp from another boot, an unknown boot count or a future stamp counts as nothing`
- `no stamp - only playing shows`
- `the stamp moves on playing and on the end of play, not on paused after paused`
- `an end of play after a reboot (previous stamp from another boot or none) keeps nothing`
- `an end of play counts only within 60 s of the last playing report - a stale flag in the same boot keeps the stamp`
- `only an end of play that moved the stamp notifies`
- `windowedSession keeps a playing session, a paused one inside the window, nothing else`
- `headset types - wired, USB headset, A2DP, LE headset and speaker; not SCO, ASHA hearing aid, broadcast, USB device, HDMI, built-ins or sources`
- `devices at registration are the baseline, never fresh`
- `a re-add of the same device within 60 s is not fresh; after 60 s it is`
- `a second device (another address) is fresh`
- `a wired device keys by its type`
- `connections in the first 2 minutes after boot are baseline`
- `connections in the first 2 minutes after the registration are baseline too - the later of boot and registration`
- `the recheck delay is the time left and at least 1 ms, null while playing or without an end`

`MusicCardTest` (the helper gets an open window by default):
- `the card shows only while playing or inside the window`
- `a paused live session outside the window hides the card - never the snapshot instead`
- `hideAt is the window's end while paused and null while playing`

`MusicMediaPlanTest` / `LockMediaGuardTest`:
- `Home's card and the lock tile read the same window`: a source scan.
  - `MusicHomeCard.kt` has `musicCard(` and `MusicListening.listening()`.
  - In `MusicLockPanel.kt`, `windowedSession(` occurs exactly once (in `shown()`), and both `lookUp` and `render`
    call `shown()`.
- `the end of play is fed by the bridge`: `observePlay` in `MusicNowPlaying.store` and `playerGone`.
- `Home renders the card at every resume`: `renderMusicCard()` in `resumeContent`.
- The existing `VolumeCap.init`/`MusicCalls.init` pin stays green.

`DebugHookAbsentTest`: `HeadsetDebugReceiver` is banned from main and release.

`PlayPolicyTest`:
- `the Play Store is listable in install mode and while the override or the pause is open` (`playStoreListable` truth
  table);
- `Play services and GSF are never listable, in a window or not`;
- `the Play Store is on Home only in install mode` (`playPackageOnHome`).

`PlayInvariantsTest` (source scans):
- `the filter asks the Play rule with the windows`: `PlayRuntime.storeListable(context)` in `AppFilter.kt`;
- `Home drops the store outside install mode`: `playPackageOnHome(` in `HomeActivity.refreshApps`;
- `a window's edges reload the app list`: `WindowRelock.addListener` with `loadApps()` in `Application.kt`.

`AppFilterTest`: unchanged (the names are kept).

## 7. Steps (one implementer sitting each; each green on its own:
`./gradlew assembleDebug assembleRelease testDebugUnitTest` in `launcher/`)

1. **Part B.** `PlayPolicy.kt`, `PlayRuntime.kt`, the one-call edit in `AppFilter.kt`, Home's filter, the
   `Application` listener, and the tests in §6. Also:
   - launcher `CLAUDE.md`, "Push, battery and Play": "never pinned or shown on Home/drawer ... except in install mode
     **and, in the drawer only, while the override or the pause is open (design 28); a window's edges reload the app
     list**";
   - a line in design 07's status.
2. **Part A rules and feeds.** `MusicWindow.kt` + `MusicWindowTest`, `MusicListening.kt`, the bridge feed,
   `MusicMedia.init`, the debug receiver with its manifest, gradle and test entries. No surface reads the window yet,
   so the behaviour is unchanged after this step.
3. **Part A surfaces and docs.**
   - `musicCard` + `MusicCardModel`, `MusicHomeCard`, `HomeActivity.resumeContent`, `MusicLockPanel`, `glanceTile`'s
     comment, `MusicCardTest` and the guard tests.
   - Launcher `CLAUDE.md`: the "media surfaces and time" bullets for Home and the lock, and the glance section's tile
     rule.
   - A pointer to design 28 in design 21's step 4 status and design 25 §1.1.
   - `docs/testing/emulator.md` §6j (below).
   - Status here.

## 8. Device checks (Android 16 Google Play emulator, `docs/testing/emulator.md`; new §6j)

Set up: a managed phone with the music app and the debug feed (§6f), listener access granted, `adb shell svc power
stayon true` for the waits. Check the boot time with `adb shell cat /proc/uptime`. The debug broadcast is
`adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.music.HeadsetDebugReceiver`, below just "the
broadcast".

**Part A**
1. Play an episode. The Home card ticks. Screen off and on: the glance tile shows, playing.
2. Pause in the player and go Home. Note the time. The card shows paused. Don't touch the phone: at +10 min it goes
   by itself. Lock the screen and wake it: no tile. Then on the lock (step 3 code QA L3): play again, pause, and at
   about +8 min lock and wake the screen: the tile shows paused. Leave the lock on screen (stay-on is set): at +10 min
   the tile goes by itself and the page refits (the rows or the big clock move into its place, nothing scrolls).
3. Play, pause, and at about +5 min crash the launcher (`adb shell am crash me.vibb.launcher.debug`). The PIN lock
   comes up (a new process locks; step 3 code QA L2). With the music app's session still live, its tile shows
   paused. Unlock: Home has the card. Both go at the original +10 min.
4. Reboot after a pause, then again with `adb reboot` **while it plays**. No card and no tile after either boot, and
   none after opening and closing the player (QA L1).
5. With uptime > 2 min and the launcher's process older than 2 min (§2.3, the registration's grace), send the broadcast on a visible Home. The snapshot card appears at once. Its play button opens
   the player playing (`EXTRA_RESUME`). Pause: the card goes 10 min after the pause.
6. In the same launcher process (each broadcast is its own device), outside a window: with the music app's session
   still live and paused, the broadcast brings the glance tile back on the lock (play works from it). Force-stop the
   music app and send it again: the lock shows no tile, and Home shows the snapshot.
7. Reboot and send the broadcast while uptime < 2 min: no card. Then crash the launcher (as in check 3) and send it
   within 2 min of the crash (the grace counts from the new process's start, not from Home - don't unlock first and
   run past it), before or after unlocking: the result's `headsetAt` stays as it was, and there is no card and no tile.
8. Sonos (§6h, fake speaker): play on the fake room, so the card is playing. Pause from the player: the card stays
   paused and goes about 10 min after the player shows paused.
9. The real phone (Jelly Star; not on the emulator):
   - BT buds connected with nothing played for an hour show the card for 10 min.
   - Buds in the ears at boot give no card.
   - Reboot with the buds in, wait 3 minutes before the PIN, unlock: no card (step 2 code QA L3: the grace counts from
     the watch's registration too).
   - Walk out of range for about 10 s and come back: no new card.
   - Switch the BT codec in Developer options: no card.
   - A call over the buds and its end: no card.
   - A USB-C headset behaves like the buds. An SCO-only car kit gives no card.

**Part B** (do B1-B3 with **every installed app allowlisted**, so no other package's suspension edge refreshes the
lists by accident; note the device page's `play_store_suspendable`: false on this emulator)
1. Managed, kiosk on: no Play Store in the drawer or on Home.
2. Open the drawer and leave it open. Settings -> "Pause all restrictions" (parent PIN), then back to the drawer: the
   Play Store is listed (no reopen needed). Home's grid shows no Play Store. Play opens from the drawer with no "App is
   not available" and no lock-task toast. Run
   `adb shell am start -a android.intent.action.VIEW -d market://details?id=org.example`: Play opens.
3. "Lock again" (Home's banner, Home staying in front): Play leaves the drawer at once. The market link:
   - `play_store_suspendable` false (this emulator): the link reaches Play and the kiosk stops the start (with the
     kiosk off it opens - the B4 status quo);
   - true: "The Play Store is closed".
4. Let a pause run out by itself (or end it with the PWA's `end_windows`): Play leaves the drawer at the relock.
5. The same with a bedtime rule and the time lock's "Enter unlock code" (the override), and with the PWA's pause.
6. In no case do Play services or GSF show. Install mode is unchanged (§6b): the store is in the drawer and on Home.

## 9. Defaults chosen (veto if wrong), and notes for the user

- The lock never shows the snapshot (no controls without opening the app). Home does.
- SCO-only devices and ASHA hearing aids (`TYPE_HEARING_AID`) are not headsets. LE broadcast is not a connection.
- The boot grace is 2 minutes of elapsed time, counted from the boot and again from the watch's start (the first unlock,
  or a launcher restart).
- An end of play counts only within 60 s of the last "playing" report: a stale flag opens no window.
- A device that comes back within **60 s** of going (a BT blip, a codec change, an active-device switch) is not a
  fresh connection.
- A launcher restart inside the window brings the card back for the rest of it.
- The Play Store in a window is in the **drawer only**, not on Home's grid. Install mode keeps it in both.
- **For the user:**
  - **Play during a window is the whole store**, on the phone's Google account. The kid can install apps, and they are
    caught by the new-package block (hidden and suspended after the window, reported to the device page; Play is a
    trusted installer for the relock). Purchases go through the parent's Google account settings (a payment method,
    purchase authentication).
  - **LE Audio hearing aids count as a headset.** They enumerate as `TYPE_BLE_HEADSET`, and the launcher can't tell
    them apart without Bluetooth permissions. A kid with LE Audio hearing aids sees the card for 10 minutes at each
    real reconnect (not at blips).

## Design QA (2026-10-13, `qa-28-design.md`: 0 High, 2 Medium, 8 Low; all accepted)

- **M1 (Part B rested on Play's suspension).** §5 now covers both kinds of phone (`play_store_suspendable`) and the
  link blocker's behaviour on each. The refresh comes from the window's edges: a `WindowRelock` listener in
  `Application` reloads the app list. The "order doesn't matter" argument is replaced by the real one (the flags are
  written before the notifications). §8 B2-B4 run with every app allowlisted, and B3 has both link outcomes. A source
  test was added.
- **M2 (every BT blip a fresh headset).**
  - `AudioOut` carries `getAddress()`. A device keyed by `type + address` that comes back within `HEADSET_REJOIN_MS`
    (60 s) is not fresh (`headsetAdded`/`headsetRemoved`).
  - Tests: the 60 s rejoin, a second device, a wired key.
  - §8 check 9 has the range, codec and call checks on the Jelly Star.
  - The 60 s is in §9.
- **L1 (a stale `playing_at` opened a window after a reboot).** An end of play counts only after a stamp valid in this
  boot. There is a test, and §8 check 4 reboots while playing.
- **L2 (the session feed bought seconds).** Dropped. The bridge and `playerGone` are the feed, and §2.1 gives the real
  numbers. §8 check 8 no longer asks for a seconds-level difference.
- **L3 (the debug hook's single id).** Each broadcast has its own id and address. The KDoc warning for
  `DebugHookAbsentTest` is in §4.
- **L4 (state tied to `MusicMedia.init`).** The stamps load lazily under the lock on first use, and `init` has its own
  try and only does the baseline and the registration.
- **L5 (Home's expiry across a sleep).** `resumeContent` renders the card.
- **L6 (the rename broke pinned tests).** The parameter names are kept in `PlayPolicy.kt` and `AppFilter.kt`, so the
  tests stay as they are. The line is `AppFilter.kt:38`.
- **L7 (the boundary; the lock's two reads).** Shown iff `now < end`, the recheck at least 1 ms, with tests at the
  boundary. `MusicLockPanel` has one `shown()` for `lookUp` and `render`, and the scan counts it.
- **L8 (§9).**
  - Orchestrator decision: the store goes in the **drawer only**. This was possible without new MIT logic: Home's grid
    drops it in `HomeActivity` through `playPackageOnHome`.
  - The whole-store and LE Audio hearing-aid notes are user-facing notes in §9, and the 60 s rejoin is listed.
  - The boot count is read once per process.

## Step 1 implementation status (2026-10-13, branch `design-28-s1`)

Part B as §5 says; nothing of Part A.
- `play/PlayPolicy.kt`: `playStoreListable`, `playPackageOnHome`; `playPackageLaunchable` keeps its parameter name
  (its doc says what it means now), the header and `PLAY_CORE`'s comment name the drawer in a window.
- `play/PlayRuntime.kt`: `storeListable(context)` = install mode or `overrideActive()` (the file's existing
  `OfflineOverride.isActive() || RestrictionsPause.isActive()`).
- `apps/AppFilter.kt` (MIT): the one call and its comment; the local stays `installMode`.
- `ui/HomeActivity.kt` `refreshApps`: `installMode` read in the background block (an exception counts as off, as in
  `AppFilter`), then `.filter { playPackageOnHome(pkg, installMode) }` after our own package is dropped.
- `Application.initRest`: `WindowRelock.addListener { loadApps() }` right after the first `loadApps()`.
- Tests: `PlayPolicyTest` (3 new), `PlayInvariantsTest` (3 new source scans; the filter scan also checks that
  `AppFilter` no longer asks `installModeActive(` itself). `AppFilterTest` unchanged and green.
- Docs: launcher `CLAUDE.md` ("Push, battery and Play"), design 07's status. `docs/testing/emulator.md` waits for
  step 3 (§6j has both parts).
- Not device-tested: §8 B1-B6.

## Step 2 implementation status (2026-10-13)

Branch `design-28-s2` on `84e29547`. Not pushed. Nothing was run on a device or the emulator. **No surface reads the
window yet** (step 3), so the phone behaves as before; the stamps are written and the headset is watched.

- **Pure rules** `music/MusicWindow.kt` as §3 names them (`BootStamp`, `Listening`, the three constants,
  `musicShownUntil`, `windowedSession`, `playedStampAfter`, `playStampNotifies`, `AudioOut`, `countsAsHeadset`,
  `HeadsetWatch`, `headsetAdded`, `headsetRemoved`, `musicRecheckInMs`). A stamp is valid when its boot count is today's
  and known and its elapsed time isn't in the future. The end is exclusive, and the recheck is
  `(end - now).coerceAtLeast(1)`; `Long.MAX_VALUE` (playing) gives no recheck. The rejoin window is
  `0 <= now - removedAt < 60 s`; older entries are dropped at every step. A device's key is `"type/address"`.
- **`music/MusicListening.kt`**: `init(app)` (its own try: the baseline from `getDevices(GET_DEVICES_OUTPUTS)`, then
  `registerAudioDeviceCallback` on main), `listening()`, `bootCount()`, `observePlay(context, wasPlaying, playing)`,
  `devicesAdded(outs)`, `devicesRemoved(outs)`, `addListener`/`removeListener` (main thread; notified by a post only on
  an end of play that moved the stamp or a fresh headset). The stamps load lazily under the object's lock. The context
  is the caller's, else `init`'s, else `Application.instance`. A read before the first unlock (CE prefs throw
  `IllegalStateException`) shows nothing and loads later. Keys as §2.2. The boot count is read once, and read again
  only while it is still -1.
- **Feed**: `MusicNowPlaying.store` calls `observePlay(context, wasPlaying, playing)` right before its `changed()`, and
  `playerGone` calls it with `playing = false`. `MusicMedia.init` calls `MusicListening.init(app)` right after
  `MusicCalls.init(app)`.
- **Debug hook**: `src/debug/.../music/HeadsetDebugReceiver.kt` (id `-999 - n` = -1000, -1001, ...; address
  `debug-<n>`, n from 1). It calls `MusicListening.devicesAdded`, then reports the headset stamp, the uptime and the
  boot count (logcat `HeadsetDebug` and the broadcast's result data). Exported and guarded by DUMP in
  `src/debug/AndroidManifest.xml`. It is in `CheckDebugHook`'s names (`checkDebugHasDebugHook` finds it) and in
  `DebugHookAbsentTest`'s banned list. That test's manifest check now covers all three debug receivers.
- **Tests**: `MusicWindowTest` (§6's 17 cases at `f0b83ffd`, 19 after the fix round). `MusicMediaPlanTest`: `the end of play is fed by the bridge`, plus a pin
  for the headset watch: `MusicListening.init(app)` after `MusicCalls.init(app)`, the baseline read before the
  registration, `init`'s own catch, and the stamps loaded by `listening`/`observePlay`/`devicesAdded`, not `init`.
  `DebugHookAbsentTest`: the banned name, the three receivers, the hook's own id and address.
- **Code QA fix round** (`qa-28-s2-code.md`, 0 High, 0 Medium, 4 Low, all fixed; its "Fixes" table):
  - L1: the baseline seed is the pure `headsetBaseline` (tested; `MusicMediaPlanTest` pins its one call between the read
    and the registration).
  - L2: an end of play counts only within `END_OF_PLAY_STAMP_MAX_AGE_MS` (60 s) of the last stamp (§2.1).
  - L3: `HeadsetWatch.freshFromMs`, the later of boot + 2 min and registration + 2 min (§2.3), and §8 check 9's late
    unlock (check 5 and 7 adjusted).
  - L4: `CheckDebugHook`'s comment and messages name every hook; `MusicSuspensionGuardTest`'s KDoc names the design 28
    pins.
- **Left for step 3**: the launcher `CLAUDE.md` (also a line on the new debug receiver next to the 16d hook's bullet)
  and `docs/testing/emulator.md` §6j.

## Step 3 implementation status (2026-10-13)

Branch `design-28-s3` on `486c3e57` (step 2 and its fix round; step 1, Part B, is on its own branch). Not pushed.
Nothing was run on a device or the emulator. **The surfaces now read the window**: Home's card and the lock's tile show
while playing or inside the listening window, and nothing outside it.

- **`ui/home/MusicCard.kt`**: `musicCard(..., listening, bootCount)` returns null when `musicShownUntil(live?.playing
  == true, ...)` is null, right after the locked/access/usable checks, so a paused session outside the window never
  falls back to the snapshot. `MusicCardModel.hideAtElapsedMs` is the window's end, null while playing (no default:
  every construction names it).
- **`music/MusicHomeCard.kt`**: `render` passes `MusicListening.listening()`/`bootCount()` with one `now`; while
  playing the 1 s ticker runs, otherwise one `expiry` (`render()`) is posted at `musicRecheckInMs(card.hideAtElapsedMs,
  now)` - only while the card shows. Both runnables are removed at every render and on `setActive(false)`;
  `setActive` adds and removes the `MusicListening` listener (the same `changed` as the sessions' and the bridge's).
- **`ui/HomeActivity.kt`**: `resumeContent` calls `renderMusicCard()` after `render()` (QA L5).
- **`music/MusicLockPanel.kt`**: one private `shown()`, used by `lookUp()` and `render()`. Since the code QA (L1) it
  returns the pure `lockTileSession(access, usable, session, listening, now)` (`MusicWindow.kt`: `shownSession`, then
  `windowedSession`, with the window's end - `WindowedSession(session, hideAtElapsedMs)`, the end null while playing
  or hidden), so the lock's half of the rule is tested like Home's `musicCard`; the panel names no `windowedSession(`,
  `shownSession(` or `musicShownUntil(` itself (§6's "`windowedSession(` exactly once" became "`lockTileSession(`
  exactly once, in `shown()`"). `lookUp()` posts the `expiry` (-> `changed`: `lookUp` + `onChange`, the panel refits
  without the tile) at `musicRecheckInMs(hideAtElapsedMs, now)` with the same `now`, only while started.
  `start`/`stop` add and remove the `MusicListening` listener; `stop` also removes the expiry. `render`'s local
  `shown` became `size` (it shadowed the function). It still calls nothing new on `MusicSessions`.
- **`lock/LockGlance.kt`**: `glanceTile` unchanged; its comment says the session is the windowed one.
- **Tests**:
  - `MusicCardTest` (the helper gets an open window by default, the music stopped at elapsed 0 in boot 7): `the card
    shows only while playing or inside the window` (10 min - 1 ms / exactly 10 min, the later headset window, another
    boot, and the old gates still close it), `a paused live session outside the window hides the card - never the
    snapshot instead`, `hideAt is the window's end while paused and null while playing` (with the recheck's delay).
  - `MusicSuspensionGuardTest` (`MusicMediaPlanTest.kt`): `Home's card and the lock tile read the same window` (the
    card's `musicCard(` with the window, its expiry beside the ticker and in `setActive`; in `MusicLockPanel.kt`
    `lockTileSession(` exactly once, in `shown()`, no `windowedSession(`/`shownSession(`/`musicShownUntil(` of its
    own, `shown()` in both `lookUp` and `render`, the one expiry post behind `if (started)`, the expiry and the
    listener in `start`/`stop`) and `Home renders the card at every resume`.
  - `MusicWindowTest` (code QA L1): `the lock's tile - a paused session outside the window is no tile, never a fallback
    to the live session`, `... hideAt is the end while paused, null while playing or hidden`, `... no access, not
    usable, STOPPED or no session is no tile inside the window too`.
  - `LockMediaGuardTest` (extended): the panel's `MusicListening` calls are exactly `addListener`, `removeListener`,
    `listening`, `bootCount` (the stamps are read, never written), and a new `the lock's tile is a live session only -
    never the snapshot` (no `MusicNowPlaying`, snapshot, `openPlayer`, `EXTRA_RESUME` or `musicCard` in its code). The
    `MusicSessions` call set is unchanged.
  - Full build: `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` green (stub `tsnet.aar`):
    1063 tests in 118 classes, 0 failures (1066 after the code QA fix round); `checkDebugHasDebugHook` "hook present",
    `checkReleaseHasNoDebugHook` "no debug hook". No new compiler warning.
- **Code QA fix round** (`qa-28-s3-code.md`, 0 High, 0 Medium, 3 Low, all fixed; its "Fixes" table): L1 the pure
  `lockTileSession` above; L2 and L3 §8 checks 2, 3 and 7 (and §6j): the PIN lock first after a crash, the grace from
  the crash, the lock tile after a restart, and the tile going by itself on a lock left on screen.
- **Docs**: launcher `CLAUDE.md` (the listening window, Home's card and the lock's tile in "media surfaces and time",
  the glance section's tile rule, the debug headset next to the 16d hook's bullet); pointers in design 21's step 4
  status and design 25 §1.1; `docs/testing/emulator.md` §6j (§8's checks with the debug broadcast; its Play checks
  P1-P6 describe step 1's behaviour and need that branch merged).
