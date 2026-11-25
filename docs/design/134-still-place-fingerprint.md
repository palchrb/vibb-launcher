# 134 - The same place after a small move: a fingerprint of what the phone heard

Status: **design, revision 1**, 2026-11-25 (branch `design-134`), read against `main` d036f5b33. Part (B) of PLAN's
item 1b. Part (A) - a failed 4-h cap try no longer ends `STILL` while the sensor hasn't fired; `still` keeps being sent
and the fresh fix is retried at 30 min, 1 h, 2 h, then every 4 h - is a bug fix built apart on branch `still-keeps` for
rc.4; this note builds on it. Asked by the owner on 2026-11-25: "med lite (ikke null) bevegelse kan den jo fortsatt være
i huset og så vil vi jo helst ikke feile lokasjon da". A launcher round; no server code, no contract file change, no
new PWA words.

## 0. Short answer

Today one significant-motion fire - the kid carrying the phone to another room - makes the next beat demand a fresh
fix. Indoors with Google Location Accuracy (GLA) off that often fails: GPS gets nothing in 15 s, and design 125's
learned fix needs at least three fresh usable APs and, after a move, three networks. The try ends with nothing, the
gate goes `QUIET`, and the PWA shows the fix's age instead of "Hasn't moved since".

At every good fix the phone now keeps a **fingerprint** of what it heard with it: the usable Wi-Fi APs with their
levels, the connected network, and the cells. After a move, when the heartbeat's try ends with no fresh fix and the
attempt's own scan still matches the fingerprint closely, the phone **re-sends the last good fix with `still: true`**
- the same renewal design 125's zone link and Apple fix already make. The fingerprint vouches still-ness only, never a
position. It is sealed on the phone like the Wi-Fi places table, never sent, cleared by the same clears.

The rule, in short: a **scan witness** (weighted Jaccard >= 0.5, two agreeing APs of two networks, the strongest
network heard now one of the fingerprint's) is enough on its own; **one AP** (the connected one) is enough only while
the sensor says still, and after a move only with a **cell witness** too. A match after a move holds one interval and
is checked again at the next beat before it holds 4 h. A scan **much poorer than the fingerprint** - fewer than half
its APs, none of its saved networks, or results older than the attempt's own scan request - is **inconclusive, not a
mismatch**: no renewal, no failure, one more scan in the same attempt when the throttle allows, else a try at the next
beat. The Jelly Star's 15:31 scan (one usable AP) was such a scan: the same unmoved place gave four usable APs of three
networks at 18:24.

## 1. What the phone does today (read in the code)

- **The flags and the heartbeat** (design 124, `zones/ZonePlan.kt`): `MotionFlags.motion()` gives `MOVED` after a fire
  or a new process, `STILL_SINCE_TRY` after a failed try, `STILL` while nothing fired since the last fresh fix.
  `heartbeatDue`: `STILL` (no try, `still: true`) while the last fresh fix is under 4 h old; `QUIET` within the back-off;
  else `FIX`. A fire always gives a try at the next beat.
- **The attempt** (design 125 D14, `places/WifiPlaces.attempt`, with the `network` provider off): after a move
  (`MOVED`, `NO_SENSOR`) or with the daily refresh due, `TOGETHER` - scan and GPS 15 s together, the learned or Apple
  fix beside; at a still cap try `LEARNED_FIRST` - the learned fix, then the zone link's renewal (`linkRenews`, 125 D12),
  then GPS. The snapshot at the attempt's start holds one scan (`scanNow`), the connection (`connected`) and the cells
  (`readCells`).
- **The renewals that exist**: 125 D12's zone link (the cap try while `STILL`, the vouch surely inside a linked zone, the
  connected network linked) and A9's Apple fix (while `STILL`, within `accOld + accNew` of the vouch). Both only while
  `STILL`; after a move nothing renews. Design 124 §8's Wi-Fi witness was dropped by design 125 D15 in favour of 125 D12.
