# Launcher notes: safe zones, phone off and on, battery in the status report

Moved from launcher/CLAUDE.md on 2026-11-01.

## The battery in the status report (design 35 §2.5, `docs/design/35-parent-notifications.md`, at the monorepo root)

- **Wire** (design 35 step 1, in the server's commit): `StatusReportRequest.battery: BatteryReport? = null` with
  `BatteryReport(level, charging, low)` (every field always sent; `null` is left out, so `time_status_report.json` /
  `TimeStateZoneTest`, which build their own request, are unchanged). The server stores the level and charging for
  the PWA's "Battery 14 %, charging" line and raises the parent's "Battery low" event from `low` (latched; re-armed on
  battery, not charging, above Android's level); a level outside 0..100 drops the reading, never the report.
- **The reading** (step 3, `server/BatteryStatus.kt`): every report sets `battery = BatteryStatus.report(context)` -
  the sticky `ACTION_BATTERY_CHANGED` (`ContextCompat.registerReceiver(context, null, ..)`, as `SelfUpdate`'s ECBM read) through the pure `BatteryReport.of(level, scale,
  status, plugged, low)`: level = round(level x 100 / scale) clamped 0..100, charging = plugged != 0 or status
  CHARGING/FULL, low = `EXTRA_BATTERY_LOW`; `null` (left out) without a sticky intent, with the level -1 or a scale
  <= 0, or on any exception. Capability `battery_v1` (`BATTERY_CAPABILITY`, in `STATUS_CAPABILITIES`).
- **The trigger**: `Application.initRest` calls `BatteryStatus.watch(this)` - one runtime receiver
  (`RECEIVER_NOT_EXPORTED`; the system's broadcasts still reach it) for `ACTION_BATTERY_LOW` and `ACTION_BATTERY_OKAY`
  that calls `SyncRunner.request(app, "battery")`, so the report with `low=true` lands within seconds (OKAY: the
  re-arm). The request and the registration are each caught. While the process isn't running (killed, or before the
  first unlock) the edge is lost and the next report carries the sticky state.
- **After an emergency call connects** (`calls/EmergencyConnects.kt`, one per `KidInCallService`): every connected
  state of a platform-confirmed emergency call (active, active again after hold, disconnected after connecting) goes
  through `connected(call, connectTimeMillis, now) { CallPrefs.recordEmergencyCall(this, it, creationTimeMillis) }`, which commits
  the record with **one instant per call** (the call's `connectTimeMillis`, else its `creationTimeMillis` - both
  Telecom's, stable across a restart of our process mid-call -, else the first sighting; kept per `Call` object,
  identity map, dropped in `onCallRemoved`) and then asks for **one** sync per call
  (`SyncRunner.request(this, "emergency_call")`, called directly - Telecom's callbacks are on the main looper, and a
  post could be dropped by `onDestroy`'s `removeCallbacksAndMessages` - and caught) - so the report with
  `last_emergency_call_at` reaches the parent within seconds. `CallPrefs` keeps the instant
  (`mdm.calls.emergency_connected_at_ms`, DE prefs `call_boot_state`); the pure rules beside `callbackWindowUntil`
  (`CallRules.kt`): `emergencyRecord` - the callback window runs from **this** record, the hang-up's last, so a
  50-minute call still leaves an hour - and `reportedEmergencyAt` - the stored instant, a record from an older build
  its window's start. Before the stable instant the report moved from the connect to the disconnect time, which the
  server announces as a second emergency call. **Never before the first unlock** (`CallPolicyStore.userUnlocked`
  false: the anchor isn't direct-boot-aware): no request, the call isn't marked, so its next connected state after
  the unlock asks; else the anchor's start sync after the unlock carries the call from the DE record. **An emergency
  call the launcher placed** (`CallSystem.placeCall`, E7's H1) is recorded and synced the same way the moment Telecom
  takes it - its instant the placing, taken just before Telecom creates the call; when our in-call service sees that
  call connect too, `emergencyCallInstant` keeps the placing's instant (stored no more than 30 s before Telecom's
  `creationTimeMillis`), so the server still hears of one call.
- Tests: `BatteryReportTest` (`of`: rounding, clamp, the charging rule, every null case; the report with and without
  `battery`; the receiver's registration, actions and catches by source), `SyncScheduleTest` (`battery_v1`),
  `EmergencyConnectsTest` (one sync and one instant per call over its states, the creation-time fallback, a second
  call, never before the unlock and at the next state after it, `emergencyRecord`/`reportedEmergencyAt`, the
  service's wiring and the request's catch by source; `BatteryReportTest` counts `report()`'s catch too). Emulator check: `docs/testing/emulator.md` §6n.
- **Not device-tested**: the Jelly Star's BATTERY_LOW threshold and whether `EXTRA_BATTERY_LOW` is set there, the
  receiver in Doze, the emergency sync over mobile data (design 35 "Step 3 implementation status").

## The phone turned off and on (design 38, `docs/design/38-phone-off-on.md`, at the monorepo root)

- **What**: the parent's "Anna was turned off · 21:14 · battery 2 %, probably flat", "Anna restarted" and "Anna was
  turned on · off since 21:14, battery was 2 %" (the server's words; design 38 §3) from the phone's own signals:
  Android's shutdown broadcast and the first process start of a boot. Pure rules and the glue in
  `server/PowerEvents.kt` (`PowerEventsTest`), the body `dto/PowerReport.kt` (`POST /api/devices/power`,
  `MdmApi.sendPower`), capability `power_v1` (`POWER_CAPABILITY`, in `STATUS_CAPABILITIES`; `SyncScheduleTest`).
- **The record** (DE prefs `power_events`, so the start is written in direct boot; nothing about the user or the
  PIN): at most one shutdown (`off_at_ms`, `off_boot_count`, `off_level`/`off_charging`/`off_low`, `off_reboot`,
  `off_reason` - absent = unknown -, `off_sent`) and one start (`on_*`); a new one replaces the old (an unsent old one
  is lost). Pure codec `encodePowerRecord`/`decodePowerRecord` (`PowerKeys`). Every read-compare-write under
  `PowerEvents`' lock with one `commit()` (`update`): the shutdown receiver on the main thread races the sync's thread
  (qa-38-design L3). A sent shutdown is **kept** (`off_sent`) and sent again with the next start until a later
  boot's start is accepted, so the server pairs them in one body (QA H1); kept parts older than 7 days go unsent
  (`stale`, `POWER_STALE_MS`) - an old server's 404 ends there too.
- **The shutdown**: `BootCover`'s `ACTION_SHUTDOWN` receiver (from `PinLockRuntime.init`, `RECEIVER_NOT_EXPORTED`;
  ShutdownThread sends it to registered receivers only) is the process's **one** shutdown receiver (QA M3): first
  the cover's synchronous writes as before (`coverAtShutdown`: `KeyguardCover.onShutdown`, the boot cover's arm),
  then `PowerEvents.onShutdown` - each in its own `try`/`catch` (QA N5). That reads the battery, the restart flag
  (pure `rebootOf`: `EXTRA_SHUTDOWN_USERSPACE_ONLY` -> true, `sys.shutdown.requested` "1…"/"0…" -> true/false,
  anything else - unreadable included - `null`, never `false`; the property read by reflection is a bonus, expected
  unreadable on the Jelly Star) and its reason (`reasonOf`, the text after the digit), `commit()`s the shutdown
  (write-ahead, `off_sent = false`), and only then `goAsync()` with the POST on its own thread (`finish()` in a
  `finally`, also when the executor refuses). Not enrolled (no prepared client): nothing is sent, the record waits.
- **The prepared client** (QA N1, the design QA's High): `createMdmApi` reads `TsnetClient.proxy()` only when it is
  built, so the shutdown's client (`createMdmApi(.., callTimeoutMs = 3 s)`, `SHUTDOWN_POST_TIMEOUT_MS`) is built in
  advance, keyed by `(base URL, token, tsnet proxy address)` (pure `clientKeyOf`/`rebuildNeeded`), and rebuilt off the
  main thread whenever one differs: `PowerEvents.init` from `initRest` (and it sets `TsnetClient.proxyListener`, which
  `connect`/`close` call when the proxy address changes), and `PowerEvents.prepare` after each sync's
  `connectFromPreferences` (a new enrollment's base URL and token too). The receiver takes whatever is current.
- **The boot**: `BootWitness.record` exposes `hadLastBoot` (what it had before overwriting `last_boot`, QA L2);
  `Application.onCreate` calls `PowerEvents.recordBoot` right after it - after the `:bootcover` early return (main
  process only), in direct boot too. Pure `bootRecord(newBoot, hadLastBoot, bootCount, nowMs, elapsedMs, battery)`:
  a start only for a new boot with a last boot known and a count >= 0 (-1 is "new" at every start, QA M4), at
  `now - elapsedRealtime`.
- **The upload**: `PowerEvents.upload` in `performMdmSync` after `ParentPinEvents.upload` - stale parts out, then one
  POST when an unsent shutdown or a start waits (pure `uploadBody`: the start with any kept shutdown of an earlier
  boot, sent or not; else an unsent shutdown alone; a shutdown of the start's own boot or later isn't paired - the
  start then waits for the next boot's to replace it; the start's instant recomputed while it is still its boot). The
  answer by `pinEventsLeaveQueue` as is (QA L5, `afterAnswer`): 2xx -> `clearSent` (the shutdown marked sent; with a
  start, the start and any kept shutdown of an earlier boot go; compared by instant and boot - a start by its boot -,
  so a newer record written meanwhile stays), 401/404/408/429, 5xx and no answer keep, any other 4xx -> `dropSent`.
  The shutdown's own POST uses the same table. A start whose sync found no network is sent by the stream's first
  open (`sse_open`), else the 30-min backstop.
- Tests: `PowerEventsTest` (`rebootOf`/`reasonOf`, `bootRecord`, `uploadBody` - the resend with the start -,
  the answer table, `clearSent`/`dropSent` keeping a newer record, `stale`, the codec, the prepared client's key,
  the encoding equals `power_report.json` and both copies match; by source: one `ACTION_SHUTDOWN` receiver in the
  process, the cover's writes before `PowerEvents.onShutdown`, each caught, the commit before `goAsync`, `finish()` in
  a `finally`, the record's writes `commit()` never `apply()`, the boot cover's synchronous arm in
  `coverAtShutdown` - the code QA's L1 -, the wiring in `Application`, `MdmSyncWorker` and `TsnetClient`),
  `KeyguardCoverPlanTest` (the cover's part is `coverAtShutdown`), `SyncScheduleTest` (`power_v1`). Emulator check: `docs/testing/emulator.md` §6p.
- **The last position** (2026-10-25, design 38 "Last position"): the shutdown's `off` carries `location` - the newest
  fix already held (`LocateCommands.newestKnown`, pure `shutdownLocation`: none without a fix or a location policy,
  under "Off" - `noteLocationPolicy` from every sync, the cache's at `init` -, or for a mock fix), never a fresh one;
  the fix is kept in CE default prefs `power_off_location` (`OffLocation`, `commit()` before `goAsync`), never in the
  DE record (`encodePowerRecord` has no location key), and joined back to its own shutdown at the upload
  (`withOffLocation`, `offLocationKept`). At `ACTION_BATTERY_LOW` `BatteryStatus`' receiver marks the edge
  (`LowBatteryFix.onBatteryEdge`) before its sync request; that sync's location step takes it (`LowBatteryFix.take`,
  pure `lowBatteryFixDue`: device owner, a policy not "Off", Location on, none taken in this episode) and takes the
  balanced path (`withLowBatteryFix` -> `FRESH_BALANCED`, 15 s + 15 s), reported in that report - or, with a fix of
  its own under 2 min old (`lowFixKind`, `LOW_FIX_RECENT_MS`), reports that one (`LowFix.CACHED`); the episode (CE
  `battery_low_fix_taken`) is marked first and ends at `ACTION_BATTERY_OKAY` (`lowEpisodeAfterEdge`) or a reading
  discharging and not low (`lowEpisodeAfterReading`); every step is `LowBatteryEpisode` over a `LowEpisodeFlag`, so the
  tests drive the drain guard with a field (qa-last-position-code Q-2). `noteLocationPolicy` and `init`'s cached read
  share one lock (Q-6). Tests: `PowerEventsTest`, `LowBatteryFixTest`.
- **Not device-tested**: the Jelly Star's power menu (Power off, Restart), a real flat battery (is the broadcast sent,
  at which level), the property read, and Restart timed to the start's arrival on Wi-Fi and on mobile data against
  the server's 5-minute hold (design 38 §6); the last position's emulator checks (design 38 "Last position").

## Safe zones on the phone (design 64 step 2, `docs/design/64-safe-zones.md` §4, at the monorepo root)

- **The phone decides nothing** (decision 1): the server's two-fix rule on the stored fixes decides every arrival and
  departure; the fences, the motion sensor and the distances (`distanceM`, haversine) are only reasons to take a fix,
  when, and whether to send it now - so the launcher has no side state and no copy of `zone_vectors.json`. Pure rules
  in `zones/ZonePlan.kt` (`ZonePlanTest`), glue `zones/ZoneWatch.kt`, `ZoneFences.kt`, `ZoneSensors.kt`,
  `ZoneSeries.kt` (GPL, not the MIT directories), log tag `ZoneWatch`. Wire: `LocationPolicy.zones`
  (`PolicyZone(id, latitude, longitude, radiusM)` - never a name; missing = none), `StatusReportRequest.extraLocations`
  and `zoneWatch` (`ZoneWatchReport`), `LocationReport.provider`/`mock`; contract `zone_status_report.json`
  (`ZoneReportTest`); capability `zones_v1` (the server sends the zones' centres only to a launcher claiming it).
- **One gate** (qa-design-64-step2-code Q-2, the coordinator's decision): everything below - the fences, the passive
  feed, the motion gate, the heartbeat, the backstop counting from the beat, the series, the balanced path, the queue
  - runs only while `zoneWatchActive` (device owner, managed, Regularly **with zones**; `ZoneWatch.zonesOn`). Without
  zones the sync's location step is `syncLocationAction`'s first line, today's `locationAction` exactly (`ZonePlanTest`
  runs every input), the backstop counts from our last own fix (`backstopAnchorMs`), and nothing else of step 2 runs;
  only the mock rule applies to every phone.
- **The fences** (`ZoneFences.sync`, from `ZoneWatch.apply` right after every sync's `AppEnforcer.apply`): one
  `LocationManager.addProximityAlert(lat, lng, radius, -1, pi)` per zone (AOSP's `GeofenceManager`, no Play services, no
  cap; the system polls a hidden balanced fused fix at most every 30 min and judges every fused fix any app causes;
  no location indicator). `zoneFencePlan`: remove ours and add the policy's set at the first sync of every process (a
  reboot drops them, an update may cancel our PendingIntents) and whenever the zones' fingerprint (id, centre, radius)
  changed; nothing otherwise; every stored fence goes when the mode isn't Regularly, the zones are `[]` or we aren't
  device owner. A PendingIntent per zone (request code 8, data `vibb-zone:<id>`), `FLAG_MUTABLE | FLAG_UPDATE_CURRENT`
  (AOSP fills in `KEY_PROXIMITY_ENTERING`), explicit to the non-exported `ZoneAlertReceiver`; removal is
  `removeProximityAlert` then `cancel()`. The location permissions are self-granted first (as `LocateCommands`); a
  `SecurityException` leaves the record without a fingerprint (CE prefs `zone_watch` `fences_v1`, `commit()`), so the
  next sync tries again - never a loop. A policy change reaches the phone at once (the PWA's zone save nudges).
- **A crossing** (`ZoneWatch.onFence`, `fenceVerdict`): an id not in the cached policy's zones is ignored, an
  "entering" within 60 s of the registration is its echo (`FENCE_ECHO_WINDOW_MS`); anything else sets the fresh-fix
  flag and asks `SyncRunner.request("zone")`, and arms `ZoneConfirmReceiver` 2 min later (`CONFIRM_DELAY_MS`,
  `setExactAndAllowWhileIdle`, request code 9) for the confirming fix (`"zone_confirm"`). The sync's location step
  takes the flag (`takeFreshWanted`, a `FreshKind`; the stronger ask wins) as a `locate` would, never answering a
  command: the first fix `BALANCED`, the confirm `PRECISE`.
- **The paths** (design 64 §4.4, `LocationAction`): `FRESH` - today's precise path, GPS first, 15 s - only for
  `locate`/`ring` and a crossing's confirming fix; `FRESH_BALANCED` - `getCurrentLocation(FUSED_PROVIDER,
  QUALITY_BALANCED_POWER_ACCURACY, 15 s)`, GPS only when fused gives nothing - for the heartbeat and a crossing's first
  fix; the series' checks the balanced fix alone (`LocateCommands.seriesFix`, no GPS, no grant, no Location switch).
- **The moving series** (§4.5, `ZoneSeries`, pure `nextCheckDelay`/`seriesOnMotion`/`seriesEnd`/`seriesAfterCheck`/
  `checkVerdict`): while the watch is on `MotionGate` keeps the trigger armed (re-armed at each fire), and a fire
  starts a series (memory only) or tells the running one. Its checks (`ZoneCheckReceiver`, exact while-idle alarm,
  request code 10, a 45 s wake lock) come every `nextCheckDelay`: the cached fix's distance to the nearest boundary
  less its accuracy, at 15 m/s (`CHECK_SPEED_MPS`), less its age, within 3..30 min (`CHECK_MIN_DELAY_MS`,
  `CHECK_MAX_DELAY_MS`), from the newest fix known (`LocateCommands.newestKnown`: the kept one or the reported one); no
  zones = no series, no fix = 3 min. A check's fix (`checkVerdict`, qa-design-64-step2-code F-1): **surely** on the
  other side of a boundary than the **reported** fix by the server's own rule (`sureSide`: inside at `d + a <= r`,
  outside at `d - a >= r`; the reported fix's sure side, or its plain distance when unsure) -> the phone marked moved,
  promoted to the reported fix, queued, a sync (`"zone_check"`) and the confirming GPS fix 2 min later; anything else
  (unsure, no accuracy, the same side) -> only the kept slot (`keepFix`, the heartbeat's `KEPT`). **Caps** (F-2,
  provisional until the Jelly Star walk, design 64 §5): after a series ends the motion sensor starts no new one for
  `SERIES_COOLDOWN_MS` 20 min (a fence crossing starts one at once, `onMotion(fromFence = true)`), and at most
  `SERIES_CHECKS_PER_DAY` 60 checks a local day (`seriesMayStart`, `checksLeft`/`budgetAfterCheck`, CE prefs
  `zone_watch` `series_budget_v1`; `SeriesEnd.BUDGET`). A motion fire's work runs on the series' IO scope
  (`ZoneSeries.launch`), never the sensor callback's main thread (F-4). The fused fix passes the platform fence too. The series ends after `SERIES_MAX_MS` 60 min,
  `SERIES_MAX_CHECKS` 20, or no fire within twice the last delay (`SeriesEnd.HOUR`/`COUNT`/`STILL`); no zones watched
  stops it (`ZoneSeries.stop`).
- **The queue** (`queueWith`/`extraLocations`/`queueAfterReport`, CE prefs `zone_watch` `queue_v1`): a report the
  server didn't take (no network, a 5xx, an exception) leaves its `location` queued; the next report sends the queue
  minus its own fix as `extra_locations`, oldest first; a taken report removes what it carried (what was queued
  meanwhile stays). Each fix once (time and place, the server's key), at most `ZONE_QUEUE_MAX` 20 (the newest). With
  Location Off or no zones the queue is emptied **before** the report, which then sends no extras (Q-5). Only the
  reports' fixes and a series' candidates are queued - a passive fix never is.
- **The passive feed** (`PassiveFixes`, while the watch is on; removed when it ends and in the anchor's `onDestroy`):
  `requestLocationUpdates(PASSIVE_PROVIDER, LocationRequest.Builder(60 s))`. `passiveFixWorth`: not mock in a release
  build, accuracy <= 200 m (`PASSIVE_ACCURACY_FLOOR_M`), newer than the newest fix known - then it goes to the **kept
  slot** (`LocateCommands.keepFix`, default prefs `zone_kept_fix`), **never the cached (reported) fix** (Q-1): every
  sync between beats re-sends the reported fix as before; only a due heartbeat's `KEPT` or a crossing's sync
  (`LocateCommands.promoteKept`) makes it the reported fix, so the trail stays as dense as the parent's interval (design
  64 §4.3).
- **The heartbeat** (`heartbeatDue` through `syncLocationAction`, in `currentLocationReport` with zones and no
  command): due when the served interval passed since the last heartbeat (`ZoneWatch.lastBeatMs`, CE prefs
  `last_beat_ms`; the clock set back counts as due); due, it reports the kept slot's fix when it is not yet reported and
  younger than the interval (`KEPT`, promoted first; never our own reported fix, Q-4), or the reported fix while the
  significant-motion sensor hasn't fired since our last own fix and that fix is under 4 h old (`STILL`,
  `STILL_SKIP_MAX_MS`, counted from the last **genuinely fresh** own fix - `ZoneWatch.lastFreshFixMs`, stamped only with
  `freshOwnFix`, never a failed attempt; F-3), else takes a fresh fix (`FIX`); every due call and every fresh fix stamps the beat, and
  `BackstopAlarm` counts from it (a skip waits a whole interval, not the 9-minute floor). A report that re-sends the
  cached fix while the sensor says still (`reportStill`) sends `zone_watch.still: true`, so the server's "Here now"
  counts from it (Q-3). With zones on a watching phone the server serves at least 30 min (decision 3). `MotionGate`:
  `TYPE_SIGNIFICANT_MOTION` via `requestTriggerSensor`, armed only after a **genuinely fresh** own fix (`freshOwnFix`:
  taken at the request, less 60 s - never after a fallback to the cached or an old last-known fix, Q-6; `arm` and the
  fire share one monitor) and kept armed while the watch is on, for the series; one-shot (disarms itself when it
  fires); a new process counts as moved; a fence crossing and a series' side change mark it moved too
  (`MotionGate.markMoved`, F-3: the sensor may miss a smooth ride); **no sensor (the emulator) = always fix**, and no
  series.
- **The mock rule** (decision 14): `ZoneWatch.accept` on every fix the phone takes or keeps - the fresh one, each
  last-known candidate, each passive fix: a release build ignores a `Location.isMock()` fix and counts it once per fix
  (`countMock`, CE prefs `mock_ignored`, a running count - `zone_watch.mock_ignored`), the debug build takes it (the
  emulator's test provider; `LocationReport.mock` then says so). A fix counts once by its time and place
  (`mockKey`, the last `MOCK_KEYS_KEPT` 8 keys in CE prefs `mock_keys_v1`, Q-9). The cached fix keeps its provider and
  mock flag.
- **`zone_watch`** (`ZoneWatch.report`, every report as device owner): `fences` (the record's ids), `motion_sensor`,
  `location_on`, `background_location` (the self-granted permission, never confirmed on a device), `mock_ignored`,
  `still` (left out when false).
- **Un-managed and a new enrollment**: `AppEnforcer.apply` on a phone that isn't managed (`!hardeningManaged`, right
  after `clearHardening`) calls `ZoneWatch.release` - every fence of ours removed, the passive feed, the sensor and the
  series stopped, the queue and the kept fix emptied; both enrollment paths (`Provisioning`, Settings' enroll, next to
  `CommandAnswers.clear`) call `ZoneWatch.forget` - the same, and the fence record, the beat's stamp and the mock count
  (Q-8). A release before the process's first watching sync doesn't count as its registration
  (`ZoneFences.registeredHere`). The fence record and its stamp are saved **before** the first `addProximityAlert`, so
  the registration's own "entering" is always inside the echo window; no record reads as within it (Q-7).
- **The pause and the override change nothing** (decision 12: locating isn't a restriction); "Keep location on" is
  unchanged hardening; Doze: the fence's broadcast comes with system_server's wake lock, our alarms are while-idle on
  the power-save allowlist (design 19b).
- **Kid Settings** (decision 6): "This phone shares where it is with mum and dad." / "Telefonen deler hvor den er med
  mamma og pappa." (`kid_settings_location_shared`, the wallpaper caption's look, no control) while `sharesLocation`:
  device owner, an `Ok` cached policy whose mode isn't Off (`KidSettingsModelTest`).
- Not device-tested: `docs/testing/emulator.md` §6t (the emulator) and design 64 §9 (the Jelly Star: the background
  app op, the sensor armed in the background, whether the passive feed shows the location indicator, a walk home, the
  battery run, a mock-location app).
