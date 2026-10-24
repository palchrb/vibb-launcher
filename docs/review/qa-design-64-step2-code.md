# Code QA: design 64 step 2 (safe zones on the phone, the contract)

Branch `design-64-step2` at 41510352 against main `961d55a1`: 2ccd571f (the contract in both directories), eee466dd (the
launcher's zone watch), e42a4d38 (the server's Locate page), 41510352 (docs). Reviewer's branch `qa-design-64-step2` =
41510352 + this file. Design: `docs/design/64-safe-zones.md` §1, §4, §5, §6, §9 and both "As built" sections;
`docs/notes/server/zones.md`; the step-1 code QA `docs/review/qa-design-64-step1-code.md`.

**Not reviewed**: the fifth commit (§4.5's moving series, the `provider`/`mock` columns, removing the fences at
un-enrol), which the implementer was still adding while this review ran. Q-8 and parts of Q-2 touch its scope.

## Setup

- Worktree checked out at 41510352. I read `git diff 961d55a1..41510352` in full. I also read the code around it:
  `performMdmSync` (the policy fetch, `apply`, the location step, the report and its `catch`), `LocateCommands.currentLocation`
  and the cache, `locationAction`, `backstopDelayMs`/`BackstopAlarm`, `zones::fresh_for`/`here_now`/`freshness_inputs`,
  `judge`/`store_fix` (step 1), `device_api::status`'s previous-report read, `ServerJson`, the manifest's alarm
  permissions, and `Provisioning`'s enrollment clears.
- Launcher suite under the shared lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true
  assembleDebug testDebugUnitTest`):

  ```
  BUILD SUCCESSFUL in 2m 23s
  54 actionable tasks: 54 executed
  tests=1693 failures=0 errors=0 skipped=0
  ```

  The only warning is kapt's `Supported source version 'RELEASE_11'` line (both kapt tasks).
- Server (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-design-64-step2 CARGO_BUILD_JOBS=2 CARGO_PROFILE_DEV_DEBUG=0`, the
  test and clippy runs under `flock ~/build.lock`):

  ```
  cargo test:        test result: ok. 1258 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 351.28s
  cargo fmt --check: exit 0
  cargo clippy --all-targets: `kid_phone_server` (bin "kid_phone_server") generated 13 warnings
  ```

  These are the 13 known warnings (`models.rs` dead fields, `dns_engine.rs:54-55`, `devices.rs:1324`, `storytel.rs:144`).
  None of them is in a file this diff touches.
- Mutations: applied by an exact-replace script (each pattern had to match exactly once), in batches with at most one
  mutation per test method. The launcher's batches ran the whole unit suite and the server's ran `cargo test zone`.
  After each batch the sources were put back with `git checkout`. The worktree was clean (`git status` empty, apart from
  the ignored `local.properties`) before this file.
- No emulator (as briefed).

## What was checked and holds

- **The contract, both directories, one commit (2ccd571f).** `location_policy` is always `{mode, interval_minutes,
  zones}`, with `zones` as `[{id, latitude, longitude, radius_m}]` only while the mode is `interval`. It never carries a
  name: the server's `PolicyZone` has no such field, and `the_policy_serves_the_zones_and_the_floor` asserts that "Home"
  is absent. `policy_json_keys_snapshot` pins the three keys, and `time_rules`' policy assertions follow. The launcher's
  `LocationPolicy.zones` defaults to `[]`, so an older server's policy decodes as "no zones". The cache round trip keeps
  them (`PolicyResponseCompatTest`). An older launcher drops `zones` (`ignoreUnknownKeys = true`) and is never given the
  floor, because it reports no watch. `zone_status_report.json` is byte-identical in the two directories (`cmp`).
  `ZoneReportTest` encodes exactly that file and compares the copies. `a_launcher_report_raises_the_arrival` posts it
  with its instants moved and compares too. Both workflows' path filters list the other side's copy, and root
  CLAUDE.md's shared-files paragraph and CI paragraph name it. `zones_v1` is claimed but read by nothing, so the watch
  (`fences > 0`) is the real capability; that is honest and documented. Migration 0092 is a single `ALTER`, and
  `migrations_73_to_92_in_order` covers it.
- **The server intake.** `report_fixes` checks every extra, sorts the extras by their stored text (which is time order),
  keeps the newest 20 and appends the report's own fix. `intake` stores all of them first, each on its own (step 1's
  dedupe, so a duplicate inside one report is one row), then judges once. If nothing could be stored there is no
  judging. A junk entry, or `extra_locations` that isn't a list, drops only that entry (`lenient_locations`). Two
  parallel reports with the same pair raise one crossing (the cursor plus `BEGIN IMMEDIATE`; tested). `sanitize_zone_watch`
  keeps the five keys with their types and ranges, or nothing. The floor is `served_interval`: `max(chosen, 30)` only
  with Regularly, zones and a watch holding fences, from the latest report since enrollment. It is never written, and the
  parent's interval comes back when the last zone goes (tested). "Here now" counts in the served interval
  (`freshness_inputs`). The Locate page's note under "Every", the footer without "two intervals" for a watching phone,
  the watch's line (fences / no sensor / Location off / no background grant / not set up yet) and the mock row all hold,
  and their wording is in the PWA's register. The security line names the phone and counts, never a place.
