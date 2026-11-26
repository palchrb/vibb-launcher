# 138 - A zone's Wi-Fi: the parent confirms it, the phone learns it, both bound to the router

Status: **built** on branch `wifi-link-confirm` (2026-11-26; revision 3 - the single-router fix and the code QA, §12);
design revision 2, 2026-11-26 (branch `wifi-link-confirm`), read against `main` 3e84d466f. Approved by
the owner on 2026-11-26 as A + B + the router's address; revision 2 takes the design QA (H1, M1-M4, L1-L9) and the
owner's answers of the same day (Q1, the travel router, the cell veto; §11). Revision 1 was 714ab8e3b. It blocks the
next launcher RC and the stable release. Both directories, one migration (0180), one new contract file. Android
14-17: every API used is at or under minSdk 34 (§9); the emulator rows run on 14 and 16.

## 0. Short answer

- **The finding.** Design 131 made a Wi-Fi fix without a check never fresh. Indoors on the home router GPS never
  fixes, and a zone link is taught only from GPS fixes, so a phone that lies at home never gets a `link` check. After
  rc.4's install restarted the process (a new process starts `MOVED`), every heartbeat try found a good Apple fix
  (`attempt path=TOGETHER|LEARNED_FIRST result=vibb_apple`, `wifi fix check=none step=USE`, later `step=GPS`), logged
  `FIX result=none` and backed off 20, 40, 90, 190 min; the PWA showed an old position. A real motion fire indoors
  does the same.
- **A - the parent confirms the zone's network** on the zone's page: the network the phone is on now (its name, when
  it is a WPA2/WPA3 personal network), one tap. It counts at once. Removable; more than one per zone. Bound to the
  router (its address's first five octets - one router's bands - or its multi-link address) and the name. Moving the
  zone further than its radius clears it. Good GPS far outside the zone with that router connected or heard, twice,
  blocks it, and the zone shows one orange line. **A is the path everywhere** - a detached house hearing only its own
  router through the single-router fix (D15, revision 3): connected to the confirmed router and no other Wi-Fi fix, a
  `link` fix at the router's own position with an accuracy by the connected level.
- **B - the phone also learns the link from Wi-Fi fixes** surely inside the zone while it is on a PSK/SAE network:
  the same link row and counters as GPS teaching (3 occasions on 2 days). **B is for areas with neighbours**: it
  learns only when the neighbours' routers alone place the phone inside.
- **At every use, the neighbours must agree**: the Wi-Fi fix recomputed without the connected router (its network
  and its device, so its guest name is out too) must still have its centre inside the zone. Only a parent's
  confirmation may stand where nothing else is placed - unless the serving cell is placed elsewhere, the router's own
  rows have moved, or it is struck. A registered cell placed far from the zone vetoes every link and the single-router
  fix.
- **A router that travels with the family should not be confirmed** (§3): elsewhere its neighbours disagree, and
  where nothing else is heard the first good outdoor GPS fix moves its rows and strikes it - until then it can stand
  for "home".
- **How the PWA learns the router**: the status report's `zone_watch` gains `wifi` - the connected network's name,
  BSSID and multi-link address - while the policy has zones. A confirm binds to the newest report that has it.
- **A process restart still counts as `MOVED`** (D11); "Fjern" ends only the parent's confirmation (Q1, decided).
- **Server**: migration **0180** (`zone_networks`); the post-release chain renumbers after it in its own order. The
  policy's `location_policy.zones[]` gains `networks`. The contract is `contracts/core/zone_networks.json` (new) and
  `zone_status_report.json` (grown), both sides in one commit.

## 1. Today (`main` 3e84d466f)

- `places/PlacesPlan.kt`: a `RowKind.LINK` row per zone and network (key = the zone's HMAC of id, centre and radius,
  `network` = the SSID's key, `device` = design 131's binding: the security class and Android's network id).
  `teachLinks` runs only at a GPS teach (`teachWith`): surely inside (`d + a <= r`) while connected, an occasion
  (>= 30 min apart) and a day; surely 200 m outside, every *heard network name* with a row of that zone gets a strike
  (the first revokes, a second within 60 days blocks 60 days). `PlaceRow.linked` = 3 occasions on 2 days, not
  blocked. `linkConfirms` (the `link` check), `linkRenews` (D12's still renewal) and the leave-one-out `ZONE` class
  read the same rows. No router address anywhere in the link.
- `wifiFresh(fix, check) = fix.fresh && check != null`; `MdmSyncWorker` stamps a fresh fix only when
  `!places.notFresh`. An unchecked Wi-Fi fix fails the heartbeat try.
- The status report carries no connected network. `zone_watch` is a heartbeat field (`HEARTBEAT_FIELDS`), stored per
  report in `device_status.zone_watch_json` (`zones::sanitize_zone_watch`, known keys only, at most 512 bytes).
- The policy's zones are `{id, latitude, longitude, radius_m}` (pinned by `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`); the fences' fingerprint and the zone key read only the circle.
- The attempt's snapshot reads the connection through `connectedNow` (design 134 D25) and the cells (`readCells`);
  the passive teach (`offerPassive`) reads the connection without the callback.

## 2. Decisions

### D1. Two sources, one check

A link is a zone, a network name and a router. One pure function answers whether the connection holds one
(`zoneLink`, `PlacesPlan.kt`):

```
enum class LinkBy { CONFIRMED, LEARNED }
fun zoneLink(conn: ZoneConn, zone: LinkZone, confirmed: List<ConfirmedAp>, struck: Set<Long>,
             rows: List<PlaceRow>, nowMin: Int): LinkBy?
```

- **The router's match** (both sources, the server's page too - H1): the connected AP's device key - its BSSID's
  first five octets, design 125's one router's bands (`keys.device(bssid, null)`) - or its multi-link address
  (`keys.device(bssid, mld)`, `mld` known on both sides) equals the router's; and the network key (the SSID's) is
  the same. A mesh node with its own address prefix is another router (D5).
