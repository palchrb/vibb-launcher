# 125 - Wi-Fi places, learned on the phone

Status: **revision 3 - ready to build**, 2026-11-22. Asked by the user: "kunne egentlig tenkt meg å kunne beholde innendørs
posisjon vha google, men finnes det noen foss alternativer eller noe annet vi kan gjøre for å sikre at vi ikke er
identifiserbare for google?", then "Ja sett i gang et wifi selvlæringsdesign" and "vil den bare lære wifi eller også
cell towers?". Revision 2 applies the user's answers (§11: Q1 yes, but "vi må jo passe på at vi ikke nå legger opp til
noe som blir veldig unøyaktig bare for å spare 0.5% batteri btw. Men privacy er en god grunn da"; Q2 a self-learned
zone link; Q3 and Q4 in) and the design QA (H1-H2, M1-M5, L1-L10; §10); revision 3 the user's Q5 (the zone link only
renews a still vouch), a 60-day block and what the research into microG, Local NLP Backend, Déjà Vu and GrapheneOS's
NetworkLocation gave (§10). Launcher round; no server code, no contract
file changes. Read against `main` b56676686 with design 124 revision 2 beside it (its step 1 is in build; this note
builds on its D3 `MotionFlags`, D5 heartbeat and D9 provider choice).

## 0. Short answer

With Google Location Accuracy (GLA) off, Android's `network` provider is gone and indoor GPS rarely answers, so the
phone has no indoor fix. microG and Déjà Vu need a ROM without Play services (the Jelly Star has them) and BeaconDB
gives no export, so **Vibb learns places itself**: whenever its own satellite fix is good, it remembers the Wi-Fi
access points (APs) and the serving cell heard at that moment and where they must be; later, indoors, a scan matched
against the table gives a position computed on the phone. The table never leaves the phone; the server gets only the
fix it already gets, with the provider word `vibb_wifi` (or `vibb_cell`) and an honest 68 % accuracy.

