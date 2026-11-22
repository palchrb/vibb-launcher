# 124 - Location while the phone lies still

Status: **proposal**, 2026-11-22. Asked by the user: "hva tror vi om å be om location hver 30 minutt slik vi gjør nå,
hvor mye batteri vil det kreve? ... nå kjører den jo på med hvert 30 minutt hele natten mens man er hjemme ... Har vi
gjort dette på en optimal måte?", then "Jeg er interessert i forbedringene du kommer med, når det ikke er bevegelse
etc i telefonen. Men tenk gjerne nøye gjennom det", then "da må vi jo sikre at vi klarer å hente den sensoren - hva
skal til?". Launcher round plus a small server change; no contract file changes. Read against `main` 2e3796699.

## 0. Short answer

Today's 30-min fix costs about **12-19 mAh a day (0.6-1 % of the Jelly Star's 2000 mAh)**: cheap, not a battery hog.
But it is not optimal. A phone without zones - the Jelly Star today - wakes the GPS for 15 s every 30 min all night,
indoors, where it usually finds nothing, and the PWA then shows an aging fix. Design 64 built the cure (the
significant-motion sensor gates the fix) but only for phones with zones. This note gives every Regularly phone the
same still rule, fixes a gap in it that also hits zones phones (after 4 h still, a failing fix retries every beat), and
adds a second witness - the same Wi-Fi access point - so a phone on the nightstand stays "Hasn't moved since 20:15"
all night without a single fix. After: about **2-6 mAh a day**, and the parent sees a current position, not an old one.

## 1. What the phone does today (read in the code)

| | Regularly **with** zones (`zoneWatchActive`) | Regularly **without** zones |
|---|---|---|
| Location step | `syncLocationAction` -> `heartbeatDue` (`ZonePlan.kt:215`) | `locationAction` (`LocationPolicies.kt:51`): `FRESH` every interval |
| Due fix | `FRESH_BALANCED`: fused at balanced, 15 s; then GPS 15 s; then last-known | `FRESH`: `preciseFix(false)` = first enabled of gps/network, i.e. **GPS only**, 15 s; then last-known |
| Still skip | `STILL` while `MotionGate` hasn't fired since the last genuinely fresh fix, < 4 h (`STILL_SKIP_MAX_MS`) | none |
| Motion sensor | `MotionGate.watch`/`arm` (`MdmSyncWorker.kt:898,934`) | **never armed** (`motion = NO_SENSOR` when `!zonesOn`) |
| `zone_watch.still` | `reportStill` | never (`zonesOn &&`) |
| Passive feed / KEPT | yes | no |
| Backstop anchor | the beat (`backstopAnchorMs`) | the last attempt |

