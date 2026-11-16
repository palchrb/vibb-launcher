# Code QA: branch `mobilnett` (designs 94, 95 revision 3)

Reviewed 2026-11-13, read-only: ec70e04b, ddcdb30b, 11dbc038 (and the note f2a703d9), diffed against
`git merge-base mobilnett main` (12bd1016). Spec: `docs/design/94-data-roaming.md`, `docs/design/95-connectivity-control.md`
(revision 3 first). No build, no emulator. "Verified" = read in the code, traced end to end; "inferred" = depends on
Android behaviour not run here.

## High

### H1. The service writes lockdown off at a start with no network, and overwrites the record that protects it (fail-open, inferred)

`KidVpnService.wantedShape()` (`KidVpnService.kt:184`) is `vpnShape(storedInputs(), lastBest ?: bestNet(this))`, not
`applyShape`. With no physical network (`lastBest == null` at `onCreate`, `bestNet == null`), `sinkReason` is `OFF`, so a
Bare Vibb phone decides `FILTER` (or `PASS` with the filter off). `startVpn()` (`:192`) then calls
`writeAlwaysOn(this, FILTER)`, which **writes `vpn_shape = "filter"` first** (`MobileNetworkRuntime.kt:74`) and then
`setAlwaysOnVpnPackage(..., lockdown = false)`.

This defeats `applyShape`'s own guard (`MobileNetwork.kt:110`, "never writes lockdown off on a doubt about the
network"): once the service has written `filter`, `apply()` reads `persisted = FILTER` and decides `FILTER` too. The
design's invariant ("never writing lockdown off while the persisted shape is the sink", rev. 3 item 2) holds for
`apply()` only.

Scenario: Bare Vibb on, the phone on cellular, reboot (or the process restarted by the system with the radio not yet
up). The DPM-persisted lockdown is on at boot, so nothing would leak - but the always-on start reaches `onCreate` before
cellular data is up, and the service turns lockdown off. When cellular comes up, every app's boot burst (Play services'
check-in, FCM, chat apps) goes out over cellular through the filter's fall-through until the best-network callback
runs `reshape()` on a main thread that is busy with the launcher's cold start, and `establish(SINK)` completes. The same
happens after airplane mode if the process restarts while in it.

Verified: the code path and the record overwrite. Inferred: the leak's size (callback latency at boot).

Fix direction: the service decides with `applyShape(storedInputs(), lastBest ?: bestNet(this), storedShape())`
wherever it decides (`onCreate`, `onStartCommand`, `startVpn`, `reshape`), or at least never writes a non-sink shape
while the net is unknown and the record says sink. A test should drive the service's decision function, not only
`applyShape` (see T1).

## Medium

### M1. A failed switch to the sink leaves cellular open with no retry (fail-open, verified)

`reshape()` (`KidVpnService.kt:281-283`): for `wanted == SINK` nothing is written before `establish(SINK)`, and on
failure the old `FILTER`/`PASS` tunnel stays with lockdown off. The comment says "from the sink that fails closed,
never open" - true leaving the sink, false entering it. No retry is scheduled (the tunnel isn't null, so
`retryTunnel` never runs) and `vpn_shape` still says `filter`, so the next `apply()` is the only way out (the next sync
or SSE nudge; up to the 30-minute backstop on an idle phone).

Scenario: Wi-Fi drops, `establish()` throws or returns null (a `SecurityException` from a racing prepare, a transient
`IllegalStateException`) - every app has cellular until the next sync.

Fix direction: on a failed establish toward the sink, write `alwaysOnFor(SINK)` anyway (lockdown with the filter's
tunnel cuts other apps - fail closed, which is what Bare Vibb wants on cellular) and schedule the retry chain.

### M2. The roaming reason with the filter off has no standing watcher (fail-open, verified, narrow)

