# Launcher notes: Wi-Fi places, learned on the phone

## Wi-Fi places (design 125 step 1, `docs/design/125-wifi-places-on-the-phone.md`)

- **What it is**: with Google Location Accuracy off (the `network` provider off) the phone has no indoor fix. Vibb
  learns places itself: every good satellite fix teaches the Wi-Fi APs (and the serving cell) heard with it; later a
  scan matched against the table gives a fix computed on the phone, reported as `vibb_wifi` (`vibb_cell`) with a 68 %
  accuracy estimate. Nothing of the table leaves the phone; the server only gets the fix, as any fix (no contract
  change). Pure rules in `places/PlacesPlan.kt` (`PlacesTest` over `src/test/resources/wifi_places.json` - the
  launcher's alone - and a synthetic town), glue `places/WifiPlaces.kt`, the moving-SSID list
  `assets/places/moving_ssids.json`; log tag `WifiPlaces`. GPL, not the MIT directories.
- **Who teaches** (D1-D3): our own and passive `gps` fixes with `satellites` >= 5 in the extras (missing = no teach,
  the Jelly Star's check 1), accuracy <= 25 m, speed <= 3 m/s or unknown, not mock in a release build; never `fused`
  (until the Jelly Star shows it has no Wi-Fi path with GLA off), `network`, a balanced fix or our own `vibb_*`. Our own
  GPS runs as a **stream** (`requestLocationUpdates(gps, 1 s)`) and each scan result pairs with the stream fix nearest
  in elapsed time within a second (a teaching fix with no result that close takes one more scan); after its first fix
  the stream runs on up to 5 s (within its bound) for a fix that teaches, ended early by one over 3 m/s, and the best
  accepted fix is reported; a passive fix pairs with the system's cached results within +-30 s. Only results heard
  after the last motion fire teach; the leave-one-out count, the zone links' strikes and D15's contradiction read only
  the results paired with the best teacher (`pairedWith`); nothing paired - no count, no link, no write. Each GPS try
  logs its refusals as counts and the last 10 fixes' signal (`gps fixes=9 taught=false coarse=7 nosat=2 sig=5/25/22@8,...` - `satellites/maxCn0/meanCn0@accuracy` from the fix's extras, `WifiPlaces.signal`; a passive GPS fix that may teach logs `passive teach sig=`; 2026-11-23, data for a C/N0 floor for teaching). `accEff = acc + speed x |dt|` (1.5 m/s unknown) <= 40 m; the 60
  strongest APs. A passive fix is skipped while one of our own attempts teaches (it teaches its stream itself). The
  passive feed runs on **every Regularly phone** (`PassiveFixes.update(on = stillOn, keep = zonesOn)`); with GLA on it
  is the only teacher and pairs only with the system's own results - Vibb scans nothing then (H1).
- **Usable APs** (D4, `usableAp`): unicast, the locally administered bit clear, no `_nomap`/`_optout`, not a moving
  SSID (the asset's sections: Local NLP Backend's words/prefixes/suffixes/exact/contains/first words, microG's
  transport list and phone keywords, Vibb's `direct-`, and the **empty Norwegian slot** - Vy, Ruter, Skyss, AtB, Flytoget, the ferries,
  each added only once seen in the field), not an SSID equal to the BSSID's last three octets (a car). The connected
  AP only when unmetered (a metered one's results go - a hotspot), added when the scan lacks it; a `null` or
  `02:00:00:00:00:00` BSSID is no connection.
- **Rows** (D5-D8): key = HMAC-SHA256(install key, BSSID) cut to 12 bytes; device = HMAC of the MLO address or the first
  five octets (8 bytes); network = HMAC of the SSID (8 bytes) or the device for a hidden one. A weighted centroid
  (`q(rssi) x a(accEff)`, the weight capped at 20), the range floored by band (100 m at 2.4 GHz, 60 m at 5/6 GHz) and
  never capped; a sighting further than the band's reach (300 m / 150 m) beyond its error is a **move**; two moves
  within 60 days block the row for 60 days (then it restarts). Occasions count teaches >= 30 min apart, days distinct
  local days. Unseen 180 days goes; above 4000 rows the least recently seen go. Cells (D10): `radio|mcc|mnc|area|cid` (numbers; design 125b code QA L3) of the
  registered cell (one `requestCellInfoUpdate` when the cached one is over a minute old), range 500 m-10 km, a move at
  20 km, paired within 30 s whatever the teacher; no SIM = an empty list, an ordinary miss.
- **The lookup** (D9, `learnedFix`): results newer than the last motion fire (`MotionFlags.movedAtElapsedMs`) and
  <= 30 s old (>= 3 usable of them), else <= 2 min old - the fallback, reported but **never fresh** for `afterTry` or
  the fresh-fix stamp (`Ask.notFresh`); the stale guard: a newer result at the previous scan's very level is suspect (a
  quarter weight, never taught), and the scan is stale (the fallback) only when at least 5 repeat and they are 80 % of
  the results the previous scan heard too (code QA H1: a still phone repeats whole-number levels often). Trusted rows (not blocked, two
  occasions) group into devices, devices into networks; **complete link** - the device with the most others within
  `rangeA + rangeB` seeds, a device joins only within that of every member; the cluster needs >= 2 networks and more
  than half of the trusted networks heard. Position: devices weighted by `q(best rssi) x min(occasions, 5)/5`, the
  connected network x 2, **each network's weight shared by its devices** (a mesh is one witness - added in the build:
  without it a moved mesh's three nodes outvoted a corner of the town). Accuracy:
  `sqrt(spread^2 + (RANGE_SHARE x rangeMed)^2)`, x 1.5 with two networks, x 1.3 under four median occasions, the
  weak-signal floor of the strongest member, x 2 with two stronger unknown networks, at least 30 m, none over 200 m.
  `RANGE_SHARE` stays the note's **0.5**. The synthetic town (24 houses and a school, walls of 7-22 dB, 6 dB shadowing,
  learned from street walks): taught from every street a median accuracy of 106 m against a median error of 15 m,
  every fix within; taught from one street only 70 m against 36 m, 79 % within (0.25 held 54 % there). The binding
  rows: the neighbour 120 m from home never surely inside home's 150 m zone, and points 50-70 m from home, in a house
  without a router of its own, that hear home, never inside a zone just smaller than their distance. The leave-one-out
  counts on the Jelly Star are the real check. The time is the oldest used
  result's. The lookup only touches `lastSeen`. A learned serving cell more than its range + 2 km away vetoes the fix.
