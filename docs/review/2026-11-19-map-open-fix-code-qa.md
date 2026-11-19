# Code QA: `map-open-fix` (map links into a running Organic Maps, the VPN leaving the sink, the level-10 centre)

Branch `map-open-fix` at 00a374485, three launcher commits over main 1184d4ac8:
- c74907803: map links start Organic Maps or CoMaps with `FLAG_ACTIVITY_CLEAR_TASK` (unless the target sent the link)
  and a fresh `Intent.setIdentifier`.
- 34d9fce8e: the VPN leaves the "Bare Vibb" sink with the filter's tunnel first and lockdown off last (design 120 fix A,
  `docs/review/120-spotify-recovers-by-itself-design-qa.md` M1).
- 00a374485: the fallback search centre at S2 level 10.

Read: the diff, `KidVpnService` (`switchTo`, `reshape`, `establish`, `onStartCommand`, `liveTunnel`),
`MobileNetworkRuntime.writeAlwaysOn`/`lockdownOurs`, `AppEnforcer.applyVpnRestrictions`, `VpnRetryReceiver`,
`MapLinkActivity`, `MapLinks`, the manifest entry, the tests, `docs/notes/launcher/map-links.md`,
`docs/notes/launcher/dns-and-vpn.md`, design 110 §4.2's centre and design 120 M1.

Build: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`, green: 2840 tests, 0 failures (`MapLinksTest` 12,
`MobileNetworkTest` 25).

**Result: 0 H, 0 M, 4 L.** No code changed.

## The VPN's step order: fail-closed at every step

There are three writers of always-on: the service's `switchTo`, `apply()` and the retry alarm. Each state on the way
out of the sink:

| State | Tunnel | Lockdown | Other apps |
|---|---|---|---|
| Start | sink (`0.0.0.0/0`, `::/0`) | on | nothing leaves |
| After `establish(FILTER)` in place | filter (`192.0.2.2/32`, `::/0 unreachable`) | on | DNS through the filter, everything else dropped by lockdown (the "VPN required for UIDs" rule; this is AppEnforcer's documented "lockdown with the filter breaks the device's network") |
| After `writeAlwaysOn(FILTER)` | filter | off, a new VPN agent | free, filtered DNS: the wanted end state |

- **No step lets traffic out that couldn't get out before.** The only open state is the end state the decision asks for.
- **The process dies between the two steps.** The tunnel's fd closes, so there is no VPN, and lockdown is on, so nothing
  leaves. On restart, `wantedShape()` reads the stored shape. Through the watcher's path that is still `sink`, because
  `alwaysOnBeforeEstablish` writes nothing while lockdown is on. Through `apply()`'s path it is already `filter`. Either
  way, a network that is seen and not cellular gives FILTER. `startVpn` → `switchTo(FILTER)` reads lockdown on and
  repeats the same order. With no network seen and `sink` stored, the sink is kept (`applyShape`), which is closed.
- **`establish()` fails on the way out.** `alwaysOnAfterEstablish` still writes lockdown off, as before. With the old
  sink tunnel still there, its routes keep catching everything (it isn't bypassable). With no old tunnel (after a
  restart), the end state is "no VPN, data allowed", the same as main's lockdown-off-first. The retry chain runs either
  way.
- **The reverse direction is unchanged.** With lockdown off, `before` returns null for SINK and `after` returns SINK, and
  for FILTER↔PASS `before` returns the shape and `after` returns null, as on main.
- **`apply()`'s `keepLockdown`** (`AppEnforcer.kt:1525`, `MobileNetworkRuntime.kt:161`) records the decision and leaves
  lockdown on only while the live tunnel is up. The `KidVpnService.start` right after it reaches `reshape()`, where
  `wanted != shape` leads to `switchTo` and lockdown goes off last. If the service decides differently (still SINK), the
  `wanted == shape` branch writes SINK back, which is consistent. Lockdown can't be stranded on: every later
  `reshape()`/`switchTo` writes without `keepLockdown`. The worst case is a start that throws, which leaves the phone
  closed (not open) until the next sync or network change, and that start is the same `startService` as on main.
- **Android 14 and 16 do restart the VPN's agent when lockdown changes.** Measured below:
  `Vpn: setting state=CONNECTING, reason=Lockdown mode changed`, a new `registerNetworkAgent` and the old VPN network
  disconnected. So the last change on the way out is a new netId, which is the premise of design 120 fix A. Android 17
  was not run (no API 37 image boots on this VM, §6as).

## FLAG_ACTIVITY_CLEAR_TASK under lock task

- `NEW_TASK|CLEAR_TASK` (`flg=0x10008000`) on a locked map task reuses the task: `performClearTaskForReuse` keeps the
  `Task` object, so it stays in `mLockTaskModeTasks`. The emulator showed the same task id across every start, listed as
  `#1` after Home's `#0`.
