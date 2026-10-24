# 64 - Safe zones: named circles on the Locate map, "arrived" and "left" for the parent (proposal)

Asked 2026-10-23 (PLAN.md "Fix backlog", "Safe zones (the user, 2026-10-23, from Xplora)"). Two steps: step 1 is
server only (`handlers::locate`, a new `zones` module, `device_api::status`'s location intake, `parent_events`, the
Locate pages, migration); step 2 is both directories (the policy, the status report, the launcher's zone watch). Builds
on design 06 (`location_policy`), design 31 §3.6 / design 44 (the Locate tab, its map and history), design 35 + 62 §5
(`parent_events`, Web Push, Notify Me About per parent), design 04 (`lock_location`, "Keep location on") and design
19b (the power-save allowlist).

## 0. Goal

The user, 2026-10-23: zones per phone ("Home", "School") drawn on the Locate map, an inbox event and a push when the
phone arrives or leaves. "We should put on the list to implement this stuff!" - both steps. The fences do the watching
("the system checks by distance and speed, cell/Wi-Fi before GPS"), so the Regularly interval can be stretched once
they are in; fences re-registered at the first sync after a boot; "the 100-fence cap is plenty". And piggyback our own
locating on them: crossings take the fixes that matter, the Regularly timer becomes a long heartbeat (30-60 min) for
the trail and "last seen", the passive provider's fixes feed the trail free, the heartbeat's fix is skipped while the
significant-motion sensor says the phone hasn't moved. Privacy: "det er bedre for privacy at det funker bra og at det
ikke er loopholes".

Today: `device_locations` gets one fix per status report (`device_api::status`, `models::checked_location`); the
launcher takes a fresh fix per `locationAction` (off / on request / every N >= 10 min; the server's default mode is
`on_request`, interval 30) and otherwise re-sends its cached fix in every report - so the table holds the same fix
many times over (no dedupe on insert). Nothing on the phone or the server knows any place by name. Nothing on the
kid's side says the phone shares its location.

**Two corrections to the backlog entry**, from AOSP's source (§2): the platform proximity alert has **no 100 cap**
(that is Play services' `GeofencingClient`, which we can't use) and it **polls at most every 30 minutes**, not "by
distance and speed" in any way that makes it faster - a crossing seen only by the fence can take up to 30 minutes. So
the fences stay (the user's decision: they are cheap, invisible and the system's own), but the minutes come from the
significant-motion sensor plus our own cheap checks near a boundary (§4.5). `addProximityAlert` is also **not
deprecated** (`LocationManager` in `android16-release`).

## 1. Decisions

1. **The server decides every arrival and departure; the phone only takes and sends fixes.** No crossing flag on the
   wire: the phone's fence or its own check is a reason to take a fix, the server's rule (§3.2) on the stored fixes is
   the only judge. One rule in one place (the server owns rules), the two-fix rule can't be bypassed by a phone that
   says "I crossed", and nothing new leaves the phone but fixes.
2. **A fix is inside, outside or unsure per zone**: with `d` the distance to the centre and `a` the accuracy, inside
   when `d + a <= r`, outside when `d - a >= r`, else unsure; a fix without accuracy is unsure. Unsure counts for
   neither side and breaks nothing. The user's straddle rule, exact; AOSP's own rule (`d <= max(r, a)`) counts a ±1 km
   cell fix as "inside" any small zone, which is the loophole the user asked us not to have.
3. **A side counts after two consecutive sure fixes on it, the second at least 60 s after the first**, and only
   fixes newer than the last one evaluated count (a re-sent or late fix is stored for the trail, never evaluated
   twice). One bad fix can't flap a zone; a burst of 1 Hz fixes from one moment isn't two observations.
4. **The first side of a new or moved zone is set silently** (state unknown -> the first confirmed side raises
   nothing): drawing "Home" while the kid is home must not push "Sverre arrived at Home".
5. **Zones are the phone's, shared by the family; the push is each parent's.** The per-zone "Notify on arrive / leave"
   switches (default both on) decide whether an event is raised at all; Notify Me About's new switch "Zones" (per
   parent, default on) decides only the push - as every kind (design 35: every kind is stored, the switches govern
   the push).