- **The fences' PendingIntents.** All of them use request code 8. Each one's `Intent` differs from the others by its
  data URI (`vibb-zone:<id>`), and `Intent.filterEquals` compares action, data, type, identifier, package, component
  and categories. So each zone has its own PendingIntent. `pendingIntent(context, id)` for the removal returns that same
  token, so `removeProximityAlert(pi)` and `pi.cancel()` hit the right fence, and the next `getBroadcast` makes a fresh
  one. They are `FLAG_MUTABLE | FLAG_UPDATE_CURRENT`, explicit to `ZoneAlertReceiver` (`exported="false"`; the manifest
  pin is in `ZonePlanTest`), with expiration -1. The receiver checks the action, the scheme and a numeric id.
  `zoneFencePlan`:
  - the first sync of a process removes ours and adds all;
  - a changed fingerprint does the same;
  - not watching or `[]` removes all;
  - a partial add leaves the record without a fingerprint, so the next sync plans again. That is once per sync, with no
    loop inside a sync: cheap, and the record is right.

  A reboot or a force-stop (an update) drops the platform's fences or our PIs, and the fresh-process rule re-adds them.
- **The confirming fix.** `ZoneConfirmReceiver` is not exported and its PendingIntent is immutable and explicit (request
  code 9; a different component from BackstopAlarm's 7, so no clash). It is armed with `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`.
  The manifest already declares `USE_EXACT_ALARM` (granted at install time, as `TimeRuleAlarm` relies on), so
  `canScheduleExactAlarms()` is true and the inexact fallback is only defensive. A second fence broadcast within 2 min
  moves the one alarm (`FLAG_UPDATE_CURRENT`), so one confirm follows the latest crossing.
- **The crossing's fix.** `freshWanted` is taken once, in the location step. A sync already past that step leaves the
  flag for the coalesced next run. When the server can't be reached the sync still reaches the location step (the policy
  fetch only returns `UNREACHABLE`), so a crossing in a tunnel still takes its fix and queues it with the failed report.
