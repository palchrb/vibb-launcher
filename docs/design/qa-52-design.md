# Design QA - 52 Bluetooth headphones (connect a paired device from Kid Settings and the music app)

Reviewed on `main` at 016f8677 (2026-10-20): `docs/design/52-bluetooth-headphones.md` against `CLAUDE.md`,
`launcher/CLAUDE.md` (Kid Settings, Bluetooth gotchas, `MusicSync`), `music/CLAUDE.md` (renderer/output axes),
design 26a §5.1, the code it names, and AOSP `packages/modules/Bluetooth` at `android14-release`, `android15-release`
and `android16-release` (framework proxies, `A2dpService`/binder, `AdapterServiceBinder`, `HeadsetServiceBinder`,
`Utils`, `PhonePolicy`, `AdapterProperties`, `apex/hiddenapi/*`) plus `build/soong`'s `generate_hiddenapi_lists.py`
(android16). App paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `M/` =
`music/app/src/main/java/me/vibb/music/`. AOSP paths are the downloaded copies (`aosp-bt/`, `r14/`, `r15/`, `r16/`).
Reading only, no build.

## The central claim holds - on 14, 15 and 16

`BluetoothA2dp.connect(BluetoothDevice)` / `disconnect(BluetoothDevice)` carry the same annotations on all three
releases: `@hide`, `@RequiresPermission(BLUETOOTH_CONNECT)`, `@UnsupportedAppUsage` with no `maxTargetSdk`
(`r14/BluetoothA2dp.java:329-333,375-379`, `r15/BluetoothA2dp.java:326-330,364-368`,
`aosp-bt/BluetoothA2dp.java:335-339,374-377`). So the methods are on the "unsupported" list: reflection works at
target 36 and logs. The module's own `apex/hiddenapi/*.txt` overrides name only AIDL stubs on all three releases, never
these methods. The service side asks for the same on all three: service available,
`checkCallerIsSystemOrActiveOrManagedUser`, and `checkConnectPermissionForDataDelivery` (16:
`A2dpServiceBinder.java:72-101`; 15: `r15/A2dpService.java:1380-1412`; 14: `r14/A2dpService.java:1281-1316`). The
proxy fills in `mAttributionSource`. Then `A2dpService.connect` refuses `CONNECTION_POLICY_FORBIDDEN` and a device
without the A2DP sink UUID (`A2dpService.java:219-260`, `r14/A2dpService.java:280-`).

What a failure looks like, for the catch list:
- **No `BLUETOOTH_CONNECT`**: a hard denial makes `Utils.checkPermissionForDataDelivery` throw `SecurityException`
  (`r14/Utils.java:553-554`, `r15/Utils.java:636-637`, `r16/Utils.java:555`). The exception crosses the binder. On
  16/15 `BluetoothA2dp.connect` catches only `RemoteException`. On 14 the `SynchronousResultReceiver` re-throws the
  propagated exception. Either way reflection sees `InvocationTargetException(SecurityException)`.
- **A soft denial, Bluetooth off, the proxy not attached, or an invalid device**: the call returns `false`.
- **Android 14 only**: the call blocks on a `SynchronousResultReceiver` with a timeout (`r14/BluetoothA2dp.java:341-345`).
- **A future block**: `getMethod` throws `NoSuchMethodException`.

`BluetoothLeAudio.connect`/`disconnect` are plain `@hide` (no `@SystemApi`, no `@UnsupportedAppUsage`) on all three
releases (`r14/…:722-726`, `r15/…:775-779`, `aosp-bt/…:823-827`). They are blocked, as the note says.

## Findings

**52-1 (High) - §0/§1's facts about `@SystemApi` are wrong, and decision 2 rests on them.** `@SystemApi` members are
**not** blocked for reflection. `generate_hiddenapi_lists.py:310-311` adds `sdk` to every member flagged `public-api`
or `system-api`. Our own launcher already shows it: Forget reflects `BluetoothDevice.removeBond()`, which is
`@SystemApi` only (`aosp-bt/BluetoothDevice.java:2083-2085`, the same on 14/15), and Forget is live-verified
(`launcher/CLAUDE.md:1086`). So:
- `isConnected()` (`BluetoothDevice.java:2340-2353`) is reachable. Its service call needs only `BLUETOOTH_CONNECT`
  (`r16/AdapterServiceBinder.java:626-635`). `isConnectedReflective` (`L/server/QuickControls.kt:500`) most likely
  works today, but it gives the **ACL** state (any profile), not "connected for music".