6. **Zone events go away with the location history** (the phone's Keep history for, 1-30 days), not after the inbox's
   90 days: "Sverre arrived at Home 08:12" is a location record and must not outlive the trail it was read from.
7. **Zones need fixes and never change the parent's mode silently.** Off and Only when asked keep zones stored but
   unwatched (step 2: not in the policy), the page says so and offers one tap to Regularly (§3.6).
8. **At most 10 zones per phone, radius 100 m - 2 km, default 150 m** (§11 decides). 10 keeps the phone's work and the
   page small; the straddle rule needs a radius about twice a good Wi-Fi fix's accuracy (±20-50 m) to ever say
   "inside", hence the 100 m floor.
9. **The zone names never go to the phone.** The policy carries id, centre and radius; "Home" stays on the server -
   the phone needs no names to take fixes.
10. **Step 2's fences are `LocationManager.addProximityAlert`, one per zone, re-registered at every process start and
    on every zone change** (idempotent: remove all, add the policy's). Every process start covers the first sync
    after a boot (the user's rule) and also an update or a crash, which a boot-only rule would miss; a registration
    is cheap (§2).
11. **Significant motion gates the phone's own fixes**: a heartbeat fix is skipped while the sensor hasn't fired since
    the last fix (at most 4 h of skipping, then one fix anyway); a fire starts a short series of cheap checks near
    the zones (§4.5). The user's rule plus a cap, so a sensor that never fires can't freeze the trail.
12. **Locating isn't a restriction: zones keep watching during a pause** (§11 decides), as the location policy does
    today (`locationAction` ignores the pause).
13. **The heartbeat replaces Regularly's timer only while zones exist on a phone that reports `zones_v1`**: the
    effective interval is `max(chosen, 30 min)`, computed when the policy is served, never written; without zones the
    phone does what the parent chose, as today. No silent write, and deleting the last zone gives back the old
    interval.
14. **Mock locations count for nothing in a release build** (`Location.isMock()`): neither the trail nor a zone, and
    the report counts them (`zone_watch.mock_ignored`). A spoofing app placing the phone at school is exactly a
    loophole; the debug build accepts them for the emulator (§9).

## 2. What Android offers without Play (AOSP `android14-release`, read for this note; `GeofenceManager` and `addProximityAlert` unchanged in `android16-release`)

| Fact | Where |
|---|---|
| `addProximityAlert(lat, lon, radius, expiration, PendingIntent)` is not deprecated; needs `ACCESS_FINE_LOCATION`; the PendingIntent must be **mutable** and **targeted to a package** (`BLOCK_IMMUTABLE_PENDING_INTENTS`, `BLOCK_UNTARGETED_PENDING_INTENTS`) - an explicit intent to our own receiver with `FLAG_MUTABLE` | `LocationManager.java:2272-2297`, `GeofenceManager.addGeofence:348` |
| No cap on the number of fences | `GeofenceManager` (none in the file) |
| One fused request for all fences: interval `min(2 h, max(T, d_min / 100 m/s))`, `T` = `Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS`, **default 30 min**; `d_min` = the nearest boundary from the last fix. So never faster than 30 min; slower only beyond 180 km | `GeofenceManager.mergeRegistrations:465-501`, `SystemSettingsHelper:71-72,273-282` |
| A device owner can't change `T` (not in `DevicePolicyManagerService.GLOBAL_SETTINGS_ALLOWLIST`) | `DPMS:737-748` |
| The request is `FUSED_PROVIDER`, default quality (balanced: Wi-Fi/cell on a GMS phone), `setMinUpdateIntervalMillis(0)` - so **every fused fix any app causes** is evaluated too - and `setHiddenFromAppOps(true)`: no location indicator, our UID in the work source (batterystats charges us) | `:497-501`, `registerWithService:449` |
| Inside when `distance <= max(radius, accuracy)`; "entering" on any change to inside (also from unknown, i.e. right after registration when the last fix <= 5 min old is inside), "exiting" only after inside; extra `KEY_PROXIMITY_ENTERING` | `GeofenceRegistration.onLocationChanged:228-251`, `onActive:180-185`, `getLastLocation:521-534` |
| Fences live in system_server's memory: gone at reboot; dropped when the PendingIntent is cancelled; inactive while Location is off, active again when it's on | `isActive:376-399`, `sendIntent:256-268` |
| The broadcast is sent with a wake lock and `DontSendToRestrictedApps` (we are allowlisted, design 19b) | `sendIntent` |
| Non-passive providers are throttled while the device is in Doze **and** stationary (the last location re-delivered with a new time) - only for a request that had a fix in the last 30 s, so it spares continuous requests, not our one-shot fixes | `StationaryThrottlingLocationProvider`, `LocationManagerService:362-371` |
| A background app's continuous requests are throttled to 30 min unless foreground; the anchor FGS counts as foreground. One-shot `getCurrentLocation` isn't throttled | `LocationProviderManager.calculateProviderLocationRequest:680-687` |
| The passive provider gets every provider's filtered fix (GPS, network, fused) | `LocationProviderManager:2564-2566` |
| `adb shell cmd location providers add-test-provider fused` / `set-test-provider-location fused --location LAT,LON --accuracy M` (shell needs `appops set com.android.shell android:mock_location allow`): drives the fence and the passive listener with an exact accuracy - mock fixes | `LocationShellCommand:396-412` |

So the AOSP fence is "a hidden balanced fused poll every 30 min, plus every fused fix someone else causes, judged by a
loose rule". Worth keeping as one trigger (free riding on Maps and the system, survives our process, no indicator), not
as the judge or the latency source. [inferred, check on the Jelly Star] Its GMS fused provider answers a balanced
request from Wi-Fi/cell without GPS; GrapheneOS's network location is optional, without it fused falls back to GPS.

## 3. Step 1 - the server

### 3.1 Data (migration: the next free number, 0091 today)

```sql
CREATE TABLE device_zones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name TEXT NOT NULL,                 -- 1..40 chars, unique per phone (case-insensitive), checked in code
    latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m INTEGER NOT NULL CHECK (radius_m > 0),   -- the range is code's (zones::RADIUS), not frozen here
    notify_arrive INTEGER NOT NULL DEFAULT 1,
    notify_leave INTEGER NOT NULL DEFAULT 1,
    created_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    -- the rule's state (§3.2); NULL side = unknown
    side TEXT CHECK (side IN ('inside', 'outside')),
    side_since TEXT,                    -- captured_at of the first fix of the pair that set it
    pending_side TEXT CHECK (pending_side IN ('inside', 'outside')),
    pending_at TEXT,                    -- captured_at of that pending fix
    evaluated_to TEXT                   -- captured_at of the newest fix evaluated
);
CREATE INDEX idx_device_zones_device ON device_zones(device_id);
ALTER TABLE notification_prefs ADD COLUMN push_zones INTEGER NOT NULL DEFAULT 1;
```

The range checks live in code so §11's choice isn't frozen into a migration (a migration's text is frozen at its first
commit, server/CLAUDE.md). A moved centre or a changed radius clears the five state columns (decision 4); a rename or a
switch keeps them. An enrollment into the row clears them too (another phone). `migrations_73_to_90_in_order` gains
the file.

**The fix intake stops storing the same fix twice**: `INSERT ... SELECT ... WHERE NOT EXISTS (SELECT 1 FROM
device_locations WHERE device_id = ? AND captured_at = ? AND latitude = ? AND longitude = ?)` (the existing
`(device_id, captured_at)` index). Today every cached re-send is a row; the trail, design 44's counts and the two-fix
rule all want one row per fix. No migration deletes old duplicates (retention ages them out).

### 3.2 The rule (`src/zones.rs`, pure)

- `side(zone, fix) -> Side { Inside, Outside, Unsure }`: haversine distance (the mean Earth radius 6 371 008.8 m);
  decision 2.
- `step(state, zone, fix) -> (State, Option<Transition>)` for a fix with `captured_at > evaluated_to` (else
  unchanged, no transition):
  - `evaluated_to = captured_at`; unsure -> nothing else.
  - sure and equal to `side` -> `pending` cleared.
  - sure, not `side`, and equal to `pending_side` with `captured_at - pending_at >= 60 s` -> `side` = it, `side_since
    = pending_at`, `pending` cleared, a transition `Arrived`/`Left` at `pending_at` unless the old `side` was unknown.
  - sure, not `side`, equal to `pending_side` but under 60 s -> nothing (neither confirms nor breaks).
  - sure, not `side`, other than `pending_side` -> `pending = (it, captured_at)`.
- `evaluate(zones, fixes)` sorts the fixes by `(captured_at, id)` and steps each zone through each.
- The event's time is the first fix of the pair (the earliest evidence that it happened), so "arrived 15:42" stays
  true when the second fix comes 20 minutes later.

Shared vectors: `server/testdata/zone_vectors.json` - rows `{zone, fix, side}` (inside, outside, both straddles, no
accuracy, the antimeridian, a pole-side zone, exactly on the boundary: `d + a == r` is inside, `d - a == r` outside).
The server's `zones::tests::the_shared_vectors` reads every row; step 2 adds a byte-identical copy to the launcher's
test resources (§4.1).

### 3.3 The intake

`device_api::status` keeps its order; after the report's row, the location part becomes one transaction: insert the
checked fixes (step 1: the one `location`; step 2: also `extra_locations`, §4.1) with the dedupe, then
`zones::evaluate` on the fixes **actually inserted**, write the zones' state, raise the events (`parent_events::raise`
on the same connection), commit, then `state.push.wake()`. A failed zone read or write rolls the fixes back too and is
logged (the report's other parts are already stored, as today a failed location insert is dropped). Each step is
`O(zones x fixes)`, at most 10 x 21.

### 3.4 Events and push (`parent_events`)

- Two kinds, `Kind::ZoneArrived` (`zone_arrived`) and `Kind::ZoneLeft` (`zone_left`), after `PhoneOn` in `Kind::ALL`;
  both `switch()` = `("push_zones", "Zones: arrived and left")`; not urgent, foldable, TTL 3600, urgency normal.
- Raised only when the zone's switch for that direction is on (decision 5). Key `<zone id>:<side>:<pending_at>`.
- Words (`Phone::zone`, design 35's limits, the zone's name as stored when raised): title "Sverre arrived at Home" /
  "Sverre left School"; summary the time by `Phone::at` ("15:42", "Yesterday 21:14"). Target
  `/devices/locate?device={id}#location`. §11 asks about Norwegian.
- **Pushed only while it is news**: `Push::Now` when the confirming fix is under 30 min old at intake, else `Push::No`
  (inbox and Activity only). A phone back from a day without data must not push "arrived at School 08:10" at 15:00.
- They show in the inbox, the summary's Activity (design 48, unchanged code) and the bell, like every kind.
- `retention::prune`: `zone_*` events go by the phone's `location_retention_days` (by `created_at`), decision 6;
  `Pruned` counts them with `parent_events`.

### 3.5 Pages

**Locate (`device_locate.html`)**, a new group `#zones` "Zones" right after `#location` (it's about the map):

- A row per zone: "Home" over "150 m · arrive and leave" (or "arrive", "leave", "no notifications"), the value "Here
  now" while `side = inside`, a chevron, to the zone's page. Then "Add Zone" (disabled with "10 zones is the most"
  at the cap). Footer: §3.6's line.
- The newest fix's sub-line gains the badge: "Within 40 m · At Home" while that zone's `side` is inside (two zones:
  both names, "At School · Gym"); the marker's popup says the same (`textContent`).
- The map draws each zone as an `L.circle` (token `--map-zone`, a calm outline, both schemes) with its name as a
  tooltip. The circles come from `GET /devices/{id}/zones.json` (`no_store_path`, like `locations.json`: the
  coordinates of a child's home never sit in a cached page); the no-JS page lists names and radii only.
- The phone's summary (`device_detail.html`, Now): one row "At Home since 15:42" while a zone says inside.

**The zone's page** (`GET /devices/{id}/zones/new`, `GET /devices/{id}/zones/{zone_id}`, `device_zone.html`, tab Locate,
back to `/devices/locate?device={id}#zones`; both in `no_store_path`): a map on top, then one form `#zone-form`:
Name, Latitude, Longitude, Radius (a `<input type="range">` with its number field, `zones::RADIUS` as min/max, step
10), Notify When They Arrive, Notify When They Leave (switches), Save. A new zone starts at the phone's newest fix (no
fix: the form is empty and says "No location yet - type the coordinates or tap the map"), radius the default. Below
the form, for an existing zone, "Delete Zone…" as a `<details class="confirm">`: "Delete Home? Its past arrivals stay
in Notifications until the location history is cleared." + Delete.

- **Without JS** the form is the whole feature (numbers typed or kept from the newest fix).
- **With JS** (`static/zones.js`, ES5, `?v=1`, every value from `data-` attributes, `textContent` only): the circle on
  the map; a tap places the centre (Leaflet `click`, which a drag never fires; `doubleClickZoom: false` so a second
  tap doesn't zoom), the centre marker drags, the slider and the number field redraw the circle live, "Use Phone's
  Location" moves it to the newest fix. Every change writes the latitude/longitude fields (6 decimals), so Save posts
  what the map shows. Long-press isn't used: a tap is enough and iOS's long-press opens its own menu.
- Routes (full auth, CSRF as every form): `POST /devices/{id}/zones` (create), `POST /devices/{id}/zones/{zone_id}`
  (save), `POST /devices/{id}/zones/{zone_id}/delete`. Success: 303 to `/devices/locate?device={id}&zone=saved|added|
  deleted#zones` (the flash in `#zones`: the list is where the parent sees the result; never the top). A refusal
  re-renders the zone's page with 400, the field marked (`aria-invalid`, the message under it) and the page at
  `#zone-form`: name empty, over 40 or taken on this phone; a coordinate out of range or not a number; a radius out
  of range; an 11th zone; nothing written. An unknown phone or a zone of another phone is a 404; a DB error a 500
  "Nothing was saved".
- Every save nudges the phone (`command_notify`), as a policy save does (step 2's phone re-registers).

**Notify Me About** gains the "Zones: arrived and left" switch from `Kind::switch()` (no template change, design 38's
way).

### 3.6 The interplay with the location settings

| The phone's state | What the zones do | What `#zones` says |
|---|---|---|
| Off | nothing | the existing orange "Location is off" row gains "Zones can't see where the phone is." |
| Only when asked | judged only on fixes from Update Location | orange row "Zones need Regularly" / "With Only when asked the phone reports a location only when you tap Update Location." + a **Locate Regularly** button (`POST /devices/{id}/location-policy`, `mode=interval`, `back=zones` -> `#zones`) |
| Regularly, launcher without `zones_v1` | judged at each fix | footer "Arrivals and departures show after two locations on the same side - within about two intervals (here 20 min)." |
| Regularly, `zones_v1` (step 2) | the phone watches | footer "The phone watches the zones itself and reports a crossing within minutes. The trail gets a point every 30 min." |
| `lock_location` off ("Keep location on") | as above, while the kid leaves Location on | a note row "Your child can turn Location off on the phone; zones then see nothing." linking to the switch |

The Locate Regularly button is the existing route with a whitelisted `back` (`zones` -> `#zones`, anything else
`#location-settings` as today), so its redirect never lands at the top.

### 3.7 Security log

`zone_added`, `zone_changed` (what changed: "radius 150 -> 250 m", "moved", "renamed", "arrive off"), `zone_deleted` -
the phone and the zone's name, **never coordinates** (the security log is read on more screens than Locate). Words in
`handlers::admin`'s event names ("Zone added" ...).

## 4. Step 2 - the phone watches

### 4.1 The contract (both directories, one commit)

- **Policy**: `location_policy.zones`, always present: `[{id, latitude, longitude, radius_m}]` while the mode is
  `interval`, `[]` otherwise (decision 7; inside `location_policy` because it is sent by the location mode's rule and
  read by the same code). `location_policy.interval_minutes` is the effective one (decision 13). Pinned by
  `policy_json_keys_snapshot` (`location_policy`'s keys `interval_minutes, mode, zones` and a zone's keys) and the
  launcher's `PolicyResponseCompatTest` (a policy with zones, one without the key -> `[]`, an older server's policy).
  An older launcher ignores the key (`ignoreUnknownKeys`).
- **Status report**: `extra_locations` (optional, at most 20, oldest first: fixes the phone took or kept since its last
  report and hasn't sent - a crossing's fixes made in a tunnel arrive later in order; each is a `LocationReport`,
  each through `checked_location`, more than 20 -> the newest 20) and `zone_watch` (optional object: `fences` int,
  `motion_sensor` bool, `location_on` bool, `background_location` bool, `mock_ignored` int since the last report;
  stored capped at 512 bytes in `device_status.zone_watch_json`, the next free migration after step 1's). Capability
  `zones_v1`.
- Contract files, byte-identical, the repo's way: `server/testdata/zone_status_report.json` (a report with `location`,
  `extra_locations`, `zone_watch`, `zones_v1`) in `launcher/app/src/test/resources/` - the launcher's
  `ZoneReportTest` encodes exactly it, the server's `zones::a_launcher_report_raises_the_arrival` posts it (its
  instants moved to the last hour) and both compare the copies; `server/testdata/zone_vectors.json` (§3.2) in the
  launcher's resources - `ZonePlanTest` reads every row. Root CLAUDE.md's contract paragraph and the CI path filters
  (`launcher.yml`, `server-ci.yml`) gain both files.
- Server: `mock_ignored > 0` writes a security event `mock_location_ignored` ("the phone ignored 3 made-up
  locations"); `zone_watch` shows as a line in `#zones`' footer ("Watching 2 zones on the phone" / "The phone has no
  motion sensor - it checks every 30 min" / "Location is off on the phone").

### 4.2 The fences (`zones/ZoneFences.kt`; pure `zones/ZonePlan.kt`)

- `fencePlan(policyZones, stored: FenceRecord?, processStartId) -> FencePlan(removeIds, add)`: all of them on a new
  process start or a changed fingerprint (`id, latitude, longitude, radius_m` of each), nothing otherwise. Applied after
  every accepted policy (`AppEnforcer.apply`'s end) and at the anchor's first sync. `FenceRecord` (CE prefs
  `zone_fences`: fingerprint, ids, registered-at elapsed time) is written with `commit()` after the calls.
- One `PendingIntent.getBroadcast` per zone: explicit component `ZoneAlertReceiver` (`exported="false"`), action
  `me.vibb.launcher.action.ZONE`, data `vibb-zone:<id>` (one PendingIntent identity per zone), `FLAG_MUTABLE |
  FLAG_UPDATE_CURRENT` (AOSP requires mutable: it fills in `KEY_PROXIMITY_ENTERING`). Removal: `removeProximityAlert`
  for every stored id, then `cancel()`. `expiration = -1`.
- Before registering: the three location permissions self-granted (`QuickControls.selfGrantPermission`, as
  `LocateCommands` does) and Location on if the hardening wants it; a `SecurityException` is logged and reported
  (`zone_watch.background_location`), never retried in a loop.
- **The receiver** takes nothing as truth: an id not in the current policy is ignored; an "entering" within 60 s of
  that fence's registration is the registration's own echo (§2: unknown -> inside fires at once) and is dropped;
  anything else asks the watch for a check (§4.5). A mutable PendingIntent held only by system_server with a fixed
  component can't be redirected; a forged broadcast can't reach a non-exported receiver, and at worst would cause one
  fix.
- Zones `[]`, mode not `interval`, unenrolled, or not the device owner: every stored fence removed.

### 4.3 The passive feed (`zones/PassiveFixes.kt`)

- While Regularly is on (zones or not): `requestLocationUpdates(PASSIVE_PROVIDER, LocationRequest.Builder(
  PASSIVE_INTERVAL).build(), executor, listener)` in the anchor service (`CommandListenerService.onCreate`, removed in
  `onDestroy`). Free: it wakes nothing, it only sees fixes someone else made (the fence's poll, Maps, the system).
- A passive fix is kept when it is not mock (release), accuracy <= 200 m, and newer than the newest fix the phone
  knows; as built it goes to a kept slot of its own, never the cached (reported) fix, and only a due heartbeat (`KEPT`,
  §4.4) or a crossing promotes it - see "As built: step 2", the code QA's fix round (Q-1).
- **The trail stays as dense as the parent chose**: a passive fix reaches the server as the heartbeat's fix (§4.4) or
  as a crossing's fix, never on its own. The parent's interval is the promise of how often the trail gets a point;
  more points would be a commute trace nobody asked for.

### 4.4 The heartbeat (`locationAction` grows; pure, `LocationPolicyTest`)

`heartbeatAction(policy, zonesActive, sinceLastReportedMs, newestKeptFixAgeMs, movedSinceFix: Boolean?,
sinceOwnFixMs)` at each due point (the backstop alarm, already 30 min; `backstopDelayMs` keeps "never later than the
interval"):

| Case | Action |
|---|---|
| mode off / on request | as today (`NONE`; `FRESH` only for `locate`/`ring`) |
| due, a kept fix (own or passive) younger than the interval, not yet reported | `CACHED` - report it, no LocationManager call |
| due, `movedSinceFix == false` and `sinceOwnFixMs < 4 h` | `CACHED` (the old fix is where the phone still is; nothing new reported) |
| due otherwise (moved, no sensor = `null`, or 4 h passed) | `FRESH_BALANCED`: `getCurrentLocation(FUSED_PROVIDER, LocationRequest(quality BALANCED, duration 30 s))`, falling back to today's GPS path when fused gives nothing |
| `locate`/`ring` command | `FRESH` (today's GPS path, unchanged) |

With zones on a `zones_v1` phone the interval is the server's effective one (>= 30 min, decision 13); without zones it
is the parent's 10-240 min. ~~The passive source and the motion skip apply to every Regularly phone~~ - as built (the
code QA's Q-2, the coordinator's decision) everything of step 2 - the passive source, the motion skip, the heartbeat,
the backstop counting from the beat, the series - applies only to a phone with zones; without zones the phone does
exactly what it did before.

### 4.5 The watch (`zones/ZoneWatch.kt`; pure `watchStep` in `ZonePlan.kt`)

The phone keeps, per zone, the side of the last fix it **reported** (`side()` from `zone_vectors.json`, the server's
rule; never the two-fix rule).

- **Triggers of a check**: a fence broadcast; the significant-motion trigger; a passive fix (checked as it comes, no
  new fix); a moving follow-up alarm.
- **A check** takes a `FRESH_BALANCED` fix (passive fixes are their own check) and compares each zone's side with the
  last reported one. A sure side that differs is a **crossing**: the fix is reported now (`SyncRunner.request("zone")`
  with it as `location`), and a **confirming fix** is taken 2 min later with the precise path (today's `FRESH`, GPS
  first) and reported too - the server's two-fix rule then confirms within ~2 min. No difference: the fix is kept
  locally (cache, heartbeat), not sent.
- **Moving** (significant motion fired): checks at `max(3 min, d / 15 m/s)` (`d` = the nearest boundary from the last
  fix, 15 m/s a car in town), capped at 30 min; the series ends after two checks within 100 m of each other (still)
  or after 2 h, and the sensor is armed again. Alarms: `setExactAndAllowWhileIdle` (`USE_EXACT_ALARM` held; on the
  allowlist they are unrestricted, design 19b), request code 8 (follow-ups) and 9 (the confirming fix).
- **The sensor**: `SensorManager.getDefaultSensor(TYPE_SIGNIFICANT_MOTION)` + `requestTriggerSensor` - a one-shot
  wake-up trigger, disabled after it fires and armed again by us (after a fix for the heartbeat flag, at a series' end
  for the watch). It needs no permission (unlike the step sensors' `ACTIVITY_RECOGNITION`) and runs in the sensor hub
  [inferred: Doze itself arms it while idle]. Missing sensor -> `movedSinceFix = null`: the heartbeat always fixes
  when due (today's behaviour) and only the fences and passive fixes trigger checks.
- Every reported fix also feeds `extra_locations` when its sync fails (the queue, at most 20, CE prefs, `commit()`).

### 4.6 Permissions, Doze, the pause, hardening, the kid's side

- **Background location** is self-granted as device owner today (`LocateCommands.currentLocation`), with a log line
  because it was never confirmed on a device. Fences, the passive request and the anchor-held listener need it (the
  anchor is a `specialUse` FGS, not `location`); step 2's first device check is `dumpsys package` + `appops get
  me.vibb.launcher FINE_LOCATION` = allow, and `zone_watch.background_location` shows it in the PWA.
- **Doze**: the fence's broadcast comes with system_server's wake lock; our alarms are while-idle and unrestricted on
  the allowlist; the anchor's FGS state passes Doze's network firewall (19b §0). A phone that moves leaves deep Doze
  anyway (the same sensor). Still in Doze, the heartbeat is skipped - the biggest saving.
- **The pause** (decision 12) and the override change nothing here.
- **"Keep location on"** (`lock_location`: `DISALLOW_CONFIG_LOCATION` + Location on) is what makes zones reliable; it
  stays hardening (not lifted by the pause). With it off the kid can turn Location off, the fences go inactive
  (§2), `zone_watch.location_on` reports it and the PWA says so (§3.6). Off in the parent's mode turns Location off as
  today and removes the fences.
- **The kid's side** (§11 decides): one calm line in Kid Settings while the mode isn't off - "Telefonen deler hvor den
  er med foreldrene dine" / "This phone shares where it is with your parents" - from the cached policy, no contract
  change. Today nothing tells the kid; zones make the sharing more continuous, and an honest line is calm, not
  flashy. Fences make no location indicator; the phone's own fixes show Android's indicator as today.

## 5. Battery model

Estimates for a Jelly Star class phone (2000 mAh, a small SoC; screen off), marked so until the device run (§9)
measures them. A sync (status report over tsnet with the radio's tail) is counted separately; the backstop already
syncs every 30 min in every model, so a 30-min heartbeat adds no sync.

| Mechanism | One costs (estimate) | Notes |
|---|---|---|
| GPS fix, today's path (`getCurrentLocation(GPS)`, <= 15 s, the AP awake) | 0.25-0.4 mAh | indoors often times out and falls back to the last known |
| Balanced fused fix (Wi-Fi scan + network lookup, 2-5 s) | 0.1-0.25 mAh | no GPS on a GMS phone [inferred] |
| An extra sync (mobile data tail) | 0.2-0.5 mAh | less on Wi-Fi |
| A passive fix | ~0 | someone else paid for it |
| Significant-motion trigger | ~0 | in the sensor hub, already used by Doze [inferred] |
| AOSP fence poll | one balanced fix per 30 min while any fence exists | hidden, charged to our UID |

| Per day (kid: 4 trips, 8 crossings, still ~16 h) | Fixes | Extra syncs | mAh/day | Of 2000 mAh |
|---|---|---|---|---|
| Today, Regularly every 10 min | 144 GPS | +96 (backstop pulled to 10 min) | 55-105 | 3-5 % |
| Today, Regularly every 30 min (the server's default interval) | 48 GPS | 0 | 12-19 | 0.6-1 % |
| Today, Only when asked (the server's default mode) | on request | 0 | ~0 | ~0 |
| New, Regularly 30 without zones (passive + motion skip) | ~10-20 GPS/balanced | 0 | 3-8 | 0.2-0.4 % |
| New, zones + heartbeat 30 | 48 fence polls + 0-10 heartbeat + 8 x (balanced + GPS confirm) + ~16 moving checks | +16 (two per crossing) | 17-30 | 0.9-1.5 % |
| New, zones + heartbeat 60 | the fence still polls every 30 min | +16 | 15-27 | 0.8-1.4 % |

So zones with a 30-min heartbeat cost about what today's 30-min Regularly costs plus ~5-10 mAh for minutes-fast
crossings, and a third of today's 10-min Regularly. The fence poll dominates and can't be stretched (§2), so a 60-min
heartbeat saves little: **30 min** is the default (§11).

**What the emulator can and can't measure.** It can count: `dumpsys batterystats --reset` then `dumpsys batterystats
me.vibb.launcher.debug` (wake locks, alarms, GPS time and Wi-Fi scans charged to our UID - the fence's work source
included), `dumpsys location` (our geofences and their state, the fused provider's merged request and its 30-min
interval with our work source, the last locations), `dumpsys alarm | grep vibb`, `dumpsys sensorservice` (is there a
significant-motion sensor, is our trigger armed). It can't give mAh: the emulator's power profile is a stub and its
radios cost nothing. mAh comes from the Jelly Star run (design 07 §3's protocol: a night untouched, then a school day,
batterystats' estimated power for our UID, today's build against step 2's).

## 6. Privacy

- Zones are the family's (every parent sees and edits them, the security log says who); each parent's own Notify Me
  About decides their pushes.
- Nothing new leaves the phone but fixes: a crossing adds two fixes; moving checks without a crossing stay on the
  phone; the passive feed adds no points beyond the parent's interval (§4.3); `zone_watch` is counts and switches, no
  place. Zone names never reach the phone (decision 9).
- Retention unchanged for the trail; zone events follow it (decision 6); zones themselves are deleted with the zone or
  the phone (cascade). Backups carry zones like the trail (the same database).
- Coordinates are never in the security log (§3.7), never in a cached page (`zones.json`, the zone's page in
  `no_store_path`).
- Mock locations are ignored and counted (decision 14); the kid gets an honest line (§4.6, §11).

## 7. Security

Admin pages behind the session and CSRF; every phone value through `checked_location` (also each `extra_locations`
entry), `textContent` and Askama escaping (security review H2); a zone name is the parent's text, escaped everywhere
and cut by `Phone`'s limits in an event; `zones.json` and the zone page `no-store`; the routes 404 a zone of another
phone; `zone_watch` is a capped object of known keys (`sanitize_zone_watch`, others dropped). The receiver and the
PendingIntents per §4.2.

## 8. Tests

**Server (step 1)**: `zones::tests` - the shared vectors; `step`'s table: two sure fixes -> one transition at the first
one's time; one bad fix between two good ones flaps nothing; unsure fixes neither confirm nor break; the 60-s gap; a fix
not newer than `evaluated_to` is ignored; the first side silent; out-of-order fixes in one report evaluated in time
order. `src/tests/zones.rs` - create/save/delete and every refusal (each field, the 11th zone, a taken name, another
phone's zone 404) re-rendering at `#zone-form` with nothing written; every form returns to its group
(`every_form_on_the_locate_page_returns_to_its_group` gains `#zones` and Locate Regularly's `back=zones`);
`no_store_paths` gains both; a moved zone forgets its side, a rename doesn't; the duplicate-fix intake (a re-sent
cached fix stores one row and evaluates once); a report pair raising `zone_arrived` with a push only for parents
whose `push_zones` is on (`tests::web_push`'s `ScriptedPush`), the arrive switch off raising nothing, an old
confirming fix in the inbox without a push; retention pruning zone events at the phone's days; the security log
lines without coordinates; the summary's "At Home since" and the fix row's badge; the §3.6 warnings by mode;
`migrations_73_to_90_in_order` (renamed as it grows). `jstest/zones.test.js` (a fake Leaflet): a tap writes the fields,
the slider redraws, Use Phone's Location; `jstest/locate.test.js`: circles from `zones.json`.

**Both (step 2)**: `policy_json_keys_snapshot` and `PolicyResponseCompatTest` (§4.1); the effective interval (zones +
`zones_v1` -> 30, without the capability the stored 10); `extra_locations` capped, checked and ordered;
`zone_status_report.json` both sides; `mock_location_ignored`.

**Launcher (step 2), pure JVM**: `ZonePlanTest` - the vectors; `fencePlan` (a new process start re-registers all, the
same fingerprint nothing, a changed radius all, `[]` removes all); `watchStep` (a changed sure side reports and
schedules the confirm, an unchanged one keeps it local, the echo window, an id not in the policy, the moving interval
from the distance and its end on two still checks); `LocationPolicyTest` - `heartbeatAction`'s table (§4.4) incl. no
sensor and the 4-h cap; the passive filter (mock in release, accuracy over 200 m, older than the cached fix); the
queue's cap of 20.

## 9. Checks on the emulator and the phone

Emulator (`docs/testing/emulator.md`, a new §6 entry; the Play image, the debug build, which accepts mock fixes):
`adb shell appops set com.android.shell android:mock_location allow`, `cmd location providers add-test-provider fused`,
`set-test-provider-enabled fused true`; a zone Home, r = 150 m, Regularly.

1. `dumpsys location`: one geofence of our package, the fused request at 30 min with our work source; `zone_watch` on
   the Locate page says 1 fence.
2. Inside, silent: `set-test-provider-location fused --location <centre> --accuracy 20` twice, 2 min apart - the
   registration echo dropped (logcat), Home's side set, no event.
3. Leave: a fix 1 km away (accuracy 30) -> the fence's exit broadcast -> a check, a report, the confirming fix 2 min
   later (`adb emu geo fix <lon> <lat>` - longitude first - for the GPS path, set to the same place first) -> "Sverre
   left Home" in the inbox and as a push.
4. Straddle: a fix 100 m from the centre with accuracy 200 -> unsure, nothing raised, nothing reported beyond the
   heartbeat.
5. Arrive back, then one bad fix outside between two inside fixes -> no flap.
6. A policy change (the radius in the PWA) -> one fence with the new radius, never two; delete the zone -> none; Only
   when asked -> none.
7. `adb reboot`, unlock -> the fence back after the first sync; `adb shell dumpsys deviceidle force-idle`, then a
   crossing fix -> the broadcast still arrives.
8. Significant motion **can't be faked on the emulator** (no such sensor - `dumpsys sensorservice` shows it); the
   emulator runs the no-sensor fallback (the heartbeat fixes when due, `zone_watch.motion_sensor` false).
9. Counts for §5: `batterystats` over an hour still (heartbeat skips only on a phone with the sensor; the emulator
   shows the fallback's count) and over a scripted trip.

Jelly Star (release, mock fixes refused): the background permission and app op; the sensor present and armed; a walk
home with the screen off -> the push within ~5 min of arriving; the school day and night battery run (§5); a mock
location app (if one can be installed at all with the hardening on) -> `mock_location_ignored`.

## 10. Out of scope

Several families (design 63), zone schedules ("only on school days 08-15", "late" alerts), polygons, the kid's own
view of zones or arrivals, a per-parent per-zone switch, a "didn't arrive by" alarm, history of zone dwell times, an
SMS fallback. Notifications in Norwegian beyond §11's choice (the PWA's words are English throughout).

## 11. Decisions for the user (defaults in bold)

1. Radius: min **100 m**, default **150 m**, max **2 km** (or a min of 50 m, which a Wi-Fi fix rarely satisfies
   under the straddle rule).
2. Zones per phone: **10** (Android has no cap; the 100 is Play's).
3. Heartbeat with zones: **30 min** (the fence polls every 30 min anyway, §5) or 60 min.
4. A pause: **zones keep watching and pushing** (locating isn't a restriction) or events are held during a pause.
5. The push wording: **English like every other event** - "Sverre arrived at Home" / "Sverre left School" - or
   Norwegian, "Sverre kom til Hjem" / "Sverre forlot Skole" (a fixed form; "kom hjem" needs a per-zone phrase, out
   of scope).
6. The kid's line in Kid Settings: **yes, one calm line while location isn't off** (§4.6), or nothing as today.
7. Mock locations in a release build: **ignored and logged** (decision 14), or reported and marked.
8. When a crossing is old news: **push only when the confirming fix is under 30 min old** (inbox always), or always
   push.

## 12. Steps

| # | Step | Size |
|---|---|---|
| 1a | Server: migration, `zones.rs` (rule, vectors file), the intake's dedupe and evaluation in one transaction, the two kinds, `push_zones`, the push-only-while-news rule, retention, security log | M |
| 1b | Server: `#zones` on Locate, the badge, the summary's Now row, the zone page and its routes and refusals, `zones.json`, `static/zones.js` and `locate.js`'s circles, the §3.6 warnings and Locate Regularly, `no_store_path`, CSS tokens, JS tests, `docs/notes/server/pwa.md` + `notifications.md` | M |
| 2a | Both dirs, one commit: `location_policy.zones` and the effective interval, `extra_locations`, `zone_watch` (+ migration), `zones_v1`, the two contract files in both places, the snapshot and compat tests, root CLAUDE.md, the CI paths | M |
| 2b | Launcher: `ZonePlan` (fences, watch, heartbeat), `ZoneFences`, `ZoneAlertReceiver`, `PassiveFixes`, the sensor, the alarms, the queue, the mock rule, the Kid Settings line, `launcher/CLAUDE.md`, the emulator §6 entry and its run | L |

Step 1 (1a + 1b) ships as one server release and works with every launcher (zones judged at each fix). Step 2 needs
step 1 and ships as a server release before or with the launcher (an older server sends no `zones` key: the launcher
behaves as today). Roughly: step 1 two implementer sittings, step 2 three (2a one, 2b two), plus the emulator run and
a fix round each.

## As built: step 1 (2026-10-24, branch `design-64-step1`)

The user's answers to §11 (2026-10-24) are in: radius 100 m - 2 km, default 150 m; 10 zones per phone; no separate
heartbeat setting - with zones the served Regularly interval becomes `max(chosen, 30 min)` (step 2); zones keep
watching and pushing during a pause; English words, **stored as a key plus parameters, never prose**; the kid's line
is step 2's; mock locations ignored and counted (step 2: the status body has no flag today); push only while news.
Step 1 is server only, as §3 says, with these differences and details (the full account: `docs/notes/server/zones.md`
and `pwa.md`, "Locate: zones"):

- **The cursor is `(captured_at, fix id)`**, two columns `evaluated_at` + `evaluated_fix_id` instead of §3.1's
  `evaluated_to`: two fixes with one `captured_at` are told apart, and a fix "after the cursor" is exact.
- **The intake judges the stored fixes after each zone's cursor**, not only "the fixes actually inserted" (§3.3): the
  same transaction (`BEGIN IMMEDIATE`: store, evaluate, write the states, raise), but a fix that reached the table any
  other way is judged at the next report, once - and the cursor makes a re-run harmless. It runs on a duplicate fix
  too (cheap: one query for the zones, one for the fixes after the oldest cursor, at most 1000).
- **A new or moved zone starts at the phone's newest fix** (cursor there, side unknown): the history before it is never
  judged against it. §3.1 left the cursor empty, which would have replayed the whole kept trail into a new zone and
  could have told "arrived at School" for last week. "Here now" waits for two new locations.
- **An enrollment** forgets the sides and keeps the cursors (the old phone's fixes are never judged again).
- **Events carry `params`** (a new `parent_events.params` column in 0091; `title`/`summary` stored empty), worded by
  `parent_events::told` where they are shown or pushed (the time told at read, "Yesterday 14:02"). §3.4's `Phone::zone`
  became `parent_events::zone_crossing` + `told`. One switch for both kinds needed `Kind::switches()` (each switch
  once on Notify Me About).
- **The distance is compared in whole millimetres** (`d`, `a` and `r` rounded) so `d + a == r` is exact on both sides of
  the contract; `testdata/zone_vectors.json` has 15 rows including a millimetre each side of both boundaries.
- **A save lands on the zone's row** (`?zone=added|saved&zone_id=N#zone-N`, the line under the row) rather than §3.5's
  `#zones`; a delete and Locate Regularly land on `#zones`. A save that changed nothing logs nothing.
- **The zone page uses the time rule's sticky editor bar** (Back and Add/Save at the top, a refusal's line in it), the
  map inside the form. Coordinates are stored rounded to 6 decimals, so a form saved unchanged never "moves" the zone.
- **§3.6's table, step 1's part**: the Off row gains "Zones can't see where the phone is."; Only when asked shows the
  orange "Zones need Regularly" + Locate Regularly (`back=zones`); Regularly's footer "within about two intervals (here
  1 hour)"; "Keep location on" off adds the note row to Advanced. All only while the phone has zones. Decision 3's floor
  and its words come with step 2 (to phones reporting `zones_v1`); until then the served interval is unchanged and the
  trail as dense as today.
- **The summary's line is in the header** ("At Home since 15:42", `data-zone-now`), beside the battery and "Off since" -
  the summary has no "Now" group.
- Not done in step 1, as planned: the policy, the status report, the launcher, mock locations, the kid's line.

**The code QA's fix round** (`docs/review/qa-design-64-step1-code.md`, Q-1..Q-8, N-8):

- **The intake is two steps**: the fix stored on its own, then the judging in its own `BEGIN IMMEDIATE` transaction.
  A zone's error (or a crash between) never loses the fix; the cursors didn't move, so the next report judges it,
  once (Q-6).
- **What the pages say** (Q-1, Q-2, the coordinator's decision on open question 1): the fix's row and popup from that
  fix's own side; "Here now" and the summary's line only with no other side pending and a fresh newest fix (2 x the
  interval, else 1 h); a stale zone's row says "Last seen at Home 14:02", the summary nothing.
- **A new or moved zone counts a newest fix under 30 min old as its pending side** (open question 2): one more fix
  sets the side, silently.
- **Activity's clock-only summary** is dropped, now a rule in `notifications.md` (open question 3).
- The Locate map applies the zones after the locations, and a span without points shows the zones (Q-7); a zone name
  refuses Unicode's format characters too, "No line breaks or invisible characters." (Q-8); `zones.json` drops `here`
  and is a 404 for an unknown phone (N-8); tests for the confirming fix's age (Q-4), the tie at a cursor's instant
  (Q-5) and the zone forms in both "every form lands" tests (Q-3).

## As built: step 2 (2026-10-24, branch `design-64-step2`)

Both directories; the user's decisions of 2026-10-24 bind it (radius 100-2000 m, 10 zones, no heartbeat setting - the
floor -, zones watch and push in a pause, English key + params, the kid's line, mock fixes ignored and counted, push
while news). The full account: `docs/notes/server/zones.md` ("Safe zones, step 2"), `docs/notes/server/pwa.md`
("Locate: zones", step 2), `launcher/CLAUDE.md` ("Safe zones on the phone"); the emulator run is
`docs/testing/emulator.md` §6t. As §4 says, with these differences:

- **The phone decides nothing; distances only time the fixes** (decision 1; the coordinator's correction of
  2026-10-24): no local side state and no copy of `zone_vectors.json` in the launcher. A fence broadcast asks for a
  balanced fix now and a precise (GPS first) confirming one 2 min later, both reported at once. **§4.5's moving
  series is built** (`zones/ZoneSeries.kt`, the pure `nextCheckDelay`/`seriesOnMotion`/`seriesEnd`/`checkVerdict`): a
  significant-motion fire while the policy has zones starts checks every `max(3 min, d / 15 m/s)` - `d` the haversine
  distance from the cached fix to the nearest boundary less the fix's accuracy, its age taken off, capped at 30 min -,
  each a balanced fused fix (no GPS); a check whose fix is on the other side of a boundary than the cached fix (plain
  distance against the radius, a hint only) is queued and reported now with the confirming GPS fix 2 min later, one
  within its accuracy of a boundary is queued and reported now, any other waits in the kept slot (the fix round). The fused fix
  also passes the platform fence. The series ends after 60 min, 20 checks, or no motion fire within twice the last
  delay (the sensor is kept armed while zones are watched). No sensor (the emulator): no series. The `watchStep`
  bookkeeping of §4.5 (a per-zone side of the last *reported* fix) is replaced by the cached fix.
- **The paths** (§4.4): `LocationAction.FRESH_BALANCED` (the fused provider at balanced power, 15 s, GPS only when it
  gives nothing) for the heartbeat and a crossing's first fix; today's precise `FRESH` (GPS first) for `locate`/`ring`
  and a crossing's confirming fix; the series' checks the balanced fix alone (`LocateCommands.seriesFix`).
- **The capability is the watch**: the floor waits for `zone_watch.fences > 0` in the phone's latest report since its
  enrollment, not for `zones_v1` (claimed, read by nothing): a phone whose fences were refused keeps the parent's
  interval. `zone_watch` is §4.1's object; `mock_ignored` is a **running** count since the launcher's data was created
  (not "since the last report"), so a lost report loses nothing; the server logs each rise (`mock_location_ignored`)
  and the Locate page shows the count.
- **`extra_locations`** are the fixes of reports the server didn't take (the report's own `location` queued on a
  failure), not every own fix at capture; a passive fix is never queued or sent on its own (§4.3, the trail as dense as
  the parent's interval) - it waits in the kept slot until a due heartbeat (`KEPT`) or a crossing promotes it (the fix
  round's Q-1). Read
  leniently on the server (a bad entry is dropped, never the report); every one stored before one judging (code QA
  N-4). Each fix may carry `provider` and `mock`; the server stores both (migration 0093) and shows them only in the
  Locate page's newest-fix row's title.
- **Un-managed and a new enrollment** (the coordinator's decision): `AppEnforcer.apply` on a phone that isn't managed
  any more calls `ZoneWatch.release` - every fence of ours removed, the passive feed, the motion sensor and the series
  stopped, the queue and the kept fix emptied; both enrollment paths (`Provisioning`, Settings' enroll) call
  `ZoneWatch.forget` - the same, and the fence record, the beat's stamp and the mock count (Q-8).
- **The heartbeat** is `heartbeatDue` with a stamp of its own (`last_beat_ms`), so a skipped fix (`STILL`, `KEPT`) waits
  a whole interval and `BackstopAlarm` counts from it; the 4-h cap counts from our last own fix. The fix itself is
  the balanced fused one (`FRESH_BALANCED`, above).
- **Names and numbers**: the fences' action `com.kidslauncher.mdm.action.ZONE` (the codebase's prefix, not §4.2's
  `me.vibb.launcher...`), request code 8 for the fences (one per zone by the data URI) and 9 for the confirming fix;
  the record and the queue in CE prefs `zone_watch`. The kid's line uses the app's "mum and dad" / "mamma og pappa"
  (§4.6 said "your parents").
- **The Locate page**: Regularly's footer for a watching phone drops "within about two intervals"; the watch's line
  (fences, no motion sensor, Location off, no background grant, no fence yet); "Every" says "30 min while the phone has
  zones" while the served interval is above the choice (the select keeps the choice); "Here now" counts in the served
  interval (`freshness_inputs`).
- **Mock fixes on the emulator** (read from AOSP, to confirm in §6t step 1): `adb emu geo fix` reaches the GNSS HAL, so
  its fixes are the `gps` provider's and not mock; a `cmd location providers` test provider's fixes are mock
  (`MockLocationProvider`). The debug build takes both; a release build would refuse and count the second.

### The code QA's fix round (`docs/review/qa-design-64-step2-code.md`, Q-1 to Q-12)

- **Q-2 (the gate)**: everything new hangs on `zoneWatchActive` (device owner, managed, Regularly **with zones**;
  `ZoneWatch.zonesOn`) - the passive feed, the motion gate, the heartbeat, the backstop counting from the beat, the
  series, the balanced path and the queue. Without zones the location step is the pure `syncLocationAction`'s first
  line, today's `locationAction` exactly (a test runs every input), and the backstop counts from our last own fix
  (`backstopAnchorMs`). The mock rule (decision 14) stays on every phone.
- **Q-1, Q-4 (the trail)**: a passive fix or a series check's that is no crossing candidate goes to a **kept slot**
  (`LocateCommands.keepFix`, default prefs `zone_kept_fix`), never the cached fix; `CACHED` keeps re-sending the fix the
  last beat reported. Only a due heartbeat's `KEPT` and a crossing's sync (`promoteKept`), or a series candidate, make
  it the reported fix. `KEPT` is the slot's fix not yet reported, never our own reported one.
- **Q-3 ("Here now")**: a report that re-sends the cached fix while the motion sensor says the phone hasn't moved since
  our last own fix (under 4 h) says `zone_watch.still: true` (`reportStill`); the server stamps the fix's row
  `device_locations.resent_at` (migration 0093) and "Here now" (`freshness_inputs`, the Locate page) counts from the
  later of `captured_at` and it. The fix's own time is never rewritten; a re-send without `still` changes nothing.
- **Q-5**: Location Off (and no zones) empties the queue **before** the report, which then carries no extras.
- **Q-6**: the motion gate arms only after a genuinely fresh own fix (`freshOwnFix`: taken at the request, less 60 s),
  never after a fallback to the cached or an old last-known fix; a fire and an arm share one monitor.
- **Q-7**: the fence record and its stamp are saved before the first `addProximityAlert`; no record is within the
  echo window.
- **Q-8**: `ZoneWatch.forget` at both enrollment paths.
- **Q-9**: the phone counts a mock fix once by its time and place (the last 8 keys); the server logs a rise once
  against `devices.mock_logged` (an UPDATE only a higher count passes; 0093) and the Locate row says when it last rose.
- **Q-10**: `location_policy.zones` goes only to a launcher whose latest report since enrollment claims `zones_v1`.
- **Q-11**: the glue pins G1-G9 in `ZonePlanTest`; the server's S6, S8 and S12 tests in `tests::zone_watch`.
- **Latency and the still phone**: a fence-only crossing waits up to the fence's 30-min poll (or another app's fused
  fix, or the heartbeat) plus the 2-min confirm; on a phone with the motion sensor a still phone's heartbeat re-sends
  the old fix for up to 4 h - "Here now" stays by `still` (Q-3).