- **The queue, traced for loss and duplicates.**
  - A report the server took removes exactly what it carried.
  - A report that failed queues its own `location`. A CACHED re-send is queued once, because entries are keyed on time
    and place.
  - An exception before or during the POST takes the `catch`, which queues the fix (`zoneQueueDone` stops a second
    `afterZoneReport`).
  - The server took the report but the phone saw a timeout: the next report re-sends, and the server's dedupe stores
    one row. No fix is sent twice inside one report (`extraLocations` leaves out the report's own `location`).
  - The process dies between the POST and `afterZoneReport`: the queued extras are re-sent and deduped.
  - The process dies before the POST: that report's fresh fix is not queued, but it is still the cached fix, so the
    next report sends it unless a newer own fix replaced it first (crash only, accepted).
- **The mock rule** covers the fresh fix, each last-known candidate and each passive fix. The cached fix keeps its mock
  flag, and a release build can't cache a mock fix. The debug build takes mock fixes and says so with `mock: true`
  (pinned by `ZoneReportTest`).
- **Background location** is still self-granted by DPM, with the same three permissions as `LocateCommands`
  (unchanged). **Pause/override** change nothing (`ZoneWatch.apply` ignores them). **"Keep location on"** is untouched.
  **Kid Settings**: the line shows as device owner with an `Ok` cached policy whose mode isn't `off`
  (`on_request` and an older server's policy included); the wording is calm, in both locales, with no control; pinned
  by `KidSettingsModelTest`.
- **Privacy, names.** No zone name reaches the phone. `zone_watch` is counts and switches only. The provider string is
  cut to 16 characters. Q-1, Q-5 and Q-8 below are the exceptions to "nothing new leaves the phone but the fixes the
  parent's setting asked for".

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| L1 | `zoneFencePlan`: a fresh process re-registers nothing (drop `!freshProcess`) | `a new process registers every fence again...` |
| L2 | `zoneFencePlan`: fingerprint ignored (stored record = no change) | `a changed zone re-registers all...` |
| L3 | `zoneFencePlan`: `watching` ignored | `a changed zone re-registers all...` |
| L4 | `fenceVerdict`: an "exiting" in the window is an echo too | `the registration's echo...` |
| L5 | echo window inclusive of 60 s (`0..`) | `the registration's echo...` |
| L6 | `heartbeatDue`: clock set back is not due (`since < interval`) | `the heartbeat fixes when due...` |
| L7 | KEPT accepts a negative age | `the heartbeat fixes when due...` |
| L8 | STILL bound 4 h -> 8 h | `the heartbeat fixes when due...` |
| L9 | STILL also without a sensor (`!= MOVED`) | `the heartbeat fixes when due...` |
| L9b | STILL with our last own fix in the future | `the heartbeat fixes when due...` |
| L10 | `queueWith` keeps the oldest 20 | `the queue keeps each fix once...` |
| L11 | a taken report leaves its `location` queued | `a report the server took...` |
| L12 | a failed report doesn't queue its `location` | `a report the server took...` |
| L13 | Off doesn't empty the queue | `a report the server took...` |
| L14 | passive accuracy floor exclusive (`>=`) | `a passive fix replaces the cached one...` |
| L15 | passive "newer" accepts the same time | `a passive fix replaces the cached one...` |
| L16 | mock refused in debug too | `a release build ignores a mock fix...` |
| L17 | `extraLocations` keeps the report's own fix | `the extras are the queue without...` |
| G1 | glue: `MotionGate.arm` after our own fix removed | **not caught** |
| G2 | glue: `BackstopAlarm` back to `lastActiveLocationFetchAtMs` | **not caught** |
| G3 | glue: a due heartbeat (KEPT/STILL) doesn't stamp the beat | **not caught** |
| G4 | glue: the report's `catch` doesn't queue the fix | **not caught** |
| G5 | glue: `countMock` counts the same fix again | **not caught** |
| G6 | glue: a partial fence record keeps a fingerprint (no retry) | **not caught** |
| G7 | glue: `onFence` takes zones from a non-Regularly cached policy | **not caught** |
| G8 | glue: `ZoneWatch.apply` watches without device owner | **not caught** |
| G9 | glue: a passive mock fix isn't counted | **not caught** |
| S1 | `MAX_EXTRA_LOCATIONS` 20 -> 25 | `a_reports_fixes_are_checked_capped_and_ordered` |
| S2 | the cap keeps the oldest 20 | `a_reports_fixes_...`, `extra_locations_are_all_stored_then_judged_once` |
| S3 | (not run: the sort; S2/S8 cover the order around it) | - |
| S4 | no floor (`chosen` as is) | `the_served_interval_...`, `the_policy_serves_the_zones_and_the_floor`, Locate test |
| S5 | the floor without a watch (`&& watching` dropped) | `the_served_interval_...`, the policy test |
| S6 | `last_zone_watch` reads reports before the enrollment | **not caught** |
| S7 | the mock line counts from 0 every time | `the_mock_line_comes_with_each_rise`, `the_mock_counter_logs_each_rise` |
| S8 | the zones judged after each stored fix (not once) | **not caught** |
| S9 | `policy_zones` ignores the mode | `the_policy_carries_the_zones_only_while_regularly` |
| S12 | `freshness_inputs` uses the parent's interval, not the served one | **not caught** |
| S13 | the Locate page's zones view gets the parent's interval | `the_locate_page_tells_the_floor_...` |

Every briefed pure rule is caught: `zoneFencePlan`, the echo window, `heartbeatDue`'s three skips and the 4-h bound,
`queueWith`/`queueAfterReport`, `fixVerdict`, the server's cap and order, and the floor. Every glue line survives, and
so do three server rules: the enrollment filter of the floor, the one judging and the "Here now" interval. See Q-11.

## Findings

### Q-1 (High, privacy) - a passive fix rides on every report, so the trail is denser than the parent's interval

`LocateCommands.kt:89` (`CACHED` returns the cached fix), `ZoneSensors.kt:83` (`keepFix` replaces it),
`MdmSyncWorker.kt:598-606` (`NOT_DUE` -> `CACHED`).

What's wrong:

- Every sync that doesn't take a fresh fix sends the cached fix as `location`. A `NOT_DUE` heartbeat, an on-request
  sync and every SSE nudge all do this.
- Before step 2 the cached fix changed only with our own fixes, so the trail had one point per own fix.
- Now `PassiveFixes` replaces the cached fix with every passive fix that is better and newer.
- So the next sync of any kind reports that fix: the 30-min backstop, a policy-save nudge, a command, an app list, a
  battery edge.

With zones, the platform fence's own hidden fused poll produces such a fix every 30 min (design §2), and so do Maps and
the system. A parent who chose "every 4 hours" therefore gets a point roughly every 30 min (the backstop). A phone
without zones gets a point at every nudge after any other app's fix.

The design promised the opposite: §4.3 says "never on its own. The parent's interval is the promise of how often the
trail gets a point". `launcher/CLAUDE.md:878-882`, the KDocs of `PassiveFixes` and `keepFix`, and the "As built" bullet
("it only replaces the cached fix, which the next due heartbeat reports") say the same. The existing pin
(`ZoneSensors` has no `SyncRunner`) checks only that the feed never triggers a sync. The trouble is that it rides on
other syncs.

Expected fix: keep a passive fix in its own slot (memory, or its own pref key), not in `CachedFix`. Promote it to the
cached and reported fix only at a due heartbeat (`KEPT`) or a crossing's sync. Between beats, `CACHED` keeps
re-sending the fix the last beat reported. Pin it by source: `keepFix` never writes `cachedLocationJson`, and only the
KEPT branch promotes. Q-4's fix uses the same slot.

### Q-2 (High, regression; needs the user's decision) - a phone without zones doesn't behave as before

`ZoneWatch.kt:68-71`, `MdmSyncWorker.kt:585-610`, `BackstopAlarm.kt:51`.

What's wrong: the brief's most important check, "a phone with no zones behaves exactly as before", fails. For every
device-owner phone on Regularly (zones or not, and also an older server's missing `location_policy`, which reads as
`LEGACY_LOCATION_POLICY` = interval):

1. `PassiveFixes` registers a `PASSIVE_PROVIDER` listener. Q-1 follows from it, and whether Android shows its location
   indicator for a passive listener is a device check.
2. `MotionGate` arms `TYPE_SIGNIFICANT_MOTION` after each own fix. On a phone with the sensor, a due heartbeat then
   skips its fix for up to 4 h (`STILL`) and re-sends the old one.
3. `heartbeatDue` replaces `locationAction`, with `KEPT` (Q-4) and `STILL`.
4. `BackstopAlarm` counts from the beat instead of the last fix. On a phone where every beat fixes, the two are the
   same. With a skip they are not.

Only the fences are gated on zones. `ZoneFences.sync` with `[]` does no LocationManager call, so no fences hold.

The design chose this: §4.4's last paragraph says "the passive source and the motion skip apply to every Regularly
phone... these two only save battery". The user's decisions in the brief don't mention it, and decision 13 reads "without
zones the phone does what the parent chose, as today". The emulator can't show it either, because it has no motion
sensor and no other app's fixes (§6t step 9).

Expected fix: put it to the user. My recommendation is to gate the passive feed, the motion gate and the heartbeat's
KEPT/STILL on a pure `zoneWatchActive(owner, policy)` = Regularly with `zones.isNotEmpty()`. Without zones, keep
`locationAction` and the old backstop source exactly. Pin it with a test that, with zones `[]`, the location action
equals `locationAction` for every input, and with a source check that `PassiveFixes.update(.., on = ..)` and `MotionGate`
take that gate. That keeps today's phones as they are. If the user keeps the design's choice instead, Q-1 and Q-4 must
still be fixed, because they hit every Regularly phone.

### Q-3 (High) - the motion skip makes "Here now" and "At Home since" go stale on a phone lying still

`ZonePlan.kt:146-152` (`STILL` up to `STILL_SKIP_MAX_MS` 4 h), `server/src/zones.rs:294` (`fresh_for` = 2 x the served
interval), `zones.rs:287` (`here_now` needs `fresh`).

What's wrong: take a phone on the kid's table at home in the evening, with a motion sensor, zones and the 30-min floor.

1. Its heartbeats are `STILL` for up to 4 h. Each one re-sends the same cached fix, and the server dedupes it, so no
   new row comes.
2. The newest `captured_at` ages past `fresh_for` (60 min).
3. From then on Home's row says "Last seen at Home 14:02" instead of "Here now", and the summary's "At Home since 15:42"
   disappears.
4. This lasts until the 4-h cap or the next movement.

That is the feature's headline line, and it fails in its most common case, the kid at home for the evening or night.
Without zones the Locate page's newest fix also reads "3 hours ago" while the phone reports every 30 min. Nothing tests
this, because the server tests post fresh fixes and the launcher tests the rule in isolation. The emulator has no
sensor, so §6t can't show it either.

Expected fix: let the server know a re-sent fix is still current. For example, `store_fix` on a duplicate stamps a
`device_locations.last_seen_at`, a `device_status`-side "fix re-sent at" goes with the report, or a `STILL`/`KEPT`
report carries the beat's time. `freshness_inputs` would then read `max(captured_at, last_seen_at)`. The fix's own time
must never be rewritten. Add a server test: an old fix re-sent within the interval keeps "Here now". If Q-2 gates the
skip on zones, this still applies to every zone phone.

### Q-4 (Medium) - KEPT counts our own, already reported fix: a due beat right after the interval skips, and the trail halves

`ZonePlan.kt:149`, `MdmSyncWorker.kt:596`.

What's wrong: `keptFixAgeMs` is the age of the cached fix, and the cached fix is usually our own last heartbeat fix.
The design's KEPT is "a kept fix... **not yet reported**" (§4.4); the code dropped that condition. A trace:

1. A `FIX` at beat time `T` stamps the beat at `T`.
2. The GPS fix arrives at `T + δ` (δ = its time to fix, 1-15 s) and is cached with that time.
3. `BackstopAlarm` aims at `T + interval`. If it fires within δ of that, or another sync lands there, the beat is due.
4. But the cached fix is `interval - δ` old, so the beat is `KEPT`: it re-sends the already-reported fix and stamps the
   beat.
5. The next fix comes only at `T + 2 x interval`.

With the screen on (alarms on time) this can alternate on every Regularly phone, zones or not. The trail then gets a
point every 2 intervals instead of every interval.

Expected fix: KEPT only for a fix newer than the one the last beat reported. Keep that fix's time with the beat stamp,
or use Q-1's separate passive slot (KEPT = that slot holds a fix younger than the interval). Add a `heartbeatDue` row:
our own fix at `lastBeat + 5 s`, now at `lastBeat + interval + 1 s` -> `FIX`.

### Q-5 (Medium, privacy) - the first report after the parent turns location Off sends the queue

`MdmSyncWorker.kt:216-217` and the report's `extraLocations = extraFixes?.map { it.toReport() }`.

What's wrong: `extraFixes` is computed from the queue whatever the mode. With Off, `location` is `null`, so
`extraLocations(queue, null)` is the whole queue, and the report sends it. The queue empties only after that send.

The realistic case:

1. The phone was offline with queued fixes (a crossing in a tunnel, failed reports).
2. The parent switches Location to Off meanwhile.
3. When the phone reconnects, it fetches the Off policy and uploads those fixes in the same report, and the server
   stores them.

`queueAfterReport`'s KDoc ("nothing kept from before the parent turned it off is sent") and `ZonePlanTest` ("Location
turned off: nothing kept from before is ever sent") claim the opposite. The test checks only the pure function, not the
send.

Expected fix: `val extraFixes = if (locationOff) null else extraLocations(...)`, and pin that line by source. Optionally,
also on the server: drop `extra_locations` (not `location`, which an Off launcher never sends) while the phone's stored
mode is `off`.

### Q-6 (Medium) - the motion gate arms after a fix that isn't new, so STILL then reports a place the phone may have left

`MdmSyncWorker.kt:610`, `LocateCommands.currentLocation` (`fresh ?: loadCachedFix()`), `ZoneSensors.kt:122-136`.

What's wrong: `MotionGate.arm` runs after any `FRESH` that returns a location. That includes the fallback to the
cached fix or to an old last-known candidate, for example when GPS times out indoors. Arming says "still from here", but
the fix it re-sends may be from before the phone moved. The trace:

1. Fix at A, then the phone moves to B (the trigger fires).
2. The next beat is `FIX`, but indoors at B the GPS fails, the last-known candidates are null or old, and the cached A
   comes back.
3. The gate arms, and for up to 4 h `STILL` re-sends A while the phone sits at B.

Before this change the next due interval tried GPS again.

There is also a small race. `onTrigger` sets `moved = true` outside the monitor and then clears `listener` inside it.
An `arm()` between those two statements sets `moved = false` and returns (the listener is still set). The listener is
then cleared, and the phone is "still" with no trigger armed until the 4-h cap.

Expected fix:
- Arm only when the fix is new: its time at or after this beat's stamp, or a `fresh`/last-known fix newer than the
  cached one. Otherwise leave `moved` as it is.
- In `onTrigger`, set `moved` and clear `listener` in one `synchronized(MotionGate)` block.
- Pin the arm's condition by source.

### Q-7 (Low, battery) - the registration's echo can count as a crossing

`ZoneFences.kt:72-86` (the record is saved after the adds), `ZoneWatch.kt:83-85`, `ZonePlan.kt` `fenceVerdict`.

What's wrong: AOSP fires the unknown -> inside "entering" from `addProximityAlert` itself (`onActive` with a last
location of 5 min or less). Our receiver can run before `saveFenceRecord` commits. In that case `onFence` reads the old
record, or none at all on a phone's first registration (`since = -1`), and calls it a `CROSSING`. The same happens when
no recent last location exists: the first poll that finds the phone inside, up to 30 min later, fires "entering" from
the unknown state, outside the 60-s window. Each of these costs two GPS fixes and two syncs at every re-registration,
which is every process start and every zone save. The server raises nothing, because the side is already known or
set silently, so no event goes wrong.

Is the 60 s window right? Yes, for the immediate echo. A real "entering" inside the first minute is dropped, but the
fence's state is then inside, and the server judges the next heartbeat's fixes, so latency is lost, not the event.

Expected fix: write the record's `registeredAtElapsed` (the in-memory stamp is enough) **before** the first
`addProximityAlert`, and treat "no record" as within the window. The late first-poll echo can stay (it is rare, and
cheap compared with a missed arrival).

### Q-8 (Low, privacy; likely the fifth commit's scope) - the queue survives an enrollment

`Provisioning.kt:49` (the clears at enrollment), `ZoneWatch.kt` (CE prefs `zone_watch`).

What's wrong: a re-enrollment clears the command answers, the PIN events and install-now, but not `zone_watch`'s
`queue_v1` (nor the fence record or the beat). The first report into the new row sends the old enrollment's queued
fixes as `extra_locations`, and the server stores them in the new row's trail. The server's floor reads only reports
since the enrollment, but the fixes are stored whatever their time. The re-sent cached fix has the same problem, from
before step 2.

Expected fix: clear the queue (and the fence record, after removing the fences) where `CommandAnswers.clear` runs.
Check it together with the fifth commit's un-enrol removal.

### Q-9 (Low) - the mock count can count a fix twice, and the security line can come twice

`ZoneWatch.kt:147-154`, `device_api.rs:1159` and `:1295`.

What's wrong: `countMock` remembers only the last counted time. A spoofing app usually mocks several providers. The
FRESH path's fallback walks the last-known fixes of network, gps and fused, and the passive feed sees fixes in between,
so two different mock fixes alternate: t1 is counted, then t2, then t1 again at the next fresh path, and so on. Every
heartbeat indoors then "rises", which is noisy in the security log. On the server, two parallel reports read the same
previous row and both log the rise. A report without `zone_watch` in between (not device owner for a moment) makes the
next rise count from 0 and log the whole running count again. Separately, the Locate row "3 made-up locations ignored"
has no time and stays for the life of the launcher's data.

Expected fix:
- Keep a small set of recently counted fix keys (time, latitude, longitude; the last 8) instead of one time.
- On the server, compare with the latest non-null watch since the enrollment.
- Optionally, give the row the last rise's date from the security log.

### Q-10 (Low, data minimisation; decide) - the zones' coordinates go to every launcher

`device_api.rs` (`build_policy`, `zones: policy_zones(..)`).

What's wrong: an older launcher, one without `zones_v1`, can't use the centres and radii, but it gets them. It drops
them when it re-encodes its cache. The brief asks whether that is fine. Functionally it is: names never go, and the
coordinates are the phone's own places. Under "privacy first", though, sending data the client can't use is
unnecessary.

Expected fix: either send `zones: []` unless the phone's latest report since enrollment claims `zones_v1` (the claim
would then gate something, and the snapshot stays the same), or keep it as is and say so in `zones.md`.

