# Design 137: cell neighbours matched against Apple's cell answer

Status: design, **revision 2**, 2026-11-25 (branch `design-137`), read against `main` 4b67b3fe8. Item **5b** on the
owner's locked list ("ta det før 136"). Not built. Revision 1 was 3265a4d47; revision 2 takes the design QA (2 H, 5 M,
8 L; §12) and its **reduced scope**: the phone matches what it hears against **Apple's neighbours only**; the phone's own
neighbour rows, a tighter accuracy and site grouping wait for a later **137b** (§10), after about 30 days of SIM data.
Numbering **N1-N13**, beside 125b's C1-C14.

Asked by the owner on 2026-11-25, after a live probe: the phone hears its neighbour cells by PCI and channel only, and
Apple's cell answer already carries the neighbours around the asked cell with full ids, positions, channel and PCI.
Match the two and let the matched neighbours refine the cell fix's position - which stays **a last resort: never fresh,
never a vouch, never "Hasn't moved since"** (125b C9 unchanged).

Read with: design 125 (D10, D16, D18), 125b (C1-C14, all of it), 131 (`FixClass`), 134 (D15's `cellElsewhere`). The
code: `server/src/integrations/wifi_lookup/{cells,cache,route}.rs`, `launcher/.../places/{CellPlan,PlacesPlan,
WifiPlaces}.kt`, `contracts/integrations/wifi_lookup/contract.json`.

## 0. Short answer

- **Apple's side:** the server reads LTE's tower fields 6 (EARFCN) and 7 (PCI) and WCDMA's 11/12 (UARFCN, PSC). It
  still sends only the identity (as GrapheneOS), asks for 12 neighbours (the probe: 12 asked, 12 came), keeps **each
  asked cell's overlapping neighbours** sealed in that cell's row (one migration, one column), no longer keeps
  surrounding towers as rows of their own, and answers a **`near`** list beside `cells` and `unknown`, built from the
  store on both paths so 125b C5's no-oracle rule holds.
- **The phone:** reads each LTE/WCDMA neighbour's channel, PCI and signal; stores `near` entries as kind-5 rows with a
  **tag** (keyed hash of MCC, radio, channel, PCI) in their `network` field; matches a heard neighbour to the **one**
  kind-5 row with its tag that overlaps the registered cell (none or two: skipped).
- **The fix:** C8 on the full-id cells is the floor; matched neighbours may move its position, weighted by range and
  signal, **never take the fix away, never drop the registered cell, never leave its range**; the accuracy is C8's (500 m
  floor). A tighter circle comes in 137b, only when the new leave-one-out class `near_apple` shows it holds.
- **Honest gain:** small and in a narrow case - indoors with neither GPS nor a known Wi-Fi (a friend's house, a bag in
  the car): the position moves from the serving sector's centre towards where the phone is; the circle doesn't shrink
  yet. Nothing without a SIM. A pre-step with a SIM in the Jelly decides whether the phone half is built at all (§9).

## 1. The evidence (the probe of 2026-11-25)

One real LTE cell of a Norwegian operator (a public tower, from CellMapper; its identity stays out of the repo), asked of
`gs-loc.apple.com/clls/wloc` through Tor, the request built exactly as `cells::request` builds it (one LTE tower):

- **The asked cell:** known, accuracy (location field 3) 1529 m; location field 4 (`location_type`) 0; fields 11 = 914
  and 12 = 5045 present - GrapheneOS's `apple_wps.proto` names them `score` and `reach`, their meaning unverified.
  **No EARFCN or PCI** on the asked cell (we send none).
- **Surrounding cells with full ids**, positions, accuracy ~1414 m (one 6755 m), **and tower fields 6 and 7**: three
  EARFCNs in band 3, PCIs all different, one a sibling sector of the same eNB 0.7 km off, the rest 0.7-2.7 km away.
  **Their number is the count asked**: field 26 = 6 gave 6, 12 gave 12, 20 gave 20.
- **About 20 entries with cid -1** (TAC areas, accuracy 149,000 m) on top: the "~19 more than asked" GrapheneOS saw.
  The server already drops them (a negative cid is out of range; the accuracy is over 50 km too).
- Five stale OpenCellID cells (one sample each) were unknown to Apple; their answers held only the id-less areas.
- **The Jelly Star** (MediaTek, no SIM): `cells read=3 usable=0 registered=0` - the modem lists neighbours (PCI, EARFCN,
  RSRP) without a SIM, but none has a full id and there is no registered cell to anchor them. Whether it lists them with
  EARFCN and PCI **while registered** is unknown until a SIM is in (§9 step 0).