- **Why that is not enough** (the Jelly Star, Android 16, 2026-11-25): still from 07:30; the 11:31 cap try renewed
  through the Apple lookup; at the 15:31 cap try `cells read=3 usable=0 registered=0`, `scan usable=1 trusted=0 networks=0`,
  `attempt path=LEARNED_FIRST result=none`, GPS failed in 15 s, then `QUIET` for 240 min. `usable=1` is below
  `scanView`'s three results, so no learned fix could be tried; `trusted=0` is counted over a `null` view and says
  nothing about the table. **The parent's "Oppdater posisjon" at 18:24, the phone unmoved at the same place**, gave
  `cells read=2 usable=0 registered=0 nr=0 stale=1`, `scan usable=4 trusted=2 networks=3 acc=60`, `attempt path=LOCATE
  result=renewed` 60 s after the start, `STILL renewed by an Apple fix`. So the 15:31 scan did not reflect the place: one
  usable AP, where the place gives at least four usable APs, two of them trusted, in a fix of three networks. `hear`
  counts every result the cache holds, whatever its age, and adds the connected AP when the results lack it; so
  `usable=1` was the connected AP alone or one result of a **partial or old cached list** - a refused or throttled
  `startScan` (the cache is then returned as it is), an unanswered one (8 s, then the cache), or a framework scan of a
  few channels that replaced the cache (§7). The `scan refusals` line of that hour tells which (§9 check 1). **Cells:**
  `registered=0` both times - on this phone cells are no second witness for now (whether it has a SIM is asked of the
  owner). Part (A) keeps this case `STILL`; part (B) is about the same day with a walk to the kitchen in it, and about
  not mistaking a poor scan for a different place.

## 2. Decisions - the fingerprint