### Q-11 (Low, tests) - the glue and three server rules are unpinned

What's wrong:
- G1-G9 survive. The most important are G1 (the gate never re-arms), G2 (the backstop's source), G3 (a skip that
  doesn't stamp turns every sync into a heartbeat check), G4 (the `catch` that queues the fix) and G7 (zones taken from
  a non-Regularly cache).
- On the server, S6 survives: the floor reading reports from before the enrollment, which is the re-enrol case the brief
  names.
- S8 survives (judging after each fix). The test's out-of-order pair is all extras, and `report_fixes` sorts those
  before storing, so the one judging is never exercised. The case it exists for is a report whose own `location` is
  older than an extra, for example a confirm whose GPS failed and fell back to an older last-known fix.
- S12 survives: "Here now" in the parent's interval instead of the served one.

Expected fix: keep it lean.
- A few `contains` lines in `ZonePlanTest`'s glue test for G1-G4 and G7 (G1 after Q-6's change).
- One server test for S6: a watch reported before a re-enrollment gives no floor.
- One for S8: extras `[late]` plus `location` early -> the arrival at the early fix.
- One for S12: a fix 45 min old with the floor is "Here now".

### Q-12 (Low, docs)

- `launcher/CLAUDE.md:878-882` ("never on its own"), `PassiveFixes`' and `keepFix`'s KDocs, design 64 "As built: step 2"
  ("it only replaces the cached fix, which the next due heartbeat reports") and §4.3 (which says both "so CACHED reports
  it" and "never on its own") are wrong or contradictory until Q-1 is fixed. Correct them with it.
- `emulator.md` §6t step 9 ("the next report carries both fixes as `extra_locations`"): one of them is the report's
  `location` (the cached confirm fix), the other the extra.
- The "As built" latency statement (a fence-only crossing waits up to its 30-min poll, another app's fused fix or the
  heartbeat, about 30 min plus 2) matches the code. It should also say that, on a phone with the sensor, a still phone's
  heartbeat re-sends the old fix for up to 4 h (Q-3).
- The emulator section's mock fact (`geo fix` goes through the GNSS HAL and is not mock, a `cmd location providers` test
  provider is) matches what I know of AOSP. It is marked "to confirm in step 1", which is right.

