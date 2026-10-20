# 52 - Bluetooth headphones: connect a paired device from Kid Settings and from the music app (proposal)

Asked 2026-10-20 (the user: "ville det vært mulig å la paring/bt connect av eksisterende parede enheter dukke opp i
musikk appen? ... er det noe robusthet og enkelhet av å sikre at bt headsets kobler seg til for avspilling av musikk vi
kan forenkle gjennom musikk appen" and, after a first answer that it can't be done: "Litt rart at ikke apper kan få gjøre
det? Er du sikker? Sjekk andre prosjekter please"). Launcher (Kid Settings) and the music app; no server change.

## 0. Facts (AOSP `android16-release`, packages/modules/Bluetooth, read 2026-10-20)

- `BluetoothA2dp.connect(BluetoothDevice)` / `disconnect(BluetoothDevice)` (framework `BluetoothA2dp.java` ~:339/:377):
  `@hide`, `@RequiresPermission(BLUETOOTH_CONNECT)`, `@UnsupportedAppUsage` **without** `maxTargetSdk` - reachable by
  reflection from an app targeting 36 (the "unsupported" non-SDK list: allowed, logged; Google may block it in a later
  release, then the call throws and we fail soft). The service (`A2dpServiceBinder.getServiceAndEnforceConnect`)
  checks only service availability, `checkCallerIsSystemOrActiveOrManagedUser` and `BLUETOOTH_CONNECT` for data
  delivery. `A2dpService.connect` refuses a device with `CONNECTION_POLICY_FORBIDDEN` or without the A2DP sink UUID,
  and when only one sink may be connected disconnects the current one first. Tasker's "Bluetooth Connection › Connect"
  (no root) is the same kind of call.
- `BluetoothDevice.connect()` (:2278): `@SystemApi`, `allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED,
  MODIFY_PHONE_STATE}`, returns an `int`. `BluetoothDevice.isConnected()` (:2344): `@SystemApi` (not on the unsupported
  list). Neither is usable by us.
- Public and enough for the state: `BluetoothAdapter.getBondedDevices()`, `BluetoothProfile.getConnectedDevices()` /
  `getConnectionState(device)` on the A2DP (and LE Audio) proxy from `getProfileProxy`, `BluetoothDevice.getUuids()`,
  `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` for what is routed now. All need `BLUETOOTH_CONNECT` (runtime).

## 1. Today

- **Kid Settings › Bluetooth** (`ui/quickcontrols/BluetoothDevicesActivity`, `server/QuickControls.kt`): scan, pair,
  forget work; **Connect/Disconnect call `BluetoothDevice.connect()`/`disconnect()` by reflection** (:670-694) - the
  `@SystemApi` methods above: a `SecurityException` (or a blocked-API `NoSuchMethodException`) caught and logged, and
  the `int` result read `as? Boolean` -> `false` anyway. The "connected" mark comes from `isConnectedReflective()`
  (:500), the blocked `isConnected()` -> always `false`. So on Android 14+ Connect never worked and every device reads
  "not connected" (not device-tested before; the launcher's CLAUDE.md lists it as untested).
- **The music app's "Spill av på" sheet** (`ui/PlayerScreen.kt` `OutputSheet` :347): the phone, the **connected**
  Bluetooth and wired outputs (from the routed audio devices), the Sonos rooms. A paired headset that is off or
  connected elsewhere isn't there. Wired headphones route by themselves on plug-in (no connect step).

## 2. Decisions

1. **One small pure + glue helper per app, the same rules** (each app has its own copy; no new bridge call):
   `BluetoothAudio` with `pairedAudioDevices(bonded, uuids, a2dpConnected, leConnected)` (pure: a bonded device with an
   A2DP sink or LE audio UUID, name or address, its state CONNECTED / CONNECTING / OFF by the proxy's
   `getConnectionState`), `connect(device)` / `disconnect(device)` through the A2DP proxy's hidden methods by
   reflection (`getProfileProxy(A2DP)`, awaited at most 2 s, closed after; a `Throwable` caught, logged by class),
   and **not LE Audio**: `BluetoothLeAudio.connect`/`disconnect` (`BluetoothLeAudio.java` :827/:863) are `@hide`
   without `@UnsupportedAppUsage`, i.e. blocked for reflection at target 36 - an LE-only device (rare; LE headsets also
   speak A2DP) is listed with its state but gets no Connect. The result is "asked" - the connection state
   broadcast (`BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`) is the truth; the UI shows "Kobler til…" until it comes
   or 10 s pass, then "Fikk ikke kontakt - slå på hodetelefonene".
2. **Launcher, Kid Settings**: `QuickControls.connectBluetoothDevice`/`disconnectBluetoothDevice` go through
   `BluetoothAudio`; the device list's "connected" mark comes from the proxies (`isConnectedReflective` goes). Only
   audio devices get Connect/Disconnect; others (a watch, a keyboard) keep Forget only. `KidScreensEscapeTest` and
   the Settings gate are unchanged (no Settings intent).
3. **Music app, "Spill av på"**: under the connected outputs, the **paired audio devices that aren't connected** as
   rows "<name> · Koble til" (the headphones glyph, dimmed subtitle); a tap connects (row subtitle "Kobler til…"); when
   the device connects Android routes media to it and the sheet's existing connected row takes over (the phone's
   playback moves with the route as today; a Sonos room stays a room - picking the headset row while a room plays
   first switches the renderer back to the phone, as picking "Telefon" does). A connected Bluetooth row gets a small
   "Koble fra" action only if the kid needs it (proposal: no - the headset's own button does it; drop unless wanted).
   Wired headphones: a status row "Hodetelefoner med ledning · i bruk" while plugged in (already shown as an output),
   nothing else. Pairing new devices stays in Kid Settings (the system's pairing dialog).
4. **Permission**: the music app declares `BLUETOOTH_CONNECT` (`usesPermissionFlags` not needed for connect); the
   launcher's `MusicSync` grants it by policy while a policy exists, beside `POST_NOTIFICATIONS`
   (`setPermissionGrantState`, the same grant/withdraw rules, no dialog for the kid). Without the grant the sheet shows
   only what it shows today. No scan, no `BLUETOOTH_SCAN` in the music app.
5. **Never**: Settings intents, the system output switcher (`ACTION_MEDIA_OUTPUT` - SystemUI/Settings, an escape),
   `setConnectionPolicy` (`@SystemApi`), anything that pairs from the music app.

## 3. Tests (required)

Launcher: `BluetoothAudioTest` (the pure filter and states; a source check that the connect/disconnect path reflects
`BluetoothA2dp` and never `BluetoothDevice.connect`/`isConnected`), the Kid Settings model (Connect only for audio
devices). Music app: the same `BluetoothAudioTest` (its copy), the sheet's rows (paired-not-connected after the
connected outputs, "Kobler til…" then the failure line, a room playing switches to the phone first), `MusicSync`'s
grant in the launcher's `MusicPlanTest`; `SourceScanTest`: no Settings intent, no "Spotify"; nb + en strings.

## 4. Device checks

Jelly Star (the emulator's Bluetooth can't pair a real headset): a headset paired, last connected to another phone and
switched on near ours - Kid Settings › Bluetooth shows it "not connected", Connect connects it and the mark follows; the
music app's sheet shows "<name> · Koble til", a tap connects and music moves to it; switched off - "Fikk ikke kontakt";
two paired headsets - connecting the second drops the first (A2DP's one sink); `adb logcat` shows no non-SDK block
(`Accessing hidden method ... (unsupported, reflection, allowed)` is the expected line).
