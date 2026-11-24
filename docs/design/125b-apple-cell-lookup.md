# Design 125b: Apple's cell lookup (design 125 step 3)

Status: design, **revision 1**, 2026-11-24 (branch `cell-lookup-design`), read against `main` 88d11d5de. **Not built.**
Asked by the user on 2026-11-22 ("Legg det i køen"): the phone asks the family's server for the cells it hears and has
no position for, the same way as for BSSIDs; the server asks Apple through the same proxy into the same sealed cache,
under the same limits; the phone gets a coarse fix (hundreds of metres to kilometres) where no Wi-Fi is known.

**Why a note of its own** and not a section of design 125: 125 is at revision 3 with steps 1 and 2 built, its QA rounds
settled and its "what the build settled" in its status line; step 3 starts at revision 1 with its own design QA. A
build agent reads 125 (D10, §4a) and this note; 125's A10 and §9 point here. Numbering: **C1-C14** here, beside 125's
D (step 1) and A (step 2).

## 0. Short answer

GrapheneOS NetworkLocation already does this at the commit step 2 ported (474472e, MIT): its `apple_wps.proto` has
the cell fields, `AppleCellPositioningService.kt` the request and answer, `LocationReportingTask.kt` reads `CellInfo`
into identities. So step 3 is mostly step 2 again with another key: the phone reads its registered cells (and any
neighbour with a full identity), asks the server for those it has no row for - in the same request as the BSSIDs, on
the same learned miss -, the server answers from its sealed cache or makes a **separate** Apple cell call through the
proxy, and the phone keeps the answers as **Apple cell rows** beside step 1's own cell rows. The fix is a last resort,
never fresh, never a vouch, and **never moves a zone** (C10 - this also closes a gap in step 1's `vibb_cell`). One
switch (Q1), no migration, contract rows added to `wifi_lookup/contract.json`.

## 1. Apple's protocol for cells (sources and field numbers)

Sources: GrapheneOS `platform_packages_apps_NetworkLocation` at **474472e** (the commit step 2 already cites):
`proto/apple_wps.proto`, `cell/AppleCellPositioningService.kt`, `cell/CellTowerScanner.kt`,
`cell/CellPositioningServiceCache.kt`, `LocationReportingTask.kt`. Cross-checked against acheong08
`apple-corelocation-experiments` (0b136860e, `pb/BSSIDApple.proto`), which reverse-engineered LTE alone: it agrees on
LTE's request field **25**, answer field **22** and the tower's fields 1-7. Both read 2026-11-24.

| What | Request (`ALSLocationRequest`) | Count of surrounding | Answer (`ALSLocationResponse`) |
|---|---|---|---|
| GSM (`GsmCellTower`) | 1 | 3 | 1 |
| LTE (`LteCellTower`) | 25 | 26 | 22 |
| "SCDMA" (`ScdmaCellTower`) - GrapheneOS sends **WCDMA** here | 27 | 28 | 23 |
| NR (`Nr5GCellTower`) | 29 | 30 | 24 |
| CDMA / EV-DO (not sent) | 21 / 22 | 23 / 24 | 21 |
| Wi-Fi (step 2, unchanged) | 2 | 4 | 2 |
| `meta` (macOS's, as step 2) | 33 | | |

The towers: GSM and SCDMA `{mcc 1, mnc 2, cell_id 3, lac_id 4, location 5, arfcn 11, psc 12}`, all int32; LTE `{mcc
1, mnc 2, cell_id 3 (int32, the 28-bit ECI), tac_id 4, location 5, uarfcn 6, pid 7}`; NR `{mcc 1, mnc 2, cell_id 3
(**int64**, the 36-bit NCI), tac_id 4, location 5, nrarfcn 6}`. `location` is step 2's `ALSLocation {latitude 1,
longitude 2, accuracy 3}`, x 1e-8, -18000000000 = unknown. GrapheneOS sends only the identity (1-4), never the channel,
PCI or signal; so do we. MCC and MNC are numbers (MNC "01" goes as 1).

**Quirks GrapheneOS found** (kept as they are): Apple sends about 19 more surrounding towers than asked, and asking for
none of a standard that is in the request makes it send ~120 - so each standard asked gets `max(1, (25 - 19) x its
share of the asked towers)` (4 instead of 25 while throttled); an answer of 10 or more towers throttles the next 10 s;
at most 12 towers asked. **Not verified anywhere:** that WCDMA belongs in the SCDMA fields (Norway has no 3G; low
stakes, Q7), and that one call may carry both Wi-Fi and cells (GrapheneOS never does - C5 keeps them apart, Q4).
acheong08 reads field 3/4 as `sint32` where GrapheneOS has `int32`; GrapheneOS's form is the one that works in step 2.

## 2. What the phone has today

Step 1 (D10, built): `WifiPlaces.servingCell` takes the first `isRegistered` cell from `getAllCellInfo()`, one
`requestCellInfoUpdate` when it is over 60 s old (3 s wait), keys it `HMAC("cell|mcc|mnc|area|cid")`; own cell rows
(kind 2) range 500 m-10 km, a move at 20 km; `cellFix` gives `vibb_cell` from that one row (3 occasions, <= 5 km) **with
`fresh = true`**; `cellVetoes` rejects a Wi-Fi fix 2 km past the cell's range. Step 2 (A3-A9, built): the route, the
sealed cache, the phone's Apple rows (kind 4). The Jelly Star has no SIM (`mCellInfo=[]` on both slots, 125 §1).

## 3. Decisions - what the phone reads

C1. **The read** (Android 14-17; every API below is <= 30). `getAllCellInfo()` returns the **cached** cells of every
    slot (AOSP `PhoneInterfaceManager.getCachedCellInfo` loops over all phones; apps targeting Q+ never trigger a modem
    poll with it). When the newest registered cell is over 60 s old (`CellInfo.getTimestampMillis()`, elapsed since
    boot), one `requestCellInfoUpdate` per **active subscription** (`createForSubscriptionId`: the call refreshes only
    its subscription's modem - step 1 asks only the default one, a one-line change), waiting 3 s as today. The modem is
    asked at most every 2 s with the screen on and no Wi-Fi (or charging), else every 10 s - a request inside that
    returns the last list (`ServiceStateTracker.requestAllCellInfo`, `DeviceStateMonitor.computeCellInfoMinInterval`,
    read 2026-11-24): one refresh per attempt is all we ask. **Permissions:** `ACCESS_FINE_LOCATION` (and background,
    both self-granted) with Location on - with Location off `LocationAccessPolicy` gives `DENIED_SOFT`, an empty list;
    the fix window holds Location on. Not `READ_PHONE_STATE`. Device Owner changes nothing here. No SIM, airplane mode,
    radio off, an invalid subscription (`IllegalArgumentException`, caught): an empty list, an ordinary miss.
C2. **Which cells.** Every `CellInfo` with a **full identity** (C3's form): the registered ones (one per SIM;
    `getCellConnectionStatus()` `PRIMARY_SERVING`), `SECONDARY_SERVING` (carrier aggregation) and neighbours that carry
    one (some modems give GSM neighbours a LAC and CID; LTE and NR neighbours usually carry only PCI and channel and are
    skipped). Registered first, then by `getCellSignalStrength().dbm`; duplicates (one cell on both slots) once; at most
    **12**. A cell whose time is still more than 60 s before the attempt's start after the refresh is not used or asked
    (a modem that hands back an old list).
    **NR:** in NSA (EN-DC) the LTE anchor is the registered cell and the NR leg comes as a `CellInfoNr` without NCI or
    TAC - skipped; in SA the registered `CellIdentityNr` has the 36-bit NCI and the 24-bit TAC and is used.
    **Guards** against values modems send for "unknown": `CellInfo.UNAVAILABLE`/`UNAVAILABLE_LONG`, a null MCC or MNC
    string, 0, and the reserved/placeholder areas (C3's ranges). What the Jelly Star's MediaTek modem sends - the
    registered flag on both slots, neighbour identities, timestamps that move, NR - is §9's SIM check; the guards hold
    whatever it sends. TD-SCDMA and CDMA identities are skipped.
    Logs: counts only (`WifiPlaces: cells read=4 usable=2 registered=1 nr=0 stale=0`).
C3. **The wire form** of a cell: `"<radio>:<mcc>:<mnc>:<area>:<cid>"`, radio `gsm|wcdma|lte|nr`, MCC 3 digits
    (`001`-`999`), MNC 2-3 digits as the network gives it (`mncString`), area and cid decimal without leading zeros,
    in these ranges (3GPP TS 23.003 reserves area 0 and `...FFFE`; `...FFFF` and 0 are common "unknown" fills):

    | radio | area | cid |
    |---|---|---|
    | `gsm` | LAC 1..65533 | 1..65535 |
    | `wcdma` | LAC 1..65533 | 1..268435455 (28 bits) |
    | `lte` | TAC 1..65533 | 1..268435455 (ECI, 28 bits) |
    | `nr` | TAC 1..16777213 (24 bits) | 1..68719476735 (NCI, 36 bits) |

    The phone sends only what passes; the server refuses anything else (C4). Example: `lte:242:01:4711:12345678`.

## 4. Decisions - the ask, the route, Apple and the cache

C4. **When the phone asks** - A3's rule, unchanged: the switch on, the `network` provider off, Location on, a network
    up, a **learned miss** in a fresh-fix attempt (heartbeat, `locate`, crossing, low-battery), beside GPS, never from a
    teaching or passive fix, never periodic. On that miss the request carries the BSSIDs A3 picks **and** the usable
    cells (C2) with no trusted own cell row (3 occasions), no live Apple cell row, no negative and no tombstone (C7) -
    either list may be empty, not both. So an area's cells are asked once per three months, and only where Wi-Fi
    didn't give a fix that stands alone. Never with GLA on (H1 of 125).
C5. **The route** stays `POST /api/devices/wifi-lookup` (a rename would touch both dirs, the docs and the PWA for
    nothing): `{"bssids"?: [...], "cells"?: [...]}` - each list optional, 1..40 BSSIDs and **1..12 cells** when present,
    at least one list; any other key, an empty list or one bad entry is a 400 (hand-parsed as today, 4 KB).
    **200** adds `"cells": [{"cell", "lat", "lng", "acc_m", "fresh_months_left"}]` and `"unknown_cells": [...]` -
    present only when the request had cells (and `aps`/`unknown` only when it had BSSIDs, so every step-2 row stays
    as it is); `cell` echoes the phone's own string; `retry_after_s` covers both lists. 409/429/503 as A4 (503 only when
    the cache answered nothing of either list). One request is one of the phone's 30 lookups an hour, whatever it
    carries. **A per-phone day cap: 120 cells answered per UTC day** (memory only, like the hourly count): past it the
    cells are left out of both lists and `retry_after_s` runs to midnight, the BSSIDs are still answered (C12 says why;
    Q5). The phone's action per status is A4's (`phone_answers` gains one row).
C6. **Apple's side.** Cells go in **their own Apple call** in GrapheneOS's exact shape (the towers, the per-standard
    surrounding counts of §1, `meta`; no `number_of_surrounding_wifis`), never inside the Wi-Fi call; a lookup that
    needs both makes two calls in a row under the one gate (Wi-Fi first), each counted in the 60 an hour and 10 in 10
    minutes. Field mapping: `gsm` -> 1/3, `wcdma` -> 27/28, `lte` -> 25/26, `nr` -> 29/30, written in field-number
    order (protoc's, so the golden hex matches byte for byte). Its own throttle (10 towers -> 10 s at 4). The answer
    walks fields 1, 22, 23 and 24 (21 skipped) with step 2's bounded reader (A8): a tower missing MCC, MNC, area or cid,
    a negative value or one outside its radio's range is dropped; a missing location or -18000000000 is **unknown**;
    accuracy 1..**50,000 m** (towers reach further than APs; APs keep 1..5,000) else dropped; at most 200 towers; the
    first of duplicates wins. The same URL, headers, proxy rule (no silent direct call), pauses and
    `integration_health` - a cell call's failure is Apple's failure like any other, and the orange "Apple lookups
    aren't working" covers both. The sweep's counts line gains `cells=` and `cell_calls=`.
C7. **The server's cache: the same table, key and months.** A cell row lives in `wifi_lookup_aps` beside the APs: its
    id the HMAC of the **canonical form** with MNC as a number (`lte:242:1:4711:12345678` - Apple's form, so the
    surrounding towers it sends match what phones ask), the canonical form also the seal's AAD. No column says "cell";
    a BSSID and a cell form can't collide. Fresh 3 months, a negative until its calendar month ends, `CACHE_LIFETIME`
    (2 years unused), the shared caps (200,000 / 20,000 - cells are a few thousand at most). **No migration.**
    **The phone's Apple cell rows:** kind **5** (`APPLE_CELL`) in D18's table, the same 71-byte layout (no version bump,
    nothing relearned), keyed by step 1's cell key (`HMAC("cell|mcc|mnc|area|cid")`, the radio not in it - an LTE ECI
    and an NR NCI with the same digits under one PLMN and TAC isn't expected, and own and Apple rows of one cell must
    meet); `rangeM = max(500 m, acc_m)`; live until the server's row stops being fresh (A9's `appleUntilMin`), a stale
    row a day, a negative until the next UTC month; at most **500** (least recently seen out); dropped with the switch
    off and every D19 clear. **Own versus Apple per cell** - A9's rule: an untrusted own row (< 3 occasions) doesn't
    block Apple's; trusted and agreeing (centres within both ranges) -> the smaller range (a tie: own); disagreeing ->
    own, and Apple's becomes a 90-day tombstone, as does an own teach that moves the cell (D10's 20 km).

## 5. Decisions - the fix, the still rule, zones

C8. **The cell fix** (pure `cellFix`, replacing step 1's one-row rule): the read cells with a chosen row (C7).
    - A **registered** cell must have one (a neighbour alone gives none). The reference is the registered cell with the
      smaller range; two SIMs' registered cells must lie within both ranges of each other, else no fix. Any other cell
      further from the reference than both ranges is dropped (a far or wrong row).
    - Position: the mean weighted by `1 / range^2`. **Accuracy: `max(smallest used range, weighted RMS distance of the
      used cells from the fix)`**, at least 500 m; over `CELL_FIX_MAX_M` (5 km, D10's; Q3) no fix. A range is the area
      a phone hearing that cell is in (GrapheneOS reads Apple's tower accuracy so), so this is wider than 125's 68 %;
      C11's counts tell how much.
    - Time: the oldest used cell's `getTimestampMillis()` on the wall clock. Provider: **`vibb_apple_cell`** (15
      characters, within `models::location_provider`'s 16) when any used position is Apple's, else `vibb_cell`.
    - The veto (D10) reads the chosen rows: a registered cell, own trusted or live Apple, more than its range + 2 km
      from a learned or `vibb_apple` fix rejects that fix (counted, `cell_vetoes=`).
C9. **How it joins the fixes - never over a fine one.**
    1. **Last in the attempt**, as D14 has it: GPS, the learned fix, the Apple Wi-Fi fix, then the cell fix, then the
       last-known. Any of the others in the same attempt wins (keep-the-better; a cell fix is >= 500 m).
    2. **Still means still:** while `motion()` is `STILL` a cell fix is never reported; further than both accuracies +
       2 km from the vouched fix it counts as a **cell contradiction** (CE prefs, counts only, 30 days).
    3. **It doesn't override a recent fine fix:** when the last fresh fix is under 4 h old (124's cap) and lies inside
       the cell fix's circle (`d <= accCell + accFine`), nothing is reported - the PWA keeps the fine fix and shows its
       age (Q6).
    4. Otherwise it is reported with its provider, accuracy and time, and it is **never fresh** (`afterTry`, the
       fresh-fix stamp: `Ask.notFresh`, so the next beat tries GPS again), never a vouch (not in
       `VOUCHING_PROVIDERS`), never renews "Hasn't moved since", never teaches, never the new side of 124's D7. **This
       changes step 1:** its `cellFix` passes `fresh = true` today, so a `vibb_cell` fix clears the motion flags and
       can carry a still night at kilometres.
C10. **Zones never move on a cell fix** (the user's rule: "never move a zone on a cell fix alone"). Step 1 has a gap:
    `zones::side` is `d - a >= r`, so a `vibb_cell` fix of 1 km 3 km from home is **surely outside** and, with a
    second, raises "left Hjem" - from a cell that may sit across a fjord. **Server:** `zones::step` treats a fix whose
    provider is in `zones::COARSE_PROVIDERS` (`vibb_cell`, `vibb_apple_cell`) as unsure: it moves the cursor and
    nothing else - no pending side, no confirmation; a side changes only on two sure fine fixes, as today
    (`evaluate_stored` reads `device_locations.provider`, stored since migration 0093). **Phone:** `sureSide` returns
    null for the same providers, so a series check's cell fix never starts a crossing sync. A GLA-on phone's
    `network` fix is unchanged (Q8). Visible cost (Q2): where only cells answer, an arrival or a leaving waits for a
    GPS or Wi-Fi fix.
C11. **Leave-one-out for cells** (D16's way): before a GPS fix teaches, `cellFix` on the cells paired with it against
    the table as it was; whether the GPS fix lies inside its accuracy, per source (own, apple, mixed), with the summed
    error, 30 days; logged `loo cell own=3/3 apple=12/14 mixed=1/1 err own=800 apple=600 mixed=700`. This is how the
    Jelly Star tells what Apple's tower accuracy means and whether C8's accuracy is honest.

## 6. Privacy (C12)

- **Per ask, a cell says less than a BSSID.** A cell is public infrastructure covering hundreds of metres to
  kilometres, shared by everyone there; it can't be tied to a household or an address the way a home router can.
  What Apple learns from a cell call through the proxy: an area and the **operator** (MCC/MNC - "a Telia Norway
  subscriber near X"), never an IMSI, IMEI, phone number, signal or channel; not linkable to the family, but - as A6 says
  for Wi-Fi - linkable to the other calls through the same exit over time. Apple's ~25 surrounding towers blur which
  one was asked.
- **The cache gets wider.** Cells are asked where Wi-Fi isn't known: on the road, at the cabin, abroad. Over two years
  the server's cache shows the family's trips coarsely, not just its places. The (i)'s last sentence already says the
  answers "show roughly where the phones have been".
- **Sealing protects cell rows less.** Cell identities are enumerable from public lists (Norway's are tens of
  thousands; OpenCellID has them), so whoever holds both the database and the key K can open every cell row cheaply,
  where a BSSID needs a guess per vendor prefix (2^24). The database or a backup alone still reveals nothing. Said
  plainly in the server notes.
- **A device token can enumerate.** The route answers any named cell from the cache, and over the server-wide limit it
  answers the cache alone - so a stolen token could walk Norway's cells (12 a lookup x 30 an hour = 360 an hour, the
  whole country in days) and map where the family's phones have been. **C5's day cap (120 cells a phone a day)** makes
  that a year's work while a normal day asks a handful (a long trip a few dozen). BSSIDs don't need it: 2^24 per vendor.
- **On the phone**: cell ids never logged, never in a report; rows keyed by the HMAC (D18). Nothing new for the kid.

## 7. The PWA's words (C13)

One switch for both (Q1), on the Location page where it is:
- The switch: **"Oppslag hos Apple"** / **"Lookups at Apple"** (was "Wi-Fi-oppslag hos Apple"), "På"/"Av".
- The (i): "Når Googles posisjonstjeneste er av og telefonen ikke kjenner stedet selv, spør serveren Apple hvor
  Wi-Fi-nettene og mobilmastene rundt står. Apple ser nettene og mastene i nærheten - ikke telefonen eller navn.
  Svarene lagres på serveren og viser omtrent hvor telefonene har vært." / "When Google's location service is off and
  the phone doesn't know the place itself, the server asks Apple where the Wi-Fi networks and mobile masts around it
  are. Apple sees the nearby networks and masts - not the phone or any name. The answers are kept on the server and
  show roughly where the phones have been."
- Unchanged: "Oppslagene går fra serverens egen adresse." while no proxy is set; the orange "Apple-oppslag virker ikke"
  and its (i). Nothing else: no cell line, no count, nothing on the kid's phone. Locate shows a cell fix as any fix -
  its circle and `vibb_cell`/`vibb_apple_cell` in the newest fix's title.

## 8. Contract and tests (C14)

**`server/testdata/wifi_lookup/contract.json`** (byte-identical in `launcher/app/src/test/resources/wifi_lookup/`),
rows added, none changed. Test PLMN **001-01** (ITU-T E.212's test network, never a real cell) and made-up coordinates
in every row:
- `rules`: `cells_max` 12, `cells_a_day` 120.
- `request_cells` (cells only) and `request_both` (the exact bodies).
- `cell_forms`: `{cell, valid}` - every radio's range edges (area 0, 1, 65533, 65534; NR's 24-bit TAC; LTE's 28-bit and
  NR's 36-bit cid and one past), MNC of 1 and 4 digits, a leading zero in area or cid, upper case, `cdma`, a space, a
  sign, a 40-digit number.
- `answers`: `cells_fresh` (an LTE and an NR known, a GSM unknown; MNC echoed as `01` though Apple says 1; Apple's
  surrounding towers cached, never answered), `cells_cached`, `both` (the four lists), `cells_day_cap` (the BSSIDs
  answered, the cells in neither list, `retry_after_s` to midnight), `bad_cells_empty`, `bad_cells_too_many`,
  `bad_cell_form`, `bad_neither_list`, `bad_cells_not_a_list`.
- `phone_answers`: `use_cells`. `phone_rows`: cell rows and their `until` (A9's months).

**`server.json`** (the server's alone, by `scripts/wifi-lookup-fixtures.py` - its encoder gains the tower messages):
`golden_cells` (one GSM, one LTE, one NR on 001-01, the per-standard counts of §1, with the header; checked once with
`protoc --encode` like step 2's) and `apple_cells` fake answers: each tower type, unknown, a missing location, a
missing TAC, duplicates, an NR cid over 32 bits, a negative cid (10-byte varint), accuracy 0 and 60 km, a CDMA field
21 skipped, 201 towers, Apple's numeric MNC matched to the asked `01`.

**The launcher's own rows** (`wifi_places.json`, `PlacesTest`): `cell_reads` (inputs shaped as `CellInfo` to entries:
UNAVAILABLE, a null MNC, placeholder areas, an NSA NR leg, one cell on two slots, an old timestamp, 13 cells -> 12),
`cell_fix` (one cell, two agreeing, two SIMs disagreeing, a far neighbour dropped, over 5 km none, the provider word),
`cell_choice` (C7's own versus Apple), `cell_report` (C9: still -> withheld or contradiction, inside a recent fine fix
-> nothing, outside -> reported and not fresh) and the C11 counts. `ZonePlanTest`: `sureSide` null for both coarse
words. **Server:** `zones::tests` and `zone_vectors.json` (the server's alone) gain a `provider` on new rows - a coarse
fix sure by geometry changes nothing, a GPS-set pending side is not confirmed by a cell fix; the route's contract rows;
the codec's `server.json` rows; a cell row sealed under its canonical form and found from Apple's numeric MNC; the day
cap; the source test (no cell string in any `wifi_lookup` tracing line, as for BSSIDs).

## 9. The emulator and the Jelly Star

**The emulator** (14 and 17, with the user's OK) shows the wiring only. Its modem simulator has one fixed registered
LTE cell on a real PLMN (310260, or 311740 on the Norwegian-SIM image, `docs/testing/emulator.md` §6al) - its values
read with `adb shell dumpsys telephony.registry | grep -i cellinfo` at build time. It can show: the cell read with
Location on and an empty list with it off; the request carrying `cells` (the server's counts line `cells=1`) on a
learned miss with GLA off and the switch on; no ask with the switch off, with the network provider on, in airplane
mode; the 503 path and the phone's wait. **The server's proxy points at a closed port for the run**, so no emulator
cell (a real PLMN's numbers) ever reaches Apple. It can't show: neighbours, NR, a second SIM, a move between cells,
Apple's answer or its accuracy.

**The Jelly Star needs a SIM** (it has none: 125 §1). With one (release RC, new lines in `new-phone-model.md` §2):
1. `dumpsys telephony.registry | grep -i -m5 cellinfo`: the registered cell's identity, which neighbours carry one,
   whether NR shows (NSA or SA), whether the timestamps move after a refresh, both slots' flags.
2. `WifiPlaces: cells read=` lines over a day; `apple asked=... cells=... got=...`; the server's `cells=`/`cell_calls=`.
3. Outdoors, Wi-Fi off, GPS blocked (a bag or a car): a `vibb_apple_cell` fix and its circle against the GPS fix
   after; the C11 counts after a few days (what Apple's tower accuracy means here).
4. A zone arrival by car: no crossing from a cell fix, the arrival at the first fine fix (C10).
5. A fjord or valley edge, if handy: the cell contradiction count stays at 0 on a still evening.

Before the first release, by hand (as step 2's L8): the `protoc --encode` check of `golden_cells`; one real cell
lookup through the proxy - the owner's own phone's serving cell (the owner's own area, never anyone else's).

## 10. Size and steps

One commit, server + launcher (the API in both dirs, `docs/notes/...` updated, root `CLAUDE.md`'s `wifi_lookup`
paragraph gains the rows): server ~300 lines + ~250 of tests (route, codec, the day cap, `zones::step`), launcher
~300 + ~250 (the read, the ask, kind 5, `cellFix`, C9, `sureSide`), the script ~120. GPL; GrapheneOS's MIT notice
already in `NOTICE.md` and the ported files covers the cell format (its file list there gains the `cell/` files).
Order: design QA of this revision -> the user's answers (§11) -> build -> code QA -> the emulator wiring run (the
user's OK) -> the SIM on the Jelly Star.

## 11. Open questions (each with a recommendation; **visible** = the user sees a difference)

- **Q1 (visible) - one switch or two.** Recommended: one switch, renamed "Oppslag hos Apple" (C13); a parent who turned
  on the Wi-Fi lookup now also sends cells (today: the owner's phone only). The other way: a second switch "Mobilmaster
  hos Apple", off by default - one more line on the page for little.
- **Q2 (visible) - zones and coarse fixes.** Recommended: a cell fix never sets or confirms a side (C10), so an arrival
  or a leaving waits for a GPS or Wi-Fi fix where only cells answer. The other way: a cell fix may confirm a side a
  fine fix proposed - not recommended (a mast across a fjord).
- **Q3 (visible) - the largest cell fix.** Recommended: keep D10's 5 km for both sources; in open country with only
  big cells the PWA then shows the last fine fix's age. The other way: 10 km for Apple cells, big circles on Locate.
  Revisit with C11's counts.
- **Q4 - one Apple call or two.** Recommended: two (C6), the shapes GrapheneOS runs; try a combined call once during
  the manual check, and merge them later only if Apple answers both.
- **Q5 (visible) - the day cap of 120 cells per phone.** Recommended: yes (C12's enumeration); a very long trip day may
  hit it and leave new cells without a fix until midnight (the age shows).
- **Q6 (visible) - a cell fix that agrees with a recent fine fix.** Recommended: not reported (C9.3), so the trail
  keeps the fine point and its age instead of a coarse point. The other way: report it (a "still around here" dot
  with a 1 km circle) - noisier, and it would look fresh without being so.
- **Q7 - WCDMA in the SCDMA fields.** Recommended: as GrapheneOS sends it; unverified, and Norway has no 3G.
- **Q8 - GMS `network` fixes with GLA on.** Recommended: unchanged (C10 names our own words only); treating every fix
  over some accuracy as unsure is another design.
- **Q9 - timing advance or signal to sharpen the fix.** Recommended: not now; TA measures to the mast, and Apple's
  position is where phones heard the cell, not the mast.