- `isLockTaskModeViolation(task, isNewClearTask=true)` re-checks the task's auth. For an allowlisted package that is
  `LOCK_TASK_AUTH_ALLOWLISTED`, so there is no violation and no "App is not available". A package that is not
  allowlisted was already refused without the clear. When a package leaves the list, `updateLockTaskPackages` finishes
  its tasks anyway.
- `activityBlockedFromFinish` guards only the root task of lock task, which is Home's (§6ai). The map task's
  activities finish normally.
- `Intent.setIdentifier` is API 29, so no version gate is needed. Nothing in 15-17's activity-start hardening touches an
  explicit-package start from a resumed activity.
- **The "sent the link itself" exception.** `referrer?.host` is the target's package only when the target started the
  handler, or when a sender sets `EXTRA_REFERRER(_NAME)` (L4). Without the clear, the fresh identifier alone still keeps
  the start from being dropped (row e).

## Findings

### L1 - The notes say "not checked on an emulator" and "lock task wasn't on"; both are now checked

`docs/notes/launcher/dns-and-vpn.md:284` ("Not checked on an emulator (no debug way to toggle the sink without a
server)") and `docs/notes/launcher/map-links.md:39` ("Lock task wasn't on in either (no enrolment)").
**Fix:** replace both with this run: 2026-11-19, `compat34` and `jelly36`, enrolled and in lock task, rows below.

### L2 - Into the sink through `apply()`, the last change is the sink tunnel in place, not a lockdown change

Seen on 14 (16:00:33): after a PWA save of "Bare Vibb" on cellular, `Lockdown mode changed` (.064, a new agent over the
filter tunnel) came first, then `Tunnel: sink` (.085, in place). Only the watcher's own switch ends on the lockdown
change. This is harmless: nothing connects inside the sink, and the way out is the case that matters, which is heard.
But design 120 M1's "into the sink ends with lockdown on, a new netId" (`docs/review/120-…-design-qa.md:106`) holds only
for the watcher's path.
**Fix:** one clause in `dns-and-vpn.md`: apply() into the sink still writes lockdown first (closed), so its last change
is the in-place sink. No code change.

### L3 - A route in progress in Organic Maps isn't checked against the clear

`MapLinkActivity.kt:85`: the clear recreates `MwmActivity` whenever the map app is in the background. The note accepts
the loss of "an open search or card". An active route or turn-by-turn navigation (OM's routing state is
process-level, with its own foreground service) was not checked. That would need a country map on the emulator.
**Fix:** check it once on a phone with a downloaded map (start a route, go Home, tap a map link in Meldinger). Then
either note it as kept or tell the user.

### L4 - The referrer is the sender's to set

`MapLinks.kt:117`: `Activity.getReferrer()` returns `EXTRA_REFERRER`/`EXTRA_REFERRER_NAME` when the sender sets one.
So any app can make the start skip the clear. Row e did exactly that from the shell. The cost is only the old, uncleared
open, now with the fresh identifier, and nothing about lock task or the filter. No fix. Worth one clause in
`map-links.md` so nobody later reads the referrer as proof of the sender.

### Not of this diff (unchanged from main)

When the sink is left with no live tunnel (`applyLeavesLockdownToSwitch` false, for example a cold start before the
service is up, or the retry alarm), lockdown still goes off before the filter tunnel exists. That leaves a short
window of unfiltered DNS. Data is allowed by then, so nothing is cut that the parent wanted cut. This is as on main and
as the note says ("with no tunnel up it writes as before").

### The centre (00a374485)

Level 10 keeps 3 + 2·10 = 23 bits. That is ≤ 24, the smallest shared prefix measured (RAI), so every measured place's
point lies in the id's cell. The marker bit is `1 shl 40`, which is correct. The Artbox row in `map_link_rules.json`
and the test (≤ 2.1 km, Steak no Don ≤ 1 km) match. No server-side copy of the centre exists (grep of
`server/testdata/map_links`), so no contract change was needed.

### Tests

- The decision functions are covered row by row: `clearsTask`, `startIdentity`'s uniqueness,
  `alwaysOnBefore/AfterEstablish` with `lockdownNow`, and `applyLeavesLockdownToSwitch`.
- The source checks pin the wiring in `open()`, `switchTo` and `applyVpnRestrictions`. "The handler is the one place
  that starts a map app" guards against a second starter.
- `assertClosedAllTheWay` models lockdown as "blocks everything outside the VPN". That matches the emulator's "VPN
  required for UIDs" and the filter's `::/0 unreachable`.

## Emulator

