# Design 95: connectivity control as one whole - roaming by region, "bare Vibb", and a cheap line home

Status: research and test note, revision 1, 2026-11-13. No code is built. It extends design 94 (data roaming,
revision 2, approved, not built; branch `design-94`) and changes it in three places (§6). Everything marked
"measured" was run on the emulator (`jelly36`, Android 16, Google Play image) with throwaway probes and the real
debug launcher from `main` (`f4c1b196`) against a local server built from the same commit. The probes and logs stayed
in the scratchpad; nothing here was installed on a phone.

The user (2026-11-13): *"lots of freedom to control whether the phone has a connection or not, while the phone can
ideally always reach the server cheaply."*

## 0. Verdict

1. **Can a device owner turn data roaming ON? Yes on a single-modem phone, no on a dual-SIM phone.** (§1)
   - `DevicePolicyManager.setGlobalSetting(admin, Settings.Global.DATA_ROAMING, "1")` is allowed (DPMS
     `GLOBAL_SETTINGS_ALLOWLIST`, exact names), and it is refused silently while `DISALLOW_DATA_ROAMING` is set.
   - Telephony reads the **bare** key only when `getSimCount() > 1` is false. A dual-SIM phone (the Jelly Star has two
     slots) reads `data_roaming<subId>`, and the DO gets `SecurityException: device owners cannot update data_roaming1`
     (measured). So the bare write does nothing there.
   - Telephony does not watch the setting. Writing `1` brings nothing up by itself (measured: 44 s with no data, until
     some app filed a new network request). **Our own `requestNetwork(TRANSPORT_CELLULAR, INTERNET)` right after the
     write brought roaming data up in 119 ms** (measured), because a new request is evaluated at once and reads the
     setting fresh.
   - Writing `0`, or setting the restriction, does **not** end a running roaming session (measured, both; design 94
     §2.4.1 confirmed).
2. **Keeping only our connection works, as a second shape of the one `KidVpnService`.** (§2)
   - "Sink" shape: `0.0.0.0/0` and `::/0` into the TUN, the launcher excluded with `addDisallowedApplication`, DNS
     answered `REFUSED`, TCP answered with a RST, the rest dropped. Measured: the excluded probe's HTTPS worked
     (15-44 ms) while the other app failed in 1-3 ms; without the RST it hung 8-10 s per attempt.
   - The shape follows the network: cellular gives sink, Wi-Fi gives today's DNS-only filter. A Wi-Fi to cellular
     switch re-established the TUN in 4-12 ms, 64 ms after Wi-Fi was lost, and the VPN kept its network id, so apps
     never saw the VPN go away (measured).
   - Idle cost: 102 packets and **10 ms of CPU in 10 minutes** with Google Play services running (measured).
   - Lockdown must follow the shape: with lockdown on, the DNS-only shape breaks every other app (measured, as
     `AppEnforcer` already says). In the sink shape lockdown is what we want.
3. **The idle line home costs about 0.9 MB/day today, and about 0.15 MB/day with the recommended changes.** (§3)
   Measured: the real launcher used **51 KB in an idle hour on cellular (1.2 MB/day)** in netstats; the server line
   was 29 KB of that (two syncs at 11 KB each, 15 SSE keepalives), the rest is the DNS filter's own DoT upstream for
   other apps' lookups. Over https (the user's setup) each sync also pays a full TLS handshake, because every sync
   builds a new `OkHttpClient`: the server line is then ~0.86 MB/day on the emulator's small phone, and an estimated
   ~1.4 MB/day on a configured Jelly Star. The policy came back byte-identical in all three idle syncs, the report
   changed in one field, and neither answer is compressed (the device router has no `CompressionLayer`; only the admin
   router does). A 304, a heartbeat report, gzip and one shared client cut the line about six-fold.