## Verdict

**Not ready to merge.** The contract, the server's intake and floor, the fences' identities and the queue's bookkeeping
are sound, and every briefed pure rule is pinned. But the behaviour around the heartbeat breaks three promises: the
trail as dense as the parent chose (Q-1, Q-4), "Here now" (Q-3), and "Off sends nothing more" (Q-5). The regression
check for phones without zones fails by design (Q-2), and that needs the user's decision; my recommendation is to gate
on zones. Fix Q-1, Q-3, Q-4, Q-5 and Q-6, get Q-2 decided, and take Q-7 to Q-12 in the same round. Then run §6t on the
emulator, and on the Jelly Star the device checks: the passive listener and the location indicator, the sensor armed
for a background UID, and a still phone keeping "Here now".

## Follow-up: the fix round and the moving series (efd00f23, 9c37285d, 55a6bf1b)

`qa-design-64-step2` has been rebased onto the branch tip 55a6bf1b. I read `git diff 41510352..55a6bf1b` in full.
Migration 0093 was amended before release to carry `resent_at`, `mock_logged` and `mock_logged_at`, as the
coordinator noted; a dev database that ran the old 0093 needs a fresh `data/`.

### Suites (verbatim)

- Launcher: `BUILD SUCCESSFUL in 1m 13s`, `tests=1703 failures=0 errors=0 skipped=0`. The only warnings are kapt's two
  `RELEASE_11` lines.