The Jelly Star (coordinator, 2026-11-22): Vibb is uid 10232 (music 10233). The phone **has** the sensor
(`android.sensor.significant_motion`, mtk, wake-up); its registrations in `dumpsys sensorservice` are uid 10116
(likely Play services' own location engine [inferred]) and `DeviceIdleController` - **never Vibb**: the phone has no
zones, so it runs the right-hand column. Google Location Accuracy is off and there is no SIM, so the network provider
gives nothing; every fix is a GPS try.

**A gap in the zones path too.** After 4 h still the cap demands a fix. Indoors with network location off it fails;
`lastFreshFixMs` is stamped only on success (F-3) and the gate is not re-armed, so every following beat is `FIX` - a
15-s GPS try every 30 min for the rest of the night - and `still` stops (`stillSinceOwnFix` is false past 4 h), so
the PWA ages too. On the Jelly Star a zones phone would spend nights 4 h into the same pattern as a no-zones phone.

**The server needs nothing for `still` without zones** (checked): the launcher sends `zone_watch` in every
device-owner report, zones or not (`ZoneWatch.report`); `device_api.rs:1925` marks `resent_at` from
`zones::report_still` with no zone test; `Seen::still_line` ("Hasn't moved since 11:40") and the fresh/old word on
both Locate views read `seen_at()` with no zone test; `served_interval_of` floors the interval only when
`fences > 0`. One reader ignores `resent_at`: the weather's fix (`handlers/weather.rs:55`, `captured_at` within 6 h).

## 2. Decisions

1. **One still rule for every Regularly phone.** A new gate `stillWatchActive(owner, managed, policy)` = device
   owner, managed, mode `interval` (zones or not). It carries the heartbeat (`heartbeatDue`, the beat stamp, the
   backstop counting from the beat), `MotionGate`, `lastFreshFixMs`, `reportStill`, `FRESH_BALANCED`. The fences, the
   passive feed (KEPT), the moving series and the queue stay on `zoneWatchActive`: they cost or show something only
   zones need, and the passive listener's indicator is still a device check (design 64 §9). Without zones the kept
   slot is empty, so `KEPT` never happens. `ZonePlanTest`'s "without zones = `locationAction`" row becomes "not
   Regularly, not owner or not managed = `locationAction`".
2. **MotionGate without zones: one-shot only.** `ZoneWatch.apply`: zones -> `watch(zones = true)` (kept armed for
   the series, today); Regularly without zones -> `watch(zones = false)` (armed only by `arm`, not re-armed at a
   fire); anything else -> `stop`. A fire without zones only marks moved.
3. **Two flags, two jobs.** `MotionGate` keeps `movedSinceFresh` (today's `moved`) and `movedSinceTry`. A fire,
   `markMoved` and a new process set both. A genuinely fresh own fix clears both (`arm(fresh = true)`, today's call).
   **A due fix that failed** clears only `movedSinceTry` (`arm(fresh = false)`, after every due attempt that returned
   no fresh fix). `motion()` returns `MOVED`, `STILL` (since the last fresh fix), `STILL_SINCE_TRY` (moved since the
   fix, but not since the last failed try) or `NO_SENSOR`. So a phone that went to school and failed indoors does not
   retry every 30 min while it sits in class; the next walk (a fire) retries at the next beat. A fix is never less
   available after the phone actually moved.
4. **The heartbeat** (pure, `heartbeatDue`; `lastTryMs` = `lastActiveLocationFetchAtMs`, stamped at every attempt):
   not due -> `NOT_DUE`; a kept unreported fix -> `KEPT`; `STILL` and (the last fresh fix < 4 h old **or** the Wi-Fi
   witness, D6) -> `STILL` (no fix, `still: true`); `STILL` or `STILL_SINCE_TRY`, and the last try < 4 h old ->
   new `QUIET` (no fix, **no** `still`: the reported fix's own age shows); else `FIX`. `reportStill` = the action is
   `CACHED` and the `STILL` condition holds - between beats too, as today. Past the cap with no witness, the phone
   tries once per 4 h, never every beat (closes the gap in §1).
5. **The cap stays 4 h, no night rule, not tied to the interval.** It only bounds trust in a sensor that might not
   fire; a cap fix costs ~0.3 mAh, six a day are ~2 mAh. A clock rule is wrong at a sleepover; an interval-tied cap is
   10 min at the 10-min floor. The night at home is handled by the witness instead.
6. **The Wi-Fi witness** (Q1, recommended): at a genuinely fresh own fix the phone stores the SHA-256 of the
   connected access point's BSSID (CE prefs `zone_watch` `fresh_wifi_v1`; `null` when not on Wi-Fi or the BSSID is
   redacted); past the cap, a due beat or a `CACHED` sync reads the BSSID again, and the same hash counts as "still
   where the fix was" - Wi-Fi range is ~50-100 m and needs no sensor. Not on Wi-Fi, another access point (a mesh
   node too) or redacted -> no witness -> `QUIET`/`FIX` by D4. Never sent, cleared with the kept fix (`release`,
   `forget`, mode not Regularly). Read through a one-shot `ConnectivityManager` network callback with
   `FLAG_INCLUDE_LOCATION_INFO` (API 31; the WifiInfo from it carries the BSSID with fine location and Location on),
   bounded to 2 s.
7. **A sensor that lies is caught and dropped.** A genuinely fresh own fix taken while `motion()` said `STILL` (a
   cap fix, a parent's `locate`, a low-battery fix) is compared with the fix it vouched for: surely elsewhere
   (`distance > accOld + accNew + 200 m`, both accuracies known) -> the sensor missed a move: CE prefs
   `motion_distrust_until_ms` = now + 7 days, `motion()` returns `NO_SENSOR` meanwhile (every due beat fixes, today's
   behaviour) and `zone_watch.motion_sensor` reports `false` - the existing field, so the zones footer already says
   "It has no motion sensor, so it takes a location every 30 min". A wrong distrust only costs battery.
8. **Delivery of the trigger** (the user's "hva skal til?"): (a) D2 arms it without zones; (b) Android 9+ delivers
   one-shot sensors only to a UID that isn't idle - the anchor `CommandListenerService` (a `specialUse` FGS, always
   running on an enrolled phone) keeps 10232 active; its `onDestroy` gets `MotionGate.markMoved()` (one line: with
   the anchor gone a fire may be lost); (c) a reboot or process death drops the registration and `moved` starts true,
   so the first due beat fixes and re-arms - at most one extra fix per process start; (d) no sensor -> `NO_SENSOR` ->
   every due beat fixes, today's behaviour (plus D9's balanced first).
9. **Balanced first without zones, with a floor.** A due `FIX` is `FRESH_BALANCED` on every Regularly phone. A
   balanced fix worse than 200 m (`PASSIVE_ACCURACY_FLOOR_M`; a rural cell fix can be 1-3 km) asks GPS once more and
   keeps the better of the two; GPS giving nothing keeps the balanced one. Same for a zones heartbeat; the series'
   checks stay balanced only. With network location off this is today's GPS try plus a fused call that returns at
   once. The parent's `locate` keeps its own path (fused high accuracy first, 60 s).
10. **Unchanged:** `locate` (always fresh, explicit), the low-battery fix (one balanced fix per episode - the last
    position before a flat battery is worth its 0.2 mAh), crossings and their confirms (`markMoved`), "Only when
    asked", Off, the pause, the P-L3 age limit (a `still` re-send has none, as today).
11. **What is visible, no new data.** Log lines under `ZoneWatch`: `Motion trigger armed (fresh|try)`,
    `Motion trigger fired`, `Motion trigger refused`, `Still: wifi witness`, `Motion sensor distrusted until ...`.
    No new report field: `motion_sensor` (exists) says whether the phone uses one, and the PWA's "Hasn't moved since
    20:15" is itself the proof that the gate works - a night at home that never shows it means the trigger is not
    armed. QA reads that without adb.
12. **Network location (Google Location Accuracy) off: no PWA line now** (Q4). It is a Google setting, not a choice
    the PWA offers, and with D3-D6 its cost is small. It goes into the per-model checklist and
    `docs/setup/google-account.md` as a conscious choice: off means indoor fixes fail (the trail has gaps, the still
    line covers home) and zones' fences hear only GPS fixes - the fence's own 30-min balanced poll finds nothing.
13. **Server: the weather reads the vouched time.** `weather::cell_of` takes the newest fix whose
    `MAX(captured_at, COALESCE(resent_at, captured_at))` is within 6 h (the same `instant` format), so a phone still
    since 20:00 keeps its own weather cell in the morning. Plus one test pinning that a `still` report without zones
    shows "Hasn't moved since" on Locate (no code).

## 3. Failure modes

| Case | What happens | Bound |
|---|---|---|
| No sensor (emulator, some models) | `NO_SENSOR`: fixes every beat | today's behaviour |
| Sensor never fires (broken, OEM batching, UID idle) | `STILL` vouches a fix that may be wrong | 4 h past the last fresh fix, unless the same Wi-Fi AP (then it is right anyway); the first fresh fix elsewhere distrusts it for 7 days (D7) |
| Trigger registration refused | `ensureArmed` sets `moved = true`: fixes | today's behaviour, logged |
| Process death / reboot / anchor stopped | moved (D8) | one extra fix |
| Carried in a car, phone "still" in a pocket | the walk to the car fires the one-shot first; AOSP's HAL definition lists "sitting in a moving car, coach or train" as significant motion | if it missed: D7 |
| Phone left at a friend's | still there: "Hasn't moved since" is true; their Wi-Fi rarely connected -> past 4 h `QUIET`, a try per 4 h | honest age after 4 h |
| Moved, then indoors with no network location | one failed try, then `QUIET` until the next walk or 4 h | D3 |
| Mesh Wi-Fi, roaming between nodes | another BSSID: no witness -> `QUIET`/a try | battery only |
| Clock set back | `since < 0` counts as due, as today; `lastTryMs` in the future counts as old | - |
| Zones crossing | `markMoved`, fixes as today | - |

## 4. Android 14-17

- `TYPE_SIGNIFICANT_MOTION` / `requestTriggerSensor`: API 18; the idle-UID rule since Android 9; no change found in
  14-16's behaviour-change lists; 17's to be read at build time. The sampling-rate limits of 12+ touch continuous
  sensors only.
- `getCurrentLocation(provider, LocationRequest, ..)` and `FUSED_PROVIDER`: API 31, under minSdk 34, no gate; the
  platform caps the duration at 30 s on 14-17 (our 15 s is below it).
- The BSSID through `FLAG_INCLUDE_LOCATION_INFO`: API 31; redacted to `02:00:00:00:00:00` without fine location or
  with Location off - read as no witness. `WifiManager.getConnectionInfo` is not used (deprecated since 31).
- FGS: the anchor's `specialUse` type is untouched by 15's FGS timeouts (dataSync/mediaProcessing only).
- The emulator has no significant-motion sensor: it can only run the `NO_SENSOR` path (balanced first, D9) on 14 and
  17; the still paths are unit-tested and device-checked (§7).

## 5. Battery, before and after (estimates, 30-min interval; a GPS try 0.25-0.4 mAh, a balanced fix 0.1-0.25 mAh,
design 64 §5; the 30-min sync itself is unchanged and not counted; the motion trigger and a BSSID read ~0)

| Case | Today | After |
|---|---|---|
| Night at home 22-07 (18 beats), no zones, network location off (Jelly Star) | 18 GPS tries: 4.5-7 mAh, PWA ages | 0 fixes with the witness, 2 tries without it: 0-0.8 mAh, "Hasn't moved since 21:40" |
| Night, zones, network location off | 8 still beats, then 10 GPS tries: 2.5-4 mAh, PWA ages after 4 h | as above |
| School day 08-15 (14 beats), no zones, network location off | 14 GPS tries: 3.5-5.5 mAh | ~4-6 tries (after walks), the rest `QUIET`: 1-2.4 mAh |
| Whole day, no zones, network location off | 48 GPS tries: 12-19 mAh (0.6-1 %) | ~10-15 tries: 2.5-6 mAh (0.1-0.3 %) |
| Whole day, no zones, network location on | 48 GPS tries (GPS-first): 12-19 mAh | ~10-15 balanced fixes: 1.5-3.5 mAh |
| Whole day, zones | design 64 §5: 17-30 mAh, mostly the fence poll and the series | the same less ~2-4 mAh of night tries |

So the saving is ~10-15 mAh a day, about 0.5-0.75 % of the battery: worth having, but the larger gain is a PWA that
says where the phone is now, and fewer location-indicator flashes on the kid's screen.

## 6. Size and steps

- **L1 (launcher, M-small, one commit):** the gate (D1), `MotionGate`'s two flags, `arm(fresh)`, `watch(zones)`,
  the distrust (D3, D7), `heartbeatDue`/`reportStill` with `QUIET` and the witness (D4, D6), the BSSID reader
  (`zones/WifiWitness.kt`, GPL), balanced first with the floor (D9), the anchor's `markMoved` (D8), log lines (D11).
  ~150 lines of code. Docs: `docs/notes/launcher/zones-and-power.md` (the gate, the two flags, the witness),
  `docs/testing/new-phone-model.md` §2 (the sensor check below; the network-location line), `docs/setup/google-account.md`.
- **S1 (server, S):** the weather query (D13) and its test; the still-without-zones Locate test. ~40 lines.
- No contract file changes: `zone_watch.still` and `motion_sensor` are in `zone_status_report.json` already, and the
  rules are the phone's alone (`ZonePlanTest`).
- Order: S1 any time; L1 after Q1-Q3. Design QA on this note first, code QA after L1; the emulator `NO_SENSOR` run on
  14 and 17 with the user's OK; then §7 on the Jelly Star.

## 7. Tests and the device check

- `ZonePlanTest`: the gate's table; `heartbeatDue` over motion x fresh-age x try-age x witness (`STILL`, `QUIET`,
  `FIX`, the cap gap of §1 as a regression row); `reportStill`; the contradiction rule's distances and missing
  accuracies; the floor's keep-the-better. `LocationPolicyTest` unchanged. A source check: `ZoneWatch.apply` calls
  `watch(zones = false)` without zones, the anchor's `onDestroy` calls `markMoved`, the witness never enters a DTO.