- **A cell fix is a last resort** (design 125b step 3a, C9; `cellReport`, `PlacesTest`'s `cell_report` rows in
  `wifi_places.json`): `cellFix` is **never fresh** (`Ask.notFresh` - the next beat tries GPS again; it never stamps
  the fresh fix, never renews "Hasn't moved since", never vouches - not in `VOUCHING_PROVIDERS` -, never teaches).
  While `motion` is `STILL` it is never reported: further than both accuracies + 2 km from the vouched fix it counts as
  a **cell contradiction** (`DayCounts.cellContradictions`, `cell_contradictions=` in the counts line), else it is
  withheld. Moved or not, the last fresh fix (`Ask.lastFresh`) under 4 h old (`STILL_SKIP_MAX_MS`, by elapsed realtime - its elapsed time when of this boot, an unknown age holding nothing back; code QA L4) and inside the cell
  fix's circle (`d <= accCell + accFine`) keeps it back too (the owner's Q6): the cached fine fix is reported with its
  age. **The parent's `locate`** (`AttemptPlan.LOCATE`) reports it whatever the rest says (C9.5, the owner's Q10 on
  2026-11-24: yes). Logged `cell fix acc=... REPORT|WITHHOLD|CONTRADICTS`.
- **Stands alone** (H2): after a motion fire (`motion` not `STILL`) a fix from fewer than three networks never stands
  alone - GPS runs in the same attempt; one network never gives a fix.
- **The attempt** (D13, D14; `WifiPlaces.attempt`, from `LocateCommands.freshOrLastKnown` when
  `places != null && WifiPlaces.networkOff(lm)`, inside `currentLocation`'s Location window; the plan is the pure
  `attemptPlan`): the heartbeat's `FIX`, a
  crossing's first fix and the low-battery fix (`Path.HEARTBEAT`) after a move (`MOVED`, `NO_SENSOR`) or with the daily
  refresh due (24 h without a GPS try of this design, `last_gps_try_ms`) scan and run GPS 15 s together and keep the
  better (GPS at equal or better accuracy; the lookup runs on the first scan beside GPS); otherwise the learned fix if it stands alone, else the zone link's renewal,
  else GPS. A crossing's confirm (`Path.CONFIRM`) GPS first, then the learned fix; `locate` (`Path.LOCATE`) GPS for
  60 s, 20 s when a learned fix of >= 3 networks within 60 m is in hand, the better kept - with GLA off the stream is
  `gps` directly (fused is GNSS-only then, and only `gps` teaches). Then a cell fix, then today's last-known fallback.
  Every fix through the mock rule. A scan: the cached results when the fresh rule holds, else one `startScan` and
  `registerScanResultsCallback` for up to 8 s; refused = the cache, counted by reason (`scan refusals permission=0
  throttled=2 unanswered=1 wifioff=0`). With the network provider on none of this runs:
  design 124 D9's balanced path, unchanged. A Keystore failure skips the table: GPS as before.
- **The teaching fix** (D11): a motion fire arms an exact while-idle alarm 2 min on (`WifiPlacesTeachReceiver`, request
  code 13; a later fire moves it, so it runs at the stop) - only on a Regularly phone with Location on and the network
  provider off (`teachAllowed`, checked at the fire and at the alarm); it is skipped while an attempt of ours runs, and
  at most 6 that taught count a local day (CE prefs `wifi_places` `teach_budget_v1`): a scan and the GPS stream until a
  teaching fix, 15 s or a fix over 3 m/s, a 45 s wake lock; no `goAsync` - the anchor keeps the process, the wake lock
  the CPU. On a zones phone the fix goes to the kept slot; it is no heartbeat try.
- **The zone link** (D12, Q5 = b): per policy zone (its key is the HMAC of id, centre and radius - a change drops its
  links) a network connected (unmetered) at >= 3 teaching occasions surely inside on >= 2 local days is linked; heard
  at a teach surely 200 m outside, revoked; twice within 60 days, blocked 60 days. **It only renews a still vouch**: the
  heartbeat's 4-h cap try (`FIX` while `motion` is `STILL`, zones on) re-sends the vouched fix with `still: true` when
  the vouched fix is surely inside a zone linked to the connected network and the daily refresh isn't due
  (`Ask.renewed`, `ZoneWatch.stampVouchRenewed`; the still skip counts its 4 h from the later of the last fresh fix and
  the renewal, `stillFrom`). It never establishes a position and yields no fix of its own.
