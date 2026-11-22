# 125 - Wi-Fi places, learned on the phone

Status: **revision 1 - proposal**, 2026-11-22. Asked by the user: "kunne egentlig tenkt meg å kunne beholde innendørs
posisjon vha google, men finnes det noen foss alternativer eller noe annet vi kan gjøre for å sikre at vi ikke er
identifiserbare for google?", then "Ja sett i gang et wifi selvlæringsdesign"; §8a (cell towers) on "vil den bare lære
wifi eller også cell towers?" the same day. Launcher round; no server code, no contract file changes. Read against
`main` b56676686 with design 124 revision 2 beside it (its step 1 is in build; this note builds on its D5 heartbeat and
D9 provider choice).

## 0. Short answer

With Google Location Accuracy (GLA) off, Android's `network` provider is gone and indoor GPS rarely answers, so the
phone has no indoor fix. microG and Déjà Vu need a ROM without Play services (the Jelly Star has them) and BeaconDB
gives no export, so **Vibb learns places itself**: whenever its own GPS fix is good, it remembers the Wi-Fi access
points (APs) heard at that moment and where they must be; later, indoors, a Wi-Fi scan matched against the learned APs
gives a position computed on the phone. The table never leaves the phone; the server gets only the fix it already
gets, with the provider word `vibb_wifi` and an honest accuracy (40-200 m). It works only at places the phone has seen
with GPS before; elsewhere everything is as today. It also makes design 124's deferred Wi-Fi witness (§8 there)
unnecessary: past the 4-h cap a known place gives a real fresh fix instead of a vouch. Cell towers could be learned
the same way as a coarse fallback (§8a), but the SIM-less Jelly Star reports no cells: a later, optional step.

## 1. What the phone has today (read in the code)

- Fixes: `LocateCommands.freshOrLastKnown` - balanced fused, then GPS (`preciseFix`), then `getLastKnownLocation`, then
  the cached fix. With GLA off the fused provider is GMS's but GPS-only, `network` is disabled; design 124 D9 then goes
  to GPS directly. GNSS also loses its network-assisted seed, so cold starts indoors fail more often.
