# Code QA - design 52, the music app's half ("Spill av på"'s Bluetooth headset rows)

- Commit: `8ff3a57b` on branch `bt-b` (one commit on `main` 41cb9440): `bluetooth/BluetoothAudio.kt` (pure),
  `bluetooth/BluetoothLink.kt` (Android glue), the view model's connect state, receiver and routed-output callback,
  `OutputRows.kt` + `OutputSheet`, `PlayerHub.Message.HEADSET_NO_CONTACT`, the manifest's `BLUETOOTH_CONNECT`, nb + en
  strings, `BluetoothAudioTest`, `OutputRowsTest`, `SourceScanTest` `d52`, `FitTest`; 26a §5.1 and `music/CLAUDE.md`.
- Read: root and `music/CLAUDE.md` (the renderer and output axes, `RendererChoice`, the room switch, `SourceScanTest`,
  the privacy rules); `52-bluetooth-headphones.md` whole (incl. "Implementation status (music app)" and its
  deviations); `qa-52-design.md` (52-3, 52-5, 52-6 in particular); 26a §5.1; `PlaybackService`'s `onCustomCommand`,
  `output`, `toPhone`, `comeHome`, `handOff`, `setOutput` and the device callback; `MusicRoot`'s message and sheet
  wiring; the full diff.
- AOSP (read-only, `android14/15/16-release`): `frameworks/base` `BroadcastSkipPolicy.java`,
  `BroadcastController.java` (16; registration and the protected-broadcast send check), `ActivityManager.java`
  (`checkComponentPermission`, `canAccessUnexportedComponents`), `core/res/AndroidManifest.xml`; `packages/modules/
  Bluetooth` `android/app/AndroidManifest.xml` and `a2dp/A2dpStateMachine.java`.
- Ran: `testDebugUnitTest -PwarningsAsErrors=true --rerun` (full music suite): **BUILD SUCCESSFUL, 994 tests, 0
  failures, 0 errors, 0 skipped**. Then 17 one-line mutations and 1 extra move (finding 5) against the targeted tests (`*BluetoothAudio*`,
  `*OutputRows*`, `*SourceScan*`, `*FitTest*`), each reverted (`git checkout -- app/src/main`); the tree is clean but
  for this file.

## Findings

