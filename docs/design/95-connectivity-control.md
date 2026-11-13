# Design 95: connectivity control as one whole - roaming by region, "bare Vibb", and a cheap line home

Status: research and test note, **revision 2 (design QA)**, 2026-11-13. No code is built. It extends design 94 (data
roaming, revision 2, approved, not built; branch `design-94`) and changes it in the places listed in §6. Everything
marked "measured" was run on the emulator (`jelly36`, Android 16, Google Play image) with throwaway probes and the real
debug launcher from `main` (`f4c1b196`) against a local server built from the same commit. The probes and logs stayed
in the scratchpad; nothing here was installed on a phone.

The user (2026-11-13): *"lots of freedom to control whether the phone has a connection or not, while the phone can
ideally always reach the server cheaply."*

**Revision 2 (design QA, 2026-11-13), checked against the logs, android15-release (`Vpn.java`, `ConnectivityService`,
`DataNetworkController`, `DataSettingsManager`, `GlobalSettingsHelper`, `ServiceStateTracker`) and the repo
(`KidVpnService`, `AppEnforcer`, `Hardening.kt`, `createMdmApi`, `MdmSyncWorker`, `PinVault`, `SyncSchedule`,
`AppDownloadPlan`, `LocateCommands`, `device_routes.rs`, `device_api.rs`, `streams.rs`, `admin_auth.rs`):**

- **§2.2 always-on has two writers (high).** `AppEnforcer.applyVpnRestrictions` calls
  `setAlwaysOnVpnPackage(admin, pkg, false)` on every `apply()`, so every sync and every process start would turn the
  sink's lockdown off again, with a leak window each time. One function now owns the always-on call (package,
  lockdown, allow list), and `apply()` and the service both go through it.
- **§2.2 the launcher is excluded in both shapes (high).** Revision 1 kept the launcher inside the filter shape and
  excluded it only in the sink. The probe was measured excluded in **both** shapes (`SinkVpn.java`, the dns branch),
  so the "nothing of ours moves" result was never shown for the shape the note proposed. Moving the launcher's uid out
  of the VPN at a filter -> sink switch leaves its open sockets (the SSE stream, a sync) marked with the VPN's netId,
  which the sink's `0.0.0.0/0` then catches and RSTs. Excluding the launcher in both shapes is what was measured, and
  its uid never moves.
- **§1.4, §4.2, §4.3 "Tillat i EU/EØS" made fail-closed and quiet (high).** (a) On a one-modem phone revision 1 left
  `data_roaming=1` in place at home after an EEA trip, so a crossing straight from home onto a non-EEA network (the
  Russian border in Finnmark, a ferry's 901 network) carried data until the launcher reacted. Now the lock is on
  except on a known EEA roaming network. (b) On a dual-SIM phone a transient `mcc == null` locked and wiped the per-SIM
  value for the rest of the trip; now an unknown MCC keeps the last decision. (c) "The phone reports before the lock"
  can't happen: the phone learns it is outside the EEA only once it is on that network. Vibb's own line now goes quiet
  on a lingering non-EEA session (§4.3), so the lock's "zero bytes" holds apart from Android's own traffic.
- **§3.4, §5 the 120-minute backstop is dropped (high).** `streams.rs` shows a phone as **Offline** when no request
  came for `ONLINE_WINDOW_MINUTES` (60), even with its stream open. That window is sized for a 30-minute inexact alarm
  that Android may deliver 1.75 x late. A slower backstop would show every roaming phone as Offline in the PWA. After
  the other changes it would save only ~25 KB/day.
- **§3.5 the heartbeat's server side specified (medium-high).** `device_status` holds one full row per report. The
  status handler diffs against the previous row (security log, new apps, foreign access, unseen boots), the capability
  checks read the latest row, and the battery history reads rows over time. A heartbeat now inserts a copy of the
  latest row with the heartbeat's fields, updates `last_seen_at`, runs no diffs, and answers `{"full_report": true}`
  when its hash isn't the stored one.
- **§3.5 the 304 is specified against the real cache (medium).** The cached policy is re-encoded by the launcher's
  DTOs and has the PIN hashes moved out by `PinVault`, so it can't be the kept body. The launcher now keeps the raw
  200 body, sealed, with the ETag, its versionCode and the server URL. A 304 is applied exactly as that body arriving
  again (`judgeFresh` included). The server hashes the bytes after `pop_command`.
- **§3.3, §5 no gzip on the policy (medium).** The policy carries the override PIN's hash and salt and the kid lock's
  (low-entropy PINs, so a leaked hash is a leaked PIN). The admin listener's rule (`admin_auth::Compressible`) keeps
  every secret-carrying answer uncompressed. BREACH is impractical here, but the same rule applies (§3.3). The 304
  removes most of what gzip would have saved. Gzip stays for answers without secrets, and for the status report
  upload.
- **§2.2 filter off + a sink reason (medium).** The network watcher lives in `KidVpnService`. With the parent's
  filter off the service is stopped on Wi-Fi, so nothing would start the sink when Wi-Fi drops. A third, "pass" shape
  keeps the service and its watcher up. `DISALLOW_CONFIG_VPN` also follows the sink, not only the filter.
- **§2.4 what "bare Vibb" also cuts (medium).** Google Play services is blocked too, so network location and A-GPS
  assistance stop, and the parent's locate falls back to raw GPS. Chat apps' pushes stop as well. Both are now named,
  with a device test. The launcher's own bulk downloads (catalog APKs, self-update) are not covered by the sink, so
  they now wait for Wi-Fi while it is on.
- **§6 national roaming (medium).** `DataNetworkController` treats domestic roaming as roaming, so design 94's "Av
  (låst)" cuts data at home in a national-roaming area, and on a dual-SIM phone "Tillat i EU/EØS" can't help. The rule
  now treats a roaming network with the SIM's own MCC as home. The device test is a gate before 95-R.
- **§1.4 clear-then-write and the kick (medium).** Writing `"1"` right after clearing the restriction may be refused
  (UMS posts the change). The write now waits for `ACTION_USER_RESTRICTIONS_CHANGED` and then reads the value back.
  The kick runs only when cellular would be the default network, because a `requestNetwork(CELLULAR)` on Wi-Fi brings
  up roaming data for nothing.
- **Low:** shared client details (§3.5: the SSE stream must share the pool, TLS resumption on HTTP/1.1, a ping
  interval); numbers (§3.2: a real phone's battery level changes the report too; two SSE streams were open for the
  first 13 minutes; the policy row left out the upload; tsnet's own traffic isn't measured); the kid's switch counts
  only while the parent's tick is on (§4.1); the 119 ms counts from the request, not from the write (§1.2); a VPN
  without an IPv6 address already blocks IPv6 (§7); the section anchor and the CI path lists (§6, §8); the test
  device's name dropped.

## 0. Verdict

1. **Can a device owner turn data roaming ON? Yes on a single-modem phone, no on a dual-SIM phone.** (§1)
   - `DevicePolicyManager.setGlobalSetting(admin, Settings.Global.DATA_ROAMING, "1")` is allowed (DPMS
     `GLOBAL_SETTINGS_ALLOWLIST`, exact names). It is refused silently while `DISALLOW_DATA_ROAMING` is set.
   - Telephony reads the **bare** key only when `getSimCount() > 1` is false. A dual-SIM phone (the Jelly Star has two
     slots) reads `data_roaming<subId>`, and the DO gets `SecurityException: device owners cannot update data_roaming1`
     (measured). So the bare write does nothing there.
   - Telephony does not watch the setting. Writing `1` brings nothing up by itself (measured: 44 s with no data, until
     some app filed a new network request). **Our own `requestNetwork(TRANSPORT_CELLULAR, INTERNET)` after the write
     brought roaming data up 119 ms after the request** (measured). A new request is evaluated at once and reads the
     setting fresh.
   - Writing `0`, or setting the restriction, does **not** end a running roaming session (measured, both; design 94
     §2.4.1 confirmed).
2. **Keeping only our connection works, as a second shape of the one `KidVpnService`.** (§2)
   - "Sink" shape: `0.0.0.0/0` and `::/0` into the TUN. DNS is answered `REFUSED`, TCP gets a RST, the rest is
     dropped. The launcher is excluded with `addDisallowedApplication`, **in both shapes** (rev. 2). Measured: the
     excluded probe's HTTPS worked (15-44 ms) while the other app failed in 1-3 ms. Without the RST it hung 8-10 s per
     attempt.
   - The shape follows the network: cellular gives the sink, Wi-Fi gives today's DNS-only filter. A Wi-Fi to cellular
     switch re-established the TUN in 4-12 ms, 64 ms after Wi-Fi was lost. The VPN kept its network id, so apps never
     saw the VPN go away (measured).
   - Idle cost: 102 packets and **10 ms of CPU in 10 minutes** with Google Play services running (measured).
   - Lockdown must follow the shape. With lockdown on, the DNS-only shape breaks every other app (measured, as
     `AppEnforcer` already says). In the sink shape lockdown is what we want. **One function owns the always-on call**
     (rev. 2: today `apply()` writes lockdown off on every sync).
3. **The idle line home costs about 0.9 MB/day today, and about 0.15-0.2 MB/day with the recommended changes.** (§3)
   Measured: the real launcher used **51 KB in an idle hour on cellular (1.2 MB/day)** in netstats. The server line
   was 29 KB of that (two syncs at 11 KB each, 15 SSE keepalives). The rest is the DNS filter's own DoT upstream for
   other apps' lookups. Over https (the user's setup) each sync also pays a full TLS handshake, because every sync
   builds a new `OkHttpClient`. The server line is then ~0.86 MB/day on the emulator's small phone, and an estimated
   ~1.4 MB/day on a configured Jelly Star. The policy came back byte-identical in all three idle syncs, and the report
   changed in one field. Neither answer is compressed (the device router has no `CompressionLayer`; only the admin
   router has). A 304, a heartbeat report with gzip, and one shared client cut the line five- to six-fold. The policy
   itself stays uncompressed: it carries the PIN hashes (§3.3).