- Wi-Fi: the manifest has `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, fine/coarse/background location (self-granted in
  `currentLocation`) and `NEARBY_WIFI_DEVICES` **with `neverForLocation`** (the Quick Controls network picker,
  `QuickControls.scanWifiNetworks`: `startScan` + `SCAN_RESULTS_AVAILABLE_ACTION`). Scans need system Location on
  whatever permission is held (`QuickControls.isLocationEnabled`'s note); `currentLocation` already switches Location
  on for the fix and back off unless `locationStaysOn` (P-L2).
- Keys at rest: `SealedPrefs` (AES-GCM in the AndroidKeyStore) for small secrets; `allowBackup="false"`.
- The server stores any provider word of 1-16 `[a-z0-9_-]` (`models::location_provider`) and shows it only in the
  Locate page's newest-fix title (`locate::fix_title`); `vibb_wifi` passes as is.

## 2. Decisions

### Learning (who teaches, what is kept)

1. **Only our own good satellite fixes teach.** A fix teaches when it is ours and fresh (`freshOwnFix`), its provider
   is `gps` - or `fused` while `NETWORK_PROVIDER` is disabled (GLA off: GMS's fused can then only be GNSS [inferred,
   Jelly Star check]) -, not mock in a release build, accuracy <= **25 m**, and speed unknown or <= **3 m/s** (a bus or
   car smears positions and carries its own APs). Never a `vibb_wifi` fix (no feedback loop), never a balanced fix
   with GLA on (that is Google's Wi-Fi database, not ours), never a last-known or cached one. Teaching runs whether GLA
   is on or off, so the table is ready the day the user switches GLA off. Every path that takes a GPS fix teaches: the
   heartbeat, `locate`, a crossing's confirm, the low-battery fix; on zones phones also a passive `gps` fix from
   another app (`PassiveFixes`), free.
2. **The scan that teaches** is one whose results' `timestamp` lies within **+-30 s** of the fix's
   `elapsedRealtimeNanos`. Every fresh-fix path starts a scan at its beginning (D9), so a GPS fix of 5-60 s finds one;
   else the system's cached results are used if they are that close. At most the **60** strongest usable APs per teach.
3. **Which APs are usable** (pure `usableAp`, the same filter for teaching and for lookup):
   - a parseable unicast BSSID with the **locally administered bit clear** (first octet & 0x02): Android and iOS
     hotspots, Wi-Fi Direct groups and most virtual/guest BSSIDs use random local addresses. Cost: a router's
     secondary guest BSSID is skipped; its primary isn't.
   - an SSID that does not contain **`_nomap`** (the Google/Mozilla opt-out) or **`_optout`** (Microsoft's), any case.
   - an SSID that doesn't look like a phone or Wi-Fi Direct: starts with `DIRECT-`, `AndroidAP`, `AndroidShare`,
     `Pixel`, `Galaxy`, or contains `iPhone`/`iPad` (rows in the vectors, easy to extend). Hidden SSIDs are kept.
   - the network the phone is **connected** to only when unmetered (`NET_CAPABILITY_NOT_METERED`): a hotspot the
     phone rides on is metered.
   The real guard against things that move is D6; the name list only removes the obvious.
4. **Per AP the phone keeps** (one row, ~70 bytes): `key` = HMAC-SHA256(install key, BSSID) cut to 12 bytes;
   `device` = HMAC(install key, the first five octets) cut to 8 bytes (one router's 2.4/5/6 GHz and MLO links usually
   share them - H2/L2 of design 124: several BSSIDs count as **one device** in D8); `lat`, `lng` (the estimate),
   `weight` (capped), `rangeM`, `sightings`, `occasions` (teaches at least 30 min apart), `lastTeachMs`, `lastSeenMs`,
   `moves`. No SSID, no BSSID, no signal history.
5. **The estimate** (Déjà Vu's idea, simplified): a weighted centroid of the teach positions. A sighting's weight
   `w = q(rssi) x a(acc)`, `q = clamp((rssi + 100) / 40, 0.1, 1)` (heard at -60 dBm or stronger = 1: near the AP),
   `a = clamp(10 m / acc, 0.4, 1)`. Update `pos = (pos x W + teach x w) / (W + w)`, `W = min(W + w, 20)`: once
   saturated the newest ~20 sightings dominate, so a slow drift follows. `rangeM` = max over sightings of (distance
   from the estimate + the fix's accuracy), within **40..250 m** (40 m: an AP is heard at least that far indoors, even
   when every teach was at its door). Plain lat/lng arithmetic is exact enough within an AP's reach.
6. **APs that move.** A sighting more than **500 m** from the estimate (beyond any phone-heard reach) is a move: the
   row restarts at the new point (`sightings = 1`, `occasions = 1`) and `moves + 1`. **Two moves** block the row for
   good: it is kept as a tombstone, never used, never taught again (a bus or train AP, a parent's travel router). One
   move (a family moving house with its router) relearns. Smaller moves (a neighbour within 500 m) drift away through
   D5's cap.
7. **Ageing and size.** A row unseen (`lastSeenMs`, also touched by a lookup that uses it) for **180 days** goes; above
   **4000 rows** the least recently seen go first (tombstones included). ~280 KB at the cap; a kid's places and routes
   are likely 500-3000 APs.

### Using it

8. **The lookup** (pure `wifiFix(scan, table, connected, now)`): usable APs from a scan **<= 2 min old**, found by key,
   not blocked, **trusted** (`occasions >= 2`). Grouped into devices (D4). Consensus: the device with the most others
   within `rangeA + rangeB` of it seeds the cluster (a tie: the stronger signal); the fix needs **at least 2 devices and
   more than half** of the trusted devices seen - else none (two that disagree give no fix). Position: the mean of the
   cluster's estimates weighted by `q(rssi) x min(occasions, 5) / 5`, the connected unmetered AP x 2 (the user's
   "home Wi-Fi" signal - a weight, never a place by itself). Accuracy: `min over the cluster of (distance(fix, AP) +
   rangeM)` - the phone hears every one of them, so it is within that of the fix whenever the ranges hold; over
   **200 m** gives no fix (design 64's `PASSIVE_ACCURACY_FLOOR_M`). Time: the newest used result's `timestamp` on the
   wall clock. **One device alone** gives a fix only when it is the connected unmetered network, `occasions >= 3`,
   taught within 7 days and no other trusted device is seen (a lone rural router). A lookup changes nothing in the table
   but `lastSeenMs`.
9. **Getting the scan** (`WifiPlaces.scan`): at the start of every fresh-fix attempt, inside `currentLocation`'s
   Location window: if the cached `getScanResults()` hold results <= 30 s old (the system's or another app's scan), use
   them; else `startScan()` and wait for `SCAN_RESULTS_AVAILABLE_ACTION` (`EXTRA_RESULTS_UPDATED`) up to **8 s**
   (meanwhile GPS starts where the path wants it); `false` (throttled) or no answer -> the cached results if <= 2 min
   old, else no scan. Never a scan outside a fix attempt: no `QUIET` or `STILL` beat scans (the phone hasn't moved, the
   answer would be the same), no periodic scanning, no `requestLocationUpdates`-style feed.
10. **Where it sits** (in `freshOrLastKnown`; a learned fix is accepted, cached and reported like any fix, provider
    `vibb_wifi`):

    | Path | `network` provider on (GLA on) | `network` off (GLA off) |
    |---|---|---|
    | Heartbeat `FIX`, a crossing's first fix, the low-battery fix (`FRESH_BALANCED`) | 124 D9: balanced, GPS if worse than 200 m or none; **then the learned fix** before last-known | **the learned fix first**; none -> GPS 15 s |
    | A crossing's confirm (`FRESH`, precise) | GPS, then the learned fix | the same |
    | `locate` (fused high accuracy, 60 s) | as today, then the learned fix | GPS waits **20 s** when a learned fix is in hand, else 60 s; then the learned fix |
    | The zones series' checks | unchanged (balanced only) | unchanged |

    **The daily refresh:** a heartbeat that got a learned fix still runs GPS (15 s, keep the better) when no GPS fix
    has taught for **24 h** - so known places are re-checked and a moved router meets D6. One extra GPS start a day.
11. **Design 124's rules for a learned fix:** it counts as fresh for `MotionFlags.afterTry` (it is an independent
    observation: a still night at a known place renews "Hasn't moved since" at each 4-h cap try, all night); it is
    **never** a D7 contradiction (only GPS/fused fixes judge the sensor - a wrong Wi-Fi fix must not distrust it); the
    back-off (`QUIET`) is unchanged and does not scan. 124 §8's witness is **dropped**: a learned fix is a better answer
    to the same question.
12. **The manifest's `neverForLocation` goes.** It asserts Vibb never derives location from Wi-Fi scans; with this
    design that is false. Vibb's scan reads rely on `ACCESS_FINE_LOCATION` (self-granted) and Location on, which the
    fix window already holds; the Quick Controls picker switches Location on itself and self-grants fine location too
    (added there if missing - build check).

### Privacy and what the parent sees

13. **Everything stays on the phone.** The table is a file in credential-encrypted app storage
    (`noBackupFilesDir/wifi_places_v1`, written through `AtomicFile`), never in a report, a log, a backup or a command
    answer. The 32-byte install key is random, kept in `SealedPrefs` (Keystore-sealed), so the file alone can't be
    reversed by trying the small BSSID space (124 L3). Logs carry counts and accuracies only - never a BSSID, SSID, key
    or the table's coordinates (`WifiPlaces: scan aps=23 usable=17 trusted=9 cluster=7 acc=55`, `taught aps=14 new=3
    moved=1 blocked=0`, `cleared (off|unenrol|unreadable)`).
14. **Cleared** with the key: when the parent sets location **Off** (`LocateCommands.clearCachedFix`, P-L3's rule), in
    `ZoneWatch.release` (no longer managed) and `ZoneWatch.forget` (a new enrollment), at a wipe by nature. "Only when
    asked" keeps it: it learns only from the `locate` fixes the parent asked for, and `locate` indoors is its best use.
    An unreadable file or key (a Keystore reset) is dropped and started again (no migration, the no-compat rule).
15. **Google:** Vibb sends Google nothing in any mode. With GLA on, GMS scans and reports Wi-Fi itself whatever Vibb
    does; with GLA off, Google states it collects none, and Vibb's own scans only refill the system's cache. A-GPS help
    (SUPL/PSDS downloads by the GNSS stack) is the chipset's and unchanged [not checked on the Jelly Star].
16. **What the parent sees:** the fix on Locate with its circle (40-200 m) and `vibb_wifi` in the newest fix's title
    beside today's `gps`/`fused`; "Hasn't moved since" through whole nights at known places; zones judge it by the
    server's two-fix rule like any fix. No new report field, no PWA line, no table data.

## 3. Android 14-17 and the Jelly Star

- `startScan()` (deprecated in 9, working on 14-17) is throttled by `ScanRequestProxy`: an app at importance
  `FOREGROUND_SERVICE` or better may scan **4 times per 2 min**; background apps together **once per 30 min**. Vibb's
  anchor (`specialUse` FGS) keeps it in the first class [inferred from AOSP, device check]; without the anchor a scan
  may be refused and the cached results decide. A Device Owner can't lift the throttle or set "Wi-Fi scanning"
  (`WIFI_SCAN_ALWAYS_AVAILABLE` isn't in `setGlobalSetting`'s list).
- `getScanResults()`: `ACCESS_FINE_LOCATION` and Location on (a `SecurityException` or an empty list otherwise, both
  caught); background reads need background location (self-granted, `zone_watch.background_location` reports it).
  `ScanResult.wifiSsid` (33), `timestamp` (us since boot), `level`; Wi-Fi 7 links are separate entries (D4 groups).
- Wi-Fi off: scans need **Location › Wi-Fi scanning** on (a user setting; off -> no learned fix, GPS as today, logged).
  Goes into `docs/setup/android-settings.md` with GLA's own switch in `docs/setup/google-account.md`.
- Doze: the heartbeat runs from our while-idle alarm on the power-save allowlist; whether the Wi-Fi HAL scans in deep
  Doze on MediaTek is a device check. 14-16's behaviour-change lists name nothing for scans; 17's is read at build time.
- The emulator has one virtual AP (`AndroidWifi`): wiring only (scan read on 14 and 17, the throttle's answer, the
  clear at Off, the manifest); the estimator is proven by the unit vectors and the Jelly Star.

## 4. Battery, data, storage (estimates)

A full scan is ~2-4 s of Wi-Fi radio: ~0.02-0.1 mAh [estimate; batterystats measures it], a GPS try 0.25-0.4 mAh
(design 64 §5). GLA off, 30-min interval, after 124 step 1: ~12-20 tries a day, today all GPS (3-8 mAh, most indoor
ones failing). With learned places: one scan per try (0.3-2 mAh) and GPS only at unknown places, on a miss and the
daily refresh (~1-3 mAh): **about the same or less, 1.5-5 mAh a day**. The gain is indoor fixes without Google, not
battery. No network data at all (the fix rides on the report). Storage <= ~300 KB; CPU: a few hundred software HMACs a
day.

## 5. Failure modes

| Case | What happens | Bound |
|---|---|---|
| A place never seen with GPS (a friend's flat) | no trusted APs -> GPS -> often none; the age shows | as today |
| The first days, or after an Off | empty table; learns from outdoor fixes; home needs a good fix where its APs are heard | the Jelly Star measures days-to-learn (§7, Q3) |
| A moved router (family moved) | lone at the new place: no fix (no consensus) -> GPS teaches -> one move, relearns | D6, the daily refresh |
| A bus, train or car AP | taught only at a stop (speed <= 3 m/s), not trusted until 2 occasions; outvoted by street APs; blocked after 2 moves | D3, D6, D8 |
| A parent's hotspot | random local address or a phone name: never used | D3 |
| An apartment block, a school | APs on every floor share a 2-D position: fine; interior APs never heard outside stay unknown | no fix deep inside |
| Two clusters disagree | no fix | D8 |
| Someone clones two known BSSIDs | a false fix until GPS teaches or the refresh contradicts it | low risk; `vibb_wifi` is named |
| Scan throttled / Wi-Fi and scanning off | cached results <= 2 min, else no learned fix | today's paths |
| Key or file unreadable, direct boot | no learned fix; a new table | logged |

## 6. Size and steps

- **L1 (launcher, M, one commit, after 124 L1 is merged):** pure `places/WifiPlacesPlan.kt` (filters, teach, update,
  moves, prune, consensus, estimate; ~250 lines), glue `places/WifiPlaces.kt` + the store (scan, `AtomicFile`, the
  sealed key, the clears; ~200 lines), hooks in `LocateCommands.freshOrLastKnown`/`clearCachedFix`,
  `ZoneWatch.release`/`forget`, `PassiveFixes` (teach), the manifest. GPL, not the MIT directories.
- **Server:** none. One test row (optional, ~10 lines): a report with provider `vibb_wifi` is stored and titled.
- **No contract file changes:** the provider is a free word in `zone_status_report.json`'s shape; the rules are the
  phone's alone.
- **Docs:** a `## Wi-Fi places` section in `docs/notes/launcher/zones-and-power.md`, `docs/setup/google-account.md`
  (GLA off, a conscious choice), `docs/setup/android-settings.md` (Wi-Fi scanning on), `new-phone-model.md` §2.