- Server:
  - `cargo test`: `test result: ok. 1262 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 371.97s`
  - `cargo fmt --check`: exit 0
  - `cargo clippy --all-targets`: `generated 13 warnings`, the same 13 known ones. `models.rs:622` is the old `:605`
    warning, moved down by the new fields.

### The findings, verified

- **Q-1 holds.** A passive fix and a series `KEEP` go only to the kept slot (`zone_kept_fix`). The source pins this:
  `keepFix` never writes `cachedLocationJson`, and the file writes the cached fix in exactly two places.
  Re-traced:
  - A backstop sync that isn't due, a nudge and an on-request sync are `CACHED` and send the reported fix.
  - A `locate`/`ring` command is a precise `FRESH` and replaces the reported fix; the older kept fix is then never
    "unreported" again.
  - Only a due `KEPT`, a crossing's sync and a series candidate call `promoteKept`.

  Nothing passive rides on an ordinary sync any more.
- **Q-2 holds.** `zoneWatchActive` (owner, managed, Regularly with zones) is the one gate for the passive feed, the
  motion gate, the series, the heartbeat, the balanced path, the beat stamp, the kept slot, the queue and the
  backstop anchor. The "without zones" test runs `syncLocationAction` over every policy, both forced/precise values,
  nine ages, every `Motion`, three kept ages and three beats, and checks it against `locationAction`. Together with
  `backstopAnchorMs(false, ..)` = the last own fix and `reportStill(false, ..)` = false, the pure side is today's.
  The glue passes the rest of today's behaviour:
  - motion `NO_SENSOR` and kept `null` without zones;
  - no beat stamp, no promotion, no arm;
  - the precise GPS-first path, because `FRESH_BALANCED` is never chosen;
  - the queue cleared with no extras.

  The mock rule stays on every phone (`ZoneWatch.accept` in `currentLocation`). Without zones only our own fixes
  reach it, because the passive feed is off.