`vpnShape` (`MobileNetwork.kt:97-101`) gives `PASS` only for `parentSink`. With the Web filter off, Bare Vibb off and
the roaming lock on (the default "Av"), a lingering roaming session is sunk only while the service runs: on Wi-Fi the
shape is `NONE`, `reshape()` (`:263`) writes always-on off and stops the service and its watcher. Design 95 rev. 2's
"filter off + a sink reason -> pass" covered both reasons.

Scenario: abroad, lock set while roaming data was up (lingering). The kid joins hotel Wi-Fi - `NONE`, service gone. With
"Mobile data always active" (on by default) the cellular session survives in the background; the kid leaves the Wi-Fi
and every app uses the lingering roaming session until the next `apply()`.

Fix direction: `PASS` also when `roamingLocked` (and the filter is off), or the service stays up while any sink reason
is configured.

## Low

- **L1. `DISALLOW_CONFIG_VPN` yields to the parent's explicit hardening "off" even under the sink** (`Hardening.kt:99`,
  `policy?.disallowConfigVpn == false`; fed by `AppEnforcer.kt:370`). Rev. 2 says the lock follows the sink. With the
  hardening switch off and Bare Vibb on, the kid can open Settings › VPN while the sink holds. Whether Android lets a user
  change a DO-set always-on/lockdown there is [needs device test]; at least the page should not imply Bare Vibb is
  tamper-proof in that combination, or the sink should force the lock.
- **L2. Two unsynchronised writers of always-on.** `apply()` (background thread, its own `bestNet`) and the service
  (main thread, `lastBest`) each read-compare-write (`MobileNetworkRuntime.kt:73-90`). Interleaving can leave lockdown
  matching the other's view for a moment, and `apply()` writes lockdown on before the sink tunnel exists (lockdown over
  the filter's tunnel: other apps cut, fail closed). It self-heals because `apply()` always ends in
  `KidVpnService.start` -> `onStartCommand` -> `reshape()`. Noting it so nobody removes that start.
- **L3. The Wi-Fi -> cellular gap is open by construction** (FILTER/PASS have lockdown off; the sink comes after the
  callback). Accepted in design 95 §2.3 (64 ms measured on the probe), but the launcher's main thread does more than the
  probe's; measure it in §6ak B.2.
- **L4. `SinkPackets`** (`SinkPackets.kt`): bounds are safe (every index checked against `n` or within the MTU buffer;
  `IndexOutOfBoundsException` caught; no other throw found), the RST+ACK numbers and both checksums are right, a RST is
  never answered. Gaps: an IPv4 header with IHL < 5 or a non-first fragment is parsed as TCP and answered with a
  garbage RST (the kernel drops it; harmless); DNS over IPv6 or UDP to anything but 53 is dropped, so an app with its
  own v6 resolver waits for its timeout. Tests cover none of the short/malformed cases (only `ByteArray(3)`).
- **L5. The relock counts as done even when the wait timed out** (`MobileNetworkRuntime.kt:128`, the sub is added to
  `relocked` before the clear is applied), and nothing reads the SIM back afterwards; the PWA's "still on" line is the
  only signal. The comment calling `ACTION_USER_RESTRICTIONS_CHANGED` a `@SystemApi` constant is wrong (public since
  API 21) - harmless.
- **L6. "Låst opp mens telefonen er åpnet for oppsett" without a pause** (`server/src/mobile_network.rs:395-398`): the
  `unlocked` line shows whenever the report says unlocked and the choice is `off`, not only while `paused` - right after
  the parent switches "Telefonens valg" -> "Av" (the report predates the next sync), or when the launcher failed to set
  it. Gate it on `paused` (as `lifted` is) and say something else otherwise. Likewise the lock group has no line when the
  lock is wanted, the phone reports it unset and no pause runs.
- **L7. `mobile_data.sink` reads `off` while the sink holds with no network** (`mobileDataReport` uses `bestNet`, not the
  persisted shape) - cosmetic.

## Tests that would not fail

