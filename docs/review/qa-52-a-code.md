# Code QA - design 52, launcher half (Bluetooth headphones in Kid Settings, the music app's `BLUETOOTH_CONNECT`)

- Commit: `738c5cbf` on branch `bt-a`, on top of `41cb9440`.
  - New: `server/BluetoothAudio.kt`.
  - Kid Settings › Bluetooth (`BluetoothDevicesActivity`, `BluetoothDeviceAdapter`, `QuickControls`' Bluetooth
    functions) now goes through it.
  - `MUSIC_RUNTIME_GRANTS` / `bluetoothGrantState` (`MusicMediaPlan.kt`, `MusicSync`).
  - Two strings (nb + en).
  - Tests: `BluetoothAudioTest` (new), `MusicMediaPlanTest`, `KidScreensEscapeTest`.
- Read:
  - root and launcher `CLAUDE.md`: the new "Bluetooth headphones in Kid Settings (design 52)" section, Quick
    Controls, `MusicSync`, the re-grant gotcha;
  - `docs/design/52-bluetooth-headphones.md` whole, incl. "Implementation status (launcher)" and its differences;
  - `docs/design/qa-52-design.md`;
  - the full diff `41cb9440..738c5cbf`;
  - the AOSP copies (`aosp-bt/`, `r14/`, `r15/`, `r16/`): the annotations and signatures of
    `BluetoothA2dp.connect`/`disconnect`/`setActiveDevice` and `BluetoothHeadset.disconnect`, and the module's
    hidden-API override lists.