**GrapheneOS at 474472e** (checked 2026-11-25 through GitHub's API): `apple_wps.proto` declares LTE `uarfcn 6`, `pid 7`,
NR `nrarfcn 6` (no PCI field), GSM `arcfn 11` (sic) / `psc 12`, SCDMA `arfcn 11` / `psc 12`, but
`AppleCellPositioningService` never reads them; `estimateLocationCell` keeps only `CellInfo`s whose identity has MCC,
MNC, area and cid, so an LTE neighbour (PCI and EARFCN only) is dropped; Apple's surrounding towers go into a 15-minute,
1000-entry LRU, which helps only when a neighbour is heard with its full id (rare on LTE). On an LTE network
GrapheneOS's cell fix is therefore the registered cell's alone. Its throttle counts every tower of the answer, id-less
ones included.

## 2. Decisions - Apple's protocol and the server

N1. **What the server reads** (`cells::answer`). A tower gains `channel: Option<u32>` and `pci: Option<u16>`:

    | Answer field | Tower | channel | pci | Kept (else unknown) |
    |---|---|---|---|---|
    | 22 | LTE | 6 (EARFCN) | 7 | EARFCN 0..262143, PCI 0..503 |
    | 23 | SCDMA, read as WCDMA (125b Q7) | 11 (UARFCN) | 12 (PSC) | UARFCN 0..16383, PSC 0..511 |
    | 24 | NR | not read (no PCI field; NR waits for 137b) | | |
    | 1 | GSM | not read (field 12's meaning for GSM unknown; 2G is blocked on the kid's phone, design 126 A5) | | |

    All are `int32`. Apple's -1 (a 10-byte varint), a value past the range or over `i32::MAX`, **and a wire type other
    than varint** in these optional fields are **unknown** - never `Malformed` (QA L1): the tower is kept with its
    position. Fields 1-5 keep today's strict rule. Location fields 4, 11 and 12 stay skipped (unknown fields); the
    tests carry the probe's shape for a later reader.
N2. **What the server sends Apple, and the throttle.**
    - **The identity only** (fields 1-4), as GrapheneOS. Sending the registered cell's EARFCN/PCI gains nothing (the
      phone reads its own registered cell's) and would widen what leaves the phone (125b C12) and change the golden
      request `protoc` checked.
    - **The count fields** (3, 26, 28, 30, `int32`, plain varints): the probe shows Apple answers the count in full-id
      neighbours and adds the id-less areas on top. `max(1, NEAR_ASKED x share)` with **`NEAR_ASKED = 12`** (was
      `(25 - 19) x share`, 6 for a lone cell); throttled: 1 each, as today. `golden_cells` changes and is checked with
      `protoc --encode` again.
    - **The throttle** (QA M1): today an answer of 10 parsed towers or more throttles the next 10 s, which 12 neighbours
      would trip at every call. Now a call throttles only when **Apple sent more than asked**: parsed towers `> asked +
      NEAR_ASKED` (GrapheneOS's ~120 when a count is 0 or ignored). A call made **while throttled** stores the asked
      cells' positions but leaves their `near` **NULL**, so each is wanted again at its next ask (N3) and every answered
      list comes from a full call.
N3. **The cache: each asked cell holds its neighbour list.** Apple's surrounding towers are one flat list for the whole
    call, and a cached answer must be the same as a fetched one (125b C5), so the list is kept **per asked cell**:
    - **`near`** (migration, N4): for each asked cell Apple placed, the call's positioned surrounding towers (not an asked
      one) that **overlap it** - `d <= r_cell + r_tower`, the two accuracies (QA M2; a phone can hear both only where
      both reach) - **nearest first, at most 12**, each `{canonical, lat, lng, acc_m, channel?, pci?}`, sealed
      AES-256-GCM under a key expanded from the seal PRK with the info `near|<canonical>` (AAD the same), so it opens only
      with `K` and that cell's id, like the position. An empty list is an empty sealed list; NULL means "not from a full
      call" (an old row, a throttled call).
    - **Surrounding towers are no longer rows of their own** (125b C7's "Apple's surrounding towers cached" goes): they
      were never answered, and a row without a list is asked again anyway, so they bought nothing; fewer rows is also a
      smaller cache of where the family has been. Wi-Fi's surrounding APs are unchanged.
    - **A cell row whose `near` is NULL is wanted** (`Cached::wanted`): Apple is asked once more. Negatives, months, the
      caps and `CACHE_LIFETIME` are unchanged.
N4. **The migration.** `ALTER TABLE wifi_lookup_aps ADD COLUMN near BLOB` (NULL for Wi-Fi rows and old cell rows; the
    rows stay and are asked again). The number is **the next free one on `main` at merge** (QA L6). `main` is at 0179
    today; 137 merges after phase D's 0180 and design 135's 0181 are on `main`, so it takes **0182**, and design 136
    (written with 0182) moves to the next free number; if 136 merges first, 137 takes 0183. Never a gap, never a lower
    number after a higher one has run (`server/CLAUDE.md`). The server holds real data: the migration only adds a column.
N5. **The route's answer** (`POST /api/devices/wifi-lookup`, a `{"cells"}` body; nothing else changes):

    ```json
    {"cells": [{"cell", "lat", "lng", "acc_m"}], "unknown": [cell],
     "near": [{"cell", "lat", "lng", "acc_m", "ch"?, "pci"?}]}
    ```

    - `near` is **always present** (maybe empty). Order (QA L2): **per asked cell in the ask's order** (the phone sends
      its registered cell first), each cell's stored list nearest first; an entry already given or an asked cell left
      out; at most **64**. `ch` and `pci` only when known. A `near` cell is written in the **wire form** (C3) - MCC three
      digits, MNC at least two (`%02d`; Apple gives a number) - so the phone's `parseCellWire` takes it.
    - The `cells` entries keep their form and **never carry `ch` or `pci`**.
    - **Built from the store on both paths:** after a call the server writes the rows, reads the asked rows back and
      builds the answer exactly as for a cache hit - one code path, one body. **A failed store write answers `503`**
      (900) at the answer time (QA L3): an answer built from Apple's reply instead of the store could differ from the
      next cached one.
    - The fixed answer time, the permit, the gate, the slot, the day's brake, the hour's 30 and the 503s are unchanged
      (C5). The answer is at most ~8 KB.
    - **What is left of an oracle, in C5's terms** (beside C5's L2, accepted there): the answer is still the same
      whether the row was cached or fetched in this request, at the same time - but a stored list was cut from the call
      that fetched it, and when that call asked several cells, each cell's share of the count was smaller, so its list
      can be shorter than a lone ask's. A walker who asks one cell alone and gets a short list may infer that the cell's
      row came from a phone's multi-cell ask - that **some family phone was near that cell once**, which is what C5
      exists to hide; the overlap cut keeps the other asked cells' neighbours out, so it doesn't tell where else. The
      length varies with Apple's data too (a cell with few neighbours has a short list whoever asks), so the inference
      is weak, and the walk is braked at 1000 cells a day. A throttled call leaves no short list (NULL, asked again).
      **Taken as the mitigation; the owner may change it (§11).**