- **CONFIRMED** - a parent's confirmation for the zone (D2) that isn't struck (D7), matched as above. It needs a
  `ZoneConn`: connected, unmetered, PSK or SAE, a readable BSSID and a name - **not** Android's network id (M4: the
  callback's `WifiInfo` may come back with -1 on the Jelly).
- **LEARNED** - the zone's `LINK` row of that network, `linked` under design 131's binding (which keeps the network
  id: the learned link is unchanged there), and a `LINK_AP` row (D5) of the zone matching the router. The security
  part stays `linkSecurityHolds`: PSK/SAE, or enterprise with its AP among the fix's own rows; open, OWE and unknown
  never.

`linkConfirms`, `linkRenews`, the `ZONE` leave-one-out class and the strikes (D7) call `zoneLink`; nothing else reads
`LINK` rows.

### D2. A: the parent's confirmation

- **What can be confirmed**: the network of the phone's newest report that has `wifi` (D3, D4). Open, OWE and
  enterprise networks are never reported, so they can never be confirmed; an enterprise network keeps design 131's
  rule (learned only, its AP among the fix's own rows).
- **One row per BSSID** (`zone_networks`, D12): the zone, the name, the BSSID, the multi-link address when the phone
  had one. A mesh with its own names, or with nodes of another prefix, is several rows. **At most 16 per zone**,
  blocked rows counted (L8: a blocked row stays listed until the parent removes or replaces it, which frees its
  place).
- **It counts at once**: the save nudges the phone (`command_notify`, as every zone save), the policy carries the row
  (D13), and the next attempt's `linkConfirms` answers `CONFIRMED` - no occasions, no days.
- **Remove** ("Fjern", Q1 decided): every row of that name for the zone. It ends the parent's confirmation only; the
  phone's own learned link for the same network keeps confirming.
- **Replace**: no edit. A new router is confirmed (a new row); a blocked row is replaced by confirming its router
  again (a new id, the block gone).

### D3. How the PWA learns the router: `zone_watch.wifi`

The status report's `zone_watch` (a heartbeat field already, so a changed network never forces a full report and
`HEARTBEAT_FIELDS`/`fields_tag` stay) gains:

```json
"wifi": {"ssid": "Net-A", "bssid": "00:00:5e:00:53:01", "mld": "00:00:5e:00:53:10"},
"wifi_struck": [7]
```