- Order: design QA, L1, code QA, the emulator wiring run (14 and 17, the user's OK), then §7 on the Jelly Star.

## 7. Tests and the Jelly Star check

- **`WifiPlacesTest`** over `launcher/app/src/test/resources/wifi_places.json` (the launcher's alone): `usable` rows
  (local bit, multicast, `_nomap`/`_optout` in any case, each phone name, metered connected); `teach` rows (provider x
  GLA x accuracy x speed x mock x the +-30 s window); `update` vectors (centroid, weight cap, range clamp, a 500 m move,
  the second move blocks); `prune` (age, the cap, tombstones); `lookup` rows (consensus, devices grouping, a 2-2 split,
  the lone connected router, a stale scan, the 200 m limit, the accuracy bound). Plus a **synthetic town**: 40 fake APs
  with BSSIDs from RFC 7042's documentation block `00:00:5e:00:53:xx`, a seeded log-distance signal model, walks that
  teach, a bus AP that moves; 100 indoor points: the true point inside the reported accuracy in >= 90 %, never a fix
  with fewer than 2 trusted devices. The HMAC key in tests is `test-key-not-a-secret`.
- Source checks: no `Log` call in `places/` takes a BSSID, SSID or key; the manifest has no `neverForLocation`;
  `clearCachedFix`, `release` and `forget` call `WifiPlaces.clear`; a `vibb_wifi` fix never reaches teach or D7.
- **The Jelly Star** (release build; GLA off, Wi-Fi scanning on; new lines in `new-phone-model.md` §2):
  1. `dumpsys location`: `network` disabled. A day on Regularly: `adb logcat -s WifiPlaces` counts teaches;
     `dumpsys wifi | grep -i -A3 "scan"` shows uid 10232's requests accepted, not throttled.
  2. Days until the first `vibb_wifi` fix at home, and how far its circle is from a GPS fix at the window.
  3. A still night: "Hasn't moved since" through it; the cap tries log `path=wifi result=fresh`.
  4. Wi-Fi off with scanning on: a learned fix; scanning off: none, logged.
  5. Location Off from the PWA: `cleared (off)`; back on: the table starts empty.
  6. `dumpsys batterystats me.vibb.launcher`: Wi-Fi scan count and time, GPS time, against 124's measurement.

## 8. Not in this design

Wi-Fi RTT (few APs answer it), Bluetooth beacons, GNSS seeding from the learned fix (`injectLocation` is
system-only), sharing tables between phones. Cell towers: §8a.

## 8a. Cell towers (the user, 2026-11-22: "vil den bare lære wifi eller også cell towers?")

**Decision: cells are a later, optional step (L2), not in L1.** Measured on the Jelly Star without a SIM (2026-11-22):
`dumpsys telephony.registry` shows `mCellInfo=[]` on both slots - a SIM-less phone reports no cell, so L2 would be a
no-op there today. With a SIM it would work, and it fits this design; where it works it says "this part of town", not
"this house".

- **How it would work.** The same teach rule (D1: our own GPS fix <= 25 m, slow) records the **serving cell** from
  `TelephonyManager.getAllCellInfo()` (the cached list, no radio work) - or `requestCellInfoUpdate` (API 29, one modem
  query) when the cached `CellInfo.getTimestampMillis()` (API 30, elapsed realtime) is more than 60 s from the fix.
  The identity is `CellIdentityLte` (MCC, MNC, TAC, CI), `CellIdentityNr` (MCC, MNC, TAC, NCI), `CellIdentityWcdma`/
  `Gsm` (LAC, CID); neighbour cells usually carry only PCI/EARFCN and are skipped, so a lookup has **one cell**. Kept
  in the same store as a row of kind `cell`: `key` = HMAC(install key, "mcc|mnc|area|cid"), the D5 centroid, `rangeM`
  clamped **500 m..10 km**, a move at **20 km** (operators re-plan IDs), the same ageing and cap (cells are few: a kid's
  life is maybe 50-200).
- **Use:** only after GPS and learned Wi-Fi gave nothing, before last-known, never instead of GPS; provider
  `vibb_cell`; trusted at `occasions >= 3`; accuracy the cell's `rangeM` (honestly 500 m-several km: the PWA's circle
  shows it); over **5 km** no fix. With GLA on there is no point: GMS's `network` provider already answers by cell.
  The zones' two-fix rule rarely calls a km-wide fix "inside" a small zone, so it cannot fake an arrival; the weather
  and "which town" on a trip are its use.
- **Permissions:** `getAllCellInfo` and `requestCellInfoUpdate` need `ACCESS_FINE_LOCATION` and Location on (target
  29+), not `READ_PHONE_STATE`; background reads need background location - all already held. Android 14-17: no
  change named for these calls [17 read at build]; with no SIM the default `TelephonyManager` reads slot 0 [check].
- **Without a SIM** (the Jelly Star, measured): `mCellInfo=[]` on both slots - no serving or neighbour cell
  reported, so nothing to learn. If L2 is built anyway, an empty list is an ordinary miss: no row, no fix, nothing
  logged beyond a count. Recheck with a SIM: `adb shell dumpsys telephony.registry | grep -i -m5 cellinfo` - a
  `CellIdentityLte`/`Nr` with a real `mCi`/`mNci` means it works.
- **Cost:** reading the cached list is free; `requestCellInfoUpdate` is one modem query (~0 mAh) per fix attempt. No
  network data. The privacy rules are D13-D15 unchanged (keyed hash, on the phone, cleared with the Wi-Fi rows).
- **Size:** L2 ~120 lines of code (a second identity kind in `WifiPlacesPlan`'s store, the cell read, the order row)
  plus vector rows. **When:** only once the kid's phone has a SIM and the recheck above shows its cells, and L1's days
  show where Wi-Fi alone leaves gaps (Q4). Until then L1 leaves the store's row kind open (a `kind` byte), nothing more.

## 9. Questions for the user (defaults in bold)

- **Q1** GLA off: at a known place the learned Wi-Fi fix is taken **instead of** GPS (the trail there ±40-150 m, not
  ±5-15 m, GPS once a day and wherever Wi-Fi knows nothing): **yes** / GPS first every time (better outdoors, but a
  failed 15-s GPS start at every indoor try).
- **Q2** "Connected to the home Wi-Fi" shown as its own "hjemme" in the PWA: **no** - a zone named Hjemme already says
  "at Home" for the fix, and the connected network only weighs more on the phone / yes (a new report field and a PWA
  line).
- **Q3** (after the Jelly Star's days-to-learn) A teaching GPS fix ~3 min after the phone starts moving, to learn the
  street outside home and school faster: at most 6 a day, ~1.5-2.5 mAh and the location dot that many more times:
  **decided after the measurement** / now.
- **Q4** Cell towers as a coarse fallback (±0.5-5 km, `vibb_cell`): **a later, optional step once the phone has a
  SIM** (the Jelly Star without one reports no cells) / in L1 now (~120 lines that do nothing until a SIM is in).