## 3. Decisions - the phone

N6. **The neighbour read** (`cellReads` gains a second list). From the same `getAllCellInfo()` / one
    `requestCellInfoUpdate` per active subscription as C1 - no extra modem call. A **neighbour reading** is a `CellInfo`
    without a full identity (C2's form) that has:

    | Radio | channel | pci | signal |
    |---|---|---|---|
    | `CellInfoLte` | `CellIdentityLte.getEarfcn()` (API 24) | `getPci()` (17) | `CellSignalStrengthLte.getRsrp()` (26) |
    | `CellInfoWcdma` | `CellIdentityWcdma.getUarfcn()` (24) | `getPsc()` (18) | `CellSignalStrengthWcdma.getDbm()` (RSCP) |
    | NR, GSM | not read (NR has no PCI in Apple's answer: it can match only through the phone's own rows, 137b) | | |

    **Android 14-17:** every getter is at API <= 26, inside minSdk 34. The behaviour-change lists of 15, 16 and 17 name
    nothing for `CellIdentity`/`CellSignalStrength` as far as the author knows; the build checks each version's list
    again (root `CLAUDE.md`), the gate runs 14 and 16, the Jelly runs 16. What a modem reports is the modem's (§9).
    **Guards:** `CellInfo.UNAVAILABLE` or a value outside N1's ranges for channel or PCI -> not a reading; a signal
    outside -140..-43 dBm -> unknown signal (kept); older than 60 s before the attempt's start (C2's rule) -> stale, not
    a reading; a reading whose channel and PCI equal a registered cell's own (a modem listing its serving cell twice) ->
    dropped; one neighbour on both slots -> once, the stronger kept; at most **12**, strongest first. Not one read is
    sent anywhere. Logged with the match (N9).