- `BluetoothDevice.connect()`/`disconnect()` are reachable too. They fail on `enforceCallingOrSelfPermission(MODIFY_PHONE_STATE / BLUETOOTH_PRIVILEGED)`
  (`r16/AdapterServiceBinder.java:739-759`), which throws `SecurityException`. Reflection wraps it, and the existing
  `catch (e: Exception)` catches it. The result is never `NoSuchMethodException`. So Connect/Disconnect are broken, as
  the note says, but for a different reason.

Change the text:
- §0: "`isConnected()` is `@SystemApi`, so it is on the sdk list (reachable by reflection); it needs only
  `BLUETOOTH_CONNECT` and reports the ACL link. `BluetoothDevice.connect()`/`disconnect()` are reachable but the service
  enforces `BLUETOOTH_PRIVILEGED` (+ `MODIFY_PHONE_STATE`)."
- §1: drop "a blocked-API `NoSuchMethodException`" and "every device reads not connected". Say instead: the mark is the
  ACL state, unverified.
- Decision 2: "the mark of an **audio** device comes from the A2DP/LE proxies ('Tilkoblet' = A2DP connected). A
  non-audio device keeps `isConnected` (ACL), so a connected watch or keyboard still reads 'Connected'." Do not
  remove `isConnectedReflective`.

**52-2 (High) - threading: "awaited at most 2 s" never works on the main thread, which is where today's callers
run.** `getProfileProxy` delivers `onServiceConnected` through `mMainHandler::post`
(`r16/BluetoothAdapter.java:934,3438`). The proxy object only reaches us in that callback. A latch or `runBlocking`
on main therefore always times out. The Kid Settings buttons call `QuickControls.connectBluetoothDevice` synchronously
on main (`L/ui/quickcontrols/BluetoothDevicesActivity.kt:56-57`). On 14 the A2DP call itself also blocks on a
synchronous receiver (above).

Change decision 1:
- "`BluetoothAudio`'s glue is `suspend`. The proxy is awaited with `suspendCancellableCoroutine` + `withTimeoutOrNull(2 s)`,
  never by blocking a thread. Every binder call (connect, disconnect, states, bonded devices, UUIDs, names) runs on
  `Dispatchers.IO`."
- "Kid Settings calls it from `lifecycleScope`. The music app calls it from the ViewModel's scope."
- Name the source check: `BluetoothAudio` contains no `CountDownLatch`, `runBlocking` or `.await(` on a latch.

Also say whether the proxy is held for the life of the screen or sheet (recommended: one A2DP proxy, plus the LE proxy
only when `adapter.isLeAudioSupported() == FEATURE_SUPPORTED`; opened when the screen or sheet opens, closed on
dismiss) or opened per call. Without that gate, an unsupported LE proxy never connects and every refresh waits the
full 2 s.

**52-3 (High) - a room playing: "first switches the renderer back to the phone" sends a failed connect to the phone's
speaker.** `output(dev:…)` → `toPhone(resume = null)` hands off the room and plays on the phone if the room was
playing (`M/playback/PlaybackService.kt:1960-1965,2048-2053`). The note does this at the tap, before the headset
exists. If the headset is off or busy with the other phone, the room has been stopped and the story now plays out
loud from the phone. Even when the headset does connect, the speaker plays for the 2-6 s it takes. There is also no
`dev:` key to pick until the device is routed (keys are `AudioDeviceInfo.id`, `PlayerModel.kt:25-29`).

Change decision 3:
- "A tap on '<name> · Koble til' only connects; the renderer doesn't move."
- "When the device then shows up as a routed output (matched by address, 52-4) inside the window, and the tap was
  made while a room played, the app sends `OUTPUT` with that output's `dev:` key once (the usual `toPhone` + `setOutput`)."
- "On failure the room plays on untouched."
- "On the phone renderer nothing extra is needed: `onAudioDevicesAdded` already drops a 'Telefonen' choice to system
  routing (`PlaybackService.kt:1331-1340`) and the new active A2DP device takes the sound."