- **Design 124's rules** (D15): a fresh learned fix is fresh for `afterTry`; D7 compares only a `gps`/`fused` vouch and
  a learned fix is never its new side (`VOUCHING_PROVIDERS`); a GPS fix more than 500 m from a learned fix of >= 3
  networks within 10 min is a **learned contradiction**, counted. So during a still night on learned fixes or zone-link
  renewals D7 never tests the sensor - up to the 24-h refresh, whose GPS fix is again a satellite vouch. The leave-one-out counts (D16): before a GPS fix
  teaches, the table as it was answers the same scan, and whether the GPS fix lies inside that accuracy is counted per
  class (3+, 2, zone) for 30 days (CE prefs `wifi_places` `loo_v1`, `DayCounts`), logged
  `WifiPlaces: loo 3+=41/52 2=9/14 zone=3/3 contradictions=0`.
- **Privacy and the store** (D18, D19): one file `noBackupFilesDir/wifi_places_v1` (credential-encrypted, `AtomicFile`),
  sealed whole with `Secrets.box` (the secrets key, AAD `wifi_places_v1`); the format `VWP`, version 1, the count and
  71-byte rows (positions as 1e-7 degree ints, times in minutes); another version is dropped. The 32-byte HMAC key is
  sealed with the same `Secrets.box` (AAD `wifi_places/hmac_key_v1`) in CE prefs `wifi_places` `hmac_key_v1`, the table
  and keys cached in memory for the process. Written only when its contents changed; `lastSeen` moves at most once a
  day.
  **Cleared** (`WifiPlaces.clear`: the file, the prefs, the memory - no Keystore call; a clear generation drops a save
  from an attempt that started before it): location Off (`LocateCommands.clearCachedFix`, `off`), `ZoneWatch.release`
  (unmanaged, and through `forget` a new enrollment, `unenrol`), a broken Keystore key (`keybroken`), a key or table a
  healthy key can't open (`AEADBadTagException` or a broken encoding: `unreadable`); a transient Keystore error skips
  that lookup or teach. Logs carry counts and accuracies only (`PlacesTest` checks every `Log`
  call in `places/`). "Only when asked" keeps the table: it learns from the parent's `locate`.
- **Android 14-17** (D17, §5): `NEARBY_WIFI_DEVICES` lost `neverForLocation` (the scans are for location now) and is
  self-granted in `currentLocation` beside the location permissions; Quick Controls' Wi-Fi picker grants fine location
  too (its scan reads need it now; it already switches Location on). `startScan` is deprecated but works on 14-17 and is
  throttled (foreground-service importance: 4 per 2 min); `registerScanResultsCallback`, `wifiSsid`,
  `getApMldMacAddress`, `requestCellInfoUpdate` and `CellInfo.getTimestampMillis` are all under minSdk 34. The
  connected BSSID comes from the deprecated `WifiManager.getConnectionInfo()` (inside the Location window) and its
  metering from the active network's capabilities (our VPN's merge the Wi-Fi's); if Android 17 drops the BSSID there, a
  `NetworkCallback` with `FLAG_INCLUDE_LOCATION_INFO` is the way (L3). Location -> Wi-Fi scanning must stay on for
  scans with Wi-Fi off (`docs/setup/android-settings.md`).
- Not device-tested: design 125 §8's Jelly Star list (`docs/testing/new-phone-model.md` §2) and the emulator wiring
  run (14 and 17).

## The Apple lookup (design 125 step 2, §4a)

- **What it is**: with the parent's switch on (the policy's `wifi_lookup: {on}`, `wifiLookupOn`), a learned miss - no
  fix that stands alone - asks the server where the heard Wi-Fi networks are (`POST /api/devices/wifi-lookup`), the
  server asks Apple through its proxy, and the phone stores the answer as **Apple rows** (kind 4 in the sealed table)
  and looks up again. Pure rules in `places/ApplePlan.kt` (`PlacesTest`, `WifiLookupContractTest` over the shared
  `wifi_lookup/contract.json`); glue in `WifiPlaces` (`askApple`, `postLookup`, `policyAccepted`).
- **When** (A3): inside the attempt - so only with the network provider off and Location on -, with the switch on, no
  wait or 409 in force and a network with internet (offline: nothing asked, nothing changed); `learned() ?: appleFix()`
  runs beside GPS in every plan (in `LEARNED_FIRST` only after the own lookup and the zone link's renewal gave nothing).
  Never from the teaching fix or a passive fix, never periodic. The BSSIDs: the scan view's usable results, strongest
  first, at most 40, none with a trusted own row, a live Apple row, a negative or an Apple tombstone (`appleAsk`). The call has
  15 s.
- **The answers** (A4, `appleStep`): 200 the rows and the negatives stored, and its `retry_after_s` kept as the wait
  when the server couldn't ask Apple for the rest (code QA M1); 409 no ask until a fresh policy says on (`apple_off`,
  cleared by `policyAccepted`; a sync is asked for at once); 503 no ask for `retry_after_s` (at most a day; a wait past a day
  is a clock set back); anything else, a transport failure or the timeout 15 min (`apple_wait_until_ms`). CE prefs
  `wifi_places`, cleared with the table.