- **T1.** `MobileNetworkTest.the single owner never drops the sink's lockdown on a doubt` exercises `applyShape` only;
  the service never calls it. H1 passes every test. `QuietNotificationsTest`/`CustomDnsGuardsTest` pin source strings
  (`if (wantedShape() != VpnShape.NONE) startVpn()`), which say nothing about which rule `wantedShape` uses.
- **T2.** No test for `reshape()`'s failure branch (M1) or for `NONE` stopping the watcher under a configured roaming
  reason (M2): both are Android-side; a pure "next shape and always-on writes for (current, wanted, establish ok)"
  function would make them testable.

## Checked and fine (verified)

- The pause, Åpne for oppsett and the offline override lift all three: `dataRoamingLocked`, `mobileSettingsLocked`,
  `parentSinkOn` all take `overrideActive`; the sink, lockdown and the sink's `DISALLOW_CONFIG_VPN` go with the shape
  `NONE`/`FILTER`. Unknown values fail closed (`"eea"`, `"later"`); an older server's missing keys lock nothing; an older
  launcher ignores the keys and the page says so. `LastEnforcedPlan`'s "off"/true defaults apply only to a corrupt cache
  (`PolicyGate`), not after an upgrade.
- No early return in `applyHeld` skips the new restrictions; `apply()` is `@Synchronized` and off the main thread, so
  the relock's 5 s wait can't block the UI. `SubscriptionManager.getSubscriptionId(slot)` is API 34 = minSdk.
- The launcher is `addDisallowedApplication` in every shape, so its sync/SSE never move at a switch; the filter still
  routes other apps' DNS on Wi-Fi; the old tunnel's read loop ends stale (`readLoopEnded` identity check).
- Server: migration 0143's defaults (off/on/1) reach existing phones; the POST is under the admin router's
  `require_session` + `csrf_guard`; the column comes from a fixed list; 400 on an unknown value/group writes nothing,
  404 for an unknown phone; the 303 to `#mobile-data`/`#data-roaming`/`#mobile-lock` is logged by `settings_log`;
  `scroll-restore.js`/`autosave.js` are on every page; the 75 new keys exist in both `en` and `nb`; the contract is
  byte-identical and both sides compare the copies (`tests::mobile_network::the_contract`,
  `MobileNetworkTest.the contract is identical to the server's copy`); the report is cleaned field by field.

## For the emulator pass (§6ak)

1. **H1:** Bare Vibb on, Wi-Fi off, cellular data off (`svc data disable`), reboot; once booted, `settings get secure
   always_on_vpn_lockdown` and `vpn_shape` before turning data on - expect `1`/`sink`; this build should show `0`/`filter`.
   Then `svc data enable` and watch the other app's first requests in the gap. Repeat with airplane mode + `am kill`.
2. **M1:** hard to force; at least confirm in logcat that no "Failed to establish VPN" occurs across 20 Wi-Fi <-> cellular
   switches, and that the IO thread count (`ps -T`) stays flat (each switch ends one read loop).
3. **M2:** Nettfilter off, Bare Vibb off, roaming lock on, a lingering session (D.2) -> Wi-Fi on -> Wi-Fi off: does the
   other app get roaming data? (This build: yes, until the next sync.)
4. B.2: time from `svc wifi disable` to `Tunnel: sink`, with the launcher's cold start just done, and whether the other
   app got any byte through.
5. B.7: the pass shape's DNS on Wi-Fi (no DNS server on the VPN) - names must resolve for other apps.
6. Settings › VPN with the hardening "Blokker VPN-innstillinger" switched **off** and the sink up (L1): can always-on or
   lockdown be turned off?
7. D.3 then D.1 quickly: the Mobilnett page must not say "Låst opp mens telefonen er åpnet for oppsett" without a pause
   (L6).
8. `setAlwaysOnVpnPackage(admin, null, false, emptySet())` (the `NONE` write) succeeds on 36 - check the log line
   "Always-on: none" appears and no "Couldn't set always-on".
