# 03: Full Tailscale for the kid (Tailscale app mode)

Status: design, 2026-10-04. Direction from PLAN.md "Full Tailscale for the kid". `L` = `kids-launcher-mdm` (paths under
`app/src/main/java/com/kidslauncher/mdm/`), `S` = `kid-phone-server`, `TA` = `tailscale/tailscale-android@6d63d3e`
(2026-10-01), `AOSP` = `aosp-mirror/platform_frameworks_base` `main`. Tags: **[verified: source]**, **[belief]**,
**[device]** = must be tested on the Jelly Star.

## 1. Facts this rests on

| Fact | Source |
|---|---|
| Android managed-config keys the app reads: `ForceEnabled`(bool), `AuthKey`, `Hostname`, `Tailnet`, `ExitNodeID`, `UseTailscaleDNSSettings`/`UseTailscaleSubnets`(always/never/user-decides), `ExitNodesPicker`/`RunExitNode`/`ManageTailnetLock`/`OnboardingFlow`(show/hide), `HiddenNetworkDevices`(string array), `ExcludedPackageNames`/`IncludedPackageNames`(comma list), `ManagedBy*`, `PostureChecking`, `HardwareAttestation` | TA `res/xml/app_restrictions.xml`, `mdm/MDMSettings.kt:30-121` [verified]; tailscale.com/kb/1315/mdm-keys (Hostname needs 1.80+) |
| Keys not in `MDMSettings` are ignored: the app answers the Go backend only from `MDMSettings.allSettingsByKey`. So `AlwaysOn.Enabled` and `ReconnectAfter` (documented for Android, ForceEnabled "deprecated in 1.84") **do nothing on Android today** | TA `App.kt:437-438` [verified]; kb/1315 |
| `ForceEnabled` only hides the in-app toggle and the notification's disconnect action. The Quick Settings tile stops the VPN without checking it, and the exported `IPNReceiver` stops it on a `com.tailscale.ipn.DISCONNECT_VPN` broadcast from any app | TA `ui/view/MainView.kt:154`, `IPNService.kt:81,161`, `QuickToggleService.java:82-90`, `IPNReceiver.java:49-56`, manifest `exported="true"` [verified]. Upstream saw the tile bypass live (`git show cdb4835`, old applyVpnRestrictions doc) |
| User split tunnelling (per-app exclusions picked in the app) applies **unless** MDM sets `IncludedPackageNames` or `ExcludedPackageNames` non-empty. Always excluded: Google Messages (RCS), Android Auto, Chromecast and a few others; a missing package is logged and skipped | TA `IPNService.kt:191-249`, `App.kt:820-834` [verified] |
| Runtime config changes: the app listens for `ACTION_APPLICATION_RESTRICTIONS_CHANGED` while its process runs, and reloads in `MainActivity` | TA `App.kt:118-120`, `mdm/MDMSettingsChangedReceiver.kt` [verified] |
| Started by always-on (`android.net.VpnService` action) the app sets `WantRunning=true`; `ACTION_STOP_VPN` sets it false | TA `IPNService.kt:48-90` [verified] |
| The VPN's DNS servers are the tailnet's `Nameservers` (100.100.100.100 when MagicDNS/override is on) | TA `libtailscale/net.go:111-125` [verified] |
| "Override DNS servers": devices "always use the global nameservers defined for the tailnet"; without it devices prefer local DNS | tailscale.com/kb/1054/dns [verified: docs] |
| Tagged devices: key expiry disabled by default; auth keys can carry tags | tailscale.com/kb/1068/tags [verified: docs] |
| `setAlwaysOnVpnPackage` pre-authorizes the package (no consent dialog) and starts it; restart happens only on set, user unlock and package add/replace - **not after the app stops its own VPN** | AOSP `Vpn.java:1011-1020`, `VpnManagerService.java:594-617,897-919` [verified] |
| `startAlwaysOnVpn` is a no-op while the VPN is connected, so re-calling `setAlwaysOnVpnPackage` is a safe "kick" | AOSP `Vpn.java:1117-1140` [verified] |
| While always-on is set, no other package can prepare a VPN | AOSP `Vpn.java:1237-1240` [verified] |
| `DISALLOW_CONFIG_VPN`: "also prevents VPNs from starting. However ... the system does start always-on VPNs created by the device or profile owner"; it also blocks network reset from clearing always-on | AOSP `UserManager.java` javadoc, `VpnManagerService.java:967-968` [verified]. Re-`establish()` by Tailscale on route changes under it: [device] |
| Lockdown without an exit node drops all non-tailnet traffic | tailscale/tailscale#12925; L `AppEnforcer.kt:420-447` [verified] |
| A split-route VPN's DNS servers serve all lookups of covered apps: KidVpnService relies on exactly this (one host route + `addDnsServer`) and works live | L `KidVpnService.kt:84-93`, CLAUDE.md [verified]; same for Tailscale: [device] |
| APKs: GitHub releases of `tailscale/tailscale-android` carry `tailscale-android-universal-<ver>.apk`; F-Droid also ships it | GitHub API, F-Droid API 2026-10-04 [verified] |

