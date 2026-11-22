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
  logs its refusals as counts (`gps fixes=9 taught=false coarse=7 nosat=2`). `accEff = acc + speed x |dt|` (1.5 m/s unknown) <= 40 m; the 60
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
  local days. Unseen 180 days goes; above 4000 rows the least recently seen go. Cells (D10): `mcc|mnc|area|cid` of the
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