N7. **The tag.** `tag = HMAC("tag|mcc|radio|channel|pci")`, 8 bytes, under the places HMAC key (D18), MCC as a number,
    **no MNC** (national roaming and shared RAN carry one cell under two PLMNs; one country's operators use separate
    channels). A reading's MCC is its registered cell's; a row's is its own cell's.
N8. **The rows** (D18's table, the 71-byte layout; **no version bump**).
    - **`near` entries become kind-5 rows** like asked ones (`storeAppleCells`): position, `rangeM = max(500 m,
      acc_m)`, a fixed 90 days; never over a row still blocking; C7's own-against-Apple and tombstones as for any kind-5
      row. **`APPLE_CELL_ROWS_MAX` 500 -> 1500** (an answer brings up to 13 rows; 1000 rows more is 71 KB).
    - **The tag lives in the row's 8-byte `network` field.** Today `decodeTable` empties `network` for kinds 4 and 5 and
      an own cell row (kind 2) holds `key.take(16)` there. **The decoder keeps `network` for kinds 2 and 5** (QA M3);
      **all zeros or equal to `key.take(16)` means no tag**, so a table written before this decodes with no tags and
      nothing is relearned. Only Apple's `ch`/`pci` set a tag (kind 2 gets none in 137; own tags are 137b's). A newer
      answer whose tag differs from the stored one **replaces it and is counted as its own change** (`retagged=` in the
      store's counts line - an operator re-planned PCIs, or Apple corrected itself); an answer without `ch`/`pci` leaves
      the stored tag.
    - **The table's ceiling** (QA L7): own rows 4000, Apple Wi-Fi rows 3000, Apple cell rows 1500 - at most 8500 rows,
      about 600 KB sealed. 137b's own neighbour rows would add their own cap.
N9. **The match** (pure, `nearMatches` in `CellPlan.kt`), per attempt, on the table as `cellOnly` reads it after the ask
    (C8's H1 rule):
    - **Anchors:** each registered cell with a chosen row (C7: own trusted or live Apple), range `r_a`. No anchor -> no
      match.
    - **Candidates** (QA M5): live kind-5 rows with the reading's tag whose position lies within **`r_a + r_row + 500 m`**
      of an anchor - the overlap gate plus a margin for Apple's centres. **Exactly one** candidate -> matched (and N10
      then admits it only inside the overlap itself). None -> unmatched; two or more -> **ambiguous**, skipped. A
      cell's neighbours have distinct PCIs on one channel by network planning ("confusion-free"), so within one
      anchor's overlap the same tag twice means PCI reuse at the edge or Apple's data out of date: skip, don't guess.
    - A matched row that is also a heard full-id cell is used once (by its key).
    - Logged per attempt, counts only: `WifiPlaces: near heard=6 matched=3 ambiguous=1 far=1 noanchor=0 ta=0` (`far`: a
      unique candidate in the 500-m margin, not admitted).
N10. **The fix: a refinement that never takes a fix away** (`cellFix`; QA H1, H2):
    1. **The floor:** C8 on the full-id cells exactly as today - the **base fix**. No base, no fix: readings never make a
       fix alone, and at least one full-id cell is always used.
    2. **Admitted readings:** matched readings whose row lies within **`d <= r_a + r_row`** of the anchor they matched
       (no slack), the strongest first, so that the set holds **at most 5** cells.
    3. **The set's rules, readings dropped first:** C8's rules run on base cells plus readings, but **only readings are
       ever dropped**: two cells must lie within both ranges of each other, else the reading goes; with three or more,
       the median reference - if it would drop a full-id cell the base used, the **weakest reading** is dropped and the
       rules run again; a reading the median drops goes. When no reading is left, the result is the base fix.
    4. **Position:** the mean weighted by `w = s / r^2`, `r` the row's range and `s` the signal factor
       `10^((P - P_best) / 17.5)` clamped to 0.05..1, where `P` is RSRP (LTE) or RSCP (WCDMA) and `P_best` the strongest
       of the used cells **on the same channel** (QA L4: path loss differs by band); a cell alone on its channel, or
       with an unknown channel, takes 1, an unknown signal 0.25. (17.5 dB per halving of `1/d^2`: a path loss of 35 dB a
       decade, the urban rule of thumb; 10 dB weaker -> 0.27, 20 dB -> 0.07.)
    5. **Inside the anchor:** the position must lie within `r_a` of the anchor's row; else the base fix.
    6. **Accuracy - C8's, now:** `max(smallest used range, weighted RMS distance, 500 m)` over the used set; if that is
       wider than the base fix's, the base fix (the readings didn't help). None over 5 km (`CELL_FIX_MAX_M`).
    7. Time (the oldest used cell's), providers (`vibb_apple_cell` when any used position is Apple's - an admitted reading
       always is; else `vibb_cell`) and `fresh = false` as C8. **No new provider word**, so the server's
       `zones::COARSE_PROVIDERS` and 131's `FixClass` need nothing.

    So a matched reading can move the position towards where the phone is and never widen, remove or move the fix out
    of its registered cell. Worked example (made-up): the registered cell's row 1529 m at -85 dBm, three matched
    readings on its channel, rows of 1414 m at -88, -95, -101 dBm, 1.2, 1.5 and 1.8 km east of the anchor: `s` 1,
    0.67, 0.27, 0.12, weights 4.3, 3.4, 1.3, 0.6 (x 1e-7); the position moves ~0.7 km east of the anchor's centre,
    inside its 1529 m; the accuracy `max(1414, RMS, 500)` = 1414 m against the base's 1529 m (the smallest used range;
    no tightening beyond C8's own formula).
