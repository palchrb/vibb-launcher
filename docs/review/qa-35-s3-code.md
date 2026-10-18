# Code QA: design 35 step 3 (launcher: battery, low-battery receiver, emergency-call sync)

Commit under review: `ef9a4bfd` (branch `notif-s3`, base `main` `afa753c2`). Reviewer branch `qa-notif-s3`.
Checked against `docs/design/35-parent-notifications.md` §2.5, §9 step 3 and its "Step 3 implementation status",
`server/src/parent_events.rs` `new_emergency_call`, `launcher/CLAUDE.md` (Calls & SMS, Kiosk escapes, Push).

**Verdict: ready to merge.** No High or Medium findings. The deviation (one reported instant per emergency call) is
correct and needed. It does not move the callback window or weaken the self-update gate. The four Low items are a
narrow lost-sync race, a fallback that isn't stable across a restart, and two test gaps on guards the mutations showed
nothing pins. They can go in a short fix round or the backlog.

## Build

`./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug -PwarningsAsErrors=true` (under
`flock ~/build.lock`): **BUILD SUCCESSFUL, 1344 unit tests, 0 failures, 0 skipped.** The step adds 11 tests:
`BatteryReportTest` 6 and `EmergencyConnectsTest` 5, plus one assertion in `SyncScheduleTest`. Lint reports nothing in
`BatteryStatus.kt`, `EmergencyConnects.kt`, `KidInCallService.kt` or `CallSystem.kt`. Its 46 errors are older ones in
other files.

## What was checked and holds

- **`BatteryReport.of`** matches §2.5. The level is `round(level*100/scale)` (`roundToInt`, so halves round up),
  clamped to 0..100. Charging is `plugged != 0` or status CHARGING/FULL. Low is `EXTRA_BATTERY_LOW` as read. It returns
  null for level < 0 and for scale <= 0. A missing level extra comes back as -1, which is null. `report()` catches
  everything and returns null, so the sync never sees an exception from it. It runs inside `performMdmSync` (off the
  main thread).
- **`battery` is left out when null.** `explicitNulls = false` in `ServerJson` does this, and the test pins it (M3).
  `time_status_report.json` and both copies are untouched. `TimeStateZoneTest` builds its own request without
  `battery`, so it stays byte-identical. `battery_v1` is in `STATUS_CAPABILITIES` (M4).
- **The receiver** is registered once per process: `initRest` runs only from `initUnlocked`, which is guarded by
  `unlockedInitDone`. It uses `ContextCompat.registerReceiver(.., RECEIVER_NOT_EXPORTED)` for the two actions, and both
  the request and the registration are caught. `onReceive` (main thread) only calls `SyncRunner.request`, a wake lock
  plus a service start, the same as the package receiver. The reason `"battery"` follows the other reasons' style. The
  doc comment and §2.5 cover the edge lost while the process is dead or before the first unlock: the next report
  carries the sticky state.
- **`registerReceiver(null, filter)` without a flag** (`BatteryStatus.kt:27`) is fine. On API 34+ the explicit-flag
  rule doesn't apply to a null receiver (a sticky query: AMS sets EXPORTED itself), and `ACTION_BATTERY_CHANGED` is a
  protected broadcast anyway. Lint (`UnspecifiedRegisterReceiverFlag`) stays silent. See N1 for consistency only.
- **One event per call on the server.** `new_emergency_call` announces `reported` when it is newer than the previous
  report's value. Every record of a call now stores the same `connectedAtMs`, and `lastEmergencyCallMs` =
  max(call-log start, recorded instant). The call log's DATE is the call's creation (dial) time, which is at or before
  `connectTimeMillis`. So the max is the connect instant both during the call (the log has no row yet) and after it
  (the log row is older): one value, one event. A later call has a later instant and is a new event. Before this
  change the value moved from the connect to the hang-up time, which gave two events.
- **The callback window still runs from the last record.** `recordEmergencyConnected` still computes
  `untilWallMs = System.currentTimeMillis() + CALLBACK_WINDOW_MS` (`CallSystem.kt:211`) at every connected state, the
  disconnect included, along with the elapsed start and boot count. `connectedAtMs` only goes into the new key.
  `recordedWindowUntil` and `callbackWindowUntil` (screening, the self-update gate) read the window keys, never the new
  key. A 50-minute call still leaves 60 minutes of callback after the hang-up. The call log's own window (end =
  start + duration) is unchanged. No regression, but no test pins it (L3, M8).
