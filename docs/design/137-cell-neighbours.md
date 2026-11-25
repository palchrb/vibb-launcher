# Design 137: cell neighbours matched against Apple's cell answer

Status: design, **revision 1**, 2026-11-25 (branch `design-137`), read against `main` 4b67b3fe8. Item **5b** on the
owner's locked list ("ta det før 136"). Not built. Numbering **N1-N16**, beside 125b's C1-C14.

Asked by the owner on 2026-11-25, after a live probe: the phone hears its neighbour cells by PCI and channel only, and
Apple's cell answer already carries the neighbours around the asked cell with full ids, positions, channel and PCI. Match
the two, weigh by accuracy and signal, and learn the phone's own neighbour rows from GPS - a tighter cell fix that stays
**a last resort: never fresh, never a vouch, never "Hasn't moved since"** (125b C9 unchanged).

Read with: design 125 (D10, D16, D18), 125b (C1-C14, all of it), 131 (`FixClass`), 134 (D15's `cellElsewhere`). The
code: `server/src/integrations/wifi_lookup/{cells,cache,route}.rs`, `launcher/.../places/{CellPlan,PlacesPlan,
WifiPlaces}.kt`, `contracts/integrations/wifi_lookup/contract.json`.

## 0. Short answer

- **Apple's side:** read LTE's tower fields 6 (EARFCN) and 7 (PCI) and WCDMA's 11/12 (UARFCN, PSC); NR's field 6
  (NR-ARFCN) has no PCI beside it. We still send only the identity (as GrapheneOS). The server keeps **each asked
  cell's neighbour list** (Apple's positioned surrounding towers with their channel and PCI) sealed in the asked cell's
  row (one migration, one column), stops keeping surrounding towers as rows of their own, and answers a new **`near`**
  list beside `cells` and `unknown` - built from the store on both paths, so the no-oracle rule (125b C5) holds.