N11. **Leave-one-out: `near_apple`** (C11 extended; the evidence 137b needs). Before a GPS fix teaches, `cellFix` on the
    cells and readings paired with it (30 s) against the table as it was; a fix that used an admitted reading counts in
    a new class **`near_apple`**: its hit (GPS inside the accuracy), its error, **the base fix's error on the same
    sample**, and its anchor. `DayCounts` gains the counts and the day's distinct anchors (up to 16 four-byte prefixes of
    the anchor's key a day, so the line can say how many cells the samples came from). Logged
    `loo cell own=3/3 apple=12/14 mixed=1/1 near_apple=9/11 err near=520 base=760 anchors=6 days=8` - 30 days, CE
    prefs, counts only.
N12. **Timing advance: a logged count only** (the owner's point 5; 125b Q9). `CellSignalStrengthLte.getTimingAdvance()`
    exists only on the serving cell and only while connected, and it measures the distance to the mast, while Apple's
    position is where phones heard the cell; the `near` line counts `ta=` (present or not), nothing more.

## 4. Effects

- **125b's cell fix and its report rules: unchanged gates.** C9's order (last in the attempt), still means still,
  withheld inside a recent fine fix's circle, `locate` always gets it, never fresh, never a vouch, never "Hasn't moved
  since", never teaches, never 124's new side. The veto (C8) reads **registered** cells only, as today.
- **C4's ask: unchanged.** The 8-positioned threshold counts full-id read cells; readings never cause or prevent an ask.
  A registered cell whose kind-5 row came in someone's `near` list is not asked again for its own neighbours (no extra
  lookups); matching near it uses the rows the earlier answer brought, which overlap.
- **134's `cellElsewhere`: unchanged - readings never witness.** A PCI match is an inference, not an identity, and D15
  says "only by a known position" of the registered cell. One indirect effect: more registered cells now have a kind-5
  row (they came in `near` lists), so the `cell` clause can decide more often - by the same rule and margin, from
  Apple's own position of that very cell. Accepted.
- **Zones: untouched.** A coarse fix is never "inside"; "surely outside" stays `d - r >= max(3a, 20 km)`; the accuracy is
  C8's as before; `seed`, `start_over`, `here_now` read fine fixes (C10). The phone's `sureSide` guard is unchanged.
- **The PWA:** nothing new; no words change, nothing on the kid's phone.

## 5. Privacy

- **Nothing new leaves the phone.** The cells request is the same body. Neighbours' channel, PCI and signal are never
  sent to the server or Apple.
- **Apple sees the same call** with a count of 12 instead of 6 - a number, not a datum about the phone.
- **The server's cache** holds each asked cell's overlapping neighbours (Apple's data) sealed under that cell; the
  separate surrounding-tower rows go, so the cache shows no wider an area than before. C12's "sealing protects cell rows
  less" holds for the lists too. The residual oracle is N5's. The sweep's counts line gains `near=` (entries answered);
  the source test keeps any cell id, channel or PCI out of every `wifi_lookup` tracing line.
- **On the phone:** tags only in the sealed table; logs carry counts only (`PlacesTest` checks every `Log` call).

## 6. Battery

No extra lookups, no extra modem polls: the readings come in the `CellInfo` list C1 already reads, and the match runs on
the table already in memory. The cells answer grows by at most ~8 KB once per newly asked cell (90 days).

## 7. Is it worth it? (QA M4)

**Where a cell fix is reported at all** (C9): only when GPS gave nothing in the attempt, no learned or Apple Wi-Fi fix
came, and the phone isn't still (or it is the parent's Locate). In town and at home Wi-Fi nearly always gives a fix; on
the road GPS usually does. What is mostly left is **indoors with neither GPS nor a known Wi-Fi**: a friend's house (a
first visit, a sparse place Apple knows little of), a bag in the car (GPS blocked, Wi-Fi thin) - and the parent's
Locate there.