- **The self-update gate** (`SelfUpdate.emergencyRecent`) checks `CallSystem.callbackWindowUntil(context, now)` first.
  That is the window from the hang-up, plus the call log's start + duration. It blocks the commit for an hour after any
  connected emergency call. Only the "last 10 minutes" check now counts from the connect, and the window always covers
  it. After a reboot right after a long call, the recorded window closes (boot count). But the gate only runs unlocked,
  where the call log's window (boot-independent) still holds. ECBM is unchanged. Acceptable as the note says.
- **A record an older build wrote** has no `mdm.calls.emergency_connected_at_ms` and gives the window's start, as
  before (`CallSystem.kt:240`).
- **The DE storage rule** holds: the only new key in `call_boot_state` is one wall-clock instant, the kind of value the
  emergency record already keeps there. No number, no name.
- **One request per call.** `Seen.synced` is keyed by the `Call` object in an identity map and dropped in
  `onCallRemoved`. The request is caught and posted to the main handler. Telecom's `Call.Callback` runs on the main
  looper (registered from `onCallAdded` without a handler), so the Telecom binder thread is never blocked. The record is
  committed before the post, so the sync's report carries it.
- **Direct boot.** `KidInCallService` is `directBootAware`. `SyncRunner` / `CommandListenerService` are not, and no
  path reaches them before the unlock. `EmergencyConnects` asks `CallPolicyStore.userUnlocked` (which catches and is
  false on error) before `requestSync`. While locked the call isn't marked, so a later connected state after the unlock
  asks (M5). The battery receiver only exists after `initRest`, which runs after the unlock. During "unlocking"
  (`isUserUnlocked` still false while the component factory already ran `ensureUnlockedInit`) no sync is requested.
  That is conservative and correct.
- `connectTimeMillis == 0` at the first ACTIVE: the first sighting is the instant, and later records keep it (M7).

## Findings

### High

None.

### Medium

None.

### Low

**L1. The emergency sync can be lost to `onDestroy`'s `removeCallbacksAndMessages(null)`**
(`KidInCallService.kt:191`, `:62`). The request is posted to `handler`, which `onDestroy` clears. A destroy can land
between the post and the run when the posting callback is already the call's last. Example: a short emergency call
whose first "connected" sighting is its DISCONNECTED state (the service bound late, or the ACTIVE update coalesced).
Telecom's `onCallRemoved` and the unbind/destroy are then often already queued behind it on the main looper, so
`onDestroy` runs first and drops the runnable. In the usual case (ACTIVE seen while the call is up) the post has run
long before the hang-up, so the window is narrow.
- What still carries the call: the DE record is committed before the post. The next sync reports it: the SSE
  reconnect, a nudge, or the 30-minute backstop. The parent hears of it late, not never.
- Fix: post with a handler that `onDestroy` doesn't clear (`Handler(Looper.getMainLooper())` held by the
  `EmergencyConnects` wiring, or `ContextCompat.getMainExecutor(applicationContext)`). Or call `SyncRunner.request`
  directly: the callback is already on the main thread, and the request is the same non-blocking work every receiver
  does.

**L2. The fallback instant isn't stable across a process restart mid-call** (`EmergencyConnects.kt:29`). When
`connectTimeMillis` is 0 at the first sighting, the instant is `nowMs`. If the process then dies mid-call, Telecom
rebinds with a new `Call` object. The new sighting (if `connectTimeMillis` is still 0) takes a later `nowMs`,
overwrites the DE key, and the server announces it as a second emergency call. Both conditions are rare
(`connectTimeMillis` is normally set before ACTIVE reaches the in-call service). With a real connect time the restart
gives the same instant and only one extra (harmless) sync.
- Fix: fall back to `call.details.creationTimeMillis` (API 26+, held by Telecom, stable across our restart) before
  `nowMs`. Or, on a first sighting with no connect time, reuse the stored `emergency_connected_at_ms` when the recorded
  window is still open in this boot.

