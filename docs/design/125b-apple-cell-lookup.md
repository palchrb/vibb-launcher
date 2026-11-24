# Design 125b: Apple's cell lookup (design 125 step 3)

Status: design, **revision 2**, 2026-11-24 (branch `cell-lookup-design`), read against `main` 88d11d5de. **Step 3a built** on branch `cell-lookup` (a83041bac and the commit after it; the zones half needs no column: a pending side's coarseness is read from its fix's row). **Step 3b built** on the same branch (the commit after them). What the build settled: the contract's cell rows sit under keys of their own (`cell_answers`, `cell_phone_answers`, `cell_phone_rows`) beside the step-2 ones; the canonical form has the MCC as a number too; a gate wait that ran out is still `429 busy`; the day's brake counts distinct canonical cells per request and is checked before the hour's 30; the phone's cell rules are `CellPlanTest` code rows, not `wifi_places.json` rows (`docs/notes/server/integrations.md` and `docs/notes/launcher/wifi-places.md`, "Apple's cell lookup").
Revision 1 was d0610e571; revision 2 takes the design QA (1 H, 3 M, 9 L, two clarifications) and the user's answers (Q1,
Q2 changed, Q3, Q5 then superseded, Q6; §12) and the user's later change: **no cache oracle for cells** and the day cap
raised to an abuse brake (C5, C12). Asked by the user on 2026-11-22 ("Legg det i køen"): the phone asks the family's
server for the cells it hears and has no position for, the same way as for BSSIDs; the server asks Apple through the
same proxy into the same sealed cache, under the same limits; the phone gets a coarse fix (hundreds of metres to
kilometres) where no Wi-Fi is known.

**Why a note of its own** and not a section of design 125: 125 is at revision 3 with steps 1 and 2 built, its QA rounds
settled and its "what the build settled" in its status line; step 3 starts with its own design QA. A build agent reads
125 (D10, D14, §4a) and this note; 125's A10 and §9 point here. Numbering: **C1-C14** here, beside 125's D (step 1) and
A (step 2).

**Two commits.** **Step 3a** (§10) is a fix of what is built - step 1's `vibb_cell` fixes count as fresh, re-arm
`STILL`, are reported while still, and two of them 60 s apart can confirm "left Hjem" through `zones::side`/`step` - and
goes first, on its own, with no API change. **Step 3b** is the cell lookup.

## 0. Short answer