**1 (Medium) - a routed attempt hands the output over even after the kid picked another output meanwhile.**
`ui/MusicViewModel.kt:221-233` (`reduce`): the `OUTPUT` is decided only from `Attempt.onRoom`, captured at the tap
(`MusicRoot.kt:162`). Nothing checks the output when the headset is routed. Scenario: the kitchen plays, the kid taps
"Buds · Koble til" (the sheet stays open on "Kobler til…"), changes their mind and taps "Stua" (a pick: the sheet
closes, the room switch runs); four seconds later the buds connect and `reduce` sends `OUTPUT dev:<buds>` - Stua is
handed off and the story moves to the buds the kid walked away from. The same after a pick of the wired headphones.
(After a pick of "Telefonen" the extra `dev:` is benign: the device callback would route to the headset anyway.) The
design's rule is "the tap was made while a room played ... once"; the brief's "never after the kid picked another
output meanwhile" is not held. The tests can't see it: `headsetTapPlan(onRoom, routedNow)` has no input for it.
Fix: `Attempt` carries the output key at the tap (`fromKey`, the room's `sonos:<uid>`, null on the phone), and the
plan becomes `headsetTapPlan(fromKey, currentKey, routedNow)` = OUTPUT only when `fromKey != null && fromKey ==
currentKey` (`currentKey` = `PlayerHub.state.value.output.key` in `reduce`); two `BluetoothAudioTest` cases (another
room picked, the phone picked). Optional: `outputRequests` is a buffered channel collected by a `LaunchedEffect`, so a
request made during an Activity recreation is sent later; checking the key again at the collect costs one line.

**2 (Low) - `openA2dp` leaks the proxy when its caller is cancelled mid-await.** `bluetooth/BluetoothLink.kt:57-73`.
The stray-proxy cleanup runs only when `withContext` returns `null` (the 2 s timeout). On a cancellation -
`viewModelScope` is cancelled before `onCleared`, so a view model cleared within 2 s of a sheet open or a tap - the
`withContext` throws: `wait.abandoned` stays false, the late `onServiceConnected` stores the proxy in `wait.proxy`
and nobody closes it (`onCleared`'s `closeLater` sees `a2dp == null`, it was never assigned). The same if the callback
resumed the continuation and the cancellation then dropped the value (withContext's prompt cancellation). The
comment ":66 the value was dropped by a cancellation" claims the case but the code never reaches it. Rare and small
(one profile proxy binding in a process the playback service keeps alive), but the brief asked "never leaked when
the view model clears mid-await". Fix: `try { ... } finally { if (got == null) { abandon; close the stray } }`
(or `catch (e: CancellationException) { abandon/close; throw e }`); `closeQuietly` doesn't suspend, so it is safe in
the `finally`.

**3 (Low) - a "Bytt til" that doesn't switch in 10 s reads "Fikk ikke kontakt med <name> - er den på?" for a device
that is connected.** `BluetoothAudio.kt` `connectStep` `Tapped` opens every attempt with `connected = false`; for a
MAKE_ACTIVE row no A2DP CONNECTED broadcast comes (the device already is), so if `setActiveDevice` is asked (`true`)
but the route doesn't move within the window, the `Tick` fails it with the "is it switched on?" line - the lie 52-4
named. `BluetoothAudioTest` "rows - Kobler til after a tap ..." pins this behaviour (`NO_CONTACT` for `on`). Fix: the
attempt carries its action; a MAKE_ACTIVE window's end clears quietly (the row goes back to "Bytt til"), an immediate
`false` still shows the line. Change the test with it.

**4 (Low) - load-bearing lines with no test (mutations g, m, o, p survive).**
- `Context.RECEIVER_EXPORTED` (`MusicViewModel.kt:296`): switching it to `RECEIVER_NOT_EXPORTED` - the reflex of the
  next reader who sees "exported" - leaves every test green and silently drops every A2DP broadcast (see deviation 3
  below): the CONNECTING -> DISCONNECTED failure and the CONNECTED -> "Bytt til" step would be gone, only the 10 s and
  the routed callback left.
- `CONNECT_WINDOW_MS = 10_000L`: every test uses the constant, so 30 s passes.
- `onCleared`'s `unregisterReceiver(a2dpWatch)`: removable with the suite green (a receiver holding a cleared view
  model).
- (smaller) `Tapped` clearing the old failure (mutation o): the rows mask it while the attempt is open, and CONNECTED
  and routed clear it too, so only a quiet window end after a re-tap would show the stale line.

Fix: in `SourceScanTest` `d52`, one assert on the registration line (with a comment naming `BroadcastSkipPolicy`'s
"Exported Denial") and one on both unregister lines in `onCleared`; `assertEquals(10_000L, CONNECT_WINDOW_MS)` in
`BluetoothAudioTest`.

**5 (Low) - the reflection is inside the `try` (no crash), but nothing keeps it there (mutation r survives).**
Asked after the launcher half's High. `bluetooth/BluetoothLink.kt:155-167` `callA2dp`: `adapter?.getRemoteDevice`,
`getMethod(method, BluetoothDevice::class.java)` and `invoke` all run inside `try { ... } catch (e: Exception)`; a
blocked hidden method (`NoSuchMethodException`), a revoked grant (`InvocationTargetException(SecurityException)`), a
bad address (`IllegalArgumentException`) and a closed proxy are all `Exception`s and come back as `false` + a
classes-only log line. It is the only reflective call in the music app (`getMethod` pinned to that one occurrence).
The other Bluetooth calls are wrapped too: `getProfileProxy` (`awaitA2dp`), `closeProfileProxy`, `routed()`,
`pairedAudio()` (`alias`/`uuids`/`getConnectionState`); `adapter.isEnabled` in `openA2dp` needs no permission.
Catching `Exception` (not `Throwable`) is right: reflection reports a hidden-API block as `NoSuchMethodException`,
never an `Error`. What an escape would do: `viewModelScope` is `SupervisorJob() + Main.immediate` with no
`CoroutineExceptionHandler`, so the exception would pass through `withContext` into the `launch` in `headsetTapped`
and reach the main thread's uncaught-exception handler - the process dies, and with it the playback service (the
story stops, on the phone or the room's session). So this would be High if it regressed; today it holds. Moving the
`getMethod` above the `try` (as the launcher had it) leaves every test green. Fix: one `SourceScanTest` d52 assert
that `callA2dp`'s body has the `getMethod(...).invoke(` between its `try {` and `catch (e: Exception)`.

**6 (Nit) - the 10 s tick per tap is not cancelled when the attempt ends.** `MusicViewModel.kt:215-218`. Harmless as
written: `Tick` ends only attempts with `now - startedAt >= 10 s`, so a stale tick never ends a younger attempt (a
re-tap after an end opens a new `startedAt`; a re-tap on an open attempt keeps the old one and the old tick ends it
on time), and `delay` runs on uptime while the stamp is `elapsedRealtime`, so a tick is never early. A `Job` per
address cancelled on the end would make it explicit; not required.

**7 (Nit) - the log scan covers `bluetooth/` and the view model, not the sheet's own files.** `PlayerScreen.kt` and
`OutputRows.kt` hold names (they are in the test's `readers` set) but are not scanned for `Log.`; add them to the
view model's "no `Log.`" loop, or scan `readers` for an interpolated log.

**8 (Nit) - deviation 5 (`HEADSET_NO_CONTACT` ends a switch hold): agreed, harmless** - the hold ends 3 s early at
worst, and only while a failure arrives with the sheet closed.

## The checks, with the evidence

1. **Deviation 3 - `RECEIVER_EXPORTED` is right; `RECEIVER_NOT_EXPORTED` would receive nothing.**
   - The sender is the Bluetooth app: `A2dpStateMachine.broadcastConnectionState` (16 :743-752) calls
     `mA2dpService.sendBroadcast(intent, BLUETOOTH_CONNECT, ...)` in `com.android.bluetooth`, whose manifest has
     `android:sharedUserId="android.uid.bluetooth"` (uid 1002).
   - The skip rule (`BroadcastSkipPolicy`, 16 :582, 15 :604, 14 :567): `if (!filter.exported &&
     checkComponentPermission(null, callingPid, callingUid, filter.receiverList.uid, false) != GRANTED)` -> "Exported
     Denial ... not specifying RECEIVER_EXPORTED". `checkComponentPermission` grants a not-exported target only to
     `canAccessUnexportedComponents` (app id ROOT or SYSTEM = 1000) or the same app. 1002 is neither, so a
     not-exported receiver never sees the broadcast. There is no protected-broadcast exemption in the skip path.
   - Exported widens nothing: the action is a `<protected-broadcast>` (`core/res/AndroidManifest.xml:225`), and
     `BroadcastController` (16 :1033-1070) throws `SecurityException` at any sender that isn't root, system, phone,
     bluetooth, nfc, se, network stack or a persistent (system-image) app. A spoofed broadcast from another app can't
     be sent at all; the intent is also sent with `BLUETOOTH_CONNECT` as the receiver's required permission, and a
     dynamic receiver can't be addressed by component. Even no flag would do: a filter of protected actions only is
     registered exported (`BroadcastController` :467-468) and needs no flag.
   - The same holds for the launcher's Kid Settings receiver (A2DP + `ACTION_ACL_*`, also sent by uid 1002): it must
     be exported (or flagless) too.
2. **The room hand-over: once, only routed, never on failure - holds; "not after another pick" - doesn't (finding 1).**
   `OUTPUT` comes only from `reduce` on a `Routed` step, which ends the attempt (`s.attempts - ended.keys`), so never
   twice; `Answered(false)`, the CONNECTING -> DISCONNECTED edge and the `Tick` remove the attempt without `routed`, so
   never on a failure; a late `Routed` after a failure finds no attempt and only clears the line (tested). The key is
   `routedIds[address]`, set just before the `Routed` reduce, as `Output.deviceKey(id)`. It goes `MusicRoot` ->
   `actions.output` -> the session's `OUTPUT` -> `output(key)` -> `toPhone(resume = null)` under `switching.withLock`;
   `toPhone` plays on only with `wasPlaying && playAllowed(suspendedNow())`. The existing gated path.
   **Deviation 2 (a paused room hands over paused): accepted.** It is exactly 26a's room -> phone rule (`toPhone`
   resumes only a speaker that was PLAYING), nothing starts, the room's Stop is ours-gated, and the kid's pick wins:
   leaving a paused room as the output would make the next Play start the living-room speaker, not the headset the kid
   just connected. The status section records it.
3. **Threading and leaks - mostly holds; one leak (finding 2).** The proxy is awaited with
   `suspendCancellableCoroutine` inside `withTimeoutOrNull(2 s)` on IO, never blocking; Bluetooth off skips the wait;
   a proxy that arrives after the timeout is closed (the `Wait` lock covers the race both ways). Every Bluetooth read
   and call is on IO; the view model's state is touched on main only (`viewModelScope` is `Main.immediate`, the
   receiver and the audio callback are delivered on main). The proxy closes at the sheet's dismiss unless a call runs
   (`calls`), after a call, after a refresh that finds the sheet closed, and in `onCleared` (`closeLater` on a process
   scope). The receiver and `AudioDeviceCallback` are unregistered in `onCleared` (untested, finding 4). No
   `CountDownLatch`, `runBlocking`, `.await(`, `Thread.sleep`, `.get()` (pinned). The tick: finding 6. The reflection: finding 5.
4. **Privacy - holds.** Every holder of an address has a redacted `toString` (`Bonded`, `PairedAudio`, `HeadsetRow`,
   `HeadsetConnect`), the events are plain classes, `Attempt` and `ConnectStep` hold none; `SheetItem.Headset` prints
   the redacted row. The only log calls are `failureWords` (class names only; messages, which may carry an address,
   are dropped). `Output`, `PlayerHub`, `PlaybackService` carry no address (pinned); the only thing sent on is the
   `dev:<id>` key; `HEADSET_NO_CONTACT` is nameless. No file outside the sheet's five names the package or
   `BluetoothDevice` (pinned), so nothing reaches `music_state`, the report, now-playing, `renderer.json`, the recorder
   or the bridge. The `d52` pins catch an interpolated log (mutation l) and an un-redacted holder (mutation j); the gap
   is finding 7.
5. **The sheet - holds, with finding 3.** `outputSheetRows` = phone + routed (`hub.outputs`), headsets, rooms, rescan
   (pinned, mutation k). nb "Koble til" / "Bytt til" / "Kobler til…" / "Fikk ikke kontakt med %1$s - er den på?" and
   the nameless hint; en "Connect" / "Switch to" / "Connecting…" / "Couldn't reach %1$s - is it switched on?" and
   "Couldn't reach the headphones - are they switched on?". `connectStep`: a tap opens (a second tap is a no-op) and
   clears the failure; immediate `false` fails; CONNECTING -> DISCONNECTED fails only with an open attempt; CONNECTED
   marks the attempt (row "Bytt til") and clears a failure; routed ends it as success; the window fails an
   unconnected attempt at exactly 10 s; a late success clears without a move - each caught by a mutation. "Bytt til"
   calls `setActiveDevice` (`actionFor`). A failure with the sheet closed emits `HEADSET_NO_CONTACT` (3 s, `MusicRoot`
   branch). **Deviation 4 (the failure cleared on reopen): fine** - a closed sheet's failure was already said by the
   hint, and an open attempt's "Kobler til…" survives the reopen. **No grant: today's sheet exactly** - no rows, no
   watch, no proxy; the new scroll column keeps the sheet's 8 dp spacing, so the layout is unchanged below 70 % of
   the height.
6. **Deviation 1 (no HEADSET proxy): accepted.** The HEADSET proxy serves only Kid Settings' HFP disconnect; the sheet
   has no "Koble fra" and `PhonePolicy` brings HFP up after A2DP by itself. Pinned (`BluetoothProfile.HEADSET` absent).
7. **Never list - holds.** Manifest: `BLUETOOTH_CONNECT` the only Bluetooth permission (debug manifest none); no
   `createBond`, discovery, enable, `setConnectionPolicy`, output switcher, `MediaRouter`, `BluetoothLeAudio`, Settings
   intent; reflection only `BluetoothA2dp.getMethod(method, BluetoothDevice)` with the two names (pinned).

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `pairedAudioDevices` keeps the routed device (`.filter { true }`) | caught - `BluetoothAudioTest` routed matching |
| b | `actionFor`: CONNECTING also MAKE_ACTIVE | caught - routed matching, rows (2 tests) |
| c | `headsetTapPlan` = OUTPUT on `onRoom` alone (moves at the tap / on failure) | caught - `headsetTapPlan` |
| d | `Answered(false)` fails a CONNECTED attempt too | caught - "CONNECTED ends Kobler til" |
| e | any `-> DISCONNECTED` edge fails (not only from CONNECTING) | caught - "CONNECTING to DISCONNECTED" |
| f | the window ends at `>` 10 s, not `>=` | caught - 3 tests |
| g | `CONNECT_WINDOW_MS = 30_000L` | **NOT caught** (finding 4) |
| h | a late `Routed` after the failure line hands the room over | caught - "a late success ... moves no room" |
| i | the view model's plan uses `routedNow = step.failed.isEmpty()` | caught - `OutputRowsTest` view model wiring |
| j | `PairedAudio` without its redacted `toString` | caught - "no holder prints a name or an address" |
| k | rooms before the headset rows | caught - `OutputRowsTest` order |
| l | `callA2dp`'s failure log appends the address | caught - `SourceScanTest` d52 "no device name or address" |
| m | `RECEIVER_EXPORTED` -> `RECEIVER_NOT_EXPORTED` | **NOT caught** (finding 4) |
| n | the closed-sheet hint also with the sheet open | caught - `OutputRowsTest` view model wiring |
| o | a new tap doesn't clear the old failure | **NOT caught** (finding 4; masked in the rows while the attempt is open) |
| p | `onCleared` without `unregisterReceiver(a2dpWatch)` | **NOT caught** (finding 4) |
| q | a HEADSET proxy opened beside the A2DP one | caught - `SourceScanTest` d52 glue |
| r | (extra) `getMethod` hoisted above `callA2dp`'s `try` (the launcher's High) | **NOT caught** (finding 5) |

(A duplicated `getMethod` outside the `try` is caught by the exact-list pin; only a true move survives.)

## Verdict

**Approved with fixes.** No High. Before the merge: finding 1 (the hand-over must check the output is still the
tapped room - a real wrong move, small fix with two test cases) and finding 4's `RECEIVER_EXPORTED` pin (the
deviation is right, and only a pin keeps it right). Findings 2, 3 and 5's pin are small and fit the same round; 6-8 can stay.
Deviation 3 is settled from the sources; the device check "the A2DP broadcast reaches the app" stays on the list.
