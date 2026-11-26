# 134 - After a small move: honest scans now, a fingerprint in shadow

Status: **design, revision 3**, 2026-11-25 (built on branch `still-print`), read against `main` d036f5b33. Revision
1 was 5e980281a; revision 2 applies the owner's scope decisions of 2026-11-25 and the design QA findings (H1-H3,
M1-M6, L1-L5; §11); revision 3 adds the one rule the owner approved the same evening: **a scan mismatch ends `STILL`**
(§3a, D15-D19; §12) - the mismatch direction acts, the match direction stays shadow-only; revision 4 (§13, D20-D25,
the owner's addition the same night, branch `still-connected`) lets **the connected router witness the still vouch**
at a cap try or retry while `STILL`, its code QA applied (§13a) with the owner's answer (b) (§13b). Part (B) of PLAN's item 1b. Part (A) ships apart on branch `still-keeps`: while the sensor
hasn't fired, a failed cap try no longer ends `STILL`; `still` keeps being sent and the fresh fix is retried at **the
next two beats, then after 1 h, 2 h, and then every 4 h**. Asked by the owner on 2026-11-25: "med lite (ikke null)
bevegelse kan den jo fortsatt være i huset og så vil vi jo helst ikke feile lokasjon da". A launcher round; no server
code, no contract file change, no PWA words.

## 0. Short answer

Two parts, one build:

1. **Built to act now: an inconclusive scan after a move.** A heartbeat try after a motion fire judges the place by a
   scan. When Android refused that scan, answered it with results older than our request, or didn't answer in 8 s,
   the scan is **inconclusive**: it says nothing about the place. The attempt asks once more when a fresh scan could
   decide; otherwise it ends **without reporting anything** - no worse fix, no failure, no back-off - and the next beat
   tries again, up to 3 times. A fresh scan that honestly hears one network or none (a cabin) is a valid answer and
   gets no retries. While `STILL`, only (A)'s ladder applies.
2. **Built to measure only: the fingerprint, in shadow.** At each good fix the phone keeps a sealed fingerprint of
   the settled Wi-Fi devices it heard. After a move it computes whether the strict rule *would* renew the vouch,
   records that verdict and, once the next real fix arrives, how far away it was. **It never renews, vouches or
   reports anything.** The owner reads the record over adb after 2-4 weeks
   (`dumpsys activity service ... still-print`); whether the fingerprint may ever act is decided then, in a later
   revision.
3. **Built to act (revision 3): a scan mismatch ends `STILL`.** While the phone says `STILL`, a cap try or retry
   ((A)'s ladder) whose scan is fresh and conclusive and **clearly isn't the place** - none of the fingerprint's
   settled devices heard at all, and either 3 other devices heard or the registered cell placed more than 1 km past its
   range from home (code QA H1 and the owner's refinement: cells only back a Wi-Fi absence, by position) - and that no renewal in the same attempt contradicts (code QA M1) ends `STILL` at once: no `still`, the PWA shows the fix's
   age, `QUIET` with design 124's back-off, and the next tries run GPS as after a fire. No satellite fix is needed to
   stop vouching. It never vouches, renews or moves a position; a match still decides nothing (2). (A)'s 24-h ceiling
   stays the last backstop.

## 1. What the phone does today, and what the Jelly Star showed

- **The flags and the heartbeat** (design 124, `zones/ZonePlan.kt`): `MotionFlags.motion()` gives `MOVED` after a fire
  or a new process, `STILL_SINCE_TRY` after a failed try, `STILL` while nothing fired since the last fresh fix.
  `heartbeatDue`: `STILL` (no try, `still: true`) while the last fresh fix is under 4 h old; `QUIET` within the back-off;
  else `FIX`. A fire always gives a try at the next beat; a failed try after a move clears `movedSinceTry`, so the next
  beats are `QUIET` with design 124 D5's back-off and the PWA shows the last fix's age.
- **The attempt** (design 125 D14, `places/WifiPlaces.attempt`, the `network` provider off): after a move `TOGETHER` -
  scan, GPS 15 s and the learned or Apple fix together, the better kept, then a cell fix (design 125b C9), then
  last-known. `scanNow` returns the cache as it is when it already holds three fresh usable results, else calls
  `startScan()` and waits up to 8 s for `onScanResultsAvailable`; refused (`false`) or unanswered, it returns the cache
  as it is. `hear` then counts **every** cached result, whatever its age, and adds the connected AP when the results
  lack it. Nothing today asks whether the results answer our request.
- **The Jelly Star, Android 16, 2026-11-25.** Still from 07:30; the 11:31 cap try renewed through the Apple lookup. The
  **15:31 cap try**: `cells read=3 usable=0 registered=0`, `scan usable=1 trusted=0 networks=0`,
  `attempt path=LEARNED_FIRST result=none`, GPS failed in 15 s, then `QUIET` for 240 min. The **parent's "Oppdater
  posisjon" at 18:24**, the phone unmoved at the same place: `cells read=2 usable=0 registered=0 nr=0 stale=1`,
  `scan usable=4 trusted=2 networks=3 acc=60`, `attempt path=LOCATE result=renewed` 60 s after the start, `STILL
  renewed by an Apple fix`. So the 15:31 list did not reflect the place.
- **Why a list can be that short** (QA L1, AOSP `ScanRequestProxy` and `WifiConnectivityManager`, to be re-read at the
  pinned Wi-Fi mainline version at build time): `getScanResults()` returns the cache of the last **full-band** scan; the
  framework's own partial (few-channel) connectivity scans go into a separate LRU and don't replace it; an app's
  `startScan()` is always full-band. So a one-AP list is not a partial scan that overwrote the cache: it is a full
  scan that heard little (a short dwell or a radio busy in deep Doze), or a cache that was emptied or never refilled
  while our request was refused or unanswered. The results' own `timestamp` and the `scan refusals` line tell which -
  the Jelly Star check (§9). **Cells**: `registered=0` both times; on this phone cells witness nothing for now.

## 2. Decisions - the inconclusive scan after a move (acts)

D1. **Which results answer our request.** `scanNow` keeps the elapsed time of its `startScan()` call
    (`requestElapsedMs`) and its outcome (`answered`, `refused`, `unanswered`, `wifioff`, or `cache` when it took the
    cache without asking). A result answers the request when its `ScanResult.timestamp` (microseconds since boot) is
    later than `requestElapsedMs`; with `cache`, when it is at most 30 s old. Both also newer than the last fire
    (design 125 D9's rule).
D2. **Inconclusive is defined by freshness only** (the owner; QA M1). A scan is inconclusive when:
    - the request was **refused** (`startScan()` returned `false`: throttled) - unless the cache path already had a
      fresh answer;
    - **no result answers it** (D1) - every result is older than the request, or the cache is empty with Wi-Fi on;
    - it went **unanswered** in 8 s;
    - or the phone is **connected** (a live `WifiInfo` BSSID) to a network that none of the answering results
      contains. Kept because it is a sign of a list that isn't the place now: the connected AP is the one the phone
      hears best, on a channel every full-band scan covers, so a fresh full answer that lacks it didn't really scan.
      The check runs before `hear` adds the connected AP.
    **Not inconclusive:** a fresh answer with one usable AP, or none (the cabin with 0-1 Wi-Fi; the Jelly Star's
    `usable=1` would count only if its result answered the request). The count of results never makes a scan
    inconclusive.
D3. **One more scan, only when it would decide** (D5c of revision 1, kept). After a move, when the first scan is
    inconclusive and the attempt is about to end with no fresh fix (GPS gave nothing), **and** a fresh scan could give a
    fix - the places table holds at least two trusted networks, or the Apple switch is on and a network is up - the
    attempt calls `startScan()` once more, at least **10 s** after the first request, and waits up to 8 s. One at most
    per attempt: two of the four scans the throttle allows the anchor's importance in 2 min (§7). Its answer goes
    through the learned fix and the Apple lookup as the first would have. Not when the first was refused while Wi-Fi
    is off - and (decided at the build, as gate row (c) says) not after a throttled refusal either: the throttle that
    refused the first refuses a second within its 2-min window. A refusal while Wi-Fi is off is conclusive (nothing
    can be heard, nothing would be by asking again).
D4. **After an inconclusive try** - the scan (and the second one, when D3 ran) inconclusive, and no fresh fix:
    - **nothing is reported** from the attempt: no cell fix, no unchecked Wi-Fi or Apple fix, no 2-min fallback, no
      last-known - never a worse fix than the last good one. The server keeps what the last report said;
    - **no failure**: `MotionFlags.afterTry` keeps the flags as they were before the attempt (`movedSinceTry` stays
      set), so the gate stays `MOVED`, `QUIET` can't start and the back-off doesn't grow;
    - **the next beat tries again** (a move always tries), up to **3** inconclusive tries in a row
      (`inconclusive_tries`, CE prefs `zone_watch`, reset by any fresh fix or conclusive try);
    - the fourth is an ordinary failed try: today's outcome and design 124's back-off.
    Logged `ZoneWatch: Location heartbeat: FIX result=inconclusive reason=refused|old|unanswered|noconnected n=2/3`.
D5. **While `STILL`** - a cap try, (A)'s ladder - an inconclusive scan counts as a **cap fail** (QA M2): (A)'s ladder
    goes on, no retry of this design, no extra scan. `locate`, a crossing's fixes and the low-battery fix are
    unchanged.

## 3. Decisions - the fingerprint (shadow only)

D6. **Never acts** (but D15's mismatch, revision 3, which only *stops* a vouch). No renewal, no `still: true`, no vouch, no report, no change to the flags, the heartbeat, the
    attempt's keep, `wifi_disagreed` (QA L2) or any count the server sees. Its only outputs are the sealed ring (D12),
    the dump (D13) and log lines. The still tier of revision 1 is gone; the moved tier only records.
D7. **What it is** (`StillPrint`, pure, `places/StillPlan.kt`): the anchor fix's time (`fixTimeMs`), when it was
    taken, the boot it was taken in, and up to **12 devices** - design 125 D5's device key (the MLD address or the
    first five octets: a router's bands and links are one device), its network key, its strongest level in dBm and the
    number of its APs heard. Only **settled** devices (QA H3): at least one of its APs has an own Wi-Fi row in the
    places table with `days >= 2`, `moves == 0` and not blocked; Apple rows don't make a device settled. No SSID, BSSID
    or position, and no cells: D15's cell witness is the serving cell's own position (the owner's refinement of code QA
    H1), so the print needs none (format version 2; the build's first version kept the cells' keys).
D8. **When it is taken** (QA M4). With a fix that becomes the vouch (`ZoneWatch.lastFreshFix` stamped by one of our
    own attempts) when the fix is `gps`, or a checked Wi-Fi fix, with accuracy **<= 50 m**, not moving (`fixMoving`
    false), not mock in a release build, **and** a scan that answers the attempt's request (D1, not inconclusive by D2)
    whose results lie within **+-30 s** of the fix's elapsed time. Otherwise the old fingerprint is dropped and none is
    taken. **Additions** (QA M5): at a 125 D12 or A9 renewal while still, the fingerprint gains devices it lacks only
    when that attempt's scan was conclusive and the strict rule (D9) held on it; it never drops a device, changes a
    level or moves its anchor.
D9. **The strict verdict** (`shadowVerdict(print, scan, rows, nowMin)`), computed on a conclusive scan (D2) after a
    move. Devices on both sides as in D7 (settled only); "now" levels from the answering results. It **would renew**
    when all hold, else records the first that fails as its reason:
    1. `thin` - the print has at least **3** settled devices (QA H3: counted as devices, never by network name).
    2. `anchor` - the print's strongest device is heard now within **15 dB** of its print level, and the **strongest
       usable result now** (over all usable results, settled or not) belongs to one of the print's **two strongest
       devices** (QA H1: in a neighbour's house or flat their own router is strongest, settled in our table or not).
    3. `agree` - at least **2 agreeing devices**: print level **>= -70 dBm** and heard now within 15 dB. Faint devices
       never agree.
    4. `similar` - over the print's devices at -70 dBm or stronger, the **mean absolute level difference** (a device
       not heard now counts 30 dB) is at most **12 dB**. No saturating clamp: near APs differ in dB as much as far ones.
    5. `veto` - nothing in the attempt says elsewhere: no fresh fix further than both accuracies from the vouch, no
       unchecked Wi-Fi or Apple fix outside them, no cell fix past them + 2 km, no impossible jump (design 131).
    Also recorded, for the tuning, not in the verdict: `J`, a weighted Jaccard over settled devices with the linear
    weight `max(0, dBm + 100)` (no clamp), and the counts of near devices (>= -70 dBm) on each side.
D10. **When an event is recorded.** At every heartbeat `FIX` after a move (`MOVED`, not `NO_SENSOR`) while a fingerprint
    exists: with a conclusive scan, the verdict; with an inconclusive one, an event of kind `inconclusive` and its D2
    reason. The verdict is recorded whatever the attempt kept, so a GPS fix in the same attempt is its outcome at once
    (leave-one-out).
D11. **The outcome.** The next genuinely fresh fix of accuracy <= 100 m (GPS, a checked Wi-Fi fix) after an event is its
    outcome: the distance from the vouch rounded to 10 m, the minutes since the event, the fix's accuracy class, and
    whether a fire or a boot came between (`moved_after`). Classes: **confirmed** - would-renew, no fire between, distance <=
    both accuracies + 50 m; **contradicted** - would-renew, no fire between, further; **would-not** - with the same
    distance kept, so a too-strict rule shows too; **unjudged** - a fire between, no outcome within 24 h, or the
    fingerprint dropped first.
D12. **The ring** (sealed, on the phone): the last **500** events, 37 bytes each (`still_print_ring_v1`, ~18.5 KB at 500, P1);
    events older than 60 days drop out. Each holds the time (minutes), the kind (`verdict`, `inconclusive`, and since
    revision 3 `mismatch` - D19), would
    yes/no and the reason, `J` and the mean difference to one decimal, the device and near-device counts on both
    sides, the agreeing count, and the outcome fields once known. **Never a BSSID, SSID, key or coordinate.** Each event
    is also logged under the tag `StillPrint` (`StillPrint: verdict would=yes j=0.7 diff=6.5 devices=4/4 near=2/2
    agree=2`, `StillPrint: outcome confirmed d=20 after=34`).
D13. **Reading it on the release build.** `CommandListenerService` (the anchor, always running) overrides
    `Service.dump`: with the argument `still-print` it prints the summary - verdicts by class (confirmed,
    contradicted, would-not by reason, unjudged), inconclusive tries by reason, mismatches by clause and outcome
    (revision 3, D19), prints taken and dropped by reason - then the ring, newest first. Without that argument it prints one line and nothing of the ring, so a bug report
    carries none of it. The framework lets only a caller holding `DUMP` reach it (adb's shell has it); it needs no
    debuggable build:
    `adb shell dumpsys activity service me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService still-print`.
D14. **Dropped** (QA M3): at a boot (`BootClock.bootCount()` other than the print's), at GLA on (the `network`
    provider enabled at an attempt), when the still rule is off (not Regularly, unmanaged), at sensor distrust (124
    D7), when its vouch is replaced, when something not fresh was reported after its vouch, **72 h** after it was taken
    (QA L4: a print describes the place of one vouch, so its life is short; the ring of outcome events keeps 60 days),
    and at every Wi-Fi places clear. Each drop logged with its reason. A mismatch (D15) doesn't drop it: its vouch is
    still the last fresh fix, and its outcome is judged against it.

## 3a. Decisions - a scan mismatch ends `STILL` (revision 3, acts)

The owner, 2026-11-25 evening: the mismatch direction may act; the match direction stays shadow-only (D6).

D15. **The rule.** While `motion` is `STILL` the heartbeat's `FIX` (the cap try at 4 h and every retry of (A)'s
    ladder) scans as today. When that scan is **fresh and conclusive** (D1, D2) and **clearly isn't the place** of
    the fingerprint (`printMismatch`, pure, `places/StillPlan.kt`), and the attempt brings no genuinely fresh fix,
    `STILL` ends at once - before a GPS fix, and without one. Clearly not the place (code QA H1, approved by the
    coordinator: `wifiNone && (devicesHeard >= 3 || cellElsewhere)`) needs **none** of the print's settled devices heard
    at all (any level) among the answering results' usable APs, and then one of:
    1. `wifi` - the answer holds at least **3 distinct devices** (code QA L4: one router's bands and links are one);
    2. `cell` - a thinner answer (a cabin of one or two networks), and the **registered cell read now is placed away
       from home** (the owner's refinement, 2026-11-25): its position - a trusted own cell row (design 125 D10) or a live
       Apple cell row (design 125b's answers) **already in the table** - lies further from the vouch than the cell's range
       (`rangeM`: own rows floored at 500 m, Apple's `max(500 m, acc)`), the vouch's accuracy and **1 km**
       (`cellElsewhere`, `CELL_ELSEWHERE_MARGIN_M`). A position unknown (no row, not looked up yet, the Apple switch
       off), no registered cell or no vouch: no witness. No lookup is ever started for this: the attempt uses the table
       as it is (while `STILL` design 125b's cells ask doesn't run, so in practice rows learned or answered before).
    Cells only back a Wi-Fi absence and only by position, never by id: a still phone changes its serving cell often (a
    carrier or an edge flip) and another id at the same place says nothing. **Why 1 km**: the range is already the
    cell's reach as the table knows it, so range + the vouch's accuracy bounds where a phone at the vouch may hear it;
    1 km more covers a serving cell kept past that reach (handover hysteresis, an edge flip) and a centroid's error,
    while a car ride of a few km to a town house is still caught. It is less than the 2 km of design 125b's cell veto
    and cell contradiction (a fix's position alone) because here the Wi-Fi absence must hold too: two witnesses.
    Logged `ZoneWatch: Location heartbeat: STILL ended by a scan mismatch` (with the clause, `wifi|cell`; counts only).
D16. **What ending means.** The same as a fire seen at that try, then the try failing: `MotionFlags.scanMismatch`
    sets `movedSinceFresh` (so `motion` is `STILL_SINCE_TRY`), keeps `movedSinceTry` clear and sets `stillSinceMs` to
    the try, so the next beats are `QUIET` with design 124 D5's back-off (`stillBackoffMs`: one interval, then growing
    to 4 h) and the tries after it run as after a fire (GPS, the Wi-Fi places; D3/D4 don't apply: it is not `MOVED`).
    That try reports **no fix** and no `still` (`capTryResends` false): the server keeps the vouch, the PWA shows its
    age. (A)'s count (`still_cap_fails_v1`) is cleared. It never vouches, never renews, never moves a position, never
    reports a fix: it only stops vouching. A fresh fix in the same attempt wins (it is the new vouch, design 124 D7
    checks it as before). **A renewal vetoes it** (code QA M1, approved by the coordinator): a zone link or an Apple fix
    within the vouch in the same attempt is positive evidence of the place, so the renewal goes as (A) says and
    `STILL` stays; the mismatch is still recorded in the ring, flagged `vetoed` (D19), and judged by the next fix.
D17. **Guards.**
    - An **inconclusive** scan never counts (D2): it stays (A)'s cap fail (D5) - the Jelly Star's 15:31.
    - A print of **fewer than 3** settled devices never decides a mismatch, by either clause.
    - **No print** (none taken, dropped, GLA on, Off, a boot): (A)'s rules alone.
    - `wifi` needs at least 3 distinct devices in the fresh answer (D15.1): a full scan that heard little (§1: a short
      dwell or a busy radio in deep Doze) says nothing about "elsewhere" by itself. A thinner answer (a cabin of 1-2
      networks after a missed car ride) ends `STILL` only when the registered cell is placed away; with no SIM, or
      the cell's position unknown, it keeps `STILL` up to the 24-h ceiling.
    - `cell` never decides while any of the print's devices is heard (H1), and never by an id - only by a position
      the table already holds.
    - A renewal in the same attempt vetoes it (M1, D16).
D18. **Not a D7 contradiction.** The mismatch is not a satellite fix: it has no position and no distance, and a
    router swapped or switched off, a neighbour's mesh renamed, or the operator renumbering its cells can all make a
    conclusive scan miss the print at home. Counting it toward design 124 D7's distrust (two in 7 days -> `NO_SENSOR`
    for a week, a GPS start at every beat) would let such a change cost a week of battery. So it is **not** a
    contradiction. D7 keeps its own evidence: the first genuinely fresh fix after a mismatch, **with no fire since the
    vouch** (`MotionFlags.mismatchSinceFresh`, cleared by a fire or a fresh fix), is checked by D7 against the vouch as
    if the gate still said `STILL` - the satellites are the contradiction, the scan only made the try come sooner.
D19. **Recorded** in the ring (D12) as kind `mismatch` with its clause (`wifi`, `cell`), the devices heard now and the
    print's, at every mismatch - a vetoed one too, flagged `vetoed` (D16); its outcome is the next genuinely fresh fix of accuracy <= 100 m (D11): **confirmed**
    (it was right: further from the vouch than both accuracies + 50 m, no fire between), **contradicted** (it was wrong:
    within that), or **unjudged** (a fire or a boot between, none within 24 h, the print dropped first). The dump's
    summary shows mismatches by clause, vetoed and outcome, so the owner's adb check says how often it fired and whether it
    was right. The log: `StillPrint: mismatch clause=wifi devices=0/4` and `StillPrint: outcome confirmed d=3000 after=95`.
    The 24-h ceiling of (A) (`STILL_CEILING_MS`) stays the last backstop: a mismatch only ends `STILL` sooner.

## 4. Storage and privacy

P1. **Sealing** (QA L3). Two files in credential-encrypted `noBackupFilesDir`, each an `AtomicFile` **sealed whole with
    AES-GCM through `Secrets.box`** - the AndroidKeyStore secrets key the places table uses - under its own AAD:
    `still_print_v1` (the fingerprint, < 1 KB: 25 bytes and 37 a device, at most 12) and
    `still_print_ring_v1` (the ring, 37 bytes an event: ~18.5 KB at 500, plus the AES-GCM tag and nonce). The device and
    network keys inside are HMACs under the places table's per-install HMAC key (itself sealed in CE prefs), so they
    compare with a scan's keys directly and mean nothing without it. A new format version drops the old file (the
    no-compat rule). Written only on a change (a fingerprint taken or grown, an event, an outcome).
P2. **Never sent**: not to the server, not in a report. Logs and the dump carry counts, levels, `J`, rounded relative
    distances and times only; `PlacesTest`'s source check over every `Log` call in `places/` covers the new code, and a
    new test checks the dump's lines the same way.
P3. **The minimum**: 12 settled devices by keyed hash and their levels; the ring's distances are from the vouch, never
    a position.
P4. **Cleared** by `WifiPlaces.clear` - location Off, unenrolled, a new enrollment, a broken or unreadable key - with
    the table: both files and the memory.
P5. The ring is a record of when the phone moved a little and how far it was from its last fix after. Kept 60 days at
    most, read only over adb.

## 5. Risks

| Case | What happens | Bound |
|---|---|---|
| A throttled or unanswered scan after a move | inconclusive: one more scan when it would decide, else nothing reported and the next beat tries | 3 tries in a row, then today's failure (D4) |
| A fresh scan that hears 0-1 networks (a cabin) | conclusive: today's outcome, no retries | D2 |
| A stale cache taken as fresh | only results newer than the request answer it; the cache path only within 30 s | D1 |
| The connected network missing from a fresh list | inconclusive - the list isn't the place | D2 |
| Three inconclusive beats while the kid walks away | nothing reported for up to ~1.5 h; the last good fix stays on the PWA, "Here now" lapses by the server's rule as today | D4; GPS runs at every one of those tries |
| A car or bus router (QA H3) | never settled (it moves, or isn't seen on 2 days at one place); a print counts settled devices only, at least 3 | recorded would-not (`thin` or `anchor`) |
| A neighbour's house or an adjacent flat (QA H1) | their own router is the strongest result: `anchor` fails | recorded would-not |
| The shadow verdict is wrong | nothing acts on it | the data shows it |
| A boot, GLA on, Off | the print is dropped | D14 |
| A car ride the sensor missed, still at the friend's house (revision 3) | the cap try's fresh scan hears none of home's devices: `STILL` ends, `QUIET`, the age shows, GPS at the next tries | D15; a cabin of 1-2 networks: only when its cell is placed away (a known position), else (A)'s 24-h ceiling |
| An AP rebooting at home while still | the other settled devices are heard: no mismatch | D15.1: none heard at all |
| A thin fresh scan at home (a short dwell) | fewer than 3 devices and the home cell read: no mismatch | D17 |
| A still phone's serving cell flips at home | home's Wi-Fi heard: the cells never decide (code QA H1); and another cell at the same place is no witness anyway | D15 |
| A zone link or an Apple fix renews while the scan misses the print | the renewal vetoes the mismatch: `STILL` stays, the event recorded `vetoed` (code QA M1) | D16 |
| A router swapped, every home device new | a false mismatch: `STILL` ends at home, one `QUIET` back-off and a GPS try; recorded `contradicted` once a fix comes | the ring shows it; never a D7 contradiction (D18) |

## 6. Android 14-17

- **Scans and their throttle** (AOSP `ScanRequestProxy`; the class split re-read at the pinned Wi-Fi mainline version
  at build time). `startScan()` is deprecated since 9 and works on 14-17, always full-band (QA L1). A caller whose
  importance is foreground service or better gets **4 scans per 2 min**; every caller further back shares **one per 30
  min**. Exempt are only holders of `NETWORK_SETTINGS`/`NETWORK_SETUP_WIZARD` and every caller when the developer
  option "Wi-Fi scan throttling" is off; **a Device Owner is not exempt** and can't switch it off
  (`wifi_scan_throttle_enabled` isn't in `setGlobalSetting`'s list, design 125 §5). The anchor (`specialUse` FGS)
  gives the first class. A throttled `startScan()` returns `false` and starts nothing; `getScanResults()` then returns
  the last full-band cache, of any age. A `true` may bring no callback (the HAL busy, a failed scan). **So the
  results' `timestamp` against the request is the only proof of an answer** (D1).
- **Wi-Fi is a mainline module** (`com.android.wifi`, Google's build `com.google.android.wifi`): its version, not the
  Android version alone, decides the cache and throttle behaviour. The Jelly Star's version is a gate item (§9).
- **APIs**, all at or under minSdk 34: `ScanResult.timestamp`/`level` (17), `getApMldMacAddress` (33),
  `WifiManager.ScanResultsCallback` (30), `WifiInfo.getBSSID` (02:00:.. without fine location and Location on - the fix
  window holds both), `Service.dump` (1). 14-16's behaviour-change lists name nothing for scan results; 17's is read
  at build time (whether `getConnectionInfo()` keeps the BSSID - else design 125 L3's `NetworkCallback` with
  `FLAG_INCLUDE_LOCATION_INFO`).
- **Battery**: D3's extra scan only after an inconclusive first and only when it would decide (~0.02-0.1 mAh, design
  125 §6); an inconclusive try runs GPS as any try after a move. The shadow: a few dozen comparisons and one sealed
  write per event.

## 7. Debug hooks and their release guards (QA M6)

The emulator has no significant-motion sensor (§6bn row b) and no real neighbours, so the gate needs three hooks, all
in the existing `PlacesDebugReceiver` (`src/debug`, exported, guarded by `DUMP`): `--es motion sensor|fire|off` (a
fake trigger feeding `MotionGate` as the real one would), `--ei scan_age_s N` (the made-up results heard N s ago; today
they are always heard now) and `--ez refuse_scan true` (every `startScan` counts as throttled until `false` or `clear`;
built as `scan_age_s`/`refuse_scan`, not `age_s`/`refuse`: the release check matches substrings of the dex, and a
release build holds "refused" in its log lines). Main code reaches them only
through `PlacesDebugHooks`, an object with a debug implementation in `src/debug` and a **no-op twin in `src/release`**
(like `LockTaskDebug`), so a release build can't hold a fake trigger. The existing `WifiPlaces.debugResults` and
`debugConnected` move behind the same object. The new names (`PlacesDebugHooks`' debug-only members `fakeMotion`,
`scanAgeS`, `refuseScan`, and the extras `scan_age_s`, `refuse_scan`) join `DebugHookAbsentTest`'s banned list for `src/main` and
`src/release` and `checkReleaseHasNoDebugHook`'s `names` in `app/build.gradle.kts`.

## 8. Steps and tests

| Step | What | Size |
|---|---|---|
| **L1** (launcher, a commit per step, on `still-print` from `still-keeps`) | D1-D5: `scanNow`'s request time and outcome, the pure `scanAnswer` (D1, D2), D3's second scan, D4's withheld report and `afterTry` keep, `inconclusive_tries` in `heartbeatDue`'s caller; D6-D14: the pure `StillPrint`, `shadowVerdict`, outcome and ring codecs (`places/StillPlan.kt`), the glue in `WifiPlaces` (take, grow, drop, record, outcome), `CommandListenerService.dump`; §7's hooks and guards; revision 3's D15-D19 (`printMismatch`, `MotionFlags.scanMismatch`, the sync's end of `STILL`) | ~500 lines of code + ~500 of tests |
| **Docs** (same commit) | `docs/notes/launcher/wifi-places.md` (a section "After a small move: inconclusive scans and the shadow fingerprint"), `zones-and-power.md`'s still section (D4, D5), `docs/testing/new-phone-model.md` §2 (§9), `docs/testing/emulator.md` §6bs | - |
| **E** (the owner's OK) | §6bs on Android 14 and 16 (the 17 images don't run; the Jelly Star is on 16), a debug and a release build | - |
| **J** | the Jelly Star (§9) for 2-4 weeks; the owner reads the dump | - |
| **Later** | a revision 4 decides from the data whether the moved tier may renew | - |

**Pure tests** (built as `StillPlanTest` - D1-D5 and the glue by source - and `StillPrintTest` - the print, the
verdict, the ring and binding rows 1-12, Kotlin rows rather than a `wifi_places.json` section; `ZonePlanTest` for the
flags):
- **`scanAnswer`** (D1, D2): results after the request; all older; the cache path within and past 30 s; refused with
  and without a fresh cache; unanswered; Wi-Fi off; connected with its AP in the answer, missing from it, and not
  connected; one fresh AP and none - conclusive.
- **D3**: a second scan only when inconclusive, no fresh fix, and the table or the Apple switch could decide; never
  twice; not before 10 s; not when refused with Wi-Fi off.
- **D4/D5**: an inconclusive try reports nothing (a cell fix, an unchecked fix and the fallback withheld), keeps the
  flags, gives `FIX` at the next beat; the 4th in a row is today's failure and back-off; a fresh fix resets the count;
  while `STILL` an inconclusive cap try is (A)'s cap fail.
- **The take** (D8): 50 and 51 m, gps vs fused vs unchecked Wi-Fi, moving, mock, 30 and 31 s pairing, an inconclusive
  scan; settled devices only (`days` 1 vs 2, `moves` 1, blocked, Apple-only); 12 devices at most; additions only on a
  conclusive scan where D9 held.
- **The verdict** (D9): each reason in order, 15 vs 16 dB, -70 vs -71 dBm, the mean difference with an absent device,
  `J` with the linear weight; the outcome classes (D11) with a fire between; the ring's cap and 60-day drop; the drops
  (D14); the codecs' round trip and a new version dropped; no `wifi_disagreed` rise from any shadow path.
- **Source checks**: no `Log` or dump line in `places/` or the dump takes a BSSID, SSID, key or coordinate; the dump
  prints the ring only with `still-print`; §7's names absent outside `src/debug`.

**Binding rows** (each a test row; the levels from design 125's synthetic town, log-distance with walls and 6 dB
shadowing, rooms added):
1. **The Jelly Star's day.** A print at the 07:30 fix of 4 settled devices. 15:31 cap try while `STILL` with a list
   older than the request: (A)'s cap fail, no shadow event (the still tier records nothing). 15:40 a walk to the
   kitchen (a fire); the 16:01 beat's request refused: `inconclusive` event, nothing reported, flags kept; 16:31 a
   fresh answer of 4 devices: `would=yes`; 18:24 a fresh fix at the same place: `confirmed`.
2. **QA H1's neighbour.** Next door, 25 m off, our router heard 18 dB weaker and their own (unsettled) router
   strongest: `would=no reason=anchor`. With their router switched off and ours 18 dB weaker: `would=no reason=anchor`
   (the 15 dB on the print's strongest).
3. **An adjacent flat** through one concrete wall, 8 m off, with its own router: `would=no reason=anchor`; without a
   router of its own, ours 16+ dB down: `would=no`; the case of a light wall within 15 dB is a recorded `would=yes`
   whose outcome the data will judge (the known limit, under 15 m).
4. **QA H3's car router** (a global MAC, unmetered, not in the moving list): seen at home and at the school, so it
   moved and is never settled; a print of it and one home device is `thin`; in the car 2 km on: `would=no`.
5. **Boot.** A print, a reboot, the first try after it: `print dropped (boot)`, no verdict event.
6. **The cabin.** One AP, a fresh answer to the request: conclusive, no second scan, no retry, today's outcome; no
   print qualifies (`thin`).
7. **A would-renew, then a failed re-check** (QA L5, H2). A `would=yes` event after a move; GPS gave nothing, so the
   attempt ends as today - design 124's `QUIET` back-off and the fix's age; the later tries (and, once still, (A)'s
   ladder) fail too. The event stays `unjudged` until a fresh fix or 24 h; no `still: true` is ever sent because of it,
   and (A)'s open-ended still never starts from a print.
8. **Power-off and boot** (QA L5): the phone off for an hour at home, then on - `print dropped (boot)` at the first
   try, which runs as after any move; the ring and its pending outcomes survive (an outcome after the boot is judged
   with `moved_after` set, so `unjudged`).
9. **A place with no saved network** (QA L5), a friend's house, not connected: D2's connected rule doesn't apply; a
   fresh answer of five unknown APs is conclusive - no second scan, no retry, today's outcome; with no print there, no
   event. Connected to a metered phone hotspot (locally administered, never usable) the rule looks for the hotspot's
   BSSID in the answer as for any connection.

10. **The friend's house after a car ride the sensor missed** (revision 3). A print at home of 4 settled devices and
    the home cell; the sensor misses the drive (no fire); at the 4-h cap try at the friend's house 3 km off a fresh
    answer of 5 unknown APs: `wifi` mismatch - `STILL` ends, the try reports nothing, no `still`; the next beat tries
    (GPS; design 124's back-off counts from the mismatch, so the waits grow - `QUIET` between - while the tries fail); its fix 3 km off: `outcome confirmed` and a D7
    contradiction (no fire since the vouch).
11. **The Jelly Star's own day under the rule** (revision 3). 15:31's cap try with a list older than the request:
    inconclusive - no mismatch, (A)'s cap fail, `STILL` goes on (the retries at the next two beats, 1 h, 2 h). The same
    try with a fresh answer of one usable AP that isn't the print's: no mismatch (D17's 3 devices; no SIM, so no cell
    witness - with a SIM and a registered cell placed 5 km away it would be a `cell` mismatch; another cell at the same
    place, no). A fresh answer of 4 home devices: no mismatch, whatever the cells say (H1).
12. **An AP reboot at home** (revision 3). A print of 4 settled devices; at the cap try one of them is off (rebooting),
    the other three heard: no mismatch, the cap try as (A) says - also when the serving cell flipped meanwhile (H1). All
    four gone but the scan holds three neighbours' devices: a `wifi` mismatch (the known cost, §5's router row); with
    the zone link renewing in the same attempt, the mismatch is vetoed (M1).

**The emulator gate** (`docs/testing/emulator.md` §6bs; Android 14 and 16 - the 17 images don't run, 17 is covered by reading its behaviour-change list (§6); settled rows taught as in §6bn - three
networks of their own devices, three occasions over two local days by the time-zone step):

| # | Step | Expect |
|---|---|---|
| a | A satellite feed fix (8 satellites, 10 m) at home with home's scan | `StillPrint: taken devices=3` |
| b | `motion fire`, GPS silent, home's scan with `scan_age_s 600` | `result=inconclusive reason=old`, a second scan (`refuse_scan` off) - still old - then nothing reported (Locate unchanged); next beat tries again; the 4th in a row ends as today with `QUIET` |
| c | As (b) with `refuse_scan true` | `reason=refused`, no second scan |
| d | `motion fire`, GPS silent, home's scan fresh | `StillPrint: verdict would=yes`; nothing renewed (Locate as today); then the feed at home: `outcome confirmed` |
| e | `motion fire`, a neighbour's scan (their device strongest) | `would=no reason=anchor` |
| f | One made-up AP, fresh | no second scan, no retry; `thin` |
| g | `adb reboot` | `print dropped (boot)` |
| h | The release build | `dumpsys activity service ... still-print` prints the summary and ring; without the argument one line; `checkReleaseHasNoDebugHook` passes; the receiver's extras do nothing (no receiver) |
| i | Location Off from the PWA | both files gone, `print dropped (off)`, the dump's ring empty |
| j | (revision 3) `motion sensor`, a fresh print at home (a), the clock 4 h on (an image with `adb root`: `date`), a neighbour's scan without home's devices, GPS silent | `Location heartbeat: STILL ended by a scan mismatch`, `StillPrint: mismatch clause=wifi`, no `still` in the report, the next beat a `FIX` (GPS), `QUIET` between the later tries; the same with home's scan and one device missing: no mismatch, `STILL ... fails=1`; the neighbour's scan with the zone link's connection (D12) renewing: `StillPrint: mismatch clause=wifi devices=0/3 vetoed`, `STILL renewed by a zone link`, no end of `STILL`; one neighbour device only: no mismatch while the registered cell has no position or one near home (another cell id at home included), `mismatch clause=cell` once its known position is 5 km off |

**What can't be faked there**: real room-to-room levels, real neighbours and flats, the HAL's behaviour in deep Doze,
the real throttle under the anchor's importance with other apps scanning, a real sensor's delay.

## 9. The Jelly Star (`docs/testing/new-phone-model.md` §2)

1. **The Wi-Fi mainline module's version**: `adb shell pm list packages --apex-only --show-versioncode | grep -i wifi`
   and `adb shell dumpsys package com.google.android.wifi | grep -i version` - noted with the run, and the AOSP read of
   `ScanRequestProxy` done at that version.
2. **Inconclusive tries**: per day, by reason, from the dump's summary and `adb logcat -s ZoneWatch WifiPlaces
   StillPrint`; the `scan refusals` line; whether a second scan brings the place back.
2a. **The results' time base** (code QA L2): `ScanResult.timestamp` must be boottime microseconds - the `WifiPlaces:
   scan answer n/m ask=answered` lines show `n > 0` on a fresh scan and never `timebase`; compare a result's
   `timestamp` in `adb shell dumpsys wifi` (its "Latest scan results") with `adb shell cat /proc/uptime` around the
   suspend gap (boottime counts deep sleep, uptime doesn't), noted with the module version of item 1.
3. **The shadow record after 2-4 weeks**: `adb shell dumpsys activity service
   me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService still-print` - confirmed, contradicted,
   would-not by reason, and the distances of the contradicted ones.
4. **Cells**: whether the phone has a SIM (asked of the owner); the rule doesn't depend on it.
5. **The witness's level** (revision 4, code QA H1): at a screen-off cap try, the `connected witness ... level=link
   screen=off dbm=N vouch=N` line next to a fresh read with the screen on minutes later (`adb shell dumpsys wifi |
   grep -i rssi`, or a `locate` with the screen on: `level=scan` or `screen=on`) - how far the stale level lies from
   the live one, for the owner's (a)/(b) (§13b).

## 10. Questions for the owner

Revision 4's witness level with the screen off: decided (b) by the owner 2026-11-26 (§13b). Whether the moved tier may renew is decided from §9's record, in a later revision; whether the mismatch rule
stays, from the same record's mismatch outcomes.

## 11. Revision 2 - the owner's scope and the design QA

The owner (2026-11-25): (A) ships apart with its ladder (§0, D5); the inconclusive scan after a move is built to act,
defined by freshness only (D1-D4); the fingerprint runs in shadow and never acts (D6), recording the strict rule with
QA's fixes (D7-D14) in a sealed ring read over `dumpsys` (D12, D13); the debug hooks get release guards (§7); revision
1's Q1-Q3 are gone (§10). The QA findings named in that decision: **H1** the near-device anchor, -70 dBm agreeing, no
saturating clamp (D9); **H3** devices not names, settled APs only, at least 3 devices (D7, D9); **M1** the count rules
dropped (D2); **M2** an inconclusive cap try is (A)'s cap fail (D5); **M3** the drops at boot, GLA on, still rule off and
distrust (D14); **M4** 50 m and +-30 s (D8); **M5** additions only on a conclusive scan where the rule held (D8);
**M6** the release guards (§7); **L1** the full-band cache and the partial scans' LRU (§1, §6); **L2** no
`wifi_disagreed` from the shadow (D6); **L3** the sealing wording (P1). Revision 1's still tier, its renewals, its
clear-mismatch rule, its connection and cell witnesses, the one-interval hold and the print contradiction are cut with
the renewals. **H2** (a print renewal followed by (A)'s open-ended still) is moot: the print never acts (D6; binding
row 7). **L4**: the print lives 72 h (D14). **L5**: binding rows 7-9.

## 12. Revision 3 - a scan mismatch ends `STILL`

The owner (2026-11-25 evening) approved one more rule: the mismatch direction acts for real, the match direction
stays shadow-only. §0 point 3; §3a D15-D19; D6, D7, D12, D13, D14 amended; §5's rows; binding rows
10-12; gate row (j). Decided at the build: the mismatch is **not** a D7 contradiction (D18) but keeps D7's own check
for the next fix; a fresh fix wins over a mismatch (D16); a guard stricter than the owner's words (D17: `wifi` needs 3
heard).

**Code QA of the build (2026-11-25), approved by the coordinator:** H1 - cells only back a Wi-Fi absence
(`wifiNone && (devicesHeard >= 3 || cellsDiffer)`, D15, D17); M1 - a renewal in the same attempt vetoes the mismatch,
recorded as `vetoed` (D16, D19; it replaced the build's "a mismatch over a renewal"); M2 - `scanNow` waits on within
its 8 s past a callback whose results are all older than the request; L1 - the cache path's 30 s from the request; L2 -
`scan answer n/m ask=..` logged, a result over 60 s in the future is inconclusive (`timebase`), §9 item 2a; L3 - a drop
deletes an unreadable print; L4 - devices, not APs; L5 - the dump reads only; L6 - the withheld run ends whenever the
motion isn't `MOVED`; L8 - D12's and P1's sizes.

**The owner's refinement of H1 (2026-11-25):** "the cells differ" means the registered cell is placed away from home -
its position from the table (an own cell row or design 125b's Apple answer), further from the vouch than its range,
the vouch's accuracy and 1 km; unknown counts for nothing, and no lookup is started for it (D15.2). The print keeps
no cells (D7, format version 2).

## 13. Revision 4 - the connected router as a still witness

The owner (2026-11-25 night): "Ja ta med det". The Jelly Star lay on top of the home router all day; both background
cap tries got a poor list (15:31 `scan usable=1 trusted=0`, 22:25 `usable=2 trusted=0`) without the home router in
`getScanResults()`, while the parent's `locate` at 18:24 heard `usable=4 trusted=2` and renewed. D1-D5 and (A)'s
ladder already treat a stale or refused list as a cap fail; the phone's own connection says more, without a scan.
Code QA of the build (1H 2M 5L) is applied below (§13a).

D20. **The rule.** While `motion` is `STILL` (no fire since the vouch), the heartbeat's `FIX` - the 4-h cap try and
    every retry of (A)'s ladder, the `LEARNED_FIRST` plan (no daily refresh due) - renews the vouch when no learned fix
    was judged, the zone link (design 125 D12) didn't renew, and **all** of these hold (`stillWitness`, pure,
    `places/StillPlan.kt`):
    1. **a vouch under (A)'s 24-h ceiling, and its link**: at the fresh fix that became the vouch, not moving, the
       same attempt read the phone connected to a bound AP (2, 3) with a known level; that read is kept **in memory
       only** (`VouchLink`: the vouch's time, the boot, the AP's key, its MLO device key, its device key and binding,
       its level). The ceiling counts from the last fresh fix or a renewal by a zone link or an Apple fix -
       **never from this renewal** (code QA M1, `ceilingRenewedMs`, `stillCeilingPassed`): a phone lying on its router
       with no fresh fix for 24 h goes `QUIET` at 24 h whatever the witness says (`reason=ceiling`).
    2. **connected now** to an AP whose BSSID reads (not `02:00:00:00:00:00`, not unparseable) and is not locally
       administered (a hotspot, a virtual BSSID).
    3. **design 131's binding holds**: unmetered, Android's id of the saved network known, WPA2/WPA3 personal or
       enterprise (open and OWE never) - `linkConn` and `linkSecurityHolds`, the zone link's own checks - **and it is
       the binding read at the vouch** (the network saved anew or its security class changed: no witness).
    4. **the same AP as at the vouch** (code QA M2): the same BSSID (its key), or another link of the same MLO
       device (the MLD address). The router's other band or another mesh node is not: band steering falls back to
       (A)'s ladder.
    5. **settled**: one of the AP's device's own Wi-Fi rows trusted (2 occasions) and seen on >= 2 local days, and
       none of the device's rows moved or blocked (D7's "settled", stricter).
    6. **its level known and within 15 dB** (`PRINT_LEVEL_DB`) of the level at the vouch. Both levels are the
       connected BSSID's own entry in the attempt's **answering** scan (D1: heard after the request) when it is there,
       else the connection's `WifiInfo.getRssi()` (code QA H1). `-127` (`INVALID_RSSI`) or any level outside
       -126..0 is unknown and refuses (code QA L2). **The connection's level is stale while the screen is off**
       (Android polls it only with the screen on), so in the background this check is weak: see D22 and §13b.
    It renews **only the still-ness**: the vouch re-sent with `still: true` (`Ask.renewed`, (A)'s count cleared, the
    4-h skip counted from it - **not** the 24-h ceiling, 1) - never a new position, never a fresh fix, never a print, a
    teach or a D7 check. Logged `ZoneWatch: Location heartbeat: STILL renewed by the connected AP` and
    `WifiPlaces: connected witness renews level=scan|link screen=on|off dbm=N vouch=N diff=N`; one that doesn't hold
    `WifiPlaces: connected witness no reason=<wire>` (with the same level fields once a link and a level exist).
    The vouch's read: `WifiPlaces: vouch link read level=scan|link` or `vouch link none`.
D21. **Not a renewal** (the reason's wire): `moved` (any motion but `STILL`: after a fire the connected AP is no
    witness - QA H3's travel router and the car), `novouch`, `ceiling` (past (A)'s 24 h, D20.1), `none` (not on
    Wi-Fi), `unreadable` (redacted or unparseable), `local` (locally administered), `binding` (metered, unknown id,
    open/OWE, or another binding than at the vouch), `nolink` (no link read for this vouch: another boot, a new
    process, the vouch's attempt not connected or its level unknown), `other` (another AP than at the vouch),
    `unsettled`, `nolevel` (the level unknown now), `level` (more than 15 dB weaker), `stronger` (more than 15 dB
    stronger).
D22. **Why it is safe, and how far.** Being connected proves range: the phone exchanges frames with that AP now, so
    it is within the AP's reach. The AP is settled - in the table on two days, never moved - so it stands where it
    stood, and it is the AP the phone was connected to at the vouch, so the vouch lies within its reach too. The
    sensor hasn't fired since the vouch. The binding ties it to the saved network the parent's phone joined, not a
    name anyone can broadcast: an open or OWE twin never counts, and a WPA2/WPA3 twin needs the home's password.
    **The real bound is the AP's own range**: indoors about one home, outdoors perhaps 50-100 m (a garden, the
    street, a neighbour's flat), guarded by the motion sensor - a phone carried that far without a fire is the still
    rule's own limit (§13b, L3). The level helps only when it is fresh: with the screen on, or when the answering scan
    holds the connected BSSID (then its level is that scan's). With the screen off and the BSSID missing from the
    answer - the Jelly Star's case - the connection's level is the last one polled with the screen on, often the
    vouch's own, so the 15 dB check passes by construction there and bounds nothing. It renews nothing a zone link
    (D12) wouldn't, needs no zone, and can't outlast (A)'s 24-h ceiling.
D23. **The mismatch** (D15): the witness **vetoes** the mismatch as a renewal does (code QA M1) when it renews, or
    fails only by a level **more than 15 dB stronger** than at the vouch (code QA L1: nearer the router, never
    further) - `mismatchVetoed`, the event recorded `vetoed`. A level more than 15 dB weaker vetoes nothing. It
    seldom arises: with a readable BSSID, a fresh answer that lacks the connected AP is already inconclusive (D2,
    `noconnected`), and one that holds it hears a device of the print when the AP's device is in it.
D24. **Reusing design 125 D12.** The witness sits beside `renews()` in `LEARNED_FIRST` (`judged == null && (renews ||
    witness)`) and shares its pieces: `linkConn` (unmetered, saved-network id, the binding), the security check
    `linkSecurityHolds` (taken out of `linkConfirms`), `PlaceKeys.ap`/`device`/`binding`. The sync hands the vouch to
    the attempt on every Regularly phone (`renewable` is `stillOn`'s; `zonesOn` is `stillOn` with zones), so it needs
    no zone; `linkRenews` itself is unchanged (no zone, no link row: no zone-link renewal).
D25. **Reading the BSSID on Android 14-17.** `WifiManager.getConnectionInfo()` (deprecated in 31, documented "apps can
    continue using this API") gives the real BSSID with `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (both
    granted as Device Owner) and Location on - the same check `getScanResults()` passes; it is what D2 and the zone
    link read; its `unmetered` is the active network's `NOT_METERED` (merged through our VPN). When it gives none
    while the active network carries Wi-Fi, the attempt's scans (`connectedNow`, both of D3's) register one
    `ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` (API 31) for Wi-Fi and read its
    `NetworkCapabilities.getTransportInfo()` `WifiInfo`, up to 1 s, then unregister (`getNetworkCapabilities()` alone
    always redacts it since 31, so it is no use here); its `unmetered` is **that Wi-Fi network's own** `NOT_METERED`,
    not the merged one. **That read feeds every user of the attempt's snapshot** (code QA L4), not the witness alone:
    D2's `noconnected` check, the zone link (`linkConn` in `renews()` and `linkConfirms`) and `hear()` (which adds the
    connected AP when the results lack it) - where the deprecated read was redacted they now see the connection too,
    which is the rule they were written to. The teaching path (`teachWith`'s snapshot) keeps the plain read. Both APIs
    and `WifiInfo.getApMldMacAddress()` (33, the MLO key) are at or under minSdk 34: no SDK gate. Android 17's
    all-apps behaviour changes name nothing for Wi-Fi, and the launcher targets 36. Never logged: the BSSID; the log
    carries the reason, levels, their difference, the level's source and the screen's state.

**Pure tests** (`StillWitnessTest`, rows 13-19; `ZonePlanTest` for the sync's source): 13. **the Jelly Star's day** -
a vouch at 07:30 connected to the home router (-42 dBm, settled), the 15:31 cap try's list poor and old
(inconclusive), connected at -45: renewed; 14. an unsettled AP (one day, one occasion, a moved or blocked row of its
device, an Apple row only): `unsettled`; 15. the level 20 dB off: `level` (weaker) or `stronger`, 15 dB renews, -127
`nolevel`; 16. a redacted BSSID (`02:00:00:00:00:00`): `unreadable`, a locally administered one `local`; 17. `MOVED`,
`STILL_SINCE_TRY`, `NO_SENSOR`: `moved`; 18. the mismatch veto - by a renewing or a stronger witness, not by a weaker
one; not connected, unsettled, another binding, no link: it acts; 19. **the ceiling** - renewals by the witness
every 4 h, the beat at 24 h from the vouch a `FIX` the witness refuses (`ceiling`), `QUIET` and no `still` after it,
a zone link's renewal still moving it. Also: the answering scan's level of the connected BSSID over the
connection's (H1), the other band and a mesh node `other`, another link of the same MLO device the same AP,
another binding, an open network, no link for this vouch (another boot).

**Gate** (`docs/testing/emulator.md` §6bs row k, written, not run): the made-up connection (`--es connect`, its level
the sixth field) to a settled home AP at the vouch and at the cap try.

### 13a. Code QA of the build (2026-11-25), applied

**H1** the connected RSSI is stale with the screen off: option (b) for now - the check kept, D20.6 and D22 say what
it bounds, the answering scan's level of the connected BSSID used when present, `level=scan|link screen=on|off` in
the log, §9 item 5; the owner decides (§13b). **M1** the witness never moves the 24-h ceiling (D20.1). **M2** the
same AP only, or another link of one MLO device (D20.4). **L1** only a renewing or a stronger witness vetoes a
mismatch (D23). **L2** an unknown level refuses (D20.6). **L3** a known limit, guarded by the sensor (§13b). **L4**
the callback read's other users and its `unmetered` (D25). **L5** the test class's name and the ceiling's wording
(this section).

### 13b. Known limit and the owner's question

**Known limit (code QA L3)**, no code change: the witness says the phone is within the router's reach, not where in
it. A phone carried a few rooms, into the garden or to a neighbour's flat inside that reach without a motion fire
keeps `STILL`, and with the screen off the level (D20.6) doesn't tell those places apart. Only the motion sensor
guards it - the bound the still rule already lives with (design 124 D7's distrust after two contradictions), with
(A)'s 24-h ceiling as the backstop.

**The owner's question, DECIDED 2026-11-26: (b), the level with the screen off.**
- **(a) Refuse a stale level.** The level counts only when fresh - the connected BSSID in the answering scan, or the
  screen on; otherwise no renewal (a new reason, the cap try as (A)'s ladder says). The 15 dB check then always
  means something, but on the Jelly Star's evidence (the router missing from the background lists, the screen off)
  the witness would seldom act at the very cap tries it was asked for.
- **(b) Keep the check as built (this build) - CHOSEN by the owner 2026-11-26.** A stale level passes; the bound is the AP's own range (D22) guarded by
  the sensor; the log's `level=link screen=off` shows how often that is the case, and §9 item 5 measures the
  difference on the Jelly Star.