- **Q-4 holds.** `keptFixAgeMs` is the age of the kept slot's *unreported* fix, and there is a row for our own fix 5 s
  after the beat.
- **Q-5 holds.** Off, or no zones, clears the queue before the report and sends no extras (pinned).
- **Q-6 holds.** The gate arms only after `freshOwnFix` (taken at the request, less 60 s). A fire clears `moved` and
  `listener` in one monitor, the same one `arm` holds.
- **Q-7 holds.** The record and its stamp are saved before the first add, and no record counts as the echo.
  `registeredHere` is set only by a watching pass, so a release before the first sync can't skip the
  re-registration.
- **Q-8 holds.** `ZoneWatch.forget` runs at both enrollment paths, and `release` when un-managed (`AppEnforcer.apply`).
- **Q-10 holds.** The zones go only to a launcher whose latest report since enrollment claims `zones_v1`.
- **Q-11:** S6 and S8 are now caught (`a_watch_from_before_a_re_enrollment_gives_no_floor`,
  `a_location_older_than_its_extra_is_judged_in_order`). Two gaps remain; see F-6.
- **Q-3, Q-9:** fixed, with the remainders in F-3 and F-5.
- **Q-12:** the docs match the code. §6t step 9 now names the `location` and the extra correctly.

### Mutations (follow-up)

| # | Mutation | Caught by |
|---|---|---|
| F1 | `nextCheckDelay` without the 3-min floor | `the next check comes by the distance...` |
| F2 | accuracy not taken off the distance | same |
| F3 | the fix's age not taken off | same |
| F4 | no zones gives a delay instead of `null` | same |
| F5 | `checkVerdict` never says SIDE_CHANGED | `a check's fix is sent only when it could be a crossing` |
| F6 | NEAR_BOUNDARY at half the accuracy | same |
| F7 | the hour end exclusive | `a series ends after an hour...` |
| F8 | no 20-check end | same |
| F9 | the still end at 3x the delay | same |
| F10 | a fire doesn't refresh `lastMotionAtMs` | same |
| F11 | a crossing's sync doesn't promote the kept fix | the glue test |
| F12 | `promoteKept` promotes a kept fix that isn't newer | **not caught** |
| F13 | `still` also on a fresh fix | `a re-sent fix is still current only while the sensor says so` |
| F14 | the 4-h bound accepts a future own fix | the still test, the heartbeat test |
| F15 | a series NEAR_BOUNDARY isn't sent | the glue test |
| F16 | the series' re-arm in `finally` removed | **not caught** |
| F17 | `still` without zones | `without zones the location step is exactly today's...` |
| S6 | the floor reads a watch from before the enrollment | `a_watch_from_before_a_re_enrollment_gives_no_floor` |
| S8 | the zones judged after each fix | `a_location_older_than_its_extra_is_judged_in_order` |
| S12 | `freshness_inputs` in the parent's interval | **not caught** |
| T1 | the mock UPDATE without its `< ?1` guard | **not caught** |
| T2 | a still report doesn't stamp `resent_at` | `here_now_counts_the_served_interval_and_a_still_re_send` |
| T3 | `still` read as true for any value | same |

### New findings

#### F-1 (Medium) - the series sends exactly the fixes the server's rule ignores

`ZonePlan.kt` `checkVerdict`, `ZoneSeries.kt:114-118`.

What's wrong: NEAR_BOUNDARY is `|d - r| <= a`. The server's straddle rule (decision 2) calls a fix *unsure*
precisely when `|d - r| < a`, and an unsure fix "counts for neither side". So every NEAR_BOUNDARY fix, apart from
exact equality, is one the server can't use. Sending it buys a sync, a promoted trail point and nothing for the
judgement.

SIDE_CHANGED compares plain distances, so it also fires for an unsure fix, and for a fix with no accuracy, which the
server always calls unsure. Each such fix costs a sync and a GPS confirm 2 min later.

The case: a kid playing near the edge of Home (r = 150 m, Wi-Fi fixes at ±30-50 m). Every 3-min check is NEAR_BOUNDARY
or flips sides. That means a sync and a trail point every 3 min, plus a GPS fix on each flip, for as long as the kid
moves. The trail is then far denser than the parent's interval (Q-1's promise), and the battery pays for it.

Expected fix: send only a fix the server can use. SIDE_CHANGED only when the new fix is *sure* (`d + a <= r` or
`d - a >= r`, with an accuracy) and on the other side from the reported fix. A near-boundary fix stays in the kept
slot; at most it shortens the next check. Add `checkVerdict` rows for an unsure flip and a fix with no accuracy.

#### F-2 (Medium, battery; measure on the Jelly Star) - the series' caps don't cap a moving day