- **The phone:** reads each neighbour's radio, channel, PCI and signal; stores Apple's `near` rows as kind-5 rows with a
  **tag** (keyed hash of MCC, radio, channel, PCI) in their unused `network` field; matches a heard neighbour to the
  **one** row with its tag near the registered cell (none or two: skipped); learns **own neighbour rows** (new kind 6,
  keyed by the registered cell and the neighbour's tag) from teaching GPS fixes, so it works without Apple too.
- **The fix:** C8's median outlier rule and 5-km ceiling stay; the weights gain a signal factor; with three or more
  positioned cells the accuracy may go below the smallest range (floor 300 m, a factor `K = 1.5` that the leave-one-out
  counts tune). Same providers, same report rules, zones untouched, 134's mismatch untouched.
- **Honest gain:** in town a few hundred metres off a cell fix's error and a circle of roughly 0.8-1.2 km instead of
  1.4-1.5 km; in the countryside little until the phone has learned its own routes. It matters where Wi-Fi gives no fix
  (roads, the edge of town, a car); it does nothing without a SIM.

## 1. The evidence (the probe of 2026-11-25)

One real LTE cell of a Norwegian operator (a public tower, from CellMapper; its identity stays out of the repo), asked of
`gs-loc.apple.com/clls/wloc` through Tor, the request built exactly as `cells::request` builds it (one LTE tower, field 26
= 6):

- **The asked cell:** known, accuracy (location field 3) 1529 m; location field 4 (`location_type`) 0; fields **11 = 914**
  and **12 = 5045** present - GrapheneOS's `apple_wps.proto` names them `score` and `reach`; their meaning is unverified.
  **No EARFCN or PCI** on the asked cell (we send none).
- **Six surrounding cells with full ids**, positions, accuracy ~1414 m (one 6755 m), **and tower fields 6 and 7**: three
  EARFCNs in band 3, PCIs all different; one a sibling sector of the same eNB 0.7 km off, the rest 0.7-2.7 km away. Six
  is exactly the count asked.
- **About 20 entries with cid -1** (TAC areas, accuracy 149,000 m): the "~19 more than asked" GrapheneOS saw. The server
  already drops them (a negative cid is out of range; the accuracy is over 50 km too).
- Five stale OpenCellID cells (one sample each) were unknown to Apple; their answers held only the id-less areas.
- **The Jelly Star** (MediaTek, no SIM): `cells read=3 usable=0 registered=0` - the modem lists neighbours (PCI, EARFCN,
  RSRP) even without a SIM, but none has a full id and there is no registered cell to anchor them.

**GrapheneOS at 474472e** (checked 2026-11-25 through GitHub's API): `apple_wps.proto` declares LTE `uarfcn 6`, `pid 7`,
NR `nrarfcn 6`, GSM `arcfn 11` (sic) / `psc 12`, SCDMA `arfcn 11` / `psc 12`, but `AppleCellPositioningService` never
reads them; `estimateLocationCell` keeps only `CellInfo`s whose identity has MCC, MNC, area and cid, so an LTE neighbour
(PCI and EARFCN only) is dropped; Apple's surrounding towers go into a 15-minute, 1000-entry LRU, which helps only when a
neighbour is heard with its full id (rare on LTE). On an LTE network GrapheneOS's cell fix is therefore the registered
cell's alone. Its throttle counts every tower of the answer, id-less ones included (the probe's 27 entries would throttle
every call); ours counts the parsed towers (125b C6), unchanged.

## 2. Decisions - Apple's protocol and the server

N1. **What the server reads** (`cells::answer`). A tower gains `channel: Option<u32>` and `pci: Option<u16>`:

    | Answer field | Tower | channel | pci | Ranges kept (else unknown) |
    |---|---|---|---|---|
    | 22 | LTE | 6 (EARFCN) | 7 | EARFCN 0..262143, PCI 0..503 |
    | 23 | SCDMA, read as WCDMA (125b Q7) | 11 (UARFCN) | 12 (PSC) | UARFCN 0..16383, PSC 0..511 |
    | 24 | NR | 6 (NR-ARFCN) | - (no field known) | NR-ARFCN 0..3279165 |
    | 1 | GSM | not read | not read | (field 12's meaning for GSM unknown; 2G is blocked on the kid's phone, design 126 A5) |

    All are `int32`: Apple's -1 (a 10-byte varint) or any value past the range or over `i32::MAX` is **unknown**, never
    an error - the tower is kept with its position. A wire type other than varint in fields 6, 7, 11, 12 is `Malformed`,
    as for fields 1-5. Location fields 4, 11 and 12 stay skipped (unknown fields); the build's tests carry the probe's
    shape so a later reader can use `reach` (Q6).
N2. **What the server sends Apple: unchanged.** Only the identity (fields 1-4), as GrapheneOS. Sending the registered
    cell's EARFCN/PCI would gain nothing - the phone reads its own registered cell's channel and PCI (N7) - and would
    widen what leaves the phone (the request today carries only the ids the phone asks for, 125b C12) and change the
    golden request that `protoc` checked. **The count fields** (3, 26, 28, 30, `int32`, plain varints): the probe shows
    Apple answers the count in full-id neighbours and adds ~20 id-less areas on top. Today's `max(1, (25 - 19) x share)`
    gives 6 for a lone LTE cell. **Proposed: `max(1, 12 x share)`** (`NEAR_ASKED = 12`; throttled: 1 each, as today's
    `TOWERS_THROTTLED` gives), so a lone registered cell brings 12 neighbours - more of what the phone hears is in the
    table. Unverified that Apple honours 12 (Q1): one call through Tor before the first release, as 125b §9's manual
    check; if it answers fewer or worse, stay at 6. An answer of 13 parsed towers throttles the next 10 s (unchanged
    rule); cells calls are minutes apart, so this costs nothing in practice. `golden_cells` changes with the count and is
    checked with `protoc --encode` again.
N3. **The cache: each asked cell holds its neighbour list.** Apple's surrounding towers are one flat list for the whole
    call, and a cached answer must be the same as a fetched one (125b C5), so the list is kept **per asked cell**:
    - **`near`** (migration, N4): for each asked cell Apple placed, the call's positioned surrounding towers (not an asked
      one) **within 15 km of that cell's position, the 12 nearest**, each `{canonical, lat, lng, acc_m, channel?, pci?}`,
      sealed AES-256-GCM under a key expanded from the seal PRK with the info `near|<canonical>` (AAD the same), so it
      opens only with `K` and that cell's id - like the position. An empty list is an empty sealed list, never NULL.
    - **Surrounding towers are no longer rows of their own** (125b C7's "Apple's surrounding towers cached" goes). They
      were never answered, and under N5 a cell row without a neighbour list must be asked anyway, so they bought nothing;
      fewer rows is also a smaller cache of where the family has been. Wi-Fi's surrounding APs are unchanged.
    - **A cell row with no `near`** (from before the migration) is **wanted** (`Cached::wanted`): Apple is asked once
      more, so every answered cell has a list. Negatives, months, the caps and `CACHE_LIFETIME` are unchanged; the
      tombstones are the phone's (C7) and unchanged.
N4. **The migration.** `ALTER TABLE wifi_lookup_aps ADD COLUMN near BLOB` (NULL for Wi-Fi rows and old cell rows; the
    rows stay, they are just asked again). The number is the next free one on `main` **when this is built**: `main` is
    at 0179; phase D holds 0180, design 135 0181, design 136 0182. The owner put 5b **before 136**, so if 137 is built
    before 136 it takes **0182** and 136 moves to 0183; built after, it takes **0183**. Never a gap, never a lower number
    after a higher one has run (`server/CLAUDE.md`). The server's database is real data: the migration only adds a
    column.
N5. **The route's answer** (`POST /api/devices/wifi-lookup`, a `{"cells"}` body; nothing else changes):

    ```json
    {"cells": [{"cell", "lat", "lng", "acc_m"}], "unknown": [cell],
     "near": [{"cell", "lat", "lng", "acc_m", "ch"?, "pci"?}]}
    ```

    - `near` is **always present** (maybe empty): the union of the asked known cells' stored lists, an asked cell left
      out, one entry per canonical form (the first in canonical order wins), **sorted by canonical form**, at most 64.
      `ch` and `pci` only when known. A `near` cell is written in the **wire form** (C3) - MCC three digits, MNC at least
      two (`%02d`; Apple gives a number) - so the phone's `parseCellWire` takes it; the phone keys by numbers, so `01`
      and `001` are one row anyway.
    - The `cells` entries keep their form and **never carry `ch` or `pci`** (Apple gives none for an asked cell, and a
      cached cell that once came as someone's neighbour must not differ).
    - **Built from the store on both paths:** after a call, the server writes the rows and then reads the asked rows
      back and builds the answer exactly as for a cache hit - one code path, so a fetched and a cached answer are the
      same body. The fixed answer time, the permit, the gate, the slot and the 503s are unchanged (C5).
    - **What is left of an oracle** (beside C5's L2): a cached list was cut from a call that may have carried other
      cells, whose shares of the count were smaller, so it may be shorter than a lone ask's would be; the 15-km cut keeps
      the other cells' far neighbours out. It tells a walker at most that some phone once asked this cell together with
      others nearby - not where. Accepted, stated in the server notes.
    - The answer is at most ~8 KB; the day's brake and the hour's 30 count asked cells, as today.

## 3. Decisions - the phone

N6. **The neighbour read** (`cellReads` gains a second list; Android 14-17, every getter at API <= 34). From the same
    `getAllCellInfo()` / one `requestCellInfoUpdate` per active subscription as C1 - no extra modem call. A **neighbour
    reading** is a `CellInfo` without a full identity (C2's form) that has:

    | Radio | channel | pci | signal |
    |---|---|---|---|
    | `CellInfoLte` | `CellIdentityLte.getEarfcn()` (24) | `getPci()` | `CellSignalStrengthLte.getRsrp()` (26) |
    | `CellInfoNr` | `CellIdentityNr.getNrarfcn()` (29) | `getPci()` (29) | `CellSignalStrengthNr.getSsRsrp()` (29) |
    | `CellInfoWcdma` | `CellIdentityWcdma.getUarfcn()` (24) | `getPsc()` | `getDbm()` (RSCP) |
    | `CellInfoGsm` | not read | | |

    **Guards:** `CellInfo.UNAVAILABLE` or a value outside N1's ranges -> not a reading (the PCI or channel), a signal
    outside -140..-43 dBm -> unknown signal (kept); older than 60 s before the attempt's start (C2's rule) -> stale; a
    reading whose tag equals a registered cell's own (a modem listing its serving cell twice) -> dropped; duplicates (one
    neighbour on both slots) once, the stronger kept; at most **12**, strongest first. An NSA NR leg without NCI (C2
    skips it as a cell) is a reading by NR-ARFCN and PCI. A `SECONDARY_SERVING` cell without a full id is a reading.
    Not one read is sent anywhere.
N7. **The tag.** `tag = HMAC("tag|mcc|radio|channel|pci")`, 8 bytes, under the places HMAC key (D18), MCC as a number,
    **no MNC** (national roaming and shared RAN carry one cell under two PLMNs; one country's operators use separate
    channels). A heard neighbour's MCC is its registered cell's; a row's is its own cell's. Tags go only where the table
    already holds keyed values; channel and PCI are never stored in the clear (the table is sealed whole anyway, D18).
N8. **The rows** (D18's table, the 71-byte layout; **no version bump**, nothing relearned):
    - **Kinds 2 and 5 gain a tag** in their 8-byte `network` field (today a copy of the key's prefix, read by nothing for
      cells - the build checks every reader of a cell row's `network`; design 131 did the same with a link row's
      `device`). An **own cell row** (kind 2) takes its tag from the registered cell's own channel and PCI when taught; a
      **kind-5 row of an asked cell** takes it from the read that asked it; a **kind-5 row from `near`** from Apple's
      `ch`/`pci`. A newer tag replaces an older one (an operator re-plans PCIs); unknown leaves the field empty.
    - **`near` entries become kind-5 rows** like asked ones (`storeAppleCells`): position, `rangeM = max(500 m, acc_m)`,
      a fixed 90 days, the tag; never over a row still blocked; C7's own-against-Apple and tombstones as for any kind-5
      row. **`APPLE_CELL_ROWS_MAX` 500 -> 1500** (an answer brings up to ~13 rows; ~107 KB more at most).
    - **Kind 6, `CELL_NEAR` - the phone's own neighbour rows**, id `n/<key>`, key `HMAC("near|<registered cell id>|
      radio|channel|pci")`: where the phone was when it heard that neighbour while on that registered cell. By network
      planning a cell's neighbours have distinct PCIs on one channel ("confusion-free"), so the key is unique without
      any search. Band `CELL` (range 500 m-10 km, a move at 20 km, D10), trusted at **3 occasions** (`CELL_OCCASIONS`),
      unseen 180 days goes, at most **1000** (their own cap beside the 4000, so they never push Wi-Fi rows out; 71 KB),
      cleared with every D19 clear like own cell rows, **kept with the Apple switch off** (own learning needs no Apple).
N9. **Own learning** (the owner's point 4; `pairings`, D2). With **exactly one** registered cell (a second SIM's
    neighbours can't be told apart: no kind-6 teach then), each neighbour reading pairs with the teaching GPS fix nearest
    in time **within 30 s** (`PAIR_SINGLE_MS`, as cells and C11 do) and teaches its kind-6 row (`teachRow`, weight 1 as
    for cells). The teach also sets the own cell row's tag (N8). Only fixes that pass `teaches` (D1: `gps`, >= 5
    satellites, <= 25 m, <= 3 m/s); never a `vibb_*` fix.
N10. **The match** (pure, `nearMatches` in `CellPlan.kt`), per attempt, on the table as `cellOnly` reads it after the ask
    (C8's H1 rule):
    - **Anchors:** each registered cell with a chosen row (C7: own trusted or live Apple). No anchor -> no Apple match
      (kind-6 rows still work: they need no anchor position).
    - **First the phone's own:** a trusted kind-6 row for (registered cell, reading) is the reading's position.
    - **Else by tag:** the candidates are own trusted kind-2 and live kind-5 rows (one per cell key, C7's choice) whose
      tag equals the reading's and whose position lies **within 20 km** (`NEAR_UNIQUE_M`) of an anchor. **Exactly one**
      candidate, and it lies within `R = min(r_anchor + r_row + 2 km, 15 km)` of that anchor (both can be heard from one
      place; the 2 km is C8's veto slack) -> matched. None -> unmatched; two or more -> **ambiguous**, skipped (PCI
      reuse: in town the same PCI on the same channel recurs some kilometres away; 20 km covers rural reuse for the rows a
      phone holds, which are its own and Apple's neighbourhoods of cells it was on).
    - A matched row that is itself a heard full-id cell is used once (by its key).
    - Logged per attempt, counts only: `WifiPlaces: near heard=6 own=1 matched=3 ambiguous=1 far=0 noanchor=0 ta=0`.
N11. **The fix** (`cellFix`, C8 kept and extended). The positioned cells: every heard full-id cell with a chosen row (as
    today) plus each matched reading - **at most 5**: the registered cells first, then by signal. Then:
    - **Outliers:** C8's rules unchanged (three or more: the per-axis median reference, a cell further than its range
      dropped; two must lie within both ranges; one is one).
    - **Weights:** `w = s / r^2`, `r` the row's range, `s` the signal factor `10^((P - P_best) / 17.5)` clamped to
      0.05..1, where `P` is RSRP (LTE), SS-RSRP (NR) or RSCP (WCDMA) and `P_best` the strongest of the used cells of the
      same radio; an unknown signal `s = 0.25`. (17.5 dB per halving of distance squared: a path loss of 35 dB a decade,
      the urban rule of thumb. 10 dB weaker -> 0.27, 20 dB -> 0.07.)
    - **Accuracy** with three or more used and at least one matched reading:
      `acc = max(300 m, min(acc_C8, K x max(r_min, RMS) / sqrt(n_eff)))`, `n_eff = (sum w)^2 / sum w^2`, `r_min` the
      smallest used range, `RMS` C8's weighted RMS distance, `acc_C8` today's accuracy for the same cells, **`K = 1.5`**
      until C11's counts say otherwise; otherwise C8's accuracy as today. None over 5 km (`CELL_FIX_MAX_M`).
      Worked example (made-up): the registered cell 1529 m at -85 dBm, neighbours of 1414 m at -88, -95, -101 dBm and
      one of 6755 m at -110 -> `s` 1, 0.67, 0.27, 0.12, 0.05; `n_eff` 2.9; with an RMS of 900 m `acc = 1.5 x 1414 /
      1.70 = 1246 m` (today: 1529 m); with `K = 1` 830 m.
    - **The floor of 300 m:** above `LEARNED_ACCURACY_MAX_M` (200 m), so a cell fix never looks like a Wi-Fi fix and never
      wins keep-the-better against one; below it a centroid of tower estimates (each a coverage centre ~1 km out, Apple's
      ranges floored at 500 m) can't be honest even from five cells. It binds only with small ranges and many cells.
    - Position, time (the oldest used cell's), providers (`vibb_apple_cell` when any used position is Apple's, a matched
      kind-5 row included; else `vibb_cell`) and `fresh = false` as C8. **No new provider word**, so the server's
      `zones::COARSE_PROVIDERS` and 131's `FixClass` need nothing.
N12. **Leave-one-out** (C11 extended, the calibration of `K`). Before a GPS fix teaches, `cellFix` on the cells and
    readings paired with it (30 s) against the table as it was; a fix that used a matched reading counts in a fourth
    class **`near`**, with its hit (GPS inside the accuracy) and error: `loo cell own=3/3 apple=12/14 mixed=1/1
    near=9/11 err own=800 apple=600 mixed=700 near=420`. **The rule for `K`:** after 30 days of a SIM, `near` hits at
    68-90 % keep `K`; over 90 % with 20+ samples lowers it to 1.0 (one constant, a code change after the counts); under
    68 % raises it to 2.0. The counts sit in `DayCounts`, 30 days, CE prefs, as today.
N13. **Timing advance: logged only** (the owner's point 5; 125b Q9 stands). `CellSignalStrengthLte.getTimingAdvance()`
    (and NR's `getTimingAdvanceMicros()`, API 34) exists only on the serving cell and only while connected (RRC
    connected), so a mostly idle kid's phone rarely has it; it measures the distance to the **mast**, while Apple's
    position is where phones heard the cell (a coverage centre, often hundreds of metres off the mast), so it can't ring
    that position. The `near` line counts `ta=` (present or not), nothing more; a later design may use it if the Jelly
    shows it present often.

## 4. Effects

- **125b's cell fix and its report rules: unchanged gates.** C9's order (last in the attempt), still means still,
  withheld inside a recent fine fix's circle (`d <= accCell + accFine` - a smaller cell circle is withheld less often
  when it disagrees and still withheld when it agrees), `locate` always gets it, never fresh, never a vouch, never
  "Hasn't moved since", never teaches, never 124's new side; the veto (C8) reads **registered** cells only, as today.
- **C4's ask: unchanged.** The 8-positioned threshold counts full-id read cells as today; matched readings never cause
  or prevent an ask. A registered cell whose kind-5 row came in someone's `near` list is **not asked again** for its own
  neighbours (Q2): matching near it uses the rows the earlier answer brought, which overlap.
- **134's `cellElsewhere`: unchanged - matched neighbours don't witness** (Q3). The mismatch ends `STILL`; a PCI match is
  an inference, not an identity, and D15 already says "never by an id, only by a known position" of the registered
  cell. One indirect effect: more registered cells now have a kind-5 row (they came in `near` lists), so the `cell`
  clause can decide more often - by the same rule and margin, from Apple's own position of that very cell. Accepted.
- **Zones: untouched.** A coarse fix is never "inside"; "surely outside" stays `d - r >= max(3a, 20 km)`, where 20 km
  governs any accuracy under 6.7 km - so a tighter circle changes no zone outcome; the 10-minute pair, `seed`,
  `start_over` and `here_now` read fine fixes as today (C10). The phone's `sureSide` guard is unchanged.
- **The PWA:** nothing new. Locate draws the smaller circle; no words change, nothing on the kid's phone.

## 5. Privacy (N14)

- **Nothing new leaves the phone.** The cells request is the same body (the wire forms of full-id cells it has no
  position for). Neighbours' channel, PCI and signal are never sent to the server or Apple; kind-6 rows never leave.
- **Apple sees the same call**, with a count of 12 instead of 6 (N2) - a number, not a datum about the phone.
- **The server's cache** holds each asked cell's neighbour list (Apple's data) sealed under that cell; the separate
  surrounding-tower rows go (N3), so the cache shows no wider an area than before. C12's "sealing protects cell rows
  less" holds for the lists too (enumerable ids). The sweep's counts line gains `near=` (entries answered); the source
  test keeps any cell id, channel or PCI out of every `wifi_lookup` tracing line.
- **On the phone**: kind-6 rows say where the kid has been at cell precision, like own cell rows - in the same sealed
  table, cleared the same way. Logs carry counts and accuracies only (`PlacesTest` checks every `Log` call).

## 6. Battery (N15)

No extra lookups, no extra modem polls: the neighbours come in the `CellInfo` list C1 already reads, the match runs on
the table already in memory, own learning rides the teaches that happen anyway. The cells answer grows by at most ~8 KB
once per new registered cell (90 days). Kind-6 rows add at most 71 KB to the sealed file.

## 7. Is it worth it?

**What it buys** (estimates; the Jelly's leave-one-out tells the truth):
- **Town** (cells 0.5-3 km apart, Apple's ranges ~1-1.5 km, 3-6 neighbours heard): a cell fix from 3-5 positioned
  cells instead of one. The position error drops from a sector's coverage centre (~0.8-1.5 km off) to roughly
  300-700 m; the claimed circle from ~1.5 km to ~1.2 km at `K = 1.5` and ~0.8 km once the counts allow `K = 1`. But in
  town Wi-Fi nearly always gives a fix (learned or Apple's), so cell fixes are rare there: a car, a bus, a tunnel exit.
- **The edge of town and the road** - where cell fixes actually happen - gain most: Wi-Fi is thin, neighbours are still
  heard, Apple knows the cells.
- **Countryside** (cells 5-15 km apart, Apple's ranges 3-7 km, 0-2 neighbours heard, rows sparse): little - often no
  match, or ambiguous. Own learning (kind 6) helps on the routes the kid takes often (school, the cabin road) after a few
  weeks of GPS teaches.
- **Without a SIM** (the Jelly today): nothing - no registered cell, no anchor.
- **Zones**: no change by design.

**Better than GrapheneOS's `AppleCellPositioningService`:** GrapheneOS uses LTE neighbours only when heard with a full
id, so on LTE it positions from the registered cell alone; this matches neighbours by channel and PCI, weights by signal,
learns its own neighbour rows without Apple, and calibrates its accuracy against GPS. GrapheneOS's combined-variance
accuracy is more optimistic than ours; ours is the honest one (C8), now less wide.

**Cost:** server ~150 lines + ~150 of tests, launcher ~320 + ~280, one migration, the contract rows. **Verdict:** worth
it as a moderate step, because the server half is small and the phone half is pure code with tests; the size of the
gain is unknown until a SIM is in the Jelly. If the SIM check shows the modem's neighbours lack EARFCN or PCI, the phone
half has nothing to match and only own learning of full-id rows is left - then stop after step 1.

## 8. Contract and tests (N16)

**`contracts/integrations/wifi_lookup/contract.json`** (server and launcher read it; one commit with the API):
- `rules`: `near_max` 64, `near_per_cell` 12, `near_km` 15.
- `cell_answers`: every existing 200 body gains `"near"` (the existing rows change in that one key; their notes say
  "Apple's surrounding towers kept in the asked cell's list"); new `cells_fetched_near` and `cells_cached_near`
  (**byte-identical bodies**, both at the answer time): one asked LTE cell, 12 surrounding of which 8 have `ch`/`pci`,
  two without, one with `ch` alone, one 20 km away (cut), and ~20 id-less areas (dropped); the `near` cells in the wire
  form with MNC `01`. `cells_old_row` (a cell row without `near`, as after the migration: asked again, the list then
  present). `cells_two_asked` (two asked cells with overlapping lists: one entry each, canonical order, neither asked
  cell in `near`).
- `cell_phone_answers`: `use_cells_near` (200 with `near`: kind-5 rows with tags), `near_bad_entry` (a wire form that
  fails, a position out of range, `pci` 504 on LTE: that entry left out, the rest stored).
- Test PLMN **001-01** and **made-up positions** on the existing rows' grid; the channel and PCI values made up too -
  never the probe's cell, positions, EARFCNs or PCIs.

**`server/testdata/wifi_lookup/server.json`** (by `scripts/wifi-lookup-fixtures.py`, whose encoder gains fields 6, 7,
11, 12 and location fields 4, 11, 12): `golden_cells` with the count 12 (checked once with `protoc --encode`);
`apple_cells` rows in the probe's shape - an asked LTE cell with location fields 4, 11, 12 and no 6/7; surrounding LTE
towers with 6/7; field 7 = -1 (10-byte varint) and 504 (unknown, tower kept); field 6 as a length-delimited value
(`Malformed`); an NR tower with field 6 only; a WCDMA tower with 11/12; a GSM tower with 11/12 (not read); 20 towers with
cid -1 at 149,000 m (dropped).

**Server tests:** the codec rows; a near list sealed under its asked cell and opening for no other; no surrounding-tower
row stored; the fetched and cached answers byte-identical and both at the answer time; the 15-km cut and the 12 nearest;
the migration (an old cell row is wanted, a Wi-Fi row untouched); the source test.

**Launcher (`wifi_places.json`, `PlacesTest`, `CellPlanTest`):** `near_reads` (UNAVAILABLE PCI or channel, out of range,
a stale reading, the serving cell listed again, one neighbour on two slots, 13 -> 12, an NSA NR leg as a reading, an
RSRP of -150 as unknown); `near_match` (unique -> matched; two same-tag rows within 20 km -> ambiguous; one beyond `R` ->
far; another MCC -> no candidate; no anchor; a trusted kind-6 row first; an untrusted one not); `near_fix` (the worked
example's numbers, the 300-m floor, never above `acc_C8`, at most 5 cells, the median dropping a wrong match, the
provider); `near_teach` (one registered cell -> a kind-6 row; two -> none; 31 s apart -> none; the own cell row's tag
set); `near_rows` (the 1500 and 1000 caps, the switch off keeps kind 6, a clear drops it, a tag replaced); the C11
`near` class and its line. `WifiLookupContractTest` over the new rows.

## 9. Steps

1. **Server + contract + the phone's DTO** (one commit, the API in both dirs): N1-N5, the migration, `server.json` and
   the script, the contract rows; the launcher's `CellLookupAnswer` gains `near` (stored as kind-5 rows with tags, N8's
   first two bullets), its contract test. Notes: `docs/notes/server/integrations.md`, `server/CLAUDE.md`'s migration
   line. The server ships first (an older launcher ignores `near`: `ignoreUnknownKeys`).
2. **Launcher:** N6-N13 - the readings, the tags, kind 6 and its teach, the match, the fix, the counts and log lines;
   `docs/notes/launcher/wifi-places.md`. 125b's status line notes that C7's surrounding rows and C8's accuracy changed
   here.
3. **Code QA**, then **the emulator gate on Android 14 and 16** (the user's OK; a new `docs/testing/emulator.md` §6
   section): the emulator's modem lists one registered LTE cell and no neighbours, and the server's proxy points at a
   closed port (no emulator cell ever reaches Apple; §6bk has no fake Apple), so the gate fakes at the phone - two
   debug hooks in `PlacesDebugReceiver` (src/debug, `DebugHookAbsentTest`): `--es cells <file>` (a made-up reading: one
   registered cell on 001-01 and neighbours with channel, PCI, RSRP) and `--es cell_answer <file>` (a made-up cells 200
   with `near`, stored as if the server answered). It shows: the `near heard=` line; kind-5 rows with tags stored from
   the fake answer; a match and an ambiguous reading; a `vibb_apple_cell` fix with the tighter accuracy on a Locate with
   Wi-Fi and GPS off; a kind-6 row taught from a mock GPS fix in a debug build (D1 allows mock outside release); the
   `loo cell ... near=` line; the switch off keeping kind 6. The server half is the contract test, not the gate.
4. **The Jelly Star with a SIM** (release RC, new lines in `docs/testing/new-phone-model.md` §2): `dumpsys
   telephony.registry | grep -i cellinfo` - do neighbours carry EARFCN and PCI, RSRP, fresh timestamps, a TA; a day of
   `near heard=` lines (matched against ambiguous); a walk and a drive in town and outside with Wi-Fi off and GPS
   blocked, the `vibb_apple_cell` circle against the GPS fix after; the `near` leave-one-out class after 30 days, and
   `K` set by N12's rule.

Before the first release, by hand: the `protoc --encode` check of the new `golden_cells`, and one call with the count
12 through Tor on a public cell (Q1).

## 10. Questions for the owner (each with the default taken unless the owner says otherwise)

- **Q1** Ask Apple for 12 neighbours a call instead of 6? **Default: yes**, if the Tor check shows 12 come back; else 6.
- **Q2** Ask again for a registered cell that is only known as someone's neighbour, to get its own neighbours (one more
  Apple call per new cell on a drive)? **Default: no** - no extra lookups.
- **Q3** Let a matched neighbour witness 134's "scan mismatch" (end `STILL`)? **Default: no** - only the registered cell,
  by its own position.
- **Q4** Report the tighter fix at once, or run the match in shadow (logged and counted, not reported) until the SIM's
  counts are in? **Default: at once** with `K = 1.5` - it stays a coarse last resort and zones ignore it.
- **Q5** Timing advance: **default: logged only** (`ta=` count).
- **Q6** Apple's `reach` (location field 12, 5045 m in the probe): **default: not used** - its meaning is unverified; a
  later revision may use it for the match radius `R` once a few answers show what it is.