**What 137 buys there:** the position moves from the registered sector's coverage centre (often 0.5-1.5 km from the
phone) towards where the neighbours overlap - in town perhaps a few hundred metres nearer; in the countryside, with few
neighbours heard and Apple's ranges of 3-7 km, mostly nothing (no match, or a reading the overlap gate refuses). **The
circle stays C8's** - so on Locate the dot is a little better placed and the circle the same or slightly smaller. Zones
don't change by design. Without a SIM, nothing.

**Better than GrapheneOS's `AppleCellPositioningService`:** GrapheneOS positions from the registered LTE cell alone
(§1); this uses its neighbours by channel and PCI, weighted by signal, and records against GPS whether that helped
(`near_apple`).

**Cost:** server ~150 lines + ~150 of tests (most of it the cache's list and the contract), launcher ~200 + ~200, one
migration. **Verdict:** a small gain for a moderate cost; worth building because it is the measured way to 137b - the
`near_apple` counts say whether a tighter circle and the phone's own neighbour rows are worth it - and because the
server half also stops keeping surrounding towers. If §9's step 0 shows the modem lists no EARFCN/PCI while registered,
the phone half has nothing to match: don't build it.

## 8. Contract and tests

**`contracts/integrations/wifi_lookup/contract.json`** (server and launcher read it; one commit with the API):
- `rules`: `near_max` 64, `near_per_cell` 12, `near_asked` 12.
- `cell_answers`: every existing 200 body gains `"near"` (those rows change in that one key; their notes now say
  "Apple's surrounding towers kept in the asked cell's list, overlapping ones only"); new `cells_fetched_near` and
  `cells_cached_near` (**byte-identical bodies**, both at the answer time): one asked LTE cell, 14 surrounding of which
  12 overlap - 9 with `ch`/`pci`, 2 without, 1 with `ch` alone - and 2 that don't (cut), plus ~20 id-less areas
  (dropped); the `near` cells in the wire form with MNC `01`. `cells_old_row` (a row with `near` NULL: asked again, the
  list then present). `cells_throttled` (a call made while throttled: the position answered, `near` empty, the row asked
  again next time). `cells_two_asked` (two asked cells with overlapping lists: per asked cell in ask order, each entry
  once, neither asked cell in `near`). `cells_store_failed` (the write fails: 503 at the answer time).
- `cell_phone_answers`: `use_cells_near` (200 with `near`: kind-5 rows with tags), `near_bad_entry` (a wire form that
  fails, a position out of range, `pci` 504 on LTE: that entry without a tag or left out as C7 says, the rest stored).
- Test PLMN **001-01** and **made-up positions** on the existing rows' grid; channels and PCIs made up too - never the
  probe's cell, positions, EARFCNs or PCIs.

**`server/testdata/wifi_lookup/server.json`** (by `scripts/wifi-lookup-fixtures.py`, whose encoder gains fields 6, 7,
11, 12 and location fields 4, 11, 12): `golden_cells` with the count 12 (checked once with `protoc --encode`);
`apple_cells` rows in the probe's shape - an asked LTE cell with location fields 4, 11, 12 and no 6/7; surrounding LTE
towers with 6/7; field 7 = -1 (10-byte varint) and 504 (unknown, tower kept); field 6 as a length-delimited value
(unknown, tower kept - QA L1); an NR tower with field 6 (read as no channel); a WCDMA tower with 11/12; a GSM tower with
11/12 (not read); 20 towers with cid -1 at 149,000 m (dropped); an answer of 13 towers to a count of 12 (no throttle) and
one of 120 (throttled).

**Server tests:** the codec rows; a list sealed under its asked cell and opening for no other; the overlap cut and the
12 nearest; no surrounding-tower row stored; fetched and cached answers byte-identical, both at the answer time; the
order per asked cell; the throttle rule and the NULL list after a throttled call; a failed write's 503; the migration (an
old cell row wanted, a Wi-Fi row untouched); the source test.