**Accuracy before battery, privacy before accuracy** (the user's rule): the learned fix is used because it keeps Google
out, not to save GPS starts - so GPS still runs after every move, a teaching fix follows a move, and a learned fix
stands alone only when enough independent networks agree. It works only at places seen with GPS before; elsewhere
everything is as today. **Learning starts when GLA is switched off**: with GLA on, Vibb never scans and never uses the
table (GMS answers indoors anyway); only a free passive GPS listener teaches, which is little. Design 124's deferred
Wi-Fi witness (§8 there) is dropped: past its 4-h cap a known place gives a real fresh fix instead of a vouch.

## 1. What the phone has today (read in the code)

- Fixes: `LocateCommands.freshOrLastKnown` - balanced fused, then GPS (`preciseFix`), then `getLastKnownLocation`, then
  the cached fix. With GLA off the fused provider is GMS's but GNSS-only, `network` is disabled; design 124 D9 then
  goes to GPS directly. GNSS also loses its network-assisted seed, so indoor cold starts fail more often.
- Wi-Fi: the manifest has `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, fine/coarse/background location (self-granted in
  `currentLocation`) and `NEARBY_WIFI_DEVICES` **with `neverForLocation`** (the Quick Controls picker). Scans need
  system Location on whatever is held; `currentLocation` already switches Location on for a fix and back off unless
  `locationStaysOn` (P-L2). `PassiveFixes` (the passive provider, 60 s) runs only on zones phones.
- Keys at rest: `SealedPrefs`' `SecretBox` (AES-GCM in the AndroidKeyStore, `seal`/`open` with an AAD, `keyBroken`).
- The server stores any provider word of 1-16 `[a-z0-9_-]` (`models::location_provider`), shows it only in the Locate
  page's newest-fix title, and judges zones by `d + a <= r` (inside) / `d - a >= r` (outside) on the stored fixes.
- Cells: the Jelly Star without a SIM shows `mCellInfo=[]` on both slots (`dumpsys telephony.registry`, 2026-11-22).

## 2. Decisions - learning

1. **Who teaches.** Only our own satellite fixes, whatever path took them - the heartbeat, `locate`, a crossing's
   confirm, the low-battery fix, the teaching fix (D11) - and a **passive `gps` fix** from any app (D3). A fix teaches
   when its provider is `gps` with `satellites >= 5` in its extras (dropped as a condition if the Jelly Star doesn't
   set the extra - §8 check), not mock in a release build, accuracy <= **25 m**, speed unknown or <= **3 m/s**. `fused`
   teaches **only after** the Jelly Star shows GMS's fused has no Wi-Fi path with GLA off (§8 step 2); until then never.
   Never: a `vibb_wifi`/`vibb_cell` fix (no feedback), a balanced fix, a fix with the `network` provider on (Google's
   Wi-Fi database, not ours - no bootstrap from Google-derived fixes), a last-known or cached fix.
2. **Pairing a scan with the teacher.** While GPS runs for the teaching fix (D11), the first `FIX` after a move or a
   `locate`, the attempt keeps the **GPS stream** (`requestLocationUpdates(gps, 1 s)` for the attempt's length, each
   fix in a memory buffer) and pairs **each scan result with the stream fix nearest in elapsed time** (within ~1 s;
   Local NLP Backend's way). Every other teacher (a passive fix, a single `getCurrentLocation`) pairs results whose
   `timestamp` lies within **+-30 s** of its `elapsedRealtimeNanos`. When a teaching fix arrives and the newest scan is more than **15 s** old, one more scan is
   taken (wait <= 8 s) - not with GLA on (D13). The pairing error goes into the weight: `accEff = acc + speed x |dt|`
   (speed unknown: 1.5 m/s), and `accEff <= 40 m` or no teach. At most the **60** strongest usable APs per teach; the
   serving cell (D10) pairs the same way by `CellInfo.getTimestampMillis()`.
3. **The passive listener, every Regularly phone.** `PassiveFixes` registers on every Regularly phone (device owner,
   managed, mode `interval`), zones or not; a `gps` fix that passes D1 teaches - free, opportunistic (a maps app's
   navigation teaches a whole route). Zones phones keep today's kept-slot use on top. With GLA on it pairs only with
   the system's own cached results (Vibb scans nothing then, H1). Whether the passive request lights the location
   indicator is design 64 §9's open check.
4. **Which APs are usable** (pure `usableAp`, the same for teaching and lookup): a parseable unicast BSSID with the
   **locally administered bit clear** (Android/iOS hotspots, Wi-Fi Direct, most virtual BSSIDs); an SSID without
   **`_nomap`** (Google/Mozilla's opt-out) or **`_optout`** (Microsoft's), any case; not a **moving SSID** - phones
   and Wi-Fi Direct, cars (also an SSID equal to the BSSID's last three octets), coaches, trains (`nsb_interakti`
   among them), dashcams, in-flight - by words, prefixes, suffixes and exact names taken from Local NLP Backend's
   `RfEmitter.kt` (GPL-3.0) and microG's `MovingWifiHelper.kt` transport list (Apache-2.0), attributed in the file and
   `NOTICE.md`, kept as data in `launcher/app/src/main/assets/places/moving_ssids.json` with test rows; a marked slot
   for Norwegian names (Vy, Ruter, Skyss, AtB, Flytoget, the ferries) filled only after each is seen in the field. The
   SSID is read at that moment only, never stored. The connected network only when unmetered; hidden SSIDs are kept.
   The real guards against moving APs are D7 and D9.
5. **Per AP one row** (~64 bytes, D18's format): `key` = HMAC(install key, BSSID) cut to 12 bytes; `device` =
   HMAC(the MLO address from `ScanResult.getApMldMacAddress()` when set, else the first five octets) cut to 8 bytes -
   one router's bands and links are **one device** (a false merge of two same-vendor neighbours is harmless: they are
   still one network each, D9); `network` = HMAC(SSID) cut to 8 bytes, or the device key for a hidden SSID - a mesh's
   nodes and a router's bands are **one network**; `band` (2.4 / 5 / 6 GHz from `frequency`); `lat`, `lng`, `weight`,
   `rangeM`, `occasions` (teaches >= 30 min apart), `days` (distinct local days, capped), `lastTeach`, `lastSeen`,
   `moves`. No SSID, no BSSID, no signal history.
6. **The estimate per row** (Déjà Vu's idea, simplified): a weighted centroid of its teach positions, sighting weight
   `w = q(rssi) x a(accEff)`, `q = clamp((rssi + 100) / 40, 0.1, 1)`, `a = clamp(10 m / accEff, 0.25, 1)`;
   `pos = (pos x W + teach x w) / (W + w)`, `W = min(W + w, 20)` (the newest ~20 sightings dominate, so a slow drift
   follows). `rangeM` = max of (distance from the estimate + accEff), floored by band - **100 m at 2.4 GHz, 60 m at 5/6
   GHz** (what a phone hears through walls, even when every teach was at the door) - and never capped: a sighting that would
   push it past the band's reach is a move (D7). A device's
   position is the weight-weighted mean of its rows, a network's of its devices.
7. **Moves, per band.** A sighting whose `distance - accEff` from the row's estimate exceeds the band's reach -
   **300 m at 2.4 GHz, 150 m at 5/6 GHz** - is a move: the row restarts there (`occasions = 1`, `days = 1`) and
   `moves + 1`; **two moves within 60 days block it for 60 days** (a tombstone, never used or taught meanwhile; then it
   restarts - a bus or travel router re-blocks itself, a house move relearns). A network whose devices all moved
   together (a mesh taken to a new house) moves as one.
8. **Ageing and size.** A row unseen for **180 days** goes (`lastSeen` is also touched by a lookup that uses it); above
   **4000 rows** the least recently seen go first, tombstones included. ~260 KB at the cap.

## 3. Decisions - using it

9. **The lookup** (pure `learnedFix(scan, table, connected, motion, now)`):
   - **The scan**: results whose own `timestamp` is <= 30 s old (a fresh scan) or, as a fallback, <= 2 min old - and in
     both cases **newer than the last motion fire** (`MotionFlags` keeps the fire's elapsed time, M2); at least **3**
     usable results that fresh, else no lookup (a partial cached list, L8).
   - **Stale-scan guard** (Local NLP Backend's `onWiFisChanged`): a result whose level equals that BSSID's level in the
     previous scan is suspect - it never teaches and weighs a quarter; **more than 2** repeats and the scan is stale -
     no teaching, and it counts as the 2-min fallback. The previous levels are in memory only.
   - **The connected AP is always there** (microG's `WifiScannerSource`): the connected unmetered BSSID from `WifiInfo`
     is added when the results lack it; a `null` BSSID or `02:00:00:00:00:00` means not connected.
   - **Trusted rows**: not blocked, `occasions >= 2`. Rows group into devices, devices into **networks** - the witness
     unit. Consensus by **complete link** (Local NLP Backend's `locationCompatibleWithGroup`): the device with the most
     others within `rangeA + rangeB` seeds a cluster (tie: stronger signal), and a device joins only when it is within
     `rangeA + rangeB` of **every** member; the cluster must hold **>= 2 distinct networks and more than half** of the trusted networks seen, else no fix.
   - **Position**: the cluster's devices weighted by `q(best rssi) x min(occasions, 5) / 5`, the connected unmetered
     network x 2.
   - **Accuracy, a 68 % estimate, not a bound** (M1): `acc = sqrt(spread^2 + (0.5 x rangeMed)^2)`, `spread` the
     weighted RMS distance of the cluster's devices from the fix, `rangeMed` their weighted median range; then **x 1.5**
     with only 2 networks, **x 1.3** when the median `occasions` < 4; a **weak-signal floor** from the strongest
     cluster member - >= -60 dBm none, -60..-75 its band floor, -75..-85 1.5 x the band floor, weaker 150 m; **x 2**
     when two or more unknown usable networks are heard stronger than every cluster member (the phone is nearer
     somewhere else). Floor 30 m; over **200 m** no fix. The constants are tuned in the synthetic town (§8); the
     binding row: **a neighbour 120 m from home, hearing home's APs through walls, is never `d + a <= r` inside a
     150 m zone at home.**
   - **Stands alone or not** (H2): after a motion fire, a learned fix from **fewer than 3 networks** never stands alone
     - GPS runs in the same attempt (D14). **One network alone** (a lone router, a mesh) never gives a fix (no
     single-emitter fixes); only a zone-linked network extends a still vouch (D12).
   - Time: the **oldest** used result's `timestamp` on the wall clock (GrapheneOS's `LocationReportingTask`). A fix from the 2-min fallback is reported but is
     **not fresh** for `MotionFlags.afterTry` or the fresh-fix stamp (M2). The lookup changes nothing but `lastSeen`.
10. **Cells** (Q4: in step 1, a no-op without a SIM). The serving cell from `TelephonyManager.getAllCellInfo()`, or one
    `requestCellInfoUpdate` (API 29) when the cached `getTimestampMillis()` is > 60 s off: `CellIdentityLte` (MCC,
    MNC, TAC, CI), `CellIdentityNr` (MCC, MNC, TAC, NCI), `Wcdma`/`Gsm` (LAC, CID); neighbours usually lack an
    identity and are skipped. A row of kind `cell`: `key` = HMAC("mcc|mnc|area|cid"), D6's centroid with `rangeM`
    **500 m..10 km**, a move at **20 km**. Used only after GPS and Wi-Fi gave nothing (`vibb_cell`, `occasions >= 3`,
    accuracy its `rangeM`, over 5 km none) and as a **veto**: a learned serving cell whose estimate lies more than its
    range + 2 km from a Wi-Fi fix rejects that fix. An empty list (no SIM: the Jelly Star today) is an ordinary miss.
    Needs `ACCESS_FINE_LOCATION` and Location on, not `READ_PHONE_STATE`; with no SIM the default `TelephonyManager`
    reads slot 0 [check with a SIM: `adb shell dumpsys telephony.registry | grep -i -m5 cellinfo`].
11. **The teaching fix** (Q3, in): **2 min** after a motion fire (`CONFIRM_DELAY_MS`, the crossing confirm's exact
    while-idle alarm, its own request code), one GPS fix (15 s) with a scan, **at most 6 a local day** (CE prefs
    `wifi_places` `teach_budget_v1`), only on a Regularly phone with GLA off and Location on. It teaches; on a zones
    phone it also goes to the kept slot (design 64's `keepFix`), otherwise it is not reported (the trail stays at the
    parent's interval). It isn't a heartbeat try for 124's flags.
12. **The zone link** (Q2: no "hjemme" field, but learned). Per policy zone (by id; dropped when the zone's centre or
    radius changes or it goes), the phone learns which **networks** are present when it is surely inside: a network
    becomes **linked** after being **connected** (unmetered) at >= 3 teaching fixes that are `d + a <= r` inside the
    zone, on >= 2 local days, and **never** heard at a teaching fix surely outside by 200 m (`d - a >= r + 200 m`). One
    such sighting revokes the link; a second blocks it for 60 days (a cabin MiFi, a travel router).
    **Use - it only renews a still vouch (Q5):** when 124's 4-h cap is reached while `motion()` says `STILL` since a
    fix surely inside that zone, and the phone is connected to a network linked to it, the cap try re-sends the vouched
    fix with `still: true` (a new 4-h step) instead of a new position; a motion fire ends it, and the daily refresh (D14)
    still runs GPS. It never establishes a position after a move, and it never yields a fix of its own (design 64
    decision 1: the server judges the zone on the fixes). Nothing about the link leaves the phone.
13. **Getting the scan** (`WifiPlaces.scan`), only with the `network` provider **off**: at the start of every
    fresh-fix attempt, inside `currentLocation`'s Location window - the cached `getScanResults()` when D9's fresh rule
    holds, else `startScan()` and `registerScanResultsCallback` (API 30; the broadcast only as a fallback) for up to
    **8 s** while GPS starts where the path wants it; `false` (throttled) or no answer -> the 2-min fallback. Never on a
    `QUIET` or `STILL` beat, never periodic. With the `network` provider **on**: no `startScan`, no lookup, no teaching
    scan (H1: each would hand GMS another scan-with-position).
14. **Where it sits** (in `freshOrLastKnown`; a learned fix is accepted, cached and reported like any fix):

    | Path | `network` on (GLA on) | `network` off (GLA off) |
    |---|---|---|
    | Heartbeat `FIX`, a crossing's first fix, the low-battery fix | 124 D9, unchanged - no learned path | the **first `FIX` after a motion fire**: scan + GPS 15 s together, keep the better (GPS wins at equal or better accuracy), GPS teaches; **other tries**: the learned fix if it stands alone (D9), else GPS 15 s; then a cell fix, then last-known |
    | A crossing's confirm (precise) | unchanged | GPS first, then the learned fix, then a cell fix |
    | `locate` (fused high accuracy) | unchanged | 60 s; **20 s** only when a learned fix with >= 3 networks and acc <= 60 m is in hand (L5); then the learned fix, then a cell fix |
    | The zones series' checks | unchanged | unchanged (balanced only) |

    **The daily refresh** (M3): `lastRefreshTryMs` stamped at every GPS try of this design; when 24 h pass without one
    (a still weekend at home), the next heartbeat `FIX` runs GPS too, keeps the better and teaches.
15. **Design 124's rules for a learned fix:** a fresh (not fallback) learned fix counts as fresh for `afterTry` - a
    still night at a known place renews "Hasn't moved since" at each 4-h cap try. 124's D7 compares only when the
    **vouched** fix is `gps`/`fused`, and a learned fix is never the new side of a contradiction (a wrong Wi-Fi fix
    must not distrust the sensor). A GPS fix more than 500 m from a learned fix of >= 3 networks taken within 10 min is a
    **learned contradiction**: logged and counted (CE prefs, last 30 days) for the Jelly Star's review (M5). `QUIET` is
    unchanged and does not scan. 124 §8's witness is **dropped**; only D12's zone link renews a vouch.
16. **Leave-one-out accuracy counts.** Before a GPS fix teaches, `learnedFix` runs on the same scan against the table
    as it was; whether the GPS fix lies inside the reported accuracy is counted per class (>= 3 networks, 2 networks,
    a zone-link renewal) over 30 days (CE prefs `wifi_places` `loo_v1`), logged as counts only
    (`WifiPlaces: loo 3+=41/52 2=9/14`). It is how the Jelly Star tells whether "68 %" holds.
17. **The manifest's `neverForLocation` goes** (it would now be false); the scan reads rely on `ACCESS_FINE_LOCATION`
    and Location on, which the fix window holds; `NEARBY_WIFI_DEVICES` is self-granted beside the location
    permissions (L2), the manifest comment says why. The connected BSSID and metering come from
    `WifiManager.getConnectionInfo()` (with fine location, inside the window) and
    `ConnectivityManager.getNetworkCapabilities` (`NOT_METERED`); a `NetworkCallback` with `FLAG_INCLUDE_LOCATION_INFO`
    (API 31) if 17 drops the first (L3). Scans and cell reads note a location access: Vibb shows in the privacy
    dashboard as it does for its fixes.

## 4. Privacy and what the parent sees

18. **Everything stays on the phone.** The table - Wi-Fi rows, cell rows, zone links - is one file in
    credential-encrypted storage (`noBackupFilesDir/wifi_places_v1`, `AtomicFile`), **sealed whole** with AES-GCM
    through `SecretBox` (AAD `wifi_places_v1`; L7). The 32-byte HMAC key is random, kept in `SealedPrefs`, and cached in
    memory for the process (L6). Format, before sealing: magic `VWP`, a version byte, the row count; rows each a `kind`
    byte (1 Wi-Fi, 2 cell, 3 zone link), fixed-width fields (positions as 1e-7 degree ints, times in minutes). A new
    version drops the old file (the no-compat rule). Logs carry counts and accuracies only - never a BSSID, SSID, cell
    ID, key or a table coordinate (`WifiPlaces: scan usable=17 trusted=9 networks=4 acc=55 path=wifi`, `taught aps=14
    new=3 moved=1`, `learned contradiction`, `cleared (off|unenrol|keybroken)`).
19. **Cleared** (`WifiPlaces.clear`: delete the file and the key's pref, no Keystore call): location **Off**
    (`LocateCommands.clearCachedFix`, P-L3), `ZoneWatch.release` (no longer managed), `ZoneWatch.forget` (a new
    enrollment), and when `keyBroken()` says the Keystore key is gone. A transient Keystore error or an unreadable read
    skips that one lookup or teach and keeps the table (L6). "Only when asked" keeps the table: it learns from the
    `locate` fixes the parent asked for, and `locate` indoors is its best use.
20. **Google:** Vibb sends Google nothing in any mode, and with GLA on it scans nothing (D13). A-GPS help (SUPL/PSDS
    downloads by the GNSS stack) is the chipset's, unchanged [not checked on the Jelly Star].
21. **What the parent sees:** the fix on Locate with its circle and `vibb_wifi`/`vibb_cell` in the newest fix's title;
    "Hasn't moved since" through still nights at known places; zone arrivals judged as for any fix. No new report
    field, no PWA line, nothing of the table or the links.

## 5. Android 14-17 and the Jelly Star

- `startScan()` (deprecated in 9, working on 14-17) is throttled by `ScanRequestProxy`: importance `FOREGROUND_SERVICE`
  or better 4 scans per 2 min, background apps together once per 30 min; Vibb's anchor (`specialUse` FGS) should put it
  in the first class [inferred from AOSP, device check]. A Device Owner can't lift the throttle or set "Wi-Fi scanning"
  (`WIFI_SCAN_ALWAYS_AVAILABLE` isn't in `setGlobalSetting`'s list).
- `getScanResults()`: fine location and Location on (a `SecurityException` or an empty list, both caught); background
  needs background location (self-granted, `zone_watch.background_location`). `registerScanResultsCallback` (30),
  `ScanResult.wifiSsid` (33), `getApMldMacAddress` (33), `timestamp` in us since boot - all under minSdk 34.
- Wi-Fi off: scans need **Location › Wi-Fi scanning** on (off -> no learned fix, GPS as today, logged); into
  `docs/setup/android-settings.md`, GLA's switch into `docs/setup/google-account.md`.
- Doze: the heartbeat and the teaching fix run from while-idle alarms on the power-save allowlist; whether MediaTek's
  Wi-Fi HAL scans in deep Doze is a device check. 14-16's behaviour-change lists name nothing for scans or cell info;
  17's is read at build time.
- The emulator has one virtual AP (`AndroidWifi`) and a fake cell: wiring only (scan and cell reads on 14 and 17, the
  throttle's answer, the clear at Off, the manifest); the estimator is proven by the vectors and the Jelly Star.

## 6. Battery, data, storage (estimates)

A full scan ~0.02-0.1 mAh [estimate], a GPS try 0.25-0.4 mAh (design 64 §5). GLA off, 30-min interval, after 124 step 1,
~12-20 heartbeat tries a day: scans 0.3-2 mAh; GPS after each move (~5-8: 1.3-3.2 mAh), teaching fixes (<= 6: <= 2.4
mAh), unknown places and the refresh (~1-2 mAh): **~3-9 mAh a day (0.15-0.45 %)** - about 124's figure with GPS
alone, by design: the gain is indoor fixes without Google. No network data. Storage <= ~300 KB; CPU a few hundred
software HMACs and one AES-GCM open/seal per attempt.

## 7. Failure modes

| Case | What happens | Bound |
|---|---|---|
| A place never seen with GPS | no trusted networks -> GPS -> often none; the age shows | as today |
| The first days, or after an Off | empty table; GPS after moves, teaching fixes and passive GPS fill it | the Jelly Star's days-to-learn (§8) |
| A moved lone router or mesh (house move) | one network never gives a fix (D9) -> GPS teaches -> the network moves (D7) | at the first GPS fix there |
| A cabin MiFi linked to Home | the drive fires the sensor: no renewal after a move (D12); any good fix at the cabin revokes the link | none |
| A bus, train or car AP | a moving SSID is skipped (D4); else taught only at a stop, untrusted until 2 occasions, outvoted, blocked 60 days after 2 moves | D1, D4, D7, D9 |
| A stale or repeated scan | repeats weigh a quarter; more than 2 make it the fallback, no teaching | D9 |
| A neighbour hearing home's APs | weak-signal floor and stronger unknown networks inflate the accuracy | never "inside" (D9's binding row) |
| Two clusters disagree | no fix | D9 |
| Someone clones known BSSIDs | needs 3 networks to stand alone after a move; GPS after moves catches it | low risk; the provider word names it |
| Scan throttled, partial or stale; Wi-Fi and scanning off | the 2-min fallback (not fresh) or nothing | today's paths |
| Keystore error | that lookup skipped; a broken key clears the table | L6 |

## 8. Size, steps, tests, the Jelly Star check

- **L1 (launcher, after 124 L1 is merged, one commit; ~750-850 lines of code + ~500 of tests):** pure
  `places/PlacesPlan.kt` (usable and the moving list, teach and stream pairing, row update, moves, prune, the stale
  guard, complete-link consensus, accuracy, stand-alone rules, zone links, cells, leave-one-out counts, the teach
  budget, the store codec), glue `places/WifiPlaces.kt` (scan, the GPS stream, cell read, connected network, the sealed
  store, the key, the teaching alarm, the clears), the asset `places/moving_ssids.json`, hooks in `LocateCommands.freshOrLastKnown`/
  `clearCachedFix`, `MotionFlags` (the fire's time), `PassiveFixes` (every Regularly phone), `ZoneWatch.release`/
  `forget`, the manifest. GPL, not the MIT directories. **Server:** none (an optional test: a `vibb_wifi` fix stored
  and titled). **No contract file changes.**
- **Docs:** a `## Wi-Fi places` section in `docs/notes/launcher/zones-and-power.md` - including that on a GLA-off
  phone zone arrivals indoors depend on learned fixes of ~100 m or better (L9) -, `docs/setup/google-account.md`,
  `docs/setup/android-settings.md`, `new-phone-model.md` §2.
- **Tests:** `PlacesTest` over `launcher/app/src/test/resources/wifi_places.json` (the launcher's alone): `usable`
  (every row of the moving list, the three-octet car rule), `teach` (provider x GLA x accuracy x speed x satellites x
  mock, stream pairing to the nearest fix, the +-30 s fallback, `accEff`), `update` (centroid, band floors, a move per
  band, the second move blocks for 60 days and then restarts), `prune`, `lookup` (the fire rule, a partial scan, the
  stale guard, the connected AP added, a dead `WifiInfo`, complete vs single link, consensus by network, a 2-2 split, a
  false five-octet merge, no single-network fix, the fallback not fresh, the oldest time, every accuracy factor),
  `zone_link` (learning, revoking, the 60-day block, a renewal only while still), `loo` (the counts per class),
  `cells` (the veto, an empty list), `codec` (round trip, a new version dropped). **The synthetic town**: ~60 fake APs (RFC 7042's documentation block `00:00:5e:00:53:xx`) in houses and a
  school, realistic reach (log-distance, exponent ~3.3, 6 dB shadowing, walls; 2.4 GHz heard ~100-150 m outdoors), walks
  that teach; >= 68 % of indoor points within the reported accuracy, the neighbour row never inside, and the H2 rows: a
  **moved mesh**, a **cabin MiFi**, a **moved lone router** - none gives a wrong standing fix. Test HMAC key
  `test-key-not-a-secret`. Source checks: no `Log` in `places/` takes a BSSID, SSID, cell ID or key; no
  `neverForLocation`; the clears are called; with the `network` provider on no `startScan` is reached.
- **The Jelly Star** (release build; new lines in `new-phone-model.md` §2):
  1. `dumpsys location` with GLA off: `network` disabled; a teaching GPS fix's extras carry `satellites` (L1).
  2. GLA off, airplane mode with Wi-Fi on, indoors: `locate` returns nothing from `fused` - then fused may teach (L1).
  3. A day on Regularly: `adb logcat -s WifiPlaces` counts teaches; `dumpsys wifi` shows uid 10232's scans accepted.
  4. Days until the first `vibb_wifi` fix at home; its circle against a GPS fix at the window; the zone link's day.
  5. A still night: "Hasn't moved since" through it; the cap tries log `path=wifi`; the learned contradictions count.
  6. Wi-Fi off with scanning on / off; Location Off from the PWA: `cleared (off)`.
  7. `dumpsys batterystats me.vibb.launcher`: scans, GPS time, against 124's measurement.
- Order: design QA of this revision, L1, code QA, the emulator wiring run (14 and 17, the user's OK), §8 on the phone.

## 9. Not in this design, and later

Not: Wi-Fi RTT (few APs answer it), Bluetooth beacons, GNSS seeding from a learned fix (`injectLocation` is
system-only), sharing tables between phones, any learned-place word in the PWA.

Later, each its own decision:
- **An Apple Wi-Fi lookup through the family's own server** (the user, 2026-11-22): optional, **off by default**, per
  family; only on a learned miss, the phone asks its own server, which asks Apple's Wi-Fi service for the area and caches
  the batch; Apple sees the server's IP and the nearby BSSIDs, no identity. Not in this step.
- Teaching by novelty (teach more where the table knows little), decay of rows expected but not heard, an OpenCellID
  import (Norway is sparse: 22,485 cells, Ice 1,393, NR 8, LTE ranges mostly the 1000 m default - wait for the SIM day
  test), distance from RSSI.

## 10. Revision 2 - the design QA and the user's answers

H1: learning starts at GLA off, no scans or lookups with the `network` provider on, the passive listener on every
Regularly phone, no bootstrap from Google-derived fixes (§0, D1, D3, D13). H2: networks as witnesses (D5, D9), GPS
after a fire (D14), one network never establishes after a move (D9), the H2 vectors (§8), §7 made consistent. M1: the
68 % accuracy with band floors and inflations, the neighbour row (D6, D9). M2: the fire rule and the fallback not fresh
(D9). M3: the refresh stamp (D14). M4: the re-scan and `accEff` (D2). M5: D7 and learned contradictions (D15). L1:
`satellites`, fused after the check (D1). L2, L3: D17. L4: MLO and false merges (D5). L5: `locate`'s 20 s (D14). L6,
L7: D18, D19. L8: D9, D13. L9: §8 docs. L10: §8 size, D18's format, D6's device position, the step order. The user:
Q1 yes with GPS after moves; Q2 the zone link (D12); Q3 the teaching fix (D11); Q4 cells in step 1 (D10).

**Revision 3.** The user: Q5 b - the zone link only renews a still vouch (D12); two moves block for 60 days, not
forever (D7); the Apple lookup as a later option (§9). From the research (microG GmsCore f686900, Local NLP Backend
f052047, Déjà Vu e54aae2, GrapheneOS NetworkLocation 474472e): the stale-scan guard (D9), the connected AP always
present and the dead `WifiInfo` guard (D9), the moving-SSID list as data (D4), the move per band without a silent cap
(D6, D7), pairing against a GPS stream (D2), the oldest result's time (D9), leave-one-out counts (D16), complete-link
grouping (D9). Not copied: single-emitter fixes, microG's 14-day quarantine, GrapheneOS's per-axis 1-sigma accuracy,
any online source.

## 11. Questions for the user

All answered on 2026-11-22: Q1 the learned fix at known places with GLA off - yes, accuracy before 0.5 % battery, so
GPS after every move (D14); Q2 no "hjemme" field, a self-learned zone link instead (D12); Q3 the teaching fix after a
move - in (D11); Q4 cells in step 1 (D10); Q5 the zone link only renews a still vouch, never establishes a position
after a move (D12) - a one-router home shows the fix's age after arriving until GPS works there. Nothing is left open.