**L3. The callback-window invariant and the older-record fallback aren't pinned by any test** (`CallSystem.kt:209-219`,
`:240-242`). M8 (the window starting at `connectedAtMs`) survived, so a later edit could bring back exactly the
regression this review was asked to rule out, silently: a 50-minute call leaving 10 minutes of callback. M11 (the
report ignoring the new key, which brings back two events per call) survived too.
- Fix: extract the two decisions as pure functions next to `callbackWindowUntil` and test them:
  - `emergencyRecord(nowWallMs, nowElapsedMs, boot, connectedAtMs)` returns the `WindowStart` (from now) plus the
    instant.
  - `reportedEmergencyAt(storedConnectedAtMs, windowUntilMs)` gives the instant, or the window start when it is 0.
  - Or, in the repo's source-check style, assert that `recordEmergencyConnected` computes `untilWallMs` from
    `System.currentTimeMillis()` and puts `connectedAtMs` only into `EMERGENCY_CONNECTED_AT_MS`.

**L4. The two "everything caught" guards outside `watch` aren't pinned** (M9, M10 survived). The catch around the
emergency `SyncRunner.request` (`KidInCallService.kt:191-197`) keeps a failing service start from becoming an uncaught
exception on the main thread of the process that runs the in-call UI. The catch in `report()` (`BatteryStatus.kt:36`)
keeps a battery read from failing the sync. `BatteryReportTest` counts catches only in `watch`, and
`EmergencyConnectsTest` checks the request line but not its `try`.
- Fix: extend the two source checks. Count `catch (e: Exception)` in `report`'s body and in the `requestSync` lambda,
  as `watch`'s check already does.

### Nit

**N1. Consistency (`BatteryStatus.kt:27`).** `report()` uses `context.registerReceiver(null, ..)`, while
`SelfUpdate`'s sticky ECBM read goes through `ContextCompat.registerReceiver(context, null, filter, flag)`. Both work
(see above). Moving to `ContextCompat.registerReceiver(context, null, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`
would make the two sticky reads look the same. If changed, update the source check in `BatteryReportTest`.

**N2. A stale instant after a downgrade and re-upgrade.** An older build writes the window keys but never touches
`mdm.calls.emergency_connected_at_ms`. After a downgrade, an emergency call, and an upgrade, `recordedEmergencyAtMs`
reports the earlier call's connect instant beside the newer window. `lastEmergencyCallMs`' max with the call log
hides it unlocked. Downgrades aren't a supported path, so this is noted only. Clearing the key whenever the window is
written without it isn't possible from old code. Accept.

**N3. Unconnected emergency calls.** An emergency call that never connects gets no immediate sync. The call log's row
reaches the parent at the next report. That is within §9 step 3 ("after an emergency call connects"), but the
emulator section §6n could say so in one line, so a tester doesn't expect one.

## Mutations

Each one was applied alone, then `testDebugUnitTest -PwarningsAsErrors=true` was run on the `calls.*`, `server.*` and
`SyncScheduleTest` tests, then the mutation was reverted. The tree is clean afterwards.

| # | Mutation | Result | Caught by |
|---|---|---|---|
| M1 | drop the 0..100 clamp in `of` | killed | `BatteryReportTest` level |
| M2 | invert the plugged rule (`plugged == 0`) | killed | `BatteryReportTest` charging, report |
| M3 | send `battery` when null (`@EncodeDefault`) | killed | `BatteryReportTest` report (and the DNS status contract tests) |
| M4 | drop `battery_v1` from `STATUS_CAPABILITIES` | killed | `SyncScheduleTest` |
| M5 | request the sync before the unlock | killed | `EmergencyConnectsTest` unlock |
| M6 | request twice per call (never mark `synced`) | killed | `EmergencyConnectsTest` once, unlock |
| M7 | record a new instant at every connected state | killed | `EmergencyConnectsTest` (3 tests) |
| M8 | start the callback window from the connect | **survived** | none (L3) |
| M9 | drop the catch around the emergency request | **survived** | none (L4) |
| M10 | drop the catch in `BatteryStatus.report` | **survived** | none (L4) |
| M11 | report the window start, ignoring the new key | **survived** | none (L3) |
| M12 | drop the `scale <= 0` null case | killed | `BatteryReportTest` null cases |
| M13 | drop `emergencyConnects.removed(call)` | killed | `EmergencyConnectsTest` source check |

9 of 13 killed. The survivors are the code paths L3 and L4 cover. The code itself is correct for each of them.

Test quality otherwise: the behaviour tests are lean (no redundant matrices). The source checks for the receiver and
the service wiring follow the repo's pattern.