**The recommended whole (§4):** three parent choices in the PWA's Mobilnett group - Dataroaming (Av / Tillat i EU/EØS /
Telefonens valg), Mobildata for apper (På / Bare Vibb), and whether the kid has a Mobildata switch in Quick Controls -
all enforced by two levers on the phone: the roaming restriction (zero bytes) and the sink (only Vibb's bytes). Our own
connection is never cut by any of them, except the roaming restriction outside the EEA, which is the point of it.

## 1. Question 1: the EU/EEA roaming choice

### 1.1 What was tested, and how

The emulator's console fakes roaming only when the network's MCC differs from the SIM's: `ServiceStateTracker
.updateRoamingState` (opt/telephony 15 ~l.2674) turns a roaming registration back into home when
`isSameNamedOperators` holds (same MCC and the SIM's name equals the network's). `gsm data roaming` alone therefore
reads `roaming=false` on the stock AVD. What worked:

- A private copy of the system image's `data/` (the AVD started with `-sysdir` pointing at it; the SDK's image was not
  touched) with the modem simulator's SIM changed to a Norwegian one: `iccprofile_for_sim0.xml` `CIMI 242010000000000`
  and `EF_AD` MNC length 2, so the SIM reads `24201` (Telenor) while the network stays `311740` in the US.
- `adb emu gsm data roaming` + `gsm voice roaming` then gives `isNetworkRoaming() = true`, `getNetworkOperator() =
  311740`, `getNetworkCountryIso() = "us"`.
- The SIM had no APN in the image's list, so the probe (device owner) added one with
  `DevicePolicyManager.addOverrideApn` + `setOverrideApnsEnabled(true)`. Then roaming data came up:
  `active=CELL,ROAMING,VAL,metered` (no `NET_CAPABILITY_NOT_ROAMING`).
- The probe `me.vibb.probe.sink` (debug-signed, its own package) was the device owner. Commands went in by
  `am broadcast`; the radio log's `DNC-0` lines show each data evaluation and its disallowed reasons.

The emulator has one modem (`getActiveModemCount() = 1`), so the dual-SIM path is read from AOSP and checked by the
allow list's answer, not run.

### 1.2 Results (measured, `q1.log`)

| Step | What the probe did | Result |
|---|---|---|
| A | Roaming data up; `setGlobalSetting(DATA_ROAMING, "0")` | `isDataRoamingEnabled() = false`, data **stays up** (20 s+) |
| B | Flip `gsm data home` then `roaming` | Re-evaluation of existing networks: torn down, `ROAMING_DISABLED` |
| C | Roaming, down; `setGlobalSetting(DATA_ROAMING, "1")`; no other trigger | Nothing for 44 s. Then `NEW_REQUEST` from an unrelated app (Photos, uid 10169) brought it up |
| D | Same, then at once `requestNetwork(TRANSPORT_CELLULAR + INTERNET, 8 s)` | `onAvailable` after **119 ms**, `DNC-0: Data evaluation: evaluation reason:NEW_REQUEST, Data allowed reason: NORMAL`; data stays the default after the request is released |
| E1 | Roaming data up; `addUserRestriction(DISALLOW_DATA_ROAMING)` | Android writes `data_roaming=0`, data **stays up** (20 s+) |
| E2 | Restricted; `setGlobalSetting(DATA_ROAMING, "1")`; also `adb shell settings put` | Both refused silently; the value stays `0` |
| E3 | Restriction cleared | Nothing written; the value stays `0` |
| F | `setGlobalSetting("data_roaming1", "0")` | `SecurityException: Permission denial: device owners cannot update data_roaming1` |

Also seen: a target-33 app reading `Settings.Global.getString("data_roaming")` gets `SecurityException: Settings key:
<data_roaming> is only readable to apps with targetSdkVersion lower than or equal to: 32`. The launcher reads
`TelephonyManager.isDataRoamingEnabled()` instead (design 94 §2.5 already does).

### 1.3 AOSP (android15-release)

- `DevicePolicyManagerService.setGlobalSetting` (~l.14987): device owner only, the name must be in
  `GLOBAL_SETTINGS_ALLOWLIST` (~l.702; `DATA_ROAMING` at ~l.707), written with a clean identity through
  `Settings.Global.putString`. The restriction check in SettingsProvider still applies (design 94 §2.3), hence E2.
- `GlobalSettingsHelper.getSettingName` (~l.86): `data_roaming<subId>` when `TelephonyManager.getSimCount() > 1`,
  and `getSimCount()` is `getPhoneCount()`, the active modem count. A dual-SIM phone in dual standby reads the per-SIM
  key even with one SIM in it.
- `DataSettingsManager.isDataRoamingEnabled` (~l.560) reads the setting fresh each time. The manager observes only
  `DEVICE_PROVISIONED` and `DEVICE_PROVISIONING_MOBILE_DATA_ENABLED` (~l.294), so a write fires no callback.
- `DataNetworkController.evaluateNetworkRequest` (~l.1661): `getDataRoaming() && !isDataRoamingEnabled()` adds
  `ROAMING_DISABLED`. A new request (`onAddNetworkRequest` ~l.1301, reason `NEW_REQUEST`) is evaluated at once - the
  kick in step D. Other re-evaluations of unsatisfied requests: data enabled, data profiles, a TAC change (delayed),
  service state, the end of a call, SIM loaded, a retry.
- `TelephonyManager.setDataRoamingEnabled` needs `MODIFY_PHONE_STATE` or carrier privileges (~l.11908); a device owner
  has neither. There is no other public API.

### 1.4 What this means for "Tillat i EU/EØS"

| Phone | In the EEA (roaming on) | Outside the EEA, or MCC 901 (roaming off) | Back in the EEA |
|---|---|---|---|
| One modem | `setGlobalSetting(DATA_ROAMING, "1")` + the kick | the restriction (writes `0` on every SIM; zero bytes) + the sink until the old session ends (§4.3) | clear the restriction, then `"1"` + the kick: **automatic** |
| Two modems (Jelly Star, likely) | needs Android's own switch on (once, by a person) | the same restriction + sink | the restriction is cleared, but the per-SIM `0` stays: **a person turns it on again** |

So on the Jelly Star the switch to "off outside the EEA" is automatic and safe, but the way back is not. Two
alternatives for the way back are open decisions (D2): leave roaming on and use "bare Vibb" outside the EEA instead of
the restriction (no manual step, but a few KB/day of our own traffic leave the phone abroad, which can start a
day price, §4.4), or a manual step in the PWA's words ("Slå på roaming igjen under Åpne for oppsett").

**The best alternative where ON is impossible:** the sink (question 2) enforces "only Vibb" outside the EEA while
roaming stays on. It is not a zero-byte guarantee: Vibb's own line home keeps going, and Android's own network checks
are not ours to stop.

## 2. Question 2: keeping only our connection

### 2.1 How the DNS filter routes today

`KidVpnService.startVpn` (launcher `server/KidVpnService.kt` ~l.174): `addAddress(192.0.2.1/32)`,
`addDnsServer(192.0.2.2)`, **one route** `addRoute(192.0.2.2, 32)`, MTU 32767, `setMetered(false)`, no
`addDisallowedApplication`. Every app's DNS goes into the TUN; every other packet never matches a VPN route and falls
through to the physical network. `AppEnforcer.applyVpnRestrictions` (~l.1394) sets always-on **without** lockdown, and
says why: with lockdown and only that route, all apps lost the internet (found live, and measured again here, §2.3 c1).
tsnet is not a VPN; it runs in the launcher's process (`TsnetClient`), so it shares the launcher's uid.

### 2.2 The combined design: one service, two shapes

Android allows one VPN. The sink is a second `Builder` shape of `KidVpnService`, not a second service:

| | Filter shape (today) | Sink shape (new) |
|---|---|---|
| Routes | `192.0.2.2/32` | `0.0.0.0/0`, `::/0` (plus a ULA `/128` address so v6 can be routed) |
| DNS server | `192.0.2.2`, filtered and relayed | `192.0.2.2`, answered `REFUSED` at once |
| Excluded apps | none (as today) | the launcher's own package (and tsnet with it); the music app per D4 |
| Read loop | DNS only | DNS -> `REFUSED`; TCP -> RST+ACK; anything else dropped |
| Always-on lockdown | off | **on**, lockdown allow list = the excluded apps besides ours (the VPN owner is exempt by itself, `Vpn.setVpnForcedLocked` ~l.2044) |
| When | Wi-Fi or Ethernet is the best non-VPN network, or no sink reason | cellular is the best non-VPN network **and** a sink reason holds (§4.2) |

- The switch is a new `establish()` on the same service. Android keeps the VPN's network (the probe's `net103` stayed
  through dns -> sink -> dns), closes the old fd, and the apps' default network never changes. Order: going to sink,
  establish the sink shape first, then lockdown on; going back, lockdown off first, then the filter shape.
- The trigger is the same `registerBestMatchingNetworkCallback(INTERNET + NOT_VPN)` the filter already holds
  (`watchNetwork`). The sink decision is a pure function of the network's transport, the roaming state, the network's
  MCC and the policy (§4.2).
- With the filter off by the parent (`vpn_filter_enabled = false`), the service still runs the sink shape when a sink
  reason holds, and stops when none does. Always-on is set while either shape is wanted.
- The launcher is not excluded in the filter shape (no change from today). Its traffic never used the TUN there
  except DNS, so the switch should move none of its TCP connections (the SSE stream, a sync in flight). Measured
  only with fresh fetches right after each switch (all fine); a long-lived connection across a shape switch is a
  build-time emulator check.

### 2.3 Tests (measured, `q2*.log`)

Probe `me.vibb.probe.sink` (device owner, the "Vibb") and `me.vibb.probe.other` (the same APK under another package,
the "other app"). URLs: `https://1.1.1.1/cdn-cgi/trace` (an IP literal, so TCP is exercised) and
`https://www.google.com/generate_204` (a name, so DNS is exercised).

**(a) Ours works, theirs fails.** Cellular, sink shape:

| Sink's answer | Probe (excluded) | Other app, IP literal | Other app, by name |
|---|---|---|---|
| RST + `REFUSED` | 200 in 36-44 ms | `ConnectException` in **1 ms** | `UnknownHostException` in **3 ms** |
| drop (read, no answer) | 200 in 39 ms | `SocketTimeoutException` after **8 s** (the probe's timeout) | `UnknownHostException` after **10 s** |
| never read the TUN | 200 in 36 ms | timeout after **8 s** | the same |

A blackhole that never reads the TUN costs nothing in our process, but every blocked app hangs until its own
timeout and retries. The RST answer makes apps fail at once and back off; it costs the read loop that the filter has
anyway. **Use the RST.**

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
instead (AOSP: lockdown blocks every uid but the VPN owner and its allow list on non-VPN networks, `Vpn.setVpnForcedLocked`
~l.2044). Not run: `am force-stop` of a device owner is refused, so the probe's VPN could not be killed (c3).

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

The read loop blocks in `read()`; it wakes only when a blocked app sends, and the radio is awake for that send in
any case. Blocked apps back off after the RST, so the count falls over time.

**(e) `NET_CAPABILITY_NOT_ROAMING` under the VPN** (roaming, both shapes): the other app's default network read
`VPN+CELL,ROAMING`, the excluded probe's `CELL,ROAMING`. `ConnectivityService.applyUnderlyingCapabilities` (~l.9455)
clears `NOT_ROAMING` on the VPN when any underlying network roams (~l.9494, ~l.9516). So `AppDownloadPlan`'s roaming
check and design 94's `data_active` read right in both shapes, excluded or not.

### 2.4 tsnet, the music app, calls and MMS

- **tsnet** is in the launcher's process and uid, so `addDisallowedApplication(ownPackage)` excludes it with the
  launcher. A tailnet phone keeps its WireGuard path in the sink shape. Its SOCKS proxy is ours too.
- **The music app** (`me.vibb.music`, its own uid) is cut by the sink unless excluded (D4). It streams; "bare Vibb" is
  for cheap traffic, so the default should be: not excluded. If the parent wants it through, it goes in both
  `addDisallowedApplication` and the lockdown allow list.
- **Calls and SMS** are not IP (CS on the emulator) or use their own IMS network, not the default network; the sink
  routes the default network's traffic. **MMS** goes over the MMS APN through `com.android.mms.service`; old reports
  say lockdown VPNs broke MMS on some builds. [needs device test] on the Jelly Star: an MMS in and out with the sink
  shape and lockdown on; if it fails, the MMS service goes on the lockdown allow list (it is never routed into our
  TUN anyway, as it binds the MMS network).

### 2.5 The alternatives, briefly

- **`DevicePolicyManager.setPreferentialNetworkServiceConfigs`** (API 33): routes chosen apps to an enterprise slice
  (`NET_CAPABILITY_ENTERPRISE`) with an optional block of the default. It needs the carrier to offer an enterprise
  network slice (5G URSP) or the device's enterprise APN; consumer SIMs in Norway don't. Not usable.
- **Per-app background restriction** (`NetworkPolicyManager.setUidPolicy`, `POLICY_REJECT_METERED_BACKGROUND`): not a
  public API; needs `MANAGE_NETWORK_POLICY`. Not usable.
- **Suspending apps** (`setPackagesSuspended`): we already do it for other reasons. It stops the app being used, not
  its jobs or sync adapters, and it hides the app. Too blunt for "no data on cellular".
- **Roaming off, with SMS as the fallback channel**: a signed command SMS (an HMAC over the command and a counter with
  a key from enrollment) could carry "lock", "locate", "pause" while there is no data. It costs 0 B of data and a few
  øre per SMS abroad, works on a ship without data, and needs the launcher's SMS role (design 77 has it). But it is one
  way unless the phone answers by SMS (more cost, and a kid's phone sending SMS abroad), it is slow, and it is a new
  attack surface (replay, spoofed senders, carrier filtering). Worth having later as a last resort beside the
  restriction; it does not replace the sink.

## 3. Question 3: cheap server traffic

### 3.1 What was measured, and how

- The debug launcher built from `main` (`f4c1b196`, `assembleDebug`), on a wiped `jelly36`, enrolled by the emulator
  loop's steps (`docs/testing/emulator.md` §4) to a local server built from the same commit (`cargo build`). The
  device row and a QR-kind setup code (device "Emma") were put in the server's SQLite directly instead of through the
  PWA; USB debugging was left allowed.
- The phone talked to `http://10.0.2.2:3102`, a counting TCP proxy in front of the server (`tap.py`, scratchpad): it
  logged every connection, every request line and the bytes each way, and kept the raw streams for the body sizes.
- Wi-Fi off, so cellular was the default network. After 5 minutes to settle, **60 minutes untouched** (the screen
  was on; nothing was opened). `dumpsys netstats --poll` then `dumpsys netstats detail` at both ends, summed per uid
  over the mobile idents.

### 3.2 Results

| Source | In 60 min | Per day |
|---|---|---|
| The launcher's uid on cellular (netstats) | 21 143 B in, 29 899 B out, 276 packets = **51.0 KB** | **1.22 MB** |
| - of which the server line (proxy, HTTP/1.1, no TLS; ~4 KB of TCP/IP headers added) | ~29 KB | ~0.69 MB |
| - of which the rest: the DNS filter's DoT upstream (`dns_upstream.in_use = provider`; the TUN carried ~20 queries from other apps in the hour) | ~22 KB | ~0.5 MB |

The server line, by request (two backstop syncs in the hour, at 13:15:44 and 13:49:03; each on a new connection,
closed by OkHttp's pool 300 s later):

| Request | Each sync, up / down (bytes, headers included) |
|---|---|
| `GET /api/devices/policy` | 219 / 3 526 (body 3 304, **not compressed**) |
| `GET /api/devices/apps` | 217 / 221 |
| `POST /api/devices/status` | 7 840 / 239 (body 7 551: `installed_apps` 4 183 B for 19 apps, `capabilities` 567, `sms_state` 450, `call_state` 422, ...) |
| SSE keepalive | 8 B of chunked `:\n\n` every 240 s, 15 an hour |

- **The policy was byte-identical** in the three syncs after the first (3 304 B each). A 304 would have answered all
  of them.
- **The report changed in one field** between the idle syncs: `time_state.used_minutes` (the emulator's screen was on,
  so screen time counted). On a phone in a pocket nothing changes.
- gzip (level 6) of the real bodies: policy 3 304 -> 1 306 B (**2.5x**), report 7 552 -> 2 958 B (**2.55x**), the
  first sync's report answer 1 291 -> 755 B.
- OkHttp 4.12 already sends `Accept-Encoding: gzip` and unpacks the answer itself, so a gzip'd policy needs no
  launcher change at all.

### 3.3 What https adds (measured on the VM with `openssl s_client`, Let's Encrypt's test hosts)

| Handshake | Read | Written |
|---|---|---|
| Full TLS 1.3, ECDSA chain (Caddy's default key; `valid-isrgrootx2`) | 2 668 B | 420 B (X25519) |
| Full TLS 1.3, RSA chain (`valid-isrgrootx1`) | 4 665 B | 1 663 B (with the hybrid PQ key share) |
| Resumed (PSK ticket) | 245 B | 739 B |

`createMdmApi` builds a new `OkHttpClient` (its own pool) for every sync (`MdmSyncWorker` ~l.106), and the pool's
connection is idle-closed after 5 minutes, so **every sync is a new TCP connection and a full handshake** (~3.6 KB
with the ECDSA chain), unless Conscrypt resumes across clients - not measured (the probe couldn't run on the managed
emulator: the launcher hides and suspends sideloaded apps, as expected). The SSE stream has its own client.
Behind Caddy (HTTP/2 by ALPN) each keepalive is ~140 B on the wire (3 B data + 9 B frame + 22 B TLS record + 52 B
TCP/IP, and the phone's 52 B ACK).

### 3.4 MB/day, before and after

Per day at 48 syncs (the 30-minute backstop), https via Caddy, the emulator's body sizes. A configured Jelly Star has
more apps (~220 B each in the report) and a fuller policy; its column is an estimate (45 apps, an 8 KB policy).

| Part | Today (emulator size) | Today (Jelly Star, est.) |
|---|---|---|
| Policy GET | 169 KB | 390 KB |
| Status POST + answer | 388 KB | 660 KB |
| Apps GET | 21 KB | 21 KB |
| TCP/IP of the syncs | 62 KB | 75 KB |
| TLS handshake per sync | 173 KB | 173 KB |
| SSE keepalives | 50 KB | 50 KB |
| **Server line** | **~0.86 MB** | **~1.37 MB** |
| DNS filter upstream (other apps' lookups) | ~0.5 MB | depends on the apps |

Each saving alone (emulator size):

| Change | Saves per day | Where |
|---|---|---|
| gzip the policy answer | 102 KB | server only (a `CompressionLayer` on the device router for JSON answers; never on the SSE route) |
| ETag + 304 for an unchanged policy | 157 KB (most of gzip's too) | both: the server hashes the served JSON, the launcher keeps the last body and sends `If-None-Match` |
| gzip the status report | 228 KB | both: `Content-Encoding: gzip` only when the policy says `status_gzip: true` (an older server never gets one); the server decompresses with `RequestDecompressionLayer` and applies `DEVICE_BODY_LIMIT` to the decompressed body |
| Report on change (a heartbeat of ~0.3 KB when nothing but clocks moved) | ~350 KB | both: the launcher hashes the report without its volatile fields, and sends the full report when the hash changes and at least every 6 h |
| One shared client for the server API (connection reuse; with Caddy's HTTP/2 the sync rides the SSE's connection) | ~190 KB | launcher: one `OkHttpClient` per server path, rebuilt only when the path or pinning changes |
| A 120-minute backstop while roaming or in "bare Vibb" | 75% of the sync parts | launcher: `backstopDelayMs` gets the plan; the SSE still brings commands at once |

**Recommended combination**: shared client + 304 + heartbeat (with gzip on the full report) + gzip on the policy
for when it did change. Per sync then ~0.3 KB up, ~0.25 KB down and ~8 packets: about **0.15 MB/day** for the server
line (48 KB of syncs, 50 KB of keepalives, ~30 KB of real changes), from ~0.86 (emulator size) or ~1.4 (Jelly Star).
Abroad with the 120-minute backstop: about **0.07 MB/day**, now mostly keepalives.

The DNS filter's own upstream (~0.5 MB/day here) is the next biggest item on an idle phone; its DoT connections
are evicted on every network change and close after a short idle on the resolver's side. Worth its own look (D7); in
the sink shape it is zero (DNS is answered locally).

## 4. The recommended whole

### 4.1 The choices, and who can flip them

In design 94's Advanced › **Mobilnett** group (one form, autosave, `#mobile-network`):

| Row | Options | Default | Who |
|---|---|---|---|
| Dataroaming | **Av (låst)** / **Tillat i EU/EØS** / **Telefonens valg** | Av (låst) | the parent (PWA) |
| Mobildata for apper | **På** / **Bare Vibb** | På | the parent (PWA) |
| Barnet kan slå av mobildata | a switch: shows a **Mobildata** row in Quick Controls (`QUICK_CONTROL_MOBILE_DATA = 32`) | off | the parent turns it on; then the kid flips it on the phone |

- The kid's row is "Mobildata: På / Av", and **Av means "bare Vibb"** (the sink): the kid can't cut our line, and the
  row says so in its ⓘ ("Vibb One holder kontakten med foreldrene"). When the parent has picked Bare Vibb, the kid's
  row shows Av and is locked.
- "Åpne for oppsett" (the pause) and the parent override lift the parent's Bare Vibb and the roaming lock, as every
  restriction (the standing rule). It does not lift the kid's own Av (it is the kid's choice).
- "Tillat i EU/EØS" is fail-closed for an older launcher: design 94's `dataRoamingLocked` treats a value it doesn't know
  as locked, so an older launcher reads `"eea"` as "Av (låst)".

### 4.2 How they combine on the phone (one pure rule)

```kotlin
data class NetState(val cellular: Boolean, val roaming: Boolean, val mcc: String?, val roamingDataActive: Boolean)

/** The lever for the network now: ROAMING_LOCK = DISALLOW_DATA_ROAMING (zero bytes); SINK = only Vibb. */
fun connectivityPlan(policy: DataPolicy, kidMobileOff: Boolean, net: NetState, lifted: Boolean): Plan {
    val outside = net.roaming && (net.mcc == null || net.mcc == "901" || net.mcc !in EEA_MCC)
    val roamingLock = !lifted && when (policy.dataRoaming) {
        "phone", null -> false
        "eea" -> outside
        else -> true                              // "off", and unknown values: locked (design 94)
    }
    val sink = net.cellular && (
        (!lifted && policy.appsMobile == "vibb") || kidMobileOff ||
        (roamingLock && net.roamingDataActive)    // design 94 §2.4.1: the session the lock can't end
    )
    // "Tillat i EU/EØS" in the EEA: ask for roaming on (setGlobalSetting("1") + the kick; one-modem phones only).
    val roamingOn = !lifted && policy.dataRoaming == "eea" && net.roaming && !outside
    return Plan(roamingLock, sink, roamingOn)
}
```

(The sketch is the shape, not the code.) `EEA_MCC` holds the EU 27 plus Iceland, Liechtenstein and Norway, and the French outermost regions' own
MCCs (340, 647, 742; EU territory). The network's MCC is `getNetworkOperator().take(3)` on the default data SIM, no
permission; 901 never counts as EEA, and it decides before the country (design 94 §2.5).

### 4.3 How our connection stays up

- The sink never covers the launcher (excluded by package, so tsnet too). In the measured switch the probe's HTTPS
  never failed.
- The roaming lock does cut it - that is its purpose (zero bytes abroad). The phone reports before the lock (the
  status report with design 94's `data_roaming` block and the new `sink` field), and the PWA says "Ingen data i <land>
  - kontakt igjen på Wi-Fi eller hjemme".
- On Wi-Fi nothing is cut: the filter shape is today's.
- The sink also closes design 94's §2.4.1 gap for every app but ours: a roaming session that outlives the lock carries
  only Vibb's few KB until Android tears it down.

### 4.4 Costs abroad, plainly

Norwegian plans charge roaming outside the EU/EEA either per MB or as a day price that starts with the first use of
data that day. Even "bare Vibb" uses a few KB a day (§3), and Android's own network checks use some more; **only the
roaming lock is zero bytes.** That is why "Tillat i EU/EØS" locks outside the EEA instead of sinking, and the PWA's
footer should say it.

## 5. The traffic plan

- **Today**: ~0.9 MB/day of server line on the emulator's small phone, ~1.4 MB/day estimated on a configured Jelly
  Star, plus ~0.5 MB/day of the DNS filter's upstream: the user's 1-3 MB/day estimate holds (measured 1.2 MB/day for
  the launcher's uid on the emulator, over plain http).
- **Step 1, server only (any launcher)**: gzip the device answers -> -0.1 MB/day (-0.25 on the Jelly Star).
- **Step 2, both**: 304 on the policy, the shared client -> another -0.35 MB/day.
- **Step 3, both**: heartbeat reports with gzip'd full reports, the slower backstop abroad -> about 0.15 MB/day at
  home, 0.07 MB/day abroad.
- **Not changed**: the SSE keepalive stays at 240 s (Caddy's `write_idle` and carriers' NAT timers; DEPLOY.md). It
  is ~50 KB/day and becomes the largest part.

## 6. How design 94 changes

1. **The policy value set** grows: `data_roaming` in `off | eea | phone`. Put `eea` in 94's migration `CHECK` now
   (`CHECK (data_roaming IN ('off','eea','phone'))`), so 95 needs no table rebuild; the PWA offers it only once a
   phone claims `data_roaming_v2`.
2. **§2.4.1 (the running session) and Q4** get a real answer: option (d), the sink while `locked && data_active`.
   It cuts every app but Vibb at once; Android ends the session later as before. Q4's (b) (a reboot button) is no
   longer needed.
3. **The report** gains `modems` (`getActiveModemCount()`), `mcc` (already in `network`), and `sink`
   (`"off" | "parent" | "kid" | "roaming"`): the PWA can say why apps have no data, and whether "Tillat i EU/EØS" can
   turn roaming back on by itself (one modem) or needs a person (two).
4. **A risk to add to §10**: national roaming. A Norwegian SIM on another Norwegian network (Ice on Telia where Ice
   has no coverage, for one) may read `roaming = true` with MCC 242; "Av (låst)" then cuts data at home. "Tillat i
   EU/EØS" doesn't (242 is EEA). [needs device test] with an Ice SIM; the report's `network` shows it.
5. Nothing else in 94 changes; it can be built as it stands, with the CHECK widened.

## 7. Risks

- **Dual-SIM way back** (§1.4): on the Jelly Star roaming stays off after a trip outside the EEA until a person turns
  it on. Stated in the PWA, and D2 has the alternative.
- **Lockdown and MMS** (§2.4): [needs device test].
- **The sink is not zero bytes**: Vibb's line home and Android's own network validation still use cellular.
- **The RST is ours to get right**: a wrong checksum means apps hang instead of failing fast (the probe's code is in
  the scratchpad; the launcher's version gets a unit test with captured packets).
- **IPv6 only networks / 464XLAT**: the sink routes `::/0` too; tested only on the emulator's IPv4 cellular.
- **A VPN that dies in the sink shape with lockdown on** leaves every app but Vibb offline - which is the sink's state
  anyway. The filter shape keeps lockdown off, so a dead filter never takes the internet with it (as today).
- **Carriers' roaming flags** (design 94 §10) and national roaming (§6 item 4).

## 8. Build steps and sizes

1. **95-T (traffic), both directories, M.** (a) server: gzip on the device router's JSON answers (not the SSE route), `ETag`/304 on
   `GET /api/devices/policy`, `RequestDecompressionLayer` with the body limit after decompression, the policy flag
   `status_gzip`; (b) launcher: one shared `OkHttpClient` per server path, `If-None-Match` with the kept policy body,
   gzip'd reports when the flag says so, the heartbeat (`status_heartbeat_v1`: a report with only `heartbeat: true`,
   the hash and the volatile fields, the full one every 6 h or on a change), the slower backstop under the plan; a
   contract file `server/testdata/status_heartbeat.json`. Step (a)'s gzip can ship alone first (S).
2. **95-S (the sink), launcher M-L, server S.** The second shape in `KidVpnService` (the RST/REFUSED answers, the shape
   switch, lockdown with the shape, the always-on rule when the filter is off), `connectivityPlan`, the policy key
   `apps_mobile` (`"on" | "vibb"`), the Quick Controls bit and row, the report's `sink`; the PWA rows; a contract file
   `server/testdata/connectivity.json` (policy rows, report, plan vectors) byte-identical in the launcher's test
   resources. Emulator check as §2.3, plus the Jelly Star MMS check.
3. **95-R (Tillat i EU/EØS), both directories, M.** After 94 is built. `EEA_MCC`, the network's MCC watch (the best
   network callback plus `TelephonyCallback.ServiceStateListener` for an MCC change without a network change), the
   one-modem ON path (`setGlobalSetting` + the kick, at most once per network change), the restriction outside the
   EEA, the sink for the lingering session, `data_roaming_v2`, the PWA's third option and words.

## 9. Open decisions for the user

- **D1** **D1. The traffic changes, and in which order.** Recommended: gzip of the device answers now (server only, S);
  then the shared client and the 304 (S-M, both); then the heartbeat report with gzip (M, both). The heartbeat changes
  what "last report" means in the PWA (the summary's "siden" line keeps working: a heartbeat is a report).
- **D2. The way back into the EEA on a dual-SIM phone.** (a) The restriction outside the EEA (zero bytes), and the
  parent turns roaming on again during "Åpne for oppsett" (recommended: it is the only zero-byte choice); or (b)
  roaming stays on and outside the EEA the phone runs "bare Vibb" (no manual step, a few KB/day abroad, which can
  start a day price).
- **D3. Does the pause lift the parent's "Bare Vibb"?** Recommended yes (the standing rule), as it lifts the roaming
  lock.
- **D4. Does "Vibb" include the music app?** Recommended no: "bare Vibb" is for cheap traffic; the music app streams.
  A later per-app row is possible (the exclusion and the lockdown allow list take a list).
- **D5. EEA list edges.** Switzerland and the UK are not EEA; some Norwegian plans include them. Recommended: the
  fixed EEA list now, with CH/GB as a later per-phone tick if asked.
- **D6. A kid's Mobildata switch**: in Quick Controls behind the parent's tick (recommended), or not at all.
- **D7. The DNS filter's upstream cost** (~0.5 MB/day on the emulator, from other apps' lookups): look at keeping DoT
  connections longer or a larger answer cache, as its own small item. Not part of this design's steps.
- **D8. "Bare Vibb" on a schedule** (nights, school hours) through the time rules: possible later with the same
  plan input; not asked for. Recommended: not now.