**Launcher (`wifi_places.json`, `PlacesTest`, `CellPlanTest`):** `near_reads` (an UNAVAILABLE PCI, an UNAVAILABLE
EARFCN, a PCI of 504, a reading 61 s older than the attempt's start - stale, one 59 s - kept, the serving cell listed
again, one neighbour on two slots, 13 -> 12, an RSRP of -150 as unknown, an NR and a GSM neighbour not read);
`near_tags` (a table encoded before this - kind 2 with `key.take(16)`, kind 5 with zeros - decodes with no tags; a tag
stored; a differing tag replaces it and counts `retagged`; an entry without `ch`/`pci` keeps the old tag);
`near_match` (unique inside the gate -> matched; two same-tag rows inside -> ambiguous; one in the 500-m margin -> far;
another MCC -> none; no anchor -> none; an NR reading -> none); `near_fix` (**the anchor and one match 2 km off with
ranges 1529/1414 -> a fix: the anchor's, the reading dropped by the two-cell rule; two matches on the same side 2.5 km
off -> the anchor never dropped: the median drops the weakest reading, the two-cell rule the other, the anchor's fix**; a position pulled out of the anchor's
range -> the base fix; the accuracy never wider than the base's; at most 5 cells; per-channel `P_best`; the worked
example; the provider); the `near_apple` counts and their line. `WifiLookupContractTest` over the new rows.

## 9. Steps