D1. **What it is** (`StillPrint`, pure, in `places/PlacesPlan.kt` or its own `places/StillPlan.kt`): the fix time it
    belongs to (`fixTimeMs`, the vouch's own time), when it was taken (minutes), and:
    - **APs**: up to the **20** strongest usable results (design 125 D4's `usableAp`: unicast, locally administered bit
      clear, no `_nomap`/`_optout`, not a moving SSID, not the car's three-octet SSID), each as its 12-byte AP key, its
      8-byte network key (`PlaceKeys.ap`, `network`), its band, its level in dBm and a **saved** bit (its network name
      is one of the phone's saved networks, `getConfiguredNetworks` - the list design 131 already reads; the name is
      compared at that moment and not kept). One AP per key.
    - **The connection**: the connected AP's key, its level, its network key and design 131's binding (`linkConn`: the
      security class and Android's network id) - only when unmetered and not open or OWE; else none.
    - **Cells**: up to **8**, each as a key: a cell with a full identity by `PlaceKeys.cell` (design 125b's wire form),
      and a cell without one (a neighbour with only its channel and PCI) by a new **local key** `HMAC("pci|<radio>|<arfcn>|<pci>")`
      cut to 12 bytes; with its dBm and whether registered. A local key is never asked of anyone and means nothing
      off the phone.
    No SSID, no BSSID, no cell id, no position (the vouch is the cached fix, held where it is today).
D2. **When it is taken: with every fix that becomes the vouch.** Whenever `ZoneWatch.lastFreshFix` is stamped by a fix
    of one of our own attempts (the heartbeat, the low-battery fix, `locate`, a crossing's fix), the fingerprint is
    taken from that attempt's snapshot when (a) the fix is not on the move (`fixMoving` false), its accuracy is at most
    **100 m** and it isn't mock in a release build, and (b) the snapshot pairs with it: its results lie within **+-30 s**
    of the fix's elapsed time (design 125 D2's single-fix window) **or** no fire came between the snapshot and the fix
    (the `fires` count unchanged - a `locate` whose GPS answered at 50 s still pairs), and all are newer than the last
    fire. The scan must not be stale (125 D9's guard) and only its own results count (D5a); a refused scan gives a
    fingerprint of the connection and cells alone. A thin fingerprint (fewer than 3 APs) is filled by D3's additions
    at the next still renewals.
    **The invariant:** the fingerprint always belongs to the current last fresh fix (`fixTimeMs` equal), or there is
    none - a fresh fix that can't take one (a passive or kept fix, no paired snapshot, the `network` provider on) drops
    the old one.
D3. **At a renewal while still** (the owner's "and at a renewal"): when 125 D12's link, A9's Apple fix or D6's still tier
    renews the vouch with no fire since the fingerprint was taken, the fingerprint **gains** the APs and cells it lacks
    from that attempt's scan (up to the caps); it never drops an entry, changes a level or moves its anchor. After a
    move it is **never** changed: a chain of renewals each re-anchored on the last would let a slow walk - room,
    hallway, garden, street - match step by step.
D4. **Only where design 125's attempt runs**: a Regularly phone with a trusted motion sensor, the `network` provider
    off (GLA off) and Location on. With GLA on, Vibb scans nothing (125 H1) and GMS answers indoors; no fingerprint is
    taken or used. `NO_SENSOR` (or a distrusted sensor) has no still rule at all; nothing changes there.

## 3. Decisions - the match

D5a. **Which results are the scan's own.** `getScanResults()` hands back whatever the framework holds, of any age;
    `ScanResult.timestamp` (microseconds since boot) says when each AP was last heard. The print counts a result only
    when it is newer than **the attempt's own `startScan` request** (its elapsed time, kept by `scanNow`) - or, when
    `scanNow` took the cache without asking (125 D9's three fresh usable results already there), at most **30 s** old - and
    newer than the last fire. Everything older is not the place now, whatever the cache says.
D5b. **Conclusive, mismatch, inconclusive.** Against the fingerprint, the scan's own usable results are
    **inconclusive** when any of these holds:
    - fewer than **max(3, half the fingerprint's fixed APs)** of them;
    - the fingerprint holds an AP of a **saved** network and none of the scan's results is of a saved network - and
      the phone isn't connected to the fingerprint's network either (the connected AP is heard now, `WifiInfo`);
    - the scan was refused, throttled or unanswered (`scanNow`'s reasons) and the cache gave nothing newer than the
      request.
    An inconclusive scan is **neither a match nor a mismatch**: no W, no D7 mismatch, no renewal from it, and it never
    counts against the place. C (the connection, read live) may still renew in the still tier (D6). A **conclusive**
    scan is judged by D5. A fingerprint that is itself thin (fewer than 3 APs) never makes a scan inconclusive by the
    first rule.
D5c. **One more scan, when it helps.** When the attempt's first scan is inconclusive and the print would decide the
    attempt (the still tier before GPS; the moved tier when GPS ended with nothing), the attempt asks for **one more**
    `startScan` and waits for its callback up to 8 s (`SCAN_WAIT_MS`), at least **10 s** after the first request (a
    scan that came back short is often a framework scan of a few channels still running). One at most per attempt:
    two of the four scans the throttle allows a foreground-service app in 2 min (§7). The second scan's results are
    judged by D5a-D5b again; still inconclusive, the attempt ends as today without a renewal, and **the next due beat
    tries again** (D13). On the still tier the second scan runs while GPS hasn't started, so a match saves the GPS
    start; it is the one scan beyond the attempt's own this design adds, and only after a poor first one.
D5. **The three witnesses** (pure `printMatch(print, view, connection, cells, rows, nowMin)`):
    - **Scan witness (W)**, only on a conclusive scan (D5a-D5b; 125 D9's freshness rule holds within it: newer than the
      last fire, not stale):
      - **Fixed APs only**: a result whose own row in the places table is blocked or moved in the last 60 days
        (`moves > 0`, `lastMoveMin` within `BLOCK_MIN`) or whose Apple row is a tombstone doesn't count, on either side.
      - **Weighted Jaccard** over the union of AP keys: `J = sum(min(qF, qS)) / sum(max(qF, qS))`, `q` design 125 D6's
        `clamp((rssi + 100) / 40, 0.1, 1)`, an AP absent on one side 0. Strong APs (which stay) dominate; the faint
        far ones that come and go between scans weigh little. A plain Jaccard would let two faint neighbours outvote
        the home router.
      - **Agreeing APs**: in both, levels within **15 dB** (one or two inner walls and the body; a car parked by the
        house, heard at -80 dBm through a wall and at -40 inside it, never agrees).
      - **W holds** when `J >= 0.5`, **at least 2 agreeing APs of at least 2 networks** (a router's bands and a mesh's
        nodes are one network), and the **strongest usable result now belongs to one of the fingerprint's networks**
        (in the neighbour's house their own router is strongest).
    - **Connection witness (C)**: connected now, unmetered, not open or OWE, under the **same binding** as in the
      fingerprint (design 131: the security class and the network id - a network saved anew learns nothing here), to
      an AP whose key is the fingerprint's connected AP or one of its APs (a mesh roam), at a level within **15 dB** of
      that AP's in the fingerprint. Read from `WifiInfo`, so it holds when the scan was refused (the Jelly Star at 15:31).
    - **Cell witness (K)**: a full-identity key in both, or **2 local keys** in both. **Cells differ** when at least 2
      cells are read now, the fingerprint holds at least 2, and none is shared. A cell witness says "the same few km"
      and is never a witness alone.
D6. **Two tiers.**

    | Tier | When | Renews when | Never when |
    |---|---|---|---|
    | **Still** | a cap try (`STILL`, part (A)'s ladder) | W, or C | cells differ and W doesn't hold |
    | **Moved** | the heartbeat's `FIX` after a fire or a new process (`MOVED`) | W and not cells differ; or (C, or one agreeing AP) and K | anything in the attempt disagrees (D8) |

    One AP heard: first ask whether the scan is inconclusive (D5b) - on the Jelly Star at 15:31 it was, and the next
    beat's scan would have been judged instead. A place that truly has one AP (a lone house) leaves the fingerprint
    thin, so the scan is conclusive: in the still tier the sensor is the first witness and the connection the second -
    enough. After a move one AP needs a cell match (the owner's rule: two fixed APs or a cell match). With no
    registered cell - the Jelly Star at 15:31 and 18:24 - one AP after a move never renews (Q1).
D7. **A clear mismatch ends `STILL`.** In the still tier, on a conclusive scan (D5b) where the strongest usable result's
    network is not in the fingerprint and `J < 0.2`, the gate is marked moved (`MotionGate.markMoved`, logged
    `print differs`): the next beat tries, as after a fire. A sensor that missed a move is caught by the Wi-Fi it
    hears, not only by 124 D7's GPS contradiction. No count: a scan is not a position.
D8. **What vetoes a renewal after a move.** The attempt's own results come first and any of them that says elsewhere
    stops it: a fresh fix (GPS, a checked Wi-Fi fix) wins outright and becomes the new vouch with a new fingerprint; an
    unchecked Wi-Fi or Apple fix, the 2-min fallback or a cell fix further than both accuracies from the vouch (a cell
    fix: + 2 km, design 125b C9) vetoes and is handled as today; one within them doesn't - the renewal replaces it (the
    vouch is the better fix of the same place). design 131's jump gate refusing a Wi-Fi fix as impossible vetoes too.
D9. **The vouch must still be the newest reported fix.** A renewal re-sends `ZoneWatch.lastFreshFix` with `still: true`
    and the vouch's own design-131 check - never a new check. When something not fresh was reported after it (an
    unchecked Wi-Fi fix, a cell fix), the vouch is no longer the newest and the fingerprint is dropped: the server would
    otherwise stamp `resent_at` on an older row.
D10. **The numbers are a start.** 0.5, 15 dB, 0.2 and the counts are set in the synthetic town (§8) and then by the
    Jelly Star's leave-one-out counts (D16); the binding rows in §8 hold whatever they become.

## 4. Decisions - where it sits and what it may vouch

D11. **In the attempt** (`WifiPlaces.attempt`, the plan unchanged; a new `Kept.PRINT` in the pure `attemptKeep`):
    - `LEARNED_FIRST` (the still cap try): the learned fix that stands alone, else 125 D12's link, else **the still tier**,
      else GPS with Apple beside it (today's order). A still-tier renewal, like the link's, runs no GPS.
    - `TOGETHER` after a move: unchanged - scan, GPS 15 s and the learned/Apple fix together, the better kept. Only when
      that keeps nothing fresh does **the moved tier** decide between the renewal and today's outcome (D8). GPS always
      runs after a move: accuracy before battery (125 Q1).
    - `TOGETHER` for the daily refresh while still: GPS runs as today; when it gives nothing, the still tier may renew.
    - Not for a crossing's fixes (a fence fired: real evidence of a move), a crossing's confirm (`GPS_FIRST`) or
      `locate` (the parent asked for a position; Q10 of design 125b stands - a cell fix is reported).
    The `Ask` gains `printVouch` (the last fresh fix when the fingerprint belongs to it, whatever the motion) and
    `printTier`.
D12. **Still-ness only, never a position.** The renewal re-sends the last good fix; it is not fresh (no
    `lastFreshFixMs` stamp, no teach, no leave-one-out teacher), never a new vouch, never a zone arrival or departure,
    never the new side of 124 D7's contradiction. "Hasn't moved since" keeps meaning "still at that place": the vouch's
    circle, house-sized.
D13. **The flags after a renewal after a move.** `MotionFlags.afterTry` gains a `renewed` outcome: both moved flags
    clear, `stillSinceMs = now`, and `printHoldUntilMs = now + interval` (the CE prefs beside `vouch_renewed_ms`):
    `heartbeatDue`'s `STILL` holds only until then, so the next due beat is a still cap try (`LEARNED_FIRST`, a scan and
    no GPS on a match). A second match there holds the usual 4 h (`stillFrom` = the renewal). Two independent scans 30
    min apart before 4 h of trust; a car 30 min on doesn't match. A renewal in the still tier holds 4 h at once, as
    125 D12's. **After an inconclusive scan** (D5b, the second one of D5c too) and no other fix, the try is not a failure
    the back-off grows from: `afterTry` keeps the flags as they were before the attempt and `heartbeatDue` returns `FIX`
    at the next due beat (`printRetry`, CE prefs), at most **3 times in a row**; then today's rules - `QUIET` with
    design 124's back-off after a move, part (A)'s ladder while still. The fingerprint is kept meanwhile. While still,
    part (A) keeps sending `still` across these tries anyway; after a move the PWA shows the fix's age until a scan
    decides.
D14. **A print contradiction.** While the gate is `STILL` because of a moved-tier renewal (`printVouch` set at it,
    cleared at the next fresh fix), a fresh fix with no fire since the renewal that lies further from the vouch than
    both accuracies + 200 m (both <= 100 m) is a **print contradiction**, not 124 D7's motion contradiction: the sensor
    did its job, the fingerprint was wrong. Counted (CE prefs `wifi_places`, the last 7 days); **two within 7 days turn
    the moved tier off for 7 days** (logged `print moved tier off until ...`; the still tier stays). Each one is also
    a design-131 disagreement (`zone_watch.wifi_disagreed`, one rise an attempt): the server's quiet security-log line
    "Wi-Fi and GPS disagreed" already words it.
D15. **The PWA: no new words.** A renewal is the `still: true` re-send the server already reads: "Hasn't moved since
    07:30" / "Har ikke flyttet seg siden 07:30" on Locate (`locate.all.still`), "Here now" kept by a fine vouch's
    re-send as today, and design 131's rule that a Wi-Fi-alone vouch's re-send keeps nothing fresh. No report field, no
    contract change, no server code.
D16. **Counts, for the tuning.** Per local day, 30 days, in `DayCounts` beside the others and its counts line:
    renewals by tier, misses by reason (`inconclusive`, `j`, `agree`, `strongest`, `cells`, `veto`), second scans and
    whether they decided, `print differs`,
    print contradictions. **Leave-one-out:** at every fresh GPS fix of teaching quality taken after a move, what the
    moved tier would have said against whether the GPS fix lies within both accuracies + 50 m of the vouch -
    `print loo same=9/10 differ=4/4`. That is how the Jelly Star tells whether 0.5 and 15 dB hold. Logged under
    `WifiPlaces`, counts and `J` to one decimal only: `print taken aps=7 conn=yes cells=2`, `print tier=moved j=0.7
    agree=4 networks=3 conn=yes cells=match RENEW`, `print dropped (vouch|age|off|unenrol|keybroken)`.

## 5. Storage, privacy, retention

P1. **Sealed on the phone**: one file `noBackupFilesDir/still_print_v1` (credential-encrypted, `AtomicFile`), sealed
    whole with `Secrets.box` under AAD `still_print_v1`, keyed by the places table's HMAC key (so its keys compare with
    a scan's `Heard` keys directly). Format: magic `VSP`, a version byte, `fixTimeMs`, the taken minute, the counts and
    fixed-width entries; under 1 KB. A new version drops the old file (the no-compat rule). Written only when the
    fingerprint changes (a new vouch, D3's additions).
P2. **Never sent**: not to the server, not in a report, not in a log (counts and `J` only; `PlacesTest`'s source check
    over every `Log` call in `places/` covers the new code).
P3. **The minimum**: 20 APs and 8 cells by keyed hash, their levels and bands, one connection's binding; no SSID, no
    BSSID, no position, no history - one fingerprint, the current vouch's, never a list of places.
P4. **Cleared** by `WifiPlaces.clear` with the table (Off, unenrolled, a new enrollment, a broken or unreadable key);
    dropped when its vouch is replaced (D2), when something not fresh is reported after it (D9), and **7 days** after it
    was taken (Q3): a phone left a week in one house where no fix works shows the fix's age after that.
P5. **What it reveals to someone holding the unlocked phone and its key**: that the phone was near these keyed APs at
    the vouch's time - less than the places table beside it already holds.

## 6. Risks

| Case | What happens | Bound |
|---|---|---|
| A bus, train or car Wi-Fi in the fingerprint | moving SSIDs and locally administered BSSIDs (phone hotspots) are never in it (D1); an AP the table saw move is not fixed (D5); after a move one AP needs a cell match (D6); a car AP heard through a wall at the vouch disagrees by level inside the car (15 dB) | a wrong renewal holds one interval (D13); the sensor is re-armed after every try, so a ride that goes on fires again and the next beat's GPS catches it; two print contradictions turn the moved tier off (D14) |
| A travel router or MiFi brought along | as above; at a new place its neighbours and cells differ, so W fails on `J` and the strongest network | D6, D13 |
| MAC randomisation | the phone's own randomised MAC doesn't matter (we read the APs'); APs with locally administered BSSIDs - some guest and mesh backhaul networks - are left out, so a home may have fewer usable APs; Wi-Fi 7 links by their MLD address are one device (125 D5) | fewer witnesses, never a wrong one |
| A neighbour's AP heard from the street | weighted by level, so faint ones count little; the strongest network now must be the fingerprint's; agreeing needs 15 dB | the synthetic town's street and neighbour rows (§8) |
| The neighbour's house hearing ours | their own router is strongest there | W fails; never a renewal (§8 binding row) |
| The scan refused, throttled, partial or old (the Jelly Star's 15:31) | inconclusive, never a mismatch: one more scan when it would decide, else the next beat tries (D5a-D5c, D13); C alone still renews in the still tier | three inconclusive tries in a row, then today's back-off |
| A stale (repeated) scan | its repeated results are suspect; with 125 D9's stale verdict the scan is inconclusive | D5a, D5b |
| The phone moved and came back between beats | the same place: a renewal is true | - |
| The vouch is old (a still week) | the fingerprint is dropped after 7 days | P4 |
| A process start or night self-update | `MOVED`; the try runs the moved tier: a strong scan renews | D13's one-interval hold |
| A cloned AP | needs two networks or a cell; any fix elsewhere vetoes | as design 125 §7 |
| The router rebooted onto another channel or band | the AP key is the BSSID, unchanged; a dual-band router's other BSSID is a new key | `J` drops a little |

## 7. Android 14-17, battery

- **Scans and their throttle** (AOSP `ScanRequestProxy`, the same on 14-17 as far as the behaviour-change lists say;
  the exact class split is re-read in AOSP at build time). `startScan()` is deprecated since 9 and still works. A
  caller whose importance is foreground service or better gets **4 scans per 2 min**; every caller further in the
  background shares **one scan per 30 min**. Exempt are only callers holding `NETWORK_SETTINGS` or
  `NETWORK_SETUP_WIZARD` (Settings, the setup wizard) and every caller when the developer option "Wi-Fi scan
  throttling" is off. **A Device Owner is not exempt** and can't switch the option off (`wifi_scan_throttle_enabled`
  isn't in `setGlobalSetting`'s list, design 125 §5). Vibb's anchor (`specialUse` FGS) puts it in the first class;
  without the anchor the gate is `MOVED` anyway (design 124 D4) but the moved tier would then scan in the second
  class - the count of `throttled` refusals shows it. **A throttled `startScan` returns `false` and starts nothing;**
  `getScanResults()` then returns whatever the framework holds - results of the last scan anyone ran, possibly a
  connected-mode scan of a few channels, possibly an hour old. A `true` may still bring no callback (the HAL busy, a
  failed scan): `scanNow` waits 8 s and takes the cache. **So the results' own `timestamp` is the only proof of a
  fresh, full answer** - which is what D5a judges by. Whether MediaTek's Wi-Fi HAL answers our scans in deep Doze
  with the screen off is the Jelly Star's (§9 check 1).
- **Battery**: the fingerprint is taken from, and matched against, the attempt's own snapshot; the one extra scan
  (D5c) comes only after a poor first one and only when it would decide (~0.02-0.1 mAh, design 125 §6). A still-tier
  renewal runs no GPS (like 125 D12's), so each matching cap try saves a GPS start (0.25-0.4 mAh, design 64 §5); after a
  move GPS runs as today; D13's re-check is a scan with no GPS on a match. CPU: a few dozen comparisons; one AES-GCM
  seal per vouch change.
- **APIs**, all at or under minSdk 34: `ScanResult.level`/`timestamp` (17), `WifiInfo.getRssi`, `getBSSID` (02:00:..
  without fine location and Location on - the fix window holds both), `getNetworkId` and `getCurrentSecurityType`
  (31; design 131's gate read the ids on Android 14), `CellIdentityLte.getPci`/`getEarfcn` (24),
  `CellIdentityNr.getPci`/`getNrarfcn` (29), `CellIdentityWcdma.getPsc`/`getUarfcn` and `CellIdentityGsm.getArfcn`/
  `getBsic` (24), `CellInfo.isRegistered` (17). `getAllCellInfo` returns cached cells; one `requestCellInfoUpdate`
  per attempt (the modem answers at most every 2-10 s, design 125b C1). Cells need fine location and Location on,
  not `READ_PHONE_STATE`.
- **Per version**: 14-16's behaviour-change lists name nothing for scan results, `WifiInfo` or cell info (as design
  125 §5 read them); 17's list is read at build time - in particular whether `getConnectionInfo()` still gives the
  BSSID (else design 125 L3's `NetworkCallback` with `FLAG_INCLUDE_LOCATION_INFO`). Whether MediaTek's Wi-Fi HAL
  answers a scan in deep Doze is the Jelly Star's (§9 check 1).

## 8. Steps, tests, the emulator gate

| Step | What | Size |
|---|---|---|
| **L1** (launcher, one commit, after `still-keeps` is merged) | D1-D16 with D5a-D5c (`scanNow` keeps its request's time; the second scan): the pure `StillPrint`, `printMatch`, the tiers, `attemptKeep`'s `PRINT`, the codec; the glue in `WifiPlaces` (take, store, judge, clear), the local cell key in `cellInput`; `MotionFlags.afterTry`'s `renewed`, `heartbeatDue`'s hold, the print contradiction in `ZoneWatch`; the counts | ~300 lines of code + ~350 of tests |
| **Docs** (same commit) | `docs/notes/launcher/wifi-places.md` (a section "The same place after a small move"), `zones-and-power.md`'s still section (D13, D14), `docs/testing/new-phone-model.md` §2 (§9 below) | - |
| **E** (with the owner's OK) | the emulator gate §6 row set below, Android 14 and 17 | - |
| **J** | §9 on the Jelly Star, a week | - |

**Tests (pure plan tests only).** `PlacesTest` over a new `still_print` section of `wifi_places.json`: the take
(accuracy, moving, mock, the +-30 s pairing and the no-fire pairing, a stale scan, a refused scan giving connection and
cells only, the invariant - a passive fresh fix drops it), D3's additions (still: added; after a fire: unchanged), each
witness (J by hand on small cases, agreeing at 14/15/16 dB, the strongest network, the binding, open/OWE/metered
refused, a mesh roam, full and local cell keys, cells differ), D5a's own results (older than the request, cached within 30 s, newer than the fire), D5b's three inconclusive causes
and a thin fingerprint never inconclusive by count, D5c's second scan (only when it would decide, at most one, 10 s
after the first), the tier table row by row, D7's mismatch (never on an inconclusive scan), D8's vetoes
(GPS wins, an unchecked fix within and outside, a cell fix, an impossible jump), D9 (a later non-fresh report drops
it), the codec round trip and a new version dropped, the clears. `ZonePlanTest`: `afterTry(renewed)`, the hold of one
interval then 4 h, an inconclusive try keeping the flags and trying at the next beat - three in a row, then the
back-off -, D14's contradiction vs 124 D7's, the 7-day moved-tier switch. **The synthetic town** (design 125's,
log-distance with walls and 6 dB shadowing) gains rooms: a fingerprint taken in each room of a house, matched from
every point. The binding rows:
1. **Every room of the same house** (up to ~15 m and two inner walls): at least 90 % of points renew in the moved tier.
2. **The neighbour's house** with its own router, 20-40 m away: **never** renews, either tier.
3. **Any point further than 60 m**: never renews in the moved tier.
4. **A bus AP** missing from the moving list in the fingerprint, the phone on the bus 1 km on: never (one AP, cells
   differ); **a parked car's AP** heard at -80 at the vouch, the phone in the car at -40: never.
5. **The one-router house**: the still tier on the connection alone renews; the moved tier without cells doesn't, with
   the same registered cell does.
6. **Chaining**: room -> hallway -> garden -> street, a fire and a try at each: no renewal past the garden, and the
   fingerprint unchanged throughout.
7. **The Jelly Star's day as a row**: a fingerprint of four APs of three networks, two saved; a scan of the connected
   AP alone (15:31) - inconclusive, no renewal, no `QUIET`; the full scan at the next beat (18:24's) - a match.

**The emulator gate** (`docs/testing/emulator.md` §6bo, written with L1, Android 14 and 17, the owner's OK). Neither
image has a significant-motion sensor (§6bn row b: "not reachable"), so L1 adds to the **debug build only**
(`src/debug`, `PlacesDebugReceiver`) a fake trigger: `--es motion sensor|fire|off` - a sensor present, a fire, gone -
feeding `MotionGate` as the real one would - and an age for the made-up results (`--ei age_s N`; today they are
always heard now) with `--ez refuse true` (the next `startScan` counts as throttled); nothing in a release build. With it and §6bn's made-up scan, connection
and satellite feed:

| # | Step | Expect |
|---|---|---|
| a | A satellite fix at home with home's scan (three networks) | `print taken aps=.. conn=yes` |
| b | `fire`, GPS silent, home's scan at other levels (within 15 dB) | `print tier=moved .. RENEW`; Locate "Hasn't moved since" the fix's time; the next beat a `LEARNED_FIRST` try, `print tier=still .. RENEW`, no GPS |
| c | `fire`, GPS silent, a neighbour's scan (their network strongest) | no renewal; the fix's age on Locate |
| d | Home's connection alone (no scan), `fire` | no renewal (one AP, the emulator's cell alone is no second witness unless it matches); then with no fire at the cap try: `RENEW` |
| e | After (b), the satellite feed 2 km away, no fire | `print contradiction`; twice -> `print moved tier off` |
| f | Location Off from the PWA | `print dropped (off)` |
| g | After (a), `fire`, GPS silent, the scan with only the connected AP and `refuse` | `print .. inconclusive`, no second scan (refused); no renewal; the next beat tries (no `QUIET`) |
| h | As (g) with home's full scan given `age_s 600` | inconclusive (older than the request); with `age_s 0` at the second scan: `RENEW` |

**What can't be faked there**: real room-to-room levels and their spread, a real neighbour list, MediaTek's scans in
deep Doze, neighbour cells (the emulator has one registered cell; whether the console can change its identity is noted
in the run, else cell rows are plan tests only), the time a real sensor takes to fire.

## 9. The Jelly Star (`docs/testing/new-phone-model.md` §2)

1. **The 15:31 case again** (a still afternoon indoors, screen off for hours): the `scan refusals` line and `dumpsys
   wifi` for uid 10232's scan requests at the cap try - refused, unanswered, or answered with a short list? The
   results' ages (a debug line in L1: the newest and oldest result's age, counts only); whether a second scan (D5c)
   brought the place back; how often a cap try is inconclusive in a day. Whether the phone has a SIM, and the read
   cells' kind (`dumpsys telephony.registry | grep -i cellinfo`: do they carry a PCI and channel?).
2. **A day at home with walks between rooms**: `print tier=moved` lines and their `J`; Locate keeping "Hasn't moved
   since" across the walks; `print loo` after a few days.
3. **A walk to a neighbour and a drive**: no renewal; GPS fixes on the way.
4. **Battery**: GPS starts per day before and after (`dumpsys location`), against design 124 §6's measurement.

## 10. Questions for the owner (defaults in bold)

- **Q1** One AP and no registered cell after a move (the Jelly Star at 15:31 and 18:24; whether it has a SIM is
  asked apart): **no renewal - the fix's age shows until a fix works**
  / renew on the connected AP alone when the places table has seen it settled (two days, never moved).
- **Q2** After a move, a match holds **one interval and is checked again by a scan at the next beat** / 4 h at once
  (fewer scans, a wrong match lasts longer).
- **Q3** The fingerprint is kept at most **7 days** / 24 h / 72 h.