- **The Jelly Star** (the checklist's new lines, `adb` over Wi-Fi):
  1. After a due fix: `adb logcat -s ZoneWatch` shows `Motion trigger armed (fresh)`;
     `adb shell dumpsys sensorservice | grep -i -B1 -A3 significant` lists a registration by uid **10232**.
  2. A walk of a minute: `Motion trigger fired`; the next beat fixes.
  3. A night on the nightstand on home Wi-Fi: the PWA shows "Hasn't moved since ..." and Fresh in the morning;
     `dumpsys location` shows no GPS request from 10232 overnight; `dumpsys batterystats me.vibb.launcher` before and
     after, against a night on today's build.
  4. Off Wi-Fi indoors past 4 h: one try, then quiet; the PWA's age is the fix's own.

## 8. Questions for the user (visible choices; defaults in bold)

- **Q1** Past 4 h still, when no fix can be had (indoors, network location off): **trust the same Wi-Fi access point
  as a witness** (all night "Hasn't moved since 21:40", no fix; the BSSID's hash stays on the phone) / trust the motion
  sensor alone (also away from Wi-Fi; a broken sensor can then claim "still" for hours) / no witness (after 4 h the
  PWA shows the fix's real age until the morning).
- **Q2** Phones without zones show "Hasn't moved since 21:40" on Locate like zones phones: **yes**.
- **Q3** Phones without zones take a Wi-Fi/cell fix first (±20-100 m) instead of GPS (±5-15 m) when network location
  is on, GPS when that is worse than 200 m: **yes** (the trail's points get a little coarser in town).
- **Q4** Network location off: **only the setup runbook and the model checklist** / a quiet note in the zones ⓘ (needs
  a new report field).