`ZoneSeries.onMotion`, `MotionGate.fired`, `ZonePlan.kt` `nextCheckDelay`.

What's wrong:
- The trigger is kept armed while zones are watched. After an end for HOUR or COUNT, the next fire starts a new series
  at once, and while moving the STILL end never comes. So the 60 min and 20 checks bound a series, not a day.
- The 3-min floor applies to everything within about 2.7 km of any boundary (180 s at 15 m/s, less the accuracy).
  That is a kid's whole neighbourhood.
- So a moving kid gets a balanced fix every 3 min, about 20 an hour, each holding a 45-s wake lock for up to 15 s.

Design §5 modelled about 16 moving checks a day. Several hours of walking, playground or bus gives 60-100.

Expected fix: a cooldown after an HOUR/COUNT end (for example, no new series until the next heartbeat) or a daily
budget of checks. Then put the real counts in §5 after the Jelly Star walk (batterystats: `kidslauncher:zone_check`
wake locks, fused requests).

#### F-3 (Medium, honesty of `still`) - a fence crossing doesn't count as motion, and the 4-h bound counts attempts

`ZoneWatch.onFence` (no `MotionGate` call), `LocateCommands.kt:142` (`saveLastActiveFetchAt` before the attempt),
`stillSinceOwnFix`.

What's wrong: with a working sensor, `still` is honest. A failed fix never arms the gate, so a move keeps `moved`
true and `still` false.

It fails when the sensor misses a move: a smooth car ride, or a trigger not delivered to a background UID (§9's open
question 5). The trace:
1. The fence fires an exit, which is evidence the phone moved.
2. The balanced and GPS fixes fail, and the sync falls back to the old cached fix.
3. `moved` stays false, so later `CACHED` syncs send `still: true`.
4. "Here now" at Home stays while the fence has said the kid left.

The failed attempt also stamped `lastActiveLocationFetchAt`, so the 4-h bound, which exists for exactly a sensor that
doesn't fire, starts again.

Expected fix:
- A fence `CROSSING` (and a series SIDE_CHANGED) sets `moved = true` (`MotionGate.markMoved`).
- `stillSinceOwnFix` counts from the last *genuinely fresh* own fix: a stamp written only when `freshOwnFix` holds,
  not at the attempt.
- Add a test row for each.

#### F-4 (Low) - the series' state across threads, and `fired()` on the main thread

`ZoneSeries.kt:70-100`, `ZoneSensors.kt` `fired()`.

What's wrong: the state is consistent. Reads and writes go through the object's monitor, a `stop()` during a check
leaves `series` null so `finally` doesn't re-arm, and a fire during a check only refreshes `lastMotionAtMs`. Two small
points:
- A `stop()` followed by a new `onMotion()` while a check runs makes that check's `finally` count a check for the new
  series and re-arm it with its own delay. This is harmless.
- `fired()` runs on the sensor callback's main thread. It reads the cached policy, decodes two pref JSONs and makes an
  alarm binder call there. That is cheap, but it is the one zone path on the main thread.

The re-arm itself (F16) and `promoteKept`'s "newer" condition (F12) are unpinned.

Expected fix: one `contains` pin for `if (still) arm(app, next)` inside `finally`, and one for the `keptUnreported()`
condition. Optionally post `fired()`'s work to the series' IO scope.

#### F-5 (Low) - the mock count's line and date at the edges

`zones.rs` `mock_rise`.

What's wrong:
- Two parallel reports with *different* counts (4 and 5 over a logged 3) both pass the guard in turn. The second
  line's "rose" comes from the stale read: it says 2 when the rise was 1, and the total is right. Equal counts log
  once, as intended.
- The parallel test can't tell the guard is there (T1 survives), because the in-process reports serialize before the
  read.
- After a re-enrollment, a lower count is taken but `mock_logged_at` is kept, so the Locate row can show the old
  install's date beside the new install's count.

Expected fix:
- Compute the line from the row the UPDATE changed: read `mock_logged` inside the same `BEGIN IMMEDIATE`, or take the
  rise from the UPDATE's own old value.
- Clear `mock_logged_at` on the lower-count path, or at enrollment.
- For a test of the guard, call `mock_rise` twice with the same stale `logged`, or drive it from two connections.

#### F-6 (Low, tests) - "Here now" in the served interval is still unpinned

`tests::zone_watch::here_now_counts_the_served_interval_and_a_still_re_send`.

What's wrong: the test reads `freshness_inputs` only after the still re-send, which makes the fix current whatever
the interval. So S12 (the parent's interval instead of the served one) survives.

Expected fix: assert `fresh(freshness_inputs(..))` once before the re-send, with the 45-min-old fix and the floor on.

### Follow-up verdict

**Q-1 to Q-12 are fixed as described.** The regression check for phones without zones now holds and is pinned. The
fix round is clean. The new series needs one more round: **F-1 and F-3 before merge** (both small pure-rule changes
with test rows). F-2 needs a decision on a cooldown or budget, then the Jelly Star measurement. F-4 to F-6 are cheap
pins and touches, to take in the same round. After that the branch is ready for §6t on the emulator and the device
checks: the series on a walk, a still phone keeping "Here now" for hours, and whether the trigger reaches a
foreground-service UID.
