# 124 - Location while the phone lies still

Status: **revision 2 - step 1 approved 2026-11-22 ("Ja") and built (L1 + S1, branch `location-still-build`), awaiting
code QA and the Jelly Star's §6-§7; step 2 not built**, 2026-11-22. Asked by the user: "hva tror vi om å be om location hver
30 minutt slik vi gjør nå, hvor mye batteri vil det kreve? ... nå kjører den jo på med hvert 30 minutt hele natten mens
man er hjemme ... Har vi gjort dette på en optimal måte?", then "Jeg er interessert i forbedringene du kommer med, når
det ikke er bevegelse etc i telefonen. Men tenk gjerne nøye gjennom det", then "da må vi jo sikre at vi klarer å hente
den sensoren - hva skal til?". The user's direction after revision 1 (final yes pending): **build step 1 now without the
Wi-Fi witness**, phones without zones show "Hasn't moved since", balanced first only when the network provider is on.
Revision 2 applies the design QA (H1-H2, M1-M3, L1-L8; §9) and the Jelly Star's `dumpsys location` of 2026-11-21/22.
Launcher round plus a small server change; no contract file changes. Read against `main` 2e3796699.

Amended 2026-11-25 (D5, the owner's decision after a still day on the Jelly Star): a failed try at or past the 4-h cap
while the sensor hasn't fired is no evidence of a move - `STILL` (`still: true`) goes on, the next try after the next
two beats, then 1 h, 2 h, every 4 h, starting over at a fresh fix, a renewal or a fire. The cap try keeps the vouch as
the reported fix unless it brings a genuinely fresh fix (never an unchecked Wi-Fi fix or a last-known fallback) and,
failed, re-sends it with `still`. The bound on trust is now **24 h** (`STILL_CEILING_MS`, provisional - the owner may
change it) from the last fresh fix or renewal: past it with every try failed, `QUIET` and the fix's own age, the tries
going on every 4 h. D6's 4 h is now when the vouch must be checked, not how long it may hold. `QUIET` otherwise only
after a fire. `docs/notes/launcher/zones-and-power.md` has the rule.

## 0. Short answer

Today's 30-min fix costs roughly **12-19 mAh a day (0.6-1 % of 2000 mAh)** - an estimate, not a measurement. It is
cheap, not a battery hog, but not optimal: the Jelly Star starts its GPS every 30 min all night, and from midnight to
~04:40 every one of those starts found nothing. Design 64's cure - the significant-motion sensor gates the fix - runs
only on phones with zones; the Jelly Star has none, so Vibb never arms the sensor. Step 1 gives every Regularly phone
the still rule, stops retrying a failed fix every beat while the phone hasn't moved (it backs off up to 4 h), and fixes
a gap that also hits zones phones. Expected after: **~3-7 mAh a day**; the Jelly Star day after step 1 measures it.

What the parent sees, honestly: "Hasn't moved since 20:30" for up to **4 h after the last successful fix**, when no
move came after it. Past that, or when the last try after a move failed, the PWA shows the fix's real age - as today,
with fewer GPS starts. A whole night of "Hasn't moved since" needs either a fix that succeeds at night or a second
witness (the Wi-Fi access point, §8), which waits for the measurement.

## 1. What the phone does today (read in the code and on the Jelly Star)

| | Regularly **with** zones (`zoneWatchActive`) | Regularly **without** zones |
|---|---|---|
| Location step | `syncLocationAction` -> `heartbeatDue` (`ZonePlan.kt:215`) | `locationAction` (`LocationPolicies.kt:51`): `FRESH` every interval |
| Due fix | `FRESH_BALANCED`: fused balanced 15 s, then GPS 15 s, then last-known (`LocateCommands.freshOrLastKnown`) | `FRESH`: `preciseFix(false)` asks the first enabled of gps/network - **GPS only**, 15 s; then last-known |
| Still skip | `STILL` while `MotionGate` hasn't fired since the last genuinely fresh fix, < 4 h | none |
| Motion sensor | `MotionGate.watch`/`arm` | **never armed** (`MdmSyncWorker.kt:898`: `NO_SENSOR` when `!zonesOn`; `ZoneWatch.apply` calls `MotionGate.stop`) |
| `zone_watch.still` | `reportStill` | never |
| Backstop anchor (`BackstopAlarm.schedule`) | the beat | the last attempt |

**The Jelly Star** (Android 16, 2026-11-21/22): Vibb is uid 10232. The phone has the sensor (`significant_motion`, mtk,
wake-up); its registrations are uid 10116 (likely Play services [inferred]) and `DeviceIdleController`, **never Vibb**.
Google Location Accuracy is **on**: `network` is GMS's NetworkLocationService, `fused` GMS's FusedLocationService.
Vibb's fixes show as GnssService's network-assist requests (`LOW_POWER duration=10s`) at :11 and :41 from 23:11 to
08:11 - a GPS start every 30 min all night (before 23:11 ~52 min apart: the old inexact alarm). From 00:11 to 04:41
those requests got **no location**; from 04:51 someone's 10-min fused request received fixes and the assists were
answered at once. A parent's `locate` at 20:33 (fused high accuracy, 1 min) worked. So at night fixes fail whatever
the provider, and each failure costs a GPS start: not retrying while the phone hasn't moved is the main lever.

**A gap in the zones path too.** Past the 4-h cap a fix is demanded; when it fails, `lastFreshFixMs` isn't stamped and
the gate isn't re-armed, so every later beat is `FIX` (a GPS start each) and `still` stops.

**The server needs nothing for `still` without zones** (checked): `zone_watch` rides on every device-owner report,
zones or not; `device_api.rs:1928` marks `resent_at` from `zones::report_still` with no zone test; `Seen::still_line`
and both Locate views' fresh/old word read `seen_at()` with no zone test; `served_interval_of` floors the interval
only when `fences > 0`. One reader ignores `resent_at`: the weather's fix (`handlers/weather.rs:55`).

## 2. Decisions (step 1)

1. **One still rule for every Regularly phone.** A gate `stillWatchActive(owner, managed, policy)` = device owner,
   managed, mode `interval`, zones or not. It carries the heartbeat (`heartbeatDue`, the beat stamp, `BackstopAlarm`
   counting from the beat through `backstopAnchorMs`), `MotionGate`, `lastFreshFixMs`, `reportStill` and
   `FRESH_BALANCED`. Fences, the passive feed (KEPT), the series and the queue stay on `zoneWatchActive`. Without
   zones the kept slot is empty, so `KEPT` never happens. `ZonePlanTest:168` ("without zones the location step is
   exactly today's") becomes "not Regularly, not owner or not managed = `locationAction`".
2. **The sensor without zones: one-shot.** `ZoneWatch.apply`: zones -> `watch(zones = true)` (kept armed for the
   series, as today); Regularly without zones -> `watch(zones = false)`: armed at every sync if not armed (M3: so at
   every process start a new process catches a move from its first sync) and after every heartbeat try, not re-armed
   at a fire;
   `MotionGate.stop` (which resets to moved) only when not Regularly. `LocateCommands.clearCachedFix` (mode Off) also
   calls `markMoved`, so nothing vouches across an Off.
3. **The flags as a pure class** (`MotionFlags` in `ZonePlan.kt`, rows in `ZonePlanTest`; `MotionGate` holds one
   under its monitor). State: `movedSinceFresh`, `movedSinceTry`, `fires` (a counter), `stillSinceMs` (when the
   phone last became still since a try). Transitions: a new process -> both moved; `fire()` and `markMoved()` -> both
   moved, `fires + 1`; `afterTry(fresh, firesAtStart, now)` after a **heartbeat `FIX`** only (L7) - with `fires` the
   same as the snapshot taken before the LocationManager call (M1): a fresh fix clears both and sets `stillSinceMs` to
   the fix's time; a failed try clears `movedSinceTry` and, if it was set, `stillSinceMs = now`; with a fire during the
   attempt nothing clears (the trigger is re-armed). `locate`, the low-battery fix and a crossing's fixes go through the
   fresh-fix stamp as today but never clear `movedSinceTry` on a failure.
4. **`motion()`**: `NO_SENSOR` without the sensor or while distrusted (D7); `MOVED` while the anchor isn't running
   (`CommandListenerService.isRunning` false - L5; its `onDestroy` also calls `markMoved`) or `movedSinceTry`;
   `STILL_SINCE_TRY` when only `movedSinceFresh`; else `STILL`.
5. **The heartbeat** (pure `heartbeatDue`; `lastTryMs` = `lastActiveLocationFetchAtMs`, stamped at every attempt):
   not due -> `NOT_DUE`; a kept unreported fix -> `KEPT`; `STILL` and the last fresh fix < 4 h old -> `STILL` (no fix,
   `still: true`); `STILL` or `STILL_SINCE_TRY` and `now - lastTryMs < backoff` -> new `QUIET` (no fix, no `still`: the
   fix's own age shows); else `FIX`. `backoff = clamp(lastTryMs - stillSinceMs, interval, 4 h)`: each failed try while
   still waits as long as the phone has already been still and failing - after a move at 20:10 and failures from 20:30
   (`stillSinceMs` 20:30), tries at 20:30, 21:00, 21:30, 22:30, 00:30, 04:30 instead of every 30 min; after a good fix
   at 20:30, `STILL` to 00:30, then one try per 4 h (00:30, 04:30). `reportStill` = `CACHED` while the `STILL` condition holds (between beats too, as today). A move
   (a fire) always gives a try at the next beat. *Superseded in part 2026-11-25 (the amendment above): past the cap
   while `STILL` a failed try no longer leads to `QUIET` - `STILL` goes on with its own retries up to 24 h; the
   back-off here is now `STILL_SINCE_TRY`'s alone.*
6. **The cap stays 4 h**, no night rule, not tied to the interval (a clock rule is wrong at a sleepover; an
   interval-tied cap is 10 min at the floor). Since the 2026-11-25 amendment it is when the vouch is checked (a fresh
   fix, D7's distance check on it, or a renewal); while those tries fail, the vouch holds up to `STILL_CEILING_MS`
   (24 h) - that is the bound on trust in a sensor that might not fire.
7. **A sensor that lies is caught** (L4): a genuinely fresh own fix taken while `motion()` said `STILL` (a cap try, a
   `locate`, a low-battery fix) whose distance from the vouched fix exceeds `accOld + accNew + 200 m`, both accuracies
   known and <= 100 m, is a contradiction. **Two within 7 days** (CE prefs `zone_watch` `motion_contradictions_v1`:
   the instants) -> `motion_distrust_until_ms` = now + 7 days: `NO_SENSOR` meanwhile (every due beat fixes, today's
   behaviour) and `zone_watch.motion_sensor: false` (the existing field; the zones footer already words it). Logged.
8. **Delivery of the trigger** ("hva skal til?"): armed on every Regularly phone (D2); Android 9+ delivers one-shot
   sensors only to a UID that isn't idle - the anchor (`specialUse` FGS) keeps 10232 active, and without it `motion()`
   is `MOVED` (D4); a reboot, process death or self-update starts moved, re-armed at the first sync (D2) - one try,
   and the PWA shows the age until the next good fix; no sensor -> every due beat fixes, as today.
9. **Balanced first only with the network provider on** (M2). A heartbeat `FIX` on every Regularly phone: if
   `isProviderEnabled(NETWORK_PROVIDER)`, the fused balanced fix first (15 s); worse than 200 m
   (`PASSIVE_ACCURACY_FLOOR_M`; a rural cell fix can be km) -> GPS once and keep the better; nothing -> GPS. Network
   off -> GPS directly (today's no-zones path; no 15-s wait on a fused call that can't answer). The series' checks
   stay balanced only; `locate` keeps its own path (fused high accuracy, 60 s). In `freshOrLastKnown`, with a log line
   naming the path and the result (D11). Decided at the code QA (L4): the same path for every `FRESH_BALANCED` fix -
   a crossing's first fix and the low-battery fix too (`LocateCommands.balancedFirst`); fused not there counts as
   network off (`path=gps`).
10. **Unchanged:** `locate` (always fresh), the low-battery fix (one balanced fix per episode, by D9's path), crossings
    (`markMoved`; the first fix by D9's path), "Only when asked", Off, the pause, P-L3's age limit (a `still` re-send
    has none, as today).
11. **What is visible, no new data.** Log lines under `ZoneWatch`: `Motion trigger armed (start|fresh|try)`,
    `Motion trigger fired`, `Motion trigger refused`, `Location heartbeat: STILL|QUIET|FIX path=balanced|gps|balanced+gps
    result=fresh|none backoff=..`, `Motion contradiction`, `Motion sensor distrusted until ...`. No new report field:
    `motion_sensor` says whether the phone uses one; "Hasn't moved since" on Locate shows the gate working without adb.
12. **Network location off: no PWA line** (Q3). On the Jelly Star it is on; off, it goes into the per-model checklist
    and `docs/setup/google-account.md` as a conscious choice (indoor fixes fail; zones' fences hear only GPS).
13. **Server: the weather reads the vouched time.** `weather::cell_of` takes the newest fix whose
    `MAX(captured_at, COALESCE(resent_at, captured_at))` is within 6 h (`zones::instant` and `fix_stamp` share the
    format). Plus one Locate test: a `still` report without zones shows "Hasn't moved since" (no code).

## 3. Failure modes

| Case | What happens | Bound |
|---|---|---|
| No sensor | `NO_SENSOR`: fixes every beat | today's behaviour |
| Sensor never fires (broken, OEM batching, UID idle, a fire missed in deep Doze) | `STILL` vouches a fix that may be wrong | a try every 4 h: a good fix replaces the vouch (and two contradictions distrust the sensor for 7 days); while every try fails, up to 24 h (`STILL_CEILING_MS`, amended 2026-11-25), then the age |
| Registration refused / anchor not running | `MOVED`: fixes | today's behaviour, logged |
| Process death, reboot, night self-update | moved; one try; the vouch ends | the age shows until the next good fix |
| A fire during the 15-60 s attempt | the counter keeps it moved | M1 |
| Evening walk home, then the indoor try fails | `QUIET` with back-off, no vouch; the PWA shows the last good fix's age | ~6 tries a night instead of 18 |
| In a car, phone "still" in a pocket | the walk to the car fires the one-shot; AOSP's HAL definition counts "sitting in a moving car, coach or train" | if missed: the cap and D7 |
| Moving car, sensor silent (a vendor's sensor re-armed mid-trip misses a smooth drive) | a fresh fix on the move - speed over 1.5 m/s, or without a speed more than 200 m + both accuracies from the last fresh fix within the interval (`fixMoving`) - leaves the gate `MOVED`: no `STILL`, no `QUIET`, every due beat fixes | until a fresh fix at rest (added before the merge, the user's question) |
| Phone left at a friend's | still: "Hasn't moved since" is true; the 4-h tries renew it, and while they fail it holds up to 24 h, then the age | `STILL_CEILING_MS` |
| Clock set back | negative ages count as due / old, as today | - |
| Zones crossing | `markMoved`, fixes as today | - |

## 4. Android 14-17

- `TYPE_SIGNIFICANT_MOTION` / `requestTriggerSensor`: API 18; the idle-UID rule since 9; nothing in 14-16's
  behaviour changes; 17's list to be read at build time. 12+'s sampling-rate limits touch continuous sensors only.
- `getCurrentLocation(provider, LocationRequest, ..)`, `FUSED_PROVIDER`: API 31, under minSdk 34; the platform caps
  the duration at 30 s on 14-17 (ours is 15 s). `isProviderEnabled(NETWORK_PROVIDER)` is false on a GMS phone with
  Location Accuracy off [device check on a phone with it off; the Jelly Star has it on].
- FGS: the anchor's `specialUse` is untouched by 15's FGS timeouts.
- The emulator has no significant-motion sensor: it runs the `NO_SENSOR` path (D9's provider choice) on 14 and 17;
  the still paths are unit-tested and device-checked (§7).

## 5. Battery, before and after

Estimates (design 64 §5: a GPS try 0.25-0.4 mAh, a balanced fix 0.1-0.25 mAh; the 30-min sync itself unchanged and not
counted; the trigger ~0). Network location on (the Jelly Star), 30-min interval. **To be replaced by the measurement.**

| Case | Today | Step 1 |
|---|---|---|
| Night 22-07 (18 beats), the evening fix succeeded | 18 GPS starts: 4.5-7 mAh; the age shows | 2 cap tries (00:30, 04:30) when they succeed: 0.5-0.8 mAh; when they fail (amended 2026-11-25) 5 tries (00:30, 01:00, 01:30, 02:30, 04:30): 1.3-2 mAh; "Hasn't moved since" all night |
| Night, the evening try after the walk home failed | 18 GPS starts: 4.5-7 mAh | ~6 tries with back-off: 1.5-2.4 mAh; the age shows |
| Night with a self-update at 03:00 | as above | + one try |
| School day 08-15 (14 beats) | 14 GPS starts: 3.5-5.5 mAh | ~4-6 tries after walks, the rest `STILL`/`QUIET`: 0.5-2 mAh |
| Whole day | 48 GPS starts: 12-19 mAh (0.6-1 %) | ~12-20 tries, by day mostly balanced: 3-7 mAh (0.15-0.35 %) |
| Whole day, zones | design 64 §5: 17-30 mAh | less the night's retries: ~2-4 mAh |

The saving is ~8-13 mAh a day (~0.5 %): worth having, plus fewer location-indicator flashes; the PWA is current more
often, not always.

## 6. Measurement plan

1. **Baseline (have it):** the Jelly Star's `dumpsys location` of 2026-11-21/22 - a GPS start every 30 min, none
   answered 00:11-04:41. Add `dumpsys batterystats me.vibb.launcher` (GPS time, wake locks) over the next night on
   today's build.
2. **Step 1 on the Jelly Star** (release build), one ordinary day and night: count the GPS starts in `dumpsys location`
   (GnssService's assist registrations) and the D11 heartbeat lines (`adb logcat -s ZoneWatch`); the same
   batterystats; note when the PWA showed "Hasn't moved since" and the age.
3. **Then decide step 2** (§8): if the nights mostly end in `QUIET` with an aged fix, the Wi-Fi witness; if the cap
   tries mostly fail, a longer cap; if fires come without moves (a step-based MediaTek SMD - H2), their count per day.

## 7. Tests and the device check

- `ZonePlanTest`: `stillWatchActive`'s table; `MotionFlags` (every transition, the fire counter across an attempt, L7's
  failure from `locate` clearing nothing); `heartbeatDue` over motion x fresh-age x try-age x back-off (the §1 cap gap
  as a regression row; both sequences of D5); `reportStill`; the contradiction rule (distances, accuracies over
  100 m and missing, one vs two in 7 days); the provider choice and keep-the-better. A source check: `ZoneWatch.apply`
  calls `watch(zones = false)` without zones and `stop` only when not Regularly; the anchor's `onDestroy` and
  `clearCachedFix` call `markMoved`; `motion()` reads `isRunning`.
- **The Jelly Star** (new lines in `docs/testing/new-phone-model.md` §2):
  1. After the first sync: `Motion trigger armed (start)`; `adb shell dumpsys sensorservice | grep -i -B1 -A3
     significant` lists a registration by uid **10232**.
  2. **After at least an hour screen-off** (deep Doze - L6): a walk of a minute logs `Motion trigger fired`, and the
     next beat fixes.
  3. A still evening: the PWA shows "Hasn't moved since"; overnight `dumpsys location` shows at most the cap tries.

## 8. Later (step 2, after the measurement)

The **Wi-Fi witness** (revision 1's D6): past the cap, the same access point as at the last good fix counts as still.
Before it is built: access points that move (a parent's hotspot, bus, train and car Wi-Fi - H2) must not count:
only an unmetered, non-hotspot network; several BSSIDs per router and mesh nodes (L2); an HMAC with a per-install key
instead of a plain SHA-256 (L3: the BSSID space is small); Location on and background location for the read (L1).

## 9. Revision 2 - the design QA

H1 (§0/§5 promised a whole night "still"): rewritten honestly; the evening's failed try and a new process end the
vouch; measure first (§6). H2: the witness moved to step 2 with its conditions. M1: the fire counter (D3). M2: balanced
only with the network provider on (D9). M3: armed at every process start (D2). L1-L3: §8. L4: two contradictions,
accuracies <= 100 m (D7). L5: `MOVED` without the anchor (D4). L6: the Doze walk (§7). L7: only the heartbeat's `FIX`
(D3). L8: `BackstopAlarm`, `apply`'s `stop`, `freshOrLastKnown`, `clearCachedFix`, `ZonePlanTest:168` named; the
back-off (D5) added for the H1 case; steps split (§10).

## 10. Size and steps

- **Step 1, L1 (launcher, M-small, one commit):** D1-D11, ~150 lines of code. Docs: `docs/notes/launcher/zones-and-power.md`,
  `docs/testing/new-phone-model.md` §2, `docs/setup/google-account.md`.
- **Step 1, S1 (server, S):** D13 and its test, the still-without-zones Locate test; ~40 lines.
- No contract file changes: `zone_watch.still` and `motion_sensor` are in `zone_status_report.json`; the rules are the
  phone's alone.
- Order: code QA after L1 + S1; the emulator `NO_SENSOR` run on 14 and 17 with the user's OK; §6-§7 on the Jelly Star.
- **Step 2 (L2, the witness):** only after §6, and the user's yes.

## 11. Questions for the user (defaults in bold)

- **Q1** Phones without zones show "Hasn't moved since 21:40" on Locate like zones phones: **yes** (the user's
  direction).
- **Q2** With network location on, Regularly takes a Wi-Fi/cell fix first (±20-100 m) instead of GPS (±5-15 m), GPS
  when that is worse than 200 m: **yes** (the trail's points get a little coarser in town; the user's direction).
- **Q3** Network location off: **only the setup runbook and the model checklist** / a quiet note in the zones ⓘ (a
  new report field).
- **Q4** (after the measurement) The Wi-Fi witness for whole nights of "Hasn't moved since": decided then.