- "A headset that connects by itself while a room plays changes nothing (26a: the room stays the output,
  `publish` prefers `renderer.room`, `:1742-1746`)."

Add the pure decision (`headsetTapPlan(onRoom, routedNow)`) to the tests.

**52-4 (Medium) - "paired but not connected" misses a connected-but-not-routed headset.** A2DP may hold several sinks:
`mMaxConnectedAudioDevices` is the overlay `config_bluetooth_max_connected_audio_devices` (AOSP default **5**,
`r16/config.xml:173`; `AdapterProperties.java:162-178`), and only the active one is an `AudioDeviceInfo` output. So
with two headsets on, the inactive one is CONNECTED by the proxy. It is not in `outputs()`
(`PlaybackService.kt:1310-1312`) and not a "Koble til" row either. It can't be reached, and `connect()` on it does
nothing (no state change, so the 10 s "Fikk ikke kontakt" would be a lie). The device check's "connecting the second
drops the first (A2DP's one sink)" holds only where the overlay is 1.

Change decision 1/3:
- The sheet's extra rows are "paired audio devices **not routed now**". They are matched by address: with
  `BLUETOOTH_CONNECT` granted, `AudioDeviceInfo.getAddress()` is the real MAC.
- The pure function takes `(bonded, uuids, a2dpStates: Map<address,Int>, leStates, routedAddresses)`; drop the
  `a2dpConnected`/`leConnected` lists, which can't carry CONNECTING.
- A tap on a CONNECTED-but-not-routed device calls the A2DP proxy's `setActiveDevice(device)`: `@UnsupportedAppUsage(trackingBug)`
  with no `maxTargetSdk` on 14/15/16 (`r14/BluetoothA2dp.java:502`, `r15/…:466`, `aosp-bt/…:475`); the binder asks only
  `BLUETOOTH_CONNECT` (`A2dpServiceBinder.java:135-146`). Any other state calls `connect`.
- §4 device check: "read `adb shell getprop persist.bluetooth.maxconnectedaudiodevices` / `dumpsys bluetooth_manager`.
  With 1, the second drops the first (and the becoming-noisy pause, 52-9). With more, both stay connected and the
  new one becomes active."

**52-5 (Medium) - the sheet's behaviour after a tap is undecided.**
- Today any pick closes the sheet (`M/ui/MusicRoot.kt:157`), so "Kobler til…" and the failure line have nowhere to
  live.
- The state has no owner: composable `remember` is lost when the sheet is reopened.
- `connect()` returning `false` (forbidden policy, no A2DP UUID, Bluetooth off) should not wait 10 s.
- A late CONNECTED (the A2DP state machine's connect timeout is longer than 10 s) must clear the failure.

Decide in decision 3:
- "A 'Koble til' tap keeps the sheet open. The ViewModel holds `connecting: address → startedAt`.
  - `false` from `connect` → the failure line at once.
  - `ACTION_CONNECTION_STATE_CHANGED` (A2DP) CONNECTING→DISCONNECTED for that device → the failure line at once.
  - Routed → the row is gone (the connected row takes over) and the sheet closes.
  - 10 s with neither → the failure line. A later CONNECTED still routes and clears it.
  - Dismissing the sheet doesn't cancel the attempt. A failure after dismissal is a `PlayerHub.Message`
    (`HEADSET_NO_CONTACT`), shown like `NOT_DOWNLOADED`."
- Name the strings (nb + en). "Fikk ikke kontakt - slå på hodetelefonene" is wrong for a Bluetooth speaker: use
  "Fikk ikke kontakt med %1$s - er den slått på?" / "Couldn't reach %1$s - is it switched on?".

**52-6 (Medium) - privacy: the note never says where names and addresses may go.** `BLUETOOTH_CONNECT` gives the
music app every bonded device's name and MAC, and un-anonymised `AudioDeviceInfo` addresses. Today the app sees only
the routed device's `productName`. `music_state` has no output field (`server/testdata/music_state.json`) and the
bridge sends none, so nothing leaks today; keep it that way in writing.

Add to decision 4: "Bonded names and addresses stay in memory on the phone. They are never logged (on failure log
only the exception's class and its cause's class, never `$device`, a name or an address - the launcher's
`Log.w(…, "… $address …")` lines in `QuickControls.kt:654,676,689` go too). They are never in the report,
`music_state`, `setNowPlaying`, the state recorder or `renderer.json`. The address is only the row's key."

Add a `SourceScanTest` case like the Storytel one ("no log line in `bluetooth/` interpolates a value"). Also say
the reflection failure log reads the cause, not `InvocationTargetException`.

**52-7 (Medium) - Kid Settings: half-specified.**
- There is no Kid Settings model to extend. The buttons are decided in `BluetoothDeviceAdapter.bind`
  (`L/ui/quickcontrols/BluetoothDeviceAdapter.kt:64-74`). Name a pure function (`bluetoothRowActions(info)` →
  Connect / Disconnect / Forget / none) and add `audio: Boolean` (+ `state`) to `BluetoothDeviceInfo`.
- After a tap the screen does `refreshOne` at once (`BluetoothDevicesActivity.kt:56-57`), so it always shows the old
  state. Register for A2DP `ACTION_CONNECTION_STATE_CHANGED` (and `ACTION_ACL_CONNECTED/DISCONNECTED` for the ACL
  mark) next to the bond receiver. Show "Kobler til…" like `setPairing`, with the same 10 s / failure rule as the
  music app (nb + en).
- The screen reads the bonded list in `scan()` before `scanBluetoothDevices` grants `BLUETOOTH_CONNECT`
  (`BluetoothDevicesActivity.kt:82-85` vs `QuickControls.kt:563-564`). Grant first in `onCreate`; the proxies throw
  without it.
- Disconnect through A2DP drops music only: HFP stays up and the call audio still goes to the headset. Either also
  call `BluetoothHeadset.disconnect(device)` by reflection (`@SystemApi`, so on the sdk list; its binder asks only
  `BLUETOOTH_CONNECT` - `r16/HeadsetServiceBinder.java:82-88`, `r14/HeadsetService.java:514-526`; annotations
  `r16/BluetoothHeadset.java:474-478`, `r14/…:451-455`, `r15/…:460-464`) - recommended, so Disconnect
  means what it says - or say in the note that Disconnect is "stop music on it". Connect needs nothing extra:
  `PhonePolicy` connects the other enabled profiles (HFP) 6 s after A2DP connects (`r16/PhonePolicy.java:94,651`).
- Put `BluetoothAudio` under a path `KidScreensEscapeTest` scans (`server/` beside `QuickControls.kt`, or add its
  file to `guarded`, `KidScreensEscapeTest.kt:41-42`).

**52-8 (Medium) - device checks cover one Android version; the claim is about three.** The Jelly Star runs one
release (its version is still unrecorded, `docs/testing/emulator.md:1076`). Add an emulator check on 34, 35 and 36
images that needs no headset:
1. `dumpsys package me.vibb.music | grep BLUETOOTH_CONNECT` shows `granted=true` with the policy-fixed flag after a
   sync.
2. `BluetoothAudio.connect` on `adapter.getRemoteDevice("02:00:00:00:00:01")` (debug-only trigger, or a unit of the
   debug build) returns `false`, not an exception (the UUID refusal).
3. logcat has `Accessing hidden method Landroid/bluetooth/BluetoothA2dp;->connect(…) (unsupported, reflection,
   allowed)`.
4. With the grant reset to default and the permission revoked via `pm revoke`, the `SecurityException` is caught and
   the sheet shows only today's rows.

On the phone, add:
- a room playing + Koble til to a switched-off headset: the room plays on;
- the same with the headset on: music moves to it;
- a call after Koble til rings in the headset (HFP followed);
- Bluetooth off: no extra rows, nothing crashes;
- the maxconnectedaudiodevices value (52-4);
- Kid Settings' marks for a connected non-audio device (52-1).

**52-9 (Low) - switching headsets pauses (when the overlay is 1).** `LocalRenderer` sets
`setHandleAudioBecomingNoisy(true)` (`M/playback/LocalRenderer.kt:67`). When A2DP drops the first sink, that is a
noisy event and the player pauses; the second headset then connects with the story paused. Fine (it is the same as
pulling a plug), but say so in decision 3 and expect it in §4. Don't fight it.

**52-10 (Low) - scope trims.**
- (a) The wired "status row" is already there: the WIRED output row says "Kablet · tilkoblet" (`values-nb/strings.xml:72`,
  `PlayerScreen.kt:368`). Drop that sentence: "wired: unchanged".
- (b) LE-only devices: list them in Kid Settings with their state (no Connect). Leave them out of the sheet, where a
  row without an action is a dead tap.
- (c) "Koble fra" in the sheet: agree, drop it. Strike the proposal so the implementer doesn't build it.
- (d) Bluetooth off: `getBondedDevices` is empty, so the sheet shows nothing new. State that; the music app never
  turns Bluetooth on.
- (e) Add to decision 5's Never list: `BluetoothAdapter.ACTION_REQUEST_ENABLE` (a system dialog), `enable()`,
  `createBond`, `startDiscovery`, `BLUETOOTH_SCAN`/`BLUETOOTH_ADMIN` in the music manifest - pinned by
  `SourceScanTest`.
- (f) The feature doesn't depend on the parent's Kid Settings Bluetooth switch (connecting an already-paired device
  changes no rule). Say so, so nobody gates it on the mask.

**52-11 (Low) - permission details.**
- The withdraw path doesn't withdraw. `notificationGrantState(false)` is `PERMISSION_GRANT_STATE_DEFAULT`, which keeps
  the current grant and only unfixes it (`L/music/MusicMediaPlan.kt:129-132`). Without a policy the music app keeps
  `BLUETOOTH_CONNECT` until someone revokes it. Fine, but write it down.
- The kid sees nothing: no dialog, and the "admin granted" notification is for location only. The once-only
  `getPermissionGrantState != wanted` check (`MusicSync.kt:68`) keeps the gotcha about repeated grants away.
- Make the grant a list (`MUSIC_RUNTIME_GRANTS = [POST_NOTIFICATIONS, BLUETOOTH_CONNECT]`) looped in
  `grantNotifications` (rename it), and pin it in **`MusicMediaPlanTest`**, which holds `notificationGrantState`'s test
  (`:138-139`), not `MusicPlanTest`.

**52-12 (Low) - small text fixes.**
- Name the UUIDs the filter uses: A2DP sink `0000110B-…`; LE Audio's ASCS `0000184E-…` (`BluetoothUuid.LE_AUDIO` is
  not public).