**The recommended whole (§4):** three parent choices in the PWA's Mobilnett group:

- Dataroaming: Av / Tillat i EU/EØS / Telefonens valg.
- Mobildata for apper: På / Bare Vibb.
- Whether the kid has a Mobildata switch in Quick Controls.

Two levers on the phone enforce all of them: the roaming restriction (zero bytes) and the sink (only Vibb's bytes).
None of them cuts our own connection, except the roaming restriction outside the EEA, which is its point.

## 1. Question 1: the EU/EEA roaming choice

### 1.1 What was tested, and how

The emulator's console fakes roaming only when the network's MCC differs from the SIM's. `ServiceStateTracker
.updateRoamingState` (opt/telephony 15 ~l.2674) turns a roaming registration back into home when
`isSameNamedOperators` holds (same MCC, and the SIM's name equals the network's). `gsm data roaming` alone therefore
reads `roaming=false` on the stock AVD. What worked:

- A private copy of the system image's `data/`, with the AVD started with `-sysdir` pointing at it (the SDK's image
  was not touched). In it the modem simulator's SIM became a Norwegian one: `iccprofile_for_sim0.xml` `CIMI
  242010000000000` and `EF_AD` MNC length 2. The SIM then reads `24201` (Telenor) while the network stays `311740` in
  the US.
- `adb emu gsm data roaming` + `gsm voice roaming` then gives `isNetworkRoaming() = true`, `getNetworkOperator() =
  311740`, `getNetworkCountryIso() = "us"`.
- The SIM had no APN in the image's list, so the probe (device owner) added one with
  `DevicePolicyManager.addOverrideApn` + `setOverrideApnsEnabled(true)`. Then roaming data came up:
  `active=CELL,ROAMING,VAL,metered` (no `NET_CAPABILITY_NOT_ROAMING`).
- The probe `me.vibb.probe.sink` (debug-signed, its own package) was the device owner. Commands went in by
  `am broadcast`. The radio log's `DNC-0` lines show each data evaluation and its disallowed reasons.

The emulator has one modem (`getActiveModemCount() = 1`), so the dual-SIM path is read from AOSP and checked by the
allow list's answer, not run.

### 1.2 Results (measured, `q1.log`)

| Step | What the probe did | Result |
|---|---|---|
| A | Roaming data up; `setGlobalSetting(DATA_ROAMING, "0")` | `isDataRoamingEnabled() = false`, data **stays up** (20 s+) |
| B | Flip `gsm data home` then `roaming` | Existing networks re-evaluated: torn down, `ROAMING_DISABLED` |
| C | Roaming, down; `setGlobalSetting(DATA_ROAMING, "1")`; no other trigger | Nothing for 44 s. Then a `NEW_REQUEST` from an unrelated app (Photos, uid 10169) brought it up |
| D | Same, then `requestNetwork(TRANSPORT_CELLULAR + INTERNET, 8 s)` | `onAvailable` **119 ms after the request** (the probe filed it 3.1 s after the write), `DNC-0: Data evaluation: evaluation reason:NEW_REQUEST, Data allowed reason: NORMAL`. Data stays the default after the request is released |
| E1 | Roaming data up; `addUserRestriction(DISALLOW_DATA_ROAMING)` | Android writes `data_roaming=0`, data **stays up** (20 s+) |
| E2 | Restricted; `setGlobalSetting(DATA_ROAMING, "1")`; also `adb shell settings put` | Both refused silently; the value stays `0` |
| E3 | Restriction cleared | Nothing written; the value stays `0` |
| F | `setGlobalSetting("data_roaming1", "0")` | `SecurityException: Permission denial: device owners cannot update data_roaming1` |

Also seen: a target-33 app reading `Settings.Global.getString("data_roaming")` gets `SecurityException: Settings key:
<data_roaming> is only readable to apps with targetSdkVersion lower than or equal to: 32`. The launcher reads
`TelephonyManager.isDataRoamingEnabled()` instead (design 94 §2.5 already does).

Not measured: a `"1"` written right after the restriction is cleared. UMS posts the change to its handler (design 94
rev. 2, §2.4.2), so the write may still be refused. The ON path therefore waits for `ACTION_USER_RESTRICTIONS_CHANGED`
after the clear, writes, and reads `isDataRoamingEnabled()` back, with one retry (§8, 95-R).

### 1.3 AOSP (android15-release)

- `DevicePolicyManagerService.setGlobalSetting` (~l.14987): device owner only, and the name must be in
  `GLOBAL_SETTINGS_ALLOWLIST` (~l.702; `DATA_ROAMING` at ~l.707). It is written with a clean identity through
  `Settings.Global.putString`. The restriction check in SettingsProvider still applies (design 94 §2.3), hence E2.
- `GlobalSettingsHelper.getSettingName` (~l.86): `data_roaming<subId>` when `TelephonyManager.getSimCount() > 1`.
  `getSimCount()` is `getPhoneCount()`, the active modem count. A dual-SIM phone in dual standby reads the per-SIM key
  even with one SIM in it.
- `DataSettingsManager.isDataRoamingEnabled` (~l.560) reads the setting fresh each time. The manager observes only
  `DEVICE_PROVISIONED` and `DEVICE_PROVISIONING_MOBILE_DATA_ENABLED` (~l.294), so a write fires no callback.
- `DataNetworkController.evaluateNetworkRequest` (~l.1661): `getDataRoaming() && !isDataRoamingEnabled()` adds
  `ROAMING_DISABLED`. `ServiceState.getDataRoaming()` is true for **domestic** roaming too (national roaming, §6
  item 4). A new request (`onAddNetworkRequest` ~l.1301, reason `NEW_REQUEST`) is evaluated at once: that is the kick
  in step D. Unsatisfied requests are also re-evaluated on: data enabled, data profiles, a TAC change (delayed), a
  service state change, the end of a call, SIM loaded, a retry. When Wi-Fi is lost, the default request goes back to
  telephony as a new request, so no kick is needed while Wi-Fi is the default.
- `TelephonyManager.setDataRoamingEnabled` needs `MODIFY_PHONE_STATE` or carrier privileges (~l.11908). A device owner
  has neither, and there is no other public API.

### 1.4 What this means for "Tillat i EU/EØS"

"In the EEA" below means: roaming, on a network whose MCC is **known** and in `EEA_MCC` (§4.2), and not 901. A
roaming network with the SIM's own MCC counts as home (national roaming, §6 item 4).

| Phone | Roaming in the EEA | Anywhere else: at home, outside the EEA, 901, MCC unknown | Back in the EEA |
|---|---|---|---|
| One modem | clear the restriction, wait for it, `setGlobalSetting(DATA_ROAMING, "1")`, read back, the kick if cellular is the would-be default | **the restriction** (fail-closed: `0` before any crossing). Zero bytes, plus the sink and Vibb quiet for a session that outlives it (§4.3) | as the first column: **automatic** |
| Two modems (Jelly Star, likely) | needs Android's own per-SIM switch on (once, by a person) | the restriction **only on a known non-EEA MCC or 901**. An unknown MCC keeps the last decision (a transient `null` must not wipe the per-SIM value). At home nothing is set | the restriction is cleared, but the per-SIM `0` stays: **a person turns it on again** |

The two rows differ on purpose. On one modem the lock costs nothing to set and to lift, so it is on everywhere but an
EEA network, and a crossing from home straight onto a non-EEA network is closed in advance. Examples are Finnmark to
Russia, and a ferry's maritime network. On two modems every set of the lock wipes the per-SIM value, so it is set
only when needed. That leaves a window from the crossing to the launcher's reaction (the service-state callback, well
under a second while the process runs) in which Android itself may move bytes (§4.4).

So on the Jelly Star the switch to "off outside the EEA" is automatic and safe, but the way back is not. Note what a
single trip does. The ferries from Norway to Denmark and Germany run on maritime networks (MCC 901), and the
restriction goes on there. **One ferry ride turns EEA roaming off for the rest of the trip** until a person turns it
back on. The same happens on a dual-SIM phone with "Av (låst)" from design 94. The alternatives for the way back are
open decision D2:

- leave roaming on and use "bare Vibb" outside the EEA instead of the restriction. There is no manual step, but bytes
  leave the phone abroad: Vibb's own, and Android's network validation. That can start a day price (§4.4).
- a manual step in the PWA's words ("Slå på roaming igjen under Åpne for oppsett"). The PWA shows it whenever a
  two-modem phone with "Tillat i EU/EØS" reports `enabled: false`, **at home too**, so the parent can fix it before
  the next trip rather than at the border.

**The best alternative where ON is impossible:** the sink (question 2) enforces "only Vibb" outside the EEA while
roaming stays on. It is not a zero-byte guarantee. Vibb's line home keeps going unless it is made quiet too, and
Android's own network checks are not ours to stop.

## 2. Question 2: keeping only our connection

### 2.1 How the DNS filter routes today

`KidVpnService.startVpn` (launcher `server/KidVpnService.kt` ~l.170) sets `addAddress(192.0.2.1/32)`,
`addDnsServer(192.0.2.2)`, **one route** `addRoute(192.0.2.2, 32)`, MTU 32767, `setMetered(false)`, and no
`addDisallowedApplication`. Every app's DNS goes into the TUN, the launcher's own included (the server's name resolves
through the filter: `docs/notes/launcher/dns-and-vpn.md`). Every other packet matches no VPN route and falls through
to the physical network.

`AppEnforcer.applyVpnRestrictions` (~l.1427) sets always-on **without** lockdown on every `apply()`, and says why:
with lockdown and only that route, all apps lost the internet (found live, and measured again here, §2.3 c1). With the
filter off it clears always-on and stops the service.

tsnet is not a VPN. It runs in the launcher's process (`TsnetClient`), so it shares the launcher's uid. The network
watcher (`watchNetwork`, `registerBestMatchingNetworkCallback(INTERNET + NOT_VPN)`) lives in the service, so it runs
only while the service does.

### 2.2 The combined design: one service, three shapes

Android allows one VPN. The sink is another `Builder` shape of `KidVpnService`, not a second service:

| | Filter shape (today, one change) | Sink shape (new) | Pass shape (new) |
|---|---|---|---|
| Routes | `192.0.2.2/32` | `0.0.0.0/0`, `::/0` (plus a ULA `/128` address so v6 can be routed) | none (`allowFamily` for both families) |
| DNS server | `192.0.2.2`, filtered and relayed | `192.0.2.2`, answered `REFUSED` at once | none (the network's own) |
| Excluded apps | **the launcher (and so tsnet)** | the launcher (and so tsnet); the music app per D4 | the launcher |
| Read loop | DNS only | DNS -> `REFUSED`; TCP -> RST+ACK; anything else dropped | nothing arrives |
| Always-on lockdown | off | **on**, allow list = the excluded apps besides ours (the VPN owner is exempt by itself, `Vpn.setVpnForcedLocked` ~l.2044) | off |
| When | the filter is on, and Wi-Fi or Ethernet is the best non-VPN network or no sink reason holds | cellular is the best non-VPN network **and** a sink reason holds (§4.2) | the filter is off, a sink reason is configured, the best network is not cellular |

- **The launcher is excluded in every shape (rev. 2).** This is what the probe measured (`SinkVpn.java` excludes
  itself in the dns branch too). It keeps the launcher's uid outside the VPN for good. Otherwise, at a filter -> sink
  switch, the launcher's open sockets, made while its default network was the VPN and so marked with the VPN's netId,
  would be routed by netd's per-network rule into the VPN's table. The sink's `0.0.0.0/0` there would RST them. The
  SSE stream and a sync in flight would die at every switch. What it changes: the launcher's own lookups (the
  server's name, tsnet's) use the network's resolver instead of the filter. A dead filter upstream (the "DoT blocked"
  outage in `dns-and-vpn.md`) then no longer takes the launcher's sync with it. One case to check at build time: a
  server name that only the parent's own DNS server (design 36) resolves, i.e. split-horizon at home.
- **One owner for always-on (rev. 2).** `applyVpnRestrictions` today writes `setAlwaysOnVpnPackage(admin, pkg,
  false)` on every `apply()`. With the sink that would turn lockdown off on every sync and at every process start
  (before the service has decided), and each time other apps would get cellular until the next sink decision. A
  single function, `alwaysOnFor(plan)`, computes `(package or null, lockdown, allow list)` from the persisted shape
  decision. `apply()`, the service's shape switch and the cold start all call it and only it, and it writes only on a
  change. A pure test pins that `apply()` never writes lockdown off while the persisted shape is the sink.
- **The switch** is a new `establish()` on the same service. Android keeps the VPN's network (the probe's `net103`
  stayed through dns -> sink -> dns), closes the old fd, and the apps' default network never changes. Order: going to
  the sink, establish the sink shape first, then lockdown on. Going back, lockdown off first, then the filter (or pass)
  shape. If that `establish()` fails, the sink shape stays (no lockdown). On Wi-Fi that means other apps get RSTs until
  the retry (fails closed, never open).
- **The trigger** is the best-network callback the filter already holds (`watchNetwork`), plus a
  `TelephonyCallback.ServiceStateListener` for an MCC or roaming change without a network change. The sink decision is
  a pure function of the transport, the roaming state, the network's MCC, the modem count and the policy (§4.2).
- **The filter off (`vpn_filter_enabled = false`).** The service still runs while a sink reason is *configured*: the
  sink shape on cellular, the pass shape otherwise, so the watcher stays up to see Wi-Fi drop. It stops only when no
  sink reason can arise. Always-on is set while any shape runs. The pass shape changes nothing for apps: no routes,
  no DNS server, and both families allowed through. Without it, the sink would start only at the next `apply()`, and
  apps would use cellular freely until then.
- **`DISALLOW_CONFIG_VPN` follows the sink too.** `withFilterVpnLock` (`Hardening.kt`) sets it only while the filter
  is on. It now also holds while the persisted shape is the sink, so a filter-off phone can't switch lockdown off or
  disconnect the sink in Settings. The parent's explicit `disallow_config_vpn: false` still wins; the pause lifts it
  with the parent's sink reasons (§4.1).

### 2.3 Tests (measured, `q2*.log`)

Probe `me.vibb.probe.sink` (device owner, the "Vibb") and `me.vibb.probe.other` (the same APK under another package,
the "other app"). URLs: `https://1.1.1.1/cdn-cgi/trace` (an IP literal, so TCP is exercised) and
`https://www.google.com/generate_204` (a name, so DNS is exercised). The probe was excluded from its own VPN in both
shapes.

**(a) Ours works, theirs fails.** Cellular, sink shape:

| Sink's answer | Probe (excluded) | Other app, IP literal | Other app, by name |
|---|---|---|---|
| RST + `REFUSED` | 200 in 36-44 ms | `ConnectException` in **1 ms** | `UnknownHostException` in **3 ms** |
| drop (read, no answer) | 200 in 39 ms | `SocketTimeoutException` after **8 s** (the probe's timeout) | `UnknownHostException` after **10 s** |
| never read the TUN | 200 in 36 ms | timeout after **8 s** | the same |

A blackhole that never reads the TUN costs nothing in our process, but every blocked app hangs until its own timeout
and then retries. The RST answer makes apps fail at once and back off; it costs a read loop, which the filter has
anyway. **Use the RST.** It must answer every TCP segment but a RST, not only SYNs (the probe's `Pkt.tcpRst` does),
so a connection that somehow lands in the sink fails fast instead of hanging. Opportunistic Private DNS probes port
853 on `192.0.2.2`; the RST ends that at once too. UDP (QUIC included) is dropped; apps race TCP alongside it.

**(b) Across a Wi-Fi to cellular switch** (auto shape, no lockdown):

```
12:41:30.110 vpn best network lost 107                  (svc wifi disable)
12:41:30.170 vpn best network 102 CELL,ROAMING,VAL,metered
12:41:30.174 vpn shape sink (act=rst) established in 4 ms
12:41:38.055 fetch me.vibb.probe.other https://1.1.1.1/... FAILED in 2 ms: ConnectException
12:41:42.219 fetch me.vibb.probe.sink  https://1.1.1.1/... -> 200 262B in 35 ms
12:41:50.450 vpn best network 109 WIFI,NOT_ROAMING,VAL,unmetered   (svc wifi enable)
12:41:50.459 vpn shape dns (act=rst) established in 8 ms
12:41:59.010 fetch me.vibb.probe.other https://1.1.1.1/... -> 200 265B in 531 ms
```

Without lockdown there is a ~64 ms window after Wi-Fi is lost in which a new socket of another app can take
cellular. With lockdown on in the sink shape, a crash, a launcher update or a slow restart of the VPN fails closed
instead. AOSP: lockdown blocks every uid but the VPN owner and its allow list on non-VPN networks
(`Vpn.setVpnForcedLocked` ~l.2044). Not run: `am force-stop` of a device owner is refused, so the probe's VPN could
not be killed (c3).

**(c) Lockdown** (`setAlwaysOnVpnPackage(admin, pkg, lockdown = true, allowlist)`):

- c1: Wi-Fi, filter shape, lockdown on: the other app fails in 1-2 ms, by IP and by name; the probe works. Lockdown
  must never be on in the filter shape.
- c2: cellular, sink shape, lockdown on: the other app fails in 1 ms; the probe works in 39 ms.

**(d) Idle cost of the sink**, 10 minutes, cellular, sink + RST + lockdown, Google Play services and the stock apps
running, screen on, battery unplugged:

| | Value |
|---|---|
| Packets read from the TUN | 102 (31 DNS answered `REFUSED`, 64 RSTs, 7 dropped) |
| The probe's CPU time (`/proc/<pid>/stat` utime+stime) | 19 -> 20 ticks: **10 ms in 10 min** |
| Bytes on the cellular interface (`eth0`) | 594 B in, 1 536 B out in 9 min; then **0 B** in a clean 3.5 min window (polled netstats both ends) |

The read loop blocks in `read()`. It wakes only when a blocked app sends, and the radio is awake for that send in any
case. Blocked apps back off after the RST, so the count falls over time. In the launcher the RST path must not go
through the DNS path's admission limit and coroutine per packet (`dnsAdmit`, `dnsSlots`). It is a synchronous answer
in the read loop.

**(e) `NET_CAPABILITY_NOT_ROAMING` under the VPN** (roaming, both shapes): the other app's default network read
`VPN+CELL,ROAMING`, the excluded probe's `CELL,ROAMING`. `ConnectivityService.applyUnderlyingCapabilities` (~l.9455)
clears `NOT_ROAMING` on the VPN when any underlying network roams (~l.9494, ~l.9516). So `AppDownloadPlan`'s roaming
check and design 94's `data_active` read right in both shapes, excluded or not.

### 2.4 tsnet, the music app, Google's services, calls, SMS and MMS

- **tsnet** is in the launcher's process and uid, so `addDisallowedApplication(ownPackage)` excludes it with the
  launcher. A tailnet phone keeps its WireGuard path in every shape. Its SOCKS proxy is ours too.
- **The music app** (`me.vibb.music`, its own uid) is cut by the sink unless excluded (D4). It streams; "bare Vibb" is
  for cheap traffic, so the default should be: not excluded. If the parent wants it through, it goes in both
  `addDisallowedApplication` and the lockdown allow list.
- **The launcher's own bulk traffic is not covered by the sink.** Catalog APK downloads, the self-update, and logo
  uploads run in the launcher's uid. `AppDownloadPlan` already holds them while roaming, but at home with "Bare Vibb"
  or the kid's Av they would still pull megabytes over cellular. While the sink shape is wanted, the plan treats the
  phone as "Wi-Fi only" (`WAIT_WIFI`). Install Now's parent override still goes through, as today. Weather and
  calendar feeds come through the server (`/api/devices/weather`, `/calendar/feeds`), so they are part of the cheap
  line.
- **Google Play services is blocked too.** This is the point of the sink for Play's updates and backups, but it has
  two costs the parent should hear:
  - On a Google phone `LocationManager.NETWORK_PROVIDER` and the fused provider are Play services. `LocateCommands`
    uses both. Their Wi-Fi/cell lookups and the GNSS assistance downloads can't reach Google. A locate on cellular
    then falls back to raw GPS: a slow first fix, and none indoors.
  - Chat apps' pushes (FCM) stop on cellular. Calls and SMS don't.
  - [needs device test] on the Jelly Star: a locate (cold, indoors and out) with the sink on. If it is bad, the
    choice is D4's list (Play services through the sink costs its idle traffic, often MBs a day) or the words in the
    PWA. Emergency location over SMS (AML) is not affected; an HTTPS variant would be.
- **Calls and SMS** are not IP (CS on the emulator) or use their own IMS network, not the default network, and the
  sink routes only the default network's traffic. Under lockdown the system's own sockets are still exempt when they
  are bound to a network. netd marks a socket that a uid with the system network permission binds to a non-VPN
  network as protected from the VPN, and lockdown's "prohibit non-VPN" rule skips protected sockets. That is how
  IMS, MMS and emergency PDNs bind. Emergency calls go over CS or the emergency PDN in the modem and the phone process,
  never through an app's default network.
- **MMS** goes over the MMS APN through `com.android.mms.service` (the phone uid), which binds that network. Old
  reports say lockdown VPNs broke MMS on some builds.
- **Wi-Fi calling**: the filter shape (no lockdown) runs on Wi-Fi, as today. One corner: a Wi-Fi that is connected
  but not validated (a captive portal) leaves cellular as the best network. The sink and lockdown then run while
  IWLAN may use that Wi-Fi.
- [needs device test] on the Jelly Star, sink shape and lockdown on: an MMS in and out, a VoLTE call in and out, an
  SMS over IMS, and Wi-Fi calling on a captive Wi-Fi. If MMS fails, `com.android.phone` (the shared phone uid) goes on
  the lockdown allow list; it is never routed into our TUN anyway. No real 112 call is placed as a test.

### 2.5 The alternatives, briefly

- **`DevicePolicyManager.setPreferentialNetworkServiceConfigs`** (API 33): routes chosen apps to an enterprise slice
  (`NET_CAPABILITY_ENTERPRISE`), with an optional block of the default. It needs the carrier to offer an enterprise
  network slice (5G URSP) or the device's enterprise APN; consumer SIMs in Norway don't. Not usable.
- **Per-app background restriction** (`NetworkPolicyManager.setUidPolicy`, `POLICY_REJECT_METERED_BACKGROUND`): not a
  public API; needs `MANAGE_NETWORK_POLICY`. Not usable.
- **Suspending apps** (`setPackagesSuspended`): we already do it for other reasons. It stops the app being used, not
  its jobs or sync adapters, and it hides the app. Too blunt for "no data on cellular".
- **Roaming off, with SMS as the fallback channel**: a signed command SMS could carry "lock", "locate" and "pause"
  while there is no data (an HMAC over the command and a counter, with a key from enrollment).
  - For: it costs 0 B of data and a few øre per SMS abroad, works on a ship without data, and the launcher's SMS role
    (design 77) can receive it.
  - Against: it is one way unless the phone answers by SMS (more cost, and a kid's phone sending SMS abroad). It is
    slow, and it is a new attack surface (replay, spoofed senders, carrier filtering).
  - Worth having later as a last resort beside the restriction; it does not replace the sink.

## 3. Question 3: cheap server traffic

### 3.1 What was measured, and how

- The debug launcher built from `main` (`f4c1b196`, `assembleDebug`), on a wiped `jelly36`. It was enrolled by the
  emulator loop's steps (`docs/testing/emulator.md` §4) to a local server built from the same commit (`cargo build`).
  The device row and a QR-kind setup code for a test device were put in the server's SQLite directly instead of
  through the PWA. USB debugging was left allowed.
- The phone talked to `http://10.0.2.2:3102`, a counting TCP proxy in front of the server (`tap.py`, scratchpad). It
  logged every connection, every request line and the bytes each way, and kept the raw streams for the body sizes.
- Wi-Fi off, so cellular was the default network. After 5 minutes to settle, **60 minutes untouched** (the screen was
  on; nothing was opened). `dumpsys netstats --poll` then `dumpsys netstats detail` at both ends, summed per uid over
  the mobile idents.

### 3.2 Results

| Source | In 60 min | Per day |
|---|---|---|
| The launcher's uid on cellular (netstats) | 21 143 B in, 29 899 B out, 276 packets = **51.0 KB** | **1.22 MB** |
| - of which the server line (proxy, HTTP/1.1, no TLS; ~4 KB of TCP/IP headers added) | ~29 KB | ~0.69 MB |
| - of which the rest: the DNS filter's DoT upstream (`dns_upstream.in_use = provider`; the TUN carried ~20 queries from other apps in the hour) | ~22 KB | ~0.5 MB |

The server line, by request. There were two backstop syncs in the hour, at 13:15:44 and 13:49:03. Each used a new
connection, which OkHttp's pool closed 300 s later.

| Request | Each sync, up / down (bytes, headers included) |
|---|---|
| `GET /api/devices/policy` | 219 / 3 526 (body 3 304, **not compressed**) |
| `GET /api/devices/apps` | 217 / 221 |
| `POST /api/devices/status` | 7 840 / 239 (body 7 551: `installed_apps` 4 183 B for 19 apps, `capabilities` 567, `sms_state` 450, `call_state` 422, ...) |
| SSE keepalive | 8 B of chunked `:\n\n` every 240 s, 15 an hour |

- **The policy was byte-identical** in the three syncs after the first (3 304 B each). A 304 would have answered all
  of them.
- **The report changed in one field** between the idle syncs: `time_state.used_minutes` (the emulator's screen was on,
  so screen time counted). The emulator's battery sat at 100 % on charge. On a real phone in a pocket
  `battery.level` changes too, about every other sync, so both are heartbeat fields (§3.5).
- gzip (level 6) of the real bodies: policy 3 304 -> 1 306 B (**2.5x**), report 7 552 -> 2 958 B (**2.55x**), the
  first sync's report answer 1 291 -> 755 B.
- OkHttp 4.12 already sends `Accept-Encoding: gzip` and unpacks the answer itself, so a gzip'd answer needs no
  launcher change at all.
- Seen in `tap.jsonl`: right after enrollment **two** SSE streams were open (connections 2 and 5) until the first
  closed after 792 s. Harmless here (two keepalives), but a build-time check: a duplicate stream after enrollment or a
  network change doubles the keepalive cost and the server's `stream.open` count.

### 3.3 What https adds, and what gzip may carry

Handshakes measured on the VM with `openssl s_client` against Let's Encrypt's test hosts:

| Handshake | Read | Written |
|---|---|---|
| Full TLS 1.3, ECDSA chain (Caddy's default key; `valid-isrgrootx2`) | 2 668 B | 420 B (X25519) |
| Full TLS 1.3, RSA chain (`valid-isrgrootx1`) | 4 665 B | 1 663 B (with the hybrid PQ key share) |
| Resumed (PSK ticket) | 245 B | 739 B |

`createMdmApi` builds a new `OkHttpClient` for every sync (`MdmSyncWorker` ~l.106), with its own pool and its own TLS
context. The pool's connection is idle-closed after 5 minutes. So **every sync is a new TCP connection and a full
handshake**: ~3.1 KB of TLS records with the ECDSA chain, ~3.6 KB with its packets' headers. Resumption can't help
across clients, because each client's TLS context has its own session cache. The SSE stream has its own client.
Behind Caddy (HTTP/2 by ALPN) each keepalive is ~140 B on the wire: 3 B data + 9 B frame + 22 B TLS record + 52 B
TCP/IP, and the phone's 52 B ACK.

**Gzip and secrets (BREACH).** BREACH needs three things: a secret in a compressed answer, text the attacker chooses
in the same answer, and many observed requests.

- The policy has the first. `override_pin_hash`/`override_pin_salt` and the kid lock's `pin_hash`/`pin_salt` are
  there, and the PINs are short, so a leaked hash is a leaked PIN.
- The second: no attacker-chosen text in the policy was found (contact requests carry ids and states only, and names
  and rules are the parent's).
- The third is weak: a 30-minute sync, and a nudge only on the parent's changes.

So in practice it does not apply. But the admin listener already keeps every secret-carrying answer uncompressed as
defence in depth (`admin_auth::Compressible`: the device API, the Child PIN, the setup QR). The device side follows the
same rule:

- **No gzip** on `GET /api/devices/policy`, the token answers (`music/spotify/token`, `music/storytel/token`) or
  `enroll`. The 304 (§3.5) already removes most of what a gzip'd policy would save.
- **Gzip** on answers without secrets: `apps`, `dns-blocklist`, the music library and listings, weather, the calendar
  feeds. A predicate marks them, as on the admin side. Never on the SSE route or on images and audio.
- **The status report upload** may be gzip'd (§3.5). It holds no secret (the token is a header), and the server never
  echoes it back.

### 3.4 MB/day, before and after

Per day at 48 syncs (the 30-minute backstop), https via Caddy, the emulator's body sizes. A configured Jelly Star has
more apps (~220 B each in the report) and a fuller policy; its column is an estimate (45 apps, an 8 KB policy).

| Part | Today (emulator size) | Today (Jelly Star, est.) |
|---|---|---|
| Policy GET (down; ~10 KB/day up on top) | 169 KB | 390 KB |
| Status POST + answer | 388 KB | 660 KB |
| Apps GET | 21 KB | 21 KB |
| TCP/IP of the syncs | 62 KB | 75 KB |
| TLS handshake per sync | 173 KB | 173 KB |
| SSE keepalives | 50 KB | 50 KB |
| **Server line** | **~0.86 MB** | **~1.37 MB** |
| DNS filter upstream (other apps' lookups) | ~0.5 MB | depends on the apps |
| tsnet's own traffic (control, DERP, disco) on a tailnet phone | not measured | not measured |

Each saving alone (emulator size):

| Change | Saves per day | Where |
|---|---|---|
| ETag + 304 for an unchanged policy | ~160 KB | both (§3.5) |
| One shared client for the server API (connection reuse; with Caddy's HTTP/2 the sync rides the SSE's connection; on HTTP/1.1 a resumed handshake) | ~190 KB | launcher (§3.5) |
| Report on change: a heartbeat of ~0.3-0.6 KB when only the heartbeat fields moved | ~350 KB | both (§3.5) |
| gzip the full status report | ~220 KB (most of it overlaps the heartbeat's) | both: `Content-Encoding: gzip` only when the policy says `status_gzip: true` (an older server never gets one) |
| gzip the answers without secrets | ~0 on the idle line; real on the blocklist and music downloads | server only |
| ~~gzip the policy answer~~ | ~~102 KB~~ | dropped in rev. 2 (§3.3) |
| ~~A 120-minute backstop while roaming or in "bare Vibb"~~ | ~~75 % of the sync parts~~ | dropped in rev. 2: the server shows a phone Offline after 60 min without a request (`streams.rs` `ONLINE_WINDOW_MINUTES`, sized for the 30-min inexact alarm delivered up to 1.75 x late). After the other changes it would save ~25 KB/day |

**Recommended combination**: shared client + 304 + heartbeat (with gzip on the full report). Per idle sync that is
then ~0.3-0.6 KB up, ~0.25 KB down and ~8 packets: about **0.15-0.2 MB/day** for the server line (~50 KB of syncs,
50 KB of keepalives, 30-80 KB of real changes: a changed policy goes out uncompressed), down from ~0.86 (emulator
size) or ~1.4 (Jelly Star). The keepalives become the largest part.

The DNS filter's own upstream (~0.5 MB/day here) is the next biggest item on an idle phone. Its DoT connections are
evicted on every network change and close after a short idle on the resolver's side. It is worth its own look (D7). In
the sink shape it is zero (DNS is answered locally).

### 3.5 The traffic changes, specified

**ETag and 304 on `GET /api/devices/policy`.**

- Server: the ETag is a hash of the **exact bytes served**, computed after `build_policy` and its `pop_command`. A
  popped command is in the body, so a body with a new command never answers 304. Time-dependent content is in the bytes
  too, so the hash follows it: `served_rule_rows(now)`, a lift's `until_ms`, a rule that has ended. Correctness needs
  nothing else. It is a weak ETag (`W/"..."`) with `no-store` kept. `If-None-Match` equal to it gives `304` with no
  body. The authenticated request still counts as the phone's last access, and the pop still happens; the body only
  repeats one the phone already holds.
- Launcher: the kept body is **not** the policy cache. `kidModePolicy` is re-encoded by the launcher's DTOs (unknown
  keys dropped), and `PinVault` writes a redacted copy without the PIN hashes. So the launcher keeps:
  - the **raw 200 body**, in `SealedPrefs` (it holds the PIN hashes), with its ETag, the launcher's versionCode and
    the server's base URL;
  - it keeps it only when the policy was **accepted** (`judgeFresh` ACCEPT; never a REJECT_SUSPECT or an undecodable
    one);
  - it drops it on a versionCode change (a newer launcher must see keys an older DTO dropped), a server URL or token
    change, a re-enrollment, or a corrupt cache;
  - it sends `If-None-Match` only while all four match.
- A 304 is applied exactly as if that raw body had just arrived. The same decode, `judgeFresh`, `apply()`, PIN vault
  and command dedupe run, and it counts as a fresh policy everywhere a fresh fetch counts. The API method returns
  `Response<PolicyResponse>` (Retrofit treats 304 as unsuccessful). Design 76's certificate check reads the 304's
  handshake as it reads a 200's.

**The heartbeat report** (`status_heartbeat_v1`). The server stores every report as a full `device_status` row and
reads it in many places, so the heartbeat is defined by what the server does with it:

- Launcher: it hashes its full report body **without the heartbeat fields** (`battery`, `time_state`, `lock_reason`,
  and design 94/95's `data_roaming` and `sink`). If the hash equals the last one the server acknowledged, and the last
  full report is under 6 h old, it sends `{"heartbeat": true, "hash": "...", <the heartbeat fields>}`. Otherwise it
  sends the full report with `"hash"`. Any other field changing therefore sends a full report: an app installed, a
  call state, a zone arrival, a capability.
- Server: on a heartbeat it checks the hash against the one stored with the latest row since enrollment
  (`report_hash`, a new column).
  - On a match it **inserts a copy of that row** with the heartbeat's fields and a new `reported_at` (`INSERT ...
    SELECT`). The battery history and the time badges keep their rows, and retention prunes as today. It updates
    `devices.last_seen_at`, runs the heartbeat fields' own checks (time state, battery low), and runs **none** of the
    previous-row diffs (security log, new or foreign apps, unseen boots, `app_updated`, the parent's events). It
    answers as for a full report (`wanted_logos` from the stored apps).
  - With no match, no row, or a row from before the latest enrollment, it answers `{"full_report": true}` and stores
    nothing. The phone sends the full report at once. A server upgrade that wants new fields can answer the same.
- A contract file `server/testdata/status_heartbeat.json`: the heartbeat body, the hash rule's vectors (which fields
  are out), and each answer. Both sides run it, as the other contracts.

**gzip'd report uploads.** The server adds `tower_http::decompression::RequestDecompressionLayer` (gzip only;
another `Content-Encoding` is refused with 415) on the authenticated routes, inside `require_device_token`, so an
unauthenticated body is never inflated. The limit holds after decompression: axum's `DefaultBodyLimit` is applied by
the extractor (`Json`/`Bytes`) to the body it reads, and after the layer that body is the decompressed stream. So
`DEVICE_BODY_LIMIT` (512 KiB) and the per-route limits count inflated bytes, and a gzip bomb stops at the limit with a
413. A server test posts a gzip body that inflates past the limit. `intake_limits` runs on the decompressed report as
today. The launcher gzips only when the policy says `status_gzip: true`.

**One shared client.** One base `OkHttpClient` per server path (base URL, `ServerPath`, pinning, tsnet's proxy),
rebuilt only when one of those changes. The sync's API client, the SSE client and `PowerEvents`' prepared client are
`newBuilder()` children with their own timeouts. They then share the connection pool and the TLS context:

- With HTTP/2 (Caddy) the sync's requests ride the SSE stream's connection: no handshake at all.
- On HTTP/1.1 (a tailnet's plain http, or a proxy without h2) a sync still opens a connection, but resumes TLS
  (~1 KB instead of ~3.6 KB).
- A dead shared connection would make a sync wait for its read timeout. A `pingInterval` on the base client (or
  evicting the pool when the SSE stream reconnects) covers that.
- Note that `dns-and-vpn.md`'s DNS clients must stay separate builders (never `newBuilder()` of these: they must not
  carry tsnet's proxy).

## 4. The recommended whole

### 4.1 The choices, and who can flip them

In design 94's Advanced › **Mobilnett** group (one form, autosave; the anchor becomes `#mobile-network`, §6):

| Row | Options | Default | Who |
|---|---|---|---|
| Dataroaming | **Av (låst)** / **Tillat i EU/EØS** / **Telefonens valg** | Av (låst) | the parent (PWA) |
| Mobildata for apper | **På** / **Bare Vibb** | På | the parent (PWA) |
| Barnet kan slå av mobildata | a switch: shows a **Mobildata** row in Quick Controls (`QUICK_CONTROL_MOBILE_DATA = 32`, free today) | off | the parent turns it on; then the kid flips it on the phone |

- The kid's row is "Mobildata: På / Av", and **Av means "bare Vibb"** (the sink). The kid can't cut our line, and the
  row says so in its ⓘ ("Vibb One holder kontakten med foreldrene"). When the parent has picked Bare Vibb, the kid's
  row shows Av and is locked. The kid's Av counts only while the parent's tick is on. Turning the tick off ends it.
- "Åpne for oppsett" (the pause) and the parent override lift the parent's Bare Vibb and the roaming lock, as every
  restriction (the standing rule), and with them the sink's `DISALLOW_CONFIG_VPN`. They do not lift the kid's own Av:
  it is the kid's choice, and the kid can undo it in Quick Controls anyway.
- The ⓘ under Bare Vibb names what stops besides apps' data: chat apps' notifications, Google's network location (a
  locate can be slow, §2.4), and Play's updates. Calls and SMS go on.
- "Tillat i EU/EØS" is fail-closed for an older launcher. Design 94's `dataRoamingLocked` treats a value it doesn't
  know as locked, so an older launcher reads `"eea"` as "Av (låst)".

### 4.2 How they combine on the phone (one pure rule)

```kotlin
data class NetState(
    val cellular: Boolean,             // the best non-VPN network is cellular
    val roaming: Boolean,              // isNetworkRoaming() on the default data SIM
    val mcc: String?,                  // the network's MCC, null when unknown
    val simMcc: String?,               // the SIM's MCC (getSimOperator().take(3))
    val modems: Int,                   // getActiveModemCount()
    val roamingDataActive: Boolean,    // design 94's data_active
)

/** ROAMING_LOCK = DISALLOW_DATA_ROAMING (zero bytes); SINK = only Vibb; QUIET = Vibb's own line stops too. */
fun connectivityPlan(policy: DataPolicy, kidMobileOff: Boolean, net: NetState, lifted: Boolean, last: Plan?): Plan {
    val domestic = net.roaming && net.mcc != null && net.mcc == net.simMcc        // national roaming: home (§6 item 4)
    val eeaRoaming = net.roaming && !domestic && net.mcc != null && net.mcc != "901" && net.mcc in EEA_MCC
    val knownOutside = net.roaming && !domestic && net.mcc != null && (net.mcc == "901" || net.mcc !in EEA_MCC)
    val roamingLock = !lifted && when (policy.dataRoaming) {
        "phone", null -> false
        "eea" -> if (net.modems <= 1) !eeaRoaming                                  // fail-closed: on everywhere else
                 else if (knownOutside) true
                 else if (net.roaming && net.mcc == null) last?.roamingLock ?: false // unknown MCC: keep the decision
                 else false
        else -> !domestic                         // "off", and unknown values: locked (design 94), not in national roaming
    }
    val lingering = roamingLock && net.roamingDataActive                          // design 94 §2.4.1
    val sink = net.cellular && (
        (!lifted && policy.appsMobile == "vibb") || (policy.kidMobileSwitch && kidMobileOff) || lingering
    )
    // Vibb's own line stops too on a lingering session outside the EEA (zero bytes, §4.3).
    val quiet = lingering && (knownOutside || net.mcc == null)
    // One modem, "Tillat i EU/EØS", on an EEA network: clear, wait, "1", read back, kick if cellular would be default.
    val roamingOn = !lifted && policy.dataRoaming == "eea" && eeaRoaming && net.modems <= 1
    return Plan(roamingLock, sink, quiet, roamingOn)
}
```

The sketch is the shape, not the code. Notes on it:

- `EEA_MCC` holds the EU 27 plus Iceland, Liechtenstein and Norway, and the French outermost regions' own MCCs (340,
  647, 742; EU territory). 901 never counts as EEA, and it decides before the country (design 94 §2.5).
- The network's MCC is `getNetworkOperator().take(3)` on the default data SIM, and the SIM's is
  `getSimOperator().take(3)`. Neither needs a permission.
- On one modem, "Av (låst)" with a domestic roaming network can't simply skip the lock. Android refuses domestic
  roaming data while `data_roaming=0`, so that case also needs `roamingOn`. That, and whether national roaming reads
  as roaming at all on our SIMs, is §6 item 4's device test.
- The plan is persisted with every decision. `last` is how an unknown MCC keeps the previous one, and `alwaysOnFor`
  (§2.2) reads the persisted sink.

### 4.3 How our connection stays up, and when it goes quiet on purpose

- The sink never covers the launcher (excluded by package, so tsnet too, in every shape). In the measured switch the
  probe's HTTPS never failed.
- The roaming lock does cut it. That is its purpose (zero bytes abroad). The phone can't report "before" the lock: it
  learns it is outside the EEA only once it is on that network, and any report would then go over it. The last report
  from the EEA or home stands. The PWA shows the last `data_roaming` it had ("Ingen data utenfor EU/EØS - kontakt
  igjen på Wi-Fi eller i EU/EØS"), and the phone reports at once on the next Wi-Fi or EEA network.
- **Quiet** (rev. 2): while a roaming session outlives the lock on a non-EEA or 901 network (`plan.quiet`), the
  launcher's own line stops as well. It closes the SSE stream, skips the backstop, makes no tsnet connection and does
  no download. The sink cuts the other apps. Only Android's own traffic is left, until Android tears the session
  down. Inside the EEA (design 94's "Av" in Sweden, say) a lingering session is cheap, so Vibb's line goes on and the
  report shows `locked` with `data_active`.
- On Wi-Fi nothing is cut: the filter shape is today's.
- The sink closes design 94's §2.4.1 gap for every app but ours, and quiet closes it for ours outside the EEA.

### 4.4 Costs abroad, plainly

Norwegian plans charge roaming outside the EU/EEA either per MB or as a day price that starts with the first use of
data that day. Even "bare Vibb" uses a few KB a day (§3), and Android's own network checks use some more. **Only the
roaming lock is zero bytes**, and only when it was on before the phone attached to the non-EEA network. That is the
one-modem default under "Tillat i EU/EØS". On two modems the lock comes a moment after the attach, so Android's own
validation probe may already have gone out. That is why "Tillat i EU/EØS" locks outside the EEA instead of sinking.
The PWA's footer says this, and says that the dual-SIM case is "nesten null".

## 5. The traffic plan

- **Today**: ~0.9 MB/day of server line on the emulator's small phone, and ~1.4 MB/day estimated on a configured Jelly
  Star. On top come ~0.5 MB/day of the DNS filter's upstream and, on a tailnet phone, tsnet's own (not measured). The
  user's 1-3 MB/day estimate holds (measured: 1.2 MB/day for the launcher's uid on the emulator, over plain http).
- **Step 1, both**: the 304 on the policy and the shared client -> about -0.35 MB/day.
- **Step 2, both**: heartbeat reports with gzip'd full reports -> about 0.15-0.2 MB/day in all. The server's gzip of
  the non-secret answers can ride along in either step; it does not move the idle line.
- **Not changed**: the SSE keepalive stays at 240 s (Caddy's `write_idle` and carriers' NAT timers; DEPLOY.md), and so
  does the 30-minute backstop (the server's Online window, §3.4). The keepalives, ~50 KB/day, become the largest part.

## 6. How design 94 changes

1. **The policy value set** grows: `data_roaming` in `off | eea | phone`. Put `eea` in 94's migration `CHECK` now
   (`CHECK (data_roaming IN ('off','eea','phone'))`), so 95 needs no table rebuild. The PWA offers it only once a
   phone claims `data_roaming_v2`. 94's section anchor `#data-roaming` becomes `#mobile-network` before 94 is built,
   so the group's redirect and its page test are written once (the pinned redirect table in `tests::device_pages`).
2. **§2.4.1 (the running session) and Q4** get a real answer: option (d), the sink while `locked && data_active`,
   plus quiet outside the EEA (§4.3). It cuts every app but Vibb at once, and Vibb too outside the EEA; Android ends
   the session later as before. Q4's (b) (a reboot button) is no longer needed. 94's warning and advice stay for a
   launcher without 95-S.
3. **The report** gains:
   - `modems` (`getActiveModemCount()`), so the PWA knows whether "Tillat i EU/EØS" can turn roaming back on by itself
     (one modem) or needs a person (two);
   - `domestic` (roaming on the SIM's own MCC; no country leaves the phone);
   - `sink` (`"off" | "parent" | "kid" | "roaming"`) and `quiet`, so the PWA can say why apps have no data.
   `network` already carries the MCC while roaming.
4. **National roaming, a risk that becomes a rule.** `ServiceState.getDataRoaming()` is true for domestic roaming,
   and `DataNetworkController` refuses roaming data for it while `data_roaming=0` (§1.3). A Norwegian SIM on another
   Norwegian network may read `roaming = true` with MCC 242, unless its carrier config lists the partner as
   non-roaming or the operator names match. An example is Ice in a national-roaming area. Then:
   - 94's "Av (låst)" cuts data **at home**.
   - On one modem, 95's rule fixes it (domestic is home, `roamingOn` there).
   - On two modems nothing automatic can: the per-SIM key needs a person, and the lock wipes it on every trip.

   [needs device test] **before 95-R and before 94's default goes to existing phones**: an Ice SIM (or any SIM whose
   operator uses national roaming) in a national-roaming area, reading `isNetworkRoaming()`, `getNetworkOperator()`
   and `dumpsys telephony`'s data roaming type. Until then 94's report shows it in the field: `roaming: true` with
   `network` starting `242`. The PWA then says "Nasjonal roaming: Av (låst) stenger mobildata her".
5. 94's `dataRoamingLocked(value, overrideActive)` is replaced by `connectivityPlan`'s `roamingLock` in 95-R. 94 can
   be built as it stands, with the CHECK widened and the anchor renamed.

## 7. Risks

- **Dual-SIM way back** (§1.4): on the Jelly Star roaming stays off after a trip outside the EEA, or one ferry ride,
  until a person turns it on. The PWA says so, at home too, and D2 has the alternative.
- **Lockdown and MMS, VoLTE, Wi-Fi calling** (§2.4): [needs device test].
- **Bare Vibb and location** (§2.4): Google's network location and GNSS assistance stop; [needs device test].
- **The sink is not zero bytes**: Vibb's line home (outside quiet) and Android's own network validation still use
  cellular.
- **The RST is ours to get right**: a wrong checksum means apps hang instead of failing fast. The probe's code is in
  the scratchpad, and the launcher's version gets a unit test with captured packets, v4 and v6.
- **IPv6-only networks / 464XLAT**: the sink routes `::/0` too; tested only on the emulator's IPv4 cellular. Today's
  filter shape has no IPv6 address, route or DNS server, and Android then blocks IPv6 for the VPN's apps (the `::/0
  unreachable` that `AppEnforcer`'s note records). That is unchanged, and the launcher is now outside it.
- **A VPN that dies in the sink shape with lockdown on** leaves every app but Vibb offline. On cellular that is the
  sink's state anyway. But lockdown stays until the launcher runs again, so after a crash loop it holds on Wi-Fi too,
  and the pause from the phone can't lift it (the launcher is the one that lifts). The filter shape keeps lockdown off,
  so a dead filter never takes the internet with it (as today).
- **Two writers of always-on** (§2.2): the single owner and its test are part of 95-S, not a follow-up.
- **A shape switch with open connections**: the launcher is outside the VPN in every shape, so none of its sockets
  move. Build-time emulator check: an SSE stream open across dns -> sink -> dns with the real launcher.
- **Carriers' roaming flags** (design 94 §10) and national roaming (§6 item 4).

## 8. Build steps and sizes

1. **95-T1 (the policy and the client), both directories, S-M.**
   - Server: the policy's ETag/304 and a page-less test of the pop-then-hash order. Gzip on the non-secret device
     answers (§3.3, a `Compressible`-style predicate; never the policy, token answers, enroll, SSE, images or audio).
   - Launcher: the sealed raw body with its ETag, versionCode and URL; `If-None-Match`; a 304 applied as a 200; one
     base client per server path, with the SSE, sync and power clients as `newBuilder()` children.
   - No contract file: the 304 is plain HTTP, and each side tests its own half.
2. **95-T2 (the heartbeat), both directories, M.**
   - Server: `report_hash`, the heartbeat path (copy the row, no diffs, `full_report`),
     `RequestDecompressionLayer` with a past-the-limit test, the policy flag `status_gzip`.
   - Launcher: `status_heartbeat_v1`, the hash without the heartbeat fields, the 6 h full report, gzip under the
     flag.
   - The contract file `server/testdata/status_heartbeat.json`, byte-identical in the launcher's test resources, and
     added to `launcher.yml`'s and `server-ci.yml`'s path lists.
3. **95-S (the sink), launcher L, server S.**
   - Launcher: the sink and pass shapes in `KidVpnService`; the launcher excluded in every shape; the RST/REFUSED
     answers outside the DNS path's limits; the shape switch; `alwaysOnFor` as the single owner (and
     `applyVpnRestrictions` rewritten onto it); `DISALLOW_CONFIG_VPN` with the sink; `AppDownloadPlan`'s Wi-Fi-only
     under the sink; `connectivityPlan` without the roaming parts.
   - Server: the policy key `apps_mobile` (`"on" | "vibb"`), the Quick Controls bit and row, the report's `sink`, and
     the PWA rows and words.
   - A contract file `server/testdata/connectivity.json` (policy rows, report, plan vectors), byte-identical in the
     launcher's test resources and in both CI path lists.
   - Emulator checks as §2.3, plus an SSE stream across the switches. The Jelly Star checks of §2.4 (MMS, VoLTE,
     locate).
4. **95-R (Tillat i EU/EØS), both directories, M.** After 94 **and** 95-S (the lingering session needs the sink), and
   after §6 item 4's device test.
   - `EEA_MCC`, the MCC watch (the best-network callback plus `TelephonyCallback.ServiceStateListener` for an MCC
     change without a network change), the modem-dependent lock rule with the persisted last plan.
   - The one-modem ON path: clear, wait for `ACTION_USER_RESTRICTIONS_CHANGED`, `setGlobalSetting("1")`, read back,
     and the kick only when cellular would be the default. At most once per network change.
   - Quiet; `data_roaming_v2`; the report's `modems`, `domestic` and `quiet`; the PWA's third option and words (the
     dual-SIM "turn it on again" line at home too).

95-T1 and 95-S are independent and can run in parallel worktrees. 95-T2 follows 95-T1 (the shared client).

## 9. Open decisions for the user

- **D1. The traffic changes, and in which order.** Recommended: the 304 and the shared client first (S-M, both), then
  the heartbeat report with gzip (M, both). The heartbeat changes what "last report" means in the PWA; the summary's
  "siden" line keeps working, since a heartbeat is a report row. **Changed in rev. 2:** no gzip of the policy (it
  carries the PIN hashes; §3.3), and no slower backstop abroad (the PWA would show the phone Offline; §3.4).
- **D2. The way back into the EEA on a dual-SIM phone.** (a) The restriction outside the EEA (zero bytes or nearly,
  §4.4), and the parent turns roaming on again during "Åpne for oppsett". The PWA asks for it at home too, as soon as
  the phone reports roaming off. Recommended: it is the only near-zero-byte choice. (b) Roaming stays on, and outside
  the EEA the phone runs "bare Vibb" with Vibb quiet. There is no manual step, but Android's own bytes go out on every
  non-EEA network, and that can start a day price. Both have the ferry case: under (a) one ride turns EEA roaming off
  until a person acts; under (b) the ship's network sees Android's bytes at maritime prices.
- **D3. Does the pause lift the parent's "Bare Vibb"?** Recommended yes (the standing rule), as it lifts the roaming
  lock.
- **D4. Does "Vibb" include the music app, or Google Play services?** Recommended no for both: "bare Vibb" is for
  cheap traffic, the music app streams, and Play services is the heaviest idle app. A per-app row is possible later
  (the exclusion and the lockdown allow list take a list). Revisit Play services if the locate test (§2.4) is bad.
- **D5. EEA list edges.** Switzerland and the UK are not EEA; some Norwegian plans include them. Recommended: the
  fixed EEA list now, with CH/GB as a later per-phone tick if asked.
- **D6. A kid's Mobildata switch**: in Quick Controls behind the parent's tick (recommended), or not at all.
- **D7. The DNS filter's upstream cost** (~0.5 MB/day on the emulator, from other apps' lookups): look at keeping DoT
  connections longer or a larger answer cache, as its own small item. Not part of this design's steps.
- **D8. "Bare Vibb" on a schedule** (nights, school hours) through the time rules: possible later with the same plan
  input; not asked for. Recommended: not now.