- `wifi` only while the policy has zones (Regularly with zones), Location is on and the phone is device owner, for a
  `ZoneConn` (D1) with a UTF-8 name (Android's quoted form; a hidden or hex-only name is left out). `mld` only when
  Android gives one. Otherwise no `wifi` key. No `links` (L7).
- `wifi_struck`: the confirmation ids the phone struck (D7) that the policy still carries, at most 80.
- Read on the report's path by `WifiPlaces.reportWifi(context)` (suspend, in the sync before the report is built)
  through `connectedNow` (D10).
- Server: `sanitize_zone_watch` keeps the new keys only in their shape - `ssid` 1-32 bytes, no control characters;
  `bssid` and `mld` six lowercase hex pairs, unicast, never `00:00:00:00:00:00` or `02:00:00:00:00:00` (Android's
  redacted value); `wifi_struck` positive integers within the cap. A bad `wifi` is dropped alone, the rest of
  `zone_watch` kept. `ZONE_WATCH_MAX` 512 -> 2048. `ZoneWatch` keeps its fields and its `Copy`; the network is read
  apart (`zones::reported_wifi`, with the report's `reported_at`).
- **Privacy** (L9): the server holds the name and address of the network the phone is on, only while it has zones,
  in report rows it prunes after 48 h, and the confirmed rows until removed. The phone's cached policy holds the
  confirmed names and addresses of its zones. Both already hold the zones' positions, so a router's name at a known
  place adds little. The security log never names a network (D12).

### D4. The confirm binds to the phone's report (L3, L4)

- The zone's page reads the phone's newest report (since its enrolment) that **has** `wifi`, when it is at most 1 h
  old and no newer report has `wifi` with another BSSID (a report without `wifi` - a redacted read - neither shows nor
  hides it).
- The current network's row (its name and Bekreft) shows when that report's router isn't already one of the zone's
  unblocked rows by the match of D1 (prefix or multi-link address, and the name), and the phone's newest stored fix
  isn't surely outside the zone (`zones::side`; no fix counts as not outside).
- The form posts the BSSID the page showed. The server confirms only when the same lookup, at the POST, finds that
  BSSID or its multi-link address; it stores the name and the multi-link address from that report, never from the
  form. Anything else: no row, the page again at `#zone-wifi` with "Telefonen er på et annet nettverk nå".
- The same BSSID twice (two parents): one row, the second tap a no-op that still says Saved. A blocked row of that
  BSSID: deleted and a new row made, in one transaction (a new id, so the phone's struck id no longer matches).
- The zone must be this phone's (404 otherwise); the 17th row is refused.

### D5. The learned link's routers (`LINK_AP` rows)

- **New row kind** `RowKind.LINK_AP(6)` in the sealed table: `key` = the zone's key, `device` = a router's device key
  (the five-octet key, and a second row for the multi-link key when known), `network` = the first network key it was
  taught under (informational); its id `p/key/device`. A router row matches by its device keys alone (QA M2): the
  `LINK` row binds the network, so the router's other name on the same device ("Net-A-5G", a guest name) holds its own
  learned link through the same router row. Version 1 stays (an older launcher would drop the table; no backward compatibility is owed). Dropped
  with its zone's key like a `LINK` row (a zone moved, resized, gone, the mode off Regularly).
- **What joins** (L5): the connected router at **every** teach surely inside the zone - GPS or B's Wi-Fi teach -
  while connected under the link's binding, counted as an occasion or not (inside the 30-min gap).
- **Kept alive** (L6): `lastSeenMin` moves at most once a day when the row confirms, so the 180-day ageing
  (`prune`) never takes a router in use.
- **Cut for this release (H1)**: revision 1's growth by a settled AP of the zone's rows. A mesh node with its own
  address prefix is another router: the page shows its Bekreft row, and the learned link adds it at its
  next inside teach.
- A link learned before this build has no routers: it confirms nothing until its next inside teach adds one.

### D6. B: the link learned from Wi-Fi fixes - the same row, one gate

**The same `LINK` row and counters** as GPS teaching. The row means one thing - this network was connected inside the
zone on N occasions over D days - whatever placed the phone inside; a sibling kind would double the binding, rebind,
clear and strike code for one meaning. The difference in grade is handled at the entry, and strikes come only from
GPS (D7).

A Wi-Fi teach (`wifiOccasion`, pure; once at an attempt's end, `WifiPlaces.attempt`, on the snapshot the fix was
judged on) counts when all hold:

1. the attempt judged a Wi-Fi fix (`vibb_wifi` or `vibb_apple`) from a fresh scan view, its verdict not `REFUSE`, its
   check not `link` (a link never teaches itself);
2. the attempt marked no disagreement (no overruling satellites, no impossible jump, no learned contradiction);
3. the fix is surely inside the zone (`d + a <= r`);
4. **the fix without the connected router** (D8's exclusion) exists and is surely inside the zone - no exception
   here: B learns only where the neighbours place the phone;
5. the connection has a `LinkConn` whose security holds, and no cell veto (D9).

Then `teachLinks(..., strikes = false)` adds the inside occasion under the binding (131's rules) and the router joins
(D5). The occasion gap (30 min) and the day rule are the same; GPS and Wi-Fi occasions add up - at home on the
30-min heartbeat, about a day and a night.

### D7. Strikes: by router, two fixes (M2)

At a GPS teach (teaching quality: <= 25 m, >= 5 satellites, not mock in a release build), a router of a zone's links
(a confirmation's or a `LINK_AP` row's, by D1's match) is **struck** when:

- **connected now** and the fix is surely outside by 200 m (`d - a >= r + 200 m`, as today), or
- **heard in the results paired with the teacher** and the fix is outside by the band's reach
  (`d - a >= r + reach`: 300 m on 2.4 GHz, 150 m on 5 and 6 GHz - design 125's `Band.reachM`);
- and a **second teaching fix at least 60 s and at most 2 h later** says the same (any teach: the same stream, a
  passive fix, the teaching alarm, the next try). The first is pending in memory (per zone and router, lost with the
  process); one fix never strikes.

Then:

- **a confirmation**: its id joins the phone's struck list (CE prefs `zone_links` `confirm_struck_v1` - not the Wi-Fi
  places prefs, so a clear keeps it, L2; an id leaves only when the policy no longer carries it, and unenrolment
  empties it). `zoneLink` never answers `CONFIRMED` for it, and the report's `wifi_struck` carries it until the
  server's block reaches the policy. Logged `WifiPlaces: zone link struck by=confirmed` (no id, no name).
- **a `LINK_AP` row**: blocked 60 days (`blockedUntilMin`); the `LINK` row's counters stay. Logged `by=learned`.

The strike by a heard *name* goes: a same-named network elsewhere can't confirm anything any more. Wi-Fi fixes never
strike; good outdoor GPS always overrules a Wi-Fi fix (`gpsOverrules`, unchanged). The passive teach reads the
connection through `connectedNow` too (L1: `offerPassive` runs on the passive feed's executor, so the up-to-1-s
callback blocks nothing that matters), so a connected router can strike there.

### D8. At every use: the neighbours must agree (M3, the owner's travel router)

`linkConfirms(fix, ...)` answers `link` only when, for a zone:

1. `zoneLink` holds, and the fix's **centre** is inside the zone (`d <= r`) for CONFIRMED (M1: the parent vouched
   for the place), or the fix is **surely** inside (`d + a <= r`) for LEARNED (as today);
2. **the fix without the connected router** - the same view with `view.heard` filtered by `network != conn.network
   && device != connectedDevice` (the router's guest name on the same device is out too), its centre by the
   complete-link cluster without the majority rule or the accuracy cap (QA M1: only the centre is judged, and weak
   neighbours beside strong unknown networks still place it) - has its centre inside the zone (`d <= r`). For an Apple
   fix the same, over the table with its Apple rows;
3. **the exception, CONFIRMED only**: when nothing else is placed after the exclusion - no other result with a
   trusted own row or a live Apple row (an unknown router says nothing about where the phone is) - the confirmation
   stands, unless the router's own rows have moved or are blocked (design 125: `moves > 0` or blocked on any of its
   device's Wi-Fi rows) or it is struck. LEARNED has no exception;
4. no cell veto (D9).

**Built with the widening** (the coordinator, 2026-11-26): with exactly one placed network left after the exclusion and
no fix of its own, a confirmation stands when every row of that network lies within the zone's radius plus its range
(the same vetoes apply); two or more placed networks without a fix confirm nothing (the try runs GPS). A detached
house that hears only its own router has no Wi-Fi fix at all (a scan view needs three usable results and a fix two
networks): the single-router fix (D15) is its path.

The server's own rule (`zones::side`, the straddle) is unchanged: a `link`-checked fix that straddles the edge is
still unsure there.

### D9. The cell veto (the owner: the cells should say the router can't be trusted)

Design 134 rev 3's `cellElsewhere`, reused, vetoes every link confirmation and every B teach: the registered cell
read in the attempt (`snap.cells`) has a position - a trusted own cell row or a live Apple cell row already in the
table, never a lookup started for it - further from the zone than the cell's range + the fix's accuracy + 1 km
(`VouchPoint` = the zone's centre, its accuracy the zone's radius + the fix's accuracy). An unknown cell, no SIM, no
registered cell: no veto. **It needs a SIM; the Jelly has none yet.** The motion sensor can't help here: it says the
phone moved, not how far.

### D10. The connection read in the background

Reuse design 134 D25's `connectedNow`: `WifiManager.getConnectionInfo()` first; when it gives no readable BSSID (the
Jelly's `02:00:00:00:00:00` in the background) while the active network carries Wi-Fi, one
`NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` for Wi-Fi, up to 1 s. The attempt's snapshot, `teachWith`, the passive
teach (D7) and `reportWifi` read through it. Unreadable: no `wifi` in the report and no link this attempt (logged
`zone wifi report no reason=unreadable|none|metered|security|hidden`, and `connection read by callback yes|no
netid=known|none` - never a name or address). No new permission.

### D11. A process restart still counts as `MOVED`

No change. The flags live in memory because the significant-motion trigger is armed by the process: an update, a
crash or a kill leaves a gap in which a move is never seen, and carrying the flags across would vouch a phone that
moved meanwhile `STILL` for up to 4 h. The finding was the unchecked fix, not the restart: with A or B the restart's
`MOVED` try at home is `link`-checked and fresh, so it costs one attempt. Elsewhere indoors without a link the try
runs GPS and backs off - design 131's intended behaviour.

### D12. Server: migration 0180, the routes, the log

No existing table fits: `device_zones` is one row per zone with its rule state; a set of routers per zone needs its
own rows. This build keeps **0180**; the post-release chain renumbers after it in its own order (phase D's drop
before 135's family zones), each before its first push to `main` (the order hazard, `server/CLAUDE.md`).

```sql
-- Design 138: Wi-Fi routers a parent confirmed for a zone, one row per BSSID. `ssid` the name the
-- phone reported, `mld` the router's multi-link address when it had one; `blocked_at` set when the
-- phone reported the router seen far outside the zone (then never served).
CREATE TABLE zone_networks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    zone_id INTEGER NOT NULL REFERENCES device_zones(id) ON DELETE CASCADE,
    ssid TEXT NOT NULL,
    bssid TEXT NOT NULL,
    mld TEXT,
    confirmed_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    confirmed_at TEXT NOT NULL DEFAULT (datetime('now')),
    blocked_at TEXT
);
CREATE UNIQUE INDEX idx_zone_networks_ap ON zone_networks(zone_id, bssid);
```

- **Routes** (session + 2FA, as the zone's page; both land on `/devices/{id}/zones/{zone_id}#zone-wifi`, so a save
  never jumps to the top): `POST /devices/{id}/zones/{zone_id}/wifi` (`bssid`; D4) and
  `POST /devices/{id}/zones/{zone_id}/wifi/{network_id}/delete` (every row of that row's name in the zone).
- **The strike intake** (`zones::intake`, after the fix): `wifi_struck` ids that are this phone's zones' rows and not
  yet blocked get `blocked_at`, one security line each, and a nudge; another phone's ids are ignored.
- **Security log** (the zone's name, never the network's name or address): `zone_wifi_confirmed`,
  `zone_wifi_removed`, `zone_wifi_blocked`; `zone_changed` gains "Wi-Fi removed" when a move cleared them.
- **Zone edits**: moved further than its radius (old to new centre > the old radius) - every row of the zone deleted
  in the save's transaction; a smaller move or a radius change keeps them; a deleted zone cascades; a new phone
  enrolled into the row keeps them (they describe the router). On the phone any circle change drops the zone's `LINK`
  and `LINK_AP` rows (its key changes, as today); the confirmations come back from the policy.
- **After design 135** (family zones): whether the confirmations move to the family zone is 135's call at its rebase;
  left on the link row (`device_zones`) they keep working per phone. **Decided at the merge (2026-11-26):** the rows
  stay on the link (no schema change: 0180 shipped in server 0.94.0, and 135's migration is as built), and every
  write keeps a family zone's links in step - a confirm on the family zone's editor (`/zones/{zone_id}/wifi`, bound
  to the named phone's report) writes every link, Fjern and a move past the radius clear every link, a strike blocks
  the router on every link, and a new link starts with the zone's rows (`zone_networks::copy_to_link` in
  `zones::link`). So a confirmation is the family zone's and every phone using it is served the router.

### D13. The contract

- `location_policy.zones[]` gains `networks` - always present, `[]` for none, unblocked rows only:
  `[{"id": 7, "ssid": "Net-A", "bssid": "00:00:5e:00:53:01", "mld": null}]`. Design 64's "never a name" is the
  zone's name. The launcher's `PolicyZone` gains `networks: List<ZoneNetwork> = emptyList()` (a missing key: none).
  `fenceFingerprint` and `PlaceKeys.zone` read only the circle, so a confirmation never re-registers fences or drops
  learned links.
- **Files, one commit, both sides**: `contracts/core/zone_networks.json` (new: the policy's `networks` rows; the
  report's `wifi`/`wifi_struck` rows the server keeps and drops - the redacted and zero BSSIDs, a 33-byte name, a
  control character; the D1 match rows - same prefix, same multi-link address, another prefix, another name; a
  README row: 138, by hand, server `tests::zone_networks`, launcher `ZoneNetworksContractTest`),
  `contracts/core/zone_status_report.json` (one report with `wifi` and `wifi_struck`; its README row gains 138),
  `server/src/tests/device_api.rs` `policy_json_keys_snapshot`, the launcher's `PolicyResponseCompatTest`.

### D14. What the check changes downstream, and what doesn't

A `link`-checked fix is what design 131 already handles: it stands (`wifiVerdict`), is fresh (`wifiFresh`), may
become the still vouch, lets Locate stop at 20 s, and on the server is a fine fix. The owner's case: `MOVED`,
`TOGETHER`, the Apple fix judged `check=link step=USE`, GPS silent, `attemptKeep` keeps the Wi-Fi fix with `link`,
`FIX result=fresh`, no back-off. Unchanged: `gpsOverrules`, the jump gate, the still rules, design 134's witness (its
own same-AP rule, no `LINK` row), the Apple lookup, the server's `FixClass`, the fix's `check` on the wire. The kid's
phone shows nothing new.

### D15. The single-router fix (revision 3: the owner's "A" of 2026-11-26 and the location review's must-changes)

When the phone is connected to a **confirmed** router (`confirmedFor`: the same name and router, not struck; never a
learned link) and no other Wi-Fi fix stands (no own learned fix, no Apple fix), the attempt reports a fix from the
router alone (`routerFix`, `WifiPlaces.routerOnlyFix`). It runs after `learned()` and `appleFix()` in every plan - in
`LEARNED_FIRST` beside GPS after the Apple ask -, so "nothing contradicts" is judged with the Apple answers for unknown
heard routers already in the table.

- **The continuity gate** (round 2 M2): its witness is the newest, by time, of the phone's known fix (the kept slot's
  unreported fix, else the cached one) and the last satellite fix. The gate holds while that witness's centre is
  inside the zone (the verification QA's H1: the newest candidate that says something decides - a `link` fix with its
  centre inside says inside whatever its accuracy, so the single-router fix keeps the gate it came through; a fine fix
  by its centre; a coarse one over the radius, a cell fix, only when surely outside, else the next older one decides);
  when the witness is the boot right before this one's, only if the phone was off at most 10 min (the gap computed at
  the boot, kept apart from the power record the upload clears; unknown - a pulled battery - holds nothing). This boot's
  candidates are ordered by elapsed time, ahead of another boot's; a vouch re-sent after a reboot keeps its own boot. 10 min covers a restart and a battery swap; a phone off longer
  could have been carried anywhere (10 min in a car is 10-15 km), and "off at home, on at a remote place" must not
  start inside. It keeps a known "inside" (the restart case); it never brings a phone "home" after it was seen
  elsewhere - that stays GPS's, or an ordinary checked Wi-Fi fix's.
- **Where** (round 2 M3, L2): the first source whose centre lies within the zone's radius - its device's trusted own
  Wi-Fi rows (taught by satellite fixes while it was heard), weighted; else Apple's row for one of its own addresses
  heard (its device, multi-link or five-octet key - never by the network name); else the zone's centre. A router fix
  never lands outside its own zone (no false "left", no gate lock-out).
- **How sure**: `sqrt(pos^2 + level^2)`. `pos` is half the row's range (own or Apple's, typically 60-100 m, so 30-50
  m), or the zone's radius at its centre. `level` by the connected BSSID's entry in the answering scan: 25 m at -45 dBm
  or stronger, 50 m at -65, 150 m at -85 or weaker, straight lines between (100 m at -75) - the same room, a few walls,
  the edge of a home router's reach. Without that entry the -85 dBm bound (150 m): `WifiInfo.getRssi()` is stale with
  the screen off, so it is never used. Typical: 40-60 m on an own row; at least the radius (about 150-210 m) at the
  centre.
- **The centre, not the vouch**: re-sending the vouch would be no fresh fix - the finding again (`FIX result=none`,
  the back-off). A fresh fix at the zone's centre with an accuracy of at least the radius claims only "in or around
  the zone", which the continuity gate already holds.
- **`check=link`, provider `vibb_wifi`**: the position is the phone's own table's (or the zone's), not Apple's lookup
  of this scan. Fresh, so `FIX result=fresh`, and never withheld after a move (design 134's `withheld` needs no fresh
  fix); a `link` fix stands while still, so it renews the still vouch like any `link` fix.
- **Anything contradicting vetoes it**: the router's own rows moved or blocked; struck; a registered cell placed away
  from the zone (D9); the neighbours' own centre (D8's, without the router) outside the zone; two or more placed
  networks outside the zone's radius plus their range; any other placed network farther from the router's position
  than its range plus the router's; a conclusive scan of three or more other networks none of them placed near the
  zone; an impossible jump from the last satellite fix (`jumpGate`). The neighbours' own centre inside agrees.
- **It never teaches** (no B occasion, no router row, no own-row teach - `LearnedFix.router`, `judged` sets no
  `teachLater` for it) **and never strikes**; it is no outcome evidence for design 134's fingerprint and never a
  print's vouch (`Ask.routerFix`, round 2 L4).
- **An unchecked Wi-Fi fix in hand** (round 2 L1): the single-router fix is tried too (`withRouter`) - the unchecked
  fix is the neighbours' verdict, so its centre outside the zone vetoes the router fix (`others`); inside, the router
  fix replaces it (checked, fresh).
- **Where GPS ran beside it** (round 2 M1, `LEARNED_FIRST`'s GPS branch): the keep is TOGETHER's, so good satellites
  far off overrule it (`keepBetter`, `gpsOverrules`).
- **Where it is promised** (round 2 L3): where the neighbours are placed (own or Apple rows) or fewer than three
  unplaced networks are heard; a conclusive scan of three or more networks none of them placed near the zone vetoes
  it.
- Logged `WifiPlaces: router fix fix|none|unconfirmed|continuity|moved|cell|others|outside|unplaced|disagree|jump
  at=own|apple|zone acc=N`.

A router that moves with the family and is confirmed (§3) can't bring the phone "home" from elsewhere (the continuity
gate); elsewhere its own rows move at the first good GPS teach and it is struck - the reason a moving router should
not be confirmed.

**The server's badge** (unchanged this round): Locate's "At Home" comes from `zones::side` (`d + a <= r`), while a
confirmation takes the fix's centre inside; a `link` fix of 66 m whose centre is 100 m from the centre of a 150 m zone
shows no "At Home" on its row, and the zone's side stays what it was. The fix still keeps the heartbeat fresh.

## 3. What a link needs

- **A confirmed link** needs at once: the network's password (to connect), the router itself (another router with
  the same name and password has another address), a Wi-Fi fix whose centre is inside the zone, the neighbours'
  routers agreeing without it (or none placed), and no serving cell placed elsewhere.
- **The child may know the home password.** That is why the router is bound: the child's own hotspot named like
  home has another address and confirms nothing (and a phone's hotspot is metered, so it has no `ZoneConn`).
- **A copied router address** elsewhere is out-voted by that place's neighbours; where nothing else is placed, the
  cell veto (with a SIM) or the first good GPS fixes there - which move the copied address's rows and strike it -
  end it, and the zone's page shows "Sett et annet sted".
- **A router that travels with the family** (the same name, password and address everywhere) **should not be
  confirmed.** Elsewhere its neighbours place the phone elsewhere, so it confirms nothing; where nothing else is
  heard (a remote cabin) it stands for the zone until a registered cell placed away vetoes it (needs a SIM), or the
  first good outdoor GPS fixes there move its rows and strike it. Then it is blocked and the zone shows "Sett et
  annet sted"; removing it is the fix (§4's (i)). A learned link to it never forms where nothing else is placed (D6.4).
- **The single-router fix** (D15) adds nothing a confirmation didn't already allow where nothing else is placed; it
  only gives the place a position (the router's own) and an accuracy by the level, and every veto of D8 applies.
- **B adds little**: an unconfirmed network learns only from fixes surely inside the zone that hold without the
  router (D6.4), on 3 occasions 30 min apart over 2 days, with no disagreement in any of them.
- **A wrong confirmation** (Bekreft tapped while the phone is elsewhere): the page offers it only where the newest fix
  isn't surely outside; even so it confirms nothing where the fix or the neighbours aren't inside the zone, and GPS
  there strikes it.
- **Good outdoor GPS always overrules** a far Wi-Fi fix, checked or not (`gpsOverrules`), and the server still decides
  every arrival.

## 4. PWA words (nb and en)

On the zone's page (`device_zone.html`), a `#zone-wifi` section after the zone's form (its own forms; not on a new
zone). The rows: each confirmed name (`{name}`) with a "Fjern..." confirm; the current network's row (`{name}`,
Bekreft) when D4 lets it show. One orange line at most under the group, "Sett et annet sted". Revision 3 cut "Ukjent
ruter" (the location review): it invited a parent to confirm a router a child set up with the home's name and password.
The Bekreft row for the network the phone is on now stays - the parent's own step, how a mesh node gets confirmed.
The kid's phone shows nothing.

| Where | nb | en |
|---|---|---|
| Section header | Wi-Fi | Wi-Fi |
| Current network's button | Bekreft | Confirm |
| Remove (summary and button) | Fjern | Remove |
| Remove's question | Fjerne {name}? | Remove {name}? |
| (i) on the group | Et bekreftet nettverk gjør Wi-Fi-posisjonen sikker i sonen, uten GPS. Bare ruteren telefonen var på, teller. Ikke bekreft en ruter som flyttes. | A confirmed network makes the Wi-Fi position certain in the zone, without GPS. Only the router the phone was on counts. Don't confirm a router that moves. |
| Orange line: a confirmation blocked | Sett et annet sted | Seen elsewhere |
| Its (i) | Telefonen var på {name} langt utenfor sonen, så det teller ikke lenger. Fjern det, eller bekreft det igjen når telefonen er her. | The phone was on {name} far outside the zone, so it no longer counts. Remove it, or confirm it again when the phone is here. |
| Flash, D4's refusal | Telefonen er på et annet nettverk nå | The phone is on another network now |
| Flash, the 17th row | Maks 16 rutere | At most 16 routers |
| Flash, a confirm or remove | the existing "Lagret" | the existing "Saved" |
| Security log labels | Wi-Fi bekreftet / Wi-Fi fjernet / Wi-Fi sett et annet sted | Wi-Fi confirmed / Wi-Fi removed / Wi-Fi seen elsewhere |
| `zone_changed` detail | Wi-Fi fjernet | Wi-Fi removed |

A confirmed name is a choice: a plain row, no state word. A blocked row stays listed with the orange line under the
group.

## 5. Tests (lean)

**Launcher** (pure; `PlacesTest`'s fixture `wifi_places.json` gains `zone_links` rows, and a `ZoneNetworksTest`):

- `zoneLink`: CONFIRMED by the same BSSID, by another BSSID of the same five-octet prefix, by the multi-link address;
  another prefix, another name, struck - none; open, OWE, enterprise - never CONFIRMED; metered or unreadable - none;
  network id -1 - CONFIRMED still, LEARNED not; LEARNED only with the `LINK` row linked and a matching `LINK_AP` row.
- `linkConfirms` (D8): CONFIRMED with the centre inside and the fix straddling - `link`; LEARNED straddling - none;
  the fix without the router outside - none; the router's guest name excluded with it; nothing placed after the
  exclusion - CONFIRMED yes, LEARNED no; the same with the router's rows moved, blocked or struck - none; one placed
  network and no fix - none; a cell veto - none. The owner's case end to end: `MOVED`, `TOGETHER`, an Apple fix
  inside, a confirmation, GPS silent - `attemptKeep` keeps the Wi-Fi fix with `LINK`, `wifiFresh` true. The travel
  router: a confirmation, another place's placed neighbours - none.
- `teachLinks`: an inside teach adds the router (both keys), counted or not; a connected router at 200 m and a heard
  one at its band's reach become pending; a second teaching fix 60 s-2 h later strikes (a confirmation's id; a
  `LINK_AP` row blocked 60 days, the `LINK` counters kept); one fix, or a second after 2 h, never; a same-name router
  of another prefix heard outside - nothing; `strikes = false` never strikes.
- `wifiOccasion`: each of D6's conditions failing alone - no occasion; all holding - one.
- `zoneWifiReport` (the report's pure half): a `ZoneConn` with a quoted name and zones on - reported; each failing -
  not; the struck list kept through a Wi-Fi places clear, dropped when the policy drops its id.
- The table: a `LINK_AP` row round-trips; `lastSeenMin` moves at most daily on use.
- Contract: `ZoneNetworksContractTest`, `ZoneReportTest`, `PolicyResponseCompatTest` (`networks`; a missing key none).

**Server** (`src/tests/zone_networks.rs`, `zones::tests`):

- `sanitize_zone_watch` over the contract's rows; a bad `wifi` dropped alone.
- Confirm (D4): the newest report with `wifi` is used past a newer one without it; a newer one with another BSSID -
  refused; a multi-link match accepted; over 1 h - refused; the name and multi-link address stored from the report;
  twice - one row; a blocked BSSID - a new id; the 17th (blocked counted) - refused; another phone's zone - 404.
  Remove: every row of the name. Both land on `#zone-wifi` (`shell::every_form_on_every_page_lands_on_its_own_group`).
- The page: the Confirm row hidden for a router already confirmed by prefix or multi-link address, and when the
  newest fix of the last hour is surely outside; "Sett et annet sted" for a blocked row, the only orange line.
- The strike intake: `blocked_at` once, one log line, the policy no longer serves it; another phone's id ignored.
- A move over the radius clears, one under keeps, a radius change keeps; the `zone_changed` detail.
- `policy_json_keys_snapshot`: `networks` always present; blocked rows and the non-Regularly modes serve none.
- No security line carries a name or a BSSID.

## 6. Emulator rows (a new section after §6bu, "Confirmed zone networks (design 138)"; Android 14 and 16)

The setup of §6bn: the debug launcher, Regularly, a zone Home (150 m), Google's location accuracy off, the made-up
scan and connection through `PlacesDebugReceiver`, the satellite feed for teaching, a second local day by the time
zone. The receiver's `connect` gains an optional sixth field, the multi-link address. Names `Net-A`/`Net-B`;
documentation addresses `00:00:5e:00:53:xx` for the home router, other prefixes for the neighbours and a mesh node,
as §6bn's setup notes say. Home's scan: the router (`Net-A` and its guest name on the same prefix) and three placed
neighbours.

| # | Step | Expect |
|---|---|---|
| a | Nothing learned (`--ez clear true`), GPS silent, home's scan, `connect "00:00:5e:00:53:01,Net-A,psk,7,5180"`; a sync | the report's `zone_watch.wifi` has `Net-A` and the BSSID; the zone's page shows `Net-A` with Bekreft; with `open` - no row |
| b | Tap Bekreft; Locate | the policy's zone has `networks`; `wifi fix check=link step=USE`; the report's fix `check: "link"`; Locate no "Wi-Fi estimate"; "At Home" on the row only when the fix is surely inside (the server's side rule, D15) |
| c | The finding's path: `adb install -r` the same debug APK (a new process, `MOVED`), then the heartbeat's own `FIX` | `attempt path=TOGETHER`, `check=link`, `Location heartbeat: FIX result=fresh`; no back-off |
| d | `connect` `00:00:5e:00:53:02` (the same router's other band); then a mesh node of another prefix, `Net-A` | the first `check=link` at once; the second `check=none` (or the single-router fix's `link` when nothing else stands), the page's Bekreft row, no orange line; Bekreft - `check=link` |
| e | `connect` with the confirmed router's multi-link address on another link | `check=link` at once |
| f | The travel router: `Net-A` (a) connected, another place's scan (its neighbours taught 3 km away) | `check=none`; with only the router heard (its two names, nothing else placed) - `check=link` (the exception) |
| g | Then the satellite feed 3 km away (8 satellites) twice, 90 s apart, `Net-A` connected | after the second: `zone link struck by=confirmed`; the report's `wifi_struck`; the page "Sett et annet sted"; the security log "Wi-Fi seen elsewhere" (the zone's name only); the policy drops the row; one feed fix alone strikes nothing |
| h | B: no confirmation, GPS silent, home's scan and `Net-B` psk; Locate 3 times 30 min apart over 2 local days | the link forms on the third occasion (`link taught by=wifi occasions=3 days=2`); the next fix `check=link`; a scan of only `Net-B` and its guest name - no occasion |
| i | Move the zone 200 m (radius 150); then confirm again and move it 50 m | the first clears the rows ("Wi-Fi removed"); the second keeps them |

The cell veto is a unit row: the emulator's cell has no placed row unless §6bk's fake Apple cell answer is loaded - if
it is, place it 5 km away and expect (b) to give `check=none`.

**Device row** (the Jelly Star, Android 16, the owner's check): at home, screen off, an hour after the install - the
report's `wifi` present (`connection read by callback yes netid=known|none` when the first read was redacted), the
scan fresh (`scan answer n/m ask=answered`), the parent's Bekreft, then `FIX result=fresh` at the next heartbeat.

## 7. Size: what keeps the build small

The largest new parts are the exclusion fix (one more `learnedFix` call over a filtered view, D8), the two-fix strike
(a pending map in memory, D7) and the server's page logic (D4, §4). Already cut: the settled-AP growth (H1) and the
report's `links` (L7), and in revision 3 the "Ukjent ruter" line (the Bekreft row still offers a mesh node).

## 8. Docs to update with the build

`docs/notes/launcher/wifi-places.md` (a section for 138; the zone-link paragraphs of D12 and 131 point to it),
`launcher/CLAUDE.md`'s index line, `docs/notes/server/zones.md` (the table, the routes, the intake, the policy key),
`docs/notes/server/pwa.md` ("Locate: zones": the Wi-Fi group), `server/CLAUDE.md` (the data model's 0180 and the next
free number, the policy's and the report's fields), `contracts/README.md`.

## 9. Android 14-17

All at or under minSdk 34, no SDK gate: `WifiManager.getConnectionInfo()` (API 1, deprecated 31, served on 14-17 with
fine and background location and Location on), `ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`
(31), `NetworkCapabilities.getTransportInfo()` (29), `WifiInfo.getCurrentSecurityType()` (31),
`WifiInfo.getApMldMacAddress()` (33), `WifiInfo.getNetworkId()` (1; may be -1 through the callback, D1),
`TelephonyManager.getAllCellInfo`/`requestCellInfoUpdate` (already read, design 125b). Checked against 14 and 16 on
the emulator, 16 on the Jelly; 17 is a device row (design 134's note: if 17 drops the BSSID from
`getConnectionInfo()`, the callback is the read).

## 10. Open questions for the owner

None. Q1 is decided (§11).

## 11. Revision 2

- The owner, 2026-11-26: Q1 - "Fjern" ends only the parent's confirmation (D2). The travel router - the exclusion at
  every use and its CONFIRMED-only exception (D8, §3). The cell veto (D9).
- Design QA: H1 (D1's match by prefix or multi-link address; the settled growth cut, D5; 16 per zone), M1 (centre
  for CONFIRMED, D8), M2 (band reach and two fixes, D7), M3 (the exclusion with the device, D8; B for neighbours, D6),
  M4 (CONFIRMED without the network id, D1; the device row's lines, §6), L1 (`connectedNow` in the passive teach, D7),
  L2 (the struck list outlives clears, D7), L3 and L4 (D4), L5 and L6 (D5), L7 (`links` cut, D3, §4), L8 (D2), L9
  (D3), §3's heading, the migration note (D12).

## 12. Revision 3 (the code QA of 13cb6ecee and the owner's "A", 2026-11-26)

- **The single-router fix** (D15, the owner's "A", and the answer to QA M3): a detached house hearing only its
  confirmed router gets a `link` fix at the router's own position, the accuracy by the level, every veto applied; it
  never teaches or strikes.
- **M1**: the neighbours' fix without the router is a centre by the complete-link cluster, without `learnedFix`'s
  majority rule and its 200 m cap (`learnedFix(.., centreOnly = true)`).
- **M2**: a router row matches by its device keys alone; the `LINK` row binds the network (D5).
- **L1**: the zone page's "newest fix surely outside" counts only a fix of the last hour. **L2**: the struck list is
  read, filtered and written under one lock. **L3**: the struck ids come from the strikes, outside the table's save.
  **L4**: a GPS teach surely inside a zone with the router connected or heard drops its pending strike. **L5**: a zero
  or redacted multi-link address is none (the report keeps the rest). **L6**: the Wi-Fi teach runs only from an
  attempt that was conclusive and not withheld. **L7**: an unquoted (hex) name is never reported or confirmed.
  **L8**: `linkConfirms`/`linkRenews` are gone (their tests on `linkCheck`/`zoneRenews`); an enterprise learned link
  never renews a still vouch - there is no fix to hold its AP then (conservative). **L9**: removing a row that is gone
  lands on the group. **L10**: `docs/notes/server/pwa.md`, "Locate: zones".
- **The location review's must-changes** (folded into D15 and §4): the single-router fix only for a confirmation and
  only while the last reported fix's centre is inside that zone (the continuity gate); its accuracy `sqrt(pos^2 +
  level^2)` with the level from the answering scan alone (else the -85 dBm bound); "nothing contradicts" judged after
  the Apple ask, with the neighbours' centre outside, two placed networks outside the zone, a conclusive scan of three
  unplaced networks and an impossible jump as vetoes beside the rest; "Ukjent ruter" cut; a test that a router fix on
  an inconclusive scan after a restart is fresh and never withheld; §6bw's row (b) expects "At Home" only for a fix
  surely inside (the server's badge is `zones::side`, unchanged this round).
- **Code QA round 2** (of da96e0fd2): **M1** the GPS branch of `LEARNED_FIRST` keeps as TOGETHER; **M2** the gate's
  witness, its accuracy and the 10-min bound across a boot; **M3** the router's position within its zone; **L1** an
  unchecked fix in hand tries the router fix, its centre outside vetoing; **L2** Apple only by the router's
  addresses; **L3** where D15 is promised; **L4** no print's vouch or outcome from a router fix; **L5** a stale
  server comment; **L6** behaviour tests of the gate, the level, the heard filter, Apple's place and the position
  outside; **L7** an explicit `conclusive` for the teach gate.
- **Verification QA** (of 5a2c30131): **H1** the gate's witness rules above (a router fix never closes its own gate;
  a coarse fix never decides unless surely outside); **M1** the off gap computed once at the boot in its own key, for
  the boot right before only; **L1** the candidates by elapsed time in this boot, the cached fix's own boot kept;
  **L2** the router fix never replaces a renewal of the still vouch, and own rows placing the router outside the zone
  by more than their range veto it (no fallback to Apple or the centre); **L3** tests for each.