- Ran:
  - `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1477 tests, 0 failures, 0 errors, 0 skipped**
    (`BluetoothAudioTest` 12, `MusicMediaPlanTest` 15, `KidScreensEscapeTest` 2).
  - 16 one-line mutations against `*BluetoothAudio*`, `*MusicMediaPlan*`, `*KidScreensEscape*` and
    `*QuickControls*` (the last pattern matches no class). Each was reverted with `git checkout --`. The tree is clean
    apart from this file.

## Findings

**1 (High) - a blocked or missing hidden method crashes the launcher process; it does not fail soft.**
`server/BluetoothAudio.kt:282-283, 297, 300` evaluates `BluetoothA2dp::class.java.getMethod(...)` /
`BluetoothHeadset::class.java.getMethod(...)` as an *argument* to `call(...)`. That happens before `call`'s `try`
(`:311`).

- `getMethod` throws `NoSuchMethodException` in exactly the case design 52 §0 names: "Google may block it in a later
  release, then `getMethod` throws `NoSuchMethodException` and we fail soft". The same happens on an OEM build that
  lacks the member.
- The exception escapes `withContext(IO)`, `BluetoothAudio.connect`/`disconnect` and
  `QuickControls.connect…/disconnectBluetoothDevice`. It then reaches the `lifecycleScope.launch` in
  `onConnectTapped`/`onDisconnectTapped` (`BluetoothDevicesActivity.kt:205-230`), which has no handler.
- `Application`'s default handler then runs `exitProcess(1)` (`Application.kt:214-226`). That kills the launcher: the
  home screen, the device owner agent and everything else in its process.
- It is not reachable on 14/15/16 today: all three release copies carry `@UnsupportedAppUsage` without
  `maxTargetSdk`, and the override lists name none of the four methods. But this is the failure mode the design says
  is handled, and the brief's catch list includes it.

Fix:
- Move the lookup inside the `try`: `call(name: String, owner: Class<*>, proxy, device, what)` doing
  `owner.getMethod(name, BluetoothDevice::class.java).invoke(proxy, device) as? Boolean ?: false`.
- Add a source pin that every `getMethod(` in `BluetoothAudio.kt` is inside `call`.
- The music half (`bt-b`, `bluetooth/BluetoothLink.kt:161`) does the lookup inside its expression. Its reviewer should
  confirm that the expression sits inside a `try`.

**2 (Medium) - a profile proxy can leak when the opening coroutine is cancelled.** `awaitProxy` itself is right: a
proxy that lands after the 2 s wait, after a cancellation, or in the timeout-vs-resume race is closed by the
`finally` and the `GAVE_UP`/`DELIVERED` states. Two windows around it are not covered:

- `openProxies` (`:184-189`) awaits A2DP, then HEADSET. A cancellation during the HEADSET await (up to 2 s) throws out
  of `openProxies`, and the A2DP proxy it already holds is never closed.
- `withContext(Dispatchers.IO)` has prompt cancellation: if the caller is cancelled while the block runs, it throws
  `CancellationException` *even when the block returned a value*. A finished `Proxies` with both proxies is then
  dropped, and `Session.proxies` (`:169`) never sees it, so its "closed → close what lands" rule can't apply.
- Who cancels the opener:
  - the activity's destroy. Back pressed while the page is still opening: `onStop` closes the session, then
    `onDestroy` cancels `lifecycleScope` before the open lands.
  - `refreshOne`, which cancels an older refresh of the same row (`BluetoothDevicesActivity.kt:186`). An older refresh
    is the opener whenever an earlier open came back null, because `Session` retries a failed open on the next call.
- The implementation status says "a proxy that lands late or after the close is closed". That holds only for the
  single await.

Fix: in `Session.proxies`, open under `withContext(NonCancellable + Dispatchers.IO) { openProxies(app) }`. The wait is
bounded by 2 x 2 s, and whatever lands then always reaches the `keep`/`fresh.close()` step. Mutations 14 and 16 (below)
show that nothing pins the closing either.

**3 (Low) - an A2DP proxy that misses the 2 s wait is never retried for the page's visit.** `openProxies` returns
`Proxies(adapter, null, headset)` when only HEADSET arrived (`:189`). `Session` caches it, so for the rest of the visit:
- every audio row falls back to the ACL mark;
- Connect answers `false` at once ("Fikk ikke kontakt").

This is plausible right after Bluetooth was switched on, while the profile services are still starting. Fix: treat
`a2dp == null` as a failed open (close the HEADSET proxy, return null, retry on the next call). With finding 2's change
it costs nothing. Awaiting both proxies concurrently would also halve the worst-case 4 s first read.

**4 (Low) - a Connect tap during the page's own scan pages while discovery runs.** The page starts discovery on its
first start (`scan()`, ~12 s of inquiry). A Connect tap in that window calls A2DP `connect` while discovery is still
running. The platform says not to: "New connections to remote Bluetooth devices should not be attempted while
discovery is in progress" (`BluetoothAdapter.startDiscovery`).
- Paging is slowed, so the 10 s attempt can end in "Fikk ikke kontakt med <name> – er den på?" just before the headset
  connects.
- The late success clears the line again, so the result is a confusing flash, not a wrong final state.
- This is the most likely moment for a kid's first tap.

Fix: `adapter.cancelDiscovery()` on a Connect tap, on IO in `BluetoothAudio.connect` before the call. The scan's
receiver stays; `ACTION_DISCOVERY_FINISHED` then ends the "Søker…" line as usual.

**5 (Low) - test gaps: four lines of the glue are unguarded (mutations 5, 8, 13, 14/16 survive).**
- `failure(e)` has no test (13). Printing `t.message` passes the suite, and that message can name the device: for
  example `getRemoteDevice`'s `IllegalArgumentException` carries the address. The log-line scan trusts `failure(e)`
  by name, so this one function holds the privacy rule. Fix: a unit test with
  `InvocationTargetException(SecurityException("… AA:BB:CC:00:11:22 …"))` that expects
  `"SecurityException"` and no address. Also test a plain exception with a cause.
- `connect`'s routed input to `btTap` (5): `btTap(…, false)` passes, which turns a routed device's tap into
  `setActiveDevice` instead of a no-op. Fix: a source assert on `isRouted(address, routedAddresses(context))`.
- The attempt timer (8): `delay(30_000L)` passes, because the 10 s is only pinned on the constant. Fix: assert
  `delay(BT_ATTEMPT_TIMEOUT_MS)` in the activity.
- The proxy closes (14, 16): neither `Session.close`'s `p?.close()` nor the late-callback
  `closeProfileProxy` is pinned. Fix: source asserts next to the existing `session?.close()` one (finding 2).

**6 (Low) - `MusicSync` rewrites the grant on every sync until the music app declares `BLUETOOTH_CONNECT`.**
- For a music build without the permission (every build until `bt-b` ships), `getPermissionGrantState` stays
  `DEFAULT`, so `!= wanted` is always true.
- `setPermissionGrantState` is then called on every sync and returns `false`. No exception and no transparency
  notification: that is for sensors and location, not `BLUETOOTH_CONNECT`. So the re-grant gotcha's loop does not
  bite here.
- The cost is one binder round trip per sync, per installed music package.

Fix: accept it, or merge `bt-b` (the manifest line) before or with `bt-a`. Optionally skip a permission the package
doesn't request (`PackageInfo.requestedPermissions`).

**7 (Nit)**
- `Proxies.close()` (`:142-149`) closes both proxies inside one `try`. If the A2DP close throws, the HEADSET proxy
  stays open. Use one `try` each.
- The scan's `ACTION_FOUND` receiver now builds its row with `toInfo(null)` on the main thread. That reads `uuids` (a
  binder call) as well as the old `isConnected`. It is cheap and the pattern is older, but it is the one Bluetooth read
  left on main.

## The rules, with the evidence

1. **Reflection targets: right, apart from finding 1.**
   - Signatures: `BluetoothA2dp.connect`, `disconnect` and `setActiveDevice(BluetoothDevice)` are `public boolean`
     with `@UnsupportedAppUsage` and no `maxTargetSdk` on all three releases (r14 `:333/:379/:503`, r15
     `:330/:368/:467`, 16 `:339/:377/:476`). `BluetoothHeadset.disconnect(BluetoothDevice)` is `@SystemApi`,
     `public boolean` (r14 `:455`, r15 `:464`, r16 `:478`). The module's `hiddenapi-*.txt` lists name none of them.
   - The lookups use `getMethod(name, BluetoothDevice::class.java)` and invoke on the proxy objects (`a2dp`, `it` =
     headset), never on `BluetoothDevice`. `as? Boolean ?: false` reads the boxed `boolean` correctly.
   - `call` catches `Exception`. That covers `InvocationTargetException(SecurityException)`; `failure` unwraps
     `targetException`. A null proxy returns `false` before any call.
   - `BluetoothDevice.connect`/`disconnect`/`isConnected` are absent from `BluetoothAudio`. `isConnected` stays in
     `QuickControls` for the ACL mark. Mutations 1 and 2 are caught.
2. **Threading: holds.**
   - Every proxy, state, UUID, bonded and connect/disconnect call runs in `withContext(Dispatchers.IO)`.
   - Nothing blocks: no latch, `runBlocking` or `.await(` (pinned).
   - `onServiceConnected` arrives on main. It is handed over through a `synchronized` state machine
     (`WAITING`/`DELIVERED`/`GAVE_UP`/`CLOSED`/`IGNORED`) and `cont.resume`, so a repeated callback is ignored.
   - No deadlock with Bluetooth off: `isEnabled` is false, so the open returns null without waiting. A refused
     `getProfileProxy` resumes with null at once.
   - Android 14's synchronous receiver inside `connect` runs on IO.
   - Closing on stop: see finding 2.
3. **Privacy: holds in the code; the guard has a gap (finding 5).**
   - `failure(e)` prints `simpleName` and the cause's `simpleName` only.
   - Every log line in `BluetoothAudio` interpolates only `failure(e)`, `what` (a fixed label) or `profile` (an
     int).
   - `QuickControls`' pairing, forget and bonded-read lines log `failure(e)`. Its four address lines are gone. The
     scan code logs only booleans (`granted=`, `isEnabled=`, `returned`).
   - `BluetoothDeviceInfo.toString` names no device.
   - The activity and adapter have no logs. Their only device text is the row and the failure line on screen.
   - `MusicSync` logs the permission name.
   - Mutation 11 is caught.
4. **The grant: holds.**
   - `MUSIC_RUNTIME_GRANTS` = `POST_NOTIFICATIONS` with `notificationGrantState` (unchanged: GRANTED / DEFAULT) and
     `BLUETOOTH_CONNECT` with `bluetoothGrantState` (GRANTED / DENIED).
   - Both are looped over `MUSIC_PACKAGES` (`me.vibb.music`, `.debug`) that are installed, device owner only.
   - Each is set only when `getPermissionGrantState != wanted`, so a set state is never written twice. The launcher's
     own `BLUETOOTH_CONNECT` goes through `selfGrantPermission`, which checks `checkSelfPermission` first.
   - Mutations 9 and 15 are caught. The one wrinkle is finding 6.
5. **Kid Settings: holds, apart from findings 2-4.**
   - The grant is in `onCreate`, before the first list read in `onStart`. Mutation 10 is caught.
   - One receiver (bond, A2DP connection state, ACL connected/disconnected) is registered in `onCreate` and
     unregistered in `onDestroy`. The scan's receiver is unregistered through `stopBluetoothScan` in `onDestroy`.
   - `RECEIVER_NOT_EXPORTED` is fine for these broadcasts in practice. The scan's `ACTION_FOUND` receiver, sent by the
     same Bluetooth process, has been `NOT_EXPORTED` since `b9a332f4` (2026-08-03), and scan and pair were
     live-verified the next day. The live row update is still a device check (design 52 §4).
   - `bluetoothRowActions`: Connect (OFF) / Disconnect (CONNECTED) only for `audio`; Forget for every bonded device;
     pair only for an unbonded one; nothing while busy (pinned).
   - The attempt timer is one job per row in `lifecycleScope`. It is cancelled by a new tap, by success or failure,
     by Forget and on destroy, but **not on stop**. That is acceptable:
     - a tap followed by Home ends as `false` (the session is closed) and shows the failure line;
     - a connected read on return (`settle`) clears it.
   - A scan and a connect do fight a little (finding 4).
6. **The escape test: holds.** `server/BluetoothAudio.kt` is in `guarded` and in the must-be-found names. A
   `Settings.ACTION_BLUETOOTH_SETTINGS` added to it fails the test (mutation 12). The diff adds no Settings intent,
   `ACTION_REQUEST_ENABLE` or `enable()`.
7. **Strings: hold.**
   - nb: "Kobler til …" and "Fikk ikke kontakt med %1$s – er den på?".
   - en: "Connecting…" and "Couldn't reach %1$s – is it switched on?" (apostrophe escaped).
   - The en dash in place of the note's " - " is fine.
   - The name falls back to the address when a device has none, which is on screen only.
8. **Docs:** the launcher `CLAUDE.md` section, the corrected gotcha (`BluetoothDevice.connect` vs the A2DP proxy), the
   "not device-tested" line and PLAN's status match the code. One exception is finding 2's "closed when it lands".

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| 1 | `getMethod("connect", BluetoothDevice::class.java)` -> `getMethod("connect")` | caught - `BluetoothAudioTest` reflected methods |
| 2 | HFP disconnect reflects `BluetoothA2dp` instead of `BluetoothHeadset` | caught - reflected methods |
| 3 | `btLink`: `leAudio && aclConnected` -> `aclConnected` (ACL counts for a plain A2DP device) | caught - "an audio device's state is A2DP's" |
| 4 | `btTap`: a CONNECTED, not routed device -> `CONNECT` (no `setActiveDevice`) | caught - the tap test |
| 5 | `connect` passes `false` for routed into `btTap` | **NOT caught** (finding 5) |
| 6 | `btAttemptStep`: CONNECTING -> DISCONNECTED -> `null` | caught - the attempt test |
| 7 | `BT_ATTEMPT_TIMEOUT_MS` 10 s -> 30 s | caught - the attempt test |
| 8 | the activity's timer `delay(BT_ATTEMPT_TIMEOUT_MS)` -> `delay(30_000L)` | **NOT caught** (finding 5) |
| 9 | `bluetoothGrantState(false)` -> `DEFAULT` | caught - `MusicMediaPlanTest` |
| 10 | the grant moved from `onCreate` to after `scan()` in `onStart` | caught - grant order |
| 11 | `Log.w(TAG, "No such device $address: …")` | caught - the log-line scan |
| 12 | `Settings.ACTION_BLUETOOTH_SETTINGS` in `BluetoothAudio.kt` | caught - `KidScreensEscapeTest` |
| 13 | `failure(e)` prints `t.message` | **NOT caught** (finding 5) |
| 14 | `Session.close` no longer closes the open proxies | **NOT caught** (findings 2, 5) |
| 15 | `MusicSync` sets the grant without comparing (`true \|\| …`) | caught - `MusicMediaPlanTest` |
| 16 | a proxy landing after the wait is not closed (`CLOSED -> Unit`) | **NOT caught** (findings 2, 5) |

## Verdict

**Approved with fixes.**
- Before the merge:
  - finding 1 (move `getMethod` inside the `try`; one line per call site plus a pin). It is High because it turns
    the design's own "fail soft" case into a launcher process exit.
  - finding 2 (`NonCancellable` around the open).
  - finding 5's four source asserts and the `failure` test.
- Findings 3 and 4 are small and worth doing in the same pass. Finding 6 is settled by merging `bt-b` with or before
  `bt-a`.
- The reflection targets, threading, privacy, grant rules, rows, receivers, escape scan and strings are otherwise as
  the note and its revision decided.
- Device checks are owed per design 52 §4.
