# 134 - After a small move: honest scans now, a fingerprint in shadow

Status: **design, revision 2**, 2026-11-25 (branch `design-134`), read against `main` d036f5b33. Revision 1 was
5e980281a; revision 2 applies the owner's scope decisions of 2026-11-25 and the design QA findings (H1-H3,
M1-M6, L1-L5; §11). Part (B) of PLAN's item 1b. Part (A) ships apart on branch `still-keeps`: while the sensor
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
    is off.
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

D6. **Never acts.** No renewal, no `still: true`, no vouch, no report, no change to the flags, the heartbeat, the
    attempt's keep, `wifi_disagreed` (QA L2) or any count the server sees. Its only outputs are the sealed ring (D12),
    the dump (D13) and log lines. The still tier of revision 1 is gone; the moved tier only records.
D7. **What it is** (`StillPrint`, pure, `places/StillPlan.kt`): the anchor fix's time (`fixTimeMs`), when it was
    taken, the boot it was taken in, and up to **12 devices** - design 125 D5's device key (the MLD address or the
    first five octets: a router's bands and links are one device), its network key, its strongest level in dBm and the
    number of its APs heard. Only **settled** devices (QA H3): at least one of its APs has an own Wi-Fi row in the
    places table with `days >= 2`, `moves == 0` and not blocked; Apple rows don't make a device settled. No SSID, BSSID
    or position; no cells (they witnessed nothing on the Jelly Star, and the strict rule doesn't use them).
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
D12. **The ring** (sealed, on the phone): the last **500** events, ~30 bytes each (`still_print_ring_v1`, D16);
    events older than 60 days drop out. Each holds the time (minutes), the kind (`verdict`, `inconclusive`), would
    yes/no and the reason, `J` and the mean difference to one decimal, the device and near-device counts on both
    sides, the agreeing count, and the outcome fields once known. **Never a BSSID, SSID, key or coordinate.** Each event
    is also logged under the tag `StillPrint` (`StillPrint: verdict would=yes j=0.7 diff=6.5 devices=4/4 near=2/2
    agree=2`, `StillPrint: outcome confirmed d=20 after=34`).
D13. **Reading it on the release build.** `CommandListenerService` (the anchor, always running) overrides
    `Service.dump`: with the argument `still-print` it prints the summary - verdicts by class (confirmed,
    contradicted, would-not by reason, unjudged), inconclusive tries by reason, prints taken and dropped by reason -
    then the ring, newest first. Without that argument it prints one line and nothing of the ring, so a bug report
    carries none of it. The framework lets only a caller holding `DUMP` reach it (adb's shell has it); it needs no
    debuggable build:
    `adb shell dumpsys activity service me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService still-print`.
D14. **Dropped** (QA M3): at a boot (`BootClock.bootCount()` other than the print's), at GLA on (the `network`
    provider enabled at an attempt), when the still rule is off (not Regularly, unmanaged), at sensor distrust (124
    D7), when its vouch is replaced, when something not fresh was reported after its vouch, **72 h** after it was taken
    (QA L4: a print describes the place of one vouch, so its life is short; the ring of outcome events keeps 60 days),
    and at every Wi-Fi places clear. Each drop logged with its reason.

## 4. Storage and privacy

P1. **Sealing** (QA L3). Two files in credential-encrypted `noBackupFilesDir`, each an `AtomicFile` **sealed whole with
    AES-GCM through `Secrets.box`** - the AndroidKeyStore secrets key the places table uses - under its own AAD:
    `still_print_v1` (the fingerprint, < 1 KB) and `still_print_ring_v1` (the ring, ~15 KB at 500). The device and
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
fake trigger feeding `MotionGate` as the real one would), `--ei age_s N` (the made-up results heard N s ago; today they
are always heard now) and `--ez refuse true` (the next `startScan` counts as throttled). Main code reaches them only
through `PlacesDebugHooks`, an object with a debug implementation in `src/debug` and a **no-op twin in `src/release`**
(like `LockTaskDebug`), so a release build can't hold a fake trigger. The existing `WifiPlaces.debugResults` and
`debugConnected` move behind the same object. The new names (`PlacesDebugHooks`' debug-only members `fakeMotion`,
`scanAgeS`, `refuseScan`, and the extras `age_s`, `refuse`) join `DebugHookAbsentTest`'s banned list for `src/main` and
`src/release` and `checkReleaseHasNoDebugHook`'s `names` in `app/build.gradle.kts`.

## 8. Steps and tests

| Step | What | Size |
|---|---|---|
| **L1** (launcher, one commit, after `still-keeps` is merged) | D1-D5: `scanNow`'s request time and outcome, the pure `scanAnswer` (D1, D2), D3's second scan, D4's withheld report and `afterTry` keep, `inconclusive_tries` in `heartbeatDue`'s caller; D6-D14: the pure `StillPrint`, `shadowVerdict`, outcome and ring codecs (`places/StillPlan.kt`), the glue in `WifiPlaces` (take, grow, drop, record, outcome), `CommandListenerService.dump`; §7's hooks and guards | ~350 lines of code + ~400 of tests |
| **Docs** (same commit) | `docs/notes/launcher/wifi-places.md` (a section "After a small move: inconclusive scans and the shadow fingerprint"), `zones-and-power.md`'s still section (D4, D5), `docs/testing/new-phone-model.md` §2 (§9), `docs/testing/emulator.md` §6br | - |
| **E** (the owner's OK) | §6br on Android 14 and 16 (the 17 images don't run; the Jelly Star is on 16), a debug and a release build | - |
| **J** | the Jelly Star (§9) for 2-4 weeks; the owner reads the dump | - |
| **Later** | a revision 3 decides from the data whether the moved tier may renew | - |

**Pure tests.** `PlacesTest` over a `still_print` section of `wifi_places.json`, `ZonePlanTest` for the flags:
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

**The emulator gate** (`docs/testing/emulator.md` §6br; Android 14 and 16 - the 17 images don't run, 17 is covered by reading its behaviour-change list (§6); settled rows taught as in §6bn - three
networks of their own devices, three occasions over two local days by the time-zone step):

| # | Step | Expect |
|---|---|---|
| a | A satellite feed fix (8 satellites, 10 m) at home with home's scan | `StillPrint: taken devices=3` |
| b | `motion fire`, GPS silent, home's scan with `age_s 600` | `result=inconclusive reason=old`, a second scan (`refuse` off) - still old - then nothing reported (Locate unchanged); next beat tries again; the 4th in a row ends as today with `QUIET` |
| c | As (b) with `refuse true` | `reason=refused`, no second scan |
| d | `motion fire`, GPS silent, home's scan fresh | `StillPrint: verdict would=yes`; nothing renewed (Locate as today); then the feed at home: `outcome confirmed` |
| e | `motion fire`, a neighbour's scan (their device strongest) | `would=no reason=anchor` |
| f | One made-up AP, fresh | no second scan, no retry; `thin` |
| g | `adb reboot` | `print dropped (boot)` |
| h | The release build | `dumpsys activity service ... still-print` prints the summary and ring; without the argument one line; `checkReleaseHasNoDebugHook` passes; the receiver's extras do nothing (no receiver) |
| i | Location Off from the PWA | both files gone, `print dropped (off)`, the dump's ring empty |

**What can't be faked there**: real room-to-room levels, real neighbours and flats, the HAL's behaviour in deep Doze,
the real throttle under the anchor's importance with other apps scanning, a real sensor's delay.

## 9. The Jelly Star (`docs/testing/new-phone-model.md` §2)

1. **The Wi-Fi mainline module's version**: `adb shell pm list packages --apex-only --show-versioncode | grep -i wifi`
   and `adb shell dumpsys package com.google.android.wifi | grep -i version` - noted with the run, and the AOSP read of
   `ScanRequestProxy` done at that version.
2. **Inconclusive tries**: per day, by reason, from the dump's summary and `adb logcat -s ZoneWatch WifiPlaces
   StillPrint`; the `scan refusals` line; whether a second scan brings the place back.
3. **The shadow record after 2-4 weeks**: `adb shell dumpsys activity service
   me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService still-print` - confirmed, contradicted,
   would-not by reason, and the distances of the contradicted ones.
4. **Cells**: whether the phone has a SIM (asked of the owner); the rule doesn't depend on it.

## 10. Questions for the owner

None now. Whether the moved tier may renew is decided from §9's record, in a later revision.

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
