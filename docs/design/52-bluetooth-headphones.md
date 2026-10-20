# 52 - Bluetooth headphones: connect a paired device from Kid Settings and from the music app (proposal)

Asked 2026-10-20 (the user: "ville det vært mulig å la paring/bt connect av eksisterende parese [sic] enheter dukke opp i
musikk appen? ... er det noe robusthet og enkelhet av å sikre at bt headsets kobler seg til for avspilling av musikk vi
kan forenkle gjennom musikk appen" and, after a first answer that it can't be done: "Litt rart at ikke apper kan få gjøre
det? Er du sikker? Sjekk andre prosjekter please"). Launcher (Kid Settings) and the music app; no server change.

## 0. Facts (AOSP `android14-release`, `android15-release`, `android16-release`, packages/modules/Bluetooth, read 2026-10-20; revised after qa-52-design.md)

- `BluetoothA2dp.connect(BluetoothDevice)` / `disconnect(BluetoothDevice)` (framework `BluetoothA2dp.java` ~:339/:377 on
  16; the same annotations on 14 and 15): `@hide`, `@RequiresPermission(BLUETOOTH_CONNECT)`, `@UnsupportedAppUsage`
  **without** `maxTargetSdk` - reachable by reflection from an app targeting 36 (the "unsupported" non-SDK list:
  allowed, logged; Google may block it in a later release, then `getMethod` throws `NoSuchMethodException` and we fail
  soft). The service (`A2dpServiceBinder.getServiceAndEnforceConnect`) checks only service availability,
  `checkCallerIsSystemOrActiveOrManagedUser` and `BLUETOOTH_CONNECT` for data delivery. `A2dpService.connect` refuses a
  device with `CONNECTION_POLICY_FORBIDDEN` or without the A2DP sink UUID, and when only one sink may be connected
  disconnects the current one first. What a failure looks like: no `BLUETOOTH_CONNECT` -> `SecurityException` over the
  binder, seen by reflection as `InvocationTargetException(SecurityException)`; a soft denial, Bluetooth off, the proxy
  not attached, a forbidden policy or a device without the A2DP sink UUID -> `false`; on Android 14 the call blocks on a
  synchronous receiver (so never on main).
- **`@SystemApi` members are not blocked for reflection.** The hidden-api list generator adds `sdk` to every member
  flagged `public-api` or `system-api` (`generate_hiddenapi_lists.py`), so they are on the allowed list. Our own Forget
  proves it: `BluetoothDevice.removeBond()` is `@SystemApi` only and Forget is live-verified on a real phone.
  `BluetoothDevice.isConnected()` (`@SystemApi`) is therefore reachable; its service call needs only
  `BLUETOOTH_CONNECT` and it reports the **ACL** link (any profile), not "connected for music".
  `BluetoothDevice.connect()` / `disconnect()` (`@SystemApi`, `allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED,
  MODIFY_PHONE_STATE}`, returns an `int`) are reachable too, but the service enforces `BLUETOOTH_PRIVILEGED` and
  `MODIFY_PHONE_STATE` (`AdapterServiceBinder`), a `SecurityException` that reflection wraps - and the result is an
  `int`, read `as? Boolean` -> `false` anyway. That, not a block, is why Connect never worked.
- `BluetoothHeadset.disconnect(BluetoothDevice)` (HFP): `@SystemApi`, `@RequiresPermission(BLUETOOTH_CONNECT)`
  (annotations: 16 `BluetoothHeadset.java:473-478`, 15 `:460-464`, 14 `:451-455`), so on the sdk list. Its binder
  (`HeadsetServiceBinder.disconnect`, 16 :82-88; 14 `HeadsetService.java:514-526`) asks only service availability,
  the managed-user check and `BLUETOOTH_CONNECT` (`getService(source)`); only its `connect` adds
  `MODIFY_PHONE_STATE`. HFP connects by itself: `PhonePolicy` connects the other enabled profiles 6 s after A2DP
  connects.
- `BluetoothA2dp.setActiveDevice(BluetoothDevice)`: `@hide`, `@RequiresPermission(BLUETOOTH_CONNECT)`,
  `@UnsupportedAppUsage(trackingBug = 171933273)` with no `maxTargetSdk` on 14 (`r14/BluetoothA2dp.java:502`), 15
  (`:466`) and 16 (`:475`); the binder asks only `BLUETOOTH_CONNECT` (`A2dpServiceBinder.java:135-146`). The
  annotation is named per release because the implementer must re-check it on a bump.
- A2DP may hold several sinks: `mMaxConnectedAudioDevices` is the overlay `config_bluetooth_max_connected_audio_devices`
  (AOSP default **5**, `config.xml:173`; `AdapterProperties.java:162-178`; a phone may set 1), and only the **active**
  one is an `AudioDeviceInfo` output. A connected-but-inactive headset is CONNECTED by the proxy and not in the routed
  outputs.
- `BluetoothLeAudio.connect`/`disconnect` are plain `@hide` (no `@SystemApi`, no `@UnsupportedAppUsage`) on 14, 15 and
  16 (`:722-726`, `:775-779`, `:823-827`): blocked for reflection at target 36. We don't connect LE.
- Public and enough for the state: `BluetoothAdapter.getBondedDevices()`, `BluetoothProfile.getConnectedDevices()` /
  `getConnectionState(device)` on the A2DP proxy from `getProfileProxy`, `BluetoothDevice.getUuids()`,
  `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` for what is routed now (with `BLUETOOTH_CONNECT` granted
  `AudioDeviceInfo.getAddress()` is the real MAC). All need `BLUETOOTH_CONNECT` (runtime).
- `getProfileProxy` delivers `onServiceConnected` through the **main handler** (`BluetoothAdapter.java` :934, :3438 on 16):
  the proxy only reaches us in that callback, so blocking the main thread for it always times out.

## 1. Today

- **Kid Settings › Bluetooth** (`ui/quickcontrols/BluetoothDevicesActivity`, `server/QuickControls.kt`): scan, pair,
  forget work; **Connect/Disconnect call `BluetoothDevice.connect()`/`disconnect()` by reflection** (:670-694) - the
  `@SystemApi` methods above: reachable, but the service throws `SecurityException` (no `BLUETOOTH_PRIVILEGED` /
  `MODIFY_PHONE_STATE`), which the existing `catch (e: Exception)` swallows, and the `int` result is read `as?
  Boolean` -> `false` anyway. So Connect never worked (not device-tested before; the launcher's CLAUDE.md lists it as
  untested). The "connected" mark comes from `isConnectedReflective()` (:500), the reachable `isConnected()`: the ACL
  state, most likely working today, unverified. The buttons are decided in `BluetoothDeviceAdapter.onBindViewHolder` (:44-80),
  called synchronously on main (`BluetoothDevicesActivity.kt:56-57`), which then does `refreshOne` at once and so shows
  the old state; the screen reads the bonded list in `scan()` before `scanBluetoothDevices` grants `BLUETOOTH_CONNECT`
  (`BluetoothDevicesActivity.kt:82-85` vs `QuickControls.kt:563-564`). `QuickControls.kt` logs addresses
  (`Log.w(..., "... $address ...")`, :637, :656, :677, :690).
- **The music app's "Spill av på" sheet** (`ui/PlayerScreen.kt` `OutputSheet` :353): the phone, the **routed**
  Bluetooth and wired outputs (from the routed audio devices), the Sonos rooms. A paired headset that is off or
  connected elsewhere isn't there, nor is a connected one that is not the active sink. Wired headphones route by
  themselves on plug-in (no connect step) and already read "Kablet · tilkoblet". Any pick closes the sheet today
  (`ui/MusicRoot.kt:157`). The music app has no `BLUETOOTH_CONNECT`; it sees only the routed device's `productName`.
  `output(dev:...)` -> `toPhone(resume = null)` hands a playing room off and plays on the phone
  (`playback/PlaybackService.kt:1960-1965, 2048-2053`); `onAudioDevicesAdded` drops a "Telefonen" choice to system
  routing (:1331-1340); `LocalRenderer` has `setHandleAudioBecomingNoisy(true)` (:67).

## 2. Decisions

1. **One small pure + glue helper per app, the same rules** (each app has its own copy, not byte-identical - the
   launcher's also classifies non-audio devices - and no contract file for it): `BluetoothAudio`.
   - **Pure**: `pairedAudioDevices(bonded, uuids, a2dpStates: Map<address, Int>, routedAddresses)`: a bonded device
     with the A2DP sink UUID (`0000110b-0000-1000-8000-00805f9b34fb`) is an audio device; its state CONNECTED /
     CONNECTING / OFF comes from the proxy's `getConnectionState`; it is listed when its address is not among the
     routed outputs' addresses. The view model reads `routedAddresses` and the `AudioDeviceInfo.id` for a matched address
     from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` itself; `Output` and `PlayerHub.State` get no address field.
     The LE Audio ASCS UUID (`0000184e-0000-1000-8000-00805f9b34fb`; `BluetoothUuid.LE_AUDIO`
     is not public) marks an **LE-only** device: the launcher lists it in Kid Settings with its state and no Connect;
     the music sheet leaves it out (a row without an action is a dead tap). The launcher's copy also classifies
     non-audio devices (a watch, a keyboard). An LE-only device's state is the ACL `isConnected` mark, like a non-audio
     device; an audio device with the ASCS UUID counts as connected when A2DP is CONNECTED or ACL `isConnected`.
   - **Glue is `suspend`.** The proxy is awaited with `suspendCancellableCoroutine` + `withTimeoutOrNull(2 s)`, never
     by blocking a thread (the ready callback comes on main, 52-2); every Bluetooth call (bonded devices, UUIDs, states,
     `connect`, `disconnect`, `setActiveDevice`, the HFP disconnect) runs on `Dispatchers.IO`. Kid Settings calls it
     from `lifecycleScope`, the music app from the view model's scope.
   - **One A2DP proxy per screen / view model**: opened when the screen starts (Kid Settings' Bluetooth page; the music
     app: opened at the sheet's `onOpen`, closed when the sheet is dismissed; the connect call and the A2DP broadcast
     receiver outlive the sheet in the view model), closed on stop; not per call. A HEADSET proxy is opened with the A2DP
     one (same 2 s await, off main). A `false` from the HFP disconnect (no HFP state machine, or not connected) is not a
     failure; only A2DP's result decides the row. **No LE Audio proxy** - we don't connect
     LE, so nothing needs it (an unsupported LE proxy would otherwise cost the full 2 s on every refresh).
   - **Connect** = `BluetoothA2dp.connect(device)` by reflection; **make active** (a tap on a CONNECTED-but-not-routed
     device) = `BluetoothA2dp.setActiveDevice(device)` by reflection (needs only `BLUETOOTH_CONNECT`; annotations in
     §0). Every other state calls `connect`. Failures: `InvocationTargetException` is unwrapped; the `Throwable`'s
     class and its cause's class are logged, never the device (decision 5). `false` = not asked / refused.
   - The result is "asked" - `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` and the routed output are the truth.
   - "The second headset drops the first" is **phone-dependent** (it holds only where the overlay's max connected
     audio devices is 1; with more, both stay connected and the new one becomes active). Switching headsets may pause
     playback either way (decision 3).
2. **Launcher, Kid Settings**: `QuickControls.connectBluetoothDevice`/`disconnectBluetoothDevice` go through
   `BluetoothAudio`; they are no longer called synchronously on main.
   - **Marks**: an **audio** device's "connected" mark comes from the A2DP proxy ("Tilkoblet" = A2DP connected); a
     **non-audio** device (a watch, a keyboard) keeps `isConnected` (the ACL state), so it still reads connected.
     `isConnectedReflective` stays, for those.
   - **Rows**: a pure `bluetoothRowActions(info)` -> audio: Connect or Disconnect (by state) + Forget; other: Forget
     only; LE-only: Forget only. `BluetoothDeviceInfo` gets `audio: Boolean` and `state`. `BluetoothDeviceAdapter.onBindViewHolder`
     only follows it.
   - **Disconnect** means the whole headset: A2DP `disconnect` **and** `BluetoothHeadset.disconnect(device)` (HFP;
     reflective, `@SystemApi` so on the sdk list, `BLUETOOTH_CONNECT` only - §0), so a call stops ringing in the
     headset too. Connect needs nothing extra (`PhonePolicy` brings HFP up after A2DP).
   - **Live state**: register for `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` and `ACTION_ACL_CONNECTED` /
     `ACTION_ACL_DISCONNECTED` next to the bond receiver and refresh the row on each. After a tap the row reads
     "Kobler til…" (like `setPairing`) until the state broadcast, an immediate `false`, or 10 s -> "Fikk ikke kontakt med
     <name> - er den på?" (nb + en; the same rule as the music app, decision 3).
   - **The grant before the first list read**: `BluetoothDevicesActivity.onCreate` grants `BLUETOOTH_CONNECT` through
     `QuickControls.grantBluetoothConnectIfNeeded` (now `private`, :109; it becomes `internal`) before `scan()` reads the
     bonded list; the proxies throw without it.
   - `BluetoothAudio`'s file is added to `guarded` in `KidScreensEscapeTest.kt` (the list names `server/QuickControls.kt`
     as a file, so a new file in `server/` is not scanned by itself). No Settings intent; the Settings gate is unchanged.
   - The feature does not depend on the parent's Kid Settings Bluetooth switch (connecting an already-paired device
     changes no rule): nobody gates it on the mask.
3. **Music app, "Spill av på"**: rows in the order **phone, routed outputs, paired-not-routed ("Koble til"), Sonos
   rooms, "Se etter høyttalere"**.
   - **Rows**: the paired audio devices that are **not the routed output**, matched by address ("<name> · Koble til",
     the headphones glyph, dimmed subtitle; a long name ellipsizes, `FitTest` style). A CONNECTED-but-not-active
     device reads "<name> · Bytt til" (en "Switch to"; the same row; its tap makes it active); an OFF one "Koble til". Bluetooth off:
     `getBondedDevices` is empty, so the sheet shows nothing new; the music app never turns Bluetooth on. Wired
     headphones: unchanged. Pairing new devices stays in Kid Settings.
   - **A tap only connects (or makes active); the renderer does not move.** A room playing keeps playing. When the
     headset is **routed** (`AudioManager` routes media to it / the A2DP active device is it - the output is in
     `outputs()` with its `dev:` key, matched by address) while the attempt is open (up to the 10 s) and the tap was made while a room played,
     the app then sends `OUTPUT` with that `dev:` key once (the usual `toPhone` + `setOutput`). A failed connect leaves
     the room playing. A success after the failure line clears it but does not move a room (the same as a headset that
     connects by itself). On the phone renderer nothing extra is needed: `onAudioDevicesAdded` already drops a
     "Telefonen" choice to system routing and the new active A2DP device takes the sound. A headset that connects by
     itself while a room plays changes nothing (the room stays the output, 26a). The decision is the pure
     `headsetTapPlan(onRoom, routedNow)`.
   - **State lives in the music view model**, `connecting: address -> startedAt` (not a composable `remember`, which a
     reopened sheet loses). A headset row's tap **keeps the sheet open** with the row reading "Kobler til…" until:
     routed (the connected row takes over and the sheet closes as today); or `false` from the call, an A2DP
     CONNECTING -> DISCONNECTED broadcast for that device, or 10 s -> the row reads "Fikk ikke kontakt med <name> - er
     den på?" / "Couldn't reach <name> - is it switched on?" (neutral, it fits a speaker; nb + en strings). If the kid
     closed the sheet meanwhile, a failure shows as the player's one-line hint for 3 s (a new
     `PlayerHub.Message.HEADSET_NO_CONTACT`; the enum has no payload, so its string is nameless: nb "Fikk ikke kontakt med
     hodetelefonene - er de på?", en "Couldn't reach the headphones - are they switched on?"; its branch added to the
     `when` in `MusicRoot.kt`). Dismissing the sheet does not cancel the attempt. A CONNECTED
     broadcast ends "Kobler til…" and the row becomes "Bytt til" (no failure). A CONNECTING row reads "Kobler til…". A late
     success (the A2DP state machine's timeout is longer than 10 s) clears any error and routes as usual. The reducer is
     pure (immediate `false`, CONNECTING -> DISCONNECTED, timeout, late success).
   - **Switching headsets may pause playback**: `LocalRenderer` pauses on becoming-noisy when A2DP drops the first sink
     (where the overlay is 1); the second headset then connects with the story paused. Same as pulling a plug; don't
     fight it.
   - **No "Koble fra" in the sheet** (the headset's own button does it; Kid Settings has Disconnect). Strike it.
4. **Permission and privacy**: the music app declares `BLUETOOTH_CONNECT` (`usesPermissionFlags` not needed); the
   launcher's `MusicSync` grants it while a policy exists, beside `POST_NOTIFICATIONS`: `grantNotifications` becomes
   `grantRuntimePermissions` looping `MUSIC_RUNTIME_GRANTS`, a `List<Pair<String, (Boolean) -> Int>>` (`POST_NOTIFICATIONS` with
   `notificationGrantState`, `BLUETOOTH_CONNECT` with `bluetoothGrantState`) (the once-only
   `getPermissionGrantState != wanted` check stays, so repeated grants don't happen). The kid sees nothing: no dialog
   (the "admin granted" notification is for location only). **Withdrawal is real for `BLUETOOTH_CONNECT`**:
   `notificationGrantState(false)` is `PERMISSION_GRANT_STATE_DEFAULT`, which keeps the current grant and only unfixes
   it, so for this permission a missing policy sets `PERMISSION_GRANT_STATE_DENIED` (revokes and fixes it) through a
   pure `bluetoothGrantState(managed)`; a policy that returns grants it again. `POST_NOTIFICATIONS` keeps its
   rule. Without the grant the sheet shows only what it shows today. No scan, no `BLUETOOTH_SCAN`/`BLUETOOTH_ADMIN` in
   the music app.
   - **Names and addresses never leave memory.** Bonded device names and MACs (and the un-anonymised
     `AudioDeviceInfo` addresses) are never logged (on failure log only the exception's class and its cause's class,
     never `$device`, a name or an address), and never in the status report, `music_state`, `setNowPlaying`, the state
     recorder, `renderer.json` or the bridge. The address is only the row's key. The launcher's existing address log
     lines in `QuickControls` (:637, :656, :677, :690) go.
5. **Never**: Settings intents, the system output switcher (`ACTION_MEDIA_OUTPUT` - SystemUI/Settings, an escape),
   `setConnectionPolicy` (`@SystemApi`), anything that pairs from the music app: `createBond`, `startDiscovery`,
   `BluetoothAdapter.ACTION_REQUEST_ENABLE` (a system dialog), `enable()`, `BLUETOOTH_SCAN`/`BLUETOOTH_ADMIN` in the
   music manifest (pinned by `SourceScanTest`), `BluetoothDevice.connect`/`disconnect`/`isConnected` in `BluetoothAudio`.

## 3. Tests (required)

Launcher: `BluetoothAudioTest` (the pure filter, the states, routed-address matching, the LE-only case);
`bluetoothRowActions` (audio -> Connect/Disconnect + Forget, non-audio -> Forget and the ACL mark); a source check
that connect/disconnect reflect `BluetoothA2dp` (and `BluetoothHeadset.disconnect`), never `BluetoothDevice.connect`/
`disconnect` (`isConnected` only for non-audio), that `BluetoothAudio` has no `CountDownLatch`, `runBlocking` or latch
`.await(`, and that no log line in it or in `QuickControls`' Bluetooth functions interpolates a device name or address (the Wi-Fi lines
with `$ssid` stay); `KidScreensEscapeTest`
covering `BluetoothAudio`; the grant list and `bluetoothGrantState` in **`MusicMediaPlanTest`** (beside
`notificationGrantState`'s test), not `MusicPlanTest`.

Music app: the same `BluetoothAudioTest` (its copy); `headsetTapPlan` (on a room: no move at the tap; `OUTPUT` after
routing; nothing on failure); the sheet-state reducer (immediate `false`, CONNECTING -> DISCONNECTED, 10 s timeout,
late success); the sheet's rows (order, paired-not-routed after the routed outputs, "Kobler til…" then the failure
line, a connected-not-active row); a manifest/source scan (`SourceScanTest`): `BLUETOOTH_CONNECT` only, none of the
Never list, no Settings intent, no "Spotify", no `CountDownLatch`/`runBlocking`/latch `.await(` in the Bluetooth sources, no device value in a log (like the Storytel case: no log line in the
bluetooth sources interpolates a value); nb + en strings.

## 4. Device checks

**Emulator, API 34, 35 and 36 images (no headset needed)** - the claim is about three releases. Bluetooth is switched on
on the image (`adb shell cmd bluetooth_manager enable`; the app never does it).
1. `dumpsys package me.vibb.music | grep BLUETOOTH_CONNECT` shows `granted=true` with the policy-fixed flag after a
   sync; with the policy gone, `granted=false` (decision 4).
2. `BluetoothAudio.connect` on `adapter.getRemoteDevice("02:00:00:00:00:01")` (a debug-only trigger or a unit of the
   debug build; a made-up bonded address) returns `false`, not an exception (the UUID refusal).
3. logcat shows `Accessing hidden method Landroid/bluetooth/BluetoothA2dp;->connect(...) (unsupported, reflection,
   allowed)`.
4. In the state check 1 produces with the policy gone (`BLUETOOTH_CONNECT` denied and fixed), or after the grant is
   reset to default and `pm revoke` is run, the sheet shows only today's rows and nothing throws.

**Jelly Star** (the emulator's Bluetooth can't pair a real headset; Android 16 per `02-calls.md` - confirm with `adb shell getprop ro.build.version.release`,
`docs/testing/emulator.md`): record it, and
`adb shell getprop persist.bluetooth.maxconnectedaudiodevices` / `dumpsys bluetooth_manager` for the max connected
audio devices.
- A headset paired, last connected to another phone and switched on near ours: Kid Settings › Bluetooth shows it "not
  connected", Connect connects it and the mark follows (live, no reopening); the music app's sheet shows "<name> ·
  Koble til", a tap connects and music moves to it; switched off - "Fikk ikke kontakt med <name> - er den på?".
- Two paired headsets: with max 1 the second drops the first (and the music pauses, becoming-noisy); with more both
  stay connected and the new one becomes active (a tap on the inactive one reads "Bytt til" and switches).
- A room playing + Koble til to a switched-off headset: the room plays on; the same with the headset on: music
  moves to it.
- A call after Koble til rings in the headset (HFP followed); after Kid Settings' Disconnect it does not.
- Bluetooth off: no extra rows, nothing crashes.
- Kid Settings' marks for a connected non-audio device (a watch or keyboard still reads connected).
- `adb logcat` shows no non-SDK block (`(unsupported, reflection, allowed)` is the expected line).

## Revision (2026-10-20, after qa-52-design.md)

- **52-1** accepted: `@SystemApi` members are on the sdk list (our `removeBond` Forget proves it). §0/§1 corrected -
  `BluetoothDevice.connect()` is broken by the service's `BLUETOOTH_PRIVILEGED` + `MODIFY_PHONE_STATE` check and its
  `int` result, not by a block; `isConnected()` is reachable (ACL), kept for non-audio devices' mark, audio devices
  take theirs from the A2DP proxy (decision 2).
- **52-2** accepted: the helper is `suspend`, Bluetooth calls on `Dispatchers.IO`, the ready callback awaited off main;
  one proxy per screen / view model, opened on start, closed on stop; no LE Audio proxy (decision 1).
- **52-3** accepted: connect first; the room -> phone output switch is sent only once the headset is routed; a failed
  connect leaves the room playing (decision 3, `headsetTapPlan`).
- **52-4** accepted: rows are paired audio devices not routed now, matched by address; a tap on a connected-but-not-active
  one calls `setActiveDevice` (annotation named per release in §0); "the second drops the first" marked
  phone-dependent (decision 1, §4).
- **52-5** accepted: state in the music view model; the sheet stays open on a headset tap ("Kobler til…"), failure after
  `false` / CONNECTING -> DISCONNECTED / 10 s as "Fikk ikke kontakt med <name> - er den på?", a 3 s player hint (`HEADSET_NO_CONTACT`, a nameless string) when the
  sheet was closed, a late success clears it (decision 3).
- **52-6** accepted: names and addresses never in logs, report, `music_state`, now-playing, the state recorder or the
  bridge; a source-scan test in each app; the launcher's address log lines in `QuickControls` go (decision 4, §3).
- **52-7** accepted: pure `bluetoothRowActions`, live refresh on the A2DP / ACL broadcasts, the grant before the first
  list read, Disconnect also does `BluetoothHeadset.disconnect` (`@SystemApi`, `BLUETOOTH_CONNECT` only; confirmed in
  16/15/14 sources, §0), `BluetoothAudio` added to `guarded` (decision 2).
- **52-8** accepted: emulator checks on API 34/35/36 needing no headset (grant in `dumpsys package`, made-up bonded
  address returns `false`, the "unsupported, reflection, allowed" line, the revoked-permission path) plus the Jelly Star
  checks (§4).
- **52-9** accepted: switching headsets may pause playback (becoming-noisy, overlay 1); said in decision 3, expected in
  §4.
- **52-10** accepted: wired status row dropped ("wired: unchanged"); LE-only devices in Kid Settings only; "Koble fra"
  struck from the sheet; Bluetooth off adds nothing; the Never list extended; the feature is not gated on the Bluetooth
  switch (decisions 1-3, 5).
- **52-11** accepted: the withdrawal is real for `BLUETOOTH_CONNECT` (`PERMISSION_GRANT_STATE_DENIED` through
  `bluetoothGrantState`, since `DEFAULT` keeps the grant); the grant is the list `MUSIC_RUNTIME_GRANTS`, pinned in
  `MusicMediaPlanTest`; the kid sees nothing (decision 4, §3).
- **52-12** accepted: the UUIDs named (A2DP sink `0000110b-...`, ASCS `0000184e-...`); the two copies are not
  byte-identical, no contract file; the implementer updates design 26a §5.1's row order and `music/CLAUDE.md`'s output-axis
  line (phone, routed outputs, paired-not-routed "Koble til", rooms, "Se etter høyttalere"); long names ellipsize; the
  Tasker remark dropped.

## Implementation status (music app)

Built on branch `bt-b` (2026-10-20, from `main` 41cb9440); music suite 994 tests green with `-PwarningsAsErrors=true`,
`assembleDebug` built. No device check run yet.

- **Manifest**: `BLUETOOTH_CONNECT` only (no `usesPermissionFlags`); the launcher's `MusicSync` grant is implementer A's.
- **`bluetooth/BluetoothAudio.kt`** (pure): the UUIDs, `pairedAudioDevices(bonded, a2dpStates, routedAddresses)` (A2DP
  sink only - an LE-only device has no sink UUID and so no row; addresses compared upper case; sorted by name, nameless
  last), `actionFor` (CONNECTED -> `setActiveDevice`, else `connect`), `headsetRows`, the reducer `connectStep`
  (`HeadsetConnect` = `attempts: address -> Attempt(startedAt, fromKey, makeActive, connected)` + `failed`; events `Tapped`,
  `Answered`, `StateChanged`, `Routed`, `Tick`), `headsetTapPlan(fromKey, currentKey, routedNow)` and `failureWords` (the thrown
  class and its cause's class, `InvocationTargetException` unwrapped). Every holder of an address has a redacted
  `toString`; the events are plain classes.
- **`bluetooth/BluetoothLink.kt`** (Android): the A2DP proxy through `suspendCancellableCoroutine` +
  `withTimeoutOrNull(2 s)` on IO (Bluetooth off: no wait), a late proxy closed; `routed()` (address -> `AudioDeviceInfo.id`
  of the routed Bluetooth outputs), `pairedAudio`, `connect`/`makeActive` by reflection on `BluetoothA2dp`, all on IO;
  `closeLater` on a process scope (so `onCleared` can close).
- **The view model**: `headsets`, `headsetConnect`, the proxy held while the sheet is open or a call runs
  (`outputSheetOpened`/`outputSheetClosed` from a `DisposableEffect` in `OutputSheet`), the A2DP
  `ACTION_CONNECTION_STATE_CHANGED` receiver and an `AudioDeviceCallback` (registered at the first granted sheet,
  until `onCleared`), one 10 s tick per tap. A routed attempt made on a room sends its `dev:` key through
  `outputRequests` (collected in `MusicRoot` -> `actions.output`) and closes the sheet; a failure with the sheet closed
  emits `PlayerHub.Message.HEADSET_NO_CONTACT` (nameless, 3 s, its branch in `MusicRoot`). Without the grant nothing
  runs and the sheet is today's.
- **The sheet**: `outputSheetRows` (phone, routed, headsets, rooms, rescan); headset rows show the name (one line,
  ellipsized) over "Koble til" / "Bytt til" / "Kobler til…" / "Fikk ikke kontakt med <name> - er den på?" (two lines);
  the rows scroll above 70 % of the screen's height (more rows now). 26a §5.1 and `music/CLAUDE.md` updated.
- **Tests**: `BluetoothAudioTest` (filter, routed matching, LE-only, rows, the reducer's four ends - immediate `false`,
  CONNECTING -> DISCONNECTED, the window, late success - and `headsetTapPlan`), `OutputRowsTest` (order, no-grant rows,
  the view model's wiring), `SourceScanTest` `d52` (manifest, the Never list, no blocking wait, reflection on
  `BluetoothA2dp` with the two names only, no interpolating log line, nothing outside the sheet's files names the
  package, no address in `Output`/`PlayerHub`/`PlaybackService`, nb + en words), `FitTest` (the row's ellipsis, the scroll).

Deviations and choices:
- **No HEADSET proxy in the music app.** Decision 1 opens one with the A2DP proxy, but it serves only Kid Settings'
  Disconnect (the HFP disconnect); the sheet has no "Koble fra" and connecting needs no HFP step, so the music app
  would hold an unused proxy (and maybe its 2 s wait). Pinned by `SourceScanTest` (no `BluetoothProfile.HEADSET`).
- **On a room = a Sonos room is the output at the tap** (the attempt keeps its key, `fromKey`), playing or paused (decision 3 says "a room played"). A paused
  room's `OUTPUT` to the headset comes home paused (`toPhone` resumes only a playing speaker), so the headset the kid
  picked becomes the output without anything starting.
- **The receiver is `RECEIVER_EXPORTED`**: the broadcast is protected and comes from the Bluetooth stack's uid, which a
  not-exported dynamic receiver does not admit (only root/system/same-app); the routed-output callback and the 10 s
  window work without it.
- A failure line from an earlier sheet is cleared when the sheet opens again (a closed sheet's failure was the hint).
- A message from the headset (`HEADSET_NO_CONTACT`) ends a tap's instant-switch hold like every message (`GuardsTest`
  pins "every message ends the hold"); harmless.

### Fix round after QA's code review (qa-52-b-code, 2026-10-20)

- **1 (Medium)**: the attempt keeps the tapped room's output key (`fromKey`), and `headsetTapPlan(fromKey, currentKey,
  routedNow)` sends `OUTPUT` only while that room is still the output (`PlayerHub.state.value.output.key` at the
  routed moment): a room, the phone or wired headphones picked meanwhile keep their pick (two new cases, plus two more).
- **2**: `openA2dp` closes a stray proxy in a `finally` - after the timeout and after a cancellation (a view model
  cleared mid-wait), the wait marked abandoned so a later callback closes its own.
- **3**: a "Bytt til" (make-active) attempt's window ends quietly back to "Bytt til" (the device is connected); only
  its immediate `false` shows the failure line. The rows test changed with it, and a test for the immediate `false`.
- **4**: `SourceScanTest` pins the `RECEIVER_EXPORTED` registration (BroadcastSkipPolicy's "Exported Denial" for uid
  1002, the protected action), both unregister lines and the proxy's close in `onCleared`;
  `assertEquals(10_000L, CONNECT_WINDOW_MS)`; a new tap clears the old failure (its own test).
- **5**: `SourceScanTest` pins `getRemoteDevice` and `getMethod(...).invoke(` inside `callA2dp`'s `try`.
- **6**: each attempt's 10 s window is a `Job` per address, cancelled when the attempt ends sooner; a tap on an
  attempt still open asks again but keeps its window.
- **7**: `PlayerScreen.kt` and `OutputRows.kt` are in the "no `Log.`" scan.

Device checks owed (§4, the music half): on API 34/35/36 images - the sheet with the grant and Bluetooth on shows no
extra row for no bonded device and nothing throws; the revoked/denied grant shows today's sheet; logcat's
`Accessing hidden method Landroid/bluetooth/BluetoothA2dp;->connect` / `setActiveDevice` `(unsupported, reflection,
allowed)`; the A2DP broadcast reaches the app (`RECEIVER_EXPORTED`). On the Jelly Star - "<name> · Koble til" connects
and the music moves (phone renderer); a room playing + a switched-off headset: the room plays on and the line shows
(and the 3 s hint with the sheet closed); the same with the headset on: the room hands over once routed; "Bytt til"
on a second connected headset; Bluetooth off: no extra rows; a long name ellipsizes at 320 dp.