- "Each app has its own copy": the launcher's copy also classifies non-audio devices, so the copies are not
  byte-identical, and that's fine. Say so, so nobody adds a contract file for it.
- Update 26a §5.1's row order and `music/CLAUDE.md`'s output-axis line: phone, routed outputs, paired-not-routed
  ("Koble til"), rooms, "Se etter høyttalere".
- A long device name ellipsizes in the row (the 26a `FitTest` style).
- §0's "Tasker … is the same kind of call" is unverifiable and not needed - drop it or mark it hearsay.

## Tests - add to §3

Launcher:
- `BluetoothAudioTest` (filter, states, routed-address matching).
- `bluetoothRowActions` (audio → Connect/Disconnect, non-audio → Forget plus the ACL mark).
- A source check: connect/disconnect reflect `BluetoothA2dp` (and `BluetoothHeadset.disconnect` if 52-7 is taken),
  never `BluetoothDevice.connect`/`disconnect`; no blocking waits (52-2); no device value in a log (52-6).
- `MusicMediaPlanTest` for the grant list.

Music app:
- `BluetoothAudioTest` (its copy).
- `headsetTapPlan` (on a room: no move at the tap; OUTPUT after routing; nothing on failure).
- The sheet-state reducer (52-5: immediate `false`, CONNECTING→DISCONNECTED, timeout, late success).
- A manifest/source scan: `BLUETOOTH_CONNECT` only, no scan/pair/enable, no device value in logs.
- nb + en strings.

## Questions for the user

None needed. 52-7's Disconnect meaning (music only vs. the whole headset) can be decided as recommended (the whole
headset).

## Verdict

**Approve with changes.** The A2DP proxy route is sound on Android 14, 15 and 16 with only `BLUETOOTH_CONNECT`, and
the device-owner grant is the right model. Before implementation:
- Fix the `@SystemApi` facts and keep the ACL mark for non-audio devices (52-1).
- Make the glue suspend and off main (52-2).
- Make the room flow connect-first, switch-on-route (52-3).
- Handle connected-but-not-routed devices (52-4).
- Decide the sheet's state and where its messages live (52-5), and write the privacy rule (52-6).