- **Apple rows** (A9, `storeApple`): keyed by the same HMAC as the own rows, Apple's position and accuracy (`rangeM`),
  and for kind 4 `lastTeachMin` holds the row's end: the start of the UTC month `fresh_months_left` on (the month the
  server asks Apple again, `monthStartMin`), a stale row (0 left) a day (`APPLE_STALE_GRACE_MIN`); at most 3000 (the
  least recently seen out); a BSSID that isn't usable, a position out of range or a month count past the contract's
  `fresh_months` is left out. **Negatives** (code QA M2): an `unknown` BSSID is a positionless kind-4 row blocked until
  the start of the next UTC month (the server's negative), so `appleAsk` leaves it out until then. In the lookup
  (`chooseRow`) an Apple row is a trusted row of 3 occasions, weight 1, its range at least the band's floor; an untrusted own row
  doesn't block it; a trusted own row and an agreeing Apple row - the smaller range gives the position (a tie: own);
  disagreeing - the own row, and `reconcileApple` makes the Apple row a 90-day **tombstone**, as does an own teach that
  puts the BSSID beyond its band reach (`appleTaught`, in every teach). A fix whose cluster uses any Apple position is
  `vibb_apple` (`PlaceSource` `APPLE` or `MIXED`). The switch off drops every Apple row (`dropApple`) on every off
  policy - a no-op without any - and the waits are cleared only once the table is written (`save` returns whether
  the disk holds the change; the table in memory follows only a written one); the clears drop them with the table.
- **Still means still** (H2, `appleStill`, `appleStillMotion`): while `motion` is `STILL` (not `STILL_SINCE_TRY` since
  2026-11-22: moved since the vouch with only a failed try after it, the vouch is the old place), an Apple-sourced fix within
  `accOld + accNew` of the last fresh fix (`Ask.stillVouch`) re-sends that fix with `still: true` (`Ask.renewed`, a new
  4-h step; the sync logs "renewed by an Apple fix"); outside it is not reported and counts as an **Apple
  contradiction**; with no vouch it is withheld (`StillVerdict.WITHHOLD`: neither fresh nor reported).
- **Counts by source** (D16): the leave-one-out count runs on the results paired with the best teacher (code QA M1),
  per class and per source with the summed error, logged
  `loo 3+=.. 2=.. zone=.. own=38/47 apple=7/11 mixed=5/8 err own=12 apple=40 mixed=20 contradictions=0 apple_contradictions=0`.
- **The synthetic town** with Apple's rows (positions off by ~15 m, accuracies 20-80 m): Apple alone 87 of 87 indoor
  fixes within their accuracy (median error 16 m, accuracy 85 m), Apple and one street 91 of 91; the neighbours never
  inside home's zone.
- Logs carry counts only (`apple asked=12 status=200 got=9 unknown=2`, `apple contradiction`) - no BSSID ever leaves the phone
  but in the ask's body.

## Apple's cell lookup (design 125b, `docs/design/125b-apple-cell-lookup.md`)

- **What it is**: the cells the phone hears and has no position for are asked of the server too, in a request of their
  own (`{"cells": [...]}`, the same route), beside the Wi-Fi ask on a learned miss - and **alone when there is no scan
  view** (Wi-Fi and scanning off, fewer than three fresh APs; QA H1). The answers are kept as **Apple cell rows** (kind
  5, `RowKind.APPLE_CELL`, id `c/<key>`, keyed by the cell key `HMAC("cell|radio|mcc|mnc|area|cid")` with MCC and MNC as
  numbers - own cell rows too, since code QA L3), and the cell
  fix at the attempt's end reads the table again, so they give `vibb_apple_cell` in the same attempt. Pure rules in
  `places/CellPlan.kt` (`CellPlanTest`, `WifiLookupContractTest` over the shared contract's cell rows), glue in
  `WifiPlaces` (`readCells`, `askAppleCells`, `cellOnly`). GrapheneOS's cell logic (MIT, `NOTICE.md`) wherever the
  zones and the server don't need otherwise.
- **The read** (C1, C2; `readCells`, `cellReads`): `getAllCellInfo()` (every slot's cached cells); when the newest
  registered one is over a minute old, one `requestCellInfoUpdate` per active subscription
  (`MobileNetworkRuntime.activeSubscriptionIds`, the slot -> subscription mapping the roaming readings use), up to 3 s,
  the answers first. Every cell with a full identity (registered, secondary, neighbours that carry one; an NSA NR leg
  without NCI or TAC skipped), its wire form within the server's ranges (`parseCellWire`, the contract's `cell_forms`),
  none older than a minute before the attempt's start; registered first, then by signal (unavailable weakest); one per
  canonical form (a cell on both slots, `01`/`001`); at most 12. `Snapshot.cells` is the list (step 1's single cell
  went). Logged `cells read=4 usable=2 registered=1 nr=0 stale=0` - counts only. All APIs within minSdk 34
  (`CellInfo.getCellSignalStrength`, `getTimestampMillis`, `requestCellInfoUpdate`); Location off reads an empty list.
- **The ask** (C4, `cellAsk`): with the switch on, no 409, a network up and the **cell wait** over
  (`apple_cell_wait_until_ms`, `KEY_APPLE_CELL_WAIT` - set only by a cells answer: a 503 or 429 with `retry_after_s`
  that long, at most a day, anything else 15 min; a 409 is the one switch's `apple_off`). The Wi-Fi wait
  (`apple_wait_until_ms`) stops only the Wi-Fi ask, the cell wait only the cell ask. The cells asked: none when 8 read
  cells already have a position (GrapheneOS's threshold), else those with no trusted own row (3 occasions), no live
  Apple cell row, no negative or tombstone, at most 12; none while still but for a Locate (`cellAskAllowed`, code QA
  L5). Started apart in `WifiPlaces`' own scope (`askBeside`, code QA M3), timeout `APPLE_CELL_TIMEOUT_MS` (20 s: the
  server answers cells at a fixed 14 s through its proxy): the Wi-Fi ask, the learned fix and Locate's short stop never
  wait for it; the attempt awaits it only before the cell fix when nothing finer was kept, else it runs on and stores
  its rows. Logged `apple cells asked=2 status=200 got=2 unknown=0`.
- **Apple cell rows** (C7, `storeAppleCells`): Apple's position, `rangeM = max(500 m, acc_m)`, live a fixed 90 days
  from receipt (`lastTeachMin`; the answer carries no age), a negative 30 days; at most 500. Own against Apple per
  cell (`chooseCellRow`): an untrusted own row doesn't block Apple's; agreeing - the smaller range (a tie: own);
  disagreeing - own, and the Apple row a 90-day tombstone (`reconcileAppleCells`), as after an own teach that moves the
  cell 20 km (`appleCellTaught`). The switch off and every clear drop them with the Apple rows (`dropApple`).
- **The cell fix** (C8, `cellFix`): every read cell with a chosen row, registered or not. Three or more: one further
  than its range from the per-axis median is dropped (GrapheneOS's reference); two must lie within both ranges of
  each other; the position weighted by `1/r^2`; the accuracy `max(smallest used range, weighted RMS distance)`, at
  least 500 m, none over 5 km; the oldest cell's time; `vibb_apple_cell` when any position is Apple's. Reported by
  `cellReport` as step 3a set it (never fresh, never while still, not over a recent fine fix inside its circle, a
  `locate` always). The veto (`cellVetoes`) reads a registered cell's own trusted or live Apple row.
- **Leave-one-out for cells** (C11): before a GPS fix teaches, the cell fix of the cells paired with it (within 30 s)
  against the table as it was, by source (own, apple, mixed), with the summed error, 30 days; logged
  `loo cell own=3/3 apple=12/14 mixed=1/1 err own=800 apple=600 mixed=700`.
- Not device-tested: the Jelly Star has no SIM (§9: the SIM check list); the emulator rows are
  `docs/testing/emulator.md` §6bk.

## A Wi-Fi fix carries what confirmed it (design 131, `docs/design/131-wifi-fix-check.md`)

A Wi-Fi fix that no satellite fix or linked network confirms never starts an arrival. Pure rules at the end of
`places/PlacesPlan.kt` (`PlacesTest`: the `checks` rows in `wifi_places.json`, the link, the bands, the town far away);
glue in `WifiPlaces.attempt`/`judged`.

- **The check** (`FixCheck`, in the fix's extras `vibb_check`, `zones/ZoneWatch.kt`'s `fixCheck`/`withFixCheck`):
  `link` (`linkConfirms`) - the connection now (`linkConn`: by the network's name; unmetered; Android's network id
  known - -1 is no link, neither taught nor checked; PSK or SAE, or EAP with the connected AP among the fix's own rows;
  never open or OWE) holds a live D12 link under the binding it was learned with (`PlaceKeys.binding`: the security
  class - WPA2 and WPA3 personal are one (`LinkClass`), so a mesh node or a band that negotiates the other changes
  nothing - and `WifiInfo.getNetworkId()`, kept in the link row's device field, its first byte the class; another
  binding starts the row's occasions over, its strikes kept; D12's renewal needs the binding too), and the fix is surely
  inside that zone; else `gnss` (`gnssConfirms`) - a `gps` fix of at most 50 m within 2 min (the attempt's own, or the
  carried last one of this boot) within both accuracies. None: the Wi-Fi alone. The check rides on `QueuedFix.check`,
  the cached and kept fix, the report's `location`/`extra_locations`, the shutdown fix (`PowerLocation.check`) and every
  renewal (the vouch's own, never upgraded).
- **Ids after a boot** (code QA M1, `rebindAllowed`): Android may number its saved networks anew at a boot, so the first
  teach after one rebinds a link row of the same class, its occasions kept, when exactly one saved network has the
  connected name and the saved networks' count is the last boot's (CE prefs `wifi_places` `saved_networks_v1`,
  `boot:count`, from `getConfiguredNetworks`); otherwise a new id learns again. The emulator gate checks it (§6bn (h)).
  Both count saved networks by their distinct ids (`savedNetworkCounts`): Android lists a network once per security
  type it holds, so a WPA2 network with its WPA3 upgrade came twice and never rebound (the gate's run on Android 14,
  2026-11-25, which also saw the ids renumbered at a reboot: 7 became 1).
- **Mesh nodes, 6 GHz and locally administered BSSIDs**: on a WPA2/WPA3 personal network the link goes by the name, so
  they give `link` like any node of it; on an enterprise network the connected AP must be a row of the fix, and a
  locally administered BSSID never has one (`usableAp` drops it), so it never gives `link` there - by design.
- **The verdict** (`wifiVerdict`): a `link`-checked fix stands (while still too - fresh, it becomes the next vouch), and
  no jump refuses it; an impossible jump refuses any other (counted); a `gnss`-checked fix stands; an unchecked one while
  `STILL` with a vouch - the last fresh fix (`ZoneWatch.lastFreshFix`) when it is satellites of teaching quality
  (<= 25 m, >= 5 satellites, `QueuedFix.satellites`) or a checked fix (`stillVouchQualifies`: never a poor indoor
  satellite fix) - within both accuracies is used (an Apple fix renews the vouch, design 125 A9), outside refused
  (counted); an unchecked one runs GPS while `STILL` without a vouch or `STILL_SINCE_TRY`, or after a jump over the
  movement bound - reported unchecked only when GPS gives nothing, never withheld. After GPS the order is the pure
  `attemptKeep` (`runsGps`: LEARNED_FIRST runs none after a used or renewing fix; GPS_FIRST keeps GPS when it came):
  `keepBetter` - a teaching quality `gps` fix further than `max(500 m, 3 x both accuracies)` overrules (counted), else
  the usual choice; a kept Wi-Fi fix takes `gnss` when the GPS fix agrees.
- **The jump gate** (`jumpGate`, `LastGnss` in CE prefs `wifi_places` `last_gnss_v1`, from every own and passive `gps`
  fix, `noteGnss`): `v = (d - both accuracies) / dt` from the last satellite fix of at most 50 m, `dt` by elapsed time in
  its boot, else the decisions' clock (`TrustedClock`); a satellite fix newer than the Wi-Fi fix judges nothing; over
  60 m/s impossible, over 8 m/s (60 after a satellite fix over 8 m/s in the last 15 min) fast.
- **Bands and names** (`learnedFix`): a result heard on another band than its own row's (`otherBand`) is left out; the
  attempt disagreed only when two or more are, or the one would have carried the fix (`bandDisagrees`); one heard under
  another network name than its row's (`otherName`, neither hidden) is left out, not counted. Apple rows carry neither.
- **Locate** stops at 20 s only on a `link`-checked fix within 60 m (`locateStopsShort`).
- **The count** (`Disagreement`: one rise an attempt; a passive fix's learned contradiction its own):
  `zone_watch.wifi_disagreed` (CE prefs `zone_watch` `wifi_disagreed`, cleared with a new enrolment), logged
  `Wi-Fi and satellites disagreed (n in all)`.
- **Saved networks** (D11): `AppEnforcer.applySavedNetworksLock` sets `setConfiguredNetworksLockdownState` by its own
  plan flag (`EnforcementPlan.lockSavedNetworks`): while managed, or while the zones and Wi-Fi places watch (Regularly);
  lifted by the override and the pause. Android's screens can't change or forget a network Vibb saved. Vibb's own Wi-Fi
  page adds a new network (a new id) and never edits one; `DISALLOW_CONFIG_WIFI` is not set. The security comes from the
  scan (`wifiSecurityOf`, `joinSecurityType`): WPA2 (WPA3 beside it or not) as `SECURITY_TYPE_PSK`, Android keeps its
  WPA3 upgrade beside it; WPA3 alone as `SECURITY_TYPE_SAE`; open and OWE open; an enterprise, WEP or WAPI network
  shows "Not supported" / "Støttes ikke" and is never saved. Until the gate's run of 2026-11-25 both key-management bits were set, which Android read as WPA3 alone - a WPA2-only router
  never matched it. The lockdown is the global setting `wifi_device_owner_configs_lockdown` (1 while it holds).
- **The emulator**: the debug build's `PlacesDebugReceiver` (src/debug) hands the places a made-up scan and connection
  through `PlacesDebugHooks` (design 134 §7, below); rows in `docs/testing/emulator.md` §6bn.

## After a small move: inconclusive scans and the shadow fingerprint (design 134, `docs/design/134-still-place-fingerprint.md`)

Pure rules in `places/StillPlan.kt` (`StillPlanTest`, `StillPrintTest` with the note's binding rows 1-12), the store in
`places/StillPrints.kt`, the glue at the end of `WifiPlaces.attempt` (`shadow`, `afterFreshFix`) and in the sync. Log
tags `WifiPlaces`, `ZoneWatch`, `StillPrint`. No server or contract change.

- **Which results answer a scan** (D1, D2; `scanAnswer`): `scanNow` keeps its request's elapsed time and outcome
  (`cache`, `answered`, `refused` - throttled, no permission, failed -, `unanswered`, `wifioff`); a callback whose
  results are all older than the request isn't the answer - it waits on within the 8 s (code QA M2), and with only
  such callbacks it reads as `old`. A result answers when heard after the request (`ScanResult.timestamp`), on the
  cache path when at most 30 s older than the request, and after the last fire. Logged `scan answer n/m ask=answered`
  (`n` answering of `m` cached); a result over 60 s in the future means the time base isn't boottime - inconclusive,
  `timebase` (code QA L2).
  **Inconclusive** (says nothing about the place): refused; answered but every cached result older (`old`); not
  answered in 8 s and nothing new (`unanswered`); connected (a live BSSID) to an AP the answer lacks (`noconnected`,
  checked before `hear` adds the connected AP; a metered hotspot's BSSID too). Conclusive: a fresh answer of one AP or
  none (the cabin), and a refusal with Wi-Fi off (nothing to hear). The count of results never decides.
- **After a move** (the heartbeat's `FIX` while `MOVED`): an inconclusive first scan with no fresh fix takes **one more
  scan** 10 s after the first request when the table's trusted networks (>= 2) or the Apple lookup could decide - not
  after a throttled refusal (the throttle refuses it again within its window) (D3, `secondScanWorth`, logged `second
  scan conclusive|old|...`). Still inconclusive and no fresh fix: the attempt is **withheld** (D4) - nothing reported
  (no cell, unchecked or fallback fix, no last-known: `LocateCommands.freshOrLastKnown` returns nothing), and
  `MotionFlags.afterTry(.., withheld = true)` keeps the flags, so the next beat tries again (`MOVED`, no back-off).
  Three in a row (`zone_watch` `inconclusive_tries`, cleared by a fresh fix, a conclusive try, the fourth, and
  whenever the motion isn't `MOVED` - code QA L6); the
  fourth is an ordinary failed try. Logged `ZoneWatch: Location heartbeat: FIX result=inconclusive reason=old n=1/3`
  and `WifiPlaces: attempt path=TOGETHER result=withheld reason=old`. While `STILL` an inconclusive cap try is part
  A's cap fail (D5, `zones-and-power.md`); `locate`, a crossing's and the low-battery fix are unchanged.
- **The fingerprint** (D7, D8): at a genuinely fresh fix that becomes the vouch (`afterFreshFix`) - `gps` or a checked
  Wi-Fi fix, <= 50 m, not moving, not mock in a release build, its attempt's scan conclusive, a trusted sensor - the
  print of its **settled** devices heard within +-30 s: a device (design 125's device key) one of whose APs has an own
  row on >= 2 local days, never moved, not blocked (Apple rows never); the strongest 12 by level, their network key
  and AP count. No SSID, BSSID, cell or position. Logged `StillPrint: taken devices=4` or `not taken (provider|accuracy|moving|mock|scan|pair)`. It
  grows only at a renewal while still whose scan was conclusive and where the strict rule held (never a device
  dropped or a level changed). **Dropped** (logged `print dropped (<reason>)`, counted): `boot` and `expired` (72 h) at
  use, `gla` (the `network` provider on at an attempt), `rule` (the still rule off, `ZoneWatch.apply`), `distrust`
  (design 124 D7's distrust starting) and `nosensor`, `replaced` (a new vouch), `notfresh` (a reported fix that isn't
  the vouch); with every Wi-Fi places clear both files go (`off`, `unenrol`, ...).
- **The shadow verdict** (D6, D9, D10): at every heartbeat `FIX` after a move with a print, a conclusive scan records
  whether the strict rule *would* renew - `thin` (< 3 devices), `anchor` (the print's strongest device within 15 dB,
  and the strongest usable result now one of its two strongest), `agree` (2 devices of -70 dBm or stronger within
  15 dB), `similar` (their mean difference, an absent one 30 dB, <= 12 dB), `veto` (a Wi-Fi fix outside the vouch, an
  impossible jump, a cell fix past it + 2 km, a fresh fix outside it) - with `J` (weighted Jaccard, weight
  `max(0, dBm + 100)`) and the counts; an inconclusive one an `inconclusive` event. **It never acts**: no renewal, no
  `still`, no flag, no report, no `wifi_disagreed`. Logged `StillPrint: verdict would=yes j=0.81 diff=9.0
  devices=4/4 near=2/2 agree=2`.
- **A scan mismatch ends `STILL`** (revision 3, D15-D19, acts): while `STILL`, the cap try's or a retry's conclusive
  scan with no fresh fix that clearly isn't the print's place - none of its devices heard at all, and then `wifi`: at
  least 3 distinct devices heard, or `cell`: a thinner answer whose registered cell is placed away (`cellElsewhere`: a
  trusted own cell row or a live Apple cell row already in the table, further from the vouch than its range + the
  vouch's accuracy + 1 km; unknown counts for nothing, no lookup is started for it; code QA H1 and the owner's
  refinement: cells only back a Wi-Fi absence, by position - a still phone's serving cell flips) - with a print of >= 3
  devices sets
  `Ask.mismatch`. A renewal in the same attempt (a zone link, an Apple fix within the vouch) vetoes it (code QA M1):
  `STILL` stays, the event is recorded flagged `vetoed`. The sync then ends `STILL` (`MotionGate.scanMismatch`, `zones-and-power.md`),
  reports nothing and logs `ZoneWatch: Location heartbeat: STILL ended by a scan mismatch clause=wifi` and
  `StillPrint: mismatch clause=wifi devices=0/4`. Not a design 124 D7 contradiction.
- **The connected router as a still witness** (revision 4, D20-D25, acts; its code QA applied, design 134 §13a): while
  `STILL`, the cap try or a retry in `LEARNED_FIRST` with no learned fix judged renews the vouch beside the zone link
  (`judged == null && (renews || witness)`, no zone needed: the sync hands every Regularly phone's cap try its vouch,
  `renewable`) when the pure `stillWitness` says `RENEWS`: under (A)'s 24-h ceiling (`Ask.stillCeilingPassed`;
  this renewal never moves the ceiling's anchor, `ZoneWatch.ceilingRenewedMs` - code QA M1), the phone connected now
  to a readable, globally administered BSSID under design 131's binding (`linkAp`: `linkConn` - unmetered,
  saved-network id - and `linkSecurityHolds`, taken out of `linkConfirms`; open/OWE never), the **same binding and the
  same AP** (its key, or another link of the same MLO device - `WifiInfo.getApMldMacAddress()`; the other band or a
  mesh node is not, code QA M2) as the attempt read when the vouch's fix was taken (`VouchLink`, memory only, set in
  `afterFreshFix` by `vouchLinkOf` - none on the move or with an unknown level -, cleared with the table; a new process
  starts `MOVED`), its device **settled** (`deviceSettled`: a row trusted and on >= 2 days, none moved or blocked),
  and its level known (-126..0; `INVALID_RSSI` refuses, code QA L2) and within 15 dB of the vouch's. The levels are the
  connected BSSID's own entry in the attempt's answering scan when there, else `WifiInfo.getRssi()` - **stale while
  the screen is off** (code QA H1; the bound is then the AP's range, guarded by the sensor - design 134 D22, the
  owner's (a)/(b) in §13b). It renews only the still-ness (`Ask.renewedBy = CONNECTED`; the sync logs `ZoneWatch:
  Location heartbeat: STILL renewed by the connected AP`, `stampVouchRenewed(.., movesCeiling = false)`). Logged
  `WifiPlaces: connected witness renews|no reason=moved|novouch|ceiling|none|unreadable|local|binding|nolink|other|
  unsettled|nolevel|level|stronger level=scan|link screen=on|off dbm=N vouch=N diff=N` and at the vouch `vouch link
  read level=scan|link` or `none`; never a BSSID. A witness that renews or is more than 15 dB stronger than at the
  vouch (not weaker, code QA L1) also **vetoes a mismatch** (`mismatchVetoed`, D23, recorded `vetoed`). The read
  (D25): `getConnectionInfo()` (deprecated in 31, served on 14-17 with fine and background location and Location on);
  when it gives no BSSID while the active network carries Wi-Fi, the attempt's scans (`connectedNow`) register one
  `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` for Wi-Fi, up to 1 s (logged `connection read by callback yes|no`;
  its `unmetered` that Wi-Fi network's own), and that read also feeds D2's `noconnected`, the zone link and `hear()`
  (code QA L4); `getNetworkCapabilities()` alone always redacts. No SDK gate (all at or under minSdk 34).
  `StillWitnessTest` (rows 13-19).
- **Outcomes and the ring** (D11, D12): the next genuinely fresh fix of <= 100 m (`gps`, a checked Wi-Fi fix) judges
  the pending events, the distance from the vouch rounded to 10 m: a would-renew `confirmed` (within both accuracies +
  50 m) or `contradicted`; a would-not keeps its distance (`within` or not); a mismatch `confirmed` when the phone had
  moved past that, else `contradicted`; `noted` for an inconclusive event; `unjudged` after a fire or a boot between,
  24 h, or the print gone. Logged `StillPrint: outcome confirmed d=20 after=113 acc<=25`. The ring keeps the last 500
  events of 60 days (~37 bytes each) and the taken/dropped counts.
- **Storage and privacy** (P1-P5): `noBackupFilesDir/still_print_v1` and `still_print_ring_v1`, each an `AtomicFile`
  sealed whole with `Secrets.box` under its own AAD (the file's name); keys are the places table's HMACs; another
  format version is dropped; written only on a change. Never sent; logs and the dump carry counts, levels, `J`,
  rounded distances and times (`StillPlanTest` checks every `Log` call and the dump's lines).
- **Reading it** (D13): `adb shell dumpsys activity service
  me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService still-print` (debug: `me.vibb.launcher.debug/...`)
  - `CommandListenerService.dump`: the print in force, prints taken and dropped by reason, verdicts by class (would-not
  by reason), inconclusive tries by reason, mismatches by clause and outcome, then the ring, newest first. Without
  the argument one line (a bug report carries none of it). Works on a release build: the framework only asks for
  `DUMP`, which adb's shell holds. It only reads (code QA L5): a stale print is shown `(stale: boot)`, nothing is
  dropped or written; at most two Keystore opens on the main thread (adb-only). The ring is ~18.5 KB at 500 events.
  A drop deletes the print's file even when it can't be opened (code QA L3).
- **Debug hooks** (§7, src/debug only, `PlacesDebugHooks` with a no-op twin in src/release; `DebugHookAbsentTest`,
  `checkReleaseHasNoDebugHook`): `PlacesDebugReceiver`'s `--ei scan_age_s N` (the made-up results heard N s ago),
  `--ez refuse_scan true` (every `startScan` throttled until false or `clear`) and `--es motion sensor|fire|off` (a
  fake significant-motion trigger `MotionGate` arms and fires as the real one); `--es connect`'s optional sixth field
  is the made-up connection's level (revision 4; -45 dBm unless given). Gate: `docs/testing/emulator.md` §6bs.
- Not device-tested: the note's §9 on the Jelly Star (`docs/testing/new-phone-model.md` §2) and §6bs.