## 2. Decisions

1. Per-device `network_mode`: `tsnet` (today: embedded tsnet + KidVpnService, default) or `tailscale_app`.
2. App mode: always-on = `com.tailscale.ipn`, **lockdown false**, no exit node, `DISALLOW_CONFIG_VPN` on (both modes).
3. Filtering moves to AdGuard Home via tailnet DNS (Override on). KidVpnService stays as the **fallback** (§3.5).
4. The launcher talks to the server directly over the tailnet, no SOCKS, at a new **device-only port** (§6) by tailnet IP.
5. Override PIN / pause in app mode keeps the tailnet (so sync and the "ends on next sync" rule keep working) but
   switches Tailscale DNS off (`UseTailscaleDNSSettings=never`) - unfiltered, like tsnet mode's override.

## 3. Launcher

**3.1 Pure plan** (new `server/NetworkPlan.kt`, no `android.*`, like `EnforcementPlan.kt`):
```kotlin
enum class NetMode { TSNET, TAILSCALE_APP }
data class NetInput(val desired: NetMode, val tailscaleVersionCode: Long?, val overrideActive: Boolean,
    val vpnFilterEnabled: Boolean, val tailscaleUp: Boolean, val downSinceMs: Long?, val fallbackSinceMs: Long?,
    val lastProbeMs: Long?, val nowMs: Long)
data class NetPlan(val alwaysOn: String?, val runKidVpn: Boolean, val runTsnet: Boolean,
    val tailscaleConfig: Map<String, Any>?, val effective: NetMode, val state: String)   // state -> status report
fun decideNetPlan(i: NetInput): NetPlan
fun tailscaleRestrictions(hostname: String, authKey: String, tailnet: String?, dnsOn: Boolean): Map<String, Any>
```
Rules: TSNET → today's behaviour (`AppEnforcer.kt:216-217,457-474`). TAILSCALE_APP with Tailscale missing or older than
1.80 → TSNET, state `tailscale_missing`. Down > 10 min (not override) → fallback = TSNET + KidVpnService (if
`vpnFilterEnabled`), state `fallback`; every 60 min a probe sets always-on back to Tailscale for 2 min, success ends
the fallback. Override → Tailscale stays, `dnsOn=false`.