GrapheneOS NetworkLocation already does this at the commit step 2 ported (474472e, MIT): its `apple_wps.proto` has the
cell fields, `AppleCellPositioningService.kt` the request and answer, `LocationReportingTask.kt` reads `CellInfo` into
identities and estimates a cell fix. Step 3 is step 2 again with another key, and **follows GrapheneOS's cell logic
wherever our zones and server don't need otherwise** (the user, 2026-11-24): the phone reads every cell with a full
identity, asks the server for those it has no row for - in a **request of their own** beside the BSSIDs' -, the server
answers from its sealed cache or a **separate** Apple cell call through the proxy, **the same answer at the same time
either way** (no age, no cached mark, a fixed answer time: the cache never tells where the phones have been), and the
phone keeps the answers as **Apple cell rows** beside step 1's own. The fix takes GrapheneOS's median reference and
weighting with our more conservative accuracy; it is a last resort, never fresh, never a vouch, never "inside" a zone,
and "surely outside" only far away (C10, the user's cabin case). One switch, "Oppslag hos Apple"; no migration; contract
rows in `wifi_lookup/contract.json`.

## 1. Apple's protocol for cells (sources and field numbers)

Sources: GrapheneOS `platform_packages_apps_NetworkLocation` at **474472e**: `proto/apple_wps.proto`,
`cell/AppleCellPositioningService.kt`, `cell/CellTowerScanner.kt`, `cell/CellPositioningServiceCache.kt`,
`LocationReportingTask.kt`. Cross-checked against acheong08 `apple-corelocation-experiments` (0b136860e,
`pb/BSSIDApple.proto`), which reverse-engineered LTE alone: it agrees on LTE's request field **25**, answer field **22**
and the tower's fields 1-7. Both read 2026-11-24.

| What | Request (`ALSLocationRequest`) | Count of surrounding | Answer (`ALSLocationResponse`) |
|---|---|---|---|
| GSM (`GsmCellTower`) | 1 | 3 | 1 |
| LTE (`LteCellTower`) | 25 | 26 | 22 |
| "SCDMA" (`ScdmaCellTower`) - GrapheneOS sends **WCDMA** here | 27 | 28 | 23 |
| NR (`Nr5GCellTower`) | 29 | 30 | 24 |
| CDMA / EV-DO (not sent) | 21 / 22 | 23 / 24 | 21 |
| Wi-Fi (step 2, unchanged) | 2 | 4 | 2 |
| `meta` (macOS's, as step 2) | 33 | | |

The towers: GSM and SCDMA `{mcc 1, mnc 2, cell_id 3, lac_id 4, location 5, arfcn 11, psc 12}`, all int32; LTE `{mcc 1,
mnc 2, cell_id 3 (int32, the 28-bit ECI), tac_id 4, location 5, uarfcn 6, pid 7}`; NR `{mcc 1, mnc 2, cell_id 3
(**int64**, the 36-bit NCI), tac_id 4, location 5, nrarfcn 6}`. `location` is step 2's `ALSLocation {latitude 1,
longitude 2, accuracy 3}`, x 1e-8, -18000000000 = unknown. GrapheneOS sends only the identity (1-4), never the channel,
PCI or signal; so do we. MCC and MNC are numbers (MNC "01" goes as 1).

**Quirks GrapheneOS found** (kept as they are): Apple sends about 19 more surrounding towers than asked, and asking for
none of a standard that is in the request makes it send ~120 - so each standard asked gets `max(1, (25 - 19) x its share
of the asked towers)` (4 instead of 25 while throttled); an answer of 10 or more towers throttles the next 10 s; at most
12 towers asked; no request when 8 of the heard cells already have a position (`onlyCachedThreshold`). **Not verified
anywhere:** that WCDMA belongs in the SCDMA fields (Norway has no 3G; low stakes, Q7), and that one call may carry both
Wi-Fi and cells (GrapheneOS never does - C6 keeps them apart, Q4). **The count fields' type:** acheong08 declares the
request's count fields 3 and 4 as `sint32` (zigzag), GrapheneOS as `int32` (plain varints); we encode GrapheneOS's
`int32` - what GrapheneOS ships and what step 2's Wi-Fi count (field 4) already sends, checked against `protoc` in step
2's check (1).

## 2. What the phone has today

Step 1 (D10, built): `WifiPlaces.servingCell` takes the first `isRegistered` cell from `getAllCellInfo()`, one
`requestCellInfoUpdate` on the default subscription when it is over 60 s old (3 s wait), keys it
`HMAC("cell|mcc|mnc|area|cid")`; `Snapshot.cell` holds that one cell; own cell rows (kind 2) range 500 m-10 km, a move
at 20 km; `cellFix` gives `vibb_cell` from that one row (3 occasions, <= 5 km) **with `fresh = true`**; `cellOnly` reads
the store opened at the attempt's start; `cellVetoes` rejects a Wi-Fi fix 2 km past the cell's range. Step 2 (A3-A9,
built): the route, the sealed cache, the phone's Apple rows (kind 4); `askApple` returns at once when `scanView` is null
(fewer than 3 fresh usable APs, Wi-Fi and scanning off). The Jelly Star has no SIM (`mCellInfo=[]`, 125 §1).

## 3. Decisions - what the phone reads

C1. **The read** (Android 14-17; every API below is <= 30). `getAllCellInfo()` returns the **cached** cells of every
    slot (AOSP `PhoneInterfaceManager.getCachedCellInfo` loops over all phones; apps targeting Q+ never trigger a modem
    poll with it). When the newest registered cell is over 60 s old (`CellInfo.getTimestampMillis()`, elapsed since
    boot), one `requestCellInfoUpdate` per **active subscription**, through `MobileNetworkRuntime`'s existing slot ->
    subscription mapping (no second copy of it; the call refreshes only its subscription's modem), waiting 3 s as today.
    The modem is asked at most every 2 s with the screen on and no Wi-Fi (or charging), else every 10 s - a request
    inside that returns the last list (`ServiceStateTracker.requestAllCellInfo`,
    `DeviceStateMonitor.computeCellInfoMinInterval`, read 2026-11-24): one refresh per attempt is all we ask.
    **Permissions:** `ACCESS_FINE_LOCATION` (and background, both self-granted) with Location on - with Location off
    `LocationAccessPolicy` gives `DENIED_SOFT`, an empty list; the fix window holds Location on. Not `READ_PHONE_STATE`.
    Device Owner changes nothing here. No SIM, airplane mode, radio off, an invalid subscription
    (`IllegalArgumentException`, caught): an empty list, an ordinary miss.
C2. **Which cells.** Every `CellInfo` with a **full identity** (C3's form), as GrapheneOS reads them: the registered
    ones (one per SIM), `SECONDARY_SERVING` (carrier aggregation) and neighbours that carry one (some modems give GSM
    neighbours a LAC and CID; LTE and NR neighbours usually carry only PCI and channel and are skipped). Registered
    first, then by `getCellSignalStrength().dbm`, an `UNAVAILABLE` dbm sorting weakest; duplicates (one cell on both
    slots, or two spellings of one MNC) once, by C7's canonical form; at most **12**. `Snapshot.cells` becomes a list
    (step 1's single `cell` goes). A cell whose time is still more than 60 s before the attempt's start after the
    refresh is not used or asked (a modem that hands back an old list). **NR:** in NSA (EN-DC) the LTE anchor is the
    registered cell and the NR leg comes as a `CellInfoNr` without NCI or TAC - skipped; in SA the registered
    `CellIdentityNr` has the 36-bit NCI and the 24-bit TAC and is used. **Guards** against values modems send for
    "unknown": `CellInfo.UNAVAILABLE`/`UNAVAILABLE_LONG`, a null MCC or MNC string, 0, and C3's placeholder values. What
    the Jelly Star's MediaTek modem sends - the registered flag on both slots, neighbour identities, timestamps that
    move, NR - is §9's SIM check; the guards hold whatever it sends. TD-SCDMA and CDMA identities are skipped. Logs:
    counts only (`WifiPlaces: cells read=4 usable=2 registered=1 nr=0 stale=0`).
C3. **The wire form** of a cell: `"<radio>:<mcc>:<mnc>:<area>:<cid>"`, radio `gsm|wcdma|lte|nr`, MCC 3 digits
    (`001`-`999`), MNC 2-3 digits as the network gives it (`mncString`), area and cid decimal without leading zeros, in
    these ranges (3GPP TS 23.003 reserves area 0 and `...FFFE`; the all-ones value of each field and 0 are common
    "unknown" fills, so both ends are cut):

    | radio | area | cid |
    |---|---|---|
    | `gsm` | LAC 1..65533 | 1..65534 |
    | `wcdma` | LAC 1..65533 | 1..268435454 (28 bits less all-ones) |
    | `lte` | TAC 1..65533 | 1..268435454 (ECI, 28 bits less all-ones) |
    | `nr` | TAC 1..16777213 (24 bits) | 1..68719476734 (NCI, 2^36 - 2) |

    The phone sends only what passes; the server refuses anything else (C5). Example: `lte:242:01:4711:12345678`.

## 4. Decisions - the ask, the route, Apple and the cache

C4. **When the phone asks** - A3's conditions: the switch on, the `network` provider off, Location on, a network up, in
    a fresh-fix attempt (heartbeat, `locate`, crossing, low-battery), beside GPS, never from a teaching or passive fix,
    never periodic, never with GLA on. **The trigger is a learned miss, and a missing scan view is one** (QA H1):
    `askApple` no longer returns when `scanView` is null - with fewer than 3 fresh usable APs, or Wi-Fi and scanning
    off, it asks for the cells alone. The cells asked: the usable ones (C2) with no trusted own cell row (3 occasions),
    no live Apple cell row, no negative and no tombstone (C7); none when 8 or more of the read cells already have a
    position (GrapheneOS's threshold); and none while the **cell wait** runs (C5). **The cells go in their own
    request**, sent beside the BSSIDs' request when both are wanted (in parallel, each with step 2's 15 s), so the
    cells' fixed answer time (C5) never holds up a Wi-Fi answer. So an area's cells are asked once per three months
    (C7's phone row), and only where Wi-Fi gave no fix that stands alone.
C5. **The route** stays `POST /api/devices/wifi-lookup` (a rename would touch both dirs, the docs and the PWA for
    nothing). A body is **either** step 2's `{"bssids": [...]}` (unchanged) **or** `{"cells": [...]}` with 1..12 cells,
    never both (400), checked by hand as today (4 KB); any other key, an empty list or one bad entry is a 400. Two
    spellings of one cell (`01` and `001`) count once (C7's canonical form) and each asked string is echoed. Each
    request is one of the phone's 30 lookups an hour. **The answer to a cells request says nothing about the cache**
    (the user, 2026-11-24: the cap and the answer exist to protect the server's cache - where the family's phones have
    been -, not Apple's data):
    - **200 `{"cells": [{"cell", "lat", "lng", "acc_m"}], "unknown": [cell]}`** - every asked cell in one of the two
      lists, **no `fresh_months_left`, no age, no cached or fetched mark**. A cached row (fresh or stale), a row Apple
      just sent, a cached negative and Apple's "unknown" read the same. A stale row is refreshed in the call, as A5.
    - **Apple is asked or the request fails, never "the cache alone":** when an asked cell isn't fresh in the cache and
      Apple can't be asked (paused, over the server-wide limit, busy past the gate's wait), or the call fails, the
      answer is **`503 {"error": "unavailable", "retry_after_s": N}`** - and so is any cells request **while Apple can't
      be asked**, cached or not, so the pause itself doesn't separate cached from uncached. Only an Apple call that
      fails during a request with an uncached cell answers 503 where an all-cached request got 200 - rare, and not the
      asker's choice.
    - **A fixed answer time:** a cells answer (200 or 503) leaves no sooner than **the Apple call's whole deadline after
      the request arrived** - 10 s through the proxy (`music_net::PROXIED`), 5 s direct - so a cache hit looks like a
      round trip to Apple. A call that waited for the gate (up to 3 s) can still come later; that needs another lookup
      running at the moment, which a single asker can't time. The phone's 15 s timeout holds (3 + 10 s); GPS runs beside
      it, and the cells request is apart from the Wi-Fi one, so nothing else waits.
    - **The per-phone day cap - an abuse brake: 1000 cells a phone a UTC day** (memory only, like the hourly count),
      never reached by a road trip (a long day asks a few dozen); past it `429 {"error": "limit", "retry_after_s": N}`
      (N to UTC midnight). No other project has a per-device cap - GrapheneOS's own proxy has none -: it is ours because
      our server keeps a cache.
    - 409 `off`, 429 `limit` (hourly) and `busy` (a third lookup at once) and 400 as A4.
    **The phone's two waits** (QA M1): step 2's `apple_wait_until_ms` stays the Wi-Fi wait, set only by a BSSIDs
    request's answers; a new CE prefs `apple_cell_wait_until_ms` (`KEY_APPLE_CELL_WAIT`) is the cell wait, set only by a
    cells request's: its 503 or 429 with `retry_after_s` (capped at 24 h), a 429 without one, a transport failure or the
    timeout 15 min. A 409 from either sets both (the switch is one). So the cell cap never stops a Wi-Fi ask, and a
    Wi-Fi wait never stops a cell ask. Both cleared with the table.
C6. **Apple's side.** Cells go in **their own Apple call** in GrapheneOS's exact shape (the towers, the per-standard
    surrounding counts of §1, `meta`; no `number_of_surrounding_wifis`), never inside the Wi-Fi call - the phone's two
    requests make two calls under the one gate, each counted in the 60 an hour and 10 in 10 minutes. Field mapping:
    `gsm` -> 1/3, `wcdma` -> 27/28, `lte` -> 25/26, `nr` -> 29/30, written in field-number order (protoc's, so the
    golden hex matches byte for byte). Its own throttle (10 towers -> 10 s at 4). The answer walks fields 1, 22, 23 and
    24; field 2 (APs) and 21 in a cell call's answer are skipped and **never cached** (QA L3), as cells in a Wi-Fi
    call's answer are. With step 2's bounded reader (A8): a tower missing MCC, MNC, area or cid, a negative value or one
    outside C3's ranges is dropped; a missing location or -18000000000 is **unknown**; accuracy 1..**50,000 m** (towers
    reach further than APs; APs keep 1..5,000) else dropped; at most 200 towers; the first of duplicates wins. The same
    URL, headers, proxy rule (no silent direct call), pauses and `integration_health` - a cell call's failure is Apple's
    failure, and the orange "Apple lookups aren't working" covers both. The sweep's counts line gains `cells=` and
    `cell_calls=`.
C7. **The server's cache: the same table, key and months.** A cell row lives in `wifi_lookup_aps` beside the APs: its id
    the HMAC of the **canonical form** with MNC as a number (`lte:242:1:4711:12345678` - Apple's form, so the
    surrounding towers it sends match what phones ask), the canonical form also the seal's AAD. No column says "cell"; a
    BSSID and a cell form can't collide. Fresh 3 months, a negative until its calendar month ends, `CACHE_LIFETIME` (2
    years unused), the shared caps (200,000 / 20,000 - cells are a few thousand at most). **No migration.** **The
    phone's Apple cell rows:** kind **5** (`APPLE_CELL`) in D18's table, the same 71-byte layout (no version bump,
    nothing relearned), keyed by step 1's cell key (`HMAC("cell|mcc|mnc|area|cid")`, the radio not in it - an LTE ECI
    and an NR NCI with the same digits under one PLMN and TAC isn't expected, and own and Apple rows of one cell must
    meet); `rangeM = max(500 m, acc_m)`; with no age in the answer (C5) a row lives a fixed **90 days** from receipt
    (`cell_row_days`; the server refreshes its own after three months, so a phone's row is at most about six months
    behind Apple - cells move rarely), a negative **30 days** (`cell_negative_days`); at most **500** (least recently
    seen out); dropped with the switch off and every D19 clear. **Own versus Apple per cell** - A9's rule: an untrusted
    own row (< 3 occasions) doesn't block Apple's; trusted and agreeing (centres within both ranges) -> the smaller
    range (a tie: own); disagreeing -> own, and Apple's becomes a 90-day tombstone, as does an own teach that moves the
    cell (D10's 20 km).

## 5. Decisions - the fix, the still rule, zones

C8. **The cell fix** (pure `cellFix`, replacing step 1's one-row rule; GrapheneOS's `estimateLocationCell` where it
    fits): every read cell with a chosen row (C7) - registered or not, as GrapheneOS.
    - **Outliers by GrapheneOS's median reference:** the reference is the per-axis median of the positioned cells'
      latitudes and longitudes; a cell further from it than its own range is dropped. With one or two cells there is no
      median to trust: two must lie within both ranges of each other (two SIMs' registered cells included), else no fix.
    - Position: the mean weighted by `1 / range^2` (GrapheneOS's variance `acc^2`).
    - **Accuracy - ours, more conservative than GrapheneOS's combined variance:** `max(smallest used range, weighted RMS
      distance of the used cells from the fix)`, at least 500 m; over `CELL_FIX_MAX_M` (**5 km**, D10's; the user, Q3)
      no fix. A range is the area a phone hearing that cell is in (GrapheneOS reads Apple's tower accuracy so), so this
      is wider than 125's 68 %; C11's counts tell how much.
    - Time: the oldest used cell's `getTimestampMillis()` on the wall clock (GrapheneOS's). Provider:
      **`vibb_apple_cell`** (15 characters, within `models::location_provider`'s 16) when any used position is Apple's,
      else `vibb_cell`.
    - **It reads the table after the ask** (QA H1): `cellOnly` re-reads the store (or takes the one the ask wrote) once
      the Apple answer is stored, so the rows just answered give the fix in the same attempt.
    - The veto (D10) reads the chosen rows: a registered cell, own trusted or live Apple, more than its range + 2 km
      from a learned or `vibb_apple` fix rejects that fix (counted, `cell_vetoes=`).
C9. **How it joins the fixes - never over a fine one.**
    1. **Last in the attempt**, as D14 has it: GPS, the learned fix, the Apple Wi-Fi fix, then the cell fix, then the
       last-known. Any of the others in the same attempt wins (keep-the-better; a cell fix is >= 500 m).
    2. **Still means still:** while `motion()` is `STILL` a cell fix is never reported; further than both accuracies + 2
       km from the vouched fix it counts as a **cell contradiction** (CE prefs, counts only, 30 days).
    3. **It doesn't override a recent fine fix** (the user, Q6): when the last fresh fix is under 4 h old (124's cap)
       and lies inside the cell fix's circle (`d <= accCell + accFine`), nothing is reported - the PWA keeps the fine
       fix and shows its age.
    4. Otherwise it is reported with its provider, accuracy and time, and it is **never fresh** (`afterTry`, the
       fresh-fix stamp: `Ask.notFresh`, so the next beat tries GPS again), never a vouch (not in `VOUCHING_PROVIDERS`),
       never renews "Hasn't moved since", never teaches, never the new side of 124's D7.
    5. **An explicit Locate** (the parent's `locate`) reports the cell fix when nothing finer came, whatever 2 and 3 say
       - the parent asked and gets the best there is, its circle showing how coarse (QA L7; the owner's Q10: yes); still
       never fresh and never a vouch.
    6. **The heartbeat's back-off** (QA L9): a reported cell fix is no fresh fix, so after the beat's `FIX` the motion
       goes to `STILL_SINCE_TRY` and 124's `QUIET` back-off spaces the next tries (up to 4 h) until a motion fire. A
       still evening at a cabin with only cells gets few tries - each a GPS try too -, and the PWA shows the cell fix's
       age; a move or a `locate` starts over. Accepted: more tries would give the same cell.
C10. **Zones: a coarse fix is never "inside", and "surely outside" only far away** (the user, Q2 changed, for the cabin case:
    the phone off, driven to a cabin 200 km away, switched on, no Wi-Fi). `zones::COARSE_PROVIDERS` = `vibb_cell`,
    `vibb_apple_cell` (the server reads `device_locations.provider`, stored since migration 0093).
    - **Inside:** never from a coarse fix.
    - **Surely outside:** a coarse fix only when `d - r >= max(3 x a, 20 km)` (`d` its distance from the zone's centre,
      `r` the radius, `a` its accuracy) - three accuracies clear of the zone's edge and never nearer than 20 km. Nearer,
      it is unsure: it moves the cursor and nothing else.
    - **The pair:** a far coarse fix may be the first of the pair (it sets the pending side); the second - coarse by the
      same rule, or a fine fix surely outside - must come **at least 10 minutes** after it (`COARSE_CONFIRM_GAP`; fine
      pairs keep `CONFIRM_GAP`'s 60 s). A fine fix on the zone's side in between drops the pending side, as today.
    - **The event's time is the first coarse fix's**: "left Hjem 18:05" says when the phone was first seen far away,
      which after a drive with the phone off is hours after she left. The event's words are unchanged; the server notes
      say so.
    - **`zones::seed` and `start_over`** (a new or moved zone takes the newest fix as its pending side, QA M3) never
      take a coarse fix: they read the newest **fine** fix (younger than `SEED_WITHIN`) or none.
    - **"Here now"** (QA M2): `Zone::here_now`'s freshness counts from the newest **fine** fix, so a cell fix never
      keeps "Here now" and "At Home since" alive; a far coarse fix sets a pending side, which already ends "Here now".
    - **The phone** (QA's correction): `sureSide` is reached only through `ZoneSeries` and `requestBalancedFix` - the
      balanced fused path, which a cell fix never takes -, so the phone half is a guard: `sureSide` mirrors the coarse
      rule (inside never, outside only far) so a later path can't start a crossing sync on a near cell fix.
    - A GLA-on phone's `network` fix is unchanged (Q8).
C11. **Leave-one-out for cells** (D16's way): before a GPS fix teaches, `cellFix` on the cells paired with it against
    the table as it was; whether the GPS fix lies inside its accuracy, per source (own, apple, mixed), with the summed
    error, 30 days; logged `loo cell own=3/3 apple=12/14 mixed=1/1 err own=800 apple=600 mixed=700`. This is how the
    Jelly Star tells what Apple's tower accuracy means and whether C8's accuracy is honest.

## 6. Privacy (C12)

- **Per ask, a cell says less than a BSSID.** A cell is public infrastructure covering hundreds of metres to kilometres,
  shared by everyone there; it can't be tied to a household or an address the way a home router can. What Apple learns
  from a cell call through the proxy: an area and the **operator** (MCC/MNC - "a Telia Norway subscriber near X"), never
  an IMSI, IMEI, phone number, signal or channel. Apple's ~25 surrounding towers blur which one was asked.
- **Linking through one exit** (QA L5): an attempt that asks for both makes its Wi-Fi call and its cell call close
  together through the same exit, so Apple can link the home's BSSIDs to the operator, and - as A6 says for Wi-Fi - all
  calls through one exit to each other over time; not to the family. Fresh SOCKS credentials per call (A7) give each
  call its own Tor circuit, which loosens the link but doesn't remove the timing.
- **The cache gets wider.** Cells are asked where Wi-Fi isn't known: on the road, at the cabin, abroad. Over two years
  the server's cache shows the family's trips coarsely, not just its places. The (i)'s last sentence already says the
  answers "show roughly where the phones have been".
- **Sealing protects cell rows less.** Cell identities are enumerable from public lists (Norway's are tens of thousands;
  OpenCellID has them), so whoever holds both the database and the key K can open every cell row cheaply, where a BSSID
  needs a guess per vendor prefix (2^24). The database or a backup alone still reveals nothing. Said plainly in the
  server notes.
- **No cache oracle for cells** (the user, 2026-11-24). Cell identities are enumerable, so a token holder - a family
  phone, or someone who took its token - could otherwise walk Norway's cells and read from the answers which ones the
  cache holds and how fresh, i.e. where the family's phones have been. C5 closes that: the same answer and the same
  answer time whether the row came from the cache or from Apple, no age, and 503 for every cells request while Apple
  can't be asked. What a walk learns is then what Apple would tell anyone. What is left: an Apple call that fails during
  one request (C5), and the server-wide Apple limit, which uncached cells fill and cached ones don't - visible only as
  the family's lookups being refused, not as an answer. The **day cap of 1000** is only a brake on such a walk (it also
  spends the family's Apple calls). BSSIDs keep step 2's answer (`fresh_months_left`): a BSSID can't be enumerated (2^24
  per vendor).
- **On the phone**: cell ids never logged, never in a report; rows keyed by the HMAC (D18). Nothing new for the kid.

## 7. The PWA's words (C13)

One switch for both (the user, Q1), on the Location page where it is:
- The switch: **"Oppslag hos Apple"** / **"Lookups at Apple"** (was "Wi-Fi-oppslag hos Apple"), "På"/"Av".
- The (i): "Når Googles posisjonstjeneste er av og telefonen ikke kjenner stedet selv, spør serveren Apple hvor
  Wi-Fi-nettene og mobilnettet rundt er. Apple ser nettene i nærheten - ikke telefonen eller navn. Svarene lagres på
  serveren og viser omtrent hvor telefonene har vært." / "When Google's location service is off and the phone doesn't
  know the place itself, the server asks Apple where the Wi-Fi networks and the mobile network around it are. Apple sees
  the nearby networks - not the phone or any name. The answers are kept on the server and show roughly where the phones
  have been."
- Unchanged: "Oppslagene går fra serverens egen adresse." while no proxy is set; the orange "Apple-oppslag virker ikke"
  and its (i). Nothing else: no cell line, no count, nothing on the kid's phone. Locate shows a cell fix as any fix -
  its circle and `vibb_cell`/`vibb_apple_cell` in the newest fix's title.

## 8. Contract and tests (C14)

**`server/testdata/wifi_lookup/contract.json`** (byte-identical in `launcher/app/src/test/resources/wifi_lookup/`), rows
added, none changed. Test PLMN **001-01** (ITU-T E.212's test network, never a real cell) and made-up coordinates in
every row:
- `rules`: `cells_max` 12, `cells_a_day` 1000, `cell_answer_after_s` 10 (proxied; 5 direct is the server's alone),
  `cell_row_days` 90, `cell_negative_days` 30.
- `request_cells` (the exact body).
- `cell_forms`: `{cell, valid}` - every radio's range edges (area 0, 1, 65533, 65534, 65535; NR's TAC 16777213 and
  16777214; GSM cid 65534 and 65535; WCDMA and LTE cid 268435454 and 268435455; NR cid 68719476734 and 68719476735), MNC
  of 1 and 4 digits, a leading zero in area or cid, upper case, `cdma`, a space, a sign, a 40-digit number.
- `answers`: `cells_fetched` (an LTE and an NR known, a GSM unknown, all from Apple; MNC echoed as `01` though Apple
  says 1; Apple's surrounding towers cached, never answered) and `cells_cached` (the same again from the cache) -
  **byte-identical bodies**, the server's test also checks both left at the answer time; `cells_stale` (rows fetched
  four months ago: the same body again, refreshed); `cells_two_spellings` (`01` and `001` of one cell: one row each, one
  position); `cells_paused_cached` and `cells_paused_uncached` (Apple paused: the same 503 with the pause's
  `retry_after_s` for both); `cells_day_cap` (the 1001st cell: 429 `limit` with `retry_after_s` to midnight);
  `bad_both_lists`, `bad_cells_empty`, `bad_cells_too_many`, `bad_cell_form`, `bad_cells_not_a_list`.
- `phone_answers`: `use_cells` (200: rows stored, no wait), `cells_unavailable` (503: the cell wait alone),
  `cells_limit` (429 with `retry_after_s`: the cell wait alone), `cells_limit_hourly` (429 without: the cell wait 15
  min), `cells_off` (409: both). `phone_rows`: a cell row lives 90 days from `now`, a negative 30.
- The step-2 rows are unchanged.

**`server.json`** (the server's alone, by `scripts/wifi-lookup-fixtures.py` - its encoder gains the tower messages):
`golden_cells` (one GSM, one LTE, one NR on 001-01, the per-standard counts of §1, with the header; checked once with
`protoc --encode` like step 2's) and `apple_cells` fake answers: each tower type, unknown, a missing location, a missing
TAC, duplicates, an NR cid over 32 bits, a negative cid (10-byte varint), accuracy 0 and 60 km, a CDMA field 21 and an
AP field 2 skipped and not cached, 201 towers, Apple's numeric MNC matched to the asked `01`.

**The launcher's own rows** (`wifi_places.json`, `PlacesTest`): `cell_reads` (inputs shaped as `CellInfo` to entries:
UNAVAILABLE, a null MNC, placeholder values, an NSA NR leg, one cell on two slots, two MNC spellings, an old timestamp,
an `UNAVAILABLE` dbm sorted last, 13 cells -> 12), `cell_fix` (one cell, two agreeing, two SIMs disagreeing, three with
a median outlier dropped, over 5 km none, the provider word), `cell_choice` (C7's own versus Apple), `cell_report` (C9:
still -> withheld or contradiction, inside a recent fine fix -> nothing, outside -> reported and not fresh, a `locate`
-> reported), `cell_ask` (8 positioned -> no ask, the cell wait, the Wi-Fi wait not stopping cells, the two requests
apart) and the C11 counts; **an attempt-level row** (QA H1): Wi-Fi off, no scan view, the cells unknown -> the
cells-only ask -> the answer stored -> `vibb_apple_cell` in the same attempt. `ZonePlanTest`: `sureSide` for coarse
fixes (never inside, outside only far). **Server:** `zones::tests` and `zone_vectors.json` (the server's alone) gain a
`provider` on new rows - a coarse fix inside by geometry is unsure; one 19 km past the edge with a 1 km accuracy is
unsure, 21 km is outside; 3 x a beats 20 km for a 10 km accuracy; a far coarse pair 9 minutes apart confirms nothing, 10
minutes does, at the first fix's time; a fine fix inside between them drops the pending side; `seed` with a coarse
newest fix seeds nothing; `here_now` with a fresh coarse fix and an old fine one is false. The route's contract rows;
the codec's `server.json` rows; a cell row sealed under its canonical form and found from Apple's numeric MNC; the day
cap; a cache hit and an Apple answer with the same body, both no sooner than the answer time (a paused clock); the
source test (no cell string in any `wifi_lookup` tracing line, as for BSSIDs).

## 9. The emulator and the Jelly Star

**The emulator** (14 and 17, with the user's OK) shows the wiring only. Its modem simulator has one fixed registered LTE
cell on a real PLMN (310260, or 311740 on the Norwegian-SIM image, `docs/testing/emulator.md` §6al) - its values read
with `adb shell dumpsys telephony.registry | grep -i cellinfo` at build time. It can show: the cell read with Location
on and an empty list with it off; the **cells-only** ask with Wi-Fi and scanning off on a learned miss with GLA off and
the switch on (the server's counts line `cells=1`); no ask with the switch off, with the network provider on, in
airplane mode; the 503 path and the cell wait alone, the Wi-Fi wait untouched; a cells answer arriving after the fixed
10 s. **The server's proxy points at a closed port for the run**, so no emulator cell (a real PLMN's numbers) ever
reaches Apple. It can't show: neighbours, NR, a second SIM, a move between cells, Apple's answer or its accuracy, a zone
20 km away.

**The Jelly Star needs a SIM** (it has none: 125 §1). With one (release RC, new lines in `new-phone-model.md` §2):
1. `dumpsys telephony.registry | grep -i -m5 cellinfo`: the registered cell's identity, which neighbours carry one,
   whether NR shows (NSA or SA), whether the timestamps move after a refresh, both slots' flags.
2. `WifiPlaces: cells read=` lines over a day; `apple asked=... cells=... got=...`; the server's `cells=`/`cell_calls=`.
3. Outdoors, Wi-Fi off, GPS blocked (a bag or a car): a `vibb_apple_cell` fix and its circle against the GPS fix after;
   the C11 counts after a few days (what Apple's tower accuracy means here).
4. A zone arrival by car: no crossing from a near cell fix, the arrival at the first fine fix; if a trip > 20 km comes
   up, "left" from two far cell fixes 10 minutes apart (C10).
5. A fjord or valley edge, if handy: the cell contradiction count stays at 0 on a still evening.

Before the first release, by hand (as step 2's L8): the `protoc --encode` check of `golden_cells`; one real cell lookup
through the proxy - the owner's own phone's serving cell (the owner's own area, never anyone else's).

## 10. Steps and size

**Step 3a - the fix of what is built (first commit, separable, no API or contract change):** the launcher's `cellFix`
passes `fresh = false`; a `vibb_cell` fix follows C9.2-C9.6 (never reported while `STILL`, not when it holds a recent
fine fix, never fresh, never a vouch, a `locate` still gets it); the server's `zones::COARSE_PROVIDERS` with C10's whole
rule (never inside, far outside only, the 10-minute pair at the first fix's time, `seed`/`start_over` and `here_now` on
fine fixes only); the phone's `sureSide` guard. Tests: C10's `zones::tests` and `zone_vectors.json` rows, the
`cell_report` rows for `vibb_cell`, `ZonePlanTest`'s guard row. `docs/notes/server/zones.md` and
`docs/notes/launcher/wifi-places.md` say it. Size: server ~80 lines + ~120 of tests, launcher ~60 + ~80.

**Step 3b - the cell lookup (one commit, server + launcher, the API in both dirs):** C1-C9, C11-C14, the contract rows,
`server.json` and the script, `docs/notes/...`, root `CLAUDE.md`'s `wifi_lookup` paragraph gains the rows. Server ~300
lines + ~250 of tests (route, codec, the day cap, the uniform answer and its time), launcher ~320 + ~270 (the read, the
cells-only ask, two waits, kind 5, `cellFix`, C9), the script ~120. GPL; GrapheneOS's MIT notice already in `NOTICE.md`
and the ported files covers the cell format (its file list there gains the `cell/` files).

Order: design QA of this revision if wanted -> 3a -> code QA -> 3b -> code QA -> the emulator wiring run (the user's OK)
-> the SIM on the Jelly Star.

## 11. Questions (**visible** = the user sees a difference)

Answered by the user on 2026-11-24: **Q1** one switch, "Oppslag hos Apple" (C13). **Q2** changed: a coarse fix may be
surely outside at `d - r >= max(3a, 20 km)`, the pair's second at least 10 minutes later, never inside, never a seed,
the event at the first coarse fix (C10). **Q3** 5 km (C8). **Q5** first 120 cells a phone a day, then **changed**: the
cap protects the server's cache, not Apple's data - so no cache oracle for cells (the same answer, no age, a fixed
answer time) and the cap raised to **1000 a day**, an abuse brake only (C5, C12). **Q6** an agreeing cell fix isn't
reported (C9.3). **Q10** (2026-11-24) yes: an explicit Locate shows a cell fix when nothing finer came, even while
still or agreeing with a recent fine fix (C9.5).

Taken as recommended unless the user says otherwise (no visible cost):
- **Q4** two Apple calls (C6); try a combined call once during the manual check and merge later only if Apple answers
  both.
- **Q7** WCDMA in the SCDMA fields, as GrapheneOS; unverified, Norway has no 3G.
- **Q8** GMS `network` fixes with GLA on unchanged in zones.
- **Q9** no timing advance or signal: TA measures to the mast, and Apple's position is where phones heard the cell.

Open: none.

## 12. Revision 2 - the design QA and the user's answers

H1 the cells-only ask (C4: no return on a null scan view; C8: the table read after the ask; C2: `Snapshot.cells`; §8's
attempt row). M1 two waits, each set only by its own request's answers; the day cap a 429 with its wait (C5, §8). M2
"Here now" from fine fixes (C10). M3 `seed`/`start_over` never coarse (C10). M4 placeholder cid ranges and their
`cell_forms` rows (C3, §8). L1 an `UNAVAILABLE` dbm sorts weakest (C2). L2 `MobileNetworkRuntime`'s slot mapping (C1).
L3 field 2 in a cell answer skipped and never cached (C6). L4 one cell per canonical form, each string echoed (C2, C5,
§8). L5 503 when Apple can't be asked, the link through one exit (C5, C12) - taken further by the user's change below.
L6 the (i)'s "mobilnettet rundt er" (C13). L7 Locate shows a cell fix (C9.5, Q10). L9 the `QUIET` back-off (C9.6). The
`sint32` wording (§1); the phone half of C10 (`sureSide` a guard). The confirmed bug of step 1 is step 3a (§10). The
user: Q1, Q2 (the cabin rule), Q3, Q5 (the cap is ours alone, then changed: no cache oracle for cells - the same answer
without an age, cached or from Apple, at a fixed time; 503 for every cells request while Apple can't be asked; cells in
a request of their own; the phone's rows a fixed 90 days; the cap 1000 a day as a brake), Q6, and "follow GrapheneOS's
cell logic where our zones and server don't need otherwise" - the median reference, every cell with an identity used
(not only registered ones), the 8-positioned threshold, the oldest time; our accuracy stays the more conservative one
(C8).