0. **Before the phone half - the SIM check** (the Jelly Star with a SIM, a release RC or the current one): `adb shell
   dumpsys telephony.registry | grep -i -m8 cellinfo` while registered - do the non-registered LTE entries carry
   `mEarfcn` and `mPci` (not `2147483647`), an `rsrp`, a timestamp that moves after a refresh? Five readings over a day,
   in two places. **If they don't:** the phone half (step 2) isn't built and 5b stops at step 1 (harmless: a launcher
   ignores `near` it doesn't read) - or, if step 1 isn't built yet, it waits too; PLAN's 5b says so, and 137 waits for a
   phone or modem update that lists them.
1. **Server + contract + the phone's DTO** (one commit, the API in both dirs): N1-N5, the migration, `server.json` and
   the script, the contract rows; the launcher's `CellLookupAnswer` gains `near` (stored as kind-5 rows with tags, N8)
   and its contract test. Notes: `docs/notes/server/integrations.md`, `server/CLAUDE.md`'s migration line. The server
   ships first (an older launcher ignores `near`: `ignoreUnknownKeys`).
2. **Launcher:** N6-N12 - the readings, the match, the fix, the counts and log lines;
   `docs/notes/launcher/wifi-places.md`. 125b's status line notes that C6's throttle and C7's surrounding rows changed
   here.
3. **Code QA**, then **the emulator gate on Android 14 and 16** (the user's OK; a new `docs/testing/emulator.md` §6
   section). The emulator's modem lists one registered LTE cell and no neighbours, and the server's proxy points at a
   closed port (no emulator cell ever reaches Apple; §6bk has no fake Apple), so the gate fakes at the phone: two
   extras on `PlacesDebugReceiver`, **in `src/debug` only** (`PlacesDebugHooks`, with its no-op twin in `src/release`;
   `DebugHookAbsentTest` and `checkReleaseHasNoDebugHook` gain both): `--es cells <file>` (a made-up reading: one
   registered cell on 001-01 and neighbours with channel, PCI, RSRP; a stale one and an UNAVAILABLE one among them) and
   `--es cell_answer <file>` (a made-up cells 200 with `near`, stored as if the server answered). It shows: the `near
   heard=` line with the guards' counts; kind-5 rows with tags; a match, an ambiguous and a far reading; a
   `vibb_apple_cell` fix whose position moved and whose accuracy is C8's, on a Locate with Wi-Fi and GPS off; the
   anchor-only fix when the fake readings pull outside it; the `loo cell ... near_apple=` line after a mock GPS fix (D1
   allows mock in a debug build). The server half is the contract test, not the gate.
4. **The Jelly Star with a SIM** (release RC, new lines in `docs/testing/new-phone-model.md` §2): a day of `near heard=`
   lines (matched against ambiguous and far); the indoor cases of §7 - a visit with Wi-Fi unknown, the phone in a bag in
   the car - with a GPS fix after to compare; the `near_apple` counts after 30 days, which decide 137b (§10).
   **Multi-cell asks** (the server step's code QA, H1/M1): does Apple apply the count per asked cell? Where the Jelly is
   registered with two or more full-id cells of one standard heard, or by a probe as §1's (the request as
   `cells::request` builds it, count 12), ask two or three cells of one standard together and then each alone: 12
   neighbours around each asked cell, or 12 for the call in all? If 12 in all, a list stored from a multi-cell ask can be
   shorter than a lone ask's - the residual oracle N5 names (`route.rs`, "What is left of an oracle"); the answer
   decides whether it stays as it is or the server asks each cell alone.

Before the first release, by hand: the `protoc --encode` check of the new `golden_cells`.

## 10. Later: 137b (future work, with its trigger)

Not built in 137; a design note of its own when the trigger holds.

- **The trigger:** `near_apple` has **at least 20 samples, from at least 5 anchors, over at least 5 days** (N11), and
  its hits at C8's accuracy are at least 68 % with a mean error below the base fix's on the same samples. Less, or
  worse: 137b isn't written, and 137's refinement stays as it is (or goes, if the error is worse than the base's).
- **A tighter accuracy:** with three or more used cells, `max(floor, min(acc_C8, K x max(r_min, RMS) / sqrt(n_eff)))`,
  `n_eff = (sum w)^2 / sum w^2`, `K` from the counts (revision 1 proposed 1.5), **a floor of 300 m** (above
  `LEARNED_ACCURACY_MAX_M`, 200 m, so a cell fix never looks like a Wi-Fi fix).
- **Site grouping:** sibling sectors of one eNB (LTE: the ECI's top 20 bits) sit at one mast and are not independent;
  they count as one site in `n_eff`, so a three-sector site doesn't fake three witnesses.
- **The phone's own neighbour rows (kind 6):** keyed by the registered cell and the reading's tag, taught from teaching
  GPS fixes within 30 s, trusted at 3 occasions, their own cap (~1000 rows, 71 KB, beside the ceiling of N8), kept with
  the Apple switch off - so it works without Apple; own cell rows (kind 2) gain tags from the registered read. **NR**
  matches only this way (Apple gives no NR PCI), so NR readings come with kind 6.
- **Timing advance:** used only if step 4 shows `ta=` present often.

## 11. Question for the owner

- **Q1** (N5, QA M2) The cache's neighbour lists leave a weak oracle: a short list may tell a walker that some family
  phone once asked that cell together with others nearby. **Default: accept it** with the overlap cut (taken unless the
  owner says otherwise); the alternative is no `near` list at all, i.e. no 137.

## 12. Revision 2 - the design QA (2 H, 5 M, 8 L) and the reduced scope

- **Scope:** Apple's neighbours only; kind 6, the `K`/`n_eff` accuracy, the 300-m floor and site grouping go to 137b
  (§10) with their trigger; timing advance a count.
- **H1** (the fix could be taken away or leave the anchor): N10 - C8 on the full-id cells is the floor, readings
  admitted only within `r_a + r_row`, only readings dropped (weakest first), the result inside the anchor's range,
  else the base fix; the two test rows in §8.
- **H2** (a tighter accuracy unproven) and the old Q4: matched readings move the position at once with C8's accuracy
  and its 500-m floor; tightening waits for `near_apple` (§10's trigger).
- **M1** the throttle: only past `asked + NEAR_ASKED`; a throttled call leaves `near` NULL (N2, N3).
- **M2** the overlap cut instead of 15 km; the residual oracle in C5's terms (N5, Q1).
- **M3** the decoder keeps `network` for kinds 2 and 5; zeros or `key.take(16)` = no tag; `retagged` counted; the old
  table test (N8, §8).
- **M4** §7 rewritten: the case left is indoors without GPS or known Wi-Fi.
- **M5** candidates within `r_a + r_row + 500 m`, exactly one (N9).
- **L1** a bad wire type in the new fields is unknown (N1). **L2** the `near` order per asked cell, nearest first (N5).
  **L3** a failed store write is 503 (N5). **L4** `P_best` per channel (N10). **L5** the numbers fixed, Q1 answered by
  the probe (12 gave 12, 20 gave 20), NR deferred with 137b and its test row (N1, N6, §8). **L6** the migration number
  at merge (N4). **L7** kind 6 with 137b; the table's ceiling (N8). **L8** the hooks in `src/debug` with a release twin
  and `DebugHookAbsentTest`, the guards' unit rows, the Android versions named (N6, §8, §9).
- **Before the phone half:** §9's step 0, the SIM check, and what happens if it fails.
- The owner questions answered or moved: Q1 (count 12) by the probe; Q2 (no re-ask), Q3 (no mismatch witness), Q5 (TA
  logged) taken as decisions (§4, N12); Q4 by H2; Q6 (`reach`) dropped with 137b's later reader.