Rig: main's debug server (0.85.1, `cargo build -j 1`) on a copy of the C2 gate's data. The branch's debug launcher as
Device Owner (§4), enrolled with a typed code. 114's setup hold was ended with "Lås igjen", so the kiosk was on:
`mLockTaskModeState=LOCKED`, not in a pause. Organic Maps 2026.11.11-39 (F-Droid, the 2026-11-18 gate's verified APK)
was allowed under Apps, and its `outside_play` block was let out with "Allow these apps" (expected, a sideload). The
world map was downloaded once before the rows. The map-links switch was on, with Mobildata "Bare Vibb".

The link was the handler's explicit VIEW, as Meldinger's: `am start -n …/MapLinkActivity -d <google.com/maps/place
link with !3d!4d>`, so the point is offline and nothing goes to Google. A = Kiwi Majorstuen, B = Bryggen. Each row
checked:
- the card's title (uiautomator);
- `mLockTaskModeState` and `mLockTaskModeTasks`;
- `ActivityTaskManager: START` (flags);
- that no "App is not available"/"not available" appeared;
- a screenshot.

Versions:
- **Android 14:** `compat34` (`-wipe-data -dns-server 8.8.8.8`).
- **Android 16:** `jelly36`, equal to `~/kid-look-1-avd-pre-start` before (22 of 22 sums), restored from it afterwards
  (22 of 22, the same file list, `jelly36.ini` equal).

One emulator at a time under `flock -o ~/emulator.lock`, port 5556. The user's emulator was not touched.

| Row | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| a. Cold (`force-stop`, then A) | **Pass.** Card "Kiwi Majorstuen, …". `flg=0x10008000`. LOCKED, tasks `#0` Home, `#1` OM (#11) | **Pass**, the same (OM task #13) |
| b. In the background at A, then B | **Pass.** Card "Bryggen, …", the same task #11, LOCKED | **Pass** |
| c1. The same link twice (B, Home, B) | **Pass.** Card "Bryggen", LOCKED | **Pass** (the last card was left open: 16's case) |
| c2. A after B (background) | **Pass.** Card "Kiwi" | **Pass** |
| d. OM on screen, B from another sender (cleared while visible) | **Pass.** Card "Bryggen". The map drew, not black (`d-fg-later-34.png`) | **Pass**, not black |
| e. OM on screen, the link "sent by OM" (`EXTRA_REFERRER_NAME android-app://app.organicmaps`: no clear, `flg=0x10000000`) | The map moved to the place with no card (as the note says). Not black, LOCKED | the same |
| e2. The same, OM in the background | Card "Bryggen", LOCKED | the same |
| Back from OM | Home on top, LOCKED, nothing stuck | the same |
| "App is not available" | Never | Never |
| VPN: Wi-Fi off (into the sink, the watcher) | `Tunnel: sink` 15:59:29.716, then `Lockdown mode changed` .749 (network 103 → 104), `Always-on: … lockdown true (sink)` .788. `always_on_vpn_lockdown=1` | `Tunnel: sink` 16:10:37.557, then `Lockdown mode changed` .602 (103 → 104), `lockdown true` .705 |
| VPN: Wi-Fi on (out of the sink, the watcher) | `Tunnel: filter` 15:59:41.412, in place on 104 (`[104 WIFI\|VPN] validation passed`), then `Lockdown mode changed` .424, a new agent 106, `lockdown false (filter)` .441, 104 disconnected .491. **Lockdown stayed on until the filter tunnel was up; the last change is a new netId** | `Tunnel: filter` 16:10:49.149, then `Lockdown mode changed` .154, new agent 106, `lockdown false` .161, 104 disconnected .193. **The same** |
| VPN: out of the sink through `apply()` (PWA "På" on cellular) | `Always-on: lockdown stays on until the tunnel's switch (filter)` 16:00:18.132, `Tunnel: filter` .171, `Lockdown mode changed` .176 (new agent 108), `lockdown false` .188, 107 disconnected .197 | 16:11:05.410 kept, `Tunnel: filter` .440, `Lockdown mode changed` .445 (108), `lockdown false` .453 |
| VPN: into the sink through `apply()` (PWA "Bare Vibb" on cellular) | `Lockdown mode changed` 16:00:33.064 (109), `lockdown true (sink)` .071, then `Tunnel: sink` .085 in place (L2) | not logged |

Not run:
- Android 17 (no image boots on this VM).
- CoMaps in lock task. The implementer's run covered CoMaps without lock task. Its start is the same code path.
- A real Meldinger tap. The explicit start is the same intent, and only the referrer differs (shell vs launcher, both ≠
  target).
- A kill between the two VPN steps (reasoned above).

The rig and the screenshots are in the scratchpad's `mofqa/rig/`.