**3.2 Tailscale config** (`dpm.setApplicationRestrictions(admin, "com.tailscale.ipn", bundle)`, full bundle each time,
it replaces rather than merges - upstream's old note, `git show cdb4835` AppEnforcer):
`AuthKey`=pref `tailscaleAuthKey` (tagged, reusable, pre-approved key; Q1), `Hostname`=policy `tailscale_hostname`,
`Tailnet`=tailnet name (if set), `ForceEnabled`=true, `UseTailscaleDNSSettings`=`always` (`never` under override),
`ExitNodesPicker`/`RunExitNode`/`ManageTailnetLock`=`hide`, `OnboardingFlow`=`hide`,
`HiddenNetworkDevices`=[current-user, other-users, tagged-devices], `ExcludedPackageNames`=`invalid.kidslauncher.none`
(non-empty so the kid's own split-tunnel picks are ignored; never list our package), `ExitNodeID` unset,
`ManagedByOrganizationName`="Family". Whether `AuthKey` re-logs in after a logout in the app UI: [device].

**3.3 Always-on and guards** (`applyVpnRestrictions` takes the `NetPlan`): `setAlwaysOnVpnPackage(admin, plan.alwaysOn,
false)` (switching to Tailscale revokes KidVpnService: `onRevoke`, `KidVpnService.kt:216-221`); `DISALLOW_CONFIG_VPN`
set unless override/pause (CLAUDE.md rule: the PIN lifts every restriction); in app mode `setUninstallBlocked(tailscale)`
and Tailscale in `EnforcementPlan.neverRestrict` (`EnforcementPlan.kt:86`) - hiding it would kill the VPN, as upstream
already guarded (`requireTailscale` skip removed in cdb4835). Whether to *suspend* it (blocks its UI) depends on
whether the VPN survives suspension [device]; not on Home (not allowlisted; see §6 auto-allowlist exception).
**Watchdog** (new `TailnetWatchdog`): `ConnectivityManager.registerNetworkCallback` for `TRANSPORT_VPN`; on loss in app
mode, after 15 s re-call `setAlwaysOnVpnPackage(tailscale)` (restarts it, §1) - covers the QS tile and the
`DISCONNECT_VPN` broadcast. "Up" = a VPN network exists whose `LinkProperties.dnsServers` contains 100.100.100.100
[device: readable for another app's VPN] and the last direct sync succeeded.
Gate the unconditional `KidVpnService.start` on the effective mode: `Application.kt:250-252`,
`PackageReplacedReceiver.kt:37-39` (it would fail soft anyway, `Vpn.java:1237-1240`, but must not try).

**3.4 Server connection.** App mode: `TsnetClient` not started (gate `HomeActivity.kt:188`, `MdmSyncWorker.kt:71`,
`Provisioning.kt:35`), `TsnetClient.close()` once a direct sync succeeded, and no proxy in `createMdmApi`
(`MdmApi.kt:104`) / `CommandListenerService.kt:90`: pass a `useProxy` flag instead of reading `TsnetClient.proxy()`
globally. tsnet state dir is kept for the fallback. Base URL: policy `device_api_url` (e.g. `http://100.x.y.z:8444`)
is adopted into a new pref only after a `GET api/devices/policy` against it succeeds; the old `serverUrl` stays as
fallback (pure `chooseBaseUrl(current, offered, probeOk)`). By IP + plain HTTP (WireGuard-encrypted, cleartext is
already allowed, `AndroidManifest.xml:94`) so the control channel needs neither MagicDNS nor AdGuard.

**3.5 Tailscale not connected / logged out.** Nothing unlocks: policy decisions use the cached policy
(`PolicyGate.kt:203`, UNREACHABLE keeps it), calls/SMS rules are local. Lost: server contact (no new policy, commands,
status) and **DNS filtering** - Android uses the underlying network's resolver. Fallback (§3.1) bounds that gap to
~10 min plus 2-min probes; without it the gap lasts until Tailscale is back. AdGuard down while Tailscale is up:
lookups fail (fail closed) for the kid; MagicDNS names still answer from 100.100.100.100; the override PIN frees DNS.

**3.6 Install/update.** S tracked app (`migrations/0006`,`0008`): GitHub `tailscale/tailscale-android`, asset pattern
`tailscale-android-universal-*.apk`; package replace restarts always-on (AOSP `VpnManagerService.java:897`).
If the phone has a Play-installed Tailscale, signatures may differ [device: `apksigner verify --print-certs` vs
`dumpsys package com.tailscale.ipn`]; pick one source and suspend Play updates for it.

## 4. DNS and AdGuard Home

- Tailnet DNS: MagicDNS on, global nameserver = Pi's tailnet IP, **Override DNS servers on**. This is tailnet-wide:
  parents' devices also use AdGuard (they can untick "Use Tailscale DNS"; Q3). On the Pi: `tailscale set
  --accept-dns=false` so AdGuard never resolves through itself.
- Without an exit node, Tailscale's VPN only routes 100.64.0.0/10 + its IPv6 range, but its DNS server should serve all
  lookups (same mechanism as KidVpnService) [device: check 1]. Private DNS stays locked to opportunistic
  (`AppEnforcer.kt:490-500`); DoT to 100.100.100.100:853 must fail and fall back to port 53 [device: check 2]. Strict
  mode is what the lock prevents; keep it in both modes.
- AdGuard Home (Pi, `apt`/docker): DNS on the tailnet IP :53, web UI on 127.0.0.1 behind `tailscale serve
  --https=8445` (parents only via ACL). Upstreams DoT/DoH with bootstrap IPs (mirror `dns_upstream_provider`).
  Per-device: persistent client per kid keyed by its Tailscale IP (stable per node; changes on re-login) - so make
  the **default** client settings the kid level and give parents' IPs explicit lighter clients. Blocklists: copy the
  feeds from S `/dns`; safe search on. The query log replaces `device_dns_events` for app-mode devices.
  Source IP seen by AdGuard = the phone's tailnet IP [device: check 1].
- Bypasses unchanged from today: apps with hard-coded DoH/DNS; Tailscale's built-in excluded apps (RCS).

## 5. Tailnet policy (sample)

```jsonc
{ "tagOwners": { "tag:kid": ["autogroup:admin"] },
  "hosts": { "pi": "100.64.0.10" },
  "grants": [
    { "src": ["autogroup:member"], "dst": ["*"], "ip": ["*"] },             // parents: everything
    { "src": ["tag:kid"], "dst": ["pi"], "ip": ["tcp:8444", "udp:53", "tcp:53", "tcp:<immich>", "tcp:8443"] } ],
  "tests": [ { "src": "tag:kid", "accept": ["pi:8444", "pi:53"], "deny": ["pi:443", "pi:8445", "pi:22"] } ] }
```
`8443` was for MollySocket (removed 2026-10-06 - drop it from the ACL). No `autogroup:internet` for
`tag:kid`, so exit nodes are unusable. ACLs are L3/L4: path filtering on 443 is impossible, hence the port split.
The tsnet node uses the same tagged key, so **all** devices must use the 8444 URL before this policy goes live.

## 6. Server

- **Second listener**: today one `BIND_ADDR` (`main.rs:63,129-136`) serves admin + device routes from one router
  (`main.rs:145-456`), exposed by `tailscale serve --https=443` (S DEPLOY.md:15-27). Add `DEVICE_BIND_ADDR`
  (default unset; e.g. `127.0.0.1:3101`) serving `build_device_router(state)` = the device routes of `main.rs:398-448`
  only (no session layer, no `/static`), both via `tokio::try_join!`. Expose with `tailscale serve --bg --http=8444
  http://127.0.0.1:3101` [belief: flag]. The admin port keeps device routes for old URLs.
  (Zero-code interim: `tailscale serve --https=8444 --set-path=/api/devices ...` [belief: path handling].)
- Migration `0023_network_mode.sql`: `device_policy.network_mode TEXT NOT NULL DEFAULT 'tsnet' CHECK (network_mode
  IN ('tsnet','tailscale_app'))`; `provisioning_settings.device_api_url TEXT NOT NULL DEFAULT ''`;
  `device_status.network_state TEXT`. (0005's columns were dropped by 0012; nothing to reuse.)
- Policy (`device_api.rs:200-219`, `models.rs:291`): `network_mode`, `device_api_url` (null if blank),
  `tailscale_hostname` (`kid-<slug of device name>`). Always present (pattern of QA blocker 3); keys test
  `tests/device_api.rs:310-337`. Status (`models.rs:353-376`): `network_state` JSON (mode desired/effective, state,
  Tailscale version, always-on package, last direct sync); capability `tailscale_app_v1` (like `calls.rs:307`).
- PWA (`device_detail.html:132-137`, `devices.rs:775,823-830`): "Network" radio tsnet / Tailscale app; the filter
  checkbox text explains it means "fallback filter" in app mode. Warnings: no `tailscale_app_v1`; Tailscale tracked
  app not selected/not reported installed; `device_api_url` blank; `network_state.state` != `ok`. Selecting app mode
  selects the Tailscale tracked app **without** allowlisting it (exception to S CLAUDE.md:55 auto-allowlist).
  Settings page (`settings.rs:38-77`): `device_api_url` field next to `server_url`.

## 7. Mode switch and migration

Existing devices: nothing changes (`tsnet` default). Rollout: (1) S second port + `device_api_url`; (2) launcher adopts
it in both modes (tsnet reaches 100.x via SOCKS too); (3) confirm every device reports the new URL, then the ACL.
Switch tsnet→app: policy says app → launcher pushes the config, installs/keeps Tailscale, sets always-on to Tailscale,
keeps tsnet until the first direct sync, then closes it (state `ok`). No direct sync in 10 min → fallback (§3.1).
App→tsnet: always-on back to own package, KidVpnService, tsnet; Tailscale config reset to `ForceEnabled=false`, app
left installed. New phones: QR as today; the server URL in the QR should already be the 8444 one.

## 8. Unit tests (JVM / cargo)

L: `NetworkPlanTest` (each rule of §3.1 incl. override, missing/old Tailscale, 10-min boundary, probe cadence,
filter off), `tailscaleRestrictions` exact key/value map (no exit node key, non-empty exclusion, DNS never under
override), `chooseBaseUrl`, `EnforcementPlan` keeps Tailscale in `neverRestrict` in app mode, status JSON shape.
S: device router 404s `/devices`, `/login`, `/static/..`; policy keys always present; `network_mode` CHECK; warning
computation; Tailscale tracked-app auto-select does not allowlist.

## 9. Device checklist (Jelly Star, app mode)

1. `dumpsys connectivity`: Tailscale VPN, DNS 100.100.100.100, no default route. Browser, Play, a WebView app: queries
   appear in AdGuard under the phone's 100.x IP; a blocked domain fails. 2. Private DNS = opportunistic, still logged.
3. QS tile off and `adb shell am broadcast -a com.tailscale.ipn.DISCONNECT_VPN -n com.tailscale.ipn/.IPNReceiver`:
   back within ~30 s. 4. Log out in the app: AuthKey re-login? new IP? 5. Suspend Tailscale (debug): VPN survives?
6. Reboot: VPN after unlock, first sync time. 7. Toggle Wi-Fi/cell under `DISALLOW_CONFIG_VPN`: tunnel re-establishes;
Settings > VPN locked. 8. Tailscale update from the server catalog installs and reconnects. 9. Revoke the auth node in
the admin console: fallback within 10 min, KidVpnService filters, recovery after re-auth. 10. Override PIN: DNS
unfiltered, sync still works, override clears. 11. From the phone: 443/8445 unreachable, 8444 + Immich OK, no exit
nodes listed. 12. Calls/SMS/RCS normal; overnight battery vs tsnet mode.

## 10. Tasks (ordered)

1. [device, no code] Spike on a spare non-DO phone: Tailscale + AdGuard + Override; checks 1, 2, 3, 4.
2. [local S] Device listener + router + tests. 3. [local S] Migration 0023, policy/status fields, tests.
4. [local L] `chooseBaseUrl` + adopt `device_api_url` in both modes. 5. [device] Deploy S, serve 8444, devices adopt.
6. [local L] `NetworkPlan` + `tailscaleRestrictions` + tests. 7. [local L] `applyVpnRestrictions` per plan,
   `DISALLOW_CONFIG_VPN`, uninstall block, `neverRestrict`, tsnet/proxy gating, KidVpnService start gating.
8. [local L] Watchdog + fallback + `network_state` report. 9. [local S] PWA toggle, warnings, settings field.
10. [tailnet] AdGuard Home, DNS override, tagged key, ACL (§5, after 5). 11. [device] Checklist §9.
12. [local L] CLAUDE.md: app mode, the ForceEnabled/QS-tile limits, fallback.

## Open questions

Q1 One tagged reusable key for tsnet and the app, or a separate key? Q2 Fallback to KidVpnService (recommended) or
accept the gap? Q3 Parents' devices: AdGuard light client or "Use Tailscale DNS" off? Q4 Tailscale from GitHub or Play?

## User input 2026-10-05: temporary VPN pause

Turning Tailscale off for a while must be possible (plane/hotel Wi-Fi captive portals, networks
that need the VPN off while connecting). Design change:

- Per-device server setting "kid may pause VPN" (on/off) and a max duration (default 30 min).
- The pause is started from the launcher's own Quick Controls (not by fighting the watchdog):
  the launcher stops pushing always-on, lets the user disconnect, and the watchdog resumes
  enforcement when the window ends or the device reconnects to a known network, whichever
  comes first. Reported in the status report; shown on the device page.
- With the setting off, the parent can still grant a pause remotely from the PWA or via the
  override PIN.
- Nothing else unlocks during a pause (app/call rules unchanged); DNS filtering is off for the
  window.
- Whether Android's captive-portal login already works with always-on (no lockdown) Tailscale
  on the Jelly Star, which would make the pause rarely needed: [needs device test].
